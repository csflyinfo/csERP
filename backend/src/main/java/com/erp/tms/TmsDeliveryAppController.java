package com.erp.tms;

import com.erp.common.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

/**
 * TMS 司机 APP 配送核心接口（P2）。
 *
 * 状态机：
 *   调度单 ASSIGNED ─[接单]──> ACCEPTED ─[装车]──> LOADED ─[发车]──> DEPARTED ─[首店签收]──> DELIVERING ─[全部签收]──> COMPLETED
 *   行程   PLANNED  ─────────────────────[装车]──> LOADED ─[发车]──> DEPARTED ─[首店签收]──> DELIVERING ─[全部签收]──> COMPLETED
 *   发货单 dispatch_status：UNDISPATCHED → DISPATCHED → LOADED → DEPARTED → DELIVERING → COMPLETED
 *
 * ACCEPTED 只落在调度单上，行程与发货单不设对应状态：
 *   接单是司机单方表态，货还在库里、单还没动，行程和发货单的处境与派单时完全相同。
 *   若为了「状态对齐」在三张表都加一档，ERP 侧所有按 dispatch_status 取数的地方
 *   都得跟着改，收益为零而波及面很大。
 *
 * 接口：
 *   POST /tms/app/accept              司机确认接单（ASSIGNED → ACCEPTED）
 *   POST /tms/app/loading/stores       调度任务配送点清单（按门店合并，供查看清单/调序）
 *   POST /tms/app/loading/sort         调整配送点顺序（仅未发车，已完成门店锁定）
 *   POST /tms/app/loading/items        装车 SKU 明细（按发货单分组，含已装数量）
 *   POST /tms/app/loading/start        开始装车（ASSIGNED / ACCEPTED → LOADED）
 *   POST /tms/app/loading/scan         装车扫码核对（写 tms_loading_check）
 *   POST /tms/app/loading/confirm      确认装车完成
 *   POST /tms/app/depart               确认发车（LOADED → DEPARTED）
 *   POST /tms/app/arrive               到达门店打卡（GPS 围栏校验）
 *   POST /tms/app/arrive/config        到达打卡参数配置（围栏阈值 / 是否强制 / 是否必拍照）
 *   POST /tms/app/sign/items           签收 SKU 明细（按调度明细）
 *   POST /tms/app/sign                 门店签收（发货单：全部/部分/拒收）
 *   POST /tms/app/sign/upload-photo    上传签收照片（base64，MinIO 接入后改 URL）
 *   POST /tms/app/location/report      GPS 单点上报
 *   POST /tms/app/location/batch-report GPS 批量上报（离线补传）
 */
@RestController
@RequestMapping("/tms/app")
public class TmsDeliveryAppController {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 明细「已处理」与调度单「未发车」两套口径，直接复用 TmsAppController 的定义。
     *
     * 为什么不在本类各自再写一份：这两组状态值原先散落在 7 处硬编码，
     * 一度出现「首页认 4 个终态、配送中只认 3 个」的两向不一致（已签收的门店
     * 在一个页面消失、在另一个页面还挂着）。同包共用一处常量，改口径时不可能漏改。
     */
    static final Set<String> DETAIL_DONE = TmsAppController.DETAIL_DONE;
    static final Set<String> BEFORE_DEPART = TmsAppController.DISPATCH_BEFORE_DEPART;

    public TmsDeliveryAppController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 接单 ====================

    /**
     * 司机确认接单：ASSIGNED → ACCEPTED。
     *
     * 为什么需要这一档：原先调度侧一指派就直接进入可装车状态，
     * 司机没有表态环节，「这单司机到底看见没、认没认」在系统里查不出来。
     * 接单后写 accept_time / accept_user 留痕，调度侧才能识别出
     * 「派出去很久却没人接」的单子并及时干预。
     *
     * 幂等：已是 ACCEPTED 时直接返回成功而不报错——司机在弱网下重复点
     * 「确认接单」是常态，报错只会让人以为接单失败而反复重试。
     * 已进入 LOADED 及之后的状态则拒绝，那是回退而非重复。
     */
    @PostMapping("/accept")
    @Transactional
    public ApiResponse<Map<String, Object>> accept(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        String driverId = TmsUtil.currentDriverId();

        Map<String, Object> dispatch = loadDispatch(dispatchId, driverId);
        String status = TmsUtil.str(dispatch.get("status"));
        if ("ACCEPTED".equals(status)) {
            return ApiResponse.ok(Map.of(
                    "dispatchId", dispatchId,
                    "status", "ACCEPTED",
                    "acceptTime", TmsUtil.str(dispatch.get("acceptTime")),
                    "repeated", true
            ));
        }
        if (!"ASSIGNED".equals(status)) {
            return ApiResponse.fail("400", "当前调度单状态为「" + status + "」，仅「ASSIGNED」可确认接单");
        }

        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        // accept_user 存司机姓名而非 driverId：这个字段的唯一用途是给人看，
        // 调度员查「谁接的」时不该再去 base_employee 反查一次。
        String driverName = TmsUtil.str(dispatch.get("driverName"));
        if (driverName.isEmpty()) driverName = driverId;
        jdbcTemplate.update("UPDATE tms_dispatch SET status='ACCEPTED', accept_time=?, accept_user=? WHERE dispatch_id=?",
                now, driverName, dispatchId);

        TmsUtil.log(jdbcTemplate, "tms.app.delivery", "ACCEPT", dispatchId, "司机确认接单：" + driverName);
        return ApiResponse.ok(Map.of(
                "dispatchId", dispatchId,
                "status", "ACCEPTED",
                "acceptTime", now.toString(),
                "repeated", false
        ));
    }

    // ==================== 装车 ====================

