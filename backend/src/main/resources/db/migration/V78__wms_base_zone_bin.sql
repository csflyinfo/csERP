-- ============================================================
-- V78 WMS V1.5 基础资料：库区 / 库位 / 容器 / 库位库存（PRD-28）
--
-- 账实分离：
--   wms_bin_stock 记录「实物放在哪个库位/容器」（物理位置账），
--   财务库存仍以 inv_stock_balance / inv_batch_stock 为准，二者唯一键都含
--   (goods, warehouse, batch)，WMS 扣库存统一调 InventoryCostService，不自行改金额。
--
-- 约定：
--   - H2(MODE=MySQL)，不写外键约束（与既有迁移一致），不写 DROP。
--   - 唯一键 uk_bin_stock 保证「同商品+仓库+批次+库位+容器」只有一行。
--   - 容器(tote/笼车/托盘)可空：整托位存储时 container_code 为空。
-- ============================================================

CREATE TABLE IF NOT EXISTS wms_zone (
  zone_id        VARCHAR(32) PRIMARY KEY,
  zone_code      VARCHAR(50) NOT NULL,
  zone_name      VARCHAR(100) NOT NULL,
  warehouse      VARCHAR(100) NOT NULL,
  zone_type      VARCHAR(30) DEFAULT 'STORAGE',   -- STORAGE 储区 / RECEIVE 收货 / SHIP 发货 / PICK 拣货 / SORT 分拣 / COLLECT 集货
  pick_seq       INT DEFAULT 0,                   -- 拣货路径顺序
  frozen         CHAR(1) DEFAULT 'N',             -- Y=冻结（盘点/异常）
  remark         VARCHAR(255),
  created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (warehouse, zone_code)
);

CREATE TABLE IF NOT EXISTS wms_bin (
  bin_id         VARCHAR(32) PRIMARY KEY,
  bin_code       VARCHAR(50) NOT NULL,
  bin_name       VARCHAR(100),
  warehouse      VARCHAR(100) NOT NULL,
  zone_code      VARCHAR(50) NOT NULL,
  bin_type       VARCHAR(30) DEFAULT 'SHELF',     -- SHELF 货架位 / FLOOR 平库位 / COLLECT 集货位 / STAGE 暂存位
  aisle          VARCHAR(20),                     -- 巷道
  slot           VARCHAR(20),                     -- 列
  layer          VARCHAR(20),                     -- 层
  capacity_qty   DECIMAL(18,3) DEFAULT 0,         -- 容量（件）
  used_qty       DECIMAL(18,3) DEFAULT 0,         -- 已占用件数（集货位占位用）
  frozen         CHAR(1) DEFAULT 'N',
  status         VARCHAR(20) DEFAULT 'NORMAL',    -- NORMAL / DISABLED
  remark         VARCHAR(255),
  created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (warehouse, bin_code)
);

CREATE TABLE IF NOT EXISTS wms_container (
  container_id   VARCHAR(32) PRIMARY KEY,
  container_code VARCHAR(50) NOT NULL,
  container_type VARCHAR(30) DEFAULT 'TOTE',      -- TOTE 周转箱 / PALLET 托盘 / CAGE 笼车
  warehouse      VARCHAR(100),
  zone_code      VARCHAR(50),
  bin_code       VARCHAR(50),
  status         VARCHAR(20) DEFAULT 'EMPTY',     -- EMPTY / IN_USE / LOCKED
  remark         VARCHAR(255),
  created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (container_code)
);

-- 库位库存（实物位置账）。与 inv_batch_stock 同维度 + bin/container 位置。
CREATE TABLE IF NOT EXISTS wms_bin_stock (
  bin_stock_id   VARCHAR(32) PRIMARY KEY,
  goods_code     VARCHAR(50) NOT NULL,
  goods_name     VARCHAR(200),
  warehouse      VARCHAR(100) NOT NULL,
  batch_no       VARCHAR(50),
  bin_code       VARCHAR(50) NOT NULL,
  container_code VARCHAR(50),
  qty            DECIMAL(18,3) DEFAULT 0,
  locked_qty     DECIMAL(18,3) DEFAULT 0,         -- 波次下放预占（拣货中）
  updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_bin_stock UNIQUE (goods_code, warehouse, batch_no, bin_code, container_code)
);

-- 库位库存流水（移位/补货/拣货/盘点）
CREATE TABLE IF NOT EXISTS wms_bin_stock_log (
  log_id         VARCHAR(32) PRIMARY KEY,
  warehouse      VARCHAR(100),
  goods_code     VARCHAR(50),
  batch_no       VARCHAR(50),
  from_bin       VARCHAR(50),
  to_bin         VARCHAR(50),
  direction      VARCHAR(10),                     -- IN 上架 / OUT 拣货 / MOVE 移位 / ADJUST 调整
  qty            DECIMAL(18,3),
  source_bill    VARCHAR(50),
  operator       VARCHAR(100),
  occurred_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark         VARCHAR(255)
);

