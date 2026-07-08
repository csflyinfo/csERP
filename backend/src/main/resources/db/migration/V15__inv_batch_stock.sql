-- ============================================
-- V15: 批次库存表（Phase 2）
-- 独立表 inv_batch_stock 记录按批次的库存明细
-- inv_stock_balance 仍作为「按 (goods_code, warehouse) 聚合视图」
--
-- 交互约定：
-- - 采购入库审核时按批次写 inv_batch_stock，同时聚合更新 inv_stock_balance
-- - inv_stock_balance.cost_price 按加权平均法重算
-- - 保留 inv_stock_balance.batch_no 字段但不再用作唯一标识（V1.0 兼容期）
-- ============================================

CREATE TABLE inv_batch_stock (
  batch_stock_id VARCHAR(32) PRIMARY KEY,
  goods_code VARCHAR(50) NOT NULL,
  goods_name VARCHAR(200),
  warehouse VARCHAR(100) NOT NULL,
  batch_no VARCHAR(100) NOT NULL,
  production_date DATE,
  expiry_date DATE,
  qty DECIMAL(18, 4) DEFAULT 0,
  cost_price DECIMAL(18, 4) DEFAULT 0,
  stock_amount DECIMAL(18, 2) DEFAULT 0,
  status VARCHAR(20) DEFAULT 'NORMAL',
  last_inout_time TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_batch_stock_gwb ON inv_batch_stock(goods_code, warehouse, batch_no);
CREATE INDEX idx_batch_stock_goods ON inv_batch_stock(goods_code);
CREATE INDEX idx_batch_stock_warehouse ON inv_batch_stock(warehouse);
CREATE INDEX idx_batch_stock_expiry ON inv_batch_stock(expiry_date);
