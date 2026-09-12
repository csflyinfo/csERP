package com.erp.report.performance;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.common.security.datascope.DataScopeService.ScopeClause;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报表18｜库管员绩效报表（期间按 仓库+岗位+人 汇总）。
 *
 * <p>取数统一走 V119 DWD 视图 {@code v_rpt_wms_work}：收货/上架/拣货/复核/补货/盘点/
 * 调整/异常八类作业单在迁移层打平为「人+日+岗位+作业类型」事实流，页面/合计/导出同源，
 * 不直接扫七张业务表、不依赖 wms_performance_daily（该日报只覆盖收货/上架两类，
 * 拣货/复核/补货钩子缺失，见 WmsInternalService.refreshPerformance）。
 *
 * <p>口径要点：
 * <ul>
 *   <li>作业归属日 = 各类任务<b>完成时刻</b>的日期（收货=received_at，仅 status=DONE）；</li>
 *   <li>岗位：收货 RECEIVER / 上架 PUTAWAY / 拣货 PICKER / 复核 CHECKER /
 *       补货、盘点、调整、异常归 KEEPER；一人期内跨岗则按岗拆成多行；</li>
 *   <li>盘盈盘亏金额 = 盘点差异行 × 成本（批次移动平均 → 商品仓均价兜底），
 *       库存调整单按 adjust_qty 带符号同口径计价，权限码 VIEW_STOCK_COST；</li>
 *   <li>作业时长只有拣货（claimed→picked）、上架/补货（started→finished）可算，
 *       作业效率 = 可计量作业总行数 ÷ 作业小时；差错率 = 异常单数 ÷ 作业总行数；</li>
 *   <li>复核表只写 PASS（NG 落 wms_exception），故复核单数即通过单数。</li>
 * </ul>
 * 默认期间=自然月（日结口径）；仓库按数据范围隔离。
 */
@Component
public class WmsKeeperPerfDefinition implements ReportDefinition {

    private static final Set<String> ALLOWED_ROLES =
            Set.of("RECEIVER", "PUTAWAY", "PICKER", "CHECKER", "KEEPER");

    private final DataScopeService dataScope;

