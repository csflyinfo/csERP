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
import java.time.LocalDateTime;
import java.util.*;

/**
 * TMS 通用异常上报（P3-4）。
 *
 * 触发场景：司机在途遇到「没有单据流程可走」的现场异常——车辆故障、交通事故、
 *           货物破损、门店关门、天气/道路阻断等。这类异常不产生货权流转
 *           （区别于客户拒收单、改派返仓单），但必须让调度员立刻知道。
 *
 * 核心原则：
 *   1. 上报绝不能被阻断。坐标拿不到、行程关联不上、照片没传成，都要允许落库——
 *      司机在事故现场没有耐心跟表单较劲，漏报的代价远大于信息不全。
 *   2. 严重程度不采信前端。参数 TMS_EXCEPTION_URGENT_TYPES 命中的类型服务端强制置
 *      URGENT，理由同 V59 的距离复算：告警级别不能由客户端决定。
 *   3. 照片随主单一次落库。离线队列重放时若拆成两个请求，第二个请求缺 reportId
 *      会被 400 永久卡在队列（P3-1 已踩过这个坑）。
 *
 * APP 接口：
 *   POST /tms/app/exception/options   异常类型选项 + 是否必须拍照配置
 *   POST /tms/app/exception/create    上报异常（含照片 + GPS）
 *   POST /tms/app/exception/list      本司机异常上报记录（含处理进度）
 *
 * ERP 接口：
 *   POST /tms/exception/page          异常上报列表
 *   GET  /tms/exception/{id}          异常详情（含照片）
 *   POST /tms/exception/{id}/handle   接手处理（PENDING → HANDLING）
 *   POST /tms/exception/{id}/close    关闭（→ CLOSED，登记处理结论）
 */
@RestController
public class TmsExceptionReportController {

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;
    private final TmsNotifyService notifyService;

