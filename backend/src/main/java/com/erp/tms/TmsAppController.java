package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.tms.service.TmsAuthService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/**
 * TMS 司机 APP 端接口。
 *
 * 接口：
 *   POST /tms/app/login          司机登录（返回 JWT）
 *   POST /tms/app/profile        司机信息 + 所属线路
 *   POST /tms/app/today-tasks    今日任务（含发货单 + 退货单取货任务）
 *   POST /tms/app/dispatch/detail  调度单详情（APP 用）
 *   POST /tms/app/return/detail  退货单明细（逐商品退货数量）
 *   POST /tms/app/return/sign    退货签收（按行回写 signed_qty，联动退货单/入库单/应收）
 *   POST /tms/app/trip/history   历史配送任务（仅本人，默认近 30 天）
 *   POST /tms/app/driver/stats   司机绩效统计（真实单据聚合）
 *   POST /tms/app/collect/records 收款记录（门店级签收流水，仅本人）
 *
 * 鉴权：复用 JwtAuthFilter，登录时 subject=driverId，roleCode=DRIVER。
 */
@RestController
@RequestMapping("/tms/app")
public class TmsAppController {

    /**
     * 明细「已处理」状态集合。
     *
     * 原来这个判定散落两套口径，是实打实的 bug 源：
     *   /home/overview 用 {DELIVERED,PARTIAL,REJECTED}；
     *   /delivering/stores 用 status='PENDING' 的反向判定（等价于把 RETURNED 也算已处理）。
     * 于是一张 RETURNED 的退货明细，在首页被算作「待配送」、在配送中页被算作「已完成」，
     * 同一门店两个页面显示的剩余数不一致。
     * 这里统一收拢到一处：RETURNED（退货已收回）同样是终态，必须算已处理。
     * 唯一还没进终态的是 PENDING。
     */
    static final Set<String> DETAIL_DONE = Set.of("DELIVERED", "PARTIAL", "REJECTED", "RETURNED");

    /** 已发车（任务已在车上、归【配送中】管）的调度单状态。 */
    static final Set<String> DISPATCH_ON_ROAD = Set.of("DEPARTED", "DELIVERING");

    /** 未发车（仍在首页走 接单→装车→发车）的调度单状态。 */
    static final Set<String> DISPATCH_BEFORE_DEPART = Set.of("ASSIGNED", "ACCEPTED", "LOADED");

    private final JdbcTemplate jdbcTemplate;
    private final TmsAuthService authService;
    private final com.erp.sales.SalesReturnController salesReturnController;
    private final com.erp.system.SysParamService sysParamService;

    public TmsAppController(JdbcTemplate jdbcTemplate, TmsAuthService authService,
                            com.erp.sales.SalesReturnController salesReturnController,
                            com.erp.system.SysParamService sysParamService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
        this.salesReturnController = salesReturnController;
        this.sysParamService = sysParamService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String mobile = TmsUtil.str(body.get("mobile"));
        String verifyCode = TmsUtil.str(body.get("verifyCode"));
        return ApiResponse.ok(authService.login(mobile, verifyCode));
    }

    /**
     * APP 参数快照（PRD-26 §5.5）。
     *
     * <p>为什么单独开一个接口而不只挂在登录响应上：参数改了之后，已登录的司机
     * 不会重新走 login，若只在登录时下发，改配置要等司机退出重进才生效。
     * APP 在冷启动 restore、下拉刷新任务列表时调本接口刷新缓存。
     *
     * <p>与 {@code /login} 返回的 params 是同一份数据（都走 appParamSnapshot），
     * 不存在两套默认值。
     */
    @PostMapping("/params")
    public ApiResponse<Map<String, Object>> params() {
        return ApiResponse.ok(authService.appParamSnapshot());
    }

