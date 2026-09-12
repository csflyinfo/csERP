package com.erp.report.sales;

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
 * 报表11｜客户商品销售汇总表。粒度：客户 + 商品（分组必须含客户）。
 *
 * <p>业务员月度回访主表，数据范围按客户/业务员强隔离；钻取落点 #16
 * （客户+商品+期间+类型）。字段在 #10 基础上增加客户编号/名称/等级/区域/路线。
 */
@Component
public class CustomerGoodsSummaryDefinition extends AbstractSalesSummaryDefinition {

    public CustomerGoodsSummaryDefinition(DataScopeService dataScope) {
        super(dataScope);
    }

    @Override public String code() { return "customer_goods_summary"; }
    @Override public String name() { return "客户商品销售汇总表"; }
    @Override public String viewPerm() { return "report.customer_goods_summary.view"; }

    @Override
    public List<ReportColumnDef> columns() {
        List<ReportColumnDef> cols = new ArrayList<>(List.of(
                ReportColumnDef.dim("customerCode", "客户编号"),
                ReportColumnDef.dim("customerName", "客户名称"),
                ReportColumnDef.dim("customerLevel", "客户等级"),
                ReportColumnDef.dim("territory", "区域"),
                ReportColumnDef.dim("routeLine", "路线"),
                ReportColumnDef.dim("salesman", "业务员"),
                ReportColumnDef.dim("goodsCode", "商品编号"),
                ReportColumnDef.dim("goodsName", "商品名称"),
                ReportColumnDef.dim("barcode", "条码"),
                ReportColumnDef.dim("baseUnit", "基本单位"),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("categoryName", "商品类别"),
                ReportColumnDef.dim("storageProperty", "存储属性")));
        cols.addAll(measureColumns(false));
        return cols;
    }

    @Override
    Map<String, String> groupWhitelist() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("customer", "d.customer_code");
        m.put("territory", "d.territory");
        m.put("salesman", "d.salesman");
        m.put("customerLevel", "d.customer_level");
        m.put("brand", "d.brand_name");
        m.put("category", "d.category_name");
        m.put("goods", "d.goods_code");
        return m;
    }

    @Override
    List<String> defaultGroups() {
        return List.of("customer", "goods");
    }

    /** 钻取 #16 依赖客户维度：任何层级组合都必须带客户。 */
    @Override
    void validateGroups(List<String> groups) {
        if (!groups.contains("customer")) {
            throw new IllegalArgumentException("客户商品销售汇总必须包含「客户」分组维度");
        }
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        var scope = dataScope.target()
                .warehouse("d.warehouse").customer("d.customer_name").salesman("d.salesman")
                .goodsColumn("d.goods_code")
                .build();
        return buildPlan(req, scope, resolveGroups(req));
    }
}
