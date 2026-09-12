-- =====================================================================
-- 报表中心一期性能修复④：DWD 视图头级查找收敛 + 单子查询层保谓词推进
--
-- V114 的双层派生表(x→y)在 H2 上导致两个问题（10 万行/日压测实测）：
--   1. base_supplier / purchase_order 的查找按明细行执行（单日 20 万次/表），
--      它们其实只与单据头相关（2 千次即可）；
--   2. 多层嵌套下计划抖动，3 日区间分页达 44s（超过 30s 语句超时）。
--
-- 本版结构（采购/销售两个 DWD 视图同构）：
--   头子查询 h：只扫单据头（走状态/日期索引），供应商编码/采购员等
--               头级属性用相关标量子查询按头解析（实测每表 scanCount 2）；
--   JOIN 明细表 + 单位换算率 + 商品维度（每个分支各一次，每行省一次）；
--   最外层仅一层算术包装（factor/base_qty/package_qty/单价），
--   实测 WHERE bill_date 能推进到分支单据头索引（单日 1.1s）。
-- 视图列名/口径（含税、基本单位、退货负数、件数换算）完全不变。
-- =====================================================================

CREATE OR REPLACE VIEW v_rpt_purchase_detail AS
SELECT x.bill_type, x.bill_no, x.bill_date, x.source_bill_no,
       x.supplier_code, x.supplier_name, x.buyer, x.warehouse,
       x.goods_code, x.goods_name, x.order_unit, x.order_qty,
       x.factor AS convert_qty,
       x.order_qty * x.factor AS base_qty,
       CASE WHEN COALESCE(x.large_convert_qty, 0) > 0
            THEN x.order_qty * x.factor / x.large_convert_qty
            ELSE x.order_qty * x.factor END AS package_qty,
       x.large_unit AS large_unit,
       x.amount AS amount,
       CASE WHEN x.order_qty * x.factor = 0 THEN NULL
            ELSE x.amount / (x.order_qty * x.factor) END AS unit_price_base
FROM (
  SELECT '采购入库' AS bill_type, h.bill_no AS bill_no, h.bill_date AS bill_date, h.source_bill_no,
         h.supplier_code AS supplier_code, h.supplier_name AS supplier_name,
         h.buyer AS buyer,
         COALESCE(d.warehouse, h.header_warehouse) AS warehouse,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, d.received_qty AS order_qty,
         COALESCE(pfm.factor_qty,
                  CASE WHEN d.unit_name = dg.large_unit THEN dg.large_convert_qty END,
                  1) AS factor,
         dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,
         d.amount AS amount
  FROM (
    SELECT h.inbound_id, h.inbound_no AS bill_no, h.bill_date, h.source_order AS source_bill_no,
           h.supplier AS supplier_name, h.warehouse AS header_warehouse,
           (SELECT sup.supplier_code FROM base_supplier sup
             WHERE sup.supplier_name = h.supplier) AS supplier_code,
           COALESCE((SELECT po.buyer FROM purchase_order po
                      WHERE po.order_no = h.source_order),
                    (SELECT sup.default_buyer FROM base_supplier sup
                      WHERE sup.supplier_name = h.supplier), '') AS buyer
    FROM pur_inbound h
    WHERE h.status = 'APPROVED'
  ) h
  JOIN pur_inbound_detail d ON d.inbound_id = h.inbound_id
  LEFT JOIN rpt_dim_unit_factor pfm
         ON pfm.biz_type = 'P' AND pfm.goods_code = d.goods_code AND pfm.unit_name = d.unit_name
  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = d.goods_code
  UNION ALL
  SELECT '采购退货' AS bill_type, h.bill_no AS bill_no, h.bill_date AS bill_date, h.source_bill_no,
         h.supplier_code AS supplier_code, h.supplier_name AS supplier_name,
         h.buyer AS buyer,
         h.header_warehouse AS warehouse,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, -d.qty AS order_qty,
         COALESCE(pfm.factor_qty,
                  CASE WHEN d.unit_name = dg.large_unit THEN dg.large_convert_qty END,
                  1) AS factor,
         dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,
         -(d.amount + COALESCE(d.tax_amount, 0)) AS amount
  FROM (
    SELECT h.return_id, h.return_no AS bill_no, h.return_date AS bill_date,
           h.source_apply_no AS source_bill_no,
           h.supplier_name AS supplier_name, h.warehouse AS header_warehouse,
           h.supplier_code AS supplier_code,
           COALESCE((SELECT sup.default_buyer FROM base_supplier sup
                      WHERE sup.supplier_name = h.supplier_name), '') AS buyer
    FROM pur_return h
    WHERE h.status = 'APPROVED'
  ) h
  JOIN pur_return_detail d ON d.return_id = h.return_id
  LEFT JOIN rpt_dim_unit_factor pfm
         ON pfm.biz_type = 'P' AND pfm.goods_code = d.goods_code AND pfm.unit_name = d.unit_name
  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = d.goods_code
) x;

