package com.erp.report.sales;

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
 * 报表10/11/14 公共基类：基于 rpt_dws_sales_d（销售域日汇总，K8 签收口径）按白名单维度
 * GROUP BY。只产出叶子分组行，组小计由前端对叶子行实时汇总，与采购汇总基类同构。
 *
 * <p>十二列标准度量：销售（签收）/退货/净 × 数量/件数/金额 + 成本额/毛利额/毛利率
 * （含税口径 Q9：毛利=含税签收净额−签收配比成本）。成本/毛利列按敏感权限脱敏，
 * 毛利率不可加，合计行由 grandSql 以「总毛利÷总净额」单算，不走 SUM 包装。
 */
public abstract class AbstractSalesSummaryDefinition implements ReportDefinition {

    /** 可加度量别名（内层投影/排序/合计共用）。 */
    static final List<String> ADDITIVE_ALIASES = List.of(
            "signed_qty_base", "signed_package_qty", "signed_amount",
            "return_qty_base", "return_package_qty", "return_amount",
            "net_qty_base", "net_package_qty", "net_amount",
            "cost_amount", "gross_profit");

    /** 全部度量别名（含不可加的毛利率，内层叶子行需要）。 */
    static final List<String> MEASURE_ALIASES = List.of(
            "signed_qty_base", "signed_package_qty", "signed_amount",
            "return_qty_base", "return_package_qty", "return_amount",
            "net_qty_base", "net_package_qty", "net_amount",
            "cost_amount", "gross_profit", "gross_profit_rate");

    abstract Map<String, String> groupWhitelist();

    abstract List<String> defaultGroups();

    protected final DataScopeService dataScope;

    protected AbstractSalesSummaryDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public boolean dws() { return true; }
    @Override public boolean summaryReport() { return true; }