    /**
     * 调度任务配送点清单：首页卡片「查看清单」与装车前调序共用一份数据。
     *
     * 为什么不复用 /loading/items：
     *   items 是「按发货单 + SKU」铺开的装车核对视图，且只取 bill_type='RECEIPT'。
     *   司机在接单后要看的是「这趟车跑哪几个点、每个点几张单」，退货单也必须出现，
     *   两者的分组维度（单据 vs 门店）和取数范围都不同，硬塞进一个接口会让
     *   装车页与清单页互相牵制。
     *
     * 为什么按 customer_code 合并：
     *   同一门店可能同时有多张发货单和一张退货单，司机上门只跑一趟。
     *   与 /delivering/stores 的合并口径保持一致，避免同一个点在不同页面
     *   数量对不上。
     *
     * 排序键取门店内最小 seq_no：
     *   seq_no 落在明细行上，同店多单的 seq_no 不一定连续。取最小值作为
     *   门店序号，才能保证「门店顺序」与「明细顺序」不会互相打脸。
     */
    @PostMapping("/loading/stores")
    public ApiResponse<Map<String, Object>> loadingStores(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        Map<String, Object> dispatch = loadDispatch(dispatchId, TmsUtil.currentDriverId());

        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dd.detail_id, dd.bill_type, dd.source_bill_no, dd.customer_code, dd.customer_name,
                       COALESCE(NULLIF(dd.customer_address, ''), c.shipping_address) AS customer_address,
                       dd.qty, dd.sku_count, dd.seq_no, dd.status,
                       c.contact_name, c.mobile AS contact_mobile, c.settlement_type,
                       c.longitude, c.latitude,
                       r.deliver_amount
                FROM tms_dispatch_detail dd
                LEFT JOIN base_customer c ON c.customer_code = dd.customer_code
                LEFT JOIN sales_receipt r ON r.receipt_no = dd.source_bill_no AND dd.bill_type = 'RECEIPT'
                WHERE dd.dispatch_id = ?
                ORDER BY dd.seq_no, dd.detail_id
                """, dispatchId);

        Map<String, Map<String, Object>> storeMap = new LinkedHashMap<>();
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCollect = BigDecimal.ZERO;
        for (Map<String, Object> r : details) {
            boolean isReturn = "RETURN".equals(TmsUtil.str(r.get("billType")));
            boolean done = DETAIL_DONE.contains(TmsUtil.str(r.get("status")));
            BigDecimal qty = TmsUtil.toBd(r.get("qty"));
            // 代收货款只算发货单且结算方式需要收款的：退货单没有应收概念，
            // 月结门店的钱走账期，司机手上不该出现这笔数。
            boolean needCollect = !isReturn && TmsUtil.needCollect(r.get("settlementType"));
            BigDecimal receivable = needCollect ? TmsUtil.toBd(r.get("deliverAmount")) : BigDecimal.ZERO;
            totalQty = totalQty.add(qty);
            totalCollect = totalCollect.add(receivable);

            String key = TmsUtil.str(r.get("customerCode"));
            Map<String, Object> store = storeMap.get(key);
            if (store == null) {
                store = new LinkedHashMap<>();
                store.put("customerCode", key);
                store.put("customerName", r.get("customerName"));
                store.put("customerAddress", r.get("customerAddress"));
                store.put("contactName", r.get("contactName"));
                store.put("contactMobile", r.get("contactMobile"));
                store.put("settlementType", r.get("settlementType"));
                store.put("settlementText", TmsUtil.settlementText(r.get("settlementType")));
                store.put("longitude", r.get("longitude"));
                store.put("latitude", r.get("latitude"));
                store.put("seqNo", TmsUtil.toInt(r.get("seqNo")));
                store.put("receiptCount", 0);
                store.put("returnCount", 0);
                store.put("pendingCount", 0);
                store.put("totalQty", BigDecimal.ZERO);
                store.put("collectAmount", BigDecimal.ZERO);
                store.put("needCollect", false);
                store.put("bills", new ArrayList<Map<String, Object>>());
                storeMap.put(key, store);
            } else {
                store.put("seqNo", Math.min(TmsUtil.toInt(store.get("seqNo")), TmsUtil.toInt(r.get("seqNo"))));
            }
            store.put(isReturn ? "returnCount" : "receiptCount",
                    TmsUtil.toInt(store.get(isReturn ? "returnCount" : "receiptCount")) + 1);
            if (!done) store.put("pendingCount", TmsUtil.toInt(store.get("pendingCount")) + 1);
            store.put("totalQty", TmsUtil.toBd(store.get("totalQty")).add(qty));
            store.put("collectAmount", TmsUtil.toBd(store.get("collectAmount")).add(receivable));
            if (needCollect) store.put("needCollect", true);

            Map<String, Object> bill = new LinkedHashMap<>();
            bill.put("detailId", r.get("detailId"));
            bill.put("billType", r.get("billType"));
            bill.put("billTypeText", isReturn ? "取退" : "发货");
            bill.put("sourceBillNo", r.get("sourceBillNo"));
            bill.put("qty", qty);
            bill.put("skuCount", r.get("skuCount"));
            bill.put("seqNo", r.get("seqNo"));
            bill.put("status", r.get("status"));
            bill.put("done", done);
            bill.put("receivableAmount", receivable);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> bills = (List<Map<String, Object>>) store.get("bills");
            bills.add(bill);
        }

        // 已完成的门店不参与调序：货已经卸下去了，把它挪到后面毫无意义，
        // 前端据此把这些行渲染成不可拖拽。
        List<Map<String, Object>> stores = new ArrayList<>(storeMap.values());
        stores.sort(Comparator.comparingInt(s -> TmsUtil.toInt(s.get("seqNo"))));
        int index = 0;
        int pendingStore = 0;
        for (Map<String, Object> s : stores) {
            boolean storeDone = TmsUtil.toInt(s.get("pendingCount")) == 0;
            s.put("done", storeDone);
            s.put("sortable", !storeDone);
            s.put("orderNo", ++index);
            if (!storeDone) pendingStore++;
        }

        String status = TmsUtil.str(dispatch.get("status"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchId", dispatchId);
        result.put("dispatchNo", dispatch.get("dispatchNo"));
        result.put("status", status);
        result.put("vehiclePlate", dispatch.get("vehiclePlate"));
        result.put("routeLine", dispatch.get("routeLine"));
        result.put("dispatchDate", dispatch.get("dispatchDate"));
        result.put("parentDispatchId", dispatch.get("parentDispatchId"));
        result.put("appended", !TmsUtil.str(dispatch.get("parentDispatchId")).isEmpty());
        // 只有未发车才允许调序：发车后司机已按既定顺序压车装货，改序会与车厢实际堆放冲突
        result.put("canSort", BEFORE_DEPART.contains(status) && pendingStore > 1);
        result.put("stores", stores);
        result.put("summary", Map.of(
                "storeCount", stores.size(),
                "pendingStore", pendingStore,
                "billCount", details.size(),
                "totalQty", totalQty,
                "collectAmount", totalCollect
        ));
        return ApiResponse.ok(result);
    }

    /**
     * 调整配送点顺序：入参 dispatchId, customerCodes:[按新顺序排列的门店编码]。
     *
     * 为什么按门店传而不是按 detailId 传：
     *   司机在清单页拖的是「配送点」，一个点后面挂着多张单。若让前端逐单算 seqNo，
     *   同店多单的相对次序就要前端自己维护，一旦算错会出现「同一个店被拆成两段跑」。
     *   这里由后端把门店序号展开到明细：门店按新顺序 × 10 递增，同店内部保持原相对次序。
     *
     * 为什么不复用 /tms/dispatch/sort：
     *   那是 ERP 调度侧接口，不校验司机归属、不校验状态、不保护已完成门店，
     *   直接开放给 APP 等于让司机能改别人的单。
     *
     * 已完成的门店锁死在最前面：货已卸，序号必须小于所有未完成点，
     * 否则「已完成 3 个点」在进度上会跳来跳去。
     */
    @PostMapping("/loading/sort")
    @Transactional
    public ApiResponse<Map<String, Object>> loadingSort(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        Object raw = body.get("customerCodes");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return ApiResponse.fail("400", "customerCodes 不能为空");
        }
        Map<String, Object> dispatch = loadDispatch(dispatchId, TmsUtil.currentDriverId());
        String status = TmsUtil.str(dispatch.get("status"));
        if (!BEFORE_DEPART.contains(status)) {
            return ApiResponse.fail("400", "调度单已发车，不能再调整配送顺序");
        }

        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT detail_id, customer_code, status, seq_no
                FROM tms_dispatch_detail
                WHERE dispatch_id = ?
                ORDER BY seq_no, detail_id
                """, dispatchId);
        if (details.isEmpty()) return ApiResponse.fail("404", "调度单没有配送明细");

        // 按门店归集明细，并区分「已完成」与「待配送」
        Map<String, List<String>> storeDetails = new LinkedHashMap<>();
        Set<String> doneStores = new LinkedHashSet<>();
        Set<String> pendingStores = new LinkedHashSet<>();
        for (Map<String, Object> r : details) {
            String code = TmsUtil.str(r.get("customerCode"));
            storeDetails.computeIfAbsent(code, k -> new ArrayList<>()).add(TmsUtil.str(r.get("detailId")));
            if (DETAIL_DONE.contains(TmsUtil.str(r.get("status")))) doneStores.add(code);
            else pendingStores.add(code);
        }
        doneStores.removeAll(pendingStores);

        // 入参校验：只接受「待配送门店的一个全排列」。少传会让漏掉的点丢失序号，
        // 多传或传了已完成门店说明前端拿的是过期数据，直接拒绝比默默纠正安全。
        List<String> wanted = new ArrayList<>();
        for (Object o : list) {
            String code = TmsUtil.str(o);
            if (code.isEmpty()) continue;
            if (!pendingStores.contains(code)) {
                return ApiResponse.fail("400", "配送点 " + code + " 不在待配送范围内，请刷新后重试");
            }
            if (wanted.contains(code)) return ApiResponse.fail("400", "配送点 " + code + " 重复提交");
            wanted.add(code);
        }
        if (wanted.size() != pendingStores.size()) {
            return ApiResponse.fail("400", "提交的配送点数量与待配送门店不一致，请刷新后重试");
        }

