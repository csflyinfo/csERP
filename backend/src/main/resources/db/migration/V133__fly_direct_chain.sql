-- ============================================================
-- V133 飞单直发全链路：
--   ① fly_order 增加虚拟仓 + 采购入库/收货/销售出库/发货单据引用
--   ② 六张采销单据加 biz_type（NORMAL / FLY_DIRECT 飞单直发）
--   ③ 采购/销售 DWS 加 biz_type（商品日汇总并入主键，单据头日汇总只加列）
--   ④ 重建采购/销售 DWD 视图，透出 biz_type（其余口径不变）
-- 兼容：H2(MODE=MySQL) 与 MySQL 8 双跑；蛇形别名、单引号、限定 JOIN 列。
-- ============================================================

-- ① 飞单头：仓库 + 全链路单据引用 -------------------------------------------
ALTER TABLE fly_order ADD COLUMN IF NOT EXISTS warehouse_code VARCHAR(50);
ALTER TABLE fly_order ADD COLUMN IF NOT EXISTS warehouse_name VARCHAR(100);
ALTER TABLE fly_order ADD COLUMN IF NOT EXISTS inbound_id VARCHAR(32);
ALTER TABLE fly_order ADD COLUMN IF NOT EXISTS inbound_no VARCHAR(50);
ALTER TABLE fly_order ADD COLUMN IF NOT EXISTS receipt_id VARCHAR(32);
ALTER TABLE fly_order ADD COLUMN IF NOT EXISTS receipt_no VARCHAR(50);
ALTER TABLE fly_order ADD COLUMN IF NOT EXISTS outbound_id VARCHAR(32);
ALTER TABLE fly_order ADD COLUMN IF NOT EXISTS outbound_no VARCHAR(50);
ALTER TABLE fly_order ADD COLUMN IF NOT EXISTS delivery_id VARCHAR(32);
ALTER TABLE fly_order ADD COLUMN IF NOT EXISTS delivery_no VARCHAR(50);

-- ② 采销单据：业务类型 -------------------------------------------------------
ALTER TABLE purchase_order ADD COLUMN IF NOT EXISTS biz_type VARCHAR(20) DEFAULT 'NORMAL';
ALTER TABLE pur_inbound ADD COLUMN IF NOT EXISTS biz_type VARCHAR(20) DEFAULT 'NORMAL';
ALTER TABLE pur_receipt ADD COLUMN IF NOT EXISTS biz_type VARCHAR(20) DEFAULT 'NORMAL';
ALTER TABLE sales_order ADD COLUMN IF NOT EXISTS biz_type VARCHAR(20) DEFAULT 'NORMAL';
ALTER TABLE sales_outbound ADD COLUMN IF NOT EXISTS biz_type VARCHAR(20) DEFAULT 'NORMAL';
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS biz_type VARCHAR(20) DEFAULT 'NORMAL';

-- ③ DWS：商品日汇总加 biz_type 并入主键 --------------------------------------
ALTER TABLE rpt_dws_purchase_d ADD COLUMN IF NOT EXISTS biz_type VARCHAR(20) DEFAULT 'NORMAL' NOT NULL;
ALTER TABLE rpt_dws_purchase_d DROP PRIMARY KEY;
ALTER TABLE rpt_dws_purchase_d ADD CONSTRAINT pk_rpt_dws_purchase_d
    PRIMARY KEY (bill_date, supplier_code, buyer, goods_code, warehouse, biz_type);

ALTER TABLE rpt_dws_sales_d ADD COLUMN IF NOT EXISTS biz_type VARCHAR(20) DEFAULT 'NORMAL' NOT NULL;
ALTER TABLE rpt_dws_sales_d DROP PRIMARY KEY;
ALTER TABLE rpt_dws_sales_d ADD CONSTRAINT pk_rpt_dws_sales_d
    PRIMARY KEY (bill_date, customer_code, salesman, goods_code, warehouse, biz_type);

-- 单据头日汇总：一单一类型，只加列（主键不变）
ALTER TABLE rpt_dws_sales_bill_d ADD COLUMN IF NOT EXISTS biz_type VARCHAR(20) DEFAULT 'NORMAL';

