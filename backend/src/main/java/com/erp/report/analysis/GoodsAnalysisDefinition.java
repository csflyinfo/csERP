package com.erp.report.analysis;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.common.security.datascope.DataScopeService.ScopeClause;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报表17｜商品综合分析——明细表（KPI/趋势/结构图由 {@code GoodsAnalysisService} 专用端点供给）。
 *
 * <p>每行一个商品（level=warehouse 时商品+仓），一页看全购销存：
 * 期间采购数量/金额（rpt_dws_purchase_d 入库净额）、签收数量/金额/客户数/签收单数
 * （rpt_dws_sales_d + DWD 视图计单）、配比成本、毛利、期末库存（最近一天日结快照）、可销天数。
 * 金额全部含税；成本 VIEW_COST_AMOUNT、毛利 VIEW_PROFIT、库存金额 VIEW_STOCK_COST 分码脱敏。
 */
@Component
public class GoodsAnalysisDefinition implements ReportDefinition {

    private final DataScopeService dataScope;

    public GoodsAnalysisDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "goods_analysis"; }
    @Override public String name() { return "商品综合分析"; }
    @Override public String viewPerm() { return "report.goods_analysis.view"; }
    @Override public boolean dws() { return true; }
    @Override public boolean summaryReport() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        return List.of(
                ReportColumnDef.dim("goodsCode", "商品编号"),
                ReportColumnDef.dim("goodsName", "商品名称"),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("categoryName", "商品类别"),
                ReportColumnDef.dim("storageProperty", "存储属性"),
                ReportColumnDef.dim("baseUnit", "单位"),
                ReportColumnDef.dim("warehouse", "仓库"),
                ReportColumnDef.measure("purchaseQty", "采购数量", null),
                ReportColumnDef.measure("purchaseAmount", "采购金额(入库净额)", "VIEW_PURCHASE_AMOUNT"),
                ReportColumnDef.measure("signedQty", "签收数量", null),
                ReportColumnDef.measure("signedAmount", "签收金额(含税)", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("customerCount", "客户数", null),
                ReportColumnDef.measure("billCount", "签收单数", null),
                ReportColumnDef.measure("costAmount", "配比成本", "VIEW_COST_AMOUNT"),
                ReportColumnDef.measure("grossProfit", "毛利额", "VIEW_PROFIT"),
                ReportColumnDef.measure("grossProfitRate", "毛利率", "VIEW_PROFIT"),
                ReportColumnDef.measure("endQty", "期末库存数量", null),
                ReportColumnDef.measure("endAmount", "期末库存金额", "VIEW_STOCK_COST"),
                ReportColumnDef.measure("coverDays", "可销天数", null));
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        ScopeClause scope = dataScope.target()
                .warehouse("g.warehouse").goodsColumn("g.goods_code").build();
        if (scope.isDenyAll()) return plan.denyAll();

        var start = req.range().startDate();
        var end = req.range().endDate();
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        boolean goodsOnly = !"warehouse".equals(req.text("level"));
        String whExpr = goodsOnly ? "CAST('' AS VARCHAR(100))" : "g.warehouse";
        String groupCols = goodsOnly ? "g.goods_code" : "g.goods_code, g.warehouse";

        StringBuilder leaf = new StringBuilder("""
                SELECT g.goods_code AS goods_code,
                       MAX(dg.goods_name) AS goods_name,
                       MAX(dg.brand_name) AS brand_name,
                       MAX(dg.category_name) AS category_name,
                       MAX(dg.storage_property) AS storage_property,
                       MAX(dg.base_unit) AS base_unit,
                       %s AS warehouse,
                       COALESCE(SUM(g.purchase_qty), 0) AS purchase_qty,
                       COALESCE(SUM(g.purchase_amount), 0) AS purchase_amount,
                       COALESCE(SUM(g.signed_qty), 0) AS signed_qty,
                       COALESCE(SUM(g.signed_amount), 0) AS signed_amount,
                       COALESCE(SUM(g.cost_amount), 0) AS cost_amount,
                       COALESCE(SUM(cc.customer_count), 0) AS customer_count,
                       COALESCE(SUM(bc.bill_count), 0) AS bill_count,
                       MAX(g.end_qty) AS end_qty,
                       MAX(g.end_amount) AS end_amount,
                       MAX(g.avg_daily) AS avg_daily
                  FROM (
                """.formatted(whExpr));
        // 键集：销售 DWS ∪ 采购 DWS ∪ 期末快照（≤ end 最近一天）
        leaf.append("""
                    SELECT goods_code, warehouse,
                           SUM(purchase_qty) AS purchase_qty, SUM(purchase_amount) AS purchase_amount,
                           SUM(signed_qty) AS signed_qty, SUM(signed_amount) AS signed_amount,
                           SUM(cost_amount) AS cost_amount,
                           MAX(end_qty) AS end_qty, MAX(end_amount) AS end_amount,
                           MAX(avg_daily) AS avg_daily
                      FROM (
                        SELECT goods_code, warehouse,
                               0 AS purchase_qty, 0 AS purchase_amount,
                               signed_qty_base - COALESCE(return_qty_base,0) AS signed_qty,
                               signed_amount - COALESCE(return_amount,0) AS signed_amount,
                               signed_cost_amount - COALESCE(return_cost_amount,0) AS cost_amount,
                               0 AS end_qty, 0 AS end_amount, 0 AS avg_daily
                          FROM rpt_dws_sales_d
                         WHERE bill_date BETWEEN ? AND ?
                        UNION ALL
                        SELECT goods_code, warehouse,
                               inbound_qty_base - COALESCE(return_qty_base,0),
                               inbound_amount - COALESCE(return_amount,0),
                               0, 0, 0, 0, 0, 0
                          FROM rpt_dws_purchase_d
                         WHERE bill_date BETWEEN ? AND ?
                        UNION ALL
                        SELECT s.goods_code, s.warehouse, 0,0,0,0,0,
                               s.physical_qty, s.stock_amount, s.avg_daily_sales
                          FROM inv_stock_daily_snapshot s
                         WHERE s.snapshot_date = (
                               SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot
                                WHERE snapshot_date <= ?)
                    ) z
                     GROUP BY goods_code, warehouse
                """);
        List<Object> args = new ArrayList<>();
        args.add(start); args.add(end);
        args.add(start); args.add(end);
        args.add(end);
        leaf.append(") g ");
        leaf.append("""
                  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = g.goods_code
                  LEFT JOIN (
                      SELECT goods_code, warehouse,
                             COUNT(DISTINCT customer_code) AS customer_count
                        FROM rpt_dws_sales_d
                       WHERE bill_date BETWEEN ? AND ?
                       GROUP BY goods_code, warehouse
                  ) cc ON cc.goods_code = g.goods_code AND cc.warehouse = g.warehouse
                  LEFT JOIN (
                      SELECT goods_code, warehouse, COUNT(DISTINCT bill_no) AS bill_count
                        FROM v_rpt_sales_detail
                       WHERE bill_date BETWEEN ? AND ? AND amount > 0
                       GROUP BY goods_code, warehouse
                  ) bc ON bc.goods_code = g.goods_code AND bc.warehouse = g.warehouse
                 WHERE 1=1
                """);
        args.add(start); args.add(end);
        args.add(start); args.add(end);
        appendFilters(req, leaf, args);
        scope.appendTo(leaf, args);
        leaf.append(" GROUP BY ").append(groupCols).append(" ");

        plan.detailSelect = """
                SELECT t.goods_code AS goods_code, t.goods_name AS goods_name,
                       t.brand_name AS brand_name, t.category_name AS category_name,
                       t.storage_property AS storage_property, t.base_unit AS base_unit,
                       t.warehouse AS warehouse,
                       t.purchase_qty AS purchase_qty, t.purchase_amount AS purchase_amount,
                       t.signed_qty AS signed_qty, t.signed_amount AS signed_amount,
                       COALESCE(t.customer_count, 0) AS customer_count,
                       COALESCE(t.bill_count, 0) AS bill_count,
                       t.cost_amount AS cost_amount,
                       t.signed_amount - t.cost_amount AS gross_profit,
                       CASE WHEN t.signed_amount > 0
                            THEN (t.signed_amount - t.cost_amount) / t.signed_amount
                            END AS gross_profit_rate,
                       t.end_qty AS end_qty, t.end_amount AS end_amount,
                       CASE WHEN t.avg_daily > 0 THEN ROUND(t.end_qty / t.avg_daily, 1)
                            END AS cover_days
                  FROM (""";
        // 明细 SQL 需把 cc/bc 计数带进叶子（goodsOnly 跨仓时取最大仓计数不严谨，用 SUM 合入键集更准）
        plan.fromWhere.append(leaf).append(") t WHERE 1=1 ");
        if (!"1".equals(req.text("includeZero"))) {
            plan.fromWhere.append("""
                     AND (ABS(t.signed_amount) + ABS(t.purchase_amount) + ABS(t.end_amount) > 0)
                    """);
        }
        plan.args.addAll(args);
        plan.defaultOrder = "ORDER BY t.signed_amount DESC, t.goods_code ASC";
        plan.sortWhitelist.putAll(Map.of(
                "purchaseAmount", "purchase_amount",
                "signedAmount", "signed_amount",
                "signedQty", "signed_qty",
                "grossProfit", "gross_profit",
                "grossProfitRate", "gross_profit_rate",
                "endAmount", "end_amount",
                "coverDays", "cover_days"));
        plan.maskOverrides.putAll(Map.of(
                "purchaseAmount", "VIEW_PURCHASE_AMOUNT",
                "signedAmount", "VIEW_SALE_AMOUNT",
                "costAmount", "VIEW_COST_AMOUNT",
                "grossProfit", "VIEW_PROFIT",
                "grossProfitRate", "VIEW_PROFIT",
                "endAmount", "VIEW_STOCK_COST"));
        plan.grandSql = """
                SELECT COALESCE(SUM(x.purchase_qty),0) AS purchase_qty,
                       COALESCE(SUM(x.purchase_amount),0) AS purchase_amount,
                       COALESCE(SUM(x.signed_qty),0) AS signed_qty,
                       COALESCE(SUM(x.signed_amount),0) AS signed_amount,
                       COALESCE(SUM(x.customer_count),0) AS customer_count,
                       COALESCE(SUM(x.bill_count),0) AS bill_count,
                       COALESCE(SUM(x.cost_amount),0) AS cost_amount,
                       COALESCE(SUM(x.signed_amount - x.cost_amount),0) AS gross_profit,
                       CASE WHEN SUM(x.signed_amount) > 0
                            THEN SUM(x.signed_amount - x.cost_amount) / SUM(x.signed_amount)
                            END AS gross_profit_rate,
                       COALESCE(SUM(x.end_qty),0) AS end_qty,
                       COALESCE(SUM(x.end_amount),0) AS end_amount,
                       %d AS period_days
                FROM (%s %s) x
                """.formatted(days, plan.detailSelect, plan.fromWhere);
        plan.grandArgs.addAll(args);
        return plan;
    }

    private void appendFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args) {
        String goods = req.text("goods");
        if (goods != null) {
            sql.append(" AND (g.goods_code LIKE ? OR dg.goods_name LIKE ? OR dg.barcode LIKE ?) ");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
        }
        for (var e : Map.of(
                "categoryName", "dg.category_name",
                "brandName", "dg.brand_name",
                "storageProperty", "dg.storage_property").entrySet()) {
            String v = req.text(e.getKey());
            if (v != null) {
                sql.append(" AND ").append(e.getValue()).append(" = ? ");
                args.add(v);
            }
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append(" AND g.warehouse = ? ");
            args.add(warehouse);
        }
    }
}
