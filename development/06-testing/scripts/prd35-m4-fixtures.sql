-- PRD-35 M4 验证夹具（仅用于隔离库 verify-m4，切勿对生产/用户库执行）
-- 占位符 __FUND__ 由 prd35-m4-api-e2e.mjs 套用前替换为实际正常状态资金账户名称。
--
-- 场景设计（全部客户由 E2E 脚本经 /base/customer/create 建档）：
--   交账审核 AC-13（三张交账单，各自一张门店结算 + 一张 PENDING 司机结算收款单）：
--     JZ-M4-1 / MJ-M4-S1 / SK-M4-S1：应收 AR-M4-A1 未收200，实缴300 → 溢收100（参数Y，自动转预收）
--     JZ-M4-2 / MJ-M4-S2 / SK-M4-S2：应收 AR-M4-B1 未收 50，实缴 90 → 溢收 40（GL已启用，带GL事件）
--     JZ-M4-3 / MJ-M4-S3 / SK-M4-S3：应收 AR-M4-C1 未收 30，实缴 80 → 溢收 50（参数N，仅留日志）
--   期初预收 AC-14：
--     AR-M4-OP1 客户 M4ADV1 未收100，供期初预收建账后做 XH 核销/反建账守卫验证
--
-- 幂等：先按 M4 前缀清理本夹具产生的一切单据/流水/缓存，可重复执行。

-- ---------- 清理（顺序无外键依赖，保持与建数相反） ----------
DELETE FROM fin_voucher_entry WHERE voucher_id IN (
  SELECT voucher_id FROM fin_voucher WHERE source_bill_no LIKE 'SK-M4-%');
DELETE FROM fin_voucher WHERE source_bill_no LIKE 'SK-M4-%';
DELETE FROM fin_gl_event WHERE source_bill_no LIKE 'SK-M4-%';
-- 上一轮自动溢收预收单用的是真实 SK 序号（SK2026xxxx），按 related_bill_no 反查清理（必须先于 receipt_bill 删除）
DELETE FROM fin_voucher_entry WHERE voucher_id IN (
  SELECT id FROM fin_voucher WHERE source_bill_no IN (
    SELECT receipt_no FROM fin_receipt_bill
      WHERE business_source='DRIVER_OVERPAY_ADV' AND related_bill_no LIKE 'MJ-M4-%'));
DELETE FROM fin_voucher WHERE source_bill_no IN (
  SELECT receipt_no FROM fin_receipt_bill
    WHERE business_source='DRIVER_OVERPAY_ADV' AND related_bill_no LIKE 'MJ-M4-%');
DELETE FROM fin_gl_event WHERE source_bill_no IN (
  SELECT receipt_no FROM fin_receipt_bill
    WHERE business_source='DRIVER_OVERPAY_ADV' AND related_bill_no LIKE 'MJ-M4-%');
DELETE FROM fin_counterparty_ledger WHERE source_bill_no IN (
  SELECT receipt_no FROM fin_receipt_bill
    WHERE business_source='DRIVER_OVERPAY_ADV' AND related_bill_no LIKE 'MJ-M4-%');
DELETE FROM fin_receipt_detail WHERE receipt_id IN (
  SELECT receipt_id FROM fin_receipt_bill
    WHERE business_source='DRIVER_OVERPAY_ADV' AND related_bill_no LIKE 'MJ-M4-%');
