-- V95: 总账模块（PRD-32）M3——会计事件池、凭证模板、业务类型科目映射
-- 事件状态：待生成/已生成/已忽略/生成失败/已冲回
-- 模板：事件码 → 凭证字 + 摘要 + 分录行（金额白名单表达式、条件表达式、明细行展开、科目占位符 @AR/@AP/@FUND/@EXPENSE/@BIZ）

-- ========== 会计事件池 ==========
CREATE TABLE IF NOT EXISTS fin_gl_event (
    id                      VARCHAR(32) PRIMARY KEY,
    event_code              VARCHAR(32) NOT NULL,          -- 事件码：PUR_IN/PUR_RETURN/SALE_OUT/SALE_SIGN/...
    source_bill_type        VARCHAR(50),                   -- 来源单据类型
    source_bill_no          VARCHAR(64),                   -- 来源单据号
    biz_date                DATE,                          -- 业务日期（决定凭证期间）
    period                  VARCHAR(6),                    -- 会计期间 yyyyMM
    amount                  DECIMAL(18,2),                 -- 事件金额（列表展示）
    payload_json            LONGTEXT,                      -- 单据全量快照（渲染凭证的唯一数据来源）
    status                  VARCHAR(10) DEFAULT '待生成',  -- 待生成/已生成/已忽略/生成失败/已冲回
    err_msg                 VARCHAR(500),                  -- 失败中文原因
    warn_msg                VARCHAR(500),                  -- 警告（不阻断，如档案映射缺失用了默认科目）
    voucher_id              VARCHAR(32),                   -- 生成的凭证 id
    voucher_no              VARCHAR(32),                   -- 生成的凭证号（冗余）
    reverse_of_event_id     VARCHAR(32),                   -- 反向事件关联的原事件 id
    reverse_flag            CHAR(1) DEFAULT '0',           -- 1=反向事件（红字）
    process_name            VARCHAR(50),                   -- 处理人
    process_time            TIMESTAMP,
    create_time             TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_gl_event_status ON fin_gl_event(status);
CREATE INDEX IF NOT EXISTS idx_fin_gl_event_code ON fin_gl_event(event_code);
CREATE INDEX IF NOT EXISTS idx_fin_gl_event_period ON fin_gl_event(period);
CREATE INDEX IF NOT EXISTS idx_fin_gl_event_bill ON fin_gl_event(source_bill_type, source_bill_no);
-- 幂等：同单同事件同方向只落一条（reverse_flag 非空参与唯一键）
CREATE UNIQUE INDEX IF NOT EXISTS uk_fin_gl_event_idem
    ON fin_gl_event(source_bill_type, source_bill_no, event_code, reverse_flag);

-- ========== 凭证模板 ==========
CREATE TABLE IF NOT EXISTS fin_voucher_template (
    id                      VARCHAR(32) PRIMARY KEY,
    template_code           VARCHAR(32) NOT NULL UNIQUE,   -- 模板编码（=事件码）
    event_code              VARCHAR(32) NOT NULL UNIQUE,
    template_name           VARCHAR(64) NOT NULL,
    voucher_word            VARCHAR(4) DEFAULT '记',        -- 凭证字：记/收/付/转
    summary_pattern         VARCHAR(200),                   -- 凭证摘要，支持 {bill_no} 等变量
    enabled                 BOOLEAN DEFAULT TRUE,
    is_system               BOOLEAN DEFAULT TRUE,           -- 预置模板不可删
    remark                  VARCHAR(500),
    create_time             TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS fin_voucher_template_line (
    id                      VARCHAR(32) PRIMARY KEY,
    template_id             VARCHAR(32) NOT NULL,
    line_no                 INT NOT NULL,
    direction               VARCHAR(4) NOT NULL,           -- 借/贷
    summary_pattern         VARCHAR(200),                   -- 行摘要，支持 {goods_name} 等变量
    account_expr            VARCHAR(64),                    -- 固定科目编码 或 @AR/@AP/@FUND/@EXPENSE/@BIZ
    amount_expr             VARCHAR(500),                   -- 金额表达式（白名单四则+比较，变量取 payload）
    qty_expr                VARCHAR(200),                   -- 数量表达式
    aux_expr                VARCHAR(500),                   -- 辅助核算 JSON：{"customer":"customer_code"}；固定值 "=CODE|名称"
    cash_flow_item          VARCHAR(20),                    -- 预置现金流量项目编码
    condition_expr          VARCHAR(300),                   -- 行启用条件，空=始终生效
    expand_by               VARCHAR(16) DEFAULT 'NONE',     -- NONE/GOODS_LINE/EXPENSE_LINE
    lines_key               VARCHAR(32) DEFAULT 'lines',    -- 展开取 payload 的数组键
    enabled                 BOOLEAN DEFAULT TRUE,
    create_time             TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_tpl_line_tpl ON fin_voucher_template_line(template_id);

-- ========== 业务类型科目映射（其他出/入库类型 → 对方科目；类型仍留字典，不剥离） ==========
CREATE TABLE IF NOT EXISTS fin_gl_biz_subject_map (
    id                      VARCHAR(32) PRIMARY KEY,
    event_code              VARCHAR(32) NOT NULL,          -- OTHER_OUT/OTHER_IN（可扩展）
    biz_type_code           VARCHAR(32) NOT NULL,          -- 字典 dict_code
    biz_type_name           VARCHAR(64),                   -- 字典名称快照（字典改名/删值不影响历史）
    counter_subject_code    VARCHAR(32),                   -- 对方末级科目；空值=该类型不生成凭证
    is_system               BOOLEAN DEFAULT TRUE,
    remark                  VARCHAR(500),
    create_time             TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_fin_gl_biz_map ON fin_gl_biz_subject_map(event_code, biz_type_code);

-- ========== 预置 16 事件码模板 ==========
INSERT INTO fin_voucher_template (id, template_code, event_code, template_name, voucher_word, summary_pattern, enabled, remark) VALUES
('TPL_PUR_IN',           'PUR_IN',           'PUR_IN',           '采购收货',     '记', '采购收货 {bill_no}',           TRUE,  '采购收货审核：借库存商品(不含税)/进项税，贷应付(含税)'),
('TPL_PUR_RETURN',       'PUR_RETURN',       'PUR_RETURN',       '采购退货',     '记', '采购退货 {bill_no}',           TRUE,  '采购退货审核：与采购收货同构，金额为负'),
('TPL_SALE_OUT',         'SALE_OUT',         'SALE_OUT',         '销售出库成本', '转', '结转销售成本 {bill_no}',       TRUE,  '出库审核按移动加权平均结转成本'),
('TPL_SALE_SIGN',        'SALE_SIGN',        'SALE_SIGN',        '销售签收',     '记', '销售 {bill_no}',               TRUE,  '签收确认应收与收入'),
('TPL_SALE_RETURN',      'SALE_RETURN',      'SALE_RETURN',      '销售退货',     '记', '销售退货 {bill_no}',           TRUE,  '销售退货审核：与销售签收同构，金额为负'),
('TPL_SALE_RETURN_COST', 'SALE_RETURN_COST', 'SALE_RETURN_COST', '退货成本红冲', '转', '退货成本红冲 {bill_no}',       TRUE,  '退货入库审核：红冲出库成本'),
('TPL_RECEIPT',          'RECEIPT',          'RECEIPT',          '收款',         '收', '收款 {bill_no}',               TRUE,  '收款单审核：按来源分流应收/其他应收'),
('TPL_PAYMENT',          'PAYMENT',          'PAYMENT',          '付款',         '付', '付款 {bill_no}',               TRUE,  '付款单审核：按来源分流应付/其他应付'),
('TPL_EXPENSE',          'EXPENSE',          'EXPENSE',          '费用',         '记', '费用报销 {bill_no}',           TRUE,  '费用单审核：按费用明细行展开；有账户贷资金、无账户挂往来'),
('TPL_OTHER_INCOME',     'OTHER_INCOME',     'OTHER_INCOME',     '其他收入',     '记', '其他收入 {bill_no}',           TRUE,  '收入方向费用单：按明细行展开收入科目'),
('TPL_FLY_ORDER',        'FLY_ORDER',        'FLY_ORDER',        '飞单',         '记', '飞单 {bill_no}',               FALSE, '飞单业务拆为 PUR_IN + SALE_SIGN 两个事件，不单独生凭证'),
('TPL_STOCK_CHECK',      'STOCK_CHECK',      'STOCK_CHECK',      '盘点',         '记', '存货盘点 {bill_no}',           TRUE,  '盘亏入待处理财产损溢，盘盈冲管理费用'),
('TPL_OTHER_OUT',        'OTHER_OUT',        'OTHER_OUT',        '其他出库',     '转', '{biz_type_name} {bill_no}',    TRUE,  '对方科目按出库类型查业务类型科目映射'),
('TPL_OTHER_IN',         'OTHER_IN',         'OTHER_IN',         '其他入库',     '转', '{biz_type_name} {bill_no}',    TRUE,  '对方科目按入库类型查业务类型科目映射；期初库存不生凭证'),
('TPL_DAMAGE',           'DAMAGE',           'DAMAGE',           '报损',         '转', '存货报损 {bill_no}',           TRUE,  '定额内损耗入管理费用，大额待批入待处理财产损溢'),
('TPL_INVOICE_AUTH',     'INVOICE_AUTH',     'INVOICE_AUTH',     '发票认证',     '转', '发票认证 {bill_no}',           TRUE,  '采购发票认证通过：待认证进项税转进项税额（PRD-30 联动，预留）');

-- ========== 模板分录行 ==========
-- 方向/科目/金额表达式/条件/展开方式；taxpayer 变量由事件发布时按参数 fin.gl.taxpayer_type 注入
INSERT INTO fin_voucher_template_line (id, template_id, line_no, direction, summary_pattern, account_expr, amount_expr, qty_expr, aux_expr, cash_flow_item, condition_expr, expand_by, lines_key) VALUES
-- 注意：条件表达式内含单引号，SQL 字符串里用 '' 转义；切勿用双引号（H2 视为标识符）
-- PUR_IN 采购收货
('TPL_PUR_IN_L1', 'TPL_PUR_IN', 1, '借', '采购入库 {goods_name}', '1405',     'amount_excl',     'qty', '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'lines'),
('TPL_PUR_IN_L2', 'TPL_PUR_IN', 2, '借', '待认证进项税额',        '222103',   'tax_amount',      NULL,  '{"supplier":"supplier_code"}', NULL, 'taxpayer == ''GENERAL''', 'NONE', 'lines'),
('TPL_PUR_IN_L3', 'TPL_PUR_IN', 3, '贷', '应付货款 {supplier_name}', '2202', 'amount_tax_incl', NULL,  '{"supplier":"supplier_code"}', NULL, NULL, 'NONE', 'lines'),
-- PUR_RETURN 采购退货（金额为负，同构）
('TPL_PUR_RET_L1', 'TPL_PUR_RETURN', 1, '借', '采购退货 {goods_name}', '1405',   'amount_excl',     'qty', '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'lines'),
('TPL_PUR_RET_L2', 'TPL_PUR_RETURN', 2, '借', '待认证进项税额（退货）', '222103', 'tax_amount',     NULL,  '{"supplier":"supplier_code"}', NULL, 'taxpayer == ''GENERAL''', 'NONE', 'lines'),
('TPL_PUR_RET_L3', 'TPL_PUR_RETURN', 3, '贷', '退货冲应付 {supplier_name}', '2202', 'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL, NULL, 'NONE', 'lines'),
-- SALE_OUT 销售出库成本
('TPL_SALE_OUT_L1', 'TPL_SALE_OUT', 1, '借', '结转销售成本 {goods_name}', '5401', 'cost_amount', NULL, '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'lines'),
('TPL_SALE_OUT_L2', 'TPL_SALE_OUT', 2, '贷', '发出商品 {goods_name}',     '1405', 'cost_amount', 'qty', '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'lines'),
-- SALE_SIGN 销售签收
('TPL_SALE_SIGN_L1', 'TPL_SALE_SIGN', 1, '借', '应收货款 {customer_name}', '1122',    'amount_tax_incl', NULL, '{"customer":"customer_code"}', NULL, NULL, 'NONE', 'lines'),
('TPL_SALE_SIGN_L2', 'TPL_SALE_SIGN', 2, '贷', '商品销售收入 {goods_name}', '500101', 'amount_excl',     NULL, '{"customer":"customer_code","goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'lines'),
('TPL_SALE_SIGN_L3', 'TPL_SALE_SIGN', 3, '贷', '销项税额',                 '22210102','tax_amount',      NULL, NULL, NULL, 'taxpayer == ''GENERAL''', 'NONE', 'lines'),
-- SALE_RETURN 销售退货（金额为负，同构）
('TPL_SALE_RET_L1', 'TPL_SALE_RETURN', 1, '借', '退货冲应收 {customer_name}', '1122',    'amount_tax_incl', NULL, '{"customer":"customer_code"}', NULL, NULL, 'NONE', 'lines'),
('TPL_SALE_RET_L2', 'TPL_SALE_RETURN', 2, '贷', '销售退回 {goods_name}',       '500101', 'amount_excl',     NULL, '{"customer":"customer_code","goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'lines'),
('TPL_SALE_RET_L3', 'TPL_SALE_RETURN', 3, '贷', '销项税额（退货）',           '22210102','tax_amount',      NULL, NULL, NULL, 'taxpayer == ''GENERAL''', 'NONE', 'lines'),
-- SALE_RETURN_COST 退货成本红冲（金额数量为负）
('TPL_SALE_RC_L1', 'TPL_SALE_RETURN_COST', 1, '借', '退货入库 {goods_name}', '1405', 'cost_amount', 'qty', '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'lines'),
('TPL_SALE_RC_L2', 'TPL_SALE_RETURN_COST', 2, '贷', '红冲销售成本 {goods_name}', '5401', 'cost_amount', NULL, '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'lines'),
-- RECEIPT 收款
('TPL_RECEIPT_L1', 'TPL_RECEIPT', 1, '借', '收款 {fund_account_name}', '@FUND', 'amount_tax_incl', NULL, NULL, 'CF01', NULL, 'NONE', 'lines'),
-- 往来科目分流：客户/供应商往来（含未核销但对手方为客商）走应收/应付账款，无对手方走其他应收/应付
('TPL_RECEIPT_L2', 'TPL_RECEIPT', 2, '贷', '收回应收 {customer_name}', '1122', 'amount_tax_incl', NULL, '{"customer":"customer_code"}', NULL, 'ar_bill_no != '''' || customer_code != ''''', 'NONE', 'lines'),
('TPL_RECEIPT_L3', 'TPL_RECEIPT', 3, '贷', '收回其他往来',             '1221', 'amount_tax_incl', NULL, '{"customer":"customer_code","supplier":"supplier_code","employee":"employee_code"}', NULL, 'ar_bill_no == '''' && customer_code == ''''', 'NONE', 'lines'),
-- PAYMENT 付款
('TPL_PAYMENT_L1', 'TPL_PAYMENT', 1, '借', '支付应付 {supplier_name}', '2202', 'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL, 'ap_bill_no != '''' || supplier_code != ''''', 'NONE', 'lines'),
('TPL_PAYMENT_L2', 'TPL_PAYMENT', 2, '借', '支付其他往来',             '2241', 'amount_tax_incl', NULL, '{"supplier":"supplier_code","employee":"employee_code","customer":"customer_code"}', NULL, 'ap_bill_no == '''' && supplier_code == ''''', 'NONE', 'lines'),
('TPL_PAYMENT_L3', 'TPL_PAYMENT', 3, '贷', '付款 {fund_account_name}', '@FUND', 'amount_tax_incl', NULL, NULL, 'CF04', NULL, 'NONE', 'lines'),
-- EXPENSE 费用（明细行展开费用科目；有账户贷资金，无账户挂往来）
('TPL_EXPENSE_L1', 'TPL_EXPENSE', 1, '借', '{expense_type_name}', '@EXPENSE', 'amount', NULL, '{"department":"department_code"}', NULL, NULL, 'EXPENSE_LINE', 'lines'),
('TPL_EXPENSE_L2', 'TPL_EXPENSE', 2, '借', '待认证进项税额（费用）', '222103', 'tax_amount', NULL, '{"supplier":"supplier_code"}', NULL, 'taxpayer == ''GENERAL'' && expense_deductible == true', 'NONE', 'lines'),
('TPL_EXPENSE_L3', 'TPL_EXPENSE', 3, '贷', '付款 {fund_account_name}', '@FUND', 'amount_tax_incl', NULL, NULL, 'CF07', 'fund_account_code != ''''', 'NONE', 'lines'),
('TPL_EXPENSE_L4', 'TPL_EXPENSE', 4, '贷', '应付费用 {supplier_name}', '2202', 'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL, 'fund_account_code == '''' && supplier_code != ''''', 'NONE', 'lines'),
('TPL_EXPENSE_L5', 'TPL_EXPENSE', 5, '贷', '其他应付费用', '2241', 'amount_tax_incl', NULL, '{"supplier":"supplier_code","employee":"employee_code"}', NULL, 'fund_account_code == '''' && supplier_code == ''''', 'NONE', 'lines'),
-- OTHER_INCOME 其他收入
('TPL_OINC_L1', 'TPL_OTHER_INCOME', 1, '借', '收款 {fund_account_name}', '@FUND', 'amount_tax_incl', NULL, NULL, 'CF03', 'fund_account_code != ''''', 'NONE', 'lines'),
('TPL_OINC_L2', 'TPL_OTHER_INCOME', 2, '借', '应收 {customer_name}', '1122', 'amount_tax_incl', NULL, '{"customer":"customer_code"}', NULL, 'fund_account_code == '''' && customer_code != ''''', 'NONE', 'lines'),
('TPL_OINC_L3', 'TPL_OTHER_INCOME', 3, '借', '其他应收', '1221', 'amount_tax_incl', NULL, '{"customer":"customer_code","supplier":"supplier_code","employee":"employee_code"}', NULL, 'fund_account_code == '''' && customer_code == ''''', 'NONE', 'lines'),
('TPL_OINC_L4', 'TPL_OTHER_INCOME', 4, '贷', '{expense_type_name}', '@EXPENSE', 'amount', NULL, NULL, NULL, NULL, 'EXPENSE_LINE', 'lines'),
-- STOCK_CHECK 盘点（loss_lines 盘亏 / profit_lines 盘盈）
('TPL_CHECK_L1', 'TPL_STOCK_CHECK', 1, '借', '盘亏 {goods_name}', '1901',   'cost_amount', NULL, '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'loss_lines'),
('TPL_CHECK_L2', 'TPL_STOCK_CHECK', 2, '贷', '盘亏出库 {goods_name}', '1405', 'cost_amount', 'qty', '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'loss_lines'),
('TPL_CHECK_L3', 'TPL_STOCK_CHECK', 3, '借', '盘盈入库 {goods_name}', '1405',   'cost_amount', 'qty', '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'profit_lines'),
('TPL_CHECK_L4', 'TPL_STOCK_CHECK', 4, '贷', '存货盘盈冲管理费用', '560299',  'cost_amount', NULL, '{"department":"department_code"}', NULL, NULL, 'GOODS_LINE', 'profit_lines'),
-- OTHER_OUT 其他出库（@BIZ 对方科目按出库类型映射）
('TPL_OOUT_L1', 'TPL_OTHER_OUT', 1, '借', '{biz_type_name}', '@BIZ', 'cost_amount', NULL, NULL, NULL, NULL, 'NONE', 'lines'),
('TPL_OOUT_L2', 'TPL_OTHER_OUT', 2, '贷', '其他出库 {goods_name}', '1405', 'cost_amount', 'qty', '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'lines'),
-- OTHER_IN 其他入库
('TPL_OIN_L1', 'TPL_OTHER_IN', 1, '借', '其他入库 {goods_name}', '1405', 'cost_amount', 'qty', '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'lines'),
('TPL_OIN_L2', 'TPL_OTHER_IN', 2, '贷', '{biz_type_name}', '@BIZ', 'cost_amount', NULL, NULL, NULL, NULL, 'NONE', 'lines'),
-- DAMAGE 报损
('TPL_DMG_L1', 'TPL_DAMAGE', 1, '借', '存货报损待处理', '1901',   'cost_amount', NULL, NULL, NULL, 'large_loss == true', 'NONE', 'lines'),
('TPL_DMG_L2', 'TPL_DAMAGE', 2, '借', '定额内合理损耗', '560299', 'cost_amount', NULL, '{"department":"department_code"}', NULL, 'large_loss != true', 'NONE', 'lines'),
('TPL_DMG_L3', 'TPL_DAMAGE', 3, '贷', '报损出库 {goods_name}', '1405', 'cost_amount', 'qty', '{"goods":"goods_code"}', NULL, NULL, 'GOODS_LINE', 'lines'),
-- INVOICE_AUTH 发票认证（预留）
('TPL_INV_AUTH_L1', 'TPL_INVOICE_AUTH', 1, '借', '认证进项税额',     '22210101', 'tax_amount', NULL, NULL, NULL, NULL, 'NONE', 'lines'),
('TPL_INV_AUTH_L2', 'TPL_INVOICE_AUTH', 2, '贷', '结转待认证进项税', '222103',   'tax_amount', NULL, '{"supplier":"supplier_code"}', NULL, NULL, 'NONE', 'lines');

-- ========== 业务类型科目映射种子（12 行） ==========
-- 科目必须末级启用：5601/5602 为父科目，取其末级（促销费 560105 / 其他管理费用 560299）
INSERT INTO fin_gl_biz_subject_map (id, event_code, biz_type_code, biz_type_name, counter_subject_code, remark) VALUES
('BM_OOT_0', 'OTHER_OUT', '0', '内部领用', '560299', '内部领用计入管理费用'),
('BM_OOT_1', 'OTHER_OUT', '1', '样品出库', '560105', '促销样品计入销售费用-促销费'),
('BM_OOT_2', 'OTHER_OUT', '2', '借出出库', '1221',   '借出计入其他应收款'),
('BM_OOT_3', 'OTHER_OUT', '3', '活动消耗', '560105', '活动消耗计入销售费用-促销费'),
('BM_OOT_4', 'OTHER_OUT', '4', '内部加工', '4001',   '加工原料过渡生产成本'),
('BM_OOT_5', 'OTHER_OUT', '5', '其他',     '560299', '默认管理费用'),
('BM_OIT_0', 'OTHER_IN',  '0', '期初库存', NULL,     '建账前期初库存，钩子不丢事件；空映射双保险=不生凭证'),
('BM_OIT_1', 'OTHER_IN',  '1', '样品入库', '5301',   '无票样品入库计入营业外收入'),
('BM_OIT_2', 'OTHER_IN',  '2', '赠品入库', '5301',   '赠品入库计入营业外收入'),
('BM_OIT_3', 'OTHER_IN',  '3', '盘外发现', '5301',   '盘外存货计入营业外收入'),
('BM_OIT_4', 'OTHER_IN',  '4', '内部加工', '4001',   '加工成品回冲生产成本'),
('BM_OIT_5', 'OTHER_IN',  '5', '其他',     '5301',   '默认营业外收入');
