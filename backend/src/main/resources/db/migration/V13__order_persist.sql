-- ============================================
-- V13: 采购/销售订单持久化 + 补齐辅助列
-- ============================================

-- 1. 销售订单表补列（V1__schema.sql 缺失）
ALTER TABLE sales_order ADD COLUMN customer_code VARCHAR(50);
ALTER TABLE sales_order ADD COLUMN price_group_code VARCHAR(50);
ALTER TABLE sales_order ADD COLUMN expected_delivery_date DATE;
ALTER TABLE sales_order ADD COLUMN create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE sales_order ADD COLUMN remark VARCHAR(500);
CREATE INDEX idx_sales_order_customer_code ON sales_order(customer_code);
CREATE INDEX idx_sales_order_create_time ON sales_order(create_time);

-- 2. 销售订单明细补列
ALTER TABLE sales_order_detail ADD COLUMN unit_level TINYINT DEFAULT 1;
ALTER TABLE sales_order_detail ADD COLUMN convert_qty DECIMAL(18, 4) DEFAULT 1;
ALTER TABLE sales_order_detail ADD COLUMN base_qty DECIMAL(18, 4) DEFAULT 0;
ALTER TABLE sales_order_detail ADD COLUMN sales_attribute VARCHAR(20) DEFAULT '正常';
ALTER TABLE sales_order_detail ADD COLUMN remark VARCHAR(500);
CREATE INDEX idx_sales_order_detail_goods ON sales_order_detail(goods_code);

-- 3. 采购订单主表
CREATE TABLE purchase_order (
  order_id VARCHAR(32) PRIMARY KEY,
  order_no VARCHAR(50) NOT NULL UNIQUE,
  supplier_code VARCHAR(50),
  supplier_name VARCHAR(200),
  buyer VARCHAR(100),
  warehouse VARCHAR(100),
  bill_date DATE NOT NULL,
  amount DECIMAL(18, 2) DEFAULT 0,
  paid_amount DECIMAL(18, 2) DEFAULT 0,
  unpaid_amount DECIMAL(18, 2) DEFAULT 0,
  inbound_status VARCHAR(50) DEFAULT '未入库',
  payment_status VARCHAR(50) DEFAULT '未付款',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  creator_name VARCHAR(100),
  audit_info VARCHAR(200),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500)
);
CREATE INDEX idx_purchase_order_no ON purchase_order(order_no);
CREATE INDEX idx_purchase_order_supplier ON purchase_order(supplier_code);
CREATE INDEX idx_purchase_order_status ON purchase_order(status);
CREATE INDEX idx_purchase_order_date ON purchase_order(bill_date);
CREATE INDEX idx_purchase_order_create_time ON purchase_order(create_time);

CREATE TABLE purchase_order_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  order_id VARCHAR(32) NOT NULL,
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  spec VARCHAR(200),
  unit_name VARCHAR(50),
  unit_level TINYINT DEFAULT 1,
  convert_qty DECIMAL(18, 4) DEFAULT 1,
  qty DECIMAL(18, 4) DEFAULT 0,
  base_qty DECIMAL(18, 4) DEFAULT 0,
  price DECIMAL(18, 4) DEFAULT 0,
  amount DECIMAL(18, 2) DEFAULT 0,
  tax_rate VARCHAR(20),
  remark VARCHAR(500)
);
CREATE INDEX idx_purchase_order_detail_order ON purchase_order_detail(order_id);
CREATE INDEX idx_purchase_order_detail_goods ON purchase_order_detail(goods_code);
