-- =====================================================================
-- 报表中心一期性能修复：DWD 视图去相关子查询
--
-- 规模实测（日 2,000 单 × 50 行 = 10 万明细行）发现：V110 视图对每个明细行
-- 用相关子查询反查采购员与换算率（SELECT MIN(convert_qty) ... ），
-- H2 文件库上单日明细查询即超 30s 语句超时；该写法在 MySQL 上随数据增长同样恶化。
--
-- 改法（语义等价）：
--   1. 换算率改为派生表一次聚合（商品+单位 -> MIN(convert_qty)）后 JOIN，
--      配合 V111 覆盖索引纯索引扫描，全查询只聚合一趟；
--   2. 采购员由相关子查询改为订单表 LEFT JOIN（order_no 唯一索引）。
-- 视图列名/口径（含税、基本单位、退货负数、件数换算）完全不变，
-- 已构建的 rpt_dws_purchase_d 无需重算。
-- H2 2.2 / MySQL 8 均支持视图内 UNION 分支引用派生表。
-- =====================================================================

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
  LEFT JOIN (
      SELECT goods_code, unit_name, MIN(convert_qty) AS factor_qty
      FROM purchase_order_detail
      WHERE COALESCE(convert_qty, 0) > 0
      GROUP BY goods_code, unit_name
  ) pfm ON pfm.goods_code = d.goods_code AND pfm.unit_name = d.unit_name
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
  LEFT JOIN (
      SELECT goods_code, unit_name, MIN(convert_qty) AS factor_qty
      FROM purchase_order_detail
      WHERE COALESCE(convert_qty, 0) > 0
      GROUP BY goods_code, unit_name
  ) pfm ON pfm.goods_code = d.goods_code AND pfm.unit_name = d.unit_name
  WHERE h.status = 'APPROVED'
) x
LEFT JOIN rpt_dim_goods dg ON dg.goods_code = x.goods_code;

-- 销售口径（用户确认）：一律按司机签收——日期取 CAST(sign_time AS DATE)，
-- 数量取明细 signed_qty、金额取明细 sign_amount（含税）；销售退货为负。
-- 拒收行（reject_qty）不入本视图。同样去相关子查询（业务员改 JOIN、换算率改聚合派生表）。
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
  LEFT JOIN (
      SELECT goods_code, unit_name, MIN(convert_qty) AS factor_qty
      FROM sales_order_detail
      WHERE COALESCE(convert_qty, 0) > 0
      GROUP BY goods_code, unit_name
  ) sfm ON sfm.goods_code = d.goods_code AND sfm.unit_name = d.unit_name
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
         COALESCE(sfm.factor_qty,
                  CASE WHEN d.unit_name = dg0.large_unit THEN dg0.large_convert_qty END,
                  1) AS factor,
         -d.amount AS amount
  FROM sales_return_inbound h
  JOIN sales_return_inbound_detail d ON d.inbound_id = h.inbound_id
  LEFT JOIN rpt_dim_goods dg0 ON dg0.goods_code = d.goods_code
  LEFT JOIN (
      SELECT goods_code, unit_name, MIN(convert_qty) AS factor_qty
      FROM sales_order_detail
      WHERE COALESCE(convert_qty, 0) > 0
      GROUP BY goods_code, unit_name
  ) sfm ON sfm.goods_code = d.goods_code AND sfm.unit_name = d.unit_name
  WHERE h.status = 'APPROVED' AND h.stock_updated = TRUE
) x
LEFT JOIN rpt_dim_goods dg ON dg.goods_code = x.goods_code;
