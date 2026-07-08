package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.util.BillNoGenerator;
import com.erp.sales.SalesReturnController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/**
 * 司机现场退货回收（P3）。
 *
 * 闭环：
 *   1. ERP 预开 sales_return_apply(return_type=DRIVER) → 安排调度 → 指派司机 → APP 退货签收（已有 /return/sign）
 *   2. 司机现场主动退货（无预开单）→ APP /return/create 生成 sales_return_apply(DRIVER, 司机已回收) + tms_driver_return
 *   3. 司机返仓 → APP /warehouse-return/confirm → 调用 SalesReturnController.generateInboundFromApply 生成入库单 + tms_driver_return.status=WAREHOUSED
 *   4. 仓库在 ERP 审核入库单 → 库存增加 + 冲减应收（复用现有流程）
 *
 * APP 接口：
 *   POST /tms/app/return/list                  司机回收任务列表（待回收 + 已回收待返仓）
 *   POST /tms/app/return/create                司机现场创建退货（无预开单）
 *   POST /tms/app/return/upload-photo          上传退货照片（base64）
 *   POST /tms/app/return/goods-search          商品模糊搜索（现场录入用）
 *   POST /tms/app/warehouse-return/list        本趟待返仓退货清单
 *   POST /tms/app/warehouse-return/confirm     返仓交接确认（生成入库单）
 *
 * ERP 接口：
 *   POST /tms/driver-return/page               司机退货单列表
 *   GET  /tms/driver-return/{id}               司机退货单详情
 */
@RestController
public class TmsReturnController {

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;
    private final SalesReturnController salesReturnController;

