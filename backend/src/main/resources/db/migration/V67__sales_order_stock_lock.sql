-- V67：销售订单可用库存校验与锁定库存
--
-- 背景：
--   销售订单原先「审核」只改状态，不锁库存（PRD 5.5 第 6 步「锁定库存」一直没落地），
--   导致两张订单可以各自通过库存校验、抢占同一批库存。本次补上锁定/释放，
--   校验与锁定统一走 inv_stock_balance 的 goods_code + warehouse 维度。
--
-- 本迁移不加表、不加列：
--   inv_stock_balance 建表时（V1）就有 physical_qty / locked_qty / frozen_qty / available_qty，
--   够用。这里只做一次历史数据修复。
--
-- 修复原因：
--   available_qty 只在 InventoryCostService.purchaseInbound / salesOutbound 里被重算，
--   期初导入、盘点、调拨、其他出入库等路径写过 inv_stock_balance 但没同步 available_qty，
--   locked_qty / frozen_qty 也可能是 NULL。校验一旦以 available_qty 为准，
--   这些不自洽的行会直接误判（NULL 参与运算得 NULL，会被当成库存 0 而误拦单）。
--
-- 口径（与 docs/PRD-版本化产品需求/00-总览与规范/00-PRD文档索引与版本路线图.md 3.2 一致）：
--   可用库存 = 实物库存 - 锁定库存 - 冻结库存

UPDATE inv_stock_balance
SET locked_qty    = COALESCE(locked_qty, 0),
    frozen_qty    = COALESCE(frozen_qty, 0),
    physical_qty  = COALESCE(physical_qty, 0),
    available_qty = COALESCE(physical_qty, 0)
                    - COALESCE(locked_qty, 0)
                    - COALESCE(frozen_qty, 0);
