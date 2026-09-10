package com.erp.wms;

import com.erp.common.security.PermissionService;
import com.erp.common.util.BillNoGenerator;
import com.erp.common.util.BillNoGenerator.BillType;
import com.erp.inventory.service.InventoryCostService;
import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WMS V1.5 库内作业服务（PRD-28）。
 *
 * <p>覆盖：补货（被动/主动/加急）、移库移位、库存冻结/解冻、库存调整审批、报损审批、
 * 组装拆卸、盘点（动盘/全盘/抽盘/循环盘）、效期预警刷新、看板指标。
 *
 * <p><b>账实分离</b>：库内作业只动 wms_bin_stock 物理位置账；财务库存只在「报损审批」「盘点差异审批」
 * 两类需要改变账面数量的场景调用 {@link InventoryCostService}，其余位置移动不改财务库存。
 */
@Service
public class WmsInternalService {

    /** 库存查询-成本单价功能点（还需 VIEW_COST 字段授权，双控）。 */
    public static final String FUNC_STOCK_VIEW_COST = "wms_pda.stock_query.view_cost";
    /** 库存查询-批次列功能点。 */
    public static final String FUNC_STOCK_VIEW_BATCH = "wms_pda.stock_query.view_batch";

    private final JdbcTemplate jdbc;
    private final BillNoGenerator billNo;
    private final SysParamService params;
    private final InventoryCostService inventoryCost;
    private final WmsWarehouseResolver warehouseResolver;
    private final PermissionService permissionService;

    public WmsInternalService(JdbcTemplate jdbc, BillNoGenerator billNo, SysParamService params,
                              InventoryCostService inventoryCost,
                              WmsWarehouseResolver warehouseResolver,
                              PermissionService permissionService) {
        this.jdbc = jdbc;
        this.billNo = billNo;
        this.params = params;
        this.inventoryCost = inventoryCost;
        this.warehouseResolver = warehouseResolver;
        this.permissionService = permissionService;
    }

    // ==================== 补货 ====================

    /**
     * 主动补货扫描：对拣货位（PICK 类型）按容量阈值生成补货任务，从同品存储位补货。
     * 阈值规则：当拣货位数量 < 容量 * threshold%，补到容量上限。
     */
    @Transactional
    public int generateActiveReplenishment(String warehouse, int thresholdPct) {
        List<Map<String, Object>> pickBins = jdbc.queryForList("""
                SELECT b.bin_code, b.capacity_qty, COALESCE(SUM(s.qty),0) AS on_hand
                FROM wms_bin b
                LEFT JOIN wms_bin_stock s ON s.bin_code = b.bin_code AND s.warehouse = b.warehouse
                WHERE b.warehouse = ? AND b.bin_type IN ('PICK','SHELF')
                  AND COALESCE(b.frozen,'N')='N' AND COALESCE(b.status,'NORMAL')='NORMAL'
                GROUP BY b.bin_code, b.capacity_qty
                HAVING b.capacity_qty IS NOT NULL AND b.capacity_qty > 0
                   AND COALESCE(SUM(s.qty),0) < b.capacity_qty * ? / 100
                """, warehouse, thresholdPct);
        int created = 0;
        for (Map<String, Object> pb : pickBins) {
            String toBin = TmsUtil.str(pb.get("bin_code"));
            BigDecimal cap = toBd(pb.get("capacity_qty"));
            BigDecimal onHand = toBd(pb.get("on_hand"));
            BigDecimal need = cap.subtract(onHand);
            if (need.signum() <= 0) continue;
            // 找同品库存最多的存储位作为源
            List<Map<String, Object>> src = jdbc.queryForList("""
                    SELECT s.goods_code, s.goods_name, s.batch_no, s.bin_code,
                           (s.qty - COALESCE(s.locked_qty,0)) AS avail
                    FROM wms_bin_stock s
                    WHERE s.warehouse = ? AND s.bin_code <> ?
                      AND (s.qty - COALESCE(s.locked_qty,0)) > 0
                      AND s.goods_code IN (SELECT goods_code FROM wms_bin_stock WHERE bin_code = ? AND warehouse = ?)
                    ORDER BY avail DESC
                    """, warehouse, toBin, toBin, warehouse);
            for (Map<String, Object> s : src) {
                if (need.signum() <= 0) break;
                BigDecimal avail = toBd(s.get("avail"));
                BigDecimal move = avail.min(need);
                createReplenish(warehouse, TmsUtil.str(s.get("goods_code")), TmsUtil.str(s.get("goods_name")),
                        TmsUtil.str(s.get("batch_no")), TmsUtil.str(s.get("bin_code")), toBin, move, "ACTIVE", null);
                need = need.subtract(move);
                created++;
            }
        }
        return created;
    }

    /** 加急补货：拣货缺货时从波次触发，直接生成一条 PENDING 补货任务。 */
    @Transactional
    public Map<String, Object> urgentReplenish(String goodsCode, String batchNo, String toBin,
                                               BigDecimal qty, String waveId, String operator) {
        // 找源库位：同品同批次，非目标位，按可用量降序
        List<Map<String, Object>> src = jdbc.queryForList("""
                SELECT bin_code FROM wms_bin_stock
                WHERE goods_code = ? AND COALESCE(batch_no,'') = ? AND bin_code <> ?
                  AND warehouse = (SELECT warehouse FROM wms_wave WHERE wave_id = ?)
                  AND (qty - COALESCE(locked_qty,0)) > 0
                ORDER BY (qty - COALESCE(locked_qty,0)) DESC LIMIT 1
                """, goodsCode, batchNo == null ? "" : batchNo, toBin, waveId);
        if (src.isEmpty()) throw new IllegalArgumentException("找不到可用库存源库位");
        String fromBin = TmsUtil.str(src.get(0).get("bin_code"));
        List<String> whRows = jdbc.queryForList(
                "SELECT warehouse FROM wms_wave WHERE wave_id = ?", String.class, waveId);
        if (whRows.isEmpty()) throw new IllegalArgumentException("波次不存在：" + waveId);
        warehouseResolver.assertIfPda(whRows.get(0));
        String warehouse = whRows.get(0);
        String id = createReplenish(warehouse, goodsCode, "", batchNo, fromBin, toBin, qty, "URGENT", waveId);
        jdbc.update("UPDATE wms_replenish_task SET assignee = ? WHERE task_id = ?", operator, id);
        return Map.of("taskId", id, "fromBin", fromBin);
    }

    private String createReplenish(String warehouse, String goodsCode, String goodsName, String batchNo,
                                   String fromBin, String toBin, BigDecimal qty, String triggerType, String waveId) {
        String id = "RP" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        String no = billNo.nextNo(BillType.WMS_REPLENISH, "wms_replenish_task", "task_no");
        jdbc.update("""
                INSERT INTO wms_replenish_task
                (task_id, task_no, warehouse, goods_code, goods_name, batch_no, from_bin, to_bin,
                 qty, trigger_type, wave_id, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                """, id, no, warehouse, goodsCode, goodsName, emptyToNull(batchNo), fromBin, toBin,
                qty, triggerType, waveId);
        return id;
    }

