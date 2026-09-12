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
 * 报表12｜客户销售汇总表。粒度：客户（期间有签收/退货即行）。
 *
 * <p>销售指标取头粒度 DWS rpt_dws_sales_bill_d（签收单数=单据计数，客单价=净销售额÷签收单数，
 * 客户数占比用窗口函数按总净额摊）；本期回款实时聚合 fin_reconcile_record
 * （counterparty_type=CUSTOMER，按收款核销日期）；期末应收/逾期实时聚合 fin_ar
 * （应收以客户名称登记，经 base_customer 名称→编码映射）。回款/应收是实时值，
 * 不进 DWS：本期发生走 DWS、期末时点数走账龄表，两本数各自新鲜。
 *
 * <p>组小计（业务员/区域/等级）由前端对客户叶子行实时汇总；钻取：客户行 → #11，
 * 应收余额 → #20/#23。
 */
@Component
public class CustomerSummaryDefinition implements ReportDefinition {

    private final DataScopeService dataScope;

    public CustomerSummaryDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "customer_summary"; }
    @Override public String name() { return "客户销售汇总表"; }
    @Override public String viewPerm() { return "report.customer_summary.view"; }
    @Override public boolean dws() { return true; }
    @Override public boolean summaryReport() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        List<ReportColumnDef> cols = new ArrayList<>(List.of(
                ReportColumnDef.dim("customerCode", "客户编号"),
                ReportColumnDef.dim("customerName", "客户名称"),
                ReportColumnDef.dim("customerLevel", "客户等级"),
                ReportColumnDef.dim("territory", "区域"),
                ReportColumnDef.dim("routeLine", "路线"),
                ReportColumnDef.dim("salesman", "业务员"),
                ReportColumnDef.measure("receiptCount", "签收单数", null),
                ReportColumnDef.measure("signedAmount", "销售金额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("returnAmount", "退货金额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("netAmount", "净销售额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("netQtyBase", "净销售数量(小单位)", null),
                ReportColumnDef.measure("costAmount", "成本金额", "VIEW_COST_AMOUNT"),
                ReportColumnDef.measure("grossProfit", "毛利额", "VIEW_PROFIT"),
                ReportColumnDef.measure("grossProfitRate", "毛利率", "VIEW_PROFIT"),
                ReportColumnDef.measure("customerShare", "客户数占比", null),
                ReportColumnDef.measure("avgOrderAmount", "客单价", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("receivedAmount", "本期回款额", "VIEW_AR_BALANCE"),
                ReportColumnDef.measure("arBalance", "期末应收余额", "VIEW_AR_BALANCE"),
                ReportColumnDef.measure("overdueAmount", "其中逾期", "VIEW_AR_BALANCE")));
        return cols;
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        var scope = dataScope.target()
                .warehouse("d.warehouse").customer("d.customer_name").salesman("d.salesman")
                .build();
        if (scope.isDenyAll()) return plan.denyAll();

        // 内层：头 DWS 按客户聚合 + 回款/应收两个实时派生表（先聚合再 JOIN，JOIN 行数=客户数）
        StringBuilder inner = new StringBuilder("""
                SELECT d.customer_code AS customer_code,
                       MAX(d.customer_name) AS customer_name,
                       MAX(d.customer_level) AS customer_level,
                       MAX(d.territory) AS territory,
                       MAX(d.route_line) AS route_line,
                       MAX(d.salesman) AS salesman,
                       SUM(CASE WHEN d.bill_type = 'SIGN' THEN 1 ELSE 0 END) AS receipt_count,
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
                    SELECT r.counterparty_code AS customer_code,
                           SUM(r.reconcile_amount) AS received_amount
                    FROM fin_reconcile_record r
                    WHERE r.counterparty_type = 'CUSTOMER'
                      AND r.receipt_date BETWEEN ? AND ?
                    GROUP BY r.counterparty_code
                ) pay ON pay.customer_code = d.customer_code
                LEFT JOIN (
                    SELECT c.customer_code AS customer_code,
                           SUM(a.unreceived_amount) AS ar_balance,
                           SUM(CASE WHEN a.due_date < CURRENT_DATE AND a.unreceived_amount > 0
                                    THEN a.unreceived_amount ELSE 0 END) AS overdue_amount
                    FROM fin_ar a
                    JOIN base_customer c ON c.customer_name = a.customer
                    GROUP BY c.customer_code
                ) ar ON ar.customer_code = d.customer_code
                WHERE 1=1
                """);
        // 回款派生表日期参数最早出现
        plan.args.add(req.range().startDate());
        plan.args.add(req.range().endDate());
        plan.args.add(req.range().startDate());
        plan.args.add(req.range().endDate());
        inner.append(" AND d.bill_date BETWEEN ? AND ? ");

        appendFilters(req, inner, plan.args);
        scope.appendTo(inner, plan.args);
        inner.append("""
                 GROUP BY d.customer_code, pay.received_amount, ar.ar_balance, ar.overdue_amount
                """);

        // 外层：毛利率/客单价/占比（窗口按总净额摊）
        plan.detailSelect = """
                SELECT t.customer_code AS customer_code, t.customer_name AS customer_name,
                       t.customer_level AS customer_level, t.territory AS territory,
                       t.route_line AS route_line, t.salesman AS salesman,
                       t.receipt_count AS receipt_count,
                       t.signed_amount AS signed_amount, t.return_amount AS return_amount,
                       t.net_amount AS net_amount, t.net_qty_base AS net_qty_base,
                       t.cost_amount AS cost_amount, t.gross_profit AS gross_profit,
                       CASE WHEN t.net_amount = 0 THEN NULL
                            ELSE t.gross_profit / t.net_amount END AS gross_profit_rate,
                       CASE WHEN SUM(t.net_amount) OVER () = 0 THEN 0
                            ELSE t.net_amount / SUM(t.net_amount) OVER () END AS customer_share,
                       CASE WHEN t.receipt_count = 0 THEN NULL
                            ELSE t.net_amount / t.receipt_count END AS avg_order_amount,
                       t.received_amount AS received_amount,
                       t.ar_balance AS ar_balance, t.overdue_amount AS overdue_amount
                FROM (""";

        plan.fromWhere.append(inner).append(") t");
        plan.defaultOrder = "ORDER BY t.net_amount DESC, t.customer_code ASC";
        plan.sortWhitelist.putAll(Map.ofEntries(
                Map.entry("customerCode", "customer_code"),
                Map.entry("customerName", "customer_name"),
                Map.entry("receiptCount", "receipt_count"),
                Map.entry("signedAmount", "signed_amount"),
                Map.entry("returnAmount", "return_amount"),
                Map.entry("netAmount", "net_amount"),
                Map.entry("costAmount", "cost_amount"),
                Map.entry("grossProfit", "gross_profit"),
                Map.entry("avgOrderAmount", "avg_order_amount"),
                Map.entry("receivedAmount", "received_amount"),
                Map.entry("arBalance", "ar_balance"),
                Map.entry("overdueAmount", "overdue_amount")));
        plan.maskOverrides.putAll(Map.of(
                "signedAmount", "VIEW_SALE_AMOUNT",
                "returnAmount", "VIEW_SALE_AMOUNT",
                "netAmount", "VIEW_SALE_AMOUNT",
                "costAmount", "VIEW_COST_AMOUNT",
                "grossProfit", "VIEW_PROFIT",
                "grossProfitRate", "VIEW_PROFIT",
                "avgOrderAmount", "VIEW_SALE_AMOUNT",
                "receivedAmount", "VIEW_AR_BALANCE",
                "arBalance", "VIEW_AR_BALANCE",
                "overdueAmount", "VIEW_AR_BALANCE"));

        // 合计：金额/单数可加；客单价=总净额÷总签收单数；毛利率=总毛利÷总净额；占比不给
        String leafSql = plan.detailSelect + " " + plan.fromWhere;
        plan.grandSql = """
                SELECT COALESCE(SUM(g.receipt_count),0) AS receipt_count,
                       COALESCE(SUM(g.signed_amount),0) AS signed_amount,
                       COALESCE(SUM(g.return_amount),0) AS return_amount,
                       COALESCE(SUM(g.net_amount),0) AS net_amount,
                       COALESCE(SUM(g.net_qty_base),0) AS net_qty_base,
                       COALESCE(SUM(g.cost_amount),0) AS cost_amount,
                       COALESCE(SUM(g.gross_profit),0) AS gross_profit,
                       COALESCE(SUM(g.gross_profit),0) / NULLIF(SUM(g.net_amount),0) AS gross_profit_rate,
                       COALESCE(SUM(g.net_amount),0) / NULLIF(SUM(g.receipt_count),0) AS avg_order_amount,
                       COALESCE(SUM(g.received_amount),0) AS received_amount,
                       COALESCE(SUM(g.ar_balance),0) AS ar_balance,
                       COALESCE(SUM(g.overdue_amount),0) AS overdue_amount
                FROM (%s) g
                """.formatted(leafSql);
        plan.grandArgs.addAll(plan.args);
        return plan;
    }

    private void appendFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args) {
        String customer = req.text("customer");
        if (customer != null) {
            sql.append(" AND (d.customer_code LIKE ? OR d.customer_name LIKE ?) ");
            args.add("%" + customer + "%");
            args.add("%" + customer + "%");
        }
        appendEq(req, sql, args, "customerLevel", "d.customer_level");
        appendEq(req, sql, args, "territory", "d.territory");
        appendEq(req, sql, args, "routeLine", "d.route_line");
        String salesman = req.text("salesman");
        if (salesman != null) {
            sql.append(" AND d.salesman LIKE ? ");
            args.add("%" + salesman + "%");
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append(" AND d.warehouse = ? ");
            args.add(warehouse);
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
