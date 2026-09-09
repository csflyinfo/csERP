package com.erp.common.security;

import java.util.Map;

/**
 * 脱敏调用点 profile（PRD-28 卡片6）。
 *
 * <p>业务表普遍用 {@code price}/{@code amount}/{@code taxAmount} 这类通用列名，
 * 销售语义对应 VIEW_SALE_PRICE/VIEW_SALE_AMOUNT，采购语义对应 VIEW_PURCHASE_PRICE/
 * VIEW_PURCHASE_AMOUNT/应付类字段，全局 {@code SensitiveFieldRegistry} 无法一对一绑定，
 * 因此各列表/详情在 {@code fieldMasker.mask(payload, MaskProfiles.XXX)} 显式声明归属。
 *
 * <p>仅列「全局注册表无法确定」的通用 key；costPrice/latestPurchasePrice/creditLimit 等
 * 语义唯一的 key 仍由注册表统一处理，不在此重复。表名见方案 §6.3 字段分组。
 */
public final class MaskProfiles {

    private MaskProfiles() {
    }

    /** 销售单据（订单/出库/发货/飞单/退货/拒收）：单价=售价，金额家族=销售金额，收付=应收。 */
    public static final Map<String, String> SALES_BILL = Map.ofEntries(
            Map.entry("price", "VIEW_SALE_PRICE"),
            Map.entry("taxPrice", "VIEW_SALE_PRICE"),
            Map.entry("amount", "VIEW_SALE_AMOUNT"),
            Map.entry("taxAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("untaxedAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("totalAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("goodsAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("finalAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("orderAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("outboundedAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("billAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("thisAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("signAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("receiptAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("salesAmount", "VIEW_SALE_AMOUNT"),
            Map.entry("paidAmount", "VIEW_AR_BALANCE"),
            Map.entry("unpaidAmount", "VIEW_AR_BALANCE"),
            Map.entry("receivedAmount", "VIEW_AR_BALANCE"),
            Map.entry("unreceivedAmount", "VIEW_AR_BALANCE"),
            // 蛇形列标签：Excel 导出等直接用 queryForList 行（未转驼峰）的链路
            Map.entry("paid_amount", "VIEW_AR_BALANCE"),
            Map.entry("unpaid_amount", "VIEW_AR_BALANCE"),
            Map.entry("received_amount", "VIEW_AR_BALANCE"),
            Map.entry("unreceived_amount", "VIEW_AR_BALANCE"),
            // 出库批次可用量属于库存数量敏感列
            Map.entry("availableStock", "VIEW_STOCK_AMOUNT"),
            Map.entry("beforeCost", "VIEW_COST"),
            Map.entry("afterCost", "VIEW_COST"),
            // 飞单（即时购销同单）：采购侧单价/金额与毛利仅对有权限者可见
            Map.entry("salesPrice", "VIEW_SALE_PRICE"),
            Map.entry("purchasePrice", "VIEW_PURCHASE_PRICE"),
            Map.entry("purchaseAmount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("profitAmount", "VIEW_PROFIT"),
            // 销售报表的估算毛利（amount * 0.12）同样按毛利列受控
            Map.entry("grossProfit", "VIEW_PROFIT"),
            Map.entry("profit", "VIEW_PROFIT"));

    /** 采购单据（订单/入库/收货/退货三单）：单价=采购价，金额家族=采购金额。 */
    public static final Map<String, String> PURCHASE_BILL = Map.ofEntries(
            Map.entry("price", "VIEW_PURCHASE_PRICE"),
            Map.entry("taxPrice", "VIEW_PURCHASE_PRICE"),
            Map.entry("amount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("taxAmount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("untaxedAmount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("totalAmount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("goodsAmount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("finalAmount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("orderAmount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("purchaseAmount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("inboundAmount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("inboundedAmount", "VIEW_PURCHASE_AMOUNT"),
            // 蛇形列标签：Excel 导出等直接用 queryForList 行（未转驼峰）的链路
            Map.entry("inbound_amount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("inbounded_amount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("thisAmount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("billAmount", "VIEW_PURCHASE_AMOUNT"),
            Map.entry("availableStock", "VIEW_STOCK_AMOUNT"),
            Map.entry("returnableQty", "VIEW_STOCK_AMOUNT"),
            Map.entry("beforeCost", "VIEW_COST"),
            Map.entry("afterCost", "VIEW_COST"),
            // 收货单的来票/未开票金额是应付往来信息，归 VIEW_AP_BALANCE（与发票视角一致）
            Map.entry("invoicedAmount", "VIEW_AP_BALANCE"),
            Map.entry("uninvoicedAmount", "VIEW_AP_BALANCE"),
            Map.entry("invoiced_amount", "VIEW_AP_BALANCE"));

    /**
     * 采购发票与勾稽/来票报表：行单价=采购价；发票金额/来票/未开票/对账金额属应付往来，
     * 归 VIEW_AP_BALANCE（区别于采购员可见的采购金额，发票页主要是财务视角）。
     */
    public static final Map<String, String> PURCHASE_INVOICE = Map.ofEntries(
            Map.entry("price", "VIEW_PURCHASE_PRICE"),
            Map.entry("amount", "VIEW_AP_BALANCE"),
            Map.entry("taxAmount", "VIEW_AP_BALANCE"),
            Map.entry("untaxedAmount", "VIEW_AP_BALANCE"),
            Map.entry("totalAmount", "VIEW_AP_BALANCE"),
            Map.entry("invoiceAmount", "VIEW_AP_BALANCE"),
            Map.entry("invoicedAmount", "VIEW_AP_BALANCE"),
            Map.entry("uninvoicedAmount", "VIEW_AP_BALANCE"),
            Map.entry("unbilledAmount", "VIEW_AP_BALANCE"),
            Map.entry("matchedAmount", "VIEW_AP_BALANCE"),
            Map.entry("unmatchedAmount", "VIEW_AP_BALANCE"),
            Map.entry("matchedBefore", "VIEW_AP_BALANCE"),
            Map.entry("paidAmount", "VIEW_AP_BALANCE"),
            Map.entry("unpaidAmount", "VIEW_AP_BALANCE"),
            Map.entry("thisAmount", "VIEW_AP_BALANCE"),
            Map.entry("thisTaxAmount", "VIEW_AP_BALANCE"),
            Map.entry("diffAmount", "VIEW_AP_BALANCE"),
            Map.entry("billAmount", "VIEW_AP_BALANCE"),
            Map.entry("recvAmount", "VIEW_AP_BALANCE"),
            Map.entry("recvAmt", "VIEW_AP_BALANCE"),
            Map.entry("invAmt", "VIEW_AP_BALANCE"),
            Map.entry("certifiedTax", "VIEW_AP_BALANCE"));
}
