package com.erp.common.security.approval;

/**
 * 业务操作需要更高权限人当场授权（PRD-28 §6.2.1，卡片6）。
 *
 * <p>继承 {@link IllegalArgumentException} 以走用户可见中文错误通道；全局处理器把它转成
 * {@code code=NEED_APPROVAL} 响应并携带 {@link ApprovalType}，前端识别后弹出授权账号密码框，
 * 用户填妥后把 approverAccount/approverPassword 合并进原请求体重放。
 */
public class NeedApprovalException extends IllegalArgumentException {

    private final ApprovalType type;
    private final String bizNo;

    public NeedApprovalException(ApprovalType type, String bizNo, String message) {
        super(message);
        this.type = type;
        this.bizNo = bizNo;
    }

    public ApprovalType getType() {
        return type;
    }

    public String getBizNo() {
        return bizNo;
    }
}
