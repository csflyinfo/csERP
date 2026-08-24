package com.erp.wms;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WMS V1.5 PC 后台端点（PRD-28 出库闭环）。
 *
 * <p>前缀 /api/wms（context-path=/api）。所有写操作委托 {@link WmsOutboundService}（带事务），
 * Controller 不直接处理事务。账实扣减统一走销售出库审核链，详见 WmsOutboundService 类注释。
 */
@RestController
@RequestMapping("/wms")
public class WmsController {

    private final JdbcTemplate jdbc;
    private final WmsOutboundService service;
    private final WmsBindingService bindingService;

    public WmsController(JdbcTemplate jdbc, WmsOutboundService service, WmsBindingService bindingService) {
        this.jdbc = jdbc;
        this.service = service;
        this.bindingService = bindingService;
    }

    // -------- 基础资料：仓库 / 库区 / 库位 --------

    /** 仓库下拉：左侧库区管理树用，优先列出 WMS 在管仓库（zones 已引用），其它实物仓也列出。 */
    @GetMapping("/warehouse/list")
    public ApiResponse<List<Map<String, Object>>> warehouseList() {
        return ApiResponse.ok(TmsUtil.queryCamel(jdbc, """
                SELECT w.warehouse_id, w.warehouse_code, w.warehouse_name, w.warehouse_type,
                       w.manager_name, w.status,
                       (SELECT COUNT(*) FROM wms_zone z WHERE z.warehouse = w.warehouse_name) AS zone_count
                  FROM base_warehouse w
                 WHERE COALESCE(w.status,'NORMAL') = 'NORMAL'
                 ORDER BY w.warehouse_code
                """));
    }

