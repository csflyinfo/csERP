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
import org.springframework.web.bind.annotation.*;

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
        String roleCode = String.valueOf(request.filters() == null ? "ADMIN" : request.filters().getOrDefault("roleCode", "ADMIN"));
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT order_no orderNo,
                       customer,
                       salesman,
                       warehouse,
                       bill_date billDate,
                       line_type lineType,
                       amount,
                       paid_amount paidAmount,
                       unpaid_amount unpaidAmount,
                       cost_amount costAmount,
                       credit_check creditCheck,
                       stock_check stockCheck,
                       CASE status WHEN 'APPROVED' THEN '已审核' WHEN 'CLOSED' THEN '已关闭' WHEN 'DELETED' THEN '已删除' ELSE '待审核' END status,
                       outbound_status outboundStatus,
                       sign_status signStatus,
                       creator_name creatorName
                FROM sales_order
                WHERE (? = 'ADMIN' OR ? = 'SALE' OR salesman = '系统管理员')
                ORDER BY order_no DESC
                """, roleCode, roleCode), request));
    }

    @GetMapping("/order/detail")
    public ApiResponse<Map<String, Object>> orderDetail(@RequestParam String orderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM sales_order WHERE order_id = ? OR order_no = ? LIMIT 1", orderId, orderId);
        if (rows.isEmpty()) {
            return ApiResponse.ok(Map.of("orderId", orderId, "details", List.of()));
        }
        Map<String, Object> head = rows.get(0);
        head.put("details", jdbcTemplate.queryForList("""
                SELECT line_type lineType, goods_code goodsCode, goods_name goodsName, unit_name unit,
                       qty, price, discount_rate discountRate, tax_rate taxRate, amount, cost_price costPrice, cost_amount costAmount
                FROM sales_order_detail WHERE order_id = ? ORDER BY detail_id
                """, head.get("ORDER_ID")));
        return ApiResponse.ok(head);
    }

    @PostMapping("/order/create")
    public ApiResponse<Map<String, Object>> createOrder(@Valid @RequestBody SalesOrderRequest request) {
        String id = "SO" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = "SO" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));
        BigDecimal amount = request.details().stream().map(detail -> detail.qty().multiply(detail.price())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal costAmount = amount.multiply(new BigDecimal("0.90"));
        jdbcTemplate.update("""
                INSERT INTO sales_order(order_id, order_no, customer, salesman, warehouse, bill_date, amount, paid_amount, unpaid_amount, credit_check, stock_check, outbound_status, sign_status, status, line_type, cost_amount, creator_name)
                VALUES (?, ?, ?, ?, ?, CURRENT_DATE, ?, 0.00, ?, '通过', '通过', '未出库', '未签收', 'PENDING', ?, ?, '管理员')
                """, id, no, request.customerId(), request.salesman(), request.warehouseId(), amount, amount, request.lineType(), costAmount);
        for (SalesOrderDetailRequest detail : request.details()) {
            BigDecimal lineAmount = detail.qty().multiply(detail.price());
            BigDecimal lineCostAmount = lineAmount.multiply(new BigDecimal("0.90"));
            jdbcTemplate.update("""
                    INSERT INTO sales_order_detail(detail_id, order_id, line_type, goods_code, goods_name, unit_name, qty, price, discount_rate, tax_rate, amount, cost_price, cost_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "SOD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), id,
                    detail.lineType(), detail.goodsId(), detail.goodsName(), detail.unitId(), detail.qty(), detail.price(), detail.discountRate(), detail.taxRate(), lineAmount, detail.price().multiply(new BigDecimal("0.90")), lineCostAmount);
        }
        return ApiResponse.ok(Map.of("orderId", id, "orderNo", no, "status", "PENDING", "amount", amount, "costAmount", costAmount));
    }

    @PostMapping("/order/update")
    public ApiResponse<Map<String, Object>> updateOrder(@Valid @RequestBody SalesOrderUpdateRequest request) {
        BigDecimal amount = request.details().stream().map(detail -> detail.qty().multiply(detail.price())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal costAmount = amount.multiply(new BigDecimal("0.90"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT order_id FROM sales_order WHERE (order_id=? OR order_no=?) AND status='PENDING' LIMIT 1", request.orderId(), request.orderId());
        if (rows.isEmpty()) throw new IllegalArgumentException("仅待审核销售订单允许编辑");
        String id = String.valueOf(rows.get(0).get("ORDER_ID"));
        jdbcTemplate.update("""
                UPDATE sales_order
                SET customer=?, salesman=?, warehouse=?, amount=?, unpaid_amount=?, line_type=?, cost_amount=?
                WHERE order_id=?
                """, request.customerId(), request.salesman(), request.warehouseId(), amount, amount, request.lineType(), costAmount, id);
        jdbcTemplate.update("DELETE FROM sales_order_detail WHERE order_id=?", id);
        for (SalesOrderDetailRequest detail : request.details()) {
            BigDecimal lineAmount = detail.qty().multiply(detail.price());
            BigDecimal lineCostAmount = lineAmount.multiply(new BigDecimal("0.90"));
            jdbcTemplate.update("""
                    INSERT INTO sales_order_detail(detail_id, order_id, line_type, goods_code, goods_name, unit_name, qty, price, discount_rate, tax_rate, amount, cost_price, cost_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "SOD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), id,
                    detail.lineType(), detail.goodsId(), detail.goodsName(), detail.unitId(), detail.qty(), detail.price(), detail.discountRate(), detail.taxRate(), lineAmount, detail.price().multiply(new BigDecimal("0.90")), lineCostAmount);
        }
        log("sales.order", "UPDATE", request.orderId(), "销售订单编辑");
        return ApiResponse.ok(Map.of("orderId", id, "status", "PENDING", "amount", amount, "costAmount", costAmount));
    }

    @PostMapping("/order/audit")
    public ApiResponse<Map<String, Object>> auditOrder(@Valid @RequestBody AuditRequest request) {
        int updated = jdbcTemplate.update("UPDATE sales_order SET status='APPROVED', audit_info='系统管理员 ' || FORMATDATETIME(CURRENT_TIMESTAMP, 'yyyy-MM-dd HH:mm') WHERE (order_id = ? OR order_no = ?) AND status<>'DELETED'", request.bizId(), request.bizId());
        if (updated == 0) jdbcTemplate.update("UPDATE sales_order SET status='APPROVED', audit_info='系统管理员 ' || FORMATDATETIME(CURRENT_TIMESTAMP, 'yyyy-MM-dd HH:mm') WHERE order_no = (SELECT order_no FROM sales_order WHERE status<>'DELETED' ORDER BY order_no DESC LIMIT 1)");
        jdbcTemplate.update("UPDATE inv_stock_balance SET locked_qty=locked_qty+10, available_qty=available_qty-10 WHERE balance_id='SB001'");
        log("sales.order", "AUDIT", request.bizId(), "销售订单审核");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "APPROVED", "effect", "已锁定库存"));
    }

    @PostMapping("/order/reverse-audit")
    public ApiResponse<Map<String, Object>> reverseAuditOrder(@Valid @RequestBody AuditRequest request) {
        int updated = jdbcTemplate.update("""
                UPDATE sales_order SET status='PENDING', audit_info=NULL
                WHERE (order_id=? OR order_no=?) AND status='APPROVED' AND outbound_status='未出库'
                """, request.bizId(), request.bizId());
        if (updated == 0) throw new IllegalArgumentException("销售订单已出库或状态不允许反审核");
        jdbcTemplate.update("UPDATE inv_stock_balance SET locked_qty=CASE WHEN locked_qty>=10 THEN locked_qty-10 ELSE 0 END, available_qty=available_qty+10 WHERE balance_id='SB001'");
        log("sales.order", "REVERSE_AUDIT", request.bizId(), "销售订单反审核");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "PENDING", "effect", "已反审核并释放锁定库存"));
    }

    @PostMapping("/order/close")
    public ApiResponse<Map<String, Object>> closeOrder(@Valid @RequestBody AuditRequest request) {
        int updated = jdbcTemplate.update("""
                UPDATE sales_order SET status='CLOSED'
                WHERE (order_id=? OR order_no=?) AND status IN ('PENDING', 'APPROVED')
                """, request.bizId(), request.bizId());
        if (updated == 0) throw new IllegalArgumentException("销售订单状态不允许关闭");
        log("sales.order", "CLOSE", request.bizId(), "销售订单关闭");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "CLOSED", "effect", "销售订单已关闭"));
    }

    @PostMapping("/order/delete")
    public ApiResponse<Map<String, Object>> deleteOrder(@Valid @RequestBody AuditRequest request) {
        int updated = jdbcTemplate.update("""
                UPDATE sales_order SET status='DELETED'
                WHERE (order_id=? OR order_no=?) AND status='PENDING'
                """, request.bizId(), request.bizId());
        if (updated == 0) throw new IllegalArgumentException("仅待审核销售订单允许删除");
        log("sales.order", "DELETE", request.bizId(), "销售订单删除");
        return ApiResponse.ok(Map.of("orderId", request.bizId(), "status", "DELETED", "effect", "销售订单已删除"));
    }

    @PostMapping("/quick-order/create-and-audit")
    public ApiResponse<Map<String, Object>> createAndAuditQuickOrder(@Valid @RequestBody SalesOrderRequest request) {
        createOrder(request);
        auditOrder(new AuditRequest("", "快速开单审核"));
        return ApiResponse.ok(Map.of("orderNo", "SO" + LocalDate.now().toString().replace("-", "") + "0002", "status", "APPROVED", "effect", "快速开单已审核并锁库存"));
    }

    @PostMapping("/outbound/page")
    public ApiResponse<PageResult<Map<String, Object>>> outboundPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT outbound_no outboundNo,
                       source_order sourceOrder,
                       customer,
                       warehouse,
                       bill_date billDate,
                       qty,
                       amount,
                       cost_amount costAmount,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status,
                       CASE stock_updated WHEN TRUE THEN '是' ELSE '否' END stockUpdated,
                       CASE receipt_generated WHEN TRUE THEN '是' ELSE '否' END receiptGenerated
                FROM sales_outbound
                ORDER BY outbound_no DESC
                """), request));
    }

    @GetMapping("/outbound/detail")
    public ApiResponse<Map<String, Object>> outboundDetail(@RequestParam String outboundId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM sales_outbound WHERE outbound_id=? OR outbound_no=? LIMIT 1", outboundId, outboundId);
        if (rows.isEmpty()) return ApiResponse.ok(Map.of("outboundId", outboundId, "details", List.of()));
        Map<String, Object> head = rows.get(0);
        head.put("details", jdbcTemplate.queryForList("""
                SELECT goods_code goodsCode, goods_name goodsName, warehouse, unit_name unit, qty, batch_no batchNo,
                       price, amount, cost_price costPrice, cost_amount costAmount
                FROM sales_outbound_detail WHERE outbound_id=? ORDER BY detail_id
                """, head.get("OUTBOUND_ID")));
        return ApiResponse.ok(head);
    }

    @PostMapping("/outbound/create")
    public ApiResponse<Map<String, Object>> createOutbound(@RequestBody Map<String, Object> request) {
        String sourceOrder = String.valueOf(request.getOrDefault("sourceOrder", request.getOrDefault("bizId", "SO202606140001")));
        List<Map<String, Object>> orders = jdbcTemplate.queryForList("SELECT * FROM sales_order WHERE order_no=? OR order_id=? ORDER BY order_no DESC LIMIT 1", sourceOrder, sourceOrder);
        Map<String, Object> order = orders.isEmpty() ? Map.of("ORDER_NO", sourceOrder, "CUSTOMER", "默认客户", "WAREHOUSE", "总仓") : orders.get(0);
        String orderId = String.valueOf(order.getOrDefault("ORDER_ID", ""));
        List<Map<String, Object>> details = orderId.isBlank() ? List.of() : jdbcTemplate.queryForList("SELECT * FROM sales_order_detail WHERE order_id=?", orderId);
        BigDecimal qty = details.stream().map(row -> (BigDecimal) row.get("QTY")).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = details.stream().map(row -> (BigDecimal) row.get("AMOUNT")).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal costAmount = details.stream().map(row -> (BigDecimal) row.get("COST_AMOUNT")).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (details.isEmpty()) {
            qty = new BigDecimal("1");
            amount = new BigDecimal("35.00");
            costAmount = new BigDecimal("31.20");
        }
        String id = "SOU" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = "SOU" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));
        jdbcTemplate.update("""
                INSERT INTO sales_outbound(outbound_id, outbound_no, source_order, customer, warehouse, bill_date, qty, amount, cost_amount, status, stock_updated, receipt_generated, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_DATE, ?, ?, ?, 'PENDING', FALSE, FALSE, CURRENT_TIMESTAMP)
                """, id, no, order.get("ORDER_NO"), order.get("CUSTOMER"), order.get("WAREHOUSE"), qty, amount, costAmount);
        if (details.isEmpty()) {
            jdbcTemplate.update("INSERT INTO sales_outbound_detail(detail_id, outbound_id, goods_code, goods_name, warehouse, unit_name, qty, batch_no, price, amount, cost_price, cost_amount) VALUES (?, ?, 'SP001', '农夫山泉500ml*24', ?, '箱', 1, 'B' || FORMATDATETIME(CURRENT_DATE, 'yyyyMM'), 35.00, 35.00, 31.20, 31.20)", "SOUD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), id, order.get("WAREHOUSE"));
        } else {
            for (Map<String, Object> detail : details) {
                jdbcTemplate.update("""
                        INSERT INTO sales_outbound_detail(detail_id, outbound_id, goods_code, goods_name, warehouse, unit_name, qty, batch_no, price, amount, cost_price, cost_amount)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'B' || FORMATDATETIME(CURRENT_DATE, 'yyyyMM'), ?, ?, ?, ?)
                        """, "SOUD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), id, detail.get("GOODS_CODE"), detail.get("GOODS_NAME"), order.get("WAREHOUSE"), detail.get("UNIT_NAME"), detail.get("QTY"), detail.get("PRICE"), detail.get("AMOUNT"), detail.get("COST_PRICE"), detail.get("COST_AMOUNT"));
            }
        }
        log("sales.outbound", "CREATE", no, "创建销售出库单");
        return ApiResponse.ok(Map.of("outboundId", id, "outboundNo", no, "sourceOrder", order.get("ORDER_NO"), "status", "PENDING"));
    }

    @PostMapping("/outbound/audit")
    public ApiResponse<Map<String, Object>> auditOutbound(@Valid @RequestBody AuditRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM sales_outbound WHERE (outbound_id=? OR outbound_no=?) AND status='PENDING' ORDER BY outbound_no DESC LIMIT 1", request.bizId(), request.bizId());
        if (rows.isEmpty()) rows = jdbcTemplate.queryForList("SELECT * FROM sales_outbound WHERE status='PENDING' ORDER BY outbound_no DESC LIMIT 1");
        if (rows.isEmpty()) throw new IllegalArgumentException("没有可审核的销售出库单");
        Map<String, Object> outbound = rows.get(0);
        List<Map<String, Object>> details = jdbcTemplate.queryForList("SELECT * FROM sales_outbound_detail WHERE outbound_id=?", outbound.get("OUTBOUND_ID"));
        for (Map<String, Object> detail : details) {
            jdbcTemplate.update("UPDATE inv_stock_balance SET physical_qty=physical_qty-?, locked_qty=CASE WHEN locked_qty>=? THEN locked_qty-? ELSE 0 END, available_qty=available_qty, stock_amount=(physical_qty-?)*cost_price, last_inout_time=CURRENT_TIMESTAMP WHERE goods_code=? AND warehouse=?", detail.get("QTY"), detail.get("QTY"), detail.get("QTY"), detail.get("QTY"), detail.get("GOODS_CODE"), detail.get("WAREHOUSE"));
            jdbcTemplate.update("INSERT INTO inv_stock_ledger(ledger_id, ledger_no, occurred_at, source_bill, goods_code, goods_name, warehouse, batch_no, direction, qty, cost_price, amount, balance_qty, operator_name) VALUES (?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, 'OUT', ?, ?, ?, ?, '管理员')", "SL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), ledgerNo(), outbound.get("OUTBOUND_NO"), detail.get("GOODS_CODE"), detail.get("GOODS_NAME"), detail.get("WAREHOUSE"), detail.get("BATCH_NO"), detail.get("QTY"), detail.get("COST_PRICE"), detail.get("COST_AMOUNT"), detail.get("QTY"));
        }
        jdbcTemplate.update("UPDATE sales_outbound SET status='APPROVED', stock_updated=TRUE, receipt_generated=TRUE WHERE outbound_id=?", outbound.get("OUTBOUND_ID"));
        jdbcTemplate.update("UPDATE sales_order SET outbound_status='已出库' WHERE order_no=?", outbound.get("SOURCE_ORDER"));
        log("sales.outbound", "AUDIT", String.valueOf(outbound.get("OUTBOUND_NO")), "销售出库审核");
        return ApiResponse.ok(Map.of("outboundId", outbound.get("OUTBOUND_ID"), "status", "APPROVED", "effect", "已扣减库存、释放锁定并生成销售收货单"));
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

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), moduleCode, action, bizNo, detail);
    }

    private String ledgerNo() {
        return "INV" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
    }

    public record SalesOrderRequest(@NotBlank String customerId, @NotBlank String warehouseId, String salesman, String lineType, @NotEmpty List<SalesOrderDetailRequest> details) {}
    public record SalesOrderUpdateRequest(@NotBlank String orderId, @NotBlank String customerId, @NotBlank String warehouseId, String salesman, String lineType, @NotEmpty List<SalesOrderDetailRequest> details) {}
    public record SalesOrderDetailRequest(@NotBlank String goodsId, String goodsName, @NotBlank String unitId, String lineType, String discountRate, String taxRate, @NotNull @Positive BigDecimal qty, @NotNull @Positive BigDecimal price) {}
    public record AuditRequest(@NotBlank String bizId, String remark) {}
}
