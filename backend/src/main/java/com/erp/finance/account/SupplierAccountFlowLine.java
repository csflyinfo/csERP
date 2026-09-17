package com.erp.finance.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 供应商账户流水写入参数（PRD-36）。
 *
 * <p>一笔流水只填 increaseAmount 或 decreaseAmount 之一（退货红冲/红字费用用负的 increase + isRed）；
 * bizKey 为幂等键，重复写入靠 uk_saf_biz_key 兜底，调用方自己保证业务幂等。
 */
public class SupplierAccountFlowLine {

    private String supplierCode;
    private String supplierName;
    /** {@link SupplierAccountConst#ACCOUNT_AP} / {@link SupplierAccountConst#ACCOUNT_PREPAY} / EXPENSE。 */
    private String accountType;
    private String bizType;
    private BigDecimal increaseAmount = BigDecimal.ZERO;
    private BigDecimal decreaseAmount = BigDecimal.ZERO;
    private String settleStatus;
    private BigDecimal settledAmount;
    private String apNo;
    private String factoryExpenseNo;
    private String sourceBill;
    private String paymentNo;
    private String writeoffNo;
    private String reconcileId;
    private String receiptNo;
    private LocalDate postDate;
    private LocalDateTime occurredAt;
    private String summary;
    private String operatorName;
    private boolean red;
    private String bizKey;

    public SupplierAccountFlowLine() {
    }

    /** 常用构造：账户 + 类型 + 供应商 + 幂等键。 */
    public SupplierAccountFlowLine(String accountType, String bizType, String supplierCode,
                                   String supplierName, String bizKey) {
        this.accountType = accountType;
        this.bizType = bizType;
        this.supplierCode = supplierCode;
        this.supplierName = supplierName;
        this.bizKey = bizKey;
    }

    public BigDecimal changeAmount() {
        return increaseAmount.subtract(decreaseAmount);
    }

    public String getSupplierCode() { return supplierCode; }
    public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public BigDecimal getIncreaseAmount() { return increaseAmount; }
    public void setIncreaseAmount(BigDecimal increaseAmount) {
        this.increaseAmount = increaseAmount == null ? BigDecimal.ZERO : increaseAmount;
    }
    public BigDecimal getDecreaseAmount() { return decreaseAmount; }
    public void setDecreaseAmount(BigDecimal decreaseAmount) {
        this.decreaseAmount = decreaseAmount == null ? BigDecimal.ZERO : decreaseAmount;
    }
    public String getSettleStatus() { return settleStatus; }
    public void setSettleStatus(String settleStatus) { this.settleStatus = settleStatus; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public void setSettledAmount(BigDecimal settledAmount) { this.settledAmount = settledAmount; }
    public String getApNo() { return apNo; }
    public void setApNo(String apNo) { this.apNo = apNo; }
    public String getFactoryExpenseNo() { return factoryExpenseNo; }
    public void setFactoryExpenseNo(String factoryExpenseNo) { this.factoryExpenseNo = factoryExpenseNo; }
    public String getSourceBill() { return sourceBill; }
    public void setSourceBill(String sourceBill) { this.sourceBill = sourceBill; }
    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }
    public String getWriteoffNo() { return writeoffNo; }
    public void setWriteoffNo(String writeoffNo) { this.writeoffNo = writeoffNo; }
    public String getReconcileId() { return reconcileId; }
    public void setReconcileId(String reconcileId) { this.reconcileId = reconcileId; }
    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
    public LocalDate getPostDate() { return postDate; }
    public void setPostDate(LocalDate postDate) { this.postDate = postDate; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public boolean isRed() { return red; }
    public void setRed(boolean red) { this.red = red; }
    public String getBizKey() { return bizKey; }
    public void setBizKey(String bizKey) { this.bizKey = bizKey; }
}
