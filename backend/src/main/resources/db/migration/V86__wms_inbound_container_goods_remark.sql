-- ============================================================
-- V86: WMS 收货增强
--   1) 收货明细支持容器收货：wms_inbound_task_detail / wms_putaway_task 加 container_code
--   2) 收货明细支持行级备注：goods_remark（PDA 扫码收货页可填）
--   3) wms_container 加当前任务字段，跟踪容器正在为哪张收货任务服务
--   4) 新增 wms_container_record 容器作业流水（绑定/收货/封口/上架/释放）
--   5) base_goods.storage_property 已在 V1 建表，本次不动
-- 幂等：所有 ALTER 用 ADD COLUMN IF NOT EXISTS，CREATE TABLE 用 IF NOT EXISTS
-- ============================================================

-- 1. 收货明细：容器 + 行级备注
ALTER TABLE wms_inbound_task_detail ADD COLUMN IF NOT EXISTS container_code VARCHAR(50);
ALTER TABLE wms_inbound_task_detail ADD COLUMN IF NOT EXISTS goods_remark   VARCHAR(255);

-- 2. 上架任务：携带容器编号（用于整容器上架）
ALTER TABLE wms_putaway_task ADD COLUMN IF NOT EXISTS container_code VARCHAR(50);

-- 3. 容器档案：当前任务追踪
ALTER TABLE wms_container ADD COLUMN IF NOT EXISTS current_task_id   VARCHAR(32);
ALTER TABLE wms_container ADD COLUMN IF NOT EXISTS current_task_no   VARCHAR(50);
ALTER TABLE wms_container ADD COLUMN IF NOT EXISTS bound_at          TIMESTAMP NULL;
ALTER TABLE wms_container ADD COLUMN IF NOT EXISTS bound_by          VARCHAR(50);

-- 4. 容器作业流水
CREATE TABLE IF NOT EXISTS wms_container_record (
    record_id      VARCHAR(40) PRIMARY KEY,
    container_code VARCHAR(50) NOT NULL,
    task_id        VARCHAR(32),
    task_no        VARCHAR(50),
    putaway_id     VARCHAR(32),
    operation      VARCHAR(20) NOT NULL,  -- BIND / RECEIVE / SEAL / PUTAWAY / RELEASE
    goods_code     VARCHAR(50),
    qty            DECIMAL(18,3),
    operator       VARCHAR(50),
    remark         VARCHAR(255),
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_container_record_code ON wms_container_record(container_code, created_at);
CREATE INDEX IF NOT EXISTS idx_container_record_task ON wms_container_record(task_id);

-- 5. 索引：按容器查上架任务
CREATE INDEX IF NOT EXISTS idx_putaway_container ON wms_putaway_task(container_code, status);
CREATE INDEX IF NOT EXISTS idx_inbound_detail_container ON wms_inbound_task_detail(task_id, container_code);

-- 6. 库位库存流水也补容器列（原表 V78 未加），用于容器收货时正确记录来源容器
ALTER TABLE wms_bin_stock_log ADD COLUMN IF NOT EXISTS container_code VARCHAR(50);
