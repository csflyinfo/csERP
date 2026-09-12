package com.erp.report.analysis;

import com.erp.report.common.ReportCamel;
import com.erp.report.common.ReportDateRange;
import com.erp.report.common.ReportQueryRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表17｜商品综合分析图表服务（KPI 环比 / 购销存趋势 / 结构 TOP）。
 *
 * <p>明细表格走 {@link GoodsAnalysisDefinition} 通用引擎（支持导出）；本服务只供
 * KPI 与图表的聚合查询。全部取签收口径 DWS（rpt_dws_sales_d / rpt_dws_sales_bill_d）
 * 与入库口径 DWS（rpt_dws_purchase_d），库存取 ≤ 期末最近一天日结快照。
 * 跨全量数据的经营聚合屏，不做行级数据范围（同工作台口径），金额按字段权限脱敏由控制器负责。
 */
@Service
public class GoodsAnalysisService {

    /** SQL 片段（以 " AND ..." 起始）+ 顺序绑定参数。 */
    private record Filter(String sql, List<Object> args) {
        boolean present() { return !args.isEmpty(); }
    }

    private final JdbcTemplate jdbc;

    public GoodsAnalysisService(@Qualifier("reportJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** KPI：本期间 + 等长上一期间环比。 */
    public Map<String, Object> kpi(Map<String, Object> body) {
        ReportQueryRequest req = ReportQueryRequest.from(body);
        ReportDateRange r = req.range().checkSummarySpan();
        long len = ChronoUnit.DAYS.between(r.startDate(), r.endDate()) + 1;
        LocalDate pEnd = r.startDate().minusDays(1);
        LocalDate pStart = pEnd.minusDays(len - 1);

        Map<String, Object> cur = kpiAggregate(req, r.startDate(), r.endDate());
        Map<String, Object> prev = kpiAggregate(req, pStart, pEnd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startDate", r.startDate().toString());
        out.put("endDate", r.endDate().toString());
        out.put("compareStartDate", pStart.toString());
        out.put("compareEndDate", pEnd.toString());
        for (String k : List.of("purchaseAmount", "salesAmount", "costAmount", "grossProfit",
                "billCount", "customerCount", "salesQty", "endStockAmount")) {
            out.put(k, cur.get(k));
            out.put(k + "Prev", prev.get(k));
            out.put(k + "Rate", ratio(cur.get(k), prev.get(k)));
        }
        out.put("grossProfitRate", ratioOf(cur.get("grossProfit"), cur.get("salesAmount")));
        out.put("grossProfitRatePrev", ratioOf(prev.get("grossProfit"), prev.get("salesAmount")));
        out.put("avgBillAmount", ratioOf(cur.get("salesAmount"), cur.get("billCount")));
        out.put("avgBillAmountPrev", ratioOf(prev.get("salesAmount"), prev.get("billCount")));
        return out;
    }

    private Map<String, Object> kpiAggregate(ReportQueryRequest req, LocalDate start, LocalDate end) {
        Filter dws = dwsFilter(req);

        List<Object> salesArgs = new ArrayList<>();
        salesArgs.add(start); salesArgs.add(end);
        salesArgs.addAll(dws.args());
        Map<String, Object> sales = jdbc.queryForMap("""
                SELECT COALESCE(SUM(signed_amount - COALESCE(return_amount,0)),0) AS sales_amount,
                       COALESCE(SUM(signed_cost_amount - COALESCE(return_cost_amount,0)),0) AS cost_amount,
                       COALESCE(SUM(signed_qty_base - COALESCE(return_qty_base,0)),0) AS sales_qty,
                       COUNT(DISTINCT customer_code) AS customer_count
                  FROM rpt_dws_sales_d
                 WHERE bill_date BETWEEN ? AND ?
                """ + dws.sql(), salesArgs.toArray());

        // 账单 DWS 无商品/品牌/分类列：只吃仓库筛选，避免口径串味
        List<Object> billArgs = new ArrayList<>();
        billArgs.add(start); billArgs.add(end);
        String warehouse = req.text("warehouse");
        if (warehouse != null) billArgs.add(warehouse);
        Map<String, Object> bills = jdbc.queryForMap("""
                SELECT COUNT(DISTINCT bill_no) AS bill_count
                  FROM rpt_dws_sales_bill_d
                 WHERE bill_date BETWEEN ? AND ? AND bill_type = 'SIGN'
                """ + (warehouse != null ? " AND warehouse = ? " : ""), billArgs.toArray());

        List<Object> purArgs = new ArrayList<>();
        purArgs.add(start); purArgs.add(end);
        purArgs.addAll(dws.args());
        Map<String, Object> purchase = jdbc.queryForMap("""
                SELECT COALESCE(SUM(inbound_amount - COALESCE(return_amount,0)),0) AS purchase_amount
                  FROM rpt_dws_purchase_d
                 WHERE bill_date BETWEEN ? AND ?
                """ + dws.sql(), purArgs.toArray());

        Filter snap = snapshotFilter(req);
        List<Object> stockArgs = new ArrayList<>();
        stockArgs.add(end);
        stockArgs.addAll(snap.args());
        Map<String, Object> stock = jdbc.queryForMap("""
                SELECT COALESCE(SUM(s.stock_amount),0) AS end_stock_amount
                  FROM inv_stock_daily_snapshot s
                  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = s.goods_code
                 WHERE s.snapshot_date = (
                       SELECT MAX(snapshot_date) FROM inv_stock_daily_snapshot WHERE snapshot_date <= ?)
                """ + snap.sql(), stockArgs.toArray());

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(sales);
        merged.putAll(bills);
        merged.putAll(purchase);
        merged.putAll(stock);
        Map<String, Object> out = ReportCamel.camelize(merged);
        out.put("grossProfit", num(sales.get("SALES_AMOUNT")) - num(sales.get("COST_AMOUNT")));
        return out;
    }

    /**
     * 购销存趋势：granularity=day|month。
     * 返回 [{period, salesAmount, purchaseAmount, costAmount, grossProfit, salesQty, purchaseQty}]
     * 趋势只接期间与粒度（不接仓库/分类筛选：UNION 两侧过滤需成对加，图表保持全量口径）。
     */
    public List<Map<String, Object>> trend(Map<String, Object> body, String granularity) {
        ReportQueryRequest req = ReportQueryRequest.from(body);
        req.range().checkSummarySpan();
        String bucket = "month".equals(granularity)
                ? "SUBSTRING(CAST(bill_date AS VARCHAR), 1, 7)" : "CAST(bill_date AS VARCHAR(10))";
        String sql = """
                SELECT period,
                       COALESCE(SUM(sales_amount),0) AS sales_amount,
                       COALESCE(SUM(purchase_amount),0) AS purchase_amount,
                       COALESCE(SUM(cost_amount),0) AS cost_amount,
                       COALESCE(SUM(sales_amount - cost_amount),0) AS gross_profit,
                       COALESCE(SUM(sales_qty),0) AS sales_qty,
                       COALESCE(SUM(purchase_qty),0) AS purchase_qty
                  FROM (
                    SELECT %s AS period,
                           signed_amount - COALESCE(return_amount,0) AS sales_amount,
                           CAST(0 AS DECIMAL(18,2)) AS purchase_amount,
                           signed_cost_amount - COALESCE(return_cost_amount,0) AS cost_amount,
                           signed_qty_base - COALESCE(return_qty_base,0) AS sales_qty,
                           CAST(0 AS DECIMAL(18,4)) AS purchase_qty
                      FROM rpt_dws_sales_d
                     WHERE bill_date BETWEEN ? AND ?
                    UNION ALL
                    SELECT %s,
                           CAST(0 AS DECIMAL(18,2)),
                           inbound_amount - COALESCE(return_amount,0),
                           CAST(0 AS DECIMAL(18,2)), CAST(0 AS DECIMAL(18,4)),
                           inbound_qty_base - COALESCE(return_qty_base,0)
                      FROM rpt_dws_purchase_d
                     WHERE bill_date BETWEEN ? AND ?
                  ) z
                 GROUP BY period ORDER BY period ASC
                """.formatted(bucket, bucket);
        return ReportCamel.camelize(jdbc.queryForList(sql,
                req.range().startDate(), req.range().endDate(),
                req.range().startDate(), req.range().endDate()));
    }

    /** 结构区：分类占比 / TOP20 商品（按金额或毛利）/ TOP10 客户。 */
    public Map<String, Object> structure(Map<String, Object> body) {
        ReportQueryRequest req = ReportQueryRequest.from(body);
        req.range().checkSummarySpan();
        Filter dws = dwsFilter(req);
        String metric = "profit".equals(req.text("metric")) ? "gross_profit" : "sales_amount";

        List<Object> catArgs = rangeArgs(req);
        catArgs.addAll(dws.args());
        String category = """
                SELECT COALESCE(NULLIF(category_name,''),'未分类') AS name,
                       COALESCE(SUM(signed_amount - COALESCE(return_amount,0)),0) AS "value"
                  FROM rpt_dws_sales_d
                 WHERE bill_date BETWEEN ? AND ?
                """ + dws.sql() + " GROUP BY category_name ORDER BY \"value\" DESC LIMIT 20";
        List<Map<String, Object>> categoryPie =
                ReportCamel.camelize(jdbc.queryForList(category, catArgs.toArray()));

        List<Object> goodsArgs = rangeArgs(req);
        goodsArgs.addAll(dws.args());
        String goods = """
                SELECT goods_code AS code, MAX(goods_name) AS name,
                       COALESCE(SUM(signed_amount - COALESCE(return_amount,0)),0) AS sales_amount,
                       COALESCE(SUM(signed_cost_amount - COALESCE(return_cost_amount,0)),0) AS cost_amount,
                       COALESCE(SUM(signed_amount - COALESCE(return_amount,0)
                                    - (signed_cost_amount - COALESCE(return_cost_amount,0))),0) AS gross_profit
                  FROM rpt_dws_sales_d
                 WHERE bill_date BETWEEN ? AND ?
                """ + dws.sql() + " GROUP BY goods_code ORDER BY " + metric + " DESC LIMIT 20";
        List<Map<String, Object>> topGoods =
                ReportCamel.camelize(jdbc.queryForList(goods, goodsArgs.toArray()));

        List<Object> custArgs = rangeArgs(req);
        custArgs.addAll(dws.args());
        String customer = """
                SELECT customer_code AS code, MAX(customer_name) AS name,
                       COALESCE(SUM(signed_amount - COALESCE(return_amount,0)),0) AS sales_amount
                  FROM rpt_dws_sales_d
                 WHERE bill_date BETWEEN ? AND ?
                """ + dws.sql()
                + " GROUP BY customer_code, customer_name ORDER BY sales_amount DESC LIMIT 10";
        List<Map<String, Object>> topCustomers =
                ReportCamel.camelize(jdbc.queryForList(customer, custArgs.toArray()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categoryPie", categoryPie);
        out.put("topGoods", topGoods);
        out.put("topCustomers", topCustomers);
        out.put("metric", metric);
        return out;
    }

    // ============================ 内部 ============================

    private static List<Object> rangeArgs(ReportQueryRequest req) {
        List<Object> args = new ArrayList<>();
        args.add(req.range().startDate());
        args.add(req.range().endDate());
        return args;
    }

    /**
     * DWS 公共筛选（销售/采购 DWS 同构列：warehouse/category_name/brand_name/goods_*）。
     * 片段以 " AND ..." 起始，参数顺序与片段严格一致。
     */
    private Filter dwsFilter(ReportQueryRequest req) {
        StringBuilder sb = new StringBuilder();
        List<Object> args = new ArrayList<>();
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sb.append(" AND warehouse = ? ");
            args.add(warehouse);
        }
        for (var e : List.of(Map.entry("categoryName", "category_name"),
                             Map.entry("brandName", "brand_name"))) {
            String v = req.text(e.getKey());
            if (v != null) {
                sb.append(" AND ").append(e.getValue()).append(" = ? ");
                args.add(v);
            }
        }
        String goods = req.text("goods");
        if (goods != null) {
            sb.append(" AND (goods_code LIKE ? OR goods_name LIKE ?) ");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
        }
        return new Filter(sb.toString(), args);
    }

    /** 快照筛选（档案列在 rpt_dim_goods dg 上）。 */
    private Filter snapshotFilter(ReportQueryRequest req) {
        StringBuilder sb = new StringBuilder();
        List<Object> args = new ArrayList<>();
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sb.append(" AND s.warehouse = ? ");
            args.add(warehouse);
        }
        for (var e : List.of(Map.entry("categoryName", "dg.category_name"),
                             Map.entry("brandName", "dg.brand_name"))) {
            String v = req.text(e.getKey());
            if (v != null) {
                sb.append(" AND ").append(e.getValue()).append(" = ? ");
                args.add(v);
            }
        }
        String goods = req.text("goods");
        if (goods != null) {
            sb.append(" AND (s.goods_code LIKE ? OR dg.goods_name LIKE ? OR dg.barcode LIKE ?) ");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
        }
        return new Filter(sb.toString(), args);
    }

    private static double num(Object v) {
        return v == null ? 0d : ((Number) v).doubleValue();
    }

    /** 环比：(本期−上期)/上期；上期为 0 返回 null（前端显示「—」）。 */
    private static Double ratio(Object cur, Object prev) {
        double p = num(prev);
        if (p == 0d) return null;
        return (num(cur) - p) / p;
    }

    private static Double ratioOf(Object a, Object b) {
        double d = num(b);
        return d == 0d ? null : num(a) / d;
    }
}
