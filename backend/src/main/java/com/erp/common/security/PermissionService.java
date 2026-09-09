package com.erp.common.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 功能权限查询服务（PRD-28 RBAC）。
 *
 * <p>按当前登录用户的角色集合，查 sys_role_func_rel × sys_func_meta 取已授权功能点编码。
 * SYS_ADMIN 短路全放行。结果在单次请求内缓存（请求属性），避免一次请求多次查库。
 */
@Service
public class PermissionService {

    private static final String REQUEST_ATTR_FUNC_CODES = "rbac.funcCodes";
    private static final String REQUEST_ATTR_FIELD_CODES = "rbac.fieldCodes";

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
        return loadFuncCodes(roleCodes).contains(funcCode);
    }

    /** 当前用户全部已授权功能点编码（供登录/当前用户接口一次性下发前端）。 */
    public Set<String> currentFuncCodes() {
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null) return Set.of();
        if (p.isSuperAdmin()) return Set.of(); // 超管前端按角色短路，不需要全量列表
        Set<String> roleCodes = p.roleCodes();
        if (roleCodes == null || roleCodes.isEmpty()) return Set.of();
        return loadFuncCodes(roleCodes);
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

    @SuppressWarnings("unchecked")
    private Set<String> loadFuncCodes(Set<String> roleCodes) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cached = attrs.getAttribute(REQUEST_ATTR_FUNC_CODES, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof Set<?> set) return (Set<String>) set;
        }
        String placeholders = roleCodes.stream().map(c -> "?").collect(Collectors.joining(","));
        List<String> codes;
        try {
            codes = jdbcTemplate.queryForList(
                    "SELECT DISTINCT f.func_code FROM sys_role_func_rel rf " +
                    "JOIN sys_role_runtime r ON r.role_id = rf.role_id " +
                    "JOIN sys_func_meta f ON f.func_id = rf.func_id " +
                    "WHERE r.status = 'NORMAL' AND f.status = 'NORMAL' AND r.role_code IN (" + placeholders + ")",
                    String.class,
                    roleCodes.toArray());
        } catch (Exception e) {
            // 鉴权查库失败按无权限处理（fail-closed），不放行
            return Collections.emptySet();
        }
        Set<String> result = Set.copyOf(codes);
        if (attrs != null) {
            attrs.setAttribute(REQUEST_ATTR_FUNC_CODES, result, RequestAttributes.SCOPE_REQUEST);
        }
        return result;
    }
}
