-- PRD-36 M4 验证库全量复位（8082 verify-prd36-m1，AUTO_SERVER=TRUE 下可与后端并发执行）
-- 必须在以下两脚本之后串联执行（E2E 自动拼接到同一 RunScript）：
--   prd36-m2-reset-v129.sql（清 FX/FK 付款核销域）
--   prd36-m3-reset-v130.sql（清 JF/DX 厂家费用域）
-- 本脚本目标：把验证库 AP 往来/供应商账户/AP 期初与预付补录/日结/GL/RBAC 测试角色
--             全部复位到零基线，可干净重跑 prd36-m4-api-e2e.mjs。
-- 识别口径：M4 供应商统一 S-M4-% 前缀；AP 期初批号 QC*（biz_init_post init_type AP/AP_PREPAY）；
--          M1/M2 历史夹具单号 AP-TEST-%/QCAP-TEST-1/AP-M2-%/CGSH-M2-%/CGSH-TEST-%/CGTH-TEST-%。
-- 严禁在业务库执行（会清空隔离验证库的全部应付/真值/凭证数据）。

-- ========== 0. 反日结由 E2E 在套脚本前走 /finance/day-close/reopen API 完成 ==========
-- （BizDayCloseGuard 封单日是 JVM 懒缓存，直删 biz_day_close 无法 evict）

-- ========== 1. M4 供应商：账户流水/账户/银行账户/档案 ==========
-- S001/S002/S003 的历史流水一并清空，随后 E2E 调 /finance/supplier-account/repair 按真值重建
DELETE FROM fin_supplier_account_flow
 WHERE supplier_code LIKE 'S-M4-%' OR supplier_code IN ('S001', 'S002', 'S003');
DELETE FROM fin_supplier_account WHERE supplier_code LIKE 'S-M4-%';
DELETE FROM base_supplier_bank_account WHERE supplier_code LIKE 'S-M4-%';
DELETE FROM base_supplier WHERE supplier_code LIKE 'S-M4-%';

-- ========== 2. AP 往来域全量复位（M1/M2 历史夹具 + M4 QCAP 期初 + M4 守卫夹具） ==========
DELETE FROM fin_reconcile_record;
DELETE FROM fin_ap;
DELETE FROM pur_receipt
 WHERE receipt_no LIKE 'CGSH-TEST-%' OR receipt_no LIKE 'CGSH-M2-%';
DELETE FROM pur_return WHERE return_no LIKE 'CGTH-TEST-%';
-- M3 账扣可能改过采购收货付款状态（m3-reset 只复位 CGSH-M2-%，这里 TEST 行已整行删除）

-- ========== 3. AP 期初 / 预付补录：暂存行 + 批号 + 建账标志 ==========
DELETE FROM fin_ap_init;
DELETE FROM biz_init_post WHERE init_type IN ('AP', 'AP_PREPAY');
UPDATE sys_param_runtime SET param_value = 'N' WHERE param_key = 'biz.init.ap_posted';
UPDATE sys_param_runtime SET param_value = ''  WHERE param_key = 'biz.init.ap_post_no';

-- ========== 4. 业务日结：主表 + 日志 + 全部定版快照 ==========
DELETE FROM biz_close_goods_daily;
DELETE FROM biz_close_fund_daily;
DELETE FROM biz_close_ap_daily;
DELETE FROM biz_close_ar_daily;
DELETE FROM biz_day_close_log;
DELETE FROM biz_day_close;

-- ========== 5. GL 域复位：凭证/分录/事件池/期初余额试算行/启用标志 ==========
-- 期间行 fin_accounting_period 保留，enable 时会重刷期间状态
DELETE FROM fin_voucher_entry;
DELETE FROM fin_voucher;
DELETE FROM fin_gl_event;
DELETE FROM fin_init_balance;
UPDATE sys_param_runtime SET param_value = 'N'
 WHERE param_key IN ('fin.gl.initialized', 'fin.gl.enabled');

-- ========== 6. RBAC 字段权限测试角色/用户 ==========
DELETE FROM sys_role_menu_rel WHERE role_id IN (
    SELECT role_id FROM sys_role_runtime WHERE role_code = 'ROLE_M4_FIELD');
DELETE FROM sys_role_func_rel WHERE role_id IN (
    SELECT role_id FROM sys_role_runtime WHERE role_code = 'ROLE_M4_FIELD');
DELETE FROM sys_role_field_rel WHERE role_id IN (
    SELECT role_id FROM sys_role_runtime WHERE role_code = 'ROLE_M4_FIELD');
DELETE FROM sys_role_data_scope WHERE role_id IN (
    SELECT role_id FROM sys_role_runtime WHERE role_code = 'ROLE_M4_FIELD');
DELETE FROM sys_user_role_rel WHERE user_id IN (
    SELECT user_id FROM sys_user_runtime WHERE username = 'm4field');
DELETE FROM sys_user_data_scope WHERE user_id IN (
    SELECT user_id FROM sys_user_runtime WHERE username = 'm4field');
DELETE FROM sys_user_warehouse WHERE user_id IN (
    SELECT user_id FROM sys_user_runtime WHERE username = 'm4field');
DELETE FROM sys_pwd_history WHERE user_id IN (
    SELECT user_id FROM sys_user_runtime WHERE username = 'm4field');
DELETE FROM sys_user_runtime WHERE username = 'm4field';
DELETE FROM sys_role_runtime WHERE role_code = 'ROLE_M4_FIELD';

-- ========== 7. 兜底：M4 退款付款单（FK 序列，m2-reset 的 FK% 谓词已覆盖；此处按 summary 双保险） ==========
DELETE FROM fin_fund_ledger WHERE source_bill IN (
    SELECT payment_no FROM fin_payment_bill WHERE summary LIKE 'M4%');
DELETE FROM fin_payment_detail WHERE payment_id IN (
    SELECT payment_id FROM fin_payment_bill WHERE summary LIKE 'M4%');
DELETE FROM fin_payment_bill WHERE summary LIKE 'M4%';

-- 账户余额缓存不直改：E2E 复位后对 S001/S002/S003 调 repair 重算（S-M4 户已整户删除）。
SELECT 'M4_RESET_OK';
