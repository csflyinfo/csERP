package com.erp.wms;

import com.erp.common.api.ApiResponse;
import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WMS V1.5 PDA 手持端端点（PRD-28）。
 *
 * <p>前缀 /api/wms/app。使用仓库员工 JWT（与后台同 token），通过 {@link TmsUtil#currentUser()}
 * 取操作人。覆盖拣货（我的/可支援库区抢单、扫码拣货、改批次、缺货加急/异常）、分播、复检、装车。
 *
 * <p>所有写操作委托 {@link WmsOutboundService}（事务在 service 层）。
 */
@RestController
@RequestMapping("/wms/app")
public class WmsAppController {

    private final WmsOutboundService service;
    private final WmsInboundService inbound;
    private final WmsInternalService internal;
    private final WmsBindingService binding;
    private final WmsContainerService container;
    private final SysParamService params;

    public WmsAppController(WmsOutboundService service, WmsInboundService inbound,
                            WmsInternalService internal, WmsBindingService binding,
                            WmsContainerService container, SysParamService params) {
        this.service = service;
        this.inbound = inbound;
        this.internal = internal;
        this.binding = binding;
        this.container = container;
        this.params = params;
    }

    /** 当前操作员 + 关键参数（决定 PDA 交互：点击拣货/默认视图/是否复检）。 */
    @PostMapping("/profile")
    public ApiResponse<Map<String, Object>> profile() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operator", TmsUtil.currentUser());
        data.put("clickPick", params.getInt("WMS_PDA_CLICK_PICK", 1, 0, 1) == 1);
        data.put("defaultView", params.getInt("WMS_PDA_VIEW", 0, 0, 1)); // 0=逐件聚焦, 1=清单总览
        data.put("checkEnabled", params.getInt("WMS_CHECK_ENABLED", 1, 0, 1) == 1);
        data.put("crossZoneMode", params.getInt("WMS_CROSS_ZONE_MODE", 2, 0, 2));
        data.put("batchChangeAllowed", params.getInt("WMS_PICK_BATCH_CHANGE_ALLOWED", 1, 0, 1) == 1);
        return ApiResponse.ok(data);
    }

    /**
     * 拣货任务列表。
     * body: {scope:'mine'|'help'|'all', zone?}
     */
    @PostMapping("/pick/tasks")
    public ApiResponse<List<Map<String, Object>>> tasks(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        String scope = strOr(r.get("scope"), "mine");
        String operator = strOr(r.get("operator"), TmsUtil.currentUser());
        return ApiResponse.ok(service.pickTasks(operator, scope, str(r.get("zone"))));
    }

    @PostMapping("/pick/task-detail")
    public ApiResponse<Map<String, Object>> taskDetail(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.pickTaskDetail(str(req.get("taskId"))));
    }

    /** 抢单/领取（含跨区支援）。body:{taskId, help?} */
    @PostMapping("/pick/claim")
    public ApiResponse<Map<String, Object>> claim(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.claimTask(str(req.get("taskId")),
                strOr(req.get("operator"), TmsUtil.currentUser()),
                bool(req.get("help"))));
    }

    /**
     * 扫/点一件商品拣货。
     * body:{taskId, detailId?, goodsCode?, qty?, actualBatchNo?, actualBin?}
     */
    @PostMapping("/pick/item")
    public ApiResponse<Map<String, Object>> pickItem(@RequestBody Map<String, Object> req) {
        BigDecimal qty = req.get("qty") == null ? BigDecimal.ONE : new BigDecimal(str(req.get("qty")));
        return ApiResponse.ok(service.pickItem(str(req.get("detailId")), str(req.get("taskId")),
                str(req.get("goodsCode")), qty, str(req.get("actualBatchNo")),
                str(req.get("actualBin")), strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    /** 整任务拣货完成提交。 */
    @PostMapping("/pick/complete")
    public ApiResponse<Map<String, Object>> complete(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.completeTask(str(req.get("taskId")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    /**
     * 复检通过（按订单集齐后逐件/整位扫码，最后提交通过）。
     * 免复核时该步在拣货完成自动触发，PDA 不展示。
     */
    @PostMapping("/check/pass")
    public ApiResponse<Map<String, Object>> checkPass(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.recheckOrder(str(req.get("waveId")), str(req.get("orderNo")),
                strOr(req.get("operator"), TmsUtil.currentUser()),
                strOr(req.get("checkScope"), "0"), bd(req.get("shortQty")), true));
    }

    /** 复检不通过 → 开异常单。 */
    @PostMapping("/check/fail")
    public ApiResponse<Map<String, Object>> checkFail(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.recheckOrder(str(req.get("waveId")), str(req.get("orderNo")),
                strOr(req.get("operator"), TmsUtil.currentUser()),
                strOr(req.get("checkScope"), "0"), bd(req.get("shortQty")), false));
    }

    /** 装车发运确认。 */
    @PostMapping("/load/ship")
    public ApiResponse<Map<String, Object>> ship(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.ship(str(req.get("waveId")), str(req.get("vehiclePlate")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    // ==================== 入库 PDA ====================

    /** 待收货任务列表。body: {status?, inboundType?} */
    @PostMapping("/inbound/tasks")
    public ApiResponse<List<Map<String, Object>>> inboundTasks(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        return ApiResponse.ok(inbound.list(str(r.get("status")), str(r.get("inboundType")), str(r.get("keyword"))));
    }

    @PostMapping("/inbound/detail")
    public ApiResponse<Map<String, Object>> inboundDetail(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(inbound.detail(str(req.get("taskId"))));
    }

    /**
     * 扫码收货（V86 增强）：
     * body:{detailId, qty, batchNo?, productionDate?, expiryDate?, actualWeight?, containerCode?, goodsRemark?}
     * 保质期校验、批次号自动生成、容器绑定、行级备注全部在 service 层处理。
     */
    @PostMapping("/inbound/receive")
    public ApiResponse<Map<String, Object>> inboundReceive(@RequestBody Map<String, Object> req) {
        BigDecimal qty = req.get("qty") == null ? BigDecimal.ONE : new BigDecimal(str(req.get("qty")));
        return ApiResponse.ok(inbound.receive(str(req.get("detailId")), qty, str(req.get("batchNo")),
                dateOrNull(req.get("productionDate")), dateOrNull(req.get("expiryDate")),
                req.get("actualWeight") == null ? null : new BigDecimal(str(req.get("actualWeight"))),
                str(req.get("containerCode")), str(req.get("goodsRemark")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    @PostMapping("/inbound/finish-receive")
    public ApiResponse<Map<String, Object>> inboundFinish(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(inbound.finishReceive(str(req.get("taskId")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    @PostMapping("/inbound/recheck")
    public ApiResponse<Map<String, Object>> inboundRecheck(@RequestBody Map<String, Object> req) {
        boolean passed = !"N".equalsIgnoreCase(str(req.get("passed")))
                && !"false".equalsIgnoreCase(str(req.get("passed")));
        return ApiResponse.ok(inbound.recheck(str(req.get("taskId")), passed, str(req.get("remark")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    /**
     * 待上架任务列表（我的 + 可领取）。
     * body:{status?, operator?}
     * <p>status 缺省时返回 PENDING（可领取）+ PUTTING（我已领但未完成）；
     * 显式传 "" 时返回全部（PDA 用此模式再在客户端过滤）；
     * 显式传具体状态（PENDING/PUTTING/DONE）时只返回该状态。
     */
    @PostMapping("/inbound/putaway-tasks")
    public ApiResponse<List<Map<String, Object>>> putawayTasks(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        // 关键：只有完全不传 status 才默认 PENDING；显式 "" 视为全部
        String status = r.containsKey("status") ? str(r.get("status")) : "PENDING";
        return ApiResponse.ok(inbound.putawayList(status,
                strOr(r.get("operator"), TmsUtil.currentUser())));
    }

    @PostMapping("/inbound/putaway/claim")
    public ApiResponse<Map<String, Object>> putawayClaim(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(inbound.claimPutaway(str(req.get("putawayId")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    /** 扫库位上架：body:{putawayId, actualBin}。actualBin 空则用推荐位。 */
    @PostMapping("/inbound/putaway/confirm")
    public ApiResponse<Map<String, Object>> putawayConfirm(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(inbound.confirmPutaway(str(req.get("putawayId")), str(req.get("actualBin")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    /**
     * 批量上架（V87）：body:{putawayIds:["PT...","PT..."]}，逐个上到各自的推荐库位。
     * 无推荐库位/已完成/不存在的任务会跳过并在 skipped 数组里返回，不影响其他任务。
     * 注意本方法不加 @Transactional，逐个调用独立事务的 confirmPutaway。
     */
    @PostMapping("/inbound/putaway/batch-confirm")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> putawayBatchConfirm(@RequestBody Map<String, Object> req) {
        Object ids = req.get("putawayIds");
        List<String> list = new java.util.ArrayList<>();
        if (ids instanceof List<?> l) {
            for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) list.add(String.valueOf(o));
        }
        return ApiResponse.ok(inbound.batchConfirmPutaway(list,
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    /**
     * 查看商品在各库位的当前实物库存（PDA 上架页"查看库存"弹窗）。
     * body:{goodsCode, warehouse?}，返回 bin_code/zone_name/production_date/batch_no/qty。
     */
    @PostMapping("/inbound/putaway/bin-stock")
    public ApiResponse<List<Map<String, Object>>> putawayBinStock(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(inbound.binStockByGoods(str(req.get("goodsCode")), str(req.get("warehouse"))));
    }

    // ==================== 容器（V86） ====================

    /** 扫容器条码查询容器信息（含当前任务里已装入的商品）。 */
    @PostMapping("/container/lookup")
    public ApiResponse<Map<String, Object>> containerLookup(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(container.lookup(str(req.get("containerCode"))));
    }

    /** 容器建档：body:{containerCode?, containerType?, warehouse?, zoneCode?, binCode?, remark?}。code 空时自动生成。 */
    @PostMapping("/container/create")
    public ApiResponse<Map<String, Object>> containerCreate(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(container.create(str(req.get("containerCode")),
                strOr(req.get("containerType"), "TOTE"),
                str(req.get("warehouse")), str(req.get("zoneCode")), str(req.get("binCode")),
                str(req.get("remark")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    /** 查容器内本次收货已装入的商品（PDA 容器内清点页用）。 */
    @PostMapping("/container/contents")
    public ApiResponse<List<Map<String, Object>>> containerContents(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(container.taskContents(str(req.get("containerCode"))));
    }

    /** 手工释放容器（异常场景兜底；正常流程上架完成会自动释放）。 */
    @PostMapping("/container/release")
    public ApiResponse<Map<String, Object>> containerRelease(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(container.release(str(req.get("containerCode")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    // ==================== 补货 / 移库 / 盘点 PDA ====================

    @PostMapping("/replenish/tasks")
    public ApiResponse<List<Map<String, Object>>> replenishTasks(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        return ApiResponse.ok(internal.listReplenish(strOr(r.get("status"), "PENDING"),
                strOr(r.get("operator"), TmsUtil.currentUser())));
    }

    @PostMapping("/replenish/claim")
    public ApiResponse<Map<String, Object>> replenishClaim(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.claimReplenish(str(req.get("taskId")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    @PostMapping("/replenish/complete")
    public ApiResponse<Map<String, Object>> replenishComplete(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.completeReplenish(str(req.get("taskId")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    @PostMapping("/move/tasks")
    public ApiResponse<List<Map<String, Object>>> moveTasks(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        return ApiResponse.ok(internal.listMove(str(r.get("status")), str(r.get("keyword"))));
    }

    @PostMapping("/move/complete")
    public ApiResponse<Map<String, Object>> moveComplete(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.completeMove(str(req.get("taskId")),
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    /** 盘点任务明细（PDA 逐行扫库位/商品录实盘）。body:{taskId} */
    @PostMapping("/stocktake/bins")
    public ApiResponse<List<Map<String, Object>>> stocktakeBins(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.stocktakeBins(str(req.get("taskId"))));
    }

    /** 提交一行盘点结果。body:{id, realQty} */
    @PostMapping("/stocktake/count")
    public ApiResponse<Map<String, Object>> stocktakeCount(@RequestBody Map<String, Object> req) {
        BigDecimal qty = req.get("realQty") == null ? BigDecimal.ZERO : new BigDecimal(str(req.get("realQty")));
        return ApiResponse.ok(internal.countBin(str(req.get("id")), qty,
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    @PostMapping("/stocktake/recount")
    public ApiResponse<Map<String, Object>> stocktakeRecount(@RequestBody Map<String, Object> req) {
        BigDecimal qty = req.get("realQty") == null ? BigDecimal.ZERO : new BigDecimal(str(req.get("realQty")));
        return ApiResponse.ok(internal.recountBin(str(req.get("id")), qty,
                strOr(req.get("operator"), TmsUtil.currentUser())));
    }

    // ==================== 拣货位商品绑定（PDA 快速维护） ====================

    /** 扫库位/商品查询当前绑定。body:{warehouse, binCode?, goodsCode?} */
    @PostMapping("/binding/lookup")
    public ApiResponse<List<Map<String, Object>>> bindingLookup(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(binding.lookup(str(req.get("warehouse")), str(req.get("binCode")), str(req.get("goodsCode"))));
    }

    /** PDA 绑定：扫库位 + 扫商品即绑定。body:{warehouse,zoneCode,binCode,goodsCode,goodsName,bindingType?,minQty?,maxQty?} */
    @PostMapping("/binding/bind")
    public ApiResponse<Map<String, Object>> bindingBind(@RequestBody Map<String, Object> req) {
        req.put("source", "PDA");
        if (str(req.get("operator")).isBlank()) req.put("operator", TmsUtil.currentUser());
        return ApiResponse.ok(binding.bind(req));
    }

    /** PDA 解绑。body:{bindingId} */
    @PostMapping("/binding/unbind")
    public ApiResponse<Map<String, Object>> bindingUnbind(@RequestBody Map<String, Object> req) {
        req.put("source", "PDA");
        if (str(req.get("operator")).isBlank()) req.put("operator", TmsUtil.currentUser());
        return ApiResponse.ok(binding.unbind(req));
    }

    /**
     * PDA 转移绑定：扫旧库位 + 扫商品 + 扫新库位，把该商品绑定从旧库位移到新库位。
     * body:{warehouse, goodsCode, fromBin, toBin, toZoneCode?}
     */
    @PostMapping("/binding/transfer")
    public ApiResponse<Map<String, Object>> bindingTransfer(@RequestBody Map<String, Object> req) {
        req.put("source", "PDA");
        if (str(req.get("operator")).isBlank()) req.put("operator", TmsUtil.currentUser());
        return ApiResponse.ok(binding.transfer(req));
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String strOr(Object o, String dft) { String s = str(o); return s.isEmpty() ? dft : s; }
    private static java.time.LocalDate dateOrNull(Object o) {
        String s = str(o);
        if (s.isEmpty()) return null;
        try { return java.time.LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return null; }
    }
    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim();
        return "Y".equalsIgnoreCase(s) || "1".equals(s) || "true".equalsIgnoreCase(s);
    }
    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(o).trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
