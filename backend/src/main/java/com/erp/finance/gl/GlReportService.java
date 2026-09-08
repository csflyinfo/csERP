package com.erp.finance.gl;

import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务报表与业财对账（总账 M6）。
 *
 * 报表：读取 V96 种子表 fin_report_item（BS 资产负债表 / IS 利润表 / CF 现金流量表），
 *   formula 列取期末/本月数，formula_begin 列取年初/本年累计，公式统一交 GlReportCalc 取数；
 *   BS 额外返回资产总计 vs 负债和所有者权益总计的平衡校验（容差 0.02）。
 * 对账：1122 应收账款 vs fin_ar 未收、2202 应付账款 vs fin_ap 未付、
 *   资金科目（is_cash）vs base_fund_account 余额（V95 gl_account_code 映射），
 *   应收/应付按客户/供应商给明细，资金按账户给明细。
 * 现金流量项目补录：已过账凭证中现金类分录（is_cash 科目）未指定流量项目的，
 *   允许批量补录——只影响现金流量表列报，不影响余额与试算平衡，故不阻断、留操作日志。
 */
@Service
public class GlReportService {

    private final JdbcTemplate jdbc;
    private final GlReportCalc calc;

    /** 报表平衡/对账容差（元）。 */
    private static final BigDecimal TOL = new BigDecimal("0.02");

    public GlReportService(JdbcTemplate jdbc, GlReportCalc calc) {
        this.jdbc = jdbc;
        this.calc = calc;
    }

    // ==================== 三表渲染 ====================

