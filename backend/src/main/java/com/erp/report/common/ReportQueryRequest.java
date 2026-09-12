package com.erp.report.common;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表统一查询入参（区别于业务列表的 {@code PageRequest}）：
 * {@code {dateRange, filters, groupBy, pageNo, pageSize, sortField, sortOrder}}。
 *
 * <p>报表禁止内存过滤/分页：filters 由各报表服务按白名单自行翻译为 SQL 条件，
 * 本类只提供类型安全的取值方法。
 */
public class ReportQueryRequest {

    /** 报表页最大每页条数（前端 100/500/1000，服务端兜底）。 */
    public static final int MAX_PAGE_SIZE = 1000;
    /** 深分页护栏：offset 超过 10 万行禁止翻页，引导走异步导出。 */
    public static final int MAX_OFFSET = 100_000;

    private final ReportDateRange range;
    private final Map<String, Object> filters;
    private final List<String> groupBy;
    private final int pageNo;
    private final int pageSize;
    private final String sortField;
    private final String sortOrder;

    private ReportQueryRequest(ReportDateRange range, Map<String, Object> filters, List<String> groupBy,
                               int pageNo, int pageSize, String sortField, String sortOrder) {
        this.range = range;
        this.filters = filters;
        this.groupBy = groupBy;
        this.pageNo = pageNo;
        this.pageSize = pageSize;
        this.sortField = sortField;
        this.sortOrder = sortOrder;
    }

    public static ReportQueryRequest from(Map<String, Object> body) {
        return from(body, false);
    }

    @SuppressWarnings("unchecked")
    public static ReportQueryRequest from(Map<String, Object> body, boolean naturalMonthDefault) {
        Map<String, Object> b = body == null ? Map.of() : body;
        ReportDateRange range = ReportDateRange.from(b, naturalMonthDefault);
        Map<String, Object> filters = b.get("filters") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        List<String> groupBy = new ArrayList<>();
        if (b.get("groupBy") instanceof List<?> l) {
            for (Object o : l) {
                if (o != null && !String.valueOf(o).isBlank()) groupBy.add(String.valueOf(o));
            }
        }
        int pageNo = intVal(b.get("pageNo"), 1);
        int pageSize = intVal(b.get("pageSize"), 100);
        if (pageNo < 1) pageNo = 1;
        if (pageSize < 1) pageSize = 100;
        if (pageSize > MAX_PAGE_SIZE) pageSize = MAX_PAGE_SIZE;
        if ((long) (pageNo - 1) * pageSize > MAX_OFFSET) {
            throw new IllegalArgumentException("翻页深度超过 10 万行，请缩小查询范围或使用异步导出");
        }
        String sortField = b.get("sortField") == null ? null : String.valueOf(b.get("sortField")).trim();
        String sortOrder = b.get("sortOrder") == null ? null : String.valueOf(b.get("sortOrder")).trim();
        return new ReportQueryRequest(range, filters, groupBy, pageNo, pageSize,
                (sortField == null || sortField.isEmpty()) ? null : sortField,
                (sortOrder == null || sortOrder.isEmpty()) ? null : sortOrder);
    }

    public ReportDateRange range() { return range; }
    public Map<String, Object> filters() { return filters; }
    public List<String> groupBy() { return groupBy; }
    public int pageNo() { return pageNo; }
    public int pageSize() { return pageSize; }
    public String sortField() { return sortField; }
    public String sortOrder() { return sortOrder; }

    public boolean isAsc() {
        return "asc".equalsIgnoreCase(sortOrder) || "ascending".equalsIgnoreCase(sortOrder);
    }

    /** filters 中取去空白字符串；不存在/空白返回 null。 */
    public String text(String key) {
        Object v = filters.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** filters 中取多值（数组/逗号分隔）；不存在/空列表返回空 List。 */
    public List<String> texts(String key) {
        Object v = filters.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o != null && !String.valueOf(o).trim().isEmpty()) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else if (v != null && !String.valueOf(v).trim().isEmpty()) {
            for (String s : String.valueOf(v).split(",")) {
                if (!s.trim().isEmpty()) out.add(s.trim());
            }
        }
        return out;
    }

    public BigDecimal decimal(String key) {
        String s = text(key);
        if (s == null) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("筛选条件「" + key + "」不是有效数字");
        }
    }

    private static int intVal(Object v, int dflt) {
        if (v == null) return dflt;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
