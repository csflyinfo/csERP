-- =============================================================
-- V119：报表中心三期——绩效域统一 DWD 视图（#18 库管员绩效 / #19 司机配送绩效）
--
-- 元数据层复用原则：各作业单表（收货/上架/拣货/复核/补货/盘点/调整/异常）
-- 统一打平为 v_rpt_wms_work「一人一天一岗一作业类型」事实流，页面/合计/导出
-- 只消费这一份口径；配送侧以调度行为骨架、回填发货单签收量，沉淀 v_rpt_driver_bill。
-- 视图只做口径统一（状态完成字面值、日期取数、成本计价），不含任何期间参数。
-- =============================================================

-- ---------- 覆盖索引（按执行人+完成时刻，支撑期间按人聚合） ----------
CREATE INDEX IF NOT EXISTS idx_rpt_wit_receiver  ON wms_inbound_task(receiver, received_at);
CREATE INDEX IF NOT EXISTS idx_rpt_wpt_assignee  ON wms_putaway_task(assignee, finished_at);
CREATE INDEX IF NOT EXISTS idx_rpt_wpick_assignee ON wms_pick_task(assignee, picked_at);
CREATE INDEX IF NOT EXISTS idx_rpt_wrc_operator  ON wms_recheck_record(operator, checked_at);
CREATE INDEX IF NOT EXISTS idx_rpt_wst_assignee  ON wms_stocktake_task(assignee, finished_at);
CREATE INDEX IF NOT EXISTS idx_rpt_war_operator  ON wms_adjust_record(operator, approved_at);
CREATE INDEX IF NOT EXISTS idx_rpt_wrt_assignee  ON wms_replenish_task(assignee, finished_at);
CREATE INDEX IF NOT EXISTS idx_rpt_wex_reporter  ON wms_exception(reporter, created_at);
CREATE INDEX IF NOT EXISTS idx_rpt_dispatch_drv  ON tms_dispatch(driver_id, dispatch_date);
CREATE INDEX IF NOT EXISTS idx_rpt_trip_drv      ON tms_delivery_trip(driver_id, trip_date);
CREATE INDEX IF NOT EXISTS idx_rpt_settle_drv    ON tms_settlement(driver_id, settle_date);
CREATE INDEX IF NOT EXISTS idx_rpt_dret_drv      ON tms_driver_return(driver_id, return_date);
CREATE INDEX IF NOT EXISTS idx_rpt_texc_drv      ON tms_exception_report(driver_id, reported_at);
CREATE INDEX IF NOT EXISTS idx_rpt_sr_dispatch   ON sales_receipt(dispatch_id, sign_time);

-- ---------- 批次/商品仓成本计价小视图表（不存在则无需创建，此处用子查询内联） ----------

-- =============================================================
-- 视图：v_rpt_wms_work
-- 一行 = 一张已完成作业单（或一条异常）；列语义：
--   work_date   作业归属日（收货=收货结束时刻、其余=完成时刻、异常=上报时刻）
--   warehouse   仓库名称（WMS 全表按名称隔离；异常表无仓库列，由波次/入库任务回填）
--   operator    执行人 username
--   role_code   RECEIVER/PUTAWAY/PICKER/CHECKER/KEEPER
--   work_type   RECEIVE/PUTAWAY/PICK/CHECK/REPLENISH/STOCKTAKE/ADJUST_GAIN/ADJUST_LOSS/EXCEPTION
--   qty         作业数量（收货实收/上架/拣货/复核/补货数量；盘点/异常无数量）
--   line_count  作业行数（收货明细行数/上架任务行/拣货行/盘点库位行）
--   order_count 单据次数（收货单/复核单/补货单/盘点单/异常均计 1）
--   amount      成本金额：盘点差异（带符号：盈正亏负）、调整单（GAIN 正/LOSS 负）
--   work_minutes 可计算的作业耗时（分钟）：仅拣货/上架/补货有开始+完成时刻，其余为 0
-- =============================================================
CREATE VIEW IF NOT EXISTS v_rpt_wms_work AS
-- 收货：结束收货才落 received_at；完成字面值 status='DONE'
SELECT CAST(t.received_at AS DATE) AS work_date,
       t.warehouse                AS warehouse,
       t.receiver                 AS operator,
       'RECEIVER'                 AS role_code,
       'RECEIVE'                  AS work_type,
       COALESCE(t.received_qty, 0) AS qty,
       COALESCE(d.line_count, 0)  AS line_count,
       1                          AS order_count,
       CAST(0 AS DECIMAL(18, 2))  AS amount,
       0                          AS work_minutes
  FROM wms_inbound_task t
  LEFT JOIN (
      SELECT task_id, COUNT(*) AS line_count, SUM(COALESCE(received_qty, 0)) AS detail_qty
        FROM wms_inbound_task_detail
       WHERE COALESCE(received_qty, 0) > 0
       GROUP BY task_id
  ) d ON d.task_id = t.task_id
 WHERE t.status = 'DONE' AND t.receiver IS NOT NULL AND t.received_at IS NOT NULL
