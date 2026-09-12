-- throwaway: #18/#19 正向数据（全部落在 2026-09-10），可重入
-- 清理旧种子
DELETE FROM wms_inbound_task_detail WHERE detail_id='WITDP3F01';
DELETE FROM wms_inbound_task WHERE task_id='WITP3F01';
DELETE FROM wms_putaway_task WHERE putaway_id='WPTP3F01';
DELETE FROM wms_pick_task WHERE task_id='WPKP3F01';
DELETE FROM wms_recheck_record WHERE recheck_id='WRCP3F01';
DELETE FROM wms_replenish_task WHERE task_id='WRTP3F01';
DELETE FROM wms_stocktake_bin WHERE id='WSBP3F01';
DELETE FROM wms_stocktake_task WHERE task_id='WSTP3F01';
DELETE FROM wms_adjust_record WHERE adjust_id='WARP3F01';
DELETE FROM wms_exception WHERE exception_id='WEXP3F01';
DELETE FROM inv_stock_balance WHERE balance_id='BAP3FPG01';
DELETE FROM sys_user_runtime WHERE user_id='UP3FP3F';
DELETE FROM sales_receipt_detail WHERE detail_id IN ('SRDP3F01','SRDP3F02');
DELETE FROM sales_receipt WHERE receipt_id IN ('SRP3F01','SRP3F02');
DELETE FROM tms_dispatch_detail WHERE detail_id IN ('DDP3F01D1','DDP3F01D2');
DELETE FROM tms_dispatch WHERE dispatch_id='DDP3F01';
DELETE FROM tms_delivery_trip WHERE trip_id='TRP3F01';
DELETE FROM tms_settlement WHERE settlement_id='STP3F01';
DELETE FROM tms_driver_return WHERE driver_return_id='DRP3F01';
DELETE FROM tms_exception_report WHERE report_id='TERP3F01';
DELETE FROM base_employee WHERE employee_id='DVP3F01';

-- ========== WMS ==========
INSERT INTO sys_user_runtime(user_id, username, display_name, status) VALUES ('UP3FP3F','p3fuser','绩效测试员','NORMAL');

INSERT INTO inv_stock_balance(balance_id, goods_code, goods_name, warehouse, batch_no, physical_qty, locked_qty, frozen_qty, available_qty, cost_price, stock_amount)
VALUES ('BAP3FPG01','PG01','绩效商品','绩效仓-P3F',NULL, 20, 0, 0, 20, 10, 200);

