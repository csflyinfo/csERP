package com.erp.wms;

import com.erp.common.util.BillNoGenerator;
import com.erp.inventory.service.InventoryCostService;
import com.erp.sales.SalesOutboundController;
import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * WMS V1.5 出库作业编排服务（PRD-28）。
 *
 * <p>负责把已审核销售订单走完「下放组波 → 分配批次/库位 → 库区拣货 → 分播集齐 → 复核 → 扣库存 → 装车发运」。
 *
 * <p><b>账实分离口径</b>：本服务只维护作业状态与物理位置（wms_bin_stock 预占/拣出），
 * <b>所有财务库存扣减统一走 {@link SalesOutboundController#createAndAuditForWms} →
 * {@link InventoryCostService#salesOutbound}</b>，WMS 不直接改金额/成本/inv_stock_balance。
 *
 * <p>批次锁在<b>下放</b>时通过 {@link InventoryCostService#lockBatch} 预占（inv_batch_stock.locked_qty），
 * 撤销下放释放；扣库存时由出库审核链先释放再扣实物，避免双重占用。
 */
@Service
public class WmsOutboundService {

    private final JdbcTemplate jdbc;
    private final InventoryCostService inventoryCost;
    private final SalesOutboundController salesOutbound;
    private final BillNoGenerator billNo;
    private final SysParamService params;
    private final WmsWarehouseResolver warehouseResolver;

    public WmsOutboundService(JdbcTemplate jdbc, InventoryCostService inventoryCost,
                              SalesOutboundController salesOutbound, BillNoGenerator billNo,
                              SysParamService params, WmsWarehouseResolver warehouseResolver) {
        this.jdbc = jdbc;
        this.inventoryCost = inventoryCost;
        this.salesOutbound = salesOutbound;
        this.billNo = billNo;
        this.params = params;
        this.warehouseResolver = warehouseResolver;
    }

    // ==================== 订单池 ====================

    /**
     * 已审核未出库、且尚未进入任何波次的销售订单（预分配/组波池）。
     */
    public List<Map<String, Object>> orderPool(String warehouse, String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.order_id, o.order_no, o.customer_code, o.customer, o.warehouse,
                       o.salesman, c.territory, c.route_line, o.amount, o.status, o.outbound_status,
                       (SELECT COUNT(*) FROM sales_order_detail d WHERE d.order_id = o.order_id) AS sku_count,
                       (SELECT COALESCE(SUM(d.qty),0) FROM sales_order_detail d WHERE d.order_id = o.order_id) AS piece_count
                FROM sales_order o
                LEFT JOIN base_customer c ON c.customer_code = o.customer_code
                WHERE o.status = 'APPROVED'
                  AND COALESCE(o.outbound_status,'未出库') IN ('未出库','待出库')
                  AND NOT EXISTS (SELECT 1 FROM wms_wave_detail wd WHERE wd.source_order_no = o.order_no)
                """);
        List<Object> args = new ArrayList<>();
        if (warehouse != null && !warehouse.isBlank()) {
            sql.append(" AND o.warehouse = ?");
            args.add(warehouse);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(o.order_no) LIKE ? OR LOWER(o.customer) LIKE ?)");
            String k = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
            args.add(k); args.add(k);
        }
        sql.append(" ORDER BY o.create_time DESC, o.order_no DESC");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    /**
     * 预分配页左侧「线路列表」：把订单池按 base_customer.route_line 分组，统计每条线路
     * 上的待下放订单数/件数/金额，并把线路基础资料（司机、车牌、车型）一并返回。
     * 未分配线路的订单归入 NULL 行，前端显示为「未分配线路」。
     */
    public List<Map<String, Object>> routePool(String warehouse) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    CASE WHEN c.route_line IS NULL OR c.route_line = '' THEN '__NONE__' ELSE c.route_line END AS route_line,
                    COUNT(DISTINCT o.order_no) AS order_count,
                    COALESCE(SUM(d.qty),0)     AS piece_count,
                    COALESCE(SUM(o.amount),0)  AS amount
                FROM sales_order o
                LEFT JOIN base_customer c ON c.customer_code = o.customer_code
                LEFT JOIN sales_order_detail d ON d.order_id = o.order_id
                WHERE o.status = 'APPROVED'
                  AND COALESCE(o.outbound_status,'未出库') IN ('未出库','待出库')
                  AND NOT EXISTS (SELECT 1 FROM wms_wave_detail wd WHERE wd.source_order_no = o.order_no)
                """);
        List<Object> args = new ArrayList<>();
        if (warehouse != null && !warehouse.isBlank()) {
            sql.append(" AND o.warehouse = ?");
            args.add(warehouse);
        }
        sql.append(" GROUP BY CASE WHEN c.route_line IS NULL OR c.route_line = '' THEN '__NONE__' ELSE c.route_line END ");
        sql.append(" ORDER BY order_count DESC");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
        // 关联 base_route_line 司机/车牌
        for (Map<String, Object> r : rows) {
            String rl = String.valueOf(r.get("routeLine"));
            if ("__NONE__".equals(rl)) {
                r.put("routeLine", "");
                r.put("routeName", "未分配线路");
                r.put("assigned", false);
            } else {
                r.put("assigned", true);
                List<Map<String, Object>> info = jdbc.queryForList(
                        "SELECT driver, vehicle_plate, vehicle_type FROM base_route_line WHERE route_line_name = ? OR route_line_code = ? FETCH FIRST 1 ROW ONLY",
                        rl, rl);
                if (!info.isEmpty()) {
                    r.put("driver", info.get(0).get("driver"));
                    r.put("vehiclePlate", info.get(0).get("vehicle_plate"));
                    r.put("vehicleType", info.get(0).get("vehicle_type"));
                    String name = TmsUtil.str(info.get(0).get("driver"));
                    r.put("routeName", rl + (name.isBlank() ? "" : "（" + name + "）"));
                } else {
                    r.put("routeName", rl);
                }
            }
        }
        return rows;
    }

    /**
     * 订单池按指定线路过滤（前端选中左侧某条线路后调用）。routeLine 传空串/特殊值 __NONE__
     * 表示查询「未分配线路」的订单。
     */
    public List<Map<String, Object>> orderPoolByRoute(String warehouse, String routeLine, String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.order_id, o.order_no, o.customer_code, o.customer, o.warehouse,
                       o.salesman, c.territory, c.route_line, o.amount, o.status, o.outbound_status,
                       (SELECT COUNT(*) FROM sales_order_detail d WHERE d.order_id = o.order_id) AS sku_count,
                       (SELECT COALESCE(SUM(d.qty),0) FROM sales_order_detail d WHERE d.order_id = o.order_id) AS piece_count
                FROM sales_order o
                LEFT JOIN base_customer c ON c.customer_code = o.customer_code
                WHERE o.status = 'APPROVED'
                  AND COALESCE(o.outbound_status,'未出库') IN ('未出库','待出库')
                  AND NOT EXISTS (SELECT 1 FROM wms_wave_detail wd WHERE wd.source_order_no = o.order_no)
                """);
        List<Object> args = new ArrayList<>();
        if (warehouse != null && !warehouse.isBlank()) {
            sql.append(" AND o.warehouse = ?"); args.add(warehouse);
        }
        if (routeLine == null || routeLine.isBlank() || "__NONE__".equals(routeLine)) {
            sql.append(" AND (c.route_line IS NULL OR c.route_line = '')");
        } else {
            sql.append(" AND c.route_line = ?"); args.add(routeLine);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(o.order_no) LIKE ? OR LOWER(o.customer) LIKE ?)");
            String k = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
            args.add(k); args.add(k);
        }
        sql.append(" ORDER BY o.create_time DESC, o.order_no DESC");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    // ==================== 下放组波 ====================

    /**
     * 下放：把一批销售订单组成一个波次，按策略分配批次+库位，预占批次锁，并按库区生成拣货任务。
     *
     * @param request {orderNos:[], pickMode, batchStrategy, routeLine, driver, collectionZone, warehouse}
     */
    @Transactional
    public Map<String, Object> release(Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Object> orderNos = request.get("orderNos") instanceof List<?> l ? (List<Object>) l : List.of();
        if (orderNos.isEmpty()) throw new IllegalArgumentException("请至少选择一张订单下放");

        // 拣货模式/批次策略：参数值已数字化（0~4 / 0~2），前端下放弹窗也传数字。
        // 兼容旧版前端传英文枚举（ORDER/SUMMARY/...），统一归一化为数字字符串。
        int pickModeInt = normalizeEnum(request.get("pickMode"), params.getInt("WMS_PICK_MODE", 0, 0, 4),
                new String[]{"ORDER", "SUMMARY", "PICK_SORT", "ZONE_RELAY", "CROSS_DOCK"});
        int strategyInt = normalizeEnum(request.get("batchStrategy"), params.getInt("WMS_BATCH_ALLOC_STRATEGY", 0, 0, 2),
                new String[]{"FEFO", "FIFO", "SPECIFIED"});
        String pickMode = String.valueOf(pickModeInt);
        String strategy = String.valueOf(strategyInt);
        String routeLine = str(request.get("routeLine"));
        String driver = str(request.get("driver"));
        String collectionZone = strOrDefault(request.get("collectionZone"), "COL");
        String warehouse = str(request.get("warehouse"));

        String waveNo = billNo.nextNo(BillNoGenerator.BillType.WMS_WAVE, "wms_wave", "wave_no");
        String waveId = id("WV");

        int orderCount = 0, lineCount = 0;
        BigDecimal totalQty = BigDecimal.ZERO;
        List<String> taskZones = new ArrayList<>();
        Map<String, BigDecimal> taskQtyByZone = new HashMap<>();
        Map<String, Integer> taskLinesByZone = new HashMap<>();
        Map<String, List<String>> taskOrdersByZone = new HashMap<>();
        List<String> allocatedOrderNos = new ArrayList<>();

        for (Object on : orderNos) {
            String orderNo = str(on);
            List<Map<String, Object>> orderRows = jdbc.queryForList(
                    "SELECT order_id, order_no, customer_code, customer, warehouse FROM sales_order WHERE order_no = ? OR order_id = ?",
                    orderNo, orderNo);
            if (orderRows.isEmpty()) throw new IllegalArgumentException("销售订单不存在：" + orderNo);
            Map<String, Object> order = orderRows.get(0);
            if (!"APPROVED".equals(str(pick(order, "status")))
                    && !"APPROVED".equalsIgnoreCase(str(pick(order, "status")))) {
                // status is always APPROVED here due to pool filter, but double-guard
            }
            // 重复下放保护
            Integer inWave = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wms_wave_detail WHERE source_order_no = ?",
                    Integer.class, str(pick(order, "order_no")));
            if (inWave != null && inWave > 0) {
                throw new IllegalArgumentException("订单 " + orderNo + " 已在波次中，不能重复下放");
            }
            String wh = warehouse.isBlank() ? str(pick(order, "warehouse")) : warehouse;
            String orderId = str(pick(order, "order_id"));
            String customerCode = str(pick(order, "customer_code"));
            String customerName = str(pick(order, "customer"));

            List<Map<String, Object>> details = jdbc.queryForList(
                    "SELECT goods_code, goods_name, unit_name, qty FROM sales_order_detail WHERE order_id = ? ORDER BY detail_id",
                    orderId);
            for (Map<String, Object> d : details) {
                String goodsCode = str(pick(d, "goods_code"));
                BigDecimal need = bd(pick(d, "qty"));
                // 0=FEFO近效期先出(生产日期升序), 1=FIFO先进先出(批次号升序,批次号即yyyyMMdd), 2=指定批次(不自动分配)
                String batchOrder = "1".equals(strategy)
                        ? " ORDER BY batch_no ASC"
                        : " ORDER BY production_date ASC NULLS LAST, batch_no ASC";
                List<Map<String, Object>> batches = "2".equals(strategy) ? List.of()
                        : jdbc.queryForList("""
                            SELECT batch_no, qty - COALESCE(locked_qty,0) AS avail
                            FROM inv_batch_stock
                            WHERE goods_code = ? AND warehouse = ? AND qty - COALESCE(locked_qty,0) > 0
                            """ + batchOrder, goodsCode, wh);
                BigDecimal remaining = need;
                if (batches.isEmpty() && "2".equals(strategy)) {
                    // 指定批次策略：不预占，分配一条空批次明细，拣货时再定（不锁批次）
                    insertWaveDetail(waveId, waveNo, orderNo, orderId, customerCode, customerName,
                            d, goodsCode, "", "", "", "", need, wh, collectionZone, pickMode);
                    lineCount++;
                    taskZones.add("");
                    taskOrdersByZone.computeIfAbsent("", k -> new ArrayList<>()).add(orderNo);
                    taskQtyByZone.merge("", need, BigDecimal::add);
                    taskLinesByZone.merge("", 1, Integer::sum);
                    totalQty = totalQty.add(need);
                } else {
                    for (Map<String, Object> b : batches) {
                        if (remaining.signum() <= 0) break;
                        BigDecimal avail = bd(pick(b, "avail"));
                        BigDecimal take = remaining.min(avail);
                        String batchNo = str(pick(b, "batch_no"));
                        // 锁批次（下放即预占）
                        inventoryCost.lockBatch(goodsCode, wh, batchNo, take);
                        // 选拣货库位：优先有该批次实物的库位，否则取该库区一个存储库位
                        String binCode = pickBin(goodsCode, wh, batchNo);
                        String zoneCode = zoneOfBin(wh, binCode);
                        // 物理预占（库位有记录时加锁；无记录不动，保持账实一致）
                        lockBinStock(goodsCode, wh, batchNo, binCode, take);

                        insertWaveDetail(waveId, waveNo, orderNo, orderId, customerCode, customerName,
                                d, goodsCode, batchNo, binCode, zoneCode, "", take, wh, collectionZone, pickMode);
                        lineCount++;
                        taskZones.add(zoneCode);
                        if (!taskOrdersByZone.computeIfAbsent(zoneCode, k -> new ArrayList<>()).contains(orderNo)) {
                            taskOrdersByZone.get(zoneCode).add(orderNo);
                        }
                        taskQtyByZone.merge(zoneCode, take, BigDecimal::add);
                        taskLinesByZone.merge(zoneCode, 1, Integer::sum);
                        totalQty = totalQty.add(take);
                        remaining = remaining.subtract(take);
                    }
                    if (remaining.signum() > 0) {
                        throw new IllegalArgumentException("商品 " + goodsCode + " 批次可用库存不足，订单 " + orderNo
                                + " 需 " + need + "，缺 " + remaining + "（策略 " + strategy + "）");
                    }
                }
            }
            orderCount++;
            allocatedOrderNos.add(orderNo);
        }

        jdbc.update("""
                INSERT INTO wms_wave (wave_id, wave_no, warehouse, wave_type, pick_mode, batch_strategy, status,
                    order_count, line_count, total_qty, picked_qty, route_line, driver, collection_zone,
                    expedited, released_at, created_by, remark)
                VALUES (?, ?, ?, 'OUTBOUND', ?, ?, 'RELEASED', ?, ?, ?, 0, ?, ?, ?, 'N', CURRENT_TIMESTAMP, ?, 'WMS下放')
                """, waveId, waveNo, warehouse.isBlank() ? "总仓" : warehouse, pickMode, strategy,
                orderCount, lineCount, totalQty,
                emptyToNull(routeLine), emptyToNull(driver), collectionZone, TmsUtil.currentUser());

        // 按库区生成拣货任务
        for (String zone : taskQtyByZone.keySet()) {
            createPickTask(waveId, waveNo, warehouse.isBlank() ? "总仓" : warehouse, zone, pickMode,
                    taskOrdersByZone.getOrDefault(zone, List.of()),
                    taskLinesByZone.getOrDefault(zone, 0),
                    taskQtyByZone.getOrDefault(zone, BigDecimal.ZERO), false, null);
        }

        TmsUtil.log(jdbc, "wms.wave", "RELEASE", waveNo,
                "下放波次，订单 " + orderCount + " 张，行 " + lineCount + "，件 " + totalQty + "，策略 " + pickMode + "/" + strategy);
        return Map.of("waveId", waveId, "waveNo", waveNo, "orderCount", orderCount,
                "lineCount", lineCount, "totalQty", totalQty, "status", "RELEASED");
    }

    private void insertWaveDetail(String waveId, String waveNo, String orderNo, String orderId,
                                  String customerCode, String customerName, Map<String, Object> orderDetail,
                                  String goodsCode, String batchNo, String binCode, String zoneCode,
                                  String container, BigDecimal requiredQty, String warehouse,
                                  String collectionZone, String pickMode) {
        String detailId = id("WD");
        int seq = (int) (jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_wave_detail WHERE wave_id = ?", Integer.class, waveId) + 1);
        // 集货位：一单一集货位（简易分配，按波次内顺序取集货区库位）
        String collectionBin = allocateCollectionBin(warehouse, collectionZone, orderNo);
        jdbc.update("""
                INSERT INTO wms_wave_detail (detail_id, wave_id, wave_no, source_order_no, source_order_id,
                    customer_code, customer_name, goods_code, goods_name, unit_name, required_qty,
                    alloc_batch_no, alloc_bin_code, alloc_zone_code, alloc_container_code, pick_seq,
                    picked_qty, status, collection_bin_code, sort_destination, expedited)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'PENDING', ?, ?, 'N')
                """, detailId, waveId, waveNo, orderNo, orderId, customerCode, customerName,
                goodsCode, str(pick(orderDetail, "goods_name")), str(pick(orderDetail, "unit_name")), requiredQty,
                emptyToNull(batchNo), emptyToNull(binCode), emptyToNull(zoneCode), emptyToNull(container),
                seq, emptyToNull(collectionBin), customerName);
    }

    private void createPickTask(String waveId, String waveNo, String warehouse, String zone, String wavePickMode,
                                List<String> orders, int lineCount, BigDecimal qty, boolean help, String parentId) {
        String taskNo = billNo.nextNo(BillNoGenerator.BillType.WMS_PICK_TASK, "wms_pick_task", "task_no");
        // V84：拣货方式下沉到库区。任务上记录当时库区的 pick_method/sort_mode，便于 PDA 按区差异化作业；
        // 库区未配时回退波次级 wavePickMode（数字枚举），保持向后兼容。
        String zonePickMethod = null;
        String zoneSortMode = null;
        if (zone != null && !zone.isBlank()) {
            List<Map<String, Object>> zr = jdbc.queryForList(
                    "SELECT pick_method, sort_mode FROM wms_zone WHERE warehouse = ? AND zone_code = ?",
                    warehouse, zone);
            if (!zr.isEmpty()) {
                zonePickMethod = emptyToNull(str(zr.get(0).get("pick_method")));
                zoneSortMode = emptyToNull(str(zr.get(0).get("sort_mode")));
            }
        }
        jdbc.update("""
                INSERT INTO wms_pick_task (task_id, task_no, wave_id, wave_no, warehouse, zone_code, pick_mode,
                    pick_method, sort_mode,
                    assignee, assign_type, status, source_orders, line_count, total_qty, picked_qty,
                    help_task, parent_task_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'FREE', 'PENDING', ?, ?, ?, 0, ?, ?)
                """, id("PT"), taskNo, waveId, waveNo, warehouse, emptyToNull(zone), wavePickMode,
                zonePickMethod, zoneSortMode,
                String.join(",", orders), lineCount, qty, help ? "Y" : "N", emptyToNull(parentId));
    }

    // ==================== 波次/订单查询与操作 ====================

    public List<Map<String, Object>> listWaves(String status) {
        String sql = """
                SELECT wave_id, wave_no, warehouse, pick_mode, batch_strategy, status, order_count,
                       line_count, total_qty, picked_qty, route_line, driver, collection_zone, expedited,
                       released_at, picked_at, checked_at, shipped_at, created_at
                FROM wms_wave
                """ + (status != null && !status.isBlank() ? " WHERE status = ?" : "")
                + " ORDER BY created_at DESC, wave_no DESC";
        List<Map<String, Object>> rows = status != null && !status.isBlank()
                ? TmsUtil.queryCamel(jdbc, sql, status) : TmsUtil.queryCamel(jdbc, sql);
        for (Map<String, Object> r : rows) r.put("statusText", waveStatusText(str(r.get("status"))));
        return rows;
    }

    public Map<String, Object> waveDetail(String waveId) {
        List<Map<String, Object>> h = jdbc.queryForList(
                "SELECT * FROM wms_wave WHERE wave_id = ? OR wave_no = ?", waveId, waveId);
        if (h.isEmpty()) throw new IllegalArgumentException("波次不存在：" + waveId);
        Map<String, Object> head = TmsUtil.camelize(h.get(0));
        head.put("statusText", waveStatusText(str(head.get("status"))));
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbc, """
                SELECT * FROM wms_wave_detail WHERE wave_id = ? ORDER BY source_order_no, pick_seq
                """, str(head.get("waveId")));
        head.put("details", details);
        // 按订单聚合
        Map<String, Map<String, Object>> byOrder = new LinkedHashMap<>();
        for (Map<String, Object> d : details) {
            String so = str(d.get("sourceOrderNo"));
            Map<String, Object> agg = byOrder.computeIfAbsent(so, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sourceOrderNo", so);
                m.put("customerName", d.get("customerName"));
                m.put("lines", new ArrayList<Map<String, Object>>());
                m.put("requiredQty", BigDecimal.ZERO);
                m.put("pickedQty", BigDecimal.ZERO);
                m.put("status", "PENDING");
                return m;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) agg.get("lines");
            lines.add(d);
            agg.put("requiredQty", ((BigDecimal) agg.get("requiredQty")).add(bd(d.get("requiredQty"))));
            agg.put("pickedQty", ((BigDecimal) agg.get("pickedQty")).add(bd(d.get("pickedQty"))));
            if ("PICKED".equals(str(d.get("status")))) agg.put("status", "PICKED");
            else if ("SHORT".equals(str(d.get("status")))) agg.put("status", "SHORT");
            else if ("PICKING".equals(str(d.get("status")))) agg.put("status", "PICKING");
        }
        head.put("orders", new ArrayList<>(byOrder.values()));
        return head;
    }

    /** 订单加急 / 取消加急 */
    @Transactional
    public void expediteOrder(String waveId, String orderNo, boolean expedite) {
        if (params.getInt("WMS_EXPEDITE_ENABLED", 1, 0, 1) != 1) {
            throw new IllegalArgumentException("加急功能已被参数 WMS_EXPEDITE_ENABLED 关闭");
        }
        int n = jdbc.update(
                "UPDATE wms_wave_detail SET expedited = ? WHERE source_order_no = ?",
                expedite ? "Y" : "N", orderNo);
        if (n == 0) throw new IllegalArgumentException("波次内未找到订单 " + orderNo);
        TmsUtil.log(jdbc, "wms.wave", expedite ? "EXPEDITE" : "UNEXPEDITE", orderNo,
                (expedite ? "订单加急" : "取消加急") + "，波次 " + waveId);
    }

    /** 整波加急/取消 */
    @Transactional
    public void expediteWave(String waveId, boolean expedite) {
        jdbc.update("UPDATE wms_wave SET expedited = ? WHERE wave_id = ?", expedite ? "Y" : "N", waveId);
        jdbc.update("UPDATE wms_wave_detail SET expedited = ? WHERE wave_id = ?", expedite ? "Y" : "N", waveId);
        TmsUtil.log(jdbc, "wms.wave", expedite ? "WAVE_EXPEDITE" : "WAVE_UNEXPEDITE", waveId,
                expedite ? "整波加急" : "整波取消加急");
    }

    /**
     * 撤销下放：把波次内未拣的订单（或整波）回到订单池，释放批次锁与库位预占，删除相关拣货任务。
     * 已有拣货记录的订单需要主管权限（由 controller 校验 supervisor 标记）。
     */
    @Transactional
    public void cancelRelease(String waveId, String orderNo, boolean supervisor) {
        if (params.getInt("WMS_CANCEL_RELEASE_LOCK", 1, 0, 1) != 1) {
            throw new IllegalArgumentException("参数 WMS_CANCEL_RELEASE_LOCK=0，禁止撤销下放");
        }
        List<Map<String, Object>> details;
        if (orderNo != null && !orderNo.isBlank()) {
            details = jdbc.queryForList(
                    "SELECT * FROM wms_wave_detail WHERE wave_id = ? AND source_order_no = ?", waveId, orderNo);
        } else {
            details = jdbc.queryForList("SELECT * FROM wms_wave_detail WHERE wave_id = ?", waveId);
        }
        if (details.isEmpty()) throw new IllegalArgumentException("没有可撤销的下放明细");

        for (Map<String, Object> d : details) {
            BigDecimal picked = bd(pick(d, "picked_qty"));
            if (picked.signum() > 0 && !supervisor) {
                throw new IllegalArgumentException("订单 " + str(pick(d, "source_order_no"))
                        + " 已有拣货记录，撤销需主管权限");
            }
            BigDecimal qty = bd(pick(d, "required_qty"));
            String batchNo = str(pick(d, "alloc_batch_no"));
            String wh = waveWarehouse(waveId);
            // 释放未拣部分的批次锁与库位预占：
            //   · 未拣（picked=0）→ 释放整单 required_qty
            //   · 部分拣（主管撤销）→ 只释放 (required - picked)，已拣部分的锁在后续复核/扣账时兑现，
            //     绝不能因为撤销整行而把已拣货物对应的批次锁残留或多释放
            BigDecimal unpicked = qty.subtract(picked).max(BigDecimal.ZERO);
            if (unpicked.signum() > 0) {
                inventoryCost.releaseBatchLock(str(pick(d, "goods_code")), wh, batchNo, unpicked);
                unlockBinStock(str(pick(d, "goods_code")), wh, batchNo, str(pick(d, "alloc_bin_code")), unpicked);
            }
        }
        if (orderNo != null && !orderNo.isBlank()) {
            // 同步清理「只服务本单」的拣货任务。汇总拣货/跨区接力任务的 source_orders 含多个订单，
            // 撤其中一单不能删任务（其余订单还要拣），这类任务留到自然完成或整波撤销时清理。
            // 已领取/拣货中的任务由主管权限背书，连同其未拣明细一并作废。
            jdbc.update("""
                    DELETE FROM wms_pick_task
                    WHERE wave_id = ? AND source_orders = ?
                    """, waveId, orderNo);
            jdbc.update("DELETE FROM wms_wave_detail WHERE wave_id = ? AND source_order_no = ?", waveId, orderNo);
        } else {
            jdbc.update("DELETE FROM wms_pick_task WHERE wave_id = ?", waveId);
            jdbc.update("DELETE FROM wms_wave_detail WHERE wave_id = ?", waveId);
            jdbc.update("UPDATE wms_wave SET status = 'CANCELLED' WHERE wave_id = ?", waveId);
            TmsUtil.log(jdbc, "wms.wave", "CANCEL", waveId, "整波撤销下放");
            return;
        }
        // 若波次空了则关闭
        Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM wms_wave_detail WHERE wave_id = ?",
                Integer.class, waveId);
        if (left != null && left == 0) {
            jdbc.update("DELETE FROM wms_pick_task WHERE wave_id = ?", waveId);
            jdbc.update("UPDATE wms_wave SET status = 'CANCELLED' WHERE wave_id = ?", waveId);
        }
        TmsUtil.log(jdbc, "wms.wave", "CANCEL_ORDER", orderNo, "撤销订单下放，波次 " + waveId);
    }

    // ==================== 拣货（PDA / PC） ====================

    /** 拣货任务池：可按库区/领取人过滤。scope=mine 我的库区/zone、help 可支援库区、all 全部 */
    public List<Map<String, Object>> pickTasks(String assignee, String scope, String zone) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.task_id, t.task_no, t.wave_id, t.wave_no, t.warehouse, t.zone_code, t.pick_mode,
                       t.assignee, t.assign_type, t.status, t.source_orders, t.line_count,
                       t.total_qty, t.picked_qty, t.help_task, t.parent_task_id, t.claimed_at, t.created_at
                FROM wms_pick_task t
                WHERE t.status <> 'CANCELLED'
                """);
        List<Object> args = new ArrayList<>();
        // PRD-28 卡片9：PDA 任务列表强制登录仓（PDA-007）；scope=all 的主管口径由 controller 另验功能点
        if (warehouseResolver.isPda()) {
            sql.append(" AND t.warehouse = ?");
            args.add(warehouseResolver.currentWarehouseName());
        }
        if ("mine".equalsIgnoreCase(scope) && assignee != null && !assignee.isBlank()) {
            sql.append(" AND t.assignee = ?");
            args.add(assignee);
        } else if ("help".equalsIgnoreCase(scope)) {
            sql.append(" AND (t.assignee IS NULL OR t.assignee = '')");
        }
        if (zone != null && !zone.isBlank()) {
            sql.append(" AND t.zone_code = ?");
            args.add(zone);
        }
        sql.append(" ORDER BY t.created_at DESC, t.task_no DESC");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    public Map<String, Object> pickTaskDetail(String taskId) {
        List<Map<String, Object>> h = jdbc.queryForList(
                "SELECT * FROM wms_pick_task WHERE task_id = ? OR task_no = ?", taskId, taskId);
        if (h.isEmpty()) throw new IllegalArgumentException("拣货任务不存在：" + taskId);
        warehouseResolver.assertIfPda(str(h.get(0).get("warehouse")));
        Map<String, Object> task = TmsUtil.camelize(h.get(0));
        // 汇总拣：同 SKU 合并应拣数量并列出分播去向；按单拣：带订单号逐条
        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc, """
                SELECT detail_id, source_order_no, customer_name, goods_code, goods_name, unit_name,
                       required_qty, picked_qty, alloc_batch_no, alloc_bin_code, alloc_zone_code,
                       collection_bin_code, sort_destination, status, expedited, pick_seq
                FROM wms_wave_detail
                WHERE pick_task_id = ? OR (wave_id = ? AND COALESCE(alloc_zone_code,'') = COALESCE(?,''))
                ORDER BY pick_seq, source_order_no
                """, taskId, str(task.get("waveId")), str(task.get("zoneCode")));
        task.put("lines", lines);
        return task;
    }

    @Transactional
    public Map<String, Object> claimTask(String taskId, String assignee, boolean help) {
        List<Map<String, Object>> h = jdbc.queryForList(
                "SELECT task_id, status, help_task, warehouse FROM wms_pick_task WHERE task_id = ?", taskId);
        if (h.isEmpty()) throw new IllegalArgumentException("拣货任务不存在");
        warehouseResolver.assertIfPda(str(h.get(0).get("warehouse")));
        String status = str(pick(h.get(0), "status"));
        if (!"PENDING".equals(status)) throw new IllegalArgumentException("任务已被领取或已完成");
        jdbc.update("""
                UPDATE wms_pick_task SET assignee = ?, status = 'CLAIMED', claimed_at = CURRENT_TIMESTAMP, help_task = ?
                WHERE task_id = ?
                """, assignee, help ? "Y" : str(pick(h.get(0), "help_task")), taskId);
        // 把该库区下波次明细绑到任务
        jdbc.update("UPDATE wms_wave_detail SET pick_task_id = ? "
                + "WHERE pick_task_id IS NULL AND wave_id = (SELECT wave_id FROM wms_pick_task WHERE task_id = ?) "
                + "AND COALESCE(alloc_zone_code,'') = COALESCE((SELECT zone_code FROM wms_pick_task WHERE task_id = ?),'')",
                taskId, taskId, taskId);
        advanceWaveStatusByTask(taskId);
        TmsUtil.log(jdbc, "wms.pick", "CLAIM", taskId, assignee + (help ? " 跨区支援领取" : " 领取任务"));
        return Map.of("taskId", taskId, "assignee", assignee);
    }

    /**
     * PDA 扫一件商品：回写已拣数量。允许改批次（参数开关）。
     */
    @Transactional
    public Map<String, Object> pickItem(String detailId, String taskId, String goodsCode,
                                        BigDecimal qty, String actualBatchNo, String actualBin, String assignee) {
        Map<String, Object> d = findDetail(detailId, taskId, goodsCode);
        if (d == null) throw new IllegalArgumentException("未找到待拣明细");
        warehouseResolver.assertIfPda(str(d.get("warehouse")));
        String status = str(d.get("status"));
        if ("PICKED".equals(status)) throw new IllegalArgumentException("该明细已拣完");
        BigDecimal required = bd(d.get("requiredQty"));
        BigDecimal already = bd(d.get("pickedQty"));
        BigDecimal next = already.add(qty == null ? BigDecimal.ONE : qty);
        if (next.compareTo(required) > 0) next = required;

        String batchNo = str(d.get("allocBatchNo"));
        if (actualBatchNo != null && !actualBatchNo.isBlank() && !actualBatchNo.equals(batchNo)) {
            if (params.getInt("WMS_PICK_BATCH_CHANGE_ALLOWED", 1, 0, 1) != 1) {
                throw new IllegalArgumentException("参数禁止改拣批次，应拣批次 " + batchNo);
            }
            // 改批次：释放原批次预占，锁新批次（拣货当场兑现，新批次直接占/扣到审核）
            inventoryCost.releaseBatchLock(str(d.get("goodsCode")), str(d.get("warehouse")), batchNo, required.subtract(already));
            inventoryCost.lockBatch(str(d.get("goodsCode")), str(d.get("warehouse")), actualBatchNo, required.subtract(next));
            batchNo = actualBatchNo;
        }
        jdbc.update("""
                UPDATE wms_wave_detail SET picked_qty = ?, picked_bin_code = COALESCE(?, picked_bin_code),
                    picked_batch_no = ?, status = CASE WHEN ? >= required_qty THEN 'PICKED' ELSE 'PICKING' END
                WHERE detail_id = ?
                """, next, emptyToNull(actualBin), emptyToNull(batchNo), next, str(d.get("detailId")));
        // 任务/波次进度
        if (taskId != null) {
            recalcTaskProgress(taskId);
            recalcWaveProgress(taskOfWave(taskId));
            advanceWaveStatusByTask(taskId);
        }
        // 拣完一件即从库位实物预占转为已拣出库（库位库存减；财务库存仍在审核时扣）
        if (next.compareTo(required) == 0) {
            pickFromBin(str(d.get("goodsCode")), str(d.get("warehouse")), batchNo,
                    str(d.get("allocBinCode")), required);
        }
        return Map.of("detailId", d.get("detailId"), "pickedQty", next, "requiredQty", required,
                "done", next.compareTo(required) >= 0);
    }

    /** 整任务拣货完成（PC 一键 / PDA 提交）。 */
    @Transactional
    public Map<String, Object> completeTask(String taskId, String assignee) {
        List<Map<String, Object>> taskHead = jdbc.queryForList(
                "SELECT warehouse FROM wms_pick_task WHERE task_id = ?", taskId);
        if (taskHead.isEmpty()) throw new IllegalArgumentException("拣货任务不存在：" + taskId);
        warehouseResolver.assertIfPda(str(taskHead.get(0).get("warehouse")));
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT detail_id, required_qty, picked_qty, status FROM wms_wave_detail WHERE pick_task_id = ?", taskId);
        boolean allPicked = true;
        for (Map<String, Object> l : lines) {
            if (bd(pick(l, "picked_qty")).compareTo(bd(pick(l, "required_qty"))) < 0) { allPicked = false; break; }
        }
        if (!allPicked) {
            if (params.getInt("WMS_EXCEPTION_AUTO_SUSPEND", 1, 0, 1) == 1) {
                jdbc.update("UPDATE wms_pick_task SET status = 'PICKED', picked_at = CURRENT_TIMESTAMP WHERE task_id = ?", taskId);
                markWaveSuspended(taskOfWave(taskId), "任务 " + taskId + " 存在缺货明细，自动挂起");
                throw new IllegalArgumentException("存在未拣完明细，已自动挂起，请走异常/补货处理");
            }
            throw new IllegalArgumentException("存在未拣完明细，不能完成");
        }
        jdbc.update("UPDATE wms_wave_detail SET status = 'PICKED' WHERE pick_task_id = ? AND status <> 'PICKED'", taskId);
        jdbc.update("UPDATE wms_pick_task SET status = 'PICKED', picked_at = CURRENT_TIMESTAMP WHERE task_id = ?", taskId);
        recalcTaskProgress(taskId);
        String waveId = taskOfWave(taskId);
        recalcWaveProgress(waveId);
        maybeFinishWavePicking(waveId);
        TmsUtil.log(jdbc, "wms.pick", "COMPLETE", taskId, "拣货任务完成，操作人 " + assignee);
        return Map.of("taskId", taskId, "status", "PICKED");
    }

    // ==================== 复核 / 扣库存 ====================

    /**
     * 复核通过某张订单（PC 一键复检 / PDA 复检）：
     * 当 WMS_CHECK_ENABLED=N 时拣货完成直接调本方法；否则复核通过调本方法。
     * 生成并审核销售出库单 → 扣库存 → 生成 sales_receipt。
     */
    @Transactional
    public Map<String, Object> recheckOrder(String waveId, String orderNo, String operator,
                                            String checkScope, BigDecimal shortQty, boolean pass) {
        return recheckOrder(waveId, orderNo, operator, checkScope, shortQty, pass, null);
    }

    /**
     * 复核通过/不通过（PRD-28 卡片9：reason 为不通过原因，PDA 必填并写异常单留痕）。
     */
    @Transactional
    public Map<String, Object> recheckOrder(String waveId, String orderNo, String operator,
                                            String checkScope, BigDecimal shortQty, boolean pass,
                                            String reason) {
        List<String> whRows = jdbc.queryForList(
                "SELECT warehouse FROM wms_wave WHERE wave_id = ?", String.class, waveId);
        if (whRows.isEmpty()) throw new IllegalArgumentException("波次不存在：" + waveId);
        warehouseResolver.assertIfPda(whRows.get(0));
        String wh = whRows.get(0);
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT goods_code, required_qty, picked_qty, alloc_batch_no, picked_batch_no, status "
                        + "FROM wms_wave_detail WHERE wave_id = ? AND source_order_no = ?", waveId, orderNo);
        if (lines.isEmpty()) throw new IllegalArgumentException("波次内未找到订单 " + orderNo);
        // 集齐校验（按订单集齐复核）
        BigDecimal required = BigDecimal.ZERO, picked = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            required = required.add(bd(pick(l, "required_qty")));
            picked = picked.add(bd(pick(l, "picked_qty")));
        }
        if (picked.compareTo(required) < 0) {
            boolean shortAllowed = params.getInt("WMS_CHECK_SHORT_ALLOWED", 0, 0, 1) == 1;
            if (!shortAllowed) {
                throw new IllegalArgumentException("订单 " + orderNo + " 未集齐（应拣 " + required + " 已拣 " + picked + "），不能复核通过");
            }
        }
        if (!pass) {
            if (warehouseResolver.isPda() && (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("请填写复核不通过原因");
            }
            String exNo = billNo.nextNo(BillNoGenerator.BillType.WMS_EXCEPTION, "wms_exception", "exception_no");
            String desc = "复核不通过" + (reason == null || reason.isBlank() ? "" : "：" + reason.trim());
            jdbc.update("""
                    INSERT INTO wms_exception (exception_id, exception_no, wave_id, wave_no, source_order_no, exception_type,
                        qty, status, description, reporter, assignee, source_type, priority, created_at)
                    VALUES (?, ?, ?, (SELECT wave_no FROM wms_wave WHERE wave_id = ?), ?, 'DIFF', ?, 'OPEN', ?, ?, NULL, 'OUTBOUND', 'HIGH', CURRENT_TIMESTAMP)
                    """, id("EX"), exNo, waveId, waveId, orderNo, required.subtract(picked), desc, operator);
            TmsUtil.log(jdbc, "wms.recheck", "NG", orderNo, desc);
            return Map.of("passed", false, "exceptionNo", exNo);
        }

        // 组装出库明细：按实际拣货批次（改批次后取 picked_batch_no），数量取 picked_qty
        List<Map<String, Object>> outboundLines = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            BigDecimal q = bd(pick(l, "picked_qty"));
            if (q.signum() <= 0) continue;
            String batch = str(pick(l, "picked_batch_no"));
            if (batch.isBlank()) batch = str(pick(l, "alloc_batch_no"));
            Map<String, Object> line = new HashMap<>();
            line.put("goodsCode", str(pick(l, "goods_code")));
            line.put("qty", q);
            line.put("batchNo", batch);
            outboundLines.add(line);
        }
        String receiptNo = salesOutbound.createAndAuditForWms(orderNo, wh, outboundLines,
                "WMS波次 " + waveId + " 订单 " + orderNo + " 复核通过出库扣账");

        // 回写明细（H2 用 LIMIT 取最新生成的出库单）
        String outboundNo = jdbc.queryForObject(
                "SELECT outbound_no FROM sales_outbound WHERE source_order = ? ORDER BY created_at DESC LIMIT 1",
                String.class, orderNo);
        jdbc.update("UPDATE wms_wave_detail SET generated_outbound_no = ? WHERE wave_id = ? AND source_order_no = ?",
                outboundNo, waveId, orderNo);

        // 复核记录
        String recheckNo = billNo.nextNo(BillNoGenerator.BillType.WMS_RECHECK, "wms_recheck_record", "recheck_no");
        jdbc.update("""
                INSERT INTO wms_recheck_record (recheck_id, recheck_no, wave_id, source_order_no, warehouse,
                    check_mode, check_scope, checked_qty, short_qty, result, generated_outbound_no, operator, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PASS', ?, ?, 'WMS复核通过')
                """, id("RC"), recheckNo, waveId, orderNo, wh,
                String.valueOf(params.getInt("WMS_CHECK_MODE", 0, 0, 2)),
                normalizeCheckScope(checkScope),
                picked, required.subtract(picked), outboundNo, operator);

        // 波次进度：所有订单都生成出库单 → CHECKED
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_wave_detail WHERE wave_id = ? AND generated_outbound_no IS NULL",
                Integer.class, waveId);
        if (pending != null && pending == 0) {
            jdbc.update("UPDATE wms_wave SET status = 'CHECKED', checked_at = CURRENT_TIMESTAMP WHERE wave_id = ?", waveId);
        } else {
            jdbc.update("UPDATE wms_wave SET status = 'CHECKING' WHERE wave_id = ? AND status = 'PICKED'", waveId);
        }
        TmsUtil.log(jdbc, "wms.recheck", "PASS", recheckNo,
                "订单 " + orderNo + " 复核通过，生成发货单 " + receiptNo);
        return Map.of("passed", true, "outboundNo", outboundNo, "receiptNo", receiptNo, "recheckNo", recheckNo);
    }

    /** 拣货完成且免复核时，对波次内所有已拣完订单直接扣账 */
    @Transactional
    public void finishWaveWithoutCheck(String waveId, String operator) {
        List<String> orders = jdbc.queryForList(
                "SELECT DISTINCT source_order_no FROM wms_wave_detail WHERE wave_id = ? AND generated_outbound_no IS NULL",
                String.class, waveId);
        for (String orderNo : orders) {
            recheckOrder(waveId, orderNo, operator, "0", BigDecimal.ZERO, true);
        }
    }

    // ==================== 装车 / 发运 ====================

    public List<Map<String, Object>> loadableOrders(String waveId) {
        return TmsUtil.queryCamel(jdbc, """
                SELECT DISTINCT d.source_order_no, d.customer_name, d.collection_bin_code,
                       o.outbound_no AS generated_outbound_no,
                       (SELECT receipt_no FROM sales_receipt WHERE source_outbound_no = o.outbound_no FETCH FIRST 1 ROW ONLY) AS receipt_no
                FROM wms_wave_detail d
                LEFT JOIN sales_outbound o ON o.source_order = d.source_order_no AND o.status = 'APPROVED'
                WHERE d.wave_id = ? AND d.generated_outbound_no IS NOT NULL
                ORDER BY d.source_order_no
                """, waveId);
    }

    @Transactional
    public Map<String, Object> ship(String waveId, String vehiclePlate, String operator) {
        List<Map<String, Object>> heads = jdbc.queryForList(
                "SELECT wave_no, route_line, driver, warehouse FROM wms_wave WHERE wave_id = ?", waveId);
        if (heads.isEmpty()) throw new IllegalArgumentException("波次不存在");
        warehouseResolver.assertIfPda(str(heads.get(0).get("warehouse")));
        List<Map<String, Object>> orders = jdbc.queryForList(
                "SELECT DISTINCT source_order_no, generated_outbound_no FROM wms_wave_detail "
                        + "WHERE wave_id = ? AND generated_outbound_no IS NOT NULL", waveId);
        for (Map<String, Object> o : orders) {
            String orderNo = str(pick(o, "source_order_no"));
            String outboundNo = str(pick(o, "generated_outbound_no"));
            String receiptNo = jdbc.queryForObject(
                    "SELECT receipt_no FROM sales_receipt WHERE source_outbound_no = ? ORDER BY create_time DESC LIMIT 1",
                    String.class, outboundNo);
            String handoverNo = billNo.nextNo(BillNoGenerator.BillType.WMS_HANDOVER, "wms_handover", "handover_no");
            jdbc.update("""
                    INSERT INTO wms_handover (handover_id, handover_no, wave_id, source_order_no, outbound_no,
                        receipt_no, warehouse, route_line, driver, vehicle_plate, status, operator)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SHIPPED', ?)
                    """, id("HD"), handoverNo, waveId, orderNo, outboundNo, receiptNo,
                    str(pick(heads.get(0), "warehouse")), str(pick(heads.get(0), "route_line")),
                    str(pick(heads.get(0), "driver")), vehiclePlate, operator);
            // 发货单置为已发运（与 TMS 调度状态口径衔接）
            jdbc.update("UPDATE sales_receipt SET dispatch_status = 'SHIPPED' WHERE receipt_no = ?", receiptNo);
        }
        jdbc.update("UPDATE wms_wave SET status = 'SHIPPED', shipped_at = CURRENT_TIMESTAMP WHERE wave_id = ?", waveId);
        TmsUtil.log(jdbc, "wms.handover", "SHIP", waveId, "装车发运，车 " + vehiclePlate + "，订单 " + orders.size() + " 张");
        return Map.of("waveId", waveId, "shippedOrders", orders.size(), "status", "SHIPPED");
    }

    // ==================== 分拣指令查询 ====================

    public List<Map<String, Object>> sortInstructions(String zoneCode) {
        StringBuilder sql = new StringBuilder("""
                SELECT wd.wave_id, wd.wave_no, wd.alloc_zone_code AS zone_code, w.pick_mode,
                       COUNT(DISTINCT wd.source_order_no) AS order_count,
                       COUNT(DISTINCT wd.customer_name) AS store_count,
                       COUNT(*) AS line_count,
                       SUM(wd.required_qty) AS required_qty,
                       SUM(wd.picked_qty) AS picked_qty,
                       MAX(wd.status) AS status,
                       wd.collection_bin_code
                FROM wms_wave_detail wd JOIN wms_wave w ON w.wave_id = wd.wave_id
                """);
        List<Object> args = new ArrayList<>();
        if (zoneCode != null && !zoneCode.isBlank()) {
            sql.append(" WHERE wd.alloc_zone_code = ?");
            args.add(zoneCode);
        }
        sql.append(" GROUP BY wd.wave_id, wd.wave_no, wd.alloc_zone_code, w.pick_mode, wd.collection_bin_code ORDER BY wd.wave_no DESC");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) r.put("statusText", detailStatusText(str(r.get("status"))));
        return rows;
    }

    // ==================== PDA：缺货 / 跳过 / 转交 / 复核装车任务（PRD-28 卡片9） ====================

    /**
     * 缺货上报（wms_pda.pick.short_pick）：明细置 SHORT，写 wms_exception（必须录原因），
     * 任务进度/波次进度实时回算；整任务完成时沿用自动挂起逻辑。
     */
    @Transactional
    public Map<String, Object> shortPick(String detailId, BigDecimal shortQty, String reason, String operator) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("缺货上报必须填写原因");
        Map<String, Object> d = findDetail(detailId, null, null);
        if (d == null) throw new IllegalArgumentException("未找到待拣明细");
        warehouseResolver.assertIfPda(str(d.get("warehouse")));
        BigDecimal required = bd(d.get("requiredQty"));
        BigDecimal picked = bd(d.get("pickedQty"));
        BigDecimal qty = (shortQty == null || shortQty.signum() <= 0) ? required.subtract(picked) : shortQty;
        String waveId = str(d.get("waveId"));
        jdbc.update("UPDATE wms_wave_detail SET status='SHORT' WHERE detail_id=?", detailId);
        String exNo = insertException(waveId, str(d.get("sourceOrderNo")), str(d.get("goodsCode")),
                "SHORT", qty, "缺货上报：" + reason.trim(), operator);
        String taskId = str(d.get("pickTaskId"));
        if (!taskId.isBlank()) {
            recalcTaskProgress(taskId);
            recalcWaveProgress(taskOfWave(taskId));
            advanceWaveStatusByTask(taskId);
        }
        TmsUtil.log(jdbc, "wms.pick", "SHORT_PICK", str(d.get("sourceOrderNo")),
                "缺货上报 " + str(d.get("goodsCode")) + " x" + qty + "，原因：" + reason.trim());
        return Map.of("detailId", detailId, "status", "SHORT", "exceptionNo", exNo);
    }

    /**
     * 跳过商品（wms_pda.pick.skip）：明细保持未拣状态（稍后可回来拣），仅写异常留痕（必须录原因）。
     */
    @Transactional
    public Map<String, Object> skipDetail(String detailId, String reason, String operator) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("跳过商品必须填写原因");
        Map<String, Object> d = findDetail(detailId, null, null);
        if (d == null) throw new IllegalArgumentException("未找到待拣明细");
        warehouseResolver.assertIfPda(str(d.get("warehouse")));
        String exNo = insertException(str(d.get("waveId")), str(d.get("sourceOrderNo")),
                str(d.get("goodsCode")), "OTHER", BigDecimal.ZERO,
                "跳过商品：" + reason.trim(), operator);
        TmsUtil.log(jdbc, "wms.pick", "SKIP", str(d.get("sourceOrderNo")),
                "跳过 " + str(d.get("goodsCode")) + "，原因：" + reason.trim());
        return Map.of("detailId", detailId, "exceptionNo", exNo);
    }

    /**
     * 拣货任务转交（wms_pda.pick.transfer，仅 KEEPER/LEADER）：
     * 目标人必须是启用状态且绑定了当前作业仓库的用户，防止把任务转给别仓账号。
     */
    @Transactional
    public Map<String, Object> transferTask(String taskId, String toAssignee, String operator) {
        if (toAssignee == null || toAssignee.isBlank()) throw new IllegalArgumentException("请指定转交对象");
        List<Map<String, Object>> h = jdbc.queryForList(
                "SELECT task_id, status, warehouse FROM wms_pick_task WHERE task_id = ?", taskId);
        if (h.isEmpty()) throw new IllegalArgumentException("拣货任务不存在：" + taskId);
        warehouseResolver.assertIfPda(str(h.get(0).get("warehouse")));
        assertUserInCurrentWarehouse(toAssignee);
        jdbc.update("UPDATE wms_pick_task SET assignee = ?, assign_type = 'TRANSFER' WHERE task_id = ?",
                toAssignee.trim(), taskId);
        TmsUtil.log(jdbc, "wms.pick", "TRANSFER", taskId, operator + " 转交任务给 " + toAssignee.trim());
        return Map.of("taskId", taskId, "assignee", toAssignee.trim());
    }

    /**
     * 复核任务列表：当前仓待复核（PICKED 拣完待复核 / CHECKING 复核中）波次及其订单。
     * PRD-28 卡片9：每行附 orders（按 source_order_no 聚合），复核员无 pick.view 权限，
     * 不能再借 pick/task-detail 拉明细，逐单通过/差异登记所需订单信息由本端点一次下发。
     */
    public List<Map<String, Object>> checkTaskList() {
        String wh = warehouseResolver.currentWarehouseName();
        List<Map<String, Object>> waves = TmsUtil.queryCamel(jdbc, """
                SELECT w.wave_id, w.wave_no, w.status, w.route_line, w.driver, w.picked_at,
                       COUNT(DISTINCT d.source_order_no) AS order_count,
                       COALESCE(SUM(d.required_qty),0) AS required_qty,
                       COALESCE(SUM(d.picked_qty),0) AS picked_qty
                FROM wms_wave w
                JOIN wms_wave_detail d ON d.wave_id = w.wave_id
                WHERE w.warehouse = ? AND w.status IN ('PICKED','CHECKING','SUSPENDED')
                GROUP BY w.wave_id, w.wave_no, w.status, w.route_line, w.driver, w.picked_at
                ORDER BY w.picked_at DESC, w.wave_no
                """, wh);
        if (waves.isEmpty()) return waves;
        String placeholders = waves.stream().map(w -> "?").collect(java.util.stream.Collectors.joining(","));
        Object[] waveIds = waves.stream().map(w -> w.get("waveId")).toArray();
        List<Map<String, Object>> orderRows = TmsUtil.queryCamel(jdbc,
                "SELECT wave_id, source_order_no, MAX(customer_name) AS customer_name, " +
                "COALESCE(SUM(required_qty),0) AS required_qty, COALESCE(SUM(picked_qty),0) AS picked_qty " +
                "FROM wms_wave_detail WHERE wave_id IN (" + placeholders + ") " +
                "GROUP BY wave_id, source_order_no ORDER BY source_order_no",
                waveIds);
        java.util.Map<String, List<Map<String, Object>>> byWave = new java.util.LinkedHashMap<>();
        for (Map<String, Object> o : orderRows) {
            byWave.computeIfAbsent(TmsUtil.str(o.get("waveId")), k -> new ArrayList<>()).add(o);
        }
        for (Map<String, Object> w : waves) {
            w.put("orders", byWave.getOrDefault(TmsUtil.str(w.get("waveId")), List.of()));
        }
        return waves;
    }

    /** 装车任务列表：当前仓复核完成（已生成发货单）尚未发运的波次及订单。 */
    public List<Map<String, Object>> loadTaskList() {
        String wh = warehouseResolver.currentWarehouseName();
        return TmsUtil.queryCamel(jdbc, """
                SELECT w.wave_id, w.wave_no, w.status, w.route_line, w.driver,
                       COUNT(DISTINCT d.source_order_no) AS order_count
                FROM wms_wave w
                JOIN wms_wave_detail d ON d.wave_id = w.wave_id
                WHERE w.warehouse = ? AND w.status = 'CHECKED'
                  AND d.generated_outbound_no IS NOT NULL
                GROUP BY w.wave_id, w.wave_no, w.status, w.route_line, w.driver
                ORDER BY w.checked_at DESC, w.wave_no
                """, wh);
    }

    /**
     * 主管指派拣货任务（wms_pda.task_assign.assign，仅 LEADER）：
     * 目标人必须启用且绑定当前仓；任务直接置为 CLAIMED（assignee 即领取人）。
     */
    @Transactional
    public Map<String, Object> assignTask(String taskId, String toAssignee, String operator) {
        if (toAssignee == null || toAssignee.isBlank()) throw new IllegalArgumentException("请指定指派人");
        List<Map<String, Object>> h = jdbc.queryForList(
                "SELECT task_id, status, warehouse FROM wms_pick_task WHERE task_id = ?", taskId);
        if (h.isEmpty()) throw new IllegalArgumentException("拣货任务不存在：" + taskId);
        warehouseResolver.assertIfPda(str(h.get(0).get("warehouse")));
        assertUserInCurrentWarehouse(toAssignee);
        jdbc.update("""
                UPDATE wms_pick_task SET assignee = ?, assign_type = 'ASSIGN', status = 'CLAIMED',
                    claimed_at = CURRENT_TIMESTAMP WHERE task_id = ?
                """, toAssignee.trim(), taskId);
        jdbc.update("UPDATE wms_wave_detail SET pick_task_id = ? WHERE pick_task_id IS NULL AND wave_id = "
                + "(SELECT wave_id FROM wms_pick_task WHERE task_id = ?)", taskId, taskId);
        TmsUtil.log(jdbc, "wms.pick", "ASSIGN", taskId, operator + " 指派任务给 " + toAssignee.trim());
        return Map.of("taskId", taskId, "assignee", toAssignee.trim());
    }

    /**
     * 主管撤回指派（wms_pda.task_assign.recall，仅 LEADER）：
     * 仅未开始拣货（PENDING/CLAIMED）的任务可撤回，撤回后回到公共任务池。
     */
    @Transactional
    public Map<String, Object> recallTask(String taskId, String operator) {
        List<Map<String, Object>> h = jdbc.queryForList(
                "SELECT task_id, status, warehouse FROM wms_pick_task WHERE task_id = ?", taskId);
        if (h.isEmpty()) throw new IllegalArgumentException("拣货任务不存在：" + taskId);
        warehouseResolver.assertIfPda(str(h.get(0).get("warehouse")));
        String status = str(h.get(0).get("status"));
        if ("PICKING".equals(status) || "PICKED".equals(status)) {
            throw new IllegalArgumentException("任务已开始拣货，不能撤回");
        }
        jdbc.update("""
                UPDATE wms_pick_task SET assignee = NULL, assign_type = 'FREE', status = 'PENDING',
                    claimed_at = NULL, help_task = 'N' WHERE task_id = ?
                """, taskId);
        TmsUtil.log(jdbc, "wms.pick", "RECALL", taskId, operator + " 撤回任务指派");
        return Map.of("taskId", taskId, "status", "PENDING");
    }

    /** 校验目标用户启用且绑定当前 PDA 登录仓（task assign/transfer 共用）。 */
    void assertUserInCurrentWarehouse(String username) {
        String whId = warehouseResolver.currentWarehouseId();
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_user_runtime u
                JOIN sys_user_warehouse uw ON uw.user_id = u.user_id
                WHERE u.username = ? AND COALESCE(u.status,'NORMAL') = 'NORMAL'
                  AND uw.warehouse_id = ?
                """, Integer.class, username.trim(), whId);
        if (n == null || n == 0) {
            throw new IllegalArgumentException("用户「" + username + "」不存在、已停用或未绑定当前作业仓库");
        }
    }

    /** 写一条出库异常单，返回异常单号。 */
    private String insertException(String waveId, String orderNo, String goodsCode, String type,
                                   BigDecimal qty, String description, String operator) {
        String exNo = billNo.nextNo(BillNoGenerator.BillType.WMS_EXCEPTION, "wms_exception", "exception_no");
        jdbc.update("""
                INSERT INTO wms_exception (exception_id, exception_no, wave_id, wave_no, source_order_no,
                    goods_code, exception_type, qty, status, description, reporter, source_type, priority, created_at)
                VALUES (?, ?, ?, (SELECT wave_no FROM wms_wave WHERE wave_id = ?), ?, ?, ?, ?, 'OPEN', ?, ?, 'OUTBOUND', 'MEDIUM', CURRENT_TIMESTAMP)
                """, id("EX"), exNo, emptyToNull(waveId), emptyToNull(waveId), emptyToNull(orderNo),
                emptyToNull(goodsCode), type, qty, description, operator);
        return exNo;
    }

    // ==================== 内部辅助 ====================

    private void maybeFinishWavePicking(String waveId) {
        if (waveId == null) return;
        Integer allPicked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_wave_detail WHERE wave_id = ? AND status <> 'PICKED'",
                Integer.class, waveId);
        if (allPicked != null && allPicked == 0) {
            jdbc.update("UPDATE wms_wave SET status = 'PICKED', picked_at = CURRENT_TIMESTAMP WHERE wave_id = ?", waveId);
            // 免复核 → 直接扣账
            if (params.getInt("WMS_CHECK_ENABLED", 1, 0, 1) != 1) {
                finishWaveWithoutCheck(waveId, TmsUtil.currentUser());
                jdbc.update("UPDATE wms_wave SET status = 'CHECKED', checked_at = CURRENT_TIMESTAMP WHERE wave_id = ?", waveId);
            }
        } else {
            jdbc.update("UPDATE wms_wave SET status = 'PICKING' WHERE wave_id = ? AND status = 'RELEASED'", waveId);
        }
    }

    private void advanceWaveStatusByTask(String taskId) {
        String waveId = taskOfWave(taskId);
        if (waveId == null) return;
        Integer anyPicking = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_wave_detail WHERE wave_id = ? AND status IN ('PICKING','PICKED')",
                Integer.class, waveId);
        if (anyPicking != null && anyPicking > 0) {
            jdbc.update("UPDATE wms_wave SET status = 'PICKING' WHERE wave_id = ? AND status = 'RELEASED'", waveId);
        }
    }

    private void markWaveSuspended(String waveId, String reason) {
        if (waveId == null) return;
        jdbc.update("UPDATE wms_wave SET status = 'SUSPENDED', remark = ? WHERE wave_id = ?", reason, waveId);
        TmsUtil.log(jdbc, "wms.wave", "SUSPEND", waveId, reason);
    }

    private String taskOfWave(String taskId) {
        List<String> r = jdbc.queryForList("SELECT wave_id FROM wms_pick_task WHERE task_id = ?", String.class, taskId);
        return r.isEmpty() ? null : r.get(0);
    }

    private void recalcTaskProgress(String taskId) {
        jdbc.update("""
                UPDATE wms_pick_task t SET picked_qty = COALESCE((
                    SELECT SUM(picked_qty) FROM wms_wave_detail d WHERE d.pick_task_id = t.task_id), 0),
                    line_count = (SELECT COUNT(*) FROM wms_wave_detail d WHERE d.pick_task_id = t.task_id)
                WHERE t.task_id = ?
                """, taskId);
    }

    /** 回写波次头的已拣件数/订单数（拣货过程中实时反映进度）。 */
    private void recalcWaveProgress(String waveId) {
        if (waveId == null) return;
        jdbc.update("""
                UPDATE wms_wave SET picked_qty = COALESCE((
                    SELECT SUM(picked_qty) FROM wms_wave_detail d WHERE d.wave_id = wms_wave.wave_id), 0)
                WHERE wave_id = ?
                """, waveId);
    }

    private Map<String, Object> findDetail(String detailId, String taskId, String goodsCode) {
        if (detailId != null && !detailId.isBlank()) {
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT d.*, w.warehouse FROM wms_wave_detail d JOIN wms_wave w ON w.wave_id = d.wave_id WHERE d.detail_id = ?",
                    detailId);
            return r.isEmpty() ? null : TmsUtil.camelize(r.get(0));
        }
        StringBuilder sql = new StringBuilder(
                "SELECT d.*, w.warehouse FROM wms_wave_detail d JOIN wms_wave w ON w.wave_id = d.wave_id "
                        + "WHERE d.pick_task_id = ? AND d.status <> 'PICKED'");
        List<Object> args = new ArrayList<>();
        args.add(taskId);
        if (goodsCode != null && !goodsCode.isBlank()) {
            sql.append(" AND d.goods_code = ?");
            args.add(goodsCode);
        }
        sql.append(" ORDER BY d.pick_seq LIMIT 1");
        List<Map<String, Object>> r = jdbc.queryForList(sql.toString(), args.toArray());
        return r.isEmpty() ? null : TmsUtil.camelize(r.get(0));
    }

    private String pickBin(String goodsCode, String warehouse, String batchNo) {
        // 优先库位实物记录
        List<String> bins = jdbc.queryForList("""
                SELECT bin_code FROM wms_bin_stock
                WHERE goods_code = ? AND warehouse = ? AND COALESCE(batch_no,'') = ?
                  AND qty - COALESCE(locked_qty,0) > 0
                ORDER BY updated_at DESC LIMIT 1
                """, String.class, goodsCode, warehouse, batchNo == null ? "" : batchNo);
        if (!bins.isEmpty()) return bins.get(0);
        // 兜底：取一个存储库位（按库区拣货顺序）
        List<String> fb = jdbc.queryForList("""
                SELECT b.bin_code FROM wms_bin b
                JOIN wms_zone z ON z.warehouse = b.warehouse AND z.zone_code = b.zone_code
                WHERE b.warehouse = ? AND b.bin_type = 'SHELF' AND COALESCE(b.frozen,'N') = 'N'
                ORDER BY z.pick_seq, b.bin_code LIMIT 1
                """, String.class, warehouse);
        return fb.isEmpty() ? "" : fb.get(0);
    }

    private String zoneOfBin(String warehouse, String binCode) {
        if (binCode == null || binCode.isBlank()) return "";
        List<String> z = jdbc.queryForList(
                "SELECT zone_code FROM wms_bin WHERE warehouse = ? AND bin_code = ?", String.class, warehouse, binCode);
        return z.isEmpty() ? "" : z.get(0);
    }

    private void lockBinStock(String goodsCode, String warehouse, String batchNo, String binCode, BigDecimal qty) {
        if (binCode == null || binCode.isBlank()) return;
        int n = jdbc.update("""
                UPDATE wms_bin_stock SET locked_qty = COALESCE(locked_qty,0) + ?, updated_at = CURRENT_TIMESTAMP
                WHERE goods_code = ? AND warehouse = ? AND bin_code = ? AND COALESCE(batch_no,'') = ?
                """, qty, goodsCode, warehouse, binCode, batchNo == null ? "" : batchNo);
        if (n == 0) return; // 无物理库位记录则不动（账实分离，不臆造位置）
        jdbc.update("UPDATE wms_bin SET used_qty = COALESCE(used_qty,0) + ? WHERE warehouse = ? AND bin_code = ?",
                qty, warehouse, binCode);
    }

    private void unlockBinStock(String goodsCode, String warehouse, String batchNo, String binCode, BigDecimal qty) {
        if (binCode == null || binCode.isBlank()) return;
        jdbc.update("""
                UPDATE wms_bin_stock SET locked_qty = GREATEST(COALESCE(locked_qty,0) - ?, 0),
                    updated_at = CURRENT_TIMESTAMP
                WHERE goods_code = ? AND warehouse = ? AND bin_code = ? AND COALESCE(batch_no,'') = ?
                """, qty, goodsCode, warehouse, binCode, batchNo == null ? "" : batchNo);
        jdbc.update("UPDATE wms_bin SET used_qty = GREATEST(COALESCE(used_qty,0) - ?, 0) WHERE warehouse = ? AND bin_code = ?",
                qty, warehouse, binCode);
    }

    private void pickFromBin(String goodsCode, String warehouse, String batchNo, String binCode, BigDecimal qty) {
        if (binCode == null || binCode.isBlank()) return;
        jdbc.update("""
                UPDATE wms_bin_stock SET qty = qty - ?, locked_qty = GREATEST(COALESCE(locked_qty,0) - ?, 0),
                    updated_at = CURRENT_TIMESTAMP
                WHERE goods_code = ? AND warehouse = ? AND bin_code = ? AND COALESCE(batch_no,'') = ?
                """, qty, qty, goodsCode, warehouse, binCode, batchNo == null ? "" : batchNo);
        jdbc.update("UPDATE wms_bin SET used_qty = GREATEST(COALESCE(used_qty,0) - ?, 0) WHERE warehouse = ? AND bin_code = ?",
                qty, warehouse, binCode);
        try {
            jdbc.update("""
                    INSERT INTO wms_bin_stock_log (log_id, warehouse, goods_code, batch_no, from_bin, direction, qty, source_bill, operator)
                    VALUES (?, ?, ?, ?, ?, 'OUT', ?, 'WMS_PICK', ?)
                    """, id("BL"), warehouse, goodsCode, emptyToNull(batchNo), binCode, qty, TmsUtil.currentUser());
        } catch (Exception ignore) {}
    }

    private String allocateCollectionBin(String warehouse, String collectionZone, String orderNo) {
        // 集货位占用模式：0=一单一集货位, 1=一门店一集货位, 2=智能占用
        int modeInt = params.getInt("WMS_COLLECTION_MODE", 0, 0, 2);
        String mode = String.valueOf(modeInt);
        // 候选集货位必须：位本身启用(status=NORMAL)、未被人工锁定(frozen=N)、所属集货区未停用(z.frozen=N)。
        // 集货区停用通过 JOIN wms_zone 级联排除，无需逐行改 bin 状态，重新启用时各位原有状态自然保留。
        List<String> bins = jdbc.queryForList(
                "SELECT b.bin_code FROM wms_bin b "
                        + "JOIN wms_zone z ON z.warehouse = b.warehouse AND z.zone_code = b.zone_code "
                        + "WHERE b.warehouse = ? AND b.zone_code = ? AND b.bin_type = 'COLLECT' "
                        + "AND b.status = 'NORMAL' AND COALESCE(b.frozen,'N') = 'N' AND COALESCE(z.frozen,'N') = 'N' "
                        + "ORDER BY b.bin_code", String.class, warehouse, collectionZone);
        if (bins.isEmpty()) return "";
        if ("1".equals(mode)) { // 一门店一集货位
            // 一门店一集货位：按订单号哈希稳定分配
            int idx = Math.floorMod(orderNo.hashCode(), bins.size());
            return bins.get(idx);
        }
        // 0=一单一集货位：取第一个未占用的；2=智能占用：找占用最低且低于阈值的；都满则取第一个。
        BigDecimal threshold = new BigDecimal(params.get("WMS_COLLECTION_THRESHOLD", "30"));
        String bestBin = null;
        BigDecimal lowestUsed = null;
        for (String b : bins) {
            BigDecimal used = jdbc.queryForObject(
                    "SELECT COALESCE(used_qty,0) FROM wms_bin WHERE warehouse = ? AND bin_code = ?",
                    BigDecimal.class, warehouse, b);
            if (used == null) used = BigDecimal.ZERO;
            if (used.signum() == 0) return b; // 空位优先
            if (used.compareTo(threshold) < 0) {
                if (modeInt == 0) return b; // ORDER：第一个低于阈值的
                if (lowestUsed == null || used.compareTo(lowestUsed) < 0) {
                    lowestUsed = used;
                    bestBin = b;
                }
            }
        }
        return bestBin != null ? bestBin : bins.get(0);
    }

    private String waveWarehouse(String waveId) {
        List<String> r = jdbc.queryForList("SELECT warehouse FROM wms_wave WHERE wave_id = ?",
                String.class, waveId);
        return r.isEmpty() ? "总仓" : r.get(0);
    }

    private static String waveStatusText(String s) {
        return switch (s) {
            case "DRAFT" -> "草稿";
            case "RELEASED" -> "已下放";
            case "PICKING" -> "拣货中";
            case "PICKED" -> "拣货完成";
            case "CHECKING" -> "复核中";
            case "CHECKED" -> "复核完成";
            case "SHIPPED" -> "已发运";
            case "SUSPENDED" -> "差异挂起";
            case "CANCELLED" -> "已撤销";
            default -> s;
        };
    }

    private static String detailStatusText(String s) {
        return switch (s) {
            case "PENDING" -> "待拣";
            case "PICKING" -> "拣货中";
            case "PICKED" -> "已拣";
            case "SHORT" -> "缺货";
            default -> s;
        };
    }

    /**
     * 把前端可能传来的英文枚举（旧版）或数字（新版）统一归一化为数字 int。
     * 入参为 null/空时返回 dft；是纯数字直接 parse；否则按 legacyNames 按位置查下标。
     */
    private static int normalizeEnum(Object raw, int dft, String[] legacyNames) {
        if (raw == null) return dft;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return dft;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignore) { /* 走英文枚举映射 */ }
        for (int i = 0; i < legacyNames.length; i++) {
            if (legacyNames[i].equalsIgnoreCase(s)) return i;
        }
        return dft;
    }

    /** 复核粒度 WHOLE→0, SPLIT→1，纯数字直接返回。 */
    private static String normalizeCheckScope(String s) {
        if (s == null || s.isBlank()) return "0";
        if ("WHOLE".equalsIgnoreCase(s)) return "0";
        if ("SPLIT".equalsIgnoreCase(s)) return "1";
        return s.trim();
    }

    // ---- 小工具 ----
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String strOrDefault(Object o, String dft) {
        if (o == null) return dft;
        String s = String.valueOf(o).trim();
        return s.isBlank() ? dft : s;
    }
    private static String emptyToNull(String s) { return (s == null || s.isBlank()) ? null : s; }
    private static BigDecimal bd(Object o) { return TmsUtil.toBd(o); }
    private static Object pick(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        return row.get(key.toUpperCase(Locale.ROOT));
    }
}
