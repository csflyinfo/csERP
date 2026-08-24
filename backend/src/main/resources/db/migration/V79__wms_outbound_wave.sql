-- ============================================================
-- V79 WMS V1.5 出库作业链：波次 / 波次明细(分配) / 拣货任务 / 复核记录 / 装车交接 / 异常（PRD-28）
--
-- 状态机：
--   wms_wave.status: DRAFT 草稿 -> RELEASED 已下放 -> PICKING 拣货中 -> PICKED 拣货完成
--                    -> CHECKING 复核中 -> CHECKED 复核完成(=出库完成/已扣库存) -> SHIPPED 已发运
--                    (+ SUSPENDED 差异挂起 / CANCELLED 已撤销)
--   wms_wave_detail.status: PENDING 待拣 -> PICKING 拣货中 -> PICKED 已拣 -> SHORT 缺货
--   wms_pick_task.status: PENDING 待领取 -> CLAIMED 已领取 -> PICKING 拣货中 -> PICKED 已完成 -> CANCELLED
--
-- 扣库存口径：拣货完成(WMS_CHECK_ENABLED=N) 或 复核通过(WMS_CHECK_ENABLED=Y) 时，
-- 由 WmsOutboundService 复用 sales_outbound 审核链（InventoryCostService.salesOutbound）
-- 完成扣减并生成 sales_receipt；本表只记作业过程，不写金额/成本。
-- ============================================================

