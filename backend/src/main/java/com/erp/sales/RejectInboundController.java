package com.erp.sales;

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

/**
 * 拒收入库单 REST 端点（模块结构参照采购入库，实现风格参照
 * {@link com.erp.inventory.OtherInboundController} 的 JdbcTemplate 直写）。
 *
 * <p>业务场景：客户签收发货单时拒收了部分商品，这批货要退回仓库。
 *
 * <p>生命周期：
 * <ol>
 *   <li>发货单确认签收（{@code POST /sales/receipt/sign}）时若有拒收数量，
 *       调 {@link #generateFromReceipt(String)} <b>自动生成</b>本单（PENDING）。</li>
 *   <li>{@code POST /sales/reject-inbound/update} → 编辑入库数量 / 批次号 / 生产日期 / 仓库 / 备注。</li>
 *   <li>{@code POST /sales/reject-inbound/audit} → 按入库流水走：增加库存、写 {@code inv_stock_ledger}、
 *       重算移动加权平均成本。</li>
 *   <li>{@code POST /sales/reject-inbound/reverse-audit} → APPROVED → PENDING，对称扣回库存。</li>
 * </ol>
 *
 * <p><b>本单有意不提供 create 端点</b> —— 需求明确「不能新建，可编辑」，单据只能由签收拒收自动生成。
 *
 * <p>两个关键业务规则：
 * <ul>
 *   <li><b>批次与生产日期</b>：从原出库单 {@code sales_outbound_detail} 回溯。明细按 {@code goods_code}
 *       一行，若该商品原出库拆了多个批次，取<b>生产日期最新</b>的那一批的批次号与生产日期。</li>
 *   <li><b>成本单价</b>：取<b>原出库单该商品的成本单价</b>，审核时用它写流水并重算成本。
 *       成本是<b>商品 + 仓库</b>维度的移动加权平均，<b>与批次无关</b> —— 同一张单据、同一商品
 *       拆了几个批次，出库成本单价都是同一个值（见 {@code InventoryCostService.salesOutbound}
 *       写流水取的是 {@code inv_stock_balance.cost_price}）。所以「取最新生产日期那批」只决定
 *       批次号与生产日期，<b>不影响成本</b>。
 *       本单区别于其他入库单 / 销售退货入库的地方在于<b>时点</b>：这里用的是<b>出库时点的成本快照</b>，
 *       那两个取的是「审核时点的当前库存成本均价」。出库后若又有新价采购拉动了均价，两者就会不同。</li>
 * </ul>
 */
@RestController
@RequestMapping("/sales/reject-inbound")
public class RejectInboundController {

    private final JdbcTemplate jdbcTemplate;
    private final InventoryCostService inventoryCostService;
    private final com.erp.common.util.BillNoGenerator billNoGen;

    public RejectInboundController(JdbcTemplate jdbcTemplate,
                                   InventoryCostService inventoryCostService,
                                   com.erp.common.util.BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.inventoryCostService = inventoryCostService;
        this.billNoGen = billNoGen;
    }

