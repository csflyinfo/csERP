-- ============================================================
-- V81 WMS 参数数字化 & 分组（PRD-28 §10）
--
-- 1. sys_param_runtime 增加控件元数据列（param_type / option_json / sort_no / min/max/unit）
-- 2. 已有 WMS 参数（P0129~P0154）：
--    - 分组从 "WMS仓储" 拆为 WMS基础参数 / WMS入库 / WMS出库
--    - 枚举值英文 → 数字（0/1/2…），布尔 Y/N → 1/0
--    - 回填 param_type / option_json / sort_no
-- 3. 新增入库 & 库内参数（P0155~P0175）
-- 4. 已有波次/拣货任务里的字符串枚举同步转数字（种子库无波次数据，保险起见留 UPDATE）
--
-- 约定：H2(MODE=MySQL)，不写 DROP。
-- ============================================================

-- ---------- 1. 元数据列 ----------
ALTER TABLE sys_param_runtime ADD COLUMN IF NOT EXISTS param_type  VARCHAR(20)  DEFAULT 'TEXT';
ALTER TABLE sys_param_runtime ADD COLUMN IF NOT EXISTS option_json VARCHAR(2000);
ALTER TABLE sys_param_runtime ADD COLUMN IF NOT EXISTS sort_no     INT          DEFAULT 0;
ALTER TABLE sys_param_runtime ADD COLUMN IF NOT EXISTS min_value   DECIMAL(18,3);
ALTER TABLE sys_param_runtime ADD COLUMN IF NOT EXISTS max_value   DECIMAL(18,3);
ALTER TABLE sys_param_runtime ADD COLUMN IF NOT EXISTS unit        VARCHAR(20);

-- ---------- 2. 已有参数值转换 + 元数据 ----------
-- 注意：UPDATE 必须在元数据列加完之后执行。

-- 2.1 布尔 Y/N → 1/0（先统一转值，再设类型）
UPDATE sys_param_runtime SET param_value = '1', default_value = '1' WHERE param_key IN
  ('WMS_PICK_ZONE_ENABLED','WMS_PICK_BATCH_CHANGE_ALLOWED','WMS_CHECK_ENABLED',
   'WMS_CHECK_SHORT_ALLOWED','WMS_CANCEL_RELEASE_LOCK','WMS_EXPEDITE_ENABLED',
   'WMS_REPLENISH_URGENT_ENABLED','WMS_PDA_CLICK_PICK','WMS_EXCEPTION_AUTO_SUSPEND')
  AND param_value = 'Y';
UPDATE sys_param_runtime SET param_value = '0', default_value = '0' WHERE param_key IN
  ('WMS_PICK_ZONE_ENABLED','WMS_PICK_BATCH_CHANGE_ALLOWED','WMS_CHECK_ENABLED',
   'WMS_CHECK_SHORT_ALLOWED','WMS_CANCEL_RELEASE_LOCK','WMS_EXPEDITE_ENABLED',
   'WMS_REPLENISH_URGENT_ENABLED','WMS_PDA_CLICK_PICK','WMS_EXCEPTION_AUTO_SUSPEND')
  AND param_value = 'N';
UPDATE sys_param_runtime SET default_value = '1' WHERE param_key IN
  ('WMS_PICK_ZONE_ENABLED','WMS_PICK_BATCH_CHANGE_ALLOWED','WMS_CHECK_ENABLED',
   'WMS_CANCEL_RELEASE_LOCK','WMS_EXPEDITE_ENABLED','WMS_REPLENISH_URGENT_ENABLED',
   'WMS_PDA_CLICK_PICK','WMS_EXCEPTION_AUTO_SUSPEND') AND default_value = 'Y';
UPDATE sys_param_runtime SET default_value = '0' WHERE param_key IN
  ('WMS_CHECK_SHORT_ALLOWED') AND default_value = 'N';
-- WMS_WAVE_AUTO_RELEASE 默认 N
UPDATE sys_param_runtime SET param_value = '0', default_value = '0'
  WHERE param_key = 'WMS_WAVE_AUTO_RELEASE' AND param_value = 'N';
