-- =====================================================================
-- 报表中心一期性能修复③：商品维度补条码/温区 + DWD 视图去重复 JOIN
--
-- 规模压测（100 万采购明细行）定位：
--   1. 报表2/3/4 每页都实时 JOIN base_goods 取条码/温区——商品属性是档案型
--      快照数据，应与其他维度一样走 rpt_dim_goods（30 分钟刷新），把压力
--      从业务库档案表挪到报表维度；补两列并回填；
--   2. DWD 视图每个 UNION 分支内 JOIN 一次 rpt_dim_goods(dg0) 仅为换算率
--      兜底，外层又 JOIN 一次 dg 出件数/大单位，等于每个明细行做两次商品
--      维度主键查找。改为内层只出原始换算率（可空），兜底判定移到外层，
--      全查询每行省一次 JOIN。
-- 视图列名/口径（含税、基本单位、退货负数、件数换算）完全不变。
-- =====================================================================

ALTER TABLE rpt_dim_goods ADD COLUMN barcode VARCHAR(100);
ALTER TABLE rpt_dim_goods ADD COLUMN storage_property VARCHAR(50);

-- 首次回填（后续随维度快照全量重建刷新）
UPDATE rpt_dim_goods
SET barcode = (SELECT bg.barcode FROM base_goods bg WHERE bg.goods_code = rpt_dim_goods.goods_code),
    storage_property = (SELECT bg.storage_property FROM base_goods bg
                        WHERE bg.goods_code = rpt_dim_goods.goods_code);

-- 报表1 单据头窗口分页：头表按单据日期索引排序、行表按 (order_id, goods_code)
-- 跟随，窗口内排序无需对百万行做外部排序。
CREATE INDEX IF NOT EXISTS idx_po_detail_rpt_order_goods
    ON purchase_order_detail(order_id, goods_code);

CREATE OR REPLACE VIEW v_rpt_purchase_detail AS
SELECT y.bill_type, y.bill_no, y.bill_date, y.source_bill_no,
       y.supplier_code, y.supplier_name, y.buyer, y.warehouse,
       y.goods_code, y.goods_name, y.order_unit, y.order_qty,
       y.factor AS convert_qty,
       y.order_qty * y.factor AS base_qty,
       CASE WHEN y.large_unit IS NOT NULL AND COALESCE(y.large_convert_qty, 0) > 0
            THEN y.order_qty * y.factor / y.large_convert_qty
            ELSE y.order_qty * y.factor END AS package_qty,
       y.large_unit AS large_unit,
       y.amount AS amount,
       CASE WHEN y.order_qty * y.factor = 0 THEN NULL
            ELSE y.amount / (y.order_qty * y.factor) END AS unit_price_base
FROM (
  SELECT x.*, dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,
         COALESCE(x.factor_raw,
                  CASE WHEN x.order_unit = dg.large_unit THEN dg.large_convert_qty END,
                  1) AS factor
  FROM (
    SELECT '采购入库' AS bill_type, h.inbound_no AS bill_no, h.bill_date AS bill_date,
           h.source_order AS source_bill_no,
           COALESCE(sup.supplier_code, '') AS supplier_code,
           h.supplier AS supplier_name,
           COALESCE(po.buyer, sup.default_buyer, '') AS buyer,
           COALESCE(d.warehouse, h.warehouse) AS warehouse,
           d.goods_code AS goods_code, d.goods_name AS goods_name,
           d.unit_name AS order_unit, d.received_qty AS order_qty,
           pfm.factor_qty AS factor_raw,
           d.amount AS amount
    FROM pur_inbound h
    JOIN pur_inbound_detail d ON d.inbound_id = h.inbound_id
    LEFT JOIN base_supplier sup ON sup.supplier_name = h.supplier
    LEFT JOIN purchase_order po ON po.order_no = h.source_order
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
           pfm.factor_qty AS factor_raw,
           -(d.amount + COALESCE(d.tax_amount, 0)) AS amount
    FROM pur_return h
    JOIN pur_return_detail d ON d.return_id = h.return_id
    LEFT JOIN base_supplier sup ON sup.supplier_code = h.supplier_code
    LEFT JOIN rpt_dim_unit_factor pfm
           ON pfm.biz_type = 'P' AND pfm.goods_code = d.goods_code AND pfm.unit_name = d.unit_name
    WHERE h.status = 'APPROVED'
  ) x
  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = x.goods_code
) y;

CREATE OR REPLACE VIEW v_rpt_sales_detail AS
SELECT y.bill_type, y.bill_no, y.bill_date, y.source_bill_no,
       y.customer_code, y.customer_name, y.salesman, y.warehouse,
       y.goods_code, y.goods_name, y.order_unit, y.order_qty,
       y.factor AS convert_qty,
       y.order_qty * y.factor AS base_qty,
       CASE WHEN y.large_unit IS NOT NULL AND COALESCE(y.large_convert_qty, 0) > 0
            THEN y.order_qty * y.factor / y.large_convert_qty
            ELSE y.order_qty * y.factor END AS package_qty,
       y.large_unit AS large_unit,
       y.amount AS amount,
       CASE WHEN y.order_qty * y.factor = 0 THEN NULL
            ELSE y.amount / (y.order_qty * y.factor) END AS unit_price_base
FROM (
  SELECT x.*, dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,
         COALESCE(x.factor_raw,
                  CASE WHEN x.order_unit = dg.large_unit THEN dg.large_convert_qty END,
                  1) AS factor
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
           sfm.factor_qty AS factor_raw,
           d.sign_amount AS amount
    FROM sales_receipt h
    JOIN sales_receipt_detail d ON d.receipt_id = h.receipt_id
    LEFT JOIN sales_order so ON so.order_no = h.source_order_no
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
           sfm.factor_qty AS factor_raw,
           -d.amount AS amount
    FROM sales_return_inbound h
    JOIN sales_return_inbound_detail d ON d.inbound_id = h.inbound_id
    LEFT JOIN rpt_dim_unit_factor sfm
           ON sfm.biz_type = 'S' AND sfm.goods_code = d.goods_code AND sfm.unit_name = d.unit_name
    WHERE h.status = 'APPROVED' AND h.stock_updated = TRUE
  ) x
  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = x.goods_code
) y;
