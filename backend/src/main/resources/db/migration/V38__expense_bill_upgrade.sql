-- V38: 费用单模块升级
-- 背景：原 fin_expense_bill 只有 9 列，缺少完整的费用单字段与明细行
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS expense_date DATE;
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS counterparty_type VARCHAR(20);
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS counterparty_code VARCHAR(50);
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS counterparty_name VARCHAR(200);
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS handler VARCHAR(100);
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS department VARCHAR(100);
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS related_bill_no VARCHAR(50);
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS external_voucher_no VARCHAR(50);
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS business_source VARCHAR(30) DEFAULT 'BACKOFFICE';
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS fund_account VARCHAR(100);
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS total_amount DECIMAL(18,2) DEFAULT 0;
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS creator_name VARCHAR(100);
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS create_time TIMESTAMP;
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS auditor_name VARCHAR(100);
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS audit_time TIMESTAMP;

CREATE TABLE IF NOT EXISTS fin_expense_detail (
  detail_id     VARCHAR(32)  PRIMARY KEY,
  expense_id    VARCHAR(32)  NOT NULL,
  expense_type  VARCHAR(100) NOT NULL,
  amount        DECIMAL(18,2) NOT NULL DEFAULT 0,
  remark        VARCHAR(200),
  sort_order    INT DEFAULT 0
);
