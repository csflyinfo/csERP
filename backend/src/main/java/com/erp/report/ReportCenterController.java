package com.erp.report;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageResult;
import com.erp.common.security.CurrentUser;
import com.erp.common.security.FieldMasker;
import com.erp.common.security.PermissionDeniedException;
import com.erp.common.security.PermissionService;
import com.erp.common.security.ProgrammaticPerm;
import com.erp.common.security.RequirePerm;
import com.erp.report.analysis.GoodsAnalysisService;
import com.erp.report.export.ReportExportService;
import com.erp.report.meta.ReportDefinition;
import com.erp.report.meta.ReportQueryEngine;
import com.erp.report.meta.ReportRegistry;
import com.erp.report.purchase.PurchaseForecastService;
import com.erp.system.OperationLogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 报表中心统一入口（一期：采购五表 + 异步导出）。
 *
 * <p>分页/合计/导出共用 {@link ReportDefinition} 元数据：同 code 同入参，三处口径必然一致。
 * 报表权限点随菜单自动派生（{@code report.<code>.view}），统一端点按 code 编程式鉴权。
 */
@RestController
@RequestMapping("/report/center")
public class ReportCenterController {

    private final ReportRegistry registry;
    private final ReportQueryEngine engine;
    private final ReportExportService exportService;
    private final PurchaseForecastService forecastService;
    private final PermissionService permissionService;
    private final FieldMasker fieldMasker;
    private final ReportDwsSnapshotTask snapshotTask;
    private final OperationLogService opLog;
    private final GoodsAnalysisService goodsAnalysis;

    public ReportCenterController(ReportRegistry registry, ReportQueryEngine engine,
                                  ReportExportService exportService,
                                  PurchaseForecastService forecastService,
                                  PermissionService permissionService, FieldMasker fieldMasker,
                                  ReportDwsSnapshotTask snapshotTask, OperationLogService opLog,
                                  GoodsAnalysisService goodsAnalysis) {
        this.registry = registry;
        this.engine = engine;
        this.exportService = exportService;
        this.forecastService = forecastService;
        this.permissionService = permissionService;
        this.fieldMasker = fieldMasker;
        this.snapshotTask = snapshotTask;
        this.opLog = opLog;
        this.goodsAnalysis = goodsAnalysis;
    }

    @ProgrammaticPerm("报表 code → report.<code>.view（逐请求裁决）")
    @PostMapping("/{code}/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@PathVariable String code,
                                                             @RequestBody Map<String, Object> body) {
        ReportDefinition def = requireView(code);
        return ApiResponse.ok(engine.page(def, body));
    }

    @ProgrammaticPerm("报表 code → report.<code>.view（逐请求裁决）")
    @PostMapping("/{code}/summary")
    public ApiResponse<Map<String, Object>> summary(@PathVariable String code,
                                                    @RequestBody Map<String, Object> body) {
        ReportDefinition def = requireView(code);
        return ApiResponse.ok(engine.summary(def, body));
    }

    @ProgrammaticPerm("报表 code → report.<code>.view + global.export")
    @PostMapping("/{code}/export")
    public ApiResponse<Map<String, Object>> export(@PathVariable String code,
                                                   @RequestBody Map<String, Object> body) {
        ReportDefinition def = requireView(code);
        if (!permissionService.hasFunc("global.export")) {
            throw new PermissionDeniedException("无导出权限：global.export");
        }
        String filterText = body.get("filterText") == null ? null : String.valueOf(body.get("filterText"));
        String taskNo = exportService.enqueue(code, body, filterText);
        opLog.log("report." + code, com.erp.system.OperationAction.EXPORT, taskNo,
                "异步导出报表：" + def.name());
        return ApiResponse.ok(Map.of("taskNo", taskNo, "status", "CREATED",
                "message", "导出任务已提交，请到导出中心下载"));
    }

    // ========== 报表5：采购预测（交互型，不走通用定义引擎） ==========

    @RequirePerm(value = "report.purchase_forecast.view", name = "查看")
    @PostMapping("/purchase-forecast/compute")
    public ApiResponse<List<Map<String, Object>>> forecast(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = forecastService.forecast(body);
        fieldMasker.mask(rows, Map.of(
                "latestPrice", "VIEW_PURCHASE_PRICE",
                "boxPrice", "VIEW_PURCHASE_PRICE"));
        return ApiResponse.ok(rows);
    }

