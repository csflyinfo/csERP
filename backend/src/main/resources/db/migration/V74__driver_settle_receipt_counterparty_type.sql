-- ------------------------------------------------------------
-- V74 修正司机现场收款单的往来单位类型
--
-- 背景：
--   TmsStoreSettleController 生成 DRIVER_SETTLE 收款单时，counterparty_type
--   写的是中文「客户」，而财务模块全程使用标准值域 CUSTOMER / SUPPLIER /
--   COUNTERPARTY（前端展示时才映射成中文）。
--
-- 影响：
--   getCounterpartyBalance(cpType, cpCode) 按 (类型, 编码) 联合定位往来余额，
--   类型不一致会被当成另一个往来对象，导致同一客户余额分叉、
--   交账审核后的往来流水接不上历史余额。
--
-- Controller 已改为直接写 CUSTOMER，本脚本回填历史单据。
-- 仅限 DRIVER_SETTLE 来源，避免误改财务手工单。
-- ------------------------------------------------------------

UPDATE fin_receipt_bill
   SET counterparty_type = 'CUSTOMER'
 WHERE business_source = 'DRIVER_SETTLE'
   AND counterparty_type IN ('客户', '');

-- 往来流水同步纠正（这些单尚未审核时不会有流水，仅防御历史手工审核过的数据）
UPDATE fin_counterparty_ledger cl
   SET counterparty_type = 'CUSTOMER'
 WHERE counterparty_type = '客户'
   AND EXISTS (SELECT 1 FROM fin_receipt_bill rb
                WHERE rb.receipt_no = cl.source_bill_no
                  AND rb.business_source = 'DRIVER_SETTLE');
