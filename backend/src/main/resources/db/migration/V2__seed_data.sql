-- ============================================
-- V2: 系统 seed 数据（用户/角色/参数/编号规则/默认类型/默认资金账户）
-- MySQL 兼容语法：INSERT ... ON DUPLICATE KEY UPDATE
-- H2 在 MODE=MySQL 下也支持该语法
-- ============================================

-- 默认管理员：admin / admin123 （BCrypt 哈希）
INSERT INTO sys_user_runtime (user_id, username, display_name, password, mobile, role_name, data_scope, status)
VALUES ('U0001', 'admin', '系统管理员', '$2a$10$HvMZKhnDRrrfmlaoyPHO1uZvyYlQvvQksiBiUSIcgLw3JJxWgZ.Nq', '13800000000', '管理员组', '全部', 'NORMAL')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), password = VALUES(password), role_name = VALUES(role_name);

INSERT INTO sys_role_runtime (role_id, role_code, role_name, user_count, menu_scope, field_scope, data_scope, status)
VALUES ('R0001', 'ADMIN', '管理员组', 1, '*', '*', 'ALL', 'NORMAL')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), menu_scope = VALUES(menu_scope), field_scope = VALUES(field_scope), data_scope = VALUES(data_scope);
INSERT INTO sys_role_runtime (role_id, role_code, role_name, user_count, menu_scope, field_scope, data_scope, status)
VALUES ('R0002', 'SALE', '销售员组', 0, 'dashboard,sales,inventory,exportCenter,log', '隐藏成本字段', 'SELF', 'NORMAL')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), menu_scope = VALUES(menu_scope), field_scope = VALUES(field_scope), data_scope = VALUES(data_scope);
INSERT INTO sys_role_runtime (role_id, role_code, role_name, user_count, menu_scope, field_scope, data_scope, status)
VALUES ('R0003', 'PURCHASE', '采购员组', 0, 'dashboard,base,purchase,stockBalance,exportCenter,log', '*', 'DEPARTMENT', 'NORMAL')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), menu_scope = VALUES(menu_scope), field_scope = VALUES(field_scope), data_scope = VALUES(data_scope);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0001', 'CREDIT_CHECK_MODE', '信用控制', 'REMIND', 'REMIND', '销售', '可选 REMIND/BLOCK/APPROVAL')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0002', 'STOCK_NEGATIVE_ALLOWED', '允许负库存', 'false', 'false', '库存', 'V1.0默认不允许负库存')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_bill_no_rule_runtime (rule_id, bill_type, prefix, date_format, serial_length, reset_cycle, example_no, status)
VALUES ('BN001', '销售订单', 'SO', 'yyyyMMdd', 4, 'DAY', 'SO202606150001', 'NORMAL')
ON DUPLICATE KEY UPDATE bill_type = VALUES(bill_type), prefix = VALUES(prefix), example_no = VALUES(example_no);
INSERT INTO sys_bill_no_rule_runtime (rule_id, bill_type, prefix, date_format, serial_length, reset_cycle, example_no, status)
VALUES ('BN002', '采购订单', 'PO', 'yyyyMMdd', 4, 'DAY', 'PO202606150001', 'NORMAL')
ON DUPLICATE KEY UPDATE bill_type = VALUES(bill_type), prefix = VALUES(prefix), example_no = VALUES(example_no);
INSERT INTO sys_bill_no_rule_runtime (rule_id, bill_type, prefix, date_format, serial_length, reset_cycle, example_no, status)
VALUES ('BN003', '客户价格调整单', 'CPA', 'yyyyMMdd', 4, 'DAY', 'CPA202606150001', 'NORMAL')
ON DUPLICATE KEY UPDATE bill_type = VALUES(bill_type), prefix = VALUES(prefix), example_no = VALUES(example_no);

