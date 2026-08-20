package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.common.util.BillNoGenerator;
import com.erp.finance.FinanceController;
import com.erp.sales.SalesReturnController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

/**
 * 门店结算（司机现场收款）。
 *
 * 为什么要单独一个结算动作，而不是在签收时顺手收款：
 *   1. 同一个门店常常同时有发货单和退货单，客户只按「发货 - 退货」的净额付钱，
 *      按单收款金额根本对不上账；
 *   2. 司机可能同时收现金 + 微信 + 剩余挂账，单笔 payMethod 表达不了混合收款；
 *   3. 按单收款会给一次上门生成好几张零散收款单，财务对账痛苦。
 *
 * 因此流程改成：APP 逐单签收只写本地草稿（不回传），到店结算时一次性提交。
 * 本控制器的 /store-settle 是签收数据落库的唯一入口，负责：
 *   ① 补写 tms_sign_record + tms_sign_photo（把草稿转正）
 *   ② 回写 tms_dispatch_detail.status / sales_receipt 收款状态
 *   ③ 落 tms_store_settlement 三张表（单据行 / 账户行 / 照片）
 *   ④ 挂账之外的实收部分生成 fin_receipt_bill（PENDING）并冲抵 fin_ar
 *
 * 收款单为什么落 PENDING 而不是直接 APPROVED：司机手里的钱还没交回公司，
 * 现在就入账会让资金账户余额虚高。真正入账在财务确认司机交账单时触发
 * （TmsSettlementController.audit → approveStoreSettlements）。
 *
 * APP 接口：
 *   POST /tms/app/settle/accounts    司机可用收款账户（挂账恒可选，账户来自关联表）
 *   POST /tms/app/settle/preview     结算预览（勾选单据 → 应收/退货冲减/净额）
 *   POST /tms/app/settle/submit      提交结算
 */
@RestController
@RequestMapping("/tms/app/settle")
public class TmsStoreSettleController {

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;
    private final SalesReturnController salesReturnController;
    private final FinanceController financeController;

