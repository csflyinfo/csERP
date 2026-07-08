package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.util.BillNoGenerator;
import com.erp.sales.RejectInboundController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.*;

/**
 * 客户拒收单管理（P3-2）。
 *
 * 触发场景：客户全部/部分拒收 → 货物随车返仓 → 仓库收货 → 生成拒收入库单（JSRK）→ 审核后库存增加、撤销应收
 * 核心原则：仓库收货时把拒收数量回写到 sales_receipt_detail.reject_qty，
 *           再调用 {@link RejectInboundController#generateFromReceipt(String)} 复用现有的 JSRK 生成流程，
 *           库存增加由 JSRK 审核完成（不在本控制器内直接动库存）。
 *
 * APP 接口：
 *   POST /tms/app/customer-reject/create         生成客户拒收单
 *   POST /tms/app/customer-reject/upload-photo   上传留证照片
 *   POST /tms/app/customer-reject/list           本司机待返仓客户拒收单列表
 *   POST /tms/app/customer-reject/confirm        司机返仓确认
 *
 * ERP 接口：
 *   POST /tms/customer-reject/page               客户拒收单列表
 *   GET  /tms/customer-reject/{id}               客户拒收单详情
 *   POST /tms/customer-reject/{id}/receive       仓库收货（生成拒收入库单）
 *   POST /tms/customer-reject/{id}/complete      完结（JSRK 已审核后置为 COMPLETED，撤销对应应收）
 */
@RestController
public class TmsCustomerRejectController {

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;
    private final RejectInboundController rejectInboundController;

