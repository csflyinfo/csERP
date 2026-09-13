package com.erp.report.common;

import java.util.Collections;
import java.util.List;

/**
 * 报表筛选 SQL 片段共享助手：字典类多值筛选（商品分类等）统一走 IN 列表。
 * 入参兼容单值与数组（{@link ReportQueryRequest#texts} 已归一），
 * 前端下拉树勾选上级自动带入全部下级名称，后端按名称集合匹配。
 */
public final class ReportFilters {

    private ReportFilters() {}

    /** 多值 IN 筛选：filters[key] 为空/空列表时不追加任何条件。 */
    public static void inList(ReportQueryRequest req, StringBuilder sql, List<Object> args,
                              String key, String column) {
        List<String> vals = req.texts(key);
        if (vals.isEmpty()) return;
        String marks = String.join(",", Collections.nCopies(vals.size(), "?"));
        sql.append(" AND ").append(column).append(" IN (").append(marks).append(") ");
        args.addAll(vals);
    }
}
