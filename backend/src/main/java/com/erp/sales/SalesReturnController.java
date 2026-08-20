package com.erp.sales;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.inventory.service.InventoryCostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 销售退货全流程 REST 端点（V60 双路径版）。
 * <p>
 * 退货方式（return_type）决定走哪条路径，两条路径共用 logistics_status 列表达流转：
 * <ol>
 *   <li>司机回收 DRIVER：待确认→已确认→[安排调度]→已安排调度→进配送任务池→已调度
 *       →司机APP按行签收→司机已回收（回写退货数量/金额）→生成退货入库单（按回收数量）
 *       →仓库收货→已入库→审核退货单（写负向应收）</li>
 *   <li>自提到仓 WAREHOUSE：待确认→已确认→[推送仓库]→已推送仓库→生成退货入库单（按申请数量）
 *       →仓库收货（退货数量=入库数量）→已入库→审核退货单（写负向应收）</li>
 * </ol>
 * <p>
 * 入库单不再由「退货单审核」生成 —— 审核退化为纯财务动作。生成时机见
 * {@link #pushWarehouse} 与 {@code TmsAppController.returnSign}。
 * 审核的前置条件由系统参数 {@code SALES_RETURN_AR_TIMING} 控制，见 {@link #loadArTiming}。
 * <p>
 * 全部使用 JdbcTemplate 直写，DTO 用 {@code Map<String, Object>}。
 */
@RestController
@RequestMapping("/sales")
public class SalesReturnController {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 退货方式：司机回收（进 TMS 配送任务池，由司机上门取货）。 */
    public static final String RETURN_TYPE_DRIVER = "DRIVER";
    /** 退货方式：自提到仓（客户自行送回，ERP 直接推送仓库收货）。 */
    public static final String RETURN_TYPE_WAREHOUSE = "WAREHOUSE";

    /** 流转状态：未安排（两种退货方式的起点）。 */
    public static final String LOGISTICS_UNARRANGED = "未安排";
    /** 流转状态：已安排调度（仅司机回收，已进 TMS 配送任务池等待组车）。 */
    public static final String LOGISTICS_ARRANGED = "已安排调度";
    /** 流转状态：已调度（仅司机回收，已分配司机，等司机上门签收）。 */
    public static final String LOGISTICS_DISPATCHED = "已调度";
    /** 流转状态：已推送仓库（仅自提到仓，入库单已生成待收货）。 */
    public static final String LOGISTICS_PUSHED = "已推送仓库";
    /** 流转状态：司机已回收（仅司机回收，货在车上，入库单已按回收数量生成）。 */
    public static final String LOGISTICS_DRIVER_COLLECTED = "司机已回收";
    /** 流转状态：已入库（两种方式的终点，退货入库单已审核回库）。 */
    public static final String LOGISTICS_INBOUNDED = "已入库";

    /** 入账时点：按仓库收货入账，退货入库单审核后才允许审核退货单。 */
    public static final String AR_TIMING_WAREHOUSE = "WAREHOUSE_INBOUND";
    /** 入账时点：按司机回收入账，司机确认回收即自动审核退货单。 */
    public static final String AR_TIMING_DRIVER = "DRIVER_SIGN";

    private final JdbcTemplate jdbcTemplate;
    private final InventoryCostService inventoryCostService;
    private final com.erp.common.util.BillNoGenerator billNoGen;

    public SalesReturnController(JdbcTemplate jdbcTemplate,
                                 InventoryCostService inventoryCostService,
                                 com.erp.common.util.BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.inventoryCostService = inventoryCostService;
        this.billNoGen = billNoGen;
    }

    // ========================================================================
    //  销售退货单 — 列表 / 创建 / 详情 / 确认 / 驳回 / 审核 / 反审核 / 删除
    // ========================================================================

    /**
     * 【按单添加商品】左表：该客户已审核的销售出库单列表。
     */
    @GetMapping("/return-order/outbound-bills")
    public ApiResponse<List<Map<String, Object>>> outboundBills(
            @RequestParam String customerName,
            @RequestParam(required = false) String outboundNo,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        if (customerName == null || customerName.isBlank()) {
            throw new IllegalArgumentException("请先选择客户");
        }
        LocalDate from = parseDate(dateFrom, LocalDate.now().minusYears(1));
        LocalDate to = parseDate(dateTo, LocalDate.now());
        String noLike = (outboundNo == null || outboundNo.isBlank()) ? null : "%" + outboundNo.trim() + "%";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT h.outbound_id, h.outbound_no, h.bill_date, h.customer, h.warehouse, h.qty, h.amount
                FROM sales_outbound h
                WHERE h.status = 'APPROVED' AND h.customer = ?
                  AND h.bill_date >= ? AND h.bill_date <= ?
                  AND (? IS NULL OR h.outbound_no LIKE ?)
                  AND EXISTS (
                      SELECT 1 FROM sales_outbound_detail d
                      WHERE d.outbound_id = h.outbound_id
                        AND d.qty > COALESCE((
                            SELECT SUM(rd.qty) FROM sales_return_apply_detail rd
                            JOIN sales_return_apply rh ON rd.apply_id = rh.apply_id
                            WHERE rd.source_detail_id = d.detail_id
                              AND rh.status NOT IN ('CANCELLED', 'REJECTED')
                        ), 0)
                  )
                ORDER BY h.bill_date DESC, h.outbound_no DESC
                """, customerName, from, to, noLike, noLike);
        return ApiResponse.ok(rows.stream().map(SalesReturnController::camelize).toList());
    }

    /**
     * 【按单添加商品】右表：选中出库单的明细 + 已退数量 + 成本单价 + 可用库存。
     */
    @GetMapping("/return-order/outbound-detail")
    public ApiResponse<Map<String, Object>> outboundDetail(
            @RequestParam String outboundId,
            @RequestParam(required = false) String goodsKeyword) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT outbound_id, outbound_no, customer, warehouse, bill_date, status
                FROM sales_outbound WHERE outbound_id = ? OR outbound_no = ?
                """, outboundId, outboundId);
        if (heads.isEmpty()) throw new IllegalArgumentException("销售出库单不存在：" + outboundId);
        Map<String, Object> head = heads.get(0);
        String status = str(pick(head, "status"));
        if (!"APPROVED".equals(status)) {
            throw new IllegalArgumentException("仅已审核的销售出库单可退货，当前状态：" + status);
        }
        String realOutboundId = str(pick(head, "outbound_id"));
        String outboundNo = str(pick(head, "outbound_no"));
        String warehouse = str(pick(head, "warehouse"));

        String kw = (goodsKeyword == null || goodsKeyword.isBlank())
                ? null : "%" + goodsKeyword.trim().toLowerCase() + "%";

        List<Map<String, Object>> details = jdbcTemplate.queryForList("""
                SELECT d.detail_id, d.goods_code, d.goods_name, g.spec, d.unit_name,
                       d.qty, d.price, d.amount,
                       d.production_date, d.batch_no,
                       COALESCE(NULLIF(d.cost_price, 0), d.price, 0) AS cost_price,
                       COALESCE(sb.available_qty, 0) AS available_stock,
                       COALESCE((SELECT SUM(rd.qty) FROM sales_return_apply_detail rd
                                 JOIN sales_return_apply rh ON rd.apply_id = rh.apply_id
                                 WHERE rd.source_detail_id = d.detail_id
                                   AND rh.status NOT IN ('CANCELLED', 'REJECTED')), 0) AS returned_qty
                FROM sales_outbound_detail d
                LEFT JOIN base_goods g ON d.goods_code = g.goods_code
                LEFT JOIN inv_stock_balance sb
                       ON sb.goods_code = d.goods_code AND sb.warehouse = d.warehouse
                WHERE d.outbound_id = ?
                  AND (? IS NULL OR LOWER(d.goods_code) LIKE ? OR LOWER(d.goods_name) LIKE ?)
                ORDER BY d.detail_id
                """, realOutboundId, kw, kw, kw);

        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> d : details) {
            Map<String, Object> line = camelize(d);
            BigDecimal qty = toBd(pick(d, "qty"));
            BigDecimal returnedQty = toBd(pick(d, "returned_qty"));
            BigDecimal returnableQty = qty.subtract(returnedQty);
            if (returnableQty.signum() < 0) returnableQty = BigDecimal.ZERO;
            line.put("returnableQty", returnableQty);
            line.put("sourceOutboundNo", outboundNo);
            lines.add(line);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("outboundId", realOutboundId);
        result.put("outboundNo", outboundNo);
        result.put("customer", str(pick(head, "customer")));
        result.put("warehouse", warehouse);
        result.put("details", lines);
        return ApiResponse.ok(result);
    }

    /**
     * 【添加商品】三页签商品数据源：HISTORY（历史销售）/ CUSTOMER（客户商品）/ ALL（全部商品）。
     */
    @GetMapping("/return-order/goods-options")
    public ApiResponse<List<Map<String, Object>>> goodsOptions(
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String warehouse,
            @RequestParam(required = false) String keyword) {
        String mode = (tab == null || tab.isBlank()) ? "HISTORY" : tab.trim().toUpperCase(Locale.ROOT);
        String kw = (keyword == null || keyword.isBlank())
                ? null : "%" + keyword.trim().toLowerCase() + "%";
        String wh = warehouse == null ? "" : warehouse;

        List<Map<String, Object>> rows;
        switch (mode) {
            case "HISTORY" -> {
                if (customerName == null || customerName.isBlank()) {
                    throw new IllegalArgumentException("请先选择客户");
                }
                rows = jdbcTemplate.queryForList("""
                        SELECT d.goods_code, MIN(d.goods_name) AS goods_name, MIN(g.spec) AS spec,
                               MIN(g.base_unit) AS base_unit, MIN(g.unit_config) AS unit_config,
                               MAX(h.bill_date) AS last_outbound_date,
                               COALESCE(MAX(sb.cost_price), 0) AS cost_price,
                               COALESCE(MAX(sb.available_qty), 0) AS available_stock,
                               COALESCE(MAX(g.standard_price), 0) AS latest_sale_price
                        FROM sales_outbound_detail d
                        JOIN sales_outbound h ON d.outbound_id = h.outbound_id
                        LEFT JOIN base_goods g ON d.goods_code = g.goods_code
                        LEFT JOIN inv_stock_balance sb
                               ON sb.goods_code = d.goods_code AND sb.warehouse = ?
                        WHERE h.status = 'APPROVED' AND h.customer = ?
                          AND (? IS NULL OR LOWER(d.goods_code) LIKE ? OR LOWER(d.goods_name) LIKE ?)
                        GROUP BY d.goods_code
                        ORDER BY MAX(h.bill_date) DESC, d.goods_code
                        """, wh, customerName, kw, kw, kw);
            }
            case "CUSTOMER" -> {
                if (customerName == null || customerName.isBlank()) {
                    throw new IllegalArgumentException("请先选择客户");
                }
                rows = jdbcTemplate.queryForList("""
                        SELECT g.goods_code, g.goods_name, g.spec, g.base_unit, g.unit_config,
                               COALESCE(g.standard_price, 0) AS latest_sale_price,
                               COALESCE(sb.cost_price, 0) AS cost_price,
                               COALESCE(sb.available_qty, 0) AS available_stock
                        FROM base_goods g
                        LEFT JOIN inv_stock_balance sb
                               ON sb.goods_code = g.goods_code AND sb.warehouse = ?
                        WHERE COALESCE(g.status, 'NORMAL') <> 'STOPPED'
                          AND (? IS NULL OR LOWER(g.goods_code) LIKE ? OR LOWER(g.goods_name) LIKE ?
                               OR LOWER(COALESCE(g.barcode, '')) LIKE ?)
                        ORDER BY g.goods_code
                        """, wh, kw, kw, kw, kw);
            }
            default -> {
                rows = jdbcTemplate.queryForList("""
                        SELECT g.goods_code, g.goods_name, g.spec, g.base_unit, g.unit_config,
                               COALESCE(g.standard_price, 0) AS latest_sale_price,
                               COALESCE(sb.cost_price, 0) AS cost_price,
                               COALESCE(sb.available_qty, 0) AS available_stock
                        FROM base_goods g
                        LEFT JOIN inv_stock_balance sb
                               ON sb.goods_code = g.goods_code AND sb.warehouse = ?
                        WHERE COALESCE(g.status, 'NORMAL') <> 'STOPPED'
                          AND (? IS NULL OR LOWER(g.goods_code) LIKE ? OR LOWER(g.goods_name) LIKE ?
                               OR LOWER(COALESCE(g.barcode, '')) LIKE ?)
                        ORDER BY g.goods_code
                        """, wh, kw, kw, kw, kw);
            }
        }
        return ApiResponse.ok(rows.stream().map(SalesReturnController::camelize).toList());
    }

    /** 批次下拉。 */
    @GetMapping("/return-order/batch-options")
    public ApiResponse<List<Map<String, Object>>> batchOptions(
            @RequestParam String goodsCode,
            @RequestParam String warehouse) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bs.batch_no,
                       COALESCE(bs.production_date, (
                           SELECT MAX(d.production_date) FROM sales_outbound_detail d
                           WHERE d.goods_code = bs.goods_code AND d.warehouse = bs.warehouse
                             AND d.batch_no = bs.batch_no AND d.production_date IS NOT NULL
                       )) AS production_date,
                       bs.expiry_date, bs.qty,
                       (bs.qty - COALESCE(bs.locked_qty, 0) - COALESCE(bs.frozen_qty, 0)) AS available_qty,
                       bs.cost_price
                FROM inv_batch_stock bs
                WHERE bs.goods_code = ? AND bs.warehouse = ? AND bs.qty > 0
                ORDER BY production_date, bs.batch_no
                """, goodsCode, warehouse);
        return ApiResponse.ok(rows.stream().map(SalesReturnController::camelize).toList());
    }

    /** 销售退货单列表。 */
    @PostMapping("/return-order/page")
    public ApiResponse<PageResult<Map<String, Object>>> returnOrderPage(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT apply_id, apply_no, source_outbound_no,
                       customer_code, customer_name, warehouse, bill_date,
                       qty, return_qty, inbound_qty, signed_qty,
                       amount, return_amount, inbound_amount,
                       return_reason, status, inbound_generated,
                       return_type, logistics_status, driver_name, arrange_time, push_time,
                       creator_name, confirmed_user, confirmed_time, audit_user, audit_time, create_time, remark
                FROM sales_return_apply
                ORDER BY create_time DESC, apply_no DESC
                """);
        List<Map<String, Object>> mapped = rows.stream().map(r -> {
            Map<String, Object> row = camelize(r);
            row.put("statusText", resolveReturnOrderStatusText(str(pick(r, "status"))));
            row.put("returnTypeText", resolveReturnTypeText(str(pick(r, "return_type"))));
            // 流转状态是 return_type + logistics_status 的组合语义，不能只看 logistics_status
            row.put("logisticsStatusText", resolveLogisticsStatusText(
                    str(pick(r, "return_type")), str(pick(r, "logistics_status"))));
            // 退货金额 = 退货数量 * 单价（由前端计算），这里回送退货数量
            row.put("returnQty", toBd(pick(r, "return_qty")));
            row.put("inboundQty", toBd(pick(r, "inbound_qty")));
            row.put("signedQty", toBd(pick(r, "signed_qty")));
            row.put("returnAmount", toBd(pick(r, "return_amount")));
            row.put("inboundAmount", toBd(pick(r, "inbound_amount")));
            row.put("creatorInfo", str(pick(r, "creator_name")) + " " + str(pick(r, "create_time")));
            return row;
        }).collect(Collectors.toList());
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    /** 销售退货单详情。 */
    @GetMapping("/return-order/detail")
    public ApiResponse<Map<String, Object>> returnOrderDetail(
            @RequestParam(required = false) String applyId,
            @RequestParam(required = false) String id) {
        String key = applyId != null && !applyId.isBlank() ? applyId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 applyId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_apply WHERE apply_id = ? OR apply_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "销售退货单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        head.put("returnQty", toBd(pick(heads.get(0), "return_qty")));
        head.put("inboundQty", toBd(pick(heads.get(0), "inbound_qty")));
        head.put("returnAmount", toBd(pick(heads.get(0), "return_amount")));
        head.put("inboundAmount", toBd(pick(heads.get(0), "inbound_amount")));
        head.put("returnTypeText", resolveReturnTypeText(str(pick(heads.get(0), "return_type"))));
        head.put("logisticsStatusText", resolveLogisticsStatusText(
                str(pick(heads.get(0), "return_type")), str(pick(heads.get(0), "logistics_status"))));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_apply_detail WHERE apply_id = ? ORDER BY detail_id",
                head.get("applyId"));
        head.put("details", details.stream().map(SalesReturnController::camelize).toList());
        return ApiResponse.ok(head);
    }

    /** 创建销售退货单：状态 = 待确认，return_qty = qty。 */
    @PostMapping("/return-order/create")
    @Transactional
    public ApiResponse<Map<String, Object>> createReturnOrder(@RequestBody Map<String, Object> request) {
        String customer = str(request.get("customer"));
        String warehouse = str(request.get("warehouse"));
        LocalDate billDate = LocalDate.now();
        String returnReason = str(request.get("returnReason"));
        String remark = str(request.get("remark"));
        String status = str(request.get("status")).isBlank() ? "PENDING" : str(request.get("status"));
        // 退货方式决定后续走「安排调度→配送任务池」还是「推送仓库」，缺省自提到仓（不进 TMS）
        String returnType = normalizeReturnType(request.get("returnType"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        if (reqDetails.isEmpty()) throw new IllegalArgumentException("退货明细不能为空");

        validateDetails(reqDetails, null);

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("qty"));
            BigDecimal p = toBd(line.get("price"));
            BigDecimal a = q.multiply(p).setScale(2, RoundingMode.HALF_UP);
            line.put("_amount", a);
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(a);
        }

        String id = "SRA" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.SALES_RETURN_REQ, "sales_return_apply", "apply_no");

        String headSourceNo = reqDetails.stream()
                .map(l -> str(l.get("sourceOutboundNo")))
                .filter(s -> !s.isBlank())
                .findFirst().orElse("");

        jdbcTemplate.update("""
                INSERT INTO sales_return_apply(apply_id, apply_no, source_outbound_no,
                    customer_code, customer_name, warehouse, bill_date,
                    qty, return_qty, amount, return_amount, return_reason, status,
                    return_type, logistics_status, creator_name, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '系统管理员', ?)
                """, id, no, headSourceNo.isBlank() ? null : headSourceNo,
                str(request.get("customerCode")), customer, warehouse, billDate,
                totalQty, totalQty, totalAmount, totalAmount, returnReason, status,
                returnType, LOGISTICS_UNARRANGED, remark);

        for (Map<String, Object> line : reqDetails) {
            insertApplyDetail(id, line);
        }

        log("sales.return.order", "CREATE", no,
                "创建销售退货单（待确认，退货方式：" + resolveReturnTypeText(returnType) + "）");
        return ApiResponse.ok(Map.of("applyId", id, "applyNo", no, "status", status, "returnType", returnType));
    }

    /** 修改销售退货单（仅 DRAFT / PENDING 状态）。支持修改退货数量。 */
    @PostMapping("/return-order/update")
    @Transactional
    public ApiResponse<Map<String, Object>> updateReturnOrder(@RequestBody Map<String, Object> request) {
        String applyId = str(request.get("applyId"));
        if (applyId.isBlank()) throw new IllegalArgumentException("缺少 applyId");

        Map<String, Object> existing = findApplyById(applyId);
        String status = str(pick(existing, "status"));
        if (!"DRAFT".equals(status) && !"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅草稿或待确认的退货单可修改，当前状态：" + resolveReturnOrderStatusText(status));
        }

        jdbcTemplate.update("DELETE FROM sales_return_apply_detail WHERE apply_id = ?", applyId);

        String customer = strOrDefault(request.get("customer"), str(pick(existing, "customer_name")));
        String warehouse = strOrDefault(request.get("warehouse"), str(pick(existing, "warehouse")));
        LocalDate billDate = parseDate(pick(existing, "bill_date"), LocalDate.now());
        String returnReason = strOrDefault(request.get("returnReason"), str(pick(existing, "return_reason")));
        String remark = strOrDefault(request.get("remark"), str(pick(existing, "remark")));
        // 退货方式仅草稿/待确认可改（已确认后流转路径已定，改方式会让 logistics_status 串味）
        String returnType = request.get("returnType") == null || str(request.get("returnType")).isBlank()
                ? normalizeReturnType(pick(existing, "return_type"))
                : normalizeReturnType(request.get("returnType"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        if (reqDetails.isEmpty()) {
            List<Map<String, Object>> oldDetails = jdbcTemplate.queryForList(
                    "SELECT * FROM sales_return_apply_detail WHERE apply_id = ?", applyId);
            for (Map<String, Object> od : oldDetails) {
                reqDetails.add(camelize(od));
            }
        }
        if (reqDetails.isEmpty()) throw new IllegalArgumentException("退货明细不能为空");

        validateDetails(reqDetails, applyId);

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalReturnQty = BigDecimal.ZERO;
        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("qty"));
            BigDecimal p = toBd(line.get("price"));
            BigDecimal a = q.multiply(p).setScale(2, RoundingMode.HALF_UP);
            line.put("_amount", a);
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(a);
            // 退货数量：优先取 returnQty，否则取 qty
            BigDecimal rq = line.get("returnQty") != null ? toBd(line.get("returnQty")) : q;
            totalReturnQty = totalReturnQty.add(rq);
        }

        String headSourceNo = reqDetails.stream()
                .map(l -> str(l.get("sourceOutboundNo")))
                .filter(s -> !s.isBlank())
                .findFirst().orElse("");

        jdbcTemplate.update("""
                UPDATE sales_return_apply SET source_outbound_no=?, customer_code=?, customer_name=?,
                    warehouse=?, bill_date=?, qty=?, return_qty=?, amount=?, return_amount=?,
                    return_reason=?, return_type=?, remark=?
                WHERE apply_id=?
                """, headSourceNo.isBlank() ? null : headSourceNo,
                strOrDefault(request.get("customerCode"), str(pick(existing, "customer_code"))),
                customer, warehouse, billDate, totalQty, totalReturnQty, totalAmount, totalAmount,
                returnReason, returnType, remark, applyId);

        for (Map<String, Object> line : reqDetails) {
            insertApplyDetail(applyId, line);
        }

        log("sales.return.order", "UPDATE", str(pick(existing, "apply_no")), "修改销售退货单");
        return ApiResponse.ok(Map.of("applyId", applyId, "status", status));
    }

    /** 确认退货：PENDING → CONFIRMED。 */
    @PostMapping("/return-order/confirm")
    @Transactional
    public ApiResponse<Map<String, Object>> confirmReturnOrder(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> order = findApplyById(request.bizId());
        String status = str(pick(order, "status"));
        if (!"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅待确认的退货单可确认，当前状态：" + resolveReturnOrderStatusText(status));
        }
        String applyId = str(pick(order, "apply_id"));
        String applyNo = str(pick(order, "apply_no"));

        jdbcTemplate.update("""
                UPDATE sales_return_apply SET status='CONFIRMED',
                    confirmed_user=?, confirmed_time=CURRENT_TIMESTAMP
                WHERE apply_id=?
                """, "系统管理员", applyId);

        log("sales.return.order", "CONFIRM", applyNo, "确认退货");
        return ApiResponse.ok(Map.of("applyId", applyId, "applyNo", applyNo, "status", "CONFIRMED",
                "effect", "退货已确认"));
    }

    /** 驳回：PENDING → REJECTED，单据关闭。 */
    @PostMapping("/return-order/reject")
    @Transactional
    public ApiResponse<Map<String, Object>> rejectReturnOrder(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> order = findApplyById(request.bizId());
        String status = str(pick(order, "status"));
        if (!"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅待确认的退货单可驳回，当前状态：" + resolveReturnOrderStatusText(status));
        }
        String applyId = str(pick(order, "apply_id"));
        String applyNo = str(pick(order, "apply_no"));

        jdbcTemplate.update("""
                UPDATE sales_return_apply SET status='REJECTED',
                    audit_user=?, audit_time=CURRENT_TIMESTAMP
                WHERE apply_id=?
                """, "系统管理员", applyId);

        log("sales.return.order", "REJECT", applyNo, "驳回退货单");
        return ApiResponse.ok(Map.of("applyId", applyId, "applyNo", applyNo, "status", "REJECTED",
                "effect", "退货单已驳回，单据关闭"));
    }

    /**
     * 推送仓库（仅自提到仓）：未安排 → 已推送仓库，同时按申请数量生成退货入库单。
     * <p>
     * 这是自提到仓路径下入库单的唯一生成入口 —— 客户自己把货送回来，
     * 不经过 TMS 调度，所以不需要「安排调度」，直接给仓库下收货指令。
     * 幂等：入库单已存在时不重复生成，直接返回原单号。
     */
    @PostMapping("/return-order/push-warehouse")
    @Transactional
    public ApiResponse<Map<String, Object>> pushWarehouse(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> order = findApplyById(request.bizId());
        String applyId = str(pick(order, "apply_id"));
        String applyNo = str(pick(order, "apply_no"));
        String returnType = normalizeReturnType(pick(order, "return_type"));
        String status = str(pick(order, "status"));
        String logisticsStatus = strOrDefault(pick(order, "logistics_status"), LOGISTICS_UNARRANGED);

        if (!RETURN_TYPE_WAREHOUSE.equals(returnType)) {
            throw new IllegalArgumentException("仅「自提到仓」退货单可推送仓库，司机回收型请走「安排调度」进配送任务池");
        }
        if (!"CONFIRMED".equals(status)) {
            throw new IllegalArgumentException("仅已确认的退货单可推送仓库，当前状态：" + resolveReturnOrderStatusText(status));
        }
        if (!LOGISTICS_UNARRANGED.equals(logisticsStatus)) {
            throw new IllegalArgumentException("当前流转状态为「" + logisticsStatus + "」，不可重复推送仓库");
        }

        // 按申请数量生成入库单（自提到仓没有司机签收环节，实收由仓库在入库单上改）
        String inboundNo = generateInboundFromApply(applyId, false);

        jdbcTemplate.update("""
                UPDATE sales_return_apply SET logistics_status=?, push_time=CURRENT_TIMESTAMP, push_user=?
                WHERE apply_id=?
                """, LOGISTICS_PUSHED, "系统管理员", applyId);

        log("sales.return.order", "PUSH_WAREHOUSE", applyNo,
                "推送仓库 → 生成退货入库单 " + inboundNo + "，流转状态 未安排 → 已推送仓库");
        return ApiResponse.ok(Map.of("applyId", applyId, "applyNo", applyNo,
                "logisticsStatus", LOGISTICS_PUSHED, "inboundNo", inboundNo,
                "effect", "已推送仓库，生成退货入库单 " + inboundNo + "，等待仓库收货"));
    }

    /**
     * 撤销推送仓库：已推送仓库 → 未安排，删除未审核的退货入库单。
     * <p>
     * 仓库已收货（入库单已审核）则不可撤销，需走入库单反审核链路。
     */
    @PostMapping("/return-order/cancel-push")
    @Transactional
    public ApiResponse<Map<String, Object>> cancelPushWarehouse(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> order = findApplyById(request.bizId());
        String applyId = str(pick(order, "apply_id"));
        String applyNo = str(pick(order, "apply_no"));
        String logisticsStatus = strOrDefault(pick(order, "logistics_status"), LOGISTICS_UNARRANGED);

        if (!LOGISTICS_PUSHED.equals(logisticsStatus)) {
            throw new IllegalArgumentException("当前流转状态为「" + logisticsStatus + "」，仅「已推送仓库」可撤销推送");
        }

        List<Map<String, Object>> ibRows = jdbcTemplate.queryForList(
                "SELECT inbound_id, inbound_no, status FROM sales_return_inbound WHERE source_apply_no = ?", applyNo);
        for (Map<String, Object> ib : ibRows) {
            if ("APPROVED".equals(str(pick(ib, "status")))) {
                throw new IllegalArgumentException("退货入库单 " + str(pick(ib, "inbound_no"))
                        + " 已审核（仓库已收货），无法撤销推送");
            }
            String ibId = str(pick(ib, "inbound_id"));
            jdbcTemplate.update("DELETE FROM sales_return_inbound_detail WHERE inbound_id = ?", ibId);
            jdbcTemplate.update("DELETE FROM sales_return_inbound WHERE inbound_id = ?", ibId);
        }

        jdbcTemplate.update("""
                UPDATE sales_return_apply SET logistics_status=?, inbound_generated=FALSE,
                    push_time=NULL, push_user=NULL
                WHERE apply_id=?
                """, LOGISTICS_UNARRANGED, applyId);

        log("sales.return.order", "CANCEL_PUSH", applyNo, "撤销推送仓库 → 删除未审核入库单，流转状态回退未安排");
        return ApiResponse.ok(Map.of("applyId", applyId, "applyNo", applyNo,
                "logisticsStatus", LOGISTICS_UNARRANGED, "effect", "已撤销推送，入库单已删除"));
    }

    /**
     * 单独修改退货方式（司机回收 ⇄ 自提到仓）。
     * <p>
     * {@link #updateReturnOrder} 只允许草稿/待确认修改，但退货方式选错往往是确认之后才发现的
     * （单子既没推送也没排调度，卡在那儿两条路都走不了）。这里放宽到「已确认 + 未安排」：
     * 此时既没有入库单也没有调度记录，切换方式不会留下脏数据。
     * 一旦推送/排调度/回收，就必须先撤销那一步才能改。
     */
    @PostMapping("/return-order/change-return-type")
    @Transactional
    public ApiResponse<Map<String, Object>> changeReturnType(@RequestBody Map<String, Object> request) {
        String bizId = strOrDefault(request.get("bizId"), str(request.get("applyId")));
        if (bizId.isBlank()) throw new IllegalArgumentException("缺少 applyId");
        Map<String, Object> order = findApplyById(bizId);
        String applyId = str(pick(order, "apply_id"));
        String applyNo = str(pick(order, "apply_no"));
        String status = str(pick(order, "status"));
        String logisticsStatus = strOrDefault(pick(order, "logistics_status"), LOGISTICS_UNARRANGED);

        if (!"DRAFT".equals(status) && !"PENDING".equals(status) && !"CONFIRMED".equals(status)) {
            throw new IllegalArgumentException("当前状态为" + resolveReturnOrderStatusText(status) + "，不可修改退货方式");
        }
        if (!LOGISTICS_UNARRANGED.equals(logisticsStatus)) {
            throw new IllegalArgumentException("当前流转状态为「" + logisticsStatus
                    + "」，请先撤销推送 / 取消调度后再改退货方式");
        }

        String oldType = normalizeReturnType(pick(order, "return_type"));
        String newType = normalizeReturnType(request.get("returnType"));
        if (oldType.equals(newType)) {
            return ApiResponse.ok(Map.of("applyId", applyId, "applyNo", applyNo,
                    "returnType", newType, "effect", "退货方式未变化"));
        }

        jdbcTemplate.update("UPDATE sales_return_apply SET return_type=? WHERE apply_id=?", newType, applyId);
        log("sales.return.order", "CHANGE_RETURN_TYPE", applyNo, "退货方式 "
                + resolveReturnTypeText(oldType) + " → " + resolveReturnTypeText(newType));
        return ApiResponse.ok(Map.of("applyId", applyId, "applyNo", applyNo,
                "returnType", newType, "returnTypeText", resolveReturnTypeText(newType),
                "effect", "退货方式已改为" + resolveReturnTypeText(newType)));
    }

    /**
     * 审核销售退货单：CONFIRMED → APPROVED。V60 起退化为纯财务动作 —— 只写负向 fin_ar 冲减应收，
     * 不再生成退货入库单（入库单由「推送仓库」或「司机签收」生成）。
     * <p>
     * 前置条件按退货方式 + 系统参数 {@code SALES_RETURN_AR_TIMING} 分流，见 {@link #assertAuditable}。
     * 应收金额取 {@code return_amount}（退货金额）而非 {@code amount}（申请金额）——
     * 司机少签收时二者不等，冲减应收必须按实际退回的货算。
     */
    @PostMapping("/return-order/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditReturnOrder(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> order = findApplyById(request.bizId());
        String status = str(pick(order, "status"));
        if (!"CONFIRMED".equals(status)) {
            throw new IllegalArgumentException("仅已确认的退货单可审核，当前状态：" + resolveReturnOrderStatusText(status));
        }
        String applyId = str(pick(order, "apply_id"));
        String applyNo = str(pick(order, "apply_no"));

        // 按退货方式 + 入账时点参数校验是否到了可入账的节点
        assertAuditable(order);

        String effect = writeReturnAr(applyId, applyNo, "系统管理员");
        log("sales.return.order", "AUDIT", applyNo, "销售退货单审核 → " + effect);
        return ApiResponse.ok(Map.of("applyId", applyId, "applyNo", applyNo, "status", "APPROVED",
                "effect", effect));
    }

    /**
     * 校验退货单是否已到可入账（审核）的节点。
     * <p>
     * <ul>
     *   <li>自提到仓：一律等仓库收货完成（已入库）</li>
     *   <li>司机回收 + 按仓库收货入账：等仓库收货完成（已入库）</li>
     *   <li>司机回收 + 按司机回收入账：司机已回收即可（此时通常已由签收动作自动审核过）</li>
     * </ul>
     */
    private void assertAuditable(Map<String, Object> order) {
        String returnType = normalizeReturnType(pick(order, "return_type"));
        String logisticsStatus = strOrDefault(pick(order, "logistics_status"), LOGISTICS_UNARRANGED);
        String arTiming = loadArTiming();

        if (RETURN_TYPE_DRIVER.equals(returnType) && AR_TIMING_DRIVER.equals(arTiming)) {
            if (!LOGISTICS_DRIVER_COLLECTED.equals(logisticsStatus) && !LOGISTICS_INBOUNDED.equals(logisticsStatus)) {
                throw new IllegalArgumentException("当前流转状态为「" + logisticsStatus
                        + "」，司机尚未确认回收，无法审核（入账时点：按司机回收入账）");
            }
            return;
        }
        if (!LOGISTICS_INBOUNDED.equals(logisticsStatus)) {
            // 提示要跟着当前卡在哪一步走，否则「已推送仓库」的单子被告知「请先推送仓库」，只会让人以为系统坏了
            String hint;
            if (LOGISTICS_PUSHED.equals(logisticsStatus) || LOGISTICS_DRIVER_COLLECTED.equals(logisticsStatus)) {
                hint = "，请先完成仓库收货（审核退货入库单）";
            } else if (RETURN_TYPE_DRIVER.equals(returnType)) {
                hint = "，请先安排调度、由司机回收后完成仓库收货";
            } else {
                hint = "，请先「推送仓库」并完成仓库收货（审核退货入库单）";
            }
            throw new IllegalArgumentException("当前流转状态为「" + logisticsStatus
                    + "」，退货货物尚未入库，无法审核（入账时点：按仓库收货入账）" + hint);
        }
    }

    /**
     * 写负向应收并把退货单置为已审核（幂等：已有 fin_ar 时不重复写）。
     * <p>
     * 供人工审核与「按司机回收入账」的自动审核共用，返回描述性的 effect 文案。
     *
     * @param auditUser 审核人；自动审核时传入系统标识，便于事后区分人工与自动
     * @return 效果描述，写入操作日志与接口响应
     */
    private String writeReturnAr(String applyId, String applyNo, String auditUser) {
        Map<String, Object> order = findApplyById(applyId);
        String customer = str(pick(order, "customer_name"));
        // 退货金额（签收/入库后确定）；历史单据 return_amount 为 0 时回退申请金额，避免写出 0 应收
        BigDecimal returnAmount = toBd(pick(order, "return_amount"));
        if (returnAmount.signum() == 0) returnAmount = toBd(pick(order, "amount"));

        // 税额按行实际退货数量倒算（价内税），行退货数量优先取签收数
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_apply_detail WHERE apply_id = ?", applyId);
        BigDecimal totalTaxAmount = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            BigDecimal lineAmount = effectiveLineAmount(d);
            BigDecimal lineTax = taxInclusiveTax(lineAmount, parseTaxRate(str(pick(d, "tax_rate"))));
            totalTaxAmount = totalTaxAmount.add(lineTax);
        }
        BigDecimal taxExcludedAmount = returnAmount.subtract(totalTaxAmount);

        // 检查是否已生成应收（幂等）
        List<String> existingAr = jdbcTemplate.queryForList(
                "SELECT ar_id FROM fin_ar WHERE source_bill = ?", String.class, applyNo);
        String arNo;
        if (existingAr.isEmpty()) {
            String arId = "AR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            arNo = billNoGen.nextNo("AR", "fin_ar", "ar_no");
            jdbcTemplate.update("""
                    INSERT INTO fin_ar(ar_id, ar_no, source_bill, customer,
                        ar_amount, received_amount, unreceived_amount, due_date, status)
                    VALUES (?, ?, ?, ?, ?, 0, ?, DATEADD('DAY', 30, CURRENT_DATE), 'UNVERIFIED')
                    """, arId, arNo, applyNo, customer,
                    returnAmount.negate(), returnAmount.negate());
        } else {
            arNo = "";
        }

        jdbcTemplate.update("""
                UPDATE sales_return_apply SET status='APPROVED', audit_user=?, audit_time=CURRENT_TIMESTAMP
                WHERE apply_id=?
                """, auditUser, applyId);

        return "已审核，按退货金额 " + plain(returnAmount) + " 写入负向应收"
                + (arNo.isBlank() ? "（应收已存在，未重复写入）" : " " + arNo)
                + "，其中税额 " + plain(totalTaxAmount) + "，不含税 " + plain(taxExcludedAmount);
    }

    /**
     * 反审核：APPROVED → CONFIRMED，撤销负向应收。
     * <p>
     * V60 起入库单不由审核生成，所以反审核<b>不再删除入库单</b> —— 入库单归属于
     * 「推送仓库 / 司机签收」环节，撤销它要走 cancel-push 或入库单自身的链路。
     * 已审核（仓库已收货）的入库单意味着货已回库，此时反审核会让账实不符，直接拦。
     */
    @PostMapping("/return-order/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAuditReturnOrder(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> order = findApplyById(request.bizId());
        String status = str(pick(order, "status"));
        if (!"APPROVED".equals(status)) {
            throw new IllegalArgumentException("仅已审核的退货单可反审核，当前状态：" + resolveReturnOrderStatusText(status));
        }
        String applyId = str(pick(order, "apply_id"));
        String applyNo = str(pick(order, "apply_no"));

        // 入库单已审核 = 货已回库，账实会不符，不允许反审核
        List<Map<String, Object>> ibRows = jdbcTemplate.queryForList(
                "SELECT inbound_no, status FROM sales_return_inbound WHERE source_apply_no = ?", applyNo);
        for (Map<String, Object> ib : ibRows) {
            if ("APPROVED".equals(str(pick(ib, "status")))) {
                throw new IllegalArgumentException("退货入库单 " + str(pick(ib, "inbound_no"))
                        + " 已审核（货已回库），无法反审核退货单");
            }
        }

        // 删除负向应收
        List<Map<String, Object>> arRows = jdbcTemplate.queryForList(
                "SELECT ar_id, ar_no, received_amount FROM fin_ar WHERE source_bill = ?", applyNo);
        for (Map<String, Object> ar : arRows) {
            if (toBd(pick(ar, "received_amount")).compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("已有收款记录，无法反审核");
            }
        }
        jdbcTemplate.update("DELETE FROM fin_ar WHERE source_bill = ?", applyNo);

        // 只回滚单据状态，保留 logistics_status —— 物流事实（已回收/已推送）不因财务反审核而改变
        jdbcTemplate.update("UPDATE sales_return_apply SET status='CONFIRMED', " +
                "audit_user=NULL, audit_time=NULL WHERE apply_id=?", applyId);

        log("sales.return.order", "REVERSE_AUDIT", applyNo, "销售退货单反审核 → 恢复为已确认，负向应收已撤销");
        return ApiResponse.ok(Map.of("applyId", applyId, "status", "CONFIRMED", "effect", "已反审核，应收冲减已撤销"));
    }

    @PostMapping("/return-order/delete")
    @Transactional
    public ApiResponse<Map<String, Object>> deleteReturnOrder(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> order = findApplyById(request.bizId());
        String status = str(pick(order, "status"));
        if (!"DRAFT".equals(status)) throw new IllegalArgumentException("仅草稿可删除，当前状态：" + resolveReturnOrderStatusText(status));
        String applyId = str(pick(order, "apply_id"));
        String applyNo = str(pick(order, "apply_no"));
        jdbcTemplate.update("DELETE FROM sales_return_apply_detail WHERE apply_id = ?", applyId);
        jdbcTemplate.update("DELETE FROM sales_return_apply WHERE apply_id = ?", applyId);
        log("sales.return.order", "DELETE", applyNo, "删除销售退货单草稿");
        return ApiResponse.ok(Map.of("applyId", applyId, "effect", "已删除"));
    }

    // ========================================================================
    //  销售退货入库单 — 列表 / 详情 / 更新 / 审核
    // ========================================================================

    @PostMapping("/return-inbound/page")
    public ApiResponse<PageResult<Map<String, Object>>> inboundPage(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT inbound_id, inbound_no, source_apply_no,
                       customer_code, customer_name, bill_date,
                       qty, amount, cost_amount, status, stock_updated,
                       audit_user, audit_time, create_time, remark
                FROM sales_return_inbound
                ORDER BY create_time DESC, inbound_no DESC
                """);
        List<Map<String, Object>> mapped = rows.stream().map(r -> {
            Map<String, Object> row = camelize(r);
            String st = str(pick(r, "status"));
            row.put("statusText", "PENDING".equals(st) ? "待审核" : "已审核");
            return row;
        }).collect(Collectors.toList());
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @GetMapping("/return-inbound/detail")
    public ApiResponse<Map<String, Object>> inboundDetail(
            @RequestParam(required = false) String inboundId,
            @RequestParam(required = false) String id) {
        String key = inboundId != null && !inboundId.isBlank() ? inboundId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 inboundId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_inbound WHERE inbound_id = ? OR inbound_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "退货入库单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_inbound_detail WHERE inbound_id = ? ORDER BY detail_id",
                head.get("inboundId"));
        head.put("details", details.stream().map(SalesReturnController::camelize).toList());
        return ApiResponse.ok(head);
    }

    /** 修改入库数量（仅 PENDING，不可超申请数量）。 */
    @PostMapping("/return-inbound/update")
    @Transactional
    public ApiResponse<Map<String, Object>> updateInbound(@RequestBody Map<String, Object> request) {
        String inboundId = str(request.get("inboundId"));
        if (inboundId.isBlank()) throw new IllegalArgumentException("缺少 inboundId");

        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_inbound WHERE inbound_id = ?", inboundId);
        if (heads.isEmpty()) throw new IllegalArgumentException("退货入库单不存在");
        Map<String, Object> ib = heads.get(0);
        if (!"PENDING".equals(str(pick(ib, "status")))) throw new IllegalArgumentException("仅待审核的入库单可修改");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        if (reqDetails.isEmpty()) return ApiResponse.ok(Map.of("inboundId", inboundId, "effect", "无变更"));

        String applyNo = str(pick(ib, "source_apply_no"));
        Map<String, BigDecimal> applyQtyByLine = new HashMap<>();
        Map<String, String> applyGoodsByLine = new HashMap<>();
        Map<String, BigDecimal> applyPriceByLine = new HashMap<>();
        for (Map<String, Object> ad : jdbcTemplate.queryForList(
                "SELECT detail_id, goods_code, goods_name, qty, price FROM sales_return_apply_detail WHERE apply_id IN " +
                        "(SELECT apply_id FROM sales_return_apply WHERE apply_no = ?)", applyNo)) {
            String adId = str(pick(ad, "detail_id"));
            applyQtyByLine.put(adId, toBd(pick(ad, "qty")));
            applyGoodsByLine.put(adId, strOrDefault(pick(ad, "goods_name"), str(pick(ad, "goods_code"))));
            applyPriceByLine.put(adId, toBd(pick(ad, "price")));
        }
        // 退货单头的仓库，用于兜底行级 warehouse（V29 起入库明细按行记仓库，缺失会导致审核报「未指定入库仓库」）
        String applyWarehouse = "";
        List<String> whRows = jdbcTemplate.queryForList(
                "SELECT warehouse FROM sales_return_apply WHERE apply_no = ?", String.class, applyNo);
        if (!whRows.isEmpty()) applyWarehouse = str(whRows.get(0));

        // 重建明细是「删后插」，请求里没带的字段必须从原行继承，否则会被静默清零
        // （典型事故：前端只提交 qty/batchNo，price 被写成 0 → 入库金额 0 → 回写退货单金额 0）
        Map<String, Map<String, Object>> oldByDetailId = new HashMap<>();
        for (Map<String, Object> od : jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_inbound_detail WHERE inbound_id = ?", inboundId)) {
            oldByDetailId.put(str(pick(od, "detail_id")), od);
        }

        Map<String, BigDecimal> sumByApplyLine = new HashMap<>();
        for (Map<String, Object> line : reqDetails) {
            Map<String, Object> old = oldByDetailId.get(str(line.get("detailId")));
            BigDecimal q = toBd(inherit(line, "qty", old, "qty"));
            if (q.signum() < 0) {
                throw new IllegalArgumentException("商品 " + str(line.get("goodsCode")) + " 的入库数量不能为负数");
            }
            String applyDetailId = str(inherit(line, "applyDetailId", old, "apply_detail_id"));
            if (!applyDetailId.isBlank()) {
                sumByApplyLine.merge(applyDetailId, q, BigDecimal::add);
            }
        }
        for (Map.Entry<String, BigDecimal> e : sumByApplyLine.entrySet()) {
            BigDecimal applyQty = applyQtyByLine.get(e.getKey());
            if (applyQty == null) {
                throw new IllegalArgumentException("入库明细关联的退货单行不存在：" + e.getKey());
            }
            if (e.getValue().compareTo(applyQty) > 0) {
                throw new IllegalArgumentException("商品 " + applyGoodsByLine.getOrDefault(e.getKey(), e.getKey())
                        + " 总入库数量 " + plain(e.getValue()) + " 超过退货数量 " + plain(applyQty));
            }
        }

        jdbcTemplate.update("DELETE FROM sales_return_inbound_detail WHERE inbound_id = ?", inboundId);
        for (Map<String, Object> line : reqDetails) {
            Map<String, Object> old = oldByDetailId.get(str(line.get("detailId")));
            String applyDetailId = str(inherit(line, "applyDetailId", old, "apply_detail_id"));
            BigDecimal q = toBd(inherit(line, "qty", old, "qty"));
            BigDecimal price = toBd(inherit(line, "price", old, "price"));
            if (price.signum() <= 0 && !applyDetailId.isBlank()) {
                // 原行和请求都没有单价时，回到退货单行的单价，绝不落 0
                price = applyPriceByLine.getOrDefault(applyDetailId, BigDecimal.ZERO);
            }
            BigDecimal costPrice = toBd(inherit(line, "costPrice", old, "cost_price"));
            String warehouse = str(inherit(line, "warehouse", old, "warehouse"));
            if (warehouse.isBlank()) warehouse = applyWarehouse;
            String newDetailId = "SRID" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO sales_return_inbound_detail(detail_id, inbound_id, goods_code, goods_name, spec, unit_name,
                        qty, price, amount, batch_no, production_date,
                        return_mode, source_outbound_no, source_detail_id,
                        apply_detail_id, cost_price, cost_amount, warehouse)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, newDetailId, inboundId,
                    str(inherit(line, "goodsCode", old, "goods_code")),
                    str(inherit(line, "goodsName", old, "goods_name")),
                    str(inherit(line, "spec", old, "spec")),
                    str(inherit(line, "unitName", old, "unit_name")),
                    q, price, q.multiply(price).setScale(2, RoundingMode.HALF_UP),
                    str(inherit(line, "batchNo", old, "batch_no")),
                    parseDate(inherit(line, "productionDate", old, "production_date"), null),
                    strOrDefault(inherit(line, "returnMode", old, "return_mode"), "BY_BILL"),
                    emptyToNull(inherit(line, "sourceOutboundNo", old, "source_outbound_no")),
                    emptyToNull(inherit(line, "sourceDetailId", old, "source_detail_id")),
                    emptyToNull(applyDetailId),
                    costPrice,
                    q.multiply(costPrice).setScale(2, RoundingMode.HALF_UP),
                    emptyToNull(warehouse));
        }

        recalcInboundHead(inboundId);
        log("sales.return.inbound", "UPDATE", str(pick(ib, "inbound_no")), "修改退货入库明细");
        return ApiResponse.ok(Map.of("inboundId", inboundId, "effect", "已更新"));
    }

    /**
     * 审核退货入库单：
     * 1. 按当前库存成本单价计价回库
     * 2. 回写入库数量到销售退货单
     */
    @PostMapping("/return-inbound/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditInbound(@Valid @RequestBody AuditRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_inbound WHERE inbound_id = ? OR inbound_no = ?",
                request.bizId(), request.bizId());
        if (rows.isEmpty()) throw new IllegalArgumentException("退货入库单不存在：" + request.bizId());
        Map<String, Object> ib = rows.get(0);
        if (!"PENDING".equals(str(pick(ib, "status")))) throw new IllegalArgumentException("退货入库单已审核");
        String inboundId = str(pick(ib, "inbound_id"));
        String inboundNo = str(pick(ib, "inbound_no"));
        String applyNo = str(pick(ib, "source_apply_no"));

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_inbound_detail WHERE inbound_id = ?", inboundId);

        // 逐行校验
        Map<String, BigDecimal> qtyByApplyLine = new LinkedHashMap<>();
        for (Map<String, Object> d : details) {
            BigDecimal q = toBd(pick(d, "qty"));
            String adId = str(pick(d, "apply_detail_id"));
            if (!adId.isBlank()) {
                qtyByApplyLine.merge(adId, q, BigDecimal::add);
            }
        }
        for (Map.Entry<String, BigDecimal> e : qtyByApplyLine.entrySet()) {
            List<Map<String, Object>> adRows = jdbcTemplate.queryForList(
                    "SELECT goods_name, goods_code, qty FROM sales_return_apply_detail WHERE detail_id = ?", e.getKey());
            if (adRows.isEmpty()) continue;
            BigDecimal applyQty = toBd(pick(adRows.get(0), "qty"));
            if (e.getValue().compareTo(applyQty) > 0) {
                throw new IllegalArgumentException("商品 "
                        + strOrDefault(pick(adRows.get(0), "goods_name"), str(pick(adRows.get(0), "goods_code")))
                        + " 总入库数量 " + plain(e.getValue()) + " 超过退货数量 " + plain(applyQty) + "，无法审核");
            }
        }

        BigDecimal totalCostAmount = BigDecimal.ZERO;
        // 按商品汇总入库数量，用于回写退货单
        Map<String, BigDecimal> inboundQtyByGoods = new LinkedHashMap<>();
        // 按退货单明细行汇总入库数量/金额，用于回写行 inbound_qty 与表头已入库金额
        Map<String, BigDecimal> inboundQtyByApplyLine = new LinkedHashMap<>();
        BigDecimal totalInboundAmount = BigDecimal.ZERO;

        for (Map<String, Object> d : details) {
            String goodsCode = str(pick(d, "goods_code"));
            String goodsName = str(pick(d, "goods_name"));
            String batchNo = str(pick(d, "batch_no"));
            BigDecimal qty = toBd(pick(d, "qty"));
            String warehouse = str(pick(d, "warehouse"));

            // qty = 0 的零入库行跳过
            if (qty.signum() <= 0) continue;

            if (warehouse.isBlank()) {
                throw new IllegalArgumentException("商品 " + goodsName + " 未指定入库仓库，无法审核");
            }
            if (batchNo.isBlank()) {
                throw new IllegalArgumentException("商品 " + goodsName + " 未指定批次号，无法审核");
            }

            // 退货入库成本 = 当前库存成本单价
            BigDecimal costPrice = inventoryCostService.getCurrentCostPrice(goodsCode, warehouse);
            BigDecimal costAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
            totalCostAmount = totalCostAmount.add(costAmount);

            // 回库 + 写流水
            inventoryCostService.purchaseInbound(goodsCode, goodsName, warehouse, batchNo, qty, costPrice, inboundNo);

            jdbcTemplate.update("""
                    UPDATE sales_return_inbound_detail SET cost_price=?, cost_amount=?
                    WHERE detail_id=?
                    """, costPrice, costAmount, str(pick(d, "detail_id")));

            // 汇总入库数量（按商品）
            inboundQtyByGoods.merge(goodsCode, qty, BigDecimal::add);

            // 汇总入库数量/金额（按退货单明细行），已入库金额按入库单行售价算
            String adId = str(pick(d, "apply_detail_id"));
            if (!adId.isBlank()) {
                inboundQtyByApplyLine.merge(adId, qty, BigDecimal::add);
            }
            totalInboundAmount = totalInboundAmount.add(
                    qty.multiply(toBd(pick(d, "price"))).setScale(2, RoundingMode.HALF_UP));
        }

        jdbcTemplate.update("""
                UPDATE sales_return_inbound SET status='APPROVED', stock_updated=TRUE,
                    cost_amount=?, audit_user=?, audit_time=CURRENT_TIMESTAMP
                WHERE inbound_id=?
                """, totalCostAmount, "系统管理员", inboundId);

        // 回写退货单：已入库数量/金额 + 流转状态 → 已入库
        String applyEffect = "";
        if (!applyNo.isBlank()) {
            applyEffect = writeBackInboundToApply(applyNo, inboundQtyByApplyLine, inboundQtyByGoods, totalInboundAmount);
        }

        log("sales.return.inbound", "AUDIT", inboundNo,
                "退货入库审核 → 回库，成本 " + totalCostAmount + applyEffect);
        return ApiResponse.ok(Map.of(
                "inboundId", inboundId, "inboundNo", inboundNo, "status", "APPROVED",
                "costAmount", totalCostAmount,
                "effect", "库存已回库，成本已计价，入库数量已回写退货单" + applyEffect));
    }

    /**
     * 入库单审核后回写退货单（仓库收货结果）。
     * <p>
     * 两种退货方式的差异在这里体现：
     * <ul>
     *   <li>自提到仓：没有司机签收环节，仓库实收即退货事实 → 退货数量/金额 = 入库数量/金额</li>
     *   <li>司机回收：退货数量/金额已由司机签收定死，仓库少收<b>不</b>回调退货金额与应收，
     *       差异保留在「签收数 vs 已入库数」上供稽核（已确认的业务口径）</li>
     * </ul>
     *
     * @param inboundQtyByApplyLine 退货单明细行 detail_id → 本次入库数量
     * @param inboundQtyByGoods     商品编码 → 本次入库数量（无行关联时的兜底汇总）
     * @param totalInboundAmount    本次入库金额（按入库单行售价）
     * @return 追加到日志/响应的效果描述
     */
    private String writeBackInboundToApply(String applyNo,
                                           Map<String, BigDecimal> inboundQtyByApplyLine,
                                           Map<String, BigDecimal> inboundQtyByGoods,
                                           BigDecimal totalInboundAmount) {
        List<Map<String, Object>> applyRows = jdbcTemplate.queryForList(
                "SELECT apply_id, return_type, qty, signed_qty FROM sales_return_apply WHERE apply_no = ?", applyNo);
        if (applyRows.isEmpty()) return "";
        String applyId = str(pick(applyRows.get(0), "apply_id"));
        String returnType = normalizeReturnType(pick(applyRows.get(0), "return_type"));

        // 逐行回写实收数量
        for (Map.Entry<String, BigDecimal> e : inboundQtyByApplyLine.entrySet()) {
            jdbcTemplate.update(
                    "UPDATE sales_return_apply_detail SET inbound_qty = ? WHERE detail_id = ?",
                    e.getValue(), e.getKey());
        }

        BigDecimal totalInboundQty = BigDecimal.ZERO;
        for (BigDecimal q : inboundQtyByGoods.values()) {
            totalInboundQty = totalInboundQty.add(q);
        }

        if (RETURN_TYPE_WAREHOUSE.equals(returnType)) {
            // 自提到仓：仓库实收即退货事实，退货数量/金额随入库结果走
            jdbcTemplate.update("""
                    UPDATE sales_return_apply
                    SET inbound_qty=?, inbound_amount=?, return_qty=?, return_amount=?, logistics_status=?
                    WHERE apply_id=?
                    """, totalInboundQty, totalInboundAmount, totalInboundQty, totalInboundAmount,
                    LOGISTICS_INBOUNDED, applyId);
            return "，退货单已入库 " + plain(totalInboundQty) + " 件（退货数量已同步为入库数量）";
        }

        // 司机回收：退货数量/金额按签收数定死，只记已入库数量/金额
        jdbcTemplate.update("""
                UPDATE sales_return_apply
                SET inbound_qty=?, inbound_amount=?, logistics_status=?
                WHERE apply_id=?
                """, totalInboundQty, totalInboundAmount, LOGISTICS_INBOUNDED, applyId);

        BigDecimal signedQty = toBd(pick(applyRows.get(0), "signed_qty"));
        String diff = signedQty.compareTo(totalInboundQty) > 0
                ? "，少收 " + plain(signedQty.subtract(totalInboundQty)) + " 件（差异不调整退货金额与应收）"
                : "";
        return "，退货单已入库 " + plain(totalInboundQty) + " 件" + diff;
    }

    // ========================================================================
    //  内部：自动生成入库单（幂等）
    // ========================================================================

    /**
     * 由退货单生成退货入库单（幂等：已存在则返回原单号）。
     * <p>
     * 数量取哪一列由 {@code bySignedQty} 决定：
     * <ul>
     *   <li>{@code false}（自提到仓，推送仓库）：取申请数量 {@code qty}</li>
     *   <li>{@code true}（司机回收，签收后）：取按行回收数量 {@code signed_qty}，
     *       并跳过回收数量为 0 的行 —— 司机没收的货不该给仓库下收货指令</li>
     * </ul>
     *
     * @param bySignedQty 是否按司机回收数量生成
     * @return 退货入库单号；司机回收且全单 0 回收时返回空串（不生成单据）
     */
    @Transactional
    public String generateInboundFromApply(String applyId, boolean bySignedQty) {
        List<Map<String, Object>> applyRows = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_apply WHERE apply_id = ?", applyId);
        if (applyRows.isEmpty()) throw new IllegalArgumentException("销售退货单不存在：" + applyId);
        Map<String, Object> apply = applyRows.get(0);
        String applyNo = str(pick(apply, "apply_no"));

        List<String> existing = jdbcTemplate.queryForList(
                "SELECT inbound_no FROM sales_return_inbound WHERE source_apply_no = ?",
                String.class, applyNo);
        if (!existing.isEmpty()) {
            jdbcTemplate.update("UPDATE sales_return_apply SET inbound_generated=TRUE WHERE apply_id=?", applyId);
            return existing.get(0);
        }

        String customerCode = str(pick(apply, "customer_code"));
        String customerName = str(pick(apply, "customer_name"));
        String warehouse = str(pick(apply, "warehouse"));
        LocalDate billDate = parseDate(pick(apply, "bill_date"), LocalDate.now());
        String remark = str(pick(apply, "remark"));

        List<Map<String, Object>> applyDetails = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_apply_detail WHERE apply_id = ?", applyId);

        // 先筛出有效行（按回收数量生成时跳过 0 回收行），再汇总表头
        List<Map<String, Object>> effectiveLines = new ArrayList<>();
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> ad : applyDetails) {
            BigDecimal q = bySignedQty ? toBd(pick(ad, "signed_qty")) : toBd(pick(ad, "qty"));
            if (bySignedQty && q.signum() <= 0) continue;
            BigDecimal p = toBd(pick(ad, "price"));
            ad.put("_inboundQty", q);
            effectiveLines.add(ad);
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(q.multiply(p).setScale(2, RoundingMode.HALF_UP));
        }
        // 司机整单 0 回收：没有货要入库，不生成空单据
        if (effectiveLines.isEmpty()) return "";

        String inboundId = "SRI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String inboundNo = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.SALES_RETURN_IN, "sales_return_inbound", "inbound_no");

        jdbcTemplate.update("""
                INSERT INTO sales_return_inbound(inbound_id, inbound_no, source_apply_no,
                    customer_code, customer_name, warehouse, bill_date, qty, amount, status, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, inboundId, inboundNo, applyNo,
                customerCode, customerName, warehouse, billDate, totalQty, totalAmount, remark);

        for (Map<String, Object> ad : effectiveLines) {
            BigDecimal q = toBd(ad.get("_inboundQty"));
            BigDecimal p = toBd(pick(ad, "price"));
            BigDecimal amount = q.multiply(p).setScale(2, RoundingMode.HALF_UP);
            String detailId = "SRID" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            BigDecimal costPrice = toBd(pick(ad, "cost_price"));
            jdbcTemplate.update("""
                    INSERT INTO sales_return_inbound_detail(detail_id, inbound_id, goods_code, goods_name, spec, unit_name,
                        qty, price, amount, warehouse, batch_no, production_date,
                        return_mode, source_outbound_no, source_detail_id,
                        apply_detail_id, cost_price, cost_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, detailId, inboundId,
                    str(pick(ad, "goods_code")), str(pick(ad, "goods_name")), str(pick(ad, "spec")),
                    str(pick(ad, "unit_name")),
                    q, p, amount,
                    // 行级入库仓库默认取退货单表头仓库（V29 起支持不同商品入不同仓）；
                    // 不预填的话审核必报「未指定入库仓库」，逼着每张单都先进抽屉点一遍
                    emptyToNull(warehouse),
                    null, null,
                    strOrDefault(pick(ad, "return_mode"), "BY_BILL"),
                    emptyToNull(pick(ad, "source_outbound_no")),
                    emptyToNull(pick(ad, "source_detail_id")),
                    str(pick(ad, "detail_id")),
                    costPrice,
                    q.multiply(costPrice).setScale(2, RoundingMode.HALF_UP));
        }

        jdbcTemplate.update("UPDATE sales_return_apply SET inbound_generated=TRUE WHERE apply_id=?", applyId);
        log("sales.return.inbound", "GENERATE", inboundNo, "销售退货单 " + applyNo + " 生成退货入库单（数量口径："
                + (bySignedQty ? "司机回收数量" : "申请数量") + "）");
        return inboundNo;
    }

    // ========================================================================
    //  司机回收联动（供 TMS 司机端签收调用）
    // ========================================================================

    /**
     * 司机确认回收后的退货单联动。
     * <p>
     * 由 {@code TmsAppController.returnSign} 在校验完「单据属于当前司机、已调度」之后调用，
     * 把签收结果落到退货单上并往下推流程：
     * <ol>
     *   <li>按行写 {@code signed_qty}（items 为空视为全收，按申请数量签收）</li>
     *   <li>重算表头签收数量 / 退货数量 / 退货金额（申请金额 {@code amount} 保持不动，差异留痕）</li>
     *   <li>{@code logistics_status} → 司机已回收</li>
     *   <li>按回收数量生成退货入库单（整单 0 回收时不生成空单据）</li>
     *   <li>入账时点 = 按司机回收入账时，直接写负向应收并把单据置为已审核</li>
     * </ol>
     * 司机在途的所有权、调度归属校验留在调用方，这里只管业务口径校验，避免两处重复判断。
     *
     * @param items    形如 [{detailId, signedQty}]；为空/null 表示整单全收
     * @param operator 操作人（司机姓名），写入日志与自动审核的 audit_user
     * @return 联动结果：signedQty / returnAmount / inboundNo / autoAudited / effect
     */
    @Transactional
    public Map<String, Object> onDriverCollected(String applyId, List<Map<String, Object>> items, String operator) {
        Map<String, Object> order = findApplyById(applyId);
        String realApplyId = str(pick(order, "apply_id"));
        String applyNo = str(pick(order, "apply_no"));
        String returnType = normalizeReturnType(pick(order, "return_type"));
        if (!RETURN_TYPE_DRIVER.equals(returnType)) {
            throw new IllegalArgumentException("退货单 " + applyNo + " 的退货方式是自提到仓，不走司机回收签收");
        }
        String status = str(pick(order, "status"));
        if (!"CONFIRMED".equals(status)) {
            throw new IllegalArgumentException("仅已确认的退货单可签收，当前状态：" + resolveReturnOrderStatusText(status));
        }

        // 申请数量按行索引，用于校验签收数量上限；顺带作为「全收」的默认值
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT detail_id, goods_name, qty FROM sales_return_apply_detail WHERE apply_id = ?", realApplyId);
        if (details.isEmpty()) throw new IllegalArgumentException("退货单 " + applyNo + " 没有明细，无法签收");
        Map<String, BigDecimal> applyQty = new LinkedHashMap<>();
        Map<String, String> goodsNames = new LinkedHashMap<>();
        for (Map<String, Object> d : details) {
            String did = str(pick(d, "detail_id"));
            applyQty.put(did, toBd(pick(d, "qty")));
            goodsNames.put(did, str(pick(d, "goods_name")));
        }

        Map<String, BigDecimal> signed = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            // 未传明细 = 全收，兼容旧版司机端只回传整单签收的调用
            signed.putAll(applyQty);
        } else {
            for (Map<String, Object> it : items) {
                String did = str(it.get("detailId"));
                if (did.isEmpty()) throw new IllegalArgumentException("签收明细缺少 detailId");
                if (!applyQty.containsKey(did)) {
                    throw new IllegalArgumentException("签收明细 " + did + " 不属于退货单 " + applyNo);
                }
                BigDecimal q = toBd(it.get("signedQty"));
                if (q.signum() < 0) {
                    throw new IllegalArgumentException(goodsNames.get(did) + " 的回收数量不能为负数");
                }
                if (q.compareTo(applyQty.get(did)) > 0) {
                    throw new IllegalArgumentException(goodsNames.get(did) + " 的回收数量 " + plain(q)
                            + " 超过申请数量 " + plain(applyQty.get(did)));
                }
                // 同一行重复出现时取最后一次，与前端「最后一次编辑生效」的直觉一致
                signed.put(did, q);
            }
        }

        // 未出现在 items 里的行按 0 回收写死，避免残留上一次签收的数量
        for (String did : applyQty.keySet()) {
            BigDecimal q = signed.getOrDefault(did, BigDecimal.ZERO);
            jdbcTemplate.update("UPDATE sales_return_apply_detail SET signed_qty = ? WHERE detail_id = ?", q, did);
        }

        BigDecimal[] recalced = recalcApplyAmountFromSigned(realApplyId);
        BigDecimal totalSignedQty = recalced[0];
        BigDecimal returnAmount = recalced[1];

        jdbcTemplate.update("UPDATE sales_return_apply SET logistics_status = ? WHERE apply_id = ?",
                LOGISTICS_DRIVER_COLLECTED, realApplyId);

        String inboundNo = generateInboundFromApply(realApplyId, true);

        String arTiming = loadArTiming();
        boolean autoAudited = false;
        String arEffect = "";
        if (AR_TIMING_DRIVER.equals(arTiming) && totalSignedQty.signum() > 0) {
            arEffect = writeReturnAr(realApplyId, applyNo, "系统自动（司机回收入账）");
            autoAudited = true;
        }

        StringBuilder detailLog = new StringBuilder("司机 ").append(operator)
                .append(" 确认回收：回收数量 ").append(plain(totalSignedQty))
                .append("，退货金额 ").append(plain(returnAmount));
        if (inboundNo.isEmpty()) {
            detailLog.append("，整单 0 回收未生成入库单");
        } else {
            detailLog.append("，生成退货入库单 ").append(inboundNo);
        }
        detailLog.append(autoAudited ? "，" + arEffect : "，入账时点为按仓库收货入账，待仓库收货后审核");
        log("sales.return.order", "DRIVER_COLLECT", applyNo, detailLog.toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applyId", realApplyId);
        result.put("applyNo", applyNo);
        result.put("signedQty", totalSignedQty);
        result.put("returnAmount", returnAmount);
        result.put("inboundNo", inboundNo);
        result.put("autoAudited", autoAudited);
        result.put("effect", detailLog.toString());
        return result;
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    private Map<String, Object> findApplyById(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_apply WHERE apply_id = ? OR apply_no = ?", id, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("销售退货单不存在：" + id);
        return rows.get(0);
    }

    private void validateDetails(List<Map<String, Object>> details, String excludeApplyId) {
        for (Map<String, Object> line : details) {
            String goodsCode = str(line.get("goodsCode"));
            String goodsName = strOrDefault(line.get("goodsName"), goodsCode);
            BigDecimal qty = toBd(line.get("qty"));
            if (qty.signum() <= 0) {
                throw new IllegalArgumentException("商品 " + goodsName + " 的退货数量必须大于 0");
            }
            String mode = strOrDefault(line.get("returnMode"), "BY_BILL");

            if ("BY_BILL".equals(mode)) {
                String sourceDetailId = str(line.get("sourceDetailId"));
                if (sourceDetailId.isBlank()) {
                    throw new IllegalArgumentException("商品 " + goodsName + " 是按单退货，但缺少源单行信息");
                }
                List<Map<String, Object>> srcRows = jdbcTemplate.queryForList(
                        "SELECT qty FROM sales_outbound_detail WHERE detail_id = ?", sourceDetailId);
                if (srcRows.isEmpty()) {
                    throw new IllegalArgumentException("商品 " + goodsName + " 的源单行不存在：" + sourceDetailId);
                }
                BigDecimal outboundQty = toBd(pick(srcRows.get(0), "qty"));
                BigDecimal occupied = toBd(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(rd.qty), 0)
                        FROM sales_return_apply_detail rd
                        JOIN sales_return_apply rh ON rd.apply_id = rh.apply_id
                        WHERE rd.source_detail_id = ? AND rh.status NOT IN ('CANCELLED', 'REJECTED')
                          AND (? IS NULL OR rd.apply_id <> ?)
                        """, BigDecimal.class, sourceDetailId, excludeApplyId, excludeApplyId));
                BigDecimal returnable = outboundQty.subtract(occupied);
                if (returnable.signum() < 0) returnable = BigDecimal.ZERO;
                if (qty.compareTo(returnable) > 0) {
                    throw new IllegalArgumentException(
                            "商品 " + goodsName + " 退货数量 " + plain(qty)
                                    + " 超过可退数量 " + plain(returnable));
                }
            }
            // 按品退货（BY_GOODS）不校验库存：销售退货是入库类单据，退回的货是客户手里的，
            // 与本仓当前库存无关。仓库当前可用库存仅作参考展示，不作为数量上限。

            // 同商品不允许重复
            boolean dup = false;
            for (Map<String, Object> other : details) {
                if (other == line) continue;
                if (goodsCode.equals(str(other.get("goodsCode")))) { dup = true; break; }
            }
            if (dup) {
                throw new IllegalArgumentException("商品 " + goodsName + " 已在退货明细中，请勿重复添加");
            }
        }
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private void insertApplyDetail(String applyId, Map<String, Object> line) {
        BigDecimal q = toBd(line.get("qty"));
        BigDecimal p = toBd(line.get("price"));
        BigDecimal a = line.get("_amount") instanceof BigDecimal bd
                ? bd : q.multiply(p).setScale(2, RoundingMode.HALF_UP);
        String detailId = "SRAD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO sales_return_apply_detail(detail_id, apply_id, goods_code, goods_name, spec, unit_name,
                    qty, price, amount, tax_rate, remark,
                    return_mode, source_outbound_no, source_detail_id,
                    returnable_qty, cost_price, available_stock)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
                """, detailId, applyId,
                str(line.get("goodsCode")), str(line.get("goodsName")), str(line.get("spec")),
                str(line.get("unitName")),
                q, p, a,
                strOrDefault(line.get("taxRate"), "13%"),
                strOrDefault(line.get("returnMode"), "BY_BILL"),
                emptyToNull(line.get("sourceOutboundNo")),
                emptyToNull(line.get("sourceDetailId")),
                toBd(line.get("returnableQty")),
                toBd(line.get("costPrice")),
                toBd(line.get("availableStock")));
    }

    private static String emptyToNull(Object o) {
        String s = str(o);
        return s.isBlank() ? null : s;
    }

    /**
     * 请求行字段继承原明细行的值：请求里显式给了非空值就用请求的，否则沿用数据库原行。
     * 用于「删后插」式明细重建，避免调用方漏传字段（如 price）被静默清零。
     *
     * @param line     请求明细行（驼峰 key）
     * @param key      请求里的驼峰字段名
     * @param old      数据库原明细行，可为 null（新增行）
     * @param column   数据库列名（下划线）
     */
    private static Object inherit(Map<String, Object> line, String key, Map<String, Object> old, String column) {
        if (line != null && line.containsKey(key)) {
            Object v = line.get(key);
            if (v != null && !str(v).isBlank()) return v;
        }
        return old == null ? null : pick(old, column);
    }

    private void recalcInboundHead(String inboundId) {
        List<Map<String, Object>> sumRows = jdbcTemplate.queryForList(
                "SELECT COALESCE(SUM(qty), 0) AS total_qty, COALESCE(SUM(amount), 0) AS total_amount " +
                        "FROM sales_return_inbound_detail WHERE inbound_id = ?", inboundId);
        if (!sumRows.isEmpty()) {
            jdbcTemplate.update("UPDATE sales_return_inbound SET qty=?, amount=? WHERE inbound_id=?",
                    toBd(pick(sumRows.get(0), "total_qty")),
                    toBd(pick(sumRows.get(0), "total_amount")),
                    inboundId);
        }
    }

    private static String resolveReturnOrderStatusText(String st) {        return switch (st) {
            case "DRAFT" -> "草稿";
            case "PENDING" -> "待确认";
            case "CONFIRMED" -> "已确认";
            case "APPROVED" -> "已审核";
            case "REJECTED" -> "已驳回";
            default -> st;
        };
    }

    /**
     * 归一化退货方式。
     * <p>
     * 空值一律按「自提到仓」兜底：V51 加列时默认值就是 WAREHOUSE，历史单据全是自提口径，
     * 误判成司机回收会让它们凭空出现在配送任务池里。非法值直接拒，避免脏数据流进流程判断。
     */
    public static String normalizeReturnType(Object raw) {
        String s = str(raw).trim();
        if (s.isEmpty()) return RETURN_TYPE_WAREHOUSE;
        String upper = s.toUpperCase();
        if (RETURN_TYPE_DRIVER.equals(upper) || "司机回收".equals(s)) return RETURN_TYPE_DRIVER;
        if (RETURN_TYPE_WAREHOUSE.equals(upper) || "自提到仓".equals(s)) return RETURN_TYPE_WAREHOUSE;
        throw new IllegalArgumentException("退货方式非法：" + s + "（只能是 DRIVER 司机回收 / WAREHOUSE 自提到仓）");
    }

    /** 退货方式显示文案。 */
    private static String resolveReturnTypeText(String returnType) {
        return RETURN_TYPE_DRIVER.equals(returnType) ? "司机回收" : "自提到仓";
    }

    /**
     * 流转状态显示文案。
     * <p>
     * 两种退货方式共用 {@code logistics_status} 列，同一个「未安排」在两条链路上下一步动作不同，
     * 所以文案要带上方式语义，前端才能直接展示而不用自己再判断一次。
     */
    private static String resolveLogisticsStatusText(String returnType, String logisticsStatus) {
        String ls = logisticsStatus == null || logisticsStatus.isBlank() ? LOGISTICS_UNARRANGED : logisticsStatus;
        if (LOGISTICS_UNARRANGED.equals(ls)) {
            return RETURN_TYPE_DRIVER.equals(returnType) ? "待安排调度" : "待推送仓库";
        }
        return ls;
    }

    /**
     * 读取销售退货入账时点参数。
     * <p>
     * 参数表异常或值非法一律回退「按仓库收货入账」：这是更保守的口径（等货真回库才动账），
     * 配置读取绝不能成为审核动作报错的原因。
     */
    private String loadArTiming() {
        String v = null;
        try {
            List<String> rows = jdbcTemplate.queryForList("""
                    SELECT COALESCE(param_value, default_value)
                      FROM sys_param_runtime
                     WHERE param_key = 'SALES_RETURN_AR_TIMING'
                    """, String.class);
            if (!rows.isEmpty()) v = rows.get(0);
        } catch (Exception ignore) {
            // 参数表不可用时走默认值
        }
        return AR_TIMING_DRIVER.equalsIgnoreCase(str(v).trim()) ? AR_TIMING_DRIVER : AR_TIMING_WAREHOUSE;
    }

    /**
     * 明细行的实际退货金额：有签收数量时按签收数量算，否则按申请数量算。
     * <p>
     * 自提到仓链路 {@code signed_qty} 恒为 0，天然落到申请数量口径。
     */
    private static BigDecimal effectiveLineAmount(Map<String, Object> detail) {
        BigDecimal qty = toBd(pick(detail, "signed_qty"));
        if (qty.signum() <= 0) qty = toBd(pick(detail, "qty"));
        return qty.multiply(toBd(pick(detail, "price"))).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 按明细 {@code signed_qty} 重算表头签收数量 / 退货数量 / 退货金额。
     * <p>
     * 司机签收后调用：退货数量与退货金额从此以司机实收为准，而 {@code amount}（申请金额）保持不动，
     * 差异留痕便于事后对账。
     *
     * @return 重算出的 [签收数量, 退货金额]
     */
    private BigDecimal[] recalcApplyAmountFromSigned(String applyId) {
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_apply_detail WHERE apply_id = ?", applyId);
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            totalQty = totalQty.add(toBd(pick(d, "signed_qty")));
            totalAmount = totalAmount.add(effectiveLineAmount(d));
        }
        // 全单 0 回收时 effectiveLineAmount 会回退到申请数量，这里显式清零，避免写出虚假退货金额
        if (totalQty.signum() <= 0) totalAmount = BigDecimal.ZERO;
        jdbcTemplate.update("""
                UPDATE sales_return_apply SET signed_qty = ?, return_qty = ?, return_amount = ?
                WHERE apply_id = ?
                """, totalQty, totalQty, totalAmount, applyId);
        return new BigDecimal[]{totalQty, totalAmount};
    }

    private static BigDecimal parseTaxRate(String taxRate) {
        if (taxRate == null || taxRate.isBlank()) return new BigDecimal("0.13");
        String s = taxRate.trim();
        boolean isPercent = s.endsWith("%");
        if (isPercent) s = s.substring(0, s.length() - 1).trim();
        BigDecimal v;
        try { v = new BigDecimal(s); } catch (Exception e) { return new BigDecimal("0.13"); }
        if (isPercent || v.compareTo(BigDecimal.ONE) > 0) {
            v = v.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        }
        return v;
    }

    private static BigDecimal taxInclusiveTax(BigDecimal taxIncludedAmount, BigDecimal taxRate) {
        if (taxIncludedAmount == null || taxIncludedAmount.signum() == 0) return BigDecimal.ZERO;
        if (taxRate == null || taxRate.signum() == 0) return BigDecimal.ZERO;
        return taxIncludedAmount
                .multiply(taxRate)
                .divide(BigDecimal.ONE.add(taxRate), 2, RoundingMode.HALF_UP);
    }

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                moduleCode, action, bizNo, detail);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String strOrDefault(Object o, String dft) {
        if (o == null) return dft;
        String s = String.valueOf(o);
        return s.isBlank() ? dft : s;
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(String.valueOf(o)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static LocalDate parseDate(Object o, LocalDate dft) {
        if (o == null) return dft;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date sd) return sd.toLocalDate();
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return dft;
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return dft; }
    }

    private static Object pick(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        return row.get(key.toUpperCase(Locale.ROOT));
    }

    private static Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : k.toCharArray()) {
                if (c == '_') { upper = true; continue; }
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            out.put(sb.toString(), e.getValue());
        }
        return out;
    }

    public record AuditRequest(@NotBlank String bizId, String remark) {}
}
