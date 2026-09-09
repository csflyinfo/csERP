package com.erp.auth;

import com.erp.common.security.CurrentUser;
import com.erp.common.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
        String userId = String.valueOf(claims.getOrDefault("userId", ""));
        String displayName = String.valueOf(claims.getOrDefault("displayName", ""));
        String employeeId = claims.get("employeeId") == null ? null : String.valueOf(claims.get("employeeId"));
        String appType = claims.get("appType") == null ? "ERP" : String.valueOf(claims.get("appType"));
        String warehouseId = claims.get("warehouseId") == null ? null : String.valueOf(claims.get("warehouseId"));

        // PRD-28：优先读多角色声明 roleCodes，回落老版本单角色 roleCode
        Set<String> roleCodes = new LinkedHashSet<>();
        Object rawCodes = claims.get("roleCodes");
        if (rawCodes instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) roleCodes.add(o.toString());
            }
        }
        String legacyRole = String.valueOf(claims.getOrDefault("roleCode", ""));
        if (!legacyRole.isBlank()) roleCodes.add(legacyRole);
        // V102 前签发的管理员令牌角色码还是 ADMIN，归一为新的 SYS_ADMIN，保证老令牌在新权限门下仍可用
        if (roleCodes.remove("ADMIN")) roleCodes.add("SYS_ADMIN");

        String primaryRoleCode = roleCodes.stream().findFirst().orElse("");

        request.setAttribute("currentUsername", username);
        request.setAttribute("currentRoleCode", primaryRoleCode);
        // 供操作日志读取真实操作人 ID 与姓名（RequestContextFilter 消费）
        request.setAttribute("currentUserId", userId);
        request.setAttribute("currentDisplayName", displayName);

        // PRD-28：填充线程级当前用户，供 RequirePerm 拦截器 / 数据权限 / 业务层统一取用
        CurrentUser.set(CurrentUser.of(userId, username, displayName, employeeId,
                roleCodes, primaryRoleCode, appType, warehouseId));

        // 把角色写入 Spring Security 上下文，供 authorizeHttpRequests 授权规则使用
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String code : roleCodes) authorities.add(new SimpleGrantedAuthority("ROLE_" + code));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(username, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            CurrentUser.clear();
        }
    }
}
