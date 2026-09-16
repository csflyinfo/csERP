package com.erp.init;

/**
 * 期初初始化三模块（PRD-34）公共常量：库存 / 客户应收 / 供应商应付。
 *
 * <p>三模块同一套生命周期：导入/手工录入进暂存表（可反复修改）→ 一次性「期初建账（过账）」
 * 写正式账表 → 首次业务日结或总账启用后锁定 → 日结前满足条件可反建账重来。
 */
public final class InitConst {

    private InitConst() {}

    /** 过账类型。 */
    public static final String TYPE_STOCK = "STOCK";
    public static final String TYPE_AR = "AR";
    public static final String TYPE_AP = "AP";
    /** 客户期初预收（PRD-35 M4）：过账只写客户账户 ADV_OPENING 流水，不造单据不动资金。 */
    public static final String TYPE_ADV = "ADV";

    /** 暂存行状态。 */
    public static final String LINE_VALID = "VALID";
    public static final String LINE_ERROR = "ERROR";

    /** 库存期初导入模式：BATCH=按商品+批次（不定位库位）；BIN=按库位（启用 WMS）。 */
    public static final String MODE_BATCH = "BATCH";
    public static final String MODE_BIN = "BIN";

    /** 过账主表状态。 */
    public static final String POST_POSTED = "POSTED";
    public static final String POST_REVERSED = "REVERSED";

    /** 建账标志参数键（sys_param_runtime，Y/N）。 */
    public static final String PARAM_STOCK_POSTED = "biz.init.stock_posted";
    public static final String PARAM_AR_POSTED = "biz.init.ar_posted";
    public static final String PARAM_AP_POSTED = "biz.init.ap_posted";
    public static final String PARAM_ADV_POSTED = "biz.init.adv_posted";

    /** 最近一次过批号参数键。 */
    public static final String PARAM_STOCK_POST_NO = "biz.init.stock_post_no";
    public static final String PARAM_AR_POST_NO = "biz.init.ar_post_no";
    public static final String PARAM_AP_POST_NO = "biz.init.ap_post_no";
    public static final String PARAM_ADV_POST_NO = "biz.init.adv_post_no";

    /** 正式账表来源号前缀（fin_ar.source_bill / fin_ap.source_bill / inv_stock_ledger.source_bill）。 */
    public static final String PREFIX_AR = "QCAR";
    public static final String PREFIX_AP = "QCAP";
    public static final String PREFIX_STOCK_LEDGER = "QTRK";
    /** 期初预收流水来源号前缀（fin_customer_account_flow.source_bill，bizKey 用 ADVO: 前缀）。 */
    public static final String PREFIX_ADV = "QCYK";

    /** 导入任务模块编码（sys_import_task_runtime.module_code，「导入列表」按此展示）。 */
    public static final String TASK_MODULE_STOCK = "init_stock";
    public static final String TASK_MODULE_AR = "init_ar";
    public static final String TASK_MODULE_AP = "init_ap";
    public static final String TASK_MODULE_ADV = "init_adv";
}
