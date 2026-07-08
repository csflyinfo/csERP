-- V46: transfer_apply 增加调拨状态（未执行/已调出/已完成）
ALTER TABLE transfer_apply ADD COLUMN IF NOT EXISTS transfer_status VARCHAR(20) DEFAULT '未执行';
