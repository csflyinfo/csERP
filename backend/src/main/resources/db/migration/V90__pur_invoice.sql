-- V90: 采购发票管理（供应商来票登记与勾稽核销）  PRD-30
-- 注：初版曾占 V89，与并行分支 feat/wms-inbound-unify 的 V89__wms_inbound_unify 撞号
-- （该迁移已应用于本机 erp-v1），故顺延为 V90。
-- 票账分离：发票只做来票登记/勾稽/来票状态跟踪，不生成应付、不动库存成本
-- （应付在采购收货单审核时已按含税金额生成 fin_ap）。
-- 状态列统一中文值域，与 pur_receipt.pay_status/ap_status 风格一致。

-- 采购发票主表
CREATE TABLE pur_invoice (
    invoice_id      VARCHAR(40)  PRIMARY KEY,
    invoice_no      VARCHAR(32)  NOT NULL UNIQUE,        -- 系统单号 PINV...
    invoice_number  VARCHAR(32)  NOT NULL,               -- 发票号码
    invoice_code    VARCHAR(32),                         -- 发票代码（全电票可空）
    invoice_type    VARCHAR(20)  NOT NULL,               -- 增值税专用发票/增值税普通发票/电子发票/全电发票
    direction       VARCHAR(8)   NOT NULL DEFAULT '蓝字', -- 蓝字/红字（红字流程二期）
    supplier_code   VARCHAR(32)  NOT NULL,
    supplier_name   VARCHAR(128) NOT NULL,
    buyer_title     VARCHAR(128),                        -- 购买方抬头（我方）
    buyer_tax_no    VARCHAR(32),                         -- 购买方税号
    issue_date      DATE         NOT NULL,               -- 开票日期
    receive_date    DATE,                                -- 收票日期
    untaxed_amount  DECIMAL(18,4) NOT NULL DEFAULT 0,    -- 不含税金额
    tax_amount      DECIMAL(18,4) NOT NULL DEFAULT 0,    -- 税额
    total_amount    DECIMAL(18,4) NOT NULL DEFAULT 0,    -- 含税价税合计
    matched_amount  DECIMAL(18,4) NOT NULL DEFAULT 0,    -- 已勾稽含税金额
    match_status    VARCHAR(12) NOT NULL DEFAULT '未勾稽', -- 未勾稽/部分勾稽/已勾稽
    cert_status     VARCHAR(12) NOT NULL DEFAULT '未认证', -- 未认证/已认证/无需认证
    cert_date       DATE,
    status          VARCHAR(16) NOT NULL DEFAULT '草稿',  -- 草稿/已审核/已作废
    void_reason     VARCHAR(256),
    attachment_url  VARCHAR(256),
    attachment_name VARCHAR(128),
    remark          VARCHAR(256),
    creator_name    VARCHAR(64),
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    auditor_name    VARCHAR(64),
    audit_time      TIMESTAMP
);
-- 同供应商发票号码+红蓝方向唯一（作废票号仍占用，不可复用）
CREATE UNIQUE INDEX uk_pur_invoice_supplier_no ON pur_invoice(supplier_code, invoice_number, direction);

-- 采购发票商品明细（商品级来票统计数据源；勾稽选单时由收货单明细带入，可手工增改）
CREATE TABLE pur_invoice_line (
    line_id           VARCHAR(40) PRIMARY KEY,
    invoice_id        VARCHAR(40) NOT NULL,
    sort_order        INT DEFAULT 0,
    goods_code        VARCHAR(32) NOT NULL,
    goods_name        VARCHAR(128),
    spec              VARCHAR(128),
    unit_name         VARCHAR(32),
    qty               DECIMAL(18,4) NOT NULL DEFAULT 0,  -- 数量
    price             DECIMAL(18,4) NOT NULL DEFAULT 0,  -- 含税单价
    tax_rate          VARCHAR(16) NOT NULL DEFAULT '13%', -- 税率（沿用收货单口径字符串）
    untaxed_amount    DECIMAL(18,4) NOT NULL DEFAULT 0,
    tax_amount        DECIMAL(18,4) NOT NULL DEFAULT 0,
    amount            DECIMAL(18,4) NOT NULL DEFAULT 0,  -- 含税金额
    source_receipt_no VARCHAR(32)                        -- 来源收货单号（手工加行为空）
);
CREATE INDEX idx_pur_invoice_line_invoice ON pur_invoice_line(invoice_id);
CREATE INDEX idx_pur_invoice_line_goods   ON pur_invoice_line(goods_code);

-- 发票勾稽关系（一票多单：一发票多行；一单多票：一收货单跨发票多行）
CREATE TABLE pur_invoice_match (
    match_id        VARCHAR(40) PRIMARY KEY,
    invoice_id      VARCHAR(40) NOT NULL,
    invoice_no      VARCHAR(32) NOT NULL,
    bill_type       VARCHAR(16) NOT NULL DEFAULT '收货单', -- 收货单/退货单(二期)
    bill_no         VARCHAR(32) NOT NULL,                -- 采购收货单号
    bill_amount     DECIMAL(18,4) NOT NULL,              -- 单据含税金额(快照)
    matched_before  DECIMAL(18,4) NOT NULL DEFAULT 0,    -- 勾稽前该单已来票金额(快照)
    this_amount     DECIMAL(18,4) NOT NULL,              -- 本次勾稽含税金额
    this_tax_amount DECIMAL(18,4) NOT NULL DEFAULT 0,    -- 本次勾稽税额(价内税拆)
    settle_flag     SMALLINT NOT NULL DEFAULT 0,         -- 本单按此金额结清(1=结清，尾差置平记差异)
    diff_amount     DECIMAL(18,4) NOT NULL DEFAULT 0,    -- 勾稽尾差
    diff_reason     VARCHAR(256),
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_pim_invoice ON pur_invoice_match(invoice_id);
CREATE UNIQUE INDEX uk_pim_invoice_bill ON pur_invoice_match(invoice_id, bill_no);
CREATE INDEX idx_pim_bill ON pur_invoice_match(bill_no);

-- 收货单来票回写列
ALTER TABLE pur_receipt ADD COLUMN invoiced_amount DECIMAL(18,4) NOT NULL DEFAULT 0;
ALTER TABLE pur_receipt ADD COLUMN invoice_status  VARCHAR(12) NOT NULL DEFAULT '未来票'; -- 未来票/部分来票/已来票

-- 采购退货单来票回写列（二期红字勾稽启用，本期先建列）
ALTER TABLE pur_return ADD COLUMN invoiced_amount DECIMAL(18,4) NOT NULL DEFAULT 0;
ALTER TABLE pur_return ADD COLUMN invoice_status  VARCHAR(12) NOT NULL DEFAULT '未来票';

-- 应付来票状态（与 fin_ar.invoice_status 对称）
ALTER TABLE fin_ap ADD COLUMN invoiced_amount DECIMAL(18,4) NOT NULL DEFAULT 0;
ALTER TABLE fin_ap ADD COLUMN invoice_status  VARCHAR(12) NOT NULL DEFAULT '未来票';
