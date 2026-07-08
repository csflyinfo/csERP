-- ============================================
-- V4: 价格组系统默认配送价 1~10 + is_system 保护 + 客户关联字段
-- ============================================

-- 价格组表：加 is_system 保护标记（系统默认记录不可新建/删除）
ALTER TABLE base_price_group ADD COLUMN is_system BOOLEAN DEFAULT FALSE;

-- 客户表：加 price_group_code，用于统计价格组关联客户数
ALTER TABLE base_customer ADD COLUMN price_group_code VARCHAR(50);

-- 系统默认价格组 10 条：配送价 1 ~ 配送价 10，默认 enabled=FALSE
INSERT INTO base_price_group (price_group_id, price_group_code, price_group_name, enabled, sort_order, remark, status, is_system)
VALUES ('PG_SYS_01', 'PG01', '配送价1', FALSE, 1, '系统默认', 'NORMAL', TRUE)
ON DUPLICATE KEY UPDATE price_group_name = VALUES(price_group_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO base_price_group (price_group_id, price_group_code, price_group_name, enabled, sort_order, remark, status, is_system)
VALUES ('PG_SYS_02', 'PG02', '配送价2', FALSE, 2, '系统默认', 'NORMAL', TRUE)
ON DUPLICATE KEY UPDATE price_group_name = VALUES(price_group_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO base_price_group (price_group_id, price_group_code, price_group_name, enabled, sort_order, remark, status, is_system)
VALUES ('PG_SYS_03', 'PG03', '配送价3', FALSE, 3, '系统默认', 'NORMAL', TRUE)
ON DUPLICATE KEY UPDATE price_group_name = VALUES(price_group_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO base_price_group (price_group_id, price_group_code, price_group_name, enabled, sort_order, remark, status, is_system)
VALUES ('PG_SYS_04', 'PG04', '配送价4', FALSE, 4, '系统默认', 'NORMAL', TRUE)
ON DUPLICATE KEY UPDATE price_group_name = VALUES(price_group_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO base_price_group (price_group_id, price_group_code, price_group_name, enabled, sort_order, remark, status, is_system)
VALUES ('PG_SYS_05', 'PG05', '配送价5', FALSE, 5, '系统默认', 'NORMAL', TRUE)
ON DUPLICATE KEY UPDATE price_group_name = VALUES(price_group_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO base_price_group (price_group_id, price_group_code, price_group_name, enabled, sort_order, remark, status, is_system)
VALUES ('PG_SYS_06', 'PG06', '配送价6', FALSE, 6, '系统默认', 'NORMAL', TRUE)
ON DUPLICATE KEY UPDATE price_group_name = VALUES(price_group_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO base_price_group (price_group_id, price_group_code, price_group_name, enabled, sort_order, remark, status, is_system)
VALUES ('PG_SYS_07', 'PG07', '配送价7', FALSE, 7, '系统默认', 'NORMAL', TRUE)
ON DUPLICATE KEY UPDATE price_group_name = VALUES(price_group_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO base_price_group (price_group_id, price_group_code, price_group_name, enabled, sort_order, remark, status, is_system)
VALUES ('PG_SYS_08', 'PG08', '配送价8', FALSE, 8, '系统默认', 'NORMAL', TRUE)
ON DUPLICATE KEY UPDATE price_group_name = VALUES(price_group_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO base_price_group (price_group_id, price_group_code, price_group_name, enabled, sort_order, remark, status, is_system)
VALUES ('PG_SYS_09', 'PG09', '配送价9', FALSE, 9, '系统默认', 'NORMAL', TRUE)
ON DUPLICATE KEY UPDATE price_group_name = VALUES(price_group_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO base_price_group (price_group_id, price_group_code, price_group_name, enabled, sort_order, remark, status, is_system)
VALUES ('PG_SYS_10', 'PG10', '配送价10', FALSE, 10, '系统默认', 'NORMAL', TRUE)
ON DUPLICATE KEY UPDATE price_group_name = VALUES(price_group_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
