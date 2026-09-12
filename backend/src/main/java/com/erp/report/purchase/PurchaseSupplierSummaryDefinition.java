package com.erp.report.purchase;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表4｜供应商商品采购汇总表。rpt_dws_purchase_d 按供应商相关维度聚合，
 * 默认粒度「供应商 + 商品 + 采购员」；分组必须含供应商或供应商分类。
 * 组小计/总计前端汇总；钻取落点为报表2（带供应商+类型）。
 *
 * <p>SQL 形态同报表3：内层只扫 DWS（供应商分类已随日汇总下沉），GROUP BY + LIMIT
 * 在派生表 t 内完成；条码/基本单位在外层仅对商品叶子行关联 rpt_dim_goods 补齐。
 */
@Component
public class PurchaseSupplierSummaryDefinition extends AbstractPurchaseSummaryDefinition {

    public PurchaseSupplierSummaryDefinition(DataScopeService dataScope) {
        super(dataScope);
    }

    @Override public String code() { return "purchase_supplier_summary"; }
    @Override public String name() { return "供应商商品采购汇总表"; }
    @Override public String viewPerm() { return "report.purchase_supplier_summary.view"; }

    @Override
    public List<ReportColumnDef> columns() {
        List<ReportColumnDef> cols = new ArrayList<>(List.of(
                ReportColumnDef.dim("supplierCode", "供应商编号"),
                ReportColumnDef.dim("supplierName", "供应商名称"),
                ReportColumnDef.dim("supplierType", "供应商分类"),
                ReportColumnDef.dim("buyer", "采购员"),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("categoryName", "商品类别"),
                ReportColumnDef.dim("storageProperty", "存储属性"),
                ReportColumnDef.dim("goodsCode", "商品编号"),
                ReportColumnDef.dim("goodsName", "商品名称"),
                ReportColumnDef.dim("barcode", "条码"),
                ReportColumnDef.dim("baseUnit", "基本单位")));
        cols.addAll(measureColumns());
        return cols;
    }

    @Override
    Map<String, String> groupWhitelist() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("supplier", "d.supplier_code");
        m.put("supplierType", "d.supplier_type");
        m.put("buyer", "d.buyer");
        m.put("brand", "d.brand_name");
        m.put("category", "d.category_name");
        m.put("storage", "d.storage_property");
        m.put("goods", "d.goods_code");
        return m;
    }

    @Override
    List<String> defaultGroups() {
        return List.of("supplier", "goods", "buyer");
    }

    @Override
    void validateGroups(List<String> groups) {
        if (!groups.contains("supplier") && !groups.contains("supplierType")) {
            throw new IllegalArgumentException("供应商商品采购汇总表的分组必须包含「供应商」或「供应商分类」");
        }
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

        plan.detailSelect = "SELECT\n" + outerProjection(innerCols, goodsLeaf)
                + " FROM (\n SELECT\n" + innerProj;

        plan.fromWhere.append("""
                FROM rpt_dws_purchase_d d
                WHERE 1=1
                """);
        plan.args.add(req.range().startDate());
        plan.args.add(req.range().endDate());
        plan.fromWhere.append(" AND d.bill_date BETWEEN ? AND ? ");

        String supplier = req.text("supplier");
        if (supplier != null) {
            plan.fromWhere.append(" AND (d.supplier_code LIKE ? OR d.supplier_name LIKE ?) ");
            plan.args.add("%" + supplier + "%");
            plan.args.add("%" + supplier + "%");
        }
        String supplierType = req.text("supplierType");
        if (supplierType != null) {
            plan.fromWhere.append(" AND d.supplier_type = ? ");
            plan.args.add(supplierType);
        }
        appendCommonDwsFilters(req, plan.fromWhere, plan.args);
        scope.appendTo(plan.fromWhere, plan.args);

        groupBy.setLength(groupBy.length() - 2);
        plan.groupBy = "GROUP BY " + groupBy
                + ") t\n" + (goodsLeaf
                        ? "LEFT JOIN rpt_dim_goods g ON g.goods_code = t.goods_code\n"
                        : "");

        plan.summaryAliases.addAll(MEASURE_ALIASES);
        plan.sortWhitelist.putAll(measureSortWhitelist());
        plan.sortWhitelist.put("supplierCode", "supplier_code");
        plan.sortWhitelist.put("supplierType", "supplier_type");
        plan.sortWhitelist.put("goodsCode", "goods_code");
        plan.sortWhitelist.put("buyer", "buyer");
        plan.defaultOrder = "ORDER BY net_amount DESC, " + groupAlias(groups.get(0)) + " ASC";
        plan.maskOverrides.putAll(Map.of(
                "inboundAmount", "VIEW_PURCHASE_AMOUNT",
                "returnAmount", "VIEW_PURCHASE_AMOUNT",
                "netAmount", "VIEW_PURCHASE_AMOUNT"));
        return plan;
    }
}
