package com.erp.finance.account;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供应商账户模块常量（PRD-36）。
 *
 * <p>三个账户：{@link #ACCOUNT_AP} 应付账户、{@link #ACCOUNT_PREPAY} 预付账户、
 * {@link #ACCOUNT_EXPENSE} 费用账户（厂家费用）；
 * 流水业务类型编码与「业务单据」列展示文案集中在此维护（新增先查重，禁止同码多义）。
 */
public final class SupplierAccountConst {

    private SupplierAccountConst() {
    }

    /** 应付账户。 */
    public static final String ACCOUNT_AP = "AP";
    /** 预付账户。 */
    public static final String ACCOUNT_PREPAY = "PREPAY";
    /** 厂家费用账户。 */
    public static final String ACCOUNT_EXPENSE = "EXPENSE";

    // ==================== 应付账户流水 biz_type ====================
    /** 采购收货形成应付。 */
    public static final String AP_RECEIPT = "AP_RECEIPT";
    /** 采购退货红冲（负向形成行）。 */
    public static final String AP_RETURN = "AP_RETURN";
    /** 期初应付建账。 */
    public static final String AP_OPENING = "AP_OPENING";
    /** 付款单（现金/银行）结算应付。 */
    public static final String AP_SETTLE_CASH = "AP_SETTLE_CASH";
    /** 预付核销（预付冲应付）。 */
    public static final String AP_SETTLE_PREPAY = "AP_SETTLE_PREPAY";
    /** 厂家费用账扣（费用冲应付）。 */
    public static final String AP_SETTLE_EXPENSE = "AP_SETTLE_EXPENSE";
    /** 应付侧反审核冲回。 */
    public static final String AP_REVERSE = "AP_REVERSE";

    // ==================== 预付账户流水 biz_type ====================
    /** 预付付款。 */
    public static final String PREPAY_PAYMENT = "PREPAY_PAYMENT";
    /** 预付退款。 */
    public static final String PREPAY_REFUND = "PREPAY_REFUND";
    /** 预付核销（扣减预付）。 */
    public static final String PREPAY_WRITE_OFF = "PREPAY_WRITE_OFF";
    /** 期初预付。 */
    public static final String PREPAY_OPENING = "PREPAY_OPENING";
    /** 厂家费用「其他核销-转预付」形成预付。 */
    public static final String PREPAY_EXPENSE_TRANSFER = "PREPAY_EXPENSE_TRANSFER";
    /** 预付类反审核冲回。 */
    public static final String PREPAY_REVERSE = "PREPAY_REVERSE";

    // ==================== 费用账户流水 biz_type ====================
    /** 厂家费用单审核形成费用。 */
    public static final String FACTORY_EXP_FORM = "FACTORY_EXP_FORM";
    /** 厂家费用红字单冲回。 */
    public static final String FACTORY_EXP_RED = "FACTORY_EXP_RED";
    /** 现金兑现。 */
    public static final String FACTORY_EXP_SETTLE_CASH = "FACTORY_EXP_SETTLE_CASH";
    /** 冲应付兑现。 */
    public static final String FACTORY_EXP_SETTLE_OFFSET = "FACTORY_EXP_SETTLE_OFFSET";
    /** 其他核销兑现（货补/转预付/减免/坏账）。 */
    public static final String FACTORY_EXP_SETTLE_OTHER = "FACTORY_EXP_SETTLE_OTHER";
    /** 费用类反审核冲回。 */
    public static final String FACTORY_EXP_REVERSE = "FACTORY_EXP_REVERSE";

    /** 付款单付款类型：应付结算（默认，历史数据）。 */
    public static final String PAYMENT_SETTLE = "SETTLE";
    /** 付款单付款类型：预付付款。 */
    public static final String PAYMENT_PREPAY = "PREPAY";
    /** 付款单付款类型：预付退款。 */
    public static final String PAYMENT_PREPAY_REFUND = "PREPAY_REFUND";

    /** 核销记录 business_type：预付核销（M2）。 */
    public static final String BIZ_PREPAY_WRITE_OFF = "PREPAY_WRITE_OFF";
    /** 核销记录 business_type：厂家费用账扣（M3）。 */
    public static final String BIZ_FACTORY_EXPENSE_OFFSET = "FACTORY_EXPENSE_OFFSET";
    /** 往来台账 business_type：厂家费用其他核销（M3）。 */
    public static final String BIZ_FACTORY_EXPENSE_OTHER = "FACTORY_EXPENSE_OTHER";

    public static final String SETTLE_UNSETTLED = "未结算";
    public static final String SETTLE_PART = "部分结算";
    public static final String SETTLE_DONE = "已结算";

    /** 费用形成行兑现状态：待兑现。 */
    public static final String CLAIM_UNCLAIMED = "待兑现";
    public static final String CLAIM_PART = "部分兑现";
    public static final String CLAIM_DONE = "已兑现";

    public static final String REVERSE_NORMAL = "NORMAL";
    public static final String REVERSE_REVERSED = "REVERSED";

    /** biz_type → 列表「业务单据」列文案（沿用旧系统用户熟悉叫法，编码保持现代化）。 */
    private static final Map<String, String> BIZ_LABELS = new LinkedHashMap<>();

    static {
        BIZ_LABELS.put(AP_RECEIPT, "采购收货");
        BIZ_LABELS.put(AP_RETURN, "采购退货");
        BIZ_LABELS.put(AP_OPENING, "期初建账");
        BIZ_LABELS.put(AP_SETTLE_CASH, "付款结算");
        BIZ_LABELS.put(AP_SETTLE_PREPAY, "预付冲应付");
        BIZ_LABELS.put(AP_SETTLE_EXPENSE, "厂家费用账扣");
        BIZ_LABELS.put(AP_REVERSE, "结算冲回");
        BIZ_LABELS.put(PREPAY_PAYMENT, "预付付款");
        BIZ_LABELS.put(PREPAY_REFUND, "预付退款");
        BIZ_LABELS.put(PREPAY_WRITE_OFF, "预付核销");
        BIZ_LABELS.put(PREPAY_OPENING, "期初预付");
        BIZ_LABELS.put(PREPAY_EXPENSE_TRANSFER, "费用转预付");
        BIZ_LABELS.put(PREPAY_REVERSE, "预付冲回");
        BIZ_LABELS.put(FACTORY_EXP_FORM, "厂家费用");
        BIZ_LABELS.put(FACTORY_EXP_RED, "厂家费用红字");
        BIZ_LABELS.put(FACTORY_EXP_SETTLE_CASH, "费用现金兑现");
        BIZ_LABELS.put(FACTORY_EXP_SETTLE_OFFSET, "费用冲应付");
        BIZ_LABELS.put(FACTORY_EXP_SETTLE_OTHER, "费用其他兑现");
        BIZ_LABELS.put(FACTORY_EXP_REVERSE, "费用冲回");
    }

    /** 应付形成类 biz_type（同刻排序在结算动作之前，且只有这类行有结算标志）。 */
    public static boolean isApForming(String bizType) {
        return AP_RECEIPT.equals(bizType) || AP_RETURN.equals(bizType) || AP_OPENING.equals(bizType);
    }

    public static String bizLabel(String bizType) {
        return BIZ_LABELS.getOrDefault(bizType, bizType);
    }
}
