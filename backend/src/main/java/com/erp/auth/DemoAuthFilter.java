package com.erp.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Component
public class DemoAuthFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/auth/login",
            "/auth/logout",
            "/actuator/health",
            "/h2-console"
    );

    private final ObjectMapper objectMapper;
    private final String demoToken;

    public DemoAuthFilter(ObjectMapper objectMapper, @Value("${app.security.demo-token:demo-token}") String demoToken) {
        this.objectMapper = objectMapper;
        this.demoToken = demoToken;
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
        if (!demoToken.equals(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "code", "401",
                    "message", "登录已过期，请重新登录"
            ));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
