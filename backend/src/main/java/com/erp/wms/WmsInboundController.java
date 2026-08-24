package com.erp.wms;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.tms.TmsUtil;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * WMS V1.5 入库管理 PC 端点（PRD-28）。
 *
 * <p>前缀 /api/wms/inbound。覆盖采购到货/其他入库建任务、收货、复检、上架。
 * PDA 端点见 {@link WmsAppController}（/wms/app/...）。
 */
@RestController
@RequestMapping("/wms/inbound")
public class WmsInboundController {

    private final WmsInboundService service;

    public WmsInboundController(WmsInboundService service) {
        this.service = service;
    }

    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(PageResult.of(service.list(
                TmsUtil.str(f.get("status")), TmsUtil.str(f.get("inboundType")),
                TmsUtil.str(f.get("keyword"))), request));
    }

    @GetMapping("/detail")
    public ApiResponse<Map<String, Object>> detail(@RequestParam String taskId) {
        return ApiResponse.ok(service.detail(taskId));
    }

    /** 从采购订单生成入库任务。body: {purchaseOrderNo} */
    @PostMapping("/from-purchase")
    public ApiResponse<Map<String, Object>> fromPurchase(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.createFromPurchase(
                TmsUtil.str(req.get("purchaseOrderNo")), TmsUtil.currentUser()));
    }

    /** 手工建其他入库任务。body: {inboundType, supplierCode, ..., details:[...]} */
    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.createManual(req, TmsUtil.currentUser()));
    }

    /** PDA/PC 逐行收货。body: {detailId, qty, batchNo?, productionDate?, expiryDate?, actualWeight?} */
    @PostMapping("/receive")
    public ApiResponse<Map<String, Object>> receive(@RequestBody Map<String, Object> req) {
        BigDecimal qty = req.get("qty") == null ? BigDecimal.ONE : new BigDecimal(TmsUtil.str(req.get("qty")));
        return ApiResponse.ok(service.receive(
                TmsUtil.str(req.get("detailId")), qty, TmsUtil.str(req.get("batchNo")),
                TmsUtil.date(req.get("productionDate")), TmsUtil.date(req.get("expiryDate")),
                req.get("actualWeight") == null ? null : new BigDecimal(TmsUtil.str(req.get("actualWeight"))),
                TmsUtil.currentUser()));
    }

    /** 收货完成 → 复检或待上架。 */
    @PostMapping("/finish-receive")
    public ApiResponse<Map<String, Object>> finishReceive(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.finishReceive(TmsUtil.str(req.get("taskId")), TmsUtil.currentUser()));
    }

    /** 复检结论。body: {taskId, passed, remark?} */
    @PostMapping("/recheck")
    public ApiResponse<Map<String, Object>> recheck(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.recheck(TmsUtil.str(req.get("taskId")),
                !"N".equals(TmsUtil.str(req.get("passed")).toUpperCase())
                        && !"false".equalsIgnoreCase(TmsUtil.str(req.get("passed"))),
                TmsUtil.str(req.get("remark")), TmsUtil.currentUser()));
    }

    // -------- 上架任务 --------

    @PostMapping("/putaway/page")
    public ApiResponse<PageResult<Map<String, Object>>> putawayPage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        return ApiResponse.ok(PageResult.of(service.putawayList(
                TmsUtil.str(f.get("status")), TmsUtil.str(f.get("assignee"))), request));
    }

    @PostMapping("/putaway/claim")
    public ApiResponse<Map<String, Object>> claim(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.claimPutaway(TmsUtil.str(req.get("putawayId")), TmsUtil.currentUser()));
    }

    /** 确认上架：body: {putawayId, actualBin?}。actualBin 空则使用推荐库位。 */
    @PostMapping("/putaway/confirm")
    public ApiResponse<Map<String, Object>> confirmPutaway(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.confirmPutaway(
                TmsUtil.str(req.get("putawayId")), TmsUtil.str(req.get("actualBin")), TmsUtil.currentUser()));
    }
}
