package com.erp.sales;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 销售订单与采购订单：V1.0 落地骨架（保存 + 分页查询 + 明细查询）。
 * <p>单据编号：{@code SO/PO + yyyyMMdd + 4 位当日流水}
 */
@RestController
public class OrderController {

    private final JdbcTemplate jdbcTemplate;
    private final com.erp.common.util.BillNoGenerator billNoGen;

    public OrderController(JdbcTemplate jdbcTemplate, com.erp.common.util.BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
    }

    // ============ 销售订单 ============

    @PostMapping("/sales/order/create")
    @Transactional
    public ApiResponse<Map<String, Object>> createSales(@RequestBody Map<String, Object> req) {
        String orderId = "SO" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String orderNo = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.SALES_ORDER, "sales_order", "order_no");
        String customerCode = str(req.get("customerCode"));
        String customerName = str(req.get("customerName"));
        String salesman = str(req.get("salesman"));
        String warehouse = str(req.get("warehouseId"));
        String priceGroup = str(req.get("priceGroupCode"));
        LocalDate billDate = parseDate(req.get("billDate"));
        LocalDate expectedDelivery = parseDate(req.get("expectedDeliveryDate"));
        String remark = str(req.get("remark"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = req.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            totalAmount = totalAmount.add(toBd(d.get("amount")));
        }
        jdbcTemplate.update("""
                INSERT INTO sales_order (order_id, order_no, customer, customer_code, salesman, warehouse,
                    bill_date, expected_delivery_date, price_group_code, amount, unpaid_amount, status, creator_name, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """,
                orderId, orderNo, customerName, customerCode, salesman, warehouse,
                billDate, expectedDelivery, priceGroup, totalAmount, totalAmount, "系统管理员", remark);
        for (Map<String, Object> d : details) {
            String detailId = "SOD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO sales_order_detail (detail_id, order_id, goods_code, goods_name, unit_name,
                        unit_level, convert_qty, qty, base_qty, price, amount, tax_rate, line_type, sales_attribute, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    detailId, orderId,
                    str(d.get("goodsCode")), str(d.get("goodsName")), str(d.get("unitId")),
                    toInt(d.get("unitLevel"), 1), toBd(d.get("convertQty")),
                    toBd(d.get("qty")), toBd(d.get("baseQty")),
                    toBd(d.get("price")), toBd(d.get("amount")),
                    str(d.get("taxRate")),
                    str(d.get("salesAttribute"), "正常"),
                    str(d.get("salesAttribute"), "正常"),
                    str(d.get("remark")));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("orderId", orderId);
        out.put("orderNo", orderNo);
        out.put("amount", totalAmount);
        out.put("success", true);
        return ApiResponse.ok(out);
    }

    @PostMapping("/sales/order/page")
    public ApiResponse<PageResult<Map<String, Object>>> salesPage(@RequestBody PageRequest request) {
        // 走 snake_case AS snake_case，让 camelize 正确转成驼峰
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT order_id, order_no, customer, customer_code,
                       salesman, warehouse, bill_date, expected_delivery_date,
                       price_group_code, amount, paid_amount, unpaid_amount,
                       outbound_status, outbound_amount,
                       status, creator_name, create_time, audit_time, audit_user, remark
                FROM sales_order ORDER BY create_time DESC, order_no DESC
                """);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            String st = String.valueOf(row.getOrDefault("status", ""));
            row.put("statusText", switch (st) {
                case "PENDING" -> "待审核";
                case "AUDITED", "APPROVED" -> "已审核";
                case "CLOSED" -> "已关闭";
                case "CANCELLED" -> "已作废";
                default -> st;
            });
            // 前端 EXACT_TITLE_MAP 期望的兼容字段
            row.put("customerName", row.get("customer"));   // sales_order.customer 存的是名称
            row.put("createdAt", row.get("createTime"));
            Object creator = row.getOrDefault("creatorName", "");
            Object createdAt = row.getOrDefault("createTime", "");
            row.put("creatorInfo", (creator == null ? "" : creator) + " " + (createdAt == null ? "" : createdAt));
            mapped.add(row);
        }
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @GetMapping("/sales/order/detail")
    public ApiResponse<Map<String, Object>> salesDetail(
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String id) {
        String key = orderId != null && !orderId.isBlank() ? orderId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 orderId / id");
        // 同时匹配 order_id / order_no，让前端可以传单号
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT * FROM sales_order WHERE order_id = ? OR order_no = ?
                """, key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        String realOrderId = String.valueOf(head.get("orderId"));
        List<Map<String, Object>> details = jdbcTemplate.queryForList("""
                SELECT * FROM sales_order_detail WHERE order_id = ? ORDER BY detail_id
                """, realOrderId);
        head.put("details", details.stream().map(OrderController::camelize).toList());
        return ApiResponse.ok(head);
    }

