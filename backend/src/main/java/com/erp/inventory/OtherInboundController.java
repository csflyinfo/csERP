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
import java.util.*;
import java.util.stream.Collectors;

/**
 * 其他入库单 REST 端点。
 * <p>
 * 用于记录非采购/调拨类入库：期初库存、样品入库、赠品入库、盘外发现、内部加工等。
 * <p>
 * 流程：新建（未审核 PENDING）→ 编辑 → 审核（增加库存 + 写流水 + 重算成本）→ 反审核（回滚扣减）。
 * <p>无草稿态：保存即为「未审核」。DRAFT 仅作为历史遗留状态被容忍，新建不再产生。
 * 支持作废（CANCELLED）和删除（仅未审核）。
 * <p>
 * 业务规则：
 * <ul>
 *   <li>客户与供应商<b>二选一</b>，不可同时填写，也不可都为空</li>
 *   <li>明细单价默认取商品标准售价；成本单价建单时取「建单时点」库存成本，仅作参考</li>
 *   <li>审核时<b>重新取审核时点</b>的库存成本单价，据此写流水并回写单据成本（成本口径以审核时为准）</li>
 * </ul>
 * <p>
 * 全部使用 JdbcTemplate 直写，DTO 用 {@code Map<String, Object>}。
 */
@RestController
@RequestMapping("/inventory")
public class OtherInboundController {

    private final JdbcTemplate jdbcTemplate;
    private final InventoryCostService inventoryCostService;
    private final com.erp.common.util.BillNoGenerator billNoGen;

    public OtherInboundController(JdbcTemplate jdbcTemplate,
                                  InventoryCostService inventoryCostService,
                                  com.erp.common.util.BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.inventoryCostService = inventoryCostService;
        this.billNoGen = billNoGen;
    }

    // ========================================================================
    //  其他入库单列表
    // ========================================================================

