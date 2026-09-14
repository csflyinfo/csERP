package com.erp.base;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.erp.base.entity.BaseBrand;
import com.erp.base.entity.BaseCategory;
import com.erp.base.service.BaseBrandService;
import com.erp.base.service.BaseCategoryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商品导入与采销准入共用支撑（V122 商品档案优化）：
 * 分类解析（编号优先、名称唯一兼容）、大类编码生成、品牌按名称自动新建、
 * 主数据存在性校验、业务场景准入批量硬校验。
 */
@Component
public class GoodsImportSupport {

    public enum Scene {
        PURCHASE("不可采购"),
        SALE("不可销售"),
        PURCHASE_RETURN("不允许采购退货"),
        SALES_RETURN("不允许销售退货");

        private final String denyText;

        Scene(String denyText) { this.denyText = denyText; }
    }

    private final JdbcTemplate jdbcTemplate;
    private final BaseCategoryService categoryService;
    private final BaseBrandService brandService;

    public GoodsImportSupport(JdbcTemplate jdbcTemplate,
                              BaseCategoryService categoryService,
                              BaseBrandService brandService) {
        this.jdbcTemplate = jdbcTemplate;
        this.categoryService = categoryService;
        this.brandService = brandService;
    }

    /**
     * 解析商品分类：编号优先；编号为空或查不到时，值按分类名称匹配（同名唯一才采用）。
     * 与 BaseController 编码自动生成的兼容口径保持一致。
     */
    public BaseCategory resolveCategory(String codeOrName) {
        String value = codeOrName == null ? "" : codeOrName.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("商品分类编号必填（须先在商品分类中维护）");
        }
        BaseCategory category = categoryService.getOne(
                new QueryWrapper<BaseCategory>().eq("category_code", value).last("LIMIT 1"));
        if (category != null) return category;
        List<BaseCategory> matches = categoryService.list(
                new QueryWrapper<BaseCategory>().eq("category_name", value));
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("商品分类「" + value + "」不存在，须先维护分类或填写正确的分类编号");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("存在多个同名商品分类「" + value + "」，请改填分类编号");
        }
        return matches.get(0);
    }

    /**
     * 按分类上溯到大类（一级分类），生成「大类编码 + 5 位流水号」。
     * 软删商品仍占号；synchronized 单机防并发，唯一约束兜底。
     */
    public synchronized String nextCodeByCategory(BaseCategory category) {
        String rootCode = category.getCategoryCode();
        java.util.Set<String> guard = new java.util.HashSet<>();
        guard.add(rootCode);
        BaseCategory cur = category;
        while (cur.getParentCode() != null && !cur.getParentCode().isBlank()) {
            rootCode = cur.getParentCode();
            if (!guard.add(rootCode)) {
                throw new IllegalArgumentException("商品分类层级存在循环引用，无法自动生成商品编码");
            }
            BaseCategory parent = categoryService.getOne(
                    new QueryWrapper<BaseCategory>().eq("category_code", rootCode));
            if (parent == null) {
                throw new IllegalArgumentException("商品大类编码「" + rootCode + "」不存在，无法自动生成商品编码");
            }
            cur = parent;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT MAX(goods_code) AS max_code FROM base_goods WHERE goods_code LIKE ?",
                rootCode + "_____");
        String maxCode = rows.isEmpty() || rows.get(0).get("max_code") == null
                ? null : String.valueOf(rows.get(0).get("max_code"));
        int seq = 1;
        if (maxCode != null) {
            String tail = maxCode.length() > rootCode.length() ? maxCode.substring(rootCode.length()) : "";
            if (tail.matches("\\d{5}")) seq = Integer.parseInt(tail) + 1;
        }
        if (seq > 99999) {
            throw new IllegalArgumentException("商品大类「" + rootCode + "」编码流水号已达上限（99999）");
        }
        return rootCode + String.format("%05d", seq);
    }

    /** 品牌按名称查；不存在则自动新建（商品档案只冗余品牌名称），返回品牌名称。 */
    public String ensureBrandByName(String name) {
        String brandName = name == null ? "" : name.trim();
        if (brandName.isEmpty()) brandName = "未维护";
        BaseBrand existing = brandService.getOne(
                new QueryWrapper<BaseBrand>().eq("brand_name", brandName).last("LIMIT 1"));
        if (existing != null) return brandName;
        BaseBrand brand = new BaseBrand();
        String id = "BR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        brand.setBrandId(id);
        brand.setBrandCode(id);
        brand.setBrandName(brandName);
        brand.setStatus("NORMAL");
        brand.setGoodsCount(0);
        brandService.save(brand);
        return brandName;
    }

    public boolean unitExists(String unitName) {
        return countExists("SELECT COUNT(1) FROM base_unit WHERE unit_name = ? AND COALESCE(status,'NORMAL') <> 'STOPPED'", unitName);
    }

    public boolean warehouseExists(String warehouseName) {
        return countExists("SELECT COUNT(1) FROM base_warehouse WHERE warehouse_name = ? AND COALESCE(status,'NORMAL') <> 'STOPPED'", warehouseName);
    }

    public boolean supplierExists(String supplierName) {
        return countExists("SELECT COUNT(1) FROM base_supplier WHERE supplier_name = ? AND COALESCE(status,'NORMAL') <> 'STOPPED'", supplierName);
    }

    public boolean employeeExists(String employeeName) {
        return countExists("SELECT COUNT(1) FROM base_employee WHERE employee_name = ? AND COALESCE(status,'NORMAL') = 'NORMAL'", employeeName);
    }

    private boolean countExists(String sql, String name) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, name.trim());
        return n != null && n > 0;
    }

    /**
     * 业务保存时的采销准入硬校验：首个违规商品抛中文异常（指明编码/名称/原因）。
     * 库中不存在的编码不拦截（保持现状，不在本次扩大校验面）。
     */
    public void assertBizAllowed(Collection<String> goodsCodes, Scene scene) {
        if (goodsCodes == null || goodsCodes.isEmpty()) return;
        List<String> distinct = goodsCodes.stream().filter(c -> c != null && !c.isBlank()).distinct().toList();
        if (distinct.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(distinct.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT goods_code, goods_name, goods_type FROM base_goods WHERE goods_code IN (" + placeholders + ")",
                distinct.toArray());
        for (Map<String, Object> row : rows) {
            String type = row.get("GOODS_TYPE") == null ? "" : String.valueOf(row.get("GOODS_TYPE"));
            boolean allowed = switch (scene) {
                case PURCHASE -> GoodsBizPolicy.canPurchase(type);
                case SALE -> GoodsBizPolicy.canSale(type);
                case PURCHASE_RETURN -> GoodsBizPolicy.purchaseReturnAllowed(type);
                case SALES_RETURN -> GoodsBizPolicy.salesReturnAllowed(type);
            };
            if (!allowed) {
                throw new IllegalArgumentException("商品【" + row.get("GOODS_CODE") + " " + row.get("GOODS_NAME")
                        + "】" + scene.denyText + "（商品类型：" + GoodsBizPolicy.typeLabel(type) + "）");
            }
        }
    }
}
