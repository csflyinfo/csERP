-- ============================================
-- V35: 飞单模块（供应商直送客户，跳过仓库）
-- 审核时生成采购订单+销售订单+应付应收，不写库存
-- ============================================

-- 飞单主表
CREATE TABLE fly_order (
    fly_id            VARCHAR(36) PRIMARY KEY,
    fly_no            VARCHAR(50) NOT NULL UNIQUE,
    supplier_code     VARCHAR(50),
    supplier_name     VARCHAR(200),
    customer_code     VARCHAR(50),
    customer_name     VARCHAR(100),
    salesman          VARCHAR(100),
    bill_date         DATE NOT NULL,
    purchase_amount   DECIMAL(18,2) DEFAULT 0,
    sales_amount      DECIMAL(18,2) DEFAULT 0,
    profit_amount     DECIMAL(18,2) DEFAULT 0,
    status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    purchase_order_id VARCHAR(36),
    purchase_order_no VARCHAR(50),
    sales_order_id    VARCHAR(36),
    sales_order_no    VARCHAR(50),
    remark            VARCHAR(500),
    creator_name      VARCHAR(100),
    create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    audit_user        VARCHAR(100),
    audit_time        TIMESTAMP,
    audit_info        VARCHAR(500)
);
CREATE INDEX idx_fly_order_no ON fly_order(fly_no);
CREATE INDEX idx_fly_order_status ON fly_order(status);
CREATE INDEX idx_fly_order_date ON fly_order(bill_date);
CREATE INDEX idx_fly_order_create_time ON fly_order(create_time);

-- 飞单明细表
CREATE TABLE fly_order_detail (
    detail_id       VARCHAR(36) PRIMARY KEY,
    fly_id          VARCHAR(36) NOT NULL,
    goods_code      VARCHAR(50) NOT NULL,
    goods_name      VARCHAR(200),
    spec            VARCHAR(200),
    unit_name       VARCHAR(50),
    unit_level      TINYINT DEFAULT 1,
    convert_qty     DECIMAL(18,4) DEFAULT 1,
    qty             DECIMAL(18,3) NOT NULL DEFAULT 0,
    purchase_price  DECIMAL(18,4) DEFAULT 0,
    sales_price     DECIMAL(18,4) DEFAULT 0,
    purchase_amount DECIMAL(18,2) DEFAULT 0,
    sales_amount    DECIMAL(18,2) DEFAULT 0,
    tax_rate        VARCHAR(20),
    remark          VARCHAR(256)
);
CREATE INDEX idx_fly_order_detail_fly ON fly_order_detail(fly_id);
CREATE INDEX idx_fly_order_detail_goods ON fly_order_detail(goods_code);
