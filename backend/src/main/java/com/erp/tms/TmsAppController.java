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
import java.time.LocalDate;
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
 *   POST /tms/app/return/sign    退货签收（按行回写 signed_qty，联动退货单/入库单/应收）
 *   POST /tms/app/trip/history   历史配送任务（仅本人，默认近 30 天）
 *   POST /tms/app/driver/stats   司机绩效统计（真实单据聚合）
 *   POST /tms/app/collect/records 收款记录（门店级签收流水，仅本人）
 *
 * 鉴权：复用 JwtAuthFilter，登录时 subject=driverId，roleCode=DRIVER。
 */
@RestController
@RequestMapping("/tms/app")
public class TmsAppController {

    private final JdbcTemplate jdbcTemplate;
    private final TmsAuthService authService;
    private final com.erp.sales.SalesReturnController salesReturnController;

    public TmsAppController(JdbcTemplate jdbcTemplate, TmsAuthService authService,
                            com.erp.sales.SalesReturnController salesReturnController) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
        this.salesReturnController = salesReturnController;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String mobile = TmsUtil.str(body.get("mobile"));
        String verifyCode = TmsUtil.str(body.get("verifyCode"));
        return ApiResponse.ok(authService.login(mobile, verifyCode));
    }

    @PostMapping("/profile")
    public ApiResponse<Map<String, Object>> profile() {
        Map<String, Object> info = new LinkedHashMap<>(authService.getDriverInfo(TmsUtil.currentDriverId()));
        // 附带调度中心联系方式（参数化配置）：系统内无「司机→调度员」归属关系表，
        // 由 TMS_DISPATCHER_PHONE 参数统一维护，留空时 APP 隐藏「联系调度员」入口
        try {
            // 走 queryCamel 统一列名大小写：H2 返回大写、MySQL 返回原样，直接 get 会漏读
            TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT param_key, COALESCE(param_value, default_value) AS param_value
                      FROM sys_param_runtime
                     WHERE param_key IN ('TMS_DISPATCHER_PHONE','TMS_DISPATCHER_NAME')
                    """).forEach(r -> {
                String k = TmsUtil.str(r.get("paramKey"));
                String v = TmsUtil.str(r.get("paramValue"));
                if ("TMS_DISPATCHER_PHONE".equals(k)) info.put("dispatcherPhone", v);
                if ("TMS_DISPATCHER_NAME".equals(k)) info.put("dispatcherName", v);
            });
        } catch (Exception ignore) {
            // 参数未初始化（未跑 V61）时不影响登录后的基本信息展示
        }
        info.putIfAbsent("dispatcherPhone", "");
        info.putIfAbsent("dispatcherName", "调度中心");
        return ApiResponse.ok(info);
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
            // 结算方式取门店档案，应收金额取发货单发货金额（含税）：
            //   tms_dispatch_detail.amount 在排线建单时并未写入（恒为默认 0），不能作为应收来源；
            //   sales_receipt.deliver_amount 才是发货时点定死的金额，签收后的 sign_amount 是结果不是「应收」。
            //   退货单（bill_type=RETURN）没有应收概念，LEFT JOIN 自然为 null，由下方置空。
            List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT dd.detail_id, dd.dispatch_id, dd.bill_type, dd.source_bill_no, dd.customer_code,
                           dd.customer_name, dd.customer_address, dd.qty, dd.sku_count, dd.seq_no, dd.status,
                           dd.arrive_time, dd.arrive_distance, dd.gps_abnormal,
                           c.longitude, c.latitude, c.contact_name, c.mobile AS contact_mobile,
                           c.settlement_type,
                           r.deliver_amount
                    FROM tms_dispatch_detail dd
                    LEFT JOIN base_customer c ON c.customer_code = dd.customer_code
                    LEFT JOIN sales_receipt r ON r.receipt_no = dd.source_bill_no AND dd.bill_type = 'RECEIPT'
                    WHERE dd.dispatch_id = ?
                    ORDER BY dd.seq_no
                    """, dispatchId);
            for (Map<String, Object> r : details) {
                boolean isReturn = "RETURN".equals(TmsUtil.str(r.get("billType")));
                r.put("billTypeText", isReturn ? "取退" : "发货");
                r.put("dispatchNo", d.get("dispatchNo"));
                r.put("vehiclePlate", d.get("vehiclePlate"));
                r.put("settlementText", TmsUtil.settlementText(r.get("settlementType")));
                r.put("needCollect", !isReturn && TmsUtil.needCollect(r.get("settlementType")));
                // 统一字段名 receivableAmount：语义是「本次上门该收多少」，取货任务恒为 0
                r.put("receivableAmount", isReturn ? BigDecimal.ZERO : TmsUtil.toBd(r.remove("deliverAmount")));
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

    /**
     * 司机绩效统计（APP「我的」页）。
     *
     * 全部来自真实单据聚合，不做任何估算或补数：
     *   - 累计配送门店数 / 件数 / 收款额：按 tms_dispatch_detail 已签收行统计
     *   - 签收完成率：已签收门店 / 已分配门店（拒收计入分母不计入分子）
     *   - 打卡正常率：GPS 未异常的打卡 / 有打卡记录的门店
     *
     * 关于原型里的「准时率」与「4.9 评分」：
     *   现有表结构没有「计划到店时间」字段，也没有客户评价表，
     *   两项均无数据来源。为避免展示编造数字，改为返回上述可核对口径，
     *   待后续排线支持时间窗 / 上线评价功能后再补。
     *
     * 入参：days（可选，统计窗口天数，默认 0 表示不限期即累计至今）
     */
    @PostMapping("/driver/stats")
    public ApiResponse<Map<String, Object>> driverStats(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> params = body == null ? Map.of() : body;
        String driverId = TmsUtil.currentDriverId();

        List<Object> args = new ArrayList<>();
        args.add(driverId);
        // 统计窗口：默认累计至今；传 days 则只看近 N 天（按调度日期过滤，走 dispatch_date 索引）
        String dateFilter = "";
        int days = TmsUtil.toInt(params.get("days"));
        if (days > 0) {
            dateFilter = " AND d.dispatch_date >= ?";
            args.add(LocalDate.now().minusDays(days).toString());
        }

        // 一次聚合出全部计数，避免多次往返；SUM(CASE WHEN) 在 H2/MySQL 下语义一致。
        // 必须走 queryCamel：H2 返回大写列名，直接按下划线取值会静默得到 null → 统计全为 0
        String sql = """
                SELECT COUNT(*) AS total_store,
                       SUM(CASE WHEN dd.status IN ('DELIVERED','PARTIAL','RETURNED') THEN 1 ELSE 0 END) AS signed_store,
                       SUM(CASE WHEN dd.status = 'REJECTED' THEN 1 ELSE 0 END) AS reject_store,
                       SUM(CASE WHEN dd.status IN ('DELIVERED','PARTIAL','RETURNED') THEN dd.qty ELSE 0 END) AS signed_qty,
                       SUM(CASE WHEN dd.arrive_time IS NOT NULL THEN 1 ELSE 0 END) AS arrive_store,
                       SUM(CASE WHEN dd.arrive_time IS NOT NULL AND dd.gps_abnormal = 1 THEN 1 ELSE 0 END) AS gps_bad_store
                FROM tms_dispatch_detail dd
                JOIN tms_dispatch d ON d.dispatch_id = dd.dispatch_id
                WHERE d.driver_id = ? AND d.status <> 'CANCELLED'
                """ + dateFilter;
        Map<String, Object> agg = TmsUtil.queryCamel(jdbcTemplate, sql, args.toArray()).get(0);

        // 收款额取签收记录（明细表的 amount 是应收，不等于实收）
        String collectSql = """
                SELECT COALESCE(SUM(s.collect_amount), 0) AS collect_amount, COUNT(*) AS sign_count
                FROM tms_sign_record s
                JOIN tms_dispatch d ON d.dispatch_id = s.dispatch_id
                WHERE d.driver_id = ? AND d.status <> 'CANCELLED'
                """ + dateFilter;
        Map<String, Object> collect = TmsUtil.queryCamel(jdbcTemplate, collectSql, args.toArray()).get(0);

        // 出车次数按已完成行程计，统计窗口与上面保持一致（此表用 trip_date）
        StringBuilder tripSql = new StringBuilder(
                "SELECT COUNT(*) FROM tms_delivery_trip WHERE driver_id = ? AND status = 'COMPLETED'");
        List<Object> tripArgs = new ArrayList<>();
        tripArgs.add(driverId);
        if (days > 0) {
            tripSql.append(" AND trip_date >= ?");
            tripArgs.add(LocalDate.now().minusDays(days).toString());
        }
        Integer tripCount = jdbcTemplate.queryForObject(tripSql.toString(), Integer.class, tripArgs.toArray());

        int totalStore = TmsUtil.toInt(agg.get("totalStore"));
        int signedStore = TmsUtil.toInt(agg.get("signedStore"));
        int arriveStore = TmsUtil.toInt(agg.get("arriveStore"));
        int gpsBadStore = TmsUtil.toInt(agg.get("gpsBadStore"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", days);
        result.put("tripCount", tripCount == null ? 0 : tripCount);
        result.put("totalStore", totalStore);
        result.put("signedStore", signedStore);
        result.put("rejectStore", TmsUtil.toInt(agg.get("rejectStore")));
        result.put("signedQty", TmsUtil.toBd(agg.get("signedQty")));
        result.put("collectAmount", TmsUtil.toBd(collect.get("collectAmount")));
        result.put("signCount", TmsUtil.toInt(collect.get("signCount")));
        // 分母为 0 时返回 null 而非 0，让前端显示「—」，避免新司机被展示成 0% 完成率
        result.put("signRate", totalStore > 0 ? Math.round(signedStore * 1000.0 / totalStore) / 10.0 : null);
        result.put("arriveStore", arriveStore);
        result.put("gpsNormalRate", arriveStore > 0 ? Math.round((arriveStore - gpsBadStore) * 1000.0 / arriveStore) / 10.0 : null);
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
        // LEFT JOIN 门店档案透传坐标与联系人，供 APP 打卡围栏比对与「导航前往/联系客户」（dd.* 已含 arrive_* 字段）
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dd.*, c.longitude, c.latitude, c.contact_name, c.mobile AS contact_mobile
                FROM tms_dispatch_detail dd
                LEFT JOIN base_customer c ON c.customer_code = dd.customer_code
                WHERE dd.dispatch_id = ?
                ORDER BY dd.seq_no
                """, dispatchId);
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
                SELECT detail_id, goods_code, goods_name, spec, unit_name, qty AS return_qty, signed_qty,
                       batch_no, production_date
                FROM sales_return_apply_detail WHERE apply_id = ?
                ORDER BY detail_id
                """, head.get("applyId"));
        head.put("details", details);
        return ApiResponse.ok(head);
    }

    /**
     * 退货签收（司机回收链路的关键节点）。
     * 入参：applyNo, items:[{detailId, goodsCode, signedQty}], customerSigner, remark
     * 前提：logistics_status=已调度 且 driver_id=当前司机
     * <p>
     * V60 起签收不只是打个标记：退货单的回写（按行 signed_qty、重算退货数量/金额、
     * 生成退货入库单、按参数决定是否自动审核写负向应收）统一交给
     * {@code SalesReturnController.onDriverCollected}，本方法只负责 TMS 侧的
     * 司机归属校验、签收记录、照片与调度明细状态，避免两处各写一半口径不一致。
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

        // 1. 退货单回写 + 生成入库单 + 按入账时点决定是否自动审核（逐行数量校验也在里面）
        //    这里刻意不 catch IllegalArgumentException：onDriverCollected 与本方法同处一个事务，
        //    catch 掉再 return fail 只会让事务被标记 rollback-only，提交时抛
        //    「Transaction rolled back because it has been marked as rollback-only」，
        //    掩盖真实校验信息。交给 GlobalExceptionHandler 统一转 400 + 整体回滚。
        Map<String, Object> collected =
                salesReturnController.onDriverCollected(applyId, items, TmsUtil.currentUser());
        BigDecimal totalSigned = TmsUtil.toBd(collected.get("signedQty"));
        BigDecimal returnAmount = TmsUtil.toBd(collected.get("returnAmount"));
        String inboundNo = TmsUtil.str(collected.get("inboundNo"));
        boolean autoAudited = Boolean.TRUE.equals(collected.get("autoAudited"));

        // 2. 写签收记录
        //    列数 15、占位符 14（bill_type 用字面量 'RETURN'）、入参 14，三者必须一一对齐；
        //    dispatch_id / detail_id 允许为空：司机可以不经组车、由「指派司机」直接接单回收（V63 放开非空约束）
        String signId = TmsUtil.uuid("QS");
        jdbcTemplate.update("""
                INSERT INTO tms_sign_record(sign_id, dispatch_id, detail_id, trip_id, source_bill_no,
                    customer_code, customer_name, bill_type, sign_type, signed_qty, sign_time, sign_user,
                    customer_signer, customer_sign_img, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RETURN', ?, ?, ?, ?, ?, ?, ?)
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
                "司机退货签收：实收 " + totalSigned + " 件，退货金额 " + returnAmount
                        + "，物流状态 已调度 → 司机已回收"
                        + (inboundNo.isEmpty() ? "，未生成入库单" : "，入库单 " + inboundNo)
                        + (autoAudited ? "，已按司机回收口径自动审核" : ""));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applyNo", applyNo);
        result.put("logisticsStatus", "司机已回收");
        result.put("signedQty", totalSigned);
        // returnQty 这里是「应退数量」（签收前的单据数量），APP 端用它算差异，语义与 /return/detail 保持一致；
        // 退货单表里的 return_qty 签收后已被改写为实收数量，两者不是同一口径，勿混用
        result.put("returnQty", h.get("qty"));
        result.put("returnAmount", returnAmount);
        result.put("diff", TmsUtil.toBd(h.get("qty")).subtract(totalSigned));
        result.put("inboundNo", inboundNo);
        result.put("autoAudited", autoAudited);
        return ApiResponse.ok(result);
    }

    /**
     * 历史配送任务（APP 端）。
     *
     * 与 ERP 端 /tms/trip/page 的区别：此处强制注入 driver_id = 当前登录司机，
     * 司机无法通过构造入参查看他人任务；且不复用 PageResult.of，
     * 因为它会把 filters 里的日期值当作全文关键字二次过滤，导致结果为空。
     *
     * 入参（均可选）：status(ALL/COMPLETED/...)、dateFrom、dateTo、days(默认 30)、pageNo、pageSize
     * 出参：{records, pageNo, pageSize, total, summary:{tripCount, storeSum, qtySum, amountSum}}
     */
    @PostMapping("/trip/history")
    public ApiResponse<Map<String, Object>> tripHistory(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> params = body == null ? Map.of() : body;
        String driverId = TmsUtil.currentDriverId();

        StringBuilder sql = new StringBuilder("""
                SELECT t.trip_id, t.trip_no, t.dispatch_id, t.vehicle_plate, t.route_line, t.trip_date, t.status,
                       t.total_store, t.delivered_store, t.total_qty, t.delivered_qty, t.collected_amount,
                       t.loading_time, t.depart_time, t.complete_time,
                       d.dispatch_no, d.territory
                FROM tms_delivery_trip t
                LEFT JOIN tms_dispatch d ON d.dispatch_id = t.dispatch_id
                WHERE t.driver_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(driverId);

        String status = TmsUtil.str(params.get("status"));
        if (!status.isEmpty() && !"ALL".equals(status)) {
            sql.append(" AND t.status = ?");
            args.add(status);
        }
        // 日期区间：显式区间优先，否则回退 days 天（默认 30 天），避免全表扫描
        String dateFrom = TmsUtil.str(params.get("dateFrom"));
        String dateTo = TmsUtil.str(params.get("dateTo"));
        if (!dateFrom.isEmpty() || !dateTo.isEmpty()) {
            if (!dateFrom.isEmpty()) {
                sql.append(" AND t.trip_date >= ?");
                args.add(dateFrom);
            }
            if (!dateTo.isEmpty()) {
                sql.append(" AND t.trip_date <= ?");
                args.add(dateTo);
            }
        } else {
            int days = params.get("days") == null ? 30 : TmsUtil.toInt(params.get("days"));
            if (days <= 0) days = 30;
            // 由 Java 计算起始日期，避免 H2 与 MySQL 的日期减法语法差异
            sql.append(" AND t.trip_date >= ?");
            args.add(LocalDate.now().minusDays(days).toString());
        }
        sql.append(" ORDER BY t.trip_date DESC, t.trip_no DESC");

        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());

        int storeSum = 0;
        BigDecimal qtySum = BigDecimal.ZERO;
        BigDecimal amountSum = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            r.put("statusText", resolveTripStatus(TmsUtil.str(r.get("status"))));
            int total = TmsUtil.toInt(r.get("totalStore"));
            int delivered = TmsUtil.toInt(r.get("deliveredStore"));
            r.put("progress", total > 0 ? Math.round(delivered * 100.0 / total) : 0);
            storeSum += delivered;
            qtySum = qtySum.add(TmsUtil.toBd(r.get("deliveredQty")));
            amountSum = amountSum.add(TmsUtil.toBd(r.get("collectedAmount")));
        }

        int pageNo = params.get("pageNo") == null ? 1 : Math.max(1, TmsUtil.toInt(params.get("pageNo")));
        int pageSize = params.get("pageSize") == null ? 20 : TmsUtil.toInt(params.get("pageSize"));
        if (pageSize < 1 || pageSize > 200) pageSize = 20;
        int from = Math.min((pageNo - 1) * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", new ArrayList<>(rows.subList(from, to)));
        result.put("pageNo", pageNo);
        result.put("pageSize", pageSize);
        result.put("total", rows.size());
        // 汇总取全量（非当前页），供 APP 顶部统计条展示
        result.put("summary", Map.of(
                "tripCount", rows.size(),
                "storeSum", storeSum,
                "qtySum", qtySum,
                "amountSum", amountSum
        ));
        return ApiResponse.ok(result);
    }

    /** 行程状态中文映射（与 ERP 端 TmsDeliveryController 保持一致）。 */
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

    /**
     * 收款记录（APP「我的 → 收款记录」）。
     *
     * 数据源是门店级签收流水 tms_sign_record，粒度 = 一次上门一条，
     * 这是司机跟调度对账时唯一能逐笔核对的凭据。为什么不用 tms_delivery_trip.collected_amount：
     * 那是车次级汇总，对不上的时候司机没法定位是哪家门店的钱有问题。
     *
     * 归属判定刻意用 sign_user = driverId（写入时取 TmsUtil.currentUser()，APP 端 subject 就是 driverId），
     * 而不是 JOIN tms_dispatch.driver_id：司机回收型退货支持「直派」路径（V63 放开 dispatch_id 可空），
     * 走 JOIN 会把这批 null 行整体漏掉，司机看到的收款流水就会少账。
     *
     * 只返回真实发生收款的行（collect_amount > 0）：赊账/预付门店签收也会落签收记录，
     * 但金额为 0，混进「收款记录」只会让司机对账时逐条排除噪音。
     *
     * 入参：days（默认 30）/ dateFrom / dateTo / payMethod / pageNo / pageSize
     */
    @PostMapping("/collect/records")
    public ApiResponse<Map<String, Object>> collectRecords(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> params = body == null ? Map.of() : body;
        String driverId = TmsUtil.currentDriverId();

        StringBuilder sql = new StringBuilder("""
                SELECT s.sign_id, s.dispatch_id, s.detail_id, s.source_bill_no, s.customer_code, s.customer_name,
                       s.bill_type, s.sign_type, s.signed_qty, s.reject_qty, s.collect_amount, s.pay_method,
                       s.sign_time, s.customer_signer, s.remark, s.verified, s.verified_at,
                       d.dispatch_no
                FROM tms_sign_record s
                LEFT JOIN tms_dispatch d ON d.dispatch_id = s.dispatch_id
                WHERE s.sign_user = ? AND s.collect_amount > 0
                """);
        List<Object> args = new ArrayList<>();
        args.add(driverId);

        String payMethod = TmsUtil.str(params.get("payMethod"));
        if (!payMethod.isEmpty() && !"ALL".equals(payMethod)) {
            sql.append(" AND s.pay_method = ?");
            args.add(payMethod);
        }
        // 日期区间：显式区间优先，否则回退 days 天（默认 30 天），避免全表扫描。
        // sign_time 是 TIMESTAMP，dateTo 需按「当天 23:59:59」比较，
        // 直接 <= '2026-08-17' 会把当天全部记录漏掉（等价于 <= 当天 00:00:00）。
        String dateFrom = TmsUtil.str(params.get("dateFrom"));
        String dateTo = TmsUtil.str(params.get("dateTo"));
        if (!dateFrom.isEmpty() || !dateTo.isEmpty()) {
            if (!dateFrom.isEmpty()) {
                sql.append(" AND s.sign_time >= ?");
                args.add(dateFrom + " 00:00:00");
            }
            if (!dateTo.isEmpty()) {
                sql.append(" AND s.sign_time <= ?");
                args.add(dateTo + " 23:59:59");
            }
        } else {
            int days = params.get("days") == null ? 30 : TmsUtil.toInt(params.get("days"));
            if (days <= 0) days = 30;
            sql.append(" AND s.sign_time >= ?");
            args.add(LocalDate.now().minusDays(days) + " 00:00:00");
        }
        sql.append(" ORDER BY s.sign_time DESC");

        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());

        // 汇总按收款方式拆分：司机交账时现金要点钞、电子收款只需核对流水，两者必须分开算。
        // 电子收款用「非现金即电子」的补集口径，而不是枚举 微信/支付宝：
        // 将来签收页新增收款方式（如银行转账）时，枚举写法会让这笔钱两边都不落，
        // 出现「合计 ≠ 现金 + 电子」的对账悖论。
        BigDecimal amountSum = BigDecimal.ZERO;
        BigDecimal cashSum = BigDecimal.ZERO;
        BigDecimal onlineSum = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            String pm = TmsUtil.str(r.get("payMethod"));
            BigDecimal amt = TmsUtil.toBd(r.get("collectAmount"));
            amountSum = amountSum.add(amt);
            if ("现金".equals(pm)) cashSum = cashSum.add(amt);
            else onlineSum = onlineSum.add(amt);
            r.put("billTypeText", "RETURN".equals(TmsUtil.str(r.get("billType"))) ? "取退" : "发货");
            r.put("signTypeText", resolveSignType(TmsUtil.str(r.get("signType"))));
            r.put("verifiedText", resolveVerified(TmsUtil.str(r.get("verified"))));
        }

        int pageNo = params.get("pageNo") == null ? 1 : Math.max(1, TmsUtil.toInt(params.get("pageNo")));
        int pageSize = params.get("pageSize") == null ? 20 : TmsUtil.toInt(params.get("pageSize"));
        if (pageSize < 1 || pageSize > 200) pageSize = 20;
        int from = Math.min((pageNo - 1) * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", new ArrayList<>(rows.subList(from, to)));
        result.put("pageNo", pageNo);
        result.put("pageSize", pageSize);
        result.put("total", rows.size());
        // 汇总取全量（非当前页），供 APP 顶部统计条展示
        result.put("summary", Map.of(
                "recordCount", rows.size(),
                "amountSum", amountSum,
                "cashSum", cashSum,
                "onlineSum", onlineSum
        ));
        return ApiResponse.ok(result);
    }

    /** 签收类型中文映射。 */
    private String resolveSignType(String s) {
        return switch (s) {
            case "NORMAL" -> "正常签收";
            case "PARTIAL" -> "部分签收";
            case "REJECT" -> "拒收";
            case "RETURN" -> "退货回收";
            default -> s;
        };
    }

    /**
     * 核销状态中文映射（V57 的 verified 字段）。
     * PENDING 对司机的含义是「调度还没跟你对过这笔账」，所以显示成「待核销」而不是留空，
     * 让司机知道这笔钱还没交割完。
     */
    private String resolveVerified(String s) {
        return switch (s) {
            case "APPROVED" -> "已核销";
            case "REJECTED" -> "核销驳回";
            case "PENDING", "" -> "待核销";
            default -> s;
        };
    }
}