    public WmsKeeperPerfDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "wms_keeper_perf"; }
    @Override public String name() { return "库管员绩效报表"; }
    @Override public String viewPerm() { return "report.wms_keeper_perf.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return true; }
    @Override public boolean naturalMonthDefault() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        return List.of(
                ReportColumnDef.dim("warehouse", "仓库"),
                ReportColumnDef.dim("operator", "库管员账号"),
                ReportColumnDef.dim("userName", "库管员"),
                ReportColumnDef.dim("roleName", "岗位"),
                ReportColumnDef.measure("workDays", "作业天数", null),
                ReportColumnDef.measure("receiveOrders", "收货单数", null),
                ReportColumnDef.measure("receiveQty", "收货数量", null),
                ReportColumnDef.measure("receiveLines", "收货行数", null),
                ReportColumnDef.measure("putawayQty", "上架数量", null),
                ReportColumnDef.measure("putawayLines", "上架行数", null),
                ReportColumnDef.measure("pickQty", "拣货数量", null),
                ReportColumnDef.measure("pickLines", "拣货行数", null),
                ReportColumnDef.measure("checkOrders", "复核单数", null),
                ReportColumnDef.measure("replenishCount", "补货次数", null),
                ReportColumnDef.measure("stocktakeOrders", "盘点单数", null),
                ReportColumnDef.measure("gainAmount", "盘盈金额", "VIEW_STOCK_COST"),
                ReportColumnDef.measure("lossAmount", "盘亏金额", "VIEW_STOCK_COST"),
                ReportColumnDef.measure("errorCount", "异常/差错次数", null),
                ReportColumnDef.measure("workMinutes", "作业时长(分钟)", null),
                ReportColumnDef.measure("efficiency", "作业效率(行/小时)", null),
                ReportColumnDef.measure("errorRate", "差错率", null));
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        ScopeClause scope = dataScope.target().warehouse("v.warehouse").build();
        if (scope.isDenyAll()) return plan.denyAll();

        String role = req.text("roleCode");
        if (role != null && !ALLOWED_ROLES.contains(role)) {
            throw new IllegalArgumentException("不支持的岗位编码：" + role);
        }

        String leaf = """
                SELECT v.warehouse AS warehouse,
                       v.operator AS operator,
                       MAX(COALESCE(u.display_name, v.operator)) AS user_name,
                       v.role_code AS role_code,
                       CASE v.role_code
                            WHEN 'RECEIVER' THEN '收货员'
                            WHEN 'PUTAWAY' THEN '上架员'
                            WHEN 'PICKER' THEN '拣货员'
                            WHEN 'CHECKER' THEN '复核员'
                            ELSE '仓管员' END AS role_name,
                       COUNT(DISTINCT v.work_date) AS work_days,
                       SUM(CASE WHEN v.work_type = 'RECEIVE' THEN v.order_count ELSE 0 END) AS receive_orders,
                       SUM(CASE WHEN v.work_type = 'RECEIVE' THEN v.qty ELSE 0 END) AS receive_qty,
                       SUM(CASE WHEN v.work_type = 'RECEIVE' THEN v.line_count ELSE 0 END) AS receive_lines,
                       SUM(CASE WHEN v.work_type = 'PUTAWAY' THEN v.qty ELSE 0 END) AS putaway_qty,
                       SUM(CASE WHEN v.work_type = 'PUTAWAY' THEN v.line_count ELSE 0 END) AS putaway_lines,
                       SUM(CASE WHEN v.work_type = 'PICK' THEN v.qty ELSE 0 END) AS pick_qty,
                       SUM(CASE WHEN v.work_type = 'PICK' THEN v.line_count ELSE 0 END) AS pick_lines,
                       SUM(CASE WHEN v.work_type = 'CHECK' THEN v.order_count ELSE 0 END) AS check_orders,
                       SUM(CASE WHEN v.work_type = 'REPLENISH' THEN v.order_count ELSE 0 END) AS replenish_count,
                       SUM(CASE WHEN v.work_type = 'STOCKTAKE' THEN v.order_count ELSE 0 END) AS stocktake_orders,
                       SUM(CASE WHEN (v.work_type = 'STOCKTAKE' AND v.amount > 0)
                                     OR v.work_type = 'ADJUST_GAIN'
                                THEN v.amount ELSE 0 END) AS gain_amount,
                       SUM(CASE WHEN (v.work_type = 'STOCKTAKE' AND v.amount < 0)
                                     OR v.work_type = 'ADJUST_LOSS'
                                THEN v.amount ELSE 0 END) AS loss_amount,
                       SUM(CASE WHEN v.work_type = 'EXCEPTION' THEN v.order_count ELSE 0 END) AS error_count,
                       SUM(v.work_minutes) AS work_minutes
                  FROM v_rpt_wms_work v
                  LEFT JOIN sys_user_runtime u ON u.username = v.operator
                 WHERE v.work_date BETWEEN ? AND ?
                """;
        List<Object> args = new ArrayList<>();
        args.add(req.range().startDate());
        args.add(req.range().endDate());

        StringBuilder where = new StringBuilder(leaf);
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            where.append(" AND v.warehouse = ? ");
            args.add(warehouse);
        }
        String operator = req.text("operator");
        if (operator != null) {
            where.append(" AND (v.operator LIKE ? OR u.display_name LIKE ?) ");
            args.add("%" + operator + "%");
            args.add("%" + operator + "%");
        }
        if (role != null) {
            where.append(" AND v.role_code = ? ");
            args.add(role);
        }
        scope.appendTo(where, args);
        where.append(" GROUP BY v.warehouse, v.operator, v.role_code ");

        // 外层补算：作业总行数、作业效率（行/小时）、差错率；无作业时长的行效率留空
        plan.detailSelect = """
                SELECT g.warehouse AS warehouse, g.operator AS operator, g.user_name AS user_name,
                       g.role_code AS role_code, g.role_name AS role_name, g.work_days AS work_days,
                       g.receive_orders AS receive_orders, g.receive_qty AS receive_qty,
                       g.receive_lines AS receive_lines, g.putaway_qty AS putaway_qty,
                       g.putaway_lines AS putaway_lines, g.pick_qty AS pick_qty,
                       g.pick_lines AS pick_lines, g.check_orders AS check_orders,
                       g.replenish_count AS replenish_count, g.stocktake_orders AS stocktake_orders,
                       g.gain_amount AS gain_amount, g.loss_amount AS loss_amount,
                       g.error_count AS error_count, g.work_minutes AS work_minutes,
                       CASE WHEN g.work_minutes > 0
                            THEN (g.receive_lines + g.putaway_lines + g.pick_lines
                                  + g.check_orders + g.replenish_count + g.stocktake_orders)
                                 * 60.0 / g.work_minutes END AS efficiency,
                       CASE WHEN (g.receive_lines + g.putaway_lines + g.pick_lines
                                  + g.check_orders + g.replenish_count + g.stocktake_orders) > 0
                            THEN g.error_count * 1.0
                                 / (g.receive_lines + g.putaway_lines + g.pick_lines
                                    + g.check_orders + g.replenish_count + g.stocktake_orders)
                            END AS error_rate
                  FROM (""";
        plan.fromWhere.append(where).append(") g");
        plan.args.addAll(args);
        plan.defaultOrder = "ORDER BY g.work_days DESC, g.receive_qty DESC, g.operator ASC";
        plan.sortWhitelist.putAll(Map.of(
                "workDays", "work_days",
                "receiveQty", "receive_qty",
                "putawayQty", "putaway_qty",
                "pickQty", "pick_qty",
                "errorCount", "error_count",
                "errorRate", "error_rate",
                "efficiency", "efficiency"));
        plan.maskOverrides.put("gainAmount", "VIEW_STOCK_COST");
        plan.maskOverrides.put("lossAmount", "VIEW_STOCK_COST");

        // 合计：总人天/各作业量；效率与差错率按总量加权（不能对行比率平均）
        plan.grandSql = """
                SELECT COALESCE(SUM(t.work_days),0) AS work_days,
                       COALESCE(SUM(t.receive_orders),0) AS receive_orders,
                       COALESCE(SUM(t.receive_qty),0) AS receive_qty,
                       COALESCE(SUM(t.receive_lines),0) AS receive_lines,
                       COALESCE(SUM(t.putaway_qty),0) AS putaway_qty,
                       COALESCE(SUM(t.putaway_lines),0) AS putaway_lines,
                       COALESCE(SUM(t.pick_qty),0) AS pick_qty,
                       COALESCE(SUM(t.pick_lines),0) AS pick_lines,
                       COALESCE(SUM(t.check_orders),0) AS check_orders,
                       COALESCE(SUM(t.replenish_count),0) AS replenish_count,
                       COALESCE(SUM(t.stocktake_orders),0) AS stocktake_orders,
                       COALESCE(SUM(t.gain_amount),0) AS gain_amount,
                       COALESCE(SUM(t.loss_amount),0) AS loss_amount,
                       COALESCE(SUM(t.error_count),0) AS error_count,
                       COALESCE(SUM(t.work_minutes),0) AS work_minutes,
                       CASE WHEN SUM(t.work_minutes) > 0
                            THEN SUM(t.receive_lines + t.putaway_lines + t.pick_lines
                                     + t.check_orders + t.replenish_count + t.stocktake_orders)
                                 * 60.0 / SUM(t.work_minutes) END AS efficiency,
                       CASE WHEN SUM(t.receive_lines + t.putaway_lines + t.pick_lines
                                    + t.check_orders + t.replenish_count + t.stocktake_orders) > 0
                            THEN SUM(t.error_count) * 1.0
                                 / SUM(t.receive_lines + t.putaway_lines + t.pick_lines
                                       + t.check_orders + t.replenish_count + t.stocktake_orders)
                            END AS error_rate
                FROM (%s) t
                """.formatted(plan.detailSelect + " " + plan.fromWhere);
        // grandSql 内嵌整段叶子查询（detailSelect 无占位符），参数与分页查询同一套
        plan.grandArgs.addAll(args);
        return plan;
    }
}
