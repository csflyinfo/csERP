package com.erp.finance.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 客户账户流水写入参数（PRD-35）。
 *
 * <p>一笔流水只填 increaseAmount 或 decreaseAmount 之一（退货红冲用负的 increaseAmount + isRed）；
 * bizKey 为幂等键，重复写入靠 uk_caf_biz_key 兜底，调用方自己保证业务幂等。
 */
public class CustomerAccountFlowLine {

    private String customerCode;
    private String customerName;
    /** {@link CustomerAccountConst#ACCOUNT_AR} / {@link CustomerAccountConst#ACCOUNT_ADVANCE}。 */
    private String accountType;
    private String bizType;
    private BigDecimal increaseAmount = BigDecimal.ZERO;
    private BigDecimal decreaseAmount = BigDecimal.ZERO;
    private String settleStatus;
    private BigDecimal settledAmount;
    private String arNo;
    private String sourceBill;
    private String orderNo;
    private String deliveryNo;
    private String receiptNo;
    private String writeoffNo;
    private String reconcileId;
    private LocalDate postDate;
    private LocalDateTime occurredAt;
    private String summary;
    private String operatorName;
    private boolean red;
    private String bizKey;

    public CustomerAccountFlowLine() {
    }

    /** 常用构造：账户 + 类型 + 客户 + 幂等键。 */
    public CustomerAccountFlowLine(String accountType, String bizType, String customerCode,
                                   String customerName, String bizKey) {
        this.accountType = accountType;
        this.bizType = bizType;
        this.customerCode = customerCode;
        this.customerName = customerName;
        this.bizKey = bizKey;
    }

    public BigDecimal changeAmount() {
        return increaseAmount.subtract(decreaseAmount);
    }

    public String getCustomerCode() { return customerCode; }
    public void setCustomerCode(String customerCode) { this.customerCode = customerCode; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
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
    public String getArNo() { return arNo; }
    public void setArNo(String arNo) { this.arNo = arNo; }
    public String getSourceBill() { return sourceBill; }
    public void setSourceBill(String sourceBill) { this.sourceBill = sourceBill; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getDeliveryNo() { return deliveryNo; }
    public void setDeliveryNo(String deliveryNo) { this.deliveryNo = deliveryNo; }
    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
    public String getWriteoffNo() { return writeoffNo; }
    public void setWriteoffNo(String writeoffNo) { this.writeoffNo = writeoffNo; }
    public String getReconcileId() { return reconcileId; }
    public void setReconcileId(String reconcileId) { this.reconcileId = reconcileId; }
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
