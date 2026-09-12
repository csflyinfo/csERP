package com.erp.report.inventory;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.common.security.datascope.DataScopeService.ScopeClause;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.common.SqlLiterals;
import com.erp.report.meta.CustomPageSql;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报表9｜商品库存台账（逐笔实物账）。
 *
 * <p>取数 v_rpt_stock_move（V117 库存流水统一口径视图，实物账按 inv_stock_ledger
 * 实际记账时刻）。每个「商品+仓库+批次」分区：
 * <ul>
 *   <li>置顶一行灰色<b>期初虚拟行</b>——不扫全历史流水，按
 *       「当前 inv_batch_stock 批次快照 − 本期净发生」倒推，仅对与当前页相交的分区输出。
 *       快照必须取批次表：inv_stock_balance 是商品+仓库单行、batch_no 只记最后入库批次，
 *       多批次并存时拿它按批次关联会让非末批次分区的期初/滚算金额整体错位；</li>
 *   <li>其后逐笔流水：收入/发出数量分列、单价、金额（发出为负、成本调整带符号），
 *       结存数量直接取 ledger.balance_qty；结存金额 = 期初金额 + 窗口累计发生额
 *       （流水表无 balance_amount，按 occurred_at, ledger_id 稳定排序滚算）。</li>
 * </ul>
 *
 * <p>强制护栏：① 一次最多 {@value #MAX_SPAN_DAYS} 天；② 商品或仓库至少必选一项
 * （ledger 为年近亿级大表，不满足条件直接拒绝执行）。冲销/作废回库行
 * reversal_flag=TRUE，前端红字负数展示。
 *
 * <p>分页以流水行为计数单位（期初虚拟行不占页码）：ROW_NUMBER 全局编号 → 圈定页内
 * 流水 → UNION 回补相交分区的期初行。所有 SQL 均经 {@link SqlLiterals} 渲染为零参
 * 字面量（H2 2.2「CTE/窗口函数 + 绑定参数」缺陷规避），页/导出/合计同源。
 */
@Component
public class StockLedgerDefinition implements ReportDefinition {

    /** 台账一次最多查询天数（含端点）。 */
    public static final int MAX_SPAN_DAYS = 92;

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "CGRK", "CGSH", "CTCK", "XSCK", "THRK", "JSRK", "QTRK", "QTCK",
            "DBCK", "DBRK", "BSD", "PDD", "WMS_INBOUND", "WMS_ADJUST_GAIN", "OTHER");
    private static final Set<String> ALLOWED_DIRECTIONS = Set.of("IN", "OUT", "成本调整");

    private final DataScopeService dataScope;

    public StockLedgerDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "stock_ledger"; }
    @Override public String name() { return "商品库存台账"; }
    @Override public String viewPerm() { return "report.stock_ledger.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return false; }
    @Override public boolean naturalMonthDefault() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        return List.of(
                ReportColumnDef.dim("rowKind", "行类型"),
                ReportColumnDef.dim("moveDate", "日期"),
                ReportColumnDef.dim("billNo", "单据号"),
                ReportColumnDef.dim("baseBillNo", "原始单号"),
                ReportColumnDef.dim("billTypeText", "单据类型"),
                ReportColumnDef.dim("directionText", "方向"),
                ReportColumnDef.dim("reversalFlag", "冲销标记"),
                ReportColumnDef.dim("goodsCode", "商品编号"),
                ReportColumnDef.dim("goodsName", "商品名称"),
                ReportColumnDef.dim("warehouse", "仓库"),
                ReportColumnDef.dim("batchNo", "批次"),
                ReportColumnDef.measure("inQty", "收入数量", null),
                ReportColumnDef.measure("outQty", "发出数量", null),
                ReportColumnDef.dim("baseUnit", "单位"),
                ReportColumnDef.measure("costPrice", "单价", "VIEW_STOCK_COST"),
                ReportColumnDef.measure("signedAmount", "金额", "VIEW_STOCK_COST"),
                ReportColumnDef.measure("balanceQty", "结存数量", null),
                ReportColumnDef.measure("balanceAmount", "结存金额", "VIEW_STOCK_COST"),
                ReportColumnDef.dim("operatorName", "经办人"),
                ReportColumnDef.dim("barcode", "条码"),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("categoryName", "商品类别"),
                ReportColumnDef.dim("storageProperty", "存储属性")
        );
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        // ---- 硬性前置条件：条件不足不执行查询 ----
        if (req.text("goods") == null && req.text("warehouse") == null) {
            throw new IllegalArgumentException("请先选择商品或仓库再查询台账（至少一项）");
        }
        long span = ChronoUnit.DAYS.between(req.range().startDate(), req.range().endDate()) + 1;
        if (span > MAX_SPAN_DAYS) {
            throw new IllegalArgumentException(
                    "台账一次最多查询 92 天，请缩小日期范围（需要更长周期请用商品进销存汇总表）");
        }
        for (String t : req.texts("billTypes")) {
            if (!ALLOWED_TYPES.contains(t)) {
                throw new IllegalArgumentException("不支持的单据类型：" + t);
            }
        }
        String direction = req.text("direction");
        if (direction != null && !ALLOWED_DIRECTIONS.contains(direction)) {
            throw new IllegalArgumentException("方向只支持：IN / OUT / 成本调整");
        }

        // 四处别名各自挂数据范围：本期流水 l / 批次快照 s / 对账快照 w / mm 滚算 pm
        ScopeClause scopePeriod = dataScope.target().warehouse("l.warehouse")
                .goodsColumn("l.goods_code").build();
        ScopeClause scopeBatch = dataScope.target().warehouse("s.warehouse")
                .goodsColumn("s.goods_code").build();
        ScopeClause scopeSnap = dataScope.target().warehouse("w.warehouse")
                .goodsColumn("w.goods_code").build();
        // mm 的数据范围挂 pm（bs 仅为快照补值的 LEFT JOIN，挂 bs 会丢掉无快照的流水行）
        ScopeClause scopeCurrent = dataScope.target().warehouse("pm.warehouse")
                .goodsColumn("pm.goods_code").build();
        if (scopePeriod.isDenyAll() || scopeBatch.isDenyAll() || scopeSnap.isDenyAll()
                || scopeCurrent.isDenyAll()) {
            return new Plan().denyAll();
        }

        Plan plan = new Plan();
        plan.defaultOrder = "ORDER BY 1";
        plan.maskOverrides.putAll(Map.ofEntries(
                Map.entry("costPrice", "VIEW_STOCK_COST"),
                Map.entry("signedAmount", "VIEW_STOCK_COST"),
                Map.entry("balanceAmount", "VIEW_STOCK_COST"),
                Map.entry("openingAmount", "VIEW_STOCK_COST"),
                Map.entry("endingAmount", "VIEW_STOCK_COST")));

        // 计数：只数本期流水行（期初虚拟行不占页码），零参字面量
        List<Object> countArgs = new ArrayList<>();
        StringBuilder countSql = new StringBuilder("""
                SELECT COUNT(*)
                FROM v_rpt_stock_move l
                LEFT JOIN rpt_dim_goods gl ON gl.goods_code = l.goods_code
                WHERE l.move_date BETWEEN ? AND ?
                """);
        countArgs.add(req.range().startDate());
        countArgs.add(req.range().endDate());
        appendArchiveFilters(req, countSql, countArgs, "l", "gl", true);
        appendPeriodFilters(req, countSql, countArgs, "l");
        scopePeriod.appendTo(countSql, countArgs);

        // 合计（全区间，零参字面量）：期初/本期收入发出/期末 + 快照对账。
        // 对账数取 ss（商品+仓库粒度快照），CROSS JOIN 标量子查询避免多批次期初行重复计数。
        List<Object> grandArgs = new ArrayList<>();
        String grandSql = buildCtes(req, grandArgs, scopePeriod, scopeBatch, scopeSnap,
                                    scopeCurrent, false, 0, 0)
                + grandSelect()
                + " FROM ( "
                + buildUnion(req, grandArgs, false, 0, 0)
                + " ) x "
                + "CROSS JOIN ( "
                + "    SELECT COALESCE(SUM(s2.phys_qty), 0) AS snapshot_physical, "
                + "           COALESCE(SUM(s2.calc_qty), 0) AS snapshot_calc, "
                + "           COALESCE(SUM(s2.calc_qty - s2.phys_qty), 0) AS reconcile_diff "
                + "    FROM ss s2 "
                + ") z";

        plan.grandSql = SqlLiterals.render(grandSql, grandArgs);

        final String countLiteral = SqlLiterals.render(countSql.toString(), countArgs);
        plan.customPageSql = new CustomPageSql() {
            @Override public String countSql() { return countLiteral; }
            @Override public List<Object> countArgs() { return List.of(); }
            @Override public String listSql(long offset, int limit) {
                // 每次按页边界重建并渲染零参字面量（导出按 2000/批顺序调用，次数 ≤ 50）
                List<Object> args = new ArrayList<>();
                String sql = buildCtes(req, args, scopePeriod, scopeBatch, scopeSnap,
                                       scopeCurrent, true, offset, limit)
                        + outerSelect()
                        + " FROM ( "
                        + buildUnion(req, args, true, offset, limit)
                        + " ) x "
                        + "ORDER BY x.goods_code ASC, x.warehouse ASC, "
                        + "COALESCE(x.batch_no, '') ASC, x.sort_no ASC, "
                        + "x.occurred_at ASC, x.ledger_id ASC";
                return SqlLiterals.render(sql, args);
            }
            @Override public List<Object> listArgs(long offset, int limit) { return List.of(); }
        };
        return plan;
    }

    // ------------------------------------------------------------------
    // SQL 构造
    // ------------------------------------------------------------------

    /**
     * 语句级 CTE 前缀（H2 不允许派生表内部 WITH）：
     * pm=本期流水+全局行号；pn=分区本期净发生；bs=批次级当前快照（inv_batch_stock）；
     * ss=商品+仓库粒度对账快照；mm（仅分页路径）=页内流水+滚算结存金额。
     */
    private String buildCtes(ReportQueryRequest req, List<Object> args,
                             ScopeClause scopePeriod, ScopeClause scopeBatch, ScopeClause scopeSnap,
                             ScopeClause scopeCurrent,
                             boolean paged, long offset, int limit) {
        StringBuilder sb = new StringBuilder("""
                WITH pm AS (
                    SELECT l.ledger_id AS ledger_id, l.move_date AS move_date,
                           l.occurred_at AS occurred_at, l.bill_no AS bill_no,
                           l.base_bill_no AS base_bill_no, l.reversal_flag AS reversal_flag,
                           l.bill_type_code AS bill_type_code,
                           l.goods_code AS goods_code, l.goods_name AS goods_name,
                           l.warehouse AS warehouse, l.batch_no AS batch_no,
                           l.direction AS direction,
                           l.in_qty AS in_qty, l.out_qty AS out_qty,
                           l.cost_price AS cost_price,
                           CASE WHEN l.direction = 'OUT' THEN -l.amount ELSE l.amount END
                               AS signed_change,
                           l.in_amount AS in_amount, l.out_amount AS out_amount,
                           l.balance_qty AS balance_qty, l.operator_name AS operator_name,
                           gl.barcode AS barcode, gl.base_unit AS base_unit,
                           gl.brand_name AS gl_brand, gl.category_name AS gl_category,
                           gl.storage_property AS gl_storage,
                           ROW_NUMBER() OVER (
                               ORDER BY l.goods_code, l.warehouse,
                                        COALESCE(l.batch_no, ''), l.occurred_at, l.ledger_id
                           ) AS rn
                    FROM v_rpt_stock_move l
                    LEFT JOIN rpt_dim_goods gl ON gl.goods_code = l.goods_code
                    WHERE l.move_date BETWEEN ? AND ?
                """);
        args.add(req.range().startDate());
        args.add(req.range().endDate());
        appendArchiveFilters(req, sb, args, "l", "gl", true);
        appendPeriodFilters(req, sb, args, "l");
        scopePeriod.appendTo(sb, args);
        sb.append("""
                ),
                pn AS (
                    SELECT pm.goods_code AS goods_code, pm.warehouse AS warehouse,
                           pm.batch_no AS batch_no,
                           SUM(pm.in_qty - pm.out_qty) AS net_qty,
                           SUM(pm.in_amount - pm.out_amount) AS net_amount
                    FROM pm
                    GROUP BY pm.goods_code, pm.warehouse, pm.batch_no
                ),
                bs AS (
                    -- 批次级当前快照：期初倒推与逐笔滚算金额都按 商品+仓库+批次 取这一份。
                    -- 不能用 inv_stock_balance：它只有商品+仓库一行且 batch_no 仅记最后批次，
                    -- 多批次并存时非末批次分区会因关联不到快照而期初/结存金额整体错位。
                    SELECT s.goods_code AS goods_code, MAX(s.goods_name) AS goods_name,
                           s.warehouse AS warehouse, s.batch_no AS batch_no,
                           SUM(s.qty) AS qty, SUM(s.stock_amount) AS stock_amount
                    FROM inv_batch_stock s
                    LEFT JOIN rpt_dim_goods gb ON gb.goods_code = s.goods_code
                    WHERE 1=1
                """);
        appendArchiveFilters(req, sb, args, "s", "gb", true);
        scopeBatch.appendTo(sb, args);
        sb.append("""
                    GROUP BY s.goods_code, s.warehouse, s.batch_no
                ),
                ss AS (
                    -- 商品+仓库粒度对账快照：physical 恒等于 available+locked+frozen。
                    -- 刻意不挂批次筛选：对账针对商品+仓库整体，批次只是内部分拆。
                    SELECT w.goods_code AS goods_code, w.warehouse AS warehouse,
                           SUM(w.physical_qty) AS phys_qty,
                           SUM(w.available_qty + w.locked_qty + w.frozen_qty) AS calc_qty
                    FROM inv_stock_balance w
                    LEFT JOIN rpt_dim_goods gw ON gw.goods_code = w.goods_code
                    WHERE 1=1
                """);
        appendArchiveFilters(req, sb, args, "w", "gw", false);
        scopeSnap.appendTo(sb, args);
        sb.append("""
                    GROUP BY w.goods_code, w.warehouse
                )
                """);
        if (paged) {
            sb.append("""
                    , mm AS (
                        -- 结存金额 = 当前批次快照金额 − 本期净发生 + 窗口累计（窗口在圈页前的全量 pm 上滚算）
                        SELECT pm.*,
                               COALESCE(cb.stock_amount, 0) - COALESCE(pn.net_amount, 0)
                                   + SUM(pm.signed_change) OVER (
                                         PARTITION BY pm.goods_code, pm.warehouse, pm.batch_no
                                         ORDER BY pm.occurred_at, pm.ledger_id
                                         ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                                     ) AS running_amount
                        FROM pm
                        LEFT JOIN pn ON pn.goods_code = pm.goods_code
                                    AND pn.warehouse = pm.warehouse
                                    AND COALESCE(pn.batch_no, '') = COALESCE(pm.batch_no, '')
                        LEFT JOIN bs cb ON cb.goods_code = pm.goods_code
                                    AND cb.warehouse = pm.warehouse
                                    AND COALESCE(cb.batch_no, '') = COALESCE(pm.batch_no, '')
                        WHERE 1=1
                    """);
            scopeCurrent.appendTo(sb, args);
            sb.append(") ");
        }
        return sb.toString();
    }

    /**
     * UNION 叶子：期初虚拟行（仅与本页相交的分区；合计模式为全部有流水分区）+ 流水行。
     * 必须跟在 {@link #buildCtes} 产出的 CTE 之后（参数顺序与 CTE 连续绑定）。
     * 期初分支只读 bs（批次快照 CTE），档案/仓库/批次筛选与数据范围已在 CTE 内收敛。
     */
    private String buildUnion(ReportQueryRequest req, List<Object> args,
                              boolean paged, long offset, int limit) {
        StringBuilder sb = new StringBuilder();

        // ---- 期初虚拟行：批次当前快照 − 本期净发生（数量取 bs.qty，金额取 bs.stock_amount）----
        sb.append("""
                SELECT 'OPENING' AS row_kind, CAST(NULL AS VARCHAR(32)) AS ledger_id,
                       ? AS move_date, CAST(NULL AS TIMESTAMP) AS occurred_at,
                       CAST(NULL AS VARCHAR(200)) AS bill_no,
                       CAST(NULL AS VARCHAR(200)) AS base_bill_no,
                       FALSE AS reversal_flag, CAST(NULL AS VARCHAR(20)) AS bill_type_code,
                       b.goods_code AS goods_code, MAX(b.goods_name) AS goods_name,
                       b.warehouse AS warehouse, b.batch_no AS batch_no,
                       CAST(NULL AS VARCHAR(20)) AS direction,
                       COALESCE(b.qty, 0) - COALESCE(MAX(pn.net_qty), 0) AS in_qty,
                       CAST(0 AS DECIMAL(18, 4)) AS out_qty,
                       CAST(NULL AS DECIMAL(18, 6)) AS cost_price,
                       COALESCE(MAX(b.stock_amount), 0) - COALESCE(MAX(pn.net_amount), 0)
                           AS signed_change,
                       COALESCE(MAX(b.qty), 0) - COALESCE(MAX(pn.net_qty), 0) AS balance_qty,
                       COALESCE(MAX(b.stock_amount), 0) - COALESCE(MAX(pn.net_amount), 0)
                           AS balance_amount,
                       CAST(NULL AS VARCHAR(100)) AS operator_name, 0 AS sort_no,
                       MAX(gm.barcode) AS barcode, MAX(gm.base_unit) AS base_unit,
                       MAX(gm.brand_name) AS gl_brand,
                       MAX(gm.category_name) AS gl_category,
                       MAX(gm.storage_property) AS gl_storage,
                       CAST(0 AS DECIMAL(18, 4)) AS snap_physical,
                       CAST(0 AS DECIMAL(18, 4)) AS snap_calc
                FROM bs b
                LEFT JOIN rpt_dim_goods gm ON gm.goods_code = b.goods_code
                LEFT JOIN pn ON pn.goods_code = b.goods_code
                            AND pn.warehouse = b.warehouse
                            AND COALESCE(pn.batch_no, '') = COALESCE(b.batch_no, '')
                WHERE 1=1
                """);
        args.add(req.range().startDate());
        if (paged) {
            sb.append("""
                      AND EXISTS (
                          SELECT 1 FROM pm z
                          WHERE z.goods_code = b.goods_code AND z.warehouse = b.warehouse
                            AND COALESCE(z.batch_no, '') = COALESCE(b.batch_no, '')
                            AND z.rn BETWEEN ? AND ?
                      )
                    """);
            args.add(offset + 1);
            args.add(offset + limit);
        } else {
            sb.append("""
                      AND EXISTS (
                          SELECT 1 FROM pm z
                          WHERE z.goods_code = b.goods_code AND z.warehouse = b.warehouse
                            AND COALESCE(z.batch_no, '') = COALESCE(b.batch_no, '')
                      )
                    """);
        }
        // bs/pn 都是 商品+仓库+批次 唯一粒度，JOIN 后仍 1:1；GROUP BY 仅为聚合档案维度字段
        sb.append("GROUP BY b.goods_code, b.warehouse, b.batch_no, b.qty ");

        // ---- 本期流水行 ----
        sb.append("UNION ALL ");
        if (paged) {
            sb.append("""
                    SELECT 'MOVE' AS row_kind, mm.ledger_id AS ledger_id,
                           mm.move_date AS move_date, mm.occurred_at AS occurred_at,
                           mm.bill_no AS bill_no, mm.base_bill_no AS base_bill_no,
                           mm.reversal_flag AS reversal_flag, mm.bill_type_code AS bill_type_code,
                           mm.goods_code AS goods_code, mm.goods_name AS goods_name,
                           mm.warehouse AS warehouse, mm.batch_no AS batch_no,
                           mm.direction AS direction,
                           mm.in_qty AS in_qty, mm.out_qty AS out_qty,
                           mm.cost_price AS cost_price, mm.signed_change AS signed_change,
                           mm.balance_qty AS balance_qty, mm.running_amount AS balance_amount,
                           mm.operator_name AS operator_name, 1 AS sort_no,
                           mm.barcode AS barcode, mm.base_unit AS base_unit,
                           mm.gl_brand AS gl_brand, mm.gl_category AS gl_category,
                           mm.gl_storage AS gl_storage,
                           CAST(0 AS DECIMAL(18, 4)) AS snap_physical,
                           CAST(0 AS DECIMAL(18, 4)) AS snap_calc
                    FROM mm
                    WHERE mm.rn BETWEEN ? AND ?
                    """);
            args.add(offset + 1);
            args.add(offset + limit);
        } else {
            // 合计模式不需要逐行结存金额
            sb.append("""
                    SELECT 'MOVE' AS row_kind, pm.ledger_id AS ledger_id,
                           pm.move_date AS move_date, pm.occurred_at AS occurred_at,
                           pm.bill_no AS bill_no, pm.base_bill_no AS base_bill_no,
                           pm.reversal_flag AS reversal_flag, pm.bill_type_code AS bill_type_code,
                           pm.goods_code AS goods_code, pm.goods_name AS goods_name,
                           pm.warehouse AS warehouse, pm.batch_no AS batch_no,
                           pm.direction AS direction,
                           pm.in_qty AS in_qty, pm.out_qty AS out_qty,
                           pm.cost_price AS cost_price, pm.signed_change AS signed_change,
                           pm.balance_qty AS balance_qty,
                           CAST(NULL AS DECIMAL(18, 2)) AS balance_amount,
                           pm.operator_name AS operator_name, 1 AS sort_no,
                           pm.barcode AS barcode, pm.base_unit AS base_unit,
                           pm.gl_brand AS gl_brand, pm.gl_category AS gl_category,
                           pm.gl_storage AS gl_storage,
                           CAST(0 AS DECIMAL(18, 4)) AS snap_physical,
                           CAST(0 AS DECIMAL(18, 4)) AS snap_calc
                    FROM pm
                    """);
        }
        return sb.toString();
    }

    /** 页面外层：中文文案 + 蛇形列（引擎统一转驼峰）。 */
    private static String outerSelect() {
        return """
                SELECT x.row_kind AS row_kind, x.ledger_id AS ledger_id,
                       x.move_date AS move_date, x.occurred_at AS occurred_at,
                       x.bill_no AS bill_no, x.base_bill_no AS base_bill_no,
                       x.reversal_flag AS reversal_flag,
                       CASE x.bill_type_code
                            WHEN 'CGRK' THEN '采购入库'
                            WHEN 'CGSH' THEN '成本调整'
                            WHEN 'CTCK' THEN '采购退货出库'
                            WHEN 'XSCK' THEN '销售出库'
                            WHEN 'THRK' THEN '销售退货入库'
                            WHEN 'JSRK' THEN '客户拒收入库'
                            WHEN 'QTRK' THEN '其他入库'
                            WHEN 'QTCK' THEN '其他出库'
                            WHEN 'DBCK' THEN '调拨出库'
                            WHEN 'DBRK' THEN '调拨入库'
                            WHEN 'BSD' THEN '报损出库'
                            WHEN 'PDD' THEN '盘点单'
                            WHEN 'WMS_INBOUND' THEN 'WMS入库'
                            WHEN 'WMS_ADJUST_GAIN' THEN 'WMS盘盈调整'
                            WHEN 'OTHER' THEN '其他'
                            ELSE '期初结存' END AS bill_type_text,
                       CASE x.direction WHEN 'IN' THEN '收入'
                                        WHEN 'OUT' THEN '发出'
                                        WHEN '成本调整' THEN '成本调整'
                                        ELSE '' END AS direction_text,
                       x.goods_code AS goods_code, x.goods_name AS goods_name,
                       x.warehouse AS warehouse, x.batch_no AS batch_no,
                       x.in_qty AS in_qty, x.out_qty AS out_qty,
                       x.base_unit AS base_unit,
                       x.cost_price AS cost_price, x.signed_change AS signed_amount,
                       x.balance_qty AS balance_qty, x.balance_amount AS balance_amount,
                       x.operator_name AS operator_name,
                       x.barcode AS barcode, x.gl_brand AS brand_name,
                       x.gl_category AS category_name, x.gl_storage AS storage_property,
                       x.sort_no AS sort_no
                """;
    }

    /** 合计行：期初 / 本期收入 / 本期发出（净）/ 期末 + 快照对账差异。 */
    private static String grandSelect() {
        return """
                SELECT COALESCE(SUM(CASE WHEN x.row_kind = 'OPENING' THEN x.balance_qty ELSE 0 END), 0)
                           AS opening_qty,
                       COALESCE(SUM(CASE WHEN x.row_kind = 'OPENING' THEN x.balance_amount ELSE 0 END), 0)
                           AS opening_amount,
                       COALESCE(SUM(CASE WHEN x.row_kind = 'MOVE' THEN x.in_qty ELSE 0 END), 0) AS in_qty,
                       COALESCE(SUM(CASE WHEN x.row_kind = 'MOVE' THEN x.out_qty ELSE 0 END), 0) AS out_qty,
                       COALESCE(SUM(CASE WHEN x.row_kind = 'MOVE' THEN x.signed_change ELSE 0 END), 0)
                           AS signed_amount,
                       COALESCE(SUM(CASE WHEN x.row_kind = 'OPENING' THEN x.balance_qty ELSE 0 END)
                             + SUM(CASE WHEN x.row_kind = 'MOVE' THEN x.in_qty - x.out_qty ELSE 0 END), 0)
                           AS ending_qty,
                       COALESCE(SUM(CASE WHEN x.row_kind = 'OPENING' THEN x.balance_amount ELSE 0 END)
                             + SUM(CASE WHEN x.row_kind = 'MOVE' THEN x.signed_change ELSE 0 END), 0)
                           AS ending_amount,
                       COALESCE(z.snapshot_physical, 0) AS snapshot_physical,
                       COALESCE(z.snapshot_calc, 0) AS snapshot_calc,
                       COALESCE(z.reconcile_diff, 0) AS reconcile_diff
                """;
    }

    /**
     * 档案类筛选片段：商品关键字（编码/名称/条码）/分类/品牌/温区/仓库/批次。
     * 流水（l,gl）、批次快照（s,gb）、对账快照（w,gw）三处共用，口径必须一致。
     *
     * @param withBatch 批次筛选仅作用于流水与批次快照；对账快照是商品+仓库粒度，不吃批次
     */
    private static void appendArchiveFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                                             String m, String g, boolean withBatch) {
        String goods = req.text("goods");
        if (goods != null) {
            sql.append(" AND (").append(m).append(".goods_code LIKE ? OR ")
               .append(m).append(".goods_name LIKE ? OR ")
               .append(g).append(".barcode LIKE ?) ");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
        }
        for (String key : List.of("categoryName", "brandName", "storageProperty")) {
            String v = req.text(key);
            if (v != null) {
                String col = switch (key) {
                    case "categoryName" -> "category_name";
                    case "brandName" -> "brand_name";
                    case "storageProperty" -> "storage_property";
                    default -> key;
                };
                sql.append(" AND ").append(g).append('.').append(col).append(" = ? ");
                args.add(v);
            }
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append(" AND ").append(m).append(".warehouse = ? ");
            args.add(warehouse);
        }
        if (withBatch) {
            String batchNo = req.text("batchNo");
            if (batchNo != null) {
                sql.append(" AND ").append(m).append(".batch_no LIKE ? ");
                args.add("%" + batchNo + "%");
            }
        }
    }

    /** 流水期内筛选片段：单据类型多选 / 方向 / 单据号，只作用于 pm 与计数语句。 */
    private static void appendPeriodFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                                            String m) {
        List<String> types = req.texts("billTypes");
        if (!types.isEmpty()) {
            String marks = String.join(",", types.stream().map(t -> "?").toList());
            sql.append(" AND ").append(m).append(".bill_type_code IN (").append(marks).append(") ");
            args.addAll(types);
        }
        String direction = req.text("direction");
        if (direction != null) {
            sql.append(" AND ").append(m).append(".direction = ? ");
            args.add(direction);
        }
        String billNo = req.text("billNo");
        if (billNo != null) {
            sql.append(" AND ").append(m).append(".bill_no LIKE ? ");
            args.add("%" + billNo + "%");
        }
    }
}
