-- V125: 客户账户（PRD-35）M2——预收收款 / 预收退款凭证模板
-- 收款单 receipt_type：SETTLE 应收结算（沿用 RECEIPT 模板）/ ADVANCE 预收收款 / ADVANCE_REFUND 预收退款
--   预收收款审核：借 资金科目（@FUND）  贷 2203 预收账款（客户辅助核算）
--   预收退款审核：借 2203 预收账款      贷 资金科目（@FUND）
-- 反审核由事件池按反向事件自动红字生成，无需反向模板。
-- M3 的预收核销（借 2203 / 贷 1122，事件码 ADVANCE_WRITE_OFF）在后续迁移补模板。

-- ========== 模板主表 ==========
INSERT INTO fin_voucher_template (id, template_code, event_code, template_name, voucher_word, summary_pattern, enabled, remark) VALUES
('TPL_ADV_RECEIPT', 'ADVANCE_RECEIPT', 'ADVANCE_RECEIPT', '预收收款', '收', '预收收款 {bill_no}', TRUE,  '收款单审核（预收收款）：借资金，贷预收账款（客户辅助核算）'),
('TPL_ADV_REFUND',  'ADVANCE_REFUND',  'ADVANCE_REFUND',  '预收退款', '付', '预收退款 {bill_no}', TRUE,  '收款单审核（预收退款）：借预收账款，贷资金；退款额不得超过客户预收余额');

-- ========== 模板分录行 ==========
-- 预收退款的资金流出仍挂 CF01（销售商品、提供劳务收到的现金），
-- 在现金流量表中按资金科目方向与预收收款自动轧差，符合预收退回冲减原项目的列报口径。
INSERT INTO fin_voucher_template_line (id, template_id, line_no, direction, summary_pattern, account_expr, amount_expr, qty_expr, aux_expr, cash_flow_item, condition_expr, expand_by, lines_key) VALUES
-- ADVANCE_RECEIPT 预收收款：借资金 / 贷2203
('TPL_ADV_RECEIPT_L1', 'TPL_ADV_RECEIPT', 1, '借', '预收收款 {fund_account_name}', '@FUND', 'amount_tax_incl', NULL, NULL,                          'CF01', NULL, 'NONE', 'lines'),
('TPL_ADV_RECEIPT_L2', 'TPL_ADV_RECEIPT', 2, '贷', '预收 {customer_name} 货款',    '2203',  'amount_tax_incl', NULL, '{"customer":"customer_code"}', NULL,   NULL, 'NONE', 'lines'),
-- ADVANCE_REFUND 预收退款：借2203 / 贷资金
('TPL_ADV_REFUND_L1',  'TPL_ADV_REFUND',  1, '借', '退还 {customer_name} 预收款',  '2203',  'amount_tax_incl', NULL, '{"customer":"customer_code"}', NULL,   NULL, 'NONE', 'lines'),
('TPL_ADV_REFUND_L2',  'TPL_ADV_REFUND',  2, '贷', '预收退款 {fund_account_name}', '@FUND', 'amount_tax_incl', NULL, NULL,                          'CF01', NULL, 'NONE', 'lines');
