package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.tms.service.TmsAuthService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

/**
 * TMS 司机 APP 端接口。
 *
 * 接口：
 *   POST /tms/app/login          司机登录（返回 JWT）
 *   POST /tms/app/profile        司机信息 + 所属线路
 *   POST /tms/app/today-tasks    今日任务（含发货单 + 退货单取货任务）
 *   POST /tms/app/dispatch/detail  调度单详情（APP 用）
 *   POST /tms/app/return/detail  退货单明细（逐商品退货数量）
 *   POST /tms/app/return/sign    退货签收（回写 signed_qty + logistics_status=司机已回收）
 *
 * 鉴权：复用 JwtAuthFilter，登录时 subject=driverId，roleCode=DRIVER。
 */
@RestController
@RequestMapping("/tms/app")
public class TmsAppController {

    private final JdbcTemplate jdbcTemplate;
    private final TmsAuthService authService;

    public TmsAppController(JdbcTemplate jdbcTemplate, TmsAuthService authService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String mobile = TmsUtil.str(body.get("mobile"));
        String verifyCode = TmsUtil.str(body.get("verifyCode"));
        return ApiResponse.ok(authService.login(mobile, verifyCode));
    }

    @PostMapping("/profile")
    public ApiResponse<Map<String, Object>> profile() {
        return ApiResponse.ok(authService.getDriverInfo(TmsUtil.currentDriverId()));
    }

    /**
     * 今日任务：当前司机的调度单（dispatch_date=今日，状态在 ASSIGNED~DELIVERING），
     * 含明细（发货单 + 退货单取货任务）。
     */
    @PostMapping("/today-tasks")
    public ApiResponse<Map<String, Object>> todayTasks() {
        String driverId = TmsUtil.currentDriverId();
        List<Map<String, Object>> dispatches = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dispatch_id, dispatch_no, dispatch_date, route_line, vehicle_plate, status,
                       loaded_qty, return_qty, store_count
                FROM tms_dispatch
                WHERE driver_id = ? AND dispatch_date = CURRENT_DATE AND status IN ('ASSIGNED','LOADED','DEPARTED','DELIVERING')
                ORDER BY dispatch_no
                """, driverId);
        int totalStore = 0;
        BigDecimal totalQty = BigDecimal.ZERO;
        List<Map<String, Object>> allDetails = new ArrayList<>();
        for (Map<String, Object> d : dispatches) {
            String dispatchId = TmsUtil.str(d.get("dispatchId"));
            List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT detail_id, dispatch_id, bill_type, source_bill_no, customer_code, customer_name,
                           customer_address, qty, sku_count, seq_no, status
                    FROM tms_dispatch_detail
                    WHERE dispatch_id = ?
                    ORDER BY seq_no
                    """, dispatchId);
            for (Map<String, Object> r : details) {
                r.put("billTypeText", "RETURN".equals(TmsUtil.str(r.get("billType"))) ? "取退" : "发货");
                r.put("dispatchNo", d.get("dispatchNo"));
            }
            allDetails.addAll(details);
            totalStore += TmsUtil.toInt(d.get("storeCount"));
            totalQty = totalQty.add(TmsUtil.toBd(d.get("loadedQty")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatches", dispatches);
        result.put("details", allDetails);
        result.put("summary", Map.of(
                "dispatchCount", dispatches.size(),
                "totalStore", totalStore,
                "totalQty", totalQty,
                "returnTaskCount", allDetails.stream().filter(x -> "RETURN".equals(TmsUtil.str(x.get("billType")))).count()
        ));
        return ApiResponse.ok(result);
    }

    /** 调度单详情（APP 用，只返回当前司机可见的）。 */
    @PostMapping("/dispatch/detail")
    public ApiResponse<Map<String, Object>> dispatchDetail(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        List<Map<String, Object>> d = jdbcTemplate.queryForList(
                "SELECT * FROM tms_dispatch WHERE dispatch_id=? AND driver_id=?", dispatchId, TmsUtil.currentDriverId());
        if (d.isEmpty()) return ApiResponse.fail("404", "调度单不存在或非本人");
        Map<String, Object> head = TmsUtil.camelize(d.get(0));
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate,
                "SELECT * FROM tms_dispatch_detail WHERE dispatch_id=? ORDER BY seq_no", dispatchId);
        for (Map<String, Object> r : details) r.put("billTypeText", "RETURN".equals(TmsUtil.str(r.get("billType"))) ? "取退" : "发货");
        head.put("details", details);
        return ApiResponse.ok(head);
    }

