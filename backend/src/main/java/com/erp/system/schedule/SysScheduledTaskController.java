package com.erp.system.schedule;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.security.RequirePerm;
import com.erp.finance.dayclose.BizDayCloseConst;
import com.erp.finance.dayclose.BizDayCloseService;
import com.erp.system.OperationAction;
import com.erp.system.OperationLogService;
import com.erp.tms.TmsUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 定时任务管理接口（PRD-33 §7.1，动态定时任务模块）。
 *
 * <p>任务体只来自代码注册的 handler 白名单，库内仅可改 cron/备注/启停/立即执行；
 * 功能点：{@code system.schedule_task.view} / {@code .edit} / {@code .execute}。
 */
@RestController
@RequestMapping("/system/schedule-task")
public class SysScheduledTaskController {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysScheduledTaskService taskService;
    private final DynamicTaskRegistrar registrar;
    private final BizDayCloseService dayCloseService;
    private final OperationLogService opLog;

    public SysScheduledTaskController(SysScheduledTaskService taskService,
                                      DynamicTaskRegistrar registrar,
                                      BizDayCloseService dayCloseService,
                                      OperationLogService opLog) {
        this.taskService = taskService;
        this.registrar = registrar;
        this.dayCloseService = dayCloseService;
        this.opLog = opLog;
    }

    /** 任务列表分页（一期仅业务日结一行，仍走 POST /page 约定）。 */
    @RequirePerm(value = "system.schedule_task.view", name = "查看")
    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = taskService.listTasks().stream()
                .map(TmsUtil::camelize).toList();
        // 关键字/任务码等非范围过滤交由 PageResult 内存匹配
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 修改 cron/备注（cron 保存前校验 6 段表达式）。 */
    @RequirePerm(value = "system.schedule_task.edit", name = "修改")
    @PostMapping("/update")
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        String taskCode = str(body.get("taskCode"));
        String cronExpr = DynamicTaskRegistrar.validateCron(str(body.get("cronExpr")));
        String remark = str(body.get("remark"));
        taskService.updateTask(taskCode, cronExpr, remark);
        opLog.log(BizDayCloseConst.SCHEDULE_LOG_MODULE, OperationAction.UPDATE,
                taskCode, "修改定时任务 cron=" + cronExpr + (remark.isEmpty() ? "" : "，备注：" + remark));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("updated", true);
        r.put("cronExpr", cronExpr);
        r.put("nextFireTimes", preview(cronExpr));
        return ApiResponse.ok(r);
    }

    /** 启用/停用。 */
    @RequirePerm(value = "system.schedule_task.edit", name = "启停")
    @PostMapping("/toggle")
    public ApiResponse<Map<String, Object>> toggle(@RequestBody Map<String, Object> body) {
        String taskCode = str(body.get("taskCode"));
        String enabled = Boolean.TRUE.equals(body.get("enabled"))
                || "Y".equalsIgnoreCase(str(body.get("enabled"))) ? "Y" : "N";
        taskService.toggle(taskCode, enabled);
        opLog.log(BizDayCloseConst.SCHEDULE_LOG_MODULE,
                "Y".equals(enabled) ? OperationAction.ENABLE : OperationAction.DISABLE,
                taskCode, ("Y".equals(enabled) ? "启用" : "停用") + "定时任务");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("enabled", enabled);
        return ApiResponse.ok(r);
    }

    /**
     * 立即执行（手动触发，操作人记入执行日志）。
     * 只允许代码白名单内的任务码；失败时 runTriggered 已落 FAIL 日志并原样抛出。
     */
    @RequirePerm(value = "system.schedule_task.execute", name = "立即执行")
    @PostMapping("/run-once")
    public ApiResponse<Map<String, Object>> runOnce(@RequestBody Map<String, Object> body) {
        String taskCode = str(body.get("taskCode"));
        String operator = TmsUtil.currentUser();
        String message = taskService.runTriggered(taskCode, "MANUAL", operator, manualBody(taskCode));
        opLog.log(BizDayCloseConst.SCHEDULE_LOG_MODULE, OperationAction.SUBMIT,
                taskCode, "手动立即执行：" + message);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("message", message);
        return ApiResponse.ok(r);
    }

    /** 预览 cron 未来 5 次执行时间（不保存）。 */
    @RequirePerm(value = "system.schedule_task.view", name = "查看")
    @PostMapping("/next5")
    public ApiResponse<List<String>> next5(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(preview(DynamicTaskRegistrar.validateCron(str(body.get("cronExpr")))));
    }

    /** 执行日志分页（SQL 级分页，日志量可能较大）。 */
    @RequirePerm(value = "system.schedule_task.view", name = "查看")
    @PostMapping("/log-page")
    public ApiResponse<PageResult<Map<String, Object>>> logPage(@RequestBody PageRequest request) {
        int pageNo = request.safePageNo();
        int pageSize = request.safePageSize();
        String taskCode = request.filters() == null ? null : str(request.filters().get("taskCode"));
        long total = taskService.logCount(taskCode);
        List<Map<String, Object>> rows = taskService.logList(
                        taskCode.isEmpty() ? null : taskCode, (pageNo - 1) * pageSize, pageSize).stream()
                .map(TmsUtil::camelize).toList();
        return ApiResponse.ok(new PageResult<>(rows, pageNo, pageSize, total, Map.of()));
    }

    /** 任务码 → 手动执行任务体（新增任务时在此显式登记，不接受库内任意类名/脚本）。 */
    private Supplier<String> manualBody(String taskCode) {
        if (BizDayCloseConst.TASK_CODE.equals(taskCode)) {
            return dayCloseService::autoClose;
        }
        throw new IllegalArgumentException("不支持手动执行该任务：" + taskCode);
    }

    private static List<String> preview(String cronExpr) {
        List<String> times = new ArrayList<>(5);
        for (LocalDateTime t : DynamicTaskRegistrar.previewNext5(cronExpr)) {
            times.add(t.format(DT_FMT));
        }
        return times;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
