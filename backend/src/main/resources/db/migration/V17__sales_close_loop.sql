-- ============================================
-- V17: 销售模块整体迁移 —— 与采购流程对称（销售订单审核字段 + 销售收货单表）
--
-- 1) sales_order 加审核信息 & 出库金额回写字段（对齐 V14 的 purchase_order 调整）
-- 2) sales_receipt / sales_receipt_detail 两张表 —— 销售流程闭环的收货单
--
-- 决策：
--   · 出库审核 = 仓储确认（扣库存/生成流水） → 自动生成 sales_receipt(PENDING)
--   · 收货审核 = 财务确认 → 写 fin_ar 生成应收
--   · 反审收货单 = 需 fin_ar.received_amount = 0 时才允许（删除 fin_ar 并恢复 PENDING）
--   · 税额策略：逐行按明细 tax_rate 计算行税额，头部 tax_amount = Σ 明细税额（同采购）
-- ============================================

-- 1. sales_order 补审核 & 出库金额回写字段
ALTER TABLE sales_order ADD COLUMN audit_time TIMESTAMP;
ALTER TABLE sales_order ADD COLUMN audit_user VARCHAR(100);
ALTER TABLE sales_order ADD COLUMN outbound_amount DECIMAL(18, 2) DEFAULT 0;

CREATE INDEX idx_sales_order_audit_time ON sales_order(audit_time);

-- 2. sales_receipt 头部
CREATE TABLE sales_receipt (
  receipt_id VARCHAR(32) PRIMARY KEY,
  receipt_no VARCHAR(50) NOT NULL UNIQUE,
  source_outbound_no VARCHAR(50) NOT NULL,   -- 来源销售出库单号（1:1）
  source_order_no VARCHAR(50),               -- 来源销售订单号（透传）
  customer_code VARCHAR(50),
  customer_name VARCHAR(200),
  warehouse VARCHAR(100),
  receipt_date DATE NOT NULL,
  goods_amount DECIMAL(18, 2) DEFAULT 0,     -- Σ 明细金额（不含税）
  tax_amount DECIMAL(18, 2) DEFAULT 0,       -- Σ 明细税额（逐行按 tax_rate）
  expense_amount DECIMAL(18, 2) DEFAULT 0,   -- 费用分摊金额（V1.0 占位 0）
  final_amount DECIMAL(18, 2) DEFAULT 0,     -- 应收金额 = goods + tax + expense
  ar_status VARCHAR(20) DEFAULT '未生成',    -- 未生成 / 已生成（审核后写 fin_ar 置为已生成）
  receive_status VARCHAR(20) DEFAULT '未收款', -- 未收款 / 部分收款 / 已收款（收款单模块回写）
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / APPROVED / CANCELLED
  creator_name VARCHAR(100),
  audit_user VARCHAR(100),
  audit_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500)
);
CREATE UNIQUE INDEX uk_sales_receipt_source_outbound ON sales_receipt(source_outbound_no);
CREATE INDEX idx_sales_receipt_customer ON sales_receipt(customer_code);
CREATE INDEX idx_sales_receipt_status ON sales_receipt(status);
CREATE INDEX idx_sales_receipt_date ON sales_receipt(receipt_date);
CREATE INDEX idx_sales_receipt_source_order ON sales_receipt(source_order_no);

-- 3. sales_receipt_detail 明细（按 goods_code 从出库聚合而来，不含批次号）
CREATE TABLE sales_receipt_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  receipt_id VARCHAR(32) NOT NULL,
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  unit_name VARCHAR(50),
  qty DECIMAL(18, 4) DEFAULT 0,
  price DECIMAL(18, 4) DEFAULT 0,            -- 单价（来源订单单价）
  amount DECIMAL(18, 2) DEFAULT 0,           -- 金额 = qty × price
  tax_rate VARCHAR(20),                      -- 从对应销售订单明细透传
  tax_amount DECIMAL(18, 2) DEFAULT 0,       -- 行税额 = amount × tax_rate
  remark VARCHAR(500)
);
CREATE INDEX idx_sales_receipt_detail_receipt ON sales_receipt_detail(receipt_id);
CREATE INDEX idx_sales_receipt_detail_goods ON sales_receipt_detail(goods_code);