UNION ALL
-- 上架：putaway 任务与入库明细 1:1 或 1:N，一行任务即一个上架作业行
SELECT CAST(t.finished_at AS DATE),
       t.warehouse, t.assignee,
       'PUTAWAY', 'PUTAWAY',
       COALESCE(t.qty, 0), 1, 0, CAST(0 AS DECIMAL(18, 2)),
       CASE WHEN t.started_at IS NOT NULL AND t.finished_at IS NOT NULL
                 AND t.finished_at >= t.started_at
            THEN DATEDIFF('MINUTE', t.started_at, t.finished_at) ELSE 0 END
  FROM wms_putaway_task t
 WHERE t.status = 'DONE' AND t.assignee IS NOT NULL AND t.finished_at IS NOT NULL
UNION ALL
-- 拣货：完成字面值 PICKED（波次撤销是物理 DELETE，不存在 CANCELLED 行）
SELECT CAST(t.picked_at AS DATE),
       t.warehouse, t.assignee,
       'PICKER', 'PICK',
       COALESCE(t.picked_qty, 0), COALESCE(t.line_count, 0), 1, CAST(0 AS DECIMAL(18, 2)),
       CASE WHEN t.claimed_at IS NOT NULL AND t.picked_at IS NOT NULL
                 AND t.picked_at >= t.claimed_at
            THEN DATEDIFF('MINUTE', t.claimed_at, t.picked_at) ELSE 0 END
  FROM wms_pick_task t
 WHERE t.status = 'PICKED' AND t.assignee IS NOT NULL AND t.picked_at IS NOT NULL
UNION ALL
-- 复核：本表只写 PASS 行（NG 落 wms_exception），有记录即通过
SELECT CAST(r.checked_at AS DATE),
       r.warehouse, r.operator,
       'CHECKER', 'CHECK',
       COALESCE(r.checked_qty, 0), 1, 1, CAST(0 AS DECIMAL(18, 2)), 0
  FROM wms_recheck_record r
 WHERE r.operator IS NOT NULL AND r.checked_at IS NOT NULL
UNION ALL
-- 补货（库内移库补货到拣货位，按 KEEPER 归集）
SELECT CAST(t.finished_at AS DATE),
       t.warehouse, t.assignee,
       'KEEPER', 'REPLENISH',
       COALESCE(t.qty, 0), 1, 1, CAST(0 AS DECIMAL(18, 2)),
       CASE WHEN t.started_at IS NOT NULL AND t.finished_at IS NOT NULL
                 AND t.finished_at >= t.started_at
            THEN DATEDIFF('MINUTE', t.started_at, t.finished_at) ELSE 0 END
  FROM wms_replenish_task t
 WHERE t.status = 'DONE' AND t.assignee IS NOT NULL AND t.finished_at IS NOT NULL
UNION ALL
-- 盘点：一行审批通过的盘点任务；行数=已盘库位行；盘盈盘亏金额=实盘差异×成本
-- 成本计价：先按 商品+仓+批次 取批次移动平均，取不到按商品+仓库存均价，再取不到按 0
SELECT CAST(t.finished_at AS DATE),
       t.warehouse, t.assignee,
       'KEEPER', 'STOCKTAKE',
       CAST(0 AS DECIMAL(18, 3)), COALESCE(x.bin_count, 0), 1,
       COALESCE(x.diff_amount, 0), 0
  FROM wms_stocktake_task t
  LEFT JOIN (
      SELECT b.task_id AS task_id,
             COUNT(*) AS bin_count,
             SUM(b.diff_qty * COALESCE(bs.cost_price, bal.avg_cost, 0)) AS diff_amount
        FROM wms_stocktake_bin b
        LEFT JOIN (
            SELECT goods_code, warehouse, COALESCE(batch_no, '') AS batch_no,
                   AVG(cost_price) AS cost_price
              FROM inv_batch_stock
             GROUP BY goods_code, warehouse, COALESCE(batch_no, '')
        ) bs ON bs.goods_code = b.goods_code
            AND COALESCE(bs.batch_no, '') = COALESCE(b.batch_no, '')
            AND bs.warehouse = (SELECT tt.warehouse FROM wms_stocktake_task tt
                                 WHERE tt.task_id = b.task_id)
        LEFT JOIN (
            SELECT goods_code, warehouse, AVG(cost_price) AS avg_cost
              FROM inv_stock_balance
             GROUP BY goods_code, warehouse
        ) bal ON bal.goods_code = b.goods_code
            AND bal.warehouse = (SELECT tt2.warehouse FROM wms_stocktake_task tt2
                                  WHERE tt2.task_id = b.task_id)
       WHERE b.real_qty IS NOT NULL
       GROUP BY b.task_id
  ) x ON x.task_id = t.task_id
 WHERE t.status = 'APPROVED' AND t.assignee IS NOT NULL AND t.finished_at IS NOT NULL
