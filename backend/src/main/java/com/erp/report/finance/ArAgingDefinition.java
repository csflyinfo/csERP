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
 * 报表20｜应收账款账龄分析表（时点报表）。
 *
 * <p>账龄是<b>时点</b>口径：以「截止日期」（默认今天）为界——
 * <ul>
 *   <li>立账/账单：取共享 DWD 视图 v_rpt_ar_bill（立账日=签收/退货审核日，含客户档案维度）；</li>
 *   <li>已核销：不取 fin_ar.received_amount 当前值，而是按 fin_reconcile_record
 *       流水 receipt_date &le; 截止日 重新归集（取消审核写的红字负行自然轧差）；</li>
 *   <li>截至日余额 = 应收金额 − 截至日已核销；账龄桶按到期日 due_date 相对截止日的逾期天数分桶
 *       （未到期 / 1-30 / 31-60 / 61-90 / 91-180 / 180 天以上）；
 *       退货红冲形成的负数余额（预收性质）并入「未到期」桶，参与总额轧差。</li>
 * </ul>
 *
 * <p>两种视图（filters.viewMode）：customer=客户汇总（默认，含信用额度占用率，
 * 占用率≥80% 前端标红）；bill=单据明细。默认不显示已结清行。
 * 金额列挂 VIEW_AR_BALANCE 脱敏。
 */
@Component
public class ArAgingDefinition implements ReportDefinition {

    private static final String PERM = "VIEW_AR_BALANCE";

    private final DataScopeService dataScope;

