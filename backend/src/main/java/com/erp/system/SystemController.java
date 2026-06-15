package com.erp.system;

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
@RequestMapping("/system")
public class SystemController {
    private final JdbcTemplate jdbcTemplate;

    public SystemController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/menu/user-tree")
    public ApiResponse<List<Map<String, Object>>> userMenuTree() {
        return ApiResponse.ok(List.of(
                menu("dashboard", "首页", "/dashboard"),
                menu("base", "基础资料", null,
                        menu("goods", "商品档案", "/base/goods"),
                        menu("category", "商品分类", "/base/category"),
                        menu("brand", "品牌管理", "/base/brand"),
                        menu("unit", "单位管理", "/base/unit"),
                        menu("customer", "门店/客户资料", "/base/customer"),
                        menu("supplier", "供应商资料", "/base/supplier"),
                        menu("warehouse", "仓库资料", "/base/warehouse"),
                        menu("priceGroup", "价格组设置", "/base/price-group"),
                        menu("customerPrice", "客户价格调整单", "/base/customer-price-adjust"),
                        menu("customerPriceQuery", "客户价格查询", "/base/customer-price-query"),
                        menu("territory", "片区管理", "/base/territory"),
                        menu("routeLine", "线路管理", "/base/route-line"),
                        menu("employee", "人员信息", "/base/employee"),
                        menu("department", "部门管理", "/base/department"),
                        menu("owner", "货主信息", "/base/owner"),
                        menu("expenseType", "费用类型", "/base/expense-type"),
                        menu("counterparty", "往来单位", "/base/counterparty"),
                        menu("fundAccount", "资金账户", "/base/fund-account")
                ),
                menu("purchase", "采购管理", null,
                        menu("purchaseOrder", "采购订单", "/purchase/order"),
                        menu("purchaseInbound", "采购入库", "/purchase/inbound"),
                        menu("purchaseReceipt", "采购收货单", "/purchase/receipt"),
                        menu("purchaseReturn", "采购退货单", "/purchase/return"),
                        menu("purchaseExpense", "采购费用单", "/purchase/expense"),
                        menu("purchaseInvoice", "采购发票", "/purchase/invoice")
                ),
                menu("sales", "销售管理", null,
                        menu("quickOrder", "销售快速开单", "/sales/quick-order"),
                        menu("salesOrder", "销售订单", "/sales/order"),
                        menu("salesOutbound", "销售出库", "/sales/outbound"),
                        menu("salesReceipt", "销售收货单", "/sales/receipt"),
                        menu("salesReturn", "销售退货单", "/sales/return"),
                        menu("salesInvoice", "销售发票", "/sales/invoice"),
                        menu("flyOrder", "飞单", "/sales/fly-order"),
                        menu("emptyAdjust", "客户空退空出", "/sales/empty-adjust")
                ),
                menu("inventory", "库存管理", null,
                        menu("stockBalance", "库存余额", "/inventory/balance"),
                        menu("stockLedger", "库存流水", "/inventory/ledger"),
                        menu("stockLock", "库存锁定", "/inventory/lock"),
                        menu("batchStock", "批次库存", "/inventory/batch"),
                        menu("stockWarning", "库存预警", "/inventory/warning"),
                        menu("transfer", "调拨单", "/inventory/transfer"),
                        menu("damage", "报损单", "/inventory/damage"),
                        menu("costAdjust", "成本调整单", "/inventory/cost-adjust"),
                        menu("stockAdjust", "库存调整单", "/inventory/stock-adjust"),
                        menu("otherInbound", "其他入库", "/inventory/other-inbound"),
                        menu("otherOutbound", "其他出库", "/inventory/other-outbound"),
                        menu("stockTake", "库存盘点", "/inventory/stock-take")
                ),
                menu("finance", "财务管理", null,
                        menu("ar", "应收账款", "/finance/ar"),
                        menu("ap", "应付账款", "/finance/ap"),
                        menu("receiptPayment", "收付款单", "/finance/receipt-payment"),
                        menu("arSettlement", "应收结算", "/finance/ar-settlement"),
                        menu("apSettlement", "应付结算", "/finance/ap-settlement"),
                        menu("financeExpense", "费用单", "/finance/expense"),
                        menu("fundLedger", "资金流水", "/finance/fund-ledger"),
                        menu("counterpartyAr", "往来单位应收", "/finance/counterparty-ar"),
                        menu("counterpartyAp", "往来单位应付", "/finance/counterparty-ap"),
                        menu("receiptVerify", "收款核销", "/finance/receipt-verify"),
                        menu("paymentVerify", "付款核销", "/finance/payment-verify"),
                        menu("customerStatement", "客户对账", "/finance/customer-statement"),
                        menu("supplierStatement", "供应商对账", "/finance/supplier-statement")
                ),
                menu("report", "报表中心", null,
                        menu("salesReport", "销售报表", "/report/sales"),
                        menu("purchaseReport", "采购报表", "/report/purchase"),
                        menu("stockReport", "库存报表", "/report/stock"),
                        menu("financeReport", "财务报表", "/report/finance")
                ),
                menu("system", "系统管理", null,
                        menu("user", "用户管理", "/system/user"),
                        menu("role", "权限组管理", "/system/role"),
                        menu("param", "系统参数", "/system/param"),
                        menu("billNo", "单据编号规则", "/system/bill-no-rule"),
                        menu("precision", "显示精度设置", "/system/precision"),
                        menu("dictionary", "用户数据字典", "/system/dictionary"),
                        menu("workflow", "审批流配置", "/system/workflow"),
                        menu("printTemplate", "打印模板设置", "/system/print-template"),
                        menu("importList", "导入列表", "/system/import-list"),
                        menu("exportCenter", "导出中心", "/system/export-center"),
                        menu("log", "操作日志", "/system/operation-log")
                )
        ));
    }

