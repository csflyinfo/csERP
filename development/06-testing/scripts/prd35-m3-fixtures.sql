-- PRD-35 M3 验证夹具（仅用于隔离库 verify-m3，勿对生产/用户库执行）
-- 客户：预收核销测试客户甲/乙（由 E2E 脚本通过 /base/customer/create 建档）
-- AR 到期日错开（2026-09-16 为基准日）：
--   AR-M3-10 100元 due 09-06（最早）
--   AR-M3-20 200元 due 09-11
--   AR-M3-30 300元 due 09-21
--   AR-M3-40  50元 due NULL（空到期日排最后）
--   AR-M3-50  80元 客户乙 due 09-16（跨客户预收守卫）
--   AR-M3-CS1 120元 / AR-M3-CS2 60元（对账单结算路径）
DELETE FROM fin_ar WHERE ar_no LIKE 'AR-M3-%';
DELETE FROM sales_receipt WHERE receipt_no LIKE 'SR-M3-%';

INSERT INTO fin_ar(ar_id, ar_no, source_bill, customer, salesman, ar_amount,
  received_amount, unreceived_amount, due_date, overdue_days, invoice_status,
  status, reconcile_status, created_at) VALUES
('ARID-M3-10','AR-M3-10','SR-M3-10','预收核销测试客户甲','冒烟员',100.00,0,100.00,'2026-09-06',0,'未开票','UNVERIFIED','未对账',CURRENT_TIMESTAMP),
('ARID-M3-20','AR-M3-20','SR-M3-20','预收核销测试客户甲','冒烟员',200.00,0,200.00,'2026-09-11',0,'未开票','UNVERIFIED','未对账',CURRENT_TIMESTAMP),
('ARID-M3-30','AR-M3-30','SR-M3-30','预收核销测试客户甲','冒烟员',300.00,0,300.00,'2026-09-21',0,'未开票','UNVERIFIED','未对账',CURRENT_TIMESTAMP),
('ARID-M3-40','AR-M3-40','SR-M3-40','预收核销测试客户甲','冒烟员',50.00,0,50.00,NULL,0,'未开票','UNVERIFIED','未对账',CURRENT_TIMESTAMP),
('ARID-M3-50','AR-M3-50','SR-M3-50','预收核销测试客户乙','冒烟员',80.00,0,80.00,'2026-09-16',0,'未开票','UNVERIFIED','未对账',CURRENT_TIMESTAMP),
('ARID-M3-CS1','AR-M3-CS1','SR-M3-CS1','预收核销测试客户甲','冒烟员',120.00,0,120.00,'2026-09-16',0,'未开票','UNVERIFIED','未对账',CURRENT_TIMESTAMP),
('ARID-M3-CS2','AR-M3-CS2','SR-M3-CS2','预收核销测试客户甲','冒烟员',60.00,0,60.00,'2026-09-16',0,'未开票','UNVERIFIED','未对账',CURRENT_TIMESTAMP);

-- 经手人：空种子库 base_employee 为空，结算/核销弹窗的经手人是 select（API E2E 走文本兜底，
-- UI 验收必须有可选人员）；幂等，重复执行不报错
MERGE INTO base_employee(employee_id, employee_code, employee_name, department, position, status, is_salesman)
KEY(employee_code) VALUES('EMP-M3-01','M3UI01','M3经手人','财务部','会计','NORMAL',TRUE);

-- 配对发货单：用于观察 XH 审核回写 receive_status（未收款→部分收款→已收款）
INSERT INTO sales_receipt(receipt_id, receipt_no, source_outbound_no, source_order_no,
  customer_code, customer_name, warehouse, receipt_date, deliver_amount, tax_amount,
  expense_amount, ar_status, receive_status, status, creator_name, create_time) VALUES
('SRID-M3-10','SR-M3-10','SR-M3-10','SO-M3-10','KTEST1','预收核销测试客户甲','常温仓','2026-09-01',100,0,0,'已生成','未收款','APPROVED','冒烟',CURRENT_TIMESTAMP),
('SRID-M3-20','SR-M3-20','SR-M3-20','SO-M3-20','KTEST1','预收核销测试客户甲','常温仓','2026-09-02',200,0,0,'已生成','未收款','APPROVED','冒烟',CURRENT_TIMESTAMP),
('SRID-M3-30','SR-M3-30','SR-M3-30','SO-M3-30','KTEST1','预收核销测试客户甲','常温仓','2026-09-03',300,0,0,'已生成','未收款','APPROVED','冒烟',CURRENT_TIMESTAMP),
('SRID-M3-40','SR-M3-40','SR-M3-40','SO-M3-40','KTEST1','预收核销测试客户甲','常温仓','2026-09-04',50,0,0,'已生成','未收款','APPROVED','冒烟',CURRENT_TIMESTAMP);
