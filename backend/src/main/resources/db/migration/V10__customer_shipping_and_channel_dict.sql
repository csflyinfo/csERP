-- ============================================
-- V10: 客户资料补充字段 + 客户渠道字典 seed
-- ============================================

-- 1. 客户表加收货地址（默认收货地址；多地址暂放到 V2.0 独立子表）
ALTER TABLE base_customer ADD COLUMN shipping_address VARCHAR(500);

-- 2. 客户渠道字典 seed（customer_channel 类型在 V7 已建，这里补预置值）
INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('CC_0', 'customer_channel', '0', '零售商超', 1, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_code = VALUES(dict_code), dict_name = VALUES(dict_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('CC_1', 'customer_channel', '1', '便利店', 2, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_code = VALUES(dict_code), dict_name = VALUES(dict_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('CC_2', 'customer_channel', '2', '餐饮店', 3, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_code = VALUES(dict_code), dict_name = VALUES(dict_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('CC_3', 'customer_channel', '3', '批发商', 4, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_code = VALUES(dict_code), dict_name = VALUES(dict_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('CC_4', 'customer_channel', '4', '电商平台', 5, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_code = VALUES(dict_code), dict_name = VALUES(dict_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
