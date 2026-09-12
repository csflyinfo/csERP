package com.erp.report.finance;

import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.Plan;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 报表22｜现金日记账（标准三栏式：收入/支出/余额，按资金账户整期一次返回）。
 *
 * <p>数据源 fin_fund_ledger（流水只追加，取消审核写反向冲销行，source_bill 带「(取消审核)」后缀），
 * LEFT JOIN 收款单/付款单/费用单补摘要、对方单位、收支项目。
 *
 * <p>一条 SQL 用 UNION ALL 拼出账簿全部行（无窗口函数，规避 H2 2.2「窗口函数+绑定参数」缺陷）：
 * <ul>
 *   <li>OPENING：每账户一行期初余额（期间首日 0 点前最近 balance_after，回退账户档案余额）；</li>
 *   <li>DAT：逐笔流水；余额按「期初 + 期间内逐笔收付」独立滚算（同刻按 ledger_id 兜底排序），
 *       与存量 balance_after 逐笔校验，差异超 1 分标 balance_check=MISMATCH；</li>
 *   <li>DAY：每日小计（日清）；MONTH：本月合计；YEAR：本年累计（跨月期间按月分段，
 *       月/年行排在当月最后一日的日小计之后）；</li>
 * </ul>
 * 当存在方向/收支项目/对方单位/单据号过滤时，小计与月年行仍按同一过滤口径汇总，
 * 但小计余额列留空、balance_check=FILTERED（过滤集不能还原全量账户余额，避免假红标）。
 *
 * <p>默认期间本月（自然月，与 #8/#9 月结类一致）；账户默认第一个现金类账户，可多选。
 */
@Component
public class FundJournalDefinition implements ReportDefinition {

    private static final List<String> ITEM_WHITELIST = List.of("收款", "付款", "冲销", "费用", "其他");

    private final JdbcTemplate jdbcTemplate;

