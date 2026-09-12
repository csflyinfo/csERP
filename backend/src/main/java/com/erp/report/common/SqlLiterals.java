package com.erp.report.common;

import java.util.List;

/**
 * SQL 字面量渲染：把 {@code ?} 占位符按出现顺序替换为内联字面量。
 *
 * <p>唯一用途：头窗口分页路径——H2 2.2 存在「CTE 绑定参数 + 后续窗口 CTE 结果被置空」
 * 缺陷，窗口分页 SQL 必须零参数。跳过单引号字符串字面量内部的问号，
 * 字符串参数做单引号翻倍转义。MySQL 8 下同样合法。
 */
public final class SqlLiterals {

    private SqlLiterals() {
    }

    public static String render(String sql, List<Object> args) {
        StringBuilder out = new StringBuilder(sql.length() + 64);
        int ai = 0;
        boolean inStr = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                out.append(ch);
                if (inStr && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                } else {
                    inStr = !inStr;
                }
            } else if (ch == '?' && !inStr) {
                if (ai >= args.size()) {
                    throw new IllegalStateException("窗口分页 SQL 占位符多于参数：" + sql);
                }
                out.append(toLiteral(args.get(ai++)));
            } else {
                out.append(ch);
            }
        }
        if (ai != args.size()) {
            throw new IllegalStateException("窗口分页 SQL 参数多于占位符：剩余 " + (args.size() - ai));
        }
        return out.toString();
    }

    private static String toLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof java.time.LocalDate || v instanceof java.time.LocalDateTime) {
            return "'" + v + "'";
        }
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Enum<?> e) return "'" + e.name().replace("'", "''") + "'";
        return "'" + v.toString().replace("'", "''") + "'";
    }
}
