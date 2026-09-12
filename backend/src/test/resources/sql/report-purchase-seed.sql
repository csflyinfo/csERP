-- 报表中心一期·采购域验收固定场景（ReportPurchaseAcceptanceTest 使用，内存 H2 全量迁移后灌入）
-- 场景设计：
--   PO1(9/5 已审核, 10箱水+5箱面+50斤瓜子=1160) 全部入库：CGRK9/6(8箱水+5箱面+50斤=1064)、CGRK9/8(2箱水=96)
--   PO2(9/10 已审核, 0.5箱=12瓶水=24) 整单在途未入库
--   采购退货 CGTH9/9：1箱水，不含税48+税6.24=54.24（视图 -24瓶/-54.24）
--   销售签收：9/10 签60瓶120元；8/20 签100瓶200元；销售退货入库 9/9 -10瓶/-20元（仅预测取数用）
--   库存：水可用20、面可用10
-- 仓库
INSERT INTO base_warehouse(warehouse_id, warehouse_code, warehouse_name, status)
VALUES ('W001','WH01','总仓','NORMAL');

-- 供应商
INSERT INTO base_supplier(supplier_id, supplier_code, supplier_name, supplier_type, default_buyer, status)
VALUES ('S001','GYS001','华联商贸','核心供应商','张三','NORMAL'),
       ('S002','GYS002','中粮食品','普通供应商','李四','NORMAL');

-- 商品（G001 箱=24瓶；G002 箱=12袋；G003 无大单位）
INSERT INTO base_goods(goods_id, goods_code, goods_name, spec, category_name, brand_name, base_unit,
    barcode, standard_price, latest_purchase_price, storage_property, stock_lower_limit,
    default_supplier, default_warehouse, can_purchase, status, goods_manager, tax_rate, unit_config)
VALUES
 ('GID001','G001','康师傅矿泉水550ml','550ml','饮料','康师傅','瓶','6901',2.00,2.00,'常温',50,
  '华联商贸','总仓',TRUE,'NORMAL','张三','13%',
  '[{"unitName":"瓶","enabled":true,"convertQty":1},{"unitName":"组","enabled":false,"convertQty":6},{"unitName":"箱","enabled":true,"convertQty":24}]'),
 ('GID002','G002','统一方便面','袋装','食品','统一','袋','6902',3.50,3.00,'常温',30,
  '中粮食品','总仓',TRUE,'NORMAL','李四','13%',
  '[{"unitName":"袋","enabled":true,"convertQty":1},{"unitName":"提","enabled":false,"convertQty":6},{"unitName":"箱","enabled":true,"convertQty":12}]'),
 ('GID003','G003','散装瓜子','原味散称','休闲食品','恰恰','斤','6903',12.00,10.00,'常温',0,
  '华联商贸','总仓',TRUE,'NORMAL','张三','13%', NULL);

-- 采购订单1：10箱水 + 5箱面 + 50斤瓜子，已审核，全部收完
INSERT INTO purchase_order(order_id, order_no, supplier_code, supplier_name, buyer, warehouse, bill_date,
    amount, inbound_amount, inbound_status, status, creator_name, audit_user, audit_time)
VALUES ('PO1','CGDD20260905001','GYS001','华联商贸','张三','总仓', DATE '2026-09-05',
    1160.00, 1160.00,'已入库','APPROVED','系统管理员','系统管理员', TIMESTAMP '2026-09-05 09:00:00');
INSERT INTO purchase_order_detail(detail_id, order_id, goods_code, goods_name, unit_name, unit_level,
    convert_qty, qty, base_qty, price, amount, tax_rate)
VALUES
 ('POD11','PO1','G001','康师傅矿泉水550ml','箱',3,24,10,240,48.0000,480.00,'13%'),
 ('POD12','PO1','G002','统一方便面','箱',3,12,5,60,36.0000,180.00,'13%'),
 ('POD13','PO1','G003','散装瓜子','斤',1,1,50,50,10.0000,500.00,'13%');

-- 采购订单2：0.5箱水（12瓶）在途，已审核未到货
INSERT INTO purchase_order(order_id, order_no, supplier_code, supplier_name, buyer, warehouse, bill_date,
    amount, inbound_amount, inbound_status, status, creator_name, audit_user, audit_time)
VALUES ('PO2','CGDD20260910001','GYS001','华联商贸','张三','总仓', DATE '2026-09-10',
    24.00, 0.00,'未入库','APPROVED','系统管理员','系统管理员', TIMESTAMP '2026-09-10 09:00:00');
INSERT INTO purchase_order_detail(detail_id, order_id, goods_code, goods_name, unit_name, unit_level,
    convert_qty, qty, base_qty, price, amount, tax_rate)
VALUES ('POD21','PO2','G001','康师傅矿泉水550ml','箱',3,24,0.5,12,48.0000,24.00,'13%');

