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
 * 交账与结算管理（P3-3）。
 *
 * 业务流程：
 *   司机一天配送结束后 → 查看本日汇总（应收/实收/退货）→ 结算拍照 → 电子签名 → 提交交账
 *   → ERP 端财务审核（核对金额 vs 系统应收，处理长款/短款差异）
 *
 * APP 接口：
 *   POST /tms/app/settlement/summary        本日交账汇总预览（应收/实收/退货）
 *   POST /tms/app/settlement/submit         提交交账（生成交账单 + 照片 + 签名）
 *   POST /tms/app/settlement/upload-photo   上传结算照片（手机截图/现金清点/POS签购单）
 *
 * ERP 接口：
 *   POST /tms/settlement/page               交账单列表
 *   GET  /tms/settlement/{id}               交账单详情（含照片 + 签收明细）
 *   POST /tms/settlement/{id}/audit         审核交账单（→ APPROVED）
 *   POST /tms/settlement/{id}/dispute       标记差异争议（→ DISPUTED）
 */
@RestController
public class TmsSettlementController {

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;

    public TmsSettlementController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
    }

    // ========================================================================
    // APP 端接口
    // ========================================================================

    /**
     * 本日交账汇总预览。
     * 入参：dispatchId?（不传则汇总今日所有调度单）
     * 返回：totalStores, signedStores, totalAmount, cashAmount, onlineAmount,
     *       returnAmount, returnQty, submitAmount（应交回 = 现金 - 退货退款）
     */
    @PostMapping("/tms/app/settlement/summary")
    public ApiResponse<Map<String, Object>> summary(@RequestBody(required = false) Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        String dispatchId = body != null ? TmsUtil.str(body.get("dispatchId")) : "";

        // 查找今日调度单
        StringBuilder dispatchSql = new StringBuilder("""
                SELECT dispatch_id, dispatch_no, driver_name, route_line, store_count, amount, status
                FROM tms_dispatch
                WHERE driver_id = ? AND dispatch_date = CURRENT_DATE
                """);
        List<Object> args = new ArrayList<>();
        args.add(driverId);
        if (!dispatchId.isEmpty()) {
            dispatchSql.append(" AND dispatch_id = ?");
            args.add(dispatchId);
        }
        dispatchSql.append(" ORDER BY dispatch_id");
        List<Map<String, Object>> dispatches = TmsUtil.queryCamel(jdbcTemplate, dispatchSql.toString(), args.toArray());
        if (dispatches.isEmpty()) {
            return ApiResponse.fail("404", "今日无配送任务，无需交账");
        }

        // 检查是否已交账
        int existCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tms_settlement WHERE driver_id=? AND settle_date=CURRENT_DATE AND status IN ('PENDING','APPROVED')",
                Integer.class, driverId);
        if (existCount > 0) {
            List<Map<String, Object>> exist = TmsUtil.queryCamel(jdbcTemplate,
                    "SELECT settlement_id, settlement_no, status FROM tms_settlement WHERE driver_id=? AND settle_date=CURRENT_DATE ORDER BY create_time DESC LIMIT 1",
                    driverId);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("alreadySettled", true);
            if (!exist.isEmpty()) r.putAll(exist.get(0));
            return ApiResponse.ok(r);
        }

        // 聚合签收数据
        List<String> dispatchIds = new ArrayList<>();
        for (Map<String, Object> d : dispatches) {
            dispatchIds.add(TmsUtil.str(d.get("dispatchId")));
        }
        String idList = String.join("','", dispatchIds);

        // 应收金额 = 发货单明细 amount 合计
        BigDecimal totalAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM tms_dispatch_detail WHERE dispatch_id IN ('" + idList + "') AND bill_type='RECEIPT'",
                BigDecimal.class);
        if (totalAmount == null) totalAmount = BigDecimal.ZERO;

        // 已签收门店数
        int signedStores = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT detail_id) FROM tms_dispatch_detail WHERE dispatch_id IN ('" + idList + "') AND bill_type='RECEIPT' AND status IN ('DELIVERED','PARTIAL')",
                Integer.class);

        // 总门店数
        int totalStores = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT detail_id) FROM tms_dispatch_detail WHERE dispatch_id IN ('" + idList + "') AND bill_type='RECEIPT'",
                Integer.class);

        // 实收现金 = 签收记录中 pay_method='现金' 的 collect_amount 合计
        BigDecimal cashAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(collect_amount), 0) FROM tms_sign_record WHERE dispatch_id IN ('" + idList + "') AND pay_method='现金'",
                BigDecimal.class);
        if (cashAmount == null) cashAmount = BigDecimal.ZERO;

        // 线上收款 = pay_method IN ('微信','支付宝')
        BigDecimal onlineAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(collect_amount), 0) FROM tms_sign_record WHERE dispatch_id IN ('" + idList + "') AND pay_method IN ('微信','支付宝')",
                BigDecimal.class);
        if (onlineAmount == null) onlineAmount = BigDecimal.ZERO;

        // 退货金额 + 退货件数
        BigDecimal returnAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM tms_dispatch_detail WHERE dispatch_id IN ('" + idList + "') AND bill_type='RETURN'",
                BigDecimal.class);
        if (returnAmount == null) returnAmount = BigDecimal.ZERO;
        BigDecimal returnQty = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM tms_dispatch_detail WHERE dispatch_id IN ('" + idList + "') AND bill_type='RETURN'",
                BigDecimal.class);
        if (returnQty == null) returnQty = BigDecimal.ZERO;

        // 应交回金额 = 实收现金 - 退货退款
        BigDecimal submitAmount = cashAmount.subtract(returnAmount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alreadySettled", false);
        result.put("dispatches", dispatches);
        result.put("totalStores", totalStores);
        result.put("signedStores", signedStores);
        result.put("totalAmount", totalAmount);
        result.put("cashAmount", cashAmount);
        result.put("onlineAmount", onlineAmount);
        result.put("returnAmount", returnAmount);
        result.put("returnQty", returnQty);
        result.put("submitAmount", submitAmount);
        return ApiResponse.ok(result);
    }

    /**
     * 提交交账。
     * 入参：dispatchId?, actualSubmit(实际交回金额), diffReason?, signatureImg(base64), remark?, photos:[{base64, photoType?}]
     * 处理：
     *   1. 汇总今日签收数据 → 生成 tms_settlement(PENDING)
     *   2. 保存结算照片到 tms_settlement_photo
     *   3. 计算差异金额 = 实际交回 - 应交回
     */
    @PostMapping("/tms/app/settlement/submit")
    @Transactional
    public ApiResponse<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        BigDecimal actualSubmit = TmsUtil.toBd(body.get("actualSubmit"));
        String diffReason = TmsUtil.str(body.get("diffReason"));
        String signatureImg = TmsUtil.str(body.get("signatureImg"));
        String remark = TmsUtil.str(body.get("remark"));

        // 防重复提交
        int existCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tms_settlement WHERE driver_id=? AND settle_date=CURRENT_DATE AND status IN ('PENDING','APPROVED')",
                Integer.class, driverId);
        if (existCount > 0) {
            return ApiResponse.fail("400", "今日已提交交账，请勿重复提交");
        }

        // 查找今日调度单
        StringBuilder dispatchSql = new StringBuilder(
                "SELECT dispatch_id, dispatch_no, trip_id, driver_name, route_line FROM tms_dispatch WHERE driver_id=? AND dispatch_date=CURRENT_DATE");
        List<Object> dArgs = new ArrayList<>();
        dArgs.add(driverId);
        if (!dispatchId.isEmpty()) {
            dispatchSql.append(" AND dispatch_id=?");
            dArgs.add(dispatchId);
        }
        List<Map<String, Object>> dispatches = jdbcTemplate.queryForList(dispatchSql.toString(), dArgs.toArray());
        if (dispatches.isEmpty()) {
            return ApiResponse.fail("404", "今日无配送任务，无需交账");
        }

        // 聚合数据（与 summary 逻辑一致）
        List<String> dispatchIds = new ArrayList<>();
        String firstDispatchId = "";
        String firstTripId = "";
        String driverName = "";
        String routeLine = "";
        for (Map<String, Object> d : dispatches) {
            String did = TmsUtil.str(d.get("dispatch_id"));
            dispatchIds.add(did);
            if (firstDispatchId.isEmpty()) {
                firstDispatchId = did;
                firstTripId = TmsUtil.str(d.get("trip_id"));
                driverName = TmsUtil.str(d.get("driver_name"));
                routeLine = TmsUtil.str(d.get("route_line"));
            }
        }
        String idList = String.join("','", dispatchIds);

        BigDecimal totalAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM tms_dispatch_detail WHERE dispatch_id IN ('" + idList + "') AND bill_type='RECEIPT'",
                BigDecimal.class);
        if (totalAmount == null) totalAmount = BigDecimal.ZERO;

        int totalStores = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT detail_id) FROM tms_dispatch_detail WHERE dispatch_id IN ('" + idList + "') AND bill_type='RECEIPT'",
                Integer.class);
        int signedStores = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT detail_id) FROM tms_dispatch_detail WHERE dispatch_id IN ('" + idList + "') AND bill_type='RECEIPT' AND status IN ('DELIVERED','PARTIAL')",
                Integer.class);

        BigDecimal cashAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(collect_amount), 0) FROM tms_sign_record WHERE dispatch_id IN ('" + idList + "') AND pay_method='现金'",
                BigDecimal.class);
        if (cashAmount == null) cashAmount = BigDecimal.ZERO;

        BigDecimal onlineAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(collect_amount), 0) FROM tms_sign_record WHERE dispatch_id IN ('" + idList + "') AND pay_method IN ('微信','支付宝')",
                BigDecimal.class);
        if (onlineAmount == null) onlineAmount = BigDecimal.ZERO;

        BigDecimal returnAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM tms_dispatch_detail WHERE dispatch_id IN ('" + idList + "') AND bill_type='RETURN'",
                BigDecimal.class);
        if (returnAmount == null) returnAmount = BigDecimal.ZERO;
        BigDecimal returnQty = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM tms_dispatch_detail WHERE dispatch_id IN ('" + idList + "') AND bill_type='RETURN'",
                BigDecimal.class);
        if (returnQty == null) returnQty = BigDecimal.ZERO;

        BigDecimal submitAmount = cashAmount.subtract(returnAmount);
        BigDecimal diffAmount = actualSubmit.subtract(submitAmount);

        // 生成交账单
        String settlementId = "JZ" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String settlementNo = billNoGen.nextNo(
                BillNoGenerator.BillType.TMS_SETTLEMENT, "tms_settlement", "settlement_no");

        // 签名图：直接存 URL（APP 端先调 /tms/app/upload/image 上传获得）
        String signatureUrl = signatureImg;

        jdbcTemplate.update("""
                INSERT INTO tms_settlement(settlement_id, settlement_no, dispatch_id, trip_id, driver_id, driver_name,
                    route_line, settle_date, total_stores, signed_stores, total_amount, cash_amount, online_amount,
                    return_amount, return_qty, submit_amount, actual_submit, diff_amount, diff_reason,
                    signature_img, status, submitted_at, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_DATE, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, settlementId, settlementNo, firstDispatchId, firstTripId, driverId, driverName,
                routeLine, totalStores, signedStores, totalAmount, cashAmount, onlineAmount,
                returnAmount, returnQty, submitAmount, actualSubmit, diffAmount, diffReason,
                signatureUrl, Timestamp.valueOf(TmsUtil.now()), remark);

        // 保存结算照片（URL 直接入库，不再用 LONGTEXT 存 base64）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> photos = body.get("photos") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        int photoSaved = 0;
        for (Map<String, Object> p : photos) {
            String url = TmsUtil.str(p.get("url"));
            if (url.isEmpty()) continue;
            String photoType = TmsUtil.str(p.get("photoType"));
            if (photoType.isEmpty()) photoType = "CASH";
            String photoId = "JZP" + UUID.randomUUID().toString().replace("-", "").substring(0, 11).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO tms_settlement_photo(photo_id, settlement_id, photo_type, photo_url)
                    VALUES (?, ?, ?, ?)
                    """, photoId, settlementId, photoType, url);
            photoSaved++;
        }

        TmsUtil.log(jdbcTemplate, "tms.app.settlement", "SUBMIT", settlementNo,
                "司机交账提交：应交回" + submitAmount + "，实际交回" + actualSubmit + "，差异" + diffAmount + "，照片" + photoSaved + "张");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settlementId", settlementId);
        result.put("settlementNo", settlementNo);
        result.put("status", "PENDING");
        result.put("totalAmount", totalAmount);
        result.put("cashAmount", cashAmount);
        result.put("onlineAmount", onlineAmount);
        result.put("returnAmount", returnAmount);
        result.put("submitAmount", submitAmount);
        result.put("actualSubmit", actualSubmit);
        result.put("diffAmount", diffAmount);
        result.put("photoSaved", photoSaved);
        return ApiResponse.ok(result);
    }

    /** 上传结算照片（提交后补传，URL 数组）。 */
    @PostMapping("/tms/app/settlement/upload-photo")
    @Transactional
    public ApiResponse<Map<String, Object>> uploadPhoto(@RequestBody Map<String, Object> body) {
        String settlementId = TmsUtil.str(body.get("settlementId"));
        if (settlementId.isEmpty()) return ApiResponse.fail("400", "settlementId 不能为空");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> photos = body.get("photos") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        int saved = 0;
        for (Map<String, Object> p : photos) {
            String url = TmsUtil.str(p.get("url"));
            if (url.isEmpty()) continue;
            String photoType = TmsUtil.str(p.get("photoType"));
            if (photoType.isEmpty()) photoType = "CASH";
            String photoId = "JZP" + UUID.randomUUID().toString().replace("-", "").substring(0, 11).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO tms_settlement_photo(photo_id, settlement_id, photo_type, photo_url)
                    VALUES (?, ?, ?, ?)
                    """, photoId, settlementId, photoType, url);
            saved++;
        }
        return ApiResponse.ok(Map.of("settlementId", settlementId, "saved", saved));
    }

    // ========================================================================
    // ERP 端接口
    // ========================================================================

    /** 交账单列表。 */
    @PostMapping("/tms/settlement/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT s.settlement_id, s.settlement_no, s.dispatch_id, s.trip_id, s.driver_id, s.driver_name,
                       s.route_line, s.settle_date, s.total_stores, s.signed_stores, s.total_amount,
                       s.cash_amount, s.online_amount, s.return_amount, s.return_qty,
                       s.submit_amount, s.actual_submit, s.diff_amount, s.diff_reason,
                       s.status, s.submitted_at, s.audited_at, s.auditor, s.audit_remark,
                       s.create_time, s.remark,
                       d.dispatch_no
                FROM tms_settlement s
                LEFT JOIN tms_dispatch d ON d.dispatch_id = s.dispatch_id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String status = TmsUtil.str(filters.get("status"));
        if (!status.isEmpty()) { sql.append(" AND s.status = ?"); args.add(status); }
        String driverName = TmsUtil.str(filters.get("driverName"));
        if (!driverName.isEmpty()) { sql.append(" AND s.driver_name LIKE ?"); args.add("%" + driverName + "%"); }
        String settlementNo = TmsUtil.str(filters.get("settlementNo"));
        if (!settlementNo.isEmpty()) { sql.append(" AND s.settlement_no LIKE ?"); args.add("%" + settlementNo + "%"); }
        String routeLine = TmsUtil.str(filters.get("routeLine"));
        if (!routeLine.isEmpty()) { sql.append(" AND s.route_line LIKE ?"); args.add("%" + routeLine + "%"); }
        String settleDate = TmsUtil.str(filters.get("settleDate"));
        if (!settleDate.isEmpty()) { sql.append(" AND s.settle_date = ?"); args.add(settleDate); }
        sql.append(" ORDER BY s.settle_date DESC, s.create_time DESC");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 交账单详情（含照片 + 签收明细）。 */
    @GetMapping("/tms/settlement/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT s.*, d.dispatch_no
                FROM tms_settlement s
                LEFT JOIN tms_dispatch d ON d.dispatch_id = s.dispatch_id
                WHERE s.settlement_id = ? OR s.settlement_no = ?
                """, id, id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "交账单不存在：" + id);
        Map<String, Object> head = TmsUtil.camelize(heads.get(0));
        String settlementId = TmsUtil.str(head.get("settlementId"));
        String dispatchId = TmsUtil.str(head.get("dispatchId"));

        // 结算照片
        List<Map<String, Object>> photos = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT photo_id, photo_type, photo_url, create_time
                FROM tms_settlement_photo WHERE settlement_id = ? ORDER BY create_time
                """, settlementId);
        head.put("photos", photos);

        // 签收明细（该调度单的所有签收记录）
        if (!dispatchId.isEmpty()) {
            List<Map<String, Object>> signs = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT sign_id, detail_id, source_bill_no, customer_name, bill_type, sign_type,
                           signed_qty, reject_qty, collect_amount, pay_method, sign_time, customer_signer
                    FROM tms_sign_record WHERE dispatch_id = ? ORDER BY sign_time
                    """, dispatchId);
            head.put("signRecords", signs);
        }
        return ApiResponse.ok(head);
    }

    /**
     * 审核交账单（→ APPROVED）。
     * 入参：auditRemark?
     */
    @PostMapping("/tms/settlement/{id}/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> audit(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT settlement_id, settlement_no, status FROM tms_settlement WHERE settlement_id=? OR settlement_no=?", id, id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "交账单不存在");
        Map<String, Object> r = rows.get(0);
        String settlementId = TmsUtil.str(r.get("settlement_id"));
        String settlementNo = TmsUtil.str(r.get("settlement_no"));
        if ("APPROVED".equals(TmsUtil.str(r.get("status")))) {
            return ApiResponse.fail("400", "该交账单已审核，不可重复操作");
        }
        String auditRemark = body != null ? TmsUtil.str(body.get("auditRemark")) : "";
        jdbcTemplate.update("""
                UPDATE tms_settlement SET status='APPROVED', audited_at=?, auditor=?, audit_remark=?
                WHERE settlement_id=?
                """, Timestamp.valueOf(TmsUtil.now()), TmsUtil.currentUser(), auditRemark, settlementId);
        TmsUtil.log(jdbcTemplate, "tms.settlement", "AUDIT", settlementNo,
                "交账单审核通过：" + settlementNo + (auditRemark.isEmpty() ? "" : "（" + auditRemark + "）"));
        return ApiResponse.ok(Map.of("settlementId", settlementId, "settlementNo", settlementNo, "status", "APPROVED"));
    }

    /**
     * 标记差异争议（→ DISPUTED）。
     * 入参：diffReason（差异原因说明）
     */
    @PostMapping("/tms/settlement/{id}/dispute")
    @Transactional
    public ApiResponse<Map<String, Object>> dispute(@PathVariable String id, @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT settlement_id, settlement_no, status FROM tms_settlement WHERE settlement_id=? OR settlement_no=?", id, id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "交账单不存在");
        Map<String, Object> r = rows.get(0);
        String settlementId = TmsUtil.str(r.get("settlement_id"));
        String settlementNo = TmsUtil.str(r.get("settlement_no"));
        if ("APPROVED".equals(TmsUtil.str(r.get("status")))) {
            return ApiResponse.fail("400", "该交账单已审核通过，不可标记争议");
        }
        String diffReason = TmsUtil.str(body.get("diffReason"));
        if (diffReason.isEmpty()) diffReason = "财务标记差异争议，待司机核实";
        jdbcTemplate.update("""
                UPDATE tms_settlement SET status='DISPUTED', audit_remark=?, audited_at=?, auditor=?
                WHERE settlement_id=?
                """, diffReason, Timestamp.valueOf(TmsUtil.now()), TmsUtil.currentUser(), settlementId);
        TmsUtil.log(jdbcTemplate, "tms.settlement", "DISPUTE", settlementNo,
                "交账单标记差异争议：" + settlementNo + "（" + diffReason + "）");
        return ApiResponse.ok(Map.of("settlementId", settlementId, "settlementNo", settlementNo, "status", "DISPUTED"));
    }
}