    public List<Map<String, Object>> listReplenish(String status, String assignee) {
        StringBuilder sql = new StringBuilder("""
                SELECT task_id, task_no, warehouse, goods_code, goods_name, batch_no, from_bin, to_bin,
                       qty, trigger_type, wave_id, assignee, status, remark, created_at, started_at, finished_at
                FROM wms_replenish_task WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        // PRD-28 卡片9：PDA 强制当前作业仓（PDA-007）
        String pdaWh = warehouseResolver.pdaWarehouseNameOrNull();
        if (pdaWh != null) { sql.append(" AND warehouse = ?"); args.add(pdaWh); }
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); args.add(status); }
        if (assignee != null && !assignee.isBlank()) { sql.append(" AND (assignee = ? OR assignee IS NULL)"); args.add(assignee); }
        sql.append(" ORDER BY created_at DESC");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    /** PDA 领取补货任务。 */
    @Transactional
    public Map<String, Object> claimReplenish(String taskId, String operator) {
        List<Map<String, Object>> h = jdbc.queryForList(
                "SELECT warehouse, status, assignee FROM wms_replenish_task WHERE task_id = ?", taskId);
        if (h.isEmpty()) throw new IllegalArgumentException("补货任务不存在");
        warehouseResolver.assertIfPda(TmsUtil.str(h.get(0).get("warehouse")));
        int n = jdbc.update("""
                UPDATE wms_replenish_task SET assignee = ?, status = 'PICKING', started_at = CURRENT_TIMESTAMP
                WHERE task_id = ? AND (assignee IS NULL OR assignee = '' OR assignee = ?)
                """, operator, taskId, operator);
        if (n == 0) throw new IllegalArgumentException("任务已被他人领取");
        return Map.of("taskId", taskId, "assignee", operator);
    }

    /** 补货完成：把库存从源库位移到目标库位（wms_bin_stock 内迁移，不改财务库存）。 */
    @Transactional
    public Map<String, Object> completeReplenish(String taskId, String operator) {
        List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_replenish_task WHERE task_id = ?", taskId);
        if (r.isEmpty()) throw new IllegalArgumentException("补货任务不存在");
        warehouseResolver.assertIfPda(TmsUtil.str(r.get(0).get("warehouse")));
        Map<String, Object> t = r.get(0);
        if ("DONE".equals(t.get("status"))) return Map.of("taskId", taskId, "already", true);
        moveBinStock(TmsUtil.str(t.get("goodsCode")), TmsUtil.str(t.get("warehouse")),
                TmsUtil.str(t.get("batchNo")), TmsUtil.str(t.get("fromBin")),
                TmsUtil.str(t.get("toBin")), toBd(t.get("qty")), "WMS_REPLENISH:" + t.get("taskNo"));
        jdbc.update("UPDATE wms_replenish_task SET status='DONE', finished_at=CURRENT_TIMESTAMP WHERE task_id=?", taskId);
        return Map.of("taskId", taskId, "status", "DONE");
    }

    // ==================== 移库移位 ====================

    @Transactional
    public Map<String, Object> createMove(Map<String, Object> req, String operator) {
        // PRD-28 卡片9：PDA 端仓库强制取登录仓，忽略 body 传入值
        String pdaWh = warehouseResolver.pdaWarehouseNameOrNull();
        String warehouse = pdaWh != null ? pdaWh : strOr(req.get("warehouse"), "总仓");
        String id = "MV" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        String no = billNo.nextNo(BillType.WMS_MOVE, "wms_move_task", "task_no");
        jdbc.update("""
                INSERT INTO wms_move_task
                (task_id, task_no, warehouse, move_type, from_bin, to_bin, goods_code, goods_name,
                 batch_no, qty, assignee, status, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP)
                """, id, no, warehouse, strOr(req.get("moveType"), "ACTIVE"),
                TmsUtil.str(req.get("fromBin")), TmsUtil.str(req.get("toBin")),
                TmsUtil.str(req.get("goodsCode")), TmsUtil.str(req.get("goodsName")),
                emptyToNull(TmsUtil.str(req.get("batchNo"))), toBd(req.get("qty")),
                emptyToNull(TmsUtil.str(req.get("assignee"))), TmsUtil.str(req.get("remark")));
        TmsUtil.log(jdbc, "wms.move", "CREATE", no, "建移库单 " + operator);
        return Map.of("taskId", id, "taskNo", no);
    }

    public List<Map<String, Object>> listMove(String status, String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT task_id, task_no, warehouse, move_type, from_bin, to_bin, goods_code, goods_name,
                       batch_no, qty, moved_qty, assignee, status, remark, created_at, finished_at
                FROM wms_move_task WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String pdaWh = warehouseResolver.pdaWarehouseNameOrNull();
        if (pdaWh != null) { sql.append(" AND warehouse = ?"); args.add(pdaWh); }
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); args.add(status); }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(task_no) LIKE ? OR LOWER(goods_code) LIKE ? OR LOWER(goods_name) LIKE ?)");
            String k = "%" + keyword.toLowerCase() + "%";
            args.add(k); args.add(k); args.add(k);
        }
        sql.append(" ORDER BY created_at DESC");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    @Transactional
    public Map<String, Object> completeMove(String taskId, String operator) {
        List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_move_task WHERE task_id = ?", taskId);
        if (r.isEmpty()) throw new IllegalArgumentException("移库任务不存在");
        warehouseResolver.assertIfPda(TmsUtil.str(r.get(0).get("warehouse")));
        Map<String, Object> t = r.get(0);
        if ("DONE".equals(t.get("status"))) return Map.of("taskId", taskId, "already", true);
        moveBinStock(TmsUtil.str(t.get("goodsCode")), TmsUtil.str(t.get("warehouse")),
                TmsUtil.str(t.get("batchNo")), TmsUtil.str(t.get("fromBin")),
                TmsUtil.str(t.get("toBin")), toBd(t.get("qty")), "WMS_MOVE:" + t.get("taskNo"));
        jdbc.update("""
                UPDATE wms_move_task SET status='DONE', moved_qty=qty, assignee=COALESCE(NULLIF(?, ''), assignee),
                    finished_at=CURRENT_TIMESTAMP WHERE task_id=?
                """, operator, taskId);
        return Map.of("taskId", taskId, "status", "DONE");
    }

    // ==================== 冻结 / 解冻 ====================

    @Transactional
    public Map<String, Object> freeze(Map<String, Object> req, String operator) {
        String id = "FZ" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        String binCode = TmsUtil.str(req.get("binCode"));
        String goodsCode = TmsUtil.str(req.get("goodsCode"));
        String batchNo = TmsUtil.str(req.get("batchNo"));
        BigDecimal qty = toBd(req.get("qty"));
        jdbc.update("""
                INSERT INTO wms_freeze_record
                (freeze_id, warehouse, bin_code, goods_code, batch_no, qty, reason, status, operator, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'FROZEN', ?, ?, CURRENT_TIMESTAMP)
                """, id, strOr(req.get("warehouse"), "总仓"), binCode, emptyToNull(goodsCode),
                emptyToNull(batchNo), qty, TmsUtil.str(req.get("reason")), operator,
                TmsUtil.str(req.get("remark")));
        // 整库位冻结：直接冻 bin；行级冻结：扣 wms_bin_stock.qty 不动，加 locked_qty 占位
        if ((goodsCode == null || goodsCode.isBlank()) && qty.signum() == 0) {
            jdbc.update("UPDATE wms_bin SET frozen='Y' WHERE warehouse=? AND bin_code=?",
                    strOr(req.get("warehouse"), "总仓"), binCode);
        } else {
            jdbc.update("""
                    UPDATE wms_bin_stock SET locked_qty = COALESCE(locked_qty,0) + ?
                    WHERE warehouse=? AND bin_code=? AND goods_code=? AND COALESCE(batch_no,'')=?
                    """, qty, strOr(req.get("warehouse"), "总仓"), binCode, goodsCode,
                    batchNo == null ? "" : batchNo);
        }
        TmsUtil.log(jdbc, "wms.freeze", "FREEZE", id, "冻结 " + binCode + " " + goodsCode);
        return Map.of("freezeId", id);
    }

    @Transactional
    public Map<String, Object> unfreeze(String freezeId, String operator) {
        List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_freeze_record WHERE freeze_id = ?", freezeId);
        if (r.isEmpty()) throw new IllegalArgumentException("冻结记录不存在");
        Map<String, Object> f = r.get(0);
        if ("UNFROZEN".equals(f.get("status"))) return Map.of("freezeId", freezeId, "already", true);
        String goodsCode = TmsUtil.str(f.get("goodsCode"));
        BigDecimal qty = toBd(f.get("qty"));
        if ((goodsCode == null || goodsCode.isBlank()) && qty.signum() == 0) {
            jdbc.update("UPDATE wms_bin SET frozen='N' WHERE warehouse=? AND bin_code=?",
                    f.get("warehouse"), f.get("binCode"));
        } else {
            jdbc.update("""
                    UPDATE wms_bin_stock SET locked_qty = GREATEST(COALESCE(locked_qty,0) - ?, 0)
                    WHERE warehouse=? AND bin_code=? AND goods_code=? AND COALESCE(batch_no,'')=?
                    """, qty, f.get("warehouse"), f.get("binCode"), goodsCode,
                    TmsUtil.str(f.get("batchNo")));
        }
        jdbc.update("""
                UPDATE wms_freeze_record SET status='UNFROZEN', unfreeze_operator=?, unfrozen_at=CURRENT_TIMESTAMP
                WHERE freeze_id=?
                """, operator, freezeId);
        return Map.of("freezeId", freezeId, "status", "UNFROZEN");
    }

    public List<Map<String, Object>> listFreeze(String status) {
        return TmsUtil.queryCamel(jdbc, """
                SELECT freeze_id, warehouse, bin_code, goods_code, batch_no, qty, reason, status,
                       operator, unfreeze_operator, remark, created_at, unfrozen_at
                FROM wms_freeze_record
                WHERE (? IS NULL OR status = ?)
                ORDER BY created_at DESC
                """, status, status);
    }

    // ==================== 库存调整（盘盈/盘亏） ====================

    @Transactional
    public Map<String, Object> createAdjust(Map<String, Object> req, String operator) {
        String id = "AD" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        String no = billNo.nextNo(BillType.WMS_ADJUST, "wms_adjust_record", "adjust_no");
        String goodsCode = TmsUtil.str(req.get("goodsCode"));
        String batchNo = TmsUtil.str(req.get("batchNo"));
        String warehouse = strOr(req.get("warehouse"), "总仓");
        BigDecimal book = toBd(req.get("bookQty"));
        BigDecimal actual = toBd(req.get("actualQty"));
        BigDecimal diff = actual.subtract(book);
        String type = diff.signum() >= 0 ? "GAIN" : "LOSS";
        jdbc.update("""
                INSERT INTO wms_adjust_record
                (adjust_id, adjust_no, warehouse, adjust_type, bin_code, goods_code, goods_name, batch_no,
                 book_qty, actual_qty, adjust_qty, reason, status, operator, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, CURRENT_TIMESTAMP)
                """, id, no, warehouse, type, TmsUtil.str(req.get("binCode")), goodsCode,
                TmsUtil.str(req.get("goodsName")), emptyToNull(batchNo), book, actual, diff,
                TmsUtil.str(req.get("reason")), operator, TmsUtil.str(req.get("remark")));
        return Map.of("adjustId", id, "adjustNo", no, "adjustType", type, "adjustQty", diff);
    }

    @Transactional
    public Map<String, Object> approveAdjust(String adjustId, boolean approved, String approver, String remark) {
        List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_adjust_record WHERE adjust_id = ?", adjustId);
        if (r.isEmpty()) throw new IllegalArgumentException("调整单不存在");
        Map<String, Object> a = r.get(0);
        warehouseResolver.assertIfPda(TmsUtil.str(a.get("warehouse")));
        if (!"PENDING".equals(a.get("status"))) throw new IllegalArgumentException("调整单已审批");
        if (!approved) {
            jdbc.update("UPDATE wms_adjust_record SET status='REJECTED', approver=?, remark=? WHERE adjust_id=?",
                    approver, remark, adjustId);
            return Map.of("adjustId", adjustId, "status", "REJECTED");
        }
        // 审批通过：盘盈入 / 盘亏出。走 inboundAtCurrentCost（盘盈按当前成本建账）；
        // 盘亏走销售出库会污染销售数据，这里直接调 inventoryCost 的底层扣减暂不可得，
        // 采用：盘亏走其他出库由财务侧模块处理；本服务只改 wms_bin_stock 实物，财务库存差异由
        // InventoryCostService 提供的 salesOutbound 反向不合适，因此统一调用 inboundAtCurrentCost 传负数
        // （该方法本身不允许负数会抛异常，所以盘亏需要单独的出库 API）。
        // 当前阶段：盘盈调 inboundAtCurrentCost；盘亏只改 wms_bin_stock 实物并写日志，财务差异待对接。
        BigDecimal diff = toBd(a.get("adjustQty"));
        String goodsCode = TmsUtil.str(a.get("goodsCode"));
        String goodsName = TmsUtil.str(a.get("goodsName"));
        String warehouse = TmsUtil.str(a.get("warehouse"));
        String batchNo = TmsUtil.str(a.get("batchNo"));
        if (diff.signum() > 0) {
            inventoryCost.inboundAtCurrentCost(goodsCode, goodsName, warehouse, batchNo, diff,
                    BigDecimal.ZERO, "WMS_ADJUST_GAIN:" + a.get("adjustNo"), null);
            addBinStock(goodsCode, goodsName, warehouse, batchNo, TmsUtil.str(a.get("binCode")), diff);
        } else {
            // 盘亏：从实物位扣减（不动财务，由报损/其他出库对接）
            BigDecimal loss = diff.abs();
            jdbc.update("""
                    UPDATE wms_bin_stock SET qty = GREATEST(qty - ?, 0), updated_at = CURRENT_TIMESTAMP
                    WHERE goods_code=? AND warehouse=? AND bin_code=? AND COALESCE(batch_no,'')=?
                    """, loss, goodsCode, warehouse, a.get("binCode"),
                    batchNo == null ? "" : batchNo);
        }
        jdbc.update("UPDATE wms_adjust_record SET status='APPROVED', approver=?, approved_at=CURRENT_TIMESTAMP WHERE adjust_id=?",
                approver, adjustId);
        TmsUtil.log(jdbc, "wms.adjust", "APPROVE", TmsUtil.str(a.get("adjustNo")),
                "审批调整 差异=" + diff);
        return Map.of("adjustId", adjustId, "status", "APPROVED");
    }

    public List<Map<String, Object>> listAdjust(String status) {
        String pdaWh = warehouseResolver.pdaWarehouseNameOrNull();
        return TmsUtil.queryCamel(jdbc, """
                SELECT adjust_id, adjust_no, warehouse, adjust_type, bin_code, goods_code, goods_name,
                       batch_no, book_qty, actual_qty, adjust_qty, reason, status, operator, approver,
                       remark, created_at, approved_at
                FROM wms_adjust_record
                WHERE (? IS NULL OR status = ?) AND (? IS NULL OR warehouse = ?)
                ORDER BY created_at DESC
                """, status, status, pdaWh, pdaWh);
    }

    // ==================== 报损 ====================

    @Transactional
    public Map<String, Object> createDamage(Map<String, Object> req, String operator) {
        if (params.getInt("WMS_DAMAGE_NEED_PHOTO", 1, 0, 1) == 1) {
            String img = TmsUtil.str(req.get("imageUrl"));
            if (img.isBlank()) throw new IllegalArgumentException("当前参数要求报损必须上传照片");
        }
        String id = "DM" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        String no = billNo.nextNo(BillType.WMS_DAMAGE, "wms_damage_record", "damage_no");
        String goodsCode = TmsUtil.str(req.get("goodsCode"));
        // PRD-28 卡片9：PDA 端仓库强制取登录仓
        String pdaWhD = warehouseResolver.pdaWarehouseNameOrNull();
        String warehouse = pdaWhD != null ? pdaWhD : strOr(req.get("warehouse"), "总仓");
        BigDecimal qty = toBd(req.get("qty"));
        BigDecimal cost = BigDecimal.ZERO;
        try {
            BigDecimal cp = inventoryCost.getCurrentCostPrice(goodsCode, warehouse);
            if (cp != null) cost = cp.multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (Exception ignore) {}
        jdbc.update("""
                INSERT INTO wms_damage_record
                (damage_id, damage_no, warehouse, bin_code, goods_code, goods_name, batch_no,
                 qty, cost_amount, reason, responsibility, image_url, status, operator, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, CURRENT_TIMESTAMP)
                """, id, no, warehouse, TmsUtil.str(req.get("binCode")), goodsCode,
                TmsUtil.str(req.get("goodsName")), emptyToNull(TmsUtil.str(req.get("batchNo"))),
                qty, cost, TmsUtil.str(req.get("reason")), TmsUtil.str(req.get("responsibility")),
                TmsUtil.str(req.get("imageUrl")), operator, TmsUtil.str(req.get("remark")));
        return Map.of("damageId", id, "damageNo", no, "costAmount", cost);
    }

    @Transactional
    public Map<String, Object> approveDamage(String damageId, boolean approved, String approver) {
        List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_damage_record WHERE damage_id = ?", damageId);
        if (r.isEmpty()) throw new IllegalArgumentException("报损单不存在");
        Map<String, Object> d = r.get(0);
        warehouseResolver.assertIfPda(TmsUtil.str(d.get("warehouse")));
        if (!"PENDING".equals(d.get("status"))) throw new IllegalArgumentException("报损单已审批");
        if (!approved) {
            jdbc.update("UPDATE wms_damage_record SET status='REJECTED', approver=? WHERE damage_id=?", approver, damageId);
            return Map.of("damageId", damageId, "status", "REJECTED");
        }
        // 审批通过：从实物位扣减（财务侧由其他出库/报损出库单对接，当前阶段只做实物）
        BigDecimal qty = toBd(d.get("qty"));
        jdbc.update("""
                UPDATE wms_bin_stock SET qty = GREATEST(qty - ?, 0), updated_at = CURRENT_TIMESTAMP
                WHERE goods_code=? AND warehouse=? AND bin_code=? AND COALESCE(batch_no,'')=?
                """, qty, d.get("goodsCode"), d.get("warehouse"), d.get("binCode"),
                TmsUtil.str(d.get("batchNo")));
        jdbc.update("UPDATE wms_damage_record SET status='APPROVED', approver=?, approved_at=CURRENT_TIMESTAMP WHERE damage_id=?",
                approver, damageId);
        TmsUtil.log(jdbc, "wms.damage", "APPROVE", TmsUtil.str(d.get("damageNo")), "报损审批 x" + qty);
        return Map.of("damageId", damageId, "status", "APPROVED");
    }

    public List<Map<String, Object>> listDamage(String status) {
        String pdaWh = warehouseResolver.pdaWarehouseNameOrNull();
        Object whArg = pdaWh;
        return TmsUtil.queryCamel(jdbc, """
                SELECT damage_id, damage_no, warehouse, bin_code, goods_code, goods_name, batch_no,
                       qty, cost_amount, reason, responsibility, image_url, status, operator, approver,
                       remark, created_at, approved_at
                FROM wms_damage_record
                WHERE (? IS NULL OR status = ?) AND (? IS NULL OR warehouse = ?)
                ORDER BY created_at DESC
                """, status, status, whArg, whArg);
    }

    // ==================== 组装 / 拆卸 ====================

    @Transactional
    public Map<String, Object> createAssembly(Map<String, Object> req) {
        String id = "AS" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        String no = billNo.nextNo(BillType.WMS_ASSEMBLY, "wms_assembly_task", "task_no");
        jdbc.update("""
                INSERT INTO wms_assembly_task
                (task_id, task_no, task_type, finished_goods_code, finished_goods_name, qty,
                 source_order_no, station_bin, warehouse, assignee, status, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP)
                """, id, no, strOr(req.get("taskType"), "ASSEMBLY"),
                TmsUtil.str(req.get("finishedGoodsCode")), TmsUtil.str(req.get("finishedGoodsName")),
                toBd(req.get("qty")), TmsUtil.str(req.get("sourceOrderNo")),
                TmsUtil.str(req.get("stationBin")), strOr(req.get("warehouse"), "总仓"),
                emptyToNull(TmsUtil.str(req.get("assignee"))), TmsUtil.str(req.get("remark")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comps = (List<Map<String, Object>>) req.getOrDefault("components", List.of());
        for (Map<String, Object> c : comps) {
            BigDecimal unitQty = toBd(c.get("unitQty"));
            BigDecimal required = unitQty.multiply(toBd(req.get("qty")));
            jdbc.update("""
                    INSERT INTO wms_assembly_component
                    (id, task_id, goods_code, goods_name, unit_qty, required_qty, batch_no, bin_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, "AC" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(),
                    id, TmsUtil.str(c.get("goodsCode")), TmsUtil.str(c.get("goodsName")),
                    unitQty, required, emptyToNull(TmsUtil.str(c.get("batchNo"))),
                    TmsUtil.str(c.get("binCode")));
        }
        return Map.of("taskId", id, "taskNo", no);
    }

