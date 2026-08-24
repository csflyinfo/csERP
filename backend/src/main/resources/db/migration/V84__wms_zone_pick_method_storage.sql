-- ============================================================
-- V84 WMS 库区拣货方式 + 存储属性（PRD-28）
--
-- 背景：
--   1. 原 WMS_PICK_MODE 是仓库级/波次级默认值，无法区分「整件区按单拣、拆零区合拣」
--      这种同一仓内多种拣货方式并存的实际场景。V84 把拣货方式下沉到库区。
--   2. 上架推荐只按 bin_type='SHELF' 过滤，没有按商品 storage_property（温区）
--      匹配库区，导致冷藏品可能被推荐到常温区。V84 给库区加 storage_property，
--      推荐库位时优先匹配温区，收货标签上也标明，便于上架人员正确上架。
--
-- 字段：
--   pick_method       拣货方式（仅 PICK/STORAGE 类库区有意义；其它留空）：
--                       CASE_PICK   整件单拣（整件区商品按订单分拣）
--                       CASE_MERGE  整件合拣（整件区商品合并拣货，配合 sort_mode）
--                       BULK_PICK   拆零单拣（拆零区商品按订单分别分拣）
--                       BULK_MERGE  拆零合拣（拆零区商品合并分拣，配合 sort_mode）
--   sort_mode         合拣分拣方式（pick_method 为 *_MERGE 时有效）：
--                       PICK_THEN_SORT 先拣后分（汇总拣货到分拣区再按单分播）
--                       SORT_WHILE_PICK 边拣边分（拣货同时按格口分播）
--                     单拣（*_PICK）时为 NULL。
--   storage_property  存储属性（温区），对应 base_goods.storage_property：
--                       常温 / 冷藏 / 冷冻 / 恒温 / 避光
--                     入库上架推荐优先把商品推荐到 storage_property 相同的库区。
--                     一个温区可以建多个库区（如 A 常温整箱、B 常温拆零），靠
--                     zone_code/pick_seq 区分。
--
-- 不删 WMS_PICK_MODE 参数：
--   它作为「库区未配置 pick_method 时」的兜底默认值，以及波次级手工覆盖的回退。
--   参数说明同步更新到「已下沉到库区」语义。
-- ============================================================

ALTER TABLE wms_zone ADD COLUMN pick_method       VARCHAR(20);
ALTER TABLE wms_zone ADD COLUMN sort_mode         VARCHAR(20);
ALTER TABLE wms_zone ADD COLUMN storage_property  VARCHAR(50);

-- 索引：上架推荐按 warehouse + storage_property 找候选库区
CREATE INDEX IF NOT EXISTS idx_zone_storage_property
    ON wms_zone(warehouse, storage_property);

-- 拣货任务记下当时的库区拣货方式，便于事后追溯（库区配置后来改了也不影响历史任务）
ALTER TABLE wms_pick_task ADD COLUMN pick_method VARCHAR(20);
ALTER TABLE wms_pick_task ADD COLUMN sort_mode   VARCHAR(20);

-- 入库任务明细增加推荐库区（推荐库位冗余一份库区名/温区，收货标签直接打印，不用再 JOIN）
ALTER TABLE wms_inbound_task_detail ADD COLUMN recommend_zone_code    VARCHAR(50);
ALTER TABLE wms_inbound_task_detail ADD COLUMN recommend_zone_name    VARCHAR(100);
ALTER TABLE wms_inbound_task_detail ADD COLUMN recommend_storage_prop VARCHAR(50);

-- 上架任务同步冗余推荐库区/温区
ALTER TABLE wms_putaway_task ADD COLUMN recommend_zone_code    VARCHAR(50);
ALTER TABLE wms_putaway_task ADD COLUMN recommend_zone_name    VARCHAR(100);
ALTER TABLE wms_putaway_task ADD COLUMN recommend_storage_prop VARCHAR(50);

-- 给现有种子库区一个合理的初始值（NOT EXISTS 保护，不覆盖运营已配数据）：
--   A 区(整箱储区)  → 常温 + 整件单拣（整箱储备+整箱出库按单拣）
--   B 区(拆零储区)  → 常温 + 拆零合拣（拆零合拣+先拣后分）
-- 这两条只在 pick_method IS NULL 时补，运营已配过则不动。
UPDATE wms_zone SET pick_method = 'CASE_PICK',  storage_property = '常温'
 WHERE warehouse = '总仓' AND zone_code = 'A' AND pick_method IS NULL;
UPDATE wms_zone SET pick_method = 'BULK_MERGE', sort_mode = 'PICK_THEN_SORT', storage_property = '常温'
 WHERE warehouse = '总仓' AND zone_code = 'B' AND pick_method IS NULL;

-- WMS_PICK_MODE 参数说明改为「兜底」语义（ON DUPLICATE KEY UPDATE 只改 remark/param_name，
-- 不动 param_value/default_value，避免冲掉运营调整）。
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0131','WMS_PICK_MODE','拣货方式兜底（库区未配置时）','0','0','WMS仓储',
        'V84 起：拣货方式已下沉到库区 wms_zone.pick_method；本参数仅作为库区未配置 pick_method 时的兜底，以及波次手工覆盖的回退。0=按单拣 1=汇总(先拣后分) 2=边拣边分 3=分区接力 4=越库直发')
ON DUPLICATE KEY UPDATE
    param_name = VALUES(param_name),
    remark     = VALUES(remark);