    @RequirePerm(value = "report.purchase_forecast.add", name = "生成采购订单")
    @PostMapping("/purchase-forecast/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(forecastService.generateOrders(body));
    }

    // ========== 报表17：商品综合分析（图表专用端点；明细表走通用引擎 goods_analysis） ==========

    @RequirePerm(value = "report.goods_analysis.view", name = "查看")
    @PostMapping("/goods-analysis/kpi")
    public ApiResponse<Map<String, Object>> goodsAnalysisKpi(@RequestBody Map<String, Object> body) {
        Map<String, Object> data = goodsAnalysis.kpi(body);
        fieldMasker.mask(data, kpiMaskOverrides());
        return ApiResponse.ok(data);
    }

    @RequirePerm(value = "report.goods_analysis.view", name = "查看")
    @PostMapping("/goods-analysis/trend")
    public ApiResponse<List<Map<String, Object>>> goodsAnalysisTrend(@RequestBody Map<String, Object> body) {
        String gran = body.get("granularity") == null ? "day" : String.valueOf(body.get("granularity"));
        if (!"day".equals(gran) && !"month".equals(gran)) {
            throw new IllegalArgumentException("趋势粒度只支持 day / month");
        }
        List<Map<String, Object>> rows = goodsAnalysis.trend(body, gran);
        fieldMasker.mask(rows, Map.of(
                "salesAmount", "VIEW_SALE_AMOUNT",
                "purchaseAmount", "VIEW_PURCHASE_AMOUNT",
                "costAmount", "VIEW_COST_AMOUNT",
                "grossProfit", "VIEW_PROFIT"));
        return ApiResponse.ok(rows);
    }

    @RequirePerm(value = "report.goods_analysis.view", name = "查看")
    @PostMapping("/goods-analysis/structure")
    public ApiResponse<Map<String, Object>> goodsAnalysisStructure(@RequestBody Map<String, Object> body) {
        Map<String, Object> data = goodsAnalysis.structure(body);
        fieldMasker.mask(data, Map.of(
                "value", "VIEW_SALE_AMOUNT",
                "salesAmount", "VIEW_SALE_AMOUNT",
                "costAmount", "VIEW_COST_AMOUNT",
                "grossProfit", "VIEW_PROFIT"));
        return ApiResponse.ok(data);
    }

