package com.erp.report;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/report")
public class ReportController {
    private final JdbcTemplate jdbcTemplate;

    public ReportController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/dashboard/summary")
    public ApiResponse<Map<String, Object>> dashboardSummary() {
        Map<String, Object> sales = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(amount),0) salesAmount, COALESCE(SUM(unpaid_amount),0) unpaidAmount FROM sales_order");
        Map<String, Object> purchase = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(amount),0) purchaseAmount FROM pur_order");
        Map<String, Object> stock = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(stock_amount),0) stockAmount, COALESCE(SUM(available_qty),0) availableQty FROM inv_stock_balance");
        Map<String, Object> finance = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(unreceived_amount),0) arBalance FROM fin_ar");
        Map<String, Object> ap = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(unpaid_amount),0) apBalance FROM fin_ap");
        return ApiResponse.ok(GenericResult.row(
                "salesAmount", sales.get("SALESAMOUNT"),
                "purchaseAmount", purchase.get("PURCHASEAMOUNT"),
                "stockAmount", stock.get("STOCKAMOUNT"),
                "availableQty", stock.get("AVAILABLEQTY"),
                "arBalance", finance.get("ARBALANCE"),
                "apBalance", ap.get("APBALANCE"),
                "unpaidAmount", sales.get("UNPAIDAMOUNT")
        ));
    }

    @PostMapping("/sales/page")
    public ApiResponse<PageResult<Map<String, Object>>> salesReport(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_date billDate,
                       customer,
                       salesman,
                       warehouse,
                       amount salesAmount,
                       paid_amount paidAmount,
                       unpaid_amount unpaidAmount,
                       amount * 0.12 grossProfit,
                       status
                FROM sales_order
                ORDER BY bill_date DESC, order_no DESC
                """), request));
    }

    @PostMapping("/purchase/page")
    public ApiResponse<PageResult<Map<String, Object>>> purchaseReport(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_date billDate,
                       supplier,
                       buyer,
                       warehouse,
                       amount purchaseAmount,
                       inbound_amount inboundAmount,
                       payment_status paymentStatus,
                       status
                FROM pur_order
                ORDER BY bill_date DESC, order_no DESC
                """), request));
    }

    @PostMapping("/stock/page")
    public ApiResponse<PageResult<Map<String, Object>>> stockReport(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT goods_code goodsCode,
                       goods_name goodsName,
                       warehouse,
                       physical_qty physicalQty,
                       locked_qty lockedQty,
                       available_qty availableQty,
                       cost_price costPrice,
                       stock_amount stockAmount,
                       last_inout_time lastInoutTime
                FROM inv_stock_balance
                ORDER BY stock_amount DESC
                """), request));
    }

    @PostMapping("/finance/page")
    public ApiResponse<PageResult<Map<String, Object>>> financeReport(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT '应收' reportType,
                       customer objectName,
                       ar_amount amount,
                       received_amount verifiedAmount,
                       unreceived_amount balance,
                       due_date dueDate,
                       status
                FROM fin_ar
                UNION ALL
                SELECT '应付' reportType,
                       supplier objectName,
                       ap_amount amount,
                       paid_amount verifiedAmount,
                       unpaid_amount balance,
                       due_date dueDate,
                       status
                FROM fin_ap
                """), request));
    }

    @PostMapping("/export")
    public ApiResponse<Map<String, Object>> exportReport(@RequestBody Map<String, Object> request) {
        String taskId = "EXP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String taskNo = "EXP" + System.currentTimeMillis();
        String reportName = String.valueOf(request.getOrDefault("reportName", "报表导出"));
        String moduleCode = String.valueOf(request.getOrDefault("moduleCode", "report"));
        String filterText = String.valueOf(request.getOrDefault("filters", Map.of()));
        String fileName = reportName + "_" + taskNo + ".xlsx";
        jdbcTemplate.update("""
                INSERT INTO sys_export_task_runtime(task_id, task_no, report_name, module_code, filter_text, file_name, status, created_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, 'FINISHED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, taskId, taskNo, reportName, moduleCode, filterText, fileName);
        return ApiResponse.ok(GenericResult.row(
                "taskNo", taskNo,
                "status", "FINISHED",
                "fileName", fileName,
                "message", "报表导出任务已创建，请到导出中心下载"
        ));
    }
}
