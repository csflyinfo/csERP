package com.erp.report.purchase;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.meta.CustomPageSql;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import com.erp.report.meta.RowEnricher;
import com.erp.report.common.ReportQueryRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报表1｜采购订单明细查询（订单行粒度）。
 *
 * <p>取数口径（方案 §6 报表1）：purchase_order/detail 仅已审核（作废可按状态查、默认不查）；
 * 行级已入库量按 pur_inbound.source_order=order_no、已审核入库明细，按 商品+录单单位
 * 聚合 received_qty 后乘订单行换算率归一到基本单位；采购退货不冲减订单执行量。
 *
 * <p>规模性能（日 2,000 单 × 50 行 = 10 万行/日）：标准「全量 JOIN + 外部排序 + LIMIT」
 * 需要物化整个区间百万行并排序，H2 规模库实测 36s/页。默认浏览路径改用
 * <b>单据头窗口分页</b>：先在万级头表（单据日期有索引）上用窗口函数累计行数、
 * 只圈定与本页区间相交的几十张单，再 JOIN 行表取页内数据；行级已入库量由
 * {@link RowEnricher} 对本页订单号批量补算。入/出库状态过滤或用户指定非默认排序时，
 * 回退全量 JOIN 路径（护栏引导缩小范围或异步导出）。
 */
@Component
public class PurchaseOrderDetailDefinition implements ReportDefinition {