    public TmsExceptionReportController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen,
                                        TmsNotifyService notifyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
        this.notifyService = notifyService;
    }

    /**
     * 异常类型字典。
     *
     * 刻意不含「客户拒收」「地址不符」：这两类已有 tms_customer_reject /
     * tms_reschedule_return 专门承载，若在此重复开口，司机会用异常上报绕过
     * 正式单据，导致库存与应收挂账不平。
     */
    private static final List<Map<String, Object>> EXCEPTION_TYPES = List.of(
            Map.of("code", "VEHICLE_FAULT", "name", "车辆故障", "needVehicle", true),
            Map.of("code", "TRAFFIC_ACCIDENT", "name", "交通事故", "needVehicle", true),
            Map.of("code", "GOODS_DAMAGE", "name", "货物破损", "needVehicle", false),
            Map.of("code", "STORE_CLOSED", "name", "门店关门", "needVehicle", false),
            Map.of("code", "WEATHER", "name", "天气阻断", "needVehicle", false),
            Map.of("code", "ROAD_BLOCKED", "name", "道路管控", "needVehicle", false),
            Map.of("code", "OTHER", "name", "其他异常", "needVehicle", false)
    );

    private static final Set<String> VALID_TYPES = EXCEPTION_TYPES.stream()
            .map(t -> String.valueOf(t.get("code"))).collect(java.util.stream.Collectors.toSet());

    // ========================================================================
    // APP 端接口
    // ========================================================================

    /**
     * 异常上报选项与配置（APP 打开上报页时拉取，避免类型清单写死在客户端）。
     *
     * 用 POST 而非 GET：APP 侧 ApiService 只暴露 post，全部 /tms/app/** 接口
     * 统一走 POST（参见 /tms/app/arrive/config 同样是纯读配置）。
     */
    @PostMapping("/tms/app/exception/options")
    public ApiResponse<Map<String, Object>> options() {
        ExceptionConfig cfg = loadConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("types", EXCEPTION_TYPES);
        result.put("photoRequired", cfg.photoRequired());
        result.put("urgentTypes", cfg.urgentTypes());
        return ApiResponse.ok(result);
    }

    /**
     * 上报异常。
     * 入参：exceptionType, description, title?, tripId?, dispatchId?, detailId?, receiptNo?,
     *       vehicleNo?, longitude?, latitude?, accuracy?, locationAddress?, reportedAt?, remark?,
     *       photos:[{url}]
     * 处理：
     *   1. 校验类型与描述
     *   2. 有 detailId 时回填客户信息，有 tripId 时回填车牌（司机不用手填）
     *   3. 服务端判定 severity
     *   4. 落主单 + 照片 + 操作日志
     */
    @PostMapping("/tms/app/exception/create")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        String exceptionType = TmsUtil.str(body.get("exceptionType")).toUpperCase();
        String description = TmsUtil.str(body.get("description"));
        if (exceptionType.isEmpty() || !VALID_TYPES.contains(exceptionType)) {
            return ApiResponse.fail("400", "请选择有效的异常类型");
        }
        if (description.isEmpty()) {
            return ApiResponse.fail("400", "请填写异常描述");
        }

        String tripId = TmsUtil.str(body.get("tripId"));
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String detailId = TmsUtil.str(body.get("detailId"));
        String receiptNo = TmsUtil.str(body.get("receiptNo"));
        String customerCode = TmsUtil.str(body.get("customerCode"));
        String customerName = TmsUtil.str(body.get("customerName"));
        String vehicleNo = TmsUtil.str(body.get("vehicleNo"));
        String driverName = TmsUtil.str(body.get("driverName"));

        // 关联信息回填：APP 只需传 detailId，客户/发货单/行程由服务端补齐。
        // 这么做不只是省事——司机手填的客户名往往与档案不一致，事后按客户统计异常会漏。
        if (!detailId.isEmpty()) {
            List<Map<String, Object>> dRows = jdbcTemplate.queryForList("""
                    SELECT trip_id, dispatch_id, receipt_no, customer_code, customer_name
                      FROM tms_dispatch_detail WHERE detail_id = ?
                    """, detailId);
            if (!dRows.isEmpty()) {
                Map<String, Object> d = dRows.get(0);
                if (tripId.isEmpty()) tripId = TmsUtil.str(d.get("trip_id"));
                if (dispatchId.isEmpty()) dispatchId = TmsUtil.str(d.get("dispatch_id"));
                if (receiptNo.isEmpty()) receiptNo = TmsUtil.str(d.get("receipt_no"));
                if (customerCode.isEmpty()) customerCode = TmsUtil.str(d.get("customer_code"));
                if (customerName.isEmpty()) customerName = TmsUtil.str(d.get("customer_name"));
            }
        }
        // 车牌与司机名从调度单/行程补齐：车辆故障类异常必须知道是哪台车
        if (vehicleNo.isEmpty() || driverName.isEmpty()) {
            Map<String, Object> src = null;
            if (!dispatchId.isEmpty()) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT vehicle_no, driver_name FROM tms_dispatch WHERE dispatch_id=?", dispatchId);
                if (!rows.isEmpty()) src = rows.get(0);
            }
            if (src == null && !tripId.isEmpty()) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT vehicle_no, driver_name FROM tms_delivery_trip WHERE trip_id=?", tripId);
                if (!rows.isEmpty()) src = rows.get(0);
            }
            if (src == null && !driverId.isEmpty()) {
                // 无行程场景（如出车前检查发现故障）：退到司机档案取默认车辆
                try {
                    List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                            SELECT v.vehicle_no, d.driver_name
                              FROM tms_driver d LEFT JOIN tms_vehicle v ON v.vehicle_id = d.default_vehicle_id
                             WHERE d.driver_id = ?
                            """, driverId);
                    if (!rows.isEmpty()) src = rows.get(0);
                } catch (Exception ignore) {
                    // 司机档案无默认车辆字段时忽略，车牌留空由调度员补
                }
            }
            if (src != null) {
                if (vehicleNo.isEmpty()) vehicleNo = TmsUtil.str(src.get("vehicle_no"));
                if (driverName.isEmpty()) driverName = TmsUtil.str(src.get("driver_name"));
            }
        }

        // 坐标可空：地下车库、隧道、拒绝定位权限都拿不到坐标，
        // 此时靠 locationAddress（司机口述位置）兜底，绝不因为没坐标就拒绝上报
        BigDecimal lng = body.get("longitude") == null ? null : TmsUtil.toBd(body.get("longitude"));
        BigDecimal lat = body.get("latitude") == null ? null : TmsUtil.toBd(body.get("latitude"));
        BigDecimal accuracy = body.get("accuracy") == null ? null : TmsUtil.toBd(body.get("accuracy"));

        ExceptionConfig cfg = loadConfig();
        String severity = cfg.urgentTypes().contains(exceptionType) ? "URGENT" : "NORMAL";

        String title = TmsUtil.str(body.get("title"));
        if (title.isEmpty()) title = typeName(exceptionType);

        // 上报时间以客户端提交为准（离线补传时 create_time 是入库时刻，
        // 两者相差可能几小时，事故追溯必须用司机现场的时间）
        Timestamp reportedAt = parseReportedAt(TmsUtil.str(body.get("reportedAt")));

        String reportId = TmsUtil.uuid("YCSB");
        String reportNo = billNoGen.nextNo(
                BillNoGenerator.BillType.TMS_EXCEPTION_REPORT, "tms_exception_report", "report_no");

        jdbcTemplate.update("""
                INSERT INTO tms_exception_report(report_id, report_no, exception_type, severity, title, description,
                    trip_id, dispatch_id, detail_id, receipt_no, customer_code, customer_name,
                    vehicle_no, driver_id, driver_name, longitude, latitude, accuracy, location_address,
                    status, reported_at, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, reportId, reportNo, exceptionType, severity, title, description,
                nullIfEmpty(tripId), nullIfEmpty(dispatchId), nullIfEmpty(detailId), nullIfEmpty(receiptNo),
                customerCode, customerName, vehicleNo, driverId, driverName,
                lng, lat, accuracy, TmsUtil.str(body.get("locationAddress")),
                reportedAt, TmsUtil.str(body.get("remark")));

        // 随主单一并落照片：现场照片是事后定责的唯一凭据，必须保证离线也不丢
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> photos = body.get("photos") instanceof List<?> pl
                ? (List<Map<String, Object>>) pl : new ArrayList<>();
        int savedPhotos = savePhotos(reportId, photos);

        TmsUtil.log(jdbcTemplate, "tms.app.exception", "CREATE", reportNo,
                "异常上报：" + typeName(exceptionType) + "（" + severity + "）"
                        + (customerName.isEmpty() ? "" : "，客户：" + customerName)
                        + "，照片：" + savedPhotos + " 张");

        // 反向通知调度员：司机在路上出问题，调度员必须主动看到，
        // 而不是等司机打电话或自己刷异常列表
        notifyDispatchers(reportNo, exceptionType, severity, driverName, customerName,
                TmsUtil.str(body.get("description")), reportId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", reportId);
        result.put("reportNo", reportNo);
        result.put("exceptionType", exceptionType);
        result.put("severity", severity);
        result.put("status", "PENDING");
        result.put("photoCount", savedPhotos);
        return ApiResponse.ok(result);
    }

    /**
     * 本司机异常上报记录。
     * 入参：status?（不传返回全部）、limit?（默认 50）
     *
     * 必须让司机看到处理进度：上报完就消失的话，司机无法确认调度员是否收到，
     * 下次还是会先打电话，异常上报功能等于白做。
     */
    @PostMapping("/tms/app/exception/list")
    public ApiResponse<Map<String, Object>> list(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String driverId = TmsUtil.currentDriverId();
        StringBuilder sql = new StringBuilder("""
                SELECT report_id, report_no, exception_type, severity, title, description,
                       trip_id, dispatch_id, detail_id, receipt_no, customer_name, vehicle_no,
                       longitude, latitude, location_address, status, handler, handle_result,
                       handled_at, closed_at, reported_at, create_time, remark
                  FROM tms_exception_report
                 WHERE driver_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(driverId);
        String status = TmsUtil.str(b.get("status"));
        if (!status.isEmpty()) { sql.append(" AND status = ?"); args.add(status); }
        sql.append(" ORDER BY COALESCE(reported_at, create_time) DESC");

        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        int limit = TmsUtil.toInt(b.getOrDefault("limit", 50));
        if (limit > 0 && rows.size() > limit) rows = rows.subList(0, limit);
        for (Map<String, Object> r : rows) {
            r.put("exceptionTypeName", typeName(TmsUtil.str(r.get("exceptionType"))));
            r.put("photos", photoUrls(TmsUtil.str(r.get("reportId"))));
        }

        long pending = rows.stream().filter(r -> !"CLOSED".equals(TmsUtil.str(r.get("status")))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("count", rows.size());
        result.put("pendingCount", pending);
        return ApiResponse.ok(result);
    }

    // ========================================================================
    // ERP 端接口
    // ========================================================================

    /** 异常上报列表。 */
    @PostMapping("/tms/exception/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT e.report_id, e.report_no, e.exception_type, e.severity, e.title, e.description,
                       e.trip_id, e.dispatch_id, e.detail_id, e.receipt_no, e.customer_code, e.customer_name,
                       e.vehicle_no, e.driver_id, e.driver_name, e.longitude, e.latitude, e.accuracy,
                       e.location_address, e.status, e.handler, e.handle_result, e.handled_at, e.closed_at,
                       e.reported_at, e.create_time, e.remark,
                       d.dispatch_no, t.trip_no
                  FROM tms_exception_report e
                  LEFT JOIN tms_dispatch d ON d.dispatch_id = e.dispatch_id
                  LEFT JOIN tms_delivery_trip t ON t.trip_id = e.trip_id
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String status = TmsUtil.str(filters.get("status"));
        if (!status.isEmpty()) { sql.append(" AND e.status = ?"); args.add(status); }
        String type = TmsUtil.str(filters.get("exceptionType"));
        if (!type.isEmpty()) { sql.append(" AND e.exception_type = ?"); args.add(type); }
        String severity = TmsUtil.str(filters.get("severity"));
        if (!severity.isEmpty()) { sql.append(" AND e.severity = ?"); args.add(severity); }
        String driverName = TmsUtil.str(filters.get("driverName"));
        if (!driverName.isEmpty()) { sql.append(" AND e.driver_name LIKE ?"); args.add("%" + driverName + "%"); }
        String customerName = TmsUtil.str(filters.get("customerName"));
        if (!customerName.isEmpty()) { sql.append(" AND e.customer_name LIKE ?"); args.add("%" + customerName + "%"); }
        String reportNo = TmsUtil.str(filters.get("reportNo"));
        if (!reportNo.isEmpty()) { sql.append(" AND e.report_no LIKE ?"); args.add("%" + reportNo + "%"); }
        String vehicleNo = TmsUtil.str(filters.get("vehicleNo"));
        if (!vehicleNo.isEmpty()) { sql.append(" AND e.vehicle_no LIKE ?"); args.add("%" + vehicleNo + "%"); }
        String beginDate = TmsUtil.str(filters.get("beginDate"));
        if (!beginDate.isEmpty()) { sql.append(" AND COALESCE(e.reported_at, e.create_time) >= ?"); args.add(beginDate + " 00:00:00"); }
        String endDate = TmsUtil.str(filters.get("endDate"));
        if (!endDate.isEmpty()) { sql.append(" AND COALESCE(e.reported_at, e.create_time) <= ?"); args.add(endDate + " 23:59:59"); }

        // 紧急优先、未处理优先：调度员打开列表第一眼就该看到事故，而不是按时间倒排
        sql.append("""
                 ORDER BY CASE e.status WHEN 'PENDING' THEN 0 WHEN 'HANDLING' THEN 1 ELSE 2 END,
                          CASE e.severity WHEN 'URGENT' THEN 0 ELSE 1 END,
                          COALESCE(e.reported_at, e.create_time) DESC
                """);
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("exceptionTypeName", typeName(TmsUtil.str(r.get("exceptionType"))));
        }
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 异常详情（含现场照片）。 */
    @GetMapping("/tms/exception/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT e.*, d.dispatch_no, t.trip_no
                  FROM tms_exception_report e
                  LEFT JOIN tms_dispatch d ON d.dispatch_id = e.dispatch_id
                  LEFT JOIN tms_delivery_trip t ON t.trip_id = e.trip_id
                 WHERE e.report_id = ? OR e.report_no = ?
                """, id, id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "异常上报单不存在：" + id);
        Map<String, Object> head = TmsUtil.camelize(heads.get(0));
        head.put("exceptionTypeName", typeName(TmsUtil.str(head.get("exceptionType"))));
        head.put("photos", photoUrls(TmsUtil.str(head.get("reportId"))));
        return ApiResponse.ok(head);
    }

    /**
     * 接手处理（PENDING → HANDLING）。
     * 幂等：条件带 status='PENDING'，重复点击不会刷掉首次接手人与时间。
     */
    @PostMapping("/tms/exception/{id}/handle")
    @Transactional
    public ApiResponse<Map<String, Object>> handle(@PathVariable String id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT report_id, report_no, status FROM tms_exception_report WHERE report_id=? OR report_no=?", id, id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "异常上报单不存在：" + id);
        String reportId = TmsUtil.str(rows.get(0).get("report_id"));
        String reportNo = TmsUtil.str(rows.get(0).get("report_no"));
        String status = TmsUtil.str(rows.get(0).get("status"));
        if ("CLOSED".equals(status)) return ApiResponse.fail("400", "该异常已关闭，不能再接手");

        String handler = TmsUtil.currentUser();
        int n = jdbcTemplate.update("""
                UPDATE tms_exception_report
                   SET status='HANDLING', handler=?, handled_at=?, handle_result=?
                 WHERE report_id=? AND status='PENDING'
                """, handler, Timestamp.valueOf(TmsUtil.now()),
                TmsUtil.str(b.get("handleResult")), reportId);
        if (n > 0) {
            TmsUtil.log(jdbcTemplate, "tms.exception", "HANDLE", reportNo, "调度员接手处理异常");
            // 回执司机：司机上报后最想知道的是「有人管了没」，
            // 只在真正状态流转时发，重复点击不会刷屏
            notifyReporter(reportId, reportNo, TmsNotifyService.LEVEL_NORMAL,
                    "异常已受理 " + reportNo,
                    "调度员 " + handler + " 已接手处理您上报的异常。"
                            + (TmsUtil.str(b.get("handleResult")).isEmpty()
                               ? "" : "处理说明：" + TmsUtil.str(b.get("handleResult"))));
        }
        return ApiResponse.ok(Map.of("reportId", reportId, "status", "HANDLING", "updated", n));
    }

    /**
     * 关闭异常（→ CLOSED）。
     * 处理结论必填：允许空结论关闭等于允许调度员一键清空待办，
     * 异常上报就退化成了打卡走过场。
     */
    @PostMapping("/tms/exception/{id}/close")
    @Transactional
    public ApiResponse<Map<String, Object>> close(@PathVariable String id,
                                                  @RequestBody Map<String, Object> body) {
        String handleResult = TmsUtil.str(body.get("handleResult"));
        if (handleResult.isEmpty()) return ApiResponse.fail("400", "请填写处理结果");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT report_id, report_no, status FROM tms_exception_report WHERE report_id=? OR report_no=?", id, id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "异常上报单不存在：" + id);
        String reportId = TmsUtil.str(rows.get(0).get("report_id"));
        String reportNo = TmsUtil.str(rows.get(0).get("report_no"));
        if ("CLOSED".equals(TmsUtil.str(rows.get(0).get("status")))) {
            return ApiResponse.fail("400", "该异常已关闭");
        }

        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        String handler = TmsUtil.currentUser();
        jdbcTemplate.update("""
                UPDATE tms_exception_report
                   SET status='CLOSED', handle_result=?, closed_at=?,
                       handler = COALESCE(handler, ?), handled_at = COALESCE(handled_at, ?)
                 WHERE report_id=?
                """, handleResult, now, handler, now, reportId);
        TmsUtil.log(jdbcTemplate, "tms.exception", "CLOSE", reportNo, "异常关闭：" + handleResult);
        // 回执司机处理结论。用 IMPORTANT：结论往往含「自行垫付」「改约明日」等
        // 需要司机执行的动作，不能和普通通知一样被划走
        notifyReporter(reportId, reportNo, TmsNotifyService.LEVEL_IMPORTANT,
                "异常已处理完毕 " + reportNo,
                "处理结论：" + handleResult);
        return ApiResponse.ok(Map.of("reportId", reportId, "status", "CLOSED"));
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    /**
     * 给上报人（司机）发处理回执。
     *
     * driver_id 在此单独查一次而非从调用处传：handle/close 原本只 SELECT 了
     * report_id/report_no/status，为发消息去扩大它们的查询列会让业务 SQL 承担
     * 通知的职责，后续维护者容易误删。
     */
    private void notifyReporter(String reportId, String reportNo, String level,
                                String title, String content) {
        try {
            List<String> ids = jdbcTemplate.queryForList(
                    "SELECT driver_id FROM tms_exception_report WHERE report_id = ?", String.class, reportId);
            if (ids.isEmpty()) return;
            notifyService.notifyDriver(ids.get(0), TmsNotifyService.TYPE_EXCEPTION_REPLY,
                    level, title, content, "EXCEPTION", reportId, reportNo);
        } catch (Exception ignore) {
            // 回执失败不影响异常单状态流转
        }
    }

    /**
     * 把司机上报的异常推给调度员。
     *
     * 严重度直接映射消息级别：URGENT 的异常（车辆故障、交通事故）
     * 在调度端要能置顶显示，否则和「包装破损」混在一起就失去了预警价值。
     */
    private void notifyDispatchers(String reportNo, String exceptionType, String severity,
                                   String driverName, String customerName,
                                   String description, String reportId) {
        try {
            List<String> users = notifyService.findDispatcherUsernames();
            if (users.isEmpty()) return;
            String level = "URGENT".equals(severity) ? TmsNotifyService.LEVEL_URGENT
                    : TmsNotifyService.LEVEL_IMPORTANT;
            String title = "司机异常上报：" + typeName(exceptionType)
                    + (driverName.isEmpty() ? "" : "（" + driverName + "）");
            StringBuilder content = new StringBuilder("单号 " + reportNo);
            if (!customerName.isEmpty()) content.append("，客户 ").append(customerName);
            if (!description.isEmpty()) content.append("，说明：").append(description);
            notifyService.notifyUsers(users, TmsNotifyService.TYPE_EXCEPTION_ALERT,
                    level, title, content.toString(), "EXCEPTION", reportId, reportNo);
        } catch (Exception ignore) {
            // 告警失败不影响司机上报成功
        }
    }

    /** 异常上报配置项（来自 sys_param_runtime，读取失败时用内置默认值兜底）。 */
    private record ExceptionConfig(boolean photoRequired, Set<String> urgentTypes) {}

    /**
     * 读取异常上报配置。
     *
     * 与 loadArriveConfig 同样的原则：参数表异常一律回退默认值，
     * 配置读取绝不能成为上报失败的原因。
     */
    private ExceptionConfig loadConfig() {
        Map<String, String> kv = new HashMap<>();
        try {
            jdbcTemplate.queryForList("""
                    SELECT param_key, COALESCE(param_value, default_value) AS v
                      FROM sys_param_runtime
                     WHERE param_key IN ('TMS_EXCEPTION_PHOTO_REQUIRED','TMS_EXCEPTION_URGENT_TYPES')
                    """).forEach(r -> kv.put(TmsUtil.str(r.get("param_key")), TmsUtil.str(r.get("v"))));
        } catch (Exception ignore) {
            // 参数未初始化（未跑 V64）时走默认值
        }
        boolean photoRequired = !"false".equalsIgnoreCase(
                TmsUtil.str(kv.getOrDefault("TMS_EXCEPTION_PHOTO_REQUIRED", "true")));
        String raw = kv.getOrDefault("TMS_EXCEPTION_URGENT_TYPES", "TRAFFIC_ACCIDENT,VEHICLE_FAULT");
        Set<String> urgent = new LinkedHashSet<>();
        for (String s : raw.split(",")) {
            String t = s.trim().toUpperCase();
            if (!t.isEmpty()) urgent.add(t);
        }
        return new ExceptionConfig(photoRequired, urgent);
    }

    /** 落现场照片，返回实际写入张数。 */
    private int savePhotos(String reportId, List<Map<String, Object>> photos) {
        int saved = 0;
        for (Map<String, Object> p : photos) {
            String url = TmsUtil.str(p.get("url"));
            if (url.isEmpty()) continue;
            String photoId = TmsUtil.uuid("SP");
            jdbcTemplate.update("""
                    INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, 'EXCEPTION', ?, ?)
                    """, photoId, reportId, url, "exception/" + reportId + "/" + photoId);
            saved++;
        }
        return saved;
    }

    /** 读取某异常单的照片 URL 列表。 */
    private List<String> photoUrls(String reportId) {
        if (reportId.isEmpty()) return List.of();
        return jdbcTemplate.queryForList("""
                SELECT photo_url FROM tms_sign_photo
                 WHERE sign_id = ? AND photo_type = 'EXCEPTION'
                 ORDER BY create_time
                """, reportId).stream().map(r -> TmsUtil.str(r.get("photo_url"))).toList();
    }

    /** 异常类型中文名（未知类型原样返回，避免字典漏配导致列表空白）。 */
    private static String typeName(String code) {
        for (Map<String, Object> t : EXCEPTION_TYPES) {
            if (code.equals(t.get("code"))) return String.valueOf(t.get("name"));
        }
        return code;
    }

    /**
     * 解析客户端上报时间，非法或缺失时取当前时间。
     * 离线补传场景必须保留司机现场的时间戳，但也不能因为客户端时钟乱设就写失败。
     */
    private static Timestamp parseReportedAt(String raw) {
        if (!raw.isEmpty()) {
            try {
                return Timestamp.valueOf(LocalDateTime.parse(raw.replace('T', ' '), TmsUtil.DT_FMT));
            } catch (Exception ignore) {
                // 格式不合法时退回服务端时间
            }
        }
        return Timestamp.valueOf(TmsUtil.now());
    }

    /** 弱关联字段空串转 NULL，避免 '' 污染索引与 LEFT JOIN。 */
    private static String nullIfEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
