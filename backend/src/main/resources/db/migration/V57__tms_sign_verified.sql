-- V57: 签收核销字段扩展（P5-2）
-- 替代原来在 remark 字段追加文本标记的方式，改用独立字段记录核销状态。

-- 签收记录表增加核销字段
ALTER TABLE tms_sign_record ADD COLUMN IF NOT EXISTS verified VARCHAR(20) DEFAULT 'PENDING';
ALTER TABLE tms_sign_record ADD COLUMN IF NOT EXISTS verified_at TIMESTAMP;
ALTER TABLE tms_sign_record ADD COLUMN IF NOT EXISTS verified_by VARCHAR(100);

-- 核销状态索引（ERP 端筛选未核销列表用）
CREATE INDEX IF NOT EXISTS idx_sign_record_verified ON tms_sign_record(verified);
