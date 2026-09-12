package com.erp.report.meta;

import java.util.List;
import java.util.Map;

/**
 * 行级二次填充：明细报表先用轻量键完成分页，再对「当前页/当前导出批」（最多 1000/2000 行）
 * 批量补算派生指标（如采购订单行的已入库量），避免为取一页数据对百万行做全量 JOIN 聚合。
 *
 * <p>实现在查询引擎中、字段脱敏与驼峰转换前执行，入参为 JdbcTemplate 原始行
 * （Spring 的 LinkedCaseInsensitiveMap，键大小写不敏感，可直接 put 大写别名）。
 */
@FunctionalInterface
public interface RowEnricher {

    void enrich(List<Map<String, Object>> rawRows);
}
