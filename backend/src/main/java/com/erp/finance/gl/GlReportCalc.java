package com.erp.finance.gl;

import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报表取数公式引擎（总账 M5 建，M6 三表复用）：
 * QM/QC 期末/期初余额（按科目余额方向带号）、FSD/FSC 本期借/贷发生、
 * LJFS/LJFSC 本年累计借/贷发生、CF/CFY 现金流量项目本月/本年累计金额。
 * 函数先展开为数值字面量，再交给零依赖的 GlExprEngine 做四则/比较运算
 * （函数白名单即本类的正则，模板引擎的"禁函数"防线不受影响）。
 * 实时聚合：期初取 fin_init_balance，发生取已过账/已冲销凭证分录，父级科目按 4-2-2-2 前缀汇总。
 */
@Service
public class GlReportCalc {

    private final JdbcTemplate jdbc;
    private final SysParamService sysParam;

    /** code -> 科目行（account_name/balance_direction）。 */
    private final Map<String, Map<String, Object>> accounts = new LinkedHashMap<>();
    /** code -> [openD, openC, ytdD, ytdC]（父级已汇总）。 */
    private final Map<String, BigDecimal[]> initAgg = new HashMap<>();
    /** code -> period(yyyyMM) -> [debit, credit]（父级已汇总）。 */
    private final Map<String, Map<String, BigDecimal[]>> postedAgg = new HashMap<>();
    /** cashFlowItem -> period -> [debit, credit]。 */
    private final Map<String, Map<String, BigDecimal[]>> cfAgg = new HashMap<>();
    /** cashFlowItem -> 方向（流入/流出）。 */
    private final Map<String, String> cfDirection = new HashMap<>();

    private boolean loaded = false;

