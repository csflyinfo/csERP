package com.erp.wms;

import com.erp.common.security.PermissionService;
import com.erp.common.util.BillNoGenerator;
import com.erp.common.util.BillNoGenerator.BillType;
import com.erp.inventory.service.InventoryCostService;
import com.erp.purchase.PurchaseController;
import com.erp.sales.SalesReturnController;
import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
 * WMS V1.5 入库作业编排（PRD-28）。
 *
 * <p>覆盖采购到货 / 销售退货 / 拒收 / 调拨 / 其他入库，统一走「到货预约 → 收货 → 复检 → 上架 →
 * 过账入库单」五段式流程。
 *
 * <p><b>账实分离</b>：本服务只维护收货明细、物理库位（wms_bin_stock 上架增加数量）与作业状态；
 * <b>财务库存与成本由 {@link PurchaseController#createAndAuditForWms} →
 * {@link InventoryCostService#purchaseInbound} 统一处理</b>。WMS 不直接改 inv_stock_balance。
 *
 * <p>业务参数从 sys_param_runtime 取，枚举值在 V81 后全部数字化：
 * WMS_BLINDED_RECEIVE / WMS_OVER_RECEIVE_TOLERANCE / WMS_RECHECK_ENABLED / WMS_RECHECK_MODE 等。
 */
@Service
public class WmsInboundService {

    /** 超收授权功能点（PRD-28 §7.2.1：仅 KEEPER/LEADER）。 */
    public static final String FUNC_OVER_RECEIVE = "wms_pda.receive.over_receive";

    private final JdbcTemplate jdbc;
    private final BillNoGenerator billNo;
    private final SysParamService params;
    private final PurchaseController purchaseController;
    private final InventoryCostService inventoryCost;
    private final SalesReturnController salesReturnController;
    private final WmsWarehouseResolver warehouseResolver;
    private final PermissionService permissionService;

    public WmsInboundService(JdbcTemplate jdbc, BillNoGenerator billNo, SysParamService params,
                             PurchaseController purchaseController, InventoryCostService inventoryCost,
                             @Autowired(required = false) @Lazy SalesReturnController salesReturnController,
                             WmsWarehouseResolver warehouseResolver, PermissionService permissionService) {
        this.jdbc = jdbc;
        this.billNo = billNo;
        this.params = params;
        this.purchaseController = purchaseController;
        this.inventoryCost = inventoryCost;
        this.salesReturnController = salesReturnController;
        this.warehouseResolver = warehouseResolver;
        this.permissionService = permissionService;
    }

    // ==================== 入库任务列表 / 明细 ====================

    public List<Map<String, Object>> list(String status, String inboundType, String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.task_id, t.task_no, t.inbound_type, t.source_order_no, t.supplier_code, t.supplier_name,
                       t.customer_code, t.customer_name, t.warehouse, t.asn_no, t.appoint_time, t.door_code,
                       t.status, t.total_qty, t.received_qty, t.putaway_qty, t.total_weight, t.line_count,
                       t.receiver, t.rechecker, t.blind_flag, t.over_tolerance, t.remark, t.created_at, t.received_at,
                       (SELECT COUNT(*) FROM wms_inbound_task_detail d WHERE d.task_id = t.task_id) AS sku_total,
                       (SELECT COUNT(*) FROM wms_inbound_task_detail d
                          WHERE d.task_id = t.task_id AND COALESCE(d.received_qty,0) > 0) AS sku_received
                FROM wms_inbound_task t WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        // PRD-28 卡片9：PDA 查询强制带登录仓，杜绝跨仓看单（PDA-007）
        String pdaWh = warehouseResolver.pdaWarehouseNameOrNull();
        if (pdaWh != null) {
            sql.append(" AND t.warehouse = ?");
            args.add(pdaWh);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = ?");
            args.add(status);
        }
        if (inboundType != null && !inboundType.isBlank()) {
            sql.append(" AND t.inbound_type = ?");
            args.add(inboundType);
        }
        if (keyword != null && !keyword.isBlank()) {
            // 关键字同时匹配：任务号/来源单号/供应商/客户/商品名/商品编码/条码
            sql.append("""
                     AND (
                        LOWER(t.task_no) LIKE ? OR LOWER(t.source_order_no) LIKE ?
                        OR LOWER(COALESCE(t.supplier_name,'')) LIKE ?
                        OR LOWER(COALESCE(t.customer_name,'')) LIKE ?
                        OR EXISTS (
                            SELECT 1 FROM wms_inbound_task_detail d
                            LEFT JOIN base_goods g ON g.goods_code = d.goods_code
                            WHERE d.task_id = t.task_id AND (
                                LOWER(d.goods_name) LIKE ?
                                OR LOWER(d.goods_code) LIKE ?
                                OR LOWER(COALESCE(g.barcode,'')) LIKE ?
                            )
                        )
                     )
                    """);
            String k = "%" + keyword.toLowerCase() + "%";
            args.add(k); args.add(k); args.add(k); args.add(k);
            args.add(k); args.add(k); args.add(k);
        }
        sql.append(" ORDER BY t.created_at DESC");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    public Map<String, Object> detail(String taskId) {
        List<Map<String, Object>> h = TmsUtil.queryCamel(jdbc, """
                SELECT * FROM wms_inbound_task WHERE task_id = ?
                """, taskId);
        if (h.isEmpty()) throw new IllegalArgumentException("入库任务不存在");
        warehouseResolver.assertIfPda(TmsUtil.str(h.get(0).get("warehouse")));
        Map<String, Object> data = new LinkedHashMap<>(h.get(0));
        // 明细联查商品档案：规格、条码、存储属性、保质期天数，用于 PDA 详情与扫码收货页展示与校验
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbc, """
                SELECT d.detail_id, d.goods_code, d.goods_name, d.unit_name, d.expected_qty, d.received_qty,
                       d.putaway_qty, d.calc_weight, d.actual_weight, d.qualified_qty, d.unqualified_qty,
                       d.batch_no, d.production_date, d.expiry_date, d.container_code, d.goods_remark,
                       d.recommend_bin, d.recommend_zone_code, d.recommend_zone_name, d.recommend_storage_prop,
                       d.actual_bin, d.status, d.remark,
                       g.spec, g.barcode, COALESCE(g.storage_property,'常温') AS storage_property,
                       COALESCE(g.shelf_life_days,0) AS shelf_life_days,
                       (SELECT COUNT(*) FROM wms_putaway_task p
                          WHERE p.inbound_task_id = d.task_id AND p.status = 'DONE'
                            AND p.goods_code = d.goods_code
                            AND (p.batch_no = d.batch_no OR (p.batch_no IS NULL AND d.batch_no IS NULL))) AS putaway_done
                FROM wms_inbound_task_detail d
                LEFT JOIN base_goods g ON g.goods_code = d.goods_code
                WHERE d.task_id = ? ORDER BY d.detail_id
                """, taskId);
        data.put("details", details);

        // SKU 维度统计：已收/待收/总（按明细行，一行算一个 SKU 行）
        int totalSku = details.size();
        int receivedSku = 0;
        for (Map<String, Object> d : details) {
            if (TmsUtil.toBd(d.get("receivedQty")).signum() > 0) receivedSku++;
        }
        data.put("skuTotal", totalSku);
        data.put("skuReceived", receivedSku);
        data.put("skuPending", totalSku - receivedSku);
        return data;
    }

    /**
     * 按任务头取入库类型（PRD-28 卡片9：PDA 按入库类型分派功能点，
     * 采购收货 / 退货收货 / 其他入库三组功能码）。任务不存在抛 IllegalArgumentException。
     */
    public String inboundTypeOfTask(String taskId) {
        List<String> r = jdbc.queryForList(
                "SELECT inbound_type FROM wms_inbound_task WHERE task_id = ?", String.class, taskId);
        if (r.isEmpty()) throw new IllegalArgumentException("入库任务不存在");
        String t = r.get(0);
        return (t == null || t.isBlank()) ? "PURCHASE" : t;
    }

    /** 按明细行反查入库任务类型（PDA 扫码收货按类型裁决 receive/receive_return/other_inbound）。 */
    public String inboundTypeOfDetail(String detailId) {
        List<String> r = jdbc.queryForList(
                "SELECT t.inbound_type FROM wms_inbound_task t "
                        + "JOIN wms_inbound_task_detail d ON d.task_id = t.task_id WHERE d.detail_id = ?",
                String.class, detailId);
        if (r.isEmpty()) throw new IllegalArgumentException("入库明细不存在");
        String t = r.get(0);
        return (t == null || t.isBlank()) ? "PURCHASE" : t;
    }

    // ==================== 从来源单生成入库任务 ====================

    /**
     * 基于采购订单创建 WMS 入库任务（PENDING 待收货），明细按采购订单明细展开。
     * 其他类型（退货/拒收/调拨）由对应模块在审核后调用，或前端手工建单（OTHER）。
     */
    @Transactional
    public Map<String, Object> createFromPurchase(String purchaseOrderNo, String operator) {
        List<Map<String, Object>> po = jdbc.queryForList(
                "SELECT order_id, order_no, supplier_code, supplier_name, warehouse FROM purchase_order WHERE order_no = ?",
                purchaseOrderNo);
        if (po.isEmpty()) throw new IllegalArgumentException("采购订单不存在: " + purchaseOrderNo);
        Map<String, Object> h = po.get(0);
        String warehouse = TmsUtil.str(h.get("warehouse"));
        if (warehouse.isBlank()) warehouse = "总仓";

        // 同一采购单不重复建任务
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_inbound_task WHERE source_order_no = ? AND inbound_type = 'PURCHASE' AND status NOT IN ('CANCELLED','DONE')",
                Integer.class, purchaseOrderNo);
        if (dup != null && dup > 0) throw new IllegalStateException("该采购单已有进行中的收货任务");

        String taskId = "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String taskNo = billNo.nextNo(BillType.WMS_INBOUND, "wms_inbound_task", "task_no");
        int blindFlag = params.getInt("WMS_BLINDED_RECEIVE", 0, 0, 1);
        BigDecimal tol = BigDecimal.valueOf(params.getInt("WMS_OVER_RECEIVE_TOLERANCE", 0, 0, 100));

        jdbc.update("""
                INSERT INTO wms_inbound_task
                (task_id, task_no, inbound_type, source_order_no, source_order_id,
                 supplier_code, supplier_name, warehouse, status,
                 blind_flag, over_tolerance, receiver, remark, created_at)
                VALUES (?, ?, 'PURCHASE', ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, taskId, taskNo, purchaseOrderNo, TmsUtil.str(h.get("order_id")),
                TmsUtil.str(h.get("supplier_code")), TmsUtil.str(h.get("supplier_name")),
                warehouse, blindFlag == 1 ? "Y" : "N", tol, operator, "采购到货");

        // 明细
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT goods_code, goods_name, unit_name, qty
                FROM purchase_order_detail WHERE order_id = ?
                """, h.get("order_id"));
        BigDecimal totalQty = BigDecimal.ZERO;
        int lineCount = 0;
        for (Map<String, Object> ln : lines) {
            BigDecimal q = TmsUtil.toBd(ln.get("qty"));
            if (q.signum() <= 0) continue;
            String dId = "ID" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("""
                    INSERT INTO wms_inbound_task_detail
                    (detail_id, task_id, task_no, goods_code, goods_name, unit_name,
                     expected_qty, batch_no, production_date, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                    """, dId, taskId, taskNo, TmsUtil.str(ln.get("goods_code")),
                    TmsUtil.str(ln.get("goods_name")), TmsUtil.str(ln.get("unit_name")),
                    q, TmsUtil.str(ln.get("batch_no")), ln.get("production_date"));
            totalQty = totalQty.add(q);
            lineCount++;
        }
        jdbc.update("UPDATE wms_inbound_task SET total_qty = ?, line_count = ? WHERE task_id = ?",
                totalQty, lineCount, taskId);

        TmsUtil.log(jdbc, "wms.inbound", "CREATE", taskNo, "从采购单 " + purchaseOrderNo + " 生成入库任务");
        return Map.of("taskId", taskId, "taskNo", taskNo);
    }

    /**
     * 基于销售退货入库单（sales_return_inbound）创建 WMS 入库任务（V86）。
     * 与采购路径一致：退货单审核/推送仓库后，PDA 才看得到待收货任务。
     *
     * <p>来源单口径：{@code source_order_no = 退货入库单号（SRI 开头）}，
     * 不是退货申请号——申请单已生成 ERP 入库单，PDA 收完上架后自动审核该入库单回库。
     * 幂等：同一入库单已有进行中的任务则抛 IllegalStateException。
     */
    @Transactional
    public Map<String, Object> createFromSalesReturn(String inboundNo, String operator) {
        List<Map<String, Object>> heads = jdbc.queryForList(
                "SELECT inbound_id, inbound_no, source_apply_no, customer_code, customer_name, warehouse, remark "
                        + "FROM sales_return_inbound WHERE inbound_no = ? OR inbound_id = ?",
                inboundNo, inboundNo);
        if (heads.isEmpty()) throw new IllegalArgumentException("销售退货入库单不存在: " + inboundNo);
        Map<String, Object> h = heads.get(0);
        String warehouse = TmsUtil.str(h.get("warehouse"));
        if (warehouse.isBlank()) warehouse = "总仓";

        // 幂等：同一入库单不重复建任务
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_inbound_task WHERE source_order_no = ? AND inbound_type = 'SALES_RETURN' "
                        + "AND status NOT IN ('CANCELLED','DONE')",
                Integer.class, inboundNo);
        if (dup != null && dup > 0) throw new IllegalStateException("该退货入库单已有进行中的收货任务");

        String taskId = "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String taskNo = billNo.nextNo(BillType.WMS_INBOUND, "wms_inbound_task", "task_no");
        int blindFlag = params.getInt("WMS_BLINDED_RECEIVE", 0, 0, 1);
        BigDecimal tol = BigDecimal.valueOf(params.getInt("WMS_OVER_RECEIVE_TOLERANCE", 0, 0, 100));

        jdbc.update("""
                INSERT INTO wms_inbound_task
                (task_id, task_no, inbound_type, source_order_no, source_order_id,
                 customer_code, customer_name, warehouse, status,
                 blind_flag, over_tolerance, receiver, remark, created_at)
                VALUES (?, ?, 'SALES_RETURN', ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, taskId, taskNo, inboundNo, TmsUtil.str(h.get("inbound_id")),
                TmsUtil.str(h.get("customer_code")), TmsUtil.str(h.get("customer_name")),
                warehouse, blindFlag == 1 ? "Y" : "N", tol, operator,
                "销售退货 " + TmsUtil.str(h.get("source_apply_no")));

        // 明细：从 sales_return_inbound_detail 展开，带 source_detail_id 以便收完回写
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT detail_id, goods_code, goods_name, unit_name, qty, batch_no, production_date
                FROM sales_return_inbound_detail WHERE inbound_id = ?
                """, h.get("inbound_id"));
        BigDecimal totalQty = BigDecimal.ZERO;
        int lineCount = 0;
        for (Map<String, Object> ln : lines) {
            BigDecimal q = TmsUtil.toBd(ln.get("qty"));
            if (q.signum() <= 0) continue;
            String dId = "ID" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            // 把 sales_return_inbound_detail.detail_id 记到 wms 明细的 remark，postInbound 时用来回写批次
            String sourceDetailId = TmsUtil.str(ln.get("detail_id"));
            jdbc.update("""
                    INSERT INTO wms_inbound_task_detail
                    (detail_id, task_id, task_no, goods_code, goods_name, unit_name,
                     expected_qty, batch_no, production_date, status, remark, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP)
                    """, dId, taskId, taskNo, TmsUtil.str(ln.get("goods_code")),
                    TmsUtil.str(ln.get("goods_name")), TmsUtil.str(ln.get("unit_name")),
                    q, TmsUtil.str(ln.get("batch_no")), ln.get("production_date"),
                    sourceDetailId.isBlank() ? null : "SRI_DID=" + sourceDetailId);
            totalQty = totalQty.add(q);
            lineCount++;
        }
        jdbc.update("UPDATE wms_inbound_task SET total_qty = ?, line_count = ? WHERE task_id = ?",
                totalQty, lineCount, taskId);

        TmsUtil.log(jdbc, "wms.inbound", "CREATE", taskNo,
                "从销售退货入库单 " + inboundNo + " 生成收货任务（申请 " + TmsUtil.str(h.get("source_apply_no")) + "）");
        return Map.of("taskId", taskId, "taskNo", taskNo);
    }

    /** 手工建其他入库任务（无来源单，可由 PC 直接建单走收货流程）。 */
    @Transactional
    public Map<String, Object> createManual(Map<String, Object> req, String operator) {
        String inboundType = strOr(req.get("inboundType"), "OTHER");
        // PDA 端仓库以登录仓为准，不信请求体（PDA-007）
        String pdaWh = warehouseResolver.pdaWarehouseNameOrNull();
        String warehouse = pdaWh != null ? pdaWh : strOr(req.get("warehouse"), "总仓");
        String taskId = "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String taskNo = billNo.nextNo(BillType.WMS_INBOUND, "wms_inbound_task", "task_no");

        jdbc.update("""
                INSERT INTO wms_inbound_task
                (task_id, task_no, inbound_type, source_order_no, supplier_code, supplier_name,
                 customer_code, customer_name, warehouse, asn_no, appoint_time, door_code,
                 status, blind_flag, over_tolerance, receiver, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, taskId, taskNo, inboundType, TmsUtil.str(req.get("sourceOrderNo")),
                TmsUtil.str(req.get("supplierCode")), TmsUtil.str(req.get("supplierName")),
                TmsUtil.str(req.get("customerCode")), TmsUtil.str(req.get("customerName")),
                warehouse, TmsUtil.str(req.get("asnNo")), req.get("appointTime"),
                TmsUtil.str(req.get("doorCode")),
                params.getInt("WMS_BLINDED_RECEIVE", 0, 0, 1) == 1 ? "Y" : "N",
                BigDecimal.valueOf(params.getInt("WMS_OVER_RECEIVE_TOLERANCE", 0, 0, 100)),
                operator, TmsUtil.str(req.get("remark")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) req.getOrDefault("details", List.of());
        BigDecimal totalQty = BigDecimal.ZERO;
        int lineCount = 0;
        for (Map<String, Object> d : details) {
            BigDecimal q = TmsUtil.toBd(d.get("expectedQty"));
            if (q.signum() <= 0) continue;
            String dId = "ID" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("""
                    INSERT INTO wms_inbound_task_detail
                    (detail_id, task_id, task_no, goods_code, goods_name, unit_name,
                     expected_qty, batch_no, production_date, expiry_date, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                    """, dId, taskId, taskNo, TmsUtil.str(d.get("goodsCode")),
                    TmsUtil.str(d.get("goodsName")), TmsUtil.str(d.get("unitName")), q,
                    TmsUtil.str(d.get("batchNo")), d.get("productionDate"), d.get("expiryDate"));
            totalQty = totalQty.add(q);
            lineCount++;
        }
        jdbc.update("UPDATE wms_inbound_task SET total_qty = ?, line_count = ? WHERE task_id = ?",
                totalQty, lineCount, taskId);
        TmsUtil.log(jdbc, "wms.inbound", "CREATE_MANUAL", taskNo, "手工建入库任务 " + inboundType);
        return Map.of("taskId", taskId, "taskNo", taskNo);
    }

    // ==================== 收货（PDA / PC 逐行登记） ====================

    /**
     * 登记一行收货。PDA 扫商品码后调用，可分多次累计收货；不允许超过应收 + 超收容差。
     */
    @Transactional
    public Map<String, Object> receive(String detailId, BigDecimal qty, String batchNo,
                                       LocalDate productionDate, LocalDate expiryDate,
                                       BigDecimal actualWeight, String operator) {
        return receive(detailId, qty, batchNo, productionDate, expiryDate, actualWeight,
                null, null, operator, null);
    }

    /** PC 逐行收货（无容器/备注/超收原因）。 */
    @Transactional
    public Map<String, Object> receive(String detailId, BigDecimal qty, String batchNo,
                                       LocalDate productionDate, LocalDate expiryDate,
                                       BigDecimal actualWeight, String containerCode,
                                       String goodsRemark, String operator) {
        return receive(detailId, qty, batchNo, productionDate, expiryDate, actualWeight,
                containerCode, goodsRemark, operator, null);
    }

    /**
     * PDA 扫码收货（V86 增强，PRD-28 卡片9 加超收授权）：
     * <ul>
     *   <li>保质期校验：商品 shelf_life_days&gt;0 强制生产日期，过期直接拦截；生产日期不允许晚于今天</li>
     *   <li>批次号：前端传了用前端的（用户手输不覆盖）；前端没传但有生产日期则按 yyyyMMdd 兜底</li>
     *   <li>容器收货：containerCode 非空时绑定到当前任务，后续上架按容器整箱上架</li>
     *   <li>行级备注：goodsRemark 记在 wms_inbound_task_detail.goods_remark</li>
     *   <li>超收：累计超过应收+容差时，必须具备 wms_pda.receive.over_receive 功能点且填写原因，
     *       放行同时写 wms_exception 留痕（异常中心可追踪）；无权限按原口径拒绝</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> receive(String detailId, BigDecimal qty, String batchNo,
                                       LocalDate productionDate, LocalDate expiryDate,
                                       BigDecimal actualWeight, String containerCode,
                                       String goodsRemark, String operator, String overReceiveReason) {
        if (qty == null || qty.signum() <= 0) throw new IllegalArgumentException("收货数量必须大于 0");
        List<Map<String, Object>> dd = TmsUtil.queryCamel(jdbc, """
                SELECT d.detail_id, d.task_id, d.goods_code, d.goods_name, d.expected_qty,
                       d.received_qty, d.container_code,
                       COALESCE(g.shelf_life_days,0) AS shelf_life_days
                FROM wms_inbound_task_detail d
                LEFT JOIN base_goods g ON g.goods_code = d.goods_code
                WHERE d.detail_id = ?
                """, detailId);
        if (dd.isEmpty()) throw new IllegalArgumentException("明细不存在");
        Map<String, Object> detail = dd.get(0);

        List<Map<String, Object>> th = TmsUtil.queryCamel(jdbc,
                "SELECT task_id, task_no, status, over_tolerance, warehouse FROM wms_inbound_task WHERE task_id = ?",
                detail.get("taskId"));
        if (th.isEmpty()) throw new IllegalArgumentException("入库任务不存在");
        Map<String, Object> task = th.get(0);
        warehouseResolver.assertIfPda(TmsUtil.str(task.get("warehouse")));
        String status = (String) task.get("status");
        if (!"PENDING".equals(status) && !"RECEIVING".equals(status)) {
            throw new IllegalArgumentException("当前状态[" + status + "]不允许收货");
        }

        BigDecimal expected = toBd(detail.get("expectedQty"));
        BigDecimal received = toBd(detail.get("receivedQty"));
        BigDecimal tolPct = toBd(task.get("overTolerance"));
        BigDecimal maxAllowed = expected.multiply(BigDecimal.ONE.add(tolPct.divide(BigDecimal.valueOf(100))));
        if (received.add(qty).compareTo(maxAllowed) > 0) {
            // PRD-28 卡片9：超容差须有超收授权功能点 + 强制录原因并写异常单留痕；否则按原口径拒绝
            if (!permissionService.hasFunc(FUNC_OVER_RECEIVE)) {
                throw new com.erp.common.security.PermissionDeniedException(
                        "无超收权限：累计收货 " + received.add(qty) + " 超过应收+容差上限 " + maxAllowed);
            }
            if (overReceiveReason == null || overReceiveReason.isBlank()) {
                throw new IllegalArgumentException("超收必须填写原因");
            }
            recordInboundOverReceive(task, detail, received.add(qty).subtract(maxAllowed),
                    overReceiveReason, operator);
        }

        // ========== 保质期校验 ==========
        int shelfLifeDays = toBd(detail.get("shelfLifeDays")).intValue();
        String goodsName = TmsUtil.str(detail.get("goodsName"));
        LocalDate today = LocalDate.now();
        LocalDate effectiveExpiry = expiryDate;
        if (shelfLifeDays > 0) {
            if (productionDate == null) {
                throw new IllegalArgumentException("商品 " + goodsName + " 有保质期要求（" + shelfLifeDays + " 天），必须录入生产日期");
            }
            if (productionDate.isAfter(today)) {
                throw new IllegalArgumentException("生产日期不能晚于今天：" + productionDate);
            }
            // 到期日期未传则按生产日期 + 保质期天数推算
            if (effectiveExpiry == null) {
                effectiveExpiry = productionDate.plusDays(shelfLifeDays);
            }
            if (effectiveExpiry.isBefore(today)) {
                throw new IllegalArgumentException("你录入的生产日期已过期，请核对重新录入（到期日 " + effectiveExpiry + "）");
            }
        } else if (productionDate != null && effectiveExpiry == null) {
            // 无保质期要求但录了生产日期，到期日就留空（不强行加）
        }

        // 批次号兜底：前端没传 + 有生产日期 → yyyyMMdd（全局规则，PDA 端也会同步生成，这里是双保险）
        String finalBatchNo = emptyToNull(batchNo);
        if (finalBatchNo == null && productionDate != null) {
            finalBatchNo = productionDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        }

        // 容器绑定（非空时）
        if (containerCode != null && !containerCode.isBlank()) {
            bindContainerForReceive(containerCode, TmsUtil.str(task.get("taskId")),
                    TmsUtil.str(task.get("taskNo")), TmsUtil.str(detail.get("goodsCode")),
                    qty, operator);
        } else {
            containerCode = TmsUtil.str(detail.get("containerCode"));
        }

        // 行
        jdbc.update("""
                UPDATE wms_inbound_task_detail
                SET received_qty = COALESCE(received_qty,0) + ?,
                    batch_no = COALESCE(?, batch_no),
                    production_date = COALESCE(?, production_date),
                    expiry_date = COALESCE(?, expiry_date),
                    actual_weight = ?,
                    container_code = COALESCE(NULLIF(?, ''), container_code),
                    goods_remark = COALESCE(NULLIF(?, ''), goods_remark),
                    status = 'RECEIVED', updated_at = CURRENT_TIMESTAMP
                WHERE detail_id = ?
                """, qty, finalBatchNo, productionDate, effectiveExpiry,
                actualWeight, containerCode, goodsRemark, detailId);

        // 头：状态置 RECEIVING，汇总数量
        jdbc.update("""
                UPDATE wms_inbound_task
                SET status = 'RECEIVING', received_qty = COALESCE(received_qty,0) + ?,
                    total_weight = COALESCE(total_weight,0) + COALESCE(?,0),
                    receiver = COALESCE(NULLIF(?, ''), receiver)
                WHERE task_id = ?
                """, qty, actualWeight, operator, task.get("taskId"));

        TmsUtil.log(jdbc, "wms.inbound", "RECEIVE", (String) task.get("taskNo"),
                "收货 " + detail.get("goodsCode") + " x" + qty
                        + (containerCode == null || containerCode.isBlank() ? "" : " 容器 " + containerCode)
                        + (finalBatchNo == null ? "" : " 批次 " + finalBatchNo));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("detailId", detailId);
        r.put("received", received.add(qty));
        r.put("batchNo", finalBatchNo);
        r.put("expiryDate", effectiveExpiry);
        r.put("containerCode", containerCode);
        return r;
    }

    /**
     * 超收授权留痕（PRD-28 卡片9）：写 wms_exception（异常中心可追踪），不阻断收货。
     * wave_no 记入库任务号，供异常中心按 wms_inbound_task 关联回仓库做隔离。
     */
    private void recordInboundOverReceive(Map<String, Object> task, Map<String, Object> detail,
                                          BigDecimal excessQty, String reason, String operator) {
        String exNo = billNo.nextNo(BillType.WMS_EXCEPTION, "wms_exception", "exception_no");
        jdbc.update("""
                INSERT INTO wms_exception
                (exception_id, exception_no, wave_no, source_order_no, goods_code, exception_type,
                 qty, status, description, reporter, source_type, source_bill, priority, created_at)
                VALUES (?, ?, ?, ?, ?, 'OTHER', ?, 'OPEN', ?, ?, 'INBOUND', ?, 'MEDIUM', CURRENT_TIMESTAMP)
                """, "EX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                exNo, TmsUtil.str(task.get("taskNo")), TmsUtil.str(task.get("taskNo")),
                TmsUtil.str(detail.get("goodsCode")), excessQty,
                "超收授权：" + safe(reason) + "（超出容差 " + excessQty + "）",
                operator, TmsUtil.str(task.get("taskNo")));
    }

    /**
     * 把容器绑定到当前收货任务（首次）或追加记录（已绑同一任务）。
     * 容器状态 EMPTY → IN_USE；若已被别的任务占用则拒绝。写 wms_container_record 流水。
     */
    private void bindContainerForReceive(String containerCode, String taskId, String taskNo,
                                         String goodsCode, BigDecimal qty, String operator) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT container_id, status, current_task_id FROM wms_container WHERE container_code = ?",
                containerCode);
        if (rows.isEmpty()) throw new IllegalArgumentException("容器不存在：" + containerCode);
        Map<String, Object> c = rows.get(0);
        String status = TmsUtil.str(c.get("status"));
        String currentTaskId = TmsUtil.str(c.get("currentTaskId"));
        if ("LOCKED".equals(status)) {
            throw new IllegalStateException("容器 " + containerCode + " 已冻结，不能收货");
        }
        if ("IN_USE".equals(status) && !taskId.equals(currentTaskId)) {
            throw new IllegalStateException("容器 " + containerCode + " 正在为任务 " + currentTaskId + " 服务，不能用于本任务");
        }
        if ("EMPTY".equals(status)) {
            jdbc.update("""
                    UPDATE wms_container SET status='IN_USE', current_task_id=?, current_task_no=?,
                        bound_at=CURRENT_TIMESTAMP, bound_by=? WHERE container_code=?
                    """, taskId, taskNo, operator, containerCode);
            writeContainerRecord(containerCode, taskId, taskNo, null, "BIND", goodsCode, null, operator, "容器绑定到收货任务");
        }
        writeContainerRecord(containerCode, taskId, taskNo, null, "RECEIVE", goodsCode, qty, operator, null);
    }

    private void writeContainerRecord(String containerCode, String taskId, String taskNo, String putawayId,
                                      String operation, String goodsCode, BigDecimal qty,
                                      String operator, String remark) {
        jdbc.update("""
                INSERT INTO wms_container_record
                (record_id, container_code, task_id, task_no, putaway_id, operation,
                 goods_code, qty, operator, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, "CR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                containerCode, taskId, taskNo, putawayId, operation,
                goodsCode, qty, operator, remark);
    }

    /** 收货完成：若启用复检则转 RECHECK，否则直接进入 PUTAWAY 待上架并生成上架任务。 */
    @Transactional
    public Map<String, Object> finishReceive(String taskId, String operator) {
        Map<String, Object> task = mustGetTask(taskId);
        warehouseResolver.assertIfPda(TmsUtil.str(task.get("warehouse")));
        String status = (String) task.get("status");
        if (!"RECEIVING".equals(status) && !"PENDING".equals(status)) {
            throw new IllegalStateException("当前状态不允许结束收货");
        }
        // 校验每行至少有收货量
        Integer shortLines = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_inbound_task_detail WHERE task_id = ? AND COALESCE(received_qty,0) <= 0",
                Integer.class, taskId);
        if (shortLines != null && shortLines > 0
                && params.getInt("WMS_RECEIVE_WITHOUT_ORDER", 1, 0, 1) == 0) {
            throw new IllegalStateException("仍有 " + shortLines + " 行未收货");
        }

        boolean recheckEnabled = params.getInt("WMS_RECHECK_ENABLED", 0, 0, 1) == 1;
        int recheckMode = params.getInt("WMS_RECHECK_MODE", 0, 0, 2);
        // 是否触发复检：0 全部复检；1 按金额阈值；2 按比例抽核
        boolean needRecheck = recheckEnabled;
        if (recheckEnabled && recheckMode == 1) {
            BigDecimal threshold = new BigDecimal(params.get("WMS_RECHECK_AMOUNT_THRESHOLD", "5000"));
            BigDecimal amt = computeReceiveAmount(taskId);
            needRecheck = amt.compareTo(threshold) >= 0;
        } else if (recheckEnabled && recheckMode == 2) {
            // 按比例：行数抽核（简化：按 detail_id 哈希取前 ratio% 行，有命中即复检）
            int ratio = params.getInt("WMS_RECHECK_RATIO", 20, 0, 100);
            Integer hit = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wms_inbound_task_detail WHERE task_id = ? "
                            + "AND MOD(ABS(RAWTOHEX(detail_id)), 100) < ?",
                    Integer.class, taskId, ratio);
            needRecheck = hit != null && hit > 0;
        }

        String nextStatus = needRecheck ? "RECHECK" : "PUTAWAY";
        jdbc.update("""
                UPDATE wms_inbound_task SET status = ?, received_at = CURRENT_TIMESTAMP,
                    rechecker = CASE WHEN ? = 'RECHECK' THEN ? ELSE rechecker END
                WHERE task_id = ?
                """, nextStatus, nextStatus, operator, taskId);

        if (!needRecheck) {
            generatePutawayTasks(taskId);
        }
        TmsUtil.log(jdbc, "wms.inbound", "FINISH_RECEIVE", (String) task.get("taskNo"),
                "收货完成，下一步=" + nextStatus);
        return Map.of("taskId", taskId, "nextStatus", nextStatus);
    }

    /** 复检结论：passed=true 时进入上架；false 时按不合格数量走异常流程。 */
    @Transactional
    public Map<String, Object> recheck(String taskId, boolean passed, String remark, String operator) {
        Map<String, Object> task = mustGetTask(taskId);
        warehouseResolver.assertIfPda(TmsUtil.str(task.get("warehouse")));
        if (!"RECHECK".equals(task.get("status"))) throw new IllegalArgumentException("当前状态不允许复检");
        // PRD-28 PDA-005：WMS_RECHECK_SELF_NG=1（默认）禁止复检本人收货的单
        if (warehouseResolver.isPda()
                && params.getInt("WMS_RECHECK_SELF_NG", 1, 0, 1) == 1) {
            String receiver = TmsUtil.str(task.get("receiver"));
            if (!receiver.isBlank() && receiver.equals(operator)) {
                throw new IllegalArgumentException("不能复检本人收货单");
            }
        }
        if (!passed) {
            // 不合格：记录未合格数量，转 EXCEPTION；不自动过账
            jdbc.update("UPDATE wms_inbound_task SET status = 'EXCEPTION', rechecker = ?, remark = ? WHERE task_id = ?",
                    operator, "复检不通过：" + safe(remark), taskId);
            TmsUtil.log(jdbc, "wms.inbound", "RECHECK_NG", (String) task.get("taskNo"), safe(remark));
            return Map.of("taskId", taskId, "status", "EXCEPTION");
        }
        // 合格数量 = 实收 - 不合格（明细里的 unqualified_qty 可由前端提前录入）
        jdbc.update("""
                UPDATE wms_inbound_task_detail
                SET qualified_qty = received_qty - COALESCE(unqualified_qty,0)
                WHERE task_id = ? AND COALESCE(qualified_qty,0) = 0
                """, taskId);
        jdbc.update("UPDATE wms_inbound_task SET status = 'PUTAWAY', rechecker = ? WHERE task_id = ?",
                operator, taskId);
        generatePutawayTasks(taskId);
        TmsUtil.log(jdbc, "wms.inbound", "RECHECK_OK", (String) task.get("taskNo"), "复检通过");
        return Map.of("taskId", taskId, "status", "PUTAWAY");
    }

    // ==================== 上架 ====================

    /**
     * 为已收货/复检通过的明细生成上架任务；推荐库位由 {@link #recommendBin} 决定。
     * V84：同时按商品 storage_property（温区）匹配库区，把推荐库区/温区写到明细与上架任务，
     * 收货标签与 PDA 上架页直接展示，不依赖前端再 JOIN。
     */
    private void generatePutawayTasks(String taskId) {
        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc, """
                SELECT d.detail_id, d.goods_code, d.goods_name, d.batch_no, d.production_date, d.expiry_date,
                       d.received_qty, d.qualified_qty, d.container_code, t.warehouse, t.task_no
                FROM wms_inbound_task_detail d JOIN wms_inbound_task t ON t.task_id = d.task_id
                WHERE d.task_id = ? AND COALESCE(d.received_qty,0) > 0 AND COALESCE(d.putaway_qty,0) = 0
                """, taskId);
        for (Map<String, Object> ln : lines) {
            BigDecimal q = toBd(ln.get("qualifiedQty"));
            if (q.signum() <= 0) q = toBd(ln.get("receivedQty"));
            if (q.signum() <= 0) continue;
            String putawayId = "PT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String putawayNo = billNo.nextNo(BillType.WMS_PUTAWAY, "wms_putaway_task", "putaway_no");
            String goodsCode = TmsUtil.str(ln.get("goodsCode"));
            String warehouse = TmsUtil.str(ln.get("warehouse"));
            String containerCode = TmsUtil.str(ln.get("containerCode"));
            // 容器收货：推荐可放容器的地堆/暂存位；无则回退普通空 SHELF 位
            Recommendation rec = containerCode.isBlank()
                    ? recommendBin(goodsCode, warehouse)
                    : recommendContainerBin(goodsCode, warehouse);
            jdbc.update("""
                    INSERT INTO wms_putaway_task
                    (putaway_id, putaway_no, inbound_task_id, inbound_no, goods_code, goods_name,
                     batch_no, production_date, expiry_date, qty, warehouse, container_code,
                     recommend_bin, recommend_zone_code, recommend_zone_name, recommend_storage_prop,
                     status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                    """, putawayId, putawayNo, taskId, TmsUtil.str(ln.get("taskNo")),
                    goodsCode, TmsUtil.str(ln.get("goodsName")),
                    TmsUtil.str(ln.get("batchNo")), ln.get("productionDate"), ln.get("expiryDate"),
                    q, warehouse, emptyToNull(containerCode),
                    rec.binCode, rec.zoneCode, rec.zoneName, rec.storageProperty);
            jdbc.update("""
                    UPDATE wms_inbound_task_detail
                       SET recommend_bin = ?, recommend_zone_code = ?, recommend_zone_name = ?,
                           recommend_storage_prop = ?, status = 'PUTTING'
                     WHERE detail_id = ?
                    """, rec.binCode, rec.zoneCode, rec.zoneName, rec.storageProperty, ln.get("detailId"));
        }
    }

    /**
     * 容器整体上架时的库位推荐：优先找 FLOOR/STAGE 等可放容器的库位类型；
     * 若没有专门类型，回退到普通空 SHELF 位。与 {@link #recommendBin} 的差别仅在候选库位类型范围。
     */
    private Recommendation recommendContainerBin(String goodsCode, String warehouse) {
        try {
            List<Map<String, Object>> zones = jdbc.queryForList("""
                    SELECT zone_code, zone_name, storage_property FROM wms_zone
                    WHERE warehouse = ? AND COALESCE(status,'') <> 'FROZEN'
                    ORDER BY CASE WHEN storage_property = ? THEN 0 ELSE 1 END, zone_code LIMIT 1
                    """, warehouse, lookupGoodsStorageProperty(goodsCode));
            if (!zones.isEmpty()) {
                Map<String, Object> z = zones.get(0);
                List<Map<String, Object>> bins = jdbc.queryForList("""
                        SELECT b.bin_code, b.zone_code FROM wms_bin b
                        WHERE b.warehouse = ? AND b.zone_code = ?
                          AND UPPER(COALESCE(b.bin_type,'SHELF')) IN ('FLOOR','STAGE','SHELF')
                          AND COALESCE(b.status,'') <> 'FROZEN'
                          AND NOT EXISTS (
                              SELECT 1 FROM wms_bin_stock s WHERE s.bin_code = b.bin_code AND s.warehouse = b.warehouse
                          )
                        ORDER BY CASE UPPER(b.bin_type) WHEN 'FLOOR' THEN 0 WHEN 'STAGE' THEN 1 ELSE 2 END, b.bin_code LIMIT 1
                        """, warehouse, z.get("zone_code"));
                if (!bins.isEmpty()) {
                    Map<String, Object> b = bins.get(0);
                    return new Recommendation(TmsUtil.str(b.get("bin_code")), TmsUtil.str(z.get("zone_code")),
                            TmsUtil.str(z.get("zone_name")), TmsUtil.str(z.get("storage_property")));
                }
            }
        } catch (Exception ignore) {
            // 表/列可能在老库不存在，回退到普通推荐
        }
        return recommendBin(goodsCode, warehouse);
    }

    public List<Map<String, Object>> putawayList(String status, String assignee) {
        // V87：联查 base_goods 取条码/规格；联查 wms_inbound_task 取业务类型/来源单号，供 PDA 卡片展示
        StringBuilder sql = new StringBuilder("""
                SELECT p.putaway_id, p.putaway_no, p.inbound_task_id, p.inbound_no, p.goods_code, p.goods_name,
                       p.batch_no, p.production_date, p.expiry_date, p.qty, p.warehouse, p.container_code,
                       p.recommend_bin, p.recommend_zone_code, p.recommend_zone_name, p.recommend_storage_prop,
                       p.actual_bin, p.assignee, p.status, p.remark, p.created_at, p.started_at, p.finished_at,
                       g.spec, g.barcode, g.base_unit AS unit_name,
                       t.inbound_type, t.source_order_no, t.supplier_name, t.customer_name
                FROM wms_putaway_task p
                LEFT JOIN base_goods g ON g.goods_code = p.goods_code
                LEFT JOIN wms_inbound_task t ON t.task_id = p.inbound_task_id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (warehouseResolver.isPda()) { sql.append(" AND p.warehouse = ?"); args.add(warehouseResolver.currentWarehouseName()); }
        if (status != null && !status.isBlank()) { sql.append(" AND p.status = ?"); args.add(status); }
        if (assignee != null && !assignee.isBlank()) { sql.append(" AND (p.assignee = ? OR p.assignee IS NULL)"); args.add(assignee); }
        sql.append(" ORDER BY p.created_at DESC");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    /**
     * 按商品查当前实物在哪些库位有库存（PDA 上架页"查看库存"弹窗用）。
     * 维度：库位 + 生产日期 + 批次；qty > 0 才返回。
     */
    public List<Map<String, Object>> binStockByGoods(String goodsCode, String warehouse) {
        // PDA 端仓库强制取登录仓，忽略入参（PDA-007）
        if (warehouseResolver.isPda()) warehouse = warehouseResolver.currentWarehouseName();
        StringBuilder sql = new StringBuilder("""
                SELECT s.bin_code, s.batch_no, s.production_date, s.expiry_date,
                       s.container_code, s.qty, s.locked_qty, s.updated_at,
                       b.bin_type, z.zone_name
                FROM wms_bin_stock s
                LEFT JOIN wms_bin b ON b.warehouse = s.warehouse AND b.bin_code = s.bin_code
                LEFT JOIN wms_zone z ON z.warehouse = s.warehouse AND z.zone_code = b.zone_code
                WHERE s.goods_code = ? AND COALESCE(s.qty,0) > 0
                """);
        List<Object> args = new ArrayList<>();
        args.add(goodsCode);
        if (warehouse != null && !warehouse.isBlank()) {
            sql.append(" AND s.warehouse = ?");
            args.add(warehouse);
        }
        sql.append(" ORDER BY s.updated_at DESC");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    /**
     * 批量上架：把指定 putaway_id 列表一次性上到各自的推荐库位。
     * 无推荐库位的任务会被跳过并在返回结果里列出，避免静默丢任务。
     * 注意：不在 Controller 上加 @Transactional，逐个调用独立事务的 confirmPutaway，
     * 避免"内层异常被外层 catch 后仍标 rollback-only"的陷阱。
     */
    public Map<String, Object> batchConfirmPutaway(List<String> putawayIds, String operator) {
        if (putawayIds == null || putawayIds.isEmpty()) {
            throw new IllegalArgumentException("请选择要上架的任务");
        }
        List<String> done = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (String id : putawayIds) {
            try {
                List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                        "SELECT recommend_bin, status FROM wms_putaway_task WHERE putaway_id = ?", id);
                if (rows.isEmpty()) {
                    skipped.add(Map.of("putawayId", id, "reason", "任务不存在"));
                    continue;
                }
                Map<String, Object> row = rows.get(0);
                if ("DONE".equals(row.get("status"))) {
                    skipped.add(Map.of("putawayId", id, "reason", "已完成"));
                    continue;
                }
                String rec = TmsUtil.str(row.get("recommendBin"));
                if (rec.isBlank()) {
                    skipped.add(Map.of("putawayId", id, "reason", "无推荐库位，请单独扫码上架"));
                    continue;
                }
                confirmPutaway(id, rec, operator);
                done.add(id);
            } catch (Exception e) {
                skipped.add(Map.of("putawayId", id, "reason", e.getMessage() == null ? "上架失败" : e.getMessage()));
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("doneCount", done.size());
        r.put("skipped", skipped);
        return r;
    }

    @Transactional
    public Map<String, Object> claimPutaway(String putawayId, String operator) {
        List<Map<String, Object>> pre = TmsUtil.queryCamel(jdbc,
                "SELECT warehouse, status FROM wms_putaway_task WHERE putaway_id = ?", putawayId);
        if (pre.isEmpty()) throw new IllegalArgumentException("上架任务不存在");
        warehouseResolver.assertIfPda(TmsUtil.str(pre.get(0).get("warehouse")));
        int n = jdbc.update("""
                UPDATE wms_putaway_task SET assignee = ?, status = 'PUTTING', started_at = CURRENT_TIMESTAMP
                WHERE putaway_id = ? AND (assignee IS NULL OR assignee = '' OR assignee = ?)
                """, operator, putawayId, operator);
        if (n == 0) throw new IllegalStateException("任务已被他人领取");
        return Map.of("putawayId", putawayId, "assignee", operator);
    }

    /**
     * PDA/PC 确认上架到具体库位：增加 wms_bin_stock 实物数量；
     * 全部上架完则过账采购入库单，触发 {@link InventoryCostService#purchaseInbound}。
     */
    @Transactional
    public Map<String, Object> confirmPutaway(String putawayId, String actualBin, String operator) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_putaway_task WHERE putaway_id = ?", putawayId);
        if (rows.isEmpty()) throw new IllegalArgumentException("上架任务不存在");
        Map<String, Object> p = rows.get(0);
        warehouseResolver.assertIfPda(TmsUtil.str(p.get("warehouse")));
        if ("DONE".equals(p.get("status"))) throw new IllegalArgumentException("任务已完成");
        if ("CANCELLED".equals(p.get("status"))) throw new IllegalArgumentException("任务已取消");
        String bin = actualBin == null || actualBin.isBlank() ? TmsUtil.str(p.get("recommendBin")) : actualBin;
        if (bin.isBlank()) throw new IllegalArgumentException("必须指定上架库位");

        // 1) 实物账：增加 wms_bin_stock（唯一键 goods/warehouse/batch/bin/container）
        String goodsCode = TmsUtil.str(p.get("goodsCode"));
        String goodsName = TmsUtil.str(p.get("goodsName"));
        String warehouse = TmsUtil.str(p.get("warehouse"));
        String batchNo = TmsUtil.str(p.get("batchNo"));
        String containerCode = TmsUtil.str(p.get("containerCode"));
        BigDecimal qty = toBd(p.get("qty"));
        addBinStock(goodsCode, goodsName, warehouse, batchNo, bin, qty, containerCode,
                TmsUtil.toLocalDate(p.get("productionDate")), TmsUtil.toLocalDate(p.get("expiryDate")));

        // 2) 更新上架任务
        jdbc.update("""
                UPDATE wms_putaway_task SET actual_bin = ?, assignee = COALESCE(NULLIF(?, ''), assignee),
                    status = 'DONE', finished_at = CURRENT_TIMESTAMP WHERE putaway_id = ?
                """, bin, operator, putawayId);

        // 3) 回写入库明细：同商品 + 同容器（或都为空）+ 同批次的行优先匹配，避免同商品多容器串行错位
        String taskId = TmsUtil.str(p.get("inboundTaskId"));
        jdbc.update("""
                UPDATE wms_inbound_task_detail
                SET putaway_qty = COALESCE(putaway_qty,0) + ?, actual_bin = ?, status = 'DONE'
                WHERE detail_id = (
                    SELECT d.detail_id FROM wms_inbound_task_detail d
                    WHERE d.task_id = ? AND d.goods_code = ?
                      AND COALESCE(d.container_code,'') = COALESCE(?, '')
                      AND COALESCE(d.batch_no,'') = COALESCE(?, '')
                    ORDER BY d.putaway_qty ASC LIMIT 1)
                """, qty, bin, taskId, goodsCode, containerCode, batchNo);

        // 4) 入库头：累计上架量；全部上架完则过账
        jdbc.update("""
                UPDATE wms_inbound_task SET putaway_qty = (
                    SELECT COALESCE(SUM(putaway_qty),0) FROM wms_inbound_task_detail WHERE task_id = ?
                ) WHERE task_id = ?
                """, taskId, taskId);

        // 5) 容器：上架完成写流水；整单全部上架完后释放所有用过的容器
        if (!containerCode.isBlank()) {
            writeContainerRecord(containerCode, taskId, TmsUtil.str(p.get("inboundNo")), putawayId,
                    "PUTAWAY", goodsCode, qty, operator, "上架到 " + bin);
        }

        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_putaway_task WHERE inbound_task_id = ? AND status <> 'DONE'",
                Integer.class, taskId);
        if (pending == null || pending == 0) {
            if (!containerCode.isBlank()) {
                // 整单收完：本任务用过的所有容器释放回 EMPTY
                jdbc.update("""
                        UPDATE wms_container SET status='EMPTY', current_task_id=NULL, current_task_no=NULL,
                            bound_at=NULL, bound_by=NULL
                        WHERE current_task_id = ?
                        """, taskId);
                jdbc.update("""
                        INSERT INTO wms_container_record
                        (record_id, container_code, task_id, operation, operator, remark, created_at)
                        SELECT 'CR' || UPPER(REPLACE(RANDOM_UUID(),'-','')),
                               container_code, ?, 'RELEASE', ?, '整单上架完成，自动释放', CURRENT_TIMESTAMP
                        FROM wms_container_record
                        WHERE task_id = ? AND operation = 'BIND'
                        """, taskId, operator, taskId);
            }
            postInbound(taskId, operator);
        }
        TmsUtil.log(jdbc, "wms.inbound", "PUTAWAY", (String) p.get("putawayNo"),
                "上架到 " + bin + " x" + qty + (containerCode.isBlank() ? "" : " 容器 " + containerCode));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("putawayId", putawayId);
        r.put("actualBin", bin);
        r.put("qty", qty);
        r.put("containerCode", containerCode);
        return r;
    }

    /**
     * 上架全部完成 → 生成正式采购入库单并审核，由审核链写财务库存与成本。
     * 非采购类型（OTHER）当前走 inboundAtCurrentCost；销售退货/拒收由其原有流程触发。
     */
    private void postInbound(String taskId, String operator) {
        Map<String, Object> task = mustGetTask(taskId);
        if ("DONE".equals(task.get("status"))) return;
        String inboundType = TmsUtil.str(task.get("inboundType"));
        String sourceOrderNo = TmsUtil.str(task.get("sourceOrderNo"));
        String warehouse = strOr(task.get("warehouse"), "总仓");

        if ("PURCHASE".equals(inboundType)) {
            // 直接走采购入库审核链：会更新采购单收货数量、写 inv_batch_stock/inv_stock_balance、写流水
            try {
                Object r = purchaseController.createAndAuditForWms(sourceOrderNo, taskId, operator);
                if (r instanceof Map<?, ?> m && m.get("inboundNo") != null) {
                    jdbc.update("UPDATE wms_inbound_task SET generated_inbound_no = ?, status = 'DONE', putaway_at = CURRENT_TIMESTAMP WHERE task_id = ?",
                            String.valueOf(m.get("inboundNo")), taskId);
                    return;
                }
            } catch (Exception e) {
                // 采购入库审核失败：保持 PUTAWAY 状态，由异常中心排查，不回滚实物上架（已上货架不能撤）
                jdbc.update("UPDATE wms_inbound_task SET status = 'EXCEPTION', remark = ? WHERE task_id = ?",
                        "过账失败：" + e.getMessage(), taskId);
                throw e;
            }
        } else if ("SALES_RETURN".equals(inboundType)) {
            // 销售退货：把 PDA 实收数量/批次回写到 sales_return_inbound_detail，再走原审核链回库
            if (salesReturnController == null) {
                jdbc.update("UPDATE wms_inbound_task SET status = 'EXCEPTION', remark = ? WHERE task_id = ?",
                        "销售退货服务未注入，无法自动审核退货入库单 " + sourceOrderNo, taskId);
                throw new IllegalStateException("SalesReturnController 未注入");
            }
            try {
                Map<String, Object> r = salesReturnController.auditFromWms(sourceOrderNo, taskId, operator);
                jdbc.update("UPDATE wms_inbound_task SET generated_inbound_no = ?, status = 'DONE', putaway_at = CURRENT_TIMESTAMP WHERE task_id = ?",
                        String.valueOf(r.getOrDefault("inboundNo", sourceOrderNo)), taskId);
                TmsUtil.log(jdbc, "wms.inbound", "AUTO_AUDIT_SALES_RETURN",
                        TmsUtil.str(task.get("taskNo")),
                        "上架完成 → 自动审核销售退货入库单 " + sourceOrderNo + "，库存已回库");
                return;
            } catch (Exception e) {
                jdbc.update("UPDATE wms_inbound_task SET status = 'EXCEPTION', remark = ? WHERE task_id = ?",
                        "退货入库单审核失败：" + e.getMessage(), taskId);
                throw e;
            }
        } else {
            // OTHER / TRANSFER / 未走采购链的：按当前成本直接入财务库存（不重算成本）
            List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                    "SELECT goods_code, goods_name, batch_no, production_date, received_qty, qualified_qty "
                            + "FROM wms_inbound_task_detail WHERE task_id = ?", taskId);
            for (Map<String, Object> ln : lines) {
                BigDecimal q = toBd(ln.get("qualifiedQty"));
                if (q.signum() <= 0) q = toBd(ln.get("receivedQty"));
                if (q.signum() <= 0) continue;
                LocalDate prod = TmsUtil.toLocalDate(ln.get("productionDate"));
                inventoryCost.inboundAtCurrentCost(
                        TmsUtil.str(ln.get("goodsCode")), TmsUtil.str(ln.get("goodsName")),
                        warehouse, TmsUtil.str(ln.get("batchNo")), q,
                        BigDecimal.ZERO, "WMS_INBOUND:" + task.get("taskNo"), prod);
            }
        }
        jdbc.update("UPDATE wms_inbound_task SET status = 'DONE', putaway_at = CURRENT_TIMESTAMP WHERE task_id = ?",
                taskId);
    }

    // ==================== 库位推荐 ====================

    /** 推荐结果：库位 + 所属库区编码/名称 + 商品温区（用于收货标签打印）。 */
    public record Recommendation(String binCode, String zoneCode, String zoneName, String storageProperty) {
        static Recommendation empty() { return new Recommendation("", "", "", ""); }
    }

    /**
     * 库位推荐（V84 增强）：
     *   1. 参数 WMS_PUTAWAY_FREE_BIN=0 时不推荐，PDA 手扫；
     *   2. 先取商品 storage_property（温区）；
     *   3. 同品已有库存库位（保证一品一位补货），同时回填该库位所在库区；
     *   4. 商品档案默认库位（base_goods.location）；
     *   5. 按「温区匹配库区 → 该库区下空 SHELF 库位」推荐；
     *   6. 找不到温区匹配时，回退到任意未冻结空 SHELF 库位（保持原行为，不阻断流程）。
     * 任何一步命中都同时查出库区名/温区，便于标签展示。
     */
    private Recommendation recommendBin(String goodsCode, String warehouse) {
        if (params.getInt("WMS_PUTAWAY_FREE_BIN", 1, 0, 1) == 0) return Recommendation.empty();
        String goodsStorage = lookupGoodsStorageProperty(goodsCode);

        // 1) 同品现有库位（就近补位）
        List<Map<String, Object>> same = jdbc.queryForList("""
                SELECT s.bin_code AS bin_code, b.zone_code AS zone_code, z.zone_name AS zone_name,
                       z.storage_property AS zone_storage
                  FROM wms_bin_stock s
                  LEFT JOIN wms_bin b   ON b.warehouse = s.warehouse AND b.bin_code = s.bin_code
                  LEFT JOIN wms_zone z  ON z.warehouse = b.warehouse AND z.zone_code = b.zone_code
                 WHERE s.goods_code = ? AND s.warehouse = ? AND s.qty > 0
                 ORDER BY s.updated_at DESC LIMIT 1
                """, goodsCode, warehouse);
        if (!same.isEmpty()) {
            Map<String, Object> r = same.get(0);
            return new Recommendation(
                    TmsUtil.str(r.get("bin_code")),
                    TmsUtil.str(r.get("zone_code")),
                    TmsUtil.str(r.get("zone_name")),
                    firstNonBlank(TmsUtil.str(r.get("zone_storage")), goodsStorage));
        }

        // 2) 商品档案默认库位
        try {
            List<String> loc = jdbc.queryForList(
                    "SELECT location FROM base_goods WHERE goods_code = ? AND location IS NOT NULL AND location <> ''",
                    String.class, goodsCode);
            if (!loc.isEmpty()) {
                List<Map<String, Object>> exists = jdbc.queryForList("""
                        SELECT b.bin_code, b.zone_code, z.zone_name, z.storage_property
                          FROM wms_bin b
                          LEFT JOIN wms_zone z ON z.warehouse = b.warehouse AND z.zone_code = b.zone_code
                         WHERE b.warehouse = ? AND b.bin_code = ? AND COALESCE(b.frozen,'N')='N'
                        """, warehouse, loc.get(0));
                if (!exists.isEmpty()) {
                    Map<String, Object> r = exists.get(0);
                    return new Recommendation(
                            TmsUtil.str(r.get("bin_code")),
                            TmsUtil.str(r.get("zone_code")),
                            TmsUtil.str(r.get("zone_name")),
                            firstNonBlank(TmsUtil.str(r.get("storage_property")), goodsStorage));
                }
            }
        } catch (Exception ignore) { /* base_goods.location 列可能不存在 */ }

        // 3) 温区匹配的空库位
        if (!goodsStorage.isBlank()) {
            List<Map<String, Object>> matched = jdbc.queryForList("""
                    SELECT b.bin_code, b.zone_code, z.zone_name, z.storage_property
                      FROM wms_bin b
                      JOIN wms_zone z ON z.warehouse = b.warehouse AND z.zone_code = b.zone_code
                      LEFT JOIN wms_bin_stock s ON s.bin_code = b.bin_code AND s.warehouse = b.warehouse
                     WHERE b.warehouse = ?
                       AND b.bin_type = 'SHELF'
                       AND COALESCE(b.frozen,'N') = 'N'
                       AND COALESCE(z.frozen,'N') = 'N'
                       AND z.storage_property = ?
                     GROUP BY b.bin_code, b.zone_code, z.zone_name, z.storage_property, b.capacity_qty, b.used_qty
                    HAVING COALESCE(SUM(s.qty),0) = 0
                     ORDER BY z.pick_seq, b.bin_code LIMIT 1
                    """, warehouse, goodsStorage);
            if (!matched.isEmpty()) {
                Map<String, Object> r = matched.get(0);
                return new Recommendation(
                        TmsUtil.str(r.get("bin_code")),
                        TmsUtil.str(r.get("zone_code")),
                        TmsUtil.str(r.get("zone_name")),
                        goodsStorage);
            }
        }

        // 4) 回退：任意未冻结空 SHELF 库位
        List<Map<String, Object>> fallback = jdbc.queryForList("""
                SELECT b.bin_code, b.zone_code, z.zone_name, z.storage_property
                  FROM wms_bin b
                  LEFT JOIN wms_zone z ON z.warehouse = b.warehouse AND z.zone_code = b.zone_code
                  LEFT JOIN wms_bin_stock s ON s.bin_code = b.bin_code AND s.warehouse = b.warehouse
                 WHERE b.warehouse = ? AND b.bin_type = 'SHELF' AND COALESCE(b.frozen,'N') = 'N'
                 GROUP BY b.bin_code, b.zone_code, z.zone_name, z.storage_property, b.capacity_qty, b.used_qty
                HAVING COALESCE(SUM(s.qty),0) = 0
                 ORDER BY b.bin_code LIMIT 1
                """, warehouse);
        if (fallback.isEmpty()) return new Recommendation("", "", "", goodsStorage);
        Map<String, Object> r = fallback.get(0);
        return new Recommendation(
                TmsUtil.str(r.get("bin_code")),
                TmsUtil.str(r.get("zone_code")),
                TmsUtil.str(r.get("zone_name")),
                firstNonBlank(TmsUtil.str(r.get("storage_property")), goodsStorage));
    }

    private String lookupGoodsStorageProperty(String goodsCode) {
        try {
            List<String> v = jdbc.queryForList(
                    "SELECT storage_property FROM base_goods WHERE goods_code = ?",
                    String.class, goodsCode);
            return v.isEmpty() ? "" : TmsUtil.str(v.get(0));
        } catch (Exception ignore) { return ""; }
    }

    private String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b == null ? "" : b);
    }

    // ==================== 公共工具 ====================

    private void addBinStock(String goodsCode, String goodsName, String warehouse,
                             String batchNo, String bin, BigDecimal qty) {
        addBinStock(goodsCode, goodsName, warehouse, batchNo, bin, qty, null, null, null);
    }

    /** 增加实物账（V86：支持容器维度；V87：支持生产日期/到期日，唯一键 goods/warehouse/batch/bin/container）。 */
    private void addBinStock(String goodsCode, String goodsName, String warehouse,
                             String batchNo, String bin, BigDecimal qty, String containerCode,
                             LocalDate productionDate, LocalDate expiryDate) {
        String cc = containerCode == null ? "" : containerCode;
        String bn = batchNo == null ? "" : batchNo;
        int n = jdbc.update("""
                UPDATE wms_bin_stock SET qty = qty + ?, updated_at = CURRENT_TIMESTAMP
                WHERE goods_code = ? AND warehouse = ? AND bin_code = ?
                  AND COALESCE(batch_no,'') = ? AND COALESCE(container_code,'') = ?
                """, qty, goodsCode, warehouse, bin, bn, cc);
        if (n > 0) return;
        jdbc.update("""
                INSERT INTO wms_bin_stock
                (bin_stock_id, goods_code, goods_name, warehouse, batch_no, bin_code, container_code,
                 production_date, expiry_date, qty, locked_qty, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP)
                """, "BS" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(),
                goodsCode, goodsName, warehouse, emptyToNull(batchNo), bin,
                cc.isBlank() ? "" : cc, productionDate, expiryDate, qty);
        jdbc.update("UPDATE wms_bin SET used_qty = COALESCE(used_qty,0) + ? WHERE warehouse = ? AND bin_code = ?",
                qty, warehouse, bin);
        try {
            jdbc.update("""
                    INSERT INTO wms_bin_stock_log (log_id, warehouse, goods_code, batch_no, to_bin, container_code, direction, qty, source_bill, operator)
                    VALUES (?, ?, ?, ?, ?, ?, 'IN', ?, 'WMS_PUTAWAY', ?)
                    """, "BL" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(),
                    warehouse, goodsCode, emptyToNull(batchNo), bin, cc.isBlank() ? null : cc,
                    qty, TmsUtil.currentUser());
        } catch (Exception ignore) {}
    }

    private Map<String, Object> mustGetTask(String taskId) {
        List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_inbound_task WHERE task_id = ?", taskId);
        if (r.isEmpty()) throw new IllegalArgumentException("入库任务不存在");
        return r.get(0);
    }

    private BigDecimal computeReceiveAmount(String taskId) {
        // 按当前移动加权成本估算收货金额（仅用于复检阈值判断，不影响过账）
        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                "SELECT d.goods_code, d.received_qty FROM wms_inbound_task_detail d WHERE d.task_id = ?", taskId);
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> ln : lines) {
            BigDecimal q = toBd(ln.get("receivedQty"));
            if (q.signum() <= 0) continue;
            try {
                BigDecimal cost = inventoryCost.getCurrentCostPrice(TmsUtil.str(ln.get("goodsCode")), "总仓");
                if (cost != null) total = total.add(q.multiply(cost));
            } catch (Exception ignore) {}
        }
        return total;
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
    private static String safe(String s) { return s == null ? "" : s; }
}
