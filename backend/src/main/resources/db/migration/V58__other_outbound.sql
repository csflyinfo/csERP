-- V58: 其他出库单专用表
-- 支持非销售/调拨/报损类出库：内部领用、样品出库、借出出库、活动消耗等
-- 主表字段参考其他入库单（V49），明细字段参考报损单（V48）
-- 审核时调用 salesOutbound 扣减库存，反审核/作废时调用 purchaseInbound 回库

-- 其他出库单主表
CREATE TABLE IF NOT EXISTS inv_other_outbound (
    outbound_id VARCHAR(32) PRIMARY KEY,
    outbound_no VARCHAR(32) NOT NULL UNIQUE,
    bill_date DATE NOT NULL,
    customer VARCHAR(200),
    supplier VARCHAR(200),
    outbound_type VARCHAR(50),
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

-- 其他出库单明细表（字段与报损单明细一致）
CREATE TABLE IF NOT EXISTS inv_other_outbound_detail (
    detail_id VARCHAR(32) PRIMARY KEY,
    outbound_id VARCHAR(32) NOT NULL,
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

CREATE INDEX IF NOT EXISTS idx_inv_other_outbound_detail_outbound_id ON inv_other_outbound_detail(outbound_id);
CREATE INDEX IF NOT EXISTS idx_inv_other_outbound_status ON inv_other_outbound(status);
CREATE INDEX IF NOT EXISTS idx_inv_other_outbound_warehouse ON inv_other_outbound(warehouse);

-- 字典类型：其他出库类型
INSERT INTO sys_dictionary_type (id, dict_type, dict_type_name, description, is_system, status)
VALUES ('DT_OOT', 'other_outbound_type', '其他出库类型', '其他出库单的出库类型：内部领用/样品出库/借出出库/活动消耗/内部加工/其他', 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_type_name = VALUES(dict_type_name), description = VALUES(description);

-- 字典值：其他出库类型种子数据（编码从 0 开始，与 V8 规范一致）
INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OOT_01', 'other_outbound_type', '0', '内部领用', 1, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OOT_02', 'other_outbound_type', '1', '样品出库', 2, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OOT_03', 'other_outbound_type', '2', '借出出库', 3, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OOT_04', 'other_outbound_type', '3', '活动消耗', 4, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OOT_05', 'other_outbound_type', '4', '内部加工', 5, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dictionary (id, dict_type, dict_code, dict_name, sort_order, is_system, status)
VALUES ('OOT_06', 'other_outbound_type', '5', '其他', 6, 0, 'NORMAL')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);
