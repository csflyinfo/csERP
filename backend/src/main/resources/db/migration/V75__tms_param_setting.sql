-- ============================================================
-- V75 TMS 系统参数设置（PRD-26 阶段 A）
--
-- 落库 11 项司机派送参数，供「系统管理 > 参数设置」页维护。
--
-- 幂等约定（照 V59 样板）：
--   1. 幂等键是 param_key（UNIQUE 约束），不是 param_id。
--      V61/V60 曾同用 P0105 撞号，param_id 仅作展示主键。
--   2. ON DUPLICATE KEY UPDATE 中不更新 param_value ——
--      重复执行迁移不能把运营已调的值冲回默认值。
--   3. default_value 与首次 param_value 写同值，
--      保证 COALESCE(param_value, default_value) 两条路径一致。
--
-- 默认值口径（PRD-26 §2.2 决策）：
--   - 一律以需求原文默认值为准；
--   - 唯一例外 TMS_RETURN_MERGE_SETTLE 取 Y（保留 PRD-25 现状，不产生行为变更）；
--   - TMS_SETTLE_PHOTO_REQUIRED 按原文改为 N，是本需求唯一行为变更项。
-- ============================================================

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0117', 'TMS_DRIVER_FLOW_ENABLED', '是否开启TMS司机派送流程', 'Y', 'Y', 'TMS配送',
        'Y=启用司机APP签收结算流程；N=不走司机派送，由后台直接签收。关闭后司机APP配送入口不可用')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0118', 'TMS_OFFSET_FUND_ACCOUNT', '司机合并结算冲抵资金账户', NULL, '', 'TMS配送',
        '送货与退货合并结算时冲抵流水记入的资金账户编码。须是 base_fund_account 末级且 status=NORMAL 的账户，建议单设「销退冲抵过渡户」，不可指向司机收款账户。留空则合并结算报错')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0119', 'TMS_RETURN_MERGE_SETTLE', '退货单与送货是否可合并结算', 'Y', 'Y', 'TMS配送',
        'Y=APP可勾选送货单与退货单一起结算（默认，与现有能力一致）；N=不可一起结算。N 分支的「退货签收即自动完成」能力待后续版本上线')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0120', 'TMS_SIGN_ESIGN_REQUIRED', '签收是否需要客户电子签名', 'N', 'N', 'TMS配送',
        'Y=签收页必须由客户手写电子签名；N=不需要。电子签名控件为后续版本能力，当前仅落库占位')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0121', 'TMS_SIGN_PHOTO_COUNT', '送货签收需拍照片数', '2', '2', 'TMS配送',
        '取值 0~5。0=不校验照片；超出范围自动钳制到 0~5，解析失败回落 2')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0122', 'TMS_RETURN_PHOTO_COUNT', '退货回收需拍照片数', '2', '2', 'TMS配送',
        '取值 0~5，含现场退货。0=不校验照片；超出范围自动钳制到 0~5，解析失败回落 2')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0123', 'TMS_SETTLE_PHOTO_REQUIRED', '门店结算是否强制上传照片', 'N', 'N', 'TMS配送',
        'Y=结算前必须上传凭证照片；N=不强制。行为变更：原实现为强制必填，本参数上线后默认放开')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0124', 'TMS_ACCEPT_BEFORE_SETTLE', '上一任务未交款是否可接单', 'N', 'N', 'TMS配送',
        'N=司机有未交款的历史派送任务时禁止接新单；Y=不校验，允许连续接单')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0125', 'TMS_ONSITE_RETURN_ENABLED', '司机是否可进行现场退货', 'Y', 'Y', 'TMS配送',
        'Y=门店详情页展示【现场退货】入口，司机可在门店直接发起退货；N=隐藏入口，退货只能由后台创建')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0126', 'TMS_HANDOVER_ESIGN_REQUIRED', '提交交账是否需要电子签名', 'N', 'N', 'TMS配送',
        'Y=交账提交前需司机手写电子签名确认；N=不需要。电子签名控件为后续版本能力，当前仅落库占位')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0127', 'TMS_APPEND_AFTER_DEPART', '司机发车后是否可追加任务', 'Y', 'Y', 'TMS配送',
        'Y=允许向已发车的调度单追加派送任务；N=发车后锁定任务清单，追加需另开调度单')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name), default_value = VALUES(default_value), remark = VALUES(remark);
