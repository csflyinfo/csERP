-- ============================================
-- V60: 销售退货【退货方式】+ 双路径状态流转
--
-- 背景：
--   · V51 给 sales_return_apply 加了 return_type/logistics_status，但后台建单的 INSERT
--     没带 return_type，一律吃默认值 WAREHOUSE，而配送任务池只查 return_type='DRIVER'，
--     导致 ERP 后台开的退货单无论怎么确认、审核都进不了配送任务池。
--   · 原流程「退货单审核 → 生成退货入库单 + 写负向应收」把两件事绑死在审核动作上，
--     无法表达「司机先回收、货还没到仓」和「自提到仓、先推送仓库再收货」这两种真实场景。
--
-- 内容：
--   1. sales_return_apply 扩展 return_amount / inbound_amount / push_time / push_user
--   2. sales_return_apply_detail 扩展 signed_qty / inbound_qty（按行签收、按行入库）
--   3. 历史数据补偿：全部视为自提到仓，已审核单据补齐金额与流转状态
--   4. 新增系统参数 SALES_RETURN_AR_TIMING（入账时点）
--
-- 设计说明：
--   · 入库单生成时机前移：不再由「退货单审核」生成，改由
--       自提到仓 → ERP 点「推送仓库」生成（按申请数量）
--       司机回收 → 司机 APP 签收后生成（按回收数量）
--     审核动作退化为纯财务动作，只写负向应收。
--   · 不新增状态列。logistics_status 被两种退货方式共用，语义按 return_type 区分：
--       DRIVER    : 未安排 → 已安排调度 → 已调度 → 司机已回收 → 已入库
--       WAREHOUSE : 未安排 → 已推送仓库 → 已入库
--     ⚠ 所有读取 logistics_status 的地方都必须带 return_type 一起判断，
--       否则会出现「自提到仓单据显示已调度」这类串味。
--   · 金额字段拆三个，保留可追溯性，不复用同一列：
--       amount         申请金额（建单时定死，不再变）
--       return_amount  退货金额（司机签收数 / 自提到仓的入库数确定），负向应收取此列
--       inbound_amount 已入库金额（入库单审核回写）
--   · 允许仓库实收 < 司机签收：差异体现在 signed_qty vs inbound_qty，
--     return_amount 与负向应收不因少收而调整（已确认的业务口径）。
--   · 数量精度 DECIMAL(18,4)、金额 DECIMAL(18,2)，沿用 V28 约定。
-- ============================================

-- ------------------------------------------------------------
-- 1. 退货单表头：金额拆分 + 推送仓库痕迹
-- ------------------------------------------------------------
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS return_amount DECIMAL(18,2) DEFAULT 0;    -- 退货金额（签收/入库后确定，负向应收取此列）
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS inbound_amount DECIMAL(18,2) DEFAULT 0;   -- 已入库金额（入库单审核回写）
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS push_time TIMESTAMP;                      -- 推送仓库时间（自提到仓）
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS push_user VARCHAR(100);                   -- 推送仓库操作人

-- ------------------------------------------------------------
-- 2. 退货单明细：按行签收 / 按行入库
--    司机 APP 已在传 items[{detailId, signedQty}]，此前后端未落库，仅回写了表头。
-- ------------------------------------------------------------
ALTER TABLE sales_return_apply_detail ADD COLUMN IF NOT EXISTS signed_qty DECIMAL(18,4) DEFAULT 0;   -- 司机按行回收数量
ALTER TABLE sales_return_apply_detail ADD COLUMN IF NOT EXISTS inbound_qty DECIMAL(18,4) DEFAULT 0;  -- 仓库按行实收数量

-- 列表按退货方式 + 流转状态筛选
CREATE INDEX IF NOT EXISTS idx_sales_return_apply_type_logistics ON sales_return_apply(return_type, logistics_status);

-- ------------------------------------------------------------
-- 3. 历史数据补偿
--    改动前不存在 DRIVER 型后台单据，全部按自提到仓归置。
-- ------------------------------------------------------------
UPDATE sales_return_apply SET return_type = 'WAREHOUSE' WHERE return_type IS NULL;
UPDATE sales_return_apply SET logistics_status = '未安排' WHERE logistics_status IS NULL;

-- 退货金额历史值 = 申请金额（此前二者同义）
UPDATE sales_return_apply SET return_amount = COALESCE(amount, 0)
 WHERE return_amount IS NULL OR return_amount = 0;

-- 明细行签收数留 0（未经司机回收），入库数按已有入库事实无法逐行还原，留 0 不猜
UPDATE sales_return_apply_detail SET signed_qty = 0 WHERE signed_qty IS NULL;
UPDATE sales_return_apply_detail SET inbound_qty = 0 WHERE inbound_qty IS NULL;

-- 已审核且已回写入库数量的历史单据，流转状态直接置「已入库」并补已入库金额，
-- 避免新逻辑下这些单据的流转状态显示「未安排」而与事实矛盾。
UPDATE sales_return_apply SET logistics_status = '已入库', inbound_amount = COALESCE(amount, 0)
 WHERE status = 'APPROVED' AND COALESCE(inbound_qty, 0) > 0;

-- ------------------------------------------------------------
-- 4. 系统参数：销售退货入账时点（ERP 端「系统参数」页可直接维护）
-- ------------------------------------------------------------
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0105', 'SALES_RETURN_AR_TIMING', '销售退货入账时点',
        'WAREHOUSE_INBOUND', 'WAREHOUSE_INBOUND', '销售退货',
        'WAREHOUSE_INBOUND=按仓库收货入账，退货入库单审核后才允许审核退货单；DRIVER_SIGN=按司机回收入账，司机确认回收即自动审核退货单并写负向应收')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);
