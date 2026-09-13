package com.erp.finance.dayclose;

import com.erp.system.schedule.DynamicTaskRegistrar;
import com.erp.system.schedule.SysScheduledTaskService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 业务日结定时任务 handler（PRD-33 §4.4）。
 *
 * <p>启动时以 Bean 名 {@link BizDayCloseConst#JOB_BEAN} 注册到动态调度白名单；
 * 由 sys_scheduled_task 的 cron（默认 02:30）触发，也可在定时任务页【立即执行】手动触发。
 * 计时、sys_scheduled_task_log、last_* 回写统一由 {@link SysScheduledTaskService} 包裹。
 */
@Component
public class BizDayCloseJob {

    private final BizDayCloseService dayCloseService;
    private final DynamicTaskRegistrar registrar;
    private final SysScheduledTaskService scheduleTaskService;

    public BizDayCloseJob(BizDayCloseService dayCloseService,
                          DynamicTaskRegistrar registrar,
                          SysScheduledTaskService scheduleTaskService) {
        this.dayCloseService = dayCloseService;
        this.registrar = registrar;
        this.scheduleTaskService = scheduleTaskService;
    }

    @PostConstruct
    public void register() {
        // 自动触发操作人为空；手动【立即执行】由 Controller 传入当前用户
        registrar.registerHandler(BizDayCloseConst.JOB_BEAN,
                () -> scheduleTaskService.runTriggered(
                        BizDayCloseConst.TASK_CODE, BizDayCloseConst.TRIGGER_AUTO, null,
                        dayCloseService::autoClose));
    }
}
