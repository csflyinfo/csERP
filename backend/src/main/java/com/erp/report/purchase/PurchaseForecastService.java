package com.erp.report.purchase;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.common.ReportCamel;
import com.erp.report.common.ReportDateRange;
import com.erp.report.common.ReportGuard;
import com.erp.common.util.BillNoGenerator;
import com.erp.system.OperationAction;
import com.erp.system.OperationLogService;
import com.erp.system.OperationModule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报表5｜商品采购预测分析。
 *
 * <p>口径（方案 §5）：销量取司机签收净销量（v_rpt_sales_detail：签收−退货，仅已审核，拒收不计），
 * 日均=期间销量÷日历天数；建议量=MAX(ROUND(日均×预销天数 − 可用库存 − 采购在途, 0), 0)；
 * 在途由已审核未终止采购订单行实时减已审核入库量聚合（不用订单余额字段）；
 * 无销量商品若设了库存下限且可用+在途低于下限，建议补到下限。
 * 价格取期间内最近入库单小单位含税价，兜底 base_goods.latest_purchase_price（K3 含税）。
 */
@Service
public class PurchaseForecastService {

    private static final int MAX_PRE_SALE_DAYS = 90;
    private static final int FORECAST_ROW_CAP = 20_000;

    private final JdbcTemplate reportJdbc;
    private final JdbcTemplate primaryJdbc;
    private final DataScopeService dataScope;
    private final ReportGuard guard;
    private final BillNoGenerator billNoGen;
    private final OperationLogService opLog;

    public PurchaseForecastService(@Qualifier("reportJdbcTemplate") JdbcTemplate reportJdbc,
                                   JdbcTemplate primaryJdbc, DataScopeService dataScope,
                                   ReportGuard guard, BillNoGenerator billNoGen,
                                   OperationLogService opLog) {
        this.reportJdbc = reportJdbc;
        this.primaryJdbc = primaryJdbc;
        this.dataScope = dataScope;
        this.guard = guard;
        this.billNoGen = billNoGen;
        this.opLog = opLog;
    }

    /** 预测结果（点「查询」才计算，不自动算）。 */
    public List<Map<String, Object>> forecast(Map<String, Object> body) {
        Params p = Params.parse(body);
        return guard.run("rpt.purchase_forecast", body, false, () -> compute(p));
    }

