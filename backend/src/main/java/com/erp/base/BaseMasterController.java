package com.erp.base;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/base/master")
public class BaseMasterController {
    private final JdbcTemplate jdbcTemplate;

    public BaseMasterController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/price-group/page")
    public ApiResponse<PageResult<Map<String, Object>>> priceGroupPage(@RequestBody PageRequest request) {
        return page(request, row("PRICE1", "批发价", "销售价格组", "默认客户等级价", "正常"));
    }

    @PostMapping("/customer/page")
    public ApiResponse<PageResult<Map<String, Object>>> customerPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT customer_code customerCode,
                       customer_name customerName,
                       channel_type channelType,
                       contact_name contactName,
                       mobile,
                       territory,
                       route_line routeLine,
                       salesman,
                       customer_level customerLevel,
                       account_period_type accountPeriodType,
                       cutoff_day cutoffDay,
                       payment_day paymentDay,
                       credit_limit creditLimit,
                       ar_balance arBalance,
                       overdue_amount overdueAmount,
                       invoice_title invoiceTitle,
                       tax_no taxNo,
                       CASE status WHEN 'NORMAL' THEN '正常' WHEN 'FROZEN' THEN '冻结' ELSE '停用' END status
                FROM base_customer
                ORDER BY customer_code
                """), request));
    }

    @PostMapping("/supplier/page")
    public ApiResponse<PageResult<Map<String, Object>>> supplierPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT supplier_code supplierCode,
                       supplier_name supplierName,
                       short_name shortName,
                       supplier_type supplierType,
                       contact_name contactName,
                       phone,
                       delivery_days deliveryDays,
                       settlement_method settlementMethod,
                       account_period_days accountPeriodDays,
                       default_buyer defaultBuyer,
                       default_receipt_account defaultReceiptAccount,
                       invoice_title invoiceTitle,
                       tax_no taxNo,
                       ap_balance apBalance,
                       CASE status WHEN 'NORMAL' THEN '正常' ELSE '停用' END status
                FROM base_supplier
                ORDER BY supplier_code
                """), request));
    }

    @PostMapping("/counterparty/page")
    public ApiResponse<PageResult<Map<String, Object>>> counterpartyPage(@RequestBody PageRequest request) {
        return page(request, row("WL001", "顺丰物流", "物流公司", "月结", "正常"));
    }

    @PostMapping("/fund-account/page")
    public ApiResponse<PageResult<Map<String, Object>>> fundAccountPage(@RequestBody PageRequest request) {
        return page(request, row("A001", "工行基本户", "银行账户", "50300.00", "正常"));
    }

    @PostMapping("/expense-type/page")
    public ApiResponse<PageResult<Map<String, Object>>> expenseTypePage(@RequestBody PageRequest request) {
        return page(request, row("FY001", "运费", "支出", "参与采购成本", "正常"));
    }

    @PostMapping("/territory/page")
    public ApiResponse<PageResult<Map<String, Object>>> territoryPage(@RequestBody PageRequest request) {
        return page(request, row("T001", "西湖区", "杭州", "客户230", "正常"));
    }

    @PostMapping("/route-line/page")
    public ApiResponse<PageResult<Map<String, Object>>> routeLinePage(@RequestBody PageRequest request) {
        return page(request, row("R001", "朝阳线", "王司机", "覆盖86客户", "正常"));
    }

    @PostMapping("/employee/page")
    public ApiResponse<PageResult<Map<String, Object>>> employeePage(@RequestBody PageRequest request) {
        return page(request, row("E001", "张三", "销售部", "业务员", "在职"));
    }

    @PostMapping("/department/page")
    public ApiResponse<PageResult<Map<String, Object>>> departmentPage(@RequestBody PageRequest request) {
        return page(request, row("D001", "销售部", "公司", "12人", "正常"));
    }

    @PostMapping("/owner/page")
    public ApiResponse<PageResult<Map<String, Object>>> ownerPage(@RequestBody PageRequest request) {
        return page(request, row("H001", "平台默认货主", "默认货主", "平台", "正常"));
    }

    @PostMapping("/save")
    public ApiResponse<Map<String, Object>> save(@RequestBody Map<String, Object> request) {
        String moduleCode = String.valueOf(request.getOrDefault("moduleCode", "base.master"));
        if ("customer".equals(moduleCode)) return saveCustomer(request);
        if ("supplier".equals(moduleCode)) return saveSupplier(request);
        return ApiResponse.ok(GenericResult.operation(moduleCode, "SAVE"));
    }

    @PostMapping("/stop")
    public ApiResponse<Map<String, Object>> stop(@RequestBody Map<String, Object> request) {
        String moduleCode = String.valueOf(request.getOrDefault("moduleCode", "base.master"));
        String bizId = String.valueOf(request.getOrDefault("bizId", ""));
        if ("customer".equals(moduleCode)) {
            jdbcTemplate.update("UPDATE base_customer SET status='STOPPED' WHERE customer_id=? OR customer_code=?", bizId, bizId);
        } else if ("supplier".equals(moduleCode)) {
            jdbcTemplate.update("UPDATE base_supplier SET status='STOPPED' WHERE supplier_id=? OR supplier_code=?", bizId, bizId);
        }
        return ApiResponse.ok(GenericResult.operation(moduleCode, "STOP"));
    }

    private ApiResponse<Map<String, Object>> saveCustomer(Map<String, Object> request) {
        String id = String.valueOf(request.getOrDefault("customerId", "CUS" + System.currentTimeMillis()));
        String code = String.valueOf(request.getOrDefault("customerCode", request.getOrDefault("code", id)));
        jdbcTemplate.update("""
                MERGE INTO base_customer KEY(customer_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NORMAL')
                """, id, code, request.getOrDefault("customerName", request.getOrDefault("name", "新客户")),
                request.getOrDefault("channelType", "零售商超"), request.getOrDefault("contactName", ""), request.getOrDefault("mobile", ""),
                request.getOrDefault("territory", ""), request.getOrDefault("routeLine", ""), request.getOrDefault("salesman", ""),
                request.getOrDefault("customerLevel", "普通"), request.getOrDefault("accountPeriodType", "现结"), request.getOrDefault("cutoffDay", ""),
                request.getOrDefault("paymentDay", ""), request.getOrDefault("creditLimit", 0), request.getOrDefault("arBalance", 0),
                request.getOrDefault("overdueAmount", 0), request.getOrDefault("invoiceTitle", ""), request.getOrDefault("taxNo", ""));
        return ApiResponse.ok(GenericResult.row("customerId", id, "customerCode", code, "success", true));
    }

    private ApiResponse<Map<String, Object>> saveSupplier(Map<String, Object> request) {
        String id = String.valueOf(request.getOrDefault("supplierId", "SUP" + System.currentTimeMillis()));
        String code = String.valueOf(request.getOrDefault("supplierCode", request.getOrDefault("code", id)));
        jdbcTemplate.update("""
                MERGE INTO base_supplier KEY(supplier_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NORMAL')
                """, id, code, request.getOrDefault("supplierName", request.getOrDefault("name", "新供应商")),
                request.getOrDefault("shortName", ""), request.getOrDefault("supplierType", "普通供应商"), request.getOrDefault("contactName", ""),
                request.getOrDefault("phone", ""), request.getOrDefault("deliveryDays", 0), request.getOrDefault("settlementMethod", "现结"),
                request.getOrDefault("accountPeriodDays", 0), request.getOrDefault("defaultBuyer", ""), request.getOrDefault("defaultReceiptAccount", ""),
                request.getOrDefault("invoiceTitle", ""), request.getOrDefault("taxNo", ""), request.getOrDefault("apBalance", 0));
        return ApiResponse.ok(GenericResult.row("supplierId", id, "supplierCode", code, "success", true));
    }

    private ApiResponse<PageResult<Map<String, Object>>> page(PageRequest request, Map<String, Object> row) {
        return ApiResponse.ok(PageResult.of(List.of(row), request));
    }

    private Map<String, Object> row(String code, String name, String type, String remark, String status) {
        return GenericResult.row("code", code, "name", name, "type", type, "remark", remark, "status", status);
    }
}
