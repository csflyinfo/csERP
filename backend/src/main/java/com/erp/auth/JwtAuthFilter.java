package com.erp.auth;

import com.erp.common.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 由 SecurityConfig 通过 addFilterBefore 挂到 Spring Security 过滤链，不作 @Component 以免被 Boot 二次注册。
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/auth/login",
            "/auth/logout",
            "/actuator/health",
            "/tms/app/login"
    );

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        final String normalizedPath = path.isBlank() ? "/" : path;
        return PUBLIC_PREFIXES.stream().anyMatch(normalizedPath::startsWith);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";

        if (!jwtUtil.validateToken(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "code", "401",
                    "message", "登录已过期，请重新登录"
            ));
            return;
        }

        Claims claims = jwtUtil.parseToken(token);
        String username = claims.getSubject();
        String roleCode = String.valueOf(claims.getOrDefault("roleCode", ""));

        request.setAttribute("currentUsername", username);
        request.setAttribute("currentRoleCode", roleCode);

        // 把角色写入 Spring Security 上下文，供 authorizeHttpRequests 授权规则使用
        List<SimpleGrantedAuthority> authorities = roleCode == null || roleCode.isBlank()
                ? List.of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + roleCode));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(username, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
