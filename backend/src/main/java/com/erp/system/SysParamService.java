package com.erp.system;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统参数统一读取入口（PRD-26 §5.2）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>进程内 volatile 全量缓存 + 写后整体失效。参数量级只有几十条，全量重载成本极低，
 *       不做单 key 失效以避免一致性漏洞。</li>
 *   <li>查库异常不抛出，返回调用方传入的 fallback。参数服务永不成为业务链路的故障点，
 *       与既有 {@code try/catch (Exception ignore)} 裸查写法行为一致。</li>
 *   <li>getInt 内置 min/max 钳制，避免各调用点重复写边界判断。</li>
 * </ul>
 *
 * <p>本期只让 PRD-26 新增的 11 项参数走本 Service，存量裸查逻辑
 * （如 {@code TmsDeliveryAppController.loadArriveConfig()}）保持不动。
 */
@Service
public class SysParamService {
    private final JdbcTemplate jdbcTemplate;

    /** param_key -> COALESCE(param_value, default_value)。null 表示尚未加载或已失效。 */
    private volatile Map<String, String> cache = null;

    public SysParamService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 取参数原始字符串。查库失败或参数不存在时返回 fallback。
     * 参数值为空串时同样视为未配置，返回 fallback。
     */
    public String get(String key, String fallback) {
        Map<String, String> snapshot = snapshot();
        if (snapshot == null) {
            return fallback;
        }
        String v = snapshot.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    /**
     * 取布尔参数。只认 {@code Y}（忽略大小写），其余一切值（含空、null、true、1）均视为 false。
     * 仅在查库失败时返回 fallback —— 保证默认 Y 的参数降级后不影响存量业务。
     */
    public boolean getBool(String key, boolean fallback) {
        Map<String, String> snapshot = snapshot();
        if (snapshot == null) {
            return fallback;
        }
        String v = snapshot.get(key);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        return "Y".equalsIgnoreCase(v.trim());
    }

    /**
     * 取整型参数并钳制到 [min, max]。解析失败或查库失败回落 fallback（fallback 本身也参与钳制）。
     */
    public int getInt(String key, int fallback, int min, int max) {
        int value = fallback;
        Map<String, String> snapshot = snapshot();
        if (snapshot != null) {
            String v = snapshot.get(key);
            if (v != null && !v.isEmpty()) {
                try {
                    value = Integer.parseInt(v.trim());
                } catch (NumberFormatException ignore) {
                    value = fallback;
                }
            }
        }
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    /**
     * 批量取参数，返回 key -> value 的副本（不含未配置的 key）。查库失败返回空 Map。
     */
    public Map<String, String> getAll(Collection<String> keys) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, String> snapshot = snapshot();
        if (snapshot == null || keys == null) {
            return result;
        }
        for (String key : keys) {
            String v = snapshot.get(key);
            if (v != null && !v.isEmpty()) {
                result.put(key, v);
            }
        }
        return result;
    }

    /**
     * 缓存整体失效。参数写入接口（/system/param/update、/system/param/batch-update）
     * 必须在提交后调用，否则改了参数不生效、重启才对。
     */
    public void evict() {
        this.cache = null;
    }

    /**
     * 返回缓存快照；未加载时尝试加载。查库失败返回 null（由调用方回落 fallback），且不写入缓存，
     * 保证下次调用会重试。
     */
    private Map<String, String> snapshot() {
        Map<String, String> local = this.cache;
        if (local != null) {
            return local;
        }
        try {
            Map<String, String> loaded = new HashMap<>();
            jdbcTemplate.queryForList("""
                    SELECT param_key, COALESCE(param_value, default_value) AS v
                    FROM sys_param_runtime
                    """).forEach(row -> {
                Object k = row.get("param_key");
                if (k != null) {
                    Object v = row.get("v");
                    loaded.put(String.valueOf(k).trim(), v == null ? "" : String.valueOf(v).trim());
                }
            });
            this.cache = loaded;
            return loaded;
        } catch (Exception ignore) {
            return null;
        }
    }
}
