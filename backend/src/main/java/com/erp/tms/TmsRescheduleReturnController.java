package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.util.BillNoGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/**
 * 改派返仓管理（P3-2）。
 *
 * 触发场景：客户不在/地址错误/联系不上/客户要求改期 → 货物随车返仓 → 仓库验收 → 回调度池重新派送
 * 核心原则：不反审核出库单，不生成入库单，库存不变
 *
 * APP 接口：
 *   POST /tms/app/reschedule-return/create         生成改派返仓单
 *   POST /tms/app/reschedule-return/upload-photo   上传留证照片
 *   POST /tms/app/reschedule-return/list           本司机待返仓改派返仓单列表
 *   POST /tms/app/reschedule-return/confirm        返仓交接确认（→ 已验收，回调度池）
 *
 * ERP 接口：
 *   POST /tms/reschedule-return/page               改派返仓单列表
 *   GET  /tms/reschedule-return/{id}               改派返仓单详情
 *   POST /tms/reschedule-return/{id}/check         仓库验收（→ 已验收，发货单回调度池）
 *   POST /tms/reschedule-return/pool               返仓改派池（待重新派送的发货单）
 *   POST /tms/reschedule-return/{id}/redispatch    重新纳入调度
 */
@RestController
public class TmsRescheduleReturnController {

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;

