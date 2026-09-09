package com.erp.system;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.system.perm.MenuMetaService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/system")
public class SystemController {
    /** 参数设置页左侧分组的展示顺序；未列出的 param_group 追加在后，NULL/空串落「公共参数」兜底。 */
    private static final List<String> PARAM_GROUP_ORDER = List.of(
            "公共参数", "销售", "销售退货", "库存", "TMS配送",
            "WMS基础参数", "WMS入库", "WMS出库", "日志与安全");

    /** 照片张数类参数的合法区间（PRD-26 §3.3）。 */
    private static final Set<String> PHOTO_COUNT_KEYS = Set.of("TMS_SIGN_PHOTO_COUNT", "TMS_RETURN_PHOTO_COUNT");

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SysParamService sysParamService;
    private final OperationLogService opLog;
    private final MenuMetaService menuMetaService;

    public SystemController(JdbcTemplate jdbcTemplate, BCryptPasswordEncoder passwordEncoder,
                            SysParamService sysParamService, OperationLogService opLog,
                            MenuMetaService menuMetaService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.sysParamService = sysParamService;
        this.opLog = opLog;
        this.menuMetaService = menuMetaService;
    }

    /**
     * 当前登录用户的菜单树（PRD-28：改由 sys_menu_meta + sys_role_menu_rel 实时生成，支持三级）。
     * 旧 roleCode 入参仅为兼容老前端调用，不再使用——任何人只能取自己的授权菜单。
     */
    @GetMapping("/menu/user-tree")
    public ApiResponse<List<Map<String, Object>>> userMenuTree(
            @RequestParam(defaultValue = "ERP") String appType,
            @RequestParam(required = false) String roleCode) {
        return ApiResponse.ok(menuMetaService.userTree(appType));
    }

