package com.erp.common.security;

import com.erp.system.perm.SensitiveFieldRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 敏感字段响应脱敏器（PRD-28 §6.4.4，卡片4）。
 *
 * <p>项目 Controller 普遍返回 {@code Map}（JdbcTemplate，无 VO 层），脱敏在响应出参上做：
 * 按 {@link SensitiveFieldRegistry} 把 Map key 映射到 field_code，当前用户无该字段权限时
 * 把值置 null（键保留，前端列还在、值显示「—」）。List / 嵌套 Map 递归处理，
 * 因此分页对象的 records、导出查询的行集都可直接丢进来。
 *
 * <p>SYS_ADMIN 短路不脱敏；字段权限查库失败 fail-closed（按无权限脱敏）。
 * 导出/打印链路同样调用本组件（区别仅在 global.export / global.data_export_sensitive
 * 两个全局功能点，由卡片6接入），避免文件导出绕过页面脱敏。
 */
@Component
public class FieldMasker {

    private final SensitiveFieldRegistry registry;
    private final PermissionService permissionService;

    public FieldMasker(SensitiveFieldRegistry registry, PermissionService permissionService) {
        this.registry = registry;
        this.permissionService = permissionService;
    }

    /** 脱敏任意响应负载：Map / List / PageResult 风格对象（records/total 等字段随 Map 递归）。 */
    public void mask(Object payload) {
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null || p.isSuperAdmin()) return;
        walk(payload, permissionService.currentFieldCodes());
    }

    /** 用指定授权集合脱敏（测试/内部任务无 ThreadLocal 用户时用）。 */
    public void mask(Object payload, Set<String> allowedFieldCodes) {
        walk(payload, allowedFieldCodes == null ? Set.of() : allowedFieldCodes);
    }

    private void walk(Object node, Set<String> allowed) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                Object value = e.getValue();
                String fieldCode = registry.fieldOfKey(key);
                if (fieldCode != null && !allowed.contains(fieldCode)) {
                    ((Map<String, Object>) map).put(key, null);
                } else if (value instanceof Map || value instanceof List) {
                    walk(value, allowed);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map || item instanceof List) walk(item, allowed);
            }
        }
    }
}
