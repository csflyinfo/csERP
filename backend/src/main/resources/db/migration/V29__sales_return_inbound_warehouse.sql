-- ============================================
-- V29: 销售退货入库明细支持按行选择仓库
--
-- 需求变更：入库时不同商品可入到不同仓库
--   例：正品入主仓，不良品入次品仓
--   仓库从主表移到明细行级
-- ============================================

ALTER TABLE sales_return_inbound_detail ADD COLUMN warehouse VARCHAR(100);
CREATE INDEX idx_sales_return_inbound_detail_warehouse ON sales_return_inbound_detail(warehouse);
