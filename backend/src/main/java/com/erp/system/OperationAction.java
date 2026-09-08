package com.erp.system;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作日志「动作」码与中文名。
 *
 * <p>统一各模块写日志时的 action 取值，禁止再用中文字面量（历史 OrderController 用过 "创建"/"审核"）。
 * 落库存英文码（action 列），{@link #name(String)} 提供查询/展示时的中文名。
 */
public final class OperationAction {

    public static final String CREATE     = "CREATE";      // 新增/建单
    public static final String UPDATE     = "UPDATE";      // 修改
    public static final String DELETE     = "DELETE";      // 删除
    public static final String AUDIT      = "AUDIT";       // 审核
    public static final String UN_AUDIT   = "UN_AUDIT";    // 反审核
    public static final String ENABLE     = "ENABLE";      // 启用
    public static final String DISABLE    = "DISABLE";     // 停用
    public static final String IMPORT     = "IMPORT";      // 导入
    public static final String EXPORT     = "EXPORT";      // 导出
    public static final String PRINT      = "PRINT";       // 打印
    public static final String VIEW       = "VIEW";        // 查看单据详情
    public static final String CLOSE      = "CLOSE";       // 关闭/终止
    public static final String SUBMIT     = "SUBMIT";      // 提交/下发
    public static final String REVERSE    = "REVERSE";     // 冲红/冲销
    public static final String WRITE_OFF  = "WRITE_OFF";   // 核销
    public static final String UNWRITE_OFF= "UNWRITE_OFF"; // 反核销
    public static final String SIGN       = "SIGN";        // 签收
    public static final String UNSIGN     = "UNSIGN";      // 取消签收
    public static final String SAVE       = "SAVE";        // 保存（通用）
    public static final String LOGIN      = "LOGIN";       // 登录（写入登录日志表）
    public static final String LOGOUT     = "LOGOUT";      // 登出
    public static final String CLEANUP    = "CLEANUP";     // 日志清理
    // 各控制器历史沿用的动作码别名（落库保留原码，仅补中文名映射）
    public static final String REVERSE_AUDIT = "REVERSE_AUDIT"; // 反审核（=UN_AUDIT）
    public static final String GENERATE   = "GENERATE";     // 生成下游单据
    public static final String CANCEL     = "CANCEL";       // 作废/取消
    public static final String CONFIRM    = "CONFIRM";      // 确认
    public static final String REJECT     = "REJECT";       // 驳回
    public static final String TERMINATE  = "TERMINATE";    // 终止
    public static final String PUSH_WMS   = "PUSH_WAREHOUSE";       // 下发仓库/WMS
    public static final String SYNC_WMS   = "SYNC_WMS_TASKS";       // 同步仓库任务

    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put(CREATE, "新增");
        NAMES.put(UPDATE, "修改");
        NAMES.put(DELETE, "删除");
        NAMES.put(AUDIT, "审核");
        NAMES.put(UN_AUDIT, "反审核");
        NAMES.put(ENABLE, "启用");
        NAMES.put(DISABLE, "停用");
        NAMES.put(IMPORT, "导入");
        NAMES.put(EXPORT, "导出");
        NAMES.put(PRINT, "打印");
        NAMES.put(VIEW, "查看");
        NAMES.put(CLOSE, "关闭");
        NAMES.put(SUBMIT, "提交");
        NAMES.put(REVERSE, "冲销");
        NAMES.put(WRITE_OFF, "核销");
        NAMES.put(UNWRITE_OFF, "反核销");
        NAMES.put(SIGN, "签收");
        NAMES.put(UNSIGN, "取消签收");
        NAMES.put(SAVE, "保存");
        NAMES.put(LOGIN, "登录");
        NAMES.put(LOGOUT, "登出");
        NAMES.put(CLEANUP, "清理");
        // 历史/别名动作码
        NAMES.put(REVERSE_AUDIT, "反审核");
        NAMES.put(GENERATE, "生成");
        NAMES.put(CANCEL, "作废");
        NAMES.put(CONFIRM, "确认");
        NAMES.put(REJECT, "驳回");
        NAMES.put(TERMINATE, "终止");
        NAMES.put("PUSH_WAREHOUSE", "下发仓库");
        NAMES.put("PUSH_WAREHOUSE_WMS_FAIL", "下发仓库失败");
        NAMES.put("CANCEL_PUSH", "取消下发");
        NAMES.put("SYNC_WMS_TASKS", "同步仓库任务");
        NAMES.put("WMS_CREATE", "仓库建单");
        NAMES.put("WMS_AUDIT", "仓库审核");
        NAMES.put("WMS_IDEMPOTENT", "仓库幂等回执");
        NAMES.put("DRIVER_COLLECT", "司机提货");
        NAMES.put("CHANGE_RETURN_TYPE", "变更退货类型");
    }

    private OperationAction() {}

    /** 动作码 → 中文名；未知码原样返回（兼容历史/第三方写入的动作值）。 */
    public static String name(String code) {
        if (code == null) return "";
        return NAMES.getOrDefault(code, code);
    }
}
