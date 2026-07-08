-- ============================================
-- V28: 销售退货申请 — 三表联动（第一阶段：申请→入库→退货单）
--
-- 业务链路：
--   销售退货申请(XTSQ) --审核--> 销售退货入库(THRK) --审核--> 销售退货单(XSTH)
--       (可选关联销售出库单)        回库+按成本计价             冲减应收(负向fin_ar)
--
-- 设计决策（镜像采购退货，方向相反）：
--   A. 退货申请可选关联销售出库单（source_outbound_no 可为 NULL）
--   B. 申请审核后自动生成 1:1 退货入库单（幂等：唯一索引）
--   C. 入库审核后自动生成 1:1 销售退货单（幂等：唯一索引）
--   D. 退货单审核后写负向 fin_ar，不修改原应收单
-- ============================================

-- ========== 1. 销售退货申请 ==========

CREATE TABLE sales_return_apply (
  apply_id VARCHAR(32) PRIMARY KEY,
  apply_no VARCHAR(50) NOT NULL UNIQUE,
  source_outbound_no VARCHAR(50),               -- 来源销售出库单号（可为 NULL，可选关联）
  customer_code VARCHAR(50),
  customer_name VARCHAR(200),
  warehouse VARCHAR(100),
  bill_date DATE NOT NULL,
  qty DECIMAL(18, 4) DEFAULT 0,
  amount DECIMAL(18, 2) DEFAULT 0,
  return_reason VARCHAR(200),
  status VARCHAR(20) DEFAULT 'DRAFT',            -- DRAFT / PENDING / APPROVED / INBOUNDED / COMPLETED
  inbound_generated BOOLEAN DEFAULT FALSE,
  creator_name VARCHAR(100),
  audit_user VARCHAR(100),
  audit_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500)
);
CREATE INDEX idx_sales_return_apply_status ON sales_return_apply(status);
CREATE INDEX idx_sales_return_apply_customer ON sales_return_apply(customer_code);
CREATE INDEX idx_sales_return_apply_date ON sales_return_apply(bill_date);
CREATE INDEX idx_sales_return_apply_outbound ON sales_return_apply(source_outbound_no);

CREATE TABLE sales_return_apply_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  apply_id VARCHAR(32) NOT NULL,
  return_mode VARCHAR(20) DEFAULT 'BY_BILL',     -- BY_BILL（按单）/ BY_GOODS（按品）
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  spec VARCHAR(200),
  unit_name VARCHAR(50),
  qty DECIMAL(18, 4) DEFAULT 0,
  price DECIMAL(18, 4) DEFAULT 0,
  amount DECIMAL(18, 2) DEFAULT 0,
  batch_no VARCHAR(100),
  production_date DATE,
  tax_rate VARCHAR(20) DEFAULT '13%',
  source_outbound_no VARCHAR(50),                -- 按单退货时的源销售出库单号
  source_detail_id VARCHAR(32),                  -- 按单退货时的源出库明细行
  returnable_qty DECIMAL(18, 4) DEFAULT 0,       -- 按单退货：源单可退数量快照
  cost_price DECIMAL(18, 6) DEFAULT 0,           -- 成本单价快照 6位小数
  available_stock DECIMAL(18, 4) DEFAULT 0,      -- 可用库存快照
  remark VARCHAR(200)
);
CREATE INDEX idx_sales_return_apply_detail_apply ON sales_return_apply_detail(apply_id);
CREATE INDEX idx_sales_return_apply_detail_source ON sales_return_apply_detail(source_detail_id);

-- ========== 2. 销售退货入库单（回库指令） ==========