    public TmsCustomerRejectController(JdbcTemplate jdbcTemplate,
                                       BillNoGenerator billNoGen,
                                       RejectInboundController rejectInboundController) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
        this.rejectInboundController = rejectInboundController;
    }

    // ========================================================================
    // APP 端接口
    // ========================================================================

    /**
     * 生成客户拒收单。
     * 入参：dispatchId, detailId, receiptNo, rejectReason, reasonDetail, remark?,
     *       items:[{goodsCode, goodsName, spec, unitName, rejectQty, price, batchNo?}]
     * 处理：
     *   1. 生成 tms_customer_reject(PENDING) + 明细
     *   2. 更新 tms_dispatch_detail.status = REJECTED
     *   3. 全拒收时 sales_receipt.dispatch_status = REJECTED
     */
    @PostMapping("/tms/app/customer-reject/create")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String detailId = TmsUtil.str(body.get("detailId"));
        String receiptNo = TmsUtil.str(body.get("receiptNo"));
        String rejectReason = TmsUtil.str(body.get("rejectReason"));
        String reasonDetail = TmsUtil.str(body.get("reasonDetail"));
        String remark = TmsUtil.str(body.get("remark"));
        if (dispatchId.isEmpty() || detailId.isEmpty() || receiptNo.isEmpty()) {
            return ApiResponse.fail("400", "dispatchId/detailId/receiptNo 不能为空");
        }
        if (rejectReason.isEmpty()) {
            return ApiResponse.fail("400", "请选择拒收原因");
        }

        // 校验调度明细属于当前司机
        List<Map<String, Object>> detailRows = jdbcTemplate.queryForList(
                "SELECT * FROM tms_dispatch_detail WHERE detail_id=? AND dispatch_id=?", detailId, dispatchId);
        if (detailRows.isEmpty()) return ApiResponse.fail("404", "调度明细不存在");
        Map<String, Object> d = detailRows.get(0);
        List<Map<String, Object>> dispRows = jdbcTemplate.queryForList(
                "SELECT driver_id, driver_name FROM tms_dispatch WHERE dispatch_id=?", dispatchId);
        if (dispRows.isEmpty()) return ApiResponse.fail("404", "调度单不存在");
        String driverName = TmsUtil.str(dispRows.get(0).get("driver_name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = body.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        if (items.isEmpty()) {
            // 未传明细，从发货单明细拉取全部商品，作为全拒收
            List<Map<String, Object>> receiptDetails = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT d.goods_code, d.goods_name, d.spec, d.unit_name, d.qty, d.price
                    FROM sales_receipt_detail d
                    JOIN sales_receipt r ON r.receipt_id = d.receipt_id
                    WHERE r.receipt_no = ?
                    ORDER BY d.detail_id
                    """, receiptNo);
            items = receiptDetails.stream().map(rd -> {
                Map<String, Object> it = new LinkedHashMap<>(rd);
                it.put("rejectQty", rd.get("qty"));
                return it;
            }).toList();
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            BigDecimal qty = TmsUtil.toBd(it.get("rejectQty"));
            BigDecimal price = TmsUtil.toBd(it.get("price"));
            totalQty = totalQty.add(qty);
            totalAmount = totalAmount.add(qty.multiply(price).setScale(2, RoundingMode.HALF_UP));
        }
        if (totalQty.compareTo(BigDecimal.ZERO) == 0) {
            return ApiResponse.fail("400", "拒收明细数量不能为空");
        }

        String rejectId = TmsUtil.uuid("KHJS");
        String rejectNo = billNoGen.nextNo(
                BillNoGenerator.BillType.TMS_CUSTOMER_REJECT, "tms_customer_reject", "reject_no");

        jdbcTemplate.update("""
                INSERT INTO tms_customer_reject(reject_id, reject_no, trip_id, dispatch_id, detail_id, receipt_no,
                    customer_code, customer_name, customer_address, reject_reason, reason_detail,
                    total_qty, total_amount, driver_id, driver_name, status, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, rejectId, rejectNo,
                TmsUtil.str(d.get("trip_id")), dispatchId, detailId, receiptNo,
                TmsUtil.str(d.get("customer_code")), TmsUtil.str(d.get("customer_name")),
                TmsUtil.str(d.get("customer_address")), rejectReason, reasonDetail,
                totalQty, totalAmount, driverId, driverName, remark);

        for (Map<String, Object> it : items) {
            String dId = TmsUtil.uuid("CJD");
            BigDecimal qty = TmsUtil.toBd(it.get("rejectQty"));
            BigDecimal price = TmsUtil.toBd(it.get("price"));
            BigDecimal amount = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            jdbcTemplate.update("""
                    INSERT INTO tms_customer_reject_detail(detail_id, reject_id, goods_code, goods_name, spec, unit_name,
                        reject_qty, actual_receive_qty, diff_qty, price, amount, batch_no)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)
                    """, dId, rejectId,
                    TmsUtil.str(it.get("goodsCode")), TmsUtil.str(it.get("goodsName")),
                    TmsUtil.str(it.get("spec")), TmsUtil.str(it.get("unitName")),
                    qty, price, amount, TmsUtil.str(it.get("batchNo")));
        }

        // 更新调度明细状态为「已拒收」
        jdbcTemplate.update("""
                UPDATE tms_dispatch_detail SET status='REJECTED', sign_time=?, sign_user=?, remark=?
                WHERE detail_id=?
                """, Timestamp.valueOf(TmsUtil.now()), driverId,
                "客户拒收：" + rejectReason, detailId);

        // 全拒收（拒收数量 ≥ 配送数量）时发货单配送状态置为「已拒收」
        BigDecimal requiredQty = TmsUtil.toBd(d.get("qty"));
        if (totalQty.compareTo(requiredQty) >= 0) {
            jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='REJECTED' WHERE receipt_no=?", receiptNo);
        }

        TmsUtil.log(jdbcTemplate, "tms.app.customer-reject", "CREATE", rejectNo,
                "客户拒收单生成：" + receiptNo + "，原因：" + rejectReason + "，数量：" + totalQty);
        return ApiResponse.ok(Map.of(
                "rejectId", rejectId,
                "rejectNo", rejectNo,
                "status", "PENDING",
                "totalQty", totalQty,
                "totalAmount", totalAmount
        ));
    }

    /** 上传客户拒收留证照片（URL 数组，APP 端先调 /tms/app/upload/image 上传）。 */
    @PostMapping("/tms/app/customer-reject/upload-photo")
    @Transactional
    public ApiResponse<Map<String, Object>> uploadPhoto(@RequestBody Map<String, Object> body) {
        String rejectId = TmsUtil.str(body.get("rejectId"));
        if (rejectId.isEmpty()) return ApiResponse.fail("400", "rejectId 不能为空");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> photos = body.get("photos") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        int saved = 0;
        for (Map<String, Object> p : photos) {
            String url = TmsUtil.str(p.get("url"));
            if (url.isEmpty()) continue;
            String photoId = TmsUtil.uuid("SP");
            jdbcTemplate.update("""
                    INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, 'CUSTOMER_REJECT', ?, ?)
                    """, photoId, rejectId, url, "customer-reject/" + rejectId + "/" + photoId);
            saved++;
        }
        return ApiResponse.ok(Map.of("rejectId", rejectId, "saved", saved));
    }

    /** 本司机待返仓客户拒收单列表。 */
    @PostMapping("/tms/app/customer-reject/list")
    public ApiResponse<Map<String, Object>> list(@RequestBody(required = false) Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT reject_id, reject_no, trip_id, dispatch_id, receipt_no,
                       customer_code, customer_name, customer_address, reject_reason, reason_detail,
                       total_qty, total_amount, status, create_time, remark
                FROM tms_customer_reject
                WHERE driver_id = ? AND status = 'PENDING'
                ORDER BY create_time DESC
                """, driverId);
        BigDecimal totalQty = rows.stream().map(x -> TmsUtil.toBd(x.get("totalQty"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = rows.stream().map(x -> TmsUtil.toBd(x.get("totalAmount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("count", rows.size());
        result.put("totalQty", totalQty);
        result.put("totalAmount", totalAmount);
        return ApiResponse.ok(result);
    }

    /**
     * 司机返仓确认。
     * 入参：rejectIds:[...] 或 rejectId（单条）
     * 处理：仅记录 returned_at（司机实际到仓时间），状态仍为 PENDING，等待仓库收货
     */
    @PostMapping("/tms/app/customer-reject/confirm")
    @Transactional
    public ApiResponse<Map<String, Object>> confirm(@RequestBody Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        Object single = body.get("rejectId");
        if (single != null) ids.add(TmsUtil.str(single));
        Object multi = body.get("rejectIds");
        if (multi instanceof List<?> l) {
            for (Object o : l) ids.add(TmsUtil.str(o));
        }
        if (ids.isEmpty()) return ApiResponse.fail("400", "请选择要交接的客户拒收单");

        int confirmed = 0;
        List<String> failed = new ArrayList<>();
        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        for (String rejectId : ids) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT reject_id, status FROM tms_customer_reject WHERE reject_id=?", rejectId);
            if (rows.isEmpty()) { failed.add(rejectId + ":不存在"); continue; }
            String status = TmsUtil.str(rows.get(0).get("status"));
            if ("RECEIVED".equals(status) || "COMPLETED".equals(status)) {
                failed.add(rejectId + ":状态为" + status);
                continue;
            }
            jdbcTemplate.update("UPDATE tms_customer_reject SET returned_at=? WHERE reject_id=?", now, rejectId);
            TmsUtil.log(jdbcTemplate, "tms.app.customer-reject", "CONFIRM", rejectId,
                    "司机返仓确认客户拒收单");
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

    /** 客户拒收单列表。 */
    @PostMapping("/tms/customer-reject/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT r.reject_id, r.reject_no, r.trip_id, r.dispatch_id, r.receipt_no,
                       r.customer_code, r.customer_name, r.customer_address, r.reject_reason, r.reason_detail,
                       r.total_qty, r.total_amount, r.reject_inbound_no, r.driver_id, r.driver_name,
                       r.status, r.returned_at, r.received_at, r.receiver, r.create_time, r.remark,
                       d.dispatch_no, t.trip_no, sr.source_outbound_no
                FROM tms_customer_reject r
                LEFT JOIN tms_dispatch d ON d.dispatch_id = r.dispatch_id
                LEFT JOIN tms_delivery_trip t ON t.trip_id = r.trip_id
                LEFT JOIN sales_receipt sr ON sr.receipt_no = r.receipt_no
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String status = TmsUtil.str(filters.get("status"));
        if (!status.isEmpty()) { sql.append(" AND r.status = ?"); args.add(status); }
        String driverName = TmsUtil.str(filters.get("driverName"));
        if (!driverName.isEmpty()) { sql.append(" AND r.driver_name LIKE ?"); args.add("%" + driverName + "%"); }
        String customerName = TmsUtil.str(filters.get("customerName"));
        if (!customerName.isEmpty()) { sql.append(" AND r.customer_name LIKE ?"); args.add("%" + customerName + "%"); }
        String rejectNo = TmsUtil.str(filters.get("rejectNo"));
        if (!rejectNo.isEmpty()) { sql.append(" AND r.reject_no LIKE ?"); args.add("%" + rejectNo + "%"); }
        sql.append(" ORDER BY r.create_time DESC");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 客户拒收单详情。 */
    @GetMapping("/tms/customer-reject/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT r.*, d.dispatch_no, t.trip_no, sr.source_outbound_no
                FROM tms_customer_reject r
                LEFT JOIN tms_dispatch d ON d.dispatch_id = r.dispatch_id
                LEFT JOIN tms_delivery_trip t ON t.trip_id = r.trip_id
                LEFT JOIN sales_receipt sr ON sr.receipt_no = r.receipt_no
                WHERE r.reject_id = ? OR r.reject_no = ?
                """, id, id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "客户拒收单不存在：" + id);
        Map<String, Object> head = TmsUtil.camelize(heads.get(0));
        String rejectId = TmsUtil.str(head.get("rejectId"));
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT detail_id, goods_code, goods_name, spec, unit_name, reject_qty, actual_receive_qty,
                       diff_qty, price, amount, batch_no, remark
                FROM tms_customer_reject_detail WHERE reject_id = ? ORDER BY detail_id
                """, rejectId);
        head.put("details", details);
        List<Map<String, Object>> photos = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT photo_id, photo_type, photo_url, create_time
                FROM tms_sign_photo WHERE sign_id = ? ORDER BY create_time
                """, rejectId);
        head.put("photos", photos);
        return ApiResponse.ok(head);
    }

    /**
     * 仓库收货（ERP 端）。
     * 入参：items:[{detailId, actualReceiveQty}]（可选，未传默认实收=拒收数量）
     * 处理：
     *   1. 更新明细实收数量 + 差异
     *   2. 把拒收数量回写到 sales_receipt_detail.reject_qty（按 goods_code 匹配，累加）
     *   3. 调用 RejectInboundController.generateFromReceipt(receiptNo) 生成拒收入库单 JSRK
     *   4. status=RECEIVED，记录 reject_inbound_no
     *   5. 撤销对应应收（按拒收金额冲销 FinAr）
     */
    @PostMapping("/tms/customer-reject/{id}/receive")
    @Transactional
    public ApiResponse<Map<String, Object>> receive(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT reject_id, reject_no, receipt_no, status, total_qty, total_amount FROM tms_customer_reject WHERE reject_id=? OR reject_no=?", id, id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "客户拒收单不存在");
        Map<String, Object> r = rows.get(0);
        String rejectId = TmsUtil.str(r.get("reject_id"));
        String rejectNo = TmsUtil.str(r.get("reject_no"));
        String receiptNo = TmsUtil.str(r.get("receipt_no"));
        String status = TmsUtil.str(r.get("status"));
        if ("RECEIVED".equals(status) || "COMPLETED".equals(status)) {
            return ApiResponse.fail("400", "当前状态为「" + status + "」，不可重复收货");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (body != null && body.get("items") instanceof List<?> l)
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        // 拉取当前明细
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT detail_id, goods_code, reject_qty, price FROM tms_customer_reject_detail WHERE reject_id=?", rejectId);

        // 更新明细实收数量 + 差异
        Map<String, Map<String, Object>> reqByDetailId = new HashMap<>();
        for (Map<String, Object> it : items) {
            String did = TmsUtil.str(it.get("detailId"));
            if (!did.isEmpty()) reqByDetailId.put(did, it);
        }
        BigDecimal totalReceiveQty = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            String detailId = TmsUtil.str(d.get("detail_id"));
            String goodsCode = TmsUtil.str(d.get("goods_code"));
            BigDecimal rejectQty = TmsUtil.toBd(d.get("reject_qty"));
            BigDecimal actualReceive = rejectQty;
            Map<String, Object> req = reqByDetailId.get(detailId);
            if (req != null && req.containsKey("actualReceiveQty")) {
                actualReceive = TmsUtil.toBd(req.get("actualReceiveQty"));
            }
            BigDecimal diff = actualReceive.subtract(rejectQty);
            jdbcTemplate.update("""
                    UPDATE tms_customer_reject_detail
                    SET actual_receive_qty=?, diff_qty=?
                    WHERE detail_id=? AND reject_id=?
                    """, actualReceive, diff, detailId, rejectId);
            totalReceiveQty = totalReceiveQty.add(actualReceive);

            // 回写拒收数量到 sales_receipt_detail（按 goods_code 匹配，累加到已存在的 reject_qty）
            jdbcTemplate.update("""
                    UPDATE sales_receipt_detail
                    SET reject_qty = COALESCE(reject_qty, 0) + ?,
                        reject_amount = ROUND((COALESCE(reject_qty, 0) + ?) * COALESCE(price, 0), 2)
                    WHERE receipt_id = (SELECT receipt_id FROM sales_receipt WHERE receipt_no = ?)
                      AND goods_code = ?
                    """, actualReceive, actualReceive, receiptNo, goodsCode);
        }

        // 调用 RejectInboundController 生成拒收入库单 JSRK（内部幂等，已存在则返回原单号）
        String inboundNo = rejectInboundController.generateFromReceipt(receiptNo);

        // 撤销对应应收：按拒收金额冲减 fin_ar.ar_amount / unreceived_amount，
        // ar_amount 归零则置为 VERIFIED（已核销）
        BigDecimal totalAmount = TmsUtil.toBd(r.get("total_amount"));
        if (totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            try {
                List<Map<String, Object>> arRows = jdbcTemplate.queryForList(
                        "SELECT ar_id, ar_amount, unreceived_amount FROM fin_ar WHERE source_bill=? AND status='UNVERIFIED'",
                        receiptNo);
                BigDecimal remaining = totalAmount;
                for (Map<String, Object> ar : arRows) {
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                    BigDecimal arAmount = TmsUtil.toBd(ar.get("ar_amount"));
                    BigDecimal unreceived = TmsUtil.toBd(ar.get("unreceived_amount"));
                    BigDecimal deduct = remaining.min(arAmount);
                    BigDecimal newArAmount = arAmount.subtract(deduct);
                    BigDecimal newUnreceived = unreceived.subtract(deduct).max(BigDecimal.ZERO);
                    String newStatus = newArAmount.compareTo(BigDecimal.ZERO) <= 0 ? "VERIFIED" : "UNVERIFIED";
                    jdbcTemplate.update("""
                            UPDATE fin_ar SET ar_amount=?, unreceived_amount=?, status=?
                            WHERE ar_id=?
                            """, newArAmount, newUnreceived, newStatus, ar.get("ar_id"));
                    remaining = remaining.subtract(deduct);
                }
                if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                    TmsUtil.log(jdbcTemplate, "tms.customer-reject", "AR_PARTIAL", rejectNo,
                            "客户拒收单部分应收冲销失败，剩余金额：" + remaining + "，发货单：" + receiptNo);
                }
            } catch (Exception e) {
                // 应收冲销失败不阻断主流程，记录日志后继续
                TmsUtil.log(jdbcTemplate, "tms.customer-reject", "AR_WRITEOFF_FAIL", rejectNo,
                        "客户拒收单应收冲销异常：" + receiptNo + "，金额：" + totalAmount + "，原因：" + e.getMessage());
            }
        }

        // 更新客户拒收单状态为「已收货」
        jdbcTemplate.update("""
                UPDATE tms_customer_reject SET status='RECEIVED', received_at=?, receiver=?, reject_inbound_no=?
                WHERE reject_id=?
                """, Timestamp.valueOf(TmsUtil.now()), TmsUtil.currentUser(),
                inboundNo == null ? null : inboundNo, rejectId);

        TmsUtil.log(jdbcTemplate, "tms.customer-reject", "RECEIVE", rejectNo,
                "客户拒收单仓库收货：" + receiptNo + " → JSRK：" + (inboundNo == null ? "未生成" : inboundNo));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rejectId", rejectId);
        result.put("rejectNo", rejectNo);
        result.put("status", "RECEIVED");
        result.put("rejectInboundNo", inboundNo);
        result.put("totalReceiveQty", totalReceiveQty);
        return ApiResponse.ok(result);
    }

    /**
     * 完结（JSRK 审核完成后手动/系统触发置为 COMPLETED）。
     * 校验 inv_reject_inbound.status = APPROVED
     */
    @PostMapping("/tms/customer-reject/{id}/complete")
    @Transactional
    public ApiResponse<Map<String, Object>> complete(@PathVariable String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT reject_id, reject_no, reject_inbound_no, status FROM tms_customer_reject WHERE reject_id=? OR reject_no=?", id, id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "客户拒收单不存在");
        Map<String, Object> r = rows.get(0);
        String rejectId = TmsUtil.str(r.get("reject_id"));
        String rejectNo = TmsUtil.str(r.get("reject_no"));
        String status = TmsUtil.str(r.get("status"));
        String inboundNo = TmsUtil.str(r.get("reject_inbound_no"));
        if (!"RECEIVED".equals(status)) {
            return ApiResponse.fail("400", "仅「已收货」状态可完结，当前状态：" + status);
        }
        if (!inboundNo.isEmpty()) {
            List<Map<String, Object>> inboundRows = jdbcTemplate.queryForList(
                    "SELECT status FROM inv_reject_inbound WHERE inbound_no=?", inboundNo);
            if (inboundRows.isEmpty()) {
                return ApiResponse.fail("400", "关联的拒收入库单不存在：" + inboundNo);
            }
            String inboundStatus = TmsUtil.str(inboundRows.get(0).get("status"));
            if (!"APPROVED".equals(inboundStatus)) {
                return ApiResponse.fail("400", "拒收入库单未审核，当前状态：" + inboundStatus);
            }
        }
        jdbcTemplate.update("UPDATE tms_customer_reject SET status='COMPLETED' WHERE reject_id=?", rejectId);
        TmsUtil.log(jdbcTemplate, "tms.customer-reject", "COMPLETE", rejectNo,
                "客户拒收单完结：" + rejectNo);
        return ApiResponse.ok(Map.of("rejectId", rejectId, "rejectNo", rejectNo, "status", "COMPLETED"));
    }
}
