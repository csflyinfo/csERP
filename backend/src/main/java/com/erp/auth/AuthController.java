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
        return ApiResponse.ok(Map.of(
                "token", "demo-token",
                "displayName", "系统管理员",
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
        return ApiResponse.ok(Map.of(
                "userId", "U0001",
                "username", "admin",
                "displayName", "系统管理员",
                "roles", List.of("ADMIN")
        ));
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
