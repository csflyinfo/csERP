package com.erp.transfer;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.util.BillNoGenerator;
import com.erp.inventory.service.InventoryCostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 调拨流程控制器（V45）—— 调拨申请单 → 调拨出库单 → 调拨入库单（含差异退回）。
 *
 * <p>完整流程：
 * <ol>
 *   <li><b>调拨申请单</b>（DBSQ）：登记从转出仓向转入仓调拨的商品明细，审核后生成出库单。</li>
 *   <li><b>调拨出库单</b>（DBCK）：对已审核调拨申请单出仓，支持一商品多批次拆行；
 *       审核时按批次校验可用量并扣减转出仓库存，<b>出仓成本 = 出库审核时的批次成本</b>。</li>
 *   <li><b>调拨入库单</b>（DBRK）：出库单审核后自动生成，只可修改数量（不可超调出数量），
 *       <b>成本 = 调出时的成本</b>；审核时入转入仓。</li>
 *   <li><b>差异退回</b>：入库数量 &lt; 调出数量时，自动生成一条「调拨入库差异退回」入库单
 *       （入调出仓），明细不可修改，只能按单据详情入库（审核时按明细回补调出仓）。</li>
 * </ol>
 */
@RestController
@RequestMapping("/transfer")
public class TransferController {

    private static final String TYPE_DIFF_RETURN = "调拨入库差异退回";

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;
    private final InventoryCostService inventoryCostService;

