package com.erp.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * WMS PDA 端类型隔离拦截器（PRD-28 卡片9）。
 *
 * <p>/wms/app/**（登录端点除外）只接受 appType=WMS_PDA 的令牌：ERP 后台令牌、司机端
 * DRIVER 令牌、以及 V102 前不带 appType 的老令牌一律 401，强制走 PDA 登录（选仓）重新签发。
 * 功能点级授权仍由 {@link RequirePermInterceptor} 负责，本拦截器只做端类型校验。
 */
@Component
public class PdaAppGuardInterceptor implements HandlerInterceptor {

    public static final String LOGIN_PATH = "/wms/app/login";

    private final ObjectMapper objectMapper;

    public PdaAppGuardInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        CurrentUser.Principal user = CurrentUser.get();
        if (user != null && user.isPda()) return true;
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "code", "401",
                "message", "请使用 PDA 重新登录（选择作业仓库）"
        ));
        return false;
    }
}