    public List<Map<String, Object>> listAssembly(String status) {
        return TmsUtil.queryCamel(jdbc, """
                SELECT task_id, task_no, task_type, finished_goods_code, finished_goods_name, qty,
                       source_order_no, station_bin, warehouse, assignee, status, completed_qty,
                       remark, created_at, finished_at
                FROM wms_assembly_task
                WHERE (? IS NULL OR status = ?)
                ORDER BY created_at DESC
                """, status, status);
    }

    /**
     * 组装完成：把组件从源库位移到工位并扣减（实物位置账），成品入工位或成品库位。
     * 财务成本重算由财务侧「组装单」对接，本方法只维护实物。
     */
    @Transactional
    public Map<String, Object> completeAssembly(String taskId) {
        List<Map<String, Object>> t = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_assembly_task WHERE task_id = ?", taskId);
        if (t.isEmpty()) throw new IllegalArgumentException("组装任务不存在");
        Map<String, Object> task = t.get(0);
        if ("DONE".equals(task.get("status"))) return Map.of("taskId", taskId, "already", true);
        List<Map<String, Object>> comps = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_assembly_component WHERE task_id = ?", taskId);
        String station = TmsUtil.str(task.get("stationBin"));
        for (Map<String, Object> c : comps) {
            BigDecimal picked = toBd(c.get("pickedQty"));
            if (picked.signum() <= 0) picked = toBd(c.get("requiredQty"));
            if (picked.signum() <= 0) continue;
            moveBinStock(TmsUtil.str(c.get("goodsCode")), TmsUtil.str(task.get("warehouse")),
                    TmsUtil.str(c.get("batchNo")), TmsUtil.str(c.get("binCode")),
                    station.isBlank() ? TmsUtil.str(c.get("binCode")) : station,
                    picked, "WMS_ASSEMBLY:" + task.get("taskNo"));
        }
        // 成品入工位
        if (!station.isBlank()) {
            addBinStock(TmsUtil.str(task.get("finishedGoodsCode")),
                    TmsUtil.str(task.get("finishedGoodsName")),
                    TmsUtil.str(task.get("warehouse")), "", station, toBd(task.get("qty")));
        }
        jdbc.update("UPDATE wms_assembly_task SET status='DONE', completed_qty=qty, finished_at=CURRENT_TIMESTAMP WHERE task_id=?",
                taskId);
        return Map.of("taskId", taskId, "status", "DONE");
    }

