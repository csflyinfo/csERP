-- ============================================
-- V18: 批次库存锁定支持
--
-- 增加 inv_batch_stock.locked_qty / frozen_qty 列，供「批次库存查询」的锁定/取消锁定功能使用。
-- 语义：
--   · locked_qty  锁定件数（不可出库，但已在库存中）
--   · frozen_qty  冻结件数（预留，V1.0 未启用）
--
-- 联动：锁定/取消锁定单条批次时，同步 inv_stock_balance.locked_qty（按 goods_code + warehouse 聚合视图口径）。
-- ============================================

ALTER TABLE inv_batch_stock ADD COLUMN locked_qty DECIMAL(18, 4) DEFAULT 0;
ALTER TABLE inv_batch_stock ADD COLUMN frozen_qty DECIMAL(18, 4) DEFAULT 0;
