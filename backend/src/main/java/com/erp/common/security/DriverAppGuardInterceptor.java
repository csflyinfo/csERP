package com.erp.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 司机配送 APP 端类型隔离拦截器（PRD-28 卡片10）。
 *
 * <p>/tms/app/**（登录端点除外）只接受 appType=DRIVER 的令牌：ERP 后台令牌、WMS PDA
 * 令牌以及 V108 前不带 appType 的老版司机令牌一律 401，强制走新版短信登录重新签发。
 * 功能点级授权仍由 {@link RequirePermInterceptor} 负责，本拦截器只做端类型校验。
 */
@Component
public class DriverAppGuardInterceptor implements HandlerInterceptor {

    public static final String LOGIN_PATH = "/tms/app/login";

    private final ObjectMapper objectMapper;

    public DriverAppGuardInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        CurrentUser.Principal user = CurrentUser.get();
        if (user != null && "DRIVER".equals(user.appType())) return true;
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "code", "401",
                "message", "请使用司机端重新登录"
        ));
        return false;
    }
}
