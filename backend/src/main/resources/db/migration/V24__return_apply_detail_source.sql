-- ============================================
-- V24: 采购退货申请明细 —— 双入口加商品所需字段
--
-- 业务背景：
--   退货申请明细支持两种来源，用 return_mode 区分：
--     · BY_BILL  按单退货 —— 从【按单添加商品】选采购入库单明细带入，
--                 记录 source_inbound_no / source_detail_id 溯源，
--                 只允许改数量与单价，数量受 returnable_qty 硬约束；
--     · BY_GOODS 按品退货 —— 从【添加商品】选商品档案带入（无源单），
--                 数量/单位/单价/金额均可改，数量受 available_stock 约束。
--
-- 字段说明：
--   1. returnable_qty / available_stock / cost_price 均为「建单时快照」，
--      审核时后端会按实时数据二次校验，快照仅用于列表展示与前端预校验；
--   2. spec 冗余存商品规格 —— pur_inbound_detail 没有该字段，
--      每次列表都 JOIN base_goods 代价高，建单时一次性落库；
--   3. cost_price 6 位小数（CLAUDE.md：成本单价 6 位小数）。
--
-- 遵循 CLAUDE.md：只用 ADD COLUMN IF NOT EXISTS 扩展，不改已有字段。
-- ============================================

-- ========== 退货申请明细 ==========

ALTER TABLE pur_return_apply_detail ADD COLUMN IF NOT EXISTS return_mode VARCHAR(20) DEFAULT 'BY_BILL';
ALTER TABLE pur_return_apply_detail ADD COLUMN IF NOT EXISTS source_inbound_no VARCHAR(50);
ALTER TABLE pur_return_apply_detail ADD COLUMN IF NOT EXISTS source_detail_id VARCHAR(32);
ALTER TABLE pur_return_apply_detail ADD COLUMN IF NOT EXISTS returnable_qty DECIMAL(18, 4) DEFAULT 0;
ALTER TABLE pur_return_apply_detail ADD COLUMN IF NOT EXISTS cost_price DECIMAL(18, 6) DEFAULT 0;
ALTER TABLE pur_return_apply_detail ADD COLUMN IF NOT EXISTS available_stock DECIMAL(18, 4) DEFAULT 0;
ALTER TABLE pur_return_apply_detail ADD COLUMN IF NOT EXISTS spec VARCHAR(200);

-- 按源单行反查已退数量（inbound-detail 端点高频用）
CREATE INDEX IF NOT EXISTS idx_pur_return_apply_detail_source
    ON pur_return_apply_detail(source_detail_id);

-- ========== 退货出库明细：来源信息透传 ==========

ALTER TABLE pur_return_outbound_detail ADD COLUMN IF NOT EXISTS return_mode VARCHAR(20) DEFAULT 'BY_BILL';
ALTER TABLE pur_return_outbound_detail ADD COLUMN IF NOT EXISTS source_inbound_no VARCHAR(50);
ALTER TABLE pur_return_outbound_detail ADD COLUMN IF NOT EXISTS source_detail_id VARCHAR(32);
ALTER TABLE pur_return_outbound_detail ADD COLUMN IF NOT EXISTS spec VARCHAR(200);
