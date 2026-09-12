package com.erp.report.finance;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报表21｜应付账款账龄分析表（时点报表，#20 的供应商镜像）。
 *
 * <p>口径同 {@link ArAgingDefinition}：账单取共享 DWD 视图 v_rpt_ap_bill
 * （立账日=采购收货/退货审核日，回退到期日−30）；已付按 fin_reconcile_record
 * 流水 receipt_date &le; 截止日归集（business_no=ap_no，三类应付核销类型），
 * 付款取消审核的红字负行自然轧差；账龄桶按到期日相对截止日逾期天数划分。
 *
 * <p>来票信息（已收票/未收票）取 fin_ap.invoiced_amount <b>当前值</b>——系统不来票时点快照，
 * 账龄是历史时点、来票是当前状态，列名显式标注，不做时点还原。
 * filters.viewMode：supplier=供应商汇总（默认）；bill=单据明细。
 */
@Component
public class ApAgingDefinition implements ReportDefinition {

    private static final String PERM = "VIEW_AP_BALANCE";

    private final DataScopeService dataScope;

    public ApAgingDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "ap_aging"; }
    @Override public String name() { return "应付账款账龄分析表"; }
    @Override public String viewPerm() { return "report.ap_aging.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        return new ArrayList<>(List.of(
                ReportColumnDef.dim("supplierCode", "供应商编号"),
                ReportColumnDef.dim("supplierName", "供应商名称"),
                ReportColumnDef.dim("buyer", "采购员"),
                ReportColumnDef.dim("settlementMethod", "结算方式"),
                ReportColumnDef.dim("apNo", "应付单号"),
                ReportColumnDef.dim("sourceBill", "来源单号"),
                ReportColumnDef.dim("billSourceType", "单据类型"),
                ReportColumnDef.dim("billDate", "立账日期"),
                ReportColumnDef.dim("dueDate", "到期日期"),
                ReportColumnDef.dim("invoiceStatus", "来票状态"),
                ReportColumnDef.dim("agingBucket", "账龄区间"),
                ReportColumnDef.measure("billCount", "单据数", null),
                ReportColumnDef.measure("apAmount", "应付金额", PERM),
                ReportColumnDef.measure("paidAmount", "已付金额", PERM),
                ReportColumnDef.measure("outstanding", "截至日余额", PERM),
                ReportColumnDef.measure("unexpiredAmount", "未到期", PERM),
                ReportColumnDef.measure("age1To30", "逾期1-30天", PERM),
                ReportColumnDef.measure("age31To60", "逾期31-60天", PERM),
                ReportColumnDef.measure("age61To90", "逾期61-90天", PERM),
                ReportColumnDef.measure("age91To180", "逾期91-180天", PERM),
                ReportColumnDef.measure("age180Plus", "逾期180天以上", PERM),
                ReportColumnDef.measure("maxOverdueDays", "最长逾期天数", null),
                ReportColumnDef.measure("overdueBillCount", "逾期单据数", null),
                ReportColumnDef.measure("invoicedAmount", "已收票金额(当前)", PERM),
                ReportColumnDef.measure("uninvoicedAmount", "未收票金额(当前)", PERM),
                ReportColumnDef.dim("lastPaymentDate", "最近付款日期")));
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        var scope = dataScope.target()
                .supplier("b.supplier")
                .build();
        if (scope.isDenyAll()) return plan.denyAll();

        LocalDate cutoff = FinanceReportSupport.cutoff(req);
        boolean billView = "bill".equalsIgnoreCase(req.text("viewMode"));

        // 第一层：账单 + 截至日已付
        List<Object> innerArgs = new ArrayList<>();
        StringBuilder inner = new StringBuilder("""
                SELECT b.ap_no AS ap_no, b.source_bill AS source_bill,
                       b.supplier AS supplier, b.supplier_code AS supplier_code,
                       b.buyer AS buyer, b.settlement_method AS settlement_method,
                       b.account_period_days AS account_period_days,
                       b.bill_date AS bill_date, b.due_date AS due_date,
                       b.invoice_status AS invoice_status, b.bill_source_type AS bill_source_type,
                       b.ap_amount AS ap_amount, b.invoiced_amount AS invoiced_amount,
                       COALESCE((SELECT SUM(rr.reconcile_amount)
                                   FROM fin_reconcile_record rr
                                  WHERE rr.business_no = b.ap_no
                                    AND rr.business_type IN """)
                .append(FinanceReportSupport.placeholders(FinanceReportSupport.AP_CASH_TYPES.size()))
                .append("""
                                    AND rr.receipt_date <= ?), 0) AS paid_amount
                  FROM v_rpt_ap_bill b
                 WHERE b.bill_date <= ?
                """);
        innerArgs.addAll(FinanceReportSupport.AP_CASH_TYPES);
        innerArgs.add(cutoff);
        innerArgs.add(cutoff);
        appendBillFilters(req, inner, innerArgs);
        scope.appendTo(inner, innerArgs);

        // 第二层：截至日余额、逾期天数、账龄桶
        // 绑定顺序按 SQL 文本位置：mid SELECT 列表里 7 个截止日占位符文本上先于 FROM(inner)
        // 内嵌子查询的全部占位符，故 mid 参数 = 前置 7 个截止日 + inner 参数 + 尾部过滤参数。
        List<Object> midLeadArgs = new ArrayList<>();
        // mid 层 7 个截止日：overdue_days CASE 2 个 + 账龄桶 CASE 5 个
        for (int i = 0; i < 7; i++) midLeadArgs.add(cutoff);
        List<Object> midTailArgs = new ArrayList<>();
        StringBuilder mid = new StringBuilder("""
                SELECT y.ap_no AS ap_no, y.source_bill AS source_bill, y.supplier AS supplier,
                       y.supplier_code AS supplier_code, y.buyer AS buyer,
                       y.settlement_method AS settlement_method, y.account_period_days AS account_period_days,
                       y.bill_date AS bill_date, y.due_date AS due_date,
                       y.invoice_status AS invoice_status, y.bill_source_type AS bill_source_type,
                       y.ap_amount AS ap_amount, y.invoiced_amount AS invoiced_amount,
                       y.paid_amount AS paid_amount,
                       (y.ap_amount - y.paid_amount) AS outstanding,
                       CASE WHEN y.due_date IS NULL OR y.due_date >= ? THEN 0
                            ELSE DATEDIFF('DAY', y.due_date, CAST(? AS DATE)) END AS overdue_days,
                       CASE WHEN (y.ap_amount - y.paid_amount) < 0
                                 OR y.due_date IS NULL OR y.due_date >= ? THEN '未到期'
                            WHEN DATEDIFF('DAY', y.due_date, CAST(? AS DATE)) <= 30 THEN '1-30天'
                            WHEN DATEDIFF('DAY', y.due_date, CAST(? AS DATE)) <= 60 THEN '31-60天'
                            WHEN DATEDIFF('DAY', y.due_date, CAST(? AS DATE)) <= 90 THEN '61-90天'
                            WHEN DATEDIFF('DAY', y.due_date, CAST(? AS DATE)) <= 180 THEN '91-180天'
                            ELSE '180天以上' END AS aging_bucket
                  FROM (
                """).append(inner).append(") y WHERE 1=1 ");
        if (!"1".equals(req.text("includeSettled"))) {
            mid.append(" AND ABS(y.ap_amount - y.paid_amount) > ").append(FinanceReportSupport.EPS).append(' ');
        }
        if ("1".equals(req.text("onlyOverdue"))) {
            mid.append(" AND (y.ap_amount - y.paid_amount) > ").append(FinanceReportSupport.EPS)
               .append(" AND y.due_date < ? ");
            midTailArgs.add(cutoff);
        }
        java.math.BigDecimal minOutstanding = req.decimal("minOutstanding");
        if (minOutstanding != null) {
            mid.append(" AND (y.ap_amount - y.paid_amount) >= ? ");
            midTailArgs.add(minOutstanding);
        }
        // mid 完整参数：前置 7 截止日 → inner 参数 → 尾部过滤
        List<Object> midArgs = new ArrayList<>(midLeadArgs);
        midArgs.addAll(innerArgs);
        midArgs.addAll(midTailArgs);

        if (billView) {
            plan.detailSelect = """
                    SELECT z.ap_no AS ap_no, z.source_bill AS source_bill,
                           z.supplier_code AS supplier_code, z.supplier AS supplier_name,
                           z.buyer AS buyer, z.settlement_method AS settlement_method,
                           z.bill_source_type AS bill_source_type,
                           z.bill_date AS bill_date, z.due_date AS due_date,
                           z.invoice_status AS invoice_status, z.aging_bucket AS aging_bucket,
                           z.ap_amount AS ap_amount, z.paid_amount AS paid_amount,
                           z.outstanding AS outstanding, z.overdue_days AS overdue_days,
                           z.invoiced_amount AS invoiced_amount,
                           CASE WHEN z.ap_amount > 0 THEN z.ap_amount - z.invoiced_amount ELSE 0 END AS uninvoiced_amount
                    FROM (""";
            plan.fromWhere.append(mid).append(") z");
            plan.args.addAll(midArgs);
            plan.defaultOrder = "ORDER BY z.overdue_days DESC, z.outstanding DESC, z.ap_no ASC";
            plan.sortWhitelist.putAll(Map.of(
                    "apNo", "ap_no",
                    "supplierName", "supplier_name",
                    "billDate", "bill_date",
                    "dueDate", "due_date",
                    "apAmount", "ap_amount",
                    "paidAmount", "paid_amount",
                    "outstanding", "outstanding",
                    "overdueDays", "overdue_days"));
            plan.grandSql = """
                    SELECT COUNT(1) AS bill_count,
                           COALESCE(SUM(g.ap_amount),0) AS ap_amount,
                           COALESCE(SUM(g.paid_amount),0) AS paid_amount,
                           COALESCE(SUM(g.outstanding),0) AS outstanding,
                           COALESCE(SUM(CASE WHEN g.aging_bucket = '未到期' THEN g.outstanding ELSE 0 END),0) AS unexpired_amount,
                           COALESCE(SUM(CASE WHEN g.aging_bucket = '1-30天' THEN g.outstanding ELSE 0 END),0) AS age_1_30,
                           COALESCE(SUM(CASE WHEN g.aging_bucket = '31-60天' THEN g.outstanding ELSE 0 END),0) AS age_31_60,
                           COALESCE(SUM(CASE WHEN g.aging_bucket = '61-90天' THEN g.outstanding ELSE 0 END),0) AS age_61_90,
                           COALESCE(SUM(CASE WHEN g.aging_bucket = '91-180天' THEN g.outstanding ELSE 0 END),0) AS age_91_180,
                           COALESCE(SUM(CASE WHEN g.aging_bucket = '180天以上' THEN g.outstanding ELSE 0 END),0) AS age_180_plus,
                           COALESCE(MAX(CASE WHEN g.outstanding > 0 THEN g.overdue_days ELSE 0 END),0) AS max_overdue_days,
                           COALESCE(SUM(CASE WHEN g.outstanding > 0 AND g.overdue_days > 0 THEN 1 ELSE 0 END),0) AS overdue_bill_count,
                           COALESCE(SUM(g.invoiced_amount),0) AS invoiced_amount,
                           COALESCE(SUM(CASE WHEN g.ap_amount > 0 THEN g.ap_amount - g.invoiced_amount ELSE 0 END),0) AS uninvoiced_amount
                    FROM (%s) g
                    """.formatted(mid);
            plan.grandArgs.addAll(midArgs);
        } else {
            // 注意绑定顺序：SELECT 列里 last_payment 标量的 IN(?) 文本上先于 FROM(mid)，
            // 因此最近付款类型参数必须排在 midArgs 之前。
            List<Object> outerArgs = new ArrayList<>(FinanceReportSupport.AP_CASH_TYPES);
            outerArgs.addAll(midArgs);
            StringBuilder outer = new StringBuilder("""
                    SELECT z.supplier_code AS supplier_code,
                           MAX(z.supplier) AS supplier_name,
                           MAX(z.buyer) AS buyer,
                           MAX(z.settlement_method) AS settlement_method,
                           COUNT(1) AS bill_count,
                           SUM(z.ap_amount) AS ap_amount,
                           SUM(z.paid_amount) AS paid_amount,
                           SUM(z.outstanding) AS outstanding,
                           SUM(CASE WHEN z.aging_bucket = '未到期' THEN z.outstanding ELSE 0 END) AS unexpired_amount,
                           SUM(CASE WHEN z.aging_bucket = '1-30天' THEN z.outstanding ELSE 0 END) AS age_1_30,
                           SUM(CASE WHEN z.aging_bucket = '31-60天' THEN z.outstanding ELSE 0 END) AS age_31_60,
                           SUM(CASE WHEN z.aging_bucket = '61-90天' THEN z.outstanding ELSE 0 END) AS age_61_90,
                           SUM(CASE WHEN z.aging_bucket = '91-180天' THEN z.outstanding ELSE 0 END) AS age_91_180,
                           SUM(CASE WHEN z.aging_bucket = '180天以上' THEN z.outstanding ELSE 0 END) AS age_180_plus,
                           MAX(CASE WHEN z.outstanding > 0 THEN z.overdue_days ELSE 0 END) AS max_overdue_days,
                           COUNT(CASE WHEN z.outstanding > 0 AND z.overdue_days > 0 THEN 1 END) AS overdue_bill_count,
                           SUM(z.invoiced_amount) AS invoiced_amount,
                           SUM(CASE WHEN z.ap_amount > 0 THEN z.ap_amount - z.invoiced_amount ELSE 0 END) AS uninvoiced_amount,
                           (SELECT MAX(rr2.receipt_date)
                              FROM fin_reconcile_record rr2
                             WHERE rr2.counterparty_name = z.supplier
                               AND rr2.counterparty_type = 'SUPPLIER'
                               AND rr2.business_type IN """)
                    .append(FinanceReportSupport.placeholders(FinanceReportSupport.AP_CASH_TYPES.size()))
                    .append(") AS last_payment_date FROM (\n")
                    .append(mid)
                    .append(") z GROUP BY z.supplier_code HAVING 1=1 ");
            if (!"1".equals(req.text("includeSettled"))) {
                outer.append(" AND ABS(SUM(z.outstanding)) > ").append(FinanceReportSupport.EPS).append(' ');
            }
            if ("1".equals(req.text("onlyOverdue"))) {
                outer.append(" AND SUM(CASE WHEN z.outstanding > 0 AND z.overdue_days > 0 "
                        + "THEN z.outstanding ELSE 0 END) > ").append(FinanceReportSupport.EPS).append(' ');
            }
            if (minOutstanding != null) {
                outer.append(" AND SUM(z.outstanding) >= ? ");
                outerArgs.add(minOutstanding);
            }

            plan.detailSelect = "SELECT t.* FROM (";
            plan.fromWhere.append(outer).append(") t");
            plan.args.addAll(outerArgs);
            plan.defaultOrder = "ORDER BY t.outstanding DESC, t.max_overdue_days DESC, t.supplier_code ASC";
            plan.sortWhitelist.putAll(Map.of(
                    "supplierName", "supplier_name",
                    "billCount", "bill_count",
                    "apAmount", "ap_amount",
                    "outstanding", "outstanding",
                    "maxOverdueDays", "max_overdue_days",
                    "overdueBillCount", "overdue_bill_count",
                    "invoicedAmount", "invoiced_amount",
                    "uninvoicedAmount", "uninvoiced_amount"));
            plan.grandSql = """
                    SELECT COALESCE(SUM(t.bill_count),0) AS bill_count,
                           COALESCE(SUM(t.ap_amount),0) AS ap_amount,
                           COALESCE(SUM(t.paid_amount),0) AS paid_amount,
                           COALESCE(SUM(t.outstanding),0) AS outstanding,
                           COALESCE(SUM(t.unexpired_amount),0) AS unexpired_amount,
                           COALESCE(SUM(t.age_1_30),0) AS age_1_30,
                           COALESCE(SUM(t.age_31_60),0) AS age_31_60,
                           COALESCE(SUM(t.age_61_90),0) AS age_61_90,
                           COALESCE(SUM(t.age_91_180),0) AS age_91_180,
                           COALESCE(SUM(t.age_180_plus),0) AS age_180_plus,
                           COALESCE(MAX(t.max_overdue_days),0) AS max_overdue_days,
                           COALESCE(SUM(t.overdue_bill_count),0) AS overdue_bill_count,
                           COALESCE(SUM(t.invoiced_amount),0) AS invoiced_amount,
                           COALESCE(SUM(t.uninvoiced_amount),0) AS uninvoiced_amount
                    FROM (%s) t
                    """.formatted(outer);
            plan.grandArgs.addAll(outerArgs);
        }

        for (String f : List.of("apAmount", "paidAmount", "outstanding", "unexpiredAmount",
                "age1To30", "age31To60", "age61To90", "age91To180", "age180Plus",
                "invoicedAmount", "uninvoicedAmount")) {
            plan.maskOverrides.put(f, PERM);
        }
        return plan;
    }

    private void appendBillFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args) {
        String supplier = req.text("supplier");
        if (supplier != null) {
            sql.append(" AND (b.supplier_code LIKE ? OR b.supplier LIKE ?) ");
            args.add("%" + supplier + "%");
            args.add("%" + supplier + "%");
        }
        appendEq(req, sql, args, "buyer", "b.buyer");
        appendEq(req, sql, args, "settlementMethod", "b.settlement_method");
        appendEq(req, sql, args, "invoiceStatus", "b.invoice_status");
    }

    private static void appendEq(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                                 String key, String column) {
        String v = req.text(key);
        if (v != null) {
            sql.append(" AND ").append(column).append(" = ? ");
            args.add(v);
        }
    }
}
