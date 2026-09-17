package com.erp.finance.account;

import com.erp.common.util.BillNoGenerator;
import com.erp.finance.dayclose.BizDayCloseGuard;
import com.erp.finance.gl.GlHookService;
import com.erp.system.OperationLogService;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 厂家费用兑现单 DX（PRD-36 M3，方案 §6.6~§6.8）。
 *
 * <p>一单一方式不混合：
 * <ul>
 *   <li>CASH 现金结算：上半区 JF 行、下半区资金行，两区合计相等；资金 IN + 供应商往来台账 IN
 *       + EXPENSE 流水 FACTORY_EXP_SETTLE_CASH；不碰 AP/核销记录；</li>
 *   <li>OFFSET 冲应付（账扣）：JF 合计 = AP 冲销合计；逐行写核销真值（business_type=
 *       FACTORY_EXPENSE_OFFSET）、回写 fin_ap 三金额与 pur_receipt.pay_status；
 *       EXPENSE 减 + AP 减（AP_SETTLE_EXPENSE）；反审核按剩余核销记录重算；</li>
 *   <li>OTHER 其他核销：一单一个对方科目（1123 转预付 / 1405 货补 / 5601%、5602% 减免 /
 *       5711 坏账）；EXPENSE 减 FACTORY_EXP_SETTLE_OTHER，1123 另增 PREPAY；
 *       往来台账写转账类备查记录（不动余额）。</li>
 * </ul>
 */
@Service
public class FactorySettleService {

    public static final String MODULE = "fin.factory_settle";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";

    public static final String TYPE_CASH = "CASH";
    public static final String TYPE_OFFSET = "OFFSET";
    public static final String TYPE_OTHER = "OTHER";

    /** 往来台账 business_type：现金兑现及其反审核。 */
    public static final String BIZ_FACTORY_CASH = "FACTORY_EXPENSE_CASH";
    public static final String BIZ_FACTORY_CASH_CANCEL = "FACTORY_EXPENSE_CASH_CANCEL";
    public static final String BIZ_FACTORY_OTHER_CANCEL = "FACTORY_EXPENSE_OTHER_CANCEL";

    private static final Set<String> OTHER_EXACT_SUBJECTS = Set.of("1123", "1405", "5711");

    private final JdbcTemplate jdbc;
    private final BillNoGenerator billNoGen;
    private final SupplierAccountService accounts;
    private final GlHookService glHooks;
    private final BizDayCloseGuard dayCloseGuard;
    private final OperationLogService opLog;

    public FactorySettleService(JdbcTemplate jdbc, BillNoGenerator billNoGen,
                                SupplierAccountService accounts, GlHookService glHooks,
                                BizDayCloseGuard dayCloseGuard, OperationLogService opLog) {
        this.jdbc = jdbc;
        this.billNoGen = billNoGen;
        this.accounts = accounts;
        this.glHooks = glHooks;
        this.dayCloseGuard = dayCloseGuard;
        this.opLog = opLog;
    }

    // ==================== 列表 / 详情 ====================

    public Map<String, Object> page(Map<String, Object> req) {
        int pageNo = Math.max(1, TmsUtil.toInt(req.get("pageNo")));
        int pageSizeRaw = TmsUtil.toInt(req.get("pageSize"));
        int pageSize = pageSizeRaw <= 0 ? 20 : Math.min(200, pageSizeRaw);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String no = TmsUtil.str(req.get("settleNo"));
        if (!no.isEmpty()) {
            where.append(" AND settle_no LIKE ?");
            args.add("%" + no + "%");
        }
        String supplier = TmsUtil.str(req.get("supplier"));
        if (!supplier.isEmpty()) {
            where.append(" AND (supplier_code LIKE ? OR supplier_name LIKE ?)");
            args.add("%" + supplier + "%");
            args.add("%" + supplier + "%");
        }
        String status = TmsUtil.str(req.get("status"));
        if (!status.isEmpty()) {
            where.append(" AND status=?");
            args.add(status);
        }
        String type = TmsUtil.str(req.get("settleType"));
        if (!type.isEmpty()) {
            where.append(" AND settle_type=?");
            args.add(type);
        }
        String dateFrom = TmsUtil.str(req.get("dateFrom"));
        if (!dateFrom.isEmpty()) {
            where.append(" AND settle_date>=?");
            args.add(java.sql.Date.valueOf(dateFrom.length() > 10 ? dateFrom.substring(0, 10) : dateFrom));
        }
        String dateTo = TmsUtil.str(req.get("dateTo"));
        if (!dateTo.isEmpty()) {
            where.append(" AND settle_date<=?");
            args.add(java.sql.Date.valueOf(dateTo.length() > 10 ? dateTo.substring(0, 10) : dateTo));
        }

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_factory_settle" + where, Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT settle_id, settle_no, supplier_code, supplier_name, settle_date, settle_type, "
                        + "total_amount, contra_subject_code, contra_subject_name, related_bill_no, "
                        + "status, handler, remark, business_source, source_bill_no, "
                        + "creator_name, create_time, auditor_name, audit_time "
                        + "FROM fin_factory_settle" + where
                        + " ORDER BY settle_date DESC, create_time DESC, settle_no DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        for (Map<String, Object> r : records) {
            r.put("statusText", STATUS_APPROVED.equals(TmsUtil.str(r.get("status"))) ? "已审核" : "待审核");
            r.put("settleTypeText", FactoryExpenseService.settleTypeText(TmsUtil.str(r.get("settleType"))));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("pageNo", pageNo);
        result.put("pageSize", pageSize);
        result.put("total", total);
        return result;
    }

    /** 详情：主单 + JF 核销行 + AP 冲销行（OFFSET）+ 资金行（CASH）。 */
    public Map<String, Object> detail(Map<String, Object> req) {
        Map<String, Object> head = loadHead(TmsUtil.str(req.get("settleId")), TmsUtil.str(req.get("settleNo")));
        if (head == null) {
            throw new IllegalArgumentException("厂家费用兑现单不存在");
        }
        String id = TmsUtil.str(head.get("settleId"));
        head.put("statusText", STATUS_APPROVED.equals(TmsUtil.str(head.get("status"))) ? "已审核" : "待审核");
        head.put("settleTypeText", FactoryExpenseService.settleTypeText(TmsUtil.str(head.get("settleType"))));
        head.put("jfDetails", TmsUtil.queryCamel(jdbc,
                "SELECT id, settle_id, factory_expense_no, expense_date, claim_type, expense_amount, "
                        + "settled_before, settle_amount, settle_status_after, sort_order "
                        + "FROM fin_factory_settle_detail WHERE settle_id=? ORDER BY sort_order, id", id));
        head.put("apDetails", TmsUtil.queryCamel(jdbc,
                "SELECT id, settle_id, ap_no, source_bill, bill_date, due_date, ap_amount, "
                        + "unpaid_before, offset_amount, settle_status_after, sort_order "
                        + "FROM fin_factory_settle_ap WHERE settle_id=? ORDER BY sort_order, id", id));
        head.put("fundDetails", TmsUtil.queryCamel(jdbc,
                "SELECT id, settle_id, fund_account, amount, remark, sort_order "
                        + "FROM fin_factory_settle_fund WHERE settle_id=? ORDER BY sort_order, id", id));
        return head;
    }

    /** JF 候选：该供应商已审核非红字、未全兑现的费用单（按费用日期升序 FIFO）。 */
    public List<Map<String, Object>> jfCandidates(Map<String, Object> req) {
        String supplierCode = TmsUtil.str(req.get("supplierCode"));
        if (supplierCode.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT factory_expense_no, expense_date, claim_type, total_amount, settled_amount, "
                        + "unsettled_amount, settle_status, external_voucher_no, remark "
                        + "FROM fin_factory_expense WHERE supplier_code=? AND status='APPROVED' "
                        + "AND is_red='N' AND unsettled_amount>0 "
                        + "ORDER BY expense_date ASC, factory_expense_no ASC LIMIT 200", supplierCode);
        for (Map<String, Object> r : rows) {
            r.put("claimTypeText", FactoryExpenseService.claimTypeText(TmsUtil.str(r.get("claimType"))));
        }
        return rows;
    }