    @PostMapping("/field-scope")
    public ApiResponse<Map<String, Object>> fieldScope(@RequestBody Map<String, Object> request) {
        String moduleCode = String.valueOf(request.getOrDefault("moduleCode", ""));
        String roleCode = String.valueOf(request.getOrDefault("roleCode", "SYS_ADMIN"));
        Set<String> hidden = hiddenFields(roleCode, moduleCode);
        return ApiResponse.ok(GenericResult.row("moduleCode", moduleCode, "roleCode", roleCode, "hiddenFields", hidden));
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
        String rawPassword = String.valueOf(request.getOrDefault("password", "admin123"));
        String encodedPassword = passwordEncoder.encode(rawPassword);
        jdbcTemplate.update("""
                MERGE INTO sys_user_runtime KEY(user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'NORMAL')
                """, id, request.getOrDefault("username", "user" + System.currentTimeMillis()), request.getOrDefault("displayName", "新用户"),
                encodedPassword, request.getOrDefault("mobile", ""), request.getOrDefault("roleName", "普通用户"), request.getOrDefault("dataScope", "本人"));
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
                       data_scope dataScope,
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
                VALUES (?, ?, ?, 0, ?, ?, ?, 'NORMAL')
                """, id, request.getOrDefault("roleCode", id), request.getOrDefault("roleName", "新权限组"),
                request.getOrDefault("menuScope", "按配置"), request.getOrDefault("fieldScope", "按配置"), request.getOrDefault("dataScope", "ALL"));
        log("system.role", "SAVE", id, "SUCCESS", "保存权限组");
        return ApiResponse.ok(GenericResult.row("roleId", id, "success", true));
    }

    @PostMapping("/param/page")
    public ApiResponse<PageResult<Map<String, Object>>> paramPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT param_key, param_name, param_value, default_value, param_group,
                       param_type, option_json, sort_no, min_value, max_value, unit, remark
                FROM sys_param_runtime
                ORDER BY param_group, sort_no, param_key
                """).stream().map(SystemController::camelize).toList(), request));
    }

    @PostMapping("/param/update")
    public ApiResponse<Map<String, Object>> updateParam(@RequestBody Map<String, Object> request) {
        String paramKey = str(request.get("paramKey"));
        String error = validateParam(paramKey, str(request.get("paramValue")));
        if (error != null) {
            return ApiResponse.fail("400", error);
        }
        jdbcTemplate.update("UPDATE sys_param_runtime SET param_value = ? WHERE param_key = ?", request.get("paramValue"), paramKey);
        sysParamService.evict();
        log("system.param", "UPDATE", paramKey, "SUCCESS", "修改系统参数");
        return ApiResponse.ok(GenericResult.operation("system.param", "UPDATE"));
    }

    /**
     * 参数设置页一次性拉取全量参数，按 param_group 分组返回，不分页（PRD-26 §5.3）。
     * param_group 为 NULL/空串的参数统一落「公共参数」，保证不会因分组值对不上在页面上消失。
     */
    @GetMapping("/param/setting")
    public ApiResponse<Map<String, Object>> paramSetting() {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (String group : PARAM_GROUP_ORDER) {
            grouped.put(group, new ArrayList<>());
        }
        jdbcTemplate.queryForList("""
                SELECT param_id,
                       param_key,
                       param_name,
                       param_value,
                       default_value,
                       param_group,
                       param_type,
                       option_json,
                       sort_no,
                       min_value,
                       max_value,
                       unit,
                       remark
                FROM sys_param_runtime
                ORDER BY param_group, sort_no, param_key
                """).forEach(row -> {
            row = camelize(row);
            String group = str(row.get("paramGroup"));
            if (group.isEmpty()) {
                group = "公共参数";
                row.put("paramGroup", group);
            }
            grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(row);
        });

        List<Map<String, Object>> groups = new ArrayList<>();
        grouped.forEach((group, items) -> {
            if (!items.isEmpty()) {
                groups.add(GenericResult.row("groupName", group, "items", items));
            }
        });
        return ApiResponse.ok(GenericResult.row("groups", groups, "offsetAccounts", offsetAccountOptions()));
    }

    /**
     * 冲抵资金账户可选项：末级、状态正常、且未绑定给任何司机（校验规则见 validateParam）。
     * 由后端下发而非前端自行过滤，避免前端无法判断「已绑定司机」这一排除条件。
     */
    private List<Map<String, Object>> offsetAccountOptions() {
        try {
            return jdbcTemplate.queryForList("""
                    SELECT a.fund_account_code code, a.fund_account_name name
                    FROM base_fund_account a
                    WHERE a.status = 'NORMAL'
                      AND (SELECT COUNT(1) FROM base_fund_account c WHERE c.parent_code = a.fund_account_code) = 0
                      AND (SELECT COUNT(1) FROM tms_driver_fund_account d WHERE d.fund_account_code = a.fund_account_code) = 0
                    ORDER BY a.fund_account_code
                    """).stream().map(SystemController::camelize).toList();
        } catch (Exception ignore) {
            return List.of();
        }
    }

    /**
     * JDBC 返回的列名在 H2 下会被强制成大写（如 PARAM_VALUE），而前端按小驼峰读取（paramValue）。
     * 这里统一把 key 转为小驼峰：先整体转小写再按下划线拼接，能同时兼容
     * 「别名已写成 paramValue 但被 H2 转大写」和「原生列名 param_value」两种来源。
     */
    private static Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : k.toCharArray()) {
                if (c == '_') { upper = true; continue; }
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            out.put(sb.toString(), e.getValue());
        }
        return out;
    }

    /**
     * 分组内批量保存：两阶段执行 —— 先把整批全部校验完，再统一写库，最后失效缓存一次。
     * 不依赖 @Transactional 回滚：ApiResponse.fail 是正常返回而非抛异常，
     * Spring 默认只对 RuntimeException 回滚，边校验边写会造成「整批被拒但前几项已落库」。
     * 只能改 param_value，参数集合由 Flyway 管理，界面不支持新增/删除。
     */
    @PostMapping("/param/batch-update")
    @Transactional
    public ApiResponse<Map<String, Object>> batchUpdateParam(@RequestBody Map<String, Object> request) {
        Object raw = request.get("items");
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return ApiResponse.fail("400", "没有需要保存的参数");
        }
        List<Object[]> pending = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String paramKey = str(map.get("paramKey"));
            String paramValue = str(map.get("paramValue"));
            if (paramKey.isEmpty()) {
                return ApiResponse.fail("400", "参数编码不能为空");
            }
            String error = validateParam(paramKey, paramValue);
            if (error != null) {
                return ApiResponse.fail("400", error);
            }
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_param_runtime WHERE param_key = ?", Integer.class, paramKey);
            if (exists == null || exists == 0) {
                return ApiResponse.fail("400", "参数不存在：" + paramKey);
            }
            pending.add(new Object[]{paramValue, paramKey});
        }
        if (pending.isEmpty()) {
            return ApiResponse.fail("400", "没有需要保存的参数");
        }
        int updated = 0;
        for (Object[] args : pending) {
            updated += jdbcTemplate.update("UPDATE sys_param_runtime SET param_value = ? WHERE param_key = ?", args);
        }
        sysParamService.evict();
        log("system.param", "BATCH_UPDATE", String.valueOf(updated), "SUCCESS", "批量修改系统参数 " + updated + " 项");
        return ApiResponse.ok(GenericResult.row("updated", updated, "success", true));
    }

    /**
     * 参数值合法性校验（PRD-26 §3.3）。返回 null 表示通过，否则返回错误提示。
     */
    private String validateParam(String paramKey, String paramValue) {
        if (PHOTO_COUNT_KEYS.contains(paramKey)) {
            int count;
            try {
                count = Integer.parseInt(paramValue);
            } catch (NumberFormatException e) {
                return "照片张数必须是 0~5 的整数";
            }
            if (count < 0 || count > 5) {
                return "照片张数必须在 0~5 之间";
            }
            return null;
        }
        if ("TMS_OFFSET_FUND_ACCOUNT".equals(paramKey) && !paramValue.isEmpty()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT a.fund_account_code code,
                           a.status status,
                           (SELECT COUNT(1) FROM base_fund_account c WHERE c.parent_code = a.fund_account_code) childCount,
                           (SELECT COUNT(1) FROM tms_driver_fund_account d WHERE d.fund_account_code = a.fund_account_code) driverBound
                    FROM base_fund_account a
                    WHERE a.fund_account_code = ?
                    """, paramValue);
            if (rows.isEmpty()) {
                return "冲抵资金账户不存在：" + paramValue;
            }
            Map<String, Object> row = rows.get(0);
            if (!"NORMAL".equals(str(row.get("status")))) {
                return "冲抵资金账户已停用，请选择状态正常的账户";
            }
            if (num(row.get("childCount")) > 0) {
                return "冲抵资金账户必须是末级账户，不能选择分类节点";
            }
            if (num(row.get("driverBound")) > 0) {
                return "冲抵资金账户不能选择已绑定给司机的收款账户，请单设过渡户";
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
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
        log("system.import", "CREATE", taskNo, "SUCCESS", "创建导入任务：" + taskName);
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
        log("system.import", "DOWNLOAD_FAILURES", taskNo, "SUCCESS", "下载导入失败原因");
        return ApiResponse.ok(GenericResult.row(
                "taskNo", task.get("TASKNO"),
                "failedRows", task.get("FAILEDROWS"),
                "fileName", failureFileName,
                "downloadUrl", "/api/system/import-list/download-failures-file/" + task.get("TASKNO"),
                "mimeType", "text/csv;charset=UTF-8",
                "fileContent", "行号,字段,失败原因\n1,商品编码,示例：该任务无失败行\n任务号," + task.get("TASKNO") + "," + task.get("RESULTTEXT"),
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
        log("system.export", "DOWNLOAD", taskNo, "SUCCESS", "下载导出文件");
        return ApiResponse.ok(GenericResult.row(
                "taskNo", task.get("TASKNO"),
                "fileName", task.get("FILENAME"),
                "downloadUrl", "/api/system/export-center/download-file/" + task.get("TASKNO"),
                "mimeType", "text/csv;charset=UTF-8",
                "fileContent", "任务号,文件名,状态\n" + task.get("TASKNO") + "," + task.get("FILENAME") + ",FINISHED",
                "message", "导出文件已准备好"
        ));
    }

    // 注：操作日志/登录日志查询页已迁移到 SystemLogController（SQL 级过滤分页 + 改前改后），
    // 业务单据内的操作记录时间线在 OperationLogController（/operation-log/**，登录即可访问）。

    // ========== 消息通知 ==========
    @PostMapping("/notification/page")
    public ApiResponse<PageResult<Map<String, Object>>> notificationPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT notify_id notifyId, title, content, notify_type notifyType,
                       module_code moduleCode, biz_no bizNo,
                       CASE WHEN is_read THEN '已读' ELSE '未读' END status,
                       created_at createdAt
                FROM sys_notification
                ORDER BY is_read ASC, created_at DESC
                """), request));
    }

    @PostMapping("/notification/read")
    public ApiResponse<Boolean> readNotification(@RequestBody Map<String, Object> request) {
        String notifyId = String.valueOf(request.getOrDefault("bizId", ""));
        if (notifyId == null || notifyId.isBlank() || "null".equals(notifyId)) {
            jdbcTemplate.update("UPDATE sys_notification SET is_read = TRUE WHERE is_read = FALSE");
        } else {
            jdbcTemplate.update("UPDATE sys_notification SET is_read = TRUE WHERE notify_id = ?", notifyId);
        }
        return ApiResponse.ok(true);
    }

    @GetMapping("/notification/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        int count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_notification WHERE is_read = FALSE", Integer.class);
        return ApiResponse.ok(Map.of("count", count));
    }

    // ========== 待办中心 ==========
    @PostMapping("/todo/page")
    public ApiResponse<PageResult<Map<String, Object>>> todoPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT todo_id todoId, title, module_code moduleCode, biz_no bizNo, biz_id bizId,
                       CASE priority WHEN 'HIGH' THEN '高' WHEN 'LOW' THEN '低' ELSE '普通' END priority,
                       CASE status WHEN 'DONE' THEN '已完成' ELSE '待处理' END status,
                       created_at createdAt
                FROM sys_todo
                ORDER BY CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END, created_at DESC
                """), request));
    }

    @PostMapping("/todo/done")
    public ApiResponse<Boolean> doneTodo(@RequestBody Map<String, Object> request) {
        String todoId = String.valueOf(request.getOrDefault("bizId", ""));
        jdbcTemplate.update("UPDATE sys_todo SET status = 'DONE' WHERE todo_id = ?", todoId);
        return ApiResponse.ok(true);
    }

    @GetMapping("/todo/pending-count")
    public ApiResponse<Map<String, Object>> pendingCount() {
        int count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_todo WHERE status = 'PENDING'", Integer.class);
        return ApiResponse.ok(Map.of("count", count));
    }

    @GetMapping("/todo/summary")
    public ApiResponse<Map<String, Object>> todoSummary() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT module_code moduleCode, COUNT(*) cnt
                FROM sys_todo WHERE status = 'PENDING' GROUP BY module_code
                """);
        Map<String, Object> summary = new HashMap<>();
        summary.put("total", rows.stream().mapToInt(r -> ((Number) r.get("CNT")).intValue()).sum());
        for (Map<String, Object> row : rows) {
            summary.put(String.valueOf(row.get("MODULECODE")), row.get("CNT"));
        }
        return ApiResponse.ok(summary);
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

    /**
     * 系统配置类操作日志：统一委托 {@link OperationLogService}（真实操作人、IP、耗时、独立事务）。
     * 保留原 (module, action, bizNo, result, detail) 签名以最小化改动各调用点。
     */
    private void log(String module, String action, String bizNo, String result, String detail) {
        if ("FAIL".equalsIgnoreCase(result)) {
            opLog.logFail(module, action, bizNo, detail);
        } else {
            opLog.log(module, action, bizNo, detail);
        }
    }



    private Set<String> hiddenFields(String roleCode, String moduleCode) {
        Set<String> hidden = new HashSet<>();
        if ("SALE".equalsIgnoreCase(roleCode) && ("goods".equals(moduleCode) || "salesOrder".equals(moduleCode))) {
            hidden.add("成本价");
            hidden.add("成本金额");
            hidden.add("参考进价");
            hidden.add("最新进价");
        }
        return hidden;
    }

}