    // ==================== 盘点 ====================

    @Transactional
    public Map<String, Object> createStocktake(Map<String, Object> req, String operator) {
        String id = "ST" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        String no = billNo.nextNo(BillType.WMS_STOCKTAKE, "wms_stocktake_task", "task_no");
        // PRD-28 卡片9：PDA 端仓库强制取登录仓
        String pdaWhSt = warehouseResolver.pdaWarehouseNameOrNull();
        String warehouse = pdaWhSt != null ? pdaWhSt : strOr(req.get("warehouse"), "总仓");
        String countType = TmsUtil.str(req.get("countType"));
        if (countType.isBlank()) countType = "DYNAMIC";
        String countMode = params.getInt("WMS_COUNT_MODE_DEFAULT", 0, 0, 1) == 0 ? "BLIND" : "OPEN";
        if (req.get("countMode") != null && !TmsUtil.str(req.get("countMode")).isBlank()) {
            countMode = TmsUtil.str(req.get("countMode"));
        }
        boolean freeze = params.getInt("WMS_COUNT_FREEZE_BIN", 1, 0, 1) == 1
                && !"N".equalsIgnoreCase(TmsUtil.str(req.get("freezeFlag")));
        jdbc.update("""
                INSERT INTO wms_stocktake_task
                (task_id, task_no, count_type, count_mode, scope_text, warehouse, status,
                 total_bins, assignee, freeze_flag, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, CURRENT_TIMESTAMP)
                """, id, no, countType, countMode, TmsUtil.str(req.get("scopeText")), warehouse,
                operator, freeze ? "Y" : "N", TmsUtil.str(req.get("remark")));

        // 按范围快照 wms_bin_stock 到 wms_stocktake_bin
        StringBuilder scopeSql = new StringBuilder("""
                SELECT s.bin_code, s.goods_code, s.goods_name, s.batch_no, s.qty AS book_qty
                FROM wms_bin_stock s
                WHERE s.warehouse = ? AND s.qty > 0
                """);
        List<Object> args = new ArrayList<>();
        args.add(warehouse);
        @SuppressWarnings("unchecked")
        List<String> binCodes = (List<String>) req.get("binCodes");
        if (binCodes != null && !binCodes.isEmpty()) {
            scopeSql.append(" AND s.bin_code IN (").append(String.join(",", binCodes.stream().map(x -> "?").toList())).append(")");
            args.addAll(binCodes);
        }
        if ("CYCLE".equals(countType)) {
            // 循环盘：只取最近 30 天未盘过的库位
            scopeSql.append(" AND s.bin_code NOT IN (SELECT DISTINCT bin_code FROM wms_stocktake_bin st "
                    + "JOIN wms_stocktake_task h ON h.task_id = st.task_id WHERE h.created_at > TIMESTAMPADD('DAY', -30, CURRENT_TIMESTAMP))");
        }
        if ("SAMPLE".equals(countType)) {
            // 抽盘：取 20% 随机
            scopeSql.append(" AND MOD(ABS(RAWTOHEX(s.bin_stock_id)), 100) < 20");
        }
        List<Map<String, Object>> snapshot = jdbc.queryForList(scopeSql.toString(), args.toArray());
        int totalBins = 0;
        for (Map<String, Object> row : snapshot) {
            jdbc.update("""
                    INSERT INTO wms_stocktake_bin
                    (id, task_id, bin_code, goods_code, goods_name, batch_no, book_qty, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    """, "SB" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(),
                    id, TmsUtil.str(row.get("bin_code")), TmsUtil.str(row.get("goods_code")),
                    TmsUtil.str(row.get("goods_name")), emptyToNull(TmsUtil.str(row.get("batch_no"))),
                    toBd(row.get("book_qty")));
            totalBins++;
            if (freeze) {
                jdbc.update("UPDATE wms_bin SET frozen='Y' WHERE warehouse=? AND bin_code=?",
                        warehouse, TmsUtil.str(row.get("bin_code")));
            }
        }
        jdbc.update("UPDATE wms_stocktake_task SET total_bins=?, status='COUNTING' WHERE task_id=?", totalBins, id);
        TmsUtil.log(jdbc, "wms.stocktake", "CREATE", no, "建盘点单 " + countType + " 行数=" + totalBins);
        return Map.of("taskId", id, "taskNo", no, "totalBins", totalBins);
    }

