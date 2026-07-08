package com.erp.inventory;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.inventory.service.InventoryCostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 报损单 REST 端点。
 * <p>
 * 流程：新建（DRAFT/PENDING）→ 编辑 → 审核（扣减库存 + 写流水 + 记成本）→ 反审核（回库）。
 * 支持作废（CANCELLED）和删除（仅草稿）。
 * <p>
 * 全部使用 JdbcTemplate 直写，DTO 用 {@code Map<String, Object>}。
 */
@RestController
@RequestMapping("/inventory")
public class DamageController {

    private final JdbcTemplate jdbcTemplate;
    private final InventoryCostService inventoryCostService;
    private final com.erp.common.util.BillNoGenerator billNoGen;

    public DamageController(JdbcTemplate jdbcTemplate,
                            InventoryCostService inventoryCostService,
                            com.erp.common.util.BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.inventoryCostService = inventoryCostService;
        this.billNoGen = billNoGen;
    }

    // ========================================================================
    //  报损单列表
    // ========================================================================

    @PostMapping("/damage/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT damage_id, damage_no, warehouse, bill_date,
                       qty, amount, cost_amount, status,
                       creator_name, audit_user, audit_time, create_time, remark
                FROM inv_damage
                ORDER BY create_time DESC, damage_no DESC
                """);
        List<Map<String, Object>> mapped = rows.stream().map(r -> {
            Map<String, Object> row = camelize(r);
            String st = str(pick(r, "status"));
            row.put("statusText", statusText(st));
            return row;
        }).collect(Collectors.toList());
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    // ========================================================================
    //  报损单详情
    // ========================================================================

    @GetMapping("/damage/detail")
    public ApiResponse<Map<String, Object>> detail(
            @RequestParam(required = false) String damageId,
            @RequestParam(required = false) String id) {
        String key = damageId != null && !damageId.isBlank() ? damageId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 damageId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM inv_damage WHERE damage_id = ? OR damage_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "报损单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_damage_detail WHERE damage_id = ? ORDER BY detail_id",
                head.get("damageId"));
        head.put("details", details.stream().map(DamageController::camelize).toList());
        return ApiResponse.ok(head);
    }

    // ========================================================================
    //  商品查询（用于手工添加商品，带成本单价和可用库存）
    // ========================================================================

    @GetMapping("/damage/goods-options")
    public ApiResponse<List<Map<String, Object>>> goodsOptions(
            @RequestParam String warehouse,
            @RequestParam(required = false) String keyword) {
        String kw = (keyword == null || keyword.isBlank())
                ? null : "%" + keyword.trim().toLowerCase() + "%";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT g.goods_code, g.goods_name, g.spec, g.base_unit,
                       COALESCE(sb.cost_price, 0) AS cost_price,
                       COALESCE(sb.available_qty, 0) AS available_stock,
                       COALESCE(sb.warehouse, ?) AS warehouse
                FROM base_goods g
                LEFT JOIN inv_stock_balance sb
                       ON sb.goods_code = g.goods_code AND sb.warehouse = ?
                WHERE COALESCE(g.status, 'NORMAL') <> 'STOPPED'
                  AND (? IS NULL OR LOWER(g.goods_code) LIKE ? OR LOWER(g.goods_name) LIKE ?
                       OR LOWER(COALESCE(g.barcode, '')) LIKE ?)
                ORDER BY g.goods_code
                """, warehouse, warehouse, kw, kw, kw, kw);
        return ApiResponse.ok(rows.stream().map(DamageController::camelize).toList());
    }

    // ========================================================================
    //  批次库存查询（用于【添加商品】窗口：报损仓库下有可用库存的批次记录）
    // ========================================================================

    /**
     * 一行 = 一条批次库存记录（goods_code + warehouse + batch_no）。
     * 仅返回可用库存 > 0 且商品未停用的批次；到期日期缺失时按 生产日期 + 保质期天数 推算。
     */
    @GetMapping("/damage/batch-stock-options")
    public ApiResponse<List<Map<String, Object>>> batchStockOptions(
            @RequestParam String warehouse,
            @RequestParam(required = false) String keyword) {
        String kw = (keyword == null || keyword.isBlank())
                ? null : "%" + keyword.trim().toLowerCase() + "%";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bs.goods_code,
                       COALESCE(g.goods_name, bs.goods_name) AS goods_name,
                       g.spec,
                       g.base_unit,
                       g.barcode,
                       g.brand_name,
                       g.category_name,
                       COALESCE(g.shelf_life_days, 0) AS shelf_life_days,
                       bs.batch_no,
                       bs.production_date,
                       bs.expiry_date,
                       bs.cost_price,
                       (bs.qty - COALESCE(bs.locked_qty, 0) - COALESCE(bs.frozen_qty, 0)) AS available_qty
                FROM inv_batch_stock bs
                LEFT JOIN base_goods g ON g.goods_code = bs.goods_code
                WHERE bs.warehouse = ?
                  AND (bs.qty - COALESCE(bs.locked_qty, 0) - COALESCE(bs.frozen_qty, 0)) > 0
                  AND COALESCE(bs.status, 'NORMAL') = 'NORMAL'
                  AND COALESCE(g.status, 'NORMAL') <> 'STOPPED'
                  AND (? IS NULL OR LOWER(bs.goods_code) LIKE ?
                       OR LOWER(COALESCE(g.goods_name, bs.goods_name)) LIKE ?
                       OR LOWER(COALESCE(g.barcode, '')) LIKE ?
                       OR LOWER(bs.batch_no) LIKE ?)
                ORDER BY bs.goods_code, bs.expiry_date NULLS LAST, bs.batch_no
                """, warehouse, kw, kw, kw, kw, kw);

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            LocalDate prodDate = parseDate(row.get("productionDate"), null);
            LocalDate expiry = parseDate(row.get("expiryDate"), null);
            int shelfLife = toBd(row.get("shelfLifeDays")).intValue();
            // 到期日期缺失时用 生产日期 + 保质期 推算
            if (expiry == null && prodDate != null && shelfLife > 0) {
                expiry = prodDate.plusDays(shelfLife);
            }
            // 日期统一序列化为 yyyy-MM-dd 字符串，前端直接展示
            row.put("productionDate", prodDate == null ? null : prodDate.toString());
            row.put("expiryDate", expiry == null ? null : expiry.toString());
            row.put("remainingDays", expiry == null ? null : ChronoUnit.DAYS.between(today, expiry));
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    // ========================================================================
    //  批次下拉（指定仓库+商品的可用批次）
    // ========================================================================

    @GetMapping("/damage/batch-options")
    public ApiResponse<List<Map<String, Object>>> batchOptions(
            @RequestParam String goodsCode,
            @RequestParam String warehouse) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bs.batch_no,
                       bs.production_date,
                       bs.expiry_date,
                       bs.qty,
                       (bs.qty - COALESCE(bs.locked_qty, 0) - COALESCE(bs.frozen_qty, 0)) AS available_qty,
                       bs.cost_price
                FROM inv_batch_stock bs
                WHERE bs.goods_code = ? AND bs.warehouse = ? AND bs.qty > 0
                ORDER BY bs.production_date, bs.batch_no
                """, goodsCode, warehouse);
        return ApiResponse.ok(rows.stream().map(DamageController::camelize).toList());
    }

    // ========================================================================
    //  新建报损单
    // ========================================================================

    @PostMapping("/damage/create")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        String warehouse = str(request.get("warehouse"));
        if (warehouse.isBlank()) throw new IllegalArgumentException("请选择仓库");
        LocalDate billDate = parseDate(request.get("billDate"), LocalDate.now());
        // 报损单不设草稿：保存后直接进入待审核（未审核）状态
        String status = "PENDING";
        String remark = str(request.get("remark"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        if (reqDetails.isEmpty()) throw new IllegalArgumentException("报损明细不能为空");

        // 校验数量 / 批次不重复 / 可用库存
        validateDetails(warehouse, reqDetails);

        // 计算汇总
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCostAmount = BigDecimal.ZERO;
        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("qty"));
            BigDecimal p = toBd(line.get("price"));
            BigDecimal a = q.multiply(p).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cp = toBd(line.get("costPrice"));
            BigDecimal ca = q.multiply(cp).setScale(2, RoundingMode.HALF_UP);
            line.put("_amount", a);
            line.put("_costAmount", ca);
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(a);
            totalCostAmount = totalCostAmount.add(ca);
        }

        String damageId = "DMG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String damageNo = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.DAMAGE, "inv_damage", "damage_no");

        jdbcTemplate.update("""
                INSERT INTO inv_damage(damage_id, damage_no, warehouse, bill_date,
                    qty, amount, cost_amount, status, creator_name, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '系统管理员', ?)
                """, damageId, damageNo, warehouse, billDate,
                totalQty, totalAmount, totalCostAmount, status, remark);

        for (Map<String, Object> line : reqDetails) {
            insertDetail(damageId, line);
        }

        log("inventory.damage", "CREATE", damageNo, "创建报损单");
        return ApiResponse.ok(Map.of("damageId", damageId, "damageNo", damageNo, "status", status));
    }

    // ========================================================================
    //  编辑报损单
    // ========================================================================

    @PostMapping("/damage/update")
    @Transactional
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> request) {
        String damageId = str(request.get("damageId"));
        if (damageId.isBlank()) throw new IllegalArgumentException("缺少 damageId");

        Map<String, Object> existing = findDamageById(damageId);
        String status = str(pick(existing, "status"));
        if (!"DRAFT".equals(status) && !"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅未审核的报损单可修改，当前状态：" + statusText(status));
        }

        String warehouse = strOrDefault(request.get("warehouse"), str(pick(existing, "warehouse")));
        LocalDate billDate = parseDate(request.get("billDate"), parseDate(pick(existing, "bill_date"), LocalDate.now()));
        String remark = strOrDefault(request.get("remark"), str(pick(existing, "remark")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        if (reqDetails.isEmpty()) {
            List<Map<String, Object>> oldDetails = jdbcTemplate.queryForList(
                    "SELECT * FROM inv_damage_detail WHERE damage_id = ?", damageId);
            for (Map<String, Object> od : oldDetails) {
                reqDetails.add(camelize(od));
            }
        }
        if (reqDetails.isEmpty()) throw new IllegalArgumentException("报损明细不能为空");

        // 校验数量 / 批次不重复 / 可用库存（未审核单据尚未扣减库存，可直接与批次可用量比对）
        validateDetails(warehouse, reqDetails);

        jdbcTemplate.update("DELETE FROM inv_damage_detail WHERE damage_id = ?", damageId);

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCostAmount = BigDecimal.ZERO;
        for (Map<String, Object> line : reqDetails) {
            BigDecimal q = toBd(line.get("qty"));
            BigDecimal p = toBd(line.get("price"));
            BigDecimal a = q.multiply(p).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cp = toBd(line.get("costPrice"));
            BigDecimal ca = q.multiply(cp).setScale(2, RoundingMode.HALF_UP);
            line.put("_amount", a);
            line.put("_costAmount", ca);
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(a);
            totalCostAmount = totalCostAmount.add(ca);
        }

        jdbcTemplate.update("""
                UPDATE inv_damage SET warehouse=?, bill_date=?, qty=?, amount=?, cost_amount=?, remark=?
                WHERE damage_id=?
                """, warehouse, billDate, totalQty, totalAmount, totalCostAmount, remark, damageId);

        for (Map<String, Object> line : reqDetails) {
            insertDetail(damageId, line);
        }

        log("inventory.damage", "UPDATE", str(pick(existing, "damage_no")), "修改报损单");
        return ApiResponse.ok(Map.of("damageId", damageId, "status", status));
    }

    // ========================================================================
    //  审核报损单
    // ========================================================================

    /**
     * 审核报损单：PENDING → APPROVED。
     * 按当前库存成本单价计价，扣减库存，写流水。
     */
    @PostMapping("/damage/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> audit(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> damage = findDamageById(request.bizId());
        String status = str(pick(damage, "status"));
        if (!"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅待审核的报损单可审核，当前状态：" + statusText(status));
        }
        String damageId = str(pick(damage, "damage_id"));
        String damageNo = str(pick(damage, "damage_no"));
        String warehouse = str(pick(damage, "warehouse"));

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_damage_detail WHERE damage_id = ?", damageId);

        // 逐行校验可用库存
        for (Map<String, Object> d : details) {
            String goodsCode = str(pick(d, "goods_code"));
            String goodsName = str(pick(d, "goods_name"));
            BigDecimal qty = toBd(pick(d, "qty"));
            String batchNo = str(pick(d, "batch_no"));

            // 校验批次可用库存
            if (!batchNo.isBlank()) {
                BigDecimal batchAvailable = toBd(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(qty - COALESCE(locked_qty, 0) - COALESCE(frozen_qty, 0)), 0)
                        FROM inv_batch_stock
                        WHERE goods_code = ? AND warehouse = ? AND batch_no = ?
                        """, BigDecimal.class, goodsCode, warehouse, batchNo));
                if (qty.compareTo(batchAvailable) > 0) {
                    throw new IllegalArgumentException(
                            "商品 " + goodsName + "（批次 " + batchNo + "） 报损数量 " + plain(qty)
                                    + " 超过该批次可用库存 " + plain(batchAvailable) + "，无法审核");
                }
            }
        }

        BigDecimal totalCostAmount = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            String goodsCode = str(pick(d, "goods_code"));
            String goodsName = str(pick(d, "goods_name"));
            String batchNo = str(pick(d, "batch_no"));
            BigDecimal qty = toBd(pick(d, "qty"));

            // 取当前库存成本单价
            BigDecimal costPrice = inventoryCostService.getCurrentCostPrice(goodsCode, warehouse);
            BigDecimal costAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
            totalCostAmount = totalCostAmount.add(costAmount);

            // 扣减库存 + 写流水
            inventoryCostService.salesOutbound(goodsCode, goodsName, warehouse, batchNo, qty, damageNo);

            // 回写成本到明细
            jdbcTemplate.update("""
                    UPDATE inv_damage_detail SET cost_price=?, cost_amount=?
                    WHERE detail_id=?
                    """, costPrice, costAmount, str(pick(d, "detail_id")));
        }

        jdbcTemplate.update("""
                UPDATE inv_damage SET status='APPROVED', cost_amount=?,
                    audit_user='系统管理员', audit_time=CURRENT_TIMESTAMP
                WHERE damage_id=?
                """, totalCostAmount, damageId);

        log("inventory.damage", "AUDIT", damageNo, "报损单审核 → 扣减库存，成本 " + totalCostAmount);
        return ApiResponse.ok(Map.of(
                "damageId", damageId, "damageNo", damageNo, "status", "APPROVED",
                "costAmount", totalCostAmount,
                "effect", "报损审核完成：已扣减库存，成本金额 " + totalCostAmount));
    }

    // ========================================================================
    //  反审核报损单
    // ========================================================================

    /**
     * 反审核：APPROVED → PENDING，回库恢复库存。
     */
    @PostMapping("/damage/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAudit(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> damage = findDamageById(request.bizId());
        String status = str(pick(damage, "status"));
        if (!"APPROVED".equals(status)) {
            throw new IllegalArgumentException("仅已审核的报损单可反审核，当前状态：" + statusText(status));
        }
        String damageId = str(pick(damage, "damage_id"));
        String damageNo = str(pick(damage, "damage_no"));
        String warehouse = str(pick(damage, "warehouse"));

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_damage_detail WHERE damage_id = ?", damageId);

        for (Map<String, Object> d : details) {
            String goodsCode = str(pick(d, "goods_code"));
            String goodsName = str(pick(d, "goods_name"));
            String batchNo = str(pick(d, "batch_no"));
            BigDecimal qty = toBd(pick(d, "qty"));
            BigDecimal costPrice = toBd(pick(d, "cost_price"));

            // 回库：使用审核时记录的成本单价回写
            inventoryCostService.purchaseInbound(goodsCode, goodsName, warehouse, batchNo, qty, costPrice,
                    damageNo + "(反审核)");
        }

        jdbcTemplate.update("""
                UPDATE inv_damage SET status='PENDING',
                    audit_user=NULL, audit_time=NULL
                WHERE damage_id=?
                """, damageId);

        log("inventory.damage", "REVERSE_AUDIT", damageNo, "报损单反审核 → 恢复库存");
        return ApiResponse.ok(Map.of("damageId", damageId, "status", "PENDING", "effect", "已反审核，库存已恢复"));
    }

    // ========================================================================
    //  删除报损单
    // ========================================================================

    @PostMapping("/damage/delete")
    @Transactional
    public ApiResponse<Map<String, Object>> delete(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> damage = findDamageById(request.bizId());
        String status = str(pick(damage, "status"));
        if (!"DRAFT".equals(status) && !"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅未审核的报损单可删除，当前状态：" + statusText(status));
        }
        String damageId = str(pick(damage, "damage_id"));
        String damageNo = str(pick(damage, "damage_no"));
        jdbcTemplate.update("DELETE FROM inv_damage_detail WHERE damage_id = ?", damageId);
        jdbcTemplate.update("DELETE FROM inv_damage WHERE damage_id = ?", damageId);
        log("inventory.damage", "DELETE", damageNo, "删除未审核报损单");
        return ApiResponse.ok(Map.of("damageId", damageId, "effect", "已删除"));
    }

    // ========================================================================
    //  作废报损单
    // ========================================================================

    /**
     * 作废：任意非 CANCELLED 状态 → CANCELLED。
     * 已审核的单据先反审核回滚库存再作废。
     */
    @PostMapping("/damage/cancel")
    @Transactional
    public ApiResponse<Map<String, Object>> cancel(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> damage = findDamageById(request.bizId());
        String status = str(pick(damage, "status"));
        if ("CANCELLED".equals(status)) {
            throw new IllegalArgumentException("报损单已作废");
        }
        String damageId = str(pick(damage, "damage_id"));
        String damageNo = str(pick(damage, "damage_no"));
        String warehouse = str(pick(damage, "warehouse"));

        // 已审核的需先回滚库存
        if ("APPROVED".equals(status)) {
            List<Map<String, Object>> details = jdbcTemplate.queryForList(
                    "SELECT * FROM inv_damage_detail WHERE damage_id = ?", damageId);
            for (Map<String, Object> d : details) {
                String goodsCode = str(pick(d, "goods_code"));
                String goodsName = str(pick(d, "goods_name"));
                String batchNo = str(pick(d, "batch_no"));
                BigDecimal qty = toBd(pick(d, "qty"));
                BigDecimal costPrice = toBd(pick(d, "cost_price"));
                inventoryCostService.purchaseInbound(goodsCode, goodsName, warehouse, batchNo, qty, costPrice,
                        damageNo + "(作废回库)");
            }
        }

        jdbcTemplate.update("""
                UPDATE inv_damage SET status='CANCELLED',
                    audit_user=NULL, audit_time=NULL
                WHERE damage_id=?
                """, damageId);

        log("inventory.damage", "CANCEL", damageNo, "作废报损单" + ("APPROVED".equals(status) ? "（已回滚库存）" : ""));
        return ApiResponse.ok(Map.of("damageId", damageId, "status", "CANCELLED",
                "effect", "已作废" + ("APPROVED".equals(status) ? "，库存已回滚" : "")));
    }

    // ========================================================================
    //  Excel 导入：按仓库分组生成报损单
    // ========================================================================

    /**
     * 导入 JSON 行数组生成报损单。
     * 每行字段：warehouse（仓库名称或编码，必填，需系统存在）、goodsCode、batchNo、productionDate、qty。
     * 按仓库分组，每组生成一张报损单（状态 PENDING）。
     */
    @PostMapping("/damage/import")
    @Transactional
    public ApiResponse<Map<String, Object>> importDamage(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawRows = request.get("rows") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        if (rawRows.isEmpty()) throw new IllegalArgumentException("未解析到有效数据行");

        // 校验仓库存在 + 取仓库名称（同时支持编号和名称匹配）
        Map<String, String> whCodeToName = new HashMap<>();
        Map<String, String> whNameToName = new HashMap<>();
        List<Map<String, Object>> allWh = jdbcTemplate.queryForList(
                "SELECT warehouse_code, warehouse_name FROM base_warehouse WHERE status = 'NORMAL'");
        for (Map<String, Object> wh : allWh) {
            String code = str(pick(wh, "warehouse_code"));
            String name = str(pick(wh, "warehouse_name"));
            if (!code.isBlank()) whCodeToName.put(code, name);
            if (!name.isBlank()) whNameToName.put(name, name);
        }

        // 校验商品存在
        Set<String> allGoodsCodes = new HashSet<>();
        List<Map<String, Object>> allGoods = jdbcTemplate.queryForList(
                "SELECT goods_code FROM base_goods WHERE status != 'STOPPED'");
        for (Map<String, Object> g : allGoods) {
            allGoodsCodes.add(str(pick(g, "goods_code")));
        }

        List<String> errors = new ArrayList<>();
        // 按仓库分组
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> m = rawRows.get(i);
            String whInput = str(m.get("warehouse")).trim();
            if (whInput.isBlank()) {
                errors.add("第" + (i + 1) + "行：仓库不能为空");
                continue;
            }
            // 匹配仓库（先按编码，再按名称）
            String whName = whCodeToName.get(whInput);
            if (whName == null) whName = whNameToName.get(whInput);
            if (whName == null) {
                errors.add("第" + (i + 1) + "行：仓库不存在：" + whInput);
                continue;
            }
            m.put("_warehouseName", whName);

            // 校验商品编号
            String goodsCode = str(m.get("goodsCode")).trim();
            if (goodsCode.isBlank()) {
                errors.add("第" + (i + 1) + "行：商品编号不能为空");
                continue;
            }
            if (!allGoodsCodes.contains(goodsCode)) {
                errors.add("第" + (i + 1) + "行：商品编号不存在：" + goodsCode);
                continue;
            }

            // 校验数量
            BigDecimal qty = toBd(m.get("qty"));
            if (qty.signum() <= 0) {
                errors.add("第" + (i + 1) + "行：数量必须大于 0");
                continue;
            }

            grouped.computeIfAbsent(whName, k -> new ArrayList<>()).add(m);
        }

        if (grouped.isEmpty() && !errors.isEmpty()) {
            return ApiResponse.ok(Map.of("success", 0, "errors", errors, "effect", "所有行校验失败"));
        }

        // 按仓库分组生成报损单
        List<String> createdNos = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            String whName = entry.getKey();
            List<Map<String, Object>> lines = entry.getValue();

            // 补全商品信息 + 成本
            List<Map<String, Object>> detailRows = new ArrayList<>();
            BigDecimal totalQty = BigDecimal.ZERO;
            BigDecimal totalAmount = BigDecimal.ZERO;
            BigDecimal totalCostAmount = BigDecimal.ZERO;

            for (Map<String, Object> line : lines) {
                String goodsCode = str(line.get("goodsCode")).trim();
                List<Map<String, Object>> gRows = jdbcTemplate.queryForList(
                        "SELECT goods_name, spec, base_unit FROM base_goods WHERE goods_code = ?", goodsCode);
                String goodsName = gRows.isEmpty() ? goodsCode : str(pick(gRows.get(0), "goods_name"));
                String spec = gRows.isEmpty() ? "" : str(pick(gRows.get(0), "spec"));
                String unitName = gRows.isEmpty() ? "" : str(pick(gRows.get(0), "base_unit"));

                BigDecimal qty = toBd(line.get("qty"));

                // 取当前库存成本单价
                BigDecimal costPrice = inventoryCostService.getCurrentCostPrice(goodsCode, whName);
                BigDecimal costAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

                // 报损单价取成本单价（报损没有销售价概念）
                BigDecimal price = costPrice;
                BigDecimal amount = costAmount;

                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("goodsCode", goodsCode);
                detail.put("goodsName", goodsName);
                detail.put("spec", spec);
                detail.put("unitName", unitName);
                detail.put("qty", qty);
                detail.put("price", price);
                detail.put("amount", amount);
                detail.put("batchNo", str(line.get("batchNo")).trim());
                detail.put("productionDate", str(line.get("productionDate")).trim());
                detail.put("costPrice", costPrice);
                detail.put("costAmount", costAmount);
                detailRows.add(detail);

                totalQty = totalQty.add(qty);
                totalAmount = totalAmount.add(amount);
                totalCostAmount = totalCostAmount.add(costAmount);
            }

            // 生成报损单
            String damageId = "DMG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String damageNo = billNoGen.nextNo(
                    com.erp.common.util.BillNoGenerator.BillType.DAMAGE, "inv_damage", "damage_no");

            jdbcTemplate.update("""
                    INSERT INTO inv_damage(damage_id, damage_no, warehouse, bill_date,
                        qty, amount, cost_amount, status, creator_name, remark)
                    VALUES (?, ?, ?, CURRENT_DATE, ?, ?, ?, 'PENDING', '系统管理员', '(导入生成)')
                    """, damageId, damageNo, whName, totalQty, totalAmount, totalCostAmount);

            for (Map<String, Object> detail : detailRows) {
                insertDetail(damageId, detail);
            }

            createdNos.add(damageNo);
            log("inventory.damage", "IMPORT", damageNo,
                    "导入生成报损单，仓库：" + whName + "，行数：" + detailRows.size());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", createdNos.size());
        result.put("damageNos", createdNos);
        if (!errors.isEmpty()) result.put("errors", errors);
        result.put("effect", "导入完成：生成 " + createdNos.size() + " 张报损单"
                + (errors.isEmpty() ? "" : "，" + errors.size() + " 行校验失败"));
        return ApiResponse.ok(result);
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    private Map<String, Object> findDamageById(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM inv_damage WHERE damage_id = ? OR damage_no = ?", id, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("报损单不存在：" + id);
        return rows.get(0);
    }

    private void insertDetail(String damageId, Map<String, Object> line) {
        BigDecimal q = toBd(line.get("qty"));
        BigDecimal p = toBd(line.get("price"));
        BigDecimal a = line.get("_amount") instanceof BigDecimal bd
                ? bd : q.multiply(p).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cp = toBd(line.get("costPrice"));
        BigDecimal ca = line.get("_costAmount") instanceof BigDecimal bd2
                ? bd2 : q.multiply(cp).setScale(2, RoundingMode.HALF_UP);
        String detailId = "DMGD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO inv_damage_detail(detail_id, damage_id, goods_code, goods_name, spec, unit_name,
                    qty, price, amount, batch_no, production_date, cost_price, cost_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, detailId, damageId,
                str(line.get("goodsCode")), str(line.get("goodsName")), str(line.get("spec")),
                str(line.get("unitName")),
                q, p, a,
                str(line.get("batchNo")), parseDate(line.get("productionDate"), null),
                cp, ca);
    }

    /**
     * 明细通用校验：数量 > 0、同一「商品 + 批次」不可重复、数量不超过该批次实际可用库存。
     * 批次为空时退化为按 商品 + 仓库 的可用库存校验。
     */
    private void validateDetails(String warehouse, List<Map<String, Object>> reqDetails) {
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> line : reqDetails) {
            String goodsCode = str(line.get("goodsCode"));
            String goodsName = str(line.get("goodsName"));
            String batchNo = str(line.get("batchNo")).trim();
            BigDecimal qty = toBd(line.get("qty"));
            if (goodsCode.isBlank()) throw new IllegalArgumentException("报损明细存在未选择商品的行");
            if (qty.signum() <= 0) {
                throw new IllegalArgumentException("商品 " + goodsName + " 的报损数量必须大于 0");
            }
            if (!seen.add(goodsCode + "|" + batchNo)) {
                throw new IllegalArgumentException("商品 " + goodsName
                        + "（批次 " + (batchNo.isBlank() ? "无" : batchNo) + "）重复，同一批次只能报损一行");
            }
            // 以数据库实际可用量为准，避免前端传入过期的 availableStock
            BigDecimal available;
            if (batchNo.isBlank()) {
                available = toBd(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(qty - COALESCE(locked_qty, 0) - COALESCE(frozen_qty, 0)), 0)
                        FROM inv_batch_stock WHERE goods_code = ? AND warehouse = ?
                        """, Object.class, goodsCode, warehouse));
            } else {
                available = toBd(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(qty - COALESCE(locked_qty, 0) - COALESCE(frozen_qty, 0)), 0)
                        FROM inv_batch_stock WHERE goods_code = ? AND warehouse = ? AND batch_no = ?
                        """, Object.class, goodsCode, warehouse, batchNo));
            }
            if (qty.compareTo(available) > 0) {
                throw new IllegalArgumentException("商品 " + goodsName
                        + (batchNo.isBlank() ? "" : "（批次 " + batchNo + "）")
                        + " 报损数量 " + plain(qty) + " 超过可用库存 " + plain(available));
            }
        }
    }

    private static String statusText(String st) {
        return switch (st) {
            case "DRAFT" -> "草稿";
            case "PENDING" -> "待审核";
            case "APPROVED" -> "已审核";
            case "CANCELLED" -> "已作废";
            default -> st;
        };
    }

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                moduleCode, action, bizNo, detail);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String strOrDefault(Object o, String dft) {
        if (o == null) return dft;
        String s = String.valueOf(o);
        return s.isBlank() ? dft : s;
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(String.valueOf(o)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static LocalDate parseDate(Object o, LocalDate dft) {
        if (o == null) return dft;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date sd) return sd.toLocalDate();
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return dft;
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return dft; }
    }

    /** BigDecimal 去掉多余的尾随 0 */
    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** H2 大小写兼容 */
    private static Object pick(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        return row.get(key.toUpperCase(Locale.ROOT));
    }

    static Map<String, Object> camelize(Map<String, Object> row) {
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

    public record AuditRequest(@NotBlank String bizId, String remark) {}
}
