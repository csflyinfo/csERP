-- PRD-36 M3 验证夹具：厂家费用 JF/兑现 DX 全链路（在 8082 verify-prd36-m1 隔离库执行）
-- 与 prd36-m1/m2-fixtures.sql 共存、MERGE 幂等，可重复灌入。
-- 前置：先执行 prd36-m2-reset-v129.sql 把 AP-M2-* 复位为全未付。
--
-- 真值布局：
--   客户：C-M3-01 甲客户 / C-M3-02 乙客户
--   费用类型（末级）：
--     M3TG M3推广费  gl_expense_account_code=560105（代垫 FE 原路科目），factory=560199（其他性质贷方）
--     M3CC M3仓储费  gl_expense_account_code=560102，factory 未配 → 默认 5401
--   客户费用单 FE（counterparty_type='CUSTOMER' / direction='OUT' / APPROVED）：
--     FE-M3-1 2026-09-01 C-M3-01 100 = 推广费60(行FED-M3-1A) + 仓储费40(行FED-M3-1B)
--     FE-M3-2 2026-09-02 C-M3-02 80  = 仓储费80(行FED-M3-2A)
--     FE-M3-3 2026-09-03 C-M3-01 50  = 推广费50(行FED-M3-3A)
--     FE-M3-4 2026-09-04 C-M3-02 30  = 推广费30(行FED-M3-4A)，供导入关联用
--   资金账户补总账科目映射（凭证 @FUND 渲染需要，相当于上线档案配置）：
--     现金 01 → 1001 库存现金；银行卡 02 → 100201 工行基本户

-- ========== 1. 客户 ==========
MERGE INTO base_customer (customer_id, customer_code, customer_name, channel_type, salesman,
                          account_period_type, status)
KEY(customer_code)
VALUES ('CID-M3-01', 'C-M3-01', '甲客户', '零售商超', '冒烟员', '月结30天', 'NORMAL'),
       ('CID-M3-02', 'C-M3-02', '乙客户', '零售商超', '冒烟员', '现结', 'NORMAL');

-- ========== 2. 费用类型（末级，NORMAL） ==========
MERGE INTO base_expense_type (expense_type_id, expense_type_code, expense_type_name, direction,
                              cost_participation, status, parent_code,
                              gl_expense_account_code, factory_gl_credit_subject_code)
KEY(expense_type_code)
VALUES ('ETID-M3-TG', 'M3TG', 'M3推广费', 'OUT', 'N', 'NORMAL', NULL, '560105', '560199'),
       ('ETID-M3-CC', 'M3CC', 'M3仓储费', 'OUT', 'N', 'NORMAL', NULL, '560102', NULL);

-- ========== 3. 客户费用单 FE（已审核） ==========
MERGE INTO fin_expense_bill (expense_id, expense_no, direction, expense_type, object_name,
                             amount, tax_amount, relation_generated, direct_payment, status,
                             expense_date, counterparty_type, counterparty_code, counterparty_name,
                             handler, department, business_source, total_amount,
                             total_tax_amount, total_excluding_tax_amount,
                             creator_name, create_time, auditor_name, audit_time, remark)
KEY(expense_no)
VALUES ('FEID-M3-1', 'FE-M3-1', 'OUT', 'M3推广费', '甲客户',
        100, 0, FALSE, FALSE, 'APPROVED',
        DATE '2026-09-01', 'CUSTOMER', 'C-M3-01', '甲客户',
        'admin', '销售部', 'BACKOFFICE', 100, 0, 100,
        'admin', TIMESTAMP '2026-09-01 09:00:00', 'admin', TIMESTAMP '2026-09-01 09:05:00', 'M3夹具：代垫推广+仓储'),
       ('FEID-M3-2', 'FE-M3-2', 'OUT', 'M3仓储费', '乙客户',
        80, 0, FALSE, FALSE, 'APPROVED',
        DATE '2026-09-02', 'CUSTOMER', 'C-M3-02', '乙客户',
        'admin', '销售部', 'BACKOFFICE', 80, 0, 80,
        'admin', TIMESTAMP '2026-09-02 09:00:00', 'admin', TIMESTAMP '2026-09-02 09:05:00', 'M3夹具：代垫仓储'),
       ('FEID-M3-3', 'FE-M3-3', 'OUT', 'M3推广费', '甲客户',
        50, 0, FALSE, FALSE, 'APPROVED',
        DATE '2026-09-03', 'CUSTOMER', 'C-M3-01', '甲客户',
        'admin', '销售部', 'BACKOFFICE', 50, 0, 50,
        'admin', TIMESTAMP '2026-09-03 09:00:00', 'admin', TIMESTAMP '2026-09-03 09:05:00', 'M3夹具：代垫推广'),
       ('FEID-M3-4', 'FE-M3-4', 'OUT', 'M3推广费', '乙客户',
        30, 0, FALSE, FALSE, 'APPROVED',
        DATE '2026-09-04', 'CUSTOMER', 'C-M3-02', '乙客户',
        'admin', '销售部', 'BACKOFFICE', 30, 0, 30,
        'admin', TIMESTAMP '2026-09-04 09:00:00', 'admin', TIMESTAMP '2026-09-04 09:05:00', 'M3夹具：导入关联用');

-- ========== 4. FE 明细行 ==========
MERGE INTO fin_expense_detail (detail_id, expense_id, expense_type, amount, remark, sort_order,
                               goods_code, goods_name, qty, price)
KEY(detail_id)
VALUES ('FED-M3-1A', 'FEID-M3-1', 'M3推广费', 60, '门店推广', 1, NULL, NULL, 1, 60),
       ('FED-M3-1B', 'FEID-M3-1', 'M3仓储费', 40, '代垫仓储', 2, NULL, NULL, 1, 40),
       ('FED-M3-2A', 'FEID-M3-2', 'M3仓储费', 80, '代垫仓储', 1, NULL, NULL, 1, 80),
       ('FED-M3-3A', 'FEID-M3-3', 'M3推广费', 50, '陈列推广', 1, NULL, NULL, 1, 50),
       ('FED-M3-4A', 'FEID-M3-4', 'M3推广费', 30, '导入用推广', 1, NULL, NULL, 1, 30);

-- ========== 5. 资金账户 → 总账科目映射（幂等 UPDATE，等价上线后档案维护） ==========
UPDATE base_fund_account SET gl_account_code = '1001'   WHERE fund_account_code = '01';
UPDATE base_fund_account SET gl_account_code = '100201' WHERE fund_account_code = '02';

-- ========== 6. 资金档案基线余额 ==========
-- reset 已清空全部 FK%/DX% 资金流水，档案余额固定回 M3 验收基线（现金 -3130 / 银行卡 0），
-- E2E 的增量与「回到基线」断言以此为准；每次灌夹具都重设，保证可重复执行。
UPDATE base_fund_account SET balance = -3130 WHERE fund_account_code = '01';
UPDATE base_fund_account SET balance = 0     WHERE fund_account_code = '02';

SELECT 'M3_FIXTURES_OK';
