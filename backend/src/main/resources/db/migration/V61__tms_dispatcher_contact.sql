-- ============================================
-- V61 调度员联系方式参数（司机 APP「联系调度员」）
--
-- 设计说明：
--   · APP 需要一个可拨打的调度中心电话，但系统内没有「当前司机归属调度员」的关系表，
--     route_line.driver 只到司机维度，强行推断归属会在多线路司机上出错；
--   · 因此按既有做法收敛为运行参数，由管理员在参数配置页维护，
--     与 TMS_ARRIVE_* 参数保持同一 param_group，便于集中管理；
--   · 参数值留空时 APP 隐藏该入口，不展示空号码。
-- ============================================

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0105', 'TMS_DISPATCHER_PHONE', '调度中心电话', '', '', 'TMS配送', '司机APP「联系调度员」拨打的号码；留空则APP隐藏该入口')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0106', 'TMS_DISPATCHER_NAME', '调度中心名称', '调度中心', '调度中心', 'TMS配送', '司机APP拨号确认弹窗上展示的名称')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);
