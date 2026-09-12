package com.erp.report.purchase;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表3/4 公共基类：基于 rpt_dws_purchase_d（采购域日汇总）按白名单维度 GROUP BY。
 *
 * <p>只产出叶子分组行（最深层级），组小计/总计由前端按分组维度对叶子行实时汇总渲染、
 * 导出时由导出 worker 在 Java 侧汇总——小计与明细必然同口径，且避免 H2/MySQL
 * ROLLUP 方言差异。九列度量：入库/退货/净 × 数量/件数/金额，退货列正数展示发生额。
 */
public abstract class AbstractPurchaseSummaryDefinition implements ReportDefinition {

    /** 九列度量别名（总计包裹、排序白名单、导出汇总共用）。 */
    static final List<String> MEASURE_ALIASES = List.of(
            "inbound_qty_base", "inbound_package_qty", "inbound_amount",
            "return_qty_base", "return_package_qty", "return_amount",
            "net_qty_base", "net_package_qty", "net_amount");

    /** 维度白名单：key → 分组键表达式。 */
    abstract Map<String, String> groupWhitelist();

    /** 空 groupBy 时的默认层级（有序）。 */
    abstract List<String> defaultGroups();

    protected final DataScopeService dataScope;

    protected AbstractPurchaseSummaryDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public boolean dws() { return true; }
    @Override public boolean summaryReport() { return true; }

    @Override
    public List<String> groupFields(ReportQueryRequest req) {
        return resolveGroups(req).stream().map(g -> switch (g) {
            case "category" -> "categoryName";
            case "brand" -> "brandName";
            case "buyer" -> "buyer";
            case "storage" -> "storageProperty";
            case "supplier" -> "supplierName";
            case "supplierType" -> "supplierType";
            case "goods" -> "goodsName";
            default -> g;
        }).toList();
    }

    /** 解析并校验分组层级（白名单、最多 3 级、保序去重）。 */
    List<String> resolveGroups(ReportQueryRequest req) {
        List<String> raw = req.groupBy();
        List<String> groups = raw.isEmpty() ? defaultGroups() : raw;
        List<String> out = new ArrayList<>();
        for (String g : groups) {
            if (!groupWhitelist().containsKey(g)) {
                throw new IllegalArgumentException("不支持的分组维度：" + g
                        + "；可选：" + String.join("、", groupWhitelist().keySet()));
            }
            if (!out.contains(g)) out.add(g);
        }
        if (out.size() > 3) {
            throw new IllegalArgumentException("分组层级最多 3 级");
        }
        validateGroups(out);
        return out;
    }

    /** 子类附加约束（如 #4 必须含供应商维度）。 */
    void validateGroups(List<String> groups) { }

    /** 分组维度对应的内层主键列别名（默认排序兜底用）。 */
    static String groupAlias(String g) {
        return switch (g) {
            case "category" -> "category_name";
            case "brand" -> "brand_name";
            case "buyer" -> "buyer";
            case "storage" -> "storage_property";
            case "supplierType" -> "supplier_type";
            case "supplier" -> "supplier_code";
            case "goods" -> "goods_code";
            default -> throw new IllegalArgumentException("不支持的分组维度：" + g);
        };
    }

    /**
     * 拼内层分组 SELECT 投影（不含度量）与 GROUP BY 片段。
     * 全部取自 rpt_dws_purchase_d（档案属性已随日汇总下沉，聚合过程零档案表 JOIN）；
     * 商品为叶子时附带商品名称，未单独分组的品牌/类别/温区按 MAX 展示。
     * 条码/基本单位不在内层出，由分组后外层关联 rpt_dim_goods 补（只作用于商品叶子行）。
     */
    void appendInnerProjection(StringBuilder select, StringBuilder groupBy, List<String> groups) {
        for (String g : groups) {
            switch (g) {
                case "category" -> {
                    select.append("       d.category_name AS category_name,\n");
                    groupBy.append("d.category_name, ");
                }
                case "brand" -> {
                    select.append("       d.brand_name AS brand_name,\n");
                    groupBy.append("d.brand_name, ");
                }
                case "buyer" -> {
                    select.append("       d.buyer AS buyer,\n");
                    groupBy.append("d.buyer, ");
                }
                case "storage" -> {
                    select.append("       d.storage_property AS storage_property,\n");
                    groupBy.append("d.storage_property, ");
                }
                case "supplierType" -> {
                    select.append("       d.supplier_type AS supplier_type,\n");
                    groupBy.append("d.supplier_type, ");
                }
                case "supplier" -> {
                    select.append("       d.supplier_code AS supplier_code,\n");
                    select.append("       MAX(d.supplier_name) AS supplier_name,\n");
                    groupBy.append("d.supplier_code, ");
                }
                case "goods" -> {
                    select.append("       d.goods_code AS goods_code,\n");
                    select.append("       MAX(d.goods_name) AS goods_name,\n");
                    groupBy.append("d.goods_code, ");
                }
                default -> throw new IllegalArgumentException("不支持的分组维度：" + g);
            }
        }
        // 商品叶子行附带档案属性列（属性自身已作为分组层级时不重复出列，避免别名冲突）
        if (groups.contains("goods")) {
            if (!groups.contains("brand")) select.append("       MAX(d.brand_name) AS brand_name,\n");
            if (!groups.contains("category")) select.append("       MAX(d.category_name) AS category_name,\n");
            if (!groups.contains("storage")) select.append("       MAX(d.storage_property) AS storage_property,\n");
        }
    }

