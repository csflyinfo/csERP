package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.util.BillNoGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售退货单调度（V1.2 退货调度闭环）。
 *
 * 物流状态机：未安排 ──[安排调度]──> 已安排调度 ──[指派司机]──> 已调度 ──[APP退货签收]──> 司机已回收
 *
 * 接口：
 *   POST /tms/return-dispatch/page           已安排调度退货单列表（调度池取货任务）
 *   POST /tms/return-dispatch/arrange        退货单安排调度（→已安排调度，进调度池）
 *   POST /tms/return-dispatch/cancel-arrange 取消安排调度（→未安排）
 *   POST /tms/return-dispatch/assign         退货单指派司机（→已调度，回写 driver/dispatch/trip）
 *   POST /tms/return-dispatch/auto-match     指派发货单时按客户自动匹配同客户已安排调度退货单
 */
@RestController
@RequestMapping("/tms/return-dispatch")
public class TmsReturnDispatchController {

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;

    public TmsReturnDispatchController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
    }

    /** 司机回收型退货单列表（调度管理页）。支持按物流状态/客户/线路筛选，无状态过滤时返回全部。 */
    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT a.apply_id, a.apply_no, a.customer_code, a.customer_name, a.warehouse, a.bill_date,
                       a.qty AS return_qty, a.signed_qty, a.return_reason, a.status AS bill_status,
                       a.return_type, a.logistics_status, a.driver_id, a.driver_name,
                       a.dispatch_id, a.trip_id, a.arrange_time, a.arrange_remark,
                       a.create_time, a.remark,
                       c.route_line, c.territory, c.address_detail, c.longitude, c.latitude
                FROM sales_return_apply a
                LEFT JOIN base_customer c ON c.customer_code = a.customer_code
                WHERE a.return_type = 'DRIVER'
                """);
        List<Object> args = new ArrayList<>();
        String logisticsStatus = TmsUtil.str(filters.get("logisticsStatus"));
        if (!logisticsStatus.isEmpty() && !"ALL".equals(logisticsStatus)) { sql.append(" AND a.logistics_status = ?"); args.add(logisticsStatus); }
        String customer = TmsUtil.str(filters.get("customer"));
        if (!customer.isEmpty()) { sql.append(" AND (a.customer_code LIKE ? OR a.customer_name LIKE ?)"); args.add("%" + customer + "%"); args.add("%" + customer + "%"); }
        String applyNo = TmsUtil.str(filters.get("applyNo"));
        if (!applyNo.isEmpty()) { sql.append(" AND a.apply_no LIKE ?"); args.add("%" + applyNo + "%"); }
        String routeLine = TmsUtil.str(filters.get("routeLine"));
        if (!routeLine.isEmpty()) { sql.append(" AND c.route_line = ?"); args.add(routeLine); }
        sql.append(" ORDER BY a.arrange_time DESC, a.apply_no DESC");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("logisticsStatusText", TmsUtil.str(r.get("logisticsStatus")));
            r.put("billStatusText", resolveBillStatus(TmsUtil.str(r.get("billStatus"))));
        }
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /**
     * 安排调度：logistics_status 未安排 → 已安排调度。前提：return_type=DRIVER 且单据已确认。
     * <p>
     * V60 起前提是「已确认」而不是「已审核」：审核已退化为货回库后的纯财务动作，
     * 排在司机回收之后，要求先审核会让司机回收链路永远排不上调度。
     */
    @PostMapping("/arrange")
    @Transactional
    public ApiResponse<Map<String, Object>> arrange(@RequestBody Map<String, Object> body) {
        String applyNo = TmsUtil.str(body.get("applyNo"));
        if (applyNo.isEmpty()) return ApiResponse.fail("400", "退货单号不能为空");
        String remark = TmsUtil.str(body.get("remark"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT apply_id, apply_no, return_type, status, logistics_status FROM sales_return_apply WHERE apply_no = ?", applyNo);
        if (rows.isEmpty()) return ApiResponse.fail("404", "退货单不存在：" + applyNo);
        Map<String, Object> r = rows.get(0);
        String returnType = TmsUtil.str(r.get("return_type"));
        String status = TmsUtil.str(r.get("status"));
        String logisticsStatus = TmsUtil.str(r.get("logistics_status"));
        if (!"DRIVER".equals(returnType)) return ApiResponse.fail("400", "非司机回收型退货单无需安排调度");
        if (!"CONFIRMED".equals(status)) return ApiResponse.fail("400", "退货单尚未确认，无法安排调度");
        if (!"未安排".equals(logisticsStatus)) return ApiResponse.fail("400", "当前物流状态为「" + logisticsStatus + "」，不可重复安排调度");

        jdbcTemplate.update("""
                UPDATE sales_return_apply
                SET logistics_status = '已安排调度', arrange_time = ?, arrange_remark = ?
                WHERE apply_no = ?
                """, Timestamp.valueOf(TmsUtil.now()), remark, applyNo);
        TmsUtil.log(jdbcTemplate, "tms.return-dispatch", "ARRANGE", applyNo,
                "退货单安排调度：物流状态 未安排 → 已安排调度，进入调度池");
        return ApiResponse.ok(Map.of("applyNo", applyNo, "logisticsStatus", "已安排调度"));
    }

    /** 取消安排调度：已安排调度（未指派司机） → 未安排。已调度/已回收不可取消。 */
    @PostMapping("/cancel-arrange")
    @Transactional
    public ApiResponse<Map<String, Object>> cancelArrange(@RequestBody Map<String, Object> body) {
        String applyNo = TmsUtil.str(body.get("applyNo"));
        if (applyNo.isEmpty()) return ApiResponse.fail("400", "退货单号不能为空");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT apply_no, logistics_status, dispatch_id FROM sales_return_apply WHERE apply_no = ?", applyNo);
        if (rows.isEmpty()) return ApiResponse.fail("404", "退货单不存在：" + applyNo);
        Map<String, Object> r = rows.get(0);
        String logisticsStatus = TmsUtil.str(r.get("logistics_status"));
        if (!"已安排调度".equals(logisticsStatus)) {
            return ApiResponse.fail("400", "当前物流状态为「" + logisticsStatus + "」，仅「已安排调度」可取消");
        }
        jdbcTemplate.update("""
                UPDATE sales_return_apply
                SET logistics_status = '未安排', arrange_time = NULL, arrange_remark = NULL
                WHERE apply_no = ?
                """, applyNo);
        TmsUtil.log(jdbcTemplate, "tms.return-dispatch", "CANCEL_ARRANGE", applyNo,
                "取消退货单安排调度：物流状态 已安排调度 → 未安排");
        return ApiResponse.ok(Map.of("applyNo", applyNo, "logisticsStatus", "未安排"));
    }

    /**
     * 退货单指派司机：已安排调度 → 已调度。
     * 回写 driver_id/driver_name/dispatch_id/trip_id，并向 tms_dispatch_detail 写入 bill_type='RETURN' 取货任务。
     * 入参：applyNo, driverId, driverName, dispatchId(可空), tripId(可空), seqNo(可空)
     */
    @PostMapping("/assign")
    @Transactional
    public ApiResponse<Map<String, Object>> assign(@RequestBody Map<String, Object> body) {
        String applyNo = TmsUtil.str(body.get("applyNo"));
        String driverId = TmsUtil.str(body.get("driverId"));
        String driverName = TmsUtil.str(body.get("driverName"));
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String tripId = TmsUtil.str(body.get("tripId"));
        if (applyNo.isEmpty() || driverId.isEmpty()) return ApiResponse.fail("400", "退货单号、司机不能为空");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT apply_id, apply_no, customer_code, customer_name, qty, logistics_status, dispatch_id
                FROM sales_return_apply WHERE apply_no = ?
                """, applyNo);
        if (rows.isEmpty()) return ApiResponse.fail("404", "退货单不存在：" + applyNo);
        Map<String, Object> r = TmsUtil.camelize(rows.get(0));
        String logisticsStatus = TmsUtil.str(r.get("logisticsStatus"));
        if (!"已安排调度".equals(logisticsStatus)) {
            return ApiResponse.fail("400", "当前物流状态为「" + logisticsStatus + "」，仅「已安排调度」可指派司机");
        }
        String applyId = TmsUtil.str(r.get("applyId"));
        String customerCode = TmsUtil.str(r.get("customerCode"));
        String customerName = TmsUtil.str(r.get("customerName"));
        BigDecimal qty = TmsUtil.toBd(r.get("qty"));

        jdbcTemplate.update("""
                UPDATE sales_return_apply
                SET logistics_status = '已调度', driver_id = ?, driver_name = ?,
                    dispatch_id = ?, trip_id = ?
                WHERE apply_no = ?
                """, driverId.isEmpty() ? null : driverId, driverName,
                dispatchId.isEmpty() ? null : dispatchId, tripId.isEmpty() ? null : tripId, applyNo);

        // 若关联了调度单，向 tms_dispatch_detail 写入取货任务行（幂等：同一 source_bill_no 同一 dispatch_id 不重复插）
        if (!dispatchId.isEmpty()) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tms_dispatch_detail WHERE dispatch_id = ? AND bill_type = 'RETURN' AND source_bill_no = ?",
                    Integer.class, dispatchId, applyNo);
            if (exists == null || exists == 0) {
                String detailId = TmsUtil.uuid("TDD");
                int seqNo = TmsUtil.toInt(body.get("seqNo"));
                jdbcTemplate.update("""
                        INSERT INTO tms_dispatch_detail(detail_id, dispatch_id, bill_type, source_bill_no, source_bill_id,
                            customer_code, customer_name, qty, sku_count, seq_no, status, remark)
                        VALUES (?, ?, 'RETURN', ?, ?, ?, ?, ?, 0, ?, 'PENDING', '退货单取货任务')
                        """, detailId, dispatchId, applyNo, applyId, customerCode, customerName, qty, seqNo);
            }
        }

        TmsUtil.log(jdbcTemplate, "tms.return-dispatch", "ASSIGN", applyNo,
                "退货单指派司机：" + driverName + "，物流状态 已安排调度 → 已调度");
        return ApiResponse.ok(Map.of(
                "applyNo", applyNo,
                "logisticsStatus", "已调度",
                "driverId", driverId,
                "driverName", driverName,
                "dispatchId", dispatchId,
                "tripId", tripId
        ));
    }

    /**
     * 自动匹配：指派发货单时，按客户查找已安排调度（未指派）的退货单，提示一并指派。
     * 入参：customerCode 或 receiptNo（发货单号，回查客户）
     * 返回：匹配到的退货单列表
     */
    @PostMapping("/auto-match")
    public ApiResponse<List<Map<String, Object>>> autoMatch(@RequestBody Map<String, Object> body) {
        String customerCode = TmsUtil.str(body.get("customerCode"));
        String receiptNo = TmsUtil.str(body.get("receiptNo"));
        if (customerCode.isEmpty() && !receiptNo.isEmpty()) {
            List<Map<String, Object>> r = jdbcTemplate.queryForList(
                    "SELECT customer_code FROM sales_receipt WHERE receipt_no = ?", receiptNo);
            if (!r.isEmpty()) customerCode = TmsUtil.str(r.get(0).get("customer_code"));
        }
        if (customerCode.isEmpty()) return ApiResponse.ok(List.of());
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT apply_id, apply_no, customer_code, customer_name, qty AS return_qty,
                       return_reason, logistics_status, arrange_time
                FROM sales_return_apply
                WHERE return_type = 'DRIVER' AND logistics_status = '已安排调度' AND customer_code = ?
                ORDER BY arrange_time DESC
                """, customerCode);
        return ApiResponse.ok(rows);
    }

    private String resolveBillStatus(String status) {
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "PENDING" -> "待确认";
            case "CONFIRMED" -> "已确认";
            case "APPROVED" -> "已审核";
            case "REJECTED" -> "已驳回";
            default -> status;
        };
    }
}
