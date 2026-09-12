package com.erp.report.purchase;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 报表3｜商品采购汇总表。rpt_dws_purchase_d 按自定义维度（最多 3 级）聚合，
 * 默认粒度「商品 + 采购员」。组小计/总计由前端对叶子分组行汇总；钻取落点为报表2。
 *
 * <p>SQL 形态（性能修复⑤）：内层只扫 DWS（档案属性已随日汇总下沉，聚合过程零档案表
 * JOIN），GROUP BY + LIMIT 在派生表 t 内完成；条码/基本单位在外层按 t.goods_code
 * 关联 rpt_dim_goods 补齐，JOIN 行数 = 分组结果（≤10 万）而非百万级明细。
 */
@Component
public class PurchaseGoodsSummaryDefinition extends AbstractPurchaseSummaryDefinition {

    public PurchaseGoodsSummaryDefinition(DataScopeService dataScope) {
        super(dataScope);
    }

    @Override public String code() { return "purchase_goods_summary"; }
    @Override public String name() { return "商品采购汇总表"; }
    @Override public String viewPerm() { return "report.purchase_goods_summary.view"; }

    @Override
    public List<ReportColumnDef> columns() {
        List<ReportColumnDef> cols = new java.util.ArrayList<>(List.of(
                ReportColumnDef.dim("categoryName", "商品类别"),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("buyer", "采购员"),
                ReportColumnDef.dim("storageProperty", "存储属性"),
                ReportColumnDef.dim("goodsCode", "商品编号"),
                ReportColumnDef.dim("goodsName", "商品名称"),
                ReportColumnDef.dim("barcode", "条码"),
                ReportColumnDef.dim("baseUnit", "基本单位")));
        cols.addAll(measureColumns());
        return cols;
    }

    @Override
    java.util.Map<String, String> groupWhitelist() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("category", "d.category_name");
        m.put("brand", "d.brand_name");
        m.put("buyer", "d.buyer");
        m.put("storage", "d.storage_property");
        m.put("goods", "d.goods_code");
        return m;
    }

    @Override
    List<String> defaultGroups() {
        return List.of("goods", "buyer");
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        List<String> groups = resolveGroups(req);
        var scope = dataScope.target()
                .warehouse("d.warehouse").supplier("d.supplier_name").salesman("d.buyer")
                .goodsColumn("d.goods_code")
                .build();
        if (scope.isDenyAll()) return plan.denyAll();

        StringBuilder innerProj = new StringBuilder();
        StringBuilder groupBy = new StringBuilder();
        appendInnerProjection(innerProj, groupBy, groups);
        innerProj.append("       ").append(measureSelect());
        List<String> innerCols = innerColumnAliases(groups);
        boolean goodsLeaf = groups.contains("goods");

        // SELECT 外层透传 t.* + 条码/单位；内层派生表在 fromWhere 处闭合为 (...) t [JOIN dim]
        plan.detailSelect = "SELECT\n" + outerProjection(innerCols, goodsLeaf)
                + " FROM (\n SELECT\n" + innerProj;

        plan.fromWhere.append("""
                FROM rpt_dws_purchase_d d
                WHERE 1=1
                """);
        plan.args.add(req.range().startDate());
        plan.args.add(req.range().endDate());
        plan.fromWhere.append(" AND d.bill_date BETWEEN ? AND ? ");
        appendCommonDwsFilters(req, plan.fromWhere, plan.args);
        scope.appendTo(plan.fromWhere, plan.args);

        groupBy.setLength(groupBy.length() - 2); // 去尾逗号
        plan.groupBy = "GROUP BY " + groupBy
                + ") t\n" + (goodsLeaf
                        ? "LEFT JOIN rpt_dim_goods g ON g.goods_code = t.goods_code\n"
                        : "");

        plan.summaryAliases.addAll(MEASURE_ALIASES);
        plan.sortWhitelist.putAll(measureSortWhitelist());
        plan.sortWhitelist.put("goodsCode", "goods_code");
        plan.sortWhitelist.put("buyer", "buyer");
        plan.sortWhitelist.put("categoryName", "category_name");
        plan.sortWhitelist.put("brandName", "brand_name");
        plan.defaultOrder = "ORDER BY net_amount DESC, " + groupAlias(groups.get(0)) + " ASC";
        plan.maskOverrides.putAll(java.util.Map.of(
                "inboundAmount", "VIEW_PURCHASE_AMOUNT",
                "returnAmount", "VIEW_PURCHASE_AMOUNT",
                "netAmount", "VIEW_PURCHASE_AMOUNT"));
        return plan;
    }
}
