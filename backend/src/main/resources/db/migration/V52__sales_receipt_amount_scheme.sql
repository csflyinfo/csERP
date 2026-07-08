-- ============================================
-- V52: 销售发货单金额口径改版
--
-- 背景：
--   发货单原来在「出库审核生单」那一刻就把 goods_amount / tax_amount / final_amount 一次算死，
--   税走价外税（final = goods + tax + expense）。但客户可能拒收，签收数量才是真正能开票的数量，
--   所以金额必须分两个时点：发货时点定「发货金额」，签收时点才有「签收金额」和由它倒算的税。
--
-- 新口径（与采购收货单完全对齐：单价含税、税额价内倒算、结算取含税金额）：
--   deliver_amount  发货金额   = Σ 明细 qty × price          —— 出库单审核生成发货单时定死，之后不变
--   sign_amount     签收金额   = Σ 明细 signed_qty × price    —— 签收时汇总，生单默认 0
--   reject_amount   拒收金额   = Σ 明细 reject_qty × price    —— 签收时汇总，生单默认 0
--   tax_amount      税额       = 签收金额 × 税率 ÷ (1+税率)   —— 价内倒算，生单默认 0
--   untaxed_amount  不含税金额 = 签收金额 − 税额              —— 生单默认 0（原 final_amount 改名）
--   fin_ar.ar_amount 取【签收金额】（含税），与采购应付取含税 goods_amount 对称
--
--   expense_amount（费用金额）仍是 V1.0 占位 0，不再参与任何公式。
--
-- 内容：
--   1. sales_receipt：goods_amount → deliver_amount、final_amount → untaxed_amount，新增 sign_amount / reject_amount
--   2. sales_receipt_detail：新增 sign_amount / reject_amount
--   3. 历史数据回填
--
-- H2 兼容：ALTER COLUMN ... RENAME TO 是 H2 2.x 语法（本项目 H2 2.2.224）；新增列一律 IF NOT EXISTS；无 DROP COLUMN
--
-- 注意：sales_receipt.reject_qty（Σ 明细拒收数量）本次**不删列**，仍由签收逻辑写入，
--       只是不再对外返回（列表/详情改为展示「拒收金额」）。库里有用户手工建的真实数据，不做删列。
-- ============================================

-- ------------------------------------------------------------
-- 1. sales_receipt 主表
-- ------------------------------------------------------------
ALTER TABLE sales_receipt ALTER COLUMN goods_amount RENAME TO deliver_amount;
ALTER TABLE sales_receipt ALTER COLUMN final_amount RENAME TO untaxed_amount;

ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS sign_amount   DECIMAL(18, 2) DEFAULT 0;  -- 签收金额（含税）
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS reject_amount DECIMAL(18, 2) DEFAULT 0;  -- 拒收金额

-- ------------------------------------------------------------
-- 2. sales_receipt_detail 明细
--    amount 保持原义 = 行发货金额（qty × price），不动
-- ------------------------------------------------------------
ALTER TABLE sales_receipt_detail ADD COLUMN IF NOT EXISTS sign_amount   DECIMAL(18, 2) DEFAULT 0;
ALTER TABLE sales_receipt_detail ADD COLUMN IF NOT EXISTS reject_amount DECIMAL(18, 2) DEFAULT 0;

-- ------------------------------------------------------------
-- 3. 历史数据回填
--
--    deliver_amount 不用动：原 goods_amount 的值本来就是 Σ qty × price，
--    改的只是「这个数含不含税」的解释，数字本身不变。
--
--    用 ar_status 而不是 sign_status 判断：库里存在「未签收但已手工审核生成应收」的历史单据
--    （自动审核是上一轮才加的）。凡是已生成应收的，原 final_amount 就是那张 fin_ar 的金额，
--    也就是新口径下的签收金额。
--
--    已生成应收的历史单据保留原 tax_amount（改版前的价外税额）不重算 ——
--    重算会让账面税额与已经开出去的应收/发票对不上，历史就让它停在历史口径。
-- ------------------------------------------------------------
UPDATE sales_receipt SET sign_amount = COALESCE(untaxed_amount, 0)
 WHERE ar_status = '已生成';

UPDATE sales_receipt SET untaxed_amount = ROUND(COALESCE(sign_amount, 0) - COALESCE(tax_amount, 0), 2)
 WHERE ar_status = '已生成';

UPDATE sales_receipt SET sign_amount = 0, reject_amount = 0, tax_amount = 0, untaxed_amount = 0
 WHERE ar_status IS NULL OR ar_status <> '已生成';

-- 明细按已登记的签收/拒收数量回填金额（未签收的行 signed_qty/reject_qty 都是 0，自然得 0）
UPDATE sales_receipt_detail SET
    sign_amount   = ROUND(COALESCE(signed_qty, 0) * COALESCE(price, 0), 2),
    reject_amount = ROUND(COALESCE(reject_qty, 0) * COALESCE(price, 0), 2);

-- 未生成应收的单据，明细税额一并归零（税额只在签收时才产生）
UPDATE sales_receipt_detail SET tax_amount = 0
 WHERE receipt_id IN (SELECT receipt_id FROM sales_receipt
                      WHERE ar_status IS NULL OR ar_status <> '已生成');

-- 拒收金额汇总回主单（历史上有拒收登记的单据）
UPDATE sales_receipt r SET reject_amount = COALESCE(
        (SELECT SUM(d.reject_amount) FROM sales_receipt_detail d WHERE d.receipt_id = r.receipt_id), 0)
 WHERE r.ar_status = '已生成';
