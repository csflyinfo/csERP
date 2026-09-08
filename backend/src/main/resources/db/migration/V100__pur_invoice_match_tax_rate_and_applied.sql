-- V100: 采购发票勾稽增强（PRD-30 V1.3）
-- 1) pur_invoice_match_line 加税率：勾稽行税率默认从源收货单明细带入、允许修改（票面税率可能与收货不一致）；
--    勾稽金额（this_amount）允许手工填写，不再强制 = 数量×收货单价。
-- 2) pur_invoice_match 加 applied_amount：本发票对该收货单来票额的【实际】贡献（置平封顶/尾差上调后可能≠this_amount）。
--    审核后继续勾稽（/update-matches）按「当前来票额 − 本发票已贡献 + 本次勾稽额」重算目标来票额，
--    反审核/作废按 applied_amount 精确剥离本发票贡献，不再误伤其后勾稽的其他发票。
-- 约定：H2(MODE=MySQL)，不写外键约束。V92 编号留给并行分支（操作日志增强）。

ALTER TABLE pur_invoice_match_line ADD COLUMN tax_rate VARCHAR(20);

ALTER TABLE pur_invoice_match ADD COLUMN applied_amount DECIMAL(18, 2) DEFAULT 0;

-- 存量已审核数据回填：实际贡献 ≈ 本次勾稽额 − 置平溢出额（尾差上调场景误差 ≤1 元，可接受）
UPDATE pur_invoice_match SET applied_amount = this_amount - diff_amount
 WHERE applied_amount = 0 AND this_amount > 0;
