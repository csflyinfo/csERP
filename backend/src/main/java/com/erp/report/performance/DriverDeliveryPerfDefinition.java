package com.erp.report.performance;

import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报表19｜司机配送绩效报表（期间按司机汇总）。
 *
 * <p>司机归因一律走「调度单司机」：{@code v_rpt_driver_bill}（V119）以 tms_dispatch_detail
 * 为骨架、经 tms_dispatch.driver_id 归因，回填发货单签收数量/金额——不使用
 * v_rpt_sales_detail 的 sign_user COALESCE（未挂调度时会错归到系统管理员），
 * 也不使用 tms_sign_record.sign_user（新旧链路写入语义不一致）。
 *
 * <p>口径：
 * <ul>
 *   <li>计划单数 = 调度日行（bill_type=RECEIPT）按<b>调度日</b>计数；</li>
 *   <li>签收单数/拒收单数/签收数量/货值/客户数 = 同行按<b>签收日</b>统计
 *       （DELIVERED/PARTIAL/RETURNED 计签收，REJECTED 计拒收，RESCHEDULED 不计）；
 *       签收率 = 签收单数 ÷ 计划单数（跨期单据分子分母可能错期，属正常时滞）；</li>
 *   <li>趟次数 = tms_delivery_trip 非取消行程；随车退货 = tms_driver_return 已回库；</li>
 *   <li>代收/缴款 = 已审核交账单 tms_settlement：代收=现金+线上、实缴=actual_submit、
 *       差异=diff_amount（未交账部分不预估计）；</li>
 *   <li>行驶时长 = 调度单 depart_time→complete_time；里程系统只记录发车读数，
 *       无回车里程，本期不产出里程列；准时率按 Q7 不做。</li>
 * </ul>
 * 默认期间=自然月；货值 VIEW_SALE_AMOUNT、代收/缴款 VIEW_AR_BALANCE 脱敏（可对司机屏蔽）。
 * 配送为跨全量数据的运营聚合屏，无司机数据范围维度，不挂行级 DataScope（同工作台口径）。
 */
@Component
public class DriverDeliveryPerfDefinition implements ReportDefinition {

