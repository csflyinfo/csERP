-- =====================================================================
-- 报表中心一期性能修复②：单位换算率物理维度表
--
-- V112 把换算率相关子查询改成派生表聚合后，实测发现：派生表无法接收外层
-- 日期谓词，即使只查一天也会对全量订单明细（规模库 100 万行）做一趟 GROUP BY，
-- H2 文件库上仍超 30s；MySQL 上也是每查询重复付出同构成本。
-- 换算率（商品+单位 -> MIN(convert_qty)）是档案型数据，随维度快照维护即可，
-- 故物化为 rpt_dim_unit_factor，由 ReportDimGoodsService 每 30 分钟/夜间重建，
-- DWD 视图直接按主键 JOIN，单次查询不再做百万行聚合。
-- =====================================================================

CREATE TABLE IF NOT EXISTS rpt_dim_unit_factor (
  biz_type   VARCHAR(1) NOT NULL,           -- P=采购订单 / S=销售订单
  goods_code VARCHAR(50) NOT NULL,
  unit_name  VARCHAR(50) NOT NULL,
  factor_qty DECIMAL(18, 6) NOT NULL,      -- 该单位 -> 基本单位换算率（取历史 MIN）
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (biz_type, goods_code, unit_name)
);

-- 首次填充（后续由维度刷新任务 DELETE+INSERT 幂等维护）
INSERT INTO rpt_dim_unit_factor(biz_type, goods_code, unit_name, factor_qty, updated_at)
SELECT 'P', goods_code, unit_name, MIN(convert_qty), CURRENT_TIMESTAMP
FROM purchase_order_detail
WHERE COALESCE(convert_qty, 0) > 0 AND unit_name IS NOT NULL
GROUP BY goods_code, unit_name;

INSERT INTO rpt_dim_unit_factor(biz_type, goods_code, unit_name, factor_qty, updated_at)
SELECT 'S', goods_code, unit_name, MIN(convert_qty), CURRENT_TIMESTAMP
FROM sales_order_detail
WHERE COALESCE(convert_qty, 0) > 0 AND unit_name IS NOT NULL
GROUP BY goods_code, unit_name;

CREATE INDEX IF NOT EXISTS idx_dim_unit_factor_lookup
    ON rpt_dim_unit_factor(biz_type, goods_code, unit_name);

-- DWD 视图改用物理维度（列与口径不变）
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
         COALESCE(po.buyer, sup.default_buyer, '') AS buyer,
         COALESCE(d.warehouse, h.warehouse) AS warehouse,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, d.received_qty AS order_qty,
         COALESCE(pfm.factor_qty,
                  CASE WHEN d.unit_name = dg0.large_unit THEN dg0.large_convert_qty END,
                  1) AS factor,
         d.amount AS amount
  FROM pur_inbound h
  JOIN pur_inbound_detail d ON d.inbound_id = h.inbound_id
  LEFT JOIN base_supplier sup ON sup.supplier_name = h.supplier
  LEFT JOIN purchase_order po ON po.order_no = h.source_order
  LEFT JOIN rpt_dim_goods dg0 ON dg0.goods_code = d.goods_code
  LEFT JOIN rpt_dim_unit_factor pfm
         ON pfm.biz_type = 'P' AND pfm.goods_code = d.goods_code AND pfm.unit_name = d.unit_name
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
         COALESCE(pfm.factor_qty,
                  CASE WHEN d.unit_name = dg0.large_unit THEN dg0.large_convert_qty END,
                  1) AS factor,
         -(d.amount + COALESCE(d.tax_amount, 0)) AS amount
  FROM pur_return h
  JOIN pur_return_detail d ON d.return_id = h.return_id
  LEFT JOIN base_supplier sup ON sup.supplier_code = h.supplier_code
  LEFT JOIN rpt_dim_goods dg0 ON dg0.goods_code = d.goods_code
  LEFT JOIN rpt_dim_unit_factor pfm
         ON pfm.biz_type = 'P' AND pfm.goods_code = d.goods_code AND pfm.unit_name = d.unit_name
  WHERE h.status = 'APPROVED'
) x
LEFT JOIN rpt_dim_goods dg ON dg.goods_code = x.goods_code;

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
         COALESCE(so.salesman, '') AS salesman,
         h.warehouse AS warehouse,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, d.signed_qty AS order_qty,
         COALESCE(sfm.factor_qty,
                  CASE WHEN d.unit_name = dg0.large_unit THEN dg0.large_convert_qty END,
                  1) AS factor,
         d.sign_amount AS amount
  FROM sales_receipt h
  JOIN sales_receipt_detail d ON d.receipt_id = h.receipt_id
  LEFT JOIN sales_order so ON so.order_no = h.source_order_no
  LEFT JOIN rpt_dim_goods dg0 ON dg0.goods_code = d.goods_code
  LEFT JOIN rpt_dim_unit_factor sfm
         ON sfm.biz_type = 'S' AND sfm.goods_code = d.goods_code AND sfm.unit_name = d.unit_name
  WHERE h.status = 'APPROVED'
    AND h.sign_status IN ('已签收', '部分拒收')
    AND h.sign_time IS NOT NULL
    AND COALESCE(d.signed_qty, 0) > 0
  UNION ALL
  SELECT '销售退货' AS bill_type, h.inbound_no AS bill_no, h.bill_date AS bill_date,
         h.source_apply_no AS source_bill_no,
         COALESCE(h.customer_code, '') AS customer_code,
         h.customer_name AS customer_name,
         '' AS salesman, h.warehouse AS warehouse,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, -d.qty AS order_qty,
         COALESCE(sfm.factor_qty,
                  CASE WHEN d.unit_name = dg0.large_unit THEN dg0.large_convert_qty END,
                  1) AS factor,
         -d.amount AS amount
  FROM sales_return_inbound h
  JOIN sales_return_inbound_detail d ON d.inbound_id = h.inbound_id
  LEFT JOIN rpt_dim_goods dg0 ON dg0.goods_code = d.goods_code
  LEFT JOIN rpt_dim_unit_factor sfm
         ON sfm.biz_type = 'S' AND sfm.goods_code = d.goods_code AND sfm.unit_name = d.unit_name
  WHERE h.status = 'APPROVED' AND h.stock_updated = TRUE
) x
LEFT JOIN rpt_dim_goods dg ON dg.goods_code = x.goods_code;
