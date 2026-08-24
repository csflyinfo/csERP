-- ============================================================
-- V82 WMS V1.5 入库/库内作业/补货/盘点/效期/异常增强（PRD-28）
--
-- 覆盖原型页面：inbound / putaway / replenish / move / adjust /
--              assembly / expiry / stocktake / return / stock-query /
--              exception / performance / dashboard
--
-- 约定：H2(MODE=MySQL)，不写外键约束，不写 DROP。
-- ============================================================

-- 采购入库单回填 WMS 来源任务（幂等：一个 WMS 任务只生成一次入库单）
ALTER TABLE pur_inbound ADD COLUMN IF NOT EXISTS source_wms_task VARCHAR(32);
CREATE INDEX IF NOT EXISTS idx_pur_inbound_wms_task ON pur_inbound(source_wms_task);

-- 效期预警阈值（V81 已加 WMS_EXPIRY_FREEZE 开关，这里补阈值）
INSERT INTO sys_param_runtime
  (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0176','WMS_EXPIRY_WARNING_DAYS','临期预警天数','30','30','WMS基础参数','NUMBER',NULL,176,0,3650,'天','距到期 ≤ N 天进入 WARNING'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_EXPIRY_WARNING_DAYS');
INSERT INTO sys_param_runtime
  (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0177','WMS_EXPIRY_CRITICAL_DAYS','紧急临期天数','7','7','WMS基础参数','NUMBER',NULL,177,0,3650,'天','距到期 ≤ N 天进入 CRITICAL'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_EXPIRY_CRITICAL_DAYS');
INSERT INTO sys_param_runtime
  (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0178','WMS_REPLENISH_THRESHOLD','主动补货阈值','30','30','WMS基础参数','NUMBER',NULL,178,0,100,'%','拣货位库存低于容量 N% 时触发补货'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_REPLENISH_THRESHOLD');


-- ==================== 入库任务（采购/退货/拒收/调拨/其他） ====================