    public List<Map<String, Object>> listStocktake(String status) {
        String pdaWh = warehouseResolver.pdaWarehouseNameOrNull();
        return TmsUtil.queryCamel(jdbc, """
                SELECT task_id, task_no, count_type, count_mode, scope_text, warehouse, status,
                       total_bins, counted_bins, diff_count, assignee, freeze_flag, remark, created_at, finished_at
                FROM wms_stocktake_task
                WHERE (? IS NULL OR status = ?) AND (? IS NULL OR warehouse = ?)
                ORDER BY created_at DESC
                """, status, status, pdaWh, pdaWh);
    }

    public List<Map<String, Object>> stocktakeBins(String taskId) {
        assertStocktakeWarehouse(taskId);
        return TmsUtil.queryCamel(jdbc, """
                SELECT id, task_id, bin_code, goods_code, goods_name, batch_no, book_qty, real_qty,
                       diff_qty, recounted, counter, counted_at, status
                FROM wms_stocktake_bin WHERE task_id = ?
                ORDER BY bin_code, goods_code
                """, taskId);
    }

    /** 盘点单仓库断言（PDA 隔离，PDA-007）。 */
    private void assertStocktakeWarehouse(String taskId) {
        warehouseResolver.assertIfPda(stocktakeWarehouseOf(taskId));
    }

    private String stocktakeWarehouseOf(String taskId) {
        List<String> wh = jdbc.queryForList(
                "SELECT warehouse FROM wms_stocktake_task WHERE task_id = ?", String.class, taskId);
        if (wh.isEmpty()) throw new IllegalArgumentException("盘点单不存在");
        return wh.get(0);
    }

