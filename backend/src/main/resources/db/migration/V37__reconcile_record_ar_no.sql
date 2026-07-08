-- V37: 核销流水表增加应收单号与业务来源单号
ALTER TABLE fin_reconcile_record ADD COLUMN IF NOT EXISTS ar_no VARCHAR(50);
ALTER TABLE fin_reconcile_record ADD COLUMN IF NOT EXISTS source_bill VARCHAR(50);
