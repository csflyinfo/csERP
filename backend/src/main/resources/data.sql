MERGE INTO base_category KEY(category_id) VALUES ('CATE01', NULL, '', '01', '食品饮料', '13%', 128, 'NORMAL', CURRENT_TIMESTAMP);
MERGE INTO base_category KEY(category_id) VALUES ('CATE0101', 'CATE01', '01', '0101', '饮料', '13%', 86, 'NORMAL', CURRENT_TIMESTAMP);
MERGE INTO base_category KEY(category_id) VALUES ('CATE010101', 'CATE0101', '0101', '010101', '瓶装水', '13%', 32, 'NORMAL', CURRENT_TIMESTAMP);

MERGE INTO base_unit KEY(unit_id) VALUES ('UNIT001', 'U001', '瓶', TRUE, FALSE, FALSE, 128, 'NORMAL');
MERGE INTO base_unit KEY(unit_id) VALUES ('UNIT002', 'U002', '箱', TRUE, FALSE, TRUE, 86, 'NORMAL');

MERGE INTO base_brand KEY(brand_id) VALUES ('BR001', 'B001', '农夫山泉', 'NFSQ', 86, 'NORMAL');
MERGE INTO base_brand KEY(brand_id) VALUES ('BR002', 'B002', '康师傅', 'KSF', 42, 'NORMAL');

MERGE INTO base_warehouse KEY(warehouse_id) VALUES ('WH001', 'W001', '总仓', '正常仓', '平台主仓', 'CG01', '王五', 'NORMAL');
MERGE INTO base_warehouse KEY(warehouse_id) VALUES ('WH002', 'W002', '退货仓', '退货仓', '平台主仓', 'CG01', '赵六', 'NORMAL');

MERGE INTO base_goods KEY(goods_id) VALUES ('G001', 'SP001', '农夫山泉500ml*24', '500ml*24', '瓶装水', '农夫山泉', '瓶', '6941410749551', 35.00, 31.20, 30.00, 1200, 'NORMAL');
MERGE INTO base_goods KEY(goods_id) VALUES ('G002', 'SP002', '康师傅红烧牛肉面', '1*12', '方便食品', '康师傅', '箱', '690000000002', 48.00, 42.50, 40.00, 580, 'NORMAL');

MERGE INTO base_customer_price_adjust KEY(adjust_id) VALUES ('CPA001', 'CPA202606140001', 'C001', '华联超市', DATE '2026-06-14', 'SCHEDULED', TIMESTAMP '2026-06-15 08:00:00', '2026-06-15 ~ 2026-12-31', 2, '管理员 2026-06-14 10:20', '待审核', 'PENDING', '夏季饮品促销调价');
MERGE INTO base_customer_price_adjust_detail KEY(detail_id) VALUES ('CPAD001', 'CPA001', 'SP001', '农夫山泉500ml*24', '瓶', '500ml*24', '6941410749551', 35.00, 34.50, 31.20, 30.80);
MERGE INTO base_customer_price KEY(price_id) VALUES ('PRICE001', 'CPA202606010001', 'C001', '华联超市', 'SP001', '农夫山泉500ml*24', '瓶', '500ml*24', '6941410749551', 35.00, 35.00, 31.20, 30.80, 'IMMEDIATE', '长期有效', 'EFFECTIVE');

MERGE INTO inv_stock_balance KEY(balance_id) VALUES ('SB001', 'SP001', '农夫山泉500ml*24', '总仓', 'B202606', 1200, 180, 0, 1020, 100, 30.80, 36960.00, TIMESTAMP '2026-06-14 10:20:00');
MERGE INTO inv_stock_ledger KEY(ledger_id) VALUES ('SL001', 'INV202606140001', TIMESTAMP '2026-06-14 10:20:00', 'PI202606140001', 'SP001', '农夫山泉500ml*24', '总仓', 'B202606', 'IN', 100, 30.80, 3080.00, 1200, '管理员');

MERGE INTO pur_order KEY(order_id) VALUES ('PO001', 'PO202606140001', '农夫山泉杭州经销', '李四', '总仓', DATE '2026-06-14', 3500.00, 0.00, '未付款', '未到货', 'PENDING', '管理员 2026-06-14 10:20');
MERGE INTO sales_order KEY(order_id) VALUES ('SO001', 'SO202606140001', '华联超市', '张三', '总仓', DATE '2026-06-14', 350.00, 0.00, 350.00, '通过', '通过', '未出库', '未签收', 'PENDING');

MERGE INTO fin_ar KEY(ar_id) VALUES ('AR001', 'AR202606140001', 'SR202606140001', '华联超市', '张三', 350.00, 0.00, 350.00, DATE '2026-07-14', 0, '未开票', 'UNVERIFIED');
MERGE INTO fin_ap KEY(ap_id) VALUES ('AP001', 'AP202606140001', 'PR202606140001', '农夫山泉杭州经销', 3955.00, 0.00, 3955.00, DATE '2026-07-14', 'UNVERIFIED');
MERGE INTO fin_fund_ledger KEY(ledger_id) VALUES ('FL001', 'FUND202606140001', '工行基本户', 'IN', 350.00, 'RC202606140001', 50650.00, TIMESTAMP '2026-06-14 11:00:00', '管理员');

