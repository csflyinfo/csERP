package com.erp.report.sales;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.common.SqlLiterals;
import com.erp.report.meta.CustomPageSql;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import com.erp.report.meta.RowEnricher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报表15｜销售订单明细查询（订单行粒度，物流执行视角）。
 *
 * <p>#1 的销售对称版：sales_order/detail 默认仅已审核；行级已出库量按
 * sales_outbound.source_order=order_no、已审核出库明细 small_unit_qty（已是基本单位）
 * 聚合；已签收量/金额按 sales_receipt_detail.signed_qty/sign_amount 聚合（K8 销售确认口径，
 * 按行换算率归一基本单位），签收日期取最近签收时间；拒收量单列 receipt_detail.reject_qty，
 * 不算出库也不算销售。聚合不按出库/签收日期截断（区间内订单允许区间后执行）。
 *
 * <p>状态文案统一重算：出库状态 待出库/未出库 → 未出库、部分出库、已出库；
 * 签收状态 未签收/部分签收/已签收/全部拒收。按出/签收状态过滤或非默认排序时回退
 * 全量 JOIN 路径；默认浏览走单据头窗口分页 + {@link ExecutionEnricher} 本页批量补算。
 */
@Component
public class SalesOrderDetailDefinition implements ReportDefinition {

    /** 基本数量：优先落库 base_qty，老数据兜底 数量×换算率。参数：行别名。 */
    private static String baseQty(String a) {
        return "COALESCE(NULLIF(" + a + ".base_qty_raw,0), " + a + ".qty_raw * " + a + ".convert_qty_raw)";
    }

    private final DataScopeService dataScope;
    private final JdbcTemplate reportJdbc;

    public SalesOrderDetailDefinition(DataScopeService dataScope,
                                      @Qualifier("reportJdbcTemplate") JdbcTemplate reportJdbc) {
        this.dataScope = dataScope;
        this.reportJdbc = reportJdbc;
    }