-- ============================================================
-- WMS 系统参数（PRD-28 §10）。param_id 从 P0129 起，分组 "WMS仓储"。
-- 幂等：ON DUPLICATE KEY UPDATE 不覆盖 param_value，避免冲掉运营调整。
-- ============================================================
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark) VALUES
('P0129','WMS_WAVE_AUTO_RELEASE','是否启用波次自动下放','N','N','WMS仓储','Y=按 WMS_WAVE_RELEASE_CRON 定时把已审订单自动组波下放；N=仅人工在预分配页下放'),
('P0130','WMS_WAVE_RELEASE_CRON','波次自动下放时间点','09:00,14:00,18:00','09:00,14:00,18:00','WMS仓储','逗号分隔，24小时制 HH:mm，自动组波的下放时点'),
('P0131','WMS_PICK_MODE','默认拣货模式','ORDER','ORDER','WMS仓储','ORDER=按单拣;SUMMARY=汇总拣(先拣后分);PICK_SORT=边拣边分;ZONE_RELAY=分区接力;CROSS_DOCK=越库直发。下放弹窗可本次改'),
('P0132','WMS_PICK_ZONE_ENABLED','按库区生成拣货任务','Y','Y','WMS仓储','Y=任务按库区拆分，拣货员只管本库区；N=整单生成任务'),
('P0133','WMS_PICK_SPLIT_THRESHOLD','单任务拆分件数阈值','50','50','WMS仓储','单订单件数超过该值时自动拆成多个均衡子任务'),
('P0134','WMS_PICK_SPLIT_MAX','单订单最大子任务数','3','3','WMS仓储','超量拆分的子任务上限'),
('P0135','WMS_BATCH_ALLOC_STRATEGY','批次分配策略','FEFO','FEFO','WMS仓储','FEFO=近效期先出;FIFO=先进先出;SPECIFIED=指定批次'),
('P0136','WMS_PICK_BATCH_CHANGE_ALLOWED','拣货是否允许改批次','Y','Y','WMS仓储','Y=拣货缺货时可改拣其他可用批次并记录;N=必须按下放批次拣'),
('P0137','WMS_CROSS_ZONE_MODE','跨区支援模式','BOTH','BOTH','WMS仓储','FREE=自由领取;ASSIGN=主管指定;BOTH=两者皆可'),
('P0138','WMS_COLLECTION_MODE','集货位占用模式','ORDER','ORDER','WMS仓储','ORDER=一单一集货位;STORE=一门店一集货位;SMART=智能占用(小件拼位/大件多位)'),
('P0139','WMS_COLLECTION_BY','集货位占用依据','QTY','QTY','WMS仓储','QTY=按件数;VOLUME=按体积;WEIGHT=按重量'),
('P0140','WMS_COLLECTION_THRESHOLD','集货位占用阈值','30','30','WMS仓储','单集货位占用上限（件/体积/重量，按 WMS_COLLECTION_BY 维度）'),
('P0141','WMS_CHECK_ENABLED','是否启用复核','Y','Y','WMS仓储','Y=拣货完成后进入复核;N=免复核，拣货完成即扣库存'),
('P0142','WMS_CHECK_MODE','复核模式','FORCED','FORCED','WMS仓储','FORCED=强制逐件;SAMPLE=抽核;NONE=免复核'),
('P0143','WMS_CHECK_RATIO','抽核比例(%)','20','20','WMS仓储','SAMPLE 模式下按订单抽核百分比，1~100'),
('P0144','WMS_CHECK_SCOPE','复核粒度','WHOLE','WHOLE','WMS仓储','WHOLE=整位/整托核对;SPLIT=拆零逐件扫'),
('P0145','WMS_CHECK_UNIT','复核单元','ORDER','ORDER','WMS仓储','ORDER=按订单集齐后复核;PICK=按拣货单复核'),
('P0146','WMS_CHECK_HIGH_VALUE_THRESHOLD','高值商品逐件阈值','500','500','WMS仓储','商品单价超过该值强制逐件复核，不参与抽核'),
('P0147','WMS_CHECK_SHORT_ALLOWED','复核是否允许少货通过','N','N','WMS仓储','Y=少货可登记差异后通过;N=少货必须异常挂起/补货'),
('P0148','WMS_DISPATCH_TIMING','调度(派车)时机','AFTER_OUTBOUND','AFTER_OUTBOUND','WMS仓储','AT_RELEASE=下放即派车;AFTER_PICK=拣货完成派车;AFTER_OUTBOUND=出库(扣账)后派车'),
('P0149','WMS_CANCEL_RELEASE_LOCK','撤销下放是否释放批次锁','Y','Y','WMS仓储','Y=撤销未拣订单时释放 inv_batch_stock 锁并清空库位预占;N=保留预占'),
('P0150','WMS_EXPEDITE_ENABLED','是否允许加急插单','Y','Y','WMS仓储','Y=波次/订单可标记加急全链路置顶;N=不允许'),
('P0151','WMS_REPLENISH_URGENT_ENABLED','是否允许缺货一键加急补货','Y','Y','WMS仓储','Y=拣货缺货可一键发起加急补货任务;N=只能走常规补货'),
('P0152','WMS_PDA_CLICK_PICK','PDA是否支持点击拣货(非仅扫码)','Y','Y','WMS仓储','Y=PDA可点击商品行完成拣货;N=仅扫码确认'),
('P0153','WMS_PDA_VIEW','PDA默认拣货视图','FOCUS','FOCUS','WMS仓储','FOCUS=逐件聚焦(大扫码+当前商品);LIST=清单总览'),
('P0154','WMS_EXCEPTION_AUTO_SUSPEND','拣货异常是否自动挂起','Y','Y','WMS仓储','Y=缺货/差异超阈值自动挂起订单并通知主管;N=仅记录不挂起')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), param_group = VALUES(param_group), remark = VALUES(remark);