UPDATE sys_param_runtime SET param_value = '1' WHERE param_key = 'WMS_WAVE_AUTO_RELEASE' AND param_value = 'Y';
UPDATE sys_param_runtime SET default_value = '0' WHERE param_key = 'WMS_WAVE_AUTO_RELEASE' AND default_value = 'N';

-- 2.2 枚举英文 → 数字
-- WMS_PICK_MODE: ORDER→0, SUMMARY→1, PICK_SORT→2, ZONE_RELAY→3, CROSS_DOCK→4
UPDATE sys_param_runtime SET param_value = CASE param_value
  WHEN 'ORDER' THEN '0' WHEN 'SUMMARY' THEN '1' WHEN 'PICK_SORT' THEN '2'
  WHEN 'ZONE_RELAY' THEN '3' WHEN 'CROSS_DOCK' THEN '4' ELSE param_value END,
  default_value = CASE default_value
  WHEN 'ORDER' THEN '0' WHEN 'SUMMARY' THEN '1' WHEN 'PICK_SORT' THEN '2'
  WHEN 'ZONE_RELAY' THEN '3' WHEN 'CROSS_DOCK' THEN '4' ELSE default_value END
  WHERE param_key = 'WMS_PICK_MODE';

-- WMS_BATCH_ALLOC_STRATEGY: FEFO→0, FIFO→1, SPECIFIED→2
UPDATE sys_param_runtime SET param_value = CASE param_value
  WHEN 'FEFO' THEN '0' WHEN 'FIFO' THEN '1' WHEN 'SPECIFIED' THEN '2' ELSE param_value END,
  default_value = CASE default_value
  WHEN 'FEFO' THEN '0' WHEN 'FIFO' THEN '1' WHEN 'SPECIFIED' THEN '2' ELSE default_value END
  WHERE param_key = 'WMS_BATCH_ALLOC_STRATEGY';

-- WMS_CROSS_ZONE_MODE: FREE→0, ASSIGN→1, BOTH→2
UPDATE sys_param_runtime SET param_value = CASE param_value
  WHEN 'FREE' THEN '0' WHEN 'ASSIGN' THEN '1' WHEN 'BOTH' THEN '2' ELSE param_value END,
  default_value = CASE default_value
  WHEN 'FREE' THEN '0' WHEN 'ASSIGN' THEN '1' WHEN 'BOTH' THEN '2' ELSE default_value END
  WHERE param_key = 'WMS_CROSS_ZONE_MODE';

-- WMS_COLLECTION_MODE: ORDER→0, STORE→1, SMART→2
UPDATE sys_param_runtime SET param_value = CASE param_value
  WHEN 'ORDER' THEN '0' WHEN 'STORE' THEN '1' WHEN 'SMART' THEN '2' ELSE param_value END,
  default_value = CASE default_value
  WHEN 'ORDER' THEN '0' WHEN 'STORE' THEN '1' WHEN 'SMART' THEN '2' ELSE default_value END
  WHERE param_key = 'WMS_COLLECTION_MODE';

-- WMS_COLLECTION_BY: QTY→0, VOLUME→1, WEIGHT→2
UPDATE sys_param_runtime SET param_value = CASE param_value
  WHEN 'QTY' THEN '0' WHEN 'VOLUME' THEN '1' WHEN 'WEIGHT' THEN '2' ELSE param_value END,
  default_value = CASE default_value
  WHEN 'QTY' THEN '0' WHEN 'VOLUME' THEN '1' WHEN 'WEIGHT' THEN '2' ELSE default_value END
  WHERE param_key = 'WMS_COLLECTION_BY';

-- WMS_CHECK_MODE: FORCED→0, SAMPLE→1, NONE→2
UPDATE sys_param_runtime SET param_value = CASE param_value
  WHEN 'FORCED' THEN '0' WHEN 'SAMPLE' THEN '1' WHEN 'NONE' THEN '2' ELSE param_value END,
  default_value = CASE default_value
  WHEN 'FORCED' THEN '0' WHEN 'SAMPLE' THEN '1' WHEN 'NONE' THEN '2' ELSE default_value END
  WHERE param_key = 'WMS_CHECK_MODE';