    public ArAgingDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "ar_aging"; }
    @Override public String name() { return "应收账款账龄分析表"; }
    @Override public String viewPerm() { return "report.ar_aging.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        List<ReportColumnDef> cols = new ArrayList<>(List.of(
                ReportColumnDef.dim("customerCode", "客户编号"),
                ReportColumnDef.dim("customerName", "客户名称"),
                ReportColumnDef.dim("customerLevel", "客户等级"),
                ReportColumnDef.dim("territory", "区域"),
                ReportColumnDef.dim("salesman", "业务员"),
                ReportColumnDef.dim("arNo", "应收单号"),
                ReportColumnDef.dim("sourceBill", "来源单号"),
                ReportColumnDef.dim("billSourceType", "单据类型"),
                ReportColumnDef.dim("billDate", "立账日期"),
                ReportColumnDef.dim("dueDate", "到期日期"),
                ReportColumnDef.dim("invoiceStatus", "开票状态"),
                ReportColumnDef.dim("agingBucket", "账龄区间"),
                ReportColumnDef.measure("billCount", "单据数", null),
                ReportColumnDef.measure("arAmount", "应收金额", PERM),
                ReportColumnDef.measure("receivedAmount", "已核销金额", PERM),
                ReportColumnDef.measure("outstanding", "截至日余额", PERM),
                ReportColumnDef.measure("unexpiredAmount", "未到期", PERM),
                ReportColumnDef.measure("age1To30", "逾期1-30天", PERM),
                ReportColumnDef.measure("age31To60", "逾期31-60天", PERM),
                ReportColumnDef.measure("age61To90", "逾期61-90天", PERM),
                ReportColumnDef.measure("age91To180", "逾期91-180天", PERM),
                ReportColumnDef.measure("age180Plus", "逾期180天以上", PERM),
                ReportColumnDef.measure("maxOverdueDays", "最长逾期天数", null),
                ReportColumnDef.measure("overdueBillCount", "逾期单据数", null),
                ReportColumnDef.measure("creditLimit", "信用额度", PERM),
                ReportColumnDef.measure("creditOccupancy", "信用额度占用率", null),
                ReportColumnDef.dim("lastReceiptDate", "最近回款日期")));
        return cols;
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        var scope = dataScope.target()
                .customer("b.customer").salesman("b.salesman")
                .build();
        if (scope.isDenyAll()) return plan.denyAll();

        LocalDate cutoff = FinanceReportSupport.cutoff(req);
        boolean billView = "bill".equalsIgnoreCase(req.text("viewMode"));

        // 第一层：账单 + 截至日已核销（按单关联流水，receipt_date <= 截止日）
        List<Object> innerArgs = new ArrayList<>();
        StringBuilder inner = new StringBuilder("""
                SELECT b.ar_no AS ar_no, b.source_bill AS source_bill,
                       b.customer AS customer, b.customer_code AS customer_code,
                       b.customer_level AS customer_level, b.territory AS territory,
                       b.salesman AS salesman, b.credit_limit AS credit_limit,
                       b.bill_date AS bill_date, b.due_date AS due_date,
                       b.invoice_status AS invoice_status, b.bill_source_type AS bill_source_type,
                       b.ar_amount AS ar_amount,
                       COALESCE((SELECT SUM(rr.reconcile_amount)
                                   FROM fin_reconcile_record rr
                                  WHERE rr.business_no = b.ar_no
                                    AND rr.business_type IN """)
                .append(FinanceReportSupport.placeholders(FinanceReportSupport.AR_CASH_TYPES.size()))
                .append("""
                                    AND rr.receipt_date <= ?), 0) AS received_amount
                  FROM v_rpt_ar_bill b
                 WHERE b.bill_date <= ?
                """);
        innerArgs.addAll(FinanceReportSupport.AR_CASH_TYPES);
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
                SELECT y.ar_no AS ar_no, y.source_bill AS source_bill, y.customer AS customer,
                       y.customer_code AS customer_code, y.customer_level AS customer_level,
                       y.territory AS territory, y.salesman AS salesman,
                       y.credit_limit AS credit_limit, y.bill_date AS bill_date,
                       y.due_date AS due_date, y.invoice_status AS invoice_status,
                       y.bill_source_type AS bill_source_type,
                       y.ar_amount AS ar_amount, y.received_amount AS received_amount,
                       (y.ar_amount - y.received_amount) AS outstanding,
                       CASE WHEN y.due_date IS NULL OR y.due_date >= ? THEN 0
                            ELSE DATEDIFF('DAY', y.due_date, CAST(? AS DATE)) END AS overdue_days,
                       CASE WHEN (y.ar_amount - y.received_amount) < 0
                                 OR y.due_date IS NULL OR y.due_date >= ? THEN '未到期'
                            WHEN DATEDIFF('DAY', y.due_date, CAST(? AS DATE)) <= 30 THEN '1-30天'
                            WHEN DATEDIFF('DAY', y.due_date, CAST(? AS DATE)) <= 60 THEN '31-60天'
                            WHEN DATEDIFF('DAY', y.due_date, CAST(? AS DATE)) <= 90 THEN '61-90天'
                            WHEN DATEDIFF('DAY', y.due_date, CAST(? AS DATE)) <= 180 THEN '91-180天'
                            ELSE '180天以上' END AS aging_bucket
                  FROM (
                """).append(inner).append(") y WHERE 1=1 ");
        if (!"1".equals(req.text("includeSettled"))) {
            mid.append(" AND ABS(y.ar_amount - y.received_amount) > ").append(FinanceReportSupport.EPS).append(' ');
        }
        if ("1".equals(req.text("onlyOverdue"))) {
            mid.append(" AND (y.ar_amount - y.received_amount) > ").append(FinanceReportSupport.EPS)
               .append(" AND y.due_date < ? ");
            midTailArgs.add(cutoff);
        }
        java.math.BigDecimal minOutstanding = req.decimal("minOutstanding");
        if (minOutstanding != null) {
            mid.append(" AND (y.ar_amount - y.received_amount) >= ? ");
            midTailArgs.add(minOutstanding);
        }
        // mid 完整参数：前置 7 截止日 → inner 参数 → 尾部过滤
        List<Object> midArgs = new ArrayList<>(midLeadArgs);
        midArgs.addAll(innerArgs);
        midArgs.addAll(midTailArgs);

        if (billView) {
            plan.detailSelect = """
                    SELECT z.ar_no AS ar_no, z.source_bill AS source_bill,
                           z.customer_code AS customer_code, z.customer AS customer_name,
                           z.customer_level AS customer_level, z.territory AS territory,
                           z.salesman AS salesman, z.bill_source_type AS bill_source_type,
                           z.bill_date AS bill_date, z.due_date AS due_date,
                           z.invoice_status AS invoice_status, z.aging_bucket AS aging_bucket,
                           z.ar_amount AS ar_amount, z.received_amount AS received_amount,
                           z.outstanding AS outstanding, z.overdue_days AS overdue_days
                    FROM (""";
            plan.fromWhere.append(mid).append(") z");
            plan.args.addAll(midArgs);
            plan.defaultOrder = "ORDER BY z.overdue_days DESC, z.outstanding DESC, z.ar_no ASC";
            plan.sortWhitelist.putAll(Map.of(
                    "arNo", "ar_no",
                    "customerName", "customer_name",
                    "billDate", "bill_date",
                    "dueDate", "due_date",
                    "arAmount", "ar_amount",
                    "receivedAmount", "received_amount",
                    "outstanding", "outstanding",
                    "overdueDays", "overdue_days"));
            buildBillGrand(plan, mid, midArgs);
        } else {
            // 第三层：按客户汇总（账龄桶透视 + 信用额度占用 + 最近回款日）
            // 注意绑定顺序：SELECT 列里 last_receipt 标量的 IN(?) 文本上先于 FROM(mid)，
            // 因此最近回款类型参数必须排在 midArgs 之前。
            List<Object> outerArgs = new ArrayList<>(FinanceReportSupport.AR_CASH_TYPES);
            outerArgs.addAll(midArgs);
            StringBuilder outer = new StringBuilder("""
                    SELECT z.customer_code AS customer_code,
                           MAX(z.customer) AS customer_name,
                           MAX(z.customer_level) AS customer_level,
                           MAX(z.territory) AS territory,
                           MAX(z.salesman) AS salesman,
                           MAX(z.credit_limit) AS credit_limit,
                           COUNT(1) AS bill_count,
                           SUM(z.ar_amount) AS ar_amount,
                           SUM(z.received_amount) AS received_amount,
                           SUM(z.outstanding) AS outstanding,
                           SUM(CASE WHEN z.aging_bucket = '未到期' THEN z.outstanding ELSE 0 END) AS unexpired_amount,
                           SUM(CASE WHEN z.aging_bucket = '1-30天' THEN z.outstanding ELSE 0 END) AS age_1_30,
                           SUM(CASE WHEN z.aging_bucket = '31-60天' THEN z.outstanding ELSE 0 END) AS age_31_60,
                           SUM(CASE WHEN z.aging_bucket = '61-90天' THEN z.outstanding ELSE 0 END) AS age_61_90,
                           SUM(CASE WHEN z.aging_bucket = '91-180天' THEN z.outstanding ELSE 0 END) AS age_91_180,
                           SUM(CASE WHEN z.aging_bucket = '180天以上' THEN z.outstanding ELSE 0 END) AS age_180_plus,
                           MAX(CASE WHEN z.outstanding > 0 THEN z.overdue_days ELSE 0 END) AS max_overdue_days,
                           COUNT(CASE WHEN z.outstanding > 0 AND z.overdue_days > 0 THEN 1 END) AS overdue_bill_count,
                           SUM(z.outstanding) / NULLIF(MAX(z.credit_limit), 0) AS credit_occupancy,
                           (SELECT MAX(rr2.receipt_date)
                              FROM fin_reconcile_record rr2
                             WHERE rr2.counterparty_name = z.customer
                               AND rr2.counterparty_type = 'CUSTOMER'
                               AND rr2.business_type IN """)
                    .append(FinanceReportSupport.placeholders(FinanceReportSupport.AR_CASH_TYPES.size()))
                    .append(") AS last_receipt_date FROM (\n")
                    .append(mid)
                    .append(") z GROUP BY z.customer_code HAVING 1=1 ");
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
            plan.defaultOrder = "ORDER BY t.outstanding DESC, t.max_overdue_days DESC, t.customer_code ASC";
            plan.sortWhitelist.putAll(Map.of(
                    "customerName", "customer_name",
                    "billCount", "bill_count",
                    "arAmount", "ar_amount",
                    "outstanding", "outstanding",
                    "maxOverdueDays", "max_overdue_days",
                    "overdueBillCount", "overdue_bill_count",
                    "creditOccupancy", "credit_occupancy"));
            buildCustomerGrand(plan, outer, outerArgs);
        }

        for (String f : List.of("arAmount", "receivedAmount", "outstanding", "unexpiredAmount",
                "age1To30", "age31To60", "age61To90", "age91To180", "age180Plus", "creditLimit")) {
            plan.maskOverrides.put(f, PERM);
        }
        return plan;
    }

    /** 单据视图合计：桶金额按账龄桶 SUM，最长逾期取 MAX（mid 本身是完整 SELECT，直接包一层）。 */
    private void buildBillGrand(Plan plan, CharSequence mid, List<Object> midArgs) {
        plan.grandSql = """
                SELECT COUNT(1) AS bill_count,
                       COALESCE(SUM(g.ar_amount),0) AS ar_amount,
                       COALESCE(SUM(g.received_amount),0) AS received_amount,
                       COALESCE(SUM(g.outstanding),0) AS outstanding,
                       COALESCE(SUM(CASE WHEN g.aging_bucket = '未到期' THEN g.outstanding ELSE 0 END),0) AS unexpired_amount,
                       COALESCE(SUM(CASE WHEN g.aging_bucket = '1-30天' THEN g.outstanding ELSE 0 END),0) AS age_1_30,
                       COALESCE(SUM(CASE WHEN g.aging_bucket = '31-60天' THEN g.outstanding ELSE 0 END),0) AS age_31_60,
                       COALESCE(SUM(CASE WHEN g.aging_bucket = '61-90天' THEN g.outstanding ELSE 0 END),0) AS age_61_90,
                       COALESCE(SUM(CASE WHEN g.aging_bucket = '91-180天' THEN g.outstanding ELSE 0 END),0) AS age_91_180,
                       COALESCE(SUM(CASE WHEN g.aging_bucket = '180天以上' THEN g.outstanding ELSE 0 END),0) AS age_180_plus,
                       COALESCE(MAX(CASE WHEN g.outstanding > 0 THEN g.overdue_days ELSE 0 END),0) AS max_overdue_days,
                       COALESCE(SUM(CASE WHEN g.outstanding > 0 AND g.overdue_days > 0 THEN 1 ELSE 0 END),0) AS overdue_bill_count
                FROM (%s) g
                """.formatted(mid);
        plan.grandArgs.addAll(midArgs);
    }

    /** 客户视图合计：对客户叶子行再聚合（占用率/最近回款日不可加，合计不给）。 */
    private void buildCustomerGrand(Plan plan, CharSequence outer, List<Object> outerArgs) {
        plan.grandSql = """
                SELECT COALESCE(SUM(t.bill_count),0) AS bill_count,
                       COALESCE(SUM(t.ar_amount),0) AS ar_amount,
                       COALESCE(SUM(t.received_amount),0) AS received_amount,
                       COALESCE(SUM(t.outstanding),0) AS outstanding,
                       COALESCE(SUM(t.unexpired_amount),0) AS unexpired_amount,
                       COALESCE(SUM(t.age_1_30),0) AS age_1_30,
                       COALESCE(SUM(t.age_31_60),0) AS age_31_60,
                       COALESCE(SUM(t.age_61_90),0) AS age_61_90,
                       COALESCE(SUM(t.age_91_180),0) AS age_91_180,
                       COALESCE(SUM(t.age_180_plus),0) AS age_180_plus,
                       COALESCE(MAX(t.max_overdue_days),0) AS max_overdue_days,
                       COALESCE(SUM(t.overdue_bill_count),0) AS overdue_bill_count
                FROM (%s) t
                """.formatted(outer);
        plan.grandArgs.addAll(outerArgs);
    }

    private void appendBillFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args) {
        String customer = req.text("customer");
        if (customer != null) {
            sql.append(" AND (b.customer_code LIKE ? OR b.customer LIKE ?) ");
            args.add("%" + customer + "%");
            args.add("%" + customer + "%");
        }
        appendEq(req, sql, args, "customerLevel", "b.customer_level");
        appendEq(req, sql, args, "territory", "b.territory");
        appendEq(req, sql, args, "invoiceStatus", "b.invoice_status");
        String salesman = req.text("salesman");
        if (salesman != null) {
            sql.append(" AND b.salesman LIKE ? ");
            args.add("%" + salesman + "%");
        }
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
