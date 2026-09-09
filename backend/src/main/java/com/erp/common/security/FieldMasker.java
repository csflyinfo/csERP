package com.erp.common.security;

import com.erp.system.perm.SensitiveFieldRegistry;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 敏感字段响应脱敏器（PRD-28 §6.4.4，卡片4；卡片6 增加调用点 profile）。
 *
 * <p>项目 Controller 普遍返回 {@code Map}（JdbcTemplate，无 VO 层），脱敏在响应出参上做：
 * 按 {@link SensitiveFieldRegistry} 把 Map key 映射到 field_code，当前用户无该字段权限时
 * 把值置 null（键保留，前端列还在、值显示「—」）。List / 嵌套 Map 递归处理，
 * 因此分页对象的 records、导出查询的行集都可直接丢进来。
 *
 * <p><b>同名 key 的跨模块语义（卡片6 落地补充）</b>：{@code price}/{@code amount} 等通用 key
 * 在销售响应里是售价/销售金额、在采购响应里是采购价/采购金额，全局注册表无法一对一映射。
 * 调用点通过 {@code keyOverrides}（见 {@link MaskProfiles}）显式声明本负载的 key→field 归属，
 * 覆盖映射优先于全局注册表。
 *
 * <p>SYS_ADMIN 短路不脱敏；字段权限查库失败 fail-closed（按无权限脱敏）。
 */
@Component
public class FieldMasker {

    private final SensitiveFieldRegistry registry;
    private final PermissionService permissionService;

    public FieldMasker(SensitiveFieldRegistry registry, PermissionService permissionService) {
        this.registry = registry;
        this.permissionService = permissionService;
    }

    /** 脱敏任意响应负载：Map / List / PageResult 风格对象，仅按全局注册表匹配 key。 */
    public void mask(Object payload) {
        mask(payload, (Map<String, String>) null);
    }

    /**
     * 按调用点 key 覆盖表脱敏（{@link MaskProfiles} 预置销售/采购各单据的 profile）。
     * 覆盖表大小写不敏感；未出现在覆盖表中的 key 仍回落到全局注册表。
     */
    public void mask(Object payload, Map<String, String> keyOverrides) {
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null || p.isSuperAdmin()) return;
        Map<String, String> overrides = null;
        if (keyOverrides != null && !keyOverrides.isEmpty()) {
            overrides = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            overrides.putAll(keyOverrides);
        }
        walk(payload, permissionService.currentFieldCodes(), overrides);
    }

    /** 用指定授权集合脱敏（测试/内部任务无 ThreadLocal 用户时用）。 */
    public void mask(Object payload, Set<String> allowedFieldCodes) {
        walk(payload, allowedFieldCodes == null ? Set.of() : allowedFieldCodes, null);
    }

    /**
     * 导出链路脱敏（方案 GLOBAL-002）：在普通字段脱敏基础上，<b>成本/毛利类字段</b>还要求
     * 独立的全局功能点 {@code global.data_export_sensitive}——页面上能看到成本的角色，
     * 未授「敏感数据导出」时导出文件里这些列仍留空。超管短路。
     */
    private static final Set<String> EXPORT_RESTRICTED_FIELDS = Set.of(
            "VIEW_COST", "VIEW_COST_AMOUNT", "VIEW_STOCK_COST",
            "VIEW_PROFIT", "VIEW_PROFIT_TOTAL");

    /** 导出专用：按字段权限 + 敏感导出开关脱敏（无 key 覆盖表）。 */
    public void maskExport(Object payload) {
        maskExport(payload, null);
    }

    /** 导出专用：按字段权限 + 敏感导出开关脱敏（带调用点 key 覆盖表）。 */
    public void maskExport(Object payload, Map<String, String> keyOverrides) {
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null || p.isSuperAdmin()) return;
        Set<String> allowed = new HashSet<>(permissionService.currentFieldCodes());
        if (!permissionService.hasFunc("global.data_export_sensitive")) {
            allowed.removeAll(EXPORT_RESTRICTED_FIELDS);
        }
        Map<String, String> overrides = null;
        if (keyOverrides != null && !keyOverrides.isEmpty()) {
            overrides = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            overrides.putAll(keyOverrides);
        }
        walk(payload, Set.copyOf(allowed), overrides);
    }

    private void walk(Object node, Set<String> allowed, Map<String, String> overrides) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                Object value = e.getValue();
                String fieldCode = resolveField(key, overrides);
                if (fieldCode != null && !allowed.contains(fieldCode)) {
                    ((Map<String, Object>) map).put(key, null);
                } else if (value instanceof Map || value instanceof List) {
                    walk(value, allowed, overrides);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map || item instanceof List) walk(item, allowed, overrides);
            }
        }
    }

    /** 调用点覆盖优先，其次全局注册表（H2 大写下划线列标签与驼峰手工 key 都能命中）。 */
    private String resolveField(String key, Map<String, String> overrides) {
        if (overrides != null) {
            String mapped = overrides.get(key);
            if (mapped != null) return mapped;
        }
        return registry.fieldOfKey(key);
    }
}
