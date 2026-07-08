package com.erp.base;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.base.entity.*;
import com.erp.base.service.*;
import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/base")
public class BaseController {

    private final BaseCategoryService categoryService;
    private final BaseUnitService unitService;
    private final BaseBrandService brandService;
    private final BaseWarehouseService warehouseService;
    private final BaseGoodsService goodsService;
    private final BaseCustomerService customerService;
    private final BaseSupplierService supplierService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public BaseController(BaseCategoryService categoryService,
                          BaseUnitService unitService,
                          BaseBrandService brandService,
                          BaseWarehouseService warehouseService,
                          BaseGoodsService goodsService,
                          BaseCustomerService customerService,
                          BaseSupplierService supplierService,
                          org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.categoryService = categoryService;
        this.unitService = unitService;
        this.brandService = brandService;
        this.warehouseService = warehouseService;
        this.goodsService = goodsService;
        this.customerService = customerService;
        this.supplierService = supplierService;
        this.jdbcTemplate = jdbcTemplate;
    }

    private <T> Page<T> toMpPage(PageRequest request) {
        int pageNo = request.safePageNo();
        int pageSize = request.safePageSize();
        return new Page<>(pageNo, pageSize);
    }

    private <T> PageResult<T> toPageResult(IPage<T> page) {
        return new PageResult<>(page.getRecords(), (int) page.getCurrent(), (int) page.getSize(), page.getTotal(), Map.of());
    }

    private String genId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    // ========== 商品分类 ==========
    @PostMapping("/category/page")
    public ApiResponse<PageResult<Map<String, Object>>> categoryPage(@RequestBody PageRequest request) {
        QueryWrapper<BaseCategory> qw = new QueryWrapper<>();
        qw.orderByAsc("category_code");
        IPage<BaseCategory> page = categoryService.page(toMpPage(request), qw);
        // 一次查全表建 code→name 索引，避免 N+1
        java.util.List<BaseCategory> all = categoryService.list();
        java.util.Map<String, String> codeToName = new java.util.HashMap<>();
        for (BaseCategory c : all) {
            if (c.getCategoryCode() != null) codeToName.put(c.getCategoryCode(), c.getCategoryName());
        }
        java.util.List<Map<String, Object>> records = new java.util.ArrayList<>();
        for (BaseCategory c : page.getRecords()) {
            java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("categoryId", c.getCategoryId());
            row.put("parentId", c.getParentId());
            row.put("parentCode", c.getParentCode());
            row.put("parentName", c.getParentCode() == null || c.getParentCode().isBlank() ? "" : codeToName.getOrDefault(c.getParentCode(), ""));
            row.put("categoryCode", c.getCategoryCode());
            row.put("categoryName", c.getCategoryName());
            row.put("defaultTaxRate", c.getDefaultTaxRate());
            row.put("externalCode", c.getExternalCode());
            row.put("goodsCount", c.getGoodsCount());
            row.put("status", c.getStatus());
            records.add(row);
        }
        return ApiResponse.ok(new PageResult<>(records, (int) page.getCurrent(), (int) page.getSize(), page.getTotal(), Map.of()));
    }

    @PostMapping("/category/create")
    public ApiResponse<BaseCategory> createCategory(@RequestBody Map<String, Object> request) {
        BaseCategory entity = new BaseCategory();
        entity.setCategoryId(genId("CATE"));
        String parentCode = String.valueOf(request.getOrDefault("parentCode", "")).trim();
        String categoryCode = String.valueOf(request.getOrDefault("categoryCode", "")).trim();
        String categoryName = String.valueOf(request.getOrDefault("categoryName", "新分类")).trim();
        // parentId：如果传了就用；否则根据 parentCode 反查
        String parentId = String.valueOf(request.getOrDefault("parentId", "")).trim();
        if ((parentId == null || parentId.isBlank()) && !parentCode.isBlank()) {
            BaseCategory parent = categoryService.getOne(new QueryWrapper<BaseCategory>().eq("category_code", parentCode));
            if (parent != null) parentId = parent.getCategoryId();
        }
        // 生成完整分类编码：如果用户输入的编码已经以父编码开头，直接用；否则拼接
        String fullCode;
        if (parentCode.isBlank()) {
            fullCode = categoryCode;
        } else if (categoryCode.startsWith(parentCode)) {
            fullCode = categoryCode;
        } else {
            fullCode = parentCode + categoryCode;
        }
        if (fullCode == null || fullCode.isBlank()) {
            fullCode = entity.getCategoryId();
        }
        // 唯一性校验
        if (categoryService.getOne(new QueryWrapper<BaseCategory>().eq("category_code", fullCode)) != null) {
            return ApiResponse.fail("400", "分类编码 " + fullCode + " 已存在");
        }
        entity.setParentId(parentId);
        entity.setParentCode(parentCode);
        entity.setCategoryCode(fullCode);
        entity.setCategoryName(categoryName);
        Object taxRate = request.get("defaultTaxRate");
        if (taxRate == null) taxRate = request.get("taxRate");
        entity.setDefaultTaxRate(taxRate == null ? "13%" : normalizeTaxRate(taxRate.toString()));
        Object extCode = request.get("externalCode");
        entity.setExternalCode(extCode == null ? null : String.valueOf(extCode).trim());
        entity.setGoodsCount(0);
        entity.setStatus("NORMAL");
        categoryService.save(entity);
        return ApiResponse.ok(entity);
    }

    @PostMapping("/category/update")
    public ApiResponse<Void> updateCategory(@RequestBody Map<String, Object> request) {
        String code = (String) request.get("categoryCode");
        BaseCategory entity = categoryService.getOne(new QueryWrapper<BaseCategory>().eq("category_code", code));
        if (entity == null) return ApiResponse.fail("404", "分类不存在");
        if (request.get("categoryName") != null) entity.setCategoryName((String) request.get("categoryName"));
        Object taxRate = request.get("defaultTaxRate");
        if (taxRate == null) taxRate = request.get("taxRate");
        if (taxRate != null) entity.setDefaultTaxRate(normalizeTaxRate(taxRate.toString()));
        if (request.get("externalCode") != null) entity.setExternalCode(String.valueOf(request.get("externalCode")).trim());
        if (request.get("status") != null) {
            String st = String.valueOf(request.get("status")).trim();
            entity.setStatus(st.isBlank() ? entity.getStatus() : st);
        }
        categoryService.updateById(entity);
        return ApiResponse.ok(null);
    }

    /** 归一化税率：接受 "13"/"13%"/"13.0"；限制两位正整数 (0-99)。非法值返回原字符串截断. */
    private String normalizeTaxRate(String raw) {
        if (raw == null) return "13%";
        String s = raw.trim().replace("%", "");
        if (s.isEmpty()) return "0%";
        try {
            int v = (int) Math.floor(Double.parseDouble(s));
            if (v < 0) v = 0;
            if (v > 99) v = 99;
            return v + "%";
        } catch (NumberFormatException e) {
            return "13%";
        }
    }

    // ========== 计量单位 ==========
    @PostMapping("/unit/page")
    public ApiResponse<PageResult<BaseUnit>> unitPage(@RequestBody PageRequest request) {
        QueryWrapper<BaseUnit> qw = new QueryWrapper<>();
        qw.orderByAsc("unit_code");
        IPage<BaseUnit> page = unitService.page(toMpPage(request), qw);
        return ApiResponse.ok(toPageResult(page));
    }

    @PostMapping("/unit/create")
    public ApiResponse<BaseUnit> createUnit(@RequestBody Map<String, Object> request) {
        BaseUnit entity = new BaseUnit();
        entity.setUnitId(genId("UNIT"));
        entity.setUnitCode((String) request.getOrDefault("unitCode", entity.getUnitId()));
        entity.setUnitName((String) request.getOrDefault("unitName", "新单位"));
        // 基本单位始终可用；中/大单位由前端勾选控制
        entity.setCanBaseUnit(true);
        entity.setCanMiddleUnit(parseBoolean(request.get("canMiddleUnit"), false));
        entity.setCanLargeUnit(parseBoolean(request.get("canLargeUnit"), false));
        entity.setGoodsCount(0);
        entity.setStatus("NORMAL");
        unitService.save(entity);
        return ApiResponse.ok(entity);
    }

    // ========== 品牌 ==========
    @PostMapping("/brand/page")
    public ApiResponse<PageResult<BaseBrand>> brandPage(@RequestBody PageRequest request) {
        QueryWrapper<BaseBrand> qw = new QueryWrapper<>();
        qw.orderByAsc("brand_code");
        IPage<BaseBrand> page = brandService.page(toMpPage(request), qw);
        return ApiResponse.ok(toPageResult(page));
    }

    @PostMapping("/brand/create")
    public ApiResponse<BaseBrand> createBrand(@RequestBody Map<String, Object> request) {
        BaseBrand entity = new BaseBrand();
        entity.setBrandId(genId("BR"));
        entity.setBrandCode((String) request.getOrDefault("brandCode", entity.getBrandId()));
        entity.setBrandName((String) request.getOrDefault("brandName", "新品牌"));
        entity.setSimpleCode((String) request.getOrDefault("simpleCode", ""));
        entity.setGoodsCount(0);
        entity.setStatus("NORMAL");
        brandService.save(entity);
        return ApiResponse.ok(entity);
    }

