package com.erp.report.inventory;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.common.security.datascope.DataScopeService.ScopeClause;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import com.erp.system.SysParamService;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报表7｜商品周转率分析。
 *
 * <p>周转率（次）= 期间销售成本（K8 签收配比成本：出库成本 − 拒收回库 − 销售退货成本，
 * 按签收日落期间）÷ 平均库存成本；平均库存成本 =（期初金额 + 期末金额）÷ 2；
 * 周转天数 = 期间日历天数 ÷ 周转率。
 *
 * <p>取数（元数据层复用，不重复统计）：
 * <ul>
 *   <li>期初/期末数量与金额：inv_stock_daily_snapshot 日结快照，期初取 startDate-1、
 *       期末取 endDate（Q6：测试/预发同样跑日结，无流水倒推开关）；</li>
 *   <li>期间销售成本/数量/金额：rpt_dws_sales_d 签收口径 DWS（正数签收 − 退货）；</li>
 *   <li>本期入库金额：rpt_dws_stock_move_d（与 #8 一致排除成本调整 CGSH）。</li>
 * </ul>
 * 呆滞标记 = 期末有库存且（零周转 或 周转天数 &gt; 参数 P0183 REPORT_SLOW_TURNOVER_DAYS，
 * 默认 60 天）；默认排序周转率升序（最慢在前）。合计周转率为加权值（总成本÷总平均库存）。
 * 库存金额 VIEW_STOCK_COST、销售成本 VIEW_COST_AMOUNT、毛利 VIEW_PROFIT 分码脱敏。
 */
@Component
public class GoodsTurnoverDefinition implements ReportDefinition {

    private final DataScopeService dataScope;
    private final SysParamService sysParamService;

    public GoodsTurnoverDefinition(DataScopeService dataScope, SysParamService sysParamService) {
        this.dataScope = dataScope;
        this.sysParamService = sysParamService;
    }

    @Override public String code() { return "goods_turnover"; }
    @Override public String name() { return "商品周转率分析"; }
    @Override public String viewPerm() { return "report.goods_turnover.view"; }
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
                ReportColumnDef.measure("openQty", "期初数量", null),
                ReportColumnDef.measure("openAmount", "期初成本金额", "VIEW_STOCK_COST"),
                ReportColumnDef.measure("inAmount", "本期入库金额", "VIEW_STOCK_COST"),
                ReportColumnDef.measure("salesCost", "本期销售成本", "VIEW_COST_AMOUNT"),
                ReportColumnDef.measure("closeQty", "期末数量", null),
                ReportColumnDef.measure("closeAmount", "期末成本金额", "VIEW_STOCK_COST"),
                ReportColumnDef.measure("avgStockAmount", "平均库存金额", "VIEW_STOCK_COST"),
                ReportColumnDef.measure("turnoverRate", "周转率(次)", null),
                ReportColumnDef.measure("turnoverDays", "周转天数", null),
                ReportColumnDef.measure("salesAmount", "期间销售额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("grossMarginRate", "毛利率", "VIEW_PROFIT"),
                ReportColumnDef.dim("slowFlag", "呆滞标记"));
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        int slowDays = sysParamService.getInt("REPORT_SLOW_TURNOVER_DAYS", 60, 1, 3650);

        // 范围条件挂在键集子查询内部的 g 别名（WHERE 在子查询内，k 是外层聚合别名）
        ScopeClause scope = dataScope.target()
                .warehouse("g.warehouse").goodsColumn("g.goods_code").build();
        if (scope.isDenyAll()) return plan.denyAll();

        var start = req.range().startDate();
        var end = req.range().endDate();
        var openDate = start.minusDays(1);
        long calendarDays = ChronoUnit.DAYS.between(start, end) + 1;
        // 汇总粒度：默认商品+仓库；level=goods 时跨仓汇总
        boolean goodsOnly = "goods".equals(req.text("level"));
        String grainSelect = goodsOnly
                ? "g.goods_code AS goods_code, CAST('' AS VARCHAR(100)) AS warehouse "
                : "g.goods_code AS goods_code, g.warehouse AS warehouse ";
        String grainGroup = goodsOnly ? "g.goods_code" : "g.goods_code, g.warehouse";

