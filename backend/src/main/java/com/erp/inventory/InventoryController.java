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
import java.util.List;
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
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT goods_code goodsCode,
                       goods_name goodsName,
                       warehouse,
                       batch_no batchNo,
                       physical_qty physicalQty,
                       locked_qty lockedQty,
                       frozen_qty frozenQty,
                       available_qty availableQty,
                       purchase_on_way purchaseOnWay,
                       cost_price costPrice,
                       stock_amount stockAmount,
                       last_inout_time lastInoutTime
                FROM inv_stock_balance
                ORDER BY goods_code, warehouse
                """), request));
    }

    @PostMapping("/ledger/page")
    public ApiResponse<PageResult<Map<String, Object>>> ledgerPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT ledger_no ledgerNo,
                       occurred_at occurredAt,
                       source_bill sourceBill,
                       goods_code goodsCode,
                       goods_name goodsName,
                       warehouse,
                       batch_no batchNo,
                       CASE direction WHEN 'IN' THEN '入库' ELSE '出库' END direction,
                       qty,
                       cost_price costPrice,
                       amount,
                       balance_qty balanceQty,
                       operator_name operator
                FROM inv_stock_ledger
                ORDER BY occurred_at DESC, ledger_no DESC
                """), request));
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
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT goods_code goodsCode,
                       goods_name goodsName,
                       warehouse,
                       batch_no batchNo,
                       physical_qty qty,
                       cost_price costPrice,
                       '正常' status
                FROM inv_stock_balance
                WHERE batch_no IS NOT NULL
                ORDER BY goods_code, warehouse, batch_no
                """), request));
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

    @PostMapping("/damage/page")
    public ApiResponse<PageResult<Map<String, Object>>> damagePage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no damageNo, warehouse, reason damageType, qty, amount costAmount,
                       goods_code goodsCode, goods_name goodsName,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='DAMAGE' ORDER BY bill_no DESC
                """), request));
    }

    @PostMapping("/damage/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditDamage(@RequestBody Map<String, Object> request) {
        List<Map<String, Object>> bills = jdbcTemplate.queryForList(
            "SELECT * FROM biz_simple_bill WHERE bill_type='DAMAGE' AND (bill_id=? OR bill_no=? OR bill_no=(SELECT bill_no FROM biz_simple_bill WHERE bill_type='DAMAGE' ORDER BY bill_no DESC LIMIT 1))",
            request.get("bizId"), request.get("bizId"));
        if (bills.isEmpty()) {
            throw new IllegalArgumentException("报损单不存在");
        }
        Map<String, Object> bill = bills.get(0);
        String goodsCode = String.valueOf(bill.getOrDefault("GOODS_CODE", "SP001"));
        String goodsName = String.valueOf(bill.getOrDefault("GOODS_NAME", ""));
        String warehouse = String.valueOf(bill.getOrDefault("WAREHOUSE", "总仓"));
        BigDecimal qty = toBigDecimal(bill.get("QTY"));

        BigDecimal costPrice = getCostPrice(goodsCode, warehouse);
        BigDecimal amount = qty.multiply(costPrice);

        // 扣减库存
        deductStock(goodsCode, warehouse, qty);

        // 更新单据状态
        jdbcTemplate.update(
            "UPDATE biz_simple_bill SET status='APPROVED' WHERE bill_id=?",
            bill.get("BILL_ID"));

        // 生成出库流水
        BigDecimal balance = getBalanceQty(goodsCode, warehouse);
        insertLedger(goodsCode, goodsName, warehouse, "OUT", qty, costPrice, amount, balance,
            String.valueOf(bill.get("BILL_NO")));

        return ApiResponse.ok(Map.of(
            "status", "APPROVED",
            "effect", "报损已审核：扣减 " + warehouse + " " + goodsCode + " " + qty + " 件",
            "goodsCode", goodsCode,
            "qty", qty,
            "warehouse", warehouse
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
}
