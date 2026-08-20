-- ============================================================
-- V70 配送点级装车 / 发车标记
--
-- 背景：装车与发车原先只有调度单级状态（tms_dispatch.status = LOADED / DEPARTED），
-- 一张调度单里的所有配送点必须同时装完、同时发车。实际作业中司机是「装一家、
-- 确认一家」，也经常出现部分门店缺货只能先发已装的货。
--
-- 本次把两个动作下沉到配送点（明细）粒度：
--   load_status  PENDING / LOADED —— 该单是否已确认装车
--   load_time    确认装车时间
--   depart_time  该单的发车时间（NULL = 尚未随车出发）
--
-- 口径约定（后端多处依赖，不要改）：
--   1. depart_time IS NOT NULL 即「在车上、正在配送」，配送中列表以此为准，
--      不再依赖 tms_dispatch.status，因为同一张调度单会同时存在
--      「已发车的点」和「还没装车的点」。
--   2. tms_dispatch.status 仍保留：只有当名下全部明细都发车后才置 DEPARTED，
--      否则停在 LOADED，让司机能继续对剩余配送点装车 / 发车。
--   3. 存量数据回填：已发车的调度单，其明细视为已装车已发车，
--      否则升级后这些单会从配送中列表整体消失。
-- ============================================================

ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS load_status VARCHAR(20) DEFAULT 'PENDING'; -- PENDING/LOADED
ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS load_time   TIMESTAMP;                    -- 确认装车时间
ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS depart_time TIMESTAMP;                    -- 该配送点发车时间

CREATE INDEX IF NOT EXISTS idx_tms_dispatch_detail_depart ON tms_dispatch_detail(depart_time);

-- 存量回填：已发车 / 配送中 / 已完成的调度单，明细一律补成已装车已发车。
-- 时间取调度单上的 depart_time，取不到则退回创建时间，保证字段非空可用于排序。
UPDATE tms_dispatch_detail dd
SET load_status = 'LOADED',
    load_time   = COALESCE(load_time, (SELECT d.depart_time FROM tms_dispatch d WHERE d.dispatch_id = dd.dispatch_id)),
    depart_time = COALESCE(depart_time, (SELECT d.depart_time FROM tms_dispatch d WHERE d.dispatch_id = dd.dispatch_id))
WHERE EXISTS (
    SELECT 1 FROM tms_dispatch d
    WHERE d.dispatch_id = dd.dispatch_id
      AND d.status IN ('DEPARTED', 'DELIVERING', 'COMPLETED')
);
