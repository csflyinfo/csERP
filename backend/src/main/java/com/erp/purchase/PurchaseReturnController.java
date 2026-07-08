package com.erp.purchase;

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
 * 采购退货全流程 REST 端点。
 * <p>
 * 链路：
 * <ol>
 *   <li>采购退货申请（pur_return_apply）— 可选关联采购收货单，审核后自动生成退货出库单</li>
 *   <li>采购退货出库（pur_return_outbound）— 审核后扣减库存，按成本计价，自动生成采购退货单</li>
 *   <li>采购退货单（pur_return）— 审核后写负向 fin_ap 冲减应付账款</li>
 * </ol>
 * <p>
 * 全部使用 JdbcTemplate 直写，DTO 用 {@code Map<String, Object>}，
 * 模式与 {@link PurchaseReceiptController} 完全对称。
 */
@RestController
@RequestMapping("/purchase")
public class PurchaseReturnController {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbcTemplate;
    private final InventoryCostService inventoryCostService;
    private final com.erp.common.util.BillNoGenerator billNoGen;

    public PurchaseReturnController(JdbcTemplate jdbcTemplate,
                                    InventoryCostService inventoryCostService,
                                    com.erp.common.util.BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.inventoryCostService = inventoryCostService;
        this.billNoGen = billNoGen;
    }

    // ========================================================================
    //  采购退货申请 — 列表 / 创建 / 详情 / 审核 / 反审核 / 删除
    // ========================================================================

    /**
     * 【按单添加商品】左表：该供应商已审核的采购入库单列表。
     * <p>默认展示最近一年、按单据日期降序（前端默认选中第一行）。
     * <p><b>过滤无可退商品的单据</b>：整单每一行都已退完（可退数量 = 0）的入库单不展示，
     * 否则用户点进去只能看到一堆禁选行。判定用 EXISTS 子查询逐行比对
     * 「入库数量 &gt; 已被未作废退货单占用的数量」。
     *
     * @param supplierName 主表选中的供应商名称（必填）
     * @param inboundNo    入库单号模糊查询（可选）
     * @param dateFrom     单据日期起（可选，缺省为一年前）
     * @param dateTo       单据日期止（可选，缺省为今天）
     */
    @GetMapping("/return-apply/inbound-bills")
    public ApiResponse<List<Map<String, Object>>> inboundBills(
            @RequestParam String supplierName,
            @RequestParam(required = false) String inboundNo,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        if (supplierName == null || supplierName.isBlank()) {
            throw new IllegalArgumentException("请先选择供应商");
        }
        LocalDate from = parseDate(dateFrom, LocalDate.now().minusYears(1));
        LocalDate to = parseDate(dateTo, LocalDate.now());
        String noLike = (inboundNo == null || inboundNo.isBlank()) ? null : "%" + inboundNo.trim() + "%";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT h.inbound_id, h.inbound_no, h.bill_date, h.supplier, h.warehouse, h.qty, h.amount
                FROM pur_inbound h
                WHERE h.status = 'APPROVED' AND h.supplier = ?
                  AND h.bill_date >= ? AND h.bill_date <= ?
                  AND (? IS NULL OR h.inbound_no LIKE ?)
                  AND EXISTS (
                      SELECT 1 FROM pur_inbound_detail d
                      WHERE d.inbound_id = h.inbound_id
                        AND d.received_qty > COALESCE((
                            SELECT SUM(rd.qty) FROM pur_return_apply_detail rd
                            JOIN pur_return_apply rh ON rd.apply_id = rh.apply_id
                            WHERE rd.source_detail_id = d.detail_id
                              AND rh.status <> 'CANCELLED'
                        ), 0)
                  )
                ORDER BY h.bill_date DESC, h.inbound_no DESC
                """, supplierName, from, to, noLike, noLike);
        return ApiResponse.ok(rows.stream().map(PurchaseReturnController::camelize).toList());
    }

    /**
     * 【按单添加商品】右表：选中入库单的明细 + 已退数量 + 成本单价 + 可用库存。
     * <p>规格从 {@code base_goods} 补（{@code pur_inbound_detail} 无 spec 字段）；
     * 成本单价与可用库存从 {@code inv_stock_balance} 取（goods_code + warehouse）。
     * <p><b>已退数量</b>：按 {@code source_detail_id} 汇总所有<em>未作废</em>退货申请明细
     * （含草稿 DRAFT 与待审核 PENDING）—— 草稿也占用可退额度，避免多人同时建单超退。
     *
     * @param inboundId    入库单 ID 或单号
     * @param goodsKeyword 商品编号/名称模糊过滤（可选，用于定位商品）
     */
    @GetMapping("/return-apply/inbound-detail")
    public ApiResponse<Map<String, Object>> inboundDetail(
            @RequestParam String inboundId,
            @RequestParam(required = false) String goodsKeyword) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT inbound_id, inbound_no, supplier, warehouse, bill_date, status
                FROM pur_inbound WHERE inbound_id = ? OR inbound_no = ?
                """, inboundId, inboundId);
        if (heads.isEmpty()) throw new IllegalArgumentException("采购入库单不存在：" + inboundId);
        Map<String, Object> head = heads.get(0);
        String status = str(pick(head, "status"));
        if (!"APPROVED".equals(status)) {
            throw new IllegalArgumentException("仅已审核的采购入库单可退货，当前状态：" + status);
        }
        String realInboundId = str(pick(head, "inbound_id"));
        String inboundNo = str(pick(head, "inbound_no"));
        String warehouse = str(pick(head, "warehouse"));

        String kw = (goodsKeyword == null || goodsKeyword.isBlank())
                ? null : "%" + goodsKeyword.trim().toLowerCase() + "%";

