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
 * 采购域日汇总 rpt_dws_purchase_d（粒度：日期+供应商+采购员+商品+仓库）。
 *
 * <p>口径全部来自 DWD 视图 v_rpt_purchase_detail（含税、基本单位、退货负数、仅已审核）。
 * 幂等策略同 wms_performance_daily：DELETE 日期分区 + INSERT...SELECT（H2/MySQL 双兼容）。
 * 件数在日粒度直接 SUM(base)/大单位换算率（线性换算，与逐行相加之和等价）。
 */
@Service
public class PurchaseDwsService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseDwsService.class);

    private final JdbcTemplate jdbc;

    public PurchaseDwsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 重算闭区间日期分区（夜间滚动近 3 天 / 当天增量 / 手工重算共用）。 */
    @Transactional
    public int refreshRange(LocalDate start, LocalDate end) {
        jdbc.update("DELETE FROM rpt_dws_purchase_d WHERE bill_date BETWEEN ? AND ?", start, end);
        int rows = jdbc.update("""
                INSERT INTO rpt_dws_purchase_d
                    (bill_date, supplier_code, supplier_name, supplier_type, buyer, goods_code,
                     warehouse, goods_name, brand_name, category_name, storage_property,
                     inbound_qty_base, inbound_package_qty, inbound_amount,
                     return_qty_base, return_package_qty, return_amount, updated_at)
                SELECT v.bill_date, v.supplier_code, MAX(v.supplier_name),
                       MAX(sup.supplier_type), v.buyer,
                       v.goods_code, v.warehouse,
                       MAX(dg.goods_name), MAX(dg.brand_name), MAX(dg.category_name),
                       MAX(dg.storage_property),
                       SUM(CASE WHEN v.base_qty > 0 THEN v.base_qty ELSE 0 END),
                       SUM(CASE WHEN v.base_qty > 0 AND COALESCE(dg.large_convert_qty,0) > 0
                                THEN v.base_qty / dg.large_convert_qty
                                WHEN v.base_qty > 0 THEN v.base_qty ELSE 0 END),
                       SUM(CASE WHEN v.amount > 0 THEN v.amount ELSE 0 END),
                       SUM(CASE WHEN v.base_qty < 0 THEN -v.base_qty ELSE 0 END),
                       SUM(CASE WHEN v.base_qty < 0 AND COALESCE(dg.large_convert_qty,0) > 0
                                THEN -v.base_qty / dg.large_convert_qty
                                WHEN v.base_qty < 0 THEN -v.base_qty ELSE 0 END),
                       SUM(CASE WHEN v.amount < 0 THEN -v.amount ELSE 0 END),
                       CURRENT_TIMESTAMP
                FROM v_rpt_purchase_detail v
                LEFT JOIN rpt_dim_goods dg ON dg.goods_code = v.goods_code
                LEFT JOIN base_supplier sup ON sup.supplier_code = v.supplier_code
                WHERE v.bill_date BETWEEN ? AND ?
                GROUP BY v.bill_date, v.supplier_code, v.buyer, v.goods_code, v.warehouse
                """, start, end);
        log.info("rpt_dws_purchase_d 重算 {} ~ {} 完成：{} 行", start, end, rows);
        return rows;
    }

    /**
     * 对账：DWS 合计 vs 视图同期合计（金额、基本数量）。差异超 0.01 记录告警。
     * @return 对账结果（含差异金额/数量、是否平衡）
     */
    public Map<String, Object> reconcile(LocalDate start, LocalDate end) {
        double dAmt = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(inbound_amount - return_amount),0)
                FROM rpt_dws_purchase_d WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double lAmt = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount),0)
                FROM v_rpt_purchase_detail WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double dQty = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(inbound_qty_base - return_qty_base),0)
                FROM rpt_dws_purchase_d WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double lQty = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(base_qty),0)
                FROM v_rpt_purchase_detail WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double diffAmt = dAmt - lAmt;
        double diffQty = dQty - lQty;
        boolean balanced = Math.abs(diffAmt) < 0.01 && Math.abs(diffQty) < 0.001;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("start", start.toString());
        r.put("end", end.toString());
        r.put("dwsAmount", dAmt);
        r.put("liveAmount", lAmt);
        r.put("diffAmount", diffAmt);
        r.put("dwsQty", dQty);
        r.put("liveQty", lQty);
        r.put("diffQty", diffQty);
        r.put("balanced", balanced);
        if (!balanced) {
            log.warn("采购 DWS 对账不平 {} ~ {}：金额差 {}，数量差 {}", start, end, diffAmt, diffQty);
        }
        return r;
    }

    private static double num(Object v) {
        return v == null ? 0d : ((Number) v).doubleValue();
    }
}
