package com.erp.report.inventory;

import com.erp.common.security.datascope.DataScopeService;
import com.erp.common.security.datascope.DataScopeService.ScopeClause;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 报表8｜商品进销存汇总表（月底盘库/存货核算主表，粒度：商品+仓库）。
 *
 * <p>行业恒等式：期初 + 本期收入 − 本期发出 = 期末。本期发生按单据来源类型从
 * 库存流水日汇总 rpt_dws_stock_move_d 透视（收入：采购入库/销售退货/其他入库/调入；
 * 发出：销售出库/采购退货/其他出库/调出；明细列默认折叠），数量双计量
 * （基本单位数量 + 大件数，件数 = 基本数 ÷ rpt_dim_goods.large_convert_qty）。
 *
 * <p><b>期初倒推</b>：不扫全历史流水——期初 = 当前 inv_stock_balance 快照 −
 * 本期净发生（DWS 区间聚合，索引 goods/warehouse+date），与 #9 台账同一套底层元数据。
 * 期末直接取快照；同时输出 physical_qty 与 (available+locked+frozen) 的对账差异
 * （台账勾稽同款标记），差异行由前端红字提示。默认期间自然月本月；行可钻取 #9。
 */
@Component
public class InventoryRollSummaryDefinition implements ReportDefinition {

    /** 大件换算因子（蛇形表达式片段，按商品档案逐行换算）。 */
    private static final String PACKAGE_FACTOR = """
            CASE WHEN g.large_unit IS NOT NULL AND COALESCE(g.large_convert_qty, 0) > 0
                 THEN 1.0 / g.large_convert_qty ELSE 1.0 END
            """.trim();

    private final DataScopeService dataScope;

    public InventoryRollSummaryDefinition(DataScopeService dataScope) {
        this.dataScope = dataScope;
    }

