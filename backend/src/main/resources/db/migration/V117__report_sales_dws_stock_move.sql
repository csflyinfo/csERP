-- ============================================================
-- V117 报表中心二期①：销售域 DWD 成本口径 + 销售日汇总 + 库存流水视图/日汇总
--
-- 内容：
--   ① 重建 v_rpt_sales_detail：在一期签收口径（sign_time/signed_qty/sign_amount
--      含税、退货负数）上补 K8 成本口径——
--      签收成本 = 对应销售出库单成本（按商品）− 客户拒收入库(JSRK)成本，
--      归属签收日期；退货成本取销售退货入库单审核时的移动加权成本（负）。
--      新增 cost_amount / unit_cost_base / gross_profit / gross_profit_rate。
--      退货分支业务员由 退货申请.source_outbound_no → 出库单 → 销售订单 解析，
--      解析不到回退客户默认业务员（rpt_dim_partner）。
--   ② rpt_dws_sales_d：销售商品行日汇总
--      （日期+客户+业务员+商品+仓库），#10/#11/#14 等汇总表共用。
--   ③ rpt_dws_sales_bill_d：销售单据头日汇总（日期+单据），
--      承载签收单数/客单价/客户数等头粒度指标，#12/#13 共用。
--   ④ v_rpt_stock_move：库存台账统一口径视图，inv_stock_ledger 前缀派生
--      单据类型、冲销单据识别、收支方向数量/金额拆分（实物账按记账时刻）。
--   ⑤ rpt_dws_stock_move_d：库存流水日汇总（日期+商品+仓库+单据类型），
--      #8 商品进销存汇总 / 一期快照 / 三期周转率共用底层元数据。
--   ⑥ 销售/财务高频关联索引。
-- 兼容：H2(MODE=MySQL) 与 MySQL 8 双跑；蛇形别名、单引号、限定 JOIN 列。
-- ============================================================

