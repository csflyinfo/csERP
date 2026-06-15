package com.erp.common.api;

import java.util.ArrayList;
import java.util.Comparator;
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
        List<T> filteredRecords = sort(filter(records, request.filters()), request.sortField(), request.sortOrder());
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

    private static <T> List<T> sort(List<T> records, String sortField, String sortOrder) {
        if (sortField == null || sortField.isBlank()) return records;
        List<T> sortedRecords = new ArrayList<>(records);
        Comparator<T> comparator = (left, right) -> compareSortValues(sortValue(left, sortField), sortValue(right, sortField));
        if ("desc".equalsIgnoreCase(sortOrder) || "descending".equalsIgnoreCase(sortOrder)) {
            comparator = comparator.reversed();
        }
        sortedRecords.sort(comparator);
        return sortedRecords;
    }

    private static Comparable<?> sortValue(Object record, String sortField) {
        if (!(record instanceof Map<?, ?> map)) return null;
        Object value = map.get(sortField);
        if (value == null) return null;
        if (value instanceof Number number) return number.doubleValue();
        return String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private static int compareSortValues(Comparable<?> left, Comparable<?> right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }
}
