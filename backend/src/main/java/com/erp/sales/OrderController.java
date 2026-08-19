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
    private final com.erp.inventory.service.InventoryCostService inventoryCostService;

    public OrderController(JdbcTemplate jdbcTemplate,
                           com.erp.common.util.BillNoGenerator billNoGen,
                           com.erp.inventory.service.InventoryCostService inventoryCostService) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
        this.inventoryCostService = inventoryCostService;
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
        // 库存校验：在任何 INSERT 之前做，不足则直接返回，事务内尚无写入
        String shortage = checkStockOfPayload(warehouse, details);
        if (shortage != null) return ApiResponse.fail("400", shortage);
        jdbcTemplate.update("""
                INSERT INTO sales_order (order_id, order_no, customer, customer_code, salesman, warehouse,
                    bill_date, expected_delivery_date, price_group_code, amount, unpaid_amount, status, creator_name, remark, stock_check)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                """,
                orderId, orderNo, customerName, customerCode, salesman, warehouse,
                billDate, expectedDelivery, priceGroup, totalAmount, totalAmount, "系统管理员", remark,
                warehouse.isBlank() ? null : "通过");
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
        // 生成即占用：逐商品把订单数量锁进 inv_stock_balance.locked_qty
        // （审核不再重复锁定，出库审核时释放并扣实物，关闭/删除时释放）
        Map<String, BigDecimal> locked = lockOrderNeed(warehouse, needOfPayload(details));
        if (!locked.isEmpty()) {
            log("SALES_ORDER", "创建", orderNo,
                    "库存校验通过并锁定 " + locked.size() + " 个商品，仓库：" + warehouse);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("orderId", orderId);
        out.put("orderNo", orderNo);
        out.put("amount", totalAmount);
        out.put("effect", warehouse.isBlank() ? "已保存（未指定仓库，未占用库存）" : "已保存并占用库存");
        out.put("success", true);
        return ApiResponse.ok(out);
    }

    @PostMapping("/sales/order/page")
    public ApiResponse<PageResult<Map<String, Object>>> salesPage(@RequestBody PageRequest request) {
        // 走 snake_case AS snake_case，让 camelize 正确转成驼峰
        // outbound_count / outbound_audited_count：供前端收敛行内按钮
        //   已生成出库单 → 不显示【生成出库单】；已有已审核出库单（已出库）→ 不显示【反审核】
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT so.order_id, so.order_no, so.customer, so.customer_code,
                       so.salesman, so.warehouse, so.bill_date, so.expected_delivery_date,
                       so.price_group_code, so.amount, so.paid_amount, so.unpaid_amount,
                       so.outbound_status, so.outbound_amount,
                       so.credit_check, so.stock_check,
                       so.status, so.creator_name, so.create_time, so.audit_time, so.audit_user, so.remark,
                       (SELECT COUNT(*) FROM sales_outbound o WHERE o.source_order = so.order_no)
                           AS outbound_count,
                       (SELECT COUNT(*) FROM sales_outbound o WHERE o.source_order = so.order_no AND o.status = 'APPROVED')
                           AS outbound_audited_count
                FROM sales_order so ORDER BY so.create_time DESC, so.order_no DESC
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
                "SELECT status, warehouse FROM sales_order WHERE order_id = ?", orderId);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"PENDING".equals(status)) return ApiResponse.fail("400", "仅待审核销售订单允许编辑");
        String oldWarehouse = str(pickCS(rows.get(0), "warehouse"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = req.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> d : details) totalAmount = totalAmount.add(toBd(d.get("amount")));

        // 库存校验：订单在创建时已占用库存，可用库存里已经扣掉了自己，
        // 所以判断口径是「新数量 ≤ 可用 + 本单已占用」，而不是「新数量 ≤ 可用」。
        // 换仓库时旧仓库的占用与新仓库无关，本单已占用按 0 算（旧仓库的锁在下面整体释放）。
        String newWarehouse = str(req.get("warehouseId"));
        Map<String, BigDecimal> oldNeed = needOfOrder(orderId);
        Map<String, BigDecimal> newNeed = needOfPayload(details);
        String shortage = checkStockAllowingOwnLock(newWarehouse, newNeed,
                newWarehouse.equals(oldWarehouse) ? oldNeed : Map.of(), goodsNamesOfPayload(details));
        if (shortage != null) return ApiResponse.fail("400", shortage);

        // 先整体释放旧占用，再整体锁定新数量。
        // 校验公式保证释放后一定锁得回来（含历史未锁订单：那时 min(旧量, 已锁) 本来就小）。
        releaseLocks(oldWarehouse, oldNeed);
        lockOrderNeed(newWarehouse, newNeed);

        jdbcTemplate.update("""
                UPDATE sales_order SET customer = ?, customer_code = ?, salesman = ?, warehouse = ?,
                    bill_date = ?, expected_delivery_date = ?, price_group_code = ?,
                    amount = ?, unpaid_amount = ?, remark = ?, stock_check = ?
                WHERE order_id = ?
                """,
                str(req.get("customerName")), str(req.get("customerCode")),
                str(req.get("salesman")), str(req.get("warehouseId")),
                parseDate(req.get("billDate")), parseDate(req.get("expectedDeliveryDate")),
                str(req.get("priceGroupCode")), totalAmount, totalAmount, str(req.get("remark")),
                newWarehouse.isBlank() ? null : "通过",
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
        out.put("effect", newWarehouse.isBlank() ? "已保存（未指定仓库，未占用库存）" : "已保存并按新数量占用库存");
        out.put("success", true);
        return ApiResponse.ok(out);
    }

    /**
     * 销售订单审核：PENDING → APPROVED，写审核时间，outbound_status='待出库'。
     *
     * <p><b>不再锁定库存</b> —— 库存在订单创建时就已经占用（{@code createSales} → {@code lockStock}），
     * 审核只是状态流转，重复锁定会把同一批货占用两次。
     *
     * <p>同理也不能再拿「可用库存 ≥ 本单数量」校验：本单的量已在 {@code locked_qty} 里，
     * 可用库存已被自己扣掉，这么比必然自己挡自己。这里改为兜底校验
     * <b>实物库存 ≥ 本单数量</b>——实物可能被盘亏、其他出库、调拨抽走，那种情况必须拦。
     */
    @PostMapping("/sales/order/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditSales(@RequestBody Map<String, Object> req) {
        String orderId = str(req.get("orderId"));
        if (orderId.isBlank()) orderId = str(req.get("bizId"));
        if (orderId.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_id, order_no, warehouse, status FROM sales_order WHERE order_id = ? OR order_no = ?",
                orderId, orderId);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"PENDING".equals(status)) return ApiResponse.fail("400", "只有待审核订单可审核，当前状态：" + status);
        String realOrderId = str(pickCS(rows.get(0), "order_id"));
        String orderNo = str(pickCS(rows.get(0), "order_no"));
        String warehouse = str(pickCS(rows.get(0), "warehouse"));

        Map<String, BigDecimal> need = needOfOrder(realOrderId);
        String shortage = checkPhysicalByNeed(warehouse, need, goodsNamesOfOrder(realOrderId));
        if (shortage != null) return ApiResponse.fail("400", shortage);

        jdbcTemplate.update("""
                UPDATE sales_order
                SET status = 'APPROVED', audit_time = CURRENT_TIMESTAMP, audit_user = ?,
                    outbound_status = '待出库', stock_check = '通过'
                WHERE order_id = ?
                """, "系统管理员", realOrderId);
        log("SALES_ORDER", "审核", orderNo,
                "库存已于创建时占用，本次审核不重复锁定；仓库：" + (warehouse.isBlank() ? "未指定" : warehouse));
        Map<String, Object> out = new HashMap<>();
        out.put("orderId", realOrderId);
        out.put("orderNo", orderNo);
        out.put("status", "APPROVED");
        out.put("effect", warehouse.isBlank()
                ? "已审核（未指定仓库，未占用库存）"
                : "已审核，库存自创建时起持续占用");
        out.put("success", true);
        return ApiResponse.ok(out);
    }

    /**
     * 销售订单反审核：APPROVED → PENDING。
     * <p>已生成的销售出库单若全部还是 PENDING（未审核、未动实物库存、未生成发货单），
     * 则连带删除后放行；只要有一张已审核，就拒绝反审核。
     *
     * <p><b>订单占用的库存不释放</b> —— 库存从创建就占用，反审核只是回到待审核，
     * 单子还在、还要出货，占用必须延续到出库或关闭。
     * 被删掉的出库单占用的是<b>批次库存</b>，那一层要跟着单子一起释放。
     */
    @PostMapping("/sales/order/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAuditSales(@RequestBody Map<String, Object> req) {
        String orderId = str(req.get("orderId"));
        if (orderId.isBlank()) orderId = str(req.get("bizId"));
        if (orderId.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_id, order_no, warehouse, status FROM sales_order WHERE order_id = ? OR order_no = ?",
                orderId, orderId);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"APPROVED".equals(status)) return ApiResponse.fail("400", "只有已审核订单可反审核");
        String realOrderId = str(pickCS(rows.get(0), "order_id"));
        String orderNo = str(pickCS(rows.get(0), "order_no"));

        // ① 已审核的出库单一律拦截：实物库存已扣减、发货单已生成，不能靠删单回退
        List<Map<String, Object>> outbounds = jdbcTemplate.queryForList(
                "SELECT outbound_id, outbound_no, status FROM sales_outbound WHERE source_order = ?", orderNo);
        List<String> blocked = new ArrayList<>();
        for (Map<String, Object> o : outbounds) {
            if (!"PENDING".equals(str(pickCS(o, "status")))) {
                blocked.add(str(pickCS(o, "outbound_no")) + "(" + str(pickCS(o, "status")) + ")");
            }
        }
        if (!blocked.isEmpty()) {
            return ApiResponse.fail("400", "出库单 " + String.join("、", blocked) + " 已审核，无法反审核；请先处理出库单");
        }
        // ② 防御性检查：PENDING 出库单理论上不会有发货单，被引用则不删
        for (Map<String, Object> o : outbounds) {
            String no = str(pickCS(o, "outbound_no"));
            Integer refCnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sales_receipt WHERE source_outbound_no = ?", Integer.class, no);
            if (refCnt != null && refCnt > 0) {
                return ApiResponse.fail("400", "出库单 " + no + " 已被发货单引用，无法反审核");
            }
        }
        // ③ 释放批次锁定 + 删除未审核出库单（明细 → 主表）
        List<String> deleted = new ArrayList<>();
        for (Map<String, Object> o : outbounds) {
            String oid = str(pickCS(o, "outbound_id"));
            releaseBatchLocksOfOutbound(oid);
            jdbcTemplate.update("DELETE FROM sales_outbound_detail WHERE outbound_id = ?", oid);
            jdbcTemplate.update("DELETE FROM sales_outbound WHERE outbound_id = ?", oid);
            deleted.add(str(pickCS(o, "outbound_no")));
        }
        // ④ 回退订单状态。stock_check 保持「通过」：库存仍被本单占用，不是没校验过
        jdbcTemplate.update("""
                UPDATE sales_order
                SET status = 'PENDING', audit_time = NULL, audit_user = NULL,
                    outbound_status = NULL, outbound_amount = 0
                WHERE order_id = ?
                """, realOrderId);
        // ⑤ 写操作日志
        log("SALES_ORDER", "反审核", orderNo, deleted.isEmpty()
                ? "订单库存占用保持不变"
                : "已删除未审核出库单 " + String.join("、", deleted) + " 并释放其批次锁定；订单库存占用保持不变");

        Map<String, Object> out = new HashMap<>();
        out.put("orderId", realOrderId);
        out.put("orderNo", orderNo);
        out.put("status", "PENDING");
        out.put("deletedOutbounds", deleted);
        out.put("effect", deleted.isEmpty()
                ? "已反审核，订单仍占用库存"
                : "已反审核，删除未审核出库单 " + String.join("、", deleted)
                        + " 并释放其批次锁定；订单仍占用库存");
        out.put("success", true);
        return ApiResponse.ok(out);
    }

    /** 销售订单关闭：APPROVED/PENDING → CLOSED，释放尚未出库部分的锁定库存。 */
    @PostMapping("/sales/order/close")
    @Transactional
    public ApiResponse<Map<String, Object>> closeSales(@RequestBody Map<String, Object> req) {
        String orderId = str(req.get("orderId"));
        if (orderId.isBlank()) orderId = str(req.get("bizId"));
        if (orderId.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_id, order_no, warehouse, status FROM sales_order WHERE order_id = ? OR order_no = ?",
                orderId, orderId);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"PENDING".equals(status) && !"APPROVED".equals(status)) {
            return ApiResponse.fail("400", "订单状态不允许关闭");
        }
        String realOrderId = str(pickCS(rows.get(0), "order_id"));
        String orderNo = str(pickCS(rows.get(0), "order_no"));
        String warehouse = str(pickCS(rows.get(0), "warehouse"));
        // PENDING 与 APPROVED 都占着库存（创建即占用），关闭时释放尚未出库的部分
        releaseLocks(warehouse, remainingLockOf(realOrderId, orderNo));
        jdbcTemplate.update(
                "UPDATE sales_order SET status = 'CLOSED', stock_check = NULL WHERE order_id = ?", realOrderId);
        log("SALES_ORDER", "关闭", orderNo, "原状态 " + status + "，已释放未出库部分的锁定库存");
        return ApiResponse.ok(Map.of("orderId", realOrderId, "orderNo", orderNo,
                "status", "CLOSED", "effect", "订单已关闭，未出库部分的库存占用已释放", "success", true));
    }

    /** 销售订单删除：仅 PENDING 可删；删除前释放本单占用的库存。 */
    @PostMapping("/sales/order/delete")
    @Transactional
    public ApiResponse<Map<String, Object>> deleteSales(@RequestBody Map<String, Object> req) {
        String orderId = str(req.get("orderId"));
        if (orderId.isBlank()) orderId = str(req.get("bizId"));
        if (orderId.isBlank()) return ApiResponse.fail("400", "缺少 orderId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_no, warehouse, status FROM sales_order WHERE order_id = ?", orderId);
        if (rows.isEmpty()) return ApiResponse.fail("404", "订单不存在");
        String status = str(pickCS(rows.get(0), "status"));
        if (!"PENDING".equals(status)) return ApiResponse.fail("400", "只有待审核订单可删除");
        String orderNo = str(pickCS(rows.get(0), "order_no"));
        String warehouse = str(pickCS(rows.get(0), "warehouse"));
        // 创建即占用，删除必须把占用还回去
        releaseLocks(warehouse, remainingLockOf(orderId, orderNo));
        jdbcTemplate.update("DELETE FROM sales_order_detail WHERE order_id = ?", orderId);
        jdbcTemplate.update("DELETE FROM sales_order WHERE order_id = ?", orderId);
        log("SALES_ORDER", "删除", orderNo, "已释放本单占用的库存");
        return ApiResponse.ok(Map.of("orderId", orderId, "effect", "订单已删除，库存占用已释放", "success", true));
    }

    /**
     * 销售快速开单 = 创建订单 + 立即审核。
     * 走 JdbcTemplate 复用 createSales / auditSales 逻辑，避免依赖已删除的老 SalesController helper。
     * <p>库存已由 {@code createSales} 占用，这里<b>不再重复锁定</b>。
     */
    @PostMapping("/sales/quick-order/create-and-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> quickOrderCreateAndAudit(@RequestBody Map<String, Object> req) {
        var createResult = createSales(req);
        // createSales 可能因库存不足返回 fail（data 为 null），必须先判成败再取值
        if (!"0".equals(createResult.code()) || createResult.data() == null) return createResult;
        Map<String, Object> data = createResult.data();
        String orderId = String.valueOf(data.get("orderId"));
        String warehouse = str(req.get("warehouseId"));
        // 内联审核，避免 Spring 代理自调用绕过事务
        jdbcTemplate.update("""
                UPDATE sales_order
                SET status = 'APPROVED', audit_time = CURRENT_TIMESTAMP, audit_user = ?,
                    outbound_status = '待出库', stock_check = '通过'
                WHERE order_id = ?
                """, "系统管理员（快速开单）", orderId);
        log("SALES_ORDER", "快速开单审核", String.valueOf(data.get("orderNo")),
                "库存已于创建时占用，本次审核不重复锁定；仓库：" + (warehouse.isBlank() ? "未指定" : warehouse));
        Map<String, Object> out = new HashMap<>();
        out.put("orderNo", data.get("orderNo"));
        out.put("orderId", orderId);
        out.put("status", "APPROVED");
        out.put("amount", data.get("amount"));
        out.put("effect", warehouse.isBlank() ? "快速开单已审核（未指定仓库，未占用库存）" : "快速开单已审核并占用库存");
        return ApiResponse.ok(out);
    }

    // ============ 销售订单 · 库存校验与锁定辅助 ============

    /**
     * 校验前端提交的明细（驼峰 key）可用库存是否足够，不足返回提示文案，足够返回 {@code null}。
     * <p>口径：可用库存 = 实物 − 锁定 − 冻结；比较的是业务单位 {@code qty}，与出库审核扣减的口径一致。
     * 销售属性为赠品/样品/兑换/陈列的行同样计入需求量。
     */
    private String checkStockOfPayload(String warehouse, List<Map<String, Object>> details) {
        return checkStockByNeed(warehouse, needOfPayload(details), goodsNamesOfPayload(details));
    }

    /** 前端提交的明细按商品汇总数量（同商品多行——正常品 + 赠品——合并）。 */
    private Map<String, BigDecimal> needOfPayload(List<Map<String, Object>> details) {
        Map<String, BigDecimal> need = new LinkedHashMap<>();
        for (Map<String, Object> d : details) {
            String code = str(d.get("goodsCode")).trim();
            if (code.isEmpty()) continue;
            need.merge(code, toBd(d.get("qty")), BigDecimal::add);
        }
        return need;
    }

    /** 前端提交的明细的商品编号 → 名称，仅用于拼提示文案。 */
    private Map<String, String> goodsNamesOfPayload(List<Map<String, Object>> details) {
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> d : details) {
            String code = str(d.get("goodsCode")).trim();
            if (code.isEmpty()) continue;
            names.putIfAbsent(code, str(d.get("goodsName")));
        }
        return names;
    }

    /**
     * 逐商品比对可用库存，返回第一条不足的提示。
     * <p>仓库为空时无法定位库存行，跳过校验（历史接口允许不传仓库，硬拦会打断既有流程）。
     */
    private String checkStockByNeed(String warehouse, Map<String, BigDecimal> need, Map<String, String> names) {
        if (warehouse == null || warehouse.isBlank() || need.isEmpty()) return null;
        for (Map.Entry<String, BigDecimal> e : need.entrySet()) {
            BigDecimal want = e.getValue();
            if (want == null || want.signum() <= 0) continue;
            BigDecimal available = inventoryCostService.getAvailableQty(e.getKey(), warehouse);
            if (available.compareTo(want) < 0) {
                String nm = names == null ? "" : str(names.get(e.getKey()));
                return "商品【" + e.getKey() + (nm.isBlank() ? "" : " " + nm) + "】库存不足：本单需 "
                        + plain(want) + "，" + warehouse + " 可用 " + plain(available);
            }
        }
        return null;
    }

    /** 订单明细按商品汇总数量（业务单位）。 */
    private Map<String, BigDecimal> needOfOrder(String realOrderId) {
        Map<String, BigDecimal> need = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT goods_code, qty FROM sales_order_detail WHERE order_id = ?", realOrderId)) {
            String code = str(pickCS(r, "goods_code")).trim();
            if (code.isEmpty()) continue;
            need.merge(code, toBd(pickCS(r, "qty")), BigDecimal::add);
        }
        return need;
    }

    /**
     * 编辑订单时的库存校验：{@code 新数量 ≤ 可用 + 本单已占用}。
     *
     * <p>订单创建时就把数量锁进了 {@code locked_qty}，可用库存已经扣掉了自己，
     * 直接用「新数量 ≤ 可用」判断，一张原封不动重存的单也会报库存不足。
     *
     * <p>本单已占用取 {@code min(旧数量, 当前锁定量)} —— 与
     * {@code InventoryCostService.releaseLock} 的向下夹取口径一致，
     * 保证「先整体释放旧占用、再整体锁定新数量」的第二步一定锁得回来
     * （历史未锁订单的 min 本来就小，不会给出锁不回来的额度）。
     */
    private String checkStockAllowingOwnLock(String warehouse, Map<String, BigDecimal> newNeed,
                                             Map<String, BigDecimal> oldNeed, Map<String, String> names) {
        if (warehouse == null || warehouse.isBlank() || newNeed.isEmpty()) return null;
        for (Map.Entry<String, BigDecimal> e : newNeed.entrySet()) {
            BigDecimal want = e.getValue();
            if (want == null || want.signum() <= 0) continue;
            String code = e.getKey();
            BigDecimal available = inventoryCostService.getAvailableQty(code, warehouse);
            BigDecimal own = oldNeed.getOrDefault(code, BigDecimal.ZERO)
                    .min(inventoryCostService.getLockedQty(code, warehouse));
            BigDecimal allowed = available.add(own);
            if (allowed.compareTo(want) < 0) {
                String nm = names == null ? "" : str(names.get(code));
                return "商品【" + code + (nm.isBlank() ? "" : " " + nm) + "】库存不足：本单需 "
                        + plain(want) + "，" + warehouse + " 可用 " + plain(allowed)
                        + "（含本单已占用 " + plain(own) + "）";
            }
        }
        return null;
    }

    /**
     * 审核时的兜底校验：{@code 实物库存 ≥ 本单数量}。
     *
     * <p>本单的量在创建时已锁进 {@code locked_qty}，拿可用库存比对必然自己挡自己。
     * 实物库存才是有意义的下限：盘亏、其他出库、调拨都可能把实物抽走，
     * 那种情况下这张单已经出不了货，必须在审核环节就拦住。
     */
    private String checkPhysicalByNeed(String warehouse, Map<String, BigDecimal> need, Map<String, String> names) {
        if (warehouse == null || warehouse.isBlank() || need.isEmpty()) return null;
        for (Map.Entry<String, BigDecimal> e : need.entrySet()) {
            BigDecimal want = e.getValue();
            if (want == null || want.signum() <= 0) continue;
            BigDecimal physical = inventoryCostService.getPhysicalQty(e.getKey(), warehouse);
            if (physical.compareTo(want) < 0) {
                String nm = names == null ? "" : str(names.get(e.getKey()));
                return "商品【" + e.getKey() + (nm.isBlank() ? "" : " " + nm) + "】实物库存不足：本单需 "
                        + plain(want) + "，" + warehouse + " 实物 " + plain(physical) + "，无法审核";
            }
        }
        return null;
    }

    /** 订单明细的商品编号 → 名称，仅用于拼提示文案。 */
    private Map<String, String> goodsNamesOfOrder(String realOrderId) {
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT goods_code, goods_name FROM sales_order_detail WHERE order_id = ?", realOrderId)) {
            names.putIfAbsent(str(pickCS(r, "goods_code")).trim(), str(pickCS(r, "goods_name")));
        }
        return names;
    }

    /**
     * 尚未释放的锁定量 = 订单数量 − 已审核出库单数量（逐商品，负数夹到 0）。
     * <p>已审核出库时 {@code SalesOutboundController.audit} 已释放对应锁定，这里不能重复释放。
     */
    private Map<String, BigDecimal> remainingLockOf(String realOrderId, String orderNo) {
        Map<String, BigDecimal> remaining = needOfOrder(realOrderId);
        for (Map<String, Object> r : jdbcTemplate.queryForList("""
                SELECT d.goods_code AS goods_code, COALESCE(SUM(d.qty), 0) AS out_qty
                FROM sales_outbound_detail d
                JOIN sales_outbound o ON o.outbound_id = d.outbound_id
                WHERE o.source_order = ? AND o.status = 'APPROVED'
                GROUP BY d.goods_code
                """, orderNo)) {
            String code = str(pickCS(r, "goods_code")).trim();
            BigDecimal already = toBd(pickCS(r, "out_qty"));
            BigDecimal left = remaining.getOrDefault(code, BigDecimal.ZERO).subtract(already);
            remaining.put(code, left.signum() < 0 ? BigDecimal.ZERO : left);
        }
        return remaining;
    }

    /** 批量释放锁定库存；仓库为空或数量非正时跳过。 */
    private void releaseLocks(String warehouse, Map<String, BigDecimal> qtyByGoods) {
        if (warehouse == null || warehouse.isBlank()) return;
        for (Map.Entry<String, BigDecimal> e : qtyByGoods.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() <= 0) continue;
            inventoryCostService.releaseLock(e.getKey(), warehouse, e.getValue());
        }
    }

    /**
     * 批量锁定库存（生成/编辑订单时占用）；仓库为空时整体跳过。
     *
     * @return 实际锁定的商品 → 数量；仓库为空时为空 Map
     */
    private Map<String, BigDecimal> lockOrderNeed(String warehouse, Map<String, BigDecimal> qtyByGoods) {
        Map<String, BigDecimal> locked = new LinkedHashMap<>();
        if (warehouse == null || warehouse.isBlank()) return locked;
        for (Map.Entry<String, BigDecimal> e : qtyByGoods.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() <= 0) continue;
            inventoryCostService.lockStock(e.getKey(), warehouse, e.getValue());
            locked.put(e.getKey(), e.getValue());
        }
        return locked;
    }

    /**
     * 释放一张销售出库单占用的批次库存（订单反审核连带删除出库单时调用）。
     * <p>批次仓库取出库单明细自己的 warehouse —— 出库单可能与订单不同仓。
     */
    private void releaseBatchLocksOfOutbound(String outboundId) {
        for (Map<String, Object> d : jdbcTemplate.queryForList(
                "SELECT goods_code, warehouse, batch_no, qty FROM sales_outbound_detail WHERE outbound_id = ?",
                outboundId)) {
            inventoryCostService.releaseBatchLock(
                    str(pickCS(d, "goods_code")),
                    str(pickCS(d, "warehouse")),
                    str(pickCS(d, "batch_no")),
                    toBd(pickCS(d, "qty")));
        }
    }

    /** 业务操作日志，与 SalesOutboundController 同一张表、同一套字段。 */
    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                moduleCode, action, bizNo, detail);
    }

    /** 去掉 DECIMAL(18,2) 带来的多余小数尾巴，让提示文案里的数量好读。 */
    private static String plain(BigDecimal v) {
        if (v == null) return "0";
        return v.stripTrailingZeros().toPlainString();
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
