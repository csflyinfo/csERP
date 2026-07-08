-- V30: 销售退货流程简化
-- 1. sales_return_apply 复用为「销售退货单」，新增退货数量/入库数量/确认字段
-- 2. 删除旧 sales_return / sales_return_detail 表（AR 直接在退货单审核时写入）
-- 3. 删除 sales_return_inbound.return_generated 列

-- 1.1 销售退货单新增字段
ALTER TABLE sales_return_apply ADD COLUMN return_qty DECIMAL(18,4) DEFAULT 0 COMMENT '退货数量（保存时=申请数量，审核前可修改）';
ALTER TABLE sales_return_apply ADD COLUMN inbound_qty DECIMAL(18,4) DEFAULT 0 COMMENT '入库数量（入库单审核后回写）';
ALTER TABLE sales_return_apply ADD COLUMN confirmed_time TIMESTAMP NULL COMMENT '确认退货时间';
ALTER TABLE sales_return_apply ADD COLUMN confirmed_user VARCHAR(100) COMMENT '确认退货人';

-- 1.2 已有数据的退货数量 = 申请数量
UPDATE sales_return_apply SET return_qty = qty WHERE return_qty = 0;

-- 2. 删除旧销售退货单表
DROP TABLE IF EXISTS sales_return_detail;
DROP TABLE IF EXISTS sales_return;

-- 3. 删除入库单的 return_generated 列（不再生成独立退货单）
ALTER TABLE sales_return_inbound DROP COLUMN IF EXISTS return_generated;
