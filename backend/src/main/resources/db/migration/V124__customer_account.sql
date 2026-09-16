-- ============================================
-- V124: 客户账户（PRD-35）
--
-- 1. fin_customer_account        客户账户主表（每客户一条：应收余额 + 预收余额）
-- 2. fin_customer_account_flow   客户账户流水（AR 应收 / ADVANCE 预收两本账，滚存余额）
-- 3. fin_advance_writeoff        预收核销单（预收冲应收，XH 单号；M3 启用，先建表）
-- 4. fin_advance_writeoff_detail 预收核销明细行（逐应收单行核销）
-- 5. fin_receipt_bill 加 receipt_type（SETTLE 应收结算/ADVANCE 预收收款/ADVANCE_REFUND 预收退款；M2 启用）
-- 6. 历史回填：账户行、AR 形成流水（fin_ar）、AR 结算流水（fin_reconcile_record），
--    窗口函数重排余额链；预收初始为 0，历史多收款不自动转预收（财务核对后走期初预收）。
--
-- 真值与缓存：fin_ar / fin_reconcile_record 是真值，账户余额与流水结算标志为缓存，
--            可由「数据修复」从真值完整重建。
-- 约定：VARCHAR 主键、DECIMAL(18,2)、IF NOT EXISTS 幂等、无外键。
-- ============================================