    /** PDA 提交一行盘点结果。暗盘不回写 diff 给前端看。 */
    @Transactional
    public Map<String, Object> countBin(String id, BigDecimal realQty, String counter) {
        List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                "SELECT b.*, t.count_mode FROM wms_stocktake_bin b JOIN wms_stocktake_task t ON t.task_id=b.task_id WHERE b.id=?",
                id);
        if (r.isEmpty()) throw new IllegalArgumentException("盘点行不存在");
        Map<String, Object> row = r.get(0);
        warehouseResolver.assertIfPda(stocktakeWarehouseOf(TmsUtil.str(row.get("taskId"))));
        BigDecimal book = toBd(row.get("bookQty"));
        BigDecimal diff = realQty.subtract(book);
        String lineStatus = diff.signum() == 0 ? "COUNTED" : "DIFF";
        jdbc.update("""
                UPDATE wms_stocktake_bin
                SET real_qty=?, diff_qty=?, counter=?, counted_at=CURRENT_TIMESTAMP, status=?
                WHERE id=?
                """, realQty, diff, counter, lineStatus, id);
        // 回写头进度
        jdbc.update("""
                UPDATE wms_stocktake_task
                SET counted_bins = (SELECT COUNT(*) FROM wms_stocktake_bin WHERE task_id = ? AND real_qty IS NOT NULL),
                    diff_count = (SELECT COUNT(*) FROM wms_stocktake_bin WHERE task_id = ? AND diff_qty <> 0)
                WHERE task_id = ?
                """, row.get("taskId"), row.get("taskId"), row.get("taskId"));
        return Map.of("id", id, "diff", "BLIND".equals(row.get("countMode")) ? null : diff, "status", lineStatus);
    }

    /** 复盘一行。 */
    @Transactional
    public Map<String, Object> recountBin(String id, BigDecimal realQty, String counter) {
        List<String> taskIds = jdbc.queryForList(
                "SELECT task_id FROM wms_stocktake_bin WHERE id = ?", String.class, id);
        if (taskIds.isEmpty()) throw new IllegalArgumentException("盘点行不存在");
        warehouseResolver.assertIfPda(stocktakeWarehouseOf(taskIds.get(0)));
        jdbc.update("""
                UPDATE wms_stocktake_bin SET real_qty=?, diff_qty=? - book_qty, counter=?, recounted='Y',
                    counted_at=CURRENT_TIMESTAMP,
                    status=CASE WHEN ? = book_qty THEN 'COUNTED' ELSE 'DIFF' END
                WHERE id=?
                """, realQty, realQty, counter, realQty, id);
        return Map.of("id", id);
    }

    /**
     * PDA 盘点提交（wms_pda.stocktake.submit，KEEPER/LEADER）：
     * 所有盘点行必须已录入实盘数，单据转为 PENDING_APPROVAL 待主管审核，不产生调整单。
     */
    @Transactional
    public Map<String, Object> submitStocktake(String taskId, String operator) {
        List<Map<String, Object>> t = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_stocktake_task WHERE task_id = ?", taskId);
        if (t.isEmpty()) throw new IllegalArgumentException("盘点单不存在");
        Map<String, Object> task = t.get(0);
        warehouseResolver.assertIfPda(TmsUtil.str(task.get("warehouse")));
        String status = TmsUtil.str(task.get("status"));
        if ("APPROVED".equals(status)) throw new IllegalArgumentException("盘点单已审核完成");
        if ("PENDING_APPROVAL".equals(status)) return Map.of("taskId", taskId, "already", true);
        Integer uncounted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_stocktake_bin WHERE task_id = ? AND real_qty IS NULL",
                Integer.class, taskId);
        if (uncounted != null && uncounted > 0) {
            throw new IllegalArgumentException("还有 " + uncounted + " 行未录入实盘数，不能提交");
        }
        jdbc.update("UPDATE wms_stocktake_task SET status='PENDING_APPROVAL' WHERE task_id=?", taskId);
        TmsUtil.log(jdbc, "wms.stocktake", "SUBMIT", TmsUtil.str(task.get("taskNo")),
                "盘点提交待审，操作人 " + operator);
        return Map.of("taskId", taskId, "status", "PENDING_APPROVAL");
    }

    /**
     * 盘点完成审批（wms_pda.stocktake.audit，仅 LEADER）：
     * 有差异的行自动生成 wms_adjust_record 待审批；解冻库位；盘点单关闭。
     * PDA 端只允许审核已提交（PENDING_APPROVAL）的盘点单；PC 端保持原口径（盘点中亦可直接审核）。
     */
    @Transactional
    public Map<String, Object> finishStocktake(String taskId, String operator) {
        List<Map<String, Object>> t = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_stocktake_task WHERE task_id = ?", taskId);
        if (t.isEmpty()) throw new IllegalArgumentException("盘点单不存在");
        Map<String, Object> task = t.get(0);
        warehouseResolver.assertIfPda(TmsUtil.str(task.get("warehouse")));
        if (warehouseResolver.isPda()
                && !"PENDING_APPROVAL".equals(TmsUtil.str(task.get("status")))) {
            throw new IllegalArgumentException("盘点单尚未提交，不能审核");
        }
        List<Map<String, Object>> diffs = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_stocktake_bin WHERE task_id=? AND diff_qty <> 0", taskId);
        int adjustCount = 0;
        for (Map<String, Object> d : diffs) {
            // 直接建调整单（待审批），不立即过账
            Map<String, Object> adjReq = new LinkedHashMap<>();
            adjReq.put("warehouse", task.get("warehouse"));
            adjReq.put("binCode", d.get("binCode"));
            adjReq.put("goodsCode", d.get("goodsCode"));
            adjReq.put("goodsName", d.get("goodsName"));
            adjReq.put("batchNo", d.get("batchNo"));
            adjReq.put("bookQty", d.get("bookQty"));
            adjReq.put("actualQty", d.get("realQty"));
            adjReq.put("reason", "COUNT_DIFF");
            adjReq.put("remark", "盘点单 " + task.get("taskNo"));
            createAdjust(adjReq, operator);
            adjustCount++;
        }
        // 解冻本盘点单冻结的库位
        if ("Y".equals(task.get("freezeFlag"))) {
            jdbc.update("""
                    UPDATE wms_bin SET frozen='N' WHERE warehouse=? AND bin_code IN (
                        SELECT DISTINCT bin_code FROM wms_stocktake_bin WHERE task_id=?
                    )
                    """, task.get("warehouse"), taskId);
        }
        jdbc.update("UPDATE wms_stocktake_task SET status='APPROVED', finished_at=CURRENT_TIMESTAMP WHERE task_id=?",
                taskId);
        TmsUtil.log(jdbc, "wms.stocktake", "FINISH", TmsUtil.str(task.get("taskNo")),
                "盘点完成，差异行数=" + adjustCount);
        return Map.of("taskId", taskId, "adjustCount", adjustCount);
    }

    // ==================== 效期预警 ====================

    /** 扫描所有库存批次，按效期阈值写预警快照。可定时调用或手工刷新。 */
    @Transactional
    public int refreshExpiryAlerts(String warehouse) {
        int warningDays = params.getInt("WMS_EXPIRY_WARNING_DAYS", 30, 0, 3650);
        int criticalDays = params.getInt("WMS_EXPIRY_CRITICAL_DAYS", 7, 0, 3650);
        jdbc.update("DELETE FROM wms_expiry_alert WHERE warehouse = ? AND handle_status = 'PENDING'", warehouse);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.goods_code, s.goods_name, s.batch_no, s.bin_code, s.qty, b.production_date, b.expiry_date
                FROM wms_bin_stock s
                LEFT JOIN inv_batch_stock b ON b.goods_code = s.goods_code
                    AND b.warehouse = s.warehouse AND COALESCE(b.batch_no,'') = COALESCE(s.batch_no,'')
                WHERE s.warehouse = ? AND s.qty > 0
                """, warehouse);
        int inserted = 0;
        LocalDate today = LocalDate.now();
        for (Map<String, Object> r : rows) {
            Object expiryObj = r.get("expiry_date");
            if (!(expiryObj instanceof LocalDate expiry)) continue;
            int days = (int) (expiry.toEpochDay() - today.toEpochDay());
            String level;
            if (days < 0) level = "EXPIRED";
            else if (days <= criticalDays) level = "CRITICAL";
            else if (days <= warningDays) level = "WARNING";
            else continue;
            jdbc.update("""
                    INSERT INTO wms_expiry_alert
                    (alert_id, goods_code, goods_name, batch_no, warehouse, bin_code, qty,
                     production_date, expiry_date, days_to_expiry, alert_level, handle_status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                    """, "EA" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(),
                    TmsUtil.str(r.get("goods_code")), TmsUtil.str(r.get("goods_name")),
                    emptyToNull(TmsUtil.str(r.get("batch_no"))), warehouse,
                    TmsUtil.str(r.get("bin_code")), toBd(r.get("qty")),
                    r.get("production_date"), expiry, days, level);
            inserted++;
        }
        return inserted;
    }

    public List<Map<String, Object>> listExpiry(String level, String handleStatus) {
        return TmsUtil.queryCamel(jdbc, """
                SELECT alert_id, goods_code, goods_name, batch_no, warehouse, bin_code, qty,
                       production_date, expiry_date, days_to_expiry, alert_level, handle_status,
                       handle_remark, created_at, updated_at
                FROM wms_expiry_alert
                WHERE (? IS NULL OR alert_level = ?) AND (? IS NULL OR handle_status = ?)
                ORDER BY days_to_expiry ASC
                """, level, level, handleStatus, handleStatus);
    }

    @Transactional
    public Map<String, Object> handleExpiry(String alertId, String handleStatus, String remark) {
        jdbc.update("""
                UPDATE wms_expiry_alert SET handle_status=?, handle_remark=?, updated_at=CURRENT_TIMESTAMP
                WHERE alert_id=?
                """, handleStatus, remark, alertId);
        return Map.of("alertId", alertId, "handleStatus", handleStatus);
    }

    // ==================== 看板指标 ====================

    public Map<String, Object> dashboard(String warehouse) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pendingReceive", jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_inbound_task WHERE warehouse=? AND status IN ('PENDING','RECEIVING')",
                Integer.class, warehouse));
        data.put("receivedToday", jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_inbound_task WHERE warehouse=? AND received_at >= CURRENT_DATE",
                Integer.class, warehouse));
        data.put("pendingPutaway", jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_putaway_task WHERE warehouse=? AND status='PENDING'",
                Integer.class, warehouse));
        data.put("activeWaves", jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_wave WHERE warehouse=? AND status IN ('RELEASED','PICKING','CHECKING')",
                Integer.class, warehouse));
        data.put("pendingCheck", jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_wave WHERE warehouse=? AND status='CHECKING'",
                Integer.class, warehouse));
        data.put("shippedToday", jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_wave WHERE warehouse=? AND status='SHIPPED' AND updated_at >= CURRENT_DATE",
                Integer.class, warehouse));
        data.put("openExceptions", jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_exception WHERE status NOT IN ('RESOLVED','CANCELLED')",
                Integer.class));
        data.put("openFreezes", jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_freeze_record WHERE status='FROZEN'", Integer.class));
        data.put("expiryCritical", jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_expiry_alert WHERE alert_level IN ('CRITICAL','EXPIRED') AND handle_status='PENDING'",
                Integer.class));
        return data;
    }

    /** 按人按日汇总作业绩效（实时计算，写入 wms_performance_daily）。 */
    @Transactional
    public Map<String, Object> refreshPerformance(LocalDate date) {
        jdbc.update("DELETE FROM wms_performance_daily WHERE stat_date = ?", date);
        // 收货
        jdbc.update("""
                INSERT INTO wms_performance_daily (id, stat_date, operator, role_code, receive_qty, receive_lines, created_at)
                SELECT 'PF' || UUID(), ?, receiver, 'RECEIVER',
                       COALESCE(SUM(received_qty),0), COUNT(*), CURRENT_TIMESTAMP
                FROM wms_inbound_task WHERE receiver IS NOT NULL AND received_at >= ? AND received_at < ? + 1
                GROUP BY receiver
                """, date, date, date);
        // 上架
        jdbc.update("""
                INSERT INTO wms_performance_daily (id, stat_date, operator, role_code, putaway_qty, putaway_lines, created_at)
                SELECT 'PF' || UUID(), ?, assignee, 'PUTAWAY',
                       COALESCE(SUM(qty),0), COUNT(*), CURRENT_TIMESTAMP
                FROM wms_putaway_task WHERE assignee IS NOT NULL AND finished_at >= ? AND finished_at < ? + 1
                GROUP BY assignee
                """, date, date, date);
        return Map.of("date", date.toString());
    }

    public List<Map<String, Object>> listPerformance(LocalDate from, LocalDate to) {
        return listPerformance(from, to, null, null);
    }

    /**
     * 绩效查询（PRD-28 卡片9）。
     * <ul>
     *   <li>PDA self：强制只看本人（{@code wms_pda.performance.view_self}，PDA-006）；</li>
     *   <li>PDA team（LEADER）：看当前仓绑定用户（wms_performance_daily 无仓库列，
     *       以「绑定当前仓的启用用户」为口径过滤 operator）。</li>
     * </ul>
     *
     * @param operatorSelf PDA 本人查询时传当前用户名；PC/主管口径传 null
     * @param warehouseId  PDA 主管口径传当前登录仓 id；其他场景传 null
     */
    public List<Map<String, Object>> listPerformance(LocalDate from, LocalDate to,
                                                     String operatorSelf, String warehouseId) {
        StringBuilder sql = new StringBuilder("""
                SELECT stat_date, operator, role_code, receive_qty, receive_lines, putaway_qty, putaway_lines,
                       pick_qty, pick_lines, check_orders, replenish_count, error_count, work_minutes
                FROM wms_performance_daily
                WHERE stat_date BETWEEN ? AND ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(from); args.add(to);
        if (operatorSelf != null && !operatorSelf.isBlank()) {
            sql.append(" AND operator = ?");
            args.add(operatorSelf);
        } else if (warehouseId != null && !warehouseId.isBlank()) {
            sql.append(" AND operator IN (")
               .append("SELECT u.username FROM sys_user_runtime u ")
               .append("JOIN sys_user_warehouse uw ON uw.user_id = u.user_id ")
               .append("WHERE uw.warehouse_id = ? AND COALESCE(u.status,'NORMAL')='NORMAL')");
            args.add(warehouseId);
        }
        sql.append(" ORDER BY stat_date DESC, operator");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    // ==================== 库存查询 ====================

    /**
     * 实物库存查询（wms_bin_stock 与 inv_stock_balance 对账视图）。
     * PRD-28 卡片9：PDA 端仓库强制取登录仓；批次列受 {@code wms_pda.stock_query.view_batch} 控制；
     * 成本单价/金额受 {@code wms_pda.stock_query.view_cost} 功能点 + VIEW_COST/VIEW_COST_AMOUNT
     * 字段授权双控（PDA 默认五角色均无成本权限，仅 LEADER 授予）。
     */
    public List<Map<String, Object>> binStockQuery(String warehouse, String keyword, boolean discrepancyOnly) {
        String pdaWh = warehouseResolver.pdaWarehouseNameOrNull();
        if (pdaWh != null) warehouse = pdaWh;
        final String wh = warehouse;
        boolean canBatch = !warehouseResolver.isPda() || permissionService.hasFunc(FUNC_STOCK_VIEW_BATCH);
        boolean canCost = !warehouseResolver.isPda()
                || (permissionService.hasFunc(FUNC_STOCK_VIEW_COST) && permissionService.hasField("VIEW_COST"));
        boolean canCostAmount = canCost && (!warehouseResolver.isPda()
                || permissionService.hasField("VIEW_COST_AMOUNT"));
        StringBuilder sql = new StringBuilder("""
                SELECT s.goods_code, s.goods_name, s.warehouse, s.bin_code, s.batch_no,
                       s.qty AS bin_qty, s.locked_qty,
                       COALESCE((SELECT SUM(qty) FROM wms_bin_stock x
                                 WHERE x.goods_code=s.goods_code AND x.warehouse=s.warehouse
                                 AND COALESCE(x.batch_no,'')=COALESCE(s.batch_no,'')),0) AS physical_qty
                FROM wms_bin_stock s
                WHERE s.warehouse = ? AND s.qty > 0
                """);
        List<Object> args = new ArrayList<>();
        args.add(wh);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(s.goods_code) LIKE ? OR LOWER(s.goods_name) LIKE ?)");
            String k = "%" + keyword.toLowerCase() + "%";
            args.add(k); args.add(k);
        }
        sql.append(" ORDER BY s.bin_code, s.goods_code");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
        if (discrepancyOnly) {
            rows.removeIf(r -> {
                BigDecimal pq = toBd(r.get("physicalQty"));
                BigDecimal avail = inventoryCost.getAvailableQty(TmsUtil.str(r.get("goodsCode")), wh);
                return avail != null && avail.compareTo(pq) == 0;
            });
        }
        for (Map<String, Object> r : rows) {
            if (!canBatch) r.put("batchNo", null);
            if (canCost) {
                BigDecimal cp = null;
                try {
                    cp = inventoryCost.getCurrentCostPrice(TmsUtil.str(r.get("goodsCode")), wh);
                } catch (Exception ignore) { }
                r.put("unitCost", cp);
                if (canCostAmount && cp != null) {
                    r.put("costAmount", cp.multiply(toBd(r.get("binQty")))
                            .setScale(2, java.math.RoundingMode.HALF_UP));
                }
            }
        }
        return rows;
    }

    // ==================== 异常中心（PRD-28 卡片9） ====================

    /**
     * 异常单列表。仓库隔离：出库异常经 wms_wave 定位仓库，入库异常（source_type=INBOUND）
     * 经 source_bill=入库任务号关联 wms_inbound_task 定位仓库；PDA 强制当前仓。
     */
    public List<Map<String, Object>> listExceptions(String status, String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.exception_id, e.exception_no, e.wave_id, e.wave_no, e.source_order_no,
                       e.source_type, e.source_bill, e.goods_code, e.exception_type, e.qty,
                       e.status, e.priority, e.description, e.resolution, e.reporter, e.assignee,
                       e.handler, e.bin_code, e.deadline, e.created_at, e.resolved_at,
                       COALESCE(w.warehouse, it.warehouse) AS warehouse
                FROM wms_exception e
                LEFT JOIN wms_wave w ON w.wave_id = e.wave_id
                LEFT JOIN wms_inbound_task it ON it.task_no = e.source_bill
                	AND COALESCE(e.source_type,'OUTBOUND') = 'INBOUND'
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String pdaWh = warehouseResolver.pdaWarehouseNameOrNull();
        if (pdaWh != null) { sql.append(" AND COALESCE(w.warehouse, it.warehouse) = ?"); args.add(pdaWh); }
        if (status != null && !status.isBlank()) { sql.append(" AND e.status = ?"); args.add(status); }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(e.exception_no) LIKE ? OR LOWER(e.source_order_no) LIKE ?"
                    + " OR LOWER(e.goods_code) LIKE ? OR LOWER(e.description) LIKE ?)");
            String k = "%" + keyword.toLowerCase() + "%";
            args.add(k); args.add(k); args.add(k); args.add(k);
        }
        sql.append(" ORDER BY e.created_at DESC");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    /**
     * PDA 上报异常（wms_pda.exception.report，全员）。
     * 入参：exceptionType(SHORT/DIFF/DAMAGE/OTHER)、description（必填）、qty、goodsCode、
     * sourceType(OUTBOUND/INBOUND)、waveId/sourceOrderNo 或 sourceBill(入库任务号)、binCode、priority。
     */
    @Transactional
    public Map<String, Object> reportException(Map<String, Object> req, String operator) {
        String type = strOr(req.get("exceptionType"), "OTHER").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("SHORT", "DIFF", "DAMAGE", "OTHER").contains(type)) {
            throw new IllegalArgumentException("异常类型不合法：" + type);
        }
        String desc = TmsUtil.str(req.get("description"));
        if (desc.isBlank()) throw new IllegalArgumentException("请填写异常描述");
        String sourceType = "INBOUND".equalsIgnoreCase(TmsUtil.str(req.get("sourceType")))
                ? "INBOUND" : "OUTBOUND";
        String waveId = TmsUtil.str(req.get("waveId"));
        String sourceBill = TmsUtil.str(req.get("sourceBill"));
        // 仓库隔离断言：出库按波次，入库按入库任务号
        if ("INBOUND".equals(sourceType)) {
            if (sourceBill.isBlank()) throw new IllegalArgumentException("入库异常必须关联入库任务号");
            List<String> wh = jdbc.queryForList(
                    "SELECT warehouse FROM wms_inbound_task WHERE task_no = ? OR task_id = ?",
                    String.class, sourceBill, sourceBill);
            if (wh.isEmpty()) throw new IllegalArgumentException("入库任务不存在：" + sourceBill);
            warehouseResolver.assertIfPda(wh.get(0));
        } else if (!waveId.isBlank()) {
            List<String> wh = jdbc.queryForList(
                    "SELECT warehouse FROM wms_wave WHERE wave_id = ?", String.class, waveId);
            if (wh.isEmpty()) throw new IllegalArgumentException("波次不存在：" + waveId);
            warehouseResolver.assertIfPda(wh.get(0));
        }
        String exId = "EX" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        String exNo = billNo.nextNo(BillType.WMS_EXCEPTION, "wms_exception", "exception_no");
        String waveNo = "";
        if (!waveId.isBlank()) {
            List<String> nos = jdbc.queryForList(
                    "SELECT wave_no FROM wms_wave WHERE wave_id = ?", String.class, waveId);
            if (!nos.isEmpty()) waveNo = nos.get(0);
        }
        String priority = strOr(req.get("priority"), "MEDIUM").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("URGENT", "HIGH", "MEDIUM", "LOW").contains(priority)) priority = "MEDIUM";
        jdbc.update("""
                INSERT INTO wms_exception (exception_id, exception_no, wave_id, wave_no, source_order_no,
                    source_type, source_bill, goods_code, exception_type, qty, status, description,
                    reporter, priority, bin_code, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, exId, exNo, emptyToNull(waveId), emptyToNull(waveNo),
                emptyToNull(TmsUtil.str(req.get("sourceOrderNo"))), sourceType, emptyToNull(sourceBill),
                emptyToNull(TmsUtil.str(req.get("goodsCode"))), type, toBd(req.get("qty")),
                desc, operator, priority, emptyToNull(TmsUtil.str(req.get("binCode"))));
        TmsUtil.log(jdbc, "wms.exception", "REPORT", exNo, operator + " 上报异常 " + type + "：" + desc);
        return Map.of("exceptionId", exId, "exceptionNo", exNo);
    }

    /** 异常处理（wms_pda.exception.handle，仅 LEADER）：填处理结果，状态 RESOLVED。 */
    @Transactional
    public Map<String, Object> handleException(String exceptionId, String resolution, String handler) {
        Map<String, Object> e = mustGetException(exceptionId);
        if (resolution == null || resolution.isBlank()) throw new IllegalArgumentException("请填写处理结果");
        jdbc.update("""
                UPDATE wms_exception SET status='RESOLVED', resolution=?, handler=?,
                    resolved_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                WHERE exception_id=?
                """, resolution.trim(), handler, exceptionId);
        TmsUtil.log(jdbc, "wms.exception", "HANDLE", TmsUtil.str(e.get("exceptionNo")),
                handler + " 处理异常：" + resolution.trim());
        return Map.of("exceptionId", exceptionId, "status", "RESOLVED");
    }

    /** 异常分派（wms_pda.exception.assign，仅 LEADER）：指派人必须启用且绑定当前仓。 */
    @Transactional
    public Map<String, Object> assignException(String exceptionId, String assignee, String operator) {
        if (assignee == null || assignee.isBlank()) throw new IllegalArgumentException("请指定处理人");
        mustGetException(exceptionId);
        // 复用出库服务的同仓用户校验（PDA 场景）
        if (warehouseResolver.isPda()) {
            String whId = warehouseResolver.currentWarehouseId();
            Integer n = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_user_runtime u
                    JOIN sys_user_warehouse uw ON uw.user_id = u.user_id
                    WHERE u.username = ? AND COALESCE(u.status,'NORMAL')='NORMAL' AND uw.warehouse_id = ?
                    """, Integer.class, assignee.trim(), whId);
            if (n == null || n == 0) {
                throw new IllegalArgumentException("用户「" + assignee + "」不存在、已停用或未绑定当前作业仓库");
            }
        }
        jdbc.update("""
                UPDATE wms_exception SET assignee=?, status=CASE WHEN status='OPEN' THEN 'HANDLING' ELSE status END,
                    updated_at=CURRENT_TIMESTAMP WHERE exception_id=?
                """, assignee.trim(), exceptionId);
        TmsUtil.log(jdbc, "wms.exception", "ASSIGN", exceptionId,
                operator + " 分派异常给 " + assignee.trim());
        return Map.of("exceptionId", exceptionId, "assignee", assignee.trim());
    }

    private Map<String, Object> mustGetException(String exceptionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.*, COALESCE(w.warehouse, it.warehouse) AS wh_name
                FROM wms_exception e
                LEFT JOIN wms_wave w ON w.wave_id = e.wave_id
                LEFT JOIN wms_inbound_task it ON it.task_no = e.source_bill
                	AND COALESCE(e.source_type,'OUTBOUND') = 'INBOUND'
                WHERE e.exception_id = ? OR e.exception_no = ?
                """, exceptionId, exceptionId);
        if (rows.isEmpty()) throw new IllegalArgumentException("异常单不存在：" + exceptionId);
        Map<String, Object> e = rows.get(0);
        Object wh = e.get("wh_name");
        if (wh == null) wh = e.get("WH_NAME");
        warehouseResolver.assertIfPda(wh == null ? null : String.valueOf(wh));
        return TmsUtil.camelize(e);
    }

    // ==================== 公共工具 ====================

    /** 把商品从一个库位移到另一个库位（wms_bin_stock 内移动，不涉及财务库存）。 */
    private void moveBinStock(String goodsCode, String warehouse, String batchNo,
                              String fromBin, String toBin, BigDecimal qty, String sourceBill) {
        if (fromBin.equals(toBin)) return;
        // 扣源位
        int n = jdbc.update("""
                UPDATE wms_bin_stock SET qty = GREATEST(qty - ?, 0),
                    locked_qty = GREATEST(COALESCE(locked_qty,0) - ?, 0),
                    updated_at = CURRENT_TIMESTAMP
                WHERE goods_code=? AND warehouse=? AND bin_code=? AND COALESCE(batch_no,'')=?
                """, qty, qty, goodsCode, warehouse, fromBin, batchNo == null ? "" : batchNo);
        if (n == 0) throw new IllegalStateException("源库位无库存：" + fromBin + " / " + goodsCode);
        addBinStock(goodsCode, "", warehouse, batchNo, toBin, qty);
        try {
            jdbc.update("""
                    INSERT INTO wms_bin_stock_log
                    (log_id, warehouse, goods_code, batch_no, from_bin, to_bin, direction, qty, source_bill, operator)
                    VALUES (?, ?, ?, ?, ?, ?, 'MOVE', ?, ?, ?)
                    """, "BL" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(),
                    warehouse, goodsCode, emptyToNull(batchNo), fromBin, toBin, qty, sourceBill,
                    TmsUtil.currentUser());
        } catch (Exception ignore) {}
        // 库位 used_qty 同步
        jdbc.update("UPDATE wms_bin SET used_qty = GREATEST(COALESCE(used_qty,0) - ?, 0) WHERE warehouse=? AND bin_code=?",
                qty, warehouse, fromBin);
        jdbc.update("UPDATE wms_bin SET used_qty = COALESCE(used_qty,0) + ? WHERE warehouse=? AND bin_code=?",
                qty, warehouse, toBin);
    }

    private void addBinStock(String goodsCode, String goodsName, String warehouse,
                             String batchNo, String bin, BigDecimal qty) {
        int n = jdbc.update("""
                UPDATE wms_bin_stock SET qty = qty + ?, updated_at = CURRENT_TIMESTAMP
                WHERE goods_code=? AND warehouse=? AND bin_code=? AND COALESCE(batch_no,'')=?
                  AND COALESCE(container_code,'')=''
                """, qty, goodsCode, warehouse, bin, batchNo == null ? "" : batchNo);
        if (n > 0) return;
        jdbc.update("""
                INSERT INTO wms_bin_stock
                (bin_stock_id, goods_code, goods_name, warehouse, batch_no, bin_code, container_code,
                 qty, locked_qty, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, '', ?, 0, CURRENT_TIMESTAMP)
                """, "BS" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(),
                goodsCode, goodsName, warehouse, emptyToNull(batchNo), bin, qty);
        jdbc.update("UPDATE wms_bin SET used_qty = COALESCE(used_qty,0) + ? WHERE warehouse=? AND bin_code=?",
                qty, warehouse, bin);
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(o).trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static String strOr(Object o, String dft) {
        if (o == null) return dft;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? dft : s;
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
