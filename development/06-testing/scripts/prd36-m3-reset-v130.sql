-- PRD-36 M3 验证库清理脚本（8082 verify-prd36-m1，停后端后用 H2 RunScript 执行）
-- 目的：把 M3 E2E 产生的 JF/DX 单据、流水、真值、凭证事件全部清掉，FE-M3-* 夹具解除占用，
--       AP-M2-* 复位为全未付，可干净重跑 prd36-m3-api-e2e.mjs。
-- 识别口径：验证库内 JF/DX 单号仅 M3 代码产生（JF*/DX*）；
--          供应商账户流水按 M3 biz_key 前缀精确删除（FEX/FEXREV/DXC/DXCREV/DXO/DXOREV/
--          DXAP/DXAPREV/DXOT/DXOTREV/DXP/DXPREV），不碰 M2 的 FX* 键；
--          核销记录按 business_type=FACTORY_EXPENSE_OFFSET；
--          资金/往来台账按来源单号 DX%（含 DX...(取消审核) 红字行）。
-- 不动：M1/M2 夹具（AP-TEST-*、AP-M2-*、CGSH-M2-*、S001/S003）、FE-M3-* 夹具头/明细本身。
-- 建议执行顺序：prd36-m2-reset-v129.sql → prd36-m3-reset-v130.sql → prd36-m3-fixtures.sql

-- 0. 总账凭证/事件（先删分录再删凭证头）
DELETE FROM fin_voucher_entry WHERE voucher_id IN (
    SELECT id FROM fin_voucher WHERE source_bill_no LIKE 'JF%' OR source_bill_no LIKE 'DX%');
DELETE FROM fin_voucher WHERE source_bill_no LIKE 'JF%' OR source_bill_no LIKE 'DX%';
DELETE FROM fin_gl_event WHERE event_code IN
    ('FACTORY_EXPENSE', 'FACTORY_SETTLE_CASH', 'FACTORY_SETTLE_OFFSET', 'FACTORY_SETTLE_OTHER');

-- 1. 核销真值：厂家费用账扣
DELETE FROM fin_reconcile_record WHERE business_type = 'FACTORY_EXPENSE_OFFSET';

-- 2. 供应商账户流水：M3 全部 biz_key 前缀
DELETE FROM fin_supplier_account_flow WHERE biz_key LIKE 'FEX:%' OR biz_key LIKE 'FEXREV:%'
    OR biz_key LIKE 'DXC:%' OR biz_key LIKE 'DXCREV:%'
    OR biz_key LIKE 'DXO:%' OR biz_key LIKE 'DXOREV:%'
    OR biz_key LIKE 'DXAP:%' OR biz_key LIKE 'DXAPREV:%'
    OR biz_key LIKE 'DXOT:%' OR biz_key LIKE 'DXOTREV:%'
    OR biz_key LIKE 'DXP:%' OR biz_key LIKE 'DXPREV:%';

-- 3. 资金流水（IN 与取消审核 OUT 红字行来源单均以 DX 开头）
DELETE FROM fin_fund_ledger WHERE source_bill LIKE 'DX%';

-- 4. 供应商往来台账
DELETE FROM fin_counterparty_ledger WHERE source_bill_no LIKE 'DX%';

-- 5. 兑现单三明细 + 头
DELETE FROM fin_factory_settle_fund;
DELETE FROM fin_factory_settle_ap;
DELETE FROM fin_factory_settle_detail;
DELETE FROM fin_factory_settle;

-- 6. 厂家费用单明细 + 头（验证库内 JF 全部为 M3 产生）
DELETE FROM fin_factory_expense_detail;
DELETE FROM fin_factory_expense;

-- 7. FE 夹具解除 JF 回指（红字 JF 不写回指，正常单反审核失败场景下兜底）
UPDATE fin_expense_bill SET factory_expense_no = NULL WHERE factory_expense_no LIKE 'JF%';

-- 8. AP-M2 夹具真值复位 + 收货单付款状态复位（M3 账扣会把 CGSH-M2-* 刷成部分/完成付款）
UPDATE fin_ap SET paid_amount = 0, unpaid_amount = ap_amount, status = 'UNVERIFIED'
 WHERE ap_no LIKE 'AP-M2-%';
UPDATE pur_receipt SET pay_status = NULL WHERE receipt_no LIKE 'CGSH-M2-%';

-- 9. P0190 补录开关恢复默认关（E2E 中断重跑时的兜底）
UPDATE sys_param_runtime SET param_value = 'N'
 WHERE param_key = 'fin.fexp.opening-backfill.enabled';

-- 10. V130 模板开发期修正：NONE 头行只取事件顶层 vars（金额键 amount_tax_incl），
--     原 amount_expr='amount' 会报「表达式引用了不存在的变量：amount」；
--     EXPENSE_LINE/FUND_LINE 展开行仍用行 vars 的 amount，不在本修正内。
--     根因已在 V130 迁移文件同步修正，此 UPDATE 兜底已执行过旧版 V130 的开发库。
UPDATE fin_voucher_template_line SET amount_expr = 'amount_tax_incl'
 WHERE id IN ('TPL_FACTORY_EXPENSE_L1', 'TPL_FACTORY_SETTLE_CASH_L2',
              'TPL_FACTORY_SETTLE_OFFSET_L1', 'TPL_FACTORY_SETTLE_OFFSET_L2',
              'TPL_FACTORY_SETTLE_OTHER_L1', 'TPL_FACTORY_SETTLE_OTHER_L2');

-- 账户余额缓存不直改：E2E 开头会对 S001/S003 调 /finance/supplier-account/repair 重算三账户链。
SELECT 'M3_RESET_OK';
