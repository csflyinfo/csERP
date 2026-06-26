INSERT IGNORE INTO base_category VALUES ('CATE01', NULL, '', '01', '食品饮料', '13%', 128, 'NORMAL', CURRENT_TIMESTAMP);
INSERT IGNORE INTO base_category VALUES ('CATE0101', 'CATE01', '01', '0101', '饮料', '13%', 86, 'NORMAL', CURRENT_TIMESTAMP);
INSERT IGNORE INTO base_category VALUES ('CATE010101', 'CATE0101', '0101', '010101', '瓶装水', '13%', 32, 'NORMAL', CURRENT_TIMESTAMP);

INSERT IGNORE INTO base_unit VALUES ('UNIT001', 'U001', '瓶', TRUE, FALSE, FALSE, 128, 'NORMAL');
INSERT IGNORE INTO base_unit VALUES ('UNIT002', 'U002', '箱', TRUE, FALSE, TRUE, 86, 'NORMAL');

INSERT IGNORE INTO base_brand VALUES ('BR001', 'B001', '农夫山泉', 'NFSQ', 86, 'NORMAL');
INSERT IGNORE INTO base_brand VALUES ('BR002', 'B002', '康师傅', 'KSF', 42, 'NORMAL');

INSERT IGNORE INTO base_warehouse VALUES ('WH001', 'W001', '总仓', '正常仓', '平台主仓', 'CG01', '王五', 'NORMAL');
INSERT IGNORE INTO base_warehouse VALUES ('WH002', 'W002', '退货仓', '退货仓', '平台主仓', 'CG01', '赵六', 'NORMAL');

INSERT IGNORE INTO base_goods (goods_id, goods_code, goods_name, spec, category_name, brand_name, base_unit, barcode, standard_price, latest_purchase_price, min_sale_price, goods_type, shelf_life_days, storage_property, suggested_retail_price, stock_upper_limit, stock_lower_limit, default_supplier, default_warehouse, can_return, current_stock, status) VALUES ('G001', 'SP001', '农夫山泉500ml*24', '500ml*24', '瓶装水', '农夫山泉', '瓶', '6941410749551', 35.00, 31.20, 30.00, '正常商品', 365, '常温', 39.00, 2000, 200, '农夫山泉杭州经销', '总仓', TRUE, 1200, 'NORMAL');
INSERT IGNORE INTO base_goods (goods_id, goods_code, goods_name, spec, category_name, brand_name, base_unit, barcode, standard_price, latest_purchase_price, min_sale_price, goods_type, shelf_life_days, storage_property, suggested_retail_price, stock_upper_limit, stock_lower_limit, default_supplier, default_warehouse, can_return, current_stock, status) VALUES ('G002', 'SP002', '康师傅红烧牛肉面', '1*12', '方便食品', '康师傅', '箱', '690000000002', 48.00, 42.50, 40.00, '正常商品', 180, '常温', 55.00, 1000, 100, '康师傅杭州经销', '总仓', TRUE, 580, 'NORMAL');

INSERT IGNORE INTO base_customer VALUES ('CUS001', 'C001', '华联超市', '零售商超', '王店长', '13800008888', '西湖区', '朝阳线', '张三', '金牌', '月结', '25日', '次月5日', 50000.00, 12000.00, 2000.00, '华联超市有限公司', '9133****', 'NORMAL');
INSERT IGNORE INTO base_supplier VALUES ('SUP001', 'G001', '农夫山泉杭州经销', '农夫杭州', '饮料供应商', '赵经理', '0571-8888', 5, '月结', 30, '李四', '工商银行 6222****', '农夫山泉杭州经销有限公司', '9133****', 6600.00, 'NORMAL');

INSERT IGNORE INTO base_customer_price_adjust VALUES ('CPA001', 'CPA202606140001', 'C001', '华联超市', '2026-06-14', 'SCHEDULED', '2026-06-15 08:00:00', '2026-06-15 ~ 2026-12-31', 2, '管理员 2026-06-14 10:20', '待审核', 'PENDING', '夏季饮品促销调价');
INSERT IGNORE INTO base_customer_price_adjust_detail VALUES ('CPAD001', 'CPA001', 'SP001', '农夫山泉500ml*24', '瓶', '500ml*24', '6941410749551', 35.00, 34.50, 31.20, 30.80);
INSERT IGNORE INTO base_customer_price VALUES ('PRICE001', 'CPA202606010001', 'C001', '华联超市', 'SP001', '农夫山泉500ml*24', '瓶', '500ml*24', '6941410749551', 35.00, 35.00, 31.20, 30.80, 'IMMEDIATE', '长期有效', 'EFFECTIVE');