-- WMS_CHECK_SCOPE: WHOLE→0, SPLIT→1
UPDATE sys_param_runtime SET param_value = CASE param_value
  WHEN 'WHOLE' THEN '0' WHEN 'SPLIT' THEN '1' ELSE param_value END,
  default_value = CASE default_value
  WHEN 'WHOLE' THEN '0' WHEN 'SPLIT' THEN '1' ELSE default_value END
  WHERE param_key = 'WMS_CHECK_SCOPE';

-- WMS_CHECK_UNIT: ORDER→0, PICK→1
UPDATE sys_param_runtime SET param_value = CASE param_value
  WHEN 'ORDER' THEN '0' WHEN 'PICK' THEN '1' ELSE param_value END,
  default_value = CASE default_value
  WHEN 'ORDER' THEN '0' WHEN 'PICK' THEN '1' ELSE default_value END
  WHERE param_key = 'WMS_CHECK_UNIT';

-- WMS_DISPATCH_TIMING: AT_RELEASE→0, AFTER_PICK→1, AFTER_OUTBOUND→2
UPDATE sys_param_runtime SET param_value = CASE param_value
  WHEN 'AT_RELEASE' THEN '0' WHEN 'AFTER_PICK' THEN '1' WHEN 'AFTER_OUTBOUND' THEN '2' ELSE param_value END,
  default_value = CASE default_value
  WHEN 'AT_RELEASE' THEN '0' WHEN 'AFTER_PICK' THEN '1' WHEN 'AFTER_OUTBOUND' THEN '2' ELSE default_value END
  WHERE param_key = 'WMS_DISPATCH_TIMING';

-- WMS_PDA_VIEW: FOCUS→0, LIST→1
UPDATE sys_param_runtime SET param_value = CASE param_value
  WHEN 'FOCUS' THEN '0' WHEN 'LIST' THEN '1' ELSE param_value END,
  default_value = CASE default_value
  WHEN 'FOCUS' THEN '0' WHEN 'LIST' THEN '1' ELSE default_value END
  WHERE param_key = 'WMS_PDA_VIEW';

-- 2.3 回填 param_type / option_json / sort_no / 分组 / remark（按新分组重写）
-- 基础参数（sort 10~99）
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=10,
  remark='1=按设定时点自动组波下放；0=仅人工在预分配页下放'
  WHERE param_key='WMS_WAVE_AUTO_RELEASE';
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='TEXT', sort_no=11,
  remark='逗号分隔，24小时制 HH:mm，自动组波的下放时点'
  WHERE param_key='WMS_WAVE_RELEASE_CRON';
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=12,
  remark='1=撤销未拣订单时释放批次锁并清空库位预占；0=保留预占'
  WHERE param_key='WMS_CANCEL_RELEASE_LOCK';
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=13,
  remark='1=缺货/差异超阈值自动挂起订单并通知主管；0=仅记录不挂起'
  WHERE param_key='WMS_EXCEPTION_AUTO_SUSPEND';
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=20,
  remark='1=PDA可点击商品行完成拣货；0=仅扫码确认'
  WHERE param_key='WMS_PDA_CLICK_PICK';
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='SELECT',
  option_json='[{"value":"0","label":"逐件聚焦（大扫码+当前商品）"},{"value":"1","label":"清单总览"}]',
  sort_no=21,
  remark='PDA 默认拣货视图'
  WHERE param_key='WMS_PDA_VIEW';
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='BOOL',
  option_json='[{"value":"1","label":"启用"},{"value":"0","label":"关闭"}]',
  sort_no=30,
  remark='1=定时扫描拣货位低于安全库存自动生成补货任务；0=仅手动/被动补货'
  WHERE param_key='WMS_REPLENISH_ACTIVE_ENABLED';
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='SELECT',
  option_json='[{"value":"0","label":"补到容量上限"},{"value":"1","label":"补到N天销量"},{"value":"2","label":"固定补货量"}]',
  sort_no=31,
  remark='主动补货的补货量计算规则'
  WHERE param_key='WMS_REPLENISH_QTY_RULE';
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=40,
  remark='1=盘点时冻结被盘库位禁止拣货；0=不冻结'
  WHERE param_key='WMS_COUNT_FREEZE_BIN';
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='SELECT',
  option_json='[{"value":"0","label":"明盘（实时显示差异）"},{"value":"1","label":"暗盘（提交后才显示差异）"}]',
  sort_no=41,
  remark='盘点默认模式'
  WHERE param_key='WMS_COUNT_MODE_DEFAULT';
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=50,
  remark='1=报损单必须上传现场照片才能提交；0=不强制'
  WHERE param_key='WMS_DAMAGE_NEED_PHOTO';