-- 收货
INSERT INTO wms_inbound_task(task_id, task_no, inbound_type, warehouse, status, total_qty, received_qty, line_count, receiver, received_at, putaway_at)
VALUES ('WITP3F01','WIT-P3F-01','PURCHASE','绩效仓-P3F','DONE',100,100,1,'p3fuser', TIMESTAMP '2026-09-10 09:00:00', TIMESTAMP '2026-09-10 09:05:00');
INSERT INTO wms_inbound_task_detail(detail_id, task_id, task_no, goods_code, goods_name, expected_qty, received_qty, status)
VALUES ('WITDP3F01','WITP3F01','WIT-P3F-01','PG01','绩效商品',100,100,'RECEIVED');
-- 上架（20 分钟）
INSERT INTO wms_putaway_task(putaway_id, putaway_no, inbound_task_id, inbound_no, goods_code, warehouse, qty, assignee, status, started_at, finished_at)
VALUES ('WPTP3F01','WPT-P3F-01','WITP3F01','WIT-P3F-01','PG01','绩效仓-P3F',100,'p3fuser','DONE', TIMESTAMP '2026-09-10 09:10:00', TIMESTAMP '2026-09-10 09:30:00');
-- 拣货（30 分钟，3 行 90 件）
INSERT INTO wms_pick_task(task_id, task_no, wave_id, wave_no, warehouse, status, assignee, source_orders, total_qty, picked_qty, line_count, pick_mode, assign_type, claimed_at, picked_at, created_at)
VALUES ('WPKP3F01','WPK-P3F-01','WVP3F01','WV-P3F-01','绩效仓-P3F','PICKED','p3fuser','SO-P3F',90,90,3,'SINGLE','FREE', TIMESTAMP '2026-09-10 10:00:00', TIMESTAMP '2026-09-10 10:30:00', TIMESTAMP '2026-09-10 09:50:00');
-- 复核通过
INSERT INTO wms_recheck_record(recheck_id, recheck_no, wave_id, wave_no, source_order_no, warehouse, result, checked_qty, short_qty, operator, checked_at)
VALUES ('WRCP3F01','WRC-P3F-01','WVP3F01','WV-P3F-01','SO-P3F','绩效仓-P3F','PASS',90,0,'p3fuser', TIMESTAMP '2026-09-10 10:35:00');
-- 补货（10 分钟，20 件）
INSERT INTO wms_replenish_task(task_id, task_no, warehouse, goods_code, goods_name, status, assignee, qty, trigger_type, from_bin, to_bin, wave_id, started_at, finished_at)
VALUES ('WRTP3F01','WRT-P3F-01','绩效仓-P3F','PG01','绩效商品','DONE','p3fuser',20,'URGENT','B01','A01','WVP3F01', TIMESTAMP '2026-09-10 10:50:00', TIMESTAMP '2026-09-10 11:00:00');
-- 盘点审批（盘盈 2 件 × 成本 10 = 20）
INSERT INTO wms_stocktake_task(task_id, task_no, count_type, warehouse, status, total_bins, counted_bins, diff_count, assignee, created_at, finished_at)
VALUES ('WSTP3F01','WST-P3F-01','DYNAMIC','绩效仓-P3F','APPROVED',1,1,1,'p3fuser', TIMESTAMP '2026-09-10 11:10:00', TIMESTAMP '2026-09-10 11:30:00');
INSERT INTO wms_stocktake_bin(id, task_id, bin_code, goods_code, goods_name, book_qty, real_qty, diff_qty, counter, counted_at, status)
VALUES ('WSBP3F01','WSTP3F01','A01','PG01','绩效商品',18,20,2,'p3fuser', TIMESTAMP '2026-09-10 11:20:00','DIFF');
-- 调整单盘盈（+2 × 10 = 20）
INSERT INTO wms_adjust_record(adjust_id, adjust_no, adjust_type, reason, goods_code, goods_name, warehouse, bin_code, book_qty, actual_qty, adjust_qty, batch_no, status, operator, approver, approved_at, created_at)
VALUES ('WARP3F01','WAR-P3F-01','GAIN','COUNT_DIFF','PG01','绩效商品','绩效仓-P3F','A01',18,20,2,NULL,'APPROVED','p3fuser','p3fuser', TIMESTAMP '2026-09-10 11:40:00', TIMESTAMP '2026-09-10 11:35:00');
-- 异常（无波次/入库仓 → 仓库空串）
INSERT INTO wms_exception(exception_id, exception_no, exception_type, source_type, source_order_no, goods_code, qty, description, reporter, status, priority, created_at)
VALUES ('WEXP3F01','WEX-P3F-01','SHORT','OUTBOUND','SO-P3F','PG01',1,'绩效异常种子','p3fuser','OPEN','MEDIUM', TIMESTAMP '2026-09-10 12:00:00');

-- ========== TMS ==========
INSERT INTO base_employee(employee_id, employee_code, employee_name, mobile, is_deliveryman) VALUES ('DVP3F01','DVP3F01','绩效司机','13900000001', TRUE);

INSERT INTO tms_dispatch(dispatch_id, dispatch_no, dispatch_date, route_line, territory, driver_id, driver_name, driver_mobile,
  vehicle_plate, vehicle_type, loaded_qty, store_count, amount, status, arrange_user, arrange_time, depart_time, complete_time, create_time)
VALUES ('DDP3F01','DD2026091001', DATE '2026-09-10','绩效1号线','P3F区','DVP3F01','绩效司机','13900000001',
  '京P12345','4.2米',35,2,600,'COMPLETED','调度员', TIMESTAMP '2026-09-10 07:30:00', TIMESTAMP '2026-09-10 08:00:00', TIMESTAMP '2026-09-10 09:00:00', TIMESTAMP '2026-09-10 07:00:00');

INSERT INTO tms_dispatch_detail(detail_id, dispatch_id, bill_type, source_bill_no, source_bill_id,
  customer_code, customer_name, customer_address, territory, route_line, qty, amount, sku_count, seq_no, status, sign_time, sign_user)