-- ④a 采购 DWD（V115 口径完全保留，仅追加 biz_type）--------------------------
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
            ELSE x.amount / (x.order_qty * x.factor) END AS unit_price_base,
       x.biz_type AS biz_type
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
         d.amount AS amount,
         h.biz_type AS biz_type
  FROM (
    SELECT h.inbound_id, h.inbound_no AS bill_no, h.bill_date, h.source_order AS source_bill_no,
           h.supplier AS supplier_name, h.warehouse AS header_warehouse,
           h.biz_type AS biz_type,
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
         -(d.amount + COALESCE(d.tax_amount, 0)) AS amount,
         'NORMAL' AS biz_type
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

-- ④b 销售 DWD（V117 口径完全保留，仅追加 biz_type）--------------------------
CREATE OR REPLACE VIEW v_rpt_sales_detail AS
SELECT x.bill_type, x.bill_no, x.bill_date, x.source_bill_no,
       x.customer_code, x.customer_name, x.salesman, x.warehouse,
       x.driver, x.territory, x.route_line,
       x.goods_code, x.goods_name, x.order_unit, x.order_qty,
       x.factor AS convert_qty,
       x.order_qty * x.factor AS base_qty,
       CASE WHEN COALESCE(x.large_convert_qty, 0) > 0
            THEN x.order_qty * x.factor / x.large_convert_qty
            ELSE x.order_qty * x.factor END AS package_qty,
       x.large_unit AS large_unit,
       x.amount AS amount,
       CASE WHEN x.order_qty * x.factor = 0 THEN NULL
            ELSE x.amount / (x.order_qty * x.factor) END AS unit_price_base,
       x.cost_raw AS cost_amount,
       CASE WHEN x.order_qty * x.factor = 0 THEN NULL
            ELSE x.cost_raw / (x.order_qty * x.factor) END AS unit_cost_base,
       x.amount - x.cost_raw AS gross_profit,
       CASE WHEN x.amount = 0 THEN NULL
            ELSE (x.amount - x.cost_raw) / x.amount END AS gross_profit_rate,
       x.biz_type AS biz_type
FROM (
  SELECT '销售签收' AS bill_type, h.bill_no AS bill_no, h.bill_date AS bill_date, h.source_bill_no,
         h.customer_code AS customer_code, h.customer_name AS customer_name,
         h.salesman AS salesman, h.header_warehouse AS warehouse,
         h.driver AS driver, h.territory AS territory, h.route_line AS route_line,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, d.signed_qty AS order_qty,
         COALESCE(sfm.factor_qty,
                  CASE WHEN d.unit_name = dg.large_unit THEN dg.large_convert_qty END,
                  1) AS factor,
         dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,
         d.sign_amount AS amount,
         -- K8：该发货单对应出库单同商品成本合计 − 签收拒收(JSRK)明细成本。
         COALESCE((SELECT SUM(od.cost_amount)
                     FROM sales_outbound o
                     JOIN sales_outbound_detail od ON od.outbound_id = o.outbound_id
                    WHERE o.outbound_no = h.source_outbound_no
                      AND od.goods_code = d.goods_code), 0)
         - COALESCE((SELECT SUM(jd.cost_amount)
                       FROM inv_reject_inbound_detail jd
                      WHERE jd.source_detail_id = d.detail_id), 0) AS cost_raw,
         h.biz_type AS biz_type
  FROM (
    SELECT h.receipt_id, h.receipt_no AS bill_no, CAST(h.sign_time AS DATE) AS bill_date,
           h.source_outbound_no AS source_outbound_no,
           h.source_order_no AS source_bill_no,
           COALESCE(h.customer_code, '') AS customer_code,
           h.customer_name AS customer_name, h.warehouse AS header_warehouse,
           h.biz_type AS biz_type,
           COALESCE((SELECT so.salesman FROM sales_order so
                      WHERE so.order_no = h.source_order_no), '') AS salesman,
           -- 签收司机：优先签收操作人，其次运单司机姓名，回退发货单派遣司机
           COALESCE(NULLIF(h.sign_user, ''),
                    (SELECT de.employee_name FROM tms_dispatch td
                      JOIN base_employee de ON de.employee_id = td.driver_id
                     WHERE td.dispatch_id = h.dispatch_id),
                    h.driver, '') AS driver,
           COALESCE((SELECT o.territory FROM sales_outbound o
                      WHERE o.outbound_no = h.source_outbound_no), '') AS territory,
           COALESCE((SELECT o.route_line FROM sales_outbound o
                      WHERE o.outbound_no = h.source_outbound_no), '') AS route_line
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
         h.salesman AS salesman, h.header_warehouse AS warehouse,
         h.driver AS driver, h.territory AS territory, h.route_line AS route_line,
         d.goods_code AS goods_code, d.goods_name AS goods_name,
         d.unit_name AS order_unit, -d.qty AS order_qty,
         COALESCE(sfm.factor_qty,
                  CASE WHEN d.unit_name = dg.large_unit THEN dg.large_convert_qty END,
                  1) AS factor,
         dg.large_unit AS large_unit, dg.large_convert_qty AS large_convert_qty,
         -d.amount AS amount,
         -- 退货入库成本：审核时按当前移动加权成本回填（负向，与金额同向）
         -COALESCE(d.cost_amount, 0) AS cost_raw,
         'NORMAL' AS biz_type
  FROM (
    SELECT h.inbound_id, h.inbound_no AS bill_no, h.bill_date, h.source_apply_no AS source_bill_no,
           COALESCE(h.customer_code, '') AS customer_code, h.customer_name AS customer_name,
           h.warehouse AS header_warehouse,
           COALESCE((SELECT so.salesman
                       FROM sales_return_apply ra
                       JOIN sales_outbound so2 ON so2.outbound_no = ra.source_outbound_no
                       JOIN sales_order so ON so.order_no = so2.source_order
                      WHERE ra.apply_no = h.source_apply_no),
                    (SELECT dp.default_owner FROM rpt_dim_partner dp
                      WHERE dp.partner_type = 'CUSTOMER' AND dp.partner_code = h.customer_code),
                    '') AS salesman,
           COALESCE((SELECT ra.driver_name FROM sales_return_apply ra
                      WHERE ra.apply_no = h.source_apply_no), '') AS driver,
           COALESCE((SELECT o.territory FROM sales_return_apply ra
                      JOIN sales_outbound o ON o.outbound_no = ra.source_outbound_no
                     WHERE ra.apply_no = h.source_apply_no), '') AS territory,
           COALESCE((SELECT o.route_line FROM sales_return_apply ra
                      JOIN sales_outbound o ON o.outbound_no = ra.source_outbound_no
                     WHERE ra.apply_no = h.source_apply_no), '') AS route_line
    FROM sales_return_inbound h
    WHERE h.status = 'APPROVED' AND h.stock_updated = TRUE
  ) h
  JOIN sales_return_inbound_detail d ON d.inbound_id = h.inbound_id
  LEFT JOIN rpt_dim_unit_factor sfm
         ON sfm.biz_type = 'S' AND sfm.goods_code = d.goods_code AND sfm.unit_name = d.unit_name
  LEFT JOIN rpt_dim_goods dg ON dg.goods_code = d.goods_code
) x;
