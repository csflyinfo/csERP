package com.erp.inventory;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/inventory")
public class InventoryController {
    private final JdbcTemplate jdbcTemplate;

    public InventoryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/balance/page")
    public ApiResponse<PageResult<Map<String, Object>>> balancePage(@RequestBody PageRequest request) {
        // 按 goods_code + warehouse 聚合到 goods 维度（跨批次合计）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT b.goods_code, MIN(b.goods_name) AS goods_name, b.warehouse,
                       SUM(b.physical_qty) AS physical_qty, SUM(b.locked_qty) AS locked_qty,
                       SUM(b.frozen_qty) AS frozen_qty, SUM(b.available_qty) AS available_qty,
                       SUM(b.purchase_on_way) AS purchase_on_way,
                       MAX(b.cost_price) AS cost_price, SUM(b.stock_amount) AS stock_amount,
                       MAX(b.last_inout_time) AS last_inout_time,
                       MIN(g.spec) AS spec, MIN(g.barcode) AS barcode,
                       MIN(g.category_name) AS category_name, MIN(g.brand_name) AS brand_name,
                       MIN(g.base_unit) AS base_unit,
                       MIN(g.default_supplier) AS default_supplier,
                       MIN(g.storage_property) AS storage_property,
                       MIN(g.goods_manager) AS goods_manager,
                       MIN(g.unit_config) AS unit_config
                FROM inv_stock_balance b
                LEFT JOIN base_goods g ON b.goods_code = g.goods_code
                GROUP BY b.goods_code, b.warehouse
                ORDER BY b.goods_code, b.warehouse
                """);
        List<Map<String, Object>> mapped = rows.stream()
                .map(InventoryController::camelize)
                .filter(r -> matchesStockFilters(r, request.filters()))
                .toList();
        return ApiResponse.ok(pageWithSummary(mapped, request));
    }

    @PostMapping("/ledger/page")
    public ApiResponse<PageResult<Map<String, Object>>> ledgerPage(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT ledger_no, occurred_at, source_bill, goods_code, goods_name, warehouse,
                       batch_no, direction, qty, cost_price, amount, balance_qty, operator_name
                FROM inv_stock_ledger
                ORDER BY occurred_at DESC, ledger_no DESC
                """);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            // 「方向」列友好展示
            String dir = String.valueOf(row.getOrDefault("direction", ""));
            row.put("direction", "IN".equals(dir) ? "入库" : "OUT".equals(dir) ? "出库" : dir);
            // operator_name → operator（前端映射）
            row.put("operator", row.get("operatorName"));
            mapped.add(row);
        }
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @PostMapping("/lock/page")
    public ApiResponse<PageResult<Map<String, Object>>> lockPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT balance_id lockNo,
                       goods_code goodsCode,
                       goods_name goodsName,
                       warehouse,
                       locked_qty lockedQty,
                       '锁定中' status
                FROM inv_stock_balance
                WHERE locked_qty > 0
                ORDER BY goods_code, warehouse
                """), request));
    }

    @PostMapping("/batch/page")
    public ApiResponse<PageResult<Map<String, Object>>> batchPage(@RequestBody PageRequest request) {
        // 走 inv_batch_stock 拿真实批次层数据；JOIN base_goods 补商品扩展字段
        // 锁定/冻结/可用数量：批次表没有单独维护，用商品仓库维度的比例分摊估算（简化：直接用 batch.qty 作为 physical，其它按 balance 分摊）
        // V1.0 简化：批次层的 locked/frozen/available 直接从 inv_stock_balance 取对应 warehouse+goods 的值（不区分批次；后续增强）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bs.batch_stock_id, bs.goods_code, bs.goods_name, bs.warehouse, bs.batch_no,
                       bs.production_date, bs.expiry_date,
                       bs.qty AS physical_qty,
                       COALESCE(bs.locked_qty, 0) AS locked_qty,
                       COALESCE(bs.frozen_qty, 0) AS frozen_qty,
                       (bs.qty - COALESCE(bs.locked_qty, 0) - COALESCE(bs.frozen_qty, 0)) AS available_qty,
                       bs.cost_price, bs.stock_amount, bs.last_inout_time,
                       g.spec, g.barcode, g.category_name, g.brand_name, g.base_unit,
                       g.default_supplier, g.storage_property, g.goods_manager,
                       g.unit_config,
                       '正常' AS status
                FROM inv_batch_stock bs
                LEFT JOIN base_goods g ON bs.goods_code = g.goods_code
                WHERE bs.batch_no IS NOT NULL
                ORDER BY bs.goods_code, bs.warehouse, bs.production_date, bs.batch_no
                """);
        List<Map<String, Object>> mapped = rows.stream()
                .map(InventoryController::camelize)
                .filter(r -> matchesStockFilters(r, request.filters()))
                .filter(r -> matchesBatchFilters(r, request.filters()))
                .toList();
        return ApiResponse.ok(pageWithSummary(mapped, request));
    }

    @PostMapping("/warning/page")
    public ApiResponse<PageResult<Map<String, Object>>> warningPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT b.goods_code goodsCode,
                       b.goods_name goodsName,
                       b.warehouse,
                       b.available_qty currentQty,
                       g.stock_lower_limit lowerLimit,
                       g.stock_upper_limit upperLimit,
                       CASE WHEN b.available_qty < g.stock_lower_limit THEN '低于下限'
                            WHEN b.available_qty > g.stock_upper_limit THEN '高于上限'
                            ELSE '正常' END warningType,
                       '待处理' status
                FROM inv_stock_balance b
                JOIN base_goods g ON b.goods_code = g.goods_code
                WHERE b.available_qty < g.stock_lower_limit OR b.available_qty > g.stock_upper_limit
                ORDER BY b.goods_code, b.warehouse
                """), request));
    }

    @PostMapping("/transfer/page")
    public ApiResponse<PageResult<Map<String, Object>>> transferPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no transferNo, object_name objectName, warehouse, reason, qty, amount,
                       goods_code goodsCode, goods_name goodsName,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='TRANSFER' ORDER BY bill_no DESC
                """), request));
    }

    @PostMapping("/transfer/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditTransfer(@RequestBody Map<String, Object> request) {
        List<Map<String, Object>> bills = jdbcTemplate.queryForList(
            "SELECT * FROM biz_simple_bill WHERE bill_type='TRANSFER' AND (bill_id=? OR bill_no=? OR bill_no=(SELECT bill_no FROM biz_simple_bill WHERE bill_type='TRANSFER' ORDER BY bill_no DESC LIMIT 1))",
            request.get("bizId"), request.get("bizId"));
        if (bills.isEmpty()) {
            throw new IllegalArgumentException("调拨单不存在");
        }
        Map<String, Object> bill = bills.get(0);
        String goodsCode = String.valueOf(bill.getOrDefault("GOODS_CODE", "SP001"));
        String goodsName = String.valueOf(bill.getOrDefault("GOODS_NAME", ""));
        BigDecimal qty = toBigDecimal(bill.get("QTY"));
        String objectName = String.valueOf(bill.getOrDefault("OBJECT_NAME", ""));

        // 解析源仓库和目标仓库：格式 "总仓 → 东区仓"
        String sourceWarehouse = String.valueOf(bill.getOrDefault("WAREHOUSE", "总仓"));
        String targetWarehouse = sourceWarehouse;
        if (objectName.contains("→")) {
            String[] parts = objectName.split("→");
            if (parts.length >= 2) {
                sourceWarehouse = parts[0].trim();
                targetWarehouse = parts[1].trim();
            }
        }

        // 获取当前成本单价
        BigDecimal costPrice = getCostPrice(goodsCode, sourceWarehouse);
        BigDecimal amount = qty.multiply(costPrice);

        // 扣减源仓库库存
        deductStock(goodsCode, sourceWarehouse, qty);
        // 增加目标仓库库存
        addStock(goodsCode, goodsName, targetWarehouse, qty, costPrice);

        // 更新单据状态
        jdbcTemplate.update(
            "UPDATE biz_simple_bill SET status='APPROVED' WHERE bill_id=?",
            bill.get("BILL_ID"));

        // 生成调出流水
        BigDecimal sourceBalance = getBalanceQty(goodsCode, sourceWarehouse);
        insertLedger(goodsCode, goodsName, sourceWarehouse, "OUT", qty, costPrice, amount, sourceBalance,
            String.valueOf(bill.get("BILL_NO")));
        // 生成调入流水
        BigDecimal targetBalance = getBalanceQty(goodsCode, targetWarehouse);
        insertLedger(goodsCode, goodsName, targetWarehouse, "IN", qty, costPrice, amount, targetBalance,
            String.valueOf(bill.get("BILL_NO")));

        return ApiResponse.ok(Map.of(
            "status", "APPROVED",
            "effect", "调拨已审核：" + sourceWarehouse + " 调出 " + qty + "，" + targetWarehouse + " 调入 " + qty,
            "goodsCode", goodsCode,
            "qty", qty,
            "sourceWarehouse", sourceWarehouse,
            "targetWarehouse", targetWarehouse
        ));
    }

    @PostMapping("/cost-adjust/page")
    public ApiResponse<PageResult<Map<String, Object>>> costAdjustPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no adjustNo, warehouse, object_name goodsCode, amount diffAmount, reason,
                       goods_name goodsName,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='COST_ADJUST' ORDER BY bill_no DESC
                """), request));
    }

    @PostMapping("/cost-adjust/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditCostAdjust(@RequestBody Map<String, Object> request) {
        List<Map<String, Object>> bills = jdbcTemplate.queryForList(
            "SELECT * FROM biz_simple_bill WHERE bill_type='COST_ADJUST' AND (bill_id=? OR bill_no=? OR bill_no=(SELECT bill_no FROM biz_simple_bill WHERE bill_type='COST_ADJUST' ORDER BY bill_no DESC LIMIT 1))",
            request.get("bizId"), request.get("bizId"));
        if (bills.isEmpty()) {
            throw new IllegalArgumentException("成本调整单不存在");
        }
        Map<String, Object> bill = bills.get(0);
        String goodsCode = String.valueOf(bill.getOrDefault("OBJECT_NAME", "SP001"));
        String warehouse = String.valueOf(bill.getOrDefault("WAREHOUSE", "总仓"));
        BigDecimal diffAmount = toBigDecimal(bill.get("AMOUNT"));

        // 查询当前库存
        List<Map<String, Object>> balances = jdbcTemplate.queryForList(
            "SELECT physical_qty, cost_price, stock_amount FROM inv_stock_balance WHERE goods_code=? AND warehouse=?",
            goodsCode, warehouse);
        if (balances.isEmpty()) {
            throw new IllegalArgumentException("库存记录不存在：" + goodsCode + " / " + warehouse);
        }
        Map<String, Object> balance = balances.get(0);
        BigDecimal physicalQty = toBigDecimal(balance.get("PHYSICAL_QTY"));
        BigDecimal oldCostPrice = toBigDecimal(balance.get("COST_PRICE"));

        if (physicalQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("库存数量为0，无法调整成本");
        }

        // 新成本 = 原成本 + (差异金额 / 库存数量)
        BigDecimal costDelta = diffAmount.divide(physicalQty, 4, RoundingMode.HALF_UP);
        BigDecimal newCostPrice = oldCostPrice.add(costDelta);
        BigDecimal newStockAmount = physicalQty.multiply(newCostPrice).setScale(2, RoundingMode.HALF_UP);

        jdbcTemplate.update(
            "UPDATE inv_stock_balance SET cost_price=?, stock_amount=?, last_inout_time=CURRENT_TIMESTAMP WHERE goods_code=? AND warehouse=?",
            newCostPrice, newStockAmount, goodsCode, warehouse);

        // 更新单据状态
        jdbcTemplate.update(
            "UPDATE biz_simple_bill SET status='APPROVED' WHERE bill_id=?",
            bill.get("BILL_ID"));

        return ApiResponse.ok(Map.of(
            "status", "APPROVED",
            "effect", "成本调整已审核：" + goodsCode + " 成本从 " + oldCostPrice + " 调整为 " + newCostPrice,
            "goodsCode", goodsCode,
            "oldCostPrice", oldCostPrice,
            "newCostPrice", newCostPrice,
            "diffAmount", diffAmount
        ));
    }

    // ========== 库存操作辅助方法 ==========

    private BigDecimal getCostPrice(String goodsCode, String warehouse) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT cost_price FROM inv_stock_balance WHERE goods_code=? AND warehouse=?",
            goodsCode, warehouse);
        if (rows.isEmpty()) return BigDecimal.ZERO;
        return toBigDecimal(rows.get(0).get("COST_PRICE"));
    }

    private BigDecimal getBalanceQty(String goodsCode, String warehouse) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT physical_qty FROM inv_stock_balance WHERE goods_code=? AND warehouse=?",
            goodsCode, warehouse);
        if (rows.isEmpty()) return BigDecimal.ZERO;
        return toBigDecimal(rows.get(0).get("PHYSICAL_QTY"));
    }

    private void deductStock(String goodsCode, String warehouse, BigDecimal qty) {
        int updated = jdbcTemplate.update(
            """
            UPDATE inv_stock_balance
            SET physical_qty = physical_qty - ?,
                available_qty = available_qty - ?,
                stock_amount = (physical_qty - ?) * cost_price,
                last_inout_time = CURRENT_TIMESTAMP
            WHERE goods_code = ? AND warehouse = ? AND available_qty >= ?
            """,
            qty, qty, qty, goodsCode, warehouse, qty);
        if (updated == 0) {
            throw new IllegalArgumentException("库存不足或记录不存在：" + goodsCode + " / " + warehouse);
        }
    }

    private void addStock(String goodsCode, String goodsName, String warehouse, BigDecimal qty, BigDecimal costPrice) {
        int updated = jdbcTemplate.update(
            """
            UPDATE inv_stock_balance
            SET physical_qty = physical_qty + ?,
                available_qty = available_qty + ?,
                stock_amount = (physical_qty + ?) * cost_price,
                last_inout_time = CURRENT_TIMESTAMP
            WHERE goods_code = ? AND warehouse = ?
            """,
            qty, qty, qty, goodsCode, warehouse);

        if (updated == 0) {
            // 目标仓库无此商品，创建新库存记录
            String balanceId = "SB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            BigDecimal stockAmount = qty.multiply(costPrice);
            jdbcTemplate.update(
                """
                INSERT INTO inv_stock_balance(balance_id, goods_code, goods_name, warehouse, batch_no,
                    physical_qty, locked_qty, frozen_qty, available_qty, purchase_on_way,
                    cost_price, stock_amount, last_inout_time)
                VALUES (?, ?, ?, ?, '', ?, 0, 0, ?, 0, ?, ?, CURRENT_TIMESTAMP)
                """,
                balanceId, goodsCode, goodsName, warehouse, qty, qty, costPrice, stockAmount);
        }
    }

    private void insertLedger(String goodsCode, String goodsName, String warehouse, String direction,
                               BigDecimal qty, BigDecimal costPrice, BigDecimal amount, BigDecimal balanceQty,
                               String sourceBill) {
        String ledgerId = "SL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String ledgerNo = "INV" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        jdbcTemplate.update(
            """
            INSERT INTO inv_stock_ledger(ledger_id, ledger_no, occurred_at, source_bill,
                goods_code, goods_name, warehouse, batch_no, direction, qty, cost_price, amount, balance_qty, operator_name)
            VALUES (?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, '', ?, ?, ?, ?, ?, '管理员')
            """,
            ledgerId, ledgerNo, sourceBill, goodsCode, goodsName, warehouse, direction, qty, costPrice, amount, balanceQty);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    // ============ 库存查询多筛选 + 页脚合计 支持 ============

    /**
     * 库存查询通用筛选：
     *  - keyword：商品编号 / 名称 / 条码 模糊
     *  - warehouses[]：仓库多选（数组或逗号分隔）
     *  - categories[] / brands[] / suppliers[] / managers[] / storageProperties[]：多选
     *  - showZero=false 时过滤 physical_qty <= 0
     */
    @SuppressWarnings("unchecked")
    private static boolean matchesStockFilters(Map<String, Object> row, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return true;

        String keyword = strLower(filters.get("keyword"));
        if (!keyword.isBlank()) {
            String hay = strLower(row.get("goodsCode")) + " " + strLower(row.get("goodsName")) + " " + strLower(row.get("barcode"));
            if (!hay.contains(keyword)) return false;
        }

        if (!matchesMulti(row.get("warehouse"), filters.get("warehouses"))) return false;
        if (!matchesMulti(row.get("categoryName"), filters.get("categories"))) return false;
        if (!matchesMulti(row.get("brandName"), filters.get("brands"))) return false;
        if (!matchesMulti(row.get("defaultSupplier"), filters.get("suppliers"))) return false;
        if (!matchesMulti(row.get("goodsManager"), filters.get("managers"))) return false;
        if (!matchesMulti(row.get("storageProperty"), filters.get("storageProperties"))) return false;

        Object showZero = filters.get("showZero");
        boolean show = showZero != null && "true".equalsIgnoreCase(String.valueOf(showZero));
        if (!show) {
            Object q = row.get("physicalQty");
            double v = q instanceof Number n ? n.doubleValue()
                    : (q == null ? 0.0 : Double.parseDouble(String.valueOf(q)));
            if (v <= 0) return false;
        }
        return true;
    }

    private static boolean matchesBatchFilters(Map<String, Object> row, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return true;
        String batchNo = strLower(filters.get("batchNo"));
        if (!batchNo.isBlank() && !strLower(row.get("batchNo")).contains(batchNo)) return false;
        String pdFrom = strLower(filters.get("productionDateFrom"));
        String pdTo = strLower(filters.get("productionDateTo"));
        if (!pdFrom.isBlank() || !pdTo.isBlank()) {
            String pd = strLower(row.get("productionDate")).substring(0, Math.min(10, strLower(row.get("productionDate")).length()));
            if (!pdFrom.isBlank() && pd.compareTo(pdFrom) < 0) return false;
            if (!pdTo.isBlank() && pd.compareTo(pdTo) > 0) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesMulti(Object rowVal, Object filterVal) {
        if (filterVal == null) return true;
        List<String> options = new ArrayList<>();
        if (filterVal instanceof List<?> list) {
            for (Object o : list) if (o != null && !String.valueOf(o).isBlank()) options.add(String.valueOf(o));
        } else {
            String s = String.valueOf(filterVal);
            if (s.isBlank()) return true;
            for (String x : s.split(",")) if (!x.isBlank()) options.add(x.trim());
        }
        if (options.isEmpty()) return true;
        String rv = rowVal == null ? "" : String.valueOf(rowVal);
        return options.contains(rv);
    }

    private static String strLower(Object v) {
        return v == null ? "" : String.valueOf(v).trim().toLowerCase(Locale.ROOT);
    }

    /** 分页并附加 summary（数量/金额合计），用于表格页脚显示。 */
    private static PageResult<Map<String, Object>> pageWithSummary(List<Map<String, Object>> filteredRecords, PageRequest request) {
        BigDecimal physicalQtySum = BigDecimal.ZERO;
        BigDecimal lockedQtySum = BigDecimal.ZERO;
        BigDecimal availableQtySum = BigDecimal.ZERO;
        BigDecimal stockAmountSum = BigDecimal.ZERO;
        BigDecimal availableStockAmountSum = BigDecimal.ZERO;
        for (Map<String, Object> r : filteredRecords) {
            physicalQtySum = physicalQtySum.add(toBd(r.get("physicalQty")));
            lockedQtySum = lockedQtySum.add(toBd(r.get("lockedQty")));
            BigDecimal avail = toBd(r.get("availableQty"));
            availableQtySum = availableQtySum.add(avail);
            stockAmountSum = stockAmountSum.add(toBd(r.get("stockAmount")));
            availableStockAmountSum = availableStockAmountSum.add(avail.multiply(toBd(r.get("costPrice"))));
        }
        int pageNo = request.safePageNo();
        int pageSize = request.safePageSize();
        int fromIndex = Math.min((pageNo - 1) * pageSize, filteredRecords.size());
        int toIndex = Math.min(fromIndex + pageSize, filteredRecords.size());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("physicalQtySum", physicalQtySum);
        summary.put("lockedQtySum", lockedQtySum);
        summary.put("availableQtySum", availableQtySum);
        summary.put("stockAmountSum", stockAmountSum.setScale(2, RoundingMode.HALF_UP));
        summary.put("availableStockAmountSum", availableStockAmountSum.setScale(2, RoundingMode.HALF_UP));
        return new PageResult<>(filteredRecords.subList(fromIndex, toIndex), pageNo, pageSize, filteredRecords.size(), summary);
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(String.valueOf(v)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    // ============ 可用库存查询（销售订单录单/校验用） ============

    /**
     * 批量查询「指定仓库 + 一批商品」的可用库存。
     *
     * <p>供销售订单明细行展示「可用库存」列、以及前端保存前预校验使用。
     * 取的是 {@code inv_stock_balance.available_qty}（= 实物 - 锁定 - 冻结），
     * 与 {@code InventoryCostService.salesOutbound} 的扣减判断口径一致。
     *
     * <p>请求：{@code { "warehouse": "总仓", "goodsCodes": ["SP001", "SP002"], "orderId": "SO..." }}
     * <p>返回：{@code [{ goodsCode, physicalQty, lockedQty, frozenQty, availableQty, ownLockedQty }]}
     * <p>查不到库存记录的商品<b>不在返回列表里</b>，调用方按可用 0 处理。
     *
     * <p>{@code orderId}（订单号或订单 ID，可选）用于<b>编辑已存在的销售订单</b>：
     * 订单从创建那一刻就把数量锁进了 {@code locked_qty}，可用库存里已经扣掉了自己，
     * 编辑时若直接拿 {@code availableQty} 判断，一张刚建好的单会立刻显示「库存不足」。
     * 传了 {@code orderId} 时额外返回 {@code ownLockedQty = min(本单该商品数量, 该商品当前锁定量)}，
     * 前端展示与校验用 {@code availableQty + ownLockedQty}。不传时该值恒为 0，其它调用方行为不变。
     */
    @PostMapping("/available-stock")
    public ApiResponse<List<Map<String, Object>>> availableStock(@RequestBody Map<String, Object> req) {
        String warehouse = req.get("warehouse") == null ? "" : String.valueOf(req.get("warehouse")).trim();
        String orderId = req.get("orderId") == null ? "" : String.valueOf(req.get("orderId")).trim();
        List<String> codes = new ArrayList<>();
        if (req.get("goodsCodes") instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String c = String.valueOf(o).trim();
                if (!c.isEmpty() && !codes.contains(c)) codes.add(c);
            }
        }
        // 仓库或商品为空都查不出有意义的结果，直接返回空列表（不报错，前端按 0 展示「-」）
        if (warehouse.isEmpty() || codes.isEmpty()) return ApiResponse.ok(List.of());

        String inClause = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        Object[] args = new Object[codes.size() + 1];
        args[0] = warehouse;
        for (int i = 0; i < codes.size(); i++) args[i + 1] = codes.get(i);

        // 同一 goods_code + warehouse 理论上只有一行，但历史数据可能因批次拆出多行，统一 SUM 兜底
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT goods_code,
                       COALESCE(SUM(COALESCE(physical_qty, 0)), 0)  AS physical_qty,
                       COALESCE(SUM(COALESCE(locked_qty, 0)), 0)    AS locked_qty,
                       COALESCE(SUM(COALESCE(frozen_qty, 0)), 0)    AS frozen_qty,
                       COALESCE(SUM(COALESCE(available_qty, 0)), 0) AS available_qty
                FROM inv_stock_balance
                WHERE warehouse = ? AND goods_code IN (%s)
                GROUP BY goods_code
                """.formatted(inClause), args);

        // 本单已占用量：同商品多行（正常品 + 赠品）按商品汇总
        Map<String, BigDecimal> ownQty = new LinkedHashMap<>();
        if (!orderId.isBlank()) {
            for (Map<String, Object> r : jdbcTemplate.queryForList("""
                    SELECT d.goods_code AS goods_code, COALESCE(SUM(d.qty), 0) AS qty
                    FROM sales_order_detail d
                    JOIN sales_order o ON o.order_id = d.order_id
                    WHERE o.order_id = ? OR o.order_no = ?
                    GROUP BY d.goods_code
                    """, orderId, orderId)) {
                Map<String, Object> c = camelize(r);
                ownQty.put(String.valueOf(c.get("goodsCode")), toBd(c.get("qty")));
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            BigDecimal own = ownQty.getOrDefault(String.valueOf(row.get("goodsCode")), BigDecimal.ZERO);
            // 夹取：历史订单可能根本没锁（无回填），加回去的量不能超过实际锁定量
            row.put("ownLockedQty", own.min(toBd(row.get("lockedQty"))));
            out.add(row);
        }
        return ApiResponse.ok(out);
    }

    // ============ 批次库存 · 锁定 / 取消锁定 ============
    /**
     * 锁定批次库存的指定数量。
     * 同步更新 inv_stock_balance.locked_qty / available_qty 供「库存查询」显示。
     */
    @PostMapping("/batch/lock")
    @Transactional
    public ApiResponse<Map<String, Object>> batchLock(@RequestBody Map<String, Object> req) {
        String batchStockId = String.valueOf(req.getOrDefault("batchStockId", ""));
        BigDecimal qty = toBd(req.get("qty"));
        if (batchStockId.isBlank() || "null".equals(batchStockId)) {
            throw new IllegalArgumentException("批次记录 ID 缺失，请刷新查询后重试");
        }
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("锁定数量必须大于 0");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT goods_code, goods_name, warehouse, qty, locked_qty FROM inv_batch_stock WHERE batch_stock_id = ?",
                batchStockId);
        if (rows.isEmpty()) throw new IllegalArgumentException("批次不存在：" + batchStockId);
        Map<String, Object> r = camelize(rows.get(0));
        BigDecimal batchQty = toBd(r.get("qty"));
        BigDecimal locked = toBd(r.get("lockedQty"));
        BigDecimal available = batchQty.subtract(locked);
        if (qty.compareTo(available) > 0) {
            throw new IllegalArgumentException("锁定数量超过可用批次数量：可用 " + available);
        }
        // 更新批次表
        jdbcTemplate.update(
                "UPDATE inv_batch_stock SET locked_qty = COALESCE(locked_qty, 0) + ? WHERE batch_stock_id = ?",
                qty, batchStockId);
        // 联动 inv_stock_balance（按 goods_code + warehouse）
        String goodsCode = String.valueOf(r.get("goodsCode"));
        String warehouse = String.valueOf(r.get("warehouse"));
        jdbcTemplate.update("""
                UPDATE inv_stock_balance
                SET locked_qty = COALESCE(locked_qty, 0) + ?,
                    available_qty = physical_qty - COALESCE(locked_qty, 0) - COALESCE(frozen_qty, 0) - ?
                WHERE goods_code = ? AND warehouse = ?
                """, qty, qty, goodsCode, warehouse);
        return ApiResponse.ok(Map.of(
                "batchStockId", batchStockId,
                "lockedQty", qty,
                "effect", "已锁定 " + qty + " 件（" + goodsCode + " / " + warehouse + "）"
        ));
    }

    @PostMapping("/batch/unlock")
    @Transactional
    public ApiResponse<Map<String, Object>> batchUnlock(@RequestBody Map<String, Object> req) {
        String batchStockId = String.valueOf(req.getOrDefault("batchStockId", ""));
        BigDecimal qty = toBd(req.get("qty"));
        if (batchStockId.isBlank() || "null".equals(batchStockId)) {
            throw new IllegalArgumentException("批次记录 ID 缺失，请刷新查询后重试");
        }
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("取消锁定数量必须大于 0");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT goods_code, warehouse, locked_qty FROM inv_batch_stock WHERE batch_stock_id = ?",
                batchStockId);
        if (rows.isEmpty()) throw new IllegalArgumentException("批次不存在");
        Map<String, Object> r = camelize(rows.get(0));
        BigDecimal locked = toBd(r.get("lockedQty"));
        if (qty.compareTo(locked) > 0) {
            throw new IllegalArgumentException("取消锁定数量超过已锁定：已锁 " + locked);
        }
        jdbcTemplate.update(
                "UPDATE inv_batch_stock SET locked_qty = locked_qty - ? WHERE batch_stock_id = ?",
                qty, batchStockId);
        String goodsCode = String.valueOf(r.get("goodsCode"));
        String warehouse = String.valueOf(r.get("warehouse"));
        jdbcTemplate.update("""
                UPDATE inv_stock_balance
                SET locked_qty = COALESCE(locked_qty, 0) - ?,
                    available_qty = physical_qty - (COALESCE(locked_qty, 0) - ?) - COALESCE(frozen_qty, 0)
                WHERE goods_code = ? AND warehouse = ?
                """, qty, qty, goodsCode, warehouse);
        return ApiResponse.ok(Map.of("batchStockId", batchStockId, "effect", "已取消锁定 " + qty + " 件"));
    }

    /** H2 大写 key → 驼峰。 */
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