        StringBuilder leaf = new StringBuilder("""
                SELECT k.goods_code AS goods_code,
                       MAX(dg.goods_name) AS goods_name,
                       MAX(dg.brand_name) AS brand_name,
                       MAX(dg.category_name) AS category_name,
                       MAX(dg.storage_property) AS storage_property,
                       MAX(dg.base_unit) AS base_unit,
                       k.warehouse AS warehouse,
                       COALESCE(SUM(k.open_qty), 0) AS open_qty,
                       COALESCE(SUM(k.open_amount), 0) AS open_amount,
                       COALESCE(SUM(k.in_amount), 0) AS in_amount,
                       COALESCE(SUM(k.sales_cost), 0) AS sales_cost,
                       COALESCE(SUM(k.close_qty), 0) AS close_qty,
                       COALESCE(SUM(k.close_amount), 0) AS close_amount,
                       COALESCE(SUM(k.sales_amount), 0) AS sales_amount,
                       (COALESCE(SUM(k.open_amount), 0) + COALESCE(SUM(k.close_amount), 0)) / 2.0
                           AS avg_stock_amount
                  FROM (
                """).append("SELECT ").append(grainSelect).append("""
                     ,
                       COALESCE(op.qty, 0) AS open_qty, COALESCE(op.amount, 0) AS open_amount,
                       COALESCE(cl.qty, 0) AS close_qty, COALESCE(cl.amount, 0) AS close_amount,
                       COALESCE(sa.signed_qty, 0) - COALESCE(sa.return_qty, 0) AS sales_qty,
                       COALESCE(sa.signed_amount, 0) - COALESCE(sa.return_amount, 0) AS sales_amount,
                       COALESCE(sa.signed_cost, 0) - COALESCE(sa.return_cost, 0) AS sales_cost,
                       COALESCE(mv.in_amount, 0) AS in_amount
                  FROM (
                """);
        // 键集：期间快照 ∪ 期间销售 DWS ∪ 入库流水 DWS，避免漏「无库存但有销售」商品
        leaf.append("""
                        SELECT goods_code, warehouse FROM inv_stock_daily_snapshot
                         WHERE snapshot_date IN (?, ?)
                        UNION
                        SELECT goods_code, warehouse FROM rpt_dws_sales_d
                         WHERE bill_date BETWEEN ? AND ?
                        UNION
                        SELECT goods_code, warehouse FROM rpt_dws_stock_move_d
                         WHERE move_date BETWEEN ? AND ?
                    ) g
                    LEFT JOIN (
                        SELECT goods_code, warehouse,
                               SUM(physical_qty) AS qty, SUM(stock_amount) AS amount
                          FROM inv_stock_daily_snapshot WHERE snapshot_date = ?
                         GROUP BY goods_code, warehouse
                    ) op ON op.goods_code = g.goods_code AND op.warehouse = g.warehouse
                    LEFT JOIN (
                        SELECT goods_code, warehouse,
                               SUM(physical_qty) AS qty, SUM(stock_amount) AS amount
                          FROM inv_stock_daily_snapshot WHERE snapshot_date = ?
                         GROUP BY goods_code, warehouse
                    ) cl ON cl.goods_code = g.goods_code AND cl.warehouse = g.warehouse
                    LEFT JOIN (
                        SELECT goods_code, warehouse,
                               SUM(signed_qty_base) AS signed_qty,
                               SUM(return_qty_base) AS return_qty,
                               SUM(signed_amount) AS signed_amount,
                               SUM(return_amount) AS return_amount,
                               SUM(signed_cost_amount) AS signed_cost,
                               SUM(return_cost_amount) AS return_cost
                          FROM rpt_dws_sales_d WHERE bill_date BETWEEN ? AND ?
                         GROUP BY goods_code, warehouse
                    ) sa ON sa.goods_code = g.goods_code AND sa.warehouse = g.warehouse
                    LEFT JOIN (
                        SELECT goods_code, warehouse,
                               SUM(CASE WHEN bill_type_code <> 'CGSH' THEN in_amount ELSE 0 END) AS in_amount
                          FROM rpt_dws_stock_move_d WHERE move_date BETWEEN ? AND ?
                         GROUP BY goods_code, warehouse
                    ) mv ON mv.goods_code = g.goods_code AND mv.warehouse = g.warehouse
                """);
        // 商品档案（品牌/分类/温区/单位）
        leaf.append("""
                    LEFT JOIN rpt_dim_goods dg ON dg.goods_code = g.goods_code
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        // 键集 UNION 参数
        args.add(openDate); args.add(end);
        args.add(start); args.add(end);
        args.add(start); args.add(end);
        // 快照 op/cl
        args.add(openDate);
        args.add(end);
        // 销售 DWS
        args.add(start); args.add(end);
        // 入库 DWS
        args.add(start); args.add(end);

        appendFilters(req, leaf, args);
        scope.appendTo(leaf, args);
        // 内层 dg 仅供 WHERE 档案筛选；外层 k 再 JOIN 一次供 SELECT 聚合
        leaf.append(") k LEFT JOIN rpt_dim_goods dg ON dg.goods_code = k.goods_code ")
            .append("GROUP BY ").append(grainGroup.replace("g.", "k.")).append(" ");

        // 外层：周转率/天数/毛利率/呆滞标记 + HAVING 过滤
        plan.detailSelect = """
                SELECT t.goods_code AS goods_code, t.goods_name AS goods_name,
                       t.brand_name AS brand_name, t.category_name AS category_name,
                       t.storage_property AS storage_property, t.base_unit AS base_unit,
                       t.warehouse AS warehouse,
                       t.open_qty AS open_qty, t.open_amount AS open_amount,
                       t.in_amount AS in_amount, t.sales_cost AS sales_cost,
                       t.close_qty AS close_qty, t.close_amount AS close_amount,
                       t.sales_amount AS sales_amount, t.avg_stock_amount AS avg_stock_amount,
                       CASE WHEN t.avg_stock_amount > 0
                            THEN t.sales_cost / t.avg_stock_amount END AS turnover_rate,
                       CASE WHEN t.sales_cost > 0 AND t.avg_stock_amount > 0
                            THEN %d * t.avg_stock_amount / t.sales_cost END AS turnover_days,
                       CASE WHEN t.sales_amount > 0
                            THEN (t.sales_amount - t.sales_cost) / t.sales_amount
                            END AS gross_margin_rate,
                       CASE WHEN t.close_qty > 0
                                 AND (t.sales_cost = 0
                                      OR (t.avg_stock_amount > 0 AND %d * t.avg_stock_amount / t.sales_cost > %d))
                            THEN '呆滞' ELSE '' END AS slow_flag
                  FROM (""".formatted(calendarDays, calendarDays, slowDays);
        plan.fromWhere.append(leaf).append(") t WHERE 1=1 ");
        if (!"1".equals(req.text("includeZero"))) {
            // 默认只留有库存/有发生的行（全零键集无意义）
            plan.fromWhere.append("""
                     AND (ABS(t.open_qty) + ABS(t.close_qty) + ABS(t.sales_amount)
                          + ABS(t.in_amount) + ABS(t.sales_cost) > 0)
                    """);
        }
        if ("1".equals(req.text("slowOnly"))) {
            plan.fromWhere.append("""
                     AND t.close_qty > 0
                     AND (t.sales_cost = 0
                          OR (t.avg_stock_amount > 0
                              AND %d * t.avg_stock_amount / t.sales_cost > %d))
                    """.formatted(calendarDays, slowDays));
        }
        plan.args.addAll(args);
        // 默认周转率升序：NULL（零周转且有库存=呆滞）排最后（H2 支持 NULLS LAST；
        // ORDER BY 表达式里不能引用输出别名，故不用 CASE）
        plan.defaultOrder = "ORDER BY turnover_rate ASC NULLS LAST, goods_code ASC";
        plan.sortWhitelist.putAll(Map.of(
                "openAmount", "open_amount",
                "salesCost", "sales_cost",
                "closeAmount", "close_amount",
                "avgStockAmount", "avg_stock_amount",
                "turnoverRate", "turnover_rate",
                "turnoverDays", "turnover_days",
                "salesAmount", "sales_amount",
                "grossMarginRate", "gross_margin_rate"));
        plan.maskOverrides.putAll(Map.of(
                "openAmount", "VIEW_STOCK_COST",
                "inAmount", "VIEW_STOCK_COST",
                "closeAmount", "VIEW_STOCK_COST",
                "avgStockAmount", "VIEW_STOCK_COST",
                "salesCost", "VIEW_COST_AMOUNT",
                "salesAmount", "VIEW_SALE_AMOUNT",
                "grossMarginRate", "VIEW_PROFIT"));

        // 合计：加权周转率 = 总销售成本 ÷ 总平均库存；周转天数反推；毛利率总额口径
        plan.grandSql = """
                SELECT COALESCE(SUM(x.open_qty),0) AS open_qty,
                       COALESCE(SUM(x.open_amount),0) AS open_amount,
                       COALESCE(SUM(x.in_amount),0) AS in_amount,
                       COALESCE(SUM(x.sales_cost),0) AS sales_cost,
                       COALESCE(SUM(x.close_qty),0) AS close_qty,
                       COALESCE(SUM(x.close_amount),0) AS close_amount,
                       COALESCE(SUM(x.sales_amount),0) AS sales_amount,
                       COALESCE(SUM(x.avg_stock_amount),0) AS avg_stock_amount,
                       CASE WHEN SUM(x.avg_stock_amount) > 0
                            THEN SUM(x.sales_cost) / SUM(x.avg_stock_amount) END AS turnover_rate,
                       CASE WHEN SUM(x.sales_cost) > 0 AND SUM(x.avg_stock_amount) > 0
                            THEN %d * SUM(x.avg_stock_amount) / SUM(x.sales_cost) END AS turnover_days,
                       CASE WHEN SUM(x.sales_amount) > 0
                            THEN (SUM(x.sales_amount) - SUM(x.sales_cost)) / SUM(x.sales_amount)
                            END AS gross_margin_rate,
                       COALESCE(SUM(CASE WHEN x.slow_flag = '呆滞' THEN 1 ELSE 0 END),0) AS slow_count
                FROM (%s %s) x
                """.formatted(calendarDays, plan.detailSelect, plan.fromWhere);
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
