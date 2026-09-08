-- ============================================================
-- V92 操作日志统一管理与审计追溯（PRD-31）
--   1. sys_operation_log_runtime 补审计字段（操作人/IP/单据类型/改前改后/敏感标记等）
--   2. 新建 sys_login_log 登录日志表
--   3. 播种日志保留期/开关参数 P0179~P0182
-- 全部幂等（IF NOT EXISTS），可在空库/存量库重复执行。
-- ============================================================

-- ---------- 1. 操作日志表补列 ----------
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS operator_id       VARCHAR(32);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS operator_account  VARCHAR(50);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS module_name       VARCHAR(100);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS action_name       VARCHAR(50);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS biz_type          VARCHAR(50);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS biz_id            VARCHAR(32);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS request_ip        VARCHAR(50);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS request_url       VARCHAR(500);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS request_method    VARCHAR(10);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS cost_time_ms      INT DEFAULT 0;
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS fail_reason       VARCHAR(500);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS sensitive         VARCHAR(1) DEFAULT 'N';
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS before_value      CLOB;
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS after_value       CLOB;
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS operation_content VARCHAR(2000);

CREATE INDEX IF NOT EXISTS idx_log_biz       ON sys_operation_log_runtime(biz_type, biz_no);
CREATE INDEX IF NOT EXISTS idx_log_operator  ON sys_operation_log_runtime(operator_account);
CREATE INDEX IF NOT EXISTS idx_log_sensitive ON sys_operation_log_runtime(sensitive);

-- ---------- 2. 登录日志表 ----------
CREATE TABLE IF NOT EXISTS sys_login_log (
  login_log_id VARCHAR(32) PRIMARY KEY,
  user_id      VARCHAR(32),
  account      VARCHAR(50),
  user_name    VARCHAR(100),
  login_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  login_result VARCHAR(10),
  fail_reason  VARCHAR(500),
  ip           VARCHAR(50),
  user_agent   VARCHAR(500),
  app_type     VARCHAR(10) DEFAULT 'ERP',
  logout_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_login_log_at      ON sys_login_log(login_at);
CREATE INDEX IF NOT EXISTS idx_login_log_account ON sys_login_log(account);

-- ---------- 3. 日志保留期 / 开关参数（P0179~P0182，组：日志与安全） ----------
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0179','OP_LOG_RETENTION_DAYS','操作日志保留天数','180','180','日志与安全','NUMBER',
  NULL,1,30,3650,'天',
  '操作日志自动清理保留天数，超期记录每日凌晨由后台任务删除；0 或为空按默认 180 天'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='OP_LOG_RETENTION_DAYS');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0180','LOGIN_LOG_RETENTION_DAYS','登录日志保留天数','180','180','日志与安全','NUMBER',
  NULL,2,30,3650,'天',
  '登录日志自动清理保留天数，超期记录每日凌晨由后台任务删除'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='LOGIN_LOG_RETENTION_DAYS');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0181','OP_LOG_ENABLE_DETAIL_VIEW','记录单据详情查看','0','0','日志与安全','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',3,NULL,NULL,NULL,
  '1=用户打开业务单据详情时记录“查看”日志；0=不记录。客户价格、成本调整等敏感模块始终记录'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='OP_LOG_ENABLE_DETAIL_VIEW');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0182','OP_LOG_SLOW_THRESHOLD_MS','慢操作阈值','3000','3000','日志与安全','NUMBER',
  NULL,4,0,600000,'毫秒',
  '预留：操作耗时超过该阈值时在日志详情中标注慢操作'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='OP_LOG_SLOW_THRESHOLD_MS');
