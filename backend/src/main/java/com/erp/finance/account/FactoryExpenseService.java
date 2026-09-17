package com.erp.finance.account;

import com.erp.common.util.BillNoGenerator;
import com.erp.finance.dayclose.BizDayCloseGuard;
import com.erp.finance.gl.GlHookService;
import com.erp.init.InitSupport;
import com.erp.system.OperationLogService;
import com.erp.system.SysParamService;
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
 * 厂家费用单 JF（PRD-36 M3，方案 §6.1~§6.5、§7.3）。
 *
 * <p>业务链：代垫费用先由客户费用单 FE（counterparty=CUSTOMER/direction=OUT）走完，
 * JF 审核立「其他应收款—厂家」债权：EXPENSE 账户形成流水 FACTORY_EXP_FORM（每单一行汇总），
 * 后续由兑现单 DX 核销。红字 JF（FACTORY_EXP_RED，负金额）只增不删冲减原单未兑现额。
 *
 * <p>FE:JF = N:1：一张 FE 任一行被任一 JF 关联（行 id 或只录单号），整单锁定不可再选；
 * 审核时回写 fin_expense_bill.factory_expense_no，反审核清空。
 *
 * <p>business_source=BACKOFFICE 发 GL 事件；OPENING_BACKFILL（P0190 开关控制）只立费用账户、
 * 不发任何 GL 事件，避免与 1221 期初余额重复记账。
 */
@Service
public class FactoryExpenseService {

    public static final String MODULE = "fin.factory_expense";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";

    public static final String CLAIM_ADVANCE = "ADVANCE";
    public static final String CLAIM_OTHER = "OTHER";

    public static final String SOURCE_CUSTOMER_EXPENSE = "CUSTOMER_EXPENSE";
    public static final String SOURCE_IMPORT = "IMPORT";
    public static final String SOURCE_MANUAL = "MANUAL";

    public static final String BIZ_BACKOFFICE = "BACKOFFICE";
    public static final String BIZ_OPENING_BACKFILL = "OPENING_BACKFILL";

    /** 历史补录入口开关（V130 P0190）。 */
    public static final String PARAM_OPENING_BACKFILL = "fin.fexp.opening-backfill.enabled";

    /** 导入模板/失败文件列定义（{中文表头, 驼峰键}，顺序即列序），Controller 模板下载共用。 */
    public static final String[][] IMPORT_FIELDS = {
            {"供应商编码", "supplierCode"},
            {"费用类型", "expenseTypeName"},
            {"费用性质", "claimTypeText"},
            {"垫付客户编码", "customerCode"},
            {"关联客户费用单号", "customerExpenseNo"},
            {"费用日期", "expenseDate"},
            {"金额", "amount"},
            {"厂家协议号/票据号", "externalVoucherNo"},
            {"贷方科目", "glCreditSubject"},
            {"备注", "remark"},
    };

    private final JdbcTemplate jdbc;
    private final BillNoGenerator billNoGen;
    private final SupplierAccountService accounts;
    private final GlHookService glHooks;
    private final BizDayCloseGuard dayCloseGuard;
    private final SysParamService sysParam;
    private final InitSupport initSupport;
    private final OperationLogService opLog;

    public FactoryExpenseService(JdbcTemplate jdbc, BillNoGenerator billNoGen,
                                 SupplierAccountService accounts, GlHookService glHooks,
                                 BizDayCloseGuard dayCloseGuard, SysParamService sysParam,
                                 InitSupport initSupport, OperationLogService opLog) {
        this.jdbc = jdbc;
        this.billNoGen = billNoGen;
        this.accounts = accounts;
        this.glHooks = glHooks;
        this.dayCloseGuard = dayCloseGuard;
        this.sysParam = sysParam;
        this.initSupport = initSupport;
        this.opLog = opLog;
    }

    // ==================== 列表 / 详情 ====================

    /** P0190 上线历史补录入口是否开启（前端控制补录勾选框显隐，保存/审核服务端仍逐单校验）。 */
    public boolean isOpeningBackfillEnabled() {
        return "Y".equalsIgnoreCase(sysParam.get(PARAM_OPENING_BACKFILL, "N"));
    }

    /** JF 分页。body: {pageNo,pageSize,factoryExpenseNo,supplier,status,claimType,sourceMode,settleStatus,businessSource,isRed,dateFrom,dateTo}。 */
    public Map<String, Object> page(Map<String, Object> req) {
        int pageNo = Math.max(1, TmsUtil.toInt(req.get("pageNo")));
        int pageSizeRaw = TmsUtil.toInt(req.get("pageSize"));
        int pageSize = pageSizeRaw <= 0 ? 20 : Math.min(200, pageSizeRaw);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String no = TmsUtil.str(req.get("factoryExpenseNo"));
        if (!no.isEmpty()) {
            where.append(" AND factory_expense_no LIKE ?");
            args.add("%" + no + "%");
        }
        String supplier = TmsUtil.str(req.get("supplier"));
        if (!supplier.isEmpty()) {
            where.append(" AND (supplier_code LIKE ? OR supplier_name LIKE ?)");
            args.add("%" + supplier + "%");
            args.add("%" + supplier + "%");
        }
        for (String key : new String[]{"status", "claimType", "sourceMode", "settleStatus", "businessSource"}) {
            String col = switch (key) {
                case "claimType" -> "claim_type";
                case "sourceMode" -> "source_mode";
                case "settleStatus" -> "settle_status";
                case "businessSource" -> "business_source";
                default -> "status";
            };
            String v = TmsUtil.str(req.get(key));
            if (!v.isEmpty()) {
                where.append(" AND ").append(col).append("=?");
                args.add(v);
            }
        }
        String isRed = TmsUtil.str(req.get("isRed"));
        if ("Y".equals(isRed) || "N".equals(isRed)) {
            where.append(" AND is_red=?");
            args.add(isRed);
        }
        String dateFrom = TmsUtil.str(req.get("dateFrom"));
        if (!dateFrom.isEmpty()) {
            where.append(" AND expense_date>=?");
            args.add(java.sql.Date.valueOf(dateFrom.length() > 10 ? dateFrom.substring(0, 10) : dateFrom));
        }
        String dateTo = TmsUtil.str(req.get("dateTo"));
        if (!dateTo.isEmpty()) {
            where.append(" AND expense_date<=?");
            args.add(java.sql.Date.valueOf(dateTo.length() > 10 ? dateTo.substring(0, 10) : dateTo));
        }

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_factory_expense" + where, Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT factory_expense_id, factory_expense_no, supplier_code, supplier_name, expense_date, "
                        + "claim_type, source_mode, total_amount, settled_amount, unsettled_amount, settle_status, "
                        + "is_red, red_source_no, external_voucher_no, handler, department, "
                        + "total_tax_amount, total_excluding_tax_amount, remark, status, business_source, "
                        + "creator_name, create_time, auditor_name, audit_time "
                        + "FROM fin_factory_expense" + where
                        + " ORDER BY expense_date DESC, create_time DESC, factory_expense_no DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        for (Map<String, Object> r : records) {
            fillTexts(r);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("pageNo", pageNo);
        result.put("pageSize", pageSize);
        result.put("total", total);
        return result;
    }

    /** 详情：主单 + 明细行 + 兑现记录（DX 行，含 PENDING 计划数）。入参 factoryExpenseId 或 factoryExpenseNo。 */
    public Map<String, Object> detail(Map<String, Object> req) {
        Map<String, Object> head = loadHead(TmsUtil.str(req.get("factoryExpenseId")),
                TmsUtil.str(req.get("factoryExpenseNo")));
        if (head == null) {
            throw new IllegalArgumentException("厂家费用单不存在");
        }
        fillTexts(head);
        String jfId = TmsUtil.str(head.get("factoryExpenseId"));
        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbc,
                "SELECT detail_id, factory_expense_id, expense_type_code, expense_type_name, "
                        + "customer_code, customer_name, customer_expense_no, customer_expense_detail_id, "
                        + "gl_credit_subject, qty, price, amount, remark, sort_order "
                        + "FROM fin_factory_expense_detail WHERE factory_expense_id=? ORDER BY sort_order, detail_id",
                jfId);
        head.put("details", details);

