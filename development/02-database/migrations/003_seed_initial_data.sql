-- ERP-WMS-TMS V1.0 初始种子数据

INSERT INTO sys_user (user_id, username, password_hash, display_name, mobile, status, created_by)
VALUES ('U0001', 'admin', '{noop}admin123', '系统管理员', '13800000000', 'NORMAL', 'system')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

INSERT INTO sys_role (role_id, role_code, role_name, status, remark, created_by)
VALUES ('R0001', 'ADMIN', '管理员组', 'NORMAL', '系统内置管理员权限组', 'system')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name);

INSERT INTO sys_user_role (id, user_id, role_id)
VALUES ('UR0001', 'U0001', 'R0001')
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id);

INSERT INTO sys_param (param_id, param_key, param_name, param_value, default_value, param_group, param_type, remark)
VALUES
('P0001', 'CREDIT_CHECK_MODE', '信用控制', 'REMIND', 'REMIND', 'SALES', 'SELECT', '可选 REMIND/BLOCK/APPROVAL'),
('P0002', 'LOW_PRICE_CHECK_MODE', '低价控制', 'APPROVAL', 'APPROVAL', 'SALES', 'SELECT', '低于最低售价时授权'),
('P0003', 'STOCK_NEGATIVE_ALLOWED', '允许负库存', 'false', 'false', 'INVENTORY', 'BOOLEAN', 'V1.0默认不允许负库存')
ON DUPLICATE KEY UPDATE param_value = VALUES(param_value);

INSERT INTO sys_bill_no_rule (rule_id, bill_type, prefix, date_format, serial_length, reset_cycle, example_no, status)
VALUES
('BN001', 'CUSTOMER_PRICE_ADJUST', 'CPA', 'yyyyMMdd', 4, 'DAY', 'CPA202606140001', 'NORMAL'),
('BN002', 'SALES_ORDER', 'SO', 'yyyyMMdd', 4, 'DAY', 'SO202606140001', 'NORMAL'),
('BN003', 'PURCHASE_ORDER', 'PO', 'yyyyMMdd', 4, 'DAY', 'PO202606140001', 'NORMAL')
ON DUPLICATE KEY UPDATE prefix = VALUES(prefix);

INSERT INTO base_category (category_id, parent_id, category_code, category_name, default_tax_rate, status, created_by)
VALUES
('CATE01', NULL, '01', '食品饮料', 13.0000, 'NORMAL', 'system'),
('CATE0101', 'CATE01', '0101', '饮料', 13.0000, 'NORMAL', 'system'),
('CATE010101', 'CATE0101', '010101', '瓶装水', 13.0000, 'NORMAL', 'system')
ON DUPLICATE KEY UPDATE category_name = VALUES(category_name);

INSERT INTO base_unit (unit_id, unit_code, unit_name, can_base_unit, can_middle_unit, can_large_unit, status)
VALUES
('UNIT001', 'U001', '瓶', 1, 0, 0, 'NORMAL'),
('UNIT002', 'U002', '箱', 1, 0, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE unit_name = VALUES(unit_name);

INSERT INTO base_brand (brand_id, brand_code, brand_name, simple_code, status)
VALUES ('BR001', 'B001', '农夫山泉', 'NFSQ', 'NORMAL')
ON DUPLICATE KEY UPDATE brand_name = VALUES(brand_name);

INSERT INTO base_customer (customer_id, customer_code, customer_name, customer_level, contact_name, mobile, settlement_type, credit_limit, status)
VALUES ('CUS001', 'C001', '华联超市', '金牌', '王店长', '13888888888', 'MONTHLY', 50000.00, 'NORMAL')
ON DUPLICATE KEY UPDATE customer_name = VALUES(customer_name);

INSERT INTO base_goods (goods_id, goods_code, goods_name, spec, category_id, brand_id, base_unit_id, barcode, standard_price, reference_purchase_price, min_sale_price, status)
VALUES
('G001', 'SP001', '农夫山泉500ml*24', '500ml*24', 'CATE010101', 'BR001', 'UNIT001', '6941410749551', 35.00, 31.20, 30.00, 'NORMAL'),
('G002', 'SP002', '康师傅红烧牛肉面', '1*12', 'CATE01', NULL, 'UNIT002', '690000000002', 48.00, 42.50, 40.00, 'NORMAL')
ON DUPLICATE KEY UPDATE goods_name = VALUES(goods_name);
