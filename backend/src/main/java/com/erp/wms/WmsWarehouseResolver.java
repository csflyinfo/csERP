package com.erp.wms;

import com.erp.common.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Map;

/**
 * PDA 作业仓库解析器（PRD-28 卡片9）。
 *
 * <p>WMS 业务表（wms_inbound_task / wms_pick_task / wms_bin_stock ...）按仓库<b>名称</b>隔离，
 * 而 PDA 令牌（{@link CurrentUser#warehouseId()}）携带的是 base_warehouse 主键。
 * 本组件负责在单次请求内把令牌上的 warehouseId 解析为仓库名称，并提供隔离断言：
 * <ul>
 *   <li>{@link #currentWarehouseName()}：PDA 所有查询/写操作的仓库口径，请求内只查一次库；</li>
 *   <li>{@link #assertCurrent(String)}：按任务/波次 ID 加载出的单据，仓库名与登录仓不一致即拒绝，
 *       杜绝 PDA-007「登录 A 仓操作 B 仓单据」。</li>
 * </ul>
 * 仓库被停用或令牌缺 warehouseId 时抛 {@link IllegalArgumentException}（给用户看的中文提示）。
 */
@Component
public class WmsWarehouseResolver {

    private static final String REQUEST_ATTR_WAREHOUSE = "rbac.pdaWarehouse";

    private final JdbcTemplate jdbc;

    public WmsWarehouseResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 当前 PDA 登录选择的仓库主键；令牌缺失时拒绝（正常由端类型拦截器提前挡住）。 */
    public String currentWarehouseId() {
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null || p.warehouseId() == null || p.warehouseId().isBlank()) {
            throw new IllegalArgumentException("登录信息缺少作业仓库，请重新登录选择仓库");
        }
        return p.warehouseId();
    }

    /** 当前登录仓名称（WMS 业务表的 warehouse 列口径），请求内缓存。 */
    public String currentWarehouseName() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cached = attrs.getAttribute(REQUEST_ATTR_WAREHOUSE, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof String s) return s;
        }
        String warehouseId = currentWarehouseId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT warehouse_name FROM base_warehouse WHERE warehouse_id = ? AND status = 'NORMAL'",
                warehouseId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("所选仓库不存在或已停用，请重新登录选择仓库");
        }
        String name = String.valueOf(rows.get(0).get("warehouse_name"));
        if (attrs != null) {
            attrs.setAttribute(REQUEST_ATTR_WAREHOUSE, name, RequestAttributes.SCOPE_REQUEST);
        }
        return name;
    }

    /** 当前请求是否来自 PDA 端令牌（ERP/PC 请求返回 false，服务层据此决定是否强制仓隔离）。 */
    public boolean isPda() {
        CurrentUser.Principal p = CurrentUser.get();
        return p != null && p.isPda();
    }

    /** PDA 请求返回当前登录仓名称；非 PDA（PC/ERP）返回 null（沿用调用方传入的仓库口径）。 */
    public String pdaWarehouseNameOrNull() {
        return isPda() ? currentWarehouseName() : null;
    }

    /** 仅 PDA 请求断言单据仓=登录仓；PC 请求放行（PC 的仓库口径由功能/数据权限另管）。 */
    public void assertIfPda(String warehouseName) {
        if (isPda()) assertCurrent(warehouseName);
    }

    /**
     * 断言单据仓库就是当前登录仓。warehouseName 为空（老数据缺仓）时按总仓兜底口径，
     * 与各服务历史默认值保持一致；显式属于别的仓则拒绝。
     */
    public void assertCurrent(String warehouseName) {
        String current = currentWarehouseName();
        String actual = warehouseName == null || warehouseName.isBlank() ? "总仓" : warehouseName.trim();
        if (!current.equals(actual)) {
            throw new IllegalArgumentException("该单据属于仓库「" + actual + "」，非当前作业仓库「" + current + "」，禁止操作");
        }
    }
}
