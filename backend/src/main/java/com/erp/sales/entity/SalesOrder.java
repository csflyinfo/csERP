package com.erp.sales.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;

@TableName("sales_order")
public class SalesOrder {

    @TableId(type = IdType.INPUT)
    private String orderId;
    private String orderNo;
    private String customer;
    private String salesman;
    private String warehouse;
    private LocalDate billDate;
    private BigDecimal amount;
    private BigDecimal paidAmount;
    private BigDecimal unpaidAmount;
    private String creditCheck;
    private String stockCheck;
    private String outboundStatus;
    private String signStatus;
    private String status;
    private String lineType;
    private BigDecimal costAmount;
    private String creatorName;
    private String auditInfo;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getCustomer() { return customer; }
    public void setCustomer(String customer) { this.customer = customer; }
    public String getSalesman() { return salesman; }
    public void setSalesman(String salesman) { this.salesman = salesman; }
    public String getWarehouse() { return warehouse; }
    public void setWarehouse(String warehouse) { this.warehouse = warehouse; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    public BigDecimal getUnpaidAmount() { return unpaidAmount; }
    public void setUnpaidAmount(BigDecimal unpaidAmount) { this.unpaidAmount = unpaidAmount; }
    public String getCreditCheck() { return creditCheck; }
    public void setCreditCheck(String creditCheck) { this.creditCheck = creditCheck; }
    public String getStockCheck() { return stockCheck; }
    public void setStockCheck(String stockCheck) { this.stockCheck = stockCheck; }
    public String getOutboundStatus() { return outboundStatus; }
    public void setOutboundStatus(String outboundStatus) { this.outboundStatus = outboundStatus; }
    public String getSignStatus() { return signStatus; }
    public void setSignStatus(String signStatus) { this.signStatus = signStatus; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLineType() { return lineType; }
    public void setLineType(String lineType) { this.lineType = lineType; }
    public BigDecimal getCostAmount() { return costAmount; }
    public void setCostAmount(BigDecimal costAmount) { this.costAmount = costAmount; }
    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String creatorName) { this.creatorName = creatorName; }
    public String getAuditInfo() { return auditInfo; }
    public void setAuditInfo(String auditInfo) { this.auditInfo = auditInfo; }
}
