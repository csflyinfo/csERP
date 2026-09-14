package com.erp.base;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商品类型与采销准入策略（V122 商品档案优化）。
 *
 * <p>{@code goods_type} 库存数字码（VARCHAR 字符串）：
 * <ul>
 *   <li>0 正常商品：可采可销</li>
 *   <li>1 赠品：可采可销</li>
 *   <li>2 设备辅材：可采不可销</li>
 *   <li>3 包装物：可采不可销</li>
 *   <li>4 兑换物：不可采不可销，但可采退销退</li>
 * </ul>
 * 存量旧类型（组合商品/服务商品，中文值）不在枚举内，一律按可采可销处理。
 */
public final class GoodsBizPolicy {

    public static final String TYPE_NORMAL = "0";
    public static final String TYPE_GIFT = "1";
    public static final String TYPE_EQUIP_AUX = "2";
    public static final String TYPE_PACKAGE = "3";
    public static final String TYPE_EXCHANGE = "4";

    /** 码 → 中文名（导出、报错信息使用） */
    private static final Map<String, String> TYPE_LABELS = new LinkedHashMap<>();
    static {
        TYPE_LABELS.put(TYPE_NORMAL, "正常商品");
        TYPE_LABELS.put(TYPE_GIFT, "赠品");
        TYPE_LABELS.put(TYPE_EQUIP_AUX, "设备辅材");
        TYPE_LABELS.put(TYPE_PACKAGE, "包装物");
        TYPE_LABELS.put(TYPE_EXCHANGE, "兑换物");
    }

    private GoodsBizPolicy() {}

    /** 可采购：兑换物(4)不可采，其余（含旧中文类型）可采 */
    public static boolean canPurchase(String typeCode) {
        return !TYPE_EXCHANGE.equals(typeCode);
    }

    /** 可销售：设备辅材(2)/包装物(3)/兑换物(4)不可销，其余（含旧中文类型）可销 */
    public static boolean canSale(String typeCode) {
        return !TYPE_EQUIP_AUX.equals(typeCode) && !TYPE_PACKAGE.equals(typeCode) && !TYPE_EXCHANGE.equals(typeCode);
    }

    /** 采购退货准入：可采的正向可退，兑换物虽不可采但允许采退 */
    public static boolean purchaseReturnAllowed(String typeCode) {
        return TYPE_EXCHANGE.equals(typeCode) || canPurchase(typeCode);
    }

    /** 销售退货准入：可销的正向可退，兑换物虽不可销但允许销退 */
    public static boolean salesReturnAllowed(String typeCode) {
        return TYPE_EXCHANGE.equals(typeCode) || canSale(typeCode);
    }

    public static String typeLabel(String typeCode) {
        if (typeCode == null) return "";
        return TYPE_LABELS.getOrDefault(typeCode, typeCode);
    }

    /**
     * 商品类型归一化：空白→0；0..4 原样；5 个中文名→对应码；其余抛中文参数异常。
     */
    public static String normalizeType(Object value) {
        if (value == null) return TYPE_NORMAL;
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return TYPE_NORMAL;
        if (s.length() == 1 && s.charAt(0) >= '0' && s.charAt(0) <= '4') return s;
        for (Map.Entry<String, String> e : TYPE_LABELS.entrySet()) {
            if (e.getValue().equals(s)) return e.getKey();
        }
        throw new IllegalArgumentException("商品类型取值无效：" + s + "（只允许：0 正常商品、1 赠品、2 设备辅材、3 包装物、4 兑换物）");
    }

    /**
     * 税率归一化：去 % 后必须是 0-50 的整数（最多两位），返回纯数字字符串；空白→"0"。
     * 兼容老数据 "13%" 与模板中带百分号的写法。
     */
    public static String normalizeTaxRate(Object value) {
        if (value == null) return "0";
        String s = String.valueOf(value).trim().replace("%", "");
        if (s.isEmpty()) return "0";
        int n;
        try {
            // 拒绝小数/非数字；parseInt 前先做严格格式校验
            if (!s.matches("\\d{1,2}")) {
                throw new NumberFormatException();
            }
            n = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("税率取值无效：" + value + "（只允许录入 0-50 的整数）");
        }
        if (n < 0 || n > 50) {
            throw new IllegalArgumentException("税率取值无效：" + n + "（只允许录入 0-50 的整数）");
        }
        return String.valueOf(n);
    }
}
