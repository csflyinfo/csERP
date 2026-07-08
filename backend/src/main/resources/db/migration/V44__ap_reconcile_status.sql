-- V44: fin_ap 增加对账状态（对称 fin_ar V41）
ALTER TABLE fin_ap ADD COLUMN IF NOT EXISTS reconcile_status VARCHAR(20) DEFAULT '未对账';
