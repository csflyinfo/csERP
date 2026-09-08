-- =====================================================================
-- V98 总账 M7：固定资产（资产类别 / 资产卡片 / 折旧明细）
--   1. fin_asset_category 七类固定资产种子（默认折旧方法/年限/残值率/折旧费用科目）
--   2. fin_asset_card 资产卡片：建卡可选生成入账凭证草稿（借 1601 / 贷对方科目，source_bill_no=卡片编号）
--   3. fin_asset_depreciation 月度折旧明细：按卡片+期间幂等，ZJ{period} 折旧凭证同编号幂等
-- 规则（M7）：四折旧法（直线/双倍余额递减/年数总和/工作量），次月起提、末月补残值、提满停；
--   折旧凭证为「转」字草稿，按折旧费用科目+部门汇总借方，贷 1602（部门辅助）。
-- 全部 CREATE/MERGE 幂等，无外键、无 DROP。
-- =====================================================================

-- ---------- 资产类别 ----------
CREATE TABLE IF NOT EXISTS fin_asset_category (
    category_code      VARCHAR(30)  PRIMARY KEY,
    category_name      VARCHAR(50)  NOT NULL,
    default_method     VARCHAR(10)  DEFAULT '直线',      -- 直线/双倍余额/年数总和/工作量
    default_life_months INT         DEFAULT 60,
    salvage_rate       DECIMAL(6,4) DEFAULT 0.0500,      -- 预计净残值率
    expense_account    VARCHAR(20)  DEFAULT '560202',    -- 折旧费用科目（560103 销售折旧费/560202 管理折旧费）
    is_system          BOOLEAN      DEFAULT TRUE,
    sort_order         INT          DEFAULT 0
);

MERGE INTO fin_asset_category (category_code, category_name, default_method, default_life_months, salvage_rate, expense_account, is_system, sort_order) KEY(category_code) VALUES
('01', '房屋建筑物', '直线', 240, 0.0500, '560202', TRUE, 10),
('02', '机器设备',   '直线', 120, 0.0500, '560202', TRUE, 20),
('03', '运输设备',   '直线', 48,  0.0500, '560103', TRUE, 30),
('04', '电子设备',   '直线', 36,  0.0500, '560202', TRUE, 40),
('05', '办公家具',   '直线', 60,  0.0500, '560202', TRUE, 50),
('06', '工器具',     '直线', 60,  0.0500, '560202', TRUE, 60),
('07', '其他设备',   '直线', 60,  0.0500, '560202', TRUE, 70);

-- ---------- 资产卡片 ----------
CREATE TABLE IF NOT EXISTS fin_asset_card (
    card_id            VARCHAR(32)  PRIMARY KEY,
    card_no            VARCHAR(40)  NOT NULL,
    category_code      VARCHAR(30),
    category_name      VARCHAR(50),
    asset_name         VARCHAR(100) NOT NULL,
    department_code    VARCHAR(50),
    department_name    VARCHAR(100),
    acquired_date      DATE         NOT NULL,
    original_value     DECIMAL(18,2) NOT NULL,
    salvage_rate       DECIMAL(6,4) DEFAULT 0,
    salvage_value      DECIMAL(18,2) DEFAULT 0,
    depreciation_method VARCHAR(10) NOT NULL,
    life_months        INT          DEFAULT 0,            -- 直线/双倍/年数总和用
    total_workload     DECIMAL(18,2) DEFAULT 0,           -- 工作量法用（总工作量）
    used_workload      DECIMAL(18,2) DEFAULT 0,
    workload_unit      VARCHAR(10),                       -- 工作量单位（工时/公里…）
    expense_account    VARCHAR(20)  DEFAULT '560202',
    accum_depreciation DECIMAL(18,2) DEFAULT 0,
    net_value          DECIMAL(18,2) DEFAULT 0,
    last_dep_period    VARCHAR(6),
    voucher_id         VARCHAR(32),                       -- 入账凭证（建卡生成的草稿）
    status             VARCHAR(10)  DEFAULT '使用中',     -- 使用中/已停用/已清理/已拆分/已合并
    remark             VARCHAR(200),
    maker_name         VARCHAR(50),
    make_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_asset_card_no ON fin_asset_card(card_no);
CREATE INDEX IF NOT EXISTS idx_fin_asset_card_status ON fin_asset_card(status);

-- ---------- 折旧明细 ----------
CREATE TABLE IF NOT EXISTS fin_asset_depreciation (
    id                 VARCHAR(32)  PRIMARY KEY,
    card_id            VARCHAR(32)  NOT NULL,
    card_no            VARCHAR(40),
    period             VARCHAR(6)   NOT NULL,
    amount             DECIMAL(18,2) NOT NULL,
    workload           DECIMAL(18,2) DEFAULT 0,
    expense_account    VARCHAR(20),
    department_code    VARCHAR(50),
    department_name    VARCHAR(100),
    voucher_id         VARCHAR(32),
    create_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_asset_dep_card ON fin_asset_depreciation(card_id, period);
CREATE INDEX IF NOT EXISTS idx_fin_asset_dep_period ON fin_asset_depreciation(period);