UNION ALL
-- 库存调整（盘盈/盘亏审批）：adjust_qty 带符号（盈正亏负），金额按同上成本口径计价
SELECT CAST(a.approved_at AS DATE),
       a.warehouse, a.operator,
       'KEEPER',
       CASE WHEN a.adjust_type = 'GAIN' THEN 'ADJUST_GAIN' ELSE 'ADJUST_LOSS' END,
       ABS(COALESCE(a.adjust_qty, 0)), 1, 0,
       COALESCE(a.adjust_qty, 0) * COALESCE(bs.cost_price, bal.avg_cost, 0),
       0
  FROM wms_adjust_record a
  LEFT JOIN (
      SELECT goods_code, warehouse, COALESCE(batch_no, '') AS batch_no,
             AVG(cost_price) AS cost_price
        FROM inv_batch_stock
       GROUP BY goods_code, warehouse, COALESCE(batch_no, '')
  ) bs ON bs.goods_code = a.goods_code
      AND bs.warehouse = a.warehouse
      AND COALESCE(bs.batch_no, '') = COALESCE(a.batch_no, '')
  LEFT JOIN (
      SELECT goods_code, warehouse, AVG(cost_price) AS avg_cost
        FROM inv_stock_balance
       GROUP BY goods_code, warehouse
  ) bal ON bal.goods_code = a.goods_code AND bal.warehouse = a.warehouse
 WHERE a.status = 'APPROVED' AND a.operator IS NOT NULL AND a.approved_at IS NOT NULL
UNION ALL
-- 异常：本表无仓库列，波次异常取波次仓、入库异常取入库任务仓，均无则空串
SELECT CAST(e.created_at AS DATE),
       COALESCE(w.warehouse, it.warehouse, '') AS warehouse,
       e.reporter AS operator,
       'KEEPER', 'EXCEPTION',
       CAST(0 AS DECIMAL(18, 3)), 0, 1, CAST(0 AS DECIMAL(18, 2)), 0
  FROM wms_exception e
  LEFT JOIN wms_wave w ON w.wave_id = e.wave_id
  LEFT JOIN wms_inbound_task it ON it.task_no = e.source_bill
                              AND COALESCE(e.source_type, 'OUTBOUND') = 'INBOUND'
 WHERE e.reporter IS NOT NULL AND e.created_at IS NOT NULL
   AND COALESCE(e.status, 'OPEN') <> 'CANCELLED';

-- =============================================================
-- 视图：v_rpt_driver_bill
-- 一行 = 调度单上的一个客户行（RECEIPT 发货 / RETURN 退货取货），回填发货单签收结果。
-- 司机只经调度单取（tms_sign_record.sign_user 在新旧链路写入语义不一致，不作为归因）。
--   plan_date     调度日（计划口径）；sign_date 签收日（签收/拒收口径，#19 按签收日统计）
--   signed_qty    取发货明细 SUM(signed_qty)（头表无 signed_qty 列）
--   sign_amount   含税签收金额（部分拒收为实际签收净额）
--   reject_qty    头表拒收数量
-- =============================================================
CREATE VIEW IF NOT EXISTS v_rpt_driver_bill AS
SELECT d.driver_id                          AS driver_id,
       d.driver_name                        AS driver_name,
       d.dispatch_no                        AS dispatch_no,
       d.route_line                         AS route_line,
       d.territory                          AS territory,
       d.vehicle_plate                      AS vehicle_plate,
       CAST(d.dispatch_date AS DATE)        AS plan_date,
       CAST(dd.sign_time AS DATE)           AS sign_date,
       dd.bill_type                         AS bill_type,
       dd.status                            AS detail_status,
       dd.customer_code                     AS customer_code,
       dd.customer_name                     AS customer_name,
       dd.source_bill_no                    AS receipt_no,
       COALESCE(sr.sign_amount, 0)          AS sign_amount,
       COALESCE(srd.signed_qty, 0)          AS signed_qty,
       COALESCE(sr.reject_qty, 0)           AS reject_qty
  FROM tms_dispatch d
  JOIN tms_dispatch_detail dd ON dd.dispatch_id = d.dispatch_id
  LEFT JOIN sales_receipt sr ON sr.receipt_no = dd.source_bill_no
                            AND dd.bill_type = 'RECEIPT'
  LEFT JOIN (
      SELECT receipt_id, SUM(COALESCE(signed_qty, 0)) AS signed_qty
        FROM sales_receipt_detail
       GROUP BY receipt_id
  ) srd ON srd.receipt_id = sr.receipt_id
 WHERE d.driver_id IS NOT NULL
   AND d.status <> 'CANCELLED';
