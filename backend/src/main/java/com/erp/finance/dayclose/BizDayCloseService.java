package com.erp.finance.dayclose;

import com.erp.tms.TmsUtil;
import com.erp.finance.gl.GlConst;
import com.erp.report.dws.PurchaseDwsService;
import com.erp.report.dws.SalesDwsService;
import com.erp.report.dws.StockMoveDwsService;
import com.erp.report.dws.StockSnapshotService;
import com.erp.system.OperationAction;
import com.erp.system.OperationLogService;
import com.erp.system.SysParamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务日结领域服务（PRD-33 §4/§7.3）。
 *
 * <p>负责：日结向导 7 步预览、结账事务（DWS 刷新勾稽 → 四套滚存 → 定版生成 → 落账）、
 * 反日结（单日/批量，跨月结硬拦）、定时任务补结循环、未日结提醒数据。
 *
 * <p>硬阻断项（连续性/期初/DWS/四套滚存）失败抛 {@link IllegalArgumentException}（中文直接展示）；
 * 挂账单/异常数据/GL 事件为提示项；资金档案差异与现金实盘仅备注（v1.3 终审）。
 */
@Service
public class BizDayCloseService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final SysParamService sysParam;
    private final BizDayCloseGuard guard;
    private final BizCloseSnapshotService snapshotService;
    private final BizGoodsCloseSnapshotService goodsSnapshot;
    private final PurchaseDwsService purchaseDws;
    private final SalesDwsService salesDws;
    private final StockMoveDwsService stockMoveDws;
    private final StockSnapshotService stockSnapshot;
    private final OperationLogService opLog;
    /** 补结循环按天通过代理自调，保证每天独立事务。 */
    private final BizDayCloseService self;

    public BizDayCloseService(JdbcTemplate jdbcTemplate, SysParamService sysParam,
                              BizDayCloseGuard guard, BizCloseSnapshotService snapshotService,
                              BizGoodsCloseSnapshotService goodsSnapshot,
                              PurchaseDwsService purchaseDws, SalesDwsService salesDws,
                              StockMoveDwsService stockMoveDws, StockSnapshotService stockSnapshot,
                              OperationLogService opLog, @Lazy BizDayCloseService self) {
        this.jdbcTemplate = jdbcTemplate;
        this.sysParam = sysParam;
        this.guard = guard;
        this.snapshotService = snapshotService;
        this.goodsSnapshot = goodsSnapshot;
        this.purchaseDws = purchaseDws;
        this.salesDws = salesDws;
        this.stockMoveDws = stockMoveDws;
        this.stockSnapshot = stockSnapshot;
        this.opLog = opLog;
        this.self = self;
    }

    // ============================================================
    // 向导预览（只读）
    // ============================================================

    /**
     * 日结向导：返回 7 步检查结果。canClose 只反映硬项（1/4/5）；
     * 提示项（2/3/7）需前端勾选知晓后随 close 入参传回。
     */
    public Map<String, Object> wizard(LocalDate date) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.format(DATE_FMT));
        LocalDate lastClosed = guard.lastClosedDate();
        result.put("lastClosed", lastClosed == null ? null : lastClosed.format(DATE_FMT));
        boolean firstClose = lastClosed == null;
        result.put("firstClose", firstClose);
        result.put("enabled", guard.enabled());
        result.put("mode", sysParam.get(BizDayCloseConst.PARAM_MODE, BizDayCloseConst.MODE_AUTO));

        // 已存在日结记录：幂等回显
        Map<String, Object> existed = loadClose(date);
        result.put("closed", existed != null);

        result.put("step1", checkContinuity(date, lastClosed));
        result.put("step2", checkHangingBills(date));
        result.put("step3", checkAnomalies());
        result.put("step4", checkDws(date, false));
        result.put("step5", checkTies(date));
        result.put("step6", checkFundArchive(date));
        result.put("step7", checkGlEvents());
        result.put("totals", collectTotals(date));
        result.put("canClose", Boolean.TRUE.equals(result.get("closed"))
                ? false
                : hardPassed(result));
        return result;
    }

    private boolean hardPassed(Map<String, Object> result) {
        return pass(result.get("step1")) && pass(result.get("step4")) && pass(result.get("step5"));
    }

    private boolean pass(Object step) {
        return step instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("passed"));
    }

    /** 第 1 步：连续性 + 首次期初基线。 */
    private Map<String, Object> checkContinuity(LocalDate date, LocalDate lastClosed) {
        Map<String, Object> step = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (date.isAfter(LocalDate.now())) {
            errors.add("禁止日结未来日期：" + date.format(DATE_FMT));
        }
        boolean first = lastClosed == null;
        int openingPending = 0;
        List<Map<String, Object>> openingBills = List.of();
        if (first) {
            openingBills = TmsUtil.queryCamel(jdbcTemplate,
                    "SELECT inbound_no, warehouse, bill_date FROM inv_other_inbound "
                            + "WHERE inbound_type = '0' AND status = 'PENDING' ORDER BY bill_date");
            openingPending = openingBills.size();
            if (openingPending > 0) {
                errors.add("存在 " + openingPending + " 张未审核的期初入库单（其他入库-期初库存），首次日结前必须处理");
            }
            warnings.add("首次日结后，" + date.format(DATE_FMT) + " 及之前全部已生效业务一并封账");
        } else if (!date.equals(lastClosed.plusDays(1))) {
            errors.add("必须连续日结：当前封单日 " + lastClosed.format(DATE_FMT)
                    + "，只能日结 " + lastClosed.plusDays(1).format(DATE_FMT));
        }
        step.put("passed", errors.isEmpty());
        step.put("errors", errors);
        step.put("warnings", warnings);
        step.put("firstClose", first);
        step.put("openingPendingBills", openingBills);
        return step;
    }

    /** 第 2 步：跨阶挂账单（三类重点 + 未交账门店结算提示）。 */
    private Map<String, Object> checkHangingBills(LocalDate date) {
        Map<String, Object> step = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        java.sql.Date d = java.sql.Date.valueOf(date);

        // ① 已入库未审核采购收货单（pur_inbound 无审核时间列，入库单 bill_date 审核时回填）
        jdbcTemplate.queryForList("""
                SELECT r.receipt_no AS bill_no, r.source_inbound_no AS upstream_no,
                    r.supplier_name AS party_name, r.warehouse AS warehouse,
                    COALESCE(r.final_amount, r.goods_amount, 0) AS bill_amount,
                    CAST(i.bill_date AS DATE) AS upstream_date
                FROM pur_receipt r
                INNER JOIN pur_inbound i ON i.inbound_no = r.source_inbound_no
                WHERE r.status = 'PENDING' AND i.status = 'APPROVED'
                    AND CAST(i.bill_date AS DATE) <= ?
                ORDER BY i.bill_date
                """, d).forEach(row -> items.add(hangItem("PUR_RECEIPT", "采购收货单", row, date)));

        // ② 已出库未审核采购退货单
        jdbcTemplate.queryForList("""
                SELECT p.return_no AS bill_no, p.source_outbound_no AS upstream_no,
                    p.supplier_name AS party_name, o.warehouse AS warehouse,
                    COALESCE(p.final_amount, p.goods_amount, 0) AS bill_amount,
                    CAST(COALESCE(o.audit_time, o.bill_date) AS DATE) AS upstream_date
                FROM pur_return p
                INNER JOIN pur_return_outbound o ON o.outbound_no = p.source_outbound_no
                WHERE p.status = 'PENDING' AND o.status = 'APPROVED'
                    AND CAST(COALESCE(o.audit_time, o.bill_date) AS DATE) <= ?
                ORDER BY o.bill_date
                """, d).forEach(row -> items.add(hangItem("PUR_RETURN", "采购退货单", row, date)));

        // ③ 已出库未签收销售发货单（含部分拒收仍有在途数量）
        jdbcTemplate.queryForList("""
                SELECT s.receipt_no AS bill_no, s.source_outbound_no AS upstream_no,
                    s.customer_name AS party_name, s.warehouse AS warehouse,
                    COALESCE(s.deliver_amount, 0) AS bill_amount,
                    CAST(o.bill_date AS DATE) AS upstream_date
                FROM sales_receipt s
                INNER JOIN sales_outbound o ON o.outbound_no = s.source_outbound_no
                WHERE s.sign_status IN ('待签收', '部分拒收') AND s.status <> 'CANCELLED'
                    AND o.status = 'APPROVED' AND CAST(o.bill_date AS DATE) <= ?
                ORDER BY o.bill_date
                """, d).forEach(row -> items.add(hangItem("SALES_RECEIPT", "销售发货单", row, date)));

        Integer storePending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tms_store_settlement WHERE fin_status = 'PENDING' AND settle_time <= ?",
                Integer.class, java.sql.Timestamp.valueOf(date.atTime(23, 59, 59)));

        step.put("passed", true); // 提示项，永不阻断
        step.put("items", items);
        step.put("count", items.size());
        step.put("storeSettlementPending", storePending == null ? 0 : storePending);
        return step;
    }

    private Map<String, Object> hangItem(String type, String typeName, Map<String, Object> row, LocalDate target) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("hangType", type);
        item.put("hangTypeName", typeName);
        item.put("billNo", str(row.get("BILL_NO")));
        item.put("upstreamNo", str(row.get("UPSTREAM_NO")));
        item.put("partyName", str(row.get("PARTY_NAME")));
        item.put("warehouse", str(row.get("WAREHOUSE")));
        item.put("billAmount", money(row.get("BILL_AMOUNT")));
        LocalDate upDate = BizDayCloseGuard.toLocalDate(row.get("UPSTREAM_DATE"));
        item.put("upstreamDate", upDate == null ? "" : upDate.format(DATE_FMT));
        int days = upDate == null ? 0 : (int) java.time.temporal.ChronoUnit.DAYS.between(upDate, target);
        item.put("hangingDays", days);
        return item;
    }

    /** 第 3 步：异常数据提示（负库存 / 成本异常 / 往来单位未匹配档案）。 */
    private Map<String, Object> checkAnomalies() {
        Map<String, Object> step = new LinkedHashMap<>();
        List<Map<String, Object>> negative = jdbcTemplate.queryForList("""
                SELECT goods_code, MAX(goods_name) AS goods_name, warehouse,
                    SUM(physical_qty) AS qty, SUM(stock_amount) AS amount
                FROM inv_stock_balance
                GROUP BY goods_code, warehouse
                HAVING SUM(physical_qty) < 0
                ORDER BY goods_code, warehouse
                """);
        List<Map<String, Object>> zeroCost = jdbcTemplate.queryForList("""
                SELECT goods_code, MAX(goods_name) AS goods_name, warehouse,
                    SUM(physical_qty) AS qty, SUM(stock_amount) AS amount
                FROM inv_stock_balance
                GROUP BY goods_code, warehouse
                HAVING SUM(physical_qty) > 0
                    AND (COALESCE(SUM(stock_amount), 0) = 0
                         OR SUM(stock_amount) / SUM(physical_qty) <= 0)
                ORDER BY goods_code, warehouse
                """);
        List<String> unknownCustomers = jdbcTemplate.queryForList("""
                SELECT DISTINCT customer FROM fin_ar
                WHERE customer IS NOT NULL AND TRIM(customer) <> ''
                    AND customer NOT IN (SELECT customer_name FROM base_customer)
                """, String.class);
        List<String> unknownSuppliers = jdbcTemplate.queryForList("""
                SELECT DISTINCT supplier FROM fin_ap
                WHERE supplier IS NOT NULL AND TRIM(supplier) <> ''
                    AND supplier NOT IN (SELECT supplier_name FROM base_supplier)
                """, String.class);
        step.put("passed", true); // 提示项
        step.put("negativeStock", negative.stream().map(TmsUtil::camelize).toList());
        step.put("zeroCost", zeroCost.stream().map(TmsUtil::camelize).toList());
        step.put("unknownCustomers", unknownCustomers);
        step.put("unknownSuppliers", unknownSuppliers);
        step.put("count", negative.size() + zeroCost.size()
                + unknownCustomers.size() + unknownSuppliers.size());
        return step;
    }

    /**
     * 第 4 步：DWS 刷新与对账。
     *
     * @param refresh false=向导只读对账（可能存在未刷差异）；true=结账执行先刷后对
     */
    private Map<String, Object> checkDws(LocalDate date, boolean refresh) {
        Map<String, Object> step = new LinkedHashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();
        boolean balanced = true;
        try {
            if (refresh) {
                purchaseDws.refreshRange(date, date);
                salesDws.refreshRange(date, date);
                stockMoveDws.refreshRange(date, date);
            }
            for (DwsKind kind : DwsKind.values()) {
                Map<String, Object> rec = switch (kind) {
                    case PURCHASE -> purchaseDws.reconcile(date, date);
                    case SALES -> salesDws.reconcile(date, date);
                    case STOCK_MOVE -> stockMoveDws.reconcile(date, date);
                };
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("kind", kind.label);
                one.put("balanced", rec.get("balanced"));
                one.put("detail", rec);
                if (!Boolean.TRUE.equals(rec.get("balanced"))) balanced = false;
                details.add(one);
            }
        } catch (RuntimeException e) {
            balanced = false;
            step.put("error", e.getMessage());
        }
        step.put("passed", balanced);
        step.put("refreshed", refresh);
        step.put("details", details);
        return step;
    }

    private enum DwsKind {
        PURCHASE("采购 DWS"), SALES("销售 DWS"), STOCK_MOVE("库存流水 DWS");
        final String label;

        DwsKind(String label) {
            this.label = label;
        }
    }

    /** 第 5 步：四套滚存勾稽平衡（容差 0.01）。 */
    private Map<String, Object> checkTies(LocalDate date) {
        Map<String, Object> step = new LinkedHashMap<>();
        List<Map<String, Object>> allDiffs = new ArrayList<>();

        Map<String, Object> stock = tieStock(date);
        allDiffs.addAll(diffList(stock));
        Map<String, Object> ar = tieAr(date);
        allDiffs.addAll(diffList(ar));
        Map<String, Object> ap = tieAp(date);
        allDiffs.addAll(diffList(ap));
        Map<String, Object> fund = tieFund(date);
        allDiffs.addAll(diffList(fund));

        step.put("stock", stock);
        step.put("ar", ar);
        step.put("ap", ap);
        step.put("fund", fund);
        step.put("diffs", allDiffs);
        step.put("passed", allDiffs.isEmpty());
        return step;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> diffList(Map<String, Object> tie) {
        Object o = tie.get("diffs");
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    /**
     * 库存收发存：前一日快照（缺失按 0，首日由期初确认背书）+ 视图正项 − 负项 = 当前库存余额。
     * 用 inv_stock_balance 实存作右端（与 StockSnapshotService.rebuild 同源）。
     */
    private Map<String, Object> tieStock(LocalDate date) {
        Map<String, Object> tie = new LinkedHashMap<>();
        List<Map<String, Object>> diffs = new ArrayList<>();
        Map<String, BigDecimal[]> moveMap = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT goods_code, warehouse, "
                        + "COALESCE(SUM(in_qty), 0) AS in_qty, COALESCE(SUM(out_qty), 0) AS out_qty, "
                        + "COALESCE(SUM(in_amount), 0) AS in_amount, "
                        + "COALESCE(SUM(out_amount), 0) AS out_amount, "
                        + "COALESCE(SUM(adjust_amount), 0) AS adjust_amount "
                        + "FROM v_rpt_stock_move WHERE move_date = ? GROUP BY goods_code, warehouse",
                java.sql.Date.valueOf(date)).forEach(r ->
                moveMap.put(key(r.get("GOODS_CODE"), r.get("WAREHOUSE")), new BigDecimal[]{
                        bd(r.get("IN_QTY")), bd(r.get("OUT_QTY")),
                        bd(r.get("IN_AMOUNT")), bd(r.get("OUT_AMOUNT")), bd(r.get("ADJUST_AMOUNT"))}));

        // 期初优先取商品定版（V121：反结即删、重算不可改），首次日结无定版行时回落分析快照/0
        Map<String, BigDecimal[]> priorMap = new LinkedHashMap<>();
        List<Map<String, Object>> priorRows = jdbcTemplate.queryForList(
                "SELECT goods_code, warehouse, ending_qty, ending_amount "
                        + "FROM biz_close_goods_daily WHERE close_date = ?",
                java.sql.Date.valueOf(date.minusDays(1)));
        if (priorRows.isEmpty()) {
            priorRows = jdbcTemplate.queryForList(
                    "SELECT goods_code, warehouse, physical_qty, stock_amount "
                            + "FROM inv_stock_daily_snapshot WHERE snapshot_date = ?",
                    java.sql.Date.valueOf(date.minusDays(1)));
        }
        final List<Map<String, Object>> finalPriorRows = priorRows;
        finalPriorRows.forEach(r -> {
            Object qty = r.get("ENDING_QTY") != null ? r.get("ENDING_QTY") : r.get("PHYSICAL_QTY");
            Object amt = r.get("ENDING_AMOUNT") != null ? r.get("ENDING_AMOUNT") : r.get("STOCK_AMOUNT");
            priorMap.put(key(r.get("GOODS_CODE"), r.get("WAREHOUSE")),
                    new BigDecimal[]{bd(qty), bd(amt)});
        });

        Map<String, BigDecimal[]> liveMap = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT goods_code, warehouse, SUM(physical_qty) AS qty, SUM(stock_amount) AS amount "
                        + "FROM inv_stock_balance GROUP BY goods_code, warehouse").forEach(r ->
                liveMap.put(key(r.get("GOODS_CODE"), r.get("WAREHOUSE")),
                        new BigDecimal[]{bd(r.get("QTY")), bd(r.get("AMOUNT"))}));

        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(moveMap.keySet());
        keys.addAll(priorMap.keySet());
        keys.addAll(liveMap.keySet());
        for (String k : keys) {
            BigDecimal[] mv = moveMap.getOrDefault(k, new BigDecimal[5]);
            BigDecimal[] pr = priorMap.getOrDefault(k, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] lv = liveMap.getOrDefault(k, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal expectQty = pr[0].add(nz(mv[0])).subtract(nz(mv[1]));
            BigDecimal expectAmount = pr[1].add(nz(mv[2])).add(nz(mv[4])).subtract(nz(mv[3]));
            BigDecimal qtyDiff = expectQty.subtract(lv[0]);
            BigDecimal amountDiff = expectAmount.subtract(lv[1]);
            if (qtyDiff.abs().doubleValue() > 0.001D
                    || amountDiff.abs().doubleValue() > BizDayCloseConst.TIE_TOLERANCE) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("tie", "库存收发存");
                diff.put("object", k);
                diff.put("expectQty", money(expectQty));
                diff.put("actualQty", money(lv[0]));
                diff.put("qtyDiff", money(qtyDiff));
                diff.put("expectAmount", money(expectAmount));
                diff.put("actualAmount", money(lv[1]));
                diff.put("amountDiff", money(amountDiff));
                diffs.add(diff);
            }
        }
        tie.put("diffs", diffs);
        return tie;
    }

    /** 应收滚存：前日 unreceived 净额 + 当日签收立账 − 当日核销 = 当前 unreceived 净额。 */
    private Map<String, Object> tieAr(LocalDate date) {
        BigDecimal prior = sumClose(
                "SELECT COALESCE(SUM(unreceived_amount - advance_amount), 0) FROM biz_close_ar_daily WHERE close_date = ?",
                date.minusDays(1));
        BigDecimal added = nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(ar_amount), 0) FROM v_rpt_ar_bill WHERE bill_date = ?",
                BigDecimal.class, java.sql.Date.valueOf(date)));
        // 凡是改变 fin_ar.unreceived_amount 的核销流水 counterparty_type 均为 CUSTOMER
        // （含收款核销 SALES_RECEIPT、付款退客户 SALES_PAYMENT、对账 AR_SETTLE、抹零 EXPENSE_WRITEOFF）
        BigDecimal settled = nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(reconcile_amount), 0) FROM fin_reconcile_record "
                        + "WHERE receipt_date = ? AND counterparty_type = 'CUSTOMER'",
                BigDecimal.class, java.sql.Date.valueOf(date)));
        BigDecimal current = nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(unreceived_amount), 0) FROM fin_ar", BigDecimal.class));
        return rollTie("应收滚存", prior, added, settled, current);
    }

    /** 应付滚存。 */
    private Map<String, Object> tieAp(LocalDate date) {
        BigDecimal prior = sumClose(
                "SELECT COALESCE(SUM(unpaid_amount - prepaid_amount), 0) FROM biz_close_ap_daily WHERE close_date = ?",
                date.minusDays(1));
        BigDecimal added = nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(ap_amount), 0) FROM v_rpt_ap_bill WHERE bill_date = ?",
                BigDecimal.class, java.sql.Date.valueOf(date)));
        // 改变 fin_ap.unpaid_amount 的核销流水 counterparty_type 均为 SUPPLIER
        BigDecimal paid = nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(reconcile_amount), 0) FROM fin_reconcile_record "
                        + "WHERE receipt_date = ? AND counterparty_type = 'SUPPLIER'",
                BigDecimal.class, java.sql.Date.valueOf(date)));
        BigDecimal current = nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(unpaid_amount), 0) FROM fin_ap", BigDecimal.class));
        return rollTie("应付滚存", prior, added, paid, current);
    }

    private Map<String, Object> rollTie(String name, BigDecimal prior, BigDecimal added,
                                        BigDecimal settled, BigDecimal current) {
        Map<String, Object> tie = new LinkedHashMap<>();
        tie.put("prior", money(prior));
        tie.put("added", money(added));
        tie.put("settled", money(settled));
        tie.put("current", money(current));
        BigDecimal diff = prior.add(added).subtract(settled).subtract(current);
        tie.put("diff", money(diff));
        List<Map<String, Object>> diffs = new ArrayList<>();
        if (diff.abs().doubleValue() > BizDayCloseConst.TIE_TOLERANCE) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("tie", name);
            d.put("formula", "前日余额 + 当日立账 − 当日核销 − 当前余额");
            d.put("prior", money(prior));
            d.put("added", money(added));
            d.put("settled", money(settled));
            d.put("current", money(current));
            d.put("diff", money(diff));
            diffs.add(d);
        }
        tie.put("diffs", diffs);
        return tie;
    }

    /**
     * 资金滚存：① 每行 open + IN − OUT = close（滚算口径自检）；
     * ② 流水余额链完整性（balance_after 按发生时间可重放，防绕过服务直改 SQL）。
     */
    private Map<String, Object> tieFund(LocalDate date) {
        Map<String, Object> tie = new LinkedHashMap<>();
        List<Map<String, Object>> diffs = new ArrayList<>();
        List<BizCloseSnapshotService.FundRow> rows = snapshotService.loadFundRows(date);
        for (BizCloseSnapshotService.FundRow row : rows) {
            BigDecimal expect = row.openBalance.add(row.inAmount).subtract(row.outAmount);
            if (expect.subtract(row.closeBalance).abs().doubleValue() > BizDayCloseConst.TIE_TOLERANCE) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("tie", "资金滚存");
                d.put("object", row.accountName);
                d.put("open", money(row.openBalance));
                d.put("in", money(row.inAmount));
                d.put("out", money(row.outAmount));
                d.put("expectClose", money(expect));
                d.put("actualClose", money(row.closeBalance));
                d.put("diff", money(expect.subtract(row.closeBalance)));
                diffs.add(d);
            }
        }
        // 余额链硬勾稽（流水自身滚平）
        diffs.addAll(snapshotService.verifyFundChain(date));
        tie.put("accountCount", rows.size());
        tie.put("diffs", diffs);
        return tie;
    }

    /** 第 6 步：流水末笔 vs 账户档案余额差异（备注项）+ 现金账户实盘录入位。 */
    private Map<String, Object> checkFundArchive(LocalDate date) {
        Map<String, Object> step = new LinkedHashMap<>();
        // 档案余额
        Map<String, BigDecimal> archiveBalance = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT fund_account_name, balance FROM base_fund_account").forEach(r ->
                archiveBalance.put(str(r.get("FUND_ACCOUNT_NAME")), bd(r.get("BALANCE"))));
        // 每个账户流水末笔余额（按发生时间）
        Map<String, BigDecimal> lastLedger = new LinkedHashMap<>();
        List<Map<String, Object>> ledgers = jdbcTemplate.queryForList(
                "SELECT fund_account, balance_after FROM fin_fund_ledger "
                        + "ORDER BY fund_account, occurred_at, ledger_no");
        for (Map<String, Object> r : ledgers) {
            lastLedger.put(str(r.get("FUND_ACCOUNT")), bd(r.get("BALANCE_AFTER")));
        }
        List<Map<String, Object>> diffs = new ArrayList<>();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        names.addAll(archiveBalance.keySet());
        names.addAll(lastLedger.keySet());
        for (String name : names) {
            BigDecimal archive = archiveBalance.get(name);
            BigDecimal ledger = lastLedger.get(name);
            if (archive == null || ledger == null) continue;
            BigDecimal diff = ledger.subtract(archive);
            if (diff.abs().doubleValue() > BizDayCloseConst.TIE_TOLERANCE) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("fundAccount", name);
                d.put("ledgerBalance", money(ledger));
                d.put("archiveBalance", money(archive));
                d.put("diff", money(diff));
                diffs.add(d);
            }
        }
        // 现金类账户名单（P0187）：返回账面余额供录实盘数
        String cashNamesParam = sysParam.get(BizDayCloseConst.PARAM_CASH_NAMES, "");
        List<Map<String, Object>> cashAccounts = new ArrayList<>();
        for (String name : cashNamesParam.split(",")) {
            String n = name.trim();
            if (n.isEmpty()) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("fundAccount", n);
            c.put("bookBalance", money(archiveBalance.getOrDefault(n, BigDecimal.ZERO)));
            cashAccounts.add(c);
        }
        step.put("passed", true); // v1.3：仅备注，不阻断
        step.put("archiveDiffs", diffs);
        step.put("cashAccounts", cashAccounts);
        return step;
    }

    /** 第 7 步：待生成/生成失败 GL 事件（仅提示）。 */
    private Map<String, Object> checkGlEvents() {
        Map<String, Object> step = new LinkedHashMap<>();
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fin_gl_event WHERE status IN ('待生成', '生成失败')", Integer.class);
        step.put("passed", true);
        step.put("pendingCount", pending == null ? 0 : pending);
        return step;
    }

    /** 日结单关键合计（口径与 DWS/台账一致，含税）。 */
    private Map<String, Object> collectTotals(LocalDate date) {
        Map<String, Object> totals = new LinkedHashMap<>();
        java.sql.Date d = java.sql.Date.valueOf(date);
        totals.put("salesSignedAmount", nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(ar_amount), 0) FROM v_rpt_ar_bill WHERE bill_date = ?",
                BigDecimal.class, d)));
        totals.put("receiptAmount", nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(reconcile_amount), 0) FROM fin_reconcile_record "
                        + "WHERE receipt_date = ? AND counterparty_type = 'CUSTOMER'",
                BigDecimal.class, d)));
        totals.put("purchaseAmount", nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(ap_amount), 0) FROM v_rpt_ap_bill WHERE bill_date = ?",
                BigDecimal.class, d)));
        totals.put("paymentAmount", nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(reconcile_amount), 0) FROM fin_reconcile_record "
                        + "WHERE receipt_date = ? AND counterparty_type = 'SUPPLIER'",
                BigDecimal.class, d)));
        totals.put("stockInAmount", nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(in_amount), 0) FROM v_rpt_stock_move WHERE move_date = ?",
                BigDecimal.class, d)));
        totals.put("stockOutAmount", nz(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(out_amount), 0) FROM v_rpt_stock_move WHERE move_date = ?",
                BigDecimal.class, d)));
        return totals;
    }

    /**
     * 商品定版合计与主表库存收发合计闭环（防止 DWS 视图与定版透视两套口径漂移）。
     * 容差 0.01；不平直接中止结账事务。
     */
    private void assertGoodsTotals(LocalDate date, Map<String, Object> totals) {
        Map<String, Object> sum = goodsSnapshot.goodsSummary(date);
        if (sum == null) {
            throw new IllegalArgumentException("商品收发存定版未生成，已中止日结：" + date.format(DATE_FMT));
        }
        Object inObj = sum.get("inAmount");
        Object outObj = sum.get("outAmount");
        BigDecimal inTotal = inObj == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(inObj));
        BigDecimal outTotal = outObj == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(outObj));
        BigDecimal expectIn = (BigDecimal) totals.get("stockInAmount");
        BigDecimal expectOut = (BigDecimal) totals.get("stockOutAmount");
        if (inTotal.subtract(expectIn).abs().doubleValue() > BizDayCloseConst.TIE_TOLERANCE
                || outTotal.subtract(expectOut).abs().doubleValue() > BizDayCloseConst.TIE_TOLERANCE) {
            throw new IllegalArgumentException(String.format(
                    "商品定版收发合计与库存流水不一致（收入 定版%s/流水%s，发出 定版%s/流水%s），已中止日结",
                    inTotal, expectIn, outTotal, expectOut));
        }
    }

    // ============================================================
    // 结账
    // ============================================================

    /** 手工结账入参。 */
    public record CloseRequest(LocalDate date, boolean openingConfirmed,
                               boolean acknowledgeHanging, boolean acknowledgeAnomaly,
                               Map<String, BigDecimal> cashCounts, String fundRemark) {
    }

    /** 手工执行日结（权限 finance.day_close.audit）。 */
    @Transactional
    public Map<String, Object> close(CloseRequest req) {
        return performClose(req.date(), req, BizDayCloseConst.TRIGGER_MANUAL, TmsUtil.currentUser());
    }

    /**
     * 结账事务核心（手工/自动共用）。必须经 Spring 代理调用以保证事务边界
     * （自动补结每天一个独立事务）。
     */
    @Transactional
    public Map<String, Object> performClose(LocalDate date, CloseRequest manualReq,
                                            String triggerType, String operator) {
        LocalDate lastClosed = guard.lastClosedDate();
        boolean firstClose = lastClosed == null;

        // 幂等：同日期已结直接回显，不重复写定版
        Map<String, Object> existed = loadClose(date);
        if (existed != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("date", date.format(DATE_FMT));
            r.put("closeNo", existed.get("CLOSE_NO"));
            r.put("idempotent", true);
            return r;
        }

        // 1. 连续性 + 期初基线（硬）
        Map<String, Object> step1 = checkContinuity(date, lastClosed);
        if (!Boolean.TRUE.equals(step1.get("passed"))) {
            throw new IllegalArgumentException(String.join("；", errorsOf(step1)));
        }
        if (BizDayCloseConst.TRIGGER_MANUAL.equals(triggerType)) {
            if (firstClose && manualReq != null && !manualReq.openingConfirmed()) {
                throw new IllegalArgumentException("首次日结必须勾选往来/资金期初已核对");
            }
            Map<String, Object> hanging = checkHangingBills(date);
            if ((Integer) hanging.get("count") > 0
                    && (manualReq == null || !manualReq.acknowledgeHanging())) {
                throw new IllegalArgumentException("存在跨阶挂账单，请逐项核对并勾选已知晓后再日结");
            }
            Map<String, Object> anomaly = checkAnomalies();
            if ((Integer) anomaly.get("count") > 0
                    && (manualReq == null || !manualReq.acknowledgeAnomaly())) {
                throw new IllegalArgumentException("存在异常数据（负库存/成本异常/未匹配往来单位），请勾选已知晓后再日结");
            }
        }

        // 2. DWS 刷新 + 对账（硬）
        Map<String, Object> step4 = checkDws(date, true);
        if (!Boolean.TRUE.equals(step4.get("passed"))) {
            throw new IllegalArgumentException("DWS 对账不平，已中止日结：" + summarizeDws(step4));
        }

        // 3. 四套滚存（硬）；资金档案差异/实盘为备注，不参与
        Map<String, Object> step5 = checkTies(date);
        if (!Boolean.TRUE.equals(step5.get("passed"))) {
            throw new IllegalArgumentException("四套滚存勾稽不平，已中止日结，差异 "
                    + ((List<?>) step5.get("diffs")).size() + " 项");
        }

        Map<String, Object> step6 = checkFundArchive(date);
        Map<String, Object> step7 = checkGlEvents();

        // 4. 库存定版快照
        stockSnapshot.rebuild(date);

        // 5. 往来/资金定版
        Map<String, BigDecimal> cashCounts = manualReq == null ? Map.of()
                : (manualReq.cashCounts() == null ? Map.of() : manualReq.cashCounts());
        snapshotService.rebuildAr(date);
        snapshotService.rebuildAp(date);
        snapshotService.rebuildFund(date, cashCounts);
        // 商品收发存定版（V121，内含逐行恒等式硬断言，不平抛错回滚）
        goodsSnapshot.rebuildGoods(date);

        // 6. 汇总并落主记录
        Map<String, Object> step2 = checkHangingBills(date);
        Map<String, Object> step3 = checkAnomalies();
        Map<String, Object> totals = collectTotals(date);
        assertGoodsTotals(date, totals);
        boolean fundBalanced = ((List<?>) step6.get("archiveDiffs")).isEmpty();

        Map<String, Object> checkResult = new LinkedHashMap<>();
        checkResult.put("step2", step2);
        checkResult.put("step3", step3);
        checkResult.put("step4", step4);
        checkResult.put("step5", step5);
        checkResult.put("step6", step6);
        checkResult.put("step7", step7);
        checkResult.put("totals", totals);
        checkResult.put("firstClose", firstClose);
        checkResult.put("triggerType", triggerType);
        String remark = manualReq == null ? null : manualReq.fundRemark();
        if (BizDayCloseConst.TRIGGER_AUTO.equals(triggerType)) {
            remark = remark == null ? "自动日结：现金未实盘确认；资金档案差异见检查结果"
                    : remark + "（自动日结：现金未实盘确认）";
        }
        checkResult.put("fundRemark", remark);

        String closeNo = "RJ-" + date.format(NO_FMT);
        String id = TmsUtil.uuid("RJ");
        jdbcTemplate.update("""
                INSERT INTO biz_day_close(id, close_no, close_date, scope, check_result,
                    dws_balanced, tie_balanced, fund_balanced,
                    sales_signed_amount, receipt_amount, purchase_amount, payment_amount,
                    stock_in_amount, stock_out_amount, pending_bill_count,
                    opening_confirmed, fund_remark, close_name, close_time)
                VALUES (?, ?, ?, 'ALL', ?, 'Y', 'Y', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, id, closeNo, java.sql.Date.valueOf(date), toJson(checkResult),
                fundBalanced ? "Y" : "N",
                totals.get("salesSignedAmount"), totals.get("receiptAmount"),
                totals.get("purchaseAmount"), totals.get("paymentAmount"),
                totals.get("stockInAmount"), totals.get("stockOutAmount"),
                step2.get("count"),
                firstClose && manualReq != null && manualReq.openingConfirmed() ? "Y" : "N",
                remark, operator);

        // 7. 提交后：失效守卫缓存 + CLOSE 日志 + 操作日志
        final String fOperator = operator;
        final String fRemark = remark;
        final int pendingCount = (Integer) step2.get("count");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                guard.evict();
                writeLog(date, BizDayCloseConst.ACTION_CLOSE, BizDayCloseConst.RESULT_SUCCESS,
                        fOperator, null, null, triggerType,
                        "日结完成 " + closeNo + "，挂账单 " + pendingCount + " 笔", id);
                opLog.log(BizDayCloseConst.LOG_MODULE, OperationAction.AUDIT,
                        closeNo, "业务日结 " + date.format(DATE_FMT)
                                + (fRemark == null ? "" : "（" + fRemark + "）"));
            }
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.format(DATE_FMT));
        result.put("closeNo", closeNo);
        result.put("idempotent", false);
        result.put("totals", totals);
        result.put("pendingBillCount", pendingCount);
        return result;
    }

    private String summarizeDws(Map<String, Object> step4) {
        if (step4.get("error") != null) return String.valueOf(step4.get("error"));
        List<String> parts = new ArrayList<>();
        for (Object o : (List<?>) step4.get("details")) {
            if (o instanceof Map<?, ?> m && !Boolean.TRUE.equals(m.get("balanced"))) {
                parts.add(String.valueOf(m.get("kind")));
            }
        }
        return String.join("、", parts);
    }

    // ============================================================
    // 反日结
    // ============================================================

    /** 单日反结（仅最后一个已结日；权限 finance.day_close.unaudit）。 */
    @Transactional
    public void reopen(LocalDate date, String reason) {
        performReopen(date, reason, null, TmsUtil.currentUser());
    }

    /** 批量反结：从当前封单日逐日反到 toDate，共用一个批次号。 */
    @Transactional
    public Map<String, Object> reopenBatch(LocalDate toDate, String reason) {
        LocalDate cursor = guard.lastClosedDate();
        if (cursor == null) throw new IllegalArgumentException("当前没有任何已日结日期");
        if (toDate.isAfter(cursor)) {
            throw new IllegalArgumentException("目标日期晚于当前封单日 " + cursor.format(DATE_FMT));
        }
        String batchNo = TmsUtil.uuid("RB");
        String operator = TmsUtil.currentUser();
        int count = 0;
        // 同一事务内逐日反结：每删一天主记录，MAX(close_date) 即下一个待反日
        while (true) {
            java.sql.Date max = jdbcTemplate.queryForObject(
                    "SELECT MAX(close_date) FROM biz_day_close", java.sql.Date.class);
            if (max == null || max.toLocalDate().isBefore(toDate)) break;
            self.performReopen(max.toLocalDate(), reason, batchNo, operator);
            count++;
        }
        guard.evict();
        opLog.log(BizDayCloseConst.LOG_MODULE, OperationAction.UN_AUDIT,
                batchNo, "批量反日结至 " + toDate.format(DATE_FMT) + "，共 " + count + " 天");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("batchNo", batchNo);
        r.put("days", count);
        return r;
    }

    /**
     * 反结事务核心（单日/批量共用，经代理调用）。
     * 删除往来/资金/商品四类定版、库存分析快照与主记录；REOPEN 日志永久保留。
     */
    @Transactional
    public void performReopen(LocalDate date, String reason, String batchNo, String operator) {
        if (reason == null || reason.trim().length() < 2) {
            throw new IllegalArgumentException("反日结原因必填（至少 2 个字）");
        }
        Map<String, Object> row = loadClose(date);
        if (row == null) throw new IllegalArgumentException("该日期未日结：" + date.format(DATE_FMT));
        String bizId = str(row.get("ID"));

        // 单日反结只能反当前封单日；连续反多天走批量反结
        if (batchNo == null) {
            java.sql.Date max = jdbcTemplate.queryForObject(
                    "SELECT MAX(close_date) FROM biz_day_close", java.sql.Date.class);
            if (max == null || !max.toLocalDate().equals(date)) {
                throw new IllegalArgumentException("只能反日结当前最后一个已结日 "
                        + (max == null ? "" : max.toLocalDate().format(DATE_FMT))
                        + "；如需连续反结请使用批量反日结");
            }
        }

        // 跨月硬约束：已结账/已冻结会计期间内的日期不允许反日结
        String period = String.format("%04d%02d", date.getYear(), date.getMonthValue());
        List<Map<String, Object>> periods = jdbcTemplate.queryForList(
                "SELECT status FROM fin_accounting_period WHERE period = ?", period);
        if (!periods.isEmpty()) {
            String st = str(periods.get(0).get("STATUS"));
            if (GlConst.P_CLOSED.equals(st) || GlConst.P_FROZEN.equals(st)) {
                throw new IllegalArgumentException(
                        String.format(BizDayCloseConst.GL_PERIOD_CLOSED_MSG, period));
            }
        }

        java.sql.Date d = java.sql.Date.valueOf(date);
        jdbcTemplate.update("DELETE FROM biz_close_fund_daily WHERE close_date = ?", d);
        jdbcTemplate.update("DELETE FROM biz_close_ar_daily WHERE close_date = ?", d);
        jdbcTemplate.update("DELETE FROM biz_close_ap_daily WHERE close_date = ?", d);
        jdbcTemplate.update("DELETE FROM biz_close_goods_daily WHERE close_date = ?", d);
        jdbcTemplate.update("DELETE FROM inv_stock_daily_snapshot WHERE snapshot_date = ?", d);
        jdbcTemplate.update("DELETE FROM biz_day_close WHERE close_date = ?", d);

        final String fBatch = batchNo;
        final String fOperator = operator;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                guard.evict();
                writeLog(date, BizDayCloseConst.ACTION_REOPEN, BizDayCloseConst.RESULT_SUCCESS,
                        fOperator, reason.trim(), fBatch, BizDayCloseConst.TRIGGER_MANUAL,
                        "反日结 " + date.format(DATE_FMT), bizId);
                if (fBatch == null) {
                    opLog.log(BizDayCloseConst.LOG_MODULE, OperationAction.UN_AUDIT,
                            str(row.get("CLOSE_NO")), "反日结 " + date.format(DATE_FMT)
                                    + "，原因：" + reason.trim());
                }
            }
        });
    }

    // ============================================================
    // 自动日结（定时任务 / 立即执行）
    // ============================================================

    /**
     * 补结循环：封单日+1 逐日补到昨天，任一天失败中止并抛出（任务日志记 FAIL）。
     * 由 {@code SysScheduledTaskService.runTriggered} 包裹计时与日志。
     */
    public String autoClose() {
        if (!guard.enabled()) {
            return "总开关 BIZ_DAY_CLOSE_ENABLED=N，跳过自动日结";
        }
        if (!BizDayCloseConst.MODE_AUTO.equals(
                sysParam.get(BizDayCloseConst.PARAM_MODE, BizDayCloseConst.MODE_AUTO))) {
            return "日结方式为手动（MANUAL），跳过自动日结";
        }
        String taskEnabled = jdbcTemplate.queryForObject(
                "SELECT enabled FROM sys_scheduled_task WHERE task_code = ?",
                String.class, BizDayCloseConst.TASK_CODE);
        if (!"Y".equalsIgnoreCase(taskEnabled)) {
            return "定时任务已停用，跳过自动日结";
        }

        LocalDate today = LocalDate.now();
        LocalDate last = guard.lastClosedDate();
        LocalDate start = last == null ? today.minusDays(1) : last.plusDays(1);
        LocalDate end = today.minusDays(1);
        if (start.isAfter(end)) {
            return "无待日结日期（封单日 " + (last == null ? "无" : last.format(DATE_FMT)) + "）";
        }
        int done = 0;
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            final LocalDate day = cursor;
            try {
                // 经代理调用，每天独立事务
                Map<String, Object> r = self.performClose(day, null,
                        BizDayCloseConst.TRIGGER_AUTO, "系统");
                if (Boolean.TRUE.equals(r.get("idempotent"))) {
                    // 已结则跳过（理论上连续日结不会出现）
                }
                done++;
            } catch (RuntimeException e) {
                writeLog(day, BizDayCloseConst.ACTION_CLOSE, BizDayCloseConst.RESULT_FAIL,
                        "系统", null, null, BizDayCloseConst.TRIGGER_AUTO,
                        "自动日结失败：" + safeMsg(e), null);
                throw new IllegalArgumentException("自动日结在 " + day.format(DATE_FMT)
                        + " 失败并中止（已补结 " + done + " 天）：" + safeMsg(e));
            }
            cursor = cursor.plusDays(1);
        }
        return "自动日结完成：" + start.format(DATE_FMT) + " ~ " + end.format(DATE_FMT)
                + "，共 " + done + " 天";
    }

    // ============================================================
    // 工作台未日结提醒
    // ============================================================

    /** 过 P0188 时刻昨日仍未结，或最近一次自动任务 FAIL：返回红色提醒数据。 */
    public Map<String, Object> overdueReminder() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("show", false);
        if (!guard.enabled()) return r;
        int hour = sysParam.getInt(BizDayCloseConst.PARAM_LATE_HOUR, 10, 6, 23);
        LocalDate now = LocalDate.now();
        boolean late = java.time.LocalTime.now().getHour() >= hour;
        LocalDate yesterday = now.minusDays(1);
        boolean yesterdayClosed = guard.isClosed(yesterday);
        Map<String, Object> lastFail = null;
        List<Map<String, Object>> fails = jdbcTemplate.queryForList(
                "SELECT close_date, operate_time, detail FROM biz_day_close_log "
                        + "WHERE action = 'CLOSE' AND result = 'FAIL' ORDER BY operate_time DESC LIMIT 1");
        if (!fails.isEmpty()) {
            // 失败日后若已成功补结则不再提醒
            LocalDate failDate = BizDayCloseGuard.toLocalDate(fails.get(0).get("CLOSE_DATE"));
            if (failDate != null && !guard.isClosed(failDate)) {
                lastFail = TmsUtil.camelize(fails.get(0));
            }
        }
        boolean show = (late && !yesterdayClosed) || lastFail != null;
        r.put("show", show);
        r.put("late", late && !yesterdayClosed);
        r.put("yesterday", yesterday.format(DATE_FMT));
        r.put("lastClosed", guard.lastClosedDate() == null ? null
                : guard.lastClosedDate().format(DATE_FMT));
        r.put("lastFail", lastFail);
        r.put("lateHour", hour);
        return r;
    }

    // ============================================================
    // 日志/查询辅助（Controller 分页直接用 JdbcTemplate，这里提供通用取数）
    // ============================================================

    /** 单日结详情（打印 RJ 单据用）：主记录 + check_result JSON 反序列化。 */
    public Map<String, Object> detail(LocalDate date) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, close_no, close_date, scope, check_result, dws_balanced, "
                        + "tie_balanced, fund_balanced, sales_signed_amount, receipt_amount, "
                        + "purchase_amount, payment_amount, stock_in_amount, stock_out_amount, "
                        + "pending_bill_count, opening_confirmed, fund_remark, close_name, "
                        + "close_time, create_time "
                        + "FROM biz_day_close WHERE close_date = ?", java.sql.Date.valueOf(date));
        if (rows.isEmpty()) return null;
        // 统一驼峰输出（H2 裸 queryForList 为大写下划线键），check_result 原文保留供打印档备查
        Map<String, Object> row = TmsUtil.camelize(rows.get(0));
        Object cr = row.get("checkResult");
        if (cr instanceof String s && !s.isBlank()) {
            try {
                row.put("checkResultObj", JSON.readValue(s, Map.class));
            } catch (Exception ignore) {
                row.put("checkResultObj", Map.of());
            }
        }
        row.put("arDaily", TmsUtil.queryCamel(jdbcTemplate,
                "SELECT customer_code, customer_name, ar_amount, received_amount, "
                        + "unreceived_amount, advance_amount, overdue_amount, bill_count "
                        + "FROM biz_close_ar_daily WHERE close_date = ? ORDER BY customer_code",
                java.sql.Date.valueOf(date)));
        row.put("apDaily", TmsUtil.queryCamel(jdbcTemplate,
                "SELECT supplier_code, supplier_name, ap_amount, paid_amount, unpaid_amount, "
                        + "prepaid_amount, overdue_amount, bill_count "
                        + "FROM biz_close_ap_daily WHERE close_date = ? ORDER BY supplier_code",
                java.sql.Date.valueOf(date)));
        row.put("fundDaily", TmsUtil.queryCamel(jdbcTemplate,
                "SELECT fund_account_code, fund_account_name, open_balance, in_amount, "
                        + "out_amount, close_balance, cash_count "
                        + "FROM biz_close_fund_daily WHERE close_date = ? ORDER BY fund_account_code",
                java.sql.Date.valueOf(date)));
        row.put("goodsSummary", goodsSnapshot.goodsSummary(date));
        row.put("goodsDaily", TmsUtil.queryCamel(jdbcTemplate,
                "SELECT goods_code, goods_name, spec, barcode, base_unit, warehouse, "
                        + "opening_qty, opening_amount, "
                        + "purchase_in_qty, purchase_in_amount, sales_return_in_qty, sales_return_in_amount, "
                        + "other_in_qty, other_in_amount, transfer_in_qty, transfer_in_amount, "
                        + "in_qty, in_amount, adjust_amount, "
                        + "sales_out_qty, sales_out_amount, purchase_return_out_qty, purchase_return_out_amount, "
                        + "other_out_qty, other_out_amount, transfer_out_qty, transfer_out_amount, "
                        + "out_qty, out_amount, signed_qty, signed_amount, signed_cost_amount, "
                        + "gross_profit, ending_qty, ending_amount, ending_cost_price, "
                        + "qty_diff, amount_diff, tie_flag, negative_flag "
                        + "FROM biz_close_goods_daily WHERE close_date = ? "
                        + "ORDER BY warehouse, ending_amount DESC, goods_code",
                java.sql.Date.valueOf(date)));
        return row;
    }

    /** 主记录分页（按日期倒序，全量内存分页由 PageResult 完成）。 */
    public List<Map<String, Object>> listCloseRows(LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, close_no, close_date, dws_balanced, tie_balanced, fund_balanced, "
                        + "sales_signed_amount, receipt_amount, purchase_amount, payment_amount, "
                        + "stock_in_amount, stock_out_amount, pending_bill_count, opening_confirmed, "
                        + "fund_remark, close_name, close_time, create_time "
                        + "FROM biz_day_close WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND close_date >= ?");
            args.add(java.sql.Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND close_date <= ?");
            args.add(java.sql.Date.valueOf(to));
        }
        sql.append(" ORDER BY close_date DESC");
        return TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
    }

    /** 操作日志全量（内存分页）。 */
    public List<Map<String, Object>> listLogs(LocalDate from, LocalDate to, String action) {
        StringBuilder sql = new StringBuilder(
                "SELECT log_id, close_date, action, result, operator_name, operate_time, "
                        + "reason, batch_no, trigger_type, detail, biz_id "
                        + "FROM biz_day_close_log WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND close_date >= ?");
            args.add(java.sql.Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND close_date <= ?");
            args.add(java.sql.Date.valueOf(to));
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            args.add(action);
        }
        sql.append(" ORDER BY operate_time DESC, close_date DESC");
        return TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> listArDaily(LocalDate from, LocalDate to, String keyword) {
        return listDaily("SELECT close_date, customer_code, customer_name, ar_amount, received_amount, "
                + "unreceived_amount, advance_amount, overdue_amount, bill_count "
                + "FROM biz_close_ar_daily", "customer_code", "customer_name", from, to, keyword);
    }

    public List<Map<String, Object>> listApDaily(LocalDate from, LocalDate to, String keyword) {
        return listDaily("SELECT close_date, supplier_code, supplier_name, ap_amount, paid_amount, "
                + "unpaid_amount, prepaid_amount, overdue_amount, bill_count "
                + "FROM biz_close_ap_daily", "supplier_code", "supplier_name", from, to, keyword);
    }

    public List<Map<String, Object>> listFundDaily(LocalDate from, LocalDate to, String keyword) {
        return listDaily("SELECT close_date, fund_account_code, fund_account_name, open_balance, "
                + "in_amount, out_amount, close_balance, cash_count "
                + "FROM biz_close_fund_daily", "fund_account_code", "fund_account_name",
                from, to, keyword);
    }

    /** 商品收发存定版台账（区间滚算：期初取首日、期末取末日，V121）。 */
    public List<Map<String, Object>> listGoodsDaily(LocalDate from, LocalDate to, String keyword) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        return goodsSnapshot.listGoodsRoll(start, end, keyword);
    }

    private List<Map<String, Object>> listDaily(String baseSql, String codeCol, String nameCol,
                                                LocalDate from, LocalDate to, String keyword) {
        StringBuilder sql = new StringBuilder(baseSql).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND close_date >= ?");
            args.add(java.sql.Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND close_date <= ?");
            args.add(java.sql.Date.valueOf(to));
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (").append(codeCol).append(" LIKE ? OR ").append(nameCol).append(" LIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY close_date DESC, ").append(nameCol);
        return TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
    }

    private void writeLog(LocalDate date, String action, String result, String operator,
                          String reason, String batchNo, String triggerType, String detail,
                          String bizId) {
        jdbcTemplate.update("""
                INSERT INTO biz_day_close_log(log_id, close_date, action, result, operator_name,
                    reason, batch_no, trigger_type, detail, biz_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, TmsUtil.uuid("BCL"), java.sql.Date.valueOf(date), action, result, operator,
                reason, batchNo, triggerType, detail, bizId);
    }

    private Map<String, Object> loadClose(LocalDate date) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, close_no, close_date FROM biz_day_close WHERE close_date = ?",
                java.sql.Date.valueOf(date));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<String> errorsOf(Map<String, Object> step) {
        Object o = step.get("errors");
        return o instanceof List<?> l ? (List<String>) o : List.of();
    }

    private String safeMsg(RuntimeException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : (m.length() > 900 ? m.substring(0, 900) : m);
    }

    private BigDecimal sumClose(String sql, LocalDate date) {
        return nz(jdbcTemplate.queryForObject(sql, BigDecimal.class, java.sql.Date.valueOf(date)));
    }

    private String toJson(Map<String, Object> map) {
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String key(Object goods, Object warehouse) {
        return str(goods) + "@" + str(warehouse);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        return new BigDecimal(s);
    }

    private static BigDecimal money(Object o) {
        BigDecimal v = o instanceof BigDecimal b ? b : bd(o);
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