DELETE FROM fin_receipt_bill WHERE business_source='DRIVER_OVERPAY_ADV' AND related_bill_no LIKE 'MJ-M4-%';
DELETE FROM fin_reconcile_record WHERE receipt_no LIKE 'SK-M4-%';
DELETE FROM fin_fund_ledger WHERE source_bill LIKE 'SK-M4-%';
DELETE FROM fin_counterparty_ledger WHERE source_bill_no LIKE 'SK-M4-%';
DELETE FROM fin_receipt_detail WHERE receipt_id IN ('SKID-M4-S1','SKID-M4-S2','SKID-M4-S3');
DELETE FROM fin_receipt_bill WHERE receipt_no LIKE 'SK-M4-%';
DELETE FROM tms_store_settlement_detail WHERE settle_id LIKE 'MJID-M4-%';
DELETE FROM tms_store_settlement_account WHERE settle_id LIKE 'MJID-M4-%';
DELETE FROM tms_store_settlement WHERE settle_id LIKE 'MJID-M4-%';
DELETE FROM tms_settlement WHERE settlement_id LIKE 'JZID-M4-%';
DELETE FROM fin_customer_account_flow WHERE customer_code IN ('M4CUS_A','M4CUS_B','M4CUS_C','M4ADV1','M4ADV2');
DELETE FROM fin_customer_account WHERE customer_code IN ('M4CUS_A','M4CUS_B','M4CUS_C','M4ADV1','M4ADV2');
DELETE FROM fin_ar WHERE ar_no LIKE 'AR-M4-%';
-- 期初预收暂存/建账状态重置（支持脚本中断后重跑）：清暂存、清 biz_init_post 的 ADV 批号、
-- 清建账参数；同时清掉上轮残留的 XH 核销单（守卫验证产生，反审核后为 PENDING/CANCELLED）
DELETE FROM fin_advance_writeoff_detail WHERE writeoff_id IN (
  SELECT writeoff_id FROM fin_advance_writeoff WHERE customer_code IN ('M4ADV1','M4ADV2'));
DELETE FROM fin_advance_writeoff WHERE customer_code IN ('M4ADV1','M4ADV2');
DELETE FROM fin_adv_init;
DELETE FROM biz_init_post WHERE init_type='ADV';
UPDATE sys_param_runtime SET param_value='N' WHERE param_key='biz.init.adv_posted';
UPDATE sys_param_runtime SET param_value=NULL WHERE param_key='biz.init.adv_post_no';
-- 一键引入只写 GL 期初余额表，验证后清掉本批 2203 行，恢复试算 0 平衡以便启用总账
DELETE FROM fin_init_balance WHERE account_code IN ('1122','2203','2202','1405');
-- 溢收参数恢复默认 Y
UPDATE sys_param_runtime SET param_value='Y' WHERE param_key='tms.settle.overpay-to-advance';
-- 总账启用状态复位（verify-m4 专用库：上轮跑到 AC-13 即已启用总账，重跑需回到未启用让 AC-14-6 重新走启用链）
UPDATE sys_param_runtime SET param_value='N' WHERE param_key IN ('fin.gl.initialized','fin.gl.enabled');
-- 日结定版表整表清空（verify-m4 仅本夹具使用；不清会导致期初建账被「已日结」守卫拦截）
DELETE FROM biz_close_goods_daily;
DELETE FROM biz_close_fund_daily;
DELETE FROM biz_close_ar_daily;
DELETE FROM biz_close_ap_daily;
DELETE FROM biz_day_close_log;
DELETE FROM biz_day_close;
-- 资金账户→总账科目的测试映射复位（E2E 在总账启用后显式配置 现金→1001）
UPDATE base_fund_account SET gl_account_code=NULL WHERE fund_account_code='01';

-- ---------- 应收真值 ----------
INSERT INTO fin_ar(ar_id, ar_no, source_bill, customer, salesman, ar_amount,
  received_amount, unreceived_amount, due_date, overdue_days, invoice_status,
  status, reconcile_status, created_at) VALUES
('ARID-M4-A1','AR-M4-A1','SR-M4-A1','M4CUS_A','冒烟员',200.00,0,200.00,'2026-09-10',0,'未开票','UNVERIFIED','未对账',CURRENT_TIMESTAMP),
('ARID-M4-B1','AR-M4-B1','SR-M4-B1','M4CUS_B','冒烟员', 50.00,0, 50.00,'2026-09-10',0,'未开票','UNVERIFIED','未对账',CURRENT_TIMESTAMP),
('ARID-M4-C1','AR-M4-C1','SR-M4-C1','M4CUS_C','冒烟员', 30.00,0, 30.00,'2026-09-10',0,'未开票','UNVERIFIED','未对账',CURRENT_TIMESTAMP),
('ARID-M4-OP1','AR-M4-OP1','SR-M4-OP1','M4ADV1','冒烟员',100.00,0,100.00,'2026-09-10',0,'未开票','UNVERIFIED','未对账',CURRENT_TIMESTAMP);

