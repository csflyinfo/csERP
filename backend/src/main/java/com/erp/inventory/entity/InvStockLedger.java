package com.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("inv_stock_ledger")
public class InvStockLedger {

    @TableId(type = IdType.INPUT)
    private String ledgerId;
    private String ledgerNo;
    private LocalDateTime occurredAt;
    private String sourceBill;
    private String goodsCode;
    private String goodsName;
    private String warehouse;
    private String batchNo;
    private String direction;
    private BigDecimal qty;
    private BigDecimal costPrice;
    private BigDecimal amount;
    private BigDecimal balanceQty;
    private String operatorName;

    public String getLedgerId() { return ledgerId; }
    public void setLedgerId(String ledgerId) { this.ledgerId = ledgerId; }
    public String getLedgerNo() { return ledgerNo; }
    public void setLedgerNo(String ledgerNo) { this.ledgerNo = ledgerNo; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public String getSourceBill() { return sourceBill; }
    public void setSourceBill(String sourceBill) { this.sourceBill = sourceBill; }
    public String getGoodsCode() { return goodsCode; }
    public void setGoodsCode(String goodsCode) { this.goodsCode = goodsCode; }
    public String getGoodsName() { return goodsName; }
    public void setGoodsName(String goodsName) { this.goodsName = goodsName; }
    public String getWarehouse() { return warehouse; }
    public void setWarehouse(String warehouse) { this.warehouse = warehouse; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getCostPrice() { return costPrice; }
    public void setCostPrice(BigDecimal costPrice) { this.costPrice = costPrice; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getBalanceQty() { return balanceQty; }
    public void setBalanceQty(BigDecimal balanceQty) { this.balanceQty = balanceQty; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
}
