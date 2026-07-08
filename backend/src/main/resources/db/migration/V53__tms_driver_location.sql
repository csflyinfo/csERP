-- ============================================
-- V53: TMS 司机位置轨迹表（P2 配送核心流程）
--
-- 背景：
--   · P1 已建调度核心 6 表（V51），P2 需要司机 GPS 轨迹记录
--   · 用于在途监控、轨迹回放、超时告警
--   · 生产 MySQL 按月分区脚本另放 db/prod/，不进 Flyway
--
-- 内容：
--   1. tms_driver_location 司机位置轨迹
--   2. tms_dispatch 扩展：当前坐标字段（实时位置快照，避免每次查轨迹表）
-- ============================================

-- 1. 司机位置轨迹（每次上报一条记录）
CREATE TABLE IF NOT EXISTS tms_driver_location (
  loc_id          VARCHAR(32) PRIMARY KEY,
  driver_id       VARCHAR(32) NOT NULL,                  -- 司机 employee_id
  driver_name     VARCHAR(100),
  dispatch_id     VARCHAR(32),                           -- 关联调度单（可空，未出车也记录）
  trip_id         VARCHAR(32),                           -- 关联行程
  longitude       DECIMAL(10,6) NOT NULL,                -- 经度
  latitude        DECIMAL(10,6) NOT NULL,                -- 纬度
  speed           DECIMAL(6,2) DEFAULT 0,                -- 速度 km/h
  heading         DECIMAL(5,2),                          -- 方向角 0-360
  accuracy        DECIMAL(8,2),                          -- 定位精度（米）
  loc_time        TIMESTAMP NOT NULL,                    -- 定位时间（设备端）
  report_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,   -- 上报时间（服务端）
  online          BOOLEAN DEFAULT TRUE,                  -- 是否在线（30s 未上报置 false）
  remark          VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_tms_driver_loc_driver  ON tms_driver_location(driver_id);
CREATE INDEX IF NOT EXISTS idx_tms_driver_loc_dispatch ON tms_driver_location(dispatch_id);
CREATE INDEX IF NOT EXISTS idx_tms_driver_loc_time    ON tms_driver_location(loc_time);

-- 2. 调度单扩展：实时位置快照（避免在途监控频繁 JOIN 轨迹表）
ALTER TABLE tms_dispatch ADD COLUMN IF NOT EXISTS cur_longitude DECIMAL(10,6);
ALTER TABLE tms_dispatch ADD COLUMN IF NOT EXISTS cur_latitude  DECIMAL(10,6);
ALTER TABLE tms_dispatch ADD COLUMN IF NOT EXISTS cur_loc_time  TIMESTAMP;
ALTER TABLE tms_dispatch ADD COLUMN IF NOT EXISTS cur_speed     DECIMAL(6,2) DEFAULT 0;
