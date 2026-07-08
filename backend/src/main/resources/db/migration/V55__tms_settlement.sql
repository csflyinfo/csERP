-- ============================================
-- V55: TMS 交账与结算
--
-- 背景：
--   · P3-3 交账模块：司机一天配送结束后汇总应收/实收，拍照留证，提交交账
--   · ERP 端财务审核，处理长款/短款差异
--
-- 内容：
--   1. tms_settlement          交账单（主表）
--   2. tms_settlement_photo    结算照片（手机收款截图/现金清点/POS签购单）
--
-- 数据来源：
--   · 应收总金额 = SUM(tms_dispatch_detail.amount WHERE bill_type='RECEIPT')
--   · 实收现金   = SUM(tms_sign_record.collect_amount WHERE pay_method='现金')
--   · 线上收款   = SUM(tms_sign_record.collect_amount WHERE pay_method IN ('微信','支付宝'))
--   · 退货金额   = SUM(tms_dispatch_detail.amount WHERE bill_type='RETURN')
--
-- 状态机：PENDING(待审核) → APPROVED(已审核) / DISPUTED(差异争议)
-- ============================================

-- ------------------------------------------------------------
-- 1. 交账单主表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tms_settlement (
  settlement_id     VARCHAR(32) PRIMARY KEY,
  settlement_no     VARCHAR(50) NOT NULL UNIQUE,        -- JZ + yyyyMMdd + 4位流水
  dispatch_id       VARCHAR(32),                        -- 关联调度单
  trip_id           VARCHAR(32),                        -- 关联配送行程
  driver_id         VARCHAR(32) NOT NULL,               -- 司机 employee_id
  driver_name       VARCHAR(100),                       -- 司机姓名（冗余）
  route_line        VARCHAR(100),                       -- 线路
  settle_date       DATE NOT NULL,                      -- 交账日期
  total_stores      INT DEFAULT 0,                      -- 总门店数
  signed_stores     INT DEFAULT 0,                      -- 已签收门店数
  total_amount      DECIMAL(18,2) DEFAULT 0,            -- 应收总金额
  cash_amount       DECIMAL(18,2) DEFAULT 0,            -- 实收现金
  online_amount     DECIMAL(18,2) DEFAULT 0,            -- 线上收款（微信/支付宝）
  return_amount     DECIMAL(18,2) DEFAULT 0,            -- 退货金额
  return_qty        DECIMAL(18,4) DEFAULT 0,            -- 退货件数
  submit_amount     DECIMAL(18,2) DEFAULT 0,            -- 应交回金额 = 现金 - 退货退款
  actual_submit     DECIMAL(18,2) DEFAULT 0,            -- 实际交回金额（司机填写）
  diff_amount       DECIMAL(18,2) DEFAULT 0,            -- 差异金额 = 实际交回 - 应交回
  diff_reason       VARCHAR(500),                       -- 差异原因说明
  signature_img     VARCHAR(500),                       -- 电子签名图（base64 或 MinIO URL）
  status            VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING(待审核)/APPROVED(已审核)/DISPUTED(差异争议)
  submitted_at      TIMESTAMP,                          -- 司机提交时间
  audited_at        TIMESTAMP,                          -- 财务审核时间
  auditor           VARCHAR(100),                       -- 审核人
  audit_remark      VARCHAR(500),                       -- 审核备注
  create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark            VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_tms_settlement_no     ON tms_settlement(settlement_no);
CREATE INDEX IF NOT EXISTS idx_tms_settlement_status ON tms_settlement(status);
CREATE INDEX IF NOT EXISTS idx_tms_settlement_date   ON tms_settlement(settle_date);
CREATE INDEX IF NOT EXISTS idx_tms_settlement_driver ON tms_settlement(driver_id);
CREATE INDEX IF NOT EXISTS idx_tms_settlement_dispatch ON tms_settlement(dispatch_id);

-- ------------------------------------------------------------
-- 2. 结算照片（手机收款截图/现金清点照片/POS签购单）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tms_settlement_photo (
  photo_id        VARCHAR(32) PRIMARY KEY,
  settlement_id   VARCHAR(32) NOT NULL,
  photo_type      VARCHAR(20) DEFAULT 'CASH',           -- CASH(现金清点)/ONLINE(收款截图)/POS(POS签购单)/OTHER
  photo_data      LONGTEXT,                             -- base64 编码（MinIO 集成后改为 URL）
  photo_url       VARCHAR(500),                         -- MinIO 对象路径（预留）
  create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_tms_settlement_photo_settlement ON tms_settlement_photo(settlement_id);
