-- ============================================
-- V8: 字典值编码规范化 - 系统预设按顺序号从 0 开始
-- ============================================

-- 先删旧的三条系统预设值（大写英文编码）
DELETE FROM sys_dictionary WHERE id IN ('DM_01', 'DM_02', 'DM_03');

-- 重新插入：编码从 0 开始
INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('DM_01', 'delivery_method', '0', '送货上门', 1, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_code = VALUES(dict_code), dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('DM_02', 'delivery_method', '1', '到厂自提', 2, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_code = VALUES(dict_code), dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('DM_03', 'delivery_method', '2', '物流站自提', 3, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_code = VALUES(dict_code), dict_name = VALUES(dict_name);
