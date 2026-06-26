package com.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("inv_stock_balance")
public class InvStockBalance {

    @TableId(type = IdType.INPUT)
    private String balanceId;
    private String goodsCode;
    private String goodsName;
    private String warehouse;
    private String batchNo;
    private BigDecimal physicalQty;
    private BigDecimal lockedQty;
    private BigDecimal frozenQty;
    private BigDecimal availableQty;
    private BigDecimal purchaseOnWay;
    private BigDecimal costPrice;
    private BigDecimal stockAmount;
    private LocalDateTime lastInoutTime;

    public String getBalanceId() { return balanceId; }
    public void setBalanceId(String balanceId) { this.balanceId = balanceId; }
    public String getGoodsCode() { return goodsCode; }
    public void setGoodsCode(String goodsCode) { this.goodsCode = goodsCode; }
    public String getGoodsName() { return goodsName; }
    public void setGoodsName(String goodsName) { this.goodsName = goodsName; }
    public String getWarehouse() { return warehouse; }
    public void setWarehouse(String warehouse) { this.warehouse = warehouse; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public BigDecimal getPhysicalQty() { return physicalQty; }
    public void setPhysicalQty(BigDecimal physicalQty) { this.physicalQty = physicalQty; }
    public BigDecimal getLockedQty() { return lockedQty; }
    public void setLockedQty(BigDecimal lockedQty) { this.lockedQty = lockedQty; }
    public BigDecimal getFrozenQty() { return frozenQty; }
    public void setFrozenQty(BigDecimal frozenQty) { this.frozenQty = frozenQty; }
    public BigDecimal getAvailableQty() { return availableQty; }
    public void setAvailableQty(BigDecimal availableQty) { this.availableQty = availableQty; }
    public BigDecimal getPurchaseOnWay() { return purchaseOnWay; }
    public void setPurchaseOnWay(BigDecimal purchaseOnWay) { this.purchaseOnWay = purchaseOnWay; }
    public BigDecimal getCostPrice() { return costPrice; }
    public void setCostPrice(BigDecimal costPrice) { this.costPrice = costPrice; }
    public BigDecimal getStockAmount() { return stockAmount; }
    public void setStockAmount(BigDecimal stockAmount) { this.stockAmount = stockAmount; }
    public LocalDateTime getLastInoutTime() { return lastInoutTime; }
    public void setLastInoutTime(LocalDateTime lastInoutTime) { this.lastInoutTime = lastInoutTime; }
}