    /** 退货单明细：逐商品退货数量，供 APP 录入实收。 */
    @PostMapping("/return/detail")
    public ApiResponse<Map<String, Object>> returnDetail(@RequestBody Map<String, Object> body) {
        String applyNo = TmsUtil.str(body.get("applyNo"));
        if (applyNo.isEmpty()) return ApiResponse.fail("400", "applyNo 不能为空");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT apply_id, apply_no, customer_code, customer_name, warehouse, bill_date, qty AS return_qty,
                       signed_qty, return_reason, return_type, logistics_status, driver_id, driver_name, dispatch_id
                FROM sales_return_apply WHERE apply_no = ?
                """, applyNo);
        if (heads.isEmpty()) return ApiResponse.fail("404", "退货单不存在：" + applyNo);
        Map<String, Object> head = TmsUtil.camelize(heads.get(0));
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT detail_id, goods_code, goods_name, spec, unit_name, qty AS return_qty, batch_no, production_date
                FROM sales_return_apply_detail WHERE apply_id = ?
                ORDER BY detail_id
                """, head.get("applyId"));
        head.put("details", details);
        return ApiResponse.ok(head);
    }

    /**
     * 退货签收（V1.2 闭环终点）：回写 signed_qty + logistics_status=司机已回收。
     * 入参：applyNo, items:[{detailId, goodsCode, signedQty}], customerSigner, remark
     * 前提：logistics_status=已调度 且 driver_id=当前司机
     */
    @PostMapping("/return/sign")
    @Transactional
    public ApiResponse<Map<String, Object>> returnSign(@RequestBody Map<String, Object> body) {
        String applyNo = TmsUtil.str(body.get("applyNo"));
        String customerSigner = TmsUtil.str(body.get("customerSigner"));
        String remark = TmsUtil.str(body.get("remark"));
        String signatureUrl = TmsUtil.str(body.get("signatureUrl"));
        if (applyNo.isEmpty()) return ApiResponse.fail("400", "退货单号不能为空");
        String driverId = TmsUtil.currentDriverId();

        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT apply_id, apply_no, customer_code, customer_name, qty, logistics_status, driver_id, dispatch_id, trip_id
                FROM sales_return_apply WHERE apply_no = ?
                """, applyNo);
        if (heads.isEmpty()) return ApiResponse.fail("404", "退货单不存在：" + applyNo);
        Map<String, Object> h = TmsUtil.camelize(heads.get(0));
        String logisticsStatus = TmsUtil.str(h.get("logisticsStatus"));
        if (!"已调度".equals(logisticsStatus)) {
            return ApiResponse.fail("400", "当前物流状态为「" + logisticsStatus + "」，仅「已调度」可签收");
        }
        String billDriverId = TmsUtil.str(h.get("driverId"));
        if (!billDriverId.isEmpty() && !billDriverId.equals(driverId)) {
            return ApiResponse.fail("400", "非本人退货单，不可签收");
        }
        String applyId = TmsUtil.str(h.get("applyId"));
        String customerCode = TmsUtil.str(h.get("customerCode"));
        String customerName = TmsUtil.str(h.get("customerName"));
        String dispatchId = TmsUtil.str(h.get("dispatchId"));
        String tripId = TmsUtil.str(h.get("tripId"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = body.get("items") == null ? List.of() : (List<Map<String, Object>>) body.get("items");
        BigDecimal totalSigned = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            totalSigned = totalSigned.add(TmsUtil.toBd(it.get("signedQty")));
        }
        // 若未传明细，默认全收（实收=退货数）
        if (items.isEmpty()) {
            totalSigned = TmsUtil.toBd(h.get("qty"));
        }

        // 1. 回写退货单：signed_qty + logistics_status=司机已回收
        jdbcTemplate.update("""
                UPDATE sales_return_apply
                SET signed_qty = ?, logistics_status = '司机已回收'
                WHERE apply_no = ?
                """, totalSigned, applyNo);

        // 2. 写签收记录
        String signId = TmsUtil.uuid("QS");
        jdbcTemplate.update("""
                INSERT INTO tms_sign_record(sign_id, dispatch_id, detail_id, trip_id, source_bill_no,
                    customer_code, customer_name, bill_type, sign_type, signed_qty, sign_time, sign_user, customer_signer, customer_sign_img, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RETURN', ?, ?, ?, ?, ?, ?, ?, ?)
                """, signId, dispatchId.isEmpty() ? null : dispatchId, null, tripId.isEmpty() ? null : tripId,
                applyNo, customerCode, customerName,
                totalSigned.compareTo(TmsUtil.toBd(h.get("qty"))) < 0 ? "PARTIAL" : "NORMAL",
                totalSigned, Timestamp.valueOf(TmsUtil.now()), TmsUtil.currentUser(), customerSigner,
                signatureUrl.isEmpty() ? null : signatureUrl, remark);

        // 2.1 保存退货照片（URL 列表）
        @SuppressWarnings("unchecked")
        List<String> photoUrls = body.get("photos") instanceof List<?> l
                ? (List<String>) l : List.of();
        for (String url : photoUrls) {
            if (url == null || url.isEmpty()) continue;
            String photoId = TmsUtil.uuid("SP");
            jdbcTemplate.update("""
                    INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, 'GOODS', ?, ?)
                    """, photoId, signId, url, "return-sign/" + signId + "/" + photoId);
        }

        // 3. 更新调度明细行状态
        if (!dispatchId.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE tms_dispatch_detail SET status='DELIVERED', sign_time=?, sign_user=?
                    WHERE dispatch_id=? AND bill_type='RETURN' AND source_bill_no=?
                    """, Timestamp.valueOf(TmsUtil.now()), TmsUtil.currentUser(), dispatchId, applyNo);
        }

        TmsUtil.log(jdbcTemplate, "tms.app.return", "SIGN", applyNo,
                "司机退货签收：实收 " + totalSigned + " 件，物流状态 已调度 → 司机已回收");
        return ApiResponse.ok(Map.of(
                "applyNo", applyNo,
                "logisticsStatus", "司机已回收",
                "signedQty", totalSigned,
                "returnQty", h.get("qty"),
                "diff", TmsUtil.toBd(h.get("qty")).subtract(totalSigned)
        ));
    }
}
