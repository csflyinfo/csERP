package com.erp.report.meta;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表元数据注册中心：Spring 注入所有 {@link ReportDefinition}，按 code 索引。
 * Controller 与异步导出 worker 都只面向注册表，不感知具体报表类。
 */
@Component
public class ReportRegistry {

    private final Map<String, ReportDefinition> byCode = new LinkedHashMap<>();

    public ReportRegistry(List<ReportDefinition> definitions) {
        for (ReportDefinition d : definitions) {
            ReportDefinition old = byCode.put(d.code(), d);
            if (old != null) {
                throw new IllegalStateException("报表编码重复：" + d.code());
            }
        }
    }

    public ReportDefinition require(String code) {
        ReportDefinition d = byCode.get(code);
        if (d == null) {
            throw new IllegalArgumentException("报表不存在：" + code);
        }
        return d;
    }

    public List<ReportDefinition> all() {
        return List.copyOf(byCode.values());
    }
}
