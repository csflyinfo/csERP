-- V88 集货区管理独立模块
-- 集货区/集货位复用 wms_zone(zone_type='COLLECT') / wms_bin(bin_type='COLLECT')，不新建表。
-- 字段语义（在应用层端点中落实）：
--   wms_zone.frozen = 'Y'  集货区停用，分配时级联排除该区下所有集货位（见 allocateCollectionBin）
--   wms_bin.status        集货位停用/启用（DISABLED/NORMAL）
--   wms_bin.frozen = 'Y'  集货位人工锁定（释放/锁定），不参与自动分配；区别于 used_qty 实际占用
-- 本迁移仅补一个按类型过滤的索引，COLLECT 种子数据已在 V78 建立。

CREATE INDEX IF NOT EXISTS idx_zone_type ON wms_zone(zone_type);
