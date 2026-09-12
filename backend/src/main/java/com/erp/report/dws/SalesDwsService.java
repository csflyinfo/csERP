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
 * 销售域日汇总（报表中心二期）。
 *
 * <p>两张 DWS，同一套口径来源 v_rpt_sales_detail（K8：司机签收日期/数量/含税金额，
 * 签收成本=出库成本−拒收成本，退货负向）：
 * <ul>
 *   <li>{@code rpt_dws_sales_d}：日期+客户+业务员+商品+仓库 行粒度，
 *       #10/#11/#14 商品维度汇总共用；</li>
 *   <li>{@code rpt_dws_sales_bill_d}：日期+单据 头粒度，
 *       #12/#13 的签收单数、客单价、客户数等头指标共用，避免头指标对明细 DWS 做去重。</li>
 * </ul>
 *
 * <p>幂等策略同 {@link PurchaseDwsService}：DELETE 日期分区 + INSERT...SELECT。
 */
@Service
public class SalesDwsService {

    private static final Logger log = LoggerFactory.getLogger(SalesDwsService.class);

    private final JdbcTemplate jdbc;

    public SalesDwsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 重算闭区间日期分区（行粒度 + 头粒度两张表）。 */
    @Transactional
    public int refreshRange(LocalDate start, LocalDate end) {
        jdbc.update("DELETE FROM rpt_dws_sales_d WHERE bill_date BETWEEN ? AND ?", start, end);
        int lineRows = jdbc.update("""
                INSERT INTO rpt_dws_sales_d
                    (bill_date, customer_code, customer_name, customer_level, territory,
                     route_line, salesman, goods_code, warehouse, goods_name, brand_name,
                     category_name, storage_property,
                     signed_qty_base, signed_package_qty, signed_amount, signed_cost_amount,
                     return_qty_base, return_package_qty, return_amount, return_cost_amount,
                     updated_at)
                SELECT v.bill_date, v.customer_code, MAX(v.customer_name),
                       MAX(cus.customer_level), MAX(cus.territory), MAX(cus.route_line),
                       v.salesman, v.goods_code, v.warehouse,
                       MAX(dg.goods_name), MAX(dg.brand_name), MAX(dg.category_name),
                       MAX(dg.storage_property),
                       SUM(CASE WHEN v.base_qty > 0 THEN v.base_qty ELSE 0 END),
                       SUM(CASE WHEN v.base_qty > 0 AND COALESCE(dg.large_convert_qty,0) > 0
                                THEN v.base_qty / dg.large_convert_qty
                                WHEN v.base_qty > 0 THEN v.base_qty ELSE 0 END),
                       SUM(CASE WHEN v.amount > 0 THEN v.amount ELSE 0 END),
                       SUM(CASE WHEN v.base_qty > 0 THEN v.cost_amount ELSE 0 END),
                       SUM(CASE WHEN v.base_qty < 0 THEN -v.base_qty ELSE 0 END),
                       SUM(CASE WHEN v.base_qty < 0 AND COALESCE(dg.large_convert_qty,0) > 0
                                THEN -v.base_qty / dg.large_convert_qty
                                WHEN v.base_qty < 0 THEN -v.base_qty ELSE 0 END),
                       SUM(CASE WHEN v.amount < 0 THEN -v.amount ELSE 0 END),
                       SUM(CASE WHEN v.base_qty < 0 THEN -v.cost_amount ELSE 0 END),
                       CURRENT_TIMESTAMP
                FROM v_rpt_sales_detail v
                LEFT JOIN rpt_dim_goods dg ON dg.goods_code = v.goods_code
                LEFT JOIN base_customer cus ON cus.customer_code = v.customer_code
                WHERE v.bill_date BETWEEN ? AND ?
                GROUP BY v.bill_date, v.customer_code, v.salesman, v.goods_code, v.warehouse
                """, start, end);

        jdbc.update("DELETE FROM rpt_dws_sales_bill_d WHERE bill_date BETWEEN ? AND ?", start, end);
        int billRows = jdbc.update("""
                INSERT INTO rpt_dws_sales_bill_d
                    (bill_date, bill_no, bill_type, customer_code, customer_name,
                     customer_level, territory, route_line, salesman, warehouse,
                     signed_qty_base, signed_amount, signed_cost_amount,
                     return_qty_base, return_amount, return_cost_amount, updated_at)
                SELECT v.bill_date, v.bill_no,
                       CASE WHEN MIN(v.bill_type) = '销售签收' THEN 'SIGN' ELSE 'RETURN' END,
                       v.customer_code, MAX(v.customer_name),
                       MAX(cus.customer_level), MAX(cus.territory), MAX(cus.route_line),
                       v.salesman, MAX(v.warehouse),
                       SUM(CASE WHEN v.base_qty > 0 THEN v.base_qty ELSE 0 END),
                       SUM(CASE WHEN v.amount > 0 THEN v.amount ELSE 0 END),
                       SUM(CASE WHEN v.base_qty > 0 THEN v.cost_amount ELSE 0 END),
                       SUM(CASE WHEN v.base_qty < 0 THEN -v.base_qty ELSE 0 END),
                       SUM(CASE WHEN v.amount < 0 THEN -v.amount ELSE 0 END),
                       SUM(CASE WHEN v.base_qty < 0 THEN -v.cost_amount ELSE 0 END),
                       CURRENT_TIMESTAMP
                FROM v_rpt_sales_detail v
                LEFT JOIN base_customer cus ON cus.customer_code = v.customer_code
                WHERE v.bill_date BETWEEN ? AND ?
                GROUP BY v.bill_date, v.bill_no, v.customer_code, v.salesman
                """, start, end);

        log.info("销售 DWS 重算 {} ~ {} 完成：行粒度 {} 行，头粒度 {} 行",
                start, end, lineRows, billRows);
        return lineRows;
    }

