package com.erp.finance.gl;

import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 总账 M4——业务单据审核/反审核钩子中央服务。
 *
 * 职责：各业务 Controller 在审核事务内（状态翻转、往来/库存写完之后）调用对应 onXxxAudited，
 * 反审核时调用 onXxxUnaudited；本服务只负责「按单据号重载数据 → 组装事件 payload → GlHookEmitter 发事件」，
 * 事件由 {@link GlBizEventListener} 在事务提交后落 fin_gl_event。
 *
 * 铁律：
 *  1. 钩子绝不能影响业务——所有 public 方法 catch Throwable 仅记日志；
 *  2. 不在业务事务里写任何总账表（发 Spring 事件而已）；
 *  3. payload 变量名与 V94 预置模板表达式严格对齐（amount_tax_incl/amount_excl/tax_amount/cost_amount/...）；
 *  4. 自动单据（费用单联动收款/结算中心生单等 business_source != BACKOFFICE）不丢收付款事件；
 *  5. 期初库存（other_inbound inbound_type='0'）不丢事件；整单拒收（签收金额 0）不丢事件。
 *
 * 金额口径（与业务模块一致，价内税）：
 *  - 采购收货：goods_amount 含税、tax_amount 税额、不含税=含税-税额；
 *  - 销售签收：sign_amount 含税、tax_amount 税额、untaxed_amount 不含税；
 *  - 采购退货/销售退货：模板按红字同构设计，payload 金额与数量取负；
 *  - 出库/退货入库/盘点/其他出入库/报损：cost_amount 成本额。
 */
@Component
public class GlHookService {

    private static final Logger log = LoggerFactory.getLogger(GlHookService.class);

    /** 报损大额阈值（元）：整单成本 ≥ 阈值 → large_loss=true，入待处理财产损溢；参数 fin.gl.damage_large_threshold。 */
    private static final String PARAM_DAMAGE_LARGE = "fin.gl.damage_large_threshold";
    private static final BigDecimal DEFAULT_DAMAGE_LARGE = new BigDecimal("2000");

    private final JdbcTemplate jdbc;
    private final GlHookEmitter emitter;
    private final SysParamService sysParam;

    public GlHookService(JdbcTemplate jdbc, GlHookEmitter emitter, SysParamService sysParam) {
        this.jdbc = jdbc;
        this.emitter = emitter;
        this.sysParam = sysParam;
    }

    // ==================== 1. 采购收货 PUR_IN ====================

    public void onPurchaseReceiptAudited(String receiptNo) {
        safe("PUR_IN", receiptNo, () -> {
            Map<String, Object> head = head(
                    "SELECT receipt_no, supplier_code, supplier_name, receipt_date, goods_amount, tax_amount " +
                            "FROM pur_receipt WHERE receipt_no = ? OR receipt_id = ?", receiptNo, receiptNo);
            if (head == null) return;
            emitPurchase("PUR_IN", "采购收货单", head, false);
        });
    }

    public void onPurchaseReceiptUnaudited(String receiptNo) {
        safe("PUR_IN", receiptNo, () -> {
            Map<String, Object> head = head(
                    "SELECT receipt_no, supplier_code, supplier_name, receipt_date, goods_amount, tax_amount " +
                            "FROM pur_receipt WHERE receipt_no = ? OR receipt_id = ?", receiptNo, receiptNo);
            if (head == null) return;
            emitPurchase("PUR_IN", "采购收货单", head, true);
        });
    }

    // ==================== 2. 采购退货 PUR_RETURN（金额为负） ====================

    public void onPurchaseReturnAudited(String returnNo) {
        safe("PUR_RETURN", returnNo, () -> {
            Map<String, Object> head = head(
                    "SELECT return_no, supplier_code, supplier_name, return_date, goods_amount, tax_amount " +
                            "FROM pur_return WHERE return_no = ? OR return_id = ?", returnNo, returnNo);
            if (head == null) return;
            emitPurchase("PUR_RETURN", "采购退货单", head, false);
        });
    }

    public void onPurchaseReturnUnaudited(String returnNo) {
        safe("PUR_RETURN", returnNo, () -> {
            Map<String, Object> head = head(
                    "SELECT return_no, supplier_code, supplier_name, return_date, goods_amount, tax_amount " +
                            "FROM pur_return WHERE return_no = ? OR return_id = ?", returnNo, returnNo);
            if (head == null) return;
            emitPurchase("PUR_RETURN", "采购退货单", head, true);
        });
    }

