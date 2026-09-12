package com.erp.report.dws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 库存每日快照 inv_stock_daily_snapshot（方案 §3.5，Q6：测试/预发同样按日结）。
 *
 * <p>粒度 日期+商品+仓库；inv_stock_balance 为批次粒度，按商品+仓聚合。
 * sales_qty_7d/30d 取 v_rpt_sales_detail 的司机签收净销量（含退货负数，按 sign_time），
 * 窗口为含快照日的近 7/30 天；日均 = 30 天净销量 / 30。
 */
@Service
public class StockSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(StockSnapshotService.class);

    private final JdbcTemplate jdbc;

    public StockSnapshotService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 重算某一天快照（幂等：先删后插）。 */
    @Transactional
    public int rebuild(LocalDate date) {
        LocalDate from7 = date.minusDays(6);
        LocalDate from30 = date.minusDays(29);
        jdbc.update("DELETE FROM inv_stock_daily_snapshot WHERE snapshot_date = ?", date);
        int rows = jdbc.update("""
                INSERT INTO inv_stock_daily_snapshot
                    (snapshot_date, goods_code, warehouse,
                     physical_qty, locked_qty, frozen_qty, available_qty,
                     cost_price, stock_amount, purchase_on_way,
                     sales_qty_7d, sales_qty_30d, avg_daily_sales, created_at)
                SELECT ?, b.goods_code, b.warehouse,
                       b.physical_qty, b.locked_qty, b.frozen_qty, b.available_qty,
                       CASE WHEN COALESCE(b.physical_qty,0) = 0 THEN 0
                            ELSE b.stock_amount / b.physical_qty END,
                       b.stock_amount, b.purchase_on_way,
                       COALESCE(s7.qty, 0), COALESCE(s30.qty, 0),
                       COALESCE(s30.qty, 0) / 30,
                       CURRENT_TIMESTAMP
                FROM (
                    SELECT goods_code, warehouse,
                           SUM(physical_qty) AS physical_qty,
                           SUM(locked_qty) AS locked_qty,
                           SUM(frozen_qty) AS frozen_qty,
                           SUM(available_qty) AS available_qty,
                           SUM(stock_amount) AS stock_amount,
                           SUM(purchase_on_way) AS purchase_on_way
                    FROM inv_stock_balance
                    GROUP BY goods_code, warehouse
                ) b
                LEFT JOIN (
                    SELECT goods_code, warehouse, SUM(base_qty) AS qty
                    FROM v_rpt_sales_detail
                    WHERE bill_date BETWEEN ? AND ?
                    GROUP BY goods_code, warehouse
                ) s7 ON s7.goods_code = b.goods_code AND s7.warehouse = b.warehouse
                LEFT JOIN (
                    SELECT goods_code, warehouse, SUM(base_qty) AS qty
                    FROM v_rpt_sales_detail
                    WHERE bill_date BETWEEN ? AND ?
                    GROUP BY goods_code, warehouse
                ) s30 ON s30.goods_code = b.goods_code AND s30.warehouse = b.warehouse
                """, date, from7, date, from30, date);
        log.info("inv_stock_daily_snapshot 快照 {} 完成：{} 行", date, rows);
        return rows;
    }
}
