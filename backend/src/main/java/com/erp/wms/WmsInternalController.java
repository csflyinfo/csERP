package com.erp.wms;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * WMS V1.5 库内作业 PC 端点（PRD-28）。
 *
 * <p>前缀 /api/wms/internal。覆盖补货、移库、冻结、调整、报损、组装拆卸、盘点、效期、看板与库存查询。
 */
@RestController
@RequestMapping("/wms/internal")
public class WmsInternalController {

    private final WmsInternalService service;
    private final SysParamService params;

    public WmsInternalController(WmsInternalService service, SysParamService params) {
        this.service = service;
        this.params = params;
    }

    // -------- 补货 --------

    @PostMapping("/replenish/page")
    public ApiResponse<PageResult<Map<String, Object>>> replenishPage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(PageResult.of(service.listReplenish(
                str(f.get("status")), str(f.get("assignee"))), request));
    }

    @PostMapping("/replenish/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestBody(required = false) Map<String, Object> req) {
        String warehouse = req == null ? "总仓" : strOr(req.get("warehouse"), "总仓");
        int threshold = req != null && req.get("threshold") != null
                ? TmsUtil.toInt(req.get("threshold"))
                : params.getInt("WMS_REPLENISH_THRESHOLD", 30, 0, 100);
        int created = service.generateActiveReplenishment(warehouse, threshold);
        return ApiResponse.ok(Map.of("created", created, "warehouse", warehouse));
    }

    @PostMapping("/replenish/urgent")
    public ApiResponse<Map<String, Object>> urgent(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.urgentReplenish(
                TmsUtil.str(req.get("goodsCode")), TmsUtil.str(req.get("batchNo")),
                TmsUtil.str(req.get("toBin")), TmsUtil.toBd(req.get("qty")),
                TmsUtil.str(req.get("waveId")), TmsUtil.currentUser()));
    }

    @PostMapping("/replenish/claim")
    public ApiResponse<Map<String, Object>> claimReplenish(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.claimReplenish(TmsUtil.str(req.get("taskId")), TmsUtil.currentUser()));
    }

    @PostMapping("/replenish/complete")
    public ApiResponse<Map<String, Object>> completeReplenish(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.completeReplenish(TmsUtil.str(req.get("taskId")), TmsUtil.currentUser()));
    }

    // -------- 移库 --------

    @PostMapping("/move/page")
    public ApiResponse<PageResult<Map<String, Object>>> movePage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(PageResult.of(service.listMove(
                TmsUtil.str(f.get("status")), TmsUtil.str(f.get("keyword"))), request));
    }

    @PostMapping("/move/create")
    public ApiResponse<Map<String, Object>> createMove(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.createMove(req, TmsUtil.currentUser()));
    }

    @PostMapping("/move/complete")
    public ApiResponse<Map<String, Object>> completeMove(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.completeMove(TmsUtil.str(req.get("taskId")), TmsUtil.currentUser()));
    }

    // -------- 冻结 --------

    @PostMapping("/freeze/page")
    public ApiResponse<PageResult<Map<String, Object>>> freezePage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(PageResult.of(service.listFreeze(TmsUtil.str(f.get("status"))), request));
    }

    @PostMapping("/freeze/create")
    public ApiResponse<Map<String, Object>> freeze(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.freeze(req, TmsUtil.currentUser()));
    }

    @PostMapping("/freeze/unfreeze")
    public ApiResponse<Map<String, Object>> unfreeze(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.unfreeze(TmsUtil.str(req.get("freezeId")), TmsUtil.currentUser()));
    }

    // -------- 调整 --------

    @PostMapping("/adjust/page")
    public ApiResponse<PageResult<Map<String, Object>>> adjustPage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(PageResult.of(service.listAdjust(TmsUtil.str(f.get("status"))), request));
    }

    @PostMapping("/adjust/create")
    public ApiResponse<Map<String, Object>> createAdjust(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.createAdjust(req, TmsUtil.currentUser()));
    }

    @PostMapping("/adjust/approve")
    public ApiResponse<Map<String, Object>> approveAdjust(@RequestBody Map<String, Object> req) {
        boolean approved = !"false".equalsIgnoreCase(TmsUtil.str(req.get("approved")))
                && !"N".equalsIgnoreCase(TmsUtil.str(req.get("approved")));
        return ApiResponse.ok(service.approveAdjust(TmsUtil.str(req.get("adjustId")), approved,
                TmsUtil.currentUser(), TmsUtil.str(req.get("remark"))));
    }

    // -------- 报损 --------

    @PostMapping("/damage/page")
    public ApiResponse<PageResult<Map<String, Object>>> damagePage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(PageResult.of(service.listDamage(TmsUtil.str(f.get("status"))), request));
    }

    @PostMapping("/damage/create")
    public ApiResponse<Map<String, Object>> createDamage(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.createDamage(req, TmsUtil.currentUser()));
    }

    @PostMapping("/damage/approve")
    public ApiResponse<Map<String, Object>> approveDamage(@RequestBody Map<String, Object> req) {
        boolean approved = !"false".equalsIgnoreCase(TmsUtil.str(req.get("approved")))
                && !"N".equalsIgnoreCase(TmsUtil.str(req.get("approved")));
        return ApiResponse.ok(service.approveDamage(TmsUtil.str(req.get("damageId")), approved, TmsUtil.currentUser()));
    }

    // -------- 组装拆卸 --------

    @PostMapping("/assembly/page")
    public ApiResponse<PageResult<Map<String, Object>>> assemblyPage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(PageResult.of(service.listAssembly(TmsUtil.str(f.get("status"))), request));
    }

    @PostMapping("/assembly/create")
    public ApiResponse<Map<String, Object>> createAssembly(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.createAssembly(req));
    }

    @PostMapping("/assembly/complete")
    public ApiResponse<Map<String, Object>> completeAssembly(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.completeAssembly(TmsUtil.str(req.get("taskId"))));
    }

    // -------- 盘点 --------

    @PostMapping("/stocktake/page")
    public ApiResponse<PageResult<Map<String, Object>>> stocktakePage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(PageResult.of(service.listStocktake(TmsUtil.str(f.get("status"))), request));
    }

    @PostMapping("/stocktake/create")
    public ApiResponse<Map<String, Object>> createStocktake(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.createStocktake(req, TmsUtil.currentUser()));
    }

    @GetMapping("/stocktake/bins")
    public ApiResponse<List<Map<String, Object>>> stocktakeBins(@RequestParam String taskId) {
        return ApiResponse.ok(service.stocktakeBins(taskId));
    }

    @PostMapping("/stocktake/finish")
    public ApiResponse<Map<String, Object>> finishStocktake(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.finishStocktake(TmsUtil.str(req.get("taskId")), TmsUtil.currentUser()));
    }

    // -------- 效期 --------

    @PostMapping("/expiry/refresh")
    public ApiResponse<Map<String, Object>> refreshExpiry(@RequestBody(required = false) Map<String, Object> req) {
        String warehouse = req == null ? "总仓" : strOr(req.get("warehouse"), "总仓");
        return ApiResponse.ok(Map.of("refreshed", service.refreshExpiryAlerts(warehouse), "warehouse", warehouse));
    }

    @PostMapping("/expiry/page")
    public ApiResponse<PageResult<Map<String, Object>>> expiryPage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(PageResult.of(service.listExpiry(
                TmsUtil.str(f.get("alertLevel")), TmsUtil.str(f.get("handleStatus"))), request));
    }

    @PostMapping("/expiry/handle")
    public ApiResponse<Map<String, Object>> handleExpiry(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.handleExpiry(TmsUtil.str(req.get("alertId")),
                TmsUtil.str(req.get("handleStatus")), TmsUtil.str(req.get("handleRemark"))));
    }

    // -------- 看板 / 绩效 / 库存查询 --------

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(@RequestParam(defaultValue = "总仓") String warehouse) {
        return ApiResponse.ok(service.dashboard(warehouse));
    }

    @PostMapping("/performance/refresh")
    public ApiResponse<Map<String, Object>> refreshPerformance(@RequestBody(required = false) Map<String, Object> req) {
        LocalDate date = req != null && req.get("date") != null ? LocalDate.parse(TmsUtil.str(req.get("date"))) : LocalDate.now();
        return ApiResponse.ok(service.refreshPerformance(date));
    }

    @PostMapping("/performance/page")
    public ApiResponse<PageResult<Map<String, Object>>> performancePage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        LocalDate from = f.get("from") != null ? LocalDate.parse(TmsUtil.str(f.get("from"))) : LocalDate.now().minusDays(30);
        LocalDate to = f.get("to") != null ? LocalDate.parse(TmsUtil.str(f.get("to"))) : LocalDate.now();
        return ApiResponse.ok(PageResult.of(service.listPerformance(from, to), request));
    }

    @PostMapping("/stock-query")
    public ApiResponse<PageResult<Map<String, Object>>> stockQuery(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        String warehouse = strOr(f.get("warehouse"), "总仓");
        boolean discrepancy = "Y".equalsIgnoreCase(TmsUtil.str(f.get("discrepancyOnly")))
                || Boolean.TRUE.equals(f.get("discrepancyOnly"));
        return ApiResponse.ok(PageResult.of(service.binStockQuery(
                warehouse, TmsUtil.str(f.get("keyword")), discrepancy), request));
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String strOr(Object o, String dft) {
        String s = str(o);
        return s.isEmpty() ? dft : s;
    }
}
