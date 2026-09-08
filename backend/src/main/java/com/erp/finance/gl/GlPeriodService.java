package com.erp.finance.gl;

import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 期末处理服务（总账 M5）：
 * 自动转账（ZZ{transferNo}{period} 幂等，零/不平跳过）、结转损益（5xxx 净发生→3103，
 * JZ{period} 幂等，"转"字草稿）、结账四项硬检查、结账推进期间（12 期年结冻结+自动建下年期间）、
 * 反结账（仅最后一个已结账期间、原因必填留痕、JZ/ZZ 凭证回退草稿）。
 */
@Service
public class GlPeriodService {

    private final JdbcTemplate jdbc;
    private final SysParamService sysParam;
    private final GlReportCalc calc;
    private final GlInitService initService;
    private final GlAssetService assetService;

    /** 损益净发生判定阈值（元）。 */
    private static final BigDecimal EPS = new BigDecimal("0.005");

    public GlPeriodService(JdbcTemplate jdbc, SysParamService sysParam,
                           GlReportCalc calc, GlInitService initService,
                           GlAssetService assetService) {
        this.jdbc = jdbc;
        this.sysParam = sysParam;
        this.calc = calc;
        this.initService = initService;
        this.assetService = assetService;
    }

    private void ensureInitialized() {
        if (!sysParam.getBool(GlConst.PARAM_INITIALIZED, false))
            throw new IllegalArgumentException("总账尚未启用，请先在「总账初始化」中启用总账");
    }

    // ==================== 向导总览 ====================

