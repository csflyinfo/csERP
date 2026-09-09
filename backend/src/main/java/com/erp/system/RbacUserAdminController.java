package com.erp.system;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.security.CurrentUser;
import com.erp.common.security.RequirePerm;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 用户管理（PRD-28 §10.1）。
 *
 * <p>SecurityConfig 已把 /system/** 限定 SYS_ADMIN，这里再以 {@link RequirePerm} 声明功能点：
 * 功能点同步进 sys_func_meta 供健康检查/未来授权使用，超管在拦截器内短路放行。
 * 支持：多角色、主角色、绑定员工（唯一）、多绑定仓、启用/停用、解锁、重置密码（原因+敏感日志）、
 * 用户层七维数据范围收窄（与角色结果取交集）。不提供删除（账号只停用）。
 */
@RestController
@RequestMapping("/system/rbac/user")
public class RbacUserAdminController {

    /** 与 AuthController 锁定策略保持一致：连续失败 5 次锁 15 分钟。 */
    private static final int MAX_FAIL_COUNT = 5;
    private static final long LOCK_MINUTES = 15;
    private static final Set<String> SCOPE_TYPES = Set.of(
            "WAREHOUSE", "CUSTOMER", "SUPPLIER", "SALESMAN", "OWNER", "GOODS_CATEGORY", "BRAND");

    private final JdbcTemplate jdbc;
    private final OperationLogService opLog;
    private final PasswordService passwordService;

    public RbacUserAdminController(JdbcTemplate jdbc, OperationLogService opLog,
                                   PasswordService passwordService) {
        this.jdbc = jdbc;
        this.opLog = opLog;
        this.passwordService = passwordService;
    }

    // ==================== 列表 / 详情 ====================

