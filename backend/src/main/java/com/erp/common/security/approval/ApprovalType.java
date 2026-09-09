package com.erp.common.security.approval;

/**
 * 敏感操作二次授权类型（PRD-28 §6.2.1 三个审批型全局功能，卡片6）。
 *
 * <p>触发场景均为「本人无权限但业务上允许由有权限者当场授权放行」：
 * 低于最低价销售、超出客户信用额度、库存不足仍审核出库。
 */
public enum ApprovalType {

    LOW_PRICE("global.low_price_approval", "低价销售授权"),
    OVER_CREDIT("global.over_credit_approval", "超信用授权"),
    NEGATIVE_STOCK("global.negative_stock_approval", "负库存授权");

    private final String funcCode;
    private final String displayName;

    ApprovalType(String funcCode, String displayName) {
        this.funcCode = funcCode;
        this.displayName = displayName;
    }

    /** 对应的全局功能点编码（sys_func_meta.func_code）。 */
    public String funcCode() {
        return funcCode;
    }

    /** 中文名（留痕/弹窗标题用）。 */
    public String displayName() {
        return displayName;
    }
}
