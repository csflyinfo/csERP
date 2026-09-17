-- V130: 供应商账户（PRD-36）M3——厂家费用单 JF / 厂家费用兑现单 DX 凭证模板 + 补录开关参数
--
-- 四个业务事件（反审核由事件池按反向事件自动红字生成，无需反向模板）：
--   FACTORY_EXPENSE        厂家费用单审核：借 1221 其他应收款（供应商辅助核算）
--                                            贷 费用科目（按明细行 gl_credit_subject 展开）
--                          代垫行=客户费用单原路费用科目；其他行=费用类型档案 factory_gl_credit_subject_code 或默认 5401；
--                          红字 JF 金额为负、同构正向事件（同 PUR_RETURN 范式）；
--                          OPENING_BACKFILL 补录单业务侧根本不发事件（与总账 1221 期初余额避免重复记账）。
--   FACTORY_SETTLE_CASH    现金兑现：借 资金科目（@FUND，多资金账户按 fund_lines 展开；
--                                            现金流量固定 CF03 收到其他与经营活动有关的现金）
--                                 贷 1221（供应商辅助核算）
--   FACTORY_SETTLE_OFFSET  账扣兑现：借 2202 应付账款（供应商辅助核算）
--                                 贷 1221（供应商辅助核算），纯往来转账无现金流量
--   FACTORY_SETTLE_OTHER   其他兑现：借 对方科目（一单一个：1123/1405/5601%/5602%/5711，
--                                            1123 时挂供应商辅助核算，其余科目未启用供应商维度自动不挂）
--                                 贷 1221（供应商辅助核算）

-- ========== 模板主表 ==========
INSERT INTO fin_voucher_template (id, template_code, event_code, template_name, voucher_word, summary_pattern, enabled, remark) VALUES
('TPL_FACTORY_EXPENSE',        'FACTORY_EXPENSE',        'FACTORY_EXPENSE',        '厂家费用立账', '转', '厂家费用 {bill_no}', TRUE, '厂家费用单审核：借1221其他应收款（供应商辅助），贷按明细行费用科目展开；红字单金额为负'),
('TPL_FACTORY_SETTLE_CASH',    'FACTORY_SETTLE_CASH',    'FACTORY_SETTLE_CASH',    '厂家费用现金兑现', '收', '厂家费用现金兑现 {bill_no}', TRUE, '厂家打款兑现费用：借资金科目（CF03 收到其他与经营活动有关的现金），贷1221'),
('TPL_FACTORY_SETTLE_OFFSET',  'FACTORY_SETTLE_OFFSET',  'FACTORY_SETTLE_OFFSET',  '厂家费用账扣', '转', '厂家费用账扣 {bill_no}', TRUE, '费用冲应付：借2202应付账款，贷1221其他应收款，均供应商辅助核算，无现金流量'),
('TPL_FACTORY_SETTLE_OTHER',   'FACTORY_SETTLE_OTHER',   'FACTORY_SETTLE_OTHER',   '厂家费用其他兑现', '转', '厂家费用其他兑现 {bill_no}', TRUE, '货补/减免/坏账/转预付：借对方科目（1123挂供应商辅助），贷1221');

-- ========== 模板分录行 ==========
INSERT INTO fin_voucher_template_line (id, template_id, line_no, direction, summary_pattern, account_expr, amount_expr, qty_expr, aux_expr, cash_flow_item, condition_expr, expand_by, lines_key) VALUES
-- 金额变量约定：NONE 头行取事件顶层标量 vars，金额键固定 amount_tax_incl（与 V95/V125/V129 模板一致）；
-- EXPENSE_LINE/FUND_LINE 展开行才合并明细行 vars，行金额键为 amount。
-- FACTORY_EXPENSE 厂家费用立账：借1221（头金额） / 贷按明细行 subject_code 展开（@EXPENSE 首取行 vars.subject_code）
('TPL_FACTORY_EXPENSE_L1', 'TPL_FACTORY_EXPENSE', 1, '借', '厂家费用 {supplier_name} {bill_no}', '1221',     'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL, NULL, 'NONE', 'lines'),
('TPL_FACTORY_EXPENSE_L2', 'TPL_FACTORY_EXPENSE', 2, '贷', '{expense_type_name}',               '@EXPENSE', 'amount', NULL, NULL,                          NULL, NULL, 'EXPENSE_LINE', 'lines'),
-- FACTORY_SETTLE_CASH 现金兑现：借资金（按资金行展开，CF03） / 贷1221
('TPL_FACTORY_SETTLE_CASH_L1', 'TPL_FACTORY_SETTLE_CASH', 1, '借', '收到厂家兑现款 {fund_account_name}', '@FUND', 'amount', NULL, NULL,                          'CF03', NULL, 'FUND_LINE', 'fund_lines'),
('TPL_FACTORY_SETTLE_CASH_L2', 'TPL_FACTORY_SETTLE_CASH', 2, '贷', '兑现厂家费用 {supplier_name}',       '1221',  'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL,   NULL, 'NONE', 'fund_lines'),
-- FACTORY_SETTLE_OFFSET 账扣：借2202 / 贷1221
('TPL_FACTORY_SETTLE_OFFSET_L1', 'TPL_FACTORY_SETTLE_OFFSET', 1, '借', '厂家费用冲应付 {supplier_name}', '2202', 'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL, NULL, 'NONE', 'lines'),
('TPL_FACTORY_SETTLE_OFFSET_L2', 'TPL_FACTORY_SETTLE_OFFSET', 2, '贷', '厂家费用账扣 {supplier_name}',   '1221', 'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL, NULL, 'NONE', 'lines'),
-- FACTORY_SETTLE_OTHER 其他兑现：借头对方科目（@EXPENSE 取顶置 subject_code；1123 挂供应商辅助，其余科目自动跳过该维度） / 贷1221
('TPL_FACTORY_SETTLE_OTHER_L1', 'TPL_FACTORY_SETTLE_OTHER', 1, '借', '{contra_subject_name} {supplier_name}', '@EXPENSE', 'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL, NULL, 'NONE', 'lines'),
('TPL_FACTORY_SETTLE_OTHER_L2', 'TPL_FACTORY_SETTLE_OTHER', 2, '贷', '厂家费用其他兑现 {supplier_name}',       '1221',     'amount_tax_incl', NULL, '{"supplier":"supplier_code"}', NULL, NULL, 'NONE', 'lines');

-- ========== 参数 P0190：上线历史厂家费用补录开关（默认关） ==========
-- Y 时厂家费用单抽屉显示「上线历史补录单」勾选；补录单审核只写供应商费用账户流水、不发 GL 事件
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0190', 'fin.fexp.opening-backfill.enabled', '厂家费用上线历史补录入口', 'N', 'N', '财务管理',
        'Y=厂家费用单允许勾选「上线历史补录单」（business_source=OPENING_BACKFILL），补录单审核立费用账户但不生成总账凭证，避免与1221期初余额重复记账；N=隐藏入口')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);
