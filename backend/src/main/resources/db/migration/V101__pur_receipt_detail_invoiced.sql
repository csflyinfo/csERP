-- V101: 收货单商品行级来票回写（PRD-30 V1.4）
-- 背景：V90 只把已来票金额回写到收货单头（pur_receipt.invoiced_amount），
-- 用户要求「勾稽后的已勾稽金额也要更新给源单据」，即在收货单商品行上看到
-- 每个商品的已开票数量/金额；勾稽选单引入时按行带出剩余未开票金额。
-- 口径：仅统计【已审核】发票的勾稽商品行（与单据级 invoiced_amount 一致），
-- 草稿占量不回写、作废不计。由 PurchaseInvoiceController 在
-- 审核 / 审核后改勾稽 / 反审核 / 作废后按收货单全量重算。
-- 约定：H2(MODE=MySQL)，标量子查询更新；V92 编号留给并行分支（操作日志增强）。

ALTER TABLE pur_receipt_detail ADD COLUMN invoiced_qty DECIMAL(18, 4) DEFAULT 0;
ALTER TABLE pur_receipt_detail ADD COLUMN invoiced_amount DECIMAL(18, 2) DEFAULT 0;

-- 存量已审核勾稽数据回填（按 收货单+商品 汇总）
UPDATE pur_receipt_detail d SET invoiced_qty = (
    SELECT COALESCE(SUM(ml.this_qty), 0)
    FROM pur_invoice_match_line ml
    JOIN pur_invoice i ON i.invoice_id = ml.invoice_id
    JOIN pur_receipt r ON r.receipt_no = ml.receipt_no
    WHERE r.receipt_id = d.receipt_id
      AND ml.goods_code = d.goods_code
      AND i.status = '已审核'
);
UPDATE pur_receipt_detail d SET invoiced_amount = (
    SELECT COALESCE(SUM(ml.this_amount), 0)
    FROM pur_invoice_match_line ml
    JOIN pur_invoice i ON i.invoice_id = ml.invoice_id
    JOIN pur_receipt r ON r.receipt_no = ml.receipt_no
    WHERE r.receipt_id = d.receipt_id
      AND ml.goods_code = d.goods_code
      AND i.status = '已审核'
);