    public TmsStoreSettleController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen,
                                    SalesReturnController salesReturnController,
                                    FinanceController financeController) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
        this.salesReturnController = salesReturnController;
        this.financeController = financeController;
    }

    /**
     * 司机可用收款账户列表。
     * 未配置关联关系时返回空数组：账户归属涉及资金安全，绝不猜测，
     * APP 此时只显示【挂账】，不会把钱记到别人账上。
     */
    @PostMapping("/accounts")
    public ApiResponse<Map<String, Object>> accounts() {
        String driverId = TmsUtil.currentDriverId();
        if (driverId.isEmpty()) return ApiResponse.fail("401", "请重新登录");
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT a.fund_account_id, a.fund_account_code, a.fund_account_name,
                       a.is_default, a.sort_order, f.account_type
                FROM tms_driver_fund_account a
                LEFT JOIN base_fund_account f ON f.fund_account_id = a.fund_account_id
                WHERE a.driver_id = ? AND a.status = '启用'
                ORDER BY a.sort_order, a.fund_account_name
                """, driverId);
        // 注意两张表的 status 值域不同：本表（V71）用中文'启用'，
        // base_fund_account 用全库统一的 'NORMAL'。改 SQL 时别互相套用。
        return ApiResponse.ok(Map.of("accounts", rows));
    }

    /**
     * 【ERP 后台】查询某司机已绑定的收款账户 + 全部可选账户。
     * 一次返回两份数据，前端配置弹窗不必再发第二个请求。
     */
    @PostMapping("/driver-accounts/list")
    public ApiResponse<Map<String, Object>> driverAccountList(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.str(body.get("driverId"));
        if (driverId.isEmpty()) return ApiResponse.fail("400", "缺少司机");
        List<Map<String, Object>> bound = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT id, fund_account_id, fund_account_code, fund_account_name,
                       is_default, sort_order, status, remark
                FROM tms_driver_fund_account
                WHERE driver_id = ?
                ORDER BY sort_order, fund_account_name
                """, driverId);
        // 只列启用的资金账户：停用账户不该再绑给司机。
        // 状态值是 'NORMAL'（全库统一，见 V2 种子数据），不是中文「启用」。
        List<Map<String, Object>> options = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT fund_account_id, fund_account_code, fund_account_name, account_type
                FROM base_fund_account
                WHERE status = 'NORMAL'
                ORDER BY fund_account_code
                """);
        return ApiResponse.ok(Map.of("bound", bound, "options", options));
    }

    /**
     * 【ERP 后台】保存某司机的收款账户绑定（全量覆盖）。
     * 入参：driverId(必), accounts[]{fundAccountId, isDefault, sortOrder, remark}
     *
     * 用「先删后插」而不是逐条 diff：绑定关系只有几条，全量覆盖最不容易
     * 出现「界面删了但库里还在」的残留，司机也就不会看到早该撤销的账户。
     */
    @PostMapping("/driver-accounts/save")
    @Transactional
    public ApiResponse<Map<String, Object>> driverAccountSave(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.str(body.get("driverId"));
        if (driverId.isEmpty()) return ApiResponse.fail("400", "缺少司机");
        List<Map<String, Object>> accounts = mapList(body.get("accounts"));

        jdbcTemplate.update("DELETE FROM tms_driver_fund_account WHERE driver_id = ?", driverId);
        int idx = 1;
        int saved = 0;
        for (Map<String, Object> a : accounts) {
            String fid = TmsUtil.str(a.get("fundAccountId"));
            if (fid.isEmpty()) continue;
            // 账户名称从主数据回查，不信任前端传值：
            // 名称会写进结算流水与收款单，前端传错会导致对账时找不到账户。
            Map<String, Object> acc = jdbcTemplate.query("""
                    SELECT fund_account_code, fund_account_name FROM base_fund_account
                    WHERE fund_account_id = ?
                    """, rs -> {
                if (!rs.next()) return null;
                return Map.<String, Object>of("code", TmsUtil.str(rs.getString(1)),
                        "name", TmsUtil.str(rs.getString(2)));
            }, fid);
            if (acc == null) continue;
            jdbcTemplate.update("""
                    INSERT INTO tms_driver_fund_account(id, driver_id, fund_account_id,
                        fund_account_code, fund_account_name, is_default, sort_order, status, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, '启用', ?)
                    """, TmsUtil.uuid("DFA"), driverId, fid,
                    TmsUtil.str(acc.get("code")), TmsUtil.str(acc.get("name")),
                    // is_default 是 VARCHAR(1) 的 'Y'/'N'（V71），不是 1/0：
                    // 写成数字会让 APP 的默认账户判断永远不成立，金额就不会预填。
                    isTrue(a.get("isDefault")) ? "Y" : "N",
                    TmsUtil.toInt(a.get("sortOrder")) > 0 ? TmsUtil.toInt(a.get("sortOrder")) : idx,
                    TmsUtil.str(a.get("remark")));
            idx++;
            saved++;
        }
        TmsUtil.log(jdbcTemplate, "tms.driverFundAccount", "SAVE", driverId, "绑定账户 " + saved + " 个");
        return ApiResponse.ok(Map.of("driverId", driverId, "saved", saved));
    }

    /**
     * 宽松真值判断。前端可能传 true / "Y" / 1 / "true" 中的任意一种，
     * 只认死一种写法会让「设为默认」在某些调用方静默失效。
     */
    private static boolean isTrue(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim();
        return "Y".equalsIgnoreCase(s) || "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    /**
     * 结算预览。
     * 入参：dispatchId?, customerCode(必), detailIds[](必，勾选的配送点单据)
     * 返回：bills[]（每单金额，退货为负）+ receiptAmount / returnAmount / settleAmount
     *       + creditOnly（是否强制挂账：净额 <= 0 或全是退货单）
     */
    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        if (driverId.isEmpty()) return ApiResponse.fail("401", "请重新登录");
        String customerCode = TmsUtil.str(body.get("customerCode"));
        List<String> detailIds = detailIds(body);
        if (customerCode.isEmpty() || detailIds.isEmpty()) {
            return ApiResponse.fail("400", "缺少门店或结算单据");
        }
        List<Map<String, Object>> bills = loadBills(driverId, customerCode, detailIds);
        if (bills.isEmpty()) return ApiResponse.fail("400", "没有可结算的单据");
        return ApiResponse.ok(summarize(bills));
    }

    /**
     * 提交结算。
     * 入参：dispatchId?, customerCode(必), signer, remark,
     *       bills[]{detailId, sourceBillNo, billType, signType, signedQty, rejectQty,
     *               signAmount, items[], customerSigner, signatureUrl, photos[], remark}
     *              —— 来自 APP 本地签收草稿，结算时一次性转正
     *       accounts[]{fundAccountId, fundAccountCode, fundAccountName, amount} 各账户实收
     *       creditAmount 挂账金额
     *       photos[]{url, photoType} 结算现场照片（必填，至少一张）
     */
    @PostMapping("/submit")
    @Transactional
    public ApiResponse<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        if (driverId.isEmpty()) return ApiResponse.fail("401", "请重新登录");
        String customerCode = TmsUtil.str(body.get("customerCode"));
        List<Map<String, Object>> draftBills = mapList(body.get("bills"));
        if (customerCode.isEmpty() || draftBills.isEmpty()) {
            return ApiResponse.fail("400", "缺少门店或结算单据");
        }
        List<Map<String, Object>> photos = mapList(body.get("photos"));
        if (photos.isEmpty()) return ApiResponse.fail("400", "请先拍摄结算现场照片");

        // 以服务端金额为准：APP 传来的 signAmount 只作参考，避免端上算错或被篡改
        List<String> detailIds = new ArrayList<>();
        Map<String, Map<String, Object>> draftByDetail = new LinkedHashMap<>();
        for (Map<String, Object> b : draftBills) {
            String did = TmsUtil.str(b.get("detailId"));
            if (did.isEmpty()) continue;
            detailIds.add(did);
            draftByDetail.put(did, b);
        }
        List<Map<String, Object>> bills = loadBills(driverId, customerCode, detailIds);
        if (bills.isEmpty()) return ApiResponse.fail("400", "没有可结算的单据");

        // 重复结算拦截。必须有，且必须在写库之前：
        // APP 端提交超时后司机往往会再点一次，而本方法每次都新建收款单并冲抵应收，
        // 没有这道校验就会对同一批单据收两次钱、生成两张 PENDING 收款单，
        // 事后只能靠财务手工红冲。表上也没有 detail_id 唯一约束可兜底（V71）。
        String settledHint = findSettledHint(detailIds);
        if (settledHint != null) return ApiResponse.fail("400", settledHint);

        // 金额按草稿的实收比例折算：部分签收只该收部分钱
        for (Map<String, Object> bill : bills) {
            Map<String, Object> draft = draftByDetail.get(TmsUtil.str(bill.get("detailId")));
            if (draft == null) continue;
            BigDecimal signAmt = TmsUtil.toBd(draft.get("signAmount"));
            if (signAmt.signum() != 0) bill.put("amount", signAmt);
        }
        Map<String, Object> sum = summarize(bills);
        BigDecimal settleAmount = TmsUtil.toBd(sum.get("settleAmount"));

        BigDecimal credit = TmsUtil.toBd(body.get("creditAmount"));
        List<Map<String, Object>> accounts = mapList(body.get("accounts"));
        BigDecimal received = BigDecimal.ZERO;
        for (Map<String, Object> a : accounts) received = received.add(TmsUtil.toBd(a.get("amount")));

        // 净额 <= 0（退货多于发货）时不允许收钱，只能挂账，由后台走退款流程
        if (settleAmount.signum() <= 0 && received.signum() > 0) {
            return ApiResponse.fail("400", "本次结算金额不大于 0，只能选择挂账");
        }
        if (settleAmount.signum() > 0
                && received.add(credit).compareTo(settleAmount) != 0) {
            return ApiResponse.fail("400", "收款 + 挂账金额必须等于应结金额 " + settleAmount.toPlainString());
        }

        String dispatchId = TmsUtil.str(body.get("dispatchId"));
        if (dispatchId.isEmpty()) dispatchId = TmsUtil.str(bills.get(0).get("dispatchId"));
        String customerName = TmsUtil.str(bills.get(0).get("customerName"));
        String tripId = jdbcTemplate.query(
                "SELECT trip_id FROM tms_delivery_trip WHERE dispatch_id = ?",
                rs -> rs.next() ? rs.getString(1) : "", dispatchId);
        String driverName = jdbcTemplate.query(
                "SELECT employee_name FROM base_employee WHERE employee_id = ?",
                rs -> rs.next() ? rs.getString(1) : "", driverId);

        String settleId = TmsUtil.uuid("MJ");
        String settleNo = billNoGen.nextNo("MJ", "tms_store_settlement", "settle_no");
        Timestamp nowTs = Timestamp.valueOf(TmsUtil.now());

        // ① 草稿转正：补签收记录 + 照片 + 明细状态
        for (Map<String, Object> bill : bills) {
            String detailId = TmsUtil.str(bill.get("detailId"));
            Map<String, Object> draft = draftByDetail.get(detailId);
            String signId = writeSignRecord(bill, draft, dispatchId, tripId, nowTs);
            insertSettleDetail(settleId, bill, draft, signId);
        }

        // ② 结算主表
        jdbcTemplate.update("""
                INSERT INTO tms_store_settlement(settle_id, settle_no, dispatch_id, trip_id,
                    driver_id, driver_name, customer_code, customer_name,
                    bill_count, receipt_amount, return_amount, settle_amount,
                    received_amount, credit_amount, settle_status, fin_status,
                    signer, settle_time, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SETTLED', 'PENDING', ?, ?, ?)
                """, settleId, settleNo, dispatchId, tripId, driverId, driverName,
                customerCode, customerName, bills.size(),
                TmsUtil.toBd(sum.get("receiptAmount")), TmsUtil.toBd(sum.get("returnAmount")),
                settleAmount, received, credit,
                TmsUtil.str(body.get("signer")), nowTs, TmsUtil.str(body.get("remark")));

        // ③ 账户明细 + 结算照片
        // 账户编码/名称一律从 base_fund_account 回查，不信前端入参（与 driverAccountSave 同策略）。
        // 收款流水页和财务收款单都直接把账户名当收款方式展示，前端漏传时会落空串，
        // 界面上就成了「收了钱但不知道收在哪个账户」。这里先统一解析一次，
        // 供账户明细、收款单主表、收款单明细三处共用，避免同一份数据出现两种口径。
        List<Object[]> settleAccounts = new ArrayList<>();
        for (Map<String, Object> a : accounts) {
            BigDecimal amt = TmsUtil.toBd(a.get("amount"));
            if (amt.signum() <= 0) continue;
            String acctId = TmsUtil.str(a.get("fundAccountId"));
            String acctCode = TmsUtil.str(a.get("fundAccountCode"));
            String acctName = TmsUtil.str(a.get("fundAccountName"));
            if (!acctId.isEmpty()) {
                List<Map<String, Object>> fa = TmsUtil.queryCamel(jdbcTemplate,
                        "SELECT fund_account_code, fund_account_name FROM base_fund_account WHERE fund_account_id = ?",
                        acctId);
                if (!fa.isEmpty()) {
                    acctCode = TmsUtil.str(fa.get(0).get("fundAccountCode"));
                    acctName = TmsUtil.str(fa.get(0).get("fundAccountName"));
                }
            }
            if (acctName.isEmpty()) acctName = "其他";
            settleAccounts.add(new Object[]{acctId, acctCode, acctName, amt});
        }
        int idx = 1;
        for (Object[] a : settleAccounts) {
            jdbcTemplate.update("""
                    INSERT INTO tms_store_settlement_account(id, settle_id, fund_account_id,
                        fund_account_code, fund_account_name, amount, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, TmsUtil.uuid("MJA"), settleId, a[0], a[1], a[2], a[3], idx++);
        }
        for (Map<String, Object> p : photos) {
            String url = TmsUtil.str(p.get("url"));
            if (url.isEmpty()) continue;
            jdbcTemplate.update("""
                    INSERT INTO tms_store_settlement_photo(photo_id, settle_id, photo_type, photo_url, photo_path)
                    VALUES (?, ?, ?, ?, ?)
                    """, TmsUtil.uuid("MJP"), settleId,
                    TmsUtil.str(p.get("photoType")).isEmpty() ? "SETTLEMENT" : TmsUtil.str(p.get("photoType")),
                    url, TmsUtil.extractObjectKey(url));
        }

        // ④ 实收部分生成收款单（PENDING）并登记待冲抵的应收
        String receiptNo = "";
        String receiptId = "";
        if (received.signum() > 0) {
            receiptId = TmsUtil.uuid("SK");
            receiptNo = billNoGen.nextNo("SK", "fin_receipt_bill", "receipt_no");
            String firstAcct = settleAccounts.isEmpty() ? "" : TmsUtil.str(settleAccounts.get(0)[2]);
            jdbcTemplate.update("""
                    INSERT INTO fin_receipt_bill(receipt_id, receipt_no, receipt_date, status,
                        counterparty_type, counterparty_code, counterparty_name,
                        handler, related_bill_no, summary, business_source,
                        total_amount, verified_amount,
                        object_name, fund_account, amount,
                        creator_name, create_time)
                    VALUES (?, ?, ?, 'PENDING', 'CUSTOMER', ?, ?, ?, ?, ?, 'DRIVER_SETTLE', ?, 0, ?, ?, ?, ?, ?)
                    """, receiptId, receiptNo, nowTs, customerCode, customerName,
                    driverName, settleNo, "司机现场收款 " + settleNo,
                    received, customerName, firstAcct, received, driverName, nowTs);
            int sort = 1;
            for (Object[] a : settleAccounts) {
                jdbcTemplate.update("""
                        INSERT INTO fin_receipt_detail(detail_id, receipt_id, fund_account, amount, remark, sort_order)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, TmsUtil.uuid("SKD"), receiptId,
                        a[2], a[3], "司机结算 " + settleNo, sort++);
            }
            jdbcTemplate.update("UPDATE tms_store_settlement SET receipt_no=?, receipt_id=? WHERE settle_id=?",
                    receiptNo, receiptId, settleId);
        }

        // ⑤ 单据状态回写：冲减部分默认已收 / 已退
        applyBillStatus(bills, settleAmount, credit, nowTs);

        // 全部明细签收完则收口调度单
        closeDispatchIfDone(dispatchId, nowTs);

        TmsUtil.log(jdbcTemplate, "tms.storeSettle", "SETTLE", settleNo,
                "门店结算 " + customerName + "：应结 " + settleAmount.toPlainString()
                        + "，实收 " + received.toPlainString() + "，挂账 " + credit.toPlainString());

        Map<String, Object> data = new LinkedHashMap<>(sum);
        data.put("settleId", settleId);
        data.put("settleNo", settleNo);
        data.put("receivedAmount", received);
        data.put("creditAmount", credit);
        data.put("receiptNo", receiptNo);
        return ApiResponse.ok(data);
    }

    // ========================================================================
    // 交账审核联动（由 TmsSettlementController.audit 调用）
    // ========================================================================

    /**
     * 财务确认司机交账单时，把该交账单下所有门店结算的实收部分正式入账。
     *
     * 为什么放在交账审核而不是结算提交：结算时钱还在司机兜里，
     * 此刻入账会让资金账户余额虚高、应收提前销账。只有财务点了「交账审核通过」，
     * 才代表钱真的回到公司，这时才做核销 + 资金入账。
     *
     * 单张结算失败不阻断整批：一个门店的 AR 数据异常不该让整车交账卡住，
     * 失败原因写进操作日志留给财务人工处理。
     *
     * @return 入账结果统计：成功张数 / 跳过张数 / 入账金额 / 失败明细
     */
    @Transactional
    public Map<String, Object> approveStoreSettlements(String settlementId, String settlementNo) {
        List<Map<String, Object>> settles = TmsUtil.queryCamel(jdbcTemplate, """
                SELECT settle_id, settle_no, receipt_id, receipt_no, received_amount, fin_status
                FROM tms_store_settlement
                WHERE settlement_id = ?
                ORDER BY create_time, settle_no
                """, settlementId);

        int approved = 0;
        int skipped = 0;
        BigDecimal amount = BigDecimal.ZERO;
        List<String> failures = new ArrayList<>();

        for (Map<String, Object> s : settles) {
            String settleId = TmsUtil.str(s.get("settleId"));
            String settleNo = TmsUtil.str(s.get("settleNo"));
            String receiptId = TmsUtil.str(s.get("receiptId"));
            BigDecimal received = TmsUtil.toBd(s.get("receivedAmount"));

            // 已入账过的不重复处理（交账单驳回后再审核会走到这里）
            if ("APPROVED".equals(TmsUtil.str(s.get("finStatus")))) { skipped++; continue; }

            // 全额挂账的结算没有收款单，只需把财务状态推到 APPROVED
            if (receiptId.isEmpty() || received.signum() <= 0) {
                jdbcTemplate.update(
                        "UPDATE tms_store_settlement SET fin_status='APPROVED' WHERE settle_id=?", settleId);
                approved++;
                continue;
            }

            try {
                Map<String, Object> res = financeController.auditReceiptForSettle(receiptId, loadSettleArNos(settleId));
                jdbcTemplate.update(
                        "UPDATE tms_store_settlement SET fin_status='APPROVED' WHERE settle_id=?", settleId);
                approved++;
                amount = amount.add(received);
                BigDecimal unmatched = TmsUtil.toBd(res.get("unmatchedAmount"));
                if (unmatched.signum() > 0) {
                    // 收到的钱比未核销应收还多（客户预付、应收已被别处核销），
                    // 钱照样入账，但要留痕让财务决定是否转预收。
                    TmsUtil.log(jdbcTemplate, "tms.storeSettle", "RECONCILE_REMAIN", settleNo,
                            "收款单 " + TmsUtil.str(res.get("receiptNo")) + " 有 "
                                    + unmatched.toPlainString() + " 未匹配到应收，请财务确认是否转预收");
                }
            } catch (RuntimeException e) {
                failures.add(settleNo + "：" + e.getMessage());
                TmsUtil.log(jdbcTemplate, "tms.storeSettle", "APPROVE_FAIL", settleNo,
                        "交账审核入账失败：" + e.getMessage());
            }
        }

        if (approved > 0 || skipped > 0) {
            TmsUtil.log(jdbcTemplate, "tms.storeSettle", "APPROVE", settlementNo,
                    "交账审核入账：成功 " + approved + " 张，跳过 " + skipped + " 张，入账金额 "
                            + amount.toPlainString() + (failures.isEmpty() ? "" : "，失败 " + failures.size() + " 张"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("approvedCount", approved);
        data.put("skippedCount", skipped);
        data.put("approvedAmount", amount);
        data.put("failures", failures);
        return data;
    }

    /** 取该门店结算涉及的应收单号，供定向核销使用（退货行无 ar_no，天然被过滤） */
    private List<String> loadSettleArNos(String settleId) {
        return jdbcTemplate.queryForList("""
                SELECT ar_no FROM tms_store_settlement_detail
                WHERE settle_id = ? AND ar_no IS NOT NULL AND ar_no <> '' AND bill_type = 'RECEIPT'
                ORDER BY id
                """, String.class, settleId);
    }

    // ========================================================================
    // 内部实现
    // ========================================================================

    /**
     * 取待结算单据金额。
     * 发货取 sales_receipt.deliver_amount；退货取 sales_return_apply.return_amount 并转负，
     * 不用 tms_dispatch_detail.amount —— 该列由调度下发时按业务单金额写入，
     * 但结算要的是签收当下的最新金额（退货数量可能被改过），所以一律回源表取。
     */
    private List<Map<String, Object>> loadBills(String driverId, String customerCode, List<String> detailIds) {
        if (detailIds.isEmpty()) return List.of();
        String ph = String.join(",", Collections.nCopies(detailIds.size(), "?"));
        List<Object> args = new ArrayList<>(detailIds);
        args.add(driverId);
        args.add(customerCode);
        return TmsUtil.queryCamel(jdbcTemplate, """
                SELECT dd.detail_id, dd.dispatch_id, dd.bill_type, dd.source_bill_no,
                       dd.customer_code, dd.customer_name, dd.qty, dd.status, dd.arrive_time,
                       CASE WHEN dd.bill_type = 'RETURN'
                            THEN -COALESCE(ra.return_amount, 0)
                            ELSE COALESCE(sr.deliver_amount, 0) END AS amount,
                       ar.ar_no
                FROM tms_dispatch_detail dd
                JOIN tms_dispatch d ON d.dispatch_id = dd.dispatch_id
                LEFT JOIN sales_receipt sr ON sr.receipt_no = dd.source_bill_no AND dd.bill_type = 'RECEIPT'
                LEFT JOIN sales_return_apply ra ON ra.apply_no = dd.source_bill_no AND dd.bill_type = 'RETURN'
                LEFT JOIN fin_ar ar ON ar.source_bill = dd.source_bill_no AND dd.bill_type = 'RECEIPT'
                WHERE dd.detail_id IN (""" + ph + """
                ) AND d.driver_id = ? AND dd.customer_code = ?
                ORDER BY dd.bill_type DESC, dd.seq_no, dd.detail_id
                """, args.toArray());
    }

    /** 汇总：发货合计、退货冲减合计、应结净额，以及是否强制挂账。 */
    private Map<String, Object> summarize(List<Map<String, Object>> bills) {
        BigDecimal receipt = BigDecimal.ZERO;
        BigDecimal ret = BigDecimal.ZERO;
        for (Map<String, Object> b : bills) {
            BigDecimal amt = TmsUtil.toBd(b.get("amount"));
            if ("RETURN".equals(TmsUtil.str(b.get("billType")))) ret = ret.add(amt.abs());
            else receipt = receipt.add(amt);
        }
        BigDecimal settle = receipt.subtract(ret);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bills", bills);
        m.put("billCount", bills.size());
        m.put("receiptAmount", receipt);
        m.put("returnAmount", ret);
        m.put("settleAmount", settle);
        // 净额不大于 0（含纯退货场景）时前端默认且只能选【挂账】
        m.put("creditOnly", settle.signum() <= 0);
        return m;
    }

    /** 把 APP 本地签收草稿写成正式签收记录，返回 signId。 */
    private String writeSignRecord(Map<String, Object> bill, Map<String, Object> draft,
                                   String dispatchId, String tripId, Timestamp nowTs) {
        String detailId = TmsUtil.str(bill.get("detailId"));
        BigDecimal signedQty = draft == null ? TmsUtil.toBd(bill.get("qty")) : TmsUtil.toBd(draft.get("signedQty"));
        BigDecimal rejectQty = draft == null ? BigDecimal.ZERO : TmsUtil.toBd(draft.get("rejectQty"));
        String signType = draft == null ? "NORMAL" : TmsUtil.str(draft.get("signType"));
        if (signType.isEmpty()) signType = "NORMAL";
        String detailStatus = switch (signType) {
            case "REJECT" -> "REJECTED";
            case "PARTIAL" -> "PARTIAL";
            default -> "DELIVERED";
        };
        String signId = TmsUtil.uuid("QS");
        jdbcTemplate.update("""
                INSERT INTO tms_sign_record(sign_id, dispatch_id, detail_id, trip_id, source_bill_no,
                    customer_code, customer_name, bill_type, sign_type, signed_qty, reject_qty,
                    collect_amount, pay_method, sign_time, sign_user, customer_signer,
                    customer_sign_img, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, signId, dispatchId, detailId, tripId, TmsUtil.str(bill.get("sourceBillNo")),
                TmsUtil.str(bill.get("customerCode")), TmsUtil.str(bill.get("customerName")),
                TmsUtil.str(bill.get("billType")), signType, signedQty, rejectQty,
                BigDecimal.ZERO, "", nowTs, TmsUtil.currentUser(),
                draft == null ? "" : TmsUtil.str(draft.get("customerSigner")),
                draft == null ? "" : TmsUtil.str(draft.get("signatureUrl")),
                draft == null ? "" : TmsUtil.str(draft.get("remark")));
        if (draft != null) {
            for (Map<String, Object> p : mapList(draft.get("photos"))) {
                String url = TmsUtil.str(p.get("url"));
                if (url.isEmpty()) continue;
                jdbcTemplate.update("""
                        INSERT INTO tms_sign_photo(photo_id, sign_id, photo_type, photo_url, photo_path)
                        VALUES (?, ?, ?, ?, ?)
                        """, TmsUtil.uuid("PH"), signId,
                        TmsUtil.str(p.get("photoType")).isEmpty() ? "GOODS" : TmsUtil.str(p.get("photoType")),
                        url, TmsUtil.extractObjectKey(url));
            }
        }
        jdbcTemplate.update("UPDATE tms_dispatch_detail SET status=?, sign_time=?, sign_user=? WHERE detail_id=?",
                detailStatus, nowTs, TmsUtil.currentUser(), detailId);
        return signId;
    }

    private void insertSettleDetail(String settleId, Map<String, Object> bill,
                                    Map<String, Object> draft, String signId) {
        jdbcTemplate.update("""
                INSERT INTO tms_store_settlement_detail(id, settle_id, detail_id, sign_id, bill_type,
                    source_bill_no, sign_type, signed_qty, reject_qty, amount, ar_no)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, TmsUtil.uuid("MJD"), settleId, TmsUtil.str(bill.get("detailId")), signId,
                TmsUtil.str(bill.get("billType")), TmsUtil.str(bill.get("sourceBillNo")),
                draft == null ? "NORMAL" : TmsUtil.str(draft.get("signType")),
                draft == null ? TmsUtil.toBd(bill.get("qty")) : TmsUtil.toBd(draft.get("signedQty")),
                draft == null ? BigDecimal.ZERO : TmsUtil.toBd(draft.get("rejectQty")),
                TmsUtil.toBd(bill.get("amount")), TmsUtil.str(bill.get("arNo")));
    }

    /**
     * 单据状态回写：结算后冲减掉的部分默认已收 / 已退。
     * 全额挂账时发货单标「未收款」，部分收款标「部分收款」，收满标「已收款」。
     */
    private void applyBillStatus(List<Map<String, Object>> bills, BigDecimal settleAmount,
                                 BigDecimal credit, Timestamp nowTs) {
        String receiveStatus;
        if (settleAmount.signum() <= 0 || credit.compareTo(settleAmount) >= 0) receiveStatus = "未收款";
        else if (credit.signum() > 0) receiveStatus = "部分收款";
        else receiveStatus = "已收款";
        for (Map<String, Object> b : bills) {
            String billNo = TmsUtil.str(b.get("sourceBillNo"));
            if ("RETURN".equals(TmsUtil.str(b.get("billType")))) {
                // 退货单不自己拼 UPDATE：sales_return_apply 没有 return_status 列（V60 明确
                // 「不新增状态列」），且退货金额、signed_qty、入库流程都要由销售退货模块统一推进。
                // 委托 onDriverCollected，它会按实收数量重算 return_amount 并写 logistics_status='司机已回收'。
                // 不传 items = 全收，与本页「退货整单回收」的语义一致。
                try {
                    salesReturnController.onDriverCollected(billNo, null, TmsUtil.currentUser());
                } catch (RuntimeException e) {
                    // 退货单状态不满足（如非司机回收方式、未确认）不应阻断整笔结算：
                    // 货款收了才是当务之急，退货异常留给后台对账处理。
                    TmsUtil.log(jdbcTemplate, "tms.storeSettle", "RETURN_SKIP", billNo,
                            "退货回收回写失败：" + e.getMessage());
                }
            } else {
                jdbcTemplate.update("""
                        UPDATE sales_receipt SET receive_status=?, dispatch_status='DELIVERING'
                        WHERE receipt_no=?
                        """, receiveStatus, billNo);
            }
        }
    }

    /** 名下发货明细全部签收后，收口调度单 / 行程 / 发货单状态。 */
    private void closeDispatchIfDone(String dispatchId, Timestamp nowTs) {
        Integer pending = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tms_dispatch_detail
                WHERE dispatch_id=? AND bill_type='RECEIPT' AND status IN ('PENDING','PARTIAL')
                """, Integer.class, dispatchId);
        if (pending != null && pending == 0) {
            jdbcTemplate.update("UPDATE tms_dispatch SET status='COMPLETED', complete_time=? WHERE dispatch_id=?",
                    nowTs, dispatchId);
            jdbcTemplate.update("UPDATE tms_delivery_trip SET status='COMPLETED' WHERE dispatch_id=?", dispatchId);
            jdbcTemplate.update("""
                    UPDATE sales_receipt SET dispatch_status='COMPLETED'
                    WHERE receipt_no IN (SELECT source_bill_no FROM tms_dispatch_detail
                                         WHERE dispatch_id=? AND bill_type='RECEIPT')
                    """, dispatchId);
        }
    }

    /**
     * 查这批明细里是否已有结算过的，有则返回给司机看的提示，没有返回 null。
     *
     * 只要命中一条就拦下整笔：结算是「一个配送点一次收清」的语义，
     * 放过其余单据会把一次上门拆成两张结算单，对账时看不出这是同一次收款。
     */
    private String findSettledHint(List<String> detailIds) {
        if (detailIds.isEmpty()) return null;
        String ph = String.join(",", Collections.nCopies(detailIds.size(), "?"));
        return jdbcTemplate.query("""
                SELECT sd.source_bill_no, s.settle_no
                FROM tms_store_settlement_detail sd
                JOIN tms_store_settlement s ON s.settle_id = sd.settle_id
                WHERE sd.detail_id IN (""" + ph + """
                ) AND s.settle_status <> 'VOID'
                """, rs -> {
            if (!rs.next()) return null;
            return "单据 " + TmsUtil.str(rs.getString(1)) + " 已结算（结算单 "
                    + TmsUtil.str(rs.getString(2)) + "），请返回刷新后重试";
        }, detailIds.toArray());
    }

    private List<String> detailIds(Map<String, Object> body) {
        Object raw = body.get("detailIds");
        List<String> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                String s = TmsUtil.str(o);
                if (!s.isEmpty()) ids.add(s);
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }
}