CREATE OR REPLACE VIEW v_rpt_sales_detail AS
SELECT x.bill_type, x.bill_no, x.bill_date, x.source_bill_no,
       x.customer_code, x.customer_name, x.salesman, x.warehouse,
       x.goods_code, x.goods_name, x.order_unit, x.order_qty,
       x.factor AS convert_qty,
       x.order_qty * x.factor AS base_qty,
       CASE WHEN COALESCE(x.large_convert_qty, 0) > 0
            THEN x.order_qty * x.factor / x.large_convert_qty
            ELSE x.order_qty * x.factor END AS package_qty,
       x.large_unit AS large_unit,
       x.amount AS amount,
       CASE WHEN x.order_qty * x.factor = 0 THEN NULL
            ELSE x.amount / (x.order_qty * x.factor) END AS unit_price_base
FROM (
  SELECT '销售签收' AS bill_type, h.bill_no AS bill_no, h.bill_date AS bill_date, h.source_bill_no,
         h.customer_code AS customer_code, h.customer_name AS customer_name,
         h.salesman AS salesman, h.header_warehouse AS warehouse,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, d.signed_qty AS order_qty,
         COALESCE(sfm.factor_qty,
                  CASE WHEN d.unit_name = dg.large_unit THEN dg.large_convert_qty END,
                  1) AS factor,
         dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,
         d.sign_amount AS amount
  FROM (
    SELECT h.receipt_id, h.receipt_no AS bill_no, CAST(h.sign_time AS DATE) AS bill_date,
           h.source_order_no AS source_bill_no,
           COALESCE(h.customer_code, '') AS customer_code,
           h.customer_name AS customer_name, h.warehouse AS header_warehouse,
           COALESCE((SELECT so.salesman FROM sales_order so
                      WHERE so.order_no = h.source_order_no), '') AS salesman
    FROM sales_receipt h
    WHERE h.status = 'APPROVED'
      AND h.sign_status IN ('已签收', '部分拒收')
      AND h.sign_time IS NOT NULL
  ) h
  JOIN sales_receipt_detail d ON d.receipt_id = h.receipt_id
  LEFT JOIN rpt_dim_unit_factor sfm
         ON sfm.biz_type = 'S' AND sfm.goods_code = d.goods_code AND sfm.unit_name = d.unit_name
  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = d.goods_code
  WHERE d.signed_qty > 0
  UNION ALL
  SELECT '销售退货' AS bill_type, h.bill_no AS bill_no, h.bill_date AS bill_date, h.source_bill_no,
         h.customer_code AS customer_code, h.customer_name AS customer_name,
         '' AS salesman, h.header_warehouse AS warehouse,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, -d.qty AS order_qty,
         COALESCE(sfm.factor_qty,
                  CASE WHEN d.unit_name = dg.large_unit THEN dg.large_convert_qty END,
                  1) AS factor,
         dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,
         -d.amount AS amount
  FROM (
    SELECT h.inbound_id, h.inbound_no AS bill_no, h.bill_date, h.source_apply_no AS source_bill_no,
           COALESCE(h.customer_code, '') AS customer_code, h.customer_name AS customer_name,
           h.warehouse AS header_warehouse
    FROM sales_return_inbound h
    WHERE h.status = 'APPROVED' AND h.stock_updated = TRUE
  ) h
  JOIN sales_return_inbound_detail d ON d.inbound_id = h.inbound_id
  LEFT JOIN rpt_dim_unit_factor sfm
         ON sfm.biz_type = 'S' AND sfm.goods_code = d.goods_code AND sfm.unit_name = d.unit_name
  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = d.goods_code
) x;
