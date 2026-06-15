package com.erp.common.api;

import java.util.Map;

public record PageRequest(
        Integer pageNo,
        Integer pageSize,
        String sortField,
        String sortOrder,
        Map<String, Object> filters
) {
    public int safePageNo() {
        return pageNo == null || pageNo < 1 ? 1 : pageNo;
    }

    public int safePageSize() {
        return pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 200);
    }
}
