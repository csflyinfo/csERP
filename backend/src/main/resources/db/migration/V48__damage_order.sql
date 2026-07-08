-- V48: 报损单专用表
-- 替换 biz_simple_bill 占位实现，升级为完整的主表+明细表结构

-- 报损单主表
CREATE TABLE IF NOT EXISTS inv_damage (
    damage_id VARCHAR(32) PRIMARY KEY,
    damage_no VARCHAR(32) NOT NULL UNIQUE,
    warehouse VARCHAR(100) NOT NULL,
    bill_date DATE NOT NULL,
    qty DECIMAL(18,4) DEFAULT 0,
    amount DECIMAL(18,2) DEFAULT 0,
    cost_amount DECIMAL(18,2) DEFAULT 0,
    status VARCHAR(20) DEFAULT 'DRAFT',           -- DRAFT / PENDING / APPROVED / CANCELLED
    creator_name VARCHAR(50) DEFAULT '系统管理员',
    audit_user VARCHAR(50),
    audit_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    remark VARCHAR(500)
);

-- 报损单明细表
CREATE TABLE IF NOT EXISTS inv_damage_detail (
    detail_id VARCHAR(32) PRIMARY KEY,
    damage_id VARCHAR(32) NOT NULL,
    goods_code VARCHAR(50),
    goods_name VARCHAR(200),
    spec VARCHAR(100),
    unit_name VARCHAR(50),
    qty DECIMAL(18,4) DEFAULT 0,
    price DECIMAL(18,4) DEFAULT 0,
    amount DECIMAL(18,2) DEFAULT 0,
    batch_no VARCHAR(50),
    production_date DATE,
    cost_price DECIMAL(18,6) DEFAULT 0,
    cost_amount DECIMAL(18,2) DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_inv_damage_detail_damage_id ON inv_damage_detail(damage_id);
CREATE INDEX IF NOT EXISTS idx_inv_damage_status ON inv_damage(status);
CREATE INDEX IF NOT EXISTS idx_inv_damage_warehouse ON inv_damage(warehouse);