        // 明细 + 规格 + 库存成本/可用量；已退数量单独子查询按源行汇总
        // cost_price 取「源单入库成本」（after_cost，即该次入库后的移动平均成本）：
        //   按单退货是退这一笔货，成本应还原到当时入库的成本，而非当前库存成本；
        //   after_cost 为空时退回 price（订单单价）兜底。
        List<Map<String, Object>> details = jdbcTemplate.queryForList("""
                SELECT d.detail_id, d.goods_code, d.goods_name, g.spec, d.unit_name,
                       d.received_qty AS qty, d.price, d.amount,
                       d.production_date, d.batch_no,
                       COALESCE(NULLIF(d.after_cost, 0), d.price, 0) AS cost_price,
                       COALESCE(sb.available_qty, 0) AS available_stock,
                       COALESCE((SELECT SUM(rd.qty) FROM pur_return_apply_detail rd
                                 JOIN pur_return_apply rh ON rd.apply_id = rh.apply_id
                                 WHERE rd.source_detail_id = d.detail_id
                                   AND rh.status <> 'CANCELLED'), 0) AS returned_qty
                FROM pur_inbound_detail d
                LEFT JOIN base_goods g ON d.goods_code = g.goods_code
                LEFT JOIN inv_stock_balance sb
                       ON sb.goods_code = d.goods_code AND sb.warehouse = d.warehouse
                WHERE d.inbound_id = ?
                  AND (? IS NULL OR LOWER(d.goods_code) LIKE ? OR LOWER(d.goods_name) LIKE ?)
                ORDER BY d.detail_id
                """, realInboundId, kw, kw, kw);

        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> d : details) {
            Map<String, Object> line = camelize(d);
            BigDecimal qty = toBd(pick(d, "qty"));
            BigDecimal returnedQty = toBd(pick(d, "returned_qty"));
            BigDecimal returnableQty = qty.subtract(returnedQty);
            if (returnableQty.signum() < 0) returnableQty = BigDecimal.ZERO;
            line.put("returnableQty", returnableQty);
            line.put("sourceInboundNo", inboundNo);
            lines.add(line);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("inboundId", realInboundId);
        result.put("inboundNo", inboundNo);
        result.put("supplier", str(pick(head, "supplier")));
        result.put("warehouse", warehouse);
        result.put("details", lines);
        return ApiResponse.ok(result);
    }

    /**
     * 【添加商品】三页签商品数据源。
     *
     * @param tab          {@code HISTORY} 历史采购（默认）/ {@code SUPPLIER} 供应商商品 / {@code ALL} 全部商品
     * @param supplierName 供应商名称（HISTORY 与 SUPPLIER 页签必填）
     * @param warehouse    仓库（用于带出该仓库的成本单价与可用库存）
     * @param keyword      商品编号/名称/条码模糊查询
     */
    @GetMapping("/return-apply/goods-options")
    public ApiResponse<List<Map<String, Object>>> goodsOptions(
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) String supplierName,
            @RequestParam(required = false) String warehouse,
            @RequestParam(required = false) String keyword) {
        String mode = (tab == null || tab.isBlank()) ? "HISTORY" : tab.trim().toUpperCase(Locale.ROOT);
        String kw = (keyword == null || keyword.isBlank())
                ? null : "%" + keyword.trim().toLowerCase() + "%";
        String wh = warehouse == null ? "" : warehouse;

        List<Map<String, Object>> rows;
        switch (mode) {
            case "HISTORY" -> {
                // 历史采购：该供应商已审核入库单里出现过的商品，按最近入库时间倒序，带最近入库价
                if (supplierName == null || supplierName.isBlank()) {
                    throw new IllegalArgumentException("请先选择供应商");
                }
                rows = jdbcTemplate.queryForList("""
                        SELECT d.goods_code, MIN(d.goods_name) AS goods_name, MIN(g.spec) AS spec,
                               MIN(g.base_unit) AS base_unit, MIN(g.unit_config) AS unit_config,
                               MAX(h.bill_date) AS last_inbound_date,
                               COALESCE(MAX(sb.cost_price), 0) AS cost_price,
                               COALESCE(MAX(sb.available_qty), 0) AS available_stock,
                               COALESCE(MAX(g.latest_purchase_price), 0) AS latest_purchase_price
                        FROM pur_inbound_detail d
                        JOIN pur_inbound h ON d.inbound_id = h.inbound_id
                        LEFT JOIN base_goods g ON d.goods_code = g.goods_code
                        LEFT JOIN inv_stock_balance sb
                               ON sb.goods_code = d.goods_code AND sb.warehouse = ?
                        WHERE h.status = 'APPROVED' AND h.supplier = ?
                          AND (? IS NULL OR LOWER(d.goods_code) LIKE ? OR LOWER(d.goods_name) LIKE ?)
                        GROUP BY d.goods_code
                        ORDER BY MAX(h.bill_date) DESC, d.goods_code
                        """, wh, supplierName, kw, kw, kw);
            }
            case "SUPPLIER" -> {
                // 供应商商品：商品档案里 default_supplier 关联该供应商的商品
                if (supplierName == null || supplierName.isBlank()) {
                    throw new IllegalArgumentException("请先选择供应商");
                }
                rows = jdbcTemplate.queryForList("""
                        SELECT g.goods_code, g.goods_name, g.spec, g.base_unit, g.unit_config,
                               COALESCE(g.latest_purchase_price, 0) AS latest_purchase_price,
                               COALESCE(sb.cost_price, 0) AS cost_price,
                               COALESCE(sb.available_qty, 0) AS available_stock
                        FROM base_goods g
                        LEFT JOIN inv_stock_balance sb
                               ON sb.goods_code = g.goods_code AND sb.warehouse = ?
                        WHERE g.default_supplier = ? AND COALESCE(g.status, 'NORMAL') <> 'STOPPED'
                          AND (? IS NULL OR LOWER(g.goods_code) LIKE ? OR LOWER(g.goods_name) LIKE ?
                               OR LOWER(COALESCE(g.barcode, '')) LIKE ?)
                        ORDER BY g.goods_code
                        """, wh, supplierName, kw, kw, kw, kw);
            }
            default -> {
                // 全部商品：全部正常商品
                rows = jdbcTemplate.queryForList("""
                        SELECT g.goods_code, g.goods_name, g.spec, g.base_unit, g.unit_config,
                               COALESCE(g.latest_purchase_price, 0) AS latest_purchase_price,
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
        return ApiResponse.ok(rows.stream().map(PurchaseReturnController::camelize).toList());
    }

    /**
     * 批次下拉：该商品在指定仓库内<em>有库存</em>的批次。
     * <p>选中批次后前端带出 {@code productionDate / availableQty / costPrice}。
     * 同一商品同单位允许在不同行选不同批次退货。
     * <p><b>生产日期兜底</b>：早期入库审核未把生产日期透传到 {@code inv_batch_stock}
     * （见 {@code PurchaseController.auditInbound}），老批次该字段为 NULL。
     * 这里用同商品同批次的采购入库明细回查兜底，保证选批次能带出生产日期。
     */
    @GetMapping("/return-apply/batch-options")
    public ApiResponse<List<Map<String, Object>>> batchOptions(
            @RequestParam String goodsCode,
            @RequestParam String warehouse) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bs.batch_no,
                       COALESCE(bs.production_date, (
                           SELECT MAX(d.production_date) FROM pur_inbound_detail d
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
        return ApiResponse.ok(rows.stream().map(PurchaseReturnController::camelize).toList());
    }

    @PostMapping("/return-apply/page")
    public ApiResponse<PageResult<Map<String, Object>>> applyPage(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT apply_id, apply_no, source_receipt_no,
                       supplier_code, supplier_name, warehouse, bill_date,
                       qty, amount, return_reason, status, outbound_generated,
                       creator_name, audit_user, audit_time, create_time, remark
                FROM pur_return_apply
                ORDER BY create_time DESC, apply_no DESC
                """);
        List<Map<String, Object>> mapped = rows.stream().map(r -> {
            Map<String, Object> row = camelize(r);
            row.put("statusText", resolveReturnApplyStatusText(str(pick(r, "status"))));
            return row;
        }).collect(Collectors.toList());
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @GetMapping("/return-apply/detail")
    public ApiResponse<Map<String, Object>> applyDetail(
            @RequestParam(required = false) String applyId,
            @RequestParam(required = false) String id) {
        String key = applyId != null && !applyId.isBlank() ? applyId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 applyId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_apply WHERE apply_id = ? OR apply_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "退货申请不存在");
        Map<String, Object> head = camelize(heads.get(0));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_apply_detail WHERE apply_id = ? ORDER BY detail_id",
                head.get("applyId"));
        head.put("details", details.stream().map(PurchaseReturnController::camelize).toList());
        return ApiResponse.ok(head);
    }

    @PostMapping("/return-apply/create")
    @Transactional
    public ApiResponse<Map<String, Object>> createApply(@RequestBody Map<String, Object> request) {
        String supplier = str(request.get("supplier"));
        String warehouse = str(request.get("warehouse"));
        LocalDate billDate = parseDate(request.get("billDate"), LocalDate.now());
        String returnReason = str(request.get("returnReason"));
        String remark = str(request.get("remark"));
        String status = str(request.get("status")).isBlank() ? "DRAFT" : str(request.get("status"));

        // 解析明细
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        if (reqDetails.isEmpty()) throw new IllegalArgumentException("退货明细不能为空");

        // 校验：按单退货按源单行校验可退数量；按品退货按批次可用库存校验
        validateDetails(reqDetails, warehouse, null);

        // 计算汇总
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

        String id = "PRA" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.PURCHASE_RETURN_REQ, "pur_return_apply", "apply_no");

        // source_receipt_no 保留字段兼容：存本单涉及的源入库单号（多个源单时存第一个，明细行各自有 source_inbound_no）
        String headSourceNo = reqDetails.stream()
                .map(l -> str(l.get("sourceInboundNo")))
                .filter(s -> !s.isBlank())
                .findFirst().orElse("");

        jdbcTemplate.update("""
                INSERT INTO pur_return_apply(apply_id, apply_no, source_receipt_no,
                    supplier_code, supplier_name, warehouse, bill_date,
                    qty, amount, return_reason, status, creator_name, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '系统管理员', ?)
                """, id, no, headSourceNo.isBlank() ? null : headSourceNo,
                str(request.get("supplierCode")), supplier, warehouse, billDate,
                totalQty, totalAmount, returnReason, status, remark);

        for (Map<String, Object> line : reqDetails) {
            insertApplyDetail(id, line);
        }

        log("purchase.return.apply", "CREATE", no, "创建采购退货申请");
        return ApiResponse.ok(Map.of("applyId", id, "applyNo", no, "status", status));
    }

    @PostMapping("/return-apply/update")
    @Transactional
    public ApiResponse<Map<String, Object>> updateApply(@RequestBody Map<String, Object> request) {
        String applyId = str(request.get("applyId"));
        if (applyId.isBlank()) throw new IllegalArgumentException("缺少 applyId");

        Map<String, Object> existing = findApplyById(applyId);
        String status = str(pick(existing, "status"));
        if (!"DRAFT".equals(status) && !"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅草稿或待审核的申请可修改，当前状态：" + status);
        }

        // 删除旧明细，重新插入
        jdbcTemplate.update("DELETE FROM pur_return_apply_detail WHERE apply_id = ?", applyId);

        // 更新头部
        String supplier = strOrDefault(request.get("supplier"), str(pick(existing, "supplier_name")));
        String warehouse = strOrDefault(request.get("warehouse"), str(pick(existing, "warehouse")));
        LocalDate billDate = parseDate(request.get("billDate"), parseDate(pick(existing, "bill_date"), LocalDate.now()));
        String returnReason = strOrDefault(request.get("returnReason"), str(pick(existing, "return_reason")));
        String remark = strOrDefault(request.get("remark"), str(pick(existing, "remark")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        if (reqDetails.isEmpty()) {
            // 没传明细时保留原明细
            List<Map<String, Object>> oldDetails = jdbcTemplate.queryForList(
                    "SELECT * FROM pur_return_apply_detail WHERE apply_id = ?", applyId);
            for (Map<String, Object> od : oldDetails) {
                reqDetails.add(camelize(od));
            }
        }
        if (reqDetails.isEmpty()) throw new IllegalArgumentException("退货明细不能为空");

        // 校验：排除本单自身已占用的额度（excludeApplyId = 本单）
        validateDetails(reqDetails, warehouse, applyId);

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

        String headSourceNo = reqDetails.stream()
                .map(l -> str(l.get("sourceInboundNo")))
                .filter(s -> !s.isBlank())
                .findFirst().orElse("");

        jdbcTemplate.update("""
                UPDATE pur_return_apply SET source_receipt_no=?, supplier_code=?, supplier_name=?,
                    warehouse=?, bill_date=?, qty=?, amount=?, return_reason=?, remark=?
                WHERE apply_id=?
                """, headSourceNo.isBlank() ? null : headSourceNo,
                strOrDefault(request.get("supplierCode"), str(pick(existing, "supplier_code"))),
                supplier, warehouse, billDate, totalQty, totalAmount, returnReason, remark, applyId);

        for (Map<String, Object> line : reqDetails) {
            insertApplyDetail(applyId, line);
        }

        log("purchase.return.apply", "UPDATE", str(pick(existing, "apply_no")), "修改采购退货申请");
        return ApiResponse.ok(Map.of("applyId", applyId, "status", status));
    }

    /**
     * 审核退货申请：置 APPROVED → 自动生成退货出库单（幂等）。
     * 出库单抬头信息从申请单复制，明细数量 = 申请数量（WMS 确认时可修改）。
     */
    @PostMapping("/return-apply/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditApply(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> apply = findApplyById(request.bizId());
        String status = str(pick(apply, "status"));
        if (!"DRAFT".equals(status) && !"PENDING".equals(status)) {
            throw new IllegalArgumentException("退货申请已审核或已完成，当前状态：" + status);
        }
        String applyId = str(pick(apply, "apply_id"));
        String applyNo = str(pick(apply, "apply_no"));

        jdbcTemplate.update("""
                UPDATE pur_return_apply SET status='APPROVED', audit_user=?, audit_time=CURRENT_TIMESTAMP
                WHERE apply_id=?
                """, "系统管理员", applyId);

        // 自动生成退货出库单（幂等）
        String outboundNo = generateOutboundFromApply(applyId);

        log("purchase.return.apply", "AUDIT", applyNo, "采购退货申请审核 → 自动生成退货出库单 " + outboundNo);
        return ApiResponse.ok(Map.of("applyId", applyId, "applyNo", applyNo, "status", "APPROVED",
                "outboundNo", outboundNo, "effect", "已审核，自动生成退货出库单 " + outboundNo));
    }

    /** 反审核：仅当出库单未审核（PENDING 或不存在）时允许。 */
    @PostMapping("/return-apply/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAuditApply(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> apply = findApplyById(request.bizId());
        String status = str(pick(apply, "status"));
        if (!"APPROVED".equals(status)) throw new IllegalArgumentException("仅已审核申请可反审核，当前状态：" + status);
        String applyId = str(pick(apply, "apply_id"));
        String applyNo = str(pick(apply, "apply_no"));

        // 检查关联出库单
        List<Map<String, Object>> obRows = jdbcTemplate.queryForList(
                "SELECT outbound_id, status FROM pur_return_outbound WHERE source_apply_no = ?", applyNo);
        for (Map<String, Object> ob : obRows) {
            String obStatus = str(pick(ob, "status"));
            if ("APPROVED".equals(obStatus)) {
                throw new IllegalArgumentException("退货出库单已审核，无法反审核申请");
            }
            // 删除 PENDING 的出库单
            String obId = str(pick(ob, "outbound_id"));
            jdbcTemplate.update("DELETE FROM pur_return_outbound_detail WHERE outbound_id = ?", obId);
            jdbcTemplate.update("DELETE FROM pur_return_outbound WHERE outbound_id = ?", obId);
        }

        jdbcTemplate.update("UPDATE pur_return_apply SET status='PENDING', outbound_generated=FALSE, " +
                        "audit_user=NULL, audit_time=NULL WHERE apply_id=?", applyId);

        log("purchase.return.apply", "REVERSE_AUDIT", applyNo, "采购退货申请反审核");
        return ApiResponse.ok(Map.of("applyId", applyId, "status", "PENDING", "effect", "已反审核"));
    }

    @PostMapping("/return-apply/delete")
    @Transactional
    public ApiResponse<Map<String, Object>> deleteApply(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> apply = findApplyById(request.bizId());
        String status = str(pick(apply, "status"));
        if (!"DRAFT".equals(status)) throw new IllegalArgumentException("仅草稿可删除，当前状态：" + status);
        String applyId = str(pick(apply, "apply_id"));
        String applyNo = str(pick(apply, "apply_no"));
        jdbcTemplate.update("DELETE FROM pur_return_apply_detail WHERE apply_id = ?", applyId);
        jdbcTemplate.update("DELETE FROM pur_return_apply WHERE apply_id = ?", applyId);
        log("purchase.return.apply", "DELETE", applyNo, "删除采购退货申请草稿");
        return ApiResponse.ok(Map.of("applyId", applyId, "effect", "已删除"));
    }

    // ========================================================================
    //  采购退货出库单 — 列表 / 详情 / 更新 / 审核
    // ========================================================================

    @PostMapping("/return-outbound/page")
    public ApiResponse<PageResult<Map<String, Object>>> outboundPage(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT outbound_id, outbound_no, source_apply_no,
                       supplier_code, supplier_name, warehouse, bill_date,
                       qty, amount, cost_amount, status, stock_updated, return_generated,
                       audit_user, audit_time, create_time, remark
                FROM pur_return_outbound
                ORDER BY create_time DESC, outbound_no DESC
                """);
        List<Map<String, Object>> mapped = rows.stream().map(r -> {
            Map<String, Object> row = camelize(r);
            String st = str(pick(r, "status"));
            row.put("statusText", "PENDING".equals(st) ? "待审核" : "已审核");
            return row;
        }).collect(Collectors.toList());
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @GetMapping("/return-outbound/detail")
    public ApiResponse<Map<String, Object>> outboundDetail(
            @RequestParam(required = false) String outboundId,
            @RequestParam(required = false) String id) {
        String key = outboundId != null && !outboundId.isBlank() ? outboundId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 outboundId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_outbound WHERE outbound_id = ? OR outbound_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "退货出库单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_outbound_detail WHERE outbound_id = ? ORDER BY detail_id",
                head.get("outboundId"));
        head.put("details", details.stream().map(PurchaseReturnController::camelize).toList());
        return ApiResponse.ok(head);
    }

    /** 修改出库数量（仅 PENDING，不可超申请数量）。 */
    @PostMapping("/return-outbound/update")
    @Transactional
    public ApiResponse<Map<String, Object>> updateOutbound(@RequestBody Map<String, Object> request) {
        String outboundId = str(request.get("outboundId"));
        if (outboundId.isBlank()) throw new IllegalArgumentException("缺少 outboundId");

        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_outbound WHERE outbound_id = ?", outboundId);
        if (heads.isEmpty()) throw new IllegalArgumentException("退货出库单不存在");
        Map<String, Object> ob = heads.get(0);
        if (!"PENDING".equals(str(pick(ob, "status")))) throw new IllegalArgumentException("仅待审核的出库单可修改");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        if (reqDetails.isEmpty()) {
            return ApiResponse.ok(Map.of("outboundId", outboundId, "effect", "无变更"));
        }

        // 申请明细：按行取数量（apply_detail_id → 申请数量），与申请单明细一一对应。
        // 不能按 goods_code 聚合 —— 同商品多批次时会把各行数量加在一起，上限算错。
        String applyNo = str(pick(ob, "source_apply_no"));
        Map<String, BigDecimal> applyQtyByLine = new HashMap<>();
        Map<String, String> applyGoodsByLine = new HashMap<>();
        for (Map<String, Object> ad : jdbcTemplate.queryForList(
                "SELECT detail_id, goods_code, goods_name, qty FROM pur_return_apply_detail WHERE apply_id IN " +
                        "(SELECT apply_id FROM pur_return_apply WHERE apply_no = ?)", applyNo)) {
            String adId = str(pick(ad, "detail_id"));
            applyQtyByLine.put(adId, toBd(pick(ad, "qty")));
            applyGoodsByLine.put(adId, strOrDefault(pick(ad, "goods_name"), str(pick(ad, "goods_code"))));
        }

        String obWarehouse = str(pick(ob, "warehouse"));

        // 允许「一条申请行拆多个批次出库」：前端可新增行（detailId 为空）、删除行。
        // 校验口径：同 apply_detail_id 的出库数量合计 ≤ 该申请行数量；
        //          同批次的出库数量合计 ≤ 该批次可用库存。
        Map<String, BigDecimal> sumByApplyLine = new HashMap<>();
        Map<String, BigDecimal> sumByBatch = new HashMap<>();
        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("qty"));
            if (q.signum() <= 0) {
                throw new IllegalArgumentException("商品 " + str(line.get("goodsCode")) + " 的出库数量必须大于 0");
            }
            String applyDetailId = str(line.get("applyDetailId"));
            if (!applyDetailId.isBlank()) {
                sumByApplyLine.merge(applyDetailId, q, BigDecimal::add);
            }
            String batchNo = str(line.get("batchNo"));
            if (!batchNo.isBlank()) {
                sumByBatch.merge(str(line.get("goodsCode")) + "|" + batchNo, q, BigDecimal::add);
            }
        }
        // 逐申请行校验总出库数量不可大于申请数量
        for (Map.Entry<String, BigDecimal> e : sumByApplyLine.entrySet()) {
            BigDecimal applyQty = applyQtyByLine.get(e.getKey());
            if (applyQty == null) {
                throw new IllegalArgumentException("出库明细关联的申请行不存在：" + e.getKey());
            }
            if (e.getValue().compareTo(applyQty) > 0) {
                throw new IllegalArgumentException("商品 " + applyGoodsByLine.getOrDefault(e.getKey(), e.getKey())
                        + " 总出库数量 " + plain(e.getValue()) + " 超过申请数量 " + plain(applyQty));
            }
        }
        // 逐批次校验可用库存（含拆行后的合计）
        for (Map.Entry<String, BigDecimal> e : sumByBatch.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            BigDecimal batchAvailable = toBd(jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(qty - COALESCE(locked_qty, 0) - COALESCE(frozen_qty, 0)), 0)
                    FROM inv_batch_stock
                    WHERE goods_code = ? AND warehouse = ? AND batch_no = ?
                    """, BigDecimal.class, parts[0], obWarehouse, parts[1]));
            if (e.getValue().compareTo(batchAvailable) > 0) {
                throw new IllegalArgumentException("商品 " + parts[0] + "（批次 " + parts[1] + "） 出库数量合计 "
                        + plain(e.getValue()) + " 超过该批次可用库存 " + plain(batchAvailable));
            }
        }

        // 全量替换明细：支持前端删行/加行（拆批次），比逐条 diff 简单且不会漏
        jdbcTemplate.update("DELETE FROM pur_return_outbound_detail WHERE outbound_id = ?", outboundId);
        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("qty"));
            BigDecimal price = toBd(line.get("price"));
            BigDecimal costPrice = toBd(line.get("costPrice"));
            String newDetailId = "PROD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO pur_return_outbound_detail(detail_id, outbound_id, goods_code, goods_name, spec, unit_name,
                        qty, price, amount, batch_no, production_date,
                        return_mode, source_inbound_no, source_detail_id,
                        apply_detail_id, cost_price, cost_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, newDetailId, outboundId,
                    str(line.get("goodsCode")), str(line.get("goodsName")), str(line.get("spec")),
                    str(line.get("unitName")),
                    q, price, q.multiply(price).setScale(2, RoundingMode.HALF_UP),
                    str(line.get("batchNo")), parseDate(line.get("productionDate"), null),
                    strOrDefault(line.get("returnMode"), "BY_BILL"),
                    emptyToNull(line.get("sourceInboundNo")),
                    emptyToNull(line.get("sourceDetailId")),
                    emptyToNull(line.get("applyDetailId")),
                    costPrice,
                    q.multiply(costPrice).setScale(2, RoundingMode.HALF_UP));
        }

        // 重算头部合计
        recalcOutboundHead(outboundId);
        log("purchase.return.outbound", "UPDATE", str(pick(ob, "outbound_no")), "修改退货出库明细（数量/批次）");
        return ApiResponse.ok(Map.of("outboundId", outboundId, "effect", "已更新"));
    }

    /**
     * 审核退货出库单 — 核心业务逻辑：
     * <ol>
     *   <li>按当前库存成本单价计价</li>
     *   <li>调 InventoryCostService.salesOutbound 扣减库存 + 写流水</li>
     *   <li>回写申请状态 OUTBOUNDED</li>
     *   <li>自动生成采购退货单（幂等）</li>
     * </ol>
     */
    @PostMapping("/return-outbound/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditOutbound(@Valid @RequestBody AuditRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_outbound WHERE outbound_id = ? OR outbound_no = ?",
                request.bizId(), request.bizId());
        if (rows.isEmpty()) throw new IllegalArgumentException("退货出库单不存在：" + request.bizId());
        Map<String, Object> ob = rows.get(0);
        if (!"PENDING".equals(str(pick(ob, "status")))) throw new IllegalArgumentException("退货出库单已审核");
        String outboundId = str(pick(ob, "outbound_id"));
        String outboundNo = str(pick(ob, "outbound_no"));
        String warehouse = str(pick(ob, "warehouse"));

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_outbound_detail WHERE outbound_id = ?", outboundId);

        // 审核是「不可逆扣库存」的时刻，先整单校验再动库存：
        // InventoryCostService.salesOutbound 只校验商品仓库维度的汇总可用量，
        // 不校验批次维度，直接扣会把 inv_batch_stock.qty 扣成负数。
        //
        // 按「商品+批次」合计校验（拆批次出库后同一批次可能有多行，逐行校验会漏）
        Map<String, BigDecimal> qtyByBatch = new LinkedHashMap<>();
        Map<String, String> goodsNameByBatch = new HashMap<>();
        for (Map<String, Object> d : details) {
            String batchNo = str(pick(d, "batch_no"));
            if (batchNo.isBlank()) continue;
            String key = str(pick(d, "goods_code")) + "|" + batchNo;
            qtyByBatch.merge(key, toBd(pick(d, "qty")), BigDecimal::add);
            goodsNameByBatch.put(key, str(pick(d, "goods_name")));
        }
        for (Map.Entry<String, BigDecimal> e : qtyByBatch.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            BigDecimal batchAvailable = toBd(jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(qty - COALESCE(locked_qty, 0) - COALESCE(frozen_qty, 0)), 0)
                    FROM inv_batch_stock
                    WHERE goods_code = ? AND warehouse = ? AND batch_no = ?
                    """, BigDecimal.class, parts[0], warehouse, parts[1]));
            if (e.getValue().compareTo(batchAvailable) > 0) {
                throw new IllegalArgumentException(
                        "商品 " + goodsNameByBatch.getOrDefault(e.getKey(), parts[0])
                                + "（批次 " + parts[1] + "） 出库数量合计 " + plain(e.getValue())
                                + " 超过该批次可用库存 " + plain(batchAvailable) + "，无法审核");
            }
        }

        // 按申请行合计校验：总出库数量不可大于申请数量
        Map<String, BigDecimal> qtyByApplyLine = new LinkedHashMap<>();
        for (Map<String, Object> d : details) {
            String adId = str(pick(d, "apply_detail_id"));
            if (adId.isBlank()) continue;
            qtyByApplyLine.merge(adId, toBd(pick(d, "qty")), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : qtyByApplyLine.entrySet()) {
            List<Map<String, Object>> adRows = jdbcTemplate.queryForList(
                    "SELECT goods_name, goods_code, qty FROM pur_return_apply_detail WHERE detail_id = ?", e.getKey());
            if (adRows.isEmpty()) continue;
            BigDecimal applyQty = toBd(pick(adRows.get(0), "qty"));
            if (e.getValue().compareTo(applyQty) > 0) {
                throw new IllegalArgumentException("商品 "
                        + strOrDefault(pick(adRows.get(0), "goods_name"), str(pick(adRows.get(0), "goods_code")))
                        + " 总出库数量 " + plain(e.getValue()) + " 超过申请数量 " + plain(applyQty) + "，无法审核");
            }
        }

        BigDecimal totalCostAmount = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            String goodsCode = str(pick(d, "goods_code"));
            String goodsName = str(pick(d, "goods_name"));
            String batchNo = str(pick(d, "batch_no"));
            BigDecimal qty = toBd(pick(d, "qty"));

            // 退货成本 = 当前库存成本单价（PRD 要求）
            BigDecimal costPrice = inventoryCostService.getCurrentCostPrice(goodsCode, warehouse);
            BigDecimal costAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
            totalCostAmount = totalCostAmount.add(costAmount);

            // 扣减库存 + 写流水（复用销售出库的库存扣减逻辑，方向一致）
            inventoryCostService.salesOutbound(goodsCode, goodsName, warehouse, batchNo, qty, outboundNo);

            // 回写成本到明细
            jdbcTemplate.update("""
                    UPDATE pur_return_outbound_detail SET cost_price=?, cost_amount=?
                    WHERE detail_id=?
                    """, costPrice, costAmount, str(pick(d, "detail_id")));
        }

        jdbcTemplate.update("""
                UPDATE pur_return_outbound SET status='APPROVED', stock_updated=TRUE,
                    cost_amount=?, audit_user=?, audit_time=CURRENT_TIMESTAMP
                WHERE outbound_id=?
                """, totalCostAmount, "系统管理员", outboundId);

        // 回写申请状态
        String applyNo = str(pick(ob, "source_apply_no"));
        jdbcTemplate.update("UPDATE pur_return_apply SET status='OUTBOUNDED' WHERE apply_no=?", applyNo);

        // 自动生成采购退货单（幂等）
        String returnNo = generateReturnFromOutbound(outboundId);
        jdbcTemplate.update("UPDATE pur_return_outbound SET return_generated=TRUE WHERE outbound_id=?", outboundId);

        log("purchase.return.outbound", "AUDIT", outboundNo, "退货出库审核 → 扣库存，成本 " + totalCostAmount + "，自动生成退货单 " + returnNo);
        return ApiResponse.ok(Map.of(
                "outboundId", outboundId, "outboundNo", outboundNo, "status", "APPROVED",
                "costAmount", totalCostAmount, "returnNo", returnNo,
                "effect", "库存已扣减，成本已计价，自动生成采购退货单 " + returnNo));
    }

    // ========================================================================
    //  采购退货单 — 列表 / 详情 / 审核 / 反审核
    // ========================================================================

    @PostMapping("/return/page")
    public ApiResponse<PageResult<Map<String, Object>>> returnPage(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT return_id, return_no, source_apply_no, source_outbound_no,
                       supplier_code, supplier_name, warehouse, return_date,
                       goods_amount, tax_amount, final_amount, cost_amount,
                       ap_status, status, creator_name, audit_user, audit_time, create_time, remark
                FROM pur_return
                ORDER BY create_time DESC, return_no DESC
                """);
        List<Map<String, Object>> mapped = rows.stream().map(r -> {
            Map<String, Object> row = camelize(r);
            String st = str(pick(r, "status"));
            row.put("statusText", "PENDING".equals(st) ? "待审核" : "已审核");
            // 不含税金额 = 含税商品金额 − 税额。
            // final_amount 存的就是这个值，但语义变更前生成的老单据存的是「含税+税额」，
            // 这里统一按 goods_amount − tax_amount 现算，保证列表口径一致。
            BigDecimal goodsAmount = toBd(pick(r, "goods_amount"));
            BigDecimal taxAmount = toBd(pick(r, "tax_amount"));
            row.put("untaxedAmount", goodsAmount.subtract(taxAmount).setScale(2, RoundingMode.HALF_UP));
            return row;
        }).collect(Collectors.toList());
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @GetMapping("/return/detail")
    public ApiResponse<Map<String, Object>> returnDetail(
            @RequestParam(required = false) String returnId,
            @RequestParam(required = false) String id) {
        String key = returnId != null && !returnId.isBlank() ? returnId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 returnId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return WHERE return_id = ? OR return_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "采购退货单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        // 不含税金额现算（与列表同口径，兼容语义变更前的老单据）
        BigDecimal headGoodsAmount = toBd(head.get("goodsAmount"));
        BigDecimal headTaxAmount = toBd(head.get("taxAmount"));
        head.put("untaxedAmount", headGoodsAmount.subtract(headTaxAmount).setScale(2, RoundingMode.HALF_UP));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_detail WHERE return_id = ? ORDER BY detail_id",
                head.get("returnId"));
        head.put("details", details.stream().map(PurchaseReturnController::camelize).toList());
        return ApiResponse.ok(head);
    }

    /**
     * 审核采购退货单 → 写负向 fin_ap 冲减应付账款。
     * <p>负向 fin_ap 的 source_bill 存退货单号，ap_amount 为负数。
     * 原应付单不动，应付余额 = SUM(所有 fin_ap.ap_amount)。反审核时删除该条 fin_ap。
     */
    @PostMapping("/return/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditReturn(@Valid @RequestBody AuditRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT return_id, return_no, source_apply_no, supplier_name, goods_amount, tax_amount, " +
                        "final_amount, status, ap_status " +
                        "FROM pur_return WHERE return_id = ? OR return_no = ?",
                request.bizId(), request.bizId());
        if (rows.isEmpty()) throw new IllegalArgumentException("采购退货单不存在：" + request.bizId());
        Map<String, Object> r = rows.get(0);
        if (!"PENDING".equals(str(pick(r, "status"))))
            throw new IllegalArgumentException("采购退货单已审核或已作废，当前状态：" + str(pick(r, "status")));
        if ("已生成".equals(str(pick(r, "ap_status"))))
            throw new IllegalArgumentException("该退货单已生成应付冲减，无需重复审核");

        String returnId = str(pick(r, "return_id"));
        String returnNo = str(pick(r, "return_no"));
        String supplier = str(pick(r, "supplier_name"));
        // 应付冲减按「含税商品金额」——供应商实际要退回的是含税货款。
        // goods_amount 为含税金额；final_amount 是拆出税额后的不含税金额，不用于结算。
        BigDecimal apAmount = toBd(pick(r, "goods_amount"));

        // 写负向 fin_ap（source_bill 存退货单号，ap_amount 为负数）
        String apId = "AP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String apNo = billNoGen.nextNo("AP", "fin_ap", "ap_no");
        jdbcTemplate.update("""
                INSERT INTO fin_ap(ap_id, ap_no, source_bill, supplier,
                    ap_amount, paid_amount, unpaid_amount, due_date, status)
                VALUES (?, ?, ?, ?, ?, 0, ?, DATEADD('DAY', 30, CURRENT_DATE), 'UNVERIFIED')
                """, apId, apNo, returnNo, supplier,
                apAmount.negate(), apAmount.negate());

        jdbcTemplate.update("""
                UPDATE pur_return SET status='APPROVED', ap_status='已生成',
                    audit_user=?, audit_time=CURRENT_TIMESTAMP
                WHERE return_id=?
                """, "系统管理员", returnId);

        // 回写来源申请 COMPLETED
        String sourceApplyNo = str(pick(r, "source_apply_no"));
        if (!sourceApplyNo.isBlank()) {
            jdbcTemplate.update("UPDATE pur_return_apply SET status='COMPLETED' WHERE apply_no=?", sourceApplyNo);
        }

        log("purchase.return", "AUDIT", returnNo, "采购退货单审核 → 写入负向应付 " + apNo);
        return ApiResponse.ok(Map.of(
                "returnId", returnId, "returnNo", returnNo, "status", "APPROVED",
                "apNo", apNo, "effect", "已按最终金额写入负向应付冲减应付账款"));
    }

    /** 反审核：删除负向 fin_ap，恢复 ap_status='未生成'。 */
    @PostMapping("/return/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAuditReturn(@Valid @RequestBody AuditRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT return_id, return_no, status FROM pur_return WHERE return_id = ? OR return_no = ?",
                request.bizId(), request.bizId());
        if (rows.isEmpty()) throw new IllegalArgumentException("采购退货单不存在");
        String status = str(pick(rows.get(0), "status"));
        if (!"APPROVED".equals(status)) throw new IllegalArgumentException("只有已审核的退货单可反审核");

        String returnId = str(pick(rows.get(0), "return_id"));
        String returnNo = str(pick(rows.get(0), "return_no"));

        // 检查关联 fin_ap 是否已有付款
        List<Map<String, Object>> apRows = jdbcTemplate.queryForList(
                "SELECT ap_id, ap_no, paid_amount FROM fin_ap WHERE source_bill = ?", returnNo);
        for (Map<String, Object> ap : apRows) {
            if (toBd(pick(ap, "paid_amount")).compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("已有付款记录，无法反审核");
            }
        }
        // 删除负向 fin_ap
        jdbcTemplate.update("DELETE FROM fin_ap WHERE source_bill = ?", returnNo);

        jdbcTemplate.update("""
                UPDATE pur_return SET status='PENDING', ap_status='未生成',
                    audit_user=NULL, audit_time=NULL
                WHERE return_id=?
                """, returnId);

        log("purchase.return", "REVERSE_AUDIT", returnNo, "采购退货单反审核 → 撤销应付冲减");
        return ApiResponse.ok(Map.of("returnId", returnId, "status", "PENDING", "effect", "已反审核，应付冲减已撤销"));
    }

    // ========================================================================
    //  内部：自动生成单据（幂等）
    // ========================================================================

    /**
     * 从退货申请自动生成退货出库单（幂等）。
     * 出库单明细从申请明细复制，数量默认 = 申请数量。
     */
    @Transactional
    public String generateOutboundFromApply(String applyId) {
        List<Map<String, Object>> applyRows = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_apply WHERE apply_id = ?", applyId);
        if (applyRows.isEmpty()) throw new IllegalArgumentException("退货申请不存在：" + applyId);
        Map<String, Object> apply = applyRows.get(0);
        String applyNo = str(pick(apply, "apply_no"));

        // 幂等：已存在则返回
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT outbound_no FROM pur_return_outbound WHERE source_apply_no = ?",
                String.class, applyNo);
        if (!existing.isEmpty()) {
            jdbcTemplate.update("UPDATE pur_return_apply SET outbound_generated=TRUE WHERE apply_id=?", applyId);
            return existing.get(0);
        }

        String supplierCode = str(pick(apply, "supplier_code"));
        String supplierName = str(pick(apply, "supplier_name"));
        String warehouse = str(pick(apply, "warehouse"));
        LocalDate billDate = parseDate(pick(apply, "bill_date"), LocalDate.now());
        String remark = str(pick(apply, "remark"));

        // 复制明细
        List<Map<String, Object>> applyDetails = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_apply_detail WHERE apply_id = ?", applyId);

        // 计算汇总
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> ad : applyDetails) {
            BigDecimal q = toBd(pick(ad, "qty"));
            BigDecimal p = toBd(pick(ad, "price"));
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(q.multiply(p).setScale(2, RoundingMode.HALF_UP));
        }

        String outboundId = "PRO" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String outboundNo = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.PURCHASE_RETURN_OUT, "pur_return_outbound", "outbound_no");

        jdbcTemplate.update("""
                INSERT INTO pur_return_outbound(outbound_id, outbound_no, source_apply_no,
                    supplier_code, supplier_name, warehouse, bill_date, qty, amount, status, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, outboundId, outboundNo, applyNo,
                supplierCode, supplierName, warehouse, billDate, totalQty, totalAmount, remark);

        for (Map<String, Object> ad : applyDetails) {
            BigDecimal q = toBd(pick(ad, "qty"));
            BigDecimal p = toBd(pick(ad, "price"));
            BigDecimal amount = q.multiply(p).setScale(2, RoundingMode.HALF_UP);
            String detailId = "PROD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            BigDecimal costPrice = toBd(pick(ad, "cost_price"));
            jdbcTemplate.update("""
                    INSERT INTO pur_return_outbound_detail(detail_id, outbound_id, goods_code, goods_name, spec, unit_name,
                        qty, price, amount, batch_no, production_date,
                        return_mode, source_inbound_no, source_detail_id,
                        apply_detail_id, cost_price, cost_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, detailId, outboundId,
                    str(pick(ad, "goods_code")), str(pick(ad, "goods_name")), str(pick(ad, "spec")),
                    str(pick(ad, "unit_name")),
                    q, p, amount, str(pick(ad, "batch_no")), parseDate(pick(ad, "production_date"), null),
                    strOrDefault(pick(ad, "return_mode"), "BY_BILL"),
                    emptyToNull(pick(ad, "source_inbound_no")),
                    emptyToNull(pick(ad, "source_detail_id")),
                    // 与申请明细行一一对应：申请数量、拆批次出库的额度都按这个 ID 归集
                    str(pick(ad, "detail_id")),
                    // 成本单价从申请明细带入（按单退货=源单入库成本，按品退货=当前库存成本）；
                    // 审核时会用实时成本覆盖，这里先带出来供用户在出库单上核对
                    costPrice,
                    q.multiply(costPrice).setScale(2, RoundingMode.HALF_UP));
        }

        jdbcTemplate.update("UPDATE pur_return_apply SET outbound_generated=TRUE WHERE apply_id=?", applyId);
        log("purchase.return.outbound", "GENERATE", outboundNo, "退货申请 " + applyNo + " 自动生成退货出库单");
        return outboundNo;
    }

    /**
     * 从退货出库单自动生成采购退货单（幂等）。
     * 按 goods_code 聚合出库明细为一行，税率从申请明细透传（缺省 13%）。
     * final_amount = goods_amount + tax_amount。
     */
    @Transactional
    public String generateReturnFromOutbound(String outboundId) {
        List<Map<String, Object>> obRows = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_outbound WHERE outbound_id = ?", outboundId);
        if (obRows.isEmpty()) throw new IllegalArgumentException("退货出库单不存在：" + outboundId);
        Map<String, Object> ob = obRows.get(0);
        String outboundNo = str(pick(ob, "outbound_no"));
        String applyNo = str(pick(ob, "source_apply_no"));

        // 幂等：已存在则返回
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT return_no FROM pur_return WHERE source_outbound_no = ?",
                String.class, outboundNo);
        if (!existing.isEmpty()) return existing.get(0);

        // 获取申请明细的税率映射
        Map<String, String> taxRateByGoods = new HashMap<>();
        if (!applyNo.isBlank()) {
            List<Map<String, Object>> applyDetails = jdbcTemplate.queryForList(
                    "SELECT goods_code, tax_rate FROM pur_return_apply_detail WHERE apply_id IN " +
                            "(SELECT apply_id FROM pur_return_apply WHERE apply_no = ?)", applyNo);
            for (Map<String, Object> ad : applyDetails) {
                taxRateByGoods.put(str(pick(ad, "goods_code")), str(pick(ad, "tax_rate")));
            }
        }

        // 按 goods_code 聚合出库明细
        // 按 goods_code 聚合出库明细（合并所有批次为一行）。
        // cost_price 用「加权平均」而非 MIN —— 拆批次出库时各批次成本可能不同，
        // 取 MIN 会低估成本；SUM(cost_amount)/SUM(qty) 才是这批退货的真实平均成本。
        List<Map<String, Object>> aggregated = jdbcTemplate.queryForList("""
                SELECT goods_code, MIN(goods_name) AS goods_name, MIN(unit_name) AS unit_name,
                       SUM(qty) AS qty, MIN(price) AS price, SUM(amount) AS amount,
                       CASE WHEN SUM(qty) > 0 THEN SUM(cost_amount) / SUM(qty) ELSE MIN(cost_price) END AS cost_price,
                       SUM(cost_amount) AS cost_amount
                FROM pur_return_outbound_detail
                WHERE outbound_id = ?
                GROUP BY goods_code
                """, outboundId);

        BigDecimal totalGoodsAmount = BigDecimal.ZERO;
        BigDecimal totalTaxAmount = BigDecimal.ZERO;
        BigDecimal totalCostAmount = BigDecimal.ZERO;
        List<Map<String, Object>> detailRowsToInsert = new ArrayList<>();

        for (Map<String, Object> line : aggregated) {
            String goodsCode = str(pick(line, "goods_code"));
            // amount 是「含税金额」（单价为含税单价，qty × price 即含税）
            BigDecimal lineAmount = toBd(pick(line, "amount")).setScale(2, RoundingMode.HALF_UP);
            String taxRate = taxRateByGoods.getOrDefault(goodsCode, "13%");
            BigDecimal taxPct = parseTaxRate(taxRate);
            // 价内税倒算：税额 = 含税金额 × 税率 / (1 + 税率)
            BigDecimal lineTax = taxInclusiveTax(lineAmount, taxPct);
            BigDecimal lineCost = toBd(pick(line, "cost_amount"));

            Map<String, Object> row = new HashMap<>();
            row.put("detailId", "PRD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            row.put("goodsCode", goodsCode);
            row.put("goodsName", str(pick(line, "goods_name")));
            row.put("unitName", str(pick(line, "unit_name")));
            row.put("qty", toBd(pick(line, "qty")));
            row.put("price", toBd(pick(line, "price")));
            row.put("amount", lineAmount);
            row.put("taxRate", taxRate);
            row.put("taxAmount", lineTax);
            row.put("costPrice", toBd(pick(line, "cost_price")));
            row.put("costAmount", lineCost);
            detailRowsToInsert.add(row);

            totalGoodsAmount = totalGoodsAmount.add(lineAmount);
            totalTaxAmount = totalTaxAmount.add(lineTax);
            totalCostAmount = totalCostAmount.add(lineCost);
        }
        // final_amount 语义变更：不含税金额 = 含税商品金额 − 税额
        // （应付结算按含税的 goods_amount 走，见 auditReturn）
        BigDecimal finalAmount = totalGoodsAmount.subtract(totalTaxAmount);

        String returnId = "PR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String returnNo = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.PURCHASE_RETURN, "pur_return", "return_no");
        LocalDate returnDate = parseDate(pick(ob, "bill_date"), LocalDate.now());

        String supplierCode = str(pick(ob, "supplier_code"));
        String supplierName = str(pick(ob, "supplier_name"));
        String warehouse = str(pick(ob, "warehouse"));

        jdbcTemplate.update("""
                INSERT INTO pur_return(return_id, return_no, source_apply_no, source_outbound_no,
                    supplier_code, supplier_name, warehouse, return_date,
                    goods_amount, tax_amount, final_amount, cost_amount,
                    ap_status, status, creator_name, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '未生成', 'PENDING', '系统', NULL)
                """,
                returnId, returnNo, applyNo, outboundNo,
                supplierCode, supplierName, warehouse, returnDate,
                totalGoodsAmount, totalTaxAmount, finalAmount, totalCostAmount);

        for (Map<String, Object> row : detailRowsToInsert) {
            jdbcTemplate.update("""
                    INSERT INTO pur_return_detail(detail_id, return_id, goods_code, goods_name,
                        unit_name, qty, price, amount, tax_rate, tax_amount, cost_price, cost_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    row.get("detailId"), returnId,
                    row.get("goodsCode"), row.get("goodsName"), row.get("unitName"),
                    row.get("qty"), row.get("price"), row.get("amount"),
                    row.get("taxRate"), row.get("taxAmount"),
                    row.get("costPrice"), row.get("costAmount"));
        }

        log("purchase.return", "GENERATE", returnNo, "退货出库单 " + outboundNo + " 自动生成采购退货单");
        return returnNo;
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    private Map<String, Object> findApplyById(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM pur_return_apply WHERE apply_id = ? OR apply_no = ?", id, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("退货申请不存在：" + id);
        return rows.get(0);
    }

    /**
     * 明细硬校验（超量直接拦住）。
     * <ul>
     *   <li><b>BY_BILL 按单退货</b>：数量 ≤ 源单行可退数量
     *       （= 入库数量 − 其他未作废退货单已占用数量）。</li>
     *   <li><b>BY_GOODS 按品退货</b>：数量 ≤ 可用库存。</li>
     * </ul>
     * <p><b>两种方式都额外校验批次可用库存</b> —— 源单可退数量是「账面还能退多少」，
     * 批次可用库存是「实物还剩多少」，两者都不能突破，否则会把 {@code inv_batch_stock}
     * 扣成负数（{@code InventoryCostService.salesOutbound} 只校验商品仓库维度的汇总可用量，
     * 不校验批次维度）。
     *
     * @param excludeApplyId 编辑场景传本单 applyId —— 把本单自身已占用的额度排除，否则改数量时会误判超量
     */
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
                // 源单行入库数量
                List<Map<String, Object>> srcRows = jdbcTemplate.queryForList(
                        "SELECT received_qty FROM pur_inbound_detail WHERE detail_id = ?", sourceDetailId);
                if (srcRows.isEmpty()) {
                    throw new IllegalArgumentException("商品 " + goodsName + " 的源单行不存在：" + sourceDetailId);
                }
                BigDecimal inboundQty = toBd(pick(srcRows.get(0), "received_qty"));
                // 其他未作废退货单已占用（排除本单）
                BigDecimal occupied = toBd(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(rd.qty), 0)
                        FROM pur_return_apply_detail rd
                        JOIN pur_return_apply rh ON rd.apply_id = rh.apply_id
                        WHERE rd.source_detail_id = ? AND rh.status <> 'CANCELLED'
                          AND (? IS NULL OR rd.apply_id <> ?)
                        """, BigDecimal.class, sourceDetailId, excludeApplyId, excludeApplyId));
                BigDecimal returnable = inboundQty.subtract(occupied);
                if (returnable.signum() < 0) returnable = BigDecimal.ZERO;
                if (qty.compareTo(returnable) > 0) {
                    throw new IllegalArgumentException(
                            "商品 " + goodsName + " 退货数量 " + plain(qty)
                                    + " 超过可退数量 " + plain(returnable));
                }
            } else {
                // BY_GOODS：按商品仓库维度的可用库存兜底校验
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

            // 两种方式共同校验：指定批次时不得超过该批次可用库存（防止批次库存被扣成负数）
            String batchNo = str(line.get("batchNo"));
            if (!batchNo.isBlank()) {
                BigDecimal batchAvailable = toBd(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(qty - COALESCE(locked_qty, 0) - COALESCE(frozen_qty, 0)), 0)
                        FROM inv_batch_stock
                        WHERE goods_code = ? AND warehouse = ? AND batch_no = ?
                        """, BigDecimal.class, goodsCode, warehouse, batchNo));
                if (qty.compareTo(batchAvailable) > 0) {
                    throw new IllegalArgumentException(
                            "商品 " + goodsName + "（批次 " + batchNo + "） 退货数量 " + plain(qty)
                                    + " 超过该批次可用库存 " + plain(batchAvailable)
                                    + "，请拆分到其他批次退货");
                }
            }
        }
    }

    /** BigDecimal 去掉多余的尾随 0，用于错误提示（避免显示 99.0000）。 */
    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** 写一条退货申请明细（create / update 共用）。 */
    private void insertApplyDetail(String applyId, Map<String, Object> line) {
        BigDecimal q = toBd(line.get("qty"));
        BigDecimal p = toBd(line.get("price"));
        BigDecimal a = line.get("_amount") instanceof BigDecimal bd
                ? bd : q.multiply(p).setScale(2, RoundingMode.HALF_UP);
        String detailId = "PRAD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO pur_return_apply_detail(detail_id, apply_id, goods_code, goods_name, spec, unit_name,
                    qty, price, amount, batch_no, production_date, tax_rate, remark,
                    return_mode, source_inbound_no, source_detail_id,
                    returnable_qty, cost_price, available_stock)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
                """, detailId, applyId,
                str(line.get("goodsCode")), str(line.get("goodsName")), str(line.get("spec")),
                str(line.get("unitName")),
                q, p, a,
                str(line.get("batchNo")), parseDate(line.get("productionDate"), null),
                strOrDefault(line.get("taxRate"), "13%"),
                strOrDefault(line.get("returnMode"), "BY_BILL"),
                emptyToNull(line.get("sourceInboundNo")),
                emptyToNull(line.get("sourceDetailId")),
                toBd(line.get("returnableQty")),
                toBd(line.get("costPrice")),
                toBd(line.get("availableStock")));
    }

    /** 空字符串转 NULL —— 源单字段在按品退货时应为 NULL 而非 ''。 */
    private static String emptyToNull(Object o) {
        String s = str(o);
        return s.isBlank() ? null : s;
    }

    private void recalcOutboundHead(String outboundId) {
        List<Map<String, Object>> sumRows = jdbcTemplate.queryForList(
                "SELECT COALESCE(SUM(qty), 0) AS total_qty, COALESCE(SUM(amount), 0) AS total_amount " +
                        "FROM pur_return_outbound_detail WHERE outbound_id = ?", outboundId);
        if (!sumRows.isEmpty()) {
            jdbcTemplate.update("UPDATE pur_return_outbound SET qty=?, amount=? WHERE outbound_id=?",
                    toBd(pick(sumRows.get(0), "total_qty")),
                    toBd(pick(sumRows.get(0), "total_amount")),
                    outboundId);
        }
    }

    private static String resolveReturnApplyStatusText(String st) {
        return switch (st) {
            case "DRAFT" -> "草稿";
            case "PENDING" -> "待审核";
            case "APPROVED" -> "已审核";
            case "OUTBOUNDED" -> "已出库";
            case "COMPLETED" -> "已完成";
            default -> st;
        };
    }

    /** 解析税率字符串，支持 "13%" / "13" / "0.13" 三种格式，返回小数（如 0.13）。 */
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

    /**
     * 价内税倒算：从<b>含税金额</b>中拆出税额。
     * <p>公式：{@code 税额 = 含税金额 × 税率 / (1 + 税率)}
     * <p>例：含税 260.00、税率 13% → 260 × 0.13 / 1.13 = 29.91，
     * 不含税金额 = 260 − 29.91 = 230.09，反向验算 230.09 × 1.13 ≈ 260.00 ✓
     * <p>采购退货单的商品金额是含税的（单价即含税单价），
     * 因此不能用 {@code 金额 × 税率}（那是价外税，适用于不含税金额）。
     */
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

    /** H2 大小写兼容 */
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