CREATE TABLE IF NOT EXISTS wms_inbound_task (
  task_id          VARCHAR(32) PRIMARY KEY,
  task_no          VARCHAR(50) NOT NULL UNIQUE,
  inbound_type     VARCHAR(20) NOT NULL DEFAULT 'PURCHASE', -- PURCHASE 采购 / RETURN 销售退 / REJECT 拒收 / TRANSFER 调拨 / OTHER 其他
  source_order_no  VARCHAR(50),                            -- 来源采购单号/退货申请号
  source_order_id  VARCHAR(32),
  generated_inbound_no VARCHAR(50),                        -- 最终生成的入库单号
  supplier_code    VARCHAR(50),
  supplier_name    VARCHAR(200),
  customer_code    VARCHAR(50),
  customer_name    VARCHAR(200),
  warehouse        VARCHAR(100) NOT NULL DEFAULT '总仓',
  asn_no           VARCHAR(50),                            -- 到货预约号
  appoint_time     TIMESTAMP NULL,
  door_code        VARCHAR(30),                            -- 月台
  status           VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING 待收货/RECEIVING 收货中/RECHECK 待复检/PUTAWAY 待上架/PUTTING 上架中/DONE 已完成/EXCEPTION 异常/CANCELLED
  total_qty        DECIMAL(18,3) DEFAULT 0,
  received_qty     DECIMAL(18,3) DEFAULT 0,
  putaway_qty      DECIMAL(18,3) DEFAULT 0,
  total_weight     DECIMAL(18,3) DEFAULT 0,
  line_count       INT DEFAULT 0,
  receiver         VARCHAR(100),
  rechecker        VARCHAR(100),
  blind_flag       CHAR(1) DEFAULT 'N',                    -- Y=盲收
  over_tolerance   DECIMAL(5,2) DEFAULT 0,                 -- 超收容差%
  remark           VARCHAR(500),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  received_at      TIMESTAMP NULL,
  putaway_at       TIMESTAMP NULL,
  updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_inbound_status ON wms_inbound_task(status);

CREATE TABLE IF NOT EXISTS wms_inbound_task_detail (
  detail_id        VARCHAR(32) PRIMARY KEY,
  task_id          VARCHAR(32) NOT NULL,
  task_no          VARCHAR(50),
  goods_code       VARCHAR(50) NOT NULL,
  goods_name       VARCHAR(200),
  unit_name        VARCHAR(30),
  expected_qty     DECIMAL(18,3) DEFAULT 0,               -- 应收
  received_qty     DECIMAL(18,3) DEFAULT 0,               -- 实收
  putaway_qty      DECIMAL(18,3) DEFAULT 0,               -- 已上架
  calc_weight      DECIMAL(18,3) DEFAULT 0,               -- 档案核算重量
  actual_weight    DECIMAL(18,3),                          -- 蓝牙秤实测
  qualified_qty    DECIMAL(18,3) DEFAULT 0,
  unqualified_qty  DECIMAL(18,3) DEFAULT 0,
  batch_no         VARCHAR(50),
  production_date  DATE,
  expiry_date      DATE,
  recommend_bin    VARCHAR(50),                            -- 推荐上架库位
  actual_bin       VARCHAR(50),                            -- 实际库位
  status           VARCHAR(20) DEFAULT 'PENDING',          -- PENDING/RECEIVED/PUTTING/DONE
  remark           VARCHAR(255),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_inbound_detail_task ON wms_inbound_task_detail(task_id);

-- 上架任务（与 inbound_task_detail 1:1 或 1:N，独立追踪上架进度）
CREATE TABLE IF NOT EXISTS wms_putaway_task (
  putaway_id       VARCHAR(32) PRIMARY KEY,
  putaway_no       VARCHAR(50) NOT NULL UNIQUE,
  inbound_task_id  VARCHAR(32),
  inbound_no       VARCHAR(50),
  goods_code       VARCHAR(50) NOT NULL,
  goods_name       VARCHAR(200),
  batch_no         VARCHAR(50),
  production_date  DATE,
  expiry_date      DATE,
  qty              DECIMAL(18,3) DEFAULT 0,
  warehouse        VARCHAR(100) NOT NULL DEFAULT '总仓',
  recommend_bin    VARCHAR(50),
  actual_bin       VARCHAR(50),
  assignee         VARCHAR(100),
  status           VARCHAR(20) DEFAULT 'PENDING',          -- PENDING/PUTTING/DONE/CANCELLED
  remark           VARCHAR(255),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  started_at       TIMESTAMP NULL,
  finished_at      TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_putaway_status ON wms_putaway_task(status);

-- ==================== 补货任务 ====================

CREATE TABLE IF NOT EXISTS wms_replenish_task (
  task_id          VARCHAR(32) PRIMARY KEY,
  task_no          VARCHAR(50) NOT NULL UNIQUE,
  warehouse        VARCHAR(100) NOT NULL DEFAULT '总仓',
  goods_code       VARCHAR(50) NOT NULL,
  goods_name       VARCHAR(200),
  batch_no         VARCHAR(50),
  from_bin         VARCHAR(50) NOT NULL,                   -- 源库位（存储位）
  to_bin           VARCHAR(50) NOT NULL,                   -- 目标库位（拣货位）
  qty              DECIMAL(18,3) DEFAULT 0,
  trigger_type     VARCHAR(20) DEFAULT 'PASSIVE',          -- PASSIVE 被动 / ACTIVE 主动 / MANUAL 手动 / URGENT 加急
  wave_id          VARCHAR(32),                             -- 关联波次（加急补货）
  assignee         VARCHAR(100),
  status           VARCHAR(20) DEFAULT 'PENDING',          -- PENDING/PICKING/DONE/CANCELLED
  remark           VARCHAR(255),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  started_at       TIMESTAMP NULL,
  finished_at      TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_replenish_status ON wms_replenish_task(status);
CREATE INDEX IF NOT EXISTS idx_replenish_goods ON wms_replenish_task(goods_code, to_bin);

-- ==================== 移库/移位 ====================

CREATE TABLE IF NOT EXISTS wms_move_task (
  task_id          VARCHAR(32) PRIMARY KEY,
  task_no          VARCHAR(50) NOT NULL UNIQUE,
  warehouse        VARCHAR(100) NOT NULL DEFAULT '总仓',
  move_type        VARCHAR(20) DEFAULT 'ACTIVE',           -- ACTIVE 主动整理 / PASSIVE 被动(库位坏) / PALLET 整托
  from_bin         VARCHAR(50) NOT NULL,
  to_bin           VARCHAR(50) NOT NULL,
  goods_code       VARCHAR(50) NOT NULL,
  goods_name       VARCHAR(200),
  batch_no         VARCHAR(50),
  qty              DECIMAL(18,3) DEFAULT 0,
  moved_qty        DECIMAL(18,3) DEFAULT 0,
  assignee         VARCHAR(100),
  status           VARCHAR(20) DEFAULT 'PENDING',          -- PENDING/MOVING/DONE/EXCEPTION/CANCELLED
  remark           VARCHAR(255),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  finished_at      TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_move_status ON wms_move_task(status);

-- ==================== 库存冻结记录 ====================

CREATE TABLE IF NOT EXISTS wms_freeze_record (
  freeze_id        VARCHAR(32) PRIMARY KEY,
  warehouse        VARCHAR(100) NOT NULL DEFAULT '总仓',
  bin_code         VARCHAR(50) NOT NULL,
  goods_code       VARCHAR(50),
  batch_no         VARCHAR(50),
  qty              DECIMAL(18,3) DEFAULT 0,               -- 0=整库位
  reason           VARCHAR(50) NOT NULL,                   -- COUNT 盘点 / QUALITY 质量 / DAMAGE 破损 / EXPIRY 临期 / OTHER
  status           VARCHAR(20) DEFAULT 'FROZEN',           -- FROZEN / UNFROZEN
  operator         VARCHAR(100),
  unfreeze_operator VARCHAR(100),
  remark           VARCHAR(500),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  unfrozen_at      TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_freeze_bin ON wms_freeze_record(bin_code, status);

-- ==================== 库存调整单 ====================

CREATE TABLE IF NOT EXISTS wms_adjust_record (
  adjust_id        VARCHAR(32) PRIMARY KEY,
  adjust_no        VARCHAR(50) NOT NULL UNIQUE,
  warehouse        VARCHAR(100) NOT NULL DEFAULT '总仓',
  adjust_type      VARCHAR(20) NOT NULL,                   -- GAIN 盘盈 / LOSS 盘亏
  bin_code         VARCHAR(50),
  goods_code       VARCHAR(50) NOT NULL,
  goods_name       VARCHAR(200),
  batch_no         VARCHAR(50),
  book_qty         DECIMAL(18,3) DEFAULT 0,                -- 账面
  actual_qty       DECIMAL(18,3) DEFAULT 0,                -- 实盘
  adjust_qty       DECIMAL(18,3) DEFAULT 0,                -- 差异（带符号）
  reason           VARCHAR(50) NOT NULL,                   -- COUNT_DIFF 盘点差异 / DAMAGE 货损 / HISTORY 历史漏记 / OTHER
  status           VARCHAR(20) DEFAULT 'PENDING',          -- PENDING 待审批 / APPROVED 已审批 / REJECTED 驳回
  operator         VARCHAR(100),
  approver         VARCHAR(100),
  remark           VARCHAR(500),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  approved_at      TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_adjust_status ON wms_adjust_record(status);

-- ==================== 报损单 ====================

CREATE TABLE IF NOT EXISTS wms_damage_record (
  damage_id        VARCHAR(32) PRIMARY KEY,
  damage_no        VARCHAR(50) NOT NULL UNIQUE,
  warehouse        VARCHAR(100) NOT NULL DEFAULT '总仓',
  bin_code         VARCHAR(50),
  goods_code       VARCHAR(50) NOT NULL,
  goods_name       VARCHAR(200),
  batch_no         VARCHAR(50),
  qty              DECIMAL(18,3) DEFAULT 0,
  cost_amount      DECIMAL(18,2) DEFAULT 0,                -- 成本损失金额
  reason           VARCHAR(50) NOT NULL,                   -- BREAKAGE 破损 / EXPIRY 过期 / QUALITY 质量 / OTHER
  responsibility   VARCHAR(100),                           -- 责任方
  image_url        VARCHAR(500),
  status           VARCHAR(20) DEFAULT 'PENDING',          -- PENDING 待审批 / APPROVED 已报损 / REJECTED 驳回
  operator         VARCHAR(100),
  approver         VARCHAR(100),
  remark           VARCHAR(500),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  approved_at      TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_damage_status ON wms_damage_record(status);

-- ==================== 组装/拆卸 ====================

CREATE TABLE IF NOT EXISTS wms_assembly_task (
  task_id          VARCHAR(32) PRIMARY KEY,
  task_no          VARCHAR(50) NOT NULL UNIQUE,
  task_type        VARCHAR(20) NOT NULL DEFAULT 'ASSEMBLY',-- ASSEMBLY 组装 / DISASSEMBLY 拆卸
  finished_goods_code VARCHAR(50) NOT NULL,                -- 成品/套装编码
  finished_goods_name VARCHAR(200),
  qty              DECIMAL(18,3) DEFAULT 0,
  source_order_no  VARCHAR(50),                            -- 来源销售单（可选）
  station_bin      VARCHAR(50),                            -- 工位
  warehouse        VARCHAR(100) NOT NULL DEFAULT '总仓',
  assignee         VARCHAR(100),
  status           VARCHAR(20) DEFAULT 'PENDING',          -- PENDING/PICKING/ASSEMBLING/DONE/EXCEPTION
  completed_qty    DECIMAL(18,3) DEFAULT 0,
  remark           VARCHAR(255),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  finished_at      TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS wms_assembly_component (
  id               VARCHAR(32) PRIMARY KEY,
  task_id          VARCHAR(32) NOT NULL,
  goods_code       VARCHAR(50) NOT NULL,
  goods_name       VARCHAR(200),
  unit_qty         DECIMAL(18,3) DEFAULT 0,                -- 单耗
  required_qty     DECIMAL(18,3) DEFAULT 0,
  picked_qty       DECIMAL(18,3) DEFAULT 0,
  batch_no         VARCHAR(50),
  bin_code         VARCHAR(50)
);
CREATE INDEX IF NOT EXISTS idx_assembly_comp_task ON wms_assembly_component(task_id);

-- ==================== 盘点 ====================

CREATE TABLE IF NOT EXISTS wms_stocktake_task (
  task_id          VARCHAR(32) PRIMARY KEY,
  task_no          VARCHAR(50) NOT NULL UNIQUE,
  count_type       VARCHAR(20) NOT NULL,                   -- DYNAMIC 动盘 / FULL 全盘 / SPECIFIED 指定 / SAMPLE 抽盘 / CYCLE 循环盘
  count_mode       VARCHAR(10) DEFAULT 'BLIND',            -- BLIND 暗盘 / OPEN 明盘
  scope_text       VARCHAR(500),                            -- 范围描述
  warehouse        VARCHAR(100) NOT NULL DEFAULT '总仓',
  status           VARCHAR(20) DEFAULT 'PENDING',          -- PENDING/COUNTING/RECOUNT/PENDING_APPROVAL/APPROVED/CANCELLED
  total_bins       INT DEFAULT 0,
  counted_bins     INT DEFAULT 0,
  diff_count       INT DEFAULT 0,
  assignee         VARCHAR(100),
  freeze_flag      CHAR(1) DEFAULT 'Y',
  remark           VARCHAR(500),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  finished_at      TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_stocktake_status ON wms_stocktake_task(status);

CREATE TABLE IF NOT EXISTS wms_stocktake_bin (
  id               VARCHAR(32) PRIMARY KEY,
  task_id          VARCHAR(32) NOT NULL,
  bin_code         VARCHAR(50) NOT NULL,
  goods_code       VARCHAR(50) NOT NULL,
  goods_name       VARCHAR(200),
  batch_no         VARCHAR(50),
  book_qty         DECIMAL(18,3) DEFAULT 0,
  real_qty         DECIMAL(18,3),
  diff_qty         DECIMAL(18,3) DEFAULT 0,
  recounted        CHAR(1) DEFAULT 'N',
  counter          VARCHAR(100),
  counted_at       TIMESTAMP NULL,
  status           VARCHAR(20) DEFAULT 'PENDING'           -- PENDING/COUNTED/DIFF/APPROVED
);
CREATE INDEX IF NOT EXISTS idx_stocktake_bin_task ON wms_stocktake_bin(task_id);

-- ==================== 效期预警快照 ====================

CREATE TABLE IF NOT EXISTS wms_expiry_alert (
  alert_id         VARCHAR(32) PRIMARY KEY,
  goods_code       VARCHAR(50) NOT NULL,
  goods_name       VARCHAR(200),
  batch_no         VARCHAR(50),
  warehouse        VARCHAR(100) NOT NULL DEFAULT '总仓',
  bin_code         VARCHAR(50),
  qty              DECIMAL(18,3) DEFAULT 0,
  production_date  DATE,
  expiry_date      DATE,
  days_to_expiry   INT,
  alert_level      VARCHAR(20),                             -- NORMAL/WARNING/CRITICAL/EXPIRED
  handle_status    VARCHAR(20) DEFAULT 'PENDING',          -- PENDING/PROMOTION/RETURN/LOSS/DONE
  handle_remark    VARCHAR(500),
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_expiry_level ON wms_expiry_alert(alert_level, handle_status);

-- ==================== 异常类型/优先级增强 ====================
-- 已有 wms_exception 表（V79），这里只补列
ALTER TABLE wms_exception ADD COLUMN IF NOT EXISTS source_type    VARCHAR(30) DEFAULT 'OUTBOUND';
ALTER TABLE wms_exception ADD COLUMN IF NOT EXISTS source_bill    VARCHAR(100);
ALTER TABLE wms_exception ADD COLUMN IF NOT EXISTS priority       VARCHAR(10) DEFAULT 'MEDIUM'; -- URGENT/HIGH/MEDIUM/LOW
ALTER TABLE wms_exception ADD COLUMN IF NOT EXISTS suspend_status CHAR(1) DEFAULT 'N';
ALTER TABLE wms_exception ADD COLUMN IF NOT EXISTS bin_code       VARCHAR(50);
ALTER TABLE wms_exception ADD COLUMN IF NOT EXISTS handler        VARCHAR(100);
ALTER TABLE wms_exception ADD COLUMN IF NOT EXISTS deadline       TIMESTAMP NULL;
ALTER TABLE wms_exception ADD COLUMN IF NOT EXISTS resolved_at    TIMESTAMP NULL;
ALTER TABLE wms_exception ADD COLUMN IF NOT EXISTS updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ==================== 绩效统计（按人按日汇总） ====================

CREATE TABLE IF NOT EXISTS wms_performance_daily (
  id               VARCHAR(32) PRIMARY KEY,
  stat_date        DATE NOT NULL,
  operator         VARCHAR(100) NOT NULL,
  role_code        VARCHAR(30),                             -- RECEIVER/PUTAWAY/PICKER/CHECKER/KEEPER
  receive_qty      DECIMAL(18,3) DEFAULT 0,
  receive_lines    INT DEFAULT 0,
  putaway_qty      DECIMAL(18,3) DEFAULT 0,
  putaway_lines    INT DEFAULT 0,
  pick_qty         DECIMAL(18,3) DEFAULT 0,
  pick_lines       INT DEFAULT 0,
  check_orders     INT DEFAULT 0,
  replenish_count  INT DEFAULT 0,
  error_count      INT DEFAULT 0,
  work_minutes     INT DEFAULT 0,
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (stat_date, operator, role_code)
);

-- ==================== 作业看板快照（可实时算，此处只留日终汇总） ====================

CREATE TABLE IF NOT EXISTS wms_dashboard_daily (
  id               VARCHAR(32) PRIMARY KEY,
  stat_date        DATE NOT NULL,
  pending_receive  INT DEFAULT 0,
  receiving        INT DEFAULT 0,
  received_today   INT DEFAULT 0,
  pending_putaway  INT DEFAULT 0,
  active_waves     INT DEFAULT 0,
  picking_waves    INT DEFAULT 0,
  pending_check    INT DEFAULT 0,
  outbound_today   INT DEFAULT 0,
  shipped_today    INT DEFAULT 0,
  open_exceptions  INT DEFAULT 0,
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (stat_date)
);
