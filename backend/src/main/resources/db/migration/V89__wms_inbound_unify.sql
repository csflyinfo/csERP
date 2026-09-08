-- ============================================================
-- V89 WMS 入库链路统一（PRD-28 续）
--   1. 司机回收型退货签收后自动下发仓库收货任务；销售退货入库单增加来源（司机退货/自提到仓/手工）
--   2. 其它入库/拒收/调拨 审核改为"下发 WMS 收货任务"，收完上架自动审核入账
--   3. 上游单据取消/终止联动终止入库任务
--   4. 非采购类型一次性入库（逐行收货，0 实物收 0）；采购按 WMS_PO_MULTI_RECEIVE 决定是否多次入库
--   5. 入库指令查询（复用任务+明细+上架任务，无新表）
--
-- 约定：H2(MODE=MySQL)，不写外键约束，不写 DROP。
-- ============================================================

-- ---------- wms_inbound_task 增强 ----------
-- source_type：单据来源细分（采购/司机退货/自提退货/其它/拒收/调拨/手工），用于入库指令展示
ALTER TABLE wms_inbound_task ADD COLUMN IF NOT EXISTS source_type VARCHAR(20);
-- one_time：Y=一次性入库（非采购默认 Y，逐行必须登记收货，0 实物收 0，关单后不可补收）；N=采购可多次到货
ALTER TABLE wms_inbound_task ADD COLUMN IF NOT EXISTS one_time CHAR(1) DEFAULT 'Y';
-- closed_at：关单时间（过账完成或终止时回填）
ALTER TABLE wms_inbound_task ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP NULL;
-- cancel_reason：终止原因
ALTER TABLE wms_inbound_task ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(255);

-- ---------- wms_inbound_task_detail 增强 ----------
-- receive_flag：该行是否已被收货登记（含 0 实收登记）。
-- 一次性入库靠它判断"逐行都登记过"，而不是 received_qty>0（0 实物行也要显式收 0）。
ALTER TABLE wms_inbound_task_detail ADD COLUMN IF NOT EXISTS receive_flag CHAR(1) DEFAULT 'N';

-- ---------- 销售退货入库单：来源类型 ----------
-- DRIVER=司机退货（司机回收签收生成）；WAREHOUSE=自提到仓（推送仓库生成）；MANUAL=手工
ALTER TABLE sales_return_inbound ADD COLUMN IF NOT EXISTS source_type VARCHAR(20) DEFAULT 'MANUAL';

-- ---------- 三类 ERP 入库单：回填 WMS 来源任务（幂等：一张入库单只被一个 WMS 任务过账） ----------
ALTER TABLE inv_other_inbound  ADD COLUMN IF NOT EXISTS source_wms_task VARCHAR(32);
ALTER TABLE inv_reject_inbound ADD COLUMN IF NOT EXISTS source_wms_task VARCHAR(32);
ALTER TABLE transfer_inbound   ADD COLUMN IF NOT EXISTS source_wms_task VARCHAR(32);
CREATE INDEX IF NOT EXISTS idx_other_inbound_wms_task  ON inv_other_inbound(source_wms_task);
CREATE INDEX IF NOT EXISTS idx_reject_inbound_wms_task ON inv_reject_inbound(source_wms_task);
CREATE INDEX IF NOT EXISTS idx_transfer_inbound_wms_task ON transfer_inbound(source_wms_task);

-- ---------- 存量数据兜底 ----------
-- 历史任务：采购默认按多次入库处理（one_time=N），其余一次性
UPDATE wms_inbound_task SET one_time = 'N' WHERE inbound_type = 'PURCHASE' AND (one_time IS NULL OR one_time = '');
UPDATE wms_inbound_task SET one_time = 'Y' WHERE inbound_type <> 'PURCHASE' AND (one_time IS NULL OR one_time = '');
-- 历史退货入库单来源：无法精确回溯，统一置 WAREHOUSE（自提到仓），司机回收新单走 DRIVER
UPDATE sales_return_inbound SET source_type = 'WAREHOUSE' WHERE source_type IS NULL OR source_type = '';
