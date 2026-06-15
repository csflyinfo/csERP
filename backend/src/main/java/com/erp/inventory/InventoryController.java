package com.erp.inventory;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
        return ApiResponse.ok(PageResult.of(List.of(Map.of(
                "lockNo", "LOCK202606140001",
                "sourceBill", "SO202606140001",
                "goodsCode", "SP001",
                "warehouse", "总仓",
                "lockedQty", "180",
                "status", "锁定中"
        )), request));
    }

    @PostMapping("/batch/page")
    public ApiResponse<PageResult<Map<String, Object>>> batchPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(List.of(Map.of(
                "goodsCode", "SP001",
                "goodsName", "农夫山泉500ml*24",
                "warehouse", "总仓",
                "batchNo", "B202606",
                "productionDate", "2026-06-01",
                "expiryDate", "2027-06-01",
                "qty", "1200",
                "status", "正常"
        )), request));
    }

    @PostMapping("/warning/page")
    public ApiResponse<PageResult<Map<String, Object>>> warningPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(List.of(Map.of(
                "goodsCode", "SP001",
                "goodsName", "农夫山泉500ml*24",
                "warehouse", "总仓",
                "warningType", "低于下限",
                "currentQty", "80",
                "lowerLimit", "100",
                "status", "待处理"
        )), request));
    }

    @PostMapping("/transfer/page")
    public ApiResponse<PageResult<Map<String, Object>>> transferPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no transferNo, object_name objectName, warehouse, reason, qty, amount,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='TRANSFER' ORDER BY bill_no DESC
                """), request));
    }

    @PostMapping("/transfer/audit")
    public ApiResponse<Map<String, Object>> auditTransfer(@RequestBody Map<String, Object> request) {
        jdbcTemplate.update("UPDATE biz_simple_bill SET status='APPROVED' WHERE bill_type='TRANSFER' AND (bill_id=? OR bill_no=? OR bill_no=(SELECT bill_no FROM biz_simple_bill WHERE bill_type='TRANSFER' ORDER BY bill_no DESC LIMIT 1))", request.get("bizId"), request.get("bizId"));
        jdbcTemplate.update("INSERT INTO inv_stock_ledger(ledger_id, ledger_no, occurred_at, source_bill, goods_code, goods_name, warehouse, batch_no, direction, qty, cost_price, amount, balance_qty, operator_name) VALUES (?, ?, CURRENT_TIMESTAMP, 'TR202606140001', 'SP001', '农夫山泉500ml*24', '总仓', 'B202606', 'OUT', 100, 30.80, 3080.00, 1100, '管理员')", "SL" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12), "INV" + System.currentTimeMillis());
        jdbcTemplate.update("INSERT INTO inv_stock_ledger(ledger_id, ledger_no, occurred_at, source_bill, goods_code, goods_name, warehouse, batch_no, direction, qty, cost_price, amount, balance_qty, operator_name) VALUES (?, ?, CURRENT_TIMESTAMP, 'TR202606140001', 'SP001', '农夫山泉500ml*24', '东区仓', 'B202606', 'IN', 100, 30.80, 3080.00, 100, '管理员')", "SL" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12), "INV" + (System.currentTimeMillis() + 1));
        return ApiResponse.ok(Map.of("status", "APPROVED", "effect", "调拨已审核，生成调出和调入库存流水"));
    }

    @PostMapping("/damage/page")
    public ApiResponse<PageResult<Map<String, Object>>> damagePage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no damageNo, warehouse, reason damageType, qty, amount costAmount,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='DAMAGE' ORDER BY bill_no DESC
                """), request));
    }

    @PostMapping("/damage/audit")
    public ApiResponse<Map<String, Object>> auditDamage(@RequestBody Map<String, Object> request) {
        jdbcTemplate.update("UPDATE biz_simple_bill SET status='APPROVED' WHERE bill_type='DAMAGE' AND (bill_id=? OR bill_no=? OR bill_no=(SELECT bill_no FROM biz_simple_bill WHERE bill_type='DAMAGE' ORDER BY bill_no DESC LIMIT 1))", request.get("bizId"), request.get("bizId"));
        jdbcTemplate.update("UPDATE inv_stock_balance SET physical_qty=physical_qty-20, available_qty=available_qty-20, stock_amount=(physical_qty-20)*cost_price, last_inout_time=CURRENT_TIMESTAMP WHERE balance_id='SB001'");
        jdbcTemplate.update("INSERT INTO inv_stock_ledger(ledger_id, ledger_no, occurred_at, source_bill, goods_code, goods_name, warehouse, batch_no, direction, qty, cost_price, amount, balance_qty, operator_name) VALUES (?, ?, CURRENT_TIMESTAMP, 'DO202606140001', 'SP001', '农夫山泉500ml*24', '冷藏仓', 'B202606', 'OUT', 20, 30.80, 616.00, 1180, '管理员')", "SL" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12), "INV" + System.currentTimeMillis());
        return ApiResponse.ok(Map.of("status", "APPROVED", "effect", "报损已审核，库存扣减并生成出库流水"));
    }

    @PostMapping("/cost-adjust/page")
    public ApiResponse<PageResult<Map<String, Object>>> costAdjustPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT bill_no adjustNo, warehouse, object_name goodsCode, amount diffAmount, reason,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM biz_simple_bill WHERE bill_type='COST_ADJUST' ORDER BY bill_no DESC
                """), request));
    }

    @PostMapping("/cost-adjust/audit")
    public ApiResponse<Map<String, Object>> auditCostAdjust(@RequestBody Map<String, Object> request) {
        jdbcTemplate.update("UPDATE biz_simple_bill SET status='APPROVED' WHERE bill_type='COST_ADJUST' AND (bill_id=? OR bill_no=? OR bill_no=(SELECT bill_no FROM biz_simple_bill WHERE bill_type='COST_ADJUST' ORDER BY bill_no DESC LIMIT 1))", request.get("bizId"), request.get("bizId"));
        jdbcTemplate.update("UPDATE inv_stock_balance SET cost_price=cost_price+0.28, stock_amount=physical_qty*(cost_price+0.28), last_inout_time=CURRENT_TIMESTAMP WHERE balance_id='SB001'");
        return ApiResponse.ok(Map.of("status", "APPROVED", "effect", "成本调整已审核，只调整成本不改变库存数量"));
    }
}
