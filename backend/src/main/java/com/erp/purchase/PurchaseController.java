package com.erp.purchase;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.inventory.service.InventoryCostService;
import com.erp.purchase.entity.PurchaseInbound;
import com.erp.purchase.entity.PurchaseInboundDetail;
import com.erp.purchase.service.PurchaseInboundDetailService;
import com.erp.purchase.service.PurchaseInboundService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 采购入库单 / 采购收货单 / 采购退货 / 采购费用 / 采购发票 端点。
 * <p>
 * 采购订单端点在 {@link com.erp.sales.OrderController}（新 {@code purchase_order} 表）。
 * 老 {@code pur_order} 表 + {@code /purchase/order-legacy/*} 端点已于 Step A 全量删除。
 * <p>
 * Step B 起：
 * <ul>
 *   <li>{@code /purchase/inbound/create} 接受前端拆行明细（{@code details}）；缺省时按订单一商品一行自动生成。</li>
 *   <li>{@code /purchase/inbound/audit} 按 <em>累计入库金额 vs 订单金额</em> 计算 {@code inbound_status}
 *       （未/部分/已入库），并同步 {@code base_goods.latest_purchase_price}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/purchase")
public class PurchaseController {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbcTemplate;
    private final PurchaseInboundService inboundService;
    private final PurchaseInboundDetailService inboundDetailService;
    private final InventoryCostService inventoryCostService;
    private final PurchaseReceiptController receiptController;
    private final com.erp.common.util.BillNoGenerator billNoGen;

    public PurchaseController(JdbcTemplate jdbcTemplate,
                              PurchaseInboundService inboundService,
                              PurchaseInboundDetailService inboundDetailService,
                              InventoryCostService inventoryCostService,
                              PurchaseReceiptController receiptController,
                              com.erp.common.util.BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.inboundService = inboundService;
        this.inboundDetailService = inboundDetailService;
        this.inventoryCostService = inventoryCostService;
        this.receiptController = receiptController;
        this.billNoGen = billNoGen;
    }

    // ========== 采购入库 ==========
    /**
     * 采购入库单列表 —— 只返回<b>主单</b>信息。
     * <p>库位 / 批次号 / 生产日期 / 到期日期 / 应入数量 / 实收数量 / 入库前后成本 / 分摊费用
     * 都是 {@code pur_inbound_detail} 的明细字段，一张入库单可能有多行（多批次），
     * 在主单列表里展示没有意义（要么为空、要么只能取其中一行），点「查看」进详情看。
     * <p>派生字段：
     * <ul>
     *   <li>{@code statusText} —— PENDING/APPROVED 的中文（前端 STATUS_MAP 无 APPROVED，会显示英文原文）</li>
     *   <li>{@code inboundStatus} —— 入库状态：未入库 / 已入库（按 {@code stock_updated}，即库存是否已实际增加）</li>
     * </ul>
     */
    @PostMapping("/inbound/page")
    public ApiResponse<PageResult<Map<String, Object>>> inboundPage(@RequestBody PageRequest request) {
        var page = inboundService.page(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(request.safePageNo(), request.safePageSize()),
                new QueryWrapper<PurchaseInbound>().orderByDesc("inbound_no")
        );
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (PurchaseInbound in : page.getRecords()) {
            Map<String, Object> row = new HashMap<>();
            row.put("inboundId", in.getInboundId());
            row.put("inboundNo", in.getInboundNo());
            row.put("sourceOrder", in.getSourceOrder());
            row.put("supplier", in.getSupplier());
            row.put("warehouse", in.getWarehouse());
            row.put("billDate", in.getBillDate());
            row.put("qty", in.getQty());
            row.put("amount", in.getAmount());
            row.put("status", in.getStatus());
            row.put("stockUpdated", in.getStockUpdated());
            row.put("receiptGenerated", in.getReceiptGenerated());
            row.put("createdAt", in.getCreatedAt());
            row.put("statusText", "PENDING".equals(in.getStatus()) ? "待审核"
                    : "APPROVED".equals(in.getStatus()) ? "已审核" : in.getStatus());
            // 入库状态：库存是否已实际增加（审核时置 true）
            row.put("inboundStatus", Boolean.TRUE.equals(in.getStockUpdated()) ? "已入库" : "未入库");
            mapped.add(row);
        }
        return ApiResponse.ok(new PageResult<>(mapped, (int) page.getCurrent(), (int) page.getSize(), page.getTotal(), Map.of()));
    }

    @GetMapping("/inbound/detail")
    public ApiResponse<Map<String, Object>> inboundDetail(
            @RequestParam(required = false) String inboundId,
            @RequestParam(required = false) String id) {
        String key = inboundId != null && !inboundId.isBlank() ? inboundId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 inboundId / id");
        PurchaseInbound inbound = inboundService.getOne(
                new QueryWrapper<PurchaseInbound>().eq("inbound_id", key).or().eq("inbound_no", key)
        );
        if (inbound == null) return ApiResponse.ok(GenericResult.row("inboundId", key, "details", List.of()));
        List<PurchaseInboundDetail> details = inboundDetailService.list(
                new QueryWrapper<PurchaseInboundDetail>().eq("inbound_id", inbound.getInboundId()).orderByAsc("detail_id")
        );
        Map<String, Object> result = new HashMap<>();
        result.put("inboundId", inbound.getInboundId());
        result.put("inboundNo", inbound.getInboundNo());
        result.put("sourceOrder", inbound.getSourceOrder());
        result.put("supplier", inbound.getSupplier());
        result.put("warehouse", inbound.getWarehouse());
        result.put("billDate", inbound.getBillDate());
        result.put("qty", inbound.getQty());
        result.put("amount", inbound.getAmount());
        result.put("status", inbound.getStatus());
        result.put("details", details);
        return ApiResponse.ok(result);
    }

    /**
     * 按订单预填入库明细（供前端「引入采购订单」使用）。
     * <p>返回：{@code supplier / warehouse / details} —— 每条订单明细一行，价格只读，
     * 已入库数量 = 累计 SUM(pur_inbound_detail.received_qty) by goods_code。前端可以按需拆分批次。
     */
    @GetMapping("/inbound/from-order")
    public ApiResponse<Map<String, Object>> inboundFromOrder(@RequestParam String orderNo) {
        List<Map<String, Object>> orderRows = jdbcTemplate.queryForList(
                "SELECT order_id, order_no, supplier_code, supplier_name, warehouse, status, amount, inbound_amount " +
                        "FROM purchase_order WHERE order_no = ? OR order_id = ?",
                orderNo, orderNo);
        if (orderRows.isEmpty()) throw new IllegalArgumentException("采购订单不存在：" + orderNo);
        Map<String, Object> order = orderRows.get(0);
        String status = str(pick(order, "status"));
        if (!"APPROVED".equals(status)) throw new IllegalArgumentException("仅已审核的采购订单可生成入库单，当前状态：" + status);

        String orderId = str(pick(order, "order_id"));
        List<Map<String, Object>> details = jdbcTemplate.queryForList("""
                SELECT detail_id, goods_code, goods_name, spec, unit_name, qty, price, amount
                FROM purchase_order_detail WHERE order_id = ? ORDER BY detail_id
                """, orderId);

        // 计算每个商品已入库数量：合并所有历史 pur_inbound_detail（同 goods_code + source_order）
        Map<String, BigDecimal> inboundedQtyByGoods = new HashMap<>();
        List<Map<String, Object>> inboundedRows = jdbcTemplate.queryForList("""
                SELECT d.goods_code AS gc, SUM(d.received_qty) AS q
                FROM pur_inbound_detail d
                JOIN pur_inbound h ON d.inbound_id = h.inbound_id
                WHERE h.source_order = ? AND h.status = 'APPROVED'
                GROUP BY d.goods_code
                """, str(pick(order, "order_no")));
        for (Map<String, Object> r : inboundedRows) {
            inboundedQtyByGoods.put(str(pick(r, "gc")), toBd(pick(r, "q")));
        }

        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> d : details) {
            String goodsCode = str(pick(d, "goods_code"));
            BigDecimal orderQty = toBd(pick(d, "qty"));
            BigDecimal inboundedQty = inboundedQtyByGoods.getOrDefault(goodsCode, BigDecimal.ZERO);
            BigDecimal remainQty = orderQty.subtract(inboundedQty);
            if (remainQty.signum() < 0) remainQty = BigDecimal.ZERO;

            Map<String, Object> line = new HashMap<>();
            line.put("goodsCode", goodsCode);
            line.put("goodsName", str(pick(d, "goods_name")));
            line.put("spec", str(pick(d, "spec")));
            line.put("unitName", str(pick(d, "unit_name")));
            line.put("orderQty", orderQty);
            line.put("inboundedQty", inboundedQty);
            line.put("remainQty", remainQty);        // 前端默认按此填 received_qty
            line.put("price", toBd(pick(d, "price"))); // 只读
            lines.add(line);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", str(pick(order, "order_no")));
        result.put("orderId", orderId);
        result.put("supplierCode", str(pick(order, "supplier_code")));
        result.put("supplier", str(pick(order, "supplier_name")));
        result.put("warehouse", str(pick(order, "warehouse")));
        result.put("orderAmount", toBd(pick(order, "amount")));
        result.put("inboundedAmount", toBd(pick(order, "inbound_amount")));
        result.put("details", lines);
        return ApiResponse.ok(result);
    }

    /**
     * 创建采购入库单。
     * <p>请求 payload：
     * <pre>
     * {
     *   "sourceOrder": "PO20260722xxxx",      // 可选：来源采购订单号（走入口 B / 引入订单）
     *   "supplier": "…", "warehouse": "…",   // 头部（不给则从订单预填）
     *   "billDate": "2026-07-22",
     *   "details": [                          // 拆行明细：每行一批次；缺省时按订单预填一商品一行
     *     { "goodsCode":"…", "goodsName":"…", "unitName":"…", "batchNo":"…",
     *       "productionDate":"…", "expiryDate":"…", "receivedQty": 100, "price": 35 }
     *   ]
     * }
     * </pre>
     */
    @PostMapping("/inbound/create")
    @Transactional
    public ApiResponse<Map<String, Object>> createInbound(@RequestBody Map<String, Object> request) {
        String sourceOrder = str(request.get("sourceOrder"));
        if (sourceOrder.isBlank()) sourceOrder = str(request.get("bizId"));

        // 查订单 —— 仅拿头部字段用于预填与校验
        Map<String, Object> order = null;
        if (!sourceOrder.isBlank()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT order_id, order_no, supplier_name, warehouse, status " +
                            "FROM purchase_order WHERE order_no = ? OR order_id = ?",
                    sourceOrder, sourceOrder);
            if (!rows.isEmpty()) order = rows.get(0);
        }
        if (order != null && !"APPROVED".equals(str(pick(order, "status")))) {
            throw new IllegalArgumentException("采购订单未审核，无法生成入库单");
        }

        String orderNo = order != null ? str(pick(order, "order_no")) : sourceOrder;
        String supplier = strOrDefault(request.get("supplier"),
                order != null ? str(pick(order, "supplier_name")) : "");
        String warehouse = strOrDefault(request.get("warehouse"),
                order != null ? str(pick(order, "warehouse")) : "总仓");
        LocalDate billDate = parseDate(request.get("billDate"), LocalDate.now());

        // 解析前端拆行明细
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        // 明细为空：走「按订单预填」逻辑
        if (reqDetails.isEmpty() && order != null) {
            String orderId = str(pick(order, "order_id"));
            List<Map<String, Object>> orderDetails = jdbcTemplate.queryForList("""
                    SELECT goods_code, goods_name, unit_name, qty, price
                    FROM purchase_order_detail WHERE order_id = ? ORDER BY detail_id
                    """, orderId);
            for (Map<String, Object> od : orderDetails) {
                Map<String, Object> line = new HashMap<>();
                line.put("goodsCode", str(pick(od, "goods_code")));
                line.put("goodsName", str(pick(od, "goods_name")));
                line.put("unitName", str(pick(od, "unit_name")));
                line.put("receivedQty", toBd(pick(od, "qty")));
                line.put("price", toBd(pick(od, "price")));
                reqDetails.add(line);
            }
        }
        if (reqDetails.isEmpty()) throw new IllegalArgumentException("入库明细不能为空");

        // 校验：Σ实收 ≤ Σ订单剩余（仅当来源订单存在时校验）
        if (order != null) {
            String orderId = str(pick(order, "order_id"));
            Map<String, BigDecimal> orderQtyByGoods = new HashMap<>();
            for (Map<String, Object> od : jdbcTemplate.queryForList(
                    "SELECT goods_code AS gc, qty AS q FROM purchase_order_detail WHERE order_id = ?", orderId)) {
                orderQtyByGoods.merge(str(pick(od, "gc")), toBd(pick(od, "q")), BigDecimal::add);
            }
            Map<String, BigDecimal> inboundedQtyByGoods = new HashMap<>();
            for (Map<String, Object> r : jdbcTemplate.queryForList("""
                    SELECT d.goods_code AS gc, COALESCE(SUM(d.received_qty), 0) AS q
                    FROM pur_inbound_detail d
                    JOIN pur_inbound h ON d.inbound_id = h.inbound_id
                    WHERE h.source_order = ? AND h.status = 'APPROVED'
                    GROUP BY d.goods_code
                    """, orderNo)) {
                inboundedQtyByGoods.put(str(pick(r, "gc")), toBd(pick(r, "q")));
            }
            Map<String, BigDecimal> thisTimeQtyByGoods = new HashMap<>();
            for (Map<String, Object> line : reqDetails) {
                thisTimeQtyByGoods.merge(str(line.get("goodsCode")), toBd(line.get("receivedQty")), BigDecimal::add);
            }
            for (Map.Entry<String, BigDecimal> e : thisTimeQtyByGoods.entrySet()) {
                BigDecimal orderQ = orderQtyByGoods.getOrDefault(e.getKey(), BigDecimal.ZERO);
                BigDecimal inbounded = inboundedQtyByGoods.getOrDefault(e.getKey(), BigDecimal.ZERO);
                BigDecimal remain = orderQ.subtract(inbounded);
                if (e.getValue().compareTo(remain) > 0) {
                    throw new IllegalArgumentException(
                            "商品 " + e.getKey() + " 本次入库 " + e.getValue() + " 超过订单剩余 " + remain);
                }
            }
        }

        // 计算头部汇总
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("receivedQty"));
            BigDecimal p = toBd(line.get("price"));
            BigDecimal a = q.multiply(p).setScale(2, RoundingMode.HALF_UP);
            line.put("_amount", a);
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(a);
        }

        String id = "PI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.PURCHASE_INBOUND, "pur_inbound", "inbound_no");

        PurchaseInbound inbound = new PurchaseInbound();
        inbound.setInboundId(id);
        inbound.setInboundNo(no);
        inbound.setSourceOrder(orderNo);
        inbound.setSupplier(supplier);
        inbound.setWarehouse(warehouse);
        inbound.setBillDate(billDate);
        inbound.setQty(totalQty);
        inbound.setAmount(totalAmount);
        inbound.setStatus("PENDING");
        inbound.setStockUpdated(false);
        inbound.setReceiptGenerated(false);
        inboundService.save(inbound);

        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("receivedQty"));
            BigDecimal p = toBd(line.get("price"));
            BigDecimal a = (BigDecimal) line.getOrDefault("_amount", q.multiply(p).setScale(2, RoundingMode.HALF_UP));
            LocalDate productionDate = parseDate(line.get("productionDate"), null);
            LocalDate expiryDate = parseDate(line.get("expiryDate"), null);

            // 批次号生成规则（全局统一）：优先用用户输入的批次号；未输入时根据生产日期生成 YYYYMMDD 格式；
            // 生产日期也为空时批次号留空（商品库存支持空批次和空生产日期）。无前缀。
            String batchNo = str(line.get("batchNo"));
            if (batchNo.isBlank() && productionDate != null) {
                batchNo = productionDate.format(YYYYMMDD);
            }

            PurchaseInboundDetail detail = new PurchaseInboundDetail();
            detail.setDetailId("PID" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            detail.setInboundId(id);
            detail.setGoodsCode(str(line.get("goodsCode")));
            detail.setGoodsName(str(line.get("goodsName")));
            detail.setWarehouse(warehouse);
            detail.setUnitName(str(line.get("unitName")));
            detail.setExpectedQty(q);      // V1.0：应入=实收，Step 后续如需支持部分收货可拆
            detail.setReceivedQty(q);
            detail.setBatchNo(batchNo);
            detail.setProductionDate(productionDate);
            detail.setExpiryDate(expiryDate);
            detail.setPrice(p);
            detail.setAmount(a);
            detail.setBeforeCost(p);       // 审核时会被 InventoryCostService 覆盖为真实的移动平均前后成本
            detail.setAfterCost(p);
            inboundDetailService.save(detail);
        }

        log("purchase.inbound", "CREATE", no, "创建采购入库单");
        return ApiResponse.ok(GenericResult.row("inboundId", id, "inboundNo", no, "sourceOrder", orderNo, "status", "PENDING"));
    }

    @PostMapping("/inbound/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditInbound(@Valid @RequestBody AuditRequest request) {
        PurchaseInbound inbound = inboundService.getOne(
                new QueryWrapper<PurchaseInbound>()
                        .eq("inbound_id", request.bizId()).or().eq("inbound_no", request.bizId())
                        .eq("status", "PENDING")
                        .orderByDesc("inbound_no")
        );
        if (inbound == null) throw new IllegalArgumentException("入库单不存在或已审核");

        List<PurchaseInboundDetail> details = inboundDetailService.list(
                new QueryWrapper<PurchaseInboundDetail>().eq("inbound_id", inbound.getInboundId())
        );

        // 使用成本核算引擎处理库存更新和成本重算（按批次写 inv_batch_stock + 移动平均法更新 inv_stock_balance）
        for (PurchaseInboundDetail detail : details) {
            inventoryCostService.purchaseInbound(
                    detail.getGoodsCode(),
                    detail.getGoodsName(),
                    detail.getWarehouse(),
                    detail.getBatchNo(),
                    detail.getReceivedQty(),
                    detail.getAfterCost() != null ? detail.getAfterCost() : detail.getPrice(),
                    inbound.getInboundNo(),
                    detail.getProductionDate()   // 必须透传：否则 inv_batch_stock.production_date 为空，
                                                 // 下游按批次退货/出库时选批次带不出生产日期
            );
            // 同步 base_goods.latest_purchase_price（作为参考进价，非成本）
            jdbcTemplate.update(
                    "UPDATE base_goods SET latest_purchase_price = ? WHERE goods_code = ?",
                    detail.getPrice(), detail.getGoodsCode());
        }

        inbound.setStatus("APPROVED");
        inbound.setStockUpdated(true);
        // 采购收货单审核时单独设置 receipt_generated
        inboundService.updateById(inbound);

        // 回写采购订单：累加入库金额 + 按累计 vs 订单金额 计算 inbound_status
        String sourceOrderNo = inbound.getSourceOrder();
        if (sourceOrderNo != null && !sourceOrderNo.isBlank()) {
            List<Map<String, Object>> orderRows = jdbcTemplate.queryForList(
                    "SELECT order_id, amount FROM purchase_order WHERE order_no = ?", sourceOrderNo);
            if (!orderRows.isEmpty()) {
                BigDecimal orderAmount = toBd(pick(orderRows.get(0), "amount"));
                // 用当前入库金额与已累计金额比对判断，避免依赖字段读到旧值
                BigDecimal cumulativeAmount = toBd(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(h.amount), 0)
                        FROM pur_inbound h
                        WHERE h.source_order = ? AND h.status = 'APPROVED'
                        """, BigDecimal.class, sourceOrderNo));
                String inboundStatus;
                if (cumulativeAmount.signum() <= 0) {
                    inboundStatus = "未入库";
                } else if (orderAmount.signum() > 0 && cumulativeAmount.compareTo(orderAmount) >= 0) {
                    inboundStatus = "已入库";
                } else {
                    inboundStatus = "部分入库";
                }
                jdbcTemplate.update("""
                        UPDATE purchase_order
                        SET inbound_amount = ?, inbound_status = ?
                        WHERE order_no = ?
                        """, cumulativeAmount, inboundStatus, sourceOrderNo);
            }
        }

        log("purchase.inbound", "AUDIT", inbound.getInboundNo(), "采购入库审核");

        // 自动生成采购收货单（幂等：同一入库单只会有一张收货单）
        String receiptNo = receiptController.generateFromInbound(inbound.getInboundId());

        return ApiResponse.ok(Map.of(
                "inboundId", inbound.getInboundId(),
                "status", "APPROVED",
                "receiptNo", receiptNo,
                "effect", "库存增加，成本按移动加权平均法重算，生成库存流水；已自动生成采购收货单 " + receiptNo));
    }

    // ========== 采购退货已迁移到 PurchaseReturnController（完整三单流程） ==========

    @PostMapping("/expense/page")
    public ApiResponse<PageResult<Map<String, Object>>> expensePage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no expenseNo, '运费' expenseType, object_name objectName, amount expenseAmount,
                       CASE status WHEN 'APPROVED' THEN '已分摊' ELSE '未分摊' END allocationStatus,
                       CASE status WHEN 'APPROVED' THEN '已生成' ELSE '未生成' END apStatus,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='PURCHASE_EXPENSE' ORDER BY bill_no DESC
                """), request));
    }

    @PostMapping("/expense/audit")
    public ApiResponse<Map<String, Object>> auditExpense(@Valid @RequestBody AuditRequest request) {
        jdbcTemplate.update("UPDATE biz_simple_bill SET status='APPROVED' WHERE bill_type='PURCHASE_EXPENSE' AND (bill_id=? OR bill_no=? OR bill_no=(SELECT bill_no FROM biz_simple_bill WHERE bill_type='PURCHASE_EXPENSE' ORDER BY bill_no DESC LIMIT 1))", request.bizId(), request.bizId());
        return ApiResponse.ok(Map.of("expenseId", request.bizId(), "status", "APPROVED", "effect", "费用已分摊"));
    }

    @PostMapping("/invoice/page")
    public ApiResponse<PageResult<Map<String, Object>>> invoicePage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(List.of(Map.of("invoiceNo", "PINV202606140001", "supplier", "农夫山泉杭州经销", "invoiceCode", "3300****", "invoiceAmount", "3955.00", "matchStatus", "未勾稽", "certStatus", "未认证", "status", "正常")), request));
    }

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), moduleCode, action, bizNo, detail);
    }

    // ============ 工具方法 ============

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
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return dft;
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return dft; }
    }

    /** H2 返回的字段名可能是大写也可能是小写，兼容取值。 */
    private static Object pick(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        return row.get(key.toUpperCase(Locale.ROOT));
    }

    public record AuditRequest(@NotBlank String bizId, String remark) {}
}