UPDATE sys_param_runtime SET param_group='WMS基础参数', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=51,
  remark='1=过期批次自动冻结不可拣；0=不自动冻结'
  WHERE param_key='WMS_EXPIRY_FREEZE';

-- 入库参数（sort 10~99）
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=10,
  remark='1=采购到货需先预约才能收货；0=可直接收货'
  WHERE param_key='WMS_ASN_APPOINT_ENABLED';
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=11,
  remark='1=收货界面不显示应收数量（盲收）；0=显示应收数量'
  WHERE param_key='WMS_BLINDED_RECEIVE';
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='NUMBER',
  min_value=0, max_value=100, unit='%', sort_no=12,
  remark='超收容差百分比，0=不允许超收'
  WHERE param_key='WMS_OVER_RECEIVE_TOLERANCE';
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=13,
  remark='1=允许无采购订单收货（暂收区隔离）；0=必须有单收货'
  WHERE param_key='WMS_RECEIVE_WITHOUT_ORDER';
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=14,
  remark='1=一张采购订单可分多次收货；0=必须一次收完'
  WHERE param_key='WMS_PO_MULTI_RECEIVE';
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=20,
  remark='1=收货后需复检才能上架；0=收货后直接上架'
  WHERE param_key='WMS_RECHECK_ENABLED';
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='SELECT',
  option_json='[{"value":"0","label":"全部复检"},{"value":"1","label":"按金额阈值"},{"value":"2","label":"按比例抽核"}]',
  sort_no=21,
  remark='收货复检模式'
  WHERE param_key='WMS_RECHECK_MODE';
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='NUMBER',
  min_value=0, unit='元', sort_no=22,
  remark='金额阈值，超过该值的收货单需复检（复检模式=按金额阈值时生效）'
  WHERE param_key='WMS_RECHECK_AMOUNT_THRESHOLD';
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='NUMBER',
  min_value=0, max_value=100, unit='%', sort_no=23,
  remark='抽核比例（复检模式=按比例抽核时生效）'
  WHERE param_key='WMS_RECHECK_RATIO';
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=24,
  remark='1=禁止收货员复检自己的收货单；0=允许'
  WHERE param_key='WMS_RECHECK_SELF_NG';
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='TEXT', sort_no=30,
  remark='上架策略规则，逗号分隔，按优先级排列（同品就近,ABC,属性,容量）'
  WHERE param_key='WMS_PUTAWAY_STRATEGY';
UPDATE sys_param_runtime SET param_group='WMS入库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=31,
  remark='1=上架员可修改系统推荐库位；0=必须放到推荐库位'
  WHERE param_key='WMS_PUTAWAY_FREE_BIN';

