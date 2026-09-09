package com.erp.system.perm;

import com.erp.common.security.RequirePerm;
import com.erp.system.OperationLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限元数据同步器（PRD-28 §6.4.5）。
 *
 * <p>启动后（ApplicationReadyEvent）及 POST /system/perm/refresh 时，把代码声明的
 * 菜单（{@link MenuCatalog}）、功能点（@RequirePerm 扫描 + 标准派生）、敏感字段（注册表对拍）
 * upsert 进 sys_menu_meta / sys_func_meta；代码中已删除的 is_system 记录置 STOPPED（不物理删）。
 * 同步后给 SYS_ADMIN 自动补齐全部 NORMAL 菜单/功能/字段授权。
 *
 * <p>菜单名称/上级/排序受 *_customized 标志保护（§6.4.5 字段归属），路由/类型/图标/admin_only
 * 由代码强制覆盖。整个同步幂等，重复执行结果不变。
 */
@Component
public class PermissionRegistry {

    private static final Logger log = LoggerFactory.getLogger(PermissionRegistry.class);

    /** 标准功能点：所有页面都生成。 */
    private static final String[][] BASE_FUNCS = {
            {"view", "查看"}, {"add", "新增"}, {"edit", "编辑"}, {"delete", "删除"},
            {"import", "导入"}, {"export", "导出"}, {"print", "打印"}, {"log", "日志"}
    };
    /** 状态机功能点：有状态机页面（显式声明或路径探测命中）才生成。 */
    private static final String[][] STATE_FUNCS = {
            {"audit", "审核"}, {"unaudit", "反审核"}, {"close", "关闭/作废"}
    };
    private static final String[][] ALL_ACTIONS_NAME = {
            {"view", "查看"}, {"add", "新增"}, {"edit", "编辑"}, {"delete", "删除"},
            {"audit", "审核"}, {"unaudit", "反审核"}, {"close", "关闭/作废"},
            {"import", "导入"}, {"export", "导出"}, {"print", "打印"}, {"log", "日志"}
    };

    private final JdbcTemplate jdbc;
    private final MenuCatalog catalog;
    private final SensitiveFieldRegistry fields;
    private final RequestMappingHandlerMapping handlerMapping;
    private final OperationLogService opLog;

    private Map<String, Object> lastSyncStats = Map.of();

    public PermissionRegistry(JdbcTemplate jdbc, MenuCatalog catalog, SensitiveFieldRegistry fields,
                              @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
                              OperationLogService opLog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.fields = fields;
        this.handlerMapping = handlerMapping;
        this.opLog = opLog;
    }

    /** 扫描结果：注解声明的功能点 + 未挂注解的写接口（perm/health 与盘点工具用）。 */
    public record ScanResult(List<FuncDecl> funcs, List<WriteEndpoint> unguardedWrites) {}

    public record FuncDecl(String code, String name, String scope, String funcType,
                           String apiMethod, String apiPath) {}

