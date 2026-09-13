package com.erp.finance.dayclose;

/**
 * 业务日结（PRD-33）常量：系统参数键、定时任务码、日志动作与业务类型中文名。
 *
 * <p>布尔参数统一 Y/N（{@code SysParamService.getBool} 只认 Y）；方案 v1.3 终审口径：
 * 盘点日、收付款单/费用单记账日期不回填，按单据日期守卫；资金差异仅备注不阻断。
 */
public final class BizDayCloseConst {

    private BizDayCloseConst() {
    }

    // -- 系统参数（V120 播种 P0184~P0188） ------------------------------------
    /** 总开关：Y=启用封单守卫，N=全 no-op（紧急排障）。 */
    public static final String PARAM_ENABLED = "BIZ_DAY_CLOSE_ENABLED";
    /** 日结方式：AUTO 自动 / MANUAL 手动。 */
    public static final String PARAM_MODE = "BIZ_DAY_CLOSE_MODE";
    /** 月结是否要求本期业务已全部日结：Y 硬拦 / N 提示。 */
    public static final String PARAM_GL_REQUIRE = "BIZ_GL_CLOSE_REQUIRE_DAY_CLOSE";
    /** 现金类账户名单（账户名称，逗号分隔）。 */
    public static final String PARAM_CASH_NAMES = "BIZ_CASH_ACCOUNT_NAMES";
    /** 未日结提醒时刻（6~23 点）。 */
    public static final String PARAM_LATE_HOUR = "BIZ_DAY_CLOSE_LATE_HOUR";

    public static final String MODE_AUTO = "AUTO";

    // -- 定时任务 -------------------------------------------------------------
    public static final String TASK_CODE = "BIZ_DAY_CLOSE";
    /** 定时任务 handler Bean 名（与 sys_scheduled_task.handler_bean 对应）。 */
    public static final String JOB_BEAN = "bizDayCloseJob";

    // -- 日志动作/结果/触发方式 ------------------------------------------------
    public static final String ACTION_CLOSE = "CLOSE";
    public static final String ACTION_REOPEN = "REOPEN";
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAIL = "FAIL";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_AUTO = "AUTO";

    // -- 操作日志模块码 --------------------------------------------------------
    public static final String LOG_MODULE = "finance.day_close";
    public static final String SCHEDULE_LOG_MODULE = "system.schedule_task";

    /** 容差（元）：四套滚存勾稽平衡判定。 */
    public static final double TIE_TOLERANCE = 0.01D;

    /**
     * 封单统一报错文案。{0}=单据日期（yyyy-MM-dd）。
     * 抛 IllegalArgumentException，全局异常处理原样展示中文。
     */
    public static final String CLOSED_MSG = "单据日期 %s 已日结封单，不允许该操作。如需调整，请联系财务反日结后处理。";

    /** 月结硬拦文案：{0}=yyyyMM 会计期间。 */
    public static final String GL_PERIOD_CLOSED_MSG = "该日期所在会计期间 %s 已月结，请先在总账反结账。";
}
