package com.erp.purchase;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 采购收货单 REST 端点。
 * <p>
 * 生命周期：
 * <ol>
 *   <li>入库单审核时由 {@link PurchaseController#auditInbound} 尾部调 {@link #generateFromInbound(String)}
 *       自动生成一张 PENDING 状态的收货单（按 goods_code 聚合入库明细的批次为一行）。</li>
 *   <li>收货单审核（{@link #auditReceipt}）时写 {@code fin_ap} 应付账款记录，并回写 {@code ap_status='已生成'}。</li>
 * </ol>
 * <p>
 * 税额策略（决策 A）：逐行按明细 {@code tax_rate}（从对应订单明细透传）算行税额，
 * 头部 {@code tax_amount = Σ 明细税额}。
 * <p>
 * 支持反审核：仅当 {@code fin_ap} 尚未收付款（{@code paid_amount = 0}）才允许反审核。
 */
@RestController
@RequestMapping("/purchase/receipt")
public class PurchaseReceiptController {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbcTemplate;
    private final com.erp.common.util.BillNoGenerator billNoGen;

    public PurchaseReceiptController(JdbcTemplate jdbcTemplate,
                                     com.erp.common.util.BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
    }

    // ============ 列表 & 详情 ============

    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT receipt_id, receipt_no, source_inbound_no, source_order_no,
                       supplier_code, supplier_name, warehouse, receipt_date,
                       goods_amount, tax_amount, expense_amount, final_amount,
                       ap_status, pay_status, status, creator_name,
                       audit_user, audit_time, create_time, remark
                FROM pur_receipt
                ORDER BY create_time DESC, receipt_no DESC
                """);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            String st = String.valueOf(row.getOrDefault("status", ""));
            row.put("statusText", switch (st) {
                case "PENDING" -> "待审核";
                case "APPROVED" -> "已审核";
                case "CANCELLED" -> "已作废";
                default -> st;
            });
            // 兼容前端模糊映射
            row.put("sourceBill", row.get("sourceInboundNo"));
            row.put("orderNo", row.get("sourceOrderNo"));
            // 不含税金额 = 含税商品金额 − 税额。final_amount 存的就是这个值，
            // 但语义变更前生成的老单据存的是「含税+税额」，这里统一现算保证口径一致。
            BigDecimal goodsAmount = toBd(pick(r, "goods_amount"));
            BigDecimal taxAmount = toBd(pick(r, "tax_amount"));
            row.put("untaxedAmount", goodsAmount.subtract(taxAmount).setScale(2, RoundingMode.HALF_UP));
            mapped.add(row);
        }
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @GetMapping("/detail")
    public ApiResponse<Map<String, Object>> detail(
            @RequestParam(required = false) String receiptId,
            @RequestParam(required = false) String id) {
        String key = receiptId != null && !receiptId.isBlank() ? receiptId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 receiptId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM pur_receipt WHERE receipt_id = ? OR receipt_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "收货单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        // 不含税金额现算（与列表同口径，兼容语义变更前的老单据）
        BigDecimal headGoodsAmount = toBd(head.get("goodsAmount"));
        BigDecimal headTaxAmount = toBd(head.get("taxAmount"));
        head.put("untaxedAmount", headGoodsAmount.subtract(headTaxAmount).setScale(2, RoundingMode.HALF_UP));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM pur_receipt_detail WHERE receipt_id = ? ORDER BY detail_id",
                head.get("receiptId"));
        head.put("details", details.stream().map(PurchaseReceiptController::camelize).toList());
        return ApiResponse.ok(head);
    }

    // ============ 审核 / 反审核 ============

    /** 审核：写一条 fin_ap，回写 ap_status='已生成'、status='APPROVED'。 */
    @PostMapping("/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditReceipt(@Valid @RequestBody AuditRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT receipt_id, receipt_no, supplier_name, goods_amount, tax_amount, final_amount, " +
                        "status, ap_status FROM pur_receipt WHERE receipt_id = ? OR receipt_no = ?",
                request.bizId(), request.bizId());
        if (rows.isEmpty()) throw new IllegalArgumentException("收货单不存在：" + request.bizId());
        Map<String, Object> receipt = rows.get(0);
        String status = str(pick(receipt, "status"));
        String apStatus = str(pick(receipt, "ap_status"));
        if (!"PENDING".equals(status)) throw new IllegalArgumentException("收货单已审核或已作废，当前状态：" + status);
        if ("已生成".equals(apStatus)) throw new IllegalArgumentException("该收货单已生成应付账款，无需重复审核");

        String receiptId = str(pick(receipt, "receipt_id"));
        String receiptNo = str(pick(receipt, "receipt_no"));
        String supplier = str(pick(receipt, "supplier_name"));
        // 应付按「含税商品金额」——供应商实际要收的是含税货款。
        // goods_amount 为含税金额；final_amount 是拆出税额后的不含税金额，不用于结算。
        BigDecimal apAmount = toBd(pick(receipt, "goods_amount"));

        // 改价同步：把收货单单价回写到来源采购入库单，并按差额重算库存成本
        String priceSyncEffect = syncPriceToInbound(receiptId, receiptNo);

        // 写 fin_ap（source_bill 存收货单号，未来付款单核销时按 source_bill 查）
        // 到期日期用 H2/PostgreSQL/MySQL 均兼容的 DATEADD 函数（等价 MySQL DATE_ADD(..., INTERVAL 30 DAY)）。
        String apId = "AP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String apNo = billNoGen.nextNo("AP", "fin_ap", "ap_no");
        jdbcTemplate.update("""
                INSERT INTO fin_ap(ap_id, ap_no, source_bill, supplier, ap_amount, paid_amount, unpaid_amount, due_date, status)
                VALUES (?, ?, ?, ?, ?, 0, ?, DATEADD('DAY', 30, CURRENT_DATE), 'UNVERIFIED')
                """, apId, apNo, receiptNo, supplier, apAmount, apAmount);

        jdbcTemplate.update("""
                UPDATE pur_receipt
                SET status = 'APPROVED', ap_status = '已生成',
                    audit_user = ?, audit_time = CURRENT_TIMESTAMP
                WHERE receipt_id = ?
                """, "系统管理员", receiptId);

        log("purchase.receipt", "AUDIT", receiptNo, "采购收货单审核 → 生成应付 " + apNo
                + (priceSyncEffect.isBlank() ? "" : "；" + priceSyncEffect));
        return ApiResponse.ok(Map.of(
                "receiptId", receiptId,
                "receiptNo", receiptNo,
                "status", "APPROVED",
                "apNo", apNo,
                "effect", "已按含税商品金额生成应付账款"
                        + (priceSyncEffect.isBlank() ? "" : "；" + priceSyncEffect)));
    }

    /**
     * 反审核：仅当对应 fin_ap 未收付款（paid_amount = 0）才允许。
     * 反审核时删除该条 fin_ap，将 ap_status 恢复为「未生成」、status 恢复为 PENDING。
     */
    @PostMapping("/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAudit(@Valid @RequestBody AuditRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT receipt_id, receipt_no, status FROM pur_receipt WHERE receipt_id = ? OR receipt_no = ?",
                request.bizId(), request.bizId());
        if (rows.isEmpty()) throw new IllegalArgumentException("收货单不存在");
        String status = str(pick(rows.get(0), "status"));
        if (!"APPROVED".equals(status)) throw new IllegalArgumentException("只有已审核收货单可反审核");

        String receiptId = str(pick(rows.get(0), "receipt_id"));
        String receiptNo = str(pick(rows.get(0), "receipt_no"));

        // 检查关联 fin_ap 是否已有付款
        List<Map<String, Object>> apRows = jdbcTemplate.queryForList(
                "SELECT ap_id, ap_no, paid_amount FROM fin_ap WHERE source_bill = ?", receiptNo);
        for (Map<String, Object> ap : apRows) {
            if (toBd(pick(ap, "paid_amount")).signum() > 0) {
                throw new IllegalArgumentException("已有付款记录，无法反审核");
            }
        }
        // 删除应付记录
        jdbcTemplate.update("DELETE FROM fin_ap WHERE source_bill = ?", receiptNo);
        jdbcTemplate.update("""
                UPDATE pur_receipt
                SET status = 'PENDING', ap_status = '未生成',
                    audit_user = NULL, audit_time = NULL
                WHERE receipt_id = ?
                """, receiptId);

        log("purchase.receipt", "REVERSE_AUDIT", receiptNo, "采购收货单反审核 → 撤销应付");
        return ApiResponse.ok(Map.of("receiptId", receiptId, "status", "PENDING", "effect", "已反审核，应付账款已撤销"));
    }

    // ============ 编辑（未审核收货单改价） ============

    /**
     * 修改未审核收货单的<b>单价</b>（金额随之重算）。
     * <p>只开放单价 —— 数量由仓库实收决定，不应在结算环节改；
     * 税率从采购订单透传，也不在此处改。
     * <p>改价只落在收货单上；<b>审核时</b>才会回写采购入库单单价并重算库存成本
     * （见 {@link #syncPriceToInbound}），保证未审核期间可反复改价而不污染库存。
     * <p>请求：{@code { receiptId, details: [{ detailId, price }] }}
     */
    @PostMapping("/update")
    @Transactional
    public ApiResponse<Map<String, Object>> updateReceipt(@RequestBody Map<String, Object> request) {
        String key = strOrDefault(request.get("receiptId"), str(request.get("bizId")));
        if (key.isBlank()) throw new IllegalArgumentException("缺少 receiptId");

        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT receipt_id, receipt_no, status FROM pur_receipt WHERE receipt_id = ? OR receipt_no = ?",
                key, key);
        if (heads.isEmpty()) throw new IllegalArgumentException("收货单不存在：" + key);
        String status = str(pick(heads.get(0), "status"));
        if (!"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅未审核的收货单可修改，当前状态：" + status);
        }
        String receiptId = str(pick(heads.get(0), "receipt_id"));
        String receiptNo = str(pick(heads.get(0), "receipt_no"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        if (reqDetails.isEmpty()) {
            return ApiResponse.ok(GenericResult.row("receiptId", receiptId, "effect", "无变更"));
        }

        // 逐行更新单价 → 重算金额与行税额（价内税倒算）
        for (Map<String, Object> line : reqDetails) {
            String detailId = str(line.get("detailId"));
            if (detailId.isBlank()) continue;
            BigDecimal newPrice = toBd(line.get("price"));
            if (newPrice.signum() < 0) throw new IllegalArgumentException("单价不能为负");

            List<Map<String, Object>> dRows = jdbcTemplate.queryForList(
                    "SELECT qty, tax_rate FROM pur_receipt_detail WHERE detail_id = ? AND receipt_id = ?",
                    detailId, receiptId);
            if (dRows.isEmpty()) continue;
            BigDecimal qty = toBd(pick(dRows.get(0), "qty"));
            String taxRate = str(pick(dRows.get(0), "tax_rate"));
            BigDecimal newAmount = qty.multiply(newPrice).setScale(2, RoundingMode.HALF_UP);
            BigDecimal newTax = taxInclusiveTax(newAmount, parseTaxRate(taxRate));

            jdbcTemplate.update("""
                    UPDATE pur_receipt_detail SET price = ?, amount = ?, tax_amount = ?
                    WHERE detail_id = ? AND receipt_id = ?
                    """, newPrice, newAmount, newTax, detailId, receiptId);
        }

        // 重算头部合计
        recalcReceiptHead(receiptId);

        log("purchase.receipt", "UPDATE", receiptNo, "修改采购收货单单价");
        return ApiResponse.ok(GenericResult.row("receiptId", receiptId, "effect", "已更新单价与金额"));
    }

    /** 按明细重算收货单头部：商品金额（含税）/ 税额 / 不含税金额。 */
    private void recalcReceiptHead(String receiptId) {
        List<Map<String, Object>> sums = jdbcTemplate.queryForList("""
                SELECT COALESCE(SUM(amount), 0) AS goods_amount, COALESCE(SUM(tax_amount), 0) AS tax_amount
                FROM pur_receipt_detail WHERE receipt_id = ?
                """, receiptId);
        if (sums.isEmpty()) return;
        BigDecimal goodsAmount = toBd(pick(sums.get(0), "goods_amount"));
        BigDecimal taxAmount = toBd(pick(sums.get(0), "tax_amount"));
        jdbcTemplate.update("""
                UPDATE pur_receipt SET goods_amount = ?, tax_amount = ?, final_amount = ?
                WHERE receipt_id = ?
                """, goodsAmount, taxAmount, goodsAmount.subtract(taxAmount), receiptId);
    }

    /**
     * 收货单审核时把单价回写到来源<b>采购入库单</b>，并按差额重算库存成本。
     *
     * <p>处理步骤（仅对单价确实变化的商品）：
     * <ol>
     *   <li>更新 {@code pur_inbound_detail} 的 {@code price / amount / after_cost}</li>
     *   <li>更新 {@code pur_inbound} 头部 {@code amount} 合计</li>
     *   <li>按差额调整 {@code inv_batch_stock} 与 {@code inv_stock_balance} 的成本单价与库存金额</li>
     *   <li>写一条金额差额的库存流水（方向 ADJUST），留痕可追溯</li>
     *   <li>同步 {@code base_goods.latest_purchase_price}</li>
     * </ol>
     *
     * <p><b>口径说明</b>：只按<em>当前仍在库的数量</em>调整库存金额 ——
     * 已销售出库那部分的成本已结转到销售成本，不在此回溯
     * （完整回溯是【采购改价单】模块的职责，见 PRD 模块 33）。
     *
     * @return 人类可读的影响说明；无改价时返回空串
     */
    private String syncPriceToInbound(String receiptId, String receiptNo) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT source_inbound_no, warehouse FROM pur_receipt WHERE receipt_id = ?", receiptId);
        if (heads.isEmpty()) return "";
        String inboundNo = str(pick(heads.get(0), "source_inbound_no"));
        if (inboundNo.isBlank()) return "";

        List<Map<String, Object>> inbRows = jdbcTemplate.queryForList(
                "SELECT inbound_id, warehouse, status FROM pur_inbound WHERE inbound_no = ?", inboundNo);
        if (inbRows.isEmpty()) return "";
        String inboundId = str(pick(inbRows.get(0), "inbound_id"));
        String warehouse = str(pick(inbRows.get(0), "warehouse"));
        boolean stockBooked = "APPROVED".equals(str(pick(inbRows.get(0), "status")));

        // 收货单明细是按 goods_code 聚合的 → 取每个商品的新单价
        Map<String, BigDecimal> newPriceByGoods = new HashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT goods_code, price FROM pur_receipt_detail WHERE receipt_id = ?", receiptId)) {
            newPriceByGoods.put(str(pick(r, "goods_code")), toBd(pick(r, "price")));
        }
        if (newPriceByGoods.isEmpty()) return "";

        int changedLines = 0;
        BigDecimal totalDiff = BigDecimal.ZERO;

        // 逐条入库明细比对单价（同商品多批次时每行都要改）
        for (Map<String, Object> d : jdbcTemplate.queryForList("""
                SELECT detail_id, goods_code, goods_name, batch_no, received_qty, price
                FROM pur_inbound_detail WHERE inbound_id = ?
                """, inboundId)) {
            String goodsCode = str(pick(d, "goods_code"));
            BigDecimal newPrice = newPriceByGoods.get(goodsCode);
            if (newPrice == null) continue;
            BigDecimal oldPrice = toBd(pick(d, "price"));
            if (newPrice.compareTo(oldPrice) == 0) continue;   // 未改价，跳过

            String detailId = str(pick(d, "detail_id"));
            String goodsName = str(pick(d, "goods_name"));
            String batchNo = str(pick(d, "batch_no"));
            BigDecimal qty = toBd(pick(d, "received_qty"));
            BigDecimal newAmount = qty.multiply(newPrice).setScale(2, RoundingMode.HALF_UP);
            BigDecimal diffAmount = newAmount.subtract(qty.multiply(oldPrice)).setScale(2, RoundingMode.HALF_UP);

            // 1) 回写入库明细单价/金额/入库后成本
            jdbcTemplate.update("""
                    UPDATE pur_inbound_detail SET price = ?, amount = ?, after_cost = ?
                    WHERE detail_id = ?
                    """, newPrice, newAmount, newPrice, detailId);

            changedLines++;
            totalDiff = totalDiff.add(diffAmount);

            // 2) 库存成本重算（仅当入库单已审核、库存已入账）
            if (stockBooked && diffAmount.signum() != 0) {
                adjustStockCost(goodsCode, goodsName, warehouse, batchNo, qty, newPrice, diffAmount, receiptNo);
            }

            // 3) 同步参考进价
            jdbcTemplate.update("UPDATE base_goods SET latest_purchase_price = ? WHERE goods_code = ?",
                    newPrice, goodsCode);
        }

        if (changedLines == 0) return "";

        // 4) 重算入库单头部金额
        jdbcTemplate.update("""
                UPDATE pur_inbound SET amount = (
                    SELECT COALESCE(SUM(amount), 0) FROM pur_inbound_detail WHERE inbound_id = ?
                ) WHERE inbound_id = ?
                """, inboundId, inboundId);

        return "已同步 " + changedLines + " 条明细单价到入库单 " + inboundNo
                + (stockBooked ? "，库存成本差额 " + plain(totalDiff) : "（入库单未审核，库存成本无需调整）");
    }

    /**
     * 按改价差额调整库存成本。
     * <p>批次层：该批次仍在库数量按新单价重估（批次成本 = 该次入库单价，一批一价）。
     * <p>商品仓库层：{@code stock_amount += 差额（按仍在库比例）}，
     * 成本单价 = 新库存金额 / 库存数量（移动加权平均口径）。
     */
    private void adjustStockCost(String goodsCode, String goodsName, String warehouse, String batchNo,
                                 BigDecimal inboundQty, BigDecimal newPrice,
                                 BigDecimal diffAmount, String sourceBill) {
        // 批次层：仍在库数量
        BigDecimal batchQty = BigDecimal.ZERO;
        if (!batchNo.isBlank()) {
            List<Map<String, Object>> bs = jdbcTemplate.queryForList("""
                    SELECT qty FROM inv_batch_stock
                    WHERE goods_code = ? AND warehouse = ? AND batch_no = ?
                    """, goodsCode, warehouse, batchNo);
            if (!bs.isEmpty()) {
                batchQty = toBd(pick(bs.get(0), "qty"));
                // 批次成本直接按新单价（该批次就是这次入库的货）
                jdbcTemplate.update("""
                        UPDATE inv_batch_stock SET cost_price = ?, stock_amount = ? * ?
                        WHERE goods_code = ? AND warehouse = ? AND batch_no = ?
                        """, newPrice, batchQty, newPrice, goodsCode, warehouse, batchNo);
            }
        }

        // 商品仓库层：按「仍在库 / 入库数量」比例分摊差额，避免把已出库部分的成本也调进来
        List<Map<String, Object>> sb = jdbcTemplate.queryForList("""
                SELECT balance_id, physical_qty, stock_amount FROM inv_stock_balance
                WHERE goods_code = ? AND warehouse = ?
                """, goodsCode, warehouse);
        if (sb.isEmpty()) return;

        BigDecimal physicalQty = toBd(pick(sb.get(0), "physical_qty"));
        BigDecimal stockAmount = toBd(pick(sb.get(0), "stock_amount"));
        // 在库比例：批次仍在库量 / 本次入库量（批次为空时退化为全额调整）
        BigDecimal ratio = BigDecimal.ONE;
        if (!batchNo.isBlank() && inboundQty.signum() > 0) {
            ratio = batchQty.divide(inboundQty, 6, RoundingMode.HALF_UP);
            if (ratio.compareTo(BigDecimal.ONE) > 0) ratio = BigDecimal.ONE;
        }
        BigDecimal effectiveDiff = diffAmount.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        if (effectiveDiff.signum() == 0) return;

        BigDecimal newStockAmount = stockAmount.add(effectiveDiff).setScale(2, RoundingMode.HALF_UP);
        if (newStockAmount.signum() < 0) newStockAmount = BigDecimal.ZERO;
        BigDecimal newCostPrice = physicalQty.signum() > 0
                ? newStockAmount.divide(physicalQty, 4, RoundingMode.HALF_UP)
                : newPrice;

        jdbcTemplate.update("""
                UPDATE inv_stock_balance SET cost_price = ?, stock_amount = ?, last_inout_time = CURRENT_TIMESTAMP
                WHERE balance_id = ?
                """, newCostPrice, newStockAmount, str(pick(sb.get(0), "balance_id")));

        // 写成本调整流水（数量 0、金额为差额），便于追溯
        jdbcTemplate.update("""
                INSERT INTO inv_stock_ledger(ledger_id, ledger_no, occurred_at, source_bill,
                    goods_code, goods_name, warehouse, batch_no, direction, qty, cost_price, amount,
                    balance_qty, operator_name)
                VALUES (?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, '成本调整', 0, ?, ?, ?, '系统管理员')
                """,
                "SL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                "ADJ" + System.currentTimeMillis(),
                sourceBill, goodsCode, goodsName, warehouse, batchNo,
                newCostPrice, effectiveDiff, physicalQty);
    }

    /** BigDecimal 去掉多余尾随 0，用于提示文案。 */
    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String strOrDefault(Object o, String dft) {
        if (o == null) return dft;
        String s = String.valueOf(o);
        return s.isBlank() ? dft : s;
    }

    // ============ 供 PurchaseController 调用：入库单审核后自动生成 ============

    /**
     * 由 {@link PurchaseController#auditInbound} 在入库审核成功后调用。
     * 幂等：如果该 inbound 已经生成过（{@code source_inbound_no} 唯一约束），忽略并返回已存在的收货单号。
     *
     * @param inboundId 入库单 ID
     * @return 生成的收货单号
     */
    @Transactional
    public String generateFromInbound(String inboundId) {
        List<Map<String, Object>> inboundRows = jdbcTemplate.queryForList("""
                SELECT inbound_id, inbound_no, source_order, supplier, warehouse, bill_date
                FROM pur_inbound WHERE inbound_id = ?
                """, inboundId);
        if (inboundRows.isEmpty()) throw new IllegalArgumentException("入库单不存在：" + inboundId);
        Map<String, Object> inbound = inboundRows.get(0);
        String inboundNo = str(pick(inbound, "inbound_no"));
        String sourceOrderNo = str(pick(inbound, "source_order"));
        String supplierName = str(pick(inbound, "supplier"));
        String warehouse = str(pick(inbound, "warehouse"));

        // 幂等：已存在则直接返回
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT receipt_no FROM pur_receipt WHERE source_inbound_no = ?",
                String.class, inboundNo);
        if (!existing.isEmpty()) return existing.get(0);

        // 从订单反查 supplier_code 和 每商品的 tax_rate（决策 A：逐行税率）
        String supplierCode = "";
        Map<String, String> taxRateByGoods = new HashMap<>();
        if (!sourceOrderNo.isBlank()) {
            List<Map<String, Object>> orderRows = jdbcTemplate.queryForList(
                    "SELECT order_id, supplier_code FROM purchase_order WHERE order_no = ?",
                    sourceOrderNo);
            if (!orderRows.isEmpty()) {
                supplierCode = str(pick(orderRows.get(0), "supplier_code"));
                String orderId = str(pick(orderRows.get(0), "order_id"));
                for (Map<String, Object> od : jdbcTemplate.queryForList(
                        "SELECT goods_code, tax_rate FROM purchase_order_detail WHERE order_id = ?", orderId)) {
                    taxRateByGoods.put(str(pick(od, "goods_code")), str(pick(od, "tax_rate")));
                }
            }
        }

        // 按 goods_code 聚合入库明细（合并所有批次为一行）
        List<Map<String, Object>> aggregated = jdbcTemplate.queryForList("""
                SELECT goods_code, MIN(goods_name) AS goods_name, MIN(unit_name) AS unit_name,
                       SUM(received_qty) AS qty, MIN(price) AS price, SUM(amount) AS amount
                FROM pur_inbound_detail
                WHERE inbound_id = ?
                GROUP BY goods_code
                """, inboundId);

        // 逐行按 tax_rate 算行税额（价内税倒算）；头部 tax_amount = Σ
        BigDecimal totalGoodsAmount = BigDecimal.ZERO;
        BigDecimal totalTaxAmount = BigDecimal.ZERO;
        List<Map<String, Object>> detailRowsToInsert = new ArrayList<>();
        for (Map<String, Object> line : aggregated) {
            String goodsCode = str(pick(line, "goods_code"));
            // amount 是「含税金额」（单价为含税单价，qty × price 即含税）
            BigDecimal lineAmount = toBd(pick(line, "amount")).setScale(2, RoundingMode.HALF_UP);
            String taxRate = taxRateByGoods.getOrDefault(goodsCode, "13%");
            BigDecimal taxPct = parseTaxRate(taxRate);
            BigDecimal lineTax = taxInclusiveTax(lineAmount, taxPct);

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
            detailRowsToInsert.add(row);

            totalGoodsAmount = totalGoodsAmount.add(lineAmount);
            totalTaxAmount = totalTaxAmount.add(lineTax);
        }
        // final_amount 语义：不含税金额 = 含税商品金额 − 税额
        // （应付结算按含税的 goods_amount 走，见 auditReceipt）
        BigDecimal finalAmount = totalGoodsAmount.subtract(totalTaxAmount);

        // 写头部
        String receiptId = "PR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String receiptNo = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.PURCHASE_RECEIPT, "pur_receipt", "receipt_no");
        LocalDate receiptDate = parseDate(pick(inbound, "bill_date"), LocalDate.now());

        jdbcTemplate.update("""
                INSERT INTO pur_receipt (receipt_id, receipt_no, source_inbound_no, source_order_no,
                    supplier_code, supplier_name, warehouse, receipt_date,
                    goods_amount, tax_amount, expense_amount, final_amount,
                    ap_status, pay_status, status, creator_name, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, '未生成', '未付款', 'PENDING', '系统', NULL)
                """,
                receiptId, receiptNo, inboundNo, sourceOrderNo,
                supplierCode, supplierName, warehouse, receiptDate,
                totalGoodsAmount, totalTaxAmount, finalAmount);

        // 写明细
        for (Map<String, Object> row : detailRowsToInsert) {
            jdbcTemplate.update("""
                    INSERT INTO pur_receipt_detail (detail_id, receipt_id, goods_code, goods_name,
                        unit_name, qty, price, amount, tax_rate, tax_amount, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    """,
                    row.get("detailId"), receiptId,
                    row.get("goodsCode"), row.get("goodsName"), row.get("unitName"),
                    row.get("qty"), row.get("price"), row.get("amount"),
                    row.get("taxRate"), row.get("taxAmount"));
        }

        log("purchase.receipt", "GENERATE", receiptNo, "入库单 " + inboundNo + " 自动生成收货单");
        return receiptNo;
    }

    // ============ 工具方法 ============

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
     * <p>例：含税 520.00、税率 13% → 520 × 0.13 / 1.13 = 59.82，
     * 不含税金额 = 520 − 59.82 = 460.18，反向验算 460.18 × 1.13 ≈ 520.00 ✓
     * <p>采购收货单的商品金额是含税的（单价即含税单价），
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
