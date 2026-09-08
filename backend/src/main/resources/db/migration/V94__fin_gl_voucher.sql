-- V94: 总账模块（PRD-32）M2——会计凭证、凭证分录、现金流量项目
-- 凭证状态：草稿/已审核/已过账/已作废/已冲销
-- 凭证字：记/收/付/转；凭证号规则：字-yyyyMM-4位（同期同字 MAX+1，作废留号）

-- ========== 凭证主表 ==========
CREATE TABLE IF NOT EXISTS fin_voucher (
    id                  VARCHAR(32) PRIMARY KEY,
    voucher_no          VARCHAR(32) UNIQUE,            -- 凭证号，审核时生成（草稿可为空）
    voucher_word        VARCHAR(4) DEFAULT '记',        -- 凭证字：记/收/付/转
    voucher_date        DATE NOT NULL,                 -- 凭证日期
    period              VARCHAR(6) NOT NULL,           -- 会计期间 yyyyMM
    attachments         INT DEFAULT 0,                 -- 附件张数
    summary             VARCHAR(500),                  -- 凭证摘要（首张分录摘要冗余）
    source              VARCHAR(10) DEFAULT '手工',     -- 手工/自动
    source_bill_type    VARCHAR(50),                   -- 来源单据类型（自动凭证）
    source_bill_no      VARCHAR(50),                   -- 来源单据号
    event_id            VARCHAR(32),                   -- 来源会计事件 fin_gl_event.id（M3 起）
    status              VARCHAR(10) DEFAULT '草稿',     -- 草稿/已审核/已过账/已作废/已冲销
    is_red              BOOLEAN DEFAULT FALSE,         -- 红字凭证
    red_source_id       VARCHAR(32),                   -- 红冲来源凭证 id
    export_flag         VARCHAR(20),                   -- 导出标记：金蝶/用友（可多次导出，取最新）
    maker_name          VARCHAR(50),                   -- 制单人
    make_time           TIMESTAMP,                     -- 制单时间
    auditor_name        VARCHAR(50),                   -- 审核人
    audit_time          TIMESTAMP,
    poster_name         VARCHAR(50),                   -- 过账人
    post_time           TIMESTAMP,
    void_name           VARCHAR(50),                   -- 作废人
    void_time           TIMESTAMP,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_voucher_period ON fin_voucher(period);
CREATE INDEX IF NOT EXISTS idx_fin_voucher_status ON fin_voucher(status);
CREATE INDEX IF NOT EXISTS idx_fin_voucher_word_period ON fin_voucher(voucher_word, period);
CREATE INDEX IF NOT EXISTS idx_fin_voucher_source_bill ON fin_voucher(source_bill_type, source_bill_no);

-- ========== 凭证分录表 ==========
CREATE TABLE IF NOT EXISTS fin_voucher_entry (
    id                  VARCHAR(32) PRIMARY KEY,
    voucher_id          VARCHAR(32) NOT NULL,
    line_no             INT NOT NULL,                  -- 行号
    summary             VARCHAR(200),                  -- 摘要
    account_code        VARCHAR(32) NOT NULL,          -- 科目编码（必须末级启用）
    debit_amount        DECIMAL(18,2) DEFAULT 0,       -- 借方金额
    credit_amount       DECIMAL(18,2) DEFAULT 0,       -- 贷方金额
    qty                 DECIMAL(18,4),                 -- 数量（数量核算科目）
    price               DECIMAL(18,6),                 -- 单价
    aux_customer        VARCHAR(50),                   -- 辅助核算：客户编码
    aux_supplier        VARCHAR(50),                   -- 供应商编码
    aux_department      VARCHAR(50),                   -- 部门编码
    aux_employee        VARCHAR(50),                   -- 员工编码
    aux_goods           VARCHAR(50),                   -- 商品编码
    aux_project         VARCHAR(50),                   -- 项目编码
    aux_area            VARCHAR(50),                   -- 片区（自由维度）
    aux_key             VARCHAR(500),                  -- 辅助键（7 维拼接，用于合并/聚合）
    aux_text            VARCHAR(500),                  -- 辅助项文本（名称拼接，展示用）
    cash_flow_item      VARCHAR(20),                   -- 现金流量项目编码（现金类分录必填）
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_entry_voucher ON fin_voucher_entry(voucher_id);
CREATE INDEX IF NOT EXISTS idx_fin_entry_account ON fin_voucher_entry(account_code);
CREATE INDEX IF NOT EXISTS idx_fin_entry_aux_key ON fin_voucher_entry(aux_key);

-- ========== 现金流量项目 ==========
CREATE TABLE IF NOT EXISTS fin_cash_flow_item (
    id                  VARCHAR(32) PRIMARY KEY,
    item_code           VARCHAR(20) NOT NULL UNIQUE,
    item_name           VARCHAR(100) NOT NULL,
    category            VARCHAR(10) NOT NULL,          -- 经营/投资/筹资
    direction           VARCHAR(4) NOT NULL,           -- 流入/流出
    sort_order          INT DEFAULT 0,
    is_system           BOOLEAN DEFAULT TRUE,
    status              VARCHAR(10) DEFAULT '启用'
);

INSERT INTO fin_cash_flow_item (id, item_code, item_name, category, direction, sort_order) VALUES
('CF01', 'CF01', '销售商品、提供劳务收到的现金',       '经营', '流入', 1),
('CF02', 'CF02', '收到的税费返还',                     '经营', '流入', 2),
('CF03', 'CF03', '收到其他与经营活动有关的现金',       '经营', '流入', 3),
('CF04', 'CF04', '购买商品、接受劳务支付的现金',       '经营', '流出', 4),
('CF05', 'CF05', '支付给职工以及为职工支付的现金',     '经营', '流出', 5),
('CF06', 'CF06', '支付的各项税费',                     '经营', '流出', 6),
('CF07', 'CF07', '支付其他与经营活动有关的现金',       '经营', '流出', 7),
('CF08', 'CF08', '收回投资收到的现金',                 '投资', '流入', 8),
('CF09', 'CF09', '取得投资收益收到的现金',             '投资', '流入', 9),
('CF10', 'CF10', '处置固定资产、无形资产收回的现金净额','投资', '流入', 10),
('CF11', 'CF11', '收到其他与投资活动有关的现金',       '投资', '流入', 11),
('CF12', 'CF12', '购建固定资产、无形资产支付的现金',   '投资', '流出', 12),
('CF13', 'CF13', '投资支付的现金',                     '投资', '流出', 13),
('CF14', 'CF14', '支付其他与投资活动有关的现金',       '投资', '流出', 14),
('CF15', 'CF15', '吸收投资收到的现金',                 '筹资', '流入', 15),
('CF16', 'CF16', '取得借款收到的现金',                 '筹资', '流入', 16),
('CF17', 'CF17', '收到其他与筹资活动有关的现金',       '筹资', '流入', 17),
('CF18', 'CF18', '偿还债务支付的现金',                 '筹资', '流出', 18),
('CF19', 'CF19', '分配股利、利润或偿付利息支付的现金', '筹资', '流出', 19),
('CF20', 'CF20', '支付其他与筹资活动有关的现金',       '筹资', '流出', 20);
