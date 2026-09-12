package com.erp.report;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.security.FieldMasker;
import com.erp.common.security.MaskProfiles;
import com.erp.common.security.RequirePerm;
import com.erp.common.security.datascope.DataScopeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/report")
public class ReportController {
    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScope;
    private final FieldMasker fieldMasker;

    public ReportController(JdbcTemplate jdbcTemplate,
                            DataScopeService dataScope, FieldMasker fieldMasker) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataScope = dataScope;
        this.fieldMasker = fieldMasker;
    }

    // PRD-28 卡片7：工作台/图表是跨全量数据的聚合屏，不做行级数据范围（有菜单即可见聚合），
    // 金额/数量一律按字段权限脱敏；库存报表/财务报表明细走对应数据范围。
    @RequirePerm(value = "dashboard.overview.view", name = "查看")
    @GetMapping("/dashboard/summary")
    public ApiResponse<Map<String, Object>> dashboardSummary() {
        Map<String, Object> sales = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(amount),0) salesAmount, COALESCE(SUM(unpaid_amount),0) unpaidAmount, COUNT(*) salesOrderCount FROM sales_order WHERE status <> 'DELETED'");
        // 报表中心一期：采购统计从废弃的 pur_order（仅飞单兼容写入）切回现行 purchase_order，口径与图表/采购报表一致（排除已作废）
        Map<String, Object> purchase = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(amount),0) purchaseAmount, COUNT(*) purchaseOrderCount FROM purchase_order WHERE status <> 'CANCELLED'");
        Map<String, Object> stock = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(stock_amount),0) stockAmount, COALESCE(SUM(available_qty),0) availableQty FROM inv_stock_balance");
        Map<String, Object> finance = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(unreceived_amount),0) arBalance, COUNT(*) arCount FROM fin_ar WHERE status <> 'VERIFIED'");
        Map<String, Object> ap = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(unpaid_amount),0) apBalance, COUNT(*) apCount FROM fin_ap WHERE status <> 'VERIFIED'");
        Map<String, Object> tasks = jdbcTemplate.queryForMap("""
                SELECT (SELECT COUNT(*) FROM sys_import_task_runtime WHERE status='FINISHED') importFinishedCount,
                       (SELECT COUNT(*) FROM sys_export_task_runtime WHERE status='FINISHED') exportFinishedCount,
                       (SELECT COUNT(*) FROM sys_operation_log_runtime) operationLogCount
                """);
        Map<String, Object> result = GenericResult.row(
                "salesAmount", sales.get("SALESAMOUNT"),
                "purchaseAmount", purchase.get("PURCHASEAMOUNT"),
                "stockAmount", stock.get("STOCKAMOUNT"),
                "availableQty", stock.get("AVAILABLEQTY"),
                "arBalance", finance.get("ARBALANCE"),
                "apBalance", ap.get("APBALANCE"),
                "unpaidAmount", sales.get("UNPAIDAMOUNT"),
                "salesOrderCount", sales.get("SALESORDERCOUNT"),
                "purchaseOrderCount", purchase.get("PURCHASEORDERCOUNT"),
                "arCount", finance.get("ARCOUNT"),
                "apCount", ap.get("APCOUNT"),
                "importFinishedCount", tasks.get("IMPORTFINISHEDCOUNT"),
                "exportFinishedCount", tasks.get("EXPORTFINISHEDCOUNT"),
                "operationLogCount", tasks.get("OPERATIONLOGCOUNT")
        );
        // 销售/采购/应收/库存金额按字段权限脱敏（注册表已覆盖 salesAmount/purchaseAmount/
        // stockAmount/availableQty/arBalance/apBalance；销售未收货款归应收余额字段）
        fieldMasker.mask(result, Map.of("unpaidAmount", "VIEW_AR_BALANCE"));
        return ApiResponse.ok(result);
    }

    @RequirePerm(value = "report.sales.view", name = "查看")
    @PostMapping("/sales/page")
    public ApiResponse<PageResult<Map<String, Object>>> salesReport(@RequestBody PageRequest request) {
        // PRD-28 卡片6：报表中心是绕开列表的旁路出口，与销售订单列表同口径接 DataScope + 字段脱敏
        var scope = dataScope.target()
                .warehouse("so.warehouse").customer("so.customer").salesman("so.salesman")
                .creator("so.creator_name")
                .goodsLines("so.order_id", "sales_order_detail", "order_id")
                .build();
        List<Object> whereArgs = new ArrayList<>();
        StringBuilder scopeSql = new StringBuilder();
        scope.appendTo(scopeSql, whereArgs);
        // SELECT 列里的可见行金额子查询参数必须排在 WHERE 参数前（JDBC 按出现顺序绑定）
        List<Object> selectArgs = new ArrayList<>();
        StringBuilder q = new StringBuilder("""
                SELECT so.bill_date bill_date,
                       so.customer customer,
                       so.salesman salesman,
                       so.warehouse warehouse,
                       so.amount sales_amount,
                       so.paid_amount paid_amount,
                       so.unpaid_amount unpaid_amount,
                       so.amount * 0.12 gross_profit,
                       so.status status
                """);
        if (scope.isGoodsRestricted()) {
            q.append(", (SELECT COALESCE(SUM(d.amount),0) FROM sales_order_detail d WHERE d.order_id = so.order_id AND ");
            scope.appendGoodsCodeCondition("d.goods_code", q, selectArgs);
            q.append(") AS scoped_amount");
        }
        q.append(" FROM sales_order so WHERE 1=1").append(scopeSql)
                .append(" ORDER BY so.bill_date DESC, so.order_no DESC");
        List<Object> queryArgs = new ArrayList<>(selectArgs);
        queryArgs.addAll(whereArgs);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(q.toString(), queryArgs.toArray());
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            // 商品范围受限时，金额按可见明细行汇总（与销售订单列表同口径，方案 §5.3.1）
            if (scope.isGoodsRestricted() && row.get("scopedAmount") != null) {
                row.put("salesAmount", row.get("scopedAmount"));
            }
            row.remove("scopedAmount");
            mapped.add(row);
        }
        fieldMasker.mask(mapped, MaskProfiles.SALES_BILL);
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @RequirePerm(value = "report.purchase.view", name = "查看")
    @PostMapping("/purchase/page")
    public ApiResponse<PageResult<Map<String, Object>>> purchaseReport(@RequestBody PageRequest request) {
        // PRD-28 卡片6：与采购订单列表同口径（仓库/供应商/采购员/建档人 + 商品分类/品牌明细行）
        var scope = dataScope.target()
                .warehouse("po.warehouse").supplier("po.supplier_name").salesman("po.buyer")
                .creator("po.creator_name")
                .goodsLines("po.order_id", "purchase_order_detail", "order_id")
                .build();
        List<Object> whereArgs = new ArrayList<>();
        StringBuilder scopeSql = new StringBuilder();
        scope.appendTo(scopeSql, whereArgs);
        List<Object> selectArgs = new ArrayList<>();
        StringBuilder q = new StringBuilder("""
                SELECT po.bill_date bill_date,
                       po.supplier_name supplier,
                       po.buyer buyer,
                       po.warehouse warehouse,
                       po.amount purchase_amount,
                       po.inbound_amount inbound_amount,
                       po.payment_status payment_status,
                       po.status status
                """);
        if (scope.isGoodsRestricted()) {
            q.append(", (SELECT COALESCE(SUM(d.amount),0) FROM purchase_order_detail d WHERE d.order_id = po.order_id AND ");
            scope.appendGoodsCodeCondition("d.goods_code", q, selectArgs);
            q.append(") AS scoped_amount");
        }
        q.append(" FROM purchase_order po WHERE 1=1").append(scopeSql)
                .append(" ORDER BY po.bill_date DESC, po.order_no DESC");
        List<Object> queryArgs = new ArrayList<>(selectArgs);
        queryArgs.addAll(whereArgs);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(q.toString(), queryArgs.toArray());
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            if (scope.isGoodsRestricted() && row.get("scopedAmount") != null) {
                row.put("purchaseAmount", row.get("scopedAmount"));
            }
            row.remove("scopedAmount");
            mapped.add(row);
        }
        fieldMasker.mask(mapped, MaskProfiles.PURCHASE_BILL);
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @RequirePerm(value = "report.inventory.view", name = "查看")
    @PostMapping("/stock/page")
    public ApiResponse<PageResult<Map<String, Object>>> stockReport(@RequestBody PageRequest request) {
        // PRD-28 卡片7：库存报表强制按仓库 + 商品分类/品牌范围（与库存余额列表同口径，
        // 库存表无建档人列，未配范围角色 1=0 fail-closed）；成本/数量按全局注册表脱敏
        var scope = dataScope.target()
                .warehouse("sb.warehouse").goodsColumn("sb.goods_code")
                .build();
        if (scope.isDenyAll()) {
            return ApiResponse.ok(PageResult.of(List.of(), request));
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT goods_code goodsCode,
                       goods_name goodsName,
                       warehouse,
                       physical_qty physicalQty,
                       locked_qty lockedQty,
                       available_qty availableQty,
                       cost_price costPrice,
                       stock_amount stockAmount,
                       last_inout_time lastInoutTime
                FROM inv_stock_balance sb
                WHERE 1=1
                """);
        scope.appendTo(sql, args);
        sql.append(" ORDER BY stock_amount DESC");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        fieldMasker.mask(rows);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @RequirePerm(value = "report.finance.view", name = "查看")
    @PostMapping("/finance/page")
    public ApiResponse<PageResult<Map<String, Object>>> financeReport(@RequestBody PageRequest request) {
        // PRD-28 卡片7：应收行走客户/业务员数据范围，应付行走供应商数据范围（两分支独立门控，
        // 参照财务收付款合并列表口径）；金额按应收/应付余额字段分别脱敏
        List<Map<String, Object>> rows = new ArrayList<>();
        var arScope = dataScope.target().customer("customer").salesman("salesman").build();
        if (!arScope.isDenyAll()) {
            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    SELECT '应收' reportType,
                           customer objectName,
                           ar_amount amount,
                           received_amount verifiedAmount,
                           unreceived_amount balance,
                           due_date dueDate,
                           status
                    FROM fin_ar
                    WHERE 1=1
                    """);
            arScope.appendTo(sql, args);
            List<Map<String, Object>> arRows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
            fieldMasker.mask(arRows, Map.of(
                    "amount", "VIEW_AR_BALANCE",
                    "verifiedAmount", "VIEW_AR_BALANCE",
                    "balance", "VIEW_AR_BALANCE"));
            rows.addAll(arRows);
        }
        var apScope = dataScope.target().supplier("supplier").build();
        if (!apScope.isDenyAll()) {
            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    SELECT '应付' reportType,
                           supplier objectName,
                           ap_amount amount,
                           paid_amount verifiedAmount,
                           unpaid_amount balance,
                           due_date dueDate,
                           status
                    FROM fin_ap
                    WHERE 1=1
                    """);
            apScope.appendTo(sql, args);
            List<Map<String, Object>> apRows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
            fieldMasker.mask(apRows, Map.of(
                    "amount", "VIEW_AP_BALANCE",
                    "verifiedAmount", "VIEW_AP_BALANCE",
                    "balance", "VIEW_AP_BALANCE"));
            rows.addAll(apRows);
        }
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    // ========== 图表数据接口 ==========

    @RequirePerm(value = "report.chart.view", name = "查看")
    @GetMapping("/chart/sales-trend")
    public ApiResponse<List<Map<String, Object>>> salesTrend() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bill_date date, COALESCE(SUM(amount),0) amount, COUNT(*) count
                FROM sales_order WHERE status <> 'DELETED' GROUP BY bill_date ORDER BY bill_date DESC LIMIT 30
                """);
        fieldMasker.mask(rows, Map.of("amount", "VIEW_SALE_AMOUNT"));
        return ApiResponse.ok(rows);
    }

    @RequirePerm(value = "report.chart.view", name = "查看")
    @GetMapping("/chart/purchase-trend")
    public ApiResponse<List<Map<String, Object>>> purchaseTrend() {
        // 报表中心一期：切回现行 purchase_order（pur_order 仅飞单兼容写入，标准采购单不入表）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bill_date date, COALESCE(SUM(amount),0) amount, COUNT(*) count
                FROM purchase_order WHERE status <> 'CANCELLED' GROUP BY bill_date ORDER BY bill_date DESC LIMIT 30
                """);
        fieldMasker.mask(rows, Map.of("amount", "VIEW_PURCHASE_AMOUNT"));
        return ApiResponse.ok(rows);
    }

    @RequirePerm(value = "report.chart.view", name = "查看")
    @GetMapping("/chart/stock-distribution")
    public ApiResponse<List<Map<String, Object>>> stockDistribution() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT warehouse name, COALESCE(SUM(stock_amount),0) value
                FROM inv_stock_balance GROUP BY warehouse ORDER BY value DESC
                """);
        // 与库存余额列表一致：stock_amount 归库存数量金额字段
        fieldMasker.mask(rows, Map.of("value", "VIEW_STOCK_AMOUNT"));
        return ApiResponse.ok(rows);
    }

    @RequirePerm(value = "report.chart.view", name = "查看")
    @GetMapping("/chart/customer-sales")
    public ApiResponse<List<Map<String, Object>>> customerSales() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT customer name, COALESCE(SUM(amount),0) value
                FROM sales_order WHERE status <> 'DELETED' GROUP BY customer ORDER BY value DESC LIMIT 10
                """);
        fieldMasker.mask(rows, Map.of("value", "VIEW_SALE_AMOUNT"));
        return ApiResponse.ok(rows);
    }

    @RequirePerm(value = "report.chart.view", name = "查看")
    @GetMapping("/chart/finance-overview")
    public ApiResponse<Map<String, Object>> financeOverview() {
        Map<String, Object> ar = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(ar_amount),0) total, COALESCE(SUM(received_amount),0) received, COALESCE(SUM(unreceived_amount),0) unreceived FROM fin_ar");
        Map<String, Object> ap = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(ap_amount),0) total, COALESCE(SUM(paid_amount),0) paid, COALESCE(SUM(unpaid_amount),0) unpaid FROM fin_ap");
        Map<String, Object> fund = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount ELSE -amount END),0) balance FROM fin_fund_ledger");
        Map<String, Object> result = new LinkedHashMap<>(Map.of(
                "arTotal", ar.get("TOTAL"), "arReceived", ar.get("RECEIVED"), "arUnreceived", ar.get("UNRECEIVED"),
                "apTotal", ap.get("TOTAL"), "apPaid", ap.get("PAID"), "apUnpaid", ap.get("UNPAID"),
                "fundBalance", fund.get("BALANCE")
        ));
        fieldMasker.mask(result, Map.of(
                "arTotal", "VIEW_AR_BALANCE", "arReceived", "VIEW_AR_BALANCE", "arUnreceived", "VIEW_AR_BALANCE",
                "apTotal", "VIEW_AP_BALANCE", "apPaid", "VIEW_AP_BALANCE", "apUnpaid", "VIEW_AP_BALANCE"));
        // fundBalance 已由注册表绑定 VIEW_FUND_ACCOUNT_BALANCE
        return ApiResponse.ok(result);
    }

    @RequirePerm(value = "report.chart.view", name = "查看")
    @GetMapping("/chart/category-sales")
    public ApiResponse<List<Map<String, Object>>> categorySales() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT g.category_name name, COALESCE(SUM(s.amount),0) value
                FROM sales_order_detail s
                JOIN base_goods g ON s.goods_code = g.goods_code
                JOIN sales_order o ON s.order_id = o.order_id
                WHERE o.status <> 'DELETED'
                GROUP BY g.category_name ORDER BY value DESC LIMIT 10
                """);
        fieldMasker.mask(rows, Map.of("value", "VIEW_SALE_AMOUNT"));
        return ApiResponse.ok(rows);
    }

    /** H2 不带引号的别名会被拉成大写破坏驼峰，SQL 统一蛇形别名后由此转回驼峰。 */
    private static Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : k.toCharArray()) {
                if (c == '_') { upper = true; continue; }
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            out.put(sb.toString(), e.getValue());
        }
        return out;
    }
}
