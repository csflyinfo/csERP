-- V66: 修正推送渠道默认值并补充个推服务端凭据参数（PRD-TMS-P4-2）
--
-- 背景：V65 建表与参数时按极光（JPUSH）预留，实际接入的是个推（GETUI）。
-- TmsNotifyService 查设备令牌用的是 `channel = ?` 精确匹配，
-- 若参数值与 APP 上报值（固定 GETUI）不一致，会查不到任何令牌，
-- 推送链路静默走不通且只留一条「无可用设备令牌」，极难排查。故此处三处一起对齐。

-- 1) 表默认值改为 GETUI。
--    仅影响后续未显式传 channel 的插入；Controller 已显式传值，此改动是兜底。
--    H2 与 MySQL 均支持 ALTER COLUMN ... SET DEFAULT 语法。
ALTER TABLE tms_push_token ALTER COLUMN channel SET DEFAULT 'GETUI';

-- 2) 纠正历史数据。
--    V65 上线期间 APP 若已注册过令牌，channel 会被写成 JPUSH，
--    这些令牌本身是个推 CID，只是渠道名标错了，改名即可复用，不必让司机重新登录。
UPDATE tms_push_token SET channel = 'GETUI' WHERE channel = 'JPUSH';

-- 3) 渠道参数改为 GETUI（param_id 保持 P0112，仅改值与说明）。
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0112', 'TMS_PUSH_CHANNEL', '推送渠道', 'GETUI', 'GETUI', 'TMS配送',
        'GETUI=个推（当前唯一已实现）；改为其他值将跳过推送并记警告日志')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), param_value = VALUES(param_value),
                        default_value = VALUES(default_value), remark = VALUES(remark);

-- 4) 个推服务端凭据。
--    放参数表而非 application.yml：客户自行申请个推应用后可在参数维护页直接填，
--    不需要改配置文件重启服务。三项任一为空时 TmsNotifyService 标记 SKIPPED 不发起调用。
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0114', 'TMS_PUSH_GETUI_APP_ID', '个推AppID', NULL, '', 'TMS配送',
        '个推开发者中心「应用配置」中的 AppID，用于拼接推送接口地址')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0115', 'TMS_PUSH_GETUI_APP_KEY', '个推AppKey', NULL, '', 'TMS配送',
        '个推 AppKey，用于服务端鉴权签名')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0116', 'TMS_PUSH_GETUI_MASTER_SECRET', '个推MasterSecret', NULL, '', 'TMS配送',
        '个推 MasterSecret，服务端鉴权用，属敏感凭据，请勿在客户端配置')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);
