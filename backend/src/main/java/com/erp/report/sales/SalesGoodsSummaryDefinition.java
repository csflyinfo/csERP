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
 * 报表10｜商品销售汇总表（#3 商品采购汇总的销售对称版）。
 *
 * <p>取数 rpt_dws_sales_d（K8 签收口径、含税、退货发生额正数列示）；默认粒度
 * 「商品 + 业务员」，自定义分组：分类/品牌/业务员/温区/商品（最多 3 级）。
 * 选客户筛选时即该客户维度的商品汇总（分组不含客户列）。钻取落点 #16。
 */
@Component
public class SalesGoodsSummaryDefinition extends AbstractSalesSummaryDefinition {

    public SalesGoodsSummaryDefinition(DataScopeService dataScope) {
        super(dataScope);
    }

    @Override public String code() { return "sales_goods_summary"; }
    @Override public String name() { return "商品销售汇总表"; }
    @Override public String viewPerm() { return "report.sales_goods_summary.view"; }

    @Override
    public List<ReportColumnDef> columns() {
        List<ReportColumnDef> cols = new ArrayList<>(List.of(
                ReportColumnDef.dim("categoryName", "商品类别"),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("salesman", "业务员"),
                ReportColumnDef.dim("storageProperty", "存储属性"),
                ReportColumnDef.dim("goodsCode", "商品编号"),
                ReportColumnDef.dim("goodsName", "商品名称"),
                ReportColumnDef.dim("barcode", "条码"),
                ReportColumnDef.dim("baseUnit", "基本单位")));
        cols.addAll(measureColumns(false));
        return cols;
    }

    @Override
    Map<String, String> groupWhitelist() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("category", "d.category_name");
        m.put("brand", "d.brand_name");
        m.put("salesman", "d.salesman");
        m.put("storage", "d.storage_property");
        m.put("goods", "d.goods_code");
        return m;
    }

    @Override
    List<String> defaultGroups() {
        return List.of("goods", "salesman");
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