-- 出库参数（sort 10~99）
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='SELECT',
  option_json='[{"value":"0","label":"按单拣（摘果式）"},{"value":"1","label":"汇总拣+先拣后分（播种式）"},{"value":"2","label":"边拣边分（摘播混合）"},{"value":"3","label":"分区接力"},{"value":"4","label":"越库直发"}]',
  sort_no=10,
  remark='默认拣货模式；下放弹窗可本次修改。0=按单拣；1=汇总拣(先拣后分)；2=边拣边分；3=分区接力；4=越库直发'
  WHERE param_key='WMS_PICK_MODE';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=11,
  remark='1=任务按库区拆分，拣货员只管本库区；0=整单生成任务'
  WHERE param_key='WMS_PICK_ZONE_ENABLED';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='NUMBER',
  min_value=1, unit='件', sort_no=12,
  remark='单订单件数超过该值时自动拆成多个均衡子任务'
  WHERE param_key='WMS_PICK_SPLIT_THRESHOLD';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='NUMBER',
  min_value=1, max_value=10, unit='个', sort_no=13,
  remark='超量拆分的子任务上限'
  WHERE param_key='WMS_PICK_SPLIT_MAX';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='SELECT',
  option_json='[{"value":"0","label":"近效期先出（FEFO）"},{"value":"1","label":"先进先出（FIFO）"},{"value":"2","label":"指定批次"}]',
  sort_no=14,
  remark='批次分配策略。0=FEFO近效期先出；1=FIFO先进先出；2=指定批次（拣货时定）'
  WHERE param_key='WMS_BATCH_ALLOC_STRATEGY';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=15,
  remark='1=拣货缺货时可改拣其他可用批次并记录；0=必须按下放批次拣'
  WHERE param_key='WMS_PICK_BATCH_CHANGE_ALLOWED';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='SELECT',
  option_json='[{"value":"0","label":"按库位序号"},{"value":"1","label":"S形路径"}]',
  sort_no=16,
  remark='拣货路径规划策略'
  WHERE param_key='WMS_PICK_PATH_STRATEGY';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='SELECT',
  option_json='[{"value":"0","label":"自由领取"},{"value":"1","label":"主管指定"},{"value":"2","label":"两者皆可"}]',
  sort_no=20,
  remark='跨区支援模式。0=自由抢单；1=主管调派；2=两者皆可'
  WHERE param_key='WMS_CROSS_ZONE_MODE';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='SELECT',
  option_json='[{"value":"0","label":"一单一集货位"},{"value":"1","label":"一门店一集货位"},{"value":"2","label":"智能占用（小件拼位/大件多位）"}]',
  sort_no=30,
  remark='集货位占用模式'
  WHERE param_key='WMS_COLLECTION_MODE';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='SELECT',
  option_json='[{"value":"0","label":"按件数"},{"value":"1","label":"按体积"},{"value":"2","label":"按重量"}]',
  sort_no=31,
  remark='集货位占用依据维度'
  WHERE param_key='WMS_COLLECTION_BY';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='NUMBER',
  min_value=1, unit='', sort_no=32,
  remark='单集货位占用上限（按 WMS_COLLECTION_BY 维度）'
  WHERE param_key='WMS_COLLECTION_THRESHOLD';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=40,
  remark='1=拣货完成后进入复核；0=免复核，拣货完成即扣库存'
  WHERE param_key='WMS_CHECK_ENABLED';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='SELECT',
  option_json='[{"value":"0","label":"强制逐件"},{"value":"1","label":"抽核"},{"value":"2","label":"免复核"}]',
  sort_no=41,
  remark='复核模式。0=强制逐件；1=抽核；2=免复核'
  WHERE param_key='WMS_CHECK_MODE';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='NUMBER',
  min_value=0, max_value=100, unit='%', sort_no=42,
  remark='抽核模式下按订单抽核百分比，1~100'
  WHERE param_key='WMS_CHECK_RATIO';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='SELECT',
  option_json='[{"value":"0","label":"整位/整托核对"},{"value":"1","label":"拆零逐件扫"}]',
  sort_no=43,
  remark='复核粒度'
  WHERE param_key='WMS_CHECK_SCOPE';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='SELECT',
  option_json='[{"value":"0","label":"按订单集齐后复核"},{"value":"1","label":"按拣货单复核"}]',
  sort_no=44,
  remark='复核单元'
  WHERE param_key='WMS_CHECK_UNIT';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='NUMBER',
  min_value=0, unit='元', sort_no=45,
  remark='商品单价超过该值强制逐件复核，不参与抽核'
  WHERE param_key='WMS_CHECK_HIGH_VALUE_THRESHOLD';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=46,
  remark='1=少货可登记差异后通过；0=少货必须异常挂起/补货'
  WHERE param_key='WMS_CHECK_SHORT_ALLOWED';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='SELECT',
  option_json='[{"value":"0","label":"下放即派车"},{"value":"1","label":"拣货完成派车"},{"value":"2","label":"出库（扣账）后派车"}]',
  sort_no=50,
  remark='调度（派车）时机'
  WHERE param_key='WMS_DISPATCH_TIMING';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=60,
  remark='1=波次/订单可标记加急全链路置顶；0=不允许加急'
  WHERE param_key='WMS_EXPEDITE_ENABLED';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=61,
  remark='1=拣货缺货可一键发起加急补货任务；0=只能走常规补货'
  WHERE param_key='WMS_REPLENISH_URGENT_ENABLED';
