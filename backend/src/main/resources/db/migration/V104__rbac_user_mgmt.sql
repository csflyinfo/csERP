-- ============================================================
-- V104: RBAC 用户管理配套（PRD-28 卡片5）
--   1) sys_user_runtime 增加备注列（用户抽屉「备注」）
--   2) 历史密码表 sys_pwd_history：改密/重置时校验「不能与最近 3 次相同」（§8.4）
--   3) 把存量账号当前密码回填为第一条历史，避免上线后改密立刻与「当前密码」撞校验
--   仅加结构/数据，不删旧列；幂等可重复执行。
-- ============================================================

ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS remark VARCHAR(500);

CREATE TABLE IF NOT EXISTS sys_pwd_history (
  history_id  VARCHAR(32) PRIMARY KEY,
  user_id     VARCHAR(32) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pwdhist_user ON sys_pwd_history(user_id, created_at);

-- 存量账号当前哈希回填（只在该用户无任何历史时写一条），历史保留最近 3 条由应用层裁剪
INSERT INTO sys_pwd_history(history_id, user_id, password_hash, created_at)
SELECT 'PH' || SUBSTRING(REPLACE(CAST(RANDOM_UUID() AS VARCHAR), '-', ''), 1, 14),
       u.user_id, u.password, COALESCE(u.pwd_update_time, CURRENT_TIMESTAMP)
FROM sys_user_runtime u
WHERE u.password IS NOT NULL AND u.password <> ''
  AND NOT EXISTS (SELECT 1 FROM sys_pwd_history h WHERE h.user_id = u.user_id);