    /** 报表渲染。reportCode: BS/IS/CF；period 为空取进行中期间。 */
    public Map<String, Object> report(String reportCode, String period) {
        String code = TmsUtil.str(reportCode).toUpperCase();
        if (!code.equals("BS") && !code.equals("IS") && !code.equals("CF"))
            throw new IllegalArgumentException("报表代码应为 BS（资产负债表）/IS（利润表）/CF（现金流量表）");
        String per = resolvePeriod(period);
        calc.invalidate();

        List<Map<String, Object>> items = TmsUtil.queryCamel(jdbc,
                "SELECT line_no, item_name, row_type, formula, formula_begin, indent_level, remark " +
                        "FROM fin_report_item WHERE report_code = ? ORDER BY line_no", code);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> it : items) {
            String formula = TmsUtil.str(it.get("formula"));
            String formulaBegin = TmsUtil.str(it.get("formulaBegin"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", it.get("lineNo"));
            row.put("itemName", it.get("itemName"));
            row.put("rowType", it.get("rowType"));
            row.put("indentLevel", it.get("indentLevel"));
            row.put("amount", formula.isEmpty() ? BigDecimal.ZERO : calc.evalAmount(formula, per));
            row.put("beginAmount", formulaBegin.isEmpty() ? BigDecimal.ZERO : calc.evalAmount(formulaBegin, per));
            row.put("remark", it.get("remark"));
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportCode", code);
        result.put("period", per);
        result.put("rows", rows);
        if (code.equals("BS")) result.put("balance", balanceCheck(rows));
        return result;
    }

    // ==================== 报表公式维护（M9） ====================

    /** 报表项目行（含 id/公式），供公式编辑 UI。reportCode: BS/IS/CF。 */
    public List<Map<String, Object>> formulaList(String reportCode) {
        String code = normalizeReportCode(reportCode);
        return TmsUtil.queryCamel(jdbc,
                "SELECT id, report_code, line_no, item_name, row_type, formula, formula_begin, " +
                        "indent_level, is_system, remark FROM fin_report_item WHERE report_code = ? ORDER BY line_no",
                code);
    }

    /**
     * 保存单个报表项目的名称/公式/备注（行类型、行号、缩进不允许改，避免破坏表结构）。
     * body: {id, itemName?, formula?, formulaBegin?, remark?}；公式用进行中期间试算，试算不过拒绝。
     */
    @Transactional
    public Map<String, Object> formulaSave(Map<String, Object> body) {
        String id = TmsUtil.str(body.get("id"));
        if (id.isEmpty()) throw new IllegalArgumentException("报表项目 id 不能为空");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT id, report_code, line_no, item_name FROM fin_report_item WHERE id = ?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("报表项目不存在或已被删除");
        String itemName = body.containsKey("itemName") ? TmsUtil.str(body.get("itemName")).trim() : null;
        String formula = body.containsKey("formula") ? TmsUtil.str(body.get("formula")).trim() : null;
        String formulaBegin = body.containsKey("formulaBegin") ? TmsUtil.str(body.get("formulaBegin")).trim() : null;
        String remark = body.containsKey("remark") ? TmsUtil.str(body.get("remark")).trim() : null;
        if (itemName != null && itemName.isEmpty()) throw new IllegalArgumentException("项目名称不能为空");

        // 公式试算：总账已启用则用进行中期间验证可解析（金额为 0 不阻断）
        String period = null;
        try { period = resolvePeriod(""); } catch (Exception ignored) {}
        if (period != null) {
            calc.invalidate();
            if (formula != null && !formula.isEmpty()) {
                try { calc.evalAmount(formula, period); }
                catch (Exception e) { throw new IllegalArgumentException("期末/本月数公式无法取数：" + formula + "（" + e.getMessage() + "）"); }
            }
            if (formulaBegin != null && !formulaBegin.isEmpty()) {
                try { calc.evalAmount(formulaBegin, period); }
                catch (Exception e) { throw new IllegalArgumentException("年初/累计数公式无法取数：" + formulaBegin + "（" + e.getMessage() + "）"); }
            }
        }

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE fin_report_item SET ");
        if (itemName != null) { sql.append("item_name = ?, "); args.add(itemName); }
        if (formula != null) { sql.append("formula = ?, "); args.add(formula.isEmpty() ? null : formula); }
        if (formulaBegin != null) { sql.append("formula_begin = ?, "); args.add(formulaBegin.isEmpty() ? null : formulaBegin); }
        if (remark != null) { sql.append("remark = ?, "); args.add(remark.isEmpty() ? null : remark); }
        if (args.isEmpty()) throw new IllegalArgumentException("没有需要保存的内容");
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE id = ?");
        args.add(id);
        jdbc.update(sql.toString(), args.toArray());
        calc.invalidate();
        TmsUtil.log(jdbc, "finance.gl.report", "FORMULA",
                TmsUtil.str(rows.get(0).get("reportCode")) + "-" + TmsUtil.str(rows.get(0).get("lineNo")),
                "修改报表项目公式：" + TmsUtil.str(rows.get(0).get("itemName")));
        return Map.of("id", id);
    }

    private String normalizeReportCode(String reportCode) {
        String code = TmsUtil.str(reportCode).toUpperCase();
        if (!code.equals("BS") && !code.equals("IS") && !code.equals("CF"))
            throw new IllegalArgumentException("报表代码应为 BS（资产负债表）/IS（利润表）/CF（现金流量表）");
        return code;
    }

    /** 资产负债表平衡校验：资产总计 vs 负债和所有者权益总计（期末/年初各一组）。 */
    private Map<String, Object> balanceCheck(List<Map<String, Object>> rows) {
        Map<String, Object> assets = null, liabEquity = null;
        for (Map<String, Object> r : rows) {
            String name = TmsUtil.str(r.get("itemName"));
            if ("资产总计".equals(name)) assets = r;
            else if (name.contains("负债") && name.contains("总计")) liabEquity = r;
        }
        Map<String, Object> bal = new LinkedHashMap<>();
        if (assets == null || liabEquity == null) {
            bal.put("balanced", false);
            bal.put("message", "未找到资产总计/负债和所有者权益总计行，无法校验");
            return bal;
        }
        BigDecimal a = bd(assets.get("amount"));
        BigDecimal le = bd(liabEquity.get("amount"));
        BigDecimal ab = bd(assets.get("beginAmount"));
        BigDecimal leb = bd(liabEquity.get("beginAmount"));
        BigDecimal diff = a.subtract(le);
        BigDecimal diffBegin = ab.subtract(leb);
        boolean balanced = diff.abs().compareTo(TOL) < 0;
        boolean beginBalanced = diffBegin.abs().compareTo(TOL) < 0;
        bal.put("assetsAmount", a);
        bal.put("liabEquityAmount", le);
        bal.put("diff", diff);
        bal.put("balanced", balanced);
        bal.put("beginDiff", diffBegin);
        bal.put("beginBalanced", beginBalanced);
        bal.put("message", balanced
                ? (beginBalanced ? "资产总计 = 负债和所有者权益总计，表已平衡"
                                : "期末平衡，但年初数差额 " + money(diffBegin) + "，请检查启用期初")
                : "资产总计与负债和所有者权益总计差额 " + money(diff) + "，请检查凭证是否全部过账、损益是否结转");
        return bal;
    }

    // ==================== 业财对账 ====================

    /** 业财对账：应收/应付/资金三组，含明细。period 为空取进行中期间（按当前时点对账）。 */
    public Map<String, Object> reconcile(String period) {
        String per = resolvePeriod(period);
        calc.invalidate();

        List<Map<String, Object>> groups = new ArrayList<>();
        groups.add(reconcileAr(per));
        groups.add(reconcileAp(per));
        groups.add(reconcileCash(per));

        boolean matched = true;
        for (Map<String, Object> g : groups)
            if (!Boolean.TRUE.equals(g.get("matched"))) matched = false;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", per);
        result.put("matched", matched);
        result.put("groups", groups);
        return result;
    }

    /** 应收账款 1122（借方向，GL 余额 d−c）vs fin_ar 未收金额。 */
    private Map<String, Object> reconcileAr(String period) {
        BigDecimal gl = calc.qm("1122", period);
        BigDecimal biz = sum0("SELECT COALESCE(SUM(unreceived_amount),0) FROM fin_ar");
        // GL 侧按客户辅助（aux_customer 存编码，fin_ar.customer 存名称，统一成名称）
        Map<String, String> customerNames = nameMap("base_customer", "customer_code", "customer_name");
        Map<String, BigDecimal[]> glDetail = auxDetail("1122", "aux_customer", period, customerNames, true);
        Map<String, BigDecimal> bizDetail = new LinkedHashMap<>();
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc,
                "SELECT customer, SUM(unreceived_amount) bal FROM fin_ar GROUP BY customer HAVING SUM(unreceived_amount) > 0.004")) {
            bizDetail.merge(TmsUtil.str(r.get("customer")), bd(r.get("bal")), BigDecimal::add);
        }
        return group("ar", "应收账款（1122） vs 应收单未收", gl, biz,
                "总账 1122 借方余额", "业务应收单未收合计", mergeDetail(glDetail, bizDetail));
    }

