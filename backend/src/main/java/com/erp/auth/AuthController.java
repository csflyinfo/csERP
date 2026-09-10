package com.erp.auth;

import com.erp.common.api.ApiResponse;
import com.erp.common.security.CurrentUser;
import com.erp.common.util.JwtUtil;
import com.erp.system.OperationLogService;
import com.erp.system.PasswordService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ERP 后台账号密码登录（PRD-28）。
 *
 * <p>认证门禁（停用/锁定/BCrypt/失败计数/日志）统一走 {@link AuthSupport}，
 * 与 PDA 登录（{@code WmsPdaAuthService}）同一套规则；本控制器只负责 ERP 令牌签发。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;
    private final PasswordService passwordService;
    private final AuthSupport authSupport;
    private final OperationLogService opLog;

    public AuthController(JdbcTemplate jdbcTemplate, JwtUtil jwtUtil, PasswordService passwordService,
                          AuthSupport authSupport, OperationLogService opLog) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
        this.passwordService = passwordService;
        this.authSupport = authSupport;
        this.opLog = opLog;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                  jakarta.servlet.http.HttpServletRequest httpReq) {
        Map<String, Object> user = authSupport.findByUsername(request.username());
        if (user == null) {
            authSupport.writeLoginLog(null, request.username(), "FAIL", "账号不存在", httpReq, "ERP");
            throw new IllegalArgumentException("账号或密码错误");
        }
        authSupport.verifyLoginOrThrow(user, request.username(), request.password(), httpReq, "ERP");

        // 登录成功：清失败计数、回写最后登录时间/IP
        authSupport.afterLoginSuccess(user, httpReq);

        List<Map<String, Object>> roles = authSupport.loadRoles(strVal(user, "userId"));
        if (roles.isEmpty()) {
            authSupport.writeLoginLog(strVal(user, "userId"), request.username(), "FAIL", "账号未分配角色", httpReq, "ERP");
            throw new IllegalArgumentException("账号未分配角色，请联系管理员");
        }
        List<String> roleCodes = roles.stream().map(r -> strVal(r, "roleCode")).toList();
        String primaryRoleCode = authSupport.resolvePrimaryRoleCode(user, roles);

        String token = jwtUtil.generateToken(
                strVal(user, "userId"),
                request.username(),
                strVal(user, "displayName"),
                roleCodes,
                "ERP",
                null,
                blankToNull(strVal(user, "employeeId"))
        );
        authSupport.writeLoginLog(strVal(user, "userId"), request.username(), "SUCCESS", null, httpReq, "ERP");

        return ApiResponse.ok(Map.of(
                "token", token,
                "user", buildUserInfo(user, roles, roleCodes, primaryRoleCode)
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

    /**
     * 个人中心信息（PRD-28 §10.5）：任何登录用户可取自己的资料，含绑定仓库与改密时间。
     */
    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile() {
        CurrentUser.Principal me = CurrentUser.get();
        if (me == null || me.userId() == null) {
            return ApiResponse.fail("401", "未登录");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT user_id, username, display_name, mobile, email, employee_id,
                       must_change_pwd, pwd_update_time, last_login_time, last_login_ip
                FROM sys_user_runtime WHERE user_id = ?
                """, me.userId());
        if (rows.isEmpty()) return ApiResponse.fail("404", "用户不存在");
        Map<String, Object> db = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", db.get("user_id"));
        out.put("username", db.get("username"));
        out.put("displayName", db.get("display_name"));
        out.put("mobile", db.get("mobile"));
        out.put("email", db.get("email"));
        out.put("employeeId", db.get("employee_id"));
        out.put("mustChangePwd", authSupport.effectiveMustChange(authSupport.findByUsername(me.username())));
        out.put("pwdUpdateTime", db.get("pwd_update_time"));
        out.put("lastLoginTime", db.get("last_login_time"));
        out.put("lastLoginIp", db.get("last_login_ip"));
        out.put("roles", authSupport.loadRoles(me.userId()));
        out.put("warehouses", authSupport.warehouses(me.userId()));
        return ApiResponse.ok(out);
    }

    /**
     * 修改自己的密码（§8.4）：校验旧密码 → 强度 → 近 3 次历史 → 更新哈希/改密时间/首登标志 → 写历史。
     */
    @PostMapping("/change-password")
    @Transactional
    public ApiResponse<Map<String, Object>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        CurrentUser.Principal me = CurrentUser.get();
        if (me == null || me.userId() == null) {
            return ApiResponse.fail("401", "未登录");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT username, display_name, password FROM sys_user_runtime WHERE user_id = ?", me.userId());
        if (rows.isEmpty()) return ApiResponse.fail("404", "用户不存在");
        Map<String, Object> db = rows.get(0);
        if (!passwordService.matches(request.oldPassword(), strVal(db, "password"))) {
            opLog.logFail("system.user", "CHANGE_PASSWORD", strVal(db, "username"), "原密码错误");
            throw new IllegalArgumentException("原密码不正确");
        }
        if (request.newPassword() == null || request.newPassword().isBlank()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        if (request.newPassword().equals(request.oldPassword())) {
            throw new IllegalArgumentException("新密码不能与原密码相同");
        }
        passwordService.validateStrength(request.newPassword());
        passwordService.assertNotReused(me.userId(), request.newPassword());

        String encoded = passwordService.encode(request.newPassword());
        jdbcTemplate.update("UPDATE sys_user_runtime SET password = ?, must_change_pwd = FALSE, "
                + "pwd_update_time = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                encoded, me.userId());
        passwordService.recordHistory(me.userId(), encoded);
        opLog.logSensitive("system.user", "CHANGE_PASSWORD", "用户", me.userId(), me.username(),
                "用户「" + strVal(db, "display_name") + "」自助修改密码");
        return ApiResponse.ok(Map.of("success", true));
    }

    @GetMapping("/current-user")
    public ApiResponse<Map<String, Object>> currentUser(jakarta.servlet.http.HttpServletRequest request) {
        String username = (String) request.getAttribute("currentUsername");
        if (username == null) {
            return ApiResponse.fail("401", "未登录");
        }
        Map<String, Object> user = authSupport.findByUsername(username);
        if (user == null) {
            return ApiResponse.fail("404", "用户不存在");
        }
        List<Map<String, Object>> roles = authSupport.loadRoles(strVal(user, "userId"));
        List<String> roleCodes = roles.stream().map(r -> strVal(r, "roleCode")).toList();
        String primaryRoleCode = authSupport.resolvePrimaryRoleCode(user, roles);
        return ApiResponse.ok(buildUserInfo(user, roles, roleCodes, primaryRoleCode));
    }

    private Map<String, Object> buildUserInfo(Map<String, Object> user, List<Map<String, Object>> roles,
                                              List<String> roleCodes, String primaryRoleCode) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("userId", user.get("userId"));
        info.put("username", user.get("username"));
        info.put("displayName", user.get("displayName"));
        info.put("employeeId", blankToNull(strVal(user, "employeeId")));
        info.put("mobile", blankToNull(strVal(user, "mobile")));
        info.put("email", blankToNull(strVal(user, "email")));
        info.put("roles", roles);
        info.put("roleCodes", roleCodes);
        info.put("primaryRoleCode", primaryRoleCode);
        // 兼容字段：卡片8 前端改造完成前仍下发单角色 roleCode/roleName
        info.put("roleCode", primaryRoleCode);
        info.put("roleName", roles.stream()
                .filter(r -> primaryRoleCode.equals(strVal(r, "roleCode")))
                .map(r -> strVal(r, "roleName")).findFirst().orElse(""));
        // PRD-28：前端据此强制弹改密页；密码超有效期（SEC_PWD_EXPIRE_DAYS>0）同样强制
        info.put("mustChangePwd", authSupport.effectiveMustChange(user));
        return info;
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

    private String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }
}
