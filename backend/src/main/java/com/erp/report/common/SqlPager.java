package com.erp.report.common;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报表 SQL 端分页（禁止内存分页）：COUNT 包子查询 + LIMIT/OFFSET（H2/MySQL 均支持）。
 *
 * <p>排序字段走服务端白名单（前端只传逻辑字段名），杜绝排序注入；
 * 深分页已在 {@link ReportQueryRequest} 入口拦截。
 */
public final class SqlPager {

    private SqlPager() {}

    /** 一段拼好的查询：SELECT...FROM...WHERE 1=1 [+ scope/filters]，可选 GROUP BY 片段。 */
    public static class Query {
        private final StringBuilder base = new StringBuilder();
        private final List<Object> args = new ArrayList<>();
        private String groupBy = "";

        public StringBuilder sql() { return base; }
        public List<Object> args() { return args; }
        public Query groupBy(String groupBySql) { this.groupBy = groupBySql == null ? "" : groupBySql; return this; }
    }

    public record PageData(List<Map<String, Object>> rows, long total, int pageNo, int pageSize) {}

    /**
     * @param sortWhitelist 逻辑排序字段 → SQL 表达式（如 {@code amount -> "amount"} 或
     *                      汇总查询的 {@code netAmount -> "net_amount"}）；不在表内的字段直接忽略
     * @param defaultOrder  默认 ORDER BY 片段（含 "ORDER BY"），sortField 为空时使用
     */
    public static PageData page(JdbcTemplate jdbc, ReportQueryRequest req, Query q,
                                Map<String, String> sortWhitelist, String defaultOrder) {
        String wrappedCount = "SELECT COUNT(*) FROM (" + q.base + q.groupBy + ") _pc";
        Long total = jdbc.queryForObject(wrappedCount, Long.class, q.args.toArray());

        String order = resolveOrder(req, sortWhitelist, defaultOrder);
        String pageSql = q.base + q.groupBy + order + " LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(q.args);
        pageArgs.add(req.pageSize());
        pageArgs.add((req.pageNo() - 1) * req.pageSize());
        List<Map<String, Object>> rows = jdbc.queryForList(pageSql, pageArgs.toArray());
        return new PageData(rows, total == null ? 0 : total, req.pageNo(), req.pageSize());
    }

    private static String resolveOrder(ReportQueryRequest req, Map<String, String> whitelist, String defaultOrder) {
        String field = req.sortField();
        if (field == null || whitelist == null || !whitelist.containsKey(field)) {
            return " " + defaultOrder;
        }
        return " ORDER BY " + whitelist.get(field) + (req.isAsc() ? " ASC" : " DESC");
    }
}