    public TransferController(JdbcTemplate jdbcTemplate,
                              BillNoGenerator billNoGen,
                              InventoryCostService inventoryCostService) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
        this.inventoryCostService = inventoryCostService;
    }

    // ============================================================
    // 一、调拨申请单
    // ============================================================

    @PostMapping("/apply/page")
    public ApiResponse<PageResult<Map<String, Object>>> applyPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("SELECT * FROM transfer_apply WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String no = trimF(filters, "applyNo");
        if (!no.isEmpty()) { sql.append(" AND apply_no LIKE ?"); args.add("%" + no + "%"); }
        String sourceWh = trimF(filters, "sourceWarehouse");
        if (!sourceWh.isEmpty()) { sql.append(" AND source_warehouse LIKE ?"); args.add("%" + sourceWh + "%"); }
        String targetWh = trimF(filters, "targetWarehouse");
        if (!targetWh.isEmpty()) { sql.append(" AND target_warehouse LIKE ?"); args.add("%" + targetWh + "%"); }
        String status = trimF(filters, "status");
        if (!status.isEmpty()) { sql.append(" AND status = ?"); args.add(status); }
        String transferStatus = trimF(filters, "transferStatus");
        if (!transferStatus.isEmpty()) { sql.append(" AND transfer_status = ?"); args.add(transferStatus); }
        String dateFrom = trimF(filters, "dateFrom"); if (!dateFrom.isEmpty()) { sql.append(" AND apply_date >= ?"); args.add(dateFrom); }
        String dateTo = trimF(filters, "dateTo"); if (!dateTo.isEmpty()) { sql.append(" AND apply_date <= ?"); args.add(dateTo); }
        sql.append(" ORDER BY apply_no DESC");
        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("statusText", statusText(str(r.get("status"))));
            String ts = str(r.getOrDefault("transferStatus", ""));
            r.put("transferStatusText", ts.isEmpty() || "未执行".equals(ts) ? "未执行" : ts);
        }
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @PostMapping("/apply/detail")
    public ApiResponse<Map<String, Object>> applyDetail(@RequestBody Map<String, Object> body) {
        String key = str(body.get("applyId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM transfer_apply WHERE apply_id = ? OR apply_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调拨申请单不存在");
        Map<String, Object> h = heads.get(0);
        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM transfer_apply_detail WHERE apply_id = ? ORDER BY sort_order", h.get("applyId"));
        // 聚合已调出/已调入数量
        String applyNo = str(h.get("applyNo"));
        for (Map<String, Object> d : details) {
            String goodsCode = str(d.get("goodsCode"));
            // 调出数量：所有已审核出库单的该商品数量之和
            BigDecimal outQty = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(tod.qty), 0) FROM transfer_outbound_detail tod "
                + "JOIN transfer_outbound tbo ON tod.outbound_id = tbo.outbound_id "
                + "WHERE tbo.source_apply_no = ? AND tbo.status = 'APPROVED' AND tod.goods_code = ?",
                BigDecimal.class, applyNo, goodsCode);
            d.put("outQty", outQty != null ? outQty : BigDecimal.ZERO);
            // 调入数量：所有已审核入库单的该商品数量之和（含差异退回单，不含退款单的调入仓不同，不做区分）
            BigDecimal inQty = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(tid.qty), 0) FROM transfer_inbound_detail tid "
                + "JOIN transfer_inbound tbi ON tid.inbound_id = tbi.inbound_id "
                + "WHERE tbi.source_apply_no = ? AND tbi.status = 'APPROVED' AND tbi.inbound_type = '正常' AND tid.goods_code = ?",
                BigDecimal.class, applyNo, goodsCode);
            d.put("inQty", inQty != null ? inQty : BigDecimal.ZERO);
        }
        h.put("details", details);
        return ApiResponse.ok(h);
    }

    @PostMapping("/apply/create")
    public ApiResponse<Map<String, Object>> applyCreate(@RequestBody Map<String, Object> body) {
        String sourceWh = str(body.get("sourceWarehouse"));
        String targetWh = str(body.get("targetWarehouse"));
        if (sourceWh.isBlank() || targetWh.isBlank()) return ApiResponse.fail("400", "请选择转出仓和转入仓");
        if (sourceWh.equals(targetWh)) return ApiResponse.fail("400", "转出仓与转入仓不能相同");
        BigDecimal qty = sumDetailField(body, "qty");
        if (qty.signum() <= 0) return ApiResponse.fail("400", "请至少添加一条申请明细");

        String id = "DBSQ" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = billNoGen.nextNo(BillNoGenerator.BillType.TRANSFER_APPLY, "transfer_apply", "apply_no");
        String op = currentUser();
        jdbcTemplate.update("""
                INSERT INTO transfer_apply(apply_id, apply_no, source_warehouse, target_warehouse,
                    apply_date, qty, status, remark, creator_name, create_time)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, CURRENT_TIMESTAMP)
                """, id, no, sourceWh, targetWh,
                date(body, "applyDate"), qty, str(body.get("remark")), op);
        insertApplyDetails(id, body);
        return ApiResponse.ok(Map.of("applyId", id, "applyNo", no));
    }

    @PostMapping("/apply/update")
    public ApiResponse<Boolean> applyUpdate(@RequestBody Map<String, Object> body) {
        String id = str(body.get("applyId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status FROM transfer_apply WHERE apply_id = ?", id);
        if (ex.isEmpty()) return ApiResponse.fail("404", "调拨申请单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400", "仅待审核可编辑");
        String sourceWh = str(body.get("sourceWarehouse"));
        String targetWh = str(body.get("targetWarehouse"));
        if (sourceWh.equals(targetWh)) return ApiResponse.fail("400", "转出仓与转入仓不能相同");
        BigDecimal qty = sumDetailField(body, "qty");
        jdbcTemplate.update("""
                UPDATE transfer_apply SET source_warehouse = ?, target_warehouse = ?,
                    apply_date = ?, qty = ?, remark = ?
                WHERE apply_id = ?
                """, sourceWh, targetWh, date(body, "applyDate"), qty, str(body.get("remark")), id);
        jdbcTemplate.update("DELETE FROM transfer_apply_detail WHERE apply_id = ?", id);
        insertApplyDetails(id, body);
        return ApiResponse.ok(true);
    }

    @PostMapping("/apply/delete")
    public ApiResponse<Boolean> applyDelete(@RequestBody Map<String, Object> body) {
        String id = str(body.get("applyId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status FROM transfer_apply WHERE apply_id = ?", id);
        if (ex.isEmpty()) return ApiResponse.fail("404", "调拨申请单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400", "仅待审核可删除");
        jdbcTemplate.update("DELETE FROM transfer_apply_detail WHERE apply_id = ?", id);
        jdbcTemplate.update("DELETE FROM transfer_apply WHERE apply_id = ?", id);
        return ApiResponse.ok(true);
    }

    @PostMapping("/apply/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> applyAudit(@RequestBody Map<String, Object> body) {
        String id = str(body.get("applyId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM transfer_apply WHERE apply_id = ? OR apply_no = ?", id, id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调拨申请单不存在");
        Map<String, Object> h = heads.get(0);
        if (!"PENDING".equals(str(h.get("status")))) return ApiResponse.fail("400", "仅待审核可审核");
        if (toBd(h.get("qty")).signum() <= 0) return ApiResponse.fail("400", "申请数量为 0，无法审核");
        String applyId = str(h.get("applyId"));
        String applyNo = str(h.get("applyNo"));
        jdbcTemplate.update("""
                UPDATE transfer_apply SET status = 'APPROVED', auditor_name = ?, audit_time = ?
                WHERE apply_id = ?
                """, currentUser(), java.sql.Timestamp.valueOf(LocalDateTime.now()), applyId);

        // 自动生成调拨出库单：按 FIFO（生产日期升序）分配批次，占用批次库存
        String outboundNo = autoGenerateOutbound(h);
        log("transfer.apply", "AUDIT", applyNo,
                outboundNo != null ? "调拨申请审核 → 自动生成调拨出库单 " + outboundNo : "调拨申请审核");
        return ApiResponse.ok(Map.of("applyNo", applyNo, "status", "APPROVED",
                "outboundNo", outboundNo, "effect", "已审核并自动生成调拨出库单 " + (outboundNo != null ? outboundNo : "")));
    }

    /** 按 FIFO（生产日期升序）为申请单的每条商品自动分配批次，生成调拨出库单（PENDING 状态）。 */
    private String autoGenerateOutbound(Map<String, Object> apply) {
        String applyNo = str(apply.get("applyNo"));
        String sourceWh = str(apply.get("sourceWarehouse"));
        String targetWh = str(apply.get("targetWarehouse"));

        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM transfer_apply_detail WHERE apply_id = ? ORDER BY sort_order", str(apply.get("applyId")));
        if (details.isEmpty()) return null;

        String outboundId = "DBCK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String outboundNo = billNoGen.nextNo(BillNoGenerator.BillType.TRANSFER_OUTBOUND, "transfer_outbound", "outbound_no");
        String op = currentUser();
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        jdbcTemplate.update("""
                INSERT INTO transfer_outbound(outbound_id, outbound_no, source_apply_no,
                    source_warehouse, target_warehouse, bill_date, qty, cost_amount, status, remark, creator_name, create_time)
                VALUES (?, ?, ?, ?, ?, CURRENT_DATE, 0, 0, 'PENDING', ?, ?, CURRENT_TIMESTAMP)
                """, outboundId, outboundNo, applyNo, sourceWh, targetWh, "调拨申请 " + applyNo + " 自动生成", op);

        int idx = 1;
        for (Map<String, Object> d : details) {
            String goodsCode = str(d.get("goodsCode"));
            String goodsName = str(d.get("goodsName"));
            String unitName = str(d.get("unitName"));
            BigDecimal remain = toBd(d.get("qty"));
            if (remain.signum() <= 0) continue;

            // FIFO：按生产日期升序取批次
            List<Map<String, Object>> batches = queryCamel(
                    "SELECT batch_no, production_date, qty, cost_price FROM inv_batch_stock "
                    + "WHERE goods_code = ? AND warehouse = ? AND qty > 0 AND batch_no IS NOT NULL "
                    + "ORDER BY production_date ASC, batch_no ASC",
                    goodsCode, sourceWh);

            for (Map<String, Object> b : batches) {
                if (remain.signum() <= 0) break;
                BigDecimal batchQty = toBd(b.get("qty"));
                BigDecimal alloc = remain.min(batchQty);
                BigDecimal costPrice = toBd(b.get("costPrice"));
                BigDecimal costAmount = alloc.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

                jdbcTemplate.update("""
                        INSERT INTO transfer_outbound_detail(detail_id, outbound_id, goods_code, goods_name,
                            unit_name, qty, batch_no, cost_price, cost_amount, sort_order)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, "TOD" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(),
                        outboundId, goodsCode, goodsName, unitName, alloc,
                        str(b.get("batchNo")), costPrice, costAmount, idx++);

                totalQty = totalQty.add(alloc);
                totalCost = totalCost.add(costAmount);
                remain = remain.subtract(alloc);
            }
            // 如有剩余但无批次 → 仍创建行（空批次），让用户手动补
            if (remain.signum() > 0) {
                jdbcTemplate.update("""
                        INSERT INTO transfer_outbound_detail(detail_id, outbound_id, goods_code, goods_name,
                            unit_name, qty, batch_no, cost_price, cost_amount, sort_order)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, 0, 0, ?)
                        """, "TOD" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(),
                        outboundId, goodsCode, goodsName, unitName, remain, idx++);
                totalQty = totalQty.add(remain);
            }
        }
        jdbcTemplate.update("UPDATE transfer_outbound SET qty = ?, cost_amount = ? WHERE outbound_id = ?",
                totalQty, totalCost, outboundId);
        return outboundNo;
    }

    @PostMapping("/apply/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> applyReverseAudit(@RequestBody Map<String, Object> body) {
        String id = str(body.get("applyId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM transfer_apply WHERE apply_id = ? OR apply_no = ?", id, id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调拨申请单不存在");
        Map<String, Object> h = heads.get(0);
        if (!"APPROVED".equals(str(h.get("status")))) return ApiResponse.fail("400", "仅已审核可反审核");
        String applyNo = str(h.get("applyNo"));
        // 有已审核出库单时不允许反审核
        List<Map<String, Object>> approvedOb = queryCamel(
                "SELECT outbound_no FROM transfer_outbound WHERE source_apply_no = ? AND status = 'APPROVED'",
                applyNo);
        if (!approvedOb.isEmpty()) return ApiResponse.fail("400", "已有已审核的调拨出库单，无法反审核");
        // 删除未审核的自动生成出库单（含明细），然后取消审核
        jdbcTemplate.update("DELETE FROM transfer_outbound_detail WHERE outbound_id IN (SELECT outbound_id FROM transfer_outbound WHERE source_apply_no = ? AND status = 'PENDING')", applyNo);
        jdbcTemplate.update("DELETE FROM transfer_outbound WHERE source_apply_no = ? AND status = 'PENDING'", applyNo);
        jdbcTemplate.update("""
                UPDATE transfer_apply SET status = 'PENDING', auditor_name = NULL, audit_time = NULL
                WHERE apply_id = ?
                """, h.get("applyId"));
        log("transfer.apply", "REVERSE_AUDIT", applyNo, "调拨申请反审核（已删除未审核出库单）");
        return ApiResponse.ok(Map.of("applyNo", applyNo, "status", "PENDING"));
    }

    private void insertApplyDetails(String applyId, Map<String, Object> body) {
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return;
        int idx = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            jdbcTemplate.update("""
                    INSERT INTO transfer_apply_detail(detail_id, apply_id, goods_code, goods_name,
                        unit_name, qty, remark, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, "TAD" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(),
                    applyId, str(m.get("goodsCode")), str(m.get("goodsName")), str(m.get("unitName")),
                    toBd(m.get("qty")), str(m.get("remark")), idx++);
        }
    }

    // ============================================================
    // 二、调拨出库单
    // ============================================================

    @PostMapping("/outbound/page")
    public ApiResponse<PageResult<Map<String, Object>>> outboundPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("SELECT to2.*, ta.transfer_status FROM transfer_outbound to2 LEFT JOIN transfer_apply ta ON to2.source_apply_no = ta.apply_no WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String no = trimF(filters, "outboundNo");
        if (!no.isEmpty()) { sql.append(" AND to2.outbound_no LIKE ?"); args.add("%" + no + "%"); }
        String applyNo = trimF(filters, "sourceApplyNo");
        if (!applyNo.isEmpty()) { sql.append(" AND to2.source_apply_no LIKE ?"); args.add("%" + applyNo + "%"); }
        String sourceWh = trimF(filters, "sourceWarehouse");
        if (!sourceWh.isEmpty()) { sql.append(" AND to2.source_warehouse LIKE ?"); args.add("%" + sourceWh + "%"); }
        String targetWh = trimF(filters, "targetWarehouse");
        if (!targetWh.isEmpty()) { sql.append(" AND to2.target_warehouse LIKE ?"); args.add("%" + targetWh + "%"); }
        String status = trimF(filters, "status");
        if (!status.isEmpty()) { sql.append(" AND to2.status = ?"); args.add(status); }
        String transferStatus = trimF(filters, "transferStatus");
        if (!transferStatus.isEmpty()) { sql.append(" AND ta.transfer_status = ?"); args.add(transferStatus); }
        String dateFrom = trimF(filters, "dateFrom"); if (!dateFrom.isEmpty()) { sql.append(" AND to2.bill_date >= ?"); args.add(dateFrom); }
        String dateTo = trimF(filters, "dateTo"); if (!dateTo.isEmpty()) { sql.append(" AND to2.bill_date <= ?"); args.add(dateTo); }
        sql.append(" ORDER BY to2.outbound_no DESC");
        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("statusText", statusText(str(r.get("status"))));
            String ts = str(r.getOrDefault("transferStatus", ""));
            r.put("transferStatusText", switch (ts) {
                case "未执行" -> "待调出"; case "已调出" -> "待调入"; case "已完成" -> "已完成"; default -> ts.isEmpty() ? "待调出" : ts;
            });
        }
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @PostMapping("/outbound/detail")
    public ApiResponse<Map<String, Object>> outboundDetail(@RequestBody Map<String, Object> body) {
        String key = str(body.get("outboundId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM transfer_outbound WHERE outbound_id = ? OR outbound_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调拨出库单不存在");
        Map<String, Object> h = heads.get(0);
        h.put("details", queryCamel(
                "SELECT * FROM transfer_outbound_detail WHERE outbound_id = ? ORDER BY sort_order", h.get("outboundId")));
        return ApiResponse.ok(h);
    }

    /** 从已审核调拨申请单生成出库单数据（预填，不落库） */
    @PostMapping("/outbound/from-apply")
    public ApiResponse<Map<String, Object>> outboundFromApply(@RequestBody Map<String, Object> body) {
        String applyNo = str(body.get("applyNo"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM transfer_apply WHERE apply_no = ? OR apply_id = ?", applyNo, applyNo);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调拨申请单不存在");
        Map<String, Object> h = heads.get(0);
        if (!"APPROVED".equals(str(h.get("status")))) return ApiResponse.fail("400", "仅已审核的调拨申请单可生成出库单");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applyNo", h.get("applyNo"));
        out.put("sourceWarehouse", h.get("sourceWarehouse"));
        out.put("targetWarehouse", h.get("targetWarehouse"));
        out.put("billDate", LocalDate.now().toString());
        out.put("details", queryCamel(
                "SELECT * FROM transfer_apply_detail WHERE apply_id = ? ORDER BY sort_order", h.get("applyId")));
        return ApiResponse.ok(out);
    }

    /** 出库可用批次（转出仓 qty>0 的批次） */
    @GetMapping("/outbound/available-batches")
    public ApiResponse<List<Map<String, Object>>> availableBatches(
            @RequestParam String goodsCode, @RequestParam String warehouse) {
        return ApiResponse.ok(queryCamel("""
                SELECT batch_no, qty, cost_price, production_date, expiry_date
                FROM inv_batch_stock
                WHERE goods_code = ? AND warehouse = ? AND qty > 0
                ORDER BY production_date ASC, batch_no ASC
                """, goodsCode, warehouse));
    }

    @PostMapping("/outbound/create")
    public ApiResponse<Map<String, Object>> outboundCreate(@RequestBody Map<String, Object> body) {
        String sourceWh = str(body.get("sourceWarehouse"));
        String targetWh = str(body.get("targetWarehouse"));
        if (sourceWh.isBlank() || targetWh.isBlank()) return ApiResponse.fail("400", "缺少转出仓/转入仓");
        BigDecimal qty = sumDetailField(body, "qty");
        if (qty.signum() <= 0) return ApiResponse.fail("400", "请至少添加一条出库明细");

        String id = "DBCK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = billNoGen.nextNo(BillNoGenerator.BillType.TRANSFER_OUTBOUND, "transfer_outbound", "outbound_no");
        String op = currentUser();
        jdbcTemplate.update("""
                INSERT INTO transfer_outbound(outbound_id, outbound_no, source_apply_no,
                    source_warehouse, target_warehouse, bill_date, qty, status, remark, creator_name, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, CURRENT_TIMESTAMP)
                """, id, no, str(body.get("sourceApplyNo")),
                sourceWh, targetWh, date(body, "billDate"), qty, str(body.get("remark")), op);
        insertOutboundDetails(id, body);
        return ApiResponse.ok(Map.of("outboundId", id, "outboundNo", no));
    }

    @PostMapping("/outbound/update")
    public ApiResponse<Boolean> outboundUpdate(@RequestBody Map<String, Object> body) {
        String id = str(body.get("outboundId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status FROM transfer_outbound WHERE outbound_id = ?", id);
        if (ex.isEmpty()) return ApiResponse.fail("404", "调拨出库单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400", "仅待审核可编辑");
        BigDecimal qty = sumDetailField(body, "qty");
        jdbcTemplate.update("""
                UPDATE transfer_outbound SET bill_date = ?, qty = ?, remark = ? WHERE outbound_id = ?
                """, date(body, "billDate"), qty, str(body.get("remark")), id);
        jdbcTemplate.update("DELETE FROM transfer_outbound_detail WHERE outbound_id = ?", id);
        insertOutboundDetails(id, body);
        return ApiResponse.ok(true);
    }

    @PostMapping("/outbound/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> outboundAudit(@RequestBody Map<String, Object> body) {
        String key = str(body.get("outboundId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM transfer_outbound WHERE outbound_id = ? OR outbound_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调拨出库单不存在");
        Map<String, Object> h = heads.get(0);
        if (!"PENDING".equals(str(h.get("status")))) return ApiResponse.fail("400", "仅待审核可审核");

        String outboundId = str(h.get("outboundId"));
        String outboundNo = str(h.get("outboundNo"));
        String sourceWh = str(h.get("sourceWarehouse"));

        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM transfer_outbound_detail WHERE outbound_id = ? ORDER BY sort_order", outboundId);
        if (details.isEmpty()) return ApiResponse.fail("400", "无出库明细");

        BigDecimal totalCost = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            String goodsCode = str(d.get("goodsCode"));
            String goodsName = str(d.get("goodsName"));
            String batchNo = str(d.get("batchNo"));
            BigDecimal qty = toBd(d.get("qty"));
            if (qty.signum() <= 0) return ApiResponse.fail("400", "商品 " + goodsName + " 出库数量必须大于 0");

            BigDecimal costPrice;
            if (!batchNo.isBlank()) {
                // 指定批次：校验批次可用量（salesOutbound 不校验，调用方必须自己校验），成本 = 批次成本
                List<Map<String, Object>> batch = queryCamel(
                        "SELECT qty, cost_price FROM inv_batch_stock WHERE goods_code = ? AND warehouse = ? AND batch_no = ?",
                        goodsCode, sourceWh, batchNo);
                if (batch.isEmpty()) return ApiResponse.fail("400", "批次 " + batchNo + " 不存在");
                BigDecimal batchQty = toBd(batch.get(0).get("qty"));
                if (batchQty.compareTo(qty) < 0) {
                    return ApiResponse.fail("400", "批次 " + batchNo + " 可用量不足（现有 " + batchQty.stripTrailingZeros().toPlainString()
                            + "，需 " + qty.stripTrailingZeros().toPlainString() + "）");
                }
                costPrice = toBd(batch.get(0).get("costPrice"));
            } else {
                BigDecimal avail = getAvailableQty(goodsCode, sourceWh);
                if (avail.compareTo(qty) < 0) {
                    return ApiResponse.fail("400", "商品 " + goodsName + " 库存不足（可用 " + avail.stripTrailingZeros().toPlainString()
                            + "，需 " + qty.stripTrailingZeros().toPlainString() + "）");
                }
                costPrice = getCostPrice(goodsCode, sourceWh);
            }

            // 扣减转出仓库存（含批次），成本 = 出库审核时的成本
            inventoryCostService.salesOutbound(goodsCode, goodsName, sourceWh,
                    batchNo.isBlank() ? null : batchNo, qty, outboundNo);

            BigDecimal costAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
            totalCost = totalCost.add(costAmount);
            jdbcTemplate.update("UPDATE transfer_outbound_detail SET cost_price = ?, cost_amount = ? WHERE detail_id = ?",
                    costPrice, costAmount, str(d.get("detailId")));
            // 更新内存中的 map，供后续 generateInbound 使用
            d.put("costPrice", costPrice);
            d.put("costAmount", costAmount);
        }

        // 更新出库单
        jdbcTemplate.update("""
                UPDATE transfer_outbound SET status = 'APPROVED', stock_updated = TRUE,
                    cost_amount = ?, auditor_name = ?, audit_time = ?
                WHERE outbound_id = ?
                """, totalCost, currentUser(), java.sql.Timestamp.valueOf(LocalDateTime.now()), outboundId);

        // 回写调拨申请状态 → 已调出
        String applyNo = str(h.get("sourceApplyNo"));
        if (!applyNo.isBlank()) {
            jdbcTemplate.update("UPDATE transfer_apply SET transfer_status = '已调出' WHERE apply_no = ?", applyNo);
        }

        // 自动生成调拨入库单（成本 = 调出时成本）
        String inboundNo = generateInbound(h, details);

        log("transfer.outbound", "AUDIT", outboundNo, "调拨出库审核 → 自动生成调拨入库单 " + inboundNo);
        return ApiResponse.ok(Map.of(
                "outboundNo", outboundNo, "status", "APPROVED",
                "inboundNo", inboundNo,
                "effect", "已扣减转出仓库存并自动生成调拨入库单 " + inboundNo));
    }

    @PostMapping("/outbound/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> outboundReverseAudit(@RequestBody Map<String, Object> body) {
        String key = str(body.get("outboundId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM transfer_outbound WHERE outbound_id = ? OR outbound_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调拨出库单不存在");
        Map<String, Object> h = heads.get(0);
        if (!"APPROVED".equals(str(h.get("status")))) return ApiResponse.fail("400", "仅已审核可反审核");

        // 关联调拨入库单必须未审核（含差异退回单）
        List<Map<String, Object>> ins = queryCamel(
                "SELECT inbound_no, status FROM transfer_inbound WHERE source_outbound_no = ?",
                str(h.get("outboundNo")));
        for (Map<String, Object> in : ins) {
            if (!"PENDING".equals(str(in.get("status")))) {
                return ApiResponse.fail("400", "关联调拨入库单 " + str(in.get("inboundNo")) + " 已审核，无法反审核");
            }
        }
        // 删除未审核的关联入库单（含明细）
        jdbcTemplate.update("DELETE FROM transfer_inbound_detail WHERE inbound_id IN (SELECT inbound_id FROM transfer_inbound WHERE source_outbound_no = ?)", str(h.get("outboundNo")));
        jdbcTemplate.update("DELETE FROM transfer_inbound WHERE source_outbound_no = ?", str(h.get("outboundNo")));

        // 回补转出仓库存（按原批次、原成本入库）
        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM transfer_outbound_detail WHERE outbound_id = ? ORDER BY sort_order", h.get("outboundId"));
        for (Map<String, Object> d : details) {
            BigDecimal qty = toBd(d.get("qty"));
            if (qty.signum() <= 0) continue;
            inventoryCostService.purchaseInbound(
                    str(d.get("goodsCode")), str(d.get("goodsName")), str(h.get("sourceWarehouse")),
                    str(d.get("batchNo")).isBlank() ? null : str(d.get("batchNo")),
                    qty, toBd(d.get("costPrice")), str(h.get("outboundNo")) + "(反审核)", null);
        }

        jdbcTemplate.update("""
                UPDATE transfer_outbound SET status = 'PENDING', stock_updated = FALSE,
                    inbound_generated = FALSE, cost_amount = 0, auditor_name = NULL, audit_time = NULL
                WHERE outbound_id = ?
                """, h.get("outboundId"));
        jdbcTemplate.update("UPDATE transfer_outbound_detail SET cost_price = 0, cost_amount = 0 WHERE outbound_id = ?", h.get("outboundId"));

        log("transfer.outbound", "REVERSE_AUDIT", str(h.get("outboundNo")), "调拨出库反审核");
        return ApiResponse.ok(Map.of("outboundNo", str(h.get("outboundNo")), "status", "PENDING"));
    }

    private void insertOutboundDetails(String outboundId, Map<String, Object> body) {
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return;
        int idx = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            jdbcTemplate.update("""
                    INSERT INTO transfer_outbound_detail(detail_id, outbound_id, goods_code, goods_name,
                        unit_name, qty, batch_no, cost_price, cost_amount, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?)
                    """, "TOD" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(),
                    outboundId, str(m.get("goodsCode")), str(m.get("goodsName")), str(m.get("unitName")),
                    toBd(m.get("qty")), str(m.get("batchNo")), idx++);
        }
    }

    // ============================================================
    // 三、调拨入库单（含差异退回）
    // ============================================================

    @PostMapping("/inbound/page")
    public ApiResponse<PageResult<Map<String, Object>>> inboundPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("SELECT ti.*, ta.transfer_status FROM transfer_inbound ti LEFT JOIN transfer_apply ta ON ti.source_apply_no = ta.apply_no WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String no = trimF(filters, "inboundNo");
        if (!no.isEmpty()) { sql.append(" AND ti.inbound_no LIKE ?"); args.add("%" + no + "%"); }
        String outNo = trimF(filters, "sourceOutboundNo");
        if (!outNo.isEmpty()) { sql.append(" AND ti.source_outbound_no LIKE ?"); args.add("%" + outNo + "%"); }
        String type = trimF(filters, "inboundType");
        if (!type.isEmpty()) { sql.append(" AND ti.inbound_type = ?"); args.add(type); }
        String status = trimF(filters, "status");
        if (!status.isEmpty()) { sql.append(" AND ti.status = ?"); args.add(status); }
        String transferStatus = trimF(filters, "transferStatus");
        if (!transferStatus.isEmpty()) { sql.append(" AND ta.transfer_status = ?"); args.add(transferStatus); }
        String dateFrom = trimF(filters, "dateFrom"); if (!dateFrom.isEmpty()) { sql.append(" AND ti.bill_date >= ?"); args.add(dateFrom); }
        String dateTo = trimF(filters, "dateTo"); if (!dateTo.isEmpty()) { sql.append(" AND ti.bill_date <= ?"); args.add(dateTo); }
        sql.append(" ORDER BY ti.inbound_no DESC");
        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("statusText", statusText(str(r.get("status"))));
            String ts = str(r.getOrDefault("transferStatus", ""));
            r.put("transferStatusText", switch (ts) {
                case "未执行" -> "待调出"; case "已调出" -> "待调入"; case "已完成" -> "已完成"; default -> ts.isEmpty() ? "待调出" : ts;
            });
        }
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @PostMapping("/inbound/detail")
    public ApiResponse<Map<String, Object>> inboundDetail(@RequestBody Map<String, Object> body) {
        String key = str(body.get("inboundId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM transfer_inbound WHERE inbound_id = ? OR inbound_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调拨入库单不存在");
        Map<String, Object> h = heads.get(0);
        h.put("details", queryCamel(
                "SELECT * FROM transfer_inbound_detail WHERE inbound_id = ? ORDER BY sort_order", h.get("inboundId")));
        return ApiResponse.ok(h);
    }

    /** 调拨入库单只可修改数量（仅 PENDING 正常单；差异退回单明细不可修改） */
    @PostMapping("/inbound/update")
    public ApiResponse<Boolean> inboundUpdate(@RequestBody Map<String, Object> body) {
        String id = str(body.get("inboundId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status, inbound_type FROM transfer_inbound WHERE inbound_id = ? OR inbound_no = ?", id, id);
        if (ex.isEmpty()) return ApiResponse.fail("404", "调拨入库单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400", "仅待审核可修改数量");
        if (TYPE_DIFF_RETURN.equals(str(ex.get(0).get("inboundType")))) {
            return ApiResponse.fail("400", "差异退回单明细不可修改，只能按单据详情入库");
        }
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return ApiResponse.fail("400", "缺少入库明细");
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String detailId = str(m.get("detailId"));
            if (detailId.isBlank()) return ApiResponse.fail("400", "缺少明细行 ID");
            BigDecimal qty = toBd(m.get("qty"));
            if (qty.signum() < 0) return ApiResponse.fail("400", "入库数量不能为负");
            List<Map<String, Object>> d = queryCamel("SELECT * FROM transfer_inbound_detail WHERE detail_id = ?", detailId);
            if (d.isEmpty()) return ApiResponse.fail("404", "明细行不存在");
            BigDecimal outQty = toBd(d.get(0).get("outQty"));
            if (qty.compareTo(outQty) > 0) {
                return ApiResponse.fail("400", "商品 " + str(d.get(0).get("goodsName")) + " 入库数量 "
                        + qty.stripTrailingZeros().toPlainString() + " 超过调出数量 " + outQty.stripTrailingZeros().toPlainString());
            }
            BigDecimal costPrice = toBd(d.get(0).get("costPrice"));
            BigDecimal costAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
            jdbcTemplate.update("UPDATE transfer_inbound_detail SET qty = ?, cost_amount = ? WHERE detail_id = ?",
                    qty, costAmount, detailId);
            totalQty = totalQty.add(qty);
            totalCost = totalCost.add(costAmount);
        }
        jdbcTemplate.update("UPDATE transfer_inbound SET qty = ?, cost_amount = ? WHERE inbound_id = ?",
                totalQty, totalCost, id);
        return ApiResponse.ok(true);
    }

    @PostMapping("/inbound/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> inboundAudit(@RequestBody Map<String, Object> body) {
        String key = str(body.get("inboundId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM transfer_inbound WHERE inbound_id = ? OR inbound_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调拨入库单不存在");
        Map<String, Object> h = heads.get(0);
        if (!"PENDING".equals(str(h.get("status")))) return ApiResponse.fail("400", "仅待审核可审核");

        String inboundId = str(h.get("inboundId"));
        String inboundNo = str(h.get("inboundNo"));
        String inboundType = str(h.get("inboundType"));
        String targetWh = str(h.get("targetWarehouse"));

        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM transfer_inbound_detail WHERE inbound_id = ? ORDER BY sort_order", inboundId);
        if (details.isEmpty()) return ApiResponse.fail("400", "无入库明细");

        BigDecimal totalCost = BigDecimal.ZERO;
        List<Map<String, Object>> diffRows = new ArrayList<>(); // 差异退回行
        for (Map<String, Object> d : details) {
            BigDecimal outQty = toBd(d.get("outQty"));
            BigDecimal qty = toBd(d.get("qty"));
            if (qty.signum() < 0) return ApiResponse.fail("400", "商品 " + str(d.get("goodsName")) + " 入库数量不能为负");
            if (qty.compareTo(outQty) > 0) {
                return ApiResponse.fail("400", "商品 " + str(d.get("goodsName")) + " 入库数量 " + qty.stripTrailingZeros().toPlainString()
                        + " 超过调出数量 " + outQty.stripTrailingZeros().toPlainString());
            }
            BigDecimal costPrice = toBd(d.get("costPrice"));
            if (qty.signum() > 0) {
                // 入转入仓（成本 = 调出时成本；purchaseInbound 内部移动加权，同成本入仓不改变平均成本）
                inventoryCostService.purchaseInbound(
                        str(d.get("goodsCode")), str(d.get("goodsName")), targetWh,
                        str(d.get("batchNo")).isBlank() ? null : str(d.get("batchNo")),
                        qty, costPrice, inboundNo, null);
                BigDecimal costAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
                totalCost = totalCost.add(costAmount);
                jdbcTemplate.update("UPDATE transfer_inbound_detail SET cost_amount = ? WHERE detail_id = ?",
                        costAmount, str(d.get("detailId")));
            }
            // 差异：未按调出数量入库 → 差额退回调出仓
            BigDecimal diff = outQty.subtract(qty);
            if (diff.signum() > 0) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("goodsCode", d.get("goodsCode"));
                row.put("goodsName", d.get("goodsName"));
                row.put("unitName", d.get("unitName"));
                row.put("qty", diff);
                row.put("batchNo", d.get("batchNo"));
                row.put("costPrice", costPrice);
                diffRows.add(row);
            }
        }

        jdbcTemplate.update("""
                UPDATE transfer_inbound SET status = 'APPROVED', stock_updated = TRUE,
                    cost_amount = ?, auditor_name = ?, audit_time = ?
                WHERE inbound_id = ?
                """, totalCost, currentUser(), java.sql.Timestamp.valueOf(LocalDateTime.now()), inboundId);

        // 正常入库单且存在差异 → 自动生成差异退回单（入调出仓，明细不可修改）
        String diffNo = null;
        if (!TYPE_DIFF_RETURN.equals(inboundType) && !diffRows.isEmpty()) {
            diffNo = generateDiffReturn(h, diffRows);
        }

        // 回写调拨申请状态 → 已完成
        String applyNo = str(h.get("sourceApplyNo"));
        if (!applyNo.isBlank()) {
            jdbcTemplate.update("UPDATE transfer_apply SET transfer_status = '已完成' WHERE apply_no = ?", applyNo);
        }

        log("transfer.inbound", "AUDIT", inboundNo, (diffNo != null ? "调拨入库审核 → 生成差异退回单 " + diffNo : "调拨入库审核"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inboundNo", inboundNo);
        result.put("status", "APPROVED");
        result.put("diffInboundNo", diffNo);
        result.put("effect", diffNo != null ? "已入转入仓并生成差异退回单 " + diffNo : "已按调出数量入转入仓");
        return ApiResponse.ok(result);
    }

    @PostMapping("/inbound/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> inboundReverseAudit(@RequestBody Map<String, Object> body) {
        String key = str(body.get("inboundId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM transfer_inbound WHERE inbound_id = ? OR inbound_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "调拨入库单不存在");
        Map<String, Object> h = heads.get(0);
        if (!"APPROVED".equals(str(h.get("status")))) return ApiResponse.fail("400", "仅已审核可反审核");

        String inboundNo = str(h.get("inboundNo"));
        String targetWh = str(h.get("targetWarehouse"));

        // 正常单：若其差异退回单已审核则不允许反审核（先反审核差异退回单）
        if (!TYPE_DIFF_RETURN.equals(str(h.get("inboundType")))) {
            List<Map<String, Object>> diff = queryCamel(
                    "SELECT inbound_no, status FROM transfer_inbound WHERE inbound_type = ? AND source_outbound_no = ?",
                    TYPE_DIFF_RETURN, str(h.get("sourceOutboundNo")));
            for (Map<String, Object> d : diff) {
                if (!"PENDING".equals(str(d.get("status")))) {
                    return ApiResponse.fail("400", "差异退回单 " + str(d.get("inboundNo")) + " 已审核，请先反审核");
                }
            }
            // 删除未审核的差异退回单
            jdbcTemplate.update("DELETE FROM transfer_inbound_detail WHERE inbound_id IN (SELECT inbound_id FROM transfer_inbound WHERE inbound_type = ? AND source_outbound_no = ?)", TYPE_DIFF_RETURN, str(h.get("sourceOutboundNo")));
            jdbcTemplate.update("DELETE FROM transfer_inbound WHERE inbound_type = ? AND source_outbound_no = ?", TYPE_DIFF_RETURN, str(h.get("sourceOutboundNo")));
        }

        // 扣回转入仓（按入库明细原批次、原成本扣减）
        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM transfer_inbound_detail WHERE inbound_id = ? ORDER BY sort_order", h.get("inboundId"));
        for (Map<String, Object> d : details) {
            BigDecimal qty = toBd(d.get("qty"));
            if (qty.signum() <= 0) continue;
            inventoryCostService.salesOutbound(
                    str(d.get("goodsCode")), str(d.get("goodsName")), targetWh,
                    str(d.get("batchNo")).isBlank() ? null : str(d.get("batchNo")), qty,
                    inboundNo + "(反审核)");
        }

        jdbcTemplate.update("""
                UPDATE transfer_inbound SET status = 'PENDING', stock_updated = FALSE,
                    cost_amount = 0, auditor_name = NULL, audit_time = NULL
                WHERE inbound_id = ?
                """, h.get("inboundId"));
        jdbcTemplate.update("UPDATE transfer_inbound_detail SET cost_amount = 0 WHERE inbound_id = ?", h.get("inboundId"));

        log("transfer.inbound", "REVERSE_AUDIT", inboundNo, "调拨入库反审核");
        return ApiResponse.ok(Map.of("inboundNo", inboundNo, "status", "PENDING"));
    }

    /** 出库审核后自动生成调拨入库单（成本 = 调出时成本） */
    private String generateInbound(Map<String, Object> outbound, List<Map<String, Object>> details) {
        String inboundId = "DBRK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String inboundNo = billNoGen.nextNo(BillNoGenerator.BillType.TRANSFER_INBOUND, "transfer_inbound", "inbound_no");
        BigDecimal qty = details.stream().map(d -> toBd(d.get("qty"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal costAmount = details.stream().map(d -> toBd(d.get("costAmount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbcTemplate.update("""
                INSERT INTO transfer_inbound(inbound_id, inbound_no, source_outbound_no, source_apply_no,
                    source_warehouse, target_warehouse, inbound_type, bill_date, qty, cost_amount,
                    status, remark, creator_name, create_time)
                VALUES (?, ?, ?, ?, ?, ?, '正常', CURRENT_DATE, ?, ?, 'PENDING', ?, '系统', CURRENT_TIMESTAMP)
                """, inboundId, inboundNo, str(outbound.get("outboundNo")), str(outbound.get("sourceApplyNo")),
                str(outbound.get("sourceWarehouse")), str(outbound.get("targetWarehouse")),
                qty, costAmount, "调拨出库单 " + str(outbound.get("outboundNo")) + " 自动生成");
        int idx = 1;
        for (Map<String, Object> d : details) {
            BigDecimal lineQty = toBd(d.get("qty"));
            BigDecimal lineCost = lineQty.multiply(toBd(d.get("costPrice"))).setScale(2, RoundingMode.HALF_UP);
            jdbcTemplate.update("""
                    INSERT INTO transfer_inbound_detail(detail_id, inbound_id, goods_code, goods_name,
                        unit_name, out_qty, qty, batch_no, cost_price, cost_amount, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "TID" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(),
                    inboundId, str(d.get("goodsCode")), str(d.get("goodsName")), str(d.get("unitName")),
                    lineQty, lineQty, str(d.get("batchNo")), toBd(d.get("costPrice")), lineCost, idx++);
        }
        jdbcTemplate.update("UPDATE transfer_outbound SET inbound_generated = TRUE WHERE outbound_id = ?",
                str(outbound.get("outboundId")));
        return inboundNo;
    }

    /** 差异退回单：把差额商品退回调出仓（明细不可修改，只能按单据详情入库） */
    private String generateDiffReturn(Map<String, Object> inbound, List<Map<String, Object>> diffRows) {
        String inboundId = "DBRK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String inboundNo = billNoGen.nextNo(BillNoGenerator.BillType.TRANSFER_INBOUND, "transfer_inbound", "inbound_no");
        BigDecimal qty = diffRows.stream().map(r -> toBd(r.get("qty"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal costAmount = diffRows.stream()
                .map(r -> toBd(r.get("qty")).multiply(toBd(r.get("costPrice"))).setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 目标仓 = 调出仓：差额商品退回调出仓账面
        jdbcTemplate.update("""
                INSERT INTO transfer_inbound(inbound_id, inbound_no, source_outbound_no, source_apply_no,
                    source_warehouse, target_warehouse, inbound_type, bill_date, qty, cost_amount,
                    status, remark, creator_name, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_DATE, ?, ?, 'PENDING', ?, '系统', CURRENT_TIMESTAMP)
                """, inboundId, inboundNo, str(inbound.get("sourceOutboundNo")), str(inbound.get("sourceApplyNo")),
                str(inbound.get("sourceWarehouse")), str(inbound.get("sourceWarehouse")),
                TYPE_DIFF_RETURN, qty, costAmount, "调拨入库差异退回，商品未按调出数量入库，退回调出仓");
        int idx = 1;
        for (Map<String, Object> r : diffRows) {
            BigDecimal lineQty = toBd(r.get("qty"));
            BigDecimal lineCost = lineQty.multiply(toBd(r.get("costPrice"))).setScale(2, RoundingMode.HALF_UP);
            jdbcTemplate.update("""
                    INSERT INTO transfer_inbound_detail(detail_id, inbound_id, goods_code, goods_name,
                        unit_name, out_qty, qty, batch_no, cost_price, cost_amount, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "TID" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(),
                    inboundId, str(r.get("goodsCode")), str(r.get("goodsName")), str(r.get("unitName")),
                    lineQty, lineQty, str(r.get("batchNo")), toBd(r.get("costPrice")), lineCost, idx++);
        }
        return inboundNo;
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private String trimF(Map<String, Object> filters, String key) {
        Object v = filters.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String statusText(String s) {
        return switch (s == null ? "" : s) {
            case "PENDING" -> "待审核";
            case "APPROVED" -> "已审核";
            case "CANCELLED" -> "已作废";
            default -> s;
        };
    }

    private BigDecimal sumDetailField(Map<String, Object> body, String field) {
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) sum = sum.add(toBd(m.get(field)));
        }
        return sum;
    }

    private BigDecimal getAvailableQty(String goodsCode, String warehouse) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT available_qty FROM inv_stock_balance WHERE goods_code = ? AND warehouse = ?",
                goodsCode, warehouse);
        if (rows.isEmpty()) return BigDecimal.ZERO;
        Object v = rows.get(0).get("available_qty");
        if (v == null) v = rows.get(0).get("AVAILABLE_QTY");
        return toBd(v);
    }

    private BigDecimal getCostPrice(String goodsCode, String warehouse) {
        return inventoryCostService.getCurrentCostPrice(goodsCode, warehouse);
    }

    private String currentUser() {
        try {
            String name = SecurityContextHolder.getContext().getAuthentication().getName();
            if (name != null && !name.isBlank()) return name;
        } catch (Exception ignored) {}
        return "管理员";
    }

    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                currentUser(), moduleCode, action, bizNo, detail);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(String.valueOf(o)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static java.sql.Date date(Map<String, Object> body, String key) {
        String s = str(body.get(key));
        if (s.isBlank()) return java.sql.Date.valueOf(LocalDate.now());
        try { return java.sql.Date.valueOf(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return java.sql.Date.valueOf(LocalDate.now()); }
    }

    /** 查询并转驼峰键（H2 大写键 → camelCase，前端 valueForTitle 才能匹配） */
    private List<Map<String, Object>> queryCamel(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(camelize(r));
        return out;
    }

    private static Map<String, Object> camelize(Map<String, Object> row) {
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
}