    @Override public String code() { return "driver_delivery_perf"; }
    @Override public String name() { return "司机配送绩效报表"; }
    @Override public String viewPerm() { return "report.driver_delivery_perf.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return true; }
    @Override public boolean naturalMonthDefault() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        return List.of(
                ReportColumnDef.dim("driverId", "司机工号"),
                ReportColumnDef.dim("driverName", "司机"),
                ReportColumnDef.dim("mobile", "手机号"),
                ReportColumnDef.measure("tripCount", "趟次数", null),
                ReportColumnDef.measure("plannedCount", "计划单数", null),
                ReportColumnDef.measure("signedCount", "签收完成单数", null),
                ReportColumnDef.measure("signRate", "签收率", null),
                ReportColumnDef.measure("rejectCount", "拒收单数", null),
                ReportColumnDef.measure("rejectRate", "拒收率", null),
                ReportColumnDef.measure("returnCount", "随车退货单数", null),
                ReportColumnDef.measure("customerCount", "配送客户数", null),
                ReportColumnDef.measure("signedQty", "签收数量", null),
                ReportColumnDef.measure("rejectQty", "拒收数量", null),
                ReportColumnDef.measure("signAmount", "配送货值(签收含税)", "VIEW_SALE_AMOUNT"),
                ReportColumnDef.measure("codCollected", "代收货款金额", "VIEW_AR_BALANCE"),
                ReportColumnDef.measure("codSubmit", "实际缴款金额", "VIEW_AR_BALANCE"),
                ReportColumnDef.measure("codDiff", "缴款差异", "VIEW_AR_BALANCE"),
                ReportColumnDef.measure("driveMinutes", "行驶时长(分钟)", null),
                ReportColumnDef.measure("avgMinutesPerOrder", "平均每单时长(分钟)", null),
                ReportColumnDef.measure("exceptionCount", "异常次数", null));
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        var start = req.range().startDate();
        var end = req.range().endDate();

        // 司机全集：档案司机 ∪ 实际执行过调度的司机（防档案漏建）
        plan.detailSelect = """
                SELECT drv.driver_id AS driver_id,
                       drv.driver_name AS driver_name,
                       drv.mobile AS mobile,
                       COALESCE(tr.trip_count, 0) AS trip_count,
                       COALESCE(p.planned_count, 0) AS planned_count,
                       COALESCE(s.signed_count, 0) AS signed_count,
                       CASE WHEN COALESCE(p.planned_count, 0) > 0
                            THEN COALESCE(s.signed_count, 0) * 1.0 / p.planned_count END AS sign_rate,
                       COALESCE(s.reject_count, 0) AS reject_count,
                       CASE WHEN COALESCE(p.planned_count, 0) > 0
                            THEN COALESCE(s.reject_count, 0) * 1.0 / p.planned_count END AS reject_rate,
                       COALESCE(r.return_count, 0) AS return_count,
                       COALESCE(s.customer_count, 0) AS customer_count,
                       COALESCE(s.signed_qty, 0) AS signed_qty,
                       COALESCE(s.reject_qty, 0) AS reject_qty,
                       COALESCE(s.sign_amount, 0) AS sign_amount,
                       COALESCE(c.cod_collected, 0) AS cod_collected,
                       COALESCE(c.cod_submit, 0) AS cod_submit,
                       COALESCE(c.cod_diff, 0) AS cod_diff,
                       COALESCE(m.drive_minutes, 0) AS drive_minutes,
                       CASE WHEN COALESCE(s.signed_count, 0) > 0
                            THEN COALESCE(m.drive_minutes, 0) * 1.0 / s.signed_count
                            END AS avg_minutes_per_order,
                       COALESCE(x.exception_count, 0) AS exception_count
                  FROM (""";

        StringBuilder from = new StringBuilder("""
                         SELECT employee_id AS driver_id, employee_name AS driver_name, mobile AS mobile
                           FROM base_employee WHERE is_deliveryman = TRUE
                          UNION
                         SELECT driver_id, MAX(driver_name), MAX(driver_mobile)
                           FROM tms_dispatch WHERE driver_id IS NOT NULL GROUP BY driver_id
                     ) drv
                     LEFT JOIN (
                         SELECT driver_id, COUNT(*) AS planned_count
                           FROM v_rpt_driver_bill
                          WHERE bill_type = 'RECEIPT' AND plan_date BETWEEN ? AND ?
                          GROUP BY driver_id
                     ) p ON p.driver_id = drv.driver_id
                     LEFT JOIN (
                         SELECT driver_id,
                                SUM(CASE WHEN detail_status IN ('DELIVERED','PARTIAL','RETURNED') THEN 1 ELSE 0 END) AS signed_count,
                                SUM(CASE WHEN detail_status = 'REJECTED' THEN 1 ELSE 0 END) AS reject_count,
                                COUNT(DISTINCT CASE WHEN detail_status IN ('DELIVERED','PARTIAL','RETURNED')
                                                    THEN customer_code END) AS customer_count,
                                SUM(CASE WHEN detail_status IN ('DELIVERED','PARTIAL','RETURNED')
                                         THEN signed_qty ELSE 0 END) AS signed_qty,
                                // 拒收量在 REJECTED（全拒）与 PARTIAL/DELIVERED（部分拒收）单上都可能记录，全量汇总
                                SUM(COALESCE(reject_qty, 0)) AS reject_qty,
                                SUM(CASE WHEN detail_status IN ('DELIVERED','PARTIAL','RETURNED')
                                         THEN sign_amount ELSE 0 END) AS sign_amount
                           FROM v_rpt_driver_bill
                          WHERE bill_type = 'RECEIPT' AND sign_date BETWEEN ? AND ?
                          GROUP BY driver_id
                     ) s ON s.driver_id = drv.driver_id
                     LEFT JOIN (
                         SELECT driver_id, COUNT(*) AS trip_count
                           FROM tms_delivery_trip
                          WHERE COALESCE(status, '') <> 'CANCELLED'
                            AND trip_date BETWEEN ? AND ?
                          GROUP BY driver_id
                     ) tr ON tr.driver_id = drv.driver_id
                     LEFT JOIN (
                         SELECT driver_id, COUNT(*) AS return_count
                           FROM tms_driver_return
                          WHERE status = 'WAREHOUSED'
                            AND CAST(return_date AS DATE) BETWEEN ? AND ?
                          GROUP BY driver_id
                     ) r ON r.driver_id = drv.driver_id
                     LEFT JOIN (
                         SELECT driver_id,
                                SUM(COALESCE(cash_amount, 0) + COALESCE(online_amount, 0)) AS cod_collected,
                                SUM(COALESCE(actual_submit, 0)) AS cod_submit,
                                SUM(COALESCE(diff_amount, 0)) AS cod_diff
                           FROM tms_settlement
                          WHERE status = 'APPROVED'
                            AND settle_date BETWEEN ? AND ?
                          GROUP BY driver_id
                     ) c ON c.driver_id = drv.driver_id
                     LEFT JOIN (
                         SELECT driver_id,
                                SUM(DATEDIFF('MINUTE', depart_time, complete_time)) AS drive_minutes
                           FROM tms_dispatch
                          WHERE depart_time IS NOT NULL AND complete_time IS NOT NULL
                            AND complete_time >= depart_time
                            AND CAST(depart_time AS DATE) BETWEEN ? AND ?
                          GROUP BY driver_id
                     ) m ON m.driver_id = drv.driver_id
                     LEFT JOIN (
                         SELECT driver_id, COUNT(*) AS exception_count
                           FROM tms_exception_report
                          WHERE CAST(COALESCE(reported_at, create_time) AS DATE) BETWEEN ? AND ?
                          GROUP BY driver_id
                     ) x ON x.driver_id = drv.driver_id
                """);
        // 绑定顺序按 SQL 文本：FROM 中 7 个聚合子查询（计划/签收/趟次/退货/交账/时长/异常）
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            args.add(start);
            args.add(end);
        }