VALUES
 ('DDP3F01D1','DDP3F01','RECEIPT','P3F-BILL-1','SRP3F01','CUSTP3F01','绩效客户','地址1','P3F区','绩效1号线',30,600,2,1,'DELIVERED', TIMESTAMP '2026-09-10 08:40:00','DVP3F01'),
 ('DDP3F01D2','DDP3F01','RECEIPT','P3F-BILL-2','SRP3F02','CUSTP3F01','绩效客户','地址1','P3F区','绩效1号线',5,0,1,2,'REJECTED', TIMESTAMP '2026-09-10 08:50:00','DVP3F01');

INSERT INTO sales_receipt(receipt_id, receipt_no, source_outbound_no, receipt_date, status, warehouse, customer_name, customer_code,
  sign_status, sign_time, sign_user, dispatch_id, sign_amount, reject_qty, reject_amount, deliver_amount)
VALUES
 ('SRP3F01','P3F-BILL-1','XOUT-P3F-1', DATE '2026-09-10','APPROVED','绩效仓-P3F','绩效客户','CUSTP3F01',
  '已签收', TIMESTAMP '2026-09-10 08:40:00','DVP3F01','DDP3F01',600,0,0,600),
 ('SRP3F02','P3F-BILL-2','XOUT-P3F-2', DATE '2026-09-10','APPROVED','绩效仓-P3F','绩效客户','CUSTP3F01',
  '全部拒收', TIMESTAMP '2026-09-10 08:50:00','DVP3F01','DDP3F01',0,5,100,100);
INSERT INTO sales_receipt_detail(detail_id, receipt_id, goods_code, goods_name, unit_name, qty, price, amount, signed_qty, reject_qty, sign_amount, reject_amount)
VALUES
 ('SRDP3F01','SRP3F01','PG01','绩效商品','箱',30,20,600,25,0,500,0),
 ('SRDP3F02','SRP3F01','PG02','绩效商品2','箱',5,20,100,0,5,100,0);

INSERT INTO tms_delivery_trip(trip_id, trip_no, dispatch_id, driver_id, driver_name, vehicle_plate, route_line,
  trip_date, status, total_store, delivered_store, total_qty, delivered_qty, collected_amount,
  loading_time, depart_time, complete_time, create_time)
VALUES ('TRP3F01','XC2026091001','DDP3F01','DVP3F01','绩效司机','京P12345','绩效1号线',
  DATE '2026-09-10','COMPLETED',2,1,35,25,500,
  TIMESTAMP '2026-09-10 07:40:00', TIMESTAMP '2026-09-10 08:00:00', TIMESTAMP '2026-09-10 09:00:00', TIMESTAMP '2026-09-10 07:35:00');

INSERT INTO tms_settlement(settlement_id, settlement_no, dispatch_id, trip_id, driver_id, driver_name, route_line,
  settle_date, total_stores, signed_stores, total_amount, cash_amount, online_amount, return_amount, return_qty,
  submit_amount, actual_submit, diff_amount, status, submitted_at, audited_at, auditor, create_time)
VALUES ('STP3F01','JZ2026091001','DDP3F01','TRP3F01','DVP3F01','绩效司机','绩效1号线',
  DATE '2026-09-10',2,1,600,500,100,0,0,
  500,480,-20,'APPROVED', TIMESTAMP '2026-09-10 09:30:00', TIMESTAMP '2026-09-10 10:00:00','财务', TIMESTAMP '2026-09-10 09:20:00');

INSERT INTO tms_driver_return(driver_return_id, driver_return_no, return_apply_no, trip_id, dispatch_id,
  driver_id, driver_name, customer_code, customer_name, return_date, qty, status, create_time)
VALUES ('DRP3F01','XTSQ2026091001','RAP3F01','TRP3F01','DDP3F01',
  'DVP3F01','绩效司机','CUSTP3F01','绩效客户', DATE '2026-09-10', 3, 'WAREHOUSED', TIMESTAMP '2026-09-10 08:55:00');

INSERT INTO tms_exception_report(report_id, report_no, exception_type, severity, title, description,
  trip_id, dispatch_id, detail_id, receipt_no, customer_code, customer_name, vehicle_no,
  driver_id, driver_name, status, handler, reported_at, create_time)
VALUES ('TERP3F01','YCSB2026091001','VEHICLE_FAULT','NORMAL','车辆故障','抛锚一次',
  'TRP3F01','DDP3F01','DDP3F01D2','P3F-BILL-2','CUSTP3F01','绩效客户','京P12345',
  'DVP3F01','绩效司机','CLOSED','调度员', TIMESTAMP '2026-09-10 08:20:00', TIMESTAMP '2026-09-10 08:20:00');
