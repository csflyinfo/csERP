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
        return new PageResult<>(records, request.safePageNo(), request.safePageSize(), records.size(), Map.of());
    }
}
