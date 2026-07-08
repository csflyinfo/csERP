-- ============================================
-- V6: 供应商模块优化
-- 1. base_supplier 加交货方式 + 默认物流公司；invoice_title / tax_no 保留（不删列，只在前端隐藏）
-- 2. 新增 base_supplier_bank_account 子表，一供应商多账户
-- 3. 新增 sys_dictionary 字典表 + 3 条交货方式 seed
-- ============================================

-- 1. 供应商加字段
ALTER TABLE base_supplier ADD COLUMN delivery_method VARCHAR(50) DEFAULT '送货上门';
ALTER TABLE base_supplier ADD COLUMN default_logistics_company VARCHAR(100);

-- 2. 供应商收款账户子表
CREATE TABLE base_supplier_bank_account (
  id VARCHAR(32) PRIMARY KEY,
  supplier_code VARCHAR(50) NOT NULL,
  account_name VARCHAR(100) NOT NULL,
  bank_name VARCHAR(100),
  bank_account VARCHAR(100) NOT NULL,
  branch VARCHAR(100),
  is_default TINYINT(1) DEFAULT 0,
  remark VARCHAR(200),
  sort_order INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_supplier_bank_supplier ON base_supplier_bank_account(supplier_code);

-- 3. 字典表
CREATE TABLE sys_dictionary (
  id VARCHAR(32) PRIMARY KEY,
  dict_type VARCHAR(50) NOT NULL,
  dict_code VARCHAR(50) NOT NULL,
  dict_name VARCHAR(100) NOT NULL,
  sort_order INT DEFAULT 0,
  is_system TINYINT(1) DEFAULT 0,
  remark VARCHAR(200),
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_dict_type_code ON sys_dictionary(dict_type, dict_code);
CREATE INDEX idx_dict_type ON sys_dictionary(dict_type);

-- 3.1 交货方式 seed
INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('DM_01', 'delivery_method', 'DELIVERY_TO_DOOR', '送货上门', 1, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('DM_02', 'delivery_method', 'PICKUP_FACTORY', '到厂自提', 2, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('DM_03', 'delivery_method', 'PICKUP_STATION', '物流站自提', 3, 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name), sort_order = VALUES(sort_order), is_system = VALUES(is_system);
