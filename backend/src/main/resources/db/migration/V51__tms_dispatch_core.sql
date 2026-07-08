-- ============================================
-- V51: TMS 调度核心 + 销售退货单物流状态机
--
-- 背景：
--   · TMS 模块从 0 起步，本脚本落地 P1 调度核心 6 表 + 现有表扩展
--   · 同时落地 V1.2 退货单调度闭环所需字段（sales_return_apply 物流状态机）
--     —— 退货调度依赖调度核心表，一次性落地避免跨脚本依赖
--
-- 内容：
--   1. 调度核心 6 表：tms_dispatch / tms_dispatch_detail / tms_delivery_trip
--                     tms_sign_record / tms_sign_photo / tms_loading_check
--   2. tms_driver_return(+detail) 司机退货表（P3 用，先建表）
--   3. 扩展 base_customer（定位来源）、base_route_line（车辆）、sales_receipt（调度状态）
--   4. 扩展 sales_return_apply：退货类型 + 物流状态机字段（V1.2 退货调度闭环）
--   5. tms_dispatch_detail.bill_type 区分发货单(RECEIPT)/退货单(RETURN)
--
-- H2 兼容：新增列一律 ADD COLUMN IF NOT EXISTS；无 DROP COLUMN
-- ============================================

-- ------------------------------------------------------------
-- 1. 扩展现有表
-- ------------------------------------------------------------

-- 1.1 客户定位来源（经纬度已由 V11 加 longitude/latitude，这里只加来源标记）
ALTER TABLE base_customer ADD COLUMN IF NOT EXISTS address_geo_source VARCHAR(20) DEFAULT 'MANUAL'; -- MANUAL(手工)/DRIVER(司机纠偏)/IMPORT
ALTER TABLE base_customer ADD COLUMN IF NOT EXISTS address_detail VARCHAR(500);                     -- 详细地址（门牌号）

-- 1.2 线路车辆信息（driver 字段 V1 已有）
ALTER TABLE base_route_line ADD COLUMN IF NOT EXISTS vehicle_plate VARCHAR(50);    -- 车牌号
ALTER TABLE base_route_line ADD COLUMN IF NOT EXISTS vehicle_type  VARCHAR(50);    -- 车型（4.2米厢式等）
ALTER TABLE base_route_line ADD COLUMN IF NOT EXISTS load_capacity DECIMAL(18,4) DEFAULT 0; -- 载重（件数）

-- 1.3 发货单调度状态（V50 已加 sign_status/driver，这里加调度关联）
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS dispatch_status VARCHAR(20) DEFAULT 'UNDISPATCHED'; -- UNDISPATCHED/DISPATCHED/LOADED/DEPARTED/DELIVERING/COMPLETED/CANCELLED
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS dispatch_id VARCHAR(32);        -- 调度单 ID
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS trip_id VARCHAR(32);            -- 配送行程 ID
CREATE INDEX IF NOT EXISTS idx_sales_receipt_dispatch_status ON sales_receipt(dispatch_status);

-- 1.4 销售退货单物流状态机（V1.2 退货调度闭环）
--     return_type: DRIVER(司机回收)/WAREHOUSE(无司机自退)
--     logistics_status: 未安排/已安排调度/已调度/司机已回收
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS return_type VARCHAR(20) DEFAULT 'WAREHOUSE'; -- WAREHOUSE(无司机)/DRIVER(司机回收)
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS logistics_status VARCHAR(20) DEFAULT '未安排'; -- 未安排/已安排调度/已调度/司机已回收
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS signed_qty DECIMAL(18,4) DEFAULT 0;          -- 司机签收数量（APP 退货签收回写）
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS driver_id VARCHAR(32);                       -- 司机 employee_id
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS driver_name VARCHAR(100);                    -- 司机姓名（冗余）
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS dispatch_id VARCHAR(32);                     -- 调度单 ID
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS trip_id VARCHAR(32);                         -- 配送行程 ID
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS arrange_time TIMESTAMP;                      -- 安排调度时间
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS arrange_remark VARCHAR(500);                 -- 安排备注
CREATE INDEX IF NOT EXISTS idx_sales_return_apply_logistics ON sales_return_apply(logistics_status);
CREATE INDEX IF NOT EXISTS idx_sales_return_apply_return_type ON sales_return_apply(return_type);
CREATE INDEX IF NOT EXISTS idx_sales_return_apply_driver ON sales_return_apply(driver_id);

