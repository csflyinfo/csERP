package com.erp.wms;

import com.erp.auth.AuthSupport;
import com.erp.common.api.ApiResponse;
import com.erp.common.security.CurrentUser;
import com.erp.common.security.PermissionDeniedException;
import com.erp.common.security.PermissionService;
import com.erp.common.security.RequirePerm;
import com.erp.system.SysParamService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WMS V1.5 PDA 手持端端点（PRD-28 卡片9 RBAC）。
 *
 * <p>前缀 /api/wms/app。仅接受 appType=WMS_PDA 且选了作业仓的 JWT：
 * {@code PdaAppGuardInterceptor} 对其余令牌（ERP/DRIVER/旧 token）一律 401；
 * 每个端点再经 {@link RequirePerm} 校验功能点（按钮隐藏 + 后端拒绝双保险，PDA-004）。
 *
 * <p>权限分派规则（方案 §7.2.1 矩阵）：
 * <ul>
 *   <li>操作人只取 {@link CurrentUser}（自然人），不再信任 body.operator（PDA-003）；</li>
 *   <li>同端点多动作用 {@code alsoRegister} 注册载荷功能码，方法体内按参数
 *       {@link #checkPerm(String)} 逐分支裁决；</li>
 *   <li>仓库隔离在 service 层统一实施（PDA 查询强制当前仓，单据操作 assertIfPda）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/wms/app")
public class WmsAppController {

    // ==================== 功能点编码（方案 §7.2.1） ====================
    private static final String F_PROFILE_VIEW = "wms_pda.profile.view";
    private static final String F_HOME_SCAN = "wms_pda.home.scan";
    private static final String F_SWITCH_WH = "wms_pda.profile.switch_warehouse";
    private static final String F_CHANGE_PWD = "wms_pda.profile.change_pwd";

    private static final String F_PICK_VIEW = "wms_pda.pick.view";
    private static final String F_PICK_START = "wms_pda.pick.start";
    private static final String F_PICK_SCAN = "wms_pda.pick.scan";
    private static final String F_PICK_CONFIRM = "wms_pda.pick.confirm";
    private static final String F_PICK_SHORT = "wms_pda.pick.short_pick";
    private static final String F_PICK_SKIP = "wms_pda.pick.skip";
    private static final String F_PICK_TRANSFER = "wms_pda.pick.transfer";

    private static final String F_CHECK_VIEW = "wms_pda.check.view";
    private static final String F_CHECK_SCAN = "wms_pda.check.scan";
    private static final String F_CHECK_CONFIRM = "wms_pda.check.confirm";
    private static final String F_CHECK_EXCEPTION = "wms_pda.check.exception";
    private static final String F_CHECK_PACK = "wms_pda.check.pack";

    private static final String F_LOAD_VIEW = "wms_pda.load.view";
    private static final String F_LOAD_SCAN = "wms_pda.load.scan";
    private static final String F_LOAD_CONFIRM = "wms_pda.load.confirm";

    private static final String F_RECEIVE_VIEW = "wms_pda.receive.view";
    private static final String F_RECEIVE_START = "wms_pda.receive.start";
    private static final String F_RECEIVE_SCAN = "wms_pda.receive.scan";
    private static final String F_RECEIVE_CONFIRM = "wms_pda.receive.confirm";
    private static final String F_RECEIVE_RECHECK = "wms_pda.receive.recheck";
    private static final String F_RECEIVE_PRINT = "wms_pda.receive.print_label";
    private static final String F_RECEIVE_OVER = "wms_pda.receive.over_receive";
    private static final String F_RETURN_VIEW = "wms_pda.receive_return.view";
    private static final String F_RETURN_SCAN = "wms_pda.receive_return.scan";
    private static final String F_RETURN_CONFIRM = "wms_pda.receive_return.confirm";
    private static final String F_OTHER_VIEW = "wms_pda.other_inbound.view";
    private static final String F_OTHER_ADD = "wms_pda.other_inbound.add";
    private static final String F_OTHER_CONFIRM = "wms_pda.other_inbound.confirm";

    private static final String F_PUTAWAY_VIEW = "wms_pda.putaway.view";
    private static final String F_PUTAWAY_START = "wms_pda.putaway.start";
    private static final String F_PUTAWAY_SCAN = "wms_pda.putaway.scan";
    private static final String F_PUTAWAY_CONFIRM = "wms_pda.putaway.confirm";
    private static final String F_PUTAWAY_FREE = "wms_pda.putaway.free_bin";
    private static final String F_PUTAWAY_SPLIT = "wms_pda.putaway.split";

    private static final String F_REPLENISH_VIEW = "wms_pda.replenish.view";
    private static final String F_REPLENISH_CONFIRM = "wms_pda.replenish.confirm";
    private static final String F_REPLENISH_URGENT = "wms_pda.replenish.urgent";

    private static final String F_MOVE_VIEW = "wms_pda.move.view";
    private static final String F_MOVE_ADD = "wms_pda.move.add";
    private static final String F_MOVE_CONFIRM = "wms_pda.move.confirm";

    private static final String F_TAKE_VIEW = "wms_pda.stocktake.view";
    private static final String F_TAKE_SCAN = "wms_pda.stocktake.scan";
    private static final String F_TAKE_INPUT = "wms_pda.stocktake.input";
    private static final String F_TAKE_SUBMIT = "wms_pda.stocktake.submit";
    private static final String F_TAKE_AUDIT = "wms_pda.stocktake.audit";

    private static final String F_DAMAGE_VIEW = "wms_pda.damage.view";
    private static final String F_DAMAGE_ADD = "wms_pda.damage.add";
    private static final String F_DAMAGE_AUDIT = "wms_pda.damage.audit";

    private static final String F_STOCK_VIEW = "wms_pda.stock_query.view";
    private static final String F_STOCK_BATCH = "wms_pda.stock_query.view_batch";
    private static final String F_STOCK_COST = "wms_pda.stock_query.view_cost";

    private static final String F_EXC_VIEW = "wms_pda.exception.view";
    private static final String F_EXC_REPORT = "wms_pda.exception.report";
    private static final String F_EXC_HANDLE = "wms_pda.exception.handle";
    private static final String F_EXC_ASSIGN = "wms_pda.exception.assign";

    private static final String F_ASSIGN_VIEW = "wms_pda.task_assign.view";
    private static final String F_ASSIGN_ASSIGN = "wms_pda.task_assign.assign";
    private static final String F_ASSIGN_RECALL = "wms_pda.task_assign.recall";

    private static final String F_PERF_SELF = "wms_pda.performance.view_self";
    private static final String F_PERF_TEAM = "wms_pda.performance.view_team";
    private static final String F_PERF_EXPORT = "wms_pda.performance.export";

    private final WmsOutboundService service;
    private final WmsInboundService inbound;
    private final WmsInternalService internal;
    private final WmsBindingService binding;
    private final WmsContainerService container;
    private final SysParamService params;
    private final WmsWarehouseResolver warehouseResolver;
    private final PermissionService permissionService;
    private final AuthSupport authSupport;

    public WmsAppController(WmsOutboundService service, WmsInboundService inbound,
                            WmsInternalService internal, WmsBindingService binding,
                            WmsContainerService container, SysParamService params,
                            WmsWarehouseResolver warehouseResolver,
                            PermissionService permissionService,
                            AuthSupport authSupport) {
        this.service = service;
        this.inbound = inbound;
        this.internal = internal;
        this.binding = binding;
        this.container = container;
        this.params = params;
        this.warehouseResolver = warehouseResolver;
        this.permissionService = permissionService;
        this.authSupport = authSupport;
    }

    // ==================== 我的 / 首页 ====================

    /**
     * 当前操作员档案 + 作业仓 + 权限标志 + 关键参数。
     * switch_warehouse/change_pwd/home.scan 为同端点按标志使用的载荷功能码：
     * 切仓实质=退登重选仓（本端返回可切仓库列表），改密复用 POST /auth/change-password。
     */
    @PostMapping("/profile")
    @RequirePerm(value = F_PROFILE_VIEW, name = "PDA-我的",
            alsoRegister = {F_HOME_SCAN, F_SWITCH_WH, F_CHANGE_PWD})
    public ApiResponse<Map<String, Object>> profile() {
        CurrentUser.Principal p = CurrentUser.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", p.userId());
        data.put("username", p.username());
        data.put("displayName", p.displayName());
        data.put("employeeId", p.employeeId());
        data.put("roleCodes", p.roleCodes());
        data.put("primaryRoleCode", p.primaryRoleCode());
        data.put("warehouseId", p.warehouseId());
        data.put("warehouseName", warehouseResolver.currentWarehouseName());
        // 可切仓库列表（切仓=重新登录选仓，不在本端切换 token）
        data.put("warehouses", authSupport.warehouses(p.userId()));
        // 按钮裁剪标志（Flutter 与后端同口径）
        data.put("canHomeScan", permissionService.hasFunc(F_HOME_SCAN));
        data.put("canSwitchWarehouse", permissionService.hasFunc(F_SWITCH_WH));
        data.put("canChangePwd", permissionService.hasFunc(F_CHANGE_PWD));
        data.put("clickPick", params.getInt("WMS_PDA_CLICK_PICK", 1, 0, 1) == 1);
        data.put("defaultView", params.getInt("WMS_PDA_VIEW", 0, 0, 1)); // 0=逐件聚焦, 1=清单总览
        data.put("checkEnabled", params.getInt("WMS_CHECK_ENABLED", 1, 0, 1) == 1);
        data.put("crossZoneMode", params.getInt("WMS_CROSS_ZONE_MODE", 2, 0, 2));
        data.put("batchChangeAllowed", params.getInt("WMS_PICK_BATCH_CHANGE_ALLOWED", 1, 0, 1) == 1);
        return ApiResponse.ok(data);
    }

    // ==================== 出库-拣货 ====================

    /**
     * 拣货任务列表。body: {scope:'mine'|'help'|'all', zone?}
     * scope=all 是主管派工视角，须额外具备 task_assign.view（PDA-004 同口径）。
     */
    @PostMapping("/pick/tasks")
    @RequirePerm(value = F_PICK_VIEW, name = "拣货任务", alsoRegister = F_ASSIGN_VIEW)
    public ApiResponse<List<Map<String, Object>>> tasks(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        String scope = strOr(r.get("scope"), "mine");
        if ("all".equalsIgnoreCase(scope)) checkPerm(F_ASSIGN_VIEW);
        return ApiResponse.ok(service.pickTasks(operator(), scope, str(r.get("zone"))));
    }

    @PostMapping("/pick/task-detail")
    @RequirePerm(value = F_PICK_VIEW, name = "拣货明细")
    public ApiResponse<Map<String, Object>> taskDetail(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.pickTaskDetail(str(req.get("taskId"))));
    }

    /** 抢单/领取（含跨区支援）。body:{taskId, help?} */
    @PostMapping("/pick/claim")
    @RequirePerm(value = F_PICK_START, name = "领取拣货任务", type = "ACTION")
    public ApiResponse<Map<String, Object>> claim(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.claimTask(str(req.get("taskId")), operator(), bool(req.get("help"))));
    }

    /** 扫/点一件商品拣货。body:{taskId, detailId?, goodsCode?, qty?, actualBatchNo?, actualBin?} */
    @PostMapping("/pick/item")
    @RequirePerm(value = F_PICK_SCAN, name = "扫码拣货", type = "ACTION")
    public ApiResponse<Map<String, Object>> pickItem(@RequestBody Map<String, Object> req) {
        BigDecimal qty = req.get("qty") == null ? BigDecimal.ONE : new BigDecimal(str(req.get("qty")));
        return ApiResponse.ok(service.pickItem(str(req.get("detailId")), str(req.get("taskId")),
                str(req.get("goodsCode")), qty, str(req.get("actualBatchNo")),
                str(req.get("actualBin")), operator()));
    }

    /** 整任务拣货完成提交。 */
    @PostMapping("/pick/complete")
    @RequirePerm(value = F_PICK_CONFIRM, name = "拣货完成", type = "ACTION")
    public ApiResponse<Map<String, Object>> complete(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.completeTask(str(req.get("taskId")), operator()));
    }

    /** 缺货上报：明细置 SHORT + 异常单留痕（原因必填）。body:{detailId, shortQty?, reason} */
    @PostMapping("/pick/short-pick")
    @RequirePerm(value = F_PICK_SHORT, name = "缺货上报", type = "ACTION")
    public ApiResponse<Map<String, Object>> shortPick(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.shortPick(str(req.get("detailId")),
                req.get("shortQty") == null ? null : bd(req.get("shortQty")),
                str(req.get("reason")), operator()));
    }

    /** 跳过商品：不改明细状态，仅异常单留痕（原因必填）。body:{detailId, reason} */
    @PostMapping("/pick/skip")
    @RequirePerm(value = F_PICK_SKIP, name = "跳过商品", type = "ACTION")
    public ApiResponse<Map<String, Object>> skip(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.skipDetail(str(req.get("detailId")), str(req.get("reason")), operator()));
    }

    /** 拣货任务转交（仅 KEEPER/LEADER）：目标人必须启用且绑定当前仓。body:{taskId, toAssignee} */
    @PostMapping("/pick/transfer")
    @RequirePerm(value = F_PICK_TRANSFER, name = "转交拣货任务", type = "ACTION")
    public ApiResponse<Map<String, Object>> transfer(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.transferTask(str(req.get("taskId")),
                str(req.get("toAssignee")), operator()));
    }

    // ==================== 出库-复核 / 装车 ====================

    /** 复核任务列表：当前仓待复核波次及订单。 */
    @PostMapping("/check/tasks")
    @RequirePerm(value = F_CHECK_VIEW, name = "复核任务")
    public ApiResponse<List<Map<String, Object>>> checkTasks(@RequestBody(required = false) Map<String, Object> req) {
        return ApiResponse.ok(service.checkTaskList());
    }

    /**
     * 复核工位动作（同端点按 action 分派）：
     * action=scan（扫码集齐核对，载荷 check.scan）、action=pack（打包封箱，载荷 check.pack）、
     * 缺省=复核通过（check.confirm）。
     */
    @PostMapping("/check/pass")
    @RequirePerm(value = F_CHECK_CONFIRM, name = "复核通过", type = "ACTION",
            alsoRegister = {F_CHECK_SCAN, F_CHECK_PACK})
    public ApiResponse<Map<String, Object>> checkPass(@RequestBody Map<String, Object> req) {
        String action = str(req.get("action"));
        String waveId = str(req.get("waveId"));
        String orderNo = str(req.get("orderNo"));
        if ("scan".equalsIgnoreCase(action)) {
            checkPerm(F_CHECK_SCAN);
            return ApiResponse.ok(Map.of("ok", true, "waveId", waveId, "orderNo", orderNo));
        }
        if ("pack".equalsIgnoreCase(action)) {
            checkPerm(F_CHECK_PACK);
            return ApiResponse.ok(Map.of("ok", true, "waveId", waveId, "orderNo", orderNo));
        }
        return ApiResponse.ok(service.recheckOrder(waveId, orderNo, operator(),
                strOr(req.get("checkScope"), "0"), bd(req.get("shortQty")), true, str(req.get("reason"))));
    }

    /** 复核不通过 → 开异常单（原因 PDA 必填，service 强制）。 */
    @PostMapping("/check/fail")
    @RequirePerm(value = F_CHECK_EXCEPTION, name = "复核不通过", type = "ACTION")
    public ApiResponse<Map<String, Object>> checkFail(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.recheckOrder(str(req.get("waveId")), str(req.get("orderNo")),
                operator(), strOr(req.get("checkScope"), "0"), bd(req.get("shortQty")),
                false, str(req.get("reason"))));
    }

    /** 装车任务列表：当前仓已复核待发运波次。 */
    @PostMapping("/load/tasks")
    @RequirePerm(value = F_LOAD_VIEW, name = "装车任务")
    public ApiResponse<List<Map<String, Object>>> loadTasks(@RequestBody(required = false) Map<String, Object> req) {
        return ApiResponse.ok(service.loadTaskList());
    }

    /**
     * 装车工位：action=scan 为发车前扫码核对（载荷 load.scan），缺省=装车发运确认。
     */
    @PostMapping("/load/ship")
    @RequirePerm(value = F_LOAD_CONFIRM, name = "装车发运", type = "ACTION", alsoRegister = F_LOAD_SCAN)
    public ApiResponse<Map<String, Object>> ship(@RequestBody Map<String, Object> req) {
        if ("scan".equalsIgnoreCase(str(req.get("action")))) {
            checkPerm(F_LOAD_SCAN);
            return ApiResponse.ok(Map.of("ok", true, "waveId", str(req.get("waveId"))));
        }
        return ApiResponse.ok(service.ship(str(req.get("waveId")), str(req.get("vehiclePlate")), operator()));
    }

    // ==================== 入库（采购收货 / 退货收货 / 其他入库） ====================

    /**
     * 收货任务列表。body: {status?, inboundType?, keyword?}
     * 按入库类型分派视图功能点：SALES_RETURN→receive_return.view，OTHER/TRANSFER→other_inbound.view。
     */
    @PostMapping("/inbound/tasks")
    @RequirePerm(value = F_RECEIVE_VIEW, name = "收货任务",
            alsoRegister = {F_RETURN_VIEW, F_OTHER_VIEW})
    public ApiResponse<List<Map<String, Object>>> inboundTasks(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        String type = str(r.get("inboundType"));
        if (!type.isBlank()) checkPerm(receiveGroupView(type));
        return ApiResponse.ok(inbound.list(str(r.get("status")), type, str(r.get("keyword"))));
    }

    /** 入库任务详情：按任务实际类型裁决视图功能点（不信入参类型）。 */
    @PostMapping("/inbound/detail")
    @RequirePerm(value = F_RECEIVE_VIEW, name = "收货明细",
            alsoRegister = {F_RETURN_VIEW, F_OTHER_VIEW})
    public ApiResponse<Map<String, Object>> inboundDetail(@RequestBody Map<String, Object> req) {
        String taskId = str(req.get("taskId"));
        checkPerm(receiveGroupView(inbound.inboundTypeOfTask(taskId)));
        return ApiResponse.ok(inbound.detail(taskId));
    }

    /**
     * PDA 手工建其他入库任务（other_inbound.add，KEEPER/LEADER）：仅允许 OTHER/TRANSFER，
     * 仓库强制取登录仓（service 内实施）。body 同 PC 手工建单。
     */
    @PostMapping("/inbound/create")
    @RequirePerm(value = F_OTHER_ADD, name = "其他入库建单", type = "ACTION")
    public ApiResponse<Map<String, Object>> inboundCreate(@RequestBody Map<String, Object> req) {
        String type = strOr(req.get("inboundType"), "OTHER").toUpperCase(Locale.ROOT);
        if (!"OTHER".equals(type) && !"TRANSFER".equals(type)) {
            throw new IllegalArgumentException("PDA 仅支持创建其他入库/调拨入库任务");
        }
        req.put("inboundType", type);
        return ApiResponse.ok(inbound.createManual(req, operator()));
    }

    /**
     * 扫码收货（同端点按参数分派）：
     * action=start（开始收货，载荷 receive.start，返回任务详情）；
     * printLabel=true（收货后打印箱签，载荷 receive.print_label）；
     * 缺省按明细所属任务的入库类型裁决 receive.scan / receive_return.scan / other_inbound.confirm；
     * 超收（载荷 receive.over_receive）由 service 按功能点 + 必填原因双控并写异常单。
     */
    @PostMapping("/inbound/receive")
    @RequirePerm(value = F_RECEIVE_SCAN, name = "扫码收货", type = "ACTION",
            alsoRegister = {F_RETURN_SCAN, F_OTHER_CONFIRM, F_RECEIVE_START,
                    F_RECEIVE_PRINT, F_RECEIVE_OVER})
    public ApiResponse<Map<String, Object>> inboundReceive(@RequestBody Map<String, Object> req) {
        String action = str(req.get("action"));
        if ("start".equalsIgnoreCase(action)) {
            checkPerm(F_RECEIVE_START);
            return ApiResponse.ok(inbound.detail(str(req.get("taskId"))));
        }
        String detailId = str(req.get("detailId"));
        String group = receiveGroup(inbound.inboundTypeOfDetail(detailId));
        if ("receive_return".equals(group)) checkPerm(F_RETURN_SCAN);
        else if ("other_inbound".equals(group)) checkPerm(F_OTHER_CONFIRM);
        if (bool(req.get("printLabel"))) checkPerm(F_RECEIVE_PRINT);
        BigDecimal qty = req.get("qty") == null ? BigDecimal.ONE : new BigDecimal(str(req.get("qty")));
        return ApiResponse.ok(inbound.receive(detailId, qty, str(req.get("batchNo")),
                dateOrNull(req.get("productionDate")), dateOrNull(req.get("expiryDate")),
                req.get("actualWeight") == null ? null : new BigDecimal(str(req.get("actualWeight"))),
                str(req.get("containerCode")), str(req.get("goodsRemark")),
                operator(), str(req.get("overReceiveReason"))));
    }

    /** 整单收货完成：按任务实际类型裁决 confirm 功能点。 */
    @PostMapping("/inbound/finish-receive")
    @RequirePerm(value = F_RECEIVE_CONFIRM, name = "完成收货", type = "ACTION",
            alsoRegister = {F_RETURN_CONFIRM, F_OTHER_CONFIRM})
    public ApiResponse<Map<String, Object>> inboundFinish(@RequestBody Map<String, Object> req) {
        String taskId = str(req.get("taskId"));
        String group = receiveGroup(inbound.inboundTypeOfTask(taskId));
        if ("receive_return".equals(group)) checkPerm(F_RETURN_CONFIRM);
        else if ("other_inbound".equals(group)) checkPerm(F_OTHER_CONFIRM);
        return ApiResponse.ok(inbound.finishReceive(taskId, operator()));
    }

    /** 收货复检（WMS_RECHECK_SELF_NG=1 时禁止复检本人收货单，service 强制）。 */
    @PostMapping("/inbound/recheck")
    @RequirePerm(value = F_RECEIVE_RECHECK, name = "收货复检", type = "ACTION")
    public ApiResponse<Map<String, Object>> inboundRecheck(@RequestBody Map<String, Object> req) {
        boolean passed = !"N".equalsIgnoreCase(str(req.get("passed")))
                && !"false".equalsIgnoreCase(str(req.get("passed")));
        return ApiResponse.ok(inbound.recheck(str(req.get("taskId")), passed,
                str(req.get("remark")), operator()));
    }

    /**
     * 待上架任务列表。status 缺省=PENDING（可领取）+PUTTING（我已领）；显式 ""=全部。
     * body:{status?}
     */
    @PostMapping("/inbound/putaway-tasks")
    @RequirePerm(value = F_PUTAWAY_VIEW, name = "上架任务")
    public ApiResponse<List<Map<String, Object>>> putawayTasks(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        String status = r.containsKey("status") ? str(r.get("status")) : "PENDING";
        return ApiResponse.ok(inbound.putawayList(status, operator()));
    }

    @PostMapping("/inbound/putaway/claim")
    @RequirePerm(value = F_PUTAWAY_START, name = "领取上架任务", type = "ACTION")
    public ApiResponse<Map<String, Object>> putawayClaim(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(inbound.claimPutaway(str(req.get("putawayId")), operator()));
    }

    /**
     * 上架工位（同端点按参数分派）：
     * action=scan（扫库位/商品核对，载荷 putaway.scan，非变更返回 ack）；
     * actualBin 非空=人工改放库位（载荷 putaway.free_bin）；
     * split=true=拆容器/拆批上架（载荷 putaway.split）；缺省按推荐位上架。
     */
    @PostMapping("/inbound/putaway/confirm")
    @RequirePerm(value = F_PUTAWAY_CONFIRM, name = "确认上架", type = "ACTION",
            alsoRegister = {F_PUTAWAY_SCAN, F_PUTAWAY_FREE, F_PUTAWAY_SPLIT})
    public ApiResponse<Map<String, Object>> putawayConfirm(@RequestBody Map<String, Object> req) {
        String putawayId = str(req.get("putawayId"));
        if ("scan".equalsIgnoreCase(str(req.get("action")))) {
            checkPerm(F_PUTAWAY_SCAN);
            return ApiResponse.ok(Map.of("ok", true, "putawayId", putawayId));
        }
        String actualBin = str(req.get("actualBin"));
        if (!actualBin.isBlank()) checkPerm(F_PUTAWAY_FREE);
        if (bool(req.get("split"))) checkPerm(F_PUTAWAY_SPLIT);
        return ApiResponse.ok(inbound.confirmPutaway(putawayId, actualBin, operator()));
    }

    /** 批量上架：逐个上到各自推荐库位，无可上推荐位的进 skipped。body:{putawayIds:[]} */
    @PostMapping("/inbound/putaway/batch-confirm")
    @RequirePerm(value = F_PUTAWAY_CONFIRM, name = "批量上架", type = "ACTION")
    public ApiResponse<Map<String, Object>> putawayBatchConfirm(@RequestBody Map<String, Object> req) {
        Object ids = req.get("putawayIds");
        List<String> list = new ArrayList<>();
        if (ids instanceof List<?> l) {
            for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) list.add(String.valueOf(o));
        }
        return ApiResponse.ok(inbound.batchConfirmPutaway(list, operator()));
    }

    /** 查看商品在各库位的实物库存（上架页"查看库存"弹窗）。body:{goodsCode} */
    @PostMapping("/inbound/putaway/bin-stock")
    @RequirePerm(value = F_PUTAWAY_VIEW, name = "上架查库存")
    public ApiResponse<List<Map<String, Object>>> putawayBinStock(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(inbound.binStockByGoods(str(req.get("goodsCode")), null));
    }

    // ==================== 容器（收货/上架工位工具） ====================

    /** 扫容器条码查询（含本任务已装入商品）。 */
    @PostMapping("/container/lookup")
    @RequirePerm(value = F_RECEIVE_SCAN, name = "扫容器")
    public ApiResponse<Map<String, Object>> containerLookup(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(container.lookup(str(req.get("containerCode"))));
    }

    /** 容器建档：body:{containerCode?, containerType?, zoneCode?, binCode?, remark?}，仓库强制登录仓。 */
    @PostMapping("/container/create")
    @RequirePerm(value = F_RECEIVE_START, name = "容器建档", type = "ACTION")
    public ApiResponse<Map<String, Object>> containerCreate(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(container.create(str(req.get("containerCode")),
                strOr(req.get("containerType"), "TOTE"),
                null, str(req.get("zoneCode")), str(req.get("binCode")),
                str(req.get("remark")), operator()));
    }

    /** 查容器内本次收货已装入的商品。 */
    @PostMapping("/container/contents")
    @RequirePerm(value = F_RECEIVE_SCAN, name = "容器清点")
    public ApiResponse<List<Map<String, Object>>> containerContents(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(container.taskContents(str(req.get("containerCode"))));
    }

    /** 手工释放容器（异常兜底；正常流程上架完自动释放）。 */
    @PostMapping("/container/release")
    @RequirePerm(value = F_PUTAWAY_CONFIRM, name = "释放容器", type = "ACTION")
    public ApiResponse<Map<String, Object>> containerRelease(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(container.release(str(req.get("containerCode")), operator()));
    }

    // ==================== 补货 / 移库 ====================

    @PostMapping("/replenish/tasks")
    @RequirePerm(value = F_REPLENISH_VIEW, name = "补货任务")
    public ApiResponse<List<Map<String, Object>>> replenishTasks(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        return ApiResponse.ok(internal.listReplenish(strOr(r.get("status"), "PENDING"), operator()));
    }

    @PostMapping("/replenish/claim")
    @RequirePerm(value = F_REPLENISH_CONFIRM, name = "领取补货任务", type = "ACTION")
    public ApiResponse<Map<String, Object>> replenishClaim(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.claimReplenish(str(req.get("taskId")), operator()));
    }

    @PostMapping("/replenish/complete")
    @RequirePerm(value = F_REPLENISH_CONFIRM, name = "完成补货", type = "ACTION")
    public ApiResponse<Map<String, Object>> replenishComplete(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.completeReplenish(str(req.get("taskId")), operator()));
    }

    /** 缺货加急补货（PICKER/KEEPER/LEADER）：从波次触发生成补货任务。body:{goodsCode,batchNo?,toBin,qty,waveId} */
    @PostMapping("/replenish/urgent")
    @RequirePerm(value = F_REPLENISH_URGENT, name = "加急补货", type = "ACTION")
    public ApiResponse<Map<String, Object>> replenishUrgent(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.urgentReplenish(str(req.get("goodsCode")), str(req.get("batchNo")),
                str(req.get("toBin")), req.get("qty") == null ? null : bd(req.get("qty")),
                str(req.get("waveId")), operator()));
    }

    @PostMapping("/move/tasks")
    @RequirePerm(value = F_MOVE_VIEW, name = "移库任务")
    public ApiResponse<List<Map<String, Object>>> moveTasks(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        return ApiResponse.ok(internal.listMove(str(r.get("status")), str(r.get("keyword"))));
    }

    /** PDA 建移库单（KEEPER/LEADER），仓库强制登录仓。 */
    @PostMapping("/move/add")
    @RequirePerm(value = F_MOVE_ADD, name = "新建移库", type = "ACTION")
    public ApiResponse<Map<String, Object>> moveAdd(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.createMove(req, operator()));
    }

    @PostMapping("/move/complete")
    @RequirePerm(value = F_MOVE_CONFIRM, name = "完成移库", type = "ACTION")
    public ApiResponse<Map<String, Object>> moveComplete(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.completeMove(str(req.get("taskId")), operator()));
    }

    // ==================== 盘点 ====================

    /** 盘点任务明细。action=scan（扫库位进入）为载荷 stocktake.scan。body:{taskId, action?} */
    @PostMapping("/stocktake/bins")
    @RequirePerm(value = F_TAKE_VIEW, name = "盘点明细", alsoRegister = F_TAKE_SCAN)
    public ApiResponse<List<Map<String, Object>>> stocktakeBins(@RequestBody Map<String, Object> req) {
        if ("scan".equalsIgnoreCase(str(req.get("action")))) checkPerm(F_TAKE_SCAN);
        return ApiResponse.ok(internal.stocktakeBins(str(req.get("taskId"))));
    }

    /** 提交一行盘点结果。body:{id, realQty} */
    @PostMapping("/stocktake/count")
    @RequirePerm(value = F_TAKE_INPUT, name = "录入实盘", type = "ACTION")
    public ApiResponse<Map<String, Object>> stocktakeCount(@RequestBody Map<String, Object> req) {
        BigDecimal qty = req.get("realQty") == null ? BigDecimal.ZERO : new BigDecimal(str(req.get("realQty")));
        return ApiResponse.ok(internal.countBin(str(req.get("id")), qty, operator()));
    }

    /** 复盘一行。body:{id, realQty} */
    @PostMapping("/stocktake/recount")
    @RequirePerm(value = F_TAKE_INPUT, name = "复盘录入", type = "ACTION")
    public ApiResponse<Map<String, Object>> stocktakeRecount(@RequestBody Map<String, Object> req) {
        BigDecimal qty = req.get("realQty") == null ? BigDecimal.ZERO : new BigDecimal(str(req.get("realQty")));
        return ApiResponse.ok(internal.recountBin(str(req.get("id")), qty, operator()));
    }

    /** 整单提交盘点结果 → PENDING_APPROVAL（所有行必须已录实盘）。 */
    @PostMapping("/stocktake/submit")
    @RequirePerm(value = F_TAKE_SUBMIT, name = "盘点提交", type = "ACTION")
    public ApiResponse<Map<String, Object>> stocktakeSubmit(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.submitStocktake(str(req.get("taskId")), operator()));
    }

    /** 盘点审核（仅 LEADER）：PDA 只允许审核已提交单，差异自动生成调整单。 */
    @PostMapping("/stocktake/audit")
    @RequirePerm(value = F_TAKE_AUDIT, name = "盘点审核", type = "ACTION")
    public ApiResponse<Map<String, Object>> stocktakeAudit(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.finishStocktake(str(req.get("taskId")), operator()));
    }

    // ==================== 报损 ====================

    /** 报损单列表（当前仓）。body:{status?} */
    @PostMapping("/damage/list")
    @RequirePerm(value = F_DAMAGE_VIEW, name = "报损单")
    public ApiResponse<List<Map<String, Object>>> damageList(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        String status = str(r.get("status"));
        return ApiResponse.ok(internal.listDamage(status.isBlank() ? null : status));
    }

    /** PDA 报损登记（KEEPER/LEADER；参数要求照片时必传 imageUrl）。 */
    @PostMapping("/damage/add")
    @RequirePerm(value = F_DAMAGE_ADD, name = "报损登记", type = "ACTION")
    public ApiResponse<Map<String, Object>> damageAdd(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.createDamage(req, operator()));
    }

    /** 报损审批（仅 LEADER）。body:{damageId, approved} */
    @PostMapping("/damage/audit")
    @RequirePerm(value = F_DAMAGE_AUDIT, name = "报损审批", type = "ACTION")
    public ApiResponse<Map<String, Object>> damageAudit(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.approveDamage(str(req.get("damageId")),
                bool(req.get("approved")), operator()));
    }

    // ==================== 库存查询 ====================

    /**
     * 实物库存查询（全员）。批次列受 view_batch 控制；成本单价/金额受 view_cost 功能点
     * + VIEW_COST/VIEW_COST_AMOUNT 字段双控，列裁剪与金额清零在 service 内实施。
     * body:{keyword?, discrepancyOnly?}
     */
    @PostMapping("/stock-query")
    @RequirePerm(value = F_STOCK_VIEW, name = "库存查询",
            alsoRegister = {F_STOCK_BATCH, F_STOCK_COST})
    public ApiResponse<List<Map<String, Object>>> stockQuery(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        return ApiResponse.ok(internal.binStockQuery("", str(r.get("keyword")), bool(r.get("discrepancyOnly"))));
    }

    // ==================== 异常中心 ====================

    /** 异常单列表（当前仓）。body:{status?, keyword?} */
    @PostMapping("/exception/list")
    @RequirePerm(value = F_EXC_VIEW, name = "异常单")
    public ApiResponse<List<Map<String, Object>>> exceptionList(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        return ApiResponse.ok(internal.listExceptions(str(r.get("status")), str(r.get("keyword"))));
    }

    /** 上报异常（全员）。body:{exceptionType, description, qty?, goodsCode?, sourceType?, waveId?/sourceBill?, binCode?, priority?} */
    @PostMapping("/exception/report")
    @RequirePerm(value = F_EXC_REPORT, name = "上报异常", type = "ACTION")
    public ApiResponse<Map<String, Object>> exceptionReport(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.reportException(req, operator()));
    }

    /** 处理异常（仅 LEADER）。body:{exceptionId, resolution} */
    @PostMapping("/exception/handle")
    @RequirePerm(value = F_EXC_HANDLE, name = "处理异常", type = "ACTION")
    public ApiResponse<Map<String, Object>> exceptionHandle(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.handleException(str(req.get("exceptionId")),
                str(req.get("resolution")), operator()));
    }

    /** 分派异常（仅 LEADER）：处理人必须启用且绑定当前仓。body:{exceptionId, assignee} */
    @PostMapping("/exception/assign")
    @RequirePerm(value = F_EXC_ASSIGN, name = "分派异常", type = "ACTION")
    public ApiResponse<Map<String, Object>> exceptionAssign(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(internal.assignException(str(req.get("exceptionId")),
                str(req.get("assignee")), operator()));
    }

    // ==================== 主管派工 ====================

    /** 全仓拣货任务池（仅 LEADER）。body:{scope:'all'} */
    @PostMapping("/task-assign/tasks")
    @RequirePerm(value = F_ASSIGN_VIEW, name = "派工任务池")
    public ApiResponse<List<Map<String, Object>>> assignTasks(@RequestBody(required = false) Map<String, Object> req) {
        return ApiResponse.ok(service.pickTasks(operator(), "all", null));
    }

    /** 指派拣货任务（仅 LEADER）：目标人必须启用且绑定当前仓。body:{taskId, toAssignee} */
    @PostMapping("/task-assign/assign")
    @RequirePerm(value = F_ASSIGN_ASSIGN, name = "指派任务", type = "ACTION")
    public ApiResponse<Map<String, Object>> assign(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.assignTask(str(req.get("taskId")), str(req.get("toAssignee")), operator()));
    }

    /** 撤回指派（仅 LEADER）：已开始拣货的任务不可撤回。body:{taskId} */
    @PostMapping("/task-assign/recall")
    @RequirePerm(value = F_ASSIGN_RECALL, name = "撤回指派", type = "ACTION")
    public ApiResponse<Map<String, Object>> recall(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.recallTask(str(req.get("taskId")), operator()));
    }

    // ==================== 绩效 ====================

    /**
     * 绩效查询。主码 view_self（全员，强制只看本人，PDA-006）；
     * scope=team 为载荷 view_team（仅 LEADER，看当前仓绑定用户）；
     * export=true 为载荷 performance.export（仅 LEADER）。body:{scope?, export?, from?, to?}
     */
    @PostMapping("/performance")
    @RequirePerm(value = F_PERF_SELF, name = "我的绩效",
            alsoRegister = {F_PERF_TEAM, F_PERF_EXPORT})
    public ApiResponse<List<Map<String, Object>>> performance(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = req == null ? Map.of() : req;
        LocalDate today = LocalDate.now();
        LocalDate from = dateOrNull(r.get("from"));
        if (from == null) from = today;
        LocalDate to = dateOrNull(r.get("to"));
        if (to == null) to = today;
        if (bool(r.get("export"))) checkPerm(F_PERF_EXPORT);
        if ("team".equalsIgnoreCase(str(r.get("scope")))) {
            checkPerm(F_PERF_TEAM);
            return ApiResponse.ok(internal.listPerformance(from, to, null, warehouseResolver.currentWarehouseId()));
        }
        return ApiResponse.ok(internal.listPerformance(from, to, operator(), null));
    }

    // ==================== 拣货位商品绑定（PDA 快速维护，归上架类权限） ====================

    /** 扫库位/商品查绑定。body:{binCode?, goodsCode?}（仓库强制登录仓） */
    @PostMapping("/binding/lookup")
    @RequirePerm(value = F_PUTAWAY_VIEW, name = "查拣货位绑定")
    public ApiResponse<List<Map<String, Object>>> bindingLookup(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(binding.lookup(null, str(req.get("binCode")), str(req.get("goodsCode"))));
    }

    /** 绑定（改放库位类维护，free_bin）。body:{zoneCode,binCode,goodsCode,goodsName,bindingType?,minQty?,maxQty?} */
    @PostMapping("/binding/bind")
    @RequirePerm(value = F_PUTAWAY_FREE, name = "拣货位绑定", type = "ACTION")
    public ApiResponse<Map<String, Object>> bindingBind(@RequestBody Map<String, Object> req) {
        req.put("source", "PDA");
        req.remove("operator");
        return ApiResponse.ok(binding.bind(req));
    }

    /** 解绑。body:{bindingId} */
    @PostMapping("/binding/unbind")
    @RequirePerm(value = F_PUTAWAY_FREE, name = "拣货位解绑", type = "ACTION")
    public ApiResponse<Map<String, Object>> bindingUnbind(@RequestBody Map<String, Object> req) {
        req.put("source", "PDA");
        req.remove("operator");
        return ApiResponse.ok(binding.unbind(req));
    }

    /** 转移绑定：旧库位→新库位。body:{goodsCode, fromBin, toBin, toZoneCode?} */
    @PostMapping("/binding/transfer")
    @RequirePerm(value = F_PUTAWAY_FREE, name = "拣货位改绑", type = "ACTION")
    public ApiResponse<Map<String, Object>> bindingTransfer(@RequestBody Map<String, Object> req) {
        req.put("source", "PDA");
        req.remove("operator");
        return ApiResponse.ok(binding.transfer(req));
    }

    // ==================== helpers ====================

    /** 当前自然人（PdaAppGuard 已保证 WMS_PDA 登录态，这里直接取）。 */
    private static String operator() {
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null || p.username() == null || p.username().isBlank()) {
            throw new PermissionDeniedException("登录信息已失效，请重新登录");
        }
        return p.username();
    }

    /** 载荷功能点裁决：无权限抛 403（全局处理器转 HTTP 403）。 */
    private void checkPerm(String code) {
        if (!permissionService.hasFunc(code)) {
            throw new PermissionDeniedException("无操作权限：" + code);
        }
    }

    /** 入库类型 → 功能组：receive（采购）/ receive_return（退货）/ other_inbound（其他/调拨）。 */
    private static String receiveGroup(String inboundType) {
        String t = inboundType == null ? "" : inboundType.toUpperCase(Locale.ROOT);
        if ("SALES_RETURN".equals(t) || "RETURN".equals(t) || "REJECT".equals(t)) return "receive_return";
        if ("OTHER".equals(t) || "TRANSFER".equals(t)) return "other_inbound";
        return "receive";
    }

    /** 入库类型 → 对应"查看"功能点。 */
    private static String receiveGroupView(String inboundType) {
        return switch (receiveGroup(inboundType)) {
            case "receive_return" -> F_RETURN_VIEW;
            case "other_inbound" -> F_OTHER_VIEW;
            default -> F_RECEIVE_VIEW;
        };
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String strOr(Object o, String dft) { String s = str(o); return s.isEmpty() ? dft : s; }
    private static LocalDate dateOrNull(Object o) {
        String s = str(o);
        if (s.isEmpty()) return null;
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
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
