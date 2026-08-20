-- ============================================================
-- V71 配送点结算（司机现场收款）
--
-- 背景：原流程是「一单一签收，签收时顺手填收款金额和收款方式」。
-- 实际作业里这条路走不通：
--   1. 同一个门店往往同时有发货单和退货单，客户只会按「发货 - 退货」的净额付钱，
--      按单收款根本对不上；
--   2. 司机可能同时收现金 + 微信 + 剩余挂账（混合收款），单笔 payMethod 表达不了；
--   3. 按单收款会为一次上门生成好几张零散收款单，财务对账痛苦。
--
-- 因此改为「先逐单签收（只存本地草稿，不回传）→ 到店结算一次（多单合并）」，
-- 结算才是唯一的后台落库时点。本脚本建两张表 + 一处补列：
--
--   tms_driver_fund_account  司机可收款的资金账户（多对多）
--     为什么不给 base_fund_account 加 driver_id：一个账户（如公司微信）会被
--     多个司机共用，一个司机也可能持有多个账户，是纯多对多关系。
--
--   tms_store_settlement / _detail / _account  门店结算单
--     _detail  参与本次结算的单据（发货为正、退货为负）
--     _account 各资金账户实收金额；挂账金额单独存主表 credit_amount。
--     账户放明细行而非主表，才能表达混合收款（与 fin_receipt_detail 同构）。
--
--   tms_dispatch_detail.amount 对 RETURN 行恒为 0 是已知缺陷（V55 起就有），
--   本次不追改历史口径，结算时改为 JOIN sales_return_apply.return_amount 现取。
-- ============================================================

-- ---------- 司机 - 资金账户关联 ----------
CREATE TABLE IF NOT EXISTS tms_driver_fund_account (
  id                VARCHAR(32) PRIMARY KEY,
  driver_id         VARCHAR(32) NOT NULL,               -- base_employee.employee_id
  fund_account_id   VARCHAR(32) NOT NULL,               -- base_fund_account.fund_account_id
  fund_account_code VARCHAR(50),                        -- 冗余：财务接口按名称/编码字符串匹配余额
  fund_account_name VARCHAR(100),                       -- 冗余：APP 直接展示，避免每次 JOIN
  is_default        VARCHAR(1) DEFAULT 'N',             -- Y = APP 结算页默认选中
  sort_order        INT DEFAULT 0,
  status            VARCHAR(20) DEFAULT '启用',
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark            VARCHAR(500)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tms_driver_fund ON tms_driver_fund_account(driver_id, fund_account_id);
CREATE INDEX IF NOT EXISTS idx_tms_driver_fund_driver ON tms_driver_fund_account(driver_id, status);

-- ---------- 门店结算单 ----------
CREATE TABLE IF NOT EXISTS tms_store_settlement (
  settle_id       VARCHAR(32) PRIMARY KEY,
  settle_no       VARCHAR(50) NOT NULL UNIQUE,          -- MJ + yyyyMMdd + 4 位流水
  dispatch_id     VARCHAR(32),
  trip_id         VARCHAR(32),
  driver_id       VARCHAR(32) NOT NULL,
  driver_name     VARCHAR(100),
  customer_code   VARCHAR(50) NOT NULL,
  customer_name   VARCHAR(200),
  bill_count      INT DEFAULT 0,                        -- 参与结算的单据数
  receipt_amount  DECIMAL(18,2) DEFAULT 0,              -- 发货单签收金额合计
  return_amount   DECIMAL(18,2) DEFAULT 0,              -- 退货单冲减金额合计（正数存放）
  settle_amount   DECIMAL(18,2) DEFAULT 0,              -- 应结净额 = receipt_amount - return_amount，可为负
  received_amount DECIMAL(18,2) DEFAULT 0,              -- 各账户实收合计
  credit_amount   DECIMAL(18,2) DEFAULT 0,              -- 挂账（欠款）金额
  receipt_no      VARCHAR(50),                          -- 生成的 fin_receipt_bill.receipt_no（全额挂账时为空）
  receipt_id      VARCHAR(32),
  settle_status   VARCHAR(20) NOT NULL DEFAULT 'SETTLED', -- SETTLED 已结算 / VOID 已作废
  fin_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING 待财务确认 / APPROVED 已随交账审核入账
  signer          VARCHAR(100),
  settle_time     TIMESTAMP,
  create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark          VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_tms_store_settle_dispatch ON tms_store_settlement(dispatch_id, customer_code);
CREATE INDEX IF NOT EXISTS idx_tms_store_settle_driver ON tms_store_settlement(driver_id, settle_time);
CREATE INDEX IF NOT EXISTS idx_tms_store_settle_fin ON tms_store_settlement(fin_status);

-- 参与结算的单据行：一行 = 一张发货单或退货单
CREATE TABLE IF NOT EXISTS tms_store_settlement_detail (
  id             VARCHAR(32) PRIMARY KEY,
  settle_id      VARCHAR(32) NOT NULL,
  detail_id      VARCHAR(32),                           -- tms_dispatch_detail.detail_id
  sign_id        VARCHAR(32),                           -- 结算时补写的 tms_sign_record.sign_id
  bill_type      VARCHAR(20) NOT NULL,                  -- RECEIPT / RETURN
  source_bill_no VARCHAR(50) NOT NULL,
  sign_type      VARCHAR(20),                           -- NORMAL / PARTIAL / REJECT
  signed_qty     DECIMAL(18,4) DEFAULT 0,
  reject_qty     DECIMAL(18,4) DEFAULT 0,
  amount         DECIMAL(18,2) DEFAULT 0,               -- 发货为正、退货为负，便于直接 SUM
  ar_no          VARCHAR(50),                           -- 冲抵的 fin_ar.ar_no
  create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_tms_store_settle_d_settle ON tms_store_settlement_detail(settle_id);
CREATE INDEX IF NOT EXISTS idx_tms_store_settle_d_bill ON tms_store_settlement_detail(source_bill_no);

-- 各资金账户实收明细（混合收款）
CREATE TABLE IF NOT EXISTS tms_store_settlement_account (
  id                VARCHAR(32) PRIMARY KEY,
  settle_id         VARCHAR(32) NOT NULL,
  fund_account_id   VARCHAR(32),
  fund_account_code VARCHAR(50),
  fund_account_name VARCHAR(100) NOT NULL,              -- 财务侧按名称匹配余额，必填
  amount            DECIMAL(18,2) DEFAULT 0,
  sort_order        INT DEFAULT 0,
  create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_tms_store_settle_a_settle ON tms_store_settlement_account(settle_id);

-- 结算现场照片：复用签收照片通道（bizType=SETTLEMENT）上传后回传 URL
CREATE TABLE IF NOT EXISTS tms_store_settlement_photo (
  photo_id    VARCHAR(32) PRIMARY KEY,
  settle_id   VARCHAR(32) NOT NULL,
  photo_type  VARCHAR(20) DEFAULT 'SETTLEMENT',
  photo_url   VARCHAR(500) NOT NULL,
  photo_path  VARCHAR(500),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_tms_store_settle_p_settle ON tms_store_settlement_photo(settle_id);

-- 结算单反写关联：交账单审核时需要按 dispatch 找到本次所有门店结算单
ALTER TABLE tms_store_settlement ADD COLUMN IF NOT EXISTS settlement_id VARCHAR(32); -- tms_settlement.settlement_id

-- ---------- 存量司机账户初始化 ----------
-- 不预置任何绑定关系：账户归属涉及资金安全，必须由财务在后台显式配置。
-- 未配置时 APP 结算页只显示【挂账】，不会误把钱记到别人账上。