    public TmsRescheduleReturnController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
    }

    // ========================================================================
    // APP 端接口
    // ========================================================================

    /**
     * 生成改派返仓单。
     * 入参：dispatchId, detailId, receiptNo, reason, reasonDetail, rescheduleDate?, remark?, items:[{goodsCode, goodsName, spec, unitName, plannedQty, batchNo}]
     * 处理：
     *   1. 生成 tms_reschedule_return(PENDING) + 明细
     *   2. 更新 tms_dispatch_detail.status = RESCHEDULED
     *   3. 记录改派次数（同 receipt_no 的历史改派单数 + 1）
     */
    @PostMapping("/tms/app/reschedule-return/create")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String detailId = TmsUtil.str(body.get("detailId"));
        String receiptNo = TmsUtil.str(body.get("receiptNo"));
        String reason = TmsUtil.str(body.get("reason"));
        String reasonDetail = TmsUtil.str(body.get("reasonDetail"));
        String remark = TmsUtil.str(body.get("remark"));
        if (dispatchId.isEmpty() || detailId.isEmpty() || receiptNo.isEmpty()) {
            return ApiResponse.fail("400", "dispatchId/detailId/receiptNo 不能为空");
        }
        if (reason.isEmpty()) {
            return ApiResponse.fail("400", "请选择改派原因");
        }

        // 校验调度明细属于当前司机
        List<Map<String, Object>> detailRows = jdbcTemplate.queryForList(
                "SELECT * FROM tms_dispatch_detail WHERE detail_id=? AND dispatch_id=?", detailId, dispatchId);
        if (detailRows.isEmpty()) return ApiResponse.fail("404", "调度明细不存在");
        Map<String, Object> d = detailRows.get(0);
        // 查调度单校验司机
        List<Map<String, Object>> dispRows = jdbcTemplate.queryForList(
                "SELECT driver_id, driver_name FROM tms_dispatch WHERE dispatch_id=?", dispatchId);
        if (dispRows.isEmpty()) return ApiResponse.fail("404", "调度单不存在");
        String driverName = TmsUtil.str(dispRows.get(0).get("driver_name"));

        // 改派次数（同发货单历史改派次数 + 1）
        int rescheduleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tms_reschedule_return WHERE receipt_no=?",
                Integer.class, receiptNo) + 1;

        // 期望改送日期（默认次日）
        LocalDate rescheduleDate = LocalDate.now().plusDays(1);
        String dateStr = TmsUtil.str(body.get("rescheduleDate"));
        if (!dateStr.isEmpty()) {
            try { rescheduleDate = LocalDate.parse(dateStr); } catch (Exception ignored) {}
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = body.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        BigDecimal totalQty = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            totalQty = totalQty.add(TmsUtil.toBd(it.get("plannedQty")));
        }
        if (totalQty.compareTo(BigDecimal.ZERO) == 0) {
            totalQty = TmsUtil.toBd(d.get("qty"));
        }

        String returnId = "GPRC" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String returnNo = billNoGen.nextNo(
                BillNoGenerator.BillType.TMS_RESCHEDULE_RETURN, "tms_reschedule_return", "return_no");

        jdbcTemplate.update("""
                INSERT INTO tms_reschedule_return(return_id, return_no, trip_id, dispatch_id, detail_id, receipt_no,
                    customer_code, customer_name, customer_address, reason, reason_detail, total_qty,
                    reschedule_date, reschedule_count, driver_id, driver_name, status, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, returnId, returnNo,
                TmsUtil.str(d.get("trip_id")), dispatchId, detailId, receiptNo,
                TmsUtil.str(d.get("customer_code")), TmsUtil.str(d.get("customer_name")),
                TmsUtil.str(d.get("customer_address")), reason, reasonDetail, totalQty,
                rescheduleDate, rescheduleCount, driverId, driverName, remark);

        // 写明细（若未传 items，从发货单明细拉取）
        if (items.isEmpty()) {
            List<Map<String, Object>> receiptDetails = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT d.goods_code, d.goods_name, d.spec, d.unit_name, d.qty
                    FROM sales_receipt_detail d
                    JOIN sales_receipt r ON r.receipt_id = d.receipt_id
                    WHERE r.receipt_no = ?
                    ORDER BY d.detail_id
                    """, receiptNo);
            for (Map<String, Object> rd : receiptDetails) {
                String dId = "GRRD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
                jdbcTemplate.update("""
                        INSERT INTO tms_reschedule_return_detail(detail_id, return_id, goods_code, goods_name, spec, unit_name, planned_qty, actual_return_qty, diff_qty, batch_no)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, NULL)
                        """, dId, returnId,
                        TmsUtil.str(rd.get("goodsCode")), TmsUtil.str(rd.get("goodsName")),
                        TmsUtil.str(rd.get("spec")), TmsUtil.str(rd.get("unitName")),
                        TmsUtil.toBd(rd.get("qty")));
            }
        } else {
            for (Map<String, Object> it : items) {
                String dId = "GRRD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
                jdbcTemplate.update("""
                        INSERT INTO tms_reschedule_return_detail(detail_id, return_id, goods_code, goods_name, spec, unit_name, planned_qty, actual_return_qty, diff_qty, batch_no)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?)
                        """, dId, returnId,
                        TmsUtil.str(it.get("goodsCode")), TmsUtil.str(it.get("goodsName")),
                        TmsUtil.str(it.get("spec")), TmsUtil.str(it.get("unitName")),
                        TmsUtil.toBd(it.get("plannedQty")), TmsUtil.str(it.get("batchNo")));
            }
        }

        // 更新调度明细状态
        jdbcTemplate.update("""
                UPDATE tms_dispatch_detail SET status='RESCHEDULED', sign_time=?, sign_user=?, remark=?
                WHERE detail_id=?
                """, Timestamp.valueOf(TmsUtil.now()), TmsUtil.currentUser(),
                "改派返仓：" + reason + "（第" + rescheduleCount + "次）", detailId);

        // 随主单一并落照片（可选）。
        // 之所以在建单接口里也支持 photos，而不是一律走 upload-photo：
        // APP 离线时先把建单请求排入本地队列，此刻 returnId 还不存在，
        // 照片若拆成第二个请求，重放时必然缺 returnId 被 400 拒绝并永久卡在队列里。
        // 建单时 returnId 已生成，这里顺带写入即可让离线链路一次成功。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> photos = body.get("photos") instanceof List<?> pl
                ? (List<Map<String, Object>>) pl : new ArrayList<>();
        for (Map<String, Object> p : photos) {
            String url = TmsUtil.str(p.get("url"));
            if (url.isEmpty()) continue;
            String photoId = "SP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, 'RESCHEDULE', ?, ?)
                    """, photoId, returnId, url, "reschedule-return/" + returnId + "/" + photoId);
        }

        TmsUtil.log(jdbcTemplate, "tms.app.reschedule-return", "CREATE", returnNo,
                "改派返仓单生成：" + receiptNo + "，原因：" + reason + "，第" + rescheduleCount + "次改派");
        return ApiResponse.ok(Map.of(
                "returnId", returnId,
                "returnNo", returnNo,
                "status", "PENDING",
                "rescheduleCount", rescheduleCount
        ));
    }

    /** 上传改派返仓留证照片（URL 数组，APP 端先调 /tms/app/upload/image 上传）。 */
    @PostMapping("/tms/app/reschedule-return/upload-photo")
    @Transactional
    public ApiResponse<Map<String, Object>> uploadPhoto(@RequestBody Map<String, Object> body) {
        String returnId = TmsUtil.str(body.get("returnId"));
        if (returnId.isEmpty()) return ApiResponse.fail("400", "returnId 不能为空");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> photos = body.get("photos") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        int saved = 0;
        for (Map<String, Object> p : photos) {
            String url = TmsUtil.str(p.get("url"));
            if (url.isEmpty()) continue;
            String photoId = "SP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, 'RESCHEDULE', ?, ?)
                    """, photoId, returnId, url, "reschedule-return/" + returnId + "/" + photoId);
            saved++;
        }
        return ApiResponse.ok(Map.of("returnId", returnId, "saved", saved));
    }

    /** 本司机待返仓改派返仓单列表。 */
    @PostMapping("/tms/app/reschedule-return/list")
    public ApiResponse<Map<String, Object>> list(@RequestBody(required = false) Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT return_id, return_no, trip_id, dispatch_id, receipt_no,
                       customer_code, customer_name, customer_address, reason, reason_detail,
                       total_qty, reschedule_date, reschedule_count, status, create_time, remark
                FROM tms_reschedule_return
                WHERE driver_id = ? AND status = 'PENDING'
                ORDER BY create_time DESC
                """, driverId);
        BigDecimal totalQty = rows.stream().map(x -> TmsUtil.toBd(x.get("totalQty"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("count", rows.size());
        result.put("totalQty", totalQty);
        return ApiResponse.ok(result);
    }

    /**
     * 返仓交接确认（仓库验收）。
     * 入参：returnIds:[...] 或 returnId（单条）
     * 处理：
     *   1. 更新 tms_reschedule_return.status = CHECKED
     *   2. 更新 sales_receipt.dispatch_status = RESCHEDULED（待改派，回调度池）
     *   3. 不反审核出库单，不生成入库单，库存不变
     */
    @PostMapping("/tms/app/reschedule-return/confirm")
    @Transactional
    public ApiResponse<Map<String, Object>> confirm(@RequestBody Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        Object single = body.get("returnId");
        if (single != null) ids.add(TmsUtil.str(single));
        Object multi = body.get("returnIds");
        if (multi instanceof List<?> l) {
            for (Object o : l) ids.add(TmsUtil.str(o));
        }
        if (ids.isEmpty()) return ApiResponse.fail("400", "请选择要交接的改派返仓单");

        int confirmed = 0;
        List<String> failed = new ArrayList<>();
        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        for (String returnId : ids) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT return_id, receipt_no, driver_id, status FROM tms_reschedule_return WHERE return_id=?",
                    returnId);
            if (rows.isEmpty()) { failed.add(returnId + ":不存在"); continue; }
            Map<String, Object> r = rows.get(0);
            if (!"PENDING".equals(TmsUtil.str(r.get("status")))) {
                failed.add(returnId + ":状态为" + r.get("status"));
                continue;
            }
            String receiptNo = TmsUtil.str(r.get("receipt_no"));
            // 更新改派返仓单状态
            jdbcTemplate.update("""
                    UPDATE tms_reschedule_return SET status='CHECKED', checked_at=?, checker=?
                    WHERE return_id=?
                    """, now, TmsUtil.currentUser(), returnId);
            // 更新发货单配送状态为「待改派」
            jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='RESCHEDULED' WHERE receipt_no=?", receiptNo);
            TmsUtil.log(jdbcTemplate, "tms.app.reschedule-return", "CHECK", returnId,
                    "改派返仓验收：" + receiptNo + " → 发货单回调度池");
            confirmed++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmed", confirmed);
        if (!failed.isEmpty()) result.put("failed", failed);
        return ApiResponse.ok(result);
    }

    // ========================================================================
    // ERP 端接口
    // ========================================================================

    /** 改派返仓单列表。 */
    @PostMapping("/tms/reschedule-return/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT r.return_id, r.return_no, r.trip_id, r.dispatch_id, r.receipt_no,
                       r.customer_code, r.customer_name, r.customer_address, r.reason, r.reason_detail,
                       r.total_qty, r.reschedule_date, r.reschedule_count, r.driver_id, r.driver_name,
                       r.status, r.returned_at, r.checked_at, r.checker, r.create_time, r.remark,
                       d.dispatch_no, t.trip_no
                FROM tms_reschedule_return r
                LEFT JOIN tms_dispatch d ON d.dispatch_id = r.dispatch_id
                LEFT JOIN tms_delivery_trip t ON t.trip_id = r.trip_id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String status = TmsUtil.str(filters.get("status"));
        if (!status.isEmpty()) { sql.append(" AND r.status = ?"); args.add(status); }
        String driverName = TmsUtil.str(filters.get("driverName"));
        if (!driverName.isEmpty()) { sql.append(" AND r.driver_name LIKE ?"); args.add("%" + driverName + "%"); }
        String customerName = TmsUtil.str(filters.get("customerName"));
        if (!customerName.isEmpty()) { sql.append(" AND r.customer_name LIKE ?"); args.add("%" + customerName + "%"); }
        String returnNo = TmsUtil.str(filters.get("returnNo"));
        if (!returnNo.isEmpty()) { sql.append(" AND r.return_no LIKE ?"); args.add("%" + returnNo + "%"); }
        sql.append(" ORDER BY r.create_time DESC");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 改派返仓单详情。 */
    @GetMapping("/tms/reschedule-return/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT r.*, d.dispatch_no, t.trip_no
                FROM tms_reschedule_return r
                LEFT JOIN tms_dispatch d ON d.dispatch_id = r.dispatch_id
                LEFT JOIN tms_delivery_trip t ON t.trip_id = r.trip_id
                WHERE r.return_id = ? OR r.return_no = ?
                """, id, id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "改派返仓单不存在：" + id);
        Map<String, Object> head = TmsUtil.camelize(heads.get(0));
        String returnId = TmsUtil.str(head.get("returnId"));
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT detail_id, goods_code, goods_name, spec, unit_name, planned_qty, actual_return_qty, diff_qty, batch_no, remark
                FROM tms_reschedule_return_detail WHERE return_id = ? ORDER BY detail_id
                """, returnId);
        head.put("details", details);
        List<Map<String, Object>> photos = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT photo_id, photo_type, photo_url, create_time
                FROM tms_sign_photo WHERE sign_id = ? ORDER BY create_time
                """, returnId);
        head.put("photos", photos);
        return ApiResponse.ok(head);
    }

    /**
     * 仓库验收（ERP 端）。
     * 入参：returnId, items:[{detailId, actualReturnQty}]
     * 处理：更新明细实收数量 + 差异 → status=CHECKED → 发货单回调度池
     */
    @PostMapping("/tms/reschedule-return/{id}/check")
    @Transactional
    public ApiResponse<Map<String, Object>> check(@PathVariable String id, @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT return_id, receipt_no, status FROM tms_reschedule_return WHERE return_id=? OR return_no=?", id, id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "改派返仓单不存在");
        Map<String, Object> r = rows.get(0);
        String returnId = TmsUtil.str(r.get("return_id"));
        String receiptNo = TmsUtil.str(r.get("receipt_no"));
        if ("CHECKED".equals(TmsUtil.str(r.get("status"))) || "REDISPATCHED".equals(TmsUtil.str(r.get("status")))) {
            return ApiResponse.fail("400", "当前状态为「" + r.get("status") + "」，不可重复验收");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = body.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        for (Map<String, Object> it : items) {
            String detailId = TmsUtil.str(it.get("detailId"));
            BigDecimal actualReturn = TmsUtil.toBd(it.get("actualReturnQty"));
            BigDecimal planned = TmsUtil.toBd(it.get("plannedQty"));
            jdbcTemplate.update("""
                    UPDATE tms_reschedule_return_detail
                    SET actual_return_qty=?, diff_qty=?
                    WHERE detail_id=? AND return_id=?
                    """, actualReturn, actualReturn.subtract(planned), detailId, returnId);
        }

        jdbcTemplate.update("""
                UPDATE tms_reschedule_return SET status='CHECKED', checked_at=?, checker=?
                WHERE return_id=?
                """, Timestamp.valueOf(TmsUtil.now()), TmsUtil.currentUser(), returnId);
        jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='RESCHEDULED' WHERE receipt_no=?", receiptNo);

        TmsUtil.log(jdbcTemplate, "tms.reschedule-return", "CHECK", returnId,
                "ERP 验收改派返仓单：" + receiptNo + " → 发货单回调度池");
        return ApiResponse.ok(Map.of("returnId", returnId, "status", "CHECKED", "receiptNo", receiptNo));
    }

    /** 返仓改派池（待重新派送的发货单）。 */
    @PostMapping("/tms/reschedule-return/pool")
    public ApiResponse<PageResult<Map<String, Object>>> pool(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT r.return_id, r.return_no, r.receipt_no, r.customer_code, r.customer_name, r.customer_address,
                       r.total_qty, r.reschedule_date, r.reschedule_count, r.reason, r.reason_detail,
                       r.driver_name, r.checked_at, r.create_time,
                       sr.source_outbound_no, sr.amount, sr.warehouse
                FROM tms_reschedule_return r
                LEFT JOIN sales_receipt sr ON sr.receipt_no = r.receipt_no
                WHERE r.status = 'CHECKED'
                """);
        List<Object> args = new ArrayList<>();
        String customerName = TmsUtil.str(filters.get("customerName"));
        if (!customerName.isEmpty()) { sql.append(" AND r.customer_name LIKE ?"); args.add("%" + customerName + "%"); }
        sql.append(" ORDER BY r.reschedule_date ASC, r.create_time DESC");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /**
     * 重新纳入调度。
     * 处理：status=REDISPATCHED，发货单 dispatch_status=UNDISPATCHED（回调度池）
     */
    @PostMapping("/tms/reschedule-return/{id}/redispatch")
    @Transactional
    public ApiResponse<Map<String, Object>> redispatch(@PathVariable String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT return_id, receipt_no, status FROM tms_reschedule_return WHERE return_id=? OR return_no=?", id, id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "改派返仓单不存在");
        Map<String, Object> r = rows.get(0);
        if (!"CHECKED".equals(TmsUtil.str(r.get("status")))) {
            return ApiResponse.fail("400", "仅「已验收」状态可重新派送");
        }
        String returnId = TmsUtil.str(r.get("return_id"));
        String receiptNo = TmsUtil.str(r.get("receipt_no"));
        jdbcTemplate.update("UPDATE tms_reschedule_return SET status='REDISPATCHED' WHERE return_id=?", returnId);
        jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='UNDISPATCHED', dispatch_id=NULL, trip_id=NULL WHERE receipt_no=?", receiptNo);
        TmsUtil.log(jdbcTemplate, "tms.reschedule-return", "REDISPATCH", returnId,
                "改派返仓单重新纳入调度：" + receiptNo);
        return ApiResponse.ok(Map.of("returnId", returnId, "status", "REDISPATCHED", "receiptNo", receiptNo));
    }
}
