-- =====================================================================
-- PRD-36 供应商账户与厂家费用 M4（V131）
-- 1. 供应商应付期初同批号支持「期初预付金额」：列已在 V128 预置
--    （fin_ap_init.prepay_amount / generated_prepay_flow_id），
--    过账时 ap_amount>0 立 QCAP 应付单、prepay_amount>0 写 QCYF 预付期初流水。
-- 2. 已上线库漏录期初预付的「预付补录」通道：复用 fin_ap_init 暂存，
--    以 line_kind 区分初始化行（AP_INIT）与补录行（PREPAY_SUPPLEMENT），
--    补录批号写 biz_init_post(init_type='AP_PREPAY')，可多批、可整批反建账。
--    （biz_init_post.init_type VARCHAR(10)，AP_PREPAY 共 9 字符可容纳。）
-- 3. 应付日结定版快照的预付/费用两列已在 V128 预置
--    （biz_close_ap_daily.prepay_account_balance / expense_account_balance）。
-- =====================================================================

-- 暂存行类别：AP_INIT=初始化暂存行（默认，历史行全部归属此项）；PREPAY_SUPPLEMENT=上线后预付补录行
ALTER TABLE fin_ap_init ADD COLUMN IF NOT EXISTS line_kind VARCHAR(24) NOT NULL DEFAULT 'AP_INIT';
CREATE INDEX IF NOT EXISTS idx_fin_ap_init_kind ON fin_ap_init(line_kind);
