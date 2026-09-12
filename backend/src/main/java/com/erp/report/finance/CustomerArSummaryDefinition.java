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
 * 报表23｜客户应收汇总表（期间发生额滚动：期初 + 本期新增 − 本期回款 = 期末）。
 *
 * <p>与 {@link ArAgingDefinition}（#20，时点账龄）互补：本表回答「这月往来怎么变的」。
 * 取数复用共享 DWD 视图 v_rpt_ar_bill（立账日=签收/退货审核日，含税金额，退货红冲为负）：
 * <ul>
 *   <li>期初 = 期间前立账金额 − 期间前已核销（核销按 fin_reconcile_record 实算，不取当前余额）；</li>
 *   <li>本期新增 = 立账日落在期间的应收金额（与 #16 同期签收含税金额勾稽）；</li>
 *   <li>本期回款 = receipt_date 落在期间的应收核销流水（四类 AR_CASH_TYPES，含取消审核红字负行）；</li>
 *   <li>期末 = 立账日 &le; 期末 − 截至期末已核销；恒等式代数成立（预收/预付自动表现为负数）；</li>
 *   <li>减免/抹零列取 EXPENSE_WRITEOFF 流水仅作展示——该流水 business_no 是费用单号，
 *       不按单冲减应收（实际冲减发生在 AR_SETTLE 核销记录里），故<b>不参与恒等式扣减</b>，
 *       避免与回款列重复计算；</li>
 *   <li>其中逾期 = 期末口径下到期日 &le; 期末的单据未核销余额。</li>
 * </ul>
 * 默认期间 K2（截止昨天、起始为截止日上月同日的前一天）；默认仅显示期末有余额客户。
 */
@Component
public class CustomerArSummaryDefinition implements ReportDefinition {

    private static final String PERM = "VIEW_AR_BALANCE";

    private final DataScopeService dataScope;

