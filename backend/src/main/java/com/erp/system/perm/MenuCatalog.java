package com.erp.system.perm;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码声明菜单的内存目录（PRD-28 §6.4.1）。
 *
 * <p>Bean 构造即做结构校验（深度 ≤3、编码唯一、层级类型合法），违规直接启动失败（fail-fast）。
 * 供 PermissionRegistry 同步、菜单管理「恢复默认」、perm-inventory 盘点共用。
 */
@Component
public class MenuCatalog {

    private final List<MenuNode> roots;
    private final Map<String, MenuNode> byCode = new LinkedHashMap<>();

    public MenuCatalog(List<MenuNode> menuRoots) {
        this.roots = menuRoots;
    }

    @PostConstruct
    void validate() {
        for (MenuNode root : roots) {
            if (root.level() != 1 || root.getType() != MenuNode.Type.DIR) {
                throw new IllegalStateException("菜单根节点必须是一级目录：" + root.getCode());
            }
            if (root.subtreeDepth() > 3) {
                throw new IllegalStateException("菜单[" + root.getCode() + "]深度超过 3 级（实际 "
                        + root.subtreeDepth() + " 级）");
            }
            walk(root);
        }
    }

    private void walk(MenuNode node) {
        MenuNode old = byCode.put(node.getCode(), node);
        if (old != null) {
            throw new IllegalStateException("菜单编码重复：" + node.getCode());
        }
        for (MenuNode child : node.getChildren()) {
            if (child.getParent() != node) {
                throw new IllegalStateException("菜单父子链断裂：" + child.getCode());
            }
            walk(child);
        }
    }

    public List<MenuNode> roots() {
        return roots;
    }

    /** 指定端的一级目录（appType 完全匹配；ALL 占位菜单不入此列）。 */
    public List<MenuNode> roots(String appType) {
        return roots.stream().filter(r -> r.getAppType().equals(appType)).toList();
    }

    public List<MenuNode> all() {
        return new ArrayList<>(byCode.values());
    }

    public MenuNode byCode(String code) {
        return byCode.get(code);
    }

    public boolean containsCode(String code) {
        return byCode.containsKey(code);
    }

    /** 代码默认上级编码（根目录返回 null）。 */
    public String defaultParentCode(String code) {
        MenuNode node = byCode.get(code);
        if (node == null || node.getParent() == null) return null;
        return node.getParent().getCode();
    }
}
