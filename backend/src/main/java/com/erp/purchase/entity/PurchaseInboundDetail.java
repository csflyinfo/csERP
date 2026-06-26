package com.erp.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;

@TableName("pur_inbound_detail")
public class PurchaseInboundDetail {

    @TableId(type = IdType.INPUT)
    private String detailId;
    private String inboundId;
    private String goodsCode;
    private String goodsName;
    private String warehouse;
    private String unitName;
    private BigDecimal expectedQty;
    private BigDecimal receivedQty;
    private String batchNo;
    private LocalDate productionDate;
    private LocalDate expiryDate;
    private BigDecimal price;
    private BigDecimal amount;
    private BigDecimal beforeCost;
    private BigDecimal afterCost;
    private BigDecimal allocatedExpense;

    public String getDetailId() { return detailId; }
    public void setDetailId(String detailId) { this.detailId = detailId; }
    public String getInboundId() { return inboundId; }
    public void setInboundId(String inboundId) { this.inboundId = inboundId; }
    public String getGoodsCode() { return goodsCode; }
    public void setGoodsCode(String goodsCode) { this.goodsCode = goodsCode; }
    public String getGoodsName() { return goodsName; }
    public void setGoodsName(String goodsName) { this.goodsName = goodsName; }
    public String getWarehouse() { return warehouse; }
    public void setWarehouse(String warehouse) { this.warehouse = warehouse; }
    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }
    public BigDecimal getExpectedQty() { return expectedQty; }
    public void setExpectedQty(BigDecimal expectedQty) { this.expectedQty = expectedQty; }
    public BigDecimal getReceivedQty() { return receivedQty; }
    public void setReceivedQty(BigDecimal receivedQty) { this.receivedQty = receivedQty; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public LocalDate getProductionDate() { return productionDate; }
    public void setProductionDate(LocalDate productionDate) { this.productionDate = productionDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getBeforeCost() { return beforeCost; }
    public void setBeforeCost(BigDecimal beforeCost) { this.beforeCost = beforeCost; }
    public BigDecimal getAfterCost() { return afterCost; }
    public void setAfterCost(BigDecimal afterCost) { this.afterCost = afterCost; }
    public BigDecimal getAllocatedExpense() { return allocatedExpense; }
    public void setAllocatedExpense(BigDecimal allocatedExpense) { this.allocatedExpense = allocatedExpense; }
}
