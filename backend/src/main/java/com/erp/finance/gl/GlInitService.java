package com.erp.finance.gl;

import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 总账初始化服务：会计期间生成、期初余额录入、试算平衡、一键引入业务期初、启用总账。
 * 启用后期初锁定（只能通过凭证调整）。
 */
@Service
public class GlInitService {

    private final JdbcTemplate jdbc;
    private final SysParamService sysParam;

    public GlInitService(JdbcTemplate jdbc, SysParamService sysParam) {
        this.jdbc = jdbc;
        this.sysParam = sysParam;
    }

    public boolean isInitialized() {
        return sysParam.getBool(GlConst.PARAM_INITIALIZED, false);
    }

    /** 总账初始化状态。 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("initialized", isInitialized());
        m.put("enabled", sysParam.getBool(GlConst.PARAM_ENABLED, false));
        m.put("startPeriod", sysParam.get(GlConst.PARAM_START_PERIOD, ""));
        m.put("startYear", sysParam.get(GlConst.PARAM_START_YEAR, ""));
        String current = "";
        List<Map<String, Object>> periods = TmsUtil.queryCamel(jdbc,
                "SELECT period FROM fin_accounting_period WHERE status = ?", GlConst.P_IN_PROGRESS);
        if (!periods.isEmpty()) current = TmsUtil.str(periods.get(0).get("period"));
        m.put("currentPeriod", current);
        return m;
    }

    /** 期间列表（未生成时返回空，启用时生成）。 */
    public List<Map<String, Object>> periodList() {
        return TmsUtil.queryCamel(jdbc,
                "SELECT id, period_year, period_no, period, start_date, end_date, status, " +
                "settle_name, settle_time, reopen_name, reopen_time, reopen_reason " +
                "FROM fin_accounting_period ORDER BY period");
    }

    /** 生成指定年度 12 个会计期间（已存在则跳过）。 */
    public void generatePeriods(int year) {
        for (int m = 1; m <= 12; m++) {
            String period = String.format("%d%02d", year, m);
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fin_accounting_period WHERE period = ?", Integer.class, period);
            if (cnt != null && cnt > 0) continue;
            LocalDate start = LocalDate.of(year, m, 1);
            LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
            jdbc.update("INSERT INTO fin_accounting_period(id, period_year, period_no, period, start_date, end_date, status) " +
                    "VALUES (?,?,?,?,?,?,?)",
                    TmsUtil.uuid("FP"), year, m, period,
                    java.sql.Date.valueOf(start), java.sql.Date.valueOf(end), GlConst.P_NOT_STARTED);
        }
    }

    /** 期初余额主科目行（末级科目 LEFT JOIN，零发生科目也出场）。 */
    public List<Map<String, Object>> mainBalanceList() {
        return TmsUtil.queryCamel(jdbc,
                "SELECT a.id, a.account_code, a.account_name, " +
                "a.parent_code, a.account_level, a.account_type, " +
                "a.balance_direction, a.aux_dimensions, a.is_qty, " +
                "a.is_cash, a.sort_order, " +
                "b.id balance_id, b.open_debit, b.open_credit, b.open_qty, " +
                "b.ytd_debit, b.ytd_credit " +
                "FROM fin_account a " +
                "LEFT JOIN fin_init_balance b ON b.account_code = a.account_code " +
                "  AND b.aux_customer IS NULL AND b.aux_supplier IS NULL AND b.aux_department IS NULL " +
                "  AND b.aux_employee IS NULL AND b.aux_goods IS NULL AND b.aux_project IS NULL AND b.aux_area IS NULL " +
                "WHERE a.is_leaf = TRUE ORDER BY a.account_code");
    }

    /** 指定科目的辅助核算明细期初行。 */
    public List<Map<String, Object>> auxBalanceList(String accountCode) {
        return TmsUtil.queryCamel(jdbc,
                "SELECT id, account_code, aux_customer, aux_supplier, " +
                "aux_department, aux_employee, aux_goods, " +
                "aux_project, aux_area, " +
                "open_debit, open_credit, open_qty, " +
                "ytd_debit, ytd_credit " +
                "FROM fin_init_balance WHERE account_code = ? " +
                "AND (aux_customer IS NOT NULL OR aux_supplier IS NOT NULL OR aux_department IS NOT NULL " +
                "  OR aux_employee IS NOT NULL OR aux_goods IS NOT NULL OR aux_project IS NOT NULL OR aux_area IS NOT NULL) " +
                "ORDER BY aux_customer, aux_supplier, aux_department, aux_employee, aux_goods, aux_project, aux_area",
                accountCode);
    }

