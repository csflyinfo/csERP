package com.erp.finance.dayclose;

import com.erp.report.dws.ReportDimGoodsService;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商品收发存定版日余额生成（PRD-33 补充件 v1.4，V121）。
 *
 * <p>粒度：日 + 商品 + 仓库（不到批次级，Q2 终审）。必须在结账事务内、
 * 第 4/5 步 DWS 刷新与四套勾稽通过后调用，与往来/资金定版同生共死：
 * 先按日 DELETE 再 INSERT（幂等），反结时物理删除。
 *
 * <p>口径全部沿用既有权威定义，不新造：
 * <ul>
 *   <li>实物账取 {@code rpt_dws_stock_move_d}，单据类型分类与报表 #8
 *       《商品进销存汇总表》逐字一致（CGSH 采购收货不动物账，金额小计排除）；</li>
 *   <li>期末取结账时刻 {@code inv_stock_balance} 实物；成本为商品+仓移动加权；</li>
 *   <li>签收口径取 {@code rpt_dws_sales_d}（签收−退货净额），与实物发出并列冻结、
 *       互不勾稽（已出库未签收是在途）；</li>
 *   <li>档案列取 {@code rpt_dim_goods} 快照，取不到用 balance/DWS 名称兜底。</li>
 * </ul>
 *
 * <p>恒等式（与向导第 5 步库存硬门同源，不平直接抛中文异常回滚，N 行不落库）：
 * <pre>
 * 期末数量 = 期初 + 收入小计 − 发出小计            （容差 0.001）
 * 期末金额 = 期初 + 收入金额 + 成本调整 − 发出金额  （容差 0.01）
 * </pre>
 */
@Service
public class BizGoodsCloseSnapshotService {

    /** 数量勾稽容差（与 BizDayCloseService.tieStock 一致）。 */
    private static final double QTY_TOLERANCE = 0.001D;
    /** 金额勾稽容差（与 BizDayCloseConst.TIE_TOLERANCE 一致）。 */
    private static final BigDecimal AMT_TOLERANCE = new BigDecimal("0.01");

    private final JdbcTemplate jdbcTemplate;
    /**
     * 档案列取自 rpt_dim_goods（解析 unit_config 得大单位换算率）。该表由启动/30 分钟
     * 任务全量刷新，全新库可能尚未跑过；结账时刻先同步刷新一次，定版不依赖 ETL 时机，
     * 且与结账同事务（refreshAll 默认 REQUIRED），失败一起回滚。
     */
    private final ReportDimGoodsService dimGoodsService;

    public BizGoodsCloseSnapshotService(JdbcTemplate jdbcTemplate,
                                        ReportDimGoodsService dimGoodsService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dimGoodsService = dimGoodsService;
    }

