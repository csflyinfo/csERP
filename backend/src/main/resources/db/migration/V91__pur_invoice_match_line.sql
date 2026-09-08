-- V91: 采购发票勾稽改为商品行级（按数量勾稽未开票商品）  PRD-30 改版
-- 背景：初版（V90）按收货单整单金额勾稽，操作复杂；业务实际按「哪批货、多少数量来了票」勾对。
-- 本表记录发票勾稽的商品明细行（发票 ↔ 收货单商品行，多对多），
-- pur_invoice_match（单聚合行）仍保留，审核时由本表聚合生成/重算，回写逻辑不变。
-- 约定：H2(MODE=MySQL)，不写外键约束。

CREATE TABLE pur_invoice_match_line (
    id              VARCHAR(40) PRIMARY KEY,
    invoice_id      VARCHAR(40) NOT NULL,
    receipt_no      VARCHAR(32) NOT NULL,        -- 收货单号（来票状态回写对象）
    inbound_no      VARCHAR(32),                 -- 入库单号（pur_receipt.source_inbound_no）
    order_no        VARCHAR(32),                 -- 进货单号（采购订单号 source_order_no）
    goods_code      VARCHAR(32) NOT NULL,
    goods_name      VARCHAR(128),
    spec            VARCHAR(128),
    unit_name       VARCHAR(32),
    qty             DECIMAL(18,4) NOT NULL DEFAULT 0,  -- 收货数量（快照，含税口径同收货明细）
    price           DECIMAL(18,4) NOT NULL DEFAULT 0,  -- 含税单价（快照）
    amount          DECIMAL(18,4) NOT NULL DEFAULT 0,  -- 收货行含税金额（快照 = qty*price）
    this_qty        DECIMAL(18,4) NOT NULL DEFAULT 0,  -- 本次开票数量
    this_amount     DECIMAL(18,4) NOT NULL DEFAULT 0,  -- 本次开票含税金额 = this_qty*price
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_piml_invoice ON pur_invoice_match_line(invoice_id);
CREATE INDEX idx_piml_receipt_goods ON pur_invoice_match_line(receipt_no, goods_code);
