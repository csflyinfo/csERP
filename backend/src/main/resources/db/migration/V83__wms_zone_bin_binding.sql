-- ============================================================
-- V83 WMS V1.5 库区/库位治理 + 拣货位商品绑定 + 预分配按线路（PRD-28）
--
-- 内容：
--   1. wms_bin_goods_binding   拣货位(或任意库位)与商品的固定绑定关系
--   2. wms_binding_change_log  绑定/解绑/改绑/转移 的审计日志
--
-- 账实分离：本迁移只涉及"位置-商品"的逻辑绑定关系（用于补货推荐、拣货路径、
-- PDA 扫码定位），不写入任何数量/金额。库存数量仍以 wms_bin_stock +
-- inv_stock_balance / inv_batch_stock 为准。
--
-- 设计要点：
--   - 一个库位可绑定多个商品（一位多品），由 uk_bin_goods 唯一键保证同库位+同商品
--     只有一条历史记录（重复绑定时走 UPDATE 重新激活，不新增行）。
--   - 「同仓库+同商品+同类型只能有一条生效绑定」的业务约束由服务层保证
--     （bind/transfer 时先把其它记录 active_flag 置 N），不放到数据库唯一键，
--     否则转移/解绑后历史记录会与新记录冲突。
--   - 绑定类型 binding_type：PICK 拣货位 / RESERVE 备货位 / BULK 整托位。
--   - min_qty/max_qty/replenish_to_qty 驱动补货任务；PDA 与 PC 共用同一接口。
--   - 不写外键（与既有 WMS 表一致），不 DROP。
-- ============================================================

CREATE TABLE IF NOT EXISTS wms_bin_goods_binding (
  binding_id          VARCHAR(32) PRIMARY KEY,
  warehouse           VARCHAR(100) NOT NULL,
  zone_code           VARCHAR(50)  NOT NULL,
  bin_code            VARCHAR(50)  NOT NULL,
  goods_code          VARCHAR(50)  NOT NULL,
  goods_name          VARCHAR(200),
  binding_type        VARCHAR(20) DEFAULT 'PICK',  -- PICK 拣货位 / RESERVE 备货位 / BULK 整托位
  min_qty             DECIMAL(18,3) DEFAULT 0,     -- 低于此值触发补货
  max_qty             DECIMAL(18,3) DEFAULT 0,     -- 拣货位容量上限
  replenish_to_qty    DECIMAL(18,3) DEFAULT 0,     -- 补货目标量
  active_flag         CHAR(1) DEFAULT 'Y',         -- Y=生效 N=暂停(不删历史)
  remark              VARCHAR(255),
  bound_by            VARCHAR(100),
  bound_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_bin_goods UNIQUE (warehouse, bin_code, goods_code)
);

CREATE INDEX IF NOT EXISTS idx_binding_bin ON wms_bin_goods_binding(warehouse, zone_code, bin_code);

CREATE TABLE IF NOT EXISTS wms_binding_change_log (
  log_id          VARCHAR(32) PRIMARY KEY,
  warehouse       VARCHAR(100),
  goods_code      VARCHAR(50),
  from_bin        VARCHAR(50),
  to_bin          VARCHAR(50),
  change_type     VARCHAR(20) NOT NULL,   -- BIND 绑定 / UNBIND 解绑 / TRANSFER 转移 / UPDATE 调整阈值
  operator        VARCHAR(100),
  source          VARCHAR(20) DEFAULT 'PC', -- PC / PDA
  remark          VARCHAR(255),
  occurred_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_binding_log_goods ON wms_binding_change_log(warehouse, goods_code);
