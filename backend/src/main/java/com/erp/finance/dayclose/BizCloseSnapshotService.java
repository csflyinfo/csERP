package com.erp.finance.dayclose;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

/**
 * 业务日结定版快照重建（PRD-33 §6.1/§7.3）。
 *
 * <p>三张定版日余额表（客户应收 / 供应商应付 / 资金账户）统一「先按日 DELETE 再插入」，
 * 幂等可重跑。必须在结账事务内调用：
 * <ul>
 *   <li>应收/应付取 {@code fin_ar}/{@code fin_ap} 当前余额按往来单位聚合（封单链保证
 *       结账时刻即该日时点状态），名称关联档案补 code，关联不到合并为 code=UNKNOWN；</li>
 *   <li>负余额重分类为预收/预付；只聚合未结清单据（unreceived/unpaid ≠ 0）；</li>
 *   <li>资金按收/付款记账日期归属（fin_fund_ledger.occurred_at 日期部分），
 *       以账户档案余额倒推历史期初，按发生顺序滚算 open/in/out/close，
 *       同时提供 {@link #verifyFundChain} 做「流水自身滚平」硬勾稽。</li>
 * </ul>
 */
@Service
public class BizCloseSnapshotService {

    /** 名称匹配不到往来档案时的兜底 code（多行合并一行，明细在向导第 3 步列出）。 */
    public static final String UNKNOWN_CODE = "UNKNOWN";

    private final JdbcTemplate jdbcTemplate;

    public BizCloseSnapshotService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ============================================================
    // 应收日余额
    // ============================================================

    /** 先删后插某日客户应收定版余额。 */
    public void rebuildAr(LocalDate date) {
        jdbcTemplate.update("DELETE FROM biz_close_ar_daily WHERE close_date = ?", java.sql.Date.valueOf(date));

        // fin_ar 只存客户名称，code 靠档案名称关联
        Map<String, String> nameToCode = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT customer_code, customer_name FROM base_customer").forEach(r ->
                nameToCode.put(str(r.get("CUSTOMER_NAME")), str(r.get("CUSTOMER_CODE"))));

