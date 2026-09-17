-- ============================================
-- V128: 供应商账户（PRD-36 M1）
--
-- 1. fin_supplier_account         供应商账户主表（每供应商一条：应付/预付/费用三余额）
-- 2. fin_supplier_account_flow    供应商账户流水（AP 应付 / PREPAY 预付 / EXPENSE 费用三本账）
-- 3. fin_factory_expense(+detail) 厂家费用单 JF（M3 启用，先建表）
-- 4. fin_factory_settle(+3 明细)  厂家费用兑现单 DX：现金/冲应付/其他核销（M3 启用）
-- 5. fin_prepay_writeoff(+detail) 预付核销单 FX（M2 启用，先建表）
-- 6. 既有表加列：付款单类型、应付期初预付列、费用类型厂家贷方科目、FE 回指、日结快照两列
-- 7. 历史回填：账户行、AP 形成流水（fin_ap）、AP 结算流水（fin_reconcile_record），
--    窗口函数重排余额链；预付/费用初始为 0（历史负应付不自动转预付，历史费用手工补录 JF）。
--
-- 真值与缓存：fin_ap / fin_reconcile_record 是 AP 真值，账户余额与流水为可重建缓存，
--            可由「数据修复」从真值完整重建。约定同 V124。
-- ============================================

-- ========== 1. 供应商账户主表 ==========
CREATE TABLE IF NOT EXISTS fin_supplier_account (
    supplier_code    VARCHAR(50)  PRIMARY KEY,
    supplier_name    VARCHAR(200),
    default_buyer    VARCHAR(100),                 -- 默认采购员快照
    settlement_method VARCHAR(50),                 -- 结算方式快照
    account_period_days INT,                       -- 账期天数快照
    ap_balance       DECIMAL(18,2) NOT NULL DEFAULT 0,   -- 应付余额（缓存=Σ fin_ap.unpaid_amount）
    prepay_balance   DECIMAL(18,2) NOT NULL DEFAULT 0,   -- 预付余额（缓存=Σ PREPAY 流水带符号额）
    expense_balance  DECIMAL(18,2) NOT NULL DEFAULT 0,   -- 费用余额（缓存=Σ 已审 JF 未兑现额）
    version          INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ========== 2. 供应商账户流水 ==========
CREATE TABLE IF NOT EXISTS fin_supplier_account_flow (
    flow_id          VARCHAR(32)  PRIMARY KEY,
    supplier_code    VARCHAR(50)  NOT NULL,
    supplier_name    VARCHAR(200),
    account_type     VARCHAR(10)  NOT NULL,        -- AP 应付 / PREPAY 预付 / EXPENSE 费用
    biz_type         VARCHAR(30)  NOT NULL,
    increase_amount  DECIMAL(18,2) NOT NULL DEFAULT 0,
    decrease_amount  DECIMAL(18,2) NOT NULL DEFAULT 0,
    balance_after    DECIMAL(18,2),
    settle_status    VARCHAR(10),                  -- 未结算/部分结算/已结算（AP 形成行）；待兑现/部分兑现/已兑现（EXPENSE 形成行）
    settled_amount   DECIMAL(18,2),
    ap_no            VARCHAR(50),
    factory_expense_no VARCHAR(50),
    source_bill      VARCHAR(50),                  -- 来源业务单据号（CGSH/CGTH/QCAP/JF…）
    payment_no       VARCHAR(50),                  -- 付款单号 FK / 兑现单号 DX
    writeoff_no      VARCHAR(50),                  -- 预付核销单号 FX
    reconcile_id     VARCHAR(32),
    receipt_no       VARCHAR(50),
    post_date        DATE,
    occurred_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    summary          VARCHAR(500),
    operator_name    VARCHAR(100),
    is_red           CHAR(1) NOT NULL DEFAULT 'N',
    reverse_status   VARCHAR(10) NOT NULL DEFAULT 'NORMAL',
    reverse_flow_id  VARCHAR(32),
    biz_key          VARCHAR(120) NOT NULL,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_saf_biz_key ON fin_supplier_account_flow(biz_key);
CREATE INDEX IF NOT EXISTS idx_saf_supplier ON fin_supplier_account_flow(supplier_code, account_type, occurred_at, flow_id);
CREATE INDEX IF NOT EXISTS idx_saf_ap ON fin_supplier_account_flow(ap_no);
CREATE INDEX IF NOT EXISTS idx_saf_fexp ON fin_supplier_account_flow(factory_expense_no);
CREATE INDEX IF NOT EXISTS idx_saf_payment ON fin_supplier_account_flow(payment_no);
CREATE INDEX IF NOT EXISTS idx_saf_writeoff ON fin_supplier_account_flow(writeoff_no);
CREATE INDEX IF NOT EXISTS idx_saf_post_date ON fin_supplier_account_flow(post_date);

-- ========== 3. 厂家费用单 JF ==========
CREATE TABLE IF NOT EXISTS fin_factory_expense (
    factory_expense_id   VARCHAR(32) PRIMARY KEY,
    factory_expense_no   VARCHAR(50) NOT NULL UNIQUE,
    supplier_code        VARCHAR(50) NOT NULL,
    supplier_name        VARCHAR(200),
    expense_date         DATE,
    claim_type           VARCHAR(20) NOT NULL DEFAULT 'ADVANCE',  -- ADVANCE 代垫 / OTHER 其他
    source_mode          VARCHAR(20) NOT NULL DEFAULT 'MANUAL',   -- CUSTOMER_EXPENSE / IMPORT / MANUAL
    total_amount         DECIMAL(18,2) NOT NULL DEFAULT 0,
    settled_amount       DECIMAL(18,2) NOT NULL DEFAULT 0,
    unsettled_amount     DECIMAL(18,2) NOT NULL DEFAULT 0,
    settle_status        VARCHAR(10),                              -- 待兑现/部分兑现/已兑现
    is_red               CHAR(1) NOT NULL DEFAULT 'N',
    red_source_no        VARCHAR(50),
    external_voucher_no  VARCHAR(100),                             -- 厂家确认单号/协议号/票据号
    handler              VARCHAR(100),
    department           VARCHAR(100),
    total_tax_amount         DECIMAL(18,2),                        -- 登记备查，V1 不参与凭证
    total_excluding_tax_amount DECIMAL(18,2),
    remark               VARCHAR(500),
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',   -- PENDING / APPROVED
    business_source      VARCHAR(30) NOT NULL DEFAULT 'BACKOFFICE',-- BACKOFFICE / OPENING_BACKFILL
    creator_name         VARCHAR(100),
    create_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    auditor_name         VARCHAR(100),
    audit_time           TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fe_supplier ON fin_factory_expense(supplier_code);
CREATE INDEX IF NOT EXISTS idx_fe_status ON fin_factory_expense(status);
CREATE INDEX IF NOT EXISTS idx_fe_settle_status ON fin_factory_expense(settle_status);

CREATE TABLE IF NOT EXISTS fin_factory_expense_detail (
    detail_id                    VARCHAR(32) PRIMARY KEY,
    factory_expense_id           VARCHAR(32) NOT NULL,
    expense_type_code            VARCHAR(50),
    expense_type_name            VARCHAR(100),
    customer_code                VARCHAR(50),
    customer_name                VARCHAR(200),
    customer_expense_no          VARCHAR(50),   -- 关联客户费用单号
    customer_expense_detail_id   VARCHAR(32),   -- 关联客户费用单明细行（行级占用）
    gl_credit_subject            VARCHAR(32),   -- 本行贷方科目
    qty                          DECIMAL(18,4),
    price                        DECIMAL(18,6),
    amount                       DECIMAL(18,2) NOT NULL DEFAULT 0,
    remark                       VARCHAR(255),
    sort_order                   INT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_fed_expense ON fin_factory_expense_detail(factory_expense_id);
CREATE INDEX IF NOT EXISTS idx_fed_cust_exp ON fin_factory_expense_detail(customer_expense_no);

-- ========== 4. 厂家费用兑现单 DX ==========
CREATE TABLE IF NOT EXISTS fin_factory_settle (
    settle_id            VARCHAR(32) PRIMARY KEY,
    settle_no            VARCHAR(50) NOT NULL UNIQUE,
    supplier_code        VARCHAR(50) NOT NULL,
    supplier_name        VARCHAR(200),
    settle_date          DATE,
    settle_type          VARCHAR(20) NOT NULL,   -- CASH / OFFSET / OTHER
    total_amount         DECIMAL(18,2) NOT NULL DEFAULT 0,
    contra_subject_code  VARCHAR(32),            -- OTHER：对方科目
    contra_subject_name  VARCHAR(100),
    related_bill_no      VARCHAR(50),            -- OTHER：关联入库单/审批单
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    handler              VARCHAR(100),
    remark               VARCHAR(500),
    business_source      VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    source_bill_no       VARCHAR(50),
    creator_name         VARCHAR(100),
    create_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    auditor_name         VARCHAR(100),
    audit_time           TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fs_supplier ON fin_factory_settle(supplier_code);
CREATE INDEX IF NOT EXISTS idx_fs_status ON fin_factory_settle(status);

-- (1) 费用核销明细（三种方式都有）
CREATE TABLE IF NOT EXISTS fin_factory_settle_detail (
    id                   VARCHAR(32) PRIMARY KEY,
    settle_id            VARCHAR(32) NOT NULL,
    factory_expense_no   VARCHAR(50) NOT NULL,
    expense_date         DATE,
    claim_type           VARCHAR(20),
    expense_amount       DECIMAL(18,2),
    settled_before       DECIMAL(18,2),
    settle_amount        DECIMAL(18,2) NOT NULL DEFAULT 0,
    settle_status_after  VARCHAR(10),
    sort_order           INT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_fsd_settle ON fin_factory_settle_detail(settle_id);
CREATE INDEX IF NOT EXISTS idx_fsd_fexp ON fin_factory_settle_detail(factory_expense_no);

-- (2) 冲应付明细（仅 OFFSET）
CREATE TABLE IF NOT EXISTS fin_factory_settle_ap (
    id                   VARCHAR(32) PRIMARY KEY,
    settle_id            VARCHAR(32) NOT NULL,
    ap_no                VARCHAR(50) NOT NULL,
    source_bill          VARCHAR(50),
    bill_date            DATE,
    due_date             DATE,
    ap_amount            DECIMAL(18,2),
    unpaid_before        DECIMAL(18,2),
    offset_amount        DECIMAL(18,2) NOT NULL DEFAULT 0,
    settle_status_after  VARCHAR(10),
    sort_order           INT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_fsa_settle ON fin_factory_settle_ap(settle_id);
CREATE INDEX IF NOT EXISTS idx_fsa_ap ON fin_factory_settle_ap(ap_no);

-- (3) 资金行明细（仅 CASH）
CREATE TABLE IF NOT EXISTS fin_factory_settle_fund (
    id                   VARCHAR(32) PRIMARY KEY,
    settle_id            VARCHAR(32) NOT NULL,
    fund_account         VARCHAR(100) NOT NULL,
    amount               DECIMAL(18,2) NOT NULL DEFAULT 0,
    remark               VARCHAR(255),
    sort_order           INT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_fsf_settle ON fin_factory_settle_fund(settle_id);

-- ========== 5. 预付核销单 FX（镜像 fin_advance_writeoff） ==========
CREATE TABLE IF NOT EXISTS fin_prepay_writeoff (
    writeoff_id      VARCHAR(32)  PRIMARY KEY,
    writeoff_no      VARCHAR(50)  NOT NULL UNIQUE,
    supplier_code    VARCHAR(50)  NOT NULL,
    supplier_name    VARCHAR(200),
    handler          VARCHAR(100),
    writeoff_date    DATE,
    total_amount     DECIMAL(18,2) NOT NULL DEFAULT 0,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / APPROVED / CANCELLED
    business_source  VARCHAR(30) DEFAULT 'MANUAL', -- MANUAL / AP_SETTLE / STATEMENT_SETTLE
    source_bill_no   VARCHAR(50),
    remark           VARCHAR(500),
    creator_name     VARCHAR(100),
    create_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    auditor_name     VARCHAR(100),
    audit_time       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pw_supplier ON fin_prepay_writeoff(supplier_code);
CREATE INDEX IF NOT EXISTS idx_pw_status ON fin_prepay_writeoff(status);

CREATE TABLE IF NOT EXISTS fin_prepay_writeoff_detail (
    id                   VARCHAR(32)  PRIMARY KEY,
    writeoff_id          VARCHAR(32)  NOT NULL,
    ap_no                VARCHAR(50) NOT NULL,
    source_bill          VARCHAR(50),
    bill_date            DATE,
    due_date             DATE,
    ap_amount            DECIMAL(18,2) NOT NULL DEFAULT 0,
    unsettled_before     DECIMAL(18,2) NOT NULL DEFAULT 0,
    writeoff_amount      DECIMAL(18,2) NOT NULL DEFAULT 0,
    settle_status_after  VARCHAR(10),
    remark               VARCHAR(200),
    sort_order           INT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_pwd_writeoff ON fin_prepay_writeoff_detail(writeoff_id);

-- ========== 6. 既有表加列（M2~M4 启用） ==========
-- 付款类型（历史行全部 SETTLE）
ALTER TABLE fin_payment_bill ADD COLUMN IF NOT EXISTS payment_type VARCHAR(20) DEFAULT 'SETTLE';

-- 预付期初并入应付期初暂存表
ALTER TABLE fin_ap_init ADD COLUMN IF NOT EXISTS prepay_amount DECIMAL(18,2) NOT NULL DEFAULT 0;
ALTER TABLE fin_ap_init ADD COLUMN IF NOT EXISTS generated_prepay_flow_id VARCHAR(32);

-- 费用类型档案：厂家费用（OTHER 类）默认贷方科目；代垫行不取此列
ALTER TABLE base_expense_type ADD COLUMN IF NOT EXISTS factory_gl_credit_subject_code VARCHAR(32);

-- 客户费用单回指 JF
ALTER TABLE fin_expense_bill ADD COLUMN IF NOT EXISTS factory_expense_no VARCHAR(50);

-- 日结应付快照两列
ALTER TABLE biz_close_ap_daily ADD COLUMN IF NOT EXISTS prepay_account_balance DECIMAL(18,2) NOT NULL DEFAULT 0;
ALTER TABLE biz_close_ap_daily ADD COLUMN IF NOT EXISTS expense_account_balance DECIMAL(18,2) NOT NULL DEFAULT 0;

-- ========== 7. 回填供应商账户行（先铺全量在册供应商） ==========
MERGE INTO fin_supplier_account (supplier_code, supplier_name, default_buyer, settlement_method,
                                 account_period_days, ap_balance, prepay_balance, expense_balance,
                                 version, created_at, updated_at)
KEY(supplier_code)
SELECT supplier_code, supplier_name, default_buyer, settlement_method, account_period_days,
       0, 0, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM base_supplier
WHERE supplier_code IS NOT NULL;

-- ========== 8. 回填 AP 账户流水 ==========
-- 供应商归属 key 解析（fin_ap 只存供应商名称）：采购收货→pur_receipt，采购退货→pur_return，
-- 期初应付→fin_ap_init.generated_ap_no，再回落档案名称匹配，最后用名称本身兜底。
INSERT INTO fin_supplier_account_flow (
    flow_id, supplier_code, supplier_name, account_type, biz_type,
    increase_amount, decrease_amount, balance_after,
    settle_status, settled_amount, ap_no, source_bill, payment_no, writeoff_no,
    reconcile_id, receipt_no, post_date, occurred_at,
    summary, operator_name, is_red, reverse_status, biz_key, created_at)
SELECT
    x.flow_id,
    x.supplier_code, x.supplier_name, x.account_type, x.biz_type,
    x.increase_amount, x.decrease_amount,
    SUM(x.change_amount) OVER (
        PARTITION BY x.supplier_code, x.account_type
        ORDER BY x.occurred_at, x.type_order, x.flow_id
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW),
    x.settle_status, x.settled_amount, x.ap_no, x.source_bill, x.payment_no, x.writeoff_no,
    x.reconcile_id, x.receipt_no, x.post_date, x.occurred_at,
    x.summary, NULL, x.is_red, 'NORMAL', x.biz_key, CURRENT_TIMESTAMP
FROM (
    -- 8.1 AP 形成流水：一行 fin_ap 一行（含负向采购退货红字行）
    SELECT
        SUBSTRING(REPLACE(UUID(),'-',''),1,32) AS flow_id,
        COALESCE(
            (SELECT pr.supplier_code FROM pur_receipt pr WHERE pr.receipt_no = a.source_bill),
            (SELECT pt.supplier_code FROM pur_return pt WHERE pt.return_no = a.source_bill),
            (SELECT ai.supplier_code FROM fin_ap_init ai WHERE ai.generated_ap_no = a.ap_no),
            (SELECT bs.supplier_code FROM base_supplier bs WHERE bs.supplier_name = a.supplier),
            a.supplier) AS supplier_code,
        a.supplier AS supplier_name,
        'AP' AS account_type,
        CASE
            WHEN a.source_bill LIKE 'QCAP-%' THEN 'AP_OPENING'
            WHEN EXISTS (SELECT 1 FROM pur_return pt WHERE pt.return_no = a.source_bill) THEN 'AP_RETURN'
            ELSE 'AP_RECEIPT'
        END AS biz_type,
        a.ap_amount AS increase_amount,
        CAST(0 AS DECIMAL(18,2)) AS decrease_amount,
        a.ap_amount AS change_amount,
        0 AS type_order,
        CASE WHEN a.unpaid_amount = 0 THEN '已结算'
             WHEN COALESCE(a.paid_amount,0) = 0 THEN '未结算'
             ELSE '部分结算' END AS settle_status,
        COALESCE(a.paid_amount,0) AS settled_amount,
        a.ap_no,
        a.source_bill,
        CAST(NULL AS VARCHAR(50)) AS payment_no,
        CAST(NULL AS VARCHAR(50)) AS writeoff_no,
        CAST(NULL AS VARCHAR(32)) AS reconcile_id,
        CAST(NULL AS VARCHAR(50)) AS receipt_no,
        COALESCE(CAST(COALESCE(
            (SELECT pr.audit_time FROM pur_receipt pr WHERE pr.receipt_no = a.source_bill),
            (SELECT pt.audit_time FROM pur_return pt WHERE pt.return_no = a.source_bill)) AS DATE),
            CAST(a.due_date AS DATE), CURRENT_DATE) AS post_date,
        COALESCE(
            (SELECT pr.audit_time FROM pur_receipt pr WHERE pr.receipt_no = a.source_bill),
            (SELECT pt.audit_time FROM pur_return pt WHERE pt.return_no = a.source_bill),
            CURRENT_TIMESTAMP) AS occurred_at,
        CASE
            WHEN a.source_bill LIKE 'QCAP-%' THEN '期初应付 ' || a.ap_no
            WHEN EXISTS (SELECT 1 FROM pur_return pt WHERE pt.return_no = a.source_bill)
                THEN '采购退货红冲 ' || a.source_bill
            ELSE '采购收货 ' || a.source_bill
        END AS summary,
        CASE WHEN a.ap_amount < 0 THEN 'Y' ELSE 'N' END AS is_red,
        'AP:' || a.ap_no AS biz_key
    FROM fin_ap a

    UNION ALL

    -- 8.2 AP 结算流水：核销记录中归属于应付单的每笔一行（费用类/抹零记录命中不到 fin_ap，自然排除）
    SELECT
        SUBSTRING(REPLACE(UUID(),'-',''),1,32) AS flow_id,
        COALESCE(
            (SELECT COALESCE(
                (SELECT pr.supplier_code FROM pur_receipt pr
                    WHERE pr.receipt_no = (SELECT a2.source_bill FROM fin_ap a2
                        WHERE a2.ap_no = COALESCE(r.ar_no, r.business_no))),
                (SELECT pt.supplier_code FROM pur_return pt
                    WHERE pt.return_no = (SELECT a3.source_bill FROM fin_ap a3
                        WHERE a3.ap_no = COALESCE(r.ar_no, r.business_no))),
                (SELECT ai.supplier_code FROM fin_ap_init ai
                    WHERE ai.generated_ap_no = (SELECT a4.ap_no FROM fin_ap a4
                        WHERE a4.ap_no = COALESCE(r.ar_no, r.business_no))),
                (SELECT bs.supplier_code FROM base_supplier bs
                    WHERE bs.supplier_name = (SELECT a5.supplier FROM fin_ap a5
                        WHERE a5.ap_no = COALESCE(r.ar_no, r.business_no))),
                (SELECT a5b.supplier FROM fin_ap a5b
                    WHERE a5b.ap_no = COALESCE(r.ar_no, r.business_no)))
            ),
            r.counterparty_code, r.counterparty_name) AS supplier_code,
        COALESCE(r.counterparty_name,
            (SELECT a6.supplier FROM fin_ap a6
                WHERE a6.ap_no = COALESCE(r.ar_no, r.business_no))) AS supplier_name,
        'AP' AS account_type,
        'AP_SETTLE_CASH' AS biz_type,
        CAST(0 AS DECIMAL(18,2)) AS increase_amount,
        r.reconcile_amount AS decrease_amount,
        -r.reconcile_amount AS change_amount,
        1 AS type_order,
        CAST(NULL AS VARCHAR(10)) AS settle_status,
        CAST(NULL AS DECIMAL(18,2)) AS settled_amount,
        COALESCE(r.ar_no, r.business_no) AS ap_no,
        COALESCE(r.source_bill,
            (SELECT a7.source_bill FROM fin_ap a7
                WHERE a7.ap_no = COALESCE(r.ar_no, r.business_no))) AS source_bill,
        r.receipt_no AS payment_no,
        CAST(NULL AS VARCHAR(50)) AS writeoff_no,
        r.record_id AS reconcile_id,
        r.receipt_no AS receipt_no,
        COALESCE(r.receipt_date, CAST(r.created_at AS DATE)) AS post_date,
        COALESCE(r.created_at, CURRENT_TIMESTAMP) AS occurred_at,
        '付款结算 ' || r.receipt_no AS summary,
        'N' AS is_red,
        'APR:' || r.record_id AS biz_key
    FROM fin_reconcile_record r
    WHERE r.counterparty_type = 'SUPPLIER'
      AND EXISTS (SELECT 1 FROM fin_ap a10
                  WHERE a10.ap_no = COALESCE(r.ar_no, r.business_no))
) x;

-- ========== 9. 流水里出现、但档案缺失的历史供应商补账户行 ==========
MERGE INTO fin_supplier_account (supplier_code, supplier_name, default_buyer, settlement_method,
                                 account_period_days, ap_balance, prepay_balance, expense_balance,
                                 version, created_at, updated_at)
KEY(supplier_code)
SELECT DISTINCT f.supplier_code,
       COALESCE((SELECT bs.supplier_name FROM base_supplier bs WHERE bs.supplier_code = f.supplier_code),
                f.supplier_name),
       NULL, NULL, NULL, 0, 0, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM fin_supplier_account_flow f
WHERE NOT EXISTS (SELECT 1 FROM fin_supplier_account a WHERE a.supplier_code = f.supplier_code);

-- ========== 10. 账户应付余额按真值 fin_ap 重算（key 解析口径同 8.1） ==========
UPDATE fin_supplier_account asa SET ap_balance = COALESCE((
    SELECT SUM(a.unpaid_amount) FROM fin_ap a
    WHERE COALESCE(
            (SELECT pr.supplier_code FROM pur_receipt pr WHERE pr.receipt_no = a.source_bill),
            (SELECT pt.supplier_code FROM pur_return pt WHERE pt.return_no = a.source_bill),
            (SELECT ai.supplier_code FROM fin_ap_init ai WHERE ai.generated_ap_no = a.ap_no),
            (SELECT bs.supplier_code FROM base_supplier bs WHERE bs.supplier_name = a.supplier),
            a.supplier) = asa.supplier_code
), 0),
updated_at = CURRENT_TIMESTAMP;

-- 档案快照刷新
UPDATE fin_supplier_account asa SET
    supplier_name = COALESCE((SELECT bs.supplier_name FROM base_supplier bs
                              WHERE bs.supplier_code = asa.supplier_code), asa.supplier_name),
    default_buyer = (SELECT bs.default_buyer FROM base_supplier bs WHERE bs.supplier_code = asa.supplier_code),
    settlement_method = (SELECT bs.settlement_method FROM base_supplier bs WHERE bs.supplier_code = asa.supplier_code),
    account_period_days = (SELECT bs.account_period_days FROM base_supplier bs WHERE bs.supplier_code = asa.supplier_code)
WHERE EXISTS (SELECT 1 FROM base_supplier bs WHERE bs.supplier_code = asa.supplier_code);

-- prepay_balance / expense_balance 预置 0（DEFAULT 已保证）：
-- 历史负应付不自动转预付（财务核对后走应付期初预付列/已上线库补录）；历史厂家费用手工补录 JF。
