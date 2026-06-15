package com.erp.common.api;

public record ApiResponse<T>(String code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("0", "success", data);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
