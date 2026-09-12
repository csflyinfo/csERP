package com.erp.report.sales;

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
 * 报表13｜业务员销售汇总表。粒度：业务员。
 *
 * <p>销售指标取头粒度 DWS rpt_dws_sales_bill_d；新客户数=该客户全历史首张签单日落在本期
 * 且签单业务员为本行（NOT EXISTS 更早签单，跨业务员不重复计）；本期回款实时聚合
 * fin_reconcile_record，业务员归属优先取被核销应收(fin_ar.salesman)，兜底客户档案负责人；
 * 期末应收/逾期取 fin_ar 按业务员汇总（业务员归属同样兜底客户档案负责人）；
 * 部门/上级业务员由 base_employee 补。
 *
 * <p>回款率=本期回款÷本期签收金额；钻取：业务员行 → #14。
 */
@Component
public class SalesmanSummaryDefinition implements ReportDefinition {

    private final DataScopeService dataScope;

    public SalesmanSummaryDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "salesman_summary"; }
    @Override public String name() { return "业务员销售汇总表"; }
    @Override public String viewPerm() { return "report.salesman_summary.view"; }
    @Override public boolean dws() { return true; }
    @Override public boolean summaryReport() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        return new ArrayList<>(List.of(
                ReportColumnDef.dim("salesman", "业务员"),
                ReportColumnDef.dim("department", "部门"),
                ReportColumnDef.dim("parentSalesman", "上级业务员"),
                ReportColumnDef.measure("receiptCount", "签收单数", null),
                ReportColumnDef.measure("customerCount", "客户数", null),
                ReportColumnDef.measure("newCustomerCount", "新客户数", null),
                ReportColumnDef.measure("signedAmount", "销售金额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("returnAmount", "退货金额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("netAmount", "净销售额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("netQtyBase", "净销售数量(小单位)", null),
                ReportColumnDef.measure("costAmount", "成本金额", "VIEW_COST_AMOUNT"),
                ReportColumnDef.measure("grossProfit", "毛利额", "VIEW_PROFIT"),
                ReportColumnDef.measure("grossProfitRate", "毛利率", "VIEW_PROFIT"),
                ReportColumnDef.measure("avgOrderAmount", "客单价", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("receivedAmount", "本期回款额", "VIEW_AR_BALANCE"),
                ReportColumnDef.measure("receiveRate", "回款率", "VIEW_AR_BALANCE"),
                ReportColumnDef.measure("arBalance", "期末应收余额", "VIEW_AR_BALANCE"),
                ReportColumnDef.measure("overdueAmount", "其中逾期", "VIEW_AR_BALANCE")));
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        var scope = dataScope.target()
                .warehouse("d.warehouse").customer("d.customer_name").salesman("d.salesman")
                .build();
        if (scope.isDenyAll()) return plan.denyAll();

        // 内层：头 DWS 按业务员聚合，派生表依次为 新客户数 / 本期回款 / 期末应收
        StringBuilder inner = new StringBuilder("""
                SELECT d.salesman AS salesman,
                       SUM(CASE WHEN d.bill_type = 'SIGN' THEN 1 ELSE 0 END) AS receipt_count,
                       COUNT(DISTINCT CASE WHEN d.bill_type = 'SIGN'
                                           THEN d.customer_code END) AS customer_count,
                       COALESCE(MAX(nc.new_customer_count),0) AS new_customer_count,
                       COALESCE(SUM(d.signed_amount),0) AS signed_amount,
                       COALESCE(SUM(d.return_amount),0) AS return_amount,
                       COALESCE(SUM(d.signed_amount - d.return_amount),0) AS net_amount,
                       COALESCE(SUM(d.signed_qty_base - d.return_qty_base),0) AS net_qty_base,
                       COALESCE(SUM(d.signed_cost_amount - d.return_cost_amount),0) AS cost_amount,
                       COALESCE(SUM(d.signed_amount - d.return_amount
                                  - d.signed_cost_amount + d.return_cost_amount),0) AS gross_profit,
                       COALESCE(MAX(pay.received_amount),0) AS received_amount,
                       COALESCE(MAX(ar.ar_balance),0) AS ar_balance,
                       COALESCE(MAX(ar.overdue_amount),0) AS overdue_amount
                FROM rpt_dws_sales_bill_d d
                LEFT JOIN (
                    SELECT fs.salesman AS salesman, COUNT(*) AS new_customer_count
                    FROM (
                        SELECT d2.salesman AS salesman, d2.customer_code AS customer_code,
                               MIN(d2.bill_date) AS first_date
                        FROM rpt_dws_sales_bill_d d2
                        WHERE d2.bill_type = 'SIGN'
                        GROUP BY d2.salesman, d2.customer_code
                    ) fs
                    WHERE fs.first_date BETWEEN ? AND ?
                      AND NOT EXISTS (
                          SELECT 1 FROM rpt_dws_sales_bill_d x
                          WHERE x.bill_type = 'SIGN'
                            AND x.customer_code = fs.customer_code
                            AND x.bill_date < fs.first_date)
                    GROUP BY fs.salesman
                ) nc ON nc.salesman = d.salesman
                LEFT JOIN (
                    SELECT COALESCE(fa.salesman, cs.salesman) AS salesman,
                           SUM(rr.reconcile_amount) AS received_amount
                    FROM fin_reconcile_record rr
                    LEFT JOIN fin_ar fa ON fa.ar_no = rr.ar_no
                    LEFT JOIN base_customer cs ON cs.customer_code = rr.counterparty_code
                    WHERE rr.counterparty_type = 'CUSTOMER'
                      AND rr.receipt_date BETWEEN ? AND ?
                      AND COALESCE(fa.salesman, cs.salesman) IS NOT NULL
                    GROUP BY COALESCE(fa.salesman, cs.salesman)
                ) pay ON pay.salesman = d.salesman
                LEFT JOIN (
                    -- 签收生成应收时 fin_ar.salesman 历史落 NULL（SalesReceiptController 硬编码），
                    -- 与回款派生表对称：业务员归属优先应收单行，兜底客户档案负责人，否则该客户
                    -- 应收永远挂不到业务员，#13 应收余额整列为 0。
                    SELECT COALESCE(a.salesman, cs.salesman) AS salesman,
                           SUM(a.unreceived_amount) AS ar_balance,
                           SUM(CASE WHEN a.due_date < CURRENT_DATE AND a.unreceived_amount > 0
                                    THEN a.unreceived_amount ELSE 0 END) AS overdue_amount
                    FROM fin_ar a
                    LEFT JOIN base_customer cs ON cs.customer_name = a.customer
                    WHERE COALESCE(a.salesman, cs.salesman) IS NOT NULL
                    GROUP BY COALESCE(a.salesman, cs.salesman)
                ) ar ON ar.salesman = d.salesman
                WHERE 1=1
                """);
        plan.args.add(req.range().startDate());
        plan.args.add(req.range().endDate());
        plan.args.add(req.range().startDate());
        plan.args.add(req.range().endDate());
        plan.args.add(req.range().startDate());
        plan.args.add(req.range().endDate());
        inner.append(" AND d.bill_date BETWEEN ? AND ? ");

        appendFilters(req, inner, plan.args);
        scope.appendTo(inner, plan.args);
        inner.append("""
                 GROUP BY d.salesman, nc.new_customer_count, pay.received_amount,
                          ar.ar_balance, ar.overdue_amount
                """);

        // 外层补部门/上级，重算毛利率/客单价/回款率
        plan.detailSelect = """
                SELECT t.salesman AS salesman,
                       COALESCE(e.department, '') AS department,
                       COALESCE(e.parent_salesman, '') AS parent_salesman,
                       t.receipt_count AS receipt_count,
                       t.customer_count AS customer_count,
                       t.new_customer_count AS new_customer_count,
                       t.signed_amount AS signed_amount,
                       t.return_amount AS return_amount,
                       t.net_amount AS net_amount,
                       t.net_qty_base AS net_qty_base,
                       t.cost_amount AS cost_amount,
                       t.gross_profit AS gross_profit,
                       CASE WHEN t.net_amount = 0 THEN NULL
                            ELSE t.gross_profit / t.net_amount END AS gross_profit_rate,
                       CASE WHEN t.receipt_count = 0 THEN NULL
                            ELSE t.net_amount / t.receipt_count END AS avg_order_amount,
                       t.received_amount AS received_amount,
                       CASE WHEN t.signed_amount = 0 THEN NULL
                            ELSE t.received_amount / t.signed_amount END AS receive_rate,
                       t.ar_balance AS ar_balance,
                       t.overdue_amount AS overdue_amount
                FROM (""";
        plan.fromWhere.append(inner)
                .append(") t LEFT JOIN base_employee e ON e.employee_name = t.salesman");

        String department = req.text("department");
        if (department != null) {
            plan.fromWhere.append(" AND e.department = ? ");
            plan.args.add(department);
        }
        plan.defaultOrder = "ORDER BY t.net_amount DESC, t.salesman ASC";
        plan.sortWhitelist.putAll(Map.ofEntries(
                Map.entry("salesman", "salesman"),
                Map.entry("receiptCount", "receipt_count"),
                Map.entry("customerCount", "customer_count"),
                Map.entry("newCustomerCount", "new_customer_count"),
                Map.entry("signedAmount", "signed_amount"),
                Map.entry("returnAmount", "return_amount"),
                Map.entry("netAmount", "net_amount"),
                Map.entry("costAmount", "cost_amount"),
                Map.entry("grossProfit", "gross_profit"),
                Map.entry("avgOrderAmount", "avg_order_amount"),
                Map.entry("receivedAmount", "received_amount"),
                Map.entry("receiveRate", "receive_rate"),
                Map.entry("arBalance", "ar_balance"),
                Map.entry("overdueAmount", "overdue_amount")));
        plan.maskOverrides.putAll(Map.ofEntries(
                Map.entry("signedAmount", "VIEW_SALE_AMOUNT"),
                Map.entry("returnAmount", "VIEW_SALE_AMOUNT"),
                Map.entry("netAmount", "VIEW_SALE_AMOUNT"),
                Map.entry("costAmount", "VIEW_COST_AMOUNT"),
                Map.entry("grossProfit", "VIEW_PROFIT"),
                Map.entry("grossProfitRate", "VIEW_PROFIT"),
                Map.entry("avgOrderAmount", "VIEW_SALE_AMOUNT"),
                Map.entry("receivedAmount", "VIEW_AR_BALANCE"),
                Map.entry("receiveRate", "VIEW_AR_BALANCE"),
                Map.entry("arBalance", "VIEW_AR_BALANCE"),
                Map.entry("overdueAmount", "VIEW_AR_BALANCE")));

        // 合计：客户数/新客户数按业务员行可加（新客户归属唯一）；比率按总量重算
        String leafSql = plan.detailSelect + " " + plan.fromWhere;
        plan.grandSql = """
                SELECT COALESCE(SUM(g.receipt_count),0) AS receipt_count,
                       COALESCE(SUM(g.customer_count),0) AS customer_count,
                       COALESCE(SUM(g.new_customer_count),0) AS new_customer_count,
                       COALESCE(SUM(g.signed_amount),0) AS signed_amount,
                       COALESCE(SUM(g.return_amount),0) AS return_amount,
                       COALESCE(SUM(g.net_amount),0) AS net_amount,
                       COALESCE(SUM(g.net_qty_base),0) AS net_qty_base,
                       COALESCE(SUM(g.cost_amount),0) AS cost_amount,
                       COALESCE(SUM(g.gross_profit),0) AS gross_profit,
                       COALESCE(SUM(g.gross_profit),0) / NULLIF(SUM(g.net_amount),0) AS gross_profit_rate,
                       COALESCE(SUM(g.net_amount),0) / NULLIF(SUM(g.receipt_count),0) AS avg_order_amount,
                       COALESCE(SUM(g.received_amount),0) AS received_amount,
                       COALESCE(SUM(g.received_amount),0) / NULLIF(SUM(g.signed_amount),0) AS receive_rate,
                       COALESCE(SUM(g.ar_balance),0) AS ar_balance,
                       COALESCE(SUM(g.overdue_amount),0) AS overdue_amount
                FROM (%s) g
                """.formatted(leafSql);
        plan.grandArgs.addAll(plan.args);
        return plan;
    }

    private void appendFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args) {
        String salesman = req.text("salesman");
        if (salesman != null) {
            sql.append(" AND d.salesman LIKE ? ");
            args.add("%" + salesman + "%");
        }
        String territory = req.text("territory");
        if (territory != null) {
            sql.append(" AND d.territory = ? ");
            args.add(territory);
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append(" AND d.warehouse = ? ");
            args.add(warehouse);
        }
    }
}
