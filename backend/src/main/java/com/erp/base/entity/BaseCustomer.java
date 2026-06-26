package com.erp.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

@TableName("base_customer")
public class BaseCustomer {

    @TableId(type = IdType.INPUT)
    private String customerId;
    private String customerCode;
    private String customerName;
    private String channelType;
    private String contactName;
    private String mobile;
    private String territory;
    private String routeLine;
    private String salesman;
    private String customerLevel;
    private String accountPeriodType;
    private String cutoffDay;
    private String paymentDay;
    private BigDecimal creditLimit;
    private BigDecimal arBalance;
    private BigDecimal overdueAmount;
    private String invoiceTitle;
    private String taxNo;
    private String status;

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getCustomerCode() { return customerCode; }
    public void setCustomerCode(String customerCode) { this.customerCode = customerCode; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }

    public String getTerritory() { return territory; }
    public void setTerritory(String territory) { this.territory = territory; }

    public String getRouteLine() { return routeLine; }
    public void setRouteLine(String routeLine) { this.routeLine = routeLine; }

    public String getSalesman() { return salesman; }
    public void setSalesman(String salesman) { this.salesman = salesman; }

    public String getCustomerLevel() { return customerLevel; }
    public void setCustomerLevel(String customerLevel) { this.customerLevel = customerLevel; }

    public String getAccountPeriodType() { return accountPeriodType; }
    public void setAccountPeriodType(String accountPeriodType) { this.accountPeriodType = accountPeriodType; }

    public String getCutoffDay() { return cutoffDay; }
    public void setCutoffDay(String cutoffDay) { this.cutoffDay = cutoffDay; }

    public String getPaymentDay() { return paymentDay; }
    public void setPaymentDay(String paymentDay) { this.paymentDay = paymentDay; }

    public BigDecimal getCreditLimit() { return creditLimit; }
    public void setCreditLimit(BigDecimal creditLimit) { this.creditLimit = creditLimit; }

    public BigDecimal getArBalance() { return arBalance; }
    public void setArBalance(BigDecimal arBalance) { this.arBalance = arBalance; }

    public BigDecimal getOverdueAmount() { return overdueAmount; }
    public void setOverdueAmount(BigDecimal overdueAmount) { this.overdueAmount = overdueAmount; }

    public String getInvoiceTitle() { return invoiceTitle; }
    public void setInvoiceTitle(String invoiceTitle) { this.invoiceTitle = invoiceTitle; }

    public String getTaxNo() { return taxNo; }
    public void setTaxNo(String taxNo) { this.taxNo = taxNo; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