MERGE INTO biz_simple_bill KEY(bill_id) VALUES ('BILL_PUR_RETURN_001', 'PURCHASE_RETURN', 'PRT202606140001', '农夫山泉杭州经销', '总仓', '质量问题', 350.00, 10, 'PENDING', CURRENT_TIMESTAMP);
MERGE INTO biz_simple_bill KEY(bill_id) VALUES ('BILL_SALES_RETURN_001', 'SALES_RETURN', 'SRT202606140001', '华联超市', '退货仓', '客户退货', 120.00, 5, 'PENDING', CURRENT_TIMESTAMP);
MERGE INTO biz_simple_bill KEY(bill_id) VALUES ('BILL_TRANSFER_001', 'TRANSFER', 'TR202606140001', '总仓 → 东区仓', '总仓', '正常调拨', 3080.00, 100, 'PENDING', CURRENT_TIMESTAMP);
MERGE INTO biz_simple_bill KEY(bill_id) VALUES ('BILL_DAMAGE_001', 'DAMAGE', 'DO202606140001', '冷藏仓', '冷藏仓', '过期报损', 64.00, 20, 'PENDING', CURRENT_TIMESTAMP);
MERGE INTO biz_simple_bill KEY(bill_id) VALUES ('BILL_COST_ADJUST_001', 'COST_ADJUST', 'CA202606140001', 'SP001', '总仓', '成本修正', 336.00, 0, 'PENDING', CURRENT_TIMESTAMP);
MERGE INTO biz_simple_bill KEY(bill_id) VALUES ('BILL_PUR_EXPENSE_001', 'PURCHASE_EXPENSE', 'PE202606140001', '顺丰物流', '总仓', '运费分摊', 1000.00, 0, 'PENDING', CURRENT_TIMESTAMP);
MERGE INTO biz_simple_bill KEY(bill_id) VALUES ('BILL_FLY_001', 'FLY_ORDER', 'FLY202606140001', '华联超市', '虚拟仓', '飞单不影响库存', 350.00, 10, 'PENDING', CURRENT_TIMESTAMP);
MERGE INTO biz_simple_bill KEY(bill_id) VALUES ('BILL_EMPTY_001', 'EMPTY_ADJUST', 'EA202606140001', '华联超市', '虚拟仓', '不影响库存，仅调整应收', 350.00, 0, 'PENDING', CURRENT_TIMESTAMP);

MERGE INTO fin_expense_bill KEY(expense_id) VALUES ('FE001', 'FE202606140001', 'OUT', '房租', '物业公司', 5000.00, 0.00, TRUE, FALSE, 'PENDING');

MERGE INTO sys_user_runtime KEY(user_id) VALUES ('U0001', 'admin', '系统管理员', '13800000000', '管理员组', '全部', 'NORMAL');
MERGE INTO sys_role_runtime KEY(role_id) VALUES ('R0001', 'ADMIN', '管理员组', 1, '全部菜单', '全部字段', 'NORMAL');
MERGE INTO sys_param_runtime KEY(param_id) VALUES ('P0001', 'CREDIT_CHECK_MODE', '信用控制', 'REMIND', 'REMIND', '销售', '可选 REMIND/BLOCK/APPROVAL');
MERGE INTO sys_param_runtime KEY(param_id) VALUES ('P0002', 'STOCK_NEGATIVE_ALLOWED', '允许负库存', 'false', 'false', '库存', 'V1.0默认不允许负库存');
MERGE INTO sys_bill_no_rule_runtime KEY(rule_id) VALUES ('BN001', '销售订单', 'SO', 'yyyyMMdd', 4, 'DAY', 'SO202606150001', 'NORMAL');
MERGE INTO sys_bill_no_rule_runtime KEY(rule_id) VALUES ('BN002', '采购订单', 'PO', 'yyyyMMdd', 4, 'DAY', 'PO202606150001', 'NORMAL');
MERGE INTO sys_bill_no_rule_runtime KEY(rule_id) VALUES ('BN003', '客户价格调整单', 'CPA', 'yyyyMMdd', 4, 'DAY', 'CPA202606150001', 'NORMAL');
MERGE INTO sys_operation_log_runtime KEY(log_id) VALUES ('LOG001', TIMESTAMP '2026-06-15 09:00:00', '系统管理员', 'system', 'INIT', 'INIT', 'SUCCESS', '系统初始化');
MERGE INTO sys_export_task_runtime KEY(task_id) VALUES ('EXP001', 'EXP202606150001', '销售订单导出', 'salesOrder', '{}', '销售订单导出_EXP202606150001.xlsx', 'FINISHED', TIMESTAMP '2026-06-15 09:10:00', TIMESTAMP '2026-06-15 09:10:05');
MERGE INTO sys_import_task_runtime KEY(task_id) VALUES ('IMP001', 'IMP202606150001', 'goods', '商品导入任务', '商品导入模板.xlsx', 120, 0, 'FINISHED', '导入成功', TIMESTAMP '2026-06-15 09:20:00', TIMESTAMP '2026-06-15 09:20:08');