    @Override public String code() { return "sales_order_detail"; }
    @Override public String name() { return "销售订单明细查询"; }
    @Override public String viewPerm() { return "report.sales_order_detail.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return false; }

    @Override
    public List<ReportColumnDef> columns() {
        return List.of(
                ReportColumnDef.dim("orderNo", "订单号"),
                ReportColumnDef.dim("billDate", "订单日期"),
                ReportColumnDef.dim("statusText", "审核状态"),
                ReportColumnDef.dim("outboundStatusText", "出库状态"),
                ReportColumnDef.dim("signStatusText", "签收状态"),
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
                ReportColumnDef.measure("amount", "订单金额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("outboundBase", "已出库数量(小单位)", null),
                ReportColumnDef.measure("outboundPackage", "已出库件数", null),
                ReportColumnDef.measure("unoutboundBase", "未出库数量(小单位)", null),
                ReportColumnDef.measure("unoutboundPackage", "未出库件数", null),
                ReportColumnDef.measure("rejectBase", "已拒收数量(小单位)", null),
                ReportColumnDef.measure("signedBase", "已签收数量(小单位)", null),
                ReportColumnDef.measure("signedPackage", "已签收件数", null),
                ReportColumnDef.measure("signedAmount", "签收金额", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.dim("signDate", "签收日期"),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("categoryName", "商品类别"),
                ReportColumnDef.dim("storageProperty", "存储属性")
        );
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();

        String outboundStatus = req.text("outboundStatus");
        String signStatus = req.text("signStatus");
        boolean defaultOrder = req.sortField() == null
                || ("billDate".equals(req.sortField()) && !req.isAsc());
        boolean windowPath = outboundStatus == null && signStatus == null && defaultOrder;

        StringBuilder legacyFrom = new StringBuilder();
        List<Object> legacyArgs = new ArrayList<>();
        buildLegacyFrom(req, legacyFrom, legacyArgs);
        if (outboundStatus != null) {
            String cond = switch (outboundStatus) {
                case "未出库" -> "t.outbound_base <= 0";
                case "已出库" -> "t.outbound_base >= " + baseQty("t");
                case "部分出库" -> "t.outbound_base > 0 AND t.outbound_base < " + baseQty("t");
                default -> null;
            };
            if (cond == null) {
                throw new IllegalArgumentException("出库状态只支持：未出库 / 部分出库 / 已出库");
            }
            legacyFrom.append(" WHERE ").append(cond);
        }
        if (signStatus != null) {
            String cond = signCondition(signStatus);
            if (cond == null) {
                throw new IllegalArgumentException(
                        "签收状态只支持：未签收 / 部分签收 / 已签收 / 全部拒收");
            }
            legacyFrom.append(signStatus == null ? "" : (outboundStatus != null ? " AND " : " WHERE "))
                    .append(cond);
        }

        // 合计：始终用含出库/签收聚合的全量口径
        plan.grandSql = grandSummarySelect() + " " + legacyFrom;
        plan.grandArgs.addAll(legacyArgs);
        plan.grandSummarySelect = grandSummarySelect();

        if (!windowPath) {
            var scope = dataScope.target()
                    .warehouse("po.warehouse").customer("po.customer").salesman("po.salesman")
                    .creator("po.creator_name")
                    .goodsColumn("d.goods_code")
                    .build();
            if (scope.isDenyAll()) return plan.denyAll();
            plan.detailSelect = legacyDetailSelect();
            plan.fromWhere.setLength(0);
            plan.fromWhere.append(legacyFrom);
            plan.args.addAll(legacyArgs);
            fillSortAndMask(plan);
            return plan;
        }

        // 窗口路径：头级 / 行级两套数据范围
        var hdrScope = dataScope.target()
                .warehouse("po.warehouse").customer("po.customer")
                .salesman("po.salesman").creator("po.creator_name").build();
        var lineScope0 = dataScope.target().goodsColumn("d0.goods_code").build();
        var lineScope = dataScope.target().goodsColumn("d.goods_code").build();
        if (hdrScope.isDenyAll() || lineScope0.isDenyAll() || lineScope.isDenyAll()) {
            return plan.denyAll();
        }

        String line0 = linePreds(req, "d0", "gg");
        String line1 = linePreds(req, "d", "dg");
        List<Object> lineArgs0 = new ArrayList<>();
        List<Object> lineArgs1 = new ArrayList<>();
        linePreds(req, "d0", "gg", lineArgs0);
        linePreds(req, "d", "dg", lineArgs1);

        String header = headerPreds(req, "po", "bc");
        List<Object> headerArgs = new ArrayList<>();
        headerPreds(req, "po", "bc", headerArgs);
        StringBuilder hdrScopeSql = new StringBuilder();
        hdrScope.appendTo(hdrScopeSql, headerArgs);

        StringBuilder l0Scope = new StringBuilder();
        lineScope0.appendTo(l0Scope, lineArgs0);
        StringBuilder l1Scope = new StringBuilder();
        lineScope.appendTo(l1Scope, lineArgs1);

        String h0Cte = """
                WITH h0 AS (
                  SELECT po.order_id AS order_id, po.order_no AS order_no, po.bill_date AS bill_date,
                         po.status AS status, po.customer_code AS customer_code,
                         po.customer AS customer_name, po.salesman AS salesman,
                         COALESCE(bc.territory, '') AS territory,
                         COALESCE(bc.route_line, '') AS route_line,
                         (SELECT COUNT(*) FROM sales_order_detail d0
                          LEFT JOIN rpt_dim_goods gg ON gg.goods_code = d0.goods_code
                          WHERE d0.order_id = po.order_id %1$s %2$s) AS line_cnt
                  FROM sales_order po
                  LEFT JOIN base_customer bc ON bc.customer_code = po.customer_code
                  WHERE 1=1 %3$s %4$s
                )
                """.formatted(line0, l0Scope, header, hdrScopeSql);
        String hdrCte = """
                , hdr AS (
                  SELECT h0.*, SUM(line_cnt) OVER (ORDER BY bill_date DESC, order_no ASC
                      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_end
                  FROM h0
                )
                """;
        String zCte = """
                , z AS (
                  SELECT hdr.order_no AS order_no, hdr.bill_date AS bill_date, hdr.status AS status,
                         CASE hdr.status WHEN 'PENDING' THEN '待审核'
                                        WHEN 'APPROVED' THEN '已审核'
                                        WHEN 'AUDITED' THEN '已审核'
                                        WHEN 'CLOSED' THEN '已关闭'
                                        WHEN 'CANCELLED' THEN '已作废'
                                        ELSE hdr.status END AS status_text,
                         COALESCE(hdr.customer_code, '') AS customer_code,
                         hdr.customer_name AS customer_name, hdr.salesman AS salesman,
                         hdr.territory AS territory, hdr.route_line AS route_line,
                         d.goods_code AS goods_code, d.goods_name AS goods_name,
                         dg.barcode AS barcode, dg.base_unit AS base_unit,
                         dg.brand_name AS brand_name, dg.category_name AS category_name,
                         dg.storage_property AS storage_property,
                         dg.large_unit AS large_unit,
                         d.qty AS qty_raw, d.convert_qty AS convert_qty_raw,
                         d.base_qty AS base_qty_raw, d.amount AS amount,
                         d.unit_name AS unit_name_raw,
                         dg.large_convert_qty AS large_convert_qty_raw,
                         CAST(0 AS DECIMAL(18,6)) AS outbound_base,
                         CAST(0 AS DECIMAL(18,6)) AS signed_base,
                         CAST(0 AS DECIMAL(18,2)) AS signed_amount,
                         CAST(0 AS DECIMAL(18,6)) AS reject_base,
                         CAST(NULL AS DATE) AS sign_date,
                         hdr.cum_end - hdr.line_cnt
                           + ROW_NUMBER() OVER (PARTITION BY hdr.order_id
                                                ORDER BY d.goods_code ASC, d.detail_id ASC) AS gidx
                  FROM hdr
                  JOIN sales_order_detail d ON d.order_id = hdr.order_id
                  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = d.goods_code
                  WHERE hdr.cum_end > ? AND hdr.cum_end - hdr.line_cnt < ? %1$s %2$s
                )
                """.formatted(line1, l1Scope);

        List<Object> countArgsAll = new ArrayList<>();
        countArgsAll.addAll(lineArgs0);
        countArgsAll.addAll(headerArgs);
        String countSqlFinal = SqlLiterals.render(
                h0Cte + " SELECT COALESCE(SUM(line_cnt),0) AS total FROM h0", countArgsAll);

        String finalSelect = windowFinalSelect();
        String listTail = """
                FROM z
                WHERE z.gidx > %1$d AND z.gidx <= %2$d
                ORDER BY z.bill_date DESC, z.order_no ASC, z.gidx ASC
                """;
        plan.detailSelect = finalSelect;
        plan.customPageSql = new CustomPageSql() {
            @Override public String countSql() { return countSqlFinal; }
            @Override public List<Object> countArgs() { return List.of(); }
            @Override public String listSql(long offset, int limit) {
                List<Object> args = new ArrayList<>();
                args.addAll(lineArgs0);
                args.addAll(headerArgs);
                args.add(offset);
                args.add((long) offset + limit);
                args.addAll(lineArgs1);
                String sql = h0Cte + hdrCte + zCte + " " + finalSelect + " "
                        + listTail.formatted(offset, (long) offset + limit);
                return SqlLiterals.render(sql, args);
            }
            @Override public List<Object> listArgs(long offset, int limit) { return List.of(); }
        };
        plan.rowEnricher = new ExecutionEnricher(reportJdbc);
        fillSortAndMask(plan);
        return plan;
    }

    /** 签收状态过滤条件（作用在含聚合的内层派生列上）。 */
    private static String signCondition(String signStatus) {
        return switch (signStatus) {
            case "未签收" -> "t.signed_base <= 0 AND t.reject_base <= 0";
            case "部分签收" -> "t.signed_base > 0 AND t.signed_base < " + baseQty("t");
            case "已签收" -> "t.signed_base >= " + baseQty("t");
            case "全部拒收" -> "t.signed_base <= 0 AND t.reject_base > 0";
            default -> null;
        };
    }

    /** 窗口路径最终投影；出库/签收执行量为占位，由填充器覆写。 */
    private static String windowFinalSelect() {
        String b = baseQty("z");
        return """
                SELECT z.order_no AS order_no, z.bill_date AS bill_date, z.status AS status,
                       z.status_text AS status_text,
                       CAST(NULL AS VARCHAR(20)) AS outbound_status_text,
                       CAST(NULL AS VARCHAR(20)) AS sign_status_text,
                       z.customer_code AS customer_code, z.customer_name AS customer_name,
                       z.salesman AS salesman,
                       z.territory AS territory, z.route_line AS route_line,
                       z.goods_code AS goods_code, z.goods_name AS goods_name,
                       z.barcode AS barcode, z.base_unit AS base_unit,
                       %1$s AS base_qty,
                       z.amount / NULLIF(%1$s, 0) AS unit_price_base,
                       CASE WHEN COALESCE(z.large_convert_qty_raw, 0) > 0
                            THEN %1$s / z.large_convert_qty_raw ELSE %1$s END AS package_qty,
                       z.large_unit AS large_unit,
                       CASE WHEN COALESCE(z.large_convert_qty_raw, 0) > 0
                            THEN z.amount / NULLIF(%1$s, 0) * z.large_convert_qty_raw
                            ELSE z.amount / NULLIF(%1$s, 0) END AS box_price,
                       z.amount AS amount,
                       z.outbound_base AS outbound_base,
                       CASE WHEN COALESCE(z.large_convert_qty_raw, 0) > 0
                            THEN z.outbound_base / z.large_convert_qty_raw
                            ELSE z.outbound_base END AS outbound_package,
                       GREATEST(%1$s - z.outbound_base, 0) AS unoutbound_base,
                       CASE WHEN COALESCE(z.large_convert_qty_raw, 0) > 0
                            THEN GREATEST(%1$s - z.outbound_base, 0) / z.large_convert_qty_raw
                            ELSE GREATEST(%1$s - z.outbound_base, 0) END AS unoutbound_package,
                       z.reject_base AS reject_base,
                       z.signed_base AS signed_base,
                       CASE WHEN COALESCE(z.large_convert_qty_raw, 0) > 0
                            THEN z.signed_base / z.large_convert_qty_raw
                            ELSE z.signed_base END AS signed_package,
                       z.signed_amount AS signed_amount,
                       z.sign_date AS sign_date,
                       z.brand_name AS brand_name, z.category_name AS category_name,
                       z.storage_property AS storage_property,
                       z.convert_qty_raw AS convert_qty_raw,
                       z.unit_name_raw AS unit_name_raw,
                       z.large_convert_qty_raw AS large_convert_qty_raw
                """.formatted(b);
    }

    /** 回退路径外层投影（含真实执行量与重算状态文案）。 */
    private static String legacyDetailSelect() {
        String b = baseQty("t");
        return """
                SELECT t.order_no AS order_no, t.bill_date AS bill_date, t.status AS status,
                       CASE t.status WHEN 'PENDING' THEN '待审核'
                                     WHEN 'APPROVED' THEN '已审核'
                                     WHEN 'AUDITED' THEN '已审核'
                                     WHEN 'CLOSED' THEN '已关闭'
                                     WHEN 'CANCELLED' THEN '已作废'
                                     ELSE t.status END AS status_text,
                       CASE WHEN t.status = 'CANCELLED' THEN '已作废'
                            WHEN t.status = 'CLOSED' THEN '已终止'
                            WHEN t.outbound_base <= 0 THEN '未出库'
                            WHEN t.outbound_base >= %1$s THEN '已出库'
                            WHEN t.outbound_base > 0 THEN '部分出库'
                            ELSE '未出库' END AS outbound_status_text,
                       CASE WHEN t.status = 'CANCELLED' THEN '—'
                            WHEN t.signed_base <= 0 AND t.reject_base > 0 THEN '全部拒收'
                            WHEN t.signed_base <= 0 THEN '未签收'
                            WHEN t.signed_base >= %1$s THEN '已签收'
                            WHEN t.signed_base > 0 THEN '部分签收'
                            ELSE '未签收' END AS sign_status_text,
                       t.customer_code AS customer_code, t.customer_name AS customer_name,
                       t.salesman AS salesman, t.territory AS territory, t.route_line AS route_line,
                       t.goods_code AS goods_code, t.goods_name AS goods_name,
                       t.barcode AS barcode, t.base_unit AS base_unit,
                       %1$s AS base_qty,
                       t.amount / NULLIF(%1$s, 0) AS unit_price_base,
                       CASE WHEN COALESCE(dg.large_convert_qty, 0) > 0
                            THEN %1$s / dg.large_convert_qty ELSE %1$s END AS package_qty,
                       dg.large_unit AS large_unit,
                       CASE WHEN COALESCE(dg.large_convert_qty, 0) > 0
                            THEN t.amount / NULLIF(%1$s, 0) * dg.large_convert_qty
                            ELSE t.amount / NULLIF(%1$s, 0) END AS box_price,
                       t.amount AS amount,
                       t.outbound_base AS outbound_base,
                       CASE WHEN COALESCE(dg.large_convert_qty, 0) > 0
                            THEN t.outbound_base / dg.large_convert_qty
                            ELSE t.outbound_base END AS outbound_package,
                       GREATEST(%1$s - t.outbound_base, 0) AS unoutbound_base,
                       CASE WHEN COALESCE(dg.large_convert_qty, 0) > 0
                            THEN GREATEST(%1$s - t.outbound_base, 0) / dg.large_convert_qty
                            ELSE GREATEST(%1$s - t.outbound_base, 0) END AS unoutbound_package,
                       t.reject_base AS reject_base,
                       t.signed_base AS signed_base,
                       CASE WHEN COALESCE(dg.large_convert_qty, 0) > 0
                            THEN t.signed_base / dg.large_convert_qty
                            ELSE t.signed_base END AS signed_package,
                       t.signed_amount AS signed_amount,
                       t.sign_date AS sign_date,
                       t.brand_name AS brand_name, t.category_name AS category_name,
                       t.storage_property AS storage_property
                """.formatted(b);
    }

    /** 回退路径/合计共用 FROM：行表 × 头表 × 客户/商品维度 × 出库/签收/拒收三个聚合派生表。 */
    private void buildLegacyFrom(ReportQueryRequest req, StringBuilder fw, List<Object> innerArgs) {
        fw.append(" FROM (");
        fw.append("""
                SELECT po.order_no AS order_no, po.bill_date AS bill_date, po.status AS status,
                       COALESCE(po.customer_code, '') AS customer_code,
                       po.customer AS customer_name, po.salesman AS salesman,
                       po.warehouse AS warehouse,
                       COALESCE(bc.territory, '') AS territory,
                       COALESCE(bc.route_line, '') AS route_line,
                       d.goods_code AS goods_code, d.goods_name AS goods_name,
                       d.unit_name AS unit_name, d.qty AS qty_raw, d.convert_qty AS convert_qty_raw,
                       d.base_qty AS base_qty_raw, d.amount AS amount,
                       g.barcode AS barcode, g.base_unit AS base_unit,
                       g.brand_name AS brand_name, g.category_name AS category_name,
                       g.storage_property AS storage_property,
                       COALESCE(g.large_convert_qty, 0) AS large_convert_qty,
                       COALESCE(outb.out_base, 0) AS outbound_base,
                       COALESCE(sgn.signed_q, 0) * COALESCE(NULLIF(d.convert_qty, 0), 1) AS signed_base,
                       COALESCE(sgn.signed_amt, 0) AS signed_amount,
                       sgn.sign_date AS sign_date,
                       COALESCE(rej.reject_q, 0) * COALESCE(NULLIF(d.convert_qty, 0), 1) AS reject_base
                FROM sales_order po
                JOIN sales_order_detail d ON d.order_id = po.order_id
                LEFT JOIN rpt_dim_goods g ON g.goods_code = d.goods_code
                LEFT JOIN base_customer bc ON bc.customer_code = po.customer_code
                LEFT JOIN (
                    -- 已出库：出库明细 small_unit_qty 已是基本单位，按订单日期收敛、不按出库日期截断
                    SELECT o.source_order AS r_order, od.goods_code AS r_goods,
                           SUM(od.small_unit_qty) AS out_base
                    FROM sales_order por
                    JOIN sales_outbound o ON o.source_order = por.order_no AND o.status = 'APPROVED'
                    JOIN sales_outbound_detail od ON od.outbound_id = o.outbound_id
                    WHERE por.bill_date BETWEEN ? AND ?
                      AND por.status IN ('APPROVED','AUDITED','CLOSED')
                    GROUP BY o.source_order, od.goods_code
                ) outb ON outb.r_order = po.order_no AND outb.r_goods = d.goods_code
                LEFT JOIN (
                    -- 已签收：K8 口径，仅已签收/部分拒收单据的 signed_qty/sign_amount
                    SELECT r.source_order_no AS r_order, rd.goods_code AS r_goods,
                           COALESCE(rd.unit_name, '') AS r_unit,
                           SUM(rd.signed_qty) AS signed_q,
                           SUM(rd.sign_amount) AS signed_amt,
                           MAX(CAST(r.sign_time AS DATE)) AS sign_date
                    FROM sales_order por
                    JOIN sales_receipt r ON r.source_order_no = por.order_no
                       AND r.status = 'APPROVED' AND r.sign_time IS NOT NULL
                       AND r.sign_status IN ('已签收', '部分拒收')
                    JOIN sales_receipt_detail rd ON rd.receipt_id = r.receipt_id
                    WHERE por.bill_date BETWEEN ? AND ?
                      AND por.status IN ('APPROVED','AUDITED','CLOSED')
                    GROUP BY r.source_order_no, rd.goods_code, COALESCE(rd.unit_name, '')
                ) sgn ON sgn.r_order = po.order_no AND sgn.r_goods = d.goods_code
                     AND sgn.r_unit = COALESCE(d.unit_name, '')
                LEFT JOIN (
                    -- 已拒收：receipt_detail.reject_qty（物流执行参考列，不计销售）
                    SELECT r.source_order_no AS r_order, rd.goods_code AS r_goods,
                           COALESCE(rd.unit_name, '') AS r_unit,
                           SUM(rd.reject_qty) AS reject_q
                    FROM sales_order por
                    JOIN sales_receipt r ON r.source_order_no = por.order_no
                       AND r.status = 'APPROVED' AND r.sign_time IS NOT NULL
                       AND r.sign_status IN ('部分拒收', '全部拒收')
                    JOIN sales_receipt_detail rd ON rd.receipt_id = r.receipt_id
                    WHERE por.bill_date BETWEEN ? AND ?
                      AND rd.reject_qty > 0
                      AND por.status IN ('APPROVED','AUDITED','CLOSED')
                    GROUP BY r.source_order_no, rd.goods_code, COALESCE(rd.unit_name, '')
                ) rej ON rej.r_order = po.order_no AND rej.r_goods = d.goods_code
                     AND rej.r_unit = COALESCE(d.unit_name, '')
                WHERE 1=1
                """);
        // 三个派生表的日期参数文本位置最早
        innerArgs.add(req.range().startDate());
        innerArgs.add(req.range().endDate());
        innerArgs.add(req.range().startDate());
        innerArgs.add(req.range().endDate());
        innerArgs.add(req.range().startDate());
        innerArgs.add(req.range().endDate());

        String status = req.text("status");
        if (status == null || "已审核".equals(status)) {
            fw.append(" AND po.status IN ('APPROVED','AUDITED') ");
        } else {
            fw.append(" AND po.status = ? ");
            innerArgs.add(switch (status) {
                case "待审核" -> "PENDING";
                case "已关闭" -> "CLOSED";
                case "已作废" -> "CANCELLED";
                default -> status;
            });
        }
        appendHeaderFilters(req, fw, innerArgs, "po", "bc");
        appendLineFilters(req, fw, innerArgs, "d", "g");
        var scope = dataScope.target()
                .warehouse("po.warehouse").customer("po.customer").salesman("po.salesman")
                .creator("po.creator_name")
                .goodsColumn("d.goods_code")
                .build();
        scope.appendTo(fw, innerArgs);
        fw.append(") t LEFT JOIN rpt_dim_goods dg ON dg.goods_code = t.goods_code");
    }

    private static String grandSummarySelect() {
        String b = baseQty("t");
        return """
                SELECT COUNT(*) AS line_count,
                       COALESCE(SUM(%1$s),0) AS base_qty,
                       COALESCE(SUM(CASE WHEN COALESCE(t.large_convert_qty,0) > 0
                           THEN %1$s / t.large_convert_qty ELSE %1$s END),0) AS package_qty,
                       COALESCE(SUM(t.amount),0) AS amount,
                       COALESCE(SUM(t.outbound_base),0) AS outbound_base,
                       COALESCE(SUM(CASE WHEN COALESCE(t.large_convert_qty,0) > 0
                           THEN t.outbound_base / t.large_convert_qty
                           ELSE t.outbound_base END),0) AS outbound_package,
                       COALESCE(SUM(GREATEST(%1$s - t.outbound_base, 0)),0) AS unoutbound_base,
                       COALESCE(SUM(CASE WHEN COALESCE(t.large_convert_qty,0) > 0
                           THEN GREATEST(%1$s - t.outbound_base, 0) / t.large_convert_qty
                           ELSE GREATEST(%1$s - t.outbound_base, 0) END),0) AS unoutbound_package,
                       COALESCE(SUM(t.reject_base),0) AS reject_base,
                       COALESCE(SUM(t.signed_base),0) AS signed_base,
                       COALESCE(SUM(CASE WHEN COALESCE(t.large_convert_qty,0) > 0
                           THEN t.signed_base / t.large_convert_qty
                           ELSE t.signed_base END),0) AS signed_package,
                       COALESCE(SUM(t.signed_amount),0) AS signed_amount
                """.formatted(b);
    }

    private void fillSortAndMask(Plan plan) {
        plan.sortWhitelist.putAll(Map.of(
                "billDate", "bill_date", "orderNo", "order_no", "customerName", "customer_name",
                "goodsCode", "goods_code", "baseQty", "base_qty", "amount", "amount",
                "outboundBase", "outbound_base", "signedBase", "signed_base"));
        plan.defaultOrder = "ORDER BY bill_date DESC, order_no ASC, goods_code ASC";
        plan.maskOverrides.putAll(Map.of(
                "unitPriceBase", "VIEW_SALE_PRICE",
                "boxPrice", "VIEW_SALE_PRICE",
                "amount", "VIEW_SALE_AMOUNT",
                "signedAmount", "VIEW_SALE_AMOUNT"));
    }

    // ============================ 窗口路径谓词 ============================

    private static String headerPreds(ReportQueryRequest req, String po, String bc) {
        StringBuilder sb = new StringBuilder();
        appendHeaderFilters(req, sb, null, po, bc);
        return sb.toString();
    }

    private static void headerPreds(ReportQueryRequest req, String po, String bc,
                                    List<Object> args) {
        StringBuilder sb = new StringBuilder();
        appendHeaderFilters(req, sb, args, po, bc);
    }

    /** 头级谓词：日期/状态/订单号/客户/业务员/区域/路线/仓库；客户档案别名 bc 仅回退路径存在。 */
    static void appendHeaderFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                                    String po, String bc) {
        if (args != null) {
            args.add(req.range().startDate());
            args.add(req.range().endDate());
        }
        sql.append(" AND ").append(po).append(".bill_date BETWEEN ? AND ? ");

        String status = req.text("status");
        if (status == null || "已审核".equals(status)) {
            sql.append(" AND ").append(po).append(".status IN ('APPROVED','AUDITED') ");
        } else {
            sql.append(" AND ").append(po).append(".status = ? ");
            if (args != null) args.add(switch (status) {
                case "待审核" -> "PENDING";
                case "已关闭" -> "CLOSED";
                case "已作废" -> "CANCELLED";
                default -> status;
            });
        }
        String orderNo = req.text("orderNo");
        if (orderNo != null) {
            sql.append(" AND ").append(po).append(".order_no LIKE ? ");
            if (args != null) args.add("%" + orderNo + "%");
        }
        String customer = req.text("customer");
        if (customer != null) {
            sql.append(" AND (").append(po).append(".customer_code LIKE ? OR ")
               .append(po).append(".customer LIKE ?) ");
            if (args != null) {
                args.add("%" + customer + "%");
                args.add("%" + customer + "%");
            }
        }
        String salesman = req.text("salesman");
        if (salesman != null) {
            sql.append(" AND ").append(po).append(".salesman LIKE ? ");
            if (args != null) args.add("%" + salesman + "%");
        }
        String territory = req.text("territory");
        if (territory != null && bc != null) {
            sql.append(" AND ").append(bc).append(".territory = ? ");
            if (args != null) args.add(territory);
        }
        String routeLine = req.text("routeLine");
        if (routeLine != null && bc != null) {
            sql.append(" AND ").append(bc).append(".route_line = ? ");
            if (args != null) args.add(routeLine);
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append(" AND ").append(po).append(".warehouse = ? ");
            if (args != null) args.add(warehouse);
        }
    }

    private static String linePreds(ReportQueryRequest req, String dAlias, String gAlias) {
        StringBuilder sb = new StringBuilder();
        appendLineFilters(req, sb, null, dAlias, gAlias);
        return sb.toString();
    }

    private static void linePreds(ReportQueryRequest req, String dAlias, String gAlias,
                                  List<Object> args) {
        StringBuilder sb = new StringBuilder();
        appendLineFilters(req, sb, args, dAlias, gAlias);
    }

    static void appendLineFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                                  String dAlias, String gAlias) {
        String goods = req.text("goods");
        if (goods != null) {
            sql.append(" AND (").append(dAlias).append(".goods_code LIKE ? OR ")
               .append(dAlias).append(".goods_name LIKE ? OR ")
               .append(gAlias).append(".barcode LIKE ?) ");
            if (args != null) {
                args.add("%" + goods + "%");
                args.add("%" + goods + "%");
                args.add("%" + goods + "%");
            }
        }
        String category = req.text("categoryName");
        if (category != null) {
            sql.append(" AND ").append(gAlias).append(".category_name = ? ");
            if (args != null) args.add(category);
        }
        String brand = req.text("brandName");
        if (brand != null) {
            sql.append(" AND ").append(gAlias).append(".brand_name = ? ");
            if (args != null) args.add(brand);
        }
        String storage = req.text("storageProperty");
        if (storage != null) {
            sql.append(" AND ").append(gAlias).append(".storage_property = ? ");
            if (args != null) args.add(storage);
        }
    }

    // ============================ 行级执行量填充 ============================

    /**
     * 对当前页（≤1000 行，通常来自几十张订单）批量补算已出库/已签收/已拒收执行量，
     * 并重算出库/签收状态文案；查本页订单号集合，不按全区间聚合。
     */
    static class ExecutionEnricher implements RowEnricher {

        private static final String KEY_SEP = Character.toString((char) 1);

        private final JdbcTemplate jdbc;

        ExecutionEnricher(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void enrich(List<Map<String, Object>> rows) {
            if (rows == null || rows.isEmpty()) return;
            Set<String> orderNos = new LinkedHashSet<>();
            for (Map<String, Object> r : rows) {
                Object o = r.get("ORDER_NO");
                if (o != null && !String.valueOf(o).isBlank()) orderNos.add(String.valueOf(o));
            }
            String inList = String.join(",", java.util.Collections.nCopies(orderNos.size(), "?"));

            // 已出库（基本单位直接聚合）
            Map<String, BigDecimal> outbound = new HashMap<>();
            // 已签收/拒收按行单位聚合，回填时乘订单行换算率
            Map<String, BigDecimal> signed = new HashMap<>();
            Map<String, BigDecimal> signedAmt = new HashMap<>();
            Map<String, Date> signDate = new HashMap<>();
            Map<String, BigDecimal> rejected = new HashMap<>();

            if (!orderNos.isEmpty()) {
                Object[] nos = orderNos.toArray();
                jdbc.query("""
                        SELECT o.source_order AS o, od.goods_code AS g,
                               SUM(od.small_unit_qty) AS q
                        FROM sales_outbound o
                        JOIN sales_outbound_detail od ON od.outbound_id = o.outbound_id
                        WHERE o.status = 'APPROVED' AND o.source_order IN (%s)
                        GROUP BY o.source_order, od.goods_code
                        """.formatted(inList),
                        rs -> {
                            outbound.put(rs.getString("o") + KEY_SEP + rs.getString("g"),
                                    nz(rs.getBigDecimal("q")));
                        }, nos);

                jdbc.query("""
                        SELECT r.source_order_no AS o, rd.goods_code AS g,
                               COALESCE(rd.unit_name, '') AS u,
                               SUM(rd.signed_qty) AS q,
                               SUM(rd.sign_amount) AS amt,
                               MAX(CAST(r.sign_time AS DATE)) AS sd
                        FROM sales_receipt r
                        JOIN sales_receipt_detail rd ON rd.receipt_id = r.receipt_id
                        WHERE r.status = 'APPROVED' AND r.sign_time IS NOT NULL
                          AND r.sign_status IN ('已签收', '部分拒收')
                          AND r.source_order_no IN (%s)
                        GROUP BY r.source_order_no, rd.goods_code, COALESCE(rd.unit_name, '')
                        """.formatted(inList),
                        rs -> {
                            String key = rs.getString("o") + KEY_SEP + rs.getString("g")
                                    + KEY_SEP + rs.getString("u");
                            signed.put(key, nz(rs.getBigDecimal("q")));
                            signedAmt.put(key, nz(rs.getBigDecimal("amt")));
                            Date sd = rs.getDate("sd");
                            if (sd != null) signDate.put(key, sd);
                        }, nos);

                jdbc.query("""
                        SELECT r.source_order_no AS o, rd.goods_code AS g,
                               COALESCE(rd.unit_name, '') AS u,
                               SUM(rd.reject_qty) AS q
                        FROM sales_receipt r
                        JOIN sales_receipt_detail rd ON rd.receipt_id = r.receipt_id
                        WHERE r.status = 'APPROVED' AND r.sign_time IS NOT NULL
                          AND r.sign_status IN ('部分拒收', '全部拒收')
                          AND rd.reject_qty > 0
                          AND r.source_order_no IN (%s)
                        GROUP BY r.source_order_no, rd.goods_code, COALESCE(rd.unit_name, '')
                        """.formatted(inList),
                        rs -> {
                            rejected.put(rs.getString("o") + KEY_SEP + rs.getString("g")
                                    + KEY_SEP + rs.getString("u"), nz(rs.getBigDecimal("q")));
                        }, nos);
            }

            for (Map<String, Object> r : rows) {
                BigDecimal factor = bd(r.get("CONVERT_QTY_RAW"));
                if (factor.signum() == 0) factor = BigDecimal.ONE;
                BigDecimal large = bd(r.get("LARGE_CONVERT_QTY_RAW"));
                BigDecimal base = bd(r.get("BASE_QTY"));
                String unit = r.get("UNIT_NAME_RAW") == null ? "" : String.valueOf(r.get("UNIT_NAME_RAW"));
                String orderKey = str(r.get("ORDER_NO")) + KEY_SEP + str(r.get("GOODS_CODE"));
                String lineKey = orderKey + KEY_SEP + unit;

                BigDecimal outboundBase = outbound.getOrDefault(orderKey, BigDecimal.ZERO)
                        .setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
                BigDecimal signedBase = signed.getOrDefault(lineKey, BigDecimal.ZERO)
                        .multiply(factor).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
                BigDecimal rejectBase = rejected.getOrDefault(lineKey, BigDecimal.ZERO)
                        .multiply(factor).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
                BigDecimal amount = signedAmt.getOrDefault(lineKey, BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP);

                BigDecimal outboundPkg = large.signum() > 0
                        ? outboundBase.divide(large, 6, RoundingMode.HALF_UP).stripTrailingZeros()
                        : outboundBase;
                BigDecimal unout = base.subtract(outboundBase).max(BigDecimal.ZERO);
                BigDecimal unoutPkg = large.signum() > 0
                        ? unout.divide(large, 6, RoundingMode.HALF_UP).stripTrailingZeros()
                        : unout;
                BigDecimal signedPkg = large.signum() > 0
                        ? signedBase.divide(large, 6, RoundingMode.HALF_UP).stripTrailingZeros()
                        : signedBase;

                r.put("OUTBOUND_BASE", outboundBase);
                r.put("OUTBOUND_PACKAGE", outboundPkg);
                r.put("UNOUTBOUND_BASE", unout);
                r.put("UNOUTBOUND_PACKAGE", unoutPkg);
                r.put("REJECT_BASE", rejectBase);
                r.put("SIGNED_BASE", signedBase);
                r.put("SIGNED_PACKAGE", signedPkg);
                r.put("SIGNED_AMOUNT", amount);
                r.put("SIGN_DATE", signDate.get(lineKey));

                String status = str(r.get("STATUS"));
                if ("CANCELLED".equals(status)) {
                    r.put("OUTBOUND_STATUS_TEXT", "已作废");
                    r.put("SIGN_STATUS_TEXT", "—");
                } else {
                    String outboundText;
                    if (base.signum() <= 0 || outboundBase.signum() <= 0) outboundText = "未出库";
                    else if (outboundBase.compareTo(base) >= 0) outboundText = "已出库";
                    else outboundText = "部分出库";
                    r.put("OUTBOUND_STATUS_TEXT", outboundText);

                    String signText;
                    if (signedBase.signum() <= 0 && rejectBase.signum() > 0) signText = "全部拒收";
                    else if (signedBase.signum() <= 0) signText = "未签收";
                    else if (signedBase.compareTo(base) >= 0) signText = "已签收";
                    else signText = "部分签收";
                    r.put("SIGN_STATUS_TEXT", signText);
                }

                r.remove("CONVERT_QTY_RAW");
                r.remove("UNIT_NAME_RAW");
                r.remove("LARGE_CONVERT_QTY_RAW");
            }
        }

        private static BigDecimal nz(BigDecimal v) {
            return v == null ? BigDecimal.ZERO : v;
        }

        private static BigDecimal bd(Object o) {
            if (o == null) return BigDecimal.ZERO;
            if (o instanceof BigDecimal b) return b;
            return new BigDecimal(String.valueOf(o));
        }

        private static String str(Object o) {
            return o == null ? "" : String.valueOf(o);
        }
    }
}