UPDATE sys_param_runtime SET param_group='WMS出库', param_type='BOOL',
  option_json='[{"value":"1","label":"是"},{"value":"0","label":"否"}]',
  sort_no=70,
  remark='1=启用越库直发（收货后不入库直接分拣出库）；0=不启用'
  WHERE param_key='WMS_CROSS_DOCK_ENABLED';

-- ---------- 3. 新增入库 & 库内参数 ----------
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0155','WMS_ASN_APPOINT_ENABLED','是否启用到货预约','0','0','WMS入库','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',10,NULL,NULL,NULL,
  '1=采购到货需先预约才能收货；0=可直接收货'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_ASN_APPOINT_ENABLED');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0156','WMS_BLINDED_RECEIVE','收货盲收模式','0','0','WMS入库','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',11,NULL,NULL,NULL,
  '1=收货界面不显示应收数量（盲收）；0=显示应收数量'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_BLINDED_RECEIVE');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0157','WMS_OVER_RECEIVE_TOLERANCE','超收容差(%)','0','0','WMS入库','NUMBER',
  NULL,12,0,100,'%',
  '超收容差百分比，0=不允许超收'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_OVER_RECEIVE_TOLERANCE');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0158','WMS_RECEIVE_WITHOUT_ORDER','允许无单收货','1','1','WMS入库','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',13,NULL,NULL,NULL,
  '1=允许无采购订单收货（暂收区隔离）；0=必须有单收货'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_RECEIVE_WITHOUT_ORDER');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0159','WMS_PO_MULTI_RECEIVE','采购单允许多次收货','1','1','WMS入库','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',14,NULL,NULL,NULL,
  '1=一张采购订单可分多次收货；0=必须一次收完'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_PO_MULTI_RECEIVE');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0160','WMS_RECHECK_ENABLED','启用收货复检','0','0','WMS入库','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',20,NULL,NULL,NULL,
  '1=收货后需复检才能上架；0=收货后直接上架'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_RECHECK_ENABLED');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0161','WMS_RECHECK_MODE','收货复检模式','0','0','WMS入库','SELECT',
  '[{"value":"0","label":"全部复检"},{"value":"1","label":"按金额阈值"},{"value":"2","label":"按比例抽核"}]',21,NULL,NULL,NULL,
  '收货复检模式'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_RECHECK_MODE');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0162','WMS_RECHECK_AMOUNT_THRESHOLD','复检金额阈值（元）','5000','5000','WMS入库','NUMBER',
  NULL,22,0,NULL,'元',
  '超过该值的收货单需复检（复检模式=按金额阈值时生效）'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_RECHECK_AMOUNT_THRESHOLD');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0163','WMS_RECHECK_RATIO','收货抽核比例(%)','20','20','WMS入库','NUMBER',
  NULL,23,0,100,'%',
  '抽核比例（复检模式=按比例抽核时生效）'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_RECHECK_RATIO');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0164','WMS_RECHECK_SELF_NG','禁止收货员自复检','1','1','WMS入库','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',24,NULL,NULL,NULL,
  '1=禁止收货员复检自己的收货单（防串通）；0=允许'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_RECHECK_SELF_NG');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0165','WMS_PUTAWAY_STRATEGY','上架策略','同品就近,ABC,属性,容量','同品就近,ABC,属性,容量','WMS入库','TEXT',
  NULL,30,NULL,NULL,NULL,
  '上架策略规则，逗号分隔，按优先级排列'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_PUTAWAY_STRATEGY');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0166','WMS_PUTAWAY_FREE_BIN','允许修改上架库位','1','1','WMS入库','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',31,NULL,NULL,NULL,
  '1=上架员可修改系统推荐库位；0=必须放到推荐库位'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_PUTAWAY_FREE_BIN');

