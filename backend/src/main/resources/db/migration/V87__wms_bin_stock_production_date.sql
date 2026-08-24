-- ============================================================
-- V87: wms_bin_stock 加生产日期/到期日；
--      用于 PDA 上架页"查看库存"弹窗按生产日期+批次维度展示现有库存。
--      上架时从 wms_putaway_task.production_date/expiry_date 回填。
-- 幂等：ADD COLUMN IF NOT EXISTS
-- ============================================================

ALTER TABLE wms_bin_stock ADD COLUMN IF NOT EXISTS production_date DATE NULL;
ALTER TABLE wms_bin_stock ADD COLUMN IF NOT EXISTS expiry_date     DATE NULL;

-- 按商品+仓库+生产日期查库存的常用索引（"查看库存"按商品查）
CREATE INDEX IF NOT EXISTS idx_bin_stock_goods_wh
    ON wms_bin_stock(goods_code, warehouse);
