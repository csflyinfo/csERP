package com.erp.common.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 功能权限查询服务（PRD-28 RBAC）。
 *
 * <p>按当前登录用户的角色集合解析有效功能点，规则（方案 §6.2.1「反向收窄」）：
 * <pre>
 * 允许 = 显式模块功能点命中
 *      ∨ 某角色拥有对应 global.&lt;action&gt; 且该角色在目标菜单下零个 MODULE 功能点配置
 * </pre>
 * 即角色在某菜单下只要显式配置过任意模块功能点（哪怕只勾了 view），该菜单即进入
 * 「显式模式」，未勾的动作不能被全局功能补上（GLOBAL-003：有全局 audit 但单独取消
 * sales.order.audit 时，销售订单审核 403，其他模块照常可审）；该角色完全没配置过的
 * 菜单，全局功能自动覆盖（GLOBAL-001：global.export 各列表均可导出）。
 *
 * <p>全局↔模块动作映射：audit/unaudit/close/delete/import/export/print 同名；
 * 模块 {@code log} ↔ 全局 {@code global.log_view}。{@code biz_} 非标准动作、
 * view/add/edit 不走全局回落。多角色按角色独立判定后取并集。
 *
 * <p>SYS_ADMIN 短路全放行。快照在单次请求内缓存。查库失败 fail-closed。
 */
@Service
public class PermissionService {

    private static final String REQUEST_ATTR_FUNC_SNAPSHOT = "rbac.funcSnapshot";
    private static final String REQUEST_ATTR_FIELD_CODES = "rbac.fieldCodes";

    /** 模块动作 → 全局动作后缀（仅这些动作支持全局回落）。 */
    private static final Map<String, String> MODULE_TO_GLOBAL_ACTION = Map.of(
            "audit", "audit",
            "unaudit", "unaudit",
            "close", "close",
            "delete", "delete",
            "import", "import",
            "export", "export",
            "print", "print",
            "log", "log_view");

    /** 全局动作后缀 → 模块动作（有效集展开用），由上表反转得到 log_view→log 等。 */
    private static final Map<String, String> GLOBAL_TO_MODULE_ACTION;
    static {
        Map<String, String> reverse = new HashMap<>();
        MODULE_TO_GLOBAL_ACTION.forEach((moduleAction, globalAction) -> reverse.put(globalAction, moduleAction));
        GLOBAL_TO_MODULE_ACTION = Map.copyOf(reverse);
    }

    private final JdbcTemplate jdbcTemplate;

