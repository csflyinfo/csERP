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
 * TMS 调度管理（P1 核心 + V1.2 退货单取货任务融入）。
 *
 * 接口：
 *   POST /tms/dispatch/pool            待调度发货单池（含已安排调度退货单取货任务）
 *   POST /tms/dispatch/create          创建调度单（勾选发货单+退货单 → 生成 dispatch/detail/trip）
 *   POST /tms/dispatch/assign          分配司机/车辆（含退货单自动匹配提示）
 *   POST /tms/dispatch/cancel          取消调度（回退发货单/退货单状态）
 *   POST /tms/dispatch/page            调度单列表
 *   POST /tms/dispatch/detail          调度单详情（明细 + 行程）
 *   POST /tms/dispatch/sort            调整配送顺序
 *   POST /tms/dispatch/today-summary   今日总览
 */
@RestController
@RequestMapping("/tms/dispatch")
public class TmsDispatchController {

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;
    private final TmsNotifyService notifyService;

    public TmsDispatchController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen,
                                 TmsNotifyService notifyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
        this.notifyService = notifyService;
    }

    /**
     * 待调度发货单池（含已安排调度退货单取货任务）。
     * 发货单来源：sales_receipt status=APPROVED 且 dispatch_status=UNDISPATCHED
     * 退货单来源：sales_return_apply return_type=DRIVER 且 logistics_status=已安排调度
     */
    @PostMapping("/pool")
    public ApiResponse<Map<String, Object>> pool(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> filters = body == null ? Map.of() : body;
        List<Object> args = new ArrayList<>();
        StringBuilder receiptSql = new StringBuilder("""
                SELECT r.receipt_no, r.source_outbound_no, r.source_order_no, r.customer_code, r.customer_name,
                       r.warehouse, r.receipt_date, r.deliver_amount AS final_amount,
                       r.receive_status, r.dispatch_status,
                       c.route_line, c.territory, c.address_detail, c.longitude, c.latitude
                FROM sales_receipt r
                LEFT JOIN base_customer c ON c.customer_code = r.customer_code
                WHERE r.status <> 'CANCELLED' AND r.sign_status = '待签收' AND r.dispatch_status = 'UNDISPATCHED'
                """);
        String customer = TmsUtil.str(filters.get("customer"));
        if (!customer.isEmpty()) { receiptSql.append(" AND (r.customer_code LIKE ? OR r.customer_name LIKE ?)"); args.add("%" + customer + "%"); args.add("%" + customer + "%"); }
        String routeLine = TmsUtil.str(filters.get("routeLine"));
        if (!routeLine.isEmpty()) { receiptSql.append(" AND c.route_line = ?"); args.add(routeLine); }
        String territory = TmsUtil.str(filters.get("territory"));
        if (!territory.isEmpty()) { receiptSql.append(" AND c.territory = ?"); args.add(territory); }
        receiptSql.append(" ORDER BY r.receipt_date DESC, r.receipt_no DESC");
        List<Map<String, Object>> receipts = TmsUtil.queryCamel(jdbcTemplate, receiptSql.toString(), args.toArray());
        for (Map<String, Object> r : receipts) {
            r.put("billType", "RECEIPT");
            r.put("billTypeText", "发货");
            // 发货件数/SKU 数从明细聚合
            Map<String, Object> agg = jdbcTemplate.queryForList(
                    "SELECT COALESCE(SUM(qty),0) AS qty, COUNT(DISTINCT goods_code) AS sku FROM sales_receipt_detail WHERE receipt_id = (SELECT receipt_id FROM sales_receipt WHERE receipt_no = ?)",
                    r.get("receiptNo")).stream().findFirst().orElse(Map.of("QTY", 0, "SKU", 0));
            r.put("qty", TmsUtil.toBd(((Map<?, ?>) agg).get("QTY")));
            r.put("skuCount", TmsUtil.toInt(((Map<?, ?>) agg).get("SKU")));
        }

        // 已安排调度退货单（取货任务）
        List<Map<String, Object>> returns = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT a.apply_no, a.customer_code, a.customer_name, a.warehouse, a.bill_date,
                       a.qty AS return_qty, a.return_reason, a.logistics_status, a.arrange_time,
                       c.route_line, c.territory, c.address_detail, c.longitude, c.latitude
                FROM sales_return_apply a
                LEFT JOIN base_customer c ON c.customer_code = a.customer_code
                WHERE a.return_type = 'DRIVER' AND a.logistics_status = '已安排调度'
                ORDER BY a.arrange_time DESC
                """);
        for (Map<String, Object> r : returns) {
            r.put("billType", "RETURN");
            r.put("billTypeText", "取退");
            r.put("qty", r.get("returnQty"));
        }

        // 统计
        BigDecimal totalQty = receipts.stream().map(x -> TmsUtil.toBd(x.get("qty"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = receipts.stream().map(x -> TmsUtil.toBd(x.get("finalAmount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        int storeCount = (int) receipts.stream().map(x -> TmsUtil.str(x.get("customerCode"))).distinct().count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("receipts", receipts);
        result.put("returns", returns);
        result.put("summary", Map.of(
                "receiptCount", receipts.size(),
                "returnCount", returns.size(),
                "totalQty", totalQty,
                "totalAmount", totalAmount,
                "storeCount", storeCount
        ));
        return ApiResponse.ok(result);
    }

    /**
     * 创建调度单：勾选发货单 + 退货单 → 生成 tms_dispatch + tms_dispatch_detail + tms_delivery_trip。
     * 入参：dispatchDate, routeLine, driverId, driverName, vehiclePlate, vehicleType, loadCapacity,
     *      receiptNos:[], returnNos:[], remark
     * 同时回写 sales_receipt.dispatch_status=DISPATCHED，sales_return_apply.logistics_status=已调度。
     */
    @PostMapping("/create")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        LocalDate dispatchDate = TmsUtil.date(body.get("dispatchDate"));
        String routeLine = TmsUtil.str(body.get("routeLine"));
        String driverId = TmsUtil.str(body.get("driverId"));
        String driverName = TmsUtil.str(body.get("driverName"));
        String vehiclePlate = TmsUtil.str(body.get("vehiclePlate"));
        String vehicleType = TmsUtil.str(body.get("vehicleType"));
        BigDecimal loadCapacity = TmsUtil.toBd(body.get("loadCapacity"));
        String remark = TmsUtil.str(body.get("remark"));
        if (driverId.isEmpty()) return ApiResponse.fail("400", "请选择司机");

        @SuppressWarnings("unchecked")
        List<String> receiptNos = body.get("receiptNos") == null ? List.of() : (List<String>) body.get("receiptNos");
        @SuppressWarnings("unchecked")
        List<String> returnNos = body.get("returnNos") == null ? List.of() : (List<String>) body.get("returnNos");
        if (receiptNos.isEmpty() && returnNos.isEmpty()) return ApiResponse.fail("400", "请至少选择一张发货单或退货单");

        String dispatchId = TmsUtil.uuid("TD");
        String dispatchNo = billNoGen.nextNo(BillNoGenerator.BillType.TMS_DISPATCH, "tms_dispatch", "dispatch_no");
        String tripId = TmsUtil.uuid("XT");
        String tripNo = billNoGen.nextNo(BillNoGenerator.BillType.TMS_TRIP, "tms_delivery_trip", "trip_no");

        // 聚合统计
        BigDecimal loadedQty = BigDecimal.ZERO;
        BigDecimal returnQty = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        Set<String> storeSet = new LinkedHashSet<>();
        String territory = "";

        int seq = 1;
        // 发货单明细行
        for (String receiptNo : receiptNos) {
            List<Map<String, Object>> rs = jdbcTemplate.queryForList("""
                    SELECT r.receipt_id, r.receipt_no, r.customer_code, r.customer_name,
                           r.deliver_amount AS final_amount,
                           c.territory, c.route_line, c.address_detail,
                           (SELECT COALESCE(SUM(qty),0) FROM sales_receipt_detail d WHERE d.receipt_id = r.receipt_id) AS qty,
                           (SELECT COUNT(DISTINCT goods_code) FROM sales_receipt_detail d WHERE d.receipt_id = r.receipt_id) AS sku
                    FROM sales_receipt r LEFT JOIN base_customer c ON c.customer_code = r.customer_code
                    WHERE r.receipt_no = ?
                    """, receiptNo);
            if (rs.isEmpty()) continue;
            Map<String, Object> r = rs.get(0);
            BigDecimal qty = TmsUtil.toBd(r.get("qty"));
            loadedQty = loadedQty.add(qty);
            amount = amount.add(TmsUtil.toBd(r.get("final_amount")));
            storeSet.add(TmsUtil.str(r.get("customer_code")));
            if (territory.isEmpty()) territory = TmsUtil.str(r.get("territory"));
            insertDetail(dispatchId, "RECEIPT", receiptNo, TmsUtil.str(r.get("receipt_id")), r, qty, TmsUtil.toInt(r.get("sku")), seq++);
            // 回写发货单调度状态
            jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='DISPATCHED', dispatch_id=?, trip_id=? WHERE receipt_no=?",
                    dispatchId, tripId, receiptNo);
        }
        // 退货单取货任务行
        for (String applyNo : returnNos) {
            List<Map<String, Object>> rs = jdbcTemplate.queryForList("""
                    SELECT a.apply_id, a.apply_no, a.customer_code, a.customer_name, a.qty,
                           c.territory, c.route_line, c.address_detail
                    FROM sales_return_apply a LEFT JOIN base_customer c ON c.customer_code = a.customer_code
                    WHERE a.apply_no = ? AND a.return_type='DRIVER' AND a.logistics_status='已安排调度'
                    """, applyNo);
            if (rs.isEmpty()) continue;
            Map<String, Object> r = rs.get(0);
            BigDecimal qty = TmsUtil.toBd(r.get("qty"));
            returnQty = returnQty.add(qty);
            storeSet.add(TmsUtil.str(r.get("customer_code")));
            insertDetail(dispatchId, "RETURN", applyNo, TmsUtil.str(r.get("apply_id")), r, qty, 0, seq++);
            // 回写退货单物流状态 → 已调度
            jdbcTemplate.update("""
                    UPDATE sales_return_apply SET logistics_status='已调度', driver_id=?, driver_name=?, dispatch_id=?, trip_id=?
                    WHERE apply_no=?
                    """, driverId, driverName, dispatchId, tripId, applyNo);
        }

        // 调度单主表
        jdbcTemplate.update("""
                INSERT INTO tms_dispatch(dispatch_id, dispatch_no, dispatch_date, route_line, territory,
                    driver_id, driver_name, vehicle_plate, vehicle_type, load_capacity,
                    loaded_qty, return_qty, store_count, amount, status, arrange_user, arrange_time,
                    creator_name, create_time, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ASSIGNED', ?, ?, ?, CURRENT_TIMESTAMP, ?)
                """, dispatchId, dispatchNo, dispatchDate, routeLine, territory,
                driverId, driverName, vehiclePlate, vehicleType, loadCapacity,
                loadedQty, returnQty, storeSet.size(), amount, TmsUtil.currentUser(), Timestamp.valueOf(TmsUtil.now()),
                TmsUtil.currentUser(), remark);

        // 配送行程
        jdbcTemplate.update("""
                INSERT INTO tms_delivery_trip(trip_id, trip_no, dispatch_id, driver_id, driver_name, vehicle_plate,
                    route_line, trip_date, status, total_store, total_qty, create_time, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?, ?, CURRENT_TIMESTAMP, ?)
                """, tripId, tripNo, dispatchId, driverId, driverName, vehiclePlate,
                routeLine, dispatchDate, storeSet.size(), loadedQty, remark);

        TmsUtil.log(jdbcTemplate, "tms.dispatch", "CREATE", dispatchNo,
                "创建调度单：" + dispatchNo + "，" + receiptNos.size() + "发货 + " + returnNos.size() + "退货，司机" + driverName);
        return ApiResponse.ok(Map.of(
                "dispatchId", dispatchId, "dispatchNo", dispatchNo,
                "tripId", tripId, "tripNo", tripNo,
                "storeCount", storeSet.size(),
                "loadedQty", loadedQty, "returnQty", returnQty, "amount", amount
        ));
    }

    @SuppressWarnings("unchecked")
    private void insertDetail(String dispatchId, String billType, String billNo, String billId,
                              Map<String, Object> r, BigDecimal qty, int skuCount, int seq) {
        Map<String, Object> row = (Map<String, Object>) (Map<?, ?>) r;
        jdbcTemplate.update("""
                INSERT INTO tms_dispatch_detail(detail_id, dispatch_id, bill_type, source_bill_no, source_bill_id,
                    customer_code, customer_name, customer_address, territory, route_line, qty, sku_count, seq_no, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """, TmsUtil.uuid("TDD"), dispatchId, billType, billNo, billId,
                TmsUtil.str(row.get("customer_code")), TmsUtil.str(row.get("customer_name")),
                TmsUtil.str(row.get("address_detail")), TmsUtil.str(row.get("territory")),
                TmsUtil.str(row.get("route_line")), qty, skuCount, seq);
    }

    /**
     * 分配/调整司机车辆（含退货单自动匹配提示）。
     * 入参：dispatchId, driverId, driverName, vehiclePlate...
     * 若传 autoMatchReturn=true，返回该调度单内发货单客户下待匹配退货单。
     */
    @PostMapping("/assign")
    @Transactional
    public ApiResponse<Map<String, Object>> assign(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String driverId = TmsUtil.str(body.get("driverId"));
        String driverName = TmsUtil.str(body.get("driverName"));
        String vehiclePlate = TmsUtil.str(body.get("vehiclePlate"));
        if (dispatchId.isEmpty() || driverId.isEmpty()) return ApiResponse.fail("400", "调度单、司机不能为空");
        jdbcTemplate.update("""
                UPDATE tms_dispatch SET driver_id=?, driver_name=?, vehicle_plate=?, status='ASSIGNED', arrange_user=?, arrange_time=?
                WHERE dispatch_id=?
                """, driverId, driverName, vehiclePlate, TmsUtil.currentUser(), Timestamp.valueOf(TmsUtil.now()), dispatchId);
        jdbcTemplate.update("UPDATE tms_delivery_trip SET driver_id=?, driver_name=?, vehicle_plate=? WHERE dispatch_id=?",
                driverId, driverName, vehiclePlate, dispatchId);

        // 自动匹配：该调度单内发货单客户下的已安排调度退货单
        List<Map<String, Object>> matched = List.of();
        if (Boolean.TRUE.equals(body.get("autoMatchReturn"))) {
            matched = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT a.apply_no, a.customer_code, a.customer_name, a.qty AS return_qty, a.return_reason, a.logistics_status
                    FROM sales_return_apply a
                    WHERE a.return_type='DRIVER' AND a.logistics_status='已安排调度'
                      AND a.customer_code IN (SELECT customer_code FROM tms_dispatch_detail WHERE dispatch_id=? AND bill_type='RECEIPT')
                    ORDER BY a.arrange_time DESC
                    """, dispatchId);
        }
        TmsUtil.log(jdbcTemplate, "tms.dispatch", "ASSIGN", dispatchId, "调度单分配司机：" + driverName);

        // 通知司机有新任务。放在业务写库之后，且发送服务内部吞异常，
        // 不会因为发消息失败导致派单回滚（本方法有 @Transactional）
        notifyNewTask(dispatchId, driverId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchId", dispatchId);
        result.put("driverName", driverName);
        result.put("matchedReturns", matched);
        return ApiResponse.ok(result);
    }

    /** 取消调度：回退发货单 dispatch_status=UNDISPATCHED，退货单 logistics_status=已安排调度。 */
    @PostMapping("/cancel")
    @Transactional
    public ApiResponse<Map<String, Object>> cancel(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "调度单号不能为空");
        List<Map<String, Object>> d = jdbcTemplate.queryForList("SELECT status FROM tms_dispatch WHERE dispatch_id=?", dispatchId);
        if (d.isEmpty()) return ApiResponse.fail("404", "调度单不存在");
        String status = TmsUtil.str(d.get(0).get("status"));
        if ("COMPLETED".equals(status)) return ApiResponse.fail("400", "已完成的调度单不可取消");
        // 回退发货单
        jdbcTemplate.update("""
                UPDATE sales_receipt SET dispatch_status='UNDISPATCHED', dispatch_id=NULL, trip_id=NULL
                WHERE receipt_no IN (SELECT source_bill_no FROM tms_dispatch_detail WHERE dispatch_id=? AND bill_type='RECEIPT')
                """, dispatchId);
        // 回退退货单 → 已安排调度（回到调度池）
        jdbcTemplate.update("""
                UPDATE sales_return_apply SET logistics_status='已安排调度', driver_id=NULL, driver_name=NULL, dispatch_id=NULL, trip_id=NULL
                WHERE apply_no IN (SELECT source_bill_no FROM tms_dispatch_detail WHERE dispatch_id=? AND bill_type='RETURN')
                """, dispatchId);
        jdbcTemplate.update("UPDATE tms_dispatch SET status='CANCELLED' WHERE dispatch_id=?", dispatchId);
        jdbcTemplate.update("UPDATE tms_delivery_trip SET status='CANCELLED' WHERE dispatch_id=?", dispatchId);
        TmsUtil.log(jdbcTemplate, "tms.dispatch", "CANCEL", dispatchId, "取消调度单，发货单/退货单状态回退");
        return ApiResponse.ok(Map.of("dispatchId", dispatchId, "status", "CANCELLED"));
    }

    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT dispatch_id, dispatch_no, dispatch_date, route_line, territory, driver_name, vehicle_plate,
                       loaded_qty, return_qty, store_count, amount, status, arrange_user, arrange_time, create_time, remark
                FROM tms_dispatch WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String dispatchNo = TmsUtil.str(filters.get("dispatchNo"));
        if (!dispatchNo.isEmpty()) { sql.append(" AND dispatch_no LIKE ?"); args.add("%" + dispatchNo + "%"); }
        String status = TmsUtil.str(filters.get("status"));
        if (!status.isEmpty()) { sql.append(" AND status = ?"); args.add(status); }
        String driverName = TmsUtil.str(filters.get("driverName"));
        if (!driverName.isEmpty()) { sql.append(" AND driver_name LIKE ?"); args.add("%" + driverName + "%"); }
        String routeLine = TmsUtil.str(filters.get("routeLine"));
        if (!routeLine.isEmpty()) { sql.append(" AND route_line = ?"); args.add(routeLine); }
        sql.append(" ORDER BY create_time DESC, dispatch_no DESC");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) r.put("statusText", resolveDispatchStatus(TmsUtil.str(r.get("status"))));
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @PostMapping("/detail")
    public ApiResponse<Map<String, Object>> detail(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        List<Map<String, Object>> d = jdbcTemplate.queryForList("SELECT * FROM tms_dispatch WHERE dispatch_id=?", dispatchId);
        if (d.isEmpty()) return ApiResponse.fail("404", "调度单不存在");
        Map<String, Object> head = TmsUtil.camelize(d.get(0));
        head.put("statusText", resolveDispatchStatus(TmsUtil.str(head.get("status"))));
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate,
                "SELECT * FROM tms_dispatch_detail WHERE dispatch_id=? ORDER BY seq_no", dispatchId);
        for (Map<String, Object> r : details) r.put("billTypeText", "RETURN".equals(TmsUtil.str(r.get("billType"))) ? "取退" : "发货");
        List<Map<String, Object>> trips = TmsUtil.queryCamel(jdbcTemplate,
                "SELECT * FROM tms_delivery_trip WHERE dispatch_id=?", dispatchId);
        head.put("details", details);
        head.put("trips", trips);
        return ApiResponse.ok(head);
    }

    /** 调整配送顺序：入参 dispatchId, orders:[{detailId, seqNo}] */
    @PostMapping("/sort")
    @Transactional
    public ApiResponse<Map<String, Object>> sort(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = body.get("orders") == null ? List.of() : (List<Map<String, Object>>) body.get("orders");
        for (Map<String, Object> o : orders) {
            jdbcTemplate.update("UPDATE tms_dispatch_detail SET seq_no=? WHERE detail_id=? AND dispatch_id=?",
                    TmsUtil.toInt(o.get("seqNo")), TmsUtil.str(o.get("detailId")), dispatchId);
        }
        return ApiResponse.ok(Map.of("dispatchId", dispatchId, "updated", orders.size()));
    }

    @PostMapping("/today-summary")
    public ApiResponse<Map<String, Object>> todaySummary(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> filters = body == null ? Map.of() : body;
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM tms_dispatch WHERE dispatch_date = CURRENT_DATE");
        String driverId = TmsUtil.str(filters.get("driverId"));
        if (!driverId.isEmpty()) { sql.append(" AND driver_id=?"); args.add(driverId); }
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        int total = rows.size();
        long completed = rows.stream().filter(r -> "COMPLETED".equals(TmsUtil.str(r.get("status")))).count();
        long delivering = rows.stream().filter(r -> Set.of("LOADED","DEPARTED","DELIVERING").contains(TmsUtil.str(r.get("status")))).count();
        long assigned = rows.stream().filter(r -> "ASSIGNED".equals(TmsUtil.str(r.get("status")))).count();
        BigDecimal loadedQty = rows.stream().map(r -> TmsUtil.toBd(r.get("loadedQty"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        int storeCount = rows.stream().mapToInt(r -> TmsUtil.toInt(r.get("storeCount"))).sum();
        return ApiResponse.ok(Map.of(
                "total", total, "completed", completed, "delivering", delivering, "assigned", assigned,
                "loadedQty", loadedQty, "storeCount", storeCount
        ));
    }

    /**
     * 给司机发「新任务」消息。
     *
     * 标题里带上门店数与单量：司机在通知栏一眼就能判断今天工作量，
     * 不必点进 APP 逐单数。查不到调度单信息时用兜底文案，不阻断派单。
     */
    private void notifyNewTask(String dispatchId, String driverId) {
        String dispatchNo = dispatchId;
        String summary = "";
        try {
            List<Map<String, Object>> d = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT dispatch_no, dispatch_date, store_count, loaded_qty, return_qty, vehicle_plate
                      FROM tms_dispatch WHERE dispatch_id = ?
                    """, dispatchId);
            if (!d.isEmpty()) {
                Map<String, Object> r = d.get(0);
                if (!TmsUtil.str(r.get("dispatchNo")).isEmpty()) dispatchNo = TmsUtil.str(r.get("dispatchNo"));
                summary = "配送日期 " + TmsUtil.str(r.get("dispatchDate"))
                        + "，门店 " + TmsUtil.toInt(r.get("storeCount")) + " 家"
                        + "，配送 " + TmsUtil.toBd(r.get("loadedQty")) + " 件";
                // 取货件数为 0 时不显示，避免「取货 0 件」这种噪音
                if (TmsUtil.toBd(r.get("returnQty")).signum() > 0) {
                    summary += "，取货 " + TmsUtil.toBd(r.get("returnQty")) + " 件";
                }
                summary += "，车辆 " + TmsUtil.str(r.get("vehiclePlate"));
            }
        } catch (Exception ignore) {
            // 摘要拿不到也要把消息发出去，司机至少知道有新任务
        }
        notifyService.notifyDriver(driverId, TmsNotifyService.TYPE_NEW_TASK,
                TmsNotifyService.LEVEL_IMPORTANT,
                "新配送任务 " + dispatchNo,
                summary.isEmpty() ? "您有一张新的配送任务，请及时查看。" : summary,
                "DISPATCH", dispatchId, dispatchNo);
    }

    private String resolveDispatchStatus(String status) {
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "ASSIGNED" -> "已分配";
            case "LOADED" -> "已装车";
            case "DEPARTED" -> "已发车";
            case "DELIVERING" -> "配送中";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }
}
