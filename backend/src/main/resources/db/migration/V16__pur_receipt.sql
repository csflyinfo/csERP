-- ============================================
-- V16: 采购收货单（Phase 5）
--
-- Step C 引入两张新表：pur_receipt（头）+ pur_receipt_detail（明细）
-- 采购流程闭环：
--   采购订单(APPROVED) → 采购入库单(APPROVED) → 【自动生成】采购收货单(PENDING) →
--   收货单审核 → 写 fin_ap 生成正式应付
--
-- 决策 B/B/A：
--   - pay_status 由后续付款单/收款单自动回写（本次仅保留字段占位，V1.0 不使用）
--   - 走既有 fin_ap 应付账款表，不建 supplier_settlement
--   - 入库单不能跳过收货单直接生成应付
--
-- 税额：逐行按明细 tax_rate 计算，头部 tax_amount = Σ 明细税额（决策 A）
-- ============================================

CREATE TABLE pur_receipt (
  receipt_id VARCHAR(32) PRIMARY KEY,
  receipt_no VARCHAR(50) NOT NULL UNIQUE,
  source_inbound_no VARCHAR(50) NOT NULL,   -- 来源入库单号（1:1 对应，一张入库单生成一张收货单）
  source_order_no VARCHAR(50),              -- 来源采购订单号（透传，便于查询）
  supplier_code VARCHAR(50),
  supplier_name VARCHAR(200),
  warehouse VARCHAR(100),
  receipt_date DATE NOT NULL,
  goods_amount DECIMAL(18, 2) DEFAULT 0,    -- 商品金额 = Σ 明细金额（不含税）
  tax_amount DECIMAL(18, 2) DEFAULT 0,      -- 税额 = Σ 明细税额（逐行按 tax_rate 计算）
  expense_amount DECIMAL(18, 2) DEFAULT 0,  -- 费用分摊金额（V1.0 占位，暂为 0）
  final_amount DECIMAL(18, 2) DEFAULT 0,    -- 最终金额 = goods_amount + tax_amount + expense_amount
  ap_status VARCHAR(20) DEFAULT '未生成',   -- 未生成 / 已生成（审核时写 fin_ap 后置为已生成）
  pay_status VARCHAR(20) DEFAULT '未付款',  -- 未付款 / 部分付款 / 已付款（V1.0 占位，付款单模块回写）
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / APPROVED / CANCELLED
  creator_name VARCHAR(100),
  audit_user VARCHAR(100),
  audit_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500)
);
CREATE UNIQUE INDEX uk_pur_receipt_source_inbound ON pur_receipt(source_inbound_no);
CREATE INDEX idx_pur_receipt_supplier ON pur_receipt(supplier_code);
CREATE INDEX idx_pur_receipt_status ON pur_receipt(status);
CREATE INDEX idx_pur_receipt_date ON pur_receipt(receipt_date);
CREATE INDEX idx_pur_receipt_source_order ON pur_receipt(source_order_no);

-- 收货单明细：按 goods_code 从入库单聚合而来（不含批次 / 生产日期）
CREATE TABLE pur_receipt_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  receipt_id VARCHAR(32) NOT NULL,
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  unit_name VARCHAR(50),
  qty DECIMAL(18, 4) DEFAULT 0,             -- 数量 = Σ 入库明细.received_qty by goods_code
  price DECIMAL(18, 4) DEFAULT 0,           -- 单价 = 入库明细价（同商品同价，来源订单单价）
  amount DECIMAL(18, 2) DEFAULT 0,          -- 金额 = qty * price
  tax_rate VARCHAR(20),                     -- 从对应采购订单明细透传
  tax_amount DECIMAL(18, 2) DEFAULT 0,      -- 行税额 = amount * tax_rate
  remark VARCHAR(500)
);
CREATE INDEX idx_pur_receipt_detail_receipt ON pur_receipt_detail(receipt_id);
CREATE INDEX idx_pur_receipt_detail_goods ON pur_receipt_detail(goods_code);
