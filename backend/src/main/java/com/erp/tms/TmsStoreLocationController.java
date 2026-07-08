package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

/**
 * 门店定位修正管理（P4-1）。
 *
 * 业务流程：
 *   司机到达门店发现定位不准 → APP 获取当前 GPS + 拍门头照 → 提交修正申请
 *   → ERP 审核通过 → 更新 base_customer.longitude/latitude + address_geo_source='DRIVER'
 *
 * APP 接口：
 *   POST /tms/app/store-location/submit   提交门店定位修正申请
 *
 * ERP 接口：
 *   POST /tms/store-location/page         修正申请列表
 *   GET  /tms/store-location/{id}         修正申请详情
 *   POST /tms/store-location/{id}/approve 批准修正（更新客户定位）
 *   POST /tms/store-location/{id}/reject  驳回修正
 */
@RestController
public class TmsStoreLocationController {

    private final JdbcTemplate jdbcTemplate;

    public TmsStoreLocationController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ========================================================================
    // APP 端接口
    // ========================================================================

    /**
     * 提交门店定位修正申请。
     * 入参：customerId, customerCode?, customerName?, newLat, newLng, storePhoto(base64)?, dispatchId?, remark?
     */
    @PostMapping("/tms/app/store-location/submit")
    @Transactional
    public ApiResponse<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        String customerId = TmsUtil.str(body.get("customerId"));
        String customerCode = TmsUtil.str(body.get("customerCode"));
        BigDecimal newLat = TmsUtil.toBd(body.get("newLat"));
        BigDecimal newLng = TmsUtil.toBd(body.get("newLng"));
        String storePhotoUrl = TmsUtil.str(body.get("storePhotoUrl"));
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String remark = TmsUtil.str(body.get("remark"));

        if (customerId.isEmpty() && customerCode.isEmpty()) {
            return ApiResponse.fail("400", "customerId/customerCode 不能同时为空");
        }
        if (newLat.compareTo(BigDecimal.ZERO) == 0 || newLng.compareTo(BigDecimal.ZERO) == 0) {
            return ApiResponse.fail("400", "新定位坐标不能为空");
        }

        // 查客户原定位信息（优先 customerId，其次用 customerCode 查找）
        List<Map<String, Object>> custRows;
        if (!customerId.isEmpty()) {
            custRows = jdbcTemplate.queryForList(
                    "SELECT customer_id, customer_code, customer_name, longitude, latitude FROM base_customer WHERE customer_id=?",
                    customerId);
        } else {
            custRows = jdbcTemplate.queryForList(
                    "SELECT customer_id, customer_code, customer_name, longitude, latitude FROM base_customer WHERE customer_code=?",
                    customerCode);
        }
        if (custRows.isEmpty()) {
            return ApiResponse.fail("404", "客户不存在：" + (customerId.isEmpty() ? customerCode : customerId));
        }
        Map<String, Object> c = custRows.get(0);
        customerId = TmsUtil.str(c.get("customer_id"));
        customerCode = TmsUtil.str(c.get("customer_code"));
        String customerName = TmsUtil.str(c.get("customer_name"));
        BigDecimal oldLat = TmsUtil.toBd(c.get("latitude"));
        BigDecimal oldLng = TmsUtil.toBd(c.get("longitude"));

        // 查司机姓名
        String driverName = "";
        List<Map<String, Object>> driverRows = jdbcTemplate.queryForList(
                "SELECT employee_name FROM base_employee WHERE employee_id=?", driverId);
        if (!driverRows.isEmpty()) {
            driverName = TmsUtil.str(driverRows.get(0).get("employee_name"));
        }

        String logId = "SLL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        // 门头照 URL 直接入库（APP 端先调 /tms/app/upload/image 上传获得）
        jdbcTemplate.update("""
                INSERT INTO tms_store_location_log(log_id, customer_id, customer_code, customer_name,
                    old_lat, old_lng, new_lat, new_lng, store_photo_url, driver_id, driver_name,
                    dispatch_id, source, status, review_remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRIVER', 'PENDING', ?)
                """, logId, customerId, customerCode, customerName,
                oldLat.compareTo(BigDecimal.ZERO) == 0 ? null : oldLat,
                oldLng.compareTo(BigDecimal.ZERO) == 0 ? null : oldLng,
                newLat, newLng, storePhotoUrl, driverId, driverName, dispatchId, remark);

        TmsUtil.log(jdbcTemplate, "tms.app.store-location", "SUBMIT", logId,
                "门店定位修正申请：" + customerName + "（" + oldLat + "," + oldLng + " → " + newLat + "," + newLng + "）");