-- ========== 1. 客户账户主表 ==========
CREATE TABLE IF NOT EXISTS fin_customer_account (
    customer_code    VARCHAR(50)  PRIMARY KEY,
    customer_name    VARCHAR(200),
    salesman         VARCHAR(100),                 -- 业务员快照
    channel_type     VARCHAR(50),                  -- 渠道快照
    route_line       VARCHAR(100),                 -- 线路快照
    ar_balance       DECIMAL(18,2) NOT NULL DEFAULT 0,   -- 应收余额（缓存=Σ fin_ar.unreceived_amount）
    advance_balance  DECIMAL(18,2) NOT NULL DEFAULT 0,   -- 预收余额（缓存=Σ ADVANCE 流水带符号额）
    version          INT NOT NULL DEFAULT 0,       -- 乐观锁
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ========== 2. 客户账户流水 ==========
CREATE TABLE IF NOT EXISTS fin_customer_account_flow (
    flow_id          VARCHAR(32)  PRIMARY KEY,
    customer_code    VARCHAR(50)  NOT NULL,
    customer_name    VARCHAR(200),
    account_type     VARCHAR(10)  NOT NULL,        -- AR 应收 / ADVANCE 预收
    biz_type         VARCHAR(30)  NOT NULL,        -- 见 CustomerAccountConst 业务类型编码表
    increase_amount  DECIMAL(18,2) NOT NULL DEFAULT 0,
    decrease_amount  DECIMAL(18,2) NOT NULL DEFAULT 0,
    balance_after    DECIMAL(18,2),                -- 本笔后该账户滚存余额
    settle_status    VARCHAR(10),                  -- 未结算/部分结算/已结算（仅应收形成行）
    settled_amount   DECIMAL(18,2),                -- 该形成行累计已结（缓存，按 ar_no 回填）
    ar_no            VARCHAR(50),
    source_bill      VARCHAR(50),                  -- 来源业务单据号（发货/退货/费用/期初）
    order_no         VARCHAR(50),                  -- 要货单号（销售订单 YH）
    delivery_no      VARCHAR(50),                  -- 出库或退货单据号（DO/RT）
    receipt_no       VARCHAR(50),                  -- 收款单号（SK）
    writeoff_no      VARCHAR(50),                  -- 预收核销单号（XH）
    reconcile_id     VARCHAR(32),                  -- 关联核销记录
    post_date        DATE,                         -- 记账日期（参与封账/期间查询）
    occurred_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    summary          VARCHAR(500),
    operator_name    VARCHAR(100),
    is_red           CHAR(1) NOT NULL DEFAULT 'N',
    reverse_status   VARCHAR(10) NOT NULL DEFAULT 'NORMAL',  -- NORMAL / REVERSED
    reverse_flow_id  VARCHAR(32),
    biz_key          VARCHAR(120) NOT NULL,        -- 幂等键
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_caf_biz_key ON fin_customer_account_flow(biz_key);
CREATE INDEX IF NOT EXISTS idx_caf_customer ON fin_customer_account_flow(customer_code, account_type, occurred_at, flow_id);
CREATE INDEX IF NOT EXISTS idx_caf_ar ON fin_customer_account_flow(ar_no);
CREATE INDEX IF NOT EXISTS idx_caf_receipt ON fin_customer_account_flow(receipt_no);
CREATE INDEX IF NOT EXISTS idx_caf_writeoff ON fin_customer_account_flow(writeoff_no);
CREATE INDEX IF NOT EXISTS idx_caf_post_date ON fin_customer_account_flow(post_date);

-- ========== 3. 预收核销单（M3 启用） ==========
CREATE TABLE IF NOT EXISTS fin_advance_writeoff (
    writeoff_id      VARCHAR(32)  PRIMARY KEY,
    writeoff_no      VARCHAR(50)  NOT NULL UNIQUE,
    customer_code    VARCHAR(50)  NOT NULL,
    customer_name    VARCHAR(200),
    handler          VARCHAR(100),
    writeoff_date    DATE,
    total_amount     DECIMAL(18,2) NOT NULL DEFAULT 0,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / APPROVED / CANCELLED
    business_source  VARCHAR(30) DEFAULT 'MANUAL', -- MANUAL / AR_SETTLE / STATEMENT_SETTLE / TMS_SETTLE
    source_bill_no   VARCHAR(50),
    remark           VARCHAR(500),
    creator_name    VARCHAR(100),
    create_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    auditor_name     VARCHAR(100),
    audit_time       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_aw_customer ON fin_advance_writeoff(customer_code);
CREATE INDEX IF NOT EXISTS idx_aw_status ON fin_advance_writeoff(status);

CREATE TABLE IF NOT EXISTS fin_advance_writeoff_detail (
    id                   VARCHAR(32)  PRIMARY KEY,
    writeoff_id          VARCHAR(32)  NOT NULL,
    ar_no                VARCHAR(50) NOT NULL,
    source_bill          VARCHAR(50),
    bill_date            DATE,
    due_date             DATE,
    ar_amount            DECIMAL(18,2) NOT NULL DEFAULT 0,
    unsettled_before     DECIMAL(18,2) NOT NULL DEFAULT 0,
    writeoff_amount      DECIMAL(18,2) NOT NULL DEFAULT 0,
    settle_status_after  VARCHAR(10),
    remark               VARCHAR(200),
    sort_order           INT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_awd_writeoff ON fin_advance_writeoff_detail(writeoff_id);

-- ========== 4. 收款单增加收款类型（M2 启用，历史行全部 SETTLE） ==========
ALTER TABLE fin_receipt_bill ADD COLUMN IF NOT EXISTS receipt_type VARCHAR(20) DEFAULT 'SETTLE';

-- ========== 5. 回填客户账户行（先铺全量在册客户） ==========
MERGE INTO fin_customer_account (customer_code, customer_name, salesman, channel_type, route_line,
                                 ar_balance, advance_balance, version, created_at, updated_at)
KEY(customer_code)
SELECT customer_code, customer_name, salesman, channel_type, route_line, 0, 0, 0,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM base_customer
WHERE customer_code IS NOT NULL;

-- ========== 6. 回填账户流水 ==========
-- 客户归属 key 解析（fin_ar 只存客户名称）：发货签收→sales_receipt，退货→sales_return_apply，
-- 费用挂账→fin_expense_bill，再回落客户档案名称匹配，最后用名称本身兜底（历史脏数据可见但不参与新业务）。
INSERT INTO fin_customer_account_flow (
    flow_id, customer_code, customer_name, account_type, biz_type,
    increase_amount, decrease_amount, balance_after,
    settle_status, settled_amount, ar_no, source_bill, order_no, delivery_no,
    receipt_no, writeoff_no, reconcile_id, post_date, occurred_at,
    summary, operator_name, is_red, reverse_status, biz_key, created_at)
SELECT
    x.flow_id,
    x.customer_code, x.customer_name, x.account_type, x.biz_type,
    x.increase_amount, x.decrease_amount,
    SUM(x.change_amount) OVER (
        PARTITION BY x.customer_code, x.account_type
        ORDER BY x.occurred_at, x.type_order, x.flow_id
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW),
    x.settle_status, x.settled_amount, x.ar_no, x.source_bill, x.order_no, x.delivery_no,
    x.receipt_no, x.writeoff_no, x.reconcile_id, x.post_date, x.occurred_at,
    x.summary, NULL, x.is_red, 'NORMAL', x.biz_key, CURRENT_TIMESTAMP
FROM (
    -- 6.1 应收形成流水：一行 fin_ar 一行（含负向退货红字行）
    SELECT
        SUBSTRING(REPLACE(UUID(),'-',''),1,32) AS flow_id,
        COALESCE(
            (SELECT sr.customer_code FROM sales_receipt sr WHERE sr.receipt_no = a.source_bill),
            (SELECT ra.customer_code FROM sales_return_apply ra WHERE ra.apply_no = a.source_bill),
            (SELECT eb.counterparty_code FROM fin_expense_bill eb
                WHERE eb.expense_no = a.source_bill AND eb.counterparty_type = 'CUSTOMER'),
            (SELECT bc.customer_code FROM base_customer bc WHERE bc.customer_name = a.customer),
            a.customer) AS customer_code,
        a.customer AS customer_name,
        'AR' AS account_type,
        CASE
            WHEN a.source_bill LIKE 'QCAR-%' THEN 'AR_OPENING'
            WHEN EXISTS (SELECT 1 FROM sales_return_apply ra WHERE ra.apply_no = a.source_bill) THEN 'AR_RETURN'
            WHEN EXISTS (SELECT 1 FROM fin_expense_bill eb WHERE eb.expense_no = a.source_bill) THEN 'AR_EXPENSE'
            ELSE 'AR_SIGN'
        END AS biz_type,
        a.ar_amount AS increase_amount,
        CAST(0 AS DECIMAL(18,2)) AS decrease_amount,
        a.ar_amount AS change_amount,
        0 AS type_order,
        CASE WHEN a.unreceived_amount = 0 THEN '已结算'
             WHEN COALESCE(a.received_amount,0) = 0 THEN '未结算'
             ELSE '部分结算' END AS settle_status,
        COALESCE(a.received_amount,0) AS settled_amount,
        a.ar_no,
        a.source_bill,
        (SELECT sr.source_order_no FROM sales_receipt sr WHERE sr.receipt_no = a.source_bill) AS order_no,
        a.source_bill AS delivery_no,
        CAST(NULL AS VARCHAR(50)) AS receipt_no,
        CAST(NULL AS VARCHAR(50)) AS writeoff_no,
        CAST(NULL AS VARCHAR(32)) AS reconcile_id,
        COALESCE(CAST(a.created_at AS DATE), CURRENT_DATE) AS post_date,
        COALESCE(a.created_at, CURRENT_TIMESTAMP) AS occurred_at,
        CASE
            WHEN a.source_bill LIKE 'QCAR-%' THEN '期初应收 ' || a.ar_no
            WHEN EXISTS (SELECT 1 FROM sales_return_apply ra WHERE ra.apply_no = a.source_bill)
                THEN '销售退货红冲 ' || a.source_bill
            WHEN EXISTS (SELECT 1 FROM fin_expense_bill eb WHERE eb.expense_no = a.source_bill)
                THEN '费用挂账 ' || a.source_bill
            ELSE '订单出库签收 ' || a.source_bill
        END AS summary,
        CASE WHEN a.ar_amount < 0 THEN 'Y' ELSE 'N' END AS is_red,
        'AR:' || a.ar_no AS biz_key
    FROM fin_ar a

    UNION ALL

    -- 6.2 应收结算流水：核销记录中归属于应收单的每笔一行（抹零单独成类）
    SELECT
        SUBSTRING(REPLACE(UUID(),'-',''),1,32) AS flow_id,
        COALESCE(
            (SELECT COALESCE(
                (SELECT sr.customer_code FROM sales_receipt sr
                    WHERE sr.receipt_no = (SELECT a2.source_bill FROM fin_ar a2
                        WHERE a2.ar_no = COALESCE(r.ar_no, r.business_no))),
                (SELECT ra.customer_code FROM sales_return_apply ra
                    WHERE ra.apply_no = (SELECT a3.source_bill FROM fin_ar a3
                        WHERE a3.ar_no = COALESCE(r.ar_no, r.business_no))),
                (SELECT bc.customer_code FROM base_customer bc
                    WHERE bc.customer_name = (SELECT a4.customer FROM fin_ar a4
                        WHERE a4.ar_no = COALESCE(r.ar_no, r.business_no))),
                (SELECT a5.customer FROM fin_ar a5 WHERE a5.ar_no = COALESCE(r.ar_no, r.business_no)))
            ),
            r.counterparty_code, r.counterparty_name) AS customer_code,
        COALESCE(r.counterparty_name,
            (SELECT a6.customer FROM fin_ar a6 WHERE a6.ar_no = COALESCE(r.ar_no, r.business_no))) AS customer_name,
        'AR' AS account_type,
        CASE WHEN r.business_type = 'EXPENSE_WRITEOFF' THEN 'AR_WRITEOFF' ELSE 'AR_SETTLE_CASH' END AS biz_type,
        CAST(0 AS DECIMAL(18,2)) AS increase_amount,
        r.reconcile_amount AS decrease_amount,
        -r.reconcile_amount AS change_amount,
        1 AS type_order,
        CAST(NULL AS VARCHAR(10)) AS settle_status,
        CAST(NULL AS DECIMAL(18,2)) AS settled_amount,
        COALESCE(r.ar_no, r.business_no) AS ar_no,
        COALESCE(r.source_bill,
            (SELECT a7.source_bill FROM fin_ar a7 WHERE a7.ar_no = COALESCE(r.ar_no, r.business_no))) AS source_bill,
        (SELECT sr.source_order_no FROM sales_receipt sr
            WHERE sr.receipt_no = COALESCE(r.source_bill,
                (SELECT a8.source_bill FROM fin_ar a8 WHERE a8.ar_no = COALESCE(r.ar_no, r.business_no)))) AS order_no,
        COALESCE(r.source_bill,
            (SELECT a9.source_bill FROM fin_ar a9 WHERE a9.ar_no = COALESCE(r.ar_no, r.business_no))) AS delivery_no,
        r.receipt_no,
        CAST(NULL AS VARCHAR(50)) AS writeoff_no,
        r.record_id AS reconcile_id,
        COALESCE(r.receipt_date, CAST(r.created_at AS DATE)) AS post_date,
        COALESCE(r.created_at, CURRENT_TIMESTAMP) AS occurred_at,
        CASE WHEN r.business_type = 'EXPENSE_WRITEOFF' THEN '抹零核销 ' || r.receipt_no
             ELSE '收款结算 ' || r.receipt_no END AS summary,
        'N' AS is_red,
        'ARR:' || r.record_id AS biz_key
    FROM fin_reconcile_record r
    WHERE EXISTS (SELECT 1 FROM fin_ar a10 WHERE a10.ar_no = COALESCE(r.ar_no, r.business_no))
) x;

-- ========== 7. 流水里出现、但档案缺失的历史客户补账户行 ==========
MERGE INTO fin_customer_account (customer_code, customer_name, salesman, channel_type, route_line,
                                 ar_balance, advance_balance, version, created_at, updated_at)
KEY(customer_code)
SELECT DISTINCT f.customer_code,
       COALESCE((SELECT bc.customer_name FROM base_customer bc WHERE bc.customer_code = f.customer_code),
                f.customer_name),
       NULL, NULL, NULL, 0, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM fin_customer_account_flow f
WHERE NOT EXISTS (SELECT 1 FROM fin_customer_account a WHERE a.customer_code = f.customer_code);

-- ========== 8. 账户应收余额按真值 fin_ar 重算（key 解析口径同 6.1） ==========
UPDATE fin_customer_account aca SET ar_balance = COALESCE((
    SELECT SUM(a.unreceived_amount) FROM fin_ar a
    WHERE COALESCE(
            (SELECT sr.customer_code FROM sales_receipt sr WHERE sr.receipt_no = a.source_bill),
            (SELECT ra.customer_code FROM sales_return_apply ra WHERE ra.apply_no = a.source_bill),
            (SELECT eb.counterparty_code FROM fin_expense_bill eb
                WHERE eb.expense_no = a.source_bill AND eb.counterparty_type = 'CUSTOMER'),
            (SELECT bc.customer_code FROM base_customer bc WHERE bc.customer_name = a.customer),
            a.customer) = aca.customer_code
), 0),
updated_at = CURRENT_TIMESTAMP;

-- 档案快照（业务员/渠道/线路）刷新
UPDATE fin_customer_account aca SET
    salesman = (SELECT bc.salesman FROM base_customer bc WHERE bc.customer_code = aca.customer_code),
    channel_type = (SELECT bc.channel_type FROM base_customer bc WHERE bc.customer_code = aca.customer_code),
    route_line = (SELECT bc.route_line FROM base_customer bc WHERE bc.customer_code = aca.customer_code),
    customer_name = COALESCE((SELECT bc.customer_name FROM base_customer bc
                              WHERE bc.customer_code = aca.customer_code), aca.customer_name)
WHERE EXISTS (SELECT 1 FROM base_customer bc WHERE bc.customer_code = aca.customer_code);

-- advance_balance 预置 0（DEFAULT 已保证），历史多收款不自动转预收，走期初预收录入。