        // 客户账户预收余额（PRD-35 M4）：定版预收直接取客户账户 advance_balance，
        // 不再依赖负应收重分类；负应收重分类列 advance_amount 保留作兜底核对
        Map<String, BigDecimal> advanceByCode = new LinkedHashMap<>();
        Map<String, String> accountNameByCode = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT customer_code, customer_name, advance_balance FROM fin_customer_account "
                        + "WHERE COALESCE(advance_balance,0) <> 0").forEach(r -> {
            String code = str(r.get("CUSTOMER_CODE"));
            if (!code.isEmpty()) {
                advanceByCode.put(code, bd(r.get("ADVANCE_BALANCE")));
                accountNameByCode.put(code, str(r.get("CUSTOMER_NAME")));
            }
        });

        // 只聚合未结清单据（unreceived ≠ 0，含退货红负）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT customer, ar_amount, received_amount, unreceived_amount, due_date FROM fin_ar "
                        + "WHERE unreceived_amount <> 0");
        // code -> 聚合行
        Map<String, ArAgg> aggMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = str(row.get("CUSTOMER"));
            if (name.isBlank()) name = "(空名称)";
            String code = nameToCode.getOrDefault(name, UNKNOWN_CODE);
            final String partyName = name;
            ArAgg agg = aggMap.computeIfAbsent(code, k -> new ArAgg(k,
                    UNKNOWN_CODE.equals(k) ? "(未匹配客户合计)" : partyName));
            BigDecimal unreceived = bd(row.get("UNRECEIVED_AMOUNT"));
            agg.arAmount = agg.arAmount.add(bd(row.get("AR_AMOUNT")));
            agg.received = agg.received.add(bd(row.get("RECEIVED_AMOUNT")));
            agg.net = agg.net.add(unreceived);
            agg.billCount++;
            // 逾期：正余额且到期日 ≤ 结账日（负余额/预收不计逾期）
            if (unreceived.signum() > 0) {
                Object due = row.get("DUE_DATE");
                LocalDate dueDate = BizDayCloseGuard.toLocalDate(due);
                if (dueDate != null && !dueDate.isAfter(date)) {
                    agg.overdue = agg.overdue.add(unreceived);
                }
            }
        }
        // 只有预收余额、没有未结清应收的客户也要入定版行（否则预收定版漏户）
        for (Map.Entry<String, BigDecimal> e : advanceByCode.entrySet()) {
            if (e.getValue().signum() <= 0) continue;
            aggMap.computeIfAbsent(e.getKey(), k -> {
                String nm = accountNameByCode.getOrDefault(k, "");
                return new ArAgg(k, nm.isBlank() ? k : nm);
            });
        }
        for (ArAgg agg : aggMap.values()) {
            jdbcTemplate.update("""
                    INSERT INTO biz_close_ar_daily(close_date, customer_code, customer_name,
                        ar_amount, received_amount, unreceived_amount, advance_amount,
                        advance_account_balance, overdue_amount, bill_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, java.sql.Date.valueOf(date), agg.code, agg.name,
                    money(agg.arAmount), money(agg.received),
                    money(agg.net.signum() > 0 ? agg.net : BigDecimal.ZERO),
                    money(agg.net.signum() < 0 ? agg.net.negate() : BigDecimal.ZERO),
                    money(advanceByCode.getOrDefault(agg.code, BigDecimal.ZERO)),
                    money(agg.overdue), agg.billCount);
        }
    }

    /** 应收聚合中间结构。 */
    private static final class ArAgg {
        final String code;
        final String name;
        BigDecimal arAmount = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        int billCount;

        ArAgg(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    // ============================================================
    // 应付日余额
    // ============================================================

    /** 先删后插某日供应商应付定版余额。 */
    public void rebuildAp(LocalDate date) {
        jdbcTemplate.update("DELETE FROM biz_close_ap_daily WHERE close_date = ?", java.sql.Date.valueOf(date));

        Map<String, String> nameToCode = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT supplier_code, supplier_name FROM base_supplier").forEach(r ->
                nameToCode.put(str(r.get("SUPPLIER_NAME")), str(r.get("SUPPLIER_CODE"))));

        // 供应商账户预付/费用余额直接取账户缓存列（与应收侧 advance_balance 同范式）：
        // 只有预付/费用余额、没有未结清应付的供应商也要入定版行；与负应付重分类列 prepaid_amount 分列。
        Map<String, BigDecimal> prepayByCode = new LinkedHashMap<>();
        Map<String, BigDecimal> expenseByCode = new LinkedHashMap<>();
        Map<String, String> accountNameByCode = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT supplier_code, supplier_name, prepay_balance, expense_balance "
                        + "FROM fin_supplier_account "
                        + "WHERE COALESCE(prepay_balance,0) <> 0 OR COALESCE(expense_balance,0) <> 0")
                .forEach(r -> {
                    String code = str(r.get("SUPPLIER_CODE"));
                    if (!code.isEmpty()) {
                        prepayByCode.put(code, bd(r.get("PREPAY_BALANCE")));
                        expenseByCode.put(code, bd(r.get("EXPENSE_BALANCE")));
                        accountNameByCode.put(code, str(r.get("SUPPLIER_NAME")));
                    }
                });

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT supplier, ap_amount, paid_amount, unpaid_amount, due_date FROM fin_ap "
                        + "WHERE unpaid_amount <> 0");
        Map<String, ApAgg> aggMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = str(row.get("SUPPLIER"));
            if (name.isBlank()) name = "(空名称)";
            String code = nameToCode.getOrDefault(name, UNKNOWN_CODE);
            final String partyName = name;
            ApAgg agg = aggMap.computeIfAbsent(code, k -> new ApAgg(k,
                    UNKNOWN_CODE.equals(k) ? "(未匹配供应商合计)" : partyName));
            BigDecimal unpaid = bd(row.get("UNPAID_AMOUNT"));
            agg.apAmount = agg.apAmount.add(bd(row.get("AP_AMOUNT")));
            agg.paid = agg.paid.add(bd(row.get("PAID_AMOUNT")));
            agg.net = agg.net.add(unpaid);
            agg.billCount++;
            if (unpaid.signum() > 0) {
                LocalDate dueDate = BizDayCloseGuard.toLocalDate(row.get("DUE_DATE"));
                if (dueDate != null && !dueDate.isAfter(date)) {
                    agg.overdue = agg.overdue.add(unpaid);
                }
            }
        }
        // 账户有预付/费用余额但无未结清应付（或负应付已被重分类）的供应商补入定版行
        Set<String> accountCodes = new LinkedHashSet<>();
        accountCodes.addAll(prepayByCode.keySet());
        accountCodes.addAll(expenseByCode.keySet());
        for (String code : accountCodes) {
            aggMap.computeIfAbsent(code, k -> {
                String nm = accountNameByCode.getOrDefault(k, "");
                return new ApAgg(k, nm.isBlank() ? k : nm);
            });
        }
        for (ApAgg agg : aggMap.values()) {
            jdbcTemplate.update("""
                    INSERT INTO biz_close_ap_daily(close_date, supplier_code, supplier_name,
                        ap_amount, paid_amount, unpaid_amount, prepaid_amount,
                        prepay_account_balance, expense_account_balance,
                        overdue_amount, bill_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, java.sql.Date.valueOf(date), agg.code, agg.name,
                    money(agg.apAmount), money(agg.paid),
                    money(agg.net.signum() > 0 ? agg.net : BigDecimal.ZERO),
                    money(agg.net.signum() < 0 ? agg.net.negate() : BigDecimal.ZERO),
                    money(prepayByCode.getOrDefault(agg.code, BigDecimal.ZERO)),
                    money(expenseByCode.getOrDefault(agg.code, BigDecimal.ZERO)),
                    money(agg.overdue), agg.billCount);
        }
    }

    /** 应付聚合中间结构。 */
    private static final class ApAgg {
        final String code;
        final String name;
        BigDecimal apAmount = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        int billCount;

        ApAgg(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    // ============================================================
    // 资金账户日余额
    // ============================================================

    /** 资金日余额行（滚算结果，供落库与勾稽共用）。 */
    public static final class FundRow {
        public String accountName;
        public String accountCode;
        public BigDecimal openBalance = BigDecimal.ZERO;
        public BigDecimal inAmount = BigDecimal.ZERO;
        public BigDecimal outAmount = BigDecimal.ZERO;
        public BigDecimal closeBalance = BigDecimal.ZERO;
        public BigDecimal cashCount;
    }

    /**
     * 先删后插某日资金定版余额。
     *
     * @param date          结账日
     * @param cashCountByName 现金实盘数（key=账户名称，向导第 6 步备注项；自动日结传空 map）
     */
    public void rebuildFund(LocalDate date, Map<String, BigDecimal> cashCountByName) {
        jdbcTemplate.update("DELETE FROM biz_close_fund_daily WHERE close_date = ?",
                java.sql.Date.valueOf(date));
        List<FundRow> rows = loadFundRows(date);
        for (FundRow row : rows) {
            row.cashCount = cashCountByName == null ? null : cashCountByName.get(row.accountName);
            jdbcTemplate.update("""
                    INSERT INTO biz_close_fund_daily(close_date, fund_account_code, fund_account_name,
                        open_balance, in_amount, out_amount, close_balance, cash_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, java.sql.Date.valueOf(date), row.accountCode, row.accountName,
                    money(row.openBalance), money(row.inAmount), money(row.outAmount),
                    money(row.closeBalance),
                    row.cashCount == null ? null : money(row.cashCount));
        }
    }

    /**
     * 滚算截至 date 各账户的 open/in/out/close。
     *
     * <p>期初倒推：账户档案当前余额 − 该账户全部流水净额（含 date 之后的流水），
     * 这样即使补录往日记账（流水发生时间早于物理插入时间），历史每日余额仍可正确滚算。
     * 账户范围 = 流水出现过的账户 ∪ 档案中全部正常账户。
     */
    public List<FundRow> loadFundRows(LocalDate date) {
        // 账户档案：名称/编码双向可匹配 fin_fund_ledger.fund_account（历史存名称）
        Map<String, AccountArchive> archives = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT fund_account_code, fund_account_name, balance, status FROM base_fund_account")) {
            AccountArchive a = new AccountArchive(
                    str(r.get("FUND_ACCOUNT_CODE")), str(r.get("FUND_ACCOUNT_NAME")),
                    bd(r.get("BALANCE")));
            archives.put(a.name, a);
            if (!a.code.isBlank()) archives.putIfAbsent(a.code, a);
        }

        // 每个账户的全部流水按发生时间排序（同毫秒按 ledger_no 兜底）
        Map<String, List<LedgerRow>> ledgerByAccount = new LinkedHashMap<>();
        jdbcTemplate.queryForList("""
                SELECT fund_account, ledger_no, direction, amount, balance_after, occurred_at
                FROM fin_fund_ledger
                ORDER BY fund_account, occurred_at, ledger_no
                """).forEach(r -> {
            String acct = str(r.get("FUND_ACCOUNT"));
            List<LedgerRow> list = ledgerByAccount.computeIfAbsent(acct, k -> new ArrayList<>());
            list.add(new LedgerRow(str(r.get("LEDGER_NO")), str(r.get("DIRECTION")),
                    bd(r.get("AMOUNT")), bd(r.get("BALANCE_AFTER")),
                    r.get("OCCURRED_AT")));
        });

        List<FundRow> result = new ArrayList<>();
        // 账户全集：档案账户 + 流水账户
        Map<String, FundRow> rowByKey = new LinkedHashMap<>();
        for (AccountArchive a : archives.values()) {
            // archives 同时按 name/code 放了两份，同名去重
            if (rowByKey.containsKey(a.code)) continue;
            FundRow row = new FundRow();
            row.accountCode = a.code.isBlank() ? a.name : a.code;
            row.accountName = a.name;
            // 无流水账户：open=close=档案当前余额（结账日之后无流水时即该日期末余额）
            row.openBalance = a.balance;
            row.closeBalance = a.balance;
            rowByKey.put(row.accountCode, row);
        }

        for (Map.Entry<String, List<LedgerRow>> e : ledgerByAccount.entrySet()) {
            String acct = e.getKey();
            AccountArchive archive = archives.get(acct);
            String code = archive != null && !archive.code.isBlank() ? archive.code : acct;
            String name = archive != null ? archive.name : acct;
            FundRow row = rowByKey.computeIfAbsent(code, k -> {
                FundRow nr = new FundRow();
                nr.accountCode = k;
                nr.accountName = name;
                return nr;
            });
            row.accountName = name;

            List<LedgerRow> ledgers = e.getValue();
            // 全部流水净额（含 date 之后），用于倒推历史期初
            BigDecimal netAll = BigDecimal.ZERO;
            for (LedgerRow l : ledgers) {
                netAll = "IN".equals(l.direction) ? netAll.add(l.amount) : netAll.subtract(l.amount);
            }
            BigDecimal archiveBalance = archive != null ? archive.balance : BigDecimal.ZERO;
            // 历史期初 = 档案当前余额 − 全部流水净额（含 date 之后）
            BigDecimal running = archiveBalance.subtract(netAll);
            BigDecimal open = null;
            BigDecimal close = running;
            for (LedgerRow l : ledgers) {
                LocalDate rowDate = l.occurredAt == null ? null : l.occurredAt.toLocalDate();
                if (rowDate != null && rowDate.isEqual(date) && open == null) {
                    // 日初 = 上一日末余额
                    open = running;
                }
                if ("IN".equals(l.direction)) {
                    running = running.add(l.amount);
                } else {
                    running = running.subtract(l.amount);
                }
                if (rowDate != null && rowDate.isEqual(date)) {
                    if ("IN".equals(l.direction)) {
                        row.inAmount = row.inAmount.add(l.amount);
                    } else {
                        row.outAmount = row.outAmount.add(l.amount);
                    }
                }
                if (rowDate != null && !rowDate.isAfter(date)) {
                    close = running;
                }
            }
            // 当天无流水：日初=日末=截至上日累计
            row.openBalance = open != null ? open : close;
            row.closeBalance = close;
        }
        result.addAll(rowByKey.values());
        return result;
    }

    /**
     * 资金流水余额链完整性勾稽（硬项，容差 0.01）：按发生时间顺序重放，
     * 任一行 balance_after ≠ 上日末余额 ± 本行金额即不平（典型成因：绕过服务直改 SQL）。
     * 只校验发生日 ≤ date 的行；date 之后的流水不影响当日定版。
     *
     * @return 不平明细（空=平）
     */
    public List<Map<String, Object>> verifyFundChain(LocalDate date) {
        List<Map<String, Object>> diffs = new ArrayList<>();
        Map<String, AccountArchive> archives = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT fund_account_code, fund_account_name, balance FROM base_fund_account")) {
            AccountArchive a = new AccountArchive(
                    str(r.get("FUND_ACCOUNT_CODE")), str(r.get("FUND_ACCOUNT_NAME")),
                    bd(r.get("BALANCE")));
            archives.put(a.name, a);
            if (!a.code.isBlank()) archives.putIfAbsent(a.code, a);
        }
        List<Map<String, Object>> accts = jdbcTemplate.queryForList(
                "SELECT DISTINCT fund_account FROM fin_fund_ledger");
        for (Map<String, Object> acctRow : accts) {
            String acct = str(acctRow.get("FUND_ACCOUNT"));
            List<LedgerRow> ledgers = new ArrayList<>();
            jdbcTemplate.queryForList(
                    "SELECT ledger_no, direction, amount, balance_after, occurred_at "
                            + "FROM fin_fund_ledger WHERE fund_account = ? "
                            + "ORDER BY occurred_at, ledger_no", acct).forEach(r ->
                    ledgers.add(new LedgerRow(str(r.get("LEDGER_NO")), str(r.get("DIRECTION")),
                            bd(r.get("AMOUNT")), bd(r.get("BALANCE_AFTER")), r.get("OCCURRED_AT"))));
            BigDecimal netAll = BigDecimal.ZERO;
            for (LedgerRow l : ledgers) {
                netAll = "IN".equals(l.direction) ? netAll.add(l.amount) : netAll.subtract(l.amount);
            }
            AccountArchive archive = archives.get(acct);
            BigDecimal running = (archive != null ? archive.balance : BigDecimal.ZERO).subtract(netAll);
            for (LedgerRow l : ledgers) {
                running = "IN".equals(l.direction) ? running.add(l.amount) : running.subtract(l.amount);
                LocalDate rowDate = l.occurredAt == null ? null : l.occurredAt.toLocalDate();
                if (rowDate != null && !rowDate.isAfter(date)
                        && running.subtract(l.balanceAfter).abs().doubleValue() > BizDayCloseConst.TIE_TOLERANCE) {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    diff.put("fundAccount", acct);
                    diff.put("ledgerNo", l.ledgerNo);
                    diff.put("occurredAt", l.occurredAt == null ? null : l.occurredAt.toString());
                    diff.put("storedBalance", money(l.balanceAfter));
                    diff.put("expectedBalance", money(running));
                    diff.put("diff", money(running.subtract(l.balanceAfter)));
                    diffs.add(diff);
                }
            }
        }
        return diffs;
    }

    /** 账户档案中间结构。 */
    private record AccountArchive(String code, String name, BigDecimal balance) {
    }

    /** 流水行中间结构。 */
    private static final class LedgerRow {
        final String ledgerNo;
        final String direction;
        final BigDecimal amount;
        final BigDecimal balanceAfter;
        final LocalDateTime occurredAt;

        LedgerRow(String ledgerNo, String direction, BigDecimal amount, BigDecimal balanceAfter,
                  Object occurredAt) {
            this.ledgerNo = ledgerNo;
            this.direction = direction;
            this.amount = amount;
            this.balanceAfter = balanceAfter;
            if (occurredAt instanceof Timestamp ts) {
                this.occurredAt = ts.toLocalDateTime();
            } else if (occurredAt instanceof LocalDateTime ldt) {
                this.occurredAt = ldt;
            } else if (occurredAt instanceof java.sql.Date sd) {
                this.occurredAt = sd.toLocalDate().atStartOfDay();
            } else {
                this.occurredAt = null;
            }
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        return new BigDecimal(s);
    }

    /** 金额统一两位小数。 */
    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
