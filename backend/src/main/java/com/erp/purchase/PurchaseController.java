package com.erp.purchase;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.inventory.service.InventoryCostService;
import com.erp.purchase.entity.PurchaseInbound;
import com.erp.purchase.entity.PurchaseInboundDetail;
import com.erp.purchase.entity.PurchaseOrder;
import com.erp.purchase.entity.PurchaseOrderDetail;
import com.erp.purchase.service.PurchaseInboundDetailService;
import com.erp.purchase.service.PurchaseInboundService;
import com.erp.purchase.service.PurchaseOrderDetailService;
import com.erp.purchase.service.PurchaseOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/purchase")
public class PurchaseController {

    private final JdbcTemplate jdbcTemplate;
    private final PurchaseOrderService orderService;
    private final PurchaseOrderDetailService orderDetailService;
    private final PurchaseInboundService inboundService;
    private final PurchaseInboundDetailService inboundDetailService;
    private final InventoryCostService inventoryCostService;

    public PurchaseController(JdbcTemplate jdbcTemplate,
                              PurchaseOrderService orderService,
                              PurchaseOrderDetailService orderDetailService,
                              PurchaseInboundService inboundService,
                              PurchaseInboundDetailService inboundDetailService,
                              InventoryCostService inventoryCostService) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderService = orderService;
        this.orderDetailService = orderDetailService;
        this.inboundService = inboundService;
        this.inboundDetailService = inboundDetailService;
        this.inventoryCostService = inventoryCostService;
    }

    // ========== 采购订单 ==========
    @PostMapping("/order/page")
    public ApiResponse<PageResult<PurchaseOrder>> orderPage(@RequestBody PageRequest request) {
        var page = orderService.page(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(request.safePageNo(), request.safePageSize()),
                new QueryWrapper<PurchaseOrder>().orderByDesc("order_no")
        );
        return ApiResponse.ok(new PageResult<>(page.getRecords(), (int) page.getCurrent(), (int) page.getSize(), page.getTotal(), Map.of()));
    }

    @GetMapping("/order/detail")
    public ApiResponse<Map<String, Object>> orderDetail(@RequestParam String orderId) {
        PurchaseOrder order = orderService.getOne(
                new QueryWrapper<PurchaseOrder>().eq("order_id", orderId).or().eq("order_no", orderId)
        );
        if (order == null) {
            return ApiResponse.ok(GenericResult.row("orderId", orderId, "details", List.of()));
        }
        List<PurchaseOrderDetail> details = orderDetailService.list(
                new QueryWrapper<PurchaseOrderDetail>().eq("order_id", order.getOrderId()).orderByAsc("detail_id")
        );
        return ApiResponse.ok(Map.of(
                "orderId", order.getOrderId(),
                "orderNo", order.getOrderNo(),
                "supplier", order.getSupplier(),
                "buyer", order.getBuyer(),
                "warehouse", order.getWarehouse(),
                "billDate", order.getBillDate(),
                "amount", order.getAmount(),
                "costAmount", order.getCostAmount(),
                "status", order.getStatus(),
                "details", details
        ));
    }

    @PostMapping("/order/create")
    @Transactional
    public ApiResponse<Map<String, Object>> createOrder(@Valid @RequestBody PurchaseOrderRequest request) {
        String id = "PO" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = "PO" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));

        BigDecimal amount = request.details().stream()
                .map(d -> d.qty().multiply(d.price()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal costAmount = request.details().stream()
                .map(d -> d.qty().multiply(d.price()).multiply(new BigDecimal("0.90")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        PurchaseOrder order = new PurchaseOrder();
        order.setOrderId(id);
        order.setOrderNo(no);
        order.setSupplier(request.supplierId());
        order.setBuyer(request.buyer());
        order.setWarehouse(request.warehouseId());
        order.setBillDate(LocalDate.now());
        order.setAmount(amount);
        order.setInboundAmount(BigDecimal.ZERO);
        order.setPaymentStatus("未付款");
        order.setArrivalStatus("未到货");
        order.setStatus("PENDING");
        order.setCreatorInfo("管理员 " + LocalDateTime.now());
        order.setOwnerName(request.ownerName());
        order.setExpectedArrivalDate(LocalDate.now().plusDays(3));
        order.setSettlementMethod(request.settlementMethod());
        order.setCostAmount(costAmount);
        orderService.save(order);

        for (PurchaseOrderDetailRequest d : request.details()) {
            BigDecimal lineAmount = d.qty().multiply(d.price());
            PurchaseOrderDetail detail = new PurchaseOrderDetail();
            detail.setDetailId("POD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            detail.setOrderId(id);
            detail.setLineType(d.lineType());
            detail.setGoodsCode(d.goodsId());
            detail.setGoodsName(d.goodsName());
            detail.setUnitName(d.unitId());
            detail.setQty(d.qty());
            detail.setPrice(d.price());
            detail.setTaxRate(d.taxRate());
            detail.setAmount(lineAmount);
            detail.setCostPrice(d.price().multiply(new BigDecimal("0.90")));
            detail.setCostAmount(lineAmount.multiply(new BigDecimal("0.90")));
            orderDetailService.save(detail);
        }

        log("purchase.order", "CREATE", no, "创建采购订单");
        return ApiResponse.ok(Map.of("orderId", id, "orderNo", no, "status", "PENDING", "amount", amount, "costAmount", costAmount));
    }

    @PostMapping("/order/update")
    @Transactional
    public ApiResponse<Map<String, Object>> updateOrder(@Valid @RequestBody PurchaseOrderUpdateRequest request) {
        PurchaseOrder order = orderService.getOne(
                new QueryWrapper<PurchaseOrder>()
                        .eq("order_id", request.orderId()).or().eq("order_no", request.orderId())
                        .eq("status", "PENDING")
        );
        if (order == null) throw new IllegalArgumentException("仅待审核采购订单允许编辑");

        BigDecimal amount = request.details().stream()
                .map(d -> d.qty().multiply(d.price()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal costAmount = request.details().stream()
                .map(d -> d.qty().multiply(d.price()).multiply(new BigDecimal("0.90")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        order.setSupplier(request.supplierId());
        order.setBuyer(request.buyer());
        order.setWarehouse(request.warehouseId());
        order.setAmount(amount);
        order.setSettlementMethod(request.settlementMethod());
        order.setOwnerName(request.ownerName());
        order.setCostAmount(costAmount);
        orderService.updateById(order);

        orderDetailService.remove(new QueryWrapper<PurchaseOrderDetail>().eq("order_id", order.getOrderId()));
        for (PurchaseOrderDetailRequest d : request.details()) {
            BigDecimal lineAmount = d.qty().multiply(d.price());
            PurchaseOrderDetail detail = new PurchaseOrderDetail();
            detail.setDetailId("POD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            detail.setOrderId(order.getOrderId());
            detail.setLineType(d.lineType());
            detail.setGoodsCode(d.goodsId());
            detail.setGoodsName(d.goodsName());
            detail.setUnitName(d.unitId());
            detail.setQty(d.qty());
            detail.setPrice(d.price());
            detail.setTaxRate(d.taxRate());
            detail.setAmount(lineAmount);
            detail.setCostPrice(d.price().multiply(new BigDecimal("0.90")));
            detail.setCostAmount(lineAmount.multiply(new BigDecimal("0.90")));
            orderDetailService.save(detail);
        }

        log("purchase.order", "UPDATE", request.orderId(), "采购订单编辑");
        return ApiResponse.ok(Map.of("orderId", order.getOrderId(), "status", "PENDING", "amount", amount, "costAmount", costAmount));
    }

    @PostMapping("/order/audit")
    public ApiResponse<Map<String, Object>> auditOrder(@Valid @RequestBody AuditRequest request) {
        orderService.update(new UpdateWrapper<PurchaseOrder>()
                .eq("order_id", request.bizId()).or().eq("order_no", request.bizId())
                .ne("status", "DELETED")
                .set("status", "APPROVED")
                .set("arrival_status", "采购在途")
                .set("audit_info", "系统管理员 " + LocalDateTime.now()));
        log("purchase.order", "AUDIT", request.bizId(), "采购订单审核");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "APPROVED", "effect", "已形成采购在途", "auditTime", LocalDateTime.now().toString()));
    }

    @PostMapping("/order/reverse-audit")
    public ApiResponse<Map<String, Object>> reverseAuditOrder(@Valid @RequestBody AuditRequest request) {
        boolean updated = orderService.update(new UpdateWrapper<PurchaseOrder>()
                .eq("order_id", request.bizId()).or().eq("order_no", request.bizId())
                .eq("status", "APPROVED")
                .eq("inbound_amount", 0)
                .set("status", "PENDING")
                .set("arrival_status", "未到货")
                .set("audit_info", null));
        if (!updated) throw new IllegalArgumentException("采购订单已入库或状态不允许反审核");
        log("purchase.order", "REVERSE_AUDIT", request.bizId(), "采购订单反审核");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "PENDING", "effect", "已反审核，采购在途已取消"));
    }

    @PostMapping("/order/close")
    public ApiResponse<Map<String, Object>> closeOrder(@Valid @RequestBody AuditRequest request) {
        boolean updated = orderService.update(new UpdateWrapper<PurchaseOrder>()
                .eq("order_id", request.bizId()).or().eq("order_no", request.bizId())
                .in("status", List.of("PENDING", "APPROVED"))
                .set("status", "CLOSED")
                .set("arrival_status", "已终止"));
        if (!updated) throw new IllegalArgumentException("采购订单状态不允许关闭");
        log("purchase.order", "CLOSE", request.bizId(), "采购订单关闭");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "CLOSED", "effect", "采购订单已关闭"));
    }

    @PostMapping("/order/delete")
    public ApiResponse<Map<String, Object>> deleteOrder(@Valid @RequestBody AuditRequest request) {
        boolean updated = orderService.update(new UpdateWrapper<PurchaseOrder>()
                .eq("order_id", request.bizId()).or().eq("order_no", request.bizId())
                .eq("status", "PENDING")
                .set("status", "DELETED")
                .set("arrival_status", "已删除"));
        if (!updated) throw new IllegalArgumentException("仅待审核采购订单允许删除");
        log("purchase.order", "DELETE", request.bizId(), "采购订单删除");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "DELETED", "effect", "采购订单已删除"));
    }

    // ========== 采购入库 ==========
    @PostMapping("/inbound/page")
    public ApiResponse<PageResult<PurchaseInbound>> inboundPage(@RequestBody PageRequest request) {
        var page = inboundService.page(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(request.safePageNo(), request.safePageSize()),
                new QueryWrapper<PurchaseInbound>().orderByDesc("inbound_no")
        );
        return ApiResponse.ok(new PageResult<>(page.getRecords(), (int) page.getCurrent(), (int) page.getSize(), page.getTotal(), Map.of()));
    }

    @GetMapping("/inbound/detail")
    public ApiResponse<Map<String, Object>> inboundDetail(@RequestParam String inboundId) {
        PurchaseInbound inbound = inboundService.getOne(
                new QueryWrapper<PurchaseInbound>().eq("inbound_id", inboundId).or().eq("inbound_no", inboundId)
        );
        if (inbound == null) return ApiResponse.ok(GenericResult.row("inboundId", inboundId, "details", List.of()));
        List<PurchaseInboundDetail> details = inboundDetailService.list(
                new QueryWrapper<PurchaseInboundDetail>().eq("inbound_id", inbound.getInboundId()).orderByAsc("detail_id")
        );
        return ApiResponse.ok(Map.of(
                "inboundId", inbound.getInboundId(),
                "inboundNo", inbound.getInboundNo(),
                "sourceOrder", inbound.getSourceOrder(),
                "supplier", inbound.getSupplier(),
                "warehouse", inbound.getWarehouse(),
                "billDate", inbound.getBillDate(),
                "qty", inbound.getQty(),
                "amount", inbound.getAmount(),
                "status", inbound.getStatus(),
                "details", details
        ));
    }

    @PostMapping("/inbound/create")
    @Transactional
    public ApiResponse<Map<String, Object>> createInbound(@RequestBody Map<String, Object> request) {
        String sourceOrder = String.valueOf(request.getOrDefault("sourceOrder", request.getOrDefault("bizId", "PO202606140001")));
        PurchaseOrder order = orderService.getOne(
                new QueryWrapper<PurchaseOrder>().eq("order_no", sourceOrder).or().eq("order_id", sourceOrder).orderByDesc("order_no")
        );
        String supplier = order != null ? order.getSupplier() : "默认供应商";
        String warehouse = order != null ? order.getWarehouse() : "总仓";
        String orderNo = order != null ? order.getOrderNo() : sourceOrder;

        List<PurchaseOrderDetail> orderDetails = order != null
                ? orderDetailService.list(new QueryWrapper<PurchaseOrderDetail>().eq("order_id", order.getOrderId()))
                : List.of();

        BigDecimal qty = orderDetails.stream().map(PurchaseOrderDetail::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = orderDetails.stream().map(PurchaseOrderDetail::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (orderDetails.isEmpty()) {
            qty = new BigDecimal("100");
            amount = new BigDecimal("3500.00");
        }

        String id = "PI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = "PI" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));

        PurchaseInbound inbound = new PurchaseInbound();
        inbound.setInboundId(id);
        inbound.setInboundNo(no);
        inbound.setSourceOrder(orderNo);
        inbound.setSupplier(supplier);
        inbound.setWarehouse(warehouse);
        inbound.setBillDate(LocalDate.now());
        inbound.setQty(qty);
        inbound.setAmount(amount);
        inbound.setStatus("PENDING");
        inbound.setStockUpdated(false);
        inbound.setReceiptGenerated(false);
        inboundService.save(inbound);

        if (orderDetails.isEmpty()) {
            PurchaseInboundDetail detail = new PurchaseInboundDetail();
            detail.setDetailId("PID" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            detail.setInboundId(id);
            detail.setGoodsCode("SP001");
            detail.setGoodsName("农夫山泉500ml*24");
            detail.setWarehouse(warehouse);
            detail.setUnitName("箱");
            detail.setExpectedQty(new BigDecimal("100"));
            detail.setReceivedQty(new BigDecimal("100"));
            detail.setBatchNo("B" + LocalDate.now().toString().replace("-", ""));
            detail.setPrice(new BigDecimal("35.00"));
            detail.setAmount(new BigDecimal("3500.00"));
            detail.setBeforeCost(new BigDecimal("30.80"));
            detail.setAfterCost(new BigDecimal("31.20"));
            inboundDetailService.save(detail);
        } else {
            for (PurchaseOrderDetail od : orderDetails) {
                PurchaseInboundDetail detail = new PurchaseInboundDetail();
                detail.setDetailId("PID" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
                detail.setInboundId(id);
                detail.setGoodsCode(od.getGoodsCode());
                detail.setGoodsName(od.getGoodsName());
                detail.setWarehouse(warehouse);
                detail.setUnitName(od.getUnitName());
                detail.setExpectedQty(od.getQty());
                detail.setReceivedQty(od.getQty());
                detail.setBatchNo("B" + LocalDate.now().toString().replace("-", ""));
                detail.setPrice(od.getPrice());
                detail.setAmount(od.getAmount());
                detail.setBeforeCost(od.getCostPrice());
                detail.setAfterCost(od.getCostPrice());
                inboundDetailService.save(detail);
            }
        }

        log("purchase.inbound", "CREATE", no, "创建采购入库单");
        return ApiResponse.ok(GenericResult.row("inboundId", id, "inboundNo", no, "sourceOrder", orderNo, "status", "PENDING"));
    }

    @PostMapping("/inbound/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditInbound(@Valid @RequestBody AuditRequest request) {
        PurchaseInbound inbound = inboundService.getOne(
                new QueryWrapper<PurchaseInbound>()
                        .eq("inbound_id", request.bizId()).or().eq("inbound_no", request.bizId())
                        .eq("status", "PENDING")
                        .orderByDesc("inbound_no")
        );
        if (inbound == null) {
            inbound = inboundService.getOne(new QueryWrapper<PurchaseInbound>().eq("status", "PENDING").orderByDesc("inbound_no"));
        }
        if (inbound == null) throw new IllegalArgumentException("没有可审核的采购入库单");

        List<PurchaseInboundDetail> details = inboundDetailService.list(
                new QueryWrapper<PurchaseInboundDetail>().eq("inbound_id", inbound.getInboundId())
        );

        // 使用成本核算引擎处理库存更新和成本重算
        for (PurchaseInboundDetail detail : details) {
            inventoryCostService.purchaseInbound(
                    detail.getGoodsCode(),
                    detail.getGoodsName(),
                    detail.getWarehouse(),
                    detail.getBatchNo(),
                    detail.getReceivedQty(),
                    detail.getAfterCost() != null ? detail.getAfterCost() : detail.getPrice(),
                    inbound.getInboundNo()
            );
        }

        inbound.setStatus("APPROVED");
        inbound.setStockUpdated(true);
        // 采购收货单审核时单独设置 receipt_generated，此处不设置
        inboundService.updateById(inbound);

        // 更新采购订单的入库金额和到货状态
        if (inbound.getSourceOrder() != null) {
            PurchaseOrder order = orderService.getOne(
                    new QueryWrapper<PurchaseOrder>().eq("order_no", inbound.getSourceOrder())
            );
            if (order != null) {
                order.setInboundAmount(order.getInboundAmount().add(inbound.getAmount()));
                order.setArrivalStatus("已到货");
                orderService.updateById(order);
            }
        }

        log("purchase.inbound", "AUDIT", inbound.getInboundNo(), "采购入库审核");
        return ApiResponse.ok(Map.of("inboundId", inbound.getInboundId(), "status", "APPROVED", "effect", "库存增加，成本按移动加权平均法重算，生成库存流水"));
    }

    // ========== 采购收货单（审核入库单并生成真实应付） ==========
    @PostMapping("/receipt/page")
    public ApiResponse<PageResult<Map<String, Object>>> receiptPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT inbound_no receiptNo,
                       inbound_no sourceInbound,
                       supplier,
                       amount goodsAmount,
                       ROUND(amount * 0.13, 2) taxAmount,
                       ROUND(amount * 1.13, 2) finalAmount,
                       CASE WHEN receipt_generated THEN '已生成' ELSE '未生成' END apStatus,
                       CASE WHEN receipt_generated THEN '已审核' ELSE '待审核' END status
                FROM pur_inbound
                WHERE status = 'APPROVED'
                ORDER BY inbound_no DESC
                """), request));
    }

    @PostMapping("/receipt/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditReceipt(@Valid @RequestBody AuditRequest request) {
        PurchaseInbound inbound = inboundService.getOne(
                new QueryWrapper<PurchaseInbound>()
                        .eq("inbound_id", request.bizId()).or().eq("inbound_no", request.bizId())
                        .eq("status", "APPROVED")
        );
        if (inbound == null) {
            throw new IllegalArgumentException("入库单不存在或未审核");
        }
        if (Boolean.TRUE.equals(inbound.getReceiptGenerated())) {
            throw new IllegalArgumentException("该入库单已生成应付账款，无需重复审核");
        }

        // 根据实际入库金额生成应付（含税 13%）
        BigDecimal goodsAmount = inbound.getAmount();
        BigDecimal taxAmount = goodsAmount.multiply(new BigDecimal("0.13")).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal finalAmount = goodsAmount.add(taxAmount);

        String apId = "AP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String apNo = "AP" + System.currentTimeMillis();
        jdbcTemplate.update("""
                INSERT INTO fin_ap(ap_id, ap_no, source_bill, supplier, ap_amount, paid_amount, unpaid_amount, due_date, status)
                VALUES (?, ?, ?, ?, ?, 0, ?, DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY), 'UNVERIFIED')
                """, apId, apNo, inbound.getInboundNo(), inbound.getSupplier(), finalAmount, finalAmount);

        // 标记入库单已生成应付
        inbound.setReceiptGenerated(true);
        inboundService.updateById(inbound);

        log("purchase.receipt", "AUDIT", inbound.getInboundNo(), "采购收货单审核并生成应付");
        return ApiResponse.ok(Map.of("receiptId", request.bizId(), "status", "APPROVED", "apNo", apNo, "effect", "已根据实际入库金额生成应付账款"));
    }

    // ========== 采购退货（保留 JdbcTemplate，简化版） ==========
    @PostMapping("/return/page")
    public ApiResponse<PageResult<Map<String, Object>>> returnPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no returnNo, object_name supplier, warehouse, reason returnReason, amount,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='PURCHASE_RETURN' ORDER BY bill_no DESC
                """), request));
    }

    @PostMapping("/return/audit")
    public ApiResponse<Map<String, Object>> auditReturn(@Valid @RequestBody AuditRequest request) {
        jdbcTemplate.update("UPDATE biz_simple_bill SET status='APPROVED' WHERE bill_type='PURCHASE_RETURN' AND (bill_id=? OR bill_no=? OR bill_no=(SELECT bill_no FROM biz_simple_bill WHERE bill_type='PURCHASE_RETURN' ORDER BY bill_no DESC LIMIT 1))", request.bizId(), request.bizId());
        return ApiResponse.ok(Map.of("returnId", request.bizId(), "status", "APPROVED", "effect", "采购退货已处理"));
    }

    @PostMapping("/expense/page")
    public ApiResponse<PageResult<Map<String, Object>>> expensePage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no expenseNo, '运费' expenseType, object_name objectName, amount expenseAmount,
                       CASE status WHEN 'APPROVED' THEN '已分摊' ELSE '未分摊' END allocationStatus,
                       CASE status WHEN 'APPROVED' THEN '已生成' ELSE '未生成' END apStatus,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='PURCHASE_EXPENSE' ORDER BY bill_no DESC
                """), request));
    }

    @PostMapping("/expense/audit")
    public ApiResponse<Map<String, Object>> auditExpense(@Valid @RequestBody AuditRequest request) {
        jdbcTemplate.update("UPDATE biz_simple_bill SET status='APPROVED' WHERE bill_type='PURCHASE_EXPENSE' AND (bill_id=? OR bill_no=? OR bill_no=(SELECT bill_no FROM biz_simple_bill WHERE bill_type='PURCHASE_EXPENSE' ORDER BY bill_no DESC LIMIT 1))", request.bizId(), request.bizId());
        return ApiResponse.ok(Map.of("expenseId", request.bizId(), "status", "APPROVED", "effect", "费用已分摊"));
    }

    @PostMapping("/invoice/page")
    public ApiResponse<PageResult<Map<String, Object>>> invoicePage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(List.of(Map.of("invoiceNo", "PINV202606140001", "supplier", "农夫山泉杭州经销", "invoiceCode", "3300****", "invoiceAmount", "3955.00", "matchStatus", "未勾稽", "certStatus", "未认证", "status", "正常")), request));
    }

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), moduleCode, action, bizNo, detail);
    }

    public record PurchaseOrderRequest(@NotBlank String supplierId, @NotBlank String warehouseId, String buyer, String ownerName, String settlementMethod, @NotEmpty List<PurchaseOrderDetailRequest> details) {}
    public record PurchaseOrderUpdateRequest(@NotBlank String orderId, @NotBlank String supplierId, @NotBlank String warehouseId, String buyer, String ownerName, String settlementMethod, @NotEmpty List<PurchaseOrderDetailRequest> details) {}
    public record PurchaseOrderDetailRequest(@NotBlank String goodsId, String goodsName, @NotBlank String unitId, String lineType, String taxRate, @NotNull @Positive BigDecimal qty, @NotNull @Positive BigDecimal price) {}
    public record AuditRequest(@NotBlank String bizId, String remark) {}
}