-- ---------- 场景1：交账单 JZ-M4-1 + 门店结算 MJ-M4-S1 + 收款单 SK-M4-S1（300 / 应收200） ----------
INSERT INTO tms_settlement(settlement_id, settlement_no, driver_id, driver_name, route_line,
  settle_date, total_stores, signed_stores, total_amount, cash_amount, online_amount,
  return_amount, return_qty, submit_amount, actual_submit, diff_amount, status, submitted_at)
VALUES ('JZID-M4-1','JZ-M4-1','DRV-M4-01','M4DRIVER','M4线路',CURRENT_DATE,1,1,200,300,0,0,0,300,300,0,'PENDING',CURRENT_TIMESTAMP);

INSERT INTO tms_store_settlement(settle_id, settle_no, settlement_id, driver_id, driver_name,
  customer_code, customer_name, bill_count, receipt_amount, return_amount, settle_amount,
  received_amount, credit_amount, receipt_no, receipt_id, settle_status, fin_status,
  signer, settle_time)
VALUES ('MJID-M4-S1','MJ-M4-S1','JZID-M4-1','DRV-M4-01','M4DRIVER',
  'M4CUS_A','M4CUS_A',1,200,0,200,300,0,'SK-M4-S1','SKID-M4-S1','SETTLED','PENDING',
  'M4SIGNER',CURRENT_TIMESTAMP);

INSERT INTO tms_store_settlement_detail(id, settle_id, bill_type, source_bill_no, sign_type,
  signed_qty, reject_qty, amount, ar_no)
VALUES ('MJD-M4-S1','MJID-M4-S1','RECEIPT','SR-M4-A1','NORMAL',10,0,200,'AR-M4-A1');

INSERT INTO tms_store_settlement_account(id, settle_id, fund_account_code, fund_account_name, amount, sort_order)
VALUES ('MJA-M4-S1','MJID-M4-S1','__FUND_CODE__','__FUND__',300,1);

INSERT INTO fin_receipt_bill(receipt_id, receipt_no, receipt_date, status, counterparty_type,
  counterparty_code, counterparty_name, handler, related_bill_no, summary, business_source,
  receipt_type, total_amount, verified_amount, object_name, fund_account, amount,
  creator_name, create_time)
VALUES ('SKID-M4-S1','SK-M4-S1',CURRENT_DATE,'PENDING','CUSTOMER',
  'M4CUS_A','M4CUS_A','M4DRIVER','MJ-M4-S1','司机现场收款 MJ-M4-S1','DRIVER_SETTLE',
  'SETTLE',300,0,'M4CUS_A','__FUND__',300,'M4DRIVER',CURRENT_TIMESTAMP);

INSERT INTO fin_receipt_detail(detail_id, receipt_id, fund_account, amount, remark, sort_order)
VALUES ('SKD-M4-S1','SKID-M4-S1','__FUND__',300,'司机结算 MJ-M4-S1',1);

-- ---------- 场景2：JZ-M4-2 / MJ-M4-S2 / SK-M4-S2（90 / 应收50，GL 事件验证） ----------
INSERT INTO tms_settlement(settlement_id, settlement_no, driver_id, driver_name, route_line,
  settle_date, total_stores, signed_stores, total_amount, cash_amount, online_amount,
  return_amount, return_qty, submit_amount, actual_submit, diff_amount, status, submitted_at)
VALUES ('JZID-M4-2','JZ-M4-2','DRV-M4-01','M4DRIVER','M4线路',CURRENT_DATE,1,1,50,90,0,0,0,90,90,0,'PENDING',CURRENT_TIMESTAMP);

INSERT INTO tms_store_settlement(settle_id, settle_no, settlement_id, driver_id, driver_name,
  customer_code, customer_name, bill_count, receipt_amount, return_amount, settle_amount,
  received_amount, credit_amount, receipt_no, receipt_id, settle_status, fin_status,
  signer, settle_time)
VALUES ('MJID-M4-S2','MJ-M4-S2','JZID-M4-2','DRV-M4-01','M4DRIVER',
  'M4CUS_B','M4CUS_B',1,50,0,50,90,0,'SK-M4-S2','SKID-M4-S2','SETTLED','PENDING',
  'M4SIGNER',CURRENT_TIMESTAMP);

INSERT INTO tms_store_settlement_detail(id, settle_id, bill_type, source_bill_no, sign_type,
  signed_qty, reject_qty, amount, ar_no)