    /** 采购收货/采购退货同构：PUR_RETURN 金额数量取负（反审核事件再取负回到正数）。 */
    private void emitPurchase(String eventCode, String billType, Map<String, Object> head, boolean reverse) {
        boolean ret = "PUR_RETURN".equals(eventCode);
        String billNo = TmsUtil.str(ret ? head.get("returnNo") : head.get("receiptNo"));
        LocalDate bizDate = TmsUtil.date(ret ? head.get("returnDate") : head.get("receiptDate"));
        String headTable = ret ? "pur_return" : "pur_receipt";
        String noCol = ret ? "return_no" : "receipt_no";
        String idCol = ret ? "return_id" : "receipt_id";
        // 反向事件 payload 与正向一致（渲染端对 reverse 统一取负）；退货本身是红字同构，恒为负。
        BigDecimal sign = ret ? BigDecimal.ONE.negate() : BigDecimal.ONE;

        BigDecimal incl = bd(head.get("goodsAmount")).multiply(sign);
        BigDecimal tax = bd(head.get("taxAmount")).multiply(sign);
        BigDecimal excl = incl.subtract(tax);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("supplier_code", TmsUtil.str(head.get("supplierCode")));
        p.put("supplier_name", TmsUtil.str(head.get("supplierName")));
        p.put("amount_tax_incl", incl);
        p.put("tax_amount", tax);
        p.put("amount_excl", excl);
        p.put("lines", goodsLines(ret ? "pur_return_detail" : "pur_receipt_detail", idCol,
                "SELECT " + idCol + " id FROM " + headTable + " WHERE " + noCol + " = ?", billNo, sign));

        if (reverse) emitter.emitReverse(eventCode, billType, billNo, bizDate, incl.abs(), p, null);
        else emitter.emit(eventCode, billType, billNo, bizDate, incl.abs(), p);
    }

    /** 采购类商品明细行：含税额/税额/不含税额/数量，按 sign 决定正负。 */
    private List<Map<String, Object>> goodsLines(String detailTable, String fk, String idSql,
                                                 String billNo, BigDecimal sign) {
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Map<String, Object>> ids = TmsUtil.queryCamel(jdbc, idSql, billNo);
        if (ids.isEmpty()) return lines;
        String id = TmsUtil.str(ids.get(0).get("id"));
        for (Map<String, Object> d : TmsUtil.queryCamel(jdbc,
                "SELECT goods_code, goods_name, qty, amount, tax_amount FROM " + detailTable
                        + " WHERE " + fk + " = ? ORDER BY detail_id", id)) {
            BigDecimal dIncl = bd(d.get("amount")).multiply(sign);
            BigDecimal dTax = bd(d.get("taxAmount")).multiply(sign);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("goods_code", TmsUtil.str(d.get("goodsCode")));
            line.put("goods_name", TmsUtil.str(d.get("goodsName")));
            line.put("qty", bd(d.get("qty")).multiply(sign));
            line.put("amount_tax_incl", dIncl);
            line.put("tax_amount", dTax);
            line.put("amount_excl", dIncl.subtract(dTax));
            lines.add(line);
        }
        return lines;
    }

    // ==================== 3. 销售出库成本 SALE_OUT ====================

