package com.erp.system.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 定时任务管理 DB 服务（PRD-33 §7.1）。
 *
 * <p>职责：启动时把库中启用的任务排进 {@link DynamicTaskRegistrar}；统一包裹任务执行
 * （写 sys_scheduled_task_log、回写 last_* 字段）；改 cron/启停后重排；日志保留 90 天。
 * 任务体只来自代码白名单，本类不执行任何库中脚本/类名。
 */
@Service
public class SysScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(SysScheduledTaskService.class);

    /** 执行日志保留天数。 */
    private static final int LOG_RETENTION_DAYS = 90;

    private final JdbcTemplate jdbcTemplate;
    private final DynamicTaskRegistrar registrar;

    public SysScheduledTaskService(JdbcTemplate jdbcTemplate, DynamicTaskRegistrar registrar) {
        this.jdbcTemplate = jdbcTemplate;
        this.registrar = registrar;
    }

    /**
     * 应用启动后按库内任务行注册调度（任务 Bean 均已在 @PostConstruct 注册白名单，
     * ApplicationReadyEvent 晚于 PostConstruct，handler 必然就绪）。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void scheduleAllOnStartup() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT task_code, handler_bean, cron_expr, enabled FROM sys_scheduled_task");
            for (Map<String, Object> row : rows) {
                String code = String.valueOf(row.get("task_code"));
                try {
                    registrar.schedule(code, str(row.get("handler_bean")),
                            str(row.get("cron_expr")), str(row.get("enabled")));
                } catch (Exception e) {
                    log.error("启动排程失败 task={}: {}", code, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("加载定时任务失败：{}", e.getMessage());
        }
    }

    /** 全量任务行（定时任务页列表，本期仅业务日结一行）。 */
    public List<Map<String, Object>> listTasks() {
        return jdbcTemplate.queryForList(
                "SELECT task_code, task_name, handler_bean, cron_expr, enabled, "
                        + "last_fire_time, last_finish_time, last_result, last_message, remark, update_time "
                        + "FROM sys_scheduled_task ORDER BY task_code");
    }

    /** 执行日志分页数据（PageResult 组装在 Controller）。 */
    public List<Map<String, Object>> logList(String taskCode, int offset, int limit) {
        if (taskCode != null && !taskCode.isBlank()) {
            return jdbcTemplate.queryForList(
                    "SELECT id, task_code, trigger_type, operator_name, start_time, finish_time, result, message "
                            + "FROM sys_scheduled_task_log WHERE task_code = ? "
                            + "ORDER BY start_time DESC LIMIT ? OFFSET ?",
                    taskCode, limit, offset);
        }
        return jdbcTemplate.queryForList(
                "SELECT id, task_code, trigger_type, operator_name, start_time, finish_time, result, message "
                        + "FROM sys_scheduled_task_log ORDER BY start_time DESC LIMIT ? OFFSET ?",
                limit, offset);
    }

    public long logCount(String taskCode) {
        if (taskCode != null && !taskCode.isBlank()) {
            Long c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_scheduled_task_log WHERE task_code = ?", Long.class, taskCode);
            return c == null ? 0 : c;
        }
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_scheduled_task_log", Long.class);
        return c == null ? 0 : c;
    }

    /** 改 cron/备注（事务内更新库并重排；cron 已在 Controller 过校验）。 */
    @Transactional
    public void updateTask(String taskCode, String cronExpr, String remark) {
        int n = jdbcTemplate.update(
                "UPDATE sys_scheduled_task SET cron_expr = ?, remark = ?, update_time = CURRENT_TIMESTAMP "
                        + "WHERE task_code = ?", cronExpr, remark, taskCode);
        if (n == 0) {
            throw new IllegalArgumentException("定时任务不存在：" + taskCode);
        }
        reloadOne(taskCode);
    }

    /** 启停切换并重排。 */
    @Transactional
    public void toggle(String taskCode, String enabled) {
        int n = jdbcTemplate.update(
                "UPDATE sys_scheduled_task SET enabled = ?, update_time = CURRENT_TIMESTAMP WHERE task_code = ?",
                "Y".equalsIgnoreCase(enabled) ? "Y" : "N", taskCode);
        if (n == 0) {
            throw new IllegalArgumentException("定时任务不存在：" + taskCode);
        }
        reloadOne(taskCode);
    }

    private void reloadOne(String taskCode) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT handler_bean, cron_expr, enabled FROM sys_scheduled_task WHERE task_code = ?", taskCode);
        registrar.schedule(taskCode, str(row.get("handler_bean")), str(row.get("cron_expr")), str(row.get("enabled")));
    }

    /**
     * 包裹一次任务执行：落开始/结束日志、回写 last_* 字段、异常记 FAIL 并继续抛出
     * （手动立即执行时 Controller 可向用户展示失败原因）。
     *
     * @param taskCode     任务码
     * @param triggerType  AUTO / MANUAL
     * @param operatorName 手动触发的操作人；自动触发为 null
     * @param task         任务体，返回执行消息
     * @return 执行消息
     */
    public String runTriggered(String taskCode, String triggerType, String operatorName, Supplier<String> task) {
        LocalDateTime start = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sys_scheduled_task_log (task_code, trigger_type, operator_name, start_time, result) "
                            + "VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, taskCode);
            ps.setString(2, triggerType);
            if (operatorName == null || operatorName.isBlank()) {
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(3, operatorName);
            }
            ps.setTimestamp(4, Timestamp.valueOf(start));
            ps.setNull(5, Types.VARCHAR);
            return ps;
        }, keyHolder);
        Number logId = keyHolder.getKey();

        String message;
        String result;
        LocalDateTime finish;
        try {
            message = task.get();
            result = "SUCCESS";
            finish = LocalDateTime.now();
        } catch (RuntimeException e) {
            finish = LocalDateTime.now();
            message = e.getMessage();
            result = "FAIL";
            finishLog(logId, finish, result, message);
            updateLast(taskCode, start, finish, result, truncate(message));
            throw e;
        }
        finishLog(logId, finish, result, message);
        updateLast(taskCode, start, finish, result, truncate(message));
        return message;
    }

    private void finishLog(Number logId, LocalDateTime finish, String result, String message) {
        if (logId == null) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE sys_scheduled_task_log SET finish_time = ?, result = ?, message = ? WHERE id = ?",
                Timestamp.valueOf(finish), result, message, logId.longValue());
    }

    private void updateLast(String taskCode, LocalDateTime start, LocalDateTime finish, String result, String message) {
        jdbcTemplate.update(
                "UPDATE sys_scheduled_task SET last_fire_time = ?, last_finish_time = ?, "
                        + "last_result = ?, last_message = ?, update_time = CURRENT_TIMESTAMP WHERE task_code = ?",
                Timestamp.valueOf(start), Timestamp.valueOf(finish), result, message, taskCode);
    }

    /** 每天 03:10 清理 90 天前的执行日志（固定调度，与动态任务互不影响）。 */
    @Scheduled(cron = "0 10 3 * * ?")
    public void cleanExpiredLogs() {
        try {
            int n = jdbcTemplate.update(
                    "DELETE FROM sys_scheduled_task_log WHERE start_time < DATEADD('DAY', ?, CURRENT_TIMESTAMP)",
                    -LOG_RETENTION_DAYS);
            if (n > 0) {
                log.info("清理定时任务日志 {} 条（保留 {} 天）", n, LOG_RETENTION_DAYS);
            }
        } catch (Exception e) {
            log.warn("清理定时任务日志失败：{}", e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 950 ? s.substring(0, 950) : s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
