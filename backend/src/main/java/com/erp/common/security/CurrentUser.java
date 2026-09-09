package com.erp.common.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 当前登录用户（请求级 ThreadLocal）。
 *
 * <p>由 {@code JwtAuthFilter} 从 JWT claims 填充、请求结束清理；Service/Controller 通过
 * {@link #get()} 取当前人，不再各自解析 SecurityContext / request 属性。
 *
 * <p>多角色：一个用户可挂多个角色（sys_user_role_rel），功能/数据权限取并集；
 * {@code primaryRoleCode} 仅用于展示名与默认数据范围。appType 区分 ERP/WMS_PDA/DRIVER 三端。
 */
public final class CurrentUser {

    public record Principal(
            String userId,
            String username,
            String displayName,
            String employeeId,
            Set<String> roleCodes,
            String primaryRoleCode,
            String appType,       // ERP / WMS_PDA / DRIVER
            String warehouseId    // PDA 登录时选择的仓库，其他端可为空
    ) {
        public boolean hasRole(String code) {
            return code != null && roleCodes.contains(code);
        }

        /** 内置超管：权限短路，所有菜单/功能/字段/数据范围全放行。 */
        public boolean isSuperAdmin() {
            return roleCodes.contains("SYS_ADMIN");
        }

        public boolean isPda() {
            return "WMS_PDA".equals(appType);
        }
    }

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private CurrentUser() {}

    public static void set(Principal p) {
        HOLDER.set(p);
    }

    public static Principal get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 未登录场景的空对象，避免到处判空。 */
    public static Principal empty() {
        return new Principal(null, null, null, null, Collections.emptySet(), null, "ERP", null);
    }

    public static Principal of(String userId, String username, String displayName, String employeeId,
                               Set<String> roleCodes, String primaryRoleCode,
                               String appType, String warehouseId) {
        Set<String> roles = roleCodes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(roleCodes);
        String app = (appType == null || appType.isBlank()) ? "ERP" : appType;
        return new Principal(userId, username, displayName, employeeId, roles, primaryRoleCode, app, warehouseId);
    }

    /** 当前是否为超管；未登录返回 false。 */
    public static boolean isSuperAdmin() {
        Principal p = HOLDER.get();
        return p != null && p.isSuperAdmin();
    }
}