    /** KPI 含本期/上期/环比三套键，逐键声明脱敏码（环比为比率不脱敏）。 */
    private static Map<String, String> kpiMaskOverrides() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("purchaseAmount", "VIEW_PURCHASE_AMOUNT");
        m.put("purchaseAmountPrev", "VIEW_PURCHASE_AMOUNT");
        m.put("salesAmount", "VIEW_SALE_AMOUNT");
        m.put("salesAmountPrev", "VIEW_SALE_AMOUNT");
        m.put("costAmount", "VIEW_COST_AMOUNT");
        m.put("costAmountPrev", "VIEW_COST_AMOUNT");
        m.put("grossProfit", "VIEW_PROFIT");
        m.put("grossProfitPrev", "VIEW_PROFIT");
        m.put("grossProfitRate", "VIEW_PROFIT");
        m.put("grossProfitRatePrev", "VIEW_PROFIT");
        m.put("endStockAmount", "VIEW_STOCK_AMOUNT");
        m.put("endStockAmountPrev", "VIEW_STOCK_AMOUNT");
        m.put("avgBillAmount", "VIEW_SALE_AMOUNT");
        m.put("avgBillAmountPrev", "VIEW_SALE_AMOUNT");
        return m;
    }

    // ========== 异步导出中心 ==========

    @RequirePerm("system.export_center.view")
    @PostMapping("/export/page")
    public ApiResponse<List<Map<String, Object>>> exportPage(@RequestBody Map<String, Object> body) {
        String keyword = body.get("keyword") == null ? null : String.valueOf(body.get("keyword")).trim();
        Object limit = body.get("pageSize");
        return ApiResponse.ok(exportService.listTasks(
                keyword == null || keyword.isEmpty() ? null : keyword,
                limit == null ? 100 : Integer.parseInt(String.valueOf(limit))));
    }

    @RequirePerm("system.export_center.export")
    @PostMapping("/export/download")
    public void download(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        String taskNo = String.valueOf(body.getOrDefault("taskNo", ""));
        File file = exportService.finishedFile(taskNo);
        try {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            String encoded = URLEncoder.encode(file.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
            // 中文名在 filename* 中；服务端文件名是 taskNo.xlsx，下载名由前端 a[download] 再指定也可
            java.nio.file.Files.copy(file.toPath(), response.getOutputStream());
            response.getOutputStream().flush();
        } catch (Exception e) {
            throw new IllegalArgumentException("文件下载失败：" + e.getMessage());
        }
    }

    // ========== 运维：手工重算（SYS_ADMIN，菜单不出现在授权树） ==========

    @RequirePerm("report.admin.view")
    @PostMapping("/admin/recompute-purchase")
    public ApiResponse<Map<String, Object>> recomputePurchase(@RequestBody Map<String, Object> body) {
        LocalDate end = body.get("endDate") == null ? LocalDate.now()
                : LocalDate.parse(String.valueOf(body.get("endDate")));
        LocalDate start = body.get("startDate") == null ? end.minusDays(2)
                : LocalDate.parse(String.valueOf(body.get("startDate")));
        if (start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于截止日期");
        if (start.isBefore(end.minusDays(31))) {
            throw new IllegalArgumentException("手工重算区间最长 31 天");
        }
        Map<String, Object> r = snapshotTask.recomputePurchase(start, end);
        opLog.log("report.admin", com.erp.system.OperationAction.UPDATE, start + "~" + end,
                "手工重算采购 DWS：" + r);
        return ApiResponse.ok(r);
    }

    @RequirePerm("report.admin.view")
    @PostMapping("/admin/rebuild-snapshot")
    public ApiResponse<Map<String, Object>> rebuildSnapshot(@RequestBody Map<String, Object> body) {
        LocalDate date = body.get("date") == null ? LocalDate.now().minusDays(1)
                : LocalDate.parse(String.valueOf(body.get("date")));
        int rows = snapshotTask.rebuildStockSnapshot(date);
        opLog.log("report.admin", com.erp.system.OperationAction.UPDATE, date.toString(),
                "手工重建库存日结快照：" + rows + " 行");
        return ApiResponse.ok(Map.of("date", date.toString(), "rows", rows));
    }

    @RequirePerm("report.admin.view")
    @PostMapping("/admin/recompute-sales")
    public ApiResponse<Map<String, Object>> recomputeSales(@RequestBody Map<String, Object> body) {
        LocalDate end = body.get("endDate") == null ? LocalDate.now()
                : LocalDate.parse(String.valueOf(body.get("endDate")));
        LocalDate start = body.get("startDate") == null ? end.minusDays(2)
                : LocalDate.parse(String.valueOf(body.get("startDate")));
        if (start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于截止日期");
        if (start.isBefore(end.minusDays(31))) {
            throw new IllegalArgumentException("手工重算区间最长 31 天");
        }
        Map<String, Object> r = snapshotTask.recomputeSales(start, end);
        opLog.log("report.admin", com.erp.system.OperationAction.UPDATE, start + "~" + end,
                "手工重算销售 DWS：" + r);
        return ApiResponse.ok(r);
    }

    @RequirePerm("report.admin.view")
    @PostMapping("/admin/rebuild-stock-move")
    public ApiResponse<Map<String, Object>> rebuildStockMove() {
        int rows = snapshotTask.rebuildStockMoveAll();
        opLog.log("report.admin", com.erp.system.OperationAction.UPDATE, "ALL",
                "全量重建库存流水 DWS：" + rows + " 行");
        return ApiResponse.ok(Map.of("rows", rows));
    }

    private ReportDefinition requireView(String code) {
        ReportDefinition def = registry.require(code);
        if (!permissionService.hasFunc(def.viewPerm())) {
            throw new PermissionDeniedException("无报表查看权限：" + def.viewPerm());
        }
        return def;
    }
}