    // ========================================================================
    //  列表
    // ========================================================================

    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT reject_inbound_id, inbound_no, source_receipt_no, source_outbound_no, source_order_no,
                       customer_code, customer_name, warehouse, driver, route_line, salesman,
                       bill_date, qty, amount, cost_amount, status, stock_updated,
                       creator_name, audit_user, audit_time, create_time, remark
                FROM inv_reject_inbound
                ORDER BY create_time DESC, inbound_no DESC
                """);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            String st = str(pick(r, "status"));
            row.put("statusText", statusText(st));
            // 与采购入库列表一致：单独给一列「入库状态」，由 stock_updated 推导
            row.put("inboundStatus", Boolean.TRUE.equals(pick(r, "stock_updated")) ? "已入库" : "未入库");
            mapped.add(row);
        }
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    // ========================================================================
    //  详情
    // ========================================================================

    @GetMapping("/detail")
    public ApiResponse<Map<String, Object>> detail(
            @RequestParam(required = false) String inboundId,
            @RequestParam(required = false) String id) {
        String key = inboundId != null && !inboundId.isBlank() ? inboundId : id;
        if (key == null || key.isBlank()) return ApiResponse.fail("400", "缺少 inboundId / id");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM inv_reject_inbound WHERE reject_inbound_id = ? OR inbound_no = ?", key, key);
        if (heads.isEmpty()) return ApiResponse.fail("404", "拒收入库单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        // 前端抽屉统一用 inboundId 作为主键别名，避免各模块字段名不一致
        head.put("inboundId", head.get("rejectInboundId"));
        head.put("statusText", statusText(str(head.get("status"))));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_reject_inbound_detail WHERE reject_inbound_id = ? ORDER BY detail_id",
                head.get("rejectInboundId"));
        head.put("details", details.stream().map(RejectInboundController::camelize).toList());
        return ApiResponse.ok(head);
    }

    // ========================================================================
    //  批次下拉（编辑时可改批次，沿用仓库现有批次）
    // ========================================================================

    @GetMapping("/batch-options")
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
        return ApiResponse.ok(rows.stream().map(RejectInboundController::camelize).toList());
    }

    // ========================================================================
    //  编辑
    // ========================================================================

    /**
     * 编辑：仅 PENDING 可改。可改内容为「本次入库数量 / 批次号 / 生产日期 / 仓库 / 备注」。
     * <p>主单的销售订单号 / 发货单号 / 出库单号 / 客户 / 司机是来源快照，<b>不可改</b>。
     * <p>成本单价来自原出库，<b>不可改</b>；请求里带了 costPrice 也会被忽略。
     * <p>入库数量允许填 0（表示该行不入库），但不得超过签收时的拒收数量。
     */
    @PostMapping("/update")
    @Transactional
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> request) {
        String inboundId = strOrDefault(request.get("inboundId"), str(request.get("rejectInboundId")));
        if (inboundId.isBlank()) throw new IllegalArgumentException("缺少 inboundId");

        Map<String, Object> head = findByIdOrNo(inboundId);
        String status = str(pick(head, "status"));
        if (!"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅待审核的拒收入库单可修改，当前状态：" + statusText(status));
        }
        // findByIdOrNo 支持按单号查，统一取回主键
        String headId = str(pick(head, "reject_inbound_id"));
        String inboundNo = str(pick(head, "inbound_no"));

        LocalDate billDate = parseDate(request.get("billDate"), parseDate(pick(head, "bill_date"), LocalDate.now()));
        String headWarehouse = strOrDefault(request.get("warehouse"), str(pick(head, "warehouse")));
        String remark = request.containsKey("remark") ? str(request.get("remark")) : str(pick(head, "remark"));

        // 库里原明细：以 detail_id 为准做局部更新，保证 reject_qty / cost_price / 来源信息不被前端覆盖
        List<Map<String, Object>> oldDetails = jdbcTemplate.queryForList(
                "SELECT * FROM inv_reject_inbound_detail WHERE reject_inbound_id = ?", headId);
        if (oldDetails.isEmpty()) throw new IllegalArgumentException("拒收入库单没有明细，无法修改");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqDetails = request.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
        Map<String, Map<String, Object>> reqByDetailId = new HashMap<>();
        for (Map<String, Object> line : reqDetails) {
            String did = str(line.get("detailId"));
            if (!did.isBlank()) reqByDetailId.put(did, line);
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCostAmount = BigDecimal.ZERO;

        for (Map<String, Object> old : oldDetails) {
            String detailId = str(pick(old, "detail_id"));
            Map<String, Object> line = reqByDetailId.get(detailId);

            BigDecimal rejectQty = toBd(pick(old, "reject_qty"));
            BigDecimal price = toBd(pick(old, "price"));
            BigDecimal costPrice = toBd(pick(old, "cost_price"));   // 原出库成本，不接受前端覆盖
            String goodsName = str(pick(old, "goods_name"));

            BigDecimal qty = line != null && line.containsKey("qty")
                    ? toBd(line.get("qty")) : toBd(pick(old, "qty"));
            if (qty.signum() < 0) {
                throw new IllegalArgumentException("商品 " + goodsName + " 的入库数量不能为负数");
            }
            if (qty.compareTo(rejectQty) > 0) {
                throw new IllegalArgumentException("商品 " + goodsName + " 的入库数量 " + plain(qty)
                        + " 超过签收拒收数量 " + plain(rejectQty));
            }

            String warehouse = line != null
                    ? strOrDefault(line.get("warehouse"), str(pick(old, "warehouse")))
                    : str(pick(old, "warehouse"));
            if (warehouse.isBlank()) warehouse = headWarehouse;
            if (qty.signum() > 0 && warehouse.isBlank()) {
                throw new IllegalArgumentException("商品 " + goodsName + " 入库数量大于 0 时必须选择仓库");
            }

            LocalDate productionDate = line != null && line.containsKey("productionDate")
                    ? parseDate(line.get("productionDate"), null)
                    : parseDate(pick(old, "production_date"), null);
            String batchNo = line != null && line.containsKey("batchNo")
                    ? str(line.get("batchNo")).trim()
                    : str(pick(old, "batch_no"));
            // 批次号生成规则（全局统一）：手填优先 → 否则按生产日期 yyyyMMdd → 否则留空
            if (batchNo.isBlank() && productionDate != null) {
                batchNo = productionDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            }
            String lineRemark = line != null && line.containsKey("remark")
                    ? str(line.get("remark")) : str(pick(old, "remark"));

            BigDecimal amount = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            BigDecimal costAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

            jdbcTemplate.update("""
                    UPDATE inv_reject_inbound_detail
                    SET qty = ?, warehouse = ?, batch_no = ?, production_date = ?,
                        amount = ?, cost_amount = ?, remark = ?
                    WHERE detail_id = ?
                    """, qty, warehouse, emptyToNull(batchNo), productionDate,
                    amount, costAmount, lineRemark, detailId);

            totalQty = totalQty.add(qty);
            totalAmount = totalAmount.add(amount);
            totalCostAmount = totalCostAmount.add(costAmount);
        }

        jdbcTemplate.update("""
                UPDATE inv_reject_inbound
                SET bill_date = ?, warehouse = ?, qty = ?, amount = ?, cost_amount = ?, remark = ?
                WHERE reject_inbound_id = ?
                """, billDate, headWarehouse, totalQty, totalAmount, totalCostAmount, remark, headId);

        log("sales.rejectInbound", "UPDATE", inboundNo, "修改拒收入库单");
        return ApiResponse.ok(Map.of(
                "inboundId", headId, "inboundNo", inboundNo, "status", status,
                "qty", totalQty, "costAmount", totalCostAmount));
    }

    // ========================================================================
    //  审核（按入库流水走，重算成本）
    // ========================================================================

    /**
     * 审核：PENDING → APPROVED，把拒收商品退回库存。
     *
     * <p><b>成本口径</b>：用明细上快照的<b>原出库成本单价</b>入库，而非审核时点的当前库存成本均价。
     * 拒收的货本来就是这批出库出去的，按原成本回来才不会因为期间成本波动而虚增/虚减库存金额。
     *
     * <p>{@code InventoryCostService.purchaseInbound} 一次完成三件事：
     * 累加 {@code inv_batch_stock} 批次库存 → 按移动加权平均重算 {@code inv_stock_balance} 成本
     * → 写一条 {@code direction='IN'} 的 {@code inv_stock_ledger} 流水。
     *
     * <p>入库数量为 0 的行直接跳过（表示该行不入库）。
     */
    @PostMapping("/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> audit(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> head = findByIdOrNo(request.bizId());
        String status = str(pick(head, "status"));
        if (!"PENDING".equals(status)) {
            throw new IllegalArgumentException("仅待审核的拒收入库单可审核，当前状态：" + statusText(status));
        }
        String headId = str(pick(head, "reject_inbound_id"));
        String inboundNo = str(pick(head, "inbound_no"));
        String headWarehouse = str(pick(head, "warehouse"));

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_reject_inbound_detail WHERE reject_inbound_id = ?", headId);
        if (details.isEmpty()) throw new IllegalArgumentException("入库明细为空，无法审核");

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCostAmount = BigDecimal.ZERO;
        int postedLines = 0;

        for (Map<String, Object> d : details) {
            BigDecimal qty = toBd(pick(d, "qty"));
            if (qty.signum() <= 0) continue;   // 该行不入库

            String goodsCode = str(pick(d, "goods_code"));
            String goodsName = str(pick(d, "goods_name"));
            String batchNo = str(pick(d, "batch_no"));
            String warehouse = strOrDefault(pick(d, "warehouse"), headWarehouse);
            LocalDate productionDate = parseDate(pick(d, "production_date"), null);
            BigDecimal costPrice = toBd(pick(d, "cost_price"));

            if (warehouse.isBlank()) {
                throw new IllegalArgumentException("商品 " + goodsName + " 未指定仓库，无法审核");
            }

            BigDecimal costAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

            // 按原出库成本单价增加库存 + 写入库流水 + 移动加权平均重算成本
            inventoryCostService.purchaseInbound(goodsCode, goodsName, warehouse, batchNo,
                    qty, costPrice, inboundNo, productionDate);

            jdbcTemplate.update(
                    "UPDATE inv_reject_inbound_detail SET cost_amount = ? WHERE detail_id = ?",
                    costAmount, str(pick(d, "detail_id")));

            totalQty = totalQty.add(qty);
            totalCostAmount = totalCostAmount.add(costAmount);
            postedLines++;
        }

        if (postedLines == 0) {
            throw new IllegalArgumentException("所有明细的入库数量都是 0，无法审核");
        }

        jdbcTemplate.update("""
                UPDATE inv_reject_inbound
                SET status = 'APPROVED', stock_updated = TRUE, qty = ?, cost_amount = ?,
                    audit_user = '系统管理员', audit_time = CURRENT_TIMESTAMP
                WHERE reject_inbound_id = ?
                """, totalQty, totalCostAmount, headId);

        log("sales.rejectInbound", "AUDIT", inboundNo,
                "拒收入库审核 → 按原出库成本回库，数量 " + plain(totalQty) + "，成本 " + totalCostAmount);
        return ApiResponse.ok(Map.of(
                "inboundId", headId,
                "inboundNo", inboundNo,
                "status", "APPROVED",
                "qty", totalQty,
                "costAmount", totalCostAmount,
                "effect", "拒收入库审核完成：已按原出库成本单价回库，成本金额 " + totalCostAmount));
    }

    // ========================================================================
    //  反审核
    // ========================================================================

    /**
     * 反审核：APPROVED → PENDING，扣回此前入库的库存。
     * <p>入库后若该批次已被后续单据出库导致库存不足，则拒绝反审核。
     */
    @PostMapping("/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAudit(@Valid @RequestBody AuditRequest request) {
        Map<String, Object> head = findByIdOrNo(request.bizId());
        String status = str(pick(head, "status"));
        if (!"APPROVED".equals(status)) {
            throw new IllegalArgumentException("仅已审核的拒收入库单可反审核，当前状态：" + statusText(status));
        }
        String headId = str(pick(head, "reject_inbound_id"));
        String inboundNo = str(pick(head, "inbound_no"));
        String headWarehouse = str(pick(head, "warehouse"));

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_reject_inbound_detail WHERE reject_inbound_id = ?", headId);

        for (Map<String, Object> d : details) {
            BigDecimal qty = toBd(pick(d, "qty"));
            if (qty.signum() <= 0) continue;

            String goodsCode = str(pick(d, "goods_code"));
            String goodsName = str(pick(d, "goods_name"));
            String batchNo = str(pick(d, "batch_no"));
            String warehouse = strOrDefault(pick(d, "warehouse"), headWarehouse);

            // salesOutbound 只校验商品+仓库的聚合可用量，不校验批次可用量，这里必须自己前置校验
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
                                    + "，无法反审核。请先处理该批次的后续出库单据。");
                }
            }

            inventoryCostService.salesOutbound(goodsCode, goodsName, warehouse, batchNo, qty,
                    inboundNo + "(反审核)");
        }

        jdbcTemplate.update("""
                UPDATE inv_reject_inbound
                SET status = 'PENDING', stock_updated = FALSE, audit_user = NULL, audit_time = NULL
                WHERE reject_inbound_id = ?
                """, headId);

        log("sales.rejectInbound", "REVERSE_AUDIT", inboundNo, "拒收入库反审核 → 扣回库存");
        return ApiResponse.ok(Map.of("inboundId", headId, "inboundNo", inboundNo, "status", "PENDING",
                "effect", "已反审核，入库库存已扣回"));
    }

    // ========================================================================
    //  供 SalesReceiptController.sign 调用：由签收拒收自动生成
    // ========================================================================

    /**
     * 由 {@link SalesReceiptController#sign} 在确认签收且存在拒收数量时调用。
     *
     * <p>幂等：同一张发货单已生成过（{@code uk_reject_inbound_receipt} 唯一约束）→ 直接返回既有单号。
     *
     * @param receiptId 发货单主键或单号
     * @return 拒收入库单号；发货单没有拒收行时返回 {@code null}
     */
    @Transactional
    public String generateFromReceipt(String receiptId) {
        List<Map<String, Object>> receiptRows = jdbcTemplate.queryForList("""
                SELECT receipt_id, receipt_no, source_outbound_no, source_order_no,
                       customer_code, customer_name, warehouse, driver
                FROM sales_receipt WHERE receipt_id = ? OR receipt_no = ?
                """, receiptId, receiptId);
        if (receiptRows.isEmpty()) throw new IllegalArgumentException("销售发货单不存在：" + receiptId);
        Map<String, Object> receipt = receiptRows.get(0);
        String realReceiptId = str(pick(receipt, "receipt_id"));
        String receiptNo = str(pick(receipt, "receipt_no"));
        String outboundNo = str(pick(receipt, "source_outbound_no"));

        // 幂等：唯一索引 uk_reject_inbound_receipt 已经保证了唯一性，这里提前返回避免抛约束异常
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT inbound_no FROM inv_reject_inbound WHERE source_receipt_no = ?",
                String.class, receiptNo);
        if (!existing.isEmpty()) return existing.get(0);

        // 拒收行
        List<Map<String, Object>> rejectLines = jdbcTemplate.queryForList("""
                SELECT detail_id, goods_code, goods_name, unit_name, qty, reject_qty, price, reject_reason
                FROM sales_receipt_detail
                WHERE receipt_id = ? AND COALESCE(reject_qty, 0) > 0
                ORDER BY detail_id
                """, realReceiptId);
        if (rejectLines.isEmpty()) return null;

        // 出库单：拿仓库 / 业务员 / 线路，以及每个商品的批次、生产日期、原出库成本单价
        Map<String, Object> outbound = findOutboundByNo(outboundNo);
        String warehouse = strOrDefault(pick(outbound, "warehouse"), str(pick(receipt, "warehouse")));
        String salesman = str(pick(outbound, "salesman"));
        String routeLine = str(pick(outbound, "route_line"));
        // 司机优先取发货单快照，发货单没有则回落到出库单
        String driver = strOrDefault(pick(receipt, "driver"), str(pick(outbound, "driver")));

        Map<String, OutboundBatch> batchByGoods = resolveOutboundBatches(outboundNo);

        String headId = "RJI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String inboundNo = billNoGen.nextNo(
                com.erp.common.util.BillNoGenerator.BillType.REJECT_INBOUND, "inv_reject_inbound", "inbound_no");
        LocalDate billDate = LocalDate.now();

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCostAmount = BigDecimal.ZERO;
        List<Object[]> detailBatch = new ArrayList<>();

        for (Map<String, Object> line : rejectLines) {
            String goodsCode = str(pick(line, "goods_code"));
            String goodsName = str(pick(line, "goods_name"));
            BigDecimal rejectQty = toBd(pick(line, "reject_qty"));
            BigDecimal price = toBd(pick(line, "price"));

            OutboundBatch ob = batchByGoods.get(goodsCode);
            String batchNo = ob != null ? ob.batchNo() : "";
            LocalDate productionDate = ob != null ? ob.productionDate() : null;
            String lineWarehouse = ob != null && !ob.warehouse().isBlank() ? ob.warehouse() : warehouse;
            String spec = ob != null ? ob.spec() : "";

            BigDecimal costPrice = resolveCostPrice(ob, outboundNo, goodsCode, lineWarehouse);

            // 入库数量默认 = 拒收数量，审核前可编辑
            BigDecimal qty = rejectQty;
            BigDecimal amount = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            BigDecimal costAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

            detailBatch.add(new Object[]{
                    "RJID" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                    headId,
                    str(pick(line, "detail_id")),
                    goodsCode, goodsName, emptyToNull(spec), str(pick(line, "unit_name")),
                    lineWarehouse,
                    rejectQty, qty,
                    emptyToNull(batchNo), productionDate,
                    price, amount, costPrice, costAmount,
                    str(pick(line, "reject_reason"))
            });

            totalQty = totalQty.add(qty);
            totalAmount = totalAmount.add(amount);
            totalCostAmount = totalCostAmount.add(costAmount);
        }

        jdbcTemplate.update("""
                INSERT INTO inv_reject_inbound (reject_inbound_id, inbound_no, source_receipt_no,
                    source_outbound_no, source_order_no, customer_code, customer_name, warehouse,
                    driver, route_line, salesman, bill_date, qty, amount, cost_amount,
                    status, stock_updated, creator_name, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', FALSE, '系统', ?)
                """,
                headId, inboundNo, receiptNo, emptyToNull(outboundNo),
                emptyToNull(str(pick(receipt, "source_order_no"))),
                emptyToNull(str(pick(receipt, "customer_code"))),
                emptyToNull(str(pick(receipt, "customer_name"))),
                warehouse, emptyToNull(driver), emptyToNull(routeLine), emptyToNull(salesman),
                billDate, totalQty, totalAmount, totalCostAmount,
                "发货单 " + receiptNo + " 签收拒收自动生成");

        jdbcTemplate.batchUpdate("""
                INSERT INTO inv_reject_inbound_detail (detail_id, reject_inbound_id, source_detail_id,
                    goods_code, goods_name, spec, unit_name, warehouse,
                    reject_qty, qty, batch_no, production_date,
                    price, amount, cost_price, cost_amount, reject_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, detailBatch);

        jdbcTemplate.update("UPDATE sales_receipt SET reject_generated = TRUE WHERE receipt_id = ?", realReceiptId);

        log("sales.rejectInbound", "GENERATE", inboundNo,
                "发货单 " + receiptNo + " 签收拒收自动生成拒收入库单，拒收 " + rejectLines.size() + " 行");
        return inboundNo;
    }

