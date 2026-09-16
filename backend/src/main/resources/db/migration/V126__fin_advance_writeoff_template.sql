-- V126: 客户账户（PRD-35）M3——预收核销凭证模板
-- 预收核销单（XH）审核：预收冲应收，纯往来转账，不涉及资金科目
--   借 2203 预收账款（客户辅助核算）
--   贷 1122 应收账款（客户辅助核算）
-- 反审核由事件池按反向事件自动红字生成，无需反向模板。

-- ========== 模板主表 ==========
INSERT INTO fin_voucher_template (id, template_code, event_code, template_name, voucher_word, summary_pattern, enabled, remark) VALUES
('TPL_ADVANCE_WRITEOFF', 'ADVANCE_WRITE_OFF', 'ADVANCE_WRITE_OFF', '预收核销', '转', '预收核销 {bill_no}', TRUE, '预收核销单审核（预收冲应收）：借2203预收账款，贷1122应收账款，均带客户辅助核算');

-- ========== 模板分录行 ==========
INSERT INTO fin_voucher_template_line (id, template_id, line_no, direction, summary_pattern, account_expr, amount_expr, qty_expr, aux_expr, cash_flow_item, condition_expr, expand_by, lines_key) VALUES
('TPL_ADVANCE_WRITEOFF_L1', 'TPL_ADVANCE_WRITEOFF', 1, '借', '预收核销 {customer_name}', '2203', 'amount_tax_incl', NULL, '{"customer":"customer_code"}', NULL, NULL, 'NONE', 'lines'),
('TPL_ADVANCE_WRITEOFF_L2', 'TPL_ADVANCE_WRITEOFF', 2, '贷', '预收冲应收 {customer_name}', '1122', 'amount_tax_incl', NULL, '{"customer":"customer_code"}', NULL, NULL, 'NONE', 'lines');
