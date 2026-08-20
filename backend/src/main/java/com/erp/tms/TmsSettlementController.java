package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.util.BillNoGenerator;
import com.erp.tms.service.TmsNotifyService;
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
    private final TmsNotifyService notifyService;
    private final TmsStoreSettleController storeSettleController;

    public TmsSettlementController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen,
                                   TmsNotifyService notifyService,
                                   TmsStoreSettleController storeSettleController) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
        this.notifyService = notifyService;
        this.storeSettleController = storeSettleController;
    }

    // ========================================================================
    // APP 端接口
    // ========================================================================

    /**
     * 本日交账汇总预览。
     * 入参：dispatchId?（不传则汇总今日所有调度单）
     * 返回：totalStores, signedStores, totalAmount, cashAmount, onlineAmount,
     *       returnAmount, returnQty, creditAmount, submitAmount（应交回 = 实收现金）
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

        // 实收现金 / 线上收款。
        // 两条来源相加：门店结算（新流程，钱记在 tms_store_settlement_account）
        // + 旧签收路径（历史数据里 tms_sign_record 直接带了 collect_amount + pay_method）。
        // 门店结算走 writeSignRecord 时 collect_amount 恒为 0，所以两者不会重复计算。
        Map<String, BigDecimal> storeTotals = loadStoreSettleTotals(driverId, dispatchIds);

        BigDecimal signCash = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(collect_amount), 0) FROM tms_sign_record WHERE dispatch_id IN ('" + idList + "') AND pay_method='现金'",
                BigDecimal.class);
        if (signCash == null) signCash = BigDecimal.ZERO;
        BigDecimal cashAmount = storeTotals.get("cashAmount").add(signCash);

        BigDecimal signOnline = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(collect_amount), 0) FROM tms_sign_record WHERE dispatch_id IN ('" + idList + "') AND pay_method IN ('微信','支付宝')",
                BigDecimal.class);
        if (signOnline == null) signOnline = BigDecimal.ZERO;
        BigDecimal onlineAmount = storeTotals.get("onlineAmount").add(signOnline);

        // 退货金额 + 退货件数，与 submit 共用 loadReturnTotals 保证口径一致。
        // 只统计已回收（DELIVERED/PARTIAL）的退货单，未到店的退货不该冲减应交回。
        BigDecimal[] returnTotals = loadReturnTotals(dispatchIds);
        BigDecimal returnAmount = returnTotals[0];
        BigDecimal returnQty = returnTotals[1];

        // 应交回金额 = 实收现金。
        // 不再减退货：门店结算已经在应结净额里冲减过退货（收的本就是净额），
        // 这里再减一次等于让司机白交一遍退货款。returnAmount 仅作展示。
        BigDecimal submitAmount = cashAmount;

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
        // 挂账金额来自门店结算，交账页展示用（不计入应交回，司机手上没有这笔钱）
        result.put("creditAmount", storeTotals.get("creditAmount"));
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

        // 查找今日调度单。
        // trip_id 不在 tms_dispatch 上：它是 tms_delivery_trip 的主键，一张调度单出车才会生成行程。
        // 所以只能 LEFT JOIN 带出来，未出车时为 NULL（交账允许没有行程号）。
        StringBuilder dispatchSql = new StringBuilder("""
                SELECT d.dispatch_id, d.dispatch_no, t.trip_id, d.driver_name, d.route_line
                FROM tms_dispatch d
                LEFT JOIN tms_delivery_trip t ON t.dispatch_id = d.dispatch_id
                WHERE d.driver_id=? AND d.dispatch_date=CURRENT_DATE""");
        List<Object> dArgs = new ArrayList<>();
        dArgs.add(driverId);
        if (!dispatchId.isEmpty()) {
            dispatchSql.append(" AND d.dispatch_id=?");
            dArgs.add(dispatchId);
        }
        // 用 queryCamel 而不是 queryForList：H2 返回的列标签是大写的 DISPATCH_ID，
        // 直接 get("dispatch_id") 会全部取到 null，交账单会写进一串空字段。
        List<Map<String, Object>> dispatches = TmsUtil.queryCamel(jdbcTemplate, dispatchSql.toString(), dArgs.toArray());
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
            String did = TmsUtil.str(d.get("dispatchId"));
            // 一张调度单可能有多条行程，LEFT JOIN 会出重复行，这里去重避免 IN 列表里塞重复 ID
            if (did.isEmpty() || dispatchIds.contains(did)) continue;
            dispatchIds.add(did);
            if (firstDispatchId.isEmpty()) {
                firstDispatchId = did;
                firstTripId = TmsUtil.str(d.get("tripId"));
                driverName = TmsUtil.str(d.get("driverName"));
                routeLine = TmsUtil.str(d.get("routeLine"));
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

        // 收款口径必须与 summary 完全一致：门店结算 + 旧签收路径。
        // 这里是真正入账的地方，口径分叉会让司机看到的应交回和落库的不是一个数。
        Map<String, BigDecimal> storeTotals = loadStoreSettleTotals(driverId, dispatchIds);

        BigDecimal signCash = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(collect_amount), 0) FROM tms_sign_record WHERE dispatch_id IN ('" + idList + "') AND pay_method='现金'",
                BigDecimal.class);
        if (signCash == null) signCash = BigDecimal.ZERO;
        BigDecimal cashAmount = storeTotals.get("cashAmount").add(signCash);

        BigDecimal signOnline = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(collect_amount), 0) FROM tms_sign_record WHERE dispatch_id IN ('" + idList + "') AND pay_method IN ('微信','支付宝')",
                BigDecimal.class);
        if (signOnline == null) signOnline = BigDecimal.ZERO;
        BigDecimal onlineAmount = storeTotals.get("onlineAmount").add(signOnline);

        // 与 summary 共用 loadReturnTotals：这里是真正入账的地方，
        // 口径分叉会把错的应交回金额固化进交账单，事后只能靠财务手工调账。
        BigDecimal[] returnTotals = loadReturnTotals(dispatchIds);
        BigDecimal returnAmount = returnTotals[0];
        BigDecimal returnQty = returnTotals[1];

        // 应交回 = 实收现金，退货已在门店结算净额里冲减过，不再重复扣减
        BigDecimal submitAmount = cashAmount;
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

        // 反写门店结算单的 settlement_id（V71 预留列）。
        // 交账审核时财务要按交账单反查「这次交回的钱对应哪些门店结算单」，
        // 才能把那批 fin_status=PENDING 的收款单一起过审。不反写这层关联就断了，
        // 只能靠 driver_id + 日期反猜，跨日补交账时必然错配。
        // 限定 settlement_id IS NULL：已被前一张交账单认领的结算单不能再被抢走。
        int linked = 0;
        if (!dispatchIds.isEmpty()) {
            String ph = String.join(",", Collections.nCopies(dispatchIds.size(), "?"));
            List<Object> linkArgs = new ArrayList<>();
            linkArgs.add(settlementId);
            linkArgs.addAll(dispatchIds);
            linkArgs.add(driverId);
            linked = jdbcTemplate.update("""
                    UPDATE tms_store_settlement SET settlement_id = ?
                    WHERE dispatch_id IN (""" + ph + """
                    ) AND driver_id = ? AND settle_status = 'SETTLED' AND settlement_id IS NULL
                    """, linkArgs.toArray());
        }

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
                "司机交账提交：应交回" + submitAmount + "，实际交回" + actualSubmit + "，差异" + diffAmount
                        + "，照片" + photoSaved + "张，关联门店结算单" + linked + "张");

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
     *
     * 审核即「钱已回到公司」，因此同步触发财务入账：该交账单下所有门店结算的
     * 待审核收款单在此自动审核（核销应收 + 资金入账 + 往来流水）。
     */
    @PostMapping("/tms/settlement/{id}/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> audit(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate,
                "SELECT settlement_id, settlement_no, status, driver_id, settle_date FROM tms_settlement WHERE settlement_id=? OR settlement_no=?", id, id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "交账单不存在");
        Map<String, Object> r = rows.get(0);
        String settlementId = TmsUtil.str(r.get("settlementId"));
        String settlementNo = TmsUtil.str(r.get("settlementNo"));
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

        // 补认领门店结算单。
        // submit 时已按 dispatch_id 关联过一轮，但司机常在交账提交之后才补做剩下门店的结算
        // （交账页和配送点页是两条独立入口），那批结算单的 settlement_id 会一直是 NULL，
        // 审核时按 settlement_id 反查就查不到，收款单永远停在 PENDING。
        // 这里按「同司机 + 结算日期 = 交账日期 + 尚未被任何交账单认领」再兜一次，
        // 与 submit 的取数口径（当日该司机的调度单）一致，不会把别的交账单的结算抢过来。
        int lateLinked = jdbcTemplate.update("""
                UPDATE tms_store_settlement SET settlement_id = ?
                WHERE settlement_id IS NULL AND settle_status = 'SETTLED'
                  AND driver_id = ? AND CAST(settle_time AS DATE) = ?
                """, settlementId, TmsUtil.str(r.get("driverId")), r.get("settleDate"));
        if (lateLinked > 0) {
            TmsUtil.log(jdbcTemplate, "tms.settlement", "LINK", settlementNo,
                    "审核时补关联门店结算单 " + lateLinked + " 张（交账提交后才完成的结算）");
        }

        // 财务联动：门店结算收款单自动审核核销
        Map<String, Object> finance = storeSettleController.approveStoreSettlements(settlementId, settlementNo);

        // 回执司机：交账关系到司机自己的钱，审核结果必须主动告知
        notifySettleResult(settlementId, settlementNo, TmsNotifyService.LEVEL_NORMAL,
                "交账已通过 " + settlementNo,
                "您的交账单已审核通过。" + (auditRemark.isEmpty() ? "" : "备注：" + auditRemark));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settlementId", settlementId);
        data.put("settlementNo", settlementNo);
        data.put("status", "APPROVED");
        data.put("finance", finance);
        return ApiResponse.ok(data);
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
        // 差异争议用 URGENT：司机需要尽快核实金额，拖过当天现金流水就查不清了
        notifySettleResult(settlementId, settlementNo, TmsNotifyService.LEVEL_URGENT,
                "交账存在差异 " + settlementNo,
                "财务标记差异争议，请尽快核实。原因：" + diffReason);
        return ApiResponse.ok(Map.of("settlementId", settlementId, "settlementNo", settlementNo, "status", "DISPUTED"));
    }

    /**
     * 汇总这批调度单下的退货金额与退货件数，返回 {金额, 件数}，两者恒为非 null。
     *
     * 金额取 sales_return_apply.return_amount：tms_dispatch_detail.amount 在退货行恒为 0
     * （全库两处 INSERT 都不写该列），用它会让应交回金额虚高。
     *
     * 必须先子查询 DISTINCT source_bill_no 再 JOIN：dd 与 ra 是多对一，
     * 同一张退货申请被拆到多个调度明细行（改派、追加、返仓重排都会产生）时，
     * 直接 JOIN 后 SUM 会把整单金额按行数翻倍，应交回金额随之虚低。
     *
     * 金额与件数放在同一条 SQL 里算，避免两处过滤条件日后被改歪而口径分叉。
     */
    private BigDecimal[] loadReturnTotals(List<String> dispatchIds) {
        if (dispatchIds.isEmpty()) return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        String ph = String.join(",", Collections.nCopies(dispatchIds.size(), "?"));
        Map<String, Object> row = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT COALESCE(SUM(ra.return_amount), 0) AS return_amount,
                       COALESCE(SUM(COALESCE(ra.signed_qty, ra.return_qty, ra.qty)), 0) AS return_qty
                FROM (
                    SELECT DISTINCT dd.source_bill_no
                    FROM tms_dispatch_detail dd
                    WHERE dd.dispatch_id IN (""" + ph + """
                ) AND dd.bill_type = 'RETURN'
                  AND dd.status IN ('DELIVERED', 'PARTIAL')
                ) t
                JOIN sales_return_apply ra ON ra.apply_no = t.source_bill_no
                """, dispatchIds.toArray()).get(0);
        return new BigDecimal[]{
                TmsUtil.toBd(row.get("returnAmount")),
                TmsUtil.toBd(row.get("returnQty"))
        };
    }

    /**
     * 汇总门店结算单口径的收款数据（方案 A：交账以门店结算表为准）。
     *
     * 为什么不能只看 tms_sign_record：新的门店结算流程把钱记在
     * tms_store_settlement_account（支持一次结算拆多个资金账户），
     * 而 writeSignRecord 写签收记录时 collect_amount 恒为 0、pay_method 恒为空，
     * 只按签收记录聚合会让门店结算收的钱在交账页完全看不到。
     *
     * 现金判定用 base_fund_account.parent_code = '01'（'01' = 系统内置一级分类「现金」），
     * 不能用 account_type：该列在 V2 种子数据里全是空串，从来没有被赋过值。
     * 账户本身就是一级现金分类（FA_SYS_01）时 parent_code 为空，所以要额外兜一次。
     *
     * 按 driver_id + dispatch_id 精确过滤，不对 dispatch_id 为空的历史行做兜底：
     * 交账允许只结单张调度单，一旦兜底就会把别的调度单的钱算进来，
     * 而金额虚高会直接固化进 tms_settlement。
     *
     * 返回 key：cashAmount / onlineAmount / returnAmount / creditAmount / receivedAmount
     */
    private Map<String, BigDecimal> loadStoreSettleTotals(String driverId, List<String> dispatchIds) {
        Map<String, BigDecimal> r = new LinkedHashMap<>();
        for (String k : new String[]{"cashAmount", "onlineAmount", "returnAmount", "creditAmount", "receivedAmount"}) {
            r.put(k, BigDecimal.ZERO);
        }
        if (dispatchIds.isEmpty()) return r;

        String ph = String.join(",", Collections.nCopies(dispatchIds.size(), "?"));
        List<Object> args = new ArrayList<>(dispatchIds);
        args.add(driverId);

        // 账户明细是一对多，必须与主表金额分两条 SQL 算，否则主表金额会按账户行数翻倍
        Map<String, Object> acct = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT COALESCE(SUM(CASE WHEN f.parent_code = '01' OR sa.fund_account_id = 'FA_SYS_01'
                                         THEN sa.amount ELSE 0 END), 0) AS cash_amount,
                       COALESCE(SUM(CASE WHEN f.parent_code = '01' OR sa.fund_account_id = 'FA_SYS_01'
                                         THEN 0 ELSE sa.amount END), 0) AS online_amount
                FROM tms_store_settlement_account sa
                JOIN tms_store_settlement s ON s.settle_id = sa.settle_id
                LEFT JOIN base_fund_account f ON f.fund_account_id = sa.fund_account_id
                WHERE s.dispatch_id IN (""" + ph + """
                ) AND s.driver_id = ? AND s.settle_status = 'SETTLED'
                """, args.toArray()).get(0);
        r.put("cashAmount", TmsUtil.toBd(acct.get("cashAmount")));
        r.put("onlineAmount", TmsUtil.toBd(acct.get("onlineAmount")));

        Map<String, Object> main = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT COALESCE(SUM(return_amount), 0) AS return_amount,
                       COALESCE(SUM(credit_amount), 0) AS credit_amount,
                       COALESCE(SUM(received_amount), 0) AS received_amount
                FROM tms_store_settlement
                WHERE dispatch_id IN (""" + ph + """
                ) AND driver_id = ? AND settle_status = 'SETTLED'
                """, args.toArray()).get(0);
        r.put("returnAmount", TmsUtil.toBd(main.get("returnAmount")));
        r.put("creditAmount", TmsUtil.toBd(main.get("creditAmount")));
        r.put("receivedAmount", TmsUtil.toBd(main.get("receivedAmount")));
        return r;
    }

    /**
     * 给交账单所属司机发结果回执。
     *
     * 单独查 driver_id：audit/dispute 的业务 SQL 只关心状态流转，
     * 不该为了发消息去扩列。
     */
    private void notifySettleResult(String settlementId, String settlementNo, String level,
                                    String title, String content) {
        try {
            List<String> ids = jdbcTemplate.queryForList(
                    "SELECT driver_id FROM tms_settlement WHERE settlement_id = ?", String.class, settlementId);
            if (ids.isEmpty()) return;
            notifyService.notifyDriver(ids.get(0), TmsNotifyService.TYPE_SETTLE_RESULT,
                    level, title, content, "SETTLEMENT", settlementId, settlementNo);
        } catch (Exception ignore) {
            // 回执失败不影响交账单状态
        }
    }
}
