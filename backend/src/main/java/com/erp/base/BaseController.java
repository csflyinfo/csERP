package com.erp.base;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/base")
public class BaseController {
    private final JdbcTemplate jdbcTemplate;

    public BaseController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/category/page")
    public ApiResponse<PageResult<Map<String, Object>>> categoryPage(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.category_code categoryCode,
                       c.category_name categoryName,
                       COALESCE(p.category_name, '全部分类') parentName,
                       c.default_tax_rate defaultTaxRate,
                       c.goods_count goodsCount,
                       CASE c.status WHEN 'NORMAL' THEN '正常' ELSE '停用' END status
                FROM base_category c
                LEFT JOIN base_category p ON p.category_id = c.parent_id
                ORDER BY c.category_code
                """);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @PostMapping("/category/create")
    public ApiResponse<Map<String, Object>> createCategory(@Valid @RequestBody CategorySaveRequest request) {
        String fullCode = request.parentCode() + request.categoryCode();
        String id = "CATE" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO base_category(category_id, parent_id, parent_code, category_code, category_name, default_tax_rate, goods_count, status)
                VALUES (?, ?, ?, ?, ?, ?, 0, 'NORMAL')
                """, id, request.parentId(), request.parentCode(), fullCode, request.categoryName(), request.defaultTaxRate() == null ? "13%" : request.defaultTaxRate());
        return ApiResponse.ok(GenericResult.row("categoryId", id, "categoryCode", fullCode, "categoryName", request.categoryName(), "status", "NORMAL"));
    }

    @PostMapping("/category/update")
    public ApiResponse<Map<String, Object>> updateCategory(@RequestBody Map<String, Object> request) {
        jdbcTemplate.update("UPDATE base_category SET category_name = COALESCE(?, category_name), default_tax_rate = COALESCE(?, default_tax_rate) WHERE category_code = ?",
                request.get("categoryName"), request.get("defaultTaxRate"), request.get("categoryCode"));
        return ApiResponse.ok(GenericResult.operation("base.category", "UPDATE"));
    }

    @PostMapping("/unit/page")
    public ApiResponse<PageResult<Map<String, Object>>> unitPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT unit_code unitCode, unit_name unitName, can_base_unit canBaseUnit, can_middle_unit canMiddleUnit,
                       can_large_unit canLargeUnit, goods_count goodsCount,
                       CASE status WHEN 'NORMAL' THEN '正常' ELSE '停用' END status
                FROM base_unit ORDER BY unit_code
                """), request));
    }

    @PostMapping("/unit/create")
    public ApiResponse<Map<String, Object>> createUnit(@RequestBody Map<String, Object> request) {
        String id = "UNIT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("INSERT INTO base_unit(unit_id, unit_code, unit_name, can_base_unit, can_middle_unit, can_large_unit, goods_count, status) VALUES (?, ?, ?, TRUE, FALSE, FALSE, 0, 'NORMAL')",
                id, request.getOrDefault("unitCode", id), request.getOrDefault("unitName", "新单位"));
        return ApiResponse.ok(GenericResult.row("unitId", id, "status", "NORMAL"));
    }

    @PostMapping("/brand/page")
    public ApiResponse<PageResult<Map<String, Object>>> brandPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT brand_code brandCode, brand_name brandName, simple_code simpleCode, goods_count goodsCount,
                       CASE status WHEN 'NORMAL' THEN '正常' ELSE '停用' END status
                FROM base_brand ORDER BY brand_code
                """), request));
    }

    @PostMapping("/brand/create")
    public ApiResponse<Map<String, Object>> createBrand(@RequestBody Map<String, Object> request) {
        String id = "BR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("INSERT INTO base_brand(brand_id, brand_code, brand_name, simple_code, goods_count, status) VALUES (?, ?, ?, ?, 0, 'NORMAL')",
                id, request.getOrDefault("brandCode", id), request.getOrDefault("brandName", "新品牌"), request.getOrDefault("simpleCode", ""));
        return ApiResponse.ok(GenericResult.row("brandId", id, "status", "NORMAL"));
    }

    @PostMapping("/warehouse/page")
    public ApiResponse<PageResult<Map<String, Object>>> warehousePage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT warehouse_code warehouseCode, warehouse_name warehouseName, warehouse_type warehouseType,
                       inventory_type inventoryType, cost_group costGroup, manager_name managerName,
                       CASE status WHEN 'NORMAL' THEN '正常' ELSE '停用' END status
                FROM base_warehouse ORDER BY warehouse_code
                """), request));
    }

    @PostMapping("/warehouse/create")
    public ApiResponse<Map<String, Object>> createWarehouse(@RequestBody Map<String, Object> request) {
        String id = "WH" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO base_warehouse(warehouse_id, warehouse_code, warehouse_name, warehouse_type, inventory_type, cost_group, manager_name, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'NORMAL')
                """, id, request.getOrDefault("warehouseCode", id), request.getOrDefault("warehouseName", "新仓库"),
                request.getOrDefault("warehouseType", "正常仓"), request.getOrDefault("inventoryType", "平台主仓"),
                request.getOrDefault("costGroup", "CG01"), request.getOrDefault("managerName", ""));
        return ApiResponse.ok(GenericResult.row("warehouseId", id, "status", "NORMAL"));
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
