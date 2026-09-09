package com.erp.system.perm;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单/页面声明节点（PRD-28 §6.4.1）。
 *
 * <p>链式构造：根用 {@link #rootDir}；目录下挂页面 {@link #page} 返回目录自身；
 * 挂二级子目录 {@link #dir} 返回子节点，子节点挂完用 {@link #end} 回到父节点。
 *
 * <p>层级约束（与 sys_menu_meta 服务层校验一致）：
 * L1 只能是 DIR；DIR 只能在 L1/L2；PAGE 只能在 L2/L3；PAGE 不能有下级；整树深度 ≤3。
 */
public class MenuNode {

    public enum Type { DIR, PAGE }

    /** 三端标识：ERP / WMS_PDA / DRIVER。 */
    private final String appType;
    private final Type type;
    private final String code;
    private String name;
    private String routePath;
    private String componentPath;
    private String icon;
    private boolean adminOnly;
    /** 显式声明该页面有状态机（自动派生 audit/unaudit/close 功能点）。 */
    private boolean stateMachine;
    private int sortOrder;

    private MenuNode parent;
    private final List<MenuNode> children = new ArrayList<>();

    private MenuNode(Type type, String code, String name, String routePath, String componentPath,
                     String icon, String appType) {
        this.type = type;
        this.code = code;
        this.name = name;
        this.routePath = routePath;
        this.componentPath = componentPath;
        this.icon = icon;
        this.appType = appType;
    }

    /** L1 根目录。 */
    public static MenuNode rootDir(String code, String name, String icon, String appType) {
        return new MenuNode(Type.DIR, code, name, null, null, icon, appType);
    }

    /** 挂一个页面（L2 或 L3，取决于当前节点层级），返回当前目录以便继续链式挂载。 */
    public MenuNode page(String code, String name, String routePath) {
        return page(code, name, routePath, null);
    }

    public MenuNode page(String code, String name, String routePath, String componentPath) {
        MenuNode page = new MenuNode(Type.PAGE, code, name, routePath, componentPath, null, this.appType);
        addChild(page);
        return this;
    }

    /** 挂一个 SYS_ADMIN 专属页面（不出现在角色授权树），返回当前目录以便继续链式挂载。 */
    public MenuNode adminPage(String code, String name, String routePath) {
        page(code, name, routePath);
        children.get(children.size() - 1).adminOnly = true;
        return this;
    }

    /** 挂一个状态机页面（自动派生 audit/unaudit/close 功能点），返回当前目录。 */
    public MenuNode statePage(String code, String name, String routePath) {
        page(code, name, routePath);
        children.get(children.size() - 1).stateMachine = true;
        return this;
    }

    /** 挂一个二级子目录（仅 L1 目录可挂），返回子目录；挂完页面用 {@link #end} 回到父目录。 */
    public MenuNode dir(String code, String name) {
        return dir(code, name, null);
    }

    public MenuNode dir(String code, String name, String icon) {
        MenuNode sub = new MenuNode(Type.DIR, code, name, null, null, icon, this.appType);
        addChild(sub);
        return sub;
    }

    public MenuNode end() {
        return parent;
    }

    public MenuNode adminOnly() {
        this.adminOnly = true;
        return this;
    }

    public MenuNode stateMachine() {
        this.stateMachine = true;
        return this;
    }

    public MenuNode icon(String icon) {
        this.icon = icon;
        return this;
    }

    private void addChild(MenuNode child) {
        int parentLevel = level();
        if (child.type == Type.PAGE && parentLevel > 2) {
            throw new IllegalStateException("菜单[" + child.code + "]挂到第 " + (parentLevel + 1)
                    + " 层，PAGE 只能在第 2/3 层");
        }
        if (child.type == Type.DIR && parentLevel != 1) {
            throw new IllegalStateException("子目录[" + child.code + "]只能挂在一级目录下（DIR 只能在第 1/2 层）");
        }
        child.parent = this;
        child.sortOrder = children.size() * 10 + 10;
        children.add(child);
    }

    public int level() {
        int lv = 1;
        MenuNode p = parent;
        while (p != null) {
            lv++;
            p = p.parent;
        }
        return lv;
    }

    /** 子树最大深度（自身=1）。 */
    public int subtreeDepth() {
        if (children.isEmpty()) return 1;
        return 1 + children.stream().mapToInt(MenuNode::subtreeDepth).max().orElse(1);
    }

    public Type getType() { return type; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getRoutePath() { return routePath; }
    public String getComponentPath() { return componentPath; }
    public String getIcon() { return icon; }
    public String getAppType() { return appType; }
    public boolean isAdminOnly() { return adminOnly; }
    public boolean isStateMachine() { return stateMachine; }
    public int getSortOrder() { return sortOrder; }
    public MenuNode getParent() { return parent; }
    public List<MenuNode> getChildren() { return children; }
}
