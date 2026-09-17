-- PRD-36 M4 验证夹具：供应商停用/删除五守卫占用单 + 负应付重分类户
-- 在 8082 verify-prd36-m1 隔离库执行（AUTO_SERVER=TRUE，后端运行时可并发直连）。
-- 前置：供应商 S-M4-01/02/03/AP/JF/DX/FX/NEG/OK/B2 由 E2E 经 /base/supplier/create 建档。
-- 幂等：全部 MERGE KEY，可重复灌入；prd36-m4-reset-v131.sql 会清掉本表全部行。
-- 占位符由 E2E 替换：__TODAY__=当日，__DUE30__=当日+30天
--   （fin_ap 无来源单，v_rpt_ap_bill.bill_date=due_date-30，到期日设当日+30 即归入当日新增，
--     保证日结应付硬勾稽 prior+added-settled=current）

-- ========== 1. 守卫占用应付单：S-M4-AP 一张 50 未结 ==========
MERGE INTO fin_ap (ap_id, ap_no, source_bill, supplier, ap_amount, paid_amount, unpaid_amount,
                   due_date, status, reconcile_status, invoiced_amount, invoice_status)
KEY(ap_no)
VALUES ('APID-M4-G1', 'AP-M4-G1', 'M4CG-G1', 'M4应付守卫户', 50, 0, 50,
        DATE '__DUE30__', 'UNVERIFIED', '未对账', 0, '未来票');

-- ========== 2. 负应付户：S-M4-NEG -30（2202 借方/预付重分类），随后补录 70 造双计提示 ==========
MERGE INTO fin_ap (ap_id, ap_no, source_bill, supplier, ap_amount, paid_amount, unpaid_amount,
                   due_date, status, reconcile_status, invoiced_amount, invoice_status)
KEY(ap_no)
VALUES ('APID-M4-NEG', 'AP-M4-NEG', 'M4CG-NEG', 'M4负应付户', -30, 0, -30,
        DATE '__DUE30__', 'UNVERIFIED', '未对账', 0, '未来票');

-- ========== 3. 守卫占用厂家费用单：S-M4-JF 已审核未兑现 50 ==========
MERGE INTO fin_factory_expense (factory_expense_id, factory_expense_no, supplier_code, supplier_name,
                                expense_date, claim_type, source_mode, total_amount,
                                settled_amount, unsettled_amount, settle_status, is_red,
                                status, business_source, creator_name, create_time,
                                auditor_name, audit_time, remark)
KEY(factory_expense_no)
VALUES ('JFID-M4-G1', 'JF-M4-G1', 'S-M4-JF', 'M4费用守卫户',
        DATE '__TODAY__', 'ADVANCE', 'MANUAL', 50,
        0, 50, '待兑现', 'N',
        'APPROVED', 'BACKOFFICE', 'admin', CURRENT_TIMESTAMP,
        'admin', CURRENT_TIMESTAMP, 'M4守卫夹具：已审核未兑现');

-- ========== 4. 守卫占用厂家费用兑现单：S-M4-DX 待审核 PENDING ==========
MERGE INTO fin_factory_settle (settle_id, settle_no, supplier_code, supplier_name, settle_date,
                               settle_type, total_amount, status, business_source,
                               creator_name, create_time, remark)
KEY(settle_no)
VALUES ('DXID-M4-G1', 'DX-M4-G1', 'S-M4-DX', 'M4兑现守卫户', DATE '__TODAY__',
        'CASH', 50, 'PENDING', 'MANUAL',
        'admin', CURRENT_TIMESTAMP, 'M4守卫夹具：未作废兑现单');

-- ========== 5. 守卫占用预付核销单：S-M4-FX 待审核 PENDING ==========
MERGE INTO fin_prepay_writeoff (writeoff_id, writeoff_no, supplier_code, supplier_name, handler,
                                writeoff_date, total_amount, status, business_source,
                                creator_name, create_time, remark)
KEY(writeoff_no)
VALUES ('FXID-M4-G1', 'FX-M4-G1', 'S-M4-FX', 'M4核销守卫户', 'admin',
        DATE '__TODAY__', 50, 'PENDING', 'MANUAL',
        'admin', CURRENT_TIMESTAMP, 'M4守卫夹具：未作废核销单');

-- ========== 6. 资金账户 → 总账科目映射（GL 启用/凭证 @FUND 渲染前置档案） ==========
UPDATE base_fund_account SET gl_account_code = '1001'   WHERE fund_account_code = '01';
UPDATE base_fund_account SET gl_account_code = '100201' WHERE fund_account_code = '02';

SELECT 'M4_FIXTURES_OK';
