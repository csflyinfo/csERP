package com.erp.report.dws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 库存流水日汇总 rpt_dws_stock_move_d（报表中心二期）。
 *
 * <p>粒度：日期+商品+仓库+单据类型（v_rpt_stock_move.bill_type_code，由流水号
 * 前缀派生，冲销行按物理方向参与轧差）。#8 商品进销存汇总、三期周转率共用；
 * 期初/期末仍直接取流水聚合（流水自系统首日完整），本 DWS 只承载期间发生额。
 *
 * <p>幂等：DELETE 日期分区 + INSERT...SELECT。
 */
@Service
public class StockMoveDwsService {

    private static final Logger log = LoggerFactory.getLogger(StockMoveDwsService.class);

    private final JdbcTemplate jdbc;

    public StockMoveDwsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 重算闭区间日期分区。 */
    @Transactional
    public int refreshRange(LocalDate start, LocalDate end) {
        jdbc.update("DELETE FROM rpt_dws_stock_move_d WHERE move_date BETWEEN ? AND ?", start, end);
        int rows = jdbc.update("""
                INSERT INTO rpt_dws_stock_move_d
                    (move_date, goods_code, warehouse, bill_type_code,
                     goods_name, brand_name, category_name, storage_property,
                     in_qty, out_qty, in_amount, out_amount, adjust_amount, updated_at)
                SELECT v.move_date, v.goods_code, v.warehouse, v.bill_type_code,
                       MAX(dg.goods_name), MAX(dg.brand_name), MAX(dg.category_name),
                       MAX(dg.storage_property),
                       SUM(v.in_qty), SUM(v.out_qty),
                       SUM(v.in_amount), SUM(v.out_amount), SUM(v.adjust_amount),
                       CURRENT_TIMESTAMP
                FROM v_rpt_stock_move v
                LEFT JOIN rpt_dim_goods dg ON dg.goods_code = v.goods_code
                WHERE v.move_date BETWEEN ? AND ?
                GROUP BY v.move_date, v.goods_code, v.warehouse, v.bill_type_code
                """, start, end);
        log.info("rpt_dws_stock_move_d 重算 {} ~ {} 完成：{} 行", start, end, rows);
        return rows;
    }

    /** 全量重建（初始化/运维使用，流水量级远小于销售明细）。 */
    @Transactional
    public int rebuildAll() {
        LocalDate first = jdbc.queryForObject(
                "SELECT MIN(CAST(occurred_at AS DATE)) FROM inv_stock_ledger", LocalDate.class);
        if (first == null) {
            jdbc.update("DELETE FROM rpt_dws_stock_move_d");
            log.info("rpt_dws_stock_move_d 全量重建：无流水，清空 0 行");
            return 0;
        }
        LocalDate last = LocalDate.now();
        jdbc.update("DELETE FROM rpt_dws_stock_move_d");
        int rows = refreshRange(first, last);
        log.info("rpt_dws_stock_move_d 全量重建完成 {} ~ {}：{} 行", first, last, rows);
        return rows;
    }

    /** 对账：DWS 期间收发数量 vs 流水视图同期。 */
    public Map<String, Object> reconcile(LocalDate start, LocalDate end) {
        double dIn = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(in_qty),0) FROM rpt_dws_stock_move_d
                WHERE move_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double lIn = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(in_qty),0) FROM v_rpt_stock_move
                WHERE move_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double dOut = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(out_qty),0) FROM rpt_dws_stock_move_d
                WHERE move_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double lOut = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(out_qty),0) FROM v_rpt_stock_move
                WHERE move_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double dAdj = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(adjust_amount),0) FROM rpt_dws_stock_move_d
                WHERE move_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double lAdj = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(adjust_amount),0) FROM v_rpt_stock_move
                WHERE move_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double diffIn = dIn - lIn;
        double diffOut = dOut - lOut;
        double diffAdj = dAdj - lAdj;
        boolean balanced = Math.abs(diffIn) < 0.001 && Math.abs(diffOut) < 0.001
                && Math.abs(diffAdj) < 0.01;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("start", start.toString());
        r.put("end", end.toString());
        r.put("dwsInQty", dIn);
        r.put("liveInQty", lIn);
        r.put("diffInQty", diffIn);
        r.put("dwsOutQty", dOut);
        r.put("liveOutQty", lOut);
        r.put("diffOutQty", diffOut);
        r.put("dwsAdjustAmount", dAdj);
        r.put("liveAdjustAmount", lAdj);
        r.put("diffAdjustAmount", diffAdj);
        r.put("balanced", balanced);
        if (!balanced) {
            log.warn("库存流水 DWS 对账不平 {} ~ {}：收入差 {}，发出差 {}，调整差 {}",
                    start, end, diffIn, diffOut, diffAdj);
        }
        return r;
    }

    private static double num(Object v) {
        return v == null ? 0d : ((Number) v).doubleValue();
    }
}
