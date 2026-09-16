package com.erp.finance.account;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户账户模块常量（PRD-35）。
 *
 * <p>两个账户：{@link #ACCOUNT_AR} 应收账户、{@link #ACCOUNT_ADVANCE} 预收账户；
 * 流水业务类型编码与「业务单据」列展示文案集中在此维护（新增先查重，禁止同码多义）。
 */
public final class CustomerAccountConst {

    private CustomerAccountConst() {
    }

    /** 应收账户。 */
    public static final String ACCOUNT_AR = "AR";
    /** 预收账户。 */
    public static final String ACCOUNT_ADVANCE = "ADVANCE";

    // ==================== 应收账户流水 biz_type ====================
    /** 签收形成应收。 */
    public static final String AR_SIGN = "AR_SIGN";
    /** 销售退货红冲（负向形成行）。 */
    public static final String AR_RETURN = "AR_RETURN";
    /** 费用单挂应收。 */
    public static final String AR_EXPENSE = "AR_EXPENSE";
    /** 期初应收建账。 */
    public static final String AR_OPENING = "AR_OPENING";
    /** 收款单（现金/银行）结算应收。 */
    public static final String AR_SETTLE_CASH = "AR_SETTLE_CASH";
    /** 预收核销（预收冲应收）。 */
    public static final String AR_SETTLE_ADVANCE = "AR_SETTLE_ADVANCE";
    /** 抹零费用单核销。 */
    public static final String AR_WRITEOFF = "AR_WRITEOFF";
    /** 结算反审核冲回。 */
    public static final String AR_REVERSE = "AR_REVERSE";

    // ==================== 预收账户流水 biz_type ====================
    /** 预收收款。 */
    public static final String ADV_RECEIPT = "ADV_RECEIPT";
    /** 预收退款。 */
    public static final String ADV_REFUND = "ADV_REFUND";
    /** 预收核销（扣减预收）。 */
    public static final String ADV_WRITE_OFF = "ADV_WRITE_OFF";
    /** 期初预收。 */
    public static final String ADV_OPENING = "ADV_OPENING";
    /** 预收类反审核冲回。 */
    public static final String ADV_REVERSE = "ADV_REVERSE";

    /** 收款单收款类型：应收结算（默认，历史数据）。 */
    public static final String RECEIPT_SETTLE = "SETTLE";
    /** 收款单收款类型：预收收款。 */
    public static final String RECEIPT_ADVANCE = "ADVANCE";
    /** 收款单收款类型：预收退款。 */
    public static final String RECEIPT_ADVANCE_REFUND = "ADVANCE_REFUND";

    /**
     * 收款单业务来源：TMS 门店结算溢收自动转预收（PRD-35 M4）。
     * 与普通 DRIVER_SETTLE 单的区别：该单只补预收流水/往来归属/GL 事件，不写资金流水；
     * GL 钩子对自动来源默认拦截，仅此来源显式放行 ADVANCE_RECEIPT 凭证。
     */
    public static final String SOURCE_DRIVER_OVERPAY_ADV = "DRIVER_OVERPAY_ADV";

    public static final String SETTLE_UNSETTLED = "未结算";
    public static final String SETTLE_PART = "部分结算";
    public static final String SETTLE_DONE = "已结算";

    public static final String REVERSE_NORMAL = "NORMAL";
    public static final String REVERSE_REVERSED = "REVERSED";

    /** biz_type → 列表「业务单据」列文案（沿用旧系统用户熟悉叫法，编码保持现代化）。 */
    private static final Map<String, String> BIZ_LABELS = new LinkedHashMap<>();

    static {
        BIZ_LABELS.put(AR_SIGN, "订单出库");
        BIZ_LABELS.put(AR_RETURN, "销售退货");
        BIZ_LABELS.put(AR_EXPENSE, "费用单");
        BIZ_LABELS.put(AR_OPENING, "期初建账");
        BIZ_LABELS.put(AR_SETTLE_CASH, "收款结算");
        BIZ_LABELS.put(AR_SETTLE_ADVANCE, "预收冲应收");
        BIZ_LABELS.put(AR_WRITEOFF, "抹零核销");
        BIZ_LABELS.put(AR_REVERSE, "结算冲回");
        BIZ_LABELS.put(ADV_RECEIPT, "预收收款");
        BIZ_LABELS.put(ADV_REFUND, "预收退款");
        BIZ_LABELS.put(ADV_WRITE_OFF, "预收核销");
        BIZ_LABELS.put(ADV_OPENING, "期初预收");
        BIZ_LABELS.put(ADV_REVERSE, "预收冲回");
    }

    /** 应收形成类 biz_type（同刻排序在结算动作之前，且只有这类行有结算标志）。 */
    public static boolean isArForming(String bizType) {
        return AR_SIGN.equals(bizType) || AR_RETURN.equals(bizType)
                || AR_EXPENSE.equals(bizType) || AR_OPENING.equals(bizType);
    }

    public static String bizLabel(String bizType) {
        return BIZ_LABELS.getOrDefault(bizType, bizType);
    }
}