    @PostMapping("/other-inbound/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT inbound_id, inbound_no, bill_date, customer, supplier, inbound_type,
                       warehouse, qty, amount, cost_amount, status,
                       creator_name, audit_user, audit_time, create_time, remark
                FROM inv_other_inbound
                ORDER BY create_time DESC, inbound_no DESC
                """);
        List<Map<String, Object>> mapped = rows.stream().map(r -> {
            Map<String, Object> row = camelize(r);
            String st = str(pick(r, "status"));
            row.put("statusText", statusText(st));
            // 列表「客户/供应商」列：二者只会有一个有值
            String customer = str(pick(r, "customer"));
            String supplier = str(pick(r, "supplier"));
            row.put("counterpartyName", !customer.isBlank() ? customer : supplier);
            return row;
        }).collect(Collectors.toList());
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    // ========================================================================
    //  其他入库单详情
    // ========================================================================

    @GetMapping("/other-inbound/detail")
    public ApiResponse<Map<String, Object>> detail(
            @RequestParam(required = false) String inboundId,
            @RequestParam(required = false) String id) {
        String key = inboundId != null && !inboundId.isBlank() ? inboundId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 inboundId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM inv_other_inbound WHERE inbound_id = ? OR inbound_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "其他入库单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_other_inbound_detail WHERE inbound_id = ? ORDER BY detail_id",
                head.get("inboundId"));
        head.put("details", details.stream().map(OtherInboundController::camelize).toList());
        return ApiResponse.ok(head);
    }

    // ========================================================================
    //  商品查询（用于手工添加商品，带标准售价、成本单价和可用库存）
    // ========================================================================

    @GetMapping("/other-inbound/goods-options")
    public ApiResponse<List<Map<String, Object>>> goodsOptions(
            @RequestParam String warehouse,
            @RequestParam(required = false) String keyword) {
        String kw = (keyword == null || keyword.isBlank())
                ? null : "%" + keyword.trim().toLowerCase() + "%";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT g.goods_code, g.goods_name, g.spec, g.base_unit,
                       COALESCE(g.standard_price, 0) AS standard_price,
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
        return ApiResponse.ok(rows.stream().map(OtherInboundController::camelize).toList());
    }

    // ========================================================================
    //  批次下拉（指定仓库+商品的现有批次，供入库沿用已有批次）
    // ========================================================================

    @GetMapping("/other-inbound/batch-options")
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
                WHERE bs.goods_code = ? AND bs.warehouse = ?
                ORDER BY bs.production_date, bs.batch_no
                """, goodsCode, warehouse);
        return ApiResponse.ok(rows.stream().map(OtherInboundController::camelize).toList());
    }

    // ========================================================================
    //  新建其他入库单
    // ========================================================================

    @PostMapping("/other-inbound/create")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        String warehouse = str(request.get("warehouse"));
        if (warehouse.isBlank()) throw new IllegalArgumentException("请选择仓库");

        String customer = str(request.get("customer")).trim();
        String supplier = str(request.get("supplier")).trim();
        validateCounterparty(customer, supplier);

        String inboundType = str(request.get("inboundType")).trim();
        if (inboundType.isBlank()) throw new IllegalArgumentException("请选择其他入库类型");

        LocalDate billDate = parseDate(request.get("billDate"), LocalDate.now());
        // 无草稿态：保存即「未审核」，等待审核
        String status = "PENDING";
        String remark = str(request.get("remark"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        if (reqDetails.isEmpty()) throw new IllegalArgumentException("入库明细不能为空");

        for (Map<String, Object> line : reqDetails) {
            if (toBd(line.get("qty")).signum() <= 0) {
                throw new IllegalArgumentException("商品 " + str(line.get("goodsName")) + " 的入库数量必须大于 0");
            }
        }

        Totals totals = computeTotals(reqDetails);

        String inboundId = "OIB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String inboundNo = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.OTHER_INBOUND, "inv_other_inbound", "inbound_no");

        jdbcTemplate.update("""
                INSERT INTO inv_other_inbound(inbound_id, inbound_no, bill_date, customer, supplier,
                    inbound_type, warehouse, qty, amount, cost_amount, status, creator_name, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '系统管理员', ?)
                """, inboundId, inboundNo, billDate,
                emptyToNull(customer), emptyToNull(supplier), inboundType, warehouse,
                totals.qty, totals.amount, totals.costAmount, status, remark);

        for (Map<String, Object> line : reqDetails) {
            insertDetail(inboundId, line);
        }

        log("inventory.otherInbound", "CREATE", inboundNo, "创建其他入库单");
        return ApiResponse.ok(Map.of("inboundId", inboundId, "inboundNo", inboundNo, "status", status));
    }

    // ========================================================================
    //  编辑其他入库单
    // ========================================================================

    @PostMapping("/other-inbound/update")
    @Transactional
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> request) {
        String inboundId = str(request.get("inboundId"));
        if (inboundId.isBlank()) throw new IllegalArgumentException("缺少 inboundId");

        Map<String, Object> existing = findInboundById(inboundId);
        String status = str(pick(existing, "status"));
        if (!"DRAFT".equals(status) && !"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅未审核的其他入库单可修改，当前状态：" + statusText(status));
        }
        // findInboundById 支持按单号查，统一取回主键，避免后续按单号误当主键使用
        inboundId = str(pick(existing, "inbound_id"));

        String warehouse = strOrDefault(request.get("warehouse"), str(pick(existing, "warehouse")));
        LocalDate billDate = parseDate(request.get("billDate"), parseDate(pick(existing, "bill_date"), LocalDate.now()));
        String remark = strOrDefault(request.get("remark"), str(pick(existing, "remark")));
        String inboundType = strOrDefault(request.get("inboundType"), str(pick(existing, "inbound_type")));
        if (inboundType.isBlank()) throw new IllegalArgumentException("请选择其他入库类型");

        // 客户/供应商：请求未传时沿用原值，二选一规则同样生效
        String customer = request.containsKey("customer")
                ? str(request.get("customer")).trim() : str(pick(existing, "customer"));
        String supplier = request.containsKey("supplier")
                ? str(request.get("supplier")).trim() : str(pick(existing, "supplier"));
        validateCounterparty(customer, supplier);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        // 未传明细时沿用库里原有明细（与报损单一致的宽松语义）
        if (reqDetails.isEmpty()) {
            List<Map<String, Object>> oldDetails = jdbcTemplate.queryForList(
                    "SELECT * FROM inv_other_inbound_detail WHERE inbound_id = ?", inboundId);
            for (Map<String, Object> od : oldDetails) {
                reqDetails.add(camelize(od));
            }
        }
        if (reqDetails.isEmpty()) throw new IllegalArgumentException("入库明细不能为空");

        for (Map<String, Object> line : reqDetails) {
            if (toBd(line.get("qty")).signum() <= 0) {
                throw new IllegalArgumentException("商品 " + str(line.get("goodsName")) + " 的入库数量必须大于 0");
            }
        }

        Totals totals = computeTotals(reqDetails);

        jdbcTemplate.update("DELETE FROM inv_other_inbound_detail WHERE inbound_id = ?", inboundId);

        // status 一律归一到 PENDING：草稿态已取消，历史 DRAFT 单据一经编辑即迁移为「未审核」。
        // 能走到这里的状态只有 DRAFT / PENDING（上面已拦掉其它状态），所以直接写死是安全的。
        jdbcTemplate.update("""
                UPDATE inv_other_inbound SET bill_date=?, customer=?, supplier=?, inbound_type=?,
                    warehouse=?, qty=?, amount=?, cost_amount=?, remark=?, status='PENDING'
                WHERE inbound_id=?
                """, billDate, emptyToNull(customer), emptyToNull(supplier), inboundType,
                warehouse, totals.qty, totals.amount, totals.costAmount, remark, inboundId);

        for (Map<String, Object> line : reqDetails) {
            insertDetail(inboundId, line);
        }

        log("inventory.otherInbound", "UPDATE", str(pick(existing, "inbound_no")), "修改其他入库单");
        return ApiResponse.ok(Map.of("inboundId", inboundId, "status", "PENDING"));
    }

    // ========================================================================
    //  审核其他入库单
    // ========================================================================

    /**
     * 审核：PENDING → APPROVED。
     * <p><b>成本口径</b>：只取审核时点的<b>当前</b>库存成本单价用于计价，<b>不重算库存成本</b> ——
     * 只增加数量，{@code inv_stock_balance.cost_price} 不参与移动加权平均、保持原值。
     * 该成本单价同时用于写库存流水、回写明细成本单价/成本金额与单据成本金额。
     * <p>该商品在此仓库尚无成本（首次入库）时没有「当前成本」可取，依次回落：
     * 建单时记录的成本单价 → 入库单价，用于建立初始成本（建账，非重算）。
     */
    @PostMapping("/other-inbound/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> audit(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> head = findInboundById(request.bizId());
        String status = str(pick(head, "status"));
        if (!"PENDING".equals(status) && !"DRAFT".equals(status)) {
            throw new IllegalArgumentException("仅未审核的其他入库单可审核，当前状态：" + statusText(status));
        }
        String inboundId = str(pick(head, "inbound_id"));
        String inboundNo = str(pick(head, "inbound_no"));
        String warehouse = str(pick(head, "warehouse"));

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_other_inbound_detail WHERE inbound_id = ?", inboundId);
        if (details.isEmpty()) throw new IllegalArgumentException("入库明细为空，无法审核");

        BigDecimal totalCostAmount = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            String goodsCode = str(pick(d, "goods_code"));
            String goodsName = str(pick(d, "goods_name"));
            String batchNo = str(pick(d, "batch_no"));
            BigDecimal qty = toBd(pick(d, "qty"));
            LocalDate productionDate = parseDate(pick(d, "production_date"), null);

            // 无现存成本时的回落单价：建单时成本 → 入库单价
            BigDecimal fallback = toBd(pick(d, "cost_price"));
            if (fallback.signum() <= 0) fallback = toBd(pick(d, "price"));

            // 只按当前成本入库：增加数量、不重算成本；返回实际计价用的成本单价
            BigDecimal costPrice = inventoryCostService.inboundAtCurrentCost(
                    goodsCode, goodsName, warehouse, batchNo, qty, fallback, inboundNo, productionDate);

            BigDecimal costAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
            totalCostAmount = totalCostAmount.add(costAmount);

            // 回写审核时成本到明细
            jdbcTemplate.update("""
                    UPDATE inv_other_inbound_detail SET cost_price=?, cost_amount=?
                    WHERE detail_id=?
                    """, costPrice, costAmount, str(pick(d, "detail_id")));
        }

        jdbcTemplate.update("""
                UPDATE inv_other_inbound SET status='APPROVED', cost_amount=?,
                    audit_user='系统管理员', audit_time=CURRENT_TIMESTAMP
                WHERE inbound_id=?
                """, totalCostAmount, inboundId);

        log("inventory.otherInbound", "AUDIT", inboundNo, "其他入库单审核 → 增加库存，成本 " + totalCostAmount);
        return ApiResponse.ok(Map.of(
                "inboundId", inboundId, "inboundNo", inboundNo, "status", "APPROVED",
                "costAmount", totalCostAmount,
                "effect", "其他入库审核完成：已增加库存，成本金额 " + totalCostAmount));
    }

    // ========================================================================
    //  反审核其他入库单
    // ========================================================================

    /**
     * 反审核：APPROVED → PENDING，扣减此前入库的库存。
     * 入库后若商品已被出库导致库存不足，则拒绝反审核。
     */
    @PostMapping("/other-inbound/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAudit(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> head = findInboundById(request.bizId());
        String status = str(pick(head, "status"));
        if (!"APPROVED".equals(status)) {
            throw new IllegalArgumentException("仅已审核的其他入库单可取消审核，当前状态：" + statusText(status));
        }
        String inboundId = str(pick(head, "inbound_id"));
        String inboundNo = str(pick(head, "inbound_no"));
        String warehouse = str(pick(head, "warehouse"));

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_other_inbound_detail WHERE inbound_id = ?", inboundId);

        rollbackStock(details, warehouse, inboundNo, "反审核");

        jdbcTemplate.update("""
                UPDATE inv_other_inbound SET status='PENDING',
                    audit_user=NULL, audit_time=NULL
                WHERE inbound_id=?
                """, inboundId);

        log("inventory.otherInbound", "REVERSE_AUDIT", inboundNo, "其他入库单反审核 → 扣减库存");
        return ApiResponse.ok(Map.of("inboundId", inboundId, "status", "PENDING",
                "effect", "已反审核，入库库存已扣回"));
    }

    // ========================================================================
    //  删除其他入库单
    // ========================================================================

    @PostMapping("/other-inbound/delete")
    @Transactional
    public ApiResponse<Map<String, Object>> delete(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> head = findInboundById(request.bizId());
        String status = str(pick(head, "status"));
        if (!"DRAFT".equals(status) && !"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅未审核的其他入库单可删除，当前状态：" + statusText(status));
        }
        String inboundId = str(pick(head, "inbound_id"));
        String inboundNo = str(pick(head, "inbound_no"));
        jdbcTemplate.update("DELETE FROM inv_other_inbound_detail WHERE inbound_id = ?", inboundId);
        jdbcTemplate.update("DELETE FROM inv_other_inbound WHERE inbound_id = ?", inboundId);
        log("inventory.otherInbound", "DELETE", inboundNo, "删除其他入库单");
        return ApiResponse.ok(Map.of("inboundId", inboundId, "effect", "已删除"));
    }

    // ========================================================================
    //  作废其他入库单
    // ========================================================================

    /**
     * 作废：任意非 CANCELLED 状态 → CANCELLED。
     * 已审核的单据先扣回库存再作废。
     */
    @PostMapping("/other-inbound/cancel")
    @Transactional
    public ApiResponse<Map<String, Object>> cancel(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> head = findInboundById(request.bizId());
        String status = str(pick(head, "status"));
        if ("CANCELLED".equals(status)) {
            throw new IllegalArgumentException("其他入库单已作废");
        }
        String inboundId = str(pick(head, "inbound_id"));
        String inboundNo = str(pick(head, "inbound_no"));
        String warehouse = str(pick(head, "warehouse"));

        if ("APPROVED".equals(status)) {
            List<Map<String, Object>> details = jdbcTemplate.queryForList(
                    "SELECT * FROM inv_other_inbound_detail WHERE inbound_id = ?", inboundId);
            rollbackStock(details, warehouse, inboundNo, "作废");
        }

        jdbcTemplate.update("""
                UPDATE inv_other_inbound SET status='CANCELLED',
                    audit_user=NULL, audit_time=NULL
                WHERE inbound_id=?
                """, inboundId);

        log("inventory.otherInbound", "CANCEL", inboundNo,
                "作废其他入库单" + ("APPROVED".equals(status) ? "（已扣回库存）" : ""));
        return ApiResponse.ok(Map.of("inboundId", inboundId, "status", "CANCELLED",
                "effect", "已作废" + ("APPROVED".equals(status) ? "，入库库存已扣回" : "")));
    }

    // ========================================================================
    //  Excel 导入：按仓库 + 单据日期 + 客户/供应商分组生成其他入库单
    // ========================================================================

    /**
     * 导入 JSON 行数组生成其他入库单。
     * <p>每行字段：billDate、customer / supplier（二选一）、warehouse（名称或编码，必填且需系统存在）、
     * goodsCode、batchNo、productionDate、qty。
     * <p>按「仓库 + 单据日期 + 客户/供应商」分组，每组生成一张其他入库单（状态 PENDING）。
     */
    @PostMapping("/other-inbound/import")
    @Transactional
    public ApiResponse<Map<String, Object>> importOtherInbound(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawRows = request.get("rows") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        if (rawRows.isEmpty()) throw new IllegalArgumentException("未解析到有效数据行");

        // 仓库：同时支持按编码和名称匹配，统一落成名称
        Map<String, String> whCodeToName = new HashMap<>();
        Map<String, String> whNameToName = new HashMap<>();
        for (Map<String, Object> wh : jdbcTemplate.queryForList(
                "SELECT warehouse_code, warehouse_name FROM base_warehouse WHERE status = 'NORMAL'")) {
            String code = str(pick(wh, "warehouse_code"));
            String name = str(pick(wh, "warehouse_name"));
            if (!code.isBlank()) whCodeToName.put(code, name);
            if (!name.isBlank()) whNameToName.put(name, name);
        }

        // 客户/供应商：支持按编码和名称匹配，统一落成名称
        Map<String, String> custLookup = buildLookup(
                "SELECT customer_code AS code, customer_name AS name FROM base_customer WHERE status = 'NORMAL'");
        Map<String, String> suppLookup = buildLookup(
                "SELECT supplier_code AS code, supplier_name AS name FROM base_supplier WHERE status = 'NORMAL'");

        Set<String> allGoodsCodes = new HashSet<>();
        for (Map<String, Object> g : jdbcTemplate.queryForList(
                "SELECT goods_code FROM base_goods WHERE status != 'STOPPED'")) {
            allGoodsCodes.add(str(pick(g, "goods_code")));
        }

        List<String> errors = new ArrayList<>();
        // 分组键：仓库 | 单据日期 | C:客户 或 S:供应商
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        Map<String, GroupMeta> groupMeta = new LinkedHashMap<>();

        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> m = rawRows.get(i);
            int rowNo = i + 1;

            String whInput = str(m.get("warehouse")).trim();
            if (whInput.isBlank()) { errors.add("第" + rowNo + "行：仓库不能为空"); continue; }
            String whName = whCodeToName.get(whInput);
            if (whName == null) whName = whNameToName.get(whInput);
            if (whName == null) { errors.add("第" + rowNo + "行：仓库不存在：" + whInput); continue; }

            String custInput = str(m.get("customer")).trim();
            String suppInput = str(m.get("supplier")).trim();
            if (!custInput.isBlank() && !suppInput.isBlank()) {
                errors.add("第" + rowNo + "行：客户与供应商只能填写一个"); continue;
            }
            if (custInput.isBlank() && suppInput.isBlank()) {
                errors.add("第" + rowNo + "行：客户与供应商必须填写一个"); continue;
            }
            String custName = "";
            String suppName = "";
            if (!custInput.isBlank()) {
                custName = custLookup.get(custInput);
                if (custName == null) { errors.add("第" + rowNo + "行：客户不存在：" + custInput); continue; }
            } else {
                suppName = suppLookup.get(suppInput);
                if (suppName == null) { errors.add("第" + rowNo + "行：供应商不存在：" + suppInput); continue; }
            }

            String goodsCode = str(m.get("goodsCode")).trim();
            if (goodsCode.isBlank()) { errors.add("第" + rowNo + "行：商品编号不能为空"); continue; }
            if (!allGoodsCodes.contains(goodsCode)) {
                errors.add("第" + rowNo + "行：商品编号不存在：" + goodsCode); continue;
            }

            if (toBd(m.get("qty")).signum() <= 0) {
                errors.add("第" + rowNo + "行：数量必须大于 0"); continue;
            }

            LocalDate billDate = parseDate(m.get("billDate"), LocalDate.now());
            String key = whName + "|" + billDate + "|" + (custName.isBlank() ? "S:" + suppName : "C:" + custName);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            groupMeta.putIfAbsent(key, new GroupMeta(whName, billDate, custName, suppName));
        }

        if (grouped.isEmpty()) {
            return ApiResponse.ok(Map.of("success", 0, "errors", errors, "effect", "所有行校验失败"));
        }

        List<String> createdNos = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            GroupMeta meta = groupMeta.get(entry.getKey());
            List<Map<String, Object>> detailRows = new ArrayList<>();

            for (Map<String, Object> line : entry.getValue()) {
                String goodsCode = str(line.get("goodsCode")).trim();
                List<Map<String, Object>> gRows = jdbcTemplate.queryForList(
                        "SELECT goods_name, spec, base_unit, standard_price FROM base_goods WHERE goods_code = ?",
                        goodsCode);
                Map<String, Object> g = gRows.isEmpty() ? Map.of() : gRows.get(0);

                BigDecimal qty = toBd(line.get("qty"));
                // 单价取标准售价；成本单价取当前库存成本，无成本时回落到标准售价
                BigDecimal price = toBd(pick(g, "standard_price"));
                BigDecimal costPrice = inventoryCostService.getCurrentCostPrice(goodsCode, meta.warehouse());
                if (costPrice.signum() <= 0) costPrice = price;

                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("goodsCode", goodsCode);
                detail.put("goodsName", gRows.isEmpty() ? goodsCode : str(pick(g, "goods_name")));
                detail.put("spec", str(pick(g, "spec")));
                detail.put("unitName", str(pick(g, "base_unit")));
                detail.put("qty", qty);
                detail.put("price", price);
                detail.put("batchNo", str(line.get("batchNo")).trim());
                detail.put("productionDate", str(line.get("productionDate")).trim());
                detail.put("costPrice", costPrice);
                detailRows.add(detail);
            }

            Totals totals = computeTotals(detailRows);

            String inboundId = "OIB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String inboundNo = billNoGen.nextNo(
                    com.erp.common.util.BillNoGenerator.BillType.OTHER_INBOUND, "inv_other_inbound", "inbound_no");

            jdbcTemplate.update("""
                    INSERT INTO inv_other_inbound(inbound_id, inbound_no, bill_date, customer, supplier,
                        inbound_type, warehouse, qty, amount, cost_amount, status, creator_name, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', '系统管理员', '(导入生成)')
                    """, inboundId, inboundNo, meta.billDate(),
                    emptyToNull(meta.customer()), emptyToNull(meta.supplier()),
                    DEFAULT_IMPORT_INBOUND_TYPE, meta.warehouse(),
                    totals.qty, totals.amount, totals.costAmount);

            for (Map<String, Object> detail : detailRows) {
                insertDetail(inboundId, detail);
            }

            createdNos.add(inboundNo);
            log("inventory.otherInbound", "IMPORT", inboundNo,
                    "导入生成其他入库单，仓库：" + meta.warehouse() + "，日期：" + meta.billDate()
                            + "，往来：" + (meta.customer().isBlank() ? meta.supplier() : meta.customer())
                            + "，行数：" + detailRows.size());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", createdNos.size());
        result.put("inboundNos", createdNos);
        if (!errors.isEmpty()) result.put("errors", errors);
        result.put("effect", "导入完成：生成 " + createdNos.size() + " 张其他入库单"
                + (errors.isEmpty() ? "" : "，" + errors.size() + " 行校验失败"));
        return ApiResponse.ok(result);
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    /** 导入未提供入库类型时的缺省值，对应字典 other_inbound_type 的「其他」。 */
    private static final String DEFAULT_IMPORT_INBOUND_TYPE = "其他";

    /** 客户/供应商二选一校验。 */
    private static void validateCounterparty(String customer, String supplier) {
        boolean hasCustomer = customer != null && !customer.isBlank();
        boolean hasSupplier = supplier != null && !supplier.isBlank();
        if (hasCustomer && hasSupplier) {
            throw new IllegalArgumentException("客户与供应商只能选择一个");
        }
        if (!hasCustomer && !hasSupplier) {
            throw new IllegalArgumentException("请选择客户或供应商");
        }
    }

    /**
     * 已审核单据的库存回滚：逐行扣回入库数量。
     * 先校验可用库存（{@code salesOutbound} 不校验批次可用量），不足时给出可定位的业务提示。
     */
    private void rollbackStock(List<Map<String, Object>> details, String warehouse,
                               String inboundNo, String scene) {
        for (Map<String, Object> d : details) {
            String goodsCode = str(pick(d, "goods_code"));
            String goodsName = str(pick(d, "goods_name"));
            String batchNo = str(pick(d, "batch_no"));
            BigDecimal qty = toBd(pick(d, "qty"));

            if (!batchNo.isBlank()) {
                BigDecimal batchAvailable = toBd(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(qty - COALESCE(locked_qty, 0) - COALESCE(frozen_qty, 0)), 0)
                        FROM inv_batch_stock
                        WHERE goods_code = ? AND warehouse = ? AND batch_no = ?
                        """, BigDecimal.class, goodsCode, warehouse, batchNo));
                if (qty.compareTo(batchAvailable) > 0) {
                    throw new IllegalArgumentException(
                            "商品 " + goodsName + "（批次 " + batchNo + "）需扣回 " + plain(qty)
                                    + "，但该批次可用库存仅 " + plain(batchAvailable)
                                    + "，无法" + scene + "。请先处理该批次的后续出库单据。");
                }
            }

            inventoryCostService.salesOutbound(goodsCode, goodsName, warehouse, batchNo, qty,
                    inboundNo + "(" + scene + ")");
        }
    }

    /** 明细汇总：合计数量 / 金额 / 成本金额，并把行级金额缓存进 line 供落库复用。 */
    private static Totals computeTotals(List<Map<String, Object>> lines) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCostAmount = BigDecimal.ZERO;
        for (Map<String, Object> line : lines) {
            BigDecimal q = toBd(line.get("qty"));
            BigDecimal a = q.multiply(toBd(line.get("price"))).setScale(2, RoundingMode.HALF_UP);
            BigDecimal ca = q.multiply(toBd(line.get("costPrice"))).setScale(2, RoundingMode.HALF_UP);
            line.put("_amount", a);
            line.put("_costAmount", ca);
            totalQty = totalQty.add(q);
            totalAmount = totalAmount.add(a);
            totalCostAmount = totalCostAmount.add(ca);
        }
        return new Totals(totalQty, totalAmount, totalCostAmount);
    }

    /** 把 {@code code -> name} 与 {@code name -> name} 合并成一张查找表，供导入按编码或名称匹配。 */
    private Map<String, String> buildLookup(String sql) {
        Map<String, String> lookup = new HashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(sql)) {
            String code = str(pick(r, "code"));
            String name = str(pick(r, "name"));
            if (!name.isBlank()) lookup.put(name, name);
            if (!code.isBlank()) lookup.put(code, name);
        }
        return lookup;
    }

    private Map<String, Object> findInboundById(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM inv_other_inbound WHERE inbound_id = ? OR inbound_no = ?", id, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("其他入库单不存在：" + id);
        return rows.get(0);
    }

    private void insertDetail(String inboundId, Map<String, Object> line) {
        BigDecimal q = toBd(line.get("qty"));
        BigDecimal p = toBd(line.get("price"));
        BigDecimal a = line.get("_amount") instanceof BigDecimal bd
                ? bd : q.multiply(p).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cp = toBd(line.get("costPrice"));
        BigDecimal ca = line.get("_costAmount") instanceof BigDecimal bd2
                ? bd2 : q.multiply(cp).setScale(2, RoundingMode.HALF_UP);
        String detailId = "OIBD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO inv_other_inbound_detail(detail_id, inbound_id, goods_code, goods_name, spec, unit_name,
                    qty, price, amount, batch_no, production_date, cost_price, cost_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, detailId, inboundId,
                str(line.get("goodsCode")), str(line.get("goodsName")), str(line.get("spec")),
                str(line.get("unitName")),
                q, p, a,
                str(line.get("batchNo")), parseDate(line.get("productionDate"), null),
                cp, ca);
    }

    private static String statusText(String st) {
        return switch (st) {
            case "DRAFT" -> "草稿";          // 历史遗留状态，新建不再产生
            case "PENDING" -> "未审核";
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

    /** 空串转 null，避免「客户/供应商二选一」在库里留下空字符串而非 NULL。 */
    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
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

    /** 明细汇总结果。 */
    private record Totals(BigDecimal qty, BigDecimal amount, BigDecimal costAmount) {}

    /** 导入分组的表头信息：仓库 + 单据日期 + 客户/供应商。 */
    private record GroupMeta(String warehouse, LocalDate billDate, String customer, String supplier) {}

    public record AuditRequest(@NotBlank String bizId, String remark) {}
}