    public TmsReturnController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen,
                               SalesReturnController salesReturnController) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
        this.salesReturnController = salesReturnController;
    }

    // ========================================================================
    // APP 端接口
    // ========================================================================

    /**
     * 司机回收任务列表。
     * 包含两类：
     *   A. 待回收：logistics_status=已调度 且 driver_id=当前司机（预开退货单，到店需签收）
     *   B. 已回收待返仓：tms_driver_return.driver_id=当前司机 且 status=PENDING（本趟已回收，待返仓交接）
     */
    @PostMapping("/tms/app/return/list")
    public ApiResponse<Map<String, Object>> returnList(@RequestBody(required = false) Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();

        // A. 待回收（预开退货单）
        List<Map<String, Object>> pending = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT apply_id, apply_no, customer_code, customer_name, warehouse, bill_date,
                       qty AS return_qty, signed_qty, return_reason, logistics_status,
                       dispatch_id, trip_id, driver_name
                FROM sales_return_apply
                WHERE return_type = 'DRIVER' AND logistics_status = '已调度' AND driver_id = ?
                ORDER BY bill_date
                """, driverId);
        for (Map<String, Object> r : pending) r.put("taskType", "PENDING_RECYCLE");

        // B. 已回收待返仓
        List<Map<String, Object>> loaded = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dr.driver_return_id, dr.driver_return_no, dr.return_apply_no, dr.trip_id, dr.dispatch_id,
                       dr.customer_code, dr.customer_name, dr.return_date, dr.qty, dr.status, dr.remark,
                       sa.return_reason
                FROM tms_driver_return dr
                LEFT JOIN sales_return_apply sa ON sa.apply_no = dr.return_apply_no
                WHERE dr.driver_id = ? AND dr.status = 'PENDING'
                ORDER BY dr.return_date DESC, dr.create_time DESC
                """, driverId);
        for (Map<String, Object> r : loaded) r.put("taskType", "LOADED_RETURN");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingRecycle", pending);
        result.put("loadedReturn", loaded);
        result.put("summary", Map.of(
                "pendingCount", pending.size(),
                "loadedCount", loaded.size(),
                "loadedQty", loaded.stream().map(x -> TmsUtil.toBd(x.get("qty"))).reduce(BigDecimal.ZERO, BigDecimal::add)
        ));
        return ApiResponse.ok(result);
    }

    /**
     * 司机现场创建退货（无预开单场景）。
     * 入参：customerCode, customerName, warehouse, returnReason, remark,
     *       items:[{goodsCode, goodsName, spec, unitName, qty, price, batchNo}]
     *       dispatchId?, tripId?
     * 处理：
     *   1. 生成 sales_return_apply(return_type=DRIVER, logistics_status=司机已回收, signed_qty=qty, status=PENDING)
     *   2. 生成 tms_driver_return(status=PENDING, return_apply_no=apply_no)
     *   3. 回写调度明细状态（如有 dispatch_id）
     */
    @PostMapping("/tms/app/return/create")
    @Transactional
    public ApiResponse<Map<String, Object>> createReturn(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        // 查询司机姓名
        String driverName = TmsUtil.currentUser();
        if (!driverId.isEmpty()) {
            List<Map<String, Object>> empRows = jdbcTemplate.queryForList(
                    "SELECT employee_name FROM base_employee WHERE employee_id = ?", driverId);
            if (!empRows.isEmpty()) driverName = TmsUtil.str(empRows.get(0).get("employee_name"));
        }
        String customerCode = TmsUtil.str(body.get("customerCode"));
        String customerName = TmsUtil.str(body.get("customerName"));
        String warehouse = TmsUtil.str(body.get("warehouse"));
        String returnReason = TmsUtil.str(body.get("returnReason"));
        String remark = TmsUtil.str(body.get("remark"));
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String tripId = TmsUtil.str(body.get("tripId"));
        if (customerCode.isEmpty() || customerName.isEmpty()) {
            return ApiResponse.fail("400", "客户不能为空");
        }
        if (warehouse.isEmpty()) {
            return ApiResponse.fail("400", "仓库不能为空");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = body.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        if (items.isEmpty()) return ApiResponse.fail("400", "退货明细不能为空");

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            BigDecimal q = TmsUtil.toBd(it.get("qty"));
            BigDecimal p = TmsUtil.toBd(it.get("price"));
            BigDecimal a = q.multiply(p).setScale(2, BigDecimal.ROUND_HALF_UP);
            it.put("_amount", a);
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(a);
        }

        // 1. 生成 sales_return_apply
        String applyId = "SRA" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String applyNo = billNoGen.nextNo(
                BillNoGenerator.BillType.SALES_RETURN_REQ, "sales_return_apply", "apply_no");
        jdbcTemplate.update("""
                INSERT INTO sales_return_apply(apply_id, apply_no,
                    customer_code, customer_name, warehouse, bill_date,
                    qty, return_qty, amount, return_reason, status,
                    return_type, logistics_status, signed_qty, driver_id, driver_name,
                    dispatch_id, trip_id, creator_name, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING',
                    'DRIVER', '司机已回收', ?, ?, ?, ?, ?, ?, ?)
                """, applyId, applyNo,
                customerCode, customerName, warehouse, LocalDate.now(),
                totalQty, totalQty, totalAmount, returnReason,
                totalQty, driverId, driverName,
                dispatchId.isEmpty() ? null : dispatchId,
                tripId.isEmpty() ? null : tripId,
                driverName, remark);

        // 写退货申请明细
        for (Map<String, Object> it : items) {
            String detailId = "SRAD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO sales_return_apply_detail(detail_id, apply_id, return_mode,
                        goods_code, goods_name, spec, unit_name, qty, price, amount, batch_no, remark)
                    VALUES (?, ?, 'BY_GOODS', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, detailId, applyId,
                    TmsUtil.str(it.get("goodsCode")), TmsUtil.str(it.get("goodsName")),
                    TmsUtil.str(it.get("spec")), TmsUtil.str(it.get("unitName")),
                    TmsUtil.toBd(it.get("qty")), TmsUtil.toBd(it.get("price")),
                    it.get("_amount"), TmsUtil.str(it.get("batchNo")), TmsUtil.str(it.get("remark")));
        }

        // 2. 生成 tms_driver_return
        String driverReturnId = "DR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String driverReturnNo = applyNo; // 复用退货申请号
        jdbcTemplate.update("""
                INSERT INTO tms_driver_return(driver_return_id, driver_return_no, return_apply_no,
                    trip_id, dispatch_id, driver_id, driver_name,
                    customer_code, customer_name, return_date, qty, status, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, driverReturnId, driverReturnNo, applyNo,
                tripId.isEmpty() ? null : tripId,
                dispatchId.isEmpty() ? null : dispatchId,
                driverId, driverName,
                customerCode, customerName, LocalDate.now(), totalQty, remark);

        // 写司机退货明细（冗余，便于返仓清点）
        for (Map<String, Object> it : items) {
            String dId = "DRD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO tms_driver_return_detail(detail_id, driver_return_id,
                        goods_code, goods_name, spec, unit_name, qty, batch_no, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, dId, driverReturnId,
                    TmsUtil.str(it.get("goodsCode")), TmsUtil.str(it.get("goodsName")),
                    TmsUtil.str(it.get("spec")), TmsUtil.str(it.get("unitName")),
                    TmsUtil.toBd(it.get("qty")), TmsUtil.str(it.get("batchNo")), TmsUtil.str(it.get("remark")));
        }

        // 3. 回写调度明细状态（如有）
        if (!dispatchId.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE tms_dispatch_detail SET status='DELIVERED', sign_time=?, sign_user=?
                    WHERE dispatch_id=? AND bill_type='RETURN' AND source_bill_no=?
                    """, Timestamp.valueOf(TmsUtil.now()), TmsUtil.currentUser(), dispatchId, applyNo);
        }

        TmsUtil.log(jdbcTemplate, "tms.app.return", "CREATE", applyNo,
                "司机现场创建退货：" + customerName + " " + totalQty + " 件，物流状态=司机已回收");
        return ApiResponse.ok(Map.of(
                "applyNo", applyNo,
                "driverReturnId", driverReturnId,
                "driverReturnNo", driverReturnNo,
                "logisticsStatus", "司机已回收",
                "signedQty", totalQty,
                "qty", totalQty
        ));
    }

    /**
     * 上传退货照片（URL 数组，APP 端先调 /tms/app/upload/image 上传获得 URL）。
     * 入参：driverReturnId, photos:[{url, photoType}]
     *       photoType: GOODS(货物)/SCENE(门店场景)/DEFECT(破损特写)
     */
    @PostMapping("/tms/app/return/upload-photo")
    @Transactional
    public ApiResponse<Map<String, Object>> uploadPhoto(@RequestBody Map<String, Object> body) {
        String driverReturnId = TmsUtil.str(body.get("driverReturnId"));
        if (driverReturnId.isEmpty()) return ApiResponse.fail("400", "driverReturnId 不能为空");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> photos = body.get("photos") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        int saved = 0;
        for (Map<String, Object> p : photos) {
            String url = TmsUtil.str(p.get("url"));
            String photoType = TmsUtil.str(p.getOrDefault("photoType", "GOODS"));
            if (url.isEmpty()) continue;
            String photoId = "SP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, ?, ?, ?)
                    """, photoId, driverReturnId, photoType, url, "driver-return/" + driverReturnId + "/" + photoId);
            saved++;
        }
        TmsUtil.log(jdbcTemplate, "tms.app.return", "PHOTO", driverReturnId, "上传退货照片 " + saved + " 张");
        return ApiResponse.ok(Map.of("driverReturnId", driverReturnId, "saved", saved));
    }

    /**
     * 商品模糊搜索（司机现场录入退货商品用）。
     * 入参：keyword, warehouse?
     * 返回：[{goodsCode, goodsName, spec, unitName, price, stockQty}]
     */
    @PostMapping("/tms/app/return/goods-search")
    public ApiResponse<List<Map<String, Object>>> goodsSearch(@RequestBody Map<String, Object> body) {
        String keyword = TmsUtil.str(body.get("keyword"));
        String warehouse = TmsUtil.str(body.get("warehouse"));
        if (keyword.length() < 1) return ApiResponse.ok(List.of());

        // LEFT JOIN 的 ON 条件包含 warehouse 过滤，保证无库存商品也能返回（stock_qty=0）
        String joinCondition = warehouse.isEmpty()
                ? "ON s.goods_code = g.goods_code"
                : "ON s.goods_code = g.goods_code AND s.warehouse = ?";
        String sql = """
                SELECT g.goods_code, g.goods_name, g.spec, g.base_unit AS unit_name,
                       g.suggested_retail_price AS price,
                       COALESCE(s.physical_qty, 0) AS stock_qty
                FROM base_goods g
                LEFT JOIN inv_stock_balance s """ + joinCondition + """
                WHERE (g.goods_code LIKE ? OR g.goods_name LIKE ? OR g.barcode LIKE ?)
                ORDER BY g.goods_name LIMIT 30
                """;
        List<Object> args = new ArrayList<>();
        if (!warehouse.isEmpty()) args.add(warehouse);
        String like = "%" + keyword + "%";
        args.add(like);
        args.add(like);
        args.add(like);

        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql, args.toArray());
        return ApiResponse.ok(rows);
    }

    /**
     * 本趟待返仓退货清单。
     * 返回当前司机所有 status=PENDING 的 tms_driver_return（含明细）。
     */
    @PostMapping("/tms/app/warehouse-return/list")
    public ApiResponse<Map<String, Object>> warehouseReturnList(@RequestBody(required = false) Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        List<Map<String, Object>> heads = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dr.driver_return_id, dr.driver_return_no, dr.return_apply_no, dr.trip_id, dr.dispatch_id,
                       dr.customer_code, dr.customer_name, dr.return_date, dr.qty, dr.status, dr.remark,
                       sa.return_reason, sa.warehouse
                FROM tms_driver_return dr
                LEFT JOIN sales_return_apply sa ON sa.apply_no = dr.return_apply_no
                WHERE dr.driver_id = ? AND dr.status = 'PENDING'
                ORDER BY dr.return_date DESC, dr.create_time DESC
                """, driverId);

        BigDecimal totalQty = BigDecimal.ZERO;
        for (Map<String, Object> h : heads) {
            String drId = TmsUtil.str(h.get("driverReturnId"));
            List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT detail_id, goods_code, goods_name, spec, unit_name, qty, batch_no, remark
                    FROM tms_driver_return_detail
                    WHERE driver_return_id = ?
                    ORDER BY detail_id
                    """, drId);
            h.put("details", details);
            totalQty = totalQty.add(TmsUtil.toBd(h.get("qty")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", heads);
        result.put("count", heads.size());
        result.put("totalQty", totalQty);
        return ApiResponse.ok(result);
    }

    /**
     * 返仓交接确认。
     * 入参：driverReturnIds:[...]  或  driverReturnId（单条）
     * 处理：
     *   1. 校验所有退货单属于当前司机且 status=PENDING
     *   2. 逐条调用 SalesReturnController.generateInboundFromApply 生成入库单
     *   3. 更新 tms_driver_return.status=WAREHOUSED
     * 返回：{confirmed, inboundNos:[...]}
     */
    @PostMapping("/tms/app/warehouse-return/confirm")
    @Transactional
    public ApiResponse<Map<String, Object>> warehouseReturnConfirm(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        List<String> ids = new ArrayList<>();
        Object single = body.get("driverReturnId");
        if (single != null) ids.add(TmsUtil.str(single));
        Object multi = body.get("driverReturnIds");
        if (multi instanceof List<?> l) {
            for (Object o : l) ids.add(TmsUtil.str(o));
        }
        if (ids.isEmpty()) return ApiResponse.fail("400", "请选择要交接的退货单");

        int confirmed = 0;
        List<String> inboundNos = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String drId : ids) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT driver_return_id, return_apply_no, driver_id, status FROM tms_driver_return WHERE driver_return_id=?",
                    drId);
            if (rows.isEmpty()) {
                failed.add(drId + ":不存在");
                continue;
            }
            Map<String, Object> r = rows.get(0);
            if (!driverId.equals(TmsUtil.str(r.get("driver_id")))) {
                failed.add(drId + ":非本人退货单");
                continue;
            }
            if (!"PENDING".equals(TmsUtil.str(r.get("status")))) {
                failed.add(drId + ":状态为" + r.get("status") + "，不可交接");
                continue;
            }
            String applyNo = TmsUtil.str(r.get("return_apply_no"));
            // 查 applyId
            List<Map<String, Object>> applyRows = jdbcTemplate.queryForList(
                    "SELECT apply_id FROM sales_return_apply WHERE apply_no=?", applyNo);
            if (applyRows.isEmpty()) {
                failed.add(drId + ":退货申请不存在");
                continue;
            }
            String applyId = TmsUtil.str(applyRows.get(0).get("apply_id"));
            // 调用现有入库单生成逻辑
            String inboundNo = salesReturnController.generateInboundFromApply(applyId);
            inboundNos.add(inboundNo);
            // 更新司机退货单状态
            jdbcTemplate.update("UPDATE tms_driver_return SET status='WAREHOUSED' WHERE driver_return_id=?", drId);
            TmsUtil.log(jdbcTemplate, "tms.app.warehouse-return", "CONFIRM", drId,
                    "返仓交接确认：" + applyNo + " → 入库单 " + inboundNo);
            confirmed++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmed", confirmed);
        result.put("inboundNos", inboundNos);
        if (!failed.isEmpty()) result.put("failed", failed);
        return ApiResponse.ok(result);
    }

    // ========================================================================
    // ERP 端接口
    // ========================================================================

    /** 司机退货单列表（ERP 端管理页）。 */
    @PostMapping("/tms/driver-return/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT dr.driver_return_id, dr.driver_return_no, dr.return_apply_no,
                       dr.trip_id, dr.dispatch_id, dr.driver_id, dr.driver_name,
                       dr.customer_code, dr.customer_name, dr.return_date, dr.qty, dr.status,
                       dr.create_time, dr.remark,
                       sa.return_reason, sa.warehouse, sa.signed_qty,
                       d.dispatch_no, t.trip_no
                FROM tms_driver_return dr
                LEFT JOIN sales_return_apply sa ON sa.apply_no = dr.return_apply_no
                LEFT JOIN tms_dispatch d ON d.dispatch_id = dr.dispatch_id
                LEFT JOIN tms_delivery_trip t ON t.trip_id = dr.trip_id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String status = TmsUtil.str(filters.get("status"));
        if (!status.isEmpty()) {
            sql.append(" AND dr.status = ?");
            args.add(status);
        }
        String driverName = TmsUtil.str(filters.get("driverName"));
        if (!driverName.isEmpty()) {
            sql.append(" AND dr.driver_name LIKE ?");
            args.add("%" + driverName + "%");
        }
        String customerName = TmsUtil.str(filters.get("customerName"));
        if (!customerName.isEmpty()) {
            sql.append(" AND dr.customer_name LIKE ?");
            args.add("%" + customerName + "%");
        }
        String returnNo = TmsUtil.str(filters.get("driverReturnNo"));
        if (!returnNo.isEmpty()) {
            sql.append(" AND (dr.driver_return_no LIKE ? OR dr.return_apply_no LIKE ?)");
            args.add("%" + returnNo + "%");
            args.add("%" + returnNo + "%");
        }
        String startDate = TmsUtil.str(filters.get("startDate"));
        if (!startDate.isEmpty()) {
            sql.append(" AND dr.return_date >= ?");
            args.add(startDate);
        }
        String endDate = TmsUtil.str(filters.get("endDate"));
        if (!endDate.isEmpty()) {
            sql.append(" AND dr.return_date <= ?");
            args.add(endDate);
        }
        sql.append(" ORDER BY dr.return_date DESC, dr.create_time DESC");

        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 司机退货单详情。 */
    @GetMapping("/tms/driver-return/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT dr.*, sa.return_reason, sa.warehouse, sa.signed_qty, sa.return_type, sa.logistics_status,
                       d.dispatch_no, t.trip_no
                FROM tms_driver_return dr
                LEFT JOIN sales_return_apply sa ON sa.apply_no = dr.return_apply_no
                LEFT JOIN tms_dispatch d ON d.dispatch_id = dr.dispatch_id
                LEFT JOIN tms_delivery_trip t ON t.trip_id = dr.trip_id
                WHERE dr.driver_return_id = ? OR dr.driver_return_no = ?
                """, id, id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "司机退货单不存在：" + id);
        Map<String, Object> head = TmsUtil.camelize(heads.get(0));

        String drId = TmsUtil.str(head.get("driverReturnId"));
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT detail_id, goods_code, goods_name, spec, unit_name, qty, batch_no, remark
                FROM tms_driver_return_detail
                WHERE driver_return_id = ?
                ORDER BY detail_id
                """, drId);
        head.put("details", details);

        // 照片
        List<Map<String, Object>> photos = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT photo_id, photo_type, photo_url, create_time
                FROM tms_sign_photo
                WHERE sign_id = ?
                ORDER BY create_time
                """, drId);
        head.put("photos", photos);

        // 关联入库单
        String applyNo = TmsUtil.str(head.get("returnApplyNo"));
        if (!applyNo.isEmpty()) {
            List<Map<String, Object>> inbounds = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT inbound_id, inbound_no, source_apply_no, warehouse, qty, amount, status, bill_date
                    FROM sales_return_inbound
                    WHERE source_apply_no = ?
                    ORDER BY bill_date DESC
                    """, applyNo);
            head.put("inbounds", inbounds);
        }
        return ApiResponse.ok(head);
    }
}
