package com.erp.auth;

import com.erp.common.api.ApiResponse;
import com.erp.common.util.JwtUtil;
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
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        Map<String, Object> user = findUser(request.username());
        if (user == null) {
            log("LOGIN", request.username(), "FAIL", "账号不存在");
            throw new IllegalArgumentException("账号或密码错误");
        }
        String storedPassword = (String) user.getOrDefault("password", "");
        // 严格 BCrypt 校验，不再允许明文回退。DB 中存储必须是 BCrypt 哈希。
        boolean passwordMatch = storedPassword != null && storedPassword.startsWith("$2a$")
                && passwordEncoder.matches(request.password(), storedPassword);
        if (!passwordMatch) {
            log("LOGIN", request.username(), "FAIL", "密码错误");
            throw new IllegalArgumentException("账号或密码错误");
        }

        String token = jwtUtil.generateToken(
                String.valueOf(user.get("userId")),
                request.username(),
                String.valueOf(user.get("displayName")),
                String.valueOf(user.getOrDefault("roleCode", ""))
        );
        log("LOGIN", request.username(), "SUCCESS", "用户登录成功");

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
    public ApiResponse<Boolean> logout() {
        log("LOGOUT", "admin", "SUCCESS", "用户退出登录");
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

    private void log(String action, String bizNo, String result, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', 'auth', ?, ?, ?, ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), action, bizNo, result, detail);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}