    /**
     * 对账：两张 DWS 合计 vs 视图同期合计（金额、基本数量、成本）。
     * 差异超阈值记录告警。
     */
    public Map<String, Object> reconcile(LocalDate start, LocalDate end) {
        double dAmt = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(signed_amount - return_amount),0)
                FROM rpt_dws_sales_d WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double lAmt = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount),0)
                FROM v_rpt_sales_detail WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double dQty = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(signed_qty_base - return_qty_base),0)
                FROM rpt_dws_sales_d WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double lQty = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(base_qty),0)
                FROM v_rpt_sales_detail WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double dCost = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(signed_cost_amount - return_cost_amount),0)
                FROM rpt_dws_sales_d WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double lCost = num(jdbc.queryForObject("""
                SELECT COALESCE(SUM(cost_amount),0)
                FROM v_rpt_sales_detail WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        // 头粒度表只核签收+退货单数是否与视图单据数一致
        long dBills = numLong(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rpt_dws_sales_bill_d WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        long lBills = numLong(jdbc.queryForObject("""
                SELECT COUNT(DISTINCT bill_no) FROM v_rpt_sales_detail
                WHERE bill_date BETWEEN ? AND ?
                """, Object.class, start, end));
        double diffAmt = dAmt - lAmt;
        double diffQty = dQty - lQty;
        double diffCost = dCost - lCost;
        long diffBills = dBills - lBills;
        boolean balanced = Math.abs(diffAmt) < 0.01 && Math.abs(diffQty) < 0.001
                && Math.abs(diffCost) < 0.01 && diffBills == 0;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("start", start.toString());
        r.put("end", end.toString());
        r.put("dwsAmount", dAmt);
        r.put("liveAmount", lAmt);
        r.put("diffAmount", diffAmt);
        r.put("dwsQty", dQty);
        r.put("liveQty", lQty);
        r.put("diffQty", diffQty);
        r.put("dwsCost", dCost);
        r.put("liveCost", lCost);
        r.put("diffCost", diffCost);
        r.put("dwsBills", dBills);
        r.put("liveBills", lBills);
        r.put("diffBills", diffBills);
        r.put("balanced", balanced);
        if (!balanced) {
            log.warn("销售 DWS 对账不平 {} ~ {}：金额差 {}，数量差 {}，成本差 {}，单据差 {}",
                    start, end, diffAmt, diffQty, diffCost, diffBills);
        }
        return r;
    }

    private static double num(Object v) {
        return v == null ? 0d : ((Number) v).doubleValue();
    }

    private static long numLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }
}
