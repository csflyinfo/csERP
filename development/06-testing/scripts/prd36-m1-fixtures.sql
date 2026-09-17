-- PRD-36 M1 验证夹具：供应商/采购收货/退货/期初应付/核销记录真值
-- 在 V128 重跑前装入隔离验证库（8082 verify-prd36-m1）。
MERGE INTO base_supplier (supplier_id, supplier_code, supplier_name, default_buyer,
                          settlement_method, account_period_days, status)
KEY(supplier_code)
VALUES ('SID-S001', 'S001', '甲厂家', '张采购', '月结', 30, 'NORMAL'),
       ('SID-S002', 'S002', '乙厂家', '李采购', '现结', 0, 'NORMAL');

-- 采购收货（已审）→ 应付 1000，已付 300，未付 700
MERGE INTO pur_receipt (receipt_id, receipt_no, source_inbound_no, supplier_code, supplier_name,
                        receipt_date, final_amount, status, audit_time, creator_name)
KEY(receipt_no)
VALUES ('PR-S001-1', 'CGSH-TEST-1', 'RK-TEST-1', 'S001', '甲厂家',
        DATE '2026-08-10', 1000, 'APPROVED', TIMESTAMP '2026-08-10 10:00:00', 'admin');

-- 采购退货（已审）→ 负应付 -100
MERGE INTO pur_return (return_id, return_no, source_outbound_no, supplier_code, supplier_name,
                       return_date, final_amount, status, audit_time, creator_name)
KEY(return_no)
VALUES ('PT-S001-1', 'CGTH-TEST-1', 'CK-TEST-1', 'S001', '甲厂家',
        DATE '2026-08-20', 100, 'APPROVED', TIMESTAMP '2026-08-20 11:00:00', 'admin');

-- 期初应付批号行
MERGE INTO fin_ap_init (line_id, import_batch_no, row_no, supplier_code, supplier_name,
                        ap_amount, line_status, posted, post_no, generated_ap_no, created_by)
KEY(line_id)
VALUES ('AIL-1', 'QC-TEST', 1, 'S001', '甲厂家', 500, 'VALID', 'Y', 'QC2026080101', 'QCAP-TEST-1', 'admin');

-- 三笔应付真值
MERGE INTO fin_ap (ap_id, ap_no, source_bill, supplier, ap_amount, paid_amount, unpaid_amount,
                   due_date, status)
KEY(ap_no)
VALUES ('AP-1', 'AP-TEST-1', 'CGSH-TEST-1', '甲厂家', 1000, 300, 700, DATE '2026-09-10', 'UNVERIFIED'),
       ('AP-2', 'AP-TEST-2', 'CGTH-TEST-1', '甲厂家', -100, 0, -100, DATE '2026-09-20', 'UNVERIFIED'),
       ('AP-3', 'QCAP-TEST-1', 'QCAP-TEST-1', '甲厂家', 500, 0, 500, DATE '2026-08-01', 'UNVERIFIED');

-- 付款核销记录：AP-1 已核销 300（老格式，无 ar_no/source_bill）
MERGE INTO fin_reconcile_record (record_id, receipt_no, receipt_date, business_no, business_type,
                                 business_date, counterparty_type, counterparty_code,
                                 counterparty_name, reconcile_amount, created_at)
KEY(record_id)
VALUES ('RR-TEST-001', 'FK-TEST-1', DATE '2026-08-25', 'AP-TEST-1', 'PURCHASE_RECEIPT',
        DATE '2026-08-25', 'SUPPLIER', 'S001', '甲厂家', 300, TIMESTAMP '2026-08-25 14:00:00'),
-- 费用类记录命中不到 fin_ap，必须被排除
       ('RR-TEST-002', 'FE-TEST-1', DATE '2026-08-26', 'FE-TEST-1', 'EXPENSE',
        DATE '2026-08-26', 'SUPPLIER', 'S001', '甲厂家', 50, TIMESTAMP '2026-08-26 09:00:00');
