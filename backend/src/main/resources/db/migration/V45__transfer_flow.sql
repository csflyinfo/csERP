-- V45: 调拨流程 — 调拨申请单 / 调拨出库单 / 调拨入库单（含差异退回）
-- 流程：调拨申请单(审核) → 调拨出库单(审核扣转出仓库存) → 自动生成调拨入库单(审核入转入仓)
--       入库数量 < 调出数量时 → 自动生成「调拨入库差异退回」单（商品退回调出仓，明细只读）

-- 调拨申请单（DBSQ）
CREATE TABLE transfer_apply (
  apply_id        VARCHAR(32)  PRIMARY KEY,
  apply_no        VARCHAR(50)  NOT NULL UNIQUE,
  source_warehouse VARCHAR(100) NOT NULL,          -- 转出仓
  target_warehouse VARCHAR(100) NOT NULL,          -- 转入仓
  apply_date      DATE         NOT NULL,
  qty             DECIMAL(18,2) DEFAULT 0,         -- 申请总数量
  status          VARCHAR(20)  DEFAULT 'PENDING',  -- PENDING / APPROVED / CANCELLED
  remark          VARCHAR(500),
  creator_name    VARCHAR(100),
  create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  auditor_name    VARCHAR(100),
  audit_time      TIMESTAMP
);

CREATE TABLE transfer_apply_detail (
  detail_id   VARCHAR(32) PRIMARY KEY,
  apply_id    VARCHAR(32) NOT NULL,
  goods_code  VARCHAR(50),
  goods_name  VARCHAR(200),
  unit_name   VARCHAR(50),
  qty         DECIMAL(18,2) DEFAULT 0,
  remark      VARCHAR(200),
  sort_order  INT DEFAULT 0
);

-- 调拨出库单（DBCK）：对已审核调拨申请单出仓，支持一商品多批次
CREATE TABLE transfer_outbound (
  outbound_id       VARCHAR(32)  PRIMARY KEY,
  outbound_no       VARCHAR(50)  NOT NULL UNIQUE,
  source_apply_no   VARCHAR(50),                   -- 来源调拨申请单
  source_warehouse  VARCHAR(100) NOT NULL,         -- 转出仓
  target_warehouse  VARCHAR(100) NOT NULL,         -- 转入仓
  bill_date         DATE         NOT NULL,
  qty               DECIMAL(18,2) DEFAULT 0,
  cost_amount       DECIMAL(18,2) DEFAULT 0,       -- 出库成本合计
  status            VARCHAR(20)  DEFAULT 'PENDING',
  stock_updated     BOOLEAN      DEFAULT FALSE,
  inbound_generated BOOLEAN      DEFAULT FALSE,    -- 是否已自动生成调拨入库单
  remark            VARCHAR(500),
  creator_name      VARCHAR(100),
  create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  auditor_name      VARCHAR(100),
  audit_time        TIMESTAMP
);

CREATE TABLE transfer_outbound_detail (
  detail_id   VARCHAR(32) PRIMARY KEY,
  outbound_id VARCHAR(32) NOT NULL,
  goods_code  VARCHAR(50),
  goods_name  VARCHAR(200),
  unit_name   VARCHAR(50),
  qty         DECIMAL(18,2) DEFAULT 0,
  batch_no    VARCHAR(100),                        -- 出库批次（一商品多批次=多行）
  cost_price  DECIMAL(18,6) DEFAULT 0,             -- 出库审核时的成本（6 位小数）
  cost_amount DECIMAL(18,2) DEFAULT 0,
  sort_order  INT DEFAULT 0
);

-- 调拨入库单（DBRK）：调拨出库单审核后自动生成，只可修改数量；成本为调出时成本
CREATE TABLE transfer_inbound (
  inbound_id        VARCHAR(32)  PRIMARY KEY,
  inbound_no        VARCHAR(50)  NOT NULL UNIQUE,
  source_outbound_no VARCHAR(50),                  -- 来源调拨出库单
  source_apply_no   VARCHAR(50),
  source_warehouse  VARCHAR(100) NOT NULL,         -- 调出仓
  target_warehouse  VARCHAR(100) NOT NULL,         -- 调入仓（差异退回单=调出仓）
  inbound_type      VARCHAR(30)  DEFAULT '正常',   -- 正常 / 调拨入库差异退回
  bill_date         DATE         NOT NULL,
  qty               DECIMAL(18,2) DEFAULT 0,
  cost_amount       DECIMAL(18,2) DEFAULT 0,
  status            VARCHAR(20)  DEFAULT 'PENDING',
  stock_updated     BOOLEAN      DEFAULT FALSE,
  remark            VARCHAR(500),
  creator_name      VARCHAR(100),
  create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  auditor_name      VARCHAR(100),
  audit_time        TIMESTAMP
);

CREATE TABLE transfer_inbound_detail (
  detail_id   VARCHAR(32) PRIMARY KEY,
  inbound_id  VARCHAR(32) NOT NULL,
  goods_code  VARCHAR(50),
  goods_name  VARCHAR(200),
  unit_name   VARCHAR(50),
  out_qty     DECIMAL(18,2) DEFAULT 0,             -- 调出数量（只读）
  qty         DECIMAL(18,2) DEFAULT 0,             -- 实际入库数量（正常单可改；差异退回单只读）
  batch_no    VARCHAR(100),
  cost_price  DECIMAL(18,6) DEFAULT 0,             -- 调出时成本（固定）
  cost_amount DECIMAL(18,2) DEFAULT 0,
  sort_order  INT DEFAULT 0
);

CREATE INDEX idx_transfer_outbound_apply ON transfer_outbound(source_apply_no);
CREATE INDEX idx_transfer_inbound_outbound ON transfer_inbound(source_outbound_no);
CREATE INDEX idx_transfer_inbound_type ON transfer_inbound(inbound_type);
