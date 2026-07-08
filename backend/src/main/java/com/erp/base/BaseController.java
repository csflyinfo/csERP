package com.erp.base;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.base.entity.*;
import com.erp.base.service.*;
import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public BaseController(BaseCategoryService categoryService,
                          BaseUnitService unitService,
                          BaseBrandService brandService,
                          BaseWarehouseService warehouseService,
                          BaseGoodsService goodsService,
                          BaseCustomerService customerService,
                          BaseSupplierService supplierService) {
        this.categoryService = categoryService;
        this.unitService = unitService;
        this.brandService = brandService;
        this.warehouseService = warehouseService;
        this.goodsService = goodsService;
        this.customerService = customerService;
        this.supplierService = supplierService;
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
    public ApiResponse<PageResult<BaseCategory>> categoryPage(@RequestBody PageRequest request) {
        QueryWrapper<BaseCategory> qw = new QueryWrapper<>();
        qw.orderByAsc("category_code");
        IPage<BaseCategory> page = categoryService.page(toMpPage(request), qw);
        return ApiResponse.ok(toPageResult(page));
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
        entity.setDefaultTaxRate(taxRate == null ? "13%" : taxRate.toString());
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
        if (request.get("defaultTaxRate") != null) entity.setDefaultTaxRate((String) request.get("defaultTaxRate"));
        categoryService.updateById(entity);
        return ApiResponse.ok(null);
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
        entity.setCanBaseUnit(true);
        entity.setCanMiddleUnit(false);
        entity.setCanLargeUnit(false);
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
            qw.and(w -> w.like("goods_code", keyword).or().like("goods_name", keyword).or().like("barcode", keyword));
        }
        qw.orderByDesc("goods_code");
        IPage<BaseGoods> page = goodsService.page(toMpPage(request), qw);
        return ApiResponse.ok(toPageResult(page));
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
    public ApiResponse<PageResult<BaseCustomer>> customerPage(@RequestBody PageRequest request) {
        QueryWrapper<BaseCustomer> qw = new QueryWrapper<>();
        String keyword = request.keyword();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("customer_code", keyword).or().like("customer_name", keyword).or().like("mobile", keyword));
        }
        qw.orderByDesc("customer_code");
        IPage<BaseCustomer> page = customerService.page(toMpPage(request), qw);
        return ApiResponse.ok(toPageResult(page));
    }

    @PostMapping("/customer/create")
    public ApiResponse<BaseCustomer> createCustomer(@RequestBody Map<String, Object> request) {
        BaseCustomer entity = new BaseCustomer();
        entity.setCustomerId(genId("C"));
        entity.setCustomerCode((String) request.getOrDefault("customerCode", entity.getCustomerId()));
        entity.setCustomerName((String) request.getOrDefault("customerName", "新客户"));
        entity.setStatus("NORMAL");
        customerService.save(entity);
        return ApiResponse.ok(entity);
    }

    @PostMapping("/customer/update")
    public ApiResponse<Void> updateCustomer(@RequestBody Map<String, Object> request) {
        String code = (String) request.get("customerCode");
        BaseCustomer entity = customerService.getOne(new QueryWrapper<BaseCustomer>().eq("customer_code", code));
        if (entity == null) return ApiResponse.fail("404", "客户不存在");
        if (request.get("customerName") != null) entity.setCustomerName((String) request.get("customerName"));
        if (request.get("status") != null) entity.setStatus((String) request.get("status"));
        customerService.updateById(entity);
        return ApiResponse.ok(null);
    }

    // ========== 供应商资料 ==========
    @PostMapping("/supplier/page")
    public ApiResponse<PageResult<BaseSupplier>> supplierPage(@RequestBody PageRequest request) {
        QueryWrapper<BaseSupplier> qw = new QueryWrapper<>();
        String keyword = request.keyword();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("supplier_code", keyword).or().like("supplier_name", keyword).or().like("contact_name", keyword));
        }
        qw.orderByDesc("supplier_code");
        IPage<BaseSupplier> page = supplierService.page(toMpPage(request), qw);
        return ApiResponse.ok(toPageResult(page));
    }

    @PostMapping("/supplier/create")
    public ApiResponse<BaseSupplier> createSupplier(@RequestBody Map<String, Object> request) {
        BaseSupplier entity = new BaseSupplier();
        entity.setSupplierId(genId("S"));
        fillSupplierEntity(entity, request);
        supplierService.save(entity);
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
        return ApiResponse.ok(null);
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
        entity.setInvoiceTitle((String) request.getOrDefault("invoiceTitle", ""));
        entity.setTaxNo((String) request.getOrDefault("taxNo", ""));
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
}
