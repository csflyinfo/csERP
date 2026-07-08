-- V49: 其他入库单专用表
-- 支持非采购/调拨类入库：期初库存、样品入库、赠品入库、盘外发现、内部加工等
-- 审核时调用 purchaseInbound 增加库存，反审核/作废时调用 salesOutbound 扣减库存

-- 其他入库单主表
CREATE TABLE IF NOT EXISTS inv_other_inbound (
    inbound_id VARCHAR(32) PRIMARY KEY,
    inbound_no VARCHAR(32) NOT NULL UNIQUE,
    bill_date DATE NOT NULL,
    customer VARCHAR(200),
    supplier VARCHAR(200),
    inbound_type VARCHAR(50),
    warehouse VARCHAR(100) NOT NULL,
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

-- 其他入库单明细表
CREATE TABLE IF NOT EXISTS inv_other_inbound_detail (
    detail_id VARCHAR(32) PRIMARY KEY,
    inbound_id VARCHAR(32) NOT NULL,
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

CREATE INDEX IF NOT EXISTS idx_inv_other_inbound_detail_inbound_id ON inv_other_inbound_detail(inbound_id);
CREATE INDEX IF NOT EXISTS idx_inv_other_inbound_status ON inv_other_inbound(status);
CREATE INDEX IF NOT EXISTS idx_inv_other_inbound_warehouse ON inv_other_inbound(warehouse);

-- 字典类型：其他入库类型
INSERT INTO sys_dictionary_type (id, dict_type, dict_type_name, description, is_system, status)
VALUES ('DT_OIT', 'other_inbound_type', '其他入库类型', '其他入库单的入库类型：期初库存/样品入库/赠品入库/盘外发现/内部加工/其他', 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_type_name = VALUES(dict_type_name), description = VALUES(description);

-- 字典值：其他入库类型种子数据（编码从 0 开始，与 V8 规范一致）
INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OIT_01', 'other_inbound_type', '0', '期初库存', 1, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OIT_02', 'other_inbound_type', '1', '样品入库', 2, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OIT_03', 'other_inbound_type', '2', '赠品入库', 3, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OIT_04', 'other_inbound_type', '3', '盘外发现', 4, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OIT_05', 'other_inbound_type', '4', '内部加工', 5, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OIT_06', 'other_inbound_type', '5', '其他', 6, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);