    /** 基本数量：优先落库 base_qty，老数据兜底 数量×换算率。参数：行别名。 */
    /**
     * 把带 {@code ?} 占位符的 SQL 渲染成全字面量 SQL（占位符按出现顺序消费）。
     * 跳过单引号字符串字面量内部的问号；字符串参数做单引号翻倍转义。
     * 仅用于头窗口分页路径——规避 H2 2.2「CTE 绑定参数 + 后续窗口 CTE 结果置空」缺陷。
     */
    static String renderLiterals(String sql, List<Object> args) {
        StringBuilder out = new StringBuilder(sql.length() + 64);
        int ai = 0;
        boolean inStr = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                out.append(ch);
                if (inStr && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                } else {
                    inStr = !inStr;
                }
            } else if (ch == '?' && !inStr) {
                if (ai >= args.size()) {
                    throw new IllegalStateException("窗口分页 SQL 占位符多于参数：" + sql);
                }
                out.append(toLiteral(args.get(ai++)));
            } else {
                out.append(ch);
            }
        }
        if (ai != args.size()) {
            throw new IllegalStateException("窗口分页 SQL 参数多于占位符：剩余 " + (args.size() - ai));
        }
        return out.toString();
    }

    private static String toLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof java.time.LocalDate || v instanceof java.time.LocalDateTime) {
            return "'" + v + "'";
        }
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Enum<?> e) return "'" + e.name().replace("'", "''") + "'";
        return "'" + v.toString().replace("'", "''") + "'";
    }

    private static String baseQty(String a) {
        return "COALESCE(NULLIF(" + a + ".base_qty_raw,0), " + a + ".qty_raw * " + a + ".convert_qty_raw)";
    }

    private final DataScopeService dataScope;
    private final JdbcTemplate reportJdbc;

    public PurchaseOrderDetailDefinition(DataScopeService dataScope,
                                         @Qualifier("reportJdbcTemplate") JdbcTemplate reportJdbc) {
        this.dataScope = dataScope;
        this.reportJdbc = reportJdbc;
    }

    @Override public String code() { return "purchase_order_detail"; }
    @Override public String name() { return "采购订单明细查询"; }
    @Override public String viewPerm() { return "report.purchase_order_detail.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return false; }

    @Override
    public List<ReportColumnDef> columns() {
        return List.of(
                ReportColumnDef.dim("orderNo", "订单号"),
                ReportColumnDef.dim("billDate", "订单日期"),
                ReportColumnDef.dim("statusText", "审核状态"),
                ReportColumnDef.dim("inboundStatusText", "入库状态"),
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
                ReportColumnDef.measure("amount", "订单金额", "VIEW_PURCHASE_AMOUNT"),
                ReportColumnDef.measure("receivedBase", "已入库数量(小单位)", null),
                ReportColumnDef.measure("receivedPackage", "已入库件数", null),
                ReportColumnDef.measure("unreceivedBase", "未入库数量(小单位)", null),
                ReportColumnDef.measure("unreceivedPackage", "未入库件数", null),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("categoryName", "商品类别"),
                ReportColumnDef.dim("storageProperty", "存储属性")
        );
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();

        String inboundStatus = req.text("inboundStatus");
        boolean defaultOrder = req.sortField() == null
                || ("billDate".equals(req.sortField()) && !req.isAsc());
        boolean windowPath = inboundStatus == null && defaultOrder;

        StringBuilder legacyFrom = new StringBuilder();
        List<Object> legacyArgs = new ArrayList<>();
        buildLegacyFrom(req, legacyFrom, legacyArgs);
        if (inboundStatus != null) {
            String cond = switch (inboundStatus) {
                case "未入库" -> "t.received_base <= 0";
                case "已入库" -> "t.received_base >= " + baseQty("t");
                case "部分入库" -> "t.received_base > 0 AND t.received_base < " + baseQty("t");
                default -> null;
            };
            if (cond != null) legacyFrom.append(" WHERE ").append(cond);
        }

        // 合计（/summary 懒加载、导出合计行）：始终用含入库聚合的全量口径
        plan.grandSql = grandSummarySelect() + " " + legacyFrom;
        plan.grandArgs.addAll(legacyArgs);
        plan.grandSummarySelect = grandSummarySelect();

        if (!windowPath) {
            // 回退路径：全量 JOIN + 标准分页/排序
            var scope = dataScope.target()
                    .warehouse("po.warehouse").supplier("po.supplier_name").salesman("po.buyer")
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

        // 窗口路径：两套数据范围（头级 / 行级），任一 deny 即全 deny
        var hdrScope = dataScope.target()
                .warehouse("po.warehouse").supplier("po.supplier_name")
                .salesman("po.buyer").creator("po.creator_name").build();
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

        String header = headerPreds(req, "po");
        List<Object> headerArgs = new ArrayList<>();
        headerPreds(req, "po", headerArgs);
        // 头级数据范围片段需要进 SQL（appendTo 只写传入缓冲），参数只绑这一次
        StringBuilder hdrScopeSql = new StringBuilder();
        hdrScope.appendTo(hdrScopeSql, headerArgs);

        StringBuilder l0Scope = new StringBuilder();
        lineScope0.appendTo(l0Scope, lineArgs0);
        StringBuilder l1Scope = new StringBuilder();
        lineScope.appendTo(l1Scope, lineArgs1);

        // h0：头表过滤 + 每单匹配行数（相关计数，行表走 (order_id, goods_code) 索引）
        String h0Cte = """
                WITH h0 AS (
                  SELECT po.order_id AS order_id, po.order_no AS order_no, po.bill_date AS bill_date,
                         po.status AS status, po.supplier_code AS supplier_code,
                         po.supplier_name AS supplier_name, po.buyer AS buyer,
                         (SELECT COUNT(*) FROM purchase_order_detail d0
                          LEFT JOIN rpt_dim_goods gg ON gg.goods_code = d0.goods_code
                          WHERE d0.order_id = po.order_id %1$s %2$s) AS line_cnt
                  FROM purchase_order po
                  WHERE 1=1 %3$s %4$s
                )
                """.formatted(line0, l0Scope, header, hdrScopeSql);
        // hdr：按全局翻页顺序累计行数
        String hdrCte = """
                , hdr AS (
                  SELECT h0.*, SUM(line_cnt) OVER (ORDER BY bill_date DESC, order_no ASC
                      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_end
                  FROM h0
                )
                """;
        // z：只 JOIN 与本页行号区间相交的单据头，页内行号 = 累计前缀 + 单内 ROW_NUMBER
        String zCte = """
                , z AS (
                  SELECT hdr.order_no AS order_no, hdr.bill_date AS bill_date, hdr.status AS status,
                         CASE hdr.status WHEN 'PENDING' THEN '待审核'
                                        WHEN 'APPROVED' THEN '已审核'
                                        WHEN 'AUDITED' THEN '已审核'
                                        WHEN 'CLOSED' THEN '已关闭'
                                        WHEN 'CANCELLED' THEN '已作废'
                                        ELSE hdr.status END AS status_text,
                         CASE hdr.status WHEN 'CANCELLED' THEN '已作废'
                                        WHEN 'CLOSED' THEN '已终止'
                                        ELSE '未入库' END AS inbound_status_text,
                         hdr.supplier_code AS supplier_code, hdr.supplier_name AS supplier_name,
                         hdr.buyer AS buyer,
                         d.goods_code AS goods_code, d.goods_name AS goods_name,
                         dg.barcode AS barcode, dg.base_unit AS base_unit,
                         dg.brand_name AS brand_name, dg.category_name AS category_name,
                         dg.storage_property AS storage_property,
                         dg.large_unit AS large_unit,
                         d.qty AS qty_raw, d.convert_qty AS convert_qty_raw,
                         d.base_qty AS base_qty_raw, d.amount AS amount,
                         d.unit_name AS unit_name_raw,
                         dg.large_convert_qty AS large_convert_qty_raw,
                         CAST(0 AS DECIMAL(18,6)) AS received_base,
                         CAST(0 AS DECIMAL(18,6)) AS received_package,
                         hdr.cum_end - hdr.line_cnt
                           + ROW_NUMBER() OVER (PARTITION BY hdr.order_id
                                                ORDER BY d.goods_code ASC, d.detail_id ASC) AS gidx
                  FROM hdr
                  JOIN purchase_order_detail d ON d.order_id = hdr.order_id
                  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = d.goods_code
                  WHERE hdr.cum_end > ? AND hdr.cum_end - hdr.line_cnt < ? %1$s %2$s
                )
                """.formatted(line1, l1Scope);

        // H2 2.2 缺陷：前序 CTE 含绑定参数(?)、后续 CTE 含窗口函数时，窗口 CTE 结果被误置空。
        // 窗口路径统一把参数渲染成类型安全的字面量（日期 ISO、数字原样、字符串单引号转义）；
        // MySQL 8 无此缺陷，字面量 SQL 同样适用。
        List<Object> countArgsAll = new ArrayList<>();
        countArgsAll.addAll(lineArgs0);
        countArgsAll.addAll(headerArgs);
        String countSqlFinal = renderLiterals(
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
                // 尾部分页边界已在 listTail 直接内联数字；renderLiterals 只消费过滤参数 + z 内层边界
                String sql = h0Cte + hdrCte + zCte + " " + finalSelect + " "
                        + listTail.formatted(offset, (long) offset + limit);
                return renderLiterals(sql, args);
            }
            @Override public List<Object> listArgs(long offset, int limit) { return List.of(); }
        };
        plan.rowEnricher = new ReceivedEnricher(reportJdbc);
        fillSortAndMask(plan);
        return plan;
    }

    /** 窗口路径最终投影：在 z 上算金额/件数派生列；received_* 为占位，由填充器覆写。 */
    private static String windowFinalSelect() {
        String b = baseQty("z");
        return """
                SELECT z.order_no AS order_no, z.bill_date AS bill_date, z.status AS status,
                       z.status_text AS status_text, z.inbound_status_text AS inbound_status_text,
                       z.supplier_code AS supplier_code, z.supplier_name AS supplier_name, z.buyer AS buyer,
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
                       z.received_base AS received_base,
                       z.received_package AS received_package,
                       GREATEST(%1$s - z.received_base, 0) AS unreceived_base,
                       CASE WHEN COALESCE(z.large_convert_qty_raw, 0) > 0
                            THEN GREATEST(%1$s - z.received_base, 0) / z.large_convert_qty_raw
                            ELSE GREATEST(%1$s - z.received_base, 0) END AS unreceived_package,
                       z.brand_name AS brand_name, z.category_name AS category_name,
                       z.storage_property AS storage_property,
                       z.convert_qty_raw AS convert_qty_raw,
                       z.unit_name_raw AS unit_name_raw,
                       z.large_convert_qty_raw AS large_convert_qty_raw
                """.formatted(b);
    }

    /** 回退路径外层投影（含真实 received_base 与执行状态 CASE）。 */
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
                            WHEN %1$s <= 0 THEN '未入库'
                            WHEN t.received_base >= %1$s THEN '已入库'
                            WHEN t.received_base > 0 THEN '部分入库'
                            ELSE '未入库' END AS inbound_status_text,
                       t.supplier_code AS supplier_code, t.supplier_name AS supplier_name, t.buyer AS buyer,
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
                       t.received_base AS received_base,
                       CASE WHEN COALESCE(dg.large_convert_qty, 0) > 0
                            THEN t.received_base / dg.large_convert_qty ELSE t.received_base END AS received_package,
                       GREATEST(%1$s - t.received_base, 0) AS unreceived_base,
                       CASE WHEN COALESCE(dg.large_convert_qty, 0) > 0
                            THEN GREATEST(%1$s - t.received_base, 0) / dg.large_convert_qty
                            ELSE GREATEST(%1$s - t.received_base, 0) END AS unreceived_package,
                       t.brand_name AS brand_name, t.category_name AS category_name,
                       t.storage_property AS storage_property
                """.formatted(b);
    }

    /** 回退路径/合计共用 FROM：行表 × 头表 × 商品维度 × 入库聚合派生表。 */
    private void buildLegacyFrom(ReportQueryRequest req, StringBuilder fw, List<Object> innerArgs) {
        fw.append(" FROM (");
        fw.append("""
                SELECT po.order_no AS order_no, po.bill_date AS bill_date, po.status AS status,
                       po.supplier_code AS supplier_code, po.supplier_name AS supplier_name,
                       po.buyer AS buyer, po.warehouse AS warehouse,
                       d.goods_code AS goods_code, d.goods_name AS goods_name,
                       d.unit_name AS unit_name, d.qty AS qty_raw, d.convert_qty AS convert_qty_raw,
                       d.base_qty AS base_qty_raw, d.amount AS amount,
                       g.barcode AS barcode, g.base_unit AS base_unit,
                       g.brand_name AS brand_name, g.category_name AS category_name,
                       g.storage_property AS storage_property,
                       COALESCE(rcv.recv_qty, 0) * COALESCE(NULLIF(d.convert_qty, 0), 1) AS received_base
                FROM purchase_order po
                JOIN purchase_order_detail d ON d.order_id = po.order_id
                LEFT JOIN rpt_dim_goods g ON g.goods_code = d.goods_code
                LEFT JOIN (
                    -- 经采购订单按订单日期收敛：查一天只聚合这一天订单的入库，
                    -- 且入库不按入库日期截断（订单区间内的订单允许区间后到货）。
                    SELECT por.order_no AS rcv_order, id2.goods_code AS rcv_goods,
                           COALESCE(id2.unit_name, '') AS rcv_unit, SUM(id2.received_qty) AS recv_qty
                    FROM purchase_order por
                    JOIN pur_inbound ih ON ih.source_order = por.order_no AND ih.status = 'APPROVED'
                    JOIN pur_inbound_detail id2 ON id2.inbound_id = ih.inbound_id
                    WHERE por.bill_date BETWEEN ? AND ?
                      AND por.status IN ('APPROVED','AUDITED')
                    GROUP BY por.order_no, id2.goods_code, COALESCE(id2.unit_name, '')
                ) rcv ON rcv.rcv_order = po.order_no AND rcv.rcv_goods = d.goods_code
                     AND rcv.rcv_unit = COALESCE(d.unit_name, '')
                WHERE 1=1
                """);
        // rcv 派生表占位符文本位置最早：其两个日期参数必须排最前
        innerArgs.add(req.range().startDate());
        innerArgs.add(req.range().endDate());
        // po 头表日期谓词由 appendHeaderFilters 统一追加并绑参

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
        appendHeaderFilters(req, fw, innerArgs, "po");
        appendLineFilters(req, fw, innerArgs, "d", "g");
        var scope = dataScope.target()
                .warehouse("po.warehouse").supplier("po.supplier_name").salesman("po.buyer")
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
                       COALESCE(SUM(CASE WHEN COALESCE(dg.large_convert_qty,0) > 0
                           THEN %1$s / dg.large_convert_qty ELSE %1$s END),0) AS package_qty,
                       COALESCE(SUM(t.amount),0) AS amount,
                       COALESCE(SUM(t.received_base),0) AS received_base,
                       COALESCE(SUM(CASE WHEN COALESCE(dg.large_convert_qty,0) > 0
                           THEN t.received_base / dg.large_convert_qty ELSE t.received_base END),0) AS received_package,
                       COALESCE(SUM(GREATEST(%1$s - t.received_base, 0)),0) AS unreceived_base,
                       COALESCE(SUM(CASE WHEN COALESCE(dg.large_convert_qty,0) > 0
                           THEN GREATEST(%1$s - t.received_base, 0) / dg.large_convert_qty
                           ELSE GREATEST(%1$s - t.received_base, 0) END),0) AS unreceived_package
                """.formatted(b);
    }

    private void fillSortAndMask(Plan plan) {
        plan.sortWhitelist.putAll(Map.of(
                "billDate", "bill_date", "orderNo", "order_no", "supplierName", "supplier_name",
                "goodsCode", "goods_code", "baseQty", "base_qty", "amount", "amount",
                "receivedBase", "received_base"));
        plan.defaultOrder = "ORDER BY bill_date DESC, order_no ASC, goods_code ASC";
        plan.maskOverrides.putAll(Map.of(
                "unitPriceBase", "VIEW_PURCHASE_PRICE",
                "boxPrice", "VIEW_PURCHASE_PRICE",
                "amount", "VIEW_PURCHASE_AMOUNT"));
    }

    // ============================ 窗口路径谓词 ============================

    /** 头级谓词片段（日期/状态/订单号/供应商/采购员/仓库）。 */
    private static String headerPreds(ReportQueryRequest req, String po) {
        StringBuilder sb = new StringBuilder();
        appendHeaderFilters(req, sb, null, po);
        return sb.toString();
    }

    private static void headerPreds(ReportQueryRequest req, String po, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        appendHeaderFilters(req, sb, args, po);
    }

    static void appendHeaderFilters(ReportQueryRequest req, StringBuilder sql, List<Object> args, String po) {
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
        String supplier = req.text("supplier");
        if (supplier != null) {
            sql.append(" AND (").append(po).append(".supplier_code LIKE ? OR ")
               .append(po).append(".supplier_name LIKE ?) ");
            if (args != null) {
                args.add("%" + supplier + "%");
                args.add("%" + supplier + "%");
            }
        }
        String buyer = req.text("buyer");
        if (buyer != null) {
            sql.append(" AND ").append(po).append(".buyer LIKE ? ");
            if (args != null) args.add("%" + buyer + "%");
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append(" AND ").append(po).append(".warehouse = ? ");
            if (args != null) args.add(warehouse);
        }
    }

    /** 行级谓词片段（商品关键字/分类/品牌/温区），商品别名 dX、商品维度别名 gX。 */
    private static String linePreds(ReportQueryRequest req, String dAlias, String gAlias) {
        StringBuilder sb = new StringBuilder();
        appendLineFilters(req, sb, null, dAlias, gAlias);
        return sb.toString();
    }

    private static void linePreds(ReportQueryRequest req, String dAlias, String gAlias, List<Object> args) {
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

    // ============================ 行级已入库填充 ============================

    /**
     * 对当前页（≤1000 行，通常来自几十张订单）批量查询已入库量并回填执行量/执行状态；
     * 查的是本页订单号集合（IN），不再按全区间聚合百万入库明细。
     */
    static class ReceivedEnricher implements RowEnricher {

        /** 订单号 / 商品 / 单位 组合键分隔符（不出现在业务编码中）。 */
        private static final String KEY_SEP = Character.toString((char) 1);

        private final JdbcTemplate jdbc;

        ReceivedEnricher(JdbcTemplate jdbc) {
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
            Map<String, BigDecimal> recv = new HashMap<>();
            if (!orderNos.isEmpty()) {
                String inList = String.join(",", java.util.Collections.nCopies(orderNos.size(), "?"));
                String sql = """
                        SELECT ih.source_order AS o, id.goods_code AS g,
                               COALESCE(id.unit_name, '') AS u, SUM(id.received_qty) AS q
                        FROM pur_inbound ih
                        JOIN pur_inbound_detail id ON id.inbound_id = ih.inbound_id
                        WHERE ih.status = 'APPROVED' AND ih.source_order IN (%s)
                        GROUP BY ih.source_order, id.goods_code, COALESCE(id.unit_name, '')
                        """.formatted(inList);
                jdbc.query(sql, rs -> {
                    String key = rs.getString("o") + KEY_SEP + rs.getString("g") + KEY_SEP + rs.getString("u");
                    BigDecimal q = rs.getBigDecimal("q");
                    recv.put(key, q == null ? BigDecimal.ZERO : q);
                }, orderNos.toArray());
            }
            for (Map<String, Object> r : rows) {
                BigDecimal factor = bd(r.get("CONVERT_QTY_RAW"));
                if (factor.signum() == 0) factor = BigDecimal.ONE;
                BigDecimal large = bd(r.get("LARGE_CONVERT_QTY_RAW"));
                BigDecimal base = bd(r.get("BASE_QTY"));
                String unit = r.get("UNIT_NAME_RAW") == null ? "" : String.valueOf(r.get("UNIT_NAME_RAW"));
                String key = str(r.get("ORDER_NO")) + KEY_SEP + str(r.get("GOODS_CODE")) + KEY_SEP + unit;
                BigDecimal recvBase = recv.getOrDefault(key, BigDecimal.ZERO).multiply(factor)
                        .setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
                BigDecimal recvPkg = large.signum() > 0
                        ? recvBase.divide(large, 6, RoundingMode.HALF_UP).stripTrailingZeros()
                        : recvBase;
                BigDecimal unrecv = base.subtract(recvBase).max(BigDecimal.ZERO);
                BigDecimal unrecvPkg = large.signum() > 0
                        ? unrecv.divide(large, 6, RoundingMode.HALF_UP).stripTrailingZeros()
                        : unrecv;
                r.put("RECEIVED_BASE", recvBase);
                r.put("RECEIVED_PACKAGE", recvPkg);
                r.put("UNRECEIVED_BASE", unrecv);
                r.put("UNRECEIVED_PACKAGE", unrecvPkg);

                String status = str(r.get("STATUS"));
                String inboundText;
                if ("CANCELLED".equals(status)) inboundText = "已作废";
                else if ("CLOSED".equals(status)) inboundText = "已终止";
                else if (base.signum() <= 0) inboundText = "未入库";
                else if (recvBase.compareTo(base) >= 0) inboundText = "已入库";
                else if (recvBase.signum() > 0) inboundText = "部分入库";
                else inboundText = "未入库";
                r.put("INBOUND_STATUS_TEXT", inboundText);

                r.remove("CONVERT_QTY_RAW");
                r.remove("UNIT_NAME_RAW");
                r.remove("LARGE_CONVERT_QTY_RAW");
            }
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
