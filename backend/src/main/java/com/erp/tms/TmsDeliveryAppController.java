package com.erp.tms;

import com.erp.common.api.ApiResponse;
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
 * TMS 司机 APP 配送核心接口（P2）。
 *
 * 状态机：
 *   调度单 ASSIGNED ─[装车]──> LOADED ─[发车]──> DEPARTED ─[首店签收]──> DELIVERING ─[全部签收]──> COMPLETED
 *   行程   PLANNED  ─[装车]──> LOADED ─[发车]──> DEPARTED ─[首店签收]──> DELIVERING ─[全部签收]──> COMPLETED
 *   发货单 dispatch_status：UNDISPATCHED → DISPATCHED → LOADED → DEPARTED → DELIVERING → COMPLETED
 *
 * 接口：
 *   POST /tms/app/loading/items        装车 SKU 明细（按发货单分组，含已装数量）
 *   POST /tms/app/loading/start        开始装车（ASSIGNED → LOADED）
 *   POST /tms/app/loading/scan         装车扫码核对（写 tms_loading_check）
 *   POST /tms/app/loading/confirm      确认装车完成
 *   POST /tms/app/depart               确认发车（LOADED → DEPARTED）
 *   POST /tms/app/arrive               到达门店打卡（GPS 围栏校验）
 *   POST /tms/app/arrive/config        到达打卡参数配置（围栏阈值 / 是否强制 / 是否必拍照）
 *   POST /tms/app/sign/items           签收 SKU 明细（按调度明细）
 *   POST /tms/app/sign                 门店签收（发货单：全部/部分/拒收）
 *   POST /tms/app/sign/upload-photo    上传签收照片（base64，MinIO 接入后改 URL）
 *   POST /tms/app/location/report      GPS 单点上报
 *   POST /tms/app/location/batch-report GPS 批量上报（离线补传）
 */
@RestController
@RequestMapping("/tms/app")
public class TmsDeliveryAppController {

    private final JdbcTemplate jdbcTemplate;

    public TmsDeliveryAppController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 装车 ====================

    /**
     * 装车 SKU 明细：按调度单拉取所有发货单的逐商品明细（含已装车数量回写）。
     * 返回结构：
     *   dispatchId, dispatchNo, status, vehiclePlate, routeLine, loadedQty, returnQty, storeCount,
     *   receipts:[{ detailId, sourceBillNo, customerCode, customerName, customerAddress, seqNo,
     *               requiredQty, loadedQty, items:[{ goodsCode, goodsName, unitName, requiredQty, loadedQty }] }]
     */
    @PostMapping("/loading/items")
    public ApiResponse<Map<String, Object>> loadingItems(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        Map<String, Object> dispatch = loadDispatch(dispatchId, TmsUtil.currentDriverId());

        // 拉取发货单调度明细
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT detail_id, source_bill_no, customer_code, customer_name, customer_address, qty, seq_no, status
                FROM tms_dispatch_detail
                WHERE dispatch_id=? AND bill_type='RECEIPT'
                ORDER BY seq_no
                """, dispatchId);

        // 已装车数量按 (detail_id, goods_code) 聚合
        Map<String, BigDecimal> loadedMap = new HashMap<>();
        List<Map<String, Object>> checks = jdbcTemplate.queryForList(
                "SELECT detail_id, goods_code, SUM(loaded_qty) AS loaded_qty FROM tms_loading_check WHERE dispatch_id=? GROUP BY detail_id, goods_code",
                dispatchId);
        for (Map<String, Object> c : checks) {
            String key = TmsUtil.str(c.get("detail_id")) + "|" + TmsUtil.str(c.get("goods_code"));
            loadedMap.put(key, TmsUtil.toBd(c.get("loaded_qty")));
        }

        List<Map<String, Object>> receipts = new ArrayList<>();
        BigDecimal totalRequired = BigDecimal.ZERO;
        BigDecimal totalLoaded = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            String detailId = TmsUtil.str(d.get("detailId"));
            String billNo = TmsUtil.str(d.get("sourceBillNo"));
            // 查发货单 SKU 明细
            List<Map<String, Object>> items = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT d.goods_code, d.goods_name, d.unit_name, d.qty
                    FROM sales_receipt_detail d
                    JOIN sales_receipt r ON r.receipt_id = d.receipt_id
                    WHERE r.receipt_no = ?
                    ORDER BY d.detail_id
                    """, billNo);
            BigDecimal dRequired = BigDecimal.ZERO;
            BigDecimal dLoaded = BigDecimal.ZERO;
            for (Map<String, Object> it : items) {
                String gCode = TmsUtil.str(it.get("goodsCode"));
                BigDecimal req = TmsUtil.toBd(it.get("qty"));
                BigDecimal loaded = loadedMap.getOrDefault(detailId + "|" + gCode, BigDecimal.ZERO);
                it.put("requiredQty", req);
                it.put("loadedQty", loaded);
                it.put("diffQty", loaded.subtract(req));
                it.put("checked", loaded.compareTo(BigDecimal.ZERO) > 0);
                dRequired = dRequired.add(req);
                dLoaded = dLoaded.add(loaded);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("detailId", detailId);
            r.put("sourceBillNo", billNo);
            r.put("customerCode", d.get("customerCode"));
            r.put("customerName", d.get("customerName"));
            r.put("customerAddress", d.get("customerAddress"));
            r.put("seqNo", d.get("seqNo"));
            r.put("status", d.get("status"));
            r.put("requiredQty", dRequired);
            r.put("loadedQty", dLoaded);
            r.put("items", items);
            receipts.add(r);
            totalRequired = totalRequired.add(dRequired);
            totalLoaded = totalLoaded.add(dLoaded);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchId", dispatchId);
        result.put("dispatchNo", dispatch.get("dispatchNo"));
        result.put("status", dispatch.get("status"));
        result.put("vehiclePlate", dispatch.get("vehiclePlate"));
        result.put("routeLine", dispatch.get("routeLine"));
        result.put("storeCount", dispatch.get("storeCount"));
        result.put("totalRequired", totalRequired);
        result.put("totalLoaded", totalLoaded);
        result.put("allChecked", totalLoaded.compareTo(totalRequired) >= 0 && !receipts.isEmpty());
        result.put("receipts", receipts);
        return ApiResponse.ok(result);
    }

