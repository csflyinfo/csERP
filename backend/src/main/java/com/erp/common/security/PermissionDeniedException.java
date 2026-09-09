package com.erp.common.security;

/**
 * 功能权限校验未通过（PRD-28 RBAC）。
 *
 * <p>由 {@code RequirePermInterceptor} 在当前用户缺少 {@link RequirePerm} 声明的功能点时抛出，
 * 全局异常处理器统一转 HTTP 403。消息可直接给用户看。
 */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}
