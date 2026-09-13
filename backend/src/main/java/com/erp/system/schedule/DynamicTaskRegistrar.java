package com.erp.system.schedule;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 动态定时任务注册器（PRD-33 §7.1，定时任务管理模块）。
 *
 * <p>基于 spring-context 自带 {@link ThreadPoolTaskScheduler}，不引入 quartz：
 * <ul>
 *   <li>handler 在代码中注册（Bean 名 → Runnable 白名单），数据库只存 cron 与启停，
 *       不允许执行任意脚本/类名；</li>
 *   <li>cron 使用 Spring 6 段表达式（秒 分 时 日 月 周，与存量 {@code @Scheduled} 一致，支持 ?）；</li>
 *   <li>改 cron/启停后先 cancel 旧 future 再按新表达式重排，{@link #validateCron} 在保存前校验。</li>
 * </ul>
 */
@Service
public class DynamicTaskRegistrar {

    private final ThreadPoolTaskScheduler scheduler;
    /** task_code -> 当前调度句柄。 */
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    /** handler_bean -> 代码注册的任务体（白名单）。 */
    private final Map<String, Runnable> handlers = new ConcurrentHashMap<>();

    public DynamicTaskRegistrar() {
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(4);
        this.scheduler.setThreadNamePrefix("dyn-schedule-");
        this.scheduler.setWaitForTasksToCompleteOnShutdown(true);
        this.scheduler.setAwaitTerminationSeconds(10);
        this.scheduler.initialize();
    }

    /** 任务实现方启动时注册自己（如 BizDayCloseJob 注册 "bizDayCloseJob"）。 */
    public void registerHandler(String handlerBean, Runnable task) {
        handlers.put(handlerBean, task);
    }

    /** 是否存在该 handler（未注册的任务行不调度）。 */
    public boolean handlerExists(String handlerBean) {
        return handlerBean != null && handlers.containsKey(handlerBean);
    }

    /**
     * 按任务行重排调度：先取消旧调度；enabled=Y 且 handler 已注册时按新 cron 排。
     *
     * @param taskCode    任务码
     * @param handlerBean handler Bean 名
     * @param cronExpr    6 段 cron（调用前应已过 {@link #validateCron}）
     * @param enabled     Y/N
     */
    public synchronized void schedule(String taskCode, String handlerBean, String cronExpr, String enabled) {
        cancel(taskCode);
        if (!"Y".equalsIgnoreCase(enabled) || !handlerExists(handlerBean)) {
            return;
        }
        Runnable task = handlers.get(handlerBean);
        ScheduledFuture<?> future = scheduler.schedule(task, new CronTrigger(cronExpr.trim()));
        if (future != null) {
            futures.put(taskCode, future);
        }
    }

    /** 取消某任务当前调度（停用/删除时用）。 */
    public synchronized void cancel(String taskCode) {
        ScheduledFuture<?> old = futures.remove(taskCode);
        if (old != null) {
            old.cancel(false);
        }
    }

    /**
     * 校验 cron，非法时抛中文 IllegalArgumentException（保存接口直接展示）。
     */
    public static String validateCron(String cronExpr) {
        if (cronExpr == null || cronExpr.trim().isEmpty()) {
            throw new IllegalArgumentException("cron 表达式不能为空");
        }
        try {
            CronExpression.parse(cronExpr.trim());
            return cronExpr.trim();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cron 表达式格式不正确（需 6 段：秒 分 时 日 月 周）：" + cronExpr);
        }
    }

    /**
     * 预览从当前时刻起未来 5 次执行时间（定时任务页展示）。
     */
    public static List<LocalDateTime> previewNext5(String cronExpr) {
        CronExpression cron = CronExpression.parse(validateCron(cronExpr));
        List<LocalDateTime> result = new ArrayList<>(5);
        LocalDateTime t = LocalDateTime.now().withSecond(0).withNano(0).plusMinutes(1);
        for (int i = 0; i < 5; i++) {
            t = cron.next(t);
            if (t == null) {
                break;
            }
            result.add(t);
        }
        return result;
    }

    @PreDestroy
    public void shutdown() {
        futures.values().forEach(f -> f.cancel(false));
        futures.clear();
        scheduler.shutdown();
    }
}
