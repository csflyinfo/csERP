package com.erp.common.api;

import java.util.List;
import java.util.Map;

public record PageResult<T>(
        List<T> records,
        int pageNo,
        int pageSize,
        long total,
        Map<String, Object> summary
) {
    public static <T> PageResult<T> of(List<T> records, PageRequest request) {
        int pageNo = request.safePageNo();
        int pageSize = request.safePageSize();
        int fromIndex = Math.min((pageNo - 1) * pageSize, records.size());
        int toIndex = Math.min(fromIndex + pageSize, records.size());
        return new PageResult<>(records.subList(fromIndex, toIndex), pageNo, pageSize, records.size(), Map.of());
    }
}
