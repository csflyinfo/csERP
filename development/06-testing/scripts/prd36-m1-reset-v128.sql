-- PRD-36 M1 验证辅助：回退 V128（仅用于隔离验证库重跑迁移，严禁在业务库执行）
DELETE FROM flyway_schema_history WHERE version = '128';
DROP TABLE IF EXISTS fin_supplier_account_flow;
DROP TABLE IF EXISTS fin_supplier_account;
DROP TABLE IF EXISTS fin_factory_expense_detail;
DROP TABLE IF EXISTS fin_factory_expense;
DROP TABLE IF EXISTS fin_factory_settle_fund;
DROP TABLE IF EXISTS fin_factory_settle_ap;
DROP TABLE IF EXISTS fin_factory_settle_detail;
DROP TABLE IF EXISTS fin_factory_settle;
DROP TABLE IF EXISTS fin_prepay_writeoff_detail;
DROP TABLE IF EXISTS fin_prepay_writeoff;
ALTER TABLE fin_payment_bill DROP COLUMN payment_type;
ALTER TABLE fin_ap_init DROP COLUMN prepay_amount;
ALTER TABLE fin_ap_init DROP COLUMN generated_prepay_flow_id;
ALTER TABLE base_expense_type DROP COLUMN factory_gl_credit_subject_code;
ALTER TABLE fin_expense_bill DROP COLUMN factory_expense_no;
ALTER TABLE biz_close_ap_daily DROP COLUMN prepay_account_balance;
ALTER TABLE biz_close_ap_daily DROP COLUMN expense_account_balance;