        List<Map<String, Object>> settles = TmsUtil.queryCamel(jdbc,
                "SELECT s.settle_no, s.settle_date, s.settle_type, s.total_amount, s.status, "
                        + "d.settle_amount, s.remark, s.business_source "
                        + "FROM fin_factory_settle_detail d "
                        + "JOIN fin_factory_settle s ON s.settle_id=d.settle_id "
                        + "WHERE d.factory_expense_no=? ORDER BY s.settle_date DESC, s.create_time DESC",
                TmsUtil.str(head.get("factoryExpenseNo")));
        for (Map<String, Object> s : settles) {
            s.put("settleTypeText", settleTypeText(TmsUtil.str(s.get("settleType"))));
            s.put("statusText", "APPROVED".equals(TmsUtil.str(s.get("status"))) ? "已审核" : "待审核");
        }
        head.put("settles", settles);
        return head;
    }

    /**
     * 可选客户费用单行（行级粒度）：fin_expense_bill 中 counterparty_type='CUSTOMER'、
     * direction='OUT'、status='APPROVED' 且整单未被其他 JF 占用的全部明细行。
     * 编辑当前 JF 时传 currentJfNo，本单已占的行照常返回。
     */
    public List<Map<String, Object>> customerExpenseCandidates(Map<String, Object> req) {
        String currentJfNo = TmsUtil.str(req.get("currentJfNo"));
        StringBuilder sql = new StringBuilder(
                "SELECT b.expense_id, b.expense_no, b.expense_date, "
                        + "b.counterparty_code AS customer_code, b.counterparty_name AS customer_name, "
                        + "b.total_amount, b.remark AS bill_remark, "
                        + "d.detail_id, d.expense_type, d.amount AS detail_amount, d.remark AS detail_remark "
                        + "FROM fin_expense_bill b "
                        + "JOIN fin_expense_detail d ON d.expense_id=b.expense_id "
                        + "WHERE b.counterparty_type='CUSTOMER' AND b.direction='OUT' AND b.status='APPROVED' "
                        + "AND (b.factory_expense_no IS NULL OR b.factory_expense_no='' "
                        + (currentJfNo.isEmpty() ? "" : "OR b.factory_expense_no=? ")
                        + ") AND NOT EXISTS ("
                        + "SELECT 1 FROM fin_factory_expense_detail x "
                        + "JOIN fin_factory_expense xh ON xh.factory_expense_id=x.factory_expense_id "
                        + "WHERE x.customer_expense_no=b.expense_no AND xh.factory_expense_no<>? ) ");
        List<Object> args = new ArrayList<>();
        if (!currentJfNo.isEmpty()) {
            args.add(currentJfNo);
        }
        args.add(currentJfNo);
        String customer = TmsUtil.str(req.get("customer"));
        if (!customer.isEmpty()) {
            sql.append(" AND (b.counterparty_code LIKE ? OR b.counterparty_name LIKE ?)");
            args.add("%" + customer + "%");
            args.add("%" + customer + "%");
        }
        String keyword = TmsUtil.str(req.get("keyword"));
        if (!keyword.isEmpty()) {
            sql.append(" AND (b.expense_no LIKE ? OR d.expense_type LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        String dateFrom = TmsUtil.str(req.get("dateFrom"));
        if (!dateFrom.isEmpty()) {
            sql.append(" AND b.expense_date>=?");
            args.add(java.sql.Date.valueOf(dateFrom));
        }
        String dateTo = TmsUtil.str(req.get("dateTo"));
        if (!dateTo.isEmpty()) {
            sql.append(" AND b.expense_date<=?");
            args.add(java.sql.Date.valueOf(dateTo));
        }
        sql.append(" ORDER BY b.expense_date DESC, b.expense_no, d.sort_order LIMIT 200");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    // ==================== 建/改/删（PENDING） ====================

    /** 手工/代垫建单（PENDING）。 */
    @Transactional
    public Map<String, Object> create(Map<String, Object> req, String operator) {
        ParsedJf bill = parseBill(req, null, false);
        String jfId = newId("JF");
        String jfNo = billNoGen.nextNo(BillNoGenerator.BillType.FACTORY_EXPENSE,
                "fin_factory_expense", "factory_expense_no");
        insertHead(jfId, jfNo, bill, operator, false);
        insertDetails(jfId, bill.details);
        opLog.log(MODULE, "CREATE", jfNo, "新建厂家费用单 " + jfNo + "（" + claimTypeText(bill.claimType)
                + "），供应商 " + bill.supplierName + "，金额 " + bill.total.toPlainString() + " 元"
                + (BIZ_OPENING_BACKFILL.equals(bill.businessSource) ? "，上线历史补录单" : ""));
        return Map.of("factoryExpenseId", jfId, "factoryExpenseNo", jfNo);
    }

    /** 改单（仅 PENDING）。 */
    @Transactional
    public Map<String, Object> update(Map<String, Object> req, String operator) {
        String jfId = TmsUtil.str(req.get("factoryExpenseId"));
        Map<String, Object> head = requireHead(jfId);
        requirePending(head);
        boolean isRed = "Y".equals(TmsUtil.str(head.get("isRed")));
        // 先释放本原单全部 FE 头回指，parseBill 会按改后明细重新占用；
        // 否则改单删掉的 FE 关联会残留死回指，客户费用单永远选不到
        jdbc.update("UPDATE fin_expense_bill SET factory_expense_no=NULL "
                + "WHERE factory_expense_no=?", TmsUtil.str(head.get("factoryExpenseNo")));
        ParsedJf bill = parseBill(req, jfId, isRed);
        if (isRed) {
            // 红字属性不可通过改单变更
            bill.isRed = true;
            bill.redSourceNo = TmsUtil.str(head.get("redSourceNo"));
            bill.businessSource = TmsUtil.str(head.get("businessSource"));
            if (!BIZ_BACKOFFICE.equals(bill.businessSource) && !BIZ_OPENING_BACKFILL.equals(bill.businessSource)) {
                bill.businessSource = BIZ_BACKOFFICE;
            }
        }
        String jfNo = TmsUtil.str(head.get("factoryExpenseNo"));
        jdbc.update("DELETE FROM fin_factory_expense_detail WHERE factory_expense_id=?", jfId);
        insertDetails(jfId, bill.details);
        jdbc.update("UPDATE fin_factory_expense SET supplier_code=?,supplier_name=?,expense_date=?,"
                        + "claim_type=?,source_mode=?,total_amount=?,total_tax_amount=?,"
                        + "total_excluding_tax_amount=?,external_voucher_no=?,handler=?,department=?,"
                        + "remark=?,business_source=? WHERE factory_expense_id=?",
                bill.supplierCode, bill.supplierName, java.sql.Date.valueOf(bill.expenseDate),
                bill.claimType, bill.sourceMode, bill.total, bill.taxAmount, bill.excludingAmount,
                bill.externalVoucherNo, bill.handler, bill.department, bill.remark,
                bill.businessSource, jfId);
        opLog.log(MODULE, "UPDATE", jfNo, "修改厂家费用单 " + jfNo
                + "，金额 " + bill.total.toPlainString() + " 元，操作人 " + operator);
        return Map.of("factoryExpenseId", jfId, "factoryExpenseNo", jfNo);
    }

    /** 删单（仅 PENDING，连带明细），同时释放对客户费用单的占用。 */
    @Transactional
    public void delete(String jfId, String operator) {
        Map<String, Object> head = requireHead(jfId);
        requirePending(head);
        String jfNo = TmsUtil.str(head.get("factoryExpenseNo"));
        // 建单即写 FE 头回指，删单必须释放，否则客户费用单被永久占用（红字单无回指，0 行无害）
        jdbc.update("UPDATE fin_expense_bill SET factory_expense_no=NULL WHERE factory_expense_no=?", jfNo);
        jdbc.update("DELETE FROM fin_factory_expense_detail WHERE factory_expense_id=?", jfId);
        jdbc.update("DELETE FROM fin_factory_expense WHERE factory_expense_id=?", jfId);
        opLog.log(MODULE, "DELETE", jfNo, "删除厂家费用单 " + jfNo + "，操作人 " + operator);
    }

    // ==================== 审核 / 反审核 ====================

    /** 审核：PENDING→APPROVED，立费用债权（方案 §6.3）。 */
    @Transactional
    public void audit(String jfId, String operator) {
        Map<String, Object> head = requireHead(jfId);
        requirePending(head);
        doAudit(head, operator);
    }

    /** 反审核：APPROVED→PENDING（方案 §6.5）。 */
    @Transactional
    public void cancelAudit(String jfId, String operator) {
        Map<String, Object> head = requireHead(jfId);
        if (!STATUS_APPROVED.equals(TmsUtil.str(head.get("status")))) {
            throw new IllegalArgumentException("仅已审核单据可取消审核");
        }
        doCancel(head, operator);
    }

    private void doAudit(Map<String, Object> head, String operator) {
        String jfId = TmsUtil.str(head.get("factoryExpenseId"));
        String jfNo = TmsUtil.str(head.get("factoryExpenseNo"));
        LocalDate expenseDate = toLocalDate(head.get("expenseDate"));
        boolean isRed = "Y".equals(TmsUtil.str(head.get("isRed")));
        dayCloseGuard.assertWritable(expenseDate, "厂家费用单", jfNo);

        List<Map<String, Object>> details = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_factory_expense_detail WHERE factory_expense_id=? ORDER BY sort_order, detail_id",
                jfId);
        if (details.isEmpty()) {
            throw new IllegalArgumentException("厂家费用单 " + jfNo + " 没有明细行，不能审核");
        }
        // 以明细重算合计（红字单为负）
        BigDecimal total = BigDecimal.ZERO;
        Set<String> linkedFeBills = new LinkedHashSet<>();
        for (Map<String, Object> d : details) {
            BigDecimal amt = nz(TmsUtil.toBd(d.get("amount")));
            if (amt.signum() == 0) {
                throw new IllegalArgumentException("厂家费用单明细金额不能为 0");
            }
            if (!isRed && amt.signum() < 0) {
                throw new IllegalArgumentException("普通厂家费用单明细金额必须为正，负金额请走红字单");
            }
            if (isRed && amt.signum() > 0) {
                throw new IllegalArgumentException("红字厂家费用单明细金额必须为负");
            }
            total = total.add(amt);
            String feNo = TmsUtil.str(d.get("customerExpenseNo"));
            if (!feNo.isEmpty()) {
                linkedFeBills.add(feNo);
            }
        }
        if (total.signum() == 0) {
            throw new IllegalArgumentException("厂家费用单合计金额不能为 0");
        }

        String supplierCode = TmsUtil.str(head.get("supplierCode"));
        String supplierName = TmsUtil.str(head.get("supplierName"));

        // 代垫行：FE 整单占用复核 + 行锁，审核时回写头回指
        if (!linkedFeBills.isEmpty()) {
            String inPlace = String.join(",", linkedFeBills.stream().map(x -> "?").toList());
            List<Map<String, Object>> feHeads = TmsUtil.queryCamel(jdbc,
                    "SELECT expense_id, expense_no, factory_expense_no FROM fin_expense_bill "
                            + "WHERE expense_no IN (" + inPlace + ") FOR UPDATE",
                    linkedFeBills.toArray());
            if (feHeads.size() != linkedFeBills.size()) {
                throw new IllegalArgumentException("关联的客户费用单不存在，请刷新后重试");
            }
            for (Map<String, Object> fe : feHeads) {
                String backref = TmsUtil.str(fe.get("factoryExpenseNo"));
                if (!backref.isEmpty() && !backref.equals(jfNo)) {
                    throw new IllegalArgumentException("客户费用单 " + TmsUtil.str(fe.get("expenseNo"))
                            + " 已被厂家费用单 " + backref + " 关联，不能重复关联");
                }
            }
            // 行级占用复核（防并发下两张 JF 同行）
            List<Object> occupyArgs = new ArrayList<>(linkedFeBills);
            occupyArgs.add(jfNo);
            Integer occupied = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fin_factory_expense_detail x "
                            + "JOIN fin_factory_expense xh ON xh.factory_expense_id=x.factory_expense_id "
                            + "WHERE x.customer_expense_no IN (" + inPlace + ") AND xh.factory_expense_no<>?",
                    Integer.class, occupyArgs.toArray());
            if (occupied != null && occupied > 0) {
                throw new IllegalArgumentException("存在已被其他厂家费用单关联的客户费用单，请刷新后重试");
            }
            List<Object> backrefArgs = new ArrayList<>();
            backrefArgs.add(jfNo);
            backrefArgs.addAll(linkedFeBills);
            jdbc.update("UPDATE fin_expense_bill SET factory_expense_no=? WHERE expense_no IN (" + inPlace + ")",
                    backrefArgs.toArray());
        }

        // 主单三金额初值（红字单不可兑现：settle 系列置 0/已兑现态）
        BigDecimal settled = BigDecimal.ZERO;
        BigDecimal unsettled = isRed ? BigDecimal.ZERO : total;
        String settleStatus = isRed ? SupplierAccountConst.CLAIM_DONE : SupplierAccountConst.CLAIM_UNCLAIMED;
        jdbc.update("UPDATE fin_factory_expense SET total_amount=?, settled_amount=?, unsettled_amount=?, "
                        + "settle_status=? WHERE factory_expense_id=?",
                total, settled, unsettled, settleStatus, jfId);

        // 费用账户形成流水（红字走 FACTORY_EXP_RED，负金额）
        accounts.postFactoryExpenseForm(jfNo, expenseDate, supplierCode, supplierName, total, isRed);

        // 红字单冲减原单未兑现额并重算原单状态（§6.4）
        if (isRed) {
            String sourceNo = TmsUtil.str(head.get("redSourceNo"));
            applyReductionToSource(sourceNo, total.negate());
        }

        jdbc.update("UPDATE fin_factory_expense SET status='APPROVED', auditor_name=?, audit_time=? "
                + "WHERE factory_expense_id=?", operator, Timestamp.valueOf(LocalDateTime.now()), jfId);

        // GL 事件：OPENING_BACKFILL 不发（钩子内再兜一道 business_source 判断）
        glHooks.onFactoryExpenseAudited(jfNo);

        opLog.log(MODULE, "AUDIT", jfNo, "审核厂家费用单 " + jfNo + "，供应商 " + supplierName
                + "，" + (isRed ? "红字冲减 " : "立账 ") + total.abs().toPlainString() + " 元"
                + (BIZ_OPENING_BACKFILL.equals(TmsUtil.str(head.get("businessSource")))
                ? "（上线历史补录单，不生成总账凭证）" : "")
                + "，明细 " + details.size() + " 行");
    }

    private void doCancel(Map<String, Object> head, String operator) {
        String jfId = TmsUtil.str(head.get("factoryExpenseId"));
        String jfNo = TmsUtil.str(head.get("factoryExpenseNo"));
        LocalDate expenseDate = toLocalDate(head.get("expenseDate"));
        boolean isRed = "Y".equals(TmsUtil.str(head.get("isRed")));
        dayCloseGuard.assertWritable(expenseDate, "厂家费用单", jfNo);

        // 守卫：存在仍生效的 DX 兑现明细（已红冲部分不拦）
        Integer used = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_factory_settle_detail d "
                        + "JOIN fin_factory_settle s ON s.settle_id=d.settle_id "
                        + "WHERE d.factory_expense_no=? AND s.status='APPROVED'",
                Integer.class, jfNo);
        if (used != null && used > 0) {
            throw new IllegalArgumentException("厂家费用单 " + jfNo + " 已被兑现单兑现，请先反审核相关兑现单");
        }

        String supplierCode = TmsUtil.str(head.get("supplierCode"));
        String supplierName = TmsUtil.str(head.get("supplierName"));
        BigDecimal total = nz(TmsUtil.toBd(head.get("totalAmount")));

        accounts.reverseFactoryExpense(jfNo, expenseDate, supplierCode, supplierName, total, isRed);

        // 清 FE 头回指（红字单没有回指，UPDATE 0 行无害）
        jdbc.update("UPDATE fin_expense_bill SET factory_expense_no=NULL WHERE factory_expense_no=?", jfNo);

        // 红字单反审核：把原单未兑现额加回并重算状态（本红字单此刻仍 APPROVED，
        // 统计已审核红冲额时要先扣掉本单 |total|）
        if (isRed) {
            String sourceNo = TmsUtil.str(head.get("redSourceNo"));
            if (!sourceNo.isEmpty()) {
                List<Map<String, Object>> src = TmsUtil.queryCamel(jdbc,
                        "SELECT * FROM fin_factory_expense WHERE factory_expense_no=? FOR UPDATE", sourceNo);
                if (!src.isEmpty()) {
                    recomputeClaimStatus(src.get(0), total.abs());
                }
            }
        }

        jdbc.update("UPDATE fin_factory_expense SET status='PENDING', settled_amount=0, unsettled_amount=?, "
                        + "settle_status=NULL, auditor_name=NULL, audit_time=NULL WHERE factory_expense_id=?",
                isRed ? BigDecimal.ZERO : total, jfId);
        glHooks.onFactoryExpenseUnaudited(jfNo);
        opLog.log(MODULE, "UN_AUDIT", jfNo, (isRed ? "红字" : "") + "厂家费用单反审核 " + jfNo
                + "，冲回 " + total.abs().toPlainString() + " 元，操作人 " + operator);
    }

    // ==================== 红字单（§6.4） ====================

    /**
     * 由已审核原单开红字 JF（PENDING，用户确认后再审核）。
     * body 可带 expenseDate/handler/department/remark/externalVoucherNo 与 lines（负金额）；
     * 不带 lines 时按原单未兑现额等比摊到原行（尾差调最后一行）。
     */
    @Transactional
    public Map<String, Object> redCreate(Map<String, Object> req, String operator) {
        String sourceNo = TmsUtil.str(req.get("redSourceNo"));
        if (sourceNo.isEmpty()) {
            throw new IllegalArgumentException("请选择需要红冲的原厂家费用单");
        }
        List<Map<String, Object>> srcHeads = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_factory_expense WHERE factory_expense_no=?", sourceNo);
        if (srcHeads.isEmpty()) {
            throw new IllegalArgumentException("原厂家费用单 " + sourceNo + " 不存在");
        }
        Map<String, Object> src = srcHeads.get(0);
        if (!STATUS_APPROVED.equals(TmsUtil.str(src.get("status")))) {
            throw new IllegalArgumentException("只有已审核的厂家费用单可以开红字单");
        }
        if ("Y".equals(TmsUtil.str(src.get("isRed")))) {
            throw new IllegalArgumentException("红字单不能再开红字单");
        }
        Integer childCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_factory_expense WHERE red_source_no=? AND status='APPROVED'",
                Integer.class, sourceNo);
        if (childCnt != null && childCnt > 0) {
            throw new IllegalArgumentException("原单 " + sourceNo + " 已有审核通过的红字单，请先反审核红字单");
        }
        BigDecimal unsettled = nz(TmsUtil.toBd(src.get("unsettledAmount")));
        if (unsettled.signum() <= 0) {
            throw new IllegalArgumentException("原单 " + sourceNo + " 已全部兑现，不能红冲；请先反审核相关兑现单");
        }

        List<Map<String, Object>> srcLines = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_factory_expense_detail WHERE factory_expense_id=? ORDER BY sort_order, detail_id",
                TmsUtil.str(src.get("factoryExpenseId")));

        ParsedJf bill = new ParsedJf();
        bill.supplierCode = TmsUtil.str(src.get("supplierCode"));
        bill.supplierName = TmsUtil.str(src.get("supplierName"));
        String dateStr = TmsUtil.str(req.get("expenseDate"));
        bill.expenseDate = dateStr.isEmpty() ? LocalDate.now() : LocalDate.parse(dateStr.substring(0, 10));
        bill.claimType = TmsUtil.str(src.get("claimType"));
        bill.sourceMode = SOURCE_MANUAL;
        bill.businessSource = TmsUtil.str(src.get("businessSource")); // 补录原单的红冲同样不发事件
        bill.handler = TmsUtil.str(req.get("handler"));
        if (bill.handler.isEmpty()) {
            bill.handler = operator;
        }
        bill.department = TmsUtil.str(req.get("department"));
        if (bill.department.isEmpty()) {
            bill.department = TmsUtil.str(src.get("department"));
        }
        bill.externalVoucherNo = TmsUtil.str(req.get("externalVoucherNo"));
        bill.remark = TmsUtil.str(req.get("remark"));
        if (bill.remark.isEmpty()) {
            bill.remark = "红冲原单 " + sourceNo;
        }
        bill.isRed = true;
        bill.redSourceNo = sourceNo;

        Object rawLines = req.get("lines");
        BigDecimal lineTotal = BigDecimal.ZERO;
        if (rawLines instanceof List<?> list && !list.isEmpty()) {
            int sort = 1;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                BigDecimal amt = nz(TmsUtil.toBd(m.get("amount")));
                if (amt.signum() >= 0) {
                    throw new IllegalArgumentException("红字单明细金额必须为负");
                }
                String typeName = TmsUtil.str(m.get("expenseTypeName"));
                if (typeName.isEmpty()) {
                    throw new IllegalArgumentException("红字单明细费用类型不能为空");
                }
                Map<String, Object> type = loadExpenseType(typeName);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("expenseTypeCode", TmsUtil.str(type.get("expenseTypeCode")));
                d.put("expenseTypeName", typeName);
                d.put("customerCode", TmsUtil.str(m.get("customerCode")));
                d.put("customerName", TmsUtil.str(m.get("customerName")));
                // 红字行不复制 FE 单号/行 id：不新占 FE、也不释放原关联
                d.put("customerExpenseNo", "");
                d.put("customerExpenseDetailId", "");
                d.put("glCreditSubject", resolveCreditSubject(bill.claimType, typeName,
                        TmsUtil.str(m.get("glCreditSubject")), type));
                d.put("qty", nz(TmsUtil.toBd(m.get("qty"))).negate());
                d.put("price", TmsUtil.toBd(m.get("price")));
                d.put("amount", amt);
                d.put("remark", TmsUtil.str(m.get("remark")));
                d.put("sortOrder", sort++);
                bill.details.add(d);
                lineTotal = lineTotal.add(amt);
            }
        } else {
            // 默认：按未兑现额等比摊到原行（负），尾差并入最后一行
            BigDecimal toAllocate = unsettled;
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < srcLines.size(); i++) {
                Map<String, Object> sl = srcLines.get(i);
                BigDecimal share;
                if (i == srcLines.size() - 1) {
                    share = toAllocate.subtract(allocated);
                } else {
                    share = nz(TmsUtil.toBd(sl.get("amount")))
                            .multiply(unsettled).divide(nz(TmsUtil.toBd(src.get("totalAmount"))),
                                    2, java.math.RoundingMode.HALF_UP);
                    allocated = allocated.add(share);
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("expenseTypeCode", TmsUtil.str(sl.get("expenseTypeCode")));
                d.put("expenseTypeName", TmsUtil.str(sl.get("expenseTypeName")));
                d.put("customerCode", TmsUtil.str(sl.get("customerCode")));
                d.put("customerName", TmsUtil.str(sl.get("customerName")));
                d.put("customerExpenseNo", "");
                d.put("customerExpenseDetailId", "");
                d.put("glCreditSubject", TmsUtil.str(sl.get("glCreditSubject")));
                d.put("qty", nz(TmsUtil.toBd(sl.get("qty"))).negate());
                d.put("price", TmsUtil.toBd(sl.get("price")));
                d.put("amount", share.negate());
                d.put("remark", "红冲 " + sourceNo);
                d.put("sortOrder", i + 1);
                bill.details.add(d);
                lineTotal = lineTotal.add(share.negate());
            }
        }
        if (lineTotal.signum() >= 0) {
            throw new IllegalArgumentException("红字单合计金额必须为负");
        }
        if (lineTotal.abs().compareTo(unsettled) > 0) {
            throw new IllegalArgumentException("红字金额 " + lineTotal.abs().toPlainString()
                    + " 元超过原单剩余未兑现额 " + unsettled.toPlainString()
                    + " 元，已兑现部分请先反审核对应兑现单");
        }
        bill.total = lineTotal;
        bill.taxAmount = null;
        bill.excludingAmount = null;

        String jfId = newId("JF");
        String jfNo = billNoGen.nextNo(BillNoGenerator.BillType.FACTORY_EXPENSE,
                "fin_factory_expense", "factory_expense_no");
        insertHead(jfId, jfNo, bill, operator, true);
        insertDetails(jfId, bill.details);
        opLog.log(MODULE, "RED_CREATE", jfNo, "由原单 " + sourceNo + " 开具红字厂家费用单 " + jfNo
                + "，红冲 " + lineTotal.abs().toPlainString() + " 元（原单未兑现 "
                + unsettled.toPlainString() + " 元）");
        return Map.of("factoryExpenseId", jfId, "factoryExpenseNo", jfNo);
    }

    // ==================== Excel 导入（§6.2，按供应商+日期+费用性质分组生 PENDING） ====================

    /**
     * 导入行直接分组生成 PENDING JF（不设暂存表）：部分成功——逐行校验，失败行落
     * sys_import_task_runtime + 失败 xlsx；成功行按 供应商+费用日期+费用性质 分组，
     * 每组一张 PENDING JF（source_mode=IMPORT）。
     */
    @Transactional
    public Map<String, Object> importRows(List<Map<String, Object>> rows, String fileName, String operator) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("导入数据为空");
        }
        List<Map<String, Object>> failures = new ArrayList<>();
        // 分组键 -> 组内行（已转成标准 detail Map）
        Map<String, ImportGroup> groups = new LinkedHashMap<>();
        Set<String> batchOccupiedFes = new LinkedHashSet<>();
        int successRows = 0;

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2; // Excel 物理行号（含表头）
            Map<String, Object> row = rows.get(i);
            try {
                String supplierCode = TmsUtil.str(row.get("supplierCode"));
                if (supplierCode.isEmpty()) {
                    throw new IllegalArgumentException("供应商编码不能为空");
                }
                Map<String, Object> supplier = loadSupplier(supplierCode);
                String typeName = TmsUtil.str(row.get("expenseTypeName"));
                if (typeName.isEmpty()) {
                    throw new IllegalArgumentException("费用类型不能为空");
                }
                Map<String, Object> type = loadExpenseType(typeName);
                String claimText = TmsUtil.str(row.get("claimTypeText"));
                String claimType = parseClaimType(claimText);
                String dateText = TmsUtil.str(row.get("expenseDate"));
                if (dateText.isEmpty()) {
                    throw new IllegalArgumentException("费用日期不能为空");
                }
                LocalDate expenseDate;
                try {
                    expenseDate = LocalDate.parse(dateText.length() > 10 ? dateText.substring(0, 10) : dateText);
                } catch (Exception e) {
                    throw new IllegalArgumentException("费用日期格式不正确（应为 YYYY-MM-DD）：" + dateText);
                }
                BigDecimal amount = nz(TmsUtil.toBd(row.get("amount")));
                if (amount.signum() <= 0) {
                    throw new IllegalArgumentException("金额必须大于 0");
                }
                String customerCode = TmsUtil.str(row.get("customerCode"));
                String customerName = "";
                if (!customerCode.isEmpty()) {
                    List<Map<String, Object>> custs = TmsUtil.queryCamel(jdbc,
                            "SELECT customer_code, customer_name FROM base_customer "
                                    + "WHERE customer_code=? AND status<>'DELETED' LIMIT 1", customerCode);
                    if (custs.isEmpty()) {
                        throw new IllegalArgumentException("垫付客户编码 " + customerCode + " 不存在");
                    }
                    customerName = TmsUtil.str(custs.get(0).get("customerName"));
                }
                String feNo = TmsUtil.str(row.get("customerExpenseNo"));
                String feDetailId = "";
                if (!feNo.isEmpty()) {
                    List<Map<String, Object>> fes = TmsUtil.queryCamel(jdbc,
                            "SELECT expense_no, status, direction, counterparty_type, factory_expense_no "
                                    + "FROM fin_expense_bill WHERE expense_no=?", feNo);
                    if (fes.isEmpty()) {
                        throw new IllegalArgumentException("关联客户费用单 " + feNo + " 不存在");
                    }
                    Map<String, Object> fe = fes.get(0);
                    if (!"APPROVED".equals(TmsUtil.str(fe.get("status")))
                            || !"OUT".equals(TmsUtil.str(fe.get("direction")))
                            || !"CUSTOMER".equals(TmsUtil.str(fe.get("counterpartyType")))) {
                        throw new IllegalArgumentException("关联客户费用单 " + feNo + " 不是已审核的客户支出费用单");
                    }
                    String backref = TmsUtil.str(fe.get("factoryExpenseNo"));
                    if ((!backref.isEmpty()) || batchOccupiedFes.contains(feNo)
                            || isFeOccupiedByOther(feNo, null)) {
                        throw new IllegalArgumentException("关联客户费用单 " + feNo + " 已被其他厂家费用单关联（整单占用）");
                    }
                    batchOccupiedFes.add(feNo);
                }
                String explicitSubject = TmsUtil.str(row.get("glCreditSubject"));
                String subject = resolveCreditSubject(claimType, typeName, explicitSubject, type);

                Map<String, Object> d = new LinkedHashMap<>();
                d.put("expenseTypeCode", TmsUtil.str(type.get("expenseTypeCode")));
                d.put("expenseTypeName", typeName);
                d.put("customerCode", customerCode);
                d.put("customerName", customerName);
                d.put("customerExpenseNo", feNo);
                d.put("customerExpenseDetailId", feDetailId);
                d.put("glCreditSubject", subject);
                d.put("qty", BigDecimal.ZERO);
                d.put("price", null);
                d.put("amount", amount);
                d.put("remark", TmsUtil.str(row.get("remark")));

                String key = supplierCode + "|" + expenseDate + "|" + claimType;
                ImportGroup g = groups.computeIfAbsent(key, k -> {
                    ImportGroup ng = new ImportGroup();
                    ng.supplierCode = supplierCode;
                    ng.supplierName = TmsUtil.str(supplier.get("supplierName"));
                    ng.expenseDate = expenseDate;
                    ng.claimType = claimType;
                    ng.externalVoucherNo = TmsUtil.str(row.get("externalVoucherNo"));
                    return ng;
                });
                // 同组协议号不一致时不强行覆盖，留空由用户在 PENDING 单补
                String voucher = TmsUtil.str(row.get("externalVoucherNo"));
                if (!voucher.isEmpty()) {
                    if (g.externalVoucherNo.isEmpty()) {
                        g.externalVoucherNo = voucher;
                    } else if (!g.externalVoucherNo.equals(voucher)) {
                        g.externalVoucherNo = "";
                    }
                }
                d.put("sortOrder", g.details.size() + 1);
                g.details.add(d);
                successRows++;
            } catch (IllegalArgumentException e) {
                failures.add(initSupport.failure(rowNo, e.getMessage()));
            }
        }

        List<Map<String, Object>> createdBills = new ArrayList<>();
        for (ImportGroup g : groups.values()) {
            String jfId = newId("JF");
            String jfNo = billNoGen.nextNo(BillNoGenerator.BillType.FACTORY_EXPENSE,
                    "fin_factory_expense", "factory_expense_no");
            BigDecimal total = g.details.stream()
                    .map(x -> nz(TmsUtil.toBd(x.get("amount"))))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            ParsedJf bill = new ParsedJf();
            bill.supplierCode = g.supplierCode;
            bill.supplierName = g.supplierName;
            bill.expenseDate = g.expenseDate;
            bill.claimType = g.claimType;
            bill.sourceMode = SOURCE_IMPORT;
            bill.externalVoucherNo = g.externalVoucherNo;
            bill.handler = operator;
            bill.remark = "Excel 导入生成";
            bill.total = total;
            bill.details.addAll(g.details);
            insertHead(jfId, jfNo, bill, operator, false);
            insertDetails(jfId, g.details);
            opLog.log(MODULE, "IMPORT", jfNo, "导入生成厂家费用单 " + jfNo + "（" + claimTypeText(g.claimType)
                    + "），供应商 " + g.supplierName + "，" + g.details.size() + " 行，金额 "
                    + total.toPlainString() + " 元");
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("factoryExpenseNo", jfNo);
            b.put("supplierName", g.supplierName);
            b.put("expenseDate", g.expenseDate.toString());
            b.put("totalAmount", total);
            createdBills.add(b);
        }

        String taskNo = initSupport.recordImportTask("fin.factory_expense", "厂家费用单导入",
                fileName, successRows, failures, IMPORT_FIELDS, rows);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", successRows);
        result.put("failed", failures.size());
        result.put("billCount", createdBills.size());
        result.put("bills", createdBills);
        result.put("taskNo", taskNo);
        return result;
    }

    /** 导入分组中间结构。 */
    private static final class ImportGroup {
        private String supplierCode;
        private String supplierName;
        private LocalDate expenseDate;
        private String claimType;
        private String externalVoucherNo = "";
        private final List<Map<String, Object>> details = new ArrayList<>();
    }

    // ==================== 建单解析与校验 ====================

    private static final class ParsedJf {
        private String supplierCode;
        private String supplierName;
        private LocalDate expenseDate;
        private String claimType = CLAIM_ADVANCE;
        private String sourceMode = SOURCE_MANUAL;
        private String businessSource = BIZ_BACKOFFICE;
        private String externalVoucherNo = "";
        private String handler = "";
        private String department = "";
        private String remark = "";
        private BigDecimal taxAmount;
        private BigDecimal excludingAmount;
        private BigDecimal total = BigDecimal.ZERO;
        private boolean isRed;
        private String redSourceNo;
        private final List<Map<String, Object>> details = new ArrayList<>();
    }

    /**
     * 解析建/改单入参并做行级真值校验。
     *
     * @param selfJfId 改单时传当前 JF id（占用判定排除自身）；建单传 null
     * @param isRed    红字单（金额必须为负，不关联 FE）
     */
    private ParsedJf parseBill(Map<String, Object> req, String selfJfId, boolean isRed) {
        ParsedJf bill = new ParsedJf();
        bill.isRed = isRed;
        bill.supplierCode = TmsUtil.str(req.get("supplierCode"));
        if (bill.supplierCode.isEmpty()) {
            throw new IllegalArgumentException("请选择供应商");
        }
        Map<String, Object> supplier = loadSupplier(bill.supplierCode);
        bill.supplierName = TmsUtil.str(req.get("supplierName"));
        if (bill.supplierName.isEmpty()) {
            bill.supplierName = TmsUtil.str(supplier.get("supplierName"));
        }
        String dateStr = TmsUtil.str(req.get("expenseDate"));
        bill.expenseDate = dateStr.isEmpty() ? LocalDate.now() : LocalDate.parse(dateStr.substring(0, 10));

        bill.claimType = CLAIM_OTHER.equals(TmsUtil.str(req.get("claimType"))) ? CLAIM_OTHER : CLAIM_ADVANCE;
        bill.sourceMode = TmsUtil.str(req.get("sourceMode"));
        if (!SOURCE_CUSTOMER_EXPENSE.equals(bill.sourceMode) && !SOURCE_IMPORT.equals(bill.sourceMode)) {
            bill.sourceMode = SOURCE_MANUAL;
        }
        bill.businessSource = BIZ_BACKOFFICE;
        if (BIZ_OPENING_BACKFILL.equals(TmsUtil.str(req.get("businessSource")))) {
            if (!"Y".equalsIgnoreCase(sysParam.get(PARAM_OPENING_BACKFILL, "N"))) {
                throw new IllegalArgumentException("厂家费用历史补录入口未开启（参数 P0190），不能保存补录单");
            }
            bill.businessSource = BIZ_OPENING_BACKFILL;
            // 补录记账日期只能取未封账日（审核时 assertWritable 还会再守一道）
            if (dayCloseGuard.isClosed(bill.expenseDate)) {
                throw new IllegalArgumentException("补录日期 " + bill.expenseDate + " 已封账，请选择未封账日期");
            }
        }
        bill.externalVoucherNo = TmsUtil.str(req.get("externalVoucherNo"));
        bill.handler = TmsUtil.str(req.get("handler"));
        bill.department = TmsUtil.str(req.get("department"));
        bill.remark = TmsUtil.str(req.get("remark"));
        bill.taxAmount = req.get("totalTaxAmount") == null || TmsUtil.str(req.get("totalTaxAmount")).isEmpty()
                ? null : nz(TmsUtil.toBd(req.get("totalTaxAmount")));
        bill.excludingAmount = req.get("totalExcludingTaxAmount") == null
                || TmsUtil.str(req.get("totalExcludingTaxAmount")).isEmpty()
                ? null : nz(TmsUtil.toBd(req.get("totalExcludingTaxAmount")));

        Object raw = req.get("details");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("请至少录入一行费用明细");
        }
        BigDecimal total = BigDecimal.ZERO;
        Set<String> seenFeDetail = new LinkedHashSet<>();
        Set<String> seenFeBill = new LinkedHashSet<>();
        int sort = 1;
        boolean hasFeLink = false;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String typeName = TmsUtil.str(m.get("expenseTypeName"));
            if (typeName.isEmpty()) {
                throw new IllegalArgumentException("费用类型不能为空");
            }
            Map<String, Object> type = loadExpenseType(typeName);
            BigDecimal amount = nz(TmsUtil.toBd(m.get("amount")));
            if (isRed) {
                if (amount.signum() >= 0) {
                    throw new IllegalArgumentException("红字单费用金额必须为负（费用类型：" + typeName + "）");
                }
            } else if (amount.signum() <= 0) {
                throw new IllegalArgumentException("费用金额必须大于 0（费用类型：" + typeName + "）");
            }
            String customerCode = TmsUtil.str(m.get("customerCode"));
            String customerName = TmsUtil.str(m.get("customerName"));
            if (!customerCode.isEmpty()) {
                List<Map<String, Object>> custs = TmsUtil.queryCamel(jdbc,
                        "SELECT customer_name FROM base_customer WHERE customer_code=? "
                                + "AND status<>'DELETED' LIMIT 1", customerCode);
                if (custs.isEmpty()) {
                    throw new IllegalArgumentException("垫付客户编码 " + customerCode + " 不存在");
                }
                if (customerName.isEmpty()) {
                    customerName = TmsUtil.str(custs.get(0).get("customerName"));
                }
            }
            String feNo = TmsUtil.str(m.get("customerExpenseNo"));
            String feDetailId = TmsUtil.str(m.get("customerExpenseDetailId"));
            if (isRed && (!feNo.isEmpty() || !feDetailId.isEmpty())) {
                throw new IllegalArgumentException("红字单不能关联客户费用单");
            }
            if (CLAIM_OTHER.equals(bill.claimType) && (!feNo.isEmpty() || !feDetailId.isEmpty())) {
                throw new IllegalArgumentException("费用性质为「其他」的明细不能关联客户费用单");
            }
            if (CLAIM_ADVANCE.equals(bill.claimType) && (!feNo.isEmpty() || !feDetailId.isEmpty())) {
                if (feNo.isEmpty()) {
                    throw new IllegalArgumentException("关联客户费用单时必须带单号");
                }
                if (!seenFeBill.add(feNo) && feDetailId.isEmpty()) {
                    throw new IllegalArgumentException("客户费用单 " + feNo + " 在明细中重复关联，请合并为一行");
                }
                Map<String, Object> fe = assertFeLinkable(feNo, feDetailId, selfJfId);
                // 行级 id 存在时校验该明细行确实属于该 FE 且本单内不重复
                if (!feDetailId.isEmpty()) {
                    if (!seenFeDetail.add(feDetailId)) {
                        throw new IllegalArgumentException("客户费用单明细行 " + feDetailId + " 重复选择");
                    }
                    Integer lineCnt = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM fin_expense_detail WHERE detail_id=? AND expense_id=?",
                            Integer.class, feDetailId, TmsUtil.str(fe.get("expenseId")));
                    if (lineCnt == null || lineCnt == 0) {
                        throw new IllegalArgumentException("客户费用单 " + feNo + " 上不存在明细行 " + feDetailId);
                    }
                    if (isFeDetailOccupied(feDetailId, selfJfId)) {
                        throw new IllegalArgumentException("客户费用单 " + feNo + " 的明细行已被其他厂家费用单关联");
                    }
                }
                if (isFeOccupiedByOther(feNo, selfJfId)) {
                    throw new IllegalArgumentException("客户费用单 " + feNo + " 已被其他厂家费用单关联（一张客户费用单只能关联一张厂家费用单）");
                }
                hasFeLink = true;
                // 代垫关联行金额可小于 FE 行金额（差额我方自担），不做上限拦截
            }
            String explicitSubject = TmsUtil.str(m.get("glCreditSubject"));
            String subject = resolveCreditSubject(bill.claimType, typeName, explicitSubject, type);

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("expenseTypeCode", TmsUtil.str(type.get("expenseTypeCode")));
            d.put("expenseTypeName", typeName);
            d.put("customerCode", customerCode);
            d.put("customerName", customerName);
            d.put("customerExpenseNo", feNo);
            d.put("customerExpenseDetailId", feDetailId);
            d.put("glCreditSubject", subject);
            d.put("qty", TmsUtil.toBd(m.get("qty")));
            d.put("price", TmsUtil.toBd(m.get("price")));
            d.put("amount", amount);
            d.put("remark", TmsUtil.str(m.get("remark")));
            d.put("sortOrder", sort++);
            bill.details.add(d);
            total = total.add(amount);
        }
        if (bill.details.isEmpty()) {
            throw new IllegalArgumentException("请至少录入一行金额大于 0 的费用明细");
        }
        if (CLAIM_ADVANCE.equals(bill.claimType) && hasFeLink) {
            bill.sourceMode = SOURCE_CUSTOMER_EXPENSE;
        }
        bill.total = total.setScale(2, java.math.RoundingMode.HALF_UP);
        return bill;
    }

    /** FE 头可关联性校验：存在、已审核、客户支出、未被别的 JF 占（回指）。 */
    private Map<String, Object> assertFeLinkable(String feNo, String feDetailId, String selfJfId) {
        List<Map<String, Object>> fes = TmsUtil.queryCamel(jdbc,
                "SELECT expense_id, expense_no, status, direction, counterparty_type, factory_expense_no "
                        + "FROM fin_expense_bill WHERE expense_no=?", feNo);
        if (fes.isEmpty()) {
            throw new IllegalArgumentException("关联客户费用单 " + feNo + " 不存在");
        }
        Map<String, Object> fe = fes.get(0);
        if (!"APPROVED".equals(TmsUtil.str(fe.get("status")))) {
            throw new IllegalArgumentException("关联客户费用单 " + feNo + " 未审核，不能关联");
        }
        if (!"OUT".equals(TmsUtil.str(fe.get("direction")))
                || !"CUSTOMER".equals(TmsUtil.str(fe.get("counterpartyType")))) {
            throw new IllegalArgumentException("关联客户费用单 " + feNo + " 不是客户方向的支出费用单");
        }
        return fe;
    }

    private boolean isFeOccupiedByOther(String feNo, String selfJfId) {
        Integer n;
        if (selfJfId == null) {
            n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fin_factory_expense_detail d "
                            + "JOIN fin_factory_expense h ON h.factory_expense_id=d.factory_expense_id "
                            + "WHERE d.customer_expense_no=?", Integer.class, feNo);
        } else {
            n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fin_factory_expense_detail d "
                            + "JOIN fin_factory_expense h ON h.factory_expense_id=d.factory_expense_id "
                            + "WHERE d.customer_expense_no=? AND h.factory_expense_id<>?",
                    Integer.class, feNo, selfJfId);
        }
        // 已审核回指也算占用
        List<Map<String, Object>> fes = TmsUtil.queryCamel(jdbc,
                "SELECT factory_expense_no FROM fin_expense_bill WHERE expense_no=?", feNo);
        String backref = fes.isEmpty() ? "" : TmsUtil.str(fes.get(0).get("factoryExpenseNo"));
        if (!backref.isEmpty()) {
            if (selfJfId == null) {
                return true;
            }
            List<Map<String, Object>> self = TmsUtil.queryCamel(jdbc,
                    "SELECT factory_expense_no FROM fin_factory_expense WHERE factory_expense_id=?", selfJfId);
            if (self.isEmpty() || !backref.equals(TmsUtil.str(self.get(0).get("factoryExpenseNo")))) {
                return true;
            }
        }
        return n != null && n > 0;
    }

    private boolean isFeDetailOccupied(String feDetailId, String selfJfId) {
        Integer n;
        if (selfJfId == null) {
            n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fin_factory_expense_detail WHERE customer_expense_detail_id=?",
                    Integer.class, feDetailId);
        } else {
            n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fin_factory_expense_detail WHERE customer_expense_detail_id=? "
                            + "AND factory_expense_id<>?", Integer.class, feDetailId, selfJfId);
        }
        return n != null && n > 0;
    }

    private void insertHead(String jfId, String jfNo, ParsedJf bill, String operator, boolean isRed) {
        jdbc.update("INSERT INTO fin_factory_expense(factory_expense_id,factory_expense_no,"
                        + "supplier_code,supplier_name,expense_date,claim_type,source_mode,"
                        + "total_amount,settled_amount,unsettled_amount,settle_status,is_red,red_source_no,"
                        + "external_voucher_no,handler,department,total_tax_amount,total_excluding_tax_amount,"
                        + "remark,status,business_source,creator_name,create_time) "
                        + "VALUES(?,?,?,?,?,?,?,?,0,?,NULL,?,?,?,?,?,?,?,?,'PENDING',?,?,CURRENT_TIMESTAMP)",
                jfId, jfNo, bill.supplierCode, bill.supplierName, java.sql.Date.valueOf(bill.expenseDate),
                bill.claimType, bill.sourceMode, bill.total, isRed ? BigDecimal.ZERO : bill.total,
                isRed ? "Y" : "N", isRed ? bill.redSourceNo : null,
                bill.externalVoucherNo, bill.handler, bill.department,
                bill.taxAmount, bill.excludingAmount, bill.remark, bill.businessSource,
                operator);
    }

    private void insertDetails(String jfId, List<Map<String, Object>> details) {
        for (Map<String, Object> d : details) {
            jdbc.update("INSERT INTO fin_factory_expense_detail(detail_id,factory_expense_id,"
                            + "expense_type_code,expense_type_name,customer_code,customer_name,"
                            + "customer_expense_no,customer_expense_detail_id,gl_credit_subject,"
                            + "qty,price,amount,remark,sort_order) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    newId("JFD"), jfId,
                    TmsUtil.str(d.get("expenseTypeCode")), TmsUtil.str(d.get("expenseTypeName")),
                    emptyToNull(TmsUtil.str(d.get("customerCode"))), emptyToNull(TmsUtil.str(d.get("customerName"))),
                    emptyToNull(TmsUtil.str(d.get("customerExpenseNo"))),
                    emptyToNull(TmsUtil.str(d.get("customerExpenseDetailId"))),
                    TmsUtil.str(d.get("glCreditSubject")),
                    TmsUtil.toBd(d.get("qty")), TmsUtil.toBd(d.get("price")),
                    nz(TmsUtil.toBd(d.get("amount"))),
                    TmsUtil.str(d.get("remark")), TmsUtil.toInt(d.get("sortOrder")));
        }
    }

    // ==================== 原单/红字状态联动 ====================

    /** 红字审核后：冲减原单未兑现额，按 settled/red 重算状态（§6.4）。redAmount 为正数。 */
    private void applyReductionToSource(String sourceNo, BigDecimal redAmount) {
        if (sourceNo.isEmpty()) {
            return;
        }
        List<Map<String, Object>> srcs = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_factory_expense WHERE factory_expense_no=? FOR UPDATE", sourceNo);
        if (srcs.isEmpty()) {
            throw new IllegalArgumentException("红字原单 " + sourceNo + " 不存在");
        }
        Map<String, Object> src = srcs.get(0);
        BigDecimal total = nz(TmsUtil.toBd(src.get("totalAmount")));
        BigDecimal settled = nz(TmsUtil.toBd(src.get("settledAmount")));
        // 本红字单此刻仍是 PENDING（状态在调用方后置翻 APPROVED），approvedReduction 统计不到它，
        // 必须把本次 redAmount 显式加上，否则原单未兑现额不会被冲减
        BigDecimal redApproved = approvedReduction(sourceNo).add(redAmount);
        BigDecimal resolved = settled.add(redApproved);
        BigDecimal unsettled = total.subtract(resolved);
        if (unsettled.signum() < 0) {
            // 理论不可达（红字创建时已限未兑现额），兜底防串
            throw new IllegalArgumentException("红字金额超过原单 " + sourceNo + " 剩余未兑现额");
        }
        String status = resolved.signum() == 0 ? SupplierAccountConst.CLAIM_UNCLAIMED
                : resolved.compareTo(total) >= 0 ? SupplierAccountConst.CLAIM_DONE
                : SupplierAccountConst.CLAIM_PART;
        jdbc.update("UPDATE fin_factory_expense SET settled_amount=?, unsettled_amount=?, settle_status=? "
                + "WHERE factory_expense_no=?", settled, unsettled, status, sourceNo);
        accounts.writeBackClaimStatus(sourceNo, settled, status);
    }

    /**
     * 按 fin_factory_expense 真值重算一张 JF 的兑现状态（红字单反审核时用）。
     *
     * @param excludeReduction 统计已审核红冲额时需剔除的金额（本红字单反审核瞬间仍为 APPROVED）；
     *                         正常重算传 0
     */
    private void recomputeClaimStatus(Map<String, Object> src, BigDecimal excludeReduction) {
        String sourceNo = TmsUtil.str(src.get("factoryExpenseNo"));
        BigDecimal total = nz(TmsUtil.toBd(src.get("totalAmount")));
        BigDecimal settled = nz(TmsUtil.toBd(src.get("settledAmount")));
        BigDecimal redApproved = approvedReduction(sourceNo).subtract(nz(excludeReduction));
        BigDecimal resolved = settled.add(redApproved);
        BigDecimal unsettled = total.subtract(resolved);
        String status = resolved.signum() == 0 ? SupplierAccountConst.CLAIM_UNCLAIMED
                : resolved.compareTo(total) >= 0 ? SupplierAccountConst.CLAIM_DONE
                : SupplierAccountConst.CLAIM_PART;
        jdbc.update("UPDATE fin_factory_expense SET unsettled_amount=?, settle_status=? WHERE factory_expense_no=?",
                unsettled, status, sourceNo);
        accounts.writeBackClaimStatus(sourceNo, settled, status);
    }

    /** 原单已审核红字单的红冲总额（正数）。 */
    private BigDecimal approvedReduction(String sourceNo) {
        BigDecimal red = jdbc.queryForObject(
                "SELECT COALESCE(SUM(-total_amount),0) FROM fin_factory_expense "
                        + "WHERE red_source_no=? AND is_red='Y' AND status='APPROVED'",
                BigDecimal.class, sourceNo);
        return nz(red);
    }

    // ==================== 档案/科目解析 ====================

    private Map<String, Object> loadSupplier(String supplierCode) {
        List<Map<String, Object>> list = TmsUtil.queryCamel(jdbc,
                "SELECT supplier_code, supplier_name, status FROM base_supplier "
                        + "WHERE supplier_code=? LIMIT 1", supplierCode);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("供应商编码 " + supplierCode + " 不存在");
        }
        Map<String, Object> s = list.get(0);
        if ("DELETED".equals(TmsUtil.str(s.get("status")))) {
            throw new IllegalArgumentException("供应商 " + TmsUtil.str(s.get("supplierName")) + " 已停用/删除，不能制单");
        }
        return s;
    }

    /** 末级启用费用类型（按名称）。 */
    private Map<String, Object> loadExpenseType(String name) {
        List<Map<String, Object>> list = TmsUtil.queryCamel(jdbc,
                "SELECT expense_type_code, expense_type_name, gl_expense_account_code, "
                        + "factory_gl_credit_subject_code, status, parent_code "
                        + "FROM base_expense_type WHERE expense_type_name=? LIMIT 1", name);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("费用类型「" + name + "」不存在，请先在费用类型档案中维护");
        }
        Map<String, Object> t = list.get(0);
        if (!"NORMAL".equals(TmsUtil.str(t.get("status")))) {
            throw new IllegalArgumentException("费用类型「" + name + "」已停用");
        }
        String code = TmsUtil.str(t.get("expenseTypeCode"));
        Integer childCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM base_expense_type WHERE parent_code=?", Integer.class, code);
        if (childCnt != null && childCnt > 0) {
            throw new IllegalArgumentException("费用类型「" + name + "」不是末级费用类型，请选择末级");
        }
        return t;
    }

    /**
     * 行贷方科目解析（§9.2）：显式指定优先（须末级启用）；代垫=费用类型总账费用科目
     * （gl_expense_account_code，FE 原路冲回）；其他=费用类型厂家费用贷方科目
     * （factory_gl_credit_subject_code），未配默认 5401。
     */
    private String resolveCreditSubject(String claimType, String typeName, String explicit,
                                        Map<String, Object> type) {
        if (!explicit.isEmpty()) {
            assertLeafEnabled(explicit, "贷方科目");
            return explicit;
        }
        if (CLAIM_ADVANCE.equals(claimType)) {
            String code = TmsUtil.str(type.get("glExpenseAccountCode"));
            if (code.isEmpty()) {
                throw new IllegalArgumentException("费用类型「" + typeName
                        + "」未配置总账费用科目，请在费用类型档案配置或在明细行手工指定贷方科目");
            }
            assertLeafEnabled(code, "费用类型「" + typeName + "」的总账费用科目");
            return code;
        }
        String factoryCode = TmsUtil.str(type.get("factoryGlCreditSubjectCode"));
        if (factoryCode.isEmpty()) {
            factoryCode = "5401";
        }
        assertLeafEnabled(factoryCode, "费用类型「" + typeName + "」的厂家费用贷方科目");
        return factoryCode;
    }

    private void assertLeafEnabled(String code, String what) {
        List<Map<String, Object>> accs = TmsUtil.queryCamel(jdbc,
                "SELECT account_name, status FROM fin_account WHERE account_code=? AND is_leaf=TRUE", code);
        if (accs.isEmpty()) {
            throw new IllegalArgumentException(what + "「" + code + "」不存在或不是末级科目");
        }
        if (!"启用".equals(TmsUtil.str(accs.get(0).get("status")))) {
            throw new IllegalArgumentException(what + "「" + code + "」已停用，请启用或改用其他科目");
        }
    }

    private static String parseClaimType(String text) {
        if ("代垫".equals(text) || CLAIM_ADVANCE.equalsIgnoreCase(text)) {
            return CLAIM_ADVANCE;
        }
        if ("其他".equals(text) || CLAIM_OTHER.equalsIgnoreCase(text)) {
            return CLAIM_OTHER;
        }
        throw new IllegalArgumentException("费用性质只能填「代垫」或「其他」，实际值：" + text);
    }

    // ==================== 通用 ====================

    private Map<String, Object> loadHead(String jfId, String jfNo) {
        if (!jfId.isEmpty()) {
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                    "SELECT * FROM fin_factory_expense WHERE factory_expense_id=?", jfId);
            return rows.isEmpty() ? null : rows.get(0);
        }
        if (!jfNo.isEmpty()) {
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                    "SELECT * FROM fin_factory_expense WHERE factory_expense_no=?", jfNo);
            return rows.isEmpty() ? null : rows.get(0);
        }
        return null;
    }

    private Map<String, Object> requireHead(String jfId) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_factory_expense WHERE factory_expense_id=?", jfId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("厂家费用单不存在或已删除");
        }
        return rows.get(0);
    }

    private void requirePending(Map<String, Object> head) {
        if (!STATUS_PENDING.equals(TmsUtil.str(head.get("status")))) {
            throw new IllegalArgumentException("仅待审核状态的单据可执行该操作");
        }
    }

    private void fillTexts(Map<String, Object> r) {
        r.put("statusText", STATUS_APPROVED.equals(TmsUtil.str(r.get("status"))) ? "已审核" : "待审核");
        r.put("claimTypeText", claimTypeText(TmsUtil.str(r.get("claimType"))));
        r.put("sourceModeText", sourceModeText(TmsUtil.str(r.get("sourceMode"))));
        r.put("businessSourceText",
                BIZ_OPENING_BACKFILL.equals(TmsUtil.str(r.get("businessSource"))) ? "上线历史补录" : "日常业务");
        String ss = TmsUtil.str(r.get("settleStatus"));
        r.put("settleStatusText", ss.isEmpty() ? "—" : ss);
        r.put("isRedText", "Y".equals(TmsUtil.str(r.get("isRed"))) ? "红字" : "");
    }

    static String claimTypeText(String claimType) {
        return CLAIM_OTHER.equals(claimType) ? "其他" : "代垫";
    }

    private static String sourceModeText(String mode) {
        return switch (mode) {
            case SOURCE_CUSTOMER_EXPENSE -> "客户费用单关联";
            case SOURCE_IMPORT -> "Excel 导入";
            default -> "手工录入";
        };
    }

    static String settleTypeText(String type) {
        return switch (type) {
            case "CASH" -> "现金结算";
            case "OFFSET" -> "冲应付";
            case "OTHER" -> "其他核销";
            default -> type;
        };
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

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
