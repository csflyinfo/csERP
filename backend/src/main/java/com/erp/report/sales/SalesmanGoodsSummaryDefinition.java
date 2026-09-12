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
 * 报表14｜业务员商品销售汇总表。粒度：业务员 + 商品，字段与 #10 相同 + 客户数。
 *
 * <p>与 #13（业务员汇总）/ #10（商品汇总）互为维度切换；钻取落点 #16
 * （业务员+商品+期间+类型）。客户数为期间该业务员+商品维度的去重签收客户，
 * 非可加指标，合计行不汇总。
 */
@Component
public class SalesmanGoodsSummaryDefinition extends AbstractSalesSummaryDefinition {

    public SalesmanGoodsSummaryDefinition(DataScopeService dataScope) {
        super(dataScope);
    }

    @Override public String code() { return "salesman_goods_summary"; }
    @Override public String name() { return "业务员商品销售汇总表"; }
    @Override public String viewPerm() { return "report.salesman_goods_summary.view"; }

    @Override
    public List<ReportColumnDef> columns() {
        List<ReportColumnDef> cols = new ArrayList<>(List.of(
                ReportColumnDef.dim("salesman", "业务员"),
                ReportColumnDef.dim("categoryName", "商品类别"),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("storageProperty", "存储属性"),
                ReportColumnDef.dim("goodsCode", "商品编号"),
                ReportColumnDef.dim("goodsName", "商品名称"),
                ReportColumnDef.dim("barcode", "条码"),
                ReportColumnDef.dim("baseUnit", "基本单位")));
        cols.addAll(measureColumns(true));
        return cols;
    }

    @Override
    Map<String, String> groupWhitelist() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("salesman", "d.salesman");
        m.put("category", "d.category_name");
        m.put("brand", "d.brand_name");
        m.put("storage", "d.storage_property");
        m.put("goods", "d.goods_code");
        return m;
    }

    @Override
    List<String> defaultGroups() {
        return List.of("salesman", "goods");
    }

    @Override
    boolean withCustomerCount() { return true; }

    @Override
    public Plan build(ReportQueryRequest req) {
        var scope = dataScope.target()
                .warehouse("d.warehouse").customer("d.customer_name").salesman("d.salesman")
                .goodsColumn("d.goods_code")
                .build();
        return buildPlan(req, scope, resolveGroups(req));
    }
}