    /** 库区列表：支持按仓库过滤。左侧仓库选中后用。 */
    @PostMapping("/zone/page")
    public ApiResponse<PageResult<Map<String, Object>>> zones(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        String warehouse = str(f.get("warehouse"));
        StringBuilder sql = new StringBuilder("""
                SELECT zone_id, zone_code, zone_name, warehouse, zone_type, pick_seq, frozen,
                       pick_method, sort_mode, storage_property, remark,
                       (SELECT COUNT(*) FROM wms_bin b WHERE b.warehouse = wms_zone.warehouse AND b.zone_code = wms_zone.zone_code) AS bin_count
                  FROM wms_zone
                """);
        List<Object> args = new ArrayList<>();
        if (!warehouse.isBlank()) {
            sql.append(" WHERE warehouse = ?");
            args.add(warehouse);
        }
        sql.append(" ORDER BY warehouse, pick_seq");
        return ApiResponse.ok(PageResult.of(TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray()), request));
    }

    @PostMapping("/zone/save")
    public ApiResponse<Map<String, Object>> saveZone(@RequestBody Map<String, Object> req) {
        String id = str(req.get("zoneId"));
        String code = str(req.get("zoneCode"));
        String name = str(req.get("zoneName"));
        String wh = strOr(req.get("warehouse"), "总仓");
        if (code.isBlank()) throw new IllegalArgumentException("库区编码不能为空");
        // V84 拣货方式 + 存储属性
        String pickMethod = normalizePickMethod(str(req.get("pickMethod")));
        String sortMode = normalizeSortMode(pickMethod, str(req.get("sortMode")));
        String storageProperty = normalizeStorageProperty(str(req.get("storageProperty")));
        if (id.isBlank()) {
            id = "ZN" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("""
                    INSERT INTO wms_zone (zone_id, zone_code, zone_name, warehouse, zone_type, pick_seq,
                        frozen, pick_method, sort_mode, storage_property, remark)
                    VALUES (?, ?, ?, ?, ?, ?, 'N', ?, ?, ?, ?)
                    """, id, code, name, wh, strOr(req.get("zoneType"), "STORAGE"),
                    intOr(req.get("pickSeq"), 0), emptyToNull(pickMethod), emptyToNull(sortMode),
                    emptyToNull(storageProperty), str(req.get("remark")));
        } else {
            jdbc.update("""
                    UPDATE wms_zone SET zone_code=?, zone_name=?, zone_type=?, pick_seq=?,
                        pick_method=?, sort_mode=?, storage_property=?, remark=? WHERE zone_id=?
                    """, code, name, strOr(req.get("zoneType"), "STORAGE"), intOr(req.get("pickSeq"), 0),
                    emptyToNull(pickMethod), emptyToNull(sortMode), emptyToNull(storageProperty),
                    str(req.get("remark")), id);
        }
        TmsUtil.log(jdbc, "wms.base", "SAVE_ZONE", code,
                "维护库区 pickMethod=" + pickMethod + " sortMode=" + sortMode + " storage=" + storageProperty);
        return ApiResponse.ok(Map.of("zoneId", id));
    }

    /** 拣货方式枚举：CASE_PICK/CASE_MERGE/BULK_PICK/BULK_MERGE；空串=未配置（走 WMS_PICK_MODE 兜底）。 */
    private String normalizePickMethod(String raw) {
        if (raw == null) return "";
        String v = raw.trim().toUpperCase();
        return switch (v) {
            case "CASE_PICK", "CASE_MERGE", "BULK_PICK", "BULK_MERGE" -> v;
            default -> "";
        };
    }

    /** 合拣必须指定分拣方式（先拣后分/边拣边分）；单拣清空 sort_mode。 */
    private String normalizeSortMode(String pickMethod, String raw) {
        if (pickMethod == null || !pickMethod.endsWith("_MERGE")) return "";
        if (raw == null) return "";
        String v = raw.trim().toUpperCase();
        return switch (v) {
            case "PICK_THEN_SORT", "SORT_WHILE_PICK" -> v;
            default -> "";
        };
    }

    /** 存储属性：对齐 base_goods.storage_property 值域（常温/冷藏/冷冻/恒温/避光），空串=不限。 */
    private String normalizeStorageProperty(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        return java.util.Set.of("常温", "冷藏", "冷冻", "恒温", "避光").contains(v) ? v : "";
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 库区冻结/解冻。按数据库当前状态翻转，避免客户端状态不同步。frozen=Y 时不允许新任务落到该区。 */
    @PostMapping("/zone/toggle")
    public ApiResponse<Map<String, Object>> toggleZone(@RequestBody Map<String, Object> req) {
        String id = str(req.get("zoneId"));
        if (id.isBlank()) throw new IllegalArgumentException("zoneId 不能为空");
        String current = jdbc.queryForList("SELECT frozen FROM wms_zone WHERE zone_id=?", id)
                .stream().findFirst().map(m -> str(m.get("frozen"))).orElse("N");
        String target = "Y".equalsIgnoreCase(current) ? "N" : "Y";
        jdbc.update("UPDATE wms_zone SET frozen=? WHERE zone_id=?", target, id);
        TmsUtil.log(jdbc, "wms.base", "TOGGLE_ZONE", id, target.equals("Y") ? "冻结库区" : "解冻库区");
        return ApiResponse.ok(Map.of("frozen", target));
    }

    /**
     * 删除库区：仅允许在该区下没有库位时删除，避免出现孤儿库位。
     */
    @PostMapping("/zone/delete")
    public ApiResponse<Map<String, Object>> deleteZone(@RequestBody Map<String, Object> req) {
        String id = str(req.get("zoneId"));
        if (id.isBlank()) throw new IllegalArgumentException("zoneId 不能为空");
        // 用 warehouse+zone_code 校验该区下是否还有库位，避免孤儿库位
        Map<String, Object> z = jdbc.queryForList("SELECT warehouse, zone_code FROM wms_zone WHERE zone_id=?", id)
                .stream().findFirst().orElse(null);
        if (z == null) return ApiResponse.ok(Map.of("success", true));
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_bin WHERE warehouse=? AND zone_code=?",
                Integer.class, str(z.get("warehouse")), str(z.get("zone_code")));
        if (c != null && c > 0) {
            throw new IllegalArgumentException("库区下还有 " + c + " 个库位，请先删除/迁移库位后再删库区");
        }
        jdbc.update("DELETE FROM wms_zone WHERE zone_id=?", id);
        TmsUtil.log(jdbc, "wms.base", "DELETE_ZONE", id, "删除库区");
        return ApiResponse.ok(Map.of("success", true));
    }

    /** 库位列表：支持按 warehouse + zoneCode 过滤；左侧库区选中后用。 */
    @PostMapping("/bin/page")
    public ApiResponse<PageResult<Map<String, Object>>> bins(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        String warehouse = str(f.get("warehouse"));
        String zoneCode  = str(f.get("zoneCode"));
        String keyword   = str(f.get("keyword"));
        StringBuilder sql = new StringBuilder("""
                SELECT bin_id, bin_code, bin_name, warehouse, zone_code, bin_type, aisle, slot, layer,
                       capacity_qty, capacity_weight, capacity_volume, pick_seq,
                       used_qty, frozen, status, remark FROM wms_bin WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (!warehouse.isBlank()) { sql.append(" AND warehouse=?"); args.add(warehouse); }
        if (!zoneCode.isBlank())  { sql.append(" AND zone_code=?"); args.add(zoneCode); }
        if (!keyword.isBlank())   { sql.append(" AND (LOWER(bin_code) LIKE ? OR LOWER(bin_name) LIKE ?)");
                                    String k = "%" + keyword.toLowerCase() + "%"; args.add(k); args.add(k); }
        sql.append(" ORDER BY warehouse, zone_code, pick_seq, bin_code");
        return ApiResponse.ok(PageResult.of(TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray()), request));
    }

    @PostMapping("/bin/save")
    public ApiResponse<Map<String, Object>> saveBin(@RequestBody Map<String, Object> req) {
        String id = str(req.get("binId"));
        String code = str(req.get("binCode"));
        if (code.isBlank()) throw new IllegalArgumentException("库位编码不能为空");
        String warehouse = strOr(req.get("warehouse"), "总仓");
        String zoneCode = str(req.get("zoneCode"));
        String binType = strOr(req.get("binType"), "SHELF");
        String aisle = str(req.get("aisle"));
        String slot = str(req.get("slot"));
        String layer = str(req.get("layer"));
        BigDecimal capQty = bd(req.get("capacityQty"));
        BigDecimal capWeight = bd(req.get("capacityWeight"));
        BigDecimal capVolume = bd(req.get("capacityVolume"));
        int pickSeq = intOr(req.get("pickSeq"), 0);
        String remark = str(req.get("remark"));
        if (id.isBlank()) {
            id = "BN" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("""
                    INSERT INTO wms_bin (bin_id, bin_code, bin_name, warehouse, zone_code, bin_type, aisle, slot, layer,
                        capacity_qty, capacity_weight, capacity_volume, pick_seq, status, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NORMAL', ?)
                    """, id, code, str(req.get("binName")), warehouse, zoneCode, binType,
                    aisle, slot, layer, capQty, capWeight, capVolume, pickSeq, remark);
        } else {
            jdbc.update("""
                    UPDATE wms_bin SET bin_code=?, bin_name=?, zone_code=?, bin_type=?, aisle=?, slot=?, layer=?,
                        capacity_qty=?, capacity_weight=?, capacity_volume=?, pick_seq=?, remark=? WHERE bin_id=?
                    """, code, str(req.get("binName")), zoneCode, binType,
                    aisle, slot, layer, capQty, capWeight, capVolume, pickSeq, remark, id);
        }
        TmsUtil.log(jdbc, "wms.base", "SAVE_BIN", code, "维护库位");
        return ApiResponse.ok(Map.of("binId", id));
    }

    /**
     * 批量导入库位（V85）。
     * body: { warehouse, zoneCode, binType?, rows:[{binCode,binName,aisle,slot,layer,capacityQty,...}] }
     * 按 (warehouse, bin_code) 做 upsert：存在则更新，不存在则插入。
     * 返回 {inserted, updated, failed:[{binCode,reason}]}。
     */
    @PostMapping("/bin/import")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> importBins(@RequestBody Map<String, Object> req) {
        String warehouse = strOr(req.get("warehouse"), "总仓");
        String zoneCode = str(req.get("zoneCode"));
        String defaultType = strOr(req.get("binType"), "SHELF");
        if (zoneCode.isBlank()) throw new IllegalArgumentException("必须指定库区");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) req.getOrDefault("rows", List.of());
        if (rows.isEmpty()) throw new IllegalArgumentException("没有可导入的数据");
        int inserted = 0, updated = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        int defaultSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(pick_seq),0)+1 FROM wms_bin WHERE warehouse=? AND zone_code=?",
                Integer.class, warehouse, zoneCode);
        for (Map<String, Object> r : rows) {
            String code = str(r.get("binCode"));
            if (code.isBlank()) { failed.add(Map.of("binCode", "", "reason", "库位编码为空")); continue; }
            try {
                List<Map<String, Object>> exist = jdbc.queryForList(
                        "SELECT bin_id FROM wms_bin WHERE warehouse=? AND bin_code=?", warehouse, code);
                String binType = strOr(r.get("binType"), defaultType);
                BigDecimal capQty = bd(r.get("capacityQty"));
                BigDecimal capW = bd(r.get("capacityWeight"));
                BigDecimal capV = bd(r.get("capacityVolume"));
                int seq = intOr(r.get("pickSeq"), defaultSeq);
                if (exist.isEmpty()) {
                    String id = "BN" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
                    jdbc.update("""
                            INSERT INTO wms_bin (bin_id, bin_code, bin_name, warehouse, zone_code, bin_type,
                                aisle, slot, layer, capacity_qty, capacity_weight, capacity_volume, pick_seq,
                                status, remark)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NORMAL', ?)
                            """, id, code, str(r.get("binName")), warehouse, zoneCode, binType,
                            str(r.get("aisle")), str(r.get("slot")), str(r.get("layer")),
                            capQty, capW, capV, seq, str(r.get("remark")));
                    inserted++;
                } else {
                    jdbc.update("""
                            UPDATE wms_bin SET bin_name=?, bin_type=?, aisle=?, slot=?, layer=?,
                                capacity_qty=?, capacity_weight=?, capacity_volume=?, pick_seq=?, remark=?
                            WHERE bin_id=?
                            """, str(r.get("binName")), binType,
                            str(r.get("aisle")), str(r.get("slot")), str(r.get("layer")),
                            capQty, capW, capV, seq, str(r.get("remark")),
                            exist.get(0).get("bin_id"));
                    updated++;
                }
                defaultSeq = Math.max(defaultSeq, seq) + 1;
            } catch (Exception e) {
                failed.add(Map.of("binCode", code, "reason", e.getMessage() == null ? "未知错误" : e.getMessage()));
            }
        }
        TmsUtil.log(jdbc, "wms.base", "IMPORT_BIN", zoneCode,
                "导入库位 新增" + inserted + " 更新" + updated + " 失败" + failed.size());
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("inserted", inserted);
        result.put("updated", updated);
        result.put("failed", failed);
        return ApiResponse.ok(result);
    }

    /** 库位启停：停用后不再参与上架/拣货推荐，但保留库存与历史。 */
    @PostMapping("/bin/toggle")
    public ApiResponse<Map<String, Object>> toggleBin(@RequestBody Map<String, Object> req) {
        String id = str(req.get("binId"));
        String current = jdbc.queryForList("SELECT status FROM wms_bin WHERE bin_id=?", id)
                .stream().findFirst().map(m -> str(m.get("status"))).orElse("NORMAL");
        String target = "DISABLED".equalsIgnoreCase(current) ? "NORMAL" : "DISABLED";
        jdbc.update("UPDATE wms_bin SET status=? WHERE bin_id=?", target, id);
        TmsUtil.log(jdbc, "wms.base", "TOGGLE_BIN", id, target.equals("DISABLED") ? "停用库位" : "启用库位");
        return ApiResponse.ok(Map.of("status", target));
    }

    /**
     * 删除库位：仅允许在库位无在库（wms_bin_stock 合计=0）且无在途绑定时删除。
     */
    @PostMapping("/bin/delete")
    public ApiResponse<Map<String, Object>> deleteBin(@RequestBody Map<String, Object> req) {
        String id = str(req.get("binId"));
        if (id.isBlank()) throw new IllegalArgumentException("binId 不能为空");
        Map<String, Object> b = jdbc.queryForList(
                "SELECT warehouse, bin_code FROM wms_bin WHERE bin_id=?", id).stream().findFirst().orElse(null);
        if (b == null) return ApiResponse.ok(Map.of("success", true));
        BigDecimal stock = jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty),0) FROM wms_bin_stock WHERE warehouse=? AND bin_code=?",
                BigDecimal.class, str(b.get("warehouse")), str(b.get("bin_code")));
        if (stock != null && stock.signum() > 0) {
            throw new IllegalArgumentException("库位还有 " + stock.stripTrailingZeros().toPlainString()
                    + " 件在库，请先移位或清空后再删除");
        }
        jdbc.update("UPDATE wms_bin_goods_binding SET active_flag='N' WHERE warehouse=? AND bin_code=?",
                str(b.get("warehouse")), str(b.get("bin_code")));
        jdbc.update("DELETE FROM wms_bin WHERE bin_id=?", id);
        TmsUtil.log(jdbc, "wms.base", "DELETE_BIN", id, "删除库位");
        return ApiResponse.ok(Map.of("success", true));
    }

    // -------- 集货区 / 集货位（独立模块，V88） --------
    // 复用 wms_zone(zone_type='COLLECT') / wms_bin(bin_type='COLLECT')。
    // 语义：zone.frozen=集货区停用（分配级联排除）；bin.status=停用/启用；bin.frozen=人工锁定/释放。

    /** 集货区分页：仅 zone_type='COLLECT'，支持按仓库过滤。 */
    @PostMapping("/collection-zone/page")
    public ApiResponse<PageResult<Map<String, Object>>> collectionZones(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        String warehouse = str(f.get("warehouse"));
        StringBuilder sql = new StringBuilder("""
                SELECT zone_id, zone_code, zone_name, warehouse, zone_type, pick_seq, frozen, remark,
                       (SELECT COUNT(*) FROM wms_bin b
                         WHERE b.warehouse = wms_zone.warehouse
                           AND b.zone_code = wms_zone.zone_code
                           AND b.bin_type = 'COLLECT') AS bin_count
                  FROM wms_zone
                 WHERE zone_type = 'COLLECT'
                """);
        List<Object> args = new ArrayList<>();
        if (!warehouse.isBlank()) { sql.append(" AND warehouse = ?"); args.add(warehouse); }
        sql.append(" ORDER BY warehouse, pick_seq, zone_code");
        return ApiResponse.ok(PageResult.of(TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray()), request));
    }

    /** 新建/编辑集货区，强制 zone_type='COLLECT'；不涉及拣货方式/温区字段。 */
    @PostMapping("/collection-zone/save")
    public ApiResponse<Map<String, Object>> saveCollectionZone(@RequestBody Map<String, Object> req) {
        String id = str(req.get("zoneId"));
        String code = str(req.get("zoneCode"));
        String name = str(req.get("zoneName"));
        String wh = strOr(req.get("warehouse"), "总仓");
        int pickSeq = intOr(req.get("pickSeq"), 0);
        String remark = str(req.get("remark"));
        if (code.isBlank()) throw new IllegalArgumentException("集货区编码不能为空");
        if (name.isBlank()) throw new IllegalArgumentException("集货区名称不能为空");
        if (id.isBlank()) {
            id = "ZN" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("""
                    INSERT INTO wms_zone (zone_id, zone_code, zone_name, warehouse, zone_type, pick_seq, frozen, remark)
                    VALUES (?, ?, ?, ?, 'COLLECT', ?, 'N', ?)
                    """, id, code, name, wh, pickSeq, remark);
        } else {
            jdbc.update("""
                    UPDATE wms_zone SET zone_code=?, zone_name=?, warehouse=?, pick_seq=?, remark=?
                     WHERE zone_id=? AND zone_type='COLLECT'
                    """, code, name, wh, pickSeq, remark, id);
        }
        TmsUtil.log(jdbc, "wms.base", "SAVE_COLLECT_ZONE", code, "维护集货区");
        return ApiResponse.ok(Map.of("zoneId", id));
    }

    /** 集货区停用/启用：翻转 frozen。级联排除在分配查询里生效，不逐行改集货位状态。 */
    @PostMapping("/collection-zone/toggle")
    public ApiResponse<Map<String, Object>> toggleCollectionZone(@RequestBody Map<String, Object> req) {
        String id = str(req.get("zoneId"));
        if (id.isBlank()) throw new IllegalArgumentException("zoneId 不能为空");
        String current = jdbc.queryForList(
                "SELECT frozen FROM wms_zone WHERE zone_id=? AND zone_type='COLLECT'", id)
                .stream().findFirst().map(m -> str(m.get("frozen"))).orElse("N");
        String target = "Y".equalsIgnoreCase(current) ? "N" : "Y";
        jdbc.update("UPDATE wms_zone SET frozen=? WHERE zone_id=? AND zone_type='COLLECT'", target, id);
        TmsUtil.log(jdbc, "wms.base", "TOGGLE_COLLECT_ZONE", id,
                target.equals("Y") ? "停用集货区" : "启用集货区");
        return ApiResponse.ok(Map.of("frozen", target));
    }

    /** 删除集货区：该区下还有集货位时拒绝，避免孤儿集货位。 */
    @PostMapping("/collection-zone/delete")
    public ApiResponse<Map<String, Object>> deleteCollectionZone(@RequestBody Map<String, Object> req) {
        String id = str(req.get("zoneId"));
        if (id.isBlank()) throw new IllegalArgumentException("zoneId 不能为空");
        Map<String, Object> z = jdbc.queryForList(
                "SELECT warehouse, zone_code FROM wms_zone WHERE zone_id=? AND zone_type='COLLECT'", id)
                .stream().findFirst().orElse(null);
        if (z == null) return ApiResponse.ok(Map.of("success", true));
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_bin WHERE warehouse=? AND zone_code=? AND bin_type='COLLECT'",
                Integer.class, str(z.get("warehouse")), str(z.get("zone_code")));
        if (c != null && c > 0) {
            throw new IllegalArgumentException("集货区下还有 " + c + " 个集货位，请先删除/迁移后再删集货区");
        }
        jdbc.update("DELETE FROM wms_zone WHERE zone_id=? AND zone_type='COLLECT'", id);
        TmsUtil.log(jdbc, "wms.base", "DELETE_COLLECT_ZONE", id, "删除集货区");
        return ApiResponse.ok(Map.of("success", true));
    }

    /**
     * 批量导入集货区（V88）。body: { warehouse, rows:[{zoneCode,zoneName,pickSeq,remark}] }
     * 按 (warehouse, zone_code) upsert，强制 zone_type='COLLECT'。返回 {inserted,updated,failed}。
     */
    @PostMapping("/collection-zone/import")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> importCollectionZones(@RequestBody Map<String, Object> req) {
        String warehouse = strOr(req.get("warehouse"), "总仓");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) req.getOrDefault("rows", List.of());
        if (rows.isEmpty()) throw new IllegalArgumentException("没有可导入的数据");
        int inserted = 0, updated = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        int defaultSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(pick_seq),0)+1 FROM wms_zone WHERE warehouse=? AND zone_type='COLLECT'",
                Integer.class, warehouse);
        for (Map<String, Object> r : rows) {
            String code = str(r.get("zoneCode"));
            String name = str(r.get("zoneName"));
            if (code.isBlank()) { failed.add(Map.of("zoneCode", "", "reason", "集货区编码为空")); continue; }
            if (name.isBlank()) { failed.add(Map.of("zoneCode", code, "reason", "集货区名称为空")); continue; }
            try {
                List<Map<String, Object>> exist = jdbc.queryForList(
                        "SELECT zone_id FROM wms_zone WHERE warehouse=? AND zone_code=?", warehouse, code);
                int seq = intOr(r.get("pickSeq"), defaultSeq);
                String remark = str(r.get("remark"));
                if (exist.isEmpty()) {
                    String id = "ZN" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
                    jdbc.update("""
                            INSERT INTO wms_zone (zone_id, zone_code, zone_name, warehouse, zone_type, pick_seq, frozen, remark)
                            VALUES (?, ?, ?, ?, 'COLLECT', ?, 'N', ?)
                            """, id, code, name, warehouse, seq, remark);
                    inserted++;
                } else {
                    jdbc.update("""
                            UPDATE wms_zone SET zone_name=?, zone_type='COLLECT', pick_seq=?, remark=?
                             WHERE zone_id=?
                            """, name, seq, remark, exist.get(0).get("zone_id"));
                    updated++;
                }
                defaultSeq = Math.max(defaultSeq, seq) + 1;
            } catch (Exception e) {
                failed.add(Map.of("zoneCode", code, "reason", e.getMessage() == null ? "未知错误" : e.getMessage()));
            }
        }
        TmsUtil.log(jdbc, "wms.base", "IMPORT_COLLECT_ZONE", warehouse,
                "导入集货区 新增" + inserted + " 更新" + updated + " 失败" + failed.size());
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("inserted", inserted);
        result.put("updated", updated);
        result.put("failed", failed);
        return ApiResponse.ok(result);
    }

    /** 集货位分页：仅 bin_type='COLLECT'，JOIN 库区带出 zone_frozen（集货区停用态）。 */
    @PostMapping("/collection-bin/page")
    public ApiResponse<PageResult<Map<String, Object>>> collectionBins(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        String warehouse = str(f.get("warehouse"));
        String zoneCode  = str(f.get("zoneCode"));
        String keyword   = str(f.get("keyword"));
        StringBuilder sql = new StringBuilder("""
                SELECT b.bin_id, b.bin_code, b.bin_name, b.warehouse, b.zone_code, b.bin_type,
                       b.capacity_qty, b.pick_seq, b.used_qty, b.frozen, b.status, b.remark,
                       COALESCE(z.frozen,'N') AS zone_frozen
                  FROM wms_bin b
                  LEFT JOIN wms_zone z ON z.warehouse = b.warehouse AND z.zone_code = b.zone_code
                 WHERE b.bin_type = 'COLLECT'
                """);
        List<Object> args = new ArrayList<>();
        if (!warehouse.isBlank()) { sql.append(" AND b.warehouse=?"); args.add(warehouse); }
        if (!zoneCode.isBlank())  { sql.append(" AND b.zone_code=?"); args.add(zoneCode); }
        if (!keyword.isBlank())   { sql.append(" AND (LOWER(b.bin_code) LIKE ? OR LOWER(b.bin_name) LIKE ?)");
                                    String k = "%" + keyword.toLowerCase() + "%"; args.add(k); args.add(k); }
        sql.append(" ORDER BY b.warehouse, b.zone_code, b.pick_seq, b.bin_code");
        return ApiResponse.ok(PageResult.of(TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray()), request));
    }

    /** 新建/编辑集货位，强制 bin_type='COLLECT'；集货位不展示巷/列/层。 */
    @PostMapping("/collection-bin/save")
    public ApiResponse<Map<String, Object>> saveCollectionBin(@RequestBody Map<String, Object> req) {
        String id = str(req.get("binId"));
        String code = str(req.get("binCode"));
        if (code.isBlank()) throw new IllegalArgumentException("集货位编码不能为空");
        String warehouse = strOr(req.get("warehouse"), "总仓");
        String zoneCode = str(req.get("zoneCode"));
        if (zoneCode.isBlank()) throw new IllegalArgumentException("必须指定所属集货区");
        BigDecimal capQty = bd(req.get("capacityQty"));
        int pickSeq = intOr(req.get("pickSeq"), 0);
        String remark = str(req.get("remark"));
        if (id.isBlank()) {
            id = "BN" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("""
                    INSERT INTO wms_bin (bin_id, bin_code, bin_name, warehouse, zone_code, bin_type,
                        capacity_qty, pick_seq, status, remark)
                    VALUES (?, ?, ?, ?, ?, 'COLLECT', ?, ?, 'NORMAL', ?)
                    """, id, code, str(req.get("binName")), warehouse, zoneCode, capQty, pickSeq, remark);
        } else {
            jdbc.update("""
                    UPDATE wms_bin SET bin_code=?, bin_name=?, zone_code=?, capacity_qty=?, pick_seq=?, remark=?
                     WHERE bin_id=? AND bin_type='COLLECT'
                    """, code, str(req.get("binName")), zoneCode, capQty, pickSeq, remark, id);
        }
        TmsUtil.log(jdbc, "wms.base", "SAVE_COLLECT_BIN", code, "维护集货位");
        return ApiResponse.ok(Map.of("binId", id));
    }

    /** 集货位停用/启用：翻转 status（DISABLED/NORMAL）。 */
    @PostMapping("/collection-bin/toggle")
    public ApiResponse<Map<String, Object>> toggleCollectionBin(@RequestBody Map<String, Object> req) {
        String id = str(req.get("binId"));
        if (id.isBlank()) throw new IllegalArgumentException("binId 不能为空");
        String current = jdbc.queryForList(
                "SELECT status FROM wms_bin WHERE bin_id=? AND bin_type='COLLECT'", id)
                .stream().findFirst().map(m -> str(m.get("status"))).orElse("NORMAL");
        String target = "DISABLED".equalsIgnoreCase(current) ? "NORMAL" : "DISABLED";
        jdbc.update("UPDATE wms_bin SET status=? WHERE bin_id=? AND bin_type='COLLECT'", target, id);
        TmsUtil.log(jdbc, "wms.base", "TOGGLE_COLLECT_BIN", id,
                target.equals("DISABLED") ? "停用集货位" : "启用集货位");
        return ApiResponse.ok(Map.of("status", target));
    }

    /** 锁定集货位：人工挂起，置 frozen='Y'，不参与自动分配（区别于 used_qty 实际占用）。 */
    @PostMapping("/collection-bin/lock")
    public ApiResponse<Map<String, Object>> lockCollectionBin(@RequestBody Map<String, Object> req) {
        String id = str(req.get("binId"));
        if (id.isBlank()) throw new IllegalArgumentException("binId 不能为空");
        jdbc.update("UPDATE wms_bin SET frozen='Y' WHERE bin_id=? AND bin_type='COLLECT'", id);
        TmsUtil.log(jdbc, "wms.base", "LOCK_COLLECT_BIN", id, "锁定集货位");
        return ApiResponse.ok(Map.of("frozen", "Y"));
    }

    /** 释放集货位：解除人工锁定，置 frozen='N'，恢复参与自动分配。 */
    @PostMapping("/collection-bin/release")
    public ApiResponse<Map<String, Object>> releaseCollectionBin(@RequestBody Map<String, Object> req) {
        String id = str(req.get("binId"));
        if (id.isBlank()) throw new IllegalArgumentException("binId 不能为空");
        jdbc.update("UPDATE wms_bin SET frozen='N' WHERE bin_id=? AND bin_type='COLLECT'", id);
        TmsUtil.log(jdbc, "wms.base", "RELEASE_COLLECT_BIN", id, "释放集货位");
        return ApiResponse.ok(Map.of("frozen", "N"));
    }

    /** 删除集货位：无在库才允许删。 */
    @PostMapping("/collection-bin/delete")
    public ApiResponse<Map<String, Object>> deleteCollectionBin(@RequestBody Map<String, Object> req) {
        String id = str(req.get("binId"));
        if (id.isBlank()) throw new IllegalArgumentException("binId 不能为空");
        Map<String, Object> b = jdbc.queryForList(
                "SELECT warehouse, bin_code FROM wms_bin WHERE bin_id=? AND bin_type='COLLECT'", id)
                .stream().findFirst().orElse(null);
        if (b == null) return ApiResponse.ok(Map.of("success", true));
        BigDecimal stock = jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty),0) FROM wms_bin_stock WHERE warehouse=? AND bin_code=?",
                BigDecimal.class, str(b.get("warehouse")), str(b.get("bin_code")));
        if (stock != null && stock.signum() > 0) {
            throw new IllegalArgumentException("集货位还有 " + stock.stripTrailingZeros().toPlainString()
                    + " 件在库，请先移位或清空后再删除");
        }
        jdbc.update("DELETE FROM wms_bin WHERE bin_id=? AND bin_type='COLLECT'", id);
        TmsUtil.log(jdbc, "wms.base", "DELETE_COLLECT_BIN", id, "删除集货位");
        return ApiResponse.ok(Map.of("success", true));
    }

    /**
     * 批量导入集货位（V88）。body: { warehouse, zoneCode, rows:[{binCode,binName,capacityQty,pickSeq,remark}] }
     * 按 (warehouse, bin_code) upsert，强制 bin_type='COLLECT'。返回 {inserted,updated,failed}。
     */
    @PostMapping("/collection-bin/import")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> importCollectionBins(@RequestBody Map<String, Object> req) {
        String warehouse = strOr(req.get("warehouse"), "总仓");
        String zoneCode = str(req.get("zoneCode"));
        if (zoneCode.isBlank()) throw new IllegalArgumentException("必须指定所属集货区");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) req.getOrDefault("rows", List.of());
        if (rows.isEmpty()) throw new IllegalArgumentException("没有可导入的数据");
        int inserted = 0, updated = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        int defaultSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(pick_seq),0)+1 FROM wms_bin WHERE warehouse=? AND zone_code=? AND bin_type='COLLECT'",
                Integer.class, warehouse, zoneCode);
        for (Map<String, Object> r : rows) {
            String code = str(r.get("binCode"));
            if (code.isBlank()) { failed.add(Map.of("binCode", "", "reason", "集货位编码为空")); continue; }
            try {
                List<Map<String, Object>> exist = jdbc.queryForList(
                        "SELECT bin_id FROM wms_bin WHERE warehouse=? AND bin_code=?", warehouse, code);
                BigDecimal capQty = bd(r.get("capacityQty"));
                int seq = intOr(r.get("pickSeq"), defaultSeq);
                String binName = str(r.get("binName"));
                String remark = str(r.get("remark"));
                if (exist.isEmpty()) {
                    String id = "BN" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
                    jdbc.update("""
                            INSERT INTO wms_bin (bin_id, bin_code, bin_name, warehouse, zone_code, bin_type,
                                capacity_qty, pick_seq, status, remark)
                            VALUES (?, ?, ?, ?, ?, 'COLLECT', ?, ?, 'NORMAL', ?)
                            """, id, code, binName, warehouse, zoneCode, capQty, seq, remark);
                    inserted++;
                } else {
                    jdbc.update("""
                            UPDATE wms_bin SET bin_name=?, bin_type='COLLECT', capacity_qty=?, pick_seq=?, remark=?
                             WHERE bin_id=?
                            """, binName, capQty, seq, remark, exist.get(0).get("bin_id"));
                    updated++;
                }
                defaultSeq = Math.max(defaultSeq, seq) + 1;
            } catch (Exception e) {
                failed.add(Map.of("binCode", code, "reason", e.getMessage() == null ? "未知错误" : e.getMessage()));
            }
        }
        TmsUtil.log(jdbc, "wms.base", "IMPORT_COLLECT_BIN", zoneCode,
                "导入集货位 新增" + inserted + " 更新" + updated + " 失败" + failed.size());
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("inserted", inserted);
        result.put("updated", updated);
        result.put("failed", failed);
        return ApiResponse.ok(result);
    }

    // -------- 拣货位商品绑定（PC） --------

    @PostMapping("/binding/page")
    public ApiResponse<PageResult<Map<String, Object>>> bindings(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        List<Map<String, Object>> all = bindingService.list(
                str(f.get("warehouse")), str(f.get("zoneCode")), str(f.get("binCode")),
                str(f.get("keyword")), str(f.get("activeFlag")));
        return ApiResponse.ok(PageResult.of(all, request));
    }

    @PostMapping("/binding/bind")
    public ApiResponse<Map<String, Object>> bind(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(bindingService.bind(req));
    }

    @PostMapping("/binding/unbind")
    public ApiResponse<Map<String, Object>> unbind(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(bindingService.unbind(req));
    }

    @PostMapping("/binding/transfer")
    public ApiResponse<Map<String, Object>> transferBinding(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(bindingService.transfer(req));
    }

    @PostMapping("/binding/thresholds")
    public ApiResponse<Map<String, Object>> bindingThresholds(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(bindingService.updateThresholds(req));
    }

    /** 绑定变更日志查询（PC 审计）。 */
    @PostMapping("/binding/log-page")
    public ApiResponse<PageResult<Map<String, Object>>> bindingLogs(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT log_id, warehouse, goods_code, from_bin, to_bin, change_type, operator, source, remark, occurred_at
                  FROM wms_binding_change_log WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (!str(f.get("warehouse")).isBlank()) { sql.append(" AND warehouse=?"); args.add(f.get("warehouse")); }
        if (!str(f.get("goodsCode")).isBlank()) { sql.append(" AND goods_code=?"); args.add(f.get("goodsCode")); }
        if (!str(f.get("changeType")).isBlank()) { sql.append(" AND change_type=?"); args.add(f.get("changeType")); }
        sql.append(" ORDER BY occurred_at DESC");
        return ApiResponse.ok(PageResult.of(TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray()), request));
    }

    /** 库位库存（实物位置账）查询，与财务库存分开展示。 */
    @PostMapping("/bin-stock/page")
    public ApiResponse<PageResult<Map<String, Object>>> binStock(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(TmsUtil.queryCamel(jdbc, """
                SELECT bin_stock_id, goods_code, goods_name, warehouse, batch_no, bin_code, container_code,
                       qty, locked_qty, updated_at FROM wms_bin_stock ORDER BY warehouse, bin_code, goods_code
                """), request));
    }

    // -------- 订单池 / 预分配下放 --------

    @PostMapping("/order-pool")
    public ApiResponse<List<Map<String, Object>>> orderPool(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        return ApiResponse.ok(service.orderPool(str(r.get("warehouse")), str(r.get("keyword"))));
    }

    /** 预分配页左侧：按线路分组的待下放订单统计。 */
    @PostMapping("/route-pool")
    public ApiResponse<List<Map<String, Object>>> routePool(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        return ApiResponse.ok(service.routePool(str(r.get("warehouse"))));
    }

    /** 预分配页右侧：按选中线路过滤的待下放订单。routeLine="__NONE__" 或空 = 未分配。 */
    @PostMapping("/order-pool/by-route")
    public ApiResponse<List<Map<String, Object>>> orderPoolByRoute(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        return ApiResponse.ok(service.orderPoolByRoute(str(r.get("warehouse")),
                str(r.get("routeLine")), str(r.get("keyword"))));
    }

    @PostMapping("/wave/release")
    public ApiResponse<Map<String, Object>> release(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(service.release(request));
    }

    // -------- 波次管理 --------

    @PostMapping("/wave/page")
    public ApiResponse<PageResult<Map<String, Object>>> waves(@RequestBody PageRequest request) {
        String status = null;
        if (request.filters() != null) {
            Object s = request.filters().get("status");
            if (s != null && !s.toString().isBlank()) status = s.toString();
        }
        return ApiResponse.ok(paginate(service.listWaves(status), request));
    }

    @GetMapping("/wave/detail")
    public ApiResponse<Map<String, Object>> waveDetail(@RequestParam String waveId) {
        return ApiResponse.ok(service.waveDetail(waveId));
    }

    @PostMapping("/wave/expedite")
    public ApiResponse<Map<String, Object>> expediteWave(@RequestBody Map<String, Object> req) {
        service.expediteWave(str(req.get("waveId")), "Y".equalsIgnoreCase(str(req.get("expedited"))) ? false : true);
        return ApiResponse.ok(Map.of("success", true));
    }

    @PostMapping("/wave/order-expedite")
    public ApiResponse<Map<String, Object>> expediteOrder(@RequestBody Map<String, Object> req) {
        service.expediteOrder(str(req.get("waveId")), str(req.get("orderNo")),
                !"N".equalsIgnoreCase(str(req.get("expedited"))));
        return ApiResponse.ok(Map.of("success", true));
    }

    @PostMapping("/wave/cancel")
    public ApiResponse<Map<String, Object>> cancel(@RequestBody Map<String, Object> req) {
        service.cancelRelease(str(req.get("waveId")), str(req.get("orderNo")),
                "Y".equalsIgnoreCase(str(req.get("supervisor"))));
        return ApiResponse.ok(Map.of("success", true));
    }

    // -------- 拣货任务（PC 视角） --------

    @PostMapping("/pick/task-page")
    public ApiResponse<PageResult<Map<String, Object>>> pickTasks(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(paginate(service.pickTasks(
                str(f.get("assignee")), strOr(f.get("scope"), "all"), str(f.get("zone"))), request));
    }

    @GetMapping("/pick/task-detail")
    public ApiResponse<Map<String, Object>> pickTaskDetail(@RequestParam String taskId) {
        return ApiResponse.ok(service.pickTaskDetail(taskId));
    }

    @PostMapping("/pick/claim")
    public ApiResponse<Map<String, Object>> claim(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.claimTask(str(req.get("taskId")),
                strOr(req.get("assignee"), TmsUtil.currentUser()),
                "Y".equalsIgnoreCase(str(req.get("help")))));
    }

    @PostMapping("/pick/complete")
    public ApiResponse<Map<String, Object>> complete(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.completeTask(str(req.get("taskId")),
                strOr(req.get("assignee"), TmsUtil.currentUser())));
    }

    // -------- 分拣指令查询 --------

    @PostMapping("/sort/instruction-page")
    public ApiResponse<PageResult<Map<String, Object>>> sortInstructions(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(paginate(service.sortInstructions(str(f.get("zoneCode"))), request));
    }

    // -------- 复核（PC 一键复检） --------

    @PostMapping("/recheck/pass")
    public ApiResponse<Map<String, Object>> recheckPass(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.recheckOrder(str(req.get("waveId")), str(req.get("orderNo")),
                strOr(req.get("operator"), TmsUtil.currentUser()), strOr(req.get("checkScope"), "0"),
                bd(req.get("shortQty")), true));
    }

    @PostMapping("/recheck/fail")
    public ApiResponse<Map<String, Object>> recheckFail(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.recheckOrder(str(req.get("waveId")), str(req.get("orderNo")),
                strOr(req.get("operator"), TmsUtil.currentUser()), strOr(req.get("checkScope"), "0"),
                bd(req.get("shortQty")), false));
    }

    // -------- 装车 / 发运 --------

    @GetMapping("/load/loadable")
    public ApiResponse<List<Map<String, Object>>> loadable(@RequestParam String waveId) {
        return ApiResponse.ok(service.loadableOrders(waveId));
    }

    @PostMapping("/load/ship")
    public ApiResponse<Map<String, Object>> ship(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.ship(str(req.get("waveId")), str(req.get("vehiclePlate")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    // -------- 异常中心 --------

    @PostMapping("/exception/page")
    public ApiResponse<PageResult<Map<String, Object>>> exceptions(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(TmsUtil.queryCamel(jdbc, """
                SELECT exception_id, exception_no, wave_id, wave_no, source_order_no, goods_code, exception_type,
                       qty, status, description, resolution, reporter, assignee, created_at, resolved_at
                FROM wms_exception ORDER BY created_at DESC
                """), request));
    }

    // ---- helpers ----

    /**
     * 对「服务层已自行过滤/排序」的结果只做内存分页，避免 {@link PageResult#of} 再用 filters 做通用子串匹配
     * （filters 里混有 scope/assignee/zoneCode 等控制键，会误删行——例如 scope=all 会过滤掉不含 "all" 的记录）。
     */
    private static <T> PageResult<T> paginate(List<T> records, PageRequest request) {
        PageRequest pagingOnly = new PageRequest(request.pageNo(), request.pageSize(),
                request.sortField(), request.sortOrder(), null);
        return PageResult.of(records, pagingOnly);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String strOr(Object o, String dft) { String s = str(o); return s.isEmpty() ? dft : s; }
    private static int intOr(Object o, int dft) {
        if (o == null) return dft;
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return dft; }
    }
    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(o).trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
