-- PRD-36 M2 验证库清理脚本（8082 verify-prd36-m1，停后端后用 H2 RunScript 执行）
-- 目的：把 M2 E2E 产生的单据/流水/真值全部清掉，AP-M2-* 夹具复位为全未付，可干净重跑 prd36-m2-api-e2e.mjs。
-- 不动：M1 夹具（AP-TEST-*）、M2 夹具（pur_receipt CGSH-M2-* / fin_ap AP-M2-* 真值行 / S003）、
--       由 repair 生成的 AP 形成流水（重跑 repair 幂等）。
-- 识别口径：M2 后台付款单 summary LIKE 'M2%'、锚点单 business_source='SUPPLIER_STATEMENT'、
--          FX 单号前缀（验证库内 FX 仅 M2 代码产生）、对账单 remark='M2对账单'、V129 三个新 GL 事件码。

-- 0. 总账凭证/事件（先删分录再删凭证头）
DELETE FROM fin_voucher_entry WHERE voucher_id IN (
    SELECT id FROM fin_voucher WHERE source_bill_no LIKE 'FX%');
DELETE FROM fin_voucher WHERE source_bill_no LIKE 'FX%';
DELETE FROM fin_gl_event WHERE event_code IN ('PREPAY_PAYMENT', 'PREPAY_REFUND', 'PREPAY_WRITE_OFF');

-- 注意：M1 夹具核销行 receipt_no='FK-TEST-1'（prd36-m1-fixtures.sql），所有 FK% 兜底谓词
--       必须豁免 'FK-TEST-%'，否则 repair 重算 AP-TEST-1 会丢失 300 已核销真值。
-- 1. 核销真值：FX 核销行 + M2 锚点付款单的现金结算行（须在付款单删除前执行子查询）；
--    FK 数字单号兜底早期手工调试付款单残留
DELETE FROM fin_reconcile_record
 WHERE receipt_no LIKE 'FX%'
    OR (receipt_no LIKE 'FK%' AND receipt_no NOT LIKE 'FK-TEST-%')
    OR receipt_no IN (SELECT payment_no FROM fin_payment_bill
                      WHERE business_source = 'SUPPLIER_STATEMENT' OR summary LIKE 'M2%');

-- 2. 供应商账户流水：FX 双边流水/红字行（按 writeoff_no）+ PP/APR 等付款单相关行（按 payment_no）
--    FK 数字单号兜底：验证库内系统生成的付款单仅 M2 验证产生，含早期手工调试未打 M2 标记的残留行
--    （父付款单已被物理删除，子查询匹配不到）。
DELETE FROM fin_supplier_account_flow
 WHERE writeoff_no LIKE 'FX%'
    OR (payment_no LIKE 'FK%' AND payment_no NOT LIKE 'FK-TEST-%')
    OR payment_no IN (SELECT payment_no FROM fin_payment_bill
                      WHERE business_source = 'SUPPLIER_STATEMENT' OR summary LIKE 'M2%');

-- 3. 资金流水（OUT/IN，按来源付款单号；反审核行来源号带「(取消审核)」后缀，等值匹配不到，按 FK 前缀兜底）
DELETE FROM fin_fund_ledger
 WHERE (source_bill LIKE 'FK%' AND source_bill NOT LIKE 'FK-TEST-%')
    OR source_bill IN (SELECT payment_no FROM fin_payment_bill
                       WHERE business_source = 'SUPPLIER_STATEMENT' OR summary LIKE 'M2%');

-- 3b. 供应商往来台账（付款/核销写过的 SUPPLIER 行，FX/FK 均为 M2 域；M3 域 DX% 由 m3-reset 清）
DELETE FROM fin_counterparty_ledger
 WHERE counterparty_type = 'SUPPLIER'
   AND ((source_bill_no LIKE 'FK%' AND source_bill_no NOT LIKE 'FK-TEST-%')
        OR source_bill_no LIKE 'FX%');

-- 4. 付款单明细/头（验证库内 FK 数字前缀付款单全部为 M2 验证产生，含未打 M2 标记的早期手工调试单；
--    FK-TEST-* 是 M1 夹具的逻辑单号，在 fin_payment_bill 中无实体行，豁免仅为口径一致）
DELETE FROM fin_payment_detail WHERE payment_id IN (
    SELECT payment_id FROM fin_payment_bill
     WHERE payment_no LIKE 'FK%' AND payment_no NOT LIKE 'FK-TEST-%');
DELETE FROM fin_payment_bill
 WHERE payment_no LIKE 'FK%' AND payment_no NOT LIKE 'FK-TEST-%';

-- 5. 预付核销单（验证库内全部 FX 均为 M2 产生）
DELETE FROM fin_prepay_writeoff_detail WHERE writeoff_id IN (
    SELECT writeoff_id FROM fin_prepay_writeoff);
DELETE FROM fin_prepay_writeoff;

-- 6. M2 供应商对账单及明细
DELETE FROM fin_supplier_statement_detail WHERE statement_id IN (
    SELECT statement_id FROM fin_supplier_statement WHERE remark = 'M2对账单');
DELETE FROM fin_supplier_statement WHERE remark = 'M2对账单';

-- 7. AP-M2 夹具真值复位：已付清零、未付恢复、状态未核销
UPDATE fin_ap SET paid_amount = 0, unpaid_amount = ap_amount, status = 'UNVERIFIED'
 WHERE ap_no LIKE 'AP-M2-%';

-- 账户余额缓存不直改：E2E 开头会对 S001/S003 调 /finance/supplier-account/repair 重算。
SELECT 'RESET_OK';