VALUES ('MJD-M4-S2','MJID-M4-S2','RECEIPT','SR-M4-B1','NORMAL',5,0,50,'AR-M4-B1');

INSERT INTO tms_store_settlement_account(id, settle_id, fund_account_code, fund_account_name, amount, sort_order)
VALUES ('MJA-M4-S2','MJID-M4-S2','__FUND_CODE__','__FUND__',90,1);

INSERT INTO fin_receipt_bill(receipt_id, receipt_no, receipt_date, status, counterparty_type,
  counterparty_code, counterparty_name, handler, related_bill_no, summary, business_source,
  receipt_type, total_amount, verified_amount, object_name, fund_account, amount,
  creator_name, create_time)
VALUES ('SKID-M4-S2','SK-M4-S2',CURRENT_DATE,'PENDING','CUSTOMER',
  'M4CUS_B','M4CUS_B','M4DRIVER','MJ-M4-S2','司机现场收款 MJ-M4-S2','DRIVER_SETTLE',
  'SETTLE',90,0,'M4CUS_B','__FUND__',90,'M4DRIVER',CURRENT_TIMESTAMP);

INSERT INTO fin_receipt_detail(detail_id, receipt_id, fund_account, amount, remark, sort_order)
VALUES ('SKD-M4-S2','SKID-M4-S2','__FUND__',90,'司机结算 MJ-M4-S2',1);

-- ---------- 场景3：JZ-M4-3 / MJ-M4-S3 / SK-M4-S3（80 / 应收30，参数关仅日志） ----------
INSERT INTO tms_settlement(settlement_id, settlement_no, driver_id, driver_name, route_line,
  settle_date, total_stores, signed_stores, total_amount, cash_amount, online_amount,
  return_amount, return_qty, submit_amount, actual_submit, diff_amount, status, submitted_at)
VALUES ('JZID-M4-3','JZ-M4-3','DRV-M4-01','M4DRIVER','M4线路',CURRENT_DATE,1,1,30,80,0,0,0,80,80,0,'PENDING',CURRENT_TIMESTAMP);

INSERT INTO tms_store_settlement(settle_id, settle_no, settlement_id, driver_id, driver_name,
  customer_code, customer_name, bill_count, receipt_amount, return_amount, settle_amount,
  received_amount, credit_amount, receipt_no, receipt_id, settle_status, fin_status,
  signer, settle_time)
VALUES ('MJID-M4-S3','MJ-M4-S3','JZID-M4-3','DRV-M4-01','M4DRIVER',
  'M4CUS_C','M4CUS_C',1,30,0,30,80,0,'SK-M4-S3','SKID-M4-S3','SETTLED','PENDING',
  'M4SIGNER',CURRENT_TIMESTAMP);

INSERT INTO tms_store_settlement_detail(id, settle_id, bill_type, source_bill_no, sign_type,
  signed_qty, reject_qty, amount, ar_no)
VALUES ('MJD-M4-S3','MJID-M4-S3','RECEIPT','SR-M4-C1','NORMAL',3,0,30,'AR-M4-C1');

INSERT INTO tms_store_settlement_account(id, settle_id, fund_account_code, fund_account_name, amount, sort_order)
VALUES ('MJA-M4-S3','MJID-M4-S3','__FUND_CODE__','__FUND__',80,1);

INSERT INTO fin_receipt_bill(receipt_id, receipt_no, receipt_date, status, counterparty_type,
  counterparty_code, counterparty_name, handler, related_bill_no, summary, business_source,
  receipt_type, total_amount, verified_amount, object_name, fund_account, amount,
  creator_name, create_time)
VALUES ('SKID-M4-S3','SK-M4-S3',CURRENT_DATE,'PENDING','CUSTOMER',
  'M4CUS_C','M4CUS_C','M4DRIVER','MJ-M4-S3','司机现场收款 MJ-M4-S3','DRIVER_SETTLE',
  'SETTLE',80,0,'M4CUS_C','__FUND__',80,'M4DRIVER',CURRENT_TIMESTAMP);

INSERT INTO fin_receipt_detail(detail_id, receipt_id, fund_account, amount, remark, sort_order)
VALUES ('SKD-M4-S3','SKID-M4-S3','__FUND__',80,'司机结算 MJ-M4-S3',1);
