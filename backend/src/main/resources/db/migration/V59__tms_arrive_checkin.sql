-- ============================================
-- V59: TMS 到达打卡（GPS 围栏）
--
-- 背景：
--   · 司机抵达门店后需打卡记录到达时间与实际位置
--   · 与门店档案坐标（base_customer.longitude/latitude）比对得出偏差距离
--   · 偏差超阈值时标记 GPS 异常并要求填写原因，供 ERP 端稽核
--
-- 内容：
--   1. tms_dispatch_detail 扩展 arrive_* 与 gps_abnormal_* 字段
--   2. sys_param_runtime 新增 4 个到达打卡参数（阈值 / 是否强制 / 是否必拍照）
--
-- 设计说明：
--   · 坐标精度沿用 V11 约定：经度 DECIMAL(11,7)、纬度 DECIMAL(10,7)
--   · arrive_distance 单位米；门店无坐标时为 NULL（无围栏模式，不判异常）
--   · 打卡不改变 status（仍由签收推进），仅以 arrive_time 是否为空判断已/未打卡
--   · 打卡是否作为签收前置条件由参数 TMS_ARRIVE_REQUIRED 控制，默认 false（不强制）
-- ============================================

-- ------------------------------------------------------------
-- 1. 调度明细扩展到达打卡字段
-- ------------------------------------------------------------
ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS arrive_time TIMESTAMP;                          -- 到达打卡时间（NULL=未打卡）
ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS arrive_longitude DECIMAL(11,7);                 -- 打卡点经度
ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS arrive_latitude DECIMAL(10,7);                  -- 打卡点纬度
ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS arrive_accuracy DECIMAL(8,2);                    -- 定位精度（米，越小越准）
ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS arrive_distance DECIMAL(10,2);                   -- 与门店档案坐标的偏差（米，NULL=门店无坐标）
ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS gps_abnormal CHAR(1) DEFAULT 'N';                -- Y=偏差超阈值 N=正常
ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS gps_abnormal_reason VARCHAR(200);                -- GPS 异常原因（司机填写）
ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS arrive_photo_url VARCHAR(500);                   -- 到达现场照片（异常时可要求必拍）

-- 稽核场景高频查询：按调度单查异常打卡
CREATE INDEX IF NOT EXISTS idx_tms_dispatch_detail_arrive   ON tms_dispatch_detail(arrive_time);
CREATE INDEX IF NOT EXISTS idx_tms_dispatch_detail_gps_abn  ON tms_dispatch_detail(gps_abnormal);

-- ------------------------------------------------------------
-- 2. 到达打卡系统参数（ERP 端「系统参数」页可直接维护）
-- ------------------------------------------------------------
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0101', 'TMS_ARRIVE_NORMAL_RADIUS', '到达打卡正常半径(米)', '200', '200', 'TMS配送', '打卡点与门店坐标偏差 <= 该值视为正常')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0102', 'TMS_ARRIVE_WARN_RADIUS', '到达打卡告警半径(米)', '1000', '1000', 'TMS配送', '偏差介于正常半径与该值之间为轻度偏差；超过该值判定 GPS 异常并要求填写原因')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0103', 'TMS_ARRIVE_REQUIRED', '签收前必须到达打卡', 'false', 'false', 'TMS配送', 'true=未打卡不允许签收；false=仅在签收页软提示，不阻断')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0104', 'TMS_ARRIVE_PHOTO_REQUIRED', 'GPS异常打卡必须拍照', 'true', 'true', 'TMS配送', 'true=判定 GPS 异常时必须上传现场照片才能打卡')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);
