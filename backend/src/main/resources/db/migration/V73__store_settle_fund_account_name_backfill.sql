-- ------------------------------------------------------------
-- V73 回填门店结算/收款单的资金账户名称
--
-- 背景：
--   TmsStoreSettleController.settle 早期直接把前端入参的 fundAccountName 落库，
--   前端漏传时会写入空串。司机 APP 的收款流水页把 fund_account_name 当收款方式
--   展示，结果界面上出现「收了钱但看不出收在哪个账户」；财务收款单的 fund_account
--   同样受影响。
--
-- Controller 已改为从 base_fund_account 回查（回查不到时兜底为「其他」），
-- 本脚本只负责补齐历史数据。按 fund_account_id 关联主档，取不到就不动。
-- ------------------------------------------------------------

UPDATE tms_store_settlement_account sa
   SET fund_account_name = (SELECT f.fund_account_name FROM base_fund_account f
                             WHERE f.fund_account_id = sa.fund_account_id)
 WHERE (sa.fund_account_name IS NULL OR sa.fund_account_name = '')
   AND EXISTS (SELECT 1 FROM base_fund_account f WHERE f.fund_account_id = sa.fund_account_id);

UPDATE tms_store_settlement_account sa
   SET fund_account_code = (SELECT f.fund_account_code FROM base_fund_account f
                             WHERE f.fund_account_id = sa.fund_account_id)
 WHERE (sa.fund_account_code IS NULL OR sa.fund_account_code = '')
   AND EXISTS (SELECT 1 FROM base_fund_account f WHERE f.fund_account_id = sa.fund_account_id);

-- 收款单明细：按同一笔结算的账户明细金额匹配回填
UPDATE fin_receipt_detail rd
   SET fund_account = COALESCE((SELECT MIN(sa.fund_account_name)
                                  FROM tms_store_settlement_account sa
                                  JOIN tms_store_settlement s ON s.settle_id = sa.settle_id
                                 WHERE s.receipt_id = rd.receipt_id
                                   AND sa.amount = rd.amount
                                   AND sa.fund_account_name <> ''), '其他')
 WHERE (rd.fund_account IS NULL OR rd.fund_account = '');

UPDATE fin_receipt_bill rb
   SET fund_account = COALESCE((SELECT MIN(sa.fund_account_name)
                                  FROM tms_store_settlement_account sa
                                  JOIN tms_store_settlement s ON s.settle_id = sa.settle_id
                                 WHERE s.receipt_id = rb.receipt_id
                                   AND sa.fund_account_name <> ''), '其他')
 WHERE (rb.fund_account IS NULL OR rb.fund_account = '')
   AND rb.business_source = 'DRIVER_SETTLE';
