-- V42: 客户对账单模块
CREATE TABLE fin_customer_statement (
  statement_id      VARCHAR(32)  PRIMARY KEY,
  statement_no      VARCHAR(50)  NOT NULL UNIQUE,
  customer_code     VARCHAR(50)  NOT NULL,
  customer_name     VARCHAR(200) NOT NULL,
  salesman          VARCHAR(100),
  statement_date    DATE         NOT NULL,
  expected_pay_date DATE,
  contact_name      VARCHAR(100),
  contact_phone     VARCHAR(50),
  total_amount      DECIMAL(18,2) DEFAULT 0,
  paid_amount       DECIMAL(18,2) DEFAULT 0,
  write_off_amount  DECIMAL(18,2) DEFAULT 0,
  pay_status        VARCHAR(20)  DEFAULT '未收款',
  status            VARCHAR(20)  DEFAULT 'PENDING',
  remark            VARCHAR(500),
  creator_name      VARCHAR(100),
  create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  auditor_name      VARCHAR(100),
  audit_time        TIMESTAMP
);

CREATE TABLE fin_customer_statement_detail (
  detail_id       VARCHAR(32)  PRIMARY KEY,
  statement_id    VARCHAR(32)  NOT NULL,
  source_bill_no  VARCHAR(50)  NOT NULL,
  source_bill_date DATE,
  source_bill_type VARCHAR(30),
  bill_amount      DECIMAL(18,2) DEFAULT 0,
  reconcile_amount DECIMAL(18,2) DEFAULT 0,
  paid_amount      DECIMAL(18,2) DEFAULT 0,
  unpaid_amount    DECIMAL(18,2) DEFAULT 0,
  bill_remark      VARCHAR(500),
  sort_order       INT DEFAULT 0
);
