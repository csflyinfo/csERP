package com.erp.auth;

import com.erp.common.api.ApiResponse;
import com.erp.common.security.CurrentUser;
import com.erp.common.util.JwtUtil;
import com.erp.common.util.RequestContext;
import com.erp.system.OperationLogService;
import com.erp.system.PasswordService;
import com.erp.system.SysParamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    /** 连续登录失败 5 次锁定账号。 */
    private static final int MAX_FAIL_COUNT = 5;
    /** 锁定时长 15 分钟。 */
    private static final long LOCK_MINUTES = 15;

    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;
    private final PasswordService passwordService;
    private final SysParamService sysParamService;
    private final OperationLogService opLog;

    public AuthController(JdbcTemplate jdbcTemplate, JwtUtil jwtUtil, BCryptPasswordEncoder passwordEncoder,
                          PasswordService passwordService, SysParamService sysParamService,
                          OperationLogService opLog) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.passwordService = passwordService;
        this.sysParamService = sysParamService;
        this.opLog = opLog;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                  jakarta.servlet.http.HttpServletRequest httpReq) {
        Map<String, Object> user = findUser(request.username());
        if (user == null) {
            writeLoginLog(null, request.username(), "FAIL", "账号不存在", httpReq);
            throw new IllegalArgumentException("账号或密码错误");
        }
        if (!"NORMAL".equals(String.valueOf(user.get("status")))) {
            writeLoginLog(strVal(user, "userId"), request.username(), "FAIL", "账号已停用", httpReq);
            throw new IllegalArgumentException("账号已停用，请联系管理员");
        }

        // 锁定判定：失败次数达阈值且锁定期未过（fail_count/lock_time 由失败分支累加，V102 新增列）
        int failCount = intVal(user.get("failCount"));
        Timestamp lockTime = (Timestamp) user.get("lockTime");
        if (failCount >= MAX_FAIL_COUNT && lockTime != null
                && lockTime.toInstant().plus(LOCK_MINUTES, ChronoUnit.MINUTES).isAfter(Instant.now())) {
            writeLoginLog(strVal(user, "userId"), request.username(), "FAIL", "账号已锁定", httpReq);
            throw new IllegalArgumentException("账号已锁定，请 " + LOCK_MINUTES + " 分钟后再试或联系管理员");
        }

        String storedPassword = strVal(user, "password");
        // 严格 BCrypt 校验，不再允许明文回退。DB 中存储必须是 BCrypt 哈希。
        boolean passwordMatch = !storedPassword.isBlank() && storedPassword.startsWith("$2a$")
                && passwordEncoder.matches(request.password(), storedPassword);
        if (!passwordMatch) {
            int nextFail = (failCount >= MAX_FAIL_COUNT ? 0 : failCount) + 1;
            // 达到阈值的这一次失败起重新计时 15 分钟
            Timestamp newLock = nextFail >= MAX_FAIL_COUNT ? Timestamp.from(Instant.now()) : null;
            jdbcTemplate.update("UPDATE sys_user_runtime SET fail_count = ?, lock_time = ? WHERE user_id = ?",
                    nextFail, newLock, user.get("userId"));
            writeLoginLog(strVal(user, "userId"), request.username(), "FAIL", "密码错误", httpReq);
            throw new IllegalArgumentException("账号或密码错误");
        }

        // 登录成功：清失败计数、回写最后登录时间/IP
        jdbcTemplate.update(
                "UPDATE sys_user_runtime SET fail_count = 0, lock_time = NULL, " +
                "last_login_time = CURRENT_TIMESTAMP, last_login_ip = ? WHERE user_id = ?",
                RequestContext.clientIp(httpReq), user.get("userId"));

        List<Map<String, Object>> roles = loadRoles(strVal(user, "userId"));
        if (roles.isEmpty()) {
            writeLoginLog(strVal(user, "userId"), request.username(), "FAIL", "账号未分配角色", httpReq);
            throw new IllegalArgumentException("账号未分配角色，请联系管理员");
        }
        List<String> roleCodes = roles.stream().map(r -> strVal(r, "roleCode")).toList();
        String primaryRoleCode = resolvePrimaryRoleCode(user, roles);

        String token = jwtUtil.generateToken(
                strVal(user, "userId"),
                request.username(),
                strVal(user, "displayName"),
                roleCodes,
                "ERP",
                null,
                blankToNull(strVal(user, "employeeId"))
        );
        writeLoginLog(strVal(user, "userId"), request.username(), "SUCCESS", null, httpReq);

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
        out.put("mustChangePwd", effectiveMustChange(db));
        out.put("pwdUpdateTime", db.get("pwd_update_time"));
        out.put("lastLoginTime", db.get("last_login_time"));
        out.put("lastLoginIp", db.get("last_login_ip"));
        out.put("roles", loadRoles(me.userId()));
        List<Map<String, Object>> whs = new ArrayList<>();
        jdbcTemplate.queryForList("""
                SELECT w.warehouse_id, w.warehouse_name, uw.is_primary
                FROM sys_user_warehouse uw
                JOIN base_warehouse w ON w.warehouse_id = uw.warehouse_id
                WHERE uw.user_id = ? ORDER BY w.warehouse_code
                """, me.userId()).forEach(r -> {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("warehouseId", r.get("warehouse_id"));
            w.put("warehouseName", r.get("warehouse_name"));
            w.put("isPrimary", r.get("is_primary"));
            whs.add(w);
        });
        out.put("warehouses", whs);
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
        Map<String, Object> user = findUser(username);
        if (user == null) {
            return ApiResponse.fail("404", "用户不存在");
        }
        List<Map<String, Object>> roles = loadRoles(strVal(user, "userId"));
        List<String> roleCodes = roles.stream().map(r -> strVal(r, "roleCode")).toList();
        String primaryRoleCode = resolvePrimaryRoleCode(user, roles);
        return ApiResponse.ok(buildUserInfo(user, roles, roleCodes, primaryRoleCode));
    }

    /** PRD-28：用户-角色多对多聚合（sys_user_role_rel × sys_role_runtime），只取启用角色。 */
    private List<Map<String, Object>> loadRoles(String userId) {
        // H2 列标签统一大写（CASE_INSENSITIVE_IDENTIFIERS），queryForList 原始 Map 直接序列化会变全大写键，
        // 这里显式重建驼峰键 Map 再返回给前端。
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT r.role_id, r.role_code, r.role_name, r.role_group, r.app_type
                FROM sys_user_role_rel rel
                JOIN sys_role_runtime r ON r.role_id = rel.role_id
                WHERE rel.user_id = ? AND r.status = 'NORMAL'
                ORDER BY r.role_code
                """, userId);
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> role = new LinkedHashMap<>();
            role.put("roleId", row.get("role_id"));
            role.put("roleCode", row.get("role_code"));
            role.put("roleName", row.get("role_name"));
            role.put("roleGroup", row.get("role_group"));
            role.put("appType", row.get("app_type"));
            roles.add(role);
        }
        return roles;
    }

    /** 主角色优先取用户表 primary_role_id 指定的角色，未指定则取第一个；兼容老前端 roleCode 字段。 */
    private String resolvePrimaryRoleCode(Map<String, Object> user, List<Map<String, Object>> roles) {
        String primaryRoleId = blankToNull(strVal(user, "primaryRoleId"));
        if (primaryRoleId != null) {
            for (Map<String, Object> r : roles) {
                if (primaryRoleId.equals(strVal(r, "roleId"))) return strVal(r, "roleCode");
            }
        }
        return strVal(roles.get(0), "roleCode");
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
        info.put("mustChangePwd", effectiveMustChange(user));
        return info;
    }

    /**
     * 强制改密 = 管理员重置标记 OR 密码超过有效期。
     * 参数 SEC_PWD_EXPIRE_DAYS 默认 0（永不过期）；密码改密时间缺失时按已过期处理。
     */
    private boolean effectiveMustChange(Map<String, Object> user) {
        if (Boolean.TRUE.equals(user.get("mustChangePwd"))) return true;
        int expireDays;
        try {
            expireDays = Integer.parseInt(sysParamService.get("SEC_PWD_EXPIRE_DAYS", "0").trim());
        } catch (NumberFormatException e) {
            expireDays = 0;
        }
        if (expireDays <= 0) return false;
        Object ts = user.get("pwdUpdateTime");
        if (ts == null) return true;
        Timestamp pwdTime = ts instanceof Timestamp t ? t : Timestamp.valueOf(String.valueOf(ts));
        return pwdTime.toInstant().plus(expireDays, ChronoUnit.DAYS).isBefore(Instant.now());
    }

    private Map<String, Object> findUser(String username) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT user_id userId, username, display_name displayName, password, status,
                       mobile, email, employee_id employeeId, primary_role_id primaryRoleId,
                       fail_count failCount, lock_time lockTime, must_change_pwd mustChangePwd,
                       pwd_update_time pwdUpdateTime
                FROM sys_user_runtime
                WHERE username = ?
                LIMIT 1
                """, username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 登录成功/失败写登录日志表 sys_login_log（PRD-31：与操作日志分表）。
     * 登录失败也记录（账号不存在时 userId 为 null），便于安全审计爆破行为。永不抛异常。
     */
    private void writeLoginLog(String userId, String account, String result,
                              String failReason, jakarta.servlet.http.HttpServletRequest req) {
        try {
            String userName = null;
            if (userId != null) {
                try {
                    List<String> names = jdbcTemplate.queryForList(
                            "SELECT display_name FROM sys_user_runtime WHERE user_id = ?",
                            String.class, userId);
                    if (!names.isEmpty()) userName = names.get(0);
                } catch (Exception ignored) {}
            }
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

    private String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private int intVal(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }
}
