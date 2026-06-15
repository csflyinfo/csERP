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
  current_stock DECIMAL(18,2) DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
);

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
  creator_info VARCHAR(200)
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
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
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

CREATE TABLE IF NOT EXISTS biz_simple_bill (
  bill_id VARCHAR(32) PRIMARY KEY,
  bill_type VARCHAR(50) NOT NULL,
  bill_no VARCHAR(50) NOT NULL UNIQUE,
  object_name VARCHAR(100),
  warehouse VARCHAR(100),
  reason VARCHAR(200),
  amount DECIMAL(18,2) DEFAULT 0,
  qty DECIMAL(18,2) DEFAULT 0,
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
  status VARCHAR(20) DEFAULT 'NORMAL'
);

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
