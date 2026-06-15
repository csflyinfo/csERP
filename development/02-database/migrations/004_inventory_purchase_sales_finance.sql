-- ERP-WMS-TMS V1.0 库存、采购、销售、财务核心表草案

CREATE TABLE IF NOT EXISTS inv_stock_balance (
  balance_id VARCHAR(32) PRIMARY KEY,
  goods_id VARCHAR(32) NOT NULL,
  warehouse_id VARCHAR(32) NOT NULL,
  batch_no VARCHAR(100) NULL,
  physical_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
  locked_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
  frozen_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
  cost_price DECIMAL(18,6) NOT NULL DEFAULT 0,
  stock_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_goods_wh_batch (goods_id, warehouse_id, batch_no)
) COMMENT='库存余额';

CREATE TABLE IF NOT EXISTS inv_stock_ledger (
  ledger_id VARCHAR(32) PRIMARY KEY,
  ledger_no VARCHAR(50) NOT NULL UNIQUE,
  source_bill_type VARCHAR(50) NOT NULL,
  source_bill_id VARCHAR(32) NOT NULL,
  source_bill_no VARCHAR(50) NOT NULL,
  goods_id VARCHAR(32) NOT NULL,
  warehouse_id VARCHAR(32) NOT NULL,
  batch_no VARCHAR(100) NULL,
  direction VARCHAR(20) NOT NULL COMMENT 'IN/OUT',
  qty DECIMAL(18,6) NOT NULL,
  cost_price DECIMAL(18,6) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  occurred_at DATETIME NOT NULL,
  operator_id VARCHAR(32) NOT NULL
) COMMENT='库存流水';

CREATE TABLE IF NOT EXISTS pur_order (
  order_id VARCHAR(32) PRIMARY KEY,
  order_no VARCHAR(50) NOT NULL UNIQUE,
  supplier_id VARCHAR(32) NOT NULL,
  warehouse_id VARCHAR(32) NOT NULL,
  bill_date DATE NOT NULL,
  amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  inbound_status VARCHAR(20) NOT NULL DEFAULT 'NOT_INBOUND',
  creator_id VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  auditor_id VARCHAR(32) NULL,
  audit_time DATETIME NULL
) COMMENT='采购订单';

CREATE TABLE IF NOT EXISTS pur_order_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  order_id VARCHAR(32) NOT NULL,
  goods_id VARCHAR(32) NOT NULL,
  unit_id VARCHAR(32) NOT NULL,
  qty DECIMAL(18,6) NOT NULL,
  price DECIMAL(18,6) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  inbound_qty DECIMAL(18,6) NOT NULL DEFAULT 0
) COMMENT='采购订单明细';

CREATE TABLE IF NOT EXISTS sales_order (
  order_id VARCHAR(32) PRIMARY KEY,
  order_no VARCHAR(50) NOT NULL UNIQUE,
  customer_id VARCHAR(32) NOT NULL,
  warehouse_id VARCHAR(32) NOT NULL,
  bill_date DATE NOT NULL,
  amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  paid_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  outbound_status VARCHAR(20) NOT NULL DEFAULT 'NOT_OUTBOUND',
  creator_id VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  auditor_id VARCHAR(32) NULL,
  audit_time DATETIME NULL
) COMMENT='销售订单';

CREATE TABLE IF NOT EXISTS sales_order_detail (
  detail_id VARCHAR(32) PRIMARY KEY,
  order_id VARCHAR(32) NOT NULL,
  goods_id VARCHAR(32) NOT NULL,
  unit_id VARCHAR(32) NOT NULL,
  qty DECIMAL(18,6) NOT NULL,
  price DECIMAL(18,6) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  cost_price DECIMAL(18,6) NULL,
  outbound_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
  price_source VARCHAR(50) NULL
) COMMENT='销售订单明细';

CREATE TABLE IF NOT EXISTS fin_ar (
  ar_id VARCHAR(32) PRIMARY KEY,
  ar_no VARCHAR(50) NOT NULL UNIQUE,
  customer_id VARCHAR(32) NOT NULL,
  source_bill_type VARCHAR(50) NOT NULL,
  source_bill_id VARCHAR(32) NOT NULL,
  source_bill_no VARCHAR(50) NOT NULL,
  ar_amount DECIMAL(18,2) NOT NULL,
  received_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  due_date DATE NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) COMMENT='应收账款';

CREATE TABLE IF NOT EXISTS fin_ap (
  ap_id VARCHAR(32) PRIMARY KEY,
  ap_no VARCHAR(50) NOT NULL UNIQUE,
  supplier_id VARCHAR(32) NOT NULL,
  source_bill_type VARCHAR(50) NOT NULL,
  source_bill_id VARCHAR(32) NOT NULL,
  source_bill_no VARCHAR(50) NOT NULL,
  ap_amount DECIMAL(18,2) NOT NULL,
  paid_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  due_date DATE NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) COMMENT='应付账款';

CREATE TABLE IF NOT EXISTS fin_fund_ledger (
  ledger_id VARCHAR(32) PRIMARY KEY,
  ledger_no VARCHAR(50) NOT NULL UNIQUE,
  fund_account_id VARCHAR(32) NOT NULL,
  direction VARCHAR(20) NOT NULL COMMENT 'IN/OUT',
  amount DECIMAL(18,2) NOT NULL,
  source_bill_type VARCHAR(50) NOT NULL,
  source_bill_id VARCHAR(32) NOT NULL,
  source_bill_no VARCHAR(50) NOT NULL,
  balance_after DECIMAL(18,2) NOT NULL,
  occurred_at DATETIME NOT NULL,
  operator_id VARCHAR(32) NOT NULL
) COMMENT='资金流水';
