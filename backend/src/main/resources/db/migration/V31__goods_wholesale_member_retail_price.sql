-- ============================================
-- V31: 商品档案增加 批发价/会员价/零售价
--
-- 业务背景：
--   · 批发价(wholesale_price)、会员价(member_price)、零售价(retail_price)
--     为商品档案级别的独立价格字段，与多单位矩阵中的 standardPrice 无关。
--   · 这三个价格不参与多单位联动换算。
--   · 参考进价(purchase_price)已存在单位配置 JSON 中 ——
--     本次在 MultiUnitMatrix 中将其提升到标准售价上方展示。
--
-- 遵循 CLAUDE.md：只新增字段，不改已有字段。
-- ============================================

ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS wholesale_price DECIMAL(18, 4) DEFAULT 0;
ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS member_price DECIMAL(18, 4) DEFAULT 0;
ALTER TABLE base_goods ADD COLUMN IF NOT EXISTS retail_price DECIMAL(18, 4) DEFAULT 0;
