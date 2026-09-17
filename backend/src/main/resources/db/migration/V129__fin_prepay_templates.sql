-- V129: 供应商账户（PRD-36）M2——预付付款 / 预付退款 / 预付核销凭证模板
-- 付款单 payment_type：SETTLE 应付结算（沿用 PAYMENT 模板）/ PREPAY 预付付款 / PREPAY_REFUND 预付退款
--   预付付款审核：借 资金科目（@FUND，CF04 购买商品接受劳务支付的现金）
--                 贷 1123 预付账款（供应商辅助核算）
--   预付退款审核：借 1123 预付账款（供应商辅助核算）
--                 贷 资金科目（@FUND，CF03 收到其他与经营活动有关的现金）
--   预付核销 FX ：借 2202 应付账款（供应商辅助核算）
--                 贷 1123 预付账款（供应商辅助核算），纯往来转账无资金科目
-- 反审核由事件池按反向事件自动红字生成，无需反向模板。

-- ========== 模板主表 ==========
INSERT INTO fin_voucher_template (id, template_code, event_code, template_name, voucher_word, summary_pattern, enabled, remark) VALUES
('TPL_PREPAY_PAYMENT',  'PREPAY_PAYMENT',  'PREPAY_PAYMENT',  '预付付款', '付', '预付付款 {bill_no}', TRUE, '付款单审核（预付付款）：借资金，贷预付账款（供应商辅助核算）'),
('TPL_PREPAY_REFUND',   'PREPAY_REFUND',   'PREPAY_REFUND',   '预付退款', '收', '预付退款 {bill_no}', TRUE, '付款单审核（预付退款）：借预付账款，贷资金；退款额不得超过供应商预付余额'),
('TPL_PREPAY_WRITEOFF', 'PREPAY_WRITE_OFF','PREPAY_WRITE_OFF','预付核销', '转', '预付核销 {bill_no}', TRUE, '预付核销单审核（预付冲应付）：借2202应付账款，贷1123预付账款，均带供应商辅助核算');

-- ========== 模板分录行 ==========
INSERT INTO fin_voucher_template_line (id, template_id, line_no, direction, summary_pattern, account_expr, amount_expr, qty_expr, aux_expr, cash_flow_item, condition_expr, expand_by, lines_key) VALUES
-- PREPAY_PAYMENT 预付付款：借资金(CF04) / 贷1123
('TPL_PREPAY_PAYMENT_L1', 'TPL_PREPAY_PAYMENT', 1, '借', '预付货款 {fund_account_name}', '@FUND', 'amount_tax_incl', NULL, NULL,                          'CF04', NULL, 'NONE', 'lines'),
('TPL_PREPAY_PAYMENT_L2', 'TPL_PREPAY_PAYMENT', 2, '贷', '预付 {supplier_name} 货款',   '1123',  'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL,   NULL, 'NONE', 'lines'),
-- PREPAY_REFUND 预付退款：借1123 / 贷资金(CF03 收到其他与经营活动有关的现金）
('TPL_PREPAY_REFUND_L1',  'TPL_PREPAY_REFUND',  1, '借', '收回 {supplier_name} 预付款',  '1123',  'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL,   NULL, 'NONE', 'lines'),
('TPL_PREPAY_REFUND_L2',  'TPL_PREPAY_REFUND',  2, '贷', '预付退款 {fund_account_name}','@FUND','amount_tax_incl', NULL, NULL,                          'CF03', NULL, 'NONE', 'lines'),
-- PREPAY_WRITE_OFF 预付核销：借2202 / 贷1123
('TPL_PREPAY_WRITEOFF_L1','TPL_PREPAY_WRITEOFF',1, '借', '预付冲应付 {supplier_name}',  '2202',  'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL,   NULL, 'NONE', 'lines'),
('TPL_PREPAY_WRITEOFF_L2','TPL_PREPAY_WRITEOFF',2, '贷', '预付核销 {supplier_name}',    '1123',  'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL,   NULL, 'NONE', 'lines');
