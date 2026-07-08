-- ============================================
-- V50: 拒收入库单（JSRK）+ 发货单签收拒收 + 司机字段
--
-- 背景：客户签收发货单时可能拒收部分商品，这批货要退回仓库。
--
-- 1) sales_outbound / sales_receipt 增加 driver（司机）
--    —— 出库时可指定实际司机（默认取线路 base_route_line.driver），发货单从出库快照
-- 2) sales_receipt / sales_receipt_detail 增加签收拒收字段
--    —— 签收是「配送确认」维度，与 status（财务审核维度）互相独立
-- 3) inv_reject_inbound / inv_reject_inbound_detail 两张表 —— 拒收入库单
--
-- 决策：
--   · 拒收入库单**只能由签收自动生成，没有 create 端点**，可编辑（数量/批次/生产日期/仓库）
--   · source_receipt_no 唯一约束保证 1 张发货单最多 1 张拒收入库单（签收重复调用幂等）
--   · 明细按 goods_code 一行；批次号/生产日期取原出库该商品**生产日期最新**的那批
--   · cost_price 取**原出库成本单价**（与所取批次同源），审核时用它写入库流水并重算移动加权平均成本
--     —— 区别于其他入库/销售退货入库（那两个取审核时点的当前库存成本均价）
--   · 明细 cost_price 用 DECIMAL(18,6)，对齐 V48 报损单 / V49 其他入库单的明细成本精度
--
-- 注意：H2 不支持 DROP COLUMN IF EXISTS，新增列一律用 ADD COLUMN IF NOT EXISTS
-- ============================================

-- ------------------------------------------------------------
-- 1. 司机字段
-- ------------------------------------------------------------
ALTER TABLE sales_outbound ADD COLUMN IF NOT EXISTS driver VARCHAR(100);
ALTER TABLE sales_receipt  ADD COLUMN IF NOT EXISTS driver VARCHAR(100);

-- ------------------------------------------------------------
-- 2. 发货单签收拒收
-- ------------------------------------------------------------
-- 主单：签收状态独立于 status
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS sign_status VARCHAR(20) DEFAULT '待签收'; -- 待签收/已签收/部分拒收/全部拒收
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS sign_time TIMESTAMP;
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS sign_user VARCHAR(100);
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS reject_qty DECIMAL(18, 4) DEFAULT 0;      -- Σ 明细拒收数量
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS reject_generated BOOLEAN DEFAULT FALSE;   -- 是否已生成拒收入库单（幂等标记）

-- 存量发货单一律置为「待签收」，不回填签收数量（没签过就是没签过）
UPDATE sales_receipt SET sign_status = '待签收' WHERE sign_status IS NULL;
UPDATE sales_receipt SET reject_qty = 0 WHERE reject_qty IS NULL;
UPDATE sales_receipt SET reject_generated = FALSE WHERE reject_generated IS NULL;

CREATE INDEX IF NOT EXISTS idx_sales_receipt_sign_status ON sales_receipt(sign_status);

-- 明细：逐行签收 / 拒收
ALTER TABLE sales_receipt_detail ADD COLUMN IF NOT EXISTS signed_qty DECIMAL(18, 4) DEFAULT 0;
ALTER TABLE sales_receipt_detail ADD COLUMN IF NOT EXISTS reject_qty DECIMAL(18, 4) DEFAULT 0;
ALTER TABLE sales_receipt_detail ADD COLUMN IF NOT EXISTS reject_reason VARCHAR(500);

UPDATE sales_receipt_detail SET signed_qty = 0 WHERE signed_qty IS NULL;
UPDATE sales_receipt_detail SET reject_qty = 0 WHERE reject_qty IS NULL;

-- ------------------------------------------------------------
-- 3. 拒收入库单主表
-- ------------------------------------------------------------
CREATE TABLE inv_reject_inbound (
  reject_inbound_id  VARCHAR(32) PRIMARY KEY,
  inbound_no         VARCHAR(50) NOT NULL UNIQUE,      -- JSRK + yyyyMMdd + 4位流水
  source_receipt_no  VARCHAR(50) NOT NULL,             -- 来源发货单号
  source_outbound_no VARCHAR(50),                      -- 来源出库单号
  source_order_no    VARCHAR(50),                      -- 来源销售订单号
  customer_code      VARCHAR(50),
  customer_name      VARCHAR(200),
  warehouse          VARCHAR(100),                     -- 退回仓库（默认原出库仓库）
  driver             VARCHAR(100),                     -- 司机（从发货单快照）
  route_line         VARCHAR(100),
  salesman           VARCHAR(100),
  bill_date          DATE NOT NULL,
  qty                DECIMAL(18, 4) DEFAULT 0,         -- Σ 明细入库数量
  amount             DECIMAL(18, 2) DEFAULT 0,         -- Σ 明细金额（按销售单价，仅参考）
  cost_amount        DECIMAL(18, 2) DEFAULT 0,         -- Σ 明细成本金额
  status             VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / APPROVED / CANCELLED
  stock_updated      BOOLEAN DEFAULT FALSE,
  creator_name       VARCHAR(100),
  audit_user         VARCHAR(100),
  audit_time         TIMESTAMP,
  create_time        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark             VARCHAR(500)
);
-- 1 张发货单最多 1 张拒收入库单，保证 generateFromReceipt 幂等
CREATE UNIQUE INDEX uk_reject_inbound_receipt ON inv_reject_inbound(source_receipt_no);
CREATE INDEX idx_reject_inbound_no       ON inv_reject_inbound(inbound_no);
CREATE INDEX idx_reject_inbound_status   ON inv_reject_inbound(status);
CREATE INDEX idx_reject_inbound_date     ON inv_reject_inbound(bill_date);
CREATE INDEX idx_reject_inbound_customer ON inv_reject_inbound(customer_code);
CREATE INDEX idx_reject_inbound_outbound ON inv_reject_inbound(source_outbound_no);

-- ------------------------------------------------------------
-- 4. 拒收入库单明细
-- ------------------------------------------------------------
CREATE TABLE inv_reject_inbound_detail (
  detail_id         VARCHAR(32) PRIMARY KEY,
  reject_inbound_id VARCHAR(32) NOT NULL,
  source_detail_id  VARCHAR(32),                       -- 来源发货单明细行 id
  goods_code        VARCHAR(50),
  goods_name        VARCHAR(200),
  spec              VARCHAR(100),
  unit_name         VARCHAR(50),
  warehouse         VARCHAR(100),
  reject_qty        DECIMAL(18, 4) DEFAULT 0,          -- 签收拒收数量（来源，只读参考）
  qty               DECIMAL(18, 4) DEFAULT 0,          -- 本次入库数量（可编辑，默认 = reject_qty）
  batch_no          VARCHAR(100),                      -- 原出库批次号
  production_date   DATE,                              -- 原出库生产日期（多批取最新）
  price             DECIMAL(18, 4) DEFAULT 0,          -- 销售单价（透传）
  amount            DECIMAL(18, 2) DEFAULT 0,
  cost_price        DECIMAL(18, 6) DEFAULT 0,          -- 原出库成本单价
  cost_amount       DECIMAL(18, 2) DEFAULT 0,
  reject_reason     VARCHAR(500),
  remark            VARCHAR(500)
);
CREATE INDEX idx_reject_inbound_detail_head  ON inv_reject_inbound_detail(reject_inbound_id);
CREATE INDEX idx_reject_inbound_detail_goods ON inv_reject_inbound_detail(goods_code);
