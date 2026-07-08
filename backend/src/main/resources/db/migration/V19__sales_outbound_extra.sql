-- V19: 销售出库单增补字段
-- 出库主表：加业务员/线路/片区/备注（从销售订单带过来的字段）
-- 出库明细：加规格/条码/生产日期/小单位/小单位数量/备注

ALTER TABLE sales_outbound ADD COLUMN salesman VARCHAR(100);
ALTER TABLE sales_outbound ADD COLUMN territory VARCHAR(100);
ALTER TABLE sales_outbound ADD COLUMN route_line VARCHAR(100);
ALTER TABLE sales_outbound ADD COLUMN remark VARCHAR(500);

ALTER TABLE sales_outbound_detail ADD COLUMN spec VARCHAR(200);
ALTER TABLE sales_outbound_detail ADD COLUMN barcode VARCHAR(100);
ALTER TABLE sales_outbound_detail ADD COLUMN production_date DATE;
ALTER TABLE sales_outbound_detail ADD COLUMN small_unit_name VARCHAR(50);
ALTER TABLE sales_outbound_detail ADD COLUMN small_unit_qty DECIMAL(18,4) DEFAULT 0;
ALTER TABLE sales_outbound_detail ADD COLUMN remark VARCHAR(500);
