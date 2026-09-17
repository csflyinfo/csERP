-- PRD-36 M2 验证夹具：预付核销全链路真值（在 8082 verify-prd36-m1 隔离库执行）
-- 与 prd36-m1-fixtures.sql 共存、MERGE 幂等，可重复灌入。
-- 真值布局（S001 甲厂家）：
--   AP-M2-10  100  到期 2026-09-05  来源 CGSH-M2-10
--   AP-M2-CS1 120  到期 2026-09-08  来源 CGSH-M2-CS1（对账单1）
--   AP-M2-20  200  到期 2026-09-15  来源 CGSH-M2-20
--   AP-M2-CS2 60   到期 2026-09-18  来源 CGSH-M2-CS2（对账单2）
--   AP-M2-30  300  到期 2026-09-25  来源 CGSH-M2-30
--   AP-M2-40  50   到期 NULL（空到期日 FIFO 排最后，对账单3全额预付用）
--   合计 830；S003 丙厂家 AP-M2-X1 100 用于跨供应商守卫。

MERGE INTO base_supplier (supplier_id, supplier_code, supplier_name, default_buyer,
                          settlement_method, account_period_days, status)
KEY(supplier_code)
VALUES ('SID-S003', 'S003', '丙厂家', '王采购', '现结', 0, 'NORMAL');

-- 采购收货已审（供应商账户修复按来源单分类 AP_RECEIPT 形成流水）
MERGE INTO pur_receipt (receipt_id, receipt_no, source_inbound_no, supplier_code, supplier_name,
                        receipt_date, final_amount, status, audit_time, creator_name)
KEY(receipt_no)
VALUES ('PR-M2-10', 'CGSH-M2-10', 'RK-M2-10', 'S001', '甲厂家',
        DATE '2026-08-10', 100, 'APPROVED', TIMESTAMP '2026-08-10 10:00:00', 'admin'),
       ('PR-M2-20', 'CGSH-M2-20', 'RK-M2-20', 'S001', '甲厂家',
        DATE '2026-08-11', 200, 'APPROVED', TIMESTAMP '2026-08-11 10:00:00', 'admin'),
       ('PR-M2-30', 'CGSH-M2-30', 'RK-M2-30', 'S001', '甲厂家',
        DATE '2026-08-12', 300, 'APPROVED', TIMESTAMP '2026-08-12 10:00:00', 'admin'),
       ('PR-M2-40', 'CGSH-M2-40', 'RK-M2-40', 'S001', '甲厂家',
        DATE '2026-08-13', 50, 'APPROVED', TIMESTAMP '2026-08-13 10:00:00', 'admin'),
       ('PR-M2-CS1', 'CGSH-M2-CS1', 'RK-M2-CS1', 'S001', '甲厂家',
        DATE '2026-08-14', 120, 'APPROVED', TIMESTAMP '2026-08-14 10:00:00', 'admin'),
       ('PR-M2-CS2', 'CGSH-M2-CS2', 'RK-M2-CS2', 'S001', '甲厂家',
        DATE '2026-08-15', 60, 'APPROVED', TIMESTAMP '2026-08-15 10:00:00', 'admin'),
       ('PR-M2-X1', 'CGSH-M2-X1', 'RK-M2-X1', 'S003', '丙厂家',
        DATE '2026-08-16', 100, 'APPROVED', TIMESTAMP '2026-08-16 10:00:00', 'admin');

-- 应付真值（全未核销）
MERGE INTO fin_ap (ap_id, ap_no, source_bill, supplier, ap_amount, paid_amount, unpaid_amount,
                   due_date, status)
KEY(ap_no)
VALUES ('AP-M2-10', 'AP-M2-10', 'CGSH-M2-10', '甲厂家', 100, 0, 100, DATE '2026-09-05', 'UNVERIFIED'),
       ('AP-M2-CS1', 'AP-M2-CS1', 'CGSH-M2-CS1', '甲厂家', 120, 0, 120, DATE '2026-09-08', 'UNVERIFIED'),
       ('AP-M2-20', 'AP-M2-20', 'CGSH-M2-20', '甲厂家', 200, 0, 200, DATE '2026-09-15', 'UNVERIFIED'),
       ('AP-M2-CS2', 'AP-M2-CS2', 'CGSH-M2-CS2', '甲厂家', 60, 0, 60, DATE '2026-09-18', 'UNVERIFIED'),
       ('AP-M2-30', 'AP-M2-30', 'CGSH-M2-30', '甲厂家', 300, 0, 300, DATE '2026-09-25', 'UNVERIFIED'),
       ('AP-M2-40', 'AP-M2-40', 'CGSH-M2-40', '甲厂家', 50, 0, 50, NULL, 'UNVERIFIED'),
       ('AP-M2-X1', 'AP-M2-X1', 'CGSH-M2-X1', '丙厂家', 100, 0, 100, DATE '2026-09-05', 'UNVERIFIED');
