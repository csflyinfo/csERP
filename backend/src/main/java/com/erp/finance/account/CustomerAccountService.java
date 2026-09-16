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
 * 客户账户服务（PRD-35）。
 *
 * <p>真值与缓存：fin_ar / fin_reconcile_record 是真值；账户表余额、流水结算标志、
 * balance_after 滚存链都是可重建缓存。{@link #repair(String)} 可从真值完整重建并出差异报告。
 *
 * <p>M1 提供只读账户/流水查询与数据修复；M2/M3 在本服务底座上接入预收收退款与预收核销。
 */
@Service
public class CustomerAccountService {

    public static final String MODULE = "fin.customer_account";

    /** 余额链排序：同刻先形成后结算（签收当天即结算时形成行在前）。 */
    private static final String CHAIN_ORDER =
            "occurred_at, CASE WHEN biz_type IN ('AR_SIGN','AR_RETURN','AR_EXPENSE','AR_OPENING') "
            + "THEN 0 ELSE 1 END, flow_id";

    private final JdbcTemplate jdbc;
    private final OperationLogService opLog;

    public CustomerAccountService(JdbcTemplate jdbc, OperationLogService opLog) {
        this.jdbc = jdbc;
        this.opLog = opLog;
    }

    // ==================== M1：账户分页 ====================

    /** 账户分页。body: {pageNo,pageSize,keyword,channelType,salesman,hideZero}。 */
    public Map<String, Object> pageAccounts(Map<String, Object> req) {
        int pageNo = Math.max(1, TmsUtil.toInt(req.get("pageNo")));
        int pageSizeRaw = TmsUtil.toInt(req.get("pageSize"));
        int pageSize = pageSizeRaw <= 0 ? 20 : Math.min(200, pageSizeRaw);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String channel = TmsUtil.str(req.get("channelType"));
        if (!channel.isEmpty()) {
            where.append(" AND c.channel_type=?");
            args.add(channel);
        }
        String salesman = TmsUtil.str(req.get("salesman"));
        if (!salesman.isEmpty()) {
            where.append(" AND c.salesman=?");
            args.add(salesman);
        }
        String keyword = TmsUtil.str(req.get("keyword"));
        if (!keyword.isEmpty()) {
            // 关键字口径与客户主档分页一致：编号/店名/手机，另兜底账户名（历史客户主档可能已删）
            where.append(" AND (c.customer_code LIKE ? OR c.customer_name LIKE ? OR c.mobile LIKE ?"
                    + " OR a.customer_name LIKE ?)");
            for (int i = 0; i < 4; i++) args.add("%" + keyword + "%");
        }
        if (Boolean.TRUE.equals(req.get("hideZero"))) {
            where.append(" AND (a.ar_balance<>0 OR a.advance_balance<>0)");
        }

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_customer_account a "
                        + "LEFT JOIN base_customer c ON c.customer_code=a.customer_code" + where,
                Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT a.customer_code, COALESCE(c.customer_name,a.customer_name) customer_name, "
                        + "a.ar_balance, a.advance_balance, c.salesman, c.channel_type, c.route_line, "
                        + "c.credit_limit, c.account_period_type, c.cutoff_day, c.payment_day "
                        + "FROM fin_customer_account a "
                        + "LEFT JOIN base_customer c ON c.customer_code=a.customer_code" + where
                        + " ORDER BY a.ar_balance DESC, a.customer_code LIMIT ? OFFSET ?",
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
     * 往来流水分页。body: {customerCode,accountType,beginTime,endTime,settleStatus,pageNo,pageSize}。
     * 返回 priorBalance（此前余额）+ 分页流水（含 bizLabel 业务单据文案）。
     */
    public Map<String, Object> pageFlow(Map<String, Object> req) {
        String customerCode = TmsUtil.str(req.get("customerCode"));
        if (customerCode.isEmpty()) {
            throw new IllegalArgumentException("缺少客户参数");
        }
        String accountType = TmsUtil.str(req.get("accountType"));
        if (accountType.isEmpty()) {
            accountType = CustomerAccountConst.ACCOUNT_AR;
        }
        if (!CustomerAccountConst.ACCOUNT_AR.equals(accountType)
                && !CustomerAccountConst.ACCOUNT_ADVANCE.equals(accountType)) {
            throw new IllegalArgumentException("账户类型不正确");
        }

        StringBuilder where = new StringBuilder(" WHERE customer_code=? AND account_type=?");
        List<Object> args = new ArrayList<>();
        args.add(customerCode);
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
                    "SELECT balance_after FROM fin_customer_account_flow"
                            + " WHERE customer_code=? AND account_type=? AND occurred_at<?"
                            + " ORDER BY occurred_at DESC, "
                            + "CASE WHEN biz_type IN ('AR_SIGN','AR_RETURN','AR_EXPENSE','AR_OPENING') "
                            + "THEN 0 ELSE 1 END DESC, flow_id DESC LIMIT 1",
                    (rs, i) -> rs.getBigDecimal("balance_after"),
                    customerCode, accountType, Timestamp.valueOf(ensureDateTime(begin, false)));
            if (!prior.isEmpty() && prior.get(0) != null) {
                priorBalance = prior.get(0);
            }
        }

        int pageNo = Math.max(1, TmsUtil.toInt(req.get("pageNo")));
        int pageSizeRaw = TmsUtil.toInt(req.get("pageSize"));
        int pageSize = pageSizeRaw <= 0 ? 20 : Math.min(200, pageSizeRaw);

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_customer_account_flow" + where,
                Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT flow_id, customer_code, customer_name, account_type, biz_type, "
                        + "increase_amount, decrease_amount, balance_after, settle_status, settled_amount, "
                        + "ar_no, source_bill, order_no, delivery_no, receipt_no, writeoff_no, reconcile_id, "
                        + "post_date, occurred_at, summary, operator_name, is_red, reverse_status "
                        + "FROM fin_customer_account_flow" + where
                        + " ORDER BY occurred_at DESC, "
                        + "CASE WHEN biz_type IN ('AR_SIGN','AR_RETURN','AR_EXPENSE','AR_OPENING') "
                        + "THEN 0 ELSE 1 END DESC, flow_id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        for (Map<String, Object> row : records) {
            row.put("bizLabel", CustomerAccountConst.bizLabel(TmsUtil.str(row.get("bizType"))));
        }

        // 当前账户实时余额（页面头部展示）
        BigDecimal currentBalance = currentBalance(customerCode, accountType);

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
     * 1) 补账户行/档案快照；2) 补缺形成/结算流水；3) 按核销记录回填结算标志；
     * 4) 重排两账户余额链；5) 按真值重算账户余额并出差异报告。不删除业务流水。
     *
     * @param customerCode 指定客户；空串=全部客户
     */
    @Transactional
    public Map<String, Object> repair(String customerCode) {
        String onlyCode = customerCode == null ? "" : customerCode.trim();
        List<String> mismatches = new ArrayList<>();
        int missingForming = 0;
        int missingSettle = 0;

        // 1) 账户行：在册客户全量补齐（单客户时只补该客户）
        if (onlyCode.isEmpty()) {
            jdbc.update("MERGE INTO fin_customer_account (customer_code,customer_name,salesman,channel_type,"
                    + "route_line,ar_balance,advance_balance,version,created_at,updated_at) KEY(customer_code) "
                    + "SELECT customer_code,customer_name,salesman,channel_type,route_line,0,0,0,"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM base_customer WHERE customer_code IS NOT NULL");
        } else {
            ensureAccount(onlyCode);
        }

        // 已存在流水幂等键，避免重复补
        Set<String> existingKeys = new HashSet<>(jdbc.query(
                "SELECT biz_key FROM fin_customer_account_flow", (rs, i) -> rs.getString("biz_key")));

        // 2.1) 应收真值（按解析后的客户编码分组）+ 补缺形成流水
        Map<String, BigDecimal> trueAr = new HashMap<>();
        List<Map<String, Object>> arRows = TmsUtil.queryCamel(jdbc,
                "SELECT ar_id, ar_no, source_bill, customer, salesman, ar_amount, received_amount, "
                        + "unreceived_amount, due_date, created_at FROM fin_ar ORDER BY created_at, ar_no");
        for (Map<String, Object> ar : arRows) {
            String code = resolveArCustomerCode(ar);
            if (code == null || code.isEmpty()) {
                code = TmsUtil.str(ar.get("customer"));
            }
            if (!onlyCode.isEmpty() && !onlyCode.equals(code)) {
                continue;
            }
            BigDecimal unreceived = nz(TmsUtil.toBd(ar.get("unreceivedAmount")));
            trueAr.merge(code, unreceived, BigDecimal::add);
            ensureAccount(code);

            String bizKey = "AR:" + TmsUtil.str(ar.get("arNo"));
            if (!existingKeys.contains(bizKey)) {
                insertFormingFlowFromAr(ar, code, bizKey);
                existingKeys.add(bizKey);
                missingForming++;
            }
        }

        // 2.2) 补缺结算流水（只取归属应收单的核销记录）
        List<Map<String, Object>> recRows = TmsUtil.queryCamel(jdbc,
                "SELECT record_id, receipt_no, receipt_date, business_no, business_type, business_date, "
                        + "counterparty_code, counterparty_name, reconcile_amount, ar_no, source_bill, "
                        + "created_at FROM fin_reconcile_record r "
                        + "WHERE EXISTS (SELECT 1 FROM fin_ar a WHERE a.ar_no=COALESCE(r.ar_no,r.business_no)) "
                        // 预收核销记录的应收流水在线已写（AR_SETTLE_ADVANCE，bizKey XHAR:*），
                        // 不能再按 AR_SETTLE_CASH 补一遍，否则双倍扣减应收
                        + "AND r.business_type <> 'ADVANCE_WRITE_OFF' "
                        + "ORDER BY created_at, record_id");
        for (Map<String, Object> rec : recRows) {
            String arNo = TmsUtil.str(rec.get("arNo"));
            if (arNo.isEmpty()) {
                arNo = TmsUtil.str(rec.get("businessNo"));
            }
            String code = resolveArCustomerCodeByArNo(arNo);
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

            String bizKey = "ARR:" + TmsUtil.str(rec.get("recordId"));
            if (!existingKeys.contains(bizKey)) {
                insertSettleFlowFromRecord(rec, code, arNo, bizKey);
                existingKeys.add(bizKey);
                missingSettle++;
            }
        }

        // 3) 回填形成行结算标志/已结额（真值取 fin_ar 自身的 received/unreceived）
        for (Map<String, Object> ar : arRows) {
            String arNo = TmsUtil.str(ar.get("arNo"));
            String code = resolveArCustomerCode(ar);
            if (code.isEmpty()) {
                code = TmsUtil.str(ar.get("customer"));
            }
            if (!onlyCode.isEmpty() && !onlyCode.equals(code)) {
                continue;
            }
            BigDecimal received = nz(TmsUtil.toBd(ar.get("receivedAmount")));
            BigDecimal unreceived = nz(TmsUtil.toBd(ar.get("unreceivedAmount")));
            String status;
            if (unreceived.signum() == 0) {
                status = CustomerAccountConst.SETTLE_DONE;
            } else if (received.signum() == 0) {
                status = CustomerAccountConst.SETTLE_UNSETTLED;
            } else {
                status = CustomerAccountConst.SETTLE_PART;
            }
            jdbc.update("UPDATE fin_customer_account_flow SET settled_amount=?, settle_status=? "
                            + "WHERE ar_no=? AND account_type='AR' AND biz_type IN "
                            + "('AR_SIGN','AR_RETURN','AR_EXPENSE','AR_OPENING')",
                    received, status, arNo);
        }

        // 4) 重排目标客户两个账户余额链；5) 真值重算余额并比对差异
        List<String> targets;
        if (!onlyCode.isEmpty()) {
            targets = List.of(onlyCode);
        } else {
            targets = jdbc.queryForList(
                    "SELECT DISTINCT customer_code FROM fin_customer_account_flow", String.class);
        }
        int chains = 0;
        for (String code : targets) {
            BigDecimal arChain = rebuildChain(code, CustomerAccountConst.ACCOUNT_AR);
            BigDecimal advChain = rebuildChain(code, CustomerAccountConst.ACCOUNT_ADVANCE);
            chains += 2;
            BigDecimal trueArBal = trueAr.getOrDefault(code, BigDecimal.ZERO);
            if (arChain.compareTo(trueArBal) != 0) {
                mismatches.add("客户 " + code + " 应收流水余额 " + arChain + " 与应收单真值 " + trueArBal
                        + " 不一致，已按真值修正");
            }
            jdbc.update("UPDATE fin_customer_account SET ar_balance=?, advance_balance=?, updated_at=CURRENT_TIMESTAMP "
                            + "WHERE customer_code=?",
                    trueArBal, advChain, code);
            refreshSnapshot(code);
        }
        // 没有任何流水的账户行，应收也按真值兜底（理论上形成流水已补齐，这里防御）
        if (!onlyCode.isEmpty()) {
            jdbc.update("UPDATE fin_customer_account SET ar_balance=? WHERE customer_code=?",
                    trueAr.getOrDefault(onlyCode, BigDecimal.ZERO), onlyCode);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", onlyCode.isEmpty() ? "ALL" : onlyCode);
        result.put("accounts", targets.size());
        result.put("missingForming", missingForming);
        result.put("missingSettle", missingSettle);
        result.put("chainsRebuilt", chains);
        result.put("mismatches", mismatches);
        opLog.log(MODULE, "DATA_REPAIR", "",
                "客户账户数据修复：" + (onlyCode.isEmpty() ? "全部客户" : onlyCode)
                        + "，补缺形成流水 " + missingForming + " 条、结算流水 " + missingSettle
                        + " 条，重排余额链 " + chains + " 条，差异 " + mismatches.size() + " 项");
        return result;
    }

    // ==================== M2：预收收款/退款（收款单审核联动） ====================

    /** 查客户当前预收余额（账户不存在视为 0）。供收款单抽屉退款时提示余额。 */
    public BigDecimal getAdvanceBalance(String customerCode) {
        if (customerCode == null || customerCode.isBlank()) return BigDecimal.ZERO;
        List<BigDecimal> vals = jdbc.queryForList(
                "SELECT advance_balance FROM fin_customer_account WHERE customer_code = ?",
                BigDecimal.class, customerCode);
        return vals.isEmpty() ? BigDecimal.ZERO : nz(vals.get(0));
    }

    /** 按名称解析客户编码（口径同在线流水归属：档案优先，名称兜底）。 */
    public String resolveCodeByName(String customerName) {
        if (customerName == null || customerName.isBlank()) return "";
        return resolveBySource("", customerName.trim());
    }

    /** 按客户编码/名称查预收余额：编码为空时先按名称解析编码（口径同在线流水归属）。 */
    public BigDecimal getAdvanceBalanceByName(String customerCode, String customerName) {
        String code = customerCode == null ? "" : customerCode.trim();
        if (code.isEmpty() && customerName != null && !customerName.isBlank()) {
            code = resolveBySource("", customerName.trim());
        }
        return getAdvanceBalance(code);
    }

    /**
     * 预收收款单审核：写 ADV_RECEIPT（increase）。调用方须与资金/往来流水同事务。
     *
     * @param postDate 记账日期（收款单日期，决定 post_date，不决定 occurred_at）
     */
    public void postAdvanceReceipt(String receiptNo, LocalDate postDate, String customerCode,
                                   String customerName, BigDecimal amount, String summary) {
        if (customerCode == null || customerCode.isEmpty()) {
            throw new IllegalArgumentException("预收收款必须选择客户");
        }
        ensureAccount(customerCode);
        lockAccount(customerCode);
        int round = receiptRound(receiptNo) + 1;
        CustomerAccountFlowLine line = new CustomerAccountFlowLine(
                CustomerAccountConst.ACCOUNT_ADVANCE, CustomerAccountConst.ADV_RECEIPT,
                customerCode, customerName, "ADV_RECEIPT:" + receiptNo + "#" + round);
        line.setIncreaseAmount(nz(amount));
        line.setReceiptNo(receiptNo);
        line.setPostDate(postDate);
        line.setSummary("预收收款 " + receiptNo + (summary == null || summary.isEmpty() ? "" : " " + summary));
        writeFlow(line);
    }

    /**
     * 预收退款单审核：写 ADV_REFUND（decrease）。
     * 退款金额不得超过当前预收余额（先核销/先花掉的预收不能退）。
     */
    public void postAdvanceRefund(String receiptNo, LocalDate postDate, String customerCode,
                                  String customerName, BigDecimal amount, String summary) {
        if (customerCode == null || customerCode.isEmpty()) {
            throw new IllegalArgumentException("预收退款必须选择客户");
        }
        ensureAccount(customerCode);
        lockAccount(customerCode);
        BigDecimal bal = currentBalance(customerCode, CustomerAccountConst.ACCOUNT_ADVANCE);
        if (nz(amount).compareTo(bal) > 0) {
            throw new IllegalArgumentException("预收余额不足，当前预收余额 " + bal + " 元，无法退款 " + amount + " 元");
        }
        int round = receiptRound(receiptNo) + 1;
        CustomerAccountFlowLine line = new CustomerAccountFlowLine(
                CustomerAccountConst.ACCOUNT_ADVANCE, CustomerAccountConst.ADV_REFUND,
                customerCode, customerName, "ADV_REFUND:" + receiptNo + "#" + round);
        line.setDecreaseAmount(nz(amount));
        line.setReceiptNo(receiptNo);
        line.setPostDate(postDate);
        line.setSummary("预收退款 " + receiptNo + (summary == null || summary.isEmpty() ? "" : " " + summary));
        writeFlow(line);
    }

    /**
     * 预收收款单反审核：追加 ADV_REVERSE（decrease）冲回行，原预收行置 REVERSED。
     * 已被核销/退款使用（当前余额小于原收款额）时拒绝，要求先反审核下游 XH 单/退款单。
     */
    public void reverseAdvanceReceipt(String receiptNo, LocalDate postDate, String customerCode,
                                      String customerName, BigDecimal amount, String summary) {
        ensureAccount(customerCode);
        lockAccount(customerCode);
        BigDecimal bal = currentBalance(customerCode, CustomerAccountConst.ACCOUNT_ADVANCE);
        if (nz(amount).compareTo(bal) > 0) {
            throw new IllegalArgumentException("该预收款已被预收核销或退款使用（当前预收余额 " + bal
                    + " 元），请先反审核相关预收核销单或预收退款单");
        }
        appendReverseLine(receiptNo, postDate, customerCode, customerName, amount,
                CustomerAccountConst.ADV_RECEIPT, false, summary);
    }

    /** 预收退款单反审核：追加 ADV_REVERSE（increase）把预收加回来，原退款行置 REVERSED。 */
    public void reverseAdvanceRefund(String receiptNo, LocalDate postDate, String customerCode,
                                     String customerName, BigDecimal amount, String summary) {
        ensureAccount(customerCode);
        lockAccount(customerCode);
        appendReverseLine(receiptNo, postDate, customerCode, customerName, amount,
                CustomerAccountConst.ADV_REFUND, true, summary);
    }

    /**
     * 冲回行落账：round 取该收款单已有正向预收行数（收/退款），冲回行与被冲回行同轮次配对，
     * 支持「反审核 → 修改 → 再审核」多轮（每轮 bizKey 带 #轮次，不违反唯一键）。
     */
    private void appendReverseLine(String receiptNo, LocalDate postDate, String customerCode,
                                   String customerName, BigDecimal amount, String forwardBizType,
                                   boolean refundForward, String summary) {
        int round = receiptRound(receiptNo);
        if (round <= 0) {
            // 找不到正向行（历史脏数据）：不静默，提示先走数据修复
            throw new IllegalArgumentException("未找到收款单 " + receiptNo + " 的预收流水，请先执行客户账户数据修复");
        }
        // 正向行业务键确定：<bizType>:<单号>#<轮次>（H2 不支持 UPDATE...ORDER BY，按键定位）
        jdbc.update("UPDATE fin_customer_account_flow SET reverse_status=? WHERE biz_key=?",
                CustomerAccountConst.REVERSE_REVERSED,
                forwardBizType + ":" + receiptNo + "#" + round);
        CustomerAccountFlowLine line = new CustomerAccountFlowLine(
                CustomerAccountConst.ACCOUNT_ADVANCE, CustomerAccountConst.ADV_REVERSE,
                customerCode, customerName, "ADV_REVERSE:" + receiptNo + "#" + round);
        if (refundForward) {
            line.setIncreaseAmount(nz(amount));
        } else {
            line.setDecreaseAmount(nz(amount));
        }
        line.setReceiptNo(receiptNo);
        line.setPostDate(postDate);
        line.setRed(true);
        line.setSummary((refundForward ? "预收退款冲回 " : "预收款冲回 ") + receiptNo + "（取消审核）"
                + (summary == null || summary.isEmpty() ? "" : " " + summary));
        writeFlow(line);
    }

    /** 该收款单已发生的预收正向轮次（ADV_RECEIPT/ADV_REFUND 行数）。 */
    private int receiptRound(String receiptNo) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fin_customer_account_flow "
                + "WHERE receipt_no=? AND biz_type IN (?,?)", Integer.class, receiptNo,
                CustomerAccountConst.ADV_RECEIPT, CustomerAccountConst.ADV_REFUND);
        return n == null ? 0 : n;
    }

    // ==================== M4：期初预收（ADV_OPENING） ====================

    /**
     * 期初预收建账：写一笔 ADV_OPENING 增加流水（不造收款单、不动资金）。
     * 幂等键 ADVO:&lt;过批号&gt;:&lt;序号&gt;，来源号 QCYK-&lt;过批号&gt;-&lt;序号&gt;；
     * post_date 取建账日。返回生成的 flow_id（反建账据此定位）。
     */
    @Transactional
    public String postAdvanceOpening(String postNo, int seq, LocalDate postDate, String customerCode,
                                     String customerName, BigDecimal amount) {
        if (customerCode == null || customerCode.isEmpty()) {
            throw new IllegalArgumentException("期初预收必须选择客户");
        }
        if (nz(amount).signum() <= 0) {
            throw new IllegalArgumentException("期初预收金额必须大于 0");
        }
        ensureAccount(customerCode);
        lockAccount(customerCode);
        String sourceBill = "QCYK-" + postNo + "-" + seq;
        CustomerAccountFlowLine line = new CustomerAccountFlowLine(
                CustomerAccountConst.ACCOUNT_ADVANCE, CustomerAccountConst.ADV_OPENING,
                customerCode, customerName, "ADVO:" + postNo + ":" + seq);
        line.setIncreaseAmount(nz(amount));
        line.setSourceBill(sourceBill);
        line.setPostDate(postDate);
        line.setSummary("期初预收 " + sourceBill);
        writeFlow(line);
        List<String> ids = jdbc.queryForList(
                "SELECT flow_id FROM fin_customer_account_flow WHERE biz_key=?", String.class,
                "ADVO:" + postNo + ":" + seq);
        return ids.get(0);
    }

    /**
     * 期初预收反建账守卫：该客户在建账流水之后已发生预收核销/退款时返回中文拒绝原因，否则 null。
     * （预收被使用后删除期初行会造成余额链断裂/余额为负，必须先反审核下游单据。）
     *
     * <p>只统计<b>仍生效</b>的下游行：XH/退款单反审核时原 ADV_WRITE_OFF/ADV_REFUND 行保留在表中
     * （另追加红字 ADV_REVERSE 冲回行）并置 reverse_status='REVERSED'，这类行代表占用已解除，
     * 不能再阻止反建账——否则「先反审核下游再反建账」的撤销路径会被永久卡死。
     */
    public String checkAdvanceOpeningReversable(String customerCode, String flowId) {
        Integer downstream = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_customer_account_flow f "
                        + "WHERE f.customer_code=? AND f.account_type='ADVANCE' "
                        + "AND f.biz_type IN ('ADV_WRITE_OFF','ADV_REFUND') "
                        + "AND COALESCE(f.reverse_status,'') <> 'REVERSED' "
                        + "AND f.flow_id<>? AND f.occurred_at >= ("
                        + "SELECT occurred_at FROM fin_customer_account_flow WHERE flow_id=?)",
                Integer.class, customerCode, flowId, flowId);
        if (downstream != null && downstream > 0) {
            return "客户「" + customerCode + "」的期初预收已被预收核销或退款使用，不能反建账；"
                    + "请先反审核相关预收核销单或预收退款单";
        }
        return null;
    }

    /** 删除一笔期初预收流水，重排该客户预收余额链并同步账户缓存列。 */
    @Transactional
    public void deleteAdvanceOpening(String flowId, String customerCode) {
        jdbc.update("DELETE FROM fin_customer_account_flow WHERE flow_id=? AND biz_type=?",
                flowId, CustomerAccountConst.ADV_OPENING);
        BigDecimal end = rebuildChain(customerCode, CustomerAccountConst.ACCOUNT_ADVANCE);
        jdbc.update("UPDATE fin_customer_account SET advance_balance=?, updated_at=CURRENT_TIMESTAMP "
                + "WHERE customer_code=?", end, customerCode);
    }

    // ==================== M3：预收核销（XH 单联动） ====================

    /** XH 核销单行落账参数：每行一笔应收的预收核销额。 */
    public static final class WriteoffLine {
        public String arNo;
        public String sourceBill;
        /** 本行核销额（>0）。 */
        public BigDecimal amount;
        /** 核销后该 AR 累计已收（回写形成行 settled_amount）。 */
        public BigDecimal receivedAfter;
        /** 核销后形成行结算标志：部分结算/已结算。 */
        public String settleStatusAfter;
        /** 对应的核销真值记录 id（fin_reconcile_record.record_id）。 */
        public String reconcileId;

        public WriteoffLine() {
        }

        public WriteoffLine(String arNo, String sourceBill, BigDecimal amount,
                            BigDecimal receivedAfter, String settleStatusAfter, String reconcileId) {
            this.arNo = arNo;
            this.sourceBill = sourceBill;
            this.amount = amount;
            this.receivedAfter = receivedAfter;
            this.settleStatusAfter = settleStatusAfter;
            this.reconcileId = reconcileId;
        }
    }

    /**
     * XH 预收核销单审核落账（一个事务，由 AdvanceWriteoffService 调）：
     * 预收侧写 1 行 ADV_WRITE_OFF（decrease=合计），应收侧每行写 AR_SETTLE_ADVANCE（decrease），
     * 并按核销后的 fin_ar 真值回写形成行结算标志/已结额。预收余额不足直接中文报错，整单回滚。
     *
     * @param roundKey 轮次键基准（同单号支持「反审核→改→再审核」，bizKey 带 #轮次）
     */
    @Transactional
    public void postAdvanceWriteoff(String writeoffNo, LocalDate postDate, String customerCode,
                                    String customerName, BigDecimal total, List<WriteoffLine> lines,
                                    String remark) {
        if (customerCode == null || customerCode.isEmpty()) {
            throw new IllegalArgumentException("预收核销必须选择客户");
        }
        ensureAccount(customerCode);
        lockAccount(customerCode);
        BigDecimal bal = currentBalance(customerCode, CustomerAccountConst.ACCOUNT_ADVANCE);
        if (nz(total).compareTo(bal) > 0) {
            throw new IllegalArgumentException("预收余额不足，当前预收余额 " + bal + " 元，无法核销 " + total + " 元");
        }
        int round = writeoffRound(writeoffNo) + 1;
        String tail = remark == null || remark.isEmpty() ? "" : " " + remark;

        // 预收侧：一行 ADV_WRITE_OFF（decrease=合计）
        CustomerAccountFlowLine advLine = new CustomerAccountFlowLine(
                CustomerAccountConst.ACCOUNT_ADVANCE, CustomerAccountConst.ADV_WRITE_OFF,
                customerCode, customerName, "XH:" + writeoffNo + "#" + round);
        advLine.setDecreaseAmount(nz(total));
        advLine.setWriteoffNo(writeoffNo);
        advLine.setPostDate(postDate);
        advLine.setSummary("预收核销 " + writeoffNo + tail);
        writeFlow(advLine);

        // 应收侧：每行 AR_SETTLE_ADVANCE，并回写形成行结算标志
        for (WriteoffLine l : lines) {
            CustomerAccountFlowLine arLine = new CustomerAccountFlowLine(
                    CustomerAccountConst.ACCOUNT_AR, CustomerAccountConst.AR_SETTLE_ADVANCE,
                    customerCode, customerName,
                    "XHAR:" + writeoffNo + ":" + l.arNo + "#" + round);
            arLine.setDecreaseAmount(nz(l.amount));
            arLine.setArNo(l.arNo);
            arLine.setSourceBill(l.sourceBill);
            arLine.setWriteoffNo(writeoffNo);
            arLine.setReconcileId(l.reconcileId);
            arLine.setPostDate(postDate);
            arLine.setSummary("预收冲应收 " + (l.sourceBill == null || l.sourceBill.isEmpty() ? l.arNo : l.sourceBill));
            writeFlow(arLine);
            if (l.settleStatusAfter != null) {
                writeBackFormingStatus(l.arNo, nz(l.receivedAfter), l.settleStatusAfter);
            }
        }
    }

    /**
     * XH 单反审核冲回：原正向行置 REVERSED，追加红字 ADV_REVERSE（increase=合计，预收加回）
     * 与每行 AR_REVERSE（increase，应收加回），形成行结算标志按 fin_ar 当前真值复位。
     * 调用方须先删除核销真值记录、回退 fin_ar，再调本方法。
     */
    @Transactional
    public void reverseAdvanceWriteoff(String writeoffNo, LocalDate postDate, String customerCode,
                                       String customerName, BigDecimal total, List<WriteoffLine> lines) {
        ensureAccount(customerCode);
        lockAccount(customerCode);
        int round = writeoffRound(writeoffNo);
        if (round <= 0) {
            throw new IllegalArgumentException("未找到预收核销单 " + writeoffNo + " 的账户流水，请先执行客户账户数据修复");
        }
        // 预收侧：原 ADV_WRITE_OFF 置 REVERSED，追加红字 ADV_REVERSE（increase）
        jdbc.update("UPDATE fin_customer_account_flow SET reverse_status=? WHERE biz_key=?",
                CustomerAccountConst.REVERSE_REVERSED, "XH:" + writeoffNo + "#" + round);
        CustomerAccountFlowLine advRev = new CustomerAccountFlowLine(
                CustomerAccountConst.ACCOUNT_ADVANCE, CustomerAccountConst.ADV_REVERSE,
                customerCode, customerName, "XHREV:" + writeoffNo + "#" + round);
        advRev.setIncreaseAmount(nz(total));
        advRev.setWriteoffNo(writeoffNo);
        advRev.setPostDate(postDate);
        advRev.setRed(true);
        advRev.setSummary("预收核销冲回 " + writeoffNo + "（取消审核）");
        writeFlow(advRev);

        // 应收侧：逐行原 AR_SETTLE_ADVANCE 置 REVERSED，追加红字 AR_REVERSE（increase）
        for (WriteoffLine l : lines) {
            jdbc.update("UPDATE fin_customer_account_flow SET reverse_status=? WHERE biz_key=?",
                    CustomerAccountConst.REVERSE_REVERSED,
                    "XHAR:" + writeoffNo + ":" + l.arNo + "#" + round);
            CustomerAccountFlowLine arRev = new CustomerAccountFlowLine(
                    CustomerAccountConst.ACCOUNT_AR, CustomerAccountConst.AR_REVERSE,
                    customerCode, customerName,
                    "XHARREV:" + writeoffNo + ":" + l.arNo + "#" + round);
            arRev.setIncreaseAmount(nz(l.amount));
            arRev.setArNo(l.arNo);
            arRev.setSourceBill(l.sourceBill);
            arRev.setWriteoffNo(writeoffNo);
            arRev.setPostDate(postDate);
            arRev.setRed(true);
            arRev.setSummary("预收冲应收冲回 " + (l.sourceBill == null || l.sourceBill.isEmpty()
                    ? l.arNo : l.sourceBill) + "（取消审核）");
            writeFlow(arRev);
            resetFormingStatusByAr(l.arNo);
        }
    }

    /** 该 XH 单已发生的正向核销轮次（ADV_WRITE_OFF 行数）。 */
    private int writeoffRound(String writeoffNo) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fin_customer_account_flow "
                + "WHERE writeoff_no=? AND biz_type=?", Integer.class,
                writeoffNo, CustomerAccountConst.ADV_WRITE_OFF);
        return n == null ? 0 : n;
    }

    /** 回写某应收形成行的结算标志/已结额（审核核销后）。 */
    private void writeBackFormingStatus(String arNo, BigDecimal receivedAfter, String settleStatusAfter) {
        jdbc.update("UPDATE fin_customer_account_flow SET settled_amount=?, settle_status=? "
                        + "WHERE ar_no=? AND account_type='AR' AND biz_type IN "
                        + "('AR_SIGN','AR_RETURN','AR_EXPENSE','AR_OPENING')",
                receivedAfter, settleStatusAfter, arNo);
    }

    /** 按 fin_ar 当前真值复位形成行结算标志（反审核后）。 */
    private void resetFormingStatusByAr(String arNo) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT ar_amount, received_amount, unreceived_amount FROM fin_ar WHERE ar_no=?", arNo);
        if (rows.isEmpty()) {
            // AR 行本身已被撤销（形成流水同生命周期删除的场景），标志行可能已不在
            return;
        }
        Map<String, Object> ar = rows.get(0);
        BigDecimal received = nz(TmsUtil.toBd(ar.get("receivedAmount")));
        BigDecimal unreceived = nz(TmsUtil.toBd(ar.get("unreceivedAmount")));
        String status;
        if (unreceived.signum() == 0) {
            status = CustomerAccountConst.SETTLE_DONE;
        } else if (received.signum() == 0) {
            status = CustomerAccountConst.SETTLE_UNSETTLED;
        } else {
            status = CustomerAccountConst.SETTLE_PART;
        }
        writeBackFormingStatus(arNo, received, status);
    }

    // ==================== M3：应收结算现金部分在线流水 ====================

    /** 现金/银行结算 AR 行参数（对应一笔 fin_reconcile_record 核销记录）。 */
    public static final class ArCashLine {
        /** 核销真值记录 id，同时作为流水幂等键基准（ARR:<recordId>，与数据修复补缺同源）。 */
        public String recordId;
        public String arNo;
        public String sourceBill;
        /** 本行现金结算额（>0）。 */
        public BigDecimal amount;
        public String receiptNo;

        public ArCashLine() {
        }

        public ArCashLine(String recordId, String arNo, String sourceBill,
                          BigDecimal amount, String receiptNo) {
            this.recordId = recordId;
            this.arNo = arNo;
            this.sourceBill = sourceBill;
            this.amount = amount;
            this.receiptNo = receiptNo;
        }
    }

    /**
     * 应收结算在线落 AR 结算流水（现金/银行部分），逐核销记录一行 AR_SETTLE_CASH（decrease）。
     * bizKey 用 {@code ARR:<recordId>}，与 {@link #repair} 补缺键完全同源，修复自动幂等跳过；
     * 同时按 fin_ar 真值回写形成行结算标志。调用方须已写核销记录、已更新 fin_ar。
     */
    @Transactional
    public void postArCashSettle(LocalDate postDate, String customerCode, String customerName,
                                 List<ArCashLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        ensureAccount(customerCode);
        lockAccount(customerCode);
        for (ArCashLine l : lines) {
            if (l == null || nz(l.amount).signum() == 0) {
                continue;
            }
            String sourceBill = l.sourceBill == null ? "" : l.sourceBill;
            if (sourceBill.isEmpty()) {
                sourceBill = queryOneString("SELECT source_bill FROM fin_ar WHERE ar_no=?", l.arNo);
            }
            String orderNo = queryOneString(
                    "SELECT source_order_no FROM sales_receipt WHERE receipt_no=?", sourceBill);
            CustomerAccountFlowLine line = new CustomerAccountFlowLine(
                    CustomerAccountConst.ACCOUNT_AR, CustomerAccountConst.AR_SETTLE_CASH,
                    customerCode, customerName, "ARR:" + l.recordId);
            line.setDecreaseAmount(nz(l.amount));
            line.setArNo(l.arNo);
            line.setSourceBill(sourceBill);
            if (!orderNo.isEmpty()) {
                line.setOrderNo(orderNo);
                line.setDeliveryNo(sourceBill);
            }
            line.setReceiptNo(l.receiptNo);
            line.setReconcileId(l.recordId);
            line.setPostDate(postDate);
            line.setSummary("收款结算 " + (l.receiptNo == null || l.receiptNo.isEmpty() ? l.arNo : l.receiptNo));
            writeFlow(line);
            resetFormingStatusByAr(l.arNo);
        }
    }

    /**
     * 收款单反审核冲回现金结算流水：原 AR_SETTLE_CASH 置 REVERSED，逐行追加红字 AR_REVERSE
     * （increase，应收加回），形成行结算标志按 fin_ar 当前真值复位。
     * 调用方须先删除核销真值记录、回退 fin_ar，再调本方法。
     */
    @Transactional
    public void reverseArCashSettle(LocalDate postDate, String customerCode, String customerName,
                                    List<ArCashLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        ensureAccount(customerCode);
        lockAccount(customerCode);
        for (ArCashLine l : lines) {
            if (l == null || nz(l.amount).signum() == 0) {
                continue;
            }
            String bizKey = "ARR:" + l.recordId;
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM fin_customer_account_flow "
                    + "WHERE biz_key=? AND reverse_status=?", Integer.class, bizKey,
                    CustomerAccountConst.REVERSE_NORMAL);
            if (exists == null || exists == 0) {
                // 已冲回或在线流水缺失（历史单走数据修复）：跳过，避免重复红字
                continue;
            }
            jdbc.update("UPDATE fin_customer_account_flow SET reverse_status=? WHERE biz_key=?",
                    CustomerAccountConst.REVERSE_REVERSED, bizKey);
            String sourceBill = l.sourceBill == null ? "" : l.sourceBill;
            if (sourceBill.isEmpty()) {
                sourceBill = queryOneString("SELECT source_bill FROM fin_ar WHERE ar_no=?", l.arNo);
            }
            CustomerAccountFlowLine red = new CustomerAccountFlowLine(
                    CustomerAccountConst.ACCOUNT_AR, CustomerAccountConst.AR_REVERSE,
                    customerCode, customerName, "ARRR:" + l.recordId);
            red.setIncreaseAmount(nz(l.amount));
            red.setArNo(l.arNo);
            red.setSourceBill(sourceBill);
            red.setReceiptNo(l.receiptNo);
            red.setPostDate(postDate);
            red.setRed(true);
            red.setSummary("收款结算冲回 " + (l.receiptNo == null || l.receiptNo.isEmpty()
                    ? l.arNo : l.receiptNo) + "（取消审核）");
            writeFlow(red);
            resetFormingStatusByAr(l.arNo);
        }
    }

    // ==================== 底座：账户与流水（M2/M3 使用） ====================

    /** 确保账户行存在（在册客户按档案建，历史 key 用空快照建），返回账户行。 */
    @Transactional
    public Map<String, Object> ensureAccount(String customerCode) {
        if (customerCode == null || customerCode.isEmpty()) {
            throw new IllegalArgumentException("客户编码不能为空");
        }
        // 注意：MERGE 对已存在行会用 SET/VALUES 覆盖余额，绝不能把余额列写成 0，
        // 否则在线预收流水会把账户上已有的应收/预收余额清零。已存在行必须回填自身余额。
        jdbc.update("MERGE INTO fin_customer_account (customer_code,customer_name,salesman,channel_type,"
                + "route_line,ar_balance,advance_balance,version,created_at,updated_at) KEY(customer_code) "
                + "SELECT c.customer_code,c.customer_name,c.salesman,c.channel_type,c.route_line,"
                + "COALESCE(a.ar_balance,0),COALESCE(a.advance_balance,0),COALESCE(a.version,0),"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM base_customer c "
                + "LEFT JOIN fin_customer_account a ON a.customer_code=c.customer_code "
                + "WHERE c.customer_code=?",
                customerCode);
        // 客户档案已删/名称兜底场景：仅在账户行不存在时插入，禁止覆盖已有行
        jdbc.update("INSERT INTO fin_customer_account(customer_code,customer_name,ar_balance,advance_balance,"
                + "version,created_at,updated_at) "
                + "SELECT ?,?,0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS ("
                + "SELECT 1 FROM fin_customer_account WHERE customer_code=?)",
                customerCode, customerCode, customerCode);
        refreshSnapshot(customerCode);
        return TmsUtil.queryCamel(jdbc,
                "SELECT customer_code, customer_name, ar_balance, advance_balance "
                        + "FROM fin_customer_account WHERE customer_code=?", customerCode).get(0);
    }

    /** 同客户并发串行化（审核类事务先锁账户再写流水）。 */
    public void lockAccount(String customerCode) {
        jdbc.queryForObject("SELECT customer_code FROM fin_customer_account WHERE customer_code=? FOR UPDATE",
                String.class, customerCode);
    }

    /** 写一笔流水并滚存 balance_after；调用方需在事务内先 {@link #lockAccount}。 */
    @Transactional
    public void writeFlow(CustomerAccountFlowLine line) {
        if (line.getCustomerCode() == null || line.getCustomerCode().isEmpty()) {
            throw new IllegalArgumentException("流水缺少客户编码");
        }
        if (line.getBizKey() == null || line.getBizKey().isEmpty()) {
            throw new IllegalArgumentException("流水缺少幂等键");
        }
        ensureAccount(line.getCustomerCode());
        BigDecimal prev = currentBalance(line.getCustomerCode(), line.getAccountType());
        BigDecimal balanceAfter = prev.add(line.getIncreaseAmount()).subtract(line.getDecreaseAmount());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime occurred = line.getOccurredAt() == null ? now : line.getOccurredAt();
        LocalDate postDate = line.getPostDate() == null ? occurred.toLocalDate() : line.getPostDate();
        String operator = line.getOperatorName() == null ? TmsUtil.currentUser() : line.getOperatorName();
        jdbc.update("INSERT INTO fin_customer_account_flow (flow_id,customer_code,customer_name,account_type,"
                        + "biz_type,increase_amount,decrease_amount,balance_after,settle_status,settled_amount,"
                        + "ar_no,source_bill,order_no,delivery_no,receipt_no,writeoff_no,reconcile_id,post_date,"
                        + "occurred_at,summary,operator_name,is_red,reverse_status,biz_key,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                TmsUtil.uuid("CAF"), line.getCustomerCode(), line.getCustomerName(), line.getAccountType(),
                line.getBizType(), line.getIncreaseAmount(), line.getDecreaseAmount(), balanceAfter,
                line.getSettleStatus(), line.getSettledAmount(), line.getArNo(), line.getSourceBill(),
                line.getOrderNo(), line.getDeliveryNo(), line.getReceiptNo(), line.getWriteoffNo(),
                line.getReconcileId(), java.sql.Date.valueOf(postDate), Timestamp.valueOf(occurred),
                line.getSummary(), operator, line.isRed() ? "Y" : "N",
                CustomerAccountConst.REVERSE_NORMAL, line.getBizKey());
        // 在线流水始终追加在链尾（occurred_at=当前时刻），同步账户余额缓存列
        jdbc.update("UPDATE fin_customer_account SET "
                + (CustomerAccountConst.ACCOUNT_AR.equals(line.getAccountType())
                        ? "ar_balance=?" : "advance_balance=?")
                + ", updated_at=CURRENT_TIMESTAMP WHERE customer_code=?", balanceAfter,
                line.getCustomerCode());
    }

    /**
     * 重排某客户某账户的余额链（按发生时刻+形成优先），返回末笔余额。
     * 用于数据修复与反审核冲回后的链修复。
     */
    @Transactional
    public BigDecimal rebuildChain(String customerCode, String accountType) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT flow_id, increase_amount, decrease_amount FROM fin_customer_account_flow "
                        + "WHERE customer_code=? AND account_type=? ORDER BY " + CHAIN_ORDER,
                customerCode, accountType);
        BigDecimal balance = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            balance = balance.add(nz(TmsUtil.toBd(row.get("increaseAmount"))))
                    .subtract(nz(TmsUtil.toBd(row.get("decreaseAmount"))));
            jdbc.update("UPDATE fin_customer_account_flow SET balance_after=? WHERE flow_id=?",
                    balance, row.get("flowId"));
        }
        return balance;
    }

    /** 账户实时余额（末笔滚存；无流水为 0）。 */
    public BigDecimal currentBalance(String customerCode, String accountType) {
        List<BigDecimal> last = jdbc.query(
                "SELECT balance_after FROM fin_customer_account_flow"
                        + " WHERE customer_code=? AND account_type=? ORDER BY occurred_at DESC, "
                        + "CASE WHEN biz_type IN ('AR_SIGN','AR_RETURN','AR_EXPENSE','AR_OPENING') "
                        + "THEN 0 ELSE 1 END DESC, flow_id DESC LIMIT 1",
                (rs, i) -> rs.getBigDecimal("balance_after"), customerCode, accountType);
        return last.isEmpty() || last.get(0) == null ? BigDecimal.ZERO : last.get(0);
    }

    // ==================== 内部：客户归属解析（口径同 V124 回填） ====================

    /** fin_ar 行 → 客户编码：发货→退货→费用→档案名称→名称兜底。 */
    public String resolveArCustomerCode(Map<String, Object> ar) {
        String sourceBill = TmsUtil.str(ar.get("sourceBill"));
        String customerName = TmsUtil.str(ar.get("customer"));
        return resolveBySource(sourceBill, customerName);
    }

    /** 按 ar_no 取应收行再解析（结算流水归属）。 */
    public String resolveArCustomerCodeByArNo(String arNo) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT source_bill, customer FROM fin_ar WHERE ar_no=?", arNo);
        return rows.isEmpty() ? "" : resolveBySource(
                TmsUtil.str(rows.get(0).get("sourceBill")), TmsUtil.str(rows.get(0).get("customer")));
    }

    private String resolveBySource(String sourceBill, String customerName) {
        String code = queryOneString("SELECT customer_code FROM sales_receipt WHERE receipt_no=?", sourceBill);
        if (code.isEmpty()) {
            code = queryOneString("SELECT customer_code FROM sales_return_apply WHERE apply_no=?", sourceBill);
        }
        if (code.isEmpty()) {
            code = queryOneString("SELECT counterparty_code FROM fin_expense_bill "
                    + "WHERE expense_no=? AND counterparty_type='CUSTOMER'", sourceBill);
        }
        if (code.isEmpty()) {
            code = queryOneString("SELECT customer_code FROM base_customer WHERE customer_name=?", customerName);
        }
        return code.isEmpty() ? customerName : code;
    }

    private String queryOneString(String sql, Object arg) {
        List<String> list = jdbc.query(sql, (rs, i) -> rs.getString(1), arg);
        if (list.isEmpty() || list.get(0) == null) {
            return "";
        }
        return list.get(0).trim();
    }

    /** 修复补缺：从 fin_ar 行插入形成流水（口径与 V124 第 6.1 节一致）。 */
    private void insertFormingFlowFromAr(Map<String, Object> ar, String code, String bizKey) {
        String sourceBill = TmsUtil.str(ar.get("sourceBill"));
        String bizType = CustomerAccountConst.AR_SIGN;
        if (sourceBill.startsWith("QCAR-")) {
            bizType = CustomerAccountConst.AR_OPENING;
        } else if (!queryOneString("SELECT apply_no FROM sales_return_apply WHERE apply_no=?", sourceBill).isEmpty()) {
            bizType = CustomerAccountConst.AR_RETURN;
        } else if (!queryOneString("SELECT expense_no FROM fin_expense_bill WHERE expense_no=?", sourceBill).isEmpty()) {
            bizType = CustomerAccountConst.AR_EXPENSE;
        }
        BigDecimal arAmount = nz(TmsUtil.toBd(ar.get("arAmount")));
        BigDecimal received = nz(TmsUtil.toBd(ar.get("receivedAmount")));
        BigDecimal unreceived = nz(TmsUtil.toBd(ar.get("unreceivedAmount")));
        String status;
        if (unreceived.signum() == 0) {
            status = CustomerAccountConst.SETTLE_DONE;
        } else if (received.signum() == 0) {
            status = CustomerAccountConst.SETTLE_UNSETTLED;
        } else {
            status = CustomerAccountConst.SETTLE_PART;
        }
        String orderNo = queryOneString("SELECT source_order_no FROM sales_receipt WHERE receipt_no=?", sourceBill);
        Object createdAtRaw = ar.get("createdAt");
        LocalDateTime occurred = createdAtRaw instanceof Timestamp ts ? ts.toLocalDateTime() : LocalDateTime.now();
        String summary;
        switch (bizType) {
            case CustomerAccountConst.AR_OPENING -> summary = "期初应收 " + TmsUtil.str(ar.get("arNo"));
            case CustomerAccountConst.AR_RETURN -> summary = "销售退货红冲 " + sourceBill;
            case CustomerAccountConst.AR_EXPENSE -> summary = "费用挂账 " + sourceBill;
            default -> summary = "订单出库签收 " + sourceBill;
        }
        jdbc.update("INSERT INTO fin_customer_account_flow (flow_id,customer_code,customer_name,account_type,"
                        + "biz_type,increase_amount,decrease_amount,balance_after,settle_status,settled_amount,"
                        + "ar_no,source_bill,order_no,delivery_no,post_date,occurred_at,summary,is_red,"
                        + "reverse_status,biz_key,created_at) SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                        + "CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM fin_customer_account_flow WHERE biz_key=?)",
                TmsUtil.uuid("CAF"), code, TmsUtil.str(ar.get("customer")),
                CustomerAccountConst.ACCOUNT_AR, bizType, arAmount, BigDecimal.ZERO, null,
                status, received, TmsUtil.str(ar.get("arNo")), sourceBill,
                orderNo.isEmpty() ? null : orderNo, sourceBill,
                java.sql.Date.valueOf(occurred.toLocalDate()), Timestamp.valueOf(occurred), summary,
                arAmount.signum() < 0 ? "Y" : "N", CustomerAccountConst.REVERSE_NORMAL, bizKey, bizKey);
        // balance_after 由随后调用的 rebuildChain 统一重排
    }

    /** 修复补缺：从核销记录插入结算流水（口径与 V124 第 6.2 节一致）。 */
    private void insertSettleFlowFromRecord(Map<String, Object> rec, String code, String arNo, String bizKey) {
        BigDecimal amount = nz(TmsUtil.toBd(rec.get("reconcileAmount")));
        boolean writeoff = "EXPENSE_WRITEOFF".equals(TmsUtil.str(rec.get("businessType")));
        String bizType = writeoff ? CustomerAccountConst.AR_WRITEOFF : CustomerAccountConst.AR_SETTLE_CASH;
        String sourceBill = TmsUtil.str(rec.get("sourceBill"));
        if (sourceBill.isEmpty()) {
            sourceBill = queryOneString("SELECT source_bill FROM fin_ar WHERE ar_no=?", arNo);
        }
        String orderNo = queryOneString("SELECT source_order_no FROM sales_receipt WHERE receipt_no=?", sourceBill);
        String customerName = TmsUtil.str(rec.get("counterpartyName"));
        if (customerName.isEmpty()) {
            customerName = queryOneString("SELECT customer FROM fin_ar WHERE ar_no=?", arNo);
        }
        Object createdAtRaw = rec.get("createdAt");
        LocalDateTime occurred = createdAtRaw instanceof Timestamp ts ? ts.toLocalDateTime() : LocalDateTime.now();
        LocalDate postDate = rec.get("receiptDate") instanceof java.sql.Date d
                ? d.toLocalDate() : occurred.toLocalDate();
        String receiptNo = TmsUtil.str(rec.get("receiptNo"));
        jdbc.update("INSERT INTO fin_customer_account_flow (flow_id,customer_code,customer_name,account_type,"
                        + "biz_type,increase_amount,decrease_amount,balance_after,ar_no,source_bill,order_no,"
                        + "delivery_no,receipt_no,reconcile_id,post_date,occurred_at,summary,is_red,"
                        + "reverse_status,biz_key,created_at) SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                        + "CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM fin_customer_account_flow WHERE biz_key=?)",
                TmsUtil.uuid("CAF"), code, customerName, CustomerAccountConst.ACCOUNT_AR, bizType,
                BigDecimal.ZERO, amount, null, arNo, sourceBill,
                orderNo.isEmpty() ? null : orderNo, sourceBill,
                receiptNo.isEmpty() ? null : receiptNo, TmsUtil.str(rec.get("recordId")),
                java.sql.Date.valueOf(postDate), Timestamp.valueOf(occurred),
                (writeoff ? "抹零核销 " : "收款结算 ") + receiptNo, "N",
                CustomerAccountConst.REVERSE_NORMAL, bizKey, bizKey);
    }

    /** 刷新账户档案快照（改名/改业务员/渠道/线路）。 */
    private void refreshSnapshot(String customerCode) {
        jdbc.update("UPDATE fin_customer_account SET "
                + "customer_name=COALESCE((SELECT customer_name FROM base_customer WHERE customer_code=?),"
                + "customer_name), "
                + "salesman=(SELECT salesman FROM base_customer WHERE customer_code=?), "
                + "channel_type=(SELECT channel_type FROM base_customer WHERE customer_code=?), "
                + "route_line=(SELECT route_line FROM base_customer WHERE customer_code=?), "
                + "updated_at=CURRENT_TIMESTAMP WHERE customer_code=?",
                customerCode, customerCode, customerCode, customerCode, customerCode);
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
