package com.erp.report.inventory;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.common.security.datascope.DataScopeService.ScopeClause;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.common.SqlLiterals;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 报表6｜缺货商品分析（实时缺货 + 缺货历史 + 缺货趋势，filters.tab 切换）。
 *
 * <p>判定缺货（满足任一）：① 可用+在途 &le; 0；② 可用+在途 &lt; 库存下限
 * （base_goods.stock_lower_limit，系统无独立安全库存字段）；③ 可销天数
 * = 可用 ÷ 近 30 天签收日均 &lt; 主供应商交货周期 base_supplier.delivery_days。
 * <ul>
 *   <li>tab=realtime（默认）：库存/在途实时取数（在途口径同 #5 采购预测：
 *       已审核未终止采购订单行 − 已审核入库实收），日均取最近一天日结快照，
 *       建议补货量 = 下限 + 日均×交货周期 − 可用 − 在途；连续缺货天数按快照回推；</li>
 *   <li>tab=history：按期间扫 inv_stock_daily_snapshot，输出商品×仓的缺货天数、
 *       最长连续缺货天数（ROW_NUMBER 同号分段）、估算流失销量
 *       （=断货段内最高日均（近似断货前日均）×连续天数）；</li>
 *   <li>tab=trend：按日×仓输出缺货 SKU 数，前端折线。</li>
 * </ul>
 * Q6：测试/预发同样跑日结快照积累，不做流水倒推。仓库+商品数据范围隔离。
 */
@Component
public class ShortageAnalysisDefinition implements ReportDefinition {

    private final DataScopeService dataScope;

