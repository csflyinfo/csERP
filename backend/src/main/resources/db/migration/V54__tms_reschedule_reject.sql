-- ============================================
-- V54: TMS 改派返仓 + 客户拒收单（P3-2）
--
-- 内容：
--   1. tms_reschedule_return(+detail) 改派返仓单（客户不在/地址错误，不反审核出库单，验收后回调度池）
--   2. tms_customer_reject(+detail) 客户拒收单（客户拒收，仓库收货生成拒收入库单，库存增加）
--   3. 扩展 sales_receipt.dispatch_status 增加 RESCHEDULED（待改派）值（无需 DDL，仅约定）
--
-- H2 兼容：CREATE INDEX IF NOT EXISTS
-- ============================================

-- ------------------------------------------------------------
-- 1. 改派返仓单（客户不在/地址错误 → 货物随车返仓 → 仓库验收 → 回调度池重新派送）
--    核心原则：不反审核出库单，不生成入库单，库存不变
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tms_reschedule_return (
  return_id         VARCHAR(32) PRIMARY KEY,
  return_no         VARCHAR(50) NOT NULL UNIQUE,        -- GPRC + yyyyMMdd + 4位流水
  trip_id           VARCHAR(32),
  dispatch_id       VARCHAR(32),
  detail_id         VARCHAR(32),                       -- 关联 tms_dispatch_detail
  receipt_no        VARCHAR(50),                       -- 关联发货单号
  customer_code     VARCHAR(50),
  customer_name     VARCHAR(200),
  customer_address  VARCHAR(500),
  reason            VARCHAR(30) NOT NULL,              -- CUSTOMER_ABSENT/ADDRESS_ERROR/UNREACHABLE/CUSTOMER_REQUEST/OTHER
  reason_detail     VARCHAR(500),
  total_qty         DECIMAL(18,4) DEFAULT 0,           -- 改派返仓总数量
  reschedule_date   DATE,                              -- 期望改送日期（默认次日）
  reschedule_count  INT DEFAULT 1,                     -- 第几次改派（防无限延期）
  driver_id         VARCHAR(32),
  driver_name       VARCHAR(100),
  status            VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING(待返仓)/CHECKED(已验收)/REDISPATCHED(已重新派送)
  returned_at       TIMESTAMP,                         -- 司机返仓时间
  checked_at        TIMESTAMP,                         -- 仓库验收时间
  checker           VARCHAR(100),
  create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark            VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_tms_reschedule_return_trip     ON tms_reschedule_return(trip_id);
CREATE INDEX IF NOT EXISTS idx_tms_reschedule_return_dispatch ON tms_reschedule_return(dispatch_id);
CREATE INDEX IF NOT EXISTS idx_tms_reschedule_return_receipt  ON tms_reschedule_return(receipt_no);
CREATE INDEX IF NOT EXISTS idx_tms_reschedule_return_status   ON tms_reschedule_return(status);
CREATE INDEX IF NOT EXISTS idx_tms_reschedule_return_driver   ON tms_reschedule_return(driver_id);

CREATE TABLE IF NOT EXISTS tms_reschedule_return_detail (
  detail_id           VARCHAR(32) PRIMARY KEY,
  return_id           VARCHAR(32) NOT NULL,
  goods_code          VARCHAR(50),
  goods_name          VARCHAR(200),
  spec                VARCHAR(200),
  unit_name           VARCHAR(50),
  planned_qty         DECIMAL(18,4) DEFAULT 0,         -- 计划配送数量
  actual_return_qty   DECIMAL(18,4) DEFAULT 0,         -- 实际返仓数量（仓库验收录入）
  diff_qty            DECIMAL(18,4) DEFAULT 0,         -- 差异
  batch_no            VARCHAR(100),
  remark              VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_tms_reschedule_return_detail_head ON tms_reschedule_return_detail(return_id);

-- ------------------------------------------------------------
-- 2. 客户拒收单（客户拒收 → 货物随车返仓 → 仓库收货 → 生成拒收入库单 → 库存增加）
--    核心原则：仓库收货后生成 inv_reject_inbound（JSRK），审核后库存增加
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tms_customer_reject (
  reject_id         VARCHAR(32) PRIMARY KEY,
  reject_no         VARCHAR(50) NOT NULL UNIQUE,       -- KHJS + yyyyMMdd + 4位流水
  trip_id           VARCHAR(32),
  dispatch_id       VARCHAR(32),
  detail_id         VARCHAR(32),                       -- 关联 tms_dispatch_detail
  receipt_no        VARCHAR(50),                       -- 关联发货单号
  customer_code     VARCHAR(50),
  customer_name     VARCHAR(200),
  customer_address  VARCHAR(500),
  reject_reason     VARCHAR(30) NOT NULL,              -- CUSTOMER_REJECT/GOODS_DAMAGED/SPEC_MISMATCH/QTY_MISMATCH/OTHER
  reason_detail     VARCHAR(500),
  total_qty         DECIMAL(18,4) DEFAULT 0,           -- 拒收总数量
  total_amount      DECIMAL(18,2) DEFAULT 0,           -- 拒收总金额
  reject_inbound_no VARCHAR(50),                       -- 关联拒收入库单号（仓库收货后生成）
  driver_id         VARCHAR(32),
  driver_name       VARCHAR(100),
  status            VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING(待返仓)/RECEIVED(已收货)/COMPLETED(已完成)
  returned_at       TIMESTAMP,                         -- 司机返仓时间
  received_at       TIMESTAMP,                         -- 仓库收货时间
  receiver          VARCHAR(100),
  create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark            VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_tms_customer_reject_trip     ON tms_customer_reject(trip_id);
CREATE INDEX IF NOT EXISTS idx_tms_customer_reject_dispatch ON tms_customer_reject(dispatch_id);
CREATE INDEX IF NOT EXISTS idx_tms_customer_reject_receipt  ON tms_customer_reject(receipt_no);
CREATE INDEX IF NOT EXISTS idx_tms_customer_reject_status   ON tms_customer_reject(status);
CREATE INDEX IF NOT EXISTS idx_tms_customer_reject_driver   ON tms_customer_reject(driver_id);

CREATE TABLE IF NOT EXISTS tms_customer_reject_detail (
  detail_id           VARCHAR(32) PRIMARY KEY,
  reject_id           VARCHAR(32) NOT NULL,
  goods_code          VARCHAR(50),
  goods_name          VARCHAR(200),
  spec                VARCHAR(200),
  unit_name           VARCHAR(50),
  reject_qty          DECIMAL(18,4) DEFAULT 0,         -- 拒收数量
  actual_receive_qty  DECIMAL(18,4) DEFAULT 0,         -- 仓库实收数量
  diff_qty            DECIMAL(18,4) DEFAULT 0,         -- 差异
  price               DECIMAL(18,4) DEFAULT 0,
  amount              DECIMAL(18,2) DEFAULT 0,
  batch_no            VARCHAR(100),
  remark              VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_tms_customer_reject_detail_head ON tms_customer_reject_detail(reject_id);
