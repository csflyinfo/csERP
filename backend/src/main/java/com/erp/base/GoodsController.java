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
        log("base.goods", "CREATE", String.valueOf(request.getOrDefault("goodsCode", id)), "新建商品");
        return ApiResponse.ok(GenericResult.row("goodsId", id, "status", "NORMAL"));
    }

    @PostMapping("/update")
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> request) {
        String bizId = String.valueOf(request.getOrDefault("goodsId", request.getOrDefault("goodsCode", request.getOrDefault("bizId", ""))));
        int updated = jdbcTemplate.update("""
                UPDATE base_goods
                SET goods_name=?, spec=?, category_name=?, brand_name=?, base_unit=?, barcode=?,
                    standard_price=?, latest_purchase_price=?, min_sale_price=?, goods_type=?, shelf_life_days=?, storage_property=?,
                    suggested_retail_price=?, stock_upper_limit=?, stock_lower_limit=?, default_supplier=?, default_warehouse=?, can_return=?
                WHERE goods_id=? OR goods_code=?
                """, request.getOrDefault("goodsName", request.getOrDefault("name", "新商品")),
                request.getOrDefault("spec", ""), request.getOrDefault("categoryName", "默认分类"), request.getOrDefault("brandName", ""),
                request.getOrDefault("baseUnit", "瓶"), request.getOrDefault("barcode", ""),
                request.getOrDefault("standardPrice", 0), request.getOrDefault("latestPurchasePrice", 0), request.getOrDefault("minSalePrice", 0),
                request.getOrDefault("goodsType", "正常商品"), request.getOrDefault("shelfLifeDays", 0), request.getOrDefault("storageProperty", "常温"),
                request.getOrDefault("suggestedRetailPrice", 0), request.getOrDefault("stockUpperLimit", 0), request.getOrDefault("stockLowerLimit", 0),
                request.getOrDefault("defaultSupplier", ""), request.getOrDefault("defaultWarehouse", ""), request.getOrDefault("canReturn", true), bizId, bizId);
        if (updated == 0) throw new IllegalArgumentException("商品不存在，无法编辑");
        log("base.goods", "UPDATE", bizId, "编辑商品资料");
        return ApiResponse.ok(GenericResult.operation("goods", "UPDATE"));
    }

    @PostMapping("/stop")
    public ApiResponse<Map<String, Object>> stop(@RequestBody Map<String, Object> request) {
        return updateStatus(request, "STOPPED", "STOP", "停用商品");
    }

    @PostMapping("/freeze")
    public ApiResponse<Map<String, Object>> freeze(@RequestBody Map<String, Object> request) {
        return updateStatus(request, "FROZEN", "FREEZE", "冻结商品");
    }

    @PostMapping("/delete")
    public ApiResponse<Map<String, Object>> delete(@RequestBody Map<String, Object> request) {
        return updateStatus(request, "DELETED", "DELETE", "删除商品");
    }

    private ApiResponse<Map<String, Object>> updateStatus(Map<String, Object> request, String status, String action, String detail) {
        String bizId = String.valueOf(request.getOrDefault("goodsId", request.getOrDefault("goodsCode", request.getOrDefault("bizId", ""))));
        int updated = jdbcTemplate.update("UPDATE base_goods SET status=? WHERE goods_id=? OR goods_code=?", status, bizId, bizId);
        if (updated == 0) throw new IllegalArgumentException("商品不存在，无法" + detail);
        log("base.goods", action, bizId, detail);
        return ApiResponse.ok(GenericResult.operation("goods", action));
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
                       CASE status WHEN 'NORMAL' THEN '正常' WHEN 'FROZEN' THEN '冻结' WHEN 'DELETED' THEN '已删除' ELSE '停用' END status
                FROM base_goods
                ORDER BY goods_code
                """);
    }

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), moduleCode, action, bizNo, detail);
    }
}