    /**
     * 批量保存期初余额（可主科目行可辅助行）。全零行删除。
     * 启用后锁定不可改。
     */
    public void saveBalances(List<Map<String, Object>> rows) {
        if (isInitialized()) throw new IllegalArgumentException("总账已启用，期初余额已锁定，不能修改");
        if (rows == null) return;
        for (Map<String, Object> req : rows) {
            String code = TmsUtil.str(req.get("accountCode"));
            Map<String, Object> acc = account(code);
            if (acc == null) throw new IllegalArgumentException("科目不存在：" + code);
            if (!Boolean.TRUE.equals(acc.get("isLeaf"))) throw new IllegalArgumentException("只有末级科目才能录入期初：" + code);

            BigDecimal openDebit = TmsUtil.toBd(req.get("openDebit"));
            BigDecimal openCredit = TmsUtil.toBd(req.get("openCredit"));
            BigDecimal openQty = TmsUtil.toBd(req.get("openQty"));
            BigDecimal ytdDebit = TmsUtil.toBd(req.get("ytdDebit"));
            BigDecimal ytdCredit = TmsUtil.toBd(req.get("ytdCredit"));
            if (openDebit.signum() < 0 || openCredit.signum() < 0) throw new IllegalArgumentException("期初金额不能为负：" + code);
            if (openDebit.signum() > 0 && openCredit.signum() > 0)
                throw new IllegalArgumentException("科目 " + code + " 期初不能同时有借方和贷方余额");

            String auxDim = TmsUtil.str(acc.get("auxDimensions"));
            Map<String, String> aux = extractAux(req, auxDim);
            boolean isAuxRow = aux.values().stream().anyMatch(s -> !s.isEmpty());

            // 辅助维度必须是该科目已配置的维度
            if (isAuxRow && auxDim.isEmpty())
                throw new IllegalArgumentException("科目 " + code + " 未设置辅助核算，不能录辅助明细");

            boolean allZero = openDebit.signum() == 0 && openCredit.signum() == 0
                    && openQty.signum() == 0 && ytdDebit.signum() == 0 && ytdCredit.signum() == 0;

            if (isAuxRow) {
                if (mainRowExists(code) && !allZero)
                    throw new IllegalArgumentException("科目 " + code + " 已有主科目期初，请先清空主科目行再录辅助明细");
            } else {
                if (auxRowExists(code) && !allZero)
                    throw new IllegalArgumentException("科目 " + code + " 已有辅助核算明细期初，请在辅助明细中维护");
            }

            String existId = findBalanceId(code, aux);
            if (allZero) {
                if (existId != null) jdbc.update("DELETE FROM fin_init_balance WHERE id = ?", existId);
                continue;
            }
            if (existId != null) {
                jdbc.update("UPDATE fin_init_balance SET open_debit=?, open_credit=?, open_qty=?, " +
                        "ytd_debit=?, ytd_credit=?, update_time=CURRENT_TIMESTAMP WHERE id=?",
                        openDebit, openCredit, openQty, ytdDebit, ytdCredit, existId);
            } else {
                jdbc.update("INSERT INTO fin_init_balance(id, account_code, aux_customer, aux_supplier, aux_department, " +
                        "aux_employee, aux_goods, aux_project, aux_area, open_debit, open_credit, open_qty, " +
                        "ytd_debit, ytd_credit, creator_name) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        TmsUtil.uuid("IB"), code,
                        nullIfEmpty(aux.get(GlConst.DIM_CUSTOMER)), nullIfEmpty(aux.get(GlConst.DIM_SUPPLIER)),
                        nullIfEmpty(aux.get(GlConst.DIM_DEPARTMENT)), nullIfEmpty(aux.get(GlConst.DIM_EMPLOYEE)),
                        nullIfEmpty(aux.get(GlConst.DIM_GOODS)), nullIfEmpty(aux.get(GlConst.DIM_PROJECT)),
                        nullIfEmpty(aux.get(GlConst.DIM_AREA)),
                        openDebit, openCredit, openQty, ytdDebit, ytdCredit, TmsUtil.currentUser());
            }
        }
    }

