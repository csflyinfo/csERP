package com.erp.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

@TableName("pur_order_detail")
public class PurchaseOrderDetail {

    @TableId(type = IdType.INPUT)
    private String detailId;
    private String orderId;
    private String lineType;
    private String goodsCode;
    private String goodsName;
    private String unitName;
    private BigDecimal qty;
    private BigDecimal price;
    private String taxRate;
    private BigDecimal amount;
    private BigDecimal costPrice;
    private BigDecimal costAmount;

    public String getDetailId() { return detailId; }
    public void setDetailId(String detailId) { this.detailId = detailId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getLineType() { return lineType; }
    public void setLineType(String lineType) { this.lineType = lineType; }
    public String getGoodsCode() { return goodsCode; }
    public void setGoodsCode(String goodsCode) { this.goodsCode = goodsCode; }
    public String getGoodsName() { return goodsName; }
    public void setGoodsName(String goodsName) { this.goodsName = goodsName; }
    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getTaxRate() { return taxRate; }
    public void setTaxRate(String taxRate) { this.taxRate = taxRate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getCostPrice() { return costPrice; }
    public void setCostPrice(BigDecimal costPrice) { this.costPrice = costPrice; }
    public BigDecimal getCostAmount() { return costAmount; }
    public void setCostAmount(BigDecimal costAmount) { this.costAmount = costAmount; }
}
