package com.erp.report.purchase;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.CustomPageSql;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报表2｜采购明细查询（采购入库 / 采购退货）。
 *
 * <p>口径：仅已审核单据；入库为正、退货为负（K6）；金额含税（K3）；数量基本单位归一（K4）；
 * 小单位单价 = 含税金额 ÷ 基本数量，退货行金额/数量同为负、相除单价自然为正（K5）；
 * 件数 = 基本数量 ÷ 大单位换算率，未配置大单位时件数=基本数量（K4）。
 *
 * <p>取数两条路径：
 * <ul>
 *   <li><b>头窗口分页（默认浏览/导出）</b>：单据头 UNION（入库头/退货头）按日期索引排序，
 *       每单匹配行数在头侧相关计数 + SUM() OVER 累计，只 JOIN 与本页行号区间相交的单据，
 *       避免 10 万行/日规模下对全量明细外部排序；头级属性（供应商/采购员）只在头查询解析，
 *       不按明细行重复查找。H2 2.2「CTE 绑定参数 + 后续窗口 CTE 结果置空」缺陷通过把参数
 *       渲染为字面量规避（复用 {@link PurchaseOrderDetailDefinition#renderLiterals}）。</li>
 *   <li><b>DWD 视图路径</b>：合计接口与非默认排序仍走 v_rpt_purchase_detail（V115 已把
 *       供应商/采购订单的头级查找折叠进头派生表），合计与页面同口径。</li>
 * </ul>
 */
@Component
public class PurchaseMoveDetailDefinition implements ReportDefinition {

    private final DataScopeService dataScope;

    public PurchaseMoveDetailDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "purchase_move_detail"; }
    @Override public String name() { return "采购明细查询"; }
    @Override public String viewPerm() { return "report.purchase_move_detail.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return false; }

    @Override
    public List<ReportColumnDef> columns() {
        return List.of(
                ReportColumnDef.dim("billNo", "单据号"),
                ReportColumnDef.dim("billDate", "单据日期"),
                ReportColumnDef.dim("billType", "单据类型"),
                ReportColumnDef.dim("sourceBillNo", "源单号"),
                ReportColumnDef.dim("supplierCode", "供应商编号"),
                ReportColumnDef.dim("supplierName", "供应商名称"),
                ReportColumnDef.dim("buyer", "采购员"),
                ReportColumnDef.dim("goodsCode", "商品编号"),
                ReportColumnDef.dim("goodsName", "商品名称"),
                ReportColumnDef.dim("barcode", "条码"),
                ReportColumnDef.dim("baseUnit", "基本单位"),
                ReportColumnDef.measure("baseQty", "数量(小单位)", null),
                ReportColumnDef.measure("unitPriceBase", "单价(小单位)", "VIEW_PURCHASE_PRICE"),
                ReportColumnDef.measure("packageQty", "件数", null),
                ReportColumnDef.dim("largeUnit", "大单位"),
                ReportColumnDef.measure("boxPrice", "箱价", "VIEW_PURCHASE_PRICE"),
                ReportColumnDef.measure("amount", "采购金额", "VIEW_PURCHASE_AMOUNT"),
                ReportColumnDef.dim("warehouse", "仓库"),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("categoryName", "商品类别"),
                ReportColumnDef.dim("storageProperty", "存储属性")
        );
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        var viewScope = dataScope.target()
                .warehouse("v.warehouse").supplier("v.supplier_name").salesman("v.buyer")
                .goodsColumn("v.goods_code")
                .build();
        if (viewScope.isDenyAll()) return plan.denyAll();

        String billType = req.text("billType");
        if (billType != null && !"采购入库".equals(billType) && !"采购退货".equals(billType)) {
            throw new IllegalArgumentException("单据类型只支持：采购入库 / 采购退货");
        }

        buildViewPath(req, plan, viewScope, billType);

        boolean defaultOrder = req.sortField() == null
                || ("billDate".equals(req.sortField()) && !req.isAsc());
        if (defaultOrder) {
            buildWindowPath(req, plan, billType);
        }
        return plan;
    }

    // ============================ DWD 视图路径（合计/非默认排序） ============================

    private void buildViewPath(ReportQueryRequest req, Plan plan,
                               DataScopeService.ScopeClause viewScope, String billType) {
        // K5：小单位单价=金额÷基本数量；退货行金额/数量同为负，相除单价自然为正（红冲负数量、正单价）。
        plan.detailSelect = """
                SELECT v.bill_no AS bill_no, v.bill_date AS bill_date, v.bill_type AS bill_type,
                       CASE WHEN v.source_bill_no IS NULL OR v.source_bill_no = ''
                            THEN '无订单采购' ELSE v.source_bill_no END AS source_bill_no,
                       v.supplier_code AS supplier_code, v.supplier_name AS supplier_name,
                       v.buyer AS buyer,
                       v.goods_code AS goods_code, v.goods_name AS goods_name,
                       g.barcode AS barcode, g.base_unit AS base_unit,
                       v.base_qty AS base_qty,
                       CASE WHEN v.base_qty = 0 THEN NULL
                            ELSE v.amount / v.base_qty END AS unit_price_base,
                       v.package_qty AS package_qty,
                       v.large_unit AS large_unit,
                       CASE WHEN v.package_qty = 0 THEN NULL
                            ELSE v.amount / v.package_qty END AS box_price,
                       v.amount AS amount,
                       v.warehouse AS warehouse,
                       g.brand_name AS brand_name, g.category_name AS category_name,
                       g.storage_property AS storage_property
                """;

        plan.fromWhere.append("""
                FROM v_rpt_purchase_detail v
                LEFT JOIN rpt_dim_goods g ON g.goods_code = v.goods_code
                WHERE 1=1
                """);
        plan.args.add(req.range().startDate());
        plan.args.add(req.range().endDate());
        plan.fromWhere.append(" AND v.bill_date BETWEEN ? AND ? ");

        if (billType != null) {
            plan.fromWhere.append(" AND v.bill_type = ? ");
            plan.args.add(billType);
        }
        appendLike(req, plan, "billNo", "v.bill_no");
        appendLike(req, plan, "sourceBillNo", "v.source_bill_no");
        String supplier = req.text("supplier");
        if (supplier != null) {
            plan.fromWhere.append(" AND (v.supplier_code LIKE ? OR v.supplier_name LIKE ?) ");
            plan.args.add("%" + supplier + "%");
            plan.args.add("%" + supplier + "%");
        }
        appendLike(req, plan, "buyer", "v.buyer");
        appendEq(req, plan, "warehouse", "v.warehouse");
        String goods = req.text("goods");
        if (goods != null) {
            plan.fromWhere.append(" AND (v.goods_code LIKE ? OR v.goods_name LIKE ? OR g.barcode LIKE ?) ");
            plan.args.add("%" + goods + "%");
            plan.args.add("%" + goods + "%");
            plan.args.add("%" + goods + "%");
        }
        appendEq(req, plan, "categoryName", "g.category_name");
        appendEq(req, plan, "brandName", "g.brand_name");
        appendEq(req, plan, "storageProperty", "g.storage_property");
        viewScope.appendTo(plan.fromWhere, plan.args);

        plan.sortWhitelist.putAll(Map.of(
                "billDate", "bill_date", "billNo", "bill_no", "supplierName", "supplier_name",
                "goodsCode", "goods_code", "baseQty", "base_qty", "amount", "amount"));
        plan.defaultOrder = "ORDER BY bill_date DESC, bill_no ASC, goods_code ASC";

        plan.grandSummarySelect = """
                SELECT COUNT(*) AS line_count,
                       COALESCE(SUM(v.base_qty),0) AS base_qty,
                       COALESCE(SUM(v.package_qty),0) AS package_qty,
                       COALESCE(SUM(v.amount),0) AS amount,
                       COALESCE(SUM(CASE WHEN v.bill_type = '采购入库' THEN v.base_qty ELSE 0 END),0) AS inbound_qty_base,
                       COALESCE(SUM(CASE WHEN v.bill_type = '采购退货' THEN v.base_qty ELSE 0 END),0) AS return_qty_base,
                       COALESCE(SUM(CASE WHEN v.bill_type = '采购入库' THEN v.amount ELSE 0 END),0) AS inbound_amount,
                       COALESCE(SUM(CASE WHEN v.bill_type = '采购退货' THEN v.amount ELSE 0 END),0) AS return_amount
                """;
        plan.maskOverrides.putAll(Map.of(
                "unitPriceBase", "VIEW_PURCHASE_PRICE",
                "boxPrice", "VIEW_PURCHASE_PRICE",
                "amount", "VIEW_PURCHASE_AMOUNT",
                "inboundAmount", "VIEW_PURCHASE_AMOUNT",
                "returnAmount", "VIEW_PURCHASE_AMOUNT"));
    }

    private static void appendLike(ReportQueryRequest req, Plan plan, String key, String column) {
        String v = req.text(key);
        if (v != null) {
            plan.fromWhere.append(" AND ").append(column).append(" LIKE ? ");
            plan.args.add("%" + v + "%");
        }
    }

    private static void appendEq(ReportQueryRequest req, Plan plan, String key, String column) {
        String v = req.text(key);
        if (v != null) {
            plan.fromWhere.append(" AND ").append(column).append(" = ? ");
            plan.args.add(v);
        }
    }

    // ============================ 头窗口分页路径 ============================

    private void buildWindowPath(ReportQueryRequest req, Plan plan, String billType) {
        boolean includeIn = billType == null || "采购入库".equals(billType);
        boolean includeRet = billType == null || "采购退货".equals(billType);

        // 数据范围按「分支 × 计数/取页」拆 6 份：头级供应商/采购员，行级仓库/商品。任一 deny 即全 deny。
        var scopeIH = dataScope.target().supplier("h.supplier")
                .salesman("COALESCE(po.buyer, sup.default_buyer, '')").build();
        var scopeRH = dataScope.target().supplier("h.supplier_name")
                .salesman("COALESCE(sup.default_buyer, '')").build();
        var scopeIL0 = dataScope.target().warehouse("COALESCE(d.warehouse, h.warehouse)")
                .goodsColumn("d.goods_code").build();
        var scopeRL0 = dataScope.target().warehouse("h.warehouse")
                .goodsColumn("d.goods_code").build();
        var scopeIZ = dataScope.target().warehouse("COALESCE(d.warehouse, hdr.header_warehouse)")
                .goodsColumn("d.goods_code").build();
        var scopeRZ = dataScope.target().warehouse("hdr.header_warehouse")
                .goodsColumn("d.goods_code").build();
        if (scopeIH.isDenyAll() || scopeRH.isDenyAll() || scopeIL0.isDenyAll()
                || scopeRL0.isDenyAll() || scopeIZ.isDenyAll() || scopeRZ.isDenyAll()) {
            plan.denyAll();
            return;
        }

        // h0 片段与参数按 SQL 出现顺序同步产出（先计数子查询内行过滤，后头 WHERE）
        StringBuilder h0Sql = new StringBuilder("WITH h0 AS (\n");
        List<Object> countArgs = new ArrayList<>();
        boolean first = true;
        if (includeIn) {
            h0Sql.append(inboundHeaderCte(req, scopeIH, scopeIL0, countArgs));
            first = false;
        }
        if (includeRet) {
            if (!first) h0Sql.append("  UNION ALL\n");
            h0Sql.append(returnHeaderCte(req, scopeRH, scopeRL0, countArgs));
        }
        h0Sql.append(")\n");
        String countSql = PurchaseOrderDetailDefinition.renderLiterals(
                h0Sql + " SELECT COALESCE(SUM(line_cnt),0) AS total FROM h0", countArgs);

        String hdrCte = """
                , hdr AS (
                  SELECT h0.*, SUM(line_cnt) OVER (ORDER BY bill_date DESC, bill_no ASC
                      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_end
                  FROM h0
                )
                """;

        plan.customPageSql = new CustomPageSql() {
            @Override public String countSql() { return countSql; }
            @Override public List<Object> countArgs() { return List.of(); }
            @Override public String listSql(long offset, int limit) {
                // h0 段与计数同源重建（含参数），z 段边界在各自片段里按 SQL 顺序先于行过滤入参
                List<Object> args = new ArrayList<>();
                StringBuilder sql = new StringBuilder(h0Sql);
                for (Object ignored : countArgs) args.add(ignored);
                sql.append(hdrCte).append(", z AS (\n");
                boolean[] firstBranch = {true};
                if (includeIn) {
                    sql.append(inboundZCte(req, offset, limit, scopeIZ, args));
                    firstBranch[0] = false;
                }
                if (includeRet) {
                    if (!firstBranch[0]) sql.append("  UNION ALL\n");
                    sql.append(returnZCte(req, offset, limit, scopeRZ, args));
                }
                sql.append(")\n ").append(windowFinalSelect())
                   .append(" FROM z\n")
                   .append(" WHERE z.gidx > ").append(offset)
                   .append(" AND z.gidx <= ").append((long) offset + limit).append('\n')
                   .append(" ORDER BY z.bill_date DESC, z.bill_no ASC, z.gidx ASC");
                return PurchaseOrderDetailDefinition.renderLiterals(sql.toString(), args);
            }
            @Override public List<Object> listArgs(long offset, int limit) { return List.of(); }
        };
    }

    /**
     * h0 入库头分支（头级属性按头 JOIN，行级过滤进相关计数）。
     * 参数顺序：计数子查询行过滤+行数据范围 → 日期区间 → 头过滤 → 头数据范围。
     */
    private static String inboundHeaderCte(ReportQueryRequest req,
                                           DataScopeService.ScopeClause hScope,
                                           DataScopeService.ScopeClause lScope,
                                           List<Object> args) {
        StringBuilder sb = new StringBuilder();
        sb.append("  SELECT 'I' AS doc_type, h.inbound_id AS doc_id, h.inbound_no AS bill_no,\n");
        sb.append("         h.bill_date AS bill_date, h.source_order AS source_bill_no,\n");
        sb.append("         COALESCE(sup.supplier_code, '') AS supplier_code,\n");
        sb.append("         h.supplier AS supplier_name,\n");
        sb.append("         COALESCE(po.buyer, sup.default_buyer, '') AS buyer,\n");
        sb.append("         h.warehouse AS header_warehouse,\n");
        sb.append("         (SELECT COUNT(*) FROM pur_inbound_detail d\n");
        sb.append("          LEFT JOIN rpt_dim_goods gg ON gg.goods_code = d.goods_code\n");
        sb.append("          WHERE d.inbound_id = h.inbound_id\n");
        appendLinePreds(req, sb, args, "d", "gg", "COALESCE(d.warehouse, h.warehouse)");
        lScope.appendTo(sb, args);
        sb.append("          ) AS line_cnt\n");
        sb.append("  FROM pur_inbound h\n");
        sb.append("  LEFT JOIN base_supplier sup ON sup.supplier_name = h.supplier\n");
        sb.append("  LEFT JOIN purchase_order po ON po.order_no = h.source_order\n");
        sb.append("  WHERE h.status = 'APPROVED' AND h.bill_date BETWEEN ? AND ?\n");
        args.add(req.range().startDate());
        args.add(req.range().endDate());
        appendHeaderPreds(req, sb, args, "h.inbound_no", "h.source_order",
                "sup.supplier_code", "h.supplier",
                "COALESCE(po.buyer, sup.default_buyer, '')");
        hScope.appendTo(sb, args);
        return sb.toString();
    }

    /** h0 退货头分支。参数顺序同入库分支。 */
    private static String returnHeaderCte(ReportQueryRequest req,
                                          DataScopeService.ScopeClause hScope,
                                          DataScopeService.ScopeClause lScope,
                                          List<Object> args) {
        StringBuilder sb = new StringBuilder();
        sb.append("  SELECT 'R' AS doc_type, h.return_id AS doc_id, h.return_no AS bill_no,\n");
        sb.append("         h.return_date AS bill_date, h.source_apply_no AS source_bill_no,\n");
        sb.append("         COALESCE(h.supplier_code, '') AS supplier_code,\n");
        sb.append("         h.supplier_name AS supplier_name,\n");
        sb.append("         COALESCE(sup.default_buyer, '') AS buyer,\n");
        sb.append("         h.warehouse AS header_warehouse,\n");
        sb.append("         (SELECT COUNT(*) FROM pur_return_detail d\n");
        sb.append("          LEFT JOIN rpt_dim_goods gg ON gg.goods_code = d.goods_code\n");
        sb.append("          WHERE d.return_id = h.return_id\n");
        appendLinePreds(req, sb, args, "d", "gg", "h.warehouse");
        lScope.appendTo(sb, args);
        sb.append("          ) AS line_cnt\n");
        sb.append("  FROM pur_return h\n");
        sb.append("  LEFT JOIN base_supplier sup ON sup.supplier_name = h.supplier_name\n");
        sb.append("  WHERE h.status = 'APPROVED' AND h.return_date BETWEEN ? AND ?\n");
        args.add(req.range().startDate());
        args.add(req.range().endDate());
        appendHeaderPreds(req, sb, args, "h.return_no", "h.source_apply_no",
                "h.supplier_code", "h.supplier_name", "COALESCE(sup.default_buyer, '')");
        hScope.appendTo(sb, args);
        return sb.toString();
    }

    /** z 入库分支：只取与本页行号区间相交的单据；参数顺序：区间边界 2 个 → 行过滤 → 行数据范围。 */
    private static String inboundZCte(ReportQueryRequest req, long offset, long limit,
                                      DataScopeService.ScopeClause zScope, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        sb.append("  SELECT 'I' AS doc_type, hdr.doc_id, hdr.bill_no, hdr.bill_date, hdr.source_bill_no,\n");
        sb.append("         hdr.supplier_code, hdr.supplier_name, hdr.buyer, hdr.header_warehouse,\n");
        sb.append("         d.goods_code, d.goods_name, d.unit_name AS order_unit,\n");
        sb.append("         d.received_qty AS order_qty, d.warehouse AS line_warehouse,\n");
        sb.append("         d.amount AS raw_amount, CAST(0 AS DECIMAL(18,2)) AS tax_amount,\n");
        sb.append("         pfm.factor_qty AS factor_raw,\n");
        sb.append("         dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,\n");
        sb.append("         dg.base_unit AS base_unit, dg.barcode AS barcode,\n");
        sb.append("         dg.brand_name AS brand_name, dg.category_name AS category_name,\n");
        sb.append("         dg.storage_property AS storage_property,\n");
        sb.append("         hdr.cum_end - hdr.line_cnt\n");
        sb.append("           + ROW_NUMBER() OVER (PARTITION BY hdr.doc_type, hdr.doc_id\n");
        sb.append("                                ORDER BY d.goods_code ASC, d.detail_id ASC) AS gidx\n");
        sb.append("  FROM hdr\n");
        sb.append("  JOIN pur_inbound_detail d ON d.inbound_id = hdr.doc_id\n");
        sb.append("  LEFT JOIN rpt_dim_unit_factor pfm\n");
        sb.append("         ON pfm.biz_type = 'P' AND pfm.goods_code = d.goods_code\n");
        sb.append("            AND pfm.unit_name = d.unit_name\n");
        sb.append("  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = d.goods_code\n");
        sb.append("  WHERE hdr.doc_type = 'I' AND hdr.cum_end > ? AND hdr.cum_end - hdr.line_cnt < ?\n");
        args.add(offset);
        args.add(limit);
        appendLinePreds(req, sb, args, "d", "dg", "COALESCE(d.warehouse, hdr.header_warehouse)");
        zScope.appendTo(sb, args);
        return sb.toString();
    }

    /** z 退货分支：数量/金额取负，金额含税额；参数顺序同入库分支。 */
    private static String returnZCte(ReportQueryRequest req, long offset, long limit,
                                     DataScopeService.ScopeClause zScope, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        sb.append("  SELECT 'R' AS doc_type, hdr.doc_id, hdr.bill_no, hdr.bill_date, hdr.source_bill_no,\n");
        sb.append("         hdr.supplier_code, hdr.supplier_name, hdr.buyer, hdr.header_warehouse,\n");
        sb.append("         d.goods_code, d.goods_name, d.unit_name AS order_unit,\n");
        sb.append("         -d.qty AS order_qty, CAST(NULL AS VARCHAR(100)) AS line_warehouse,\n");
        sb.append("         -d.amount AS raw_amount, -COALESCE(d.tax_amount,0) AS tax_amount,\n");
        sb.append("         pfm.factor_qty AS factor_raw,\n");
        sb.append("         dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,\n");
        sb.append("         dg.base_unit AS base_unit, dg.barcode AS barcode,\n");
        sb.append("         dg.brand_name AS brand_name, dg.category_name AS category_name,\n");
        sb.append("         dg.storage_property AS storage_property,\n");
        sb.append("         hdr.cum_end - hdr.line_cnt\n");
        sb.append("           + ROW_NUMBER() OVER (PARTITION BY hdr.doc_type, hdr.doc_id\n");
        sb.append("                                ORDER BY d.goods_code ASC, d.detail_id ASC) AS gidx\n");
        sb.append("  FROM hdr\n");
        sb.append("  JOIN pur_return_detail d ON d.return_id = hdr.doc_id\n");
        sb.append("  LEFT JOIN rpt_dim_unit_factor pfm\n");
        sb.append("         ON pfm.biz_type = 'P' AND pfm.goods_code = d.goods_code\n");
        sb.append("            AND pfm.unit_name = d.unit_name\n");
        sb.append("  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = d.goods_code\n");
        sb.append("  WHERE hdr.doc_type = 'R' AND hdr.cum_end > ? AND hdr.cum_end - hdr.line_cnt < ?\n");
        args.add(offset);
        args.add(limit);
        appendLinePreds(req, sb, args, "d", "dg", "hdr.header_warehouse");
        zScope.appendTo(sb, args);
        return sb.toString();
    }

    /** z 外层最终投影：factor 兜底、基本数量/件数/单价/箱价派生（与 DWD 视图同口径）。 */
    private static String windowFinalSelect() {
        String factor = "COALESCE(z.factor_raw, CASE WHEN z.order_unit = z.large_unit"
                + " THEN z.large_convert_qty END, 1)";
        String baseQty = "z.order_qty * " + factor;
        String pkgQty = "CASE WHEN COALESCE(z.large_convert_qty, 0) > 0 THEN "
                + baseQty + " / z.large_convert_qty ELSE " + baseQty + " END";
        return """
                SELECT z.bill_no AS bill_no, z.bill_date AS bill_date,
                       CASE z.doc_type WHEN 'I' THEN '采购入库' WHEN 'R' THEN '采购退货' END AS bill_type,
                       CASE WHEN z.source_bill_no IS NULL OR z.source_bill_no = ''
                            THEN '无订单采购' ELSE z.source_bill_no END AS source_bill_no,
                       z.supplier_code AS supplier_code, z.supplier_name AS supplier_name,
                       z.buyer AS buyer,
                       z.goods_code AS goods_code, z.goods_name AS goods_name,
                       z.barcode AS barcode, z.base_unit AS base_unit,
                       %1$s AS base_qty,
                       CASE WHEN %1$s = 0 THEN NULL
                            ELSE (z.raw_amount + z.tax_amount) / (%1$s) END AS unit_price_base,
                       %2$s AS package_qty,
                       z.large_unit AS large_unit,
                       CASE WHEN %2$s = 0 THEN NULL
                            ELSE (z.raw_amount + z.tax_amount) / (%2$s) END AS box_price,
                       z.raw_amount + z.tax_amount AS amount,
                       COALESCE(z.line_warehouse, z.header_warehouse) AS warehouse,
                       z.brand_name AS brand_name, z.category_name AS category_name,
                       z.storage_property AS storage_property
                """.formatted(baseQty, pkgQty);
    }

    // ============================ 窗口路径过滤片段（SQL 与参数同步追加） ============================

    /** 头级过滤：单号/源单号 LIKE、供应商编码或名称 LIKE、采购员 LIKE。 */
    private static void appendHeaderPreds(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                                          String billNoCol, String sourceCol,
                                          String supplierCodeCol, String supplierNameCol,
                                          String buyerExpr) {
        String billNo = req.text("billNo");
        if (billNo != null) {
            sql.append("  AND ").append(billNoCol).append(" LIKE ?\n");
            args.add("%" + billNo + "%");
        }
        String source = req.text("sourceBillNo");
        if (source != null) {
            sql.append("  AND ").append(sourceCol).append(" LIKE ?\n");
            args.add("%" + source + "%");
        }
        String supplier = req.text("supplier");
        if (supplier != null) {
            sql.append("  AND (").append(supplierCodeCol).append(" LIKE ? OR ")
               .append(supplierNameCol).append(" LIKE ?)\n");
            args.add("%" + supplier + "%");
            args.add("%" + supplier + "%");
        }
        String buyer = req.text("buyer");
        if (buyer != null) {
            sql.append("  AND ").append(buyerExpr).append(" LIKE ?\n");
            args.add("%" + buyer + "%");
        }
    }

    /** 行级过滤：商品关键字（编码/名称/条码）、分类、品牌、温区、仓库；参数按片段顺序入列。 */
    static void appendLinePreds(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                                String d, String g, String warehouseExpr) {
        String goods = req.text("goods");
        if (goods != null) {
            sql.append("          AND (").append(d).append(".goods_code LIKE ? OR ")
               .append(d).append(".goods_name LIKE ? OR ")
               .append(g).append(".barcode LIKE ?)\n");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
        }
        String category = req.text("categoryName");
        if (category != null) {
            sql.append("          AND ").append(g).append(".category_name = ?\n");
            args.add(category);
        }
        String brand = req.text("brandName");
        if (brand != null) {
            sql.append("          AND ").append(g).append(".brand_name = ?\n");
            args.add(brand);
        }
        String storage = req.text("storageProperty");
        if (storage != null) {
            sql.append("          AND ").append(g).append(".storage_property = ?\n");
            args.add(storage);
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append("          AND ").append(warehouseExpr).append(" = ?\n");
            args.add(warehouse);
        }
    }
}
