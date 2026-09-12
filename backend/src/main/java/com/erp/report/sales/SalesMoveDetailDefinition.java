package com.erp.report.sales;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.common.SqlLiterals;
import com.erp.report.meta.CustomPageSql;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报表16｜商品销售明细表（销售签收 / 销售退货流水）。
 *
 * <p>口径：K8 签收口径——日期=司机签收日期(CAST(sign_time AS DATE))、数量=签收数量、
 * 金额=签收金额（含税）；拒收行不进本表；退货按退货审核日期负数列示；成本/毛利同
 * v_rpt_sales_detail（签收成本=出库成本−JSRK拒收成本，退货成本=审核时移动加权成本）。
 *
 * <p>取数两条路径（与 {@code PurchaseMoveDetailDefinition} 同构）：
 * <ul>
 *   <li><b>DWD 视图路径</b>：合计接口与非默认排序走 v_rpt_sales_detail（V117 已把
 *       司机/区域/路线/成本的查找折叠进头派生表）。</li>
 *   <li><b>头窗口分页（默认浏览/导出）</b>：发货签收头/退货入库头 UNION 按签收时间
 *       排序，相关计数 + SUM() OVER 累计，只 JOIN 与本页行号区间相交的单据。
 *       H2 2.2 CTE 绑定参数缺陷用 {@link SqlLiterals} 零参渲染规避。</li>
 * </ul>
 */
@Component
public class SalesMoveDetailDefinition implements ReportDefinition {

    private final DataScopeService dataScope;

