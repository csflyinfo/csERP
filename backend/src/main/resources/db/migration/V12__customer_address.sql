-- ============================================
-- V12: 客户地址子表（一客户多收货地址）
-- V1.0 用 base_customer.shipping_address 单字段承载「默认收货地址」；
-- V2.0 拆到独立子表 base_customer_address，支持多地址、默认地址、经纬度、省市区。
-- 兼容策略：base_customer.shipping_address/longitude/latitude 保留，作为「主地址」冗余。
--   保存客户抽屉时，主地址回写到 base_customer 三列；其它地址落 base_customer_address。
-- ============================================

CREATE TABLE base_customer_address (
  address_id VARCHAR(32) PRIMARY KEY,
  customer_code VARCHAR(50) NOT NULL,
  address_name VARCHAR(100),
  contact_name VARCHAR(50),
  contact_mobile VARCHAR(30),
  province VARCHAR(50),
  city VARCHAR(50),
  district VARCHAR(50),
  detail_address VARCHAR(500) NOT NULL,
  longitude DECIMAL(11, 7),
  latitude DECIMAL(10, 7),
  is_default TINYINT(1) DEFAULT 0,
  sort_order INT DEFAULT 0,
  remark VARCHAR(200),
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_customer_addr_code ON base_customer_address(customer_code);
CREATE INDEX idx_customer_addr_default ON base_customer_address(customer_code, is_default);
