package com.erp.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;

@TableName("fin_ar")
public class FinAr {

    @TableId(type = IdType.INPUT)
    private String arId;
    private String arNo;
    private String sourceBill;
    private String customer;
    private String salesman;
    private BigDecimal arAmount;
    private BigDecimal receivedAmount;
    private BigDecimal unreceivedAmount;
    private LocalDate dueDate;
    private Integer overdueDays;
    private String invoiceStatus;
    private String status;

    public String getArId() { return arId; }
    public void setArId(String arId) { this.arId = arId; }
    public String getArNo() { return arNo; }
    public void setArNo(String arNo) { this.arNo = arNo; }
    public String getSourceBill() { return sourceBill; }
    public void setSourceBill(String sourceBill) { this.sourceBill = sourceBill; }
    public String getCustomer() { return customer; }
    public void setCustomer(String customer) { this.customer = customer; }
    public String getSalesman() { return salesman; }
    public void setSalesman(String salesman) { this.salesman = salesman; }
    public BigDecimal getArAmount() { return arAmount; }
    public void setArAmount(BigDecimal arAmount) { this.arAmount = arAmount; }
    public BigDecimal getReceivedAmount() { return receivedAmount; }
    public void setReceivedAmount(BigDecimal receivedAmount) { this.receivedAmount = receivedAmount; }
    public BigDecimal getUnreceivedAmount() { return unreceivedAmount; }
    public void setUnreceivedAmount(BigDecimal unreceivedAmount) { this.unreceivedAmount = unreceivedAmount; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public Integer getOverdueDays() { return overdueDays; }
    public void setOverdueDays(Integer overdueDays) { this.overdueDays = overdueDays; }
    public String getInvoiceStatus() { return invoiceStatus; }
    public void setInvoiceStatus(String invoiceStatus) { this.invoiceStatus = invoiceStatus; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
