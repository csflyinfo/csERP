-- ============================================================
-- V110 报表中心一期
-- 内容：
--   ① 商品维度快照 rpt_dim_goods（Java 任务全量刷新，解析 unit_config 大单位换算率）
--   ② DWD 口径视图 v_rpt_purchase_detail / v_rpt_sales_detail（含税、基本单位、退货负数）
--   ③ 采购域日汇总 rpt_dws_purchase_d（夜间 02:10 滚动重算 + 当天 30 分钟增量）
--   ④ 库存每日快照 inv_stock_daily_snapshot（Q6：测试/预发同样日结，不做近似回推）
--   ⑤ 商品采购预测防重 report_purchase_suggest_log（报表5）
--   ⑥ 报表查询审计 rpt_query_log（慢查询 >3s 落表，保留 90 天）
--   ⑦ 异步导出任务表 sys_export_task_runtime 扩展
--   ⑧ 报表高频过滤/关联索引
-- 兼容：H2(MODE=MySQL) 文件库与 MySQL 8 双跑；蛇形别名、单引号字面量；
--       幂等重刷一律 DELETE 分区 + INSERT...SELECT（同 wms_performance_daily 既有模式）。
-- ============================================================

-- ① 商品维度快照 ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS rpt_dim_goods (
  goods_code VARCHAR(50) PRIMARY KEY,
  goods_name VARCHAR(200),
  spec VARCHAR(200),
  brand_name VARCHAR(100),
  category_name VARCHAR(100),
  base_unit VARCHAR(50),
  large_unit VARCHAR(50),
  large_convert_qty DECIMAL(18, 4),
  default_supplier_code VARCHAR(50),
  default_supplier_name VARCHAR(200),
  default_warehouse VARCHAR(100),
  can_purchase BOOLEAN DEFAULT TRUE,
  status VARCHAR(20),
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ①b 往来单位统一维度（客户/供应商一套表，往来类 DWS/报表复用；名称展示仍实时 JOIN 档案）---
CREATE TABLE IF NOT EXISTS rpt_dim_partner (
  partner_type VARCHAR(10) NOT NULL,        -- CUSTOMER / SUPPLIER
  partner_code VARCHAR(50) NOT NULL,
  partner_name VARCHAR(200),
  default_owner VARCHAR(100),               -- 供应商默认采购员 / 客户默认业务员
  status VARCHAR(20),
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (partner_type, partner_code)
);
CREATE INDEX IF NOT EXISTS idx_rpt_dim_partner_name ON rpt_dim_partner(partner_name);

-- ② DWD 口径视图 -----------------------------------------------------------
-- 通用口径（K1~K8）：只取已审核单；金额为含税金额（退货取负）；
-- order_qty/order_unit 为录单单位数量，convert_qty 为该单位→基本单位换算率，
-- base_qty = order_qty × convert_qty（基本单位数量）；
-- package_qty 为件数（base_qty ÷ 大单位换算率，未配置大单位的商品为 NULL）；
-- unit_price_base = 含税金额 ÷ 基本单位数量（小单位单价）。
-- 换算率取值：优先该商品+该单位在订单明细中的历史换算率；
--            其次取 rpt_dim_goods 大单位换算率；查不到按 1（录单单位即基本单位）。
CREATE OR REPLACE VIEW v_rpt_purchase_detail AS
SELECT x.bill_type, x.bill_no, x.bill_date, x.source_bill_no,
       x.supplier_code, x.supplier_name, x.buyer, x.warehouse,
       x.goods_code, x.goods_name, x.order_unit, x.order_qty,
       x.factor AS convert_qty,
       x.order_qty * x.factor AS base_qty,
       CASE WHEN dg.large_unit IS NOT NULL AND COALESCE(dg.large_convert_qty, 0) > 0
            THEN x.order_qty * x.factor / dg.large_convert_qty
            ELSE x.order_qty * x.factor END AS package_qty,
       dg.large_unit AS large_unit,
       x.amount AS amount,
       CASE WHEN x.order_qty * x.factor = 0 THEN NULL
            ELSE x.amount / (x.order_qty * x.factor) END AS unit_price_base
FROM (
  SELECT '采购入库' AS bill_type, h.inbound_no AS bill_no, h.bill_date AS bill_date,
         h.source_order AS source_bill_no,
         COALESCE(sup.supplier_code, '') AS supplier_code,
         h.supplier AS supplier_name,
         COALESCE((SELECT po.buyer FROM purchase_order po
                    WHERE po.order_no = h.source_order LIMIT 1),
                  sup.default_buyer, '') AS buyer,
         COALESCE(d.warehouse, h.warehouse) AS warehouse,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, d.received_qty AS order_qty,
         COALESCE((SELECT MIN(pod.convert_qty) FROM purchase_order_detail pod
                    WHERE pod.goods_code = d.goods_code AND pod.unit_name = d.unit_name
                      AND COALESCE(pod.convert_qty, 0) > 0),
                  CASE WHEN d.unit_name = dg0.large_unit THEN dg0.large_convert_qty END,
                  1) AS factor,
         d.amount AS amount
  FROM pur_inbound h
  JOIN pur_inbound_detail d ON d.inbound_id = h.inbound_id
  LEFT JOIN base_supplier sup ON sup.supplier_name = h.supplier
  LEFT JOIN rpt_dim_goods dg0 ON dg0.goods_code = d.goods_code
  WHERE h.status = 'APPROVED'
  UNION ALL
  SELECT '采购退货' AS bill_type, h.return_no AS bill_no, h.return_date AS bill_date,
         h.source_apply_no AS source_bill_no,
         COALESCE(h.supplier_code, '') AS supplier_code,
         h.supplier_name AS supplier_name,
         COALESCE(sup.default_buyer, '') AS buyer,
         h.warehouse AS warehouse,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, -d.qty AS order_qty,
         COALESCE((SELECT MIN(pod.convert_qty) FROM purchase_order_detail pod
                    WHERE pod.goods_code = d.goods_code AND pod.unit_name = d.unit_name
                      AND COALESCE(pod.convert_qty, 0) > 0),
                  CASE WHEN d.unit_name = dg0.large_unit THEN dg0.large_convert_qty END,
                  1) AS factor,
         -(d.amount + COALESCE(d.tax_amount, 0)) AS amount
  FROM pur_return h
  JOIN pur_return_detail d ON d.return_id = h.return_id
  LEFT JOIN base_supplier sup ON sup.supplier_code = h.supplier_code
  LEFT JOIN rpt_dim_goods dg0 ON dg0.goods_code = d.goods_code
  WHERE h.status = 'APPROVED'
) x
LEFT JOIN rpt_dim_goods dg ON dg.goods_code = x.goods_code;

-- 销售口径（用户确认）：一律按司机签收——日期取 CAST(sign_time AS DATE)，
-- 数量取明细 signed_qty、金额取明细 sign_amount（含税）；销售退货为负。
-- 拒收行（reject_qty）不入本视图。
CREATE OR REPLACE VIEW v_rpt_sales_detail AS
SELECT x.bill_type, x.bill_no, x.bill_date, x.source_bill_no,
       x.customer_code, x.customer_name, x.salesman, x.warehouse,
       x.goods_code, x.goods_name, x.order_unit, x.order_qty,
       x.factor AS convert_qty,
       x.order_qty * x.factor AS base_qty,
       CASE WHEN dg.large_unit IS NOT NULL AND COALESCE(dg.large_convert_qty, 0) > 0
            THEN x.order_qty * x.factor / dg.large_convert_qty
            ELSE x.order_qty * x.factor END AS package_qty,
       dg.large_unit AS large_unit,
       x.amount AS amount,
       CASE WHEN x.order_qty * x.factor = 0 THEN NULL
            ELSE x.amount / (x.order_qty * x.factor) END AS unit_price_base
FROM (
  SELECT '销售签收' AS bill_type, h.receipt_no AS bill_no,
         CAST(h.sign_time AS DATE) AS bill_date,
         h.source_order_no AS source_bill_no,
         COALESCE(h.customer_code, '') AS customer_code,
         h.customer_name AS customer_name,
         COALESCE((SELECT so.salesman FROM sales_order so
                    WHERE so.order_no = h.source_order_no LIMIT 1), '') AS salesman,
         h.warehouse AS warehouse,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, d.signed_qty AS order_qty,
         COALESCE((SELECT MIN(sod.convert_qty) FROM sales_order_detail sod
                    WHERE sod.goods_code = d.goods_code AND sod.unit_name = d.unit_name
                      AND COALESCE(sod.convert_qty, 0) > 0),
                  CASE WHEN d.unit_name = dg0.large_unit THEN dg0.large_convert_qty END,
                  1) AS factor,
         d.sign_amount AS amount
  FROM sales_receipt h
  JOIN sales_receipt_detail d ON d.receipt_id = h.receipt_id
  LEFT JOIN rpt_dim_goods dg0 ON dg0.goods_code = d.goods_code
  WHERE h.status = 'APPROVED'
    AND h.sign_status IN ('已签收', '部分拒收')
    AND h.sign_time IS NOT NULL
    AND COALESCE(d.signed_qty, 0) > 0
  UNION ALL
  -- V30 起销售退货财务单并入 sales_return_inbound（WMS 收货入库链路），
  -- 审核且 stock_updated=TRUE 才代表退货已入账；金额为价内税含税金额（无独立税额列）。
  SELECT '销售退货' AS bill_type, h.inbound_no AS bill_no, h.bill_date AS bill_date,
         h.source_apply_no AS source_bill_no,
         COALESCE(h.customer_code, '') AS customer_code,
         h.customer_name AS customer_name,
         '' AS salesman, h.warehouse AS warehouse,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, -d.qty AS order_qty,
         COALESCE((SELECT MIN(sod.convert_qty) FROM sales_order_detail sod
                    WHERE sod.goods_code = d.goods_code AND sod.unit_name = d.unit_name
                      AND COALESCE(sod.convert_qty, 0) > 0),
                  CASE WHEN d.unit_name = dg0.large_unit THEN dg0.large_convert_qty END,
                  1) AS factor,
         -d.amount AS amount
  FROM sales_return_inbound h
  JOIN sales_return_inbound_detail d ON d.inbound_id = h.inbound_id
  LEFT JOIN rpt_dim_goods dg0 ON dg0.goods_code = d.goods_code
  WHERE h.status = 'APPROVED' AND h.stock_updated = TRUE
) x
LEFT JOIN rpt_dim_goods dg ON dg.goods_code = x.goods_code;

-- ③ 采购域日汇总（粒度：日期+供应商+采购员+商品+仓库）-----------------------
-- 页面 #3/#4 汇总查询走本表；名称仅快照，展示以编码实时 JOIN 档案为准。
CREATE TABLE IF NOT EXISTS rpt_dws_purchase_d (
  bill_date DATE NOT NULL,
  supplier_code VARCHAR(50) NOT NULL,
  supplier_name VARCHAR(200),
  buyer VARCHAR(100) NOT NULL,
  goods_code VARCHAR(50) NOT NULL,
  warehouse VARCHAR(100) NOT NULL,
  goods_name VARCHAR(200),
  brand_name VARCHAR(100),
  category_name VARCHAR(100),
  inbound_qty_base DECIMAL(18, 4) DEFAULT 0,
  inbound_package_qty DECIMAL(18, 4) DEFAULT 0,
  inbound_amount DECIMAL(18, 2) DEFAULT 0,
  return_qty_base DECIMAL(18, 4) DEFAULT 0,
  return_package_qty DECIMAL(18, 4) DEFAULT 0,
  return_amount DECIMAL(18, 2) DEFAULT 0,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (bill_date, supplier_code, buyer, goods_code, warehouse)
);
CREATE INDEX IF NOT EXISTS idx_rpt_dws_purchase_goods ON rpt_dws_purchase_d(goods_code, bill_date);
CREATE INDEX IF NOT EXISTS idx_rpt_dws_purchase_supplier ON rpt_dws_purchase_d(supplier_code, bill_date);

-- ④ 库存每日快照（Q6：测试/预发同样按日结，上线日起持续积累）----------------
CREATE TABLE IF NOT EXISTS inv_stock_daily_snapshot (
  snapshot_date DATE NOT NULL,
  goods_code VARCHAR(50) NOT NULL,
  warehouse VARCHAR(100) NOT NULL,
  physical_qty DECIMAL(18, 2) DEFAULT 0,
  locked_qty DECIMAL(18, 2) DEFAULT 0,
  frozen_qty DECIMAL(18, 2) DEFAULT 0,
  available_qty DECIMAL(18, 2) DEFAULT 0,
  cost_price DECIMAL(18, 6) DEFAULT 0,
  stock_amount DECIMAL(18, 2) DEFAULT 0,
  purchase_on_way DECIMAL(18, 2) DEFAULT 0,
  sales_qty_7d DECIMAL(18, 4) DEFAULT 0,
  sales_qty_30d DECIMAL(18, 4) DEFAULT 0,
  avg_daily_sales DECIMAL(18, 4) DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (snapshot_date, goods_code, warehouse)
);
CREATE INDEX IF NOT EXISTS idx_inv_snapshot_goods ON inv_stock_daily_snapshot(goods_code, snapshot_date);

-- ⑤ 商品采购预测防重记录（报表5）------------------------------------------
CREATE TABLE IF NOT EXISTS report_purchase_suggest_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_no VARCHAR(40) NOT NULL,
  goods_code VARCHAR(50) NOT NULL,
  goods_name VARCHAR(200),
  supplier_code VARCHAR(50),
  supplier_name VARCHAR(200),
  warehouse VARCHAR(100),
  qty DECIMAL(18, 4) DEFAULT 0,
  base_qty DECIMAL(18, 4) DEFAULT 0,
  source_params VARCHAR(2000),
  purchase_order_no VARCHAR(50),
  created_by VARCHAR(100),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rpt_suggest_batch ON report_purchase_suggest_log(batch_no);
CREATE INDEX IF NOT EXISTS idx_rpt_suggest_goods ON report_purchase_suggest_log(goods_code, purchase_order_no);

-- ⑥ 报表查询审计（保留 90 天，由定时任务清理）------------------------------
CREATE TABLE IF NOT EXISTS rpt_query_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  report_code VARCHAR(50) NOT NULL,
  param_hash VARCHAR(64),
  user_id VARCHAR(32),
  user_name VARCHAR(100),
  data_scope VARCHAR(500),
  cost_ms INT DEFAULT 0,
  row_count INT DEFAULT 0,
  slow BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rpt_query_log_report ON rpt_query_log(report_code, cost_ms);
CREATE INDEX IF NOT EXISTS idx_rpt_query_log_created ON rpt_query_log(created_at);

-- ⑦ 异步导出任务表扩展（sys_export_task_runtime 原有 CREATED/FINISHED 状态机）--
ALTER TABLE sys_export_task_runtime ADD COLUMN IF NOT EXISTS file_path VARCHAR(500);
ALTER TABLE sys_export_task_runtime ADD COLUMN IF NOT EXISTS total_rows INT DEFAULT 0;
ALTER TABLE sys_export_task_runtime ADD COLUMN IF NOT EXISTS error_msg VARCHAR(1000);
ALTER TABLE sys_export_task_runtime ADD COLUMN IF NOT EXISTS started_at TIMESTAMP;
ALTER TABLE sys_export_task_runtime ADD COLUMN IF NOT EXISTS expire_at TIMESTAMP;
ALTER TABLE sys_export_task_runtime ADD COLUMN IF NOT EXISTS created_by VARCHAR(32);
CREATE INDEX IF NOT EXISTS idx_export_task_status ON sys_export_task_runtime(status, created_at);

-- ⑧ 报表高频过滤/关联索引 ---------------------------------------------------
-- 采购入库：按日期+状态过滤、按来源订单回累计收货量（报表1）、明细按商品聚合（视图）
CREATE INDEX IF NOT EXISTS idx_pur_inbound_rpt_date ON pur_inbound(bill_date, status);
CREATE INDEX IF NOT EXISTS idx_pur_inbound_rpt_source ON pur_inbound(source_order, status);
CREATE INDEX IF NOT EXISTS idx_pur_inbound_detail_rpt_inbound ON pur_inbound_detail(inbound_id, goods_code);
CREATE INDEX IF NOT EXISTS idx_pur_inbound_detail_rpt_goods ON pur_inbound_detail(goods_code);
-- 采购退货明细：按头关联/商品聚合
CREATE INDEX IF NOT EXISTS idx_pur_return_detail_rpt_goods ON pur_return_detail(goods_code);
-- 销售签收：签收口径 leading 列必须是 sign_time（用户确认的统计口径）
CREATE INDEX IF NOT EXISTS idx_sales_receipt_rpt_sign ON sales_receipt(sign_time, status, sign_status);
CREATE INDEX IF NOT EXISTS idx_sales_receipt_detail_rpt_goods ON sales_receipt_detail(goods_code, receipt_id);
CREATE INDEX IF NOT EXISTS idx_sales_return_rpt_date ON sales_return_inbound(bill_date, status);
CREATE INDEX IF NOT EXISTS idx_sales_return_detail_rpt_goods ON sales_return_inbound_detail(goods_code);
-- 库存台账（报表8/9 期间过滤）
CREATE INDEX IF NOT EXISTS idx_inv_ledger_rpt_occurred ON inv_stock_ledger(occurred_at);
CREATE INDEX IF NOT EXISTS idx_inv_ledger_rpt_goods_wh ON inv_stock_ledger(goods_code, warehouse, occurred_at);
-- 采购订单（报表1：订单期间+到货状态聚合）
CREATE INDEX IF NOT EXISTS idx_purchase_order_rpt_status ON purchase_order(status, inbound_status, bill_date);