    /** AP 候选：该供应商未结清、非负的应付行（按到期日升序，空到期日末位）。 */
    public List<Map<String, Object>> apCandidates(Map<String, Object> req) {
        String supplierCode = TmsUtil.str(req.get("supplierCode"));
        if (supplierCode.isEmpty()) {
            return List.of();
        }
        String keyword = TmsUtil.str(req.get("keyword"));
        StringBuilder sql = new StringBuilder(
                "SELECT ap_no, source_bill, due_date, ap_amount, paid_amount, unpaid_amount, status, supplier "
                        + "FROM fin_ap WHERE unpaid_amount>0 AND ap_amount>=0 ");
        List<Object> args = new ArrayList<>();
        if (!keyword.isEmpty()) {
            sql.append(" AND (ap_no LIKE ? OR source_bill LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY CASE WHEN due_date IS NULL THEN 1 ELSE 0 END, due_date ASC, ap_no ASC LIMIT 500");
        List<Map<String, Object>> all = TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
        List<Map<String, Object>> mine = new ArrayList<>();
        for (Map<String, Object> ap : all) {
            if (supplierCode.equals(accounts.resolveApSupplierCode(ap))) {
                mine.add(ap);
                if (mine.size() >= 200) {
                    break;
                }
            }
        }
        return mine;
    }

    // ==================== 建/改/删（PENDING） ====================

    @Transactional
    public Map<String, Object> create(Map<String, Object> req, String operator) {
        ParsedSettle bill = parseBill(req);
        String id = newId("DX");
        String no = billNoGen.nextNo(BillNoGenerator.BillType.FACTORY_SETTLE,
                "fin_factory_settle", "settle_no");
        insertHead(id, no, bill, operator);
        insertJfDetails(id, bill.jfLines);
        if (TYPE_OFFSET.equals(bill.settleType)) {
            insertApDetails(id, bill.apLines);
        }
        if (TYPE_CASH.equals(bill.settleType)) {
            insertFundDetails(id, bill.fundLines);
        }
        opLog.log(MODULE, "CREATE", no, "新建厂家费用兑现单 " + no + "（"
                + FactoryExpenseService.settleTypeText(bill.settleType) + "），供应商 "
                + bill.supplierName + "，金额 " + bill.total.toPlainString() + " 元");
        return Map.of("settleId", id, "settleNo", no);
    }

    @Transactional
    public Map<String, Object> update(Map<String, Object> req, String operator) {
        String id = TmsUtil.str(req.get("settleId"));
        Map<String, Object> head = requireHead(id);
        requirePending(head);
        ParsedSettle bill = parseBill(req);
        String no = TmsUtil.str(head.get("settleNo"));
        jdbc.update("DELETE FROM fin_factory_settle_detail WHERE settle_id=?", id);
        jdbc.update("DELETE FROM fin_factory_settle_ap WHERE settle_id=?", id);
        jdbc.update("DELETE FROM fin_factory_settle_fund WHERE settle_id=?", id);
        insertJfDetails(id, bill.jfLines);
        if (TYPE_OFFSET.equals(bill.settleType)) {
            insertApDetails(id, bill.apLines);
        }
        if (TYPE_CASH.equals(bill.settleType)) {
            insertFundDetails(id, bill.fundLines);
        }
        jdbc.update("UPDATE fin_factory_settle SET supplier_code=?,supplier_name=?,settle_date=?,"
                        + "settle_type=?,total_amount=?,contra_subject_code=?,contra_subject_name=?,"
                        + "related_bill_no=?,handler=?,remark=? WHERE settle_id=?",
                bill.supplierCode, bill.supplierName, java.sql.Date.valueOf(bill.settleDate),
                bill.settleType, bill.total,
                TYPE_OTHER.equals(bill.settleType) ? bill.contraSubjectCode : null,
                TYPE_OTHER.equals(bill.settleType) ? bill.contraSubjectName : null,
                bill.relatedBillNo, bill.handler, bill.remark, id);
        opLog.log(MODULE, "UPDATE", no, "修改厂家费用兑现单 " + no
                + "，金额 " + bill.total.toPlainString() + " 元，操作人 " + operator);
        return Map.of("settleId", id, "settleNo", no);
    }

    @Transactional
    public void delete(String id, String operator) {
        Map<String, Object> head = requireHead(id);
        requirePending(head);
        String no = TmsUtil.str(head.get("settleNo"));
        jdbc.update("DELETE FROM fin_factory_settle_detail WHERE settle_id=?", id);
        jdbc.update("DELETE FROM fin_factory_settle_ap WHERE settle_id=?", id);
        jdbc.update("DELETE FROM fin_factory_settle_fund WHERE settle_id=?", id);
        jdbc.update("DELETE FROM fin_factory_settle WHERE settle_id=?", id);
        opLog.log(MODULE, "DELETE", no, "删除厂家费用兑现单 " + no + "，操作人 " + operator);
    }

    // ==================== 审核 / 反审核 ====================

    @Transactional
    public void audit(String id, String operator) {
        Map<String, Object> head = requireHead(id);
        requirePending(head);
        doAudit(head, operator);
    }

    @Transactional
    public void cancelAudit(String id, String operator) {
        Map<String, Object> head = requireHead(id);
        if (!STATUS_APPROVED.equals(TmsUtil.str(head.get("status")))) {
            throw new IllegalArgumentException("仅已审核单据可取消审核");
        }
        doCancel(head, operator);
    }

    private void doAudit(Map<String, Object> head, String operator) {
        String id = TmsUtil.str(head.get("settleId"));
        String no = TmsUtil.str(head.get("settleNo"));
        LocalDate settleDate = toLocalDate(head.get("settleDate"));
        String type = TmsUtil.str(head.get("settleType"));
        String supplierCode = TmsUtil.str(head.get("supplierCode"));
        String supplierName = TmsUtil.str(head.get("supplierName"));
        dayCloseGuard.assertWritable(settleDate, "厂家费用兑现单", no);

        accounts.ensureAccount(supplierCode);
        accounts.lockAccount(supplierCode);

        // ---- 上半区：JF 行真值重检 + 快照 ----
        List<Map<String, Object>> jfRows = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_factory_settle_detail WHERE settle_id=? ORDER BY sort_order, id", id);
        if (jfRows.isEmpty()) {
            throw new IllegalArgumentException("兑现单 " + no + " 没有费用核销明细，不能审核");
        }
        BigDecimal total = BigDecimal.ZERO;
        List<JfSettle> jfSettles = new ArrayList<>();
        Set<String> seenJf = new LinkedHashSet<>();
        for (Map<String, Object> row : jfRows) {
            String jfNo = TmsUtil.str(row.get("factoryExpenseNo"));
            BigDecimal amount = nz(TmsUtil.toBd(row.get("settleAmount")));
            if (amount.signum() <= 0) {
                continue;
            }
            if (!seenJf.add(jfNo)) {
                throw new IllegalArgumentException("厂家费用单 " + jfNo + " 在兑现明细中重复，请合并为一行");
            }
            List<Map<String, Object>> jfs = TmsUtil.queryCamel(jdbc,
                    "SELECT * FROM fin_factory_expense WHERE factory_expense_no=? FOR UPDATE", jfNo);
            if (jfs.isEmpty()) {
                throw new IllegalArgumentException("厂家费用单 " + jfNo + " 不存在");
            }
            Map<String, Object> jf = jfs.get(0);
            if (!"APPROVED".equals(TmsUtil.str(jf.get("status")))) {
                throw new IllegalArgumentException("厂家费用单 " + jfNo + " 未审核，不能兑现");
            }
            if ("Y".equals(TmsUtil.str(jf.get("isRed")))) {
                throw new IllegalArgumentException("红字厂家费用单 " + jfNo + " 不能被兑现");
            }
            if (!supplierCode.equals(TmsUtil.str(jf.get("supplierCode")))) {
                throw new IllegalArgumentException("厂家费用单 " + jfNo + " 不属于供应商 " + supplierName);
            }
            BigDecimal unsettled = nz(TmsUtil.toBd(jf.get("unsettledAmount")));
            if (amount.compareTo(unsettled) > 0) {
                throw new IllegalArgumentException("厂家费用单 " + jfNo + " 剩余未兑现额仅 " + unsettled
                        + " 元，本次兑现 " + amount + " 元超出");
            }
            BigDecimal settledBefore = nz(TmsUtil.toBd(jf.get("settledAmount")));
            BigDecimal settledAfter = settledBefore.add(amount);
            BigDecimal jfTotal = nz(TmsUtil.toBd(jf.get("totalAmount")));
            BigDecimal unsettledAfter = jfTotal.subtract(settledAfter);
            String statusAfter = unsettledAfter.signum() <= 0
                    ? SupplierAccountConst.CLAIM_DONE
                    : settledAfter.signum() > 0 ? SupplierAccountConst.CLAIM_PART
                    : SupplierAccountConst.CLAIM_UNCLAIMED;
            jfSettles.add(new JfSettle(jfNo, amount, settledBefore, settledAfter,
                    unsettledAfter, statusAfter));
            // 明细快照回写
            jdbc.update("UPDATE fin_factory_settle_detail SET settled_before=?, settle_status_after=?, "
                            + "expense_date=?, claim_type=?, expense_amount=? WHERE id=?",
                    settledBefore, statusAfter,
                    jf.get("expenseDate"), TmsUtil.str(jf.get("claimType")), jfTotal,
                    TmsUtil.str(row.get("id")));
            total = total.add(amount);
        }
        if (total.signum() <= 0) {
            throw new IllegalArgumentException("兑现金额合计必须大于 0");
        }
        BigDecimal headTotal = nz(TmsUtil.toBd(head.get("totalAmount")));
        if (total.compareTo(headTotal) != 0) {
            throw new IllegalArgumentException("费用核销合计 " + total + " 与单据金额 "
                    + headTotal + " 不一致，请刷新后重试");
        }

        // ---- 方式分支 ----
        List<SupplierAccountService.ExpOffsetLine> offsetLines = new ArrayList<>();
        switch (type) {
            case TYPE_CASH -> auditCash(head, settleDate, supplierCode, supplierName, total, id, no, operator);
            case TYPE_OFFSET -> auditOffset(head, settleDate, supplierCode, supplierName, total,
                    id, no, offsetLines);
            case TYPE_OTHER -> auditOther(head, supplierCode, supplierName, total, no);
            default -> throw new IllegalArgumentException("未知的兑现方式：" + type);
        }

        // ---- 回写 JF 主单与形成行兑现标志 ----
        for (JfSettle j : jfSettles) {
            jdbc.update("UPDATE fin_factory_expense SET settled_amount=?, unsettled_amount=?, settle_status=? "
                    + "WHERE factory_expense_no=?", j.settledAfter, j.unsettledAfter, j.statusAfter, j.jfNo);
            accounts.writeBackClaimStatus(j.jfNo, j.settledAfter, j.statusAfter);
        }

        // ---- 账户流水 ----
        switch (type) {
            case TYPE_CASH -> accounts.postFactorySettleCash(no, settleDate, supplierCode,
                    supplierName, total, TmsUtil.str(head.get("remark")));
            case TYPE_OFFSET -> accounts.postFactorySettleOffset(no, settleDate, supplierCode,
                    supplierName, total, offsetLines);
            case TYPE_OTHER -> accounts.postFactorySettleOther(no, settleDate, supplierCode,
                    supplierName, total,
                    "1123".equals(TmsUtil.str(head.get("contraSubjectCode"))),
                    TmsUtil.str(head.get("contraSubjectName")));
        }

        jdbc.update("UPDATE fin_factory_settle SET total_amount=?, status='APPROVED', "
                        + "auditor_name=?, audit_time=? WHERE settle_id=?",
                total, operator, Timestamp.valueOf(LocalDateTime.now()), id);
        glHooks.onFactorySettleAudited(no);
        opLog.log(MODULE, "AUDIT", no, "审核厂家费用兑现单 " + no + "（"
                + FactoryExpenseService.settleTypeText(type) + "），供应商 " + supplierName
                + "，兑现 " + total.toPlainString() + " 元，费用单 " + jfSettles.size() + " 张");
    }

    /** CASH：资金多行 IN + 档案余额 + 往来台账 IN（守恒：Σ资金行=total 建单时已校验，审核再核）。 */
    private void auditCash(Map<String, Object> head, LocalDate settleDate, String supplierCode,
                           String supplierName, BigDecimal total, String id, String no, String operator) {
        List<Map<String, Object>> funds = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_factory_settle_fund WHERE settle_id=? ORDER BY sort_order, id", id);
        if (funds.isEmpty()) {
            throw new IllegalArgumentException("现金兑现单 " + no + " 没有资金账户明细");
        }
        BigDecimal fundTotal = BigDecimal.ZERO;
        Set<String> touched = new LinkedHashSet<>();
        for (Map<String, Object> f : funds) {
            String fa = TmsUtil.str(f.get("fundAccount"));
            BigDecimal amt = nz(TmsUtil.toBd(f.get("amount")));
            if (amt.signum() <= 0) {
                throw new IllegalArgumentException("资金账户 " + fa + " 的到账金额必须大于 0");
            }
            assertFundAccount(fa);
            BigDecimal bal = getFundBalance(fa).add(amt);
            insertFundLedger(fa, "IN", amt, no, bal, operator, settleDate);
            updateFundBalance(fa, bal);
            touched.add(fa);
            fundTotal = fundTotal.add(amt);
        }
        if (fundTotal.compareTo(total) != 0) {
            throw new IllegalArgumentException("资金到账合计 " + fundTotal + " 与费用兑现合计 "
                    + total + " 不一致");
        }
        touched.forEach(this::rebuildFundChain);

        BigDecimal cpBal = getCounterpartyBalance(supplierCode).add(total);
        writeCounterpartyLedger(supplierCode, supplierName, "IN", total, no,
                BIZ_FACTORY_CASH, cpBal, "收到厂家费用兑现款 " + no);
    }

    /** OFFSET：逐 AP 行写真值、回写 fin_ap；pur_receipt 付款状态按涉及收货单重算。 */
    private void auditOffset(Map<String, Object> head, LocalDate settleDate, String supplierCode,
                             String supplierName, BigDecimal total, String id, String no,
                             List<SupplierAccountService.ExpOffsetLine> outLines) {
        List<Map<String, Object>> aps = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_factory_settle_ap WHERE settle_id=? ORDER BY sort_order, id", id);
        if (aps.isEmpty()) {
            throw new IllegalArgumentException("账扣兑现单 " + no + " 没有冲应付明细");
        }
        BigDecimal apTotal = BigDecimal.ZERO;
        Set<String> seenAp = new LinkedHashSet<>();
        Set<String> touchedReceipts = new LinkedHashSet<>();
        for (Map<String, Object> row : aps) {
            String apNo = TmsUtil.str(row.get("apNo"));
            BigDecimal amount = nz(TmsUtil.toBd(row.get("offsetAmount")));
            if (amount.signum() <= 0) {
                continue;
            }
            if (!seenAp.add(apNo)) {
                throw new IllegalArgumentException("应付单 " + apNo + " 在冲销明细中重复，请合并为一行");
            }
            List<Map<String, Object>> locked = TmsUtil.queryCamel(jdbc,
                    "SELECT * FROM fin_ap WHERE ap_no=? FOR UPDATE", apNo);
            if (locked.isEmpty()) {
                throw new IllegalArgumentException("应付单 " + apNo + " 不存在");
            }
            Map<String, Object> ap = locked.get(0);
            if (!supplierCode.equals(accounts.resolveApSupplierCode(ap))) {
                throw new IllegalArgumentException("应付单 " + apNo + " 不属于供应商 " + supplierName);
            }
            BigDecimal apAmount = nz(TmsUtil.toBd(ap.get("apAmount")));
            if (apAmount.signum() < 0) {
                throw new IllegalArgumentException("负数应付单 " + apNo + " 不能用于账扣，请改用其他兑现方式");
            }
            BigDecimal unpaid = nz(TmsUtil.toBd(ap.get("unpaidAmount")));
            if (amount.compareTo(unpaid) > 0) {
                throw new IllegalArgumentException("应付单 " + apNo + " 未结金额仅 " + unpaid
                        + " 元，本次冲销 " + amount + " 元超出");
            }
            String sourceBill = TmsUtil.str(ap.get("sourceBill"));
            BigDecimal paidAfter = nz(TmsUtil.toBd(ap.get("paidAmount"))).add(amount);
            BigDecimal unpaidAfter = apAmount.subtract(paidAfter);
            String apStatus = unpaidAfter.signum() <= 0 ? "VERIFIED" : "UNVERIFIED";
            jdbc.update("UPDATE fin_ap SET paid_amount=?, unpaid_amount=?, status=? WHERE ap_no=?",
                    paidAfter, unpaidAfter, apStatus, apNo);
            String settleStatusAfter = unpaidAfter.signum() <= 0
                    ? SupplierAccountConst.SETTLE_DONE : SupplierAccountConst.SETTLE_PART;

            String recordId = insertReconcileRecord(no, settleDate, apNo, sourceBill,
                    ap.get("dueDate"), supplierCode, supplierName, amount);

            jdbc.update("UPDATE fin_factory_settle_ap SET source_bill=?, bill_date=?, due_date=?, "
                            + "ap_amount=?, unpaid_before=?, settle_status_after=? WHERE id=?",
                    sourceBill, java.sql.Date.valueOf(settleDate),
                    sqlDate(ap.get("dueDate")),
                    apAmount, unpaid, settleStatusAfter, TmsUtil.str(row.get("id")));

            SupplierAccountService.ExpOffsetLine line = new SupplierAccountService.ExpOffsetLine();
            line.apNo = apNo;
            line.sourceBill = sourceBill;
            line.amount = amount;
            line.paidAfter = paidAfter;
            line.settleStatusAfter = settleStatusAfter;
            line.reconcileId = recordId;
            outLines.add(line);
            if (!sourceBill.isEmpty()) {
                touchedReceipts.add(sourceBill);
            }
            apTotal = apTotal.add(amount);
        }
        if (apTotal.compareTo(total) != 0) {
            throw new IllegalArgumentException("冲应付合计 " + apTotal + " 与费用兑现合计 "
                    + total + " 不一致");
        }
        touchedReceipts.forEach(this::refreshReceiptPayStatus);
    }

    /**
     * OTHER：科目白名单已在建单校验；往来台账写 OUT 非现金结转（余额链不断档，口径见方案 §6.8）。
     * 5711 坏账必须填原因。
     */
    private void auditOther(Map<String, Object> head, String supplierCode,
                            String supplierName, BigDecimal total, String no) {
        String code = TmsUtil.str(head.get("contraSubjectCode"));
        String name = TmsUtil.str(head.get("contraSubjectName"));
        String remark = TmsUtil.str(head.get("remark"));
        if ("5711".equals(code) && remark.isEmpty()) {
            throw new IllegalArgumentException("坏账核销（5711）必须填写原因备注");
        }
        BigDecimal cpBal = getCounterpartyBalance(supplierCode).subtract(total);
        writeCounterpartyLedger(supplierCode, supplierName, "OUT", total, no,
                SupplierAccountConst.BIZ_FACTORY_EXPENSE_OTHER, cpBal,
                "费用其他兑现 " + no + "（" + code + " " + name + "）"
                        + (remark.isEmpty() ? "" : "：" + remark));
    }

    private void doCancel(Map<String, Object> head, String operator) {
        String id = TmsUtil.str(head.get("settleId"));
        String no = TmsUtil.str(head.get("settleNo"));
        LocalDate settleDate = toLocalDate(head.get("settleDate"));
        String type = TmsUtil.str(head.get("settleType"));
        String supplierCode = TmsUtil.str(head.get("supplierCode"));
        String supplierName = TmsUtil.str(head.get("supplierName"));
        BigDecimal total = nz(TmsUtil.toBd(head.get("totalAmount")));
        dayCloseGuard.assertWritable(settleDate, "厂家费用兑现单", no);

        accounts.ensureAccount(supplierCode);
        accounts.lockAccount(supplierCode);

        // ---- JF 行回退（按 JF 真值重算：考虑并行红字冲减） ----
        List<Map<String, Object>> jfRows = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_factory_settle_detail WHERE settle_id=? ORDER BY sort_order, id", id);
        for (Map<String, Object> row : jfRows) {
            String jfNo = TmsUtil.str(row.get("factoryExpenseNo"));
            BigDecimal amount = nz(TmsUtil.toBd(row.get("settleAmount")));
            if (amount.signum() <= 0) {
                continue;
            }
            List<Map<String, Object>> jfs = TmsUtil.queryCamel(jdbc,
                    "SELECT * FROM fin_factory_expense WHERE factory_expense_no=? FOR UPDATE", jfNo);
            if (jfs.isEmpty()) {
                continue;
            }
            Map<String, Object> jf = jfs.get(0);
            BigDecimal settledAfter = nz(TmsUtil.toBd(jf.get("settledAmount"))).subtract(amount);
            if (settledAfter.signum() < 0) {
                settledAfter = BigDecimal.ZERO;
            }
            BigDecimal redApproved = approvedReduction(jfNo);
            BigDecimal resolved = settledAfter.add(redApproved);
            BigDecimal jfTotal = nz(TmsUtil.toBd(jf.get("totalAmount")));
            BigDecimal unsettledAfter = jfTotal.subtract(resolved);
            String statusAfter = resolved.signum() == 0
                    ? SupplierAccountConst.CLAIM_UNCLAIMED
                    : resolved.compareTo(jfTotal) >= 0 ? SupplierAccountConst.CLAIM_DONE
                    : SupplierAccountConst.CLAIM_PART;
            jdbc.update("UPDATE fin_factory_expense SET settled_amount=?, unsettled_amount=?, settle_status=? "
                    + "WHERE factory_expense_no=?", settledAfter, unsettledAfter, statusAfter, jfNo);
            accounts.writeBackClaimStatus(jfNo, settledAfter, statusAfter);
        }
        jdbc.update("UPDATE fin_factory_settle_detail SET settle_status_after=NULL WHERE settle_id=?", id);

        // ---- 方式分支回退 ----
        List<SupplierAccountService.ExpOffsetLine> offsetLines = new ArrayList<>();
        switch (type) {
            case TYPE_CASH -> cancelCash(head, supplierCode, supplierName, total, id, no, operator);
            case TYPE_OFFSET -> cancelOffset(supplierName, total, id, no, offsetLines);
            case TYPE_OTHER -> cancelOther(head, supplierCode, supplierName, total, no);
            default -> throw new IllegalArgumentException("未知的兑现方式：" + type);
        }

        // ---- 账户红字冲回 ----
        accounts.reverseFactorySettle(type, no, settleDate, supplierCode, supplierName, total, offsetLines);

        jdbc.update("UPDATE fin_factory_settle SET status='PENDING', auditor_name=NULL, audit_time=NULL "
                + "WHERE settle_id=?", id);
        glHooks.onFactorySettleUnaudited(no);
        opLog.log(MODULE, "UN_AUDIT", no, "反审核厂家费用兑现单 " + no + "（"
                + FactoryExpenseService.settleTypeText(type) + "），冲回 " + total.toPlainString()
                + " 元，操作人 " + operator);
    }

    /** CASH 反审核：资金追加红字 OUT、往来红冲 OUT（只增不删，照付款单反审核口径）。 */
    private void cancelCash(Map<String, Object> head, String supplierCode,
                            String supplierName, BigDecimal total, String id, String no, String operator) {
        List<Map<String, Object>> funds = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_factory_settle_fund WHERE settle_id=? ORDER BY sort_order, id", id);
        BigDecimal fundTotal = BigDecimal.ZERO;
        Set<String> touched = new LinkedHashSet<>();
        for (Map<String, Object> f : funds) {
            String fa = TmsUtil.str(f.get("fundAccount"));
            BigDecimal amt = nz(TmsUtil.toBd(f.get("amount")));
            if (amt.signum() <= 0) {
                continue;
            }
            BigDecimal bal = getFundBalance(fa).subtract(amt);
            insertFundLedger(fa, "OUT", amt, no + "(取消审核)", bal, operator,
                    toLocalDate(head.get("settleDate")));
            updateFundBalance(fa, bal);
            touched.add(fa);
            fundTotal = fundTotal.add(amt);
        }
        if (fundTotal.compareTo(total) != 0) {
            throw new IllegalStateException("兑现单 " + no + " 资金合计与单据金额不一致，数据已被修改");
        }
        touched.forEach(this::rebuildFundChain);

        BigDecimal cpBal = getCounterpartyBalance(supplierCode).subtract(total);
        writeCounterpartyLedger(supplierCode, supplierName, "OUT", total, no + "(取消审核)",
                BIZ_FACTORY_CASH_CANCEL, cpBal,
                "取消审核：冲回厂家兑现款 " + no);
    }

    /**
     * OFFSET 反审核：删本 DX 核销记录后，按剩余核销记录重算涉及 AP 的三金额（§6.7），
     * 再按 fin_ap 真值回写 pur_receipt.pay_status。
     */
    private void cancelOffset(String supplierName,
                              BigDecimal total, String id, String no,
                              List<SupplierAccountService.ExpOffsetLine> outLines) {
        List<Map<String, Object>> aps = TmsUtil.queryCamel(jdbc,
                "SELECT ap_no, source_bill, offset_amount FROM fin_factory_settle_ap "
                        + "WHERE settle_id=? ORDER BY sort_order, id", id);
        Set<String> touchedReceipts = new LinkedHashSet<>();
        BigDecimal apTotal = BigDecimal.ZERO;
        for (Map<String, Object> row : aps) {
            String apNo = TmsUtil.str(row.get("apNo"));
            BigDecimal amount = nz(TmsUtil.toBd(row.get("offsetAmount")));
            String sourceBill = TmsUtil.str(row.get("sourceBill"));
            if (amount.signum() <= 0) {
                continue;
            }
            outLines.add(makeOffsetLine(apNo, sourceBill, amount));
            if (!sourceBill.isEmpty()) {
                touchedReceipts.add(sourceBill);
            }
            apTotal = apTotal.add(amount);
        }
        if (apTotal.compareTo(total) != 0) {
            throw new IllegalStateException("兑现单 " + no + " 冲应付合计与单据金额不一致，数据已被修改");
        }

        // 先删本 DX 的核销真值
        jdbc.update("DELETE FROM fin_reconcile_record WHERE receipt_no=? AND business_type=?",
                no, SupplierAccountConst.BIZ_FACTORY_EXPENSE_OFFSET);

        // 按剩余记录重算每个 AP（含与其他单据交错的场景）
        for (SupplierAccountService.ExpOffsetLine l : outLines) {
            List<Map<String, Object>> locked = TmsUtil.queryCamel(jdbc,
                    "SELECT ap_amount FROM fin_ap WHERE ap_no=? FOR UPDATE", l.apNo);
            if (locked.isEmpty()) {
                continue;
            }
            BigDecimal apAmount = nz(TmsUtil.toBd(locked.get(0).get("apAmount")));
            BigDecimal paid = nz(jdbc.queryForObject(
                    "SELECT COALESCE(SUM(reconcile_amount),0) FROM fin_reconcile_record "
                            + "WHERE ar_no=? AND counterparty_type='SUPPLIER'",
                    BigDecimal.class, l.apNo));
            BigDecimal unpaidAfter = apAmount.subtract(paid);
            String apStatus = unpaidAfter.signum() <= 0 ? "VERIFIED" : "UNVERIFIED";
            jdbc.update("UPDATE fin_ap SET paid_amount=?, unpaid_amount=?, status=? WHERE ap_no=?",
                    paid, unpaidAfter, apStatus, l.apNo);
        }
        touchedReceipts.forEach(this::refreshReceiptPayStatus);
        jdbc.update("UPDATE fin_factory_settle_ap SET settle_status_after=NULL, unpaid_before=NULL "
                + "WHERE settle_id=?", id);
    }

    /** OTHER 反审核：往来台账追加 IN 冲回行（余额链回加）；1123 预付冲回守卫在账户层。 */
    private void cancelOther(Map<String, Object> head, String supplierCode,
                             String supplierName, BigDecimal total, String no) {
        String code = TmsUtil.str(head.get("contraSubjectCode"));
        String name = TmsUtil.str(head.get("contraSubjectName"));
        BigDecimal cpBal = getCounterpartyBalance(supplierCode).add(total);
        writeCounterpartyLedger(supplierCode, supplierName, "IN", total,
                no + "(取消审核)", BIZ_FACTORY_OTHER_CANCEL, cpBal,
                "取消审核：费用其他兑现冲回 " + no + "（" + code + " " + name + "）");
    }

    // ==================== 建单解析 ====================

    private static final class JfSettle {
        private final String jfNo;
        private final BigDecimal amount;
        private final BigDecimal settledBefore;
        private final BigDecimal settledAfter;
        private final BigDecimal unsettledAfter;
        private final String statusAfter;

        private JfSettle(String jfNo, BigDecimal amount, BigDecimal settledBefore,
                         BigDecimal settledAfter, BigDecimal unsettledAfter, String statusAfter) {
            this.jfNo = jfNo;
            this.amount = amount;
            this.settledBefore = settledBefore;
            this.settledAfter = settledAfter;
            this.unsettledAfter = unsettledAfter;
            this.statusAfter = statusAfter;
        }
    }

    private static final class ParsedSettle {
        private String settleType;
        private String supplierCode;
        private String supplierName;
        private LocalDate settleDate;
        private String handler;
        private String remark;
        private String contraSubjectCode;
        private String contraSubjectName;
        private String relatedBillNo;
        private BigDecimal total = BigDecimal.ZERO;
        private final List<Map<String, Object>> jfLines = new ArrayList<>();
        private final List<Map<String, Object>> apLines = new ArrayList<>();
        private final List<Map<String, Object>> fundLines = new ArrayList<>();
    }

    private ParsedSettle parseBill(Map<String, Object> req) {
        ParsedSettle bill = new ParsedSettle();
        bill.settleType = TmsUtil.str(req.get("settleType"));
        if (!TYPE_CASH.equals(bill.settleType) && !TYPE_OFFSET.equals(bill.settleType)
                && !TYPE_OTHER.equals(bill.settleType)) {
            throw new IllegalArgumentException("请选择兑现方式（现金结算/冲应付/其他核销）");
        }
        bill.supplierCode = TmsUtil.str(req.get("supplierCode"));
        if (bill.supplierCode.isEmpty()) {
            throw new IllegalArgumentException("请选择供应商");
        }
        List<Map<String, Object>> suppliers = TmsUtil.queryCamel(jdbc,
                "SELECT supplier_code, supplier_name, status FROM base_supplier "
                        + "WHERE supplier_code=? LIMIT 1", bill.supplierCode);
        if (suppliers.isEmpty()) {
            throw new IllegalArgumentException("供应商编码 " + bill.supplierCode + " 不存在");
        }
        Map<String, Object> supplier = suppliers.get(0);
        if ("DELETED".equals(TmsUtil.str(supplier.get("status")))) {
            throw new IllegalArgumentException("供应商已停用/删除，不能制单");
        }
        bill.supplierName = TmsUtil.str(req.get("supplierName"));
        if (bill.supplierName.isEmpty()) {
            bill.supplierName = TmsUtil.str(supplier.get("supplierName"));
        }
        String dateStr = TmsUtil.str(req.get("settleDate"));
        bill.settleDate = dateStr.isEmpty() ? LocalDate.now() : LocalDate.parse(dateStr.substring(0, 10));
        bill.handler = TmsUtil.str(req.get("handler"));
        if (bill.handler.isEmpty()) {
            throw new IllegalArgumentException("请填写经手人");
        }
        bill.remark = TmsUtil.str(req.get("remark"));
        bill.relatedBillNo = TmsUtil.str(req.get("relatedBillNo"));

        // ---- JF 行（三方式通用） ----
        Object rawJf = req.get("jfDetails");
        if (!(rawJf instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一行厂家费用单并填写兑现金额");
        }
        Set<String> seenJf = new LinkedHashSet<>();
        int sort = 1;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String jfNo = TmsUtil.str(m.get("factoryExpenseNo"));
            BigDecimal amount = nz(TmsUtil.toBd(m.get("settleAmount")));
            if (jfNo.isEmpty() || amount.signum() <= 0) {
                continue;
            }
            if (!seenJf.add(jfNo)) {
                throw new IllegalArgumentException("厂家费用单 " + jfNo + " 在兑现明细中重复，请合并为一行");
            }
            List<Map<String, Object>> jfs = TmsUtil.queryCamel(jdbc,
                    "SELECT factory_expense_no, supplier_code, expense_date, claim_type, total_amount, "
                            + "settled_amount, unsettled_amount, status, is_red "
                            + "FROM fin_factory_expense WHERE factory_expense_no=?", jfNo);
            if (jfs.isEmpty()) {
                throw new IllegalArgumentException("厂家费用单 " + jfNo + " 不存在");
            }
            Map<String, Object> jf = jfs.get(0);
            if (!"APPROVED".equals(TmsUtil.str(jf.get("status")))) {
                throw new IllegalArgumentException("厂家费用单 " + jfNo + " 未审核，不能兑现");
            }
            if ("Y".equals(TmsUtil.str(jf.get("isRed")))) {
                throw new IllegalArgumentException("红字厂家费用单 " + jfNo + " 不能被兑现");
            }
            if (!bill.supplierCode.equals(TmsUtil.str(jf.get("supplierCode")))) {
                throw new IllegalArgumentException("厂家费用单 " + jfNo + " 不属于供应商 " + bill.supplierName);
            }
            BigDecimal unsettled = nz(TmsUtil.toBd(jf.get("unsettledAmount")));
            if (amount.compareTo(unsettled) > 0) {
                throw new IllegalArgumentException("厂家费用单 " + jfNo + " 剩余未兑现额仅 " + unsettled
                        + " 元，本次兑现 " + amount + " 元超出");
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("factoryExpenseNo", jfNo);
            d.put("expenseDate", jf.get("expenseDate"));
            d.put("claimType", TmsUtil.str(jf.get("claimType")));
            d.put("expenseAmount", nz(TmsUtil.toBd(jf.get("totalAmount"))));
            d.put("settledBefore", nz(TmsUtil.toBd(jf.get("settledAmount"))));
            d.put("settleAmount", amount);
            d.put("sortOrder", sort++);
            bill.jfLines.add(d);
            bill.total = bill.total.add(amount);
        }
        if (bill.jfLines.isEmpty()) {
            throw new IllegalArgumentException("请至少填写一行大于 0 的兑现金额");
        }

        // ---- 方式特有行 ----
        if (TYPE_CASH.equals(bill.settleType)) {
            Object rawFunds = req.get("fundDetails");
            if (!(rawFunds instanceof List<?> funds) || funds.isEmpty()) {
                throw new IllegalArgumentException("现金兑现请至少填写一行资金账户到账");
            }
            BigDecimal fundTotal = BigDecimal.ZERO;
            int fSort = 1;
            for (Object o : funds) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String fa = TmsUtil.str(m.get("fundAccount"));
                BigDecimal amount = nz(TmsUtil.toBd(m.get("amount")));
                if (fa.isEmpty() || amount.signum() <= 0) {
                    continue;
                }
                assertFundAccount(fa);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("fundAccount", fa);
                f.put("amount", amount);
                f.put("remark", TmsUtil.str(m.get("remark")));
                f.put("sortOrder", fSort++);
                bill.fundLines.add(f);
                fundTotal = fundTotal.add(amount);
            }
            if (bill.fundLines.isEmpty()) {
                throw new IllegalArgumentException("请至少填写一行金额大于 0 的资金到账");
            }
            if (fundTotal.compareTo(bill.total) != 0) {
                throw new IllegalArgumentException("资金到账合计 " + fundTotal + " 与费用兑现合计 "
                        + bill.total + " 不一致");
            }
        } else if (TYPE_OFFSET.equals(bill.settleType)) {
            Object rawAps = req.get("apDetails");
            if (!(rawAps instanceof List<?> apList) || apList.isEmpty()) {
                throw new IllegalArgumentException("账扣兑现请至少选择一行未结清应付");
            }
            BigDecimal apTotal = BigDecimal.ZERO;
            Set<String> seenAp = new LinkedHashSet<>();
            int aSort = 1;
            for (Object o : apList) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String apNo = TmsUtil.str(m.get("apNo"));
                BigDecimal amount = nz(TmsUtil.toBd(m.get("offsetAmount")));
                if (apNo.isEmpty() || amount.signum() <= 0) {
                    continue;
                }
                if (!seenAp.add(apNo)) {
                    throw new IllegalArgumentException("应付单 " + apNo + " 在冲销明细中重复，请合并为一行");
                }
                List<Map<String, Object>> aps = TmsUtil.queryCamel(jdbc,
                        "SELECT * FROM fin_ap WHERE ap_no=?", apNo);
                if (aps.isEmpty()) {
                    throw new IllegalArgumentException("应付单 " + apNo + " 不存在");
                }
                Map<String, Object> ap = aps.get(0);
                if (!bill.supplierCode.equals(accounts.resolveApSupplierCode(ap))) {
                    throw new IllegalArgumentException("应付单 " + apNo + " 不属于供应商 " + bill.supplierName);
                }
                BigDecimal apAmount = nz(TmsUtil.toBd(ap.get("apAmount")));
                if (apAmount.signum() < 0) {
                    throw new IllegalArgumentException("负数应付单 " + apNo + " 不能用于账扣");
                }
                BigDecimal unpaid = nz(TmsUtil.toBd(ap.get("unpaidAmount")));
                if (unpaid.signum() <= 0) {
                    throw new IllegalArgumentException("应付单 " + apNo + " 已结清，不能再冲销");
                }
                if (amount.compareTo(unpaid) > 0) {
                    throw new IllegalArgumentException("应付单 " + apNo + " 未结金额仅 " + unpaid
                            + " 元，本次冲销 " + amount + " 元超出");
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("apNo", apNo);
                d.put("sourceBill", TmsUtil.str(ap.get("sourceBill")));
                d.put("dueDate", ap.get("dueDate"));
                d.put("apAmount", apAmount);
                d.put("unpaidBefore", unpaid);
                d.put("offsetAmount", amount);
                d.put("sortOrder", aSort++);
                bill.apLines.add(d);
                apTotal = apTotal.add(amount);
            }
            if (bill.apLines.isEmpty()) {
                throw new IllegalArgumentException("请至少填写一行大于 0 的冲销金额");
            }
            if (apTotal.compareTo(bill.total) != 0) {
                throw new IllegalArgumentException("冲应付合计 " + apTotal + " 与费用兑现合计 "
                        + bill.total + " 不一致");
            }
        } else {
            // OTHER：一单一个对方科目，白名单
            String code = TmsUtil.str(req.get("contraSubjectCode"));
            if (code.isEmpty()) {
                throw new IllegalArgumentException("其他核销请选择对方科目");
            }
            Map<String, Object> acc = assertOtherContraSubject(code);
            bill.contraSubjectCode = code;
            bill.contraSubjectName = TmsUtil.str(acc.get("accountName"));
            if ("5711".equals(code) && bill.remark.isEmpty()) {
                throw new IllegalArgumentException("坏账核销（对方科目 5711）必须填写原因备注");
            }
        }
        return bill;
    }

    /** OTHER 对方科目白名单：1123/1405/5711 精确，5601%/5602% 前缀，均须末级启用。 */
    private Map<String, Object> assertOtherContraSubject(String code) {
        boolean whitelisted = OTHER_EXACT_SUBJECTS.contains(code)
                || code.startsWith("5601") || code.startsWith("5602");
        if (!whitelisted) {
            throw new IllegalArgumentException("对方科目 " + code + " 不在允许范围"
                    + "（1123 预付账款 / 1405 库存商品 / 5601、5602 末级费用科目 / 5711 营业外支出）");
        }
        List<Map<String, Object>> accs = TmsUtil.queryCamel(jdbc,
                "SELECT account_code, account_name, status FROM fin_account "
                        + "WHERE account_code=? AND is_leaf=TRUE", code);
        if (accs.isEmpty()) {
            throw new IllegalArgumentException("对方科目 " + code + " 不存在或不是末级科目");
        }
        if (!"启用".equals(TmsUtil.str(accs.get(0).get("status")))) {
            throw new IllegalArgumentException("对方科目 " + code + " 已停用");
        }
        return accs.get(0);
    }

    private void assertFundAccount(String fundAccount) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM base_fund_account WHERE fund_account_code=? OR fund_account_name=?",
                Integer.class, fundAccount, fundAccount);
        if (n == null || n == 0) {
            throw new IllegalArgumentException("资金账户「" + fundAccount + "」不存在");
        }
    }

    // ==================== 落库 ====================

    private void insertHead(String id, String no, ParsedSettle bill, String operator) {
        jdbc.update("INSERT INTO fin_factory_settle(settle_id,settle_no,supplier_code,supplier_name,"
                        + "settle_date,settle_type,total_amount,contra_subject_code,contra_subject_name,"
                        + "related_bill_no,status,handler,remark,business_source,source_bill_no,"
                        + "creator_name,create_time) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?, 'PENDING',?,?,'MANUAL',NULL,?,CURRENT_TIMESTAMP)",
                id, no, bill.supplierCode, bill.supplierName, java.sql.Date.valueOf(bill.settleDate),
                bill.settleType, bill.total,
                TYPE_OTHER.equals(bill.settleType) ? bill.contraSubjectCode : null,
                TYPE_OTHER.equals(bill.settleType) ? bill.contraSubjectName : null,
                TYPE_OTHER.equals(bill.settleType) ? bill.relatedBillNo : null,
                bill.handler, bill.remark, operator);
    }

    private void insertJfDetails(String id, List<Map<String, Object>> lines) {
        for (Map<String, Object> d : lines) {
            jdbc.update("INSERT INTO fin_factory_settle_detail(id,settle_id,factory_expense_no,"
                            + "expense_date,claim_type,expense_amount,settled_before,settle_amount,"
                            + "settle_status_after,sort_order) VALUES(?,?,?,?,?,?,?,?,NULL,?)",
                    newId("DXD"), id, TmsUtil.str(d.get("factoryExpenseNo")), sqlDate(d.get("expenseDate")),
                    TmsUtil.str(d.get("claimType")), nz(TmsUtil.toBd(d.get("expenseAmount"))),
                    nz(TmsUtil.toBd(d.get("settledBefore"))), nz(TmsUtil.toBd(d.get("settleAmount"))),
                    TmsUtil.toInt(d.get("sortOrder")));
        }
    }

    private void insertApDetails(String id, List<Map<String, Object>> lines) {
        for (Map<String, Object> d : lines) {
            jdbc.update("INSERT INTO fin_factory_settle_ap(id,settle_id,ap_no,source_bill,bill_date,"
                            + "due_date,ap_amount,unpaid_before,offset_amount,settle_status_after,sort_order) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,NULL,?)",
                    newId("DXA"), id, TmsUtil.str(d.get("apNo")), TmsUtil.str(d.get("sourceBill")),
                    null, sqlDate(d.get("dueDate")), nz(TmsUtil.toBd(d.get("apAmount"))),
                    nz(TmsUtil.toBd(d.get("unpaidBefore"))), nz(TmsUtil.toBd(d.get("offsetAmount"))),
                    TmsUtil.toInt(d.get("sortOrder")));
        }
    }

    private void insertFundDetails(String id, List<Map<String, Object>> lines) {
        for (Map<String, Object> d : lines) {
            jdbc.update("INSERT INTO fin_factory_settle_fund(id,settle_id,fund_account,amount,remark,sort_order) "
                            + "VALUES(?,?,?,?,?,?)",
                    newId("DXF"), id, TmsUtil.str(d.get("fundAccount")),
                    nz(TmsUtil.toBd(d.get("amount"))), TmsUtil.str(d.get("remark")),
                    TmsUtil.toInt(d.get("sortOrder")));
        }
    }

    // ==================== 真值：核销记录 / pur_receipt 回写 ====================

    /** 写核销真值（14 列，口径同 FinanceController.writeReconcileRecordV2）。 */
    private String insertReconcileRecord(String dxNo, LocalDate receiptDate, String apNo, String sourceBill,
                                         Object dueDate, String supplierCode, String supplierName,
                                         BigDecimal amount) {
        String recordId = "RR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        java.sql.Date bizDate = sqlDate(dueDate);
        jdbc.update("INSERT INTO fin_reconcile_record(record_id, receipt_no, receipt_date, "
                        + "business_no, business_type, business_date, "
                        + "counterparty_type, counterparty_code, counterparty_name, "
                        + "reconcile_amount, receipt_remark, business_remark, ar_no, source_bill) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                recordId, dxNo, java.sql.Date.valueOf(receiptDate),
                apNo, SupplierAccountConst.BIZ_FACTORY_EXPENSE_OFFSET, bizDate,
                "SUPPLIER", supplierCode, supplierName, amount,
                "厂家费用账扣 " + dxNo, "", apNo, sourceBill);
        return recordId;
    }

    /**
     * 按 fin_ap 真值回写 pur_receipt.pay_status（同收货单全部 AP 的已付/应付派生）：
     * 未付款 / 部分付款 / 完成付款。非采购收货来源（CGTH 等）无行可更，跳过。
     */
    private void refreshReceiptPayStatus(String sourceBill) {
        if (sourceBill == null || sourceBill.isEmpty()) {
            return;
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pur_receipt WHERE receipt_no=?", Integer.class, sourceBill);
        if (exists == null || exists == 0) {
            return;
        }
        Map<String, Object> sum = jdbc.queryForMap(
                "SELECT COALESCE(SUM(ap_amount),0) ap_total, COALESCE(SUM(paid_amount),0) paid_total "
                        + "FROM fin_ap WHERE source_bill=?", sourceBill);
        BigDecimal apTotal = nz((BigDecimal) sum.get("AP_TOTAL"));
        BigDecimal paidTotal = nz((BigDecimal) sum.get("PAID_TOTAL"));
        String status;
        if (paidTotal.signum() <= 0) {
            status = "未付款";
        } else if (apTotal.signum() > 0 && paidTotal.compareTo(apTotal) >= 0) {
            status = "完成付款";
        } else {
            status = "部分付款";
        }
        jdbc.update("UPDATE pur_receipt SET pay_status=? WHERE receipt_no=?", status, sourceBill);
    }

    private SupplierAccountService.ExpOffsetLine makeOffsetLine(String apNo, String sourceBill,
                                                                BigDecimal amount) {
        SupplierAccountService.ExpOffsetLine l = new SupplierAccountService.ExpOffsetLine();
        l.apNo = apNo;
        l.sourceBill = sourceBill;
        l.amount = amount;
        return l;
    }

    /** 原单已审核红字总额（正数）。 */
    private BigDecimal approvedReduction(String jfNo) {
        BigDecimal red = jdbc.queryForObject(
                "SELECT COALESCE(SUM(-total_amount),0) FROM fin_factory_expense "
                        + "WHERE red_source_no=? AND is_red='Y' AND status='APPROVED'",
                BigDecimal.class, jfNo);
        return nz(red);
    }

    // ==================== 资金/往来台账（自包含实现，口径同 FinanceController） ====================

    private BigDecimal getFundBalance(String fundAccount) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT balance_after FROM fin_fund_ledger WHERE fund_account=? "
                        + "ORDER BY occurred_at DESC, ledger_no DESC LIMIT 1", fundAccount);
        if (rows.isEmpty()) {
            List<Map<String, Object>> acc = jdbc.queryForList(
                    "SELECT balance FROM base_fund_account WHERE fund_account_name=? OR fund_account_code=? LIMIT 1",
                    fundAccount, fundAccount);
            return acc.isEmpty() ? BigDecimal.ZERO : nz((BigDecimal) acc.get(0).get("BALANCE"));
        }
        return nz((BigDecimal) rows.get(0).get("BALANCE_AFTER"));
    }

    private void updateFundBalance(String fundAccount, BigDecimal newBalance) {
        jdbc.update("UPDATE base_fund_account SET balance=? WHERE fund_account_name=? OR fund_account_code=?",
                newBalance, fundAccount, fundAccount);
    }

    private void insertFundLedger(String fundAccount, String direction, BigDecimal amount,
                                  String sourceBill, BigDecimal balanceAfter, String operator,
                                  LocalDate occurredDate) {
        jdbc.update("INSERT INTO fin_fund_ledger(ledger_id, ledger_no, fund_account, direction, amount, "
                        + "source_bill, balance_after, occurred_at, operator_name) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)",
                "FL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                "FUND" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4),
                fundAccount, direction, amount, sourceBill, balanceAfter,
                occurredDate == null
                        ? Timestamp.valueOf(LocalDateTime.now())
                        : Timestamp.valueOf(occurredDate.atTime(java.time.LocalTime.now())),
                operator);
    }

    /**
     * 按发生时间重建资金账户余额链（口径同 FinanceController.rebuildFundChain：
     * 档案当前余额 − 全部净额倒推期初，再滚算）。流水只增不删。
     */
    private void rebuildFundChain(String fundAccount) {
        if (fundAccount == null || fundAccount.isBlank()) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ledger_id, direction, amount FROM fin_fund_ledger WHERE fund_account=? "
                        + "ORDER BY occurred_at ASC, ledger_no ASC", fundAccount);
        if (rows.isEmpty()) {
            return;
        }
        BigDecimal net = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            BigDecimal amt = nz((BigDecimal) row.get("AMOUNT"));
            net = "IN".equals(String.valueOf(row.get("DIRECTION"))) ? net.add(amt) : net.subtract(amt);
        }
        List<Map<String, Object>> acc = jdbc.queryForList(
                "SELECT balance FROM base_fund_account WHERE fund_account_name=? OR fund_account_code=? LIMIT 1",
                fundAccount, fundAccount);
        BigDecimal archive = acc.isEmpty() ? BigDecimal.ZERO : nz((BigDecimal) acc.get(0).get("BALANCE"));
        BigDecimal running = archive.subtract(net);
        for (Map<String, Object> row : rows) {
            BigDecimal amt = nz((BigDecimal) row.get("AMOUNT"));
            running = "IN".equals(String.valueOf(row.get("DIRECTION"))) ? running.add(amt) : running.subtract(amt);
            jdbc.update("UPDATE fin_fund_ledger SET balance_after=? WHERE ledger_id=?",
                    running, String.valueOf(row.get("LEDGER_ID")));
        }
        updateFundBalance(fundAccount, running);
    }

    private BigDecimal getCounterpartyBalance(String supplierCode) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT balance_after FROM fin_counterparty_ledger WHERE counterparty_type='SUPPLIER' "
                        + "AND counterparty_code=? ORDER BY occurred_at DESC, ledger_id DESC LIMIT 1",
                supplierCode);
        return rows.isEmpty() || rows.get(0).get("BALANCE_AFTER") == null
                ? BigDecimal.ZERO : nz((BigDecimal) rows.get(0).get("BALANCE_AFTER"));
    }

    /**
     * 写供应商往来台账（occurred_at 恒取当前时刻，口径同 FinanceController：
     * 余额链按插入顺序滚算，不用业务日期，避免补录往日记账造成尾余额丢量）。
     */
    private void writeCounterpartyLedger(String supplierCode, String supplierName, String direction,
                                         BigDecimal amount, String sourceBillNo, String businessType,
                                         BigDecimal balanceAfter, String remark) {
        jdbc.update("INSERT INTO fin_counterparty_ledger(ledger_id, counterparty_type, counterparty_code, "
                        + "counterparty_name, direction, amount, source_bill_no, business_type, "
                        + "balance_after, occurred_at, remark) VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?)",
                "CL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                "SUPPLIER", supplierCode, supplierName, direction, amount, sourceBillNo, businessType,
                balanceAfter, remark);
    }

    // ==================== 通用 ====================

    private Map<String, Object> loadHead(String id, String no) {
        if (!id.isEmpty()) {
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                    "SELECT * FROM fin_factory_settle WHERE settle_id=?", id);
            return rows.isEmpty() ? null : rows.get(0);
        }
        if (!no.isEmpty()) {
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                    "SELECT * FROM fin_factory_settle WHERE settle_no=?", no);
            return rows.isEmpty() ? null : rows.get(0);
        }
        return null;
    }

    private Map<String, Object> requireHead(String id) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_factory_settle WHERE settle_id=?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("厂家费用兑现单不存在或已删除");
        }
        return rows.get(0);
    }

    private void requirePending(Map<String, Object> head) {
        if (!STATUS_PENDING.equals(TmsUtil.str(head.get("status")))) {
            throw new IllegalArgumentException("仅待审核状态的单据可执行该操作");
        }
    }

    private static LocalDate toLocalDate(Object v) {
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime().toLocalDate();
        }
        String s = TmsUtil.str(v);
        return s.isEmpty() ? LocalDate.now() : LocalDate.parse(s.substring(0, 10));
    }

    /** JDBC/Timestamp/字符串 → java.sql.Date，空值/异常返回 null（到期日允许为空）。 */
    private static java.sql.Date sqlDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Date d) {
            return d;
        }
        if (v instanceof Timestamp t) {
            return java.sql.Date.valueOf(t.toLocalDateTime().toLocalDate());
        }
        String s = TmsUtil.str(v);
        if (s.isEmpty()) {
            return null;
        }
        try {
            return java.sql.Date.valueOf(s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
