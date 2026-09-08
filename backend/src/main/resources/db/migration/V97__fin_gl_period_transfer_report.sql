-- =====================================================
-- 总账 M5：期末处理
--   1. 自动转账模板（fin_auto_transfer + entry）：月末计提/结转固定分录，金额走报表公式
--   2. 报表项目种子（fin_report_item）：BS/IS/CF 三表取数公式，M6 报表引擎直接读取
-- 幂等：建表 IF NOT EXISTS、种子 MERGE KEY；无外键、无 DROP。
-- =====================================================

-- ========== 1. 自动转账模板 ==========
CREATE TABLE IF NOT EXISTS fin_auto_transfer (
    id               VARCHAR(32)  PRIMARY KEY,
    transfer_no      VARCHAR(20)  NOT NULL UNIQUE,   -- ZZ01...
    transfer_name    VARCHAR(100) NOT NULL,
    condition_expr   VARCHAR(500),                   -- 成立才执行（变量：taxpayer/surtax_rate + 报表函数）
    enabled          BOOLEAN      DEFAULT TRUE,
    is_system        BOOLEAN      DEFAULT FALSE,
    remark           VARCHAR(300),
    create_time      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS fin_auto_transfer_entry (
    id               VARCHAR(32)  PRIMARY KEY,
    transfer_id      VARCHAR(32)  NOT NULL,
    line_no          INT          NOT NULL,
    direction        VARCHAR(2)   NOT NULL,          -- 借/贷
    summary          VARCHAR(200),
    account_code     VARCHAR(32)  NOT NULL,
    amount_expr      VARCHAR(500) NOT NULL,          -- 报表公式（FSD/FSC/QM...）
    aux_config       VARCHAR(300),                   -- 辅助核算 JSON（同凭证模板格式）
    create_time      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_auto_trans_entry ON fin_auto_transfer_entry(transfer_id);

-- 预置 2 张（月末向导自动执行；会计可自建 ZZ03+ 房租摊销/工资计提等）
MERGE INTO fin_auto_transfer (id, transfer_no, transfer_name, condition_expr, enabled, is_system, remark) KEY(id) VALUES
('ZZ01', 'ZZ01', '结转未交增值税', 'taxpayer == ''GENERAL''', TRUE, TRUE,
 '一般纳税人月末结转：销项-进项>0 时转出未交增值税；金额为零自动跳过'),
('ZZ02', 'ZZ02', '计提附加税费', '(FSC(22210102)-FSD(22210101)) > 0', TRUE, TRUE,
 '按应交增值税计提城建税7%/教育费附加3%/地方教育附加2%（合计率参数 fin.gl.surtax_rate 默认12%，种子按标准率分行）');

MERGE INTO fin_auto_transfer_entry (id, transfer_id, line_no, direction, summary, account_code, amount_expr, aux_config) KEY(id) VALUES
-- ZZ01 结转未交增值税：借 转出未交增值税 / 贷 未交增值税
('ZZ01L1', 'ZZ01', 1, '借', '结转未交增值税',     '22210105', 'FSC(22210102)-FSD(22210101)', NULL),
('ZZ01L2', 'ZZ01', 2, '贷', '结转未交增值税',     '222102',   'FSC(22210102)-FSD(22210101)', NULL),
-- ZZ02 计提附加税费：借 税金及附加 / 贷 应交税费明细
('ZZ02L1', 'ZZ02', 1, '借', '计提附加税费',       '5403',     '(FSC(22210102)-FSD(22210101))*0.12', NULL),
('ZZ02L2', 'ZZ02', 2, '贷', '应交城市维护建设税', '222104',   '(FSC(22210102)-FSD(22210101))*0.07', NULL),
('ZZ02L3', 'ZZ02', 3, '贷', '应交教育费附加',     '222105',   '(FSC(22210102)-FSD(22210101))*0.03', NULL),
('ZZ02L4', 'ZZ02', 4, '贷', '应交地方教育附加',   '222106',   '(FSC(22210102)-FSD(22210101))*0.02', NULL);

-- ========== 2. 报表项目种子（BS/IS/CF） ==========
CREATE TABLE IF NOT EXISTS fin_report_item (
    id               VARCHAR(32)  PRIMARY KEY,
    report_code      VARCHAR(10)  NOT NULL,         -- BS 资产负债表 / IS 利润表 / CF 现金流量表
    line_no          INT          NOT NULL,
    item_name        VARCHAR(100) NOT NULL,
    row_type         VARCHAR(10)  DEFAULT '项目',   -- 项目/小计/合计
    formula          VARCHAR(800),                  -- 期末余额（BS）/本月数（IS）/本月金额（CF）
    formula_begin    VARCHAR(800),                  -- 年初余额（BS）/本年累计（IS）
    indent_level     INT          DEFAULT 0,
    is_system        BOOLEAN      DEFAULT TRUE,
    remark           VARCHAR(200)
);
CREATE INDEX IF NOT EXISTS idx_fin_report_item_report ON fin_report_item(report_code, line_no);

-- ---------- BS 资产负债表（小企业会计准则，formula=期末 QM，formula_begin=年初 QC） ----------
MERGE INTO fin_report_item (id, report_code, line_no, item_name, row_type, formula, formula_begin, indent_level) KEY(id) VALUES
-- 资产
('RBS01', 'BS', 1,  '货币资金',        '项目', 'QM(1001)+QM(1002)+QM(1012)', 'QC(1001)+QC(1002)+QC(1012)', 1),
('RBS02', 'BS', 2,  '应收账款',        '项目', 'QM(1122)', 'QC(1122)', 1),
('RBS03', 'BS', 3,  '预付账款',        '项目', 'QM(1123)', 'QC(1123)', 1),
('RBS04', 'BS', 4,  '其他应收款',      '项目', 'QM(1221)', 'QC(1221)', 1),
('RBS05', 'BS', 5,  '存货',            '项目', 'QM(1405)+QM(4001)', 'QC(1405)+QC(4001)', 1),
('RBS06', 'BS', 6,  '固定资产原价',    '项目', 'QM(1601)', 'QC(1601)', 1),
('RBS07', 'BS', 7,  '减：累计折旧',    '项目', 'QM(1602)', 'QC(1602)', 2),
('RBS08', 'BS', 8,  '固定资产账面价值','项目', 'QM(1601)-QM(1602)', 'QC(1601)-QC(1602)', 1),
('RBS09', 'BS', 9,  '无形资产',        '项目', 'QM(1701)-QM(1702)', 'QC(1701)-QC(1702)', 1),
('RBS10', 'BS', 10, '长期待摊费用',    '项目', 'QM(1801)', 'QC(1801)', 1),
('RBS11', 'BS', 11, '待处理财产损溢',  '项目', 'QM(1901)', 'QC(1901)', 1),
('RBS12', 'BS', 12, '资产总计',        '合计',
 'QM(1001)+QM(1002)+QM(1012)+QM(1122)+QM(1123)+QM(1221)+QM(1405)+QM(4001)+QM(1601)-QM(1602)+QM(1701)-QM(1702)+QM(1801)+QM(1901)',
 'QC(1001)+QC(1002)+QC(1012)+QC(1122)+QC(1123)+QC(1221)+QC(1405)+QC(4001)+QC(1601)-QC(1602)+QC(1701)-QC(1702)+QC(1801)+QC(1901)', 0),
-- 负债
('RBS13', 'BS', 13, '短期借款',        '项目', 'QM(2001)', 'QC(2001)', 1),
('RBS14', 'BS', 14, '应付票据',        '项目', 'QM(2201)', 'QC(2201)', 1),
('RBS15', 'BS', 15, '应付账款',        '项目', 'QM(2202)', 'QC(2202)', 1),
('RBS16', 'BS', 16, '预收账款',        '项目', 'QM(2203)', 'QC(2203)', 1),
('RBS17', 'BS', 17, '应付职工薪酬',    '项目', 'QM(2211)', 'QC(2211)', 1),
('RBS18', 'BS', 18, '应交税费',        '项目', 'QM(2221)', 'QC(2221)', 1),
('RBS19', 'BS', 19, '其他应付款',      '项目', 'QM(2241)', 'QC(2241)', 1),
('RBS20', 'BS', 20, '负债合计',        '小计',
 'QM(2001)+QM(2201)+QM(2202)+QM(2203)+QM(2211)+QM(2221)+QM(2241)',
 'QC(2001)+QC(2201)+QC(2202)+QC(2203)+QC(2211)+QC(2221)+QC(2241)', 0),
-- 所有者权益
('RBS21', 'BS', 21, '实收资本',        '项目', 'QM(3001)', 'QC(3001)', 1),
('RBS22', 'BS', 22, '资本公积',        '项目', 'QM(3002)', 'QC(3002)', 1),
('RBS23', 'BS', 23, '盈余公积',        '项目', 'QM(3101)', 'QC(3101)', 1),
-- 未分配利润行：3104 利润分配 + 3103 本年利润 + 损益类期末余额（结转前期中也平，结转后损益为 0 不重复）
('RBS24', 'BS', 24, '未分配利润',      '项目',
 'QM(3104)+QM(3103)+QM(5001)+QM(5051)+QM(5301)-QM(5401)-QM(5402)-QM(5403)-QM(5601)-QM(5602)-QM(5603)-QM(5701)-QM(5711)',
 'QC(3104)+QC(3103)+QC(5001)+QC(5051)+QC(5301)-QC(5401)-QC(5402)-QC(5403)-QC(5601)-QC(5602)-QC(5603)-QC(5701)-QC(5711)', 1),
('RBS25', 'BS', 25, '所有者权益合计',  '小计',
 'QM(3001)+QM(3002)+QM(3101)+QM(3104)+QM(3103)+QM(5001)+QM(5051)+QM(5301)-QM(5401)-QM(5402)-QM(5403)-QM(5601)-QM(5602)-QM(5603)-QM(5701)-QM(5711)',
 'QC(3001)+QC(3002)+QC(3101)+QC(3104)+QC(3103)+QC(5001)+QC(5051)+QC(5301)-QC(5401)-QC(5402)-QC(5403)-QC(5601)-QC(5602)-QC(5603)-QC(5701)-QC(5711)', 0),
('RBS26', 'BS', 26, '负债和所有者权益总计', '合计',
 'QM(2001)+QM(2201)+QM(2202)+QM(2203)+QM(2211)+QM(2221)+QM(2241)+QM(3001)+QM(3002)+QM(3101)+QM(3104)+QM(3103)+QM(5001)+QM(5051)+QM(5301)-QM(5401)-QM(5402)-QM(5403)-QM(5601)-QM(5602)-QM(5603)-QM(5701)-QM(5711)',
 'QC(2001)+QC(2201)+QC(2202)+QC(2203)+QC(2211)+QC(2221)+QC(2241)+QC(3001)+QC(3002)+QC(3101)+QC(3104)+QC(3103)+QC(5001)+QC(5051)+QC(5301)-QC(5401)-QC(5402)-QC(5403)-QC(5601)-QC(5602)-QC(5603)-QC(5701)-QC(5711)', 0);

-- ---------- IS 利润表（formula=本月数 FSD/FSC，formula_begin=本年累计 LJFS/LJFSC） ----------
MERGE INTO fin_report_item (id, report_code, line_no, item_name, row_type, formula, formula_begin, indent_level) KEY(id) VALUES
('RIS01', 'IS', 1,  '一、营业收入',     '项目', 'FSC(5001)+FSC(5051)', 'LJFSC(5001)+LJFSC(5051)', 0),
('RIS02', 'IS', 2,  '减：营业成本',     '项目', 'FSD(5401)+FSD(5402)', 'LJFS(5401)+LJFS(5402)', 1),
('RIS03', 'IS', 3,  '税金及附加',       '项目', 'FSD(5403)', 'LJFS(5403)', 1),
('RIS04', 'IS', 4,  '销售费用',         '项目', 'FSD(5601)', 'LJFS(5601)', 1),
('RIS05', 'IS', 5,  '管理费用',         '项目', 'FSD(5602)', 'LJFS(5602)', 1),
('RIS06', 'IS', 6,  '财务费用',         '项目', 'FSD(5603)', 'LJFS(5603)', 1),
('RIS07', 'IS', 7,  '二、营业利润',     '小计',
 'FSC(5001)+FSC(5051)-FSD(5401)-FSD(5402)-FSD(5403)-FSD(5601)-FSD(5602)-FSD(5603)',
 'LJFSC(5001)+LJFSC(5051)-LJFS(5401)-LJFS(5402)-LJFS(5403)-LJFS(5601)-LJFS(5602)-LJFS(5603)', 0),
('RIS08', 'IS', 8,  '加：营业外收入',   '项目', 'FSC(5301)', 'LJFSC(5301)', 1),
('RIS09', 'IS', 9,  '减：营业外支出',   '项目', 'FSD(5711)', 'LJFS(5711)', 1),
('RIS10', 'IS', 10, '三、利润总额',     '小计',
 'FSC(5001)+FSC(5051)-FSD(5401)-FSD(5402)-FSD(5403)-FSD(5601)-FSD(5602)-FSD(5603)+FSC(5301)-FSD(5711)',
 'LJFSC(5001)+LJFSC(5051)-LJFS(5401)-LJFS(5402)-LJFS(5403)-LJFS(5601)-LJFS(5602)-LJFS(5603)+LJFSC(5301)-LJFS(5711)', 0),
('RIS11', 'IS', 11, '减：所得税费用',   '项目', 'FSD(5701)', 'LJFS(5701)', 1),
('RIS12', 'IS', 12, '四、净利润',       '合计',
 'FSC(5001)+FSC(5051)-FSD(5401)-FSD(5402)-FSD(5403)-FSD(5601)-FSD(5602)-FSD(5603)+FSC(5301)-FSD(5711)-FSD(5701)',
 'LJFSC(5001)+LJFSC(5051)-LJFS(5401)-LJFS(5402)-LJFS(5403)-LJFS(5601)-LJFS(5602)-LJFS(5603)+LJFSC(5301)-LJFS(5711)-LJFS(5701)', 0);

-- ---------- CF 现金流量表（直接法，分录级流量项目；formula=本月 CF 项目，formula_begin=本年累计） ----------
MERGE INTO fin_report_item (id, report_code, line_no, item_name, row_type, formula, formula_begin, indent_level) KEY(id) VALUES
('RCF01', 'CF', 1,  '销售商品、提供劳务收到的现金',   '项目', 'CF(''CF01'')', 'CFY(''CF01'')', 1),
('RCF02', 'CF', 2,  '收到的税费返还',                 '项目', 'CF(''CF02'')', 'CFY(''CF02'')', 1),
('RCF03', 'CF', 3,  '收到其他与经营活动有关的现金',   '项目', 'CF(''CF03'')', 'CFY(''CF03'')', 1),
('RCF04', 'CF', 4,  '经营活动现金流入小计',           '小计', 'CF(''CF01'')+CF(''CF02'')+CF(''CF03'')', 'CFY(''CF01'')+CFY(''CF02'')+CFY(''CF03'')', 0),
('RCF05', 'CF', 5,  '购买商品、接受劳务支付的现金',   '项目', 'CF(''CF04'')', 'CFY(''CF04'')', 1),
('RCF06', 'CF', 6,  '支付给职工以及为职工支付的现金', '项目', 'CF(''CF05'')', 'CFY(''CF05'')', 1),
('RCF07', 'CF', 7,  '支付的各项税费',                 '项目', 'CF(''CF06'')', 'CFY(''CF06'')', 1),
('RCF08', 'CF', 8,  '支付其他与经营活动有关的现金',   '项目', 'CF(''CF07'')', 'CFY(''CF07'')', 1),
('RCF09', 'CF', 9,  '经营活动现金流出小计',           '小计', 'CF(''CF04'')+CF(''CF05'')+CF(''CF06'')+CF(''CF07'')', 'CFY(''CF04'')+CFY(''CF05'')+CFY(''CF06'')+CFY(''CF07'')', 0),
('RCF10', 'CF', 10, '经营活动产生的现金流量净额',     '合计',
 'CF(''CF01'')+CF(''CF02'')+CF(''CF03'')-CF(''CF04'')-CF(''CF05'')-CF(''CF06'')-CF(''CF07'')',
 'CFY(''CF01'')+CFY(''CF02'')+CFY(''CF03'')-CFY(''CF04'')-CFY(''CF05'')-CFY(''CF06'')-CFY(''CF07'')', 0),
('RCF11', 'CF', 11, '收回投资收到的现金',             '项目', 'CF(''CF08'')', 'CFY(''CF08'')', 1),
('RCF12', 'CF', 12, '取得投资收益收到的现金',         '项目', 'CF(''CF09'')', 'CFY(''CF09'')', 1),
('RCF13', 'CF', 13, '处置固定资产、无形资产收回的现金净额', '项目', 'CF(''CF10'')', 'CFY(''CF10'')', 1),
('RCF14', 'CF', 14, '收到其他与投资活动有关的现金',   '项目', 'CF(''CF11'')', 'CFY(''CF11'')', 1),
('RCF15', 'CF', 15, '投资活动现金流入小计',           '小计', 'CF(''CF08'')+CF(''CF09'')+CF(''CF10'')+CF(''CF11'')', 'CFY(''CF08'')+CFY(''CF09'')+CFY(''CF10'')+CFY(''CF11'')', 0),
('RCF16', 'CF', 16, '购建固定资产、无形资产支付的现金','项目', 'CF(''CF12'')', 'CFY(''CF12'')', 1),
('RCF17', 'CF', 17, '投资支付的现金',                 '项目', 'CF(''CF13'')', 'CFY(''CF13'')', 1),
('RCF18', 'CF', 18, '支付其他与投资活动有关的现金',   '项目', 'CF(''CF14'')', 'CFY(''CF14'')', 1),
('RCF19', 'CF', 19, '投资活动现金流出小计',           '小计', 'CF(''CF12'')+CF(''CF13'')+CF(''CF14'')', 'CFY(''CF12'')+CFY(''CF13'')+CFY(''CF14'')', 0),
('RCF20', 'CF', 20, '投资活动产生的现金流量净额',     '合计',
 'CF(''CF08'')+CF(''CF09'')+CF(''CF10'')+CF(''CF11'')-CF(''CF12'')-CF(''CF13'')-CF(''CF14'')',
 'CFY(''CF08'')+CFY(''CF09'')+CFY(''CF10'')+CFY(''CF11'')-CFY(''CF12'')-CFY(''CF13'')-CFY(''CF14'')', 0),
('RCF21', 'CF', 21, '吸收投资收到的现金',             '项目', 'CF(''CF15'')', 'CFY(''CF15'')', 1),
('RCF22', 'CF', 22, '取得借款收到的现金',             '项目', 'CF(''CF16'')', 'CFY(''CF16'')', 1),
('RCF23', 'CF', 23, '收到其他与筹资活动有关的现金',   '项目', 'CF(''CF17'')', 'CFY(''CF17'')', 1),
('RCF24', 'CF', 24, '筹资活动现金流入小计',           '小计', 'CF(''CF15'')+CF(''CF16'')+CF(''CF17'')', 'CFY(''CF15'')+CFY(''CF16'')+CFY(''CF17'')', 0),
('RCF25', 'CF', 25, '偿还债务支付的现金',             '项目', 'CF(''CF18'')', 'CFY(''CF18'')', 1),
('RCF26', 'CF', 26, '分配股利、利润或偿付利息支付的现金','项目', 'CF(''CF19'')', 'CFY(''CF19'')', 1),
('RCF27', 'CF', 27, '支付其他与筹资活动有关的现金',   '项目', 'CF(''CF20'')', 'CFY(''CF20'')', 1),
('RCF28', 'CF', 28, '筹资活动现金流出小计',           '小计', 'CF(''CF18'')+CF(''CF19'')+CF(''CF20'')', 'CFY(''CF18'')+CFY(''CF19'')+CFY(''CF20'')', 0),
('RCF29', 'CF', 29, '筹资活动产生的现金流量净额',     '合计',
 'CF(''CF15'')+CF(''CF16'')+CF(''CF17'')-CF(''CF18'')-CF(''CF19'')-CF(''CF20'')',
 'CFY(''CF15'')+CFY(''CF16'')+CFY(''CF17'')-CFY(''CF18'')-CFY(''CF19'')-CFY(''CF20'')', 0),
('RCF30', 'CF', 30, '现金及现金等价物净增加额',       '合计',
 'QM(1001)+QM(1002)+QM(1012)-QC(1001)-QC(1002)-QC(1012)',
 'QM(1001)+QM(1002)+QM(1012)-QC(1001)-QC(1002)-QC(1012)', 0);
