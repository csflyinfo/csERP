-- ============================================
-- V7: 用户数据字典模块
-- 1. sys_dictionary_type 字典类型主表（V6 建的 sys_dictionary 是「字典值」表）
-- 2. 补充默认字典类型 seed
-- ============================================

CREATE TABLE sys_dictionary_type (
  id VARCHAR(32) PRIMARY KEY,
  dict_type VARCHAR(50) NOT NULL UNIQUE,
  dict_type_name VARCHAR(100) NOT NULL,
  description VARCHAR(300),
  is_system TINYINT(1) DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_dict_type_name ON sys_dictionary_type(dict_type_name);

-- 预置字典类型
INSERT INTO sys_dictionary_type (id, dict_type, dict_type_name, description, is_system, status)
VALUES ('DT_01', 'delivery_method', '交货方式', '供应商交货方式：送货上门/到厂自提/物流站自提', 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_type_name = VALUES(dict_type_name), description = VALUES(description), is_system = VALUES(is_system);

INSERT INTO sys_dictionary_type (id, dict_type, dict_type_name, description, is_system, status)
VALUES ('DT_02', 'logistics_company', '物流公司', '供应商默认物流公司', 1, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_type_name = VALUES(dict_type_name), description = VALUES(description), is_system = VALUES(is_system);

INSERT INTO sys_dictionary_type (id, dict_type, dict_type_name, description, is_system, status)
VALUES ('DT_03', 'customer_channel', '客户渠道', '客户渠道分类', 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_type_name = VALUES(dict_type_name), description = VALUES(description), is_system = VALUES(is_system);

INSERT INTO sys_dictionary_type (id, dict_type, dict_type_name, description, is_system, status)
VALUES ('DT_04', 'supplier_type', '供应商类型', '供应商分类：普通/核心/临时', 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_type_name = VALUES(dict_type_name), description = VALUES(description), is_system = VALUES(is_system);

INSERT INTO sys_dictionary_type (id, dict_type, dict_type_name, description, is_system, status)
VALUES ('DT_05', 'settlement_method', '结算方式', '现结/月结30/月结60/货到付款', 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_type_name = VALUES(dict_type_name), description = VALUES(description), is_system = VALUES(is_system);
