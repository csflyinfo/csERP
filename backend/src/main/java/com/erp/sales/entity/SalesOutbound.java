package com.erp.sales.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("sales_outbound")
public class SalesOutbound {

    @TableId(type = IdType.INPUT)
    private String outboundId;
    private String outboundNo;
    private String sourceOrder;
    private String customer;
    private String warehouse;
    private LocalDate billDate;
    private BigDecimal qty;
    private BigDecimal amount;
    private BigDecimal costAmount;
    private String status;
    private Boolean stockUpdated;
    private Boolean receiptGenerated;
    private LocalDateTime createdAt;

    public String getOutboundId() { return outboundId; }
    public void setOutboundId(String outboundId) { this.outboundId = outboundId; }
    public String getOutboundNo() { return outboundNo; }
    public void setOutboundNo(String outboundNo) { this.outboundNo = outboundNo; }
    public String getSourceOrder() { return sourceOrder; }
    public void setSourceOrder(String sourceOrder) { this.sourceOrder = sourceOrder; }
    public String getCustomer() { return customer; }
    public void setCustomer(String customer) { this.customer = customer; }
    public String getWarehouse() { return warehouse; }
    public void setWarehouse(String warehouse) { this.warehouse = warehouse; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getCostAmount() { return costAmount; }
    public void setCostAmount(BigDecimal costAmount) { this.costAmount = costAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getStockUpdated() { return stockUpdated; }
    public void setStockUpdated(Boolean stockUpdated) { this.stockUpdated = stockUpdated; }
    public Boolean getReceiptGenerated() { return receiptGenerated; }
    public void setReceiptGenerated(Boolean receiptGenerated) { this.receiptGenerated = receiptGenerated; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