-- 基础/库内新增
INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0167','WMS_PICK_PATH_STRATEGY','拣货路径策略','0','0','WMS出库','SELECT',
  '[{"value":"0","label":"按库位序号"},{"value":"1","label":"S形路径"}]',16,NULL,NULL,NULL,
  '拣货路径规划策略'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_PICK_PATH_STRATEGY');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0168','WMS_REPLENISH_ACTIVE_ENABLED','启用主动补货','1','1','WMS基础参数','BOOL',
  '[{"value":"1","label":"启用"},{"value":"0","label":"关闭"}]',30,NULL,NULL,NULL,
  '1=定时扫描拣货位低于安全库存自动生成补货任务；0=仅手动/被动补货'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_REPLENISH_ACTIVE_ENABLED');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0169','WMS_REPLENISH_QTY_RULE','补货量规则','0','0','WMS基础参数','SELECT',
  '[{"value":"0","label":"补到容量上限"},{"value":"1","label":"补到N天销量"},{"value":"2","label":"固定补货量"}]',31,NULL,NULL,NULL,
  '主动补货的补货量计算规则'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_REPLENISH_QTY_RULE');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0170','WMS_COUNT_FREEZE_BIN','盘点冻结库位','1','1','WMS基础参数','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',40,NULL,NULL,NULL,
  '1=盘点时冻结被盘库位禁止拣货；0=不冻结'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_COUNT_FREEZE_BIN');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0171','WMS_COUNT_MODE_DEFAULT','盘点默认模式','0','0','WMS基础参数','SELECT',
  '[{"value":"0","label":"明盘（实时显示差异）"},{"value":"1","label":"暗盘（提交后才显示差异）"}]',41,NULL,NULL,NULL,
  '盘点默认模式'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_COUNT_MODE_DEFAULT');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0172','WMS_DAMAGE_NEED_PHOTO','报损必须拍照','1','1','WMS基础参数','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',50,NULL,NULL,NULL,
  '1=报损单必须上传现场照片才能提交；0=不强制'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_DAMAGE_NEED_PHOTO');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0173','WMS_EXPIRY_FREEZE','过期自动冻结','1','1','WMS基础参数','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',51,NULL,NULL,NULL,
  '1=过期批次自动冻结不可拣；0=不自动冻结'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_EXPIRY_FREEZE');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0174','WMS_CROSS_DOCK_ENABLED','启用越库直发','0','0','WMS出库','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',70,NULL,NULL,NULL,
  '1=启用越库直发（收货后不入库直接分拣出库）；0=不启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_CROSS_DOCK_ENABLED');

INSERT INTO sys_param_runtime (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0175','WMS_DISPATCH_DRIVER_REASSIGN_CONFIRM','未发车改派需扫码接收','1','1','WMS出库','BOOL',
  '[{"value":"1","label":"是"},{"value":"0","label":"否"}]',51,NULL,NULL,NULL,
  '1=未发车改派需新司机扫码接收确认；0=后台直接改派'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key='WMS_DISPATCH_DRIVER_REASSIGN_CONFIRM');

-- ---------- 4. 已有波次/拣货任务字符串枚举 → 数字（种子库无数据，保险） ----------
UPDATE wms_wave SET pick_mode = CASE pick_mode
  WHEN 'ORDER' THEN '0' WHEN 'SUMMARY' THEN '1' WHEN 'PICK_SORT' THEN '2'
  WHEN 'ZONE_RELAY' THEN '3' WHEN 'CROSS_DOCK' THEN '4' ELSE pick_mode END
  WHERE pick_mode IS NOT NULL AND pick_mode IN ('ORDER','SUMMARY','PICK_SORT','ZONE_RELAY','CROSS_DOCK');
UPDATE wms_wave SET batch_strategy = CASE batch_strategy
  WHEN 'FEFO' THEN '0' WHEN 'FIFO' THEN '1' WHEN 'SPECIFIED' THEN '2' ELSE batch_strategy END
  WHERE batch_strategy IS NOT NULL AND batch_strategy IN ('FEFO','FIFO','SPECIFIED');
UPDATE wms_pick_task SET pick_mode = CASE pick_mode
  WHEN 'ORDER' THEN '0' WHEN 'SUMMARY' THEN '1' WHEN 'PICK_SORT' THEN '2'
  WHEN 'ZONE_RELAY' THEN '3' WHEN 'CROSS_DOCK' THEN '4' ELSE pick_mode END
  WHERE pick_mode IS NOT NULL AND pick_mode IN ('ORDER','SUMMARY','PICK_SORT','ZONE_RELAY','CROSS_DOCK');