-- ------------------------------------------------------------
-- 2. 调度单主表
-- ------------------------------------------------------------
CREATE TABLE tms_dispatch (
  dispatch_id     VARCHAR(32) PRIMARY KEY,
  dispatch_no     VARCHAR(50) NOT NULL UNIQUE,        -- DD + yyyyMMdd + 4位流水
  dispatch_date   DATE NOT NULL,
  route_line      VARCHAR(100),                       -- 线路
  territory       VARCHAR(100),                       -- 片区
  driver_id       VARCHAR(32),                        -- 司机 employee_id
  driver_name     VARCHAR(100),                       -- 司机姓名（冗余）
  driver_mobile   VARCHAR(50),
  vehicle_plate   VARCHAR(50),                        -- 车牌
  vehicle_type    VARCHAR(50),
  load_capacity   DECIMAL(18,4) DEFAULT 0,            -- 车辆载重
  loaded_qty      DECIMAL(18,4) DEFAULT 0,            -- 已装载件数（发货单）
  return_qty      DECIMAL(18,4) DEFAULT 0,            -- 取货件数（退货单，不占载重）
  store_count     INT DEFAULT 0,                      -- 门店数
  amount          DECIMAL(18,2) DEFAULT 0,            -- 应收金额合计
  status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT', -- DRAFT/ASSIGNED/LOADED/DEPARTED/DELIVERING/COMPLETED/CANCELLED
  arrange_user    VARCHAR(100),                       -- 排线人
  arrange_time    TIMESTAMP,
  depart_time     TIMESTAMP,
  complete_time   TIMESTAMP,
  creator_name    VARCHAR(100),
  create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark          VARCHAR(500)
);
CREATE INDEX idx_tms_dispatch_no     ON tms_dispatch(dispatch_no);
CREATE INDEX idx_tms_dispatch_status ON tms_dispatch(status);
CREATE INDEX idx_tms_dispatch_date   ON tms_dispatch(dispatch_date);
CREATE INDEX idx_tms_dispatch_driver ON tms_dispatch(driver_id);
CREATE INDEX idx_tms_dispatch_line   ON tms_dispatch(route_line);

-- ------------------------------------------------------------
-- 3. 调度明细（发货单 + 退货单取货任务混合）
-- ------------------------------------------------------------
CREATE TABLE tms_dispatch_detail (
  detail_id       VARCHAR(32) PRIMARY KEY,
  dispatch_id     VARCHAR(32) NOT NULL,
  bill_type       VARCHAR(20) NOT NULL DEFAULT 'RECEIPT', -- RECEIPT(发货单)/RETURN(退货单取货)
  source_bill_no  VARCHAR(50) NOT NULL,                -- 发货单号/退货单号
  source_bill_id  VARCHAR(32),
  customer_code   VARCHAR(50),
  customer_name   VARCHAR(200),
  customer_address VARCHAR(500),
  territory       VARCHAR(100),
  route_line      VARCHAR(100),
  qty             DECIMAL(18,4) DEFAULT 0,             -- 发货件数 / 退货待回收件数
  amount          DECIMAL(18,2) DEFAULT 0,             -- 应收金额 / 退货金额（负向参考）
  sku_count       INT DEFAULT 0,                       -- SKU 数
  seq_no          INT DEFAULT 0,                       -- 配送顺序
  status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING/DELIVERED/PARTIAL/REJECTED/RETURNED
  sign_time       TIMESTAMP,
  sign_user       VARCHAR(100),
  remark          VARCHAR(500)
);
CREATE INDEX idx_tms_dispatch_detail_dispatch ON tms_dispatch_detail(dispatch_id);
CREATE INDEX idx_tms_dispatch_detail_bill     ON tms_dispatch_detail(bill_type);
CREATE INDEX idx_tms_dispatch_detail_source   ON tms_dispatch_detail(source_bill_no);
CREATE INDEX idx_tms_dispatch_detail_customer ON tms_dispatch_detail(customer_code);

-- ------------------------------------------------------------
-- 4. 配送行程（一次出车一条，关联调度单 + 司机 + 车辆）
-- ------------------------------------------------------------
CREATE TABLE tms_delivery_trip (
  trip_id         VARCHAR(32) PRIMARY KEY,
  trip_no         VARCHAR(50) NOT NULL UNIQUE,         -- XC + yyyyMMdd + 4位流水
  dispatch_id     VARCHAR(32) NOT NULL,
  driver_id       VARCHAR(32),
  driver_name     VARCHAR(100),
  vehicle_plate   VARCHAR(50),
  route_line      VARCHAR(100),
  trip_date       DATE NOT NULL,
  status          VARCHAR(20) NOT NULL DEFAULT 'PLANNED', -- PLANNED/LOADED/DEPARTED/DELIVERING/COMPLETED/CANCELLED
  total_store     INT DEFAULT 0,
  delivered_store INT DEFAULT 0,
  total_qty       DECIMAL(18,4) DEFAULT 0,
  delivered_qty   DECIMAL(18,4) DEFAULT 0,
  collected_amount DECIMAL(18,2) DEFAULT 0,            -- 已收金额
  loading_time    TIMESTAMP,
  depart_time     TIMESTAMP,
  complete_time   TIMESTAMP,
  create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark          VARCHAR(500)
);
CREATE INDEX idx_tms_delivery_trip_dispatch ON tms_delivery_trip(dispatch_id);
CREATE INDEX idx_tms_delivery_trip_driver   ON tms_delivery_trip(driver_id);
CREATE INDEX idx_tms_delivery_trip_status   ON tms_delivery_trip(status);
CREATE INDEX idx_tms_delivery_trip_date     ON tms_delivery_trip(trip_date);

