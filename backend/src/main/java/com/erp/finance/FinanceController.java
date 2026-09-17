package com.erp.finance;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.util.BillNoGenerator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.erp.common.security.RequirePerm;
import com.erp.tms.TmsUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/finance")
public class FinanceController {
    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;
    private final com.erp.system.OperationLogService opLog;
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final com.erp.finance.gl.GlHookService glHooks;

    private final com.erp.common.security.datascope.DataScopeService dataScope;
    private final com.erp.common.security.FieldMasker fieldMasker;
    private final com.erp.finance.dayclose.BizDayCloseGuard dayCloseGuard;
    private final com.erp.finance.account.CustomerAccountService customerAccountService;
    private final com.erp.finance.account.AdvanceWriteoffService advanceWriteoffService;
    private final com.erp.finance.account.SupplierAccountService supplierAccountService;
    private final com.erp.finance.account.PrepayWriteoffService prepayWriteoffService;

    public FinanceController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen,
                             com.erp.system.OperationLogService opLog,
                             com.erp.finance.gl.GlHookService glHooks,
                             com.erp.common.security.datascope.DataScopeService dataScope,
                             com.erp.common.security.FieldMasker fieldMasker,
                             com.erp.finance.dayclose.BizDayCloseGuard dayCloseGuard,
                             com.erp.finance.account.CustomerAccountService customerAccountService,
                             com.erp.finance.account.AdvanceWriteoffService advanceWriteoffService,
                             com.erp.finance.account.SupplierAccountService supplierAccountService,
                             com.erp.finance.account.PrepayWriteoffService prepayWriteoffService) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
        this.opLog = opLog;
        this.glHooks = glHooks;
        this.dataScope = dataScope;
        this.fieldMasker = fieldMasker;
        this.dayCloseGuard = dayCloseGuard;
        this.customerAccountService = customerAccountService;
        this.advanceWriteoffService = advanceWriteoffService;
        this.supplierAccountService = supplierAccountService;
        this.prepayWriteoffService = prepayWriteoffService;
    }

    /** 收款单收款类型归一：空=SETTLE；非法值中文报错；预收类只允许客户往来。 */
    private static String normalizeReceiptType(String raw, String cpType) {
        String t = raw == null || raw.isBlank()
                ? com.erp.finance.account.CustomerAccountConst.RECEIPT_SETTLE : raw.trim();
        if (!com.erp.finance.account.CustomerAccountConst.RECEIPT_SETTLE.equals(t)
                && !com.erp.finance.account.CustomerAccountConst.RECEIPT_ADVANCE.equals(t)
                && !com.erp.finance.account.CustomerAccountConst.RECEIPT_ADVANCE_REFUND.equals(t)) {
            throw new IllegalArgumentException("收款类型不正确：" + t);
        }
        if (!com.erp.finance.account.CustomerAccountConst.RECEIPT_SETTLE.equals(t)
                && !"CUSTOMER".equals(cpType)) {
            throw new IllegalArgumentException("预收收款/预收退款的往来单位必须是客户");
        }
        return t;
    }

    private static String receiptTypeText(String t) {
        if (com.erp.finance.account.CustomerAccountConst.RECEIPT_ADVANCE.equals(t)) return "预收收款";
        if (com.erp.finance.account.CustomerAccountConst.RECEIPT_ADVANCE_REFUND.equals(t)) return "预收退款";
        return "应收结算";
    }

    /**
     * PRD-36 M2：付款类型归一化。空值（历史数据/前端未传）按 SETTLE 应付结算；
     * 预付付款/预付退款的往来单位必须是供应商。
     */
    private static String normalizePaymentType(String raw, String cpType) {
        String t = raw == null || raw.isBlank()
                ? com.erp.finance.account.SupplierAccountConst.PAYMENT_SETTLE : raw.trim();
        if (!com.erp.finance.account.SupplierAccountConst.PAYMENT_SETTLE.equals(t)
                && !com.erp.finance.account.SupplierAccountConst.PAYMENT_PREPAY.equals(t)
                && !com.erp.finance.account.SupplierAccountConst.PAYMENT_PREPAY_REFUND.equals(t)) {
            throw new IllegalArgumentException("付款类型不正确：" + t);
        }
        if (!com.erp.finance.account.SupplierAccountConst.PAYMENT_SETTLE.equals(t)
                && !"SUPPLIER".equals(cpType)) {
            throw new IllegalArgumentException("预付付款/预付退款的往来单位必须是供应商");
        }
        return t;
    }

    private static String paymentTypeText(String t) {
        if (com.erp.finance.account.SupplierAccountConst.PAYMENT_PREPAY.equals(t)) return "预付付款";
        if (com.erp.finance.account.SupplierAccountConst.PAYMENT_PREPAY_REFUND.equals(t)) return "预付退款";
        return "应付结算";
    }

    // ============================================================
    // PRD-28 卡片7：财务数据范围 / 金额脱敏 / 往来单位写守卫
    // ============================================================

    /** 应收金额类 key → VIEW_AR_BALANCE（注册表只自动覆盖 arAmount，其余调用点显式映射）。 */
    private static final Map<String, String> AR_AMOUNT_KEYS = Map.of(
            "receivedAmount", "VIEW_AR_BALANCE",
            "unreceivedAmount", "VIEW_AR_BALANCE",
            "settlementAmount", "VIEW_AR_BALANCE");
    /** 应付金额类 key → VIEW_AP_BALANCE。 */
    private static final Map<String, String> AP_AMOUNT_KEYS = Map.of(
            "paidAmount", "VIEW_AP_BALANCE",
            "unpaidAmount", "VIEW_AP_BALANCE",
            "invoicedAmount", "VIEW_AP_BALANCE",
            "settlementAmount", "VIEW_AP_BALANCE");
    /** 资金/费用金额类 key → VIEW_FUND_FLOW（收付款单、费用单、资金流水的金额列）。 */
    private static final Map<String, String> FUND_AMOUNT_KEYS = Map.of(
            "totalAmount", "VIEW_FUND_FLOW",
            "verifiedAmount", "VIEW_FUND_FLOW",
            "amount", "VIEW_FUND_FLOW",
            "totalTaxAmount", "VIEW_FUND_FLOW",
            "totalExcludingTaxAmount", "VIEW_FUND_FLOW",
            "taxAmount", "VIEW_FUND_FLOW",
            "excludingTaxAmount", "VIEW_FUND_FLOW",
            "price", "VIEW_FUND_FLOW",
            "reconcileAmount", "VIEW_FUND_FLOW",
            "billAmount", "VIEW_FUND_FLOW");
    /**
     * 客户对账单（主表 + 明细递归脱敏）：对账金额/未收属应收视角，已收属资金视角，
     * 核销/抹零（writeOffAmount 注册表自动命中 VIEW_SETTLE_DETAIL）属结算明细视角。
     */
    private static final Map<String, String> CS_MASK_KEYS = Map.ofEntries(
            Map.entry("receivedAmount", "VIEW_AR_BALANCE"),
            Map.entry("unreceivedAmount", "VIEW_AR_BALANCE"),
            Map.entry("settlementAmount", "VIEW_AR_BALANCE"),
            Map.entry("totalAmount", "VIEW_AR_BALANCE"),
            Map.entry("unpaidAmount", "VIEW_AR_BALANCE"),
            Map.entry("billAmount", "VIEW_AR_BALANCE"),
            Map.entry("paidAmount", "VIEW_FUND_FLOW"),
            Map.entry("reconcileAmount", "VIEW_SETTLE_DETAIL"));
    /** 供应商对账单：同客户对账单口径，往来金额改应付视角（已付款金额同样是资金视角）。 */
    private static final Map<String, String> SS_MASK_KEYS = Map.ofEntries(
            Map.entry("unpaidAmount", "VIEW_AP_BALANCE"),
            Map.entry("settlementAmount", "VIEW_AP_BALANCE"),
            Map.entry("totalAmount", "VIEW_AP_BALANCE"),
            Map.entry("billAmount", "VIEW_AP_BALANCE"),
            Map.entry("paidAmount", "VIEW_FUND_FLOW"),
            Map.entry("reconcileAmount", "VIEW_SETTLE_DETAIL"));

    /**
     * 混合往来单位表（counterparty_type=CUSTOMER/SUPPLIER/COUNTERPARTY）按数据范围收窄：
     * 客户行走客户维度、供应商行走供应商维度（两分支 OR）；往来单位行无对应数据维度，
     * 有菜单即可见。角色未配任何维度时退回建档人过滤（creatorCol 为 null 则 fail-closed 1=0）。
     */
    private void appendCpScope(StringBuilder sql, List<Object> args,
                               String typeCol, String nameCol, String creatorCol) {
        var base = creatorCol == null ? dataScope.target().build()
                : dataScope.target().creator(creatorCol).build();
        if (base.isDenyAll()) { sql.append(" AND 1=0"); return; }
        if (!base.predicateSql().isEmpty()) { base.appendTo(sql, args); return; }
        var cust = dataScope.target().customer(nameCol).build();
        var supp = dataScope.target().supplier(nameCol).build();
        List<String> ors = new ArrayList<>();
        List<Object> gateArgs = new ArrayList<>();
        if (!cust.isDenyAll()) {
            if (cust.predicateSql().isEmpty()) ors.add(typeCol + " = 'CUSTOMER'");
            else { ors.add("(" + typeCol + " = 'CUSTOMER' AND " + cust.predicateSql() + ")"); gateArgs.addAll(cust.predicateParams()); }
        }
        if (!supp.isDenyAll()) {
            if (supp.predicateSql().isEmpty()) ors.add(typeCol + " = 'SUPPLIER'");
            else { ors.add("(" + typeCol + " = 'SUPPLIER' AND " + supp.predicateSql() + ")"); gateArgs.addAll(supp.predicateParams()); }
        }
        ors.add("(" + typeCol + " IS NULL OR " + typeCol + " NOT IN ('CUSTOMER','SUPPLIER'))");
        sql.append(" AND (").append(String.join(" OR ", ors)).append(")");
        args.addAll(gateArgs);
    }

    /** 写操作往来单位可见性：客户行校客户范围、供应商行校供应商范围；往来单位(COUNTERPARTY)无维度不拦。 */
    private void assertCpVisible(String type, String name) {
        if (name == null || name.isBlank() || type == null) return;
        boolean cust = "CUSTOMER".equals(type);
        if (!cust && !"SUPPLIER".equals(type)) return;
        String col = cust ? "customer_name" : "supplier_name";
        var scope = cust ? dataScope.target().customer(col).build()
                        : dataScope.target().supplier(col).build();
        if (scope.isDenyAll()) throw new com.erp.common.security.PermissionDeniedException("无该往来单位的操作权限");
        if (scope.predicateSql().isEmpty()) return;
        List<Object> a = new ArrayList<>();
        a.add(name);
        a.addAll(scope.predicateParams());
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + (cust ? "base_customer" : "base_supplier")
                        + " WHERE " + col + " = ? AND " + scope.predicateSql(),
                Integer.class, a.toArray());
        if (cnt == null || cnt == 0) {
            throw new com.erp.common.security.PermissionDeniedException("无该往来单位的操作权限：" + name);
        }
    }

    /** 对账单据头/收付款单头加载后校验往来单位可见性（counterparty_type/name 两列）。 */
    private void assertHeadCpVisible(Map<String, Object> head) {
        assertCpVisible(str(head.get("counterpartyType")), str(head.get("counterpartyName")));
    }

    @RequirePerm(value = "fin.ar_detail.view", name = "查看")
    @PostMapping("/ar/page")
    public ApiResponse<PageResult<Map<String, Object>>> arPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        // 数据范围（PRD-28 §5.3）：客户 + 业务员（应收表无仓库/建档人列）
        var scope = dataScope.target()
                .customer("a.customer").salesman("COALESCE(c.salesman, a.salesman)")
                .build();
        // 投影必须带 c.customer_code：SQL 按客户编码过滤命中后，PageResult.of 还会用过滤值
        // 对行文本做内存兜底匹配，缺了编码列会把按编码搜索的结果全部二次过滤掉。
        StringBuilder sql = new StringBuilder("""
                SELECT a.ar_no, a.customer, c.customer_code AS customer_code,
                       COALESCE(c.salesman, a.salesman) AS salesman,
                       a.source_bill, a.ar_amount, a.received_amount, a.unreceived_amount,
                       a.due_date, a.overdue_days, a.invoice_status, a.reconcile_status, a.created_at,
                       CASE a.status WHEN 'VERIFIED' THEN '已核销' ELSE '未核销' END status
                FROM fin_ar a
                LEFT JOIN base_customer c ON c.customer_name = a.customer
                WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
        scope.appendTo(sql, args);
        String customer = trimF(filters, "customer", "客户");
        if (!customer.isEmpty()) { sql.append(" AND (a.customer LIKE ? OR c.customer_code LIKE ?)"); args.add("%"+customer+"%"); args.add("%"+customer+"%"); }
        String status = trimF(filters, "status", "核销状态");
        if (!status.isEmpty()) {
            if ("已核销".equals(status)) sql.append(" AND a.status = 'VERIFIED'");
            else if ("未核销".equals(status)) sql.append(" AND a.status = 'UNVERIFIED'");
        }
        String reconcile = trimF(filters, "reconcileStatus", "对账状态");
        if (!reconcile.isEmpty()) { sql.append(" AND a.reconcile_status = ?"); args.add(reconcile); }
        String dateFrom = trimF(filters, "dateFrom");
        if (!dateFrom.isEmpty()) { sql.append(" AND a.created_at >= ?"); args.add(dateFrom + " 00:00:00"); }
        String dateTo = trimF(filters, "dateTo");
        if (!dateTo.isEmpty()) { sql.append(" AND a.created_at <= ?"); args.add(dateTo + " 23:59:59"); }
        sql.append(" ORDER BY a.ar_no DESC");

        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            String rs = str(r.get("reconcileStatus"));
            r.put("reconcileStatusText", rs == null || rs.isEmpty() || "未对账".equals(rs) ? "未对账"
                    : "对账中".equals(rs) ? "对账中" : "已对账".equals(rs) ? "已对账" : rs);
        }
        fieldMasker.mask(rows, AR_AMOUNT_KEYS);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 收款结算：生成收款单并审核，更新 AR 已收金额，抹零生成费用单 */
    @RequirePerm(value = "fin.ar_settle.settle", name = "结算")
    @PostMapping("/ar/settle")
    @Transactional
    public ApiResponse<Map<String, Object>> arSettle(@RequestBody Map<String, Object> body) {
        String receiptDate = str(body.get("receiptDate"));
        String summary = str(body.get("summary"));
        String handler = str(body.get("handler"));
        BigDecimal writeOff = toBd(body.get("writeOff"));
        String writeOffExpType = str(body.get("writeOffExpenseType"));
        Object acctsRaw = body.get("accounts");
        Object arListRaw = body.get("arList");
        if (!(arListRaw instanceof List<?> list) || list.isEmpty())
            return ApiResponse.fail("400", "请选择要结算的应收单据");
        // PRD-35 M3：本次使用预收（≤ min(结算净额, 客户预收余额)，预收优先冲最早到期行）
        BigDecimal useAdvance = toBd(body.get("useAdvanceAmount"));
        if (useAdvance == null || useAdvance.signum() < 0) useAdvance = BigDecimal.ZERO;
        // 收款金额 = 账户实收合计
        BigDecimal acctTotal = BigDecimal.ZERO;
        if (acctsRaw instanceof List<?> al) for (Object o : al) if (o instanceof Map<?,?> am) acctTotal = acctTotal.add(toBd(am.get("amount")));
        // 申请结算总额（弹窗逐行手工录入）
        BigDecimal grossSettle = BigDecimal.ZERO;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) grossSettle = grossSettle.add(toBd(m.get("settleAmount")));
        }

        Map<String, java.util.List<Map<String, Object>>> byCustomer = new java.util.LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            List<Map<String, Object>> ars = queryCamel("SELECT * FROM fin_ar WHERE ar_no = ?", str(m.get("arNo")));
            if (ars.isEmpty()) continue;
            Map<String, Object> ar = ars.get(0);
            byCustomer.computeIfAbsent(str(ar.get("customer")), k -> new java.util.ArrayList<>()).add(ar);
        }
        // PRD-28：写操作往来单位范围校验，禁止直接构造请求核销不可见客户的应收
        byCustomer.keySet().forEach(c -> assertCpVisible("CUSTOMER", c));
        java.sql.Date settleDate = java.sql.Date.valueOf(receiptDate.isEmpty() ? LocalDate.now().toString() : receiptDate);
        // 结算记账按结算日期判封单（原 /ar/settle 漏了封单守卫，M3 补齐，与对账单结算一致）
        dayCloseGuard.assertWritable(settleDate.toLocalDate(), "应收结算", "");
        boolean useAdv = useAdvance.signum() > 0;
        if (useAdv && byCustomer.size() > 1) {
            throw new IllegalArgumentException("跨客户结算不能使用预收，请按客户分别结算");
        }
        BigDecimal netSettle = grossSettle.subtract(writeOff);
        if (useAdv && useAdvance.compareTo(netSettle) > 0) {
            throw new IllegalArgumentException("使用预收金额不能超过本次结算净额 " + netSettle + " 元");
        }
        BigDecimal expectedCash = netSettle.subtract(useAdvance);
        if (expectedCash.signum() < 0) {
            throw new IllegalArgumentException("抹零与使用预收合计不能超过结算总额");
        }
        if (acctTotal.compareTo(expectedCash) != 0) {
            throw new IllegalArgumentException("资金账户合计 " + acctTotal.toPlainString()
                    + " 元须等于净额扣减预收后的金额 " + expectedCash.toPlainString() + " 元");
        }
        LocalDateTime now = LocalDateTime.now(); String op = currentUser();
        int created = 0;
        String lastReceiptNo = "";
        java.util.List<String> autoWriteoffNos = new java.util.ArrayList<>();

        for (Map.Entry<String, java.util.List<Map<String, Object>>> entry : byCustomer.entrySet()) {
            String custName = entry.getKey();
            // 弹窗逐行金额（arNo → settleAmount），过滤掉 0 额行
            Map<String, BigDecimal> reqAmount = new java.util.LinkedHashMap<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                BigDecimal amt = toBd(m.get("settleAmount"));
                if (amt.signum() > 0) reqAmount.put(str(m.get("arNo")), amt);
            }
            java.util.List<Map<String, Object>> targetRows = new java.util.ArrayList<>();
            for (Map<String, Object> ar : entry.getValue()) {
                String arNo = str(ar.get("arNo"));
                if (!reqAmount.containsKey(arNo)) continue;
                BigDecimal unreceived = toBd(ar.get("unreceivedAmount"));
                if (reqAmount.get(arNo).compareTo(unreceived) > 0) {
                    throw new IllegalArgumentException("应收单 " + arNo + " 本次结算额 "
                            + reqAmount.get(arNo) + " 元超过未收余额 " + unreceived + " 元");
                }
                targetRows.add(ar);
            }
            // FIFO：预收优先冲最早到期行（到期日为空排最后），现金接续
            targetRows.sort(java.util.Comparator.comparing(
                    (Map<String, Object> a) -> a.get("dueDate") instanceof java.sql.Date d ? d : java.sql.Date.valueOf("9999-12-31")));
            BigDecimal advRemain = useAdv ? useAdvance : BigDecimal.ZERO;
            BigDecimal cashTotal = BigDecimal.ZERO;
            Map<String, BigDecimal> advPartMap = new java.util.LinkedHashMap<>();
            for (Map<String, Object> ar : targetRows) {
                BigDecimal settleAmt = reqAmount.get(str(ar.get("arNo")));
                BigDecimal advPart = settleAmt.min(advRemain);
                advRemain = advRemain.subtract(advPart);
                BigDecimal cashPart = settleAmt.subtract(advPart);
                if (advPart.signum() > 0) advPartMap.put(str(ar.get("arNo")), advPart);
                cashTotal = cashTotal.add(cashPart);
            }
            if (advRemain.signum() > 0) {
                // 理论上前端已按 min(净额,余额) 限制；防御性报错，避免预收凭空多出
                throw new IllegalArgumentException("预收金额超出本次可核销的应收金额，剩余 " + advRemain + " 元");
            }

            // 始终生成收款单（即便全额预收，金额为 0）：作为结算凭证与反审核入口，
            // 自动 XH 单 source_bill_no 挂 SK 单号，反审核收款单即级联反核销预收
            String receiptNo = billNoGen.nextNo("SK", "fin_receipt_bill", "receipt_no");
            lastReceiptNo = receiptNo;
            String receiptId = "SK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String firstAcct = "默认账户";
            if (acctsRaw instanceof List<?> al2 && !al2.isEmpty() && al2.get(0) instanceof Map<?,?> am2) firstAcct = str(am2.get("fundAccount"));
            jdbcTemplate.update("""
                        INSERT INTO fin_receipt_bill(receipt_id,receipt_no,receipt_date,status,counterparty_type,counterparty_code,counterparty_name,object_name,total_amount,verified_amount,fund_account,amount,business_source,handler,related_bill_no,summary,creator_name,create_time,auditor_name,audit_time)
                        VALUES(?,?,?,'APPROVED','CUSTOMER',?,?,?,?,?,?,?,'AR_SETTLE',?,'',?,?,?,?,?)""",
                    receiptId,receiptNo,settleDate,custName,custName,custName,cashTotal,cashTotal,firstAcct,cashTotal,handler,summary,op,java.sql.Timestamp.valueOf(now),op,java.sql.Timestamp.valueOf(now));
            // 核销 AR：现金部分本端点 fin_ar + 核销记录 + 在线流水；
            // 预收部分汇总到自动 XH 单（XH 审核自行更新预收行的 fin_ar 真值，这里不能重复加）
            String customerCode = customerAccountService.resolveArCustomerCode(targetRows.get(0));
            java.util.List<Map<String, Object>> autoLines = new java.util.ArrayList<>();
            java.util.List<com.erp.finance.account.CustomerAccountService.ArCashLine> cashLines = new java.util.ArrayList<>();
            for (Map<String, Object> ar : targetRows) {
                String arNo = str(ar.get("arNo"));
                BigDecimal settleAmt = reqAmount.get(arNo);
                BigDecimal advPart = advPartMap.getOrDefault(arNo, BigDecimal.ZERO);
                BigDecimal cashPart = settleAmt.subtract(advPart);
                if (cashPart.signum() > 0) {
                    BigDecimal newReceived = toBd(ar.get("receivedAmount")).add(cashPart);
                    BigDecimal newUnreceived = toBd(ar.get("arAmount")).subtract(newReceived);
                    jdbcTemplate.update("UPDATE fin_ar SET received_amount=?,unreceived_amount=?,status=? WHERE ar_no=?",
                            newReceived,newUnreceived,newUnreceived.signum()<=0?"VERIFIED":"UNVERIFIED",arNo);
                    String recordId = writeReconcileRecordV2(receiptNo,settleDate,arNo,str(ar.get("sourceBill")),"AR_SETTLE",str(ar.get("dueDate")),"CUSTOMER",custName,custName,cashPart,summary,"");
                    cashLines.add(new com.erp.finance.account.CustomerAccountService.ArCashLine(
                            recordId, arNo, str(ar.get("sourceBill")), cashPart, receiptNo));
                }
                if (advPart.signum() > 0) {
                    Map<String, Object> line = new java.util.LinkedHashMap<>();
                    line.put("arNo", arNo);
                    line.put("amount", advPart);
                    autoLines.add(line);
                }
            }
            // 现金部分在线写客户账户 AR 结算流水（bizKey 与数据修复同源，幂等不双补）
            customerAccountService.postArCashSettle(settleDate.toLocalDate(), customerCode, custName, cashLines);
            // 回写发货单收款状态（按来源单聚合 fin_ar：未收款/部分收款/已收款）
            cashLines.forEach(l -> advanceWriteoffService.refreshReceiveStatus(l.sourceBill));
            // 预收部分：自动生成并审核 XH 单（source_bill_no 记 SK 单号，供反审核级联）
            if (!autoLines.isEmpty()) {
                String xhNo = advanceWriteoffService.createAutoFromSettle(customerCode, custName,
                        settleDate.toLocalDate(), handler,
                        com.erp.finance.account.AdvanceWriteoffService.SOURCE_AR_SETTLE,
                        receiptNo, summary, autoLines);
                if (!xhNo.isEmpty()) autoWriteoffNos.add(xhNo);
            }
            // 抹零生成费用单并自动审核 + 写核销
            if (writeOff.signum() != 0 && !writeOffExpType.isEmpty()) {
                String expId = "FE"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase();
                String expNo = billNoGen.nextNo("FE","fin_expense_bill","expense_no");
                String direction = writeOff.signum() > 0 ? "OUT" : "IN";
                // 19 列，14 个 ? + 2 个字面值 + CURRENT_DATE
                jdbcTemplate.update("""
                        INSERT INTO fin_expense_bill(expense_id,expense_no,expense_date,direction,status,
                            counterparty_type,counterparty_code,counterparty_name,
                            handler,total_amount,business_source,remark,
                            creator_name,create_time,auditor_name,audit_time,
                            object_name,expense_type,amount)
                        VALUES(?,?,CURRENT_DATE,?,'APPROVED',
                            'CUSTOMER',?,?,
                            ?,?,'AR_WRITEOFF',?,
                            ?,?,?,?,
                            ?,?,?)
                        """,
                        expId,expNo,direction,
                        custName,custName,
                        handler,writeOff.abs(),"应收结算抹零",
                        op,java.sql.Timestamp.valueOf(now),op,java.sql.Timestamp.valueOf(now),
                        custName,writeOffExpType,writeOff.abs());
                jdbcTemplate.update("INSERT INTO fin_expense_detail(detail_id,expense_id,expense_type,amount,remark,sort_order) VALUES(?,?,?,?,?,1)",
                        "FED"+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(),expId,writeOffExpType,writeOff.abs(),"抹零");
                // 抹零费用单也生成核销流水
                writeReconcileRecordV2(receiptNo,settleDate,expNo,expNo,"EXPENSE_WRITEOFF","","CUSTOMER",custName,custName,writeOff,summary,"抹零");
            }
            created++;
        }
        finLog(com.erp.system.OperationModule.FIN_RECEIPT, com.erp.system.OperationAction.WRITE_OFF,
                com.erp.system.KeyFields.BIZ_FIN_RECEIPT, lastReceiptNo, "核销应收 " + lastReceiptNo
                        + (useAdv ? "（含预收核销 " + useAdvance + " 元，自动单 " + String.join(",", autoWriteoffNos) + "）" : ""));
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("created", created);
        resp.put("receiptAmount", acctTotal);
        resp.put("advanceAmount", useAdvance);
        resp.put("writeoffNos", autoWriteoffNos);
        return ApiResponse.ok(resp);
    }

    @RequirePerm(value = "fin.ap.view", name = "查看")
    @PostMapping("/ap/page")
    public ApiResponse<PageResult<Map<String, Object>>> apPage(@RequestBody PageRequest request) {
        // 数据范围（PRD-28 §5.3）：供应商维度（AP 表无客户/业务员/建档人列）
        var scope = dataScope.target().supplier("supplier").build();
        if (scope.isDenyAll()) return ApiResponse.ok(PageResult.of(java.util.List.of(), request));
        StringBuilder sql = new StringBuilder("""
                SELECT ap_no, supplier, source_bill,
                       ap_amount, paid_amount, unpaid_amount, due_date,
                       invoiced_amount, invoice_status,
                       CASE status WHEN 'VERIFIED' THEN '已核销' ELSE '未核销' END status
                FROM fin_ap
                WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
        scope.appendTo(sql, args);
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        String supplier = trimF(filters, "supplier", "供应商");
        if (!supplier.isEmpty()) { sql.append(" AND supplier LIKE ?"); args.add("%"+supplier+"%"); }
        String status = trimF(filters, "status", "核销状态");
        if (!status.isEmpty()) {
            if ("已核销".equals(status)) sql.append(" AND status = 'VERIFIED'");
            else if ("未核销".equals(status)) sql.append(" AND status = 'UNVERIFIED'");
        }
        sql.append(" ORDER BY ap_no DESC");
        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        fieldMasker.mask(rows, AP_AMOUNT_KEYS);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @RequirePerm(value = "fin.receipt.view", name = "查看")
    @PostMapping("/receipt-payment/page")
    public ApiResponse<PageResult<Map<String, Object>>> receiptPaymentPage(@RequestBody PageRequest request) {
        // PRD-28：UNION 两分支分别挂往来单位数据范围（客户行走客户维度/供应商行走供应商维度）
        StringBuilder gR = new StringBuilder(); List<Object> aR = new java.util.ArrayList<>();
        appendCpScope(gR, aR, "r.counterparty_type", "r.counterparty_name", "r.creator_name");
        StringBuilder gP = new StringBuilder(); List<Object> aP = new java.util.ArrayList<>();
        appendCpScope(gP, aP, "p.counterparty_type", "p.counterparty_name", "p.creator_name");
        if (gR.toString().contains("1=0") && gP.toString().contains("1=0"))
            return ApiResponse.ok(PageResult.of(java.util.List.of(), request));
        StringBuilder sql = new StringBuilder("""
                SELECT receipt_no bill_no,
                       '收款单' bill_type,
                       object_name,
                       fund_account,
                       amount,
                       verified_amount,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM fin_receipt_bill r
                WHERE 1=1
                """);
        sql.append(gR).append("""
                UNION ALL
                SELECT payment_no bill_no,
                       '付款单' bill_type,
                       object_name,
                       fund_account,
                       amount,
                       verified_amount,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM fin_payment_bill p
                WHERE 1=1
                """);
        sql.append(gP).append(" ORDER BY bill_no DESC");
        List<Object> args = new java.util.ArrayList<>();
        args.addAll(aR); args.addAll(aP);
        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        fieldMasker.mask(rows, FUND_AMOUNT_KEYS);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 收款单创建（V32 重构：完整字段 + 明细行） */
    @RequirePerm(value = "fin.receipt.add", name = "新增")
    @PostMapping("/receipt/create")
    public ApiResponse<Map<String, Object>> createReceipt(@RequestBody Map<String, Object> body) {
        String receiptId = "SK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String receiptNo = billNoGen.nextNo("SK", "fin_receipt_bill", "receipt_no");
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUser();
        BigDecimal total = sumDetails(body);
        String cpName = str(body.get("counterpartyName"));
        assertCpVisible(str(body.get("counterpartyType")), cpName);
        // PRD-35：收款类型 SETTLE/ADVANCE/ADVANCE_REFUND（预收类强制客户往来）
        String receiptType = normalizeReceiptType(str(body.get("receiptType")),
                str(body.get("counterpartyType")));
        // 老列 object_name / fund_account / amount 仍有 NOT NULL 约束（V1 schema），
        // 新设计下这些信息存在明细行里，这里取值填上保证写入不报错
        String firstFundAcct = "";
        Object raw = body.get("details");
        if (raw instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m)
            firstFundAcct = str(m.get("fundAccount"));
        jdbcTemplate.update("""
                INSERT INTO fin_receipt_bill(receipt_id, receipt_no, receipt_date, status,
                    counterparty_type, counterparty_code, counterparty_name,
                    handler, related_bill_no, summary, business_source,
                    total_amount, verified_amount,
                    object_name, fund_account, amount, receipt_type,
                    creator_name, create_time)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, 'BACKOFFICE', ?, 0, ?, ?, ?, ?, ?, ?)
                """, receiptId, receiptNo, date(body, "receiptDate"),
                str(body.get("counterpartyType")), str(body.get("counterpartyCode")),
                cpName, str(body.get("handler")),
                str(body.get("relatedBillNo")), str(body.get("summary")),
                total, cpName, firstFundAcct, total, receiptType,
                operator, java.sql.Timestamp.valueOf(now));
        insertDetails(receiptId, body);
        finLog(com.erp.system.OperationModule.FIN_RECEIPT, com.erp.system.OperationAction.CREATE,
                com.erp.system.KeyFields.BIZ_FIN_RECEIPT, receiptNo, "新增收款单 " + receiptNo);
        return ApiResponse.ok(GenericResult.row("receiptId", receiptId, "receiptNo", receiptNo));
    }

    /** 付款单创建（V32 重构：完整字段 + 明细行） */
    @RequirePerm(value = "fin.payment.add", name = "新增")
    @PostMapping("/payment/create")
    public ApiResponse<Map<String, Object>> createPayment(@RequestBody Map<String, Object> body) {
        String paymentId = "FK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String paymentNo = billNoGen.nextNo("FK", "fin_payment_bill", "payment_no");
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUser();
        BigDecimal total = sumDetails(body);
        String cpName = str(body.get("counterpartyName"));
        assertCpVisible(str(body.get("counterpartyType")), cpName);
        // PRD-36 M2：付款类型 SETTLE/PREPAY/PREPAY_REFUND（预付类强制供应商往来）
        String paymentType = normalizePaymentType(str(body.get("paymentType")),
                str(body.get("counterpartyType")));
        String firstFundAcct = "";
        Object raw = body.get("details");
        if (raw instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m)
            firstFundAcct = str(m.get("fundAccount"));
        jdbcTemplate.update("""
                INSERT INTO fin_payment_bill(payment_id, payment_no, payment_date, status,
                    counterparty_type, counterparty_code, counterparty_name,
                    handler, related_bill_no, summary, business_source,
                    total_amount, verified_amount,
                    object_name, fund_account, amount, payment_type,
                    creator_name, create_time)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, 'BACKOFFICE', ?, 0, ?, ?, ?, ?, ?, ?)
                """, paymentId, paymentNo, date(body, "paymentDate"),
                str(body.get("counterpartyType")), str(body.get("counterpartyCode")),
                cpName, str(body.get("handler")),
                str(body.get("relatedBillNo")), str(body.get("summary")),
                total, cpName, firstFundAcct, total, paymentType,
                operator, java.sql.Timestamp.valueOf(now));
        insertPaymentDetails(paymentId, body);
        finLog(com.erp.system.OperationModule.FIN_PAYMENT, com.erp.system.OperationAction.CREATE,
                com.erp.system.KeyFields.BIZ_FIN_PAYMENT, paymentNo, "新增付款单 " + paymentNo);
        return ApiResponse.ok(GenericResult.row("paymentId", paymentId, "paymentNo", paymentNo));
    }

    @RequirePerm(value = "fin.fund_flow.view", name = "查看")
    @PostMapping("/fund-ledger/page")
    public ApiResponse<PageResult<Map<String, Object>>> fundLedgerPage(@RequestBody PageRequest request) {
        // PRD-28：资金流水无往来单位列，按操作人收窄（未配维度仅见本人经手）；金额/账户余额脱敏
        var scope = dataScope.target().creator("operator_name").build();
        if (scope.isDenyAll()) return ApiResponse.ok(PageResult.of(java.util.List.of(), request));
        StringBuilder sql = new StringBuilder("""
                SELECT ledger_no, fund_account, direction, amount,
                       source_bill, balance_after, occurred_at, operator_name
                FROM fin_fund_ledger
                WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
        scope.appendTo(sql, args);
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        String fundAccount = trimF(filters, "fundAccount");
        if (!fundAccount.isEmpty()) { sql.append(" AND fund_account LIKE ?"); args.add("%"+fundAccount+"%"); }
        String direction = trimF(filters, "direction");
        if (!direction.isEmpty()) { sql.append(" AND direction = ?"); args.add(direction); }
        String dateFrom = trimF(filters, "dateFrom");
        if (!dateFrom.isEmpty()) { sql.append(" AND occurred_at >= ?"); args.add(dateFrom + " 00:00:00"); }
        String dateTo = trimF(filters, "dateTo");
        if (!dateTo.isEmpty()) { sql.append(" AND occurred_at <= ?"); args.add(dateTo + " 23:59:59"); }
        sql.append(" ORDER BY occurred_at DESC");
        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        java.util.Map<String, String> keys = new java.util.HashMap<>(FUND_AMOUNT_KEYS);
        keys.put("balanceAfter", "VIEW_FUND_ACCOUNT_BALANCE");
        fieldMasker.mask(rows, keys);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @RequirePerm(value = "fin.ar_settle.view", name = "查看")
    @PostMapping("/ar-settlement/page")
    public ApiResponse<PageResult<Map<String, Object>>> arSettlementPage(@RequestBody PageRequest request) {
        // PRD-28：按客户数据范围收窄；结算金额随应收余额脱敏
        var scope = dataScope.target().customer("customer").build();
        if (scope.isDenyAll()) return ApiResponse.ok(PageResult.of(java.util.List.of(), request));
        // 日期前缀在 Java 层拼接：H2 MODE=MySQL 不支持 DATE_FORMAT（旧写法在 H2 下整页 500）
        String day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        StringBuilder sql = new StringBuilder("""
                SELECT CONCAT('ARS', ?, '0001') settlementNo,
                       customer,
                       SUM(unreceived_amount) settlementAmount,
                       0.00 discountAmount,
                       '待审核' status
                FROM fin_ar
                WHERE status <> 'VERIFIED'
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(day);
        scope.appendTo(sql, args);
        sql.append(" GROUP BY customer");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        fieldMasker.mask(rows, AR_AMOUNT_KEYS);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @RequirePerm(value = "fin.ap_settle.view", name = "查看")
    @PostMapping("/ap-settlement/page")
    public ApiResponse<PageResult<Map<String, Object>>> apSettlementPage(@RequestBody PageRequest request) {
        // PRD-28：按供应商数据范围收窄；结算金额随应付余额脱敏
        var scope = dataScope.target().supplier("supplier").build();
        if (scope.isDenyAll()) return ApiResponse.ok(PageResult.of(java.util.List.of(), request));
        // 日期前缀在 Java 层拼接：H2 MODE=MySQL 不支持 DATE_FORMAT（旧写法在 H2 下整页 500）
        String day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        StringBuilder sql = new StringBuilder("""
                SELECT CONCAT('APS', ?, '0001') settlementNo,
                       supplier,
                       SUM(unpaid_amount) settlementAmount,
                       0.00 discountAmount,
                       '待审核' status
                FROM fin_ap
                WHERE status <> 'VERIFIED'
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(day);
        scope.appendTo(sql, args);
        sql.append(" GROUP BY supplier");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        fieldMasker.mask(rows, AP_AMOUNT_KEYS);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    // ============================================================
    // 费用单 V38 重构
    // ============================================================

    @RequirePerm(value = "fin.fee.view", name = "查看")
    @PostMapping("/expense/page")
    public ApiResponse<PageResult<Map<String, Object>>> expensePage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT e.expense_id, e.expense_no, e.expense_date, e.direction, e.status,
                       e.counterparty_type, e.counterparty_code, e.counterparty_name,
                       e.handler, e.department, e.total_amount, e.total_tax_amount, e.total_excluding_tax_amount,
                       e.business_source, e.related_bill_no, e.external_voucher_no, e.fund_account, e.remark,
                       e.creator_name, e.create_time, e.auditor_name, e.audit_time
                FROM fin_expense_bill e
                WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
        // PRD-28：往来单位多态列按客户/供应商维度收窄，未配维度仅见本人建档
        appendCpScope(sql, args, "e.counterparty_type", "e.counterparty_name", "e.creator_name");
        String cpType = trimF(filters, "counterpartyType");
        if (!cpType.isEmpty()) { sql.append(" AND e.counterparty_type = ?"); args.add(cpType); }
        String cpName = trimF(filters, "counterparty");
        if (!cpName.isEmpty()) { sql.append(" AND (e.counterparty_code LIKE ? OR e.counterparty_name LIKE ?)"); args.add("%"+cpName+"%"); args.add("%"+cpName+"%"); }
        String status = trimF(filters, "status");
        if (!status.isEmpty()) { sql.append(" AND e.status = ?"); args.add(status); }
        String dateFrom = trimF(filters, "dateFrom");
        if (!dateFrom.isEmpty()) { sql.append(" AND e.expense_date >= ?"); args.add(dateFrom); }
        String dateTo = trimF(filters, "dateTo");
        if (!dateTo.isEmpty()) { sql.append(" AND e.expense_date <= ?"); args.add(dateTo); }
        String remark = trimF(filters, "remark");
        if (!remark.isEmpty()) { sql.append(" AND e.remark LIKE ?"); args.add("%"+remark+"%"); }
        String relBill = trimF(filters, "relatedBillNo");
        if (!relBill.isEmpty()) { sql.append(" AND e.related_bill_no LIKE ?"); args.add("%"+relBill+"%"); }
        String voucher = trimF(filters, "externalVoucherNo");
        if (!voucher.isEmpty()) { sql.append(" AND e.external_voucher_no LIKE ?"); args.add("%"+voucher+"%"); }
        sql.append(" ORDER BY e.expense_no DESC");

        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("directionText", "IN".equals(str(r.get("direction"))) ? "收入" : "支出");
            String st = str(r.get("status"));
            r.put("statusText", "APPROVED".equals(st) ? "已审核" : "PENDING".equals(st) ? "待审核" : st);
            String src = str(r.get("businessSource"));
            r.put("businessSourceText", src.isEmpty() || "BACKOFFICE".equals(src) ? "后台创建" : src);
            String ct = str(r.get("counterpartyType"));
            r.put("counterpartyTypeText", "CUSTOMER".equals(ct) ? "客户" : "SUPPLIER".equals(ct) ? "供应商" : "COUNTERPARTY".equals(ct) ? "往来单位" : ct);
        }
        fieldMasker.mask(rows, FUND_AMOUNT_KEYS);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @RequirePerm(value = "fin.fee.view", name = "查看")
    @PostMapping("/expense/detail")
    public ApiResponse<Map<String, Object>> expenseDetail(@RequestBody Map<String, Object> body) {
        String id = str(body.get("expenseId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_expense_bill WHERE expense_id = ? OR expense_no = ?", id, id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "费用单不存在");
        Map<String, Object> head = heads.get(0);
        assertHeadCpVisible(head);
        head.put("details", queryCamel("SELECT * FROM fin_expense_detail WHERE expense_id = ? ORDER BY sort_order", head.get("expenseId")));
        fieldMasker.mask(head, FUND_AMOUNT_KEYS);
        return ApiResponse.ok(head);
    }

    @RequirePerm(value = "fin.fee.add", name = "新增")
    @PostMapping("/expense/create")
    public ApiResponse<Map<String, Object>> createExpense(@RequestBody Map<String, Object> body) {
        String expenseId = "FE" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String expenseNo = billNoGen.nextNo("FE", "fin_expense_bill", "expense_no");
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUser();
        BigDecimal total = sumDetails(body);
        BigDecimal totalTax = sumDetailField(body, "taxAmount");
        BigDecimal totalExcluding = sumDetailField(body, "excludingTaxAmount");
        // 收支方向以表单选择为准（OUT=费用支出 / IN=其他收入）；前端默认 OUT
        String direction = "IN".equals(str(body.get("direction"))) ? "IN" : "OUT";
        assertCpVisible(str(body.get("counterpartyType")), str(body.get("counterpartyName")));
        // 取第一条明细的费用类型回填旧列 expense_type（NOT NULL），其他旧列有 DEFAULT
        String firstExpType = "其他";
        Object rd = body.get("details");
        if (rd instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m)
            firstExpType = str(m.get("expenseType"));
        jdbcTemplate.update("""
                INSERT INTO fin_expense_bill(expense_id, expense_no, expense_date, direction, status,
                    counterparty_type, counterparty_code, counterparty_name,
                    handler, department, related_bill_no, external_voucher_no,
                    business_source, fund_account, remark, total_amount,
                    total_tax_amount, total_excluding_tax_amount,
                    creator_name, create_time, object_name, expense_type, amount)
                VALUES (?, ?, ?, ?, 'PENDING',
                        ?, ?, ?,
                        ?, ?,
                        ?, ?,
                        'BACKOFFICE',
                        ?, ?, ?,
                        ?, ?,
                        ?, ?,
                        ?, ?, ?)
                """, expenseId, expenseNo, date(body, "expenseDate"), direction,
                str(body.get("counterpartyType")), str(body.get("counterpartyCode")), str(body.get("counterpartyName")),
                str(body.get("handler")), str(body.get("department")),
                str(body.get("relatedBillNo")), str(body.get("externalVoucherNo")),
                str(body.get("fundAccount")), str(body.get("remark")), total,
                totalTax, totalExcluding,
                operator, java.sql.Timestamp.valueOf(now),
                str(body.get("counterpartyName")), firstExpType, total);
        insertExpenseDetails(expenseId, body);
        finLog(com.erp.system.OperationModule.FIN_EXPENSE, com.erp.system.OperationAction.CREATE,
                com.erp.system.KeyFields.BIZ_FIN_EXPENSE, expenseNo, "新增费用单 " + expenseNo);
        return ApiResponse.ok(GenericResult.row("expenseId", expenseId, "expenseNo", expenseNo));
    }

    @RequirePerm(value = "fin.fee.edit", name = "修改")
    @PostMapping("/expense/update")
    public ApiResponse<Boolean> updateExpense(@RequestBody Map<String, Object> body) {
        String id = str(body.get("expenseId"));
        List<Map<String, Object>> exist = queryCamel("SELECT status, expense_no, counterparty_type, counterparty_name FROM fin_expense_bill WHERE expense_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "费用单不存在");
        if (!"PENDING".equals(str(exist.get(0).get("status")))) return ApiResponse.fail("400", "仅待审核单据可编辑");
        // PRD-28：旧往来单位与改后往来单位都须在数据范围内
        assertHeadCpVisible(exist.get(0));
        assertCpVisible(str(body.get("counterpartyType")), str(body.get("counterpartyName")));
        String expenseNo = str(exist.get(0).get("expenseNo"));
        BigDecimal total = sumDetails(body);
        BigDecimal totalTax = sumDetailField(body, "taxAmount");
        BigDecimal totalExcluding = sumDetailField(body, "excludingTaxAmount");
        jdbcTemplate.update("""
                UPDATE fin_expense_bill SET expense_date = ?, direction = ?, counterparty_type = ?,
                    counterparty_code = ?, counterparty_name = ?, handler = ?, department = ?,
                    related_bill_no = ?, external_voucher_no = ?, fund_account = ?,
                    remark = ?, total_amount = ?, total_tax_amount = ?, total_excluding_tax_amount = ?
                WHERE expense_id = ?
                """, date(body, "expenseDate"),
                "IN".equals(str(body.get("direction"))) ? "IN" : "OUT",
                str(body.get("counterpartyType")),
                str(body.get("counterpartyCode")), str(body.get("counterpartyName")),
                str(body.get("handler")), str(body.get("department")),
                str(body.get("relatedBillNo")), str(body.get("externalVoucherNo")),
                str(body.get("fundAccount")), str(body.get("remark")),
                total, totalTax, totalExcluding, id);
        jdbcTemplate.update("DELETE FROM fin_expense_detail WHERE expense_id = ?", id);
        insertExpenseDetails(id, body);
        finLog(com.erp.system.OperationModule.FIN_EXPENSE, com.erp.system.OperationAction.UPDATE,
                com.erp.system.KeyFields.BIZ_FIN_EXPENSE, expenseNo, "修改费用单 " + expenseNo);
        return ApiResponse.ok(true);
    }

    @RequirePerm(value = "fin.fee.delete", name = "删除")
    @PostMapping("/expense/delete")
    public ApiResponse<Boolean> deleteExpense(@RequestBody Map<String, Object> body) {
        String id = str(body.get("expenseId"));
        List<Map<String, Object>> exist = queryCamel("SELECT status, expense_no, counterparty_type, counterparty_name FROM fin_expense_bill WHERE expense_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "费用单不存在");
        if (!"PENDING".equals(str(exist.get(0).get("status")))) return ApiResponse.fail("400", "仅待审核单据可删除");
        assertHeadCpVisible(exist.get(0));
        String expenseNo = str(exist.get(0).get("expenseNo"));
        jdbcTemplate.update("DELETE FROM fin_expense_detail WHERE expense_id = ?", id);
        jdbcTemplate.update("DELETE FROM fin_expense_bill WHERE expense_id = ?", id);
        finLog(com.erp.system.OperationModule.FIN_EXPENSE, com.erp.system.OperationAction.DELETE,
                com.erp.system.KeyFields.BIZ_FIN_EXPENSE, expenseNo, "删除费用单 " + expenseNo);
        return ApiResponse.ok(true);
    }

    @RequirePerm(value = "fin.fee.audit", name = "审核")
    @PostMapping("/expense/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditExpense(@RequestBody Map<String, Object> body) {
        String id = str(body.get("expenseId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_expense_bill WHERE expense_id = ?", id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "费用单不存在");
        Map<String, Object> r = heads.get(0);
        if (!"PENDING".equals(str(r.get("status")))) return ApiResponse.fail("400", "仅待审核单据可审核");
        assertHeadCpVisible(r);
        LocalDateTime now = LocalDateTime.now();
        String auditor = currentUser();
        String expenseNo = str(r.get("expenseNo"));
        // v1.3：费用单记账日期不回填用户已填值；为空按当天并持久化；审核按该日期判封单
        boolean expenseDateMissing = !(r.get("expenseDate") instanceof java.sql.Date);
        java.sql.Date expenseDate = expenseDateMissing
                ? java.sql.Date.valueOf(LocalDate.now()) : (java.sql.Date) r.get("expenseDate");
        dayCloseGuard.assertWritable(expenseDate.toLocalDate(), "费用单", expenseNo);
        String cpType = str(r.get("counterpartyType"));
        String cpCode = str(r.get("counterpartyCode"));
        String cpName = str(r.get("counterpartyName"));
        String fundAcct = str(r.get("fundAccount"));
        BigDecimal total = toBd(r.get("totalAmount"));
        // 收支方向以单据存档为准（建单/编辑时按表单写入），不能再按金额正负推断
        String direction = str(r.get("direction"));
        if (direction.isEmpty()) direction = "OUT";
        if (expenseDateMissing) {
            jdbcTemplate.update("UPDATE fin_expense_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?, direction = ?, expense_date = ? WHERE expense_id = ?",
                    auditor, java.sql.Timestamp.valueOf(now), direction, expenseDate, id);
        } else {
            jdbcTemplate.update("UPDATE fin_expense_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?, direction = ? WHERE expense_id = ?",
                    auditor, java.sql.Timestamp.valueOf(now), direction, id);
        }

        BigDecimal absAmt = total.abs();
        if (!fundAcct.isEmpty()) {
            // 有收/付账户 → 收入方向自动生成收款单、支出方向自动生成付款单（business_source=EXPENSE，
            // 总账钩子不再为这些自动单丢收付款事件，费用事件模板自身已按资金账户贷方生成分录）
            String autoNo;
            if ("IN".equals(direction)) {
                autoNo = billNoGen.nextNo("SK", "fin_receipt_bill", "receipt_no");
                String autoId = "SK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
                jdbcTemplate.update("""
                        INSERT INTO fin_receipt_bill(receipt_id, receipt_no, receipt_date, status,
                            counterparty_type, counterparty_code, counterparty_name, object_name,
                            total_amount, verified_amount, fund_account, amount,
                            business_source, handler, related_bill_no, summary,
                            creator_name, create_time, auditor_name, audit_time)
                        VALUES (?, ?, ?, 'APPROVED', ?, ?, ?, ?, ?, ?, ?, ?, 'EXPENSE', ?, ?, ?, ?, ?, ?, ?)
                        """, autoId, autoNo, expenseDate, cpType, cpCode, cpName, cpName,
                        absAmt, absAmt, fundAcct, absAmt,
                        str(r.get("handler")), expenseNo, "费用单自动生成",
                        auditor, java.sql.Timestamp.valueOf(now), auditor, java.sql.Timestamp.valueOf(now));
            } else {
                autoNo = billNoGen.nextNo("FK", "fin_payment_bill", "payment_no");
                String autoId = "FK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
                jdbcTemplate.update("""
                        INSERT INTO fin_payment_bill(payment_id, payment_no, payment_date, status,
                            counterparty_type, counterparty_code, counterparty_name, object_name,
                            total_amount, verified_amount, fund_account, amount,
                            business_source, handler, related_bill_no, summary,
                            creator_name, create_time, auditor_name, audit_time)
                        VALUES (?, ?, ?, 'APPROVED', ?, ?, ?, ?, ?, ?, ?, ?, 'EXPENSE', ?, ?, ?, ?, ?, ?, ?)
                        """, autoId, autoNo, expenseDate, cpType, cpCode, cpName, cpName,
                        absAmt, absAmt, fundAcct, absAmt,
                        str(r.get("handler")), expenseNo, "费用单自动生成",
                        auditor, java.sql.Timestamp.valueOf(now), auditor, java.sql.Timestamp.valueOf(now));
            }
            // 写核销记录（receipt_no 列对付款单同样复用，存付款单号；归属日同费用记账日，v1.3）
            writeReconcileRecordV2(autoNo, expenseDate, expenseNo, expenseNo,
                    "EXPENSE", str(r.get("expenseDate")), cpType, cpCode, cpName, absAmt, "", "");
        } else {
            // 无收/付账户 → 生成往来 AR/AP
            writeCounterpartyLedger(cpType, cpCode, cpName, "IN".equals(direction) ? "IN" : "OUT",
                    total.abs(), expenseNo, "EXPENSE", BigDecimal.ZERO, "费用单生成往来");
        }
        // 总账钩子：费用支出 → EXPENSE 事件；收入方向 → OTHER_INCOME 事件
        glHooks.onExpenseAudited(expenseNo);
        finLog(com.erp.system.OperationModule.FIN_EXPENSE, com.erp.system.OperationAction.AUDIT,
                com.erp.system.KeyFields.BIZ_FIN_EXPENSE, expenseNo, "审核费用单 " + expenseNo);
        return ApiResponse.ok(GenericResult.row("expenseNo", expenseNo, "status", "APPROVED"));
    }

    private void insertExpenseDetails(String expenseId, Map<String, Object> body) {
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return;
        int idx = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            jdbcTemplate.update("""
                    INSERT INTO fin_expense_detail(detail_id, expense_id, expense_type,
                        goods_code, goods_name, brand_name,
                        qty, price, amount, tax_rate, tax_amount, excluding_tax_amount,
                        remark, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "FED" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(),
                    expenseId, str(m.get("expenseType")),
                    str(m.get("goodsCode")), str(m.get("goodsName")), str(m.get("brandName")),
                    toBd(m.get("qty")), toBd(m.get("price")), toBd(m.get("amount")),
                    toBd(m.get("taxRate")), toBd(m.get("taxAmount")), toBd(m.get("excludingTaxAmount")),
                    str(m.get("remark")), idx++);
        }
    }

    @RequirePerm(value = "fin.receipt_verify.writeoff", name = "核销")
    @PostMapping("/reconcile/receive")
    @Transactional
    public ApiResponse<Map<String, Object>> receiveReconcile(@Valid @RequestBody FundBillRequest request) {
        // 日结封单：快速核销资金即时生效，按当天判封单
        dayCloseGuard.assertWritable(LocalDate.now(), "往来快速核销", null);
        // 查找目标应收（优先按传入的 objectId 匹配，否则取最近一条未核销）
        List<Map<String, Object>> rows;
        if (request.objectId() != null && !request.objectId().isBlank()) {
            rows = jdbcTemplate.queryForList(
                "SELECT * FROM fin_ar WHERE status <> 'VERIFIED' AND (ar_no = ? OR customer = ?) ORDER BY ar_no DESC LIMIT 1",
                request.objectId(), request.objectId());
            if (rows.isEmpty()) {
                rows = jdbcTemplate.queryForList("SELECT * FROM fin_ar WHERE status <> 'VERIFIED' ORDER BY ar_no DESC LIMIT 1");
            }
        } else {
            rows = jdbcTemplate.queryForList("SELECT * FROM fin_ar WHERE status <> 'VERIFIED' ORDER BY ar_no DESC LIMIT 1");
        }
        if (rows.isEmpty()) {
            return ApiResponse.ok(Map.of("success", true, "effect", "无待核销应收记录"));
        }

        Map<String, Object> ar = rows.get(0);
        assertCpVisible("CUSTOMER", String.valueOf(ar.get("CUSTOMER")));
        BigDecimal arAmount = toBigDecimal(ar.get("AR_AMOUNT"));
        BigDecimal receivedAmount = toBigDecimal(ar.get("RECEIVED_AMOUNT"));
        BigDecimal unreceivedAmount = toBigDecimal(ar.get("UNRECEIVED_AMOUNT"));
        BigDecimal payAmount = request.amount() != null ? request.amount() : unreceivedAmount;

        // 本次实际核销金额不能超过未收金额
        BigDecimal actualVerify = payAmount.min(unreceivedAmount);
        BigDecimal newReceived = receivedAmount.add(actualVerify);
        BigDecimal newUnreceived = arAmount.subtract(newReceived);
        String newStatus = newUnreceived.compareTo(BigDecimal.ZERO) <= 0 ? "VERIFIED" : "UNVERIFIED";

        jdbcTemplate.update(
            "UPDATE fin_ar SET received_amount = ?, unreceived_amount = ?, status = ? WHERE ar_id = ?",
            newReceived, newUnreceived, newStatus, ar.get("AR_ID"));

        // 动态计算资金余额
        BigDecimal currentBalance = getLatestFundBalance();
        BigDecimal newBalance = currentBalance.add(actualVerify);
        insertFundLedger("IN", actualVerify, String.valueOf(ar.get("AR_NO")), newBalance);

        finLog(com.erp.system.OperationModule.FIN_RECEIPT, com.erp.system.OperationAction.WRITE_OFF,
                com.erp.system.KeyFields.BIZ_FIN_RECEIPT, String.valueOf(ar.get("AR_NO")), "核销应收 " + ar.get("AR_NO"));
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("success", true);
        resp.put("effect", "收款已核销应收并生成资金流水");
        resp.put("arNo", ar.get("AR_NO"));
        resp.put("verifiedAmount", actualVerify);
        resp.put("remaining", newUnreceived);
        fieldMasker.mask(resp, java.util.Map.of("verifiedAmount", "VIEW_FUND_FLOW", "remaining", "VIEW_AR_BALANCE"));
        return ApiResponse.ok(resp);
    }

    @RequirePerm(value = "fin.payment_verify.writeoff", name = "核销")
    @PostMapping("/reconcile/pay")
    @Transactional
    public ApiResponse<Map<String, Object>> payReconcile(@Valid @RequestBody FundBillRequest request) {
        // 日结封单：快速核销资金即时生效，按当天判封单
        dayCloseGuard.assertWritable(LocalDate.now(), "往来快速核销", null);
        List<Map<String, Object>> rows;
        if (request.objectId() != null && !request.objectId().isBlank()) {
            rows = jdbcTemplate.queryForList(
                "SELECT * FROM fin_ap WHERE status <> 'VERIFIED' AND (ap_no = ? OR supplier = ?) ORDER BY ap_no DESC LIMIT 1",
                request.objectId(), request.objectId());
            if (rows.isEmpty()) {
                rows = jdbcTemplate.queryForList("SELECT * FROM fin_ap WHERE status <> 'VERIFIED' ORDER BY ap_no DESC LIMIT 1");
            }
        } else {
            rows = jdbcTemplate.queryForList("SELECT * FROM fin_ap WHERE status <> 'VERIFIED' ORDER BY ap_no DESC LIMIT 1");
        }
        if (rows.isEmpty()) {
            return ApiResponse.ok(Map.of("success", true, "effect", "无待核销应付记录"));
        }

        Map<String, Object> ap = rows.get(0);
        assertCpVisible("SUPPLIER", String.valueOf(ap.get("SUPPLIER")));
        BigDecimal apAmount = toBigDecimal(ap.get("AP_AMOUNT"));
        BigDecimal paidAmount = toBigDecimal(ap.get("PAID_AMOUNT"));
        BigDecimal unpaidAmount = toBigDecimal(ap.get("UNPAID_AMOUNT"));
        BigDecimal payAmount = request.amount() != null ? request.amount() : unpaidAmount;

        BigDecimal actualVerify = payAmount.min(unpaidAmount);
        BigDecimal newPaid = paidAmount.add(actualVerify);
        BigDecimal newUnpaid = apAmount.subtract(newPaid);
        String newStatus = newUnpaid.compareTo(BigDecimal.ZERO) <= 0 ? "VERIFIED" : "UNVERIFIED";

        jdbcTemplate.update(
            "UPDATE fin_ap SET paid_amount = ?, unpaid_amount = ?, status = ? WHERE ap_id = ?",
            newPaid, newUnpaid, newStatus, ap.get("AP_ID"));

        BigDecimal currentBalance = getLatestFundBalance();
        BigDecimal newBalance = currentBalance.subtract(actualVerify);
        insertFundLedger("OUT", actualVerify, String.valueOf(ap.get("AP_NO")), newBalance);

        finLog(com.erp.system.OperationModule.FIN_PAYMENT, com.erp.system.OperationAction.WRITE_OFF,
                com.erp.system.KeyFields.BIZ_FIN_PAYMENT, String.valueOf(ap.get("AP_NO")), "核销应付 " + ap.get("AP_NO"));
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("success", true);
        resp.put("effect", "付款已核销应付并生成资金流水");
        resp.put("apNo", ap.get("AP_NO"));
        resp.put("verifiedAmount", actualVerify);
        resp.put("remaining", newUnpaid);
        fieldMasker.mask(resp, java.util.Map.of("verifiedAmount", "VIEW_FUND_FLOW", "remaining", "VIEW_AP_BALANCE"));
        return ApiResponse.ok(resp);
    }

    // ============================================================
    // 收款单 CRUD + 审核 + 取消审核（V32 重构）
    // ============================================================

    @RequirePerm(value = "fin.receipt.view", name = "查看")
    @PostMapping("/receipt/page")
    public ApiResponse<PageResult<Map<String, Object>>> receiptPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT r.receipt_id, r.receipt_no, r.receipt_date, r.status,
                       r.counterparty_type, r.counterparty_code, r.counterparty_name,
                       r.total_amount, r.verified_amount, r.handler,
                       r.business_source, r.related_bill_no, r.summary, r.receipt_type,
                       r.creator_name, r.create_time, r.auditor_name, r.audit_time
                FROM fin_receipt_bill r
                WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
        // PRD-28：往来单位多态列按客户/供应商维度收窄
        appendCpScope(sql, args, "r.counterparty_type", "r.counterparty_name", "r.creator_name");
        String cpType = trimF(filters, "counterpartyType", "counterparty_type");
        if (!cpType.isEmpty()) { sql.append(" AND r.counterparty_type = ?"); args.add(cpType); }
        String cpName = trimF(filters, "counterparty", "counterpartyName");
        if (!cpName.isEmpty()) { sql.append(" AND (r.counterparty_code LIKE ? OR r.counterparty_name LIKE ?)"); args.add("%"+cpName+"%"); args.add("%"+cpName+"%"); }
        String receiptNo = trimF(filters, "receiptNo", "receipt_no");
        if (!receiptNo.isEmpty()) { sql.append(" AND r.receipt_no LIKE ?"); args.add("%"+receiptNo+"%"); }
        String status = trimF(filters, "status");
        if (!status.isEmpty()) { sql.append(" AND r.status = ?"); args.add(status); }
        String dateFrom = trimF(filters, "dateFrom", "receiptDateFrom");
        if (!dateFrom.isEmpty()) { sql.append(" AND r.receipt_date >= ?"); args.add(dateFrom); }
        String dateTo = trimF(filters, "dateTo", "receiptDateTo");
        if (!dateTo.isEmpty()) { sql.append(" AND r.receipt_date <= ?"); args.add(dateTo); }
        String bizSrc = trimF(filters, "businessSource", "business_source");
        if (!bizSrc.isEmpty()) { sql.append(" AND r.business_source = ?"); args.add(bizSrc); }
        String reconcileStatus = trimF(filters, "reconcileStatus", "reconcile_status");
        if (!reconcileStatus.isEmpty()) {
            // PRD-35：核销状态只对「应收结算」单有意义，预收类不参与应收核销
            sql.append(" AND r.receipt_type = 'SETTLE'");
            switch (reconcileStatus) {
                case "未核销": sql.append(" AND (r.verified_amount = 0 OR r.verified_amount IS NULL)"); break;
                case "部分核销": sql.append(" AND r.verified_amount > 0 AND r.verified_amount < r.total_amount"); break;
                case "已核销": sql.append(" AND r.verified_amount >= r.total_amount AND r.total_amount > 0"); break;
            }
        }
        // PRD-35：收款类型筛选（前端下拉传中文，兼容直接传码值）
        String receiptTypeF = trimF(filters, "receiptType", "receipt_type");
        if (!receiptTypeF.isEmpty()) {
            String typeCode = switch (receiptTypeF) {
                case "应收结算" -> com.erp.finance.account.CustomerAccountConst.RECEIPT_SETTLE;
                case "预收收款" -> com.erp.finance.account.CustomerAccountConst.RECEIPT_ADVANCE;
                case "预收退款" -> com.erp.finance.account.CustomerAccountConst.RECEIPT_ADVANCE_REFUND;
                default -> receiptTypeF;
            };
            sql.append(" AND r.receipt_type = ?");
            args.add(typeCode);
        }
        sql.append(" ORDER BY r.receipt_no DESC");

        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            String src = str(r.get("businessSource"));
            r.put("businessSourceText", "BACKOFFICE".equals(src) ? "后台制单"
                    : "AR_SETTLEMENT".equals(src) ? "结算生成"
                    : "RECONCILE".equals(src) ? "对账生成" : src);
            String st = str(r.get("status"));
            r.put("statusText", "APPROVED".equals(st) ? "已审核"
                    : "PENDING".equals(st) ? "待审核"
                    : "CANCELLED".equals(st) ? "已作废" : st);
            String ct = str(r.get("counterpartyType"));
            r.put("counterpartyTypeText", "CUSTOMER".equals(ct) ? "客户"
                    : "SUPPLIER".equals(ct) ? "供应商"
                    : "COUNTERPARTY".equals(ct) ? "往来单位" : ct);
            // PRD-35：收款类型（应收结算/预收收款/预收退款）
            String rt = str(r.get("receiptType"));
            r.put("receiptTypeText", receiptTypeText(rt));
            // 核销状态（预收类单据不参与应收核销，统一展示 —）
            BigDecimal total = toBd(r.get("totalAmount"));
            BigDecimal verified = toBd(r.get("verifiedAmount"));
            if (!com.erp.finance.account.CustomerAccountConst.RECEIPT_SETTLE.equals(
                    rt == null || rt.isBlank()
                            ? com.erp.finance.account.CustomerAccountConst.RECEIPT_SETTLE : rt)) {
                r.put("reconcileStatusText", "—");
            } else if (total.signum() <= 0) r.put("reconcileStatusText", "—");
            else if (verified.signum() <= 0) r.put("reconcileStatusText", "未核销");
            else if (verified.compareTo(total) < 0) r.put("reconcileStatusText", "部分核销");
            else r.put("reconcileStatusText", "已核销");
        }
        fieldMasker.mask(rows, FUND_AMOUNT_KEYS);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 查询往来单位的未结算单据（供核销弹窗选择） */
    /** 查询往来单位的未结算单据（核销弹窗数据源） */
    @RequirePerm(value = "fin.receipt_verify.view", name = "查看")
    @PostMapping("/receipt/unsettled-bills")
    public ApiResponse<Map<String, Object>> unsettledBills(@RequestBody Map<String, Object> body) {
        String cpType = str(body.get("counterpartyType"));
        String cpCode = str(body.get("counterpartyCode"));
        String cpName = str(body.get("counterpartyName"));
        String receiptId = str(body.get("receiptId"));
        // PRD-28：核销弹窗数据源按往来单位数据范围收窄，禁止枚举不可见客户/供应商的单据
        assertCpVisible(cpType, cpName);
        // 待核销金额 = total_amount - verified_amount
        BigDecimal pendingAmount = BigDecimal.ZERO;
        if (!receiptId.isEmpty()) {
            List<Map<String, Object>> rr = queryCamel(
                    "SELECT * FROM fin_receipt_bill WHERE receipt_id = ?", receiptId);
            if (!rr.isEmpty()) {
                Map<String, Object> rec = rr.get(0);
                assertHeadCpVisible(rec);
                pendingAmount = toBd(rec.get("totalAmount")).subtract(toBd(rec.get("verifiedAmount")));
            }
        }
        List<Map<String, Object>> bills = new java.util.ArrayList<>();
        if (!"SUPPLIER".equals(cpType)) {
            String arSql = cpCode.isEmpty()
                ? "SELECT ar_no, source_bill, customer AS counterparty_name, ar_amount, received_amount, unreceived_amount, due_date FROM fin_ar WHERE customer = ? AND unreceived_amount <> 0 ORDER BY due_date"
                : "SELECT ar_no, source_bill, customer AS counterparty_name, ar_amount, received_amount, unreceived_amount, due_date FROM fin_ar WHERE (customer = ? OR customer = ?) AND unreceived_amount <> 0 ORDER BY due_date";
            List<Map<String, Object>> arList = queryCamel(arSql, cpCode.isEmpty() ? new Object[]{cpName} : new Object[]{cpCode, cpName});
            for (Map<String, Object> r : arList) {
                r.put("billType", "应收"); r.put("billTypeKey", "AR");
                r.put("billNo", r.get("arNo")); r.put("settleAmount", r.get("unreceivedAmount"));
                bills.add(r);
            }
        }
        if (!"CUSTOMER".equals(cpType)) {
            String apSql = cpCode.isEmpty()
                ? "SELECT ap_no, source_bill, supplier AS counterparty_name, ap_amount, paid_amount, unpaid_amount, due_date FROM fin_ap WHERE supplier = ? AND unpaid_amount <> 0 ORDER BY due_date"
                : "SELECT ap_no, source_bill, supplier AS counterparty_name, ap_amount, paid_amount, unpaid_amount, due_date FROM fin_ap WHERE (supplier = ? OR supplier = ?) AND unpaid_amount <> 0 ORDER BY due_date";
            List<Map<String, Object>> apList = queryCamel(apSql, cpCode.isEmpty() ? new java.util.ArrayList<>().toArray() : new Object[]{cpCode, cpCode});
            for (Map<String, Object> r : apList) {
                r.put("billType", "应付"); r.put("billTypeKey", "AP");
                r.put("billNo", r.get("apNo")); r.put("settleAmount", r.get("unpaidAmount"));
                bills.add(r);
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("pendingAmount", pendingAmount);
        result.put("bills", bills);
        java.util.Map<String, String> billKeys = new java.util.HashMap<>();
        billKeys.putAll(AR_AMOUNT_KEYS);
        billKeys.putAll(AP_AMOUNT_KEYS);
        billKeys.put("settleAmount", "VIEW_SETTLE_DETAIL");
        billKeys.put("pendingAmount", "VIEW_FUND_FLOW");
        fieldMasker.mask(result, billKeys);
        return ApiResponse.ok(result);
    }

    /** 执行核销：勾选未结算单据 → 生成核销记录 + 更新 AR/AP + 更新收款单 verified_amount */
    @RequirePerm(value = "fin.receipt_verify.writeoff", name = "核销")
    @PostMapping("/receipt/reconcile")
    @Transactional
    public ApiResponse<Map<String, Object>> reconcileReceipt(@RequestBody Map<String, Object> body) {
        String receiptId = str(body.get("receiptId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_receipt_bill WHERE receipt_id = ?", receiptId);
        if (heads.isEmpty()) return ApiResponse.fail("404", "收款单不存在");
        Map<String, Object> r = heads.get(0);
        if (!"APPROVED".equals(str(r.get("status")))) return ApiResponse.fail("400", "仅已审核单据可核销");
        assertHeadCpVisible(r);
        String receiptNo = str(r.get("receiptNo"));
        java.sql.Date receiptDate = r.get("receiptDate") instanceof java.sql.Date d ? d : java.sql.Date.valueOf(LocalDate.now());
        String cpType = str(r.get("counterpartyType"));
        String cpCode = str(r.get("counterpartyCode"));
        String cpName = str(r.get("counterpartyName"));
        String receiptRemark = str(r.get("summary"));

        Object raw = body.get("bills");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return ApiResponse.fail("400", "请选择要核销的单据");
        BigDecimal totalVerified = BigDecimal.ZERO;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String billNo = str(m.get("billNo"));
            String billType = str(m.get("billTypeKey"));
            BigDecimal amt = toBd(m.get("settleAmount"));  // 前端传本次结算金额
            if (amt.signum() <= 0) continue;
            totalVerified = totalVerified.add(amt);
            String sourceBill = "";
            if ("AR".equals(billType)) {
                List<Map<String, Object>> ars = queryCamel("SELECT * FROM fin_ar WHERE ar_no = ?", billNo);
                if (ars.isEmpty()) continue;
                Map<String, Object> ar = ars.get(0);
                assertCpVisible("CUSTOMER", str(ar.get("customer")));
                BigDecimal newReceived = toBd(ar.get("receivedAmount")).add(amt);
                BigDecimal newUnreceived = toBd(ar.get("arAmount")).subtract(newReceived);
                jdbcTemplate.update("UPDATE fin_ar SET received_amount = ?, unreceived_amount = ?, status = ? WHERE ar_no = ?",
                        newReceived, newUnreceived, newUnreceived.signum() <= 0 ? "VERIFIED" : "UNVERIFIED", billNo);
                sourceBill = str(ar.get("sourceBill"));
                writeReconcileRecordV2(receiptNo, receiptDate, billNo, sourceBill, "SALES_RECEIPT",
                        str(ar.get("dueDate")), cpType, cpCode, cpName, amt, receiptRemark, "");
            } else {
                List<Map<String, Object>> aps = queryCamel("SELECT * FROM fin_ap WHERE ap_no = ?", billNo);
                if (aps.isEmpty()) continue;
                Map<String, Object> ap = aps.get(0);
                assertCpVisible("SUPPLIER", str(ap.get("supplier")));
                BigDecimal newPaid = toBd(ap.get("paidAmount")).add(amt);
                BigDecimal newUnpaid = toBd(ap.get("apAmount")).subtract(newPaid);
                jdbcTemplate.update("UPDATE fin_ap SET paid_amount = ?, unpaid_amount = ?, status = ? WHERE ap_no = ?",
                        newPaid, newUnpaid, newUnpaid.signum() <= 0 ? "VERIFIED" : "UNVERIFIED", billNo);
                sourceBill = str(ap.get("sourceBill"));
                writeReconcileRecordV2(receiptNo, receiptDate, billNo, sourceBill, "PURCHASE_RECEIPT",
                        str(ap.get("dueDate")), cpType, cpCode, cpName, amt, receiptRemark, "");
            }
        }
        // 更新收款单的核销金额
        BigDecimal curVerified = toBd(r.get("verifiedAmount"));
        jdbcTemplate.update("UPDATE fin_receipt_bill SET verified_amount = ? WHERE receipt_id = ?",
                curVerified.add(totalVerified), receiptId);
        finLog(com.erp.system.OperationModule.FIN_RECEIPT, com.erp.system.OperationAction.WRITE_OFF,
                com.erp.system.KeyFields.BIZ_FIN_RECEIPT, receiptNo, "核销应收 " + receiptNo);
        java.util.Map<String, Object> recResp = new java.util.LinkedHashMap<>();
        recResp.put("receiptNo", receiptNo);
        recResp.put("reconciled", totalVerified);
        fieldMasker.mask(recResp, java.util.Map.of("reconciled", "VIEW_SETTLE_DETAIL"));
        return ApiResponse.ok(recResp);
    }

    /** 查询往来单位的未结算单据（付款单核销弹窗数据源，与 /receipt/unsettled-bills 对称） */
    @RequirePerm(value = "fin.payment_verify.view", name = "查看")
    @PostMapping("/payment/unsettled-bills")
    public ApiResponse<Map<String, Object>> paymentUnsettledBills(@RequestBody Map<String, Object> body) {
        String cpType = str(body.get("counterpartyType"));
        String cpCode = str(body.get("counterpartyCode"));
        String cpName = str(body.get("counterpartyName"));
        String paymentId = str(body.get("paymentId"));
        // PRD-28：核销弹窗数据源按往来单位数据范围收窄，禁止枚举不可见客户/供应商的单据
        assertCpVisible(cpType, cpName);
        // 待核销金额 = total_amount - verified_amount
        BigDecimal pendingAmount = BigDecimal.ZERO;
        if (!paymentId.isEmpty()) {
            List<Map<String, Object>> pp = queryCamel(
                    "SELECT * FROM fin_payment_bill WHERE payment_id = ?", paymentId);
            if (!pp.isEmpty()) {
                Map<String, Object> pay = pp.get(0);
                assertHeadCpVisible(pay);
                pendingAmount = toBd(pay.get("totalAmount")).subtract(toBd(pay.get("verifiedAmount")));
            }
        }
        List<Map<String, Object>> bills = new java.util.ArrayList<>();
        if (!"SUPPLIER".equals(cpType)) {
            String arSql = cpCode.isEmpty()
                ? "SELECT ar_no, source_bill, customer AS counterparty_name, ar_amount, received_amount, unreceived_amount, due_date FROM fin_ar WHERE customer = ? AND unreceived_amount <> 0 ORDER BY due_date"
                : "SELECT ar_no, source_bill, customer AS counterparty_name, ar_amount, received_amount, unreceived_amount, due_date FROM fin_ar WHERE (customer = ? OR customer = ?) AND unreceived_amount <> 0 ORDER BY due_date";
            List<Map<String, Object>> arList = queryCamel(arSql, cpCode.isEmpty() ? new Object[]{cpName} : new Object[]{cpCode, cpName});
            for (Map<String, Object> r : arList) {
                r.put("billType", "应收"); r.put("billTypeKey", "AR");
                r.put("billNo", r.get("arNo")); r.put("settleAmount", r.get("unreceivedAmount"));
                bills.add(r);
            }
        }
        if (!"CUSTOMER".equals(cpType)) {
            String apSql = cpCode.isEmpty()
                ? "SELECT ap_no, source_bill, supplier AS counterparty_name, ap_amount, paid_amount, unpaid_amount, due_date FROM fin_ap WHERE supplier = ? AND unpaid_amount <> 0 ORDER BY due_date"
                : "SELECT ap_no, source_bill, supplier AS counterparty_name, ap_amount, paid_amount, unpaid_amount, due_date FROM fin_ap WHERE (supplier = ? OR supplier = ?) AND unpaid_amount <> 0 ORDER BY due_date";
            List<Map<String, Object>> apList = queryCamel(apSql, cpCode.isEmpty() ? new java.util.ArrayList<>().toArray() : new Object[]{cpCode, cpCode});
            for (Map<String, Object> r : apList) {
                r.put("billType", "应付"); r.put("billTypeKey", "AP");
                r.put("billNo", r.get("apNo")); r.put("settleAmount", r.get("unpaidAmount"));
                bills.add(r);
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("pendingAmount", pendingAmount);
        result.put("bills", bills);
        java.util.Map<String, String> billKeys = new java.util.HashMap<>();
        billKeys.putAll(AR_AMOUNT_KEYS);
        billKeys.putAll(AP_AMOUNT_KEYS);
        billKeys.put("settleAmount", "VIEW_SETTLE_DETAIL");
        billKeys.put("pendingAmount", "VIEW_FUND_FLOW");
        fieldMasker.mask(result, billKeys);
        return ApiResponse.ok(result);
    }

    /** 执行核销：勾选未结算单据 → 生成核销记录 + 更新 AR/AP + 更新付款单 verified_amount（与 /receipt/reconcile 对称） */
    @RequirePerm(value = "fin.payment_verify.writeoff", name = "核销")
    @PostMapping("/payment/reconcile")
    @Transactional
    public ApiResponse<Map<String, Object>> reconcilePayment(@RequestBody Map<String, Object> body) {
        String paymentId = str(body.get("paymentId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_payment_bill WHERE payment_id = ?", paymentId);
        if (heads.isEmpty()) return ApiResponse.fail("404", "付款单不存在");
        Map<String, Object> p = heads.get(0);
        if (!"APPROVED".equals(str(p.get("status")))) return ApiResponse.fail("400", "仅已审核单据可核销");
        assertHeadCpVisible(p);
        String paymentNo = str(p.get("paymentNo"));
        java.sql.Date paymentDate = p.get("paymentDate") instanceof java.sql.Date d ? d : java.sql.Date.valueOf(LocalDate.now());
        String cpType = str(p.get("counterpartyType"));
        String cpCode = str(p.get("counterpartyCode"));
        String cpName = str(p.get("counterpartyName"));
        String paymentRemark = str(p.get("summary"));

        Object raw = body.get("bills");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return ApiResponse.fail("400", "请选择要核销的单据");
        BigDecimal totalVerified = BigDecimal.ZERO;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String billNo = str(m.get("billNo"));
            String billType = str(m.get("billTypeKey"));
            BigDecimal amt = toBd(m.get("settleAmount"));  // 前端传本次结算金额
            if (amt.signum() <= 0) continue;
            totalVerified = totalVerified.add(amt);
            String sourceBill = "";
            if ("AR".equals(billType)) {
                List<Map<String, Object>> ars = queryCamel("SELECT * FROM fin_ar WHERE ar_no = ?", billNo);
                if (ars.isEmpty()) continue;
                Map<String, Object> ar = ars.get(0);
                assertCpVisible("CUSTOMER", str(ar.get("customer")));
                BigDecimal newReceived = toBd(ar.get("receivedAmount")).add(amt);
                BigDecimal newUnreceived = toBd(ar.get("arAmount")).subtract(newReceived);
                jdbcTemplate.update("UPDATE fin_ar SET received_amount = ?, unreceived_amount = ?, status = ? WHERE ar_no = ?",
                        newReceived, newUnreceived, newUnreceived.signum() <= 0 ? "VERIFIED" : "UNVERIFIED", billNo);
                sourceBill = str(ar.get("sourceBill"));
                writeReconcileRecordV2(paymentNo, paymentDate, billNo, sourceBill, "SALES_PAYMENT",
                        str(ar.get("dueDate")), cpType, cpCode, cpName, amt, paymentRemark, "");
            } else {
                List<Map<String, Object>> aps = queryCamel("SELECT * FROM fin_ap WHERE ap_no = ?", billNo);
                if (aps.isEmpty()) continue;
                Map<String, Object> ap = aps.get(0);
                assertCpVisible("SUPPLIER", str(ap.get("supplier")));
                BigDecimal newPaid = toBd(ap.get("paidAmount")).add(amt);
                BigDecimal newUnpaid = toBd(ap.get("apAmount")).subtract(newPaid);
                jdbcTemplate.update("UPDATE fin_ap SET paid_amount = ?, unpaid_amount = ?, status = ? WHERE ap_no = ?",
                        newPaid, newUnpaid, newUnpaid.signum() <= 0 ? "VERIFIED" : "UNVERIFIED", billNo);
                sourceBill = str(ap.get("sourceBill"));
                writeReconcileRecordV2(paymentNo, paymentDate, billNo, sourceBill, "PURCHASE_PAYMENT",
                        str(ap.get("dueDate")), cpType, cpCode, cpName, amt, paymentRemark, "");
            }
        }
        // 更新付款单的核销金额
        BigDecimal curVerified = toBd(p.get("verifiedAmount"));
        jdbcTemplate.update("UPDATE fin_payment_bill SET verified_amount = ? WHERE payment_id = ?",
                curVerified.add(totalVerified), paymentId);
        finLog(com.erp.system.OperationModule.FIN_PAYMENT, com.erp.system.OperationAction.WRITE_OFF,
                com.erp.system.KeyFields.BIZ_FIN_PAYMENT, paymentNo, "付款单核销 " + paymentNo);
        java.util.Map<String, Object> recResp = new java.util.LinkedHashMap<>();
        recResp.put("paymentNo", paymentNo);
        recResp.put("reconciled", totalVerified);
        fieldMasker.mask(recResp, java.util.Map.of("reconciled", "VIEW_SETTLE_DETAIL"));
        return ApiResponse.ok(recResp);
    }

    /** 批量审核收款单 */
    @RequirePerm(value = "fin.receipt.audit", name = "审核")
    @PostMapping("/receipt/batch-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> batchAuditReceipt(@RequestBody Map<String, Object> body) {
        Object raw = body.get("receiptIds");
        if (!(raw instanceof List<?> list) || list.isEmpty())
            return ApiResponse.fail("400", "请选择要审核的收款单");
        int ok = 0, skip = 0;
        for (Object item : list) {
            String id = str(item);
            List<Map<String, Object>> heads = queryCamel(
                    "SELECT * FROM fin_receipt_bill WHERE receipt_id = ?", id);
            if (heads.isEmpty()) { skip++; continue; }
            Map<String, Object> r = heads.get(0);
            if (!"PENDING".equals(str(r.get("status")))) { skip++; continue; }
            assertHeadCpVisible(r);
            // PRD-35：预收收款/预收退款必须走完整审核（资金出入账 + 客户账户预收流水），
            // 应收结算维持原批量轻量路径（核销在收款核销环节处理）
            String batchType = normalizeReceiptType(str(r.get("receiptType")),
                    str(r.get("counterpartyType")));
            if (com.erp.finance.account.CustomerAccountConst.RECEIPT_SETTLE.equals(batchType)) {
                auditSingleReceipt(r);
            } else {
                doAuditReceipt(r);
            }
            ok++;
        }
        finLog(com.erp.system.OperationModule.FIN_RECEIPT, com.erp.system.OperationAction.AUDIT,
                com.erp.system.KeyFields.BIZ_FIN_RECEIPT, "", "批量审核收款单 " + ok + " 张");
        return ApiResponse.ok(Map.of("audited", ok, "skipped", skip));
    }

    /** 单条审核逻辑（供 batch-audit 与单条 audit 共用） */
    private void auditSingleReceipt(Map<String, Object> r) {
        String id = str(r.get("receiptId"));
        LocalDateTime now = LocalDateTime.now();
        String auditor = currentUser();
        String receiptNo = str(r.get("receiptNo"));
        // v1.3：按记账日期判封单；为空按当天并持久化（存量行为）
        boolean dateMissing = !(r.get("receiptDate") instanceof java.sql.Date);
        java.sql.Date receiptDate = dateMissing
                ? java.sql.Date.valueOf(LocalDate.now()) : (java.sql.Date) r.get("receiptDate");
        dayCloseGuard.assertWritable(receiptDate.toLocalDate(), "收款单", receiptNo);
        if (dateMissing) {
            jdbcTemplate.update("""
                    UPDATE fin_receipt_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?, receipt_date = ?
                    WHERE receipt_id = ?
                    """, auditor, java.sql.Timestamp.valueOf(now), receiptDate, id);
        } else {
            jdbcTemplate.update("""
                    UPDATE fin_receipt_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?
                    WHERE receipt_id = ?
                    """, auditor, java.sql.Timestamp.valueOf(now), id);
        }
        // 总账钩子：批量审核同样丢收款事件（钩子内按 business_source 过滤自动单）
        glHooks.onReceiptAudited(receiptNo);
    }

    private String trimF(Map<String, Object> filters, String key, String altKey) {
        String v = str(filters.get(key)).trim();
        if (v.isEmpty() && altKey != null) v = str(filters.get(altKey)).trim();
        return v;
    }
    private String trimF(Map<String, Object> filters, String key) { return trimF(filters, key, null); }

    /** 收款单详情（含明细行） */
    @RequirePerm(value = "fin.receipt.view", name = "查看")
    @PostMapping("/receipt/detail")
    public ApiResponse<Map<String, Object>> receiptDetail(@RequestBody Map<String, Object> body) {
        String id = str(body.get("receiptId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM fin_receipt_bill WHERE receipt_id = ? OR receipt_no = ?", id, id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "收款单不存在");
        Map<String, Object> head = heads.get(0);
        assertHeadCpVisible(head);
        head.put("details", queryCamel(
                "SELECT * FROM fin_receipt_detail WHERE receipt_id = ? ORDER BY sort_order",
                head.get("receiptId")));
        fieldMasker.mask(head, FUND_AMOUNT_KEYS);
        return ApiResponse.ok(head);
    }

    @RequirePerm(value = "fin.receipt.edit", name = "修改")
    @PostMapping("/receipt/update")
    public ApiResponse<Boolean> updateReceipt(@RequestBody Map<String, Object> body) {
        String id = str(body.get("receiptId"));
        List<Map<String, Object>> exist = queryCamel(
                "SELECT status, receipt_no, counterparty_type, counterparty_name FROM fin_receipt_bill WHERE receipt_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "收款单不存在");
        String st = str(exist.get(0).get("status"));
        if (!"PENDING".equals(st)) return ApiResponse.fail("400", "仅待审核单据可编辑");
        // PRD-28：旧往来单位与改后往来单位都须在数据范围内
        assertHeadCpVisible(exist.get(0));
        assertCpVisible(str(body.get("counterpartyType")), str(body.get("counterpartyName")));
        String receiptNo = str(exist.get(0).get("receiptNo"));

        BigDecimal total = sumDetails(body);
        String receiptType = normalizeReceiptType(str(body.get("receiptType")),
                str(body.get("counterpartyType")));
        jdbcTemplate.update("""
                UPDATE fin_receipt_bill SET receipt_date = ?, counterparty_type = ?,
                    counterparty_code = ?, counterparty_name = ?,
                    handler = ?, related_bill_no = ?, summary = ?, total_amount = ?, receipt_type = ?
                WHERE receipt_id = ?
                """, date(body, "receiptDate"), str(body.get("counterpartyType")),
                str(body.get("counterpartyCode")), str(body.get("counterpartyName")),
                str(body.get("handler")), str(body.get("relatedBillNo")),
                str(body.get("summary")), total, receiptType, id);
        jdbcTemplate.update("DELETE FROM fin_receipt_detail WHERE receipt_id = ?", id);
        insertDetails(id, body);
        finLog(com.erp.system.OperationModule.FIN_RECEIPT, com.erp.system.OperationAction.UPDATE,
                com.erp.system.KeyFields.BIZ_FIN_RECEIPT, receiptNo, "修改收款单 " + receiptNo);
        return ApiResponse.ok(true);
    }

    @RequirePerm(value = "fin.receipt.delete", name = "删除")
    @PostMapping("/receipt/delete")
    public ApiResponse<Boolean> deleteReceipt(@RequestBody Map<String, Object> body) {
        String id = str(body.get("receiptId"));
        List<Map<String, Object>> exist = queryCamel(
                "SELECT status, business_source, receipt_no, counterparty_type, counterparty_name FROM fin_receipt_bill WHERE receipt_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "收款单不存在");
        if (!"PENDING".equals(str(exist.get(0).get("status"))))
            return ApiResponse.fail("400", "仅待审核单据可删除");
        assertHeadCpVisible(exist.get(0));
        // 司机现场收款单代表司机手里真实拿着的钱，删掉就再也对不上交账差异，
        // 只能通过司机交账单审核/驳回来推进，后台不允许直接删除。
        if ("DRIVER_SETTLE".equals(str(exist.get(0).get("businessSource"))))
            return ApiResponse.fail("400", "司机现场收款单不允许删除，请通过司机交账单审核处理");
        String receiptNo = str(exist.get(0).get("receiptNo"));
        jdbcTemplate.update("DELETE FROM fin_receipt_detail WHERE receipt_id = ?", id);
        jdbcTemplate.update("DELETE FROM fin_receipt_bill WHERE receipt_id = ?", id);
        finLog(com.erp.system.OperationModule.FIN_RECEIPT, com.erp.system.OperationAction.DELETE,
                com.erp.system.KeyFields.BIZ_FIN_RECEIPT, receiptNo, "删除收款单 " + receiptNo);
        return ApiResponse.ok(true);
    }

    /** 审核收款单：生成核销记录、更新 AR/AP、写资金流水、更新账户余额、写往来流水 */
    @RequirePerm(value = "fin.receipt.audit", name = "审核")
    @PostMapping("/receipt/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditReceipt(@RequestBody Map<String, Object> body) {
        String id = str(body.get("receiptId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM fin_receipt_bill WHERE receipt_id = ?", id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "收款单不存在");
        Map<String, Object> r = heads.get(0);
        if (!"PENDING".equals(str(r.get("status"))))
            return ApiResponse.fail("400", "仅待审核单据可审核");
        assertHeadCpVisible(r);
        doAuditReceipt(r);
        return ApiResponse.ok(GenericResult.row("receiptNo", str(r.get("receiptNo")), "status", "APPROVED"));
    }

    /**
     * 收款单完整审核（单条审核与批量审核的预收类共用），按 receipt_type 分流（PRD-35）：
     * <ul>
     *   <li>SETTLE 应收结算：核销 AR/AP + 资金入账 + 往来流水（存量行为）；</li>
     *   <li>ADVANCE 预收收款：不核销，资金入账 + 往来 IN + 客户账户预收流水（预收余额 +）；</li>
     *   <li>ADVANCE_REFUND 预收退款：不核销，资金出账 + 往来 OUT + 客户账户预收退款流水
     *       （预收余额 −，退款额 &gt; 当前预收余额时服务内直接中文报错）。</li>
     * </ul>
     */
    private void doAuditReceipt(Map<String, Object> r) {
        String id = str(r.get("receiptId"));
        LocalDateTime now = LocalDateTime.now();
        String auditor = currentUser();
        String receiptNo = str(r.get("receiptNo"));
        // v1.3：记账日期不回填用户已填值；为空沿用存量行为按审核当天（并持久化，保证流水有归属日）
        boolean receiptDateMissing = !(r.get("receiptDate") instanceof java.sql.Date);
        java.sql.Date receiptDate = receiptDateMissing
                ? java.sql.Date.valueOf(LocalDate.now())
                : (java.sql.Date) r.get("receiptDate");
        // 审核按单据记账日期判封单（已封日期拒审）
        dayCloseGuard.assertWritable(receiptDate.toLocalDate(), "收款单", receiptNo);
        String cpType = str(r.get("counterpartyType"));
        String cpCode = str(r.get("counterpartyCode"));
        String cpName = str(r.get("counterpartyName"));
        BigDecimal total = toBd(r.get("totalAmount"));
        String summary = str(r.get("summary"));
        String receiptType = normalizeReceiptType(str(r.get("receiptType")), cpType);
        boolean advance = com.erp.finance.account.CustomerAccountConst.RECEIPT_ADVANCE.equals(receiptType);
        boolean advanceRefund =
                com.erp.finance.account.CustomerAccountConst.RECEIPT_ADVANCE_REFUND.equals(receiptType);

        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_receipt_detail WHERE receipt_id = ? ORDER BY sort_order", id);

        if (advance || advanceRefund) {
            // 预收类不产生核销记录；资金按明细行出入账（退款 OUT / 预收 IN）。
            // 例外：门店结算溢收自动预收单（DRIVER_OVERPAY_ADV）的资金已在结算收款单审核时
            // 按实缴全额入过账，其审核/重新审核一律不写资金流水，避免资金双计；明细资金账户
            // 仅用于 GL 凭证「借资金」科目取数。
            boolean storeOverpayAuto =
                    com.erp.finance.account.CustomerAccountConst.SOURCE_DRIVER_OVERPAY_ADV
                            .equals(str(r.get("businessSource")));
            java.util.Set<String> touchedFundAccounts = new java.util.LinkedHashSet<>();
            if (!storeOverpayAuto) {
                for (Map<String, Object> d : details) {
                    BigDecimal amt = toBd(d.get("amount"));
                    if (amt.signum() <= 0) continue;
                    String fundAcct = str(d.get("fundAccount"));
                    BigDecimal bal = advanceRefund
                            ? getFundBalance(fundAcct).subtract(amt)
                            : getFundBalance(fundAcct).add(amt);
                    insertFundLedgerV2(fundAcct, advanceRefund ? "OUT" : "IN", amt, receiptNo, bal,
                            auditor, receiptDate.toLocalDate());
                    updateFundBalance(fundAcct, bal);
                    touchedFundAccounts.add(fundAcct);
                }
                // 补录往日记账时按发生时间重排余额链，保证日结资金滚存可勾稽
                touchedFundAccounts.forEach(this::rebuildFundChain);
            }

            // 往来流水：预收 IN，退款 OUT
            BigDecimal cpBal = advanceRefund
                    ? getCounterpartyBalance(cpType, cpCode).subtract(total)
                    : getCounterpartyBalance(cpType, cpCode).add(total);
            writeCounterpartyLedger(cpType, cpCode, cpName, advanceRefund ? "OUT" : "IN", total,
                    receiptNo, advanceRefund ? "RECEIPT_REFUND" : "RECEIPT", cpBal, summary);

            // 客户账户预收流水（退款内含「预收余额不足」中文校验，失败整单回滚）
            if (advanceRefund) {
                customerAccountService.postAdvanceRefund(receiptNo, receiptDate.toLocalDate(),
                        cpCode, cpName, total, summary);
            } else {
                customerAccountService.postAdvanceReceipt(receiptNo, receiptDate.toLocalDate(),
                        cpCode, cpName, total, summary);
            }
        } else {
            // 1. 写核销记录：按明细行逐条匹配待核销业务单据
            BigDecimal remaining = total;
            for (Map<String, Object> d : details) {
                BigDecimal lineAmount = toBd(d.get("amount"));
                if (lineAmount.signum() <= 0 || remaining.signum() <= 0) continue;
                BigDecimal actual = lineAmount.min(remaining);
                // 匹配该往来单位名下未核销的 AR/AP
                BigDecimal unmatched = reconcileAndRecord(receiptNo, receiptDate, cpType, cpCode, cpName,
                        actual, summary, str(d.get("remark")));
                remaining = remaining.subtract(actual.subtract(unmatched));
            }
            // 回写已核销金额（列表核销状态/核销金额列取此列；M2 重构时漏写导致永远显示未核销）
            BigDecimal verified = total.subtract(remaining);
            jdbcTemplate.update("UPDATE fin_receipt_bill SET verified_amount = ? WHERE receipt_id = ?",
                    verified, id);

            // 2. 写资金流水 + 更新账户余额（按明细行逐条；流水归属日 = 记账日期，v1.3）
            java.util.Set<String> touchedFundAccounts = new java.util.LinkedHashSet<>();
            for (Map<String, Object> d : details) {
                BigDecimal amt = toBd(d.get("amount"));
                if (amt.signum() <= 0) continue;
                String fundAcct = str(d.get("fundAccount"));
                BigDecimal bal = getFundBalance(fundAcct).add(amt);
                insertFundLedgerV2(fundAcct, "IN", amt, receiptNo, bal, auditor, receiptDate.toLocalDate());
                updateFundBalance(fundAcct, bal);
                touchedFundAccounts.add(fundAcct);
            }
            // 补录往日记账时按发生时间重排余额链，保证日结资金滚存可勾稽
            touchedFundAccounts.forEach(this::rebuildFundChain);

            // 3. 写往来流水
            BigDecimal cpBal = getCounterpartyBalance(cpType, cpCode).add(total);
            writeCounterpartyLedger(cpType, cpCode, cpName, "IN", total, receiptNo, "RECEIPT", cpBal, summary);
        }

        // 更新收款单状态（记账日期为空时持久化审核当天，存量行为留痕）
        if (receiptDateMissing) {
            jdbcTemplate.update("""
                    UPDATE fin_receipt_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?, receipt_date = ?
                    WHERE receipt_id = ?
                    """, auditor, java.sql.Timestamp.valueOf(now), receiptDate, id);
        } else {
            jdbcTemplate.update("""
                    UPDATE fin_receipt_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?
                    WHERE receipt_id = ?
                    """, auditor, java.sql.Timestamp.valueOf(now), id);
        }
        // 总账钩子：按收款类型丢不同事件码（钩子内按 business_source 过滤自动单）
        if (advance) {
            glHooks.onAdvanceReceiptAudited(receiptNo);
        } else if (advanceRefund) {
            glHooks.onAdvanceRefundAudited(receiptNo);
        } else {
            glHooks.onReceiptAudited(receiptNo);
        }
        finLog(com.erp.system.OperationModule.FIN_RECEIPT, com.erp.system.OperationAction.AUDIT,
                com.erp.system.KeyFields.BIZ_FIN_RECEIPT, receiptNo,
                "审核收款单 " + receiptNo + "（" + receiptTypeText(receiptType) + "）");
    }

    /**
     * 取消审核：冲减流水、删除核销记录、回退 AR/AP 已收金额。
     * 取消后可修改后重新审核。
     */
    @RequirePerm(value = "fin.receipt.unaudit", name = "反审核")
    @PostMapping("/receipt/cancel-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> cancelAuditReceipt(@RequestBody Map<String, Object> body) {
        String id = str(body.get("receiptId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM fin_receipt_bill WHERE receipt_id = ?", id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "收款单不存在");
        Map<String, Object> r = heads.get(0);
        if (!"APPROVED".equals(str(r.get("status"))))
            return ApiResponse.fail("400", "仅已审核单据可取消审核");
        assertHeadCpVisible(r);

        String receiptNo = str(r.get("receiptNo"));
        String cpType = str(r.get("counterpartyType"));
        String cpCode = str(r.get("counterpartyCode"));
        String cpName = str(r.get("counterpartyName"));
        BigDecimal total = toBd(r.get("totalAmount"));
        String summary = str(r.get("summary"));
        String receiptType = normalizeReceiptType(str(r.get("receiptType")), cpType);
        boolean advance = com.erp.finance.account.CustomerAccountConst.RECEIPT_ADVANCE.equals(receiptType);
        boolean advanceRefund =
                com.erp.finance.account.CustomerAccountConst.RECEIPT_ADVANCE_REFUND.equals(receiptType);
        // v1.3：反审核按记账日期判封单（审核时空日期已持久化为当天）
        java.sql.Date receiptDate = r.get("receiptDate") instanceof java.sql.Date d ? d : null;
        dayCloseGuard.assertWritable(receiptDate == null ? null : receiptDate.toLocalDate(),
                "收款单", receiptNo);

        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_receipt_detail WHERE receipt_id = ? ORDER BY sort_order", id);
        String auditor = currentUser();
        java.util.Set<String> touchedFundAccounts = new java.util.LinkedHashSet<>();

        if (advance || advanceRefund) {
            // 预收类无核销记录；对称冲回客户账户预收流水
            // （预收冲回内含「该预收款已被核销/退款使用」中文校验，失败整单回滚）
            LocalDate flowDate = receiptDate == null ? LocalDate.now() : receiptDate.toLocalDate();
            if (advanceRefund) {
                customerAccountService.reverseAdvanceRefund(receiptNo, flowDate, cpCode, cpName, total, summary);
            } else {
                customerAccountService.reverseAdvanceReceipt(receiptNo, flowDate, cpCode, cpName, total, summary);
            }
            // 资金对称反向：预收 IN→OUT，退款 OUT→IN。
            // 例外：门店结算溢收自动预收单（DRIVER_OVERPAY_ADV）审核时本就没写资金流水
            //（钱在结算收款单里入过账），反审核同样不得动资金账户，只回预收/往来/GL。
            boolean storeOverpayAuto =
                    com.erp.finance.account.CustomerAccountConst.SOURCE_DRIVER_OVERPAY_ADV
                            .equals(str(r.get("businessSource")));
            if (!storeOverpayAuto) {
                for (Map<String, Object> d : details) {
                    BigDecimal amt = toBd(d.get("amount"));
                    if (amt.signum() <= 0) continue;
                    String fa = str(d.get("fundAccount"));
                    BigDecimal bal = advanceRefund
                            ? getFundBalance(fa).add(amt)
                            : getFundBalance(fa).subtract(amt);
                    insertFundLedgerV2(fa, advanceRefund ? "IN" : "OUT", amt, receiptNo + "(取消审核)",
                            bal, auditor, receiptDate == null ? null : receiptDate.toLocalDate());
                    updateFundBalance(fa, bal);
                    touchedFundAccounts.add(fa);
                }
                touchedFundAccounts.forEach(this::rebuildFundChain);
            }

            // 往来流水对称冲回
            BigDecimal cpBal = advanceRefund
                    ? getCounterpartyBalance(cpType, cpCode).add(total)
                    : getCounterpartyBalance(cpType, cpCode).subtract(total);
            writeCounterpartyLedger(cpType, cpCode, cpName, advanceRefund ? "IN" : "OUT", total,
                    receiptNo + "(取消审核)",
                    advanceRefund ? "RECEIPT_REFUND_CANCEL" : "RECEIPT_CANCEL", cpBal, "取消审核");
        } else {
            LocalDate flowDate = receiptDate == null ? LocalDate.now() : receiptDate.toLocalDate();
            String bizSource = str(r.get("businessSource"));

            // 0. PRD-35 M3：先级联反审核自动 XH 预收核销单（预收真值/流水先回滚，自动单置 CANCELLED）
            List<String> autoXhKeys = new java.util.ArrayList<>();
            if ("AR_SETTLE".equals(bizSource)) {
                autoXhKeys.addAll(advanceWriteoffService.findAutoWriteoffNos(
                        com.erp.finance.account.AdvanceWriteoffService.SOURCE_AR_SETTLE, List.of(receiptNo)));
            } else if ("CUSTOMER_STATEMENT".equals(bizSource)) {
                String related = str(r.get("relatedBillNo"));
                if (!related.isEmpty()) {
                    autoXhKeys.addAll(advanceWriteoffService.findAutoWriteoffNos(
                            com.erp.finance.account.AdvanceWriteoffService.SOURCE_STATEMENT_SETTLE,
                            java.util.Arrays.asList(related.split(","))));
                }
            }
            // 对账单回退用：级联前记下每张 XH 的核销合计与所属对账单
            java.util.Map<String, BigDecimal> xhTotalByStmt = new java.util.LinkedHashMap<>();
            for (String xhNo : autoXhKeys) {
                List<Map<String, Object>> xhHeads = queryCamel(
                        "SELECT total_amount,source_bill_no FROM fin_advance_writeoff WHERE writeoff_no=?", xhNo);
                if (!xhHeads.isEmpty()) {
                    xhTotalByStmt.merge(str(xhHeads.get(0).get("sourceBillNo")),
                            toBd(xhHeads.get(0).get("totalAmount")), BigDecimal::add);
                }
                advanceWriteoffService.cancelByCascade(xhNo, auditor);
            }

            // 1. 取核销记录，逐条回退 AR/AP；同时构造在线现金 AR 流水的红字冲回行
            List<Map<String, Object>> records = queryCamel(
                    "SELECT * FROM fin_reconcile_record WHERE receipt_no = ?", receiptNo);
            // 客户编码 → 冲回行（编码按 AR 真值解析，历史 /ar/settle 单 counterparty_code 存的是名称）
            java.util.Map<String, java.util.List<com.erp.finance.account.CustomerAccountService.ArCashLine>> reverseByCode =
                    new java.util.LinkedHashMap<>();
            java.util.Set<String> touchedBills = new java.util.LinkedHashSet<>();
            for (Map<String, Object> rec : records) {
                String bizNo = str(rec.get("businessNo"));
                BigDecimal amt = toBd(rec.get("reconcileAmount"));
                String recType = str(rec.get("businessType"));
                // 回退 AR/AP 已收
                jdbcTemplate.update(
                        "UPDATE fin_ar SET received_amount = received_amount - ?, unreceived_amount = unreceived_amount + ?, status = 'UNVERIFIED' WHERE ar_no = ?",
                        amt, amt, bizNo);
                jdbcTemplate.update(
                        "UPDATE fin_ap SET paid_amount = paid_amount - ?, unpaid_amount = unpaid_amount + ?, status = 'UNVERIFIED' WHERE ap_no = ?",
                        amt, amt, bizNo);
                if ("AR_SETTLE".equals(recType) || "CUSTOMER_STATEMENT".equals(recType)) {
                    List<Map<String, Object>> ars = queryCamel(
                            "SELECT source_bill,customer FROM fin_ar WHERE ar_no=?", bizNo);
                    String sourceBill = str(rec.get("sourceBill"));
                    String arName = cpName;
                    if (!ars.isEmpty()) {
                        if (sourceBill.isEmpty()) sourceBill = str(ars.get(0).get("sourceBill"));
                        arName = str(ars.get(0).get("customer"));
                    }
                    String code = customerAccountService.resolveArCustomerCodeByArNo(bizNo);
                    if (code.isEmpty()) code = cpCode;
                    reverseByCode.computeIfAbsent(code, k -> new java.util.ArrayList<>())
                            .add(new com.erp.finance.account.CustomerAccountService.ArCashLine(
                                    str(rec.get("recordId")), bizNo, sourceBill, amt, receiptNo));
                    if (!sourceBill.isEmpty()) touchedBills.add(sourceBill);
                }
            }

            // 2. 删除核销流水
            jdbcTemplate.update("DELETE FROM fin_reconcile_record WHERE receipt_no = ?", receiptNo);
            // 在线现金 AR 流水红字冲回（真值已回退，形成行标志按真值复位；历史无在线行自动跳过）
            reverseByCode.forEach((code, lines) ->
                    customerAccountService.reverseArCashSettle(flowDate, code,
                            lines.isEmpty() ? cpName : customerNameByCode(code, cpName), lines));

            // 2b. 对账单结算：按单回退已收/抹零（现金=核销记录映射，预收=XH 合计，抹零=抹零记录）
            if ("CUSTOMER_STATEMENT".equals(bizSource) && !str(r.get("relatedBillNo")).isEmpty()) {
                java.util.Map<String, BigDecimal> cashByStmt = new java.util.LinkedHashMap<>();
                java.util.Map<String, BigDecimal> woByStmt = new java.util.LinkedHashMap<>();
                for (Map<String, Object> rec : records) {
                    String recType = str(rec.get("businessType"));
                    BigDecimal amt = toBd(rec.get("reconcileAmount"));
                    if ("CUSTOMER_STATEMENT".equals(recType)) {
                        String stmtNo = stmtNoBySourceBill(str(rec.get("sourceBill")),
                                str(r.get("relatedBillNo")));
                        if (!stmtNo.isEmpty()) cashByStmt.merge(stmtNo, amt, BigDecimal::add);
                    } else if ("EXPENSE_WRITEOFF".equals(recType)) {
                        woByStmt.merge(str(rec.get("businessNo")), amt, BigDecimal::add);
                    }
                }
                for (String stmtNo : str(r.get("relatedBillNo")).split(",")) {
                    String no = stmtNo.trim();
                    if (no.isEmpty()) continue;
                    BigDecimal back = cashByStmt.getOrDefault(no, BigDecimal.ZERO)
                            .add(xhTotalByStmt.getOrDefault(no, BigDecimal.ZERO))
                            .add(woByStmt.getOrDefault(no, BigDecimal.ZERO));
                    BigDecimal woBack = woByStmt.getOrDefault(no, BigDecimal.ZERO);
                    if (back.signum() == 0 && woBack.signum() == 0) continue;
                    jdbcTemplate.update("UPDATE fin_customer_statement SET "
                            + "paid_amount=CASE WHEN paid_amount-?<0 THEN 0 ELSE paid_amount-? END, "
                            + "write_off_amount=CASE WHEN write_off_amount-?<0 THEN 0 ELSE write_off_amount-? END, "
                            + "pay_status=CASE WHEN paid_amount-?>=total_amount THEN '完成收款' "
                            + "WHEN paid_amount-?>0 THEN '部分收款' ELSE '未收款' END "
                            + "WHERE statement_no=?",
                            back, back, woBack, woBack, back, back, no);
                }
            }

            // 3. 冲减资金流水：写对冲记录（归属日同原记账日期）
            for (Map<String, Object> d : details) {
                BigDecimal amt = toBd(d.get("amount"));
                if (amt.signum() <= 0) continue;
                String fa = str(d.get("fundAccount"));
                BigDecimal bal = getFundBalance(fa).subtract(amt);
                insertFundLedgerV2(fa, "OUT", amt, receiptNo + "(取消审核)", bal, auditor,
                        receiptDate == null ? null : receiptDate.toLocalDate());
                updateFundBalance(fa, bal);
                touchedFundAccounts.add(fa);
            }
            touchedFundAccounts.forEach(this::rebuildFundChain);

            // 4. 冲减往来流水（仅现金部分；全额预收无 SK 资金/往来流水）
            if (total.signum() > 0) {
                BigDecimal cpBal = getCounterpartyBalance(cpType, cpCode).subtract(total);
                writeCounterpartyLedger(cpType, cpCode, cpName, "OUT", total,
                        receiptNo + "(取消审核)", "RECEIPT_CANCEL", cpBal, "取消审核");
            }
            // 回写发货单收款状态（XH 级联已刷它自己的来源单，这里补现金部分）
            touchedBills.forEach(advanceWriteoffService::refreshReceiveStatus);
        }

        // 5. 改回待审核（结算类核销记录已删，已核销金额同步清零；预收类本来就是 0）
        jdbcTemplate.update("UPDATE fin_receipt_bill SET status = 'PENDING', auditor_name = NULL, "
                + "audit_time = NULL, verified_amount = 0 WHERE receipt_id = ?", id);
        if (advance) {
            glHooks.onAdvanceReceiptUnaudited(receiptNo);
        } else if (advanceRefund) {
            glHooks.onAdvanceRefundUnaudited(receiptNo);
        } else {
            glHooks.onReceiptUnaudited(receiptNo);
        }
        finLog(com.erp.system.OperationModule.FIN_RECEIPT, com.erp.system.OperationAction.UN_AUDIT,
                com.erp.system.KeyFields.BIZ_FIN_RECEIPT, receiptNo,
                "反审核收款单 " + receiptNo + "（" + receiptTypeText(receiptType) + "）");
        return ApiResponse.ok(GenericResult.row("receiptNo", receiptNo, "status", "PENDING"));
    }

    /**
     * 供 TMS 司机交账审核联动调用：审核一张 DRIVER_SETTLE 收款单，
     * 完成「定向核销应收 + 资金入账 + 往来流水 + 单据转 APPROVED」。
     *
     * 为什么不直接复用 /receipt/audit：
     *   1. /receipt/audit 走 FIFO 按 due_date 找该客户所有未核销 AR，
     *      而 doReconcileAr 对 unreceived <= 0 的行会 continue —— 司机结算里
     *      退货生成的负向 AR 正好是负数，FIFO 会跳过它，把钱核到别的发货单上，
     *      导致本次结算的单据仍显示未收款；
     *   2. 门店结算明细 tms_store_settlement_detail.ar_no 已经精确记录了
     *      本次结算对应哪几张发货单的应收，可以定向核销，账目一一对应。
     *
     * 定向核销后若仍有余额（如 ar_no 缺失、AR 被别处核过），
     * 回落到原 FIFO 逻辑兜底，保证收进来的钱不会凭空消失。
     *
     * @param receiptId 收款单主键
     * @param arNos     本次结算对应的应收单号（可空/可含空串，内部去重过滤）
     * @return 收款单号 / 状态 / 已核销金额 / 未匹配余额
     */
    @Transactional
    public Map<String, Object> auditReceiptForSettle(String receiptId, List<String> arNos) {
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM fin_receipt_bill WHERE receipt_id = ?", receiptId);
        if (heads.isEmpty()) throw new IllegalStateException("收款单不存在：" + receiptId);
        Map<String, Object> r = heads.get(0);
        String receiptNo = str(r.get("receiptNo"));
        if (!"PENDING".equals(str(r.get("status")))) {
            // 幂等：交账单重复审核、或财务已手工审核过该收款单时直接跳过，不重复入账
            return GenericResult.row("receiptNo", receiptNo, "status", str(r.get("status")),
                    "skipped", Boolean.TRUE);
        }

        String auditor = currentUser();
        boolean receiptDateMissing = !(r.get("receiptDate") instanceof java.sql.Date);
        java.sql.Date receiptDate = receiptDateMissing
                ? java.sql.Date.valueOf(LocalDate.now()) : (java.sql.Date) r.get("receiptDate");
        // v1.3：交账联动审核同样按记账日期判封单
        dayCloseGuard.assertWritable(receiptDate.toLocalDate(), "收款单", receiptNo);
        String cpCode = str(r.get("counterpartyCode"));
        String cpName = str(r.get("counterpartyName"));
        BigDecimal total = toBd(r.get("totalAmount"));
        String summary = str(r.get("summary"));

        // 历史数据里 TMS 曾把 counterparty_type 写成中文「客户」，
        // 会让 getCounterpartyBalance 查不到同一客户的历史余额（往来余额分叉）。
        // 这里统一纠正到标准值域，并回写单据，保证后续取消审核对称。
        String cpType = str(r.get("counterpartyType"));
        if (!"CUSTOMER".equals(cpType) && !"SUPPLIER".equals(cpType) && !"COUNTERPARTY".equals(cpType)) {
            cpType = "CUSTOMER";
            jdbcTemplate.update("UPDATE fin_receipt_bill SET counterparty_type = ? WHERE receipt_id = ?",
                    cpType, receiptId);
        }

        // 1. 定向核销：按结算明细给出的 ar_no 逐张核
        BigDecimal remaining = total;
        if (arNos != null) {
            java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
            for (String no : arNos) {
                if (no != null && !no.isBlank()) targets.add(no.trim());
            }
            for (String arNo : targets) {
                if (remaining.signum() <= 0) break;
                List<Map<String, Object>> rows = queryCamel(
                        "SELECT * FROM fin_ar WHERE ar_no = ?", arNo);
                if (rows.isEmpty()) continue;
                Map<String, Object> ar = rows.get(0);
                BigDecimal unreceived = toBd(ar.get("unreceivedAmount"));
                if (unreceived.signum() <= 0) continue;
                BigDecimal actual = remaining.min(unreceived);
                BigDecimal newReceived = toBd(ar.get("receivedAmount")).add(actual);
                BigDecimal newUnreceived = toBd(ar.get("arAmount")).subtract(newReceived);
                String newStatus = newUnreceived.signum() <= 0 ? "VERIFIED" : "UNVERIFIED";
                jdbcTemplate.update(
                        "UPDATE fin_ar SET received_amount = ?, unreceived_amount = ?, status = ? WHERE ar_no = ?",
                        newReceived, newUnreceived, newStatus, arNo);
                writeReconcileRecordV2(receiptNo, receiptDate, arNo, str(ar.get("sourceBill")),
                        "SALES_RECEIPT", str(ar.get("dueDate")), cpType, cpCode, cpName,
                        actual, summary, "司机交账审核自动核销");
                remaining = remaining.subtract(actual);
            }
        }

        // 2. 兜底：定向核销没吃完的余额按原 FIFO 匹配该客户其他未核销应收
        if (remaining.signum() > 0) {
            remaining = reconcileAndRecord(receiptNo, receiptDate, cpType, cpCode, cpName,
                    remaining, summary, "司机交账审核自动核销");
        }

        // 3. 资金入账：按收款明细逐个账户写流水并推余额（归属日 = 记账日期，v1.3）
        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_receipt_detail WHERE receipt_id = ? ORDER BY sort_order", receiptId);
        java.util.Set<String> touchedFundAccounts = new java.util.LinkedHashSet<>();
        for (Map<String, Object> d : details) {
            BigDecimal amt = toBd(d.get("amount"));
            if (amt.signum() <= 0) continue;
            String fundAcct = str(d.get("fundAccount"));
            BigDecimal bal = getFundBalance(fundAcct).add(amt);
            insertFundLedgerV2(fundAcct, "IN", amt, receiptNo, bal, auditor, receiptDate.toLocalDate());
            updateFundBalance(fundAcct, bal);
            touchedFundAccounts.add(fundAcct);
        }
        touchedFundAccounts.forEach(this::rebuildFundChain);

        // 4. 往来流水
        BigDecimal cpBal = getCounterpartyBalance(cpType, cpCode).add(total);
        writeCounterpartyLedger(cpType, cpCode, cpName, "IN", total, receiptNo, "RECEIPT", cpBal, summary);

        // 5. 单据转已审核，并按实际核销额回写 verified_amount（空记账日期持久化当天）
        BigDecimal verified = total.subtract(remaining);
        if (receiptDateMissing) {
            jdbcTemplate.update("""
                    UPDATE fin_receipt_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?,
                        verified_amount = ?, receipt_date = ?
                    WHERE receipt_id = ?
                    """, auditor, java.sql.Timestamp.valueOf(LocalDateTime.now()), verified, receiptDate, receiptId);
        } else {
            jdbcTemplate.update("""
                    UPDATE fin_receipt_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?,
                        verified_amount = ?
                    WHERE receipt_id = ?
                    """, auditor, java.sql.Timestamp.valueOf(LocalDateTime.now()), verified, receiptId);
        }

        return GenericResult.row("receiptNo", receiptNo, "status", "APPROVED",
                "verifiedAmount", verified, "unmatchedAmount", remaining);
    }

    /**
     * 门店结算溢收自动转预收（PRD-35 M4）。交账审核核销后仍有 unmatchedAmount（客户预付、
     * 应收已被别处核销等导致实缴 &gt; 本次核销应收），且参数 tms.settle.overpay-to-advance=Y 时，
     * 由 TmsStoreSettleController 在同一交账审核事务内调用。
     *
     * <p>资金已在原门店结算收款单审核时按实缴全额入过账，这里<b>不重复写资金流水、不动资金账户
     * 余额</b>，只补四样东西：
     * <ol>
     *   <li>一张直接置 APPROVED 的 ADVANCE 收款单（related_bill_no=结算单号，可在收款单列表
     *       追溯/走标准反审核链，反审核即回补预收与 GL 红冲）；</li>
     *   <li>客户账户预收流水 ADV_RECEIPT（预收余额增加）；</li>
     *   <li>往来台账 IN 一行；</li>
     *   <li>总账 ADVANCE_RECEIPT 事件（借资金/贷 2203，资金科目取结算收款单首个资金账户）。</li>
     * </ol>
     *
     * @param settleNo        门店结算单号（溯源锚点，写 related_bill_no/摘要/流水摘要）
     * @param settleReceiptNo 原门店结算收款单号（取资金账户、摘要溯源）
     * @return 生成（或幂等命中已生成）的预收收款单号
     */
    @Transactional
    public String createAdvanceReceiptFromStoreOverpay(String settleNo, String settleReceiptNo,
                                                       String customerCode, String customerName,
                                                       BigDecimal overpayAmount) {
        if (customerCode == null || customerCode.isEmpty()) {
            throw new IllegalArgumentException("门店结算 " + settleNo + " 缺少客户编码，溢收无法自动转预收");
        }
        if (overpayAmount == null || overpayAmount.signum() <= 0) {
            throw new IllegalArgumentException("溢收金额必须大于 0");
        }
        // 幂等：交账单驳回后再审核、或重试时，同一结算单不重复生成预收单
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT receipt_no FROM fin_receipt_bill WHERE related_bill_no = ? "
                        + "AND business_source = ? AND receipt_type = 'ADVANCE' AND status = 'APPROVED' "
                        + "ORDER BY create_time LIMIT 1",
                String.class, settleNo,
                com.erp.finance.account.CustomerAccountConst.SOURCE_DRIVER_OVERPAY_ADV);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        LocalDate today = LocalDate.now();
        String advNo = billNoGen.nextNo("SK", "fin_receipt_bill", "receipt_no");
        // 审核按记账日期判封单（与普通收款单审核同一道闸）
        dayCloseGuard.assertWritable(today, "收款单", advNo);
        String auditor = currentUser();
        String summary = "门店结算溢收自动转预收 " + settleNo;

        // 资金账户：沿用结算收款单第一条正额明细的账户（GL 借资金科目取它；本单不再记资金流水）
        List<String> accts = jdbcTemplate.queryForList(
                "SELECT d.fund_account FROM fin_receipt_detail d "
                        + "JOIN fin_receipt_bill b ON b.receipt_id = d.receipt_id "
                        + "WHERE b.receipt_no = ? AND COALESCE(d.amount,0) > 0 "
                        + "ORDER BY d.sort_order LIMIT 1",
                String.class, settleReceiptNo == null ? "" : settleReceiptNo);
        String fundAcct = accts.isEmpty() || accts.get(0) == null ? "" : accts.get(0);

        String advId = TmsUtil.uuid("SK");
        jdbcTemplate.update("""
                INSERT INTO fin_receipt_bill(receipt_id, receipt_no, receipt_date, status,
                    counterparty_type, counterparty_code, counterparty_name,
                    handler, related_bill_no, summary, business_source, receipt_type,
                    total_amount, verified_amount,
                    object_name, fund_account, amount,
                    auditor_name, audit_time, creator_name, create_time)
                VALUES (?, ?, ?, 'APPROVED', 'CUSTOMER', ?, ?, ?, ?, ?, ?, 'ADVANCE', ?, 0,
                    ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)
                """, advId, advNo, java.sql.Date.valueOf(today),
                customerCode, customerName, auditor, settleNo, summary,
                com.erp.finance.account.CustomerAccountConst.SOURCE_DRIVER_OVERPAY_ADV,
                overpayAmount, customerName, fundAcct, overpayAmount,
                auditor, "门店结算自动单 " + settleNo);
        jdbcTemplate.update("""
                INSERT INTO fin_receipt_detail(detail_id, receipt_id, fund_account, amount, remark, sort_order)
                VALUES (?, ?, ?, ?, ?, 1)
                """, TmsUtil.uuid("SKD"), advId, fundAcct, overpayAmount,
                "门店结算 " + settleNo + " 溢收自动转预收（资金流水见结算收款单 " + settleReceiptNo + "）");

        // 往来台账 IN（与普通预收收款同口径）
        BigDecimal cpBal = getCounterpartyBalance("CUSTOMER", customerCode).add(overpayAmount);
        writeCounterpartyLedger("CUSTOMER", customerCode, customerName, "IN", overpayAmount,
                advNo, "RECEIPT", cpBal, summary);

        // 客户账户预收流水（bizKey ADV_RECEIPT:<advNo>#1；反审核走收款单标准链即可对称冲回）
        customerAccountService.postAdvanceReceipt(advNo, today, customerCode, customerName,
                overpayAmount, "门店结算溢收 " + settleNo);

        // 总账事件：钩子内仅对 BACKOFFICE 与本来路显式放行，模板借资金/贷 2203（客户辅助）
        glHooks.onAdvanceReceiptAudited(advNo);

        finLog(com.erp.system.OperationModule.FIN_RECEIPT, com.erp.system.OperationAction.AUDIT,
                com.erp.system.KeyFields.BIZ_FIN_RECEIPT, advNo,
                "门店结算 " + settleNo + " 溢收 " + overpayAmount.toPlainString() + " 元自动转预收");
        return advNo;
    }

    // ============================================================
    // 付款单 CRUD（对称收款单）
    // ============================================================

    @RequirePerm(value = "fin.payment.edit", name = "修改")
    @PostMapping("/payment/update")
    public ApiResponse<Boolean> updatePayment(@RequestBody Map<String, Object> body) {
        String id = str(body.get("paymentId"));
        List<Map<String, Object>> exist = queryCamel(
                "SELECT status, payment_no, counterparty_type, counterparty_name FROM fin_payment_bill WHERE payment_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "付款单不存在");
        if (!"PENDING".equals(str(exist.get(0).get("status"))))
            return ApiResponse.fail("400", "仅待审核单据可编辑");
        // PRD-28：旧往来单位与改后往来单位都须在数据范围内
        assertHeadCpVisible(exist.get(0));
        assertCpVisible(str(body.get("counterpartyType")), str(body.get("counterpartyName")));
        String paymentNo = str(exist.get(0).get("paymentNo"));
        BigDecimal total = sumDetails(body);
        // PRD-36 M2：待审核单允许改付款类型（预付类强制供应商往来）
        String paymentType = normalizePaymentType(str(body.get("paymentType")),
                str(body.get("counterpartyType")));
        jdbcTemplate.update("""
                UPDATE fin_payment_bill SET payment_date = ?, counterparty_type = ?,
                    counterparty_code = ?, counterparty_name = ?,
                    handler = ?, related_bill_no = ?, summary = ?, total_amount = ?, payment_type = ?
                WHERE payment_id = ?
                """, date(body, "paymentDate"), str(body.get("counterpartyType")),
                str(body.get("counterpartyCode")), str(body.get("counterpartyName")),
                str(body.get("handler")), str(body.get("relatedBillNo")),
                str(body.get("summary")), total, paymentType, id);
        jdbcTemplate.update("DELETE FROM fin_payment_detail WHERE payment_id = ?", id);
        insertPaymentDetails(id, body);
        finLog(com.erp.system.OperationModule.FIN_PAYMENT, com.erp.system.OperationAction.UPDATE,
                com.erp.system.KeyFields.BIZ_FIN_PAYMENT, paymentNo, "修改付款单 " + paymentNo);
        return ApiResponse.ok(true);
    }

    @RequirePerm(value = "fin.payment.delete", name = "删除")
    @PostMapping("/payment/delete")
    public ApiResponse<Boolean> deletePayment(@RequestBody Map<String, Object> body) {
        String id = str(body.get("paymentId"));
        List<Map<String, Object>> exist = queryCamel(
                "SELECT status, payment_no, counterparty_type, counterparty_name FROM fin_payment_bill WHERE payment_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "付款单不存在");
        if (!"PENDING".equals(str(exist.get(0).get("status"))))
            return ApiResponse.fail("400", "仅待审核单据可删除");
        assertHeadCpVisible(exist.get(0));
        String paymentNo = str(exist.get(0).get("paymentNo"));
        jdbcTemplate.update("DELETE FROM fin_payment_detail WHERE payment_id = ?", id);
        jdbcTemplate.update("DELETE FROM fin_payment_bill WHERE payment_id = ?", id);
        finLog(com.erp.system.OperationModule.FIN_PAYMENT, com.erp.system.OperationAction.DELETE,
                com.erp.system.KeyFields.BIZ_FIN_PAYMENT, paymentNo, "删除付款单 " + paymentNo);
        return ApiResponse.ok(true);
    }

    @RequirePerm(value = "fin.payment.audit", name = "审核")
    @PostMapping("/payment/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditPayment(@RequestBody Map<String, Object> body) {
        String id = str(body.get("paymentId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM fin_payment_bill WHERE payment_id = ?", id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "付款单不存在");
        Map<String, Object> r = heads.get(0);
        if (!"PENDING".equals(str(r.get("status"))))
            return ApiResponse.fail("400", "仅待审核单据可审核");
        assertHeadCpVisible(r);
        doAuditPayment(r);
        return ApiResponse.ok(GenericResult.row("paymentNo", str(r.get("paymentNo")), "status", "APPROVED"));
    }

    /**
     * 付款单审核内核（PRD-36 M2，按 payment_type 三分支；单审/批量审核共用）。
     * SETTLE 应付结算：FIFO 核销 AP + 供应商账户在线 AP_SETTLE_CASH 流水 + 资金 OUT + 往来台账；
     * PREPAY 预付付款：只动预付余额（+）+ 资金 OUT + 往来台账，不核销 AP；
     * PREPAY_REFUND 预付退款：预付余额（−，内含余额守卫）+ 资金 IN + 往来台账。
     */
    private void doAuditPayment(Map<String, Object> r) {
        String id = str(r.get("paymentId"));
        LocalDateTime now = LocalDateTime.now();
        String auditor = currentUser();
        String paymentNo = str(r.get("paymentNo"));
        // v1.3：付款记账日期不回填用户已填值；为空按当天并持久化；审核按该日期判封单
        boolean paymentDateMissing = !(r.get("paymentDate") instanceof java.sql.Date);
        java.sql.Date paymentDate = paymentDateMissing
                ? java.sql.Date.valueOf(LocalDate.now()) : (java.sql.Date) r.get("paymentDate");
        dayCloseGuard.assertWritable(paymentDate.toLocalDate(), "付款单", paymentNo);
        String cpType = str(r.get("counterpartyType"));
        String cpCode = str(r.get("counterpartyCode"));
        String cpName = str(r.get("counterpartyName"));
        BigDecimal total = toBd(r.get("totalAmount"));
        String summary = str(r.get("summary"));
        String paymentType = str(r.get("paymentType"));
        if (paymentType.isEmpty()) paymentType = com.erp.finance.account.SupplierAccountConst.PAYMENT_SETTLE;
        // 预付类只认供应商；历史单/名称兜底：编码为空时按名称解析（口径同账户流水归属）
        String supplierCode = cpCode;
        if (!com.erp.finance.account.SupplierAccountConst.PAYMENT_SETTLE.equals(paymentType)
                && supplierCode.isEmpty()) {
            supplierCode = supplierAccountService.resolveCodeByName(cpName);
        }

        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_payment_detail WHERE payment_id = ? ORDER BY sort_order", id);

        if (com.erp.finance.account.SupplierAccountConst.PAYMENT_PREPAY.equals(paymentType)
                || com.erp.finance.account.SupplierAccountConst.PAYMENT_PREPAY_REFUND.equals(paymentType)) {
            boolean refund = com.erp.finance.account.SupplierAccountConst.PAYMENT_PREPAY_REFUND.equals(paymentType);
            // 1. 资金：预付付款 OUT，预付退款 IN（退款内含预付余额守卫，失败整单回滚）
            java.util.Set<String> touchedFundAccounts = new java.util.LinkedHashSet<>();
            for (Map<String, Object> d : details) {
                BigDecimal amt = toBd(d.get("amount"));
                if (amt.signum() <= 0) continue;
                String fundAcct = str(d.get("fundAccount"));
                BigDecimal bal = refund ? getFundBalance(fundAcct).add(amt)
                        : getFundBalance(fundAcct).subtract(amt);
                insertFundLedgerV2(fundAcct, refund ? "IN" : "OUT", amt, paymentNo, bal,
                        auditor, paymentDate.toLocalDate());
                updateFundBalance(fundAcct, bal);
                touchedFundAccounts.add(fundAcct);
            }
            touchedFundAccounts.forEach(this::rebuildFundChain);

            // 2. 往来台账
            BigDecimal cpBal = refund ? getCounterpartyBalance(cpType, cpCode).add(total)
                    : getCounterpartyBalance(cpType, cpCode).subtract(total);
            writeCounterpartyLedger(cpType, cpCode, cpName, refund ? "IN" : "OUT", total, paymentNo,
                    refund ? "PAYMENT_REFUND" : "PREPAY_PAYMENT", cpBal, summary);

            // 3. 供应商预付账户流水（postPrepayRefund 内含「预付余额不足」中文校验）
            if (refund) {
                supplierAccountService.postPrepayRefund(paymentNo, paymentDate.toLocalDate(),
                        supplierCode, cpName, total, summary);
            } else {
                supplierAccountService.postPrepayPayment(paymentNo, paymentDate.toLocalDate(),
                        supplierCode, cpName, total, summary);
            }
        } else {
            // 1. 写核销记录：按明细行逐条 FIFO 匹配待核销 AP
            BigDecimal remaining = total;
            for (Map<String, Object> d : details) {
                BigDecimal lineAmount = toBd(d.get("amount"));
                if (lineAmount.signum() <= 0 || remaining.signum() <= 0) continue;
                BigDecimal actual = lineAmount.min(remaining);
                // 匹配该供应商名下未核销的 AP
                BigDecimal unmatched = reconcileAndRecord(paymentNo, paymentDate, cpType, cpCode, cpName,
                        actual, summary, str(d.get("remark")));
                remaining = remaining.subtract(actual.subtract(unmatched));
            }

            // 1b. 供应商账户在线 AP 结算流水（bizKey APR:<核销记录id>，与数据修复补缺同源幂等）
            List<com.erp.finance.account.SupplierAccountService.ApCashLine> cashLines = new java.util.ArrayList<>();
            List<Map<String, Object>> apRecs = queryCamel(
                    "SELECT r.record_id, COALESCE(NULLIF(r.ar_no,''), r.business_no) ap_no, "
                            + "r.reconcile_amount, r.source_bill "
                            + "FROM fin_reconcile_record r "
                            + "WHERE r.receipt_no=? AND EXISTS (SELECT 1 FROM fin_ap a "
                            + "WHERE a.ap_no=COALESCE(NULLIF(r.ar_no,''), r.business_no))", paymentNo);
            for (Map<String, Object> rec : apRecs) {
                cashLines.add(new com.erp.finance.account.SupplierAccountService.ApCashLine(
                        str(rec.get("recordId")), str(rec.get("apNo")), str(rec.get("sourceBill")),
                        toBd(rec.get("reconcileAmount")), paymentNo));
            }
            if (!cashLines.isEmpty()) {
                String code = supplierAccountService.resolveApSupplierCodeByApNo(
                        cashLines.get(0).apNo);
                if (code.isEmpty()) code = cpCode.isEmpty()
                        ? supplierAccountService.resolveCodeByName(cpName) : cpCode;
                supplierAccountService.postApCashSettle(paymentDate.toLocalDate(), code, cpName, cashLines);
            }

            // 2. 写资金流水（OUT）+ 更新账户余额（流水归属日 = 记账日期，v1.3）
            java.util.Set<String> touchedFundAccounts = new java.util.LinkedHashSet<>();
            for (Map<String, Object> d : details) {
                BigDecimal amt = toBd(d.get("amount"));
                if (amt.signum() <= 0) continue;
                String fundAcct = str(d.get("fundAccount"));
                BigDecimal bal = getFundBalance(fundAcct).subtract(amt);
                insertFundLedgerV2(fundAcct, "OUT", amt, paymentNo, bal, auditor, paymentDate.toLocalDate());
                updateFundBalance(fundAcct, bal);
                touchedFundAccounts.add(fundAcct);
            }
            touchedFundAccounts.forEach(this::rebuildFundChain);

            // 3. 写往来流水（OUT：付款给供应商 → 往来余额减少）
            BigDecimal cpBal = getCounterpartyBalance(cpType, cpCode).subtract(total);
            writeCounterpartyLedger(cpType, cpCode, cpName, "OUT", total, paymentNo, "PAYMENT", cpBal, summary);
        }

        // 4. 更新付款单状态（记账日期为空时持久化当天）
        if (paymentDateMissing) {
            jdbcTemplate.update("""
                    UPDATE fin_payment_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?, payment_date = ?
                    WHERE payment_id = ?
                    """, auditor, java.sql.Timestamp.valueOf(now), paymentDate, id);
        } else {
            jdbcTemplate.update("""
                    UPDATE fin_payment_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?
                    WHERE payment_id = ?
                    """, auditor, java.sql.Timestamp.valueOf(now), id);
        }
        // GL：钩子内按 payment_type 分流 PAYMENT / PREPAY_PAYMENT / PREPAY_REFUND 事件
        glHooks.onPaymentAudited(paymentNo);
        finLog(com.erp.system.OperationModule.FIN_PAYMENT, com.erp.system.OperationAction.AUDIT,
                com.erp.system.KeyFields.BIZ_FIN_PAYMENT, paymentNo,
                "审核付款单 " + paymentNo + "（" + paymentTypeText(paymentType) + "）");
    }

    /** 付款单取消审核：按付款类型对称冲回（预付类无核销；结算类级联作废自动 FX 单）。 */
    @RequirePerm(value = "fin.payment.unaudit", name = "反审核")
    @PostMapping("/payment/cancel-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> cancelAuditPayment(@RequestBody Map<String, Object> body) {
        String id = str(body.get("paymentId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM fin_payment_bill WHERE payment_id = ?", id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "付款单不存在");
        Map<String, Object> r = heads.get(0);
        if (!"APPROVED".equals(str(r.get("status"))))
            return ApiResponse.fail("400", "仅已审核单据可反审核");
        assertHeadCpVisible(r);
        String paymentNo = str(r.get("paymentNo"));
        String cpType = str(r.get("counterpartyType"));
        String cpCode = str(r.get("counterpartyCode"));
        String cpName = str(r.get("counterpartyName"));
        BigDecimal total = toBd(r.get("totalAmount"));
        String paymentType = str(r.get("paymentType"));
        if (paymentType.isEmpty()) paymentType = com.erp.finance.account.SupplierAccountConst.PAYMENT_SETTLE;
        // v1.3：反审核按付款记账日期判封单
        java.sql.Date paymentDate = r.get("paymentDate") instanceof java.sql.Date d ? d : null;
        LocalDate flowDate = paymentDate == null ? LocalDate.now() : paymentDate.toLocalDate();
        dayCloseGuard.assertWritable(flowDate, "付款单", paymentNo);

        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_payment_detail WHERE payment_id = ? ORDER BY sort_order", id);
        String auditor = currentUser();
        java.util.Set<String> touchedFundAccounts = new java.util.LinkedHashSet<>();

        if (com.erp.finance.account.SupplierAccountConst.PAYMENT_PREPAY.equals(paymentType)
                || com.erp.finance.account.SupplierAccountConst.PAYMENT_PREPAY_REFUND.equals(paymentType)) {
            boolean refund = com.erp.finance.account.SupplierAccountConst.PAYMENT_PREPAY_REFUND.equals(paymentType);
            String supplierCode = cpCode.isEmpty()
                    ? supplierAccountService.resolveCodeByName(cpName) : cpCode;
            // 1. 供应商预付流水对称冲回（内含「该预付款已被核销/退款使用」中文守卫，失败整单回滚）
            if (refund) {
                supplierAccountService.reversePrepayRefund(paymentNo, flowDate, supplierCode, cpName,
                        total, str(r.get("summary")));
            } else {
                supplierAccountService.reversePrepayPayment(paymentNo, flowDate, supplierCode, cpName,
                        total, str(r.get("summary")));
            }
            // 2. 资金对称反向：预付付款 OUT→IN，预付退款 IN→OUT
            for (Map<String, Object> d : details) {
                BigDecimal amt = toBd(d.get("amount"));
                if (amt.signum() <= 0) continue;
                String fa = str(d.get("fundAccount"));
                BigDecimal bal = refund ? getFundBalance(fa).subtract(amt)
                        : getFundBalance(fa).add(amt);
                insertFundLedgerV2(fa, refund ? "OUT" : "IN", amt, paymentNo + "(取消审核)", bal,
                        auditor, paymentDate == null ? null : flowDate);
                updateFundBalance(fa, bal);
                touchedFundAccounts.add(fa);
            }
            touchedFundAccounts.forEach(this::rebuildFundChain);

            // 3. 往来台账对称冲回
            BigDecimal cpBal = refund ? getCounterpartyBalance(cpType, cpCode).subtract(total)
                    : getCounterpartyBalance(cpType, cpCode).add(total);
            writeCounterpartyLedger(cpType, cpCode, cpName, refund ? "OUT" : "IN", total,
                    paymentNo + "(取消审核)",
                    refund ? "PAYMENT_REFUND_CANCEL" : "PREPAY_PAYMENT_CANCEL", cpBal, "取消审核");
        } else {
            String bizSource = str(r.get("businessSource"));

            // 0. PRD-36 M2：先级联反审核自动 FX 预付核销单（预付真值/流水先回滚，自动单置 CANCELLED）。
            //    AP_SETTLE 锚点按 FK 单号查；供应商对账单结算按 related_bill_no 里的对账单号查。
            List<String> autoFxKeys = new java.util.ArrayList<>();
            if ("AP_SETTLE".equals(bizSource)) {
                autoFxKeys.addAll(prepayWriteoffService.findAutoWriteoffNos(
                        com.erp.finance.account.PrepayWriteoffService.SOURCE_AP_SETTLE, List.of(paymentNo)));
            } else if ("SUPPLIER_STATEMENT".equals(bizSource)) {
                String related = str(r.get("relatedBillNo"));
                if (!related.isEmpty()) {
                    autoFxKeys.addAll(prepayWriteoffService.findAutoWriteoffNos(
                            com.erp.finance.account.PrepayWriteoffService.SOURCE_STATEMENT_SETTLE,
                            java.util.Arrays.asList(related.split(","))));
                }
            }
            // 对账单回退用：级联前记下每张 FX 的核销合计与所属对账单
            java.util.Map<String, BigDecimal> fxTotalByStmt = new java.util.LinkedHashMap<>();
            for (String fxNo : autoFxKeys) {
                List<Map<String, Object>> fxHeads = queryCamel(
                        "SELECT total_amount,source_bill_no FROM fin_prepay_writeoff WHERE writeoff_no=?", fxNo);
                if (!fxHeads.isEmpty()) {
                    fxTotalByStmt.merge(str(fxHeads.get(0).get("sourceBillNo")),
                            toBd(fxHeads.get(0).get("totalAmount")), BigDecimal::add);
                }
                prepayWriteoffService.cancelByCascade(fxNo, auditor);
            }

            // 1. 取核销记录，逐条回退 AP（FX 的 PREPAY_WRITE_OFF 记录已在级联中删除并自行回退，
            //    这里只剩现金结算记录，不能重复回退）
            List<Map<String, Object>> records = queryCamel(
                    "SELECT * FROM fin_reconcile_record WHERE receipt_no = ?", paymentNo);
            java.util.Map<String, java.util.List<com.erp.finance.account.SupplierAccountService.ApCashLine>> reverseByCode =
                    new java.util.LinkedHashMap<>();
            for (Map<String, Object> rec : records) {
                String recType = str(rec.get("businessType"));
                if (com.erp.finance.account.SupplierAccountConst.BIZ_PREPAY_WRITE_OFF.equals(recType)
                        || "EXPENSE_WRITEOFF".equals(recType)) {
                    // 预付核销记录已由 FX 级联自行回退；抹零记录只冲对账单 write_off_amount，都不回 AP
                    continue;
                }
                String bizNo = str(rec.get("businessNo"));
                if (!str(rec.get("arNo")).isEmpty()) bizNo = str(rec.get("arNo"));
                BigDecimal amt = toBd(rec.get("reconcileAmount"));
                jdbcTemplate.update(
                    "UPDATE fin_ap SET paid_amount = paid_amount - ?, unpaid_amount = unpaid_amount + ?, status = 'UNVERIFIED' WHERE ap_no = ?",
                    amt, amt, bizNo);
                // 在线 AP 现金结算流水红字冲回行（编码按 AP 真值解析）
                List<Map<String, Object>> aps = queryCamel(
                        "SELECT source_bill FROM fin_ap WHERE ap_no=?", bizNo);
                String sourceBill = str(rec.get("sourceBill"));
                if (!aps.isEmpty() && sourceBill.isEmpty()) sourceBill = str(aps.get(0).get("sourceBill"));
                String code = supplierAccountService.resolveApSupplierCodeByApNo(bizNo);
                if (code.isEmpty()) code = cpCode.isEmpty()
                        ? supplierAccountService.resolveCodeByName(cpName) : cpCode;
                reverseByCode.computeIfAbsent(code, k -> new java.util.ArrayList<>())
                        .add(new com.erp.finance.account.SupplierAccountService.ApCashLine(
                                str(rec.get("recordId")), bizNo, sourceBill, amt, paymentNo));
            }

            // 2. 删除核销流水
            jdbcTemplate.update("DELETE FROM fin_reconcile_record WHERE receipt_no = ?", paymentNo);
            // 在线 AP 现金流水红字冲回（真值已回退，形成行标志按真值复位；历史无在线行自动跳过）
            reverseByCode.forEach((code, lines) ->
                    supplierAccountService.reverseApCashSettle(flowDate, code, cpName, lines));

            // 2b. 供应商对账单结算：按单回退已付（现金=核销记录映射，预付=FX 合计）
            if ("SUPPLIER_STATEMENT".equals(bizSource) && !str(r.get("relatedBillNo")).isEmpty()) {
                java.util.Map<String, BigDecimal> cashByStmt = new java.util.LinkedHashMap<>();
                java.util.Map<String, BigDecimal> woByStmt = new java.util.LinkedHashMap<>();
                for (Map<String, Object> rec : records) {
                    String recType = str(rec.get("businessType"));
                    BigDecimal amt = toBd(rec.get("reconcileAmount"));
                    if ("SUPPLIER_STATEMENT".equals(recType)) {
                        String stmtNo = supplierStmtNoBySourceBill(str(rec.get("sourceBill")),
                                str(r.get("relatedBillNo")));
                        if (!stmtNo.isEmpty()) cashByStmt.merge(stmtNo, amt, BigDecimal::add);
                    } else if ("EXPENSE_WRITEOFF".equals(recType)) {
                        // 抹零真值记录 business_no=对账单号
                        woByStmt.merge(str(rec.get("businessNo")), amt, BigDecimal::add);
                    }
                }
                for (String raw : str(r.get("relatedBillNo")).split(",")) {
                    String stmtNo = raw.trim();
                    if (stmtNo.isEmpty()) continue;
                    BigDecimal woBack = woByStmt.getOrDefault(stmtNo, BigDecimal.ZERO);
                    BigDecimal back = cashByStmt.getOrDefault(stmtNo, BigDecimal.ZERO)
                            .add(fxTotalByStmt.getOrDefault(stmtNo, BigDecimal.ZERO))
                            .add(woBack);
                    if (back.signum() == 0 && woBack.signum() == 0) continue;
                    // H2 同一 UPDATE 的 SET 右侧读旧行值，pay_status 直接在 SQL 里按旧 paid_amount 计算（镜像客户侧）
                    jdbcTemplate.update("UPDATE fin_supplier_statement SET "
                            + "paid_amount=CASE WHEN paid_amount-?<0 THEN 0 ELSE paid_amount-? END, "
                            + "write_off_amount=CASE WHEN write_off_amount-?<0 THEN 0 ELSE write_off_amount-? END, "
                            + "pay_status=CASE WHEN paid_amount-?>=total_amount THEN '完成付款' "
                            + "WHEN paid_amount-?>0 THEN '部分付款' ELSE '未付款' END "
                            + "WHERE statement_no=?",
                            back, back, woBack, woBack, back, back, stmtNo);
                }
            }

            // 3. 冲减资金流水：写对冲记录（IN，归属日同原记账日期）
            for (Map<String, Object> d : details) {
                BigDecimal amt = toBd(d.get("amount"));
                if (amt.signum() <= 0) continue;
                String fa = str(d.get("fundAccount"));
                BigDecimal bal = getFundBalance(fa).add(amt);
                insertFundLedgerV2(fa, "IN", amt, paymentNo + "(取消审核)", bal, auditor,
                        paymentDate == null ? null : flowDate);
                updateFundBalance(fa, bal);
                touchedFundAccounts.add(fa);
            }
            touchedFundAccounts.forEach(this::rebuildFundChain);

            // 4. 冲减往来流水（IN：取消付款 → 往来余额恢复；0 额锚点单不写）
            if (total.signum() > 0) {
                BigDecimal cpBal = getCounterpartyBalance(cpType, cpCode).add(total);
                writeCounterpartyLedger(cpType, cpCode, cpName, "IN", total,
                        paymentNo + "(取消审核)", "PAYMENT_CANCEL", cpBal, "取消审核");
            }
        }

        // 5. 改回待审核
        jdbcTemplate.update("UPDATE fin_payment_bill SET status = 'PENDING', auditor_name = NULL, audit_time = NULL WHERE payment_id = ?", id);
        glHooks.onPaymentUnaudited(paymentNo);
        finLog(com.erp.system.OperationModule.FIN_PAYMENT, com.erp.system.OperationAction.UN_AUDIT,
                com.erp.system.KeyFields.BIZ_FIN_PAYMENT, paymentNo,
                "反审核付款单 " + paymentNo + "（" + paymentTypeText(paymentType) + "）");
        return ApiResponse.ok(GenericResult.row("paymentNo", paymentNo, "status", "PENDING"));
    }

    /** 来源单号 → 所属供应商对账单号（限定在本付款单关联的对账单范围内查明细）。 */
    private String supplierStmtNoBySourceBill(String sourceBill, String relatedStmtNos) {
        if (sourceBill == null || sourceBill.isEmpty()
                || relatedStmtNos == null || relatedStmtNos.isEmpty()) {
            return "";
        }
        for (String raw : relatedStmtNos.split(",")) {
            String no = raw.trim();
            if (no.isEmpty()) continue;
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fin_supplier_statement_detail sd "
                            + "JOIN fin_supplier_statement s ON s.statement_id = sd.statement_id "
                            + "WHERE s.statement_no = ? AND sd.source_bill_no = ?",
                    Integer.class, no, sourceBill);
            if (cnt != null && cnt > 0) return no;
        }
        return "";
    }

    @RequirePerm(value = "fin.payment.audit", name = "审核")
    @PostMapping("/payment/batch-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> batchAuditPayment(@RequestBody Map<String, Object> body) {
        Object raw = body.get("receiptIds"); // 前端统一用 receiptIds 传
        if (!(raw instanceof List<?> list) || list.isEmpty())
            return ApiResponse.fail("400", "请选择要审核的付款单");
        int ok = 0, skip = 0;
        for (Object item : list) {
            String id = str(item);
            List<Map<String, Object>> heads = queryCamel(
                    "SELECT * FROM fin_payment_bill WHERE payment_id = ?", id);
            if (heads.isEmpty()) { skip++; continue; }
            Map<String, Object> r = heads.get(0);
            if (!"PENDING".equals(str(r.get("status")))) { skip++; continue; }
            assertHeadCpVisible(r);
            // PRD-36 M2：批量审核与单审同口径（核销/预付/退款全流程），不再只翻状态，
            // 否则预付类批量审核余额不动、核销记录不写
            doAuditPayment(r);
            ok++;
        }
        finLog(com.erp.system.OperationModule.FIN_PAYMENT, com.erp.system.OperationAction.AUDIT,
                com.erp.system.KeyFields.BIZ_FIN_PAYMENT, "", "批量审核付款单 " + ok + " 张");
        return ApiResponse.ok(Map.of("audited", ok, "skipped", skip));
    }

    /** 收款核销流水表 —— 只读查询 */
    @RequirePerm(value = "fin.receipt_writeoff.view", name = "查看")
    @PostMapping("/reconcile-record/page")
    public ApiResponse<PageResult<Map<String, Object>>> reconcileRecordPage(@RequestBody PageRequest request) {
        // PRD-28：流水无建档人列，按往来单位维度收窄（未配任何维度 → 1=0，见 appendCpScope）
        StringBuilder sql = new StringBuilder("""
                SELECT record_id, receipt_no, receipt_date,
                       business_no, business_type, business_date,
                       counterparty_type, counterparty_code, counterparty_name,
                       reconcile_amount, receipt_remark, business_remark, created_at
                FROM fin_reconcile_record
                WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
        appendCpScope(sql, args, "counterparty_type", "counterparty_name", null);
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        String cpName = trimF(filters, "counterparty");
        if (!cpName.isEmpty()) { sql.append(" AND (counterparty_code LIKE ? OR counterparty_name LIKE ?)"); args.add("%"+cpName+"%"); args.add("%"+cpName+"%"); }
        String bizType = trimF(filters, "businessType");
        if (!bizType.isEmpty()) { sql.append(" AND business_type = ?"); args.add(bizType); }
        String dateFrom = trimF(filters, "dateFrom");
        if (!dateFrom.isEmpty()) { sql.append(" AND receipt_date >= ?"); args.add(dateFrom); }
        String dateTo = trimF(filters, "dateTo");
        if (!dateTo.isEmpty()) { sql.append(" AND receipt_date <= ?"); args.add(dateTo); }
        sql.append(" ORDER BY created_at DESC, receipt_no");
        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        fieldMasker.mask(rows, java.util.Map.of("reconcileAmount", "VIEW_SETTLE_DETAIL"));
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    // ==================== 内部辅助（V32 新增） ====================

    /** 取当前登录用户显示名 */
    private String currentUser() {
        try {
            String name = SecurityContextHolder.getContext().getAuthentication().getName();
            if (name != null && !name.isBlank()) return name;
        } catch (Exception ignored) {}
        return "管理员";
    }

    /** null-safe string */
    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    /** null-safe BigDecimal */
    private BigDecimal toBd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(String.valueOf(v));
    }

    /** 提取日期字段（yyyy-MM-dd 字符串 → java.sql.Date） */
    private java.sql.Date date(Map<String, Object> body, String key) {
        String s = str(body.get(key));
        if (s.isEmpty()) return java.sql.Date.valueOf(LocalDate.now());
        try { return java.sql.Date.valueOf(s.substring(0, 10)); } catch (Exception e) { return java.sql.Date.valueOf(LocalDate.now()); }
    }

    /** 明细行金额合计 */
    // ============================================================
    // 客户对账单 (V42)
    // ============================================================

    @RequirePerm(value = "fin.customer_recon.view", name = "查看")
    @PostMapping("/customer-statement/page")
    public ApiResponse<PageResult<Map<String, Object>>> csPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("SELECT * FROM fin_customer_statement WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        // PRD-28：客户 + 业务员 + 建档人三维度收窄
        dataScope.target().customer("customer_name").salesman("salesman").creator("creator_name").build()
                .appendTo(sql, args);
        String customer = trimF(filters, "customer");
        if (!customer.isEmpty()) { sql.append(" AND (customer_code LIKE ? OR customer_name LIKE ?)"); args.add("%"+customer+"%"); args.add("%"+customer+"%"); }
        String salesman = trimF(filters, "salesman");
        if (!salesman.isEmpty()) { sql.append(" AND salesman LIKE ?"); args.add("%"+salesman+"%"); }
        String dateFrom = trimF(filters, "dateFrom"); if (!dateFrom.isEmpty()) { sql.append(" AND statement_date >= ?"); args.add(dateFrom); }
        String dateTo = trimF(filters, "dateTo"); if (!dateTo.isEmpty()) { sql.append(" AND statement_date <= ?"); args.add(dateTo); }
        String payStatus = trimF(filters, "payStatus"); if (!payStatus.isEmpty()) { sql.append(" AND pay_status = ?"); args.add(payStatus); }
        String remark = trimF(filters, "remark"); if (!remark.isEmpty()) { sql.append(" AND remark LIKE ?"); args.add("%"+remark+"%"); }
        String status = trimF(filters, "status"); if (!status.isEmpty()) { sql.append(" AND status = ?"); args.add(status); }
        sql.append(" ORDER BY statement_no DESC");
        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("statusText", "APPROVED".equals(str(r.get("status"))) ? "已审核" : "PENDING".equals(str(r.get("status"))) ? "待审核" : str(r.get("status")));
        }
        fieldMasker.mask(rows, CS_MASK_KEYS);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @RequirePerm(value = "fin.customer_recon.add", name = "新增")
    @PostMapping("/customer-statement/create")
    public ApiResponse<Map<String, Object>> csCreate(@RequestBody Map<String, Object> body) {
        assertCpVisible("CUSTOMER", str(body.get("customerName")));
        String id = "CS" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = billNoGen.nextNo("CS", "fin_customer_statement", "statement_no");
        LocalDateTime now = LocalDateTime.now(); String op = currentUser();
        BigDecimal total = sumDetailField(body, "reconcileAmount");
        jdbcTemplate.update("INSERT INTO fin_customer_statement(statement_id,statement_no,customer_code,customer_name,salesman,statement_date,expected_pay_date,contact_name,contact_phone,total_amount,status,remark,creator_name,create_time) VALUES(?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?)",
                id, no, str(body.get("customerCode")), str(body.get("customerName")), str(body.get("salesman")), date(body,"statementDate"), date(body,"expectedPayDate"), str(body.get("contactName")), str(body.get("contactPhone")), total, str(body.get("remark")), op, java.sql.Timestamp.valueOf(now));
        insertCSDetails(id, body);
        // 新建时同步更新源单据对账状态为"对账中"
        updateSourceReconcileStatus(body, "对账中");
        return ApiResponse.ok(GenericResult.row("statementId", id, "statementNo", no));
    }

    /** 创建/编辑对账单时，同步更新源 AR/AP 的对账状态 */
    private void updateSourceReconcileStatus(Map<String, Object> body, String newStatus) {
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?,?> m)) continue;
            String billNo = str(m.get("sourceBillNo"));
            if (billNo.isEmpty()) continue;
            jdbcTemplate.update("UPDATE fin_ar SET reconcile_status = ? WHERE source_bill = ? OR ar_no = ?", newStatus, billNo, billNo);
            jdbcTemplate.update("UPDATE fin_ap SET reconcile_status = ? WHERE source_bill = ? OR ap_no = ?", newStatus, billNo, billNo);
        }
    }

    @RequirePerm(value = "fin.customer_recon.edit", name = "修改")
    @PostMapping("/customer-statement/update")
    public ApiResponse<Boolean> csUpdate(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status, customer_name FROM fin_customer_statement WHERE statement_id=?", id);
        if (ex.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可编辑");
        // PRD-28：改前/改后客户都须在数据范围内
        assertCpVisible("CUSTOMER", str(ex.get(0).get("customerName")));
        assertCpVisible("CUSTOMER", str(body.get("customerName")));
        BigDecimal total = sumDetailField(body, "reconcileAmount");
        jdbcTemplate.update("UPDATE fin_customer_statement SET customer_code=?,customer_name=?,salesman=?,statement_date=?,expected_pay_date=?,contact_name=?,contact_phone=?,total_amount=?,remark=? WHERE statement_id=?",
                str(body.get("customerCode")),str(body.get("customerName")),str(body.get("salesman")),date(body,"statementDate"),date(body,"expectedPayDate"),str(body.get("contactName")),str(body.get("contactPhone")),total,str(body.get("remark")),id);
        jdbcTemplate.update("DELETE FROM fin_customer_statement_detail WHERE statement_id=?",id);
        insertCSDetails(id, body);
        return ApiResponse.ok(true);
    }

    @RequirePerm(value = "fin.customer_recon.view", name = "查看")
    @PostMapping("/customer-statement/detail")
    public ApiResponse<Map<String, Object>> csDetail(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_customer_statement WHERE statement_id=? OR statement_no=?",id,id);
        if (heads.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        Map<String,Object> h = heads.get(0);
        assertCpVisible("CUSTOMER", str(h.get("customerName")));
        h.put("details", queryCamel("SELECT * FROM fin_customer_statement_detail WHERE statement_id=? ORDER BY sort_order", h.get("statementId")));
        fieldMasker.mask(h, CS_MASK_KEYS);
        return ApiResponse.ok(h);
    }

    @RequirePerm(value = "fin.customer_recon.delete", name = "删除")
    @PostMapping("/customer-statement/delete")
    public ApiResponse<Boolean> csDelete(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status, customer_name FROM fin_customer_statement WHERE statement_id=?",id);
        if (ex.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可删除");
        assertCpVisible("CUSTOMER", str(ex.get(0).get("customerName")));
        // 还原源单据对账状态
        List<Map<String, Object>> details = queryCamel("SELECT source_bill_no FROM fin_customer_statement_detail WHERE statement_id=?",id);
        for (Map<String, Object> d : details) {
            String bn = str(d.get("sourceBillNo"));
            jdbcTemplate.update("UPDATE fin_ar SET reconcile_status = '未对账' WHERE source_bill = ? OR ar_no = ?", bn, bn);
            jdbcTemplate.update("UPDATE fin_ap SET reconcile_status = '未对账' WHERE source_bill = ? OR ap_no = ?", bn, bn);
        }
        jdbcTemplate.update("DELETE FROM fin_customer_statement_detail WHERE statement_id=?",id);
        jdbcTemplate.update("DELETE FROM fin_customer_statement WHERE statement_id=?",id);
        return ApiResponse.ok(true);
    }

    @RequirePerm(value = "fin.customer_recon.audit", name = "审核")
    @PostMapping("/customer-statement/audit")
    public ApiResponse<Map<String, Object>> csAudit(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_customer_statement WHERE statement_id=?",id);
        if (heads.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(heads.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可审核");
        assertCpVisible("CUSTOMER", str(heads.get(0).get("customerName")));
        // 一般单据：审核生效日=当天（当天已封才拦），statement_date 为空时回填当天
        dayCloseGuard.assertWritable(LocalDate.now(), "客户对账单", str(heads.get(0).get("statementNo")));
        String auditor = currentUser(); LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("UPDATE fin_customer_statement SET status='APPROVED',auditor_name=?,audit_time=?,statement_date=COALESCE(statement_date, CURRENT_DATE) WHERE statement_id=?", auditor, java.sql.Timestamp.valueOf(now), id);
        // 更新源单据的对账状态为"已对账"
        List<Map<String, Object>> details = queryCamel("SELECT source_bill_no FROM fin_customer_statement_detail WHERE statement_id=?",id);
        for (Map<String, Object> d : details) {
            jdbcTemplate.update("UPDATE fin_ar SET reconcile_status='已对账' WHERE source_bill=? OR ar_no=?", str(d.get("sourceBillNo")), str(d.get("sourceBillNo")));
        }
        return ApiResponse.ok(GenericResult.row("statementNo", str(heads.get(0).get("statementNo")), "status","APPROVED"));
    }

    @RequirePerm(value = "fin.customer_recon.unaudit", name = "反审核")
    @PostMapping("/customer-statement/reverse-audit")
    public ApiResponse<Map<String, Object>> csReverseAudit(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_customer_statement WHERE statement_id=?",id);
        if (heads.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        Map<String,Object> h = heads.get(0);
        if (!"APPROVED".equals(str(h.get("status")))) return ApiResponse.fail("400","仅已审核可反审核");
        assertCpVisible("CUSTOMER", str(h.get("customerName")));
        if (toBd(h.get("paidAmount")).signum() > 0) return ApiResponse.fail("400","已有收款，不可反审核");
        // 日结封单守卫：按对账单日期判
        dayCloseGuard.assertBillWritable("fin_customer_statement", "statement_date", "statement_id",
                id, "客户对账单");
        jdbcTemplate.update("UPDATE fin_customer_statement SET status='PENDING',auditor_name=NULL,audit_time=NULL WHERE statement_id=?",id);
        // 还原源单据对账状态
        List<Map<String, Object>> details = queryCamel("SELECT source_bill_no FROM fin_customer_statement_detail WHERE statement_id=?",id);
        for (Map<String, Object> d : details) {
            jdbcTemplate.update("UPDATE fin_ar SET reconcile_status='对账中' WHERE source_bill=? OR ar_no=?", str(d.get("sourceBillNo")), str(d.get("sourceBillNo")));
        }
        return ApiResponse.ok(GenericResult.row("status","PENDING"));
    }

    /** 查询该客户未生成对账单的单据（添加单据弹窗数据源） */
    @RequirePerm(value = "fin.customer_recon.view", name = "查看")
    @PostMapping("/customer-statement/available-bills")
    public ApiResponse<List<Map<String, Object>>> csAvailableBills(@RequestBody Map<String, Object> body) {
        String customerName = str(body.get("customerName"));
        assertCpVisible("CUSTOMER", customerName);
        String dateFrom = str(body.get("dateFrom"));
        String dateTo = str(body.get("dateTo"));
        // 已在对账单中的单据排除
        String sql = "SELECT ar_no, source_bill, customer, ar_amount, received_amount, unreceived_amount, due_date FROM fin_ar WHERE customer = ? AND (unreceived_amount > 0 OR unreceived_amount < 0) AND ar_no NOT IN (SELECT source_bill_no FROM fin_customer_statement_detail)";
        List<Object> args = new java.util.ArrayList<>(); args.add(customerName);
        if (!dateFrom.isEmpty()) { sql += " AND due_date >= ?"; args.add(dateFrom); }
        if (!dateTo.isEmpty()) { sql += " AND due_date <= ?"; args.add(dateTo); }
        sql += " ORDER BY due_date";
        List<Map<String, Object>> rows = queryCamel(sql, args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("billType","销售发货"); r.put("billNo",str(r.get("sourceBill")));
            r.put("billDate",str(r.get("dueDate"))); r.put("billAmount",r.get("arAmount"));
            r.put("unsettledAmount",r.get("unreceivedAmount"));
        }
        java.util.Map<String,String> csKeys = new java.util.HashMap<>(AR_AMOUNT_KEYS);
        csKeys.put("billAmount", "VIEW_AR_BALANCE");
        csKeys.put("unsettledAmount", "VIEW_AR_BALANCE");
        fieldMasker.mask(rows, csKeys);
        return ApiResponse.ok(rows);
    }

    /** 供应商可对账单据（未生成对账单的未付款 AP） */
    @RequirePerm(value = "fin.supplier_recon.view", name = "查看")
    @PostMapping("/supplier-statement/available-bills")
    public ApiResponse<List<Map<String, Object>>> ssAvailableBills(@RequestBody Map<String, Object> body) {
        String supplierName = str(body.get("supplierName"));
        assertCpVisible("SUPPLIER", supplierName);
        String dateFrom = str(body.get("dateFrom"));
        String dateTo = str(body.get("dateTo"));
        String sql = "SELECT ap_no, source_bill, supplier, ap_amount, paid_amount, unpaid_amount, due_date FROM fin_ap WHERE supplier = ? AND (unpaid_amount > 0 OR unpaid_amount < 0) AND ap_no NOT IN (SELECT source_bill_no FROM fin_supplier_statement_detail)";
        List<Object> args = new java.util.ArrayList<>(); args.add(supplierName);
        if (!dateFrom.isEmpty()) { sql += " AND due_date >= ?"; args.add(dateFrom); }
        if (!dateTo.isEmpty()) { sql += " AND due_date <= ?"; args.add(dateTo); }
        sql += " ORDER BY due_date";
        List<Map<String, Object>> rows = queryCamel(sql, args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("billType","采购收货"); r.put("billNo",str(r.get("sourceBill")));
            r.put("billDate",str(r.get("dueDate"))); r.put("billAmount",r.get("apAmount"));
            r.put("unsettledAmount",r.get("unpaidAmount"));
        }
        java.util.Map<String,String> ssKeys = new java.util.HashMap<>(AP_AMOUNT_KEYS);
        ssKeys.put("billAmount", "VIEW_AP_BALANCE");
        ssKeys.put("unsettledAmount", "VIEW_AP_BALANCE");
        fieldMasker.mask(rows, ssKeys);
        return ApiResponse.ok(rows);
    }

    /** 对账单收款结算（PRD-35 M3：支持使用预收，预收部分自动生 XH 单，现金部分生 SK 单） */
    @RequirePerm(value = "fin.customer_recon.settle", name = "结算")
    @PostMapping("/customer-statement/settle")
    @Transactional
    public ApiResponse<Map<String, Object>> csSettle(@RequestBody Map<String, Object> body) {
        Object raw = body.get("statementIds");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return ApiResponse.fail("400","请选择对账单");
        String handler = str(body.get("handler"));
        String settleDate = str(body.get("settleDate"));
        String remark = str(body.get("remark"));
        BigDecimal writeOff = toBd(body.get("writeOff"));
        String writeOffExpType = str(body.get("writeOffExpenseType"));
        Object acctsRaw = body.get("accounts");
        // PRD-35 M3：本次使用预收（≤ min(净额, 客户预收余额)，FIFO 优先冲最早到期行）
        BigDecimal useAdvance = toBd(body.get("useAdvanceAmount"));
        if (useAdvance == null || useAdvance.signum() < 0) useAdvance = BigDecimal.ZERO;

        // 资金账户实收合计（现金部分）
        BigDecimal acctTotal = BigDecimal.ZERO;
        if (acctsRaw instanceof List<?> al0) for (Object o : al0)
            if (o instanceof Map<?,?> am0) acctTotal = acctTotal.add(toBd(am0.get("amount")));

        // 校验同一客户
        String firstCust = ""; String firstCustName = "";
        BigDecimal totalAmount = BigDecimal.ZERO;
        java.util.List<Map<String, Object>> statements = new java.util.ArrayList<>();
        for (Object item : list) {
            String sid = str(item);
            List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_customer_statement WHERE statement_id=?",sid);
            if (heads.isEmpty()) continue;
            Map<String, Object> h = heads.get(0);
            if (!"APPROVED".equals(str(h.get("status")))) return ApiResponse.fail("400","仅已审核可结算");
            String c = str(h.get("customerCode"));
            if (firstCust.isEmpty()) { firstCust = c; firstCustName = str(h.get("customerName")); }
            else if (!firstCust.equals(c)) return ApiResponse.fail("400","只能对同一客户的单据合并结算");
            totalAmount = totalAmount.add(toBd(h.get("totalAmount")).subtract(toBd(h.get("paidAmount"))));
            statements.add(h);
        }
        if (statements.isEmpty()) return ApiResponse.fail("400","未找到有效对账单");
        // PRD-28：禁止直接构造请求结算不可见客户的对账单
        assertCpVisible("CUSTOMER", firstCustName);

        BigDecimal netSettle = totalAmount.subtract(writeOff);
        BigDecimal cashNet = netSettle.subtract(useAdvance);
        if (cashNet.signum() < 0) {
            throw new IllegalArgumentException("抹零与使用预收合计不能超过结算总额 " + totalAmount + " 元");
        }
        if (acctTotal.compareTo(cashNet) != 0) {
            throw new IllegalArgumentException("资金账户合计 " + acctTotal.toPlainString()
                    + " 元须等于净额扣减预收后的金额 " + cashNet.toPlainString() + " 元");
        }
        if (useAdvance.signum() > 0) {
            BigDecimal advBal = customerAccountService.getAdvanceBalance(firstCust);
            if (useAdvance.compareTo(advBal) > 0) {
                throw new IllegalArgumentException("使用预收金额不能超过客户预收余额 " + advBal + " 元");
            }
        }
        java.sql.Date settleDateSql = java.sql.Date.valueOf(settleDate.isEmpty()?LocalDate.now().toString():settleDate);
        // v1.3：结算资金按用户录入结算日期归属，已封日期拒绝结算
        dayCloseGuard.assertWritable(settleDateSql.toLocalDate(), "客户对账结算", "");
        LocalDateTime now = LocalDateTime.now(); String op = currentUser();

        // 生成核销计划：跨对账单明细收集 AR 行（同 AR 全局只核销一次），再按到期日 FIFO
        // PlanRow: ar 行 / 所属对账单号 / 本次核销额 / 预收份额 / 现金份额
        java.util.Map<String, Object[]> planMeta = new java.util.LinkedHashMap<>();
        java.util.List<Map<String, Object>> planRows = new java.util.ArrayList<>();
        for (Map<String, Object> h : statements) {
            String stmtNo = str(h.get("statementNo"));
            List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_customer_statement_detail WHERE statement_id=? ORDER BY sort_order,detail_id",
                str(h.get("statementId")));
            for (Map<String, Object> d : details) {
                String sourceBillNo = str(d.get("sourceBillNo"));
                List<Map<String, Object>> arRows = queryCamel(
                    "SELECT * FROM fin_ar WHERE ar_no=? OR source_bill=?", sourceBillNo, sourceBillNo);
                if (arRows.isEmpty()) continue;
                Map<String, Object> ar = arRows.get(0);
                String arNo = str(ar.get("arNo"));
                if (planMeta.containsKey(arNo)) continue;
                BigDecimal unreceived = toBd(ar.get("unreceivedAmount"));
                if (unreceived.signum() <= 0) continue;
                BigDecimal recAmt = toBd(d.get("reconcileAmount")).min(unreceived);
                if (recAmt.signum() <= 0) continue;
                planMeta.put(arNo, new Object[]{stmtNo, recAmt, BigDecimal.ZERO, BigDecimal.ZERO});
                planRows.add(ar);
            }
        }
        planRows.sort(java.util.Comparator.comparing(
                (Map<String, Object> a) -> a.get("dueDate") instanceof java.sql.Date d ? d : java.sql.Date.valueOf("9999-12-31")));
        BigDecimal advRemain = useAdvance;
        for (Map<String, Object> ar : planRows) {
            String arNo = str(ar.get("arNo"));
            Object[] meta = planMeta.get(arNo);
            BigDecimal recAmt = (BigDecimal) meta[1];
            BigDecimal advPart = recAmt.min(advRemain);
            advRemain = advRemain.subtract(advPart);
            meta[2] = advPart;
            meta[3] = recAmt.subtract(advPart);
        }
        if (advRemain.signum() > 0) {
            throw new IllegalArgumentException("预收金额超出本次可核销的应收金额，剩余 " + advRemain + " 元");
        }

        // 始终生成收款单（即便全额预收，金额为 0）：结算凭证 + 反审核入口，
        // related_bill_no 带对账单号，自动 XH 单按对账单号挂接，反审核收款单即级联反核销
        String stmtNos = statements.stream().map(h -> str(h.get("statementNo")))
                .filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.joining(","));
        String relatedBills = stmtNos.length() > 100 ? stmtNos.substring(0, 100) : stmtNos;
        String receiptNo = billNoGen.nextNo("SK","fin_receipt_bill","receipt_no");
        String receiptId = "SK"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase();
        String firstAcct = "";
        if (acctsRaw instanceof List<?> al && !al.isEmpty() && al.get(0) instanceof Map<?,?> am) firstAcct = str(am.get("fundAccount"));
        jdbcTemplate.update("INSERT INTO fin_receipt_bill(receipt_id,receipt_no,receipt_date,status,counterparty_type,counterparty_code,counterparty_name,object_name,total_amount,verified_amount,fund_account,amount,business_source,handler,related_bill_no,summary,creator_name,create_time,auditor_name,audit_time) VALUES(?,?,?,'APPROVED','CUSTOMER',?,?,?,?,?,?,?,'CUSTOMER_STATEMENT',?,?,?,?,?,?,?)",
                receiptId,receiptNo,settleDateSql, firstCust,firstCust,firstCust,cashNet,cashNet,firstAcct,cashNet,handler,relatedBills,remark,op,java.sql.Timestamp.valueOf(now),op,java.sql.Timestamp.valueOf(now));

        // 写收款单明细（资金账户行）
        if (acctsRaw instanceof List<?> al) {
            int idx = 1;
            for (Object item : al) {
                if (!(item instanceof Map<?,?> am)) continue;
                jdbcTemplate.update("INSERT INTO fin_receipt_detail(detail_id,receipt_id,fund_account,amount,remark,sort_order) VALUES(?,?,?,?,?,?)",
                    "SKD"+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(),
                    receiptId, str(am.get("fundAccount")), toBd(am.get("amount")), "", idx++);
            }
        }

        // 对账单已收/抹零回写：已收=该单现金+预收实际核销额，抹零归第一张单
        java.util.Map<String, BigDecimal> appliedPerStmt = new java.util.LinkedHashMap();
        for (Object[] meta : planMeta.values()) {
            appliedPerStmt.merge((String) meta[0], ((BigDecimal) meta[2]).add((BigDecimal) meta[3]), BigDecimal::add);
        }
        boolean firstStmt = true;
        for (Map<String, Object> h : statements) {
            String stmtId = str(h.get("statementId"));
            String stmtNo = str(h.get("statementNo"));
            BigDecimal writeOffPart = firstStmt ? writeOff : BigDecimal.ZERO;
            firstStmt = false;
            BigDecimal paid = toBd(h.get("paidAmount"))
                    .add(appliedPerStmt.getOrDefault(stmtNo, BigDecimal.ZERO)).add(writeOffPart);
            BigDecimal writeOffTotal = toBd(h.get("writeOffAmount")).add(writeOffPart);
            // H2 同一条 UPDATE 的 SET 右侧一律读旧行值（不像 MySQL 按赋值顺序读新值），
            // pay_status 必须在 Java 侧按新已收额算好再落库，否则状态恒按旧值（未收款）判断
            String payStatus = paid.compareTo(toBd(h.get("totalAmount"))) >= 0 ? "完成收款"
                    : paid.signum() > 0 ? "部分收款" : "未收款";
            jdbcTemplate.update("UPDATE fin_customer_statement SET paid_amount=?,write_off_amount=?,pay_status=? WHERE statement_id=?",
                paid,writeOffTotal,payStatus,stmtId);
            // 抹零真值记录挂对账单号，供收款单反审核时按单冲回 write_off_amount
            if (writeOffPart.signum() != 0) {
                writeReconcileRecordV2(receiptNo, settleDateSql, stmtNo, stmtNo,
                        "EXPENSE_WRITEOFF", "", "CUSTOMER", firstCust, firstCustName,
                        writeOffPart, remark, "对账单结算抹零");
            }
        }

        // 逐 AR 行落真值：现金部分本端更新 fin_ar + 核销记录；预收部分交自动 XH 单
        java.util.List<com.erp.finance.account.CustomerAccountService.ArCashLine> cashLines = new java.util.ArrayList<>();
        java.util.Set<String> touchedBills = new java.util.LinkedHashSet<>();
        for (Map<String, Object> ar : planRows) {
            String arNo = str(ar.get("arNo"));
            Object[] meta = planMeta.get(arNo);
            BigDecimal cashPart = (BigDecimal) meta[3];
            String sourceBill = str(ar.get("sourceBill"));
            if (cashPart.signum() > 0) {
                BigDecimal newRcv = toBd(ar.get("receivedAmount")).add(cashPart);
                BigDecimal newUnrcv = toBd(ar.get("arAmount")).subtract(newRcv);
                jdbcTemplate.update("UPDATE fin_ar SET received_amount=?, unreceived_amount=?, status=? WHERE ar_no=?",
                    newRcv, newUnrcv, newUnrcv.signum()<=0?"VERIFIED":"UNVERIFIED", arNo);
                String recordId = writeReconcileRecordV2(receiptNo, settleDateSql, arNo, sourceBill,
                    "CUSTOMER_STATEMENT", str(ar.get("dueDate")), "CUSTOMER", firstCust, firstCustName,
                    cashPart, remark, "");
                cashLines.add(new com.erp.finance.account.CustomerAccountService.ArCashLine(
                        recordId, arNo, sourceBill, cashPart, receiptNo));
                touchedBills.add(sourceBill);
            }
        }
        // 现金部分在线写客户账户 AR 结算流水
        customerAccountService.postArCashSettle(settleDateSql.toLocalDate(), firstCust, firstCustName, cashLines);

        // 预收部分：按对账单各生一张自动 XH 单（source_bill_no=对账单号）
        java.util.List<String> autoWriteoffNos = new java.util.ArrayList<>();
        java.util.Map<String, java.util.List<Map<String, Object>>> advByStmt = new java.util.LinkedHashMap<>();
        for (Map<String, Object> ar : planRows) {
            String arNo = str(ar.get("arNo"));
            Object[] meta = planMeta.get(arNo);
            BigDecimal advPart = (BigDecimal) meta[2];
            if (advPart.signum() <= 0) continue;
            advByStmt.computeIfAbsent((String) meta[0], k -> new java.util.ArrayList<>());
            Map<String, Object> line = new java.util.LinkedHashMap<>();
            line.put("arNo", arNo);
            line.put("amount", advPart);
            advByStmt.get((String) meta[0]).add(line);
            touchedBills.add(str(ar.get("sourceBill")));
        }
        for (java.util.Map.Entry<String, java.util.List<Map<String, Object>>> e : advByStmt.entrySet()) {
            String xhNo = advanceWriteoffService.createAutoFromSettle(firstCust, firstCustName,
                    settleDateSql.toLocalDate(), handler,
                    com.erp.finance.account.AdvanceWriteoffService.SOURCE_STATEMENT_SETTLE,
                    e.getKey(), remark, e.getValue());
            if (!xhNo.isEmpty()) autoWriteoffNos.add(xhNo);
        }
        // 回写发货单收款状态
        touchedBills.forEach(advanceWriteoffService::refreshReceiveStatus);

        // 写资金流水 + 更新资金账户余额（仅现金部分；归属日 = 结算日期，v1.3）
        if (cashNet.signum() > 0) {
            java.util.Set<String> touchedFundAccounts = new java.util.LinkedHashSet<>();
            if (acctsRaw instanceof List<?> al) {
                for (Object item : al) {
                    if (!(item instanceof Map<?,?> am)) continue;
                    String acct = str(am.get("fundAccount"));
                    BigDecimal amt = toBd(am.get("amount"));
                    if (amt.signum() <= 0) continue;
                    BigDecimal bal = getFundBalance(acct).add(amt);
                    insertFundLedgerV2(acct, "IN", amt, receiptNo, bal, op, settleDateSql.toLocalDate());
                    updateFundBalance(acct, bal);
                    touchedFundAccounts.add(acct);
                }
            }
            touchedFundAccounts.forEach(this::rebuildFundChain);

            // 写往来流水（仅现金部分）
            BigDecimal cpBal = getCounterpartyBalance("CUSTOMER", firstCust).add(cashNet);
            writeCounterpartyLedger("CUSTOMER", firstCust, firstCustName, "IN", cashNet,
                receiptNo, "CUSTOMER_STATEMENT_SETTLE", cpBal, remark);
        }

        java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
        resp.put("receiptNo", receiptNo);
        resp.put("settleAmount", cashNet);
        resp.put("advanceAmount", useAdvance);
        resp.put("writeoffNos", autoWriteoffNos);
        fieldMasker.mask(resp, java.util.Map.of("settleAmount", "VIEW_SETTLE_DETAIL"));
        return ApiResponse.ok(resp);
    }

    private void insertCSDetails(String stmtId, Map<String, Object> body) {
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return;
        int idx = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?,?> m)) continue;
            jdbcTemplate.update("INSERT INTO fin_customer_statement_detail(detail_id,statement_id,source_bill_no,source_bill_date,source_bill_type,bill_amount,reconcile_amount,paid_amount,unpaid_amount,bill_remark,sort_order) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    "CSD"+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(), stmtId, str(m.get("sourceBillNo")), null, str(m.get("sourceBillType")), toBd(m.get("billAmount")), toBd(m.get("reconcileAmount")), BigDecimal.ZERO, toBd(m.get("unpaidAmount")), str(m.get("billRemark")), idx++);
        }
    }

    // ============================================================
    // 供应商对账单 (V43) — 核心端点参照客户对账单
    // ============================================================

    @RequirePerm(value = "fin.supplier_recon.view", name = "查看")
    @PostMapping("/supplier-statement/page")
    public ApiResponse<PageResult<Map<String, Object>>> ssPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("SELECT * FROM fin_supplier_statement WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        // PRD-28：供应商 + 采购员（employee 维度）+ 建档人收窄
        dataScope.target().supplier("supplier_name").salesman("buyer").creator("creator_name").build()
                .appendTo(sql, args);
        String supplier = trimF(filters, "supplier");
        if (!supplier.isEmpty()) { sql.append(" AND (supplier_code LIKE ? OR supplier_name LIKE ?)"); args.add("%"+supplier+"%"); args.add("%"+supplier+"%"); }
        String buyer = trimF(filters, "buyer");
        if (!buyer.isEmpty()) { sql.append(" AND buyer LIKE ?"); args.add("%"+buyer+"%"); }
        String dateFrom = trimF(filters, "dateFrom"); if (!dateFrom.isEmpty()) { sql.append(" AND statement_date >= ?"); args.add(dateFrom); }
        String dateTo = trimF(filters, "dateTo"); if (!dateTo.isEmpty()) { sql.append(" AND statement_date <= ?"); args.add(dateTo); }
        String payStatus = trimF(filters, "payStatus"); if (!payStatus.isEmpty()) { sql.append(" AND pay_status = ?"); args.add(payStatus); }
        sql.append(" ORDER BY statement_no DESC");
        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("statusText", "APPROVED".equals(str(r.get("status"))) ? "已审核" : "PENDING".equals(str(r.get("status"))) ? "待审核" : str(r.get("status")));
        }
        fieldMasker.mask(rows, SS_MASK_KEYS);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @RequirePerm(value = "fin.supplier_recon.add", name = "新增")
    @PostMapping("/supplier-statement/create")
    public ApiResponse<Map<String, Object>> ssCreate(@RequestBody Map<String, Object> body) {
        // 前端对账单表单统一用 customerName 传往来单位名称
        assertCpVisible("SUPPLIER", str(body.get("customerName")));
        String id = "SS" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = billNoGen.nextNo("SS", "fin_supplier_statement", "statement_no");
        LocalDateTime now = LocalDateTime.now(); String op = currentUser();
        BigDecimal total = sumDetailField(body, "reconcileAmount");
        jdbcTemplate.update("INSERT INTO fin_supplier_statement(statement_id,statement_no,supplier_code,supplier_name,buyer,statement_date,expected_pay_date,contact_name,contact_phone,is_invoiced,total_amount,status,remark,creator_name,create_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?)",
                id, no, str(body.get("customerCode")), str(body.get("customerName")), str(body.get("salesman")), date(body,"statementDate"), date(body,"expectedPayDate"), str(body.get("contactName")), str(body.get("contactPhone")), str(body.get("isInvoiced")), total, str(body.get("remark")), op, java.sql.Timestamp.valueOf(now));
        insertSSDetails(id, body);
        return ApiResponse.ok(GenericResult.row("statementId", id, "statementNo", no));
    }

    @RequirePerm(value = "fin.supplier_recon.edit", name = "修改")
    @PostMapping("/supplier-statement/update")
    public ApiResponse<Boolean> ssUpdate(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status, supplier_name FROM fin_supplier_statement WHERE statement_id=?", id);
        if (ex.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可编辑");
        // PRD-28：改前/改后供应商都须在数据范围内
        assertCpVisible("SUPPLIER", str(ex.get(0).get("supplierName")));
        assertCpVisible("SUPPLIER", str(body.get("customerName")));
        BigDecimal total = sumDetailField(body, "reconcileAmount");
        jdbcTemplate.update("UPDATE fin_supplier_statement SET supplier_code=?,supplier_name=?,buyer=?,statement_date=?,expected_pay_date=?,contact_name=?,contact_phone=?,is_invoiced=?,total_amount=?,remark=? WHERE statement_id=?",
                str(body.get("customerCode")),str(body.get("customerName")),str(body.get("salesman")),date(body,"statementDate"),date(body,"expectedPayDate"),str(body.get("contactName")),str(body.get("contactPhone")),str(body.get("isInvoiced")),total,str(body.get("remark")),id);
        jdbcTemplate.update("DELETE FROM fin_supplier_statement_detail WHERE statement_id=?",id);
        insertSSDetails(id, body);
        return ApiResponse.ok(true);
    }

    @RequirePerm(value = "fin.supplier_recon.delete", name = "删除")
    @PostMapping("/supplier-statement/delete")
    public ApiResponse<Boolean> ssDelete(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status, supplier_name FROM fin_supplier_statement WHERE statement_id=?",id);
        if (ex.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可删除");
        assertCpVisible("SUPPLIER", str(ex.get(0).get("supplierName")));
        jdbcTemplate.update("DELETE FROM fin_supplier_statement_detail WHERE statement_id=?",id);
        jdbcTemplate.update("DELETE FROM fin_supplier_statement WHERE statement_id=?",id);
        return ApiResponse.ok(true);
    }

    @RequirePerm(value = "fin.supplier_recon.view", name = "查看")
    @PostMapping("/supplier-statement/detail")
    public ApiResponse<Map<String, Object>> ssDetail(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_supplier_statement WHERE statement_id=? OR statement_no=?",id,id);
        if (heads.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        Map<String,Object> h = heads.get(0);
        assertCpVisible("SUPPLIER", str(h.get("supplierName")));
        h.put("details", queryCamel("SELECT * FROM fin_supplier_statement_detail WHERE statement_id=? ORDER BY sort_order", h.get("statementId")));
        fieldMasker.mask(h, SS_MASK_KEYS);
        return ApiResponse.ok(h);
    }

    @RequirePerm(value = "fin.supplier_recon.audit", name = "审核")
    @PostMapping("/supplier-statement/audit")
    public ApiResponse<Map<String, Object>> ssAudit(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_supplier_statement WHERE statement_id=?",id);
        if (heads.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(heads.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可审核");
        assertCpVisible("SUPPLIER", str(heads.get(0).get("supplierName")));
        // 一般单据：审核生效日=当天（当天已封才拦），statement_date 为空时回填当天
        dayCloseGuard.assertWritable(LocalDate.now(), "供应商对账单", str(heads.get(0).get("statementNo")));
        jdbcTemplate.update("UPDATE fin_supplier_statement SET status='APPROVED',auditor_name=?,audit_time=?,statement_date=COALESCE(statement_date, CURRENT_DATE) WHERE statement_id=?", currentUser(), java.sql.Timestamp.valueOf(LocalDateTime.now()), id);
        return ApiResponse.ok(GenericResult.row("status","APPROVED"));
    }

    @RequirePerm(value = "fin.supplier_recon.settle", name = "结算")
    @PostMapping("/supplier-statement/settle")
    @Transactional
    public ApiResponse<Map<String, Object>> ssSettle(@RequestBody Map<String, Object> body) {
        Object raw = body.get("statementIds");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return ApiResponse.fail("400","请选择对账单");
        String handler = str(body.get("handler"));
        String settleDate = str(body.get("settleDate"));
        String remark = str(body.get("remark"));
        BigDecimal writeOff = toBd(body.get("writeOff"));
        String writeOffExpType = str(body.get("writeOffExpenseType"));
        Object acctsRaw = body.get("accounts");
        // PRD-36 M2：本次使用预付（≤ min(净额, 供应商预付余额)，FIFO 优先冲最早到期应付行）
        BigDecimal usePrepay = toBd(body.get("usePrepayAmount"));
        if (usePrepay == null || usePrepay.signum() < 0) usePrepay = BigDecimal.ZERO;

        // 资金账户实付合计（现金部分）
        BigDecimal acctTotal = BigDecimal.ZERO;
        if (acctsRaw instanceof List<?> al0) for (Object o : al0)
            if (o instanceof Map<?,?> am0) acctTotal = acctTotal.add(toBd(am0.get("amount")));

        // 校验同一供应商
        String firstSupp = ""; String firstSuppName = "";
        BigDecimal totalAmount = BigDecimal.ZERO;
        java.util.List<Map<String, Object>> statements = new java.util.ArrayList<>();
        for (Object item : list) {
            String sid = str(item);
            List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_supplier_statement WHERE statement_id=?",sid);
            if (heads.isEmpty()) continue;
            Map<String, Object> h = heads.get(0);
            if (!"APPROVED".equals(str(h.get("status")))) return ApiResponse.fail("400","仅已审核可结算");
            String c = str(h.get("supplierCode"));
            if (firstSupp.isEmpty()) { firstSupp = c; firstSuppName = str(h.get("supplierName")); }
            else if (!firstSupp.equals(c)) return ApiResponse.fail("400","只能对同一供应商的单据合并结算");
            totalAmount = totalAmount.add(toBd(h.get("totalAmount")).subtract(toBd(h.get("paidAmount"))));
            statements.add(h);
        }
        if (statements.isEmpty()) return ApiResponse.fail("400","未找到有效对账单");
        // PRD-28：禁止直接构造请求结算不可见供应商的对账单
        assertCpVisible("SUPPLIER", firstSuppName);

        BigDecimal netSettle = totalAmount.subtract(writeOff);
        if (netSettle.signum() < 0) {
            throw new IllegalArgumentException("抹零金额不能超过结算总额 " + totalAmount + " 元");
        }
        BigDecimal cashNet = netSettle.subtract(usePrepay);
        if (cashNet.signum() < 0) {
            throw new IllegalArgumentException("抹零与使用预付合计不能超过结算总额 " + totalAmount + " 元");
        }
        if (acctTotal.compareTo(cashNet) != 0) {
            throw new IllegalArgumentException("资金账户合计 " + acctTotal.toPlainString()
                    + " 元须等于净额扣减预付后的金额 " + cashNet.toPlainString() + " 元");
        }
        if (usePrepay.signum() > 0) {
            BigDecimal prepayBal = supplierAccountService.getPrepayBalanceByName(firstSupp, firstSuppName);
            if (usePrepay.compareTo(prepayBal) > 0) {
                throw new IllegalArgumentException("使用预付金额不能超过供应商预付余额 " + prepayBal + " 元");
            }
        }
        java.sql.Date settleDateSql = java.sql.Date.valueOf(settleDate.isEmpty()?LocalDate.now().toString():settleDate);
        // v1.3：结算资金按用户录入结算日期归属，已封日期拒绝结算
        dayCloseGuard.assertWritable(settleDateSql.toLocalDate(), "供应商对账结算", "");
        LocalDateTime now = LocalDateTime.now(); String op = currentUser();

        // 供应商账户编码（对账单头理论上必带，兜底按名称解析供账户流水使用）
        String suppCode = firstSupp.isEmpty() ? supplierAccountService.resolveCodeByName(firstSuppName) : firstSupp;

        // 生成核销计划：跨对账单明细收集 AP 行（同 AP 全局只核销一次），再按到期日 FIFO
        // PlanMeta: [所属对账单号, 本次核销额, 预付份额, 现金份额]
        java.util.Map<String, Object[]> planMeta = new java.util.LinkedHashMap<>();
        java.util.List<Map<String, Object>> planRows = new java.util.ArrayList<>();
        for (Map<String, Object> h : statements) {
            String stmtNo = str(h.get("statementNo"));
            List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_supplier_statement_detail WHERE statement_id=? ORDER BY sort_order,detail_id",
                str(h.get("statementId")));
            for (Map<String, Object> d : details) {
                String sourceBillNo = str(d.get("sourceBillNo"));
                List<Map<String, Object>> apRows = queryCamel(
                    "SELECT * FROM fin_ap WHERE ap_no=? OR source_bill=?", sourceBillNo, sourceBillNo);
                if (apRows.isEmpty()) continue;
                Map<String, Object> ap = apRows.get(0);
                String apNo = str(ap.get("apNo"));
                if (planMeta.containsKey(apNo)) continue;
                BigDecimal unpaid = toBd(ap.get("unpaidAmount"));
                if (unpaid.signum() <= 0) continue;
                BigDecimal recAmt = toBd(d.get("reconcileAmount")).min(unpaid);
                if (recAmt.signum() <= 0) continue;
                planMeta.put(apNo, new Object[]{stmtNo, recAmt, BigDecimal.ZERO, BigDecimal.ZERO});
                planRows.add(ap);
            }
        }
        planRows.sort(java.util.Comparator.comparing(
                (Map<String, Object> a) -> a.get("dueDate") instanceof java.sql.Date d ? d : java.sql.Date.valueOf("9999-12-31")));
        BigDecimal prepayRemain = usePrepay;
        for (Map<String, Object> ap : planRows) {
            Object[] meta = planMeta.get(str(ap.get("apNo")));
            BigDecimal recAmt = (BigDecimal) meta[1];
            BigDecimal prepayPart = recAmt.min(prepayRemain);
            prepayRemain = prepayRemain.subtract(prepayPart);
            meta[2] = prepayPart;
            meta[3] = recAmt.subtract(prepayPart);
        }
        if (prepayRemain.signum() > 0) {
            throw new IllegalArgumentException("使用预付金额超出本次可核销的应付金额，剩余 " + prepayRemain + " 元");
        }

        // 始终生成付款单（即便全额预付，金额为 0）：结算锚点 + 反审核入口，
        // related_bill_no 带对账单号，自动 FX 单按对账单号挂接，反审核付款单即级联反核销
        String stmtNos = statements.stream().map(h -> str(h.get("statementNo")))
                .filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.joining(","));
        String relatedBills = stmtNos.length() > 100 ? stmtNos.substring(0, 100) : stmtNos;
        String paymentNo = billNoGen.nextNo("FK","fin_payment_bill","payment_no");
        String paymentId = "FK"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase();
        String firstAcct = "";
        if (acctsRaw instanceof List<?> al && !al.isEmpty() && al.get(0) instanceof Map<?,?> am) firstAcct = str(am.get("fundAccount"));
        jdbcTemplate.update("INSERT INTO fin_payment_bill(payment_id,payment_no,payment_date,status,counterparty_type,counterparty_code,counterparty_name,object_name,total_amount,verified_amount,fund_account,amount,payment_type,business_source,handler,related_bill_no,summary,creator_name,create_time,auditor_name,audit_time) "
                + "VALUES(?,?,?,'APPROVED','SUPPLIER',?,?,?,?,?,?,?,'SETTLE','SUPPLIER_STATEMENT',?,?,?,?,?,?,?)",
                paymentId,paymentNo,settleDateSql, firstSupp,firstSuppName,firstSuppName,cashNet,cashNet,firstAcct,cashNet,
                handler,relatedBills,remark,op,java.sql.Timestamp.valueOf(now),op,java.sql.Timestamp.valueOf(now));

        // 写付款单明细（资金账户行）
        if (acctsRaw instanceof List<?> al) {
            int idx = 1;
            for (Object item : al) {
                if (!(item instanceof Map<?,?> am)) continue;
                jdbcTemplate.update("INSERT INTO fin_payment_detail(detail_id,payment_id,fund_account,amount,remark,sort_order) VALUES(?,?,?,?,?,?)",
                    "FKD"+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(),
                    paymentId, str(am.get("fundAccount")), toBd(am.get("amount")), "", idx++);
            }
        }

        // 对账单已付/抹零回写：已付=该单现金+预付实际核销额，抹零归第一张单
        java.util.Map<String, BigDecimal> appliedPerStmt = new java.util.LinkedHashMap();
        for (Object[] meta : planMeta.values()) {
            appliedPerStmt.merge((String) meta[0], ((BigDecimal) meta[2]).add((BigDecimal) meta[3]), BigDecimal::add);
        }
        boolean firstStmt = true;
        for (Map<String, Object> h : statements) {
            String stmtId = str(h.get("statementId"));
            String stmtNo = str(h.get("statementNo"));
            BigDecimal writeOffPart = firstStmt ? writeOff : BigDecimal.ZERO;
            firstStmt = false;
            BigDecimal paid = toBd(h.get("paidAmount"))
                    .add(appliedPerStmt.getOrDefault(stmtNo, BigDecimal.ZERO)).add(writeOffPart);
            BigDecimal writeOffTotal = toBd(h.get("writeOffAmount")).add(writeOffPart);
            // H2 同一条 UPDATE 的 SET 右侧一律读旧行值，pay_status 在 Java 侧按新已付额算好再落库
            String payStatus = paid.compareTo(toBd(h.get("totalAmount"))) >= 0 ? "完成付款"
                    : paid.signum() > 0 ? "部分付款" : "未付款";
            jdbcTemplate.update("UPDATE fin_supplier_statement SET paid_amount=?,write_off_amount=?,pay_status=? WHERE statement_id=?",
                paid,writeOffTotal,payStatus,stmtId);
            // 抹零真值记录挂对账单号（镜像客户侧 EXPENSE_WRITEOFF），供锚点付款单反审核时按单冲回 write_off_amount
            if (writeOffPart.signum() != 0) {
                writeReconcileRecordV2(paymentNo, settleDateSql, stmtNo, stmtNo,
                        "EXPENSE_WRITEOFF", "", "SUPPLIER", suppCode, firstSuppName,
                        writeOffPart, remark, "对账单结算抹零");
            }
        }

        // 现金部分逐 AP 落真值：本端更新 fin_ap + V2 核销记录；预付部分交自动 FX 单。
        // 必须先落现金再建 FX：FX 审核按当前未结额校验，现金冲完后剩余未结额恰好等于预付份额
        java.util.List<com.erp.finance.account.SupplierAccountService.ApCashLine> cashLines = new java.util.ArrayList<>();
        for (Map<String, Object> ap : planRows) {
            String apNo = str(ap.get("apNo"));
            Object[] meta = planMeta.get(apNo);
            BigDecimal cashPart = (BigDecimal) meta[3];
            if (cashPart.signum() <= 0) continue;
            String sourceBill = str(ap.get("sourceBill"));
            BigDecimal newPaid = toBd(ap.get("paidAmount")).add(cashPart);
            BigDecimal newUnpaid = toBd(ap.get("apAmount")).subtract(newPaid);
            jdbcTemplate.update("UPDATE fin_ap SET paid_amount=?, unpaid_amount=?, status=? WHERE ap_no=?",
                newPaid, newUnpaid, newUnpaid.signum()<=0?"VERIFIED":"UNVERIFIED", apNo);
            String recordId = writeReconcileRecordV2(paymentNo, settleDateSql, apNo, sourceBill,
                "SUPPLIER_STATEMENT", str(ap.get("dueDate")), "SUPPLIER", suppCode, firstSuppName,
                cashPart, remark, "");
            cashLines.add(new com.erp.finance.account.SupplierAccountService.ApCashLine(
                    recordId, apNo, sourceBill, cashPart, paymentNo));
        }
        // 现金部分在线写供应商账户 AP 结算流水
        if (!cashLines.isEmpty()) {
            supplierAccountService.postApCashSettle(settleDateSql.toLocalDate(), suppCode, firstSuppName, cashLines);
        }

        // 预付部分：按对账单各生一张自动 FX 单（source_bill_no=对账单号），FX 审核自行回写 fin_ap
        java.util.List<String> autoWriteoffNos = new java.util.ArrayList<>();
        java.util.Map<String, java.util.List<Map<String, Object>>> prepayByStmt = new java.util.LinkedHashMap<>();
        for (Map<String, Object> ap : planRows) {
            String apNo = str(ap.get("apNo"));
            Object[] meta = planMeta.get(apNo);
            BigDecimal prepayPart = (BigDecimal) meta[2];
            if (prepayPart.signum() <= 0) continue;
            prepayByStmt.computeIfAbsent((String) meta[0], k -> new java.util.ArrayList<>());
            Map<String, Object> line = new java.util.LinkedHashMap<>();
            line.put("apNo", apNo);
            line.put("amount", prepayPart);
            prepayByStmt.get((String) meta[0]).add(line);
        }
        for (java.util.Map.Entry<String, java.util.List<Map<String, Object>>> e : prepayByStmt.entrySet()) {
            String fxNo = prepayWriteoffService.createAutoFromSettle(suppCode, firstSuppName,
                    settleDateSql.toLocalDate(), handler,
                    com.erp.finance.account.PrepayWriteoffService.SOURCE_STATEMENT_SETTLE,
                    e.getKey(), remark, e.getValue());
            if (!fxNo.isEmpty()) autoWriteoffNos.add(fxNo);
        }

        // 写资金流水（OUT）+ 更新资金账户余额（仅现金部分；归属日 = 结算日期，v1.3）
        if (cashNet.signum() > 0) {
            java.util.Set<String> touchedFundAccounts = new java.util.LinkedHashSet<>();
            if (acctsRaw instanceof List<?> al) {
                for (Object item : al) {
                    if (!(item instanceof Map<?,?> am)) continue;
                    String acct = str(am.get("fundAccount"));
                    BigDecimal amt = toBd(am.get("amount"));
                    if (amt.signum() <= 0) continue;
                    BigDecimal bal = getFundBalance(acct).subtract(amt);
                    insertFundLedgerV2(acct, "OUT", amt, paymentNo, bal, op, settleDateSql.toLocalDate());
                    updateFundBalance(acct, bal);
                    touchedFundAccounts.add(acct);
                }
            }
            touchedFundAccounts.forEach(this::rebuildFundChain);

            // 写往来流水（仅现金部分，OUT：付款给供应商 → 往来余额减少；0 额锚点单不写）
            BigDecimal cpBal = getCounterpartyBalance("SUPPLIER", firstSupp).subtract(cashNet);
            writeCounterpartyLedger("SUPPLIER", firstSupp, firstSuppName, "OUT", cashNet,
                paymentNo, "SUPPLIER_STATEMENT_SETTLE", cpBal, remark);
        }

        java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
        resp.put("paymentNo", paymentNo);
        resp.put("amount", cashNet);
        resp.put("prepayAmount", usePrepay);
        resp.put("writeoffNos", autoWriteoffNos);
        fieldMasker.mask(resp, java.util.Map.of("amount", "VIEW_SETTLE_DETAIL"));
        return ApiResponse.ok(resp);
    }

    private void insertSSDetails(String stmtId, Map<String, Object> body) {
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return;
        int idx = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?,?> m)) continue;
            jdbcTemplate.update("INSERT INTO fin_supplier_statement_detail(detail_id,statement_id,source_bill_no,source_bill_date,source_bill_type,bill_amount,reconcile_amount,paid_amount,unpaid_amount,bill_remark,sort_order) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    "SSD"+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(), stmtId, str(m.get("sourceBillNo")), null, str(m.get("sourceBillType")), toBd(m.get("billAmount")), toBd(m.get("reconcileAmount")), BigDecimal.ZERO, toBd(m.get("unpaidAmount")), str(m.get("billRemark")), idx++);
        }
    }

    private BigDecimal sumDetails(Map<String, Object> body) {
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                BigDecimal a = toBd(m.get("amount"));
                if (a.signum() > 0) sum = sum.add(a);
            }
        }
        return sum;
    }

    private BigDecimal sumDetailField(Map<String, Object> body, String field) {
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) sum = sum.add(toBd(m.get(field)));
        }
        return sum;
    }

    /** 全量替换明细行 */
    private void insertDetails(String receiptId, Map<String, Object> body) {
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return;
        int idx = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            jdbcTemplate.update("""
                    INSERT INTO fin_receipt_detail(detail_id, receipt_id, fund_account, amount, remark, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, "SKD" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(),
                    receiptId, str(m.get("fundAccount")), toBd(m.get("amount")), str(m.get("remark")), idx++);
        }
    }

    /** 付款明细行写入（参照收款明细） */
    private void insertPaymentDetails(String paymentId, Map<String, Object> body) {
        Object raw = body.get("details");
        if (!(raw instanceof List<?> list)) return;
        int idx = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            jdbcTemplate.update("""
                    INSERT INTO fin_payment_detail(detail_id, payment_id, fund_account, amount, remark, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, "FKD" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(),
                    paymentId, str(m.get("fundAccount")), toBd(m.get("amount")), str(m.get("remark")), idx++);
        }
    }

    /** 匹配该往来单位的未核销 AR/AP，核销并写入流水记录 */
    private BigDecimal reconcileAndRecord(String receiptNo, java.sql.Date receiptDate,
            String cpType, String cpCode, String cpName, BigDecimal amount,
            String receiptRemark, String lineRemark) {
        if ("SUPPLIER".equals(cpType)) {
            // 查未核销 AP（按 due_date 升序 → 早到期的先核）
            List<Map<String, Object>> rows = queryCamel(
                    "SELECT * FROM fin_ap WHERE supplier = ? AND status <> 'VERIFIED' ORDER BY due_date", cpCode);
            // 与 AR 同侧的弱引用回落：fin_ap.supplier 采购侧写入的是供应商名称，
            // 付款单 counterparty_code 存编码，编码查不到时按名称再查一次，否则付款永远核不到应付。
            if (rows.isEmpty() && !cpName.isBlank() && !cpName.equals(cpCode)) {
                rows = queryCamel(
                        "SELECT * FROM fin_ap WHERE supplier = ? AND status <> 'VERIFIED' ORDER BY due_date", cpName);
            }
            return doReconcileAp(rows, receiptNo, receiptDate, cpType, cpCode, cpName, amount, receiptRemark);
        } else {
            // CUSTOMER / COUNTERPARTY → 查 AR
            List<Map<String, Object>> rows = queryCamel(
                    "SELECT * FROM fin_ar WHERE customer = ? AND status <> 'VERIFIED' ORDER BY due_date", cpCode);
            // fin_ar.customer 是历史遗留的「弱引用」列：销售出库/退货写入时存的是客户名称，
            // 而收款单 counterparty_code 存的是客户编码（K0001），两者对不上时按编码一行都查不到，
            // 收进来的钱就会全部落到未匹配余额里。后台手工制单的老单据恰好把名称填在 code 位上，
            // 掩盖了这个问题；司机交账收款单老实填了编码，才把它暴露出来。
            // 因此编码查不到时统一回落到名称匹配（原先只有 COUNTERPARTY 才回落）。
            if (rows.isEmpty() && !cpName.isBlank() && !cpName.equals(cpCode)) {
                rows = queryCamel(
                        "SELECT * FROM fin_ar WHERE customer = ? AND status <> 'VERIFIED' ORDER BY due_date", cpName);
            }
            return doReconcileAr(rows, receiptNo, receiptDate, cpType, cpCode, cpName, amount, receiptRemark);
        }
    }

    private BigDecimal doReconcileAr(List<Map<String, Object>> rows, String receiptNo, java.sql.Date receiptDate,
            String cpType, String cpCode, String cpName, BigDecimal amount, String receiptRemark) {
        BigDecimal remain = amount;
        for (Map<String, Object> ar : rows) {
            if (remain.signum() <= 0) break;
            BigDecimal unreceived = toBd(ar.get("unreceivedAmount"));
            if (unreceived.signum() <= 0) continue;
            BigDecimal actual = remain.min(unreceived);
            String arNo = str(ar.get("arNo"));
            BigDecimal newReceived = toBd(ar.get("receivedAmount")).add(actual);
            BigDecimal newUnreceived = toBd(ar.get("arAmount")).subtract(newReceived);
            String newStatus = newUnreceived.compareTo(BigDecimal.ZERO) <= 0 ? "VERIFIED" : "UNVERIFIED";
            jdbcTemplate.update(
                    "UPDATE fin_ar SET received_amount = ?, unreceived_amount = ?, status = ? WHERE ar_no = ?",
                    newReceived, newUnreceived, newStatus, arNo);
            // 写核销记录。走 V2 补齐 ar_no / source_bill：
            // 旧版只写 business_no，核销明细查不到对应的应收单和来源发货单，
            // 财务对账时无法从一笔核销反查是哪张发货单销的账。
            writeReconcileRecordV2(receiptNo, receiptDate, arNo, str(ar.get("sourceBill")),
                    "SALES_RECEIPT", str(ar.get("dueDate")), cpType, cpCode, cpName,
                    actual, receiptRemark, "");
            remain = remain.subtract(actual);
        }
        return remain;
    }

    private BigDecimal doReconcileAp(List<Map<String, Object>> rows, String receiptNo, java.sql.Date receiptDate,
            String cpType, String cpCode, String cpName, BigDecimal amount, String receiptRemark) {
        BigDecimal remain = amount;
        for (Map<String, Object> ap : rows) {
            if (remain.signum() <= 0) break;
            BigDecimal unpaid = toBd(ap.get("unpaidAmount"));
            if (unpaid.signum() <= 0) continue;
            BigDecimal actual = remain.min(unpaid);
            String apNo = str(ap.get("apNo"));
            BigDecimal newPaid = toBd(ap.get("paidAmount")).add(actual);
            BigDecimal newUnpaid = toBd(ap.get("apAmount")).subtract(newPaid);
            String newStatus = newUnpaid.compareTo(BigDecimal.ZERO) <= 0 ? "VERIFIED" : "UNVERIFIED";
            jdbcTemplate.update(
                    "UPDATE fin_ap SET paid_amount = ?, unpaid_amount = ?, status = ? WHERE ap_no = ?",
                    newPaid, newUnpaid, newStatus, apNo);
            writeReconcileRecord(receiptNo, receiptDate, apNo, "PURCHASE_RECEIPT",
                    str(ap.get("dueDate")), cpType, cpCode, cpName, actual, receiptRemark, "");
            remain = remain.subtract(actual);
        }
        return remain;
    }

    private String writeReconcileRecord(String receiptNo, java.sql.Date receiptDate, String bizNo,
            String bizType, String bizDate, String cpType, String cpCode, String cpName,
            BigDecimal amount, String receiptRemark, String bizRemark) {
        String recordId = "RR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO fin_reconcile_record(record_id, receipt_no, receipt_date,
                    business_no, business_type, business_date,
                    counterparty_type, counterparty_code, counterparty_name,
                    reconcile_amount, receipt_remark, business_remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, recordId,
                receiptNo, receiptDate, bizNo, bizType,
                bizDate.isEmpty() ? null : java.sql.Date.valueOf(bizDate.substring(0, 10)),
                cpType, cpCode, cpName, amount, receiptRemark, bizRemark);
        return recordId;
    }

    private String writeReconcileRecordV2(String receiptNo, java.sql.Date receiptDate, String arNo,
            String sourceBill, String bizType, String bizDate, String cpType, String cpCode, String cpName,
            BigDecimal amount, String receiptRemark, String bizRemark) {
        String recordId = "RR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO fin_reconcile_record(record_id, receipt_no, receipt_date,
                    business_no, business_type, business_date,
                    counterparty_type, counterparty_code, counterparty_name,
                    reconcile_amount, receipt_remark, business_remark, ar_no, source_bill)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, recordId,
                receiptNo, receiptDate, arNo, bizType,
                bizDate.isEmpty() ? null : java.sql.Date.valueOf(bizDate.substring(0, 10)),
                cpType, cpCode, cpName, amount, receiptRemark, bizRemark, arNo, sourceBill);
        return recordId;
    }

    /** 按客户编码取客户名称（收款单反审核冲在线流水时用），取不到回落传入名。 */
    private String customerNameByCode(String code, String fallback) {
        if (code == null || code.isEmpty()) return fallback;
        List<String> names = jdbcTemplate.queryForList(
                "SELECT customer_name FROM base_customer WHERE customer_code = ? LIMIT 1",
                String.class, code);
        return names.isEmpty() || names.get(0) == null ? fallback : names.get(0);
    }

    /** 来源单号 → 所属对账单号（限定在本收款单关联的对账单范围内查明细）。 */
    private String stmtNoBySourceBill(String sourceBill, String relatedStmtNos) {
        if (sourceBill == null || sourceBill.isEmpty()
                || relatedStmtNos == null || relatedStmtNos.isEmpty()) {
            return "";
        }
        for (String raw : relatedStmtNos.split(",")) {
            String no = raw.trim();
            if (no.isEmpty()) continue;
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fin_customer_statement_detail sd "
                            + "JOIN fin_customer_statement s ON s.statement_id = sd.statement_id "
                            + "WHERE s.statement_no = ? AND sd.source_bill_no = ?",
                    Integer.class, no, sourceBill);
            if (cnt != null && cnt > 0) return no;
        }
        return "";
    }

    /** 查指定资金账户的最新余额 */
    private BigDecimal getFundBalance(String fundAccount) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT balance_after FROM fin_fund_ledger WHERE fund_account = ? ORDER BY occurred_at DESC LIMIT 1",
                fundAccount);
        if (rows.isEmpty()) {
            // 从 base_fund_account 取期初余额兜底
            List<Map<String, Object>> acc = jdbcTemplate.queryForList(
                    "SELECT balance FROM base_fund_account WHERE fund_account_name = ? OR fund_account_code = ? LIMIT 1",
                    fundAccount, fundAccount);
            return acc.isEmpty() ? BigDecimal.ZERO : toBd(acc.get(0).get("BALANCE"));
        }
        return toBd(rows.get(0).get("BALANCE_AFTER"));
    }

    private void updateFundBalance(String fundAccount, BigDecimal newBalance) {
        jdbcTemplate.update(
                "UPDATE base_fund_account SET balance = ? WHERE fund_account_name = ? OR fund_account_code = ?",
                newBalance, fundAccount, fundAccount);
    }

    private void insertFundLedgerV2(String fundAccount, String direction, BigDecimal amount,
            String sourceBill, BigDecimal balanceAfter, String operator) {
        insertFundLedgerV2(fundAccount, direction, amount, sourceBill, balanceAfter, operator, null);
    }

    /**
     * 写资金流水。occurredDate 非空时流水归属到该日期（收/付款单记账日期，v1.3 日结口径），
     * 时刻仍取当前时间；为空沿用 CURRENT_TIMESTAMP（交账/对账等动作即生效的联动场景）。
     *
     * <p>归属日可能早于账户内已有流水的日期（封单开放窗口内补录往日记账），
     * 插入后由 {@link #rebuildFundChain} 按发生时间顺序重排余额链，
     * 保证 balance_after 始终是「该时点的钱包余额」，日结资金滚存勾稽才有意义。
     */
    private void insertFundLedgerV2(String fundAccount, String direction, BigDecimal amount,
            String sourceBill, BigDecimal balanceAfter, String operator, LocalDate occurredDate) {
        java.sql.Timestamp occurred = occurredDate == null
                ? java.sql.Timestamp.valueOf(LocalDateTime.now())
                : java.sql.Timestamp.valueOf(occurredDate.atTime(LocalTime.now()));
        jdbcTemplate.update("""
                INSERT INTO fin_fund_ledger(ledger_id, ledger_no, fund_account, direction, amount, source_bill, balance_after, occurred_at, operator_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "FL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                "FUND" + System.currentTimeMillis(), fundAccount, direction, amount, sourceBill, balanceAfter,
                occurred, operator);
    }

    /**
     * 按发生时间顺序重建某资金账户全量流水的 balance_after 链（v1.3 日结资金口径）。
     *
     * <p>为什么需要：补录记账日期早于今天的收/付款单时，新流水物理上后插、业务日期却靠前，
     * 若只在链尾加减，历史每行余额都会错位。这里以「账户档案当前余额 − 全部流水净额」倒推期初，
     * 再按 occurred_at, ledger_no 顺序滚算并回写 balance_after。
     * 流水只增不删（取消审核是追加反向行），重算不会破坏任何业务事实。
     */
    private void rebuildFundChain(String fundAccount) {
        if (fundAccount == null || fundAccount.isBlank()) return;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ledger_id, direction, amount FROM fin_fund_ledger WHERE fund_account = ? "
                        + "ORDER BY occurred_at ASC, ledger_no ASC", fundAccount);
        if (rows.isEmpty()) return;
        BigDecimal net = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            BigDecimal amt = toBd(row.get("AMOUNT"));
            net = "IN".equals(String.valueOf(row.get("DIRECTION"))) ? net.add(amt) : net.subtract(amt);
        }
        BigDecimal running = getFundBalanceArchive(fundAccount).subtract(net);
        for (Map<String, Object> row : rows) {
            BigDecimal amt = toBd(row.get("AMOUNT"));
            running = "IN".equals(String.valueOf(row.get("DIRECTION"))) ? running.add(amt) : running.subtract(amt);
            jdbcTemplate.update("UPDATE fin_fund_ledger SET balance_after = ? WHERE ledger_id = ?",
                    running, String.valueOf(row.get("LEDGER_ID")));
        }
        updateFundBalance(fundAccount, running);
    }

    /** 只查账户档案余额（不回落流水末笔），供余额链倒推期初使用。 */
    private BigDecimal getFundBalanceArchive(String fundAccount) {
        List<Map<String, Object>> acc = jdbcTemplate.queryForList(
                "SELECT balance FROM base_fund_account WHERE fund_account_name = ? OR fund_account_code = ? LIMIT 1",
                fundAccount, fundAccount);
        return acc.isEmpty() ? BigDecimal.ZERO : toBd(acc.get(0).get("BALANCE"));
    }

    /**
     * 写一组账面冲抵流水（一进一出，净额为 0），供 TMS 销退合并结算调用。
     *
     * 为什么要写这两条：合并结算时客户只付「发货 - 退货」的净额，
     * 被退货对冲掉的那部分金额没有真实资金收付，但账上必须体现
     * 「收到了这笔货款、又原路退了出去」，否则销售收入与退货支出两侧都缺一笔，
     * 月末查「某笔退货的钱去哪了」时无凭无据。
     *
     * 落哪个账户由 TMS_OFFSET_FUND_ACCOUNT 全局参数指定（销退冲抵过渡户），
     * 绝不能落司机收款账户 —— 那会污染司机手上现金余额，交账对账时对不平。
     *
     * ledger_no 用独立的 OFS 号段而非 insertFundLedgerV2 的 "FUND"+毫秒：
     * ledger_no 有 UNIQUE 约束，一正一负两条在同一毫秒内连续写入必然撞键。
     *
     * 余额处理：进账后余额 +amount，出账后回到原值，所以最终 base_fund_account
     * 只需按出账后的余额更新一次，过渡户长期保持净额 0。
     *
     * @param fundAccount 冲抵账户名称（与 fin_receipt_detail.fund_account 同口径）
     * @param amount      冲抵金额，须为正数；非正数直接跳过不写
     */
    @Transactional
    public void writeOffsetLedger(String fundAccount, BigDecimal amount, String sourceBill, String operator) {
        if (fundAccount == null || fundAccount.isBlank()) return;
        if (amount == null || amount.signum() <= 0) return;
        BigDecimal opening = getFundBalance(fundAccount);
        BigDecimal afterIn = opening.add(amount);
        insertOffsetLedger(fundAccount, "IN", amount, sourceBill, afterIn, operator);
        BigDecimal afterOut = afterIn.subtract(amount);
        insertOffsetLedger(fundAccount, "OUT", amount, sourceBill, afterOut, operator);
        updateFundBalance(fundAccount, afterOut);
    }

    private void insertOffsetLedger(String fundAccount, String direction, BigDecimal amount,
            String sourceBill, BigDecimal balanceAfter, String operator) {
        String uniq = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO fin_fund_ledger(ledger_id, ledger_no, fund_account, direction, amount, source_bill, balance_after, occurred_at, operator_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                """, "FL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                "OFS" + System.currentTimeMillis() + uniq, fundAccount, direction, amount,
                sourceBill, balanceAfter, operator);
    }

    /** 查该往来单位的当前往来余额 */
    private BigDecimal getCounterpartyBalance(String cpType, String cpCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT balance_after FROM fin_counterparty_ledger WHERE counterparty_type = ? AND counterparty_code = ? ORDER BY occurred_at DESC LIMIT 1",
                cpType, cpCode);
        return rows.isEmpty() ? BigDecimal.ZERO : toBd(rows.get(0).get("BALANCE_AFTER"));
    }

    private void writeCounterpartyLedger(String cpType, String cpCode, String cpName,
            String direction, BigDecimal amount, String sourceBillNo, String businessType,
            BigDecimal balanceAfter, String remark) {
        jdbcTemplate.update("""
                INSERT INTO fin_counterparty_ledger(ledger_id, counterparty_type, counterparty_code, counterparty_name,
                    direction, amount, source_bill_no, business_type, balance_after, occurred_at, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                """, "CL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                cpType, cpCode, cpName, direction, amount, sourceBillNo, businessType, balanceAfter, remark);
    }

    // ============================================================
    // 付款单（参照收款单对称实现，阶段一仅 page，后续补 CRUD+审核）
    // ============================================================

    @RequirePerm(value = "fin.payment.view", name = "查看")
    @PostMapping("/payment/page")
    public ApiResponse<PageResult<Map<String, Object>>> paymentPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT p.payment_id, p.payment_no, p.payment_date, p.status,
                       p.counterparty_type, p.counterparty_code, p.counterparty_name,
                       p.total_amount, p.verified_amount, p.handler,
                       p.business_source, p.related_bill_no, p.summary,
                       p.creator_name, p.create_time, p.auditor_name, p.audit_time,
                       p.payment_type
                FROM fin_payment_bill p
                WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
        // PRD-28：往来单位多态列按客户/供应商维度收窄
        appendCpScope(sql, args, "p.counterparty_type", "p.counterparty_name", "p.creator_name");
        String cpType = trimF(filters, "counterpartyType");
        if (!cpType.isEmpty()) { sql.append(" AND p.counterparty_type = ?"); args.add(cpType); }
        String cpName = trimF(filters, "counterparty");
        if (!cpName.isEmpty()) { sql.append(" AND (p.counterparty_code LIKE ? OR p.counterparty_name LIKE ?)"); args.add("%"+cpName+"%"); args.add("%"+cpName+"%"); }
        String dateFrom = trimF(filters, "dateFrom");
        if (!dateFrom.isEmpty()) { sql.append(" AND p.payment_date >= ?"); args.add(dateFrom); }
        String dateTo = trimF(filters, "dateTo");
        if (!dateTo.isEmpty()) { sql.append(" AND p.payment_date <= ?"); args.add(dateTo); }
        String bizSrc = trimF(filters, "businessSource");
        if (!bizSrc.isEmpty()) { sql.append(" AND p.business_source = ?"); args.add(bizSrc); }
        // PRD-36：按付款类型（应付结算/预付付款/预付退款）筛选
        String paymentType = trimF(filters, "paymentType");
        if (!paymentType.isEmpty()) { sql.append(" AND p.payment_type = ?"); args.add(paymentType); }
        String reconcileStatus = trimF(filters, "reconcileStatus");
        if (!reconcileStatus.isEmpty()) {
            switch (reconcileStatus) {
                case "未核销": sql.append(" AND (p.verified_amount = 0 OR p.verified_amount IS NULL)"); break;
                case "部分核销": sql.append(" AND p.verified_amount > 0 AND p.verified_amount < p.total_amount"); break;
                case "已核销": sql.append(" AND p.verified_amount >= p.total_amount AND p.total_amount > 0"); break;
            }
        }
        sql.append(" ORDER BY p.payment_no DESC");

        List<Map<String, Object>> rows = queryCamel(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            String src = str(r.get("businessSource"));
            r.put("businessSourceText", "BACKOFFICE".equals(src) ? "后台制单"
                    : "AR_SETTLEMENT".equals(src) ? "结算生成"
                    : "RECONCILE".equals(src) ? "对账生成" : src);
            String st = str(r.get("status"));
            r.put("statusText", "APPROVED".equals(st) ? "已审核"
                    : "PENDING".equals(st) ? "待审核"
                    : "CANCELLED".equals(st) ? "已作废" : st);
            String ct = str(r.get("counterpartyType"));
            r.put("counterpartyTypeText", "CUSTOMER".equals(ct) ? "客户"
                    : "SUPPLIER".equals(ct) ? "供应商"
                    : "COUNTERPARTY".equals(ct) ? "往来单位" : ct);
            r.put("paymentTypeText", paymentTypeText(str(r.get("paymentType"))));
            BigDecimal total = toBd(r.get("totalAmount"));
            BigDecimal verified = toBd(r.get("verifiedAmount"));
            if (total.signum() <= 0) r.put("reconcileStatusText", "—");
            else if (verified.signum() <= 0) r.put("reconcileStatusText", "未核销");
            else if (verified.compareTo(total) < 0) r.put("reconcileStatusText", "部分核销");
            else r.put("reconcileStatusText", "已核销");
        }
        fieldMasker.mask(rows, FUND_AMOUNT_KEYS);
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    private BigDecimal getLatestFundBalance() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT balance_after FROM fin_fund_ledger ORDER BY occurred_at DESC LIMIT 1");
        if (rows.isEmpty()) {
            return new BigDecimal("50000.00");
        }
        return toBigDecimal(rows.get(0).get("BALANCE_AFTER"));
    }

    private void insertFundLedger(String direction, BigDecimal amount, String sourceBill, BigDecimal balanceAfter) {
        String id = "FL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = "FUND" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        jdbcTemplate.update("""
                INSERT INTO fin_fund_ledger(ledger_id, ledger_no, fund_account, direction, amount, source_bill, balance_after, occurred_at, operator_name)
                VALUES (?, ?, '工行基本户', ?, ?, ?, ?, CURRENT_TIMESTAMP, '管理员')
                """, id, no, direction, amount, sourceBill, balanceAfter);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 查询并转换 key 为驼峰。
     * H2 CASE_INSENSITIVE_IDENTIFIERS=TRUE 会把列别名/列名变成大写，
     * 但前端 module-api.js 的 valueForTitle() 按驼峰匹配，不转的话所有列都显示空。
     */
    private List<Map<String, Object>> queryCamel(String sql, Object... args) {
        List<Map<String, Object>> raw = jdbcTemplate.queryForList(sql, args);
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Map<String, Object> row : raw) out.add(camelKeys(row));
        return out;
    }

    /** UPPER_SNAKE / UPPERCASE key → camelCase（与 CustomerPriceController.toCamel 同逻辑） */
    static Map<String, Object> camelKeys(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isEmpty()) { out.put(key, e.getValue()); continue; }
            // 全大写/全下划线 → 转小写后首字母小写，下划线后首字母大写
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean up = false;
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (c == '_') { up = true; continue; }
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
            out.put(sb.toString(), e.getValue());
        }
        return out;
    }

    /** PRD-31 财务操作日志：统一走 OperationLogService（真实操作人/IP/耗时/中文名/单据时间线）。 */
    private void finLog(String moduleCode, String action, String bizType, String bizNo, String detail) {
        opLog.log(moduleCode, action, bizType, null, bizNo, detail);
    }

    public record FundBillRequest(@NotBlank String objectId, @NotBlank String fundAccountId, @NotNull @Positive BigDecimal amount, String remark) {}
}