    /** 销售订单编辑（仅 PENDING 允许），全量替换明细。 */
    @PostMapping("/sales/order/update")
    @Transactional
    public ApiResponse<Map<String, Object>> updateSales(@RequestBody Map<String, Object> req) {
        String orderId = str(req.get("orderId"));
        if (orderId.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status FROM sales_order WHERE order_id = ?", orderId);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"PENDING".equals(status)) return ApiResponse.fail("400", "仅待审核销售订单允许编辑");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = req.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> d : details) totalAmount = totalAmount.add(toBd(d.get("amount")));

        jdbcTemplate.update("""
                UPDATE sales_order SET customer = ?, customer_code = ?, salesman = ?, warehouse = ?,
                    bill_date = ?, expected_delivery_date = ?, price_group_code = ?,
                    amount = ?, unpaid_amount = ?, remark = ?
                WHERE order_id = ?
                """,
                str(req.get("customerName")), str(req.get("customerCode")),
                str(req.get("salesman")), str(req.get("warehouseId")),
                parseDate(req.get("billDate")), parseDate(req.get("expectedDeliveryDate")),
                str(req.get("priceGroupCode")), totalAmount, totalAmount, str(req.get("remark")),
                orderId);

        jdbcTemplate.update("DELETE FROM sales_order_detail WHERE order_id = ?", orderId);
        for (Map<String, Object> d : details) {
            String detailId = "SOD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO sales_order_detail (detail_id, order_id, goods_code, goods_name, unit_name,
                        unit_level, convert_qty, qty, base_qty, price, amount, tax_rate, line_type, sales_attribute, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    detailId, orderId,
                    str(d.get("goodsCode")), str(d.get("goodsName")), str(d.get("unitId")),
                    toInt(d.get("unitLevel"), 1), toBd(d.get("convertQty")),
                    toBd(d.get("qty")), toBd(d.get("baseQty")),
                    toBd(d.get("price")), toBd(d.get("amount")),
                    str(d.get("taxRate")),
                    str(d.get("salesAttribute"), "正常"),
                    str(d.get("salesAttribute"), "正常"),
                    str(d.get("remark")));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("orderId", orderId);
        out.put("amount", totalAmount);
        out.put("success", true);
        return ApiResponse.ok(out);
    }

    /** 销售订单审核：PENDING → APPROVED，写审核时间，outbound_status='待出库'。 */
    @PostMapping("/sales/order/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditSales(@RequestBody Map<String, Object> req) {
        String orderId = str(req.get("orderId"));
        if (orderId.isBlank()) orderId = str(req.get("bizId"));
        if (orderId.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status FROM sales_order WHERE order_id = ? OR order_no = ?", orderId, orderId);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"PENDING".equals(status)) return ApiResponse.fail("400", "只有待审核订单可审核，当前状态：" + status);
        jdbcTemplate.update("""
                UPDATE sales_order
                SET status = 'APPROVED', audit_time = CURRENT_TIMESTAMP, audit_user = ?,
                    outbound_status = '待出库'
                WHERE order_id = ? OR order_no = ?
                """, "系统管理员", orderId, orderId);
        return ApiResponse.ok(Map.of("orderId", orderId, "status", "APPROVED", "effect", "已锁定库存", "success", true));
    }

    /** 销售订单反审核：APPROVED → PENDING；已生成出库单则拒绝。 */
    @PostMapping("/sales/order/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAuditSales(@RequestBody Map<String, Object> req) {
        String orderId = str(req.get("orderId"));
        if (orderId.isBlank()) orderId = str(req.get("bizId"));
        if (orderId.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_no, status FROM sales_order WHERE order_id = ? OR order_no = ?", orderId, orderId);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"APPROVED".equals(status)) return ApiResponse.fail("400", "只有已审核订单可反审核");
        String orderNo = str(pickCS(rows.get(0), "order_no"));
        Integer outboundCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sales_outbound WHERE source_order = ?", Integer.class, orderNo);
        if (outboundCnt != null && outboundCnt > 0) {
            return ApiResponse.fail("400", "已生成出库单，无法反审核");
        }
        jdbcTemplate.update("""
                UPDATE sales_order
                SET status = 'PENDING', audit_time = NULL, audit_user = NULL, outbound_status = NULL
                WHERE order_id = ? OR order_no = ?
                """, orderId, orderId);
        return ApiResponse.ok(Map.of("orderId", orderId, "status", "PENDING", "effect", "已反审核并释放锁定库存", "success", true));
    }

    /** 销售订单关闭：APPROVED/PENDING → CLOSED。 */
    @PostMapping("/sales/order/close")
    @Transactional
    public ApiResponse<Map<String, Object>> closeSales(@RequestBody Map<String, Object> req) {
        String orderId = str(req.get("orderId"));
        if (orderId.isBlank()) orderId = str(req.get("bizId"));
        if (orderId.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        int updated = jdbcTemplate.update(
                "UPDATE sales_order SET status = 'CLOSED' WHERE (order_id = ? OR order_no = ?) AND status IN ('PENDING','APPROVED')",
                orderId, orderId);
        if (updated == 0) return ApiResponse.fail("400", "订单状态不允许关闭");
        return ApiResponse.ok(Map.of("orderId", orderId, "status", "CLOSED", "success", true));
    }

    /** 销售订单删除：仅 PENDING 可删。 */
    @PostMapping("/sales/order/delete")
    @Transactional
    public ApiResponse<Map<String, Object>> deleteSales(@RequestBody Map<String, Object> req) {
        String orderId = str(req.get("orderId"));
        if (orderId.isBlank()) orderId = str(req.get("bizId"));
        if (orderId.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status FROM sales_order WHERE order_id = ?", orderId);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"PENDING".equals(status)) return ApiResponse.fail("400", "只有待审核订单可删除");
        jdbcTemplate.update("DELETE FROM sales_order_detail WHERE order_id = ?", orderId);
        jdbcTemplate.update("DELETE FROM sales_order WHERE order_id = ?", orderId);
        return ApiResponse.ok(Map.of("orderId", orderId, "success", true));
    }

    /**
     * 销售快速开单 = 创建订单 + 立即审核。
     * 走 JdbcTemplate 复用 createSales / auditSales 逻辑，避免依赖已删除的老 SalesController helper。
     */
    @PostMapping("/sales/quick-order/create-and-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> quickOrderCreateAndAudit(@RequestBody Map<String, Object> req) {
        var createResult = createSales(req);
        Map<String, Object> data = createResult.data();
        String orderId = String.valueOf(data.get("orderId"));
        // 内联审核，避免 Spring 代理自调用绕过事务
        jdbcTemplate.update("""
                UPDATE sales_order
                SET status = 'APPROVED', audit_time = CURRENT_TIMESTAMP, audit_user = ?,
                    outbound_status = '待出库'
                WHERE order_id = ?
                """, "系统管理员（快速开单）", orderId);
        Map<String, Object> out = new HashMap<>();
        out.put("orderNo", data.get("orderNo"));
        out.put("orderId", orderId);
        out.put("status", "APPROVED");
        out.put("amount", data.get("amount"));
        out.put("effect", "快速开单已审核并锁库存");
        return ApiResponse.ok(out);
    }

    // ============ 采购订单 ============

    @PostMapping("/purchase/order/create")
    @Transactional
    public ApiResponse<Map<String, Object>> createPurchase(@RequestBody Map<String, Object> req) {
        String orderId = "PO" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String orderNo = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.PURCHASE_ORDER, "purchase_order", "order_no");
        String supplierCode = str(req.get("supplierCode"));
        String supplierName = str(req.get("supplierName"));
        String buyer = str(req.get("buyer"));
        String warehouse = str(req.get("warehouseId"));
        LocalDate billDate = parseDate(req.get("billDate"));
        String remark = str(req.get("remark"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = req.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            totalAmount = totalAmount.add(toBd(d.get("amount")));
        }
        jdbcTemplate.update("""
                INSERT INTO purchase_order (order_id, order_no, supplier_code, supplier_name, buyer, warehouse,
                    bill_date, amount, unpaid_amount, status, creator_name, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """,
                orderId, orderNo, supplierCode, supplierName, buyer, warehouse,
                billDate, totalAmount, totalAmount, "系统管理员", remark);
        for (Map<String, Object> d : details) {
            String detailId = "POD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO purchase_order_detail (detail_id, order_id, goods_code, goods_name, spec, unit_name,
                        unit_level, convert_qty, qty, base_qty, price, amount, tax_rate, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    detailId, orderId,
                    str(d.get("goodsCode")), str(d.get("goodsName")), str(d.get("spec")),
                    str(d.get("unitId")), toInt(d.get("unitLevel"), 1), toBd(d.get("convertQty")),
                    toBd(d.get("qty")), toBd(d.get("baseQty")),
                    toBd(d.get("price")), toBd(d.get("amount")),
                    str(d.get("taxRate")), str(d.get("remark")));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("orderId", orderId);
        out.put("orderNo", orderNo);
        out.put("amount", totalAmount);
        out.put("success", true);
        return ApiResponse.ok(out);
    }

    @PostMapping("/purchase/order/page")
    public ApiResponse<PageResult<Map<String, Object>>> purchasePage(@RequestBody PageRequest request) {
        // 走 snake_case AS snake_case，让 camelize 正确转成驼峰
        // （H2 会把不带引号的 alias 拉成大写，破坏驼峰 → 用统一 snake_case 兜底）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT order_id, order_no, supplier_name, supplier_code,
                       buyer, warehouse, bill_date, amount, paid_amount, unpaid_amount, inbound_amount,
                       inbound_status, payment_status,
                       status, creator_name, create_time, audit_time, audit_user, remark
                FROM purchase_order ORDER BY create_time DESC, order_no DESC
                """);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            String st = String.valueOf(row.getOrDefault("status", ""));
            row.put("statusText", switch (st) {
                case "PENDING" -> "待审核";
                case "AUDITED", "APPROVED" -> "已审核";
                case "CLOSED" -> "已关闭";
                case "CANCELLED" -> "已作废";
                default -> st;
            });
            // 前端 EXACT_TITLE_MAP 期望的兼容字段
            row.put("createdAt", row.get("createTime"));
            // 「创建人/时间」列合并展示
            Object creator = row.getOrDefault("creatorName", "");
            Object createdAt = row.getOrDefault("createTime", "");
            row.put("creatorInfo", (creator == null ? "" : creator) + " " + (createdAt == null ? "" : createdAt));
            // 「到货状态」按 inbound_status 反显
            row.put("arrivalStatus", row.getOrDefault("inboundStatus", ""));
            mapped.add(row);
        }
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @GetMapping("/purchase/order/detail")
    public ApiResponse<Map<String, Object>> purchaseDetail(
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String id) {
        String key = orderId != null && !orderId.isBlank() ? orderId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 orderId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT * FROM purchase_order WHERE order_id = ? OR order_no = ?
                """, key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        String realOrderId = String.valueOf(head.get("orderId"));
        List<Map<String, Object>> details = jdbcTemplate.queryForList("""
                SELECT * FROM purchase_order_detail WHERE order_id = ? ORDER BY detail_id
                """, realOrderId);
        head.put("details", details.stream().map(OrderController::camelize).toList());
        return ApiResponse.ok(head);
    }

    /** 采购订单审核：PENDING → APPROVED，写审核时间，inbound_status=待入库 */
    @PostMapping("/purchase/order/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditPurchase(@RequestBody Map<String, Object> req) {
        String key = str(req.get("orderId"));
        if (key.isBlank()) key = str(req.get("bizId"));
        if (key.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        // 同时匹配 order_id / order_no —— 前端列表首列传的是 order_no
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_id, order_no, status FROM purchase_order WHERE order_id = ? OR order_no = ?", key, key);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"PENDING".equals(status)) return ApiResponse.fail("400", "只有待审核订单可审核，当前状态：" + status);
        String realOrderId = str(pickCS(rows.get(0), "order_id"));
        jdbcTemplate.update("""
                UPDATE purchase_order
                SET status = 'APPROVED', audit_time = CURRENT_TIMESTAMP, audit_user = ?,
                    inbound_status = '待入库'
                WHERE order_id = ?
                """, "系统管理员", realOrderId);
        Map<String, Object> out = new HashMap<>();
        out.put("orderId", realOrderId);
        out.put("orderNo", str(pickCS(rows.get(0), "order_no")));
        out.put("status", "APPROVED");
        out.put("success", true);
        return ApiResponse.ok(out);
    }

    /** 采购订单反审核：APPROVED → PENDING；已生成入库单则拒绝 */
    @PostMapping("/purchase/order/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAuditPurchase(@RequestBody Map<String, Object> req) {
        String key = str(req.get("orderId"));
        if (key.isBlank()) key = str(req.get("bizId"));
        if (key.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_id, order_no, status FROM purchase_order WHERE order_id = ? OR order_no = ?", key, key);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"APPROVED".equals(status)) return ApiResponse.fail("400", "只有已审核订单可反审核");
        String orderNo = str(pickCS(rows.get(0), "order_no"));
        String realOrderId = str(pickCS(rows.get(0), "order_id"));
        // 检查是否已生成入库单
        Integer inboundCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pur_inbound WHERE source_order = ?", Integer.class, orderNo);
        if (inboundCnt != null && inboundCnt > 0) {
            return ApiResponse.fail("400", "已生成入库单，无法反审核");
        }
        jdbcTemplate.update("""
                UPDATE purchase_order
                SET status = 'PENDING', audit_time = NULL, audit_user = NULL, inbound_status = '未入库'
                WHERE order_id = ?
                """, realOrderId);
        Map<String, Object> out = new HashMap<>();
        out.put("orderId", realOrderId);
        out.put("orderNo", orderNo);
        out.put("status", "PENDING");
        out.put("success", true);
        return ApiResponse.ok(out);
    }

    /** 采购订单终止：APPROVED/PENDING → CLOSED（不再允许生成入库单）。已入库的部分保留。 */
    @PostMapping("/purchase/order/close")
    @Transactional
    public ApiResponse<Map<String, Object>> closePurchase(@RequestBody Map<String, Object> req) {
        String key = str(req.get("orderId"));
        if (key.isBlank()) key = str(req.get("bizId"));
        if (key.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        int updated = jdbcTemplate.update("""
                UPDATE purchase_order
                SET status = 'CLOSED', inbound_status = '已终止'
                WHERE (order_id = ? OR order_no = ?) AND status IN ('PENDING','APPROVED')
                """, key, key);
        if (updated == 0) return ApiResponse.fail("400", "订单状态不允许终止");
        return ApiResponse.ok(Map.of("orderId", key, "status", "CLOSED", "effect", "订单已终止，不再允许生成入库单", "success", true));
    }

    /** 采购订单删除：仅 PENDING 可删；同时删明细 */
    @PostMapping("/purchase/order/delete")
    @Transactional
    public ApiResponse<Map<String, Object>> deletePurchase(@RequestBody Map<String, Object> req) {
        String key = str(req.get("orderId"));
        if (key.isBlank()) key = str(req.get("bizId"));
        if (key.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_id, status FROM purchase_order WHERE order_id = ? OR order_no = ?", key, key);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"PENDING".equals(status)) return ApiResponse.fail("400", "只有待审核订单可删除");
        String realOrderId = str(pickCS(rows.get(0), "order_id"));
        jdbcTemplate.update("DELETE FROM purchase_order_detail WHERE order_id = ?", realOrderId);
        jdbcTemplate.update("DELETE FROM purchase_order WHERE order_id = ?", realOrderId);
        return ApiResponse.ok(Map.of("orderId", realOrderId, "success", true));
    }

    // ============ 工具 ============

    /** 生成单据号已迁移到 {@link com.erp.common.util.BillNoGenerator}。 */

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String str(Object o, String dft) {
        if (o == null) return dft;
        String s = String.valueOf(o);
        return s.isBlank() ? dft : s;
    }

    /** H2 返回的字段名可能是大写也可能是小写，兼容取值。 */
    private static Object pickCS(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        return row.get(key.toUpperCase(Locale.ROOT));
    }
    private static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(String.valueOf(o)); } catch (Exception e) { return BigDecimal.ZERO; }
    }
    private static int toInt(Object o, int dft) {
        if (o == null) return dft;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return dft; }
    }
    private static LocalDate parseDate(Object o) {
        if (o == null) return LocalDate.now();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return LocalDate.now(); }
    }

    /** 下划线 key → 驼峰；H2 大写也一并处理。 */
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
}
