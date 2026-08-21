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
 *   POST /tms/dispatch/driver-active   查司机未完成调度单（创建前判断新建/追加）
 *   POST /tms/dispatch/pool            待调度发货单池（含已安排调度退货单取货任务）
 *   POST /tms/dispatch/create          创建调度单（勾选发货单+退货单 → 生成 dispatch/detail/trip，支持 parentDispatchId 追加）
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
    private final com.erp.system.SysParamService sysParamService;

    public TmsDispatchController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen,
                                 TmsNotifyService notifyService,
                                 com.erp.system.SysParamService sysParamService) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
        this.notifyService = notifyService;
        this.sysParamService = sysParamService;
    }

    /**
     * 查司机当前未完成的调度单。
     *
     * 用途：调度员创建调度单前先问一句「这司机手上还有活吗」。
     * 有的话前端弹「新建 / 追加」二选一——原来完全不查，
     * 同一个司机可以被并行塞进多张互不相关的 ASSIGNED 单，
     * APP 首页就会同时冒出多张待接单卡片，司机根本分不清哪张是哪趟车。
     *
     * 未完成的口径 = 除 COMPLETED/CANCELLED 之外的全部状态，
     * 而不是只取 DEPARTED/DELIVERING（「配送中」的字面义）：
     * 一张刚派出去还没接单的单同样占着这个司机，
     * 漏掉它就还是会出现两张并行待接单。
     *
     * 追加目标只列 parent_dispatch_id 为空的主单：
     * 追加单本身已经挂在某张主单上，再让它当父节点会把关系搞成多层链，
     * 「同一趟车」的归并口径就不好算了。
     *
     * 同时要求父单与本次创建是同一个配送日（dispatchDate 不传按今天算）。
     * 实测司机手上会残留好几天前忘记收尾的单，
     * 不卡这一层就会把今天的货追加到一趟早跑完的行程里。
     * 遗留单仍在 dispatches 里返回（带 stale=true）并给出 staleCount，
     * 让调度员知道要去清理，但不允许当追加目标。
     *
     * 入参：driverId, dispatchDate(可选)
     */
    @PostMapping("/driver-active")
    public ApiResponse<Map<String, Object>> driverActive(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.str(body.get("driverId"));
        if (driverId.isEmpty()) return ApiResponse.fail("400", "driverId 不能为空");
        // 追加目标只认「本次配送日」的单：司机手上可能残留几天前忘记收尾的单，
        // 把今天的货追加到那种单上，货会挂到一趟早就跑完的行程里。
        // dispatch_date 取出来可能是 DATE 也可能是字符串，统一截前 10 位比对。
        String bizDate = TmsUtil.date(body.get("dispatchDate")).toString();
        // 与 create 里的追加拦截读同一个参数：若只在 create 拦，调度员会先在下拉里
        // 选中已发车的单、填完明细才被拒，白做一遍。这里提前把它们剔出候选。
        boolean appendAfterDepart = sysParamService.getBool("TMS_APPEND_AFTER_DEPART", true);
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dispatch_id, dispatch_no, dispatch_date, route_line, vehicle_plate, status,
                       loaded_qty, return_qty, store_count, amount, parent_dispatch_id,
                       accept_time, depart_time, create_time
                FROM tms_dispatch
                WHERE driver_id = ? AND status NOT IN ('COMPLETED','CANCELLED')
                ORDER BY create_time DESC
                """, driverId);
        for (Map<String, Object> r : rows) {
            String status = TmsUtil.str(r.get("status"));
            r.put("statusText", resolveDispatchStatus(status));
            // 已发车的单在司机端已经进入「配送中」，追加提示语要区分这两种
            boolean departed = Set.of("DEPARTED", "DELIVERING").contains(status);
            r.put("departed", departed);
            // 非本次配送日的历史遗留单不给追加，只在提示里露出数量供调度员去清理
            boolean sameDay = bizDate.equals(TmsUtil.date(r.get("dispatchDate")).toString());
            r.put("stale", !sameDay);
            r.put("canAppend", TmsUtil.str(r.get("parentDispatchId")).isEmpty() && sameDay
                    && (appendAfterDepart || !departed));
            // 未完成配送点数：追加提示里要让调度员看出这趟车还剩多少点
            Integer pending = jdbcTemplate.queryForObject("""
                    SELECT COUNT(DISTINCT customer_code) FROM tms_dispatch_detail
                    WHERE dispatch_id = ? AND status = 'PENDING'
                    """, Integer.class, TmsUtil.str(r.get("dispatchId")));
            r.put("pendingStore", pending == null ? 0 : pending);
        }
        List<Map<String, Object>> appendable = rows.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("canAppend"))).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatches", rows);
        result.put("appendable", appendable);
        result.put("hasActive", !rows.isEmpty());
        result.put("delivering", rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("departed"))));
        result.put("staleCount", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("stale"))).count());
        return ApiResponse.ok(result);
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
                       c.route_line, c.territory, c.shipping_address AS address_detail, c.longitude, c.latitude
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
                       c.route_line, c.territory, c.shipping_address AS address_detail, c.longitude, c.latitude
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
     *      receiptNos:[], returnNos:[], remark, parentDispatchId(可选，追加模式)
     * 同时回写 sales_receipt.dispatch_status=DISPATCHED，sales_return_apply.logistics_status=已调度。
     *
     * 追加模式（parentDispatchId 非空）：
     *   司机已经出车在途，临时又来单。此时**仍然新建一张独立调度单**，
     *   只把 parent_dispatch_id 指回在途单。原因见 V69 迁移注释：
     *   装车/发车是调度单级状态，往 DEPARTED 的单里塞明细会逼着状态机下沉到明细级。
     *   新单独立走 接单→装车→发车，发车后配送点在「配送中」按门店与原单归并，
     *   司机视角就是同一趟车又多了几个点。
     *   司机与车辆强制继承父单：追加的语义就是「加到这趟车上」，
     *   若允许指定别的司机，这个字段的含义就废了。
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
        String parentDispatchId = TmsUtil.str(body.get("parentDispatchId"));

        if (!parentDispatchId.isEmpty()) {
            List<Map<String, Object>> ps = jdbcTemplate.queryForList("""
                    SELECT dispatch_no, status, driver_id, driver_name, driver_mobile,
                           vehicle_plate, vehicle_type, load_capacity, route_line, dispatch_date,
                           parent_dispatch_id
                    FROM tms_dispatch WHERE dispatch_id=?
                    """, parentDispatchId);
            if (ps.isEmpty()) return ApiResponse.fail("404", "追加的目标调度单不存在");
            Map<String, Object> p = ps.get(0);
            String pStatus = TmsUtil.str(p.get("status"));
            // 已收尾的单不能再追加：追加意味着「这趟车还会继续跑」，
            // 往 COMPLETED/CANCELLED 上挂新单只会让关联关系变成误导信息。
            if (Set.of("COMPLETED", "CANCELLED").contains(pStatus)) {
                return ApiResponse.fail("400", "目标调度单已" + resolveDispatchStatus(pStatus) + "，不能追加任务");
            }
            // 发车后追加开关（PRD-26 §5.5，TMS_APPEND_AFTER_DEPART）。
            //
            // 关闭时只拦「已发车」的单，未发车（ASSIGNED/ACCEPTED/LOADED）的追加照旧放行——
            // 这个开关管的是「车已经在路上还能不能塞新单」这件事，装车前追加本来就是正常排单。
            // 默认 Y 保持存量行为不变；管控严格的企业关掉后，司机在途中拿到的任务清单
            // 就与出车时装的货完全一致，便于对账。
            if (!sysParamService.getBool("TMS_APPEND_AFTER_DEPART", true)
                    && TmsAppController.DISPATCH_ON_ROAD.contains(pStatus)) {
                return ApiResponse.fail("400", "目标调度单已发车，当前参数设置不允许发车后追加任务");
            }
            // 后端兜底两道，不能只靠前端的下拉过滤：
            // 1) 不允许挂到追加单上，否则 parent 链变多层，「同一趟车」没法一次查出来
            if (!TmsUtil.str(p.get("parent_dispatch_id")).isEmpty()) {
                return ApiResponse.fail("400", "目标调度单本身是追加单，请选择它的主调度单");
            }
            // 2) 配送日必须一致，否则今天的货会挂到别的日期的行程上
            LocalDate pDate = TmsUtil.date(p.get("dispatch_date"));
            if (!pDate.equals(dispatchDate)) {
                return ApiResponse.fail("400", "目标调度单的配送日期为 " + pDate + "，与本次创建的 " + dispatchDate + " 不一致，不能追加");
            }
            driverId = TmsUtil.str(p.get("driver_id"));
            driverName = TmsUtil.str(p.get("driver_name"));
            if (vehiclePlate.isEmpty()) vehiclePlate = TmsUtil.str(p.get("vehicle_plate"));
            if (vehicleType.isEmpty()) vehicleType = TmsUtil.str(p.get("vehicle_type"));
            if (loadCapacity.signum() == 0) loadCapacity = TmsUtil.toBd(p.get("load_capacity"));
            if (routeLine.isEmpty()) routeLine = TmsUtil.str(p.get("route_line"));
        }
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
                           c.territory, c.route_line, c.shipping_address AS address_detail,
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
            insertDetail(dispatchId, "RECEIPT", receiptNo, TmsUtil.str(r.get("receipt_id")), r, qty,
                    TmsUtil.toBd(r.get("final_amount")), TmsUtil.toInt(r.get("sku")), seq++);
            // 回写发货单调度状态
            jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='DISPATCHED', dispatch_id=?, trip_id=? WHERE receipt_no=?",
                    dispatchId, tripId, receiptNo);
        }
        // 退货单取货任务行
        for (String applyNo : returnNos) {
            List<Map<String, Object>> rs = jdbcTemplate.queryForList("""
                    SELECT a.apply_id, a.apply_no, a.customer_code, a.customer_name, a.qty,
                           COALESCE(a.return_amount, a.amount, 0) AS return_amount,
                           c.territory, c.route_line, c.shipping_address AS address_detail
                    FROM sales_return_apply a LEFT JOIN base_customer c ON c.customer_code = a.customer_code
                    WHERE a.apply_no = ? AND a.return_type='DRIVER' AND a.logistics_status='已安排调度'
                    """, applyNo);
            if (rs.isEmpty()) continue;
            Map<String, Object> r = rs.get(0);
            BigDecimal qty = TmsUtil.toBd(r.get("qty"));
            returnQty = returnQty.add(qty);
            storeSet.add(TmsUtil.str(r.get("customer_code")));
            // 退货金额按正数存明细，负向语义由 bill_type='RETURN' 表达，
            // 不存负数是为了让 SUM(amount) WHERE bill_type='RETURN' 直接可用。
            insertDetail(dispatchId, "RETURN", applyNo, TmsUtil.str(r.get("apply_id")), r, qty,
                    TmsUtil.toBd(r.get("return_amount")), 0, seq++);
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
                    creator_name, create_time, remark, parent_dispatch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ASSIGNED', ?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
                """, dispatchId, dispatchNo, dispatchDate, routeLine, territory,
                driverId, driverName, vehiclePlate, vehicleType, loadCapacity,
                loadedQty, returnQty, storeSet.size(), amount, TmsUtil.currentUser(), Timestamp.valueOf(TmsUtil.now()),
                TmsUtil.currentUser(), remark, parentDispatchId.isEmpty() ? null : parentDispatchId);

        // 配送行程
        jdbcTemplate.update("""
                INSERT INTO tms_delivery_trip(trip_id, trip_no, dispatch_id, driver_id, driver_name, vehicle_plate,
                    route_line, trip_date, status, total_store, total_qty, create_time, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?, ?, CURRENT_TIMESTAMP, ?)
                """, tripId, tripNo, dispatchId, driverId, driverName, vehiclePlate,
                routeLine, dispatchDate, storeSet.size(), loadedQty, remark);

        TmsUtil.log(jdbcTemplate, "tms.dispatch", "CREATE", dispatchNo,
                (parentDispatchId.isEmpty() ? "创建调度单：" : "追加调度单：") + dispatchNo + "，"
                        + receiptNos.size() + "发货 + " + returnNos.size() + "退货，司机" + driverName);

        // 通知司机有新任务。原来 /create 不发推送，只有 /assign 发；
        // 但 /assign 前端零调用，实际派单走的就是 /create，
        // 于是司机只能靠自己打开 APP 才知道有活——追加任务场景下这尤其致命：
        // 司机已经在路上，不主动刷新就永远不知道多了配送点。
        // notifyNewTask 内部吞异常，发失败不会回滚派单。
        notifyNewTask(dispatchId, driverId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchId", dispatchId);
        result.put("dispatchNo", dispatchNo);
        result.put("tripId", tripId);
        result.put("tripNo", tripNo);
        result.put("storeCount", storeSet.size());
        result.put("loadedQty", loadedQty);
        result.put("returnQty", returnQty);
        result.put("amount", amount);
        result.put("appended", !parentDispatchId.isEmpty());
        result.put("parentDispatchId", parentDispatchId);
        return ApiResponse.ok(result);
    }

    @SuppressWarnings("unchecked")
    private void insertDetail(String dispatchId, String billType, String billNo, String billId,
                              Map<String, Object> r, BigDecimal qty, BigDecimal amt, int skuCount, int seq) {
        Map<String, Object> row = (Map<String, Object>) (Map<?, ?>) r;
        jdbcTemplate.update("""
                INSERT INTO tms_dispatch_detail(detail_id, dispatch_id, bill_type, source_bill_no, source_bill_id,
                    customer_code, customer_name, customer_address, territory, route_line, qty, amount, sku_count, seq_no, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """, TmsUtil.uuid("TDD"), dispatchId, billType, billNo, billId,
                TmsUtil.str(row.get("customer_code")), TmsUtil.str(row.get("customer_name")),
                TmsUtil.str(row.get("address_detail")), TmsUtil.str(row.get("territory")),
                TmsUtil.str(row.get("route_line")), qty, amt, skuCount, seq);
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
        // assigned 保持原义「已派单但司机还没接」，ACCEPTED 单独计数而不是合并进 assigned：
        // 调度员真正关心的是「派出去没人应」的那一批，两者混在一个数里就看不出来了。
        long assigned = rows.stream().filter(r -> "ASSIGNED".equals(TmsUtil.str(r.get("status")))).count();
        long accepted = rows.stream().filter(r -> "ACCEPTED".equals(TmsUtil.str(r.get("status")))).count();
        BigDecimal loadedQty = rows.stream().map(r -> TmsUtil.toBd(r.get("loadedQty"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        int storeCount = rows.stream().mapToInt(r -> TmsUtil.toInt(r.get("storeCount"))).sum();
        return ApiResponse.ok(Map.of(
                "total", total, "completed", completed, "delivering", delivering,
                "assigned", assigned, "accepted", accepted,
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
            case "ACCEPTED" -> "已接单";
            case "LOADED" -> "已装车";
            case "DEPARTED" -> "已发车";
            case "DELIVERING" -> "配送中";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }
}
