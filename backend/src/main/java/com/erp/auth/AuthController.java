package com.erp.auth;

import com.erp.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
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

    public AuthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        if (!"admin".equals(request.username()) || !"admin123".equals(request.password())) {
            log("LOGIN", request.username(), "FAIL", "账号或密码错误");
            throw new IllegalArgumentException("账号或密码错误");
        }
        log("LOGIN", request.username(), "SUCCESS", "用户登录成功");
        Map<String, Object> user = currentUserData();
        return ApiResponse.ok(Map.of(
                "token", "demo-token",
                "displayName", user.get("displayName"),
                "roleCode", user.get("roleCode"),
                "menuScope", user.get("menuScope"),
                "fieldScope", user.get("fieldScope"),
                "dataScope", user.get("dataScope"),
                "permissions", List.of("*")
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout() {
        log("LOGOUT", "admin", "SUCCESS", "用户退出登录");
        return ApiResponse.ok(true);
    }

    @GetMapping("/current-user")
    public ApiResponse<Map<String, Object>> currentUser() {
        return ApiResponse.ok(currentUserData());
    }

    private Map<String, Object> currentUserData() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT u.user_id userId, u.username, u.display_name displayName, r.role_code roleCode, r.role_name roleName,
                       r.menu_scope menuScope, r.field_scope fieldScope, COALESCE(r.data_scope, u.data_scope) dataScope
                FROM sys_user_runtime u
                LEFT JOIN sys_role_runtime r ON r.role_name = u.role_name
                WHERE u.username='admin'
                LIMIT 1
                """);
        if (rows.isEmpty()) {
            return Map.of("userId", "U0001", "username", "admin", "displayName", "系统管理员", "roles", List.of("ADMIN"), "roleCode", "ADMIN", "menuScope", "*", "fieldScope", "*", "dataScope", "ALL");
        }
        Map<String, Object> row = rows.get(0);
        return Map.of(
                "userId", row.get("USERID"),
                "username", row.get("USERNAME"),
                "displayName", row.get("DISPLAYNAME"),
                "roles", List.of(String.valueOf(row.getOrDefault("ROLECODE", "ADMIN"))),
                "roleCode", row.getOrDefault("ROLECODE", "ADMIN"),
                "roleName", row.getOrDefault("ROLENAME", "管理员组"),
                "menuScope", row.getOrDefault("MENUSCOPE", "*"),
                "fieldScope", row.getOrDefault("FIELDSCOPE", "*"),
                "dataScope", row.getOrDefault("DATASCOPE", "ALL")
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
