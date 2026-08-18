-- ============================================
-- V64: TMS 通用异常上报（P3-4）
--
-- 背景：
--   · 原型「⚠️ 异常上报」按钮只是 prompt() 假实现，弹 4 个选项后仅 toast，无任何落库
--   · 其中「客户拒收」「地址不符」已有专门单据承载（tms_customer_reject / tms_reschedule_return），
--     真正无处安放的是「车辆故障」「交通事故」「货物破损」「门店关门」「天气阻断」这类
--     不产生货权流转、但必须让调度员立刻知道的现场异常
--   · 这类异常此前只能靠司机打电话口述，没有时间戳、没有位置、没有照片，
--     事后无法复盘更无法作为责任划分依据
--
-- 内容：
--   1. tms_exception_report 异常上报单（含 GPS + 关联业务对象 + 处理闭环字段）
--   2. sys_param_runtime 新增 2 个异常上报参数（是否必拍照 / 严重异常类型清单）
--
-- 设计说明：
--   · 坐标精度沿用 V59 口径：经度 DECIMAL(11,7)、纬度 DECIMAL(10,7)；
--     坐标允许为空——司机可能在地下车库或已拒绝定位权限，此时不能因为拿不到坐标就阻断上报
--   · 不建外键：trip_id/dispatch_id/detail_id/receipt_no 均为弱关联，
--     车辆故障类异常可能发生在任何行程之外（如出车前检查发现），此时全为空
--   · 照片复用共享表 tms_sign_photo，photo_type='EXCEPTION'，sign_id 存 report_id，
--     与 RETURN/RESCHEDULE/CUSTOMER_REJECT 一致，不另建照片表
--   · status 只做 PENDING/HANDLING/CLOSED 三态：司机上报后调度员接手再关闭，
--     不设「驳回」——现场异常的真伪由线下核实，系统里强行驳回只会让司机下次不敢报
--   · 严重程度 severity 由前端按异常类型带入，服务端用参数校正，
--     理由同 V59 的距离复算：不能让客户端决定告警级别
-- ============================================

-- ------------------------------------------------------------
-- 1. 异常上报单
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tms_exception_report (
  report_id         VARCHAR(32) PRIMARY KEY,
  report_no         VARCHAR(50) NOT NULL UNIQUE,        -- YCSB + yyyyMMdd + 4位流水
  exception_type    VARCHAR(30) NOT NULL,               -- VEHICLE_FAULT(车辆故障)/TRAFFIC_ACCIDENT(交通事故)/GOODS_DAMAGE(货物破损)/STORE_CLOSED(门店关门)/WEATHER(天气阻断)/ROAD_BLOCKED(道路管控)/OTHER(其他)
  severity          VARCHAR(20) NOT NULL DEFAULT 'NORMAL', -- NORMAL(一般)/URGENT(紧急，需调度员立即介入)
  title             VARCHAR(200),                       -- 异常摘要（列表展示用，为空时由类型名兜底）
  description       VARCHAR(1000) NOT NULL,             -- 异常描述（司机填写，必填）
  trip_id           VARCHAR(32),                        -- 关联配送行程（车辆故障可能无行程）
  dispatch_id       VARCHAR(32),                        -- 关联调度单
  detail_id         VARCHAR(32),                        -- 关联 tms_dispatch_detail（门店级异常）
  receipt_no        VARCHAR(50),                        -- 关联发货单号
  customer_code     VARCHAR(50),
  customer_name     VARCHAR(200),
  vehicle_no        VARCHAR(50),                        -- 车牌（车辆故障/事故类必填）
  driver_id         VARCHAR(32),
  driver_name       VARCHAR(100),
  longitude         DECIMAL(11,7),                      -- 上报点经度（无定位权限时为 NULL）
  latitude          DECIMAL(10,7),                      -- 上报点纬度
  accuracy          DECIMAL(8,2),                       -- 定位精度（米，越小越准）
  location_address  VARCHAR(500),                       -- 司机补充的位置描述（GPS 拿不到时的唯一线索）
  status            VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING(待处理)/HANDLING(处理中)/CLOSED(已关闭)
  handler           VARCHAR(100),                       -- 处理人（调度员）
  handle_result     VARCHAR(1000),                      -- 处理结果/结论
  handled_at        TIMESTAMP,                          -- 接手处理时间
  closed_at         TIMESTAMP,                          -- 关闭时间
  reported_at       TIMESTAMP,                          -- 司机上报时间（离线补传时与 create_time 不同）
  create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark            VARCHAR(500)
);
-- 调度员看板高频查询：按状态捞待处理、按司机/行程追溯、按类型统计
CREATE INDEX IF NOT EXISTS idx_tms_exception_report_status   ON tms_exception_report(status);
CREATE INDEX IF NOT EXISTS idx_tms_exception_report_driver   ON tms_exception_report(driver_id);
CREATE INDEX IF NOT EXISTS idx_tms_exception_report_trip     ON tms_exception_report(trip_id);
CREATE INDEX IF NOT EXISTS idx_tms_exception_report_dispatch ON tms_exception_report(dispatch_id);
CREATE INDEX IF NOT EXISTS idx_tms_exception_report_type     ON tms_exception_report(exception_type);
CREATE INDEX IF NOT EXISTS idx_tms_exception_report_time     ON tms_exception_report(reported_at);

-- ------------------------------------------------------------
-- 2. 异常上报系统参数（ERP 端「系统参数」页可直接维护）
-- ------------------------------------------------------------
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0109', 'TMS_EXCEPTION_PHOTO_REQUIRED', '异常上报必须拍照', 'true', 'true', 'TMS配送', 'true=上报异常必须至少上传1张现场照片；离线上报时照片随队列补传，不阻断提交')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0110', 'TMS_EXCEPTION_URGENT_TYPES', '紧急异常类型', 'TRAFFIC_ACCIDENT,VEHICLE_FAULT', 'TRAFFIC_ACCIDENT,VEHICLE_FAULT', 'TMS配送', '逗号分隔；命中的类型服务端强制置为 URGENT，不采信前端传入的严重程度')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);
