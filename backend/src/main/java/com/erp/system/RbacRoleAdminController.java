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
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 角色/权限组管理（PRD-28 §10.2）。
 *
 * <p>右侧四分区授权一次性保存：菜单（sys_role_menu_rel）、功能点（sys_role_func_rel，
 * 含全局功能）、敏感字段（sys_role_field_rel）、七维数据范围（sys_role_data_scope）。
 * 内置角色（is_system=TRUE）保护：编码不可改、不可删，只可改数据范围与备注；
 * admin_only 菜单任何角色都不可授权（MENU-003 的后端兜底）。
 */
@RestController
@RequestMapping("/system/rbac/role")
public class RbacRoleAdminController {

    private static final Set<String> SCOPE_TYPES = Set.of(
            "WAREHOUSE", "CUSTOMER", "SUPPLIER", "SALESMAN", "OWNER", "GOODS_CATEGORY", "BRAND");
    private static final Set<String> APP_TYPES = Set.of("ERP", "WMS_PDA", "DRIVER", "ALL");

    private final JdbcTemplate jdbc;
    private final OperationLogService opLog;

    public RbacRoleAdminController(JdbcTemplate jdbc, OperationLogService opLog) {
        this.jdbc = jdbc;
        this.opLog = opLog;
    }

    // ==================== 列表 / 详情 ====================

