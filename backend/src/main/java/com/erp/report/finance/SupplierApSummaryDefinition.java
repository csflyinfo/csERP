package com.erp.report.finance;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报表24｜供应商应付汇总表（#23 的供应商镜像，期间发生额滚动）。
 *
 * <p>账单复用 v_rpt_ap_bill（立账日=采购收货/退货审核日，回退到期日−30；含税金额，
 * 采购退货红冲为负）；付款按 fin_reconcile_record 三类应付核销流水实算。
 * 恒等式：期初 + 本期新增 − 本期付款 = 期末（折让流水当前系统无供应商侧写入，列保留，通常为 0）。
 *
 * <p>默认期间按需求方 2026-09-11 确认口径：截止昨天、起始为截止日上月同日的前一天（K2）。
 * 已收票/未收票取 fin_ap.invoiced_amount 当前值（系统无来票时点快照，列名标注当前）。
 */
@Component
public class SupplierApSummaryDefinition implements ReportDefinition {

    private static final String PERM = "VIEW_AP_BALANCE";

    private final DataScopeService dataScope;

    public SupplierApSummaryDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "supplier_ap_summary"; }
    @Override public String name() { return "供应商应付汇总表"; }
    @Override public String viewPerm() { return "report.supplier_ap_summary.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        return new ArrayList<>(List.of(
                ReportColumnDef.dim("supplierCode", "供应商编号"),
                ReportColumnDef.dim("supplierName", "供应商名称"),
                ReportColumnDef.dim("buyer", "采购员"),
                ReportColumnDef.dim("settlementMethod", "结算方式"),
                ReportColumnDef.measure("openingAmount", "期初应付", PERM),
                ReportColumnDef.measure("newAmount", "本期新增应付", PERM),
                ReportColumnDef.measure("paidAmount", "本期付款", PERM),
                ReportColumnDef.measure("discountAmount", "折让/核销", PERM),
                ReportColumnDef.measure("endingAmount", "期末应付", PERM),
                ReportColumnDef.measure("overdueAmount", "其中逾期", PERM),
                ReportColumnDef.measure("invoicedAmount", "已收票金额(当前)", PERM),
                ReportColumnDef.measure("uninvoicedAmount", "未收票金额(当前)", PERM)));
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        var scope = dataScope.target()
                .supplier("b.supplier")
                .build();
        if (scope.isDenyAll()) return plan.denyAll();

        var start = req.range().startDate();
        var end = req.range().endDate();

        StringBuilder leaf = new StringBuilder("""
                SELECT t.supplier_code AS supplier_code,
                       MAX(t.supplier_name) AS supplier_name,
                       MAX(t.buyer) AS buyer,
                       MAX(t.settlement_method) AS settlement_method,
                       COALESCE(SUM(t.open_bill - t.paid_before),0) AS opening_amount,
                       COALESCE(SUM(t.new_bill),0) AS new_amount,
                       COALESCE(SUM(t.paid_in),0) AS paid_amount,
                       COALESCE(MAX(t.discount_amount),0) AS discount_amount,
                       COALESCE(SUM(t.end_bill - t.paid_to_end),0) AS ending_amount,
                       COALESCE(SUM(t.overdue_bill - t.overdue_paid),0) AS overdue_amount,
                       COALESCE(SUM(t.invoiced_amount),0) AS invoiced_amount,
                       COALESCE(SUM(CASE WHEN t.ap_amount > 0
                                         THEN t.ap_amount - t.invoiced_amount ELSE 0 END),0) AS uninvoiced_amount
                  FROM (
                SELECT b.supplier_code AS supplier_code,
                       b.supplier AS supplier_name,
                       b.buyer AS buyer,
                       b.settlement_method AS settlement_method,
                       b.ap_amount AS ap_amount,
                       b.invoiced_amount AS invoiced_amount,
                       CASE WHEN b.bill_date < ? THEN b.ap_amount ELSE 0 END AS open_bill,
                       CASE WHEN b.bill_date BETWEEN ? AND ? THEN b.ap_amount ELSE 0 END AS new_bill,
                       CASE WHEN b.bill_date <= ? THEN b.ap_amount ELSE 0 END AS end_bill,
                       COALESCE(rc.paid_before,0) AS paid_before,
                       COALESCE(rc.paid_in,0) AS paid_in,
                       COALESCE(rc.paid_to_end,0) AS paid_to_end,
                       CASE WHEN b.bill_date <= ? AND b.due_date IS NOT NULL AND b.due_date <= ?
                            THEN b.ap_amount ELSE 0 END AS overdue_bill,
                       CASE WHEN b.bill_date <= ? AND b.due_date IS NOT NULL AND b.due_date <= ?
                            THEN COALESCE(rc.paid_to_end,0) ELSE 0 END AS overdue_paid,
                       COALESCE(wo.wo_amount,0) AS discount_amount
                  FROM v_rpt_ap_bill b
                  LEFT JOIN (
                      SELECT business_no AS ap_no,
                             SUM(CASE WHEN receipt_date < ? THEN reconcile_amount ELSE 0 END) AS paid_before,
                             SUM(CASE WHEN receipt_date BETWEEN ? AND ? THEN reconcile_amount ELSE 0 END) AS paid_in,
                             SUM(reconcile_amount) AS paid_to_end
                        FROM fin_reconcile_record
                       WHERE business_type IN """)
                .append(FinanceReportSupport.placeholders(FinanceReportSupport.AP_CASH_TYPES.size()))
                .append("""
                         AND receipt_date <= ?
                       GROUP BY business_no
                  ) rc ON rc.ap_no = b.ap_no
                  LEFT JOIN (
                      SELECT counterparty_name AS cp_name,
                             SUM(reconcile_amount) AS wo_amount
                        FROM fin_reconcile_record
                       WHERE business_type = 'EXPENSE_WRITEOFF'
                         AND counterparty_type = 'SUPPLIER'
                         AND receipt_date BETWEEN ? AND ?
                       GROUP BY counterparty_name
                  ) wo ON wo.cp_name = b.supplier
                 WHERE b.bill_date <= ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(start);                       // open_bill < start
        args.add(start); args.add(end);        // new_bill BETWEEN
        args.add(end);                         // end_bill <= end
        args.add(end); args.add(end);          // overdue_bill
        args.add(end); args.add(end);          // overdue_paid
        args.add(start);                       // rc.paid_before < start
        args.add(start); args.add(end);        // rc.paid_in BETWEEN
        args.addAll(FinanceReportSupport.AP_CASH_TYPES);
        args.add(end);                         // rc receipt_date <= end
        args.add(start); args.add(end);        // 折让期间
        args.add(end);                         // b.bill_date <= end
        appendFilters(req, leaf, args);
        scope.appendTo(leaf, args);
        leaf.append(") t GROUP BY t.supplier_code HAVING 1=1 ");
        if (!"1".equals(req.text("includeSettled"))) {
            leaf.append(" AND ABS(SUM(t.end_bill - t.paid_to_end)) > ")
                .append(FinanceReportSupport.EPS).append(' ');
        }
        java.math.BigDecimal minEnding = req.decimal("minEnding");
        if (minEnding != null) {
            leaf.append(" AND SUM(t.end_bill - t.paid_to_end) >= ? ");
            args.add(minEnding);
        }

        plan.detailSelect = "SELECT g.* FROM (";
        plan.fromWhere.append(leaf).append(") g");
        plan.args.addAll(args);
        plan.defaultOrder = "ORDER BY g.ending_amount DESC, g.supplier_code ASC";
        plan.sortWhitelist.putAll(Map.of(
                "supplierName", "supplier_name",
                "openingAmount", "opening_amount",
                "newAmount", "new_amount",
                "paidAmount", "paid_amount",
                "endingAmount", "ending_amount",
                "overdueAmount", "overdue_amount",
                "invoicedAmount", "invoiced_amount",
                "uninvoicedAmount", "uninvoiced_amount"));
        plan.grandSql = """
                SELECT COALESCE(SUM(x.opening_amount),0) AS opening_amount,
                       COALESCE(SUM(x.new_amount),0) AS new_amount,
                       COALESCE(SUM(x.paid_amount),0) AS paid_amount,
                       COALESCE(SUM(x.discount_amount),0) AS discount_amount,
                       COALESCE(SUM(x.ending_amount),0) AS ending_amount,
                       COALESCE(SUM(x.overdue_amount),0) AS overdue_amount,
                       COALESCE(SUM(x.invoiced_amount),0) AS invoiced_amount,
                       COALESCE(SUM(x.uninvoiced_amount),0) AS uninvoiced_amount
                FROM (%s) x
                """.formatted(leaf);
        plan.grandArgs.addAll(args);
        for (String f : List.of("openingAmount", "newAmount", "paidAmount", "discountAmount",
                "endingAmount", "overdueAmount", "invoicedAmount", "uninvoicedAmount")) {
            plan.maskOverrides.put(f, PERM);
        }
        return plan;
    }

    private void appendFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args) {
        String supplier = req.text("supplier");
        if (supplier != null) {
            sql.append(" AND (b.supplier_code LIKE ? OR b.supplier LIKE ?) ");
            args.add("%" + supplier + "%");
            args.add("%" + supplier + "%");
        }
        String v = req.text("buyer");
        if (v != null) {
            sql.append(" AND b.buyer = ? ");
            args.add(v);
        }
        v = req.text("settlementMethod");
        if (v != null) {
            sql.append(" AND b.settlement_method = ? ");
            args.add(v);
        }
    }
}
