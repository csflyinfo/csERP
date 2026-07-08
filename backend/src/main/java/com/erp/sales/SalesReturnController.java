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
 * 销售退货全流程 REST 端点（V2 简化版）。
 * <p>
 * 链路（两模块）：
 * <ol>
 *   <li>销售退货单（sales_return_apply）— 创建→待确认→确认退货/驳回→审核→生成退货入库单+写应收</li>
 *   <li>销售退货入库单（sales_return_inbound）— 审核后回库存，按成本计价，回写入库数量</li>
 * </ol>
 * <p>
 * 全部使用 JdbcTemplate 直写，DTO 用 {@code Map<String, Object>}。
 */
@RestController
@RequestMapping("/sales")
public class SalesReturnController {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

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
                       qty, return_qty, inbound_qty, amount, return_reason, status, inbound_generated,
                       creator_name, confirmed_user, confirmed_time, audit_user, audit_time, create_time, remark
                FROM sales_return_apply
                ORDER BY create_time DESC, apply_no DESC
                """);
        List<Map<String, Object>> mapped = rows.stream().map(r -> {
            Map<String, Object> row = camelize(r);
            row.put("statusText", resolveReturnOrderStatusText(str(pick(r, "status"))));
            // 退货金额 = 退货数量 * 单价（由前端计算），这里回送退货数量
            row.put("returnQty", toBd(pick(r, "return_qty")));
            row.put("inboundQty", toBd(pick(r, "inbound_qty")));
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

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        if (reqDetails.isEmpty()) throw new IllegalArgumentException("退货明细不能为空");

        validateDetails(reqDetails, warehouse, null);

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
                    qty, return_qty, amount, return_reason, status, creator_name, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '系统管理员', ?)
                """, id, no, headSourceNo.isBlank() ? null : headSourceNo,
                str(request.get("customerCode")), customer, warehouse, billDate,
                totalQty, totalQty, totalAmount, returnReason, status, remark);

        for (Map<String, Object> line : reqDetails) {
            insertApplyDetail(id, line);
        }

        log("sales.return.order", "CREATE", no, "创建销售退货单（待确认）");
        return ApiResponse.ok(Map.of("applyId", id, "applyNo", no, "status", status));
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

        validateDetails(reqDetails, warehouse, applyId);

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
                    warehouse=?, bill_date=?, qty=?, return_qty=?, amount=?, return_reason=?, remark=?
                WHERE apply_id=?
                """, headSourceNo.isBlank() ? null : headSourceNo,
                strOrDefault(request.get("customerCode"), str(pick(existing, "customer_code"))),
                customer, warehouse, billDate, totalQty, totalReturnQty, totalAmount,
                returnReason, remark, applyId);

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
     * 审核销售退货单：CONFIRMED → APPROVED。
     * 1. 自动生成退货入库单（幂等）
     * 2. 写负向 fin_ar 冲减应收账款
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

        // 1. 生成退货入库单（幂等）
        String inboundNo = generateInboundFromApply(applyId);

        // 2. 写负向应收
        String customer = str(pick(order, "customer_name"));
        BigDecimal returnAmount = toBd(pick(order, "amount"));

        // 计算税额
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM sales_return_apply_detail WHERE apply_id = ?", applyId);
        BigDecimal totalTaxAmount = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            String taxRate = str(pick(d, "tax_rate"));
            BigDecimal lineAmount = toBd(pick(d, "amount"));
            BigDecimal lineTax = taxInclusiveTax(lineAmount, parseTaxRate(taxRate));
            totalTaxAmount = totalTaxAmount.add(lineTax);
        }
        BigDecimal finalAmount = returnAmount.subtract(totalTaxAmount);

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
                """, "系统管理员", applyId);

        log("sales.return.order", "AUDIT", applyNo,
                "销售退货单审核 → 自动生成退货入库单 " + inboundNo + "，写入负向应收");
        return ApiResponse.ok(Map.of("applyId", applyId, "applyNo", applyNo, "status", "APPROVED",
                "inboundNo", inboundNo, "arNo", arNo,
                "effect", "已审核，自动生成退货入库单 " + inboundNo));
    }

    /** 反审核：仅当入库单未审核时允许。APPROVED → CONFIRMED。 */
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

        // 检查入库单是否已审核
        List<Map<String, Object>> ibRows = jdbcTemplate.queryForList(
                "SELECT inbound_id, status FROM sales_return_inbound WHERE source_apply_no = ?", applyNo);
        for (Map<String, Object> ib : ibRows) {
            String ibStatus = str(pick(ib, "status"));
            if ("APPROVED".equals(ibStatus)) {
                throw new IllegalArgumentException("退货入库单已审核，无法反审核退货单");
            }
            // 删除未审核的入库单
            String ibId = str(pick(ib, "inbound_id"));
            jdbcTemplate.update("DELETE FROM sales_return_inbound_detail WHERE inbound_id = ?", ibId);
            jdbcTemplate.update("DELETE FROM sales_return_inbound WHERE inbound_id = ?", ibId);
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

        jdbcTemplate.update("UPDATE sales_return_apply SET status='CONFIRMED', inbound_generated=FALSE, " +
                "audit_user=NULL, audit_time=NULL WHERE apply_id=?", applyId);

        log("sales.return.order", "REVERSE_AUDIT", applyNo, "销售退货单反审核 → 恢复为已确认");
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
        for (Map<String, Object> ad : jdbcTemplate.queryForList(
                "SELECT detail_id, goods_code, goods_name, qty FROM sales_return_apply_detail WHERE apply_id IN " +
                        "(SELECT apply_id FROM sales_return_apply WHERE apply_no = ?)", applyNo)) {
            String adId = str(pick(ad, "detail_id"));
            applyQtyByLine.put(adId, toBd(pick(ad, "qty")));
            applyGoodsByLine.put(adId, strOrDefault(pick(ad, "goods_name"), str(pick(ad, "goods_code"))));
        }

        Map<String, BigDecimal> sumByApplyLine = new HashMap<>();
        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("qty"));
            if (q.signum() < 0) {
                throw new IllegalArgumentException("商品 " + str(line.get("goodsCode")) + " 的入库数量不能为负数");
            }
            String applyDetailId = str(line.get("applyDetailId"));
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
            BigDecimal q = toBd(line.get("qty"));
            BigDecimal price = toBd(line.get("price"));
            BigDecimal costPrice = toBd(line.get("costPrice"));
            String newDetailId = "SRID" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO sales_return_inbound_detail(detail_id, inbound_id, goods_code, goods_name, spec, unit_name,
                        qty, price, amount, batch_no, production_date,
                        return_mode, source_outbound_no, source_detail_id,
                        apply_detail_id, cost_price, cost_amount, warehouse)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, newDetailId, inboundId,
                    str(line.get("goodsCode")), str(line.get("goodsName")), str(line.get("spec")),
                    str(line.get("unitName")),
                    q, price, q.multiply(price).setScale(2, RoundingMode.HALF_UP),
                    str(line.get("batchNo")), parseDate(line.get("productionDate"), null),
                    strOrDefault(line.get("returnMode"), "BY_BILL"),
                    emptyToNull(line.get("sourceOutboundNo")),
                    emptyToNull(line.get("sourceDetailId")),
                    emptyToNull(line.get("applyDetailId")),
                    costPrice,
                    q.multiply(costPrice).setScale(2, RoundingMode.HALF_UP),
                    str(line.get("warehouse")));
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
        }

        jdbcTemplate.update("""
                UPDATE sales_return_inbound SET status='APPROVED', stock_updated=TRUE,
                    cost_amount=?, audit_user=?, audit_time=CURRENT_TIMESTAMP
                WHERE inbound_id=?
                """, totalCostAmount, "系统管理员", inboundId);

        // 回写入库数量到销售退货单
        if (!applyNo.isBlank()) {
            BigDecimal totalInboundQty = BigDecimal.ZERO;
            for (BigDecimal q : inboundQtyByGoods.values()) {
                totalInboundQty = totalInboundQty.add(q);
            }
            jdbcTemplate.update(
                    "UPDATE sales_return_apply SET inbound_qty = ? WHERE apply_no = ?",
                    totalInboundQty, applyNo);
        }

        log("sales.return.inbound", "AUDIT", inboundNo,
                "退货入库审核 → 回库，成本 " + totalCostAmount);
        return ApiResponse.ok(Map.of(
                "inboundId", inboundId, "inboundNo", inboundNo, "status", "APPROVED",
                "costAmount", totalCostAmount,
                "effect", "库存已回库，成本已计价，入库数量已回写退货单"));
    }

    // ========================================================================
    //  内部：自动生成入库单（幂等）
    // ========================================================================

    @Transactional
    public String generateInboundFromApply(String applyId) {
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

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> ad : applyDetails) {
            BigDecimal q = toBd(pick(ad, "qty"));
            BigDecimal p = toBd(pick(ad, "price"));
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(q.multiply(p).setScale(2, RoundingMode.HALF_UP));
        }

        String inboundId = "SRI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String inboundNo = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.SALES_RETURN_IN, "sales_return_inbound", "inbound_no");

        jdbcTemplate.update("""
                INSERT INTO sales_return_inbound(inbound_id, inbound_no, source_apply_no,
                    customer_code, customer_name, warehouse, bill_date, qty, amount, status, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, inboundId, inboundNo, applyNo,
                customerCode, customerName, warehouse, billDate, totalQty, totalAmount, remark);

        for (Map<String, Object> ad : applyDetails) {
            BigDecimal q = toBd(pick(ad, "qty"));
            BigDecimal p = toBd(pick(ad, "price"));
            BigDecimal amount = q.multiply(p).setScale(2, RoundingMode.HALF_UP);
            String detailId = "SRID" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            BigDecimal costPrice = toBd(pick(ad, "cost_price"));
            jdbcTemplate.update("""
                    INSERT INTO sales_return_inbound_detail(detail_id, inbound_id, goods_code, goods_name, spec, unit_name,
                        qty, price, amount, batch_no, production_date,
                        return_mode, source_outbound_no, source_detail_id,
                        apply_detail_id, cost_price, cost_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, detailId, inboundId,
                    str(pick(ad, "goods_code")), str(pick(ad, "goods_name")), str(pick(ad, "spec")),
                    str(pick(ad, "unit_name")),
                    q, p, amount,
                    null, null,
                    strOrDefault(pick(ad, "return_mode"), "BY_BILL"),
                    emptyToNull(pick(ad, "source_outbound_no")),
                    emptyToNull(pick(ad, "source_detail_id")),
                    str(pick(ad, "detail_id")),
                    costPrice,
                    q.multiply(costPrice).setScale(2, RoundingMode.HALF_UP));
        }

        jdbcTemplate.update("UPDATE sales_return_apply SET inbound_generated=TRUE WHERE apply_id=?", applyId);
        log("sales.return.inbound", "GENERATE", inboundNo, "销售退货单 " + applyNo + " 自动生成退货入库单");
        return inboundNo;
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

    private void validateDetails(List<Map<String, Object>> details, String warehouse, String excludeApplyId) {
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
            } else {
                BigDecimal available = toBd(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(available_qty), 0) FROM inv_stock_balance
                        WHERE goods_code = ? AND warehouse = ?
                        """, BigDecimal.class, goodsCode, warehouse));
                if (qty.compareTo(available) > 0) {
                    throw new IllegalArgumentException(
                            "商品 " + goodsName + " 退货数量 " + plain(qty)
                                    + " 超过可用库存 " + plain(available));
                }
            }

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

    private static String resolveReturnOrderStatusText(String st) {
        return switch (st) {
            case "DRAFT" -> "草稿";
            case "PENDING" -> "待确认";
            case "CONFIRMED" -> "已确认";
            case "APPROVED" -> "已审核";
            case "REJECTED" -> "已驳回";
            default -> st;
        };
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
