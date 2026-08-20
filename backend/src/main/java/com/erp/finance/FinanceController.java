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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public FinanceController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
    }

    @PostMapping("/ar/page")
    public ApiResponse<PageResult<Map<String, Object>>> arPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT a.ar_no, a.customer, COALESCE(c.salesman, a.salesman) AS salesman,
                       a.source_bill, a.ar_amount, a.received_amount, a.unreceived_amount,
                       a.due_date, a.overdue_days, a.invoice_status, a.reconcile_status, a.created_at,
                       CASE a.status WHEN 'VERIFIED' THEN '已核销' ELSE '未核销' END status
                FROM fin_ar a
                LEFT JOIN base_customer c ON c.customer_name = a.customer
                WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
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
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 收款结算：生成收款单并审核，更新 AR 已收金额，抹零生成费用单 */
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
        // 收款金额 = 账户实收合计
        BigDecimal acctTotal = BigDecimal.ZERO;
        if (acctsRaw instanceof List<?> al) for (Object o : al) if (o instanceof Map<?,?> am) acctTotal = acctTotal.add(toBd(am.get("amount")));

        Map<String, java.util.List<Map<String, Object>>> byCustomer = new java.util.LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            List<Map<String, Object>> ars = queryCamel("SELECT * FROM fin_ar WHERE ar_no = ?", str(m.get("arNo")));
            if (ars.isEmpty()) continue;
            Map<String, Object> ar = ars.get(0);
            byCustomer.computeIfAbsent(str(ar.get("customer")), k -> new java.util.ArrayList<>()).add(ar);
        }
        java.sql.Date settleDate = java.sql.Date.valueOf(receiptDate.isEmpty() ? LocalDate.now().toString() : receiptDate);
        LocalDateTime now = LocalDateTime.now(); String op = currentUser();
        int created = 0;

        for (Map.Entry<String, java.util.List<Map<String, Object>>> entry : byCustomer.entrySet()) {
            String custName = entry.getKey();
            // 生成收款单（金额=账户实收合计）
            String receiptNo = billNoGen.nextNo("SK", "fin_receipt_bill", "receipt_no");
            String receiptId = "SK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String firstAcct = "默认账户";
            if (acctsRaw instanceof List<?> al2 && !al2.isEmpty() && al2.get(0) instanceof Map<?,?> am2) firstAcct = str(am2.get("fundAccount"));
            jdbcTemplate.update("""
                    INSERT INTO fin_receipt_bill(receipt_id,receipt_no,receipt_date,status,counterparty_type,counterparty_code,counterparty_name,object_name,total_amount,verified_amount,fund_account,amount,business_source,handler,related_bill_no,summary,creator_name,create_time,auditor_name,audit_time)
                    VALUES(?,?,?,'APPROVED','CUSTOMER',?,?,?,?,?,?,?,'AR_SETTLE',?,'',?,?,?,?,?)""",
                    receiptId,receiptNo,settleDate,custName,custName,custName,acctTotal,acctTotal,firstAcct,acctTotal,handler,summary,op,java.sql.Timestamp.valueOf(now),op,java.sql.Timestamp.valueOf(now));
            // 核销 AR + 抹零费用单
            for (Map<String, Object> ar : entry.getValue()) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) continue;
                    if (custName.equals(str(ar.get("customer"))) && str(m.get("arNo")).equals(str(ar.get("arNo")))) {
                        BigDecimal settleAmt = toBd(m.get("settleAmount"));
                        BigDecimal newReceived = toBd(ar.get("receivedAmount")).add(settleAmt);
                        BigDecimal newUnreceived = toBd(ar.get("arAmount")).subtract(newReceived);
                        jdbcTemplate.update("UPDATE fin_ar SET received_amount=?,unreceived_amount=?,status=? WHERE ar_no=?",
                                newReceived,newUnreceived,newUnreceived.signum()<=0?"VERIFIED":"UNVERIFIED",str(ar.get("arNo")));
                        writeReconcileRecordV2(receiptNo,settleDate,str(ar.get("arNo")),str(ar.get("sourceBill")),"AR_SETTLE",str(ar.get("dueDate")),"CUSTOMER",custName,custName,settleAmt,summary,"");
                        break;
                    }
                }
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
        return ApiResponse.ok(Map.of("created",created,"receiptAmount",acctTotal));
    }

    @PostMapping("/ap/page")
    public ApiResponse<PageResult<Map<String, Object>>> apPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(queryCamel("""
                SELECT ap_no, supplier, source_bill,
                       ap_amount, paid_amount, unpaid_amount, due_date,
                       CASE status WHEN 'VERIFIED' THEN '已核销' ELSE '未核销' END status
                FROM fin_ap
                ORDER BY ap_no DESC
                """), request));
    }

    @PostMapping("/receipt-payment/page")
    public ApiResponse<PageResult<Map<String, Object>>> receiptPaymentPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(queryCamel("""
                SELECT receipt_no bill_no,
                       '收款单' bill_type,
                       object_name,
                       fund_account,
                       amount,
                       verified_amount,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM fin_receipt_bill
                UNION ALL
                SELECT payment_no bill_no,
                       '付款单' bill_type,
                       object_name,
                       fund_account,
                       amount,
                       verified_amount,
                       CASE status WHEN 'APPROVED' THEN '已审核' ELSE '待审核' END status
                FROM fin_payment_bill
                ORDER BY bill_no DESC
                """), request));
    }

    /** 收款单创建（V32 重构：完整字段 + 明细行） */
    @PostMapping("/receipt/create")
    public ApiResponse<Map<String, Object>> createReceipt(@RequestBody Map<String, Object> body) {
        String receiptId = "SK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String receiptNo = billNoGen.nextNo("SK", "fin_receipt_bill", "receipt_no");
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUser();
        BigDecimal total = sumDetails(body);
        String cpName = str(body.get("counterpartyName"));
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
                    object_name, fund_account, amount,
                    creator_name, create_time)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, 'BACKOFFICE', ?, 0, ?, ?, ?, ?, ?)
                """, receiptId, receiptNo, date(body, "receiptDate"),
                str(body.get("counterpartyType")), str(body.get("counterpartyCode")),
                cpName, str(body.get("handler")),
                str(body.get("relatedBillNo")), str(body.get("summary")),
                total, cpName, firstFundAcct, total,
                operator, java.sql.Timestamp.valueOf(now));
        insertDetails(receiptId, body);
        return ApiResponse.ok(GenericResult.row("receiptId", receiptId, "receiptNo", receiptNo));
    }

    /** 付款单创建（V32 重构：完整字段 + 明细行） */
    @PostMapping("/payment/create")
    public ApiResponse<Map<String, Object>> createPayment(@RequestBody Map<String, Object> body) {
        String paymentId = "FK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String paymentNo = billNoGen.nextNo("FK", "fin_payment_bill", "payment_no");
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUser();
        BigDecimal total = sumDetails(body);
        String cpName = str(body.get("counterpartyName"));
        String firstFundAcct = "";
        Object raw = body.get("details");
        if (raw instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m)
            firstFundAcct = str(m.get("fundAccount"));
        jdbcTemplate.update("""
                INSERT INTO fin_payment_bill(payment_id, payment_no, payment_date, status,
                    counterparty_type, counterparty_code, counterparty_name,
                    handler, related_bill_no, summary, business_source,
                    total_amount, verified_amount,
                    object_name, fund_account, amount,
                    creator_name, create_time)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, 'BACKOFFICE', ?, 0, ?, ?, ?, ?, ?)
                """, paymentId, paymentNo, date(body, "paymentDate"),
                str(body.get("counterpartyType")), str(body.get("counterpartyCode")),
                cpName, str(body.get("handler")),
                str(body.get("relatedBillNo")), str(body.get("summary")),
                total, cpName, firstFundAcct, total,
                operator, java.sql.Timestamp.valueOf(now));
        insertPaymentDetails(paymentId, body);
        return ApiResponse.ok(GenericResult.row("paymentId", paymentId, "paymentNo", paymentNo));
    }

    @PostMapping("/fund-ledger/page")
    public ApiResponse<PageResult<Map<String, Object>>> fundLedgerPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(queryCamel("""
                SELECT ledger_no, fund_account, direction, amount,
                       source_bill, balance_after, occurred_at, operator_name
                FROM fin_fund_ledger
                ORDER BY occurred_at DESC
                """), request));
    }

    @PostMapping("/ar-settlement/page")
    public ApiResponse<PageResult<Map<String, Object>>> arSettlementPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT CONCAT('ARS', DATE_FORMAT(NOW(), '%Y%m%d'), '0001') settlementNo,
                       customer,
                       SUM(unreceived_amount) settlementAmount,
                       0.00 discountAmount,
                       '待审核' status
                FROM fin_ar
                WHERE status <> 'VERIFIED'
                GROUP BY customer
                """), request));
    }

    @PostMapping("/ap-settlement/page")
    public ApiResponse<PageResult<Map<String, Object>>> apSettlementPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(jdbcTemplate.queryForList("""
                SELECT CONCAT('APS', DATE_FORMAT(NOW(), '%Y%m%d'), '0001') settlementNo,
                       supplier,
                       SUM(unpaid_amount) settlementAmount,
                       0.00 discountAmount,
                       '待审核' status
                FROM fin_ap
                WHERE status <> 'VERIFIED'
                GROUP BY supplier
                """), request));
    }

    // ============================================================
    // 费用单 V38 重构
    // ============================================================

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
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @PostMapping("/expense/detail")
    public ApiResponse<Map<String, Object>> expenseDetail(@RequestBody Map<String, Object> body) {
        String id = str(body.get("expenseId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_expense_bill WHERE expense_id = ? OR expense_no = ?", id, id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "费用单不存在");
        Map<String, Object> head = heads.get(0);
        head.put("details", queryCamel("SELECT * FROM fin_expense_detail WHERE expense_id = ? ORDER BY sort_order", head.get("expenseId")));
        return ApiResponse.ok(head);
    }

    @PostMapping("/expense/create")
    public ApiResponse<Map<String, Object>> createExpense(@RequestBody Map<String, Object> body) {
        String expenseId = "FE" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String expenseNo = billNoGen.nextNo("FE", "fin_expense_bill", "expense_no");
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUser();
        BigDecimal total = sumDetails(body);
        BigDecimal totalTax = sumDetailField(body, "taxAmount");
        BigDecimal totalExcluding = sumDetailField(body, "excludingTaxAmount");
        String direction = total.signum() >= 0 ? "IN" : "OUT";
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
        return ApiResponse.ok(GenericResult.row("expenseId", expenseId, "expenseNo", expenseNo));
    }

    @PostMapping("/expense/update")
    public ApiResponse<Boolean> updateExpense(@RequestBody Map<String, Object> body) {
        String id = str(body.get("expenseId"));
        List<Map<String, Object>> exist = queryCamel("SELECT status FROM fin_expense_bill WHERE expense_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "费用单不存在");
        if (!"PENDING".equals(str(exist.get(0).get("status")))) return ApiResponse.fail("400", "仅待审核单据可编辑");
        BigDecimal total = sumDetails(body);
        BigDecimal totalTax = sumDetailField(body, "taxAmount");
        BigDecimal totalExcluding = sumDetailField(body, "excludingTaxAmount");
        jdbcTemplate.update("""
                UPDATE fin_expense_bill SET expense_date = ?, counterparty_type = ?,
                    counterparty_code = ?, counterparty_name = ?, handler = ?, department = ?,
                    related_bill_no = ?, external_voucher_no = ?, fund_account = ?,
                    remark = ?, total_amount = ?, total_tax_amount = ?, total_excluding_tax_amount = ?
                WHERE expense_id = ?
                """, date(body, "expenseDate"), str(body.get("counterpartyType")),
                str(body.get("counterpartyCode")), str(body.get("counterpartyName")),
                str(body.get("handler")), str(body.get("department")),
                str(body.get("relatedBillNo")), str(body.get("externalVoucherNo")),
                str(body.get("fundAccount")), str(body.get("remark")),
                total, totalTax, totalExcluding, id);
        jdbcTemplate.update("DELETE FROM fin_expense_detail WHERE expense_id = ?", id);
        insertExpenseDetails(id, body);
        return ApiResponse.ok(true);
    }

    @PostMapping("/expense/delete")
    public ApiResponse<Boolean> deleteExpense(@RequestBody Map<String, Object> body) {
        String id = str(body.get("expenseId"));
        List<Map<String, Object>> exist = queryCamel("SELECT status FROM fin_expense_bill WHERE expense_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "费用单不存在");
        if (!"PENDING".equals(str(exist.get(0).get("status")))) return ApiResponse.fail("400", "仅待审核单据可删除");
        jdbcTemplate.update("DELETE FROM fin_expense_detail WHERE expense_id = ?", id);
        jdbcTemplate.update("DELETE FROM fin_expense_bill WHERE expense_id = ?", id);
        return ApiResponse.ok(true);
    }

    @PostMapping("/expense/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> auditExpense(@RequestBody Map<String, Object> body) {
        String id = str(body.get("expenseId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_expense_bill WHERE expense_id = ?", id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "费用单不存在");
        Map<String, Object> r = heads.get(0);
        if (!"PENDING".equals(str(r.get("status")))) return ApiResponse.fail("400", "仅待审核单据可审核");
        LocalDateTime now = LocalDateTime.now();
        String auditor = currentUser();
        String expenseNo = str(r.get("expenseNo"));
        String cpType = str(r.get("counterpartyType"));
        String cpCode = str(r.get("counterpartyCode"));
        String cpName = str(r.get("counterpartyName"));
        String fundAcct = str(r.get("fundAccount"));
        BigDecimal total = toBd(r.get("totalAmount"));
        String direction = total.signum() >= 0 ? "IN" : "OUT";
        jdbcTemplate.update("UPDATE fin_expense_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?, direction = ? WHERE expense_id = ?",
                auditor, java.sql.Timestamp.valueOf(now), direction, id);

        if (!fundAcct.isEmpty()) {
            // 有收/付账户 → 自动生成收款单/付款单并审核核销
            String receiptNo = billNoGen.nextNo("SK", "fin_receipt_bill", "receipt_no");
            String receiptId = "SK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            BigDecimal absAmt = total.abs();
            jdbcTemplate.update("""
                    INSERT INTO fin_receipt_bill(receipt_id, receipt_no, receipt_date, status,
                        counterparty_type, counterparty_code, counterparty_name, object_name,
                        total_amount, verified_amount, fund_account, amount,
                        business_source, handler, related_bill_no, summary,
                        creator_name, create_time, auditor_name, audit_time)
                    VALUES (?, ?, CURRENT_DATE, 'APPROVED', ?, ?, ?, ?, ?, ?, ?, ?, 'EXPENSE', ?, ?, ?, ?, ?, ?, ?)
                    """, receiptId, receiptNo, cpType, cpCode, cpName, cpName,
                    absAmt, absAmt, fundAcct, absAmt,
                    str(r.get("handler")), expenseNo, "费用单自动生成",
                    auditor, java.sql.Timestamp.valueOf(now), auditor, java.sql.Timestamp.valueOf(now));
            // 写核销记录
            writeReconcileRecordV2(receiptNo, java.sql.Date.valueOf(LocalDate.now()), expenseNo, expenseNo,
                    "EXPENSE", str(r.get("expenseDate")), cpType, cpCode, cpName, absAmt, "", "");
        } else {
            // 无收/付账户 → 生成往来 AR/AP
            writeCounterpartyLedger(cpType, cpCode, cpName, "IN".equals(direction) ? "IN" : "OUT",
                    total.abs(), expenseNo, "EXPENSE", BigDecimal.ZERO, "费用单生成往来");
        }
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

    @PostMapping("/reconcile/receive")
    @Transactional
    public ApiResponse<Map<String, Object>> receiveReconcile(@Valid @RequestBody FundBillRequest request) {
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

        return ApiResponse.ok(Map.of(
            "success", true,
            "effect", "收款已核销应收并生成资金流水",
            "arNo", ar.get("AR_NO"),
            "verifiedAmount", actualVerify,
            "remaining", newUnreceived
        ));
    }

    @PostMapping("/reconcile/pay")
    @Transactional
    public ApiResponse<Map<String, Object>> payReconcile(@Valid @RequestBody FundBillRequest request) {
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

        return ApiResponse.ok(Map.of(
            "success", true,
            "effect", "付款已核销应付并生成资金流水",
            "apNo", ap.get("AP_NO"),
            "verifiedAmount", actualVerify,
            "remaining", newUnpaid
        ));
    }

    // ============================================================
    // 收款单 CRUD + 审核 + 取消审核（V32 重构）
    // ============================================================

    @PostMapping("/receipt/page")
    public ApiResponse<PageResult<Map<String, Object>>> receiptPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT r.receipt_id, r.receipt_no, r.receipt_date, r.status,
                       r.counterparty_type, r.counterparty_code, r.counterparty_name,
                       r.total_amount, r.verified_amount, r.handler,
                       r.business_source, r.related_bill_no, r.summary,
                       r.creator_name, r.create_time, r.auditor_name, r.audit_time
                FROM fin_receipt_bill r
                WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
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
            switch (reconcileStatus) {
                case "未核销": sql.append(" AND (r.verified_amount = 0 OR r.verified_amount IS NULL)"); break;
                case "部分核销": sql.append(" AND r.verified_amount > 0 AND r.verified_amount < r.total_amount"); break;
                case "已核销": sql.append(" AND r.verified_amount >= r.total_amount AND r.total_amount > 0"); break;
            }
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
            // 核销状态
            BigDecimal total = toBd(r.get("totalAmount"));
            BigDecimal verified = toBd(r.get("verifiedAmount"));
            if (total.signum() <= 0) r.put("reconcileStatusText", "—");
            else if (verified.signum() <= 0) r.put("reconcileStatusText", "未核销");
            else if (verified.compareTo(total) < 0) r.put("reconcileStatusText", "部分核销");
            else r.put("reconcileStatusText", "已核销");
        }
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    /** 查询往来单位的未结算单据（供核销弹窗选择） */
    /** 查询往来单位的未结算单据（核销弹窗数据源） */
    @PostMapping("/receipt/unsettled-bills")
    public ApiResponse<Map<String, Object>> unsettledBills(@RequestBody Map<String, Object> body) {
        String cpType = str(body.get("counterpartyType"));
        String cpCode = str(body.get("counterpartyCode"));
        String cpName = str(body.get("counterpartyName"));
        String receiptId = str(body.get("receiptId"));
        // 待核销金额 = total_amount - verified_amount
        BigDecimal pendingAmount = BigDecimal.ZERO;
        if (!receiptId.isEmpty()) {
            List<Map<String, Object>> rr = queryCamel(
                    "SELECT total_amount, verified_amount FROM fin_receipt_bill WHERE receipt_id = ?", receiptId);
            if (!rr.isEmpty()) {
                Map<String, Object> rec = rr.get(0);
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
        return ApiResponse.ok(result);
    }

    /** 执行核销：勾选未结算单据 → 生成核销记录 + 更新 AR/AP + 更新收款单 verified_amount */
    @PostMapping("/receipt/reconcile")
    @Transactional
    public ApiResponse<Map<String, Object>> reconcileReceipt(@RequestBody Map<String, Object> body) {
        String receiptId = str(body.get("receiptId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_receipt_bill WHERE receipt_id = ?", receiptId);
        if (heads.isEmpty()) return ApiResponse.fail("404", "收款单不存在");
        Map<String, Object> r = heads.get(0);
        if (!"APPROVED".equals(str(r.get("status")))) return ApiResponse.fail("400", "仅已审核单据可核销");
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
        return ApiResponse.ok(Map.of("receiptNo", receiptNo, "reconciled", totalVerified));
    }

    /** 批量审核收款单 */
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
            auditSingleReceipt(r);
            ok++;
        }
        return ApiResponse.ok(Map.of("audited", ok, "skipped", skip));
    }

    /** 单条审核逻辑（供 batch-audit 与单条 audit 共用） */
    private void auditSingleReceipt(Map<String, Object> r) {
        String id = str(r.get("receiptId"));
        LocalDateTime now = LocalDateTime.now();
        String auditor = currentUser();
        String receiptNo = str(r.get("receiptNo"));
        // 写核销记录（简化：不实际匹配 AR/AP，后续可加强）
        // 更新状态
        jdbcTemplate.update("""
                UPDATE fin_receipt_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?
                WHERE receipt_id = ?
                """, auditor, java.sql.Timestamp.valueOf(now), id);
    }

    private String trimF(Map<String, Object> filters, String key, String altKey) {
        String v = str(filters.get(key)).trim();
        if (v.isEmpty() && altKey != null) v = str(filters.get(altKey)).trim();
        return v;
    }
    private String trimF(Map<String, Object> filters, String key) { return trimF(filters, key, null); }

    /** 收款单详情（含明细行） */
    @PostMapping("/receipt/detail")
    public ApiResponse<Map<String, Object>> receiptDetail(@RequestBody Map<String, Object> body) {
        String id = str(body.get("receiptId"));
        List<Map<String, Object>> heads = queryCamel(
                "SELECT * FROM fin_receipt_bill WHERE receipt_id = ? OR receipt_no = ?", id, id);
        if (heads.isEmpty()) return ApiResponse.fail("404", "收款单不存在");
        Map<String, Object> head = heads.get(0);
        head.put("details", queryCamel(
                "SELECT * FROM fin_receipt_detail WHERE receipt_id = ? ORDER BY sort_order",
                head.get("receiptId")));
        return ApiResponse.ok(head);
    }

    @PostMapping("/receipt/update")
    public ApiResponse<Boolean> updateReceipt(@RequestBody Map<String, Object> body) {
        String id = str(body.get("receiptId"));
        List<Map<String, Object>> exist = queryCamel(
                "SELECT status FROM fin_receipt_bill WHERE receipt_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "收款单不存在");
        String st = str(exist.get(0).get("status"));
        if (!"PENDING".equals(st)) return ApiResponse.fail("400", "仅待审核单据可编辑");

        BigDecimal total = sumDetails(body);
        jdbcTemplate.update("""
                UPDATE fin_receipt_bill SET receipt_date = ?, counterparty_type = ?,
                    counterparty_code = ?, counterparty_name = ?,
                    handler = ?, related_bill_no = ?, summary = ?, total_amount = ?
                WHERE receipt_id = ?
                """, date(body, "receiptDate"), str(body.get("counterpartyType")),
                str(body.get("counterpartyCode")), str(body.get("counterpartyName")),
                str(body.get("handler")), str(body.get("relatedBillNo")),
                str(body.get("summary")), total, id);
        jdbcTemplate.update("DELETE FROM fin_receipt_detail WHERE receipt_id = ?", id);
        insertDetails(id, body);
        return ApiResponse.ok(true);
    }

    @PostMapping("/receipt/delete")
    public ApiResponse<Boolean> deleteReceipt(@RequestBody Map<String, Object> body) {
        String id = str(body.get("receiptId"));
        List<Map<String, Object>> exist = queryCamel(
                "SELECT status, business_source FROM fin_receipt_bill WHERE receipt_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "收款单不存在");
        if (!"PENDING".equals(str(exist.get(0).get("status"))))
            return ApiResponse.fail("400", "仅待审核单据可删除");
        // 司机现场收款单代表司机手里真实拿着的钱，删掉就再也对不上交账差异，
        // 只能通过司机交账单审核/驳回来推进，后台不允许直接删除。
        if ("DRIVER_SETTLE".equals(str(exist.get(0).get("businessSource"))))
            return ApiResponse.fail("400", "司机现场收款单不允许删除，请通过司机交账单审核处理");
        jdbcTemplate.update("DELETE FROM fin_receipt_detail WHERE receipt_id = ?", id);
        jdbcTemplate.update("DELETE FROM fin_receipt_bill WHERE receipt_id = ?", id);
        return ApiResponse.ok(true);
    }

    /** 审核收款单：生成核销记录、更新 AR/AP、写资金流水、更新账户余额、写往来流水 */
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

        LocalDateTime now = LocalDateTime.now();
        String auditor = currentUser();
        String receiptNo = str(r.get("receiptNo"));
        java.sql.Date receiptDate = r.get("receiptDate") instanceof java.sql.Date d
                ? d : java.sql.Date.valueOf(LocalDate.now());
        String cpType = str(r.get("counterpartyType"));
        String cpCode = str(r.get("counterpartyCode"));
        String cpName = str(r.get("counterpartyName"));
        BigDecimal total = toBd(r.get("totalAmount"));
        String summary = str(r.get("summary"));

        // 1. 写核销记录：按明细行逐条匹配待核销业务单据
        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_receipt_detail WHERE receipt_id = ? ORDER BY sort_order", id);
        BigDecimal remaining = total;
        for (Map<String, Object> d : details) {
            String fundAccount = str(d.get("fundAccount"));
            BigDecimal lineAmount = toBd(d.get("amount"));
            if (lineAmount.signum() <= 0 || remaining.signum() <= 0) continue;
            BigDecimal actual = lineAmount.min(remaining);
            // 匹配该往来单位名下未核销的 AR/AP
            remaining = remaining.subtract(reconcileAndRecord(receiptNo, receiptDate, cpType, cpCode, cpName,
                    actual, summary, str(d.get("remark"))));
        }

        // 2. 写资金流水 + 更新账户余额（按明细行逐条）
        for (Map<String, Object> d : details) {
            BigDecimal amt = toBd(d.get("amount"));
            if (amt.signum() <= 0) continue;
            String fundAcct = str(d.get("fundAccount"));
            BigDecimal bal = getFundBalance(fundAcct).add(amt);
            insertFundLedgerV2(fundAcct, "IN", amt, receiptNo, bal, auditor);
            updateFundBalance(fundAcct, bal);
        }

        // 3. 写往来流水
        BigDecimal cpBal = getCounterpartyBalance(cpType, cpCode).add(total);
        writeCounterpartyLedger(cpType, cpCode, cpName, "IN", total, receiptNo, "RECEIPT", cpBal, summary);

        // 4. 更新收款单状态
        jdbcTemplate.update("""
                UPDATE fin_receipt_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?
                WHERE receipt_id = ?
                """, auditor, java.sql.Timestamp.valueOf(now), id);
        return ApiResponse.ok(GenericResult.row("receiptNo", receiptNo, "status", "APPROVED"));
    }

    /**
     * 取消审核：冲减流水、删除核销记录、回退 AR/AP 已收金额。
     * 取消后可修改后重新审核。
     */
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

        String receiptNo = str(r.get("receiptNo"));
        String cpType = str(r.get("counterpartyType"));
        String cpCode = str(r.get("counterpartyCode"));
        BigDecimal total = toBd(r.get("totalAmount"));

        // 1. 取核销记录，逐条回退 AR/AP
        List<Map<String, Object>> records = queryCamel(
                "SELECT * FROM fin_reconcile_record WHERE receipt_no = ?", receiptNo);
        for (Map<String, Object> rec : records) {
            String bizNo = str(rec.get("businessNo"));
            BigDecimal amt = toBd(rec.get("reconcileAmount"));
            // 回退 AR/AP 已收
            jdbcTemplate.update(
                    "UPDATE fin_ar SET received_amount = received_amount - ?, unreceived_amount = unreceived_amount + ?, status = 'UNVERIFIED' WHERE ar_no = ?",
                    amt, amt, bizNo);
            jdbcTemplate.update(
                    "UPDATE fin_ap SET paid_amount = paid_amount - ?, unpaid_amount = unpaid_amount + ?, status = 'UNVERIFIED' WHERE ap_no = ?",
                    amt, amt, bizNo);
        }

        // 2. 删除核销流水
        jdbcTemplate.update("DELETE FROM fin_reconcile_record WHERE receipt_no = ?", receiptNo);

        // 3. 冲减资金流水：写对冲记录
        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_receipt_detail WHERE receipt_id = ? ORDER BY sort_order", id);
        String auditor = currentUser();
        for (Map<String, Object> d : details) {
            BigDecimal amt = toBd(d.get("amount"));
            if (amt.signum() <= 0) continue;
            String fa = str(d.get("fundAccount"));
            BigDecimal bal = getFundBalance(fa).subtract(amt);
            insertFundLedgerV2(fa, "OUT", amt, receiptNo + "(取消审核)", bal, auditor);
            updateFundBalance(fa, bal);
        }

        // 4. 冲减往来流水
        BigDecimal cpBal = getCounterpartyBalance(cpType, cpCode).subtract(total);
        writeCounterpartyLedger(cpType, cpCode, str(r.get("counterpartyName")), "OUT", total,
                receiptNo + "(取消审核)", "RECEIPT_CANCEL", cpBal, "取消审核");

        // 5. 改回待审核
        jdbcTemplate.update("UPDATE fin_receipt_bill SET status = 'PENDING', auditor_name = NULL, audit_time = NULL WHERE receipt_id = ?", id);
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
        java.sql.Date receiptDate = r.get("receiptDate") instanceof java.sql.Date d
                ? d : java.sql.Date.valueOf(LocalDate.now());
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

        // 3. 资金入账：按收款明细逐个账户写流水并推余额
        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_receipt_detail WHERE receipt_id = ? ORDER BY sort_order", receiptId);
        for (Map<String, Object> d : details) {
            BigDecimal amt = toBd(d.get("amount"));
            if (amt.signum() <= 0) continue;
            String fundAcct = str(d.get("fundAccount"));
            BigDecimal bal = getFundBalance(fundAcct).add(amt);
            insertFundLedgerV2(fundAcct, "IN", amt, receiptNo, bal, auditor);
            updateFundBalance(fundAcct, bal);
        }

        // 4. 往来流水
        BigDecimal cpBal = getCounterpartyBalance(cpType, cpCode).add(total);
        writeCounterpartyLedger(cpType, cpCode, cpName, "IN", total, receiptNo, "RECEIPT", cpBal, summary);

        // 5. 单据转已审核，并按实际核销额回写 verified_amount
        BigDecimal verified = total.subtract(remaining);
        jdbcTemplate.update("""
                UPDATE fin_receipt_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?,
                    verified_amount = ?
                WHERE receipt_id = ?
                """, auditor, java.sql.Timestamp.valueOf(LocalDateTime.now()), verified, receiptId);

        return GenericResult.row("receiptNo", receiptNo, "status", "APPROVED",
                "verifiedAmount", verified, "unmatchedAmount", remaining);
    }

    // ============================================================
    // 付款单 CRUD（对称收款单）
    // ============================================================

    @PostMapping("/payment/update")
    public ApiResponse<Boolean> updatePayment(@RequestBody Map<String, Object> body) {
        String id = str(body.get("paymentId"));
        List<Map<String, Object>> exist = queryCamel(
                "SELECT status FROM fin_payment_bill WHERE payment_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "付款单不存在");
        if (!"PENDING".equals(str(exist.get(0).get("status"))))
            return ApiResponse.fail("400", "仅待审核单据可编辑");
        BigDecimal total = sumDetails(body);
        jdbcTemplate.update("""
                UPDATE fin_payment_bill SET payment_date = ?, counterparty_type = ?,
                    counterparty_code = ?, counterparty_name = ?,
                    handler = ?, related_bill_no = ?, summary = ?, total_amount = ?
                WHERE payment_id = ?
                """, date(body, "paymentDate"), str(body.get("counterpartyType")),
                str(body.get("counterpartyCode")), str(body.get("counterpartyName")),
                str(body.get("handler")), str(body.get("relatedBillNo")),
                str(body.get("summary")), total, id);
        jdbcTemplate.update("DELETE FROM fin_payment_detail WHERE payment_id = ?", id);
        insertPaymentDetails(id, body);
        return ApiResponse.ok(true);
    }

    @PostMapping("/payment/delete")
    public ApiResponse<Boolean> deletePayment(@RequestBody Map<String, Object> body) {
        String id = str(body.get("paymentId"));
        List<Map<String, Object>> exist = queryCamel(
                "SELECT status FROM fin_payment_bill WHERE payment_id = ?", id);
        if (exist.isEmpty()) return ApiResponse.fail("404", "付款单不存在");
        if (!"PENDING".equals(str(exist.get(0).get("status"))))
            return ApiResponse.fail("400", "仅待审核单据可删除");
        jdbcTemplate.update("DELETE FROM fin_payment_detail WHERE payment_id = ?", id);
        jdbcTemplate.update("DELETE FROM fin_payment_bill WHERE payment_id = ?", id);
        return ApiResponse.ok(true);
    }

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

        LocalDateTime now = LocalDateTime.now();
        String auditor = currentUser();
        String paymentNo = str(r.get("paymentNo"));
        java.sql.Date paymentDate = r.get("paymentDate") instanceof java.sql.Date d
                ? d : java.sql.Date.valueOf(LocalDate.now());
        String cpType = str(r.get("counterpartyType"));
        String cpCode = str(r.get("counterpartyCode"));
        String cpName = str(r.get("counterpartyName"));
        BigDecimal total = toBd(r.get("totalAmount"));
        String summary = str(r.get("summary"));

        // 1. 写核销记录：按明细行逐条匹配待核销 AP
        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_payment_detail WHERE payment_id = ? ORDER BY sort_order", id);
        BigDecimal remaining = total;
        for (Map<String, Object> d : details) {
            String fundAccount = str(d.get("fundAccount"));
            BigDecimal lineAmount = toBd(d.get("amount"));
            if (lineAmount.signum() <= 0 || remaining.signum() <= 0) continue;
            BigDecimal actual = lineAmount.min(remaining);
            // 匹配该供应商名下未核销的 AP
            remaining = remaining.subtract(reconcileAndRecord(paymentNo, paymentDate, cpType, cpCode, cpName,
                    actual, summary, str(d.get("remark"))));
        }

        // 2. 写资金流水（OUT）+ 更新账户余额
        for (Map<String, Object> d : details) {
            BigDecimal amt = toBd(d.get("amount"));
            if (amt.signum() <= 0) continue;
            String fundAcct = str(d.get("fundAccount"));
            BigDecimal bal = getFundBalance(fundAcct).subtract(amt);
            insertFundLedgerV2(fundAcct, "OUT", amt, paymentNo, bal, auditor);
            updateFundBalance(fundAcct, bal);
        }

        // 3. 写往来流水（OUT：付款给供应商 → 往来余额减少）
        BigDecimal cpBal = getCounterpartyBalance(cpType, cpCode).subtract(total);
        writeCounterpartyLedger(cpType, cpCode, cpName, "OUT", total, paymentNo, "PAYMENT", cpBal, summary);

        // 4. 更新付款单状态
        jdbcTemplate.update("""
                UPDATE fin_payment_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?
                WHERE payment_id = ?
                """, auditor, java.sql.Timestamp.valueOf(now), id);
        return ApiResponse.ok(GenericResult.row("paymentNo", paymentNo, "status", "APPROVED"));
    }

    /** 付款单取消审核：回退 AP 核销 + 冲减资金流水(IN) + 冲减往来流水(IN) */
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
        String paymentNo = str(r.get("paymentNo"));
        String cpType = str(r.get("counterpartyType"));
        String cpCode = str(r.get("counterpartyCode"));
        BigDecimal total = toBd(r.get("totalAmount"));

        // 1. 取核销记录，逐条回退 AP
        List<Map<String, Object>> records = queryCamel(
                "SELECT * FROM fin_reconcile_record WHERE receipt_no = ?", paymentNo);
        for (Map<String, Object> rec : records) {
            String bizNo = str(rec.get("businessNo"));
            BigDecimal amt = toBd(rec.get("reconcileAmount"));
            jdbcTemplate.update(
                "UPDATE fin_ap SET paid_amount = paid_amount - ?, unpaid_amount = unpaid_amount + ?, status = 'UNVERIFIED' WHERE ap_no = ?",
                amt, amt, bizNo);
        }

        // 2. 删除核销流水
        jdbcTemplate.update("DELETE FROM fin_reconcile_record WHERE receipt_no = ?", paymentNo);

        // 3. 冲减资金流水：写对冲记录（IN）
        List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_payment_detail WHERE payment_id = ? ORDER BY sort_order", id);
        String auditor = currentUser();
        for (Map<String, Object> d : details) {
            BigDecimal amt = toBd(d.get("amount"));
            if (amt.signum() <= 0) continue;
            String fa = str(d.get("fundAccount"));
            BigDecimal bal = getFundBalance(fa).add(amt);
            insertFundLedgerV2(fa, "IN", amt, paymentNo + "(取消审核)", bal, auditor);
            updateFundBalance(fa, bal);
        }

        // 4. 冲减往来流水（IN：取消付款 → 往来余额恢复）
        BigDecimal cpBal = getCounterpartyBalance(cpType, cpCode).add(total);
        writeCounterpartyLedger(cpType, cpCode, str(r.get("counterpartyName")), "IN", total,
                paymentNo + "(取消审核)", "PAYMENT_CANCEL", cpBal, "取消审核");

        // 5. 改回待审核
        jdbcTemplate.update("UPDATE fin_payment_bill SET status = 'PENDING', auditor_name = NULL, audit_time = NULL WHERE payment_id = ?", id);
        return ApiResponse.ok(GenericResult.row("paymentNo", paymentNo, "status", "PENDING"));
    }

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
            jdbcTemplate.update("""
                    UPDATE fin_payment_bill SET status = 'APPROVED', auditor_name = ?, audit_time = ?
                    WHERE payment_id = ?
                    """, currentUser(), java.sql.Timestamp.valueOf(LocalDateTime.now()), id);
            ok++;
        }
        return ApiResponse.ok(Map.of("audited", ok, "skipped", skip));
    }

    /** 收款核销流水表 —— 只读查询 */
    @PostMapping("/reconcile-record/page")
    public ApiResponse<PageResult<Map<String, Object>>> reconcileRecordPage(@RequestBody PageRequest request) {
        return ApiResponse.ok(PageResult.of(queryCamel("""
                SELECT record_id, receipt_no, receipt_date,
                       business_no, business_type, business_date,
                       counterparty_type, counterparty_code, counterparty_name,
                       reconcile_amount, receipt_remark, business_remark, created_at
                FROM fin_reconcile_record
                ORDER BY created_at DESC, receipt_no
                """), request));
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

    @PostMapping("/customer-statement/page")
    public ApiResponse<PageResult<Map<String, Object>>> csPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("SELECT * FROM fin_customer_statement WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
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
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @PostMapping("/customer-statement/create")
    public ApiResponse<Map<String, Object>> csCreate(@RequestBody Map<String, Object> body) {
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

    @PostMapping("/customer-statement/update")
    public ApiResponse<Boolean> csUpdate(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status FROM fin_customer_statement WHERE statement_id=?", id);
        if (ex.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可编辑");
        BigDecimal total = sumDetailField(body, "reconcileAmount");
        jdbcTemplate.update("UPDATE fin_customer_statement SET customer_code=?,customer_name=?,salesman=?,statement_date=?,expected_pay_date=?,contact_name=?,contact_phone=?,total_amount=?,remark=? WHERE statement_id=?",
                str(body.get("customerCode")),str(body.get("customerName")),str(body.get("salesman")),date(body,"statementDate"),date(body,"expectedPayDate"),str(body.get("contactName")),str(body.get("contactPhone")),total,str(body.get("remark")),id);
        jdbcTemplate.update("DELETE FROM fin_customer_statement_detail WHERE statement_id=?",id);
        insertCSDetails(id, body);
        return ApiResponse.ok(true);
    }

    @PostMapping("/customer-statement/detail")
    public ApiResponse<Map<String, Object>> csDetail(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_customer_statement WHERE statement_id=? OR statement_no=?",id,id);
        if (heads.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        Map<String,Object> h = heads.get(0);
        h.put("details", queryCamel("SELECT * FROM fin_customer_statement_detail WHERE statement_id=? ORDER BY sort_order", h.get("statementId")));
        return ApiResponse.ok(h);
    }

    @PostMapping("/customer-statement/delete")
    public ApiResponse<Boolean> csDelete(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status FROM fin_customer_statement WHERE statement_id=?",id);
        if (ex.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可删除");
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

    @PostMapping("/customer-statement/audit")
    public ApiResponse<Map<String, Object>> csAudit(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_customer_statement WHERE statement_id=?",id);
        if (heads.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(heads.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可审核");
        String auditor = currentUser(); LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("UPDATE fin_customer_statement SET status='APPROVED',auditor_name=?,audit_time=? WHERE statement_id=?", auditor, java.sql.Timestamp.valueOf(now), id);
        // 更新源单据的对账状态为"已对账"
        List<Map<String, Object>> details = queryCamel("SELECT source_bill_no FROM fin_customer_statement_detail WHERE statement_id=?",id);
        for (Map<String, Object> d : details) {
            jdbcTemplate.update("UPDATE fin_ar SET reconcile_status='已对账' WHERE source_bill=? OR ar_no=?", str(d.get("sourceBillNo")), str(d.get("sourceBillNo")));
        }
        return ApiResponse.ok(GenericResult.row("statementNo", str(heads.get(0).get("statementNo")), "status","APPROVED"));
    }

    @PostMapping("/customer-statement/reverse-audit")
    public ApiResponse<Map<String, Object>> csReverseAudit(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_customer_statement WHERE statement_id=?",id);
        if (heads.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        Map<String,Object> h = heads.get(0);
        if (!"APPROVED".equals(str(h.get("status")))) return ApiResponse.fail("400","仅已审核可反审核");
        if (toBd(h.get("paidAmount")).signum() > 0) return ApiResponse.fail("400","已有收款，不可反审核");
        jdbcTemplate.update("UPDATE fin_customer_statement SET status='PENDING',auditor_name=NULL,audit_time=NULL WHERE statement_id=?",id);
        // 还原源单据对账状态
        List<Map<String, Object>> details = queryCamel("SELECT source_bill_no FROM fin_customer_statement_detail WHERE statement_id=?",id);
        for (Map<String, Object> d : details) {
            jdbcTemplate.update("UPDATE fin_ar SET reconcile_status='对账中' WHERE source_bill=? OR ar_no=?", str(d.get("sourceBillNo")), str(d.get("sourceBillNo")));
        }
        return ApiResponse.ok(GenericResult.row("status","PENDING"));
    }

    /** 查询该客户未生成对账单的单据（添加单据弹窗数据源） */
    @PostMapping("/customer-statement/available-bills")
    public ApiResponse<List<Map<String, Object>>> csAvailableBills(@RequestBody Map<String, Object> body) {
        String customerName = str(body.get("customerName"));
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
        return ApiResponse.ok(rows);
    }

    /** 供应商可对账单据（未生成对账单的未付款 AP） */
    @PostMapping("/supplier-statement/available-bills")
    public ApiResponse<List<Map<String, Object>>> ssAvailableBills(@RequestBody Map<String, Object> body) {
        String supplierName = str(body.get("supplierName"));
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
        return ApiResponse.ok(rows);
    }

    /** 对账单收款结算 */
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

        BigDecimal settleAmount = totalAmount.subtract(writeOff);
        java.sql.Date settleDateSql = java.sql.Date.valueOf(settleDate.isEmpty()?LocalDate.now().toString():settleDate);
        LocalDateTime now = LocalDateTime.now(); String op = currentUser();

        // 生成收款单
        String receiptNo = billNoGen.nextNo("SK","fin_receipt_bill","receipt_no");
        String receiptId = "SK"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase();
        String firstAcct = "";
        if (acctsRaw instanceof List<?> al && !al.isEmpty() && al.get(0) instanceof Map<?,?> am) firstAcct = str(am.get("fundAccount"));
        jdbcTemplate.update("INSERT INTO fin_receipt_bill(receipt_id,receipt_no,receipt_date,status,counterparty_type,counterparty_code,counterparty_name,object_name,total_amount,verified_amount,fund_account,amount,business_source,handler,summary,creator_name,create_time,auditor_name,audit_time) VALUES(?,?,?,'APPROVED','CUSTOMER',?,?,?,?,?,?,?,'CUSTOMER_STATEMENT',?,?,?,?,?,?)",
                receiptId,receiptNo,settleDateSql, firstCust,firstCust,firstCust,settleAmount.abs(),settleAmount.abs(),firstAcct,settleAmount.abs(),handler,remark,op,java.sql.Timestamp.valueOf(now),op,java.sql.Timestamp.valueOf(now));

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

        // 核销每个对账单明细里的 AR 记录
        for (Map<String, Object> h : statements) {
            String stmtId = str(h.get("statementId"));
            // 更新对账单已收金额
            BigDecimal paid = toBd(h.get("paidAmount")).add(settleAmount.abs());
            jdbcTemplate.update("UPDATE fin_customer_statement SET paid_amount=?,write_off_amount=?,pay_status=CASE WHEN paid_amount>=total_amount THEN '完成收款' WHEN paid_amount>0 THEN '部分收款' ELSE '未收款' END WHERE statement_id=?",
                paid,writeOff,stmtId);

            // 逐明细核销 AR
            List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_customer_statement_detail WHERE statement_id=?", stmtId);
            for (Map<String, Object> d : details) {
                String sourceBillNo = str(d.get("sourceBillNo"));
                List<Map<String, Object>> arRows = queryCamel(
                    "SELECT * FROM fin_ar WHERE ar_no=? OR source_bill=?", sourceBillNo, sourceBillNo);
                if (arRows.isEmpty()) continue;
                Map<String, Object> ar = arRows.get(0);
                BigDecimal unreceived = toBd(ar.get("unreceivedAmount"));
                if (unreceived.signum() <= 0) continue;
                BigDecimal recAmt = toBd(d.get("reconcileAmount")).min(unreceived);
                BigDecimal newRcv = toBd(ar.get("receivedAmount")).add(recAmt);
                BigDecimal newUnrcv = toBd(ar.get("arAmount")).subtract(newRcv);
                jdbcTemplate.update("UPDATE fin_ar SET received_amount=?, unreceived_amount=?, status=? WHERE ar_no=?",
                    newRcv, newUnrcv, newUnrcv.signum()<=0?"VERIFIED":"UNVERIFIED", str(ar.get("arNo")));
                // 写核销记录
                writeReconcileRecord(receiptNo, settleDateSql, str(ar.get("arNo")),
                    "CUSTOMER_STATEMENT", str(ar.get("dueDate")), "CUSTOMER", firstCust, firstCustName,
                    recAmt, remark, "");
            }
        }

        // 写资金流水 + 更新资金账户余额
        if (acctsRaw instanceof List<?> al) {
            for (Object item : al) {
                if (!(item instanceof Map<?,?> am)) continue;
                String acct = str(am.get("fundAccount"));
                BigDecimal amt = toBd(am.get("amount"));
                if (amt.signum() <= 0) continue;
                BigDecimal bal = getFundBalance(acct).add(amt);
                insertFundLedgerV2(acct, "IN", amt, receiptNo, bal, op);
                updateFundBalance(acct, bal);
            }
        }

        // 写往来流水
        BigDecimal cpBal = getCounterpartyBalance("CUSTOMER", firstCust).add(settleAmount.abs());
        writeCounterpartyLedger("CUSTOMER", firstCust, firstCustName, "IN", settleAmount.abs(),
            receiptNo, "CUSTOMER_STATEMENT_SETTLE", cpBal, remark);

        return ApiResponse.ok(Map.of("receiptNo",receiptNo,"settleAmount",settleAmount));
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

    @PostMapping("/supplier-statement/page")
    public ApiResponse<PageResult<Map<String, Object>>> ssPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("SELECT * FROM fin_supplier_statement WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
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
        return ApiResponse.ok(PageResult.of(rows, request));
    }

    @PostMapping("/supplier-statement/create")
    public ApiResponse<Map<String, Object>> ssCreate(@RequestBody Map<String, Object> body) {
        String id = "SS" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String no = billNoGen.nextNo("SS", "fin_supplier_statement", "statement_no");
        LocalDateTime now = LocalDateTime.now(); String op = currentUser();
        BigDecimal total = sumDetailField(body, "reconcileAmount");
        jdbcTemplate.update("INSERT INTO fin_supplier_statement(statement_id,statement_no,supplier_code,supplier_name,buyer,statement_date,expected_pay_date,contact_name,contact_phone,is_invoiced,total_amount,status,remark,creator_name,create_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?)",
                id, no, str(body.get("customerCode")), str(body.get("customerName")), str(body.get("salesman")), date(body,"statementDate"), date(body,"expectedPayDate"), str(body.get("contactName")), str(body.get("contactPhone")), str(body.get("isInvoiced")), total, str(body.get("remark")), op, java.sql.Timestamp.valueOf(now));
        insertSSDetails(id, body);
        return ApiResponse.ok(GenericResult.row("statementId", id, "statementNo", no));
    }

    @PostMapping("/supplier-statement/update")
    public ApiResponse<Boolean> ssUpdate(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status FROM fin_supplier_statement WHERE statement_id=?", id);
        if (ex.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可编辑");
        BigDecimal total = sumDetailField(body, "reconcileAmount");
        jdbcTemplate.update("UPDATE fin_supplier_statement SET supplier_code=?,supplier_name=?,buyer=?,statement_date=?,expected_pay_date=?,contact_name=?,contact_phone=?,is_invoiced=?,total_amount=?,remark=? WHERE statement_id=?",
                str(body.get("customerCode")),str(body.get("customerName")),str(body.get("salesman")),date(body,"statementDate"),date(body,"expectedPayDate"),str(body.get("contactName")),str(body.get("contactPhone")),str(body.get("isInvoiced")),total,str(body.get("remark")),id);
        jdbcTemplate.update("DELETE FROM fin_supplier_statement_detail WHERE statement_id=?",id);
        insertSSDetails(id, body);
        return ApiResponse.ok(true);
    }

    @PostMapping("/supplier-statement/delete")
    public ApiResponse<Boolean> ssDelete(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> ex = queryCamel("SELECT status FROM fin_supplier_statement WHERE statement_id=?",id);
        if (ex.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(ex.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可删除");
        jdbcTemplate.update("DELETE FROM fin_supplier_statement_detail WHERE statement_id=?",id);
        jdbcTemplate.update("DELETE FROM fin_supplier_statement WHERE statement_id=?",id);
        return ApiResponse.ok(true);
    }

    @PostMapping("/supplier-statement/detail")
    public ApiResponse<Map<String, Object>> ssDetail(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_supplier_statement WHERE statement_id=? OR statement_no=?",id,id);
        if (heads.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        Map<String,Object> h = heads.get(0);
        h.put("details", queryCamel("SELECT * FROM fin_supplier_statement_detail WHERE statement_id=? ORDER BY sort_order", h.get("statementId")));
        return ApiResponse.ok(h);
    }

    @PostMapping("/supplier-statement/audit")
    public ApiResponse<Map<String, Object>> ssAudit(@RequestBody Map<String, Object> body) {
        String id = str(body.get("statementId"));
        List<Map<String, Object>> heads = queryCamel("SELECT * FROM fin_supplier_statement WHERE statement_id=?",id);
        if (heads.isEmpty()) return ApiResponse.fail("404","对账单不存在");
        if (!"PENDING".equals(str(heads.get(0).get("status")))) return ApiResponse.fail("400","仅待审核可审核");
        jdbcTemplate.update("UPDATE fin_supplier_statement SET status='APPROVED',auditor_name=?,audit_time=? WHERE statement_id=?", currentUser(), java.sql.Timestamp.valueOf(LocalDateTime.now()), id);
        return ApiResponse.ok(GenericResult.row("status","APPROVED"));
    }

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

        BigDecimal settleAmount = totalAmount.subtract(writeOff);
        java.sql.Date settleDateSql = java.sql.Date.valueOf(settleDate.isEmpty()?LocalDate.now().toString():settleDate);
        LocalDateTime now = LocalDateTime.now(); String op = currentUser();

        // 生成付款单
        String paymentNo = billNoGen.nextNo("FK","fin_payment_bill","payment_no");
        String paymentId = "FK"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase();
        String firstAcct = "";
        if (acctsRaw instanceof List<?> al && !al.isEmpty() && al.get(0) instanceof Map<?,?> am) firstAcct = str(am.get("fundAccount"));
        jdbcTemplate.update("INSERT INTO fin_payment_bill(payment_id,payment_no,payment_date,status,counterparty_type,counterparty_code,counterparty_name,object_name,total_amount,verified_amount,fund_account,amount,business_source,handler,summary,creator_name,create_time,auditor_name,audit_time) VALUES(?,?,?,'APPROVED','SUPPLIER',?,?,?,?,?,?,?,'SUPPLIER_STATEMENT',?,?,?,?,?,?)",
                paymentId,paymentNo,settleDateSql, firstSupp,firstSupp,firstSupp,settleAmount.abs(),settleAmount.abs(),firstAcct,settleAmount.abs(),handler,remark,op,java.sql.Timestamp.valueOf(now),op,java.sql.Timestamp.valueOf(now));

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

        // 核销每个对账单明细里的 AP 记录
        for (Map<String, Object> h : statements) {
            String stmtId = str(h.get("statementId"));
            // 更新对账单已付金额
            BigDecimal paid = toBd(h.get("paidAmount")).add(settleAmount.abs());
            jdbcTemplate.update("UPDATE fin_supplier_statement SET paid_amount=?,write_off_amount=?,pay_status=CASE WHEN paid_amount>=total_amount THEN '完成付款' WHEN paid_amount>0 THEN '部分付款' ELSE '未付款' END WHERE statement_id=?",
                paid,writeOff,stmtId);

            // 逐明细核销 AP
            List<Map<String, Object>> details = queryCamel(
                "SELECT * FROM fin_supplier_statement_detail WHERE statement_id=?", stmtId);
            for (Map<String, Object> d : details) {
                String sourceBillNo = str(d.get("sourceBillNo"));
                List<Map<String, Object>> apRows = queryCamel(
                    "SELECT * FROM fin_ap WHERE ap_no=? OR source_bill=?", sourceBillNo, sourceBillNo);
                if (apRows.isEmpty()) continue;
                Map<String, Object> ap = apRows.get(0);
                BigDecimal unpaid = toBd(ap.get("unpaidAmount"));
                if (unpaid.signum() <= 0) continue;
                BigDecimal payAmt = toBd(d.get("reconcileAmount")).min(unpaid);
                BigDecimal newPaid = toBd(ap.get("paidAmount")).add(payAmt);
                BigDecimal newUnpaid = toBd(ap.get("apAmount")).subtract(newPaid);
                jdbcTemplate.update("UPDATE fin_ap SET paid_amount=?, unpaid_amount=?, status=? WHERE ap_no=?",
                    newPaid, newUnpaid, newUnpaid.signum()<=0?"VERIFIED":"UNVERIFIED", str(ap.get("apNo")));
                // 写核销记录
                writeReconcileRecord(paymentNo, settleDateSql, str(ap.get("apNo")),
                    "SUPPLIER_STATEMENT", str(ap.get("dueDate")), "SUPPLIER", firstSupp, firstSuppName,
                    payAmt, remark, "");
            }
        }

        // 写资金流水（OUT）+ 更新资金账户余额
        if (acctsRaw instanceof List<?> al) {
            for (Object item : al) {
                if (!(item instanceof Map<?,?> am)) continue;
                String acct = str(am.get("fundAccount"));
                BigDecimal amt = toBd(am.get("amount"));
                if (amt.signum() <= 0) continue;
                BigDecimal bal = getFundBalance(acct).subtract(amt);
                insertFundLedgerV2(acct, "OUT", amt, paymentNo, bal, op);
                updateFundBalance(acct, bal);
            }
        }

        // 写往来流水（OUT：付款给供应商 → 往来余额减少）
        BigDecimal cpBal = getCounterpartyBalance("SUPPLIER", firstSupp).subtract(settleAmount.abs());
        writeCounterpartyLedger("SUPPLIER", firstSupp, firstSuppName, "OUT", settleAmount.abs(),
            paymentNo, "SUPPLIER_STATEMENT_SETTLE", cpBal, remark);

        return ApiResponse.ok(Map.of("paymentNo",paymentNo,"amount",settleAmount.abs()));
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

    private void writeReconcileRecord(String receiptNo, java.sql.Date receiptDate, String bizNo,
            String bizType, String bizDate, String cpType, String cpCode, String cpName,
            BigDecimal amount, String receiptRemark, String bizRemark) {
        jdbcTemplate.update("""
                INSERT INTO fin_reconcile_record(record_id, receipt_no, receipt_date,
                    business_no, business_type, business_date,
                    counterparty_type, counterparty_code, counterparty_name,
                    reconcile_amount, receipt_remark, business_remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "RR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                receiptNo, receiptDate, bizNo, bizType,
                bizDate.isEmpty() ? null : java.sql.Date.valueOf(bizDate.substring(0, 10)),
                cpType, cpCode, cpName, amount, receiptRemark, bizRemark);
    }

    private void writeReconcileRecordV2(String receiptNo, java.sql.Date receiptDate, String arNo,
            String sourceBill, String bizType, String bizDate, String cpType, String cpCode, String cpName,
            BigDecimal amount, String receiptRemark, String bizRemark) {
        jdbcTemplate.update("""
                INSERT INTO fin_reconcile_record(record_id, receipt_no, receipt_date,
                    business_no, business_type, business_date,
                    counterparty_type, counterparty_code, counterparty_name,
                    reconcile_amount, receipt_remark, business_remark, ar_no, source_bill)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "RR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                receiptNo, receiptDate, arNo, bizType,
                bizDate.isEmpty() ? null : java.sql.Date.valueOf(bizDate.substring(0, 10)),
                cpType, cpCode, cpName, amount, receiptRemark, bizRemark, arNo, sourceBill);
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
        jdbcTemplate.update("""
                INSERT INTO fin_fund_ledger(ledger_id, ledger_no, fund_account, direction, amount, source_bill, balance_after, occurred_at, operator_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                """, "FL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                "FUND" + System.currentTimeMillis(), fundAccount, direction, amount, sourceBill, balanceAfter, operator);
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

    @PostMapping("/payment/page")
    public ApiResponse<PageResult<Map<String, Object>>> paymentPage(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("""
                SELECT p.payment_id, p.payment_no, p.payment_date, p.status,
                       p.counterparty_type, p.counterparty_code, p.counterparty_name,
                       p.total_amount, p.verified_amount, p.handler,
                       p.business_source, p.related_bill_no, p.summary,
                       p.creator_name, p.create_time, p.auditor_name, p.audit_time
                FROM fin_payment_bill p
                WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
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
            BigDecimal total = toBd(r.get("totalAmount"));
            BigDecimal verified = toBd(r.get("verifiedAmount"));
            if (total.signum() <= 0) r.put("reconcileStatusText", "—");
            else if (verified.signum() <= 0) r.put("reconcileStatusText", "未核销");
            else if (verified.compareTo(total) < 0) r.put("reconcileStatusText", "部分核销");
            else r.put("reconcileStatusText", "已核销");
        }
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

    public record FundBillRequest(@NotBlank String objectId, @NotBlank String fundAccountId, @NotNull @Positive BigDecimal amount, String remark) {}
}
