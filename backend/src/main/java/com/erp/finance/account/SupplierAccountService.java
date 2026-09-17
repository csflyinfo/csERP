package com.erp.finance.account;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 供应商账户服务（PRD-36）。
 *
 * <p>真值与缓存：fin_ap / fin_reconcile_record 是应付真值（M2 后预付核销、M3 后厂家费用账扣
 * 的在线流水不取自核销记录补漏）；账户表三余额、流水结算标志、balance_after 滚存链都是可重建缓存。
 * {@link #repair(String)} 可从真值完整重建并出差异报告。
 *
 * <p>M1 提供只读账户/流水查询与数据修复；M2/M3/M4 在本服务底座上接入预付收退款、预付核销、
 * 厂家费用单/兑现单、期初预付。
 */
@Service
public class SupplierAccountService {

    public static final String MODULE = "fin.supplier_account";

    /** 余额链排序：同刻先形成后结算（收货当天即付款时形成行在前）。 */
    private static final String CHAIN_ORDER =
            "occurred_at, CASE WHEN biz_type IN ('AP_RECEIPT','AP_RETURN','AP_OPENING') THEN 0 ELSE 1 END, flow_id";
    private static final String CHAIN_ORDER_DESC =
            "occurred_at DESC, CASE WHEN biz_type IN ('AP_RECEIPT','AP_RETURN','AP_OPENING') "
                    + "THEN 0 ELSE 1 END DESC, flow_id DESC";

    private final JdbcTemplate jdbc;
    private final OperationLogService opLog;

    public SupplierAccountService(JdbcTemplate jdbc, OperationLogService opLog) {
        this.jdbc = jdbc;
        this.opLog = opLog;
    }

    // ==================== M1：账户分页 ====================

    /** 账户分页。body: {pageNo,pageSize,keyword,hideZero}。 */
    public Map<String, Object> pageAccounts(Map<String, Object> req) {
        int pageNo = Math.max(1, TmsUtil.toInt(req.get("pageNo")));
        int pageSizeRaw = TmsUtil.toInt(req.get("pageSize"));
        int pageSize = pageSizeRaw <= 0 ? 20 : Math.min(200, pageSizeRaw);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String keyword = TmsUtil.str(req.get("keyword"));
        if (!keyword.isEmpty()) {
            // 关键字口径与供应商主档分页一致：编号/名称/联系人/手机，另兜底账户名（档案可能已停用/删除）
            where.append(" AND (s.supplier_code LIKE ? OR s.supplier_name LIKE ? OR s.contact_name LIKE ?"
                    + " OR s.phone LIKE ? OR a.supplier_name LIKE ?)");
            for (int i = 0; i < 5; i++) args.add("%" + keyword + "%");
        }
        if (Boolean.TRUE.equals(req.get("hideZero"))) {
            where.append(" AND (a.ap_balance<>0 OR a.prepay_balance<>0 OR a.expense_balance<>0)");
        }

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_supplier_account a "
                        + "LEFT JOIN base_supplier s ON s.supplier_code=a.supplier_code" + where,
                Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT a.supplier_code, COALESCE(s.supplier_name,a.supplier_name) supplier_name, "
                        + "a.ap_balance, a.prepay_balance, a.expense_balance, "
                        + "s.default_buyer, s.settlement_method, s.account_period_days, s.status "
                        + "FROM fin_supplier_account a "
                        + "LEFT JOIN base_supplier s ON s.supplier_code=a.supplier_code" + where
                        + " ORDER BY a.ap_balance DESC, a.supplier_code LIMIT ? OFFSET ?",
                pageArgs.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("pageNo", pageNo);
        result.put("pageSize", pageSize);
        result.put("total", total);
        return result;
    }

    // ==================== M1：流水分页 ====================

    /**
     * 往来流水分页。body: {supplierCode,accountType,beginTime,endTime,settleStatus,pageNo,pageSize}。
     * 返回 priorBalance（此前余额）+ 分页流水（含 bizLabel 业务单据文案）。
     */
    public Map<String, Object> pageFlow(Map<String, Object> req) {
        String supplierCode = TmsUtil.str(req.get("supplierCode"));
        if (supplierCode.isEmpty()) {
            throw new IllegalArgumentException("缺少供应商参数");
        }
        String accountType = TmsUtil.str(req.get("accountType"));
        if (accountType.isEmpty()) {
            accountType = SupplierAccountConst.ACCOUNT_AP;
        }
        if (!SupplierAccountConst.ACCOUNT_AP.equals(accountType)
                && !SupplierAccountConst.ACCOUNT_PREPAY.equals(accountType)
                && !SupplierAccountConst.ACCOUNT_EXPENSE.equals(accountType)) {
            throw new IllegalArgumentException("账户类型不正确");
        }

        StringBuilder where = new StringBuilder(" WHERE supplier_code=? AND account_type=?");
        List<Object> args = new ArrayList<>();
        args.add(supplierCode);
        args.add(accountType);

        String begin = TmsUtil.str(req.get("beginTime"));
        if (!begin.isEmpty()) {
            where.append(" AND occurred_at>=?");
            args.add(Timestamp.valueOf(ensureDateTime(begin, false)));
        }
        String end = TmsUtil.str(req.get("endTime"));
        if (!end.isEmpty()) {
            where.append(" AND occurred_at<=?");
            args.add(Timestamp.valueOf(ensureDateTime(end, true)));
        }
        String settleStatus = TmsUtil.str(req.get("settleStatus"));
        if (!settleStatus.isEmpty()) {
            where.append(" AND settle_status=?");
            args.add(settleStatus);
        }

        // 此前余额：期间开始前该账户最后一笔滚存余额
        BigDecimal priorBalance = BigDecimal.ZERO;
        if (!begin.isEmpty()) {
            List<BigDecimal> prior = jdbc.query(
                    "SELECT balance_after FROM fin_supplier_account_flow"
                            + " WHERE supplier_code=? AND account_type=? AND occurred_at<?"
                            + " ORDER BY " + CHAIN_ORDER_DESC + " LIMIT 1",
                    (rs, i) -> rs.getBigDecimal("balance_after"),
                    supplierCode, accountType, Timestamp.valueOf(ensureDateTime(begin, false)));
            if (!prior.isEmpty() && prior.get(0) != null) {
                priorBalance = prior.get(0);
            }
        }

        int pageNo = Math.max(1, TmsUtil.toInt(req.get("pageNo")));
        int pageSizeRaw = TmsUtil.toInt(req.get("pageSize"));
        int pageSize = pageSizeRaw <= 0 ? 20 : Math.min(200, pageSizeRaw);

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_supplier_account_flow" + where,
                Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT flow_id, supplier_code, supplier_name, account_type, biz_type, "
                        + "increase_amount, decrease_amount, balance_after, settle_status, settled_amount, "
                        + "ap_no, factory_expense_no, source_bill, payment_no, writeoff_no, reconcile_id, "
                        + "receipt_no, post_date, occurred_at, summary, operator_name, is_red, reverse_status "
                        + "FROM fin_supplier_account_flow" + where
                        + " ORDER BY " + CHAIN_ORDER_DESC + " LIMIT ? OFFSET ?",
                pageArgs.toArray());
        for (Map<String, Object> row : records) {
            row.put("bizLabel", SupplierAccountConst.bizLabel(TmsUtil.str(row.get("bizType"))));
        }

        // 当前账户实时余额（页面头部展示）
        BigDecimal currentBalance = currentBalance(supplierCode, accountType);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("pageNo", pageNo);
        result.put("pageSize", pageSize);
        result.put("total", total);
        result.put("priorBalance", priorBalance);
        result.put("currentBalance", currentBalance);
        return result;
    }

    // ==================== M1：数据修复 ====================

    /**
     * 数据修复（对应旧系统「数据修复」）：
     * 1) 补账户行/档案快照；2) 补缺 AP 形成/结算流水；3) 按应付单回填结算标志；
     * 4) 重排三账户余额链；5) 按真值重算账户余额并出差异报告。不删除业务流水。
     *
     * @param supplierCode 指定供应商；空串=全部供应商
     */
    @Transactional
    public Map<String, Object> repair(String supplierCode) {
        String onlyCode = supplierCode == null ? "" : supplierCode.trim();
        List<String> mismatches = new ArrayList<>();
        int missingForming = 0;
        int missingSettle = 0;

        // 1) 账户行：在册供应商全量补齐（单供应商时只补该供应商）
        if (onlyCode.isEmpty()) {
            jdbc.update("MERGE INTO fin_supplier_account (supplier_code,supplier_name,default_buyer,"
                    + "settlement_method,account_period_days,ap_balance,prepay_balance,expense_balance,"
                    + "version,created_at,updated_at) KEY(supplier_code) "
                    + "SELECT supplier_code,supplier_name,default_buyer,settlement_method,account_period_days,"
                    + "0,0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM base_supplier "
                    + "WHERE supplier_code IS NOT NULL");
        } else {
            ensureAccount(onlyCode);
        }

        // 已存在流水幂等键，避免重复补
        Set<String> existingKeys = new HashSet<>(jdbc.query(
                "SELECT biz_key FROM fin_supplier_account_flow", (rs, i) -> rs.getString("biz_key")));

        // 2.1) 应付真值（按解析后的供应商编码分组）+ 补缺形成流水
        Map<String, BigDecimal> trueAp = new HashMap<>();
        List<Map<String, Object>> apRows = TmsUtil.queryCamel(jdbc,
                "SELECT ap_id, ap_no, source_bill, supplier, ap_amount, paid_amount, "
                        + "unpaid_amount, due_date FROM fin_ap ORDER BY due_date, ap_no");
        for (Map<String, Object> ap : apRows) {
            String code = resolveApSupplierCode(ap);
            if (code == null || code.isEmpty()) {
                code = TmsUtil.str(ap.get("supplier"));
            }
            if (!onlyCode.isEmpty() && !onlyCode.equals(code)) {
                continue;
            }
            BigDecimal unpaid = nz(TmsUtil.toBd(ap.get("unpaidAmount")));
            trueAp.merge(code, unpaid, BigDecimal::add);
            ensureAccount(code);

            String bizKey = "AP:" + TmsUtil.str(ap.get("apNo"));
            if (!existingKeys.contains(bizKey)) {
                insertFormingFlowFromAp(ap, code, bizKey);
                existingKeys.add(bizKey);
                missingForming++;
            }
        }

        // 2.2) 补缺结算流水（只取归属应付单的核销记录）
        // 预付核销（PREPAY_WRITE_OFF）/厂家费用账扣（FACTORY_EXPENSE_OFFSET）的 AP 流水在线已写
        // （AP_SETTLE_PREPAY / AP_SETTLE_EXPENSE，bizKey FXAP:* / DXAP:*），不能再按现金结算补一遍。
        List<Map<String, Object>> recRows = TmsUtil.queryCamel(jdbc,
                "SELECT record_id, receipt_no, receipt_date, business_no, business_type, business_date, "
                        + "counterparty_code, counterparty_name, reconcile_amount, ar_no, source_bill, "
                        + "created_at FROM fin_reconcile_record r "
                        + "WHERE r.counterparty_type='SUPPLIER' "
                        + "AND EXISTS (SELECT 1 FROM fin_ap a WHERE a.ap_no=COALESCE(r.ar_no,r.business_no)) "
                        + "AND r.business_type NOT IN ('PREPAY_WRITE_OFF','FACTORY_EXPENSE_OFFSET') "
                        + "ORDER BY created_at, record_id");
        for (Map<String, Object> rec : recRows) {
            String apNo = TmsUtil.str(rec.get("arNo"));
            if (apNo.isEmpty()) {
                apNo = TmsUtil.str(rec.get("businessNo"));
            }
            String code = resolveApSupplierCodeByApNo(apNo);
            if (code.isEmpty()) {
                code = TmsUtil.str(rec.get("counterpartyCode"));
                if (code.isEmpty()) {
                    code = TmsUtil.str(rec.get("counterpartyName"));
                }
            }
            if (code.isEmpty() || (!onlyCode.isEmpty() && !onlyCode.equals(code))) {
                continue;
            }
            ensureAccount(code);

            String bizKey = "APR:" + TmsUtil.str(rec.get("recordId"));
            if (!existingKeys.contains(bizKey)) {
                insertSettleFlowFromRecord(rec, code, apNo, bizKey);
                existingKeys.add(bizKey);
                missingSettle++;
            }
        }

        // 3) 回填形成行结算标志/已结额（真值取 fin_ap 自身的 paid/unpaid）
        for (Map<String, Object> ap : apRows) {
            String apNo = TmsUtil.str(ap.get("apNo"));
            String code = resolveApSupplierCode(ap);
            if (code.isEmpty()) {
                code = TmsUtil.str(ap.get("supplier"));
            }
            if (!onlyCode.isEmpty() && !onlyCode.equals(code)) {
                continue;
            }
            BigDecimal paid = nz(TmsUtil.toBd(ap.get("paidAmount")));
            BigDecimal unpaid = nz(TmsUtil.toBd(ap.get("unpaidAmount")));
            String status;
            if (unpaid.signum() == 0) {
                status = SupplierAccountConst.SETTLE_DONE;
            } else if (paid.signum() == 0) {
                status = SupplierAccountConst.SETTLE_UNSETTLED;
            } else {
                status = SupplierAccountConst.SETTLE_PART;
            }
            jdbc.update("UPDATE fin_supplier_account_flow SET settled_amount=?, settle_status=? "
                            + "WHERE ap_no=? AND account_type='AP' AND biz_type IN "
                            + "('AP_RECEIPT','AP_RETURN','AP_OPENING')",
                    paid, status, apNo);
        }

        // 4) 重排目标供应商三个账户余额链；5) 真值重算余额并比对差异
        List<String> targets;
        if (!onlyCode.isEmpty()) {
            targets = List.of(onlyCode);
        } else {
            targets = jdbc.queryForList(
                    "SELECT DISTINCT supplier_code FROM fin_supplier_account_flow", String.class);
        }
        int chains = 0;
        for (String code : targets) {
            BigDecimal apChain = rebuildChain(code, SupplierAccountConst.ACCOUNT_AP);
            BigDecimal prepayChain = rebuildChain(code, SupplierAccountConst.ACCOUNT_PREPAY);
            BigDecimal expChain = rebuildChain(code, SupplierAccountConst.ACCOUNT_EXPENSE);
            chains += 3;
            BigDecimal trueApBal = trueAp.getOrDefault(code, BigDecimal.ZERO);
            if (apChain.compareTo(trueApBal) != 0) {
                mismatches.add("供应商 " + code + " 应付流水余额 " + apChain + " 与应付单真值 " + trueApBal
                        + " 不一致，已按真值修正");
            }
            // AP 按真值；预付/费用按流水链（其真值即流水本身，后续里程碑由在线单据保证）
            jdbc.update("UPDATE fin_supplier_account SET ap_balance=?, prepay_balance=?, expense_balance=?, "
                            + "updated_at=CURRENT_TIMESTAMP WHERE supplier_code=?",
                    trueApBal, prepayChain, expChain, code);
            refreshSnapshot(code);
        }
        // 没有任何流水的账户行，应付也按真值兜底（理论上形成流水已补齐，这里防御）
        if (!onlyCode.isEmpty()) {
            jdbc.update("UPDATE fin_supplier_account SET ap_balance=? WHERE supplier_code=?",
                    trueAp.getOrDefault(onlyCode, BigDecimal.ZERO), onlyCode);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", onlyCode.isEmpty() ? "ALL" : onlyCode);
        result.put("accounts", targets.size());
        result.put("missingForming", missingForming);
        result.put("missingSettle", missingSettle);
        result.put("chainsRebuilt", chains);
        result.put("mismatches", mismatches);
        opLog.log(MODULE, "DATA_REPAIR", "",
                "供应商账户数据修复：" + (onlyCode.isEmpty() ? "全部供应商" : onlyCode)
                        + "，补缺形成流水 " + missingForming + " 条、结算流水 " + missingSettle
                        + " 条，重排余额链 " + chains + " 条，差异 " + mismatches.size() + " 项");
        return result;
    }

    // ==================== 底座：账户与流水（M2~M4 使用） ====================

    /** 确保账户行存在（在册供应商按档案建，历史 key 用空快照建），返回账户行。 */
    @Transactional
    public Map<String, Object> ensureAccount(String supplierCode) {
        if (supplierCode == null || supplierCode.isEmpty()) {
            throw new IllegalArgumentException("供应商编码不能为空");
        }
        // 注意：MERGE 对已存在行会用 SET/SELECT 覆盖余额，绝不能把余额列写成 0，
        // 否则在线流水会把账户上已有的三余额清零。已存在行必须回填自身余额。
        jdbc.update("MERGE INTO fin_supplier_account (supplier_code,supplier_name,default_buyer,"
                + "settlement_method,account_period_days,ap_balance,prepay_balance,expense_balance,"
                + "version,created_at,updated_at) KEY(supplier_code) "
                + "SELECT s.supplier_code,s.supplier_name,s.default_buyer,s.settlement_method,"
                + "s.account_period_days,COALESCE(a.ap_balance,0),COALESCE(a.prepay_balance,0),"
                + "COALESCE(a.expense_balance,0),COALESCE(a.version,0),"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM base_supplier s "
                + "LEFT JOIN fin_supplier_account a ON a.supplier_code=s.supplier_code "
                + "WHERE s.supplier_code=?",
                supplierCode);
        // 档案已删/名称兜底场景：仅在账户行不存在时插入，禁止覆盖已有行
        jdbc.update("INSERT INTO fin_supplier_account(supplier_code,supplier_name,ap_balance,prepay_balance,"
                + "expense_balance,version,created_at,updated_at) "
                + "SELECT ?,?,0,0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS ("
                + "SELECT 1 FROM fin_supplier_account WHERE supplier_code=?)",
                supplierCode, supplierCode, supplierCode);
        refreshSnapshot(supplierCode);
        return TmsUtil.queryCamel(jdbc,
                "SELECT supplier_code, supplier_name, ap_balance, prepay_balance, expense_balance "
                        + "FROM fin_supplier_account WHERE supplier_code=?", supplierCode).get(0);
    }

    /** 同供应商并发串行化（审核类事务先锁账户再写流水）。 */
    public void lockAccount(String supplierCode) {
        jdbc.queryForObject(
                "SELECT supplier_code FROM fin_supplier_account WHERE supplier_code=? FOR UPDATE",
                String.class, supplierCode);
    }

    /** 写一笔流水并滚存 balance_after；调用方需在事务内先 {@link #lockAccount}。 */
    @Transactional
    public void writeFlow(SupplierAccountFlowLine line) {
        if (line.getSupplierCode() == null || line.getSupplierCode().isEmpty()) {
            throw new IllegalArgumentException("流水缺少供应商编码");
        }
        if (line.getBizKey() == null || line.getBizKey().isEmpty()) {
            throw new IllegalArgumentException("流水缺少幂等键");
        }
        ensureAccount(line.getSupplierCode());
        BigDecimal prev = currentBalance(line.getSupplierCode(), line.getAccountType());
        BigDecimal balanceAfter = prev.add(line.getIncreaseAmount()).subtract(line.getDecreaseAmount());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime occurred = line.getOccurredAt() == null ? now : line.getOccurredAt();
        LocalDate postDate = line.getPostDate() == null ? occurred.toLocalDate() : line.getPostDate();
        String operator = line.getOperatorName() == null ? TmsUtil.currentUser() : line.getOperatorName();
        jdbc.update("INSERT INTO fin_supplier_account_flow (flow_id,supplier_code,supplier_name,account_type,"
                        + "biz_type,increase_amount,decrease_amount,balance_after,settle_status,settled_amount,"
                        + "ap_no,factory_expense_no,source_bill,payment_no,writeoff_no,reconcile_id,receipt_no,"
                        + "post_date,occurred_at,summary,operator_name,is_red,reverse_status,biz_key,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                TmsUtil.uuid("SAF"), line.getSupplierCode(), line.getSupplierName(), line.getAccountType(),
                line.getBizType(), line.getIncreaseAmount(), line.getDecreaseAmount(), balanceAfter,
                line.getSettleStatus(), line.getSettledAmount(), line.getApNo(), line.getFactoryExpenseNo(),
                line.getSourceBill(), line.getPaymentNo(), line.getWriteoffNo(), line.getReconcileId(),
                line.getReceiptNo(), java.sql.Date.valueOf(postDate), Timestamp.valueOf(occurred),
                line.getSummary(), operator, line.isRed() ? "Y" : "N",
                SupplierAccountConst.REVERSE_NORMAL, line.getBizKey());
        // 在线流水始终追加在链尾（occurred_at=当前时刻），同步账户余额缓存列
        String balanceColumn = switch (line.getAccountType()) {
            case SupplierAccountConst.ACCOUNT_PREPAY -> "prepay_balance";
            case SupplierAccountConst.ACCOUNT_EXPENSE -> "expense_balance";
            default -> "ap_balance";
        };
        jdbc.update("UPDATE fin_supplier_account SET " + balanceColumn
                + "=?, updated_at=CURRENT_TIMESTAMP WHERE supplier_code=?", balanceAfter,
                line.getSupplierCode());
    }

    /**
     * 重排某供应商某账户的余额链（按发生时刻+形成优先），返回末笔余额。
     * 用于数据修复与反审核冲回后的链修复。
     */
    @Transactional
    public BigDecimal rebuildChain(String supplierCode, String accountType) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT flow_id, increase_amount, decrease_amount FROM fin_supplier_account_flow "
                        + "WHERE supplier_code=? AND account_type=? ORDER BY " + CHAIN_ORDER,
                supplierCode, accountType);
        BigDecimal balance = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            balance = balance.add(nz(TmsUtil.toBd(row.get("increaseAmount"))))
                    .subtract(nz(TmsUtil.toBd(row.get("decreaseAmount"))));
            jdbc.update("UPDATE fin_supplier_account_flow SET balance_after=? WHERE flow_id=?",
                    balance, row.get("flowId"));
        }
        return balance;
    }

    /** 账户实时余额（末笔滚存；无流水为 0）。 */
    public BigDecimal currentBalance(String supplierCode, String accountType) {
        List<BigDecimal> last = jdbc.query(
                "SELECT balance_after FROM fin_supplier_account_flow"
                        + " WHERE supplier_code=? AND account_type=? ORDER BY " + CHAIN_ORDER_DESC + " LIMIT 1",
                (rs, i) -> rs.getBigDecimal("balance_after"), supplierCode, accountType);
        return last.isEmpty() || last.get(0) == null ? BigDecimal.ZERO : last.get(0);
    }

    // ==================== M2：预付付款/退款（付款单审核联动） ====================

    /** 查供应商当前预付余额（账户不存在视为 0）。供付款单抽屉退款时提示余额。 */
    public BigDecimal getPrepayBalance(String supplierCode) {
        if (supplierCode == null || supplierCode.isBlank()) return BigDecimal.ZERO;
        List<BigDecimal> vals = jdbc.queryForList(
                "SELECT prepay_balance FROM fin_supplier_account WHERE supplier_code = ?",
                BigDecimal.class, supplierCode);
        return vals.isEmpty() ? BigDecimal.ZERO : nz(vals.get(0));
    }

    /** 按供应商编码/名称查预付余额：编码为空时先按名称解析编码（口径同在线流水归属）。 */
    public BigDecimal getPrepayBalanceByName(String supplierCode, String supplierName) {
        String code = supplierCode == null ? "" : supplierCode.trim();
        if (code.isEmpty() && supplierName != null && !supplierName.isBlank()) {
            code = resolveCodeByName(supplierName.trim());
        }
        return getPrepayBalance(code);
    }

    /**
     * 预付付款单审核：写 PREPAY_PAYMENT（increase，预付余额 +）。
     * 调用方须与资金 OUT / 往来台账同事务。
     *
     * @param postDate 记账日期（付款单日期，决定 post_date，不决定 occurred_at）
     */
    public void postPrepayPayment(String paymentNo, LocalDate postDate, String supplierCode,
                                  String supplierName, BigDecimal amount, String summary) {
        if (supplierCode == null || supplierCode.isEmpty()) {
            throw new IllegalArgumentException("预付付款必须选择供应商");
        }
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        int round = paymentRound(paymentNo) + 1;
        SupplierAccountFlowLine line = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_PREPAY, SupplierAccountConst.PREPAY_PAYMENT,
                supplierCode, supplierName, "PP:" + paymentNo + "#" + round);
        line.setIncreaseAmount(nz(amount));
        line.setPaymentNo(paymentNo);
        line.setReceiptNo(paymentNo);
        line.setPostDate(postDate);
        line.setSummary("预付付款 " + paymentNo + (summary == null || summary.isEmpty() ? "" : " " + summary));
        writeFlow(line);
    }

    /**
     * 预付退款单审核（供应商退回预付）：写 PREPAY_REFUND（decrease，预付余额 −）。
     * 退款金额不得超过当前预付余额（已核销的预付不能退）。
     */
    public void postPrepayRefund(String paymentNo, LocalDate postDate, String supplierCode,
                                 String supplierName, BigDecimal amount, String summary) {
        if (supplierCode == null || supplierCode.isEmpty()) {
            throw new IllegalArgumentException("预付退款必须选择供应商");
        }
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        BigDecimal bal = currentBalance(supplierCode, SupplierAccountConst.ACCOUNT_PREPAY);
        if (nz(amount).compareTo(bal) > 0) {
            throw new IllegalArgumentException("预付余额不足，当前预付余额 " + bal + " 元，无法退款 " + amount + " 元");
        }
        int round = paymentRound(paymentNo) + 1;
        SupplierAccountFlowLine line = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_PREPAY, SupplierAccountConst.PREPAY_REFUND,
                supplierCode, supplierName, "PP:" + paymentNo + "#" + round);
        line.setDecreaseAmount(nz(amount));
        line.setPaymentNo(paymentNo);
        line.setReceiptNo(paymentNo);
        line.setPostDate(postDate);
        line.setSummary("预付退款 " + paymentNo + (summary == null || summary.isEmpty() ? "" : " " + summary));
        writeFlow(line);
    }

    /**
     * 预付付款单反审核：追加 PREPAY_REVERSE（decrease）冲回行，原付款行置 REVERSED。
     * 已被核销/退款使用（当前余额小于原付款额）时拒绝，要求先反审核下游 FX 单/退款单。
     */
    public void reversePrepayPayment(String paymentNo, LocalDate postDate, String supplierCode,
                                     String supplierName, BigDecimal amount, String summary) {
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        BigDecimal bal = currentBalance(supplierCode, SupplierAccountConst.ACCOUNT_PREPAY);
        if (nz(amount).compareTo(bal) > 0) {
            throw new IllegalArgumentException("该预付款已被预付核销或退款使用（当前预付余额 " + bal
                    + " 元），请先反审核相关预付核销单或预付退款单");
        }
        appendPrepayReverseLine(paymentNo, postDate, supplierCode, supplierName, amount,
                false, summary);
    }

    /** 预付退款单反审核：追加 PREPAY_REVERSE（increase）把预付加回来，原退款行置 REVERSED。 */
    public void reversePrepayRefund(String paymentNo, LocalDate postDate, String supplierCode,
                                    String supplierName, BigDecimal amount, String summary) {
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        appendPrepayReverseLine(paymentNo, postDate, supplierCode, supplierName, amount,
                true, summary);
    }

    /**
     * 预付冲回行落账：round 取该付款单已有正向预付行数（付款/退款），冲回行与被冲回行同轮次配对，
     * 支持「反审核 → 修改 → 再审核」多轮（每轮 bizKey 带 #轮次，不违反唯一键）。
     */
    private void appendPrepayReverseLine(String paymentNo, LocalDate postDate, String supplierCode,
                                         String supplierName, BigDecimal amount, boolean refundForward,
                                         String summary) {
        int round = paymentRound(paymentNo);
        if (round <= 0) {
            // 找不到正向行（历史脏数据）：不静默，提示先走数据修复
            throw new IllegalArgumentException("未找到付款单 " + paymentNo + " 的预付流水，请先执行供应商账户数据修复");
        }
        jdbc.update("UPDATE fin_supplier_account_flow SET reverse_status=? WHERE biz_key=?",
                SupplierAccountConst.REVERSE_REVERSED, "PP:" + paymentNo + "#" + round);
        SupplierAccountFlowLine line = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_PREPAY, SupplierAccountConst.PREPAY_REVERSE,
                supplierCode, supplierName, "PPR:" + paymentNo + "#" + round);
        if (refundForward) {
            line.setIncreaseAmount(nz(amount));
        } else {
            line.setDecreaseAmount(nz(amount));
        }
        line.setPaymentNo(paymentNo);
        line.setReceiptNo(paymentNo);
        line.setPostDate(postDate);
        line.setRed(true);
        line.setSummary((refundForward ? "预付退款冲回 " : "预付款冲回 ") + paymentNo + "（取消审核）"
                + (summary == null || summary.isEmpty() ? "" : " " + summary));
        writeFlow(line);
    }

    /** 该付款单已发生的预付正向轮次（PREPAY_PAYMENT/PREPAY_REFUND 行数）。 */
    private int paymentRound(String paymentNo) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fin_supplier_account_flow "
                + "WHERE payment_no=? AND biz_type IN (?,?)", Integer.class, paymentNo,
                SupplierAccountConst.PREPAY_PAYMENT, SupplierAccountConst.PREPAY_REFUND);
        return n == null ? 0 : n;
    }

    // ==================== M2：期初预付（M4 期初模块调用，底座先建） ====================

    /**
     * 期初预付建账：写一笔 PREPAY_OPENING 增加流水（不造付款单、不动资金）。
     * 幂等键 PPO:&lt;过批号&gt;:&lt;序号&gt;，来源号 QCYF-&lt;过批号&gt;-&lt;序号&gt;；
     * post_date 取建账日。返回生成的 flow_id（反建账据此定位）。
     */
    @Transactional
    public String postPrepayOpening(String postNo, int seq, LocalDate postDate, String supplierCode,
                                    String supplierName, BigDecimal amount) {
        if (supplierCode == null || supplierCode.isEmpty()) {
            throw new IllegalArgumentException("期初预付必须选择供应商");
        }
        if (nz(amount).signum() <= 0) {
            throw new IllegalArgumentException("期初预付金额必须大于 0");
        }
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        String sourceBill = "QCYF-" + postNo + "-" + seq;
        SupplierAccountFlowLine line = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_PREPAY, SupplierAccountConst.PREPAY_OPENING,
                supplierCode, supplierName, "PPO:" + postNo + ":" + seq);
        line.setIncreaseAmount(nz(amount));
        line.setSourceBill(sourceBill);
        line.setPostDate(postDate);
        line.setSummary("期初预付 " + sourceBill);
        writeFlow(line);
        List<String> ids = jdbc.queryForList(
                "SELECT flow_id FROM fin_supplier_account_flow WHERE biz_key=?", String.class,
                "PPO:" + postNo + ":" + seq);
        return ids.get(0);
    }

    /**
     * 期初预付反建账守卫：该供应商在建账流水之后已发生预付核销/退款/费用转预付且仍生效时，
     * 返回中文拒绝原因，否则 null。只统计 reverse_status 非 REVERSED 的下游行（占用已解除的不拦）。
     */
    public String checkPrepayOpeningReversable(String supplierCode, String flowId) {
        Integer downstream = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_supplier_account_flow f "
                        + "WHERE f.supplier_code=? AND f.account_type='PREPAY' "
                        + "AND f.biz_type IN ('PREPAY_WRITE_OFF','PREPAY_REFUND','PREPAY_EXPENSE_TRANSFER') "
                        + "AND COALESCE(f.reverse_status,'') <> 'REVERSED' "
                        + "AND f.flow_id<>? AND f.occurred_at >= ("
                        + "SELECT occurred_at FROM fin_supplier_account_flow WHERE flow_id=?)",
                Integer.class, supplierCode, flowId, flowId);
        if (downstream != null && downstream > 0) {
            return "供应商「" + supplierCode + "」的期初预付已被预付核销、退款或费用转预付使用，不能反建账；"
                    + "请先反审核相关预付核销单/退款单/厂家费用兑现单";
        }
        return null;
    }

    /** 删除一笔期初预付流水，重排该供应商预付余额链并同步账户缓存列。 */
    @Transactional
    public void deletePrepayOpening(String flowId, String supplierCode) {
        jdbc.update("DELETE FROM fin_supplier_account_flow WHERE flow_id=? AND biz_type=?",
                flowId, SupplierAccountConst.PREPAY_OPENING);
        BigDecimal end = rebuildChain(supplierCode, SupplierAccountConst.ACCOUNT_PREPAY);
        jdbc.update("UPDATE fin_supplier_account SET prepay_balance=?, updated_at=CURRENT_TIMESTAMP "
                + "WHERE supplier_code=?", end, supplierCode);
    }

    // ==================== M4：期初应付（与 QCYF 期初预付对称，ApInitService 调用） ====================

    /**
     * 期初应付建账：写一笔 AP_OPENING 形成流水（不造付款单、不动资金、不发 GL 事件）。
     * 幂等键 {@code AP:<apNo>}，与 {@link #repair} 补缺完全同源（repair 见此键即跳过）；
     * post_date 取建账日，ap_no/source_bill 回写流水供预付核销落账与链修复定位。返回 flow_id。
     */
    @Transactional
    public String postApOpening(String apNo, String sourceBill, LocalDate postDate, String supplierCode,
                                String supplierName, BigDecimal amount) {
        if (supplierCode == null || supplierCode.isEmpty()) {
            throw new IllegalArgumentException("期初应付必须选择供应商");
        }
        if (nz(amount).signum() <= 0) {
            throw new IllegalArgumentException("期初应付金额必须大于 0");
        }
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        String bizKey = "AP:" + apNo;
        SupplierAccountFlowLine line = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_AP, SupplierAccountConst.AP_OPENING,
                supplierCode, supplierName, bizKey);
        line.setIncreaseAmount(nz(amount));
        line.setApNo(apNo);
        line.setSourceBill(sourceBill);
        line.setSettleStatus(SupplierAccountConst.SETTLE_UNSETTLED);
        line.setSettledAmount(BigDecimal.ZERO);
        line.setPostDate(postDate);
        line.setSummary("期初应付 " + apNo);
        writeFlow(line);
        List<String> ids = jdbc.queryForList(
                "SELECT flow_id FROM fin_supplier_account_flow WHERE biz_key=?", String.class, bizKey);
        return ids.get(0);
    }

    /** 删除某张期初应付单的形成流水，重排该供应商应付余额链并同步账户缓存列。 */
    @Transactional
    public void deleteApOpening(String apNo, String supplierCode) {
        jdbc.update("DELETE FROM fin_supplier_account_flow WHERE biz_type=? AND biz_key=?",
                SupplierAccountConst.AP_OPENING, "AP:" + apNo);
        BigDecimal end = rebuildChain(supplierCode, SupplierAccountConst.ACCOUNT_AP);
        jdbc.update("UPDATE fin_supplier_account SET ap_balance=?, updated_at=CURRENT_TIMESTAMP "
                + "WHERE supplier_code=?", end, supplierCode);
    }

    // ==================== M2：预付核销（FX 单联动） ====================

    /** FX 核销单行落账参数：每行一笔应付的预付核销额。 */
    public static final class WriteoffLine {
        public String apNo;
        public String sourceBill;
        /** 本行核销额（>0）。 */
        public BigDecimal amount;
        /** 核销后该 AP 累计已付（回写形成行 settled_amount）。 */
        public BigDecimal paidAfter;
        /** 核销后形成行结算标志：部分结算/已结算。 */
        public String settleStatusAfter;
        /** 对应的核销真值记录 id（fin_reconcile_record.record_id）。 */
        public String reconcileId;

        public WriteoffLine() {
        }

        public WriteoffLine(String apNo, String sourceBill, BigDecimal amount,
                            BigDecimal paidAfter, String settleStatusAfter, String reconcileId) {
            this.apNo = apNo;
            this.sourceBill = sourceBill;
            this.amount = amount;
            this.paidAfter = paidAfter;
            this.settleStatusAfter = settleStatusAfter;
            this.reconcileId = reconcileId;
        }
    }

    /**
     * FX 预付核销单审核落账（一个事务，由 PrepayWriteoffService 调）：
     * 预付侧写 1 行 PREPAY_WRITE_OFF（decrease=合计），应付侧每行写 AP_SETTLE_PREPAY（decrease），
     * 并按核销后的 fin_ap 真值回写形成行结算标志/已结额。预付余额不足直接中文报错，整单回滚。
     */
    @Transactional
    public void postPrepayWriteoff(String writeoffNo, LocalDate postDate, String supplierCode,
                                   String supplierName, BigDecimal total, List<WriteoffLine> lines,
                                   String remark) {
        if (supplierCode == null || supplierCode.isEmpty()) {
            throw new IllegalArgumentException("预付核销必须选择供应商");
        }
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        BigDecimal bal = currentBalance(supplierCode, SupplierAccountConst.ACCOUNT_PREPAY);
        if (nz(total).compareTo(bal) > 0) {
            throw new IllegalArgumentException("预付余额不足，当前预付余额 " + bal + " 元，无法核销 " + total + " 元");
        }
        int round = writeoffRound(writeoffNo) + 1;
        String tail = remark == null || remark.isEmpty() ? "" : " " + remark;

        // 预付侧：一行 PREPAY_WRITE_OFF（decrease=合计）
        SupplierAccountFlowLine prepayLine = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_PREPAY, SupplierAccountConst.PREPAY_WRITE_OFF,
                supplierCode, supplierName, "FX:" + writeoffNo + "#" + round);
        prepayLine.setDecreaseAmount(nz(total));
        prepayLine.setWriteoffNo(writeoffNo);
        prepayLine.setPostDate(postDate);
        prepayLine.setSummary("预付核销 " + writeoffNo + tail);
        writeFlow(prepayLine);

        // 应付侧：每行 AP_SETTLE_PREPAY，并回写形成行结算标志
        for (WriteoffLine l : lines) {
            SupplierAccountFlowLine apLine = new SupplierAccountFlowLine(
                    SupplierAccountConst.ACCOUNT_AP, SupplierAccountConst.AP_SETTLE_PREPAY,
                    supplierCode, supplierName,
                    "FXAP:" + writeoffNo + ":" + l.apNo + "#" + round);
            apLine.setDecreaseAmount(nz(l.amount));
            apLine.setApNo(l.apNo);
            apLine.setSourceBill(l.sourceBill);
            apLine.setWriteoffNo(writeoffNo);
            apLine.setReconcileId(l.reconcileId);
            apLine.setPostDate(postDate);
            apLine.setSummary("预付冲应付 " + (l.sourceBill == null || l.sourceBill.isEmpty()
                    ? l.apNo : l.sourceBill));
            writeFlow(apLine);
            if (l.settleStatusAfter != null) {
                writeBackApFormingStatus(l.apNo, nz(l.paidAfter), l.settleStatusAfter);
            }
        }
    }

    /**
     * FX 单反审核冲回：原正向行置 REVERSED，追加红字 PREPAY_REVERSE（increase=合计，预付加回）
     * 与每行 AP_REVERSE（increase，应付加回），形成行结算标志按 fin_ap 当前真值复位。
     * 调用方须先删除核销真值记录、回退 fin_ap，再调本方法。
     */
    @Transactional
    public void reversePrepayWriteoff(String writeoffNo, LocalDate postDate, String supplierCode,
                                      String supplierName, BigDecimal total, List<WriteoffLine> lines) {
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        int round = writeoffRound(writeoffNo);
        if (round <= 0) {
            throw new IllegalArgumentException("未找到预付核销单 " + writeoffNo + " 的账户流水，请先执行供应商账户数据修复");
        }
        // 预付侧：原 PREPAY_WRITE_OFF 置 REVERSED，追加红字 PREPAY_REVERSE（increase）
        jdbc.update("UPDATE fin_supplier_account_flow SET reverse_status=? WHERE biz_key=?",
                SupplierAccountConst.REVERSE_REVERSED, "FX:" + writeoffNo + "#" + round);
        SupplierAccountFlowLine prepayRev = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_PREPAY, SupplierAccountConst.PREPAY_REVERSE,
                supplierCode, supplierName, "FXREV:" + writeoffNo + "#" + round);
        prepayRev.setIncreaseAmount(nz(total));
        prepayRev.setWriteoffNo(writeoffNo);
        prepayRev.setPostDate(postDate);
        prepayRev.setRed(true);
        prepayRev.setSummary("预付核销冲回 " + writeoffNo + "（取消审核）");
        writeFlow(prepayRev);

        // 应付侧：逐行原 AP_SETTLE_PREPAY 置 REVERSED，追加红字 AP_REVERSE（increase）
        for (WriteoffLine l : lines) {
            jdbc.update("UPDATE fin_supplier_account_flow SET reverse_status=? WHERE biz_key=?",
                    SupplierAccountConst.REVERSE_REVERSED,
                    "FXAP:" + writeoffNo + ":" + l.apNo + "#" + round);
            SupplierAccountFlowLine apRev = new SupplierAccountFlowLine(
                    SupplierAccountConst.ACCOUNT_AP, SupplierAccountConst.AP_REVERSE,
                    supplierCode, supplierName,
                    "FXAPREV:" + writeoffNo + ":" + l.apNo + "#" + round);
            apRev.setIncreaseAmount(nz(l.amount));
            apRev.setApNo(l.apNo);
            apRev.setSourceBill(l.sourceBill);
            apRev.setWriteoffNo(writeoffNo);
            apRev.setPostDate(postDate);
            apRev.setRed(true);
            apRev.setSummary("预付冲应付冲回 " + (l.sourceBill == null || l.sourceBill.isEmpty()
                    ? l.apNo : l.sourceBill) + "（取消审核）");
            writeFlow(apRev);
            resetApFormingStatusByAp(l.apNo);
        }
    }

    /** 该 FX 单已发生的正向核销轮次（PREPAY_WRITE_OFF 行数）。 */
    private int writeoffRound(String writeoffNo) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fin_supplier_account_flow "
                + "WHERE writeoff_no=? AND biz_type=?", Integer.class,
                writeoffNo, SupplierAccountConst.PREPAY_WRITE_OFF);
        return n == null ? 0 : n;
    }

    // ==================== M2：应付现金结算在线流水 ====================

    /** 现金/银行结算 AP 行参数（对应一笔 fin_reconcile_record 核销记录）。 */
    public static final class ApCashLine {
        /** 核销真值记录 id，同时作为流水幂等键基准（APR:&lt;recordId&gt;，与数据修复补缺同源）。 */
        public String recordId;
        public String apNo;
        public String sourceBill;
        /** 本行现金结算额（>0）。 */
        public BigDecimal amount;
        /** 结算单号（付款单 FK / 供应商退款收款单 SK），同时落 payment_no、receipt_no 两列。 */
        public String paymentNo;

        public ApCashLine() {
        }

        public ApCashLine(String recordId, String apNo, String sourceBill,
                          BigDecimal amount, String paymentNo) {
            this.recordId = recordId;
            this.apNo = apNo;
            this.sourceBill = sourceBill;
            this.amount = amount;
            this.paymentNo = paymentNo;
        }
    }

    /**
     * 应付结算在线落 AP 结算流水（现金/银行部分），逐核销记录一行 AP_SETTLE_CASH（decrease）。
     * bizKey 用 {@code APR:<recordId>}，与 {@link #repair} 补缺键完全同源，修复自动幂等跳过；
     * 同时按 fin_ap 真值回写形成行结算标志。调用方须已写核销记录、已更新 fin_ap。
     */
    @Transactional
    public void postApCashSettle(LocalDate postDate, String supplierCode, String supplierName,
                                 List<ApCashLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        for (ApCashLine l : lines) {
            if (l == null || nz(l.amount).signum() == 0) {
                continue;
            }
            String sourceBill = l.sourceBill == null ? "" : l.sourceBill;
            if (sourceBill.isEmpty()) {
                sourceBill = queryOneString("SELECT source_bill FROM fin_ap WHERE ap_no=?", l.apNo);
            }
            SupplierAccountFlowLine line = new SupplierAccountFlowLine(
                    SupplierAccountConst.ACCOUNT_AP, SupplierAccountConst.AP_SETTLE_CASH,
                    supplierCode, supplierName, "APR:" + l.recordId);
            line.setDecreaseAmount(nz(l.amount));
            line.setApNo(l.apNo);
            line.setSourceBill(sourceBill);
            line.setPaymentNo(l.paymentNo);
            line.setReceiptNo(l.paymentNo);
            line.setReconcileId(l.recordId);
            line.setPostDate(postDate);
            line.setSummary("付款结算 " + (l.paymentNo == null || l.paymentNo.isEmpty() ? l.apNo : l.paymentNo));
            writeFlow(line);
            resetApFormingStatusByAp(l.apNo);
        }
    }

    /**
     * 付款单反审核冲回现金结算流水：原 AP_SETTLE_CASH 置 REVERSED，逐行追加红字 AP_REVERSE
     * （increase，应付加回），形成行结算标志按 fin_ap 当前真值复位。
     * 调用方须先删除核销真值记录、回退 fin_ap，再调本方法。
     * M2 之前的历史付款单无在线流水，按 bizKey 找不到 NORMAL 行时跳过（由数据修复兜底）。
     */
    @Transactional
    public void reverseApCashSettle(LocalDate postDate, String supplierCode, String supplierName,
                                    List<ApCashLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        for (ApCashLine l : lines) {
            if (l == null || nz(l.amount).signum() == 0) {
                continue;
            }
            String bizKey = "APR:" + l.recordId;
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM fin_supplier_account_flow "
                    + "WHERE biz_key=? AND reverse_status=?", Integer.class, bizKey,
                    SupplierAccountConst.REVERSE_NORMAL);
            if (exists == null || exists == 0) {
                // 已冲回或在线流水缺失（历史单走数据修复）：跳过，避免重复红字
                continue;
            }
            jdbc.update("UPDATE fin_supplier_account_flow SET reverse_status=? WHERE biz_key=?",
                    SupplierAccountConst.REVERSE_REVERSED, bizKey);
            String sourceBill = l.sourceBill == null ? "" : l.sourceBill;
            if (sourceBill.isEmpty()) {
                sourceBill = queryOneString("SELECT source_bill FROM fin_ap WHERE ap_no=?", l.apNo);
            }
            SupplierAccountFlowLine red = new SupplierAccountFlowLine(
                    SupplierAccountConst.ACCOUNT_AP, SupplierAccountConst.AP_REVERSE,
                    supplierCode, supplierName, "APRR:" + l.recordId);
            red.setIncreaseAmount(nz(l.amount));
            red.setApNo(l.apNo);
            red.setSourceBill(sourceBill);
            red.setPaymentNo(l.paymentNo);
            red.setReceiptNo(l.paymentNo);
            red.setPostDate(postDate);
            red.setRed(true);
            red.setSummary("付款结算冲回 " + (l.paymentNo == null || l.paymentNo.isEmpty()
                    ? l.apNo : l.paymentNo) + "（取消审核）");
            writeFlow(red);
            resetApFormingStatusByAp(l.apNo);
        }
    }

    /** 回写某应付形成行的结算标志/已结额（核销后）。 */
    private void writeBackApFormingStatus(String apNo, BigDecimal paidAfter, String settleStatusAfter) {
        jdbc.update("UPDATE fin_supplier_account_flow SET settled_amount=?, settle_status=? "
                        + "WHERE ap_no=? AND account_type='AP' AND biz_type IN "
                        + "('AP_RECEIPT','AP_RETURN','AP_OPENING')",
                paidAfter, settleStatusAfter, apNo);
    }

    /** 按 fin_ap 当前真值复位形成行结算标志（反审核后）。 */
    private void resetApFormingStatusByAp(String apNo) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT ap_amount, paid_amount, unpaid_amount FROM fin_ap WHERE ap_no=?", apNo);
        if (rows.isEmpty()) {
            // AP 行本身已被撤销（形成流水同生命周期删除的场景），标志行可能已不在
            return;
        }
        Map<String, Object> ap = rows.get(0);
        BigDecimal paid = nz(TmsUtil.toBd(ap.get("paidAmount")));
        BigDecimal unpaid = nz(TmsUtil.toBd(ap.get("unpaidAmount")));
        String status;
        if (unpaid.signum() == 0) {
            status = SupplierAccountConst.SETTLE_DONE;
        } else if (paid.signum() == 0) {
            status = SupplierAccountConst.SETTLE_UNSETTLED;
        } else {
            status = SupplierAccountConst.SETTLE_PART;
        }
        writeBackApFormingStatus(apNo, paid, status);
    }

    // ==================== M3：厂家费用单 JF 形成/红字/反审核 ====================

    /**
     * JF 审核落账：每张 JF 一行费用形成汇总流水（FACTORY_EXP_FORM）。
     * 普通单 signedAmount 为正；红字单（isRed）传负数、biz_type=FACTORY_EXP_RED、is_red='Y'，
     * 直接冲减费用余额（同 PUR_RETURN 红字范式，不另做反向事件）。
     * 幂等键 {@code FEX:<jfNo>#<round>}，支持「反审核→修改→再审核」多轮。
     */
    @Transactional
    public void postFactoryExpenseForm(String jfNo, LocalDate postDate, String supplierCode,
                                       String supplierName, BigDecimal signedAmount, boolean isRed) {
        if (supplierCode == null || supplierCode.isEmpty()) {
            throw new IllegalArgumentException("厂家费用单必须选择供应商");
        }
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        int round = fexpRound(jfNo) + 1;
        SupplierAccountFlowLine line = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_EXPENSE,
                isRed ? SupplierAccountConst.FACTORY_EXP_RED : SupplierAccountConst.FACTORY_EXP_FORM,
                supplierCode, supplierName, "FEX:" + jfNo + "#" + round);
        line.setIncreaseAmount(nz(signedAmount));
        line.setFactoryExpenseNo(jfNo);
        line.setPostDate(postDate);
        line.setRed(isRed);
        // 红字行可能为负 increase；settle_status/settled_amount 只在正数形成行上有意义
        if (nz(signedAmount).signum() >= 0) {
            line.setSettleStatus(SupplierAccountConst.CLAIM_UNCLAIMED);
            line.setSettledAmount(BigDecimal.ZERO);
        }
        line.setSummary((isRed ? "厂家费用红字 " : "厂家费用 ") + jfNo);
        writeFlow(line);
    }

    /**
     * JF 反审核冲回：原形成行（FORM/RED）置 REVERSED，追加一行 FACTORY_EXP_REVERSE
     * （金额与原行相反），支持多轮（{@code FEXREV:<jfNo>#<round>}）。
     * 调用方须先校验该 JF 没有仍生效的 DX 兑现（AC-13）。
     */
    @Transactional
    public void reverseFactoryExpense(String jfNo, LocalDate postDate, String supplierCode,
                                      String supplierName, BigDecimal signedAmount, boolean isRed) {
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        int round = fexpRound(jfNo);
        if (round <= 0) {
            throw new IllegalArgumentException("未找到厂家费用单 " + jfNo + " 的账户流水，请先执行供应商账户数据修复");
        }
        jdbc.update("UPDATE fin_supplier_account_flow SET reverse_status=? WHERE biz_key=?",
                SupplierAccountConst.REVERSE_REVERSED, "FEX:" + jfNo + "#" + round);
        SupplierAccountFlowLine rev = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_EXPENSE, SupplierAccountConst.FACTORY_EXP_REVERSE,
                supplierCode, supplierName, "FEXREV:" + jfNo + "#" + round);
        rev.setIncreaseAmount(nz(signedAmount).negate());
        rev.setFactoryExpenseNo(jfNo);
        rev.setPostDate(postDate);
        rev.setRed(true);
        rev.setSummary("厂家费用冲回 " + jfNo + "（取消审核）");
        writeFlow(rev);
    }

    /** 该 JF 已发生的形成轮次（FACTORY_EXP_FORM/FACTORY_EXP_RED 行数）。 */
    private int fexpRound(String jfNo) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fin_supplier_account_flow "
                + "WHERE factory_expense_no=? AND biz_type IN (?,?)", Integer.class, jfNo,
                SupplierAccountConst.FACTORY_EXP_FORM, SupplierAccountConst.FACTORY_EXP_RED);
        return n == null ? 0 : n;
    }

    /**
     * 回写某 JF 形成行的兑现标志/已兑现金额（DX 审核后），并同步 JF 主单三金额状态。
     * 只更新非冲回的正向形成行（红字行不挂兑现标志）。
     */
    @Transactional
    public void writeBackClaimStatus(String jfNo, BigDecimal settledAmount, String claimStatus) {
        jdbc.update("UPDATE fin_supplier_account_flow SET settle_status=?, settled_amount=? "
                        + "WHERE factory_expense_no=? AND account_type='EXPENSE' "
                        + "AND biz_type IN (?,?) AND COALESCE(reverse_status,'')='NORMAL'",
                claimStatus, nz(settledAmount), jfNo,
                SupplierAccountConst.FACTORY_EXP_FORM, SupplierAccountConst.FACTORY_EXP_RED);
    }

    // ==================== M3：厂家费用兑现单 DX（现金/冲应付/其他） ====================

    /** DX-OFFSET 每行应付冲销参数。 */
    public static final class ExpOffsetLine {
        public String apNo;
        public String sourceBill;
        public BigDecimal amount;
        /** 冲销后 AP 累计已付（回写形成行）。 */
        public BigDecimal paidAfter;
        public String settleStatusAfter;
        /** 核销真值记录 id（fin_reconcile_record.record_id）。 */
        public String reconcileId;

        public ExpOffsetLine() {
        }
    }

    /** DX 兑现落账轮次：按 receipt_no=DX 号统计三种兑现流水行数。 */
    private int dxRound(String dxNo) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fin_supplier_account_flow "
                + "WHERE receipt_no=? AND biz_type IN (?,?,?)", Integer.class, dxNo,
                SupplierAccountConst.FACTORY_EXP_SETTLE_CASH,
                SupplierAccountConst.FACTORY_EXP_SETTLE_OFFSET,
                SupplierAccountConst.FACTORY_EXP_SETTLE_OTHER);
        return n == null ? 0 : n;
    }

    /**
     * DX-CASH 审核：EXPENSE 账户一行 FACTORY_EXP_SETTLE_CASH（decrease=合计），
     * 幂等键 {@code DXC:<dxNo>#<round>}；资金/往来台账由 FactorySettleService 写。
     */
    @Transactional
    public void postFactorySettleCash(String dxNo, LocalDate postDate, String supplierCode,
                                      String supplierName, BigDecimal total, String remark) {
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        int round = dxRound(dxNo) + 1;
        SupplierAccountFlowLine line = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_EXPENSE, SupplierAccountConst.FACTORY_EXP_SETTLE_CASH,
                supplierCode, supplierName, "DXC:" + dxNo + "#" + round);
        line.setDecreaseAmount(nz(total));
        line.setPaymentNo(dxNo);
        line.setReceiptNo(dxNo);
        line.setPostDate(postDate);
        line.setSummary("费用现金兑现 " + dxNo + (remark == null || remark.isEmpty() ? "" : " " + remark));
        writeFlow(line);
    }

    /**
     * DX-OFFSET 审核：EXPENSE 一行 FACTORY_EXP_SETTLE_OFFSET（decrease=合计，{@code DXO:}），
     * AP 账户逐行 AP_SETTLE_EXPENSE（decrease，{@code DXAP:<dxNo>:<apNo>#<round>}）并回写形成行标志。
     */
    @Transactional
    public void postFactorySettleOffset(String dxNo, LocalDate postDate, String supplierCode,
                                        String supplierName, BigDecimal total, List<ExpOffsetLine> lines) {
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        int round = dxRound(dxNo) + 1;
        SupplierAccountFlowLine expLine = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_EXPENSE, SupplierAccountConst.FACTORY_EXP_SETTLE_OFFSET,
                supplierCode, supplierName, "DXO:" + dxNo + "#" + round);
        expLine.setDecreaseAmount(nz(total));
        expLine.setPaymentNo(dxNo);
        expLine.setReceiptNo(dxNo);
        expLine.setPostDate(postDate);
        expLine.setSummary("费用冲应付 " + dxNo);
        writeFlow(expLine);

        for (ExpOffsetLine l : lines) {
            SupplierAccountFlowLine apLine = new SupplierAccountFlowLine(
                    SupplierAccountConst.ACCOUNT_AP, SupplierAccountConst.AP_SETTLE_EXPENSE,
                    supplierCode, supplierName,
                    "DXAP:" + dxNo + ":" + l.apNo + "#" + round);
            apLine.setDecreaseAmount(nz(l.amount));
            apLine.setApNo(l.apNo);
            apLine.setSourceBill(l.sourceBill);
            apLine.setPaymentNo(dxNo);
            apLine.setReceiptNo(dxNo);
            apLine.setReconcileId(l.reconcileId);
            apLine.setPostDate(postDate);
            apLine.setSummary("厂家费用账扣 " + (l.sourceBill == null || l.sourceBill.isEmpty()
                    ? l.apNo : l.sourceBill));
            writeFlow(apLine);
            if (l.settleStatusAfter != null) {
                writeBackApFormingStatus(l.apNo, nz(l.paidAfter), l.settleStatusAfter);
            }
        }
    }

    /**
     * DX-OTHER 审核：EXPENSE 一行 FACTORY_EXP_SETTLE_OTHER（decrease=合计，{@code DXOT:}）；
     * 对方科目为 1123（费用转预付）时另写 PREPAY 增 PREPAY_EXPENSE_TRANSFER（{@code DXP:}）。
     */
    @Transactional
    public void postFactorySettleOther(String dxNo, LocalDate postDate, String supplierCode,
                                       String supplierName, BigDecimal total, boolean toPrepay,
                                       String contraSubjectName) {
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        int round = dxRound(dxNo) + 1;
        SupplierAccountFlowLine expLine = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_EXPENSE, SupplierAccountConst.FACTORY_EXP_SETTLE_OTHER,
                supplierCode, supplierName, "DXOT:" + dxNo + "#" + round);
        expLine.setDecreaseAmount(nz(total));
        expLine.setPaymentNo(dxNo);
        expLine.setReceiptNo(dxNo);
        expLine.setPostDate(postDate);
        expLine.setSummary("费用其他兑现 " + dxNo + "（" + contraSubjectName + "）");
        writeFlow(expLine);

        if (toPrepay) {
            SupplierAccountFlowLine prepayLine = new SupplierAccountFlowLine(
                    SupplierAccountConst.ACCOUNT_PREPAY, SupplierAccountConst.PREPAY_EXPENSE_TRANSFER,
                    supplierCode, supplierName, "DXP:" + dxNo + "#" + round);
            prepayLine.setIncreaseAmount(nz(total));
            prepayLine.setPaymentNo(dxNo);
            prepayLine.setReceiptNo(dxNo);
            prepayLine.setPostDate(postDate);
            prepayLine.setSummary("费用转预付 " + dxNo);
            writeFlow(prepayLine);
        }
    }

    /**
     * DX 反审核通用冲回（一单一方式，按原 settle_type 调一次）：
     * 原兑现行置 REVERSED 并追加红字冲回行；OFFSET 逐 AP 行回写形成行标志（按 fin_ap 真值复位）；
     * 1123 转预付的预付增行冲回，余额不足（已被 FX/退款使用）时中文拒绝，要求先反下游。
     */
    @Transactional
    public void reverseFactorySettle(String settleType, String dxNo, LocalDate postDate,
                                     String supplierCode, String supplierName, BigDecimal total,
                                     List<ExpOffsetLine> offsetLines) {
        ensureAccount(supplierCode);
        lockAccount(supplierCode);
        int round = dxRound(dxNo);
        if (round <= 0) {
            throw new IllegalArgumentException("未找到兑现单 " + dxNo + " 的账户流水，请先执行供应商账户数据修复");
        }
        switch (settleType) {
            case "CASH" -> {
                jdbc.update("UPDATE fin_supplier_account_flow SET reverse_status=? WHERE biz_key=?",
                        SupplierAccountConst.REVERSE_REVERSED, "DXC:" + dxNo + "#" + round);
                appendExpenseReverse("DXCREV:" + dxNo + "#" + round,
                        dxNo, postDate, supplierCode, supplierName, total, "费用现金兑现冲回 " + dxNo);
            }
            case "OFFSET" -> {
                jdbc.update("UPDATE fin_supplier_account_flow SET reverse_status=? WHERE biz_key=?",
                        SupplierAccountConst.REVERSE_REVERSED, "DXO:" + dxNo + "#" + round);
                appendExpenseReverse("DXOREV:" + dxNo + "#" + round,
                        dxNo, postDate, supplierCode, supplierName, total, "费用冲应付冲回 " + dxNo);
                for (ExpOffsetLine l : offsetLines) {
                    jdbc.update("UPDATE fin_supplier_account_flow SET reverse_status=? WHERE biz_key=?",
                            SupplierAccountConst.REVERSE_REVERSED,
                            "DXAP:" + dxNo + ":" + l.apNo + "#" + round);
                    SupplierAccountFlowLine apRev = new SupplierAccountFlowLine(
                            SupplierAccountConst.ACCOUNT_AP, SupplierAccountConst.AP_REVERSE,
                            supplierCode, supplierName,
                            "DXAPREV:" + dxNo + ":" + l.apNo + "#" + round);
                    apRev.setIncreaseAmount(nz(l.amount));
                    apRev.setApNo(l.apNo);
                    apRev.setSourceBill(l.sourceBill);
                    apRev.setPaymentNo(dxNo);
                    apRev.setReceiptNo(dxNo);
                    apRev.setPostDate(postDate);
                    apRev.setRed(true);
                    apRev.setSummary("厂家费用账扣冲回 " + (l.sourceBill == null || l.sourceBill.isEmpty()
                            ? l.apNo : l.sourceBill) + "（取消审核）");
                    writeFlow(apRev);
                    resetApFormingStatusByAp(l.apNo);
                }
            }
            case "OTHER" -> {
                boolean toPrepay = jdbc.queryForObject("SELECT COUNT(*) FROM fin_supplier_account_flow "
                        + "WHERE biz_key=? AND reverse_status=?", Integer.class,
                        "DXP:" + dxNo + "#" + round, SupplierAccountConst.REVERSE_NORMAL) > 0;
                jdbc.update("UPDATE fin_supplier_account_flow SET reverse_status=? WHERE biz_key=?",
                        SupplierAccountConst.REVERSE_REVERSED, "DXOT:" + dxNo + "#" + round);
                appendExpenseReverse("DXOTREV:" + dxNo + "#" + round,
                        dxNo, postDate, supplierCode, supplierName, total, "费用其他兑现冲回 " + dxNo);
                if (toPrepay) {
                    BigDecimal prepayBal = currentBalance(supplierCode, SupplierAccountConst.ACCOUNT_PREPAY);
                    if (nz(total).compareTo(prepayBal) > 0) {
                        throw new IllegalArgumentException("该笔费用转预付已被预付核销或退款使用（当前预付余额 "
                                + prepayBal + " 元），请先反审核相关预付核销单或退款单");
                    }
                    jdbc.update("UPDATE fin_supplier_account_flow SET reverse_status=? WHERE biz_key=?",
                            SupplierAccountConst.REVERSE_REVERSED, "DXP:" + dxNo + "#" + round);
                    SupplierAccountFlowLine prepayRev = new SupplierAccountFlowLine(
                            SupplierAccountConst.ACCOUNT_PREPAY, SupplierAccountConst.PREPAY_REVERSE,
                            supplierCode, supplierName, "DXPREV:" + dxNo + "#" + round);
                    prepayRev.setDecreaseAmount(nz(total));
                    prepayRev.setPaymentNo(dxNo);
                    prepayRev.setReceiptNo(dxNo);
                    prepayRev.setPostDate(postDate);
                    prepayRev.setRed(true);
                    prepayRev.setSummary("费用转预付冲回 " + dxNo + "（取消审核）");
                    writeFlow(prepayRev);
                }
            }
            default -> throw new IllegalArgumentException("未知的兑现方式：" + settleType);
        }
    }

    /** 费用账户红字冲回行（increase，冲销原 decrease 兑现）。 */
    private void appendExpenseReverse(String bizKey, String dxNo, LocalDate postDate,
                                      String supplierCode, String supplierName,
                                      BigDecimal amount, String summary) {
        SupplierAccountFlowLine rev = new SupplierAccountFlowLine(
                SupplierAccountConst.ACCOUNT_EXPENSE, SupplierAccountConst.FACTORY_EXP_REVERSE,
                supplierCode, supplierName, bizKey);
        rev.setIncreaseAmount(nz(amount));
        rev.setPaymentNo(dxNo);
        rev.setReceiptNo(dxNo);
        rev.setPostDate(postDate);
        rev.setRed(true);
        rev.setSummary(summary + "（取消审核）");
        writeFlow(rev);
    }

    // ==================== 内部：供应商归属解析（口径同 V128 回填） ====================

    /** fin_ap 行 → 供应商编码：收货→退货→期初批号→档案名称→名称兜底。 */
    public String resolveApSupplierCode(Map<String, Object> ap) {
        String sourceBill = TmsUtil.str(ap.get("sourceBill"));
        String supplierName = TmsUtil.str(ap.get("supplier"));
        return resolveBySource(sourceBill, supplierName);
    }

    /** 按 ap_no 取应付行再解析（结算流水归属）。 */
    public String resolveApSupplierCodeByApNo(String apNo) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT source_bill, supplier FROM fin_ap WHERE ap_no=?", apNo);
        return rows.isEmpty() ? "" : resolveBySource(
                TmsUtil.str(rows.get(0).get("sourceBill")), TmsUtil.str(rows.get(0).get("supplier")));
    }

    /** 按名称解析供应商编码（口径同在线流水归属：档案优先，名称兜底）。 */
    public String resolveCodeByName(String supplierName) {
        if (supplierName == null || supplierName.isBlank()) return "";
        return resolveBySource("", supplierName.trim());
    }

    private String resolveBySource(String sourceBill, String supplierName) {
        String code = queryOneString(
                "SELECT supplier_code FROM pur_receipt WHERE receipt_no=?", sourceBill);
        if (code.isEmpty()) {
            code = queryOneString(
                    "SELECT supplier_code FROM pur_return WHERE return_no=?", sourceBill);
        }
        if (code.isEmpty()) {
            code = queryOneString(
                    "SELECT supplier_code FROM fin_ap_init WHERE generated_ap_no=?", sourceBill);
        }
        if (code.isEmpty()) {
            code = queryOneString(
                    "SELECT supplier_code FROM base_supplier WHERE supplier_name=?", supplierName);
        }
        return code.isEmpty() ? supplierName : code;
    }

    private String queryOneString(String sql, Object arg) {
        if (arg == null || arg.toString().isEmpty()) {
            return "";
        }
        List<String> list = jdbc.query(sql, (rs, i) -> rs.getString(1), arg);
        if (list.isEmpty() || list.get(0) == null) {
            return "";
        }
        return list.get(0).trim();
    }

    /** 修复补缺：从 fin_ap 行插入形成流水（口径与 V128 第 8.1 节一致）。 */
    private void insertFormingFlowFromAp(Map<String, Object> ap, String code, String bizKey) {
        String sourceBill = TmsUtil.str(ap.get("sourceBill"));
        String bizType = SupplierAccountConst.AP_RECEIPT;
        if (sourceBill.startsWith("QCAP-")) {
            bizType = SupplierAccountConst.AP_OPENING;
        } else if (!queryOneString(
                "SELECT return_no FROM pur_return WHERE return_no=?", sourceBill).isEmpty()) {
            bizType = SupplierAccountConst.AP_RETURN;
        }
        BigDecimal apAmount = nz(TmsUtil.toBd(ap.get("apAmount")));
        BigDecimal paid = nz(TmsUtil.toBd(ap.get("paidAmount")));
        BigDecimal unpaid = nz(TmsUtil.toBd(ap.get("unpaidAmount")));
        String status;
        if (unpaid.signum() == 0) {
            status = SupplierAccountConst.SETTLE_DONE;
        } else if (paid.signum() == 0) {
            status = SupplierAccountConst.SETTLE_UNSETTLED;
        } else {
            status = SupplierAccountConst.SETTLE_PART;
        }
        Timestamp auditTime = queryOneTimestamp(
                "SELECT audit_time FROM pur_receipt WHERE receipt_no=?", sourceBill);
        if (auditTime == null) {
            auditTime = queryOneTimestamp(
                    "SELECT audit_time FROM pur_return WHERE return_no=?", sourceBill);
        }
        LocalDateTime occurred = auditTime == null ? LocalDateTime.now() : auditTime.toLocalDateTime();
        java.sql.Date billDate = queryOneDate(
                "SELECT receipt_date FROM pur_receipt WHERE receipt_no=?", sourceBill);
        if (billDate == null) {
            billDate = queryOneDate(
                    "SELECT return_date FROM pur_return WHERE return_no=?", sourceBill);
        }
        LocalDate dueLocalDate = null;
        Object dueRaw = ap.get("dueDate");
        if (dueRaw instanceof java.sql.Date d) {
            dueLocalDate = d.toLocalDate();
        } else if (dueRaw instanceof LocalDate ld) {
            dueLocalDate = ld;
        } else {
            String dueStr = TmsUtil.str(dueRaw);
            if (dueStr.length() >= 10) {
                dueLocalDate = LocalDate.parse(dueStr.substring(0, 10));
            }
        }
        LocalDate postDate = billDate != null ? billDate.toLocalDate()
                : (dueLocalDate != null ? dueLocalDate : occurred.toLocalDate());
        String summary;
        switch (bizType) {
            case SupplierAccountConst.AP_OPENING -> summary = "期初应付 " + TmsUtil.str(ap.get("apNo"));
            case SupplierAccountConst.AP_RETURN -> summary = "采购退货红冲 " + sourceBill;
            default -> summary = "采购收货 " + sourceBill;
        }
        jdbc.update("INSERT INTO fin_supplier_account_flow (flow_id,supplier_code,supplier_name,account_type,"
                        + "biz_type,increase_amount,decrease_amount,balance_after,settle_status,settled_amount,"
                        + "ap_no,source_bill,post_date,occurred_at,summary,is_red,"
                        + "reverse_status,biz_key,created_at) SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                        + "CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM fin_supplier_account_flow WHERE biz_key=?)",
                TmsUtil.uuid("SAF"), code, TmsUtil.str(ap.get("supplier")),
                SupplierAccountConst.ACCOUNT_AP, bizType, apAmount, BigDecimal.ZERO, null,
                status, paid, TmsUtil.str(ap.get("apNo")), sourceBill,
                java.sql.Date.valueOf(postDate), Timestamp.valueOf(occurred), summary,
                apAmount.signum() < 0 ? "Y" : "N", SupplierAccountConst.REVERSE_NORMAL, bizKey, bizKey);
        // balance_after 由随后调用的 rebuildChain 统一重排
    }

    /** 修复补缺：从核销记录插入结算流水（口径与 V128 第 8.2 节一致）。 */
    private void insertSettleFlowFromRecord(Map<String, Object> rec, String code, String apNo, String bizKey) {
        BigDecimal amount = nz(TmsUtil.toBd(rec.get("reconcileAmount")));
        String sourceBill = TmsUtil.str(rec.get("sourceBill"));
        if (sourceBill.isEmpty()) {
            sourceBill = queryOneString("SELECT source_bill FROM fin_ap WHERE ap_no=?", apNo);
        }
        String supplierName = TmsUtil.str(rec.get("counterpartyName"));
        if (supplierName.isEmpty()) {
            supplierName = queryOneString("SELECT supplier FROM fin_ap WHERE ap_no=?", apNo);
        }
        Object createdAtRaw = rec.get("createdAt");
        LocalDateTime occurred = createdAtRaw instanceof Timestamp ts ? ts.toLocalDateTime() : LocalDateTime.now();
        LocalDate postDate = rec.get("receiptDate") instanceof java.sql.Date d
                ? d.toLocalDate() : occurred.toLocalDate();
        String receiptNo = TmsUtil.str(rec.get("receiptNo"));
        jdbc.update("INSERT INTO fin_supplier_account_flow (flow_id,supplier_code,supplier_name,account_type,"
                        + "biz_type,increase_amount,decrease_amount,balance_after,ap_no,source_bill,"
                        + "payment_no,receipt_no,reconcile_id,post_date,occurred_at,summary,is_red,"
                        + "reverse_status,biz_key,created_at) SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                        + "CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM fin_supplier_account_flow WHERE biz_key=?)",
                TmsUtil.uuid("SAF"), code, supplierName, SupplierAccountConst.ACCOUNT_AP,
                SupplierAccountConst.AP_SETTLE_CASH, BigDecimal.ZERO, amount, null, apNo, sourceBill,
                receiptNo.isEmpty() ? null : receiptNo, receiptNo.isEmpty() ? null : receiptNo,
                TmsUtil.str(rec.get("recordId")),
                java.sql.Date.valueOf(postDate), Timestamp.valueOf(occurred),
                "付款结算 " + receiptNo, "N",
                SupplierAccountConst.REVERSE_NORMAL, bizKey, bizKey);
    }

    private Timestamp queryOneTimestamp(String sql, Object arg) {
        List<Timestamp> list = jdbc.query(sql, (rs, i) -> rs.getTimestamp(1), arg);
        return list.isEmpty() ? null : list.get(0);
    }

    private java.sql.Date queryOneDate(String sql, Object arg) {
        List<java.sql.Date> list = jdbc.query(sql, (rs, i) -> rs.getDate(1), arg);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 刷新账户档案快照（改名/改采购员/结算方式/账期）。 */
    private void refreshSnapshot(String supplierCode) {
        jdbc.update("UPDATE fin_supplier_account SET "
                + "supplier_name=COALESCE((SELECT supplier_name FROM base_supplier WHERE supplier_code=?),"
                + "supplier_name), "
                + "default_buyer=(SELECT default_buyer FROM base_supplier WHERE supplier_code=?), "
                + "settlement_method=(SELECT settlement_method FROM base_supplier WHERE supplier_code=?), "
                + "account_period_days=(SELECT account_period_days FROM base_supplier WHERE supplier_code=?), "
                + "updated_at=CURRENT_TIMESTAMP WHERE supplier_code=?",
                supplierCode, supplierCode, supplierCode, supplierCode, supplierCode);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 前端时间输入兼容：yyyy-MM-dd 补成 00:00:00 / 23:59:59。 */
    private static String ensureDateTime(String s, boolean endOfDay) {
        String t = s.trim().replace('T', ' ');
        if (t.length() == 10) {
            return t + (endOfDay ? " 23:59:59" : " 00:00:00");
        }
        return t;
    }
}