        // 外层筛选（占位符文本上在 FROM 子查询之后）
        from.append(" WHERE 1=1 ");
        String driver = req.text("driver");
        if (driver != null) {
            from.append(" AND (drv.driver_name LIKE ? OR drv.driver_id LIKE ? OR drv.mobile LIKE ?) ");
            args.add("%" + driver + "%");
            args.add("%" + driver + "%");
            args.add("%" + driver + "%");
        }
        String route = req.text("routeLine");
        if (route != null) {
            from.append("""
                     AND EXISTS (
                         SELECT 1 FROM v_rpt_driver_bill b
                          WHERE b.driver_id = drv.driver_id AND b.bill_type = 'RECEIPT'
                            AND b.route_line = ?
                            AND ((b.plan_date BETWEEN ? AND ?) OR (b.sign_date BETWEEN ? AND ?))
                     )
                    """);
            args.add(route);
            args.add(start); args.add(end);
            args.add(start); args.add(end);
        }
        if (!"1".equals(req.text("includeInactive"))) {
            // 默认只列期间有作业的司机（计划/签收/趟次/退货/交账/异常任一）
            from.append("""
                     AND (COALESCE(p.planned_count,0) + COALESCE(s.signed_count,0)
                          + COALESCE(tr.trip_count,0) + COALESCE(r.return_count,0)
                          + COALESCE(c.cod_collected,0) + COALESCE(x.exception_count,0) > 0)
                    """);
        }
        plan.fromWhere.append(from);
        plan.args.addAll(args);
        plan.defaultOrder = "ORDER BY signed_count DESC, trip_count DESC, driver_name ASC";
        plan.sortWhitelist.putAll(Map.of(
                "tripCount", "trip_count",
                "plannedCount", "planned_count",
                "signedCount", "signed_count",
                "signRate", "sign_rate",
                "rejectCount", "reject_count",
                "signedQty", "signed_qty",
                "signAmount", "sign_amount",
                "codCollected", "cod_collected",
                "exceptionCount", "exception_count"));
        plan.maskOverrides.put("signAmount", "VIEW_SALE_AMOUNT");
        plan.maskOverrides.put("codCollected", "VIEW_AR_BALANCE");
        plan.maskOverrides.put("codSubmit", "VIEW_AR_BALANCE");
        plan.maskOverrides.put("codDiff", "VIEW_AR_BALANCE");

        // 合计：比率按总量加权，不平均行比率
        plan.grandSql = """
                SELECT COALESCE(SUM(t.trip_count),0) AS trip_count,
                       COALESCE(SUM(t.planned_count),0) AS planned_count,
                       COALESCE(SUM(t.signed_count),0) AS signed_count,
                       CASE WHEN SUM(t.planned_count) > 0
                            THEN SUM(t.signed_count) * 1.0 / SUM(t.planned_count) END AS sign_rate,
                       COALESCE(SUM(t.reject_count),0) AS reject_count,
                       CASE WHEN SUM(t.planned_count) > 0
                            THEN SUM(t.reject_count) * 1.0 / SUM(t.planned_count) END AS reject_rate,
                       COALESCE(SUM(t.return_count),0) AS return_count,
                       COALESCE(SUM(t.customer_count),0) AS customer_count,
                       COALESCE(SUM(t.signed_qty),0) AS signed_qty,
                       COALESCE(SUM(t.reject_qty),0) AS reject_qty,
                       COALESCE(SUM(t.sign_amount),0) AS sign_amount,
                       COALESCE(SUM(t.cod_collected),0) AS cod_collected,
                       COALESCE(SUM(t.cod_submit),0) AS cod_submit,
                       COALESCE(SUM(t.cod_diff),0) AS cod_diff,
                       COALESCE(SUM(t.drive_minutes),0) AS drive_minutes,
                       CASE WHEN SUM(t.signed_count) > 0
                            THEN SUM(t.drive_minutes) * 1.0 / SUM(t.signed_count)
                            END AS avg_minutes_per_order,
                       COALESCE(SUM(t.exception_count),0) AS exception_count
                FROM (%s %s) t
                """.formatted(plan.detailSelect, plan.fromWhere);
        plan.grandArgs.addAll(args);
        return plan;
    }
}
