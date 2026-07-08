package com.erp.sales;

import com.erp.common.api.ApiResponse;
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
 * 销售发货单 REST 端点（对称于 {@link com.erp.purchase.PurchaseReceiptController}）。
 *
 * <p>生命周期：
 * <ol>
 *   <li>出库单审核末尾调用 {@link #generateFromOutbound(String)}
 *       —— 按 {@code goods_code} 聚合出库明细为 1 行，逐行按订单税率算行税额。</li>
 *   <li>{@code POST /sales/receipt/sign} → 客户确认签收，逐行登记签收/拒收数量；
 *       有拒收时自动生成拒收入库单（{@link RejectInboundController#generateFromReceipt}）。</li>
 *   <li>{@code POST /sales/receipt/audit} → 写 {@code fin_ar}，{@code ar_status='已生成'}。</li>
 *   <li>{@code POST /sales/receipt/reverse-audit} → 仅当关联 {@code fin_ar.received_amount = 0} 才允许，删 fin_ar 恢复 PENDING。</li>
 * </ol>
 *
 * <p>决策：{@code source_outbound_no} 唯一约束保证 1:1；重复调用返回已存在发货单号（幂等）。
 *
 * <p><b>金额口径（V52 改版）</b>—— 分两个时点，与采购收货单完全对齐（单价含税、税额价内倒算、结算取含税）：
 * <table border="1">
 *   <tr><th>字段</th><th>算法</th><th>时点</th></tr>
 *   <tr><td>{@code deliver_amount} 发货金额</td><td>Σ 明细 {@code qty × price}（含税）</td>
 *       <td><b>出库单审核</b>生成发货单时定死，之后不再变</td></tr>
 *   <tr><td>{@code sign_amount} 签收金额</td><td>Σ 明细 {@code signed_qty × price}（含税）</td>
 *       <td>签收时汇总，生单默认 0</td></tr>
 *   <tr><td>{@code reject_amount} 拒收金额</td><td>Σ 明细 {@code reject_qty × price}</td>
 *       <td>签收时汇总，生单默认 0</td></tr>
 *   <tr><td>{@code tax_amount} 税额</td><td>签收金额 × 税率 ÷ (1+税率)（<b>价内倒算</b>）</td>
 *       <td>签收时算，生单默认 0</td></tr>
 *   <tr><td>{@code untaxed_amount} 不含税金额</td><td>签收金额 − 税额</td>
 *       <td>签收时算，生单默认 0</td></tr>
 * </table>
 * {@code fin_ar.ar_amount} 取<b>签收金额（含税）</b>，与采购应付取含税 {@code goods_amount} 对称。
 * {@code expense_amount}（费用金额）仍是 V1.0 占位 0，<b>不参与</b>上述任何公式。
 *
 * <p>注意 {@code receive_status} 是<b>收款</b>状态（未收款/已收款），
 * <b>签收</b>状态是 V50 新增的 {@code sign_status}（待签收/已签收/部分拒收/全部拒收），两者不要混。
 */
@RestController
@RequestMapping("/sales/receipt")
public class SalesReceiptController {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbcTemplate;
    private final com.erp.common.util.BillNoGenerator billNoGen;
    private final RejectInboundController rejectInboundController;

    public SalesReceiptController(JdbcTemplate jdbcTemplate,
                                  com.erp.common.util.BillNoGenerator billNoGen,
                                  RejectInboundController rejectInboundController) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
        this.rejectInboundController = rejectInboundController;
    }

    // ============ 列表 & 详情 ============

    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT r.receipt_id, r.receipt_no, r.source_outbound_no, r.source_order_no,
                       r.customer_code, r.customer_name, r.warehouse, r.driver, r.receipt_date,
                       r.deliver_amount, r.sign_amount, r.reject_amount,
                       r.tax_amount, r.expense_amount, r.untaxed_amount,
                       r.ar_status, r.receive_status, r.status, r.creator_name,
                       r.sign_status, r.sign_time, r.sign_user, r.reject_generated,
                       r.audit_user, r.audit_time, r.create_time, r.remark,
                       COALESCE(a.reconcile_status, '未对账') AS reconcile_status,
                       (SELECT MIN(ri.inbound_no) FROM inv_reject_inbound ri
                        WHERE ri.source_receipt_no = r.receipt_no) AS reject_inbound_no
                FROM sales_receipt r
                LEFT JOIN fin_ar a ON a.source_bill = r.receipt_no
                ORDER BY r.create_time DESC, r.receipt_no DESC
                """);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            String st = str(row.getOrDefault("status", ""));
            row.put("statusText", switch (st) {
                case "PENDING" -> "待审核";
                case "APPROVED" -> "已审核";
                case "CANCELLED" -> "已作废";
                default -> st;
            });
            String ar = str(row.getOrDefault("arStatus", ""));
            row.put("arStatusText", "已生成".equals(ar) ? "已生成" : "未生成");
            String rs = str(row.getOrDefault("receiveStatus", ""));
            row.put("receiveStatusText", "已签收".equals(rs) ? "已签收" : "PENDING".equals(rs) ? "待签收" : rs);
            String rcs = str(row.getOrDefault("reconcileStatus", ""));
            row.put("reconcileStatusText", rcs.isEmpty() || "未对账".equals(rcs) ? "未对账" : rcs);
            String signSt = str(row.getOrDefault("signStatus", ""));
            row.put("signStatusText", signSt.isEmpty() ? "待签收" : signSt);
            row.put("sourceBill", row.get("sourceOutboundNo"));
            row.put("orderNo", row.get("sourceOrderNo"));
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
        // 显式列清单：主单 reject_qty 不再对外返回（列表/详情展示「拒收金额」），避免 SELECT * 又把它漏出去
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT receipt_id, receipt_no, source_outbound_no, source_order_no,
                       customer_code, customer_name, warehouse, driver, receipt_date,
                       deliver_amount, sign_amount, reject_amount,
                       tax_amount, expense_amount, untaxed_amount,
                       ar_status, receive_status, status, creator_name,
                       sign_status, sign_time, sign_user, reject_generated,
                       audit_user, audit_time, create_time, remark,
                       dispatch_status, dispatch_id, trip_id
                FROM sales_receipt WHERE receipt_id = ? OR receipt_no = ?
                """, key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "销售发货单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        head.put("signStatusText", str(head.getOrDefault("signStatus", "")).isBlank()
                ? "待签收" : head.get("signStatus"));
        List<String> rejectNos = jdbcTemplate.queryForList(
                "SELECT inbound_no FROM inv_reject_inbound WHERE source_receipt_no = ?",
                String.class, str(head.get("receiptNo")));
        head.put("rejectInboundNo", rejectNos.isEmpty() ? null : rejectNos.get(0));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM sales_receipt_detail WHERE receipt_id = ? ORDER BY detail_id",
                head.get("receiptId"));
        head.put("details", details.stream().map(SalesReceiptController::camelize).toList());
        return ApiResponse.ok(head);
    }

    // ============ 审核 / 反审核 ============

    /**
     * 发货单审核 → 生成应收。
     *
     * <p><b>通常不需要手工调用</b>：确认签收（{@link #sign}）成功后会<b>自动审核</b>，
     * 因为应收金额要按签收数量算，必须等签收登记完才有正确金额。
     * 本端点保留给「已签收但自动审核失败」之类的补救场景，两个前置校验和自动审核完全一致。
     */
    @PostMapping("/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditReceipt(@Valid @RequestBody AuditRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT receipt_id, receipt_no, customer_name, sign_amount, status, ar_status, sign_status " +
                        "FROM sales_receipt WHERE receipt_id = ? OR receipt_no = ?",
                request.bizId(), request.bizId());
        if (rows.isEmpty()) throw new IllegalArgumentException("销售发货单不存在：" + request.bizId());
        Map<String, Object> receipt = rows.get(0);
        String status = str(pick(receipt, "status"));
        String arStatus = str(pick(receipt, "ar_status"));
        String signStatus = str(pick(receipt, "sign_status"));
        if (!"PENDING".equals(status)) throw new IllegalArgumentException("发货单已审核或已作废，当前状态：" + status);
        if ("已生成".equals(arStatus)) throw new IllegalArgumentException("该发货单已生成应收账款，无需重复审核");
        // 应收按签收数量算，所以必须先签收 —— 否则会按发货数量把拒收的货也开票给客户
        if (signStatus.isBlank() || "待签收".equals(signStatus)) {
            throw new IllegalArgumentException("请先确认签收再审核：应收金额按客户实际签收数量生成");
        }
        if ("全部拒收".equals(signStatus)) {
            throw new IllegalArgumentException("该发货单全部拒收，签收金额为 0，无应收可生成");
        }

        String receiptId = str(pick(receipt, "receipt_id"));
        String receiptNo = str(pick(receipt, "receipt_no"));
        String customer = str(pick(receipt, "customer_name"));
        // 应收取「签收金额」（含税），不是不含税金额
        BigDecimal signAmount = toBd(pick(receipt, "sign_amount"));
        if (signAmount.signum() <= 0) {
            throw new IllegalArgumentException("该发货单签收金额为 0，无应收可生成");
        }

        String arNo = approveAndGenerateAr(receiptId, receiptNo, customer, signAmount);

        log("sales.receipt", "AUDIT", receiptNo, "销售发货单审核 → 生成应收 " + arNo);
        return ApiResponse.ok(Map.of(
                "receiptId", receiptId,
                "receiptNo", receiptNo,
                "status", "APPROVED",
                "arNo", arNo,
                "effect", "已按签收金额生成应收账款"));
    }

    /**
     * 写 fin_ar + 把发货单置为 APPROVED / ar_status='已生成'，返回应收单号。
     *
     * <p>{@code signAmount} 必须是<b>签收金额（含税）</b>（{@link #sign} 已按签收数量汇总过主单金额），
     * 与采购应付取含税 {@code goods_amount} 对称。不含税金额只用于展示/开票，不进应收。
     * {@code source_bill} 存发货单号，收款单核销时按它反查。
     */
    private String approveAndGenerateAr(String receiptId, String receiptNo, String customer, BigDecimal signAmount) {
        String arId = "AR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String arNo = billNoGen.nextNo("AR", "fin_ar", "ar_no");
        jdbcTemplate.update("""
                INSERT INTO fin_ar(ar_id, ar_no, source_bill, customer, salesman, ar_amount,
                    received_amount, unreceived_amount, due_date, overdue_days, invoice_status, status)
                VALUES (?, ?, ?, ?, NULL, ?, 0, ?, DATEADD('DAY', 30, CURRENT_DATE), 0, '未开票', 'UNVERIFIED')
                """, arId, arNo, receiptNo, customer, signAmount, signAmount);

        jdbcTemplate.update("""
                UPDATE sales_receipt
                SET status = 'APPROVED', ar_status = '已生成',
                    audit_user = ?, audit_time = CURRENT_TIMESTAMP
                WHERE receipt_id = ?
                """, "系统管理员", receiptId);
        return arNo;
    }

    @PostMapping("/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAudit(@Valid @RequestBody AuditRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT receipt_id, receipt_no, status FROM sales_receipt WHERE receipt_id = ? OR receipt_no = ?",
                request.bizId(), request.bizId());
        if (rows.isEmpty()) throw new IllegalArgumentException("发货单不存在");
        String status = str(pick(rows.get(0), "status"));
        if (!"APPROVED".equals(status)) throw new IllegalArgumentException("只有已审核发货单可反审核");

        String receiptId = str(pick(rows.get(0), "receipt_id"));
        String receiptNo = str(pick(rows.get(0), "receipt_no"));

        // 检查关联 fin_ar 是否已收款
        List<Map<String, Object>> arRows = jdbcTemplate.queryForList(
                "SELECT ar_id, ar_no, received_amount FROM fin_ar WHERE source_bill = ?", receiptNo);
        for (Map<String, Object> ar : arRows) {
            if (toBd(pick(ar, "received_amount")).signum() > 0) {
                throw new IllegalArgumentException("已有收款记录，无法反审核");
            }
        }
        jdbcTemplate.update("DELETE FROM fin_ar WHERE source_bill = ?", receiptNo);
        jdbcTemplate.update("""
                UPDATE sales_receipt
                SET status = 'PENDING', ar_status = '未生成',
                    audit_user = NULL, audit_time = NULL
                WHERE receipt_id = ?
                """, receiptId);

        log("sales.receipt", "REVERSE_AUDIT", receiptNo, "销售发货单反审核 → 撤销应收");
        return ApiResponse.ok(Map.of("receiptId", receiptId, "status", "PENDING", "effect", "已反审核，应收账款已撤销"));
    }

    // ============ 签收 / 撤销签收 ============

    /**
     * 确认签收：逐行登记客户实际签收数量与拒收数量。
     *
     * <p>请求体：{@code { receiptId, remark, details: [{ detailId, signedQty, rejectQty, rejectReason }] }}
     * <p>每行 {@code signedQty + rejectQty} 必须等于发货数量 {@code qty}；只传 {@code rejectQty} 时
     * {@code signedQty} 自动按 {@code qty - rejectQty} 补齐。拒收数量大于 0 的行必须填拒收原因。
     *
     * <p>只要有一行拒收，签收成功后<b>自动生成拒收入库单</b>（PENDING，待仓库审核入库）。
     *
     * <p><b>签收时汇总金额，并自动审核生成应收</b>：拒收的货客户没收到，不能开票给客户。
     * 所以签收登记完后：
     * <ol>
     *   <li>明细写 {@code sign_amount = signed_qty × price}（含税）、
     *       {@code reject_amount = reject_qty × price}、
     *       {@code tax_amount = sign_amount × 税率 ÷ (1+税率)}（<b>价内税倒算</b>，与采购一致）；
     *       明细的 {@code amount} 是<b>发货金额</b>，签收<b>不改它</b>；</li>
     *   <li>主单汇总写 {@code sign_amount / reject_amount / tax_amount}，
     *       {@code untaxed_amount = sign_amount - tax_amount}；
     *       {@code deliver_amount}（发货金额，出库审核时定死）<b>不动</b>；</li>
     *   <li>随即<b>自动审核</b>（{@code status='APPROVED'}、{@code ar_status='已生成'}）并按
     *       <b>{@code sign_amount}（含税）</b>写 {@code fin_ar}。应收天然只含签收部分，<b>不需要再冲减</b>。</li>
     * </ol>
     * 明细的 {@code qty} 仍是<b>发货数量</b>，配合 {@code signed_qty / reject_qty} 保留完整发货记录。
     *
     * <p><b>全部拒收</b>时签收金额为 0，不生成应收、<b>不自动审核</b>，发货单停在 PENDING
     * （没有开票对象；后续走拒收入库 + 作废/重发）。
     *
     * <p>撤销签收（{@link #unsign}）会把这些全部回滚：删应收、恢复 PENDING、
     * 签收/拒收/税额/不含税金额全部归 0（回到生单口径）。
     */
    @PostMapping("/sign")
    @Transactional
    public ApiResponse<Map<String, Object>> sign(@RequestBody Map<String, Object> request) {
        String key = str(request.get("receiptId"));
        if (key.isBlank()) key = str(request.get("bizId"));
        if (key.isBlank()) throw new IllegalArgumentException("缺少 receiptId");

        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT receipt_id, receipt_no, customer_name, status, sign_status, ar_status, deliver_amount " +
                        "FROM sales_receipt WHERE receipt_id = ? OR receipt_no = ?",
                key, key);
        if (heads.isEmpty()) throw new IllegalArgumentException("销售发货单不存在：" + key);
        Map<String, Object> head = heads.get(0);
        String receiptId = str(pick(head, "receipt_id"));
        String receiptNo = str(pick(head, "receipt_no"));
        String customerName = str(pick(head, "customer_name"));
        if ("CANCELLED".equals(str(pick(head, "status")))) {
            throw new IllegalArgumentException("发货单已作废，无法签收");
        }
        if ("已生成".equals(str(pick(head, "ar_status")))) {
            throw new IllegalArgumentException("该发货单已生成应收账款，请先反审核再登记签收");
        }
        String signStatus = str(pick(head, "sign_status"));
        if (!signStatus.isBlank() && !"待签收".equals(signStatus)) {
            throw new IllegalArgumentException("该发货单已签收（" + signStatus + "），如需重新登记请先撤销签收");
        }

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT detail_id, goods_name, qty, price, tax_rate FROM sales_receipt_detail WHERE receipt_id = ?",
                receiptId);
        if (details.isEmpty()) throw new IllegalArgumentException("发货单没有明细，无法签收");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        Map<String, Map<String, Object>> reqByDetailId = new HashMap<>();
        for (Map<String, Object> line : reqDetails) {
            String did = str(line.get("detailId"));
            if (!did.isBlank()) reqByDetailId.put(did, line);
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalRejectQty = BigDecimal.ZERO;
        // 签收时才产生的三个金额：签收金额（含税，应收取它）、拒收金额、税额（价内倒算）
        BigDecimal totalSignAmount = BigDecimal.ZERO;
        BigDecimal totalRejectAmount = BigDecimal.ZERO;
        BigDecimal totalTaxAmount = BigDecimal.ZERO;

        for (Map<String, Object> d : details) {
            String detailId = str(pick(d, "detail_id"));
            String goodsName = str(pick(d, "goods_name"));
            BigDecimal qty = toBd(pick(d, "qty"));
            BigDecimal price = toBd(pick(d, "price"));
            String taxRate = str(pick(d, "tax_rate"));
            Map<String, Object> line = reqByDetailId.get(detailId);

            // 前端没传该行 → 视为全部签收
            BigDecimal rejectQty = line != null ? toBd(line.get("rejectQty")) : BigDecimal.ZERO;
            BigDecimal signedQty = line != null && line.get("signedQty") != null
                    ? toBd(line.get("signedQty"))
                    : qty.subtract(rejectQty);
            String rejectReason = line != null ? str(line.get("rejectReason")).trim() : "";

            if (rejectQty.signum() < 0 || signedQty.signum() < 0) {
                throw new IllegalArgumentException("商品 " + goodsName + " 的签收/拒收数量不能为负数");
            }
            if (signedQty.add(rejectQty).compareTo(qty) != 0) {
                throw new IllegalArgumentException("商品 " + goodsName + " 的签收数量 " + plain(signedQty)
                        + " + 拒收数量 " + plain(rejectQty) + " 应等于发货数量 " + plain(qty));
            }
            if (rejectQty.signum() > 0 && rejectReason.isEmpty()) {
                throw new IllegalArgumentException("商品 " + goodsName + " 有拒收数量，必须填写拒收原因");
            }

            // 签收金额 / 拒收金额都是含税金额；税额按价内倒算（sign × rate / (1+rate)），与采购一致。
            // 明细的 amount 是「发货金额」，出库审核时就定死了，签收不动它。
            BigDecimal lineSignAmount = signedQty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineRejectAmount = rejectQty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            BigDecimal rate = parseTaxRate(taxRate);
            BigDecimal lineTax = rate.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                    : lineSignAmount.multiply(rate)
                            .divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);

            jdbcTemplate.update("""
                    UPDATE sales_receipt_detail
                    SET signed_qty = ?, reject_qty = ?, reject_reason = ?,
                        sign_amount = ?, reject_amount = ?, tax_amount = ?
                    WHERE detail_id = ?
                    """, signedQty, rejectQty, rejectReason.isEmpty() ? null : rejectReason,
                    lineSignAmount, lineRejectAmount, lineTax, detailId);

            totalQty = totalQty.add(qty);
            totalRejectQty = totalRejectQty.add(rejectQty);
            totalSignAmount = totalSignAmount.add(lineSignAmount);
            totalRejectAmount = totalRejectAmount.add(lineRejectAmount);
            totalTaxAmount = totalTaxAmount.add(lineTax);
        }

        String newSignStatus;
        if (totalRejectQty.signum() <= 0) newSignStatus = "已签收";
        else if (totalRejectQty.compareTo(totalQty) >= 0) newSignStatus = "全部拒收";
        else newSignStatus = "部分拒收";

        // 不含税金额 = 签收金额 − 税额（费用金额是占位字段，不参与任何公式）
        BigDecimal untaxedAmount = totalSignAmount.subtract(totalTaxAmount);

        jdbcTemplate.update("""
                UPDATE sales_receipt
                SET sign_status = ?, sign_time = CURRENT_TIMESTAMP, sign_user = '系统管理员', reject_qty = ?,
                    sign_amount = ?, reject_amount = ?, tax_amount = ?, untaxed_amount = ?
                WHERE receipt_id = ?
                """, newSignStatus, totalRejectQty,
                totalSignAmount, totalRejectAmount, totalTaxAmount, untaxedAmount, receiptId);

        // 有拒收 → 自动生成拒收入库单（内部已做幂等）
        String rejectInboundNo = null;
        if (totalRejectQty.signum() > 0) {
            rejectInboundNo = rejectInboundController.generateFromReceipt(receiptId);
        }

        // 签收完就自动审核生成应收 —— 取签收金额（含税），拒收部分天然不在里面，无需冲减。
        // 全部拒收（签收金额 0）没有开票对象，不生成应收也不审核，单据停在 PENDING。
        String arNo = null;
        if (totalSignAmount.signum() > 0) {
            arNo = approveAndGenerateAr(receiptId, receiptNo, customerName, totalSignAmount);
        }

        log("sales.receipt", "SIGN", receiptNo, "确认签收 → " + newSignStatus
                + "，拒收数量 " + plain(totalRejectQty)
                + "，签收金额 " + plain(totalSignAmount)
                + "，拒收金额 " + plain(totalRejectAmount)
                + (rejectInboundNo != null ? "，生成拒收入库单 " + rejectInboundNo : "")
                + (arNo != null ? "，自动审核生成应收 " + arNo : "，全部拒收未生成应收"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("receiptId", receiptId);
        result.put("receiptNo", receiptNo);
        result.put("signStatus", newSignStatus);
        result.put("rejectQty", totalRejectQty);
        result.put("rejectInboundNo", rejectInboundNo);
        result.put("deliverAmount", toBd(pick(head, "deliver_amount")));
        result.put("signAmount", totalSignAmount);
        result.put("rejectAmount", totalRejectAmount);
        result.put("taxAmount", totalTaxAmount);
        result.put("untaxedAmount", untaxedAmount);
        result.put("status", arNo != null ? "APPROVED" : "PENDING");
        result.put("arNo", arNo);

        StringBuilder effect = new StringBuilder("签收完成（").append(newSignStatus).append("）");
        if (rejectInboundNo != null) {
            effect.append("，已自动生成拒收入库单 ").append(rejectInboundNo).append("，请到「拒收入库单」审核入库");
        }
        effect.append(arNo != null
                ? "；已按签收金额 " + plain(totalSignAmount) + " 自动审核生成应收 " + arNo
                : "；全部拒收，签收金额为 0，未生成应收");
        result.put("effect", effect.toString());
        return ApiResponse.ok(result);
    }

    /**
     * 撤销签收：回滚签收带来的一切 —— 应收、拒收入库单、签收登记、金额口径。
     *
     * <p>顺序与拦截：
     * <ol>
     *   <li>已有收款记录（{@code fin_ar.received_amount > 0}）→ 拒绝，钱已经收了不能反悔；</li>
     *   <li>拒收入库单已审核入库 → 拒绝（库存会对不上），必须先反审核那张单；</li>
     *   <li>删 {@code fin_ar}、恢复 {@code status='PENDING'} / {@code ar_status='未生成'}；</li>
     *   <li>金额还原成<b>生单口径</b>：明细与主单的
     *       {@code sign_amount / reject_amount / tax_amount / untaxed_amount} 全部归 0，
     *       {@code deliver_amount}（发货金额）与明细 {@code amount} 保持不变；</li>
     *   <li>清空 {@code signed_qty / reject_qty / reject_reason} 与主单签收字段。</li>
     * </ol>
     */
    @PostMapping("/unsign")
    @Transactional
    public ApiResponse<Map<String, Object>> unsign(@Valid @RequestBody AuditRequest request) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT receipt_id, receipt_no, sign_status, deliver_amount " +
                        "FROM sales_receipt WHERE receipt_id = ? OR receipt_no = ?",
                request.bizId(), request.bizId());
        if (heads.isEmpty()) throw new IllegalArgumentException("销售发货单不存在：" + request.bizId());
        String receiptId = str(pick(heads.get(0), "receipt_id"));
        String receiptNo = str(pick(heads.get(0), "receipt_no"));
        String signStatus = str(pick(heads.get(0), "sign_status"));
        if (signStatus.isBlank() || "待签收".equals(signStatus)) {
            throw new IllegalArgumentException("该发货单尚未签收，无需撤销");
        }

        // 1) 已收款不能撤 —— 钱已经进来了，撤签收会让应收凭空消失
        List<Map<String, Object>> arRows = jdbcTemplate.queryForList(
                "SELECT ar_no, received_amount FROM fin_ar WHERE source_bill = ?", receiptNo);
        for (Map<String, Object> ar : arRows) {
            if (toBd(pick(ar, "received_amount")).signum() > 0) {
                throw new IllegalArgumentException("应收 " + str(pick(ar, "ar_no"))
                        + " 已有收款记录，无法撤销签收。请先撤销收款核销。");
            }
        }

        // 2) 先删拒收入库单（内部校验只有 PENDING 才能删，已审核入库的会直接拒绝）
        rejectInboundController.deleteByReceiptNo(receiptNo);

        // 3) 撤掉签收时自动生成的应收，单据退回未审核
        String removedAr = arRows.isEmpty() ? null : str(pick(arRows.get(0), "ar_no"));
        jdbcTemplate.update("DELETE FROM fin_ar WHERE source_bill = ?", receiptNo);

        // 4) 金额还原成【生单口径】：签收才产生的四个金额全部归 0，
        //    发货金额（deliver_amount / 明细 amount）是出库审核时定死的，不动。
        jdbcTemplate.update("""
                UPDATE sales_receipt_detail
                SET signed_qty = 0, reject_qty = 0, reject_reason = NULL,
                    sign_amount = 0, reject_amount = 0, tax_amount = 0
                WHERE receipt_id = ?
                """, receiptId);

        jdbcTemplate.update("""
                UPDATE sales_receipt
                SET sign_status = '待签收', sign_time = NULL, sign_user = NULL,
                    reject_qty = 0, reject_generated = FALSE,
                    status = 'PENDING', ar_status = '未生成', audit_user = NULL, audit_time = NULL,
                    sign_amount = 0, reject_amount = 0, tax_amount = 0, untaxed_amount = 0
                WHERE receipt_id = ?
                """, receiptId);

        log("sales.receipt", "UNSIGN", receiptNo, "撤销签收 → 清空签收拒收登记，签收/拒收/税额/不含税金额归 0"
                + "，发货金额 " + plain(toBd(pick(heads.get(0), "deliver_amount"))) + " 保持不变"
                + (removedAr != null ? "，撤销应收 " + removedAr : ""));
        return ApiResponse.ok(Map.of("receiptId", receiptId, "receiptNo", receiptNo,
                "signStatus", "待签收", "status", "PENDING",
                "effect", "已撤销签收，拒收入库单已删除，签收/拒收/税额/不含税金额已归 0（发货金额不变）"
                        + (removedAr != null ? "，应收 " + removedAr + " 已撤销" : "")));
    }

    // ============ 供 SalesOutboundController.audit 调用 ============

    /**
     * 由 {@link SalesOutboundController#audit} 在出库审核成功后调用。
     * 幂等：同一 outbound 已生成过 → 返回既有 receiptNo。
     */
    @Transactional
    public String generateFromOutbound(String outboundId) {
        List<Map<String, Object>> outboundRows = jdbcTemplate.queryForList("""
                SELECT outbound_id, outbound_no, source_order, customer, warehouse, bill_date, driver
                FROM sales_outbound WHERE outbound_id = ?
                """, outboundId);
        if (outboundRows.isEmpty()) throw new IllegalArgumentException("出库单不存在：" + outboundId);
        Map<String, Object> outbound = outboundRows.get(0);
        String outboundNo = str(pick(outbound, "outbound_no"));
        String sourceOrderNo = str(pick(outbound, "source_order"));
        String customerName = str(pick(outbound, "customer"));
        String warehouse = str(pick(outbound, "warehouse"));
        // 司机从出库单快照过来，后续拒收入库单再从发货单快照
        String driver = str(pick(outbound, "driver"));

        // 幂等
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT receipt_no FROM sales_receipt WHERE source_outbound_no = ?",
                String.class, outboundNo);
        if (!existing.isEmpty()) return existing.get(0);

        // 从订单反查 customer_code + 每商品 tax_rate
        String customerCode = "";
        Map<String, String> taxRateByGoods = new HashMap<>();
        if (!sourceOrderNo.isBlank()) {
            List<Map<String, Object>> orderRows = jdbcTemplate.queryForList(
                    "SELECT order_id, customer_code FROM sales_order WHERE order_no = ?", sourceOrderNo);
            if (!orderRows.isEmpty()) {
                customerCode = str(pick(orderRows.get(0), "customer_code"));
                String orderId = str(pick(orderRows.get(0), "order_id"));
                for (Map<String, Object> od : jdbcTemplate.queryForList(
                        "SELECT goods_code, tax_rate FROM sales_order_detail WHERE order_id = ?", orderId)) {
                    taxRateByGoods.put(str(pick(od, "goods_code")), str(pick(od, "tax_rate")));
                }
            }
        }

        // 按 goods_code 聚合出库明细（合并所有批次为一行）
        List<Map<String, Object>> aggregated = jdbcTemplate.queryForList("""
                SELECT goods_code, MIN(goods_name) AS goods_name, MIN(unit_name) AS unit_name,
                       SUM(qty) AS qty, MIN(price) AS price, SUM(amount) AS amount
                FROM sales_outbound_detail
                WHERE outbound_id = ?
                GROUP BY goods_code
                """, outboundId);

        // 生单时只有【发货金额】：deliver_amount = Σ 明细 qty × price（含税）。
        // 签收金额 / 拒收金额 / 税额 / 不含税金额都要等签收才有，这里一律写 0。
        BigDecimal totalDeliverAmount = BigDecimal.ZERO;
        List<Map<String, Object>> detailRowsToInsert = new ArrayList<>();
        for (Map<String, Object> line : aggregated) {
            String goodsCode = str(pick(line, "goods_code"));
            BigDecimal lineAmount = toBd(pick(line, "amount")).setScale(2, RoundingMode.HALF_UP);
            String taxRate = taxRateByGoods.getOrDefault(goodsCode, "13%");

            Map<String, Object> row = new HashMap<>();
            row.put("detailId", "SRD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            row.put("goodsCode", goodsCode);
            row.put("goodsName", str(pick(line, "goods_name")));
            row.put("unitName", str(pick(line, "unit_name")));
            row.put("qty", toBd(pick(line, "qty")));
            row.put("price", toBd(pick(line, "price")));
            row.put("amount", lineAmount);
            row.put("taxRate", taxRate);
            detailRowsToInsert.add(row);

            totalDeliverAmount = totalDeliverAmount.add(lineAmount);
        }

        String receiptId = "SR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String receiptNo = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.SALES_RECEIPT, "sales_receipt", "receipt_no");
        LocalDate receiptDate = parseDate(pick(outbound, "bill_date"), LocalDate.now());

        jdbcTemplate.update("""
                INSERT INTO sales_receipt (receipt_id, receipt_no, source_outbound_no, source_order_no,
                    customer_code, customer_name, warehouse, driver, receipt_date,
                    deliver_amount, sign_amount, reject_amount, tax_amount, expense_amount, untaxed_amount,
                    ar_status, receive_status, status, creator_name, remark,
                    sign_status, reject_qty, reject_generated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0,
                    '未生成', '未收款', 'PENDING', '系统', NULL,
                    '待签收', 0, FALSE)
                """,
                receiptId, receiptNo, outboundNo, sourceOrderNo,
                customerCode, customerName, warehouse, emptyToNull(driver), receiptDate,
                totalDeliverAmount);

        for (Map<String, Object> row : detailRowsToInsert) {
            jdbcTemplate.update("""
                    INSERT INTO sales_receipt_detail (detail_id, receipt_id, goods_code, goods_name,
                        unit_name, qty, price, amount, tax_rate, tax_amount,
                        signed_qty, reject_qty, sign_amount, reject_amount, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, NULL)
                    """,
                    row.get("detailId"), receiptId,
                    row.get("goodsCode"), row.get("goodsName"), row.get("unitName"),
                    row.get("qty"), row.get("price"), row.get("amount"),
                    row.get("taxRate"));
        }

        log("sales.receipt", "GENERATE", receiptNo, "出库单 " + outboundNo + " 自动生成发货单，发货金额 "
                + plain(totalDeliverAmount) + "（签收金额/税额/不含税金额待签收后生成）");
        return receiptNo;
    }

    // ============ 工具方法 ============

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

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                moduleCode, action, bizNo, detail);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String emptyToNull(String s) { return (s == null || s.isBlank()) ? null : s; }
    /** BigDecimal 去掉多余尾随 0，用于错误提示 */
    private static String plain(BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }
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
