-- ============================================================
-- V118 报表中心三期①：往来账龄/应收应付汇总共用的 DWD 视图 + 索引 + 参数
--
-- 设计动机（元数据复用）：
--   fin_ar / fin_ap 是往来台账，但「单据日期」分散在来源业务单上
--   （fin_ar 无业务日期列，fin_ap 连 created_at 都没有），
--   #20 应收账龄 / #21 应付账龄 / #23 客户应收汇总 / #24 供应商应付汇总
--   四张报表都需要同一套「立账日期 + 往来单位档案 + 核销截至日」口径。
--   不允许四套 SQL 各关联一遍来源单，因此统一沉淀为两个 DWD 视图：
--     v_rpt_ar_bill：应收单行（立账日=司机签收日/退货审核日，回退 created_at）
--     v_rpt_ap_bill：应付单行（立账日=采购收货审核日/采购退货审核日，
--                     飞单等无来源行回退 due_date−30，与写入口径一致）
--   核销（回款/付款）按 fin_reconcile_record 流水做「截至日」归集，
--   不依赖 fin_ar.received_amount 的当前值——账龄是时点报表。
--
-- 另含：
--   ① fin_ap(supplier,status)、fin_fund_ledger(fund_account,occurred_at) 索引
--     （fin_ar 同名索引已在 V117 建立）；
--   ② 参数 REPORT_SLOW_TURNOVER_DAYS：#7 商品周转率呆滞判定阈值（默认 60 天）。
--
-- 兼容：H2(MODE=MySQL) 与 MySQL 8 双跑；蛇形别名、单引号、限定 JOIN 列。
-- ============================================================

-- ① 应收 DWD：一笔 fin_ar 一行，补齐立账日期/客户档案/来源类型 ---------------
CREATE OR REPLACE VIEW v_rpt_ar_bill AS
SELECT a.ar_no,
       a.source_bill,
       a.customer,
       COALESCE(c.customer_code, '') AS customer_code,
       COALESCE(NULLIF(a.salesman, ''), c.salesman, '') AS salesman,
       COALESCE(c.territory, '') AS territory,
       COALESCE(c.customer_level, '') AS customer_level,
       COALESCE(c.route_line, '') AS route_line,
       COALESCE(c.credit_limit, 0) AS credit_limit,
       a.ar_amount,
       a.received_amount,
       a.unreceived_amount,
       a.due_date,
       a.invoice_status,
       a.reconcile_status,
       a.status,
       a.created_at,
       -- 立账日期：销售签收按司机签收时刻（无签收时刻回退审核时刻），
       -- 销售退货按退货审核时刻；飞单等其他来源回退 fin_ar.created_at。
       COALESCE(CAST(sr.sign_time AS DATE),
                CAST(sr.audit_time AS DATE),
                CAST(ra.audit_time AS DATE),
                CAST(a.created_at AS DATE)) AS bill_date,
       CASE WHEN sr.receipt_no IS NOT NULL THEN '销售签收'
            WHEN ra.apply_no IS NOT NULL THEN '销售退货'
            ELSE '其他' END AS bill_source_type
FROM fin_ar a
LEFT JOIN base_customer c ON c.customer_name = a.customer
LEFT JOIN sales_receipt sr ON sr.receipt_no = a.source_bill
LEFT JOIN sales_return_apply ra ON ra.apply_no = a.source_bill;

-- ② 应付 DWD：一笔 fin_ap 一行，补齐立账日期/供应商档案/来票信息 ---------------
CREATE OR REPLACE VIEW v_rpt_ap_bill AS
SELECT a.ap_no,
       a.source_bill,
       a.supplier,
       COALESCE(s.supplier_code, '') AS supplier_code,
       COALESCE(s.default_buyer, '') AS buyer,
       COALESCE(s.settlement_method, '') AS settlement_method,
       COALESCE(s.account_period_days, 0) AS account_period_days,
       a.ap_amount,
       a.paid_amount,
       a.unpaid_amount,
       a.due_date,
       a.invoiced_amount,
       a.invoice_status,
       a.reconcile_status,
       a.status,
       -- 立账日期：采购收货按审核时刻，采购退货按退货审核时刻，
       -- 飞单等无来源行回退到期日−30 天（所有应付写入时统一到期日=立账+30）。
       COALESCE(CAST(pr.audit_time AS DATE),
                pr.receipt_date,
                CAST(pt.audit_time AS DATE),
                DATEADD('DAY', -30, a.due_date)) AS bill_date,
       CASE WHEN pr.receipt_no IS NOT NULL THEN '采购收货'
            WHEN pt.return_no IS NOT NULL THEN '采购退货'
            ELSE '其他' END AS bill_source_type
FROM fin_ap a
LEFT JOIN base_supplier s ON s.supplier_name = a.supplier
LEFT JOIN pur_receipt pr ON pr.receipt_no = a.source_bill
LEFT JOIN pur_return pt ON pt.return_no = a.source_bill;

-- ③ 账龄/汇总高频查询索引 -----------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_fin_ap_supplier_status ON fin_ap(supplier, status);
CREATE INDEX IF NOT EXISTS idx_fin_fund_ledger_acct_time
    ON fin_fund_ledger(fund_account, occurred_at);

-- ④ 呆滞周转天数参数 -----------------------------------------------------------
INSERT INTO sys_param_runtime
  (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0183','REPORT_SLOW_TURNOVER_DAYS','呆滞品周转天数阈值','60','60','报表参数','NUMBER',NULL,183,1,3650,'天',
       '商品周转率分析（报表7）：周转天数大于该值或期间零周转但有库存的商品标记为呆滞品'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key = 'REPORT_SLOW_TURNOVER_DAYS');
