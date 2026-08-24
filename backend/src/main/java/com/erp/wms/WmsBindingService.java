package com.erp.wms;

import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WMS V1.5 拣货位商品绑定服务（PRD-28，V83）。
 *
 * <p>维护「库位 ↔ 商品」的固定绑定关系，用于：
 * <ul>
 *   <li>上架/补货推荐时把商品固定落到拣货位；</li>
 *   <li>拣货路径生成时按绑定库位排序；</li>
 *   <li>PDA 扫码快速核对/改绑。</li>
 * </ul>
 *
 * <p><b>账实分离</b>：本服务只维护绑定元数据（库位、商品、阈值），
 * 不写入任何库存数量或金额。绑定/解绑/转移均写 {@code wms_binding_change_log} 审计日志。
 *
 * <p>约束：
 * <ul>
 *   <li>同仓库 + 同库位 + 同商品 只能有一条绑定（uk_bin_goods）；</li>
 *   <li>同仓库 + 同商品 + 同绑定类型 只能有一条生效绑定（uk_goods_warehouse），
 *       转移绑定 = 旧库位 active_flag 置 N + 新库位 INSERT，记录在同一事务里。</li>
 * </ul>
 */
@Service
public class WmsBindingService {

    private final JdbcTemplate jdbc;

    public WmsBindingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 绑定列表（可按仓库/库区/库位/商品筛选）。 */
    public List<Map<String, Object>> list(String warehouse, String zoneCode, String binCode,
                                          String keyword, String activeFlag) {
        StringBuilder sql = new StringBuilder("""
                SELECT b.binding_id, b.warehouse, b.zone_code, b.bin_code, b.goods_code, b.goods_name,
                       b.binding_type, b.min_qty, b.max_qty, b.replenish_to_qty, b.active_flag,
                       b.remark, b.bound_by, b.bound_at, b.updated_at,
                       COALESCE((SELECT SUM(s.qty) FROM wms_bin_stock s
                          WHERE s.bin_code = b.bin_code AND s.warehouse = b.warehouse
                            AND s.goods_code = b.goods_code),0) AS on_hand_qty
                FROM wms_bin_goods_binding b
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (notBlank(warehouse))   { sql.append(" AND b.warehouse = ?");  args.add(warehouse); }
        if (notBlank(zoneCode))    { sql.append(" AND b.zone_code = ?");  args.add(zoneCode); }
        if (notBlank(binCode))     { sql.append(" AND b.bin_code = ?");   args.add(binCode); }
        if ("Y".equalsIgnoreCase(activeFlag) || "N".equalsIgnoreCase(activeFlag)) {
            sql.append(" AND b.active_flag = ?"); args.add(activeFlag.toUpperCase());
        }
        if (notBlank(keyword)) {
            sql.append(" AND (LOWER(b.goods_code) LIKE ? OR LOWER(b.goods_name) LIKE ? OR LOWER(b.bin_code) LIKE ?)");
            String k = "%" + keyword.toLowerCase() + "%";
            args.add(k); args.add(k); args.add(k);
        }
        sql.append(" ORDER BY b.warehouse, b.zone_code, b.bin_code, b.goods_code");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    /** 绑定（或在已存在记录上重新激活 + 更新阈值）。 */
    @Transactional
    public Map<String, Object> bind(Map<String, Object> req) {
        String warehouse = str(req.get("warehouse"));
        String zoneCode  = str(req.get("zoneCode"));
        String binCode   = str(req.get("binCode"));
        String goodsCode = str(req.get("goodsCode"));
        if (warehouse.isBlank() || zoneCode.isBlank() || binCode.isBlank() || goodsCode.isBlank()) {
            throw new IllegalArgumentException("仓库/库区/库位/商品编码均不能为空");
        }
        assertBinInZone(warehouse, zoneCode, binCode);
        String bindingType = strOr(req.get("bindingType"), "PICK");
        // 同商品在同仓库+同绑定类型下只能有一条生效绑定：先把其它绑定暂停
        jdbc.update("""
                UPDATE wms_bin_goods_binding SET active_flag='N', updated_at=CURRENT_TIMESTAMP
                WHERE warehouse=? AND goods_code=? AND binding_type=? AND active_flag='Y'
                """, warehouse, goodsCode, bindingType);

        String existing = jdbc.queryForList(
                "SELECT binding_id FROM wms_bin_goods_binding WHERE warehouse=? AND bin_code=? AND goods_code=? AND binding_type=?",
                warehouse, binCode, goodsCode, bindingType).stream().findFirst()
                .map(m -> str(m.get("binding_id"))).orElse("");
        String id;
        if (existing.isBlank()) {
            id = "BG" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("""
                    INSERT INTO wms_bin_goods_binding
                    (binding_id, warehouse, zone_code, bin_code, goods_code, goods_name, binding_type,
                     min_qty, max_qty, replenish_to_qty, active_flag, remark, bound_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'Y', ?, ?)
                    """,
                    id, warehouse, zoneCode, binCode, goodsCode, str(req.get("goodsName")), bindingType,
                    bd(req.get("minQty")), bd(req.get("maxQty")), bd(req.get("replenishToQty")),
                    str(req.get("remark")), strOr(req.get("operator"), TmsUtil.currentUser()));
        } else {
            id = existing;
            jdbc.update("""
                    UPDATE wms_bin_goods_binding
                       SET goods_name=?, min_qty=?, max_qty=?, replenish_to_qty=?, active_flag='Y',
                           remark=?, updated_at=CURRENT_TIMESTAMP
                     WHERE binding_id=?
                    """,
                    str(req.get("goodsName")), bd(req.get("minQty")), bd(req.get("maxQty")),
                    bd(req.get("replenishToQty")), str(req.get("remark")), id);
        }
        writeLog(warehouse, goodsCode, "", binCode, "BIND", req);
        return Map.of("bindingId", id, "binCode", binCode);
    }

    /** 解绑：把 active_flag 置 N 并写审计日志。不删历史，便于回溯。 */
    @Transactional
    public Map<String, Object> unbind(Map<String, Object> req) {
        String id = str(req.get("bindingId"));
        if (id.isBlank()) throw new IllegalArgumentException("bindingId 不能为空");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT warehouse, bin_code, goods_code FROM wms_bin_goods_binding WHERE binding_id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("绑定不存在：" + id);
        Map<String, Object> r = rows.get(0);
        jdbc.update("UPDATE wms_bin_goods_binding SET active_flag='N', updated_at=CURRENT_TIMESTAMP WHERE binding_id=?", id);
        writeLog(str(r.get("warehouse")), str(r.get("goods_code")),
                str(r.get("bin_code")), "", "UNBIND", req);
        return Map.of("success", true);
    }

    /**
     * 改绑/转移：把某商品在某仓库内的生效绑定从旧库位切到新库位。
     * 旧库位 active_flag 置 N，新库位若已有记录则激活、否则 INSERT。
     */
    @Transactional
    public Map<String, Object> transfer(Map<String, Object> req) {
        String warehouse = str(req.get("warehouse"));
        String goodsCode = str(req.get("goodsCode"));
        String fromBin   = str(req.get("fromBin"));
        String toBin     = str(req.get("toBin"));
        String toZone    = str(req.get("toZoneCode"));
        String bindingType = strOr(req.get("bindingType"), "PICK");
        if (warehouse.isBlank() || goodsCode.isBlank() || fromBin.isBlank() || toBin.isBlank()) {
            throw new IllegalArgumentException("仓库/商品/源库位/目标库位均不能为空");
        }
        if (toZone.isBlank()) {
            toZone = jdbc.queryForList(
                    "SELECT zone_code FROM wms_bin WHERE warehouse=? AND bin_code=?", warehouse, toBin)
                    .stream().findFirst().map(m -> str(m.get("zone_code"))).orElse("");
        }
        if (toZone.isBlank()) throw new IllegalArgumentException("目标库位不存在或未指定库区");
        assertBinInZone(warehouse, toZone, toBin);

        // 1) 旧库位上的同商品+同类型绑定全部置 N
        jdbc.update("""
                UPDATE wms_bin_goods_binding SET active_flag='N', updated_at=CURRENT_TIMESTAMP
                WHERE warehouse=? AND goods_code=? AND binding_type=? AND bin_code=?
                """, warehouse, goodsCode, bindingType, fromBin);

        // 2) 目标库位若已存在记录则激活，否则新建
        String existingId = jdbc.queryForList(
                "SELECT binding_id FROM wms_bin_goods_binding WHERE warehouse=? AND bin_code=? AND goods_code=? AND binding_type=?",
                warehouse, toBin, goodsCode, bindingType).stream().findFirst()
                .map(m -> str(m.get("binding_id"))).orElse("");
        if (existingId.isBlank()) {
            existingId = "BG" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("""
                    INSERT INTO wms_bin_goods_binding
                    (binding_id, warehouse, zone_code, bin_code, goods_code, binding_type, active_flag, bound_by)
                    VALUES (?, ?, ?, ?, ?, ?, 'Y', ?)
                    """, existingId, warehouse, toZone, toBin, goodsCode, bindingType,
                    strOr(req.get("operator"), TmsUtil.currentUser()));
        } else {
            jdbc.update("""
                    UPDATE wms_bin_goods_binding SET active_flag='Y', zone_code=?, updated_at=CURRENT_TIMESTAMP
                    WHERE binding_id=?
                    """, toZone, existingId);
        }
        writeLog(warehouse, goodsCode, fromBin, toBin, "TRANSFER", req);
        return Map.of("bindingId", existingId, "fromBin", fromBin, "toBin", toBin);
    }

    /** 阈值调整（不改变库位关系，只改 min/max/replenish）。 */
    @Transactional
    public Map<String, Object> updateThresholds(Map<String, Object> req) {
        String id = str(req.get("bindingId"));
        if (id.isBlank()) throw new IllegalArgumentException("bindingId 不能为空");
        jdbc.update("""
                UPDATE wms_bin_goods_binding
                   SET min_qty=?, max_qty=?, replenish_to_qty=?, remark=?, updated_at=CURRENT_TIMESTAMP
                 WHERE binding_id=?
                """, bd(req.get("minQty")), bd(req.get("maxQty")), bd(req.get("replenishToQty")),
                str(req.get("remark")), id);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT warehouse, bin_code, goods_code FROM wms_bin_goods_binding WHERE binding_id=?", id);
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            writeLog(str(r.get("warehouse")), str(r.get("goods_code")),
                    str(r.get("bin_code")), str(r.get("bin_code")), "UPDATE", req);
        }
        return Map.of("success", true);
    }

    /** PDA 快速查询：扫库位/商品得到当前绑定，用于确认与改绑。 */
    public List<Map<String, Object>> lookup(String warehouse, String binCode, String goodsCode) {
        StringBuilder sql = new StringBuilder("""
                SELECT binding_id, warehouse, zone_code, bin_code, goods_code, goods_name,
                       binding_type, min_qty, max_qty, replenish_to_qty, active_flag
                  FROM wms_bin_goods_binding
                 WHERE active_flag='Y'
                """);
        List<Object> args = new ArrayList<>();
        if (notBlank(warehouse)) { sql.append(" AND warehouse=?"); args.add(warehouse); }
        if (notBlank(binCode))   { sql.append(" AND bin_code=?"); args.add(binCode); }
        if (notBlank(goodsCode)) { sql.append(" AND goods_code=?"); args.add(goodsCode); }
        sql.append(" ORDER BY bin_code, goods_code");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    // ==================== helpers ====================

    private void assertBinInZone(String warehouse, String zoneCode, String binCode) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_bin WHERE warehouse=? AND zone_code=? AND bin_code=?",
                Integer.class, warehouse, zoneCode, binCode);
        if (c == null || c == 0) {
            throw new IllegalArgumentException("库位 " + binCode + " 不属于库区 " + zoneCode + "（" + warehouse + "），请先在库位管理维护");
        }
    }

    private void writeLog(String warehouse, String goodsCode, String fromBin, String toBin,
                          String changeType, Map<String, Object> req) {
        String source = "PDA".equalsIgnoreCase(str(req.get("source"))) ? "PDA" : "PC";
        jdbc.update("""
                INSERT INTO wms_binding_change_log
                (log_id, warehouse, goods_code, from_bin, to_bin, change_type, operator, source, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "BL" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                warehouse, goodsCode, emptyToNull(fromBin), emptyToNull(toBin), changeType,
                strOr(req.get("operator"), TmsUtil.currentUser()), source, str(req.get("remark")));
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String strOr(Object o, String dft) { String s = str(o); return s.isEmpty() ? dft : s; }
    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s; }
    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(o).trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