    // ========== 仓库 ==========
    @PostMapping("/warehouse/page")
    public ApiResponse<PageResult<BaseWarehouse>> warehousePage(@RequestBody PageRequest request) {
        QueryWrapper<BaseWarehouse> qw = new QueryWrapper<>();
        qw.orderByAsc("warehouse_code");
        IPage<BaseWarehouse> page = warehouseService.page(toMpPage(request), qw);
        return ApiResponse.ok(toPageResult(page));
    }

    @PostMapping("/warehouse/create")
    public ApiResponse<BaseWarehouse> createWarehouse(@RequestBody Map<String, Object> request) {
        BaseWarehouse entity = new BaseWarehouse();
        entity.setWarehouseId(genId("WH"));
        entity.setWarehouseCode((String) request.getOrDefault("warehouseCode", entity.getWarehouseId()));
        entity.setWarehouseName((String) request.getOrDefault("warehouseName", "新仓库"));
        entity.setWarehouseType((String) request.getOrDefault("warehouseType", "正常仓"));
        entity.setInventoryType((String) request.getOrDefault("inventoryType", "平台主仓"));
        entity.setCostGroup((String) request.getOrDefault("costGroup", "CG01"));
        entity.setManagerName((String) request.getOrDefault("managerName", ""));
        entity.setStatus("NORMAL");
        warehouseService.save(entity);
        return ApiResponse.ok(entity);
    }

    // ========== 商品档案 ==========
    @PostMapping("/goods/page")
    public ApiResponse<PageResult<BaseGoods>> goodsPage(@RequestBody PageRequest request) {
        QueryWrapper<BaseGoods> qw = new QueryWrapper<>();
        String keyword = request.keyword();
        if (keyword != null && !keyword.isBlank()) {
            // 关键字覆盖 编号 / 名称 / 条码 / 简拼 —— 简拼（simple_code）是录单时最常用的快速检索方式。
            // 用 UPPER() 两侧统一大写：H2 的 LIKE 区分大小写，而用户习惯小写输入简拼（nfsqkqs）。
            String upper = keyword.toUpperCase(java.util.Locale.ROOT);
            qw.and(w -> w.apply("UPPER(goods_code) LIKE {0}", "%" + upper + "%")
                    .or().apply("UPPER(goods_name) LIKE {0}", "%" + upper + "%")
                    .or().apply("UPPER(barcode) LIKE {0}", "%" + upper + "%")
                    .or().apply("UPPER(simple_code) LIKE {0}", "%" + upper + "%"));
        }
        qw.orderByDesc("goods_code");
        IPage<BaseGoods> page = goodsService.page(toMpPage(request), qw);
        return ApiResponse.ok(toPageResult(page));
    }

