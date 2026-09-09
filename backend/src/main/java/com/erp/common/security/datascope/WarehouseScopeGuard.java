package com.erp.common.security.datascope;

import com.erp.common.security.PermissionDeniedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 仓库数据范围守卫（PRD-28 §5.3，卡片7）。
 *
 * <p>列表 SQL 过滤直接用 {@link DataScopeService.ScopeClause#appendTo}；本组件服务于
 * 「写接口入参只带仓库名/单据 ID」的场景（批次锁定、库存单据审核过账等）：
 * 先把业务做下去之前校验目标仓库在当前用户仓库数据范围内，防止越权改动他仓库存。
 *
 * <p>口径与库存列表一致：非超管且未配任何数据范围维度时 fail-closed（仓库维度谓词为 1=0），
 * 即没有仓库范围的账号不能操作任何仓库的库存。超管在 DataScopeService 内短路放行。
 */
@Component
public class WarehouseScopeGuard {

    private final DataScopeService dataScope;

    public WarehouseScopeGuard(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    /**
     * 校验单个仓库在当前用户数据范围内；不在范围抛 {@link PermissionDeniedException}（全局转 403）。
     * 空仓名不拦——必填性由业务校验负责，避免对不存在/空值泄露范围信息也无意义。
     */
    public void assertVisible(JdbcTemplate jdbc, String warehouse) {
        if (warehouse == null || warehouse.isBlank()) return;
        var scope = dataScope.target().warehouse("warehouse_name").build();
        if (scope.isDenyAll()) {
            throw new PermissionDeniedException("无该仓库的操作权限");
        }
        if (scope.predicateSql().isEmpty()) return;
        List<Object> args = new ArrayList<>();
        args.add(warehouse);
        args.addAll(scope.predicateParams());
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM base_warehouse WHERE warehouse_name = ? AND " + scope.predicateSql(),
                Integer.class, args.toArray());
        if (cnt == null || cnt == 0) {
            throw new PermissionDeniedException("无该仓库的操作权限：" + warehouse);
        }
    }

    /**
     * 多仓「任一可见」校验——与列表 {@code warehouseAny(源仓, 目标仓)} 的 OR 口径一致，
     * 用于调拨单等双仓单据的详情查看：源仓、目标仓只要有一个在数据范围内即可见。
     * 全部不在范围才抛 {@link PermissionDeniedException}。写操作（库存实际增减）仍须用
     * {@link #assertVisible} 对作业仓库逐个强校验，不得用本方法放行。
     */
    public void assertAnyVisible(JdbcTemplate jdbc, String... warehouses) {
        List<String> names = new ArrayList<>();
        for (String w : warehouses) {
            if (w != null && !w.isBlank() && !names.contains(w)) names.add(w);
        }
        if (names.isEmpty()) return;
        var scope = dataScope.target().warehouse("warehouse_name").build();
        if (scope.isDenyAll()) {
            throw new PermissionDeniedException("无相关仓库的操作权限");
        }
        if (scope.predicateSql().isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
        List<Object> args = new ArrayList<>(names);
        args.addAll(scope.predicateParams());
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM base_warehouse WHERE warehouse_name IN (" + placeholders + ") AND "
                        + scope.predicateSql(),
                Integer.class, args.toArray());
        if (cnt == null || cnt == 0) {
            throw new PermissionDeniedException("无相关仓库的操作权限");
        }
    }
}
