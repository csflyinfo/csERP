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
import java.util.UUID;

@RestController
@RequestMapping("/report")
public class ReportController {
    private final JdbcTemplate jdbcTemplate;
    private final com.erp.system.OperationLogService opLog;
    private final DataScopeService dataScope;
    private final FieldMasker fieldMasker;

    public ReportController(JdbcTemplate jdbcTemplate, com.erp.system.OperationLogService opLog,
                            DataScopeService dataScope, FieldMasker fieldMasker) {
        this.jdbcTemplate = jdbcTemplate;
        this.opLog = opLog;
        this.dataScope = dataScope;
        this.fieldMasker = fieldMasker;
    }

    @GetMapping("/dashboard/summary")
    public ApiResponse<Map<String, Object>> dashboardSummary() {
        Map<String, Object> sales = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(amount),0) salesAmount, COALESCE(SUM(unpaid_amount),0) unpaidAmount, COUNT(*) salesOrderCount FROM sales_order");
        Map<String, Object> purchase = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(amount),0) purchaseAmount, COUNT(*) purchaseOrderCount FROM pur_order");
        Map<String, Object> stock = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(stock_amount),0) stockAmount, COALESCE(SUM(available_qty),0) availableQty FROM inv_stock_balance");
        Map<String, Object> finance = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(unreceived_amount),0) arBalance, COUNT(*) arCount FROM fin_ar WHERE status <> 'VERIFIED'");
        Map<String, Object> ap = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(unpaid_amount),0) apBalance, COUNT(*) apCount FROM fin_ap WHERE status <> 'VERIFIED'");
        Map<String, Object> tasks = jdbcTemplate.queryForMap("""
                SELECT (SELECT COUNT(*) FROM sys_import_task_runtime WHERE status='FINISHED') importFinishedCount,
                       (SELECT COUNT(*) FROM sys_export_task_runtime WHERE status='FINISHED') exportFinishedCount,
                       (SELECT COUNT(*) FROM sys_operation_log_runtime) operationLogCount
                """);
        return ApiResponse.ok(GenericResult.row(
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
        ));
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

    @PostMapping("/stock/page")
    public ApiResponse<PageResult<Map<String, Object>>> stockReport(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT goods_code goodsCode,
                       goods_name goodsName,
                       warehouse,
                       physical_qty physicalQty,
                       locked_qty lockedQty,
                       available_qty availableQty,
                       cost_price costPrice,
                       stock_amount stockAmount,
                       last_inout_time lastInoutTime
                FROM inv_stock_balance
                ORDER BY stock_amount DESC
                """), request));
    }

    @PostMapping("/finance/page")
    public ApiResponse<PageResult<Map<String, Object>>> financeReport(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT '应收' reportType,
                       customer objectName,
                       ar_amount amount,
                       received_amount verifiedAmount,
                       unreceived_amount balance,
                       due_date dueDate,
                       status
                FROM fin_ar
                UNION ALL
                SELECT '应付' reportType,
                       supplier objectName,
                       ap_amount amount,
                       paid_amount verifiedAmount,
                       unpaid_amount balance,
                       due_date dueDate,
                       status
                FROM fin_ap
                """), request));
    }

    // ========== 图表数据接口 ==========

    @GetMapping("/chart/sales-trend")
    public ApiResponse<List<Map<String, Object>>> salesTrend() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bill_date date, COALESCE(SUM(amount),0) amount, COUNT(*) count
                FROM sales_order WHERE status <> 'DELETED' GROUP BY bill_date ORDER BY bill_date DESC LIMIT 30
                """);
        return ApiResponse.ok(rows);
    }

    @GetMapping("/chart/purchase-trend")
    public ApiResponse<List<Map<String, Object>>> purchaseTrend() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bill_date date, COALESCE(SUM(amount),0) amount, COUNT(*) count
                FROM pur_order WHERE status <> 'DELETED' GROUP BY bill_date ORDER BY bill_date DESC LIMIT 30
                """);
        return ApiResponse.ok(rows);
    }

    @GetMapping("/chart/stock-distribution")
    public ApiResponse<List<Map<String, Object>>> stockDistribution() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT warehouse name, COALESCE(SUM(stock_amount),0) value
                FROM inv_stock_balance GROUP BY warehouse ORDER BY value DESC
                """);
        return ApiResponse.ok(rows);
    }

    @GetMapping("/chart/customer-sales")
    public ApiResponse<List<Map<String, Object>>> customerSales() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT customer name, COALESCE(SUM(amount),0) value
                FROM sales_order WHERE status <> 'DELETED' GROUP BY customer ORDER BY value DESC LIMIT 10
                """);
        return ApiResponse.ok(rows);
    }

    @GetMapping("/chart/finance-overview")
    public ApiResponse<Map<String, Object>> financeOverview() {
        Map<String, Object> ar = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(ar_amount),0) total, COALESCE(SUM(received_amount),0) received, COALESCE(SUM(unreceived_amount),0) unreceived FROM fin_ar");
        Map<String, Object> ap = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(ap_amount),0) total, COALESCE(SUM(paid_amount),0) paid, COALESCE(SUM(unpaid_amount),0) unpaid FROM fin_ap");
        Map<String, Object> fund = jdbcTemplate.queryForMap("SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount ELSE -amount END),0) balance FROM fin_fund_ledger");
        return ApiResponse.ok(Map.of(
                "arTotal", ar.get("TOTAL"), "arReceived", ar.get("RECEIVED"), "arUnreceived", ar.get("UNRECEIVED"),
                "apTotal", ap.get("TOTAL"), "apPaid", ap.get("PAID"), "apUnpaid", ap.get("UNPAID"),
                "fundBalance", fund.get("BALANCE")
        ));
    }

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
        return ApiResponse.ok(rows);
    }

    @RequirePerm(value = "global.export", global = true, name = "导出", type = "ACTION")
    @PostMapping("/export")
    public ApiResponse<Map<String, Object>> exportReport(@RequestBody Map<String, Object> request) {
        String taskId = "EXP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String taskNo = "EXP" + System.currentTimeMillis();
        String reportName = String.valueOf(request.getOrDefault("reportName", "报表导出"));
        String moduleCode = String.valueOf(request.getOrDefault("moduleCode", "report"));
        String filterText = String.valueOf(request.getOrDefault("filters", Map.of()));
        String fileName = reportName + "_" + taskNo + ".xlsx";
        jdbcTemplate.update("""
                INSERT INTO sys_export_task_runtime(task_id, task_no, report_name, module_code, filter_text, file_name, status, created_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, 'FINISHED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, taskId, taskNo, reportName, moduleCode, filterText, fileName);
        logExport(taskNo, reportName);
        return ApiResponse.ok(GenericResult.row(
                "taskNo", taskNo,
                "status", "FINISHED",
                "fileName", fileName,
                "message", "报表导出任务已创建，请到导出中心下载"
        ));
    }

    private void logExport(String taskNo, String reportName) {
        // PRD-31 操作日志统一走 OperationLogService（真实操作人/IP/耗时/中文名）。
        opLog.log("report.export", com.erp.system.OperationAction.EXPORT, taskNo, "导出报表：" + reportName);
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