        return ApiResponse.ok(Map.of(
                "logId", logId,
                "status", "PENDING",
                "customerName", customerName
        ));
    }

    // ========================================================================
    // ERP 端接口
    // ========================================================================

    /** 修正申请列表。 */
    @PostMapping("/tms/store-location/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT log_id, customer_id, customer_code, customer_name,
                       old_lat, old_lng, new_lat, new_lng,
                       driver_id, driver_name, dispatch_id, source,
                       status, reviewer_id, reviewer_name, review_remark,
                       created_at, reviewed_at
                FROM tms_store_location_log
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String status = TmsUtil.str(filters.get("status"));
        if (!status.isEmpty()) { sql.append(" AND status = ?"); args.add(status); }
        String customerName = TmsUtil.str(filters.get("customerName"));
        if (!customerName.isEmpty()) { sql.append(" AND customer_name LIKE ?"); args.add("%" + customerName + "%"); }
        String driverName = TmsUtil.str(filters.get("driverName"));
        if (!driverName.isEmpty()) { sql.append(" AND driver_name LIKE ?"); args.add("%" + driverName + "%"); }
        sql.append(" ORDER BY created_at DESC");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 修正申请详情（含门头照）。 */
    @GetMapping("/tms/store-location/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM tms_store_location_log WHERE log_id=?", id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "修正申请不存在：" + id);
        Map<String, Object> head = TmsUtil.camelize(rows.get(0));
        return ApiResponse.ok(head);
    }

    /**
     * 批准修正（更新客户定位）。
     * 处理：
     *   1. 更新 base_customer.longitude/latitude/address_geo_source='DRIVER'/address_geo_updated_at
     *   2. 更新 tms_store_location_log.status='APPROVED'
     */
    @PostMapping("/tms/store-location/{id}/approve")
    @Transactional
    public ApiResponse<Map<String, Object>> approve(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT log_id, customer_id, customer_name, new_lat, new_lng, status FROM tms_store_location_log WHERE log_id=?", id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "修正申请不存在");
        Map<String, Object> r = rows.get(0);
        if ("APPROVED".equals(TmsUtil.str(r.get("status")))) {
            return ApiResponse.fail("400", "该申请已批准，不可重复操作");
        }
        String customerId = TmsUtil.str(r.get("customer_id"));
        String customerName = TmsUtil.str(r.get("customer_name"));
        BigDecimal newLat = TmsUtil.toBd(r.get("new_lat"));
        BigDecimal newLng = TmsUtil.toBd(r.get("new_lng"));
        String reviewRemark = body != null ? TmsUtil.str(body.get("reviewRemark")) : "";

        // 更新客户定位
        jdbcTemplate.update("""
                UPDATE base_customer
                SET latitude=?, longitude=?, address_geo_source='DRIVER', address_geo_updated_at=?
                WHERE customer_id=?
                """, newLat, newLng, Timestamp.valueOf(TmsUtil.now()), customerId);

        // 更新申请状态
        jdbcTemplate.update("""
                UPDATE tms_store_location_log
                SET status='APPROVED', reviewer_id=?, reviewer_name=?, review_remark=?, reviewed_at=?
                WHERE log_id=?
                """, TmsUtil.currentUser(), TmsUtil.currentUser(), reviewRemark,
                Timestamp.valueOf(TmsUtil.now()), id);

        TmsUtil.log(jdbcTemplate, "tms.store-location", "APPROVE", id,
                "批准门店定位修正：" + customerName + " → (" + newLat + "," + newLng + ")");

        return ApiResponse.ok(Map.of("logId", id, "status", "APPROVED", "customerId", customerId));
    }

    /**
     * 驳回修正。
     * 入参：reviewRemark（驳回原因）
     */
    @PostMapping("/tms/store-location/{id}/reject")
    @Transactional
    public ApiResponse<Map<String, Object>> reject(@PathVariable String id, @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT log_id, status FROM tms_store_location_log WHERE log_id=?", id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "修正申请不存在");
        Map<String, Object> r = rows.get(0);
        if ("APPROVED".equals(TmsUtil.str(r.get("status")))) {
            return ApiResponse.fail("400", "该申请已批准，不可驳回");
        }
        String reviewRemark = TmsUtil.str(body.get("reviewRemark"));
        if (reviewRemark.isEmpty()) reviewRemark = "定位修正不合适，已驳回";

        jdbcTemplate.update("""
                UPDATE tms_store_location_log
                SET status='REJECTED', reviewer_id=?, reviewer_name=?, review_remark=?, reviewed_at=?
                WHERE log_id=?
                """, TmsUtil.currentUser(), TmsUtil.currentUser(), reviewRemark,
                Timestamp.valueOf(TmsUtil.now()), id);

        TmsUtil.log(jdbcTemplate, "tms.store-location", "REJECT", id,
                "驳回门店定位修正：" + reviewRemark);

        return ApiResponse.ok(Map.of("logId", id, "status", "REJECTED"));
    }
}
