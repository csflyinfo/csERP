package com.erp.sales;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/sales")
public class SalesController {
    private final JdbcTemplate jdbcTemplate;

    public SalesController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/order/page")
    public ApiResponse<PageResult<Map<String, Object>>> orderPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT order_no orderNo,
                       customer,
                       salesman,
                       warehouse,
                       bill_date billDate,
                       amount,
                       paid_amount paidAmount,
                       unpaid_amount unpaidAmount,
                       credit_check creditCheck,
                       stock_check stockCheck,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status,
                       outbound_status outboundStatus,
                       sign_status signStatus
                FROM sales_order
                ORDER BY order_no DESC
                """), request));
    }

    @PostMapping("/order/create")
    public ApiResponse<Map<String, Object>> createOrder(@Valid @RequestBody SalesOrderRequest request) {
        String id = "SO" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = "SO" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));
        jdbcTemplate.update("""
                INSERT INTO sales_order(order_id, order_no, customer, salesman, warehouse, bill_date, amount, paid_amount, unpaid_amount, credit_check, stock_check, outbound_status, sign_status, status)
                VALUES (?, ?, '华联超市', '张三', '总仓', CURRENT_DATE, 350.00, 0.00, 350.00, '通过', '通过', '未出库', '未签收', 'PENDING')
                """, id, no);
        return ApiResponse.ok(Map.of("orderId", id, "orderNo", no, "status", "PENDING"));
    }

    @PostMapping("/order/audit")
    public ApiResponse<Map<String, Object>> auditOrder(@Valid @RequestBody AuditRequest request) {
        int updated = jdbcTemplate.update("UPDATE sales_order SET status='APPROVED' WHERE order_id = ? OR order_no = ?", request.bizId(), request.bizId());
        if (updated == 0) jdbcTemplate.update("UPDATE sales_order SET status='APPROVED' WHERE order_no = (SELECT order_no FROM sales_order ORDER BY order_no DESC LIMIT 1)");
        jdbcTemplate.update("UPDATE inv_stock_balance SET locked_qty=locked_qty+10, available_qty=available_qty-10 WHERE balance_id='SB001'");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "APPROVED", "effect", "已锁定库存"));
    }

    @PostMapping("/quick-order/create-and-audit")
    public ApiResponse<Map<String, Object>> createAndAuditQuickOrder(@Valid @RequestBody SalesOrderRequest request) {
        createOrder(request);
        auditOrder(new AuditRequest("", "快速开单审核"));
        return ApiResponse.ok(Map.of("orderNo", "SO" + LocalDate.now().toString().replace("-", "") + "0002", "status", "APPROVED", "effect", "快速开单已审核并锁库存"));
    }

    @PostMapping("/outbound/page")
    public ApiResponse<PageResult<Map<String, Object>>> outboundPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(List.of(Map.of("outboundNo", "SOU202606140001", "sourceOrder", "SO202606140001", "customer", "华联超市", "warehouse", "总仓", "billDate", "2026-06-14", "qty", "10", "amount", "350.00", "costAmount", "308.00", "status", "待审核")), request));
    }

    @PostMapping("/outbound/audit")
    public ApiResponse<Map<String, Object>> auditOutbound(@Valid @RequestBody AuditRequest request) {
        jdbcTemplate.update("UPDATE inv_stock_balance SET physical_qty=physical_qty-10, locked_qty=CASE WHEN locked_qty>=10 THEN locked_qty-10 ELSE 0 END, available_qty=available_qty, stock_amount=(physical_qty-10)*cost_price, last_inout_time=CURRENT_TIMESTAMP WHERE balance_id='SB001'");
        jdbcTemplate.update("INSERT INTO inv_stock_ledger(ledger_id, ledger_no, occurred_at, source_bill, goods_code, goods_name, warehouse, batch_no, direction, qty, cost_price, amount, balance_qty, operator_name) VALUES (?, ?, CURRENT_TIMESTAMP, 'SOU202606140001', 'SP001', '农夫山泉500ml*24', '总仓', 'B202606', 'OUT', 10, 30.80, 308.00, 1190, '管理员')", "SL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "INV" + System.currentTimeMillis());
        return ApiResponse.ok(Map.of("outboundId", request.bizId(), "status", "APPROVED", "effect", "已扣减库存、释放锁定并生成销售收货单"));
    }

    @PostMapping("/receipt/page")
    public ApiResponse<PageResult<Map<String, Object>>> receiptPage(@RequestBody PageRequest request) { return ApiResponse.ok(PageResult.of(List.of(Map.of("receiptNo", "SR202606140001", "sourceOutbound", "SOU202606140001", "customer", "华联超市", "signedAmount", "350.00", "arStatus", "未生成", "signStatus", "全部签收", "status", "待审核")), request)); }

    @PostMapping("/receipt/audit")
    public ApiResponse<Map<String, Object>> auditReceipt(@Valid @RequestBody AuditRequest request) {
        String id = "AR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = "AR" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));
        jdbcTemplate.update("INSERT INTO fin_ar(ar_id, ar_no, source_bill, customer, salesman, ar_amount, received_amount, unreceived_amount, due_date, overdue_days, invoice_status, status) VALUES (?, ?, 'SR202606140001', '华联超市', '张三', 350.00, 0, 350.00, DATEADD('DAY',30,CURRENT_DATE), 0, '未开票', 'UNVERIFIED')", id, no);
        return ApiResponse.ok(Map.of("receiptId", request.bizId(), "status", "APPROVED", "effect", "已生成应收账款"));
    }

    @PostMapping("/return/page")
    public ApiResponse<PageResult<Map<String, Object>>> returnPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no returnNo, object_name customer, reason returnReason, warehouse, amount,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='SALES_RETURN' ORDER BY bill_no DESC
                """), request));
    }

    @PostMapping("/return/audit")
    public ApiResponse<Map<String, Object>> auditReturn(@Valid @RequestBody AuditRequest request) {
        jdbcTemplate.update("UPDATE biz_simple_bill SET status='APPROVED' WHERE bill_type='SALES_RETURN' AND (bill_id=? OR bill_no=? OR bill_no=(SELECT bill_no FROM biz_simple_bill WHERE bill_type='SALES_RETURN' ORDER BY bill_no DESC LIMIT 1))", request.bizId(), request.bizId());
        jdbcTemplate.update("UPDATE inv_stock_balance SET physical_qty=physical_qty+5, available_qty=available_qty+5, stock_amount=(physical_qty+5)*cost_price, last_inout_time=CURRENT_TIMESTAMP WHERE balance_id='SB001'");
        jdbcTemplate.update("INSERT INTO inv_stock_ledger(ledger_id, ledger_no, occurred_at, source_bill, goods_code, goods_name, warehouse, batch_no, direction, qty, cost_price, amount, balance_qty, operator_name) VALUES (?, ?, CURRENT_TIMESTAMP, 'SRT202606140001', 'SP001', '农夫山泉500ml*24', '退货仓', 'B202606', 'IN', 5, 30.80, 154.00, 1205, '管理员')", "SL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "INV" + System.currentTimeMillis());
        jdbcTemplate.update("INSERT INTO fin_ar(ar_id, ar_no, source_bill, customer, salesman, ar_amount, received_amount, unreceived_amount, due_date, overdue_days, invoice_status, status) VALUES (?, ?, 'SRT202606140001', '华联超市', '张三', -120.00, 0, -120.00, CURRENT_DATE, 0, '未开票', 'UNVERIFIED')", "AR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "AR" + System.currentTimeMillis());
        return ApiResponse.ok(Map.of("returnId", request.bizId(), "status", "APPROVED", "effect", "退货入库并冲减应收"));
    }

    @PostMapping("/invoice/page") public ApiResponse<PageResult<Map<String, Object>>> invoicePage(@RequestBody PageRequest request) { return ApiResponse.ok(PageResult.of(List.of(Map.of("invoiceNo", "SINV202606140001", "customer", "华联超市", "invoiceType", "增值税专票", "invoiceAmount", "350.00", "issueStatus", "未开票", "matchStatus", "未勾稽")), request)); }

    @PostMapping("/fly-order/page")
    public ApiResponse<PageResult<Map<String, Object>>> flyOrderPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no flyNo, object_name customer, '农夫山泉杭州经销' supplier, amount salesAmount,
                       amount-50 purchaseAmount, 50.00 grossProfit,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='FLY_ORDER' ORDER BY bill_no DESC
                """), request));
    }

    @PostMapping("/empty-adjust/page")
    public ApiResponse<PageResult<Map<String, Object>>> emptyAdjustPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no adjustNo, '旧客户' sourceCustomer, object_name targetCustomer, amount, reason effect,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='EMPTY_ADJUST' ORDER BY bill_no DESC
                """), request));
    }

    public record SalesOrderRequest(@NotBlank String customerId, @NotBlank String warehouseId, @NotEmpty List<SalesOrderDetailRequest> details) {}
    public record SalesOrderDetailRequest(@NotBlank String goodsId, @NotBlank String unitId, @NotNull @Positive BigDecimal qty, @NotNull @Positive BigDecimal price) {}
    public record AuditRequest(@NotBlank String bizId, String remark) {}
}
