package com.erp.sales;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.inventory.service.InventoryCostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 销售出库单 REST 端点（与采购入库对称）。
 *
 * <p>生命周期：
 * <ol>
 *   <li>{@code POST /sales/outbound/create} —— 从已审核销售订单生成 PENDING 出库单，接受拆行 payload。
 *       前端可指定 {@code batchNo} 从具体批次扣，也可省略走 FIFO（当前实现依赖 InventoryCostService 从
 *       {@code inv_batch_stock} 找指定批次；如需 FIFO 请把 batchNo 留空）。</li>
 *   <li>{@code POST /sales/outbound/audit} —— 扣减 {@code inv_batch_stock} + 更新 {@code inv_stock_balance}
 *       + 回写销售订单 {@code outbound_status}（未/部分/已出库）+ 自动生成
 *       {@code sales_receipt}（PENDING）。审核后不再直接生成 fin_ar（决策：改由收货单审核触发）。</li>
 * </ol>
 */
@RestController
@RequestMapping("/sales")
public class SalesOutboundController {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbcTemplate;
    private final InventoryCostService inventoryCostService;
    private final SalesReceiptController receiptController;
    private final com.erp.common.util.BillNoGenerator billNoGen;

    public SalesOutboundController(JdbcTemplate jdbcTemplate,
                                    InventoryCostService inventoryCostService,
                                    SalesReceiptController receiptController,
                                    com.erp.common.util.BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.inventoryCostService = inventoryCostService;
        this.receiptController = receiptController;
        this.billNoGen = billNoGen;
    }

    // ============ 列表 & 详情 ============

