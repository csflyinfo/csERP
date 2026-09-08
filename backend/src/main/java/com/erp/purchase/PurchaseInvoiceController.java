package com.erp.purchase;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.util.BillNoGenerator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 采购发票（供应商进项发票）REST 端点。PRD-30。
 * <p>
 * <b>票账分离</b>：发票只做来票登记、与采购收货单勾稽、来票状态跟踪与报表统计，
 * <b>不生成应付、不动库存成本</b>（应付在采购收货单审核时已按含税金额生成 fin_ap）。
 * <p>
 * 业务流程：<b>录入发票（填发票金额，可不勾稽）→ 勾稽发票（按商品行选未开票数量）
 * → 取消勾稽（移除勾稽行）→ 审核</b>。审核回写 pur_receipt / fin_ap 来票状态，
 * 反审核/作废按勾稽快照(matched_before)逆向回退；已认证发票禁止反审核与作废。
 * 审核后主信息锁定，仅允许认证操作与追加备注。
 * <p>
 * 勾稽粒度：商品行（pur_invoice_match_line），记录到「收货单+商品」的本次开票数量；
 * 审核时按收货单聚合金额（pur_invoice_match）回写。金额口径全程含税（价内税）。
 */
@RestController
@RequestMapping("/purchase/invoice")
public class PurchaseInvoiceController {

    /** 勾稽容差（元）：尾差在此范围内自动置平。后续可迁系统参数 invoice.match-tolerance。 */
    private static final BigDecimal TOLERANCE = new BigDecimal("1.00");
    /** 数量容差：四舍五入误差允许。 */
    private static final BigDecimal QTY_EPS = new BigDecimal("0.0001");

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;