    @Override
    public List<String> groupFields(ReportQueryRequest req) {
        return resolveGroups(req).stream().map(g -> switch (g) {
            case "category" -> "categoryName";
            case "brand" -> "brandName";
            case "salesman" -> "salesman";
            case "storage" -> "storageProperty";
            case "customer" -> "customerName";
            case "customerLevel" -> "customerLevel";
            case "territory" -> "territory";
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

    /** 子类附加约束（如 #11 必须含客户维度）。 */
    void validateGroups(List<String> groups) { }

    /** 分组维度对应的内层主键列别名（默认排序兜底用）。 */
    static String groupAlias(String g) {
        return switch (g) {
            case "category" -> "category_name";
            case "brand" -> "brand_name";
            case "salesman" -> "salesman";
            case "storage" -> "storage_property";
            case "customer" -> "customer_code";
            case "customerLevel" -> "customer_level";
            case "territory" -> "territory";
            case "goods" -> "goods_code";
            default -> throw new IllegalArgumentException("不支持的分组维度：" + g);
        };
    }

    /** 拼内层分组 SELECT 投影（不含度量）与 GROUP BY 片段。 */
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
                case "salesman" -> {
                    select.append("       d.salesman AS salesman,\n");
                    groupBy.append("d.salesman, ");
                }
                case "storage" -> {
                    select.append("       d.storage_property AS storage_property,\n");
                    groupBy.append("d.storage_property, ");
                }
                case "customerLevel" -> {
                    select.append("       d.customer_level AS customer_level,\n");
                    groupBy.append("d.customer_level, ");
                }
                case "territory" -> {
                    select.append("       d.territory AS territory,\n");
                    groupBy.append("d.territory, ");
                }
                case "customer" -> {
                    select.append("       d.customer_code AS customer_code,\n");
                    select.append("       MAX(d.customer_name) AS customer_name,\n");
                    if (!groups.contains("customerLevel")) {
                        select.append("       MAX(d.customer_level) AS customer_level,\n");
                    }
                    if (!groups.contains("territory")) {
                        select.append("       MAX(d.territory) AS territory,\n");
                    }
                    select.append("       MAX(d.route_line) AS route_line,\n");
                    groupBy.append("d.customer_code, ");
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
     * 内层 SELECT 输出列别名：分组投影列（含客户/商品附带列）→ 商品附带属性 → 度量。
     * 顺序必须与 {@link #appendInnerProjection} + {@link #measureSelect(boolean)} 严格一致。
     */
    List<String> innerColumnAliases(List<String> groups) {
        List<String> cols = new ArrayList<>();
        for (String g : groups) {
            if ("customer".equals(g)) {
                cols.add("customer_code");
                cols.add("customer_name");
                if (!groups.contains("customerLevel")) cols.add("customer_level");
                if (!groups.contains("territory")) cols.add("territory");
                cols.add("route_line");
            } else if ("goods".equals(g)) {
                cols.add("goods_code");
                cols.add("goods_name");
            } else {
                cols.add(groupAlias(g));
            }
        }
        if (groups.contains("goods")) {
            if (!groups.contains("brand")) cols.add("brand_name");
            if (!groups.contains("category")) cols.add("category_name");
            if (!groups.contains("storage")) cols.add("storage_property");
        }
        cols.addAll(MEASURE_ALIASES);
        if (withCustomerCount()) cols.add("customer_count");
        return cols;
    }

    /** #14 业务员商品汇总额外输出「客户数」（非加，合计行不汇总）。 */
    boolean withCustomerCount() { return false; }

    /** 外层投影：内层列透传；商品叶子行分组后再关联维度补条码/基本单位。 */
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

    /** 十二列标准度量 SELECT 片段（前导逗号由调用方保证行尾衔接）。 */
    String measureSelect(boolean withCustomerCount) {
        String base = """
                COALESCE(SUM(d.signed_qty_base),0) AS signed_qty_base,
                COALESCE(SUM(d.signed_package_qty),0) AS signed_package_qty,
                COALESCE(SUM(d.signed_amount),0) AS signed_amount,
                COALESCE(SUM(d.return_qty_base),0) AS return_qty_base,
                COALESCE(SUM(d.return_package_qty),0) AS return_package_qty,
                COALESCE(SUM(d.return_amount),0) AS return_amount,
                COALESCE(SUM(d.signed_qty_base - d.return_qty_base),0) AS net_qty_base,
                COALESCE(SUM(d.signed_package_qty - d.return_package_qty),0) AS net_package_qty,
                COALESCE(SUM(d.signed_amount - d.return_amount),0) AS net_amount,
                COALESCE(SUM(d.signed_cost_amount - d.return_cost_amount),0) AS cost_amount,
                COALESCE(SUM(d.signed_amount - d.return_amount
                           - d.signed_cost_amount + d.return_cost_amount),0) AS gross_profit,
                CASE WHEN COALESCE(SUM(d.signed_amount - d.return_amount),0) = 0 THEN NULL
                     ELSE SUM(d.signed_amount - d.return_amount
                            - d.signed_cost_amount + d.return_cost_amount)
                          / SUM(d.signed_amount - d.return_amount) END AS gross_profit_rate
                """;
        // 客户数：期间有签收（非纯退货）的去重客户
        return withCustomerCount
                ? base + ",\n       COUNT(DISTINCT CASE WHEN d.signed_amount > 0"
                    + " THEN d.customer_code END) AS customer_count"
                : base;
    }

    static Map<String, String> measureSortWhitelist() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("signedQtyBase", "signed_qty_base");
        m.put("signedPackageQty", "signed_package_qty");
        m.put("signedAmount", "signed_amount");
        m.put("returnQtyBase", "return_qty_base");
        m.put("returnPackageQty", "return_package_qty");
        m.put("returnAmount", "return_amount");
        m.put("netQtyBase", "net_qty_base");
        m.put("netPackageQty", "net_package_qty");
        m.put("netAmount", "net_amount");
        m.put("costAmount", "cost_amount");
        m.put("grossProfit", "gross_profit");
        return m;
    }

    /** 十二列标准度量列定义（成本/毛利受列权限，默认仍可见，由权限决定是否脱敏）。 */
    static List<ReportColumnDef> measureColumns(boolean withCustomerCount) {
        List<ReportColumnDef> cols = new ArrayList<>(List.of(
                ReportColumnDef.measure("signedQtyBase", "销售数量(小单位)", null),
                ReportColumnDef.measure("signedPackageQty", "销售件数", null),
                ReportColumnDef.measure("signedAmount", "销售金额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("returnQtyBase", "退货数量(小单位)", null),
                ReportColumnDef.measure("returnPackageQty", "退货件数", null),
                ReportColumnDef.measure("returnAmount", "退货金额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("netQtyBase", "净销售数量(小单位)", null),
                ReportColumnDef.measure("netPackageQty", "净销售件数", null),
                ReportColumnDef.measure("netAmount", "净销售额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("costAmount", "成本金额", "VIEW_COST_AMOUNT"),
                ReportColumnDef.measure("grossProfit", "毛利额", "VIEW_PROFIT"),
                ReportColumnDef.measure("grossProfitRate", "毛利率", "VIEW_PROFIT")));
        if (withCustomerCount) {
            cols.add(ReportColumnDef.measure("customerCount", "客户数", null));
        }
        return cols;
    }

    /** 公共筛选：业务员/仓库/商品关键字/分类/品牌/温区/客户/等级/区域/路线。 */
    void appendCommonDwsFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args) {
        appendLike(req, sql, args, "salesman", "d.salesman");
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append(" AND d.warehouse = ? ");
            args.add(warehouse);
        }
        String goods = req.text("goods");
        if (goods != null) {
            sql.append(" AND (d.goods_code LIKE ? OR d.goods_name LIKE ?")
               .append(" OR d.goods_code IN (SELECT gg.goods_code FROM rpt_dim_goods gg")
               .append(" WHERE gg.barcode LIKE ?)) ");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
        }
        appendEq(req, sql, args, "categoryName", "d.category_name");
        appendEq(req, sql, args, "brandName", "d.brand_name");
        appendEq(req, sql, args, "storageProperty", "d.storage_property");
        String customer = req.text("customer");
        if (customer != null) {
            sql.append(" AND (d.customer_code LIKE ? OR d.customer_name LIKE ?) ");
            args.add("%" + customer + "%");
            args.add("%" + customer + "%");
        }
        appendEq(req, sql, args, "customerLevel", "d.customer_level");
        appendEq(req, sql, args, "territory", "d.territory");
        appendEq(req, sql, args, "routeLine", "d.route_line");
    }

    static void appendLike(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                           String key, String column) {
        String v = req.text(key);
        if (v != null) {
            sql.append(" AND ").append(column).append(" LIKE ? ");
            args.add("%" + v + "%");
        }
    }

    static void appendEq(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                         String key, String column) {
        String v = req.text(key);
        if (v != null) {
            sql.append(" AND ").append(column).append(" = ? ");
            args.add(v);
        }
    }

    /**
     * 组装计划（子类只提供白名单/默认分组/列定义与个性化数据范围）。
     * 合计走自定义 grandSql：在分组结果外包一层，毛利率按「总毛利÷总净额」重算，
     * 客户数等非加指标给 NULL。
     */
    protected Plan buildPlan(ReportQueryRequest req, DataScopeService.ScopeClause scope,
                             List<String> groups) {
        Plan plan = new Plan();
        if (scope.isDenyAll()) return plan.denyAll();

        boolean withCount = withCustomerCount();
        StringBuilder innerProj = new StringBuilder();
        StringBuilder groupBy = new StringBuilder();
        appendInnerProjection(innerProj, groupBy, groups);
        innerProj.append("       ").append(measureSelect(withCount));
        List<String> innerCols = innerColumnAliases(groups);
        boolean goodsLeaf = groups.contains("goods");

        plan.detailSelect = "SELECT\n" + outerProjection(innerCols, goodsLeaf)
                + " FROM (\n SELECT\n" + innerProj;

        plan.fromWhere.append("""
                FROM rpt_dws_sales_d d
                WHERE 1=1
                """);
        plan.args.add(req.range().startDate());
        plan.args.add(req.range().endDate());
        plan.fromWhere.append(" AND d.bill_date BETWEEN ? AND ? ");
        appendCommonDwsFilters(req, plan.fromWhere, plan.args);
        scope.appendTo(plan.fromWhere, plan.args);

        groupBy.setLength(groupBy.length() - 2);
        plan.groupBy = "GROUP BY " + groupBy
                + ") t\n" + (goodsLeaf
                        ? "LEFT JOIN rpt_dim_goods g ON g.goods_code = t.goods_code\n"
                        : "");

        // 合计行：可加度量 SUM 包装；毛利率总算；客户数不汇总
        StringBuilder grand = new StringBuilder("SELECT ");
        boolean firstMeasure = true;
        for (String col : innerCols) {
            if (MEASURE_ALIASES.contains(col) || "customer_count".equals(col)) {
                if (!firstMeasure) grand.append(", ");
                firstMeasure = false;
                if ("gross_profit_rate".equals(col)) {
                    grand.append("COALESCE(SUM(_g.gross_profit),0) / NULLIF(SUM(_g.net_amount),0)")
                         .append(" AS gross_profit_rate");
                } else if ("customer_count".equals(col)) {
                    grand.append("CAST(NULL AS BIGINT) AS customer_count");
                } else {
                    grand.append("COALESCE(SUM(_g.").append(col).append("),0) AS ").append(col);
                }
            }
        }
        // grandSql 消费与主查询完全相同的叶子行 SQL（同口径、同参数、同数据范围）
        String leafSql = plan.detailSelect + " " + plan.fromWhere + plan.groupBy;
        grand.append(" FROM (").append(leafSql).append(") _g");
        plan.grandSql = grand.toString();
        plan.grandArgs.addAll(plan.args);

        plan.sortWhitelist.putAll(measureSortWhitelist());
        plan.sortWhitelist.put("goodsCode", "goods_code");
        plan.sortWhitelist.put("salesman", "salesman");
        plan.sortWhitelist.put("customerCode", "customer_code");
        plan.sortWhitelist.put("categoryName", "category_name");
        plan.sortWhitelist.put("brandName", "brand_name");
        plan.defaultOrder = "ORDER BY net_amount DESC, " + groupAlias(groups.get(0)) + " ASC";
        plan.maskOverrides.putAll(Map.of(
                "signedAmount", "VIEW_SALE_AMOUNT",
                "returnAmount", "VIEW_SALE_AMOUNT",
                "netAmount", "VIEW_SALE_AMOUNT",
                "costAmount", "VIEW_COST_AMOUNT",
                "grossProfit", "VIEW_PROFIT",
                "grossProfitRate", "VIEW_PROFIT"));
        return plan;
    }
}
