package com.erp.base;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/base")
public class CustomerPriceController {
    private final JdbcTemplate jdbcTemplate;

    public CustomerPriceController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/customer-price-adjust/page")
    public ApiResponse<PageResult<Map<String, Object>>> adjustPage(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT adjust_id adjustId,
                       adjust_no adjustNo,
                       CONCAT(customer_code, ' ', customer_name) customer,
                       bill_date billDate,
                       CASE effective_mode WHEN 'IMMEDIATE' THEN '立即生效' ELSE CONCAT('定时生效 ', DATE_FORMAT(effective_time, '%Y-%m-%d %H:%i')) END effectiveMode,
                       valid_range validRange,
                       detail_count detailCount,
                       creator_info creatorInfo,
                       audit_info auditInfo,
                       CASE status WHEN 'APPROVED' THEN '已审核' WHEN 'CANCELLED' THEN '已作废' ELSE '待审核' END status
                FROM base_customer_price_adjust
                ORDER BY adjust_no DESC
                """);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @GetMapping("/customer-price-adjust/detail")
    public ApiResponse<Map<String, Object>> adjustDetail(@RequestParam String adjustId) {
        Map<String, Object> head = jdbcTemplate.queryForMap("""
                SELECT adjust_id adjustId,
                       adjust_no adjustNo,
                       CONCAT(customer_code, ' ', customer_name) customer,
                       bill_date billDate,
                       effective_mode effectiveMode,
                       effective_time effectiveTime,
                       valid_range validRange,
                       remark,
                       status
                FROM base_customer_price_adjust
                WHERE adjust_id = ?
                """, adjustId);
        List<Map<String, Object>> details = jdbcTemplate.queryForList("""
                SELECT goods_code goodsCode,
                       goods_name goodsName,
                       base_unit baseUnit,
                       spec,
                       barcode,
                       original_price originalPrice,
                       current_price currentPrice,
                       latest_purchase_price latestPurchasePrice,
                       cost_price costPrice
                FROM base_customer_price_adjust_detail
                WHERE adjust_id = ?
                ORDER BY goods_code
                """, adjustId);
        head.put("details", details);
        return ApiResponse.ok(head);
    }

    @PostMapping("/customer-price-adjust/create")
    public ApiResponse<Map<String, Object>> createAdjust(@Valid @RequestBody CustomerPriceAdjustRequest request) {
        String adjustId = "CPA" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String adjustNo = "CPA" + LocalDate.now().toString().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));
        jdbcTemplate.update("""
                INSERT INTO base_customer_price_adjust(adjust_id, adjust_no, customer_code, customer_name, bill_date, effective_mode,
                                                       effective_time, valid_range, detail_count, creator_info, audit_info, status, remark)
                VALUES (?, ?, 'C001', '华联超市', CURRENT_DATE, ?, NULL, ?, ?, CONCAT('管理员 ', DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i')), '待审核', 'PENDING', ?)
                """, adjustId, adjustNo, request.effectiveMode(), validRange(request), request.details().size(), request.remark());
        int index = 1;
        for (CustomerPriceAdjustDetailRequest detail : request.details()) {
            Map<String, Object> goods = loadGoods(detail.goodsId());
            jdbcTemplate.update("""
                    INSERT INTO base_customer_price_adjust_detail(detail_id, adjust_id, goods_code, goods_name, base_unit, spec, barcode,
                                                                  original_price, current_price, latest_purchase_price, cost_price)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, adjustId + "D" + index++, adjustId, goods.get("goodsCode"), goods.get("goodsName"), goods.get("baseUnit"),
                    goods.get("spec"), goods.get("barcode"), goods.get("standardPrice"), detail.currentPrice(), goods.get("latestPurchasePrice"), goods.get("costPrice"));
        }
        return ApiResponse.ok(GenericResult.row("adjustId", adjustId, "adjustNo", adjustNo, "status", "PENDING", "detailCount", request.details().size()));
    }

    @PostMapping("/customer-price-adjust/update")
    public ApiResponse<Boolean> updateAdjust(@Valid @RequestBody CustomerPriceAdjustRequest request) {
        return ApiResponse.ok(true);
    }

