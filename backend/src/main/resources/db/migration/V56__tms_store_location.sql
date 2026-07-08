-- ============================================
-- V56: TMS 门店定位修正记录（P4-1）
--
-- 背景：
--   · 司机到达门店后发现系统定位不准（GPS偏移、地址变更、新开门店等）
--   · APP 端修正门店坐标 + 拍门头照 → ERP 审核通过后更新 base_customer 经纬度
--
-- 内容：
--   1. tms_store_location_log  门店定位修正记录
--   2. base_customer 扩展 address_geo_updated_at（经纬度已有 V11，来源已有 V51）
--
-- 状态机：PENDING(待审核) → APPROVED(已通过，更新客户定位) / REJECTED(已驳回)
--
-- 注意：
--   · base_customer.longitude/latitude 由 V11 创建
--   · base_customer.address_geo_source 由 V51 创建（MANUAL/DRIVER/IMPORT）
--   · base_customer.address_detail 由 V51 创建
--   · 本脚本只新增 address_geo_updated_at 和修正记录表
-- ============================================

-- 1. base_customer 扩展定位更新时间
ALTER TABLE base_customer ADD COLUMN IF NOT EXISTS address_geo_updated_at TIMESTAMP;

-- 2. 门店定位修正记录表
CREATE TABLE IF NOT EXISTS tms_store_location_log (
  log_id          VARCHAR(32) PRIMARY KEY,
  customer_id     VARCHAR(32) NOT NULL,                  -- 客户ID
  customer_code   VARCHAR(50),                           -- 客户编码（冗余）
  customer_name   VARCHAR(200),                          -- 客户名称（冗余）
  old_lat         DECIMAL(10,7),                         -- 原纬度
  old_lng         DECIMAL(11,7),                         -- 原经度
  new_lat         DECIMAL(10,7) NOT NULL,                -- 新纬度
  new_lng         DECIMAL(11,7) NOT NULL,                -- 新经度
  store_photo     LONGTEXT,                              -- 门头照（base64，MinIO 集成后改 URL）
  store_photo_url VARCHAR(500),                          -- 门头照 MinIO 路径（预留）
  driver_id       VARCHAR(32),                           -- 提交司机
  driver_name     VARCHAR(100),                          -- 司机姓名（冗余）
  dispatch_id     VARCHAR(32),                           -- 关联调度单（可空）
  source          VARCHAR(20) DEFAULT 'DRIVER',          -- DRIVER(司机)/ADMIN(管理员)
  status          VARCHAR(20) DEFAULT 'PENDING',         -- PENDING/APPROVED/REJECTED
  reviewer_id     VARCHAR(32),                           -- 审核人ID
  reviewer_name   VARCHAR(100),                          -- 审核人姓名
  review_remark   VARCHAR(500),                          -- 审核备注
  created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,   -- 提交时间
  reviewed_at     TIMESTAMP                              -- 审核时间
);
CREATE INDEX IF NOT EXISTS idx_tms_store_loc_log_customer ON tms_store_location_log(customer_id);
CREATE INDEX IF NOT EXISTS idx_tms_store_loc_log_status   ON tms_store_location_log(status);
CREATE INDEX IF NOT EXISTS idx_tms_store_loc_log_driver   ON tms_store_location_log(driver_id);
CREATE INDEX IF NOT EXISTS idx_tms_store_loc_log_created  ON tms_store_location_log(created_at);
