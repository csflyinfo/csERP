-- ============================================================
-- V46: 盘点单 (Stock Take / Inventory Count)
-- 主表 inv_count_sheet + 明细表 inv_count_detail
-- ============================================================

CREATE TABLE IF NOT EXISTS inv_count_sheet (
    count_sheet_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_no VARCHAR(30) NOT NULL UNIQUE,
    warehouse VARCHAR(100) NOT NULL,
    count_date DATE,
    count_type VARCHAR(10) DEFAULT '1',
    status VARCHAR(20) DEFAULT 'PENDING',
    remark VARCHAR(500),
    create_by VARCHAR(50) DEFAULT '管理员',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    audit_by VARCHAR(50),
    audit_time TIMESTAMP
);

CREATE TABLE IF NOT EXISTS inv_count_detail (
    detail_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_no VARCHAR(30) NOT NULL,
    goods_code VARCHAR(50) NOT NULL,
    goods_name VARCHAR(200),
    spec VARCHAR(100),
    unit_name VARCHAR(50),
    batch_no VARCHAR(50),
    production_date DATE,
    book_qty DECIMAL(18,4) DEFAULT 0,
    real_qty DECIMAL(18,4) DEFAULT 0,
    diff_qty DECIMAL(18,4) DEFAULT 0,
    cost_price DECIMAL(18,6) DEFAULT 0,
    book_amount DECIMAL(18,2) DEFAULT 0,
    real_amount DECIMAL(18,2) DEFAULT 0,
    diff_amount DECIMAL(18,2) DEFAULT 0,
    diff_remark VARCHAR(200),
    is_new_batch INT DEFAULT 0,
    line_no INT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_count_detail_sheet ON inv_count_detail(sheet_no);