    /** 试算平衡：按科目汇总期初借贷，返回合计与逐科目明细。 */
    public Map<String, Object> trialBalance() {
        List<Map<String, Object>> agg = TmsUtil.queryCamel(jdbc,
                "SELECT account_code, SUM(open_debit) open_debit, SUM(open_credit) open_credit, " +
                "SUM(ytd_debit) ytd_debit, SUM(ytd_credit) ytd_credit, SUM(open_qty) open_qty " +
                "FROM fin_init_balance GROUP BY account_code");
        BigDecimal totalDebit = BigDecimal.ZERO, totalCredit = BigDecimal.ZERO;
        List<Map<String, Object>> details = new ArrayList<>();
        for (Map<String, Object> row : agg) {
            Map<String, Object> acc = account(TmsUtil.str(row.get("accountCode")));
            if (acc == null) continue;
            BigDecimal d = TmsUtil.toBd(row.get("openDebit"));
            BigDecimal c = TmsUtil.toBd(row.get("openCredit"));
            totalDebit = totalDebit.add(d);
            totalCredit = totalCredit.add(c);
            Map<String, Object> dRow = new LinkedHashMap<>(row);
            dRow.put("accountName", acc.get("accountName"));
            dRow.put("balanceDirection", acc.get("balanceDirection"));
            details.add(dRow);
        }
        details.sort((a, b) -> TmsUtil.str(a.get("accountCode")).compareTo(TmsUtil.str(b.get("accountCode"))));
        BigDecimal diff = totalDebit.subtract(totalCredit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDebit", totalDebit);
        result.put("totalCredit", totalCredit);
        result.put("diff", diff);
        result.put("balanced", diff.abs().compareTo(new BigDecimal("0.005")) < 0);
        result.put("details", details);
        return result;
    }

    /**
     * 一键引入业务期初：应收（fin_ar→1122 客户辅助）、应付（fin_ap→2202 供应商辅助）、
     * 库存商品（inv_stock_balance→1405 商品辅助，数量+成本金额）。
     * 资金账户期初在 M4 档案映射上线后引入（base_fund_account.gl_account_code）。
     */
    public Map<String, Object> importBusiness() {
        if (isInitialized()) throw new IllegalArgumentException("总账已启用，不能再引入期初");
        ensureLeafSubject("1122");
        ensureLeafSubject("2202");
        ensureLeafSubject("1405");
        Map<String, Object> result = new LinkedHashMap<>();

        // 应收：按客户汇总未收金额
        List<Map<String, Object>> arRows = TmsUtil.queryCamel(jdbc,
                "SELECT customer, SUM(unreceived_amount) bal FROM fin_ar " +
                "GROUP BY customer HAVING SUM(unreceived_amount) > 0.004");
        int arCount = 0;
        BigDecimal arTotal = BigDecimal.ZERO;
        for (Map<String, Object> r : arRows) {
            String name = TmsUtil.str(r.get("customer"));
            String code = lookupCode("base_customer", "customer_code", "customer_name", name, name);
            upsertAuxBalance("1122", GlConst.DIM_CUSTOMER, code, name,
                    TmsUtil.toBd(r.get("bal")), BigDecimal.ZERO, BigDecimal.ZERO);
            arCount++;
            arTotal = arTotal.add(TmsUtil.toBd(r.get("bal")));
        }
        result.put("arCount", arCount);
        result.put("arTotal", arTotal);

        // 应付：按供应商汇总未付金额
        List<Map<String, Object>> apRows = TmsUtil.queryCamel(jdbc,
                "SELECT supplier, SUM(unpaid_amount) bal FROM fin_ap " +
                "GROUP BY supplier HAVING SUM(unpaid_amount) > 0.004");
        int apCount = 0;
        BigDecimal apTotal = BigDecimal.ZERO;
        for (Map<String, Object> r : apRows) {
            String name = TmsUtil.str(r.get("supplier"));
            String code = lookupCode("base_supplier", "supplier_code", "supplier_name", name, name);
            upsertAuxBalance("2202", GlConst.DIM_SUPPLIER, code, name,
                    BigDecimal.ZERO, TmsUtil.toBd(r.get("bal")), BigDecimal.ZERO);
            apCount++;
            apTotal = apTotal.add(TmsUtil.toBd(r.get("bal")));
        }
        result.put("apCount", apCount);
        result.put("apTotal", apTotal);

        // 库存商品：按商品汇总实存数量与成本金额
        List<Map<String, Object>> goodsRows = TmsUtil.queryCamel(jdbc,
                "SELECT goods_code, MAX(goods_name) goods_name, SUM(physical_qty) qty, SUM(stock_amount) amt " +
                "FROM inv_stock_balance GROUP BY goods_code " +
                "HAVING SUM(physical_qty) <> 0 OR SUM(stock_amount) <> 0");
        int goodsCount = 0;
        BigDecimal goodsTotal = BigDecimal.ZERO, goodsQty = BigDecimal.ZERO;
        for (Map<String, Object> r : goodsRows) {
            String code = TmsUtil.str(r.get("goodsCode"));
            String name = TmsUtil.str(r.get("goodsName"));
            upsertAuxBalance("1405", GlConst.DIM_GOODS, code, name,
                    TmsUtil.toBd(r.get("amt")), BigDecimal.ZERO, TmsUtil.toBd(r.get("qty")));
            goodsCount++;
            goodsTotal = goodsTotal.add(TmsUtil.toBd(r.get("amt")));
            goodsQty = goodsQty.add(TmsUtil.toBd(r.get("qty")));
        }
        result.put("goodsCount", goodsCount);
        result.put("goodsTotal", goodsTotal);
        result.put("goodsQty", goodsQty);
        TmsUtil.log(jdbc, "finance.gl.init", "IMPORT", "",
                "一键引入业务期初：应收 " + arCount + " 户/" + arTotal + "，应付 " + apCount + " 户/" + apTotal
                        + "，库存 " + goodsCount + " 品/" + goodsTotal);
        return result;
    }

    /** 启用总账：试算平衡通过后生成期间、写参数。startPeriod=yyyyMM。 */
    public void enable(String startPeriod) {
        if (isInitialized()) throw new IllegalArgumentException("总账已启用，不能重复启用");
        if (startPeriod == null || !startPeriod.matches("\\d{6}"))
            throw new IllegalArgumentException("启用期间格式应为 yyyyMM，如 202609");
        Map<String, Object> trial = trialBalance();
        if (!Boolean.TRUE.equals(trial.get("balanced")))
            throw new IllegalArgumentException("试算不平衡，借方合计 " + trial.get("totalDebit")
                    + "，贷方合计 " + trial.get("totalCredit") + "，差额 " + trial.get("diff") + "，不能启用");
        int year = Integer.parseInt(startPeriod.substring(0, 4));
        int month = Integer.parseInt(startPeriod.substring(4, 6));
        generatePeriods(year);
        // 启用月份之前的期间标记已结账（期初已含年初至启用的累计发生），启用月进行中
        for (int m = 1; m <= 12; m++) {
            String period = String.format("%d%02d", year, m);
            String status = m < month ? GlConst.P_CLOSED : (m == month ? GlConst.P_IN_PROGRESS : GlConst.P_NOT_STARTED);
            if (m < month) {
                jdbc.update("UPDATE fin_accounting_period SET status = ?, settle_name = '系统启用', settle_time = CURRENT_TIMESTAMP " +
                        "WHERE period = ?", status, period);
            } else {
                jdbc.update("UPDATE fin_accounting_period SET status = ? WHERE period = ?", status, period);
            }
        }
        setParam(GlConst.PARAM_INITIALIZED, "Y", "总账已启用");
        setParam(GlConst.PARAM_ENABLED, "Y", "总账功能开关");
        setParam(GlConst.PARAM_START_YEAR, String.valueOf(year), "总账启用年度");
        setParam(GlConst.PARAM_START_PERIOD, startPeriod, "总账启用期间");
        TmsUtil.log(jdbc, "finance.gl.init", "ENABLE", startPeriod, "启用总账，启用期间 " + startPeriod);
    }

    // ==================== 内部方法 ====================

    private Map<String, Object> account(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT account_code, account_name, account_type, " +
                "balance_direction, aux_dimensions, is_leaf, status " +
                "FROM fin_account WHERE account_code = ?", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void ensureLeafSubject(String code) {
        Map<String, Object> acc = account(code);
        if (acc == null) throw new IllegalArgumentException("预置科目 " + code + " 不存在，请检查科目种子数据");
        if (!Boolean.TRUE.equals(acc.get("isLeaf"))) throw new IllegalArgumentException("科目 " + code + " 不是末级科目");
    }

    /** 从请求行提取 7 个辅助维度值（只保留科目已配置的维度，其他维度传值报错）。 */
    private Map<String, String> extractAux(Map<String, Object> req, String auxDim) {
        Map<String, String> aux = new LinkedHashMap<>();
        for (String dim : GlConst.AUX_DIMS) {
            String val = TmsUtil.str(req.get("aux" + capitalize(dim)));
            aux.put(dim, val);
            if (!val.isEmpty() && (auxDim.isEmpty() || !List.of(auxDim.split(",")).contains(dim))) {
                throw new IllegalArgumentException("该科目未配置辅助核算维度：" + GlConst.AUX_LABELS.get(dim));
            }
        }
        return aux;
    }

    private String findBalanceId(String code, Map<String, String> aux) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT id, aux_customer c, aux_supplier s, aux_department d, aux_employee e, " +
                "aux_goods g, aux_project p, aux_area a FROM fin_init_balance WHERE account_code = ?", code);
        for (Map<String, Object> r : rows) {
            if (eq(r.get("c"), aux.get(GlConst.DIM_CUSTOMER))
                    && eq(r.get("s"), aux.get(GlConst.DIM_SUPPLIER))
                    && eq(r.get("d"), aux.get(GlConst.DIM_DEPARTMENT))
                    && eq(r.get("e"), aux.get(GlConst.DIM_EMPLOYEE))
                    && eq(r.get("g"), aux.get(GlConst.DIM_GOODS))
                    && eq(r.get("p"), aux.get(GlConst.DIM_PROJECT))
                    && eq(r.get("a"), aux.get(GlConst.DIM_AREA))) {
                return TmsUtil.str(r.get("id"));
            }
        }
        return null;
    }

