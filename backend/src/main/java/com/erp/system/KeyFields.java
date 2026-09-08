package com.erp.system;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 各业务对象「修改留痕」的关键字段清单。
 *
 * <p>规则（见 PRD-31）：修改日志只对关键字段做字段级改前/改后对比；备注等非关键字段变化时，
 * 仅在 operation_content 标注「修改了备注等非关键字段」，不逐字留痕。明细行按行级 diff。
 *
 * <p>Map 的 key 必须与控制器构造 before/after Map 时使用的 key 完全一致；value 是展示用中文标签。
 * 用 LinkedHashMap 保证字段在 operation_content 与对比表中的顺序稳定。
 */
public final class KeyFields {

    // ---- 业务对象类型（biz_type）：与表名对齐，用于按单据聚合时间线 ----
    public static final String BIZ_PURCHASE_ORDER   = "purchase_order";
    public static final String BIZ_SALES_ORDER      = "sales_order";
    public static final String BIZ_GOODS            = "base_goods";
    public static final String BIZ_CUSTOMER         = "base_customer";
    public static final String BIZ_SUPPLIER         = "base_supplier";
    public static final String BIZ_WAREHOUSE        = "base_warehouse";

    // 业务单据 biz_type（用于按单聚合时间线；Tier1 动作用，logUpdate 不一定配字段表）
    public static final String BIZ_PURCHASE_RECEIPT = "purchase_receipt";   // 采购入库
    public static final String BIZ_PURCHASE_RETURN  = "purchase_return";    // 采购退货
    public static final String BIZ_SALES_OUTBOUND   = "sales_outbound";     // 销售出库
    public static final String BIZ_SALES_RECEIPT    = "sales_receipt";      // 销售签收/收款
    public static final String BIZ_SALES_RETURN     = "sales_return";       // 销售退货
    public static final String BIZ_REJECT_INBOUND   = "reject_inbound";     // 拒收入库
    public static final String BIZ_OTHER_INBOUND    = "other_inbound";      // 其他入库
    public static final String BIZ_OTHER_OUTBOUND   = "other_outbound";     // 其他出库
    public static final String BIZ_DAMAGE           = "damage";             // 报损报溢
    public static final String BIZ_TRANSFER         = "transfer";           // 库存调拨
    public static final String BIZ_STOCK_TAKE       = "stock_take";         // 库存盘点
    public static final String BIZ_FIN_RECEIPT      = "fin_receipt";        // 收款单
    public static final String BIZ_FIN_PAYMENT      = "fin_payment";        // 付款单
    public static final String BIZ_FIN_EXPENSE      = "fin_expense";        // 费用单

