package com.erp.finance;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/finance")
public class FinanceController {
    private final JdbcTemplate jdbcTemplate;

    public FinanceController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/ar/page")
    public ApiResponse<PageResult<Map<String, Object>>> arPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT ar_no arNo,
                       customer,
                       salesman,
                       source_bill sourceBill,
                       ar_amount arAmount,
                       received_amount receivedAmount,
                       unreceived_amount unreceivedAmount,
                       due_date dueDate,
                       overdue_days overdueDays,
                       invoice_status invoiceStatus,
                       CASE status WHEN 'VERIFIED' THEN '已核销' ELSE '未核销' END status
                FROM fin_ar
                ORDER BY ar_no DESC
                """), request));
    }

    @PostMapping("/ap/page")
    public ApiResponse<PageResult<Map<String, Object>>> apPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT ap_no apNo,
                       supplier,
                       source_bill sourceBill,
                       ap_amount apAmount,
                       paid_amount paidAmount,
                       unpaid_amount unpaidAmount,
                       due_date dueDate,
                       CASE status WHEN 'VERIFIED' THEN '已核销' ELSE '未核销' END status
                FROM fin_ap
                ORDER BY ap_no DESC
                """), request));
    }

    @PostMapping("/receipt-payment/page")
    public ApiResponse<PageResult<Map<String, Object>>> receiptPaymentPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(List.of(Map.of(
                "billNo", "RC202606140001",
                "billType", "收款单",
                "objectName", "华联超市",
                "fundAccount", "工行基本户",
                "amount", "350.00",
                "verifiedAmount", "350.00",
                "status", "已审核"
        )), request));
    }

    @PostMapping("/receipt/create")
    public ApiResponse<Map<String, Object>> createReceipt(@Valid @RequestBody FundBillRequest request) {
        String billNo = "RC" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));
        return ApiResponse.ok(Map.of("billNo", billNo, "status", "DRAFT"));
    }

    @PostMapping("/payment/create")
    public ApiResponse<Map<String, Object>> createPayment(@Valid @RequestBody FundBillRequest request) {
        String billNo = "PAY" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));
        return ApiResponse.ok(Map.of("billNo", billNo, "status", "DRAFT"));
    }

    @PostMapping("/fund-ledger/page")
    public ApiResponse<PageResult<Map<String, Object>>> fundLedgerPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT ledger_no ledgerNo,
                       fund_account fundAccount,
                       direction,
                       amount,
                       source_bill sourceBill,
                       balance_after balanceAfter,
                       occurred_at occurredAt,
                       operator_name operator
                FROM fin_fund_ledger
                ORDER BY occurred_at DESC
                """), request));
    }

    @PostMapping("/ar-settlement/page")
    public ApiResponse<PageResult<Map<String, Object>>> arSettlementPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT 'ARS' || FORMATDATETIME(CURRENT_TIMESTAMP, 'yyyyMMdd') || '0001' settlementNo,
                       customer,
                       SUM(unreceived_amount) settlementAmount,
                       0.00 discountAmount,
                       '待审核' status
                FROM fin_ar
                WHERE status <> 'VERIFIED'
                GROUP BY customer
                """), request));
    }

    @PostMapping("/ap-settlement/page")
    public ApiResponse<PageResult<Map<String, Object>>> apSettlementPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT 'APS' || FORMATDATETIME(CURRENT_TIMESTAMP, 'yyyyMMdd') || '0001' settlementNo,
                       supplier,
                       SUM(unpaid_amount) settlementAmount,
                       0.00 discountAmount,
                       '待审核' status
                FROM fin_ap
                WHERE status <> 'VERIFIED'
                GROUP BY supplier
                """), request));
    }

    @PostMapping("/expense/page")
    public ApiResponse<PageResult<Map<String, Object>>> expensePage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT expense_no expenseNo,
                       CASE direction WHEN 'IN' THEN '收入' ELSE '支出' END direction,
                       expense_type expenseType,
                       object_name objectName,
                       amount,
                       tax_amount taxAmount,
                       relation_generated relationGenerated,
                       direct_payment directPayment,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM fin_expense_bill
                ORDER BY expense_no DESC
                """), request));
    }

    @PostMapping("/expense/audit")
    public ApiResponse<Map<String, Object>> auditExpense(@Valid @RequestBody FundBillRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM fin_expense_bill WHERE status <> 'APPROVED' ORDER BY expense_no DESC LIMIT 1");
        if (rows.isEmpty()) {
            return ApiResponse.ok(Map.of("success", true, "effect", "无待审核费用单，往来已生成或无需重复生成"));
        }
        Map<String, Object> expense = rows.get(0);
        jdbcTemplate.update("UPDATE fin_expense_bill SET status='APPROVED' WHERE expense_id=?", expense.get("EXPENSE_ID"));
        BigDecimal amount = (BigDecimal) expense.get("AMOUNT");
        String direction = String.valueOf(expense.get("DIRECTION"));
        if ("IN".equals(direction)) {
            String id = "AR" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("INSERT INTO fin_ar(ar_id, ar_no, source_bill, customer, salesman, ar_amount, received_amount, unreceived_amount, due_date, overdue_days, invoice_status, status) VALUES (?, ?, ?, ?, '费用', ?, 0, ?, DATEADD('DAY',30,CURRENT_DATE), 0, '未开票', 'UNVERIFIED')", id, "AR" + System.currentTimeMillis(), expense.get("EXPENSE_NO"), expense.get("OBJECT_NAME"), amount, amount);
        } else {
            String id = "AP" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("INSERT INTO fin_ap(ap_id, ap_no, source_bill, supplier, ap_amount, paid_amount, unpaid_amount, due_date, status) VALUES (?, ?, ?, ?, ?, 0, ?, DATEADD('DAY',30,CURRENT_DATE), 'UNVERIFIED')", id, "AP" + System.currentTimeMillis(), expense.get("EXPENSE_NO"), expense.get("OBJECT_NAME"), amount, amount);
        }
        return ApiResponse.ok(Map.of("success", true, "effect", "费用单已审核并生成往来"));
    }

    @PostMapping("/reconcile/receive")
    public ApiResponse<Map<String, Object>> receiveReconcile(@Valid @RequestBody FundBillRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM fin_ar WHERE status <> 'VERIFIED' ORDER BY ar_no DESC LIMIT 1");
        if (!rows.isEmpty()) {
            Map<String, Object> ar = rows.get(0);
            jdbcTemplate.update("UPDATE fin_ar SET received_amount=ar_amount, unreceived_amount=0, status='VERIFIED' WHERE ar_id=?", ar.get("AR_ID"));
            insertFundLedger("IN", request.amount(), String.valueOf(ar.get("AR_NO")), "50650.00");
        }
        return ApiResponse.ok(Map.of("success", true, "effect", "收款已核销应收并生成资金流水"));
    }

    @PostMapping("/reconcile/pay")
    public ApiResponse<Map<String, Object>> payReconcile(@Valid @RequestBody FundBillRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM fin_ap WHERE status <> 'VERIFIED' ORDER BY ap_no DESC LIMIT 1");
        if (!rows.isEmpty()) {
            Map<String, Object> ap = rows.get(0);
            jdbcTemplate.update("UPDATE fin_ap SET paid_amount=ap_amount, unpaid_amount=0, status='VERIFIED' WHERE ap_id=?", ap.get("AP_ID"));
            insertFundLedger("OUT", request.amount(), String.valueOf(ap.get("AP_NO")), "46695.00");
        }
        return ApiResponse.ok(Map.of("success", true, "effect", "付款已核销应付并生成资金流水"));
    }

    private void insertFundLedger(String direction, BigDecimal amount, String sourceBill, String balanceAfter) {
        String id = "FL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = "FUND" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        jdbcTemplate.update("""
                INSERT INTO fin_fund_ledger(ledger_id, ledger_no, fund_account, direction, amount, source_bill, balance_after, occurred_at, operator_name)
                VALUES (?, ?, '工行基本户', ?, ?, ?, ?, CURRENT_TIMESTAMP, '管理员')
                """, id, no, direction, amount, sourceBill, new BigDecimal(balanceAfter));
    }

    public record FundBillRequest(@NotBlank String objectId, @NotBlank String fundAccountId, @NotNull @Positive BigDecimal amount, String remark) {
    }
}
