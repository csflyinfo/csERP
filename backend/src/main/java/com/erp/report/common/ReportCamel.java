package com.erp.report.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 报表行蛇形别名转驼峰（H2 不带引号的别名会被拉成大写，破坏前端契约）。
 * 从原 ReportController#camelize 提取，报表模块统一使用。
 */
public final class ReportCamel {

    private ReportCamel() {}

    public static Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(camel(e.getKey()), e.getValue());
        }
        return out;
    }

    public static List<Map<String, Object>> camelize(List<Map<String, Object>> rows) {
        return rows.stream().map(ReportCamel::camelize).toList();
    }

    /** 蛇形 key 转驼峰；已是驼峰的 key 原样返回（全小写后按 _ 拼接）。 */
    public static String camel(String key) {
        if (key == null) return null;
        String k = key.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(k.length());
        boolean upper = false;
        for (char c : k.toCharArray()) {
            if (c == '_') {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }
}