    /** 结账向导 8 步状态。body: {period?}，缺省取当前进行中期间。 */
    public Map<String, Object> wizard(Map<String, Object> body) {
        ensureInitialized();
        calc.invalidate();
        String period = resolvePeriod(TmsUtil.str(body.get("period")));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> pRow = periodRow(period);
        out.put("period", period);
        out.put("periodStatus", pRow == null ? "" : TmsUtil.str(pRow.get("status")));
        out.put("periodEnd", pRow == null ? "" : TmsUtil.str(pRow.get("endDate")));

        List<Map<String, Object>> steps = new ArrayList<>();

        // ① 事件检查（提示项：待生成/生成失败事件清单）
        List<Map<String, Object>> pending = TmsUtil.queryCamel(jdbc,
                "SELECT event_code, source_bill_no, status, warn_msg FROM fin_gl_event " +
                        "WHERE status IN ('待生成','生成失败') ORDER BY create_time DESC LIMIT 50");
        steps.add(step(1, "事件检查", pending.isEmpty() ? "pass" : "warn",
                pending.isEmpty() ? "事件池无待处理事件" : ("有 " + pending.size() + " 个待生成/生成失败事件，请先到工作台处理"),
                pending));

        // ② 单据检查（提示项，不阻断：业务单据审核口径分散在各模块，由会计核对）
        steps.add(step(2, "单据检查", "skip", "提示项：请确认本期采购/销售/库存/费用单据均已审核（不阻断结账）", null));

        // ③ 折旧计提（M7 固定资产：预览→执行生成 ZJ{period} 转字草稿，幂等）
        Map<String, Object> depStatus = assetService.wizardStatus(period);
        steps.add(step(3, "折旧计提", TmsUtil.str(depStatus.get("status")),
                TmsUtil.str(depStatus.get("detail")), depStatus));

        // ④ 自动转账
        List<Map<String, Object>> transfers = transferPreview(period);
        boolean zzDone = transfers.stream().allMatch(t -> "done".equals(t.get("status")) || "skip".equals(t.get("status")));
        steps.add(step(4, "自动转账", zzDone ? "pass" : "todo",
                transfers.size() + " 张转账模板：" + (zzDone ? "均已处理或无需生成" : "有待执行凭证"), transfers));

        // ⑤ 结转损益
        Map<String, Object> profit = profitPreview(period);
        Map<String, Object> jz = periodVoucher("JZ", "JZ" + period);
        String profitStatus;
        String profitDetail;
        if (jz != null) {
            profitStatus = "done";
            profitDetail = "结转凭证已生成（" + TmsUtil.str(jz.get("status")) + "），净额 " + money(profit.get("netProfit"));
        } else if (new BigDecimal(String.valueOf(profit.get("totalAmount"))).abs().compareTo(EPS) < 0) {
            profitStatus = "pass";
            profitDetail = "本期损益类科目无净发生额，无需结转";
        } else {
            profitStatus = "todo";
            profitDetail = "待结转：收入合计 " + money(profit.get("totalIncome")) + "，成本费用合计 " + money(profit.get("totalExpense"));
        }
        steps.add(step(5, "结转损益", profitStatus, profitDetail, profit));

        // ⑥ 结账检查（四项硬检查）
        List<Map<String, Object>> checks = runChecks(period);
        boolean allPass = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));
        steps.add(step(6, "结账检查", allPass ? "pass" : "fail",
                allPass ? "四项硬检查全部通过" : "有检查未通过，不能结账", checks));

        // ⑦ 期末结账
        String closeStatus = switch (TmsUtil.str(pRow == null ? null : pRow.get("status"))) {
            case GlConst.P_CLOSED -> "done";
            case GlConst.P_FROZEN -> "frozen";
            case GlConst.P_IN_PROGRESS -> allPass ? "ready" : "todo";
            default -> "todo";
        };
        steps.add(step(7, "期末结账", closeStatus,
                closeStatus.equals("done") ? "本期已结账" : closeStatus.equals("frozen") ? "年度结账已冻结" : "检查通过后可结账", null));

        // ⑧ 业财对账（M6）
        steps.add(step(8, "业财对账", "skip", "业财对账 M6 上线，结账后也可随时查询", null));

        out.put("steps", steps);

        // 反结账信息：最后一个已结账期间
        List<Map<String, Object>> closed = TmsUtil.queryCamel(jdbc,
                "SELECT period, status, settle_name, settle_time, reopen_name, reopen_time, reopen_reason " +
                        "FROM fin_accounting_period WHERE status IN ('已结账','已冻结') ORDER BY period DESC LIMIT 1");
        out.put("lastClosed", closed.isEmpty() ? null : closed.get(0));
        return out;
    }

    private static Map<String, Object> step(int no, String name, String status, String detail, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("no", no);
        m.put("name", name);
        m.put("status", status);
        m.put("detail", detail);
        m.put("data", data);
        return m;
    }

    // ==================== 自动转账 ====================

    /** 预览：每张启用模板的适用性与分录金额（不写库）。 */
    public List<Map<String, Object>> transferPreview(String period) {
        ensureInitialized();
        calc.invalidate();
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> vars = formulaVars();
        for (Map<String, Object> t : TmsUtil.queryCamel(jdbc,
                "SELECT id, transfer_no, transfer_name, condition_expr, enabled, remark " +
                        "FROM fin_auto_transfer WHERE enabled = TRUE ORDER BY transfer_no")) {
            String no = TmsUtil.str(t.get("transferNo"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("transferNo", no);
            row.put("transferName", TmsUtil.str(t.get("transferName")));
            row.put("remark", TmsUtil.str(t.get("remark")));
            Map<String, Object> exist = periodVoucher("ZZ", no + period);
            if (exist != null) {
                row.put("status", "done");
                row.put("message", "本期已生成（" + TmsUtil.str(exist.get("status")) + "）");
                row.put("voucherId", exist.get("id"));
            } else {
                Map<String, Object> evalResult = evalTransfer(t, period, vars);
                row.put("status", evalResult.get("status"));     // todo / skip
                row.put("message", evalResult.get("message"));
                row.put("entries", evalResult.get("entries"));
                row.put("debitTotal", evalResult.get("debitTotal"));
                row.put("creditTotal", evalResult.get("creditTotal"));
            }
            out.add(row);
        }
        return out;
    }

    /** 执行自动转账（全部启用模板；transferNos 指定时只执行选中的）。零/不平/条件不满足跳过，幂等拦截。 */
    @Transactional
    public Map<String, Object> transferExecute(Map<String, Object> body) {
        ensureInitialized();
        String period = resolvePeriod(TmsUtil.str(body.get("period")));
        requirePeriodOpen(period);
        calc.invalidate();
        @SuppressWarnings("unchecked")
        List<String> only = body.get("transferNos") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        Map<String, Object> vars = formulaVars();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> t : TmsUtil.queryCamel(jdbc,
                "SELECT id, transfer_no, transfer_name, condition_expr, enabled " +
                        "FROM fin_auto_transfer WHERE enabled = TRUE ORDER BY transfer_no")) {
            String no = TmsUtil.str(t.get("transferNo"));
            if (!only.isEmpty() && !only.contains(no)) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("transferNo", no);
            r.put("transferName", TmsUtil.str(t.get("transferName")));
            Map<String, Object> exist = periodVoucher("ZZ", no + period);
            if (exist != null) {
                r.put("status", "exists");
                r.put("message", "本期已存在凭证，幂等跳过");
                results.add(r);
                continue;
            }
            Map<String, Object> ev = evalTransfer(t, period, vars);
            if (!"todo".equals(ev.get("status"))) {
                r.put("status", "skip");
                r.put("message", TmsUtil.str(ev.get("message")));
                results.add(r);
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) ev.get("entries");
            LocalDate end = periodEnd(period);
            String id = createGeneratedVoucher(period, "转", "自动转账", "ZZ", no + period,
                    "自动转账：" + TmsUtil.str(t.get("transferName")), end, entries);
            r.put("status", "created");
            r.put("message", "已生成转账凭证（草稿）");
            r.put("voucherId", id);
            results.add(r);
        }
        TmsUtil.log(jdbc, "finance.gl.period", "TRANSFER", period,
                "自动转账执行 " + results.size() + " 张：" + results.stream()
                        .filter(x -> "created".equals(x.get("status"))).count() + " 张生成");
        return Map.of("period", period, "results", results);
    }

    /** 计算单张模板的分录金额；status: todo（可生成）/ skip（零/不平/条件不满足/科目异常）。 */
    private Map<String, Object> evalTransfer(Map<String, Object> t, String period, Map<String, Object> vars) {
        Map<String, Object> r = new LinkedHashMap<>();
        String cond = TmsUtil.str(t.get("conditionExpr"));
        if (!cond.isEmpty() && !calc.evalCondition(cond, period, vars)) {
            r.put("status", "skip");
            r.put("message", "执行条件不满足（如非一般纳税人）");
            return r;
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (Map<String, Object> e : TmsUtil.queryCamel(jdbc,
                "SELECT line_no, direction, summary, account_code, amount_expr " +
                        "FROM fin_auto_transfer_entry WHERE transfer_id = ? ORDER BY line_no",
                TmsUtil.str(t.get("id")))) {
            String code = TmsUtil.str(e.get("accountCode"));
            BigDecimal amount = calc.evalAmount(TmsUtil.str(e.get("amountExpr")), period).abs();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("direction", TmsUtil.str(e.get("direction")));
            line.put("accountCode", code);
            line.put("summary", TmsUtil.str(e.get("summary")));
            line.put("amount", amount);
            entries.add(line);
            if ("借".equals(TmsUtil.str(e.get("direction")))) debit = debit.add(amount);
            else credit = credit.add(amount);
        }
        debit = debit.setScale(2, RoundingMode.HALF_UP);
        credit = credit.setScale(2, RoundingMode.HALF_UP);
        r.put("entries", entries);
        r.put("debitTotal", debit);
        r.put("creditTotal", credit);
        if (debit.abs().compareTo(EPS) < 0 && credit.abs().compareTo(EPS) < 0) {
            r.put("status", "skip");
            r.put("message", "计算金额为零，自动跳过");
            return r;
        }
        if (debit.subtract(credit).abs().compareTo(EPS) >= 0) {
            r.put("status", "skip");
            r.put("message", "借贷不平（借 " + debit + " / 贷 " + credit + "），跳过，请检查模板公式");
            return r;
        }
        for (Map<String, Object> line : entries) {
            if (leafAccount(TmsUtil.str(line.get("accountCode"))) == null) {
                r.put("status", "skip");
                r.put("message", "科目 " + line.get("accountCode") + " 不是末级启用科目，跳过");
                return r;
            }
        }
        r.put("status", "todo");
        r.put("message", "可生成凭证");
        return r;
    }

    // ==================== 结转损益 ====================

    /** 预览损益类科目本期净发生（按科目+辅助核算分组）。 */
    public Map<String, Object> profitPreview(String period) {
        ensureInitialized();
        calc.invalidate();
        List<Map<String, Object>> groups = profitGroups(period);
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO, totalExpense = BigDecimal.ZERO;
        for (Map<String, Object> g : groups) {
            boolean creditSide = "贷".equals(TmsUtil.str(g.get("balanceDirection")));
            BigDecimal d = bd(g.get("debitAmount")), c = bd(g.get("creditAmount"));
            BigDecimal net = creditSide ? c.subtract(d) : d.subtract(c);
            if (net.abs().compareTo(EPS) < 0) continue;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("accountCode", g.get("accountCode"));
            line.put("accountName", g.get("accountName"));
            line.put("direction", creditSide ? "收入类（贷）" : "成本费用类（借）");
            line.put("netAmount", net.abs().setScale(2, RoundingMode.HALF_UP));
            line.put("auxText", g.get("auxText"));
            lines.add(line);
            if (creditSide) totalIncome = totalIncome.add(net.abs());
            else totalExpense = totalExpense.add(net.abs());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", lines);
        out.put("totalIncome", totalIncome.setScale(2, RoundingMode.HALF_UP));
        out.put("totalExpense", totalExpense.setScale(2, RoundingMode.HALF_UP));
        out.put("totalAmount", totalIncome.add(totalExpense));
        // 净利润 = 收入净贷 − 成本费用净借
        out.put("netProfit", totalIncome.subtract(totalExpense).setScale(2, RoundingMode.HALF_UP));
        return out;
    }

    /** 执行结转损益：生成"转"字草稿凭证 JZ{period}，幂等。 */
    @Transactional
    public Map<String, Object> profitCarry(Map<String, Object> body) {
        ensureInitialized();
        String period = resolvePeriod(TmsUtil.str(body.get("period")));
        requirePeriodOpen(period);
        if (periodVoucher("JZ", "JZ" + period) != null)
            throw new IllegalArgumentException("本期已生成结转损益凭证（JZ" + period + "），请勿重复结转；如需重转请先删除该凭证");
        List<Map<String, Object>> groups = profitGroups(period);
        List<Map<String, Object>> entries = new ArrayList<>();
        BigDecimal to3103Credit = BigDecimal.ZERO;   // 收入结转：贷 3103
        BigDecimal to3103Debit = BigDecimal.ZERO;    // 费用结转：借 3103
        int lineNo = 1;
        for (Map<String, Object> g : groups) {
            boolean creditSide = "贷".equals(TmsUtil.str(g.get("balanceDirection")));
            BigDecimal d = bd(g.get("debitAmount")), c = bd(g.get("creditAmount"));
            BigDecimal net = (creditSide ? c.subtract(d) : d.subtract(c)).setScale(2, RoundingMode.HALF_UP);
            if (net.abs().compareTo(EPS) < 0) continue;
            BigDecimal amt = net.abs();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineNo", lineNo++);
            line.put("direction", creditSide ? "借" : "贷");   // 收入类：借收入冲平；费用类：贷费用冲平
            line.put("accountCode", g.get("accountCode"));
            line.put("summary", (creditSide ? "结转收入 " : "结转成本费用 ") + TmsUtil.str(g.get("accountName")));
            line.put("amount", amt);
            line.put("aux", g);  // 辅助列整行带出（见 createGeneratedVoucher 取值）
            entries.add(line);
            if (creditSide) to3103Credit = to3103Credit.add(amt);
            else to3103Debit = to3103Debit.add(amt);
        }
        if (entries.isEmpty())
            throw new IllegalArgumentException("本期损益类科目无净发生额，无需结转");
        // 3103 本年利润汇总行（收入一笔贷方、费用一笔借方；辅助为空）
        if (to3103Credit.signum() > 0) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineNo", lineNo++);
            line.put("direction", "贷");
            line.put("accountCode", "3103");
            line.put("summary", "结转本期收入至本年利润");
            line.put("amount", to3103Credit);
            entries.add(line);
        }
        if (to3103Debit.signum() > 0) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineNo", lineNo++);
            line.put("direction", "借");
            line.put("accountCode", "3103");
            line.put("summary", "结转本期成本费用至本年利润");
            line.put("amount", to3103Debit);
            entries.add(line);
        }
        if (to3103Credit.add(to3103Debit).signum() == 0)
            throw new IllegalArgumentException("本期损益类科目无净发生额，无需结转");
        LocalDate end = periodEnd(period);
        String id = createGeneratedVoucher(period, "转", "结转损益", "JZ", "JZ" + period,
                "结转本期损益至本年利润", end, entries);
        TmsUtil.log(jdbc, "finance.gl.period", "CARRY", "JZ" + period,
                "结转损益：收入 " + to3103Credit + "，成本费用 " + to3103Debit);
        return Map.of("period", period, "voucherId", id,
                "totalIncome", to3103Credit, "totalExpense", to3103Debit,
                "netProfit", to3103Credit.subtract(to3103Debit));
    }

    /** 损益类科目（5xxx）本期已过账发生，按科目+辅助核算分组（辅助余额也要逐组轧平）。 */
    private List<Map<String, Object>> profitGroups(String period) {
        return TmsUtil.queryCamel(jdbc,
                "SELECT e.account_code, a.account_name, a.balance_direction, " +
                        "e.aux_customer, e.aux_supplier, e.aux_department, e.aux_employee, " +
                        "e.aux_goods, e.aux_project, e.aux_area, e.aux_key, e.aux_text, " +
                        "SUM(e.debit_amount) debit_amount, SUM(e.credit_amount) credit_amount " +
                        "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                        "JOIN fin_account a ON a.account_code = e.account_code " +
                        "WHERE v.period = ? AND v.status IN ('已过账','已冲销') AND e.account_code LIKE '5%' " +
                        "GROUP BY e.account_code, a.account_name, a.balance_direction, " +
                        "e.aux_customer, e.aux_supplier, e.aux_department, e.aux_employee, " +
                        "e.aux_goods, e.aux_project, e.aux_area, e.aux_key, e.aux_text " +
                        "ORDER BY e.account_code", period);
    }

    // ==================== 结账 / 反结账 ====================

    /** 四项硬检查（只读）。 */
    public List<Map<String, Object>> checks(String period) {
        ensureInitialized();
        calc.invalidate();
        return runChecks(period);
    }

    private List<Map<String, Object>> runChecks(String period) {
        List<Map<String, Object>> checks = new ArrayList<>();

        // 1. 本期凭证全部过账
        List<Map<String, Object>> unposted = TmsUtil.queryCamel(jdbc,
                "SELECT id, voucher_word, voucher_no, summary, status FROM fin_voucher " +
                        "WHERE period = ? AND status IN ('草稿','已审核') ORDER BY voucher_word, voucher_no", period);
        checks.add(check("全部过账", unposted.isEmpty(),
                unposted.isEmpty() ? "本期凭证均已过账" : ("有 " + unposted.size() + " 张凭证未过账（草稿/已审核）"),
                unposted));

        // 2. 借贷平衡：本期发生额借贷合计相等，且期初（启用余额+以前期间发生）试算平衡
        Map<String, Object> occ = TmsUtil.queryCamel(jdbc,
                "SELECT COALESCE(SUM(e.debit_amount),0) debit_amount, COALESCE(SUM(e.credit_amount),0) credit_amount " +
                        "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                        "WHERE v.period = ? AND v.status IN ('已过账','已冲销')", period)
                .stream().findFirst().orElse(Map.of());
        BigDecimal occD = bd(occ.get("debitAmount")), occC = bd(occ.get("creditAmount"));
        Map<String, Object> open = TmsUtil.queryCamel(jdbc,
                "SELECT COALESCE(SUM(open_debit),0) debit_amount, COALESCE(SUM(open_credit),0) credit_amount FROM fin_init_balance")
                .stream().findFirst().orElse(Map.of());
        Map<String, Object> before = TmsUtil.queryCamel(jdbc,
                "SELECT COALESCE(SUM(e.debit_amount),0) debit_amount, COALESCE(SUM(e.credit_amount),0) credit_amount " +
                        "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                        "WHERE v.period < ? AND v.status IN ('已过账','已冲销')", period)
                .stream().findFirst().orElse(Map.of());
        BigDecimal openD = bd(open.get("debitAmount")).add(bd(before.get("debitAmount")));
        BigDecimal openC = bd(open.get("creditAmount")).add(bd(before.get("creditAmount")));
        BigDecimal diff = occD.subtract(occC).abs().max(openD.subtract(openC).abs());
        checks.add(check("借贷平衡", diff.compareTo(new BigDecimal("0.02")) < 0,
                "本期借方发生 " + money(occD) + " / 贷方发生 " + money(occC)
                        + "；期初借 " + money(openD) + " / 贷 " + money(openC),
                diff.compareTo(new BigDecimal("0.02")) < 0 ? null : Map.of("diff", diff)));

        // 3. 损益已结转：JZ 凭证已过账，或损益类科目本期无净发生
        Map<String, Object> jz = periodVoucher("JZ", "JZ" + period);
        Map<String, Object> profit = profitPreview(period);
        BigDecimal plTotal = new BigDecimal(String.valueOf(profit.get("totalAmount")));
        boolean profitOk;
        String profitMsg;
        if (jz != null && GlConst.V_POSTED.equals(TmsUtil.str(jz.get("status")))) {
            profitOk = true;
            profitMsg = "结转损益凭证已过账，净利润 " + money(profit.get("netProfit"));
        } else if (plTotal.abs().compareTo(EPS) < 0) {
            profitOk = true;
            profitMsg = "损益类科目无净发生额，无需结转";
        } else if (jz != null) {
            profitOk = false;
            profitMsg = "结转损益凭证已生成但未过账（当前状态：" + TmsUtil.str(jz.get("status")) + "）";
        } else {
            profitOk = false;
            profitMsg = "损益类科目有净发生额 " + money(plTotal) + "，尚未结转";
        }
        checks.add(check("损益结转", profitOk, profitMsg, null));

        // 4. 上期已结账（期间连续；启用期之前由启用流程批量结账）
        String prev = prevPeriod(period);
        String startPeriod = sysParam.get(GlConst.PARAM_START_PERIOD, period);
        if (prev.compareTo(startPeriod) < 0) {
            checks.add(check("上期已结", true, "启用首期（" + startPeriod + " 启用），无上期", null));
        } else {
            Map<String, Object> prevRow = periodRow(prev);
            String st = prevRow == null ? "" : TmsUtil.str(prevRow.get("status"));
            boolean ok = GlConst.P_CLOSED.equals(st) || GlConst.P_FROZEN.equals(st);
            checks.add(check("上期已结", ok,
                    ok ? "上期 " + prev + " 已" + st : "上期 " + prev + " 状态为「" + st + "」，请先结账上期", null));
        }
        return checks;
    }

    /** 期末结账：四检查全过 → 期间置已结账（12 期已冻结），下一期间置进行中（自动建下年期间）。 */
    @Transactional
    public Map<String, Object> close(Map<String, Object> body) {
        ensureInitialized();
        String period = resolvePeriod(TmsUtil.str(body.get("period")));
        Map<String, Object> row = periodRow(period);
        if (row == null) throw new IllegalArgumentException("会计期间 " + period + " 不存在");
        String st = TmsUtil.str(row.get("status"));
        if (GlConst.P_CLOSED.equals(st) || GlConst.P_FROZEN.equals(st))
            throw new IllegalArgumentException("会计期间 " + period + " 已" + st + "，不能重复结账");
        if (!GlConst.P_IN_PROGRESS.equals(st))
            throw new IllegalArgumentException("会计期间 " + period + " 状态为「" + st + "」，不能结账");
        List<Map<String, Object>> checks = runChecks(period);
        List<Map<String, Object>> failed = checks.stream().filter(c -> !Boolean.TRUE.equals(c.get("passed"))).toList();
        if (!failed.isEmpty()) {
            StringBuilder sb = new StringBuilder("结账检查未通过：");
            for (Map<String, Object> f : failed)
                sb.append("\n【").append(TmsUtil.str(f.get("name"))).append("】").append(TmsUtil.str(f.get("detail")));
            throw new IllegalArgumentException(sb.toString());
        }
        boolean yearEnd = period.endsWith("12");
        String newStatus = yearEnd ? GlConst.P_FROZEN : GlConst.P_CLOSED;
        jdbc.update("UPDATE fin_accounting_period SET status = ?, settle_name = ?, settle_time = CURRENT_TIMESTAMP " +
                "WHERE period = ?", newStatus, TmsUtil.currentUser(), period);

        // 下一期间推进；12 期结账后自动生成下年期间再开账
        String next = nextPeriod(period);
        if (yearEnd && periodRow(next) == null) {
            initService.generatePeriods(Integer.parseInt(next.substring(0, 4)));
        }
        int opened = jdbc.update("UPDATE fin_accounting_period SET status = ? WHERE period = ? AND status = ?",
                GlConst.P_IN_PROGRESS, next, GlConst.P_NOT_STARTED);
        TmsUtil.log(jdbc, "finance.gl.period", "CLOSE", period,
                (yearEnd ? "年度结账（冻结）" : "期末结账") + "，下一期间 " + next + (opened > 0 ? " 已开账" : " 未开账（请检查期间）"));
        return Map.of("period", period, "status", newStatus, "nextPeriod", next, "nextOpened", opened > 0);
    }

    /** 反结账：仅最后一个已结账期间、原因必填；12 期冻结不可反；JZ/ZZ 凭证回退草稿。 */
    @Transactional
    public Map<String, Object> reopen(Map<String, Object> body) {
        ensureInitialized();
        String period = resolvePeriod(TmsUtil.str(body.get("period")));
        String reason = TmsUtil.str(body.get("reason")).trim();
        if (reason.length() < 2) throw new IllegalArgumentException("反结账必须填写原因（至少 2 个字）");
        Map<String, Object> row = periodRow(period);
        if (row == null) throw new IllegalArgumentException("会计期间 " + period + " 不存在");
        String st = TmsUtil.str(row.get("status"));
        if (GlConst.P_FROZEN.equals(st))
            throw new IllegalArgumentException("会计期间 " + period + " 为年度结账（已冻结），不能反结账");
        if (!GlConst.P_CLOSED.equals(st))
            throw new IllegalArgumentException("会计期间 " + period + " 状态为「" + st + "」，无需反结账");
        List<Map<String, Object>> latest = TmsUtil.queryCamel(jdbc,
                "SELECT period, status FROM fin_accounting_period WHERE status IN ('已结账','已冻结') " +
                        "ORDER BY period DESC LIMIT 1");
        if (latest.isEmpty() || !period.equals(TmsUtil.str(latest.get(0).get("period"))))
            throw new IllegalArgumentException("只能反结账最后一个已结账期间"
                    + (latest.isEmpty() ? "" : "（最后为 " + TmsUtil.str(latest.get(0).get("period")) + "）"));

        jdbc.update("UPDATE fin_accounting_period SET status = ?, reopen_name = ?, reopen_time = CURRENT_TIMESTAMP, " +
                "reopen_reason = ? WHERE period = ?",
                GlConst.P_IN_PROGRESS, TmsUtil.currentUser(), reason, period);
        // 结转损益/自动转账凭证回退为草稿（清空凭证号与审核/过账痕迹，可改可删）
        int reverted = jdbc.update("UPDATE fin_voucher SET status = '草稿', voucher_no = NULL, " +
                        "auditor_name = NULL, audit_time = NULL, poster_name = NULL, post_time = NULL, " +
                        "update_time = CURRENT_TIMESTAMP " +
                        "WHERE period = ? AND source_bill_type IN ('JZ','ZZ') AND status <> '已作废'",
                period);
        TmsUtil.log(jdbc, "finance.gl.period", "REOPEN", period,
                "反结账，原因：" + reason + "；回退期末凭证 " + reverted + " 张为草稿");
        return Map.of("period", period, "status", GlConst.P_IN_PROGRESS, "revertedVouchers", reverted);
    }

    // ==================== 公共工具 ====================

    private Map<String, Object> check(String name, boolean passed, String detail, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("passed", passed);
        m.put("detail", detail);
        m.put("data", data);
        return m;
    }

    private Map<String, Object> formulaVars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("taxpayer", sysParam.get(GlConst.PARAM_TAXPAYER_TYPE, "GENERAL"));
        try {
            vars.put("surtax_rate", new BigDecimal(sysParam.get(GlConst.PARAM_SURTAX_RATE, "0.12")));
        } catch (Exception e) {
            vars.put("surtax_rate", new BigDecimal("0.12"));
        }
        return vars;
    }

    /** 本期期末凭证（source_bill_type + source_bill_no 幂等键），不含作废。 */
    private Map<String, Object> periodVoucher(String billType, String billNo) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT id, voucher_no, status, summary FROM fin_voucher " +
                        "WHERE source_bill_type = ? AND source_bill_no = ? AND status <> '已作废' LIMIT 1",
                billType, billNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 生成期末类草稿凭证（转字、月末日期），返回凭证 id。entries: direction/accountCode/summary/amount[/aux(Map)]。 */
    private String createGeneratedVoucher(String period, String word, String source, String billType,
                                          String billNo, String summary, LocalDate date,
                                          List<Map<String, Object>> entries) {
        String id = TmsUtil.uuid("VP");
        jdbc.update("INSERT INTO fin_voucher(id, voucher_word, voucher_date, period, attachments, summary, " +
                        "source, source_bill_type, source_bill_no, status, is_red, maker_name, make_time) " +
                        "VALUES (?,?,?,?,0,?,?,?,?,'草稿',FALSE,?,CURRENT_TIMESTAMP)",
                id, word, java.sql.Date.valueOf(date), period, summary, source, billType, billNo,
                TmsUtil.currentUser());
        int lineNo = 1;
        for (Map<String, Object> e : entries) {
            String direction = TmsUtil.str(e.get("direction"));
            BigDecimal amount = bd(e.get("amount")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal debit = "借".equals(direction) ? amount : BigDecimal.ZERO;
            BigDecimal credit = "贷".equals(direction) ? amount : BigDecimal.ZERO;
            @SuppressWarnings("unchecked")
            Map<String, Object> aux = (Map<String, Object>) e.get("aux");
            jdbc.update("INSERT INTO fin_voucher_entry(id, voucher_id, line_no, summary, account_code, " +
                            "debit_amount, credit_amount, qty, price, aux_customer, aux_supplier, aux_department, " +
                            "aux_employee, aux_goods, aux_project, aux_area, aux_key, aux_text, cash_flow_item) " +
                            "VALUES (?,?,?,?,?,?,?,NULL,NULL,?,?,?,?,?,?,?,?,?,NULL)",
                    TmsUtil.uuid("VE"), id, lineNo++,
                    TmsUtil.str(e.get("summary")), TmsUtil.str(e.get("accountCode")),
                    debit, credit,
                    aux == null ? null : aux.get("auxCustomer"),
                    aux == null ? null : aux.get("auxSupplier"),
                    aux == null ? null : aux.get("auxDepartment"),
                    aux == null ? null : aux.get("auxEmployee"),
                    aux == null ? null : aux.get("auxGoods"),
                    aux == null ? null : aux.get("auxProject"),
                    aux == null ? null : aux.get("auxArea"),
                    aux == null ? null : aux.get("auxKey"),
                    aux == null ? null : aux.get("auxText"));
        }
        return id;
    }

    private Map<String, Object> leafAccount(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT account_code FROM fin_account WHERE account_code = ? AND is_leaf = TRUE AND status = ?",
                code, GlConst.ACCOUNT_ENABLED);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requirePeriodOpen(String period) {
        Map<String, Object> row = periodRow(period);
        if (row == null) throw new IllegalArgumentException("会计期间 " + period + " 不存在");
        String st = TmsUtil.str(row.get("status"));
        if (!GlConst.P_IN_PROGRESS.equals(st))
            throw new IllegalArgumentException("会计期间 " + period + " 已" + st + "，不能执行期末操作");
    }

    private String resolvePeriod(String period) {
        if (!period.isEmpty()) return period;
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT period FROM fin_accounting_period WHERE status = ? ORDER BY period LIMIT 1",
                GlConst.P_IN_PROGRESS);
        if (rows.isEmpty()) throw new IllegalArgumentException("没有进行中的会计期间，请检查总账初始化");
        return TmsUtil.str(rows.get(0).get("period"));
    }

    private Map<String, Object> periodRow(String period) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT period, period_year, period_no, start_date, end_date, status FROM fin_accounting_period WHERE period = ?",
                period);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LocalDate periodEnd(String period) {
        Map<String, Object> row = periodRow(period);
        Object end = row == null ? null : row.get("endDate");
        if (end instanceof java.sql.Date d) return d.toLocalDate();
        if (end instanceof LocalDate d) return d;
        // 兜底：期间月末
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        return LocalDate.of(y, m, 1).plusMonths(1).minusDays(1);
    }

    private static String nextPeriod(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        return m == 12 ? String.format("%d01", y + 1) : String.format("%d%02d", y, m + 1);
    }

    private static String prevPeriod(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        return m == 1 ? String.format("%d12", y - 1) : String.format("%d%02d", y, m - 1);
    }

    private static BigDecimal bd(Object o) {
        return o == null ? BigDecimal.ZERO : TmsUtil.toBd(o);
    }

    private static String money(Object o) {
        return bd(o).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