    /** 主表关键字段：bizType -> (fieldKey -> 中文标签)。 */
    private static final Map<String, LinkedHashMap<String, String>> MAIN_FIELDS = new LinkedHashMap<>();
    /** 明细行关键字段：bizType -> (fieldKey -> 中文标签)。 */
    private static final Map<String, LinkedHashMap<String, String>> LINE_FIELDS = new LinkedHashMap<>();
    /**
     * 敏感字段（采购价/成本/价格体系/信用额度）：命中则整条日志 sensitive=Y，
     * 且在单据时间线里对非管理员脱敏。
     * 注：不含通用 price/amount——销售单价/单据金额是业务员本就可见的字段（角色隔离已在模块层控制），
     * 采购单价对采购员、销售单价对销售员各自可见，无需脱敏。
     */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "latest_purchase_price", "cost_price", "cost_amount", "standard_price",
            "min_sale_price", "suggested_retail_price", "credit_limit"
    );

    static {
        // ---- 商品 ----
        LinkedHashMap<String, String> goods = new LinkedHashMap<>();
        goods.put("goods_code", "商品编码");
        goods.put("goods_name", "商品名称");
        goods.put("spec", "规格");
        goods.put("category_name", "商品分类");
        goods.put("brand_name", "品牌");
        goods.put("base_unit", "基本单位");
        goods.put("barcode", "条码");
        goods.put("goods_type", "商品类型");
        goods.put("storage_property", "温区/储存属性");
        goods.put("tax_rate", "税率");
        goods.put("standard_price", "标准售价");
        goods.put("latest_purchase_price", "最近采购价");
        goods.put("min_sale_price", "最低售价");
        goods.put("suggested_retail_price", "建议零售价");
        goods.put("default_supplier", "默认供应商");
        goods.put("default_warehouse", "默认仓库");
        goods.put("can_sale", "可销售");
        goods.put("can_purchase", "可采购");
        goods.put("shelf_life_days", "保质期天数");
        goods.put("warning_days", "预警天数");
        goods.put("status", "状态");
        MAIN_FIELDS.put(BIZ_GOODS, goods);

        // ---- 客户 ----
        LinkedHashMap<String, String> customer = new LinkedHashMap<>();
        customer.put("customer_code", "客户编码");
        customer.put("customer_name", "客户名称");
        customer.put("channel_type", "渠道类型");
        customer.put("contact_name", "联系人");
        customer.put("mobile", "手机号");
        customer.put("territory", "区域");
        customer.put("route_line", "路线");
        customer.put("salesman", "业务员");
        customer.put("customer_level", "客户等级");
        customer.put("account_period_type", "账期类型");
        customer.put("cutoff_day", "结账日");
        customer.put("payment_day", "付款日");
        customer.put("credit_limit", "信用额度");
        customer.put("invoice_title", "发票抬头");
        customer.put("tax_no", "税号");
        customer.put("status", "状态");
        MAIN_FIELDS.put(BIZ_CUSTOMER, customer);

        // ---- 供应商 ----
        LinkedHashMap<String, String> supplier = new LinkedHashMap<>();
        supplier.put("supplier_code", "供应商编码");
        supplier.put("supplier_name", "供应商名称");
        supplier.put("short_name", "简称");
        supplier.put("supplier_type", "供应商类型");
        supplier.put("contact_name", "联系人");
        supplier.put("phone", "电话");
        supplier.put("settlement_method", "结算方式");
        supplier.put("account_period_days", "账期天数");
        supplier.put("delivery_days", "送货天数");
        supplier.put("default_buyer", "默认采购员");
        supplier.put("invoice_title", "发票抬头");
        supplier.put("tax_no", "税号");
        supplier.put("status", "状态");
        MAIN_FIELDS.put(BIZ_SUPPLIER, supplier);

        // ---- 仓库 ----
        LinkedHashMap<String, String> wh = new LinkedHashMap<>();
        wh.put("warehouse_code", "仓库编码");
        wh.put("warehouse_name", "仓库名称");
        wh.put("warehouse_type", "仓库类型");
        wh.put("inventory_type", "库存类型");
        wh.put("cost_group", "成本分组");
        wh.put("manager_name", "负责人");
        wh.put("status", "状态");
        MAIN_FIELDS.put(BIZ_WAREHOUSE, wh);

        // ---- 采购订单（主表）----
        LinkedHashMap<String, String> po = new LinkedHashMap<>();
        po.put("order_no", "单据编号");
        po.put("supplier_name", "供应商");
        po.put("buyer", "采购员");
        po.put("warehouse", "仓库");
        po.put("bill_date", "单据日期");
        po.put("amount", "单据金额");
        po.put("paid_amount", "已付金额");
        po.put("unpaid_amount", "未付金额");
        po.put("inbound_status", "入库状态");
        po.put("payment_status", "付款状态");
        po.put("status", "单据状态");
        MAIN_FIELDS.put(BIZ_PURCHASE_ORDER, po);

        // ---- 销售订单（主表）----
        LinkedHashMap<String, String> so = new LinkedHashMap<>();
        so.put("order_no", "单据编号");
        so.put("customer", "客户");
        so.put("salesman", "业务员");
        so.put("warehouse", "仓库");
        so.put("bill_date", "单据日期");
        so.put("amount", "单据金额");
        so.put("paid_amount", "已收金额");
        so.put("unpaid_amount", "未收金额");
        so.put("outbound_status", "出库状态");
        so.put("sign_status", "签收状态");
        so.put("status", "单据状态");
        MAIN_FIELDS.put(BIZ_SALES_ORDER, so);

        // ---- 订单明细行（采购/销售共用结构）----
        LinkedHashMap<String, String> line = new LinkedHashMap<>();
        line.put("goods_code", "商品编码");
        line.put("goods_name", "商品名称");
        line.put("unit_name", "单位");
        line.put("qty", "数量");
        line.put("price", "单价");
        line.put("discount_rate", "折扣率");
        line.put("tax_rate", "税率");
        line.put("amount", "金额");
        line.put("cost_price", "成本单价");
        line.put("cost_amount", "成本金额");
        LINE_FIELDS.put(BIZ_PURCHASE_ORDER, line);
        LINE_FIELDS.put(BIZ_SALES_ORDER, line);
    }

    private KeyFields() {}

    /** 主表关键字段（field -> label），无配置返回空 Map。 */
    public static Map<String, String> mainFields(String bizType) {
        return MAIN_FIELDS.getOrDefault(bizType, new LinkedHashMap<>());
    }

    /** 明细行关键字段（field -> label），无配置返回空 Map。 */
    public static Map<String, String> lineFields(String bizType) {
        return LINE_FIELDS.getOrDefault(bizType, new LinkedHashMap<>());
    }

    /** 该字段是否敏感（敏感字段变化会把整条日志标记为 sensitive=Y）。 */
    public static boolean isSensitiveField(String fieldKey) {
        return fieldKey != null && SENSITIVE_FIELDS.contains(fieldKey);
    }
}