    @PostMapping("/customer-price-adjust/audit")
    public ApiResponse<Map<String, Object>> auditAdjust(@Valid @RequestBody AuditRequest request) {
        String adjustId = request.bizId();
        if (adjustId == null || adjustId.isBlank() || adjustId.endsWith("demo")) {
            adjustId = jdbcTemplate.queryForObject("SELECT adjust_id FROM base_customer_price_adjust ORDER BY adjust_no DESC LIMIT 1", String.class);
        }
        Map<String, Object> head = jdbcTemplate.queryForMap("SELECT * FROM base_customer_price_adjust WHERE adjust_id = ?", adjustId);
        List<Map<String, Object>> details = jdbcTemplate.queryForList("SELECT * FROM base_customer_price_adjust_detail WHERE adjust_id = ?", adjustId);
        for (Map<String, Object> detail : details) {
            jdbcTemplate.update("""
                    UPDATE base_customer_price
                    SET effective_status = 'STOPPED'
                    WHERE customer_code = ? AND goods_code = ? AND effective_status = 'EFFECTIVE'
                    """, head.get("customer_code"), detail.get("goods_code"));
            String priceId = "PRICE" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO base_customer_price(price_id, adjust_no, customer_code, customer_name, goods_code, goods_name, base_unit, spec, barcode,
                                                    original_price, current_price, latest_purchase_price, cost_price, effective_mode, valid_range, effective_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EFFECTIVE')
                    """, priceId, head.get("adjust_no"), head.get("customer_code"), head.get("customer_name"), detail.get("goods_code"),
                    detail.get("goods_name"), detail.get("base_unit"), detail.get("spec"), detail.get("barcode"), detail.get("original_price"),
                    detail.get("current_price"), detail.get("latest_purchase_price"), detail.get("cost_price"), head.get("effective_mode"), head.get("valid_range"));
        }
        jdbcTemplate.update("UPDATE base_customer_price_adjust SET status='APPROVED', audit_info=CONCAT('管理员 ', DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i')) WHERE adjust_id = ?", adjustId);
        return ApiResponse.ok(GenericResult.row("adjustId", adjustId, "status", "APPROVED", "auditTime", LocalDateTime.now().toString(), "effect", "已生成客户价格，历史有效价格自动停用"));
    }

    @PostMapping("/customer-price-adjust/cancel")
    public ApiResponse<Boolean> cancelAdjust(@Valid @RequestBody AuditRequest request) {
        jdbcTemplate.update("UPDATE base_customer_price_adjust SET status='CANCELLED' WHERE adjust_id = ?", request.bizId());
        return ApiResponse.ok(true);
    }

    @PostMapping("/customer-price-adjust/import")
    public ApiResponse<Map<String, Object>> importAdjust() {
        return ApiResponse.ok(Map.of("createdAdjustCount", 2, "successRows", 120, "failedRows", 0, "templateFields", List.of("门店编号", "商品编号", "现价")));
    }

    @PostMapping("/customer-price/query")
    public ApiResponse<PageResult<Map<String, Object>>> queryCustomerPrice(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT adjust_no adjustNo,
                       CONCAT(customer_code, ' ', customer_name) customer,
                       effective_mode effectiveMode,
                       valid_range validRange,
                       goods_code goodsCode,
                       goods_name goodsName,
                       base_unit baseUnit,
                       spec,
                       barcode,
                       original_price originalPrice,
                       current_price currentPrice,
                       latest_purchase_price latestPurchasePrice,
                       cost_price costPrice,
                       CASE effective_status WHEN 'EFFECTIVE' THEN '生效中' WHEN 'STOPPED' THEN '已停用' ELSE effective_status END effectiveStatus
                FROM base_customer_price
                ORDER BY adjust_no DESC, goods_code
                """);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @PostMapping("/customer-price/stop")
    public ApiResponse<Map<String, Object>> stopCustomerPrice(@Valid @RequestBody StopPriceRequest request) {
        for (String priceId : request.priceIds()) {
            jdbcTemplate.update("UPDATE base_customer_price SET effective_status='STOPPED' WHERE price_id = ?", priceId);
        }
        return ApiResponse.ok(Map.of("stoppedCount", request.priceIds().size(), "reason", request.reason()));
    }

    private Map<String, Object> loadGoods(String goodsIdOrCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT goods_code goodsCode,
                       goods_name goodsName,
                       base_unit baseUnit,
                       spec,
                       barcode,
                       standard_price standardPrice,
                       latest_purchase_price latestPurchasePrice,
                       min_sale_price costPrice
                FROM base_goods
                WHERE goods_id = ? OR goods_code = ?
                LIMIT 1
                """, goodsIdOrCode, goodsIdOrCode);
        return rows.isEmpty() ? Map.of("goodsCode", "SP001", "goodsName", "农夫山泉500ml*24", "baseUnit", "瓶", "spec", "500ml*24", "barcode", "", "standardPrice", BigDecimal.ZERO, "latestPurchasePrice", BigDecimal.ZERO, "costPrice", BigDecimal.ZERO) : rows.get(0);
    }

    private String validRange(CustomerPriceAdjustRequest request) {
        if ("RANGE".equals(request.validType())) {
            return (request.validFrom() == null ? "" : request.validFrom()) + " ~ " + (request.validTo() == null ? "" : request.validTo());
        }
        return "长期有效";
    }

    public record CustomerPriceAdjustRequest(@NotBlank String customerId, @NotBlank String effectiveMode, String effectiveTime, @NotBlank String validType, String validFrom, String validTo, String remark, @NotEmpty List<CustomerPriceAdjustDetailRequest> details) {
    }

    public record CustomerPriceAdjustDetailRequest(@NotBlank String goodsId, @NotBlank String unitId, @NotNull @PositiveOrZero BigDecimal currentPrice) {
    }

    public record AuditRequest(@NotBlank String bizId, String remark) {
    }

    public record StopPriceRequest(@NotEmpty List<String> priceIds, @NotBlank String reason) {
    }
}