    private List<Map<String, Object>> compute(Params p) {
        var scope = dataScope.target()
                .warehouse("g.default_warehouse")
                .supplier("sup.supplier_name")
                .salesman("COALESCE(NULLIF(g.goods_manager,''), sup.default_buyer)")
                .goodsColumn("g.goods_code")
                .build();
        if (scope.isDenyAll()) return List.of();

        LocalDate end = p.range.endDate();
        LocalDate start7 = end.minusDays(6);
        LocalDate start30 = end.minusDays(29);
        // 仓库谓词片段（别名与各派生表内别名保持一致）
        String whCond = p.warehouse == null ? "" : " AND sb.warehouse = ? ";
        String whCondSales = p.warehouse == null ? "" : " AND vs.warehouse = ? ";
        String whCondOnWay = p.warehouse == null ? "" : " AND po2.warehouse = ? ";

        // 全部指标改为「整表一趟聚合的派生表 JOIN」：旧写法对每个商品做 3 个销量相关子查询
        // + 嵌套两层的在途子查询（里层还有逐行换算率 MIN），1,000 商品时数千次探测视图，
        // 在 10 万行/日规模下不可用。派生表在 V112 去相关视图之上每窗口仅全扫一趟。
        String salesJoin = """
                LEFT JOIN (
                    SELECT vs.goods_code AS goods_code, SUM(vs.base_qty) AS qty
                    FROM v_rpt_sales_detail vs
                    WHERE vs.bill_date BETWEEN ? AND ?
                """;
        StringBuilder sql = new StringBuilder("""
                SELECT g.goods_code AS goods_code, g.goods_name AS goods_name, g.barcode AS barcode,
                       g.base_unit AS base_unit, g.goods_manager AS goods_manager,
                       g.storage_property AS storage_property, g.brand_name AS brand_name,
                       g.category_name AS category_name, g.spec AS spec, g.tax_rate AS tax_rate,
                       g.default_warehouse AS default_warehouse,
                       COALESCE(g.stock_lower_limit,0) AS stock_lower_limit,
                       sup.supplier_code AS main_supplier_code,
                       g.default_supplier AS main_supplier_name,
                       COALESCE(NULLIF(g.goods_manager,''), sup.default_buyer) AS buyer,
                       dg.large_unit AS large_unit,
                       COALESCE(dg.large_convert_qty,0) AS large_convert_qty,
                       COALESCE(sp.qty,0) AS sales_qty_period,
                       COALESCE(s7.qty,0) AS sales_qty_7d,
                       COALESCE(s30.qty,0) AS sales_qty_30d,
                       COALESCE(ba.qty,0) AS available_qty,
                       COALESCE(ow.qty,0) AS on_way_qty,
                       lp.supplier_code AS recent_supplier_code,
                       lp.supplier_name AS recent_supplier_name,
                       lp.unit_price_base AS recent_price,
                       COALESCE(NULLIF(lp.unit_price_base,0), g.latest_purchase_price, 0) AS latest_price
                FROM base_goods g
                LEFT JOIN rpt_dim_goods dg ON dg.goods_code = g.goods_code
                LEFT JOIN base_supplier sup ON sup.supplier_name = g.default_supplier
                """);
        sql.append(salesJoin).append(whCondSales).append("""
                    GROUP BY vs.goods_code
                ) sp ON sp.goods_code = g.goods_code
                """);
        sql.append(salesJoin).append(whCondSales).append("""
                    GROUP BY vs.goods_code
                ) s7 ON s7.goods_code = g.goods_code
                """);
        sql.append(salesJoin).append(whCondSales).append("""
                    GROUP BY vs.goods_code
                ) s30 ON s30.goods_code = g.goods_code
                """);
        sql.append("""
                LEFT JOIN (
                    SELECT sb.goods_code AS goods_code, SUM(sb.available_qty) AS qty
                    FROM inv_stock_balance sb WHERE 1=1
                """);
        sql.append(whCond).append("""
                    GROUP BY sb.goods_code
                ) ba ON ba.goods_code = g.goods_code
                """);
        // 在途 = 已审核订单未入库余量；入库量用与 #1 报表相同的整表聚合派生表（订单单位×本行换算率）
        sql.append("""
                LEFT JOIN (
                    SELECT pod.goods_code AS goods_code,
                           SUM(COALESCE(NULLIF(pod.base_qty,0), pod.qty * pod.convert_qty)
                               - COALESCE(rcv.recv_qty * COALESCE(NULLIF(pod.convert_qty,0),1), 0)) AS qty
                    FROM purchase_order po2
                    JOIN purchase_order_detail pod ON pod.order_id = po2.order_id
                    LEFT JOIN (
                        SELECT ih.source_order AS rcv_order, id2.goods_code AS rcv_goods,
                               COALESCE(id2.unit_name,'') AS rcv_unit,
                               SUM(id2.received_qty) AS recv_qty
                        FROM pur_inbound ih
                        JOIN pur_inbound_detail id2 ON id2.inbound_id = ih.inbound_id
                        WHERE ih.status = 'APPROVED'
                        GROUP BY ih.source_order, id2.goods_code, COALESCE(id2.unit_name,'')
                    ) rcv ON rcv.rcv_order = po2.order_no AND rcv.rcv_goods = pod.goods_code
                         AND rcv.rcv_unit = COALESCE(pod.unit_name,'')
                    WHERE po2.status IN ('APPROVED','AUDITED')
                """);
        sql.append(whCondOnWay).append("""
                    GROUP BY pod.goods_code
                ) ow ON ow.goods_code = g.goods_code
                """);
        sql.append("""
                LEFT JOIN (
                    SELECT x.goods_code, x.supplier_code, x.supplier_name, x.unit_price_base
                    FROM (
                        SELECT vs.goods_code, vs.supplier_code, vs.supplier_name, vs.unit_price_base,
                               ROW_NUMBER() OVER (PARTITION BY vs.goods_code
                                   ORDER BY vs.bill_date DESC, vs.bill_no DESC) AS rn
                        FROM v_rpt_purchase_detail vs
                        WHERE vs.bill_type = '采购入库' AND vs.bill_date BETWEEN ? AND ?
                    ) x WHERE x.rn = 1
                ) lp ON lp.goods_code = g.goods_code
                WHERE g.can_purchase = TRUE
                """);

        List<Object> args = new ArrayList<>();
        // 三个销量窗口，每个窗口两个日期 + 可选仓库（日期必须紧挨在各自占位符前）
        addWindowArgs(args, p.range.startDate(), end, p.warehouse, whCondSales);
        addWindowArgs(args, start7, end, p.warehouse, whCondSales);
        addWindowArgs(args, start30, end, p.warehouse, whCondSales);
        if (p.warehouse != null) args.add(p.warehouse);          // 库存
        if (p.warehouse != null) args.add(p.warehouse);          // 在途
        args.add(p.range.startDate()); args.add(end);            // 最近入库窗口

        appendFilters(p, sql, args);
        scope.appendTo(sql, args);
        sql.append(" LIMIT ").append(FORECAST_ROW_CAP + 1);

        List<Map<String, Object>> raw = reportJdbc.queryForList(sql.toString(), args.toArray());
        if (raw.size() > FORECAST_ROW_CAP) {
            throw new IllegalArgumentException("预测商品超过 2 万种，请增加筛选条件");
        }

        long periodDays = ChronoUnit.DAYS.between(p.range.startDate(), end) + 1;
        Map<String, String> pendingMap = loadPendingSuggestedOrders();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r0 : raw) {
            Map<String, Object> r = ReportCamel.camelize(r0);
            BigDecimal periodSales = bd(r.get("salesQtyPeriod"));
            BigDecimal avg = periodDays <= 0 ? BigDecimal.ZERO
                    : periodSales.divide(BigDecimal.valueOf(periodDays), 4, RoundingMode.HALF_UP);
            BigDecimal available = bd(r.get("availableQty"));
            BigDecimal onWay = bd(r.get("onWayQty"));
            BigDecimal demand = avg.multiply(BigDecimal.valueOf(p.preSaleDays));
            BigDecimal suggest = demand.subtract(available)
                    .subtract(p.includeOnWay ? onWay : BigDecimal.ZERO)
                    .setScale(0, RoundingMode.HALF_UP);
            if (suggest.signum() < 0) suggest = BigDecimal.ZERO;
            // 无销量商品：可用+在途低于库存下限时补到下限
            if (periodSales.signum() == 0) {
                BigDecimal lower = bd(r.get("stockLowerLimit"));
                BigDecimal gap = lower.subtract(available)
                        .subtract(p.includeOnWay ? onWay : BigDecimal.ZERO);
                if (lower.signum() > 0 && gap.signum() > 0) suggest = gap.setScale(0, RoundingMode.HALF_UP);
            }
            if (suggest.signum() == 0 && !p.showZero) continue;
            if (periodSales.signum() == 0 && !p.showUnsold) continue;

            BigDecimal conv = bd(r.get("largeConvertQty"));
            boolean hasLarge = conv.signum() > 0;
            String mainCode = str(r.get("mainSupplierCode"));
            String recentCode = str(r.get("recentSupplierCode"));
            boolean useRecent = "recent".equals(p.supplierMode);
            String supplierCode = useRecent ? (recentCode != null ? recentCode : mainCode) : mainCode;
            String supplierName = useRecent
                    ? (str(r.get("recentSupplierName")) != null ? str(r.get("recentSupplierName"))
                        : str(r.get("mainSupplierName")))
                    : str(r.get("mainSupplierName"));
            BigDecimal latestPrice = bd(r.get("latestPrice"));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("supplierCode", supplierCode);
            row.put("supplierName", supplierName);
            row.put("hasMainSupplier", str(r.get("mainSupplierName")) != null);
            row.put("buyer", str(r.get("buyer")));
            row.put("goodsCode", str(r.get("goodsCode")));
            row.put("goodsName", str(r.get("goodsName")));
            row.put("barcode", str(r.get("barcode")));
            row.put("baseUnit", str(r.get("baseUnit")));
            row.put("salesQty7d", bd(r.get("salesQty7d")));
            row.put("salesQty30d", bd(r.get("salesQty30d")));
            row.put("salesQtyPeriod", periodSales);
            row.put("avgDailySales", avg);
            row.put("availableBase", available);
            row.put("availablePackage", hasLarge ? available.divide(conv, 4, RoundingMode.HALF_UP) : available);
            row.put("saleDays", avg.signum() > 0
                    ? available.divide(avg, 1, RoundingMode.HALF_UP) : null);
            row.put("onWayQty", onWay);
            row.put("suggestBase", suggest);
            row.put("suggestPackage", hasLarge ? suggest.divide(conv, 4, RoundingMode.HALF_UP) : suggest);
            row.put("purchaseBase", suggest);
            row.put("purchasePackage", hasLarge ? suggest.divide(conv, 4, RoundingMode.HALF_UP) : suggest);
            row.put("largeUnit", str(r.get("largeUnit")));
            row.put("largeConvertQty", conv);
            row.put("recentSupplierName", str(r.get("recentSupplierName")));
            row.put("latestPrice", latestPrice.setScale(6, RoundingMode.HALF_UP));
            row.put("boxPrice", hasLarge
                    ? latestPrice.multiply(conv).setScale(6, RoundingMode.HALF_UP)
                    : latestPrice.setScale(6, RoundingMode.HALF_UP));
            row.put("brandName", str(r.get("brandName")));
            row.put("categoryName", str(r.get("categoryName")));
            row.put("storageProperty", str(r.get("storageProperty")));
            row.put("defaultWarehouse", str(r.get("defaultWarehouse")));
            row.put("pendingOrderNos", pendingMap.getOrDefault(str(r.get("goodsCode")), ""));
            rows.add(row);
        }
        // 默认按建议量倒序，采购员一眼看到最该下单的商品
        rows.sort((a, b) -> ((BigDecimal) b.get("suggestBase")).compareTo((BigDecimal) a.get("suggestBase")));
        return rows;
    }

    /** 按供应商+采购员+仓库分组生成待审核采购订单，返回生成的单号清单。 */
    @Transactional
    public Map<String, Object> generateOrders(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = body.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        if (items.isEmpty()) throw new IllegalArgumentException("请勾选需要生成采购订单的商品");
        String genMode = "package".equals(String.valueOf(body.get("genMode"))) ? "package" : "qty";
        String queryWarehouse = str(body.get("warehouse"));
        String batchNo = "CGYC" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String sourceParams = body.get("params") == null ? "" : String.valueOf(body.get("params"));

        // key = 供应商编码|采购员|仓库 → 一张订单
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            String supplierCode = str(it.get("supplierCode"));
            String supplierName = str(it.get("supplierName"));
            if (supplierCode == null && supplierName == null) {
                throw new IllegalArgumentException("商品「" + str(it.get("goodsName")) + "」未设置供应商，无法生成采购订单");
            }
            String buyer = str(it.get("buyer"));
            if (buyer == null) {
                throw new IllegalArgumentException("商品「" + str(it.get("goodsName")) + "」无对应采购员，无法生成采购订单");
            }
            String warehouse = queryWarehouse != null ? queryWarehouse : str(it.get("warehouse"));
            if (warehouse == null) warehouse = str(it.get("defaultWarehouse"));
            if (warehouse == null) {
                throw new IllegalArgumentException("商品「" + str(it.get("goodsName")) + "」未指定仓库，请先选择查询仓库");
            }
            BigDecimal qty = bd(it.get(genMode.equals("package") ? "purchasePackage" : "purchaseBase"));
            if (qty.signum() <= 0) {
                throw new IllegalArgumentException("商品「" + str(it.get("goodsName")) + "」采购数量必须大于 0");
            }
            String key = (supplierCode == null ? "" : supplierCode) + "|" + buyer + "|" + warehouse;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(it);
        }

        List<String> orderNos = new ArrayList<>();
        int goodsKinds = 0;
        for (List<Map<String, Object>> group : groups.values()) {
            Map<String, Object> first = group.get(0);
            String supplierCode = str(first.get("supplierCode"));
            String supplierName = str(first.get("supplierName"));
            String buyer = str(first.get("buyer"));
            String warehouse = queryWarehouse != null ? queryWarehouse
                    : (str(first.get("warehouse")) != null ? str(first.get("warehouse"))
                        : str(first.get("defaultWarehouse")));
            String orderId = "PO" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
            String orderNo = billNoGen.nextNo(BillNoGenerator.BillType.PURCHASE_ORDER, "purchase_order", "order_no");
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (Map<String, Object> it : group) {
                BigDecimal conv = bd(it.get("largeConvertQty"));
                boolean byPackage = genMode.equals("package") && conv.signum() > 0;
                BigDecimal qty = bd(it.get(byPackage ? "purchasePackage" : "purchaseBase"));
                BigDecimal baseQty = byPackage ? qty.multiply(conv).setScale(4, RoundingMode.HALF_UP) : qty;
                BigDecimal priceBase = bd(it.get("latestPrice"));
                BigDecimal price = byPackage
                        ? priceBase.multiply(conv).setScale(6, RoundingMode.HALF_UP)
                        : priceBase.setScale(6, RoundingMode.HALF_UP);
                BigDecimal amount = price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
                totalAmount = totalAmount.add(amount);
                String detailId = "POD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
                primaryJdbc.update("""
                        INSERT INTO purchase_order_detail (detail_id, order_id, goods_code, goods_name, spec,
                            unit_name, unit_level, convert_qty, qty, base_qty, price, amount, tax_rate, remark)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '采购预测生成')
                        """,
                        detailId, orderId, str(it.get("goodsCode")), str(it.get("goodsName")), str(it.get("spec")),
                        byPackage ? str(it.get("largeUnit")) : str(it.get("baseUnit")),
                        byPackage ? 3 : 1,
                        byPackage ? conv : BigDecimal.ONE, qty, baseQty, price, amount,
                        str(it.get("taxRate")));
                goodsKinds++;
                primaryJdbc.update("""
                        INSERT INTO report_purchase_suggest_log(batch_no, goods_code, goods_name,
                            supplier_code, supplier_name, warehouse, qty, base_qty, source_params,
                            purchase_order_no, created_by, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                        batchNo, str(it.get("goodsCode")), str(it.get("goodsName")),
                        supplierCode, supplierName, warehouse, qty, baseQty, sourceParams,
                        orderNo, currentUser());
            }
            primaryJdbc.update("""
                    INSERT INTO purchase_order (order_id, order_no, supplier_code, supplier_name, buyer, warehouse,
                        bill_date, amount, unpaid_amount, status, creator_name, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, '采购预测生成')
                    """,
                    orderId, orderNo, supplierCode, supplierName, buyer, warehouse,
                    LocalDate.now(), totalAmount, totalAmount, currentUser());
            orderNos.add(orderNo);
            opLog.log(OperationModule.PURCHASE_ORDER, OperationAction.CREATE, orderNo,
                    "采购预测生成待审核采购订单 " + orderNo + "，" + group.size() + " 种商品，金额 " + totalAmount);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batchNo);
        result.put("orderCount", orderNos.size());
        result.put("goodsKinds", goodsKinds);
        result.put("orderNos", orderNos);
        return result;
    }

    /** 各商品「预测生成且仍待审核」的订单号（防重黄叹号）。 */
    private Map<String, String> loadPendingSuggestedOrders() {
        List<Map<String, Object>> rows = reportJdbc.queryForList("""
                SELECT l.goods_code AS goods_code, po.order_no AS order_no
                FROM report_purchase_suggest_log l
                JOIN purchase_order po ON po.order_no = l.purchase_order_no
                WHERE po.status = 'PENDING'
                ORDER BY po.order_no
                """);
        Map<String, String> map = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String code = str(r.get("GOODS_CODE"));
            String no = str(r.get("ORDER_NO"));
            map.merge(code, no, (a, b) -> a + "," + b);
        }
        return map;
    }

    private void appendFilters(Params p, StringBuilder sql, List<Object> args) {
        if (p.supplier != null) {
            sql.append(" AND (sup.supplier_code LIKE ? OR g.default_supplier LIKE ?) ");
            args.add("%" + p.supplier + "%");
            args.add("%" + p.supplier + "%");
        }
        if (p.category != null) {
            sql.append(" AND g.category_name = ? ");
            args.add(p.category);
        }
        if (p.brand != null) {
            sql.append(" AND g.brand_name = ? ");
            args.add(p.brand);
        }
        if (p.buyer != null) {
            sql.append(" AND COALESCE(NULLIF(g.goods_manager,''), sup.default_buyer) LIKE ? ");
            args.add("%" + p.buyer + "%");
        }
    }

    private static void addWindowArgs(List<Object> args, LocalDate start, LocalDate end,
                                      String warehouse, String whCond) {
        args.add(start);
        args.add(end);
        if (!whCond.isEmpty()) args.add(warehouse);
    }

    private static String currentUser() {
        var u = com.erp.common.security.CurrentUser.get();
        if (u == null) return null;
        return u.displayName() != null ? u.displayName() : u.username();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(String.valueOf(o));
    }

    /** 计算参数（Q5：预销天数 1~90，默认 20）。 */
    static class Params {
        ReportDateRange range;
        Integer preSaleDays = 20;
        boolean includeOnWay = true;
        String supplierMode = "main";   // main | recent
        String warehouse;
        String supplier;
        String category;
        String brand;
        String buyer;
        boolean showUnsold = false;
        boolean showZero = false;

        @SuppressWarnings("unchecked")
        static Params parse(Map<String, Object> body) {
            Params p = new Params();
            p.range = ReportDateRange.from(body);
            Map<String, Object> f = body.get("filters") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            p.warehouse = s(f.get("warehouse"));
            p.supplier = s(f.get("supplier"));
            p.category = s(f.get("categoryName"));
            p.brand = s(f.get("brandName"));
            p.buyer = s(f.get("buyer"));
            Object days = body.get("preSaleDays");
            if (days == null) days = f.get("preSaleDays");
            if (days != null) {
                try {
                    p.preSaleDays = Integer.parseInt(String.valueOf(days).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("预销天数必须是 1~90 的正整数");
                }
                if (p.preSaleDays < 1 || p.preSaleDays > MAX_PRE_SALE_DAYS) {
                    throw new IllegalArgumentException("预销天数必须是 1~90 的正整数");
                }
            }
            Object onWay = body.get("includeOnWay");
            if (onWay == null) onWay = f.get("includeOnWay");
            if (onWay != null) p.includeOnWay = Boolean.parseBoolean(String.valueOf(onWay));
            Object mode = body.get("supplierMode");
            if (mode == null) mode = f.get("supplierMode");
            if (mode != null && ("main".equals(String.valueOf(mode)) || "recent".equals(String.valueOf(mode)))) {
                p.supplierMode = String.valueOf(mode);
            }
            Object unsold = body.get("showUnsold");
            if (unsold == null) unsold = f.get("showUnsold");
            if (unsold != null) p.showUnsold = Boolean.parseBoolean(String.valueOf(unsold));
            Object zero = body.get("showZeroSuggestion");
            if (zero == null) zero = f.get("showZeroSuggestion");
            if (zero != null) p.showZero = Boolean.parseBoolean(String.valueOf(zero));
            return p;
        }

        private static String s(Object o) {
            if (o == null) return null;
            String v = String.valueOf(o).trim();
            return v.isEmpty() ? null : v;
        }
    }
}
