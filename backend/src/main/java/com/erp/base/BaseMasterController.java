package com.erp.base;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.erp.base.entity.BaseCustomer;
import com.erp.base.entity.BaseSupplier;
import com.erp.base.service.BaseCustomerService;
import com.erp.base.service.BaseSupplierService;
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
import java.util.UUID;

@RestController
@RequestMapping("/base/master")
public class BaseMasterController {

    private final JdbcTemplate jdbcTemplate;
    private final BaseCustomerService customerService;
    private final BaseSupplierService supplierService;

    public BaseMasterController(JdbcTemplate jdbcTemplate, BaseCustomerService customerService, BaseSupplierService supplierService) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerService = customerService;
        this.supplierService = supplierService;
    }

    @PostMapping("/price-group/page")
    public ApiResponse<PageResult<Map<String, Object>>> priceGroupPage(@RequestBody PageRequest request) {
        return page(request, row("PRICE1", "批发价", "销售价格组", "默认客户等级价", "正常"));
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
        return updateMasterStatus(request, "STOPPED", "STOP", "停用基础资料");
    }

    @PostMapping("/delete")
    public ApiResponse<Map<String, Object>> delete(@RequestBody Map<String, Object> request) {
        String moduleCode = String.valueOf(request.getOrDefault("moduleCode", "base.master"));
        String bizId = String.valueOf(request.getOrDefault("bizId", ""));
        boolean removed = false;
        switch (moduleCode) {
            case "priceGroup":
                removed = priceGroupService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.erp.base.entity.BasePriceGroup>()
                        .eq("price_group_code", bizId).or().eq("price_group_id", bizId));
                break;
            case "territory":
                removed = territoryService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.erp.base.entity.BaseTerritory>()
                        .eq("territory_code", bizId).or().eq("territory_id", bizId));
                break;
            case "routeLine":
                removed = routeLineService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.erp.base.entity.BaseRouteLine>()
                        .eq("route_line_code", bizId).or().eq("route_line_id", bizId));
                break;
            case "employee":
                removed = employeeService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.erp.base.entity.BaseEmployee>()
                        .eq("employee_code", bizId).or().eq("employee_id", bizId));
                break;
            case "department":
                removed = departmentService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.erp.base.entity.BaseDepartment>()
                        .eq("department_code", bizId).or().eq("department_id", bizId));
                break;
            case "owner":
                removed = ownerService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.erp.base.entity.BaseOwner>()
                        .eq("owner_code", bizId).or().eq("owner_id", bizId));
                break;
            case "expenseType":
                removed = expenseTypeService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.erp.base.entity.BaseExpenseType>()
                        .eq("expense_type_code", bizId).or().eq("expense_type_id", bizId));
                break;
            case "counterparty":
                removed = counterpartyService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.erp.base.entity.BaseCounterparty>()
                        .eq("counterparty_code", bizId).or().eq("counterparty_id", bizId));
                break;
            case "fundAccount":
                removed = fundAccountService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.erp.base.entity.BaseFundAccount>()
                        .eq("fund_account_code", bizId).or().eq("fund_account_id", bizId));
                break;
        }
        if (!removed) throw new IllegalArgumentException("基础资料不存在或删除失败");
        log("base." + moduleCode, "DELETE", bizId, "删除基础资料");
        return ApiResponse.ok(GenericResult.operation(moduleCode, "DELETE"));
    }

    @PostMapping("/freeze")
    public ApiResponse<Map<String, Object>> freeze(@RequestBody Map<String, Object> request) {
        return updateMasterStatus(request, "FROZEN", "FREEZE", "冻结基础资料");
    }

    @PostMapping("/unfreeze")
    public ApiResponse<Map<String, Object>> unfreeze(@RequestBody Map<String, Object> request) {
        return updateMasterStatus(request, "NORMAL", "UNFREEZE", "解冻基础资料");
    }

    private ApiResponse<Map<String, Object>> updateMasterStatus(Map<String, Object> request, String status, String action, String detail) {
        String moduleCode = String.valueOf(request.getOrDefault("moduleCode", "base.master"));
        String bizId = String.valueOf(request.getOrDefault("bizId", ""));
        boolean updated = false;
        if ("customer".equals(moduleCode)) {
            updated = customerService.update(new UpdateWrapper<BaseCustomer>()
                    .eq("customer_id", bizId).or().eq("customer_code", bizId)
                    .set("status", status));
        } else if ("supplier".equals(moduleCode)) {
            updated = supplierService.update(new UpdateWrapper<BaseSupplier>()
                    .eq("supplier_id", bizId).or().eq("supplier_code", bizId)
                    .set("status", status));
        }
        if (!updated) throw new IllegalArgumentException("基础资料不存在，无法" + detail);
        log("base." + moduleCode, action, bizId, detail);
        return ApiResponse.ok(GenericResult.operation(moduleCode, action));
    }

    private ApiResponse<Map<String, Object>> saveCustomer(Map<String, Object> request) {
        String id = String.valueOf(request.getOrDefault("customerId", "CUS" + System.currentTimeMillis()));
        String code = String.valueOf(request.getOrDefault("customerCode", request.getOrDefault("code", id)));
        BaseCustomer existing = customerService.getOne(new QueryWrapper<BaseCustomer>().eq("customer_id", id).or().eq("customer_code", code));
        if (existing != null) {
            // 更新
            existing.setCustomerName((String) request.getOrDefault("customerName", request.getOrDefault("name", existing.getCustomerName())));
            existing.setChannelType((String) request.getOrDefault("channelType", existing.getChannelType()));
            existing.setContactName((String) request.getOrDefault("contactName", existing.getContactName()));
            existing.setMobile((String) request.getOrDefault("mobile", existing.getMobile()));
            existing.setTerritory((String) request.getOrDefault("territory", existing.getTerritory()));
            existing.setRouteLine((String) request.getOrDefault("routeLine", existing.getRouteLine()));
            existing.setSalesman((String) request.getOrDefault("salesman", existing.getSalesman()));
            existing.setCustomerLevel((String) request.getOrDefault("customerLevel", existing.getCustomerLevel()));
            existing.setAccountPeriodType((String) request.getOrDefault("accountPeriodType", existing.getAccountPeriodType()));
            existing.setCutoffDay((String) request.getOrDefault("cutoffDay", existing.getCutoffDay()));
            existing.setPaymentDay((String) request.getOrDefault("paymentDay", existing.getPaymentDay()));
            customerService.updateById(existing);
        } else {
            // 新建
            BaseCustomer entity = new BaseCustomer();
            entity.setCustomerId(id);
            entity.setCustomerCode(code);
            entity.setCustomerName((String) request.getOrDefault("customerName", request.getOrDefault("name", "新客户")));
            entity.setChannelType((String) request.getOrDefault("channelType", "零售商超"));
            entity.setContactName((String) request.getOrDefault("contactName", ""));
            entity.setMobile((String) request.getOrDefault("mobile", ""));
            entity.setTerritory((String) request.getOrDefault("territory", ""));
            entity.setRouteLine((String) request.getOrDefault("routeLine", ""));
            entity.setSalesman((String) request.getOrDefault("salesman", ""));
            entity.setCustomerLevel((String) request.getOrDefault("customerLevel", "普通"));
            entity.setAccountPeriodType((String) request.getOrDefault("accountPeriodType", "现结"));
            entity.setStatus("NORMAL");
            customerService.save(entity);
        }
        log("base.customer", "SAVE", code, "保存客户资料");
        return ApiResponse.ok(GenericResult.row("customerId", id, "customerCode", code, "success", true));
    }

    private ApiResponse<Map<String, Object>> saveSupplier(Map<String, Object> request) {
        String id = String.valueOf(request.getOrDefault("supplierId", "SUP" + System.currentTimeMillis()));
        String code = String.valueOf(request.getOrDefault("supplierCode", request.getOrDefault("code", id)));
        BaseSupplier existing = supplierService.getOne(new QueryWrapper<BaseSupplier>().eq("supplier_id", id).or().eq("supplier_code", code));
        if (existing != null) {
            existing.setSupplierName((String) request.getOrDefault("supplierName", request.getOrDefault("name", existing.getSupplierName())));
            existing.setShortName((String) request.getOrDefault("shortName", existing.getShortName()));
            existing.setSupplierType((String) request.getOrDefault("supplierType", existing.getSupplierType()));
            existing.setContactName((String) request.getOrDefault("contactName", existing.getContactName()));
            existing.setPhone((String) request.getOrDefault("phone", existing.getPhone()));
            existing.setSettlementMethod((String) request.getOrDefault("settlementMethod", existing.getSettlementMethod()));
            existing.setDefaultBuyer((String) request.getOrDefault("defaultBuyer", existing.getDefaultBuyer()));
            supplierService.updateById(existing);
        } else {
            BaseSupplier entity = new BaseSupplier();
            entity.setSupplierId(id);
            entity.setSupplierCode(code);
            entity.setSupplierName((String) request.getOrDefault("supplierName", request.getOrDefault("name", "新供应商")));
            entity.setShortName((String) request.getOrDefault("shortName", ""));
            entity.setSupplierType((String) request.getOrDefault("supplierType", "普通供应商"));
            entity.setContactName((String) request.getOrDefault("contactName", ""));
            entity.setPhone((String) request.getOrDefault("phone", ""));
            entity.setSettlementMethod((String) request.getOrDefault("settlementMethod", "现结"));
            entity.setStatus("NORMAL");
            supplierService.save(entity);
        }
        log("base.supplier", "SAVE", code, "保存供应商资料");
        return ApiResponse.ok(GenericResult.row("supplierId", id, "supplierCode", code, "success", true));
    }

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), moduleCode, action, bizNo, detail);
    }

    private ApiResponse<PageResult<Map<String, Object>>> page(PageRequest request, Map<String, Object> row) {
        return ApiResponse.ok(PageResult.of(List.of(row), request));
    }

    private Map<String, Object> row(String code, String name, String type, String remark, String status) {
        return GenericResult.row("code", code, "name", name, "type", type, "remark", remark, "status", status);
    }
}
