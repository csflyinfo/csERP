package com.erp.system.perm;

import com.erp.common.api.ApiResponse;
import com.erp.common.security.CurrentUser;
import com.erp.common.security.PermissionDeniedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单/功能点/敏感字段查询与权限健康检查（PRD-28 §9.4）。
 *
 * <p>访问控制：
 * <ul>
 *   <li>/system/menu/tree、grant-tree、/system/func/list、/system/field/list、/perm/health、
 *       /perm/refresh —— SecurityConfig 已限定 SYS_ADMIN（角色配置页与巡检用）；</li>
 *   <li>/system/menu/user-tree、/system/perm/mine —— 任意登录用户可取「自己的」权限。</li>
 * </ul>
 */
@RestController
@RequestMapping("/system")
public class PermissionQueryController {

    private final MenuMetaService menuMetaService;
    private final PermissionRegistry registry;
    private final JdbcTemplate jdbc;

    public PermissionQueryController(MenuMetaService menuMetaService, PermissionRegistry registry,
                                     JdbcTemplate jdbc) {
        this.menuMetaService = menuMetaService;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    /** 全量菜单树（含 admin_only、STOPPED、自定义标志），菜单管理页用。 */
    @GetMapping("/menu/tree")
    public ApiResponse<List<Map<String, Object>>> menuTree(@RequestParam(defaultValue = "ERP") String appType) {
        return ApiResponse.ok(menuMetaService.managementTree(appType));
    }

    /** 角色授权用树：排除 admin_only 与 STOPPED，自动剪掉空目录。 */
    @GetMapping("/menu/grant-tree")
    public ApiResponse<List<Map<String, Object>>> grantTree(@RequestParam(defaultValue = "ERP") String appType) {
        return ApiResponse.ok(menuMetaService.grantTree(appType));
    }

    /** 某菜单下的功能点（角色配置页勾选）。 */
    @GetMapping("/func/list")
    public ApiResponse<List<Map<String, Object>>> funcList(@RequestParam String menuId) {
        return ApiResponse.ok(menuMetaService.funcsByMenu(menuId));
    }

    /** 全部敏感字段定义（角色配置页勾选可见字段）。 */
    @GetMapping("/field/list")
    public ApiResponse<List<Map<String, Object>>> fieldList() {
        return ApiResponse.ok(menuMetaService.fieldList());
    }

    /** 当前登录用户的功能点编码 + 可见敏感字段编码（前端 v-permission、敏感列显隐）。 */
    @GetMapping("/perm/mine")
    public ApiResponse<Map<String, Object>> mine() {
        return ApiResponse.ok(menuMetaService.mine());
    }

    /** 手动触发一次元数据同步（发布后不重启刷新）。SYS_ADMIN 硬校验双保险。 */
    @PostMapping("/perm/refresh")
    public ApiResponse<Map<String, Object>> refresh() {
        if (!CurrentUser.isSuperAdmin()) {
            throw new PermissionDeniedException("仅系统管理员可刷新权限元数据");
        }
        return ApiResponse.ok(registry.sync());
    }

    /**
     * 权限体系健康检查：元数据同步统计、已停用（代码删除）菜单/功能点数量、
     * 未挂 @RequirePerm 的写接口清单（卡片 5~7 逐步清零）。
     */
    @GetMapping("/perm/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lastSync", registry.getLastSyncStats());

        Integer stoppedMenus = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_menu_meta WHERE status = 'STOPPED' AND is_system = TRUE",
                Integer.class);
        Integer stoppedFuncs = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_func_meta WHERE status = 'STOPPED' AND is_system = TRUE",
                Integer.class);
        out.put("stoppedSystemMenus", stoppedMenus);
        out.put("stoppedSystemFuncs", stoppedFuncs);

        PermissionRegistry.ScanResult scan = registry.scanMappings();
        out.put("unguardedWriteCount", scan.unguardedWrites().size());
        out.put("unguardedWrites", scan.unguardedWrites().stream()
                .map(w -> Map.of("method", w.httpMethod(), "path", w.path(), "handler", w.handler()))
                .toList());

        // 同步器会把代码已删除的 MODULE 功能点置 STOPPED；正常 NORMAL 集合里不应再有孤儿，
        // stoppedFuncs 仅作信息展示（历史遗留/手工数据），健康与否看未纳管写接口是否清零（分卡片收敛）
        out.put("healthy", scan.unguardedWrites().isEmpty());
        return ApiResponse.ok(out);
    }
}