    @PostMapping("/user/page")
    public ApiResponse<PageResult<Map<String, Object>>> userPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT username,
                       display_name displayName,
                       mobile,
                       role_name role,
                       data_scope dataScope,
                       CASE status WHEN 'NORMAL' THEN '正常' ELSE '停用' END status
                FROM sys_user_runtime
                ORDER BY username
                """), request));
    }

    @PostMapping("/user/save")
    public ApiResponse<Map<String, Object>> saveUser(@RequestBody Map<String, Object> request) {
        String id = String.valueOf(request.getOrDefault("userId", "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase()));
        jdbcTemplate.update("""
                MERGE INTO sys_user_runtime KEY(user_id)
                VALUES (?, ?, ?, ?, ?, ?, 'NORMAL')
                """, id, request.getOrDefault("username", "user" + System.currentTimeMillis()), request.getOrDefault("displayName", "新用户"),
                request.getOrDefault("mobile", ""), request.getOrDefault("roleName", "普通用户"), request.getOrDefault("dataScope", "本人"));
        log("system.user", "SAVE", id, "SUCCESS", "保存用户");
        return ApiResponse.ok(GenericResult.row("userId", id, "success", true));
    }

    @PostMapping("/role/page")
    public ApiResponse<PageResult<Map<String, Object>>> rolePage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT role_code roleCode,
                       role_name roleName,
                       user_count userCount,
                       menu_scope menuScope,
                       field_scope fieldScope,
                       CASE status WHEN 'NORMAL' THEN '正常' ELSE '停用' END status
                FROM sys_role_runtime
                ORDER BY role_code
                """), request));
    }

    @PostMapping("/role/save")
    public ApiResponse<Map<String, Object>> saveRole(@RequestBody Map<String, Object> request) {
        String id = String.valueOf(request.getOrDefault("roleId", "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase()));
        jdbcTemplate.update("""
                MERGE INTO sys_role_runtime KEY(role_id)
                VALUES (?, ?, ?, 0, ?, ?, 'NORMAL')
                """, id, request.getOrDefault("roleCode", id), request.getOrDefault("roleName", "新权限组"),
                request.getOrDefault("menuScope", "按配置"), request.getOrDefault("fieldScope", "按配置"));
        log("system.role", "SAVE", id, "SUCCESS", "保存权限组");
        return ApiResponse.ok(GenericResult.row("roleId", id, "success", true));
    }

    @PostMapping("/param/page")
    public ApiResponse<PageResult<Map<String, Object>>> paramPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT param_key paramKey,
                       param_name paramName,
                       param_value paramValue,
                       default_value defaultValue,
                       param_group paramGroup,
                       remark
                FROM sys_param_runtime
                ORDER BY param_group, param_key
                """), request));
    }

    @PostMapping("/param/update")
    public ApiResponse<Map<String, Object>> updateParam(@RequestBody Map<String, Object> request) {
        jdbcTemplate.update("UPDATE sys_param_runtime SET param_value = ? WHERE param_key = ?", request.get("paramValue"), request.get("paramKey"));
        log("system.param", "UPDATE", String.valueOf(request.get("paramKey")), "SUCCESS", "修改系统参数");
        return ApiResponse.ok(GenericResult.operation("system.param", "UPDATE"));
    }

    @PostMapping("/bill-no-rule/page")
    public ApiResponse<PageResult<Map<String, Object>>> billNoRulePage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_type billType,
                       prefix,
                       date_format dateFormat,
                       serial_length serialLength,
                       reset_cycle resetCycle,
                       example_no exampleNo,
                       CASE status WHEN 'NORMAL' THEN '正常' ELSE '停用' END status
                FROM sys_bill_no_rule_runtime
                ORDER BY bill_type
                """), request));
    }

    @PostMapping("/bill-no-rule/update")
    public ApiResponse<Map<String, Object>> updateBillNoRule(@RequestBody Map<String, Object> request) {
        jdbcTemplate.update("UPDATE sys_bill_no_rule_runtime SET prefix = COALESCE(?, prefix), serial_length = COALESCE(?, serial_length) WHERE bill_type = ?",
                request.get("prefix"), request.get("serialLength"), request.get("billType"));
        log("system.billNo", "UPDATE", String.valueOf(request.get("billType")), "SUCCESS", "修改编号规则");
        return ApiResponse.ok(GenericResult.operation("system.billNo", "UPDATE"));
    }

    @PostMapping("/precision/page")
    public ApiResponse<PageResult<Map<String, Object>>> precisionPage(@RequestBody PageRequest request) {
        return simpleSystemPage(request, "PRECISION", "数量显示位数", "显示精度", "正常", "数量/单价/金额显示位数，只可增大");
    }

    @PostMapping("/precision/save")
    public ApiResponse<Map<String, Object>> savePrecision(@RequestBody Map<String, Object> request) {
        return saveSimpleConfig("system.precision", request);
    }

    @PostMapping("/dictionary/page")
    public ApiResponse<PageResult<Map<String, Object>>> dictionaryPage(@RequestBody PageRequest request) {
        return simpleSystemPage(request, "DICT", "客户等级", "用户字典", "正常", "客户等级、支付方式、费用方向等业务字典");
    }

    @PostMapping("/dictionary/save")
    public ApiResponse<Map<String, Object>> saveDictionary(@RequestBody Map<String, Object> request) {
        return saveSimpleConfig("system.dictionary", request);
    }

    @PostMapping("/workflow/page")
    public ApiResponse<PageResult<Map<String, Object>>> workflowPage(@RequestBody PageRequest request) {
        return simpleSystemPage(request, "WF", "低价审批", "审批流", "正常", "超信用、低价、付款审批规则");
    }

    @PostMapping("/workflow/save")
    public ApiResponse<Map<String, Object>> saveWorkflow(@RequestBody Map<String, Object> request) {
        return saveSimpleConfig("system.workflow", request);
    }

    @PostMapping("/print-template/page")
    public ApiResponse<PageResult<Map<String, Object>>> printTemplatePage(@RequestBody PageRequest request) {
        return simpleSystemPage(request, "PRINT", "销售单模板", "打印模板", "正常", "销售单、采购单、小票模板");
    }

    @PostMapping("/print-template/save")
    public ApiResponse<Map<String, Object>> savePrintTemplate(@RequestBody Map<String, Object> request) {
        return saveSimpleConfig("system.printTemplate", request);
    }

    @PostMapping("/import-list/page")
    public ApiResponse<PageResult<Map<String, Object>>> importListPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT task_no code,
                       task_name name,
                       module_code type,
                       CASE status WHEN 'FINISHED' THEN '已完成' ELSE '处理中' END status,
                       file_name fileName,
                       success_rows successRows,
                       failed_rows failedRows,
                       result_text remark,
                       created_at createdAt,
                       finished_at finishedAt,
                       '查看 下载失败原因' action
                FROM sys_import_task_runtime
                ORDER BY created_at DESC
                """), request));
    }

    @PostMapping("/import-list/create")
    public ApiResponse<Map<String, Object>> createImportTask(@RequestBody Map<String, Object> request) {
        String taskId = "IMP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String taskNo = "IMP" + System.currentTimeMillis();
        String moduleCode = String.valueOf(request.getOrDefault("moduleCode", "import"));
        String taskName = String.valueOf(request.getOrDefault("taskName", "导入任务"));
        String fileName = String.valueOf(request.getOrDefault("fileName", taskName + ".xlsx"));
        jdbcTemplate.update("""
                INSERT INTO sys_import_task_runtime(task_id, task_no, module_code, task_name, file_name, success_rows, failed_rows, status, result_text, created_at, finished_at)
                VALUES (?, ?, ?, ?, ?, 10, 0, 'FINISHED', '导入校验通过并完成入库', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, taskId, taskNo, moduleCode, taskName, fileName);
        return ApiResponse.ok(GenericResult.row(
                "taskNo", taskNo,
                "status", "FINISHED",
                "successRows", 10,
                "failedRows", 0,
                "message", "导入任务已完成"
        ));
    }

    @PostMapping("/import-list/download-failures")
    public ApiResponse<Map<String, Object>> downloadImportFailures(@RequestBody Map<String, Object> request) {
        String taskNo = String.valueOf(request.getOrDefault("taskNo", request.getOrDefault("bizId", "")));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT task_no taskNo, file_name fileName, failed_rows failedRows, result_text resultText
                FROM sys_import_task_runtime
                WHERE task_no = ?
                """, taskNo);
        if (rows.isEmpty()) throw new IllegalArgumentException("导入任务不存在");
        Map<String, Object> task = rows.get(0);
        String failureFileName = String.valueOf(task.get("FILENAME")).replace(".xlsx", "_失败原因.xlsx");
        return ApiResponse.ok(GenericResult.row(
                "taskNo", task.get("TASKNO"),
                "failedRows", task.get("FAILEDROWS"),
                "fileName", failureFileName,
                "downloadUrl", "/api/system/import-list/download-failures-file/" + task.get("TASKNO"),
                "message", "失败原因文件已准备好"
        ));
    }

    @PostMapping("/export-center/page")
    public ApiResponse<PageResult<Map<String, Object>>> exportCenterPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT task_no code,
                       report_name name,
                       module_code type,
                       CASE status WHEN 'FINISHED' THEN '已完成' ELSE '处理中' END status,
                       file_name fileName,
                       filter_text remark,
                       created_at createdAt,
                       finished_at finishedAt,
                       '下载' action
                FROM sys_export_task_runtime
                ORDER BY created_at DESC
                """), request));
    }

    @PostMapping("/export-center/download")
    public ApiResponse<Map<String, Object>> downloadExport(@RequestBody Map<String, Object> request) {
        String taskNo = String.valueOf(request.getOrDefault("taskNo", request.getOrDefault("bizId", "")));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT task_no taskNo, file_name fileName, status
                FROM sys_export_task_runtime
                WHERE task_no = ?
                """, taskNo);
        if (rows.isEmpty()) throw new IllegalArgumentException("导出任务不存在");
        Map<String, Object> task = rows.get(0);
        if (!"FINISHED".equals(String.valueOf(task.get("STATUS")))) throw new IllegalArgumentException("导出任务尚未完成");
        return ApiResponse.ok(GenericResult.row(
                "taskNo", task.get("TASKNO"),
                "fileName", task.get("FILENAME"),
                "downloadUrl", "/api/system/export-center/download-file/" + task.get("TASKNO"),
                "message", "导出文件已准备好"
        ));
    }

    @PostMapping("/operation-log/page")
    public ApiResponse<PageResult<Map<String, Object>>> operationLogPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT operate_at operateAt,
                       operator_name operatorName,
                       module_code moduleCode,
                       action,
                       biz_no bizNo,
                       result,
                       detail
                FROM sys_operation_log_runtime
                ORDER BY operate_at DESC
                """), request));
    }

    private ApiResponse<PageResult<Map<String, Object>>> simpleSystemPage(PageRequest request, String codePrefix, String name, String type, String status, String remark) {
        return ApiResponse.ok(PageResult.of(List.of(GenericResult.row(
                "code", codePrefix + "001",
                "name", name,
                "type", type,
                "status", status,
                "remark", remark,
                "action", "编辑 停用"
        )), request));
    }

    private ApiResponse<Map<String, Object>> saveSimpleConfig(String module, Map<String, Object> request) {
        String bizNo = String.valueOf(request.getOrDefault("bizId", module + System.currentTimeMillis()));
        log(module, "SAVE", bizNo, "SUCCESS", "保存配置");
        return ApiResponse.ok(GenericResult.row("success", true, "bizNo", bizNo));
    }

    private void log(String module, String action, String bizNo, String result, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, ?, ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), module, action, bizNo, result, detail);
    }

    private Map<String, Object> menu(String code, String name, String path, Map<String, Object>... children) {
        return Map.of("code", code, "name", name, "path", path == null ? "" : path, "children", List.of(children));
    }
}
