-- =====================================================================
-- V80: 出库幂等与安全加固（PRD-28 / 安全审计）
--   1) sales_outbound.source_order 加唯一约束：一张销售订单只能有一张出库单
--      （业务上 SalesOutboundController.create 早已强制「一单一出库」，
--       这里把约束落到 DB，堵住并发复核 check-then-insert 的 TOCTOU 窗口，
--       防止双扣库存/双重应收。NULL 允许多行（不关联订单的手工出库）。
--   2) 幂等：唯一索引冲突时由应用层捕获并回查已存在的出库单/发货单。
-- =====================================================================

-- H2/MySQL 均允许唯一索引中存在多个 NULL，因此不关联订单的出库不受影响。

-- 0) 历史脏数据清理：同一 source_order 若存在多张出库单，保留 APPROVED 中最新的一张，
--    其余 PENDING 孤儿单（早期 smoke / 重复点击留下的未审核单）连同明细一起删除。
--    仅清理未审核单，APPROVED 已审核单一律保留，避免误删真实业务。
DELETE FROM sales_outbound_detail
 WHERE outbound_id IN (
        SELECT o.outbound_id FROM sales_outbound o
         LEFT JOIN sales_outbound k
           ON k.source_order = o.source_order
          AND k.status = 'APPROVED'
         WHERE o.source_order IS NOT NULL
           AND o.status <> 'APPROVED'
           AND k.outbound_id IS NOT NULL
     );
DELETE FROM sales_outbound
 WHERE outbound_id IN (
        SELECT o.outbound_id FROM sales_outbound o
         LEFT JOIN sales_outbound k
           ON k.source_order = o.source_order
          AND k.status = 'APPROVED'
         WHERE o.source_order IS NOT NULL
           AND o.status <> 'APPROVED'
           AND k.outbound_id IS NOT NULL
     );

CREATE UNIQUE INDEX IF NOT EXISTS uk_sales_outbound_source_order
    ON sales_outbound(source_order);