-- ① 销售 DWD（一期列与口径完全保留，仅追加 4 个成本/毛利列）----------------
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
            ELSE (x.amount - x.cost_raw) / x.amount END AS gross_profit_rate
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
         -- JSRK 在签收事务内按原出库成本快照生成（source_detail_id 关联发货行），
         -- 一张发货单至多一张 JSRK；是否已审核只影响在途/在库，不影响签收成本归属。
         COALESCE((SELECT SUM(od.cost_amount)
                     FROM sales_outbound o
                     JOIN sales_outbound_detail od ON od.outbound_id = o.outbound_id
                    WHERE o.outbound_no = h.source_outbound_no
                      AND od.goods_code = d.goods_code), 0)
         - COALESCE((SELECT SUM(jd.cost_amount)
                       FROM inv_reject_inbound_detail jd
                      WHERE jd.source_detail_id = d.detail_id), 0) AS cost_raw
  FROM (
    SELECT h.receipt_id, h.receipt_no AS bill_no, CAST(h.sign_time AS DATE) AS bill_date,
           h.source_outbound_no AS source_outbound_no,
           h.source_order_no AS source_bill_no,
           COALESCE(h.customer_code, '') AS customer_code,
           h.customer_name AS customer_name, h.warehouse AS header_warehouse,
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
         -COALESCE(d.cost_amount, 0) AS cost_raw
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

-- ② 销售商品行日汇总（粒度：日期+客户+业务员+商品+仓库）---------------------
CREATE TABLE IF NOT EXISTS rpt_dws_sales_d (
  bill_date DATE NOT NULL,
  customer_code VARCHAR(50) NOT NULL,
  customer_name VARCHAR(200),
  customer_level VARCHAR(50),
  territory VARCHAR(100),
  route_line VARCHAR(100),
  salesman VARCHAR(100) NOT NULL,
  goods_code VARCHAR(50) NOT NULL,
  warehouse VARCHAR(100) NOT NULL,
  goods_name VARCHAR(200),
  brand_name VARCHAR(100),
  category_name VARCHAR(100),
  storage_property VARCHAR(50),
  signed_qty_base DECIMAL(18, 4) DEFAULT 0,
  signed_package_qty DECIMAL(18, 4) DEFAULT 0,
  signed_amount DECIMAL(18, 2) DEFAULT 0,
  signed_cost_amount DECIMAL(18, 2) DEFAULT 0,
  return_qty_base DECIMAL(18, 4) DEFAULT 0,
  return_package_qty DECIMAL(18, 4) DEFAULT 0,
  return_amount DECIMAL(18, 2) DEFAULT 0,
  return_cost_amount DECIMAL(18, 2) DEFAULT 0,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (bill_date, customer_code, salesman, goods_code, warehouse)
);
CREATE INDEX IF NOT EXISTS idx_rpt_dws_sales_goods ON rpt_dws_sales_d(goods_code, bill_date);
CREATE INDEX IF NOT EXISTS idx_rpt_dws_sales_customer ON rpt_dws_sales_d(customer_code, bill_date);
CREATE INDEX IF NOT EXISTS idx_rpt_dws_sales_salesman ON rpt_dws_sales_d(salesman, bill_date);

-- ③ 销售单据头日汇总（粒度：日期+发货/退货单；头粒度指标专用）----------------
-- #12 客户汇总（签收单数/客单价）、#13 业务员汇总（客户数/单数）取本表；
-- 一张单据一行，仓库取单头仓（发货/退货入库均为单仓单）。
CREATE TABLE IF NOT EXISTS rpt_dws_sales_bill_d (
  bill_date DATE NOT NULL,
  bill_no VARCHAR(50) NOT NULL,
  bill_type VARCHAR(10) NOT NULL,          -- SIGN 销售签收 / RETURN 销售退货
  customer_code VARCHAR(50) NOT NULL,
  customer_name VARCHAR(200),
  customer_level VARCHAR(50),
  territory VARCHAR(100),
  route_line VARCHAR(100),
  salesman VARCHAR(100) NOT NULL,
  warehouse VARCHAR(100),
  signed_qty_base DECIMAL(18, 4) DEFAULT 0,
  signed_amount DECIMAL(18, 2) DEFAULT 0,
  signed_cost_amount DECIMAL(18, 2) DEFAULT 0,
  return_qty_base DECIMAL(18, 4) DEFAULT 0,
  return_amount DECIMAL(18, 2) DEFAULT 0,
  return_cost_amount DECIMAL(18, 2) DEFAULT 0,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (bill_date, bill_no)
);
CREATE INDEX IF NOT EXISTS idx_rpt_dws_sales_bill_customer
    ON rpt_dws_sales_bill_d(customer_code, bill_date);
CREATE INDEX IF NOT EXISTS idx_rpt_dws_sales_bill_salesman
    ON rpt_dws_sales_bill_d(salesman, bill_date);

-- ④ 库存流水统一口径视图（#8 进销存汇总 / #9 库存台账共用 DWD）---------------
-- 实物账口径：日期取 inv_stock_ledger.occurred_at 的日期（库存实际记账时刻，
-- 与销售签收口径各自独立，已出库未签收在途构成两套账差异）。
-- 冲销/作废回库不删原行，而是追加反方向流水、source_bill 带 '(反审核)'/'(作废回库)'
-- 后缀；视图拆出原始单号与冲销标记，台账联查原单、汇总按物理方向自然轧差。
CREATE OR REPLACE VIEW v_rpt_stock_move AS
SELECT m.move_date, m.occurred_at, m.ledger_id,
       m.source_bill AS bill_no, m.base_bill_no,
       m.reversal_flag,
       CASE WHEN SUBSTRING(m.base_bill_no, 1, 16) = 'WMS_ADJUST_GAIN:' THEN 'WMS_ADJUST_GAIN'
            WHEN SUBSTRING(m.base_bill_no, 1, 12) = 'WMS_INBOUND:' THEN 'WMS_INBOUND'
            WHEN SUBSTRING(m.base_bill_no, 1, 4) = 'CGRK' THEN 'CGRK'
            WHEN SUBSTRING(m.base_bill_no, 1, 4) = 'CGSH' THEN 'CGSH'
            WHEN SUBSTRING(m.base_bill_no, 1, 4) = 'CTCK' THEN 'CTCK'
            WHEN SUBSTRING(m.base_bill_no, 1, 4) = 'XSCK' THEN 'XSCK'
            WHEN SUBSTRING(m.base_bill_no, 1, 4) = 'THRK' THEN 'THRK'
            WHEN SUBSTRING(m.base_bill_no, 1, 4) = 'JSRK' THEN 'JSRK'
            WHEN SUBSTRING(m.base_bill_no, 1, 4) = 'QTRK' THEN 'QTRK'
            WHEN SUBSTRING(m.base_bill_no, 1, 4) = 'QTCK' THEN 'QTCK'
            WHEN SUBSTRING(m.base_bill_no, 1, 4) = 'DBCK' THEN 'DBCK'
            WHEN SUBSTRING(m.base_bill_no, 1, 4) = 'DBRK' THEN 'DBRK'
            WHEN SUBSTRING(m.base_bill_no, 1, 3) = 'BSD' THEN 'BSD'
            WHEN SUBSTRING(m.base_bill_no, 1, 3) = 'PDD' THEN 'PDD'
            ELSE 'OTHER' END AS bill_type_code,
       m.goods_code, m.goods_name, m.warehouse, m.batch_no,
       m.direction, m.qty,
       CASE WHEN m.direction = 'IN' THEN m.qty ELSE 0 END AS in_qty,
       CASE WHEN m.direction = 'OUT' THEN m.qty ELSE 0 END AS out_qty,
       m.cost_price, m.amount,
       CASE WHEN m.direction = 'IN' THEN m.amount
            WHEN m.direction = '成本调整' THEN m.amount ELSE 0 END AS in_amount,
       CASE WHEN m.direction = 'OUT' THEN m.amount ELSE 0 END AS out_amount,
       CASE WHEN m.direction = '成本调整' THEN m.amount ELSE 0 END AS adjust_amount,
       m.balance_qty, m.operator_name
FROM (
  SELECT CAST(l.occurred_at AS DATE) AS move_date, l.occurred_at AS occurred_at,
         l.ledger_id AS ledger_id,
         l.source_bill AS source_bill,
         CASE WHEN l.source_bill LIKE '%(反审核)'
              THEN SUBSTRING(l.source_bill, 1, LENGTH(l.source_bill) - 5)
              WHEN l.source_bill LIKE '%(作废回库)'
              THEN SUBSTRING(l.source_bill, 1, LENGTH(l.source_bill) - 6)
              ELSE l.source_bill END AS base_bill_no,
         CASE WHEN l.source_bill LIKE '%(反审核)' OR l.source_bill LIKE '%(作废回库)'
              THEN TRUE ELSE FALSE END AS reversal_flag,
         l.goods_code AS goods_code, l.goods_name AS goods_name,
         l.warehouse AS warehouse, l.batch_no AS batch_no,
         l.direction AS direction, l.qty AS qty,
         l.cost_price AS cost_price, l.amount AS amount,
         l.balance_qty AS balance_qty, l.operator_name AS operator_name
  FROM inv_stock_ledger l
) m;

-- ⑤ 库存流水日汇总（粒度：日期+商品+仓库+单据类型）-------------------------
CREATE TABLE IF NOT EXISTS rpt_dws_stock_move_d (
  move_date DATE NOT NULL,
  goods_code VARCHAR(50) NOT NULL,
  warehouse VARCHAR(100) NOT NULL,
  bill_type_code VARCHAR(20) NOT NULL,
  goods_name VARCHAR(200),
  brand_name VARCHAR(100),
  category_name VARCHAR(100),
  storage_property VARCHAR(50),
  in_qty DECIMAL(18, 4) DEFAULT 0,
  out_qty DECIMAL(18, 4) DEFAULT 0,
  in_amount DECIMAL(18, 2) DEFAULT 0,
  out_amount DECIMAL(18, 2) DEFAULT 0,
  adjust_amount DECIMAL(18, 2) DEFAULT 0,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (move_date, goods_code, warehouse, bill_type_code)
);
CREATE INDEX IF NOT EXISTS idx_rpt_dws_move_goods
    ON rpt_dws_stock_move_d(goods_code, move_date);
CREATE INDEX IF NOT EXISTS idx_rpt_dws_move_wh
    ON rpt_dws_stock_move_d(warehouse, move_date);

-- ⑥ 高频关联/过滤索引 -------------------------------------------------------
-- 签收成本：发货单（UNIQUE 单号）→ 出库明细按商品聚合成本
CREATE INDEX IF NOT EXISTS idx_sales_outbound_detail_rpt_cost
    ON sales_outbound_detail(outbound_id, goods_code);
-- 拒收成本：JSRK 明细按来源发货行直接定位
CREATE INDEX IF NOT EXISTS idx_reject_detail_rpt_src
    ON inv_reject_inbound_detail(source_detail_id);
-- 退货业务员解析：退货申请 → 原出库单
CREATE INDEX IF NOT EXISTS idx_sales_return_apply_rpt_outbound
    ON sales_return_apply(source_outbound_no);
-- 报表15 销售订单明细：订单行按头+商品跟随
CREATE INDEX IF NOT EXISTS idx_so_detail_rpt_order_goods
    ON sales_order_detail(order_id, goods_code);
-- 报表15 头窗口：订单按日期+状态
CREATE INDEX IF NOT EXISTS idx_sales_order_rpt_date_status
    ON sales_order(bill_date, status);
-- 报表12 客户回款/应收实时关联
CREATE INDEX IF NOT EXISTS idx_fin_reconcile_rpt_customer
    ON fin_reconcile_record(counterparty_code, receipt_date);
CREATE INDEX IF NOT EXISTS idx_fin_ar_rpt_customer
    ON fin_ar(customer, status);