    /**
     * 销售录单的商品候选列表 —— 「最近一年有交易的优先，按销量高→低」。
     *
     * <p><b>排序规则</b>（两级）：
     * <ol>
     *   <li>最近一年有交易的商品排在前面（{@code recent_qty > 0}）</li>
     *   <li>组内按最近一年销量降序；销量相同或都无交易时按商品编号排序</li>
     * </ol>
     * 用 LEFT JOIN：一年内没交易的商品仍会出现在后面，否则新商品/冷门商品无法录单。
     *
     * <p><b>销量口径</b>：{@code sales_order_detail.qty} 之和，
     * 只统计 {@code bill_date} 在最近一年内、且单据未作废/未删除的（含待审核单，新开单立即影响排序）。
     * 传了 customerCode 则只统计该客户，否则统计全店。
     *
     * <p><b>关键字</b>：为空时返回销量前 N 条；非空时在全部正常商品里模糊匹配
     * 编号/名称/简拼/条码，命中结果同样按上述规则排序。
     *
     * <p>返回字段包含 unit_config / is_weighted / simple_code 等，
     * 供前端内联选择器选中后直接建行，避免二次查商品档案。
     *
     * @param body customerCode / keyword / limit
     */
    @PostMapping("/goods/sale-ranking")
    public ApiResponse<List<Map<String, Object>>> goodsSaleRanking(@RequestBody Map<String, Object> body) {
        String customerCode = trimStr(body.get("customerCode"));
        String keyword = trimStr(body.get("keyword"));
        int limit = parseLimit(body.get("limit"));

        // 最近一年销量子查询。用 bill_date 而非 create_time：
        // 单据日期才是业务上的成交日期，create_time 是录入时间。
        StringBuilder saleSub = new StringBuilder("""
                SELECT d.goods_code AS gc, SUM(d.qty) AS total_qty
                FROM sales_order_detail d
                JOIN sales_order o ON o.order_id = d.order_id
                WHERE o.status NOT IN ('CANCELLED', 'DELETED')
                  AND o.bill_date >= ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(java.sql.Date.valueOf(java.time.LocalDate.now().minusYears(1)));
        if (!customerCode.isEmpty()) {
            saleSub.append(" AND o.customer_code = ?");
            args.add(customerCode);
        }
        saleSub.append(" GROUP BY d.goods_code");

        // 商品字段一次取全，供前端建行使用
        String goodsCols = """
                g.goods_code, g.goods_name, g.spec, g.barcode, g.simple_code,
                g.category_name, g.brand_name, g.base_unit, g.unit_config,
                g.is_weighted, g.standard_price, g.status
                """;

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(goodsCols)
           .append(", COALESCE(s.total_qty, 0) AS sale_qty")
           // 一年内有交易 = 1，无交易 = 0，作为第一排序键
           .append(", CASE WHEN COALESCE(s.total_qty, 0) > 0 THEN 1 ELSE 0 END AS recent_traded")
           .append(" FROM base_goods g LEFT JOIN (").append(saleSub)
           .append(") s ON s.gc = g.goods_code WHERE g.status = 'NORMAL'");

        // 关键字：编号 / 名称 / 简拼 / 条码，UPPER 双侧统一（H2 的 LIKE 区分大小写）
        if (!keyword.isEmpty()) {
            String kw = "%" + keyword.toUpperCase(java.util.Locale.ROOT) + "%";
            sql.append(" AND (UPPER(g.goods_code) LIKE ? OR UPPER(g.goods_name) LIKE ?")
               .append(" OR UPPER(g.simple_code) LIKE ? OR UPPER(g.barcode) LIKE ?)");
            args.add(kw); args.add(kw); args.add(kw); args.add(kw);
        }
        // 有交易的优先 → 销量降序 → 编号兜底（保证顺序稳定）
        sql.append(" ORDER BY recent_traded DESC, sale_qty DESC, g.goods_code LIMIT ").append(limit);

        List<Map<String, Object>> raw = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new java.util.ArrayList<>(raw.size());
        for (Map<String, Object> r : raw) out.add(camelKeys(r));
        return ApiResponse.ok(out);
    }

    private static String trimStr(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 条数上限：默认 20，兜住非法值与超大值 */
    private static int parseLimit(Object v) {
        int n = 20;
        if (v != null) {
            try { n = Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException ignore) { }
        }
        if (n <= 0) n = 20;
        return Math.min(n, 200);
    }

    /**
     * 下划线列名转驼峰。
     * H2 开了 CASE_INSENSITIVE_IDENTIFIERS，queryForList 返回的 key 是大写（GOODS_CODE），
     * 前端按驼峰取值，必须统一转换。
     */
    private static Map<String, Object> camelKeys(Map<String, Object> row) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String lower = e.getKey().toLowerCase(java.util.Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : lower.toCharArray()) {
                if (c == '_') { upper = true; continue; }
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            out.put(sb.toString(), e.getValue());
        }
        return out;
    }

    @PostMapping("/goods/create")
    public ApiResponse<BaseGoods> createGoods(@RequestBody Map<String, Object> request) {
        BaseGoods entity = new BaseGoods();
        entity.setGoodsId(genId("G"));
        fillGoodsEntity(entity, request);
        entity.setCurrentStock(java.math.BigDecimal.ZERO);
        goodsService.save(entity);
        return ApiResponse.ok(entity);
    }

    @PostMapping("/goods/selector")
    public ApiResponse<java.util.List<BaseGoods>> goodsSelector() {
        QueryWrapper<BaseGoods> qw = new QueryWrapper<>();
        qw.eq("status", "NORMAL").orderByAsc("goods_code");
        return ApiResponse.ok(goodsService.list(qw));
    }

    /**
     * 商品多单位矩阵用的价格组数据。
     * <p>返回所有<b>已启用</b>的价格组，每组附带三个单位级别的小/中/大价格
     * （从 {@code base_price_group_item} 取，无记录则为 0）。
     * 单位矩阵中这些价格是<b>只读</b>的 —— 由价格组调价单更新。
     *
     * @param goodsCode 可选；传了则带出该商品在各价格组的价格，不传则仅返回价格组列表
     */
    @GetMapping("/goods/price-groups")
    public ApiResponse<java.util.List<Map<String, Object>>> goodsPriceGroups(
            @RequestParam(required = false) String goodsCode) {
        // 所有已启用的价格组
        java.util.List<Map<String, Object>> groups = jdbcTemplate.queryForList(
                "SELECT price_group_code, price_group_name, sort_order FROM base_price_group WHERE enabled = TRUE ORDER BY sort_order, price_group_code");
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> g : groups) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("priceGroupCode", str(g.get("price_group_code")));
            row.put("priceGroupName", str(g.get("price_group_name")));
            row.put("sortOrder", g.get("sort_order"));
            // 取出该商品在该价格组的三级单位价格
            String pgc = str(g.get("price_group_code"));
            if (goodsCode != null && !goodsCode.isBlank()) {
                java.util.List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        SELECT unit_level, price FROM base_price_group_item
                        WHERE goods_code = ? AND price_group_code = ? AND is_active = TRUE
                        """, goodsCode, pgc);
                for (Map<String, Object> it : items) {
                    int level = ((Number) it.get("unit_level")).intValue();
                    BigDecimal price = parseDecimal(it.get("price"));
                    switch (level) {
                        case 1 -> row.put("smallPrice", price);
                        case 2 -> row.put("middlePrice", price);
                        case 3 -> row.put("largePrice", price);
                    }
                }
            }
            result.add(row);
        }
        return ApiResponse.ok(result);
    }


    @PostMapping("/goods/update")
    public ApiResponse<Void> updateGoods(@RequestBody Map<String, Object> request) {
        String bizId = String.valueOf(request.getOrDefault("goodsId", request.getOrDefault("goodsCode", request.getOrDefault("bizId", ""))));
        BaseGoods entity = goodsService.getOne(new QueryWrapper<BaseGoods>().eq("goods_id", bizId).or().eq("goods_code", bizId));
        if (entity == null) return ApiResponse.fail("404", "商品不存在");
        String originalCode = entity.getGoodsCode();
        fillGoodsEntity(entity, request);
        entity.setGoodsCode(originalCode);
        goodsService.updateById(entity);
        return ApiResponse.ok(null);
    }

    private void fillGoodsEntity(BaseGoods entity, Map<String, Object> request) {
        entity.setGoodsCode((String) request.getOrDefault("goodsCode", entity.getGoodsId()));
        entity.setGoodsName((String) request.getOrDefault("goodsName", "新商品"));
        entity.setGoodsType((String) request.getOrDefault("goodsType", "正常商品"));
        entity.setSpec((String) request.getOrDefault("spec", ""));
        entity.setCategoryName((String) request.getOrDefault("categoryName", ""));
        entity.setBrandName((String) request.getOrDefault("brandName", ""));
        entity.setBaseUnit((String) request.getOrDefault("baseUnit", ""));
        entity.setBarcode((String) request.getOrDefault("barcode", ""));
        entity.setStandardPrice(parseDecimal(request.get("standardPrice")));
        entity.setLatestPurchasePrice(parseDecimal(request.get("latestPurchasePrice")));
        entity.setMinSalePrice(parseDecimal(request.get("minSalePrice")));
        entity.setSuggestedRetailPrice(parseDecimal(request.get("suggestedRetailPrice")));
        entity.setWholesalePrice(parseDecimal(request.get("wholesalePrice")));
        entity.setMemberPrice(parseDecimal(request.get("memberPrice")));
        entity.setRetailPrice(parseDecimal(request.get("retailPrice")));
        entity.setStockUpperLimit(parseDecimal(request.get("stockUpperLimit")));
        entity.setStockLowerLimit(parseDecimal(request.get("stockLowerLimit")));
        entity.setShelfLifeDays(parseInt(request.get("shelfLifeDays")));
        entity.setStorageProperty((String) request.getOrDefault("storageProperty", "常温"));
        entity.setDefaultSupplier((String) request.getOrDefault("defaultSupplier", ""));
        entity.setDefaultWarehouse((String) request.getOrDefault("defaultWarehouse", ""));
        entity.setCanReturn(parseBoolean(request.get("canReturn"), true));
        // 扩展字段
        try { entity.setSimpleCode((String) request.getOrDefault("simpleCode", "")); } catch (Exception ignore) {}
        try { entity.setGoodsLevel((String) request.getOrDefault("goodsLevel", "")); } catch (Exception ignore) {}
        try { entity.setTaxRate((String) request.getOrDefault("taxRate", "")); } catch (Exception ignore) {}
        try { entity.setGoodsManager((String) request.getOrDefault("goodsManager", "")); } catch (Exception ignore) {}
        try { entity.setCanSale(parseBoolean(request.get("canSale"), true)); } catch (Exception ignore) {}
        try { entity.setCanPurchase(parseBoolean(request.get("canPurchase"), true)); } catch (Exception ignore) {}
        try { entity.setIsWeighted(parseBoolean(request.get("isWeighted"), false)); } catch (Exception ignore) {}
        try { entity.setIsPresale(parseBoolean(request.get("isPresale"), false)); } catch (Exception ignore) {}
        try { entity.setOrigin((String) request.getOrDefault("origin", "")); } catch (Exception ignore) {}
        try { entity.setWarningDays(parseInt(request.get("warningDays"))); } catch (Exception ignore) {}
        try { entity.setMinOrderQty(parseDecimal(request.get("minOrderQty"))); } catch (Exception ignore) {}
        try { entity.setPalletQty(parseInt(request.get("palletQty"))); } catch (Exception ignore) {}
        try { entity.setStackLayers(parseInt(request.get("stackLayers"))); } catch (Exception ignore) {}
        try { entity.setBaseWeight(parseDecimal(request.get("baseWeight"))); } catch (Exception ignore) {}
        try { entity.setBaseVolume(parseDecimal(request.get("baseVolume"))); } catch (Exception ignore) {}
        try { entity.setGoodsIntro((String) request.getOrDefault("goodsIntro", "")); } catch (Exception ignore) {}
        try { entity.setRemark((String) request.getOrDefault("remark", "")); } catch (Exception ignore) {}
        // 默认采购单位（单位名称）：设置后采购单据添加商品时优先取此单位
        try { entity.setDefaultPurchaseUnit((String) request.getOrDefault("defaultPurchaseUnit", "")); } catch (Exception ignore) {}
        // units 序列化为 JSON
        Object units = request.get("units");
        if (units != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                entity.setUnitConfig(mapper.writeValueAsString(units));
            } catch (Exception e) {
                // ignore
            }
        }
        String status = (String) request.getOrDefault("status", "NORMAL");
        if ("正常".equals(status)) status = "NORMAL";
        else if ("停用".equals(status)) status = "STOPPED";
        entity.setStatus(status);
    }

    private java.math.BigDecimal parseDecimal(Object val) {
        if (val == null) return java.math.BigDecimal.ZERO;
        try {
            String s = val.toString().replaceAll("[^0-9.\\-]", "");
            if (s.isEmpty()) return java.math.BigDecimal.ZERO;
            return new java.math.BigDecimal(s);
        } catch (Exception e) {
            return java.math.BigDecimal.ZERO;
        }
    }

    private Integer parseInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number n) return n.intValue();
        try {
            String s = val.toString().replaceAll("[^0-9\\-]", "");
            if (s.isEmpty()) return 0;
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private Boolean parseBoolean(Object val, Boolean fallback) {
        if (val == null) return fallback;
        if (val instanceof Boolean) return (Boolean) val;
        String s = val.toString().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "是".equals(s) || "y".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "否".equals(s) || "n".equals(s)) return false;
        return fallback;
    }

    @PostMapping("/goods/stop")
    public ApiResponse<Void> stopGoods(@RequestBody Map<String, Object> request) {
        return updateGoodsStatus(request, "STOPPED");
    }

    @PostMapping("/goods/freeze")
    public ApiResponse<Void> freezeGoods(@RequestBody Map<String, Object> request) {
        return updateGoodsStatus(request, "FROZEN");
    }

    @PostMapping("/goods/delete")
    public ApiResponse<Void> deleteGoods(@RequestBody Map<String, Object> request) {
        return updateGoodsStatus(request, "DELETED");
    }

    private ApiResponse<Void> updateGoodsStatus(Map<String, Object> request, String status) {
        String bizId = String.valueOf(request.getOrDefault("goodsId", request.getOrDefault("goodsCode", request.getOrDefault("bizId", ""))));
        BaseGoods entity = goodsService.getOne(new QueryWrapper<BaseGoods>().eq("goods_id", bizId).or().eq("goods_code", bizId));
        if (entity == null) return ApiResponse.fail("404", "商品不存在");
        entity.setStatus(status);
        goodsService.updateById(entity);
        return ApiResponse.ok(null);
    }

    // ========== 客户资料 ==========
    @PostMapping("/customer/page")
    public ApiResponse<PageResult<Map<String, Object>>> customerPage(@RequestBody PageRequest request) {
        QueryWrapper<BaseCustomer> qw = new QueryWrapper<>();
        String keyword = request.keyword();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("customer_code", keyword).or().like("customer_name", keyword).or().like("mobile", keyword));
        }
        qw.orderByDesc("customer_code");
        IPage<BaseCustomer> page = customerService.page(toMpPage(request), qw);
        // 一次拉全量价格组 code→name 索引，避免 N+1
        java.util.Map<String, String> priceGroupName = new java.util.HashMap<>();
        try {
            jdbcTemplate.queryForList("SELECT price_group_code, price_group_name FROM base_price_group")
                    .forEach(r -> {
                        Object c = r.get("price_group_code"); if (c == null) c = r.get("PRICE_GROUP_CODE");
                        Object n = r.get("price_group_name"); if (n == null) n = r.get("PRICE_GROUP_NAME");
                        if (c != null && n != null) priceGroupName.put(String.valueOf(c), String.valueOf(n));
                    });
        } catch (Exception e) { /* 忽略 */ }
        // 转 Map + 附加派生字段
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.List<Map<String, Object>> mapped = new java.util.ArrayList<>();
        for (BaseCustomer c : page.getRecords()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = mapper.convertValue(c, Map.class);
            row.put("settlementText", settlementSummary(c.getSettlementType(), c.getTermType(),
                    c.getTermDays(), c.getCutoffDay(), c.getPaymentMode(), c.getTermMonths(), c.getPaymentDay()));
            // 价格组名称（列表展示更友好；下拉编辑仍用 priceGroupCode）
            String pgCode = c.getPriceGroupCode();
            row.put("priceGroupName", pgCode == null || pgCode.isBlank() ? "" : priceGroupName.getOrDefault(pgCode, pgCode));
            mapped.add(row);
        }
        PageResult<Map<String, Object>> result = new PageResult<>(mapped, (int) page.getCurrent(),
                (int) page.getSize(), page.getTotal(), Map.of());
        return ApiResponse.ok(result);
    }

    /** 按账期管理规范生成结算方式摘要文案（列表展示用）。 */
    private static String settlementSummary(String settlementType, String termType, Integer termDays,
                                            String cutoffDay, String paymentMode, Integer termMonths, String paymentDay) {
        if (settlementType == null || settlementType.isBlank()) return "";
        if ("PREPAY".equals(settlementType)) return "预付";
        if ("COD".equals(settlementType)) return "货到付款";
        if ("TERM".equals(settlementType)) {
            if (termType == null) return "账期";
            switch (termType) {
                case "FIXED": return "账期 · 固定 " + nvl(termDays, 0) + " 天";
                case "WEEKLY": return "账期 · 周结 " + nvl(termDays, 0) + " 天";
                case "SEMI_MONTH": return "账期 · 半月结 " + nvl(termDays, 0) + " 天";
                case "MONTHLY":
                    if ("B".equals(paymentMode)) {
                        return "账期 · 月结 截账 " + nvl(cutoffDay, "?") + " 日 + " + nvl(termMonths, 0)
                                + " 月第 " + nvl(paymentDay, "?") + " 日";
                    }
                    return "账期 · 月结 截账 " + nvl(cutoffDay, "?") + " 日 + " + nvl(termDays, 0) + " 天";
                default: return "账期";
            }
        }
        return settlementType;
    }

    private static Object nvl(Object v, Object fallback) {
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    @PostMapping("/customer/create")
    public ApiResponse<BaseCustomer> createCustomer(@RequestBody Map<String, Object> request) {
        String validationError = validateCustomerPriceGroup(request);
        if (validationError != null) return ApiResponse.fail("400", validationError);
        BaseCustomer entity = new BaseCustomer();
        entity.setCustomerId(genId("C"));
        fillCustomerEntity(entity, request);
        entity.setStatus("NORMAL");
        customerService.save(entity);
        saveCustomerAddresses(entity.getCustomerCode(), request.get("addresses"));
        return ApiResponse.ok(entity);
    }

    @PostMapping("/customer/update")
    public ApiResponse<Void> updateCustomer(@RequestBody Map<String, Object> request) {
        String validationError = validateCustomerPriceGroup(request);
        if (validationError != null) return ApiResponse.fail("400", validationError);
        String code = (String) request.get("customerCode");
        BaseCustomer entity = customerService.getOne(new QueryWrapper<BaseCustomer>().eq("customer_code", code));
        if (entity == null) return ApiResponse.fail("404", "客户不存在");
        String originalCode = entity.getCustomerCode();
        fillCustomerEntity(entity, request);
        entity.setCustomerCode(originalCode);
        if (request.get("status") != null) entity.setStatus((String) request.get("status"));
        customerService.updateById(entity);
        saveCustomerAddresses(originalCode, request.get("addresses"));
        return ApiResponse.ok(null);
    }

    /** 校验客户提交的价格组：非空则必须启用中且状态正常。返回 null=通过，否则返回错误信息。 */
    private String validateCustomerPriceGroup(Map<String, Object> request) {
        Object v = request.get("priceGroupCode");
        if (v == null) return null;
        String code = String.valueOf(v).trim();
        if (code.isEmpty()) return null; // 空 = 使用商品默认售价
        try {
            java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT enabled, status FROM base_price_group WHERE price_group_code = ?", code);
            if (rows.isEmpty()) return "价格组不存在：" + code;
            Map<String, Object> r = rows.get(0);
            Object en = r.get("enabled"); if (en == null) en = r.get("ENABLED");
            Object st = r.get("status"); if (st == null) st = r.get("STATUS");
            boolean enabled = en instanceof Boolean b ? b : (en instanceof Number n && n.intValue() > 0);
            String status = st == null ? "" : String.valueOf(st);
            if (!"NORMAL".equalsIgnoreCase(status)) return "价格组已停用，不允许引用：" + code;
            if (!enabled) return "价格组未启用，不允许引用：" + code;
        } catch (Exception e) {
            // 表不存在或其他异常，放行不阻塞主流程
        }
        return null;
    }

    /** 客户地址子表：全量替换保存。空 list = 保持不变（避免误清空历史）。 */
    @SuppressWarnings("unchecked")
    private void saveCustomerAddresses(String customerCode, Object addressesRaw) {
        if (customerCode == null || customerCode.isBlank()) return;
        if (!(addressesRaw instanceof java.util.List<?> list)) return;
        jdbcTemplate.update("DELETE FROM base_customer_address WHERE customer_code = ?", customerCode);
        int sort = 0;
        boolean hasDefault = false;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> row = (Map<String, Object>) raw;
            String detail = String.valueOf(row.getOrDefault("detailAddress", "")).trim();
            if (detail.isEmpty()) continue; // 空行跳过
            boolean isDefault = Boolean.TRUE.equals(row.get("isDefault")) && !hasDefault;
            if (isDefault) hasDefault = true;
            jdbcTemplate.update("""
                INSERT INTO base_customer_address
                (address_id, customer_code, address_name, contact_name, contact_mobile,
                 province, city, district, detail_address, longitude, latitude,
                 is_default, sort_order, remark, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NORMAL')
                """,
                genId("ADDR"), customerCode,
                str(row.get("addressName")), str(row.get("contactName")), str(row.get("contactMobile")),
                str(row.get("province")), str(row.get("city")), str(row.get("district")),
                detail,
                parseDecimalOrNull(row.get("longitude")), parseDecimalOrNull(row.get("latitude")),
                isDefault ? 1 : 0, ++sort, str(row.get("remark"))
            );
        }
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    private static java.math.BigDecimal parseDecimalOrNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return new java.math.BigDecimal(s); } catch (Exception e) { return null; }
    }

    /** 查询客户全部地址（含冗余的主地址，若子表为空则从主表回退） */
    @PostMapping("/customer/addresses")
    public ApiResponse<java.util.List<Map<String, Object>>> customerAddresses(@RequestBody Map<String, Object> request) {
        String code = String.valueOf(request.getOrDefault("customerCode", "")).trim();
        if (code.isEmpty()) return ApiResponse.ok(new java.util.ArrayList<>());
        java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT address_id, address_name, contact_name, contact_mobile, province, city, district, "
                        + "detail_address, longitude, latitude, is_default, sort_order, remark "
                        + "FROM base_customer_address WHERE customer_code = ? AND status = 'NORMAL' "
                        + "ORDER BY is_default DESC, sort_order ASC",
                code);
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> r : rows) out.add(camelize(r));
        return ApiResponse.ok(out);
    }

    private static Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey().toLowerCase(java.util.Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : k.toCharArray()) {
                if (c == '_') { upper = true; continue; }
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            out.put(sb.toString(), e.getValue());
        }
        return out;
    }

    private void fillCustomerEntity(BaseCustomer entity, Map<String, Object> request) {
        entity.setCustomerCode((String) request.getOrDefault("customerCode", entity.getCustomerId()));
        entity.setCustomerName((String) request.getOrDefault("customerName", "新客户"));
        if (request.containsKey("channelType")) entity.setChannelType((String) request.get("channelType"));
        if (request.containsKey("contactName")) entity.setContactName((String) request.get("contactName"));
        if (request.containsKey("mobile")) entity.setMobile((String) request.get("mobile"));
        if (request.containsKey("territory")) entity.setTerritory((String) request.get("territory"));
        if (request.containsKey("routeLine")) entity.setRouteLine((String) request.get("routeLine"));
        if (request.containsKey("salesman")) entity.setSalesman((String) request.get("salesman"));
        if (request.containsKey("customerLevel")) entity.setCustomerLevel((String) request.get("customerLevel"));
        if (request.containsKey("creditLimit")) entity.setCreditLimit(new java.math.BigDecimal(String.valueOf(request.getOrDefault("creditLimit", "0"))));
        if (request.containsKey("invoiceTitle")) entity.setInvoiceTitle((String) request.get("invoiceTitle"));
        if (request.containsKey("taxNo")) entity.setTaxNo((String) request.get("taxNo"));
        if (request.containsKey("shippingAddress")) entity.setShippingAddress((String) request.get("shippingAddress"));
        if (request.containsKey("priceGroupCode")) entity.setPriceGroupCode((String) request.get("priceGroupCode"));
        if (request.containsKey("longitude")) {
            Object v = request.get("longitude");
            if (v == null || String.valueOf(v).isBlank()) entity.setLongitude(null);
            else try { entity.setLongitude(new java.math.BigDecimal(String.valueOf(v))); } catch (Exception e) { entity.setLongitude(null); }
        }
        if (request.containsKey("latitude")) {
            Object v = request.get("latitude");
            if (v == null || String.valueOf(v).isBlank()) entity.setLatitude(null);
            else try { entity.setLatitude(new java.math.BigDecimal(String.valueOf(v))); } catch (Exception e) { entity.setLatitude(null); }
        }
        // 账期设置（按规范）
        if (request.containsKey("settlementType")) entity.setSettlementType((String) request.get("settlementType"));
        if (request.containsKey("termType")) entity.setTermType((String) request.get("termType"));
        if (request.containsKey("termDays")) entity.setTermDays(parseInt(request.get("termDays")));
        if (request.containsKey("cutoffDay")) entity.setCutoffDay(String.valueOf(request.get("cutoffDay")));
        if (request.containsKey("paymentDay")) entity.setPaymentDay(String.valueOf(request.get("paymentDay")));
        if (request.containsKey("paymentMode")) entity.setPaymentMode((String) request.get("paymentMode"));
        if (request.containsKey("termMonths")) entity.setTermMonths(parseInt(request.get("termMonths")));
    }

    // ========== 供应商资料 ==========
    @PostMapping("/supplier/page")
    public ApiResponse<PageResult<Map<String, Object>>> supplierPage(@RequestBody PageRequest request) {
        QueryWrapper<BaseSupplier> qw = new QueryWrapper<>();
        String keyword = request.keyword();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("supplier_code", keyword).or().like("supplier_name", keyword).or().like("contact_name", keyword));
        }
        qw.orderByDesc("supplier_code");
        IPage<BaseSupplier> page = supplierService.page(toMpPage(request), qw);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.List<Map<String, Object>> mapped = new java.util.ArrayList<>();
        for (BaseSupplier s : page.getRecords()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = mapper.convertValue(s, Map.class);
            row.put("settlementText", settlementSummary(s.getSettlementType(), s.getTermType(),
                    s.getTermDays(), s.getCutoffDay(), s.getPaymentMode(), s.getTermMonths(), s.getPaymentDay()));
            mapped.add(row);
        }
        PageResult<Map<String, Object>> result = new PageResult<>(mapped, (int) page.getCurrent(),
                (int) page.getSize(), page.getTotal(), Map.of());
        return ApiResponse.ok(result);
    }

    @PostMapping("/supplier/create")
    public ApiResponse<BaseSupplier> createSupplier(@RequestBody Map<String, Object> request) {
        BaseSupplier entity = new BaseSupplier();
        entity.setSupplierId(genId("S"));
        fillSupplierEntity(entity, request);
        supplierService.save(entity);
        saveSupplierBankAccounts(entity.getSupplierCode(), request.get("bankAccounts"));
        return ApiResponse.ok(entity);
    }

    @PostMapping("/supplier/update")
    public ApiResponse<Void> updateSupplier(@RequestBody Map<String, Object> request) {
        String code = (String) request.get("supplierCode");
        BaseSupplier entity = supplierService.getOne(new QueryWrapper<BaseSupplier>().eq("supplier_code", code));
        if (entity == null) return ApiResponse.fail("404", "供应商不存在");
        String originalCode = entity.getSupplierCode();
        fillSupplierEntity(entity, request);
        entity.setSupplierCode(originalCode);
        supplierService.updateById(entity);
        saveSupplierBankAccounts(originalCode, request.get("bankAccounts"));
        return ApiResponse.ok(null);
    }

    /** 全量替换供应商银行账户：先删后建，简单可靠。 */
    @SuppressWarnings("unchecked")
    private void saveSupplierBankAccounts(String supplierCode, Object bankAccountsRaw) {
        if (supplierCode == null || supplierCode.isBlank()) return;
        if (!(bankAccountsRaw instanceof java.util.List<?> list)) return;
        jdbcTemplate.update("DELETE FROM base_supplier_bank_account WHERE supplier_code = ?", supplierCode);
        int sort = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> row = (Map<String, Object>) raw;
            String accountName = String.valueOf(row.getOrDefault("accountName", "")).trim();
            String bankAccount = String.valueOf(row.getOrDefault("bankAccount", "")).trim();
            if (accountName.isEmpty() && bankAccount.isEmpty()) continue; // 空行跳过
            jdbcTemplate.update("""
                INSERT INTO base_supplier_bank_account
                (id, supplier_code, account_name, bank_name, bank_account, branch, is_default, remark, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                genId("BA"), supplierCode, accountName,
                String.valueOf(row.getOrDefault("bankName", "")),
                bankAccount,
                String.valueOf(row.getOrDefault("branch", "")),
                Boolean.TRUE.equals(row.get("isDefault")) ? 1 : 0,
                String.valueOf(row.getOrDefault("remark", "")),
                ++sort);
        }
    }

    private void fillSupplierEntity(BaseSupplier entity, Map<String, Object> request) {
        entity.setSupplierCode((String) request.getOrDefault("supplierCode", entity.getSupplierId()));
        entity.setSupplierName((String) request.getOrDefault("supplierName", "新供应商"));
        entity.setShortName((String) request.getOrDefault("shortName", ""));
        entity.setSupplierType((String) request.getOrDefault("supplierType", "普通供应商"));
        entity.setContactName((String) request.getOrDefault("contactName", ""));
        entity.setPhone((String) request.getOrDefault("phone", ""));
        entity.setDeliveryDays(parseInt(request.get("deliveryDays")));
        entity.setSettlementMethod((String) request.getOrDefault("settlementMethod", "现结"));
        entity.setAccountPeriodDays(parseInt(request.get("accountPeriodDays")));
        entity.setDefaultBuyer((String) request.getOrDefault("defaultBuyer", ""));
        entity.setDefaultReceiptAccount((String) request.getOrDefault("defaultReceiptAccount", ""));
        // 交货方式 / 默认物流公司（V6 新增）
        entity.setDeliveryMethod((String) request.getOrDefault("deliveryMethod", "送货上门"));
        entity.setDefaultLogisticsCompany((String) request.getOrDefault("defaultLogisticsCompany", ""));
        // 账期设置（按 docs/账期管理-产品说明.md，V9 新增）
        if (request.containsKey("settlementType")) entity.setSettlementType((String) request.get("settlementType"));
        if (request.containsKey("termType")) entity.setTermType((String) request.get("termType"));
        if (request.containsKey("termDays")) entity.setTermDays(parseInt(request.get("termDays")));
        if (request.containsKey("cutoffDay")) entity.setCutoffDay(String.valueOf(request.get("cutoffDay")));
        if (request.containsKey("paymentDay")) entity.setPaymentDay(String.valueOf(request.get("paymentDay")));
        if (request.containsKey("paymentMode")) entity.setPaymentMode((String) request.get("paymentMode"));
        if (request.containsKey("termMonths")) entity.setTermMonths(parseInt(request.get("termMonths")));
        // 税务字段 V1.0 后期不再前端展示，但字段保留兼容旧数据；只在传入时更新
        if (request.containsKey("invoiceTitle")) entity.setInvoiceTitle((String) request.get("invoiceTitle"));
        if (request.containsKey("taxNo")) entity.setTaxNo((String) request.get("taxNo"));
        entity.setAddress((String) request.getOrDefault("address", ""));
        entity.setRemark((String) request.getOrDefault("remark", ""));
        String status = (String) request.getOrDefault("status", "NORMAL");
        if ("正常".equals(status)) status = "NORMAL";
        else if ("停用".equals(status)) status = "STOPPED";
        entity.setStatus(status);
    }

    public record CategorySaveRequest(
            @NotBlank String parentId,
            @NotBlank String parentCode,
            @NotBlank @Pattern(regexp = "\\d{2}", message = "分类编号必须为两位数字") String categoryCode,
            @NotBlank String categoryName,
            String defaultTaxRate
    ) {
    }

    // ============================================================
    // 各基础资料的 delete / stop / update 端点
    // 前端配置里都写了 /{module}/delete、/{module}/stop 等路径，此处一次性补齐。
    // ============================================================

    private String pickBizKey(Map<String, Object> request, String... keys) {
        for (String k : keys) {
            Object v = request.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        }
        return "";
    }

    // ---------- category ----------
    @PostMapping("/category/delete")
    public ApiResponse<Map<String, Object>> deleteCategory(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "categoryCode", "categoryId", "bizId");
        boolean removed = categoryService.remove(new QueryWrapper<BaseCategory>().eq("category_code", biz).or().eq("category_id", biz));
        if (!removed) return ApiResponse.fail("404", "分类不存在或删除失败");
        return ApiResponse.ok(GenericResult.operation("category", "DELETE"));
    }

    @PostMapping("/category/stop")
    public ApiResponse<Void> stopCategory(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "categoryCode", "categoryId", "bizId");
        boolean updated = categoryService.update(new UpdateWrapper<BaseCategory>()
                .eq("category_code", biz).or().eq("category_id", biz)
                .set("status", "STOPPED"));
        if (!updated) return ApiResponse.fail("404", "分类不存在");
        return ApiResponse.ok(null);
    }

    // ---------- unit ----------
    @PostMapping("/unit/update")
    public ApiResponse<Void> updateUnit(@RequestBody Map<String, Object> request) {
        String code = pickBizKey(request, "unitCode", "unitId", "bizId");
        BaseUnit entity = unitService.getOne(new QueryWrapper<BaseUnit>().eq("unit_code", code).or().eq("unit_id", code));
        if (entity == null) return ApiResponse.fail("404", "单位不存在");
        if (request.get("unitName") != null) entity.setUnitName((String) request.get("unitName"));
        if (request.get("canMiddleUnit") != null) entity.setCanMiddleUnit(parseBoolean(request.get("canMiddleUnit"), entity.getCanMiddleUnit()));
        if (request.get("canLargeUnit") != null) entity.setCanLargeUnit(parseBoolean(request.get("canLargeUnit"), entity.getCanLargeUnit()));
        if (request.get("status") != null) entity.setStatus((String) request.get("status"));
        unitService.updateById(entity);
        return ApiResponse.ok(null);
    }

    @PostMapping("/unit/delete")
    public ApiResponse<Map<String, Object>> deleteUnit(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "unitCode", "unitId", "bizId");
        boolean removed = unitService.remove(new QueryWrapper<BaseUnit>().eq("unit_code", biz).or().eq("unit_id", biz));
        if (!removed) return ApiResponse.fail("404", "单位不存在或删除失败");
        return ApiResponse.ok(GenericResult.operation("unit", "DELETE"));
    }

    @PostMapping("/unit/stop")
    public ApiResponse<Void> stopUnit(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "unitCode", "unitId", "bizId");
        boolean updated = unitService.update(new UpdateWrapper<BaseUnit>()
                .eq("unit_code", biz).or().eq("unit_id", biz).set("status", "STOPPED"));
        if (!updated) return ApiResponse.fail("404", "单位不存在");
        return ApiResponse.ok(null);
    }

    // ---------- brand ----------
    @PostMapping("/brand/update")
    public ApiResponse<Void> updateBrand(@RequestBody Map<String, Object> request) {
        String code = pickBizKey(request, "brandCode", "brandId", "bizId");
        BaseBrand entity = brandService.getOne(new QueryWrapper<BaseBrand>().eq("brand_code", code).or().eq("brand_id", code));
        if (entity == null) return ApiResponse.fail("404", "品牌不存在");
        if (request.get("brandName") != null) entity.setBrandName((String) request.get("brandName"));
        if (request.get("simpleCode") != null) entity.setSimpleCode((String) request.get("simpleCode"));
        if (request.get("status") != null) entity.setStatus((String) request.get("status"));
        brandService.updateById(entity);
        return ApiResponse.ok(null);
    }

    @PostMapping("/brand/delete")
    public ApiResponse<Map<String, Object>> deleteBrand(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "brandCode", "brandId", "bizId");
        boolean removed = brandService.remove(new QueryWrapper<BaseBrand>().eq("brand_code", biz).or().eq("brand_id", biz));
        if (!removed) return ApiResponse.fail("404", "品牌不存在或删除失败");
        return ApiResponse.ok(GenericResult.operation("brand", "DELETE"));
    }

    @PostMapping("/brand/stop")
    public ApiResponse<Void> stopBrand(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "brandCode", "brandId", "bizId");
        boolean updated = brandService.update(new UpdateWrapper<BaseBrand>()
                .eq("brand_code", biz).or().eq("brand_id", biz).set("status", "STOPPED"));
        if (!updated) return ApiResponse.fail("404", "品牌不存在");
        return ApiResponse.ok(null);
    }

    // ---------- warehouse ----------
    @PostMapping("/warehouse/update")
    public ApiResponse<Void> updateWarehouse(@RequestBody Map<String, Object> request) {
        String code = pickBizKey(request, "warehouseCode", "warehouseId", "bizId");
        BaseWarehouse entity = warehouseService.getOne(new QueryWrapper<BaseWarehouse>().eq("warehouse_code", code).or().eq("warehouse_id", code));
        if (entity == null) return ApiResponse.fail("404", "仓库不存在");
        if (request.get("warehouseName") != null) entity.setWarehouseName((String) request.get("warehouseName"));
        if (request.get("warehouseType") != null) entity.setWarehouseType((String) request.get("warehouseType"));
        if (request.get("inventoryType") != null) entity.setInventoryType((String) request.get("inventoryType"));
        if (request.get("costGroup") != null) entity.setCostGroup((String) request.get("costGroup"));
        if (request.get("managerName") != null) entity.setManagerName((String) request.get("managerName"));
        if (request.get("status") != null) entity.setStatus((String) request.get("status"));
        warehouseService.updateById(entity);
        return ApiResponse.ok(null);
    }

    @PostMapping("/warehouse/delete")
    public ApiResponse<Map<String, Object>> deleteWarehouse(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "warehouseCode", "warehouseId", "bizId");
        boolean removed = warehouseService.remove(new QueryWrapper<BaseWarehouse>().eq("warehouse_code", biz).or().eq("warehouse_id", biz));
        if (!removed) return ApiResponse.fail("404", "仓库不存在或删除失败");
        return ApiResponse.ok(GenericResult.operation("warehouse", "DELETE"));
    }

    @PostMapping("/warehouse/stop")
    public ApiResponse<Void> stopWarehouse(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "warehouseCode", "warehouseId", "bizId");
        boolean updated = warehouseService.update(new UpdateWrapper<BaseWarehouse>()
                .eq("warehouse_code", biz).or().eq("warehouse_id", biz).set("status", "STOPPED"));
        if (!updated) return ApiResponse.fail("404", "仓库不存在");
        return ApiResponse.ok(null);
    }

    // ---------- customer ----------
    @PostMapping("/customer/delete")
    public ApiResponse<Map<String, Object>> deleteCustomer(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "customerCode", "customerId", "bizId");
        boolean removed = customerService.remove(new QueryWrapper<BaseCustomer>().eq("customer_code", biz).or().eq("customer_id", biz));
        if (!removed) return ApiResponse.fail("404", "客户不存在或删除失败");
        return ApiResponse.ok(GenericResult.operation("customer", "DELETE"));
    }

    @PostMapping("/customer/stop")
    public ApiResponse<Void> stopCustomer(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "customerCode", "customerId", "bizId");
        boolean updated = customerService.update(new UpdateWrapper<BaseCustomer>()
                .eq("customer_code", biz).or().eq("customer_id", biz).set("status", "STOPPED"));
        if (!updated) return ApiResponse.fail("404", "客户不存在");
        return ApiResponse.ok(null);
    }

    // ---------- supplier ----------
    @PostMapping("/supplier/delete")
    public ApiResponse<Map<String, Object>> deleteSupplier(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "supplierCode", "supplierId", "bizId");
        boolean removed = supplierService.remove(new QueryWrapper<BaseSupplier>().eq("supplier_code", biz).or().eq("supplier_id", biz));
        if (!removed) return ApiResponse.fail("404", "供应商不存在或删除失败");
        return ApiResponse.ok(GenericResult.operation("supplier", "DELETE"));
    }

    @PostMapping("/supplier/stop")
    public ApiResponse<Void> stopSupplier(@RequestBody Map<String, Object> request) {
        String biz = pickBizKey(request, "supplierCode", "supplierId", "bizId");
        boolean updated = supplierService.update(new UpdateWrapper<BaseSupplier>()
                .eq("supplier_code", biz).or().eq("supplier_id", biz).set("status", "STOPPED"));
        if (!updated) return ApiResponse.fail("404", "供应商不存在");
        return ApiResponse.ok(null);
    }

    // ============================================================
    // 采购订单新建页辅助接口
    // ============================================================

    /** 查询单个供应商详情（用于选择供应商后回填默认采购员等字段）。 */
    @GetMapping("/supplier/detail")
    public ApiResponse<Map<String, Object>> supplierDetail(@RequestParam String code) {
        BaseSupplier entity = supplierService.getOne(new QueryWrapper<BaseSupplier>()
                .eq("supplier_code", code).or().eq("supplier_id", code));
        if (entity == null) entity = supplierService.getOne(new QueryWrapper<BaseSupplier>().eq("supplier_name", code));
        if (entity == null) return ApiResponse.ok(null);
        // 转 Map + 附子表
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> result = mapper.convertValue(entity, Map.class);
        java.util.List<Map<String, Object>> banks;
        try {
            banks = jdbcTemplate.queryForList(
                    "SELECT id, supplier_code AS supplierCode, account_name AS accountName, bank_name AS bankName, "
                    + "bank_account AS bankAccount, branch, is_default AS isDefault, remark, sort_order AS sortOrder "
                    + "FROM base_supplier_bank_account WHERE supplier_code = ? ORDER BY sort_order ASC",
                    entity.getSupplierCode());
        } catch (Exception e) {
            banks = new java.util.ArrayList<>();
        }
        result.put("bankAccounts", banks);
        return ApiResponse.ok(result);
    }

    /** 字典查询：按 dict_type 返回启用中的条目，供前端下拉。 */
    @GetMapping("/dictionary")
    public ApiResponse<java.util.List<Map<String, Object>>> dictionaryList(@RequestParam String type) {
        try {
            java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT dict_code AS code, dict_name AS name, sort_order AS sortOrder "
                    + "FROM sys_dictionary WHERE dict_type = ? AND status = 'NORMAL' ORDER BY sort_order ASC, dict_code ASC",
                    type);
            return ApiResponse.ok(rows);
        } catch (Exception e) {
            return ApiResponse.ok(new java.util.ArrayList<>());
        }
    }

    /**
     * 采购员下拉：所有 status=NORMAL 且 is_buyer=true 的人员姓名。
     * 兼容 base_employee 未升级到含 is_buyer 列的场景（返回空列表）。
     */
    @PostMapping("/employee/buyers")
    public ApiResponse<java.util.List<Map<String, Object>>> buyers() {
        return employeeRoleList("is_buyer");
    }

    /** 业务员下拉：所有 status=NORMAL 且 is_salesman=true 的人员姓名。 */
    @PostMapping("/employee/salesmen")
    public ApiResponse<java.util.List<Map<String, Object>>> salesmen() {
        return employeeRoleList("is_salesman");
    }

    private ApiResponse<java.util.List<Map<String, Object>>> employeeRoleList(String roleColumn) {
        try {
            java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT employee_code, employee_name FROM base_employee WHERE status = 'NORMAL' AND " + roleColumn + " = TRUE ORDER BY employee_code ASC");
            java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (Map<String, Object> r : rows) {
                Object code = r.get("employee_code");
                if (code == null) code = r.get("EMPLOYEE_CODE");
                Object name = r.get("employee_name");
                if (name == null) name = r.get("EMPLOYEE_NAME");
                java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("code", code == null ? "" : String.valueOf(code));
                item.put("name", name == null ? "" : String.valueOf(name));
                out.add(item);
            }
            return ApiResponse.ok(out);
        } catch (Exception e) {
            return ApiResponse.ok(new java.util.ArrayList<>());
        }
    }

    /**
     * 商品最近采购价：V1.0 无采购单表，两个字段都兜底 base_goods.latest_purchase_price。
     * 引入 purchase_order_detail 后再按 supplier_code 查真实最近价。
     */
    @GetMapping("/goods/latest-purchase-price")
    public ApiResponse<Map<String, Object>> latestPurchasePrice(
            @RequestParam String goodsCode,
            @RequestParam(required = false) String supplierCode) {
        BaseGoods g = goodsService.getOne(new QueryWrapper<BaseGoods>()
                .eq("goods_code", goodsCode).or().eq("goods_id", goodsCode));
        java.math.BigDecimal p = (g != null && g.getLatestPurchasePrice() != null && g.getLatestPurchasePrice().signum() > 0)
                ? g.getLatestPurchasePrice() : java.math.BigDecimal.ZERO;
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("goodsCode", goodsCode);
        out.put("supplierCode", supplierCode);
        out.put("supplierLatestPrice", p);
        out.put("systemLatestPrice", p);
        return ApiResponse.ok(out);
    }

    /**
     * 商品最近售价：
     * - customerLatestPrice：该客户最近一次销售单价（剔除 0）；无历史返回 0
     * - systemLatestPrice：系统全局最近一次销售单价（剔除 0）；无历史回退到商品建议零售价 / 标准售价
     */
    @GetMapping("/goods/latest-sales-price")
    public ApiResponse<Map<String, Object>> latestSalesPrice(
            @RequestParam String goodsCode,
            @RequestParam(required = false) String customerCode) {
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("goodsCode", goodsCode);
        out.put("customerCode", customerCode);

        java.math.BigDecimal customerLatest = java.math.BigDecimal.ZERO;
        java.math.BigDecimal systemLatest = java.math.BigDecimal.ZERO;

        // 系统全局最近售价（订单明细 join 主表按创建时间倒序，剔除 0/负数）
        try {
            java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT d.price FROM sales_order_detail d "
                            + "JOIN sales_order o ON o.order_id = d.order_id "
                            + "WHERE d.goods_code = ? AND d.price > 0 "
                            + "ORDER BY o.create_time DESC LIMIT 1",
                    goodsCode);
            if (!rows.isEmpty()) {
                Object v = rows.get(0).get("price"); if (v == null) v = rows.get(0).get("PRICE");
                if (v != null) systemLatest = new java.math.BigDecimal(String.valueOf(v));
            }
        } catch (Exception e) { /* 表不存在 / 无数据 → 走兜底 */ }

        // 客户最近售价（同客户）
        if (customerCode != null && !customerCode.isBlank()) {
            try {
                java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT d.price FROM sales_order_detail d "
                                + "JOIN sales_order o ON o.order_id = d.order_id "
                                + "WHERE d.goods_code = ? AND o.customer_code = ? AND d.price > 0 "
                                + "ORDER BY o.create_time DESC LIMIT 1",
                        goodsCode, customerCode);
                if (!rows.isEmpty()) {
                    Object v = rows.get(0).get("price"); if (v == null) v = rows.get(0).get("PRICE");
                    if (v != null) customerLatest = new java.math.BigDecimal(String.valueOf(v));
                }
            } catch (Exception e) { /* 忽略 */ }
        }

        // 系统最近售价兜底：商品建议零售价 / 标准售价
        if (systemLatest.signum() == 0) {
            BaseGoods g = goodsService.getOne(new QueryWrapper<BaseGoods>()
                    .eq("goods_code", goodsCode).or().eq("goods_id", goodsCode));
            if (g != null) {
                if (g.getSuggestedRetailPrice() != null && g.getSuggestedRetailPrice().signum() > 0) {
                    systemLatest = g.getSuggestedRetailPrice();
                } else if (g.getStandardPrice() != null && g.getStandardPrice().signum() > 0) {
                    systemLatest = g.getStandardPrice();
                }
            }
        }

        out.put("customerLatestPrice", customerLatest);
        out.put("systemLatestPrice", systemLatest);
        return ApiResponse.ok(out);
    }

    /**
     * 销售取价 —— 按优先级返回某商品某单位对该客户应取的销售单价。
     *
     * <p><b>优先级</b>（命中即止，价格必须 &gt; 0 才算命中）：
     * <ol>
     *   <li><b>客户商品价格</b> —— base_customer_price_item，客户专属价</li>
     *   <li><b>价格组价格</b> —— 客户档案 price_group_code 关联的 base_price_group_item</li>
     *   <li><b>商品标准价</b> —— base_goods.unit_config 中该单位的 standardPrice</li>
     * </ol>
     *
     * <p><b>按单位取价</b>：unitLevel 1-小 2-中 3-大，三级价格表均按此维度存储，不串位。
     *
     * <p><b>停用即跳过</b>：is_active=false 的价格视为不存在，继续降级取下一级；
     * 与「客户价格查询」只展示生效价的口径一致。
     *
     * @param goodsCode    商品编码
     * @param customerCode 客户编码；为空时跳过前两级，直接取商品标价
     * @param unitLevel    单位级别 1/2/3，缺省 1（小单位）
     */
    @GetMapping("/goods/sale-price")
    public ApiResponse<Map<String, Object>> salePrice(
            @RequestParam String goodsCode,
            @RequestParam(required = false) String customerCode,
            @RequestParam(required = false, defaultValue = "1") Integer unitLevel) {

        int level = (unitLevel == null || unitLevel < 1 || unitLevel > 3) ? 1 : unitLevel;
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("goodsCode", goodsCode);
        out.put("customerCode", customerCode);
        out.put("unitLevel", level);

        // ① 客户专属价（来源单必须已审核，与客户价格查询口径一致）
        java.math.BigDecimal customerPrice = queryCustomerUnitPrice(goodsCode, customerCode, level);
        out.put("customerPrice", customerPrice);

        // ② 价格组价（客户未关联价格组、或价格组本身停用时为 null）
        String priceGroupCode = lookupCustomerPriceGroup(customerCode);
        out.put("priceGroupCode", priceGroupCode);
        java.math.BigDecimal groupPrice = queryPriceGroupUnitPrice(goodsCode, priceGroupCode, level);
        out.put("priceGroupPrice", groupPrice);

        // ③ 商品标价
        java.math.BigDecimal standardPrice = queryGoodsUnitStandardPrice(goodsCode, level);
        out.put("standardPrice", standardPrice);

        // 命中判定：> 0 才算设了价（0 视为未设价，与价格组模块既有口径一致）
        java.math.BigDecimal finalPrice;
        String source;
        String sourceText;
        if (isPositive(customerPrice)) {
            finalPrice = customerPrice; source = "CUSTOMER"; sourceText = "客户专属价";
        } else if (isPositive(groupPrice)) {
            finalPrice = groupPrice; source = "PRICE_GROUP";
            sourceText = "价格组" + (priceGroupCode == null ? "" : " " + priceGroupCode);
        } else if (isPositive(standardPrice)) {
            finalPrice = standardPrice; source = "GOODS_STANDARD"; sourceText = "商品标价";
        } else {
            finalPrice = java.math.BigDecimal.ZERO; source = "NONE"; sourceText = "未设价";
        }
        out.put("price", finalPrice);
        out.put("priceSource", source);
        out.put("priceSourceText", sourceText);
        return ApiResponse.ok(out);
    }

    private static boolean isPositive(java.math.BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    /** ① 客户专属价：唯一键保证最多一条；要求生效中且来源调整单已审核 */
    private java.math.BigDecimal queryCustomerUnitPrice(String goodsCode, String customerCode, int level) {
        if (customerCode == null || customerCode.isBlank()) return null;
        try {
            java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT i.price FROM base_customer_price_item i
                    WHERE i.customer_code = ? AND i.goods_code = ? AND i.unit_level = ?
                      AND i.is_active = TRUE
                      AND EXISTS (SELECT 1 FROM base_customer_price_adjust h
                                  WHERE h.adjust_no = i.adjust_no AND h.status = 'APPROVED')
                    LIMIT 1
                    """, customerCode, goodsCode, level);
            return firstDecimal(rows, "price");
        } catch (Exception e) {
            return null;   // 表缺失时不阻断开单，降级取下一级
        }
    }

    /** 取客户档案关联的价格组编码；价格组本身被停用则返回 null（视为未关联） */
    private String lookupCustomerPriceGroup(String customerCode) {
        if (customerCode == null || customerCode.isBlank()) return null;
        try {
            java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT c.price_group_code FROM base_customer c
                    JOIN base_price_group g ON g.price_group_code = c.price_group_code
                    WHERE (c.customer_code = ? OR c.customer_id = ?)
                      AND g.enabled = TRUE AND g.status = 'NORMAL'
                    LIMIT 1
                    """, customerCode, customerCode);
            if (rows.isEmpty()) return null;
            Object v = rows.get(0).get("price_group_code");
            if (v == null) v = rows.get(0).get("PRICE_GROUP_CODE");
            String code = v == null ? "" : String.valueOf(v).trim();
            return code.isEmpty() ? null : code;
        } catch (Exception e) {
            return null;
        }
    }

    /** ② 价格组价：要求生效中 */
    private java.math.BigDecimal queryPriceGroupUnitPrice(String goodsCode, String priceGroupCode, int level) {
        if (priceGroupCode == null || priceGroupCode.isBlank()) return null;
        try {
            java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT price FROM base_price_group_item
                    WHERE price_group_code = ? AND goods_code = ? AND unit_level = ?
                      AND is_active = TRUE
                    LIMIT 1
                    """, priceGroupCode, goodsCode, level);
            return firstDecimal(rows, "price");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ③ 商品标价：从 unit_config JSON 取对应单位的 standardPrice。
     * 口径与 PriceGroupExtController.extractUnitStandardPrice 一致 ——
     * 索引 = level-1；小单位（level 1）在缺失或为 0 时兜底 base_goods.standard_price。
     */
    private java.math.BigDecimal queryGoodsUnitStandardPrice(String goodsCode, int level) {
        BaseGoods g = goodsService.getOne(new QueryWrapper<BaseGoods>()
                .eq("goods_code", goodsCode).or().eq("goods_id", goodsCode));
        if (g == null) return null;
        java.math.BigDecimal fallback = g.getStandardPrice();

        java.math.BigDecimal fromConfig = null;
        String raw = g.getUnitConfig();
        if (raw != null && raw.trim().startsWith("[")) {
            try {
                java.util.List<Map<String, Object>> units =
                        new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, java.util.List.class);
                int idx = level - 1;
                if (idx >= 0 && idx < units.size()) {
                    Map<String, Object> u = units.get(idx);
                    // 该单位被停用时不取它的价（停用单位的 standardPrice 常是占位 0）
                    Object en = u.get("enabled");
                    boolean enabled = idx == 0 || en == null || Boolean.TRUE.equals(en) || "true".equals(String.valueOf(en));
                    if (enabled) {
                        Object p = u.get("standardPrice");
                        if (p != null) {
                            try { fromConfig = new java.math.BigDecimal(String.valueOf(p)); }
                            catch (NumberFormatException ignore) { /* 保持 null */ }
                        }
                    }
                }
            } catch (Exception ignore) { /* 解析失败走兜底 */ }
        }
        if (isPositive(fromConfig)) return fromConfig;
        // 仅小单位可兜底商品档案基本单位标价；中/大单位没配就是没配
        return level == 1 ? fallback : fromConfig;
    }

    private static java.math.BigDecimal firstDecimal(java.util.List<Map<String, Object>> rows, String key) {
        if (rows.isEmpty()) return null;
        Object v = rows.get(0).get(key);
        if (v == null) v = rows.get(0).get(key.toUpperCase(java.util.Locale.ROOT));
        if (v == null) return null;
        if (v instanceof java.math.BigDecimal b) return b;
        try { return new java.math.BigDecimal(String.valueOf(v)); }
        catch (NumberFormatException e) { return null; }
    }

    /** 单个商品的库存汇总。V1.0 无锁定库存表，可用库存 = 当前库存。 */
    @GetMapping("/goods/stock-summary")
    public ApiResponse<Map<String, Object>> stockSummary(@RequestParam String goodsCode) {
        // 走 inv_stock_balance 聚合真实库存（跨仓库合计），而不是 base_goods.current_stock（历史字段，不再维护）
        java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT COALESCE(SUM(physical_qty), 0) AS physical_qty,
                       COALESCE(SUM(available_qty), 0) AS available_qty
                FROM inv_stock_balance WHERE goods_code = ?
                """, goodsCode);
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        Object phy = row.get("physical_qty");
        if (phy == null) phy = row.get("PHYSICAL_QTY");
        Object avail = row.get("available_qty");
        if (avail == null) avail = row.get("AVAILABLE_QTY");
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("goodsCode", goodsCode);
        out.put("currentStock", phy != null ? phy : java.math.BigDecimal.ZERO);
        out.put("availableStock", avail != null ? avail : java.math.BigDecimal.ZERO);
        return ApiResponse.ok(out);
    }

    // ============================================================
    // 通用导入接口（category / brand / unit / warehouse）
    // body: { rows: [ {...camelCase 字段...} ] }
    // 返回：{ inserted, skipped }
    // ============================================================

    @PostMapping("/category/import")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> importCategory(@RequestBody Map<String, Object> request) {
        Object rowsObj = request.get("rows");
        if (!(rowsObj instanceof java.util.List<?> rows)) return ApiResponse.fail("400", "缺少 rows");
        int inserted = 0, skipped = 0;
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> m)) { skipped++; continue; }
            Map<String, Object> r = (Map<String, Object>) m;
            String name = str(r.get("categoryName"));
            if (name.isEmpty()) { skipped++; continue; }
            String code = str(r.get("categoryCode"));
            if (code.isEmpty()) code = genId("CATE");
            if (categoryService.getOne(new QueryWrapper<BaseCategory>().eq("category_code", code)) != null) { skipped++; continue; }
            BaseCategory e = new BaseCategory();
            e.setCategoryId(genId("CATE"));
            e.setCategoryCode(code);
            e.setCategoryName(name);
            e.setParentCode(str(r.get("parentCode")));
            String rate = str(r.get("defaultTaxRate"));
            e.setDefaultTaxRate(rate.isEmpty() ? null : (rate.contains("%") ? rate : rate + "%"));
            e.setExternalCode(str(r.get("externalCode")));
            e.setGoodsCount(0);
            e.setStatus("NORMAL");
            categoryService.save(e);
            inserted++;
        }
        return ApiResponse.ok(java.util.Map.of("inserted", inserted, "skipped", skipped));
    }

    @PostMapping("/brand/import")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> importBrand(@RequestBody Map<String, Object> request) {
        Object rowsObj = request.get("rows");
        if (!(rowsObj instanceof java.util.List<?> rows)) return ApiResponse.fail("400", "缺少 rows");
        int inserted = 0, skipped = 0;
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> m)) { skipped++; continue; }
            Map<String, Object> r = (Map<String, Object>) m;
            String name = str(r.get("brandName"));
            if (name.isEmpty()) { skipped++; continue; }
            String code = str(r.get("brandCode"));
            if (code.isEmpty()) code = genId("BR");
            if (brandService.getOne(new QueryWrapper<BaseBrand>().eq("brand_code", code)) != null) { skipped++; continue; }
            BaseBrand e = new BaseBrand();
            e.setBrandId(genId("BR"));
            e.setBrandCode(code);
            e.setBrandName(name);
            e.setSimpleCode(str(r.get("simpleCode")));
            e.setStatus("NORMAL");
            brandService.save(e);
            inserted++;
        }
        return ApiResponse.ok(java.util.Map.of("inserted", inserted, "skipped", skipped));
    }

    @PostMapping("/unit/import")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> importUnit(@RequestBody Map<String, Object> request) {
        Object rowsObj = request.get("rows");
        if (!(rowsObj instanceof java.util.List<?> rows)) return ApiResponse.fail("400", "缺少 rows");
        int inserted = 0, skipped = 0;
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> m)) { skipped++; continue; }
            Map<String, Object> r = (Map<String, Object>) m;
            String name = str(r.get("unitName"));
            if (name.isEmpty()) { skipped++; continue; }
            String code = str(r.get("unitCode"));
            if (code.isEmpty()) code = genId("UNIT");
            if (unitService.getOne(new QueryWrapper<BaseUnit>().eq("unit_code", code)) != null) { skipped++; continue; }
            BaseUnit e = new BaseUnit();
            e.setUnitId(genId("UNIT"));
            e.setUnitCode(code);
            e.setUnitName(name);
            e.setCanBaseUnit(true);
            e.setCanMiddleUnit(parseBoolean(r.get("canMiddleUnit"), false));
            e.setCanLargeUnit(parseBoolean(r.get("canLargeUnit"), false));
            e.setGoodsCount(0);
            e.setStatus("NORMAL");
            unitService.save(e);
            inserted++;
        }
        return ApiResponse.ok(java.util.Map.of("inserted", inserted, "skipped", skipped));
    }

    @PostMapping("/warehouse/import")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> importWarehouse(@RequestBody Map<String, Object> request) {
        Object rowsObj = request.get("rows");
        if (!(rowsObj instanceof java.util.List<?> rows)) return ApiResponse.fail("400", "缺少 rows");
        int inserted = 0, skipped = 0;
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> m)) { skipped++; continue; }
            Map<String, Object> r = (Map<String, Object>) m;
            String name = str(r.get("warehouseName"));
            if (name.isEmpty()) { skipped++; continue; }
            String code = str(r.get("warehouseCode"));
            if (code.isEmpty()) code = genId("WH");
            if (warehouseService.getOne(new QueryWrapper<BaseWarehouse>().eq("warehouse_code", code)) != null) { skipped++; continue; }
            BaseWarehouse e = new BaseWarehouse();
            e.setWarehouseId(genId("WH"));
            e.setWarehouseCode(code);
            e.setWarehouseName(name);
            e.setWarehouseType(nvl(str(r.get("warehouseType")), "实物仓"));
            e.setInventoryType(nvl(str(r.get("inventoryType")), "平台仓库"));
            e.setCostGroup(str(r.get("costGroup")));
            e.setManagerName(str(r.get("managerName")));
            e.setStatus("NORMAL");
            warehouseService.save(e);
            inserted++;
        }
        return ApiResponse.ok(java.util.Map.of("inserted", inserted, "skipped", skipped));
    }

    private static String nvl(String v, String def) { return v == null || v.isBlank() ? def : v; }
}
