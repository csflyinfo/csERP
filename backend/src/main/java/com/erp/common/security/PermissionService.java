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