INSERT IGNORE INTO inv_stock_balance VALUES ('SB001', 'SP001', '农夫山泉500ml*24', '总仓', 'B202606', 1200, 180, 0, 1020, 100, 30.80, 36960.00, '2026-06-14 10:20:00');
INSERT IGNORE INTO inv_stock_ledger VALUES ('SL001', 'INV202606140001', '2026-06-14 10:20:00', 'PI202606140001', 'SP001', '农夫山泉500ml*24', '总仓', 'B202606', 'IN', 100, 30.80, 3080.00, 1200, '管理员');

INSERT IGNORE INTO pur_order (order_id, order_no, supplier, buyer, warehouse, bill_date, amount, inbound_amount, payment_status, arrival_status, status, creator_info, owner_name, expected_arrival_date, settlement_method, cost_amount, audit_info) VALUES ('PO001', 'PO202606140001', '农夫山泉杭州经销', '李四', '总仓', '2026-06-14', 3500.00, 0.00, '未付款', '未到货', 'PENDING', '管理员 2026-06-14 10:20', '平台货主', '2026-06-18', '月结30天', 3120.00, NULL);
INSERT IGNORE INTO pur_order_detail VALUES ('POD001', 'PO001', '正常', 'SP001', '农夫山泉500ml*24', '箱', 100, 35.00, '13%', 3500.00, 31.20, 3120.00);
INSERT IGNORE INTO sales_order (order_id, order_no, customer, salesman, warehouse, bill_date, amount, paid_amount, unpaid_amount, credit_check, stock_check, outbound_status, sign_status, status, line_type, cost_amount, creator_name, audit_info) VALUES ('SO001', 'SO202606140001', '华联超市', '张三', '总仓', '2026-06-14', 350.00, 0.00, 350.00, '通过', '通过', '未出库', '未签收', 'PENDING', '正常', 312.00, '管理员', NULL);
INSERT IGNORE INTO sales_order_detail VALUES ('SOD001', 'SO001', '正常', 'SP001', '农夫山泉500ml*24', '箱', 10, 35.00, '100%', '13%', 350.00, 31.20, 312.00);
INSERT IGNORE INTO pur_inbound VALUES ('PI001', 'PI202606140001', 'PO202606140001', '农夫山泉杭州经销', '总仓', '2026-06-14', 100, 3500.00, 'PENDING', FALSE, FALSE, CURRENT_TIMESTAMP);
INSERT IGNORE INTO pur_inbound_detail VALUES ('PID001', 'PI001', 'SP001', '农夫山泉500ml*24', '总仓', '箱', 100, 100, 'B202606', '2026-06-01', '2027-06-01', 35.00, 3500.00, 30.80, 31.20, 100.00);
INSERT IGNORE INTO sales_outbound VALUES ('SOU001', 'SOU202606140001', 'SO202606140001', '华联超市', '总仓', '2026-06-14', 10, 350.00, 312.00, 'PENDING', FALSE, FALSE, CURRENT_TIMESTAMP);
INSERT IGNORE INTO sales_outbound_detail VALUES ('SOUD001', 'SOU001', 'SP001', '农夫山泉500ml*24', '总仓', '箱', 10, 'B202606', 35.00, 350.00, 31.20, 312.00);

INSERT IGNORE INTO fin_ar VALUES ('AR001', 'AR202606140001', 'SR202606140001', '华联超市', '张三', 350.00, 0.00, 350.00, '2026-07-14', 0, '未开票', 'UNVERIFIED');
INSERT IGNORE INTO fin_ap VALUES ('AP001', 'AP202606140001', 'PR202606140001', '农夫山泉杭州经销', 3955.00, 0.00, 3955.00, '2026-07-14', 'UNVERIFIED');
INSERT IGNORE INTO fin_fund_ledger VALUES ('FL001', 'FUND202606140001', '工行基本户', 'IN', 350.00, 'RC202606140001', 50650.00, '2026-06-14 11:00:00', '管理员');

