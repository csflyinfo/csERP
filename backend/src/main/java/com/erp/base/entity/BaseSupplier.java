package com.erp.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

@TableName("base_supplier")
public class BaseSupplier {

    @TableId(type = IdType.INPUT)
    private String supplierId;
    private String supplierCode;
    private String supplierName;
    private String shortName;
    private String supplierType;
    private String contactName;
    private String phone;
    private Integer deliveryDays;
    private String settlementMethod;
    private Integer accountPeriodDays;
    // 账期设置（按 docs/账期管理-产品说明.md）
    private String settlementType;   // PREPAY / COD / TERM
    private String termType;         // FIXED / WEEKLY / SEMI_MONTH / MONTHLY
    private Integer termDays;
    private String cutoffDay;
    private String paymentMode;      // A / B
    private Integer termMonths;
    private String paymentDay;
    private String defaultBuyer;
    private String defaultReceiptAccount;
    private String invoiceTitle;
    private String taxNo;
    private BigDecimal apBalance;
    private String status;
    private String address;
    private String remark;
    private String deliveryMethod;
    private String defaultLogisticsCompany;

    public String getSupplierId() { return supplierId; }
    public void setSupplierId(String supplierId) { this.supplierId = supplierId; }

    public String getSupplierCode() { return supplierCode; }
    public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }

    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }

    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }

    public String getSupplierType() { return supplierType; }
    public void setSupplierType(String supplierType) { this.supplierType = supplierType; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public Integer getDeliveryDays() { return deliveryDays; }
    public void setDeliveryDays(Integer deliveryDays) { this.deliveryDays = deliveryDays; }

    public String getSettlementMethod() { return settlementMethod; }
    public void setSettlementMethod(String settlementMethod) { this.settlementMethod = settlementMethod; }

    public Integer getAccountPeriodDays() { return accountPeriodDays; }
    public void setAccountPeriodDays(Integer accountPeriodDays) { this.accountPeriodDays = accountPeriodDays; }

    public String getSettlementType() { return settlementType; }
    public void setSettlementType(String settlementType) { this.settlementType = settlementType; }

    public String getTermType() { return termType; }
    public void setTermType(String termType) { this.termType = termType; }

    public Integer getTermDays() { return termDays; }
    public void setTermDays(Integer termDays) { this.termDays = termDays; }

    public String getCutoffDay() { return cutoffDay; }
    public void setCutoffDay(String cutoffDay) { this.cutoffDay = cutoffDay; }

    public String getPaymentMode() { return paymentMode; }
    public void setPaymentMode(String paymentMode) { this.paymentMode = paymentMode; }

    public Integer getTermMonths() { return termMonths; }
    public void setTermMonths(Integer termMonths) { this.termMonths = termMonths; }

    public String getPaymentDay() { return paymentDay; }
    public void setPaymentDay(String paymentDay) { this.paymentDay = paymentDay; }

    public String getDefaultBuyer() { return defaultBuyer; }
    public void setDefaultBuyer(String defaultBuyer) { this.defaultBuyer = defaultBuyer; }

    public String getDefaultReceiptAccount() { return defaultReceiptAccount; }
    public void setDefaultReceiptAccount(String defaultReceiptAccount) { this.defaultReceiptAccount = defaultReceiptAccount; }

    public String getInvoiceTitle() { return invoiceTitle; }
    public void setInvoiceTitle(String invoiceTitle) { this.invoiceTitle = invoiceTitle; }

    public String getTaxNo() { return taxNo; }
    public void setTaxNo(String taxNo) { this.taxNo = taxNo; }

    public BigDecimal getApBalance() { return apBalance; }
    public void setApBalance(BigDecimal apBalance) { this.apBalance = apBalance; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getDeliveryMethod() { return deliveryMethod; }
    public void setDeliveryMethod(String deliveryMethod) { this.deliveryMethod = deliveryMethod; }

    public String getDefaultLogisticsCompany() { return defaultLogisticsCompany; }
    public void setDefaultLogisticsCompany(String defaultLogisticsCompany) { this.defaultLogisticsCompany = defaultLogisticsCompany; }
}
