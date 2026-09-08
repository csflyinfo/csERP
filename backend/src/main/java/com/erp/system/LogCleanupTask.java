package com.erp.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 操作日志 / 登录日志保留期自动清理（PRD-31）。
 *
 * <p>每日 03:30 执行，按参数 OP_LOG_RETENTION_DAYS / LOGIN_LOG_RETENTION_DAYS 删除超期记录。
 * 截止时间在 Java 层算（H2 无 DATEADD），分批删除（每批 1000，子查询 + LIMIT）避免长事务锁表。
 * 清理完成后写一条 CLEANUP 日志留痕。手动清理（管理页按钮）复用 {@link #cleanupNow()}。
 */
@Component
public class LogCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(LogCleanupTask.class);
    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final SysParamService sysParam;
    private final OperationLogService opLog;

    public LogCleanupTask(JdbcTemplate jdbc, SysParamService sysParam, OperationLogService opLog) {
        this.jdbc = jdbc;
        this.sysParam = sysParam;
        this.opLog = opLog;
    }

    /** 每日 03:30 自动清理。 */
    @Scheduled(cron = "0 30 3 * * *")
    public void scheduledCleanup() {
        try {
            int[] r = cleanupNow();
            log.info("日志自动清理完成：操作日志删除 {} 条，登录日志删除 {} 条", r[0], r[1]);
        } catch (Exception e) {
            log.warn("日志自动清理失败：{}", e.getMessage());
        }
    }

    /**
     * 立即执行清理（手动按钮 / 定时任务共用）。
     *
     * @return [操作日志删除条数, 登录日志删除条数]
     */
    public int[] cleanupNow() {
        int opDays = sysParam.getInt("OP_LOG_RETENTION_DAYS", 180, 30, 3650);
        int loginDays = sysParam.getInt("LOGIN_LOG_RETENTION_DAYS", 180, 30, 3650);

        LocalDateTime opCutoff = LocalDateTime.now().minusDays(opDays);
        LocalDateTime loginCutoff = LocalDateTime.now().minusDays(loginDays);

        int opDeleted = deleteBatch(
                "sys_operation_log_runtime", "log_id", "operate_at", opCutoff);
        int loginDeleted = deleteBatch(
                "sys_login_log", "login_log_id", "login_at", loginCutoff);

        // 清理动作本身留痕（新记录不会被本轮删除）
        opLog.log(OperationModule.OP_LOG, OperationAction.CLEANUP, null,
                "清理操作日志 " + opDeleted + " 条（保留" + opDays + "天），登录日志 "
                        + loginDeleted + " 条（保留" + loginDays + "天）");
        return new int[]{opDeleted, loginDeleted};
    }

    /**
     * 分批删除超期记录。H2 不支持 UPDATE...FROM，删除用「主键 IN (子查询 LIMIT)」循环，
     * 每批 {@value #BATCH} 条，直到删不到为止。
     */
    private int deleteBatch(String table, String pk, String timeCol, LocalDateTime cutoff) {
        int total = 0;
        while (true) {
            int n = jdbc.update(
                    "DELETE FROM " + table + " WHERE " + pk + " IN (" +
                    "SELECT " + pk + " FROM " + table +
                    " WHERE " + timeCol + " < ? ORDER BY " + pk + " LIMIT ?)",
                    cutoff, BATCH);
            total += n;
            if (n < BATCH) {
                break;
            }
        }
        return total;
    }
}