    @Override public String code() { return "inventory_roll"; }
    @Override public String name() { return "商品进销存汇总表"; }
    @Override public String viewPerm() { return "report.inventory_roll.view"; }
    @Override public boolean dws() { return true; }
    @Override public boolean summaryReport() { return true; }
    @Override public boolean naturalMonthDefault() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        return List.of(
                // 档案
                ReportColumnDef.dim("goodsCode", "商品编号"),
                ReportColumnDef.dim("goodsName", "商品名称"),
                ReportColumnDef.dim("barcode", "条码"),
                ReportColumnDef.dim("baseUnit", "基本单位"),
                ReportColumnDef.dim("warehouse", "仓库"),
                ReportColumnDef.dim("brandName", "品牌"),
                ReportColumnDef.dim("categoryName", "分类"),
                ReportColumnDef.dim("storageProperty", "存储属性"),
                // 期初
                ReportColumnDef.measure("openingQty", "期初数量", null),
                ReportColumnDef.measure("openingPackage", "期初件数", null),
                ReportColumnDef.measure("openingAmount", "期初成本金额", "VIEW_STOCK_COST"),
                // 本期收入（明细默认折叠）
                hidden("purchaseInQty", "采购入库数量", null),
                hidden("purchaseInPackage", "采购入库件数", null),
                hidden("salesReturnInQty", "销售退货入库数量", null),
                hidden("salesReturnInPackage", "销售退货入库件数", null),
                hidden("otherInQty", "其他入库数量", null),
                hidden("otherInPackage", "其他入库件数", null),
                hidden("transferInQty", "调入数量", null),
                hidden("transferInPackage", "调入件数", null),
                ReportColumnDef.measure("inQty", "收入数量小计", null),
                ReportColumnDef.measure("inPackage", "收入件数小计", null),
                ReportColumnDef.measure("inAmount", "收入金额小计", "VIEW_STOCK_COST"),
                hidden("adjustAmount", "成本调整金额", "VIEW_STOCK_COST"),
                // 本期发出（明细默认折叠）
                hidden("salesOutQty", "销售出库数量", null),
                hidden("salesOutPackage", "销售出库件数", null),
                hidden("purchaseReturnOutQty", "采购退货出库数量", null),
                hidden("purchaseReturnOutPackage", "采购退货出库件数", null),
                hidden("otherOutQty", "其他出库数量", null),
                hidden("otherOutPackage", "其他出库件数", null),
                hidden("transferOutQty", "调出数量", null),
                hidden("transferOutPackage", "调出件数", null),
                ReportColumnDef.measure("outQty", "发出数量小计", null),
                ReportColumnDef.measure("outPackage", "发出件数小计", null),
                ReportColumnDef.measure("outAmount", "发出金额小计", "VIEW_STOCK_COST"),
                // 期末
                ReportColumnDef.measure("endingQty", "期末数量", null),
                ReportColumnDef.measure("endingPackage", "期末件数", null),
                ReportColumnDef.measure("endingAmount", "期末成本金额", "VIEW_STOCK_COST"),
                hidden("reconcileDiff", "对账差异(实物−可用锁定冻结)", null),
                ReportColumnDef.dim("diffFlag", "勾稽标记")
        );
    }

    private static ReportColumnDef hidden(String field, String title, String perm) {
        return new ReportColumnDef(field, title, perm, true, false);
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        ScopeClause scope = dataScope.target().warehouse("k.warehouse")
                .goodsColumn("k.goods_code").build();
        if (scope.isDenyAll()) {
            return plan.denyAll();
        }

        // 大件换算因子用占位符注入（文本块开口分隔符后只能跟换行，不能直接拼接表达式）
        plan.detailSelect = """
                SELECT k.goods_code AS goods_code,
                       COALESCE(g.goods_name, '') AS goods_name,
                       g.barcode AS barcode, g.base_unit AS base_unit,
                       k.warehouse AS warehouse,
                       g.brand_name AS brand_name, g.category_name AS category_name,
                       g.storage_property AS storage_property,
                       COALESCE(b.physical_qty, 0) - COALESCE(p.net_qty, 0) AS opening_qty,
                       (COALESCE(b.physical_qty, 0) - COALESCE(p.net_qty, 0)) * (@pf@)
                           AS opening_package,
                       COALESCE(b.stock_amount, 0) - COALESCE(p.net_amount, 0) AS opening_amount,
                       COALESCE(p.purchase_in_qty, 0) AS purchase_in_qty,
                       COALESCE(p.purchase_in_qty, 0) * (@pf@) AS purchase_in_package,
                       COALESCE(p.sales_return_in_qty, 0) AS sales_return_in_qty,
                       COALESCE(p.sales_return_in_qty, 0) * (@pf@) AS sales_return_in_package,
                       COALESCE(p.other_in_qty, 0) AS other_in_qty,
                       COALESCE(p.other_in_qty, 0) * (@pf@) AS other_in_package,
                       COALESCE(p.transfer_in_qty, 0) AS transfer_in_qty,
                       COALESCE(p.transfer_in_qty, 0) * (@pf@) AS transfer_in_package,
                       COALESCE(p.in_qty, 0) AS in_qty,
                       COALESCE(p.in_qty, 0) * (@pf@) AS in_package,
                       COALESCE(p.in_amount, 0) AS in_amount,
                       COALESCE(p.adjust_amount, 0) AS adjust_amount,
                       COALESCE(p.sales_out_qty, 0) AS sales_out_qty,
                       COALESCE(p.sales_out_qty, 0) * (@pf@) AS sales_out_package,
                       COALESCE(p.purchase_return_out_qty, 0) AS purchase_return_out_qty,
                       COALESCE(p.purchase_return_out_qty, 0) * (@pf@)
                           AS purchase_return_out_package,
                       COALESCE(p.other_out_qty, 0) AS other_out_qty,
                       COALESCE(p.other_out_qty, 0) * (@pf@) AS other_out_package,
                       COALESCE(p.transfer_out_qty, 0) AS transfer_out_qty,
                       COALESCE(p.transfer_out_qty, 0) * (@pf@) AS transfer_out_package,
                       COALESCE(p.out_qty, 0) AS out_qty,
                       COALESCE(p.out_qty, 0) * (@pf@) AS out_package,
                       COALESCE(p.out_amount, 0) AS out_amount,
                       COALESCE(b.physical_qty, 0) AS ending_qty,
                       COALESCE(b.physical_qty, 0) * (@pf@) AS ending_package,
                       COALESCE(b.stock_amount, 0) AS ending_amount,
                       COALESCE(b.physical_qty, 0) - COALESCE(b.calc_qty, 0) AS reconcile_diff,
                       CASE WHEN ABS(COALESCE(b.physical_qty, 0) - COALESCE(b.calc_qty, 0)) > 0.0001
                            THEN '不一致' ELSE '一致' END AS diff_flag
                """.replace("@pf@", PACKAGE_FACTOR);

        // 键集：当前有快照的（商品,仓）∪ 本期有发生的（商品,仓）
        plan.fromWhere.append("""
                FROM (
                    SELECT sb.goods_code AS goods_code, sb.warehouse AS warehouse
                    FROM inv_stock_balance sb
                    GROUP BY sb.goods_code, sb.warehouse
                    UNION
                    SELECT dd.goods_code AS goods_code, dd.warehouse AS warehouse
                    FROM rpt_dws_stock_move_d dd
                    WHERE dd.move_date BETWEEN ? AND ?
                    GROUP BY dd.goods_code, dd.warehouse
                ) k
                LEFT JOIN (
                    SELECT sb.goods_code AS goods_code, sb.warehouse AS warehouse,
                           SUM(sb.physical_qty) AS physical_qty,
                           SUM(sb.available_qty + sb.locked_qty + sb.frozen_qty) AS calc_qty,
                           SUM(sb.stock_amount) AS stock_amount
                    FROM inv_stock_balance sb
                    GROUP BY sb.goods_code, sb.warehouse
                ) b ON b.goods_code = k.goods_code AND b.warehouse = k.warehouse
                LEFT JOIN (
                    SELECT d.goods_code AS goods_code, d.warehouse AS warehouse,
                           SUM(CASE WHEN d.bill_type_code IN ('CGRK', 'WMS_INBOUND')
                                    THEN d.in_qty ELSE 0 END) AS purchase_in_qty,
                           SUM(CASE WHEN d.bill_type_code = 'THRK'
                                    THEN d.in_qty ELSE 0 END) AS sales_return_in_qty,
                           -- 其他入库：其他入库单/客户拒收/盘盈(盘点IN/WMS盘盈调整)/未识别前缀
                           SUM(CASE WHEN d.bill_type_code IN ('QTRK', 'JSRK', 'PDD',
                                                              'WMS_ADJUST_GAIN', 'OTHER')
                                    THEN d.in_qty ELSE 0 END) AS other_in_qty,
                           SUM(CASE WHEN d.bill_type_code = 'DBRK'
                                    THEN d.in_qty ELSE 0 END) AS transfer_in_qty,
                           SUM(CASE WHEN d.bill_type_code = 'XSCK'
                                    THEN d.out_qty ELSE 0 END) AS sales_out_qty,
                           SUM(CASE WHEN d.bill_type_code = 'CTCK'
                                    THEN d.out_qty ELSE 0 END) AS purchase_return_out_qty,
                           -- 其他出库：其他出库/报损/盘亏(盘点OUT)/未识别前缀
                           SUM(CASE WHEN d.bill_type_code IN ('QTCK', 'BSD', 'PDD', 'OTHER')
                                    THEN d.out_qty ELSE 0 END) AS other_out_qty,
                           SUM(CASE WHEN d.bill_type_code = 'DBCK'
                                    THEN d.out_qty ELSE 0 END) AS transfer_out_qty,
                           SUM(d.in_qty) AS in_qty,
                           SUM(d.out_qty) AS out_qty,
                           SUM(CASE WHEN d.bill_type_code <> 'CGSH'
                                    THEN d.in_amount ELSE 0 END) AS in_amount,
                           SUM(d.out_amount) AS out_amount,
                           SUM(d.adjust_amount) AS adjust_amount,
                           SUM(d.in_amount - d.out_amount) AS net_amount,
                           SUM(d.in_qty - d.out_qty) AS net_qty
                    FROM rpt_dws_stock_move_d d
                    WHERE d.move_date BETWEEN ? AND ?
                    GROUP BY d.goods_code, d.warehouse
                ) p ON p.goods_code = k.goods_code AND p.warehouse = k.warehouse
                LEFT JOIN rpt_dim_goods g ON g.goods_code = k.goods_code
                WHERE 1=1
                """);
        // 参数顺序：键集 DWS 日期 → 透视 DWS 日期，再追加外层筛选
        plan.args.add(req.range().startDate());
        plan.args.add(req.range().endDate());
        plan.args.add(req.range().startDate());
        plan.args.add(req.range().endDate());

        appendOuterFilters(req, plan);
        scope.appendTo(plan.fromWhere, plan.args);

        plan.defaultOrder = "ORDER BY ending_amount DESC, k.goods_code ASC, k.warehouse ASC";
        plan.sortWhitelist.putAll(Map.ofEntries(
                Map.entry("goodsCode", "goods_code"),
                Map.entry("warehouse", "warehouse"),
                Map.entry("openingQty", "opening_qty"),
                Map.entry("openingAmount", "opening_amount"),
                Map.entry("inQty", "in_qty"),
                Map.entry("inAmount", "in_amount"),
                Map.entry("outQty", "out_qty"),
                Map.entry("outAmount", "out_amount"),
                Map.entry("endingQty", "ending_qty"),
                Map.entry("endingAmount", "ending_amount")));

        // 合计：对叶子行直接 SUM（差异标志等非可加列不取）
        plan.summaryAliases.addAll(List.of(
                "opening_qty", "opening_package", "opening_amount",
                "purchase_in_qty", "purchase_in_package",
                "sales_return_in_qty", "sales_return_in_package",
                "other_in_qty", "other_in_package",
                "transfer_in_qty", "transfer_in_package",
                "in_qty", "in_package", "in_amount", "adjust_amount",
                "sales_out_qty", "sales_out_package",
                "purchase_return_out_qty", "purchase_return_out_package",
                "other_out_qty", "other_out_package",
                "transfer_out_qty", "transfer_out_package",
                "out_qty", "out_package", "out_amount",
                "ending_qty", "ending_package", "ending_amount", "reconcile_diff"));

        for (String amountKey : List.of("openingAmount", "inAmount", "adjustAmount",
                "outAmount", "endingAmount")) {
            plan.maskOverrides.put(amountKey, "VIEW_STOCK_COST");
        }
        return plan;
    }

    /** 外层档案/仓库/结存筛选（DWS 透视在子查询内已按日期收敛，外层只过滤输出键集）。 */
    private static void appendOuterFilters(ReportQueryRequest req, Plan plan) {
        StringBuilder sql = plan.fromWhere;
        String goods = req.text("goods");
        if (goods != null) {
            sql.append(" AND (k.goods_code LIKE ? OR g.goods_name LIKE ? OR g.barcode LIKE ?) ");
            plan.args.add("%" + goods + "%");
            plan.args.add("%" + goods + "%");
            plan.args.add("%" + goods + "%");
        }
        for (String key : List.of("categoryName", "brandName", "storageProperty")) {
            String v = req.text(key);
            if (v != null) {
                String col = switch (key) {
                    case "categoryName" -> "category_name";
                    case "brandName" -> "brand_name";
                    case "storageProperty" -> "storage_property";
                    default -> key;
                };
                sql.append(" AND g.").append(col).append(" = ? ");
                plan.args.add(v);
            }
        }
        String warehouse = req.text("warehouse");
        if (warehouse != null) {
            sql.append(" AND k.warehouse = ? ");
            plan.args.add(warehouse);
        }
        String onlyNonZero = req.text("onlyNonZero");
        if ("1".equals(onlyNonZero) || "true".equalsIgnoreCase(onlyNonZero)) {
            sql.append(" AND COALESCE(b.physical_qty, 0) <> 0 ");
        }
    }
}
