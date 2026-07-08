-- ============================================
-- V32: 调价单重构 —— 从「按价格组调价」改为「按商品多单位多价格调价」
--
-- 业务背景：
--   1. 价格组调价仍保留兼容（price_group_code 改为可空），
--      NEW 模式是新字段：标准售价/参考进价/最低价/建议零售价 + 价格组价格
--   2. base_price_adjust_order 新增 goods_code 用于「快速调价」（单商品锁定）
--   3. base_price_adjust_order_item 新增 unit_level + 四种价格 + JSON 价格组
--   4. 老字段（small/medium/large_*_price）保留不动，兼容历史数据
--
-- 遵循 CLAUDE.md：ADD COLUMN IF NOT EXISTS 扩展，不改已有字段。
-- ============================================

-- ========== 调价单头：价格组可空 + goods_code ==========

ALTER TABLE base_price_adjust_order ALTER COLUMN price_group_code SET NULL;
ALTER TABLE base_price_adjust_order ADD COLUMN IF NOT EXISTS goods_code VARCHAR(50);
ALTER TABLE base_price_adjust_order ADD COLUMN IF NOT EXISTS goods_name VARCHAR(200);
ALTER TABLE base_price_adjust_order ADD COLUMN IF NOT EXISTS goods_locked BOOLEAN DEFAULT FALSE;

-- ========== 调价单明细：多价格字段 ==========

ALTER TABLE base_price_adjust_order_item ADD COLUMN IF NOT EXISTS unit_level TINYINT DEFAULT 1;
ALTER TABLE base_price_adjust_order_item ADD COLUMN IF NOT EXISTS standard_price_new DECIMAL(14, 4);
ALTER TABLE base_price_adjust_order_item ADD COLUMN IF NOT EXISTS standard_price_old DECIMAL(14, 4);
ALTER TABLE base_price_adjust_order_item ADD COLUMN IF NOT EXISTS purchase_price_new DECIMAL(14, 4);
ALTER TABLE base_price_adjust_order_item ADD COLUMN IF NOT EXISTS purchase_price_old DECIMAL(14, 4);
ALTER TABLE base_price_adjust_order_item ADD COLUMN IF NOT EXISTS min_price_new DECIMAL(14, 4);
ALTER TABLE base_price_adjust_order_item ADD COLUMN IF NOT EXISTS min_price_old DECIMAL(14, 4);
ALTER TABLE base_price_adjust_order_item ADD COLUMN IF NOT EXISTS suggest_retail_price_new DECIMAL(14, 4);
ALTER TABLE base_price_adjust_order_item ADD COLUMN IF NOT EXISTS suggest_retail_price_old DECIMAL(14, 4);
-- 价格组价格 JSON：[{pgCode, pgName, newPrice, oldPrice}]
ALTER TABLE base_price_adjust_order_item ADD COLUMN IF NOT EXISTS price_group_prices VARCHAR(4000);