    public GlReportCalc(JdbcTemplate jdbc, SysParamService sysParam) {
        this.jdbc = jdbc;
        this.sysParam = sysParam;
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        for (Map<String, Object> a : TmsUtil.queryCamel(jdbc,
                "SELECT account_code, account_name, account_type, balance_direction " +
                        "FROM fin_account ORDER BY account_code")) {
            accounts.put(TmsUtil.str(a.get("accountCode")), a);
        }
        // 期初（末级 → 前缀汇总）
        Map<String, BigDecimal[]> leafInit = new HashMap<>();
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc,
                "SELECT account_code, SUM(open_debit) open_debit, SUM(open_credit) open_credit, " +
                        "SUM(ytd_debit) ytd_debit, SUM(ytd_credit) ytd_credit FROM fin_init_balance GROUP BY account_code")) {
            leafInit.put(TmsUtil.str(r.get("accountCode")), new BigDecimal[]{
                    nz(r.get("openDebit")), nz(r.get("openCredit")), nz(r.get("ytdDebit")), nz(r.get("ytdCredit"))});
        }
        rollupFlat(leafInit, 4, initAgg);
        // 已过账发生（已冲销凭证留账与红字轧差）
        Map<String, Map<String, BigDecimal[]>> leafPosted = new HashMap<>();
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc,
                "SELECT e.account_code, v.period, SUM(e.debit_amount) debit_amount, SUM(e.credit_amount) credit_amount " +
                        "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                        "WHERE v.status IN ('已过账','已冲销') GROUP BY e.account_code, v.period")) {
            leafPosted.computeIfAbsent(TmsUtil.str(r.get("accountCode")), k -> new HashMap<>())
                    .put(TmsUtil.str(r.get("period")), new BigDecimal[]{nz(r.get("debitAmount")), nz(r.get("creditAmount"))});
        }
        rollupPosted(leafPosted, postedAgg);
        // 现金流量项目发生额
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc,
                "SELECT e.cash_flow_item item, v.period, SUM(e.debit_amount) debit_amount, SUM(e.credit_amount) credit_amount " +
                        "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                        "WHERE v.status IN ('已过账','已冲销') AND e.cash_flow_item IS NOT NULL " +
                        "GROUP BY e.cash_flow_item, v.period")) {
            cfAgg.computeIfAbsent(TmsUtil.str(r.get("item")), k -> new HashMap<>())
                    .put(TmsUtil.str(r.get("period")), new BigDecimal[]{nz(r.get("debitAmount")), nz(r.get("creditAmount"))});
        }
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc,
                "SELECT item_code, direction FROM fin_cash_flow_item")) {
            cfDirection.put(TmsUtil.str(r.get("itemCode")), TmsUtil.str(r.get("direction")));
        }
    }

    /**
     * 强制下次请求重新加载（结账/过账后同进程内复用单例时用）。
     * 必须同时清空聚合 Map：rollup 是累加写入，不清空会导致重载后金额翻倍。
     */
    public void invalidate() {
        loaded = false;
        accounts.clear();
        initAgg.clear();
        postedAgg.clear();
        cfAgg.clear();
        cfDirection.clear();
    }

    // ==================== 取数函数 ====================

    /** 本期借方发生额（period 单期）。 */
    public BigDecimal fsd(String code, String fromPeriod, String toPeriod) {
        ensureLoaded();
        return sumPosted(code, fromPeriod, toPeriod, 0);
    }

    /** 本期贷方发生额。 */
    public BigDecimal fsc(String code, String fromPeriod, String toPeriod) {
        ensureLoaded();
        return sumPosted(code, fromPeriod, toPeriod, 1);
    }

    /** 本年累计借方发生（year 的 01 期 ~ toPeriod）。 */
    public BigDecimal ljfs(String code, String year, String toPeriod) {
        return fsd(code, year + "01", toPeriod);
    }

    /** 本年累计贷方发生。 */
    public BigDecimal ljfsc(String code, String year, String toPeriod) {
        return fsc(code, year + "01", toPeriod);
    }

    /** 期初余额（period 初），按科目余额方向带号；科目不存在返回 0。 */
    public BigDecimal qc(String code, String period) {
        ensureLoaded();
        return balance(code, null, periodBefore(period));
    }

    /** 期末余额（period 末），按科目余额方向带号。 */
    public BigDecimal qm(String code, String period) {
        ensureLoaded();
        return balance(code, null, period);
    }

    /** 现金流量项目金额：流入取借方、流出取贷方（恒为正）。 */
    public BigDecimal cf(String itemCode, String fromPeriod, String toPeriod) {
        ensureLoaded();
        BigDecimal d = sumCf(itemCode, fromPeriod, toPeriod, 0);
        BigDecimal c = sumCf(itemCode, fromPeriod, toPeriod, 1);
        return "流出".equals(cfDirection.get(itemCode)) ? c : d;
    }

    /** 现金流量项目本年累计。 */
    public BigDecimal cfy(String itemCode, String year, String toPeriod) {
        return cf(itemCode, year + "01", toPeriod);
    }

    // ==================== 公式展开 + 求值 ====================

    /** 函数白名单正则：QM/QC/FSD/FSC/LJFS/LJFSC/CFY/CF，参数可为 '1001' 或 1001。 */
    private static final Pattern FUNC = Pattern.compile(
            "(LJFS|LJFSC|QM|QC|FSD|FSC|CFY|CF)\\s*\\(\\s*'?([A-Za-z0-9]+)'?\\s*\\)");

    /** 把报表公式中的函数替换为数值字面量（单期口径：FSD/FSC/CF 取 period 当月，LJ/CFY 取年累）。 */
    public String expand(String expr, String period) {
        if (expr == null || expr.isEmpty()) return "0";
        String year = period.substring(0, 4);
        Matcher m = FUNC.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String fn = m.group(1);
            String arg = m.group(2);
            BigDecimal v = switch (fn) {
                case "QM" -> qm(arg, period);
                case "QC" -> qc(arg, period);
                case "FSD" -> fsd(arg, period, period);
                case "FSC" -> fsc(arg, period, period);
                case "LJFS" -> ljfs(arg, year, period);
                case "LJFSC" -> ljfsc(arg, year, period);
                case "CF" -> cf(arg, period, period);
                case "CFY" -> cfy(arg, year, period);
                default -> BigDecimal.ZERO;
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(v.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 金额公式求值（结果 2 位小数）。 */
    public BigDecimal evalAmount(String expr, String period) {
        return GlExprEngine.evalAmount(expand(expr, period), new HashMap<>());
    }

    /** 条件公式求值；vars 可带 taxpayer 等业务变量。 */
    public boolean evalCondition(String expr, String period, Map<String, Object> vars) {
        return GlExprEngine.evalCondition(expand(expr, period), vars == null ? new HashMap<>() : vars);
    }

    // ==================== 内部聚合 ====================

    private BigDecimal sumPosted(String code, String from, String to, int idx) {
        BigDecimal sum = BigDecimal.ZERO;
        Map<String, BigDecimal[]> per = postedAgg.get(code);
        if (per == null) return BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> e : per.entrySet()) {
            if (e.getKey().compareTo(from) >= 0 && e.getKey().compareTo(to) <= 0)
                sum = sum.add(e.getValue()[idx]);
        }
        return sum;
    }

    private BigDecimal sumCf(String item, String from, String to, int idx) {
        BigDecimal sum = BigDecimal.ZERO;
        Map<String, BigDecimal[]> per = cfAgg.get(item);
        if (per == null) return BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> e : per.entrySet()) {
            if (e.getKey().compareTo(from) >= 0 && e.getKey().compareTo(to) <= 0)
                sum = sum.add(e.getValue()[idx]);
        }
        return sum;
    }

    /**
     * 科目余额（截至 endPeriod，含 endPeriod；endPeriod 为上期时即期初）。
     * 借方向科目返回 借−贷；贷方向返回 贷−借；未配置科目按借方处理。
     */
    private BigDecimal balance(String code, String from, String endPeriod) {
        BigDecimal[] init = initAgg.get(code);
        BigDecimal openD = init == null ? BigDecimal.ZERO : init[0];
        BigDecimal openC = init == null ? BigDecimal.ZERO : init[1];
        BigDecimal d = openD, c = openC;
        Map<String, BigDecimal[]> per = postedAgg.get(code);
        if (per != null) {
            for (Map.Entry<String, BigDecimal[]> e : per.entrySet()) {
                if (e.getKey().compareTo(endPeriod) <= 0) {
                    d = d.add(e.getValue()[0]);
                    c = c.add(e.getValue()[1]);
                }
            }
        }
        Map<String, Object> acc = accounts.get(code);
        boolean creditSide = acc != null && "贷".equals(TmsUtil.str(acc.get("balanceDirection")));
        return creditSide ? c.subtract(d) : d.subtract(c);
    }

    /** 上一会计期间 yyyyMM（1 月返回上年 12，仅用于比较，无需存在）。 */
    private static String periodBefore(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        if (m == 1) return String.format("%d12", y - 1);
        return String.format("%d%02d", y, m - 1);
    }

    private List<String> prefixes(String code) {
        List<String> list = new ArrayList<>();
        for (int len = 4; len <= code.length(); len += 2) list.add(code.substring(0, len));
        return list;
    }

    private void rollupFlat(Map<String, BigDecimal[]> leaf, int width, Map<String, BigDecimal[]> out) {
        for (Map.Entry<String, BigDecimal[]> en : leaf.entrySet()) {
            for (String prefix : prefixes(en.getKey())) {
                if (!accounts.containsKey(prefix)) continue;
                BigDecimal[] dst = out.computeIfAbsent(prefix, k -> zeros(width));
                for (int i = 0; i < width && i < en.getValue().length; i++)
                    dst[i] = dst[i].add(en.getValue()[i]);
            }
        }
    }

    private void rollupPosted(Map<String, Map<String, BigDecimal[]>> leaf,
                              Map<String, Map<String, BigDecimal[]>> out) {
        for (Map.Entry<String, Map<String, BigDecimal[]>> en : leaf.entrySet()) {
            for (String prefix : prefixes(en.getKey())) {
                if (!accounts.containsKey(prefix)) continue;
                Map<String, BigDecimal[]> dst = out.computeIfAbsent(prefix, k -> new HashMap<>());
                for (Map.Entry<String, BigDecimal[]> pe : en.getValue().entrySet()) {
                    BigDecimal[] d = dst.computeIfAbsent(pe.getKey(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    d[0] = d[0].add(pe.getValue()[0]);
                    d[1] = d[1].add(pe.getValue()[1]);
                }
            }
        }
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        for (int i = 0; i < n; i++) a[i] = BigDecimal.ZERO;
        return a;
    }

    private static BigDecimal nz(Object o) {
        return o == null ? BigDecimal.ZERO : TmsUtil.toBd(o);
    }
}