CREATE TABLE sales_return_inbound (
  inbound_id VARCHAR(32) PRIMARY KEY,
  inbound_no VARCHAR(50) NOT NULL UNIQUE,
  source_apply_no VARCHAR(50) NOT NULL,          -- 来源申请单号（1:1 对应）
  customer_code VARCHAR(50),
  customer_name VARCHAR(200),
  warehouse VARCHAR(100),
  bill_date DATE NOT NULL,
  qty DECIMAL(18, 4) DEFAULT 0,
  amount DECIMAL(18, 2) DEFAULT 0,
  cost_amount DECIMAL(18, 2) DEFAULT 0,
  status VARCHAR(20) DEFAULT 'PENDING',          -- PENDING / APPROVED
  stock_updated BOOLEAN DEFAULT FALSE,
  return_generated BOOLEAN DEFAULT FALSE,
  audit_user VARCHAR(100),
  audit_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500)
);
CREATE UNIQUE INDEX uk_sales_return_inbound_apply ON sales_return_inbound(source_apply_no);
CREATE INDEX idx_sales_return_inbound_status ON sales_return_inbound(status);

CREATE TABLE sales_return_inbound_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  inbound_id VARCHAR(32) NOT NULL,
  return_mode VARCHAR(20) DEFAULT 'BY_BILL',
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  spec VARCHAR(200),
  unit_name VARCHAR(50),
  qty DECIMAL(18, 4) DEFAULT 0,
  price DECIMAL(18, 4) DEFAULT 0,
  amount DECIMAL(18, 2) DEFAULT 0,
  batch_no VARCHAR(100),
  production_date DATE,
  source_outbound_no VARCHAR(50),                -- 按单退货透视源单
  source_detail_id VARCHAR(32),
  apply_detail_id VARCHAR(32),                   -- 关联申请明细行（拆批次出库额度归集）
  cost_price DECIMAL(18, 6) DEFAULT 0,           -- 成本单价 6位小数
  cost_amount DECIMAL(18, 2) DEFAULT 0
);
CREATE INDEX idx_sales_return_inbound_detail_inbound ON sales_return_inbound_detail(inbound_id);
CREATE INDEX idx_sales_return_inbound_detail_apply ON sales_return_inbound_detail(apply_detail_id);

-- ========== 3. 销售退货单（财务结算） ==========

CREATE TABLE sales_return (
  return_id VARCHAR(32) PRIMARY KEY,
  return_no VARCHAR(50) NOT NULL UNIQUE,
  source_apply_no VARCHAR(50),
  source_inbound_no VARCHAR(50) NOT NULL,        -- 来源入库单号（1:1 对应）
  customer_code VARCHAR(50),
  customer_name VARCHAR(200),
  warehouse VARCHAR(100),
  return_date DATE NOT NULL,
  goods_amount DECIMAL(18, 2) DEFAULT 0,         -- 商品金额 = Σ 明细金额（不含税）
  tax_amount DECIMAL(18, 2) DEFAULT 0,           -- 税额 = Σ 行税额
  final_amount DECIMAL(18, 2) DEFAULT 0,         -- 最终金额 = goods_amount + tax_amount
  cost_amount DECIMAL(18, 2) DEFAULT 0,          -- 成本金额
  ar_status VARCHAR(20) DEFAULT '未生成',         -- 未生成 / 已生成（审核后写负向 fin_ar）
  status VARCHAR(20) DEFAULT 'PENDING',           -- PENDING / APPROVED
  creator_name VARCHAR(100),
  audit_user VARCHAR(100),
  audit_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500)
);
CREATE UNIQUE INDEX uk_sales_return_source_inbound ON sales_return(source_inbound_no);
CREATE INDEX idx_sales_return_status ON sales_return(status);
CREATE INDEX idx_sales_return_customer ON sales_return(customer_code);
CREATE INDEX idx_sales_return_date ON sales_return(return_date);

CREATE TABLE sales_return_detail (
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
  cost_price DECIMAL(18, 6) DEFAULT 0,           -- 成本单价 6位小数
  cost_amount DECIMAL(18, 2) DEFAULT 0
);
CREATE INDEX idx_sales_return_detail_return ON sales_return_detail(return_id);
