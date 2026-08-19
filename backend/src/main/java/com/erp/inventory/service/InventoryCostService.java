package com.erp.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.erp.inventory.entity.InvStockBalance;
import com.erp.inventory.entity.InvStockLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 库存成本核算引擎 — 移动加权平均法
 *
 * 公式：新平均成本 = (原库存金额 + 本次入库金额) / (原库存数量 + 本次入库数量)
 *
 * V15 起：入库时按批次写 inv_batch_stock，同时聚合更新 inv_stock_balance。
 */
@Service
public class InventoryCostService {

    private final InvStockBalanceService stockBalanceService;
    private final InvStockLedgerService stockLedgerService;
    private final JdbcTemplate jdbcTemplate;

    public InventoryCostService(InvStockBalanceService stockBalanceService,
                                 InvStockLedgerService stockLedgerService,
                                 JdbcTemplate jdbcTemplate) {
        this.stockBalanceService = stockBalanceService;
        this.stockLedgerService = stockLedgerService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 采购入库审核 — 增加库存并重算成本（含批次维度）
     * @param productionDate 生产日期（可 null；null 时不写入批次表的 production_date）
     */
    @Transactional
    public void purchaseInbound(String goodsCode, String goodsName, String warehouse, String batchNo,
                                 BigDecimal inboundQty, BigDecimal inboundPrice, String sourceBill,
                                 LocalDate productionDate) {
        BigDecimal inboundAmount = inboundQty.multiply(inboundPrice);

        // 1) 更新/新建批次库存
        upsertBatchStock(goodsCode, goodsName, warehouse, batchNo, productionDate, inboundQty, inboundPrice);

        // 2) 查询当前库存余额（按 goods+warehouse 聚合）
        InvStockBalance balance = stockBalanceService.getOne(
                new QueryWrapper<InvStockBalance>()
                        .eq("goods_code", goodsCode)
                        .eq("warehouse", warehouse)
        );

        BigDecimal oldQty = balance != null && balance.getPhysicalQty() != null ? balance.getPhysicalQty() : BigDecimal.ZERO;
        BigDecimal oldAmount = balance != null && balance.getStockAmount() != null ? balance.getStockAmount() : BigDecimal.ZERO;

        // 移动加权平均法计算新成本
        BigDecimal newQty = oldQty.add(inboundQty);
        BigDecimal newCost;
        if (newQty.compareTo(BigDecimal.ZERO) > 0) {
            newCost = oldAmount.add(inboundAmount).divide(newQty, 4, RoundingMode.HALF_UP);
        } else {
            newCost = inboundPrice;
        }
        BigDecimal newStockAmount = newQty.multiply(newCost).setScale(2, RoundingMode.HALF_UP);

        // 3) 更新或创建库存余额
        if (balance != null) {
            balance.setPhysicalQty(newQty);
            balance.setAvailableQty(newQty.subtract(balance.getLockedQty() != null ? balance.getLockedQty() : BigDecimal.ZERO)
                    .subtract(balance.getFrozenQty() != null ? balance.getFrozenQty() : BigDecimal.ZERO));
            balance.setCostPrice(newCost);
            balance.setStockAmount(newStockAmount);
            balance.setBatchNo(batchNo); // 记录最新批次（供旧查询兼容）
            balance.setLastInoutTime(LocalDateTime.now());
            stockBalanceService.updateById(balance);
        } else {
            InvStockBalance newBalance = new InvStockBalance();
            newBalance.setBalanceId("SB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            newBalance.setGoodsCode(goodsCode);
            newBalance.setGoodsName(goodsName);
            newBalance.setWarehouse(warehouse);
            newBalance.setBatchNo(batchNo);
            newBalance.setPhysicalQty(newQty);
            newBalance.setLockedQty(BigDecimal.ZERO);
            newBalance.setFrozenQty(BigDecimal.ZERO);
            newBalance.setAvailableQty(newQty);
            newBalance.setPurchaseOnWay(BigDecimal.ZERO);
            newBalance.setCostPrice(newCost);
            newBalance.setStockAmount(newStockAmount);
            newBalance.setLastInoutTime(LocalDateTime.now());
            stockBalanceService.save(newBalance);
        }

        // 4) 写入库存流水
        writeLedger("IN", goodsCode, goodsName, warehouse, batchNo, inboundQty, newCost, inboundAmount, newQty, sourceBill);
    }

    /** 兼容旧签名调用（无生产日期）。 */
    @Transactional
    public void purchaseInbound(String goodsCode, String goodsName, String warehouse, String batchNo,
                                 BigDecimal inboundQty, BigDecimal inboundPrice, String sourceBill) {
        purchaseInbound(goodsCode, goodsName, warehouse, batchNo, inboundQty, inboundPrice, sourceBill, null);
    }

    /**
     * 按「当前成本」入库 —— <b>不做移动加权平均重算</b>。
     * <p>
     * 用于其他入库这类不应改变库存成本口径的入库（期初/样品/赠品/盘外发现/内部加工等）：
     * 只增加数量，成本单价沿用库存现有成本，库存金额 = 新数量 × 现有成本。
     * <p>
     * 与 {@link #purchaseInbound} 的区别：purchaseInbound 用入库单价参与加权平均并改写 cost_price；
     * 本方法在已有成本时<b>完全不改 cost_price</b>。
     * <p>
     * 例外：该商品在此仓库<b>尚无成本</b>（首次入库，无余额行或 cost_price 为 0）时没有「当前成本」可取，
     * 只能用 {@code fallbackPrice} 建立初始成本 —— 这是建账，不是重算。
     *
     * @param fallbackPrice 无现有成本时用于建立初始成本的单价
     * @return 实际用于计价与写流水的成本单价，供调用方回写单据
     */
    @Transactional
    public BigDecimal inboundAtCurrentCost(String goodsCode, String goodsName, String warehouse, String batchNo,
                                           BigDecimal inboundQty, BigDecimal fallbackPrice, String sourceBill,
                                           LocalDate productionDate) {
        InvStockBalance balance = stockBalanceService.getOne(
                new QueryWrapper<InvStockBalance>()
                        .eq("goods_code", goodsCode)
                        .eq("warehouse", warehouse)
        );

        BigDecimal existingCost = (balance != null && balance.getCostPrice() != null)
                ? balance.getCostPrice() : BigDecimal.ZERO;
        // 有现存成本就沿用（不重算）；没有才用回落单价建账
        BigDecimal effectiveCost = existingCost.signum() > 0
                ? existingCost
                : (fallbackPrice == null ? BigDecimal.ZERO : fallbackPrice);

        // 1) 批次层：只累加数量，已有批次保留自身成本，新批次按 effectiveCost 建账
        upsertBatchStockKeepCost(goodsCode, goodsName, warehouse, batchNo, productionDate, inboundQty, effectiveCost);

        // 2) 汇总层：只加数量；cost_price 写回 effectiveCost（已有成本时即原值，等于不变）
        BigDecimal oldQty = (balance != null && balance.getPhysicalQty() != null)
                ? balance.getPhysicalQty() : BigDecimal.ZERO;
        BigDecimal newQty = oldQty.add(inboundQty);
        BigDecimal newStockAmount = newQty.multiply(effectiveCost).setScale(2, RoundingMode.HALF_UP);

        if (balance != null) {
            BigDecimal locked = balance.getLockedQty() != null ? balance.getLockedQty() : BigDecimal.ZERO;
            BigDecimal frozen = balance.getFrozenQty() != null ? balance.getFrozenQty() : BigDecimal.ZERO;
            balance.setPhysicalQty(newQty);
            balance.setAvailableQty(newQty.subtract(locked).subtract(frozen));
            balance.setCostPrice(effectiveCost);
            balance.setStockAmount(newStockAmount);
            balance.setBatchNo(batchNo);
            balance.setLastInoutTime(LocalDateTime.now());
            stockBalanceService.updateById(balance);
        } else {
            InvStockBalance nb = new InvStockBalance();
            nb.setBalanceId("SB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            nb.setGoodsCode(goodsCode);
            nb.setGoodsName(goodsName);
            nb.setWarehouse(warehouse);
            nb.setBatchNo(batchNo);
            nb.setPhysicalQty(newQty);
            nb.setLockedQty(BigDecimal.ZERO);
            nb.setFrozenQty(BigDecimal.ZERO);
            nb.setAvailableQty(newQty);
            nb.setPurchaseOnWay(BigDecimal.ZERO);
            nb.setCostPrice(effectiveCost);
            nb.setStockAmount(newStockAmount);
            nb.setLastInoutTime(LocalDateTime.now());
            stockBalanceService.save(nb);
        }

        // 3) 流水：按 effectiveCost 计价
        writeLedger("IN", goodsCode, goodsName, warehouse, batchNo, inboundQty, effectiveCost,
                inboundQty.multiply(effectiveCost).setScale(2, RoundingMode.HALF_UP), newQty, sourceBill);

        return effectiveCost;
    }

    /**
     * upsert 批次库存 —— 只累加数量，<b>不重算成本</b>。
     * 已存在的批次保留原有 cost_price（金额按原成本 × 新数量重算）；新批次用 cost 建账。
     */
    private void upsertBatchStockKeepCost(String goodsCode, String goodsName, String warehouse, String batchNo,
                                          LocalDate productionDate, BigDecimal qty, BigDecimal cost) {
        String normBatchNo = (batchNo == null || batchNo.isBlank()) ? "" : batchNo;
        java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT batch_stock_id, qty, cost_price FROM inv_batch_stock "
                        + "WHERE goods_code = ? AND warehouse = ? AND COALESCE(batch_no, '') = ?",
                goodsCode, warehouse, normBatchNo);
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            Object idV = r.get("batch_stock_id"); if (idV == null) idV = r.get("BATCH_STOCK_ID");
            BigDecimal oldQty = toBd(r.get("qty"), r.get("QTY"));
            BigDecimal batchCost = toBd(r.get("cost_price"), r.get("COST_PRICE"));
            if (batchCost.signum() <= 0) batchCost = cost;   // 老批次没成本时才建账
            BigDecimal newQty = oldQty.add(qty);
            BigDecimal newAmt = newQty.multiply(batchCost).setScale(2, RoundingMode.HALF_UP);
            jdbcTemplate.update(
                    "UPDATE inv_batch_stock SET qty = ?, cost_price = ?, stock_amount = ?, "
                            + "production_date = COALESCE(production_date, ?), "
                            + "last_inout_time = CURRENT_TIMESTAMP WHERE batch_stock_id = ?",
                    newQty, batchCost, newAmt, productionDate, idV);
        } else {
            String id = "BS" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String dbBatchNo = normBatchNo.isEmpty() ? null : normBatchNo;
            jdbcTemplate.update("""
                    INSERT INTO inv_batch_stock (batch_stock_id, goods_code, goods_name, warehouse, batch_no,
                        production_date, qty, cost_price, stock_amount, status, last_inout_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'NORMAL', CURRENT_TIMESTAMP)
                    """,
                    id, goodsCode, goodsName, warehouse, dbBatchNo,
                    productionDate, qty, cost, qty.multiply(cost).setScale(2, RoundingMode.HALF_UP));
        }
    }

    /** upsert 批次库存 —— 若已存在同 (goods, warehouse, batch)，累加数量并按加权平均重算成本。 */
    private void upsertBatchStock(String goodsCode, String goodsName, String warehouse, String batchNo,
                                   LocalDate productionDate, BigDecimal qty, BigDecimal price) {
        // 批次号可能为空（未填生产日期时）：用 COALESCE 将 NULL 视为 '' 以正确匹配空批次
        String normBatchNo = (batchNo == null || batchNo.isBlank()) ? "" : batchNo;
        java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT batch_stock_id, qty, cost_price, stock_amount FROM inv_batch_stock "
                        + "WHERE goods_code = ? AND warehouse = ? AND COALESCE(batch_no, '') = ?",
                goodsCode, warehouse, normBatchNo);
        BigDecimal inboundAmount = qty.multiply(price);
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            Object idV = r.get("batch_stock_id"); if (idV == null) idV = r.get("BATCH_STOCK_ID");
            BigDecimal oldQty = toBd(r.get("qty"), r.get("QTY"));
            BigDecimal oldAmt = toBd(r.get("stock_amount"), r.get("STOCK_AMOUNT"));
            BigDecimal newQty = oldQty.add(qty);
            BigDecimal newAmt = oldAmt.add(inboundAmount).setScale(2, RoundingMode.HALF_UP);
            BigDecimal newCost = newQty.compareTo(BigDecimal.ZERO) > 0
                    ? newAmt.divide(newQty, 4, RoundingMode.HALF_UP) : price;
            jdbcTemplate.update(
                    "UPDATE inv_batch_stock SET qty = ?, cost_price = ?, stock_amount = ?, "
                            // production_date 只在原值为空时补写：老数据可能因早期未透传生产日期而为 NULL，
                            // 这里顺带回填；已有值则不覆盖（同批次生产日期应当一致）
                            + "production_date = COALESCE(production_date, ?), "
                            + "last_inout_time = CURRENT_TIMESTAMP WHERE batch_stock_id = ?",
                    newQty, newCost, newAmt, productionDate, idV);
        } else {
            String id = "BS" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            // 空批次号存为 NULL，COALESCE 索引会按 (goods_code, warehouse) 聚合
            String dbBatchNo = normBatchNo.isEmpty() ? null : normBatchNo;
            jdbcTemplate.update("""
                    INSERT INTO inv_batch_stock (batch_stock_id, goods_code, goods_name, warehouse, batch_no,
                        production_date, qty, cost_price, stock_amount, status, last_inout_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'NORMAL', CURRENT_TIMESTAMP)
                    """,
                    id, goodsCode, goodsName, warehouse, dbBatchNo,
                    productionDate, qty, price, inboundAmount.setScale(2, RoundingMode.HALF_UP));
        }
    }

    private static BigDecimal toBd(Object a, Object b) {
        Object v = a != null ? a : b;
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(String.valueOf(v)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private void writeLedger(String direction, String goodsCode, String goodsName, String warehouse, String batchNo,
                              BigDecimal qty, BigDecimal costPrice, BigDecimal amount, BigDecimal balanceQty, String sourceBill) {
        InvStockLedger ledger = new InvStockLedger();
        ledger.setLedgerId("SL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        ledger.setLedgerNo("INV" + System.currentTimeMillis());
        ledger.setOccurredAt(LocalDateTime.now());
        ledger.setSourceBill(sourceBill);
        ledger.setGoodsCode(goodsCode);
        ledger.setGoodsName(goodsName);
        ledger.setWarehouse(warehouse);
        ledger.setBatchNo(batchNo);
        ledger.setDirection(direction);
        ledger.setQty(qty);
        ledger.setCostPrice(costPrice);
        ledger.setAmount(amount);
        ledger.setBalanceQty(balanceQty);
        ledger.setOperatorName("系统管理员");
        stockLedgerService.save(ledger);
    }

    // ============ 锁定库存（销售订单创建即占用，出库审核/关闭时释放） ============
    //
    // 两层锁定，互不重复计数：
    //   · balance 层（inv_stock_balance.locked_qty）—— 销售订单占用。订单不指定批次，只占「商品 + 仓库」。
    //     创建即锁，审核不重复锁，出库审核时释放并扣实物，关闭/删除时释放剩余。
    //   · batch 层（inv_batch_stock.locked_qty）—— 销售出库单占用所选批次。
    //     创建出库单即锁，出库审核时释放并扣批次实物，出库单被删（订单反审核）时释放。
    //
    // batch 层刻意<b>不联动</b> inv_stock_balance：该数量已被来源订单锁在 balance 层，
    // 再联动就是同一批货被占用两次，可用库存会凭空少一半。
    // （手工的 /inventory/batch/lock 端点是联动的 —— 那是仓管独立的锁定动作，没有订单在 balance 层占位。）

    /**
     * 查询「商品 + 仓库」维度的可用库存。
     *
     * <p>口径与 {@link #salesOutbound} 的判断口径完全一致（都取 inv_stock_balance.available_qty），
     * 保证「销售订单存单/审核能过」与「销售出库审核能过」不会互相打脸。
     *
     * @return 可用库存；该商品在该仓库无库存记录时返回 0（而不是 null）
     */
    public BigDecimal getAvailableQty(String goodsCode, String warehouse) {
        InvStockBalance balance = stockBalanceService.getOne(
                new QueryWrapper<InvStockBalance>()
                        .eq("goods_code", goodsCode)
                        .eq("warehouse", warehouse)
        );
        if (balance == null || balance.getAvailableQty() == null) return BigDecimal.ZERO;
        return balance.getAvailableQty();
    }

    /**
     * 锁定库存 —— 销售订单<b>创建</b>时占用可用库存，避免多张订单抢占同一批库存。
     *
     * <p>锁定只做到「商品 + 仓库」维度，不落批次：销售订单本身不指定批次，
     * 批次是到销售出库单才选的（那一层用 {@link #lockBatch}）。
     *
     * @throws IllegalArgumentException 可用库存不足，或该商品在该仓库无库存记录
     */
    @Transactional
    public void lockStock(String goodsCode, String warehouse, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) return;
        InvStockBalance balance = stockBalanceService.getOne(
                new QueryWrapper<InvStockBalance>()
                        .eq("goods_code", goodsCode)
                        .eq("warehouse", warehouse)
        );
        BigDecimal available = balance == null || balance.getAvailableQty() == null
                ? BigDecimal.ZERO : balance.getAvailableQty();
        if (balance == null || available.compareTo(qty) < 0) {
            throw new IllegalArgumentException(
                    "可用库存不足，无法锁定：" + goodsCode + " / " + warehouse + "，需 " + qty + "，可用 " + available);
        }
        BigDecimal locked = nz(balance.getLockedQty()).add(qty);
        balance.setLockedQty(locked);
        balance.setAvailableQty(nz(balance.getPhysicalQty()).subtract(locked).subtract(nz(balance.getFrozenQty())));
        stockBalanceService.updateById(balance);
    }

    /**
     * 释放锁定库存 —— 销售订单关闭/删除，或销售出库审核扣实物之前调用。
     *
     * <p>注意：销售订单<b>反审核不释放</b>（订单回到待审核仍然占用库存，直到出库或关闭）。
     *
     * <p><b>刻意向下夹取</b>：实际释放量 = min(请求量, 当前已锁定量)。
     * 库里存在一批「创建/审核时还没有锁定逻辑」的历史销售订单，它们没有锁定记录，
     * 关闭/出库时释放必须能安全地释放 0，而不是把 locked_qty 打成负数。
     */
    @Transactional
    public void releaseLock(String goodsCode, String warehouse, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) return;
        InvStockBalance balance = stockBalanceService.getOne(
                new QueryWrapper<InvStockBalance>()
                        .eq("goods_code", goodsCode)
                        .eq("warehouse", warehouse)
        );
        if (balance == null) return;
        BigDecimal locked = nz(balance.getLockedQty());
        // 夹取：已锁的比要释放的少，就只释放已锁的那部分
        BigDecimal release = qty.min(locked);
        if (release.signum() <= 0) return;
        BigDecimal newLocked = locked.subtract(release);
        balance.setLockedQty(newLocked);
        balance.setAvailableQty(nz(balance.getPhysicalQty()).subtract(newLocked).subtract(nz(balance.getFrozenQty())));
        stockBalanceService.updateById(balance);
    }

    /** null 当 0 —— 历史数据里 locked_qty / frozen_qty 可能为 NULL。 */
    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 查询「商品 + 仓库」维度的实物库存。
     *
     * <p>销售订单审核时用它兜底：本单数量在创建时已锁进 {@code locked_qty}，
     * 再拿「可用库存」比对必然自己挡自己，只有实物库存是有意义的下限
     * （实物可能被盘亏、其他出库、调拨抽走）。
     */
    public BigDecimal getPhysicalQty(String goodsCode, String warehouse) {
        InvStockBalance balance = stockBalanceService.getOne(
                new QueryWrapper<InvStockBalance>()
                        .eq("goods_code", goodsCode)
                        .eq("warehouse", warehouse)
        );
        if (balance == null) return BigDecimal.ZERO;
        return nz(balance.getPhysicalQty());
    }

    /** 查询「商品 + 仓库」维度的当前锁定量；无记录返回 0。 */
    public BigDecimal getLockedQty(String goodsCode, String warehouse) {
        InvStockBalance balance = stockBalanceService.getOne(
                new QueryWrapper<InvStockBalance>()
                        .eq("goods_code", goodsCode)
                        .eq("warehouse", warehouse)
        );
        if (balance == null) return BigDecimal.ZERO;
        return nz(balance.getLockedQty());
    }

    /**
     * 锁定批次库存 —— 销售出库单创建/编辑时占用所选批次。
     *
     * <p>只写 {@code inv_batch_stock.locked_qty}，<b>不联动</b> {@code inv_stock_balance}：
     * 这批量已被来源销售订单锁在 balance 层，联动会造成同一批货占用两次。
     *
     * @throws IllegalArgumentException 批次不存在，或批次可用量（数量 − 已锁定）不足
     */
    @Transactional
    public void lockBatch(String goodsCode, String warehouse, String batchNo, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) return;
        if (batchNo == null || batchNo.isBlank()) return;   // 未指定批次的行不落批次锁
        java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT batch_stock_id, COALESCE(qty, 0) AS qty, COALESCE(locked_qty, 0) AS locked_qty
                FROM inv_batch_stock
                WHERE goods_code = ? AND warehouse = ? AND batch_no = ?
                """, goodsCode, warehouse, batchNo);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "批次不存在，无法锁定：" + goodsCode + " / " + warehouse + " / 批次 " + batchNo);
        }
        Map<String, Object> r = rows.get(0);
        BigDecimal batchQty = toBd(r.get("qty"), r.get("QTY"));
        BigDecimal locked = toBd(r.get("locked_qty"), r.get("LOCKED_QTY"));
        BigDecimal available = batchQty.subtract(locked);
        if (qty.compareTo(available) > 0) {
            throw new IllegalArgumentException("批次可用量不足，无法锁定：" + goodsCode + " / 批次 " + batchNo
                    + "，需 " + qty + "，可用 " + available);
        }
        Object idV = r.get("batch_stock_id") != null ? r.get("batch_stock_id") : r.get("BATCH_STOCK_ID");
        jdbcTemplate.update(
                "UPDATE inv_batch_stock SET locked_qty = COALESCE(locked_qty, 0) + ? WHERE batch_stock_id = ?",
                qty, idV);
    }

    /**
     * 释放批次锁定 —— 销售出库单审核（扣批次实物前）/被删除（订单反审核）时调用。
     *
     * <p>与 {@link #releaseLock} 同样<b>向下夹取</b>：历史出库单创建时没有批次锁，
     * 释放必须安全地释放 0。
     */
    @Transactional
    public void releaseBatchLock(String goodsCode, String warehouse, String batchNo, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) return;
        if (batchNo == null || batchNo.isBlank()) return;
        java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT batch_stock_id, COALESCE(locked_qty, 0) AS locked_qty
                FROM inv_batch_stock
                WHERE goods_code = ? AND warehouse = ? AND batch_no = ?
                """, goodsCode, warehouse, batchNo);
        if (rows.isEmpty()) return;
        Map<String, Object> r = rows.get(0);
        BigDecimal locked = toBd(r.get("locked_qty"), r.get("LOCKED_QTY"));
        BigDecimal release = qty.min(locked);
        if (release.signum() <= 0) return;
        Object idV = r.get("batch_stock_id") != null ? r.get("batch_stock_id") : r.get("BATCH_STOCK_ID");
        jdbcTemplate.update(
                "UPDATE inv_batch_stock SET locked_qty = COALESCE(locked_qty, 0) - ? WHERE batch_stock_id = ?",
                release, idV);
    }

    /**
     * 销售出库审核 — 扣减库存（成本不变）
     */
    @Transactional
    public void salesOutbound(String goodsCode, String goodsName, String warehouse, String batchNo,
                               BigDecimal outboundQty, String sourceBill) {
        InvStockBalance balance = stockBalanceService.getOne(
                new QueryWrapper<InvStockBalance>()
                        .eq("goods_code", goodsCode)
                        .eq("warehouse", warehouse)
        );
        if (balance == null || balance.getAvailableQty().compareTo(outboundQty) < 0) {
            throw new IllegalArgumentException("库存不足：" + goodsCode + " / " + warehouse);
        }

        BigDecimal costPrice = balance.getCostPrice();
        BigDecimal newQty = balance.getPhysicalQty().subtract(outboundQty);
        BigDecimal newAmount = newQty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

        balance.setPhysicalQty(newQty);
        balance.setAvailableQty(newQty.subtract(balance.getLockedQty() != null ? balance.getLockedQty() : BigDecimal.ZERO)
                .subtract(balance.getFrozenQty() != null ? balance.getFrozenQty() : BigDecimal.ZERO));
        balance.setStockAmount(newAmount);
        balance.setLastInoutTime(LocalDateTime.now());
        stockBalanceService.updateById(balance);

        // 批次层：从指定批次扣减；若 batch 为空则按 FIFO 从最早生产日期开始扣
        if (batchNo != null && !batchNo.isBlank()) {
            jdbcTemplate.update("""
                    UPDATE inv_batch_stock
                    SET qty = qty - ?, stock_amount = (qty - ?) * cost_price, last_inout_time = CURRENT_TIMESTAMP
                    WHERE goods_code = ? AND warehouse = ? AND batch_no = ?
                    """, outboundQty, outboundQty, goodsCode, warehouse, batchNo);
        }

        writeLedger("OUT", goodsCode, goodsName, warehouse, batchNo, outboundQty, costPrice,
                outboundQty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP), newQty, sourceBill);
    }

    /**
     * 获取当前成本单价
     */
    public BigDecimal getCurrentCostPrice(String goodsCode, String warehouse) {
        InvStockBalance balance = stockBalanceService.getOne(
                new QueryWrapper<InvStockBalance>()
                        .eq("goods_code", goodsCode)
                        .eq("warehouse", warehouse)
        );
        return balance != null ? balance.getCostPrice() : BigDecimal.ZERO;
    }
}
