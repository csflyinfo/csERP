package com.erp.base;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 商品调价单 REST 端点 —— 与原价格组调价单独立。
 *
 * <p>与原 {@code base_price_adjust_order} 的区别：
 * <ul>
 *   <li>不绑定价格组 —— 按<b>商品</b>维度同时调多种价格</li>
 *   <li>每种价格类型的原价/新价都记录在明细行中</li>
 *   <li>审核后写回 {@code base_goods.unit_config} JSON（标准售价/参考进价/最低价/建议零售价）
 *       和 {@code base_price_group_item}（价格组价格）</li>
 * </ul>
 */
@RestController
@RequestMapping("/base")
public class GoodsPriceAdjustController {

    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    public GoodsPriceAdjustController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ========== 列表 ==========

    @PostMapping("/goods-price-adjust/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("SELECT * FROM base_goods_price_adjust_order WHERE 1=1");
        List<Object> args = new ArrayList<>();
        addFilter(sql, args, "order_no", f.get("orderNo"), "LIKE");
        addFilter(sql, args, "goods_code", f.get("goodsCode"), "LIKE");
        addFilter(sql, args, "status", f.get("status"), "=");
        sql.append(" ORDER BY create_time DESC, order_no DESC");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        return ApiResponse.ok(PageResult.of(rows.stream().map(GoodsPriceAdjustController::toCamel).toList(), request));
    }

    // ========== 详情 ==========