    /** 开始装车：调度单 ASSIGNED → LOADED，行程 PLANNED → LOADED。 */
    @PostMapping("/loading/start")
    @Transactional
    public ApiResponse<Map<String, Object>> loadingStart(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        String driverId = TmsUtil.currentDriverId();

        Map<String, Object> dispatch = loadDispatch(dispatchId, driverId);
        String status = TmsUtil.str(dispatch.get("status"));
        if (!"ASSIGNED".equals(status)) {
            return ApiResponse.fail("400", "当前调度单状态为「" + status + "」，仅「ASSIGNED」可开始装车");
        }

        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        jdbcTemplate.update("UPDATE tms_dispatch SET status='LOADED', arrange_time=? WHERE dispatch_id=?", now, dispatchId);
        jdbcTemplate.update("UPDATE tms_delivery_trip SET status='LOADED', loading_time=? WHERE dispatch_id=?", now, dispatchId);
        // 发货单 dispatch_status → LOADED
        jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='LOADED' WHERE dispatch_id=?", dispatchId);

        TmsUtil.log(jdbcTemplate, "tms.app.delivery", "LOADING_START", dispatchId, "开始装车");
        return ApiResponse.ok(Map.of("dispatchId", dispatchId, "status", "LOADED"));
    }

    /** 装车扫码核对：逐商品录入实装数量，写 tms_loading_check。 */
    @PostMapping("/loading/scan")
    @Transactional
    public ApiResponse<Map<String, Object>> loadingScan(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String sourceBillNo = TmsUtil.str(body.get("sourceBillNo"));
        String goodsCode = TmsUtil.str(body.get("goodsCode"));
        BigDecimal loadedQty = TmsUtil.toBd(body.get("loadedQty"));
        BigDecimal requiredQty = TmsUtil.toBd(body.get("requiredQty"));
        if (dispatchId.isEmpty() || sourceBillNo.isEmpty() || goodsCode.isEmpty()) {
            return ApiResponse.fail("400", "dispatchId、sourceBillNo、goodsCode 不能为空");
        }
        String driverId = TmsUtil.currentDriverId();

        String checkId = TmsUtil.uuid("LC");
        BigDecimal diff = loadedQty.subtract(requiredQty);
        jdbcTemplate.update("""
                INSERT INTO tms_loading_check(check_id, dispatch_id, detail_id, source_bill_no, goods_code, goods_name,
                    loaded_qty, required_qty, diff_qty, check_time, checker, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, checkId, dispatchId, TmsUtil.str(body.get("detailId")), sourceBillNo, goodsCode,
                TmsUtil.str(body.get("goodsName")), loadedQty, requiredQty, diff,
                Timestamp.valueOf(TmsUtil.now()), driverId, TmsUtil.str(body.get("remark")));

        String alert = diff.compareTo(BigDecimal.ZERO) != 0 ? "装车差异：" + diff + "（应装 " + requiredQty + "，实装 " + loadedQty + "）" : "核对一致";
        return ApiResponse.ok(Map.of("checkId", checkId, "diff", diff, "alert", alert));
    }

    /** 确认装车完成：校验所有发货单已扫码，记录完成时间。 */
    @PostMapping("/loading/confirm")
    @Transactional
    public ApiResponse<Map<String, Object>> loadingConfirm(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        String driverId = TmsUtil.currentDriverId();
        Map<String, Object> dispatch = loadDispatch(dispatchId, driverId);
        String status = TmsUtil.str(dispatch.get("status"));
        if (!"LOADED".equals(status)) {
            return ApiResponse.fail("400", "当前调度单状态为「" + status + "」，需先开始装车");
        }

        // 统计装车核对情况
        Integer checkedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT source_bill_no) FROM tms_loading_check WHERE dispatch_id=?",
                Integer.class, dispatchId);
        Integer totalBills = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tms_dispatch_detail WHERE dispatch_id=? AND bill_type='RECEIPT'",
                Integer.class, dispatchId);
        if (checkedCount == null) checkedCount = 0;
        if (totalBills == null) totalBills = 0;

        TmsUtil.log(jdbcTemplate, "tms.app.delivery", "LOADING_CONFIRM", dispatchId,
                "装车完成：已核对 " + checkedCount + "/" + totalBills + " 张发货单");
        return ApiResponse.ok(Map.of(
                "dispatchId", dispatchId,
                "checkedBills", checkedCount,
                "totalBills", totalBills,
                "allChecked", checkedCount >= totalBills
        ));
    }

    // ==================== 发车 ====================

    /** 确认发车：LOADED → DEPARTED，更新发货单 dispatch_status=DEPARTED。 */
    @PostMapping("/depart")
    @Transactional
    public ApiResponse<Map<String, Object>> depart(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        String driverId = TmsUtil.currentDriverId();
        Map<String, Object> dispatch = loadDispatch(dispatchId, driverId);
        String status = TmsUtil.str(dispatch.get("status"));
        if (!"LOADED".equals(status)) {
            return ApiResponse.fail("400", "当前调度单状态为「" + status + "」，仅「LOADED」可发车");
        }

        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        jdbcTemplate.update("UPDATE tms_dispatch SET status='DEPARTED', depart_time=? WHERE dispatch_id=?", now, dispatchId);
        jdbcTemplate.update("UPDATE tms_delivery_trip SET status='DEPARTED', depart_time=? WHERE dispatch_id=?", now, dispatchId);
        jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='DEPARTED' WHERE dispatch_id=?", dispatchId);

        TmsUtil.log(jdbcTemplate, "tms.app.delivery", "DEPART", dispatchId, "确认发车");
        return ApiResponse.ok(Map.of("dispatchId", dispatchId, "status", "DEPARTED", "departTime", now.toString()));
    }

    // ==================== 到店 ====================

    /**
     * 到达门店打卡（GPS 围栏校验）。
     *
     * 入参：dispatchId、detailId（必填）；longitude、latitude、accuracy（可选，无定位权限时缺省）；
     *       abnormalReason（GPS 异常时必填）、photoUrl（异常且参数要求拍照时必填）
     *
     * 距离与异常判定一律在服务端复算，不信任前端提交的 distance/gpsAbnormal，
     * 防止司机改包绕过围栏。门店无坐标或未取到定位时降级为「无围栏模式」，
     * 只记时间不判异常，避免因基础数据缺失阻断配送。
     *
     * 幂等：仅当 arrive_time IS NULL 时写入，重复打卡返回首次打卡结果。
     */
    @PostMapping("/arrive")
    @Transactional
    public ApiResponse<Map<String, Object>> arrive(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String detailId = TmsUtil.str(body.get("detailId"));
        if (dispatchId.isEmpty() || detailId.isEmpty()) return ApiResponse.fail("400", "dispatchId、detailId 不能为空");
        String driverId = TmsUtil.currentDriverId();
        loadDispatch(dispatchId, driverId); // 校验权限

        List<Map<String, Object>> detailRows = jdbcTemplate.queryForList(
                "SELECT customer_code, customer_name, arrive_time FROM tms_dispatch_detail WHERE detail_id=? AND dispatch_id=?",
                detailId, dispatchId);
        if (detailRows.isEmpty()) return ApiResponse.fail("404", "调度明细不存在");
        Map<String, Object> detail = detailRows.get(0);

        // 已打卡：直接回放首次结果，前端据此提示「已打卡」而非报错
        if (detail.get("arrive_time") != null) {
            Map<String, Object> old = TmsUtil.camelize(jdbcTemplate.queryForList(
                    "SELECT arrive_time, arrive_distance, gps_abnormal, gps_abnormal_reason FROM tms_dispatch_detail WHERE detail_id=?",
                    detailId).get(0));
            old.put("dispatchId", dispatchId);
            old.put("detailId", detailId);
            old.put("repeated", true);
            old.put("message", "该门店已打卡，本次不重复记录");
            return ApiResponse.ok(old);
        }

        BigDecimal lng = body.get("longitude") == null ? null : TmsUtil.toBd(body.get("longitude"));
        BigDecimal lat = body.get("latitude") == null ? null : TmsUtil.toBd(body.get("latitude"));
        BigDecimal accuracy = body.get("accuracy") == null ? null : TmsUtil.toBd(body.get("accuracy"));

        ArriveConfig cfg = loadArriveConfig();

        // 门店档案坐标：缺失则无法围栏，降级放行
        BigDecimal storeLng = null, storeLat = null;
        String customerCode = TmsUtil.str(detail.get("customer_code"));
        if (!customerCode.isEmpty()) {
            List<Map<String, Object>> cs = jdbcTemplate.queryForList(
                    "SELECT longitude, latitude FROM base_customer WHERE customer_code=?", customerCode);
            if (!cs.isEmpty()) {
                storeLng = cs.get(0).get("longitude") == null ? null : TmsUtil.toBd(cs.get(0).get("longitude"));
                storeLat = cs.get(0).get("latitude") == null ? null : TmsUtil.toBd(cs.get(0).get("latitude"));
            }
        }

        BigDecimal distance = null;
        boolean abnormal = false;
        boolean geoEnabled = lng != null && lat != null && storeLng != null && storeLat != null;
        if (geoEnabled) {
            distance = BigDecimal.valueOf(haversineMeters(
                    lat.doubleValue(), lng.doubleValue(), storeLat.doubleValue(), storeLng.doubleValue()))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            abnormal = distance.doubleValue() > cfg.warnRadius;
        }

        String reason = TmsUtil.str(body.get("abnormalReason"));
        String photoUrl = TmsUtil.str(body.get("photoUrl"));
        // 仅在服务端判定为异常时才强校验，避免正常打卡被误拦
        if (abnormal) {
            if (reason.isEmpty()) {
                return ApiResponse.fail("400", String.format(
                        "定位偏差 %.0f 米，超过 %.0f 米，请填写异常原因", distance.doubleValue(), cfg.warnRadius));
            }
            if (cfg.photoRequired && photoUrl.isEmpty()) {
                return ApiResponse.fail("400", "定位异常打卡必须上传现场照片");
            }
        }

        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        jdbcTemplate.update("""
                UPDATE tms_dispatch_detail
                   SET arrive_time=?, arrive_longitude=?, arrive_latitude=?, arrive_accuracy=?,
                       arrive_distance=?, gps_abnormal=?, gps_abnormal_reason=?, arrive_photo_url=?
                 WHERE detail_id=? AND dispatch_id=? AND arrive_time IS NULL
                """, now, lng, lat, accuracy, distance, abnormal ? "Y" : "N",
                reason.isEmpty() ? null : reason, photoUrl.isEmpty() ? null : photoUrl, detailId, dispatchId);

        // 首店到达时推进调度单状态 DEPARTED → DELIVERING
        jdbcTemplate.update("UPDATE tms_dispatch SET status='DELIVERING' WHERE dispatch_id=? AND status='DEPARTED'", dispatchId);
        jdbcTemplate.update("UPDATE tms_delivery_trip SET status='DELIVERING' WHERE dispatch_id=? AND status='DEPARTED'", dispatchId);
        jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='DELIVERING' WHERE dispatch_id=? AND dispatch_status='DEPARTED'", dispatchId);

        TmsUtil.log(jdbcTemplate, "tms.app.arrive", "ARRIVE", detailId,
                "到达打卡：" + TmsUtil.str(detail.get("customer_name"))
                        + (distance == null ? "（未启用围栏）" : String.format("，偏差 %.0f 米", distance.doubleValue()))
                        + (abnormal ? "，GPS异常：" + reason : ""));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchId", dispatchId);
        result.put("detailId", detailId);
        result.put("arriveTime", now.toString().substring(0, 19));
        result.put("distance", distance);
        result.put("gpsAbnormal", abnormal ? "Y" : "N");
        result.put("geoEnabled", geoEnabled);
        result.put("repeated", false);
        result.put("message", abnormal ? "打卡成功，已记录 GPS 异常"
                : geoEnabled ? "打卡成功" : "打卡成功（门店未维护坐标，本次未做围栏校验）");
        return ApiResponse.ok(result);
    }

    /**
     * 到达打卡参数配置。
     *
     * APP 启动/进入打卡页时拉取，据此决定围栏提示强度与是否强制打卡。
     * 参数在 ERP「系统参数」页维护，无需改代码即可调整策略。
     */
    @PostMapping("/arrive/config")
    public ApiResponse<Map<String, Object>> arriveConfig() {
        ArriveConfig cfg = loadArriveConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("normalRadius", cfg.normalRadius);
        result.put("warnRadius", cfg.warnRadius);
        result.put("arriveRequired", cfg.arriveRequired);
        result.put("photoRequired", cfg.photoRequired);
        return ApiResponse.ok(result);
    }

    /** 到达打卡配置项（来自 sys_param_runtime，读取失败时用内置默认值兜底）。 */
    private record ArriveConfig(double normalRadius, double warnRadius, boolean arriveRequired, boolean photoRequired) {}

    /**
     * 读取到达打卡配置。
     *
     * 参数表异常或值非法一律回退默认值：配置读取绝不能成为打卡失败的原因。
     * 同时保证 warnRadius >= normalRadius，避免误配置导致所有打卡都判异常。
     */
    private ArriveConfig loadArriveConfig() {
        Map<String, String> kv = new HashMap<>();
        try {
            jdbcTemplate.queryForList("""
                    SELECT param_key, COALESCE(param_value, default_value) AS v
                      FROM sys_param_runtime
                     WHERE param_key IN ('TMS_ARRIVE_NORMAL_RADIUS','TMS_ARRIVE_WARN_RADIUS',
                                         'TMS_ARRIVE_REQUIRED','TMS_ARRIVE_PHOTO_REQUIRED')
                    """).forEach(r -> kv.put(TmsUtil.str(r.get("param_key")), TmsUtil.str(r.get("v"))));
        } catch (Exception ignore) {
            // 参数表不可用时走默认值
        }
        double normal = parseDouble(kv.get("TMS_ARRIVE_NORMAL_RADIUS"), 200);
        double warn = parseDouble(kv.get("TMS_ARRIVE_WARN_RADIUS"), 1000);
        if (warn < normal) warn = normal;
        return new ArriveConfig(normal, warn,
                "true".equalsIgnoreCase(TmsUtil.str(kv.get("TMS_ARRIVE_REQUIRED"))),
                !"false".equalsIgnoreCase(TmsUtil.str(kv.getOrDefault("TMS_ARRIVE_PHOTO_REQUIRED", "true"))));
    }

    private double parseDouble(String v, double def) {
        if (v == null || v.isBlank()) return def;
        try {
            double d = Double.parseDouble(v.trim());
            return d > 0 ? d : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Haversine 球面距离（米）。短距离场景精度足够，无需引入 GIS 依赖。 */
    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ==================== 签收 ====================

    /**
     * 签收 SKU 明细：按调度明细 detailId 拉取该发货单的逐商品明细（含已签收数量回写）。
     * 返回结构：
     *   detailId, dispatchId, sourceBillNo, customerCode, customerName, customerAddress,
     *   requiredQty, signedQty, rejectQty, amount, collectAmount, payMethod, items:[{ goodsCode, goodsName, unitName, requiredQty, signedQty }]
     */
    @PostMapping("/sign/items")
    public ApiResponse<Map<String, Object>> signItems(@RequestBody Map<String, Object> body) {
        String detailId = TmsUtil.str(body.get("detailId"));
        if (detailId.isEmpty()) return ApiResponse.fail("400", "detailId 不能为空");
        // 查调度明细并校验本人
        List<Map<String, Object>> detailRows = jdbcTemplate.queryForList(
                "SELECT * FROM tms_dispatch_detail WHERE detail_id=?", detailId);
        if (detailRows.isEmpty()) return ApiResponse.fail("404", "调度明细不存在");
        Map<String, Object> dRaw = detailRows.get(0);
        String dispatchId = TmsUtil.str(dRaw.get("dispatch_id"));
        loadDispatch(dispatchId, TmsUtil.currentDriverId()); // 校验权限

        Map<String, Object> d = TmsUtil.camelize(dRaw);
        String billNo = TmsUtil.str(d.get("sourceBillNo"));

        // 查发货单头
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT receipt_id, receipt_no, customer_code, customer_name, deliver_amount, receive_status FROM sales_receipt WHERE receipt_no=?",
                billNo);
        BigDecimal billAmount = heads.isEmpty() ? BigDecimal.ZERO : TmsUtil.toBd(heads.get(0).get("deliver_amount"));

        // 查 SKU 明细
        List<Map<String, Object>> items = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT d.goods_code, d.goods_name, d.unit_name, d.qty
                FROM sales_receipt_detail d
                JOIN sales_receipt r ON r.receipt_id = d.receipt_id
                WHERE r.receipt_no = ?
                ORDER BY d.detail_id
                """, billNo);

        // 已签收数量（取最近一次该 detail_id 的签收记录拆解；当前简化为按单据级 signed_qty 均摊，无明细级回写）
        BigDecimal detailSigned = BigDecimal.ZERO;
        List<Map<String, Object>> signRows = jdbcTemplate.queryForList(
                "SELECT signed_qty, reject_qty, collect_amount, pay_method FROM tms_sign_record WHERE detail_id=? ORDER BY sign_time DESC LIMIT 1",
                detailId);
        if (!signRows.isEmpty()) {
            detailSigned = TmsUtil.toBd(signRows.get(0).get("signed_qty"));
        }

        BigDecimal dRequired = TmsUtil.toBd(d.get("qty"));
        for (Map<String, Object> it : items) {
            it.put("requiredQty", TmsUtil.toBd(it.get("qty")));
            it.put("signedQty", BigDecimal.ZERO); // 简化：默认未签，APP 端可二次录入
            it.remove("qty");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("detailId", detailId);
        result.put("dispatchId", dispatchId);
        result.put("sourceBillNo", billNo);
        result.put("billType", TmsUtil.str(d.get("billType")));
        result.put("customerCode", d.get("customerCode"));
        result.put("customerName", d.get("customerName"));
        result.put("customerAddress", d.get("customerAddress"));
        result.put("seqNo", d.get("seqNo"));
        result.put("status", d.get("status"));
        result.put("requiredQty", dRequired);
        result.put("signedQty", detailSigned);
        result.put("amount", billAmount);
        // 透传打卡状态与配置，供签收页做软提示（是否阻断由 arriveRequired 决定，判定在 /sign 复算）
        ArriveConfig cfg = loadArriveConfig();
        result.put("arriveTime", d.get("arriveTime"));
        result.put("arriveDistance", d.get("arriveDistance"));
        result.put("gpsAbnormal", d.get("gpsAbnormal"));
        result.put("arriveRequired", cfg.arriveRequired());
        // 透传门店档案坐标与联系人：坐标供打卡围栏（否则会退化成无围栏模式），电话供「联系客户」
        // 同时透传结算方式：司机要在门店当场决定「这单该不该收钱」，只有货到付款才需要收，
        // 预付已付、账期挂账，缺了这个字段司机只能凭记忆判断，容易多收或漏收。
        String custCode = TmsUtil.str(d.get("customerCode"));
        if (!custCode.isEmpty()) {
            List<Map<String, Object>> cs = jdbcTemplate.queryForList(
                    "SELECT longitude, latitude, contact_name, mobile, settlement_type FROM base_customer WHERE customer_code=?", custCode);
            if (!cs.isEmpty()) {
                result.put("longitude", cs.get(0).get("longitude"));
                result.put("latitude", cs.get(0).get("latitude"));
                result.put("contactName", cs.get(0).get("contact_name"));
                result.put("contactMobile", cs.get(0).get("mobile"));
                Object st = cs.get(0).get("settlement_type");
                result.put("settlementType", TmsUtil.str(st));
                result.put("settlementText", TmsUtil.settlementText(st));
                result.put("needCollect", TmsUtil.needCollect(st));
            }
        }
        result.put("items", items);
        return ApiResponse.ok(result);
    }

    /**
     * 门店签收（发货单）：全部/部分/拒收。
     * 入参：dispatchId, detailId, sourceBillNo, items:[{goodsCode, signedQty, rejectQty}],
     *       collectAmount, payMethod, customerSigner, remark
     */
    @PostMapping("/sign")
    @Transactional
    public ApiResponse<Map<String, Object>> sign(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String detailId = TmsUtil.str(body.get("detailId"));
        String sourceBillNo = TmsUtil.str(body.get("sourceBillNo"));
        if (dispatchId.isEmpty() || detailId.isEmpty() || sourceBillNo.isEmpty()) {
            return ApiResponse.fail("400", "dispatchId、detailId、sourceBillNo 不能为空");
        }
        String driverId = TmsUtil.currentDriverId();
        Map<String, Object> dispatch = loadDispatch(dispatchId, driverId);
        String tripId = TmsUtil.str(dispatch.get("tripId"));

        // 查调度明细
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM tms_dispatch_detail WHERE detail_id=? AND dispatch_id=?", detailId, dispatchId);
        if (details.isEmpty()) return ApiResponse.fail("404", "调度明细不存在");
        Map<String, Object> detail = TmsUtil.camelize(details.get(0));
        String detailStatus = TmsUtil.str(detail.get("status"));
        if ("DELIVERED".equals(detailStatus)) return ApiResponse.fail("400", "该发货单已签收");

        // 打卡门禁：默认不强制，仅当参数 TMS_ARRIVE_REQUIRED=true 时才阻断签收。
        // 服务端复算而不信任前端，避免绕过；关闭时完全不干预签收流程。
        if (loadArriveConfig().arriveRequired() && detail.get("arriveTime") == null) {
            return ApiResponse.fail("400", "当前配置要求先完成到店打卡才能签收");
        }

        BigDecimal requiredQty = TmsUtil.toBd(detail.get("qty"));
        String customerCode = TmsUtil.str(detail.get("customerCode"));
        String customerName = TmsUtil.str(detail.get("customerName"));

        // 解析签收明细
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = body.get("items") == null ? List.of() : (List<Map<String, Object>>) body.get("items");
        BigDecimal totalSigned = BigDecimal.ZERO;
        BigDecimal totalReject = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            totalSigned = totalSigned.add(TmsUtil.toBd(it.get("signedQty")));
            totalReject = totalReject.add(TmsUtil.toBd(it.get("rejectQty")));
        }
        // 未传明细默认全收
        if (items.isEmpty()) totalSigned = requiredQty;

        // 签收类型
        String signType;
        String newDetailStatus;
        if (totalSigned.compareTo(BigDecimal.ZERO) == 0 && totalReject.compareTo(BigDecimal.ZERO) > 0) {
            signType = "REJECT";
            newDetailStatus = "REJECTED";
        } else if (totalSigned.compareTo(requiredQty) < 0) {
            signType = "PARTIAL";
            newDetailStatus = "PARTIAL";
        } else {
            signType = "NORMAL";
            newDetailStatus = "DELIVERED";
        }

        BigDecimal collectAmount = TmsUtil.toBd(body.get("collectAmount"));
        String payMethod = TmsUtil.str(body.get("payMethod"));
        String customerSigner = TmsUtil.str(body.get("customerSigner"));
        String remark = TmsUtil.str(body.get("remark"));
        String signatureUrl = TmsUtil.str(body.get("signatureUrl"));
        Timestamp now = Timestamp.valueOf(TmsUtil.now());

        // 1. 写签收记录
        String signId = TmsUtil.uuid("QS");
        jdbcTemplate.update("""
                INSERT INTO tms_sign_record(sign_id, dispatch_id, detail_id, trip_id, source_bill_no,
                    customer_code, customer_name, bill_type, sign_type, signed_qty, reject_qty,
                    collect_amount, pay_method, sign_time, sign_user, customer_signer, customer_sign_img, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RECEIPT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, signId, dispatchId, detailId, tripId.isEmpty() ? null : tripId, sourceBillNo,
                customerCode, customerName, signType, totalSigned, totalReject,
                collectAmount, payMethod.isEmpty() ? null : payMethod, now, driverId, customerSigner,
                signatureUrl.isEmpty() ? null : signatureUrl, remark);

        // 1.1 随主单一并落照片（可选）。
        // 之所以在签收接口里也支持 photos，而不是一律走 /sign/upload-photo：
        // APP 离线时先把签收请求排入本地队列，此刻 signId 还不存在，
        // 照片若拆成第二个请求，重放时必然缺 signId 被 400 拒绝并永久卡在队列里。
        // 签收时 signId 已生成，这里顺带写入即可让离线链路一次成功。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> headPhotos = body.get("photos") instanceof List<?> pl
                ? (List<Map<String, Object>>) pl : List.<Map<String, Object>>of();
        for (Map<String, Object> p : headPhotos) {
            String url = TmsUtil.str(p.get("url"));
            if (url.isEmpty()) continue;
            String photoType = TmsUtil.str(p.get("photoType"));
            if (photoType.isEmpty()) photoType = "GOODS";
            jdbcTemplate.update("""
                    INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, ?, ?, ?)
                    """, TmsUtil.uuid("PH"), signId, photoType, url, TmsUtil.extractObjectKey(url));
        }

        // 2. 更新调度明细状态
        jdbcTemplate.update("UPDATE tms_dispatch_detail SET status=?, sign_time=?, sign_user=? WHERE detail_id=?",
                newDetailStatus, now, driverId, detailId);

        // 3. 更新发货单收款状态
        if (collectAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal finalAmount = TmsUtil.toBd(detail.get("amount"));
            String receiveStatus = collectAmount.compareTo(finalAmount) >= 0 ? "已收款" : "部分收款";
            jdbcTemplate.update("UPDATE sales_receipt SET receive_status=?, dispatch_status='DELIVERING' WHERE receipt_no=?",
                    receiveStatus, sourceBillNo);
        }

        // 4. 检查是否全部签收完成
        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tms_dispatch_detail WHERE dispatch_id=? AND bill_type='RECEIPT' AND status IN ('PENDING','PARTIAL')",
                Integer.class, dispatchId);
        if (pendingCount != null && pendingCount == 0) {
            jdbcTemplate.update("UPDATE tms_dispatch SET status='COMPLETED', complete_time=? WHERE dispatch_id=?", now, dispatchId);
            jdbcTemplate.update("UPDATE tms_delivery_trip SET status='COMPLETED', complete_time=? WHERE dispatch_id=?", now, dispatchId);
            jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='COMPLETED' WHERE dispatch_id=?", dispatchId);
        }

        TmsUtil.log(jdbcTemplate, "tms.app.delivery", "SIGN", sourceBillNo,
                "发货单签收：类型=" + signType + "，实收=" + totalSigned + "，拒收=" + totalReject + "，收款=" + collectAmount);
        return ApiResponse.ok(Map.of(
                "signId", signId,
                "signType", signType,
                "detailStatus", newDetailStatus,
                "signedQty", totalSigned,
                "rejectQty", totalReject,
                "collectAmount", collectAmount,
                "allCompleted", pendingCount != null && pendingCount == 0
        ));
    }

    /**
     * 上传签收照片（URL 数组，APP 端先调 /tms/app/upload/image 上传获得 URL）。
     * 入参：signId, photos:[{url, photoType}]
     */
    @PostMapping("/sign/upload-photo")
    @Transactional
    public ApiResponse<Map<String, Object>> uploadPhoto(@RequestBody Map<String, Object> body) {
        String signId = TmsUtil.str(body.get("signId"));
        if (signId.isEmpty()) return ApiResponse.fail("400", "signId 不能为空");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> photos = body.get("photos") == null ? List.of() : (List<Map<String, Object>>) body.get("photos");
        if (photos.isEmpty()) return ApiResponse.fail("400", "照片不能为空");

        List<String> photoIds = new ArrayList<>();
        for (Map<String, Object> p : photos) {
            String photoId = TmsUtil.uuid("PH");
            String photoType = TmsUtil.str(p.get("photoType"));
            if (photoType.isEmpty()) photoType = "GOODS";
            String url = TmsUtil.str(p.get("url"));
            if (url.isEmpty()) continue;
            jdbcTemplate.update("""
                    INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, ?, ?, ?)
                    """, photoId, signId, photoType, url, TmsUtil.extractObjectKey(url));
            photoIds.add(photoId);
        }

        return ApiResponse.ok(Map.of("signId", signId, "photoIds", photoIds, "count", photoIds.size()));
    }

    // ==================== GPS 定位 ====================

    /** GPS 单点上报：写 tms_driver_location + 更新调度单当前位置快照。 */
    @PostMapping("/location/report")
    @Transactional
    public ApiResponse<Map<String, Object>> locationReport(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        if (driverId.isEmpty()) return ApiResponse.fail("401", "未登录");
        BigDecimal longitude = TmsUtil.toBd(body.get("longitude"));
        BigDecimal latitude = TmsUtil.toBd(body.get("latitude"));
        if (longitude.compareTo(BigDecimal.ZERO) == 0 || latitude.compareTo(BigDecimal.ZERO) == 0) {
            return ApiResponse.fail("400", "经纬度不能为空");
        }
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String tripId = TmsUtil.str(body.get("tripId"));

        // 查司机名
        String driverName = "";
        if (!dispatchId.isEmpty()) {
            List<Map<String, Object>> d = jdbcTemplate.queryForList(
                    "SELECT driver_name FROM tms_dispatch WHERE dispatch_id=?", dispatchId);
            if (!d.isEmpty()) driverName = TmsUtil.str(d.get(0).get("driver_name"));
        }

        String locId = TmsUtil.uuid("LOC");
        String locTimeStr = TmsUtil.str(body.get("locTime"));
        Timestamp locTime = locTimeStr.isEmpty() ? Timestamp.valueOf(TmsUtil.now()) : Timestamp.valueOf(locTimeStr);

        jdbcTemplate.update("""
                INSERT INTO tms_driver_location(loc_id, driver_id, driver_name, dispatch_id, trip_id,
                    longitude, latitude, speed, heading, accuracy, loc_time, report_time, online)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                """, locId, driverId, driverName, dispatchId.isEmpty() ? null : dispatchId, tripId.isEmpty() ? null : tripId,
                longitude, latitude, TmsUtil.toBd(body.get("speed")), TmsUtil.toBd(body.get("heading")),
                TmsUtil.toBd(body.get("accuracy")), locTime, Timestamp.valueOf(TmsUtil.now()));

        // 更新调度单当前位置快照
        if (!dispatchId.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE tms_dispatch SET cur_longitude=?, cur_latitude=?, cur_loc_time=?, cur_speed=?
                    WHERE dispatch_id=?
                    """, longitude, latitude, locTime, TmsUtil.toBd(body.get("speed")), dispatchId);
        }

        return ApiResponse.ok(Map.of("locId", locId, "received", true));
    }

    /** GPS 批量上报（离线补传）：一次性写入多条轨迹。 */
    @PostMapping("/location/batch-report")
    @Transactional
    public ApiResponse<Map<String, Object>> batchReport(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        if (driverId.isEmpty()) return ApiResponse.fail("401", "未登录");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locations = body.get("locations") == null ? List.of() : (List<Map<String, Object>>) body.get("locations");
        if (locations.isEmpty()) return ApiResponse.fail("400", "定位数据不能为空");

        // 批量插入 GPS 轨迹（使用 batchUpdate 提升离线补传性能）
        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        List<Object[]> batchArgs = new ArrayList<>();
        for (Map<String, Object> loc : locations) {
            BigDecimal longitude = TmsUtil.toBd(loc.get("longitude"));
            BigDecimal latitude = TmsUtil.toBd(loc.get("latitude"));
            if (longitude.compareTo(BigDecimal.ZERO) == 0) continue;
            String dispatchId = TmsUtil.str(loc.get("dispatchId"));
            String tripId = TmsUtil.str(loc.get("tripId"));
            String locTimeStr = TmsUtil.str(loc.get("locTime"));
            Timestamp locTime = locTimeStr.isEmpty() ? now : Timestamp.valueOf(locTimeStr);
            String locId = TmsUtil.uuid("LOC");

            batchArgs.add(new Object[]{
                    locId, driverId, dispatchId.isEmpty() ? null : dispatchId, tripId.isEmpty() ? null : tripId,
                    longitude, latitude, TmsUtil.toBd(loc.get("speed")), TmsUtil.toBd(loc.get("heading")),
                    TmsUtil.toBd(loc.get("accuracy")), locTime, now
            });
        }
        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO tms_driver_location(loc_id, driver_id, dispatch_id, trip_id,
                        longitude, latitude, speed, heading, accuracy, loc_time, report_time, online)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                    """, batchArgs);
        }
        int count = batchArgs.size();

        // 更新最后位置到调度单快照
        if (!locations.isEmpty()) {
            Map<String, Object> last = locations.get(locations.size() - 1);
            String dispatchId = TmsUtil.str(last.get("dispatchId"));
            if (!dispatchId.isEmpty()) {
                jdbcTemplate.update("""
                        UPDATE tms_dispatch SET cur_longitude=?, cur_latitude=?, cur_loc_time=?, cur_speed=?
                        WHERE dispatch_id=?
                        """, TmsUtil.toBd(last.get("longitude")), TmsUtil.toBd(last.get("latitude")),
                        Timestamp.valueOf(TmsUtil.now()), TmsUtil.toBd(last.get("speed")), dispatchId);
            }
        }

        return ApiResponse.ok(Map.of("received", count, "total", locations.size()));
    }

    // ==================== 辅助方法 ====================

    /** 加载调度单并校验司机权限。 */
    private Map<String, Object> loadDispatch(String dispatchId, String driverId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM tms_dispatch WHERE dispatch_id=? AND driver_id=?", dispatchId, driverId);
        if (rows.isEmpty()) throw new IllegalArgumentException("调度单不存在或非本人");
        Map<String, Object> d = TmsUtil.camelize(rows.get(0));
        // 查关联行程
        List<Map<String, Object>> trips = jdbcTemplate.queryForList(
                "SELECT trip_id FROM tms_delivery_trip WHERE dispatch_id=? ORDER BY create_time DESC LIMIT 1", dispatchId);
        if (!trips.isEmpty()) d.put("tripId", TmsUtil.str(trips.get(0).get("trip_id")));
        return d;
    }
}
