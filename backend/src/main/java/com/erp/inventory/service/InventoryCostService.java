package com.erp.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.erp.inventory.entity.InvStockBalance;
import com.erp.inventory.entity.InvStockLedger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 库存成本核算引擎 — 移动加权平均法
 *
 * 公式：新平均成本 = (原库存金额 + 本次入库金额) / (原库存数量 + 本次入库数量)
 */
@Service
public class InventoryCostService {

    private final InvStockBalanceService stockBalanceService;
    private final InvStockLedgerService stockLedgerService;

    public InventoryCostService(InvStockBalanceService stockBalanceService, InvStockLedgerService stockLedgerService) {
        this.stockBalanceService = stockBalanceService;
        this.stockLedgerService = stockLedgerService;
    }

    /**
     * 采购入库审核 — 增加库存并重算成本
     */
    @Transactional
    public void purchaseInbound(String goodsCode, String goodsName, String warehouse, String batchNo,
                                 BigDecimal inboundQty, BigDecimal inboundPrice, String sourceBill) {
        // 查询当前库存余额
        InvStockBalance balance = stockBalanceService.getOne(
                new QueryWrapper<InvStockBalance>()
                        .eq("goods_code", goodsCode)
                        .eq("warehouse", warehouse)
        );

        BigDecimal oldQty = balance != null ? balance.getPhysicalQty() : BigDecimal.ZERO;
        BigDecimal oldCost = balance != null ? balance.getCostPrice() : BigDecimal.ZERO;
        BigDecimal oldAmount = balance != null ? balance.getStockAmount() : BigDecimal.ZERO;

        // 移动加权平均法计算新成本
        BigDecimal newQty = oldQty.add(inboundQty);
        BigDecimal inboundAmount = inboundQty.multiply(inboundPrice);
        BigDecimal newCost;
        if (newQty.compareTo(BigDecimal.ZERO) > 0) {
            newCost = oldAmount.add(inboundAmount).divide(newQty, 4, RoundingMode.HALF_UP);
        } else {
            newCost = inboundPrice;
        }
        BigDecimal newStockAmount = newQty.multiply(newCost);

        // 更新或创建库存余额
        if (balance != null) {
            balance.setPhysicalQty(newQty);
            balance.setAvailableQty(newQty.subtract(balance.getLockedQty() != null ? balance.getLockedQty() : BigDecimal.ZERO)
                    .subtract(balance.getFrozenQty() != null ? balance.getFrozenQty() : BigDecimal.ZERO));
            balance.setCostPrice(newCost);
            balance.setStockAmount(newStockAmount);
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

        // 写入库存流水
        InvStockLedger ledger = new InvStockLedger();
        ledger.setLedgerId("SL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        ledger.setLedgerNo("INV" + System.currentTimeMillis());
        ledger.setOccurredAt(LocalDateTime.now());
        ledger.setSourceBill(sourceBill);
        ledger.setGoodsCode(goodsCode);
        ledger.setGoodsName(goodsName);
        ledger.setWarehouse(warehouse);
        ledger.setBatchNo(batchNo);
        ledger.setDirection("IN");
        ledger.setQty(inboundQty);
        ledger.setCostPrice(newCost);
        ledger.setAmount(inboundAmount);
        ledger.setBalanceQty(newQty);
        ledger.setOperatorName("系统管理员");
        stockLedgerService.save(ledger);
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
        BigDecimal newAmount = newQty.multiply(costPrice);

        balance.setPhysicalQty(newQty);
        balance.setAvailableQty(newQty.subtract(balance.getLockedQty() != null ? balance.getLockedQty() : BigDecimal.ZERO)
                .subtract(balance.getFrozenQty() != null ? balance.getFrozenQty() : BigDecimal.ZERO));
        balance.setStockAmount(newAmount);
        balance.setLastInoutTime(LocalDateTime.now());
        stockBalanceService.updateById(balance);

        InvStockLedger ledger = new InvStockLedger();
        ledger.setLedgerId("SL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        ledger.setLedgerNo("INV" + System.currentTimeMillis());
        ledger.setOccurredAt(LocalDateTime.now());
        ledger.setSourceBill(sourceBill);
        ledger.setGoodsCode(goodsCode);
        ledger.setGoodsName(goodsName);
        ledger.setWarehouse(warehouse);
        ledger.setBatchNo(batchNo);
        ledger.setDirection("OUT");
        ledger.setQty(outboundQty);
        ledger.setCostPrice(costPrice);
        ledger.setAmount(outboundQty.multiply(costPrice));
        ledger.setBalanceQty(newQty);
        ledger.setOperatorName("系统管理员");
        stockLedgerService.save(ledger);
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
