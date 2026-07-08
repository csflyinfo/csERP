package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * TMS ERP 端配送监控接口（P2）。
 *
 * 接口：
 *   POST /tms/trip/page              配送行程列表（含在途状态、司机、完成率）
 *   GET  /tms/trip/{id}/detail       行程详情（含调度明细）
 *   GET  /tms/trip/{id}/sign-records 行程签收记录
 *   POST /tms/sign/verify            审核签收（单条）
 *   POST /tms/sign/batch-verify      批量核销
 *   GET  /tms/dispatch/{id}/track    调度单在途轨迹（GPS 点序列）
 *   POST /tms/dispatch/monitor       在途监控列表（调度单 + 实时位置 + 进度）
 */
@RestController
@RequestMapping("/tms")
public class TmsDeliveryController {

    private final JdbcTemplate jdbcTemplate;

    public TmsDeliveryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 配送行程列表（ERP 端监控）。 */
    @PostMapping("/trip/page")
    public ApiResponse<PageResult<Map<String, Object>>> tripPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT t.trip_id, t.trip_no, t.dispatch_id, t.driver_id, t.driver_name, t.vehicle_plate,
                       t.route_line, t.trip_date, t.status, t.total_store, t.delivered_store,
                       t.total_qty, t.delivered_qty, t.collected_amount,
                       t.loading_time, t.depart_time, t.complete_time,
                       d.dispatch_no, d.route_line AS dispatch_route, d.territory, d.store_count
                FROM tms_delivery_trip t
                LEFT JOIN tms_dispatch d ON d.dispatch_id = t.dispatch_id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String status = TmsUtil.str(filters.get("status"));
        if (!status.isEmpty() && !"ALL".equals(status)) { sql.append(" AND t.status = ?"); args.add(status); }
        String driverName = TmsUtil.str(filters.get("driverName"));
        if (!driverName.isEmpty()) { sql.append(" AND t.driver_name LIKE ?"); args.add("%" + driverName + "%"); }
        String routeLine = TmsUtil.str(filters.get("routeLine"));
        if (!routeLine.isEmpty()) { sql.append(" AND t.route_line = ?"); args.add(routeLine); }
        String tripDate = TmsUtil.str(filters.get("tripDate"));
        if (!tripDate.isEmpty()) { sql.append(" AND t.trip_date = ?"); args.add(tripDate); }
        sql.append(" ORDER BY t.trip_date DESC, t.trip_no DESC");

        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("statusText", resolveTripStatus(TmsUtil.str(r.get("status"))));
            int total = TmsUtil.toInt(r.get("totalStore"));
            int delivered = TmsUtil.toInt(r.get("deliveredStore"));
            r.put("progress", total > 0 ? Math.round(delivered * 100.0 / total) : 0);
        }
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 行程详情。 */
    @GetMapping("/trip/{id}/detail")
    public ApiResponse<Map<String, Object>> tripDetail(@PathVariable String id) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM tms_delivery_trip WHERE trip_id=?", id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "行程不存在");
        Map<String, Object> head = TmsUtil.camelize(heads.get(0));
        head.put("statusText", resolveTripStatus(TmsUtil.str(head.get("status"))));

        String dispatchId = TmsUtil.str(head.get("dispatchId"));
        if (!dispatchId.isEmpty()) {
            List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT detail_id, bill_type, source_bill_no, customer_code, customer_name,
                           customer_address, qty, amount, sku_count, seq_no, status, sign_time, sign_user, remark
                    FROM tms_dispatch_detail WHERE dispatch_id=? ORDER BY seq_no
                    """, dispatchId);
            for (Map<String, Object> r : details) {
                r.put("billTypeText", "RETURN".equals(TmsUtil.str(r.get("billType"))) ? "取退" : "发货");
                r.put("statusText", resolveDetailStatus(TmsUtil.str(r.get("status"))));
            }
            head.put("details", details);
        }
        return ApiResponse.ok(head);
    }

    /** 行程签收记录。 */
    @GetMapping("/trip/{id}/sign-records")
    public ApiResponse<List<Map<String, Object>>> signRecords(@PathVariable String id) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT s.sign_id, s.dispatch_id, s.detail_id, s.source_bill_no, s.customer_code, s.customer_name,
                       s.bill_type, s.sign_type, s.signed_qty, s.reject_qty, s.collect_amount, s.pay_method,
                       s.sign_time, s.sign_user, s.customer_signer, s.remark,
                       s.verified, s.verified_at, s.verified_by,
                       (SELECT COUNT(*) FROM tms_sign_photo p WHERE p.sign_id = s.sign_id) AS photo_count
                FROM tms_sign_record s
                WHERE s.trip_id=? OR s.dispatch_id IN (SELECT dispatch_id FROM tms_delivery_trip WHERE trip_id=?)
                ORDER BY s.sign_time DESC
                """, id, id);
        for (Map<String, Object> r : rows) {
            r.put("billTypeText", "RETURN".equals(TmsUtil.str(r.get("billType"))) ? "取退" : "发货");
            r.put("signTypeText", resolveSignType(TmsUtil.str(r.get("signType"))));
        }
        return ApiResponse.ok(rows);
    }

    /** 审核签收（单条）。 */
    @PostMapping("/sign/verify")
    @Transactional
    public ApiResponse<Map<String, Object>> verifySign(@RequestBody Map<String, Object> body) {
        String signId = TmsUtil.str(body.get("signId"));
        if (signId.isEmpty()) return ApiResponse.fail("400", "signId 不能为空");
        String action = TmsUtil.str(body.get("action")); // PASS / REJECT
        String remark = TmsUtil.str(body.get("remark"));
        String verifiedStatus = "PASS".equalsIgnoreCase(action) ? "APPROVED" : "REJECTED";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sign_id, source_bill_no, sign_type, signed_qty FROM tms_sign_record WHERE sign_id=?", signId);
        if (rows.isEmpty()) return ApiResponse.fail("404", "签收记录不存在");

        // 签收核销：使用 verified 字段记录核销状态
        jdbcTemplate.update("""
                UPDATE tms_sign_record SET verified=?, verified_at=CURRENT_TIMESTAMP, verified_by=?
                WHERE sign_id=?
                """, verifiedStatus, TmsUtil.currentUser(), signId);

        TmsUtil.log(jdbcTemplate, "tms.sign", "VERIFY", signId,
                "签收核销：" + action + "，单据：" + TmsUtil.str(rows.get(0).get("source_bill_no")));
        return ApiResponse.ok(Map.of("signId", signId, "action", action, "verified", true));
    }

    /** 批量核销。 */
    @PostMapping("/sign/batch-verify")
    @Transactional
    public ApiResponse<Map<String, Object>> batchVerify(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> signIds = body.get("signIds") == null ? List.of() : (List<String>) body.get("signIds");
        if (signIds.isEmpty()) return ApiResponse.fail("400", "signIds 不能为空");
        String action = TmsUtil.str(body.get("action"));
        if (action.isEmpty()) action = "PASS";
        String verifiedStatus = "PASS".equalsIgnoreCase(action) ? "APPROVED" : "REJECTED";

        int count = 0;
        for (String signId : signIds) {
            jdbcTemplate.update("""
                    UPDATE tms_sign_record SET verified=?, verified_at=CURRENT_TIMESTAMP, verified_by=?
                    WHERE sign_id=?
                    """, verifiedStatus, TmsUtil.currentUser(), signId);
            count++;
        }
        TmsUtil.log(jdbcTemplate, "tms.sign", "BATCH_VERIFY", String.join(",", signIds),
                "批量核销 " + count + " 条签收记录");
        return ApiResponse.ok(Map.of("verified", count, "action", action));
    }

    /** 调度单在途轨迹（GPS 点序列）。 */
    @GetMapping("/dispatch/{id}/track")
    public ApiResponse<List<Map<String, Object>>> dispatchTrack(@PathVariable String id) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT loc_id, longitude, latitude, speed, heading, accuracy, loc_time, report_time
                FROM tms_driver_location
                WHERE dispatch_id=? AND loc_time >= CURRENT_DATE - 1
                ORDER BY loc_time ASC
                """, id);
        return ApiResponse.ok(rows);
    }

    /** 在途监控列表（调度单 + 实时位置 + 进度）。 */
    @PostMapping("/dispatch/monitor")
    public ApiResponse<List<Map<String, Object>>> dispatchMonitor(@RequestBody(required = false) Map<String, Object> body) {
        body = body == null ? Map.of() : body;
        String statusFilter = TmsUtil.str(body.get("status"));
        StringBuilder sql = new StringBuilder("""
                SELECT d.dispatch_id, d.dispatch_no, d.dispatch_date, d.driver_id, d.driver_name,
                       d.vehicle_plate, d.route_line, d.territory, d.status, d.store_count, d.loaded_qty, d.return_qty,
                       d.cur_longitude, d.cur_latitude, d.cur_loc_time, d.cur_speed,
                       d.depart_time, d.complete_time,
                       (SELECT COUNT(*) FROM tms_dispatch_detail det WHERE det.dispatch_id=d.dispatch_id AND det.bill_type='RECEIPT' AND det.status='DELIVERED') AS delivered_count,
                       (SELECT COUNT(*) FROM tms_dispatch_detail det WHERE det.dispatch_id=d.dispatch_id AND det.bill_type='RECEIPT') AS total_receipt
                FROM tms_dispatch d
                WHERE d.status IN ('LOADED','DEPARTED','DELIVERING')
                """);
        List<Object> args = new ArrayList<>();
        if (!statusFilter.isEmpty() && !"ALL".equals(statusFilter)) {
            sql.append(" AND d.status = ?");
            args.add(statusFilter);
        }
        sql.append(" ORDER BY d.depart_time DESC");

        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("statusText", resolveDispatchStatus(TmsUtil.str(r.get("status"))));
            int total = TmsUtil.toInt(r.get("totalReceipt"));
            int delivered = TmsUtil.toInt(r.get("deliveredCount"));
            r.put("progress", total > 0 ? Math.round(delivered * 100.0 / total) : 0);
            r.put("deliveredCount", delivered);
            r.put("online", r.get("curLocTime") != null);
        }
        return ApiResponse.ok(rows);
    }

    // ==================== 状态翻译 ====================

    private String resolveTripStatus(String s) {
        return switch (s) {
            case "PLANNED" -> "待装车";
            case "LOADED" -> "已装车";
            case "DEPARTED" -> "已发车";
            case "DELIVERING" -> "配送中";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            default -> s;
        };
    }

    private String resolveDetailStatus(String s) {
        return switch (s) {
            case "PENDING" -> "待配送";
            case "DELIVERED" -> "已签收";
            case "PARTIAL" -> "部分签收";
            case "REJECTED" -> "已拒收";
            case "RETURNED" -> "已退货";
            default -> s;
        };
    }

    private String resolveSignType(String s) {
        return switch (s) {
            case "NORMAL" -> "正常签收";
            case "PARTIAL" -> "部分签收";
            case "REJECT" -> "全部拒收";
            default -> s;
        };
    }

    private String resolveDispatchStatus(String s) {
        return switch (s) {
            case "DRAFT" -> "草稿";
            case "ASSIGNED" -> "已分配";
            case "LOADED" -> "已装车";
            case "DEPARTED" -> "已发车";
            case "DELIVERING" -> "配送中";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            default -> s;
        };
    }
}
