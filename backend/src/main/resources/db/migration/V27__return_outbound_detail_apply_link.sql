-- ============================================
-- V27: 采购退货出库明细 —— 关联申请行 + 支持拆批次出库
--
-- 业务背景：
--   1. 出库明细原来只按 goods_code 与申请对应，同商品多批次时「申请数量」
--      会被聚合成同一个值（申请 7+3 两行，出库两行都显示 10），校验上限也算错。
--      本次加 apply_detail_id 与申请明细行**一一对应**，申请数量按行取。
--
--   2. 支持「一条申请行拆多个批次出库」：
--      实物在库批次可能与申请指定批次不一致（申请退 10 箱，但 B01 只剩 7 箱，
--      需 B01 出 7 + B02 出 3）。此时多条出库明细共享同一个 apply_detail_id，
--      约束为：SUM(同 apply_detail_id 的出库数量) ≤ 该申请行数量。
--      因此 apply_detail_id 上**不能建唯一索引**，只建普通索引。
--
--   3. cost_price 在生成出库单时即从申请明细带入（按单退货取源单成本、
--      按品退货取当前库存成本），不再等到审核时才算 —— 审核时会用实时成本覆盖。
--
-- 遵循 CLAUDE.md：ADD COLUMN IF NOT EXISTS 扩展，不改已有字段。
-- ============================================

ALTER TABLE pur_return_outbound_detail ADD COLUMN IF NOT EXISTS apply_detail_id VARCHAR(32);

-- 普通索引（非唯一）：一条申请行可对应多条出库明细（拆批次出库）
CREATE INDEX IF NOT EXISTS idx_pur_return_outbound_detail_apply
    ON pur_return_outbound_detail(apply_detail_id);

-- 回填历史数据：按 (outbound.source_apply_no, goods_code, batch_no) 匹配申请行。
-- 老数据是一申请行一出库行，匹配得上；匹配不到的保持 NULL，
-- 前端会退回按 goods_code 聚合的老逻辑，不影响既有单据展示。
UPDATE pur_return_outbound_detail od
SET apply_detail_id = (
    SELECT MIN(ad.detail_id)
    FROM pur_return_apply_detail ad
    JOIN pur_return_apply ah ON ad.apply_id = ah.apply_id
    JOIN pur_return_outbound oh ON oh.source_apply_no = ah.apply_no
    WHERE oh.outbound_id = od.outbound_id
      AND ad.goods_code = od.goods_code
      AND COALESCE(ad.batch_no, '') = COALESCE(od.batch_no, '')
)
WHERE od.apply_detail_id IS NULL;