    /** 应付账款 2202（贷方向，GL 余额 c−d 为正）vs fin_ap 未付金额。 */
    private Map<String, Object> reconcileAp(String period) {
        BigDecimal gl = calc.qm("2202", period);
        BigDecimal biz = sum0("SELECT COALESCE(SUM(unpaid_amount),0) FROM fin_ap");
        Map<String, String> supplierNames = nameMap("base_supplier", "supplier_code", "supplier_name");
        Map<String, BigDecimal[]> glDetail = auxDetail("2202", "aux_supplier", period, supplierNames, false);
        Map<String, BigDecimal> bizDetail = new LinkedHashMap<>();
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc,
                "SELECT supplier, SUM(unpaid_amount) bal FROM fin_ap GROUP BY supplier HAVING SUM(unpaid_amount) > 0.004")) {
            bizDetail.merge(TmsUtil.str(r.get("supplier")), bd(r.get("bal")), BigDecimal::add);
        }
        return group("ap", "应付账款（2202） vs 应付单未付", gl, biz,
                "总账 2202 贷方余额", "业务应付单未付合计", mergeDetail(glDetail, bizDetail));
    }

    /** 资金科目（is_cash）vs 资金账户余额（base_fund_account.gl_account_code 映射）。 */
    private Map<String, Object> reconcileCash(String period) {
        // GL 现金科目
        Map<String, String> cashAccounts = new LinkedHashMap<>();
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc,
                "SELECT account_code, account_name FROM fin_account WHERE is_cash = TRUE AND status = '启用'")) {
            cashAccounts.put(TmsUtil.str(r.get("accountCode")), TmsUtil.str(r.get("accountName")));
        }
        BigDecimal glTotal = BigDecimal.ZERO;
        for (String code : cashAccounts.keySet()) glTotal = glTotal.add(calc.qm(code, period));

        // 业务资金账户
        List<Map<String, Object>> funds = TmsUtil.queryCamel(jdbc,
                "SELECT fund_account_code, fund_account_name, gl_account_code, balance " +
                        "FROM base_fund_account WHERE status = 'NORMAL' ORDER BY fund_account_code");
        BigDecimal bizTotal = BigDecimal.ZERO;
        Map<String, BigDecimal> glByCode = new HashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();
        for (Map<String, Object> f : funds) {
            String fCode = TmsUtil.str(f.get("fundAccountCode"));
            String fName = TmsUtil.str(f.get("fundAccountName"));
            String glCode = TmsUtil.str(f.get("glAccountCode"));
            BigDecimal bizAmt = bd(f.get("balance"));
            bizTotal = bizTotal.add(bizAmt);
            // 未映射科目的父级/空户（余额恒 0）不进明细，只在有余额或有映射时展示
            if (glCode.isEmpty() && bizAmt.abs().compareTo(TOL) < 0) continue;
            BigDecimal glAmt = null;
            if (!glCode.isEmpty()) {
                glAmt = glByCode.computeIfAbsent(glCode, c -> calc.qm(c, period));
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("party", fCode + " " + fName);
            d.put("bizAmount", bizAmt);
            d.put("glAmount", glAmt);
            d.put("glAccountCode", glCode);
            d.put("diff", glAmt == null ? null : glAmt.subtract(bizAmt));
            details.add(d);
        }
        // GL 现金科目未被任何资金账户映射的，单列一行
        for (Map.Entry<String, String> e : cashAccounts.entrySet()) {
            boolean mapped = funds.stream().anyMatch(f ->
                    e.getKey().equals(TmsUtil.str(f.get("glAccountCode"))));
            if (!mapped) {
                BigDecimal glAmt = calc.qm(e.getKey(), period);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("party", "（无资金账户映射）" + e.getKey() + " " + e.getValue());
                d.put("bizAmount", null);
                d.put("glAmount", glAmt);
                d.put("glAccountCode", e.getKey());
                d.put("diff", glAmt);
                details.add(d);
            }
        }
        Map<String, Object> g = group("cash", "资金科目 vs 资金账户余额", glTotal, bizTotal,
                "总账现金类科目余额合计", "资金账户余额合计", details);
        g.put("glLabel", "总账资金合计");
        g.put("bizLabel", "资金账户合计");
        return g;
    }

    // ==================== 现金流量项目补录 ====================

    /** 待补录现金流量项目的已过账分录（现金类科目、cash_flow_item 为空）。period 为空查全部。 */
    public List<Map<String, Object>> cfPending(String period) {
        String per = TmsUtil.str(period);
        String filter = per.isEmpty() ? "" : " AND v.period = ?";
        String sql =
                "SELECT e.id entry_id, v.id voucher_id, v.voucher_no, v.voucher_date, v.period, " +
                "       e.line_no, e.summary, e.account_code, a.account_name, " +
                "       e.debit_amount, e.credit_amount " +
                "FROM fin_voucher_entry e " +
                "JOIN fin_voucher v ON v.id = e.voucher_id " +
                "JOIN fin_account a ON a.account_code = e.account_code " +
                "WHERE a.is_cash = TRUE AND v.status = '已过账' AND e.cash_flow_item IS NULL" + filter + " " +
                "ORDER BY v.period, v.voucher_no, e.line_no";
        List<Map<String, Object>> rows = per.isEmpty()
                ? TmsUtil.queryCamel(jdbc, sql)
                : TmsUtil.queryCamel(jdbc, sql, per);
        for (Map<String, Object> r : rows) {
            BigDecimal d = bd(r.get("debitAmount"));
            BigDecimal c = bd(r.get("creditAmount"));
            r.put("amount", d.add(c));
            r.put("direction", d.compareTo(c) >= 0 ? "流入（借）" : "流出（贷）");
        }
        return rows;
    }

    /** 现金流量项目下拉。 */
    public List<Map<String, Object>> cfItems() {
        return TmsUtil.queryCamel(jdbc,
                "SELECT item_code, item_name, category, direction FROM fin_cash_flow_item ORDER BY item_code");
    }

    /**
     * 批量补录现金流量项目。body.items: [{entryId, cashFlowItem}]。
     * 只允许已过账凭证的现金类分录；项目编码必须存在。补录后刷新取数缓存。
     */
    @Transactional
    public Map<String, Object> cfFill(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("没有需要补录的分录");
        int updated = 0;
        List<String> done = new ArrayList<>();
        for (Map<String, Object> it : items) {
            String entryId = TmsUtil.str(it.get("entryId"));
            String cf = TmsUtil.str(it.get("cashFlowItem"));
            if (entryId.isEmpty() || cf.isEmpty()) continue;
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM fin_cash_flow_item WHERE item_code = ?", Integer.class, cf);
            if (cnt == null || cnt == 0) throw new IllegalArgumentException("现金流量项目不存在：" + cf);
            List<Map<String, Object>> targets = TmsUtil.queryCamel(jdbc,
                    "SELECT e.id, v.voucher_no, v.period, a.account_code " +
                            "FROM fin_voucher_entry e " +
                            "JOIN fin_voucher v ON v.id = e.voucher_id " +
                            "JOIN fin_account a ON a.account_code = e.account_code " +
                            "WHERE e.id = ? AND a.is_cash = TRUE AND v.status = '已过账'", entryId);
            if (targets.isEmpty())
                throw new IllegalArgumentException("分录 " + entryId + " 不是已过账凭证的现金类分录，不能补录");
            int n = jdbc.update("UPDATE fin_voucher_entry SET cash_flow_item = ? WHERE id = ?", cf, entryId);
            if (n > 0) {
                updated++;
                Map<String, Object> t = targets.get(0);
                done.add(TmsUtil.str(t.get("voucherNo")) + " 分录→" + cf);
            }
        }
        calc.invalidate();
        TmsUtil.log(jdbc, "finance.gl.report", "CF_FILL", "",
                "现金流量项目批量补录 " + updated + " 条：" + String.join("；", done));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        return result;
    }

    // ==================== 私有辅助 ====================

    /**
     * 辅助核算明细余额：已过账分录 + 启用期初，按辅助维度汇总。
     * 返回 party(名称) -> [glSignedAmount, rawDebit, rawCredit]；debitSide=true 借方向科目取 d−c，否则 c−d。
     */
    private Map<String, BigDecimal[]> auxDetail(String accountPrefix, String auxColumn, String period,
                                                Map<String, String> codeToName, boolean debitSide) {
        // 显式拼列名（auxColumn 来自内部常量，无注入面）
        String sql =
                "SELECT party, SUM(d) d, SUM(c) c FROM (" +
                " SELECT e." + auxColumn + " party, SUM(e.debit_amount) d, SUM(e.credit_amount) c " +
                " FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                " WHERE e.account_code LIKE ? AND v.status IN ('已过账','已冲销') AND v.period <= ? " +
                " GROUP BY e." + auxColumn +
                " UNION ALL " +
                " SELECT b." + auxColumn + ", SUM(b.open_debit), SUM(b.open_credit) " +
                " FROM fin_init_balance b WHERE b.account_code LIKE ? GROUP BY b." + auxColumn +
                ") t GROUP BY party";
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc, sql,
                accountPrefix + "%", period, accountPrefix + "%")) {
            String code = TmsUtil.str(r.get("party"));
            if (code.isEmpty()) code = "（未挂辅助核算）";
            String name = codeToName.getOrDefault(code, code);
            BigDecimal d = bd(r.get("d"));
            BigDecimal c = bd(r.get("c"));
            BigDecimal signed = debitSide ? d.subtract(c) : c.subtract(d);
            if (signed.abs().compareTo(TOL) < 0) continue;
            out.merge(name, new BigDecimal[]{signed, d, c}, (a, b) ->
                    new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1]), a[2].add(b[2])});
        }
        return out;
    }

    /** 合并 GL 辅助明细与业务往来明细为对账行（GL 已按科目方向带号，业务侧恒为正）。 */
    private List<Map<String, Object>> mergeDetail(Map<String, BigDecimal[]> glDetail,
                                                  Map<String, BigDecimal> bizDetail) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal[]> e : glDetail.entrySet()) {
            Map<String, Object> row = merged.computeIfAbsent(e.getKey(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("party", k);
                m.put("glAmount", BigDecimal.ZERO);
                m.put("bizAmount", BigDecimal.ZERO);
                return m;
            });
            row.put("glAmount", e.getValue()[0]);
        }
        for (Map.Entry<String, BigDecimal> e : bizDetail.entrySet()) {
            Map<String, Object> row = merged.computeIfAbsent(e.getKey(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("party", k);
                m.put("glAmount", BigDecimal.ZERO);
                m.put("bizAmount", BigDecimal.ZERO);
                return m;
            });
            row.put("bizAmount", e.getValue());
        }
        List<Map<String, Object>> list = new ArrayList<>(merged.values());
        for (Map<String, Object> row : list) {
            BigDecimal g = bd(row.get("glAmount"));
            BigDecimal b = bd(row.get("bizAmount"));
            row.put("glAmount", g);
            row.put("bizAmount", b);
            row.put("diff", g.subtract(b));
        }
        list.sort((a, b) -> TmsUtil.str(a.get("party")).compareTo(TmsUtil.str(b.get("party"))));
        return list;
    }

    private Map<String, Object> group(String key, String name, BigDecimal glAmount, BigDecimal bizAmount,
                                      String glLabel, String bizLabel, List<Map<String, Object>> details) {
        BigDecimal diff = glAmount.subtract(bizAmount);
        boolean matched = diff.abs().compareTo(TOL) < 0;
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("key", key);
        g.put("name", name);
        g.put("glAmount", glAmount);
        g.put("bizAmount", bizAmount);
        g.put("diff", diff);
        g.put("matched", matched);
        g.put("glLabel", glLabel);
        g.put("bizLabel", bizLabel);
        g.put("details", details);
        return g;
    }

    /** 档案 code→name 映射（辅助核算存编码，业务单据存名称）。 */
    private Map<String, String> nameMap(String table, String codeCol, String nameCol) {
        Map<String, String> map = new HashMap<>();
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc,
                "SELECT " + codeCol + " code, " + nameCol + " name FROM " + table)) {
            map.put(TmsUtil.str(r.get("code")), TmsUtil.str(r.get("name")));
        }
        return map;
    }

    private BigDecimal sum0(String sql) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    private String resolvePeriod(String period) {
        String per = TmsUtil.str(period);
        if (!per.isEmpty()) return per;
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT period FROM fin_accounting_period WHERE status = ? ORDER BY period LIMIT 1",
                GlConst.P_IN_PROGRESS);
        if (rows.isEmpty()) throw new IllegalArgumentException("没有进行中的会计期间，请检查总账初始化");
        return TmsUtil.str(rows.get(0).get("period"));
    }

    private static BigDecimal bd(Object o) {
        return o == null ? BigDecimal.ZERO : TmsUtil.toBd(o);
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
