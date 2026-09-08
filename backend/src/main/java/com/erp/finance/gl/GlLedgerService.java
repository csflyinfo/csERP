package com.erp.finance.gl;

import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账簿查询服务：总账 / 明细账 / 余额表 / 序时账。
 * 实时聚合，不落余额表：期初取 fin_init_balance，发生额取已过账凭证分录，
 * 父级科目金额按编码前缀（4-2-2-2）从末级逐级汇总。
 */
@Service
public class GlLedgerService {

    private final JdbcTemplate jdbc;
    private final SysParamService sysParam;

    public GlLedgerService(JdbcTemplate jdbc, SysParamService sysParam) {
        this.jdbc = jdbc;
        this.sysParam = sysParam;
    }

    private void ensureInitialized() {
        if (!sysParam.getBool(GlConst.PARAM_INITIALIZED, false))
            throw new IllegalArgumentException("总账尚未启用，请先在「总账初始化」中启用总账");
    }

    /** 全部科目（code -> 行），按编码排序。 */
    private Map<String, Map<String, Object>> loadAccounts() {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> a : TmsUtil.queryCamel(jdbc,
                "SELECT account_code, account_name, account_type, balance_direction, account_level, " +
                "is_leaf, is_qty, aux_dimensions, status FROM fin_account ORDER BY account_code")) {
            map.put(TmsUtil.str(a.get("accountCode")), a);
        }
        return map;
    }

    /** 期初聚合（末级科目）：code -> [openD, openC, openQ, ytdD, ytdC]。 */
    private Map<String, BigDecimal[]> loadInitAgg() {
        Map<String, BigDecimal[]> map = new HashMap<>();
        List<Map<String, Object>> rows;
        try {
            rows = TmsUtil.queryCamel(jdbc,
                    "SELECT account_code, SUM(open_debit) open_debit, SUM(open_credit) open_credit, " +
                    "SUM(open_qty) open_qty, SUM(ytd_debit) ytd_debit, SUM(ytd_credit) ytd_credit " +
                    "FROM fin_init_balance GROUP BY account_code");
        } catch (Exception e) {
            return map;
        }
        for (Map<String, Object> r : rows) {
            map.put(TmsUtil.str(r.get("accountCode")), new BigDecimal[]{
                    nz(r.get("openDebit")), nz(r.get("openCredit")), nz(r.get("openQty")),
                    nz(r.get("ytdDebit")), nz(r.get("ytdCredit"))});
        }
        return map;
    }

    /** 已过账发生额（末级科目）：code -> period(yyyyMM) -> [debit, credit, qty]。 */
    private Map<String, Map<String, BigDecimal[]>> loadPostedAgg() {
        Map<String, Map<String, BigDecimal[]>> map = new HashMap<>();
        // 已冲销凭证仍留在账上（与红字凭证轧差），故账上发生额 = 已过账 + 已冲销
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT e.account_code, v.period, " +
                "SUM(e.debit_amount) debit_amount, SUM(e.credit_amount) credit_amount, SUM(e.qty) qty " +
                "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                "WHERE v.status IN ('已过账','已冲销') GROUP BY e.account_code, v.period");
        for (Map<String, Object> r : rows) {
            map.computeIfAbsent(TmsUtil.str(r.get("accountCode")), k -> new HashMap<>())
                    .put(TmsUtil.str(r.get("period")),
                            new BigDecimal[]{nz(r.get("debitAmount")), nz(r.get("creditAmount")), nz(r.get("qty"))});
        }
        return map;
    }

    /** 把末级聚合按 4-2-2-2 前缀累加到所有存在的父级科目。返回 code -> 同结构。 */
    private Map<String, BigDecimal[]> rollupFlat(Map<String, BigDecimal[]> leaf,
                                                 Map<String, Map<String, Object>> accounts) {
        Map<String, BigDecimal[]> out = new HashMap<>();
        for (Map.Entry<String, BigDecimal[]> en : leaf.entrySet()) {
            for (String prefix : prefixes(en.getKey())) {
                if (!accounts.containsKey(prefix)) continue;
                BigDecimal[] dst = out.computeIfAbsent(prefix, k -> zeros(5));
                BigDecimal[] src = en.getValue();
                for (int i = 0; i < src.length; i++) dst[i] = dst[i].add(src[i]);
            }
        }
        return out;
    }

    private Map<String, Map<String, BigDecimal[]>> rollupPosted(Map<String, Map<String, BigDecimal[]>> leafPosted,
                                                                Map<String, Map<String, Object>> accounts) {
        Map<String, Map<String, BigDecimal[]>> out = new HashMap<>();
        for (Map.Entry<String, Map<String, BigDecimal[]>> en : leafPosted.entrySet()) {
            for (String prefix : prefixes(en.getKey())) {
                if (!accounts.containsKey(prefix)) continue;
                Map<String, BigDecimal[]> dst = out.computeIfAbsent(prefix, k -> new HashMap<>());
                for (Map.Entry<String, BigDecimal[]> pe : en.getValue().entrySet()) {
                    BigDecimal[] d = dst.computeIfAbsent(pe.getKey(), k -> zeros(3));
                    BigDecimal[] s = pe.getValue();
                    for (int i = 0; i < s.length; i++) d[i] = d[i].add(s[i]);
                }
            }
        }
        return out;
    }

    /** 科目的全部祖先前缀（含自身）：4/6/8/10 位。 */
    private List<String> prefixes(String code) {
        List<String> list = new ArrayList<>();
        for (int len = 4; len <= code.length(); len += 2) list.add(code.substring(0, len));
        return list;
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        for (int i = 0; i < n; i++) a[i] = BigDecimal.ZERO;
        return a;
    }

    private static BigDecimal nz(Object o) {
        return o == null ? BigDecimal.ZERO : TmsUtil.toBd(o);
    }

    /** 按借贷净额拆成「借/贷」两列展示：净额>=0 放借方，<0 放贷方绝对值。 */
    private static Map<String, Object> splitBal(BigDecimal debitSide, BigDecimal creditSide) {
        BigDecimal net = debitSide.subtract(creditSide);
        Map<String, Object> m = new LinkedHashMap<>();
        if (net.signum() >= 0) {
            m.put("debit", net);
            m.put("credit", BigDecimal.ZERO);
            m.put("direction", net.signum() == 0 ? "" : "借");
        } else {
            m.put("debit", BigDecimal.ZERO);
            m.put("credit", net.negate());
            m.put("direction", "贷");
        }
        return m;
    }

    // ==================== 总账 ====================

    /**
     * 总账：按年度逐月展示每个科目的期初/本期借贷/期末。
     * body: {year, keyword?（科目编码/名称模糊）, accountCode?（精确单个科目）}
     */
    public List<Map<String, Object>> generalLedger(Map<String, Object> body) {
        ensureInitialized();
        String year = TmsUtil.str(body.get("year"));
        if (year.isEmpty()) year = String.valueOf(java.time.LocalDate.now().getYear());
        String startPeriod = sysParam.get(GlConst.PARAM_START_PERIOD, "");
        String startYear = startPeriod.length() >= 4 ? startPeriod.substring(0, 4) : year;

        Map<String, Map<String, Object>> accounts = loadAccounts();
        Map<String, BigDecimal[]> initAgg = rollupFlat(loadInitAgg(), accounts);
        Map<String, Map<String, BigDecimal[]>> posted = rollupPosted(loadPostedAgg(), accounts);

        String kw = TmsUtil.str(body.get("keyword"));
        String onlyCode = TmsUtil.str(body.get("accountCode"));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> en : accounts.entrySet()) {
            String code = en.getKey();
            Map<String, Object> acc = en.getValue();
            if (!onlyCode.isEmpty() && !code.equals(onlyCode)) continue;
            if (onlyCode.isEmpty() && !kw.isEmpty()
                    && !code.contains(kw) && !TmsUtil.str(acc.get("accountName")).contains(kw)) continue;

            BigDecimal[] init = initAgg.getOrDefault(code, zeros(5));
            Map<String, BigDecimal[]> perPeriod = posted.getOrDefault(code, Map.of());

            // 年初余额：启用年=期初净额-年累发生净额（openD-openC 为启用期期初净借方）；
            // 以后年度=期初余额+启用期至上年末全部过账发生
            BigDecimal openD, openC;
            if (year.equals(startYear)) {
                BigDecimal net = init[0].subtract(init[1]).subtract(init[3]).add(init[4]);
                openD = net.signum() >= 0 ? net : BigDecimal.ZERO;
                openC = net.signum() < 0 ? net.negate() : BigDecimal.ZERO;
            } else {
                openD = init[0];
                openC = init[1];
                for (Map.Entry<String, BigDecimal[]> pe : perPeriod.entrySet()) {
                    if (pe.getKey().compareTo(year + "01") < 0) {
                        openD = openD.add(pe.getValue()[0]);
                        openC = openC.add(pe.getValue()[1]);
                    }
                }
            }

            List<Map<String, Object>> months = new ArrayList<>();
            BigDecimal carryD = openD, carryC = openC;
            BigDecimal yearD = year.equals(startYear) ? init[3] : BigDecimal.ZERO;
            BigDecimal yearC = year.equals(startYear) ? init[4] : BigDecimal.ZERO;
            for (int m = 1; m <= 12; m++) {
                String period = String.format("%s%02d", year, m);
                BigDecimal[] mv = perPeriod.getOrDefault(period, zeros(3));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("period", period);
                row.put("month", m + "月");
                row.put("opening", splitBal(carryD, carryC));
                row.put("debitAmount", mv[0]);
                row.put("creditAmount", mv[1]);
                carryD = carryD.add(mv[0]);
                carryC = carryC.add(mv[1]);
                row.put("closing", splitBal(carryD, carryC));
                months.add(row);
                yearD = yearD.add(mv[0]);
                yearC = yearC.add(mv[1]);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("accountCode", code);
            item.put("accountName", acc.get("accountName"));
            item.put("accountType", acc.get("accountType"));
            item.put("balanceDirection", acc.get("balanceDirection"));
            item.put("isLeaf", acc.get("isLeaf"));
            item.put("yearOpening", splitBal(openD, openC));
            item.put("yearDebit", yearD);
            item.put("yearCredit", yearC);
            item.put("months", months);
            result.add(item);
        }
        return result;
    }

    // ==================== 余额表 ====================

    /**
     * 发生额及余额表：期间范围 periodFrom~periodTo（yyyyMM）。
     * 列：期初借/贷、本期借/贷、期末借/贷、本年累计借/贷。
     */
    public List<Map<String, Object>> balanceTable(Map<String, Object> body) {
        ensureInitialized();
        String periodFrom = TmsUtil.str(body.get("periodFrom"));
        String periodTo = TmsUtil.str(body.get("periodTo"));
        if (periodFrom.isEmpty() || periodTo.isEmpty())
            throw new IllegalArgumentException("请选择查询期间范围");
        if (periodFrom.compareTo(periodTo) > 0)
            throw new IllegalArgumentException("起始期间不能晚于截止期间");
        String year = periodFrom.substring(0, 4);
        String startPeriod = sysParam.get(GlConst.PARAM_START_PERIOD, "");
        String startYear = startPeriod.length() >= 4 ? startPeriod.substring(0, 4) : year;

        Map<String, Map<String, Object>> accounts = loadAccounts();
        Map<String, BigDecimal[]> initAgg = rollupFlat(loadInitAgg(), accounts);
        Map<String, Map<String, BigDecimal[]>> posted = rollupPosted(loadPostedAgg(), accounts);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> en : accounts.entrySet()) {
            String code = en.getKey();
            Map<String, Object> acc = en.getValue();
            BigDecimal[] init = initAgg.getOrDefault(code, zeros(5));
            Map<String, BigDecimal[]> perPeriod = posted.getOrDefault(code, Map.of());

            // 期初（periodFrom 初）= 期初余额 + periodFrom 之前全部过账发生
            BigDecimal openD = init[0], openC = init[1];
            BigDecimal openQty = init[2];
            for (Map.Entry<String, BigDecimal[]> pe : perPeriod.entrySet()) {
                if (pe.getKey().compareTo(periodFrom) < 0) {
                    openD = openD.add(pe.getValue()[0]);
                    openC = openC.add(pe.getValue()[1]);
                    openQty = openQty.add(pe.getValue()[2]);
                }
            }
            // 本期发生 & 本年累计
            BigDecimal pd = BigDecimal.ZERO, pc = BigDecimal.ZERO, pq = BigDecimal.ZERO;
            BigDecimal yd = year.equals(startYear) ? init[3] : BigDecimal.ZERO;
            BigDecimal yc = year.equals(startYear) ? init[4] : BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal[]> pe : perPeriod.entrySet()) {
                String p = pe.getKey();
                if (p.compareTo(periodFrom) >= 0 && p.compareTo(periodTo) <= 0) {
                    pd = pd.add(pe.getValue()[0]);
                    pc = pc.add(pe.getValue()[1]);
                    pq = pq.add(pe.getValue()[2]);
                }
                if (p.startsWith(year) && p.compareTo(periodTo) <= 0) {
                    yd = yd.add(pe.getValue()[0]);
                    yc = yc.add(pe.getValue()[1]);
                }
            }
            BigDecimal closeD = openD.add(pd), closeC = openC.add(pc);
            // 全零行跳过（未使用科目）
            if (openD.signum() == 0 && openC.signum() == 0 && pd.signum() == 0 && pc.signum() == 0
                    && yd.signum() == 0 && yc.signum() == 0) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("accountCode", code);
            row.put("accountName", acc.get("accountName"));
            row.put("accountType", acc.get("accountType"));
            row.put("balanceDirection", acc.get("balanceDirection"));
            row.put("accountLevel", acc.get("accountLevel"));
            row.put("isLeaf", acc.get("isLeaf"));
            row.put("isQty", acc.get("isQty"));
            row.put("opening", splitBal(openD, openC));
            row.put("periodDebit", pd);
            row.put("periodCredit", pc);
            row.put("closing", splitBal(closeD, closeC));
            row.put("yearDebit", yd);
            row.put("yearCredit", yc);
            if (Boolean.TRUE.equals(acc.get("isQty"))) {
                row.put("openQty", openQty);
                row.put("periodQty", pq);
                row.put("closeQty", openQty.add(pq));
            }
            result.add(row);
        }
        return result;
    }

    // ==================== 明细账 ====================

    /**
     * 明细账：单个末级科目的逐笔分录 + 滚动余额，支持辅助核算过滤。
     * body: {accountCode, dateFrom, dateTo, auxCustomer?, auxSupplier?, auxDepartment?, auxEmployee?, auxGoods?, auxProject?, auxArea?}
     */
    public Map<String, Object> subsidiaryLedger(Map<String, Object> body) {
        ensureInitialized();
        String code = TmsUtil.str(body.get("accountCode"));
        if (code.isEmpty()) throw new IllegalArgumentException("请选择科目");
        List<Map<String, Object>> accs = TmsUtil.queryCamel(jdbc,
                "SELECT account_code, account_name, balance_direction, " +
                "is_leaf, is_qty FROM fin_account WHERE account_code = ?", code);
        if (accs.isEmpty()) throw new IllegalArgumentException("科目不存在：" + code);
        Map<String, Object> acc = accs.get(0);
        if (!Boolean.TRUE.equals(acc.get("isLeaf"))) throw new IllegalArgumentException("明细账只能查询末级科目");

        String dateFrom = TmsUtil.str(body.get("dateFrom"));
        String dateTo = TmsUtil.str(body.get("dateTo"));
        if (dateFrom.isEmpty()) dateFrom = "1900-01-01";
        if (dateTo.isEmpty()) dateTo = "2999-12-31";

        // 辅助过滤条件
        StringBuilder auxWhere = new StringBuilder();
        List<Object> auxArgs = new ArrayList<>();
        for (String dim : GlConst.AUX_DIMS) {
            String val = TmsUtil.str(body.get("aux" + dim.substring(0, 1).toUpperCase() + dim.substring(1)));
            if (!val.isEmpty()) {
                auxWhere.append(" AND e.aux_").append(dim).append(" = ?");
                auxArgs.add(val);
            }
        }

        // 期初：fin_init_balance（含辅助行过滤）+ 日期之前的已过账分录
        StringBuilder initSql = new StringBuilder(
                "SELECT COALESCE(SUM(open_debit),0) debit, COALESCE(SUM(open_credit),0) credit, " +
                "COALESCE(SUM(open_qty),0) qty FROM fin_init_balance WHERE account_code = ?");
        List<Object> initArgs = new ArrayList<>();
        initArgs.add(code);
        for (String dim : GlConst.AUX_DIMS) {
            String val = TmsUtil.str(body.get("aux" + dim.substring(0, 1).toUpperCase() + dim.substring(1)));
            if (!val.isEmpty()) { initSql.append(" AND aux_").append(dim).append(" = ?"); initArgs.add(val); }
        }
        Map<String, Object> initRow = TmsUtil.queryCamel(jdbc, initSql.toString(), initArgs.toArray())
                .stream().findFirst().orElse(Map.of());
        BigDecimal openD = nz(initRow.get("debit"));
        BigDecimal openC = nz(initRow.get("credit"));
        BigDecimal openQty = nz(initRow.get("qty"));

        StringBuilder beforeSql = new StringBuilder(
                "SELECT COALESCE(SUM(e.debit_amount),0) debit, COALESCE(SUM(e.credit_amount),0) credit, " +
                "COALESCE(SUM(e.qty),0) qty FROM fin_voucher_entry e " +
                "JOIN fin_voucher v ON v.id = e.voucher_id " +
                "WHERE e.account_code = ? AND v.status IN ('已过账','已冲销') AND v.voucher_date < ?");
        List<Object> beforeArgs = new ArrayList<>();
        beforeArgs.add(code); beforeArgs.add(java.sql.Date.valueOf(dateFrom));
        beforeSql.append(auxWhere);
        beforeArgs.addAll(auxArgs);
        Map<String, Object> beforeRow = TmsUtil.queryCamel(jdbc, beforeSql.toString(), beforeArgs.toArray())
                .stream().findFirst().orElse(Map.of());
        openD = openD.add(nz(beforeRow.get("debit")));
        openC = openC.add(nz(beforeRow.get("credit")));
        openQty = openQty.add(nz(beforeRow.get("qty")));

        // 明细分录
        StringBuilder sql = new StringBuilder(
                "SELECT v.voucher_date, v.voucher_no, v.period, v.is_red, " +
                "v.source, v.source_bill_no, e.line_no, e.summary, " +
                "e.debit_amount, e.credit_amount, e.qty, e.price, " +
                "e.aux_text, e.cash_flow_item " +
                "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                "WHERE e.account_code = ? AND v.status IN ('已过账','已冲销') AND v.voucher_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(code);
        args.add(java.sql.Date.valueOf(dateFrom)); args.add(java.sql.Date.valueOf(dateTo));
        sql.append(auxWhere);
        args.addAll(auxArgs);
        sql.append(" ORDER BY v.voucher_date, v.voucher_no, e.line_no");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());

        BigDecimal runD = openD, runC = openC, runQty = openQty;
        BigDecimal totalD = BigDecimal.ZERO, totalC = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal d = nz(r.get("debitAmount")), c = nz(r.get("creditAmount"));
            runD = runD.add(d);
            runC = runC.add(c);
            runQty = runQty.add(nz(r.get("qty")));
            totalD = totalD.add(d);
            totalC = totalC.add(c);
            Map<String, Object> bal = splitBal(runD, runC);
            r.put("balanceDebit", bal.get("debit"));
            r.put("balanceCredit", bal.get("credit"));
            r.put("balanceDirection", bal.get("direction"));
            r.put("balanceQty", runQty);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", acc);
        result.put("dateFrom", dateFrom);
        result.put("dateTo", dateTo);
        result.put("opening", splitBal(openD, openC));
        result.put("openingQty", openQty);
        result.put("rows", rows);
        result.put("totalDebit", totalD);
        result.put("totalCredit", totalC);
        result.put("closing", splitBal(runD, runC));
        result.put("closingQty", runQty);
        return result;
    }

    // ==================== 序时账 ====================

    /**
     * 序时账：按时间顺序列示凭证分录。body: {dateFrom, dateTo, status?（默认全部非作废）, keyword?}
     */
    public List<Map<String, Object>> journal(Map<String, Object> body) {
        ensureInitialized();
        String dateFrom = TmsUtil.str(body.get("dateFrom"));
        String dateTo = TmsUtil.str(body.get("dateTo"));
        if (dateFrom.isEmpty()) dateFrom = "1900-01-01";
        if (dateTo.isEmpty()) dateTo = "2999-12-31";
        String status = TmsUtil.str(body.get("status"));
        String keyword = TmsUtil.str(body.get("keyword"));

        StringBuilder sql = new StringBuilder(
                "SELECT v.voucher_date, v.voucher_no, v.voucher_word, " +
                "v.period, v.status, v.is_red, v.source, v.source_bill_type, " +
                "v.source_bill_no, v.maker_name, v.auditor_name, " +
                "v.poster_name, e.line_no, e.summary entry_summary, v.summary head_summary, " +
                "e.account_code, a.account_name, " +
                "e.debit_amount, e.credit_amount, e.aux_text, " +
                "e.cash_flow_item " +
                "FROM fin_voucher v JOIN fin_voucher_entry e ON e.voucher_id = v.id " +
                "LEFT JOIN fin_account a ON a.account_code = e.account_code " +
                "WHERE v.voucher_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Date.valueOf(dateFrom));
        args.add(java.sql.Date.valueOf(dateTo));
        if (status.isEmpty()) {
            sql.append(" AND v.status <> ?");
            args.add(GlConst.V_VOID);
        } else {
            sql.append(" AND v.status = ?");
            args.add(status);
        }
        if (!keyword.isEmpty()) {
            sql.append(" AND (v.voucher_no LIKE ? OR v.summary LIKE ? OR e.summary LIKE ? OR e.account_code LIKE ? OR a.account_name LIKE ?)");
            for (int i = 0; i < 5; i++) args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY v.voucher_date, v.voucher_no, e.line_no");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    // ==================== 多栏账（M9） ====================

    /**
     * 多栏账：上级科目的末级子科目摊列为金额栏，逐月列示分析侧发生额。
     * 分析侧取科目余额方向：借方向科目分析借方发生（费用/成本类），贷方向分析贷方发生（收入类）。
     * body: {accountCode（非末级科目）, periodFrom, periodTo}
     */
    public Map<String, Object> multiColumnLedger(Map<String, Object> body) {
        ensureInitialized();
        String code = TmsUtil.str(body.get("accountCode"));
        if (code.isEmpty()) throw new IllegalArgumentException("请选择上级科目");
        String periodFrom = TmsUtil.str(body.get("periodFrom"));
        String periodTo = TmsUtil.str(body.get("periodTo"));
        if (periodFrom.isEmpty() || periodTo.isEmpty()) throw new IllegalArgumentException("请选择查询期间范围");
        if (periodFrom.compareTo(periodTo) > 0) throw new IllegalArgumentException("起始期间不能晚于截止期间");

        Map<String, Map<String, Object>> accounts = loadAccounts();
        Map<String, Object> parent = accounts.get(code);
        if (parent == null) throw new IllegalArgumentException("科目不存在：" + code);
        if (Boolean.TRUE.equals(parent.get("isLeaf")))
            throw new IllegalArgumentException("多栏账请选择非末级科目（如「管理费用」「主营业务收入」），末级科目请查明细账");

        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> en : accounts.entrySet()) {
            String c = en.getKey();
            Map<String, Object> acc = en.getValue();
            if (!c.startsWith(code) || c.equals(code)) continue;
            if (!Boolean.TRUE.equals(acc.get("isLeaf"))) continue;
            if (!GlConst.ACCOUNT_ENABLED.equals(TmsUtil.str(acc.get("status")))) continue;
            columns.add(acc);
        }
        if (columns.isEmpty()) throw new IllegalArgumentException("科目 " + code + " 下没有启用的末级明细科目，无法做多栏分析");

        boolean debitSide = !"贷".equals(TmsUtil.str(parent.get("balanceDirection")));
        Map<String, BigDecimal[]> initAgg = loadInitAgg();          // 末级 code -> [openD,openC,...]
        Map<String, Map<String, BigDecimal[]>> posted = loadPostedAgg();  // 末级 code -> period -> [d,c,q]

        // 期初（父级口径 = 各子栏合计）：启用期初 + periodFrom 之前过账发生
        BigDecimal openD = BigDecimal.ZERO, openC = BigDecimal.ZERO;
        for (Map<String, Object> col : columns) {
            String cc = TmsUtil.str(col.get("accountCode"));
            BigDecimal[] init = initAgg.getOrDefault(cc, zeros(5));
            openD = openD.add(init[0]);
            openC = openC.add(init[1]);
            Map<String, BigDecimal[]> per = posted.getOrDefault(cc, Map.of());
            for (Map.Entry<String, BigDecimal[]> pe : per.entrySet()) {
                if (pe.getKey().compareTo(periodFrom) < 0) {
                    openD = openD.add(pe.getValue()[0]);
                    openC = openC.add(pe.getValue()[1]);
                }
            }
        }

        List<String> periods = monthsBetween(periodFrom, periodTo);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, BigDecimal> colTotals = new LinkedHashMap<>();
        for (Map<String, Object> col : columns)
            colTotals.put(TmsUtil.str(col.get("accountCode")), BigDecimal.ZERO);
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (String period : periods) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", period);
            Map<String, Object> cells = new LinkedHashMap<>();
            BigDecimal rowTotal = BigDecimal.ZERO;
            for (Map<String, Object> col : columns) {
                String cc = TmsUtil.str(col.get("accountCode"));
                BigDecimal[] mv = posted.getOrDefault(cc, Map.of()).getOrDefault(period, zeros(3));
                BigDecimal amt = debitSide ? mv[0] : mv[1];
                cells.put(cc, amt);
                rowTotal = rowTotal.add(amt);
                colTotals.merge(cc, amt, BigDecimal::add);
            }
            row.put("cells", cells);
            row.put("total", rowTotal);
            rows.add(row);
            grandTotal = grandTotal.add(rowTotal);
        }

        Map<String, Object> totalRow = new LinkedHashMap<>();
        totalRow.put("cells", colTotals);
        totalRow.put("total", grandTotal);

        List<Map<String, Object>> colMeta = new ArrayList<>();
        for (Map<String, Object> col : columns) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountCode", col.get("accountCode"));
            m.put("accountName", col.get("accountName"));
            colMeta.add(m);
        }
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("accountCode", code);
        account.put("accountName", parent.get("accountName"));
        account.put("balanceDirection", parent.get("balanceDirection"));
        account.put("analyzeSide", debitSide ? "借" : "贷");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", account);
        result.put("columns", colMeta);
        result.put("periodFrom", periodFrom);
        result.put("periodTo", periodTo);
        result.put("opening", splitBal(openD, openC));
        result.put("rows", rows);
        result.put("totalRow", totalRow);
        return result;
    }

    // ==================== 辅助核算账（M9） ====================

    /** 辅助维度 → 档案表 [table, codeCol, nameCol]；片区为自由文本无档案。 */
    private static final Map<String, String[]> AUX_ARCHIVE = new LinkedHashMap<>();
    static {
        AUX_ARCHIVE.put(GlConst.DIM_CUSTOMER, new String[]{"base_customer", "customer_code", "customer_name"});
        AUX_ARCHIVE.put(GlConst.DIM_SUPPLIER, new String[]{"base_supplier", "supplier_code", "supplier_name"});
        AUX_ARCHIVE.put(GlConst.DIM_DEPARTMENT, new String[]{"base_department", "department_code", "department_name"});
        AUX_ARCHIVE.put(GlConst.DIM_EMPLOYEE, new String[]{"base_employee", "employee_code", "employee_name"});
        AUX_ARCHIVE.put(GlConst.DIM_GOODS, new String[]{"base_goods", "goods_code", "goods_name"});
        AUX_ARCHIVE.put(GlConst.DIM_PROJECT, new String[]{"fin_aux_project", "project_code", "project_name"});
    }

    /** 辅助维度档案 code→name（缺表环境返回空 Map，调用方回退显示编码/原文）。 */
    private Map<String, String> auxNameMap(String dim) {
        String[] cfg = AUX_ARCHIVE.get(dim);
        if (cfg == null) return Map.of();
        try {
            Map<String, String> map = new LinkedHashMap<>();
            for (Map<String, Object> r : TmsUtil.queryCamel(jdbc,
                    "SELECT " + cfg[1] + " code, " + cfg[2] + " name FROM " + cfg[0])) {
                map.put(TmsUtil.str(r.get("code")), TmsUtil.str(r.get("name")));
            }
            return map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String auxColumn(String dim) {
        if (!GlConst.AUX_DIMS.contains(dim))
            throw new IllegalArgumentException("辅助核算维度应为：" + String.join("/", GlConst.AUX_DIMS));
        return "aux_" + dim;
    }

    /**
     * 辅助核算余额表：按辅助对象（客户/供应商/部门/员工/商品/项目/片区）汇总期初/本期借贷/期末。
     * body: {dimension, periodFrom, periodTo, accountCode?（科目前缀过滤，可传父级）}
     */
    public Map<String, Object> auxBalance(Map<String, Object> body) {
        ensureInitialized();
        String dim = TmsUtil.str(body.get("dimension"));
        String auxCol = auxColumn(dim);
        String periodFrom = TmsUtil.str(body.get("periodFrom"));
        String periodTo = TmsUtil.str(body.get("periodTo"));
        if (periodFrom.isEmpty() || periodTo.isEmpty()) throw new IllegalArgumentException("请选择查询期间范围");
        if (periodFrom.compareTo(periodTo) > 0) throw new IllegalArgumentException("起始期间不能晚于截止期间");
        String prefix = TmsUtil.str(body.get("accountCode"));
        boolean byAccount = !prefix.isEmpty();

        // party -> [openD, openC, periodD, periodC]
        Map<String, BigDecimal[]> agg = new LinkedHashMap<>();
        // 启用期初
        StringBuilder initSql = new StringBuilder(
                "SELECT " + auxCol + " party, SUM(open_debit) d, SUM(open_credit) c " +
                        "FROM fin_init_balance WHERE " + auxCol + " IS NOT NULL AND " + auxCol + " <> ''");
        List<Object> initArgs = new ArrayList<>();
        if (byAccount) { initSql.append(" AND account_code LIKE ?"); initArgs.add(prefix + "%"); }
        initSql.append(" GROUP BY " + auxCol);
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc, initSql.toString(), initArgs.toArray())) {
            String party = TmsUtil.str(r.get("party"));
            BigDecimal[] a = agg.computeIfAbsent(party, k -> zeros(4));
            a[0] = a[0].add(nz(r.get("d")));
            a[1] = a[1].add(nz(r.get("c")));
        }
        // 已过账发生（已冲销留账轧差）
        StringBuilder postSql = new StringBuilder(
                "SELECT " + auxCol + " party, v.period period, SUM(e.debit_amount) d, SUM(e.credit_amount) c " +
                        "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                        "WHERE v.status IN ('已过账','已冲销') AND " + auxCol + " IS NOT NULL AND " + auxCol + " <> ''");
        List<Object> postArgs = new ArrayList<>();
        if (byAccount) { postSql.append(" AND e.account_code LIKE ?"); postArgs.add(prefix + "%"); }
        postSql.append(" GROUP BY " + auxCol + ", v.period");
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc, postSql.toString(), postArgs.toArray())) {
            String party = TmsUtil.str(r.get("party"));
            String per = TmsUtil.str(r.get("period"));
            BigDecimal[] a = agg.computeIfAbsent(party, k -> zeros(4));
            if (per.compareTo(periodFrom) < 0) {
                a[0] = a[0].add(nz(r.get("d")));
                a[1] = a[1].add(nz(r.get("c")));
            } else if (per.compareTo(periodTo) <= 0) {
                a[2] = a[2].add(nz(r.get("d")));
                a[3] = a[3].add(nz(r.get("c")));
            }
        }

        Map<String, String> names = auxNameMap(dim);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> parties = new ArrayList<>(agg.keySet());
        Collections.sort(parties);
        for (String party : parties) {
            BigDecimal[] a = agg.get(party);
            BigDecimal closeD = a[0].add(a[2]), closeC = a[1].add(a[3]);
            if (a[0].signum() == 0 && a[1].signum() == 0 && a[2].signum() == 0 && a[3].signum() == 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("auxCode", party);
            row.put("auxName", names.getOrDefault(party, party));
            row.put("opening", splitBal(a[0], a[1]));
            row.put("periodDebit", a[2]);
            row.put("periodCredit", a[3]);
            row.put("closing", splitBal(closeD, closeC));
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dim);
        result.put("dimensionLabel", GlConst.AUX_LABELS.get(dim));
        result.put("accountCode", prefix);
        result.put("periodFrom", periodFrom);
        result.put("periodTo", periodTo);
        result.put("rows", rows);
        return result;
    }

    /**
     * 辅助核算明细表：单个辅助对象的逐笔分录 + 滚动余额（可跨科目，可按科目前缀过滤）。
     * body: {dimension, auxCode（必填）, dateFrom, dateTo, accountCode?（前缀）}
     */
    public Map<String, Object> auxSubsidiary(Map<String, Object> body) {
        ensureInitialized();
        String dim = TmsUtil.str(body.get("dimension"));
        String auxCol = auxColumn(dim);
        String auxCode = TmsUtil.str(body.get("auxCode"));
        if (auxCode.isEmpty()) throw new IllegalArgumentException("请选择" + GlConst.AUX_LABELS.getOrDefault(dim, "辅助") + "对象");
        String dateFrom = TmsUtil.str(body.get("dateFrom"));
        String dateTo = TmsUtil.str(body.get("dateTo"));
        if (dateFrom.isEmpty()) dateFrom = "1900-01-01";
        if (dateTo.isEmpty()) dateTo = "2999-12-31";
        String prefix = TmsUtil.str(body.get("accountCode"));
        boolean byAccount = !prefix.isEmpty();

        // 期初：启用期初 + 日期之前过账发生
        StringBuilder initSql = new StringBuilder(
                "SELECT COALESCE(SUM(open_debit),0) d, COALESCE(SUM(open_credit),0) c " +
                        "FROM fin_init_balance WHERE " + auxCol + " = ?");
        List<Object> initArgs = new ArrayList<>();
        initArgs.add(auxCode);
        if (byAccount) { initSql.append(" AND account_code LIKE ?"); initArgs.add(prefix + "%"); }
        Map<String, Object> initRow = TmsUtil.queryCamel(jdbc, initSql.toString(), initArgs.toArray())
                .stream().findFirst().orElse(Map.of());
        BigDecimal openD = nz(initRow.get("d"));
        BigDecimal openC = nz(initRow.get("c"));

        StringBuilder beforeSql = new StringBuilder(
                "SELECT COALESCE(SUM(e.debit_amount),0) d, COALESCE(SUM(e.credit_amount),0) c " +
                        "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                        "WHERE v.status IN ('已过账','已冲销') AND e." + auxCol + " = ? AND v.voucher_date < ?");
        List<Object> beforeArgs = new ArrayList<>();
        beforeArgs.add(auxCode);
        beforeArgs.add(java.sql.Date.valueOf(dateFrom));
        if (byAccount) { beforeSql.append(" AND e.account_code LIKE ?"); beforeArgs.add(prefix + "%"); }
        Map<String, Object> beforeRow = TmsUtil.queryCamel(jdbc, beforeSql.toString(), beforeArgs.toArray())
                .stream().findFirst().orElse(Map.of());
        openD = openD.add(nz(beforeRow.get("d")));
        openC = openC.add(nz(beforeRow.get("c")));

        StringBuilder sql = new StringBuilder(
                "SELECT v.voucher_date, v.voucher_no, v.voucher_word, v.period, v.is_red, " +
                        "v.source, e.line_no, e.summary, e.account_code, a.account_name, " +
                        "e.debit_amount, e.credit_amount, e.aux_text " +
                        "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                        "LEFT JOIN fin_account a ON a.account_code = e.account_code " +
                        "WHERE v.status IN ('已过账','已冲销') AND e." + auxCol + " = ? " +
                        "AND v.voucher_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(auxCode);
        args.add(java.sql.Date.valueOf(dateFrom));
        args.add(java.sql.Date.valueOf(dateTo));
        if (byAccount) { sql.append(" AND e.account_code LIKE ?"); args.add(prefix + "%"); }
        sql.append(" ORDER BY v.voucher_date, v.voucher_no, e.line_no");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());

        BigDecimal runD = openD, runC = openC;
        BigDecimal totalD = BigDecimal.ZERO, totalC = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal d = nz(r.get("debitAmount")), c = nz(r.get("creditAmount"));
            runD = runD.add(d);
            runC = runC.add(c);
            totalD = totalD.add(d);
            totalC = totalC.add(c);
            Map<String, Object> bal = splitBal(runD, runC);
            r.put("balanceDebit", bal.get("debit"));
            r.put("balanceCredit", bal.get("credit"));
            r.put("balanceDirection", bal.get("direction"));
        }

        Map<String, String> names = auxNameMap(dim);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dim);
        result.put("dimensionLabel", GlConst.AUX_LABELS.get(dim));
        result.put("auxCode", auxCode);
        result.put("auxName", names.getOrDefault(auxCode, auxCode));
        result.put("accountCode", prefix);
        result.put("dateFrom", dateFrom);
        result.put("dateTo", dateTo);
        result.put("opening", splitBal(openD, openC));
        result.put("rows", rows);
        result.put("totalDebit", totalD);
        result.put("totalCredit", totalC);
        result.put("closing", splitBal(runD, runC));
        return result;
    }

    /** 期间范围（yyyyMM）逐月列表。 */
    private static List<String> monthsBetween(String from, String to) {
        List<String> list = new ArrayList<>();
        int y = Integer.parseInt(from.substring(0, 4));
        int m = Integer.parseInt(from.substring(4, 6));
        String cur = String.format("%04d%02d", y, m);
        while (cur.compareTo(to) <= 0) {
            list.add(cur);
            m++;
            if (m > 12) { m = 1; y++; }
            cur = String.format("%04d%02d", y, m);
        }
        return list;
    }
}