    /**
     * 先删后插某日商品定版；逐行恒等式断言，不平抛 IllegalArgumentException 回滚整笔结账。
     */
    public void rebuildGoods(LocalDate date) {
        dimGoodsService.refreshAll();
        java.sql.Date d = java.sql.Date.valueOf(date);
        java.sql.Date prev = java.sql.Date.valueOf(date.minusDays(1));
        jdbcTemplate.update("DELETE FROM biz_close_goods_daily WHERE close_date = ?", d);
        jdbcTemplate.update(INSERT_SQL, d, prev, d, d, d, prev);

        Integer bad = nz(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_close_goods_daily "
                        + "WHERE close_date = ? AND (ABS(qty_diff) > 0.001 OR ABS(amount_diff) > 0.01)",
                Integer.class, d));
        if (bad > 0) {
            throw new IllegalArgumentException("商品收发存定版恒等式校验不平（"
                    + date + "，" + bad + " 行），已中止日结");
        }
    }

    /** 当日商品定版合计（RJ 单商品汇总区用）。无行返回 null。 */
    public Map<String, Object> goodsSummary(LocalDate date) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COUNT(*) AS row_count, "
                        + "COALESCE(SUM(in_qty), 0) AS in_qty, "
                        + "COALESCE(SUM(in_amount), 0) AS in_amount, "
                        + "COALESCE(SUM(adjust_amount), 0) AS adjust_amount, "
                        + "COALESCE(SUM(out_qty), 0) AS out_qty, "
                        + "COALESCE(SUM(out_amount), 0) AS out_amount, "
                        + "COALESCE(SUM(signed_qty), 0) AS signed_qty, "
                        + "COALESCE(SUM(signed_amount), 0) AS signed_amount, "
                        + "COALESCE(SUM(signed_cost_amount), 0) AS signed_cost_amount, "
                        + "COALESCE(SUM(gross_profit), 0) AS gross_profit, "
                        + "COALESCE(SUM(ending_qty), 0) AS ending_qty, "
                        + "COALESCE(SUM(ending_amount), 0) AS ending_amount, "
                        + "COALESCE(SUM(CASE WHEN negative_flag = 'Y' THEN 1 ELSE 0 END), 0) AS negative_count "
                        + "FROM biz_close_goods_daily WHERE close_date = ?",
                java.sql.Date.valueOf(date));
        return rows.isEmpty() ? null : TmsUtil.camelize(rows.get(0));
    }

    /**
     * 商品定版台账区间滚算：期初取区间内首个结息日 opening_*，分类收发与签收列 SUM，
     * 期末取区间内最末结息日 ending_*。区间内无定版行返回空。
     * keyword 模糊商品编码/名称（含条码）。
     */
    public List<Map<String, Object>> listGoodsRoll(LocalDate from, LocalDate to, String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT k.goods_code AS goods_code,
                       k.warehouse AS warehouse,
                       MAX(d.goods_name) AS goods_name,
                       MAX(d.spec) AS spec,
                       MAX(d.barcode) AS barcode,
                       MAX(d.base_unit) AS base_unit,
                       MAX(d.large_unit) AS large_unit,
                       MAX(d.large_convert_qty) AS large_convert_qty,
                       MAX(d.brand_name) AS brand_name,
                       MAX(d.category_name) AS category_name,
                       MAX(d.storage_property) AS storage_property,
                       MAX(d.negative_flag) AS negative_flag,
                       MIN(d.close_date) AS from_date,
                       MAX(d.close_date) AS to_date,
                       f.opening_qty AS opening_qty,
                       f.opening_amount AS opening_amount,
                       COALESCE(SUM(d.purchase_in_qty), 0) AS purchase_in_qty,
                       COALESCE(SUM(d.purchase_in_amount), 0) AS purchase_in_amount,
                       COALESCE(SUM(d.sales_return_in_qty), 0) AS sales_return_in_qty,
                       COALESCE(SUM(d.sales_return_in_amount), 0) AS sales_return_in_amount,
                       COALESCE(SUM(d.other_in_qty), 0) AS other_in_qty,
                       COALESCE(SUM(d.other_in_amount), 0) AS other_in_amount,
                       COALESCE(SUM(d.transfer_in_qty), 0) AS transfer_in_qty,
                       COALESCE(SUM(d.transfer_in_amount), 0) AS transfer_in_amount,
                       COALESCE(SUM(d.in_qty), 0) AS in_qty,
                       COALESCE(SUM(d.in_amount), 0) AS in_amount,
                       COALESCE(SUM(d.adjust_amount), 0) AS adjust_amount,
                       COALESCE(SUM(d.sales_out_qty), 0) AS sales_out_qty,
                       COALESCE(SUM(d.sales_out_amount), 0) AS sales_out_amount,
                       COALESCE(SUM(d.purchase_return_out_qty), 0) AS purchase_return_out_qty,
                       COALESCE(SUM(d.purchase_return_out_amount), 0) AS purchase_return_out_amount,
                       COALESCE(SUM(d.other_out_qty), 0) AS other_out_qty,
                       COALESCE(SUM(d.other_out_amount), 0) AS other_out_amount,
                       COALESCE(SUM(d.transfer_out_qty), 0) AS transfer_out_qty,
                       COALESCE(SUM(d.transfer_out_amount), 0) AS transfer_out_amount,
                       COALESCE(SUM(d.out_qty), 0) AS out_qty,
                       COALESCE(SUM(d.out_amount), 0) AS out_amount,
                       COALESCE(SUM(d.signed_qty), 0) AS signed_qty,
                       COALESCE(SUM(d.signed_amount), 0) AS signed_amount,
                       COALESCE(SUM(d.signed_cost_amount), 0) AS signed_cost_amount,
                       COALESCE(SUM(d.gross_profit), 0) AS gross_profit,
                       l.ending_qty AS ending_qty,
                       l.ending_amount AS ending_amount,
                       l.ending_cost_price AS ending_cost_price
                FROM (SELECT DISTINCT goods_code, warehouse
                        FROM biz_close_goods_daily
                       WHERE close_date BETWEEN ? AND ?) k
                JOIN biz_close_goods_daily d
                  ON d.goods_code = k.goods_code AND d.warehouse = k.warehouse
                 AND d.close_date BETWEEN ? AND ?
                JOIN (SELECT goods_code, warehouse,
                             MIN(close_date) AS first_date, MAX(close_date) AS last_date
                        FROM biz_close_goods_daily
                       WHERE close_date BETWEEN ? AND ?
                       GROUP BY goods_code, warehouse) r
                  ON r.goods_code = k.goods_code AND r.warehouse = k.warehouse
                JOIN biz_close_goods_daily f
                  ON f.goods_code = k.goods_code AND f.warehouse = k.warehouse
                 AND f.close_date = r.first_date
                JOIN biz_close_goods_daily l
                  ON l.goods_code = k.goods_code AND l.warehouse = k.warehouse
                 AND l.close_date = r.last_date
                """);
        sql.append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        java.sql.Date df = java.sql.Date.valueOf(from);
        java.sql.Date dt = java.sql.Date.valueOf(to);
        args.add(df); args.add(dt); args.add(df); args.add(dt); args.add(df); args.add(dt);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (k.goods_code LIKE ? OR d.goods_name LIKE ? OR d.barcode LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw); args.add(kw); args.add(kw);
        }
        sql.append("""
                 GROUP BY k.goods_code, k.warehouse, f.opening_qty, f.opening_amount,
                          l.ending_qty, l.ending_amount, l.ending_cost_price
                ORDER BY l.ending_amount DESC, k.goods_code, k.warehouse
                """);
        return TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
    }

    private static Integer nz(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 定版生成 SQL（参数顺序：close_date, 键集前日, 键集当日, DWS 当日, 签收 DWS 当日, 期初前日）。
     * 用普通字符串拼接显式留空格（不用文本块，避免行尾裁剪粘连）。
     */
    private static final String INSERT_SQL =
            "INSERT INTO biz_close_goods_daily ("
                    + "close_date, goods_code, warehouse, goods_name, spec, barcode, base_unit, "
                    + "large_unit, large_convert_qty, brand_name, category_name, storage_property, "
                    + "opening_qty, opening_amount, "
                    + "purchase_in_qty, purchase_in_amount, sales_return_in_qty, sales_return_in_amount, "
                    + "other_in_qty, other_in_amount, transfer_in_qty, transfer_in_amount, "
                    + "in_qty, in_amount, adjust_amount, "
                    + "sales_out_qty, sales_out_amount, purchase_return_out_qty, purchase_return_out_amount, "
                    + "other_out_qty, other_out_amount, transfer_out_qty, transfer_out_amount, "
                    + "out_qty, out_amount, "
                    + "signed_qty, signed_amount, signed_cost_amount, gross_profit, "
                    + "ending_qty, ending_amount, ending_cost_price, "
                    + "qty_diff, amount_diff, tie_flag, negative_flag, frozen_time) "
                    + "SELECT ?, k.goods_code, k.warehouse, "
                    + "COALESCE(NULLIF(g.goods_name, ''), b.goods_name, p.goods_name, '') AS goods_name, "
                    + "g.spec, g.barcode, g.base_unit, g.large_unit, g.large_convert_qty, "
                    + "g.brand_name, g.category_name, g.storage_property, "
                    + "COALESCE(pv.ending_qty, 0), COALESCE(pv.ending_amount, 0), "
                    + "COALESCE(p.purchase_in_qty, 0), COALESCE(p.purchase_in_amount, 0), "
                    + "COALESCE(p.sales_return_in_qty, 0), COALESCE(p.sales_return_in_amount, 0), "
                    + "COALESCE(p.other_in_qty, 0), COALESCE(p.other_in_amount, 0), "
                    + "COALESCE(p.transfer_in_qty, 0), COALESCE(p.transfer_in_amount, 0), "
                    + "COALESCE(p.in_qty, 0), COALESCE(p.in_amount, 0), COALESCE(p.adjust_amount, 0), "
                    + "COALESCE(p.sales_out_qty, 0), COALESCE(p.sales_out_amount, 0), "
                    + "COALESCE(p.purchase_return_out_qty, 0), COALESCE(p.purchase_return_out_amount, 0), "
                    + "COALESCE(p.other_out_qty, 0), COALESCE(p.other_out_amount, 0), "
                    + "COALESCE(p.transfer_out_qty, 0), COALESCE(p.transfer_out_amount, 0), "
                    + "COALESCE(p.out_qty, 0), COALESCE(p.out_amount, 0), "
                    + "COALESCE(s.signed_qty, 0), COALESCE(s.signed_amount, 0), "
                    + "COALESCE(s.signed_cost, 0), "
                    + "COALESCE(s.signed_amount, 0) - COALESCE(s.signed_cost, 0), "
                    + "COALESCE(b.physical_qty, 0), COALESCE(b.stock_amount, 0), "
                    + "CASE WHEN COALESCE(b.physical_qty, 0) <> 0 "
                    + "     THEN ROUND(COALESCE(b.stock_amount, 0) / b.physical_qty, 6) END, "
                    + "COALESCE(pv.ending_qty, 0) + COALESCE(p.in_qty, 0) - COALESCE(p.out_qty, 0) "
                    + "     - COALESCE(b.physical_qty, 0), "
                    + "COALESCE(pv.ending_amount, 0) + COALESCE(p.in_amount, 0) "
                    + "     + COALESCE(p.adjust_amount, 0) - COALESCE(p.out_amount, 0) "
                    + "     - COALESCE(b.stock_amount, 0), "
                    + "'Y', "
                    + "CASE WHEN COALESCE(b.physical_qty, 0) < 0 THEN 'Y' ELSE 'N' END, "
                    + "CURRENT_TIMESTAMP "
                    + "FROM ("
                    + "  SELECT goods_code, warehouse FROM biz_close_goods_daily WHERE close_date = ? "
                    + "  UNION "
                    + "  SELECT goods_code, warehouse FROM rpt_dws_stock_move_d WHERE move_date = ? "
                    + "  GROUP BY goods_code, warehouse "
                    + "  UNION "
                    + "  SELECT goods_code, warehouse FROM inv_stock_balance "
                    + "  GROUP BY goods_code, warehouse "
                    + "  HAVING COALESCE(SUM(physical_qty), 0) <> 0 "
                    + "      OR COALESCE(SUM(stock_amount), 0) <> 0"
                    + ") k "
                    + "LEFT JOIN rpt_dim_goods g ON g.goods_code = k.goods_code "
                    + "LEFT JOIN ("
                    + "  SELECT goods_code, warehouse, MAX(goods_name) AS goods_name, "
                    + "    SUM(CASE WHEN bill_type_code IN ('CGRK', 'WMS_INBOUND') THEN in_qty ELSE 0 END) AS purchase_in_qty, "
                    + "    SUM(CASE WHEN bill_type_code IN ('CGRK', 'WMS_INBOUND') THEN in_amount ELSE 0 END) AS purchase_in_amount, "
                    + "    SUM(CASE WHEN bill_type_code = 'THRK' THEN in_qty ELSE 0 END) AS sales_return_in_qty, "
                    + "    SUM(CASE WHEN bill_type_code = 'THRK' THEN in_amount ELSE 0 END) AS sales_return_in_amount, "
                    + "    SUM(CASE WHEN bill_type_code IN ('QTRK', 'JSRK', 'PDD', 'WMS_ADJUST_GAIN', 'OTHER') "
                    + "             THEN in_qty ELSE 0 END) AS other_in_qty, "
                    + "    SUM(CASE WHEN bill_type_code IN ('QTRK', 'JSRK', 'PDD', 'WMS_ADJUST_GAIN', 'OTHER') "
                    + "             THEN in_amount ELSE 0 END) AS other_in_amount, "
                    + "    SUM(CASE WHEN bill_type_code = 'DBRK' THEN in_qty ELSE 0 END) AS transfer_in_qty, "
                    + "    SUM(CASE WHEN bill_type_code = 'DBRK' THEN in_amount ELSE 0 END) AS transfer_in_amount, "
                    + "    SUM(in_qty) AS in_qty, "
                    + "    SUM(CASE WHEN bill_type_code <> 'CGSH' THEN in_amount ELSE 0 END) AS in_amount, "
                    + "    SUM(adjust_amount) AS adjust_amount, "
                    + "    SUM(CASE WHEN bill_type_code = 'XSCK' THEN out_qty ELSE 0 END) AS sales_out_qty, "
                    + "    SUM(CASE WHEN bill_type_code = 'XSCK' THEN out_amount ELSE 0 END) AS sales_out_amount, "
                    + "    SUM(CASE WHEN bill_type_code = 'CTCK' THEN out_qty ELSE 0 END) AS purchase_return_out_qty, "
                    + "    SUM(CASE WHEN bill_type_code = 'CTCK' THEN out_amount ELSE 0 END) AS purchase_return_out_amount, "
                    + "    SUM(CASE WHEN bill_type_code IN ('QTCK', 'BSD', 'PDD', 'OTHER') "
                    + "             THEN out_qty ELSE 0 END) AS other_out_qty, "
                    + "    SUM(CASE WHEN bill_type_code IN ('QTCK', 'BSD', 'PDD', 'OTHER') "
                    + "             THEN out_amount ELSE 0 END) AS other_out_amount, "
                    + "    SUM(CASE WHEN bill_type_code = 'DBCK' THEN out_qty ELSE 0 END) AS transfer_out_qty, "
                    + "    SUM(CASE WHEN bill_type_code = 'DBCK' THEN out_amount ELSE 0 END) AS transfer_out_amount, "
                    + "    SUM(out_qty) AS out_qty, SUM(out_amount) AS out_amount "
                    + "  FROM rpt_dws_stock_move_d WHERE move_date = ? GROUP BY goods_code, warehouse"
                    + ") p ON p.goods_code = k.goods_code AND p.warehouse = k.warehouse "
                    + "LEFT JOIN ("
                    + "  SELECT goods_code, warehouse, "
                    + "    COALESCE(SUM(signed_qty_base), 0) - COALESCE(SUM(return_qty_base), 0) AS signed_qty, "
                    + "    COALESCE(SUM(signed_amount), 0) - COALESCE(SUM(return_amount), 0) AS signed_amount, "
                    + "    COALESCE(SUM(signed_cost_amount), 0) - COALESCE(SUM(return_cost_amount), 0) AS signed_cost "
                    + "  FROM rpt_dws_sales_d WHERE bill_date = ? GROUP BY goods_code, warehouse"
                    + ") s ON s.goods_code = k.goods_code AND s.warehouse = k.warehouse "
                    + "LEFT JOIN ("
                    + "  SELECT goods_code, warehouse, MAX(goods_name) AS goods_name, "
                    + "    SUM(physical_qty) AS physical_qty, SUM(stock_amount) AS stock_amount "
                    + "  FROM inv_stock_balance GROUP BY goods_code, warehouse"
                    + ") b ON b.goods_code = k.goods_code AND b.warehouse = k.warehouse "
                    + "LEFT JOIN ("
                    + "  SELECT goods_code, warehouse, ending_qty, ending_amount "
                    + "  FROM biz_close_goods_daily WHERE close_date = ?"
                    + ") pv ON pv.goods_code = k.goods_code AND pv.warehouse = k.warehouse";
}
