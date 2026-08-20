-- ------------------------------------------------------------
-- V72 回填 tms_dispatch_detail.amount
--
-- 背景：
--   TmsDispatchController.insertDetail 与 TmsReturnDispatchController.assign 的
--   INSERT 列清单里都漏了 amount，导致自建表以来所有配送明细的金额恒为 0。
--   表现是司机交账页「应收总金额」永远显示 ¥0.00（tms_dispatch 主表 amount 有值，
--   但交账 summary 是按明细 SUM 的），而门店数、件数都正常，所以一直没被发现。
--
-- 两个 Controller 已修复，本脚本只负责把历史数据补齐：
--   RECEIPT 行 → sales_receipt.deliver_amount（与派单时的取值口径一致）
--   RETURN  行 → sales_return_apply.return_amount，正数存放
--                （负向语义由 bill_type='RETURN' 表达，存正数是为了让
--                 SUM(amount) WHERE bill_type='RETURN' 直接可用）
--
-- 只补 amount 为 0 或 NULL 的行，已有金额的不动，避免覆盖人工调整过的数据。
-- ------------------------------------------------------------

UPDATE tms_dispatch_detail d
   SET amount = COALESCE((SELECT r.deliver_amount FROM sales_receipt r
                           WHERE r.receipt_no = d.source_bill_no), 0)
 WHERE d.bill_type = 'RECEIPT'
   AND (d.amount IS NULL OR d.amount = 0);

UPDATE tms_dispatch_detail d
   SET amount = COALESCE((SELECT COALESCE(a.return_amount, a.amount, 0) FROM sales_return_apply a
                           WHERE a.apply_no = d.source_bill_no), 0)
 WHERE d.bill_type = 'RETURN'
   AND (d.amount IS NULL OR d.amount = 0);
