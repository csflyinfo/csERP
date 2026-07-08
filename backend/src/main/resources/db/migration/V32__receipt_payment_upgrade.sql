-- ============================================
-- V32: 收款单 / 付款单 模块升级
--
-- 背景：
--   原 fin_receipt_bill / fin_payment_bill 只有 8 列（对象名、资金账户、金额、
--   核销金额、来源单号、备注、状态、创建时间），没有往来单位、日期、经手人、
--   制单审核人、明细行等核心字段。收款核销直接改 fin_ar 不记历史。
--
-- 本次改造：
--   1. fin_receipt_bill / fin_payment_bill 扩展为完整的收款/付款单主表
--   2. 新建收款明细、付款明细、核销流水、往来流水四张支撑表
--   3. 老列 object_name / fund_account / amount 保留不动（兼容），
--      新代码读写新列，老数据不受影响
-- ============================================

-- =====================================================
-- 1. 收款单主表 —— 扩展字段
-- =====================================================
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS receipt_date DATE;
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS counterparty_type VARCHAR(20);
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS counterparty_code VARCHAR(50);
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS counterparty_name VARCHAR(200);
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS handler VARCHAR(100);
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS related_bill_no VARCHAR(50);
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS summary VARCHAR(500);
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS business_source VARCHAR(30) DEFAULT 'BACKOFFICE';
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS total_amount DECIMAL(18,2) DEFAULT 0;
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS creator_name VARCHAR(100);
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS create_time TIMESTAMP;
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS auditor_name VARCHAR(100);
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS audit_time TIMESTAMP;

-- =====================================================
-- 2. 收款明细行
-- =====================================================
CREATE TABLE IF NOT EXISTS fin_receipt_detail (
  detail_id    VARCHAR(32)  PRIMARY KEY,
  receipt_id   VARCHAR(32)  NOT NULL,
  fund_account VARCHAR(100) NOT NULL,
  amount       DECIMAL(18,2) NOT NULL DEFAULT 0,
  remark       VARCHAR(200),
  sort_order   INT DEFAULT 0
);

-- =====================================================
-- 3. 付款单主表 —— 扩展字段（与收款单对称）
-- =====================================================
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS payment_date DATE;
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS counterparty_type VARCHAR(20);
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS counterparty_code VARCHAR(50);
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS counterparty_name VARCHAR(200);
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS handler VARCHAR(100);
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS related_bill_no VARCHAR(50);
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS summary VARCHAR(500);
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS business_source VARCHAR(30) DEFAULT 'BACKOFFICE';
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS total_amount DECIMAL(18,2) DEFAULT 0;
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS creator_name VARCHAR(100);
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS create_time TIMESTAMP;
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS auditor_name VARCHAR(100);
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS audit_time TIMESTAMP;

-- =====================================================
-- 4. 付款明细行
-- =====================================================
CREATE TABLE IF NOT EXISTS fin_payment_detail (
  detail_id    VARCHAR(32)  PRIMARY KEY,
  payment_id   VARCHAR(32)  NOT NULL,
  fund_account VARCHAR(100) NOT NULL,
  amount       DECIMAL(18,2) NOT NULL DEFAULT 0,
  remark       VARCHAR(200),
  sort_order   INT DEFAULT 0
);

-- =====================================================
-- 5. 收款核销流水表
-- =====================================================
CREATE TABLE IF NOT EXISTS fin_reconcile_record (
  record_id          VARCHAR(32)  PRIMARY KEY,
  receipt_no         VARCHAR(50)  NOT NULL,
  receipt_date       DATE,
  business_no        VARCHAR(50)  NOT NULL,
  business_type      VARCHAR(30)  NOT NULL,
  business_date      DATE,
  counterparty_type  VARCHAR(20),
  counterparty_code  VARCHAR(50),
  counterparty_name  VARCHAR(200),
  reconcile_amount   DECIMAL(18,2) NOT NULL DEFAULT 0,
  receipt_remark     VARCHAR(500),
  business_remark    VARCHAR(500),
  created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rr_receipt ON fin_reconcile_record(receipt_no);
CREATE INDEX IF NOT EXISTS idx_rr_business ON fin_reconcile_record(business_no);

-- =====================================================
-- 6. 往来流水表
-- =====================================================
CREATE TABLE IF NOT EXISTS fin_counterparty_ledger (
  ledger_id          VARCHAR(32)  PRIMARY KEY,
  counterparty_type  VARCHAR(20)  NOT NULL,
  counterparty_code  VARCHAR(50)  NOT NULL,
  counterparty_name  VARCHAR(200),
  direction          VARCHAR(10)  NOT NULL,
  amount             DECIMAL(18,2) NOT NULL DEFAULT 0,
  source_bill_no     VARCHAR(50),
  business_type      VARCHAR(30),
  balance_after      DECIMAL(18,2),
  occurred_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark             VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_cpl_counterparty ON fin_counterparty_ledger(counterparty_code);
CREATE INDEX IF NOT EXISTS idx_cpl_occurred ON fin_counterparty_ledger(occurred_at);
