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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 预付核销单（FX，PRD-36 M2）。
 *
 * <p>核销单审核（方案 §7，一个事务）：核销单 APPROVED → 逐行写 fin_reconcile_record
 * （business_type=PREPAY_WRITE_OFF，receipt_no=FX 单号，核销唯一真值）→ 更新 fin_ap
 * 三金额/状态 → 供应商账户写 1 行 PREPAY_WRITE_OFF + 每行 AP_SETTLE_PREPAY 流水
 * （预付↓应付↓，不碰资金）→ 总账事件 PREPAY_WRITE_OFF（借2202/贷1123）。
 * 反审核对称：删真值记录、回退 fin_ap、红字流水冲回、单据回 PENDING。
 *
 * <p>应付结算/对账单结算使用预付时由 FinanceController 调
 * {@link #createAutoFromSettle} 自动建单并审核，业务来源 AP_SETTLE/STATEMENT_SETTLE；
 * 对应付款单反审核时级联 {@link #cancelByCascade}，自动单置 CANCELLED（不回 PENDING，
 * 避免被当成手工单再次审核）。
 *
 * <p>结构镜像 PRD-35 预收核销单（AdvanceWriteoffService）：客户→供应商、fin_ar→fin_ap、
 * XH→FX、AR_SETTLE→AP_SETTLE；供应商侧无发货单收款状态回写。
 */
@Service
public class PrepayWriteoffService {

    public static final String MODULE = "fin.prepay_writeoff";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_AP_SETTLE = "AP_SETTLE";
    public static final String SOURCE_STATEMENT_SETTLE = "STATEMENT_SETTLE";

    /** 核销真值记录业务类型（fin_reconcile_record.business_type）。 */
    public static final String RECONCILE_TYPE = "PREPAY_WRITE_OFF";

    private final JdbcTemplate jdbc;
    private final BillNoGenerator billNoGen;
    private final SupplierAccountService accounts;
    private final GlHookService glHooks;
    private final BizDayCloseGuard dayCloseGuard;
    private final OperationLogService opLog;

    public PrepayWriteoffService(JdbcTemplate jdbc, BillNoGenerator billNoGen,
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

    /** FX 单分页。body: {pageNo,pageSize,writeoffNo,supplier,status,businessSource,dateFrom,dateTo}。 */
    public Map<String, Object> page(Map<String, Object> req) {
        int pageNo = Math.max(1, TmsUtil.toInt(req.get("pageNo")));
        int pageSizeRaw = TmsUtil.toInt(req.get("pageSize"));
        int pageSize = pageSizeRaw <= 0 ? 20 : Math.min(200, pageSizeRaw);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String no = TmsUtil.str(req.get("writeoffNo"));
        if (!no.isEmpty()) {
            where.append(" AND writeoff_no LIKE ?");
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
        String source = TmsUtil.str(req.get("businessSource"));
        if (!source.isEmpty()) {
            where.append(" AND business_source=?");
            args.add(source);
        }
        String dateFrom = TmsUtil.str(req.get("dateFrom"));
        if (!dateFrom.isEmpty()) {
            where.append(" AND writeoff_date>=?");
            args.add(java.sql.Date.valueOf(dateFrom));
        }
        String dateTo = TmsUtil.str(req.get("dateTo"));
        if (!dateTo.isEmpty()) {
            where.append(" AND writeoff_date<=?");
            args.add(java.sql.Date.valueOf(dateTo));
        }

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_prepay_writeoff" + where, Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT writeoff_id, writeoff_no, supplier_code, supplier_name, handler, writeoff_date, "
                        + "total_amount, status, business_source, source_bill_no, remark, "
                        + "creator_name, create_time, auditor_name, audit_time "
                        + "FROM fin_prepay_writeoff" + where
                        + " ORDER BY writeoff_date DESC, create_time DESC, writeoff_no DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        for (Map<String, Object> r : records) {
            r.put("statusText", statusText(TmsUtil.str(r.get("status"))));
            r.put("businessSourceText", sourceText(TmsUtil.str(r.get("businessSource"))));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("pageNo", pageNo);
        result.put("pageSize", pageSize);
        result.put("total", total);
        return result;
    }

    /** 详情：主单 + 明细行。入参 writeoffId 或 writeoffNo。 */
    public Map<String, Object> detail(Map<String, Object> req) {
        Map<String, Object> head = loadHead(TmsUtil.str(req.get("writeoffId")), TmsUtil.str(req.get("writeoffNo")));
        if (head == null) {
            throw new IllegalArgumentException("预付核销单不存在");
        }
        head.put("statusText", statusText(TmsUtil.str(head.get("status"))));
        head.put("businessSourceText", sourceText(TmsUtil.str(head.get("businessSource"))));
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbc,
                "SELECT id, writeoff_id, ap_no, source_bill, bill_date, due_date, ap_amount, "
                        + "unsettled_before, writeoff_amount, settle_status_after, remark, sort_order "
                        + "FROM fin_prepay_writeoff_detail WHERE writeoff_id=? ORDER BY sort_order, id",
                TmsUtil.str(head.get("writeoffId")));
        Map<String, Object> result = new LinkedHashMap<>(head);
        result.put("details", details);
        return result;
    }

    // ==================== 建/改/删（PENDING） ====================

    /** 手工建单（PENDING）。body: {supplierCode,supplierName,handler,writeoffDate,remark,details:[{apNo,writeoffAmount,remark}]}。 */
    @Transactional
    public Map<String, Object> create(Map<String, Object> req, String operator) {
        ParsedBill bill = parseBill(req);
        // 建单时即做余额与行校验，避免制了单审核才发现预付不够（审核时仍会再校验一次真值）
        BigDecimal bal = accounts.getPrepayBalance(bill.supplierCode);
        if (bill.total.compareTo(bal) > 0) {
            throw new IllegalArgumentException("预付余额不足，当前预付余额 " + bal + " 元，核销合计 " + bill.total + " 元");
        }
        String writeoffId = newId("FX");
        String writeoffNo = billNoGen.nextNo("FX", "fin_prepay_writeoff", "writeoff_no");
        LocalDate date = bill.writeoffDate;
        jdbc.update("INSERT INTO fin_prepay_writeoff(writeoff_id,writeoff_no,supplier_code,supplier_name,"
                        + "handler,writeoff_date,total_amount,status,business_source,source_bill_no,remark,"
                        + "creator_name,create_time) VALUES(?,?,?,?,?,?,?,'PENDING','MANUAL',NULL,?,?,CURRENT_TIMESTAMP)",
                writeoffId, writeoffNo, bill.supplierCode, bill.supplierName, bill.handler,
                java.sql.Date.valueOf(date), bill.total, bill.remark, operator);
        insertDetails(writeoffId, bill.details, date);
        opLog.log(MODULE, "CREATE", writeoffNo, "新建预付核销单 " + writeoffNo
                + "，供应商 " + bill.supplierName + "，核销合计 " + bill.total + " 元");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("writeoffId", writeoffId);
        result.put("writeoffNo", writeoffNo);
        return result;
    }

    /** 改单（仅 PENDING）。 */
    @Transactional
    public Map<String, Object> update(Map<String, Object> req, String operator) {
        String writeoffId = TmsUtil.str(req.get("writeoffId"));
        Map<String, Object> head = requireHead(writeoffId);
        requirePending(head);
        ParsedBill bill = parseBill(req);
        BigDecimal bal = accounts.getPrepayBalance(bill.supplierCode);
        if (bill.total.compareTo(bal) > 0) {
            throw new IllegalArgumentException("预付余额不足，当前预付余额 " + bal + " 元，核销合计 " + bill.total + " 元");
        }
        String writeoffNo = TmsUtil.str(head.get("writeoffNo"));
        jdbc.update("UPDATE fin_prepay_writeoff SET supplier_code=?,supplier_name=?,handler=?,"
                        + "writeoff_date=?,total_amount=?,remark=? WHERE writeoff_id=?",
                bill.supplierCode, bill.supplierName, bill.handler,
                java.sql.Date.valueOf(bill.writeoffDate), bill.total, bill.remark, writeoffId);
        jdbc.update("DELETE FROM fin_prepay_writeoff_detail WHERE writeoff_id=?", writeoffId);
        insertDetails(writeoffId, bill.details, bill.writeoffDate);
        opLog.log(MODULE, "UPDATE", writeoffNo, "修改预付核销单 " + writeoffNo
                + "，核销合计 " + bill.total + " 元，操作人 " + operator);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("writeoffId", writeoffId);
        result.put("writeoffNo", writeoffNo);
        return result;
    }

    /** 删单（仅 PENDING，连带明细）。 */
    @Transactional
    public void delete(String writeoffId, String operator) {
        Map<String, Object> head = requireHead(writeoffId);
        requirePending(head);
        String writeoffNo = TmsUtil.str(head.get("writeoffNo"));
        jdbc.update("DELETE FROM fin_prepay_writeoff_detail WHERE writeoff_id=?", writeoffId);
        jdbc.update("DELETE FROM fin_prepay_writeoff WHERE writeoff_id=?", writeoffId);
        opLog.log(MODULE, "DELETE", writeoffNo, "删除预付核销单 " + writeoffNo + "，操作人 " + operator);
    }

    // ==================== 审核 / 反审核 ====================

    /** 手工审核。 */
    @Transactional
    public void audit(String writeoffId, String operator) {
        Map<String, Object> head = requireHead(writeoffId);
        requirePending(head);
        doAudit(head, operator);
    }

    /** 手工反审核：单据回 PENDING，可改可删。结算自动单禁止手工反审核（须走付款单级联）。 */
    @Transactional
    public void cancelAudit(String writeoffId, String operator) {
        Map<String, Object> head = requireHead(writeoffId);
        String source = TmsUtil.str(head.get("businessSource"));
        if (!SOURCE_MANUAL.equals(source)) {
            throw new IllegalArgumentException("结算自动生成的核销单 " + TmsUtil.str(head.get("writeoffNo"))
                    + " 不能单独反审核，请对对应付款单反审核以级联冲回");
        }
        doCancel(head, false, operator);
    }

    /**
     * 结算单反审核级联：冲回核销但自动单置 CANCELLED（方案 §7.3 级联反审核）。
     * 供 FinanceController 在付款单反审核事务内调用。
     */
    @Transactional
    public void cancelByCascade(String writeoffNo, String operator) {
        List<Map<String, Object>> heads = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_prepay_writeoff WHERE writeoff_no=?", writeoffNo);
        if (heads.isEmpty()) {
            throw new IllegalArgumentException("预付核销单不存在：" + writeoffNo);
        }
        Map<String, Object> head = heads.get(0);
        if (!STATUS_APPROVED.equals(TmsUtil.str(head.get("status")))) {
            // 已被级联作废过（重复触发）：幂等直接返回
            return;
        }
        doCancel(head, true, operator);
    }

    /**
     * 查已审核自动核销单（按业务来源+来源单号），供付款单反审核级联反核销：
     * AP_SETTLE 按 FK 单号查；STATEMENT_SETTLE 按对账单号查（可多个）。
     * 仅返回 APPROVED 单（已级联作废的不重复处理），按制单时间倒序。
     */
    public List<String> findAutoWriteoffNos(String businessSource, java.util.Collection<String> sourceBillNos) {
        if (sourceBillNos == null || sourceBillNos.isEmpty()) {
            return List.of();
        }
        List<String> vals = sourceBillNos.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim).distinct().toList();
        if (vals.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(vals.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(businessSource);
        args.addAll(vals);
        return jdbc.queryForList("SELECT writeoff_no FROM fin_prepay_writeoff "
                + "WHERE status='APPROVED' AND business_source=? AND source_bill_no IN ("
                + placeholders + ") ORDER BY create_time DESC", String.class, args.toArray());
    }

    /**
     * 应付结算/对账单结算使用预付：自动建 FX 单并立即审核。
     * 调用方事务内；行金额由结算侧按 FIFO（预付优先冲最早到期行）拆好后传入。
     *
     * @param lines 每行 {apNo, amount}，amount 必须 >0
     * @return FX 单号
     */
    @Transactional
    public String createAutoFromSettle(String supplierCode, String supplierName, LocalDate writeoffDate,
                                       String handler, String businessSource, String sourceBillNo,
                                       String remark, List<Map<String, Object>> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        if (!SOURCE_AP_SETTLE.equals(businessSource) && !SOURCE_STATEMENT_SETTLE.equals(businessSource)) {
            throw new IllegalArgumentException("预付核销单自动来源不合法：" + businessSource);
        }
        // 组装成与手工建单同构的明细入参，复用 parseBill 的真值校验
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("supplierCode", supplierCode);
        req.put("supplierName", supplierName);
        req.put("handler", handler);
        req.put("writeoffDate", writeoffDate.toString());
        req.put("remark", remark);
        List<Map<String, Object>> detailReq = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            BigDecimal amt = nz(TmsUtil.toBd(l.get("amount")));
            if (amt.signum() <= 0) {
                continue;
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("apNo", TmsUtil.str(l.get("apNo")));
            d.put("writeoffAmount", amt);
            detailReq.add(d);
        }
        if (detailReq.isEmpty()) {
            return "";
        }
        req.put("details", detailReq);
        ParsedBill bill = parseBill(req);

        String writeoffId = newId("FX");
        String writeoffNo = billNoGen.nextNo("FX", "fin_prepay_writeoff", "writeoff_no");
        jdbc.update("INSERT INTO fin_prepay_writeoff(writeoff_id,writeoff_no,supplier_code,supplier_name,"
                        + "handler,writeoff_date,total_amount,status,business_source,source_bill_no,remark,"
                        + "creator_name,create_time) VALUES(?,?,?,?,?,?,?,'PENDING',?,?,?,?,CURRENT_TIMESTAMP)",
                writeoffId, writeoffNo, bill.supplierCode, bill.supplierName, bill.handler,
                java.sql.Date.valueOf(bill.writeoffDate), bill.total, businessSource,
                sourceBillNo == null || sourceBillNo.isEmpty() ? null : sourceBillNo,
                bill.remark, TmsUtil.currentUser());
        insertDetails(writeoffId, bill.details, bill.writeoffDate);

        Map<String, Object> head = new LinkedHashMap<>();
        head.put("writeoffId", writeoffId);
        head.put("writeoffNo", writeoffNo);
        doAudit(head, TmsUtil.currentUser());
        opLog.log(MODULE, "AUTO_AUDIT", writeoffNo,
                ("STATEMENT_SETTLE".equals(businessSource) ? "对账单结算" : "应付结算")
                        + "使用预付，自动生成并审核预付核销单 " + writeoffNo + "，核销 " + bill.total + " 元");
        return writeoffNo;
    }

    // ==================== 审核/反审核内核 ====================

    /**
     * 审核内核（方案 §7 全流程，一个事务）。head 至少含 writeoffId；审核时按真值重算明细快照。
     */
    private void doAudit(Map<String, Object> head, String operator) {
        String writeoffId = TmsUtil.str(head.get("writeoffId"));
        head = requireHead(writeoffId);
        String writeoffNo = TmsUtil.str(head.get("writeoffNo"));
        LocalDate writeoffDate = head.get("writeoffDate") instanceof java.sql.Date d
                ? d.toLocalDate() : LocalDate.now();
        dayCloseGuard.assertWritable(writeoffDate, "预付核销单", writeoffNo);

        String supplierCode = TmsUtil.str(head.get("supplierCode"));
        String supplierName = TmsUtil.str(head.get("supplierName"));
        accounts.ensureAccount(supplierCode);
        accounts.lockAccount(supplierCode);

        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_prepay_writeoff_detail WHERE writeoff_id=? ORDER BY sort_order, id",
                writeoffId);
        if (details.isEmpty()) {
            throw new IllegalArgumentException("核销单 " + writeoffNo + " 没有核销明细，不能审核");
        }

        BigDecimal total = BigDecimal.ZERO;
        List<SupplierAccountService.WriteoffLine> flowLines = new ArrayList<>();
        for (Map<String, Object> d : details) {
            String apNo = TmsUtil.str(d.get("apNo"));
            BigDecimal writeoffAmount = nz(TmsUtil.toBd(d.get("writeoffAmount")));
            if (writeoffAmount.signum() <= 0) {
                continue;
            }
            // 行锁 + 真值重检（制单后应付可能已被别处结算）
            List<Map<String, Object>> aps = TmsUtil.queryCamel(jdbc,
                    "SELECT * FROM fin_ap WHERE ap_no=? FOR UPDATE", apNo);
            if (aps.isEmpty()) {
                throw new IllegalArgumentException("应付单 " + apNo + " 不存在，不能核销");
            }
            Map<String, Object> ap = aps.get(0);
            assertApBelongsToSupplier(ap, supplierCode, supplierName, apNo);
            BigDecimal unpaid = nz(TmsUtil.toBd(ap.get("unpaidAmount")));
            if (writeoffAmount.compareTo(unpaid) > 0) {
                throw new IllegalArgumentException("应付单 " + apNo + " 未结金额仅 " + unpaid
                        + " 元，本次核销 " + writeoffAmount + " 元超出未结金额");
            }
            String sourceBill = TmsUtil.str(ap.get("sourceBill"));

            // 1) 真值：核销记录（receipt_no=FX 单号，business_type=PREPAY_WRITE_OFF）
            String recordId = "RR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbc.update("INSERT INTO fin_reconcile_record(record_id, receipt_no, receipt_date, "
                            + "business_no, business_type, business_date, "
                            + "counterparty_type, counterparty_code, counterparty_name, "
                            + "reconcile_amount, receipt_remark, business_remark, ar_no, source_bill) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    recordId, writeoffNo, java.sql.Date.valueOf(writeoffDate),
                    apNo, RECONCILE_TYPE,
                    ap.get("dueDate") == null ? null : java.sql.Date.valueOf(TmsUtil.str(ap.get("dueDate")).substring(0, 10)),
                    "SUPPLIER", supplierCode, supplierName, writeoffAmount,
                    "预付核销 " + writeoffNo, "", apNo, sourceBill);

            // 2) 真值：fin_ap 三金额/状态（全额 VERIFIED，部分保持 UNVERIFIED 两档兼容）
            BigDecimal paidAfter = nz(TmsUtil.toBd(ap.get("paidAmount"))).add(writeoffAmount);
            BigDecimal unpaidAfter = nz(TmsUtil.toBd(ap.get("apAmount"))).subtract(paidAfter);
            String apStatus = unpaidAfter.signum() <= 0 ? "VERIFIED" : "UNVERIFIED";
            jdbc.update("UPDATE fin_ap SET paid_amount=?, unpaid_amount=?, status=? WHERE ap_no=?",
                    paidAfter, unpaidAfter, apStatus, apNo);
            String settleStatusAfter = unpaidAfter.signum() <= 0
                    ? SupplierAccountConst.SETTLE_DONE : SupplierAccountConst.SETTLE_PART;

            // 明细快照回写（核销前未结额/核销后状态，供列表与审计）
            jdbc.update("UPDATE fin_prepay_writeoff_detail SET unsettled_before=?, settle_status_after=?, "
                            + "ap_amount=?, source_bill=?, due_date=? WHERE id=?",
                    unpaid, settleStatusAfter, nz(TmsUtil.toBd(ap.get("apAmount"))),
                    sourceBill, ap.get("dueDate") == null ? null : java.sql.Date.valueOf(
                            TmsUtil.str(ap.get("dueDate")).substring(0, 10)),
                    TmsUtil.str(d.get("id")));

            flowLines.add(new SupplierAccountService.WriteoffLine(
                    apNo, sourceBill, writeoffAmount, paidAfter, settleStatusAfter, recordId));
            total = total.add(writeoffAmount);
        }
        if (total.signum() <= 0) {
            throw new IllegalArgumentException("核销金额合计必须大于 0");
        }
        // 合计以明细重算结果为准（制单后未结额可能变化）
        jdbc.update("UPDATE fin_prepay_writeoff SET total_amount=? WHERE writeoff_id=?", total, writeoffId);

        // 3) 供应商账户流水（内含预付余额硬校验，不足整单回滚；同时回写形成行结算标志）
        accounts.postPrepayWriteoff(writeoffNo, writeoffDate, supplierCode, supplierName,
                total, flowLines, TmsUtil.str(head.get("remark")));

        // 4) 单据 APPROVED + 总账事件（借2202/贷1123，事件 AFTER_COMMIT 落池）
        jdbc.update("UPDATE fin_prepay_writeoff SET status='APPROVED', auditor_name=?, audit_time=? "
                + "WHERE writeoff_id=?", operator, Timestamp.valueOf(LocalDateTime.now()), writeoffId);
        glHooks.onPrepayWriteoffAudited(writeoffNo);
        opLog.log(MODULE, "AUDIT", writeoffNo, "审核预付核销单 " + writeoffNo
                + "，供应商 " + supplierName + "，核销 " + total + " 元，明细 " + flowLines.size() + " 行");
    }

    /**
     * 反审核内核：删核销真值记录、回退 fin_ap、红字冲回两侧流水、复位形成行标志；
     * 手工单反 PENDING，结算级联自动单置 CANCELLED。
     */
    private void doCancel(Map<String, Object> head, boolean cascade, String operator) {
        String writeoffId = TmsUtil.str(head.get("writeoffId"));
        String writeoffNo = TmsUtil.str(head.get("writeoffNo"));
        if (!STATUS_APPROVED.equals(TmsUtil.str(head.get("status")))) {
            throw new IllegalArgumentException("仅已审核单据可取消审核");
        }
        LocalDate writeoffDate = head.get("writeoffDate") instanceof java.sql.Date d
                ? d.toLocalDate() : LocalDate.now();
        dayCloseGuard.assertWritable(writeoffDate, "预付核销单", writeoffNo);

        String supplierCode = TmsUtil.str(head.get("supplierCode"));
        String supplierName = TmsUtil.str(head.get("supplierName"));
        accounts.ensureAccount(supplierCode);
        accounts.lockAccount(supplierCode);

        // 1) 核销真值记录驱动回退 fin_ap，随后删除记录
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_reconcile_record WHERE receipt_no=? AND business_type=?",
                writeoffNo, RECONCILE_TYPE);
        for (Map<String, Object> rec : records) {
            String apNo = TmsUtil.str(rec.get("arNo"));
            if (apNo.isEmpty()) {
                apNo = TmsUtil.str(rec.get("businessNo"));
            }
            BigDecimal amt = nz(TmsUtil.toBd(rec.get("reconcileAmount")));
            List<Map<String, Object>> aps = TmsUtil.queryCamel(jdbc,
                    "SELECT ap_amount, paid_amount, unpaid_amount, source_bill "
                            + "FROM fin_ap WHERE ap_no=? FOR UPDATE", apNo);
            if (aps.isEmpty()) {
                // AP 已被撤销等异常场景：记录照删，流水由数据修复兜底
                continue;
            }
            Map<String, Object> ap = aps.get(0);
            BigDecimal paidAfter = nz(TmsUtil.toBd(ap.get("paidAmount"))).subtract(amt);
            BigDecimal unpaidAfter = nz(TmsUtil.toBd(ap.get("apAmount"))).subtract(paidAfter);
            String apStatus = unpaidAfter.signum() <= 0 ? "VERIFIED" : "UNVERIFIED";
            jdbc.update("UPDATE fin_ap SET paid_amount=?, unpaid_amount=?, status=? WHERE ap_no=?",
                    paidAfter, unpaidAfter, apStatus, apNo);
        }
        jdbc.update("DELETE FROM fin_reconcile_record WHERE receipt_no=? AND business_type=?",
                writeoffNo, RECONCILE_TYPE);

        // 2) 明细行驱动红字流水（原正向行置 REVERSED，预付加回、应付加回，形成行标志按真值复位）
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbc,
                "SELECT ap_no, writeoff_amount FROM fin_prepay_writeoff_detail WHERE writeoff_id=?",
                writeoffId);
        List<SupplierAccountService.WriteoffLine> flowLines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            BigDecimal amt = nz(TmsUtil.toBd(d.get("writeoffAmount")));
            if (amt.signum() <= 0) {
                continue;
            }
            String apNo = TmsUtil.str(d.get("apNo"));
            List<Map<String, Object>> aps = TmsUtil.queryCamel(jdbc,
                    "SELECT source_bill FROM fin_ap WHERE ap_no=?", apNo);
            String sourceBill = aps.isEmpty() ? "" : TmsUtil.str(aps.get(0).get("sourceBill"));
            flowLines.add(new SupplierAccountService.WriteoffLine(
                    apNo, sourceBill, amt, null, null, null));
            total = total.add(amt);
        }
        accounts.reversePrepayWriteoff(writeoffNo, writeoffDate, supplierCode, supplierName,
                total, flowLines);

        // 3) 明细核销后状态复位
        jdbc.update("UPDATE fin_prepay_writeoff_detail SET settle_status_after=NULL WHERE writeoff_id=?",
                writeoffId);

        // 4) 单据状态 + 反向总账事件
        if (cascade) {
            jdbc.update("UPDATE fin_prepay_writeoff SET status='CANCELLED' WHERE writeoff_id=?", writeoffId);
        } else {
            jdbc.update("UPDATE fin_prepay_writeoff SET status='PENDING', auditor_name=NULL, audit_time=NULL "
                    + "WHERE writeoff_id=?", writeoffId);
        }
        glHooks.onPrepayWriteoffUnaudited(writeoffNo);
        opLog.log(MODULE, cascade ? "CASCADE_CANCEL" : "UN_AUDIT", writeoffNo,
                (cascade ? "结算单反审核级联作废" : "反审核") + "预付核销单 " + writeoffNo
                        + "，冲回 " + total + " 元，操作人 " + operator);
    }

    // ==================== 建单解析 ====================

    /** 解析后的建/改单数据。 */
    private static final class ParsedBill {
        private String supplierCode;
        private String supplierName;
        private String handler;
        private LocalDate writeoffDate;
        private String remark;
        private BigDecimal total;
        private List<Map<String, Object>> details;
    }

    /** 解析建单入参并做行级真值校验，明细 Map 补 apAmount/unsettledBefore/dueDate/sourceBill 快照。 */
    private ParsedBill parseBill(Map<String, Object> req) {
        ParsedBill bill = new ParsedBill();
        bill.supplierCode = TmsUtil.str(req.get("supplierCode"));
        bill.supplierName = TmsUtil.str(req.get("supplierName"));
        if (bill.supplierCode.isEmpty()) {
            throw new IllegalArgumentException("请选择供应商");
        }
        if (bill.supplierName.isEmpty()) {
            bill.supplierName = bill.supplierCode;
        }
        bill.handler = TmsUtil.str(req.get("handler"));
        if (bill.handler.isEmpty()) {
            throw new IllegalArgumentException("请填写经手人");
        }
        String dateStr = TmsUtil.str(req.get("writeoffDate"));
        bill.writeoffDate = dateStr.isEmpty() ? LocalDate.now() : LocalDate.parse(dateStr.substring(0, 10));
        bill.remark = TmsUtil.str(req.get("remark"));

        Object raw = req.get("details");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一行未结算应付并填写核销金额");
        }
        bill.details = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        java.util.Set<String> seenAp = new java.util.LinkedHashSet<>();
        int sort = 1;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String apNo = TmsUtil.str(m.get("apNo"));
            BigDecimal amount = nz(TmsUtil.toBd(m.get("writeoffAmount")));
            if (apNo.isEmpty() || amount.signum() <= 0) {
                continue;
            }
            if (!seenAp.add(apNo)) {
                throw new IllegalArgumentException("应付单 " + apNo + " 在核销明细中重复，请合并为一行");
            }
            List<Map<String, Object>> aps = TmsUtil.queryCamel(jdbc,
                    "SELECT * FROM fin_ap WHERE ap_no=?", apNo);
            if (aps.isEmpty()) {
                throw new IllegalArgumentException("应付单 " + apNo + " 不存在");
            }
            Map<String, Object> ap = aps.get(0);
            assertApBelongsToSupplier(ap, bill.supplierCode, bill.supplierName, apNo);
            BigDecimal unpaid = nz(TmsUtil.toBd(ap.get("unpaidAmount")));
            if (unpaid.signum() <= 0) {
                throw new IllegalArgumentException("应付单 " + apNo + " 已全额结算，不能再核销");
            }
            if (amount.compareTo(unpaid) > 0) {
                throw new IllegalArgumentException("应付单 " + apNo + " 未结金额仅 " + unpaid
                        + " 元，本次核销 " + amount + " 元超出未结金额");
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("apNo", apNo);
            d.put("sourceBill", TmsUtil.str(ap.get("sourceBill")));
            d.put("dueDate", ap.get("dueDate"));
            d.put("apAmount", nz(TmsUtil.toBd(ap.get("apAmount"))));
            d.put("unsettledBefore", unpaid);
            d.put("writeoffAmount", amount);
            d.put("remark", TmsUtil.str(m.get("remark")));
            d.put("sortOrder", sort++);
            bill.details.add(d);
            total = total.add(amount);
        }
        if (bill.details.isEmpty()) {
            throw new IllegalArgumentException("请至少填写一行大于 0 的核销金额");
        }
        bill.total = total;
        return bill;
    }

    /** 校验 AP 行归属同一供应商（fin_ap 只存名称，优先按解析出的供应商编码比对，再按名称兜底）。 */
    private void assertApBelongsToSupplier(Map<String, Object> ap, String supplierCode,
                                           String supplierName, String apNo) {
        String resolved = accounts.resolveApSupplierCode(ap);
        if (supplierCode.equals(resolved)) {
            return;
        }
        if (resolved.equals(TmsUtil.str(ap.get("supplier")))
                && supplierName.equals(TmsUtil.str(ap.get("supplier")))) {
            // 解析不到编码、只能用名称兜底的历史脏数据：同名即认可
            return;
        }
        throw new IllegalArgumentException("应付单 " + apNo + " 不属于供应商 "
                + (supplierName.isEmpty() ? supplierCode : supplierName));
    }

    /** 落明细行（bill_date 快照核销日期；due_date 取应付到期日）。 */
    private void insertDetails(String writeoffId, List<Map<String, Object>> details,
                               LocalDate writeoffDate) {
        int sortBase = 0;
        for (Map<String, Object> d : details) {
            int sort = TmsUtil.toInt(d.get("sortOrder"));
            if (sort == 0) {
                sort = ++sortBase;
            }
            Object dueRaw = d.get("dueDate");
            java.sql.Date due = null;
            if (dueRaw instanceof java.sql.Date dd) {
                due = dd;
            } else if (dueRaw instanceof Timestamp ts) {
                due = java.sql.Date.valueOf(ts.toLocalDateTime().toLocalDate());
            } else {
                String s = TmsUtil.str(dueRaw);
                if (!s.isEmpty()) {
                    due = java.sql.Date.valueOf(s.substring(0, 10));
                }
            }
            // 12 列：settle_status_after 在建/改单时固定 NULL（审核时回写），NULL 字面量必须
            // 落在第 10 个值位；错一位会让 writeoff_amount 写成 NULL、金额串入状态列
            jdbc.update("INSERT INTO fin_prepay_writeoff_detail(id,writeoff_id,ap_no,source_bill,"
                            + "bill_date,due_date,ap_amount,unsettled_before,writeoff_amount,"
                            + "settle_status_after,remark,sort_order) VALUES(?,?,?,?,?,?,?,?,?,NULL,?,?)",
                    newId("FXD"), writeoffId, TmsUtil.str(d.get("apNo")),
                    TmsUtil.str(d.get("sourceBill")),
                    java.sql.Date.valueOf(writeoffDate), due,
                    nz(TmsUtil.toBd(d.get("apAmount"))),
                    nz(TmsUtil.toBd(d.get("unsettledBefore"))),
                    nz(TmsUtil.toBd(d.get("writeoffAmount"))),
                    TmsUtil.str(d.get("remark")),
                    sort);
        }
    }

    // ==================== 通用 ====================

    private Map<String, Object> loadHead(String writeoffId, String writeoffNo) {
        if (!writeoffId.isEmpty()) {
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                    "SELECT * FROM fin_prepay_writeoff WHERE writeoff_id=?", writeoffId);
            return rows.isEmpty() ? null : rows.get(0);
        }
        if (!writeoffNo.isEmpty()) {
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                    "SELECT * FROM fin_prepay_writeoff WHERE writeoff_no=?", writeoffNo);
            return rows.isEmpty() ? null : rows.get(0);
        }
        return null;
    }

    private Map<String, Object> requireHead(String writeoffId) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_prepay_writeoff WHERE writeoff_id=?", writeoffId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("预付核销单不存在或已删除");
        }
        return rows.get(0);
    }

    private void requirePending(Map<String, Object> head) {
        if (!STATUS_PENDING.equals(TmsUtil.str(head.get("status")))) {
            throw new IllegalArgumentException("仅待审核状态的单据可执行该操作");
        }
    }

    private static String statusText(String status) {
        return switch (status) {
            case STATUS_PENDING -> "待审核";
            case STATUS_APPROVED -> "已审核";
            case STATUS_CANCELLED -> "已作废";
            default -> status;
        };
    }

    private static String sourceText(String source) {
        return switch (source) {
            case SOURCE_AP_SETTLE -> "应付结算";
            case SOURCE_STATEMENT_SETTLE -> "对账单结算";
            default -> "手工录入";
        };
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
