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
    // 账期设置（按 docs/账期管理-产品说明.md）
    private String settlementType;   // PREPAY 预付 / COD 货到付款 / TERM 账期
    private String termType;         // FIXED / WEEKLY / SEMI_MONTH / MONTHLY
    private Integer termDays;        // 账期天数
    private String paymentMode;      // A / B（月结）
    private Integer termMonths;      // 账期月数（月结B）
    private BigDecimal creditLimit;
    private BigDecimal arBalance;
    private BigDecimal overdueAmount;
    private String invoiceTitle;
    private String taxNo;
    private String shippingAddress;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String priceGroupCode;
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

    public String getSettlementType() { return settlementType; }
    public void setSettlementType(String settlementType) { this.settlementType = settlementType; }

    public String getTermType() { return termType; }
    public void setTermType(String termType) { this.termType = termType; }

    public Integer getTermDays() { return termDays; }
    public void setTermDays(Integer termDays) { this.termDays = termDays; }

    public String getPaymentMode() { return paymentMode; }
    public void setPaymentMode(String paymentMode) { this.paymentMode = paymentMode; }

    public Integer getTermMonths() { return termMonths; }
    public void setTermMonths(Integer termMonths) { this.termMonths = termMonths; }

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

    public String getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(String shippingAddress) { this.shippingAddress = shippingAddress; }

    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }

    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }

    public String getPriceGroupCode() { return priceGroupCode; }
    public void setPriceGroupCode(String priceGroupCode) { this.priceGroupCode = priceGroupCode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