        // 序号统一重排：已完成门店在前（保持原相对次序），其后是新顺序的待配送门店。
        // 步长 10 留出手工插单的余量，与排线侧的习惯一致。
        List<String> ordered = new ArrayList<>(doneStores);
        ordered.addAll(wanted);
        int seq = 0;
        int updated = 0;
        for (String code : ordered) {
            for (String detailId : storeDetails.getOrDefault(code, List.of())) {
                seq += 10;
                updated += jdbcTemplate.update(
                        "UPDATE tms_dispatch_detail SET seq_no=? WHERE detail_id=? AND dispatch_id=?",
                        seq, detailId, dispatchId);
            }
        }
        TmsUtil.log(jdbcTemplate, "tms.app.delivery", "SORT", dispatchId,
                "司机调整配送顺序：" + String.join(" → ", wanted));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchId", dispatchId);
        result.put("storeCount", ordered.size());
        result.put("pendingStore", wanted.size());
        // updated 返回真实影响行数，而不是入参条数：前端据此能发现「明细已被调度侧删改」
        result.put("updated", updated);
        return ApiResponse.ok(result);
    }

    /**
     * 装车 SKU 明细：按调度单拉取所有发货单的逐商品明细（含已装车数量回写）。
     * 返回结构：
     *   dispatchId, dispatchNo, status, vehiclePlate, routeLine, loadedQty, returnQty, storeCount,
     *   receipts:[{ detailId, sourceBillNo, customerCode, customerName, customerAddress, seqNo,
     *               loadStatus, requiredQty, loadedQty,
     *               items:[{ goodsCode, goodsName, unitName, requiredQty, loadedQty }] }]
     *
     * 已发车的配送点（depart_time IS NOT NULL）**不再返回**：
     *   支持部分发车后，同一张调度单会同时存在「已在车上的点」和「还没装的点」，
     *   已发车的点留在装车清单里只会让司机反复确认已经送出去的货，
     *   而剩余待装的点被挤到列表底部。配送中列表会接管这些点。
     */
    @PostMapping("/loading/items")
    public ApiResponse<Map<String, Object>> loadingItems(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        Map<String, Object> dispatch = loadDispatch(dispatchId, TmsUtil.currentDriverId());

        // 拉取发货单调度明细
        // 地址用 JOIN 回落到客户档案：dd.customer_address 是下单快照，历史数据大量为空。
        // 逐行补查会产生 N+1，这里随主查询一次取回，与配送点列表/详情/签收页口径一致。
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dd.detail_id, dd.source_bill_no, dd.customer_code, dd.customer_name,
                       COALESCE(NULLIF(dd.customer_address, ''), c.shipping_address) AS customer_address,
                       dd.qty, dd.seq_no, dd.status,
                       COALESCE(dd.load_status, 'PENDING') AS load_status, dd.load_time
                FROM tms_dispatch_detail dd
                LEFT JOIN base_customer c ON c.customer_code = dd.customer_code
                WHERE dd.dispatch_id=? AND dd.bill_type='RECEIPT' AND dd.depart_time IS NULL
                ORDER BY dd.seq_no
                """, dispatchId);

        // 已装车数量按 (detail_id, goods_code) 聚合
        Map<String, BigDecimal> loadedMap = new HashMap<>();
        List<Map<String, Object>> checks = jdbcTemplate.queryForList(
                "SELECT detail_id, goods_code, SUM(loaded_qty) AS loaded_qty FROM tms_loading_check WHERE dispatch_id=? GROUP BY detail_id, goods_code",
                dispatchId);
        for (Map<String, Object> c : checks) {
            String key = TmsUtil.str(c.get("detail_id")) + "|" + TmsUtil.str(c.get("goods_code"));
            loadedMap.put(key, TmsUtil.toBd(c.get("loaded_qty")));
        }

        List<Map<String, Object>> receipts = new ArrayList<>();
        BigDecimal totalRequired = BigDecimal.ZERO;
        BigDecimal totalLoaded = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            String detailId = TmsUtil.str(d.get("detailId"));
            String billNo = TmsUtil.str(d.get("sourceBillNo"));
            // 查发货单 SKU 明细
            List<Map<String, Object>> items = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT d.goods_code, d.goods_name, d.unit_name, d.qty
                    FROM sales_receipt_detail d
                    JOIN sales_receipt r ON r.receipt_id = d.receipt_id
                    WHERE r.receipt_no = ?
                    ORDER BY d.detail_id
                    """, billNo);
            BigDecimal dRequired = BigDecimal.ZERO;
            BigDecimal dLoaded = BigDecimal.ZERO;
            for (Map<String, Object> it : items) {
                String gCode = TmsUtil.str(it.get("goodsCode"));
                BigDecimal req = TmsUtil.toBd(it.get("qty"));
                BigDecimal loaded = loadedMap.getOrDefault(detailId + "|" + gCode, BigDecimal.ZERO);
                it.put("requiredQty", req);
                it.put("loadedQty", loaded);
                it.put("diffQty", loaded.subtract(req));
                it.put("checked", loaded.compareTo(BigDecimal.ZERO) > 0);
                dRequired = dRequired.add(req);
                dLoaded = dLoaded.add(loaded);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("detailId", detailId);
            r.put("sourceBillNo", billNo);
            r.put("customerCode", d.get("customerCode"));
            r.put("customerName", d.get("customerName"));
            r.put("customerAddress", d.get("customerAddress"));
            r.put("seqNo", d.get("seqNo"));
            r.put("status", d.get("status"));
            // 配送点级装车状态：司机点【装车】后置 LOADED，是「能否发车」的唯一判据。
            // 不用「实装数量 >= 应装数量」推断：缺货只装了一部分也是确认装车完成，
            // 数量差异由差异记录承载，不能因此拦住发车。
            r.put("loadStatus", d.get("loadStatus"));
            r.put("loadTime", d.get("loadTime"));
            r.put("requiredQty", dRequired);
            r.put("loadedQty", dLoaded);
            r.put("items", items);
            receipts.add(r);
            totalRequired = totalRequired.add(dRequired);
            totalLoaded = totalLoaded.add(dLoaded);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchId", dispatchId);
        result.put("dispatchNo", dispatch.get("dispatchNo"));
        result.put("status", dispatch.get("status"));
        result.put("vehiclePlate", dispatch.get("vehiclePlate"));
        result.put("routeLine", dispatch.get("routeLine"));
        result.put("storeCount", dispatch.get("storeCount"));
        result.put("totalRequired", totalRequired);
        result.put("totalLoaded", totalLoaded);
        // allChecked 改为「所有待发车配送点都已确认装车」，不再看数量是否装满：
        // 缺货、破损导致实装少于应装是常态，按数量判定会让司机永远点不动发车按钮。
        long loadedStore = receipts.stream()
                .filter(r -> "LOADED".equals(TmsUtil.str(r.get("loadStatus")))).count();
        result.put("loadedStoreCount", loadedStore);
        result.put("pendingStoreCount", receipts.size() - loadedStore);
        result.put("allChecked", !receipts.isEmpty() && loadedStore == receipts.size());
        // anyLoaded：只要有一个点装好就能发车（部分发车），底部按钮据此启用
        result.put("anyLoaded", loadedStore > 0);
        result.put("receipts", receipts);
        return ApiResponse.ok(result);
    }

    /**
     * 开始装车：调度单 ASSIGNED / ACCEPTED → LOADED，行程 PLANNED → LOADED。
     *
     * 为什么同时放通 ASSIGNED：ACCEPTED 是新增状态，V68 之前的存量单仍停在
     * ASSIGNED，只认 ACCEPTED 会把这些单永久卡死在装车前。新单走
     * 「接单→装车」，存量单可直接装车，两者都不阻断。
     */
    @PostMapping("/loading/start")
    @Transactional
    public ApiResponse<Map<String, Object>> loadingStart(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        String driverId = TmsUtil.currentDriverId();

        Map<String, Object> dispatch = loadDispatch(dispatchId, driverId);
        String status = TmsUtil.str(dispatch.get("status"));
        if (!"ASSIGNED".equals(status) && !"ACCEPTED".equals(status)) {
            return ApiResponse.fail("400", "当前调度单状态为「" + status + "」，仅「已分配 / 已接单」可开始装车");
        }

        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        jdbcTemplate.update("UPDATE tms_dispatch SET status='LOADED', arrange_time=? WHERE dispatch_id=?", now, dispatchId);
        jdbcTemplate.update("UPDATE tms_delivery_trip SET status='LOADED', loading_time=? WHERE dispatch_id=?", now, dispatchId);
        // 发货单 dispatch_status → LOADED
        jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='LOADED' WHERE dispatch_id=?", dispatchId);

        TmsUtil.log(jdbcTemplate, "tms.app.delivery", "LOADING_START", dispatchId, "开始装车");
        return ApiResponse.ok(Map.of("dispatchId", dispatchId, "status", "LOADED"));
    }

    /** 装车扫码核对：逐商品录入实装数量，写 tms_loading_check。 */
    @PostMapping("/loading/scan")
    @Transactional
    public ApiResponse<Map<String, Object>> loadingScan(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String sourceBillNo = TmsUtil.str(body.get("sourceBillNo"));
        String goodsCode = TmsUtil.str(body.get("goodsCode"));
        BigDecimal loadedQty = TmsUtil.toBd(body.get("loadedQty"));
        BigDecimal requiredQty = TmsUtil.toBd(body.get("requiredQty"));
        if (dispatchId.isEmpty() || sourceBillNo.isEmpty() || goodsCode.isEmpty()) {
            return ApiResponse.fail("400", "dispatchId、sourceBillNo、goodsCode 不能为空");
        }
        String driverId = TmsUtil.currentDriverId();

        String checkId = TmsUtil.uuid("LC");
        BigDecimal diff = loadedQty.subtract(requiredQty);
        jdbcTemplate.update("""
                INSERT INTO tms_loading_check(check_id, dispatch_id, detail_id, source_bill_no, goods_code, goods_name,
                    loaded_qty, required_qty, diff_qty, check_time, checker, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, checkId, dispatchId, TmsUtil.str(body.get("detailId")), sourceBillNo, goodsCode,
                TmsUtil.str(body.get("goodsName")), loadedQty, requiredQty, diff,
                Timestamp.valueOf(TmsUtil.now()), driverId, TmsUtil.str(body.get("remark")));

        String alert = diff.compareTo(BigDecimal.ZERO) != 0 ? "装车差异：" + diff + "（应装 " + requiredQty + "，实装 " + loadedQty + "）" : "核对一致";
        return ApiResponse.ok(Map.of("checkId", checkId, "diff", diff, "alert", alert));
    }

    /**
     * 确认装车：按配送点（调度明细）粒度把 load_status 置为 LOADED。
     *
     * 入参：dispatchId 必填；detailIds:[...] 可选。
     *   - 传 detailIds：只确认这些配送点（司机单点【装车】或勾选多点批量【装车】）。
     *   - 不传：确认该调度单下全部未发车配送点（全选装车 / 兼容老版本「确认装车完毕」）。
     *
     * 为什么装车状态落在明细而不是调度单：
     *   司机的真实作业是「装一家、确认一家」，且经常出现部分门店缺货只能先发已装的货。
     *   状态挂在调度单上就只能整单同时装完、整单同时发车，与作业节奏不符。
     *
     * 为什么不校验「SKU 是否已逐个核对」：
     *   缺货、破损导致实装少于应装是常态，硬性要求装满会让司机点不动按钮。
     *   数量差异由 tms_loading_check 的 diff_qty 留痕，不作为拦截条件。
     *   已发车的配送点（depart_time IS NOT NULL）不允许再改，防止重复发车。
     */
    @PostMapping("/loading/confirm")
    @Transactional
    public ApiResponse<Map<String, Object>> loadingConfirm(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        String driverId = TmsUtil.currentDriverId();
        Map<String, Object> dispatch = loadDispatch(dispatchId, driverId);
        String status = TmsUtil.str(dispatch.get("status"));
        // DEPARTED/DELIVERING 也放通：部分发车后调度单会停在 LOADED，
        // 但存量数据里整单已发车的单同样可能被追加新明细，不能因状态拦死
        if (!Set.of("LOADED", "DEPARTED", "DELIVERING").contains(status)) {
            return ApiResponse.fail("400", "当前调度单状态为「" + status + "」，需先开始装车");
        }

        List<String> detailIds = new ArrayList<>();
        Object raw = body.get("detailIds");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                String s = TmsUtil.str(o);
                if (!s.isEmpty()) detailIds.add(s);
            }
        }

        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        int updated;
        if (detailIds.isEmpty()) {
            // 全选装车：一次确认全部未发车配送点
            updated = jdbcTemplate.update("""
                    UPDATE tms_dispatch_detail SET load_status='LOADED', load_time=?
                    WHERE dispatch_id=? AND bill_type='RECEIPT' AND depart_time IS NULL
                    """, now, dispatchId);
        } else {
            String ph = String.join(",", java.util.Collections.nCopies(detailIds.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(now);
            args.add(dispatchId);
            args.addAll(detailIds);
            updated = jdbcTemplate.update("""
                    UPDATE tms_dispatch_detail SET load_status='LOADED', load_time=?
                    WHERE dispatch_id=? AND bill_type='RECEIPT' AND depart_time IS NULL
                      AND detail_id IN (""" + ph + ")", args.toArray());
        }

        // 剩余未装车（且未发车）的配送点数：APP 据此提示「还有 N 个点未装车」
        Integer pending = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tms_dispatch_detail
                WHERE dispatch_id=? AND bill_type='RECEIPT' AND depart_time IS NULL
                  AND COALESCE(load_status,'PENDING') <> 'LOADED'
                """, Integer.class, dispatchId);
        Integer loaded = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tms_dispatch_detail
                WHERE dispatch_id=? AND bill_type='RECEIPT' AND depart_time IS NULL
                  AND COALESCE(load_status,'PENDING') = 'LOADED'
                """, Integer.class, dispatchId);
        if (pending == null) pending = 0;
        if (loaded == null) loaded = 0;

        TmsUtil.log(jdbcTemplate, "tms.app.delivery", "LOADING_CONFIRM", dispatchId,
                "确认装车 " + updated + " 个配送点，待装 " + pending + " 个");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchId", dispatchId);
        result.put("updated", updated);
        result.put("loadedStoreCount", loaded);
        result.put("pendingStoreCount", pending);
        result.put("allChecked", pending == 0 && loaded > 0);
        return ApiResponse.ok(result);
    }

    // ==================== 发车 ====================

    /**
     * 确认发车：只把**已确认装车**的配送点置为发车状态（部分发车）。
     *
     * 为什么改成配送点粒度：
     *   原实现是整单 UPDATE tms_dispatch SET status='DEPARTED'，一旦发车，
     *   没装的货也被标成「在路上」，司机回库补装后无从再发一次。
     *   现在发车只作用于 load_status='LOADED' 的明细：
     *     - 写 depart_time，该点进入配送中列表、从装车清单消失；
     *     - 未装车的点原地保留，司机补装后可再次点【装车】→【发车】继续出车。
     *
     * 调度单主状态口径：
     *   全部明细都已发车 → DEPARTED（整趟车都在路上）；
     *   仍有未发车明细   → 停在 LOADED，保证装车入口不消失。
     *
     * 为什么用 needConfirm 而不是直接 fail：
     *   漏装未必是错误——临时缺货、客户改约都会造成合理的少装。首次调用返回
     *   未装清单让 APP 弹窗列出，司机确认后带 force=true 再调一次即可发车，
     *   同时把漏装单号写进日志留痕。校验一律在服务端做，防改包绕过。
     */
    @PostMapping("/depart")
    @Transactional
    public ApiResponse<Map<String, Object>> depart(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) return ApiResponse.fail("400", "dispatchId 不能为空");
        String driverId = TmsUtil.currentDriverId();
        Map<String, Object> dispatch = loadDispatch(dispatchId, driverId);
        String status = TmsUtil.str(dispatch.get("status"));
        // DEPARTED/DELIVERING 同样放通：部分发车后司机补装剩余点还要再发一次车，
        // 此时主状态可能已被前一次发车推进，只认 LOADED 会把补发路径堵死。
        if (!Set.of("LOADED", "DEPARTED", "DELIVERING").contains(status)) {
            return ApiResponse.fail("400", "当前调度单状态为「" + status + "」，需先开始装车");
        }

        // 待发车明细（未发车的），按装车状态分成两组
        List<Map<String, Object>> pendingDetails = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dd.detail_id, dd.source_bill_no, dd.customer_name, dd.qty,
                       COALESCE(dd.load_status,'PENDING') AS load_status
                FROM tms_dispatch_detail dd
                WHERE dd.dispatch_id = ? AND dd.bill_type = 'RECEIPT' AND dd.depart_time IS NULL
                ORDER BY dd.seq_no
                """, dispatchId);
        List<Map<String, Object>> loadedDetails = new ArrayList<>();
        List<Map<String, Object>> unloaded = new ArrayList<>();
        for (Map<String, Object> d : pendingDetails) {
            if ("LOADED".equals(TmsUtil.str(d.get("loadStatus")))) loadedDetails.add(d);
            else unloaded.add(d);
        }

        if (loadedDetails.isEmpty()) {
            return ApiResponse.fail("400", "尚无已确认装车的配送点，请先完成装车再发车");
        }

        boolean force = "true".equalsIgnoreCase(TmsUtil.str(body.get("force")));
        if (!unloaded.isEmpty() && !force) {
            Map<String, Object> confirm = new LinkedHashMap<>();
            confirm.put("dispatchId", dispatchId);
            confirm.put("needConfirm", true);
            confirm.put("unloadedCount", unloaded.size());
            confirm.put("unloaded", unloaded);
            confirm.put("departCount", loadedDetails.size());
            confirm.put("message", "还有 " + unloaded.size() + " 个配送点未确认装车，本次只发车已装的 "
                    + loadedDetails.size() + " 个配送点，未装的可稍后补装再发车。确认发车？");
            return ApiResponse.ok(confirm);
        }

        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        // 1) 只给已装车的明细打发车时间
        List<String> departIds = loadedDetails.stream().map(x -> TmsUtil.str(x.get("detailId"))).toList();
        String ph = String.join(",", java.util.Collections.nCopies(departIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(now);
        args.add(dispatchId);
        args.addAll(departIds);
        int departed = jdbcTemplate.update(
                "UPDATE tms_dispatch_detail SET depart_time=? WHERE dispatch_id=? AND detail_id IN (" + ph + ")",
                args.toArray());

        // 2) 发货单状态只更新本次发车的单，未装的仍留在 LOADED 等待补发
        List<Object> billArgs = new ArrayList<>();
        billArgs.add(dispatchId);
        List<String> billNos = loadedDetails.stream().map(x -> TmsUtil.str(x.get("sourceBillNo"))).toList();
        billArgs.addAll(billNos);
        jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='DEPARTED' WHERE dispatch_id=? AND receipt_no IN ("
                + String.join(",", java.util.Collections.nCopies(billNos.size(), "?")) + ")", billArgs.toArray());

        // 2.5) 退货（取退）明细随首次发车一并放行。
        //      退货是「去客户处取货」，车上本来没有这批货，装车清单（只查 RECEIPT）里
        //      也不会出现它，永远等不到 load_status='LOADED'。而配送中列表改用
        //      depart_time 过滤后，不给退货明细补时间戳，取退任务就会整体消失。
        jdbcTemplate.update("""
                UPDATE tms_dispatch_detail SET depart_time=?
                WHERE dispatch_id=? AND bill_type='RETURN' AND depart_time IS NULL
                """, now, dispatchId);

        // 3) 主状态：仍有未发车明细就停在 LOADED，装车入口不能消失
        boolean allDeparted = unloaded.isEmpty();
        if (allDeparted) {
            jdbcTemplate.update("UPDATE tms_dispatch SET status='DEPARTED', depart_time=COALESCE(depart_time,?) WHERE dispatch_id=?", now, dispatchId);
            jdbcTemplate.update("UPDATE tms_delivery_trip SET status='DEPARTED', depart_time=COALESCE(depart_time,?) WHERE dispatch_id=?", now, dispatchId);
        } else {
            // 首次部分发车也要落 depart_time：配送中/历史页要按发车时间排序展示
            jdbcTemplate.update("UPDATE tms_dispatch SET status='LOADED', depart_time=COALESCE(depart_time,?) WHERE dispatch_id=?", now, dispatchId);
            jdbcTemplate.update("UPDATE tms_delivery_trip SET status='LOADED', depart_time=COALESCE(depart_time,?) WHERE dispatch_id=?", now, dispatchId);
        }

        String detail = "确认发车 " + departed + " 个配送点";
        if (!unloaded.isEmpty()) {
            // 漏装单号必须落日志：这是司机主动确认后的带缺发车，事后追责与对账都要靠它
            detail += "（未装车 " + unloaded.size() + " 个待补发："
                    + unloaded.stream().map(x -> TmsUtil.str(x.get("sourceBillNo"))).collect(java.util.stream.Collectors.joining("、"))
                    + "）";
        }
        TmsUtil.log(jdbcTemplate, "tms.app.delivery", "DEPART", dispatchId, detail);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchId", dispatchId);
        result.put("status", allDeparted ? "DEPARTED" : "LOADED");
        result.put("departTime", now.toString());
        result.put("needConfirm", false);
        result.put("departCount", departed);
        result.put("unloadedCount", unloaded.size());
        // allDeparted=false 时 APP 要留在装车页继续补装，不能直接 pop 回首页
        result.put("allDeparted", allDeparted);
        return ApiResponse.ok(result);
    }

    // ==================== 到店 ====================

    /**
     * 到达门店打卡（GPS 围栏校验）。
     *
     * 入参：dispatchId、detailId（必填）；longitude、latitude、accuracy（可选，无定位权限时缺省）；
     *       abnormalReason（GPS 异常时必填）、photoUrl（异常且参数要求拍照时必填）
     *
     * 距离与异常判定一律在服务端复算，不信任前端提交的 distance/gpsAbnormal，
     * 防止司机改包绕过围栏。门店无坐标或未取到定位时降级为「无围栏模式」，
     * 只记时间不判异常，避免因基础数据缺失阻断配送。
     *
     * 幂等：仅当 arrive_time IS NULL 时写入，重复打卡返回首次打卡结果。
     */
    @PostMapping("/arrive")
    @Transactional
    public ApiResponse<Map<String, Object>> arrive(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String detailId = TmsUtil.str(body.get("detailId"));
        if (dispatchId.isEmpty() || detailId.isEmpty()) return ApiResponse.fail("400", "dispatchId、detailId 不能为空");
        String driverId = TmsUtil.currentDriverId();
        loadDispatch(dispatchId, driverId); // 校验权限

        List<Map<String, Object>> detailRows = jdbcTemplate.queryForList(
                "SELECT customer_code, customer_name, arrive_time FROM tms_dispatch_detail WHERE detail_id=? AND dispatch_id=?",
                detailId, dispatchId);
        if (detailRows.isEmpty()) return ApiResponse.fail("404", "调度明细不存在");
        Map<String, Object> detail = detailRows.get(0);

        // 已打卡：直接回放首次结果，前端据此提示「已打卡」而非报错
        if (detail.get("arrive_time") != null) {
            Map<String, Object> old = TmsUtil.camelize(jdbcTemplate.queryForList(
                    "SELECT arrive_time, arrive_distance, gps_abnormal, gps_abnormal_reason FROM tms_dispatch_detail WHERE detail_id=?",
                    detailId).get(0));
            old.put("dispatchId", dispatchId);
            old.put("detailId", detailId);
            old.put("repeated", true);
            old.put("message", "该门店已打卡，本次不重复记录");
            return ApiResponse.ok(old);
        }

        BigDecimal lng = body.get("longitude") == null ? null : TmsUtil.toBd(body.get("longitude"));
        BigDecimal lat = body.get("latitude") == null ? null : TmsUtil.toBd(body.get("latitude"));
        BigDecimal accuracy = body.get("accuracy") == null ? null : TmsUtil.toBd(body.get("accuracy"));

        ArriveConfig cfg = loadArriveConfig();

        // 门店档案坐标：缺失则无法围栏，降级放行
        BigDecimal storeLng = null, storeLat = null;
        String customerCode = TmsUtil.str(detail.get("customer_code"));
        if (!customerCode.isEmpty()) {
            List<Map<String, Object>> cs = jdbcTemplate.queryForList(
                    "SELECT longitude, latitude FROM base_customer WHERE customer_code=?", customerCode);
            if (!cs.isEmpty()) {
                storeLng = cs.get(0).get("longitude") == null ? null : TmsUtil.toBd(cs.get(0).get("longitude"));
                storeLat = cs.get(0).get("latitude") == null ? null : TmsUtil.toBd(cs.get(0).get("latitude"));
            }
        }

        BigDecimal distance = null;
        boolean abnormal = false;
        boolean geoEnabled = lng != null && lat != null && storeLng != null && storeLat != null;
        if (geoEnabled) {
            distance = BigDecimal.valueOf(haversineMeters(
                    lat.doubleValue(), lng.doubleValue(), storeLat.doubleValue(), storeLng.doubleValue()))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            abnormal = distance.doubleValue() > cfg.warnRadius;
        }

        String reason = TmsUtil.str(body.get("abnormalReason"));
        String photoUrl = TmsUtil.str(body.get("photoUrl"));
        // 仅在服务端判定为异常时才强校验，避免正常打卡被误拦
        if (abnormal) {
            if (reason.isEmpty()) {
                return ApiResponse.fail("400", String.format(
                        "定位偏差 %.0f 米，超过 %.0f 米，请填写异常原因", distance.doubleValue(), cfg.warnRadius));
            }
            if (cfg.photoRequired && photoUrl.isEmpty()) {
                return ApiResponse.fail("400", "定位异常打卡必须上传现场照片");
            }
        }

        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        jdbcTemplate.update("""
                UPDATE tms_dispatch_detail
                   SET arrive_time=?, arrive_longitude=?, arrive_latitude=?, arrive_accuracy=?,
                       arrive_distance=?, gps_abnormal=?, gps_abnormal_reason=?, arrive_photo_url=?
                 WHERE detail_id=? AND dispatch_id=? AND arrive_time IS NULL
                """, now, lng, lat, accuracy, distance, abnormal ? "Y" : "N",
                reason.isEmpty() ? null : reason, photoUrl.isEmpty() ? null : photoUrl, detailId, dispatchId);

        // 首店到达时推进调度单状态 DEPARTED → DELIVERING
        jdbcTemplate.update("UPDATE tms_dispatch SET status='DELIVERING' WHERE dispatch_id=? AND status='DEPARTED'", dispatchId);
        jdbcTemplate.update("UPDATE tms_delivery_trip SET status='DELIVERING' WHERE dispatch_id=? AND status='DEPARTED'", dispatchId);
        jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='DELIVERING' WHERE dispatch_id=? AND dispatch_status='DEPARTED'", dispatchId);

        TmsUtil.log(jdbcTemplate, "tms.app.arrive", "ARRIVE", detailId,
                "到达打卡：" + TmsUtil.str(detail.get("customer_name"))
                        + (distance == null ? "（未启用围栏）" : String.format("，偏差 %.0f 米", distance.doubleValue()))
                        + (abnormal ? "，GPS异常：" + reason : ""));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchId", dispatchId);
        result.put("detailId", detailId);
        result.put("arriveTime", now.toString().substring(0, 19));
        result.put("distance", distance);
        result.put("gpsAbnormal", abnormal ? "Y" : "N");
        result.put("geoEnabled", geoEnabled);
        result.put("repeated", false);
        result.put("message", abnormal ? "打卡成功，已记录 GPS 异常"
                : geoEnabled ? "打卡成功" : "打卡成功（门店未维护坐标，本次未做围栏校验）");
        return ApiResponse.ok(result);
    }

    /**
     * 到达打卡参数配置。
     *
     * APP 启动/进入打卡页时拉取，据此决定围栏提示强度与是否强制打卡。
     * 参数在 ERP「系统参数」页维护，无需改代码即可调整策略。
     */
    @PostMapping("/arrive/config")
    public ApiResponse<Map<String, Object>> arriveConfig() {
        ArriveConfig cfg = loadArriveConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("normalRadius", cfg.normalRadius);
        result.put("warnRadius", cfg.warnRadius);
        result.put("arriveRequired", cfg.arriveRequired);
        result.put("photoRequired", cfg.photoRequired);
        return ApiResponse.ok(result);
    }

    /** 到达打卡配置项（来自 sys_param_runtime，读取失败时用内置默认值兜底）。 */
    private record ArriveConfig(double normalRadius, double warnRadius, boolean arriveRequired, boolean photoRequired) {}

    /**
     * 读取到达打卡配置。
     *
     * 参数表异常或值非法一律回退默认值：配置读取绝不能成为打卡失败的原因。
     * 同时保证 warnRadius >= normalRadius，避免误配置导致所有打卡都判异常。
     */
    private ArriveConfig loadArriveConfig() {
        Map<String, String> kv = new HashMap<>();
        try {
            jdbcTemplate.queryForList("""
                    SELECT param_key, COALESCE(param_value, default_value) AS v
                      FROM sys_param_runtime
                     WHERE param_key IN ('TMS_ARRIVE_NORMAL_RADIUS','TMS_ARRIVE_WARN_RADIUS',
                                         'TMS_ARRIVE_REQUIRED','TMS_ARRIVE_PHOTO_REQUIRED')
                    """).forEach(r -> kv.put(TmsUtil.str(r.get("param_key")), TmsUtil.str(r.get("v"))));
        } catch (Exception ignore) {
            // 参数表不可用时走默认值
        }
        double normal = parseDouble(kv.get("TMS_ARRIVE_NORMAL_RADIUS"), 200);
        double warn = parseDouble(kv.get("TMS_ARRIVE_WARN_RADIUS"), 1000);
        if (warn < normal) warn = normal;
        return new ArriveConfig(normal, warn,
                "true".equalsIgnoreCase(TmsUtil.str(kv.get("TMS_ARRIVE_REQUIRED"))),
                !"false".equalsIgnoreCase(TmsUtil.str(kv.getOrDefault("TMS_ARRIVE_PHOTO_REQUIRED", "true"))));
    }

    private double parseDouble(String v, double def) {
        if (v == null || v.isBlank()) return def;
        try {
            double d = Double.parseDouble(v.trim());
            return d > 0 ? d : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Haversine 球面距离（米）。短距离场景精度足够，无需引入 GIS 依赖。 */
    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ==================== 签收 ====================

    /**
     * 签收 SKU 明细：按调度明细 detailId 拉取该发货单的逐商品明细（含已签收数量回写）。
     * 返回结构：
     *   detailId, dispatchId, sourceBillNo, customerCode, customerName, customerAddress,
     *   requiredQty, signedQty, rejectQty, amount, collectAmount, payMethod, items:[{ goodsCode, goodsName, unitName, requiredQty, signedQty }]
     */
    @PostMapping("/sign/items")
    public ApiResponse<Map<String, Object>> signItems(@RequestBody Map<String, Object> body) {
        String detailId = TmsUtil.str(body.get("detailId"));
        if (detailId.isEmpty()) return ApiResponse.fail("400", "detailId 不能为空");
        // 查调度明细并校验本人
        List<Map<String, Object>> detailRows = jdbcTemplate.queryForList(
                "SELECT * FROM tms_dispatch_detail WHERE detail_id=?", detailId);
        if (detailRows.isEmpty()) return ApiResponse.fail("404", "调度明细不存在");
        Map<String, Object> dRaw = detailRows.get(0);
        String dispatchId = TmsUtil.str(dRaw.get("dispatch_id"));
        loadDispatch(dispatchId, TmsUtil.currentDriverId()); // 校验权限

        Map<String, Object> d = TmsUtil.camelize(dRaw);
        String billNo = TmsUtil.str(d.get("sourceBillNo"));

        // 查发货单头
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT receipt_id, receipt_no, customer_code, customer_name, deliver_amount, receive_status FROM sales_receipt WHERE receipt_no=?",
                billNo);
        BigDecimal billAmount = heads.isEmpty() ? BigDecimal.ZERO : TmsUtil.toBd(heads.get(0).get("deliver_amount"));

        // 查 SKU 明细
        List<Map<String, Object>> items = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT d.goods_code, d.goods_name, d.unit_name, d.qty
                FROM sales_receipt_detail d
                JOIN sales_receipt r ON r.receipt_id = d.receipt_id
                WHERE r.receipt_no = ?
                ORDER BY d.detail_id
                """, billNo);

        // 已签收数量（取最近一次该 detail_id 的签收记录拆解；当前简化为按单据级 signed_qty 均摊，无明细级回写）
        BigDecimal detailSigned = BigDecimal.ZERO;
        List<Map<String, Object>> signRows = jdbcTemplate.queryForList(
                "SELECT signed_qty, reject_qty, collect_amount, pay_method FROM tms_sign_record WHERE detail_id=? ORDER BY sign_time DESC LIMIT 1",
                detailId);
        if (!signRows.isEmpty()) {
            detailSigned = TmsUtil.toBd(signRows.get(0).get("signed_qty"));
        }

        BigDecimal dRequired = TmsUtil.toBd(d.get("qty"));
        for (Map<String, Object> it : items) {
            it.put("requiredQty", TmsUtil.toBd(it.get("qty")));
            it.put("signedQty", BigDecimal.ZERO); // 简化：默认未签，APP 端可二次录入
            it.remove("qty");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("detailId", detailId);
        result.put("dispatchId", dispatchId);
        result.put("sourceBillNo", billNo);
        result.put("billType", TmsUtil.str(d.get("billType")));
        result.put("customerCode", d.get("customerCode"));
        result.put("customerName", d.get("customerName"));
        result.put("customerAddress", d.get("customerAddress"));
        result.put("seqNo", d.get("seqNo"));
        result.put("status", d.get("status"));
        result.put("requiredQty", dRequired);
        result.put("signedQty", detailSigned);
        result.put("amount", billAmount);
        // 透传打卡状态与配置，供签收页做软提示（是否阻断由 arriveRequired 决定，判定在 /sign 复算）
        ArriveConfig cfg = loadArriveConfig();
        result.put("arriveTime", d.get("arriveTime"));
        result.put("arriveDistance", d.get("arriveDistance"));
        result.put("gpsAbnormal", d.get("gpsAbnormal"));
        result.put("arriveRequired", cfg.arriveRequired());
        // 透传门店档案坐标与联系人：坐标供打卡围栏（否则会退化成无围栏模式），电话供「联系客户」
        // 同时透传结算方式：司机要在门店当场决定「这单该不该收钱」，只有货到付款才需要收，
        // 预付已付、账期挂账，缺了这个字段司机只能凭记忆判断，容易多收或漏收。
        String custCode = TmsUtil.str(d.get("customerCode"));
        if (!custCode.isEmpty()) {
            List<Map<String, Object>> cs = jdbcTemplate.queryForList(
                    "SELECT longitude, latitude, contact_name, mobile, settlement_type, shipping_address FROM base_customer WHERE customer_code=?", custCode);
            if (!cs.isEmpty()) {
                result.put("longitude", cs.get(0).get("longitude"));
                result.put("latitude", cs.get(0).get("latitude"));
                result.put("contactName", cs.get(0).get("contact_name"));
                result.put("contactMobile", cs.get(0).get("mobile"));
                // 地址回落到客户档案：tms_dispatch_detail.customer_address 是下单时的快照，
                // 历史数据里大量为空，签收页会出现地址空白。与配送点列表/详情保持同一口径。
                if (TmsUtil.str(result.get("customerAddress")).isEmpty()) {
                    result.put("customerAddress", cs.get(0).get("shipping_address"));
                }
                Object st = cs.get(0).get("settlement_type");
                result.put("settlementType", TmsUtil.str(st));
                result.put("settlementText", TmsUtil.settlementText(st));
                result.put("needCollect", TmsUtil.needCollect(st));
            }
        }
        result.put("items", items);
        return ApiResponse.ok(result);
    }

    /**
     * 门店签收（发货单）：全部/部分/拒收。
     * 入参：dispatchId, detailId, sourceBillNo, items:[{goodsCode, signedQty, rejectQty}],
     *       collectAmount, payMethod, customerSigner, remark
     */
    @PostMapping("/sign")
    @Transactional
    public ApiResponse<Map<String, Object>> sign(@RequestBody Map<String, Object> body) {
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String detailId = TmsUtil.str(body.get("detailId"));
        String sourceBillNo = TmsUtil.str(body.get("sourceBillNo"));
        if (dispatchId.isEmpty() || detailId.isEmpty() || sourceBillNo.isEmpty()) {
            return ApiResponse.fail("400", "dispatchId、detailId、sourceBillNo 不能为空");
        }
        String driverId = TmsUtil.currentDriverId();
        Map<String, Object> dispatch = loadDispatch(dispatchId, driverId);
        String tripId = TmsUtil.str(dispatch.get("tripId"));

        // 查调度明细
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM tms_dispatch_detail WHERE detail_id=? AND dispatch_id=?", detailId, dispatchId);
        if (details.isEmpty()) return ApiResponse.fail("404", "调度明细不存在");
        Map<String, Object> detail = TmsUtil.camelize(details.get(0));
        String detailStatus = TmsUtil.str(detail.get("status"));
        if ("DELIVERED".equals(detailStatus)) return ApiResponse.fail("400", "该发货单已签收");

        // 打卡门禁：默认不强制，仅当参数 TMS_ARRIVE_REQUIRED=true 时才阻断签收。
        // 服务端复算而不信任前端，避免绕过；关闭时完全不干预签收流程。
        if (loadArriveConfig().arriveRequired() && detail.get("arriveTime") == null) {
            return ApiResponse.fail("400", "当前配置要求先完成到店打卡才能签收");
        }

        BigDecimal requiredQty = TmsUtil.toBd(detail.get("qty"));
        String customerCode = TmsUtil.str(detail.get("customerCode"));
        String customerName = TmsUtil.str(detail.get("customerName"));

        // 解析签收明细
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = body.get("items") == null ? List.of() : (List<Map<String, Object>>) body.get("items");
        BigDecimal totalSigned = BigDecimal.ZERO;
        BigDecimal totalReject = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            totalSigned = totalSigned.add(TmsUtil.toBd(it.get("signedQty")));
            totalReject = totalReject.add(TmsUtil.toBd(it.get("rejectQty")));
        }
        // 未传明细默认全收
        if (items.isEmpty()) totalSigned = requiredQty;

        // 签收类型
        String signType;
        String newDetailStatus;
        if (totalSigned.compareTo(BigDecimal.ZERO) == 0 && totalReject.compareTo(BigDecimal.ZERO) > 0) {
            signType = "REJECT";
            newDetailStatus = "REJECTED";
        } else if (totalSigned.compareTo(requiredQty) < 0) {
            signType = "PARTIAL";
            newDetailStatus = "PARTIAL";
        } else {
            signType = "NORMAL";
            newDetailStatus = "DELIVERED";
        }

        BigDecimal collectAmount = TmsUtil.toBd(body.get("collectAmount"));
        String payMethod = TmsUtil.str(body.get("payMethod"));
        String customerSigner = TmsUtil.str(body.get("customerSigner"));
        String remark = TmsUtil.str(body.get("remark"));
        String signatureUrl = TmsUtil.str(body.get("signatureUrl"));
        Timestamp now = Timestamp.valueOf(TmsUtil.now());

        // 1. 写签收记录
        String signId = TmsUtil.uuid("QS");
        jdbcTemplate.update("""
                INSERT INTO tms_sign_record(sign_id, dispatch_id, detail_id, trip_id, source_bill_no,
                    customer_code, customer_name, bill_type, sign_type, signed_qty, reject_qty,
                    collect_amount, pay_method, sign_time, sign_user, customer_signer, customer_sign_img, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RECEIPT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, signId, dispatchId, detailId, tripId.isEmpty() ? null : tripId, sourceBillNo,
                customerCode, customerName, signType, totalSigned, totalReject,
                collectAmount, payMethod.isEmpty() ? null : payMethod, now, driverId, customerSigner,
                signatureUrl.isEmpty() ? null : signatureUrl, remark);

        // 1.1 随主单一并落照片（可选）。
        // 之所以在签收接口里也支持 photos，而不是一律走 /sign/upload-photo：
        // APP 离线时先把签收请求排入本地队列，此刻 signId 还不存在，
        // 照片若拆成第二个请求，重放时必然缺 signId 被 400 拒绝并永久卡在队列里。
        // 签收时 signId 已生成，这里顺带写入即可让离线链路一次成功。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> headPhotos = body.get("photos") instanceof List<?> pl
                ? (List<Map<String, Object>>) pl : List.<Map<String, Object>>of();
        for (Map<String, Object> p : headPhotos) {
            String url = TmsUtil.str(p.get("url"));
            if (url.isEmpty()) continue;
            String photoType = TmsUtil.str(p.get("photoType"));
            if (photoType.isEmpty()) photoType = "GOODS";
            jdbcTemplate.update("""
                    INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, ?, ?, ?)
                    """, TmsUtil.uuid("PH"), signId, photoType, url, TmsUtil.extractObjectKey(url));
        }

        // 2. 更新调度明细状态
        jdbcTemplate.update("UPDATE tms_dispatch_detail SET status=?, sign_time=?, sign_user=? WHERE detail_id=?",
                newDetailStatus, now, driverId, detailId);

        // 3. 更新发货单收款状态
        if (collectAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal finalAmount = TmsUtil.toBd(detail.get("amount"));
            String receiveStatus = collectAmount.compareTo(finalAmount) >= 0 ? "已收款" : "部分收款";
            jdbcTemplate.update("UPDATE sales_receipt SET receive_status=?, dispatch_status='DELIVERING' WHERE receipt_no=?",
                    receiveStatus, sourceBillNo);
        }

        // 4. 检查是否全部签收完成
        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tms_dispatch_detail WHERE dispatch_id=? AND bill_type='RECEIPT' AND status IN ('PENDING','PARTIAL')",
                Integer.class, dispatchId);
        if (pendingCount != null && pendingCount == 0) {
            jdbcTemplate.update("UPDATE tms_dispatch SET status='COMPLETED', complete_time=? WHERE dispatch_id=?", now, dispatchId);
            jdbcTemplate.update("UPDATE tms_delivery_trip SET status='COMPLETED', complete_time=? WHERE dispatch_id=?", now, dispatchId);
            jdbcTemplate.update("UPDATE sales_receipt SET dispatch_status='COMPLETED' WHERE dispatch_id=?", dispatchId);
        }

        TmsUtil.log(jdbcTemplate, "tms.app.delivery", "SIGN", sourceBillNo,
                "发货单签收：类型=" + signType + "，实收=" + totalSigned + "，拒收=" + totalReject + "，收款=" + collectAmount);
        return ApiResponse.ok(Map.of(
                "signId", signId,
                "signType", signType,
                "detailStatus", newDetailStatus,
                "signedQty", totalSigned,
                "rejectQty", totalReject,
                "collectAmount", collectAmount,
                "allCompleted", pendingCount != null && pendingCount == 0
        ));
    }

    /**
     * 上传签收照片（URL 数组，APP 端先调 /tms/app/upload/image 上传获得 URL）。
     * 入参：signId, photos:[{url, photoType}]
     */
    @PostMapping("/sign/upload-photo")
    @Transactional
    public ApiResponse<Map<String, Object>> uploadPhoto(@RequestBody Map<String, Object> body) {
        String signId = TmsUtil.str(body.get("signId"));
        if (signId.isEmpty()) return ApiResponse.fail("400", "signId 不能为空");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> photos = body.get("photos") == null ? List.of() : (List<Map<String, Object>>) body.get("photos");
        if (photos.isEmpty()) return ApiResponse.fail("400", "照片不能为空");

        List<String> photoIds = new ArrayList<>();
        for (Map<String, Object> p : photos) {
            String photoId = TmsUtil.uuid("PH");
            String photoType = TmsUtil.str(p.get("photoType"));
            if (photoType.isEmpty()) photoType = "GOODS";
            String url = TmsUtil.str(p.get("url"));
            if (url.isEmpty()) continue;
            jdbcTemplate.update("""
                    INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, ?, ?, ?)
                    """, photoId, signId, photoType, url, TmsUtil.extractObjectKey(url));
            photoIds.add(photoId);
        }

        return ApiResponse.ok(Map.of("signId", signId, "photoIds", photoIds, "count", photoIds.size()));
    }

    // ==================== GPS 定位 ====================

    /** GPS 单点上报：写 tms_driver_location + 更新调度单当前位置快照。 */
    @PostMapping("/location/report")
    @Transactional
    public ApiResponse<Map<String, Object>> locationReport(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        if (driverId.isEmpty()) return ApiResponse.fail("401", "未登录");
        BigDecimal longitude = TmsUtil.toBd(body.get("longitude"));
        BigDecimal latitude = TmsUtil.toBd(body.get("latitude"));
        if (longitude.compareTo(BigDecimal.ZERO) == 0 || latitude.compareTo(BigDecimal.ZERO) == 0) {
            return ApiResponse.fail("400", "经纬度不能为空");
        }
        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        String tripId = TmsUtil.str(body.get("tripId"));

        // 查司机名
        String driverName = "";
        if (!dispatchId.isEmpty()) {
            List<Map<String, Object>> d = jdbcTemplate.queryForList(
                    "SELECT driver_name FROM tms_dispatch WHERE dispatch_id=?", dispatchId);
            if (!d.isEmpty()) driverName = TmsUtil.str(d.get(0).get("driver_name"));
        }

        String locId = TmsUtil.uuid("LOC");
        String locTimeStr = TmsUtil.str(body.get("locTime"));
        Timestamp locTime = locTimeStr.isEmpty() ? Timestamp.valueOf(TmsUtil.now()) : Timestamp.valueOf(locTimeStr);

        jdbcTemplate.update("""
                INSERT INTO tms_driver_location(loc_id, driver_id, driver_name, dispatch_id, trip_id,
                    longitude, latitude, speed, heading, accuracy, loc_time, report_time, online)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                """, locId, driverId, driverName, dispatchId.isEmpty() ? null : dispatchId, tripId.isEmpty() ? null : tripId,
                longitude, latitude, TmsUtil.toBd(body.get("speed")), TmsUtil.toBd(body.get("heading")),
                TmsUtil.toBd(body.get("accuracy")), locTime, Timestamp.valueOf(TmsUtil.now()));

        // 更新调度单当前位置快照
        if (!dispatchId.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE tms_dispatch SET cur_longitude=?, cur_latitude=?, cur_loc_time=?, cur_speed=?
                    WHERE dispatch_id=?
                    """, longitude, latitude, locTime, TmsUtil.toBd(body.get("speed")), dispatchId);
        }

        return ApiResponse.ok(Map.of("locId", locId, "received", true));
    }

    /** GPS 批量上报（离线补传）：一次性写入多条轨迹。 */
    @PostMapping("/location/batch-report")
    @Transactional
    public ApiResponse<Map<String, Object>> batchReport(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        if (driverId.isEmpty()) return ApiResponse.fail("401", "未登录");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locations = body.get("locations") == null ? List.of() : (List<Map<String, Object>>) body.get("locations");
        if (locations.isEmpty()) return ApiResponse.fail("400", "定位数据不能为空");

        // 批量插入 GPS 轨迹（使用 batchUpdate 提升离线补传性能）
        Timestamp now = Timestamp.valueOf(TmsUtil.now());
        List<Object[]> batchArgs = new ArrayList<>();
        for (Map<String, Object> loc : locations) {
            BigDecimal longitude = TmsUtil.toBd(loc.get("longitude"));
            BigDecimal latitude = TmsUtil.toBd(loc.get("latitude"));
            if (longitude.compareTo(BigDecimal.ZERO) == 0) continue;
            String dispatchId = TmsUtil.str(loc.get("dispatchId"));
            String tripId = TmsUtil.str(loc.get("tripId"));
            String locTimeStr = TmsUtil.str(loc.get("locTime"));
            Timestamp locTime = locTimeStr.isEmpty() ? now : Timestamp.valueOf(locTimeStr);
            String locId = TmsUtil.uuid("LOC");

            batchArgs.add(new Object[]{
                    locId, driverId, dispatchId.isEmpty() ? null : dispatchId, tripId.isEmpty() ? null : tripId,
                    longitude, latitude, TmsUtil.toBd(loc.get("speed")), TmsUtil.toBd(loc.get("heading")),
                    TmsUtil.toBd(loc.get("accuracy")), locTime, now
            });
        }
        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO tms_driver_location(loc_id, driver_id, dispatch_id, trip_id,
                        longitude, latitude, speed, heading, accuracy, loc_time, report_time, online)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                    """, batchArgs);
        }
        int count = batchArgs.size();

        // 更新最后位置到调度单快照
        if (!locations.isEmpty()) {
            Map<String, Object> last = locations.get(locations.size() - 1);
            String dispatchId = TmsUtil.str(last.get("dispatchId"));
            if (!dispatchId.isEmpty()) {
                jdbcTemplate.update("""
                        UPDATE tms_dispatch SET cur_longitude=?, cur_latitude=?, cur_loc_time=?, cur_speed=?
                        WHERE dispatch_id=?
                        """, TmsUtil.toBd(last.get("longitude")), TmsUtil.toBd(last.get("latitude")),
                        Timestamp.valueOf(TmsUtil.now()), TmsUtil.toBd(last.get("speed")), dispatchId);
            }
        }

        return ApiResponse.ok(Map.of("received", count, "total", locations.size()));
    }

    // ==================== 辅助方法 ====================

    /** 加载调度单并校验司机权限。 */
    private Map<String, Object> loadDispatch(String dispatchId, String driverId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM tms_dispatch WHERE dispatch_id=? AND driver_id=?", dispatchId, driverId);
        if (rows.isEmpty()) throw new IllegalArgumentException("调度单不存在或非本人");
        Map<String, Object> d = TmsUtil.camelize(rows.get(0));
        // 查关联行程
        List<Map<String, Object>> trips = jdbcTemplate.queryForList(
                "SELECT trip_id FROM tms_delivery_trip WHERE dispatch_id=? ORDER BY create_time DESC LIMIT 1", dispatchId);
        if (!trips.isEmpty()) d.put("tripId", TmsUtil.str(trips.get(0).get("trip_id")));
        return d;
    }
}
