-- V93: 总账模块（PRD-32）M1——会计科目、会计期间、期初余额、项目档案
-- 科目体系执行《小企业会计准则》(2013)，编码 4-2-2-2 连续数字
-- 约定：VARCHAR(32) PK、DECIMAL(18,2) 金额、状态中文值、IF NOT EXISTS 幂等、无外键
-- 注意：V89~V91 被 PRD-30 采购发票占用、V92 被 PRD-31 操作日志占用，总账顺延自 V93 起编号

-- ========== 会计科目表 ==========
CREATE TABLE IF NOT EXISTS fin_account (
    id                  VARCHAR(32) PRIMARY KEY,
    account_code        VARCHAR(32) NOT NULL UNIQUE,   -- 科目编码，全局唯一
    account_name        VARCHAR(100) NOT NULL,         -- 科目名称
    parent_code         VARCHAR(32),                   -- 上级编码，顶级为空
    account_level       INT DEFAULT 1,                 -- 级次 1~4
    account_type        VARCHAR(10) NOT NULL,          -- 资产/负债/权益/成本/损益
    balance_direction   VARCHAR(4) NOT NULL,           -- 借/贷（科目自身余额方向）
    aux_dimensions      VARCHAR(200),                  -- 辅助核算维度，逗号分隔：customer,supplier,department,employee,goods,project,area
    is_qty              BOOLEAN DEFAULT FALSE,         -- 数量核算（库存商品等）
    is_cash             BOOLEAN DEFAULT FALSE,         -- 现金类科目（现金流量表取数依据）
    is_leaf             BOOLEAN DEFAULT TRUE,          -- 末级（只有末级可录凭证）
    is_system           BOOLEAN DEFAULT FALSE,         -- 系统预置（不可删、不可改编码）
    status              VARCHAR(10) DEFAULT '启用',     -- 启用/停用
    sort_order          INT DEFAULT 0,
    remark              VARCHAR(500),
    creator_name        VARCHAR(50) DEFAULT '系统管理员',
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_account_parent ON fin_account(parent_code);
CREATE INDEX IF NOT EXISTS idx_fin_account_type ON fin_account(account_type);

-- ========== 会计期间表 ==========
CREATE TABLE IF NOT EXISTS fin_accounting_period (
    id              VARCHAR(32) PRIMARY KEY,
    period_year     INT NOT NULL,                   -- 年度，如 2026
    period_no       INT NOT NULL,                   -- 月份 1~12
    period          VARCHAR(6) NOT NULL UNIQUE,     -- yyyyMM，如 202601
    start_date      DATE NOT NULL,
    end_date        DATE NOT NULL,
    status          VARCHAR(10) DEFAULT '未开始',    -- 未开始/进行中/已结账/已冻结
    settle_name     VARCHAR(50),                    -- 结账人
    settle_time     TIMESTAMP,                      -- 结账时间
    reopen_name     VARCHAR(50),                    -- 反结账人
    reopen_time     TIMESTAMP,
    reopen_reason   VARCHAR(500),                   -- 反结账原因（必填）
    creator_name    VARCHAR(50) DEFAULT '系统管理员',
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ========== 科目期初余额表 ==========
-- 一个科目+辅助核算组合一行；主科目期初 aux 各列为空
CREATE TABLE IF NOT EXISTS fin_init_balance (
    id              VARCHAR(32) PRIMARY KEY,
    account_code    VARCHAR(32) NOT NULL,
    aux_customer    VARCHAR(50),
    aux_supplier    VARCHAR(50),
    aux_department  VARCHAR(50),
    aux_employee    VARCHAR(50),
    aux_goods       VARCHAR(50),
    aux_project     VARCHAR(50),
    aux_area        VARCHAR(50),
    open_debit      DECIMAL(18,2) DEFAULT 0,        -- 期初借方余额（启用期间上期结转）
    open_credit     DECIMAL(18,2) DEFAULT 0,        -- 期初贷方余额
    open_qty        DECIMAL(18,4) DEFAULT 0,        -- 期初数量（数量核算科目）
    ytd_debit       DECIMAL(18,2) DEFAULT 0,        -- 年初至启用期累计借方发生
    ytd_credit      DECIMAL(18,2) DEFAULT 0,        -- 年初至启用期累计贷方发生
    creator_name    VARCHAR(50) DEFAULT '系统管理员',
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_fin_init_balance ON fin_init_balance(
    account_code, aux_customer, aux_supplier, aux_department, aux_employee, aux_goods, aux_project, aux_area
);

-- ========== 项目核算档案 ==========
CREATE TABLE IF NOT EXISTS fin_aux_project (
    id              VARCHAR(32) PRIMARY KEY,
    project_code    VARCHAR(50) NOT NULL UNIQUE,
    project_name    VARCHAR(200) NOT NULL,
    project_type    VARCHAR(50),                    -- 项目类型
    start_date      DATE,
    end_date        DATE,
    budget          DECIMAL(18,2),
    owner_name      VARCHAR(50),
    status          VARCHAR(10) DEFAULT '启用',
    remark          VARCHAR(500),
    creator_name    VARCHAR(50) DEFAULT '系统管理员',
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ========== 预置会计科目（小企业会计准则，id 直接用科目编码） ==========
-- 列顺序：id, account_code, account_name, parent_code, account_level, account_type, balance_direction, aux_dimensions, is_qty, is_cash, is_leaf, is_system, sort_order
-- 资产类
INSERT INTO fin_account (id, account_code, account_name, parent_code, account_level, account_type, balance_direction, aux_dimensions, is_qty, is_cash, is_leaf, is_system, sort_order) VALUES
('1001',     '1001',     '库存现金',       NULL,     1, '资产', '借', NULL,                          FALSE, TRUE,  TRUE,  TRUE, 10),
('1002',     '1002',     '银行存款',       NULL,     1, '资产', '借', NULL,                          FALSE, TRUE,  FALSE, TRUE, 20),
('100201',   '100201',   '工行基本户',     '1002',   2, '资产', '借', NULL,                          FALSE, TRUE,  TRUE,  TRUE, 21),
('100202',   '100202',   '农行一般户',     '1002',   2, '资产', '借', NULL,                          FALSE, TRUE,  TRUE,  TRUE, 22),
('1012',     '1012',     '其他货币资金',   NULL,     1, '资产', '借', NULL,                          FALSE, TRUE,  TRUE,  TRUE, 30),
('1122',     '1122',     '应收账款',       NULL,     1, '资产', '借', 'customer',                     FALSE, FALSE, TRUE,  TRUE, 40),
('1123',     '1123',     '预付账款',       NULL,     1, '资产', '借', 'supplier',                     FALSE, FALSE, TRUE,  TRUE, 50),
('1221',     '1221',     '其他应收款',     NULL,     1, '资产', '借', 'employee,customer,supplier',  FALSE, FALSE, TRUE,  TRUE, 60),
('1405',     '1405',     '库存商品',       NULL,     1, '资产', '借', 'goods',                        TRUE,  FALSE, TRUE,  TRUE, 70),
('1601',     '1601',     '固定资产',       NULL,     1, '资产', '借', 'department',                   FALSE, FALSE, TRUE,  TRUE, 80),
('1602',     '1602',     '累计折旧',       NULL,     1, '资产', '贷', 'department',                   FALSE, FALSE, TRUE,  TRUE, 90),
('1606',     '1606',     '固定资产清理',   NULL,     1, '资产', '借', NULL,                          FALSE, FALSE, TRUE,  TRUE, 100),
('1701',     '1701',     '无形资产',       NULL,     1, '资产', '借', NULL,                          FALSE, FALSE, TRUE,  TRUE, 110),
('1702',     '1702',     '累计摊销',       NULL,     1, '资产', '贷', NULL,                          FALSE, FALSE, TRUE,  TRUE, 120),
('1801',     '1801',     '长期待摊费用',   NULL,     1, '资产', '借', NULL,                          FALSE, FALSE, TRUE,  TRUE, 130),
('1901',     '1901',     '待处理财产损溢', NULL,     1, '资产', '借', NULL,                          FALSE, FALSE, TRUE,  TRUE, 140);

-- 负债类
INSERT INTO fin_account (id, account_code, account_name, parent_code, account_level, account_type, balance_direction, aux_dimensions, is_qty, is_cash, is_leaf, is_system, sort_order) VALUES
('2001',     '2001',     '短期借款',           NULL,         1, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 210),
('2201',     '2201',     '应付票据',           NULL,         1, '负债', '贷', 'supplier', FALSE, FALSE, TRUE,  TRUE, 220),
('2202',     '2202',     '应付账款',           NULL,         1, '负债', '贷', 'supplier', FALSE, FALSE, TRUE,  TRUE, 230),
('2203',     '2203',     '预收账款',           NULL,         1, '负债', '贷', 'customer', FALSE, FALSE, TRUE,  TRUE, 240),
('2211',     '2211',     '应付职工薪酬',       NULL,         1, '负债', '贷', 'department', FALSE, FALSE, FALSE, TRUE, 250),
('221101',   '221101',   '工资',               '2211',       2, '负债', '贷', 'department', FALSE, FALSE, TRUE,  TRUE, 251),
('221102',   '221102',   '社会保险费',         '2211',       2, '负债', '贷', 'department', FALSE, FALSE, TRUE,  TRUE, 252),
('221103',   '221103',   '职工福利',           '2211',       2, '负债', '贷', 'department', FALSE, FALSE, TRUE,  TRUE, 253),
('2221',     '2221',     '应交税费',           NULL,         1, '负债', '贷', NULL,       FALSE, FALSE, FALSE, TRUE, 260),
('222101',   '222101',   '应交增值税',         '2221',       2, '负债', '贷', NULL,       FALSE, FALSE, FALSE, TRUE, 261),
('22210101', '22210101', '进项税额',           '222101',     3, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 262),
('22210102', '22210102', '销项税额',           '222101',     3, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 263),
('22210103', '22210103', '已交税金',           '222101',     3, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 264),
('22210104', '22210104', '进项税额转出',       '222101',     3, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 265),
('22210105', '22210105', '转出未交增值税',     '222101',     3, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 266),
('222102',   '222102',   '未交增值税',         '2221',       2, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 267),
('222103',   '222103',   '待认证进项税额',     '2221',       2, '负债', '贷', 'supplier', FALSE, FALSE, TRUE,  TRUE, 268),
('222104',   '222104',   '应交城市维护建设税', '2221',       2, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 269),
('222105',   '222105',   '应交教育费附加',     '2221',       2, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 270),
('222106',   '222106',   '应交地方教育附加',   '2221',       2, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 271),
('222107',   '222107',   '应交企业所得税',     '2221',       2, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 272),
('222108',   '222108',   '应交个人所得税',     '2221',       2, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 273),
('222109',   '222109',   '应交印花税',         '2221',       2, '负债', '贷', NULL,       FALSE, FALSE, TRUE,  TRUE, 274),
('2241',     '2241',     '其他应付款',         NULL,         1, '负债', '贷', 'employee,customer,supplier', FALSE, FALSE, TRUE, TRUE, 280);

-- 权益类
INSERT INTO fin_account (id, account_code, account_name, parent_code, account_level, account_type, balance_direction, aux_dimensions, is_qty, is_cash, is_leaf, is_system, sort_order) VALUES
('3001',     '3001',     '实收资本',       NULL,     1, '权益', '贷', NULL, FALSE, FALSE, TRUE,  TRUE, 310),
('3002',     '3002',     '资本公积',       NULL,     1, '权益', '贷', NULL, FALSE, FALSE, TRUE,  TRUE, 320),
('3101',     '3101',     '盈余公积',       NULL,     1, '权益', '贷', NULL, FALSE, FALSE, FALSE, TRUE, 330),
('310101',   '310101',   '法定盈余公积',   '3101',   2, '权益', '贷', NULL, FALSE, FALSE, TRUE,  TRUE, 331),
('3103',     '3103',     '本年利润',       NULL,     1, '权益', '贷', NULL, FALSE, FALSE, TRUE,  TRUE, 340),
('3104',     '3104',     '利润分配',       NULL,     1, '权益', '贷', NULL, FALSE, FALSE, FALSE, TRUE, 350),
('310401',   '310401',   '未分配利润',     '3104',   2, '权益', '贷', NULL, FALSE, FALSE, TRUE,  TRUE, 351);

-- 成本类
INSERT INTO fin_account (id, account_code, account_name, parent_code, account_level, account_type, balance_direction, aux_dimensions, is_qty, is_cash, is_leaf, is_system, sort_order) VALUES
('4001',     '4001',     '生产成本',       NULL,     1, '成本', '借', NULL, FALSE, FALSE, TRUE,  TRUE, 410);

-- 损益类
INSERT INTO fin_account (id, account_code, account_name, parent_code, account_level, account_type, balance_direction, aux_dimensions, is_qty, is_cash, is_leaf, is_system, sort_order) VALUES
('5001',     '5001',     '主营业务收入',   NULL,     1, '损益', '贷', 'customer,goods,area', FALSE, FALSE, FALSE, TRUE, 510),
('500101',   '500101',   '商品销售收入',   '5001',   2, '损益', '贷', 'customer,goods,area', FALSE, FALSE, TRUE,  TRUE, 511),
('5051',     '5051',     '其他业务收入',   NULL,     1, '损益', '贷', NULL,                  FALSE, FALSE, TRUE,  TRUE, 520),
('5301',     '5301',     '营业外收入',     NULL,     1, '损益', '贷', NULL,                  FALSE, FALSE, TRUE,  TRUE, 530),
('5401',     '5401',     '主营业务成本',   NULL,     1, '损益', '借', 'goods',               FALSE, FALSE, TRUE,  TRUE, 540),
('5402',     '5402',     '其他业务成本',   NULL,     1, '损益', '借', NULL,                  FALSE, FALSE, TRUE,  TRUE, 550),
('5403',     '5403',     '税金及附加',     NULL,     1, '损益', '借', NULL,                  FALSE, FALSE, TRUE,  TRUE, 560),
('5601',     '5601',     '销售费用',       NULL,     1, '损益', '借', 'department',          FALSE, FALSE, FALSE, TRUE, 570),
('560101',   '560101',   '运费',           '5601',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 571),
('560102',   '560102',   '仓储费',         '5601',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 572),
('560103',   '560103',   '折旧费',         '5601',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 573),
('560104',   '560104',   '工资',           '5601',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 574),
('560105',   '560105',   '促销费',         '5601',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 575),
('560106',   '560106',   '差旅费',         '5601',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 576),
('560199',   '560199',   '其他销售费用',   '5601',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 579),
('5602',     '5602',     '管理费用',       NULL,     1, '损益', '借', 'department',          FALSE, FALSE, FALSE, TRUE, 580),
('560201',   '560201',   '办公费',         '5602',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 581),
('560202',   '560202',   '折旧费',         '5602',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 582),
('560203',   '560203',   '房租物业费',     '5602',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 583),
('560204',   '560204',   '工资',           '5602',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 584),
('560205',   '560205',   '差旅费',         '5602',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 585),
('560206',   '560206',   '业务招待费',     '5602',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 586),
('560207',   '560207',   '水电费',         '5602',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 587),
('560299',   '560299',   '其他管理费用',   '5602',   2, '损益', '借', 'department',          FALSE, FALSE, TRUE,  TRUE, 589),
('5603',     '5603',     '财务费用',       NULL,     1, '损益', '借', NULL,                  FALSE, FALSE, FALSE, TRUE, 590),
('560301',   '560301',   '手续费',         '5603',   2, '损益', '借', NULL,                  FALSE, FALSE, TRUE,  TRUE, 591),
('560302',   '560302',   '利息支出',       '5603',   2, '损益', '借', NULL,                  FALSE, FALSE, TRUE,  TRUE, 592),
('560399',   '560399',   '其他财务费用',   '5603',   2, '损益', '借', NULL,                  FALSE, FALSE, TRUE,  TRUE, 599),
('5701',     '5701',     '所得税费用',     NULL,     1, '损益', '借', NULL, FALSE, FALSE, TRUE,  TRUE, 600),
('5711',     '5711',     '营业外支出',     NULL,     1, '损益', '借', NULL, FALSE, FALSE, TRUE,  TRUE, 610);