    public void onSalesOutboundAudited(String outboundNo) {
        safe("SALE_OUT", outboundNo, () -> {
            Map<String, Object> head = head(
                    "SELECT outbound_no, bill_date FROM sales_outbound " +
                            "WHERE outbound_no = ? OR outbound_id = ?", outboundNo, outboundNo);
            if (head == null) return;
            String billNo = TmsUtil.str(head.get("outboundNo"));
            // 出库单明细不回写成本：成本以库存流水（审核时按移动加权平均写入）为准
            List<Map<String, Object>> lines = new ArrayList<>();
            BigDecimal cost = BigDecimal.ZERO;
            for (Map<String, Object> d : TmsUtil.queryCamel(jdbc,
                    "SELECT goods_code, goods_name, SUM(qty) qty, SUM(amount) cost_amount " +
                            "FROM inv_stock_ledger WHERE source_bill = ? AND direction = 'OUT' " +
                            "GROUP BY goods_code, goods_name ORDER BY goods_code", billNo)) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("goods_code", TmsUtil.str(d.get("goodsCode")));
                line.put("goods_name", TmsUtil.str(d.get("goodsName")));
                line.put("qty", bd(d.get("qty")));
                line.put("cost_amount", bd(d.get("costAmount")));
                lines.add(line);
                cost = cost.add(bd(d.get("costAmount")));
            }
            if (lines.isEmpty()) return; // 无库存流水（异常数据）→ 不丢事件，避免渲染出空凭证
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("cost_amount", cost);
            p.put("lines", lines);
            emitter.emit("SALE_OUT", "销售出库单", billNo, TmsUtil.date(head.get("billDate")), cost, p);
        });
    }

    // ==================== 4. 销售签收 SALE_SIGN ====================

    public void onSalesSignAudited(String receiptNo) {
        safe("SALE_SIGN", receiptNo, () -> emitSalesSign(receiptNo, false));
    }

    public void onSalesSignUnaudited(String receiptNo) {
        safe("SALE_SIGN", receiptNo, () -> emitSalesSign(receiptNo, true));
    }

    private void emitSalesSign(String receiptNo, boolean reverse) {
        Map<String, Object> head = head(
                "SELECT receipt_no, customer_code, customer_name, receipt_date, sign_amount, tax_amount, untaxed_amount " +
                        "FROM sales_receipt WHERE receipt_no = ? OR receipt_id = ?", receiptNo, receiptNo);
        if (head == null) return;
        String billNo = TmsUtil.str(head.get("receiptNo"));
        BigDecimal sign = bd(head.get("signAmount"));
        if (sign.abs().compareTo(new BigDecimal("0.005")) < 0) return; // 整单拒收/未签收 → 无应收无收入，不丢事件
        BigDecimal tax = bd(head.get("taxAmount"));
        BigDecimal excl = bd(head.get("untaxedAmount"));

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("customer_code", TmsUtil.str(head.get("customerCode")));
        p.put("customer_name", TmsUtil.str(head.get("customerName")));
        p.put("amount_tax_incl", sign);
        p.put("tax_amount", tax);
        p.put("amount_excl", excl);

        List<Map<String, Object>> lines = new ArrayList<>();
        List<Map<String, Object>> ids = TmsUtil.queryCamel(jdbc,
                "SELECT receipt_id id FROM sales_receipt WHERE receipt_no = ?", billNo);
        if (!ids.isEmpty()) {
            for (Map<String, Object> d : TmsUtil.queryCamel(jdbc,
                    "SELECT goods_code, goods_name, COALESCE(signed_qty, qty) qty, sign_amount, tax_amount " +
                            "FROM sales_receipt_detail WHERE receipt_id = ? ORDER BY detail_id",
                    TmsUtil.str(ids.get(0).get("id")))) {
                BigDecimal lineIncl = bd(d.get("signAmount"));
                if (lineIncl.abs().compareTo(new BigDecimal("0.005")) < 0) continue;
                BigDecimal lineTax = bd(d.get("taxAmount"));
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("goods_code", TmsUtil.str(d.get("goodsCode")));
                line.put("goods_name", TmsUtil.str(d.get("goodsName")));
                line.put("qty", bd(d.get("qty")));
                line.put("amount_tax_incl", lineIncl);
                line.put("tax_amount", lineTax);
                line.put("amount_excl", lineIncl.subtract(lineTax));
                lines.add(line);
            }
        }
        p.put("lines", lines);
        // 反向事件 payload 与正向一致，渲染端按 reverse 统一取负
        if (reverse) emitter.emitReverse("SALE_SIGN", "销售发货单", billNo,
                TmsUtil.date(head.get("receiptDate")), sign, p, null);
        else emitter.emit("SALE_SIGN", "销售发货单", billNo, TmsUtil.date(head.get("receiptDate")), sign, p);
    }

    // ==================== 5a. 销售退货（应收红冲）SALE_RETURN（金额为负） ====================

    public void onSalesReturnAudited(String applyNo) {
        safe("SALE_RETURN", applyNo, () -> emitSalesReturn(applyNo, false));
    }

    public void onSalesReturnUnaudited(String applyNo) {
        safe("SALE_RETURN", applyNo, () -> emitSalesReturn(applyNo, true));
    }

    private void emitSalesReturn(String applyNo, boolean reverse) {
        Map<String, Object> head = head(
                "SELECT apply_no, customer_code, customer_name, bill_date, return_amount, amount " +
                        "FROM sales_return_apply WHERE apply_no = ? OR apply_id = ?", applyNo, applyNo);
        if (head == null) return;
        String billNo = TmsUtil.str(head.get("applyNo"));
        BigDecimal incl = bd(head.get("returnAmount"));
        if (incl.signum() == 0) incl = bd(head.get("amount"));
        // 模板按红字同构设计：退货事件 payload 恒为负；反审核事件 payload 相同，渲染端按 reverse 取负回到正数
        BigDecimal sign = BigDecimal.ONE.negate();
        incl = incl.multiply(sign);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("customer_code", TmsUtil.str(head.get("customerCode")));
        p.put("customer_name", TmsUtil.str(head.get("customerName")));

        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal totalTax = BigDecimal.ZERO;
        List<Map<String, Object>> ids = TmsUtil.queryCamel(jdbc,
                "SELECT apply_id id FROM sales_return_apply WHERE apply_no = ?", billNo);
        if (!ids.isEmpty()) {
            for (Map<String, Object> d : TmsUtil.queryCamel(jdbc,
                    // signed_qty 为司机签收数量（V60 加列，默认 0，后台仓库退货流程不回写），
                    // 为 0 时必须回落申请数量 qty，COALESCE 直接判空会取到 0
                    "SELECT goods_code, goods_name, COALESCE(NULLIF(signed_qty, 0), qty) qty, price, tax_rate " +
                            "FROM sales_return_apply_detail WHERE apply_id = ? ORDER BY detail_id",
                    TmsUtil.str(ids.get(0).get("id")))) {
                BigDecimal qty = bd(d.get("qty")).multiply(sign);
                BigDecimal lineIncl = bd(d.get("qty")).multiply(bd(d.get("price")))
                        .setScale(2, RoundingMode.HALF_UP).multiply(sign);
                BigDecimal lineTax = inclusiveTax(lineIncl.abs(), parseRate(TmsUtil.str(d.get("taxRate"))))
                        .multiply(sign);
                totalTax = totalTax.add(lineTax);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("goods_code", TmsUtil.str(d.get("goodsCode")));
                line.put("goods_name", TmsUtil.str(d.get("goodsName")));
                line.put("qty", qty);
                line.put("amount_tax_incl", lineIncl);
                line.put("tax_amount", lineTax);
                line.put("amount_excl", lineIncl.subtract(lineTax));
                lines.add(line);
            }
        }
        p.put("lines", lines);
        p.put("amount_tax_incl", incl);
        p.put("tax_amount", totalTax);
        p.put("amount_excl", incl.subtract(totalTax));
        if (reverse) emitter.emitReverse("SALE_RETURN", "销售退货单", billNo,
                TmsUtil.date(head.get("billDate")), incl.abs(), p, null);
        else emitter.emit("SALE_RETURN", "销售退货单", billNo, TmsUtil.date(head.get("billDate")),
                incl.abs(), p);
    }

    // ==================== 5b. 退货成本红冲 SALE_RETURN_COST ====================

    public void onSalesReturnCostAudited(String inboundNo) {
        safe("SALE_RETURN_COST", inboundNo, () -> {
            Map<String, Object> head = head(
                    "SELECT inbound_no, bill_date, cost_amount FROM sales_return_inbound " +
                            "WHERE inbound_no = ? OR inbound_id = ?", inboundNo, inboundNo);
            if (head == null) return;
            String billNo = TmsUtil.str(head.get("inboundNo"));
            // 退货入库明细表无成本列：成本以库存流水（审核时 purchaseInbound 按移动加权平均写入）为准
            List<Map<String, Object>> lines = new ArrayList<>();
            BigDecimal cost = BigDecimal.ZERO;
            for (Map<String, Object> d : TmsUtil.queryCamel(jdbc,
                    "SELECT goods_code, goods_name, SUM(qty) qty, SUM(amount) cost_amount " +
                            "FROM inv_stock_ledger WHERE source_bill = ? AND direction = 'IN' " +
                            "GROUP BY goods_code, goods_name ORDER BY goods_code", billNo)) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("goods_code", TmsUtil.str(d.get("goodsCode")));
                line.put("goods_name", TmsUtil.str(d.get("goodsName")));
                line.put("qty", bd(d.get("qty")));
                line.put("cost_amount", bd(d.get("costAmount")));
                lines.add(line);
                cost = cost.add(bd(d.get("costAmount")));
            }
            if (lines.isEmpty()) return; // 无库存流水（异常数据）→ 不丢事件
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("cost_amount", cost);
            p.put("lines", lines);
            emitter.emit("SALE_RETURN_COST", "退货入库单", billNo, TmsUtil.date(head.get("billDate")), cost, p);
        });
    }

    // ==================== 6. 收款 RECEIPT（仅后台手工单） ====================

    public void onReceiptAudited(String receiptNo) {
        safe("RECEIPT", receiptNo, () -> emitReceipt(receiptNo, false));
    }

    public void onReceiptUnaudited(String receiptNo) {
        safe("RECEIPT", receiptNo, () -> emitReceipt(receiptNo, true));
    }

    private void emitReceipt(String receiptNo, boolean reverse) {
        Map<String, Object> head = head(
                "SELECT receipt_no, receipt_date, counterparty_type, counterparty_code, counterparty_name, " +
                        "total_amount, business_source FROM fin_receipt_bill WHERE receipt_no = ? OR receipt_id = ?",
                receiptNo, receiptNo);
        if (head == null) return;
        if (!isBackoffice(TmsUtil.str(head.get("businessSource")))) return; // 费用联动/结算中心自动单不重复丢事件
        String billNo = TmsUtil.str(head.get("receiptNo"));

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("amount_tax_incl", bd(head.get("totalAmount")));
        applyCounterparty(p, TmsUtil.str(head.get("counterpartyType")),
                TmsUtil.str(head.get("counterpartyCode")), TmsUtil.str(head.get("counterpartyName")));
        // 资金科目：取第一条有金额的明细行资金账户
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbc,
                "SELECT fund_account, amount FROM fin_receipt_detail d " +
                        "WHERE d.receipt_id = (SELECT receipt_id FROM fin_receipt_bill WHERE receipt_no = ?) " +
                        "ORDER BY sort_order", billNo);
        for (Map<String, Object> d : details) {
            if (bd(d.get("amount")).signum() > 0) { applyFund(p, TmsUtil.str(d.get("fundAccount"))); break; }
        }
        // 核销的应收单号（存在 → 走应收账款；不存在 → 其他应收）
        List<Map<String, Object>> recs = TmsUtil.queryCamel(jdbc,
                "SELECT business_no FROM fin_reconcile_record WHERE receipt_no = ? ORDER BY created_at LIMIT 1", billNo);
        if (!recs.isEmpty()) p.put("ar_bill_no", TmsUtil.str(recs.get(0).get("businessNo")));
        else p.put("ar_bill_no", "");

        if (reverse) emitter.emitReverse("RECEIPT", "收款单", billNo,
                TmsUtil.date(head.get("receiptDate")), bd(head.get("totalAmount")), p, null);
        else emitter.emit("RECEIPT", "收款单", billNo, TmsUtil.date(head.get("receiptDate")),
                bd(head.get("totalAmount")), p);
    }

    // ==================== 7. 付款 PAYMENT（仅后台手工单） ====================

    public void onPaymentAudited(String paymentNo) {
        safe("PAYMENT", paymentNo, () -> emitPayment(paymentNo, false));
    }

    public void onPaymentUnaudited(String paymentNo) {
        safe("PAYMENT", paymentNo, () -> emitPayment(paymentNo, true));
    }

    private void emitPayment(String paymentNo, boolean reverse) {
        Map<String, Object> head = head(
                "SELECT payment_no, payment_date, counterparty_type, counterparty_code, counterparty_name, " +
                        "total_amount, business_source FROM fin_payment_bill WHERE payment_no = ? OR payment_id = ?",
                paymentNo, paymentNo);
        if (head == null) return;
        if (!isBackoffice(TmsUtil.str(head.get("businessSource")))) return;
        String billNo = TmsUtil.str(head.get("paymentNo"));

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("amount_tax_incl", bd(head.get("totalAmount")));
        applyCounterparty(p, TmsUtil.str(head.get("counterpartyType")),
                TmsUtil.str(head.get("counterpartyCode")), TmsUtil.str(head.get("counterpartyName")));
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbc,
                "SELECT fund_account, amount FROM fin_payment_detail d " +
                        "WHERE d.payment_id = (SELECT payment_id FROM fin_payment_bill WHERE payment_no = ?) " +
                        "ORDER BY sort_order", billNo);
        for (Map<String, Object> d : details) {
            if (bd(d.get("amount")).signum() > 0) { applyFund(p, TmsUtil.str(d.get("fundAccount"))); break; }
        }
        List<Map<String, Object>> recs = TmsUtil.queryCamel(jdbc,
                "SELECT business_no FROM fin_reconcile_record WHERE receipt_no = ? ORDER BY created_at LIMIT 1", billNo);
        if (!recs.isEmpty()) p.put("ap_bill_no", TmsUtil.str(recs.get(0).get("businessNo")));
        else p.put("ap_bill_no", "");

        if (reverse) emitter.emitReverse("PAYMENT", "付款单", billNo,
                TmsUtil.date(head.get("paymentDate")), bd(head.get("totalAmount")), p, null);
        else emitter.emit("PAYMENT", "付款单", billNo, TmsUtil.date(head.get("paymentDate")),
                bd(head.get("totalAmount")), p);
    }

    // ==================== 8/9. 费用单 EXPENSE（支出）/ OTHER_INCOME（收入） ====================

    public void onExpenseAudited(String expenseNo) {
        safe("EXPENSE", expenseNo, () -> {
            Map<String, Object> head = head(
                    "SELECT expense_no, expense_date, direction, counterparty_type, counterparty_code, counterparty_name, " +
                            "fund_account, department, handler, total_amount, total_tax_amount, total_excluding_tax_amount " +
                            "FROM fin_expense_bill WHERE expense_no = ? OR expense_id = ?", expenseNo, expenseNo);
            if (head == null) return;
            String billNo = TmsUtil.str(head.get("expenseNo"));
            // direction：OUT=费用支出 → EXPENSE；IN=收入 → OTHER_INCOME
            String direction = TmsUtil.str(head.get("direction"));
            boolean income = "IN".equals(direction);
            String eventCode = income ? "OTHER_INCOME" : "EXPENSE";

            Map<String, Object> p = new LinkedHashMap<>();
            BigDecimal incl = bd(head.get("totalAmount")).abs();
            BigDecimal tax = bd(head.get("totalTaxAmount")).abs();
            BigDecimal excl = bd(head.get("totalExcludingTaxAmount")).abs();
            p.put("amount_tax_incl", incl);
            p.put("tax_amount", tax);
            p.put("amount_excl", excl);
            // 进项税是否抵扣：一般纳税人且有税额才拆税行；否则税额资本化进费用（行 amount 取含税），
            // 保证借贷平衡（模板 L2 条件 expense_deductible == true 按整单头变量门控）
            String taxpayer = sysParam.get(GlConst.PARAM_TAXPAYER_TYPE, "GENERAL");
            boolean deductible = "GENERAL".equalsIgnoreCase(taxpayer) && tax.signum() > 0;
            p.put("expense_deductible", deductible);
            applyCounterparty(p, TmsUtil.str(head.get("counterpartyType")),
                    TmsUtil.str(head.get("counterpartyCode")), TmsUtil.str(head.get("counterpartyName")));
            applyFund(p, TmsUtil.str(head.get("fundAccount")));
            // 部门/经手人：档案存名称，辅助核算要编码 → 按名反查（查不到则不挂辅助）
            p.put("department_code", lookupCode(
                    "SELECT department_code code FROM base_department WHERE department_name = ?",
                    TmsUtil.str(head.get("department"))));
            p.put("employee_code", lookupCode(
                    "SELECT employee_code code FROM base_employee WHERE employee_name = ?",
                    TmsUtil.str(head.get("handler"))));

            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> d : TmsUtil.queryCamel(jdbc,
                    "SELECT expense_type, goods_code, goods_name, amount, tax_amount, excluding_tax_amount " +
                            "FROM fin_expense_detail WHERE expense_id = " +
                            "(SELECT expense_id FROM fin_expense_bill WHERE expense_no = ?) ORDER BY sort_order", billNo)) {
                String typeName = TmsUtil.str(d.get("expenseType"));
                Map<String, Object> type = head(
                        "SELECT expense_type_code code, gl_expense_account_code subject FROM base_expense_type " +
                                "WHERE expense_type_name = ?", typeName);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("expense_type_name", typeName);
                line.put("expense_type_code", type == null ? "" : TmsUtil.str(type.get("code")));
                // 档案已配总账科目 → 直接给 subject_code（@EXPENSE 优先取它），未配则渲染端按默认科目兜底
                line.put("subject_code", type == null ? "" : TmsUtil.str(type.get("subject")));
                BigDecimal lineTax = bd(d.get("taxAmount")).abs();
                BigDecimal lineExcl = bd(d.get("excludingTaxAmount"));
                if (lineExcl.signum() == 0) lineExcl = bd(d.get("amount")).subtract(lineTax);
                // 可抵扣：费用行取不含税额（税行进项税）；不可抵扣：税额并进费用行
                line.put("amount", (deductible ? lineExcl : lineExcl.add(lineTax)).abs());
                line.put("tax_amount", lineTax);
                line.put("goods_code", TmsUtil.str(d.get("goodsCode")));
                line.put("goods_name", TmsUtil.str(d.get("goodsName")));
                lines.add(line);
            }
            p.put("lines", lines);
            emitter.emit(eventCode, "费用单", billNo, TmsUtil.date(head.get("expenseDate")), incl, p);
        });
    }

    // ==================== 10. 盘点 STOCK_CHECK ====================

    public void onStockCheckAudited(String sheetNo) {
        safe("STOCK_CHECK", sheetNo, () -> emitStockCheck(sheetNo, false));
    }

    public void onStockCheckUnaudited(String sheetNo) {
        safe("STOCK_CHECK", sheetNo, () -> emitStockCheck(sheetNo, true));
    }

    private void emitStockCheck(String sheetNo, boolean reverse) {
        Map<String, Object> head = head(
                "SELECT sheet_no, count_date FROM inv_count_sheet WHERE sheet_no = ?", sheetNo);
        if (head == null) return;
        String billNo = TmsUtil.str(head.get("sheetNo"));
        List<Map<String, Object>> lossLines = new ArrayList<>();
        List<Map<String, Object>> profitLines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> d : TmsUtil.queryCamel(jdbc,
                "SELECT goods_code, goods_name, diff_qty, diff_amount FROM inv_count_detail WHERE sheet_no = ? ORDER BY line_no",
                billNo)) {
            BigDecimal diffQty = bd(d.get("diffQty"));
            BigDecimal diffAmt = bd(d.get("diffAmount")).abs();
            if (diffQty.abs().compareTo(new BigDecimal("0.0001")) < 0
                    && diffAmt.compareTo(new BigDecimal("0.005")) < 0) continue;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("goods_code", TmsUtil.str(d.get("goodsCode")));
            line.put("goods_name", TmsUtil.str(d.get("goodsName")));
            line.put("qty", diffQty.abs());
            line.put("cost_amount", diffAmt);
            total = total.add(diffAmt);
            if (diffQty.signum() < 0) lossLines.add(line); else profitLines.add(line);
        }
        if (lossLines.isEmpty() && profitLines.isEmpty()) return; // 无盈亏不丢事件
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("loss_lines", lossLines);
        p.put("profit_lines", profitLines);
        p.put("cost_amount", total);
        if (reverse) emitter.emitReverse("STOCK_CHECK", "盘点单", billNo,
                TmsUtil.date(head.get("countDate")), total, p, null);
        else emitter.emit("STOCK_CHECK", "盘点单", billNo, TmsUtil.date(head.get("countDate")), total, p);
    }

    // ==================== 11. 其他出库 OTHER_OUT ====================

    public void onOtherOutboundAudited(String outboundNo) {
        safe("OTHER_OUT", outboundNo, () -> emitOtherOutbound(outboundNo, false));
    }

    public void onOtherOutboundUnaudited(String outboundNo) {
        safe("OTHER_OUT", outboundNo, () -> emitOtherOutbound(outboundNo, true));
    }

    private void emitOtherOutbound(String outboundNo, boolean reverse) {
        Map<String, Object> head = head(
                "SELECT outbound_no, bill_date, outbound_type, cost_amount FROM inv_other_outbound " +
                        "WHERE outbound_no = ? OR outbound_id = ?", outboundNo, outboundNo);
        if (head == null) return;
        String billNo = TmsUtil.str(head.get("outboundNo"));
        String typeCode = TmsUtil.str(head.get("outboundType"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("biz_type_code", typeCode);
        p.put("biz_type_name", dictName("other_outbound_type", typeCode));
        p.put("cost_amount", bd(head.get("costAmount")));
        p.put("lines", goodsCostLines("inv_other_outbound_detail", "outbound_id",
                "SELECT outbound_id id FROM inv_other_outbound WHERE outbound_no = ?", billNo));
        BigDecimal amt = bd(head.get("costAmount"));
        if (reverse) emitter.emitReverse("OTHER_OUT", "其他出库单", billNo,
                TmsUtil.date(head.get("billDate")), amt, p, null);
        else emitter.emit("OTHER_OUT", "其他出库单", billNo, TmsUtil.date(head.get("billDate")), amt, p);
    }

    // ==================== 12. 其他入库 OTHER_IN（期初库存不丢事件） ====================

    public void onOtherInboundAudited(String inboundNo) {
        safe("OTHER_IN", inboundNo, () -> emitOtherInbound(inboundNo, false));
    }

    public void onOtherInboundUnaudited(String inboundNo) {
        safe("OTHER_IN", inboundNo, () -> emitOtherInbound(inboundNo, true));
    }

    private void emitOtherInbound(String inboundNo, boolean reverse) {
        Map<String, Object> head = head(
                "SELECT inbound_no, bill_date, inbound_type, cost_amount FROM inv_other_inbound " +
                        "WHERE inbound_no = ? OR inbound_id = ?", inboundNo, inboundNo);
        if (head == null) return;
        String typeCode = TmsUtil.str(head.get("inboundType"));
        if ("0".equals(typeCode)) return; // 期初库存：不进总账
        String billNo = TmsUtil.str(head.get("inboundNo"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("biz_type_code", typeCode);
        p.put("biz_type_name", dictName("other_inbound_type", typeCode));
        p.put("cost_amount", bd(head.get("costAmount")));
        p.put("lines", goodsCostLines("inv_other_inbound_detail", "inbound_id",
                "SELECT inbound_id id FROM inv_other_inbound WHERE inbound_no = ?", billNo));
        BigDecimal amt = bd(head.get("costAmount"));
        if (reverse) emitter.emitReverse("OTHER_IN", "其他入库单", billNo,
                TmsUtil.date(head.get("billDate")), amt, p, null);
        else emitter.emit("OTHER_IN", "其他入库单", billNo, TmsUtil.date(head.get("billDate")), amt, p);
    }

    // ==================== 13. 报损 DAMAGE ====================

    public void onDamageAudited(String damageNo) {
        safe("DAMAGE", damageNo, () -> emitDamage(damageNo, false));
    }

    public void onDamageUnaudited(String damageNo) {
        safe("DAMAGE", damageNo, () -> emitDamage(damageNo, true));
    }

    private void emitDamage(String damageNo, boolean reverse) {
        Map<String, Object> head = head(
                "SELECT damage_no, bill_date, cost_amount FROM inv_damage WHERE damage_no = ? OR damage_id = ?",
                damageNo, damageNo);
        if (head == null) return;
        String billNo = TmsUtil.str(head.get("damageNo"));
        BigDecimal cost = bd(head.get("costAmount"));
        BigDecimal threshold;
        try {
            threshold = new BigDecimal(TmsUtil.str(sysParam.get(PARAM_DAMAGE_LARGE,
                    DEFAULT_DAMAGE_LARGE.toPlainString())));
        } catch (Exception e) {
            threshold = DEFAULT_DAMAGE_LARGE;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("cost_amount", cost);
        p.put("large_loss", cost.compareTo(threshold) >= 0);
        p.put("lines", goodsCostLines("inv_damage_detail", "damage_id",
                "SELECT damage_id id FROM inv_damage WHERE damage_no = ?", billNo));
        if (reverse) emitter.emitReverse("DAMAGE", "报损单", billNo,
                TmsUtil.date(head.get("billDate")), cost, p, null);
        else emitter.emit("DAMAGE", "报损单", billNo, TmsUtil.date(head.get("billDate")), cost, p);
    }

    // ==================== 公共组装工具 ====================

    /** 商品+成本明细行：出库/入库/报损类单据统一结构 {goods_code,goods_name,qty,cost_amount}。 */
    private List<Map<String, Object>> goodsCostLines(String detailTable, String fk, String idSql, String billNo) {
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Map<String, Object>> ids = TmsUtil.queryCamel(jdbc, idSql, billNo);
        if (ids.isEmpty()) return lines;
        String id = TmsUtil.str(ids.get(0).get("id"));
        for (Map<String, Object> d : TmsUtil.queryCamel(jdbc,
                "SELECT goods_code, goods_name, qty, cost_amount FROM " + detailTable
                        + " WHERE " + fk + " = ? ORDER BY detail_id", id)) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("goods_code", TmsUtil.str(d.get("goodsCode")));
            line.put("goods_name", TmsUtil.str(d.get("goodsName")));
            line.put("qty", bd(d.get("qty")));
            line.put("cost_amount", bd(d.get("costAmount")));
            lines.add(line);
        }
        return lines;
    }

    /** 资金账户：档案存编码或名称均兼容；注入 fund_account_code/fund_account_name/fund_subject_code(@FUND)。 */
    private void applyFund(Map<String, Object> p, String fundAccount) {
        if (fundAccount == null || fundAccount.isEmpty()) {
            p.put("fund_account_code", "");
            p.put("fund_account_name", "");
            p.put("fund_subject_code", "");
            return;
        }
        Map<String, Object> fa = head(
                "SELECT fund_account_code code, fund_account_name name, gl_account_code subject " +
                        "FROM base_fund_account WHERE fund_account_code = ? OR fund_account_name = ? LIMIT 1",
                fundAccount, fundAccount);
        if (fa == null) {
            p.put("fund_account_code", fundAccount);
            p.put("fund_account_name", fundAccount);
            p.put("fund_subject_code", "");
            return;
        }
        p.put("fund_account_code", TmsUtil.str(fa.get("code")));
        p.put("fund_account_name", TmsUtil.str(fa.get("name")));
        p.put("fund_subject_code", TmsUtil.str(fa.get("subject")));
    }

    /** 往来单位：CUSTOMER→客户辅助、SUPPLIER→供应商辅助，其余（COUNTERPARTY 等）不挂辅助。 */
    private void applyCounterparty(Map<String, Object> p, String cpType, String cpCode, String cpName) {
        p.put("customer_code", "");
        p.put("customer_name", "");
        p.put("supplier_code", "");
        p.put("supplier_name", "");
        if ("CUSTOMER".equals(cpType)) {
            p.put("customer_code", cpCode);
            p.put("customer_name", cpName);
        } else if ("SUPPLIER".equals(cpType)) {
            p.put("supplier_code", cpCode);
            p.put("supplier_name", cpName);
        }
    }

    private boolean isBackoffice(String businessSource) {
        return businessSource.isEmpty() || "BACKOFFICE".equals(businessSource);
    }

    private String dictName(String dictType, String dictCode) {
        if (dictCode == null || dictCode.isEmpty()) return "";
        Map<String, Object> r = head(
                "SELECT dict_name name FROM sys_dictionary WHERE dict_type = ? AND dict_code = ?",
                dictType, dictCode);
        return r == null ? dictCode : TmsUtil.str(r.get("name"));
    }

    private String lookupCode(String sql, String name) {
        if (name == null || name.isEmpty()) return "";
        Map<String, Object> r = head(sql, name);
        return r == null ? "" : TmsUtil.str(r.get("code"));
    }

    /** 价内税倒算税额：含税额 × 率 ÷ (1+率)，与 SalesReturnController.taxInclusiveTax 同口径。 */
    private static BigDecimal inclusiveTax(BigDecimal taxIncluded, BigDecimal rate) {
        if (taxIncluded == null || taxIncluded.signum() == 0 || rate == null || rate.signum() == 0)
            return BigDecimal.ZERO;
        return taxIncluded.multiply(rate).divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
    }

    /** '13%' / '13' / '0.13' → 0.13，与业务模块 parseTaxRate 同口径。 */
    private static BigDecimal parseRate(String taxRate) {
        if (taxRate == null || taxRate.isBlank()) return new BigDecimal("0.13");
        String s = taxRate.trim();
        boolean pct = s.endsWith("%");
        if (pct) s = s.substring(0, s.length() - 1).trim();
        BigDecimal v;
        try { v = new BigDecimal(s); } catch (Exception e) { return new BigDecimal("0.13"); }
        if (pct || v.compareTo(BigDecimal.ONE) > 0) v = v.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        return v;
    }

    private Map<String, Object> head(String sql, Object... args) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static BigDecimal bd(Object o) { return TmsUtil.toBd(o); }

    /** 钩子绝不能影响业务：任何异常只记日志。 */
    private void safe(String eventCode, String billNo, RunnableEx r) {
        try {
            r.run();
        } catch (Throwable t) {
            log.error("总账钩子失败 event={} bill={} : {}", eventCode, billNo, t.toString(), t);
        }
    }

    @FunctionalInterface
    private interface RunnableEx { void run() throws Exception; }
}