-- 往来单位默认类型
INSERT INTO base_counterparty_type (type_id, type_code, type_name, sort_order, remark, status)
VALUES ('CT001', 'PT001', '代理经销商', 1, '默认类型', 'NORMAL')
ON DUPLICATE KEY UPDATE type_name = VALUES(type_name), sort_order = VALUES(sort_order);
INSERT INTO base_counterparty_type (type_id, type_code, type_name, sort_order, remark, status)
VALUES ('CT002', 'PT002', '物流承运商', 2, '默认类型', 'NORMAL')
ON DUPLICATE KEY UPDATE type_name = VALUES(type_name), sort_order = VALUES(sort_order);
INSERT INTO base_counterparty_type (type_id, type_code, type_name, sort_order, remark, status)
VALUES ('CT003', 'PT003', '服务供应商', 3, '默认类型', 'NORMAL')
ON DUPLICATE KEY UPDATE type_name = VALUES(type_name), sort_order = VALUES(sort_order);
INSERT INTO base_counterparty_type (type_id, type_code, type_name, sort_order, remark, status)
VALUES ('CT004', 'PT004', '合作厂商', 4, '默认类型', 'NORMAL')
ON DUPLICATE KEY UPDATE type_name = VALUES(type_name), sort_order = VALUES(sort_order);
INSERT INTO base_counterparty_type (type_id, type_code, type_name, sort_order, remark, status)
VALUES ('CT005', 'PT005', '其他往来单位', 5, '默认类型', 'NORMAL')
ON DUPLICATE KEY UPDATE type_name = VALUES(type_name), sort_order = VALUES(sort_order);

-- 资金账户系统默认一级分类（不可编辑/删除/停用）
INSERT INTO base_fund_account (fund_account_id, fund_account_code, fund_account_name, account_type, balance, remark, status, parent_code, is_system)
VALUES ('FA_SYS_01', '01', '现金',     '', 0, '系统默认', 'NORMAL', '', TRUE)
ON DUPLICATE KEY UPDATE fund_account_name = VALUES(fund_account_name), is_system = VALUES(is_system);
INSERT INTO base_fund_account (fund_account_id, fund_account_code, fund_account_name, account_type, balance, remark, status, parent_code, is_system)
VALUES ('FA_SYS_02', '02', '银行卡',   '', 0, '系统默认', 'NORMAL', '', TRUE)
ON DUPLICATE KEY UPDATE fund_account_name = VALUES(fund_account_name), is_system = VALUES(is_system);
INSERT INTO base_fund_account (fund_account_id, fund_account_code, fund_account_name, account_type, balance, remark, status, parent_code, is_system)
VALUES ('FA_SYS_03', '03', '微信',     '', 0, '系统默认', 'NORMAL', '', TRUE)
ON DUPLICATE KEY UPDATE fund_account_name = VALUES(fund_account_name), is_system = VALUES(is_system);
INSERT INTO base_fund_account (fund_account_id, fund_account_code, fund_account_name, account_type, balance, remark, status, parent_code, is_system)
VALUES ('FA_SYS_04', '04', '支付宝',   '', 0, '系统默认', 'NORMAL', '', TRUE)
ON DUPLICATE KEY UPDATE fund_account_name = VALUES(fund_account_name), is_system = VALUES(is_system);
INSERT INTO base_fund_account (fund_account_id, fund_account_code, fund_account_name, account_type, balance, remark, status, parent_code, is_system)
VALUES ('FA_SYS_05', '05', '三方账户', '', 0, '系统默认', 'NORMAL', '', TRUE)
ON DUPLICATE KEY UPDATE fund_account_name = VALUES(fund_account_name), is_system = VALUES(is_system);
INSERT INTO base_fund_account (fund_account_id, fund_account_code, fund_account_name, account_type, balance, remark, status, parent_code, is_system)
VALUES ('FA_SYS_06', '06', '其他',     '', 0, '系统默认', 'NORMAL', '', TRUE)
ON DUPLICATE KEY UPDATE fund_account_name = VALUES(fund_account_name), is_system = VALUES(is_system);