    public PurchaseInvoiceController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
    }

    // ============ 列表 & 详情 ============

    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT invoice_id, invoice_no, invoice_number, invoice_code, invoice_type, direction, " +
                        "supplier_code, supplier_name, issue_date, receive_date, " +
                        "untaxed_amount, tax_amount, total_amount, matched_amount, match_status, " +
                        "cert_status, cert_date, status, void_reason, creator_name, create_time, " +
                        "auditor_name, audit_time, remark " +
                        "FROM pur_invoice ORDER BY create_time DESC, invoice_no DESC");
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            BigDecimal total = toBd(row.get("totalAmount"));
            BigDecimal matched = toBd(row.get("matchedAmount"));
            row.put("unmatchedAmount", total.subtract(matched).setScale(2, RoundingMode.HALF_UP));
            row.put("invoiceAmount", total); // 发票金额（含税）
            row.put("supplier", row.get("supplierName")); // 兼容前端模糊映射
            mapped.add(row);
        }
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @GetMapping("/detail")
    public ApiResponse<Map<String, Object>> detail(
            @RequestParam(required = false) String invoiceId,
            @RequestParam(required = false) String id) {
        String key = invoiceId != null && !invoiceId.isBlank() ? invoiceId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 invoiceId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM pur_invoice WHERE invoice_id = ? OR invoice_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "发票不存在");
        Map<String, Object> head = camelize(heads.get(0));
        BigDecimal total = toBd(head.get("totalAmount"));
        BigDecimal matched = toBd(head.get("matchedAmount"));
        head.put("unmatchedAmount", total.subtract(matched).setScale(2, RoundingMode.HALF_UP));
        head.put("invoiceAmount", total);

        List<Map<String, Object>> lines = jdbcTemplate.queryForList(
                "SELECT * FROM pur_invoice_line WHERE invoice_id = ? ORDER BY sort_order, line_id",
                head.get("invoiceId"));
        head.put("lines", lines.stream().map(PurchaseInvoiceController::camelize).toList());

        // 勾稽商品行（录入后即保存，草稿/已审核都回显）
        List<Map<String, Object>> matchLines = jdbcTemplate.queryForList(
                "SELECT * FROM pur_invoice_match_line WHERE invoice_id = ? ORDER BY create_time, id",
                head.get("invoiceId"));
        head.put("matchLines", matchLines.stream().map(PurchaseInvoiceController::camelize).toList());

        // 勾稽聚合行（按收货单）：带实时已来票/未票金额
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> m : jdbcTemplate.queryForList(
                "SELECT * FROM pur_invoice_match WHERE invoice_id = ? ORDER BY create_time, match_id",
                head.get("invoiceId"))) {
            Map<String, Object> mm = camelize(m);
            String billNo = str(mm.get("billNo"));
            List<Map<String, Object>> bills = jdbcTemplate.queryForList(
                    "SELECT receipt_date, source_order_no, warehouse FROM pur_receipt WHERE receipt_no = ?", billNo);
            if (!bills.isEmpty()) {
                Map<String, Object> b = camelize(bills.get(0));
                mm.put("billDate", b.get("receiptDate"));
                mm.put("orderNo", b.get("sourceOrderNo"));
                mm.put("warehouse", b.get("warehouse"));
            }
            BigDecimal billAmount = toBd(mm.get("billAmount"));
            BigDecimal before = toBd(mm.get("matchedBefore"));
            // 草稿期 matched_before 尚未落快照，用收货单实时已来票额
            if (before.signum() == 0) {
                List<Map<String, Object>> cur = jdbcTemplate.queryForList(
                        "SELECT invoiced_amount FROM pur_receipt WHERE receipt_no = ?", billNo);
                if (!cur.isEmpty()) before = toBd(pick(cur.get(0), "invoiced_amount"));
            }
            mm.put("invoicedAmount", before);
            mm.put("unbilledAmount", billAmount.subtract(before).setScale(2, RoundingMode.HALF_UP));
            mm.put("settleFlag", toBd(mm.get("settleFlag")).signum() != 0);
            matches.add(mm);
        }
        head.put("matches", matches);
        return ApiResponse.ok(head);
    }

    // ============ 新建 / 修改（草稿） ============

    @PostMapping("/create")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        String invoiceId = "PINV" + uuid();
        String invoiceNo = billNoGen.nextNo(BillNoGenerator.BillType.PURCHASE_INVOICE, "pur_invoice", "invoice_no");
        saveInvoice(invoiceId, invoiceNo, request, true);
        log("purchase.invoice", "CREATE", invoiceNo, "登记采购发票（草稿）");
        return ApiResponse.ok(Map.of("invoiceId", invoiceId, "invoiceNo", invoiceNo,
                "status", "草稿", "effect", "发票已保存为草稿"));
    }

    @PostMapping("/update")
    @Transactional
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> request) {
        String key = str(request.get("invoiceId"));
        if (key.isBlank()) key = str(request.get("id"));
        if (key.isBlank()) throw new IllegalArgumentException("缺少 invoiceId");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT invoice_id, invoice_no, status FROM pur_invoice WHERE invoice_id = ? OR invoice_no = ?", key, key);
        if (heads.isEmpty()) throw new IllegalArgumentException("发票不存在：" + key);
        if (!"草稿".equals(str(pick(heads.get(0), "status")))) {
            throw new IllegalArgumentException("仅草稿状态发票可修改，审核后请先反审核");
        }
        String invoiceId = str(pick(heads.get(0), "invoice_id"));
        String invoiceNo = str(pick(heads.get(0), "invoice_no"));
        jdbcTemplate.update("DELETE FROM pur_invoice_line WHERE invoice_id = ?", invoiceId);
        jdbcTemplate.update("DELETE FROM pur_invoice_match WHERE invoice_id = ?", invoiceId);
        jdbcTemplate.update("DELETE FROM pur_invoice_match_line WHERE invoice_id = ?", invoiceId);
        saveInvoice(invoiceId, invoiceNo, request, false);
        log("purchase.invoice", "UPDATE", invoiceNo, "修改采购发票草稿");
        return ApiResponse.ok(Map.of("invoiceId", invoiceId, "invoiceNo", invoiceNo, "effect", "发票草稿已更新"));
    }

    /**
     * 保存发票头/勾稽商品行/商品明细（create 与 update 共用）。
     * <p>发票金额（含税）由用户录入；勾稽商品行按「收货单+商品」校验未开票数量，
     * 审核前不强制勾稽。商品明细与税额由勾稽行聚合 + 手工补行生成。
     */
    private void saveInvoice(String invoiceId, String invoiceNo, Map<String, Object> request, boolean isNew) {
        String invoiceNumber = str(request.get("invoiceNumber")).trim();
        String supplierCode = str(request.get("supplierCode")).trim();
        String supplierName = str(request.get("supplierName")).trim();
        String invoiceType = str(request.get("invoiceType")).trim();
        String issueDate = str(request.get("issueDate"));
        if (invoiceNumber.isBlank()) throw new IllegalArgumentException("发票号码必填");
        if (supplierCode.isBlank() || supplierName.isBlank()) throw new IllegalArgumentException("供应商必填");
        if (invoiceType.isBlank()) throw new IllegalArgumentException("发票类型必填");
        if (issueDate.isBlank()) throw new IllegalArgumentException("开票日期必填");
        BigDecimal invoiceAmount = toBd(request.get("totalAmount"));
        if (invoiceAmount.signum() <= 0) throw new IllegalArgumentException("请填写发票金额（含税价税合计）");
        invoiceAmount = invoiceAmount.setScale(2, RoundingMode.HALF_UP);
        String direction = strOrDefault(request.get("direction"), "蓝字");
        String certStatus = strOrDefault(request.get("certStatus"), "未认证");

        // 同供应商发票号码+红蓝方向唯一（作废票号也占用，不可复用）
        List<Map<String, Object>> dup = jdbcTemplate.queryForList(
                "SELECT invoice_no FROM pur_invoice WHERE supplier_code = ? AND invoice_number = ? AND direction = ? "
                        + (isNew ? "" : "AND invoice_id <> ?"),
                isNew ? new Object[]{supplierCode, invoiceNumber, direction}
                      : new Object[]{supplierCode, invoiceNumber, direction, invoiceId});
        if (!dup.isEmpty()) {
            throw new IllegalArgumentException("供应商 " + supplierName + " 已存在" + direction
                    + "发票号码 " + invoiceNumber + "（" + str(pick(dup.get(0), "invoice_no")) + "），不可重复登记");
        }

        // ---- 勾稽商品行：校验 + 聚合（草稿保存与审核后改勾稽共用 collectMatchLines） ----
        Map<String, Map<String, Object>> matchAgg =
                collectMatchLines(request.get("matchLines"), invoiceId, supplierCode, supplierName);
        BigDecimal matchSum = matchSumOf(matchAgg);
        if (matchSum.compareTo(invoiceAmount.add(TOLERANCE)) > 0) {
            throw new IllegalArgumentException("勾稽合计 " + plain(matchSum) + " 元超过发票金额 "
                    + plain(invoiceAmount) + " 元");
        }

        // ---- 发票商品明细：勾稽行按商品聚合 + 手工补行（草稿期支持） ----
        List<Map<String, Object>> lines = buildInvoiceLines(matchAgg, request.get("lines"));
        BigDecimal totalTax = BigDecimal.ZERO;
        for (Map<String, Object> ln : lines) totalTax = totalTax.add(toBd(ln.get("tax")));
        BigDecimal totalUntaxed = invoiceAmount.subtract(totalTax).setScale(2, RoundingMode.HALF_UP);

        // ---- 落库：头（新建 INSERT / 修改 UPDATE，update 前已清空明细行） ----
        LocalDate issue = parseDate(issueDate, LocalDate.now());
        LocalDate receive = parseDate(str(request.get("receiveDate")), null);
        if (isNew) {
            jdbcTemplate.update(
                    "INSERT INTO pur_invoice(invoice_id, invoice_no, invoice_number, invoice_code, invoice_type, " +
                            "direction, supplier_code, supplier_name, buyer_title, buyer_tax_no, issue_date, receive_date, " +
                            "untaxed_amount, tax_amount, total_amount, matched_amount, match_status, cert_status, " +
                            "status, attachment_url, attachment_name, remark, creator_name, create_time) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, '未勾稽', ?, '草稿', ?, ?, ?, '系统', CURRENT_TIMESTAMP)",
                    invoiceId, invoiceNo, invoiceNumber, strOrNull(request.get("invoiceCode")), invoiceType,
                    direction, supplierCode, supplierName,
                    strOrNull(request.get("buyerTitle")), strOrNull(request.get("buyerTaxNo")),
                    issue, receive, totalUntaxed, totalTax, invoiceAmount,
                    certStatus,
                    strOrNull(request.get("attachmentUrl")), strOrNull(request.get("attachmentName")),
                    str(request.get("remark")));
        } else {
            jdbcTemplate.update(
                    "UPDATE pur_invoice SET invoice_number = ?, invoice_code = ?, invoice_type = ?, direction = ?, " +
                            "supplier_code = ?, supplier_name = ?, buyer_title = ?, buyer_tax_no = ?, " +
                            "issue_date = ?, receive_date = ?, untaxed_amount = ?, tax_amount = ?, total_amount = ?, " +
                            "matched_amount = 0, match_status = '未勾稽', cert_status = ?, " +
                            "attachment_url = ?, attachment_name = ?, remark = ? " +
                            "WHERE invoice_id = ?",
                    invoiceNumber, strOrNull(request.get("invoiceCode")), invoiceType, direction,
                    supplierCode, supplierName,
                    strOrNull(request.get("buyerTitle")), strOrNull(request.get("buyerTaxNo")),
                    issue, receive, totalUntaxed, totalTax, invoiceAmount, certStatus,
                    strOrNull(request.get("attachmentUrl")), strOrNull(request.get("attachmentName")),
                    str(request.get("remark")), invoiceId);
        }

        // ---- 落库：勾稽商品行 ----
        for (Map<String, Object> m : matchAgg.values()) {
            jdbcTemplate.update(
                    "INSERT INTO pur_invoice_match_line(id, invoice_id, receipt_no, inbound_no, order_no, " +
                            "goods_code, goods_name, spec, unit_name, qty, price, amount, this_qty, this_amount, tax_rate) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "PIML" + uuid(), invoiceId, m.get("receiptNo"), m.get("inboundNo"), m.get("orderNo"),
                    m.get("goodsCode"), m.get("goodsName"), m.get("spec"), m.get("unitName"),
                    m.get("qty"), m.get("price"), m.get("amount"), m.get("thisQty"), m.get("thisAmount"),
                    strOrNull(m.get("taxRate")));
        }

        // ---- 落库：勾稽聚合行（按收货单；matched_before/settle/diff 审核时定） ----
        Map<String, BigDecimal> billThisAmount = new LinkedHashMap<>();
        for (Map<String, Object> m : matchAgg.values()) {
            billThisAmount.merge(str(m.get("receiptNo")), toBd(m.get("thisAmount")), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : billThisAmount.entrySet()) {
            String billNo = e.getKey();
            List<Map<String, Object>> bills = jdbcTemplate.queryForList(
                    "SELECT goods_amount FROM pur_receipt WHERE receipt_no = ?", billNo);
            BigDecimal billAmount = bills.isEmpty() ? BigDecimal.ZERO : toBd(pick(bills.get(0), "goods_amount"));
            BigDecimal thisTax = invoiceAmount.signum() > 0
                    ? totalTax.multiply(e.getValue()).divide(invoiceAmount, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            jdbcTemplate.update(
                    "INSERT INTO pur_invoice_match(match_id, invoice_id, invoice_no, bill_type, bill_no, " +
                            "bill_amount, matched_before, this_amount, this_tax_amount, settle_flag, diff_amount, diff_reason) " +
                            "VALUES (?, ?, ?, '收货单', ?, ?, 0, ?, ?, 0, 0, NULL)",
                    "PIM" + uuid(), invoiceId, invoiceNo, billNo, billAmount, e.getValue(), thisTax);
        }

        // ---- 落库：商品明细 ----
        for (Map<String, Object> ln : lines) {
            jdbcTemplate.update(
                    "INSERT INTO pur_invoice_line(line_id, invoice_id, sort_order, goods_code, goods_name, spec, " +
                            "unit_name, qty, price, tax_rate, untaxed_amount, tax_amount, amount, source_receipt_no) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ln.get("lineId"), invoiceId, ln.get("sort"),
                    ln.get("goodsCode"), ln.get("goodsName"), ln.get("spec"), ln.get("unitName"),
                    ln.get("qty"), ln.get("price"), ln.get("taxRate"),
                    ln.get("untaxed"), ln.get("tax"), ln.get("amount"),
                    strOrNull(ln.get("sourceReceiptNo")));
        }
    }

    // ============ 审核 / 反审核 / 作废 / 删除 ============

    /** 审核：按勾稽行回写收货单与应付的来票金额/状态，发票转已审核。 */
    @PostMapping("/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> audit(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> inv = requireInvoice(request.bizId());
        String invoiceId = str(pick(inv, "invoice_id"));
        String invoiceNo = str(pick(inv, "invoice_no"));
        String status = str(pick(inv, "status"));
        if ("已作废".equals(status)) throw new IllegalArgumentException("发票已作废，不可审核");
        if ("已审核".equals(status)) throw new IllegalArgumentException("发票已审核，请勿重复操作");

        List<Map<String, Object>> matchRows = jdbcTemplate.queryForList(
                "SELECT * FROM pur_invoice_match WHERE invoice_id = ?", invoiceId);
        List<Map<String, Object>> matchLines = jdbcTemplate.queryForList(
                "SELECT * FROM pur_invoice_match_line WHERE invoice_id = ?", invoiceId);

        // 审核时点重新校验商品行未开票数量（草稿期间其他发票可能已勾稽同一商品行）
        for (Map<String, Object> ml : matchLines) {
            String billNo = str(pick(ml, "receipt_no"));
            String goodsCode = str(pick(ml, "goods_code"));
            BigDecimal thisQty = toBd(pick(ml, "this_qty"));
            List<Map<String, Object>> details = jdbcTemplate.queryForList(
                    "SELECT d.qty FROM pur_receipt_detail d JOIN pur_receipt r ON r.receipt_id = d.receipt_id " +
                            "WHERE r.receipt_no = ? AND d.goods_code = ?", billNo, goodsCode);
            if (details.isEmpty()) throw new IllegalArgumentException("勾稽商品行不存在：" + billNo + " / " + goodsCode);
            BigDecimal lineQty = toBd(pick(details.get(0), "qty"));
            BigDecimal occupied = occupiedQty(billNo, goodsCode, invoiceId, true);
            BigDecimal freeQty = lineQty.subtract(occupied);
            if (thisQty.compareTo(freeQty.add(QTY_EPS)) > 0) {
                throw new IllegalArgumentException("商品 " + goodsCode + " 在收货单 " + billNo
                        + " 的未开票数量仅 " + plain(freeQty) + "，本发票勾稽 " + plain(thisQty)
                        + "，请取消勾稽后修改再审核");
            }
        }

        // 逐单回写（金额口径：收货单 goods_amount 为含税结算额）
        BigDecimal matchedSum = BigDecimal.ZERO;
        for (Map<String, Object> m : matchRows) {
            String billNo = str(pick(m, "bill_no"));
            BigDecimal thisAmount = toBd(pick(m, "this_amount"));

            List<Map<String, Object>> bills = jdbcTemplate.queryForList(
                    "SELECT goods_amount, invoiced_amount FROM pur_receipt WHERE receipt_no = ?", billNo);
            if (bills.isEmpty()) throw new IllegalArgumentException("勾稽收货单不存在：" + billNo);
            BigDecimal billAmount = toBd(pick(bills.get(0), "goods_amount"));
            BigDecimal invoiced = toBd(pick(bills.get(0), "invoiced_amount"));
            BigDecimal unbilled = billAmount.subtract(invoiced);

            boolean settle = false;
            if (thisAmount.compareTo(unbilled) > 0) {
                BigDecimal over = thisAmount.subtract(unbilled);
                if (over.compareTo(TOLERANCE) > 0) {
                    throw new IllegalArgumentException("收货单 " + billNo + " 未票金额仅 " + plain(unbilled)
                            + " 元，本次勾稽 " + plain(thisAmount) + " 元超出容差，请修改后再审核");
                }
                settle = true;
            }
            BigDecimal tail = unbilled.subtract(thisAmount);
            if (!settle && tail.abs().compareTo(TOLERANCE) <= 0 && tail.signum() >= 0 && thisAmount.signum() > 0) {
                settle = true; // 少票尾差在容差内，自动结清置平
            }
            // 置平：结清单按收货额封顶，尾差记入 diff_amount
            BigDecimal newInvoiced = settle ? billAmount : invoiced.add(thisAmount);
            BigDecimal diff = settle ? invoiced.add(thisAmount).subtract(billAmount) : BigDecimal.ZERO;
            // 本发票对该收货单来票额的实际贡献（置平/尾差后可能 ≠ thisAmount），反审核按此精确剥离
            BigDecimal applied = newInvoiced.subtract(invoiced);
            String invoiceStatus = invoiceStatusOf(newInvoiced, billAmount);

            jdbcTemplate.update(
                    "UPDATE pur_receipt SET invoiced_amount = ?, invoice_status = ? WHERE receipt_no = ?",
                    newInvoiced, invoiceStatus, billNo);
            // 同步应付（source_bill=收货单号 的正向 AP；退货负向 AP 二期处理）
            jdbcTemplate.update(
                    "UPDATE fin_ap SET invoiced_amount = ?, invoice_status = ? WHERE source_bill = ? AND ap_amount > 0",
                    newInvoiced, invoiceStatus, billNo);
            jdbcTemplate.update(
                    "UPDATE pur_invoice_match SET matched_before = ?, settle_flag = ?, diff_amount = ?, applied_amount = ? " +
                            "WHERE match_id = ?",
                    invoiced, settle ? 1 : 0, diff, applied, pick(m, "match_id"));
            matchedSum = matchedSum.add(thisAmount);
        }

        BigDecimal totalAmount = toBd(pick(inv, "total_amount"));
        String matchStatus = matchedSum.signum() == 0 ? "未勾稽"
                : totalAmount.subtract(matchedSum).abs().compareTo(TOLERANCE) <= 0 ? "已勾稽"
                : "部分勾稽";
        jdbcTemplate.update(
                "UPDATE pur_invoice SET status = '已审核', matched_amount = ?, match_status = ?, " +
                        "auditor_name = '系统管理员', audit_time = CURRENT_TIMESTAMP WHERE invoice_id = ?",
                matchedSum, matchStatus, invoiceId);

        // 行级回写：发票转已审核后，按收货单商品行重算已开票数量/金额（本发票行此刻才计入）
        for (Map<String, Object> m : matchRows) {
            refreshReceiptLineInvoiced(str(pick(m, "bill_no")));
        }

        log("purchase.invoice", "AUDIT", invoiceNo, "采购发票审核，勾稽 " + matchLines.size()
                + " 条商品行、" + matchRows.size() + " 张收货单，来票金额 " + plain(matchedSum));
        return ApiResponse.ok(Map.of("invoiceId", invoiceId, "invoiceNo", invoiceNo,
                "status", "已审核", "matchStatus", matchStatus,
                "effect", "已审核并回写 " + matchRows.size() + " 张收货单的来票状态"));
    }

    /** 反审核：按 matched_before 快照逆向回退；已认证发票禁止。 */
    @PostMapping("/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAudit(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> inv = requireInvoice(request.bizId());
        String invoiceId = str(pick(inv, "invoice_id"));
        String invoiceNo = str(pick(inv, "invoice_no"));
        if (!"已审核".equals(str(pick(inv, "status")))) {
            throw new IllegalArgumentException("只有已审核发票可反审核");
        }
        if ("已认证".equals(str(pick(inv, "cert_status")))) {
            throw new IllegalArgumentException("已认证发票不可反审核（税法要求已认证专票须走红字流程）");
        }
        List<String> rolledBills = rollbackMatches(invoiceId);
        jdbcTemplate.update(
                "UPDATE pur_invoice SET status = '草稿', matched_amount = 0, match_status = '未勾稽', " +
                        "auditor_name = NULL, audit_time = NULL WHERE invoice_id = ?", invoiceId);
        // 行级回写：发票转草稿后重算（本发票行不再计入已审核口径）
        for (String billNo : rolledBills) refreshReceiptLineInvoiced(billNo);
        log("purchase.invoice", "REVERSE_AUDIT", invoiceNo, "采购发票反审核，回退来票勾稽");
        return ApiResponse.ok(Map.of("invoiceId", invoiceId, "status", "草稿", "effect", "已反审核，来票勾稽已回退"));
    }

    /** 作废：已审核票先逆向回退再作废；已认证禁止；作废原因必填。 */
    @PostMapping("/void")
    @Transactional
    public ApiResponse<Map<String, Object>> voidInvoice(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> inv = requireInvoice(request.bizId());
        String invoiceId = str(pick(inv, "invoice_id"));
        String invoiceNo = str(pick(inv, "invoice_no"));
        String status = str(pick(inv, "status"));
        if ("已作废".equals(status)) throw new IllegalArgumentException("发票已作废");
        if ("已认证".equals(str(pick(inv, "cert_status")))) {
            throw new IllegalArgumentException("已认证发票不可作废，请登记红字发票");
        }
        String reason = request.remark() == null ? "" : request.remark().trim();
        if (reason.isBlank()) throw new IllegalArgumentException("作废必须填写作废原因");
        List<String> rolledBills = "已审核".equals(status) ? rollbackMatches(invoiceId) : List.of();
        jdbcTemplate.update(
                "UPDATE pur_invoice SET status = '已作废', void_reason = ?, matched_amount = 0, " +
                        "match_status = '未勾稽' WHERE invoice_id = ?", reason, invoiceId);
        // 行级回写：发票转作废后重算（本发票行不再计入已审核口径）
        for (String billNo : rolledBills) refreshReceiptLineInvoiced(billNo);
        log("purchase.invoice", "VOID", invoiceNo, "采购发票作废：" + reason);
        return ApiResponse.ok(Map.of("invoiceId", invoiceId, "status", "已作废", "effect", "发票已作废，来票勾稽已释放"));
    }

    /** 删除：仅草稿；已审核/已作废留痕不可删。 */
    @PostMapping("/delete")
    @Transactional
    public ApiResponse<Map<String, Object>> delete(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> inv = requireInvoice(request.bizId());
        String invoiceId = str(pick(inv, "invoice_id"));
        String invoiceNo = str(pick(inv, "invoice_no"));
        if (!"草稿".equals(str(pick(inv, "status")))) {
            throw new IllegalArgumentException("仅草稿发票可删除，已审核发票请作废");
        }
        jdbcTemplate.update("DELETE FROM pur_invoice_line WHERE invoice_id = ?", invoiceId);
        jdbcTemplate.update("DELETE FROM pur_invoice_match WHERE invoice_id = ?", invoiceId);
        jdbcTemplate.update("DELETE FROM pur_invoice_match_line WHERE invoice_id = ?", invoiceId);
        jdbcTemplate.update("DELETE FROM pur_invoice WHERE invoice_id = ?", invoiceId);
        log("purchase.invoice", "DELETE", invoiceNo, "删除采购发票草稿");
        return ApiResponse.ok(Map.of("invoiceId", invoiceId, "effect", "草稿发票已删除"));
    }

    /**
     * 审核后继续勾稽：已审核发票仍可增删/修改勾稽商品行（数量、金额、税率），保存即增量回写。
     * <p>目标来票额 = 当前来票额 − 本发票已贡献(applied_amount) + 本次勾稽额（按收货单聚合），
     * 超收货额 1 元容差自动置平、超出拒绝；勾稽行全部移除则该收货单恢复到本发票贡献前。
     * 反审核/作废仍按 applied_amount 整体剥离，不影响其他发票。
     */
    @PostMapping("/update-matches")
    @Transactional
    public ApiResponse<Map<String, Object>> updateMatches(@RequestBody Map<String, Object> request) {
        String key = strOrDefault(request.get("invoiceId"), str(request.get("id")));
        if (key.isBlank()) throw new IllegalArgumentException("缺少 invoiceId");
        Map<String, Object> inv = requireInvoice(key);
        String invoiceId = str(pick(inv, "invoice_id"));
        String invoiceNo = str(pick(inv, "invoice_no"));
        String status = str(pick(inv, "status"));
        if ("已作废".equals(status)) throw new IllegalArgumentException("发票已作废，不可勾稽");
        if (!"已审核".equals(status)) {
            throw new IllegalArgumentException("仅已审核发票使用本接口；草稿请走保存/审核");
        }
        String supplierCode = str(pick(inv, "supplier_code"));
        String supplierName = str(pick(inv, "supplier_name"));
        BigDecimal invoiceAmount = toBd(pick(inv, "total_amount"));

        Map<String, Map<String, Object>> matchAgg =
                collectMatchLines(request.get("matchLines"), invoiceId, supplierCode, supplierName);
        BigDecimal matchedSum = matchSumOf(matchAgg);
        if (matchedSum.compareTo(invoiceAmount.add(TOLERANCE)) > 0) {
            throw new IllegalArgumentException("勾稽合计 " + plain(matchedSum) + " 元超过发票金额 "
                    + plain(invoiceAmount) + " 元");
        }

        // 本次勾稽额按收货单聚合
        Map<String, BigDecimal> newThisByBill = new LinkedHashMap<>();
        for (Map<String, Object> m : matchAgg.values()) {
            newThisByBill.merge(str(m.get("receiptNo")), toBd(m.get("thisAmount")), BigDecimal::add);
        }
        // 旧勾稽（含首次审核快照与实际贡献额）
        Map<String, Map<String, Object>> oldByBill = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT bill_no, matched_before, applied_amount FROM pur_invoice_match WHERE invoice_id = ?", invoiceId)) {
            oldByBill.put(str(pick(row, "bill_no")), row);
        }

        // 逐收货单：剔除本发票旧贡献 → 加本次勾稽 → 置平规则 → 回写
        Map<String, BigDecimal> beforeByBill = new LinkedHashMap<>();
        Map<String, BigDecimal> appliedByBill = new LinkedHashMap<>();
        Map<String, Boolean> settleByBill = new HashMap<>();
        Map<String, BigDecimal> diffByBill = new HashMap<>();
        Map<String, BigDecimal> billAmountByBill = new HashMap<>();
        List<String> allBills = new ArrayList<>(oldByBill.keySet());
        for (String b : newThisByBill.keySet()) if (!allBills.contains(b)) allBills.add(b);
        for (String billNo : allBills) {
            List<Map<String, Object>> bills = jdbcTemplate.queryForList(
                    "SELECT goods_amount, invoiced_amount FROM pur_receipt WHERE receipt_no = ?", billNo);
            if (bills.isEmpty()) throw new IllegalArgumentException("勾稽收货单不存在：" + billNo);
            BigDecimal billAmount = toBd(pick(bills.get(0), "goods_amount"));
            BigDecimal invoiced = toBd(pick(bills.get(0), "invoiced_amount"));
            Map<String, Object> old = oldByBill.get(billNo);
            BigDecimal appliedOld = old == null ? BigDecimal.ZERO : toBd(pick(old, "applied_amount"));
            // 快照：旧单沿用首次审核快照；新勾稽的收货单快照=当前来票额（不含本发票）
            BigDecimal before = old != null ? toBd(pick(old, "matched_before")) : invoiced;
            BigDecimal base = invoiced.subtract(appliedOld); // 剔除本发票已贡献部分
            if (base.signum() < 0) base = BigDecimal.ZERO;
            BigDecimal newThis = newThisByBill.getOrDefault(billNo, BigDecimal.ZERO);
            BigDecimal raw = base.add(newThis);

            boolean settle = false;
            BigDecimal diff = BigDecimal.ZERO;
            BigDecimal newInvoiced;
            if (raw.compareTo(billAmount) > 0) {
                BigDecimal over = raw.subtract(billAmount);
                if (over.compareTo(TOLERANCE) > 0) {
                    throw new IllegalArgumentException("收货单 " + billNo + " 未票金额仅 "
                            + plain(billAmount.subtract(base)) + " 元，本次勾稽后合计 "
                            + plain(raw) + " 元超出容差，请减少勾稽金额");
                }
                settle = true;
                diff = raw.subtract(billAmount);
                newInvoiced = billAmount;
            } else {
                BigDecimal tail = billAmount.subtract(raw);
                if (newThis.signum() > 0 && tail.signum() >= 0 && tail.compareTo(TOLERANCE) <= 0) {
                    settle = true; // 少票尾差容差内自动置平
                    newInvoiced = billAmount;
                } else {
                    newInvoiced = raw;
                }
            }
            BigDecimal appliedNew = newInvoiced.subtract(base);
            String invoiceStatus = invoiceStatusOf(newInvoiced, billAmount);
            jdbcTemplate.update(
                    "UPDATE pur_receipt SET invoiced_amount = ?, invoice_status = ? WHERE receipt_no = ?",
                    newInvoiced, invoiceStatus, billNo);
            jdbcTemplate.update(
                    "UPDATE fin_ap SET invoiced_amount = ?, invoice_status = ? WHERE source_bill = ? AND ap_amount > 0",
                    newInvoiced, invoiceStatus, billNo);
            beforeByBill.put(billNo, before);
            appliedByBill.put(billNo, appliedNew);
            settleByBill.put(billNo, settle);
            diffByBill.put(billNo, diff);
            billAmountByBill.put(billNo, billAmount);
        }

        // 重建勾稽商品行 / 聚合行 / 发票商品明细
        jdbcTemplate.update("DELETE FROM pur_invoice_line WHERE invoice_id = ?", invoiceId);
        jdbcTemplate.update("DELETE FROM pur_invoice_match WHERE invoice_id = ?", invoiceId);
        jdbcTemplate.update("DELETE FROM pur_invoice_match_line WHERE invoice_id = ?", invoiceId);

        for (Map<String, Object> m : matchAgg.values()) {
            jdbcTemplate.update(
                    "INSERT INTO pur_invoice_match_line(id, invoice_id, receipt_no, inbound_no, order_no, " +
                            "goods_code, goods_name, spec, unit_name, qty, price, amount, this_qty, this_amount, tax_rate) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "PIML" + uuid(), invoiceId, m.get("receiptNo"), m.get("inboundNo"), m.get("orderNo"),
                    m.get("goodsCode"), m.get("goodsName"), m.get("spec"), m.get("unitName"),
                    m.get("qty"), m.get("price"), m.get("amount"), m.get("thisQty"), m.get("thisAmount"),
                    strOrNull(m.get("taxRate")));
        }
        List<Map<String, Object>> lines = buildInvoiceLines(matchAgg, List.of());
        BigDecimal totalTax = BigDecimal.ZERO;
        for (Map<String, Object> ln : lines) totalTax = totalTax.add(toBd(ln.get("tax")));
        for (Map.Entry<String, BigDecimal> e : newThisByBill.entrySet()) {
            String billNo = e.getKey();
            BigDecimal thisTax = invoiceAmount.signum() > 0
                    ? totalTax.multiply(e.getValue()).divide(invoiceAmount, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            jdbcTemplate.update(
                    "INSERT INTO pur_invoice_match(match_id, invoice_id, invoice_no, bill_type, bill_no, " +
                            "bill_amount, matched_before, this_amount, this_tax_amount, settle_flag, diff_amount, " +
                            "applied_amount, diff_reason) " +
                            "VALUES (?, ?, ?, '收货单', ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                    "PIM" + uuid(), invoiceId, invoiceNo, billNo, billAmountByBill.get(billNo),
                    beforeByBill.get(billNo), e.getValue(), thisTax,
                    settleByBill.get(billNo) ? 1 : 0, diffByBill.get(billNo), appliedByBill.get(billNo));
        }
        for (Map<String, Object> ln : lines) {
            jdbcTemplate.update(
                    "INSERT INTO pur_invoice_line(line_id, invoice_id, sort_order, goods_code, goods_name, spec, " +
                            "unit_name, qty, price, tax_rate, untaxed_amount, tax_amount, amount, source_receipt_no) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ln.get("lineId"), invoiceId, ln.get("sort"),
                    ln.get("goodsCode"), ln.get("goodsName"), ln.get("spec"), ln.get("unitName"),
                    ln.get("qty"), ln.get("price"), ln.get("taxRate"),
                    ln.get("untaxed"), ln.get("tax"), ln.get("amount"),
                    strOrNull(ln.get("sourceReceiptNo")));
        }

        BigDecimal totalUntaxed = invoiceAmount.subtract(totalTax).setScale(2, RoundingMode.HALF_UP);
        String matchStatus = matchedSum.signum() == 0 ? "未勾稽"
                : invoiceAmount.subtract(matchedSum).abs().compareTo(TOLERANCE) <= 0 ? "已勾稽" : "部分勾稽";
        jdbcTemplate.update(
                "UPDATE pur_invoice SET untaxed_amount = ?, tax_amount = ?, matched_amount = ?, match_status = ? " +
                        "WHERE invoice_id = ?",
                totalUntaxed, totalTax, matchedSum, matchStatus, invoiceId);
        // 行级回写：勾稽商品行已重建，按涉及的全部收货单（含本次移除勾稽的旧单）重算
        for (String billNo : allBills) refreshReceiptLineInvoiced(billNo);
        log("purchase.invoice", "MATCH_SAVE", invoiceNo, "已审核发票继续勾稽：" + matchAgg.size()
                + " 条商品行、" + newThisByBill.size() + " 张收货单，累计来票金额 " + plain(matchedSum));
        return ApiResponse.ok(Map.of("invoiceId", invoiceId, "invoiceNo", invoiceNo,
                "status", "已审核", "matchStatus", matchStatus, "matchedAmount", matchedSum,
                "effect", "勾稽已保存并回写 " + newThisByBill.size() + " 张收货单的来票状态"));
    }

    /**
     * 反审核/作废：把本发票对收货单/应付来票额的【实际贡献】(applied_amount) 精确剥离。
     * 不用 matched_before 整体复位——其后可能有其他发票勾稽同一收货单，整体复位会误伤。
     *
     * @return 涉及的收货单号（调用方在发票状态翻转后需逐单重算商品行级来票回写）
     */
    private List<String> rollbackMatches(String invoiceId) {
        List<Map<String, Object>> matchRows = jdbcTemplate.queryForList(
                "SELECT bill_no, applied_amount FROM pur_invoice_match WHERE invoice_id = ?", invoiceId);
        List<String> billNos = new ArrayList<>();
        for (Map<String, Object> m : matchRows) {
            String billNo = str(pick(m, "bill_no"));
            billNos.add(billNo);
            BigDecimal applied = toBd(pick(m, "applied_amount"));
            if (applied.signum() == 0) continue;
            List<Map<String, Object>> bills = jdbcTemplate.queryForList(
                    "SELECT goods_amount, invoiced_amount FROM pur_receipt WHERE receipt_no = ?", billNo);
            if (bills.isEmpty()) continue;
            BigDecimal billAmount = toBd(pick(bills.get(0), "goods_amount"));
            BigDecimal invoiced = toBd(pick(bills.get(0), "invoiced_amount"));
            BigDecimal target = invoiced.subtract(applied);
            if (target.signum() < 0) target = BigDecimal.ZERO;
            String invoiceStatus = invoiceStatusOf(target, billAmount);
            jdbcTemplate.update(
                    "UPDATE pur_receipt SET invoiced_amount = ?, invoice_status = ? WHERE receipt_no = ?",
                    target, invoiceStatus, billNo);
            jdbcTemplate.update(
                    "UPDATE fin_ap SET invoiced_amount = ?, invoice_status = ? WHERE source_bill = ? AND ap_amount > 0",
                    target, invoiceStatus, billNo);
        }
        return billNos;
    }

    /**
     * 重算某收货单【商品行级】已开票数量/金额（V94 回写列 pur_receipt_detail.invoiced_qty/invoiced_amount）。
     * 口径：仅已审核发票的勾稽商品行（与单据级 pur_receipt.invoiced_amount 一致），草稿/作废不计。
     * 审核 / 审核后改勾稽 / 反审核 / 作废后调用；调用时机须在发票状态落库之后（本发票状态决定是否计入）。
     */
    private void refreshReceiptLineInvoiced(String receiptNo) {
        jdbcTemplate.update(
                "UPDATE pur_receipt_detail d SET invoiced_qty = COALESCE((" +
                        "SELECT SUM(ml.this_qty) FROM pur_invoice_match_line ml " +
                        "JOIN pur_invoice i ON i.invoice_id = ml.invoice_id " +
                        "WHERE ml.receipt_no = ? AND ml.goods_code = d.goods_code AND i.status = '已审核'" +
                        "), 0), " +
                        "invoiced_amount = COALESCE((" +
                        "SELECT SUM(ml.this_amount) FROM pur_invoice_match_line ml " +
                        "JOIN pur_invoice i ON i.invoice_id = ml.invoice_id " +
                        "WHERE ml.receipt_no = ? AND ml.goods_code = d.goods_code AND i.status = '已审核'" +
                        "), 0) " +
                        "WHERE d.receipt_id = (SELECT r.receipt_id FROM pur_receipt r WHERE r.receipt_no = ?)",
                receiptNo, receiptNo, receiptNo);
    }

    /**
     * 校验并聚合前端提交的勾稽商品行（草稿保存与审核后改勾稽共用）。
     * key = receiptNo|goodsCode 合并重复行。每行校验：收货单已审核、属于该供应商、
     * 本次数量不超过未开票数量（含草稿占量）。
     * <p>本次开票金额允许手工填（票面金额与收货结算额可能不一致），默认 = 数量×含税单价；
     * 税率允许修改，默认取源收货单明细税率。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> collectMatchLines(Object rawMatchLines, String invoiceId,
                                                               String supplierCode, String supplierName) {
        List<Map<String, Object>> reqMatchLines = rawMatchLines instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        Map<String, Map<String, Object>> matchAgg = new LinkedHashMap<>();
        for (Map<String, Object> req : reqMatchLines) {
            String receiptNo = str(req.get("receiptNo")).trim();
            String goodsCode = str(req.get("goodsCode")).trim();
            BigDecimal thisQty = toBd(req.get("thisQty"));
            if (receiptNo.isBlank() || goodsCode.isBlank() || thisQty.signum() <= 0) continue;

            List<Map<String, Object>> details = jdbcTemplate.queryForList(
                    "SELECT d.goods_code, d.goods_name, d.unit_name, d.qty, d.price, d.amount, d.tax_rate, " +
                            "r.source_inbound_no, r.source_order_no, r.supplier_code, r.supplier_name, r.status " +
                            "FROM pur_receipt_detail d JOIN pur_receipt r ON r.receipt_id = d.receipt_id " +
                            "WHERE r.receipt_no = ? AND d.goods_code = ?", receiptNo, goodsCode);
            if (details.isEmpty()) throw new IllegalArgumentException("勾稽商品行不存在：" + receiptNo + " / " + goodsCode);
            Map<String, Object> detail = details.get(0);
            if (!"APPROVED".equals(str(pick(detail, "status")))) {
                throw new IllegalArgumentException("收货单 " + receiptNo + " 未审核，不可勾稽");
            }
            if (!supplierCode.equals(str(pick(detail, "supplier_code")))) {
                throw new IllegalArgumentException("收货单 " + receiptNo + " 不属于供应商 " + supplierName);
            }
            BigDecimal lineQty = toBd(pick(detail, "qty"));
            BigDecimal price = toBd(pick(detail, "price"));
            // 已被其他发票（含草稿，不含作废与本单）占用的开票数量
            BigDecimal occupied = occupiedQty(receiptNo, goodsCode, invoiceId, false);
            BigDecimal freeQty = lineQty.subtract(occupied);
            if (thisQty.compareTo(freeQty.add(QTY_EPS)) > 0) {
                throw new IllegalArgumentException("商品 " + goodsCode + " 在收货单 " + receiptNo
                        + " 的未开票数量仅 " + plain(freeQty) + "，本次填写 " + plain(thisQty));
            }
            // 本次金额：前端可手工改，默认 数量×含税单价
            BigDecimal reqAmount = toBd(req.get("thisAmount"));
            BigDecimal thisAmount = reqAmount.signum() > 0
                    ? reqAmount.setScale(2, RoundingMode.HALF_UP)
                    : thisQty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            if (thisAmount.signum() < 0) {
                throw new IllegalArgumentException("勾稽金额不可为负：" + receiptNo + " / " + goodsCode);
            }
            // 税率：前端可改，默认源收货单明细税率
            String taxRate = strOrDefault(req.get("taxRate"), strOrDefault(pick(detail, "tax_rate"), "13%"));

            String key = receiptNo + "|" + goodsCode;
            Map<String, Object> agg = matchAgg.computeIfAbsent(key, k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("receiptNo", receiptNo);
                m.put("inboundNo", str(pick(detail, "source_inbound_no")));
                m.put("orderNo", str(pick(detail, "source_order_no")));
                m.put("goodsCode", goodsCode);
                m.put("goodsName", str(pick(detail, "goods_name")));
                m.put("unitName", str(pick(detail, "unit_name")));
                m.put("taxRate", taxRate);
                m.put("qty", lineQty);
                m.put("price", price);
                m.put("amount", toBd(pick(detail, "amount")));
                m.put("thisQty", BigDecimal.ZERO);
                m.put("thisAmount", BigDecimal.ZERO);
                return m;
            });
            agg.put("thisQty", ((BigDecimal) agg.get("thisQty")).add(thisQty));
            agg.put("thisAmount", ((BigDecimal) agg.get("thisAmount")).add(thisAmount));
            agg.put("taxRate", taxRate); // 重复行以最后一次税率为准
        }
        // 规格从商品档案补
        for (Map<String, Object> m : matchAgg.values()) {
            List<Map<String, Object>> g = jdbcTemplate.queryForList(
                    "SELECT spec FROM base_goods WHERE goods_code = ?", m.get("goodsCode"));
            m.put("spec", g.isEmpty() ? "" : strOrNull(pick(g.get(0), "spec")));
        }
        return matchAgg;
    }

    /** 勾稽行合计金额。 */
    private static BigDecimal matchSumOf(Map<String, Map<String, Object>> matchAgg) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> m : matchAgg.values()) sum = sum.add(toBd(m.get("thisAmount")));
        return sum;
    }

    /**
     * 由勾稽商品行聚合发票商品明细（pur_invoice_line）：同商品合并数量/金额、单价加权平均，
     * 税额按各行税率价内倒算。rawManualLines 为草稿期手工补行（审核后改勾稽不支持）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildInvoiceLines(Map<String, Map<String, Object>> matchAgg,
                                                        Object rawManualLines) {
        Map<String, Map<String, Object>> lineMap = new LinkedHashMap<>();
        Map<String, List<String>> lineSources = new HashMap<>();
        for (Map<String, Object> m : matchAgg.values()) {
            String goodsCode = str(m.get("goodsCode"));
            Map<String, Object> ln = lineMap.computeIfAbsent(goodsCode, k -> {
                Map<String, Object> x = new HashMap<>();
                x.put("goodsCode", goodsCode);
                x.put("goodsName", m.get("goodsName"));
                x.put("spec", m.get("spec"));
                x.put("unitName", m.get("unitName"));
                x.put("qty", BigDecimal.ZERO);
                x.put("amount", BigDecimal.ZERO);
                x.put("taxRate", m.get("taxRate"));
                x.put("auto", true);
                return x;
            });
            ln.put("qty", ((BigDecimal) ln.get("qty")).add(toBd(m.get("thisQty"))));
            ln.put("amount", ((BigDecimal) ln.get("amount")).add(toBd(m.get("thisAmount"))));
            lineSources.computeIfAbsent(goodsCode, k -> new ArrayList<>()).add(str(m.get("receiptNo")));
        }
        List<Map<String, Object>> reqLines = rawManualLines instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        for (Map<String, Object> reqLine : reqLines) {
            String goodsCode = str(reqLine.get("goodsCode")).trim();
            if (goodsCode.isBlank()) continue;
            BigDecimal qty = toBd(reqLine.get("qty"));
            BigDecimal price = toBd(reqLine.get("price"));
            if (qty.signum() <= 0 || price.signum() < 0) continue;
            BigDecimal amount = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            String taxRateStr = strOrDefault(reqLine.get("taxRate"), "13%");
            Map<String, Object> ln = lineMap.computeIfAbsent(goodsCode, k -> {
                Map<String, Object> x = new HashMap<>();
                x.put("goodsCode", goodsCode);
                x.put("goodsName", str(reqLine.get("goodsName")));
                x.put("spec", str(reqLine.get("spec")));
                x.put("unitName", str(reqLine.get("unitName")));
                x.put("qty", BigDecimal.ZERO);
                x.put("amount", BigDecimal.ZERO);
                x.put("taxRate", taxRateStr);
                x.put("auto", Boolean.TRUE.equals(reqLine.get("auto")));
                return x;
            });
            ln.put("qty", ((BigDecimal) ln.get("qty")).add(qty));
            ln.put("amount", ((BigDecimal) ln.get("amount")).add(amount));
        }

        List<Map<String, Object>> lines = new ArrayList<>();
        int sort = 0;
        for (Map<String, Object> ln : lineMap.values()) {
            BigDecimal qty = toBd(ln.get("qty"));
            BigDecimal amount = toBd(ln.get("amount"));
            BigDecimal price = qty.signum() > 0
                    ? amount.divide(qty, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal tax = taxInclusiveTax(amount, parseTaxRate(str(ln.get("taxRate"))));
            List<String> srcs = lineSources.getOrDefault(str(ln.get("goodsCode")), List.of());
            String sourceReceipt = srcs.size() == 1 ? srcs.get(0) : null;
            Map<String, Object> row = new HashMap<>();
            row.put("lineId", "PIL" + uuid());
            row.put("sort", sort++);
            row.put("goodsCode", ln.get("goodsCode"));
            row.put("goodsName", ln.get("goodsName"));
            row.put("spec", ln.get("spec"));
            row.put("unitName", ln.get("unitName"));
            row.put("qty", qty);
            row.put("price", price);
            row.put("taxRate", str(ln.get("taxRate")));
            row.put("tax", tax);
            row.put("untaxed", amount.subtract(tax));
            row.put("amount", amount);
            row.put("sourceReceiptNo", sourceReceipt);
            lines.add(row);
        }
        return lines;
    }

    // ============ 认证 & 备注 ============

    @PostMapping("/certify")
    @Transactional
    public ApiResponse<Map<String, Object>> certify(@RequestBody Map<String, Object> request) {
        String key = strOrDefault(request.get("invoiceId"), str(request.get("bizId")));
        String certStatus = strOrDefault(request.get("certStatus"), "已认证");
        if (!"已认证".equals(certStatus) && !"未认证".equals(certStatus) && !"无需认证".equals(certStatus)) {
            throw new IllegalArgumentException("认证状态仅支持：未认证 / 已认证 / 无需认证");
        }
        Map<String, Object> inv = requireInvoice(key);
        if (!"已审核".equals(str(pick(inv, "status")))) {
            throw new IllegalArgumentException("仅已审核发票可登记认证");
        }
        String invoiceId = str(pick(inv, "invoice_id"));
        String invoiceNo = str(pick(inv, "invoice_no"));
        LocalDate certDate = "已认证".equals(certStatus) ? LocalDate.now() : null;
        jdbcTemplate.update("UPDATE pur_invoice SET cert_status = ?, cert_date = ? WHERE invoice_id = ?",
                certStatus, certDate, invoiceId);
        log("purchase.invoice", "CERTIFY", invoiceNo, "采购发票认证状态 → " + certStatus);
        return ApiResponse.ok(Map.of("invoiceId", invoiceId, "certStatus", certStatus,
                "effect", "认证状态已更新为：" + certStatus));
    }

    /** 审核后追加备注（主信息锁定，仅备注可改）。 */
    @PostMapping("/update-remark")
    @Transactional
    public ApiResponse<Map<String, Object>> updateRemark(@RequestBody Map<String, Object> request) {
        String key = strOrDefault(request.get("invoiceId"), str(request.get("bizId")));
        Map<String, Object> inv = requireInvoice(key);
        if (!"已审核".equals(str(pick(inv, "status")))) {
            throw new IllegalArgumentException("仅已审核发票可追加备注（草稿备注随保存更新）");
        }
        String invoiceId = str(pick(inv, "invoice_id"));
        String invoiceNo = str(pick(inv, "invoice_no"));
        String remark = str(request.get("remark"));
        if (remark.length() > 256) throw new IllegalArgumentException("备注最长 256 字");
        jdbcTemplate.update("UPDATE pur_invoice SET remark = ? WHERE invoice_id = ?", remark, invoiceId);
        log("purchase.invoice", "REMARK", invoiceNo, "采购发票追加备注");
        return ApiResponse.ok(Map.of("invoiceId", invoiceId, "effect", "备注已保存"));
    }

    // ============ 可勾稽商品行 ============

    /**
     * 同供应商已审核收货单中、尚有未开票数量的商品行。
     * 列：进货单号(采购订单)/入库单号/入库日期/商品/单位/规格/数量/单价/金额/税率/
     *     已开票数量/未开票数量/已开票金额/未开票金额。
     * 已被其他发票（含草稿，不含作废；编辑时排除本发票）占用的数量/金额不计入未开票。
     * 金额口径含税；金额占用取勾稽行 this_amount（可为手工票面金额，不完全等于数量×单价）。
     */
    @PostMapping("/available-lines")
    public ApiResponse<List<Map<String, Object>>> availableLines(@RequestBody Map<String, Object> request) {
        String supplierCode = str(request.get("supplierCode")).trim();
        if (supplierCode.isBlank()) return ApiResponse.ok(List.of());
        String excludeInvoiceId = strOrNull(request.get("excludeInvoiceId"));
        String keyword = str(request.get("keyword")).trim();
        String sql =
                "SELECT r.receipt_no, r.source_inbound_no AS inbound_no, r.source_order_no AS order_no, " +
                        "r.receipt_date AS inbound_date, r.warehouse, " +
                        "d.goods_code, d.goods_name, g.spec AS spec, d.unit_name, " +
                        "d.qty, d.price, d.amount, d.tax_rate, " +
                        "COALESCE(occ.occ_qty, 0) AS invoiced_qty, " +
                        "d.qty - COALESCE(occ.occ_qty, 0) AS uninvoiced_qty, " +
                        "COALESCE(occ.occ_amt, 0) AS invoiced_amount, " +
                        "d.amount - COALESCE(occ.occ_amt, 0) AS uninvoiced_amount " +
                        "FROM pur_receipt_detail d " +
                        "JOIN pur_receipt r ON r.receipt_id = d.receipt_id " +
                        "LEFT JOIN base_goods g ON g.goods_code = d.goods_code " +
                        "LEFT JOIN ( " +
                        "  SELECT ml.receipt_no, ml.goods_code, " +
                        "         SUM(ml.this_qty) AS occ_qty, SUM(ml.this_amount) AS occ_amt " +
                        "  FROM pur_invoice_match_line ml " +
                        "  JOIN pur_invoice i ON i.invoice_id = ml.invoice_id " +
                        "  WHERE i.status <> '已作废' AND i.invoice_id <> ? " +
                        "  GROUP BY ml.receipt_no, ml.goods_code " +
                        ") occ ON occ.receipt_no = r.receipt_no AND occ.goods_code = d.goods_code " +
                        "WHERE r.status = 'APPROVED' AND r.supplier_code = ? " +
                        "  AND (d.qty - COALESCE(occ.occ_qty, 0)) > 0.0001 " +
                        (keyword.isBlank() ? "" : "  AND (d.goods_code LIKE ? OR d.goods_name LIKE ?) ") +
                        "ORDER BY r.receipt_date DESC, r.receipt_no, d.goods_code";
        List<Map<String, Object>> rows;
        if (keyword.isBlank()) {
            rows = jdbcTemplate.queryForList(sql, excludeInvoiceId == null ? "" : excludeInvoiceId, supplierCode);
        } else {
            String kw = "%" + keyword + "%";
            rows = jdbcTemplate.queryForList(sql, excludeInvoiceId == null ? "" : excludeInvoiceId,
                    supplierCode, kw, kw);
        }
        return ApiResponse.ok(rows.stream().map(PurchaseInvoiceController::camelize).toList());
    }

    /**
     * 某收货单某商品已被其他发票占用的开票数量。
     * @param auditedOnly true=仅统计已审核发票（审核时点校验）；false=含草稿（选单/草稿保存防重复占量）
     */
    private BigDecimal occupiedQty(String receiptNo, String goodsCode, String excludeInvoiceId, boolean auditedOnly) {
        String sql = "SELECT COALESCE(SUM(ml.this_qty), 0) FROM pur_invoice_match_line ml " +
                "JOIN pur_invoice i ON i.invoice_id = ml.invoice_id " +
                "WHERE ml.receipt_no = ? AND ml.goods_code = ? AND i.invoice_id <> ? " +
                (auditedOnly ? "AND i.status = '已审核' " : "AND i.status <> '已作废' ");
        List<Map<String, Object>> r = jdbcTemplate.queryForList(sql, receiptNo, goodsCode,
                excludeInvoiceId == null ? "" : excludeInvoiceId);
        return r.isEmpty() ? BigDecimal.ZERO : toBd(r.get(0).values().iterator().next());
    }

    // ============ 报表 ============

    /**
     * R1 采购来票跟踪。filters.dimension：bill（单据级，默认）/ goods（商品级）。
     */
    @PostMapping("/report/track/page")
    public ApiResponse<PageResult<Map<String, Object>>> trackReport(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? new HashMap<>() : new HashMap<>(request.filters());
        String dimension = str(filters.getOrDefault("dimension", "bill"));
        filters.remove("dimension"); // 维度参数不参与行模糊过滤
        List<Map<String, Object>> out = "goods".equalsIgnoreCase(dimension) ? trackByGoods() : trackByBill();
        return ApiResponse.ok(PageResult.of(out, new PageRequest(
                request.pageNo(), request.pageSize(), request.sortField(), request.sortOrder(), filters)));
    }

    /** 单据级：每张已审核收货单的来票情况。 */
    private List<Map<String, Object>> trackByBill() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT receipt_no, receipt_date, source_order_no, supplier_code, supplier_name, warehouse, " +
                        "goods_amount, invoiced_amount, invoice_status FROM pur_receipt WHERE status = 'APPROVED' " +
                        "ORDER BY receipt_date DESC, receipt_no DESC");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            BigDecimal goods = toBd(row.get("goodsAmount"));
            BigDecimal invoiced = toBd(row.get("invoicedAmount"));
            BigDecimal unbilled = goods.subtract(invoiced).setScale(2, RoundingMode.HALF_UP);
            row.put("unbilledAmount", unbilled);
            row.put("invoiceRate", rate(invoiced, goods));
            row.put("agingDays", ChronoUnit.DAYS.between(
                    parseDate(row.get("receiptDate"), LocalDate.now()), LocalDate.now()));
            List<String> invNos = jdbcTemplate.queryForList(
                    "SELECT m.invoice_no FROM pur_invoice_match m " +
                            "JOIN pur_invoice i ON i.invoice_id = m.invoice_id " +
                            "WHERE m.bill_no = ? AND i.status = '已审核' ORDER BY m.invoice_no",
                    String.class, str(row.get("receiptNo")));
            row.put("invoiceNos", String.join("、", invNos));
            out.add(row);
        }
        return out;
    }

    /** 商品级：收货商品汇总 FULL JOIN 发票商品汇总（UNION ALL 兼容 H2/MySQL）。 */
    private List<Map<String, Object>> trackByGoods() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT goods_code, MIN(goods_name) AS goods_name, MIN(unit_name) AS unit_name, " +
                        "SUM(recv_qty) AS recv_qty, SUM(recv_amt) AS recv_amt, " +
                        "SUM(inv_qty) AS inv_qty, SUM(inv_amt) AS inv_amt FROM ( " +
                        "  SELECT d.goods_code, d.goods_name, d.unit_name, d.qty AS recv_qty, d.amount AS recv_amt, " +
                        "         0 AS inv_qty, 0 AS inv_amt " +
                        "  FROM pur_receipt_detail d JOIN pur_receipt r ON r.receipt_id = d.receipt_id " +
                        "  WHERE r.status = 'APPROVED' " +
                        "  UNION ALL " +
                        "  SELECT l.goods_code, l.goods_name, l.unit_name, 0, 0, l.qty, l.amount " +
                        "  FROM pur_invoice_line l JOIN pur_invoice i ON i.invoice_id = l.invoice_id " +
                        "  WHERE i.status = '已审核' " +
                        ") t GROUP BY goods_code ORDER BY SUM(recv_amt) - SUM(inv_amt) DESC");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            BigDecimal recvAmt = toBd(row.get("recvAmt"));
            BigDecimal invAmt = toBd(row.get("invAmt"));
            row.put("unbilledAmount", recvAmt.subtract(invAmt).setScale(2, RoundingMode.HALF_UP));
            row.put("invoiceRate", rate(invAmt, recvAmt));
            out.add(row);
        }
        return out;
    }

    /** R2 供应商来票统计（累计口径：未票余额取收货单实时来票额）。 */
    @PostMapping("/report/supplier/page")
    public ApiResponse<PageResult<Map<String, Object>>> supplierReport(@RequestBody PageRequest request) {
        Map<String, Map<String, Object>> bySupplier = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT supplier_code, supplier_name, " +
                        "SUM(goods_amount) AS recv_amount, SUM(invoiced_amount) AS invoiced_amount " +
                        "FROM pur_receipt WHERE status = 'APPROVED' GROUP BY supplier_code, supplier_name")) {
            Map<String, Object> row = camelize(r);
            String code = str(row.get("supplierCode"));
            bySupplier.computeIfAbsent(code, k -> new LinkedHashMap<>(row));
        }
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT supplier_code, supplier_name, COUNT(*) AS invoice_count, " +
                        "SUM(total_amount) AS invoice_amount, SUM(tax_amount) AS tax_amount, " +
                        "SUM(CASE WHEN cert_status = '已认证' THEN tax_amount ELSE 0 END) AS certified_tax, " +
                        "SUM(CASE WHEN cert_status = '未认证' THEN 1 ELSE 0 END) AS uncertified_count " +
                        "FROM pur_invoice WHERE status = '已审核' GROUP BY supplier_code, supplier_name")) {
            String code = str(pick(r, "supplier_code"));
            Map<String, Object> row = bySupplier.computeIfAbsent(code, k -> {
                Map<String, Object> m = camelize(r);
                m.put("recvAmount", BigDecimal.ZERO);
                m.put("invoicedAmount", BigDecimal.ZERO);
                return m;
            });
            row.put("invoiceCount", toBd(pick(r, "invoice_count")));
            row.put("invoiceAmount", toBd(pick(r, "invoice_amount")));
            row.put("taxAmount", toBd(pick(r, "tax_amount")));
            row.put("certifiedTax", toBd(pick(r, "certified_tax")));
            row.put("uncertifiedCount", toBd(pick(r, "uncertified_count")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : bySupplier.values()) {
            row.putIfAbsent("invoiceCount", BigDecimal.ZERO);
            row.putIfAbsent("invoiceAmount", BigDecimal.ZERO);
            row.putIfAbsent("taxAmount", BigDecimal.ZERO);
            row.putIfAbsent("certifiedTax", BigDecimal.ZERO);
            row.putIfAbsent("uncertifiedCount", BigDecimal.ZERO);
            BigDecimal recv = toBd(row.get("recvAmount"));
            BigDecimal invoiced = toBd(row.get("invoicedAmount"));
            row.put("unbilledAmount", recv.subtract(invoiced).setScale(2, RoundingMode.HALF_UP));
            row.put("invoiceRate", rate(invoiced, recv));
            out.add(row);
        }
        out.sort((a, b) -> toBd(b.get("unbilledAmount")).compareTo(toBd(a.get("unbilledAmount"))));
        return ApiResponse.ok(PageResult.of(out, request));
    }

    /**
     * R4 未勾稽发票与勾稽差异。filters.dimension：unmatched（默认）/ diff。
     */
    @PostMapping("/report/unmatched/page")
    public ApiResponse<PageResult<Map<String, Object>>> unmatchedReport(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? new HashMap<>() : new HashMap<>(request.filters());
        String dimension = str(filters.getOrDefault("dimension", "unmatched"));
        filters.remove("dimension"); // 维度参数不参与行模糊过滤
        request = new PageRequest(request.pageNo(), request.pageSize(), request.sortField(),
                request.sortOrder(), filters);
        List<Map<String, Object>> out;
        if ("diff".equalsIgnoreCase(dimension)) {
            out = new ArrayList<>();
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT m.invoice_no, m.bill_no, m.bill_amount, m.matched_before, m.this_amount, " +
                            "m.diff_amount, m.diff_reason, i.supplier_name, i.issue_date " +
                            "FROM pur_invoice_match m JOIN pur_invoice i ON i.invoice_id = m.invoice_id " +
                            "WHERE i.status = '已审核' AND ABS(m.diff_amount) > 0.001 ORDER BY m.invoice_no")) {
                out.add(camelize(r));
            }
        } else {
            out = new ArrayList<>();
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT invoice_no, invoice_number, supplier_name, issue_date, total_amount, " +
                            "matched_amount, match_status, cert_status FROM pur_invoice " +
                            "WHERE status = '已审核' AND match_status <> '已勾稽' ORDER BY issue_date DESC")) {
                Map<String, Object> row = camelize(r);
                row.put("unmatchedAmount", toBd(row.get("totalAmount")).subtract(toBd(row.get("matchedAmount")))
                        .setScale(2, RoundingMode.HALF_UP));
                out.add(row);
            }
        }
        return ApiResponse.ok(PageResult.of(out, request));
    }

    // ============ 工具方法 ============

    private Map<String, Object> requireInvoice(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("缺少发票标识");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM pur_invoice WHERE invoice_id = ? OR invoice_no = ?", key, key);
        if (rows.isEmpty()) throw new IllegalArgumentException("发票不存在：" + key);
        return rows.get(0);
    }

    /** 来票状态：未票余额 ≤ 容差视为已来票（尾差置平场景）。 */
    private static String invoiceStatusOf(BigDecimal invoiced, BigDecimal billAmount) {
        if (invoiced.signum() <= 0) return "未来票";
        if (billAmount.subtract(invoiced).abs().compareTo(TOLERANCE) <= 0) return "已来票";
        return "部分来票";
    }

    /** 来票率（百分比字符串，保留 1 位小数）。 */
    private static String rate(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return part != null && part.signum() > 0 ? "100%" : "0%";
        return part.multiply(new BigDecimal("100"))
                .divide(whole, 1, RoundingMode.HALF_UP) + "%";
    }

    /** 解析税率字符串，支持 "13%" / "13" / "0.13"。 */
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

    /** 价内税倒算：税额 = 含税金额 × 税率 / (1 + 税率)。 */
    private static BigDecimal taxInclusiveTax(BigDecimal taxIncludedAmount, BigDecimal taxRate) {
        if (taxIncludedAmount == null || taxIncludedAmount.signum() == 0) return BigDecimal.ZERO;
        if (taxRate == null || taxRate.signum() == 0) return BigDecimal.ZERO;
        return taxIncludedAmount.multiply(taxRate)
                .divide(BigDecimal.ONE.add(taxRate), 2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isBlank() ? null : s;
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

    private static LocalDate parseDate(String s, LocalDate dft) {
        if (s == null || s.isBlank()) return dft;
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return dft; }
    }

    private static LocalDate parseDate(Object o, LocalDate dft) {
        if (o == null) return dft;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date sd) return sd.toLocalDate();
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        return parseDate(String.valueOf(o), dft);
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

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update(
                "INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail) " +
                        "VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)",
                "LOG" + uuid(), moduleCode, action, bizNo, detail);
    }

    public record AuditRequest(@NotBlank String bizId, String remark) {}
}