    @PostMapping("/page")
    @RequirePerm("system.role.view")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.role_id, r.role_code, r.role_name, r.role_group, r.app_type,
                       r.data_scope, r.status, r.is_system, r.remark,
                       r.created_at,
                       (SELECT COUNT(1) FROM sys_user_role_rel ur
                        JOIN sys_user_runtime u ON u.user_id = ur.user_id
                        WHERE ur.role_id = r.role_id AND u.status = 'NORMAL') AS user_count
                FROM sys_role_runtime r
                ORDER BY r.is_system DESC, r.role_group, r.role_code
                """);
        List<Map<String, Object>> all = new ArrayList<>();
        String keyword = request.keyword();
        for (Map<String, Object> row : rows) {
            if (keyword != null && !keyword.isBlank()) {
                String hay = (str(row.get("role_code")) + " " + str(row.get("role_name"))).toLowerCase();
                if (!hay.contains(keyword.toLowerCase())) continue;
            }
            all.add(toCamel(row));
        }
        return ApiResponse.ok(PageResult.of(all, request));
    }

    @GetMapping("/{roleId}")
    @RequirePerm("system.role.view")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String roleId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT role_id, role_code, role_name, role_group, app_type, data_scope,
                       status, is_system, remark, created_at, updated_at
                FROM sys_role_runtime WHERE role_id = ?
                """, roleId);
        if (rows.isEmpty()) throw new IllegalArgumentException("角色不存在");
        Map<String, Object> role = toCamel(rows.get(0));

        role.put("menuIds", jdbc.queryForList(
                "SELECT menu_id FROM sys_role_menu_rel WHERE role_id = ?", String.class, roleId));
        role.put("funcCodes", jdbc.queryForList("""
                SELECT f.func_code FROM sys_role_func_rel rf
                JOIN sys_func_meta f ON f.func_id = rf.func_id
                WHERE rf.role_id = ? AND f.status = 'NORMAL'
                """, String.class, roleId));
        role.put("fieldCodes", jdbc.queryForList("""
                SELECT f.field_code FROM sys_role_field_rel rf
                JOIN sys_field_meta f ON f.field_id = rf.field_id
                WHERE rf.role_id = ? AND f.status = 'NORMAL'
                """, String.class, roleId));
        List<Map<String, Object>> scopes = new ArrayList<>();
        jdbc.queryForList(
                "SELECT scope_type, scope_value FROM sys_role_data_scope WHERE role_id = ? ORDER BY scope_type",
                roleId).forEach(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scopeType", r.get("scope_type"));
            m.put("scopeValue", r.get("scope_value"));
            scopes.add(m);
        });
        role.put("dataScopes", scopes);
        return ApiResponse.ok(role);
    }

    // ==================== 新建 / 编辑 / 删除 / 复制 ====================

    @PostMapping("/create")
    @RequirePerm("system.role.add")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String roleCode = required(body, "roleCode", "角色编码").toUpperCase();
        String roleName = required(body, "roleName", "角色名称");
        validateCode(roleCode);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_role_runtime WHERE role_code = ?", Integer.class, roleCode);
        if (exists != null && exists > 0) throw new IllegalArgumentException("角色编码已存在：" + roleCode);

        String roleGroup = normalizeGroup(str(body.get("roleGroup")));
        String appType = normalizeAppType(str(body.get("appType")));
        String roleId = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String operator = CurrentUser.get() == null ? null : CurrentUser.get().username();
        jdbc.update("""
                INSERT INTO sys_role_runtime(role_id, role_code, role_name, role_group, app_type,
                    user_count, menu_scope, field_scope, data_scope, status, is_system, remark,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, '按配置', '按配置', 'SELF', 'NORMAL', FALSE, ?,
                        ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, roleId, roleCode, roleName, roleGroup, appType, str(body.get("remark")), operator);

        saveGrantsInternal(roleId, body, true);
        opLog.log("system.role", "CREATE", "角色", roleId, roleCode,
                "新建角色「" + roleName + "」（" + roleGroup + "/" + appType + "）");
        return ApiResponse.ok(GenericResult.row("roleId", roleId, "success", true));
    }

    @PostMapping("/update")
    @RequirePerm("system.role.edit")
    @Transactional
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        String roleId = required(body, "roleId", "角色");
        Map<String, Object> db = loadRole(roleId);
        String roleCode = str(db.get("role_code"));
        boolean builtIn = bool(db.get("is_system"));

        if (builtIn) {
            // §10.2：内置角色只可改数据范围与备注；编码/名称/分类/端均不可改
            jdbc.update("UPDATE sys_role_runtime SET remark = ?, updated_at = CURRENT_TIMESTAMP WHERE role_id = ?",
                    str(body.get("remark")), roleId);
            replaceDataScopes(roleId, normalizeScopes(body.get("dataScopes")));
            opLog.log("system.role", "UPDATE", "角色", roleId, roleCode,
                    "修改内置角色「" + str(db.get("role_name")) + "」备注/数据范围");
            return ApiResponse.ok(GenericResult.row("success", true));
        }

        String newCode = required(body, "roleCode", "角色编码").toUpperCase();
        String newName = required(body, "roleName", "角色名称");
        validateCode(newCode);
        if (!newCode.equals(roleCode)) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM sys_role_runtime WHERE role_code = ? AND role_id <> ?",
                    Integer.class, newCode, roleId);
            if (cnt != null && cnt > 0) throw new IllegalArgumentException("角色编码已存在：" + newCode);
        }
        jdbc.update("""
                UPDATE sys_role_runtime SET role_code = ?, role_name = ?, role_group = ?, app_type = ?,
                    remark = ?, updated_at = CURRENT_TIMESTAMP
                WHERE role_id = ?
                """, newCode, newName, normalizeGroup(str(body.get("roleGroup"))),
                normalizeAppType(str(body.get("appType"))), str(body.get("remark")), roleId);

        // 编辑页允许直接带四分区一起保存
        if (body.containsKey("menuIds") || body.containsKey("funcCodes")
                || body.containsKey("fieldCodes") || body.containsKey("dataScopes")) {
            saveGrantsInternal(roleId, body, true);
        }
        opLog.log("system.role", "UPDATE", "角色", roleId, newCode, "编辑角色「" + newName + "」");
        return ApiResponse.ok(GenericResult.row("success", true));
    }

    @PostMapping("/delete")
    @RequirePerm("system.role.delete")
    public ApiResponse<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        String roleId = required(body, "roleId", "角色");
        Map<String, Object> db = loadRole(roleId);
        if (bool(db.get("is_system"))) {
            throw new IllegalArgumentException("内置角色不可删除：" + str(db.get("role_name")));
        }
        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_user_role_rel WHERE role_id = ?", Integer.class, roleId);
        if (userCount != null && userCount > 0) {
            throw new IllegalArgumentException("该角色下仍有 " + userCount + " 个用户，请先调整这些用户的角色后再删除");
        }
        jdbc.update("DELETE FROM sys_role_menu_rel WHERE role_id = ?", roleId);
        jdbc.update("DELETE FROM sys_role_func_rel WHERE role_id = ?", roleId);
        jdbc.update("DELETE FROM sys_role_field_rel WHERE role_id = ?", roleId);
        jdbc.update("DELETE FROM sys_role_data_scope WHERE role_id = ?", roleId);
        jdbc.update("DELETE FROM sys_role_runtime WHERE role_id = ?", roleId);
        opLog.log("system.role", "DELETE", "角色", roleId, str(db.get("role_code")),
                "删除角色「" + str(db.get("role_name")) + "」");
        return ApiResponse.ok(GenericResult.row("success", true));
    }

    @PostMapping("/copy")
    @RequirePerm(value = "system.role.copy", name = "复制角色", type = "ACTION")
    @Transactional
    public ApiResponse<Map<String, Object>> copy(@RequestBody Map<String, Object> body) {
        String sourceId = required(body, "sourceRoleId", "源角色");
        String newCode = required(body, "newRoleCode", "新角色编码").toUpperCase();
        String newName = required(body, "newRoleName", "新角色名称");
        validateCode(newCode);
        Map<String, Object> src = loadRole(sourceId);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_role_runtime WHERE role_code = ?", Integer.class, newCode);
        if (exists != null && exists > 0) throw new IllegalArgumentException("角色编码已存在：" + newCode);

        String roleId = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String operator = CurrentUser.get() == null ? null : CurrentUser.get().username();
        jdbc.update("""
                INSERT INTO sys_role_runtime(role_id, role_code, role_name, role_group, app_type,
                    user_count, menu_scope, field_scope, data_scope, status, is_system, remark,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, '按配置', '按配置', 'SELF', 'NORMAL', FALSE, ?,
                        ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, roleId, newCode, newName, str(src.get("role_group")), str(src.get("app_type")),
                "复制自 " + str(src.get("role_code")), operator);

        jdbc.queryForList("SELECT menu_id FROM sys_role_menu_rel WHERE role_id = ?", String.class, sourceId)
                .forEach(menuId -> jdbc.update(
                        "INSERT INTO sys_role_menu_rel(id, role_id, menu_id) VALUES (?, ?, ?)",
                        "RM" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), roleId, menuId));
        jdbc.queryForList("SELECT func_id FROM sys_role_func_rel WHERE role_id = ?", String.class, sourceId)
                .forEach(funcId -> jdbc.update(
                        "INSERT INTO sys_role_func_rel(id, role_id, func_id) VALUES (?, ?, ?)",
                        "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), roleId, funcId));
        jdbc.queryForList("SELECT field_id FROM sys_role_field_rel WHERE role_id = ?", String.class, sourceId)
                .forEach(fieldId -> jdbc.update(
                        "INSERT INTO sys_role_field_rel(id, role_id, field_id) VALUES (?, ?, ?)",
                        "RD" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), roleId, fieldId));
        jdbc.queryForList("SELECT scope_type, scope_value FROM sys_role_data_scope WHERE role_id = ?", sourceId)
                .forEach(s -> jdbc.update(
                        "INSERT INTO sys_role_data_scope(id, role_id, scope_type, scope_value) VALUES (?, ?, ?, ?)",
                        "RS" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), roleId,
                        s.get("scope_type"), s.get("scope_value")));

        opLog.log("system.role", "COPY", "角色", roleId, newCode,
                "从「" + str(src.get("role_name")) + "」复制角色");
        return ApiResponse.ok(GenericResult.row("roleId", roleId, "success", true));
    }

    // ==================== 四分区授权保存 ====================

    /**
     * 保存角色授权（菜单/功能/字段/数据范围全量替换）。
     * body: {roleId, menuIds:[], funcCodes:[], fieldCodes:[], dataScopes:[{scopeType,scopeValue}]}
     */
    @PostMapping("/grants")
    @RequirePerm(value = "system.role.grant", name = "配置权限", type = "ACTION")
    @Transactional
    public ApiResponse<Map<String, Object>> grants(@RequestBody Map<String, Object> body) {
        String roleId = required(body, "roleId", "角色");
        Map<String, Object> db = loadRole(roleId);
        if (bool(db.get("is_system"))) {
            // 内置角色只放开数据范围；菜单/功能/字段由 V102/V103 等迁移种子管理，防止现场误收回管超管体系
            replaceDataScopes(roleId, normalizeScopes(body.get("dataScopes")));
            opLog.log("system.role", "GRANT", "角色", roleId, str(db.get("role_code")),
                    "修改内置角色「" + str(db.get("role_name")) + "」数据范围");
            return ApiResponse.ok(GenericResult.row("success", true, "builtIn", true));
        }
        saveGrantsInternal(roleId, body, false);
        opLog.log("system.role", "GRANT", "角色", roleId, str(db.get("role_code")),
                "保存角色「" + str(db.get("role_name")) + "」授权：菜单/功能/字段/数据范围");
        return ApiResponse.ok(GenericResult.row("success", true));
    }

    /**
     * @param allowPartial create/update 时未带分区不清空（按缺省跳过）；grants 全量替换缺省按空集。
     */
    private void saveGrantsInternal(String roleId, Map<String, Object> body, boolean allowPartial) {
        if (body.containsKey("menuIds") || !allowPartial) {
            replaceMenus(roleId, normalizeIdList(body.get("menuIds")));
        }
        if (body.containsKey("funcCodes") || !allowPartial) {
            replaceFuncs(roleId, normalizeIdList(body.get("funcCodes")));
        }
        if (body.containsKey("fieldCodes") || !allowPartial) {
            replaceFields(roleId, normalizeIdList(body.get("fieldCodes")));
        }
        if (body.containsKey("dataScopes") || !allowPartial) {
            replaceDataScopes(roleId, normalizeScopes(body.get("dataScopes")));
        }
    }

    private void replaceMenus(String roleId, List<String> menuIds) {
        jdbc.update("DELETE FROM sys_role_menu_rel WHERE role_id = ?", roleId);
        Set<String> uniq = new LinkedHashSet<>(menuIds);
        for (String menuId : uniq) {
            // admin_only 菜单任何角色都不可授权（§10.3 硬规则2 / MENU-003 后端兜底）
            Integer forbidden = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM sys_menu_meta WHERE menu_id = ? AND admin_only = TRUE",
                    Integer.class, menuId);
            if (forbidden != null && forbidden > 0) {
                throw new IllegalArgumentException("该菜单为超管专属，不允许授权：" + menuId);
            }
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM sys_menu_meta WHERE menu_id = ? AND status = 'NORMAL'",
                    Integer.class, menuId);
            if (exists == null || exists == 0) {
                throw new IllegalArgumentException("菜单不存在或已停用：" + menuId);
            }
            jdbc.update("INSERT INTO sys_role_menu_rel(id, role_id, menu_id) VALUES (?, ?, ?)",
                    "RM" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), roleId, menuId);
        }
    }

    private void replaceFuncs(String roleId, List<String> funcCodes) {
        jdbc.update("DELETE FROM sys_role_func_rel WHERE role_id = ?", roleId);
        Set<String> uniq = new LinkedHashSet<>(funcCodes);
        for (String code : uniq) {
            List<String> ids = jdbc.queryForList(
                    "SELECT func_id FROM sys_func_meta WHERE func_code = ? AND status = 'NORMAL'",
                    String.class, code);
            if (ids.isEmpty()) throw new IllegalArgumentException("功能点不存在或已停用：" + code);
            jdbc.update("INSERT INTO sys_role_func_rel(id, role_id, func_id) VALUES (?, ?, ?)",
                    "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), roleId, ids.get(0));
        }
    }

    private void replaceFields(String roleId, List<String> fieldCodes) {
        jdbc.update("DELETE FROM sys_role_field_rel WHERE role_id = ?", roleId);
        Set<String> uniq = new LinkedHashSet<>(fieldCodes);
        for (String code : uniq) {
            List<String> ids = jdbc.queryForList(
                    "SELECT field_id FROM sys_field_meta WHERE field_code = ? AND status = 'NORMAL'",
                    String.class, code);
            if (ids.isEmpty()) throw new IllegalArgumentException("敏感字段不存在：" + code);
            jdbc.update("INSERT INTO sys_role_field_rel(id, role_id, field_id) VALUES (?, ?, ?)",
                    "RD" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), roleId, ids.get(0));
        }
    }

    private void replaceDataScopes(String roleId, List<Map<String, Object>> dataScopes) {
        jdbc.update("DELETE FROM sys_role_data_scope WHERE role_id = ?", roleId);
        for (Map<String, Object> s : dataScopes) {
            String type = str(s.get("scopeType"));
            String value = str(s.get("scopeValue"));
            if (!SCOPE_TYPES.contains(type)) {
                throw new IllegalArgumentException("不支持的数据范围维度：" + type);
            }
            if (value.isEmpty()) continue;
            if (value.length() > 2000) throw new IllegalArgumentException("维度 " + type + " 指定值过多");
            jdbc.update("INSERT INTO sys_role_data_scope(id, role_id, scope_type, scope_value) VALUES (?, ?, ?, ?)",
                    "RS" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), roleId, type, value);
        }
    }

    // ==================== 辅助 ====================

    private Map<String, Object> loadRole(String roleId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role_id, role_code, role_name, role_group, app_type, is_system, status "
                        + "FROM sys_role_runtime WHERE role_id = ?", roleId);
        if (rows.isEmpty()) throw new IllegalArgumentException("角色不存在");
        return rows.get(0);
    }

    private void validateCode(String code) {
        if (!code.matches("[A-Z][A-Z0-9_]{1,49}")) {
            throw new IllegalArgumentException("角色编码需为 2~50 位大写字母/数字/下划线，且以字母开头");
        }
    }

    private String normalizeGroup(String group) {
        if (group.isBlank()) return "BIZ";
        if (!Set.of("SYS", "BIZ", "WMS", "TMS").contains(group)) {
            throw new IllegalArgumentException("角色分类只能是 SYS/BIZ/WMS/TMS");
        }
        return group;
    }

    private String normalizeAppType(String appType) {
        if (appType.isBlank()) return "ERP";
        if (!APP_TYPES.contains(appType)) {
            throw new IllegalArgumentException("适用端只能是 ERP/WMS_PDA/DRIVER/ALL");
        }
        return appType;
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

    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }
}
