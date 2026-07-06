-- ============================================
-- 仅保留必要的系统配置数据（用户/角色/参数/编号规则）
-- 业务数据全部由用户在应用中自行创建
-- ============================================

MERGE INTO sys_user_runtime KEY(user_id) VALUES ('U0001', 'admin', '系统管理员', 'admin123', '13800000000', '管理员组', '全部', 'NORMAL');

MERGE INTO sys_role_runtime KEY(role_id) VALUES ('R0001', 'ADMIN', '管理员组', 1, '*', '*', 'ALL', 'NORMAL');
MERGE INTO sys_role_runtime KEY(role_id) VALUES ('R0002', 'SALE', '销售员组', 0, 'dashboard,sales,inventory,exportCenter,log', '隐藏成本字段', 'SELF', 'NORMAL');
MERGE INTO sys_role_runtime KEY(role_id) VALUES ('R0003', 'PURCHASE', '采购员组', 0, 'dashboard,base,purchase,stockBalance,exportCenter,log', '*', 'DEPARTMENT', 'NORMAL');

MERGE INTO sys_param_runtime KEY(param_id) VALUES ('P0001', 'CREDIT_CHECK_MODE', '信用控制', 'REMIND', 'REMIND', '销售', '可选 REMIND/BLOCK/APPROVAL');
MERGE INTO sys_param_runtime KEY(param_id) VALUES ('P0002', 'STOCK_NEGATIVE_ALLOWED', '允许负库存', 'false', 'false', '库存', 'V1.0默认不允许负库存');

MERGE INTO sys_bill_no_rule_runtime KEY(rule_id) VALUES ('BN001', '销售订单', 'SO', 'yyyyMMdd', 4, 'DAY', 'SO202606150001', 'NORMAL');
MERGE INTO sys_bill_no_rule_runtime KEY(rule_id) VALUES ('BN002', '采购订单', 'PO', 'yyyyMMdd', 4, 'DAY', 'PO202606150001', 'NORMAL');
MERGE INTO sys_bill_no_rule_runtime KEY(rule_id) VALUES ('BN003', '客户价格调整单', 'CPA', 'yyyyMMdd', 4, 'DAY', 'CPA202606150001', 'NORMAL');
