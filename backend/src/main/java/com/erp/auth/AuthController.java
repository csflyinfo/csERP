package com.erp.auth;

import com.erp.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        if (!"admin".equals(request.username()) || !"admin123".equals(request.password())) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        return ApiResponse.ok(Map.of(
                "token", "demo-token",
                "displayName", "系统管理员",
                "permissions", List.of("*")
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout() {
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

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}