    public FundJournalDefinition(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override public String code() { return "fund_journal"; }
    @Override public String name() { return "现金日记账"; }
    @Override public String viewPerm() { return "report.fund_journal.view"; }
    @Override public boolean dws() { return false; }
    @Override public boolean summaryReport() { return true; }
    @Override public boolean naturalMonthDefault() { return true; }

    @Override
    public List<ReportColumnDef> columns() {
        return new ArrayList<>(List.of(
                ReportColumnDef.dim("rowType", "行类型", false),
                ReportColumnDef.dim("fundAccount", "资金账户"),
                ReportColumnDef.dim("bizDate", "日期"),
                ReportColumnDef.dim("ledgerNo", "单据/流水号"),
                ReportColumnDef.dim("sourceBill", "来源单号"),
                ReportColumnDef.dim("summary", "摘要"),
                ReportColumnDef.dim("counterpartyName", "对方单位"),
                ReportColumnDef.dim("expenseItem", "收支项目"),
                ReportColumnDef.measure("inAmount", "收入金额", null),
                ReportColumnDef.measure("outAmount", "支出金额", null),
                ReportColumnDef.measure("balance", "余额", null),
                ReportColumnDef.dim("balanceCheck", "余额校验"),
                ReportColumnDef.dim("operatorName", "经办人"),
                ReportColumnDef.dim("sortTs", "排序时刻", false),
                ReportColumnDef.dim("sortNo", "排序序", false)));
    }

    @Override
    public Plan build(ReportQueryRequest req) {
        Plan plan = new Plan();
        LocalDate start = req.range().startDate();
        LocalDate end = req.range().endDate();

        // ---- 账户集合：显式多选优先；否则第一个现金类正常账户；显式传入但均已停用则空表 ----
        List<String> accounts = req.texts("fundAccount");
        if (accounts.isEmpty()) {
            accounts = jdbcTemplate.queryForList("""
                    SELECT fund_account_name FROM base_fund_account
                     WHERE status = 'NORMAL'
                     ORDER BY CASE WHEN account_type LIKE '%现金%' THEN 0 ELSE 1 END,
                              fund_account_code
                     LIMIT 1
                    """, String.class);
            if (accounts.isEmpty()) {
                throw new IllegalArgumentException("尚未维护状态正常的资金账户，无法输出日记账");
            }
        } else {
            List<String> exist = jdbcTemplate.queryForList("""
                    SELECT fund_account_name FROM base_fund_account
                     WHERE fund_account_name IN %s
                    """.formatted(FinanceReportSupport.placeholders(accounts.size())),
                    String.class, accounts.toArray());
            if (exist.isEmpty()) return plan.denyAll();
            accounts = exist;
        }
        String acctIn = FinanceReportSupport.placeholders(accounts.size());

        String direction = req.text("direction");
        if (direction != null && !"IN".equals(direction) && !"OUT".equals(direction)) {
            throw new IllegalArgumentException("收支方向只允许 IN（收入）/OUT（支出）");
        }
        String item = req.text("expenseItem");
        if (item != null && !ITEM_WHITELIST.contains(item)) {
            throw new IllegalArgumentException("收支项目只允许：收款/付款/冲销/费用/其他");
        }
        String counterparty = req.text("counterparty");
        String sourceBill = req.text("sourceBill");
        // 任何收窄 DAT 行的过滤都会让「全量余额」校验失效
        boolean detailFilter = direction != null || item != null
                || counterparty != null || sourceBill != null;

        Filters filters = new Filters(direction, item,
                counterparty == null ? null : "%" + counterparty + "%",
                sourceBill == null ? null : "%" + sourceBill + "%");

        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        appendOpening(sql, args, accounts, start);
        sql.append(" UNION ALL ");
        appendDat(sql, args, accounts, acctIn, start, end, filters);
        sql.append(" UNION ALL ");
        appendDay(sql, args, accounts, acctIn, start, end, filters, detailFilter);
        sql.append(" UNION ALL ");
        appendMonth(sql, args, accounts, acctIn, start, end, filters, detailFilter, false);
        sql.append(" UNION ALL ");
        appendMonth(sql, args, accounts, acctIn, start, end, filters, detailFilter, true);

        plan.detailSelect = "SELECT u.* FROM (";
        plan.fromWhere.append(sql).append(") u");
        plan.args.addAll(args);
        // 同账户同日期：OPENING(0)→DAT(1，再按时刻/流水号)→DAY(2)→MONTH(3)→YEAR(4)
        plan.defaultOrder = "ORDER BY u.fund_account, u.biz_date, u.sort_no, u.sort_ts, u.ledger_no";

        buildGrand(plan, accounts, acctIn, start, end);
        return plan;
    }

    /** DAT 过滤条件（参数化表别名，DAT/DAY/MONTH/YTD 各臂复用，口径强制一致）。 */
    private record Filters(String direction, String item, String counterpartyLike, String sourceBillLike) {
    }

    /**
     * 期间前基准余额表达式（每处出现含 1 个绑定参数：期间起点）。
     * 回退层级：①期间前最近一笔流水 balance_after
     *          → ②该账户最早一笔「balance_after − 本笔发生额」还原的初始余额
     *          → ③账户档案余额（仅在从无流水时取，此时档案余额即期初）→ 0。
     * 关键：base_fund_account.balance 是随单据审核滚动的<b>当前</b>余额，
     * 已有流水后直接拿它当期初会与逐笔滚算双重计算，首日就误报 MISMATCH。
     */
    private static String baseBefore(String acctExpr) {
        return """
                COALESCE((SELECT p2.balance_after FROM fin_fund_ledger p2
                           WHERE p2.fund_account = %s
                             AND p2.occurred_at < CAST(? AS TIMESTAMP)
                           ORDER BY p2.occurred_at DESC, p2.ledger_id DESC LIMIT 1),
                         (SELECT f0.balance_after - CASE f0.direction WHEN 'IN' THEN f0.amount
                                                                      ELSE -f0.amount END
                            FROM fin_fund_ledger f0
                           WHERE f0.fund_account = %s
                           ORDER BY f0.occurred_at ASC, f0.ledger_id ASC LIMIT 1),
                         (SELECT ba.balance FROM base_fund_account ba
                           WHERE ba.fund_account_name = %s), 0)
                """.formatted(acctExpr, acctExpr, acctExpr);
    }

    // ============================ 各 UNION 臂 ============================

    /** 期初行：每账户一行，余额取期间首日 0 点前最近流水余额，回退账户档案余额。 */
    private void appendOpening(StringBuilder sql, List<Object> args, List<String> accounts,
                               LocalDate start) {
        String scalar = baseBefore("fa.fund_account_name");
        sql.append("""
                SELECT 'OPENING' AS row_type, fa.fund_account_name AS fund_account,
                       CAST(? AS DATE) AS biz_date, CAST(? AS TIMESTAMP) AS sort_ts, 0 AS sort_no,
                       CAST(NULL AS VARCHAR) AS ledger_no, CAST(NULL AS VARCHAR) AS source_bill,
                       '期初余额' AS summary, CAST(NULL AS VARCHAR) AS counterparty_name,
                       CAST(NULL AS VARCHAR) AS expense_item,
                       CAST(0 AS DECIMAL(18,2)) AS in_amount,
                       CAST(0 AS DECIMAL(18,2)) AS out_amount,
                       (""").append(scalar).append(") AS balance,\n(")
           .append(scalar).append(") AS balance_calc,\n")
           .append("'OK' AS balance_check, CAST(NULL AS VARCHAR) AS operator_name\n")
           .append("""
                  FROM base_fund_account fa
                 WHERE fa.status = 'NORMAL' AND fa.fund_account_name IN """)
           .append(FinanceReportSupport.placeholders(accounts.size()));
        args.add(start);                                    // biz_date
        args.add(Timestamp.valueOf(start.atStartOfDay())); // sort_ts
        args.add(Timestamp.valueOf(start.atStartOfDay())); // scalar ×2
        args.add(Timestamp.valueOf(start.atStartOfDay()));
        args.addAll(accounts);
    }

    /** DAT：逐笔流水 + 独立滚算余额校验。 */
    private void appendDat(StringBuilder sql, List<Object> args, List<String> accounts, String acctIn,
                           LocalDate start, LocalDate end, Filters filters) {
        // 独立滚算余额（出现 2 次：balance_calc 与 MISMATCH 判断各一次；每次 2 个绑定参数）
        String calc = "(SELECT " + baseBefore("l.fund_account")
                + """
                         + COALESCE((SELECT SUM(CASE z.direction WHEN 'IN' THEN z.amount
                                                                 ELSE -z.amount END)
                                          FROM fin_fund_ledger z
                                         WHERE z.fund_account = l.fund_account
                                           AND z.occurred_at >= CAST(? AS TIMESTAMP)
                                           AND (z.occurred_at < l.occurred_at
                                                OR (z.occurred_at = l.occurred_at
                                                    AND z.ledger_id <= l.ledger_id))), 0))
                """;
        sql.append("""
                SELECT 'DAT' AS row_type, l.fund_account AS fund_account,
                       CAST(l.occurred_at AS DATE) AS biz_date, l.occurred_at AS sort_ts, 1 AS sort_no,
                       l.ledger_no AS ledger_no, l.source_bill AS source_bill,
                       COALESCE(NULLIF(r.summary,''), NULLIF(p.summary,''),
                                NULLIF(e.remark,''), l.source_bill) AS summary,
                       COALESCE(r.counterparty_name, p.counterparty_name,
                                e.counterparty_name, e.object_name, '') AS counterparty_name,
                       CASE WHEN l.source_bill LIKE '%(取消审核)' THEN '冲销'
                            WHEN r.receipt_no IS NOT NULL THEN '收款'
                            WHEN p.payment_no IS NOT NULL THEN '付款'
                            WHEN e.expense_no IS NOT NULL THEN COALESCE(NULLIF(e.expense_type,''),'费用')
                            ELSE '其他' END AS expense_item,
                       CASE WHEN l.direction = 'IN' THEN l.amount ELSE 0 END AS in_amount,
                       CASE WHEN l.direction = 'OUT' THEN l.amount ELSE 0 END AS out_amount,
                       l.balance_after AS balance,
                       (""").append(calc).append(") AS balance_calc,\n")
           .append("CASE WHEN ABS((").append(calc)
           .append(") - l.balance_after) <= 0.01 THEN 'OK' ELSE 'MISMATCH' END AS balance_check,\n")
           .append("l.operator_name AS operator_name\n")
           .append("""
                  FROM fin_fund_ledger l
                  LEFT JOIN fin_receipt_bill r
                    ON r.receipt_no = REPLACE(l.source_bill,'(取消审核)','')
                  LEFT JOIN fin_payment_bill p
                    ON p.payment_no = REPLACE(l.source_bill,'(取消审核)','')
                  LEFT JOIN fin_expense_bill e
                    ON e.expense_no = REPLACE(l.source_bill,'(取消审核)','')
                 WHERE l.fund_account IN """).append(acctIn)
           .append(" AND CAST(l.occurred_at AS DATE) BETWEEN ? AND ? ");
        // calc 出现 2 次，每次 2 个时间参数
        for (int i = 0; i < 2; i++) {
            args.add(Timestamp.valueOf(start.atStartOfDay()));
            args.add(Timestamp.valueOf(start.atStartOfDay()));
        }
        args.addAll(accounts);
        args.add(start);
        args.add(end);
        appendFilters(args, sql, filters, "l", "r", "p", "e");
    }

    /** DAY：日小计（先在 k 里按账户+日聚合，余额/校验挂外层标量子查询）。 */
    private void appendDay(StringBuilder sql, List<Object> args, List<String> accounts, String acctIn,
                           LocalDate start, LocalDate end, Filters filters, boolean detailFilter) {
        sql.append("""
                SELECT 'DAY' AS row_type, k.fund_account AS fund_account,
                       k.d AS biz_date,
                       DATEADD('SECOND', 86221, CAST(k.d AS TIMESTAMP)) AS sort_ts, 2 AS sort_no,
                       CAST(NULL AS VARCHAR) AS ledger_no, CAST(NULL AS VARCHAR) AS source_bill,
                       '日小计' AS summary, CAST(NULL AS VARCHAR) AS counterparty_name,
                       CAST(NULL AS VARCHAR) AS expense_item,
                       k.in_amount AS in_amount, k.out_amount AS out_amount,
                """);
        if (detailFilter) {
            sql.append("CAST(NULL AS DECIMAL(18,2)) AS balance, ")
               .append("CAST(NULL AS DECIMAL(18,2)) AS balance_calc, 'FILTERED' AS balance_check, ");
        } else {
            // 日终余额 = 期初 + 期间首日至当日末全部收付；与当日最后一笔流水余额核对
            String cumToDay = "(" + baseBefore("k.fund_account")
                    + """
                         + COALESCE((SELECT SUM(CASE z.direction WHEN 'IN' THEN z.amount ELSE -z.amount END)
                                      FROM fin_fund_ledger z
                                     WHERE z.fund_account = k.fund_account
                                       AND z.occurred_at >= CAST(? AS TIMESTAMP)
                                       AND z.occurred_at < DATEADD('DAY', 1, CAST(k.d AS DATE))), 0))
                    """;
            sql.append(cumToDay).append(" AS balance,\n")
               .append("CAST(NULL AS DECIMAL(18,2)) AS balance_calc,\n")
               .append("CASE WHEN ABS(").append(cumToDay)
               .append("""
                         - (SELECT z2.balance_after FROM fin_fund_ledger z2
                             WHERE z2.fund_account = k.fund_account
                               AND CAST(z2.occurred_at AS DATE) = k.d
                             ORDER BY z2.occurred_at DESC, z2.ledger_id DESC LIMIT 1)) <= 0.01
                         THEN 'OK' ELSE 'MISMATCH' END AS balance_check,
                    """);
            // cumToDay 出现 2 次，每次 2 个时间参数
            for (int i = 0; i < 2; i++) {
                args.add(Timestamp.valueOf(start.atStartOfDay()));
                args.add(Timestamp.valueOf(start.atStartOfDay()));
            }
        }
        sql.append("""
                CAST(NULL AS VARCHAR) AS operator_name
                  FROM (
                SELECT l.fund_account AS fund_account,
                       CAST(l.occurred_at AS DATE) AS d,
                       SUM(CASE WHEN l.direction = 'IN' THEN l.amount ELSE 0 END) AS in_amount,
                       SUM(CASE WHEN l.direction = 'OUT' THEN l.amount ELSE 0 END) AS out_amount
                  FROM fin_fund_ledger l
                  LEFT JOIN fin_receipt_bill r
                    ON r.receipt_no = REPLACE(l.source_bill,'(取消审核)','')
                  LEFT JOIN fin_payment_bill p
                    ON p.payment_no = REPLACE(l.source_bill,'(取消审核)','')
                  LEFT JOIN fin_expense_bill e
                    ON e.expense_no = REPLACE(l.source_bill,'(取消审核)','')
                 WHERE l.fund_account IN """).append(acctIn)
           .append(" AND CAST(l.occurred_at AS DATE) BETWEEN ? AND ? ");
        args.addAll(accounts);
        args.add(start);
        args.add(end);
        appendFilters(args, sql, filters, "l", "r", "p", "e");
        sql.append(" GROUP BY l.fund_account, CAST(l.occurred_at AS DATE)) k ");
    }

    /**
     * MONTH/YEAR 共用：按月分组。ytd=false→本月合计（sort_no=3，月内发生额，带月末余额）；
     * ytd=true→本年累计（sort_no=4，发生额自年初累计到当月末，不给余额）。
     */
    private void appendMonth(StringBuilder sql, List<Object> args, List<String> accounts, String acctIn,
                             LocalDate start, LocalDate end, Filters filters,
                             boolean detailFilter, boolean ytd) {
        Timestamp endNext = Timestamp.valueOf(end.plusDays(1).atStartOfDay());
        sql.append("""
                SELECT '%s' AS row_type, k.fund_account AS fund_account,
                       DATEADD('DAY', -1, DATEADD('MONTH', 1, CAST(k.mstart AS DATE))) AS biz_date,
                       DATEADD('SECOND', %d,
                           CAST(DATEADD('DAY', -1, DATEADD('MONTH', 1, CAST(k.mstart AS DATE))) AS TIMESTAMP))
                           AS sort_ts, %d AS sort_no,
                       CAST(NULL AS VARCHAR) AS ledger_no, CAST(NULL AS VARCHAR) AS source_bill,
                       '%s' AS summary, CAST(NULL AS VARCHAR) AS counterparty_name,
                       CAST(NULL AS VARCHAR) AS expense_item,
                """.formatted(ytd ? "YEAR" : "MONTH", ytd ? 86341 : 86281,
                ytd ? 4 : 3, ytd ? "本年累计" : "本月合计"));
        if (ytd) {
            // 年累计发生额：独立关联一整套 r3/p3/e3，过滤口径与 DAT 完全一致
            sql.append(ytdSignedSum("IN", "k"));
            args.add(endNext);
            appendFilters(args, sql, filters, "z", "r3", "p3", "e3");
            sql.append("),0) AS in_amount,\n");
            sql.append(ytdSignedSum("OUT", "k"));
            args.add(endNext);
            appendFilters(args, sql, filters, "z", "r3", "p3", "e3");
            sql.append("),0) AS out_amount,\n");
        } else {
            sql.append("k.in_amount AS in_amount, k.out_amount AS out_amount,\n");
        }
        if (!ytd && !detailFilter) {
            // 本月合计余额 = 期初 + 期间首日至当月末（不超过查询截止次日）全部收付
            sql.append("(").append(baseBefore("k.fund_account"))
               .append("""
                     + COALESCE((SELECT SUM(CASE z.direction WHEN 'IN' THEN z.amount ELSE -z.amount END)
                                  FROM fin_fund_ledger z
                                 WHERE z.fund_account = k.fund_account
                                   AND z.occurred_at >= CAST(? AS TIMESTAMP)
                                   AND z.occurred_at < LEAST(DATEADD('MONTH', 1, CAST(k.mstart AS DATE)),
                                                             CAST(? AS TIMESTAMP))), 0)) AS balance,
                    CAST(NULL AS DECIMAL(18,2)) AS balance_calc, 'CALC' AS balance_check,
                    """);
            args.add(Timestamp.valueOf(start.atStartOfDay()));
            args.add(Timestamp.valueOf(start.atStartOfDay()));
            args.add(endNext);
        } else {
            sql.append("CAST(NULL AS DECIMAL(18,2)) AS balance, ")
               .append("CAST(NULL AS DECIMAL(18,2)) AS balance_calc, ")
               .append(detailFilter ? "'FILTERED' AS balance_check, "
                                    : "'CALC' AS balance_check, ");
        }
        sql.append("""
                CAST(NULL AS VARCHAR) AS operator_name
                  FROM (
                SELECT l.fund_account AS fund_account,
                       DATEADD('DAY', 1 - EXTRACT(DAY FROM CAST(l.occurred_at AS DATE)),
                               CAST(l.occurred_at AS DATE)) AS mstart,
                       SUM(CASE WHEN l.direction = 'IN' THEN l.amount ELSE 0 END) AS in_amount,
                       SUM(CASE WHEN l.direction = 'OUT' THEN l.amount ELSE 0 END) AS out_amount
                  FROM fin_fund_ledger l
                  LEFT JOIN fin_receipt_bill r
                    ON r.receipt_no = REPLACE(l.source_bill,'(取消审核)','')
                  LEFT JOIN fin_payment_bill p
                    ON p.payment_no = REPLACE(l.source_bill,'(取消审核)','')
                  LEFT JOIN fin_expense_bill e
                    ON e.expense_no = REPLACE(l.source_bill,'(取消审核)','')
                 WHERE l.fund_account IN """).append(acctIn)
           .append(" AND CAST(l.occurred_at AS DATE) BETWEEN ? AND ? ");
        args.addAll(accounts);
        args.add(start);
        args.add(end);
        appendFilters(args, sql, filters, "l", "r", "p", "e");
        sql.append("""
                 GROUP BY l.fund_account,
                          DATEADD('DAY', 1 - EXTRACT(DAY FROM CAST(l.occurred_at AS DATE)),
                                  CAST(l.occurred_at AS DATE))) k
                 WHERE k.mstart BETWEEN ? AND ?
                """);
        args.add(start.withDayOfMonth(1));
        args.add(end);
    }

    /** YTD 发生额标量子查询骨架（外层负责补 COALESCE 包裹与别名）。 */
    private static String ytdSignedSum(String direction, String kAlias) {
        return """
                COALESCE((SELECT SUM(CASE z.direction WHEN '%s' THEN z.amount ELSE 0 END)
                            FROM fin_fund_ledger z
                            LEFT JOIN fin_receipt_bill r3
                              ON r3.receipt_no = REPLACE(z.source_bill,'(取消审核)','')
                            LEFT JOIN fin_payment_bill p3
                              ON p3.payment_no = REPLACE(z.source_bill,'(取消审核)','')
                            LEFT JOIN fin_expense_bill e3
                              ON e3.expense_no = REPLACE(z.source_bill,'(取消审核)','')
                           WHERE z.fund_account = %s.fund_account
                             AND z.occurred_at >= DATEADD('MONTH',
                                 1 - EXTRACT(MONTH FROM %s.mstart), CAST(%s.mstart AS TIMESTAMP))
                             AND z.occurred_at < LEAST(DATEADD('MONTH', 1, CAST(%s.mstart AS DATE)),
                                                       CAST(? AS TIMESTAMP))
                """.formatted(direction, kAlias, kAlias, kAlias, kAlias);
    }

    /** 追加 DAT 口径过滤（方向/收支项目/对方单位/来源单号），各臂必须共用。 */
    private void appendFilters(List<Object> args, StringBuilder sql, Filters f,
                               String l, String r, String p, String e) {
        if (f.direction() != null) {
            sql.append(" AND ").append(l).append(".direction = ? ");
            args.add(f.direction());
        }
        if (f.item() != null) {
            String cancel = l + ".source_bill LIKE '%(取消审核)'";
            switch (f.item()) {
                case "收款" -> sql.append(" AND NOT ").append(cancel)
                                  .append(" AND ").append(r).append(".receipt_no IS NOT NULL ");
                case "付款" -> sql.append(" AND NOT ").append(cancel)
                                  .append(" AND ").append(p).append(".payment_no IS NOT NULL ");
                case "冲销" -> sql.append(" AND ").append(cancel).append(' ');
                case "费用" -> sql.append(" AND NOT ").append(cancel)
                                  .append(" AND ").append(e).append(".expense_no IS NOT NULL ");
                default -> sql.append(" AND NOT ").append(cancel)
                              .append(" AND ").append(r).append(".receipt_no IS NULL")
                              .append(" AND ").append(p).append(".payment_no IS NULL")
                              .append(" AND ").append(e).append(".expense_no IS NULL ");
            }
        }
        if (f.counterpartyLike() != null) {
            sql.append(" AND COALESCE(").append(r).append(".counterparty_name, ")
               .append(p).append(".counterparty_name, ")
               .append(e).append(".counterparty_name, ").append(e).append(".object_name, '') LIKE ? ");
            args.add(f.counterpartyLike());
        }
        if (f.sourceBillLike() != null) {
            sql.append(" AND ").append(l).append(".source_bill LIKE ? ");
            args.add(f.sourceBillLike());
        }
    }

    // ============================ 合计行 ============================

    /**
     * 合计：期初总额、期间收入/支出总额、期末总额（=期初+收入−支出）、余额不平笔数。
     * 不平笔数的滚算口径与 DAT 臂完全相同（逐笔相关子查询），按账户汇总。
     */
    private void buildGrand(Plan plan, List<String> accounts, String acctIn,
                            LocalDate start, LocalDate end) {
        Timestamp startTs = Timestamp.valueOf(start.atStartOfDay());
        // 期初基准走 baseBefore（最早流水还原初始余额，不拿档案当前余额充期初）
        String openingScalar = baseBefore("fa.fund_account_name");
        String calcPerRow = "(SELECT " + baseBefore("l.fund_account")
                + """
                         + COALESCE((SELECT SUM(CASE z.direction WHEN 'IN' THEN z.amount
                                                                 ELSE -z.amount END)
                                          FROM fin_fund_ledger z
                                         WHERE z.fund_account = l.fund_account
                                           AND z.occurred_at >= CAST(? AS TIMESTAMP)
                                           AND (z.occurred_at < l.occurred_at
                                                OR (z.occurred_at = l.occurred_at
                                                    AND z.ledger_id <= l.ledger_id))), 0))
                """;
        plan.grandSql = """
                SELECT COALESCE(SUM((%s)),0) AS opening_total,
                       COALESCE((SELECT SUM(CASE WHEN l.direction = 'IN' THEN l.amount ELSE 0 END)
                                   FROM fin_fund_ledger l
                                  WHERE l.fund_account IN %s
                                    AND CAST(l.occurred_at AS DATE) BETWEEN ? AND ?), 0) AS in_total,
                       COALESCE((SELECT SUM(CASE WHEN l.direction = 'OUT' THEN l.amount ELSE 0 END)
                                   FROM fin_fund_ledger l
                                  WHERE l.fund_account IN %s
                                    AND CAST(l.occurred_at AS DATE) BETWEEN ? AND ?), 0) AS out_total,
                       COALESCE(SUM((%s)),0)
                         + COALESCE((SELECT SUM(CASE WHEN l.direction = 'IN' THEN l.amount
                                                    WHEN l.direction = 'OUT' THEN -l.amount ELSE 0 END)
                                       FROM fin_fund_ledger l
                                      WHERE l.fund_account IN %s
                                        AND CAST(l.occurred_at AS DATE) BETWEEN ? AND ?), 0) AS ending_total,
                       COALESCE(SUM((SELECT COUNT(1) FROM fin_fund_ledger l
                                      WHERE l.fund_account = fa.fund_account_name
                                        AND CAST(l.occurred_at AS DATE) BETWEEN ? AND ?
                                        AND ABS((%s) - l.balance_after) > 0.01)), 0) AS mismatch_count
                  FROM base_fund_account fa
                 WHERE fa.status = 'NORMAL' AND fa.fund_account_name IN %s
                """.formatted(openingScalar, acctIn, acctIn, openingScalar, acctIn,
                              calcPerRow, acctIn);
        plan.grandArgs.add(startTs);                 // 1 openingScalar #1
        plan.grandArgs.addAll(accounts);             // 2 in_total IN
        plan.grandArgs.add(start); plan.grandArgs.add(end);      // 3 in_total
        plan.grandArgs.addAll(accounts);             // 4 out_total IN
        plan.grandArgs.add(start); plan.grandArgs.add(end);      // 5 out_total
        plan.grandArgs.add(startTs);                 // 6 openingScalar #2
        plan.grandArgs.addAll(accounts);             // 7 ending net move IN
        plan.grandArgs.add(start); plan.grandArgs.add(end);      // 8 ending net move
        plan.grandArgs.add(start); plan.grandArgs.add(end);      // 9 mismatch COUNT BETWEEN
        // calcPerRow 在 COUNT 标量内按账户逐行执行，占位符仍按 SQL 文本位置绑定
        plan.grandArgs.add(startTs);                 // 10 calcPerRow p2
        plan.grandArgs.add(startTs);                 // 11 calcPerRow flows >=
        plan.grandArgs.addAll(accounts);             // 12 外层 fa IN
    }
}