    private boolean eq(Object dbVal, String reqVal) {
        String a = dbVal == null ? "" : TmsUtil.str(dbVal);
        String b = reqVal == null ? "" : reqVal;
        return a.equals(b);
    }

    private boolean mainRowExists(String code) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_init_balance WHERE account_code = ? " +
                "AND aux_customer IS NULL AND aux_supplier IS NULL AND aux_department IS NULL " +
                "AND aux_employee IS NULL AND aux_goods IS NULL AND aux_project IS NULL AND aux_area IS NULL " +
                "AND (open_debit > 0 OR open_credit > 0 OR open_qty > 0 OR ytd_debit > 0 OR ytd_credit > 0)",
                Integer.class, code);
        return cnt != null && cnt > 0;
    }

    private boolean auxRowExists(String code) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_init_balance WHERE account_code = ? " +
                "AND (aux_customer IS NOT NULL OR aux_supplier IS NOT NULL OR aux_department IS NOT NULL " +
                "OR aux_employee IS NOT NULL OR aux_goods IS NOT NULL OR aux_project IS NOT NULL OR aux_area IS NOT NULL)",
                Integer.class, code);
        return cnt != null && cnt > 0;
    }

    /** 业务期初引入用：辅助行 upsert（金额累加，重复引入不翻倍——按同一辅助键覆盖）。 */
    private void upsertAuxBalance(String accountCode, String dim, String auxCode, String auxName,
                                  BigDecimal debit, BigDecimal credit, BigDecimal qty) {
        Map<String, String> aux = new LinkedHashMap<>();
        for (String d : GlConst.AUX_DIMS) aux.put(d, d.equals(dim) ? auxCode : "");
        String existId = findBalanceId(accountCode, aux);
        if (existId != null) {
            jdbc.update("UPDATE fin_init_balance SET open_debit=?, open_credit=?, open_qty=?, update_time=CURRENT_TIMESTAMP WHERE id=?",
                    debit, credit, qty, existId);
        } else {
            jdbc.update("INSERT INTO fin_init_balance(id, account_code, aux_customer, aux_supplier, aux_department, " +
                    "aux_employee, aux_goods, aux_project, aux_area, open_debit, open_credit, open_qty, creator_name) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    TmsUtil.uuid("IB"), accountCode,
                    nullIfEmpty(aux.get(GlConst.DIM_CUSTOMER)), nullIfEmpty(aux.get(GlConst.DIM_SUPPLIER)),
                    nullIfEmpty(aux.get(GlConst.DIM_DEPARTMENT)), nullIfEmpty(aux.get(GlConst.DIM_EMPLOYEE)),
                    nullIfEmpty(aux.get(GlConst.DIM_GOODS)), nullIfEmpty(aux.get(GlConst.DIM_PROJECT)),
                    nullIfEmpty(aux.get(GlConst.DIM_AREA)),
                    debit, credit, qty, TmsUtil.currentUser());
        }
    }

    /** 按名称查档案编码；查不到时回退用名称本身（保证辅助值可读）。 */
    private String lookupCode(String table, String codeCol, String nameCol, String name, String fallback) {
        try {
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                    "SELECT " + codeCol + " code FROM " + table + " WHERE " + nameCol + " = ? LIMIT 1", name);
            if (!rows.isEmpty()) return TmsUtil.str(rows.get(0).get("code"));
        } catch (Exception ignored) {}
        return fallback;
    }

    private void setParam(String key, String value, String name) {
        jdbc.update("INSERT INTO sys_param_runtime(param_id, param_key, param_name, param_value, default_value, param_group) " +
                "SELECT ?,?,?,?,?, '总账' WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key = ?)",
                TmsUtil.uuid("PM"), key, name, value, value, key);
        jdbc.update("UPDATE sys_param_runtime SET param_value = ? WHERE param_key = ?", value, key);
        sysParam.evict();
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
