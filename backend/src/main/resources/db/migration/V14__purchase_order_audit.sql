-- ============================================
-- V14: 采购订单表加审核字段 + 补 inbound 状态列
-- Phase 1 依赖
-- ============================================

-- purchase_order 加审核信息 & 入库回写字段（V13 建表时 inbound_status 已存在，此处仅补 audit / inbound_amount）
ALTER TABLE purchase_order ADD COLUMN audit_time TIMESTAMP;
ALTER TABLE purchase_order ADD COLUMN audit_user VARCHAR(100);
ALTER TABLE purchase_order ADD COLUMN inbound_amount DECIMAL(18, 2) DEFAULT 0;

CREATE INDEX idx_purchase_order_audit_time ON purchase_order(audit_time);
