package com.erp.base;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/base/goods")
public class GoodsController {
    private final JdbcTemplate jdbcTemplate;

    public GoodsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(loadGoods(), request));
    }

    @PostMapping("/selector")
    public ApiResponse<List<Map<String, Object>>> selector(@RequestBody PageRequest request) {
        return ApiResponse.ok(loadGoods());
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        String id = "G" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO base_goods(goods_id, goods_code, goods_name, spec, category_name, brand_name, base_unit, barcode,
                                       standard_price, latest_purchase_price, min_sale_price, goods_type, shelf_life_days, storage_property,
                                       suggested_retail_price, stock_upper_limit, stock_lower_limit, default_supplier, default_warehouse, can_return, current_stock, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'NORMAL')
                """, id, request.getOrDefault("goodsCode", id), request.getOrDefault("goodsName", "新商品"),
                request.getOrDefault("spec", ""), request.getOrDefault("categoryName", "默认分类"), request.getOrDefault("brandName", ""),
                request.getOrDefault("baseUnit", "瓶"), request.getOrDefault("barcode", ""),
                request.getOrDefault("standardPrice", 0), request.getOrDefault("latestPurchasePrice", 0), request.getOrDefault("minSalePrice", 0),
                request.getOrDefault("goodsType", "正常商品"), request.getOrDefault("shelfLifeDays", 0), request.getOrDefault("storageProperty", "常温"),
                request.getOrDefault("suggestedRetailPrice", 0), request.getOrDefault("stockUpperLimit", 0), request.getOrDefault("stockLowerLimit", 0),
                request.getOrDefault("defaultSupplier", ""), request.getOrDefault("defaultWarehouse", ""), request.getOrDefault("canReturn", true));
        return ApiResponse.ok(GenericResult.row("goodsId", id, "status", "NORMAL"));
    }

    private List<Map<String, Object>> loadGoods() {
        return jdbcTemplate.queryForList("""
                SELECT goods_code goodsCode,
                       goods_name goodsName,
                       spec,
                       category_name category,
                       brand_name brandName,
                       base_unit baseUnit,
                       barcode,
                       standard_price standardPrice,
                       latest_purchase_price latestPurchasePrice,
                       latest_purchase_price referencePurchasePrice,
                       min_sale_price minSalePrice,
                       goods_type goodsType,
                       shelf_life_days shelfLifeDays,
                       storage_property storageProperty,
                       suggested_retail_price suggestedRetailPrice,
                       stock_upper_limit stockUpperLimit,
                       stock_lower_limit stockLowerLimit,
                       default_supplier defaultSupplier,
                       default_warehouse defaultWarehouse,
                       can_return canReturn,
                       CASE can_return WHEN TRUE THEN '是/是/是' ELSE '是/是/否' END salePurchaseReturnFlag,
                       current_stock physicalQty,
                       current_stock currentStock,
                       CASE status WHEN 'NORMAL' THEN '正常' ELSE '停用' END status
                FROM base_goods
                ORDER BY goods_code
                """);
    }
}
