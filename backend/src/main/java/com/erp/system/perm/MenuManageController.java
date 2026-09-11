package com.erp.system.perm;

import com.erp.common.api.ApiResponse;
import com.erp.common.security.CurrentUser;
import com.erp.common.security.PermissionDeniedException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 【模块菜单管理】写接口（PRD-28 §10.3）。
 *
 * <p>双保险鉴权：SecurityConfig 已把 /system/** 限定 SYS_ADMIN，这里每个写操作再硬校验一次
 * （不走 @RequirePerm——菜单管理本身是权限体系的地基，不能被授权配置反向影响）。
 * 校验规则（层级≤3 / DIR/PAGE 层位 / 防环 / 空目录二次确认 / 同级同名 / admin_only 禁移动 /
 * STOPPED 禁移动）全部在 {@link MenuMetaService}，所有变更落 MENU_CHANGE 审计日志。
 */
@RestController
@RequestMapping("/system/menu-manage")
public class MenuManageController {

    private final MenuMetaService menuMetaService;

    public MenuManageController(MenuMetaService menuMetaService) {
        this.menuMetaService = menuMetaService;
    }

    /** 修改菜单名称（admin_only 节点允许改名）。body: {"name":"新名称"} */
    @PutMapping("/{menuId}/name")
    public ApiResponse<Map<String, Object>> rename(@PathVariable String menuId,
                                                   @RequestBody Map<String, Object> body) {
        requireSuperAdmin();
        Object name = body == null ? null : body.get("name");
        return ApiResponse.ok(menuMetaService.rename(menuId, name == null ? "" : String.valueOf(name)));
    }

    /**
     * 更换上级（newParentId 传 null/空串=移到一级）。
     * 移动会导致旧目录变空目录时首次返回 needConfirm=true，前端二次确认带 confirm=true 再调一次。
     * body: {"parentId":"M_xxx" 或 null, "confirm":false}
     */
    @PutMapping("/{menuId}/parent")
    public ApiResponse<Map<String, Object>> move(@PathVariable String menuId,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        requireSuperAdmin();
        String parentId = null;
        boolean confirm = false;
        if (body != null) {
            Object pid = body.get("parentId");
            if (pid != null && !String.valueOf(pid).isBlank()) parentId = String.valueOf(pid);
            Object c = body.get("confirm");
            confirm = Boolean.TRUE.equals(c) || "true".equalsIgnoreCase(String.valueOf(c));
        }
        return ApiResponse.ok(menuMetaService.move(menuId, parentId, confirm));
    }

    /** 同级批量排序。body: [{"menuId":"M_xxx","sortOrder":10}, ...]，必须同属一个上级。 */
    @PutMapping("/sort")
    public ApiResponse<Map<String, Object>> sort(@RequestBody List<Map<String, Object>> items) {
        requireSuperAdmin();
        return ApiResponse.ok(menuMetaService.sort(items));
    }

    /**
     * 新建自定义目录（一级或二级）。
     * body: {"appType":"ERP|WMS_PDA|DRIVER", "name":"目录名", "parentId":"M_xxx 或 null"}
     */
    @PostMapping("/dir")
    public ApiResponse<Map<String, Object>> createDir(@RequestBody Map<String, Object> body) {
        requireSuperAdmin();
        if (body == null) throw new IllegalArgumentException("请求体为空");
        String appType = String.valueOf(body.get("appType"));
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name"));
        Object pid = body.get("parentId");
        String parentId = pid == null || String.valueOf(pid).isBlank() ? null : String.valueOf(pid);
        return ApiResponse.ok(menuMetaService.createDir(appType, name, parentId));
    }

    /**
     * 设置菜单是否启用。停用级联整棵子树；启用仅自身且要求上级已启用。
     * body: {"enabled":true/false}
     */
    @PutMapping("/{menuId}/enabled")
    public ApiResponse<Map<String, Object>> setEnabled(@PathVariable String menuId,
                                                       @RequestBody Map<String, Object> body) {
        requireSuperAdmin();
        Object enabled = body == null ? null : body.get("enabled");
        boolean on = Boolean.TRUE.equals(enabled) || "true".equalsIgnoreCase(String.valueOf(enabled));
        return ApiResponse.ok(menuMetaService.setEnabled(menuId, on));
    }

    /** 删除空的自定义目录（内置菜单拒绝删除）。 */
    @PostMapping("/{menuId}/delete")
    public ApiResponse<Map<String, Object>> deleteDir(@PathVariable String menuId) {
        requireSuperAdmin();
        return ApiResponse.ok(menuMetaService.deleteCustomDir(menuId));
    }

    /** 单个菜单恢复代码默认（名称/上级/排序三个自定义标志一并清除并重取）。 */
    @PostMapping("/{menuId}/reset")
    public ApiResponse<Map<String, Object>> resetOne(@PathVariable String menuId) {
        requireSuperAdmin();
        return ApiResponse.ok(menuMetaService.resetOne(menuId));
    }

    /** 整树恢复默认（前端必须二次确认后调用）。 */
    @PostMapping("/reset-all")
    public ApiResponse<Map<String, Object>> resetAll(@RequestParam(defaultValue = "false") boolean confirm) {
        requireSuperAdmin();
        if (!confirm) throw new IllegalArgumentException("整树恢复默认将清除全部菜单自定义，需显式 confirm=true");
        return ApiResponse.ok(menuMetaService.resetAll());
    }

    private void requireSuperAdmin() {
        if (!CurrentUser.isSuperAdmin()) {
            throw new PermissionDeniedException("仅系统管理员可操作模块菜单管理");
        }
    }
}