    public PermissionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 当前用户是否拥有指定功能点编码（如 global.export、sales.order.audit）。 */
    public boolean hasFunc(String funcCode) {
        if (funcCode == null || funcCode.isBlank()) return true;
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null) return false;
        if (p.isSuperAdmin()) return true;
        Set<String> roleCodes = p.roleCodes();
        if (roleCodes == null || roleCodes.isEmpty()) return false;
        return snapshotFor(roleCodes).allows(funcCode);
    }

    /**
     * 当前用户全部「有效」功能点编码（显式 + 全局回落到未配置菜单的展开集），
     * 供 /system/perm/mine 一次性下发前端做按钮 v-permission 判定。
     * 超管返回空集（前端按角色短路）。
     */
    public Set<String> currentFuncCodes() {
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null) return Set.of();
        if (p.isSuperAdmin()) return Set.of();
        Set<String> roleCodes = p.roleCodes();
        if (roleCodes == null || roleCodes.isEmpty()) return Set.of();
        return snapshotFor(roleCodes).effectiveCodes();
    }

    /**
     * 按用户 ID 判定是否「显式」拥有某功能点（授权弹窗校验授权人用，不走 ThreadLocal）。
     * 审批类全局功能只认真实授予，不做任何回落。
     */
    public boolean userIdHasFunc(String userId, String funcCode) {
        if (userId == null || funcCode == null) return false;
        try {
            List<String> roleCodes = jdbcTemplate.queryForList(
                    "SELECT r.role_code FROM sys_user_role_rel urr "
                            + "JOIN sys_role_runtime r ON r.role_id = urr.role_id AND r.status = 'NORMAL' "
                            + "WHERE urr.user_id = ?",
                    String.class, userId);
            if (roleCodes.isEmpty()) return false;
            Snapshot s = loadSnapshot(new LinkedHashSet<>(roleCodes));
            return s.allows(funcCode);
        } catch (Exception e) {
            return false; // fail-closed
        }
    }

    /** 当前用户是否有某敏感字段的查看权限（sys_role_field_rel，SYS_ADMIN 短路）。 */
    public boolean hasField(String fieldCode) {
        if (fieldCode == null || fieldCode.isBlank()) return true;
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null) return false;
        if (p.isSuperAdmin()) return true;
        if (p.roleCodes() == null || p.roleCodes().isEmpty()) return false;
        return loadFieldCodes(p.roleCodes()).contains(fieldCode);
    }

    /** 当前用户已授权的敏感字段编码集合（超管返回空集，由 {@link #hasField} 短路，脱敏器同样跳过）。 */
    public Set<String> currentFieldCodes() {
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null) return Set.of();
        if (p.isSuperAdmin()) return Set.of();
        if (p.roleCodes() == null || p.roleCodes().isEmpty()) return Set.of();
        return loadFieldCodes(p.roleCodes());
    }

    /**
     * 当前用户敏感字段授权集合的规范化签名（报表结果缓存跨账号共享用）：
     * 脱敏在缓存 loader 内已完成，字段授权集合相同的账号看到的结果一致，可共享同一缓存项；
     * 集合不同则键不同，无金额权限者绝不会命中有权限者的结果。超管统一 "admin"。
     */
    public String currentFieldCodesSignature() {
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null) return "anonymous";
        if (p.isSuperAdmin()) return "admin";
        Set<String> codes = currentFieldCodes();
        String joined = codes.isEmpty() ? "" : String.join(",", new TreeSet<>(codes));
        return DigestUtils.md5DigestAsHex(joined.getBytes(StandardCharsets.UTF_8));
    }

    // ============================ 权限快照（显式集 + 反向收窄） ============================

    /** 一次解析出的权限快照。 */
    static class Snapshot {
        /** 全部角色并集的显式功能点（含 global.* 与 module.*）。 */
        final Set<String> explicit;
        /** roleCode → 该角色配置过 MODULE 功能点的 menuId 集合（非空即「显式模式」）。 */
        final Map<String, Set<String>> configuredMenuByRole;
        /** roleCode → 该角色显式拥有的全局功能后缀集合（如 export、log_view）。 */
        final Map<String, Set<String>> globalActionByRole;
        /** menuCode → menuId（NORMAL 菜单）。 */
        final Map<String, String> menuIdByCode;
        /** menuId → menuCode，用于展开有效集。 */
        final Map<String, String> menuCodeById;

        Snapshot(Set<String> explicit,
                 Map<String, Set<String>> configuredMenuByRole,
                 Map<String, Set<String>> globalActionByRole,
                 Map<String, String> menuIdByCode,
                 Map<String, String> menuCodeById) {
            this.explicit = explicit;
            this.configuredMenuByRole = configuredMenuByRole;
            this.globalActionByRole = globalActionByRole;
            this.menuIdByCode = menuIdByCode;
            this.menuCodeById = menuCodeById;
        }

        boolean allows(String funcCode) {
            if (explicit.contains(funcCode)) return true;
            int dot = funcCode.lastIndexOf('.');
            if (dot <= 0) return false;
            String menuCode = funcCode.substring(0, dot);
            String action = funcCode.substring(dot + 1);
            String globalAction = MODULE_TO_GLOBAL_ACTION.get(action);
            if (globalAction == null) return false; // biz_/view/add/edit 不回落
            String menuId = menuIdByCode.get(menuCode);
            if (menuId == null) return false;
            for (Map.Entry<String, Set<String>> e : globalActionByRole.entrySet()) {
                if (!e.getValue().contains(globalAction)) continue;
                // 该角色在本菜单零 MODULE 配置 → 全局功能覆盖本动作
                if (!configuredMenuByRole.getOrDefault(e.getKey(), Set.of()).contains(menuId)) {
                    return true;
                }
            }
            return false;
        }

        /** 显式集 + 全局功能对「各角色未配置菜单」的展开集（下发前端用）。 */
        Set<String> effectiveCodes() {
            Set<String> out = new LinkedHashSet<>(explicit);
            for (Map.Entry<String, Set<String>> e : globalActionByRole.entrySet()) {
                Set<String> configured = configuredMenuByRole.getOrDefault(e.getKey(), Set.of());
                for (Map.Entry<String, String> menu : menuCodeById.entrySet()) {
                    if (configured.contains(menu.getKey())) continue;
                    for (String globalAction : e.getValue()) {
                        String moduleAction = reverseGlobalAction(globalAction);
                        if (moduleAction != null) {
                            out.add(menu.getValue() + "." + moduleAction);
                        }
                    }
                }
            }
            return Set.copyOf(out);
        }

        private static String reverseGlobalAction(String globalAction) {
            // 审批类/改价/结算等非标准全局动作不在表中，返回 null 不展开为模块码
            return GLOBAL_TO_MODULE_ACTION.get(globalAction);
        }
    }

    private Snapshot snapshotFor(Set<String> roleCodes) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cached = attrs.getAttribute(REQUEST_ATTR_FUNC_SNAPSHOT, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof Snapshot s) return s;
        }
        Snapshot snapshot;
        try {
            snapshot = loadSnapshot(roleCodes);
        } catch (Exception e) {
            // fail-closed：查库失败按零权限处理
            snapshot = new Snapshot(Set.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
        if (attrs != null) {
            attrs.setAttribute(REQUEST_ATTR_FUNC_SNAPSHOT, snapshot, RequestAttributes.SCOPE_REQUEST);
        }
        return snapshot;
    }

    private Snapshot loadSnapshot(Set<String> roleCodes) {
        String placeholders = roleCodes.stream().map(c -> "?").collect(Collectors.joining(","));
        // 一条 SQL 拉回角色 × 功能点（含 scope/menuId），用 RowMapper 避免 H2 列标签大写问题
        List<FuncRow> rows = jdbcTemplate.query(
                "SELECT r.role_code AS role_code, f.func_code AS func_code, "
                        + "f.func_scope AS func_scope, f.menu_id AS menu_id "
                        + "FROM sys_role_func_rel rf "
                        + "JOIN sys_role_runtime r ON r.role_id = rf.role_id "
                        + "JOIN sys_func_meta f ON f.func_id = rf.func_id "
                        + "WHERE r.status = 'NORMAL' AND f.status = 'NORMAL' AND r.role_code IN ("
                        + placeholders + ")",
                (rs, i) -> new FuncRow(rs.getString("role_code"), rs.getString("func_code"),
                        rs.getString("func_scope"), rs.getString("menu_id")),
                roleCodes.toArray());

        Set<String> explicit = new LinkedHashSet<>();
        Map<String, Set<String>> configuredMenuByRole = new HashMap<>();
        Map<String, Set<String>> globalActionByRole = new HashMap<>();
        for (FuncRow row : rows) {
            explicit.add(row.funcCode);
            if ("GLOBAL".equalsIgnoreCase(row.funcScope)) {
                if (row.funcCode.startsWith("global.")) {
                    globalActionByRole.computeIfAbsent(row.roleCode, k -> new LinkedHashSet<>())
                            .add(row.funcCode.substring("global.".length()));
                }
            } else if (row.menuId != null) {
                configuredMenuByRole.computeIfAbsent(row.roleCode, k -> new LinkedHashSet<>())
                        .add(row.menuId);
            }
        }

        Map<String, String> menuIdByCode = new HashMap<>();
        Map<String, String> menuCodeById = new HashMap<>();
        jdbcTemplate.query("SELECT menu_id, menu_code FROM sys_menu_meta WHERE status = 'NORMAL'",
                rs -> {
                    String id = rs.getString("menu_id");
                    String code = rs.getString("menu_code");
                    menuIdByCode.put(code, id);
                    menuCodeById.put(id, code);
                });
        // 全局占位菜单不参与模块回落展开
        menuIdByCode.remove("_global_");
        menuCodeById.entrySet().removeIf(e -> "_global_".equals(e.getValue()));

        return new Snapshot(Set.copyOf(explicit), configuredMenuByRole, globalActionByRole,
                menuIdByCode, menuCodeById);
    }

    private record FuncRow(String roleCode, String funcCode, String funcScope, String menuId) {
    }

    @SuppressWarnings("unchecked")
    private Set<String> loadFieldCodes(Set<String> roleCodes) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cached = attrs.getAttribute(REQUEST_ATTR_FIELD_CODES, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof Set<?> set) return (Set<String>) set;
        }
        String placeholders = roleCodes.stream().map(c -> "?").collect(Collectors.joining(","));
        Set<String> result;
        try {
            result = Set.copyOf(jdbcTemplate.queryForList(
                    "SELECT DISTINCT f.field_code FROM sys_role_field_rel rf "
                            + "JOIN sys_role_runtime r ON r.role_id = rf.role_id "
                            + "JOIN sys_field_meta f ON f.field_id = rf.field_id "
                            + "WHERE r.status = 'NORMAL' AND f.status = 'NORMAL' AND r.role_code IN ("
                            + placeholders + ")",
                    String.class,
                    roleCodes.toArray()));
        } catch (Exception e) {
            // fail-closed：查库失败按无字段权限处理（脱敏置空）
            return Collections.emptySet();
        }
        if (attrs != null) {
            attrs.setAttribute(REQUEST_ATTR_FIELD_CODES, result, RequestAttributes.SCOPE_REQUEST);
        }
        return result;
    }
}
