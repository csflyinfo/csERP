package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import com.erp.tms.TmsUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 财务报表与业财对账（总账 M6）：三表渲染、业财对账、现金流量项目补录。
 */
@RestController
@RequestMapping("/finance/gl/report")
public class GlReportController {

    private final GlReportService reportService;

    public GlReportController(GlReportService reportService) {
        this.reportService = reportService;
    }

    /** 报表数据。body: {reportCode: BS/IS/CF, period?} */
    @PostMapping("/data")
    public ApiResponse<?> data(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(reportService.report(
                TmsUtil.str(body.get("reportCode")), TmsUtil.str(body.get("period"))));
    }

    /** 报表项目公式列表（公式编辑用）。body: {reportCode} */
    @PostMapping("/formula-list")
    public ApiResponse<?> formulaList(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(reportService.formulaList(TmsUtil.str(body.get("reportCode"))));
    }

    /** 保存报表项目公式/名称/备注（公式保存时试算校验）。body: {id, itemName?, formula?, formulaBegin?, remark?} */
    @PostMapping("/formula-save")
    public ApiResponse<?> formulaSave(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(reportService.formulaSave(body));
    }

    /** 业财对账。body: {period?} */
    @PostMapping("/reconcile")
    public ApiResponse<?> reconcile(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(reportService.reconcile(TmsUtil.str(body.get("period"))));
    }

    /** 待补录现金流量项目的已过账现金分录。body: {period?} */
    @PostMapping("/cf-pending")
    public ApiResponse<?> cfPending(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(reportService.cfPending(TmsUtil.str(body.get("period"))));
    }

    /** 现金流量项目下拉。 */
    @PostMapping("/cf-items")
    public ApiResponse<?> cfItems() {
        return ApiResponse.ok(reportService.cfItems());
    }

    /** 批量补录现金流量项目。body: {items: [{entryId, cashFlowItem}]} */
    @PostMapping("/cf-fill")
    @SuppressWarnings("unchecked")
    public ApiResponse<?> cfFill(@RequestBody Map<String, Object> body) {
        Object raw = body.get("items");
        List<Map<String, Object>> items = raw instanceof List ? (List<Map<String, Object>>) raw : List.of();
        return ApiResponse.ok(reportService.cfFill(items));
    }
}
