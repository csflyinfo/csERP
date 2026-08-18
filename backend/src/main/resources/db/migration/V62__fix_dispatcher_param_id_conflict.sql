-- ============================================
-- V62 修正 V61 参数主键撞号（TMS_DISPATCHER_PHONE 未落库 + P0105 被改写）
--
-- 缺陷复盘：
--   · V61 用 param_id='P0105' 插入 TMS_DISPATCHER_PHONE，但该主键已被
--     V60 的 SALES_RETURN_AR_TIMING 占用；
--   · ON DUPLICATE KEY UPDATE 对「任意唯一键冲突」都会触发，于是主键撞号后
--     并未插入新行，而是把 V60 那行的 param_name/default_value/remark 改写成了
--     调度中心电话的内容 —— 结果是 param_key 与 param_name 张冠李戴，
--     且 APP 永远读不到 TMS_DISPATCHER_PHONE（「联系调度员」恒为未配置）。
--
-- 修复策略（幂等，可在已跑过/未跑过 V61 的库上重复执行）：
--   1. 还原 P0105 应有的销售退货参数描述；
--   2. 用未占用的 P0107 重新落地 TMS_DISPATCHER_PHONE；
--   3. 顺带把 V61 里同样自行占用的 P0106（TMS_DISPATCHER_NAME）迁到 P0108，
--      使两个调度参数号段连续，避免后续再被误占。
--   为兼容 H2 与 MySQL，改写一律用 UPDATE + 条件插入，不用 ON DUPLICATE KEY。
-- ============================================

-- 1. 还原被 V61 改写的销售退货参数（按 param_key 定位，不依赖 param_id）
UPDATE sys_param_runtime
   SET param_name = '销售退货入账时点',
       default_value = 'WAREHOUSE_INBOUND',
       param_group = '销售退货',
       remark = 'WAREHOUSE_INBOUND=按仓库收货入账，退货入库单审核后才允许审核退货单；DRIVER_SIGN=按司机回收入账，司机确认回收即自动审核退货单并写负向应收'
 WHERE param_key = 'SALES_RETURN_AR_TIMING';

-- 2. 清理 V61 可能留下的错号行：TMS_DISPATCHER_NAME 若落在 P0106 则先删除，
--    统一由下面按新号段重建，避免同 key 两行
DELETE FROM sys_param_runtime
 WHERE param_key IN ('TMS_DISPATCHER_PHONE', 'TMS_DISPATCHER_NAME');

-- 3. 按未占用号段重新落地调度中心联系方式
--    上一步已删除同 key 旧行，这里直接 INSERT 即可，不需要 ON DUPLICATE / NOT EXISTS
--    （MySQL 不允许无 FROM 的 SELECT ... WHERE，避开该写法保持双库兼容）
--    电话留空：APP 读到空值时提示管理员去配置，不展示空号码
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0107', 'TMS_DISPATCHER_PHONE', '调度中心电话', '', '', 'TMS配送', '司机APP「联系调度员」拨打的号码；留空则APP提示未配置');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0108', 'TMS_DISPATCHER_NAME', '调度中心名称', '调度中心', '调度中心', 'TMS配送', '司机APP拨号提示上展示的名称');