-- 演示库区/库位种子（默认仓库"总仓"，可在基础资料页继续维护）。
-- 用 NOT EXISTS 保证重复执行不报错、不覆盖运营数据。
INSERT INTO wms_zone (zone_id, zone_code, zone_name, warehouse, zone_type, pick_seq)
SELECT 'ZN-A','A','A区(整箱储区)','总仓','STORAGE',1 WHERE NOT EXISTS (SELECT 1 FROM wms_zone WHERE warehouse='总仓' AND zone_code='A');
INSERT INTO wms_zone (zone_id, zone_code, zone_name, warehouse, zone_type, pick_seq)
SELECT 'ZN-B','B','B区(拆零储区)','总仓','STORAGE',2 WHERE NOT EXISTS (SELECT 1 FROM wms_zone WHERE warehouse='总仓' AND zone_code='B');
INSERT INTO wms_zone (zone_id, zone_code, zone_name, warehouse, zone_type, pick_seq)
SELECT 'ZN-COL','COL','集货区','总仓','COLLECT',9 WHERE NOT EXISTS (SELECT 1 FROM wms_zone WHERE warehouse='总仓' AND zone_code='COL');
INSERT INTO wms_zone (zone_id, zone_code, zone_name, warehouse, zone_type, pick_seq)
SELECT 'ZN-SORT','SORT','分拣区','总仓','SORT',8 WHERE NOT EXISTS (SELECT 1 FROM wms_zone WHERE warehouse='总仓' AND zone_code='SORT');

INSERT INTO wms_bin (bin_id, bin_code, bin_name, warehouse, zone_code, bin_type, aisle, slot, layer, capacity_qty)
SELECT 'BN-A01','A-01-01','A区01巷01列1层','总仓','A','SHELF','A','01','01',200 WHERE NOT EXISTS (SELECT 1 FROM wms_bin WHERE warehouse='总仓' AND bin_code='A-01-01');
INSERT INTO wms_bin (bin_id, bin_code, bin_name, warehouse, zone_code, bin_type, aisle, slot, layer, capacity_qty)
SELECT 'BN-A02','A-01-02','A区01巷01列2层','总仓','A','SHELF','A','01','02',200 WHERE NOT EXISTS (SELECT 1 FROM wms_bin WHERE warehouse='总仓' AND bin_code='A-01-02');
INSERT INTO wms_bin (bin_id, bin_code, bin_name, warehouse, zone_code, bin_type, aisle, slot, layer, capacity_qty)
SELECT 'BN-B01','B-01-01','B区01巷01列1层','总仓','B','SHELF','B','01','01',120 WHERE NOT EXISTS (SELECT 1 FROM wms_bin WHERE warehouse='总仓' AND bin_code='B-01-01');
INSERT INTO wms_bin (bin_id, bin_code, bin_name, warehouse, zone_code, bin_type, aisle, slot, layer, capacity_qty)
SELECT 'BN-COL1','COL-01','集货位01','总仓','COL','COLLECT',NULL,NULL,NULL,30 WHERE NOT EXISTS (SELECT 1 FROM wms_bin WHERE warehouse='总仓' AND bin_code='COL-01');
INSERT INTO wms_bin (bin_id, bin_code, bin_name, warehouse, zone_code, bin_type, aisle, slot, layer, capacity_qty)
SELECT 'BN-COL2','COL-02','集货位02','总仓','COL','COLLECT',NULL,NULL,NULL,30 WHERE NOT EXISTS (SELECT 1 FROM wms_bin WHERE warehouse='总仓' AND bin_code='COL-02');
