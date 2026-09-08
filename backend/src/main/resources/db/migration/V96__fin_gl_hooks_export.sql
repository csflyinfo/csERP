-- =====================================================================
-- V96 总账 M4：业务钩子支撑 + 凭证导出
--  1. 基础档案加"对应总账科目"列：资金账户 / 费用类型 / 商品分类
--  2. fin_voucher_export_log 凭证导出日志（金蝶/用友）
--  3. 销售类模板收入/成本行改用 @INCOME/@COST 占位符（按商品分类档案映射，回落 500101/5401）
-- 约定：ADD COLUMN IF NOT EXISTS 幂等；无外键；中文状态值。
-- =====================================================================

-- ---------- 1. 档案映射列 ----------
ALTER TABLE base_fund_account  ADD COLUMN IF NOT EXISTS gl_account_code           VARCHAR(32);
ALTER TABLE base_expense_type  ADD COLUMN IF NOT EXISTS gl_expense_account_code   VARCHAR(32);
ALTER TABLE base_category      ADD COLUMN IF NOT EXISTS gl_income_account_code    VARCHAR(32);
ALTER TABLE base_category      ADD COLUMN IF NOT EXISTS gl_cost_account_code      VARCHAR(32);

-- ---------- 2. 凭证导出日志 ----------
CREATE TABLE IF NOT EXISTS fin_voucher_export_log (
    id              VARCHAR(32) PRIMARY KEY,
    export_format   VARCHAR(20)  NOT NULL,          -- KINGDEE 金蝶KIS / YONYOU 用友U8/T+
    period_from     VARCHAR(6),                     -- yyyyMM
    period_to       VARCHAR(6),
    voucher_word    VARCHAR(10),
    file_name       VARCHAR(200),
    voucher_count   INT          DEFAULT 0,
    entry_count     INT          DEFAULT 0,
    creator         VARCHAR(50),
    create_time     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    remark          VARCHAR(500)
);

-- ---------- 3. 销售类模板科目占位符升级（幂等 UPDATE） ----------
-- @COST：按商品分类 gl_cost_account_code，未配回落 5401 主营业务成本
UPDATE fin_voucher_template_line SET account_expr = '@COST'   WHERE id IN ('TPL_SALE_OUT_L1', 'TPL_SALE_RC_L2');
-- @INCOME：按商品分类 gl_income_account_code，未配回落 500101 主营业务收入-商品销售收入
UPDATE fin_voucher_template_line SET account_expr = '@INCOME' WHERE id IN ('TPL_SALE_SIGN_L2', 'TPL_SALE_RET_L2');
