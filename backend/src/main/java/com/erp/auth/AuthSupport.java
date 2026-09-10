package com.erp.auth;

import com.erp.common.util.RequestContext;
import com.erp.system.PasswordService;
import com.erp.system.SysParamService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 账号密码认证公共支撑（PRD-28 卡片9 抽取）。
 *
 * <p>{@link AuthController}（ERP 后台）与 {@code WmsPdaAuthService}（仓库 PDA）共用同一套：
 * 用户查询、停用/锁定判定、BCrypt 校验、失败计数、登录成功回写、角色加载、强制改密判定、
 * 登录日志（app_type 区分端）。规则与卡片4 V102 上线时完全一致，PDA 端不允许明文回退。
 */
@Service
public class AuthSupport {

    /** 连续登录失败 5 次锁定账号。 */
    public static final int MAX_FAIL_COUNT = 5;
    /** 锁定时长 15 分钟。 */
    public static final long LOCK_MINUTES = 15;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordService passwordService;
    private final SysParamService sysParamService;

    public AuthSupport(JdbcTemplate jdbcTemplate, PasswordService passwordService,
                       SysParamService sysParamService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordService = passwordService;
        this.sysParamService = sysParamService;
    }

    /** 按登录名查账号（PDA 允许用工号：先 username 后 base_employee.employee_code）。找不到返回 null。 */
    public Map<String, Object> findLoginUser(String loginName) {
        Map<String, Object> user = findByUsername(loginName);
        if (user != null) return user;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT u.user_id userId, u.username, u.display_name displayName, u.password, u.status,
                       u.mobile, u.email, u.employee_id employeeId, u.primary_role_id primaryRoleId,
                       u.fail_count failCount, u.lock_time lockTime, u.must_change_pwd mustChangePwd,
                       u.pwd_update_time pwdUpdateTime
                FROM sys_user_runtime u
                JOIN base_employee e ON e.employee_id = u.employee_id
                WHERE e.employee_code = ?
                LIMIT 1
                """, loginName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 严格按 username 查账号（ERP 登录口径，不允许工号回落）。 */
    public Map<String, Object> findByUsername(String username) {
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
     * 登录全部门禁：停用 → 锁定 → 密码（失败累加计数/上锁）。
     * 全部失败抛 IllegalArgumentException（全局转业务错误提示），失败写登录日志。
     */
    public void verifyLoginOrThrow(Map<String, Object> user, String loginName, String rawPassword,
                                   jakarta.servlet.http.HttpServletRequest httpReq, String appType) {
        if (!"NORMAL".equals(String.valueOf(user.get("status")))) {
            writeLoginLog(strVal(user, "userId"), loginName, "FAIL", "账号已停用", httpReq, appType);
            throw new IllegalArgumentException("账号已停用，请联系管理员");
        }
        int failCount = intVal(user.get("failCount"));
        Timestamp lockTime = (Timestamp) user.get("lockTime");
        if (failCount >= MAX_FAIL_COUNT && lockTime != null
                && lockTime.toInstant().plus(LOCK_MINUTES, ChronoUnit.MINUTES).isAfter(Instant.now())) {
            writeLoginLog(strVal(user, "userId"), loginName, "FAIL", "账号已锁定", httpReq, appType);
            throw new IllegalArgumentException("账号已锁定，请 " + LOCK_MINUTES + " 分钟后再试或联系管理员");
        }
        String storedPassword = strVal(user, "password");
        if (!passwordService.matches(rawPassword, storedPassword)) {
            int nextFail = (failCount >= MAX_FAIL_COUNT ? 0 : failCount) + 1;
            // 达到阈值的这一次失败起重新计时 15 分钟
            Timestamp newLock = nextFail >= MAX_FAIL_COUNT ? Timestamp.from(Instant.now()) : null;
            jdbcTemplate.update("UPDATE sys_user_runtime SET fail_count = ?, lock_time = ? WHERE user_id = ?",
                    nextFail, newLock, user.get("userId"));
            writeLoginLog(strVal(user, "userId"), loginName, "FAIL", "密码错误", httpReq, appType);
            throw new IllegalArgumentException("账号或密码错误");
        }
    }

    /** 登录成功：清失败计数、回写最后登录时间/IP。 */
    public void afterLoginSuccess(Map<String, Object> user, jakarta.servlet.http.HttpServletRequest httpReq) {
        jdbcTemplate.update(
                "UPDATE sys_user_runtime SET fail_count = 0, lock_time = NULL, " +
                        "last_login_time = CURRENT_TIMESTAMP, last_login_ip = ? WHERE user_id = ?",
                RequestContext.clientIp(httpReq), user.get("userId"));
    }

    /** PRD-28：用户-角色多对多聚合（sys_user_role_rel × sys_role_runtime），只取启用角色。 */
    public List<Map<String, Object>> loadRoles(String userId) {
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

    /** 主角色优先取用户表 primary_role_id 指定的角色，未指定则取第一个。 */
    public String resolvePrimaryRoleCode(Map<String, Object> user, List<Map<String, Object>> roles) {
        String primaryRoleId = blankToNull(strVal(user, "primaryRoleId"));
        if (primaryRoleId != null) {
            for (Map<String, Object> r : roles) {
                if (primaryRoleId.equals(strVal(r, "roleId"))) return strVal(r, "roleCode");
            }
        }
        return roles.isEmpty() ? "" : strVal(roles.get(0), "roleCode");
    }

    /** 用户绑定仓库列表（PDA 登录选仓）。 */
    public List<Map<String, Object>> warehouses(String userId) {
        List<Map<String, Object>> whs = new ArrayList<>();
        jdbcTemplate.queryForList("""
                SELECT w.warehouse_id, w.warehouse_code, w.warehouse_name, uw.is_primary
                FROM sys_user_warehouse uw
                JOIN base_warehouse w ON w.warehouse_id = uw.warehouse_id
                WHERE uw.user_id = ? AND w.status = 'NORMAL'
                ORDER BY uw.is_primary DESC, w.warehouse_code
                """, userId).forEach(r -> {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("warehouseId", r.get("warehouse_id"));
            w.put("warehouseCode", r.get("warehouse_code"));
            w.put("warehouseName", r.get("warehouse_name"));
            w.put("isPrimary", r.get("is_primary"));
            whs.add(w);
        });
        return whs;
    }

    /**
     * 强制改密 = 管理员重置标记 OR 密码超过有效期。
     * 参数 SEC_PWD_EXPIRE_DAYS 默认 0（永不过期）；密码改密时间缺失时按已过期处理。
     */
    public boolean effectiveMustChange(Map<String, Object> user) {
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

    /**
     * 登录成功/失败写登录日志表 sys_login_log（PRD-31：与操作日志分表）。
     * 登录失败也记录（账号不存在时 userId 为 null）。永不抛异常。
     */
    public void writeLoginLog(String userId, String account, String result, String failReason,
                              jakarta.servlet.http.HttpServletRequest req, String appType) {
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
                            "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?)",
                    "LL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                    userId, account, userName, result, failReason,
                    RequestContext.clientIp(req),
                    ua == null ? null : (ua.length() > 500 ? ua.substring(0, 500) : ua),
                    appType);
        } catch (Exception ignored) {}
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
}
