package com.erp.system.perm;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 敏感字段注册表（PRD-28 §6.4.4）。
 *
 * <p>本项目 Controller 多返回 Map（JdbcTemplate，无统一 VO），脱敏按响应 key 匹配：
 * 每个 field_code 登记它在响应 Map 中可能出现的 key（驼峰/下划线都列）。
 * 新增敏感字段只改这里，不改脱敏器。
 *
 * <p>启动 fail-fast：登记的 field_code 必须在 sys_field_meta（V102 种子，共 26 个）中存在，
 * 防止字段名写错导致脱敏静默失效。
 */
@Component
public class SensitiveFieldRegistry {

    private final Map<String, Set<String>> keysByField = new LinkedHashMap<>();
    // 大小写不敏感：H2 未加引号的列标签会被驱动返回成大写下划线（如 LATEST_PURCHASE_PRICE），
    // Java 层手工映射的行又是驼峰，按同一套小写绑定两边都要能命中
    private final Map<String, String> keyToField = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final JdbcTemplate jdbcTemplate;

    public SensitiveFieldRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void validateAgainstDb() {
        register();
        // 表尚未建好时（极早期迁移场景）不阻断正常启动；正常 V102 之后必须全部命中
        Integer tableExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM information_schema.tables WHERE table_name = 'SYS_FIELD_META'",
                Integer.class);
        if (tableExists == null || tableExists == 0) return;
        Set<String> dbCodes = new LinkedHashSet<>(
                jdbcTemplate.queryForList("SELECT field_code FROM sys_field_meta", String.class));
        for (String code : keysByField.keySet()) {
            if (!dbCodes.contains(code)) {
                throw new IllegalStateException(
                        "敏感字段注册表中的 field_code 在 sys_field_meta 不存在：" + code
                                + "（请检查拼写或补 V102 种子）");
            }
        }
    }

    /** 注册全部 26 个字段及其响应 key（卡片4 脱敏器消费；新模块出现新 key 在此追加）。 */
    private void register() {
        bind("VIEW_SALE_PRICE", "salePrice", "sale_price", "salesPrice", "sales_price",
                "standardPrice", "standard_price");
        bind("VIEW_SALE_AMOUNT", "saleAmount", "sale_amount", "salesAmount", "sales_amount");
        bind("VIEW_PURCHASE_PRICE", "purchasePrice", "purchase_price", "latestPurchasePrice",
                "latest_purchase_price", "referencePurchasePrice", "reference_purchase_price",
                "lastPurchasePrice", "last_purchase_price");
        bind("VIEW_PURCHASE_AMOUNT", "purchaseAmount", "purchase_amount");
        bind("VIEW_COST", "costPrice", "cost_price", "unitCost", "unit_cost",
                "avgCost", "avg_cost", "outboundCost", "outbound_cost", "inboundCost", "inbound_cost");
        bind("VIEW_COST_AMOUNT", "costAmount", "cost_amount", "stockCostAmount", "stock_cost_amount");
        bind("VIEW_PROFIT", "grossProfit", "gross_profit", "grossProfitRate",
                "gross_profit_rate", "profitRate", "profit_rate");
        bind("VIEW_MIN_PRICE", "minPrice", "min_price", "lowestPrice", "lowest_price",
                "minimumSalePrice", "minimum_sale_price", "minSalePrice", "min_sale_price",
                "floorPrice", "floor_price");
        bind("VIEW_SUGGEST_RETAIL_PRICE", "retailPrice", "retail_price", "suggestRetailPrice",
                "suggest_retail_price", "suggestedRetailPrice", "suggested_retail_price",
                "marketPrice", "market_price");
        bind("VIEW_PRICE_GROUP", "groupPrice", "group_price", "priceGroupPrice",
                "price_group_price", "deliveryPrice", "delivery_price");
        bind("VIEW_CUSTOMER_PRICE", "customerPrice", "customer_price", "agreedPrice", "agreed_price");
        bind("VIEW_AR_BALANCE", "arBalance", "ar_balance", "receivableBalance",
                "receivable_balance", "arAmount", "ar_amount", "debtAmount", "debt_amount");
        bind("VIEW_AP_BALANCE", "apBalance", "ap_balance", "payableBalance",
                "payable_balance", "apAmount", "ap_amount");
        bind("VIEW_PRE_RECEIVED", "preReceived", "pre_received", "preReceivedAmount",
                "pre_received_amount", "prepaid", "pre_paid", "prePayment", "pre_payment");
        bind("VIEW_FUND_ACCOUNT_BALANCE", "accountBalance", "account_balance", "fundBalance",
                "fund_balance", "cashBalance", "cash_balance", "bankBalance", "bank_balance");
        bind("VIEW_FUND_FLOW", "fundFlow", "fund_flow", "incomeAmount", "income_amount",
                "expenseAmount", "expense_amount");
        bind("VIEW_CREDIT_LIMIT", "creditLimit", "credit_limit", "creditAmount", "credit_amount");
        bind("VIEW_SETTLE_DETAIL", "settleDetail", "settle_detail", "writeOffAmount",
                "write_off_amount", "writeoffAmount", "writeoff_amount", "handoverAmount", "handover_amount");
        bind("VIEW_PROFIT_TOTAL", "netProfit", "net_profit", "totalProfit", "total_profit",
                "operatingProfit", "operating_profit");
        bind("VIEW_STOCK_AMOUNT", "stockQty", "stock_qty", "stockAmount", "stock_amount",
                "availableQty", "available_qty", "balanceQty", "balance_qty");
        bind("VIEW_STOCK_LOCK", "lockedQty", "locked_qty", "lockQty", "lock_qty",
                "allocatedQty", "allocated_qty", "preAllocatedQty", "pre_allocated_qty");
        bind("VIEW_STOCK_COST", "stockCost", "stock_cost", "inventoryAmount", "inventory_amount",
                "stockValue", "stock_value");
        bind("VIEW_SUPPLIER_OF_GOODS", "defaultSupplier", "default_supplier",
                "mainSupplier", "main_supplier");
        bind("VIEW_CUSTOMER_MOBILE", "customerMobile", "customer_mobile", "contactMobile",
                "contact_mobile", "customerPhone", "customer_phone");
        bind("VIEW_SUPPLIER_MOBILE", "supplierMobile", "supplier_mobile", "supplierPhone", "supplier_phone");
        bind("VIEW_DRIVER_MOBILE", "driverMobile", "driver_mobile", "driverPhone", "driver_phone");
    }

    private void bind(String fieldCode, String... keys) {
        Set<String> set = keysByField.computeIfAbsent(fieldCode, k -> new LinkedHashSet<>());
        for (String key : keys) {
            set.add(key);
            keyToField.put(key, fieldCode);
        }
    }

    public Set<String> fieldCodes() {
        return keysByField.keySet();
    }

    /** 响应 key → field_code（无映射返回 null）。 */
    public String fieldOfKey(String key) {
        if (key == null) return null;
        return keyToField.get(key);
    }

    public Set<String> keysOfField(String fieldCode) {
        return keysByField.getOrDefault(fieldCode, Set.of());
    }
}
