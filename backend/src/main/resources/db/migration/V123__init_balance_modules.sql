-- V123: 期初初始化三模块（PRD-34）——库存期初、客户应收期初、供应商应付期初
-- 设计：导入/手工录入先进「暂存表」，核对后一次性过账写正式账表（fin_ar/fin_ap/库存三账+wms_bin_stock），
--       过账主表 biz_init_post 留痕；首次业务日结后与总账启用后锁定，日结前可有条件反建账。
-- 约定同 V93：VARCHAR(32) PK、DECIMAL 金额、状态码、IF NOT EXISTS 幂等、无外键。

-- ========== 库存期初暂存行 ==========
CREATE TABLE IF NOT EXISTS inv_stock_init (
    line_id                 VARCHAR(32) PRIMARY KEY,
    import_batch_no         VARCHAR(40) NOT NULL,          -- 每次导入一个批次号，便于按批清空
    row_no                  INT,                           -- Excel 物理行号（含表头）
    init_mode               VARCHAR(10) NOT NULL DEFAULT 'BATCH', -- BATCH=按商品批次（不定位库位）/ BIN=按库位（WMS）
    goods_code              VARCHAR(50),
    goods_name              VARCHAR(200),
    warehouse_code         VARCHAR(50),
    warehouse_name         VARCHAR(100),
    bin_code                VARCHAR(100),                  -- BIN 模式必填
    container_code          VARCHAR(100),
    batch_no                VARCHAR(100),                  -- 按全局批次规则推导后的最终批号（可空）
    batch_no_input          VARCHAR(100),                  -- 用户原始录入
    production_date         DATE,
    expiry_date             DATE,
    qty                     DECIMAL(18,4) NOT NULL DEFAULT 0,
    unit_price              DECIMAL(18,6) NOT NULL DEFAULT 0, -- 成本单价（必填，允许 0）
    amount                  DECIMAL(18,2) NOT NULL DEFAULT 0, -- 数量×成本单价
    remark                  VARCHAR(255),
    line_status             VARCHAR(10) NOT NULL DEFAULT 'VALID', -- VALID / ERROR
    error_msg               VARCHAR(1000),
    task_no                 VARCHAR(50),
    posted                  CHAR(1) NOT NULL DEFAULT 'N',
    post_no                 VARCHAR(40),
    generated_ledger_ids    VARCHAR(2000),                 -- 过账生成的台账来源号清单（反建账定位，分号分隔）
    generated_bin_stock_ids VARCHAR(2000),                 -- 过账写入的 wms_bin_stock 主键清单
    created_by              VARCHAR(32),
    created_by_name         VARCHAR(100),
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_inv_stock_init_posted ON inv_stock_init(posted);
CREATE INDEX IF NOT EXISTS idx_inv_stock_init_batch ON inv_stock_init(import_batch_no);

-- ========== 客户应收期初暂存行 ==========
CREATE TABLE IF NOT EXISTS fin_ar_init (
    line_id                 VARCHAR(32) PRIMARY KEY,
    import_batch_no         VARCHAR(40) NOT NULL,
    row_no                  INT,
    customer_code           VARCHAR(50),
    customer_name           VARCHAR(100),
    original_bill_no        VARCHAR(50),                   -- 客户原单号：仅留存展示，不进 fin_ar.source_bill
    original_bill_date      DATE,                          -- 原单据日期：仅留存（账龄按建账日起算）
    ar_amount               DECIMAL(18,2) NOT NULL DEFAULT 0, -- 未收余额（received 恒为 0）
    salesman                VARCHAR(100),
    remark                  VARCHAR(255),
    line_status             VARCHAR(10) NOT NULL DEFAULT 'VALID',
    error_msg               VARCHAR(1000),
    task_no                 VARCHAR(50),
    posted                  CHAR(1) NOT NULL DEFAULT 'N',
    post_no                 VARCHAR(40),
    generated_ar_no         VARCHAR(50),                   -- 过账生成的 fin_ar.ar_no（反建账定位）
    created_by              VARCHAR(32),
    created_by_name         VARCHAR(100),
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_ar_init_posted ON fin_ar_init(posted);
CREATE INDEX IF NOT EXISTS idx_fin_ar_init_batch ON fin_ar_init(import_batch_no);

-- ========== 供应商应付期初暂存行 ==========
CREATE TABLE IF NOT EXISTS fin_ap_init (
    line_id                 VARCHAR(32) PRIMARY KEY,
    import_batch_no         VARCHAR(40) NOT NULL,
    row_no                  INT,
    supplier_code           VARCHAR(50),
    supplier_name           VARCHAR(100),
    original_bill_no        VARCHAR(50),
    original_bill_date      DATE,
    ap_amount               DECIMAL(18,2) NOT NULL DEFAULT 0, -- 未付余额（paid 恒为 0）
    remark                  VARCHAR(255),
    line_status             VARCHAR(10) NOT NULL DEFAULT 'VALID',
    error_msg               VARCHAR(1000),
    task_no                 VARCHAR(50),
    posted                  CHAR(1) NOT NULL DEFAULT 'N',
    post_no                 VARCHAR(40),
    generated_ap_no         VARCHAR(50),
    created_by              VARCHAR(32),
    created_by_name         VARCHAR(100),
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_ap_init_posted ON fin_ap_init(posted);
CREATE INDEX IF NOT EXISTS idx_fin_ap_init_batch ON fin_ap_init(import_batch_no);

-- ========== 期初过账主表（溯源/反建账留痕） ==========
CREATE TABLE IF NOT EXISTS biz_init_post (
    post_id                 VARCHAR(32) PRIMARY KEY,
    post_no                 VARCHAR(40) NOT NULL UNIQUE,   -- QC + yyyyMMdd + 4 位流水
    init_type               VARCHAR(10) NOT NULL,          -- STOCK / AR / AP
    line_count              INT DEFAULT 0,
    total_qty               DECIMAL(18,4) DEFAULT 0,       -- AR/AP 为 0
    total_amount            DECIMAL(18,2) DEFAULT 0,
    status                  VARCHAR(10) NOT NULL DEFAULT 'POSTED', -- POSTED / REVERSED
    remark                  VARCHAR(255),
    posted_by               VARCHAR(32),
    posted_by_name          VARCHAR(100),
    posted_at               TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    reversed_by             VARCHAR(32),
    reversed_by_name        VARCHAR(100),
    reversed_at             TIMESTAMP,
    reverse_reason          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_biz_init_post_type ON biz_init_post(init_type, status);