    @PostMapping("/page")
    @RequirePerm("system.user.view")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT user_id, username, display_name, mobile, email, employee_id,
                       primary_role_id, status, is_system, must_change_pwd,
                       last_login_time, last_login_ip, fail_count, lock_time,
                       created_by, created_at, remark
                FROM sys_user_runtime
                ORDER BY is_system DESC, username
                """);
        String keyword = request.keyword();
        String statusFilter = filterStr(request, "status");
        String roleFilter = filterStr(request, "roleId");
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> user = toCamel(row);
            String userId = str(row.get("user_id"));
            List<Map<String, Object>> roles = loadRoles(userId);
            List<Map<String, Object>> warehouses = loadWarehouses(userId);
            user.put("roles", roles);
            user.put("warehouses", warehouses);
            user.put("primaryRole", pickPrimaryRole(roles, str(row.get("primary_role_id"))));
            user.put("locked", isLocked(num(row.get("fail_count")), (Timestamp) row.get("lock_time")));
            user.put("scopeTypes", jdbc.queryForList(
                    "SELECT scope_type FROM sys_user_data_scope WHERE user_id = ? ORDER BY scope_type",
                    String.class, userId));

            if (statusFilter != null && !statusFilter.isBlank()
                    && !statusFilter.equalsIgnoreCase(str(row.get("status")))) {
                continue;
            }
            if (roleFilter != null && !roleFilter.isBlank()
                    && roles.stream().noneMatch(r -> roleFilter.equals(str(r.get("roleId"))))) {
                continue;
            }
            if (keyword != null && !keyword.isBlank()) {
                String haystack = (str(row.get("username")) + " " + str(row.get("display_name"))
                        + " " + str(row.get("mobile")) + " "
                        + roles.stream().map(r -> str(r.get("roleName"))).reduce("", String::concat)).toLowerCase();
                if (!haystack.contains(keyword.toLowerCase())) continue;
            }
            all.add(user);
        }
        return ApiResponse.ok(PageResult.of(all, request));
    }

    @GetMapping("/{userId}")
    @RequirePerm("system.user.view")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT user_id, username, display_name, mobile, email, employee_id,
                       primary_role_id, status, is_system, must_change_pwd,
                       pwd_update_time, last_login_time, last_login_ip,
                       fail_count, lock_time, created_by, created_at, remark
                FROM sys_user_runtime WHERE user_id = ?
                """, userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("用户不存在");
        Map<String, Object> user = toCamel(rows.get(0));
        List<Map<String, Object>> roles = loadRoles(userId);
        user.put("roles", roles);
        user.put("warehouses", loadWarehouses(userId));
        user.put("primaryRole", pickPrimaryRole(roles, str(rows.get(0).get("primary_role_id"))));
        List<Map<String, Object>> scopes = new ArrayList<>();
        jdbc.queryForList("SELECT scope_type, scope_value FROM sys_user_data_scope "
                + "WHERE user_id = ? ORDER BY scope_type", userId).forEach(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scopeType", r.get("scope_type"));
            m.put("scopeValue", r.get("scope_value"));
            scopes.add(m);
        });
        user.put("dataScopes", scopes);
        // 员工冗余名（列表抽屉回显）
        String employeeId = str(rows.get(0).get("employee_id"));
        if (!employeeId.isEmpty()) {
            List<String> names = jdbc.queryForList(
                    "SELECT employee_name FROM base_employee WHERE employee_id = ?", String.class, employeeId);
            user.put("employeeName", names.isEmpty() ? null : names.get(0));
        }
        return ApiResponse.ok(user);
    }

    // ==================== 新建 / 编辑 ====================

    @PostMapping("/create")
    @RequirePerm("system.user.add")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String username = required(body, "username", "账号");
        String displayName = required(body, "displayName", "姓名");
        String password = required(body, "password", "初始密码");
        passwordService.validateStrength(password);

        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_user_runtime WHERE username = ?", Integer.class, username);
        if (exists != null && exists > 0) throw new IllegalArgumentException("账号已存在：" + username);

        List<String> roleIds = normalizeIdList(body.get("roleIds"));
        if (roleIds.isEmpty()) throw new IllegalArgumentException("请至少选择一个角色");
        assertRolesExist(roleIds);
        String primaryRoleId = resolvePrimaryRoleId(body, roleIds);
        String employeeId = str(body.get("employeeId"));
        assertEmployeeBindable(employeeId, null);
        List<String> warehouseIds = normalizeIdList(body.get("warehouseIds"));
        assertPdaWarehouse(roleIds, warehouseIds);
        List<Map<String, Object>> dataScopes = normalizeScopes(body.get("dataScopes"));

        String userId = "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String operator = CurrentUser.get() == null ? null : CurrentUser.get().username();
        jdbc.update("""
                INSERT INTO sys_user_runtime(user_id, username, display_name, password, mobile, email,
                    employee_id, primary_role_id, role_name, status, must_change_pwd,
                    pwd_update_time, fail_count, created_by, created_at, updated_at, remark, is_system)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 0, ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, FALSE)
                """, userId, username, displayName, passwordService.encode(password),
                str(body.get("mobile")), str(body.get("email")), blankToNull(employeeId),
                primaryRoleId, primaryRoleName(primaryRoleId),
                "DISABLED".equalsIgnoreCase(str(body.get("status"))) ? "DISABLED" : "NORMAL",
                !Boolean.FALSE.equals(body.get("mustChangePwd")),
                operator, str(body.get("remark")));

        replaceRoles(userId, roleIds);
        replaceWarehouses(userId, warehouseIds);
        replaceUserScopes(userId, dataScopes);
        passwordService.recordHistory(userId, jdbc.queryForObject(
                "SELECT password FROM sys_user_runtime WHERE user_id = ?", String.class, userId));

        opLog.log("system.user", "CREATE", "用户", userId, username,
                "新建用户「" + displayName + "」，角色 " + roleNamesOf(roleIds));
        return ApiResponse.ok(GenericResult.row("userId", userId, "success", true));
    }

    @PostMapping("/update")
    @RequirePerm("system.user.edit")
    @Transactional
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        String userId = required(body, "userId", "用户");
        Map<String, Object> db = loadUserRow(userId);

        List<String> roleIds = normalizeIdList(body.get("roleIds"));
        if (roleIds.isEmpty()) throw new IllegalArgumentException("请至少选择一个角色");
        assertRolesExist(roleIds);
        // 内置保护：admin 账号永远不能失去 SYS_ADMIN
        if (bool(db.get("is_system")) || "admin".equals(str(db.get("username")))) {
            if (roleIds.stream().noneMatch(this::isSysAdminRole)) {
                throw new IllegalArgumentException("内置管理员账号不可移除 SYS_ADMIN 角色");
            }
        }
        String primaryRoleId = resolvePrimaryRoleId(body, roleIds);
        String employeeId = str(body.get("employeeId"));
        assertEmployeeBindable(employeeId, userId);
        List<String> warehouseIds = normalizeIdList(body.get("warehouseIds"));
        assertPdaWarehouse(roleIds, warehouseIds);
        List<Map<String, Object>> dataScopes = normalizeScopes(body.get("dataScopes"));

        jdbc.update("""
                UPDATE sys_user_runtime SET display_name = ?, mobile = ?, email = ?, employee_id = ?,
                    primary_role_id = ?, role_name = ?, remark = ?, updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ?
                """, required(body, "displayName", "姓名"), str(body.get("mobile")),
                str(body.get("email")), blankToNull(employeeId), primaryRoleId,
                primaryRoleName(primaryRoleId), str(body.get("remark")), userId);

        replaceRoles(userId, roleIds);
        replaceWarehouses(userId, warehouseIds);
        replaceUserScopes(userId, dataScopes);

        opLog.log("system.user", "UPDATE", "用户", userId, str(db.get("username")),
                "编辑用户「" + str(db.get("display_name")) + "」，角色 " + roleNamesOf(roleIds));
        return ApiResponse.ok(GenericResult.row("success", true));
    }

    // ==================== 敏感 / 状态操作 ====================

    @PostMapping("/reset-password")
    @RequirePerm(value = "system.user.reset_password", name = "重置密码", type = "ACTION")
    @Transactional
    public ApiResponse<Map<String, Object>> resetPassword(@RequestBody Map<String, Object> body) {
        String userId = required(body, "userId", "用户");
        String newPassword = required(body, "newPassword", "新密码");
        String reason = required(body, "reason", "重置原因");
        passwordService.validateStrength(newPassword);
        Map<String, Object> db = loadUserRow(userId);
        passwordService.assertNotReused(userId, newPassword);

        String encoded = passwordService.encode(newPassword);
        jdbc.update("UPDATE sys_user_runtime SET password = ?, must_change_pwd = ?, fail_count = 0, " +
                        "lock_time = NULL, pwd_update_time = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP " +
                        "WHERE user_id = ?",
                encoded, !Boolean.FALSE.equals(body.get("mustChangePwd")), userId);
        passwordService.recordHistory(userId, encoded);

        // AUDIT-002：重置密码必须留痕（被重置人 + 原因），走敏感日志通道；不记录密码本身
        opLog.logSensitive("system.user", "RESET_PASSWORD", "用户", userId, str(db.get("username")),
                "重置用户「" + str(db.get("display_name")) + "」密码，原因：" + reason
                        + "，要求首登改密：" + (!Boolean.FALSE.equals(body.get("mustChangePwd"))));
        return ApiResponse.ok(GenericResult.row("success", true));
    }

    @PostMapping("/disable")
    @RequirePerm(value = "system.user.disable", name = "停用", type = "ACTION")
    public ApiResponse<Map<String, Object>> disable(@RequestBody Map<String, Object> body) {
        return toggleStatus(body, false);
    }

    @PostMapping("/enable")
    @RequirePerm(value = "system.user.enable", name = "启用", type = "ACTION")
    public ApiResponse<Map<String, Object>> enable(@RequestBody Map<String, Object> body) {
        return toggleStatus(body, true);
    }

    @PostMapping("/unlock")
    @RequirePerm(value = "system.user.unlock", name = "解锁", type = "ACTION")
    public ApiResponse<Map<String, Object>> unlock(@RequestBody Map<String, Object> body) {
        String userId = required(body, "userId", "用户");
        Map<String, Object> db = loadUserRow(userId);
        jdbc.update("UPDATE sys_user_runtime SET fail_count = 0, lock_time = NULL WHERE user_id = ?", userId);
        opLog.log("system.user", "UNLOCK", "用户", userId, str(db.get("username")),
                "解锁用户「" + str(db.get("display_name")) + "」");
        return ApiResponse.ok(GenericResult.row("success", true));
    }

    private ApiResponse<Map<String, Object>> toggleStatus(Map<String, Object> body, boolean enable) {
        String userId = required(body, "userId", "用户");
        Map<String, Object> db = loadUserRow(userId);
        String username = str(db.get("username"));
        // 内置保护：admin 不可停用；不能停用自己
        if (!enable) {
            if (bool(db.get("is_system")) || "admin".equals(username)) {
                throw new IllegalArgumentException("内置管理员账号不可停用");
            }
            CurrentUser.Principal me = CurrentUser.get();
            if (me != null && userId.equals(me.userId())) {
                throw new IllegalArgumentException("不能停用当前登录账号");
            }
        }
        jdbc.update("UPDATE sys_user_runtime SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                enable ? "NORMAL" : "DISABLED", userId);
        opLog.log("system.user", enable ? "ENABLE" : "DISABLE", "用户", userId, username,
                (enable ? "启用" : "停用") + "用户「" + str(db.get("display_name")) + "」");
        return ApiResponse.ok(GenericResult.row("success", true));
    }

    // ==================== 下拉选项 ====================

    /** 用户抽屉/收窄面板所需的全部选项一次拉齐。 */
    @GetMapping("/options")
    @RequirePerm("system.user.view")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> out = new LinkedHashMap<>();
        // H2 未加引号的别名会被碾成大写（同嵌套 Map 坑），统一查原始列名后 toCamel
        out.put("roles", jdbc.queryForList("""
                SELECT role_id, role_code, role_name, role_group, app_type, status
                FROM sys_role_runtime ORDER BY role_group, role_code
                """).stream().map(RbacUserAdminController::toCamel).toList());
        out.put("warehouses", jdbc.queryForList("""
                SELECT warehouse_id, warehouse_code, warehouse_name
                FROM base_warehouse WHERE status = 'NORMAL' ORDER BY warehouse_code
                """).stream().map(RbacUserAdminController::toCamel).toList());
        out.put("scopeTypes", scopeTypeOptions());
        return ApiResponse.ok(out);
    }

    /** 绑定员工搜索：一个员工只能绑一个账号（已绑他人的员工不返回，自己已绑的保留）。 */
    @GetMapping("/employees")
    @RequirePerm("system.user.view")
    public ApiResponse<List<Map<String, Object>>> employees(@RequestParam(required = false) String keyword,
                                                            @RequestParam(required = false) String excludeUserId) {
        // H2 未加引号的驼峰别名会被碾成大写（EMPLOYEEID），查原始列名后统一 toCamel
        StringBuilder sql = new StringBuilder("""
                SELECT e.employee_id, e.employee_code, e.employee_name,
                       e.mobile, e.department, e.position
                FROM base_employee e
                WHERE e.status = 'NORMAL'
                  AND NOT EXISTS (SELECT 1 FROM sys_user_runtime u
                                  WHERE u.employee_id = e.employee_id
                                    AND (? IS NULL OR u.user_id <> ?))
                """);
        List<Object> args = new ArrayList<>();
        args.add(blankToNull(excludeUserId));
        args.add(blankToNull(excludeUserId));
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(e.employee_name) LIKE ? OR LOWER(e.employee_code) LIKE ? OR e.mobile LIKE ?)");
            String like = "%" + keyword.toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY e.employee_code LIMIT 50");
        return ApiResponse.ok(jdbc.queryForList(sql.toString(), args.toArray())
                .stream().map(RbacUserAdminController::toCamel).toList());
    }

    /** 数据范围收窄面板的候选维度值（指定模式的 ID 候选）。 */
    @GetMapping("/scope-values")
    @RequirePerm("system.user.view")
    public ApiResponse<List<Map<String, Object>>> scopeValues(@RequestParam String scopeType,
                                                              @RequestParam(required = false) String keyword) {
        String like = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.toLowerCase() + "%";
        // 查原始列名后手工组装 {id,name}：H2 未加引号别名 id/name 同样会被碾成大写
        List<Map<String, Object>> result = switch (scopeType) {
            case "WAREHOUSE" -> idName(jdbc.queryForList("""
                    SELECT warehouse_id, warehouse_name FROM base_warehouse
                    WHERE status='NORMAL' AND LOWER(warehouse_name) LIKE ? ORDER BY warehouse_code LIMIT 100
                    """, like));
            case "CUSTOMER" -> idName(jdbc.queryForList("""
                    SELECT customer_id, customer_name FROM base_customer
                    WHERE LOWER(customer_name) LIKE ? ORDER BY customer_code LIMIT 100
                    """, like));
            case "SUPPLIER" -> idName(jdbc.queryForList("""
                    SELECT supplier_id, supplier_name FROM base_supplier
                    WHERE LOWER(supplier_name) LIKE ? ORDER BY supplier_code LIMIT 100
                    """, like));
            case "SALESMAN" -> idName(jdbc.queryForList("""
                    SELECT employee_id, employee_name FROM base_employee
                    WHERE is_salesman = TRUE AND status='NORMAL' AND LOWER(employee_name) LIKE ?
                    ORDER BY employee_code LIMIT 100
                    """, like));
            case "OWNER" -> idName(jdbc.queryForList("""
                    SELECT employee_id, employee_name FROM base_employee
                    WHERE is_salesman_admin = TRUE AND status='NORMAL' AND LOWER(employee_name) LIKE ?
                    ORDER BY employee_code LIMIT 100
                    """, like));
            case "GOODS_CATEGORY" -> idName(jdbc.queryForList("""
                    SELECT category_id, category_name FROM base_category
                    WHERE LOWER(category_name) LIKE ? ORDER BY category_code LIMIT 100
                    """, like));
            case "BRAND" -> idName(jdbc.queryForList("""
                    SELECT brand_id, brand_name FROM base_brand
                    WHERE LOWER(brand_name) LIKE ? ORDER BY brand_code LIMIT 100
                    """, like));
            default -> throw new IllegalArgumentException("不支持的数据范围维度：" + scopeType);
        };
        return ApiResponse.ok(result);
    }

    // ==================== 内部辅助 ====================

    /** 把二列原始结果（xxx_id, xxx_name）组装成前端约定的 {id, name}。 */
    private static List<Map<String, Object>> idName(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.values().iterator().next());
            m.put("name", row.size() > 1 ? row.values().toArray()[1] : null);
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> scopeTypeOptions() {
        String[][] defs = {
                {"WAREHOUSE", "仓库"}, {"CUSTOMER", "客户"}, {"SUPPLIER", "供应商"},
                {"SALESMAN", "业务员"}, {"OWNER", "建档人/老板"},
                {"GOODS_CATEGORY", "商品分类"}, {"BRAND", "品牌"}
        };
        String[][] valueModes = {
                {"ALL", "全部"}, {"SELF", "仅自己"}, {"SUB_TREE", "自己及下级"}, {"IDS", "指定"}
        };
        List<Map<String, Object>> list = new ArrayList<>();
        for (String[] d : defs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scopeType", d[0]);
            m.put("scopeName", d[1]);
            List<Map<String, Object>> modes = new ArrayList<>();
            for (String[] v : valueModes) {
                modes.add(Map.of("value", v[0], "label", v[1]));
            }
            m.put("valueModes", modes);
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> loadRoles(String userId) {
        return jdbc.queryForList("""
                SELECT r.role_id, r.role_code, r.role_name, r.role_group, r.app_type
                FROM sys_user_role_rel rel
                JOIN sys_role_runtime r ON r.role_id = rel.role_id
                WHERE rel.user_id = ?
                ORDER BY r.role_code
                """, userId).stream().map(RbacUserAdminController::toCamel).toList();
    }

    private List<Map<String, Object>> loadWarehouses(String userId) {
        return jdbc.queryForList("""
                SELECT w.warehouse_id, w.warehouse_name, uw.is_primary
                FROM sys_user_warehouse uw
                JOIN base_warehouse w ON w.warehouse_id = uw.warehouse_id
                WHERE uw.user_id = ?
                ORDER BY w.warehouse_code
                """, userId).stream().map(RbacUserAdminController::toCamel).toList();
    }

    private Map<String, Object> pickPrimaryRole(List<Map<String, Object>> roles, String primaryRoleId) {
        if (roles.isEmpty()) return null;
        for (Map<String, Object> r : roles) {
            if (str(r.get("roleId")).equals(primaryRoleId)) return r;
        }
        return roles.get(0);
    }

    private boolean isLocked(long failCount, Timestamp lockTime) {
        if (failCount < MAX_FAIL_COUNT || lockTime == null) return false;
        return lockTime.toInstant().plus(LOCK_MINUTES, ChronoUnit.MINUTES).isAfter(Instant.now());
    }

    private void replaceRoles(String userId, List<String> roleIds) {
        jdbc.update("DELETE FROM sys_user_role_rel WHERE user_id = ?", userId);
        for (String roleId : roleIds) {
            jdbc.update("INSERT INTO sys_user_role_rel(id, user_id, role_id) VALUES (?, ?, ?)",
                    "UR" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), userId, roleId);
        }
    }

    private void replaceWarehouses(String userId, List<String> warehouseIds) {
        jdbc.update("DELETE FROM sys_user_warehouse WHERE user_id = ?", userId);
        Set<String> uniq = new LinkedHashSet<>(warehouseIds);
        for (String warehouseId : uniq) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM base_warehouse WHERE warehouse_id = ?", Integer.class, warehouseId);
            if (cnt == null || cnt == 0) throw new IllegalArgumentException("绑定仓库不存在：" + warehouseId);
            jdbc.update("INSERT INTO sys_user_warehouse(id, user_id, warehouse_id) VALUES (?, ?, ?)",
                    "UW" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), userId, warehouseId);
        }
    }

    /** 用户层收窄：全量替换，只接受七维白名单；空列表 = 全部跟随角色。 */
    private void replaceUserScopes(String userId, List<Map<String, Object>> dataScopes) {
        jdbc.update("DELETE FROM sys_user_data_scope WHERE user_id = ?", userId);
        for (Map<String, Object> s : dataScopes) {
            String type = str(s.get("scopeType"));
            String value = str(s.get("scopeValue"));
            if (!SCOPE_TYPES.contains(type)) {
                throw new IllegalArgumentException("不支持的数据范围维度：" + type);
            }
            if (value.isEmpty()) continue;
            if (value.length() > 2000) throw new IllegalArgumentException("维度 " + type + " 指定值过多");
            jdbc.update("INSERT INTO sys_user_data_scope(id, user_id, scope_type, scope_value) VALUES (?, ?, ?, ?)",
                    "US" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), userId, type, value);
        }
    }

    private void assertRolesExist(List<String> roleIds) {
        for (String roleId : roleIds) {
            List<Map<String, Object>> rs = jdbc.queryForList(
                    "SELECT status FROM sys_role_runtime WHERE role_id = ?", roleId);
            if (rs.isEmpty()) throw new IllegalArgumentException("角色不存在：" + roleId);
            if (!"NORMAL".equals(str(rs.get(0).get("status")))) {
                throw new IllegalArgumentException("角色已停用，不能分配：" + roleId);
            }
        }
    }

    private boolean isSysAdminRole(String roleId) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_role_runtime WHERE role_id = ? AND role_code = 'SYS_ADMIN'",
                Integer.class, roleId);
        return cnt != null && cnt > 0;
    }

    /** PDA 端角色（app_type=WMS_PDA）的用户必须至少绑定一个仓库（§10.1）。 */
    private void assertPdaWarehouse(List<String> roleIds, List<String> warehouseIds) {
        if (!warehouseIds.isEmpty()) return;
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_role_runtime WHERE role_id IN ("
                        + String.join(",", roleIds.stream().map(x -> "?").toList())
                        + ") AND app_type = 'WMS_PDA'",
                Integer.class, roleIds.toArray());
        if (cnt != null && cnt > 0) {
            throw new IllegalArgumentException("PDA 作业角色账号必须至少绑定一个仓库");
        }
    }

    private void assertEmployeeBindable(String employeeId, String excludeUserId) {
        if (employeeId == null || employeeId.isBlank()) return;
        Integer emp = jdbc.queryForObject(
                "SELECT COUNT(1) FROM base_employee WHERE employee_id = ?", Integer.class, employeeId);
        if (emp == null || emp == 0) throw new IllegalArgumentException("绑定员工不存在：" + employeeId);
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_user_runtime WHERE employee_id = ? AND user_id <> ?",
                Integer.class, employeeId, excludeUserId == null ? "" : excludeUserId);
        if (bound != null && bound > 0) {
            throw new IllegalArgumentException("该员工已绑定其他账号，一个员工只能绑定一个账号");
        }
    }

    private String primaryRoleName(String primaryRoleId) {
        if (primaryRoleId == null) return null;
        List<String> names = jdbc.queryForList(
                "SELECT role_name FROM sys_role_runtime WHERE role_id = ?", String.class, primaryRoleId);
        return names.isEmpty() ? null : names.get(0);
    }

    private String roleNamesOf(List<String> roleIds) {
        if (roleIds.isEmpty()) return "";
        List<String> names = jdbc.queryForList("SELECT role_name FROM sys_role_runtime WHERE role_id IN ("
                + String.join(",", roleIds.stream().map(x -> "?").toList()) + ")", String.class,
                roleIds.toArray());
        return String.join("、", names);
    }

    private Map<String, Object> loadUserRow(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, username, display_name, status, is_system FROM sys_user_runtime WHERE user_id = ?",
                userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("用户不存在");
        return rows.get(0);
    }

    private String resolvePrimaryRoleId(Map<String, Object> body, List<String> roleIds) {
        String explicit = str(body.get("primaryRoleId"));
        if (!explicit.isEmpty()) {
            if (!roleIds.contains(explicit)) {
                throw new IllegalArgumentException("主角色必须是已勾选角色之一");
            }
            return explicit;
        }
        return roleIds.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeScopes(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("scopeType", str(map.get("scopeType")));
                    m.put("scopeValue", str(map.get("scopeValue")));
                    out.add(m);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> normalizeIdList(Object raw) {
        List<String> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    String id = String.valueOf(item).trim();
                    if (!ids.contains(id)) ids.add(id);
                }
            }
        }
        return ids;
    }

    private static String filterStr(PageRequest request, String key) {
        if (request.filters() == null) return null;
        Object v = request.filters().get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Map<String, Object> toCamel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey().toLowerCase(java.util.Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : k.toCharArray()) {
                if (c == '_') { upper = true; continue; }
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            out.put(sb.toString(), e.getValue());
        }
        return out;
    }

    private static String required(Map<String, Object> body, String key, String label) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return String.valueOf(v).trim();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }
}
