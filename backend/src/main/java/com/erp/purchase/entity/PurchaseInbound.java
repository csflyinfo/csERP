package com.erp.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("pur_inbound")
public class PurchaseInbound {

    @TableId(type = IdType.INPUT)
    private String inboundId;
    private String inboundNo;
    private String sourceOrder;
    private String supplier;
    private String warehouse;
    private LocalDate billDate;
    private BigDecimal qty;
    private BigDecimal amount;
    private String status;
    private Boolean stockUpdated;
    private Boolean receiptGenerated;
    private LocalDateTime createdAt;

    public String getInboundId() { return inboundId; }
    public void setInboundId(String inboundId) { this.inboundId = inboundId; }
    public String getInboundNo() { return inboundNo; }
    public void setInboundNo(String inboundNo) { this.inboundNo = inboundNo; }
    public String getSourceOrder() { return sourceOrder; }
    public void setSourceOrder(String sourceOrder) { this.sourceOrder = sourceOrder; }
    public String getSupplier() { return supplier; }
    public void setSupplier(String supplier) { this.supplier = supplier; }
    public String getWarehouse() { return warehouse; }
    public void setWarehouse(String warehouse) { this.warehouse = warehouse; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getStockUpdated() { return stockUpdated; }
    public void setStockUpdated(Boolean stockUpdated) { this.stockUpdated = stockUpdated; }
    public Boolean getReceiptGenerated() { return receiptGenerated; }
    public void setReceiptGenerated(Boolean receiptGenerated) { this.receiptGenerated = receiptGenerated; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