    public ShortageAnalysisDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "shortage_analysis"; }
    @Override public String name() { return "缺货商品分析"; }
    @Override public String viewPerm() { return "report.shortage_analysis.view"; }
    @Override public boolean dws() { return true; }
    @Override public boolean summaryReport() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        return List.of(
                ReportColumnDef.dim("snapshotDate", "日期"),
                ReportColumnDef.dim("warehouse", "仓库"),
                ReportColumnDef.dim("goodsCode", "商品编号"),
                ReportColumnDef.dim("goodsName", "商品名称"),
                ReportColumnDef.dim("barcode", "条码"),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("categoryName", "商品类别"),
                ReportColumnDef.dim("storageProperty", "存储属性"),
                ReportColumnDef.dim("baseUnit", "单位"),
                ReportColumnDef.dim("supplierCode", "主供应商编号"),
                ReportColumnDef.dim("supplierName", "主供应商"),
                ReportColumnDef.dim("buyer", "采购员"),
                ReportColumnDef.measure("physicalQty", "实物数量", null),
                ReportColumnDef.measure("availableQty", "可用数量", null),
                ReportColumnDef.measure("onWayQty", "采购在途", null),
                ReportColumnDef.measure("lowerLimit", "库存下限", null),
                ReportColumnDef.measure("upperLimit", "库存上限", null),
                ReportColumnDef.measure("salesQty7d", "近7天签收销量", null),
                ReportColumnDef.measure("salesQty30d", "近30天签收销量", null),
                ReportColumnDef.measure("avgDailySales", "日均销量", null),
                ReportColumnDef.measure("coverDays", "可销天数", null),
                ReportColumnDef.measure("deliveryDays", "交货周期(天)", null),
                ReportColumnDef.measure("suggestedQty", "建议补货量", null),
                ReportColumnDef.measure("shortDays", "缺货天数", null),
                ReportColumnDef.measure("consecShortDays", "连续缺货天数", null),
                ReportColumnDef.measure("lostSalesQty", "估算流失销量", null),
                ReportColumnDef.measure("shortSkuCount", "缺货SKU数", null));
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        // 数据范围配置本身与别名无关，先做 deny-all 判定；各模式内按实际表别名挂列
        ScopeClause probe = dataScope.target().warehouse("k.warehouse").goodsColumn("k.goods_code").build();
        if (probe.isDenyAll()) return plan.denyAll();

        String tab = req.text("tab");
        if (tab == null) tab = "realtime";
        List<Object> args = new ArrayList<>();

        if ("history".equals(tab)) {
            buildHistory(req, plan, args, false);
        } else if ("trend".equals(tab)) {
            buildHistory(req, plan, args, true);
        } else {
            buildRealtime(req, plan, args);
        }
        plan.args.addAll(args);
        return plan;
    }

    // ============================ 实时缺货 ============================

    private void buildRealtime(ReportQueryRequest req, Plan plan, List<Object> args) {
        ScopeClause scope = dataScope.target()
                .warehouse("k.warehouse").goodsColumn("k.goods_code").build();
        if (scope.isDenyAll()) { plan.denyAll(); return; }
        // k = 商品×仓键集（现有库存 ∪ 未结采购在途 ∪ 商品默认仓），左联实时/快照/档案
        StringBuilder cte = new StringBuilder("""
                WITH k AS (
                    SELECT goods_code, warehouse FROM inv_stock_balance GROUP BY goods_code, warehouse
                    UNION
                    SELECT pod.goods_code, po.warehouse
                      FROM purchase_order po
                      JOIN purchase_order_detail pod ON pod.order_id = po.order_id
                      LEFT JOIN (
                          SELECT ih.source_order AS rcv_order, idg.goods_code AS rcv_goods,
                                 COALESCE(idg.unit_name, '') AS rcv_unit,
                                 SUM(idg.received_qty) AS recv_qty
                            FROM pur_inbound ih
                            JOIN pur_inbound_detail idg ON idg.inbound_id = ih.inbound_id
                           WHERE ih.status = 'APPROVED'
                           GROUP BY ih.source_order, idg.goods_code, COALESCE(idg.unit_name, '')
                      ) rcv ON rcv.rcv_order = po.order_no AND rcv.rcv_goods = pod.goods_code
                          AND rcv.rcv_unit = COALESCE(pod.unit_name, '')
                     WHERE po.status IN ('APPROVED', 'AUDITED')
                       AND (COALESCE(NULLIF(pod.base_qty, 0), pod.qty * pod.convert_qty)
                            - COALESCE(rcv.recv_qty * COALESCE(NULLIF(pod.convert_qty, 0), 1), 0)) > 0
                     GROUP BY pod.goods_code, po.warehouse
                    UNION
                    SELECT goods_code, default_warehouse
                      FROM base_goods
                     WHERE default_warehouse IS NOT NULL AND default_warehouse <> ''
                       AND COALESCE(can_purchase, TRUE) = TRUE
                ),
                rt AS (
                SELECT k.goods_code AS goods_code, k.warehouse AS warehouse,
                       bg.goods_name AS goods_name, dg.barcode AS barcode,
                       dg.brand_name AS brand_name, dg.category_name AS category_name,
                       dg.storage_property AS storage_property,
                       COALESCE(dg.base_unit, bg.base_unit) AS base_unit,
                       sup.supplier_code AS supplier_code,
                       COALESCE(bg.default_supplier, '') AS supplier_name,
                       COALESCE(sup.default_buyer, '') AS buyer,
                       COALESCE(bal.physical_qty, 0) AS physical_qty,
                       COALESCE(bal.available_qty, 0) AS available_qty,
                       COALESCE(ow.qty, 0) AS on_way_qty,
                       COALESCE(bg.stock_lower_limit, 0) AS lower_limit,
                       COALESCE(bg.stock_upper_limit, 0) AS upper_limit,
                       COALESCE(sn.sales_qty_7d, 0) AS sales_qty_7d,
                       COALESCE(sn.sales_qty_30d, 0) AS sales_qty_30d,
                       COALESCE(sn.avg_daily_sales, 0) AS avg_daily_sales,
                       CASE WHEN COALESCE(sn.avg_daily_sales, 0) > 0
                            THEN ROUND(CAST(COALESCE(bal.available_qty, 0) AS DOUBLE) / sn.avg_daily_sales, 1)
                            END AS cover_days,
                       COALESCE(sup.delivery_days, 0) AS delivery_days,
                       GREATEST(ROUND(COALESCE(bg.stock_lower_limit, 0)
                           + COALESCE(sn.avg_daily_sales, 0) * COALESCE(sup.delivery_days, 0)
                           - COALESCE(bal.available_qty, 0) - COALESCE(ow.qty, 0), 0), 0) AS suggested_qty
                  FROM k
                  JOIN base_goods bg ON bg.goods_code = k.goods_code
                  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = k.goods_code
                  LEFT JOIN base_supplier sup ON sup.supplier_name = bg.default_supplier
                  LEFT JOIN (
                      SELECT goods_code, warehouse,
                             SUM(physical_qty) AS physical_qty, SUM(available_qty) AS available_qty
                        FROM inv_stock_balance GROUP BY goods_code, warehouse
                  ) bal ON bal.goods_code = k.goods_code AND bal.warehouse = k.warehouse
                  LEFT JOIN (
                      SELECT pod.goods_code AS goods_code, po.warehouse AS warehouse,
                             SUM(COALESCE(NULLIF(pod.base_qty, 0), pod.qty * pod.convert_qty)
                                 - COALESCE(rcv.recv_qty * COALESCE(NULLIF(pod.convert_qty, 0), 1), 0)) AS qty
                        FROM purchase_order po
                        JOIN purchase_order_detail pod ON pod.order_id = po.order_id
                        LEFT JOIN (
                            SELECT ih.source_order AS rcv_order, idg.goods_code AS rcv_goods,
                                   COALESCE(idg.unit_name, '') AS rcv_unit,
                                   SUM(idg.received_qty) AS recv_qty
                              FROM pur_inbound ih
                              JOIN pur_inbound_detail idg ON idg.inbound_id = ih.inbound_id
                             WHERE ih.status = 'APPROVED'
                             GROUP BY ih.source_order, idg.goods_code, COALESCE(idg.unit_name, '')
                        ) rcv ON rcv.rcv_order = po.order_no AND rcv.rcv_goods = pod.goods_code
                            AND rcv.rcv_unit = COALESCE(pod.unit_name, '')
                       WHERE po.status IN ('APPROVED', 'AUDITED')
                       GROUP BY pod.goods_code, po.warehouse
                  ) ow ON ow.goods_code = k.goods_code AND ow.warehouse = k.warehouse
                  LEFT JOIN inv_stock_daily_snapshot sn
                         ON sn.goods_code = k.goods_code AND sn.warehouse = k.warehouse
                        AND sn.snapshot_date = (SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot)
                 WHERE 1=1
                """);
        appendShortPredicate(req, cte, "COALESCE(bal.available_qty,0) + COALESCE(ow.qty,0)",
                "COALESCE(bg.stock_lower_limit,0)", "COALESCE(sup.delivery_days,0)",
                "COALESCE(sn.avg_daily_sales,0)");
        appendArchiveFilters(req, cte, args, "k", "bg", "dg");
        appendEntityFilters(req, cte, args, "bg", "sup");
        scope.appendTo(cte, args);
        cte.append(") ");
        // CTE 部分参数此刻已固定，外层 HAVING 参数晚于它绑定，合计只复用 CTE 参数
        List<Object> cteArgs = List.copyOf(args);

        // 外层补算连续缺货天数（按日结快照回推；无可比阈值的商品不参与）
        String listSql = cte + """
                SELECT CAST((SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot) AS DATE) AS snapshot_date,
                       rt.warehouse AS warehouse, rt.goods_code AS goods_code,
                       rt.goods_name AS goods_name, rt.barcode AS barcode,
                       rt.brand_name AS brand_name, rt.category_name AS category_name,
                       rt.storage_property AS storage_property, rt.base_unit AS base_unit,
                       rt.supplier_code AS supplier_code, rt.supplier_name AS supplier_name,
                       rt.buyer AS buyer,
                       rt.physical_qty AS physical_qty, rt.available_qty AS available_qty,
                       rt.on_way_qty AS on_way_qty, rt.lower_limit AS lower_limit,
                       rt.upper_limit AS upper_limit, rt.sales_qty_7d AS sales_qty_7d,
                       rt.sales_qty_30d AS sales_qty_30d, rt.avg_daily_sales AS avg_daily_sales,
                       rt.cover_days AS cover_days, rt.delivery_days AS delivery_days,
                       rt.suggested_qty AS suggested_qty,
                       CASE WHEN rt.lower_limit = 0 AND rt.delivery_days = 0 THEN 0 ELSE COALESCE((
                           SELECT CASE WHEN MAX(nz.snapshot_date) IS NULL
                                  THEN (SELECT COUNT(*) FROM inv_stock_daily_snapshot z
                                         WHERE z.goods_code = rt.goods_code
                                           AND z.warehouse = rt.warehouse
                                           AND z.snapshot_date <=
                                               (SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot))
                                  ELSE DATEDIFF('DAY', MAX(nz.snapshot_date),
                                               (SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot))
                                  END
                             FROM inv_stock_daily_snapshot nz
                            WHERE nz.goods_code = rt.goods_code AND nz.warehouse = rt.warehouse
                              AND nz.snapshot_date <= (SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot)
                              AND nz.available_qty + COALESCE(nz.purchase_on_way, 0) >= rt.lower_limit
                              AND (nz.avg_daily_sales <= 0 OR rt.delivery_days <= 0
                                   OR CAST(nz.available_qty AS DOUBLE) / NULLIF(nz.avg_daily_sales, 0) >= rt.delivery_days)
                       ), 0) END AS short_days,
                       CASE WHEN rt.lower_limit = 0 AND rt.delivery_days = 0 THEN 0 ELSE COALESCE((
                           SELECT CASE WHEN MAX(nz.snapshot_date) IS NULL
                                  THEN (SELECT COUNT(*) FROM inv_stock_daily_snapshot z
                                         WHERE z.goods_code = rt.goods_code
                                           AND z.warehouse = rt.warehouse
                                           AND z.snapshot_date <=
                                               (SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot))
                                  ELSE DATEDIFF('DAY', MAX(nz.snapshot_date),
                                               (SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot))
                                  END
                             FROM inv_stock_daily_snapshot nz
                            WHERE nz.goods_code = rt.goods_code AND nz.warehouse = rt.warehouse
                              AND nz.snapshot_date <= (SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot)
                              AND nz.available_qty + COALESCE(nz.purchase_on_way, 0) >= rt.lower_limit
                              AND (nz.avg_daily_sales <= 0 OR rt.delivery_days <= 0
                                   OR CAST(nz.available_qty AS DOUBLE) / NULLIF(nz.avg_daily_sales, 0) >= rt.delivery_days)
                       ), 0) END AS consec_short_days,
                       ROUND(rt.avg_daily_sales * CASE WHEN rt.lower_limit = 0 AND rt.delivery_days = 0 THEN 0 ELSE COALESCE((
                           SELECT CASE WHEN MAX(nz.snapshot_date) IS NULL
                                  THEN (SELECT COUNT(*) FROM inv_stock_daily_snapshot z
                                         WHERE z.goods_code = rt.goods_code
                                           AND z.warehouse = rt.warehouse
                                           AND z.snapshot_date <=
                                               (SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot))
                                  ELSE DATEDIFF('DAY', MAX(nz.snapshot_date),
                                               (SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot))
                                  END
                             FROM inv_stock_daily_snapshot nz
                            WHERE nz.goods_code = rt.goods_code AND nz.warehouse = rt.warehouse
                              AND nz.snapshot_date <= (SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot)
                              AND nz.available_qty + COALESCE(nz.purchase_on_way, 0) >= rt.lower_limit
                              AND (nz.avg_daily_sales <= 0 OR rt.delivery_days <= 0
                                   OR CAST(nz.available_qty AS DOUBLE) / NULLIF(nz.avg_daily_sales, 0) >= rt.delivery_days)
                       ), 0) END, 1) AS lost_sales_qty,
                       CAST(0 AS INT) AS short_sku_count
                  FROM rt
                """;
        Integer minConsec = intOrNull(req.text("minConsecDays"));
        if (minConsec != null) {
            listSql += " WHERE 1=1 AND consec_short_days >= ? ";
            args.add(minConsec);
        }
        plan.detailSelect = "";
        plan.fromWhere.append(listSql);
        plan.defaultOrder = "ORDER BY consec_short_days DESC, suggested_qty DESC, goods_code ASC";
        plan.sortWhitelist = new java.util.LinkedHashMap<>(java.util.Map.of(
                "suggestedQty", "suggested_qty",
                "consecShortDays", "consec_short_days",
                "coverDays", "cover_days",
                "availableQty", "available_qty"));

        // 合计（实时）：缺货 SKU 数、可用/在途/建议量合计
        plan.grandSql = cte + """
                SELECT COUNT(*) AS short_sku_count,
                       COALESCE(SUM(rt.available_qty),0) AS available_qty,
                       COALESCE(SUM(rt.on_way_qty),0) AS on_way_qty,
                       COALESCE(SUM(rt.suggested_qty),0) AS suggested_qty,
                       COALESCE(SUM(rt.avg_daily_sales),0) AS avg_daily_sales
                  FROM rt
                """;
        plan.grandArgs.addAll(cteArgs);
    }

    // ============================ 历史/趋势 ============================

    /** 标量取库存下限（避免在窗口 CTE 上游 JOIN，H2 2.2.224 该组合 + 绑定参数会返回空集）。 */
    private static final String LOWER_SUB =
            "(SELECT COALESCE(MAX(stock_lower_limit),0) FROM base_goods WHERE goods_code = h.goods_code)";
    /** 标量取主供应商交货周期。 */
    private static final String DELIVERY_SUB =
            "(SELECT COALESCE(MAX(sup.delivery_days),0) FROM base_supplier sup "
          + "WHERE sup.supplier_name = (SELECT default_supplier FROM base_goods WHERE goods_code = h.goods_code))";

    private void buildHistory(ReportQueryRequest req, Plan plan, List<Object> args,
                              boolean trend) {
        var start = req.range().startDate();
        var end = req.range().endDate();
        // sh = 期间内每日缺货快照行，只含快照本表（无 JOIN）：
        // 阈值用标量子查询，商品/仓库直列筛选放这里；名称类筛选与档案 JOIN 全部放到窗口之后的外层。
        StringBuilder sh = new StringBuilder("""
                WITH sh AS (
                SELECT h.snapshot_date AS snapshot_date, h.goods_code AS goods_code,
                       h.warehouse AS warehouse,
                       h.available_qty AS available_qty,
                       COALESCE(h.purchase_on_way, 0) AS on_way_qty,
                       h.avg_daily_sales AS avg_daily_sales
                  FROM inv_stock_daily_snapshot h
                 WHERE h.snapshot_date BETWEEN ? AND ?
                """);
        args.add(start);
        args.add(end);
        appendShortPredicate(req, sh, "h.available_qty + COALESCE(h.purchase_on_way,0)",
                LOWER_SUB, DELIVERY_SUB, "h.avg_daily_sales");
        // 仅快照本表列的筛选
        String goodsKw = req.text("goods");
        if (goodsKw != null) {
            sh.append(" AND h.goods_code LIKE ? ");
            args.add("%" + goodsKw + "%");
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sh.append(" AND h.warehouse = ? ");
            args.add(warehouse);
        }
        ScopeClause scope = dataScope.target()
                .warehouse("h.warehouse").goodsColumn("h.goods_code").build();
        scope.appendTo(sh, args);
        sh.append(") ");

        // 窗口之后的档案 JOIN + 名称类筛选（供三种形态复用，参数在期间参数之后绑定）
        List<Object> outerArgs = new ArrayList<>();
        String outerJoin = """
                  FROM %s x
                  JOIN base_goods bg ON bg.goods_code = x.goods_code
                  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = x.goods_code
                  LEFT JOIN base_supplier sup ON sup.supplier_name = bg.default_supplier
                """;
        StringBuilder outerWhere = new StringBuilder(" WHERE 1=1 ");
        if (goodsKw != null) {
            outerWhere.append(" AND (x.goods_code LIKE ? OR bg.goods_name LIKE ? OR dg.barcode LIKE ?) ");
            outerArgs.add("%" + goodsKw + "%");
            outerArgs.add("%" + goodsKw + "%");
            outerArgs.add("%" + goodsKw + "%");
        }
        for (var e : new String[][] {
                {"categoryName", "dg.category_name"}, {"brandName", "dg.brand_name"},
                {"storageProperty", "dg.storage_property"}}) {
            String v = req.text(e[0]);
            if (v != null) {
                outerWhere.append(" AND ").append(e[1]).append(" = ? ");
                outerArgs.add(v);
            }
        }
        String supplier = req.text("supplier");
        if (supplier != null) {
            outerWhere.append(" AND (sup.supplier_code LIKE ? OR bg.default_supplier LIKE ?) ");
            outerArgs.add("%" + supplier + "%");
            outerArgs.add("%" + supplier + "%");
        }
        String buyer = req.text("buyer");
        if (buyer != null) {
            outerWhere.append(" AND sup.default_buyer LIKE ? ");
            outerArgs.add("%" + buyer + "%");
        }

        if (trend) {
            String sql = sh + """
                    SELECT x.snapshot_date AS snapshot_date, x.warehouse AS warehouse,
                           CAST(NULL AS VARCHAR(50)) AS goods_code,
                           CAST(NULL AS VARCHAR(200)) AS goods_name,
                           CAST(NULL AS VARCHAR(100)) AS barcode,
                           CAST(NULL AS VARCHAR(100)) AS brand_name,
                           CAST(NULL AS VARCHAR(100)) AS category_name,
                           CAST(NULL AS VARCHAR(50)) AS storage_property,
                           CAST(NULL AS VARCHAR(50)) AS base_unit,
                           CAST(NULL AS VARCHAR(50)) AS supplier_code,
                           CAST(NULL AS VARCHAR(200)) AS supplier_name,
                           CAST(NULL AS VARCHAR(100)) AS buyer,
                           CAST(0 AS DECIMAL(18,2)) AS physical_qty,
                           CAST(0 AS DECIMAL(18,2)) AS available_qty,
                           CAST(0 AS DECIMAL(18,2)) AS on_way_qty,
                           CAST(0 AS DECIMAL(18,2)) AS lower_limit,
                           CAST(0 AS DECIMAL(18,2)) AS upper_limit,
                           CAST(0 AS DECIMAL(18,4)) AS sales_qty_7d,
                           CAST(0 AS DECIMAL(18,4)) AS sales_qty_30d,
                           CAST(0 AS DECIMAL(18,4)) AS avg_daily_sales,
                           CAST(NULL AS DECIMAL(18,1)) AS cover_days,
                           CAST(0 AS INT) AS delivery_days,
                           CAST(0 AS DECIMAL(18,2)) AS suggested_qty,
                           COUNT(*) AS short_days,
                           COUNT(*) AS consec_short_days,
                           CAST(0 AS DECIMAL(18,2)) AS lost_sales_qty,
                           COUNT(DISTINCT x.goods_code) AS short_sku_count
                    """ + outerJoin.formatted("sh") + outerWhere
                    + " GROUP BY x.snapshot_date, x.warehouse "
                    + " ORDER BY x.snapshot_date ASC, x.warehouse ASC";
            // 窗口/CTE 路径零参化：规避 H2「CTE 绑定参数 + 窗口结果置空」缺陷
            List<Object> allArgs = new ArrayList<>(args);
            allArgs.addAll(outerArgs);
            plan.detailSelect = SqlLiterals.render(sql, allArgs);
            plan.fromWhere.setLength(0);
            plan.defaultOrder = "";
            plan.grandSql = SqlLiterals.render(sh + """
                    SELECT COUNT(DISTINCT snapshot_date) AS trend_days,
                           COALESCE(SUM(c),0) AS short_sku_days
                      FROM (SELECT snapshot_date, warehouse, COUNT(DISTINCT goods_code) AS c
                              FROM sh GROUP BY snapshot_date, warehouse) z
                    """, new ArrayList<>(args));
            plan.grandArgs.clear();
            args.clear();
            return;
        }

        // 历史商品×仓：同号连续分段（日期 − 行号 = 段键），两层窗口 CTE 直接出段长/段最高日均
        String listSql = sh + """
                , st1 AS (
                    SELECT sh.*, DATEADD('DAY',
                               -ROW_NUMBER() OVER (PARTITION BY goods_code, warehouse
                                                   ORDER BY snapshot_date),
                               snapshot_date) AS grp_key
                      FROM sh
                ),
                st2 AS (
                    SELECT st1.*,
                           COUNT(*) OVER (PARTITION BY goods_code, warehouse, grp_key) AS streak_days,
                           MAX(avg_daily_sales) OVER (PARTITION BY goods_code, warehouse, grp_key) AS seg_max_avg
                      FROM st1
                )
                SELECT MAX(x.snapshot_date) AS snapshot_date,
                       x.warehouse AS warehouse, x.goods_code AS goods_code,
                       MAX(COALESCE(dg.goods_name, bg.goods_name)) AS goods_name,
                       MAX(dg.barcode) AS barcode,
                       MAX(dg.brand_name) AS brand_name, MAX(dg.category_name) AS category_name,
                       MAX(dg.storage_property) AS storage_property,
                       MAX(COALESCE(dg.base_unit, bg.base_unit)) AS base_unit,
                       MAX(sup.supplier_code) AS supplier_code,
                       MAX(COALESCE(bg.default_supplier, '')) AS supplier_name,
                       MAX(COALESCE(sup.default_buyer, '')) AS buyer,
                       CAST(0 AS DECIMAL(18,2)) AS physical_qty,
                       COALESCE(SUM(x.available_qty),0) AS available_qty,
                       COALESCE(SUM(x.on_way_qty),0) AS on_way_qty,
                       MAX(COALESCE(bg.stock_lower_limit,0)) AS lower_limit,
                       MAX(COALESCE(bg.stock_upper_limit,0)) AS upper_limit,
                       CAST(0 AS DECIMAL(18,4)) AS sales_qty_7d,
                       CAST(0 AS DECIMAL(18,4)) AS sales_qty_30d,
                       MAX(x.avg_daily_sales) AS avg_daily_sales,
                       CAST(NULL AS DECIMAL(18,1)) AS cover_days,
                       CAST(0 AS INT) AS delivery_days,
                       CAST(0 AS DECIMAL(18,2)) AS suggested_qty,
                       COUNT(*) AS short_days,
                       COALESCE(MAX(streak_days),0) AS consec_short_days,
                       ROUND(COALESCE(SUM(seg_max_avg),0), 1) AS lost_sales_qty,
                       CAST(0 AS INT) AS short_sku_count
                """ + outerJoin.formatted("st2") + outerWhere;
        Integer minConsec = intOrNull(req.text("minConsecDays"));
        String having = " GROUP BY x.goods_code, x.warehouse HAVING 1=1 ";
        List<Object> havingArgs = new ArrayList<>();
        if (minConsec != null) {
            having += " AND COALESCE(MAX(streak_days),0) >= ? ";
            havingArgs.add(minConsec);
        }
        // 窗口 CTE 路径零参化：规避 H2 2.2.224「CTE 绑定参数 + 窗口结果被置空」缺陷
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.addAll(outerArgs);
        allArgs.addAll(havingArgs);
        plan.detailSelect = SqlLiterals.render(
                listSql + having + " ORDER BY 25 DESC, 24 DESC, 3 ASC", allArgs);
        plan.fromWhere.setLength(0);
        plan.defaultOrder = "";
        plan.sortWhitelist = new java.util.LinkedHashMap<>(java.util.Map.of(
                "shortDays", "short_days",
                "consecShortDays", "consec_short_days",
                "lostSalesQty", "lost_sales_qty"));
        plan.grandSql = SqlLiterals.render(sh + """
                SELECT COUNT(*) AS short_sku_days,
                       COUNT(DISTINCT goods_code || '@' || warehouse) AS short_sku_count,
                       ROUND(COALESCE(SUM(avg_daily_sales),0),1) AS lost_sales_qty
                  FROM sh
                """, new ArrayList<>(args));
        plan.grandArgs.clear();
        args.clear();
    }

    // ============================ 共用片段 ============================

    /**
     * 缺货判定谓词（OR 组合）：库存为0 / 低于库存下限 / 可销天数不足。
     * shortageType=ZERO/SAFETY/COVER 时只保留对应类型，默认全部。
     */
    private void appendShortPredicate(ReportQueryRequest req, StringBuilder sql, String availExpr,
                                      String lowerExpr, String deliveryExpr, String avgExpr) {
        String type = req.text("shortageType");
        String zero = availExpr + " <= 0";
        String safety = "(" + lowerExpr + " > 0 AND " + availExpr + " < " + lowerExpr + ")";
        // CAST AS DOUBLE：H2 DECIMAL(p,2)/DECIMAL(p,4) 结果精度为负会被舍入到十位
        String cover = "(" + avgExpr + " > 0 AND " + deliveryExpr + " > 0 AND "
                + "CAST(" + availExpr + " AS DOUBLE) / NULLIF(" + avgExpr + ",0) < " + deliveryExpr + ")";
        sql.append(" AND (");
        if ("ZERO".equals(type)) sql.append(zero);
        else if ("SAFETY".equals(type)) sql.append(safety);
        else if ("COVER".equals(type)) sql.append(cover);
        else sql.append(zero).append(" OR ").append(safety).append(" OR ").append(cover);
        sql.append(") ");
    }

    private void appendArchiveFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                                      String m, String g, String d) {
        String goods = req.text("goods");
        if (goods != null) {
            sql.append(" AND (").append(m).append(".goods_code LIKE ? OR ")
               .append(g).append(".goods_name LIKE ? OR ").append(d).append(".barcode LIKE ?) ");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
        }
        for (var e : java.util.Map.of(
                "categoryName", d + ".category_name",
                "brandName", d + ".brand_name",
                "storageProperty", d + ".storage_property").entrySet()) {
            String v = req.text(e.getKey());
            if (v != null) {
                sql.append(" AND ").append(e.getValue()).append(" = ? ");
                args.add(v);
            }
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append(" AND ").append(m).append(".warehouse = ? ");
            args.add(warehouse);
        }
    }

    private void appendEntityFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                                     String g, String sup) {
        String supplier = req.text("supplier");
        if (supplier != null) {
            sql.append(" AND (").append(sup).append(".supplier_code LIKE ? OR ")
               .append(g).append(".default_supplier LIKE ?) ");
            args.add("%" + supplier + "%");
            args.add("%" + supplier + "%");
        }
        String buyer = req.text("buyer");
        if (buyer != null) {
            sql.append(" AND ").append(sup).append(".default_buyer LIKE ? ");
            args.add("%" + buyer + "%");
        }
    }

    private static Integer intOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("连续缺货天数必须是整数");
        }
    }
}
