-- =====================================================================
-- PRD-35 M4：客户账户收尾三件套
--   1. 日结应收快照增加「账户预收余额」列（取 fin_customer_account 缓存，
--      与既有负应收重分类列 advance_amount 勾稽）
--   2. 客户期初预收暂存/过账表 fin_adv_init（镜像 fin_ar_init）
--   3. 参数 P0189：门店结算溢收自动转预收（默认开）
-- =====================================================================

-- 1. 日结应收快照：账户预收余额（结账时点客户账户 advance_balance）
ALTER TABLE biz_close_ar_daily ADD COLUMN IF NOT EXISTS advance_account_balance DECIMAL(18,2) NOT NULL DEFAULT 0;

-- 2. 客户期初预收暂存行
CREATE TABLE IF NOT EXISTS fin_adv_init (
    line_id                 VARCHAR(32) PRIMARY KEY,
    import_batch_no         VARCHAR(40) NOT NULL,
    row_no                  INT,
    customer_code           VARCHAR(50),
    customer_name           VARCHAR(100),
    original_bill_no        VARCHAR(50),                   -- 客户原单号/摘要：仅留存展示
    original_bill_date      DATE,                          -- 原单据日期：仅留存
    adv_amount              DECIMAL(18,2) NOT NULL DEFAULT 0, -- 期初预收余额（必须 > 0）
    remark                  VARCHAR(255),
    line_status             VARCHAR(10) NOT NULL DEFAULT 'VALID',
    error_msg               VARCHAR(1000),
    task_no                 VARCHAR(50),
    posted                  CHAR(1) NOT NULL DEFAULT 'N',
    post_no                 VARCHAR(40),
    generated_flow_id       VARCHAR(32),                   -- 过账生成的 fin_customer_account_flow.flow_id（反建账定位）
    created_by              VARCHAR(32),
    created_by_name         VARCHAR(100),
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_adv_init_posted ON fin_adv_init(posted);
CREATE INDEX IF NOT EXISTS idx_fin_adv_init_batch ON fin_adv_init(import_batch_no);

-- 3. 参数：门店结算溢收自动转预收
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0189', 'tms.settle.overpay-to-advance', '门店结算溢收自动转预收', 'Y', 'Y', 'TMS配送',
        'Y=司机交账核销后未匹配的多收款自动生成预收收款单并计入客户预收余额（资金流水不重复记）；N=仅留日志提示，财务人工处理')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);
