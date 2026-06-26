package com.erp.sales;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.inventory.service.InventoryCostService;
import com.erp.sales.entity.SalesOrder;
import com.erp.sales.entity.SalesOrderDetail;
import com.erp.sales.entity.SalesOutbound;
import com.erp.sales.entity.SalesOutboundDetail;
import com.erp.sales.service.SalesOrderDetailService;
import com.erp.sales.service.SalesOrderService;
import com.erp.sales.service.SalesOutboundDetailService;
import com.erp.sales.service.SalesOutboundService;
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
@RequestMapping("/sales")
public class SalesController {

    private final JdbcTemplate jdbcTemplate;
    private final SalesOrderService orderService;
    private final SalesOrderDetailService orderDetailService;
    private final SalesOutboundService outboundService;
    private final SalesOutboundDetailService outboundDetailService;
    private final InventoryCostService inventoryCostService;

    public SalesController(JdbcTemplate jdbcTemplate,
                           SalesOrderService orderService,
                           SalesOrderDetailService orderDetailService,
                           SalesOutboundService outboundService,
                           SalesOutboundDetailService outboundDetailService,
                           InventoryCostService inventoryCostService) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderService = orderService;
        this.orderDetailService = orderDetailService;
        this.outboundService = outboundService;
        this.outboundDetailService = outboundDetailService;
        this.inventoryCostService = inventoryCostService;
    }

    @PostMapping("/order/page")
    public ApiResponse<PageResult<SalesOrder>> orderPage(@RequestBody PageRequest request) {
        var page = orderService.page(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(request.safePageNo(), request.safePageSize()),
                new QueryWrapper<SalesOrder>().orderByDesc("order_no")
        );
        return ApiResponse.ok(new PageResult<>(page.getRecords(), (int) page.getCurrent(), (int) page.getSize(), page.getTotal(), Map.of()));
    }

    @GetMapping("/order/detail")
    public ApiResponse<Map<String, Object>> orderDetail(@RequestParam String orderId) {
        SalesOrder order = orderService.getOne(
                new QueryWrapper<SalesOrder>().eq("order_id", orderId).or().eq("order_no", orderId)
        );
        if (order == null) return ApiResponse.ok(Map.of("orderId", orderId, "details", List.of()));
        List<SalesOrderDetail> details = orderDetailService.list(
                new QueryWrapper<SalesOrderDetail>().eq("order_id", order.getOrderId()).orderByAsc("detail_id")
        );
        return ApiResponse.ok(Map.of(
                "orderId", order.getOrderId(),
                "orderNo", order.getOrderNo(),
                "customer", order.getCustomer(),
                "salesman", order.getSalesman(),
                "warehouse", order.getWarehouse(),
                "amount", order.getAmount(),
                "costAmount", order.getCostAmount(),
                "status", order.getStatus(),
                "details", details
        ));
    }

    @PostMapping("/order/create")
    @Transactional
    public ApiResponse<Map<String, Object>> createOrder(@Valid @RequestBody SalesOrderRequest request) {
        String id = "SO" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = "SO" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));
        BigDecimal amount = request.details().stream().map(d -> d.qty().multiply(d.price())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal costAmount = amount.multiply(new BigDecimal("0.90"));

        SalesOrder order = new SalesOrder();
        order.setOrderId(id);
        order.setOrderNo(no);
        order.setCustomer(request.customerId());
        order.setSalesman(request.salesman());
        order.setWarehouse(request.warehouseId());
        order.setBillDate(LocalDate.now());
        order.setAmount(amount);
        order.setPaidAmount(BigDecimal.ZERO);
        order.setUnpaidAmount(amount);
        order.setCreditCheck("通过");
        order.setStockCheck("通过");
        order.setOutboundStatus("未出库");
        order.setSignStatus("未签收");
        order.setStatus("PENDING");
        order.setLineType(request.lineType());
        order.setCostAmount(costAmount);
        order.setCreatorName("管理员");
        orderService.save(order);

        for (SalesOrderDetailRequest d : request.details()) {
            BigDecimal lineAmount = d.qty().multiply(d.price());
            SalesOrderDetail detail = new SalesOrderDetail();
            detail.setDetailId("SOD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            detail.setOrderId(id);
            detail.setLineType(d.lineType());
            detail.setGoodsCode(d.goodsId());
            detail.setGoodsName(d.goodsName());
            detail.setUnitName(d.unitId());
            detail.setQty(d.qty());
            detail.setPrice(d.price());
            detail.setDiscountRate(d.discountRate());
            detail.setTaxRate(d.taxRate());
            detail.setAmount(lineAmount);
            detail.setCostPrice(d.price().multiply(new BigDecimal("0.90")));
            detail.setCostAmount(lineAmount.multiply(new BigDecimal("0.90")));
            orderDetailService.save(detail);
        }
        return ApiResponse.ok(Map.of("orderId", id, "orderNo", no, "status", "PENDING", "amount", amount, "costAmount", costAmount));
    }

    @PostMapping("/order/update")
    @Transactional
    public ApiResponse<Map<String, Object>> updateOrder(@Valid @RequestBody SalesOrderUpdateRequest request) {
        SalesOrder order = orderService.getOne(
                new QueryWrapper<SalesOrder>()
                        .eq("order_id", request.orderId()).or().eq("order_no", request.orderId())
                        .eq("status", "PENDING")
        );
        if (order == null) throw new IllegalArgumentException("仅待审核销售订单允许编辑");

        BigDecimal amount = request.details().stream()
                .map(d -> d.qty().multiply(d.price()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal costAmount = amount.multiply(new BigDecimal("0.90"));

        order.setCustomer(request.customerId());
        order.setSalesman(request.salesman());
        order.setWarehouse(request.warehouseId());
        order.setAmount(amount);
        order.setUnpaidAmount(amount);
        order.setCostAmount(costAmount);
        order.setLineType(request.lineType());
        orderService.updateById(order);

        orderDetailService.remove(new QueryWrapper<SalesOrderDetail>().eq("order_id", order.getOrderId()));
        for (SalesOrderDetailRequest d : request.details()) {
            BigDecimal lineAmount = d.qty().multiply(d.price());
            SalesOrderDetail detail = new SalesOrderDetail();
            detail.setDetailId("SOD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            detail.setOrderId(order.getOrderId());
            detail.setLineType(d.lineType());
            detail.setGoodsCode(d.goodsId());
            detail.setGoodsName(d.goodsName());
            detail.setUnitName(d.unitId());
            detail.setQty(d.qty());
            detail.setPrice(d.price());
            detail.setDiscountRate(d.discountRate());
            detail.setTaxRate(d.taxRate());
            detail.setAmount(lineAmount);
            detail.setCostPrice(d.price().multiply(new BigDecimal("0.90")));
            detail.setCostAmount(lineAmount.multiply(new BigDecimal("0.90")));
            orderDetailService.save(detail);
        }

        log("sales.order", "UPDATE", order.getOrderId(), "销售订单编辑");
        return ApiResponse.ok(Map.of("orderId", order.getOrderId(), "status", "PENDING", "amount", amount, "costAmount", costAmount));
    }

    @PostMapping("/order/audit")
    public ApiResponse<Map<String, Object>> auditOrder(@Valid @RequestBody AuditRequest request) {
        orderService.update(new UpdateWrapper<SalesOrder>()
                .eq("order_id", request.bizId()).or().eq("order_no", request.bizId())
                .ne("status", "DELETED")
                .set("status", "APPROVED")
                .set("audit_info", "系统管理员 " + LocalDateTime.now()));
        log("sales.order", "AUDIT", request.bizId(), "销售订单审核");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "APPROVED", "effect", "已锁定库存"));
    }

    @PostMapping("/order/reverse-audit")
    public ApiResponse<Map<String, Object>> reverseAuditOrder(@Valid @RequestBody AuditRequest request) {
        boolean updated = orderService.update(new UpdateWrapper<SalesOrder>()
                .eq("order_id", request.bizId()).or().eq("order_no", request.bizId())
                .eq("status", "APPROVED")
                .eq("outbound_status", "未出库")
                .set("status", "PENDING")
                .set("audit_info", null));
        if (!updated) throw new IllegalArgumentException("销售订单已出库或状态不允许反审核");
        log("sales.order", "REVERSE_AUDIT", request.bizId(), "销售订单反审核");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "PENDING", "effect", "已反审核并释放锁定库存"));
    }

    @PostMapping("/order/close")
    public ApiResponse<Map<String, Object>> closeOrder(@Valid @RequestBody AuditRequest request) {
        boolean updated = orderService.update(new UpdateWrapper<SalesOrder>()
                .eq("order_id", request.bizId()).or().eq("order_no", request.bizId())
                .in("status", List.of("PENDING", "APPROVED"))
                .set("status", "CLOSED"));
        if (!updated) throw new IllegalArgumentException("销售订单状态不允许关闭");
        log("sales.order", "CLOSE", request.bizId(), "销售订单关闭");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "CLOSED", "effect", "销售订单已关闭"));
    }

    @PostMapping("/order/delete")
    public ApiResponse<Map<String, Object>> deleteOrder(@Valid @RequestBody AuditRequest request) {
        boolean updated = orderService.update(new UpdateWrapper<SalesOrder>()
                .eq("order_id", request.bizId()).or().eq("order_no", request.bizId())
                .eq("status", "PENDING")
                .set("status", "DELETED"));
        if (!updated) throw new IllegalArgumentException("仅待审核销售订单允许删除");
        log("sales.order", "DELETE", request.bizId(), "销售订单删除");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "DELETED", "effect", "销售订单已删除"));
    }

    @PostMapping("/quick-order/create-and-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> createAndAuditQuickOrder(@Valid @RequestBody SalesOrderRequest request) {
        var createResult = createOrder(request);
        String orderNo = (String) createResult.data().get("orderNo");
        auditOrder(new AuditRequest(orderNo, "快速开单审核"));
        return ApiResponse.ok(Map.of("orderNo", orderNo, "status", "APPROVED", "effect", "快速开单已审核并锁库存"));
    }

    @PostMapping("/outbound/create")
    @Transactional
    public ApiResponse<Map<String, Object>> createOutbound(@Valid @RequestBody CreateOutboundRequest request) {
        SalesOrder order = orderService.getOne(
                new QueryWrapper<SalesOrder>()
                        .eq("order_no", request.orderNo())
                        .eq("status", "APPROVED")
                        .eq("outbound_status", "未出库")
        );
        if (order == null) {
            throw new IllegalArgumentException("销售订单不存在、未审核或已出库");
        }

        List<SalesOrderDetail> orderDetails = orderDetailService.list(
                new QueryWrapper<SalesOrderDetail>().eq("order_id", order.getOrderId())
        );

        String outboundId = "SOU" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String outboundNo = "SOU" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCostAmount = BigDecimal.ZERO;

        for (SalesOrderDetail d : orderDetails) {
            BigDecimal lineAmount = d.getQty().multiply(d.getPrice());
            BigDecimal costPrice = inventoryCostService.getCurrentCostPrice(d.getGoodsCode(), order.getWarehouse());
            BigDecimal costAmount = d.getQty().multiply(costPrice != null ? costPrice : BigDecimal.ZERO);

            SalesOutboundDetail detail = new SalesOutboundDetail();
            detail.setDetailId("SOD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            detail.setOutboundId(outboundId);
            detail.setGoodsCode(d.getGoodsCode());
            detail.setGoodsName(d.getGoodsName());
            detail.setWarehouse(order.getWarehouse());
            detail.setUnitName(d.getUnitName());
            detail.setQty(d.getQty());
            detail.setBatchNo("BATCH-" + LocalDate.now().toString().replace("-", ""));
            detail.setPrice(d.getPrice());
            detail.setAmount(lineAmount);
            detail.setCostPrice(costPrice);
            detail.setCostAmount(costAmount);
            outboundDetailService.save(detail);

            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(lineAmount);
            totalCostAmount = totalCostAmount.add(costAmount);
        }

        SalesOutbound outbound = new SalesOutbound();
        outbound.setOutboundId(outboundId);
        outbound.setOutboundNo(outboundNo);
        outbound.setSourceOrder(order.getOrderNo());
        outbound.setCustomer(order.getCustomer());
        outbound.setWarehouse(order.getWarehouse());
        outbound.setBillDate(LocalDate.now());
        outbound.setQty(totalQty);
        outbound.setAmount(totalAmount);
        outbound.setCostAmount(totalCostAmount);
        outbound.setStatus("PENDING");
        outbound.setStockUpdated(false);
        outbound.setReceiptGenerated(false);
        outbound.setCreatedAt(LocalDateTime.now());
        outboundService.save(outbound);

        log("sales.outbound", "CREATE", outboundNo, "销售出库单创建（来源订单：" + order.getOrderNo() + "）");
        return ApiResponse.ok(Map.of("outboundId", outboundId, "outboundNo", outboundNo, "status", "PENDING", "orderNo", order.getOrderNo()));
    }

    @PostMapping("/outbound/page")
    public ApiResponse<PageResult<SalesOutbound>> outboundPage(@RequestBody PageRequest request) {
        var page = outboundService.page(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(request.safePageNo(), request.safePageSize()),
                new QueryWrapper<SalesOutbound>().orderByDesc("outbound_no")
        );
        return ApiResponse.ok(new PageResult<>(page.getRecords(), (int) page.getCurrent(), (int) page.getSize(), page.getTotal(), Map.of()));
    }

    @PostMapping("/outbound/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditOutbound(@Valid @RequestBody AuditRequest request) {
        SalesOutbound outbound = outboundService.getOne(
                new QueryWrapper<SalesOutbound>()
                        .eq("outbound_id", request.bizId()).or().eq("outbound_no", request.bizId())
                        .eq("status", "PENDING")
                        .orderByDesc("outbound_no")
        );
        if (outbound == null) {
            outbound = outboundService.getOne(new QueryWrapper<SalesOutbound>().eq("status", "PENDING").orderByDesc("outbound_no"));
        }
        if (outbound == null) throw new IllegalArgumentException("没有可审核的销售出库单");

        List<SalesOutboundDetail> details = outboundDetailService.list(
                new QueryWrapper<SalesOutboundDetail>().eq("outbound_id", outbound.getOutboundId())
        );

        // 使用成本核算引擎扣减库存
        for (SalesOutboundDetail detail : details) {
            inventoryCostService.salesOutbound(
                    detail.getGoodsCode(),
                    detail.getGoodsName(),
                    detail.getWarehouse(),
                    detail.getBatchNo(),
                    detail.getQty(),
                    outbound.getOutboundNo()
            );
        }

        outbound.setStatus("APPROVED");
        outbound.setStockUpdated(true);
        outbound.setReceiptGenerated(true);
        outboundService.updateById(outbound);

        // 更新销售订单出库状态
        if (outbound.getSourceOrder() != null) {
            orderService.update(new UpdateWrapper<SalesOrder>()
                    .eq("order_no", outbound.getSourceOrder())
                    .set("outbound_status", "已出库"));
        }

        // 自动生成应收账款
        String salesman = null;
        if (outbound.getSourceOrder() != null) {
            SalesOrder sourceOrder = orderService.getOne(
                    new QueryWrapper<SalesOrder>().eq("order_no", outbound.getSourceOrder())
            );
            if (sourceOrder != null) {
                salesman = sourceOrder.getSalesman();
            }
        }
        String arId = "AR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String arNo = "AR" + System.currentTimeMillis();
        jdbcTemplate.update("""
                INSERT INTO fin_ar(ar_id, ar_no, source_bill, customer, salesman, ar_amount, received_amount, unreceived_amount, due_date, overdue_days, invoice_status, status)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY), 0, '未开票', 'UNVERIFIED')
                """, arId, arNo, outbound.getOutboundNo(), outbound.getCustomer(), salesman, outbound.getAmount(), outbound.getAmount());

        log("sales.outbound", "AUDIT", outbound.getOutboundNo(), "销售出库审核");
        return ApiResponse.ok(Map.of("outboundId", outbound.getOutboundId(), "status", "APPROVED", "effect", "已扣减库存并生成应收账款", "arNo", arNo));
    }

    @PostMapping("/receipt/page")
    public ApiResponse<PageResult<Map<String, Object>>> receiptPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT outbound_no receiptNo,
                       outbound_no sourceOutbound,
                       customer,
                       amount signedAmount,
                       CASE WHEN receipt_generated THEN '已生成' ELSE '未生成' END arStatus,
                       '全部签收' signStatus,
                       CASE WHEN receipt_generated THEN '已审核' ELSE '待审核' END status
                FROM sales_outbound
                WHERE status = 'APPROVED'
                ORDER BY outbound_no DESC
                """), request));
    }

    @PostMapping("/return/page")
    public ApiResponse<PageResult<Map<String, Object>>> returnPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no returnNo, object_name customer, reason returnReason, warehouse, amount,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='SALES_RETURN' ORDER BY bill_no DESC
                """), request));
    }

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), moduleCode, action, bizNo, detail);
    }

    public record SalesOrderRequest(@NotBlank String customerId, @NotBlank String warehouseId, String salesman, String lineType, @NotEmpty List<SalesOrderDetailRequest> details) {}
    public record SalesOrderUpdateRequest(@NotBlank String orderId, @NotBlank String customerId, @NotBlank String warehouseId, String salesman, String lineType, @NotEmpty List<SalesOrderDetailRequest> details) {}
    public record SalesOrderDetailRequest(@NotBlank String goodsId, String goodsName, @NotBlank String unitId, String lineType, String discountRate, String taxRate, @NotNull @Positive BigDecimal qty, @NotNull @Positive BigDecimal price) {}
    public record AuditRequest(@NotBlank String bizId, String remark) {}
    public record CreateOutboundRequest(@NotBlank String orderNo) {}
}