    /**
     * 外层投影：内层列透传；商品叶子行分组后再关联维度补条码/基本单位（JOIN 行数=分组结果）；
     * 非商品叶子分组没有 goods_code 列，给 NULL 占位保持列结构一致。
     */
    static String outerProjection(List<String> innerCols, boolean goodsLeaf) {
        StringBuilder sb = new StringBuilder();
        for (String col : innerCols) {
            sb.append("       t.").append(col).append(" AS ").append(col).append(",\n");
        }
        if (goodsLeaf) {
            sb.append("       g.barcode AS barcode,\n");
            sb.append("       g.base_unit AS base_unit\n");
        } else {
            sb.append("       CAST(NULL AS VARCHAR(100)) AS barcode,\n");
            sb.append("       CAST(NULL AS VARCHAR(50)) AS base_unit\n");
        }
        return sb.toString();
    }

    /** 内层 SELECT 输出列别名：分组投影列 → 商品叶子附带属性 → 九列度量（严格按投影顺序）。 */
    static List<String> innerColumnAliases(List<String> groups) {
        List<String> cols = new ArrayList<>();
        for (String g : groups) {
            if ("supplier".equals(g)) {
                cols.add("supplier_code");
                cols.add("supplier_name");
            } else {
                cols.add(groupAlias(g));
            }
        }
        // 与 appendInnerProjection 尾部附带属性顺序一致（品牌/类别/温区，分组已含则跳过）
        if (groups.contains("goods")) {
            if (!groups.contains("brand")) cols.add("brand_name");
            if (!groups.contains("category")) cols.add("category_name");
            if (!groups.contains("storage")) cols.add("storage_property");
        }
        cols.addAll(MEASURE_ALIASES);
        return cols;
    }

    /** 九列度量 SELECT 片段（前导逗号由调用方保证行尾衔接）。 */
    static String measureSelect() {
        return """
                COALESCE(SUM(d.inbound_qty_base),0) AS inbound_qty_base,
                COALESCE(SUM(d.inbound_package_qty),0) AS inbound_package_qty,
                COALESCE(SUM(d.inbound_amount),0) AS inbound_amount,
                COALESCE(SUM(d.return_qty_base),0) AS return_qty_base,
                COALESCE(SUM(d.return_package_qty),0) AS return_package_qty,
                COALESCE(SUM(d.return_amount),0) AS return_amount,
                COALESCE(SUM(d.inbound_qty_base - d.return_qty_base),0) AS net_qty_base,
                COALESCE(SUM(d.inbound_package_qty - d.return_package_qty),0) AS net_package_qty,
                COALESCE(SUM(d.inbound_amount - d.return_amount),0) AS net_amount
                """;
    }

    static Map<String, String> measureSortWhitelist() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("inboundQtyBase", "inbound_qty_base");
        m.put("inboundPackageQty", "inbound_package_qty");
        m.put("inboundAmount", "inbound_amount");
        m.put("returnQtyBase", "return_qty_base");
        m.put("returnPackageQty", "return_package_qty");
        m.put("returnAmount", "return_amount");
        m.put("netQtyBase", "net_qty_base");
        m.put("netPackageQty", "net_package_qty");
        m.put("netAmount", "net_amount");
        return m;
    }

    static List<ReportColumnDef> measureColumns() {
        return List.of(
                ReportColumnDef.measure("inboundQtyBase", "入库数量(小单位)", null),
                ReportColumnDef.measure("inboundPackageQty", "入库件数", null),
                ReportColumnDef.measure("inboundAmount", "入库金额", "VIEW_PURCHASE_AMOUNT"),
                ReportColumnDef.measure("returnQtyBase", "退货数量(小单位)", null),
                ReportColumnDef.measure("returnPackageQty", "退货件数", null),
                ReportColumnDef.measure("returnAmount", "退货金额", "VIEW_PURCHASE_AMOUNT"),
                ReportColumnDef.measure("netQtyBase", "采购数量(小单位)", null),
                ReportColumnDef.measure("netPackageQty", "采购件数", null),
                ReportColumnDef.measure("netAmount", "采购金额", "VIEW_PURCHASE_AMOUNT"));
    }

    /** 公共筛选：采购员/仓库/商品关键字/分类/品牌/存储属性。 */
    void appendCommonDwsFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args) {
        String buyer = req.text("buyer");
        if (buyer != null) {
            sql.append(" AND d.buyer LIKE ? ");
            args.add("%" + buyer + "%");
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append(" AND d.warehouse = ? ");
            args.add(warehouse);
        }
        String goods = req.text("goods");
        if (goods != null) {
            // 条码在商品维度表：IN 半连接（子查询只跑一次），编码/名称走 DWS 冗余列
            sql.append(" AND (d.goods_code LIKE ? OR d.goods_name LIKE ?")
               .append(" OR d.goods_code IN (SELECT gg.goods_code FROM rpt_dim_goods gg")
               .append(" WHERE gg.barcode LIKE ?)) ");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
        }
        String category = req.text("categoryName");
        if (category != null) {
            sql.append(" AND d.category_name = ? ");
            args.add(category);
        }
        String brand = req.text("brandName");
        if (brand != null) {
            sql.append(" AND d.brand_name = ? ");
            args.add(brand);
        }
        String storage = req.text("storageProperty");
        if (storage != null) {
            sql.append(" AND d.storage_property = ? ");
            args.add(storage);
        }
    }
}
