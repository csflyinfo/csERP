package com.erp.common.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public record PageResult<T>(
        List<T> records,
        int pageNo,
        int pageSize,
        long total,
        Map<String, Object> summary
) {
    public static <T> PageResult<T> of(List<T> records, PageRequest request) {
        List<T> filteredRecords = filter(records, request.filters());
        int pageNo = request.safePageNo();
        int pageSize = request.safePageSize();
        int fromIndex = Math.min((pageNo - 1) * pageSize, filteredRecords.size());
        int toIndex = Math.min(fromIndex + pageSize, filteredRecords.size());
        return new PageResult<>(filteredRecords.subList(fromIndex, toIndex), pageNo, pageSize, filteredRecords.size(), Map.of());
    }

    private static <T> List<T> filter(List<T> records, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return records;
        List<String> values = filters.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .filter(entry -> !("treeNode".equals(entry.getKey()) && String.valueOf(entry.getValue()).startsWith("全部")))
                .map(entry -> String.valueOf(entry.getValue()).trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .toList();
        if (values.isEmpty()) return records;
        return records.stream()
                .filter(record -> matches(record, values))
                .toList();
    }

    private static boolean matches(Object record, List<String> values) {
        if (!(record instanceof Map<?, ?> map)) return true;
        String rowText = map.values().stream()
                .filter(value -> value != null)
                .map(String::valueOf)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + " " + right);
        return values.stream().allMatch(rowText::contains);
    }
}