    public CustomerArSummaryDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "customer_ar_summary"; }
    @Override public String name() { return "客户应收汇总表"; }
    @Override public String viewPerm() { return "report.customer_ar_summary.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        return new ArrayList<>(List.of(
                ReportColumnDef.dim("customerCode", "客户编号"),
                ReportColumnDef.dim("customerName", "客户名称"),
                ReportColumnDef.dim("customerLevel", "客户等级"),
                ReportColumnDef.dim("territory", "区域"),
                ReportColumnDef.dim("salesman", "业务员"),
                ReportColumnDef.measure("openingAmount", "期初应收", PERM),
                ReportColumnDef.measure("newAmount", "本期新增应收", PERM),
                ReportColumnDef.measure("receivedAmount", "本期回款", PERM),
                ReportColumnDef.measure("writeoffAmount", "其中减免/抹零", PERM),
                ReportColumnDef.measure("endingAmount", "期末应收", PERM),
                ReportColumnDef.measure("overdueAmount", "其中逾期", PERM),
                ReportColumnDef.measure("creditLimit", "信用额度", PERM),
                ReportColumnDef.measure("creditOccupancy", "信用额度占用率", null)));
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        var scope = dataScope.target()
                .customer("b.customer").salesman("b.salesman")
                .build();
        if (scope.isDenyAll()) return plan.denyAll();

        var start = req.range().startDate();
        var end = req.range().endDate();

        StringBuilder leaf = new StringBuilder("""
                SELECT t.customer_code AS customer_code,
                       MAX(t.customer_name) AS customer_name,
                       MAX(t.customer_level) AS customer_level,
                       MAX(t.territory) AS territory,
                       MAX(t.salesman) AS salesman,
                       COALESCE(SUM(t.open_bill - t.paid_before),0) AS opening_amount,
                       COALESCE(SUM(t.new_bill),0) AS new_amount,
                       COALESCE(SUM(t.paid_in),0) AS received_amount,
                       COALESCE(MAX(t.wo_amount),0) AS writeoff_amount,
                       COALESCE(SUM(t.end_bill - t.paid_to_end),0) AS ending_amount,
                       COALESCE(SUM(t.overdue_bill - t.overdue_paid),0) AS overdue_amount,
                       MAX(t.credit_limit) AS credit_limit,
                       COALESCE(SUM(t.end_bill - t.paid_to_end),0)
                           / NULLIF(MAX(t.credit_limit),0) AS credit_occupancy
                  FROM (
                SELECT b.customer_code AS customer_code,
                       b.customer AS customer_name,
                       b.customer_level AS customer_level,
                       b.territory AS territory,
                       b.salesman AS salesman,
                       b.credit_limit AS credit_limit,
                       CASE WHEN b.bill_date < ? THEN b.ar_amount ELSE 0 END AS open_bill,
                       CASE WHEN b.bill_date BETWEEN ? AND ? THEN b.ar_amount ELSE 0 END AS new_bill,
                       CASE WHEN b.bill_date <= ? THEN b.ar_amount ELSE 0 END AS end_bill,
                       COALESCE(rc.paid_before,0) AS paid_before,
                       COALESCE(rc.paid_in,0) AS paid_in,
                       COALESCE(rc.paid_to_end,0) AS paid_to_end,
                       CASE WHEN b.bill_date <= ? AND b.due_date IS NOT NULL AND b.due_date <= ?
                            THEN b.ar_amount ELSE 0 END AS overdue_bill,
                       CASE WHEN b.bill_date <= ? AND b.due_date IS NOT NULL AND b.due_date <= ?
                            THEN COALESCE(rc.paid_to_end,0) ELSE 0 END AS overdue_paid,
                       COALESCE(wo.wo_amount,0) AS wo_amount
                  FROM v_rpt_ar_bill b
                  LEFT JOIN (
                      SELECT business_no AS ar_no,
                             SUM(CASE WHEN receipt_date < ? THEN reconcile_amount ELSE 0 END) AS paid_before,
                             SUM(CASE WHEN receipt_date BETWEEN ? AND ? THEN reconcile_amount ELSE 0 END) AS paid_in,
                             SUM(reconcile_amount) AS paid_to_end
                        FROM fin_reconcile_record
                       WHERE business_type IN """)
                .append(FinanceReportSupport.placeholders(FinanceReportSupport.AR_CASH_TYPES.size()))
                .append("""
                         AND receipt_date <= ?
                       GROUP BY business_no
                  ) rc ON rc.ar_no = b.ar_no
                  LEFT JOIN (
                      SELECT counterparty_name AS cp_name,
                             SUM(reconcile_amount) AS wo_amount
                        FROM fin_reconcile_record
                       WHERE business_type = 'EXPENSE_WRITEOFF'
                         AND counterparty_type = 'CUSTOMER'
                         AND receipt_date BETWEEN ? AND ?
                       GROUP BY counterparty_name
                  ) wo ON wo.cp_name = b.customer
                 WHERE b.bill_date <= ?
                """);
        List<Object> args = new ArrayList<>();
        // 绑定顺序严格按 SQL 文本中 ? 出现顺序
        args.add(start);                       // open_bill < start
        args.add(start); args.add(end);        // new_bill BETWEEN
        args.add(end);                         // end_bill <= end
        args.add(end); args.add(end);          // overdue_bill
        args.add(end); args.add(end);          // overdue_paid
        args.add(start);                       // rc.paid_before < start
        args.add(start); args.add(end);        // rc.paid_in BETWEEN
        args.addAll(FinanceReportSupport.AR_CASH_TYPES);
        args.add(end);                         // rc receipt_date <= end
        args.add(start); args.add(end);        // 抹零期间
        args.add(end);                         // b.bill_date <= end
        appendFilters(req, leaf, args);
        scope.appendTo(leaf, args);
        leaf.append(") t GROUP BY t.customer_code HAVING 1=1 ");
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
        plan.defaultOrder = "ORDER BY g.ending_amount DESC, g.customer_code ASC";
        plan.sortWhitelist.putAll(Map.of(
                "customerName", "customer_name",
                "openingAmount", "opening_amount",
                "newAmount", "new_amount",
                "receivedAmount", "received_amount",
                "endingAmount", "ending_amount",
                "overdueAmount", "overdue_amount",
                "creditOccupancy", "credit_occupancy"));
        plan.grandSql = """
                SELECT COALESCE(SUM(x.opening_amount),0) AS opening_amount,
                       COALESCE(SUM(x.new_amount),0) AS new_amount,
                       COALESCE(SUM(x.received_amount),0) AS received_amount,
                       COALESCE(SUM(x.writeoff_amount),0) AS writeoff_amount,
                       COALESCE(SUM(x.ending_amount),0) AS ending_amount,
                       COALESCE(SUM(x.overdue_amount),0) AS overdue_amount
                FROM (%s) x
                """.formatted(leaf);
        plan.grandArgs.addAll(args);
        for (String f : List.of("openingAmount", "newAmount", "receivedAmount", "writeoffAmount",
                "endingAmount", "overdueAmount", "creditLimit")) {
            plan.maskOverrides.put(f, PERM);
        }
        return plan;
    }

    private void appendFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args) {
        String customer = req.text("customer");
        if (customer != null) {
            sql.append(" AND (b.customer_code LIKE ? OR b.customer LIKE ?) ");
            args.add("%" + customer + "%");
            args.add("%" + customer + "%");
        }
        appendEq(req, sql, args, "customerLevel", "b.customer_level");
        appendEq(req, sql, args, "territory", "b.territory");
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