    @PostMapping("/outbound/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        // 列表返回全部字段，前端根据 module-config 展示需要的列
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT h.outbound_id, h.outbound_no, h.source_order, h.customer, h.warehouse, h.bill_date,
                       h.qty, h.amount, h.cost_amount, h.status, h.stock_updated, h.receipt_generated, h.created_at,
                       h.salesman, h.territory, h.route_line, h.driver, h.remark,
                       -- 派生：出库商品数（SKU 种类）= COUNT(DISTINCT goods_code)；件数 = SUM(qty)
                       (SELECT COUNT(DISTINCT goods_code) FROM sales_outbound_detail d WHERE d.outbound_id = h.outbound_id) AS sku_count,
                       (SELECT COALESCE(SUM(qty), 0) FROM sales_outbound_detail d WHERE d.outbound_id = h.outbound_id) AS piece_count
                FROM sales_outbound h
                ORDER BY h.created_at DESC, h.outbound_no DESC
                """);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            row.put("statusText", switch (str(row.get("status"))) {
                case "PENDING" -> "待审核";
                case "APPROVED" -> "已审核";
                case "CANCELLED" -> "已作废";
                default -> str(row.get("status"));
            });
            mapped.add(row);
        }
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @GetMapping("/outbound/detail")
    public ApiResponse<Map<String, Object>> detail(
            @RequestParam(required = false) String outboundId,
            @RequestParam(required = false) String id) {
        String key = outboundId != null && !outboundId.isBlank() ? outboundId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 outboundId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM sales_outbound WHERE outbound_id = ? OR outbound_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "出库单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM sales_outbound_detail WHERE outbound_id = ? ORDER BY detail_id",
                head.get("outboundId"));
        head.put("details", details.stream().map(SalesOutboundController::camelize).toList());
        return ApiResponse.ok(head);
    }

    /**
     * 按订单预填出库明细（供前端「引入销售订单」使用）。
     * <p>返回：{@code customer / warehouse / details}，每条明细带 orderQty / outboundedQty / remainQty；
     * 前端可按需拆行、指定批次扣减。
     */
    @GetMapping("/outbound/from-order")
    public ApiResponse<Map<String, Object>> fromOrder(@RequestParam String orderNo) {
        List<Map<String, Object>> orderRows = jdbcTemplate.queryForList(
                "SELECT order_id, order_no, customer_code, customer, warehouse, status, amount, outbound_amount " +
                        "FROM sales_order WHERE order_no = ? OR order_id = ?",
                orderNo, orderNo);
        if (orderRows.isEmpty()) throw new IllegalArgumentException("销售订单不存在：" + orderNo);
        Map<String, Object> order = orderRows.get(0);
        String status = str(pick(order, "status"));
        if (!"APPROVED".equals(status)) throw new IllegalArgumentException("仅已审核的销售订单可生成出库单，当前状态：" + status);

        String orderId = str(pick(order, "order_id"));
        List<Map<String, Object>> details = jdbcTemplate.queryForList("""
                SELECT detail_id, goods_code, goods_name, unit_name, qty, price, amount, tax_rate
                FROM sales_order_detail WHERE order_id = ? ORDER BY detail_id
                """, orderId);

        // 已出库数量：合并所有历史 sales_outbound_detail (同 goods_code + source_order)
        Map<String, BigDecimal> outboundedByGoods = new HashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList("""
                SELECT d.goods_code AS gc, COALESCE(SUM(d.qty), 0) AS q
                FROM sales_outbound_detail d
                JOIN sales_outbound h ON d.outbound_id = h.outbound_id
                WHERE h.source_order = ? AND h.status = 'APPROVED'
                GROUP BY d.goods_code
                """, str(pick(order, "order_no")))) {
            outboundedByGoods.put(str(pick(r, "gc")), toBd(pick(r, "q")));
        }

        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> d : details) {
            String goodsCode = str(pick(d, "goods_code"));
            BigDecimal orderQty = toBd(pick(d, "qty"));
            BigDecimal outboundedQty = outboundedByGoods.getOrDefault(goodsCode, BigDecimal.ZERO);
            BigDecimal remainQty = orderQty.subtract(outboundedQty);
            if (remainQty.signum() < 0) remainQty = BigDecimal.ZERO;

            Map<String, Object> line = new HashMap<>();
            line.put("goodsCode", goodsCode);
            line.put("goodsName", str(pick(d, "goods_name")));
            line.put("unitName", str(pick(d, "unit_name")));
            line.put("orderQty", orderQty);
            line.put("outboundedQty", outboundedQty);
            line.put("remainQty", remainQty);
            line.put("price", toBd(pick(d, "price")));       // 只读
            line.put("taxRate", str(pick(d, "tax_rate")));
            lines.add(line);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", str(pick(order, "order_no")));
        result.put("orderId", orderId);
        result.put("customerCode", str(pick(order, "customer_code")));
        result.put("customer", str(pick(order, "customer")));
        result.put("warehouse", str(pick(order, "warehouse")));
        result.put("orderAmount", toBd(pick(order, "amount")));
        result.put("outboundedAmount", toBd(pick(order, "outbound_amount")));
        result.put("details", lines);
        return ApiResponse.ok(result);
    }

    /**
     * 可选批次列表（供前端「拆行指定批次」下拉）。
     * 返回给定 goods_code / warehouse 下所有 qty > 0 的批次。
     */
    @GetMapping("/outbound/available-batches")
    public ApiResponse<List<Map<String, Object>>> availableBatches(
            @RequestParam String goodsCode, @RequestParam String warehouse) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT batch_no, qty, cost_price, production_date, expiry_date
                FROM inv_batch_stock
                WHERE goods_code = ? AND warehouse = ? AND qty > 0
                ORDER BY production_date ASC, batch_no ASC
                """, goodsCode, warehouse);
        return ApiResponse.ok(rows.stream().map(SalesOutboundController::camelize).toList());
    }

    // ============ 创建 / 审核 ============

    /**
     * 创建销售出库单。
     *
     * <p>Payload：
     * <pre>
     * {
     *   "sourceOrder": "SO...",
     *   "customer": "...", "warehouse": "...",
     *   "billDate": "...",
     *   "details": [
     *     { "goodsCode": "...", "goodsName": "...", "unitName": "...",
     *       "qty": 100, "price": 35,
     *       "batchNo": "..."  // 可选：指定批次；不填则出库审核走 FIFO（当前 InventoryCostService 未实现 FIFO，
     *                         //        故实际必须填 batchNo 才能扣到批次层。审核时若为空只扣 stock_balance）
     *     }
     *   ]
     * }
     * </pre>
     */
    @PostMapping("/outbound/create")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        String sourceOrder = str(request.get("sourceOrder"));
        if (sourceOrder.isBlank()) sourceOrder = str(request.get("bizId"));

        Map<String, Object> order = null;
        if (!sourceOrder.isBlank()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT order_id, order_no, customer, customer_code, salesman, warehouse, remark, status " +
                            "FROM sales_order WHERE order_no = ? OR order_id = ?",
                    sourceOrder, sourceOrder);
            if (!rows.isEmpty()) order = rows.get(0);
        }
        if (order != null && !"APPROVED".equals(str(pick(order, "status")))) {
            throw new IllegalArgumentException("销售订单未审核，无法生成出库单");
        }

        String orderNo = order != null ? str(pick(order, "order_no")) : sourceOrder;
        String customer = strOrDefault(request.get("customer"),
                order != null ? str(pick(order, "customer")) : "");
        String warehouse = strOrDefault(request.get("warehouse"),
                order != null ? str(pick(order, "warehouse")) : "总仓");
        LocalDate billDate = parseDate(request.get("billDate"), LocalDate.now());
        // 主表备注：请求携带 > 订单 > 空
        String remark = strOrDefault(request.get("remark"),
                order != null ? str(pick(order, "remark")) : "");
        // salesman / territory / route_line：优先请求 > 订单 salesman > base_customer 反查
        String salesman = strOrDefault(request.get("salesman"),
                order != null ? str(pick(order, "salesman")) : "");
        String territory = str(request.get("territory"));
        String routeLine = str(request.get("routeLine"));
        if (territory.isBlank() || routeLine.isBlank() || salesman.isBlank()) {
            String customerCode = order != null ? str(pick(order, "customer_code")) : "";
            if (!customerCode.isBlank()) {
                List<Map<String, Object>> cust = jdbcTemplate.queryForList(
                        "SELECT salesman AS s, territory AS t, route_line AS r FROM base_customer WHERE customer_code = ? LIMIT 1",
                        customerCode);
                if (!cust.isEmpty()) {
                    if (salesman.isBlank()) salesman = str(pick(cust.get(0), "s"));
                    if (territory.isBlank()) territory = str(pick(cust.get(0), "t"));
                    if (routeLine.isBlank()) routeLine = str(pick(cust.get(0), "r"));
                }
            }
        }

        // driver：优先请求填的实际司机 > 线路上配置的默认司机。发货单/拒收入库单都从这里快照。
        String driver = str(request.get("driver"));
        if (driver.isBlank() && !routeLine.isBlank()) {
            List<String> lineDrivers = jdbcTemplate.queryForList(
                    "SELECT driver FROM base_route_line WHERE route_line_name = ? OR route_line_code = ? LIMIT 1",
                    String.class, routeLine, routeLine);
            if (!lineDrivers.isEmpty()) driver = str(lineDrivers.get(0));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        // 明细为空时按订单预填一商品一行
        if (reqDetails.isEmpty() && order != null) {
            String orderId = str(pick(order, "order_id"));
            for (Map<String, Object> od : jdbcTemplate.queryForList("""
                    SELECT goods_code, goods_name, unit_name, qty, price
                    FROM sales_order_detail WHERE order_id = ? ORDER BY detail_id
                    """, orderId)) {
                Map<String, Object> line = new HashMap<>();
                line.put("goodsCode", str(pick(od, "goods_code")));
                line.put("goodsName", str(pick(od, "goods_name")));
                line.put("unitName", str(pick(od, "unit_name")));
                line.put("qty", toBd(pick(od, "qty")));
                line.put("price", toBd(pick(od, "price")));
                reqDetails.add(line);
            }
        }
        if (reqDetails.isEmpty()) throw new IllegalArgumentException("出库明细不能为空");

        // 校验：Σ本次出库 ≤ 商品订单剩余
        if (order != null) {
            String orderId = str(pick(order, "order_id"));
            Map<String, BigDecimal> orderQtyByGoods = new HashMap<>();
            for (Map<String, Object> od : jdbcTemplate.queryForList(
                    "SELECT goods_code AS gc, qty AS q FROM sales_order_detail WHERE order_id = ?", orderId)) {
                orderQtyByGoods.merge(str(pick(od, "gc")), toBd(pick(od, "q")), BigDecimal::add);
            }
            Map<String, BigDecimal> outboundedByGoods = new HashMap<>();
            for (Map<String, Object> r : jdbcTemplate.queryForList("""
                    SELECT d.goods_code AS gc, COALESCE(SUM(d.qty), 0) AS q
                    FROM sales_outbound_detail d
                    JOIN sales_outbound h ON d.outbound_id = h.outbound_id
                    WHERE h.source_order = ? AND h.status = 'APPROVED'
                    GROUP BY d.goods_code
                    """, orderNo)) {
                outboundedByGoods.put(str(pick(r, "gc")), toBd(pick(r, "q")));
            }
            Map<String, BigDecimal> thisTimeByGoods = new HashMap<>();
            for (Map<String, Object> line : reqDetails) {
                thisTimeByGoods.merge(str(line.get("goodsCode")), toBd(line.get("qty")), BigDecimal::add);
            }
            for (Map.Entry<String, BigDecimal> e : thisTimeByGoods.entrySet()) {
                BigDecimal orderQ = orderQtyByGoods.getOrDefault(e.getKey(), BigDecimal.ZERO);
                BigDecimal outbounded = outboundedByGoods.getOrDefault(e.getKey(), BigDecimal.ZERO);
                BigDecimal remain = orderQ.subtract(outbounded);
                if (e.getValue().compareTo(remain) > 0) {
                    throw new IllegalArgumentException(
                            "商品 " + e.getKey() + " 本次出库 " + e.getValue() + " 超过订单剩余 " + remain);
                }
            }
        }

        // 一次性反查明细里所有商品的 base_goods 扩展字段（spec / barcode / base_unit / unit_config）
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (Map<String, Object> line : reqDetails) codes.add(str(line.get("goodsCode")));
        Map<String, Map<String, Object>> goodsInfo = new HashMap<>();
        if (!codes.isEmpty()) {
            String inClause = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
            List<Map<String, Object>> gRows = jdbcTemplate.queryForList(
                    "SELECT goods_code, spec, barcode, base_unit, unit_config FROM base_goods WHERE goods_code IN (" + inClause + ")",
                    codes.toArray());
            for (Map<String, Object> g : gRows) goodsInfo.put(str(pick(g, "goods_code")), g);
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCostAmount = BigDecimal.ZERO;
        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("qty"));
            BigDecimal p = toBd(line.get("price"));
            BigDecimal a = q.multiply(p).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cp = toBd(line.get("costPrice"));
            if (cp.signum() == 0) {
                cp = inventoryCostService.getCurrentCostPrice(str(line.get("goodsCode")), warehouse);
                if (cp == null) cp = BigDecimal.ZERO;
            }
            BigDecimal ca = q.multiply(cp).setScale(2, RoundingMode.HALF_UP);
            line.put("_amount", a);
            line.put("_costPrice", cp);
            line.put("_costAmount", ca);
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(a);
            totalCostAmount = totalCostAmount.add(ca);
        }

        String id = "SOU" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.SALES_OUTBOUND, "sales_outbound", "outbound_no");

        jdbcTemplate.update("""
                INSERT INTO sales_outbound (outbound_id, outbound_no, source_order, customer, warehouse,
                    bill_date, qty, amount, cost_amount, status, stock_updated, receipt_generated,
                    salesman, territory, route_line, driver, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', FALSE, FALSE, ?, ?, ?, ?, ?)
                """, id, no, orderNo, customer, warehouse, billDate, totalQty, totalAmount, totalCostAmount,
                salesman, territory, routeLine, emptyToNull(driver), remark);

        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("qty"));
            BigDecimal p = toBd(line.get("price"));
            BigDecimal a = (BigDecimal) line.get("_amount");
            BigDecimal cp = (BigDecimal) line.get("_costPrice");
            BigDecimal ca = (BigDecimal) line.get("_costAmount");
            String batchNo = str(line.get("batchNo"));
            LocalDate productionDate = parseDate(line.get("productionDate"), null);
            String detailRemark = str(line.get("remark"));

            String code = str(line.get("goodsCode"));
            Map<String, Object> g = goodsInfo.get(code);
            String spec = g != null ? str(pick(g, "spec")) : str(line.get("spec"));
            String barcode = g != null ? str(pick(g, "barcode")) : str(line.get("barcode"));
            String smallUnitName = g != null ? str(pick(g, "base_unit")) : str(line.get("smallUnitName"));
            // 小单位数量：由 unit_config 里查大单位→小单位换算率；找不到就跟 qty 相同
            BigDecimal smallUnitQty = q;
            if (g != null) {
                Object uc = pick(g, "unit_config");
                if (uc != null) {
                    try {
                        BigDecimal convert = extractSmallUnitConvertQty(String.valueOf(uc), str(line.get("unitName")));
                        if (convert != null && convert.signum() > 0) smallUnitQty = q.multiply(convert);
                    } catch (Exception ignore) { /* JSON 解析失败按 qty 处理 */ }
                }
            }

            String detailId = "SOUD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO sales_outbound_detail (detail_id, outbound_id, goods_code, goods_name,
                        warehouse, unit_name, qty, batch_no, price, amount, cost_price, cost_amount,
                        spec, barcode, production_date, small_unit_name, small_unit_qty, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    detailId, id, code, str(line.get("goodsName")),
                    warehouse, str(line.get("unitName")), q, batchNo, p, a, cp, ca,
                    spec, barcode, productionDate, smallUnitName, smallUnitQty, detailRemark);
        }

        log("sales.outbound", "CREATE", no, "创建销售出库单（来源订单：" + orderNo + "）");
        return ApiResponse.ok(GenericResult.row(
                "outboundId", id, "outboundNo", no,
                "sourceOrder", orderNo, "status", "PENDING"));
    }

    /**
     * PENDING 出库单编辑 —— 允许改主表 remark / 明细 qty / batch_no / production_date / remark。
     * 明细整表替换（跟采购入库编辑一致的策略）。
     */
    @PostMapping("/outbound/update")
    @Transactional
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> request) {
        String key = str(request.get("outboundId"));
        if (key.isBlank()) key = str(request.get("bizId"));
        if (key.isBlank()) return ApiResponse.fail("400", "缺少 outboundId");

        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT outbound_id, outbound_no, source_order, status FROM sales_outbound WHERE outbound_id = ? OR outbound_no = ?",
                key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "出库单不存在");
        String status = str(pick(heads.get(0), "status"));
        if (!"PENDING".equals(status)) return ApiResponse.fail("400", "仅待审核出库单可编辑，当前状态：" + status);
        String outboundId = str(pick(heads.get(0), "outbound_id"));
        String outboundNo = str(pick(heads.get(0), "outbound_no"));
        String sourceOrderNo = str(pick(heads.get(0), "source_order"));

        // 编辑：先删旧明细，再复用 create 的明细写入逻辑（简化：调用一次内部辅助方法）
        // 校验：Σ本次出库 ≤ 商品订单剩余（排除当前出库单）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        if (reqDetails.isEmpty()) return ApiResponse.fail("400", "出库明细不能为空");

        if (!sourceOrderNo.isBlank()) {
            List<Map<String, Object>> orderRows = jdbcTemplate.queryForList(
                    "SELECT order_id FROM sales_order WHERE order_no = ?", sourceOrderNo);
            if (!orderRows.isEmpty()) {
                String orderId = str(pick(orderRows.get(0), "order_id"));
                Map<String, BigDecimal> orderQtyByGoods = new HashMap<>();
                for (Map<String, Object> od : jdbcTemplate.queryForList(
                        "SELECT goods_code AS gc, qty AS q FROM sales_order_detail WHERE order_id = ?", orderId)) {
                    orderQtyByGoods.merge(str(pick(od, "gc")), toBd(pick(od, "q")), BigDecimal::add);
                }
                Map<String, BigDecimal> outboundedByGoods = new HashMap<>();
                for (Map<String, Object> r : jdbcTemplate.queryForList("""
                        SELECT d.goods_code AS gc, COALESCE(SUM(d.qty), 0) AS q
                        FROM sales_outbound_detail d
                        JOIN sales_outbound h ON d.outbound_id = h.outbound_id
                        WHERE h.source_order = ? AND h.status = 'APPROVED' AND h.outbound_id <> ?
                        GROUP BY d.goods_code
                        """, sourceOrderNo, outboundId)) {
                    outboundedByGoods.put(str(pick(r, "gc")), toBd(pick(r, "q")));
                }
                Map<String, BigDecimal> thisTimeByGoods = new HashMap<>();
                for (Map<String, Object> line : reqDetails) {
                    thisTimeByGoods.merge(str(line.get("goodsCode")), toBd(line.get("qty")), BigDecimal::add);
                }
                for (Map.Entry<String, BigDecimal> e : thisTimeByGoods.entrySet()) {
                    BigDecimal orderQ = orderQtyByGoods.getOrDefault(e.getKey(), BigDecimal.ZERO);
                    BigDecimal outbounded = outboundedByGoods.getOrDefault(e.getKey(), BigDecimal.ZERO);
                    BigDecimal remain = orderQ.subtract(outbounded);
                    if (e.getValue().compareTo(remain) > 0) {
                        throw new IllegalArgumentException(
                                "商品 " + e.getKey() + " 本次出库 " + e.getValue() + " 超过订单剩余 " + remain);
                    }
                }
            }
        }

        // 反查 base_goods 补 spec / barcode / smallUnit / unit_config
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (Map<String, Object> line : reqDetails) codes.add(str(line.get("goodsCode")));
        Map<String, Map<String, Object>> goodsInfo = new HashMap<>();
        if (!codes.isEmpty()) {
            String inClause = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
            List<Map<String, Object>> gRows = jdbcTemplate.queryForList(
                    "SELECT goods_code, spec, barcode, base_unit, unit_config FROM base_goods WHERE goods_code IN (" + inClause + ")",
                    codes.toArray());
            for (Map<String, Object> g : gRows) goodsInfo.put(str(pick(g, "goods_code")), g);
        }

        // 从当前主表拿 warehouse（明细写入需要）
        List<Map<String, Object>> full = jdbcTemplate.queryForList(
                "SELECT warehouse FROM sales_outbound WHERE outbound_id = ?", outboundId);
        String warehouse = full.isEmpty() ? "总仓" : str(pick(full.get(0), "warehouse"));

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCostAmount = BigDecimal.ZERO;
        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("qty"));
            BigDecimal p = toBd(line.get("price"));
            BigDecimal a = q.multiply(p).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cp = toBd(line.get("costPrice"));
            if (cp.signum() == 0) {
                cp = inventoryCostService.getCurrentCostPrice(str(line.get("goodsCode")), warehouse);
                if (cp == null) cp = BigDecimal.ZERO;
            }
            BigDecimal ca = q.multiply(cp).setScale(2, RoundingMode.HALF_UP);
            line.put("_amount", a);
            line.put("_costPrice", cp);
            line.put("_costAmount", ca);
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(a);
            totalCostAmount = totalCostAmount.add(ca);
        }

        // 主表更新（driver 未传时保持原值，避免编辑时把已指派的司机清掉）
        jdbcTemplate.update("""
                UPDATE sales_outbound SET qty = ?, amount = ?, cost_amount = ?, remark = ?,
                    driver = COALESCE(?, driver)
                WHERE outbound_id = ?
                """, totalQty, totalAmount, totalCostAmount, str(request.get("remark")),
                emptyToNull(str(request.get("driver"))), outboundId);

        // 删旧明细
        jdbcTemplate.update("DELETE FROM sales_outbound_detail WHERE outbound_id = ?", outboundId);

        // 写新明细
        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("qty"));
            BigDecimal p = toBd(line.get("price"));
            BigDecimal a = (BigDecimal) line.get("_amount");
            BigDecimal cp = (BigDecimal) line.get("_costPrice");
            BigDecimal ca = (BigDecimal) line.get("_costAmount");
            String batchNo = str(line.get("batchNo"));
            LocalDate productionDate = parseDate(line.get("productionDate"), null);
            String detailRemark = str(line.get("remark"));

            String code = str(line.get("goodsCode"));
            Map<String, Object> g = goodsInfo.get(code);
            String spec = g != null ? str(pick(g, "spec")) : str(line.get("spec"));
            String barcode = g != null ? str(pick(g, "barcode")) : str(line.get("barcode"));
            String smallUnitName = g != null ? str(pick(g, "base_unit")) : str(line.get("smallUnitName"));
            BigDecimal smallUnitQty = q;
            if (g != null) {
                Object uc = pick(g, "unit_config");
                if (uc != null) {
                    try {
                        BigDecimal convert = extractSmallUnitConvertQty(String.valueOf(uc), str(line.get("unitName")));
                        if (convert != null && convert.signum() > 0) smallUnitQty = q.multiply(convert);
                    } catch (Exception ignore) { /* ignore */ }
                }
            }

            String detailId = "SOUD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO sales_outbound_detail (detail_id, outbound_id, goods_code, goods_name,
                        warehouse, unit_name, qty, batch_no, price, amount, cost_price, cost_amount,
                        spec, barcode, production_date, small_unit_name, small_unit_qty, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    detailId, outboundId, code, str(line.get("goodsName")),
                    warehouse, str(line.get("unitName")), q, batchNo, p, a, cp, ca,
                    spec, barcode, productionDate, smallUnitName, smallUnitQty, detailRemark);
        }

        log("sales.outbound", "UPDATE", outboundNo, "销售出库单编辑");
        return ApiResponse.ok(Map.of("outboundId", outboundId, "success", true));
    }

    /**
     * 从 unit_config JSON 中查找指定单位对应的换算率（→ 小单位数量）。
     * unit_config 形如：[{"unitType":"小单位","unitName":"瓶","convertQty":1}, {"unitType":"大单位","unitName":"箱","convertQty":24}]
     * 传入 unitName（如"箱"），返回其 convertQty（如 24）。若是小单位或找不到返回 1。
     */
    private static BigDecimal extractSmallUnitConvertQty(String unitConfigJson, String unitName) {
        if (unitConfigJson == null || unitConfigJson.isBlank() || unitName == null || unitName.isBlank())
            return BigDecimal.ONE;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arr = om.readTree(unitConfigJson);
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                    if (unitName.equals(node.path("unitName").asText())) {
                        return new BigDecimal(node.path("convertQty").asText("1"));
                    }
                }
            }
        } catch (Exception ignore) { /* fall through */ }
        return BigDecimal.ONE;
    }

    @PostMapping("/outbound/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> audit(@Valid @RequestBody AuditRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT outbound_id, outbound_no, source_order, customer, warehouse, amount, status
                FROM sales_outbound WHERE (outbound_id = ? OR outbound_no = ?) AND status = 'PENDING'
                """, request.bizId(), request.bizId());
        if (rows.isEmpty()) throw new IllegalArgumentException("出库单不存在或已审核");
        Map<String, Object> outbound = rows.get(0);
        String outboundId = str(pick(outbound, "outbound_id"));
        String outboundNo = str(pick(outbound, "outbound_no"));
        String sourceOrderNo = str(pick(outbound, "source_order"));

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM sales_outbound_detail WHERE outbound_id = ?", outboundId);

        // 扣减库存：按批次或按 goods_code 合计
        for (Map<String, Object> d : details) {
            inventoryCostService.salesOutbound(
                    str(pick(d, "goods_code")),
                    str(pick(d, "goods_name")),
                    str(pick(d, "warehouse")),
                    str(pick(d, "batch_no")),  // 可空 → 只扣 stock_balance 不扣批次层
                    toBd(pick(d, "qty")),
                    outboundNo
            );
        }

        jdbcTemplate.update(
                "UPDATE sales_outbound SET status = 'APPROVED', stock_updated = TRUE WHERE outbound_id = ?",
                outboundId);

        // 回写销售订单 outbound_amount / outbound_status
        if (!sourceOrderNo.isBlank()) {
            List<Map<String, Object>> orderRows = jdbcTemplate.queryForList(
                    "SELECT order_id, amount FROM sales_order WHERE order_no = ?", sourceOrderNo);
            if (!orderRows.isEmpty()) {
                BigDecimal orderAmount = toBd(pick(orderRows.get(0), "amount"));
                BigDecimal cumulativeAmount = toBd(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0)
                        FROM sales_outbound
                        WHERE source_order = ? AND status = 'APPROVED'
                        """, BigDecimal.class, sourceOrderNo));
                String outboundStatus;
                if (cumulativeAmount.signum() <= 0) outboundStatus = "未出库";
                else if (orderAmount.signum() > 0 && cumulativeAmount.compareTo(orderAmount) >= 0) outboundStatus = "已出库";
                else outboundStatus = "部分出库";
                jdbcTemplate.update("""
                        UPDATE sales_order
                        SET outbound_amount = ?, outbound_status = ?
                        WHERE order_no = ?
                        """, cumulativeAmount, outboundStatus, sourceOrderNo);
            }
        }

        log("sales.outbound", "AUDIT", outboundNo, "销售出库审核");

        // 自动生成销售发货单（幂等）
        String receiptNo = receiptController.generateFromOutbound(outboundId);
        jdbcTemplate.update(
                "UPDATE sales_outbound SET receipt_generated = TRUE WHERE outbound_id = ?",
                outboundId);

        return ApiResponse.ok(Map.of(
                "outboundId", outboundId,
                "status", "APPROVED",
                "receiptNo", receiptNo,
                "effect", "已扣减库存并生成销售发货单 " + receiptNo));
    }

    // ============ 工具方法 ============

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                moduleCode, action, bizNo, detail);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String strOrDefault(Object o, String dft) {
        if (o == null) return dft;
        String s = String.valueOf(o);
        return s.isBlank() ? dft : s;
    }
    private static String emptyToNull(String s) { return (s == null || s.isBlank()) ? null : s; }
    private static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(String.valueOf(o)); } catch (Exception e) { return BigDecimal.ZERO; }
    }
    private static LocalDate parseDate(Object o, LocalDate dft) {
        if (o == null) return dft;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date sd) return sd.toLocalDate();
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return dft;
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return dft; }
    }
    private static Object pick(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        return row.get(key.toUpperCase(Locale.ROOT));
    }
    private static Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : k.toCharArray()) {
                if (c == '_') { upper = true; continue; }
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            out.put(sb.toString(), e.getValue());
        }
        return out;
    }

    public record AuditRequest(@NotBlank String bizId, String remark) {}
}
