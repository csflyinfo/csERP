package com.erp.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@link RequirePerm} 功能权限拦截器（PRD-28 RBAC）。
 *
 * <p>方法上的注解优先于类上的注解。未登录 → 401；已登录但缺功能点 → 抛
 * {@link PermissionDeniedException}（全局处理器转 403）。SYS_ADMIN 在
 * {@link PermissionService#hasFunc} 内短路放行。
 *
 * <p>用 MVC 拦截器而非 @Aspect：本项目离线依赖中没有 aspectjweaver，
 * 而 @RequirePerm 只标在 Controller 方法/类上，HandlerInterceptor 足够且零新依赖。
 */
@Component
public class RequirePermInterceptor implements HandlerInterceptor {

    private final PermissionService permissionService;

    public RequirePermInterceptor(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) return true;
        RequirePerm require = handlerMethod.getMethodAnnotation(RequirePerm.class);
        if (require == null) {
            require = handlerMethod.getBeanType().getAnnotation(RequirePerm.class);
        }
        if (require == null) return true;

        if (CurrentUser.get() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (!permissionService.hasFunc(require.value())) {
            throw new PermissionDeniedException("无操作权限：" + require.value());
        }
        return true;
    }
}
