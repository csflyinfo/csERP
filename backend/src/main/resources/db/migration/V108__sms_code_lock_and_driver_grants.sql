-- ============================================================
-- V108：司机短信登录配套（PRD-28 卡片10）
--   1. sys_sms_code 增失败计数/锁定时间（V102 仅建了验证码主表）：
--      5 分钟有效、60 秒限频、每手机号每日 10 条由服务端控制；
--      同一验证码连续校验失败 5 次锁手机号 15 分钟。
--   2. 司机三内置角色（TMS_DRIVER/TMS_LOADER/TMS_LEADER）的菜单/功能点
--      授权不写死在 SQL 里——由 PermissionRegistry.DRIVER_ROLE_FUNCS
--      启动幂等对账（与卡片9 PDA 六角色同一机制），代码矩阵是唯一事实来源。
-- ============================================================

ALTER TABLE sys_sms_code ADD COLUMN IF NOT EXISTS fail_count INT DEFAULT 0;
ALTER TABLE sys_sms_code ADD COLUMN IF NOT EXISTS lock_until TIMESTAMP;
ALTER TABLE sys_sms_code ADD COLUMN IF NOT EXISTS send_ip VARCHAR(50);
