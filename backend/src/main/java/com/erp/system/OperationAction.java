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

        // TMS 调度/司机配送动作（PRD-31：TMS 日志委托统一服务后补中文名）
        NAMES.put("ASSIGN", "分配司机");
        NAMES.put("ACCEPT", "接单");
        NAMES.put("RETURN_POINT", "退回配送点");
        NAMES.put("SORT", "分拣");
        NAMES.put("LOADING_START", "开始装车");
        NAMES.put("LOADING_CONFIRM", "装车确认");
        NAMES.put("DEPART", "发车");
        NAMES.put("ARRIVE", "到达");
        NAMES.put("VERIFY", "核验");
        NAMES.put("BATCH_VERIFY", "批量核验");
        NAMES.put("RECEIVE", "领取/收货");
        NAMES.put("COMPLETE", "完成");
        NAMES.put("HANDLE", "接手处理");
        NAMES.put("CHECK", "清点核对");
        NAMES.put("REDISPATCH", "重新调度");
        NAMES.put("PHOTO", "上传照片");
        NAMES.put("ARRANGE", "安排退货");
        NAMES.put("CANCEL_ARRANGE", "取消安排");
        NAMES.put("LINK", "关联单据");
        NAMES.put("DISPUTE", "异议申诉");
        NAMES.put("APPROVE", "审批通过");
        NAMES.put("APPROVE_FAIL", "审批失败");
        NAMES.put("SETTLE", "结算");
        NAMES.put("OFFSET_FAIL", "抵扣失败");
        NAMES.put("RECONCILE_REMAIN", "尾款核销");
        NAMES.put("RETURN_SKIP", "跳过退货");
        NAMES.put("AR_PARTIAL", "部分核销应收");
        NAMES.put("AR_WRITEOFF_FAIL", "应收核销失败");

        // WMS 仓储作业动作
        NAMES.put("SAVE_ZONE", "保存库区");
        NAMES.put("TOGGLE_ZONE", "冻结/解冻库区");
        NAMES.put("DELETE_ZONE", "删除库区");
        NAMES.put("SAVE_BIN", "保存库位");
        NAMES.put("IMPORT_BIN", "导入库位");
        NAMES.put("TOGGLE_BIN", "启用/停用库位");
        NAMES.put("DELETE_BIN", "删除库位");
        NAMES.put("SAVE_COLLECT_ZONE", "保存集货区");
        NAMES.put("TOGGLE_COLLECT_ZONE", "切换集货区");
        NAMES.put("DELETE_COLLECT_ZONE", "删除集货区");
        NAMES.put("IMPORT_COLLECT_ZONE", "导入集货区");
        NAMES.put("SAVE_COLLECT_BIN", "保存集货位");
        NAMES.put("TOGGLE_COLLECT_BIN", "切换集货位");
        NAMES.put("LOCK_COLLECT_BIN", "锁定集货位");
        NAMES.put("RELEASE_COLLECT_BIN", "释放集货位");
        NAMES.put("DELETE_COLLECT_BIN", "删除集货位");
        NAMES.put("IMPORT_COLLECT_BIN", "导入集货位");
        NAMES.put("CREATE_MANUAL", "手工建单");
        NAMES.put("FINISH_RECEIVE", "完成收货");
        NAMES.put("RECHECK_OK", "复检合格");
        NAMES.put("RECHECK_NG", "复检不合格");
        NAMES.put("PUTAWAY", "上架");
        NAMES.put("AUTO_AUDIT_SALES_RETURN", "销售退货自动审核");
        NAMES.put("FREEZE", "冻结");
    }

    private OperationAction() {}

    /** 动作码 → 中文名；未知码原样返回（兼容历史/第三方写入的动作值）。 */
    public static String name(String code) {
        if (code == null) return "";
        return NAMES.getOrDefault(code, code);
    }
}
