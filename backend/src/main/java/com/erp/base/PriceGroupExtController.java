package com.erp.base;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 多价格组管理：
 * F2 价格组商品查询 —— /price-group-item/*
 * F3 价格组调价单   —— /price-adjust-order/*
 * F4 价格组变价查询 —— /price-change-log/*
 *
 * 参见 PRD v2.0（erp-multi-price-group），本类是最小可用后端。
 */
@RestController
@RequestMapping("/base")
public class PriceGroupExtController {

    private final JdbcTemplate jdbc;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    public PriceGroupExtController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ============================================================
    // F2 价格组商品查询
    // ============================================================

    /** 分页：可按价格组编码 / 商品编码 / 是否启用过滤。 */
    @PostMapping("/price-group-item/page")
    public ApiResponse<PageResult<Map<String, Object>>> itemPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT i.id, i.goods_code, i.goods_name, i.price_group_code, g.price_group_name,
                       i.unit_level, i.price, i.is_active, i.created_at, i.updated_at,
                       gd.spec, gd.barcode, gd.standard_price, gd.unit_config
                FROM base_price_group_item i
                LEFT JOIN base_price_group g ON g.price_group_code = i.price_group_code
                LEFT JOIN base_goods gd ON gd.goods_code = i.goods_code
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String pg = trim(filters.get("priceGroupCode"));
        if (!pg.isEmpty()) { sql.append(" AND i.price_group_code = ?"); args.add(pg); }
        String goods = trim(filters.get("goodsCode"));
        if (!goods.isEmpty()) { sql.append(" AND (i.goods_code LIKE ? OR i.goods_name LIKE ?)"); args.add("%" + goods + "%"); args.add("%" + goods + "%"); }
        // 支持导入查询：goodsCodeList 是以逗号分隔的商品编码集合
        String codeList = trim(filters.get("goodsCodeList"));
        if (!codeList.isEmpty()) {
            String[] codes = codeList.split(",");
            String placeholders = String.join(",", java.util.Collections.nCopies(codes.length, "?"));
            sql.append(" AND i.goods_code IN (").append(placeholders).append(")");
            for (String c : codes) args.add(c.trim());
        }
        Object activeRaw = filters.get("isActive");
        if (activeRaw != null && !String.valueOf(activeRaw).isBlank()) {
            sql.append(" AND i.is_active = ?");
            args.add(Boolean.parseBoolean(String.valueOf(activeRaw)));
        }
        sql.append(" ORDER BY i.price_group_code, i.goods_code, i.unit_level");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = toCamel(r);
            // 从 base_goods.unit_config（JSON 数组，索引 0=小 1=中 2=大）提取对应级别的 standardPrice
            Object cfg = row.remove("unitConfig");
            Object level = row.get("unitLevel");
            Object smallStd = row.remove("standardPrice"); // 基本单位标价，作为小单位（level=1）兜底
            BigDecimal std = extractUnitStandardPrice(cfg, level, smallStd);
            row.put("standardPrice", std);
            out.add(row);
        }
        return ApiResponse.ok(PageResult.of(out, request));
    }

    /** 从 unit_config JSON 里按级别 1/2/3 取 standardPrice；解析失败返回 fallback（基本单位标价）。 */
    private BigDecimal extractUnitStandardPrice(Object rawCfg, Object levelObj, Object fallbackSmall) {
        int level = 1;
        if (levelObj instanceof Number n) level = n.intValue();
        else if (levelObj != null) { try { level = Integer.parseInt(String.valueOf(levelObj)); } catch (Exception ignore) {} }
        BigDecimal fb = toBig(fallbackSmall);
        if (rawCfg == null) return level == 1 ? fb : BigDecimal.ZERO;
        try {
            String json = String.valueOf(rawCfg);
            if (json.isBlank() || !json.trim().startsWith("[")) return level == 1 ? fb : BigDecimal.ZERO;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> units = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
            int idx = level - 1;
            if (idx < 0 || idx >= units.size()) return level == 1 ? fb : BigDecimal.ZERO;
            Object p = units.get(idx).get("standardPrice");
            BigDecimal v = toBig(p);
            if (v.signum() == 0 && level == 1) return fb;
            return v;
        } catch (Exception e) {
            return level == 1 ? fb : BigDecimal.ZERO;
        }
    }

    private BigDecimal toBig(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(v)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** 启用/停用单条价格。 */
    @PostMapping("/price-group-item/toggle")
    public ApiResponse<Map<String, Object>> itemToggle(@RequestBody Map<String, Object> body) {
        String id = trim(body.get("id"));
        Object activeRaw = body.get("isActive");
        if (id.isEmpty()) return ApiResponse.fail("400", "缺少 id");
        boolean active = Boolean.parseBoolean(String.valueOf(activeRaw));
        int rows = jdbc.update("UPDATE base_price_group_item SET is_active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", active, id);
        if (rows == 0) return ApiResponse.fail("404", "价格记录不存在");
        return ApiResponse.ok(GenericResult.row("id", id, "isActive", active, "success", true));
    }

    /** 汇总每个价格组已启用商品数（供价格组页 goodsCount 使用）。 */
    @PostMapping("/price-group-item/goods-count")
    public ApiResponse<Map<String, Integer>> itemGoodsCount() {
        Map<String, Integer> map = new LinkedHashMap<>();
        // 一条价格记录 = (goods, group, unitLevel) 三元组；商品数按 goods 去重
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT price_group_code, COUNT(DISTINCT goods_code) AS c FROM base_price_group_item WHERE is_active = TRUE GROUP BY price_group_code");
        for (Map<String, Object> r : rows) {
            String code = String.valueOf(r.getOrDefault("price_group_code", r.get("PRICE_GROUP_CODE")));
            Object c = r.getOrDefault("c", r.get("C"));
            if (c instanceof Number n) map.put(code, n.intValue());
        }
        return ApiResponse.ok(map);
    }

    // ============================================================
    // F3 价格组调价单
    // ============================================================

    @PostMapping("/price-adjust-order/page")
    public ApiResponse<PageResult<Map<String, Object>>> orderPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT order_id, order_no, price_group_code, price_group_name, goods_count,
                       status, remark, create_user, create_time, submit_user, submit_time,
                       audit_user, audit_time, reject_reason
                FROM base_price_adjust_order WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String no = trim(filters.get("orderNo"));
        if (!no.isEmpty()) { sql.append(" AND order_no LIKE ?"); args.add("%" + no + "%"); }
        String pg = trim(filters.get("priceGroupCode"));
        if (!pg.isEmpty()) { sql.append(" AND price_group_code = ?"); args.add(pg); }
        String status = trim(filters.get("status"));
        if (!status.isEmpty()) { sql.append(" AND status = ?"); args.add(status); }
        String creator = trim(filters.get("createUser"));
        if (!creator.isEmpty()) { sql.append(" AND create_user LIKE ?"); args.add("%" + creator + "%"); }
        sql.append(" ORDER BY create_time DESC");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(toCamel(r));
        return ApiResponse.ok(PageResult.of(out, request));
    }

    @PostMapping("/price-adjust-order/detail")
    public ApiResponse<Map<String, Object>> orderDetail(@RequestBody Map<String, Object> body) {
        String id = trim(body.get("orderId"));
        if (id.isEmpty()) return ApiResponse.fail("400", "缺少 orderId");
        List<Map<String, Object>> heads = jdbc.queryForList(
                "SELECT order_id, order_no, price_group_code, price_group_name, goods_count, status, remark, reject_reason, create_user, create_time, submit_user, submit_time, audit_user, audit_time FROM base_price_adjust_order WHERE order_id = ?",
                id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调价单不存在");
        Map<String, Object> head = toCamel(heads.get(0));
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT id, goods_code, goods_name, small_new_price, medium_new_price, large_new_price, small_old_price, medium_old_price, large_old_price FROM base_price_adjust_order_item WHERE order_id = ?",
                id);
        List<Map<String, Object>> mappedItems = new ArrayList<>();
        for (Map<String, Object> r : items) mappedItems.add(toCamel(r));
        head.put("items", mappedItems);
        return ApiResponse.ok(head);
    }

    /** 新建/编辑草稿。审核走独立端点 approve/reject。 */
    @SuppressWarnings("unchecked")
    @PostMapping("/price-adjust-order/save")
    public ApiResponse<Map<String, Object>> orderSave(@RequestBody Map<String, Object> body) {
        String orderId = trim(body.get("orderId"));
        String groupCode = trim(body.get("priceGroupCode"));
        // 新模式下 price_group_code 可为空（调价单不再绑定单一价格组）
        String user = currentUser();
        List<Map<String, Object>> items = body.get("items") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        Boolean goodsLocked = body.get("goodsLocked") instanceof Boolean b ? b : Boolean.FALSE;
        String goodsCode = trim(body.get("goodsCode"));

        if (orderId.isEmpty()) {
            orderId = "PADJ" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String orderNo = generateOrderNo();
            jdbc.update("""
                    INSERT INTO base_price_adjust_order
                    (order_id, order_no, price_group_code, price_group_name, goods_count, goods_code, goods_name, goods_locked, status, remark, create_user)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                    """,
                    orderId, orderNo, emptyToNull(groupCode), null,
                    items.size(), emptyToNull(goodsCode), trim(body.get("goodsName")),
                    goodsLocked, trim(body.get("remark")), user);
        } else {
            Integer status = jdbc.queryForObject("SELECT COUNT(*) FROM base_price_adjust_order WHERE order_id = ? AND status = 'DRAFT'",
                    Integer.class, orderId);
            if (status == null || status == 0) return ApiResponse.fail("400", "只有草稿状态可修改");
            jdbc.update("""
                    UPDATE base_price_adjust_order
                    SET price_group_code = ?, goods_count = ?, remark = ?, goods_locked = ?
                    WHERE order_id = ?
                    """, emptyToNull(groupCode), items.size(), trim(body.get("remark")), goodsLocked, orderId);
            jdbc.update("DELETE FROM base_price_adjust_order_item WHERE order_id = ?", orderId);
        }
        // 明细
        for (Map<String, Object> it : items) {
            String itemId = "PADJI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            // 价格组价格 JSON
            Object pgPrices = it.get("priceGroupPrices");
            String pgJson = null;
            if (pgPrices != null) {
                try { pgJson = MAPPER.writeValueAsString(pgPrices); } catch (Exception e) { pgJson = null; }
            }
            jdbc.update("""
                    INSERT INTO base_price_adjust_order_item
                    (id, order_id, goods_code, goods_name, unit_level,
                     standard_price_new, standard_price_old,
                     purchase_price_new, purchase_price_old,
                     min_price_new, min_price_old,
                     suggest_retail_price_new, suggest_retail_price_old,
                     price_group_prices,
                     small_new_price, medium_new_price, large_new_price,
                     small_old_price, medium_old_price, large_old_price)
                    VALUES (?, ?, ?, ?, ?,
                     ?, ?, ?, ?, ?, ?, ?, ?,
                     ?,
                     ?, ?, ?, ?, ?, ?)
                    """,
                    itemId, orderId,
                    trim(it.get("goodsCode")), trim(it.get("goodsName")),
                    intOr(it.get("unitLevel"), 1),
                    numOrNull(it.get("standardPriceNew")), numOrNull(it.get("standardPriceOld")),
                    numOrNull(it.get("purchasePriceNew")), numOrNull(it.get("purchasePriceOld")),
                    numOrNull(it.get("minPriceNew")), numOrNull(it.get("minPriceOld")),
                    numOrNull(it.get("suggestRetailPriceNew")), numOrNull(it.get("suggestRetailPriceOld")),
                    pgJson,
                    // 老字段：从 old/new price 组中取（兼容），新模式下为 NULL
                    numOrNull(it.get("smallNewPrice")), numOrNull(it.get("mediumNewPrice")), numOrNull(it.get("largeNewPrice")),
                    numOrNull(it.get("smallOldPrice")), numOrNull(it.get("mediumOldPrice")), numOrNull(it.get("largeOldPrice")));
        }
        return ApiResponse.ok(GenericResult.row("orderId", orderId, "success", true));
    }

    /** 草稿 → 待审核。 */
    @PostMapping("/price-adjust-order/submit")
    public ApiResponse<Map<String, Object>> orderSubmit(@RequestBody Map<String, Object> body) {
        String id = trim(body.get("orderId"));
        int rows = jdbc.update(
                "UPDATE base_price_adjust_order SET status='PENDING', submit_user=?, submit_time=CURRENT_TIMESTAMP WHERE order_id=? AND status='DRAFT'",
                currentUser(), id);
        if (rows == 0) return ApiResponse.fail("400", "调价单不存在或非草稿状态");
        return ApiResponse.ok(GenericResult.row("orderId", id, "success", true));
    }

    /** 待审核 → 已审核：应用价格 + 写日志（支持新旧两种模式）。 */
    @SuppressWarnings("unchecked")
    @PostMapping("/price-adjust-order/approve")
    public ApiResponse<Map<String, Object>> orderApprove(@RequestBody Map<String, Object> body) {
        String id = trim(body.get("orderId"));
        List<Map<String, Object>> heads = jdbc.queryForList(
                "SELECT order_no, price_group_code, remark FROM base_price_adjust_order WHERE order_id = ? AND status = 'PENDING'",
                id);
        if (heads.isEmpty()) return ApiResponse.fail("400", "调价单不存在或非待审核状态");
        Map<String, Object> head = heads.get(0);
        String orderNo = String.valueOf(head.getOrDefault("order_no", head.get("ORDER_NO")));
        String groupCode = String.valueOf(head.getOrDefault("price_group_code", head.get("PRICE_GROUP_CODE")));
        String remark = String.valueOf(head.getOrDefault("remark", head.get("REMARK")));
        String user = currentUser();

        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM base_price_adjust_order_item WHERE order_id = ?", id);

        java.util.concurrent.atomic.AtomicInteger applied = new java.util.concurrent.atomic.AtomicInteger(0);
        for (Map<String, Object> raw : items) {
            Map<String, Object> it = toCamel(raw);
            String gc = String.valueOf(it.getOrDefault("goodsCode", ""));
            String gn = String.valueOf(it.getOrDefault("goodsName", ""));
            int unitLevel = it.get("unitLevel") instanceof Number n ? n.intValue() : 1;

            // === 新多价格模式：标准售价/参考进价/最低价/建议零售价 ===
            applyUnitConfigPrice(gc, gn, unitLevel, "standardPrice", it.get("standardPriceNew"), orderNo, user, remark);
            applyUnitConfigPrice(gc, gn, unitLevel, "purchasePrice", it.get("purchasePriceNew"), orderNo, user, remark);
            applyUnitConfigPrice(gc, gn, unitLevel, "minPrice", it.get("minPriceNew"), orderNo, user, remark);
            applyUnitConfigPrice(gc, gn, unitLevel, "suggestRetailPrice", it.get("suggestRetailPriceNew"), orderNo, user, remark);

            // 同步小单位价格到 base_goods 主表（向后兼容）
            if (unitLevel == 1) {
                syncGoodsHeadPrice(gc, it);
            }

            // === 价格组价格 ===
            String pgJson = String.valueOf(it.getOrDefault("priceGroupPrices", ""));
            if (!pgJson.isEmpty() && !"null".equals(pgJson)) {
                try {
                    java.util.List<Map<String, Object>> pgList = (java.util.List<Map<String, Object>>) MAPPER.readValue(pgJson, java.util.List.class);
                    if (pgList != null) {
                        for (Map<String, Object> pg : pgList) {
                            String pgCode = String.valueOf(pg.getOrDefault("pgCode", ""));
                            String pgName = String.valueOf(pg.getOrDefault("pgName", ""));
                            applyOnePrice(gc, gn, pgCode, pgName, unitLevel, pg.get("newPrice"), orderNo, id, user, remark, applied::get);
                        }
                    }
                } catch (Exception e) { /* JSON parse error — skip */ }
            }

            // === 旧模式兼容（small/medium/large_new_price 非空时按旧逻辑） ===
            if (groupCode != null && !groupCode.isEmpty() && !"null".equals(groupCode)) {
                applyOnePrice(gc, gn, groupCode, "", unitLevel,
                        unitLevel == 1 ? it.get("smallNewPrice") : unitLevel == 2 ? it.get("mediumNewPrice") : it.get("largeNewPrice"),
                        orderNo, id, user, remark, applied::get);
            }
            applied.incrementAndGet();
        }

        jdbc.update("UPDATE base_price_adjust_order SET status='APPROVED', audit_user=?, audit_time=CURRENT_TIMESTAMP WHERE order_id=?",
                user, id);
        return ApiResponse.ok(GenericResult.row("orderId", id, "applied", applied.get(), "success", true));
    }

    /**
     * 将单条价格写入 base_goods.unit_config JSON 中指定单位级别。
     * 同时写变价日志 base_price_change_log。
     */
    private void applyUnitConfigPrice(String goodsCode, String goodsName, int unitLevel,
                                       String priceKey, Object newPriceRaw,
                                       String orderNo, String user, String remark) {
        Double np = numOrNull(newPriceRaw);
        if (np == null) return;   // 未填，跳过

        // 读当前 unit_config
        String currentJson = jdbc.queryForObject(
                "SELECT unit_config FROM base_goods WHERE goods_code = ?",
                String.class, goodsCode);
        if (currentJson == null || currentJson.isBlank()) currentJson = "[]";

        try {
            com.fasterxml.jackson.databind.JsonNode arr = MAPPER.readTree(currentJson);
            if (!arr.isArray() || arr.size() <= unitLevel - 1) return;
            com.fasterxml.jackson.databind.node.ObjectNode unitNode = (com.fasterxml.jackson.databind.node.ObjectNode) arr.get(unitLevel - 1);
            Double oldPrice = unitNode.has(priceKey) && !unitNode.get(priceKey).isNull()
                    ? unitNode.get(priceKey).asDouble() : null;
            // 价格未变则跳过
            if (oldPrice != null && Math.abs(oldPrice - np) < 0.0001) return;

            unitNode.put(priceKey, BigDecimal.valueOf(np).setScale(4, java.math.RoundingMode.HALF_UP).doubleValue());
            String newJson = MAPPER.writeValueAsString(arr);
            jdbc.update("UPDATE base_goods SET unit_config = ? WHERE goods_code = ?", newJson, goodsCode);

            // 变价日志
            writePriceChangeLog(goodsCode, goodsName, null, unitLevel, priceKey,
                    oldPrice == null ? null : BigDecimal.valueOf(oldPrice),
                    BigDecimal.valueOf(np), orderNo, user, remark);
        } catch (Exception e) {
            // JSON parse/write error — skip this price
        }
    }

    /** 同步小单位价格到 base_goods 主表字段（向后兼容旧查询）。 */
    private void syncGoodsHeadPrice(String goodsCode, Map<String, Object> it) {
        Double sp = numOrNull(it.get("standardPriceNew"));
        Double pp = numOrNull(it.get("purchasePriceNew"));
        Double mp = numOrNull(it.get("minPriceNew"));
        Double srp = numOrNull(it.get("suggestRetailPriceNew"));
        if (sp != null || pp != null || mp != null || srp != null) {
            StringBuilder sql = new StringBuilder("UPDATE base_goods SET ");
            java.util.List<Object> args = new java.util.ArrayList<>();
            if (sp != null) { sql.append("standard_price = ?, "); args.add(sp); }
            if (pp != null) { sql.append("latest_purchase_price = ?, "); args.add(pp); }
            if (mp != null) { sql.append("min_sale_price = ?, "); args.add(mp); }
            if (srp != null) { sql.append("suggested_retail_price = ?, "); args.add(srp); }
            sql.setLength(sql.length() - 2); // trim trailing ", "
            sql.append(" WHERE goods_code = ?");
            args.add(goodsCode);
            jdbc.update(sql.toString(), args.toArray());
        }
    }

    /**
     * 写变价日志 —— 不绑定价格组的价格变动（标准售价/参考进价/最低价/建议零售价）。
     */
    private void writePriceChangeLog(String goodsCode, String goodsName, String pgCode, int unitLevel,
                                      String priceKey, BigDecimal oldPrice, BigDecimal newPrice,
                                      String orderNo, String user, String remark) {
        String logId = "PCL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String changeType = "ADJUST_UPDATE";
        String unitLabel = unitLevel == 1 ? "小单位" : unitLevel == 2 ? "中单位" : "大单位";
        jdbc.update("""
                INSERT INTO base_price_change_log
                (id, order_id, order_no, goods_code, goods_name, price_group_code, unit_level,
                 old_price, new_price, change_type, operator, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                logId, "", orderNo, goodsCode, goodsName, pgCode, unitLevel,
                oldPrice, newPrice, changeType, user,
                (remark == null ? "" : remark) + " [" + priceKey + " " + unitLabel + "]");
    }

    /** 待审核 → 已驳回。 */
    @PostMapping("/price-adjust-order/reject")
    public ApiResponse<Map<String, Object>> orderReject(@RequestBody Map<String, Object> body) {
        String id = trim(body.get("orderId"));
        String reason = trim(body.get("rejectReason"));
        int rows = jdbc.update(
                "UPDATE base_price_adjust_order SET status='REJECTED', audit_user=?, audit_time=CURRENT_TIMESTAMP, reject_reason=? WHERE order_id=? AND status='PENDING'",
                currentUser(), reason, id);
        if (rows == 0) return ApiResponse.fail("400", "调价单不存在或非待审核状态");
        return ApiResponse.ok(GenericResult.row("orderId", id, "success", true));
    }

    /** 删除草稿。 */
    @PostMapping("/price-adjust-order/delete")
    public ApiResponse<Map<String, Object>> orderDelete(@RequestBody Map<String, Object> body) {
        String id = trim(body.get("orderId"));
        int rows = jdbc.update("DELETE FROM base_price_adjust_order WHERE order_id = ? AND status = 'DRAFT'", id);
        if (rows == 0) return ApiResponse.fail("400", "只有草稿状态可删除");
        jdbc.update("DELETE FROM base_price_adjust_order_item WHERE order_id = ?", id);
        return ApiResponse.ok(GenericResult.row("orderId", id, "success", true));
    }

    // ============================================================
    // F4 变价日志查询
    // ============================================================

    @PostMapping("/price-change-log/page")
    public ApiResponse<PageResult<Map<String, Object>>> logPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.order_id, l.order_no, l.goods_code, l.goods_name,
                       l.price_group_code,
                       COALESCE(l.price_group_name,
                         CASE
                           WHEN l.remark LIKE '%[standardPrice%' THEN '标准售价'
                           WHEN l.remark LIKE '%[purchasePrice%' THEN '参考进价'
                           WHEN l.remark LIKE '%[minPrice%' THEN '最低价'
                           WHEN l.remark LIKE '%[suggestRetailPrice%' THEN '建议零售价'
                           ELSE NULL
                         END
                       ) AS price_group_name,
                       l.unit_level,
                       l.old_price, l.new_price, l.change_type, l.operator, l.remark, l.created_at,
                       gd.spec, gd.barcode AS base_barcode, gd.brand_name, gd.unit_config
                FROM base_price_change_log l
                LEFT JOIN base_goods gd ON gd.goods_code = l.goods_code
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        String pg = trim(filters.get("priceGroupCode"));
        if (!pg.isEmpty()) {
            // 基础价格类型按 remark 模糊匹配（商品综合调价不写 price_group_code）
            if ("STANDARD".equals(pg) || "标准售价".equals(pg)) {
                sql.append(" AND (l.price_group_code = ? OR l.remark LIKE '%[standardPrice%')");
                args.add(pg);
            } else if ("PURCHASE".equals(pg) || "参考进价".equals(pg)) {
                sql.append(" AND (l.price_group_code = ? OR l.remark LIKE '%[purchasePrice%')");
                args.add(pg);
            } else if ("MIN".equals(pg) || "最低价".equals(pg)) {
                sql.append(" AND (l.price_group_code = ? OR l.remark LIKE '%[minPrice%')");
                args.add(pg);
            } else if ("SUGGEST_RETAIL".equals(pg) || "建议零售价".equals(pg)) {
                sql.append(" AND (l.price_group_code = ? OR l.remark LIKE '%[suggestRetailPrice%')");
                args.add(pg);
            } else {
                sql.append(" AND l.price_group_code = ?"); args.add(pg);
            }
        }
        String goods = trim(filters.get("goodsCode"));
        if (!goods.isEmpty()) { sql.append(" AND (l.goods_code LIKE ? OR l.goods_name LIKE ?)"); args.add("%" + goods + "%"); args.add("%" + goods + "%"); }
        // 支持导入查询：goodsCodeList 是以逗号分隔的商品编码集合
        String codeList = trim(filters.get("goodsCodeList"));
        if (!codeList.isEmpty()) {
            String[] codes = codeList.split(",");
            List<String> nonBlank = new ArrayList<>();
            for (String c : codes) { String t = c.trim(); if (!t.isEmpty()) nonBlank.add(t); }
            if (!nonBlank.isEmpty()) {
                String placeholders = String.join(",", java.util.Collections.nCopies(nonBlank.size(), "?"));
                sql.append(" AND l.goods_code IN (").append(placeholders).append(")");
                args.addAll(nonBlank);
            }
        }
        String orderNo = trim(filters.get("orderNo"));
        if (!orderNo.isEmpty()) { sql.append(" AND l.order_no LIKE ?"); args.add("%" + orderNo + "%"); }
        String operator = trim(filters.get("operator"));
        if (!operator.isEmpty()) { sql.append(" AND l.operator LIKE ?"); args.add("%" + operator + "%"); }
        String brand = trim(filters.get("brandName"));
        if (!brand.isEmpty()) { sql.append(" AND gd.brand_name = ?"); args.add(brand); }
        // 变价时间段：dateFrom / dateTo（yyyy-MM-dd）
        String dateFrom = trim(filters.get("dateFrom"));
        if (!dateFrom.isEmpty()) { sql.append(" AND l.created_at >= ?"); args.add(dateFrom + " 00:00:00"); }
        String dateTo = trim(filters.get("dateTo"));
        if (!dateTo.isEmpty()) { sql.append(" AND l.created_at <= ?"); args.add(dateTo + " 23:59:59"); }
        sql.append(" ORDER BY l.created_at DESC");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = toCamel(r);
            // 按 unit_level 取对应单位条码：1=小/基本 → base_barcode；2/3 从 unit_config[idx].barcode
            Object cfgRaw = row.remove("unitConfig");
            Object baseBc = row.remove("baseBarcode");
            int lv = 1;
            Object lo = row.get("unitLevel");
            if (lo instanceof Number n) lv = n.intValue();
            else if (lo != null) { try { lv = Integer.parseInt(String.valueOf(lo)); } catch (Exception ignore) {} }
            String bc = String.valueOf(baseBc == null ? "" : baseBc);
            if (lv != 1 && cfgRaw != null) {
                try {
                    String json = String.valueOf(cfgRaw);
                    if (json.startsWith("[")) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> units = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
                        int idx = lv - 1;
                        if (idx >= 0 && idx < units.size()) {
                            Object b = units.get(idx).get("barcode");
                            if (b != null && !String.valueOf(b).isBlank()) bc = String.valueOf(b);
                        }
                    }
                } catch (Exception ignore) {}
            }
            row.put("barcode", bc);
            out.add(row);
        }
        return ApiResponse.ok(PageResult.of(out, request));
    }

    // ============================================================
    // helpers
    // ============================================================
    private String generateOrderNo() {
        String date = LocalDateTime.now().toString().substring(0, 10).replace("-", "");
        Integer max = jdbc.queryForObject(
                "SELECT COUNT(*) FROM base_price_adjust_order WHERE order_no LIKE ?",
                Integer.class, "TJ" + date + "%");
        int next = (max == null ? 0 : max) + 1;
        return String.format("TJ%s%03d", date, next);
    }

    private void applyOnePrice(String goodsCode, String goodsName, String groupCode, String groupName,
                                int unitLevel, Object newPriceRaw, String orderNo, String orderId,
                                String user, String remark, java.util.function.Supplier<Integer> counter) {
        Double np = numOrNull(newPriceRaw);
        if (np == null) return;
        // upsert 到 base_price_group_item
        List<Map<String, Object>> exist = jdbc.queryForList(
                "SELECT id, price FROM base_price_group_item WHERE goods_code = ? AND price_group_code = ? AND unit_level = ?",
                goodsCode, groupCode, unitLevel);
        Double oldPrice = null;
        if (exist.isEmpty()) {
            String id = "PGI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("""
                    INSERT INTO base_price_group_item
                    (id, goods_code, goods_name, price_group_code, unit_level, price, is_active)
                    VALUES (?, ?, ?, ?, ?, ?, TRUE)
                    """, id, goodsCode, goodsName, groupCode, unitLevel, np);
        } else {
            Map<String, Object> r = exist.get(0);
            Object rid = r.getOrDefault("id", r.get("ID"));
            Object p = r.getOrDefault("price", r.get("PRICE"));
            if (p instanceof Number n) oldPrice = n.doubleValue();
            jdbc.update("UPDATE base_price_group_item SET price = ?, is_active = TRUE, updated_at = CURRENT_TIMESTAMP WHERE id = ?", np, rid);
        }
        // 变价日志
        String logId = "PCL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String changeType = oldPrice == null ? "ADJUST_INIT" : "ADJUST_UPDATE";
        jdbc.update("""
                INSERT INTO base_price_change_log
                (id, order_id, order_no, goods_code, goods_name, price_group_code, price_group_name,
                 unit_level, old_price, new_price, change_type, operator, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, logId, orderId, orderNo, goodsCode, goodsName, groupCode, groupName,
                unitLevel, oldPrice, np, changeType, user, remark);
    }

    private static Double numOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static String trim(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    private static String emptyToNull(String v) { return v == null || v.isBlank() ? null : v; }

    private static int intOr(Object v, int dft) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) { try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { } }
        return dft;
    }

    private static String currentUser() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) return "system";
            String name = auth.getName();
            return name == null || name.isBlank() ? "system" : name;
        } catch (Exception e) { return "system"; }
    }

    private static Map<String, Object> toCamel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String key = e.getKey();
            String lower = key.toLowerCase(Locale.ROOT);
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
}
