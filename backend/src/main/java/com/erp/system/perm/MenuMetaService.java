package com.erp.system.perm;

import com.erp.common.security.CurrentUser;
import com.erp.common.security.PermissionService;
import com.erp.system.OperationLogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 菜单/功能/字段查询与【模块菜单管理】写操作（PRD-28 §9.4、§10.3）。
 *
 * <p>写操作 7 条保存校验全部在此：层级≤3、DIR/PAGE 层位规则、防环、空目录确认、
 * 同级同名拒绝、admin_only 禁移动、STOPPED 禁移动。改名/移动/排序置对应 *_customized 标志，
 * 所有变更写 MENU_CHANGE 审计日志。
 */
@Service
public class MenuMetaService {

    private final JdbcTemplate jdbc;
    private final MenuCatalog catalog;
    private final PermissionRegistry registry;
    private final PermissionService permissionService;
    private final OperationLogService opLog;

    public MenuMetaService(JdbcTemplate jdbc, MenuCatalog catalog, PermissionRegistry registry,
                           PermissionService permissionService, OperationLogService opLog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.registry = registry;
        this.permissionService = permissionService;
        this.opLog = opLog;
    }

    // ==================== 查询 ====================

    /** 管理用全量树：含 admin_only 与 STOPPED（置底灰显），带全部管理字段。 */
    public List<Map<String, Object>> managementTree(String appType) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT menu_id, parent_id, app_type, menu_code, menu_name, menu_type, route_path, " +
                "component_path, icon, sort_order, visible, admin_only, name_customized, " +
                "parent_customized, sort_customized, status FROM sys_menu_meta " +
                "WHERE app_type = ? ORDER BY status DESC, sort_order, menu_code", appType);
        return buildTree(rows, true);
    }

    /** 角色授权树：排除 admin_only 与 STOPPED，并剪掉无授权子项的空目录（§9.4）。 */
    public List<Map<String, Object>> grantTree(String appType) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT menu_id, parent_id, app_type, menu_code, menu_name, menu_type, route_path, " +
                "icon, sort_order, admin_only, status FROM sys_menu_meta " +
                "WHERE app_type = ? AND status = 'NORMAL' AND admin_only = FALSE ORDER BY sort_order, menu_code",
                appType);
        return buildTree(rows, true);
    }

    /**
     * 当前登录用户菜单树（兼容旧前端 code/name/path/children 结构，支持三级）。
     * SYS_ADMIN 全量（含 admin_only）；其他用户按 sys_role_menu_rel 求并集，排除 admin_only。
     * 无任何菜单授权时返回空列表（不做全量兜底，防止越权看到全部页面）。
     */
    public List<Map<String, Object>> userTree(String appType) {
        CurrentUser.Principal user = CurrentUser.get();
        if (user == null) return List.of();

        List<Map<String, Object>> rows;
        if (user.isSuperAdmin()) {
            rows = jdbc.queryForList(
                    "SELECT menu_id, parent_id, menu_code, menu_name, menu_type, route_path, sort_order, " +
                    "visible, status FROM sys_menu_meta " +
                    "WHERE app_type = ? AND status = 'NORMAL' AND visible = TRUE ORDER BY sort_order, menu_code",
                    appType);
        } else {
            Set<String> codes = user.roleCodes();
            if (codes.isEmpty()) return List.of();
            String ph = codes.stream().map(c -> "?").collect(Collectors.joining(","));
            rows = jdbc.queryForList(
                    "SELECT DISTINCT m.menu_id, m.parent_id, m.menu_code, m.menu_name, m.menu_type, " +
                    "m.route_path, m.sort_order, m.visible, m.status FROM sys_menu_meta m " +
                    "JOIN sys_role_menu_rel rm ON rm.menu_id = m.menu_id " +
                    "JOIN sys_role_runtime r ON r.role_id = rm.role_id " +
                    "WHERE m.app_type = ? AND m.status = 'NORMAL' AND m.visible = TRUE " +
                    "AND m.admin_only = FALSE AND r.status = 'NORMAL' AND r.role_code IN (" + ph + ") " +
                    "ORDER BY m.sort_order, m.menu_code",
                    prepend(appType, codes));
        }
        return buildUserTree(rows);
    }

    public List<Map<String, Object>> funcsByMenu(String menuId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT func_id, menu_id, func_code, func_name, func_scope, func_type, " +
                "api_method, api_path, sort_order, status FROM sys_func_meta " +
                "WHERE menu_id = ? ORDER BY sort_order, func_code", menuId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) out.add(camelRow(row));
        return out;
    }

    public List<Map<String, Object>> fieldList() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT field_id, field_code, field_name, field_group, module_scope, remark, status " +
                "FROM sys_field_meta ORDER BY field_group, field_code");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) out.add(camelRow(row));
        return out;
    }

    /** 当前用户功能/字段编码集合（前端 v-permission、敏感列显隐一次性拉取）。 */
    public Map<String, Object> mine() {
        CurrentUser.Principal user = CurrentUser.get();
        Map<String, Object> out = new LinkedHashMap<>();
        if (user == null) {
            out.put("superAdmin", false);
            out.put("funcs", List.of());
            out.put("fields", List.of());
            return out;
        }
        out.put("superAdmin", user.isSuperAdmin());
        out.put("roleCodes", user.roleCodes());
        if (user.isSuperAdmin()) {
            // 超管前端按角色短路，不需要全量编码
            out.put("funcs", List.of());
            out.put("fields", List.of());
            return out;
        }
        out.put("funcs", new ArrayList<>(permissionService.currentFuncCodes()));

        Set<String> codes = user.roleCodes();
        if (codes.isEmpty()) {
            out.put("fields", List.of());
            return out;
        }
        String ph = codes.stream().map(c -> "?").collect(Collectors.joining(","));
        List<String> fields = jdbc.queryForList(
                "SELECT DISTINCT f.field_code FROM sys_role_field_rel rf " +
                "JOIN sys_role_runtime r ON r.role_id = rf.role_id " +
                "JOIN sys_field_meta f ON f.field_id = rf.field_id " +
                "WHERE r.status = 'NORMAL' AND f.status = 'NORMAL' AND r.role_code IN (" + ph + ")",
                String.class, codes.toArray());
        out.put("fields", fields);
        return out;
    }

    // ==================== 写操作（模块菜单管理） ====================

    @Transactional
    public Map<String, Object> rename(String menuId, String newName) {
        Map<String, Object> node = mustFind(menuId);
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("菜单名称不能为空");
        if (name.length() > 100) throw new IllegalArgumentException("菜单名称不能超过 100 字");
        String before = str(node.get("menu_name"));
        if (name.equals(before)) return Map.of("warning", "");
        Integer dup;
        if (node.get("parent_id") == null) {
            dup = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM sys_menu_meta WHERE parent_id IS NULL AND menu_name = ? AND menu_id <> ?",
                    Integer.class, name, menuId);
        } else {
            dup = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM sys_menu_meta WHERE parent_id = ? AND menu_name = ? AND menu_id <> ?",
                    Integer.class, node.get("parent_id"), name, menuId);
        }
        if (dup != null && dup > 0) throw new IllegalArgumentException("同一上级下已存在同名菜单：" + name);

        jdbc.update("UPDATE sys_menu_meta SET menu_name = ?, name_customized = TRUE, " +
                "updated_at = CURRENT_TIMESTAMP WHERE menu_id = ?", name, menuId);
        audit(str(node.get("menu_code")), "RENAME",
                "{\"menuName\":{\"before\":\"" + escape(before) + "\",\"after\":\"" + escape(name) + "\"}}");
        return Map.of("warning", "");
    }

    /**
     * 更换上级。
     * @param confirm true=已知晓空目录提示，强制执行；false 且旧目录将变空时返回 needConfirm 不落库
     */
    @Transactional
    public Map<String, Object> move(String menuId, String newParentId, boolean confirm) {
        Map<String, Object> node = mustFind(menuId);
        if ("STOPPED".equals(str(node.get("status")))) {
            throw new IllegalArgumentException("已停用（代码已删除）的菜单不允许移动");
        }
        if (Boolean.TRUE.equals(node.get("admin_only"))) {
            // 校验规则 6：admin_only 节点不允许移动，避免超管把入口弄丢
            throw new IllegalArgumentException("系统内置专属菜单不允许移动");
        }
        String oldParentId = node.get("parent_id") == null ? null : str(node.get("parent_id"));
        if (eqId(oldParentId, newParentId)) return Map.of("warning", "");

        Map<String, Object> target = newParentId == null ? null : mustFind(newParentId);
        int targetLevel = target == null ? 0 : levelOf(target);
        int nodeDepth = dbSubtreeDepth(node, new LinkedHashSet<>());
        // 规则 1：目标层级 + 自身子树深度 ≤ 3
        if (targetLevel + nodeDepth > 3) {
            throw new IllegalArgumentException("移动后菜单超过三级层级上限");
        }
        // 规则 2：DIR 只能在 1/2 层；PAGE 只能在 2/3 层
        if ("DIR".equals(str(node.get("menu_type"))) && targetLevel + 1 > 2) {
            throw new IllegalArgumentException("目录只能放在第 1、2 层");
        }
        if ("PAGE".equals(str(node.get("menu_type"))) && (targetLevel + 1 < 2 || targetLevel + 1 > 3)) {
            throw new IllegalArgumentException("页面只能放在第 2、3 层");
        }
        if (target != null && !"DIR".equals(str(target.get("menu_type")))) {
            throw new IllegalArgumentException("上级菜单必须是目录");
        }
        // 规则 3：防环——不能移到自己或自己的后代下
        if (target != null && (eqId(str(target.get("menu_id")), menuId) || isDescendant(menuId, newParentId))) {
            throw new IllegalArgumentException("不能把菜单移动到自己或自己的子级下");
        }
        // 规则 5：同级同名
        Integer dup;
        if (newParentId == null) {
            dup = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM sys_menu_meta WHERE parent_id IS NULL AND menu_name = ? AND menu_id <> ?",
                    Integer.class, node.get("menu_name"), menuId);
        } else {
            dup = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM sys_menu_meta WHERE parent_id = ? AND menu_name = ? AND menu_id <> ?",
                    Integer.class, newParentId, node.get("menu_name"), menuId);
        }
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("目标上级下已存在同名菜单：" + node.get("menu_name"));
        }
        // 规则 4：旧目录会变空目录 → 需二次确认（空目录对所有用户隐藏）
        String warning = "";
        if (!confirm && oldParentId != null && !eqId(oldParentId, newParentId)
                && remainingPageCount(oldParentId, menuId) == 0) {
            String oldName = jdbc.queryForObject("SELECT menu_name FROM sys_menu_meta WHERE menu_id = ?",
                    String.class, oldParentId);
            return Map.of("needConfirm", true,
                    "warning", "移动后「" + oldName + "」将成为空目录并对所有用户隐藏，确认继续？");
        }
        if (oldParentId != null && !eqId(oldParentId, newParentId)
                && remainingPageCount(oldParentId, menuId) == 0) {
            String oldName = jdbc.queryForObject("SELECT menu_name FROM sys_menu_meta WHERE menu_id = ?",
                    String.class, oldParentId);
            warning = "「" + oldName + "」已成为空目录，将对所有用户隐藏";
        }

        jdbc.update("UPDATE sys_menu_meta SET parent_id = ?, parent_customized = TRUE, " +
                "updated_at = CURRENT_TIMESTAMP WHERE menu_id = ?", newParentId, menuId);
        audit(str(node.get("menu_code")), "MOVE",
                "{\"parentId\":{\"before\":\"" + escape(nullToEmpty(oldParentId))
                        + "\",\"after\":\"" + escape(nullToEmpty(newParentId)) + "\"}}");
        return warning.isEmpty() ? Map.of("warning", "") : Map.of("warning", warning);
    }

    /** 同级批量排序，body 为 [{menuId, sortOrder}]；被拖节点置 sort_customized=TRUE。 */
    @Transactional
    public Map<String, Object> sort(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("排序列表为空");
        // 先统一校验（存在性 + 必须同一上级），全部通过才落库，避免半写半回滚
        record SortItem(String menuId, int sortOrder) {}
        List<SortItem> parsed = new ArrayList<>();
        Set<String> parentIds = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            String menuId = str(item.get("menuId"));
            Object sortRaw = item.get("sortOrder");
            int sort = sortRaw instanceof Number n ? n.intValue() : Integer.parseInt(str(sortRaw));
            Map<String, Object> node = mustFind(menuId);
            parentIds.add(node.get("parent_id") == null ? null : str(node.get("parent_id")));
            parsed.add(new SortItem(menuId, sort));
        }
        if (parentIds.size() > 1) {
            throw new IllegalArgumentException("一次只能调整同一上级下的菜单顺序");
        }
        for (SortItem item : parsed) {
            jdbc.update("UPDATE sys_menu_meta SET sort_order = ?, sort_customized = TRUE, " +
                    "updated_at = CURRENT_TIMESTAMP WHERE menu_id = ?", item.sortOrder(), item.menuId());
        }
        audit("SORT_BATCH", "SORT", "{\"count\":" + items.size() + "}");
        return Map.of("warning", "");
    }

    /** 单节点恢复默认：清 *_customized 标志并立即按 MenuConfig 重取名称/上级/排序。 */
    @Transactional
    public Map<String, Object> resetOne(String menuId) {
        Map<String, Object> node = mustFind(menuId);
        String code = str(node.get("menu_code"));
        jdbc.update("UPDATE sys_menu_meta SET name_customized = FALSE, parent_customized = FALSE, " +
                "sort_customized = FALSE, updated_at = CURRENT_TIMESTAMP WHERE menu_id = ?", menuId);
        int applied = applyCodeDefaults(code);
        audit(code, "RESET", "{\"applied\":" + applied + "}");
        return Map.of("warning", "");
    }

    /** 整树恢复默认：清全部标志，触发一次同步按代码重算（需二次确认，由前端保证）。 */
    @Transactional
    public Map<String, Object> resetAll() {
        jdbc.update("UPDATE sys_menu_meta SET name_customized = FALSE, parent_customized = FALSE, " +
                "sort_customized = FALSE WHERE is_system = TRUE");
        Map<String, Object> stats = registry.sync();
        audit("ALL", "RESET_ALL", "{}");
        return stats;
    }

    /** 按代码声明重取单个菜单的名称/上级/排序（reset 后立即生效，不等重启）。 */
    private int applyCodeDefaults(String code) {
        MenuNode node = catalog.byCode(code);
        if (node == null) return 0;
        String parentId = node.getParent() == null ? null : PermissionRegistry.menuIdOf(node.getParent().getCode());
        jdbc.update("UPDATE sys_menu_meta SET menu_name = ?, parent_id = ?, sort_order = ?, " +
                "updated_at = CURRENT_TIMESTAMP WHERE menu_code = ?",
                node.getName(), parentId, node.getSortOrder(), code);
        return 1;
    }

    // ==================== 校验/树工具 ====================

    private int levelOf(Map<String, Object> node) {
        int level = 1;
        String parentId = node.get("parent_id") == null ? null : str(node.get("parent_id"));
        Set<String> guard = new LinkedHashSet<>();
        while (parentId != null && guard.add(parentId)) {
            level++;
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT parent_id FROM sys_menu_meta WHERE menu_id = ?", parentId);
            if (rows.isEmpty()) break;
            Object pid = rows.get(0).get("parent_id");
            parentId = pid == null ? null : str(pid);
        }
        return level;
    }

    /** candidateId 是否为 ancestorId 的后代（沿 parent_id 上溯能遇到 ancestorId）。 */
    private boolean isDescendant(String ancestorId, String candidateId) {
        String cur = candidateId;
        Set<String> guard = new LinkedHashSet<>();
        while (cur != null && guard.add(cur)) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT parent_id FROM sys_menu_meta WHERE menu_id = ?", cur);
            if (rows.isEmpty()) return false;
            Object pid = rows.get(0).get("parent_id");
            if (pid == null) return false;
            cur = str(pid);
            if (cur.equals(ancestorId)) return true;
        }
        return false;
    }

    /** 以 node 为根、忽略 ignoreChildId（待移走的节点自身）时的子树最大深度（自身=1，查 DB 当前结构）。 */
    private int dbSubtreeDepth(Map<String, Object> node, Set<String> guard) {
        String id = str(node.get("menu_id"));
        if (!guard.add(id)) return 0;
        List<Map<String, Object>> children = jdbc.queryForList(
                "SELECT menu_id, parent_id, menu_type FROM sys_menu_meta WHERE parent_id = ? AND status = 'NORMAL'", id);
        if (children.isEmpty()) return 1;
        return 1 + children.stream().mapToInt(c -> dbSubtreeDepth(c, guard)).max().orElse(1);
    }

    private int remainingPageCount(String parentId, String movingMenuId) {
        return jdbc.queryForObject("SELECT COUNT(1) FROM sys_menu_meta " +
                "WHERE parent_id = ? AND menu_id <> ? AND status = 'NORMAL'", Integer.class, parentId, movingMenuId);
    }

    private Map<String, Object> mustFind(String menuId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT menu_id, parent_id, menu_code, menu_name, menu_type, sort_order, visible, " +
                "admin_only, status FROM sys_menu_meta WHERE menu_id = ?", menuId);
        if (rows.isEmpty()) throw new IllegalArgumentException("菜单不存在：" + menuId);
        return rows.get(0);
    }

    private void audit(String bizNo, String action, String detailJson) {
        try {
            opLog.log("system.menu", action, bizNo, detailJson);
        } catch (Exception ignored) {
            // 审计失败不阻断菜单操作
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildTree(List<Map<String, Object>> rows, boolean management) {
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            nodes.put(str(row.get("menu_id")), camelRow(row));
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> n : nodes.values()) {
            String pid = n.get("parentId") == null ? null : String.valueOf(n.get("parentId"));
            if (pid == null || !nodes.containsKey(pid)) {
                roots.add(n);
            } else {
                ((List<Map<String, Object>>) nodes.get(pid).computeIfAbsent("children", k -> new ArrayList<>())).add(n);
            }
        }
        return roots;
    }

    /** 旧前端消费的精简树：code/name/path/children；空目录裁掉。 */
    private List<Map<String, Object>> buildUserTree(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("code", row.get("menu_code"));
            n.put("name", row.get("menu_name"));
            n.put("path", row.get("route_path") == null ? "" : row.get("route_path"));
            n.put("menuType", row.get("menu_type"));
            n.put("children", new ArrayList<Map<String, Object>>());
            nodes.put(str(row.get("menu_id")), n);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        // 记录每个节点的 parent_id（精简节点 n 里没存 parentId）
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object pid = row.get("parent_id");
            parentOf.put(str(row.get("menu_id")), pid == null ? null : str(pid));
        }
        for (Map<String, Object> row : rows) {
            String id = str(row.get("menu_id"));
            String pid = parentOf.get(id);
            Map<String, Object> n = nodes.get(id);
            if (pid == null || !nodes.containsKey(pid)) {
                roots.add(n);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> kids = (List<Map<String, Object>>) nodes.get(pid).get("children");
                kids.add(n);
            }
        }
        pruneEmptyDirs(roots);
        return roots;
    }

    /** 无页面后代的空目录对用户无意义，递归裁掉（管理树保留结构，不走这里）。 */
    @SuppressWarnings("unchecked")
    private void pruneEmptyDirs(List<Map<String, Object>> nodes) {
        var it = nodes.iterator();
        while (it.hasNext()) {
            Map<String, Object> n = it.next();
            List<Map<String, Object>> kids = (List<Map<String, Object>>) n.get("children");
            pruneEmptyDirs(kids);
            // 先递归裁子级；子级裁空后，自身若是目录则一并移除（PAGE 叶子保留）
            if ("DIR".equals(n.get("menuType")) && kids.isEmpty()) {
                it.remove();
            }
        }
    }

    /** H2 列标签大写，queryForList 原始 Map 序列化键会全大写，统一显式重建驼峰 Map。 */
    private Map<String, Object> camelRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        putCamel(out, "menuId", row.get("menu_id"));
        putCamel(out, "parentId", row.get("parent_id"));
        putCamel(out, "appType", row.get("app_type"));
        putCamel(out, "code", row.get("menu_code"));
        putCamel(out, "name", row.get("menu_name"));
        putCamel(out, "menuType", row.get("menu_type"));
        putCamel(out, "path", row.get("route_path"));
        putCamel(out, "componentPath", row.get("component_path"));
        putCamel(out, "icon", row.get("icon"));
        putCamel(out, "sortOrder", row.get("sort_order"));
        putCamel(out, "visible", row.get("visible"));
        putCamel(out, "adminOnly", row.get("admin_only"));
        putCamel(out, "nameCustomized", row.get("name_customized"));
        putCamel(out, "parentCustomized", row.get("parent_customized"));
        putCamel(out, "sortCustomized", row.get("sort_customized"));
        putCamel(out, "status", row.get("status"));
        // 功能点/字段行附带的列（同名键不冲突时直接补）
        putCamel(out, "funcId", row.get("func_id"));
        putCamel(out, "funcCode", row.get("func_code"));
        putCamel(out, "funcName", row.get("func_name"));
        putCamel(out, "funcScope", row.get("func_scope"));
        putCamel(out, "funcType", row.get("func_type"));
        putCamel(out, "apiMethod", row.get("api_method"));
        putCamel(out, "apiPath", row.get("api_path"));
        putCamel(out, "fieldId", row.get("field_id"));
        putCamel(out, "fieldCode", row.get("field_code"));
        putCamel(out, "fieldName", row.get("field_name"));
        putCamel(out, "fieldGroup", row.get("field_group"));
        putCamel(out, "moduleScope", row.get("module_scope"));
        putCamel(out, "remark", row.get("remark"));
        out.put("children", new ArrayList<Map<String, Object>>());
        return out;
    }

    private void putCamel(Map<String, Object> out, String key, Object value) {
        if (value != null) out.put(key, value);
    }

    private Object[] prepend(String first, Set<String> rest) {
        List<Object> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all.toArray();
    }

    private boolean eqId(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
