CREATE TABLE IF NOT EXISTS base_category (
  category_id VARCHAR(32) PRIMARY KEY,
  parent_id VARCHAR(32),
  parent_code VARCHAR(50),
  category_code VARCHAR(50) NOT NULL UNIQUE,
  category_name VARCHAR(100) NOT NULL,
  default_tax_rate VARCHAR(20),
  goods_count INT DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS base_unit (
  unit_id VARCHAR(32) PRIMARY KEY,
  unit_code VARCHAR(50) NOT NULL UNIQUE,
  unit_name VARCHAR(100) NOT NULL,
  can_base_unit BOOLEAN DEFAULT TRUE,
  can_middle_unit BOOLEAN DEFAULT FALSE,
  can_large_unit BOOLEAN DEFAULT FALSE,
  goods_count INT DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
);

CREATE TABLE IF NOT EXISTS base_brand (
  brand_id VARCHAR(32) PRIMARY KEY,
  brand_code VARCHAR(50) NOT NULL UNIQUE,
  brand_name VARCHAR(100) NOT NULL,
  simple_code VARCHAR(50),
  goods_count INT DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
);

CREATE TABLE IF NOT EXISTS base_warehouse (
  warehouse_id VARCHAR(32) PRIMARY KEY,
  warehouse_code VARCHAR(50) NOT NULL UNIQUE,
  warehouse_name VARCHAR(100) NOT NULL,
  warehouse_type VARCHAR(50),
  inventory_type VARCHAR(50),
  cost_group VARCHAR(50),
  manager_name VARCHAR(100),
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
);

CREATE TABLE IF NOT EXISTS base_goods (
  goods_id VARCHAR(32) PRIMARY KEY,
  goods_code VARCHAR(50) NOT NULL UNIQUE,
  goods_name VARCHAR(200) NOT NULL,
  spec VARCHAR(200),
  category_name VARCHAR(100),
  brand_name VARCHAR(100),
  base_unit VARCHAR(50),
  barcode VARCHAR(100),
  standard_price DECIMAL(18,2) DEFAULT 0,
  latest_purchase_price DECIMAL(18,2) DEFAULT 0,
  min_sale_price DECIMAL(18,2) DEFAULT 0,
  goods_type VARCHAR(50) DEFAULT '正常商品',
  shelf_life_days INT DEFAULT 0,
  storage_property VARCHAR(50) DEFAULT '常温',
  suggested_retail_price DECIMAL(18,2) DEFAULT 0,
  stock_upper_limit DECIMAL(18,2) DEFAULT 0,
  stock_lower_limit DECIMAL(18,2) DEFAULT 0,
  default_supplier VARCHAR(100),
  default_warehouse VARCHAR(100),
  can_return BOOLEAN DEFAULT TRUE,
  current_stock DECIMAL(18,2) DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
);

ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS goods_type VARCHAR(50) DEFAULT '正常商品';
ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS shelf_life_days INT DEFAULT 0;
ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS storage_property VARCHAR(50) DEFAULT '常温';
ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS suggested_retail_price DECIMAL(18,2) DEFAULT 0;
ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS stock_upper_limit DECIMAL(18,2) DEFAULT 0;
ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS stock_lower_limit DECIMAL(18,2) DEFAULT 0;
ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS default_supplier VARCHAR(100);
ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS default_warehouse VARCHAR(100);
ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS can_return BOOLEAN DEFAULT TRUE;

CREATE TABLE IF NOT EXISTS base_customer (
  customer_id VARCHAR(32) PRIMARY KEY,
  customer_code VARCHAR(50) NOT NULL UNIQUE,
  customer_name VARCHAR(100) NOT NULL,
  channel_type VARCHAR(50),
  contact_name VARCHAR(100),
  mobile VARCHAR(30),
  territory VARCHAR(100),
  route_line VARCHAR(100),
  salesman VARCHAR(100),
  customer_level VARCHAR(50),
  account_period_type VARCHAR(50),
  cutoff_day VARCHAR(30),
  payment_day VARCHAR(30),
  credit_limit DECIMAL(18,2) DEFAULT 0,
  ar_balance DECIMAL(18,2) DEFAULT 0,
  overdue_amount DECIMAL(18,2) DEFAULT 0,
  invoice_title VARCHAR(200),
  tax_no VARCHAR(100),
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
);

CREATE TABLE IF NOT EXISTS base_supplier (
  supplier_id VARCHAR(32) PRIMARY KEY,
  supplier_code VARCHAR(50) NOT NULL UNIQUE,
  supplier_name VARCHAR(100) NOT NULL,
  short_name VARCHAR(100),
  supplier_type VARCHAR(50),
  contact_name VARCHAR(100),
  phone VARCHAR(30),
  delivery_days INT DEFAULT 0,
  settlement_method VARCHAR(50),
  account_period_days INT DEFAULT 0,
  default_buyer VARCHAR(100),
  default_receipt_account VARCHAR(200),
  invoice_title VARCHAR(200),
  tax_no VARCHAR(100),
  ap_balance DECIMAL(18,2) DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
);
ALTER TABLE base_supplier ADD COLUMN IF NOT EXISTS address VARCHAR(500);
ALTER TABLE base_supplier ADD COLUMN IF NOT EXISTS remark VARCHAR(500);

CREATE TABLE IF NOT EXISTS base_customer_price_adjust (
  adjust_id VARCHAR(32) PRIMARY KEY,
  adjust_no VARCHAR(50) NOT NULL UNIQUE,
  customer_code VARCHAR(50) NOT NULL,
  customer_name VARCHAR(100) NOT NULL,
  bill_date DATE NOT NULL,
  effective_mode VARCHAR(20) NOT NULL,
  effective_time TIMESTAMP,
  valid_range VARCHAR(100),
  detail_count INT DEFAULT 0,
  creator_info VARCHAR(200),
  audit_info VARCHAR(200),
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  remark VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS base_customer_price_adjust_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  adjust_id VARCHAR(32) NOT NULL,
  goods_code VARCHAR(50) NOT NULL,
  goods_name VARCHAR(200) NOT NULL,
  base_unit VARCHAR(50),
  spec VARCHAR(200),
  barcode VARCHAR(100),
  original_price DECIMAL(18,2) NOT NULL,
  current_price DECIMAL(18,2) NOT NULL,
  latest_purchase_price DECIMAL(18,2),
  cost_price DECIMAL(18,2)
);

CREATE TABLE IF NOT EXISTS base_customer_price (
  price_id VARCHAR(32) PRIMARY KEY,
  adjust_no VARCHAR(50) NOT NULL,
  customer_code VARCHAR(50) NOT NULL,
  customer_name VARCHAR(100) NOT NULL,
  goods_code VARCHAR(50) NOT NULL,
  goods_name VARCHAR(200) NOT NULL,
  base_unit VARCHAR(50),
  spec VARCHAR(200),
  barcode VARCHAR(100),
  original_price DECIMAL(18,2) NOT NULL,
  current_price DECIMAL(18,2) NOT NULL,
  latest_purchase_price DECIMAL(18,2),
  cost_price DECIMAL(18,2),
  effective_mode VARCHAR(20),
  valid_range VARCHAR(100),
  effective_status VARCHAR(20) NOT NULL DEFAULT 'EFFECTIVE'
);

CREATE TABLE IF NOT EXISTS inv_stock_balance (
  balance_id VARCHAR(32) PRIMARY KEY,
  goods_code VARCHAR(50) NOT NULL,
  goods_name VARCHAR(200) NOT NULL,
  warehouse VARCHAR(100) NOT NULL,
  batch_no VARCHAR(100),
  physical_qty DECIMAL(18,2) DEFAULT 0,
  locked_qty DECIMAL(18,2) DEFAULT 0,
  frozen_qty DECIMAL(18,2) DEFAULT 0,
  available_qty DECIMAL(18,2) DEFAULT 0,
  purchase_on_way DECIMAL(18,2) DEFAULT 0,
  cost_price DECIMAL(18,2) DEFAULT 0,
  stock_amount DECIMAL(18,2) DEFAULT 0,
  last_inout_time TIMESTAMP
);

CREATE TABLE IF NOT EXISTS inv_stock_ledger (
  ledger_id VARCHAR(32) PRIMARY KEY,
  ledger_no VARCHAR(50) NOT NULL UNIQUE,
  occurred_at TIMESTAMP NOT NULL,
  source_bill VARCHAR(50) NOT NULL,
  goods_code VARCHAR(50) NOT NULL,
  goods_name VARCHAR(200) NOT NULL,
  warehouse VARCHAR(100) NOT NULL,
  batch_no VARCHAR(100),
  direction VARCHAR(20) NOT NULL,
  qty DECIMAL(18,2) NOT NULL,
  cost_price DECIMAL(18,2) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  balance_qty DECIMAL(18,2),
  operator_name VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS pur_order (
  order_id VARCHAR(32) PRIMARY KEY,
  order_no VARCHAR(50) NOT NULL UNIQUE,
  supplier VARCHAR(100) NOT NULL,
  buyer VARCHAR(100),
  warehouse VARCHAR(100),
  bill_date DATE NOT NULL,
  amount DECIMAL(18,2) DEFAULT 0,
  inbound_amount DECIMAL(18,2) DEFAULT 0,
  payment_status VARCHAR(50),
  arrival_status VARCHAR(50),
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  creator_info VARCHAR(200),
  owner_name VARCHAR(100),
  expected_arrival_date DATE,
  settlement_method VARCHAR(50),
  cost_amount DECIMAL(18,2) DEFAULT 0,
  audit_info VARCHAR(200)
);

ALTER TABLE pur_order ADD COLUMN IF NOT EXISTS owner_name VARCHAR(100);
ALTER TABLE pur_order ADD COLUMN IF NOT EXISTS expected_arrival_date DATE;
ALTER TABLE pur_order ADD COLUMN IF NOT EXISTS settlement_method VARCHAR(50);
ALTER TABLE pur_order ADD COLUMN IF NOT EXISTS cost_amount DECIMAL(18,2) DEFAULT 0;
ALTER TABLE pur_order ADD COLUMN IF NOT EXISTS audit_info VARCHAR(200);

CREATE TABLE IF NOT EXISTS pur_order_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  order_id VARCHAR(32) NOT NULL,
  line_type VARCHAR(50) DEFAULT '正常',
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  unit_name VARCHAR(50),
  qty DECIMAL(18,2) DEFAULT 0,
  price DECIMAL(18,2) DEFAULT 0,
  tax_rate VARCHAR(20),
  amount DECIMAL(18,2) DEFAULT 0,
  cost_price DECIMAL(18,2) DEFAULT 0,
  cost_amount DECIMAL(18,2) DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sales_order (
  order_id VARCHAR(32) PRIMARY KEY,
  order_no VARCHAR(50) NOT NULL UNIQUE,
  customer VARCHAR(100) NOT NULL,
  salesman VARCHAR(100),
  warehouse VARCHAR(100),
  bill_date DATE NOT NULL,
  amount DECIMAL(18,2) DEFAULT 0,
  paid_amount DECIMAL(18,2) DEFAULT 0,
  unpaid_amount DECIMAL(18,2) DEFAULT 0,
  credit_check VARCHAR(50),
  stock_check VARCHAR(50),
  outbound_status VARCHAR(50),
  sign_status VARCHAR(50),
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  line_type VARCHAR(50) DEFAULT '正常',
  cost_amount DECIMAL(18,2) DEFAULT 0,
  creator_name VARCHAR(100),
  audit_info VARCHAR(200)
);

ALTER TABLE sales_order ADD COLUMN IF NOT EXISTS line_type VARCHAR(50) DEFAULT '正常';
ALTER TABLE sales_order ADD COLUMN IF NOT EXISTS cost_amount DECIMAL(18,2) DEFAULT 0;
ALTER TABLE sales_order ADD COLUMN IF NOT EXISTS creator_name VARCHAR(100);
ALTER TABLE sales_order ADD COLUMN IF NOT EXISTS audit_info VARCHAR(200);

CREATE TABLE IF NOT EXISTS sales_order_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  order_id VARCHAR(32) NOT NULL,
  line_type VARCHAR(50) DEFAULT '正常',
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  unit_name VARCHAR(50),
  qty DECIMAL(18,2) DEFAULT 0,
  price DECIMAL(18,2) DEFAULT 0,
  discount_rate VARCHAR(20),
  tax_rate VARCHAR(20),
  amount DECIMAL(18,2) DEFAULT 0,
  cost_price DECIMAL(18,2) DEFAULT 0,
  cost_amount DECIMAL(18,2) DEFAULT 0
);

CREATE TABLE IF NOT EXISTS pur_inbound (
  inbound_id VARCHAR(32) PRIMARY KEY,
  inbound_no VARCHAR(50) NOT NULL UNIQUE,
  source_order VARCHAR(50),
  supplier VARCHAR(100),
  warehouse VARCHAR(100),
  bill_date DATE NOT NULL,
  qty DECIMAL(18,2) DEFAULT 0,
  amount DECIMAL(18,2) DEFAULT 0,
  status VARCHAR(20) DEFAULT 'PENDING',
  stock_updated BOOLEAN DEFAULT FALSE,
  receipt_generated BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS pur_inbound_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  inbound_id VARCHAR(32) NOT NULL,
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  warehouse VARCHAR(100),
  unit_name VARCHAR(50),
  expected_qty DECIMAL(18,2) DEFAULT 0,
  received_qty DECIMAL(18,2) DEFAULT 0,
  batch_no VARCHAR(100),
  production_date DATE,
  expiry_date DATE,
  price DECIMAL(18,2) DEFAULT 0,
  amount DECIMAL(18,2) DEFAULT 0,
  before_cost DECIMAL(18,2) DEFAULT 0,
  after_cost DECIMAL(18,2) DEFAULT 0,
  allocated_expense DECIMAL(18,2) DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sales_outbound (
  outbound_id VARCHAR(32) PRIMARY KEY,
  outbound_no VARCHAR(50) NOT NULL UNIQUE,
  source_order VARCHAR(50),
  customer VARCHAR(100),
  warehouse VARCHAR(100),
  bill_date DATE NOT NULL,
  qty DECIMAL(18,2) DEFAULT 0,
  amount DECIMAL(18,2) DEFAULT 0,
  cost_amount DECIMAL(18,2) DEFAULT 0,
  status VARCHAR(20) DEFAULT 'PENDING',
  stock_updated BOOLEAN DEFAULT FALSE,
  receipt_generated BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sales_outbound_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  outbound_id VARCHAR(32) NOT NULL,
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  warehouse VARCHAR(100),
  unit_name VARCHAR(50),
  qty DECIMAL(18,2) DEFAULT 0,
  batch_no VARCHAR(100),
  price DECIMAL(18,2) DEFAULT 0,
  amount DECIMAL(18,2) DEFAULT 0,
  cost_price DECIMAL(18,2) DEFAULT 0,
  cost_amount DECIMAL(18,2) DEFAULT 0
);

CREATE TABLE IF NOT EXISTS fin_ar (
  ar_id VARCHAR(32) PRIMARY KEY,
  ar_no VARCHAR(50) NOT NULL UNIQUE,
  source_bill VARCHAR(50) NOT NULL,
  customer VARCHAR(100) NOT NULL,
  salesman VARCHAR(100),
  ar_amount DECIMAL(18,2) DEFAULT 0,
  received_amount DECIMAL(18,2) DEFAULT 0,
  unreceived_amount DECIMAL(18,2) DEFAULT 0,
  due_date DATE,
  overdue_days INT DEFAULT 0,
  invoice_status VARCHAR(50),
  status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
);

CREATE TABLE IF NOT EXISTS fin_ap (
  ap_id VARCHAR(32) PRIMARY KEY,
  ap_no VARCHAR(50) NOT NULL UNIQUE,
  source_bill VARCHAR(50) NOT NULL,
  supplier VARCHAR(100) NOT NULL,
  ap_amount DECIMAL(18,2) DEFAULT 0,
  paid_amount DECIMAL(18,2) DEFAULT 0,
  unpaid_amount DECIMAL(18,2) DEFAULT 0,
  due_date DATE,
  status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
);

CREATE TABLE IF NOT EXISTS fin_fund_ledger (
  ledger_id VARCHAR(32) PRIMARY KEY,
  ledger_no VARCHAR(50) NOT NULL UNIQUE,
  fund_account VARCHAR(100) NOT NULL,
  direction VARCHAR(20) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  source_bill VARCHAR(50) NOT NULL,
  balance_after DECIMAL(18,2) NOT NULL,
  occurred_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  operator_name VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS fin_receipt_bill (
  receipt_id VARCHAR(32) PRIMARY KEY,
  receipt_no VARCHAR(50) NOT NULL UNIQUE,
  object_name VARCHAR(100) NOT NULL,
  fund_account VARCHAR(100) NOT NULL,
  amount DECIMAL(18,2) DEFAULT 0,
  verified_amount DECIMAL(18,2) DEFAULT 0,
  source_ar_no VARCHAR(50),
  remark VARCHAR(500),
  status VARCHAR(20) DEFAULT 'PENDING',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS fin_payment_bill (
  payment_id VARCHAR(32) PRIMARY KEY,
  payment_no VARCHAR(50) NOT NULL UNIQUE,
  object_name VARCHAR(100) NOT NULL,
  fund_account VARCHAR(100) NOT NULL,
  amount DECIMAL(18,2) DEFAULT 0,
  verified_amount DECIMAL(18,2) DEFAULT 0,
  source_ap_no VARCHAR(50),
  remark VARCHAR(500),
  status VARCHAR(20) DEFAULT 'PENDING',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS biz_simple_bill (
  bill_id VARCHAR(32) PRIMARY KEY,
  bill_type VARCHAR(50) NOT NULL,
  bill_no VARCHAR(50) NOT NULL UNIQUE,
  object_name VARCHAR(100),
  warehouse VARCHAR(100),
  reason VARCHAR(200),
  amount DECIMAL(18,2) DEFAULT 0,
  qty DECIMAL(18,2) DEFAULT 0,
  goods_code VARCHAR(50),
  goods_name VARCHAR(200),
  status VARCHAR(20) DEFAULT 'PENDING',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS fin_expense_bill (
  expense_id VARCHAR(32) PRIMARY KEY,
  expense_no VARCHAR(50) NOT NULL UNIQUE,
  direction VARCHAR(20) NOT NULL,
  expense_type VARCHAR(100) NOT NULL,
  object_name VARCHAR(100),
  amount DECIMAL(18,2) DEFAULT 0,
  tax_amount DECIMAL(18,2) DEFAULT 0,
  relation_generated BOOLEAN DEFAULT FALSE,
  direct_payment BOOLEAN DEFAULT FALSE,
  status VARCHAR(20) DEFAULT 'PENDING'
);

CREATE TABLE IF NOT EXISTS sys_user_runtime (
  user_id VARCHAR(32) PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  display_name VARCHAR(100) NOT NULL,
  password VARCHAR(100) DEFAULT 'admin123',
  mobile VARCHAR(30),
  role_name VARCHAR(100),
  data_scope VARCHAR(100),
  status VARCHAR(20) DEFAULT 'NORMAL'
);

CREATE TABLE IF NOT EXISTS sys_role_runtime (
  role_id VARCHAR(32) PRIMARY KEY,
  role_code VARCHAR(50) NOT NULL UNIQUE,
  role_name VARCHAR(100) NOT NULL,
  user_count INT DEFAULT 0,
  menu_scope VARCHAR(200),
  field_scope VARCHAR(200),
  data_scope VARCHAR(100) DEFAULT 'ALL',
  status VARCHAR(20) DEFAULT 'NORMAL'
);
ALTER TABLE sys_role_runtime ADD COLUMN IF NOT EXISTS data_scope VARCHAR(100) DEFAULT 'ALL';

CREATE TABLE IF NOT EXISTS sys_param_runtime (
  param_id VARCHAR(32) PRIMARY KEY,
  param_key VARCHAR(100) NOT NULL UNIQUE,
  param_name VARCHAR(100) NOT NULL,
  param_value VARCHAR(200),
  default_value VARCHAR(200),
  param_group VARCHAR(50),
  remark VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS sys_bill_no_rule_runtime (
  rule_id VARCHAR(32) PRIMARY KEY,
  bill_type VARCHAR(100) NOT NULL UNIQUE,
  prefix VARCHAR(20) NOT NULL,
  date_format VARCHAR(30) NOT NULL,
  serial_length INT DEFAULT 4,
  reset_cycle VARCHAR(20),
  example_no VARCHAR(100),
  status VARCHAR(20) DEFAULT 'NORMAL'
);

CREATE TABLE IF NOT EXISTS sys_operation_log_runtime (
  log_id VARCHAR(32) PRIMARY KEY,
  operate_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  operator_name VARCHAR(100),
  module_code VARCHAR(100),
  action VARCHAR(50),
  biz_no VARCHAR(100),
  result VARCHAR(20),
  detail VARCHAR(1000)
);

CREATE TABLE IF NOT EXISTS sys_export_task_runtime (
  task_id VARCHAR(32) PRIMARY KEY,
  task_no VARCHAR(50) NOT NULL UNIQUE,
  report_name VARCHAR(100),
  module_code VARCHAR(100),
  filter_text VARCHAR(1000),
  file_name VARCHAR(200),
  status VARCHAR(20) DEFAULT 'CREATED',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  finished_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_import_task_runtime (
  task_id VARCHAR(32) PRIMARY KEY,
  task_no VARCHAR(50) NOT NULL UNIQUE,
  module_code VARCHAR(100),
  task_name VARCHAR(100),
  file_name VARCHAR(200),
  success_rows INT DEFAULT 0,
  failed_rows INT DEFAULT 0,
  status VARCHAR(20) DEFAULT 'CREATED',
  result_text VARCHAR(1000),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  finished_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_notification (
  notify_id VARCHAR(32) PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  content VARCHAR(1000),
  notify_type VARCHAR(50) DEFAULT 'SYSTEM',
  module_code VARCHAR(100),
  biz_no VARCHAR(100),
  is_read BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_todo (
  todo_id VARCHAR(32) PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  module_code VARCHAR(100),
  biz_no VARCHAR(100),
  biz_id VARCHAR(100),
  priority VARCHAR(20) DEFAULT 'NORMAL',
  status VARCHAR(20) DEFAULT 'PENDING',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ========== 性能优化索引 ==========
-- 基础资料索引
CREATE INDEX IF NOT EXISTS idx_goods_code ON base_goods(goods_code);
CREATE INDEX IF NOT EXISTS idx_goods_name ON base_goods(goods_name);
CREATE INDEX IF NOT EXISTS idx_goods_category ON base_goods(category_name);
CREATE INDEX IF NOT EXISTS idx_goods_brand ON base_goods(brand_name);
CREATE INDEX IF NOT EXISTS idx_goods_status ON base_goods(status);

CREATE INDEX IF NOT EXISTS idx_customer_code ON base_customer(customer_code);
CREATE INDEX IF NOT EXISTS idx_customer_name ON base_customer(customer_name);
CREATE INDEX IF NOT EXISTS idx_customer_status ON base_customer(status);

CREATE INDEX IF NOT EXISTS idx_supplier_code ON base_supplier(supplier_code);
CREATE INDEX IF NOT EXISTS idx_supplier_name ON base_supplier(supplier_name);

-- 采购订单索引
CREATE INDEX IF NOT EXISTS idx_pur_order_no ON pur_order(order_no);
CREATE INDEX IF NOT EXISTS idx_pur_order_supplier ON pur_order(supplier);
CREATE INDEX IF NOT EXISTS idx_pur_order_status ON pur_order(status);
CREATE INDEX IF NOT EXISTS idx_pur_order_date ON pur_order(bill_date);
CREATE INDEX IF NOT EXISTS idx_pur_order_detail_order ON pur_order_detail(order_id);

-- 销售订单索引
CREATE INDEX IF NOT EXISTS idx_sales_order_no ON sales_order(order_no);
CREATE INDEX IF NOT EXISTS idx_sales_order_customer ON sales_order(customer);
CREATE INDEX IF NOT EXISTS idx_sales_order_status ON sales_order(status);
CREATE INDEX IF NOT EXISTS idx_sales_order_date ON sales_order(bill_date);
CREATE INDEX IF NOT EXISTS idx_sales_order_detail_order ON sales_order_detail(order_id);

-- 入库出库索引
CREATE INDEX IF NOT EXISTS idx_pur_inbound_no ON pur_inbound(inbound_no);
CREATE INDEX IF NOT EXISTS idx_pur_inbound_order ON pur_inbound(source_order);
CREATE INDEX IF NOT EXISTS idx_pur_inbound_status ON pur_inbound(status);
CREATE INDEX IF NOT EXISTS idx_sales_outbound_no ON sales_outbound(outbound_no);
CREATE INDEX IF NOT EXISTS idx_sales_outbound_order ON sales_outbound(source_order);
CREATE INDEX IF NOT EXISTS idx_sales_outbound_status ON sales_outbound(status);

-- 库存索引
CREATE INDEX IF NOT EXISTS idx_stock_goods ON inv_stock_balance(goods_code);
CREATE INDEX IF NOT EXISTS idx_stock_warehouse ON inv_stock_balance(warehouse);
CREATE INDEX IF NOT EXISTS idx_stock_goods_warehouse ON inv_stock_balance(goods_code, warehouse);
CREATE INDEX IF NOT EXISTS idx_ledger_goods ON inv_stock_ledger(goods_code);
CREATE INDEX IF NOT EXISTS idx_ledger_warehouse ON inv_stock_ledger(warehouse);
CREATE INDEX IF NOT EXISTS idx_ledger_bill ON inv_stock_ledger(source_bill);

-- 财务索引
CREATE INDEX IF NOT EXISTS idx_fin_ar_customer ON fin_ar(customer);
CREATE INDEX IF NOT EXISTS idx_fin_ar_status ON fin_ar(status);
CREATE INDEX IF NOT EXISTS idx_fin_ap_supplier ON fin_ap(supplier);
CREATE INDEX IF NOT EXISTS idx_fin_ap_status ON fin_ap(status);
CREATE INDEX IF NOT EXISTS idx_fund_ledger_account ON fin_fund_ledger(fund_account);
CREATE INDEX IF NOT EXISTS idx_fund_ledger_bill ON fin_fund_ledger(source_bill);

-- 系统表索引
CREATE INDEX IF NOT EXISTS idx_user_username ON sys_user_runtime(username);
CREATE INDEX IF NOT EXISTS idx_user_status ON sys_user_runtime(status);
CREATE INDEX IF NOT EXISTS idx_role_code ON sys_role_runtime(role_code);
CREATE INDEX IF NOT EXISTS idx_notify_read ON sys_notification(is_read, created_at);
CREATE INDEX IF NOT EXISTS idx_notify_type ON sys_notification(notify_type);
CREATE INDEX IF NOT EXISTS idx_todo_status ON sys_todo(status);
CREATE INDEX IF NOT EXISTS idx_todo_module ON sys_todo(module_code);
CREATE INDEX IF NOT EXISTS idx_log_time ON sys_operation_log_runtime(operate_at);
CREATE INDEX IF NOT EXISTS idx_log_module ON sys_operation_log_runtime(module_code);

CREATE TABLE IF NOT EXISTS sys_todo (
  todo_id VARCHAR(32) PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  module_code VARCHAR(100),
  biz_no VARCHAR(100),
  biz_id VARCHAR(100),
  priority VARCHAR(20) DEFAULT 'NORMAL',
  status VARCHAR(20) DEFAULT 'PENDING',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
