package com.erp.auth;

import com.erp.common.api.ApiResponse;
import com.erp.common.util.JwtUtil;
import com.erp.common.util.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthController(JdbcTemplate jdbcTemplate, JwtUtil jwtUtil, BCryptPasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                  jakarta.servlet.http.HttpServletRequest httpReq) {
        Map<String, Object> user = findUser(request.username());
        if (user == null) {
            writeLoginLog(null, request.username(), "FAIL", "账号不存在", httpReq);
            throw new IllegalArgumentException("账号或密码错误");
        }
        String storedPassword = (String) user.getOrDefault("password", "");
        // 严格 BCrypt 校验，不再允许明文回退。DB 中存储必须是 BCrypt 哈希。
        boolean passwordMatch = storedPassword != null && storedPassword.startsWith("$2a$")
                && passwordEncoder.matches(request.password(), storedPassword);
        if (!passwordMatch) {
            writeLoginLog(user, request.username(), "FAIL", "密码错误", httpReq);
            throw new IllegalArgumentException("账号或密码错误");
        }

        String token = jwtUtil.generateToken(
                String.valueOf(user.get("userId")),
                request.username(),
                String.valueOf(user.get("displayName")),
                String.valueOf(user.getOrDefault("roleCode", ""))
        );
        writeLoginLog(user, request.username(), "SUCCESS", null, httpReq);

        return ApiResponse.ok(Map.of(
                "token", token,
                "user", Map.of(
                        "userId", user.get("userId"),
                        "username", user.get("username"),
                        "displayName", user.get("displayName"),
                        "roleCode", user.get("roleCode"),
                        "roleName", user.get("roleName"),
                        "menuScope", user.get("menuScope"),
                        "fieldScope", user.get("fieldScope"),
                        "dataScope", user.get("dataScope")
                )
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(jakarta.servlet.http.HttpServletRequest httpReq) {
        // 登出是公开端点（JwtAuthFilter 跳过），这里尽力从 token 解析账号，回填最近一条登录记录的 logout_at
        String account = accountFromToken(httpReq);
        if (account != null) {
            try {
                jdbcTemplate.update(
                        "UPDATE sys_login_log SET logout_at = CURRENT_TIMESTAMP WHERE login_log_id = " +
                        "(SELECT login_log_id FROM sys_login_log WHERE account = ? AND logout_at IS NULL " +
                        "ORDER BY login_at DESC LIMIT 1)", account);
            } catch (Exception ignored) {}
        }
        return ApiResponse.ok(true);
    }

    @GetMapping("/current-user")
    public ApiResponse<Map<String, Object>> currentUser(jakarta.servlet.http.HttpServletRequest request) {
        String username = (String) request.getAttribute("currentUsername");
        if (username == null) {
            return ApiResponse.fail("401", "未登录");
        }
        Map<String, Object> user = findUser(username);
        if (user == null) {
            return ApiResponse.fail("404", "用户不存在");
        }
        return ApiResponse.ok(user);
    }

    private Map<String, Object> findUser(String username) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT u.user_id userId, u.username, u.display_name displayName, u.password,
                       r.role_code roleCode, r.role_name roleName,
                       r.menu_scope menuScope, r.field_scope fieldScope, COALESCE(r.data_scope, u.data_scope) dataScope
                FROM sys_user_runtime u
                LEFT JOIN sys_role_runtime r ON r.role_name = u.role_name
                WHERE u.username = ? AND u.status = 'NORMAL'
                LIMIT 1
                """, username);
        if (rows.isEmpty()) {
            if ("admin".equals(username)) {
                return Map.of(
                        "userId", "U0001",
                        "username", "admin",
                        "displayName", "系统管理员",
                        "password", "admin123",
                        "roles", List.of("ADMIN"),
                        "roleCode", "ADMIN",
                        "roleName", "管理员组",
                        "menuScope", "*",
                        "fieldScope", "*",
                        "dataScope", "ALL"
                );
            }
            return null;
        }
        Map<String, Object> row = rows.get(0);
        return Map.of(
                "userId", row.getOrDefault("userId", "U0001"),
                "username", row.getOrDefault("username", username),
                "displayName", row.getOrDefault("displayName", username),
                "password", row.getOrDefault("password", ""),
                "roles", List.of(String.valueOf(row.getOrDefault("roleCode", "ADMIN"))),
                "roleCode", row.getOrDefault("roleCode", "ADMIN"),
                "roleName", row.getOrDefault("roleName", "管理员组"),
                "menuScope", row.getOrDefault("menuScope", "*"),
                "fieldScope", row.getOrDefault("fieldScope", "*"),
                "dataScope", row.getOrDefault("dataScope", "ALL")
        );
    }

    /**
     * 登录成功/失败写登录日志表 sys_login_log（PRD-31：与操作日志分表）。
     * 登录失败也记录（账号不存在时 user 为 null），便于安全审计爆破行为。永不抛异常。
     */
    private void writeLoginLog(Map<String, Object> user, String account, String result,
                              String failReason, jakarta.servlet.http.HttpServletRequest req) {
        try {
            String userId = user == null ? null : blankToNull(String.valueOf(user.getOrDefault("userId", "")));
            String userName = user == null ? null : blankToNull(String.valueOf(user.getOrDefault("displayName", "")));
            String ua = req.getHeader("User-Agent");
            jdbcTemplate.update(
                    "INSERT INTO sys_login_log(login_log_id, user_id, account, user_name, login_at, " +
                    "login_result, fail_reason, ip, user_agent, app_type) " +
                    "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, 'ERP')",
                    "LL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                    userId, account, userName, result, failReason,
                    RequestContext.clientIp(req),
                    ua == null ? null : (ua.length() > 500 ? ua.substring(0, 500) : ua));
        } catch (Exception ignored) {}
    }

    /** 公开登出端点拿不到 SecurityContext，尽力从 Authorization 头解析账号。 */
    private String accountFromToken(jakarta.servlet.http.HttpServletRequest req) {
        try {
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String token = auth.substring(7);
                if (jwtUtil.validateToken(token)) {
                    return jwtUtil.parseToken(token).getSubject();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return (t.isEmpty() || "null".equalsIgnoreCase(t)) ? null : t;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}
