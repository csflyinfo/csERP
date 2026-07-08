package com.erp.base;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.util.BillNoGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户价格调整单 Controller
 *
 * 提供调价单的 CRUD、审核、作废，以及客户价格查询/停用等接口。
 * 支持三级单位（小/中/大）定价，明细从商品档案 unit_config JSON 快照单位信息。
 */
@RestController
@RequestMapping("/base")
public class CustomerPriceController {
    /** unit_config JSON 解析器（无状态，可安全复用） */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** 客户价格调整单号前缀，规则见 sys_bill_no_rule_runtime 的 BN003 */
    private static final String BILL_TYPE_CUSTOMER_PRICE_ADJUST = "CPA";

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;

    public CustomerPriceController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
    }

    @PostMapping("/customer-price-adjust/page")
    public ApiResponse<PageResult<Map<String, Object>>> adjustPage(@RequestBody PageRequest request) {
        // 不加列别名：H2 开了 CASE_INSENSITIVE_IDENTIFIERS，别名会被大写成 ADJUSTID，
        // 前端读 adjustNo/billDate 全是 undefined。统一取原始列名后用 toCamel 转驼峰。
        List<Map<String, Object>> raw = jdbcTemplate.queryForList("""
                SELECT adjust_id,
                       adjust_no,
                       customer_code,
                       customer_name,
                       bill_date,
                       audit_date,
                       effective_mode,
                       effective_time,
                       valid_range,
                       detail_count,
                       creator_info,
                       audit_info,
                       creator_name,
                       create_time,
                       auditor_name,
                       audit_time,
                       status
                FROM base_customer_price_adjust
                ORDER BY adjust_no DESC
                """);
        List<Map<String, Object>> rows = new java.util.ArrayList<>(raw.size());
        for (Map<String, Object> src : raw) {
            Map<String, Object> r = toCamel(src);
            String code = String.valueOf(r.getOrDefault("customerCode", ""));
            String name = String.valueOf(r.getOrDefault("customerName", ""));
            r.put("customer", (code + " " + name).trim());
            String mode = String.valueOf(r.getOrDefault("effectiveMode", ""));
            Object t = r.get("effectiveTime");
            r.put("effectiveModeText", "IMMEDIATE".equals(mode) ? "立即生效"
                    : ("定时生效" + (t == null ? "" : " " + formatDateTime(t))));
            String st = String.valueOf(r.getOrDefault("status", ""));
            r.put("statusText", "APPROVED".equals(st) ? "已审核"
                    : "CANCELLED".equals(st) ? "已作废"
                    : "PENDING".equals(st) ? "待审核"
                    : st);
            // 单据日期：审核后展示审核当日，未审核展示建单日
            Object shownDate = r.get("auditDate") != null ? r.get("auditDate") : r.get("billDate");
            r.put("billDateText", shownDate == null ? "" : String.valueOf(shownDate));
            // 有效期：空值/长期有效统一显示「长期有效」
            String vr = r.get("validRange") == null ? "" : String.valueOf(r.get("validRange")).trim();
            r.put("validRangeText", vr.isEmpty() || "长期有效".equals(vr) ? "长期有效" : vr);
            // 制单/审核人时间：新字段优先，老数据回退到拼接串
            r.put("creatorNameText", firstNonBlank(r.get("creatorName"), r.get("creatorInfo")));
            r.put("createTimeText", formatDateTime(r.get("createTime")));
            r.put("auditorNameText", firstNonBlank(r.get("auditorName"), null));
            r.put("auditTimeText", formatDateTime(r.get("auditTime")));
            rows.add(r);
        }
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @GetMapping("/customer-price-adjust/detail")
    public ApiResponse<Map<String, Object>> adjustDetail(@RequestParam String adjustId) {
        // 同 page：不用列别名，避免 H2 大写化导致前端取不到字段
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT adjust_id,
                       adjust_no,
                       customer_code,
                       customer_name,
                       bill_date,
                       audit_date,
                       effective_mode,
                       effective_time,
                       valid_range,
                       remark,
                       creator_info,
                       audit_info,
                       creator_name,
                       create_time,
                       auditor_name,
                       audit_time,
                       status
                FROM base_customer_price_adjust
                WHERE adjust_id = ? OR adjust_no = ?
                """, adjustId, adjustId);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调整单不存在");

        Map<String, Object> head = toCamel(heads.get(0));
        String code = String.valueOf(head.getOrDefault("customerCode", ""));
        String name = String.valueOf(head.getOrDefault("customerName", ""));
        head.put("customer", (code + " " + name).trim());
        String st = String.valueOf(head.getOrDefault("status", ""));
        head.put("statusText", "APPROVED".equals(st) ? "已审核"
                : "CANCELLED".equals(st) ? "已作废"
                : "PENDING".equals(st) ? "待审核"
                : st);
        String vr = head.get("validRange") == null ? "" : String.valueOf(head.get("validRange")).trim();
        head.put("validRangeText", vr.isEmpty() || "长期有效".equals(vr) ? "长期有效" : vr);
        head.put("createTimeText", formatDateTime(head.get("createTime")));
        head.put("auditTimeText", formatDateTime(head.get("auditTime")));
        head.put("creatorNameText", firstNonBlank(head.get("creatorName"), head.get("creatorInfo")));
        head.put("auditorNameText", firstNonBlank(head.get("auditorName"), null));
        Object shownDate = head.get("auditDate") != null ? head.get("auditDate") : head.get("billDate");
        head.put("billDateText", shownDate == null ? "" : String.valueOf(shownDate));

        // 编辑页需要还原「长期有效 / 按时间段」及起止日期
        head.put("validType", vr.isEmpty() || "长期有效".equals(vr) ? "LONG_TERM" : "RANGE");
        if (vr.contains("~")) {
            String[] parts = vr.split("~");
            head.put("validFrom", parts[0].trim());
            head.put("validTo", parts.length > 1 ? parts[1].trim() : "");
        } else {
            head.put("validFrom", "");
            head.put("validTo", "");
        }

        String realAdjustId = String.valueOf(head.get("adjustId"));
        List<Map<String, Object>> rawDetails = jdbcTemplate.queryForList("""
                SELECT goods_code,
                       goods_name,
                       base_unit,
                       spec,
                       barcode,
                       original_price,
                       current_price,
                       latest_purchase_price,
                       cost_price,
                       brand_name,
                       category_name,
                       storage_property,
                       small_unit,
                       medium_unit,
                       large_unit,
                       medium_unit_enabled,
                       large_unit_enabled,
                       small_standard_price,
                       medium_standard_price,
                       large_standard_price,
                       small_current_price,
                       medium_current_price,
                       large_current_price
                FROM base_customer_price_adjust_detail
                WHERE adjust_id = ?
                ORDER BY goods_code
                """, realAdjustId);
        List<Map<String, Object>> details = new java.util.ArrayList<>(rawDetails.size());
        for (Map<String, Object> d : rawDetails) details.add(toCamel(d));
        head.put("details", details);
        return ApiResponse.ok(head);
    }

    @PostMapping("/customer-price-adjust/create")
    public ApiResponse<Map<String, Object>> createAdjust(@Valid @RequestBody CustomerPriceAdjustRequest request) {
        String adjustId = "CPA" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        // 单号走统一生成器（CPA + yyyyMMdd + 4 位当日流水），
        // 原来用 currentTimeMillis % 10000 取模，同日流水既不连续也可能撞号
        String adjustNo = billNoGen.nextNo(BILL_TYPE_CUSTOMER_PRICE_ADJUST,
                "base_customer_price_adjust", "adjust_no");
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUserDisplayName();
        String creatorInfo = operator + " " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        String customerCode = request.customerId();
        String customerName = "";
        try {
            List<Map<String, Object>> cust = jdbcTemplate.queryForList(
                    "SELECT customer_name FROM base_customer WHERE customer_code = ? OR customer_id = ? LIMIT 1",
                    customerCode, customerCode);
            if (!cust.isEmpty()) {
                Object n = cust.get(0).get("customer_name");
                if (n == null) n = cust.get(0).get("CUSTOMER_NAME");
                if (n != null) customerName = String.valueOf(n);
            }
        } catch (Exception ignore) { /* tolerate */ }

        jdbcTemplate.update("""
                INSERT INTO base_customer_price_adjust(adjust_id, adjust_no, customer_code, customer_name, bill_date, effective_mode,
                                                       effective_time, valid_range, detail_count, creator_info, audit_info, status, remark,
                                                       creator_name, create_time)
                VALUES (?, ?, ?, ?, CURRENT_DATE, ?, NULL, ?, ?, ?, '待审核', 'PENDING', ?, ?, ?)
                """, adjustId, adjustNo, customerCode, customerName, request.effectiveMode(),
                validRange(request), request.details().size(), creatorInfo, request.remark(),
                operator, java.sql.Timestamp.valueOf(now));

        insertDetails(adjustId, request.details());
        return ApiResponse.ok(GenericResult.row("adjustId", adjustId, "adjustNo", adjustNo,
                "status", "PENDING", "detailCount", request.details().size()));
    }

    /**
     * 编辑调整单：仅待审核（PENDING）单据可改。
     * 明细采取「整体替换」策略 —— 先删后插，避免逐行 diff 的复杂度。
     */
    @PostMapping("/customer-price-adjust/update")
    public ApiResponse<Boolean> updateAdjust(@Valid @RequestBody CustomerPriceAdjustUpdateRequest request) {
        String adjustId = request.adjustId();
        List<Map<String, Object>> exist = jdbcTemplate.queryForList(
                "SELECT status FROM base_customer_price_adjust WHERE adjust_id = ?", adjustId);
        if (exist.isEmpty()) return ApiResponse.fail("404", "调整单不存在");
        String status = String.valueOf(toCamel(exist.get(0)).get("status"));
        if (!"PENDING".equals(status)) return ApiResponse.fail("400", "仅待审核单据可编辑");

        String customerCode = request.customerId();
        String customerName = lookupCustomerName(customerCode);

        jdbcTemplate.update("""
                UPDATE base_customer_price_adjust
                SET customer_code = ?, customer_name = ?, effective_mode = ?, valid_range = ?,
                    detail_count = ?, remark = ?
                WHERE adjust_id = ?
                """, customerCode, customerName, request.effectiveMode(),
                validRange(request.validType(), request.validFrom(), request.validTo()),
                request.details().size(), request.remark(), adjustId);

        jdbcTemplate.update("DELETE FROM base_customer_price_adjust_detail WHERE adjust_id = ?", adjustId);
        insertDetails(adjustId, request.details());
        return ApiResponse.ok(true);
    }

    @PostMapping("/customer-price-adjust/audit")
    public ApiResponse<Map<String, Object>> auditAdjust(@Valid @RequestBody AuditRequest request) {
        String adjustId = request.bizId();
        if (adjustId == null || adjustId.isBlank() || adjustId.endsWith("demo")) {
            adjustId = jdbcTemplate.queryForObject(
                    "SELECT adjust_id FROM base_customer_price_adjust ORDER BY adjust_no DESC LIMIT 1", String.class);
        }
        // 审核人/时间先算好：拆单位写价格时要记进变价日志的 operator
        LocalDateTime auditedAt = LocalDateTime.now();
        String auditor = currentUserDisplayName();

        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM base_customer_price_adjust WHERE adjust_id = ?", adjustId);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调整单不存在");
        Map<String, Object> head = heads.get(0);
        // 只有待审核单据能审核：重复审核会重复写变价日志、把刚生效的价当成"旧价"再记一遍
        String currentStatus = str(toCamel(head).get("status"));
        if (!"PENDING".equals(currentStatus)) {
            return ApiResponse.fail("400", "APPROVED".equals(currentStatus) ? "该单据已审核" : "仅待审核单据可审核");
        }
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM base_customer_price_adjust_detail WHERE adjust_id = ?", adjustId);

        for (Map<String, Object> detail : details) {
            // 停用旧有效价
            jdbcTemplate.update("""
                    UPDATE base_customer_price
                    SET effective_status = 'STOPPED'
                    WHERE customer_code = ? AND goods_code = ? AND effective_status = 'EFFECTIVE'
                    """, head.get("customer_code"), detail.get("goods_code"));

            String priceId = "PRICE" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO base_customer_price(
                        price_id, adjust_no, customer_code, customer_name, goods_code, goods_name, base_unit, spec, barcode,
                        original_price, current_price, latest_purchase_price, cost_price,
                        effective_mode, valid_range, effective_status,
                        brand_name, category_name, storage_property,
                        small_unit, medium_unit, large_unit,
                        medium_unit_enabled, large_unit_enabled,
                        small_standard_price, medium_standard_price, large_standard_price,
                        small_current_price, medium_current_price, large_current_price)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, 'EFFECTIVE',
                            ?, ?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?, ?, ?)
                    """, priceId,
                    head.get("adjust_no"), head.get("customer_code"), head.get("customer_name"),
                    detail.get("goods_code"), detail.get("goods_name"), detail.get("base_unit"),
                    detail.get("spec"), detail.get("barcode"),
                    detail.get("original_price"), detail.get("current_price"),
                    detail.get("latest_purchase_price"), detail.get("cost_price"),
                    head.get("effective_mode"), head.get("valid_range"),
                    detail.get("brand_name"), detail.get("category_name"), detail.get("storage_property"),
                    detail.get("small_unit"), detail.get("medium_unit"), detail.get("large_unit"),
                    detail.get("medium_unit_enabled"), detail.get("large_unit_enabled"),
                    detail.get("small_standard_price"), detail.get("medium_standard_price"),
                    detail.get("large_standard_price"),
                    detail.get("small_current_price"), detail.get("medium_current_price"),
                    detail.get("large_current_price"));

            // 按单位拆行写入 base_customer_price_item，并记一条变价日志。
            // 三级单位各自独立生效/停用，故必须拆行；老表继续写是为了兼容旧接口。
            applyUnitPrices(head, detail, auditor);
        }
        // 审核信息在应用层拼装：H2 即使 MODE=MySQL 也没有 DATE_FORMAT 函数，
        // 与 createAdjust 里 creator_info 的处理方式保持一致
        String auditInfo = auditor + " " + auditedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        // audit_date 记录审核当日：列表的「单据日期」审核后展示这一天，
        // bill_date 保留原始建单日不覆盖，方便追溯
        jdbcTemplate.update("""
                UPDATE base_customer_price_adjust
                SET status='APPROVED', audit_info=?, auditor_name=?, audit_time=?, audit_date=CURRENT_DATE
                WHERE adjust_id = ?
                """, auditInfo, auditor, java.sql.Timestamp.valueOf(auditedAt), adjustId);
        return ApiResponse.ok(GenericResult.row("adjustId", adjustId, "status", "APPROVED",
                "auditTime", auditedAt.toString(), "effect", "已生成客户价格，历史有效价格自动停用"));
    }

    /**
     * 作废调整单。
     *
     * 已审核单据作废时，必须同时停用它生成的客户价格 —— 否则「客户价格查询」
     * 仍会展示一张已作废单据带来的价格，销售按错价出单。
     * 变价日志不删：它是历史流水，作废本身也是历史的一部分。
     */
    @PostMapping("/customer-price-adjust/cancel")
    public ApiResponse<Boolean> cancelAdjust(@Valid @RequestBody AuditRequest request) {
        String adjustId = request.bizId();
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT adjust_no, status FROM base_customer_price_adjust WHERE adjust_id = ?", adjustId);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调整单不存在");
        Map<String, Object> head = toCamel(heads.get(0));
        String status = str(head.get("status"));
        if ("CANCELLED".equals(status)) return ApiResponse.fail("400", "该单据已作废");

        // 已审核单据：连带停用由它产生的、且当前仍生效的价格
        if ("APPROVED".equals(status)) {
            jdbcTemplate.update("""
                    UPDATE base_customer_price_item
                    SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
                    WHERE adjust_no = ? AND is_active = TRUE
                    """, str(head.get("adjustNo")));
            jdbcTemplate.update("""
                    UPDATE base_customer_price
                    SET effective_status = 'STOPPED'
                    WHERE adjust_no = ? AND effective_status = 'EFFECTIVE'
                    """, str(head.get("adjustNo")));
        }
        jdbcTemplate.update("UPDATE base_customer_price_adjust SET status='CANCELLED' WHERE adjust_id = ?", adjustId);
        return ApiResponse.ok(true);
    }

    @PostMapping("/customer-price-adjust/import")
    public ApiResponse<Map<String, Object>> importAdjust() {
        return ApiResponse.ok(Map.of("createdAdjustCount", 2, "successRows", 120, "failedRows", 0,
                "templateFields", List.of("门店编号", "商品编号", "现价")));
    }

    /**
     * 客户价格查询 —— 客户指定商品的当前专属价格（按单位拆行）。
     * 对照【价格组商品查询】，支持按客户、商品、状态、单位类型过滤，可停用生效中的价格。
     */
    @PostMapping("/customer-price/query")
    public ApiResponse<PageResult<Map<String, Object>>> queryCustomerPrice(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        // 不查 adjust_no / effective_mode / valid_range：这三个是「来源单据」属性，
        // 本页只呈现「当前生效价」，一客户一商品一单位恒定一条（uk_cust_goods_unit 保证唯一）。
        // 需要追溯来源单号/生效方式/有效期请到【客户商品变价查询】。
        //
        // JOIN 已审核单据：只展示来源单据为 APPROVED 的价格。
        // 正常流程下写入即已审核，这里是双保险 —— 防止历史脏数据或人工改库
        // 让待审/已作废单据的价格出现在本页。
        StringBuilder sql = new StringBuilder("""
                SELECT i.id, i.customer_code, i.customer_name, i.goods_code, i.goods_name,
                       i.unit_level, i.unit_name, i.standard_price, i.price, i.is_active,
                       i.created_at, i.updated_at,
                       gd.spec, gd.barcode, gd.category_name, gd.brand_name, gd.storage_property
                FROM base_customer_price_item i
                LEFT JOIN base_goods gd ON gd.goods_code = i.goods_code
                WHERE EXISTS (
                          SELECT 1 FROM base_customer_price_adjust h
                          WHERE h.adjust_no = i.adjust_no AND h.status = 'APPROVED'
                      )
                """);
        List<Object> args = new java.util.ArrayList<>();

        String customer = trimFilter(filters.get("customer"), filters.get("customerCode"));
        if (!customer.isEmpty()) {
            sql.append(" AND (i.customer_code LIKE ? OR i.customer_name LIKE ?)");
            args.add("%" + customer + "%");
            args.add("%" + customer + "%");
        }
        String goods = trimFilter(filters.get("goods"), filters.get("goodsCode"));
        if (!goods.isEmpty()) {
            sql.append(" AND (i.goods_code LIKE ? OR i.goods_name LIKE ?)");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
        }
        // 状态：生效中 / 已停用（也接受 true/false）
        String status = trimFilter(filters.get("status"), filters.get("isActive"));
        if (!status.isEmpty()) {
            Boolean active = parseActiveFilter(status);
            if (active != null) { sql.append(" AND i.is_active = ?"); args.add(active); }
        }
        // 单位类型：接受「小单位/中单位/大单位」或 1/2/3
        String unitType = trimFilter(filters.get("unitType"), filters.get("unitLevel"));
        if (!unitType.isEmpty()) {
            Integer level = parseUnitLevelFilter(unitType);
            if (level != null) { sql.append(" AND i.unit_level = ?"); args.add(level); }
        }
        sql.append(" ORDER BY i.customer_code, i.goods_code, i.unit_level");

        List<Map<String, Object>> raw = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> rows = new java.util.ArrayList<>(raw.size());
        for (Map<String, Object> src : raw) {
            Map<String, Object> r = toCamel(src);
            boolean active = isTrue(r.get("isActive"));
            r.put("isActive", active);
            r.put("statusText", active ? "生效中" : "已停用");
            r.put("unitLevelText", unitLevelText(r.get("unitLevel")));
            r.put("customer", (str(r.get("customerCode")) + " " + str(r.get("customerName"))).trim());
            rows.add(r);
        }
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /**
     * 客户商品变价查询 —— 客户商品的历史调价记录（只读，不可操作）。
     * 每条记录对应一次单位级别的价格变动，含变价前/变价后。
     */
    @PostMapping("/customer-price-change-log/page")
    public ApiResponse<PageResult<Map<String, Object>>> customerPriceChangeLogPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        // EXISTS 已审核单据：只展示来源单据为 APPROVED 的变价记录。
        // 与【客户价格查询】口径一致 —— 待审/已作废单据不构成有效调价历史。
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.adjust_no, l.bill_date, l.customer_code, l.customer_name,
                       l.goods_code, l.goods_name, l.unit_level, l.unit_name,
                       l.category_name, l.brand_name, l.old_price, l.new_price,
                       l.effective_mode, l.valid_range, l.operator, l.remark, l.created_at
                FROM base_customer_price_change_log l
                WHERE EXISTS (
                          SELECT 1 FROM base_customer_price_adjust h
                          WHERE h.adjust_no = l.adjust_no AND h.status = 'APPROVED'
                      )
                """);
        List<Object> args = new java.util.ArrayList<>();

        String customer = trimFilter(filters.get("customer"), filters.get("customerCode"));
        if (!customer.isEmpty()) {
            sql.append(" AND (l.customer_code LIKE ? OR l.customer_name LIKE ?)");
            args.add("%" + customer + "%");
            args.add("%" + customer + "%");
        }
        String goods = trimFilter(filters.get("goods"), filters.get("goodsCode"));
        if (!goods.isEmpty()) {
            sql.append(" AND (l.goods_code LIKE ? OR l.goods_name LIKE ?)");
            args.add("%" + goods + "%");
            args.add("%" + goods + "%");
        }
        String adjustNo = trimFilter(filters.get("adjustNo"), null);
        if (!adjustNo.isEmpty()) { sql.append(" AND l.adjust_no LIKE ?"); args.add("%" + adjustNo + "%"); }
        String unitType = trimFilter(filters.get("unitType"), filters.get("unitLevel"));
        if (!unitType.isEmpty()) {
            Integer level = parseUnitLevelFilter(unitType);
            if (level != null) { sql.append(" AND l.unit_level = ?"); args.add(level); }
        }
        String brand = trimFilter(filters.get("brandName"), filters.get("brand"));
        if (!brand.isEmpty()) { sql.append(" AND l.brand_name LIKE ?"); args.add("%" + brand + "%"); }
        String category = trimFilter(filters.get("categoryName"), filters.get("category"));
        if (!category.isEmpty()) { sql.append(" AND l.category_name LIKE ?"); args.add("%" + category + "%"); }
        String dateFrom = trimFilter(filters.get("dateFrom"), null);
        if (!dateFrom.isEmpty()) { sql.append(" AND l.created_at >= ?"); args.add(dateFrom + " 00:00:00"); }
        String dateTo = trimFilter(filters.get("dateTo"), null);
        if (!dateTo.isEmpty()) { sql.append(" AND l.created_at <= ?"); args.add(dateTo + " 23:59:59"); }
        sql.append(" ORDER BY l.created_at DESC, l.goods_code, l.unit_level");

        List<Map<String, Object>> raw = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> rows = new java.util.ArrayList<>(raw.size());
        for (Map<String, Object> src : raw) {
            Map<String, Object> r = toCamel(src);
            r.put("unitLevelText", unitLevelText(r.get("unitLevel")));
            // 变价前为空 = 首次设价
            Object oldP = r.get("oldPrice");
            r.put("oldPriceText", oldP == null ? "首次设价" : toDecimal(oldP).toPlainString());
            String vr = str(r.get("validRange")).trim();
            r.put("validRangeText", vr.isEmpty() || "长期有效".equals(vr) ? "长期有效" : vr);
            r.put("effectiveModeText", "IMMEDIATE".equals(str(r.get("effectiveMode"))) ? "立即生效" : "定时生效");
            r.put("billDateText", r.get("billDate") == null ? "" : str(r.get("billDate")));
            r.put("createdAtText", formatDateTime(r.get("createdAt")));
            rows.add(r);
        }
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /**
     * 停用客户价格（按 base_customer_price_item.id）。
     * 兼容老调用：传进来的若是 base_customer_price.price_id 也一并处理。
     */
    @PostMapping("/customer-price/stop")
    public ApiResponse<Map<String, Object>> stopCustomerPrice(@Valid @RequestBody StopPriceRequest request) {
        int stopped = 0;
        for (String priceId : request.priceIds()) {
            if (priceId == null || priceId.isBlank()) continue;
            int n = jdbcTemplate.update("""
                    UPDATE base_customer_price_item
                    SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND is_active = TRUE
                    """, priceId);
            if (n == 0) {
                // 老表兜底，保证历史入口不报错
                n = jdbcTemplate.update(
                        "UPDATE base_customer_price SET effective_status='STOPPED' WHERE price_id = ?", priceId);
            }
            stopped += n;
        }
        return ApiResponse.ok(Map.of("stoppedCount", stopped, "reason", request.reason()));
    }

    // ==================== 内部辅助 ====================

    /** 单位级别：1-小 2-中 3-大，与 base_price_group_item 口径一致 */
    private static final int UNIT_LEVEL_SMALL = 1;
    private static final int UNIT_LEVEL_MEDIUM = 2;
    private static final int UNIT_LEVEL_LARGE = 3;

    /**
     * 审核时把一条调整单明细的三级单位价格落到 base_customer_price_item（按单位拆行），
     * 并为每个实际变动的单位写一条变价日志。
     *
     * 规则：
     *   · 只处理「已启用且填了现价」的单位，未启用/未填价的跳过（不生成空记录）；
     *   · 已存在同 (客户,商品,单位) 记录 → 覆盖价格并重新置为生效，旧价作为变价前；
     *   · 不存在 → 新增，变价前为空，前端显示「首次设价」。
     *
     * @param detailRow 调整单明细行（原始下划线 key）
     * @param operator  审核人显示名，记入变价日志
     */
    private void applyUnitPrices(Map<String, Object> headRow, Map<String, Object> detailRow, String operator) {
        Map<String, Object> head = toCamel(headRow);
        Map<String, Object> d = toCamel(detailRow);

        String customerCode = str(head.get("customerCode"));
        String customerName = str(head.get("customerName"));
        String adjustNo = str(head.get("adjustNo"));
        String effectiveMode = str(head.get("effectiveMode"));
        String validRange = str(head.get("validRange"));
        Object billDate = head.get("billDate");

        String goodsCode = str(d.get("goodsCode"));
        String goodsName = str(d.get("goodsName"));
        String categoryName = str(d.get("categoryName"));
        String brandName = str(d.get("brandName"));

        // 三级单位：级别 → (单位名, 标价, 现价, 是否启用)
        record UnitSlot(int level, String unitName, BigDecimal standardPrice, BigDecimal price, boolean enabled) {}
        List<UnitSlot> slots = List.of(
                new UnitSlot(UNIT_LEVEL_SMALL, str(d.get("smallUnit")),
                        toDecimal(d.get("smallStandardPrice")), toDecimal(d.get("smallCurrentPrice")), true),
                new UnitSlot(UNIT_LEVEL_MEDIUM, str(d.get("mediumUnit")),
                        toDecimal(d.get("mediumStandardPrice")), toDecimal(d.get("mediumCurrentPrice")),
                        isTrue(d.get("mediumUnitEnabled"))),
                new UnitSlot(UNIT_LEVEL_LARGE, str(d.get("largeUnit")),
                        toDecimal(d.get("largeStandardPrice")), toDecimal(d.get("largeCurrentPrice")),
                        isTrue(d.get("largeUnitEnabled"))));

        for (UnitSlot slot : slots) {
            // 未启用或没填现价的单位不生成价格记录
            if (!slot.enabled() || slot.price() == null) continue;

            List<Map<String, Object>> exist = jdbcTemplate.queryForList("""
                    SELECT id, price FROM base_customer_price_item
                    WHERE customer_code = ? AND goods_code = ? AND unit_level = ?
                    """, customerCode, goodsCode, slot.level());

            BigDecimal oldPrice = null;
            if (exist.isEmpty()) {
                jdbcTemplate.update("""
                        INSERT INTO base_customer_price_item(
                            id, customer_code, customer_name, goods_code, goods_name,
                            unit_level, unit_name, standard_price, price,
                            adjust_no, effective_mode, valid_range, is_active)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                        """, "CPI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                        customerCode, customerName, goodsCode, goodsName,
                        slot.level(), slot.unitName(), slot.standardPrice(), slot.price(),
                        adjustNo, effectiveMode, validRange);
            } else {
                Map<String, Object> row = toCamel(exist.get(0));
                oldPrice = toDecimal(row.get("price"));
                jdbcTemplate.update("""
                        UPDATE base_customer_price_item
                        SET customer_name = ?, goods_name = ?, unit_name = ?, standard_price = ?, price = ?,
                            adjust_no = ?, effective_mode = ?, valid_range = ?,
                            is_active = TRUE, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, customerName, goodsName, slot.unitName(), slot.standardPrice(), slot.price(),
                        adjustNo, effectiveMode, validRange, row.get("id"));
            }

            // 价格没变就不记日志，避免变价查询里出现「变价前=变价后」的噪音记录
            if (oldPrice != null && oldPrice.compareTo(slot.price()) == 0) continue;

            jdbcTemplate.update("""
                    INSERT INTO base_customer_price_change_log(
                        id, adjust_no, bill_date, customer_code, customer_name,
                        goods_code, goods_name, unit_level, unit_name,
                        category_name, brand_name, old_price, new_price,
                        effective_mode, valid_range, operator, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "CPL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                    adjustNo, billDate, customerCode, customerName,
                    goodsCode, goodsName, slot.level(), slot.unitName(),
                    categoryName, brandName, oldPrice, slot.price(),
                    effectiveMode, validRange, operator, str(head.get("remark")));
        }
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    /** 取第一个非空过滤值并去空白（前端字段名可能有两种写法） */
    private static String trimFilter(Object primary, Object fallback) {
        String a = str(primary).trim();
        if (!a.isEmpty()) return a;
        return str(fallback).trim();
    }

    /** 单位级别 → 中文；非 1/2/3 原样返回 */
    private static String unitLevelText(Object level) {
        String s = str(level);
        return switch (s) {
            case "1" -> "小单位";
            case "2" -> "中单位";
            case "3" -> "大单位";
            default -> s;
        };
    }

    /** 状态过滤值 → 布尔；无法识别返回 null（表示不过滤） */
    private static Boolean parseActiveFilter(String raw) {
        String s = raw.trim();
        if (s.contains("生效") || s.equals("正常") || "true".equalsIgnoreCase(s) || "1".equals(s)) return Boolean.TRUE;
        if (s.contains("停用") || "false".equalsIgnoreCase(s) || "0".equals(s)) return Boolean.FALSE;
        return null;
    }

    /** 单位类型过滤值 → 级别；无法识别返回 null（表示不过滤） */
    private static Integer parseUnitLevelFilter(String raw) {
        String s = raw.trim();
        if (s.startsWith("小") || "1".equals(s)) return UNIT_LEVEL_SMALL;
        if (s.startsWith("中") || "2".equals(s)) return UNIT_LEVEL_MEDIUM;
        if (s.startsWith("大") || "3".equals(s)) return UNIT_LEVEL_LARGE;
        return null;
    }

    /** 布尔归一：H2 可能返回 Boolean 或 0/1 */
    private static boolean isTrue(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return "true".equalsIgnoreCase(str(v)) || "1".equals(str(v));
    }

    /**
     * 写入调整单明细（create / update 共用）。
     * 商品信息与三级单位标价一律从商品档案快照，不信任前端传值；
     * 现价取前端录入值，但未启用单位强制置空。
     */
    private void insertDetails(String adjustId, List<CustomerPriceAdjustDetailRequest> details) {
        int index = 1;
        for (CustomerPriceAdjustDetailRequest detail : details) {
            Map<String, Object> goods = loadGoods(detail.goodsId());
            UnitProfile profile = parseUnitProfile(goods);

            // 现价优先级：请求传了三级单位现价就用它，否则用单级 currentPrice 回填到小单位
            BigDecimal smallCur = detail.smallCurrentPrice() != null ? detail.smallCurrentPrice()
                    : (detail.currentPrice() != null ? detail.currentPrice() : BigDecimal.ZERO);
            // 以商品档案的启用状态为准：未启用的单位一律不落价格，
            // 防止前端置灰被绕过（或商品档案改动后）写入无意义的中/大单位价
            BigDecimal mediumCur = profile.mediumEnabled ? detail.mediumCurrentPrice() : null;
            BigDecimal largeCur = profile.largeEnabled ? detail.largeCurrentPrice() : null;

            jdbcTemplate.update("""
                    INSERT INTO base_customer_price_adjust_detail(
                        detail_id, adjust_id, goods_code, goods_name, base_unit, spec, barcode,
                        original_price, current_price, latest_purchase_price, cost_price,
                        brand_name, category_name, storage_property,
                        small_unit, medium_unit, large_unit,
                        medium_unit_enabled, large_unit_enabled,
                        small_standard_price, medium_standard_price, large_standard_price,
                        small_current_price, medium_current_price, large_current_price)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?, ?,
                            ?, ?, ?)
                    """, adjustId + "D" + index++, adjustId,
                    goods.get("goodsCode"), goods.get("goodsName"), goods.get("baseUnit"),
                    goods.get("spec"), goods.get("barcode"),
                    goods.get("standardPrice"), smallCur,
                    goods.get("latestPurchasePrice"), goods.get("costPrice"),
                    goods.get("brandName"), goods.get("categoryName"), goods.get("storageProperty"),
                    profile.smallUnit, profile.mediumUnit, profile.largeUnit,
                    profile.mediumEnabled, profile.largeEnabled,
                    profile.smallStandardPrice, profile.mediumStandardPrice, profile.largeStandardPrice,
                    smallCur, mediumCur, largeCur);
        }
    }

    /** 按客户编码/ID 查客户名称，查不到返回空串（不阻断建单） */
    private String lookupCustomerName(String customerCode) {
        try {
            List<Map<String, Object>> cust = jdbcTemplate.queryForList(
                    "SELECT customer_name FROM base_customer WHERE customer_code = ? OR customer_id = ? LIMIT 1",
                    customerCode, customerCode);
            if (!cust.isEmpty()) {
                Object n = toCamel(cust.get(0)).get("customerName");
                if (n != null) return String.valueOf(n);
            }
        } catch (Exception ignore) { /* 客户表结构缺失时不阻断 */ }
        return "";
    }

    /**
     * 当前操作人的「显示名」。
     * SecurityContext 里存的是登录账号，这里换成 sys_user_runtime.display_name 给人看；
     * 查不到就退回账号名，再不行退回「系统」。
     */
    private String currentUserDisplayName() {
        String username = null;
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
                username = auth.getName();
            }
        } catch (Exception ignore) { /* 无安全上下文（如定时任务） */ }
        if (username == null) return "系统";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT display_name FROM sys_user_runtime WHERE username = ? LIMIT 1", username);
            if (!rows.isEmpty()) {
                Object dn = toCamel(rows.get(0)).get("displayName");
                if (dn != null && !String.valueOf(dn).isBlank()) return String.valueOf(dn);
            }
        } catch (Exception ignore) { /* 用户表缺失时退回账号名 */ }
        return username;
    }

    /**
     * 下划线列名转驼峰。
     * H2 开了 CASE_INSENSITIVE_IDENTIFIERS 后返回的 key 是大写（ADJUST_NO），
     * 而前端按驼峰取值，必须在这里统一转换。
     */
    private static Map<String, Object> toCamel(Map<String, Object> row) {
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

    /** 时间统一格式化为 yyyy-MM-dd HH:mm:ss（遵循 CLAUDE.md 时间格式规范） */
    private static String formatDateTime(Object v) {
        if (v == null) return "";
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().format(DATE_TIME_FMT);
        if (v instanceof LocalDateTime ldt) return ldt.format(DATE_TIME_FMT);
        return String.valueOf(v);
    }

    /** 取第一个非空白值，都为空返回空串 */
    private static String firstNonBlank(Object a, Object b) {
        if (a != null && !String.valueOf(a).isBlank()) return String.valueOf(a);
        if (b != null && !String.valueOf(b).isBlank()) return String.valueOf(b);
        return "";
    }

    /**
     * 从 base_goods 表加载商品完整信息（含多单位配置字段）
     */
    private Map<String, Object> loadGoods(String goodsIdOrCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT goods_code goodsCode,
                       goods_name goodsName,
                       base_unit baseUnit,
                       spec,
                       barcode,
                       standard_price standardPrice,
                       latest_purchase_price latestPurchasePrice,
                       min_sale_price costPrice,
                       category_name categoryName,
                       brand_name brandName,
                       storage_property storageProperty,
                       unit_config unitConfig
                FROM base_goods
                WHERE goods_id = ? OR goods_code = ?
                LIMIT 1
                """, goodsIdOrCode, goodsIdOrCode);
        if (rows.isEmpty()) {
            return Map.ofEntries(
                    Map.entry("goodsCode", "SP001"),
                    Map.entry("goodsName", "农夫山泉500ml*24"),
                    Map.entry("baseUnit", "瓶"),
                    Map.entry("spec", "500ml*24"),
                    Map.entry("barcode", ""),
                    Map.entry("standardPrice", BigDecimal.ZERO),
                    Map.entry("latestPurchasePrice", BigDecimal.ZERO),
                    Map.entry("costPrice", BigDecimal.ZERO),
                    Map.entry("categoryName", ""),
                    Map.entry("brandName", ""),
                    Map.entry("storageProperty", "常温"),
                    Map.entry("unitConfig", "[]"));
        }
        return rows.get(0);
    }

    /**
     * 三级单位 Profile 值对象<br>
     * 从 unit_config JSON 数组解析，索引 0=小 1=中 2=大。
     * 小单位恒启用，中/大单位按 enabled 字段判定。
     */
    static class UnitProfile {
        final String smallUnit;
        final String mediumUnit;
        final String largeUnit;
        final boolean mediumEnabled;
        final boolean largeEnabled;
        final BigDecimal smallStandardPrice;
        final BigDecimal mediumStandardPrice;
        final BigDecimal largeStandardPrice;

        UnitProfile(String smallUnit, String mediumUnit, String largeUnit,
                    boolean mediumEnabled, boolean largeEnabled,
                    BigDecimal smallStandardPrice, BigDecimal mediumStandardPrice,
                    BigDecimal largeStandardPrice) {
            this.smallUnit = smallUnit;
            this.mediumUnit = mediumUnit;
            this.largeUnit = largeUnit;
            this.mediumEnabled = mediumEnabled;
            this.largeEnabled = largeEnabled;
            this.smallStandardPrice = smallStandardPrice;
            this.mediumStandardPrice = mediumStandardPrice;
            this.largeStandardPrice = largeStandardPrice;
        }
    }

    /**
     * 解析 unit_config JSON 为三级单位 Profile
     */
    @SuppressWarnings("unchecked")
    private UnitProfile parseUnitProfile(Map<String, Object> goods) {
        String smallUnit = toString(goods.get("baseUnit"));
        String mediumUnit = "";
        String largeUnit = "";
        boolean mediumEnabled = false;
        boolean largeEnabled = false;
        BigDecimal smallStandardPrice = toDecimal(goods.get("standardPrice"));
        BigDecimal mediumStandardPrice = null;
        BigDecimal largeStandardPrice = null;

        try {
            // unit_config 在 H2 里是 VARCHAR，取出来是 String；容错处理已被解析成 List 的情况
            Object raw = goods.get("unitConfig");
            if (raw == null) {
                return new UnitProfile(smallUnit, mediumUnit, largeUnit,
                        mediumEnabled, largeEnabled, smallStandardPrice, mediumStandardPrice, largeStandardPrice);
            }

            List<Object> cfg;
            if (raw instanceof List) {
                cfg = (List<Object>) raw;
            } else {
                String json = String.valueOf(raw).trim();
                cfg = json.isEmpty() ? Collections.emptyList()
                        : JSON_MAPPER.readValue(json, List.class);
            }

            String[] unitNames = new String[3];
            BigDecimal[] prices = new BigDecimal[3];
            boolean[] enabled = new boolean[]{true, false, false}; // 小单位默认启用

            for (int i = 0; i < Math.min(cfg.size(), 3); i++) {
                Map<String, Object> u = (Map<String, Object>) cfg.get(i);
                if (u != null) {
                    unitNames[i] = toString(u.get("unitName"));
                    Object en = u.get("enabled");
                    enabled[i] = en == null || Boolean.TRUE.equals(en) || "true".equals(String.valueOf(en));
                    prices[i] = toDecimal(u.get("standardPrice"));
                }
            }
            // 小单位始终启用
            enabled[0] = true;

            if (unitNames[0] != null && !unitNames[0].isEmpty()) smallUnit = unitNames[0];
            if (unitNames[1] != null) mediumUnit = unitNames[1];
            if (unitNames[2] != null) largeUnit = unitNames[2];
            mediumEnabled = enabled[1];
            largeEnabled = enabled[2];
            if (prices[0] != null) smallStandardPrice = prices[0];
            // 停用单位的标价在商品档案里通常是占位 0，落库存 NULL，
            // 避免下游把 0.00 当成「该单位真实售价为 0 元」
            mediumStandardPrice = mediumEnabled ? prices[1] : null;
            largeStandardPrice = largeEnabled ? prices[2] : null;

        } catch (Exception ignore) {
            // 解析失败时使用 base_goods 基础字段兜底
        }

        return new UnitProfile(smallUnit, mediumUnit, largeUnit,
                mediumEnabled, largeEnabled, smallStandardPrice, mediumStandardPrice, largeStandardPrice);
    }

    private static String toString(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String validRange(CustomerPriceAdjustRequest request) {
        return validRange(request.validType(), request.validFrom(), request.validTo());
    }

    /** 价格有效期描述：长期有效，或「起 ~ 止」 */
    private String validRange(String validType, String validFrom, String validTo) {
        if ("RANGE".equals(validType)) {
            return (validFrom == null ? "" : validFrom) + " ~ " + (validTo == null ? "" : validTo);
        }
        return "长期有效";
    }

    // ==================== 请求/响应 DTO ====================

    public record CustomerPriceAdjustRequest(
            @NotBlank String customerId,
            @NotBlank String effectiveMode,
            String effectiveTime,
            @NotBlank String validType,
            String validFrom,
            String validTo,
            String remark,
            @NotEmpty List<CustomerPriceAdjustDetailRequest> details) {
    }

    public record CustomerPriceAdjustDetailRequest(
            @NotBlank String goodsId,
            @NotBlank String unitId,
            @NotNull @PositiveOrZero BigDecimal currentPrice,
            BigDecimal smallCurrentPrice,
            BigDecimal mediumCurrentPrice,
            BigDecimal largeCurrentPrice) {
    }

    /** 编辑请求：比新建多一个 adjustId，用于定位要改的单据 */
    public record CustomerPriceAdjustUpdateRequest(
            @NotBlank String adjustId,
            @NotBlank String customerId,
            @NotBlank String effectiveMode,
            String effectiveTime,
            @NotBlank String validType,
            String validFrom,
            String validTo,
            String remark,
            @NotEmpty List<CustomerPriceAdjustDetailRequest> details) {
    }

    public record AuditRequest(@NotBlank String bizId, String remark) {
    }

    public record StopPriceRequest(@NotEmpty List<String> priceIds, @NotBlank String reason) {
    }
}