CREATE TABLE IF NOT EXISTS wms_wave (
  wave_id           VARCHAR(32) PRIMARY KEY,
  wave_no           VARCHAR(50) NOT NULL UNIQUE,
  warehouse         VARCHAR(100) NOT NULL,
  wave_type         VARCHAR(20) DEFAULT 'OUTBOUND',
  pick_mode         VARCHAR(20) DEFAULT 'ORDER',       -- 本次波次实际拣货模式(下放时可改)
  batch_strategy    VARCHAR(20) DEFAULT 'FEFO',
  status            VARCHAR(20) DEFAULT 'DRAFT',
  order_count       INT DEFAULT 0,
  line_count        INT DEFAULT 0,
  total_qty         DECIMAL(18,3) DEFAULT 0,
  picked_qty        DECIMAL(18,3) DEFAULT 0,
  route_line        VARCHAR(100),
  driver            VARCHAR(100),
  collection_zone   VARCHAR(50),
  expedited         CHAR(1) DEFAULT 'N',               -- Y=整波加急
  released_at       TIMESTAMP NULL,
  picked_at         TIMESTAMP NULL,
  checked_at        TIMESTAMP NULL,
  shipped_at        TIMESTAMP NULL,
  remark            VARCHAR(255),
  created_by        VARCHAR(100),
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 波次明细：一行 = 一张订单的一个商品行的「分配 + 拣货」记录。
-- generated_outbound_no 在扣库存时回写，便于与财务出库单对账。
CREATE TABLE IF NOT EXISTS wms_wave_detail (
  detail_id            VARCHAR(32) PRIMARY KEY,
  wave_id              VARCHAR(32) NOT NULL,
  wave_no              VARCHAR(50),
  source_order_no      VARCHAR(50) NOT NULL,           -- 关联销售订单号(WMS<->TMS 也以此为键)
  source_order_id      VARCHAR(32),
  customer_code        VARCHAR(50),
  customer_name        VARCHAR(200),
  goods_code           VARCHAR(50) NOT NULL,
  goods_name           VARCHAR(200),
  unit_name            VARCHAR(30),
  required_qty         DECIMAL(18,3) DEFAULT 0,
  -- 下放分配结果
  alloc_batch_no       VARCHAR(50),                    -- FEFO/FIFO/SPECIFIED 分配的批次
  alloc_bin_code       VARCHAR(50),                    -- 拣货库位
  alloc_zone_code      VARCHAR(50),
  alloc_container_code VARCHAR(50),
  pick_seq             INT DEFAULT 0,                  -- 拣货路径顺序
  -- 拣货回写
  picked_qty           DECIMAL(18,3) DEFAULT 0,
  picked_bin_code      VARCHAR(50),
  picked_batch_no      VARCHAR(50),
  collection_bin_code  VARCHAR(50),                    -- 集货位
  sort_destination     VARCHAR(100),                   -- 分播去向(门店/笼车格口)
  status               VARCHAR(20) DEFAULT 'PENDING', -- PENDING/PICKING/PICKED/SHORT
  pick_task_id         VARCHAR(32),
  expedited            CHAR(1) DEFAULT 'N',
  generated_outbound_no VARCHAR(50),
  remark               VARCHAR(255),
  created_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_wave_detail_wave ON wms_wave_detail(wave_id);
CREATE INDEX IF NOT EXISTS idx_wave_detail_order ON wms_wave_detail(source_order_no);
CREATE INDEX IF NOT EXISTS idx_wave_detail_task ON wms_wave_detail(pick_task_id);

CREATE TABLE IF NOT EXISTS wms_pick_task (
  task_id       VARCHAR(32) PRIMARY KEY,
  task_no       VARCHAR(50) NOT NULL UNIQUE,
  wave_id       VARCHAR(32) NOT NULL,
  wave_no       VARCHAR(50),
  warehouse     VARCHAR(100),
  zone_code     VARCHAR(50),
  pick_mode     VARCHAR(20) DEFAULT 'ORDER',
  assignee      VARCHAR(100),                          -- 领取人(自由抢单) / 指派人
  assign_type   VARCHAR(20) DEFAULT 'FREE',           -- FREE 自由领取 / ASSIGN 主管指派
  status        VARCHAR(20) DEFAULT 'PENDING',
  source_orders VARCHAR(1000),                         -- 涉及订单号，逗号分隔(汇总/接力)
  line_count    INT DEFAULT 0,
  total_qty     DECIMAL(18,3) DEFAULT 0,
  picked_qty    DECIMAL(18,3) DEFAULT 0,
  help_task     CHAR(1) DEFAULT 'N',                   -- Y=跨区支援任务
  parent_task_id VARCHAR(32),                          -- 超量拆分的父任务
  claimed_at    TIMESTAMP NULL,
  picked_at     TIMESTAMP NULL,
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS wms_recheck_record (
  recheck_id       VARCHAR(32) PRIMARY KEY,
  recheck_no       VARCHAR(50) NOT NULL UNIQUE,
  wave_id          VARCHAR(32),
  wave_no          VARCHAR(50),
  source_order_no  VARCHAR(50) NOT NULL,
  warehouse        VARCHAR(100),
  check_mode       VARCHAR(20),                        -- FORCED/SAMPLE/NONE
  check_scope      VARCHAR(20),                        -- WHOLE/SPLIT
  checked_qty      DECIMAL(18,3) DEFAULT 0,
  short_qty        DECIMAL(18,3) DEFAULT 0,
  result           VARCHAR(20),                        -- PASS/SHORT/FAIL
  generated_outbound_no VARCHAR(50),
  operator         VARCHAR(100),
  checked_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark           VARCHAR(255)
);

-- 装车/发运交接（装车不扣库存；这里只记发车交接与对 TMS 的关联）
CREATE TABLE IF NOT EXISTS wms_handover (
  handover_id      VARCHAR(32) PRIMARY KEY,
  handover_no      VARCHAR(50) NOT NULL UNIQUE,
  wave_id          VARCHAR(32),
  source_order_no  VARCHAR(50) NOT NULL,
  outbound_no      VARCHAR(50),
  receipt_no       VARCHAR(50),
  warehouse        VARCHAR(100),
  route_line       VARCHAR(100),
  driver           VARCHAR(100),
  vehicle_plate    VARCHAR(30),
  tms_dispatch_no  VARCHAR(50),                        -- 关联 TMS 调度单号
  status           VARCHAR(20) DEFAULT 'LOADED',       -- LOADED 已装车 / SHIPPED 已发运
  loaded_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  shipped_at       TIMESTAMP NULL,
  operator         VARCHAR(100),
  remark           VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS wms_exception (
  exception_id     VARCHAR(32) PRIMARY KEY,
  exception_no     VARCHAR(50) NOT NULL UNIQUE,
  wave_id          VARCHAR(32),
  wave_no          VARCHAR(50),
  source_order_no  VARCHAR(50),
  goods_code       VARCHAR(50),
  exception_type   VARCHAR(30),                        -- SHORT 缺货 / DIFF 差异 / DAMAGE 破损 / OTHER
  qty              DECIMAL(18,3) DEFAULT 0,
  status           VARCHAR(20) DEFAULT 'OPEN',         -- OPEN / HANDLING / RESOLVED / SUSPENDED
  description      VARCHAR(500),
  resolution       VARCHAR(500),
  reporter         VARCHAR(100),
  assignee         VARCHAR(100),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  resolved_at      TIMESTAMP NULL
);
