package com.erp.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;

@TableName("fin_ap")
public class FinAp {

    @TableId(type = IdType.INPUT)
    private String apId;
    private String apNo;
    private String sourceBill;
    private String supplier;
    private BigDecimal apAmount;
    private BigDecimal paidAmount;
    private BigDecimal unpaidAmount;
    private LocalDate dueDate;
    private String status;

    public String getApId() { return apId; }
    public void setApId(String apId) { this.apId = apId; }
    public String getApNo() { return apNo; }
    public void setApNo(String apNo) { this.apNo = apNo; }
    public String getSourceBill() { return sourceBill; }
    public void setSourceBill(String sourceBill) { this.sourceBill = sourceBill; }
    public String getSupplier() { return supplier; }
    public void setSupplier(String supplier) { this.supplier = supplier; }
    public BigDecimal getApAmount() { return apAmount; }
    public void setApAmount(BigDecimal apAmount) { this.apAmount = apAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    public BigDecimal getUnpaidAmount() { return unpaidAmount; }
    public void setUnpaidAmount(BigDecimal unpaidAmount) { this.unpaidAmount = unpaidAmount; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
