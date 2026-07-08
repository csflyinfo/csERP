-- ============================================
-- V23: 采购退货流程 — 三表联动
--
-- 业务链路：
--   采购退货申请(CTSQ) --审核--> 采购退货出库(CTCK) --审核--> 采购退货单(CGTH)
--       (可选关联采购收货单)        扣库存+按成本计价             冲减应付(负向fin_ap)
--
-- 设计决策：
--   A. 退货申请可选关联采购收货单（source_receipt_no 可为 NULL）
--   B. 申请审核后自动生成 1:1 退货出库单（幂等：唯一索引）
--   C. 出库审核后自动生成 1:1 采购退货单（幂等：唯一索引）
--   D. 退货单审核后写负向 fin_ap，不修改原应付单
-- ============================================

-- ========== 1. 采购退货申请 ==========

CREATE TABLE pur_return_apply (
  apply_id VARCHAR(32) PRIMARY KEY,
  apply_no VARCHAR(50) NOT NULL UNIQUE,
  source_receipt_no VARCHAR(50),                -- 来源采购收货单号（可为 NULL，可选关联）
  supplier_code VARCHAR(50),
  supplier_name VARCHAR(200),
  warehouse VARCHAR(100),
  bill_date DATE NOT NULL,
  qty DECIMAL(18, 4) DEFAULT 0,
  amount DECIMAL(18, 2) DEFAULT 0,
  return_reason VARCHAR(200),
  status VARCHAR(20) DEFAULT 'DRAFT',           -- DRAFT / PENDING / APPROVED / OUTBOUNDED / COMPLETED
  outbound_generated BOOLEAN DEFAULT FALSE,
  creator_name VARCHAR(100),
  audit_user VARCHAR(100),
  audit_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500)
);
CREATE INDEX idx_pur_return_apply_status ON pur_return_apply(status);
CREATE INDEX idx_pur_return_apply_supplier ON pur_return_apply(supplier_code);
CREATE INDEX idx_pur_return_apply_date ON pur_return_apply(bill_date);
CREATE INDEX idx_pur_return_apply_receipt ON pur_return_apply(source_receipt_no);

CREATE TABLE pur_return_apply_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  apply_id VARCHAR(32) NOT NULL,
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  unit_name VARCHAR(50),
  qty DECIMAL(18, 4) DEFAULT 0,
  price DECIMAL(18, 4) DEFAULT 0,
  amount DECIMAL(18, 2) DEFAULT 0,
  batch_no VARCHAR(100),
  production_date DATE,                         -- PRD 关键字段：退货生产日期
  tax_rate VARCHAR(20) DEFAULT '13%',
  remark VARCHAR(200)
);
CREATE INDEX idx_pur_return_apply_detail_apply ON pur_return_apply_detail(apply_id);

-- ========== 2. 采购退货出库单（WMS指令） ==========

CREATE TABLE pur_return_outbound (
  outbound_id VARCHAR(32) PRIMARY KEY,
  outbound_no VARCHAR(50) NOT NULL UNIQUE,
  source_apply_no VARCHAR(50) NOT NULL,         -- 来源申请单号（1:1 对应）
  supplier_code VARCHAR(50),
  supplier_name VARCHAR(200),
  warehouse VARCHAR(100),
  bill_date DATE NOT NULL,
  qty DECIMAL(18, 4) DEFAULT 0,
  amount DECIMAL(18, 2) DEFAULT 0,
  cost_amount DECIMAL(18, 2) DEFAULT 0,
  status VARCHAR(20) DEFAULT 'PENDING',         -- PENDING / APPROVED
  stock_updated BOOLEAN DEFAULT FALSE,
  return_generated BOOLEAN DEFAULT FALSE,
  audit_user VARCHAR(100),
  audit_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500)
);
CREATE UNIQUE INDEX uk_pur_return_outbound_apply ON pur_return_outbound(source_apply_no);
CREATE INDEX idx_pur_return_outbound_status ON pur_return_outbound(status);

CREATE TABLE pur_return_outbound_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  outbound_id VARCHAR(32) NOT NULL,
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  unit_name VARCHAR(50),
  qty DECIMAL(18, 4) DEFAULT 0,
  price DECIMAL(18, 4) DEFAULT 0,
  amount DECIMAL(18, 2) DEFAULT 0,
  batch_no VARCHAR(100),
  production_date DATE,
  cost_price DECIMAL(18, 6) DEFAULT 0,         -- 成本单价 6 位小数（CLAUDE.md 规范）
  cost_amount DECIMAL(18, 2) DEFAULT 0
);
CREATE INDEX idx_pur_return_outbound_detail_outbound ON pur_return_outbound_detail(outbound_id);

-- ========== 3. 采购退货单 ==========

CREATE TABLE pur_return (
  return_id VARCHAR(32) PRIMARY KEY,
  return_no VARCHAR(50) NOT NULL UNIQUE,
  source_apply_no VARCHAR(50),
  source_outbound_no VARCHAR(50) NOT NULL,      -- 来源出库单号（1:1 对应）
  supplier_code VARCHAR(50),
  supplier_name VARCHAR(200),
  warehouse VARCHAR(100),
  return_date DATE NOT NULL,
  goods_amount DECIMAL(18, 2) DEFAULT 0,        -- 商品金额 = Σ 明细金额（不含税）
  tax_amount DECIMAL(18, 2) DEFAULT 0,          -- 税额 = Σ 行税额
  final_amount DECIMAL(18, 2) DEFAULT 0,        -- 最终金额 = goods_amount + tax_amount
  cost_amount DECIMAL(18, 2) DEFAULT 0,          -- 成本金额
  ap_status VARCHAR(20) DEFAULT '未生成',        -- 未生成 / 已生成（审核后写负向 fin_ap）
  status VARCHAR(20) DEFAULT 'PENDING',          -- PENDING / APPROVED
  creator_name VARCHAR(100),
  audit_user VARCHAR(100),
  audit_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500)
);
CREATE UNIQUE INDEX uk_pur_return_source_outbound ON pur_return(source_outbound_no);
CREATE INDEX idx_pur_return_status ON pur_return(status);
CREATE INDEX idx_pur_return_supplier ON pur_return(supplier_code);
CREATE INDEX idx_pur_return_date ON pur_return(return_date);

CREATE TABLE pur_return_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  return_id VARCHAR(32) NOT NULL,
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  unit_name VARCHAR(50),
  qty DECIMAL(18, 4) DEFAULT 0,
  price DECIMAL(18, 4) DEFAULT 0,
  amount DECIMAL(18, 2) DEFAULT 0,
  tax_rate VARCHAR(20) DEFAULT '13%',
  tax_amount DECIMAL(18, 2) DEFAULT 0,
  cost_price DECIMAL(18, 6) DEFAULT 0,          -- 成本单价 6 位小数
  cost_amount DECIMAL(18, 2) DEFAULT 0
);
CREATE INDEX idx_pur_return_detail_return ON pur_return_detail(return_id);