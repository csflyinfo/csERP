-- ============================================
-- V11: 客户表加经度、纬度（收货地址地图坐标）
-- ============================================

ALTER TABLE base_customer ADD COLUMN longitude DECIMAL(11, 7);
ALTER TABLE base_customer ADD COLUMN latitude DECIMAL(10, 7);
