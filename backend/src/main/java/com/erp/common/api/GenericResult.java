package com.erp.common.api;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GenericResult {
    private GenericResult() {
    }

    public static Map<String, Object> row(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    public static Map<String, Object> operation(String moduleCode, String action) {
        return row(
                "moduleCode", moduleCode,
                "action", action,
                "success", true,
                "operateAt", LocalDateTime.now().toString()
        );
    }
}