    @PostMapping("/goods-price-adjust/detail")
    public ApiResponse<Map<String, Object>> detail(@RequestBody Map<String, Object> body) {
        String id = trim(body.get("orderId"));
        List<Map<String, Object>> heads = jdbc.queryForList(
                "SELECT * FROM base_goods_price_adjust_order WHERE order_id = ?", id);
        if (heads.isEmpty()) return ApiResponse.fail("400", "调价单不存在");
        Map<String, Object> head = toCamel(heads.get(0));
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM base_goods_price_adjust_order_item WHERE order_id = ?", id);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> row = toCamel(it);
            // 解析价格组价格 JSON
            String pgRaw = trim(it.get("price_group_prices"));
            if (!pgRaw.isEmpty() && !"null".equals(pgRaw)) {
                try { row.put("priceGroupPrices", MAPPER.readValue(pgRaw, List.class)); } catch (Exception e) { }
            }
            mapped.add(row);
        }
        head.put("items", mapped);
        return ApiResponse.ok(head);
    }

    // ========== 保存 ==========

    @SuppressWarnings("unchecked")
    @PostMapping("/goods-price-adjust/save")
    @Transactional
    public ApiResponse<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        String orderId = trim(body.get("orderId"));
        String user = currentUser();
        List<Map<String, Object>> items = body.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        boolean goodsLocked = Boolean.TRUE.equals(body.get("goodsLocked"));
        String goodsCode = trim(body.get("goodsCode"));

        if (orderId.isEmpty()) {
            orderId = "GPA" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String orderNo = "SPTJ" + LocalDate.now().format(YMD) + nextSeq("SPTJ");
            jdbc.update("""
                    INSERT INTO base_goods_price_adjust_order
                    (order_id, order_no, goods_code, goods_name, goods_locked, goods_count, status, remark, create_user)
                    VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                    """, orderId, orderNo, emptyToNull(goodsCode),
                    trim(body.get("goodsName")), goodsLocked, items.size(),
                    trim(body.get("remark")), user);
        } else {
            Integer st = jdbc.queryForObject("SELECT COUNT(*) FROM base_goods_price_adjust_order WHERE order_id = ? AND status = 'DRAFT'", Integer.class, orderId);
            if (st == null || st == 0) return ApiResponse.fail("400", "只有草稿状态可修改");
            if (goodsLocked) {
                jdbc.update("UPDATE base_goods_price_adjust_order SET goods_count = ?, remark = ? WHERE order_id = ?",
                        items.size(), trim(body.get("remark")), orderId);
            } else {
                jdbc.update("UPDATE base_goods_price_adjust_order SET goods_count = ?, remark = ? WHERE order_id = ?",
                        items.size(), trim(body.get("remark")), orderId);
            }
            jdbc.update("DELETE FROM base_goods_price_adjust_order_item WHERE order_id = ?", orderId);
        }

        for (Map<String, Object> it : items) {
            String itemId = "GPAI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String pgJson = toJson(it.get("priceGroupPrices"));
            jdbc.update("""
                    INSERT INTO base_goods_price_adjust_order_item
                    (id, order_id, goods_code, goods_name, unit_level,
                     standard_price_new, standard_price_old,
                     purchase_price_new, purchase_price_old,
                     min_price_new, min_price_old,
                     suggest_retail_price_new, suggest_retail_price_old,
                     price_group_prices)
                    VALUES (?, ?, ?, ?, ?,
                     ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, itemId, orderId,
                    trim(it.get("goodsCode")), trim(it.get("goodsName")),
                    intOr(it.get("unitLevel"), 1),
                    numOrNull(it.get("standardPriceNew")), numOrNull(it.get("standardPriceOld")),
                    numOrNull(it.get("purchasePriceNew")), numOrNull(it.get("purchasePriceOld")),
                    numOrNull(it.get("minPriceNew")), numOrNull(it.get("minPriceOld")),
                    numOrNull(it.get("suggestRetailPriceNew")), numOrNull(it.get("suggestRetailPriceOld")),
                    pgJson);
        }
        return ApiResponse.ok(GenericResult.row("orderId", orderId, "success", true));
    }

    // ========== 提交 ==========

    @PostMapping("/goods-price-adjust/submit")
    public ApiResponse<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String id = trim(body.get("orderId"));
        int rows = jdbc.update("UPDATE base_goods_price_adjust_order SET status='PENDING', submit_user=?, submit_time=CURRENT_TIMESTAMP WHERE order_id=? AND status='DRAFT'",
                currentUser(), id);
        if (rows == 0) return ApiResponse.fail("400", "调价单不存在或非草稿状态");
        return ApiResponse.ok(GenericResult.row("orderId", id, "success", true));
    }

    // ========== 审核 ==========

    @SuppressWarnings("unchecked")
    @PostMapping("/goods-price-adjust/approve")
    @Transactional
    public ApiResponse<Map<String, Object>> approve(@RequestBody Map<String, Object> body) {
        String id = trim(body.get("orderId"));
        List<Map<String, Object>> heads = jdbc.queryForList(
                "SELECT order_no, remark FROM base_goods_price_adjust_order WHERE order_id = ? AND status = 'PENDING'", id);
        if (heads.isEmpty()) return ApiResponse.fail("400", "调价单不存在或非待审核状态");
        String orderNo = str(heads.get(0).get("order_no"));
        String remark = str(heads.get(0).get("remark"));
        String user = currentUser();

        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM base_goods_price_adjust_order_item WHERE order_id = ?", id);

        for (Map<String, Object> raw : items) {
            Map<String, Object> it = toCamel(raw);
            String gc = str(it.get("goodsCode"));
            String gn = str(it.get("goodsName"));
            int level = intOr(it.get("unitLevel"), 1);

            // 四种价格 → unit_config JSON
            applyToUnitConfig(gc, gn, level, "standardPrice", it.get("standardPriceNew"), orderNo, user, remark);
            applyToUnitConfig(gc, gn, level, "purchasePrice", it.get("purchasePriceNew"), orderNo, user, remark);
            applyToUnitConfig(gc, gn, level, "minPrice", it.get("minPriceNew"), orderNo, user, remark);
            applyToUnitConfig(gc, gn, level, "suggestRetailPrice", it.get("suggestRetailPriceNew"), orderNo, user, remark);

            // 小单位同步主表
            if (level == 1) syncHeadPrices(gc, it);

            // 价格组价格
            String pgJson = str(raw.get("price_group_prices"));
            if (!pgJson.isEmpty() && !"null".equals(pgJson)) {
                try {
                    List<Map<String, Object>> pgList = (List<Map<String, Object>>) MAPPER.readValue(pgJson, List.class);
                    if (pgList != null) {
                        for (Map<String, Object> pg : pgList) {
                            upsertPriceGroupItem(gc, gn, str(pg.get("pgCode")), level, numOrNull(pg.get("newPrice")));
                        }
                    }
                } catch (Exception e) { /* skip */ }
            }
        }

        jdbc.update("UPDATE base_goods_price_adjust_order SET status='APPROVED', audit_user=?, audit_time=CURRENT_TIMESTAMP WHERE order_id=?",
                user, id);
        return ApiResponse.ok(GenericResult.row("orderId", id, "success", true));
    }

    /** 快速调价确认：保存明细 → 提交 → 审核一步完成（仅 goodsLocked 订单可用）。 */
    @SuppressWarnings("unchecked")
    @PostMapping("/goods-price-adjust/confirm")
    @Transactional
    public ApiResponse<Map<String, Object>> confirm(@RequestBody Map<String, Object> body) {
        // 1) 保存明细
        ApiResponse<Map<String, Object>> saveResult = save(body);
        if (!"0".equals(saveResult.code())) return saveResult;
        String orderId = trim(saveResult.data().get("orderId"));

        // 2) 校验
        List<Map<String, Object>> heads = jdbc.queryForList(
                "SELECT * FROM base_goods_price_adjust_order WHERE order_id = ?", orderId);
        if (heads.isEmpty()) return ApiResponse.fail("400", "调价单不存在");
        Map<String, Object> h = heads.get(0);
        if (!Boolean.TRUE.equals(h.get("goods_locked")))
            return ApiResponse.fail("400", "仅快速调价单可使用确认功能");
        if (!"DRAFT".equals(str(h.get("status"))))
            return ApiResponse.fail("400", "调价单非草稿状态");

        // 3) DRAFT → APPROVED
        String orderNo = str(h.get("order_no"));
        String remark = str(h.get("remark"));
        String user = currentUser();
        jdbc.update("UPDATE base_goods_price_adjust_order SET status='APPROVED', submit_user=?, submit_time=CURRENT_TIMESTAMP, audit_user=?, audit_time=CURRENT_TIMESTAMP WHERE order_id=?",
                user, user, orderId);

        // 4) 写入价格（同 approve 逻辑）
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM base_goods_price_adjust_order_item WHERE order_id = ?", orderId);
        for (Map<String, Object> raw : items) {
            Map<String, Object> it = toCamel(raw);
            String gc = str(it.get("goodsCode"));
            String gn = str(it.get("goodsName"));
            int level = intOr(it.get("unitLevel"), 1);
            applyToUnitConfig(gc, gn, level, "standardPrice", it.get("standardPriceNew"), orderNo, user, remark);
            applyToUnitConfig(gc, gn, level, "purchasePrice", it.get("purchasePriceNew"), orderNo, user, remark);
            applyToUnitConfig(gc, gn, level, "minPrice", it.get("minPriceNew"), orderNo, user, remark);
            applyToUnitConfig(gc, gn, level, "suggestRetailPrice", it.get("suggestRetailPriceNew"), orderNo, user, remark);
            if (level == 1) syncHeadPrices(gc, it);
            String pgJson = str(raw.get("price_group_prices"));
            if (!pgJson.isEmpty() && !"null".equals(pgJson)) {
                try {
                    List<Map<String, Object>> pgList = (List<Map<String, Object>>) MAPPER.readValue(pgJson, List.class);
                    if (pgList != null) {
                        for (Map<String, Object> pg : pgList) {
                            upsertPriceGroupItem(gc, gn, str(pg.get("pgCode")), level, numOrNull(pg.get("newPrice")));
                        }
                    }
                } catch (Exception e) { /* skip */ }
            }
        }

        return ApiResponse.ok(GenericResult.row("orderId", orderId, "success", true, "effect", "价格已更新"));
    }

    // ========== 驳回 / 删除 ==========

    @PostMapping("/goods-price-adjust/reject")
    public ApiResponse<Map<String, Object>> reject(@RequestBody Map<String, Object> body) {
        String id = trim(body.get("orderId"));
        String reason = trim(body.get("rejectReason"));
        int rows = jdbc.update("UPDATE base_goods_price_adjust_order SET status='REJECTED', reject_reason=?, audit_user=?, audit_time=CURRENT_TIMESTAMP WHERE order_id=? AND status='PENDING'",
                reason, currentUser(), id);
        if (rows == 0) return ApiResponse.fail("400", "调价单不存在或非待审核状态");
        return ApiResponse.ok(GenericResult.row("orderId", id, "success", true));
    }

    @PostMapping("/goods-price-adjust/delete")
    public ApiResponse<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        String id = trim(body.get("orderId"));
        int rows = jdbc.update("DELETE FROM base_goods_price_adjust_order WHERE order_id = ? AND status = 'DRAFT'", id);
        if (rows == 0) return ApiResponse.fail("400", "只有草稿状态可删除");
        jdbc.update("DELETE FROM base_goods_price_adjust_order_item WHERE order_id = ?", id);
        return ApiResponse.ok(GenericResult.row("orderId", id, "success", true));
    }

    // ========== 核心：价格写入 ==========

    private void applyToUnitConfig(String goodsCode, String goodsName, int unitLevel,
                                    String priceKey, Object newPriceRaw,
                                    String orderNo, String user, String remark) {
        Double np = numOrNull(newPriceRaw);
        if (np == null) return;
        String json = jdbc.queryForObject("SELECT unit_config FROM base_goods WHERE goods_code = ?", String.class, goodsCode);
        if (json == null || json.isBlank()) json = "[]";
        try {
            com.fasterxml.jackson.databind.JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray() || arr.size() < unitLevel) return;
            com.fasterxml.jackson.databind.node.ObjectNode unitNode = (com.fasterxml.jackson.databind.node.ObjectNode) arr.get(unitLevel - 1);
            Double oldPrice = unitNode.has(priceKey) && !unitNode.get(priceKey).isNull()
                    ? unitNode.get(priceKey).asDouble() : null;
            if (oldPrice != null && Math.abs(oldPrice - np) < 0.0001) return;
            unitNode.put(priceKey, BigDecimal.valueOf(np).setScale(4, RoundingMode.HALF_UP).doubleValue());
            jdbc.update("UPDATE base_goods SET unit_config = ? WHERE goods_code = ?", MAPPER.writeValueAsString(arr), goodsCode);
            // 写变价日志
            String logId = "PCL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("""
                    INSERT INTO base_price_change_log
                    (id, order_id, order_no, goods_code, goods_name, price_group_code, unit_level,
                     old_price, new_price, change_type, operator, remark, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ADJUST_UPDATE', ?, ?, CURRENT_TIMESTAMP)
                    """, logId, "", orderNo, goodsCode, goodsName, null, unitLevel,
                    oldPrice != null ? BigDecimal.valueOf(oldPrice) : null,
                    BigDecimal.valueOf(np), user,
                    (remark != null ? remark : "") + " [" + priceKey + "]");
        } catch (Exception e) { /* skip */ }
    }

    private void syncHeadPrices(String goodsCode, Map<String, Object> it) {
        StringBuilder sql = new StringBuilder("UPDATE base_goods SET ");
        List<Object> args = new ArrayList<>();
        Double sp = numOrNull(it.get("standardPriceNew"));
        Double pp = numOrNull(it.get("purchasePriceNew"));
        Double mp = numOrNull(it.get("minPriceNew"));
        Double srp = numOrNull(it.get("suggestRetailPriceNew"));
        if (sp != null) { sql.append("standard_price = ?, "); args.add(sp); }
        if (pp != null) { sql.append("latest_purchase_price = ?, "); args.add(pp); }
        if (mp != null) { sql.append("min_sale_price = ?, "); args.add(mp); }
        if (srp != null) { sql.append("suggested_retail_price = ?, "); args.add(srp); }
        if (args.isEmpty()) return;
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE goods_code = ?");
        args.add(goodsCode);
        jdbc.update(sql.toString(), args.toArray());
    }

    private void upsertPriceGroupItem(String goodsCode, String goodsName, String pgCode,
                                       int unitLevel, Double newPrice) {
        if (newPrice == null || pgCode.isEmpty()) return;
        List<Map<String, Object>> exist = jdbc.queryForList(
                "SELECT id FROM base_price_group_item WHERE goods_code = ? AND price_group_code = ? AND unit_level = ?",
                goodsCode, pgCode, unitLevel);
        if (exist.isEmpty()) {
            String id = "PGI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("INSERT INTO base_price_group_item (id, goods_code, goods_name, price_group_code, unit_level, price, is_active) VALUES (?, ?, ?, ?, ?, ?, TRUE)",
                    id, goodsCode, goodsName, pgCode, unitLevel, newPrice);
        } else {
            jdbc.update("UPDATE base_price_group_item SET price = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    newPrice, exist.get(0).get("id"));
        }
    }

    // ========== 工具方法 ==========

    private String nextSeq(String prefix) {
        String like = prefix + LocalDate.now().format(YMD) + "%";
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM base_goods_price_adjust_order WHERE order_no LIKE ?", Integer.class, like);
        return String.format("%03d", (cnt == null ? 0 : cnt) + 1);
    }

    private void addFilter(StringBuilder sql, List<Object> args, String col, Object val, String op) {
        if (val == null || String.valueOf(val).isBlank()) return;
        if ("LIKE".equals(op)) { sql.append(" AND ").append(col).append(" LIKE ?"); args.add("%" + String.valueOf(val).trim() + "%"); }
        else { sql.append(" AND ").append(col).append(" = ?"); args.add(String.valueOf(val).trim()); }
    }

    private static Double numOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static String trim(Object v) { return v == null ? "" : String.valueOf(v).trim(); }
    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
    private static String emptyToNull(String v) { return v == null || v.isBlank() ? null : v; }
    private static int intOr(Object v, int dft) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) { try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { } }
        return dft;
    }

    private static String toJson(Object o) {
        if (o == null) return null;
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> queryCamel(String sql) {
        // Not used here — page uses raw JDBC
        return List.of();
    }

    private static Map<String, Object> toCamel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
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

    private static String currentUser() {
        try { return SecurityContextHolder.getContext().getAuthentication().getName(); }
        catch (Exception e) { return "系统管理员"; }
    }
}