    public record WriteEndpoint(String httpMethod, String path, String handler) {}

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        try {
            Map<String, Object> stats = sync();
            log.info("权限同步完成：菜单 {}（新增{} 停用{}），功能点 {}（新增{} 停用{}），字段 {} 项全部对拍通过",
                    stats.get("menuTotal"), stats.get("menuInserted"), stats.get("menuStopped"),
                    stats.get("funcTotal"), stats.get("funcInserted"), stats.get("funcStopped"),
                    stats.get("fieldCount"));
        } catch (Exception e) {
            // 元数据同步失败不能静默，否则授权页面/拦截器行为无依据
            log.error("权限元数据同步失败", e);
            throw e;
        }
    }

    public synchronized Map<String, Object> sync() {
        ScanResult scan = scanMappings();
        validateFuncPrefixes(scan.funcs());

        int[] menuStats = syncMenus();
        int[] funcStats = syncFuncs(scan.funcs());
        ensureSuperAdminGrants();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("menuTotal", catalog.all().size());
        stats.put("menuInserted", menuStats[0]);
        stats.put("menuStopped", menuStats[1]);
        stats.put("funcTotal", funcStats[2]);
        stats.put("funcInserted", funcStats[0]);
        stats.put("funcStopped", funcStats[1]);
        stats.put("fieldCount", fields.fieldCodes().size());
        stats.put("unguardedWriteCount", scan.unguardedWrites().size());
        this.lastSyncStats = stats;

        int changed = menuStats[0] + menuStats[1] + funcStats[0] + funcStats[1];
        if (changed > 0) {
            try {
                opLog.log("system.perm", "PERM_SYNC", null,
                        "{\"menuInserted\":" + menuStats[0] + ",\"menuStopped\":" + menuStats[1]
                                + ",\"funcInserted\":" + funcStats[0] + ",\"funcStopped\":" + funcStats[1] + "}");
            } catch (Exception ignored) {
                // 同步日志失败不影响主流程
            }
        }
        return stats;
    }

    public Map<String, Object> getLastSyncStats() {
        return lastSyncStats;
    }

    // ==================== 菜单同步 ====================

    /** @return [新增数, 停用数] */
    private int[] syncMenus() {
        int inserted = 0;
        int stopped = 0;
        Set<String> liveCodes = new LinkedHashSet<>();

        for (MenuNode node : catalog.all()) {
            liveCodes.add(node.getCode());
            String parentId = node.getParent() == null ? null : menuIdOf(node.getParent().getCode());
            List<Map<String, Object>> exists = jdbc.queryForList(
                    "SELECT menu_id, parent_id, name_customized, parent_customized, sort_customized " +
                    "FROM sys_menu_meta WHERE menu_code = ?", node.getCode());
            if (exists.isEmpty()) {
                jdbc.update("INSERT INTO sys_menu_meta(menu_id, parent_id, app_type, menu_code, menu_name, " +
                                "menu_type, route_path, component_path, icon, sort_order, visible, admin_only, " +
                                "name_customized, parent_customized, sort_customized, is_system, status) " +
                                "VALUES (?,?,?,?,?,?,?,?,?,?,TRUE,?,FALSE,FALSE,FALSE,TRUE,'NORMAL')",
                        menuIdOf(node.getCode()), parentId, node.getAppType(), node.getCode(), node.getName(),
                        node.getType().name(), node.getRoutePath(), node.getComponentPath(), node.getIcon(),
                        node.getSortOrder(), node.isAdminOnly());
                inserted++;
            } else {
                Map<String, Object> row = exists.get(0);
                String menuId = String.valueOf(row.get("menu_id"));
                boolean nameCust = boolVal(row.get("name_customized"));
                boolean parentCust = boolVal(row.get("parent_customized"));
                boolean sortCust = boolVal(row.get("sort_customized"));
                // 上级：parent_customized=TRUE 时保留管理员调整结果，否则取代码默认
                String effectiveParent = parentCust
                        ? (row.get("parent_id") == null ? null : String.valueOf(row.get("parent_id")))
                        : parentId;
                // 代码强制字段：上级（受标志保护）/app_type/类型/路由/组件/图标/admin_only/is_system/status
                jdbc.update("UPDATE sys_menu_meta SET parent_id = ?, app_type = ?, menu_type = ?, " +
                                "route_path = ?, component_path = ?, icon = ?, admin_only = ?, " +
                                "is_system = TRUE, status = 'NORMAL', updated_at = CURRENT_TIMESTAMP " +
                                "WHERE menu_id = ?",
                        effectiveParent,
                        node.getAppType(), node.getType().name(), node.getRoutePath(),
                        node.getComponentPath(), node.getIcon(), node.isAdminOnly(), menuId);
                // 管理员自定义领地：仅标志 FALSE 时用代码默认覆盖
                if (!nameCust) {
                    jdbc.update("UPDATE sys_menu_meta SET menu_name = ? WHERE menu_id = ?", node.getName(), menuId);
                }
                if (!sortCust) {
                    jdbc.update("UPDATE sys_menu_meta SET sort_order = ? WHERE menu_id = ?",
                            node.getSortOrder(), menuId);
                }
            }
        }

        // 代码中已删除的 is_system 菜单置 STOPPED（'_global_' 为 V102 全局功能占位，保留）
        stopped = jdbc.update("UPDATE sys_menu_meta SET status = 'STOPPED', updated_at = CURRENT_TIMESTAMP " +
                "WHERE is_system = TRUE AND status = 'NORMAL' AND menu_code <> '_global_' " +
                "AND menu_code NOT IN (" + placeholders(liveCodes) + ")", liveCodes.toArray());
        return new int[]{inserted, stopped};
    }

    // ==================== 功能点同步 ====================

    /** @return [新增数, 停用数, 现存总数] */
    private int[] syncFuncs(List<FuncDecl> annotated) {
        Map<String, FuncDecl> decls = new LinkedHashMap<>();

        // 1) 标准功能点派生（PAGE 菜单 × 8 基础 + 状态机 3 个）
        for (MenuNode node : catalog.all()) {
            if (node.getType() != MenuNode.Type.PAGE) continue;
            boolean state = node.isStateMachine() || pathHeuristicStateMachine(node);
            String[][] groups = state ? concatGroups() : BASE_FUNCS;
            for (int i = 0; i < groups.length; i++) {
                String code = node.getCode() + "." + groups[i][0];
                decls.put(code, new FuncDecl(code, groups[i][1], "MODULE", "BUTTON", null, null));
            }
        }
        // 2) 注解扫描结果覆盖派生值（名称/api 以注解为准）
        for (FuncDecl f : annotated) {
            decls.put(f.code(), f);
        }

        int inserted = 0;
        for (FuncDecl f : decls.values()) {
            String menuId = f.scope().equals("GLOBAL")
                    ? "M_GLOBAL_FUNC"
                    : menuIdOf(f.code().substring(0, f.code().lastIndexOf('.')));
            List<String> existIds = jdbc.queryForList(
                    "SELECT func_id FROM sys_func_meta WHERE func_code = ?", String.class, f.code());
            if (existIds.isEmpty()) {
                jdbc.update("INSERT INTO sys_func_meta(func_id, menu_id, func_code, func_name, func_scope, " +
                                "func_type, api_method, api_path, sort_order, is_system, status) " +
                                "VALUES (?,?,?,?,?,?,?,?,?,TRUE,'NORMAL')",
                        funcIdOf(f.code()), menuId, f.code(), f.name(), f.scope(), f.funcType(),
                        f.apiMethod(), f.apiPath(), 0);
                inserted++;
            } else {
                jdbc.update("UPDATE sys_func_meta SET menu_id = ?, func_name = ?, func_scope = ?, " +
                        "api_method = COALESCE(?, api_method), api_path = COALESCE(?, api_path), " +
                        "status = 'NORMAL' WHERE func_id = ?",
                        menuId, f.name(), f.scope(), f.apiMethod(), f.apiPath(), existIds.get(0));
            }
        }

        // 3) 孤儿：MODULE 范围代码已删除的 is_system 功能点置 STOPPED。
        //    GLOBAL 功能点由 V102 种子持有、注解在后续卡片补齐，不参与停用判定。
        int stopped = jdbc.update("UPDATE sys_func_meta SET status = 'STOPPED' " +
                "WHERE is_system = TRUE AND status = 'NORMAL' AND func_scope = 'MODULE' " +
                "AND func_code NOT IN (" + placeholders(decls.keySet()) + ")", decls.keySet().toArray());
        int total = jdbc.queryForObject("SELECT COUNT(1) FROM sys_func_meta WHERE status='NORMAL'", Integer.class);
        return new int[]{inserted, stopped, total};
    }

    /**
     * 路径探测状态机（§6.4.3）：该页面路由前缀下存在 audit/approve、reverse-audit/unaudit、close
     * 结尾的 Mapping 时，自动派生对应功能点。
     */
    private boolean pathHeuristicStateMachine(MenuNode page) {
        String prefix = page.getRoutePath();
        if (prefix == null) return false;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            for (String pattern : patternsOf(e.getKey())) {
                String seg = pattern.startsWith(prefix + "/") ? pattern.substring(prefix.length() + 1) : null;
                if (seg == null || seg.contains("/")) continue;
                String lower = seg.toLowerCase();
                if (lower.contains("audit") || lower.equals("approve") || lower.equals("close")) return true;
            }
        }
        return false;
    }

    private String[][] concatGroups() {
        String[][] all = new String[BASE_FUNCS.length + STATE_FUNCS.length][2];
        System.arraycopy(BASE_FUNCS, 0, all, 0, BASE_FUNCS.length);
        System.arraycopy(STATE_FUNCS, 0, all, BASE_FUNCS.length, STATE_FUNCS.length);
        return all;
    }

    // ==================== @RequirePerm 扫描 ====================

    /** §6.5 强约束：非 global 功能点前缀必须是已声明菜单，否则启动失败。 */
    private void validateFuncPrefixes(List<FuncDecl> funcs) {
        for (FuncDecl f : funcs) {
            if (f.code().startsWith("global.")) continue;
            int dot = f.code().lastIndexOf('.');
            if (dot <= 0 || !catalog.containsCode(f.code().substring(0, dot))) {
                throw new IllegalStateException(
                        "@RequirePerm 功能点 " + f.code() + " 找不到归属菜单（func_code 必须是 <menu_code>.<动作>）");
            }
        }
    }

    public ScanResult scanMappings() {
        List<FuncDecl> funcs = new ArrayList<>();
        List<WriteEndpoint> unguarded = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            HandlerMethod hm = e.getValue();
            RequirePerm require = hm.getMethodAnnotation(RequirePerm.class);
            if (require == null) require = hm.getBeanType().getAnnotation(RequirePerm.class);
            // 编程式鉴权端点（@ProgrammaticPerm + 方法内 hasFunc 逐分支裁决）不计未鉴权清单
            boolean programmatic = hm.hasMethodAnnotation(com.erp.common.security.ProgrammaticPerm.class);

            Set<String> methods = new LinkedHashSet<>();
            info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            List<String> patterns = patternsOf(info);
            String path = patterns.isEmpty() ? "" : patterns.get(0);
            String httpMethod = methods.isEmpty() ? "" : methods.iterator().next();

            if (require != null) {
                if (seenCodes.add(require.value())) {
                    String name = require.name().isBlank() ? actionName(require.value()) : require.name();
                    funcs.add(new FuncDecl(require.value(), name,
                            require.global() ? "GLOBAL" : "MODULE",
                            require.type(), httpMethod, path));
                }
            } else if (!programmatic && isWriteMethod(httpMethod) && !isExemptPath(path)) {
                unguarded.add(new WriteEndpoint(httpMethod, path,
                        hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName()));
            }
        }
        return new ScanResult(funcs, unguarded);
    }

    private boolean isWriteMethod(String m) {
        return "POST".equals(m) || "PUT".equals(m) || "DELETE".equals(m) || "PATCH".equals(m);
    }

    /** 不需要功能点授权的写接口：登录登出、危险运维端点、菜单管理（SYS_ADMIN 硬校验）、登出打点等。 */
    private boolean isExemptPath(String path) {
        return path.startsWith("/auth/")
                || path.startsWith("/testing/")
                || path.startsWith("/flow/")
                || path.startsWith("/system/menu-manage/")
                // 通知/待办是每个登录用户的自助操作（已读/完成），不属于任何可授权菜单
                || path.startsWith("/system/notification/")
                || path.startsWith("/system/todo/")
                || path.startsWith("/actuator/")
                || path.startsWith("/tms/app/")
                || path.startsWith("/operation-log/")
                || path.startsWith("/error");
    }

    private List<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues().stream().sorted().toList();
        }
        return List.of();
    }

    private String actionName(String funcCode) {
        String action = funcCode.substring(funcCode.lastIndexOf('.') + 1);
        for (String[] pair : ALL_ACTIONS_NAME) {
            if (pair[0].equals(action)) return pair[1];
        }
        return action;
    }

    // ==================== SYS_ADMIN 自动补权 ====================

    private void ensureSuperAdminGrants() {
        List<String> roleIds = jdbc.queryForList(
                "SELECT role_id FROM sys_role_runtime WHERE role_code = 'SYS_ADMIN'", String.class);
        if (roleIds.isEmpty()) return;
        String roleId = roleIds.get(0);

        jdbc.update("INSERT INTO sys_role_menu_rel(id, role_id, menu_id, created_at) " +
                "SELECT 'RM' || SUBSTRING(REPLACE(CAST(RANDOM_UUID() AS VARCHAR),'-',''),1,14), ?, m.menu_id, CURRENT_TIMESTAMP " +
                "FROM sys_menu_meta m WHERE m.status = 'NORMAL' AND NOT EXISTS (" +
                "SELECT 1 FROM sys_role_menu_rel x WHERE x.role_id = ? AND x.menu_id = m.menu_id)",
                roleId, roleId);
        jdbc.update("INSERT INTO sys_role_func_rel(id, role_id, func_id, created_at) " +
                "SELECT 'RF' || SUBSTRING(REPLACE(CAST(RANDOM_UUID() AS VARCHAR),'-',''),1,14), ?, f.func_id, CURRENT_TIMESTAMP " +
                "FROM sys_func_meta f WHERE f.status = 'NORMAL' AND NOT EXISTS (" +
                "SELECT 1 FROM sys_role_func_rel x WHERE x.role_id = ? AND x.func_id = f.func_id)",
                roleId, roleId);
        jdbc.update("INSERT INTO sys_role_field_rel(id, role_id, field_id, created_at) " +
                "SELECT 'RD' || SUBSTRING(REPLACE(CAST(RANDOM_UUID() AS VARCHAR),'-',''),1,14), ?, f.field_id, CURRENT_TIMESTAMP " +
                "FROM sys_field_meta f WHERE f.status = 'NORMAL' AND NOT EXISTS (" +
                "SELECT 1 FROM sys_role_field_rel x WHERE x.role_id = ? AND x.field_id = f.field_id)",
                roleId, roleId);
    }

    // ==================== 工具 ====================

    private String placeholders(Set<String> codes) {
        return codes.stream().map(c -> "?").reduce((a, b) -> a + "," + b).orElse("''");
    }

    /** 32 长度内的确定性主键：优先可读形式，超长走 8 位 hex 哈希，避免 VARCHAR(32) 溢出。 */
    static String menuIdOf(String menuCode) {
        return id32("M_", menuCode.replace('.', '_'));
    }

    static String funcIdOf(String funcCode) {
        return id32("F_", funcCode.replace('.', '_'));
    }

    private static String id32(String prefix, String sanitized) {
        String raw = prefix + sanitized;
        if (raw.length() <= 32) return raw;
        String hash = Integer.toHexString(sanitized.hashCode());
        String id = prefix + hash;
        return id.length() > 32 ? id.substring(0, 32) : id;
    }

    private Boolean boolVal(Object v) {
        if (v == null) return Boolean.FALSE;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
