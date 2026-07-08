-- ============================================
-- V22: 客户价格调整单 —— 制单人/审核人 拆分为独立字段
--
-- 业务背景：
--   原表只有 creator_info / audit_info 两个拼接字符串（形如「管理员 2026-08-05 20:30」），
--   列表无法按制单人、制单时间、审核人、审核时间分列展示与排序。
--   本次拆成 4 个独立字段，列表直接取用。
--
-- 字段说明：
--   1. creator_name / auditor_name 存「显示名」（sys_user_runtime.display_name），
--      而非登录账号，列表要给人看；账号名在操作日志里已有记录。
--   2. audit_date 单独存审核当天日期 —— 需求要求单据日期在审核后更新为审核当日，
--      但原始建单日期仍需留痕，故不覆盖 bill_date，另存一列。
--   3. 保留 creator_info / audit_info 不动（CLAUDE.md 禁止改已存在字段），
--      老数据与老接口继续可用；新字段由新代码写入。
-- ============================================

ALTER TABLE base_customer_price_adjust ADD COLUMN IF NOT EXISTS creator_name VARCHAR(100);
ALTER TABLE base_customer_price_adjust ADD COLUMN IF NOT EXISTS create_time TIMESTAMP;
ALTER TABLE base_customer_price_adjust ADD COLUMN IF NOT EXISTS auditor_name VARCHAR(100);
ALTER TABLE base_customer_price_adjust ADD COLUMN IF NOT EXISTS audit_time TIMESTAMP;

-- 审核通过后的「单据日期」：审核当日；未审核时为空
ALTER TABLE base_customer_price_adjust ADD COLUMN IF NOT EXISTS audit_date DATE;