INSERT IGNORE INTO biz_simple_bill VALUES ('BILL_PUR_RETURN_001', 'PURCHASE_RETURN', 'PRT202606140001', '农夫山泉杭州经销', '总仓', '质量问题', 350.00, 10, 'PENDING', CURRENT_TIMESTAMP);
INSERT IGNORE INTO biz_simple_bill VALUES ('BILL_SALES_RETURN_001', 'SALES_RETURN', 'SRT202606140001', '华联超市', '退货仓', '客户退货', 120.00, 5, 'PENDING', CURRENT_TIMESTAMP);
INSERT IGNORE INTO biz_simple_bill VALUES ('BILL_TRANSFER_001', 'TRANSFER', 'TR202606140001', '总仓 → 东区仓', '总仓', '正常调拨', 3080.00, 100, 'SP001', '农夫山泉500ml*24', 'PENDING', CURRENT_TIMESTAMP);
INSERT IGNORE INTO biz_simple_bill VALUES ('BILL_DAMAGE_001', 'DAMAGE', 'DO202606140001', '冷藏仓', '冷藏仓', '过期报损', 64.00, 20, 'SP001', '农夫山泉500ml*24', 'PENDING', CURRENT_TIMESTAMP);
INSERT IGNORE INTO biz_simple_bill VALUES ('BILL_COST_ADJUST_001', 'COST_ADJUST', 'CA202606140001', 'SP001', '总仓', '成本修正', 336.00, 0, 'SP001', '农夫山泉500ml*24', 'PENDING', CURRENT_TIMESTAMP);
INSERT IGNORE INTO biz_simple_bill VALUES ('BILL_PUR_EXPENSE_001', 'PURCHASE_EXPENSE', 'PE202606140001', '顺丰物流', '总仓', '运费分摊', 1000.00, 0, 'PENDING', CURRENT_TIMESTAMP);
INSERT IGNORE INTO biz_simple_bill VALUES ('BILL_FLY_001', 'FLY_ORDER', 'FLY202606140001', '华联超市', '虚拟仓', '飞单不影响库存', 350.00, 10, 'PENDING', CURRENT_TIMESTAMP);
INSERT IGNORE INTO biz_simple_bill VALUES ('BILL_EMPTY_001', 'EMPTY_ADJUST', 'EA202606140001', '华联超市', '虚拟仓', '不影响库存，仅调整应收', 350.00, 0, 'PENDING', CURRENT_TIMESTAMP);

INSERT IGNORE INTO fin_receipt_bill VALUES ('RC001', 'RC202606140001', '华联超市', '工行基本户', 350.00, 0.00, 'AR202606140001', '销售收款', 'PENDING');
INSERT IGNORE INTO fin_payment_bill VALUES ('PAY001', 'PAY202606140001', '农夫山泉杭州经销', '工行基本户', 3955.00, 0.00, 'AP202606140001', '采购付款', 'PENDING');
INSERT IGNORE INTO fin_expense_bill VALUES ('FE001', 'FE202606140001', 'OUT', '房租', '物业公司', 5000.00, 0.00, TRUE, FALSE, 'PENDING');

INSERT IGNORE INTO sys_user_runtime VALUES ('U0001', 'admin', '系统管理员', 'admin123', '13800000000', '管理员组', '全部', 'NORMAL');
INSERT IGNORE INTO sys_role_runtime VALUES ('R0001', 'ADMIN', '管理员组', 1, '*', '*', 'ALL', 'NORMAL');
INSERT IGNORE INTO sys_role_runtime VALUES ('R0002', 'SALE', '销售员组', 1, 'dashboard,sales,inventory,exportCenter,log', '隐藏成本字段', 'SELF', 'NORMAL');
INSERT IGNORE INTO sys_role_runtime VALUES ('R0003', 'PURCHASE', '采购员组', 1, 'dashboard,base,purchase,stockBalance,exportCenter,log', '*', 'DEPARTMENT', 'NORMAL');
INSERT IGNORE INTO sys_param_runtime VALUES ('P0001', 'CREDIT_CHECK_MODE', '信用控制', 'REMIND', 'REMIND', '销售', '可选 REMIND/BLOCK/APPROVAL');
INSERT IGNORE INTO sys_param_runtime VALUES ('P0002', 'STOCK_NEGATIVE_ALLOWED', '允许负库存', 'false', 'false', '库存', 'V1.0默认不允许负库存');
INSERT IGNORE INTO sys_bill_no_rule_runtime VALUES ('BN001', '销售订单', 'SO', 'yyyyMMdd', 4, 'DAY', 'SO202606150001', 'NORMAL');
INSERT IGNORE INTO sys_bill_no_rule_runtime VALUES ('BN002', '采购订单', 'PO', 'yyyyMMdd', 4, 'DAY', 'PO202606150001', 'NORMAL');
INSERT IGNORE INTO sys_bill_no_rule_runtime VALUES ('BN003', '客户价格调整单', 'CPA', 'yyyyMMdd', 4, 'DAY', 'CPA202606150001', 'NORMAL');
INSERT IGNORE INTO sys_operation_log_runtime VALUES ('LOG001', '2026-06-15 09:00:00', '系统管理员', 'system', 'INIT', 'INIT', 'SUCCESS', '系统初始化');
INSERT IGNORE INTO sys_export_task_runtime VALUES ('EXP001', 'EXP202606150001', '销售订单导出', 'salesOrder', '{}', '销售订单导出_EXP202606150001.xlsx', 'FINISHED', '2026-06-15 09:10:00', '2026-06-15 09:10:05');
INSERT IGNORE INTO sys_import_task_runtime VALUES ('IMP001', 'IMP202606150001', 'goods', '商品导入任务', '商品导入模板.xlsx', 120, 0, 'FINISHED', '导入成功', '2026-06-15 09:20:00', '2026-06-15 09:20:08');
