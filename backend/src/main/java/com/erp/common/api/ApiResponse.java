package com.erp.common.api;

public record ApiResponse<T>(String code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("0", "success", data);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /** 带结构化数据的失败响应（如 NEED_APPROVAL 携带 approvalType 供前端弹窗）。 */
    public static <T> ApiResponse<T> fail(String code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }
}
