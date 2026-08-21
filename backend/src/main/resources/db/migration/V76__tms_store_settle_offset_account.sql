-- ============================================================
-- V76 门店结算冲抵账户（PRD-26 阶段 C / P0118 TMS_OFFSET_FUND_ACCOUNT）
--
-- 背景：PRD-25 的合并结算只算净额（settle = receipt - return），
-- 既不产生冲抵流水，也没有记录「冲抵部分记到哪个账户」。
-- 阶段 C 要落地 PRD-26 §2.3 的职责切分：
--   - 司机实收现金/转账 -> tms_driver_fund_account（按司机配，因人而异）
--   - 销退合并结算的冲抵部分 -> TMS_OFFSET_FUND_ACCOUNT（全局唯一）
-- 冲抵是账面对冲、不涉及真实资金收付，所以必须与司机账户彻底隔离。
--
-- 本迁移做三件事：
--   1. 建「销退冲抵过渡户」末级账户（PRD §2.3 落地要求 ①）
--   2. 把参数默认指向该账户（落地要求 ②），且不覆盖运营已配的值
--   3. tms_store_settlement 增加冲抵账户与冲抵金额三列，
--      供交账审核时按结算单原样回放一正一负流水
-- ============================================================

-- ---------- 1. 销退冲抵过渡户 ----------
-- 挂在一级分类「06 其他」下：冲抵不是现金也不是三方收款，归入其他最贴切。
-- is_system=TRUE 防止财务在基础资料页误删。
-- 注意：建了 '0601' 之后 '06' 就不再是末级，
-- SystemController.offsetAccountOptions() 的末级过滤会自动把 '06' 从可选项剔除、
-- 把 '0601' 纳入，无需额外配置。
INSERT INTO base_fund_account (fund_account_id, fund_account_code, fund_account_name, account_type, balance, remark, status, parent_code, is_system)
VALUES ('FA_SYS_0601', '0601', '销退冲抵过渡户', '', 0, '系统默认：送货与退货合并结算的账面冲抵专用户，一正一负净额为0，不得指向司机收款账户', 'NORMAL', '06', TRUE)
ON DUPLICATE KEY UPDATE fund_account_name = VALUES(fund_account_name), remark = VALUES(remark), is_system = VALUES(is_system);

-- ---------- 2. 参数指向该账户 ----------
-- 只在参数尚未配置时写入：运营若已改指其他账户，重跑迁移不能冲掉。
-- default_value 保持空串不动 —— 默认值是查库失败时的回落口径，
-- 不能把具体账户编码写死进兜底逻辑。
UPDATE sys_param_runtime
SET param_value = '0601'
WHERE param_key = 'TMS_OFFSET_FUND_ACCOUNT'
  AND (param_value IS NULL OR param_value = '');

-- ---------- 3. 结算单冲抵字段 ----------
-- 冲抵账户在结算时（司机提交）就固化到结算单上，审核时不再重读参数：
-- 参数是可变的全局配置，若审核时才读，运营中途改了账户会让历史单据流水错账户。
-- offset_amount 存正数，表示本单被对冲掉的金额，等于 MIN(发货金额, 退货金额)。
ALTER TABLE tms_store_settlement ADD COLUMN IF NOT EXISTS offset_account_code VARCHAR(50);
ALTER TABLE tms_store_settlement ADD COLUMN IF NOT EXISTS offset_account_name VARCHAR(100);
ALTER TABLE tms_store_settlement ADD COLUMN IF NOT EXISTS offset_amount DECIMAL(18,2) DEFAULT 0;

-- 存量结算单不回填：历史单据当时未产生冲抵流水，
-- 补写账户编码会让审核逻辑误以为需要补记流水，造成重复入账。
