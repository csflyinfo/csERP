package com.erp.common.config;

import com.erp.auth.JwtAuthFilter;
import com.erp.common.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 授权策略（PRD-28）：
 * - /auth/login, /auth/logout, /actuator/**、H2 console：公开
 * - /system/** ：仅 SYS_ADMIN 可访问（V102 前的老令牌角色码 ADMIN 已在 JwtAuthFilter 归一）
 * - 其它接口：必须登录（JwtAuthFilter 已把角色写入 SecurityContext）
 */
@Configuration
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter() {
        return new JwtAuthFilter(jwtUtil, objectMapper);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/auth/login", "/auth/logout", "/actuator/**", "/h2-console/**").permitAll()
                    .requestMatchers("/auth/sms/send", "/tms/app/login", "/wms/app/login").permitAll()
                    // 危险端点（冒烟清库 / 业务流程跑批）仅 SYS_ADMIN，防止司机端 888888 登录后越权
                    .requestMatchers("/testing/**", "/flow/**").hasRole("SYS_ADMIN")
                    // 自助查询：任何登录用户只能取「自己的」菜单/权限（服务端按 CurrentUser 过滤），
                    // 必须排在 /system/** 全量封禁之前（先匹配先生效）
                    .requestMatchers("/system/menu/user-tree", "/system/perm/mine").authenticated()
                    // 通知/待办是所有登录用户的个人中心能力，不属于系统管理，同样排在 /system/** 封禁之前
                    .requestMatchers("/system/notification/**", "/system/todo/**").authenticated()
                    .requestMatchers("/system/**").hasRole("SYS_ADMIN")
                    .anyRequest().authenticated())
            .headers(h -> h
                    // H2 console 走 iframe，必须 SAMEORIGIN；不能 DENY
                    .frameOptions(fo -> fo.sameOrigin())
                    // 浏览器不得对响应做 MIME 嗅探（防上传脚本被当 HTML/JS 执行）
                    .contentTypeOptions(cta -> {})
                    // 基础 CSP：本服务只出 JSON（与 H2 console 调试页），前端由独立静态域承载。
                    // frame-ancestors/base-uri/form-action 防点击劫持与基标签注入；
                    // script-src 放开 unsafe-inline 仅为兼容 H2 console 内联脚本——生产环境 H2 console 应关闭。
                    .contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'self'; img-src 'self' data: blob:; "
                            + "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
                            + "connect-src 'self'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'"))
                    // 仅在 HTTPS 下下发（开发环境 http 自动忽略），防止协议降级与 Cookie 劫持
                    .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
            .addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