    @PostMapping("/profile")
    public ApiResponse<Map<String, Object>> profile() {
        Map<String, Object> info = new LinkedHashMap<>(authService.getDriverInfo(TmsUtil.currentDriverId()));
        // 附带调度中心联系方式（参数化配置）：系统内无「司机→调度员」归属关系表，
        // 由 TMS_DISPATCHER_PHONE 参数统一维护，留空时 APP 隐藏「联系调度员」入口
        try {
            // 走 queryCamel 统一列名大小写：H2 返回大写、MySQL 返回原样，直接 get 会漏读
            TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT param_key, COALESCE(param_value, default_value) AS param_value
                      FROM sys_param_runtime
                     WHERE param_key IN ('TMS_DISPATCHER_PHONE','TMS_DISPATCHER_NAME')
                    """).forEach(r -> {
                String k = TmsUtil.str(r.get("paramKey"));
                String v = TmsUtil.str(r.get("paramValue"));
                if ("TMS_DISPATCHER_PHONE".equals(k)) info.put("dispatcherPhone", v);
                if ("TMS_DISPATCHER_NAME".equals(k)) info.put("dispatcherName", v);
            });
        } catch (Exception ignore) {
            // 参数未初始化（未跑 V61）时不影响登录后的基本信息展示
        }
        info.putIfAbsent("dispatcherPhone", "");
        info.putIfAbsent("dispatcherName", "调度中心");
        return ApiResponse.ok(info);
    }

    /**
     * 当前任务：当前司机所有未完成的调度单（状态在 ASSIGNED~DELIVERING），
     * 含明细（发货单 + 退货单取货任务）。
     *
     * 为什么不再限定 dispatch_date = CURRENT_DATE：
     *   原实现只查当天，导致昨天及更早未跑完的调度单（如已发车但未签完）
     *   在今日页彻底消失；而历史页只有列表展示、没有作业入口，
     *   司机无法继续完成这些任务，形成业务死角。
     *   现改为「按状态而非按日期」筛选——只要没到终态就属于当前待办，
     *   与历史页「只看已完成/已取消」形成互补且无重叠。
     *
     * 排序：按调度日期升序，越早的积压任务排在越前面，促使司机先清旧账。
     *
     * 入参（可选）：includeOverdue=false 时退化为仅当天，供后续「只看今天」开关使用。
     *
     * 状态集合含 ACCEPTED（V68 新增）：司机接单后单据仍未开工，属于当前待办。
     * 漏掉它会让任务在「点了接单」之后立刻从列表消失，是最容易踩的坑。
     */
    @PostMapping("/today-tasks")
    public ApiResponse<Map<String, Object>> todayTasks(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> params = body == null ? Map.of() : body;
        String driverId = TmsUtil.currentDriverId();
        // 默认包含历史积压；显式传 false 才只看当天
        boolean includeOverdue = params.get("includeOverdue") == null
                || !"false".equalsIgnoreCase(TmsUtil.str(params.get("includeOverdue")));
        // 末尾必须带换行：文本块的内容以换行结尾，但这段拼接串在它之后、
        // 紧接下一个文本块的 ORDER BY，少了换行会拼出 CURRENT_DATEORDER BY
        String dateFilter = includeOverdue ? "" : " AND dispatch_date = CURRENT_DATE\n";
        List<Map<String, Object>> dispatches = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dispatch_id, dispatch_no, dispatch_date, route_line, vehicle_plate, status,
                       loaded_qty, return_qty, store_count, accept_time
                FROM tms_dispatch d
                WHERE driver_id = ? AND status IN ('ASSIGNED','ACCEPTED','LOADED','DEPARTED','DELIVERING')
                  -- V77：该司机对应日期已提交/已审核交账，则当日任务不再算作「进行中」，
                  -- 首页统计与待办卡片都应消失，交账=当日作业闭环。
                  AND NOT EXISTS (
                      SELECT 1 FROM tms_settlement s
                      WHERE s.driver_id = d.driver_id
                        AND s.settle_date = d.dispatch_date
                        AND s.status IN ('PENDING','APPROVED'))
                """ + dateFilter + """
                ORDER BY dispatch_date, dispatch_no
                """, driverId);
        int totalStore = 0;
        BigDecimal totalQty = BigDecimal.ZERO;
        List<Map<String, Object>> allDetails = new ArrayList<>();
        for (Map<String, Object> d : dispatches) {
            String dispatchId = TmsUtil.str(d.get("dispatchId"));
            // 结算方式取门店档案，应收金额取发货单发货金额（含税）：
            //   tms_dispatch_detail.amount 在排线建单时并未写入（恒为默认 0），不能作为应收来源；
            //   sales_receipt.deliver_amount 才是发货时点定死的金额，签收后的 sign_amount 是结果不是「应收」。
            //   退货单（bill_type=RETURN）没有应收概念，LEFT JOIN 自然为 null，由下方置空。
            List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT dd.detail_id, dd.dispatch_id, dd.bill_type, dd.source_bill_no, dd.customer_code,
                           dd.customer_name, dd.qty, dd.sku_count, dd.seq_no, dd.status,
                           -- 与 /delivering/stores 同一口径：快照为空回落主档
                           COALESCE(NULLIF(dd.customer_address, ''), c.shipping_address) AS customer_address,
                           dd.arrive_time, dd.arrive_distance, dd.gps_abnormal,
                           c.longitude, c.latitude, c.contact_name, c.mobile AS contact_mobile,
                           c.settlement_type,
                           r.deliver_amount
                    FROM tms_dispatch_detail dd
                    LEFT JOIN base_customer c ON c.customer_code = dd.customer_code
                    LEFT JOIN sales_receipt r ON r.receipt_no = dd.source_bill_no AND dd.bill_type = 'RECEIPT'
                    WHERE dd.dispatch_id = ?
                    ORDER BY dd.seq_no
                    """, dispatchId);
            for (Map<String, Object> r : details) {
                boolean isReturn = "RETURN".equals(TmsUtil.str(r.get("billType")));
                r.put("billTypeText", isReturn ? "取退" : "发货");
                r.put("dispatchNo", d.get("dispatchNo"));
                r.put("vehiclePlate", d.get("vehiclePlate"));
                r.put("settlementText", TmsUtil.settlementText(r.get("settlementType")));
                r.put("needCollect", !isReturn && TmsUtil.needCollect(r.get("settlementType")));
                // 统一字段名 receivableAmount：语义是「本次上门该收多少」，取货任务恒为 0
                r.put("receivableAmount", isReturn ? BigDecimal.ZERO : TmsUtil.toBd(r.remove("deliverAmount")));
            }
            allDetails.addAll(details);
            totalStore += TmsUtil.toInt(d.get("storeCount"));
            totalQty = totalQty.add(TmsUtil.toBd(d.get("loadedQty")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatches", dispatches);
        result.put("details", allDetails);
        result.put("summary", Map.of(
                "dispatchCount", dispatches.size(),
                "totalStore", totalStore,
                "totalQty", totalQty,
                "returnTaskCount", allDetails.stream().filter(x -> "RETURN".equals(TmsUtil.str(x.get("billType")))).count()
        ));
        return ApiResponse.ok(result);
    }

    /**
     * 首页概览（APP 首页）。
     *
     * 为什么另起一个接口而不复用 /today-tasks：
     *   首页只要「各状态几单、待交账多少、下一站是哪、有没有待接单」这几个数字，
     *   /today-tasks 会把全部门店明细一次拉回来（几十到上百行），首页用不上却要传输和解析。
     *
     * 为什么待交账金额不复用 /settlement/summary：
     *   1. 它的 totalAmount 取 tms_dispatch_detail.amount，该字段建单时从未写入、恒为 0；
     *   2. 它在「今日无任务」时返回 404，首页是登录后第一屏，不能因为没排班就报错。
     *   本接口的待交账口径与交账页的 submitAmount 保持一致（现金实收 - 退货退款），
     *   金额本身来自 tms_sign_record.collect_amount（实际收到的钱），不受上述缺陷影响。
     *
     * 待交账只统计「今日且未提交交账」：交账是按天结清的，
     * 昨天的钱昨天已经交过，混进来会让司机以为自己少交了。
     */
    @PostMapping("/home/overview")
    public ApiResponse<Map<String, Object>> homeOverview() {
        String driverId = TmsUtil.currentDriverId();

        // ---- 1. 未完成调度单按状态计数 ----
        List<Map<String, Object>> dispatches = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dispatch_id, dispatch_no, dispatch_date, route_line, vehicle_plate, status,
                       loaded_qty, return_qty, store_count, accept_time, depart_time, parent_dispatch_id
                FROM tms_dispatch d
                WHERE driver_id = ? AND status IN ('ASSIGNED','ACCEPTED','LOADED','DEPARTED','DELIVERING')
                  -- V77：当日已交账则任务不再进入首页进行中统计/卡片（与 /today-tasks 同口径）
                  AND NOT EXISTS (
                      SELECT 1 FROM tms_settlement s
                      WHERE s.driver_id = d.driver_id
                        AND s.settle_date = d.dispatch_date
                        AND s.status IN ('PENDING','APPROVED'))
                ORDER BY dispatch_date, dispatch_no
                """, driverId);

        // 未发车调度单单独成列表（ASSIGNED/ACCEPTED/LOADED）：
        // 首页的工作流卡片就靠它渲染。这里刻意**不是**只给待接单：
        //   接单只是承接动作，接完还要装车、发车，任务并没有离开司机的手。
        //   原来只返回 ASSIGNED，司机一点「接单」卡片就消失了，
        //   后续装车/发车入口只能靠下面那张「待配送任务列表」找，
        //   而那张列表已按需求移除，所以必须让卡片一直留到发车。
        // 发车（DEPARTED/DELIVERING）后本列表不再包含它，任务转由【配送中】承接，
        // 这正好实现「只有发车后才从首页消失进入配送中」。
        List<Map<String, Object>> pendingDispatches = new ArrayList<>();
        List<Map<String, Object>> pendingAccept = new ArrayList<>();
        String currentDispatchId = "";
        String currentStatus = "";
        for (Map<String, Object> d : dispatches) {
            String st = TmsUtil.str(d.get("status"));
            d.put("statusText", resolveDispatchStatus(st));
            // 追加单在卡片上要打标，否则司机看到两张单号不同的卡片会以为要跑两趟车
            d.put("appended", !TmsUtil.str(d.get("parentDispatchId")).isEmpty());
            if (Set.of("ASSIGNED", "ACCEPTED", "LOADED").contains(st)) {
                pendingDispatches.add(d);
                if ("ASSIGNED".equals(st)) pendingAccept.add(d);
            }
            if (!"ASSIGNED".equals(st) && currentDispatchId.isEmpty()) {
                // 当前作业单 = 最早一张已接单及之后的单，首页的「装车/发车」按钮针对它
                currentDispatchId = TmsUtil.str(d.get("dispatchId"));
                currentStatus = st;
            }
        }
        // 卡片上要显示「配送点 N 个 / 发货单 M 张 · X 件 / 代收货款 ¥Y」，
        // 主表的 store_count、loaded_qty 是建单时的快照，够用；
        // 但代收货款要按客户结算方式重算（COD 才计入），主表 amount 恒 0 不可用
        // （insertDetail 从不写 amount，是既有缺陷），所以单独聚合一次明细。
        if (!pendingDispatches.isEmpty()) {
            fillDispatchCardStat(pendingDispatches, driverId);
        }

        // ---- 2. 门店级任务量（按状态分档）----
        // 门店数而非单据数：司机对「还有几家没送」有概念，对「还有几张单」没概念。
        // 口径必须与 /delivering/stores 完全一致，否则首页算 1 家、配送中页列 2 行，
        // 司机会以为漏了一站。两处同步改为：
        //   1) 只统计已发车的配送点（dd.depart_time IS NOT NULL）——未发车的任务在上面的
        //      pendingDispatches 卡片里体现，混进「待送门店数」会让司机
        //      以为车上装着还没装的货；
        //      注意口径是配送点级而非调度单级：支持部分发车后，同一张单里
        //      已发车与未发车的点会并存，只看 d.status 会把没装的点也算进来。
        //   2) 归并键只用 customerCode（不带 dispatchId）——追加单与原单
        //      本就是同一趟车，同门店必须并成一行。
        Map<String, Object> storeStat = new LinkedHashMap<>();
        int totalStore = 0, doneStore = 0, pendingStore = 0;
        if (!dispatches.isEmpty()) {
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT dd.customer_code, dd.status
                    FROM tms_dispatch_detail dd
                    JOIN tms_dispatch d ON d.dispatch_id = dd.dispatch_id
                    WHERE d.driver_id = ? AND dd.depart_time IS NOT NULL
                    """, driverId);
            // 一个门店只要还有一张未签收的单就算「待配送」，全签完才算完成
            Map<String, Boolean> storeDone = new LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                String key = TmsUtil.str(r.get("customerCode"));
                boolean settled = DETAIL_DONE.contains(TmsUtil.str(r.get("status")));
                storeDone.merge(key, settled, (a, b) -> a && b);
            }
            totalStore = storeDone.size();
            for (boolean done : storeDone.values()) {
                if (done) doneStore++; else pendingStore++;
            }
        }
        storeStat.put("totalStore", totalStore);
        storeStat.put("doneStore", doneStore);
        storeStat.put("pendingStore", pendingStore);

        // ---- 3. 待交账金额（今日，未提交交账时才有意义）----
        boolean settledToday = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tms_settlement WHERE driver_id=? AND settle_date=CURRENT_DATE AND status IN ('PENDING','APPROVED')",
                Integer.class, driverId) > 0;
        BigDecimal cashAmount = BigDecimal.ZERO;
        BigDecimal returnAmount = BigDecimal.ZERO;
        if (!settledToday) {
            // 收款口径必须与交账页 /settlement/summary 一致，否则首页显示 850、
            // 点进交账页显示另一个数，司机只会认为系统在乱报账。
            //
            // 门店结算（新流程）：钱记在 tms_store_settlement_account，一次结算可拆多个资金账户。
            // 现金判定走 base_fund_account.parent_code = '01'（系统内置一级分类「现金」），
            // 不能用 account_type——该列在种子数据里全是空串，从未被赋值。
            cashAmount = TmsUtil.toBd(jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(sa.amount), 0)
                    FROM tms_store_settlement_account sa
                    JOIN tms_store_settlement ss ON ss.settle_id = sa.settle_id
                    JOIN tms_dispatch d ON d.dispatch_id = ss.dispatch_id
                    LEFT JOIN base_fund_account f ON f.fund_account_id = sa.fund_account_id
                    WHERE ss.driver_id = ? AND d.dispatch_date = CURRENT_DATE
                      AND ss.settle_status = 'SETTLED'
                      AND (f.parent_code = '01' OR sa.fund_account_id = 'FA_SYS_01')
                    """, BigDecimal.class, driverId));
            // 旧签收路径：历史数据直接把钱记在 tms_sign_record 上。
            // 门店结算走 writeSignRecord 时 collect_amount 恒为 0，两者不会重复计算。
            // 关联 tms_dispatch 限定本人 + 今日，不能只按 sign_time 过滤：
            // 签收记录表里是全公司的数据，漏了 driver_id 就会把别人的钱算到自己头上。
            cashAmount = cashAmount.add(TmsUtil.toBd(jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(s.collect_amount), 0) FROM tms_sign_record s
                    JOIN tms_dispatch d ON d.dispatch_id = s.dispatch_id
                    WHERE d.driver_id = ? AND d.dispatch_date = CURRENT_DATE AND s.pay_method = '现金'
                    """, BigDecimal.class, driverId)));
            // 退货退款与交账页同源：取 sales_return_apply.return_amount。
            // 原先取 tms_dispatch_detail.amount，该列在配送单上恒为 0（两处 INSERT 都不写），
            // 导致应交回金额虚高；已随交账页一并修正为退货申请上的真值。
            //
            // 必须先按 source_bill_no 去重再 JOIN 求和：dd 与 ra 是多对一，
            // 同一张退货申请被拆到多个调度明细行（改派、追加、返仓重排都会产生）时，
            // 直接 SUM 会把整单金额按行数翻倍，司机看到的应交回金额随之虚低。
            //
            // 只算已回收（DELIVERED/PARTIAL）的退货：货还没拉回来就冲减，
            // 会让司机以为这笔钱不用交，当天现金必然短款。
            returnAmount = TmsUtil.toBd(jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(ra.return_amount), 0) FROM (
                        SELECT DISTINCT dd.source_bill_no
                        FROM tms_dispatch_detail dd
                        JOIN tms_dispatch d ON d.dispatch_id = dd.dispatch_id
                        WHERE d.driver_id = ? AND d.dispatch_date = CURRENT_DATE
                          AND dd.bill_type = 'RETURN'
                          AND dd.status IN ('DELIVERED', 'PARTIAL')
                    ) t
                    JOIN sales_return_apply ra ON ra.apply_no = t.source_bill_no
                    """, BigDecimal.class, driverId));
        }
        Map<String, Object> settlement = new LinkedHashMap<>();
        settlement.put("settledToday", settledToday);
        settlement.put("cashAmount", cashAmount);
        settlement.put("returnAmount", returnAmount);
        // 应交回 = 实收现金。门店结算已在应结净额里冲减过退货（司机收到的本就是净额），
        // 这里再减一次等于让司机白交一遍退货款。returnAmount 仅作展示。
        settlement.put("submitAmount", cashAmount);

        // ---- 4. 下一站 ----
        // 只看已发车的单：没发车的货还在库里，谈「下一站」没有意义。
        // 取 seq_no 最小的未签收门店。
        // 不在 SQL 里 LIMIT 1：同店可能有多张单，需要整段结果来数出该店的 billCount，
        // 只取一行就没法算了。首页只用第一行（下一站是哪），单据明细留给详情页。
        List<Map<String, Object>> next = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dd.dispatch_id, dd.detail_id, dd.customer_code, dd.customer_name,
                       -- 与 /delivering/stores 同一口径：快照为空回落主档，
                       -- 否则首页「下一站」会出现有店名没地址的情况。
                       COALESCE(NULLIF(dd.customer_address, ''), c.shipping_address) AS customer_address,
                       dd.seq_no, dd.bill_type, c.contact_name, c.mobile AS contact_mobile,
                       c.longitude, c.latitude
                FROM tms_dispatch_detail dd
                JOIN tms_dispatch d ON d.dispatch_id = dd.dispatch_id
                LEFT JOIN base_customer c ON c.customer_code = dd.customer_code
                WHERE d.driver_id = ? AND dd.depart_time IS NOT NULL
                  AND dd.status NOT IN ('DELIVERED','PARTIAL','REJECTED','RETURNED')
                ORDER BY dd.seq_no, dd.detail_id
                """, driverId);
        Map<String, Object> nextStore = null;
        if (!next.isEmpty()) {
            nextStore = next.get(0);
            String code = TmsUtil.str(nextStore.get("customerCode"));
            // 该门店名下待办单数：司机到店前要知道这站是一单还是三单。
            // 不再按 dispatchId 过滤：追加单与原单是同一趟车，
            // 同店的单必须一并数出来，否则司机到店只掏一半货。
            long billCount = next.stream()
                    .filter(x -> code.equals(TmsUtil.str(x.get("customerCode"))))
                    .count();
            nextStore.put("billCount", billCount);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchCount", dispatches.size());
        result.put("pendingAcceptCount", pendingAccept.size());
        result.put("pendingAccept", pendingAccept);
        // 未发车调度单（含待接单/已接单/已装车）：首页卡片的唯一数据源。
        // pendingAccept 保留为 pendingDispatches 的子集，兼容旧版 APP 不至于白屏。
        result.put("pendingDispatches", pendingDispatches);
        result.put("pendingDispatchCount", pendingDispatches.size());
        result.put("currentDispatchId", currentDispatchId);
        result.put("currentStatus", currentStatus);
        result.put("currentStatusText", resolveDispatchStatus(currentStatus));
        result.put("storeStat", storeStat);
        result.put("settlement", settlement);
        result.put("nextStore", nextStore);
        return ApiResponse.ok(result);
    }

    /**
     * 为首页调度单卡片补充统计：配送点数、发货单数、退货单数、件数、代收货款。
     *
     * 为什么不直接用主表快照：
     *   - store_count / loaded_qty / return_qty 是建单那一刻算好的，卡片显示够用；
     *     但为了与「查看清单」页逐点加总的数字严格一致，这里一律按明细现算，
     *     否则调度员在 ERP 端删过明细的单，卡片和清单页会差几件，司机会以为少装货。
     *   - amount 主表恒为 0（insertDetail 的 INSERT 列表里没有 amount），
     *     必须走 sales_receipt.deliver_amount 才拿得到真实金额。
     *
     * 代收货款只算 COD 客户：月结客户的货款不由司机收，混进去司机会以为要多收几千块。
     * 口径与 /delivering/stores 的 needCollect 一致（都走 TmsUtil.needCollect）。
     *
     * 一次性查完所有单的明细再在内存里分组，避免按单 N 次查询。
     */
    private void fillDispatchCardStat(List<Map<String, Object>> targets, String driverId) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> d : targets) {
            byId.put(TmsUtil.str(d.get("dispatchId")), d);
            d.put("storeCount", 0);
            d.put("receiptCount", 0);
            d.put("returnCount", 0);
            d.put("totalQty", BigDecimal.ZERO);
            d.put("collectAmount", BigDecimal.ZERO);
        }
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dd.dispatch_id, dd.bill_type, dd.customer_code, dd.qty,
                       c.settlement_type, r.deliver_amount
                FROM tms_dispatch_detail dd
                JOIN tms_dispatch d ON d.dispatch_id = dd.dispatch_id
                LEFT JOIN base_customer c ON c.customer_code = dd.customer_code
                LEFT JOIN sales_receipt r ON r.receipt_no = dd.source_bill_no AND dd.bill_type = 'RECEIPT'
                WHERE d.driver_id = ? AND d.status IN ('ASSIGNED','ACCEPTED','LOADED')
                """, driverId);
        // 配送点数要去重，不能按明细行数累加（同店多单只算一个点）
        Map<String, Set<String>> storeSets = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> d = byId.get(TmsUtil.str(r.get("dispatchId")));
            if (d == null) continue;
            boolean isReturn = "RETURN".equals(TmsUtil.str(r.get("billType")));
            storeSets.computeIfAbsent(TmsUtil.str(r.get("dispatchId")), k -> new HashSet<>())
                    .add(TmsUtil.str(r.get("customerCode")));
            d.put(isReturn ? "returnCount" : "receiptCount",
                    TmsUtil.toInt(d.get(isReturn ? "returnCount" : "receiptCount")) + 1);
            d.put("totalQty", TmsUtil.toBd(d.get("totalQty")).add(TmsUtil.toBd(r.get("qty"))));
            if (!isReturn && TmsUtil.needCollect(r.get("settlementType"))) {
                d.put("collectAmount", TmsUtil.toBd(d.get("collectAmount"))
                        .add(TmsUtil.toBd(r.get("deliverAmount"))));
            }
        }
        storeSets.forEach((id, set) -> {
            Map<String, Object> d = byId.get(id);
            if (d != null) d.put("storeCount", set.size());
        });
    }

    /** 调度单状态中文映射（与 ERP 端 TmsDispatchController 保持一致）。 */
    private String resolveDispatchStatus(String s) {
        return switch (s == null ? "" : s) {
            case "DRAFT" -> "草稿";
            case "ASSIGNED" -> "待接单";
            // V77：司机接单后进入装车阶段，APP 卡片显示「待装车」比「已接单」更贴合下一步动作；
            // 后台调度端 TmsDispatchController 的映射保持「已接单」，两端各取所需不互相影响。
            case "ACCEPTED" -> "待装车";
            case "LOADED" -> "已装车";
            case "DEPARTED" -> "已发车";
            case "DELIVERING" -> "配送中";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            default -> s == null ? "" : s;
        };
    }

    /**
     * 配送中门店列表（APP【配送中】页）。
     *
     * 与 /today-tasks 的关系：**同源不同粒度**。
     *   - /today-tasks 的 details 是「一条 detail = 一张单据」，同店多单会出现多行，
     *     签收链路依赖其中的 detailId，粒度不能动；
     *   - 本接口是「一行 = 一个门店」，把同店多单归并，额外给出 billCount（单数）。
     * 二者的明细 SQL 保持同样的表关联与取值口径（尤其应收金额同取
     * sales_receipt.deliver_amount），保证金额、结算方式不出现两套数。
     *
     * 为什么在 Java 侧归并而不用 GROUP BY：
     *   应收金额来自 LEFT JOIN sales_receipt，是一对多关系。若直接
     *   SUM(r.deliver_amount) GROUP BY customer_code，同一发货单在多行上重复出现时
     *   金额会被放大。改为先取明细（与签收页完全同一份数据）再按门店累加，
     *   金额天然与逐单之和相等，对不上账的风险从根上消除。
     *
     * 排序沿用 seq_no（排线时定下的配送顺序），门店取其名下最小 seq_no，
     * 使列表顺序与司机实际跑店顺序一致。
     *
     * 两条口径在本轮优化中改动（务必与 /home/overview 的 storeStat 保持同步）：
     *   1) 只取已发车的调度单（DEPARTED/DELIVERING）。
     *      原来把 ASSIGNED/ACCEPTED/LOADED 也算进来，于是一张刚派下来还没装车的单
     *      就已经出现在【配送中】页，司机会去点「到店签收」，而货其实还在库里。
     *      未发车的任务归首页的 接单→装车→发车 流程管，两边不再重叠。
     *   2) 归并键从 dispatchId|customerCode 改为纯 customerCode（跨调度单合并）。
     *      司机在途时追加的任务会落成一张新调度单（见 V69），
     *      若按调度单分开归并，同一家门店会在列表里出现两行，
     *      司机到店后只掏其中一行的货，另一行漏送。
     *      跨单合并后同店恒为一行，详情页一次性列出该店在所有在途单下的全部单据。
     *      签收仍以 detailId 为准，与调度单无关，因此合并不影响签收链路。
     *   3) 已完成门店（名下单据全部终态）直接不返回。
     *      司机对「今天送过谁」没有当场查看需求，那是历史页的事；
     *      留在配送中只会让列表越跑越长、下一站越翻越远。
     *      summary.doneStore 仍然给出数量，进度条不会因此失真。
     */
    @PostMapping("/delivering/stores")
    public ApiResponse<Map<String, Object>> deliveringStores(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> params = body == null ? Map.of() : body;
        String driverId = TmsUtil.currentDriverId();
        String dispatchId = TmsUtil.str(params.get("dispatchId"));

        // 只看已发车的调度单：未发车的货还在库里，不属于「配送中」
        List<Object> args = new ArrayList<>();
        args.add(driverId);
        String extra = "";
        if (!dispatchId.isEmpty()) {
            extra = " AND dd.dispatch_id = ?";
            args.add(dispatchId);
        }

        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dd.detail_id, dd.dispatch_id, dd.bill_type, dd.source_bill_no, dd.customer_code,
                       dd.customer_name, dd.qty, dd.sku_count, dd.seq_no, dd.status,
                       dd.arrive_time, dd.arrive_distance, dd.gps_abnormal,
                       -- 地址优先用建单时的快照（历史单要还原当时地址），
                       -- 快照为空则回落主档：老单据建单时客户可能还没填地址，
                       -- 只认快照会让司机拿到一张没地址的送货单，导航直接不可用。
                       COALESCE(NULLIF(dd.customer_address, ''), c.shipping_address) AS customer_address,
                       c.longitude, c.latitude, c.contact_name, c.mobile AS contact_mobile,
                       c.settlement_type,
                       d.dispatch_no, d.vehicle_plate,
                       r.deliver_amount
                FROM tms_dispatch_detail dd
                JOIN tms_dispatch d ON d.dispatch_id = dd.dispatch_id
                LEFT JOIN base_customer c ON c.customer_code = dd.customer_code
                LEFT JOIN sales_receipt r ON r.receipt_no = dd.source_bill_no AND dd.bill_type = 'RECEIPT'
                WHERE d.driver_id = ? AND dd.depart_time IS NOT NULL
                """ + extra + """
                ORDER BY dd.seq_no, dd.detail_id
                """, args.toArray());

        // 按 customer_code 归并；LinkedHashMap 保持 seq_no 排序结果
        Map<String, Map<String, Object>> storeMap = new LinkedHashMap<>();
        for (Map<String, Object> r : details) {
            boolean isReturn = "RETURN".equals(TmsUtil.str(r.get("billType")));
            BigDecimal amount = isReturn ? BigDecimal.ZERO : TmsUtil.toBd(r.get("deliverAmount"));
            // 跨调度单按门店合并：同一家店无论落在哪张（含追加的）调度单上都只出一行。
            // 签收动作以 detailId 为准，不依赖 dispatchId，所以合并是安全的。
            String key = TmsUtil.str(r.get("customerCode"));

            Map<String, Object> store = storeMap.get(key);
            if (store == null) {
                store = new LinkedHashMap<>();
                store.put("dispatchId", r.get("dispatchId"));
                store.put("dispatchNo", r.get("dispatchNo"));
                store.put("vehiclePlate", r.get("vehiclePlate"));
                store.put("customerCode", r.get("customerCode"));
                store.put("customerName", r.get("customerName"));
                store.put("customerAddress", r.get("customerAddress"));
                store.put("contactName", r.get("contactName"));
                store.put("contactMobile", r.get("contactMobile"));
                store.put("longitude", r.get("longitude"));
                store.put("latitude", r.get("latitude"));
                store.put("settlementType", r.get("settlementType"));
                store.put("settlementText", TmsUtil.settlementText(r.get("settlementType")));
                // seqNo 先落首行值，后面按 min 收敛：同店多单的 seq_no 不一定连续，
                // 取最小值才与 /loading/stores 的门店排序键口径一致。
                store.put("seqNo", TmsUtil.toInt(r.get("seqNo")));
                // 跨单合并后 seq_no 会在不同调度单之间重号（每张单都从 10 起排），
                // 只按 seq_no 排序结果不稳定，用首次出现的调度单号作二级键兜底。
                // 注意不能复用 dispatchNo：它下面会被「下一张要送的单」覆盖。
                store.put("_seqDispatchNo", TmsUtil.str(r.get("dispatchNo")));
                store.put("arriveTime", r.get("arriveTime"));
                // 到达打卡的落点单据：/arrive 以 detailId 为必填，而门店是归并结果，
                // 不透出一个具体 detailId，APP 门店行上的「到达」按钮就没法调接口。
                // 取该店未签收的第一行（下面按 PENDING 覆盖），保证打的是还要送的那张单。
                store.put("arriveDetailId", r.get("detailId"));
                store.put("billCount", 0);
                store.put("returnCount", 0);
                store.put("totalQty", BigDecimal.ZERO);
                store.put("totalAmount", BigDecimal.ZERO);
                store.put("pendingCount", 0);
                store.put("hasReturn", false);
                store.put("needCollect", false);
                storeMap.put(key, store);
            }

            store.put("billCount", TmsUtil.toInt(store.get("billCount")) + 1);
            // 门店序号取名下单据的最小 seq_no，与 /loading/stores 保持同一口径
            store.put("seqNo", Math.min(TmsUtil.toInt(store.get("seqNo")), TmsUtil.toInt(r.get("seqNo"))));
            store.put("totalQty", TmsUtil.toBd(store.get("totalQty")).add(TmsUtil.toBd(r.get("qty"))));
            store.put("totalAmount", TmsUtil.toBd(store.get("totalAmount")).add(amount));
            if (isReturn) {
                store.put("returnCount", TmsUtil.toInt(store.get("returnCount")) + 1);
                store.put("hasReturn", true);
            } else if (TmsUtil.needCollect(r.get("settlementType"))) {
                // 只要有一张需现场收款的发货单，门店就需收款
                store.put("needCollect", true);
            }
            if (!DETAIL_DONE.contains(TmsUtil.str(r.get("status")))) {
                store.put("pendingCount", TmsUtil.toInt(store.get("pendingCount")) + 1);
                // 首行可能已签收，用第一条未完成行覆盖打卡落点：
                // 对已签收的单据打到达卡毫无意义（后端也会被 arrive_time 判重挡掉）。
                if (!Boolean.TRUE.equals(store.get("_arriveFixed"))) {
                    store.put("arriveDetailId", r.get("detailId"));
                    // 跨单合并后同店的单可能分属不同调度单，
                    // 门店行上的 dispatchId 必须跟着「下一张要送的单」走，
                    // 否则司机点进详情或打卡时带的是另一张单的 ID。
                    store.put("dispatchId", r.get("dispatchId"));
                    store.put("dispatchNo", r.get("dispatchNo"));
                    store.put("_arriveFixed", true);
                }
            }
            // 到店时间取门店下任一已打卡记录，用于列表区分「已到达/未到达」
            if (store.get("arriveTime") == null && r.get("arriveTime") != null) {
                store.put("arriveTime", r.get("arriveTime"));
            }
        }

        List<Map<String, Object>> allStores = new ArrayList<>(storeMap.values());
        // 跨单合并后 LinkedHashMap 的插入序不再等于配送序：追加单的门店是后查出来的，
        // 但它的 seq_no 可能比原单的更小。这里按（最小 seq_no, 首见调度单号）重排，
        // 保证同一 seq_no 在不同调度单间重号时顺序稳定、不会每次刷新都跳。
        allStores.sort(Comparator
                .comparingInt((Map<String, Object> s) -> TmsUtil.toInt(s.get("seqNo")))
                .thenComparing(s -> TmsUtil.str(s.get("_seqDispatchNo"))));
        // 门店状态由其名下单据推导：全部处理完 = DONE，否则 PENDING。
        // 不新增 DB 字段，避免与 detail.status 产生需要同步的第二份真相。
        for (Map<String, Object> s : allStores) {
            s.put("storeStatus", TmsUtil.toInt(s.get("pendingCount")) == 0 ? "DONE" : "PENDING");
            // 归并过程用的内部标记不外发，避免前端把它当契约字段依赖
            s.remove("_arriveFixed");
            s.remove("_seqDispatchNo");
        }

        // 已完成门店不进列表，只进 summary 计数
        List<Map<String, Object>> stores = allStores.stream()
                .filter(s -> "PENDING".equals(TmsUtil.str(s.get("storeStatus"))))
                .toList();

        // 列表序号全局重编号：seq_no 是「每张调度单内」的排序值，跨单合并后
        // 两家店都可能是 10，APP 上就会并排显示两个「1」。已完成门店被过滤掉后
        // 原始序号也会出现断号，所以按最终可见顺序从 1 连续编号。
        int orderNo = 0;
        for (Map<String, Object> s : stores) {
            s.put("orderNo", ++orderNo);
        }

        BigDecimal sumAmount = BigDecimal.ZERO;
        long pendingBill = 0;
        for (Map<String, Object> s : stores) {
            // 金额只累计还要送的门店：已送完的钱已经收了，
            // 顶部「待收款合计」如果把已收的也算进去，司机会以为还差一大笔。
            sumAmount = sumAmount.add(TmsUtil.toBd(s.get("totalAmount")));
            pendingBill += TmsUtil.toInt(s.get("pendingCount"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stores", stores);
        result.put("summary", Map.of(
                // storeCount 是本趟车的总配送点数（含已完成），进度条的分母
                "storeCount", allStores.size(),
                "pendingStore", stores.size(),
                "doneStore", allStores.size() - stores.size(),
                "totalAmount", sumAmount,
                // billCount 改为未完成单据数，与列表里能点开的单据数一致
                "billCount", pendingBill,
                "totalBillCount", details.size()
        ));
        return ApiResponse.ok(result);
    }

    /**
     * 单个门店的单据明细（APP 配送点详情页）。
     *
     * 返回该门店的全部在途单据行，字段与 /today-tasks 的 details 完全一致，
     * 使详情页可以直接复用签收页所需的 detailId、billType 等，无需二次换算。
     *
     * dispatchId 已改为**可选**：/delivering/stores 现在跨调度单按门店合并，
     * 一行门店背后的单据可能分属原单与追加单。若这里仍强制按单过滤，
     * 详情页就只能显示其中一张单的货，另一张在司机眼里凭空消失。
     * 传了 dispatchId 就按单过滤（历史页等按单查看的场景仍然需要），
     * 不传则列出该司机名下该门店的全部在途单据。
     */
    @PostMapping("/delivering/store-bills")
    public ApiResponse<Map<String, Object>> deliveringStoreBills(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String customerCode = TmsUtil.str(body.get("customerCode"));
        if (customerCode.isEmpty()) {
            return ApiResponse.fail("400", "customerCode 不能为空");
        }

        List<Object> args = new ArrayList<>();
        args.add(customerCode);
        args.add(driverId);
        String extra = "";
        if (dispatchId.isEmpty()) {
            // 不指定调度单时限定在途单，否则会把司机历史上送过这家店的所有单据全翻出来。
            // 用 dd.depart_time 而非 d.status：部分发车后主状态停在 LOADED，
            // 只认 DEPARTED/DELIVERING 会让已发车的门店点不开单据列表。
            extra = " AND dd.depart_time IS NOT NULL";
        } else {
            extra = " AND dd.dispatch_id = ?";
            args.add(dispatchId);
        }

        // 带 driver_id 条件：防止越权查看他人调度单的门店明细
        List<Map<String, Object>> bills = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dd.detail_id, dd.dispatch_id, dd.bill_type, dd.source_bill_no, dd.source_bill_id,
                       dd.customer_code, dd.customer_name, dd.qty, dd.sku_count,
                       -- 与 /delivering/stores 同一口径：快照为空回落主档
                       COALESCE(NULLIF(dd.customer_address, ''), c.shipping_address) AS customer_address,
                       dd.seq_no, dd.status, dd.sign_time, dd.remark,
                       dd.arrive_time, dd.arrive_distance, dd.gps_abnormal,
                       c.longitude, c.latitude, c.contact_name, c.mobile AS contact_mobile,
                       c.settlement_type,
                       d.dispatch_no, d.vehicle_plate,
                       r.deliver_amount
                FROM tms_dispatch_detail dd
                JOIN tms_dispatch d ON d.dispatch_id = dd.dispatch_id
                LEFT JOIN base_customer c ON c.customer_code = dd.customer_code
                LEFT JOIN sales_receipt r ON r.receipt_no = dd.source_bill_no AND dd.bill_type = 'RECEIPT'
                WHERE dd.customer_code = ? AND d.driver_id = ?
                """ + extra + """
                ORDER BY dd.bill_type, dd.detail_id
                """, args.toArray());

        for (Map<String, Object> r : bills) {
            boolean isReturn = "RETURN".equals(TmsUtil.str(r.get("billType")));
            r.put("billTypeText", isReturn ? "取退" : "发货");
            r.put("settlementText", TmsUtil.settlementText(r.get("settlementType")));
            r.put("needCollect", !isReturn && TmsUtil.needCollect(r.get("settlementType")));
            r.put("receivableAmount", isReturn ? BigDecimal.ZERO : TmsUtil.toBd(r.remove("deliverAmount")));
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        int returnCount = 0;
        int pendingCount = 0;
        for (Map<String, Object> r : bills) {
            totalAmount = totalAmount.add(TmsUtil.toBd(r.get("receivableAmount")));
            totalQty = totalQty.add(TmsUtil.toBd(r.get("qty")));
            if ("RETURN".equals(TmsUtil.str(r.get("billType")))) returnCount++;
            if (!DETAIL_DONE.contains(TmsUtil.str(r.get("status")))) pendingCount++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bills", bills);
        result.put("summary", Map.of(
                "billCount", bills.size(),
                "returnCount", returnCount,
                "pendingCount", pendingCount,
                "totalQty", totalQty,
                "totalAmount", totalAmount
        ));
        if (!bills.isEmpty()) {
            Map<String, Object> f = bills.get(0);
            result.put("store", Map.of(
                    "customerCode", TmsUtil.str(f.get("customerCode")),
                    "customerName", TmsUtil.str(f.get("customerName")),
                    "customerAddress", TmsUtil.str(f.get("customerAddress")),
                    "contactName", TmsUtil.str(f.get("contactName")),
                    "contactMobile", TmsUtil.str(f.get("contactMobile")),
                    "dispatchNo", TmsUtil.str(f.get("dispatchNo"))
            ));
        }
        return ApiResponse.ok(result);
    }

    /**
     * 司机绩效统计（APP「我的」页）。
     *
     * 全部来自真实单据聚合，不做任何估算或补数：
     *   - 累计配送门店数 / 件数 / 收款额：按 tms_dispatch_detail 已签收行统计
     *   - 签收完成率：已签收门店 / 已分配门店（拒收计入分母不计入分子）
     *   - 打卡正常率：GPS 未异常的打卡 / 有打卡记录的门店
     *
     * 关于原型里的「准时率」与「4.9 评分」：
     *   现有表结构没有「计划到店时间」字段，也没有客户评价表，
     *   两项均无数据来源。为避免展示编造数字，改为返回上述可核对口径，
     *   待后续排线支持时间窗 / 上线评价功能后再补。
     *
     * 入参：days（可选，统计窗口天数，默认 0 表示不限期即累计至今）
     */
    @PostMapping("/driver/stats")
    public ApiResponse<Map<String, Object>> driverStats(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> params = body == null ? Map.of() : body;
        String driverId = TmsUtil.currentDriverId();

        List<Object> args = new ArrayList<>();
        args.add(driverId);
        // 统计窗口：默认累计至今；传 days 则只看近 N 天（按调度日期过滤，走 dispatch_date 索引）
        String dateFilter = "";
        int days = TmsUtil.toInt(params.get("days"));
        if (days > 0) {
            dateFilter = " AND d.dispatch_date >= ?";
            args.add(LocalDate.now().minusDays(days).toString());
        }

        // 一次聚合出全部计数，避免多次往返；SUM(CASE WHEN) 在 H2/MySQL 下语义一致。
        // 必须走 queryCamel：H2 返回大写列名，直接按下划线取值会静默得到 null → 统计全为 0
        String sql = """
                SELECT COUNT(*) AS total_store,
                       SUM(CASE WHEN dd.status IN ('DELIVERED','PARTIAL','RETURNED') THEN 1 ELSE 0 END) AS signed_store,
                       SUM(CASE WHEN dd.status = 'REJECTED' THEN 1 ELSE 0 END) AS reject_store,
                       SUM(CASE WHEN dd.status IN ('DELIVERED','PARTIAL','RETURNED') THEN dd.qty ELSE 0 END) AS signed_qty,
                       SUM(CASE WHEN dd.arrive_time IS NOT NULL THEN 1 ELSE 0 END) AS arrive_store,
                       SUM(CASE WHEN dd.arrive_time IS NOT NULL AND dd.gps_abnormal = 1 THEN 1 ELSE 0 END) AS gps_bad_store
                FROM tms_dispatch_detail dd
                JOIN tms_dispatch d ON d.dispatch_id = dd.dispatch_id
                WHERE d.driver_id = ? AND d.status <> 'CANCELLED'
                """ + dateFilter;
        Map<String, Object> agg = TmsUtil.queryCamel(jdbcTemplate, sql, args.toArray()).get(0);

        // 收款额取签收记录（明细表的 amount 是应收，不等于实收）
        String collectSql = """
                SELECT COALESCE(SUM(s.collect_amount), 0) AS collect_amount, COUNT(*) AS sign_count
                FROM tms_sign_record s
                JOIN tms_dispatch d ON d.dispatch_id = s.dispatch_id
                WHERE d.driver_id = ? AND d.status <> 'CANCELLED'
                """ + dateFilter;
        Map<String, Object> collect = TmsUtil.queryCamel(jdbcTemplate, collectSql, args.toArray()).get(0);

        // 出车次数按已完成行程计，统计窗口与上面保持一致（此表用 trip_date）
        StringBuilder tripSql = new StringBuilder(
                "SELECT COUNT(*) FROM tms_delivery_trip WHERE driver_id = ? AND status = 'COMPLETED'");
        List<Object> tripArgs = new ArrayList<>();
        tripArgs.add(driverId);
        if (days > 0) {
            tripSql.append(" AND trip_date >= ?");
            tripArgs.add(LocalDate.now().minusDays(days).toString());
        }
        Integer tripCount = jdbcTemplate.queryForObject(tripSql.toString(), Integer.class, tripArgs.toArray());

        int totalStore = TmsUtil.toInt(agg.get("totalStore"));
        int signedStore = TmsUtil.toInt(agg.get("signedStore"));
        int arriveStore = TmsUtil.toInt(agg.get("arriveStore"));
        int gpsBadStore = TmsUtil.toInt(agg.get("gpsBadStore"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", days);
        result.put("tripCount", tripCount == null ? 0 : tripCount);
        result.put("totalStore", totalStore);
        result.put("signedStore", signedStore);
        result.put("rejectStore", TmsUtil.toInt(agg.get("rejectStore")));
        result.put("signedQty", TmsUtil.toBd(agg.get("signedQty")));
        result.put("collectAmount", TmsUtil.toBd(collect.get("collectAmount")));
        result.put("signCount", TmsUtil.toInt(collect.get("signCount")));
        // 分母为 0 时返回 null 而非 0，让前端显示「—」，避免新司机被展示成 0% 完成率
        result.put("signRate", totalStore > 0 ? Math.round(signedStore * 1000.0 / totalStore) / 10.0 : null);
        result.put("arriveStore", arriveStore);
        result.put("gpsNormalRate", arriveStore > 0 ? Math.round((arriveStore - gpsBadStore) * 1000.0 / arriveStore) / 10.0 : null);
        return ApiResponse.ok(result);
    }

    /** 调度单详情（APP 用，只返回当前司机可见的）。 */
    @PostMapping("/dispatch/detail")
    public ApiResponse<Map<String, Object>> dispatchDetail(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        List<Map<String, Object>> d = jdbcTemplate.queryForList(
                "SELECT * FROM tms_dispatch WHERE dispatch_id=? AND driver_id=?", dispatchId, TmsUtil.currentDriverId());
        if (d.isEmpty()) return ApiResponse.fail("404", "调度单不存在或非本人");
        Map<String, Object> head = TmsUtil.camelize(d.get(0));
        // LEFT JOIN 门店档案透传坐标与联系人，供 APP 打卡围栏比对与「导航前往/联系客户」（dd.* 已含 arrive_* 字段）
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dd.*, c.longitude, c.latitude, c.contact_name, c.mobile AS contact_mobile
                FROM tms_dispatch_detail dd
                LEFT JOIN base_customer c ON c.customer_code = dd.customer_code
                WHERE dd.dispatch_id = ?
                ORDER BY dd.seq_no
                """, dispatchId);
        for (Map<String, Object> r : details) r.put("billTypeText", "RETURN".equals(TmsUtil.str(r.get("billType"))) ? "取退" : "发货");
        head.put("details", details);
        return ApiResponse.ok(head);
    }

    /** 退货单明细：逐商品退货数量，供 APP 录入实收。 */
    @PostMapping("/return/detail")
    public ApiResponse<Map<String, Object>> returnDetail(@RequestBody Map<String, Object> body) {
        String applyNo = TmsUtil.str(body.get("applyNo"));
        if (applyNo.isEmpty()) return ApiResponse.fail("400", "applyNo 不能为空");
        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT apply_id, apply_no, customer_code, customer_name, warehouse, bill_date, qty AS return_qty,
                       signed_qty, return_reason, return_type, logistics_status, driver_id, driver_name, dispatch_id
                FROM sales_return_apply WHERE apply_no = ?
                """, applyNo);
        if (heads.isEmpty()) return ApiResponse.fail("404", "退货单不存在：" + applyNo);
        Map<String, Object> head = TmsUtil.camelize(heads.get(0));
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT detail_id, goods_code, goods_name, spec, unit_name, qty AS return_qty, signed_qty,
                       batch_no, production_date
                FROM sales_return_apply_detail WHERE apply_id = ?
                ORDER BY detail_id
                """, head.get("applyId"));
        head.put("details", details);
        return ApiResponse.ok(head);
    }

    /**
     * 退货签收（司机回收链路的关键节点）。
     * 入参：applyNo, items:[{detailId, goodsCode, signedQty}], customerSigner, remark
     * 前提：logistics_status=已调度 且 driver_id=当前司机
     * <p>
     * V60 起签收不只是打个标记：退货单的回写（按行 signed_qty、重算退货数量/金额、
     * 生成退货入库单、按参数决定是否自动审核写负向应收）统一交给
     * {@code SalesReturnController.onDriverCollected}，本方法只负责 TMS 侧的
     * 司机归属校验、签收记录、照片与调度明细状态，避免两处各写一半口径不一致。
     */
    @PostMapping("/return/sign")
    @Transactional
    public ApiResponse<Map<String, Object>> returnSign(@RequestBody Map<String, Object> body) {
        String applyNo = TmsUtil.str(body.get("applyNo"));
        String customerSigner = TmsUtil.str(body.get("customerSigner"));
        String remark = TmsUtil.str(body.get("remark"));
        String signatureUrl = TmsUtil.str(body.get("signatureUrl"));
        if (applyNo.isEmpty()) return ApiResponse.fail("400", "退货单号不能为空");
        String driverId = TmsUtil.currentDriverId();

        // 照片张数校验（PRD-26 §5.5，TMS_RETURN_PHOTO_COUNT）。
        //
        // 位置很关键：必须放在 onDriverCollected 等任何写库动作之前。
        // @Transactional 默认只对 RuntimeException 回滚，而 return ApiResponse.fail 是
        // 正常返回、不触发回滚——若校验放在写库之后，会出现「接口报错但退货单已回写」
        // 的半成品数据。参数为 0 表示不校验（PRD §3.2 值域 0~5）。
        @SuppressWarnings("unchecked")
        List<String> photoUrls = body.get("photos") instanceof List<?> pl
                ? (List<String>) pl : List.<String>of();
        long validPhotoCount = photoUrls.stream().filter(u -> u != null && !u.isEmpty()).count();
        int requirePhoto = sysParamService.getInt("TMS_RETURN_PHOTO_COUNT", 2, 0, 5);
        if (requirePhoto > 0 && validPhotoCount < requirePhoto) {
            return ApiResponse.fail("400", "请至少拍摄 " + requirePhoto + " 张退货现场照片");
        }

        // 电子签名校验（PRD-26 §P0120，TMS_SIGN_ESIGN_REQUIRED）。
        //
        // 与照片校验同理，必须放在写库之前。开关默认 N，此时完全不校验，
        // 与 APP 侧「不展示签名区」一致；开关为 Y 时 APP 已强制必签，
        // 这里是兜底——旧版本 APP 或直接调接口的场景不能绕过必签要求。
        if (sysParamService.getBool("TMS_SIGN_ESIGN_REQUIRED", false) && signatureUrl.isEmpty()) {
            return ApiResponse.fail("400", "请完成客户电子签名");
        }

        List<Map<String, Object>> heads = jdbcTemplate.queryForList("""
                SELECT apply_id, apply_no, customer_code, customer_name, qty, logistics_status, driver_id, dispatch_id, trip_id
                FROM sales_return_apply WHERE apply_no = ?
                """, applyNo);
        if (heads.isEmpty()) return ApiResponse.fail("404", "退货单不存在：" + applyNo);
        Map<String, Object> h = TmsUtil.camelize(heads.get(0));
        String logisticsStatus = TmsUtil.str(h.get("logisticsStatus"));
        if (!"已调度".equals(logisticsStatus)) {
            return ApiResponse.fail("400", "当前物流状态为「" + logisticsStatus + "」，仅「已调度」可签收");
        }
        String billDriverId = TmsUtil.str(h.get("driverId"));
        if (!billDriverId.isEmpty() && !billDriverId.equals(driverId)) {
            return ApiResponse.fail("400", "非本人退货单，不可签收");
        }
        String applyId = TmsUtil.str(h.get("applyId"));
        String customerCode = TmsUtil.str(h.get("customerCode"));
        String customerName = TmsUtil.str(h.get("customerName"));
        String dispatchId = TmsUtil.str(h.get("dispatchId"));
        String tripId = TmsUtil.str(h.get("tripId"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = body.get("items") == null ? List.of() : (List<Map<String, Object>>) body.get("items");

        // 1. 退货单回写 + 生成入库单 + 按入账时点决定是否自动审核（逐行数量校验也在里面）
        //    这里刻意不 catch IllegalArgumentException：onDriverCollected 与本方法同处一个事务，
        //    catch 掉再 return fail 只会让事务被标记 rollback-only，提交时抛
        //    「Transaction rolled back because it has been marked as rollback-only」，
        //    掩盖真实校验信息。交给 GlobalExceptionHandler 统一转 400 + 整体回滚。
        Map<String, Object> collected =
                salesReturnController.onDriverCollected(applyId, items, TmsUtil.currentUser());
        BigDecimal totalSigned = TmsUtil.toBd(collected.get("signedQty"));
        BigDecimal returnAmount = TmsUtil.toBd(collected.get("returnAmount"));
        String inboundNo = TmsUtil.str(collected.get("inboundNo"));
        boolean autoAudited = Boolean.TRUE.equals(collected.get("autoAudited"));

        // 2. 写签收记录
        //    列数 15、占位符 14（bill_type 用字面量 'RETURN'）、入参 14，三者必须一一对齐；
        //    dispatch_id / detail_id 允许为空：司机可以不经组车、由「指派司机」直接接单回收（V63 放开非空约束）
        String signId = TmsUtil.uuid("QS");
        jdbcTemplate.update("""
                INSERT INTO tms_sign_record(sign_id, dispatch_id, detail_id, trip_id, source_bill_no,
                    customer_code, customer_name, bill_type, sign_type, signed_qty, sign_time, sign_user,
                    customer_signer, customer_sign_img, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RETURN', ?, ?, ?, ?, ?, ?, ?)
                """, signId, dispatchId.isEmpty() ? null : dispatchId, null, tripId.isEmpty() ? null : tripId,
                applyNo, customerCode, customerName,
                totalSigned.compareTo(TmsUtil.toBd(h.get("qty"))) < 0 ? "PARTIAL" : "NORMAL",
                totalSigned, Timestamp.valueOf(TmsUtil.now()), TmsUtil.currentUser(), customerSigner,
                signatureUrl.isEmpty() ? null : signatureUrl, remark);

        // 2.1 保存退货照片（URL 列表）。photoUrls 已在方法入口的张数校验处解析，此处直接复用
        for (String url : photoUrls) {
            if (url == null || url.isEmpty()) continue;
            String photoId = TmsUtil.uuid("SP");
            jdbcTemplate.update("""
                    INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, 'GOODS', ?, ?)
                    """, photoId, signId, url, "return-sign/" + signId + "/" + photoId);
        }

        // 3. 更新调度明细行状态
        if (!dispatchId.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE tms_dispatch_detail SET status='DELIVERED', sign_time=?, sign_user=?
                    WHERE dispatch_id=? AND bill_type='RETURN' AND source_bill_no=?
                    """, Timestamp.valueOf(TmsUtil.now()), TmsUtil.currentUser(), dispatchId, applyNo);
        }

        TmsUtil.log(jdbcTemplate, "tms.app.return", "SIGN", applyNo,
                "司机退货签收：实收 " + totalSigned + " 件，退货金额 " + returnAmount
                        + "，物流状态 已调度 → 司机已回收"
                        + (inboundNo.isEmpty() ? "，未生成入库单" : "，入库单 " + inboundNo)
                        + (autoAudited ? "，已按司机回收口径自动审核" : ""));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applyNo", applyNo);
        result.put("logisticsStatus", "司机已回收");
        result.put("signedQty", totalSigned);
        // returnQty 这里是「应退数量」（签收前的单据数量），APP 端用它算差异，语义与 /return/detail 保持一致；
        // 退货单表里的 return_qty 签收后已被改写为实收数量，两者不是同一口径，勿混用
        result.put("returnQty", h.get("qty"));
        result.put("returnAmount", returnAmount);
        result.put("diff", TmsUtil.toBd(h.get("qty")).subtract(totalSigned));
        result.put("inboundNo", inboundNo);
        result.put("autoAudited", autoAudited);
        return ApiResponse.ok(result);
    }

    /**
     * 历史配送任务（APP 端）。
     *
     * 与 ERP 端 /tms/trip/page 的区别：此处强制注入 driver_id = 当前登录司机，
     * 司机无法通过构造入参查看他人任务；且不复用 PageResult.of，
     * 因为它会把 filters 里的日期值当作全文关键字二次过滤，导致结果为空。
     *
     * 只返回终态（COMPLETED/CANCELLED）：
     *   历史的语义是「已经办完的事」。未完成行程若同时出现在历史页，
     *   会让司机误以为可以在此继续作业，但历史页只有展示没有作业入口，
     *   实际形成死角。未完成任务统一归「当前任务」页（/today-tasks）。
     *   因此即使调用方显式传入 PLANNED 等进行中状态，也会被下方白名单拦掉。
     *
     * 入参（均可选）：status(ALL/COMPLETED/CANCELLED)、dateFrom、dateTo、days(默认 30)、pageNo、pageSize
     * 出参：{records, pageNo, pageSize, total, summary:{tripCount, storeSum, qtySum, amountSum}}
     */
    @PostMapping("/trip/history")
    public ApiResponse<Map<String, Object>> tripHistory(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> params = body == null ? Map.of() : body;
        String driverId = TmsUtil.currentDriverId();

        StringBuilder sql = new StringBuilder("""
                SELECT t.trip_id, t.trip_no, t.dispatch_id, t.vehicle_plate, t.route_line, t.trip_date, t.status,
                       t.total_store, t.delivered_store, t.total_qty, t.delivered_qty, t.collected_amount,
                       t.loading_time, t.depart_time, t.complete_time,
                       d.dispatch_no, d.territory
                FROM tms_delivery_trip t
                LEFT JOIN tms_dispatch d ON d.dispatch_id = t.dispatch_id
                WHERE t.driver_id = ? AND t.status IN ('COMPLETED','CANCELLED')
                """);
        List<Object> args = new ArrayList<>();
        args.add(driverId);

        // 状态过滤只在终态集合内生效：传 ALL 或进行中状态都视为「不额外过滤」，
        // 避免拼出 status='DELIVERING' 这类与上方白名单互斥、恒为空的条件
        String status = TmsUtil.str(params.get("status"));
        if (("COMPLETED".equals(status) || "CANCELLED".equals(status))) {
            sql.append(" AND t.status = ?");
            args.add(status);
        }
        // 日期区间：显式区间优先，否则回退 days 天（默认 30 天），避免全表扫描
        String dateFrom = TmsUtil.str(params.get("dateFrom"));
        String dateTo = TmsUtil.str(params.get("dateTo"));
        if (!dateFrom.isEmpty() || !dateTo.isEmpty()) {
            if (!dateFrom.isEmpty()) {
                sql.append(" AND t.trip_date >= ?");
                args.add(dateFrom);
            }
            if (!dateTo.isEmpty()) {
                sql.append(" AND t.trip_date <= ?");
                args.add(dateTo);
            }
        } else {
            int days = params.get("days") == null ? 30 : TmsUtil.toInt(params.get("days"));
            if (days <= 0) days = 30;
            // 由 Java 计算起始日期，避免 H2 与 MySQL 的日期减法语法差异
            sql.append(" AND t.trip_date >= ?");
            args.add(LocalDate.now().minusDays(days).toString());
        }
        sql.append(" ORDER BY t.trip_date DESC, t.trip_no DESC");

        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());

        int storeSum = 0;
        BigDecimal qtySum = BigDecimal.ZERO;
        BigDecimal amountSum = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            r.put("statusText", resolveTripStatus(TmsUtil.str(r.get("status"))));
            int total = TmsUtil.toInt(r.get("totalStore"));
            int delivered = TmsUtil.toInt(r.get("deliveredStore"));
            r.put("progress", total > 0 ? Math.round(delivered * 100.0 / total) : 0);
            storeSum += delivered;
            qtySum = qtySum.add(TmsUtil.toBd(r.get("deliveredQty")));
            amountSum = amountSum.add(TmsUtil.toBd(r.get("collectedAmount")));
        }

        int pageNo = params.get("pageNo") == null ? 1 : Math.max(1, TmsUtil.toInt(params.get("pageNo")));
        int pageSize = params.get("pageSize") == null ? 20 : TmsUtil.toInt(params.get("pageSize"));
        if (pageSize < 1 || pageSize > 200) pageSize = 20;
        int from = Math.min((pageNo - 1) * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", new ArrayList<>(rows.subList(from, to)));
        result.put("pageNo", pageNo);
        result.put("pageSize", pageSize);
        result.put("total", rows.size());
        // 汇总取全量（非当前页），供 APP 顶部统计条展示
        result.put("summary", Map.of(
                "tripCount", rows.size(),
                "storeSum", storeSum,
                "qtySum", qtySum,
                "amountSum", amountSum
        ));
        return ApiResponse.ok(result);
    }

    /** 行程状态中文映射（与 ERP 端 TmsDeliveryController 保持一致）。 */
    private String resolveTripStatus(String s) {
        return switch (s) {
            case "PLANNED" -> "待装车";
            case "LOADED" -> "已装车";
            case "DEPARTED" -> "已发车";
            case "DELIVERING" -> "配送中";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            default -> s;
        };
    }

    /**
     * 收款记录（APP「我的 → 收款记录」）。
     *
     * 数据源是门店级签收流水 tms_sign_record，粒度 = 一次上门一条，
     * 这是司机跟调度对账时唯一能逐笔核对的凭据。为什么不用 tms_delivery_trip.collected_amount：
     * 那是车次级汇总，对不上的时候司机没法定位是哪家门店的钱有问题。
     *
     * 归属判定刻意用 sign_user = driverId（写入时取 TmsUtil.currentUser()，APP 端 subject 就是 driverId），
     * 而不是 JOIN tms_dispatch.driver_id：司机回收型退货支持「直派」路径（V63 放开 dispatch_id 可空），
     * 走 JOIN 会把这批 null 行整体漏掉，司机看到的收款流水就会少账。
     *
     * 只返回真实发生收款的行（collect_amount > 0）：赊账/预付门店签收也会落签收记录，
     * 但金额为 0，混进「收款记录」只会让司机对账时逐条排除噪音。
     *
     * 入参：days（默认 30）/ dateFrom / dateTo / payMethod / pageNo / pageSize
     */
    @PostMapping("/collect/records")
    public ApiResponse<Map<String, Object>> collectRecords(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> params = body == null ? Map.of() : body;
        String driverId = TmsUtil.currentDriverId();

        StringBuilder sql = new StringBuilder("""
                SELECT * FROM (
                    SELECT s.sign_id, s.dispatch_id, s.detail_id, s.source_bill_no, s.customer_code, s.customer_name,
                           s.bill_type, s.sign_type, s.signed_qty, s.reject_qty, s.collect_amount, s.pay_method,
                           CASE WHEN s.pay_method = '现金' THEN 'Y' ELSE 'N' END AS cash_flag,
                           s.sign_time, s.customer_signer, s.remark, s.verified, s.verified_at,
                           d.dispatch_no
                    FROM tms_sign_record s
                    LEFT JOIN tms_dispatch d ON d.dispatch_id = s.dispatch_id
                    WHERE s.sign_user = ? AND s.collect_amount > 0
                    UNION ALL
                    SELECT sa.id AS sign_id, ss.dispatch_id, '' AS detail_id, ss.settle_no AS source_bill_no,
                           ss.customer_code, ss.customer_name,
                           'RECEIPT' AS bill_type, 'SETTLE' AS sign_type, 0 AS signed_qty, 0 AS reject_qty,
                           sa.amount AS collect_amount, sa.fund_account_name AS pay_method,
                           CASE WHEN f.parent_code = '01' OR sa.fund_account_id = 'FA_SYS_01'
                                THEN 'Y' ELSE 'N' END AS cash_flag,
                           ss.settle_time AS sign_time, ss.signer AS customer_signer, ss.remark,
                           ss.fin_status AS verified, CAST(NULL AS TIMESTAMP) AS verified_at,
                           d2.dispatch_no
                    FROM tms_store_settlement_account sa
                    JOIN tms_store_settlement ss ON ss.settle_id = sa.settle_id
                    LEFT JOIN base_fund_account f ON f.fund_account_id = sa.fund_account_id
                    LEFT JOIN tms_dispatch d2 ON d2.dispatch_id = ss.dispatch_id
                    WHERE ss.driver_id = ? AND ss.settle_status = 'SETTLED' AND sa.amount > 0
                ) t WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        args.add(driverId);
        args.add(driverId);

        String payMethod = TmsUtil.str(params.get("payMethod"));
        if (!payMethod.isEmpty() && !"ALL".equals(payMethod)) {
            // 现金按 cash_flag 过滤而不是按 pay_method 字面匹配：
            // 门店结算行的 pay_method 存的是资金账户名（如「门店备用金」），
            // 按字面比「现金」会把这些现金收款整体漏掉。
            if ("现金".equals(payMethod)) {
                sql.append(" AND t.cash_flag = 'Y'");
            } else {
                sql.append(" AND t.pay_method = ?");
                args.add(payMethod);
            }
        }
        // 日期区间：显式区间优先，否则回退 days 天（默认 30 天），避免全表扫描。
        // sign_time 是 TIMESTAMP，dateTo 需按「当天 23:59:59」比较，
        // 直接 <= '2026-08-17' 会把当天全部记录漏掉（等价于 <= 当天 00:00:00）。
        String dateFrom = TmsUtil.str(params.get("dateFrom"));
        String dateTo = TmsUtil.str(params.get("dateTo"));
        if (!dateFrom.isEmpty() || !dateTo.isEmpty()) {
            if (!dateFrom.isEmpty()) {
                sql.append(" AND t.sign_time >= ?");
                args.add(dateFrom + " 00:00:00");
            }
            if (!dateTo.isEmpty()) {
                sql.append(" AND t.sign_time <= ?");
                args.add(dateTo + " 23:59:59");
            }
        } else {
            int days = params.get("days") == null ? 30 : TmsUtil.toInt(params.get("days"));
            if (days <= 0) days = 30;
            sql.append(" AND t.sign_time >= ?");
            args.add(LocalDate.now().minusDays(days) + " 00:00:00");
        }
        sql.append(" ORDER BY t.sign_time DESC");

        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());

        // 汇总按收款方式拆分：司机交账时现金要点钞、电子收款只需核对流水，两者必须分开算。
        // 现金判定统一走 SQL 算出的 cash_flag（资金账户树里 parent_code='01' 即现金类），
        // 而不是在这里比 payMethod 字面值：门店结算行的 payMethod 是账户名，
        // 字面比对会让这笔钱既不进现金也不该进电子，出现「合计 ≠ 现金 + 电子」的对账悖论。
        BigDecimal amountSum = BigDecimal.ZERO;
        BigDecimal cashSum = BigDecimal.ZERO;
        BigDecimal onlineSum = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal amt = TmsUtil.toBd(r.get("collectAmount"));
            amountSum = amountSum.add(amt);
            if ("Y".equals(TmsUtil.str(r.get("cashFlag")))) cashSum = cashSum.add(amt);
            else onlineSum = onlineSum.add(amt);
            // 历史门店结算行可能没落账户名（早期版本信前端入参），
            // 兜个默认值免得界面上出现空白收款方式。
            if (TmsUtil.str(r.get("payMethod")).isEmpty()) {
                r.put("payMethod", "Y".equals(TmsUtil.str(r.get("cashFlag"))) ? "现金" : "其他");
            }
            r.put("billTypeText", "RETURN".equals(TmsUtil.str(r.get("billType"))) ? "取退" : "发货");
            r.put("signTypeText", resolveSignType(TmsUtil.str(r.get("signType"))));
            r.put("verifiedText", resolveVerified(TmsUtil.str(r.get("verified"))));
        }

        int pageNo = params.get("pageNo") == null ? 1 : Math.max(1, TmsUtil.toInt(params.get("pageNo")));
        int pageSize = params.get("pageSize") == null ? 20 : TmsUtil.toInt(params.get("pageSize"));
        if (pageSize < 1 || pageSize > 200) pageSize = 20;
        int from = Math.min((pageNo - 1) * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", new ArrayList<>(rows.subList(from, to)));
        result.put("pageNo", pageNo);
        result.put("pageSize", pageSize);
        result.put("total", rows.size());
        // 汇总取全量（非当前页），供 APP 顶部统计条展示
        result.put("summary", Map.of(
                "recordCount", rows.size(),
                "amountSum", amountSum,
                "cashSum", cashSum,
                "onlineSum", onlineSum
        ));
        return ApiResponse.ok(result);
    }

    /** 签收类型中文映射。 */
    private String resolveSignType(String s) {
        return switch (s) {
            case "NORMAL" -> "正常签收";
            case "PARTIAL" -> "部分签收";
            case "REJECT" -> "拒收";
            case "RETURN" -> "退货回收";
            default -> s;
        };
    }

    /**
     * 核销状态中文映射（V57 的 verified 字段）。
     * PENDING 对司机的含义是「调度还没跟你对过这笔账」，所以显示成「待核销」而不是留空，
     * 让司机知道这笔钱还没交割完。
     */
    private String resolveVerified(String s) {
        return switch (s) {
            case "APPROVED" -> "已核销";
            case "REJECTED" -> "核销驳回";
            case "PENDING", "" -> "待核销";
            default -> s;
        };
    }
}
