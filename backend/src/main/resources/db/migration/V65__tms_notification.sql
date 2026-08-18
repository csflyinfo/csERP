-- ============================================
-- V65: 司机端消息推送底座（P4-1）
--
-- 背景：
--   · 既有 sys_notification 是个只读空壳：全仓零处 INSERT，且没有收件人维度——
--     is_read 是表级单列，一个人点「全部已读」全公司都变已读，无法承载司机端一对一消息
--   · 司机端此前完全没有消息能力：AppBar 上的铃铛是静态图标，
--     调度员派单后司机只能靠下拉刷新自己发现新任务，异常上报处理完司机也无感知
--   · 因此不改造 sys_notification（它承载 ERP 端全局通知语义，改列会波及既有页面），
--     另建 TMS 专用的 tms_notification，带完整收件人维度
--
-- 内容：
--   1. tms_notification    消息表（一行一收件人，读写状态天然隔离）
--   2. tms_push_token      设备推送令牌表（为极光/FCM 真推送预留）
--   3. sys_param_runtime   新增 3 个推送参数
--
-- 设计说明：
--   · 「一行一收件人」而非「消息主表 + 收件人表」：司机端消息是点对点的（派单给某个司机、
--     异常回执给上报人），几乎不存在一条消息发给几百人的广播场景；
--     双表设计在这里只会让每次查询多一次 JOIN，收益为零
--   · receiver_type 区分 DRIVER/USER：同一张表既装司机端消息，也装反向通知调度员的消息，
--     避免为了调度端再建一张结构完全相同的表
--   · push_status 独立于 is_read：前者是「有没有推到设备」，后者是「人有没有看」，
--     两者混用会导致重启服务后已读消息被重复推送
--   · link_type/link_id 用于 APP 点击跳转：只存类型与业务 ID，不存 URL——
--     APP 路由由客户端决定，服务端写死路径会导致改版必须发新包
--   · 不建外键：司机可能被停用但历史消息要留存，且与既有 TMS 表一致不用 FK
-- ============================================

-- ------------------------------------------------------------
-- 1. 消息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tms_notification (
  notify_id       VARCHAR(32) PRIMARY KEY,
  notify_no       VARCHAR(50),                            -- XXTZ + yyyyMMdd + 4位流水（便于客服追溯；不加唯一约束以免离线补发撞号）
  receiver_type   VARCHAR(20) NOT NULL DEFAULT 'DRIVER',  -- DRIVER(司机，receiver_id=driver_id) / USER(ERP用户，receiver_id=用户名)
  receiver_id     VARCHAR(32) NOT NULL,                   -- 收件人标识
  receiver_name   VARCHAR(100),                           -- 冗余姓名，列表展示免 JOIN
  notify_type     VARCHAR(30) NOT NULL,                   -- NEW_TASK(新任务)/EXCEPTION_REPLY(异常回执)/SETTLE_RESULT(交账结果)/REJECT_RESULT(拒收结果)/RESCHEDULE_RESULT(改派返仓结果)/EXCEPTION_ALERT(异常告警,发调度员)/SYSTEM(系统)
  level           VARCHAR(20) NOT NULL DEFAULT 'NORMAL',  -- NORMAL(普通)/IMPORTANT(重要)/URGENT(紧急，APP 强提示)
  title           VARCHAR(200) NOT NULL,
  content         VARCHAR(1000),
  link_type       VARCHAR(30),                            -- DISPATCH(调度单)/EXCEPTION(异常单)/SETTLEMENT(交账单)/REJECT(拒收单)/RESCHEDULE(改派返仓单)/NONE
  link_id         VARCHAR(50),                            -- 对应业务主键或单号，APP 据此跳详情
  biz_no          VARCHAR(50),                            -- 业务单号（展示用）
  is_read         BOOLEAN NOT NULL DEFAULT FALSE,
  read_at         TIMESTAMP,
  push_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING(待推)/SENT(已推)/FAILED(推送失败)/SKIPPED(未启用或无令牌)
  push_channel    VARCHAR(20),                            -- INAPP(站内)/JPUSH(极光)/FCM
  pushed_at       TIMESTAMP,
  push_error      VARCHAR(500),                           -- 失败原因，便于排查真推送接入问题
  sender          VARCHAR(100),                           -- 发送方（调度员用户名或 SYSTEM）
  create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark          VARCHAR(500)
);
-- APP 高频查询：某司机的未读数、消息列表按时间倒序；推送任务扫 PENDING
CREATE INDEX IF NOT EXISTS idx_tms_notification_receiver ON tms_notification(receiver_type, receiver_id, is_read);
CREATE INDEX IF NOT EXISTS idx_tms_notification_time     ON tms_notification(create_time);
CREATE INDEX IF NOT EXISTS idx_tms_notification_push     ON tms_notification(push_status);
CREATE INDEX IF NOT EXISTS idx_tms_notification_type     ON tms_notification(notify_type);

-- ------------------------------------------------------------
-- 2. 设备推送令牌（真推送预留）
-- ------------------------------------------------------------
-- 一个司机可能有多台设备（换机未登出、备用机），故 driver_id 不唯一；
-- 同一 device_token 重复注册应更新而非新增，由 Controller 先查后写保证。
CREATE TABLE IF NOT EXISTS tms_push_token (
  token_id      VARCHAR(32) PRIMARY KEY,
  driver_id     VARCHAR(32) NOT NULL,
  device_token  VARCHAR(500) NOT NULL,                  -- 极光 registrationId 或 FCM token
  platform      VARCHAR(20),                            -- ANDROID/IOS
  channel       VARCHAR(20) NOT NULL DEFAULT 'JPUSH',   -- JPUSH/FCM
  device_model  VARCHAR(100),
  app_version   VARCHAR(50),
  enabled       BOOLEAN NOT NULL DEFAULT TRUE,          -- 司机关闭通知权限时置 FALSE，不删记录
  last_active   TIMESTAMP,
  create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_tms_push_token_driver ON tms_push_token(driver_id, enabled);
CREATE INDEX IF NOT EXISTS idx_tms_push_token_token  ON tms_push_token(device_token);

-- ------------------------------------------------------------
-- 3. 推送系统参数
-- ------------------------------------------------------------
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0111', 'TMS_PUSH_ENABLED', '启用第三方推送', 'false', 'false', 'TMS配送', 'false=只写站内消息由APP轮询拉取；true=同时调用极光/FCM。未配置密钥前保持 false，否则每条消息都会留失败记录')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0112', 'TMS_PUSH_CHANNEL', '推送通道', 'JPUSH', 'JPUSH', 'TMS配送', 'JPUSH=极光推送；FCM=Google推送。国内设备用 JPUSH，海外用 FCM')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0113', 'TMS_NOTIFY_POLL_SECONDS', '司机端消息轮询间隔(秒)', '60', '60', 'TMS配送', 'APP 前台每隔多少秒拉一次未读消息。建议不低于30秒，过于频繁会显著增加司机流量与耗电')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);