    public SalesMoveDetailDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "sales_move_detail"; }
    @Override public String name() { return "商品销售明细表"; }
    @Override public String viewPerm() { return "report.sales_move_detail.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return false; }

    @Override
    public List<ReportColumnDef> columns() {
        return List.of(
                ReportColumnDef.dim("billNo", "单据号"),
                ReportColumnDef.dim("billDate", "单据日期"),
                ReportColumnDef.dim("billType", "单据类型"),
                ReportColumnDef.dim("sourceBillNo", "销售订单号"),
                ReportColumnDef.dim("driver", "司机"),
                ReportColumnDef.dim("customerCode", "客户编号"),
                ReportColumnDef.dim("customerName", "客户名称"),
                ReportColumnDef.dim("salesman", "业务员"),
                ReportColumnDef.dim("territory", "区域"),
                ReportColumnDef.dim("routeLine", "路线"),
                ReportColumnDef.dim("goodsCode", "商品编号"),
                ReportColumnDef.dim("goodsName", "商品名称"),
                ReportColumnDef.dim("barcode", "条码"),
                ReportColumnDef.dim("baseUnit", "基本单位"),
                ReportColumnDef.measure("baseQty", "数量(小单位)", null),
                ReportColumnDef.measure("unitPriceBase", "单价(小单位)", "VIEW_SALE_PRICE"),
                ReportColumnDef.measure("packageQty", "件数", null),
                ReportColumnDef.dim("largeUnit", "大单位"),
                ReportColumnDef.measure("boxPrice", "箱价", "VIEW_SALE_PRICE"),
                ReportColumnDef.measure("amount", "销售金额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("unitCostBase", "单位成本", "VIEW_COST"),
                ReportColumnDef.measure("costAmount", "成本金额", "VIEW_COST_AMOUNT"),
                ReportColumnDef.measure("grossProfit", "毛利额", "VIEW_PROFIT"),
                ReportColumnDef.measure("grossProfitRate", "毛利率", "VIEW_PROFIT"),
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
                .warehouse("v.warehouse").customer("v.customer_name").salesman("v.salesman")
                .goodsColumn("v.goods_code")
                .build();
        if (viewScope.isDenyAll()) return plan.denyAll();

        String billType = req.text("billType");
        if (billType != null && !"销售签收".equals(billType) && !"销售退货".equals(billType)) {
            throw new IllegalArgumentException("单据类型只支持：销售签收 / 销售退货");
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
        plan.detailSelect = """
                SELECT v.bill_no AS bill_no, v.bill_date AS bill_date, v.bill_type AS bill_type,
                       v.source_bill_no AS source_bill_no, v.driver AS driver,
                       v.customer_code AS customer_code, v.customer_name AS customer_name,
                       v.salesman AS salesman, v.territory AS territory, v.route_line AS route_line,
                       v.goods_code AS goods_code, v.goods_name AS goods_name,
                       g.barcode AS barcode, g.base_unit AS base_unit,
                       v.base_qty AS base_qty,
                       v.unit_price_base AS unit_price_base,
                       v.package_qty AS package_qty,
                       v.large_unit AS large_unit,
                       CASE WHEN v.package_qty = 0 THEN NULL
                            ELSE v.amount / v.package_qty END AS box_price,
                       v.amount AS amount,
                       v.unit_cost_base AS unit_cost_base,
                       v.cost_amount AS cost_amount,
                       v.gross_profit AS gross_profit,
                       v.gross_profit_rate AS gross_profit_rate,
                       v.warehouse AS warehouse,
                       g.brand_name AS brand_name, g.category_name AS category_name,
                       g.storage_property AS storage_property
                """;

        plan.fromWhere.append("""
                FROM v_rpt_sales_detail v
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
        appendLike(req, plan, "driver", "v.driver");
        String customer = req.text("customer");
        if (customer != null) {
            plan.fromWhere.append(" AND (v.customer_code LIKE ? OR v.customer_name LIKE ?) ");
            plan.args.add("%" + customer + "%");
            plan.args.add("%" + customer + "%");
        }
        appendLike(req, plan, "salesman", "v.salesman");
        appendEq(req, plan, "territory", "v.territory");
        appendEq(req, plan, "routeLine", "v.route_line");
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
                "billDate", "bill_date", "billNo", "bill_no", "customerName", "customer_name",
                "goodsCode", "goods_code", "baseQty", "base_qty", "amount", "amount"));
        plan.defaultOrder = "ORDER BY bill_date DESC, bill_no ASC, goods_code ASC";

        plan.grandSummarySelect = """
                SELECT COUNT(*) AS line_count,
                       COALESCE(SUM(v.base_qty),0) AS base_qty,
                       COALESCE(SUM(v.package_qty),0) AS package_qty,
                       COALESCE(SUM(v.amount),0) AS amount,
                       COALESCE(SUM(v.cost_amount),0) AS cost_amount,
                       COALESCE(SUM(v.gross_profit),0) AS gross_profit,
                       COALESCE(SUM(CASE WHEN v.bill_type = '销售签收' THEN v.base_qty ELSE 0 END),0) AS signed_qty_base,
                       COALESCE(SUM(CASE WHEN v.bill_type = '销售退货' THEN v.base_qty ELSE 0 END),0) AS return_qty_base,
                       COALESCE(SUM(CASE WHEN v.bill_type = '销售签收' THEN v.amount ELSE 0 END),0) AS signed_amount,
                       COALESCE(SUM(CASE WHEN v.bill_type = '销售退货' THEN v.amount ELSE 0 END),0) AS return_amount
                """;
        plan.maskOverrides.putAll(Map.of(
                "unitPriceBase", "VIEW_SALE_PRICE",
                "boxPrice", "VIEW_SALE_PRICE",
                "amount", "VIEW_SALE_AMOUNT",
                "signedAmount", "VIEW_SALE_AMOUNT",
                "returnAmount", "VIEW_SALE_AMOUNT",
                "unitCostBase", "VIEW_COST",
                "costAmount", "VIEW_COST_AMOUNT",
                "grossProfit", "VIEW_PROFIT",
                "grossProfitRate", "VIEW_PROFIT"));
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
        boolean includeSign = billType == null || "销售签收".equals(billType);
        boolean includeRet = billType == null || "销售退货".equals(billType);

        // 数据范围按「分支 × 计数/取页」拆：头级客户/业务员，行级仓库/商品。任一 deny 即全 deny。
        var scopeSH = dataScope.target().customer("h.customer_name")
                .salesman("COALESCE(so.salesman, '')").build();
        var scopeRH = dataScope.target().customer("h.customer_name")
                .salesman("COALESCE(so.salesman, dp.default_owner, '')").build();
        var scopeSL0 = dataScope.target().warehouse("h.warehouse")
                .goodsColumn("d.goods_code").build();
        var scopeRL0 = dataScope.target().warehouse("h.warehouse")
                .goodsColumn("d.goods_code").build();
        var scopeSZ = dataScope.target().warehouse("hdr.header_warehouse")
                .goodsColumn("d.goods_code").build();
        var scopeRZ = dataScope.target().warehouse("hdr.header_warehouse")
                .goodsColumn("d.goods_code").build();
        if (scopeSH.isDenyAll() || scopeRH.isDenyAll() || scopeSL0.isDenyAll()
                || scopeRL0.isDenyAll() || scopeSZ.isDenyAll() || scopeRZ.isDenyAll()) {
            plan.denyAll();
            return;
        }

        // h0 片段与参数按 SQL 出现顺序同步产出（先计数子查询内行过滤，后头 WHERE）
        StringBuilder h0Sql = new StringBuilder("WITH h0 AS (\n");
        List<Object> countArgs = new ArrayList<>();
        boolean first = true;
        if (includeSign) {
            h0Sql.append(signHeaderCte(req, scopeSH, scopeSL0, countArgs));
            first = false;
        }
        if (includeRet) {
            if (!first) h0Sql.append("  UNION ALL\n");
            h0Sql.append(returnHeaderCte(req, scopeRH, scopeRL0, countArgs));
        }
        h0Sql.append(")\n");
        String countSql = SqlLiterals.render(
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
                List<Object> args = new ArrayList<>(countArgs);
                StringBuilder sql = new StringBuilder(h0Sql);
                sql.append(hdrCte).append(", z AS (\n");
                boolean[] firstBranch = {true};
                if (includeSign) {
                    sql.append(signZCte(req, offset, limit, scopeSZ, args));
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
                return SqlLiterals.render(sql.toString(), args);
            }
            @Override public List<Object> listArgs(long offset, int limit) { return List.of(); }
        };
    }

    /**
     * h0 签收手分支。头级属性（业务员/区域/路线）按头 JOIN 解析，行级过滤进相关计数。
     * 参数顺序：计数子查询行过滤+行数据范围 → 日期区间 → 头过滤 → 头数据范围。
     */
    private static String signHeaderCte(ReportQueryRequest req,
                                        DataScopeService.ScopeClause hScope,
                                        DataScopeService.ScopeClause lScope,
                                        List<Object> args) {
        StringBuilder sb = new StringBuilder();
        sb.append("  SELECT 'S' AS doc_type, h.receipt_id AS doc_id, h.receipt_no AS bill_no,\n");
        sb.append("         CAST(h.sign_time AS DATE) AS bill_date,\n");
        sb.append("         h.source_order_no AS source_bill_no,\n");
        sb.append("         h.source_outbound_no AS source_outbound_no,\n");
        sb.append("         COALESCE(h.customer_code, '') AS customer_code,\n");
        sb.append("         h.customer_name AS customer_name,\n");
        sb.append("         COALESCE(so.salesman, '') AS salesman,\n");
        sb.append("         COALESCE(NULLIF(h.sign_user, ''),\n");
        sb.append("           (SELECT de.employee_name FROM tms_dispatch td\n");
        sb.append("             JOIN base_employee de ON de.employee_id = td.driver_id\n");
        sb.append("            WHERE td.dispatch_id = h.dispatch_id),\n");
        sb.append("           h.driver, '') AS driver,\n");
        sb.append("         COALESCE(ob.territory, '') AS territory,\n");
        sb.append("         COALESCE(ob.route_line, '') AS route_line,\n");
        sb.append("         h.warehouse AS header_warehouse,\n");
        sb.append("         (SELECT COUNT(*) FROM sales_receipt_detail d\n");
        sb.append("          LEFT JOIN rpt_dim_goods gg ON gg.goods_code = d.goods_code\n");
        sb.append("          WHERE d.receipt_id = h.receipt_id AND d.signed_qty > 0\n");
        appendLinePreds(req, sb, args, "d", "gg", "h.warehouse");
        lScope.appendTo(sb, args);
        sb.append("          ) AS line_cnt\n");
        sb.append("  FROM sales_receipt h\n");
        sb.append("  LEFT JOIN sales_order so ON so.order_no = h.source_order_no\n");
        sb.append("  LEFT JOIN sales_outbound ob ON ob.outbound_no = h.source_outbound_no\n");
        sb.append("  WHERE h.status = 'APPROVED'\n");
        sb.append("    AND h.sign_status IN ('已签收', '部分拒收')\n");
        sb.append("    AND h.sign_time IS NOT NULL\n");
        sb.append("    AND CAST(h.sign_time AS DATE) BETWEEN ? AND ?\n");
        args.add(req.range().startDate());
        args.add(req.range().endDate());
        appendHeaderPreds(req, sb, args, "h.receipt_no", "h.source_order_no",
                "COALESCE(h.customer_code, '')", "h.customer_name",
                "COALESCE(so.salesman, '')",
                "COALESCE(NULLIF(h.sign_user, ''), h.driver, '')",
                "COALESCE(ob.territory, '')", "COALESCE(ob.route_line, '')",
                "h.warehouse");
        hScope.appendTo(sb, args);
        return sb.toString();
    }

    /** h0 退货头分支。参数顺序同签收手。 */
    private static String returnHeaderCte(ReportQueryRequest req,
                                          DataScopeService.ScopeClause hScope,
                                          DataScopeService.ScopeClause lScope,
                                          List<Object> args) {
        StringBuilder sb = new StringBuilder();
        sb.append("  SELECT 'R' AS doc_type, h.inbound_id AS doc_id, h.inbound_no AS bill_no,\n");
        sb.append("         h.bill_date AS bill_date,\n");
        sb.append("         h.source_apply_no AS source_bill_no,\n");
        sb.append("         CAST(NULL AS VARCHAR(50)) AS source_outbound_no,\n");
        sb.append("         COALESCE(h.customer_code, '') AS customer_code,\n");
        sb.append("         h.customer_name AS customer_name,\n");
        sb.append("         COALESCE(so.salesman, dp.default_owner, '') AS salesman,\n");
        sb.append("         COALESCE(ra.driver_name, '') AS driver,\n");
        sb.append("         COALESCE(ob.territory, '') AS territory,\n");
        sb.append("         COALESCE(ob.route_line, '') AS route_line,\n");
        sb.append("         h.warehouse AS header_warehouse,\n");
        sb.append("         (SELECT COUNT(*) FROM sales_return_inbound_detail d\n");
        sb.append("          LEFT JOIN rpt_dim_goods gg ON gg.goods_code = d.goods_code\n");
        sb.append("          WHERE d.inbound_id = h.inbound_id\n");
        appendLinePreds(req, sb, args, "d", "gg", "h.warehouse");
        lScope.appendTo(sb, args);
        sb.append("          ) AS line_cnt\n");
        sb.append("  FROM sales_return_inbound h\n");
        sb.append("  LEFT JOIN sales_return_apply ra ON ra.apply_no = h.source_apply_no\n");
        sb.append("  LEFT JOIN sales_outbound ob ON ob.outbound_no = ra.source_outbound_no\n");
        sb.append("  LEFT JOIN sales_order so ON so.order_no = ob.source_order\n");
        sb.append("  LEFT JOIN rpt_dim_partner dp ON dp.partner_type = 'CUSTOMER'\n");
        sb.append("         AND dp.partner_code = h.customer_code\n");
        sb.append("  WHERE h.status = 'APPROVED' AND h.stock_updated = TRUE\n");
        sb.append("    AND h.bill_date BETWEEN ? AND ?\n");
        args.add(req.range().startDate());
        args.add(req.range().endDate());
        appendHeaderPreds(req, sb, args, "h.inbound_no", "h.source_apply_no",
                "COALESCE(h.customer_code, '')", "h.customer_name",
                "COALESCE(so.salesman, dp.default_owner, '')",
                "COALESCE(ra.driver_name, '')",
                "COALESCE(ob.territory, '')", "COALESCE(ob.route_line, '')",
                "h.warehouse");
        hScope.appendTo(sb, args);
        return sb.toString();
    }

    /** z 签收分支：只取与本页行号区间相交的单据；参数顺序：区间边界 2 个 → 行过滤 → 行数据范围。 */
    private static String signZCte(ReportQueryRequest req, long offset, long limit,
                                   DataScopeService.ScopeClause zScope, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        sb.append("  SELECT 'S' AS doc_type, hdr.doc_id, hdr.bill_no, hdr.bill_date,\n");
        sb.append("         hdr.source_bill_no, hdr.customer_code, hdr.customer_name,\n");
        sb.append("         hdr.salesman, hdr.driver, hdr.territory, hdr.route_line,\n");
        sb.append("         hdr.header_warehouse,\n");
        sb.append("         d.goods_code, d.goods_name, d.unit_name AS order_unit,\n");
        sb.append("         d.signed_qty AS order_qty,\n");
        sb.append("         d.sign_amount AS raw_amount,\n");
        // K8 签收行成本：对应出库单同商品成本 − JSRK 拒收明细成本
        sb.append("         COALESCE((SELECT SUM(od.cost_amount)\n");
        sb.append("                     FROM sales_outbound o\n");
        sb.append("                     JOIN sales_outbound_detail od ON od.outbound_id = o.outbound_id\n");
        sb.append("                    WHERE o.outbound_no = hdr.source_outbound_no\n");
        sb.append("                      AND od.goods_code = d.goods_code), 0)\n");
        sb.append("           - COALESCE((SELECT SUM(jd.cost_amount)\n");
        sb.append("                        FROM inv_reject_inbound_detail jd\n");
        sb.append("                       WHERE jd.source_detail_id = d.detail_id), 0) AS raw_cost,\n");
        sb.append("         pfm.factor_qty AS factor_raw,\n");
        sb.append("         dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,\n");
        sb.append("         dg.base_unit AS base_unit, dg.barcode AS barcode,\n");
        sb.append("         dg.brand_name AS brand_name, dg.category_name AS category_name,\n");
        sb.append("         dg.storage_property AS storage_property,\n");
        sb.append("         hdr.cum_end - hdr.line_cnt\n");
        sb.append("           + ROW_NUMBER() OVER (PARTITION BY hdr.doc_type, hdr.doc_id\n");
        sb.append("                                ORDER BY d.goods_code ASC, d.detail_id ASC) AS gidx\n");
        sb.append("  FROM hdr\n");
        sb.append("  JOIN sales_receipt_detail d ON d.receipt_id = hdr.doc_id AND d.signed_qty > 0\n");
        sb.append("  LEFT JOIN rpt_dim_unit_factor pfm\n");
        sb.append("         ON pfm.biz_type = 'S' AND pfm.goods_code = d.goods_code\n");
        sb.append("            AND pfm.unit_name = d.unit_name\n");
        sb.append("  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = d.goods_code\n");
        sb.append("  WHERE hdr.doc_type = 'S' AND hdr.cum_end > ? AND hdr.cum_end - hdr.line_cnt < ?\n");
        args.add(offset);
        args.add(limit);
        appendLinePreds(req, sb, args, "d", "dg", "hdr.header_warehouse");
        zScope.appendTo(sb, args);
        return sb.toString();
    }

    /** z 退货分支：数量/金额/成本取负，成本为审核时移动加权成本；参数顺序同签收分支。 */
    private static String returnZCte(ReportQueryRequest req, long offset, long limit,
                                     DataScopeService.ScopeClause zScope, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        sb.append("  SELECT 'R' AS doc_type, hdr.doc_id, hdr.bill_no, hdr.bill_date,\n");
        sb.append("         hdr.source_bill_no, hdr.customer_code, hdr.customer_name,\n");
        sb.append("         hdr.salesman, hdr.driver, hdr.territory, hdr.route_line,\n");
        sb.append("         hdr.header_warehouse,\n");
        sb.append("         d.goods_code, d.goods_name, d.unit_name AS order_unit,\n");
        sb.append("         -d.qty AS order_qty,\n");
        sb.append("         -d.amount AS raw_amount,\n");
        sb.append("         -COALESCE(d.cost_amount, 0) AS raw_cost,\n");
        sb.append("         pfm.factor_qty AS factor_raw,\n");
        sb.append("         dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,\n");
        sb.append("         dg.base_unit AS base_unit, dg.barcode AS barcode,\n");
        sb.append("         dg.brand_name AS brand_name, dg.category_name AS category_name,\n");
        sb.append("         dg.storage_property AS storage_property,\n");
        sb.append("         hdr.cum_end - hdr.line_cnt\n");
        sb.append("           + ROW_NUMBER() OVER (PARTITION BY hdr.doc_type, hdr.doc_id\n");
        sb.append("                                ORDER BY d.goods_code ASC, d.detail_id ASC) AS gidx\n");
        sb.append("  FROM hdr\n");
        sb.append("  JOIN sales_return_inbound_detail d ON d.inbound_id = hdr.doc_id\n");
        sb.append("  LEFT JOIN rpt_dim_unit_factor pfm\n");
        sb.append("         ON pfm.biz_type = 'S' AND pfm.goods_code = d.goods_code\n");
        sb.append("            AND pfm.unit_name = d.unit_name\n");
        sb.append("  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = d.goods_code\n");
        sb.append("  WHERE hdr.doc_type = 'R' AND hdr.cum_end > ? AND hdr.cum_end - hdr.line_cnt < ?\n");
        args.add(offset);
        args.add(limit);
        appendLinePreds(req, sb, args, "d", "dg", "hdr.header_warehouse");
        zScope.appendTo(sb, args);
        return sb.toString();
    }

    /** z 外层最终投影：factor 兜底、基本数量/件数/单价/箱价/成本/毛利派生（与 DWD 视图同口径）。 */
    private static String windowFinalSelect() {
        String factor = "COALESCE(z.factor_raw, CASE WHEN z.order_unit = z.large_unit"
                + " THEN z.large_convert_qty END, 1)";
        String baseQty = "z.order_qty * " + factor;
        String pkgQty = "CASE WHEN COALESCE(z.large_convert_qty, 0) > 0 THEN "
                + baseQty + " / z.large_convert_qty ELSE " + baseQty + " END";
        return """
                SELECT z.bill_no AS bill_no, z.bill_date AS bill_date,
                       CASE z.doc_type WHEN 'S' THEN '销售签收' WHEN 'R' THEN '销售退货' END AS bill_type,
                       z.source_bill_no AS source_bill_no,
                       z.driver AS driver,
                       z.customer_code AS customer_code, z.customer_name AS customer_name,
                       z.salesman AS salesman, z.territory AS territory, z.route_line AS route_line,
                       z.goods_code AS goods_code, z.goods_name AS goods_name,
                       z.barcode AS barcode, z.base_unit AS base_unit,
                       %1$s AS base_qty,
                       CASE WHEN %1$s = 0 THEN NULL
                            ELSE z.raw_amount / (%1$s) END AS unit_price_base,
                       %2$s AS package_qty,
                       z.large_unit AS large_unit,
                       CASE WHEN %2$s = 0 THEN NULL
                            ELSE z.raw_amount / (%2$s) END AS box_price,
                       z.raw_amount AS amount,
                       CASE WHEN %1$s = 0 THEN NULL
                            ELSE z.raw_cost / (%1$s) END AS unit_cost_base,
                       z.raw_cost AS cost_amount,
                       z.raw_amount - z.raw_cost AS gross_profit,
                       CASE WHEN z.raw_amount = 0 THEN NULL
                            ELSE (z.raw_amount - z.raw_cost) / z.raw_amount END AS gross_profit_rate,
                       z.header_warehouse AS warehouse,
                       z.brand_name AS brand_name, z.category_name AS category_name,
                       z.storage_property AS storage_property
                """.formatted(baseQty, pkgQty);
    }

    // ============================ 窗口路径过滤片段（SQL 与参数同步追加） ============================

    /** 头级过滤：单号/订单号/客户/业务员/司机 LIKE，区域/路线/仓库 =。 */
    private static void appendHeaderPreds(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                                          String billNoCol, String sourceCol,
                                          String customerCodeCol, String customerNameCol,
                                          String salesmanExpr, String driverExpr,
                                          String territoryExpr, String routeLineExpr,
                                          String warehouseCol) {
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
        String customer = req.text("customer");
        if (customer != null) {
            sql.append("  AND (").append(customerCodeCol).append(" LIKE ? OR ")
               .append(customerNameCol).append(" LIKE ?)\n");
            args.add("%" + customer + "%");
            args.add("%" + customer + "%");
        }
        String salesman = req.text("salesman");
        if (salesman != null) {
            sql.append("  AND ").append(salesmanExpr).append(" LIKE ?\n");
            args.add("%" + salesman + "%");
        }
        String driver = req.text("driver");
        if (driver != null) {
            sql.append("  AND ").append(driverExpr).append(" LIKE ?\n");
            args.add("%" + driver + "%");
        }
        String territory = req.text("territory");
        if (territory != null) {
            sql.append("  AND ").append(territoryExpr).append(" = ?\n");
            args.add(territory);
        }
        String routeLine = req.text("routeLine");
        if (routeLine != null) {
            sql.append("  AND ").append(routeLineExpr).append(" = ?\n");
            args.add(routeLine);
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append("  AND ").append(warehouseCol).append(" = ?\n");
            args.add(warehouse);
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
