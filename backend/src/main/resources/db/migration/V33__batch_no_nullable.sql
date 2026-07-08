-- ============================================
-- V33: 批次号支持空值
-- 商品批次号生成规则调整：未填生产日期时批次号为空
-- inv_batch_stock.batch_no 由 NOT NULL 改为可空
-- 唯一索引改用 COALESCE，使空批次按 (goods_code, warehouse) 聚合
-- ============================================

-- 1. 删除旧唯一索引
DROP INDEX IF EXISTS uk_batch_stock_gwb;

-- 2. batch_no 改为可空
ALTER TABLE inv_batch_stock ALTER COLUMN batch_no SET NULL;

-- 3. 重建普通索引（H2 不支持表达式索引；唯一性由应用层保证）
CREATE INDEX uk_batch_stock_gwb ON inv_batch_stock(goods_code, warehouse, batch_no);
