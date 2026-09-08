package com.erp.auth;

import com.erp.common.util.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 在请求进入 Controller 前填充 {@link RequestContext}（操作人账号、IP、URI、方法、UA、起始时间），
 * 请求结束后清理 ThreadLocal。
 *
 * <p>注册为普通 Servlet 过滤器（{@code @Component} 被 Boot 自动注册，顺序 LOWEST，即最内层、
 * 紧贴 DispatcherServlet）。这样执行到 Controller 时 Spring Security 链已跑完，
 * {@code JwtAuthFilter} 设置的 currentUsername 属性与 SecurityContext 均已就绪；
 * 本过滤器只负责把 HTTP 级信息存入 ThreadLocal 并在 finally 清理，任何异常都不外抛。
 */
@Component("erpRequestContextFilter")
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String account = resolveAccount(request);
            RequestContext.set(new RequestContext.Ctx(
                    account,
                    blankToNull(request.getAttribute("currentUserId")),
                    blankToNull(request.getAttribute("currentDisplayName")),
                    RequestContext.clientIp(request),
                    request.getRequestURI(),
                    request.getMethod(),
                    request.getHeader("User-Agent"),
                    System.currentTimeMillis()
            ));
            filterChain.doFilter(request, response);
        } catch (Throwable t) {
            // 不能因上下文填充失败而中断业务请求
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    /** 把空白/字面量 "null" 的属性值归一为 null，避免日志里出现 "null" 字符串。 */
    private String blankToNull(Object value) {
        if (value == null) return null;
        String s = value.toString().trim();
        return (s.isEmpty() || "null".equalsIgnoreCase(s)) ? null : s;
    }

    /** 优先取 JwtAuthFilter 写入的 currentUsername 属性，回落 SecurityContext 中的登录名。 */
    private String resolveAccount(HttpServletRequest request) {
        try {
            Object attr = request.getAttribute("currentUsername");
            if (attr != null && !attr.toString().isBlank()) {
                return attr.toString();
            }
        } catch (Exception ignored) {
            // 容器不支持 attribute 时忽略，走 SecurityContext
        }
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
                return auth.getName();
            }
        } catch (Exception ignored) {
            // 无安全上下文（如 /auth/login 公共请求）
        }
        return null;
    }
}
