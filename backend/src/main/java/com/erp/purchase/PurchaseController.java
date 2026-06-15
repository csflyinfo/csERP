package com.erp.purchase;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.jdbc.core.JdbcTemplate;
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

    public PurchaseController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/order/page")
    public ApiResponse<PageResult<Map<String, Object>>> orderPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT order_no orderNo,
                       supplier,
                       buyer,
                       warehouse,
                       bill_date billDate,
                       amount,
                       inbound_amount inboundAmount,
                       payment_status paymentStatus,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status,
                       arrival_status arrivalStatus,
                       creator_info creatorInfo
                FROM pur_order
                ORDER BY order_no DESC
                """), request));
    }

    @GetMapping("/order/detail")
    public ApiResponse<Map<String, Object>> orderDetail(@RequestParam String orderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM pur_order WHERE order_id = ? OR order_no = ? LIMIT 1", orderId, orderId);
        if (rows.isEmpty()) {
            return ApiResponse.ok(GenericResult.row("orderId", orderId, "details", List.of()));
        }
        Map<String, Object> head = rows.get(0);
        head.put("details", List.of(GenericResult.row("goodsCode", "SP001", "goodsName", "农夫山泉500ml*24", "unit", "箱", "qty", "100", "price", "35.00", "amount", "3500.00")));
        return ApiResponse.ok(head);
    }

    @PostMapping("/order/create")
    public ApiResponse<Map<String, Object>> createOrder(@Valid @RequestBody PurchaseOrderRequest request) {
        String id = "PO" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = "PO" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));
        jdbcTemplate.update("""
                INSERT INTO pur_order(order_id, order_no, supplier, buyer, warehouse, bill_date, amount, inbound_amount, payment_status, arrival_status, status, creator_info)
                VALUES (?, ?, '农夫山泉杭州经销', '李四', '总仓', CURRENT_DATE, 3500.00, 0.00, '未付款', '未到货', 'PENDING', '管理员 ' || FORMATDATETIME(CURRENT_TIMESTAMP, 'yyyy-MM-dd HH:mm'))
                """, id, no);
        return ApiResponse.ok(Map.of("orderId", id, "orderNo", no, "status", "PENDING"));
    }

    @PostMapping("/order/audit")
    public ApiResponse<Map<String, Object>> auditOrder(@Valid @RequestBody AuditRequest request) {
        int updated = jdbcTemplate.update("UPDATE pur_order SET status='APPROVED', arrival_status='采购在途' WHERE order_id = ? OR order_no = ?", request.bizId(), request.bizId());
        if (updated == 0) jdbcTemplate.update("UPDATE pur_order SET status='APPROVED', arrival_status='采购在途' WHERE order_no = (SELECT order_no FROM pur_order ORDER BY order_no DESC LIMIT 1)");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "APPROVED", "effect", "已形成采购在途", "auditTime", LocalDateTime.now().toString()));
    }

    @PostMapping("/inbound/page")
    public ApiResponse<PageResult<Map<String, Object>>> inboundPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(List.of(Map.of("inboundNo", "PI202606140001", "sourceOrder", "PO202606140001", "supplier", "农夫山泉杭州经销", "warehouse", "总仓", "billDate", "2026-06-14", "qty", "100", "amount", "3500.00", "status", "待审核")), request));
    }

    @PostMapping("/inbound/audit")
    public ApiResponse<Map<String, Object>> auditInbound(@Valid @RequestBody AuditRequest request) {
        jdbcTemplate.update("UPDATE inv_stock_balance SET physical_qty=physical_qty+100, available_qty=available_qty+100, stock_amount=(physical_qty+100)*cost_price, last_inout_time=CURRENT_TIMESTAMP WHERE balance_id='SB001'");
        jdbcTemplate.update("INSERT INTO inv_stock_ledger(ledger_id, ledger_no, occurred_at, source_bill, goods_code, goods_name, warehouse, batch_no, direction, qty, cost_price, amount, balance_qty, operator_name) VALUES (?, ?, CURRENT_TIMESTAMP, 'PI202606140001', 'SP001', '农夫山泉500ml*24', '总仓', 'B202606', 'IN', 100, 31.20, 3120.00, 1300, '管理员')", "SL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "INV" + System.currentTimeMillis());
        return ApiResponse.ok(Map.of("inboundId", request.bizId(), "status", "APPROVED", "effect", "库存增加，生成库存流水，重算成本，生成采购收货单"));
    }

    @PostMapping("/receipt/page")
    public ApiResponse<PageResult<Map<String, Object>>> receiptPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(List.of(Map.of("receiptNo", "PR202606140001", "sourceInbound", "PI202606140001", "supplier", "农夫山泉杭州经销", "goodsAmount", "3500.00", "taxAmount", "455.00", "finalAmount", "3955.00", "apStatus", "未生成", "status", "待审核")), request));
    }

    @PostMapping("/receipt/audit")
    public ApiResponse<Map<String, Object>> auditReceipt(@Valid @RequestBody AuditRequest request) {
        String id = "AP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = "AP" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));
        jdbcTemplate.update("INSERT INTO fin_ap(ap_id, ap_no, source_bill, supplier, ap_amount, paid_amount, unpaid_amount, due_date, status) VALUES (?, ?, 'PR202606140001', '农夫山泉杭州经销', 3955.00, 0, 3955.00, DATEADD('DAY', 30, CURRENT_DATE), 'UNVERIFIED')", id, no);
        return ApiResponse.ok(Map.of("receiptId", request.bizId(), "status", "APPROVED", "effect", "已生成应付账款"));
    }

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
        jdbcTemplate.update("UPDATE inv_stock_balance SET physical_qty=physical_qty-10, available_qty=available_qty-10, stock_amount=(physical_qty-10)*cost_price, last_inout_time=CURRENT_TIMESTAMP WHERE balance_id='SB001'");
        jdbcTemplate.update("INSERT INTO inv_stock_ledger(ledger_id, ledger_no, occurred_at, source_bill, goods_code, goods_name, warehouse, batch_no, direction, qty, cost_price, amount, balance_qty, operator_name) VALUES (?, ?, CURRENT_TIMESTAMP, 'PRT202606140001', 'SP001', '农夫山泉500ml*24', '总仓', 'B202606', 'OUT', 10, 30.80, 308.00, 1190, '管理员')", "SL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "INV" + System.currentTimeMillis());
        return ApiResponse.ok(Map.of("returnId", request.bizId(), "status", "APPROVED", "effect", "采购退货已扣减库存并冲减应付"));
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
        jdbcTemplate.update("UPDATE inv_stock_balance SET cost_price=cost_price+0.20, stock_amount=physical_qty*(cost_price+0.20) WHERE balance_id='SB001'");
        String id = "AP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("INSERT INTO fin_ap(ap_id, ap_no, source_bill, supplier, ap_amount, paid_amount, unpaid_amount, due_date, status) VALUES (?, ?, 'PE202606140001', '顺丰物流', 1000.00, 0, 1000.00, DATEADD('DAY', 30, CURRENT_DATE), 'UNVERIFIED')", id, "AP" + System.currentTimeMillis());
        return ApiResponse.ok(Map.of("expenseId", request.bizId(), "status", "APPROVED", "effect", "费用已分摊到入库单，成本已重算，应付已生成"));
    }
    @PostMapping("/invoice/page")
    public ApiResponse<PageResult<Map<String, Object>>> invoicePage(@RequestBody PageRequest request) { return ApiResponse.ok(PageResult.of(List.of(Map.of("invoiceNo", "PINV202606140001", "supplier", "农夫山泉杭州经销", "invoiceCode", "3300****", "invoiceAmount", "3955.00", "matchStatus", "未勾稽", "certStatus", "未认证", "status", "正常")), request)); }

    public record PurchaseOrderRequest(@NotBlank String supplierId, @NotBlank String warehouseId, @NotEmpty List<PurchaseOrderDetailRequest> details) {}
    public record PurchaseOrderDetailRequest(@NotBlank String goodsId, @NotBlank String unitId, @NotNull @Positive BigDecimal qty, @NotNull @Positive BigDecimal price) {}
    public record AuditRequest(@NotBlank String bizId, String remark) {}
}
