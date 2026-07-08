-- ============================================
-- V9: 客户/供应商加账期设置字段
-- 按 docs/账期管理-产品说明.md V1.4 规范落地
-- ============================================

-- 客户表 base_customer
ALTER TABLE base_customer ADD COLUMN settlement_type VARCHAR(20) DEFAULT 'TERM';         -- PREPAY 预付 / COD 货到付款 / TERM 账期
ALTER TABLE base_customer ADD COLUMN term_type VARCHAR(20);                              -- FIXED / WEEKLY / SEMI_MONTH / MONTHLY
ALTER TABLE base_customer ADD COLUMN term_days INT DEFAULT 0;                            -- 账期天数（0-365）
ALTER TABLE base_customer ADD COLUMN payment_mode VARCHAR(4);                            -- A (截账后N天) / B (截账后N月第M天)，仅月结用
ALTER TABLE base_customer ADD COLUMN term_months INT DEFAULT 0;                          -- 账期月数（月结B用）
-- cutoff_day 已存在（VARCHAR(30)），语义扩展为月结截账日 1-31
-- payment_day 已存在（VARCHAR(30)），语义扩展为月结B付款日 1-31

-- 供应商表 base_supplier
ALTER TABLE base_supplier ADD COLUMN settlement_type VARCHAR(20) DEFAULT 'TERM';
ALTER TABLE base_supplier ADD COLUMN term_type VARCHAR(20);
ALTER TABLE base_supplier ADD COLUMN term_days INT DEFAULT 0;
ALTER TABLE base_supplier ADD COLUMN cutoff_day VARCHAR(30);
ALTER TABLE base_supplier ADD COLUMN payment_mode VARCHAR(4);
ALTER TABLE base_supplier ADD COLUMN term_months INT DEFAULT 0;
ALTER TABLE base_supplier ADD COLUMN payment_day VARCHAR(30);