-- 入库单1（9/6）：水8箱 + 面5箱 + 瓜子50斤
INSERT INTO pur_inbound(inbound_id, inbound_no, source_order, supplier, warehouse, bill_date,
    qty, amount, status, stock_updated)
VALUES ('PI1','CGRK20260906001','CGDD20260905001','华联商贸','总仓', DATE '2026-09-06',
    63, 1064.00,'APPROVED',TRUE);
INSERT INTO pur_inbound_detail(detail_id, inbound_id, goods_code, goods_name, warehouse, unit_name,
    expected_qty, received_qty, price, amount)
VALUES
 ('PID11','PI1','G001','康师傅矿泉水550ml','总仓','箱',10,8,48.00,384.00),
 ('PID12','PI1','G002','统一方便面','总仓','箱',5,5,36.00,180.00),
 ('PID13','PI1','G003','散装瓜子','总仓','斤',50,50,10.00,500.00);

-- 入库单2（9/8）：水再到2箱
INSERT INTO pur_inbound(inbound_id, inbound_no, source_order, supplier, warehouse, bill_date,
    qty, amount, status, stock_updated)
VALUES ('PI2','CGRK20260908001','CGDD20260905001','华联商贸','总仓', DATE '2026-09-08',
    2, 96.00,'APPROVED',TRUE);
INSERT INTO pur_inbound_detail(detail_id, inbound_id, goods_code, goods_name, warehouse, unit_name,
    expected_qty, received_qty, price, amount)
VALUES ('PID21','PI2','G001','康师傅矿泉水550ml','总仓','箱',2,2,48.00,96.00);

-- 采购退货（9/9）：退水1箱，不含税48 + 税6.24，视图金额应为 -54.24
INSERT INTO pur_return(return_id, return_no, source_apply_no, source_outbound_no, supplier_code, supplier_name,
    warehouse, return_date, goods_amount, tax_amount, final_amount, status, creator_name, audit_user, audit_time)
VALUES ('PR1','CGTH20260909001','CGTHSQ001','RTOUT001','GYS001','华联商贸','总仓', DATE '2026-09-09',
    48.00,6.24,54.24,'APPROVED','系统管理员','系统管理员', TIMESTAMP '2026-09-09 14:00:00');
INSERT INTO pur_return_detail(detail_id, return_id, goods_code, goods_name, unit_name, qty, price,
    amount, tax_rate, tax_amount)
VALUES ('PRD11','PR1','G001','康师傅矿泉水550ml','箱',1,48.0000,48.00,'13%',6.24);

-- 销售签收：9/10 签60瓶120元；8/20 签100瓶200元（司机签收口径，仅预测取数用）
INSERT INTO sales_receipt(receipt_id, receipt_no, source_outbound_no, source_order_no, customer_code,
    customer_name, warehouse, receipt_date, deliver_amount, status, sign_status, sign_time,
    sign_user, sign_amount)
VALUES
 ('SR1','XSFH20260910001','XSCK001','XSDD001','C001','测试门店','总仓', DATE '2026-09-10',
  120.00,'APPROVED','已签收', TIMESTAMP '2026-09-10 10:00:00','张司机',120.00),
 ('SR2','XSFH20260820001','XSCK002','XSDD002','C001','测试门店','总仓', DATE '2026-08-20',
  200.00,'APPROVED','已签收', TIMESTAMP '2026-08-20 11:00:00','张司机',200.00);
INSERT INTO sales_receipt_detail(detail_id, receipt_id, goods_code, goods_name, unit_name, qty, price,
    amount, signed_qty, sign_amount)
VALUES
 ('SRD11','SR1','G001','康师傅矿泉水550ml','瓶',60,2.0000,120.00,60,120.00),
 ('SRD21','SR2','G001','康师傅矿泉水550ml','瓶',100,2.0000,200.00,100,200.00);

-- 销售退货入库（9/9）：退10瓶20元，视图为 -10 / -20
INSERT INTO sales_return_inbound(inbound_id, inbound_no, source_apply_no, customer_code, customer_name,
    warehouse, bill_date, qty, amount, status, stock_updated)
VALUES ('SRI1','XSTH20260909001','XSTHSQ001','C001','测试门店','总仓', DATE '2026-09-09',
    10,20.00,'APPROVED',TRUE);
INSERT INTO sales_return_inbound_detail(detail_id, inbound_id, goods_code, goods_name, unit_name,
    qty, price, amount, warehouse)
VALUES ('SRID11','SRI1','G001','康师傅矿泉水550ml','瓶',10,2.0000,20.00,'总仓');

-- 库存余额：水可用20，面可用10
INSERT INTO inv_stock_balance(balance_id, goods_code, goods_name, warehouse, physical_qty,
    locked_qty, frozen_qty, available_qty, cost_price, stock_amount)
VALUES
 ('BAL1','G001','康师傅矿泉水550ml','总仓',20,0,0,20,2.00,40.00),
 ('BAL2','G002','统一方便面','总仓',10,0,0,10,3.00,30.00);