-- ------------------------------------------------------------
-- 5. 签收记录（P1 建表，P2 使用）
-- ------------------------------------------------------------
CREATE TABLE tms_sign_record (
  sign_id         VARCHAR(32) PRIMARY KEY,
  dispatch_id     VARCHAR(32) NOT NULL,
  detail_id       VARCHAR(32) NOT NULL,                -- 调度明细行
  trip_id         VARCHAR(32),
  source_bill_no  VARCHAR(50),
  customer_code   VARCHAR(50),
  customer_name   VARCHAR(200),
  bill_type       VARCHAR(20) DEFAULT 'RECEIPT',       -- RECEIPT/RETURN
  sign_type       VARCHAR(20) DEFAULT 'NORMAL',        -- NORMAL(正常)/PARTIAL(部分)/REJECT(拒收)
  signed_qty      DECIMAL(18,4) DEFAULT 0,             -- 实收/实回收数量
  reject_qty      DECIMAL(18,4) DEFAULT 0,             -- 拒收数量
  collect_amount  DECIMAL(18,2) DEFAULT 0,             -- 收款金额
  pay_method      VARCHAR(20),                         -- 现金/微信/支付宝/赊账
  sign_time       TIMESTAMP NOT NULL,
  sign_user       VARCHAR(100),                        -- 司机
  customer_signer VARCHAR(100),                        -- 客户签收人
  customer_sign_img VARCHAR(500),                      -- 电子签名图 URL
  remark          VARCHAR(500)
);
CREATE INDEX idx_tms_sign_record_dispatch ON tms_sign_record(dispatch_id);
CREATE INDEX idx_tms_sign_record_detail   ON tms_sign_record(detail_id);
CREATE INDEX idx_tms_sign_record_bill     ON tms_sign_record(source_bill_no);

-- ------------------------------------------------------------
-- 6. 签收照片（P1 建表，P2 使用）
-- ------------------------------------------------------------
CREATE TABLE tms_sign_photo (
  photo_id        VARCHAR(32) PRIMARY KEY,
  sign_id         VARCHAR(32) NOT NULL,
  photo_type      VARCHAR(20) DEFAULT 'GOODS',         -- GOODS(货物)/RECEIPT(签收单)/RETURN(退货品)
  photo_url       VARCHAR(500) NOT NULL,
  photo_path      VARCHAR(500),                        -- MinIO 对象路径
  create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_tms_sign_photo_sign ON tms_sign_photo(sign_id);

-- ------------------------------------------------------------
-- 7. 装车核对（P1 建表，P2 使用）
-- ------------------------------------------------------------
CREATE TABLE tms_loading_check (
  check_id        VARCHAR(32) PRIMARY KEY,
  dispatch_id     VARCHAR(32) NOT NULL,
  trip_id         VARCHAR(32),
  detail_id       VARCHAR(32),
  source_bill_no  VARCHAR(50),
  goods_code      VARCHAR(50),
  goods_name      VARCHAR(200),
  loaded_qty      DECIMAL(18,4) DEFAULT 0,             -- 实际装车数量
  required_qty    DECIMAL(18,4) DEFAULT 0,             -- 应装数量
  diff_qty        DECIMAL(18,4) DEFAULT 0,             -- 差异
  check_time      TIMESTAMP,
  checker         VARCHAR(100),
  remark          VARCHAR(500)
);
CREATE INDEX idx_tms_loading_check_dispatch ON tms_loading_check(dispatch_id);

-- ------------------------------------------------------------
-- 8. 司机退货表（P3 司机现场退货回收，先建表，V1.2 调度闭环复用 sales_return_apply 物流状态字段）
-- ------------------------------------------------------------
CREATE TABLE tms_driver_return (
  driver_return_id VARCHAR(32) PRIMARY KEY,
  driver_return_no VARCHAR(50) NOT NULL UNIQUE,        -- XTSQ 复用销售退货申请号规则
  return_apply_no  VARCHAR(50),                        -- 关联 sales_return_apply.apply_no（双向）
  trip_id          VARCHAR(32),
  dispatch_id      VARCHAR(32),
  driver_id        VARCHAR(32),
  driver_name      VARCHAR(100),
  customer_code    VARCHAR(50),
  customer_name    VARCHAR(200),
  return_date      DATE NOT NULL,
  qty              DECIMAL(18,4) DEFAULT 0,
  status           VARCHAR(20) DEFAULT 'PENDING',      -- PENDING/LOADED/WAREHOUSED
  create_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark           VARCHAR(500)
);
CREATE INDEX idx_tms_driver_return_apply ON tms_driver_return(return_apply_no);
CREATE INDEX idx_tms_driver_return_trip  ON tms_driver_return(trip_id);

CREATE TABLE tms_driver_return_detail (
  detail_id         VARCHAR(32) PRIMARY KEY,
  driver_return_id  VARCHAR(32) NOT NULL,
  goods_code        VARCHAR(50),
  goods_name        VARCHAR(200),
  spec              VARCHAR(200),
  unit_name         VARCHAR(50),
  qty               DECIMAL(18,4) DEFAULT 0,
  batch_no          VARCHAR(100),
  remark            VARCHAR(500)
);
CREATE INDEX idx_tms_driver_return_detail_head ON tms_driver_return_detail(driver_return_id);