    // ========================================================================
    //  原出库批次 / 成本回溯
    // ========================================================================

    /**
     * 从原出库单回溯每个商品的批次号、生产日期与出库成本单价。
     *
     * <p><b>批次 / 生产日期</b>：一个商品在出库单里可能拆了多个批次，本单明细按商品一行，
     * 取<b>生产日期最新</b>的那一批（生产日期为空的排最后）。
     *
     * <p><b>成本单价</b>：<b>按商品取，与批次无关</b>。系统计价口径是「商品 + 仓库」维度的
     * 移动加权平均（见 {@code InventoryCostService.salesOutbound}：写流水用的是
     * {@code inv_stock_balance.cost_price}，不是 {@code inv_batch_stock.cost_price}），
     * 所以<b>同一张单据、同一商品的各批次出库成本单价必然相同</b>，取 MAX 只是为了拿一个确定值。
     * 别把成本理解成「该批次的成本」——批次上虽然也存了 cost_price，但出入库计价不走它。
     *
     * @return {@code goods_code -> 该商品的批次信息 + 出库成本}
     */
    private Map<String, OutboundBatch> resolveOutboundBatches(String outboundNo) {
        Map<String, OutboundBatch> result = new LinkedHashMap<>();
        if (outboundNo == null || outboundNo.isBlank()) return result;

        // 成本按商品聚合：同商品各批次成本一致，MAX 只为取确定值（0 成本行不参与，避免拉低）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT d.goods_code, d.spec, d.warehouse, d.batch_no, d.production_date,
                       (SELECT MAX(c.cost_price) FROM sales_outbound_detail c
                        WHERE c.outbound_id = d.outbound_id AND c.goods_code = d.goods_code) AS cost_price
                FROM sales_outbound_detail d
                JOIN sales_outbound o ON o.outbound_id = d.outbound_id
                WHERE o.outbound_no = ?
                ORDER BY d.goods_code, d.production_date DESC NULLS LAST, d.detail_id DESC
                """, outboundNo);

        // 已按 (goods_code, production_date DESC) 排序，每个商品的首行即生产日期最新的那批
        for (Map<String, Object> r : rows) {
            String goodsCode = str(pick(r, "goods_code"));
            result.computeIfAbsent(goodsCode, k -> new OutboundBatch(
                    str(pick(r, "batch_no")),
                    parseDate(pick(r, "production_date"), null),
                    toBd(pick(r, "cost_price")),
                    str(pick(r, "warehouse")),
                    str(pick(r, "spec"))));
        }
        return result;
    }

    /**
     * 解析拒收入库的成本单价，三级回落：
     * <ol>
     *   <li>原出库单里<b>该商品</b>的 {@code cost_price} —— 需求要求的口径（与批次无关，见
     *       {@link #resolveOutboundBatches}）</li>
     *   <li>原出库在 {@code inv_stock_ledger} 留下的 {@code direction='OUT'} 流水成本
     *       —— 出库明细成本为 0 时的兜底，这条流水本身就是按当时的加权平均成本写的</li>
     *   <li>当前库存成本均价 —— 最后兜底，避免按 0 成本入库把移动加权平均拉坏</li>
     * </ol>
     * <p>注意第 3 级是<b>兜底而非常态</b>：常态下必须用出库时点的成本快照，
     * 否则出库后若又有新价采购拉动了均价，拒收回来的货就会按错误成本入账。
     */
    private BigDecimal resolveCostPrice(OutboundBatch ob, String outboundNo,
                                        String goodsCode, String warehouse) {
        if (ob != null && ob.costPrice().signum() > 0) return ob.costPrice();

        List<BigDecimal> ledgerCosts = jdbcTemplate.queryForList("""
                SELECT cost_price FROM inv_stock_ledger
                WHERE source_bill = ? AND direction = 'OUT' AND goods_code = ?
                ORDER BY occurred_at DESC
                LIMIT 1
                """, BigDecimal.class, outboundNo, goodsCode);
        if (!ledgerCosts.isEmpty() && ledgerCosts.get(0) != null
                && ledgerCosts.get(0).signum() > 0) {
            return ledgerCosts.get(0);
        }

        return inventoryCostService.getCurrentCostPrice(goodsCode, warehouse);
    }

    /**
     * 删除拒收入库单，供 {@link SalesReceiptController#unsign} 撤销签收时调用。
     * <p>仅 PENDING 可删（已审核的必须先反审核，否则库存会对不上）。
     */
    @Transactional
    public void deleteByReceiptNo(String receiptNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT reject_inbound_id, inbound_no, status FROM inv_reject_inbound WHERE source_receipt_no = ?",
                receiptNo);
        for (Map<String, Object> r : rows) {
            String status = str(pick(r, "status"));
            String inboundNo = str(pick(r, "inbound_no"));
            if (!"PENDING".equals(status)) {
                throw new IllegalArgumentException("拒收入库单 " + inboundNo + " 已" + statusText(status)
                        + "，无法撤销签收。请先反审核该拒收入库单。");
            }
            String headId = str(pick(r, "reject_inbound_id"));
            jdbcTemplate.update("DELETE FROM inv_reject_inbound_detail WHERE reject_inbound_id = ?", headId);
            jdbcTemplate.update("DELETE FROM inv_reject_inbound WHERE reject_inbound_id = ?", headId);
            log("sales.rejectInbound", "DELETE", inboundNo, "撤销签收 → 删除拒收入库单");
        }
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    private Map<String, Object> findByIdOrNo(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM inv_reject_inbound WHERE reject_inbound_id = ? OR inbound_no = ?", id, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("拒收入库单不存在：" + id);
        return rows.get(0);
    }

    private Map<String, Object> findOutboundByNo(String outboundNo) {
        if (outboundNo == null || outboundNo.isBlank()) return Map.of();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT outbound_id, outbound_no, warehouse, salesman, route_line, driver
                FROM sales_outbound WHERE outbound_no = ?
                """, outboundNo);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private static String statusText(String st) {
        return switch (st) {
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

    /** BigDecimal 去掉多余的尾随 0，用于错误提示 */
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

    /** 从原出库单回溯到的批次信息（批次号 / 生产日期 / 出库成本单价 / 仓库 / 规格，同源于一行出库明细）。 */
    private record OutboundBatch(String batchNo, LocalDate productionDate, BigDecimal costPrice,
                                 String warehouse, String spec) {}

    public record AuditRequest(@NotBlank String bizId, String remark) {}
}
