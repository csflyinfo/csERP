-- V40: 费用单明细税率/税额/不含税金额 + 主表税额汇总
ALTER TABLE fin_expense_detail ADD COLUMN IF NOT EXISTS tax_rate DECIMAL(6,2) DEFAULT 13;
ALTER TABLE fin_expense_detail ADD COLUMN IF NOT EXISTS tax_amount DECIMAL(18,2) DEFAULT 0;
ALTER TABLE fin_expense_detail ADD COLUMN IF NOT EXISTS excluding_tax_amount DECIMAL(18,2) DEFAULT 0;
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS total_tax_amount DECIMAL(18,2) DEFAULT 0;
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS total_excluding_tax_amount DECIMAL(18,2) DEFAULT 0;
