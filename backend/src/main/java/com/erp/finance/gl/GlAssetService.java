package com.erp.finance.gl;

import com.erp.tms.TmsUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 固定资产（总账 M7）。
 *
 * 建卡：fin_asset_card，可选生成入账凭证草稿（借 1601 / 贷对方科目，source_bill_no=卡片编号，幂等）。
 * 折旧：四方法——直线、双倍余额递减（末两年改直线）、年数总和、工作量法；
 *   次月起提、末月补残值（cum[life]=应折总额）、提满停；月度计提写 fin_asset_depreciation、
 *   更新卡片累计折旧/净值，并按「折旧费用科目+部门」汇总生成「转」字草稿凭证（借费用/贷 1602），
 *   source_bill_no=ZJ{period} 幂等。
 * 金额口径：BigDecimal HALF_UP 2 位；年法内部按年折旧额/12 月折，年初年累计自然回齐。
 */
@Service
public class GlAssetService {

    private final JdbcTemplate jdbc;
    private final GlReportCalc calc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final BigDecimal EPS = new BigDecimal("0.005");
    private static final BigDecimal RATE_DDB = new BigDecimal("2");

    public GlAssetService(JdbcTemplate jdbc, GlReportCalc calc) {
        this.jdbc = jdbc;
        this.calc = calc;
    }

    // ==================== 基础资料 / 台账 ====================

    public List<Map<String, Object>> categories() {
        return TmsUtil.queryCamel(jdbc,
                "SELECT category_code, category_name, default_method, default_life_months, " +
                        "salvage_rate, expense_account, sort_order " +
                        "FROM fin_asset_category ORDER BY sort_order");
    }

    /** 资产台账（status 为空查全部）。 */
    public List<Map<String, Object>> cards(String status) {
        String st = TmsUtil.str(status);
        String sql = "SELECT card_id, card_no, category_code, category_name, asset_name, " +
                "department_code, department_name, acquired_date, original_value, salvage_rate, salvage_value, " +
                "depreciation_method, life_months, total_workload, used_workload, workload_unit, " +
                "expense_account, accum_depreciation, net_value, last_dep_period, voucher_id, status, remark " +
                "FROM fin_asset_card " + (st.isEmpty() ? "" : "WHERE status = ? ") + "ORDER BY card_no DESC";
        return st.isEmpty() ? TmsUtil.queryCamel(jdbc, sql) : TmsUtil.queryCamel(jdbc, sql, st);
    }

    /** 建卡。createVoucher=true 且 creditAccount 非空时同时生成入账凭证草稿。 */
    @Transactional
    public Map<String, Object> createCard(Map<String, Object> body) {
        String name = TmsUtil.str(body.get("assetName"));
        if (name.isEmpty()) throw new IllegalArgumentException("资产名称必填");
        BigDecimal original = TmsUtil.toBd(body.get("originalValue"));
        if (original.signum() <= 0) throw new IllegalArgumentException("原值必须大于 0");

        String categoryCode = TmsUtil.str(body.get("categoryCode"));
        Map<String, Object> cat = category(categoryCode);
        if (cat == null) throw new IllegalArgumentException("资产类别不存在：" + categoryCode);

        String method = TmsUtil.str(body.get("depreciationMethod"));
        if (method.isEmpty()) method = TmsUtil.str(cat.get("defaultMethod"));
        if (!method.equals("直线") && !method.equals("双倍余额") && !method.equals("年数总和") && !method.equals("工作量"))
            throw new IllegalArgumentException("折旧方法应为：直线/双倍余额/年数总和/工作量");

        LocalDate acquired = TmsUtil.toLocalDate(body.get("acquiredDate"));
        if (acquired == null) throw new IllegalArgumentException("购入日期格式应为 yyyy-MM-dd");

        BigDecimal salvageRate = body.get("salvageRate") == null
                ? bd(cat.get("salvageRate")) : TmsUtil.toBd(body.get("salvageRate"));
        if (salvageRate.signum() < 0 || salvageRate.compareTo(BigDecimal.ONE) >= 0)
            throw new IllegalArgumentException("残值率应在 0 ~ 1 之间（如 0.05）");
        BigDecimal salvage = original.multiply(salvageRate).setScale(2, RoundingMode.HALF_UP);

        int lifeMonths;
        if (body.get("lifeMonths") != null) lifeMonths = TmsUtil.toInt(body.get("lifeMonths"));
        else if (body.get("lifeYears") != null) lifeMonths = TmsUtil.toInt(body.get("lifeYears")) * 12;
        else lifeMonths = TmsUtil.toInt(cat.get("defaultLifeMonths"));
        BigDecimal totalWorkload = bd(body.get("totalWorkload"));
        if (method.equals("工作量")) {
            if (totalWorkload.signum() <= 0) throw new IllegalArgumentException("工作量法必须填写总工作量");
        } else {
            if (lifeMonths < 12) throw new IllegalArgumentException("折旧年限至少 1 年（12 个月）");
            if (lifeMonths % 12 != 0) throw new IllegalArgumentException("折旧年限应为整年（月数为 12 的倍数）");
        }

        String expenseAccount = TmsUtil.str(body.get("expenseAccount"));
        if (expenseAccount.isEmpty()) expenseAccount = TmsUtil.str(cat.get("expenseAccount"));
        if (leafAccount(expenseAccount) == null)
            throw new IllegalArgumentException("折旧费用科目不是末级启用科目：" + expenseAccount);
        if (!"借".equals(accountDirection(expenseAccount)))
            throw new IllegalArgumentException("折旧费用科目应为借方科目：" + expenseAccount);

        String deptCode = TmsUtil.str(body.get("departmentCode"));
        String deptName = TmsUtil.str(body.get("departmentName"));
        String creditAccount = TmsUtil.str(body.get("creditAccount"));
        boolean createVoucher = Boolean.TRUE.equals(body.get("createVoucher")) || !creditAccount.isEmpty();
        if (createVoucher) {
            if (creditAccount.isEmpty()) throw new IllegalArgumentException("生成入账凭证必须填写对方科目（如 100201 银行存款）");
            if (leafAccount(creditAccount) == null)
                throw new IllegalArgumentException("对方科目不是末级启用科目：" + creditAccount);
            if (accountAuxDimensions(creditAccount) != null)
                throw new IllegalArgumentException("对方科目带辅助核算，不能直接建卡入账（请先在总账挂往来再建卡）：" + creditAccount);
        }

        Integer cnt = jdbc.queryForObject("SELECT COUNT(1) FROM fin_asset_card", Integer.class);
        String cardNo = String.format("ZC%06d", (cnt == null ? 0 : cnt) + 1);
        String cardId = TmsUtil.uuid("AC");
        jdbc.update("INSERT INTO fin_asset_card(card_id, card_no, category_code, category_name, asset_name, " +
                        "department_code, department_name, acquired_date, original_value, salvage_rate, salvage_value, " +
                        "depreciation_method, life_months, total_workload, used_workload, workload_unit, " +
                        "expense_account, accum_depreciation, net_value, status, remark, maker_name, make_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,0,?,'使用中',?,?,CURRENT_TIMESTAMP)",
                cardId, cardNo, categoryCode, TmsUtil.str(cat.get("categoryName")), name,
                deptCode, deptName, java.sql.Date.valueOf(acquired), original, salvageRate, salvage,
                method, lifeMonths, totalWorkload, TmsUtil.str(body.get("workloadUnit")),
                expenseAccount, original, TmsUtil.str(body.get("remark")), TmsUtil.currentUser());

        String voucherId = null;
        if (createVoucher) {
            Map<String, Object> aux = new LinkedHashMap<>();
            aux.put("auxDepartment", deptCode);
            List<Map<String, Object>> entries = List.of(
                    entry("借", "1601", "购建固定资产 " + name, original, aux),
                    entry("贷", creditAccount, "购建固定资产 " + name, original, null));
            voucherId = createVoucher(acquired.toString().replace("-", "").substring(0, 6), "转", "资产建卡",
                    "GDZC", cardNo, "固定资产建卡 " + cardNo + " " + name, acquired, entries);
            jdbc.update("UPDATE fin_asset_card SET voucher_id = ? WHERE card_id = ?", voucherId, cardId);
        }

        TmsUtil.log(jdbc, "finance.gl.asset", "CARD_CREATE", cardNo,
                "建卡 " + name + " 原值 " + original + " " + method + (createVoucher ? "，入账凭证草稿已生成" : ""));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cardNo", cardNo);
        result.put("cardId", cardId);
        result.put("voucherId", voucherId);
        return result;
    }

    /** 停用/启用卡片（已停用不再计提；M8 扩展清理/拆分）。 */
    @Transactional
    public Map<String, Object> toggleStatus(String cardId, String status) {
        String st = TmsUtil.str(status);
        if (!st.equals("使用中") && !st.equals("已停用"))
            throw new IllegalArgumentException("状态只能为 使用中/已停用");
        int n = jdbc.update("UPDATE fin_asset_card SET status = ? WHERE card_id = ? AND status IN ('使用中','已停用')", st, cardId);
        if (n == 0) throw new IllegalArgumentException("卡片不存在或当前状态不允许停用/启用");
        return Map.of("cardId", cardId, "status", st);
    }

    // ==================== 折旧 ====================

    /**
     * 折旧预览（不落库）。workloads: cardNo -> 本期工作量（工作量法）。
     * 每行含月折旧额、状态标签（正常/当月新增不提/已提满/待填工作量/已停用）。
     */
    public Map<String, Object> depPreview(String period, Map<String, BigDecimal> workloads) {
        String per = resolvePeriod(period);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> card : cards("使用中")) {
            Map<String, Object> row = depOne(card, per, workloads, false);
            rows.add(row);
            total = total.add(bd(row.get("amount")));
        }
        Map<String, Object> voucher = periodVoucher("ZJ", "ZJ" + per);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", per);
        result.put("total", total.setScale(2, RoundingMode.HALF_UP));
        result.put("rows", rows);
        result.put("voucherExists", voucher != null);
        result.put("voucherId", voucher == null ? null : voucher.get("id"));
        return result;
    }

    /** 执行本期折旧：写明细、更新卡片、生成转字折旧凭证草稿（ZJ{period} 幂等）。 */
    @Transactional
    public Map<String, Object> depExecute(Map<String, Object> body) {
        String per = resolvePeriod(TmsUtil.str(body.get("period")));
        requirePeriodOpen(per);
        if (periodVoucher("ZJ", "ZJ" + per) != null)
            throw new IllegalArgumentException("本期已计提折旧（ZJ" + per + "），凭证未作废前不能重复计提");

        @SuppressWarnings("unchecked")
        Map<String, Object> rawWorkloads = body.get("workloads") instanceof Map
                ? (Map<String, Object>) body.get("workloads") : new LinkedHashMap<>();
        Map<String, BigDecimal> workloads = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rawWorkloads.entrySet())
            workloads.put(TmsUtil.str(e.getKey()), bd(e.getValue()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> card : cards("使用中")) {
            Map<String, Object> row = depOne(card, per, workloads, true);
            if (bd(row.get("amount")).abs().compareTo(EPS) >= 0) rows.add(row);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) total = total.add(bd(r.get("amount")));
        if (total.abs().compareTo(EPS) < 0) throw new IllegalArgumentException("本期无应提折旧（无使用中资产/均已提满/当月新增不提）");

        LocalDate end = periodEnd(per);
        // 凭证分录：借 折旧费用科目（按科目+部门汇总），贷 1602（按部门汇总），1601/1602 均带部门辅助
        Map<String, Map<String, Object>> debitGroups = new LinkedHashMap<>();
        Map<String, Map<String, Object>> creditGroups = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String dept = TmsUtil.str(r.get("departmentCode"));
            String expense = TmsUtil.str(r.get("expenseAccount"));
            BigDecimal amt = bd(r.get("amount"));
            Map<String, Object> aux = new LinkedHashMap<>();
            aux.put("auxDepartment", dept);
            debitGroups.computeIfAbsent(expense + "|" + dept,
                    k -> entry("借", expense, "计提折旧 " + per, BigDecimal.ZERO, aux));
            debitGroups.get(expense + "|" + dept).put("amount",
                    bd(debitGroups.get(expense + "|" + dept).get("amount")).add(amt));
            creditGroups.computeIfAbsent(dept,
                    k -> entry("贷", "1602", "计提折旧 " + per, BigDecimal.ZERO, new LinkedHashMap<>(aux)));
            creditGroups.get(dept).put("amount", bd(creditGroups.get(dept).get("amount")).add(amt));
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.addAll(debitGroups.values());
        entries.addAll(creditGroups.values());
        String voucherId = createVoucher(per, "转", "计提折旧", "ZJ", "ZJ" + per,
                "计提" + per + "固定资产折旧", end, entries);

        for (Map<String, Object> r : rows) {
            BigDecimal amt = bd(r.get("amount")).setScale(2, RoundingMode.HALF_UP);
            jdbc.update("INSERT INTO fin_asset_depreciation(id, card_id, card_no, period, amount, workload, " +
                            "expense_account, department_code, department_name, voucher_id, create_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                    TmsUtil.uuid("AD"), r.get("cardId"), r.get("cardNo"), per, amt,
                    bd(r.get("workload")), r.get("expenseAccount"),
                    r.get("departmentCode"), r.get("departmentName"), voucherId);
            jdbc.update("UPDATE fin_asset_card SET accum_depreciation = accum_depreciation + ?, " +
                            "net_value = original_value - accum_depreciation - ?, last_dep_period = ?, " +
                            "used_workload = used_workload + ? WHERE card_id = ?",
                    amt, amt, per, bd(r.get("workload")), r.get("cardId"));
        }
        TmsUtil.log(jdbc, "finance.gl.asset", "DEPRECIATE", "ZJ" + per,
                "计提折旧 " + rows.size() + " 张卡片，合计 " + total.setScale(2, RoundingMode.HALF_UP) + "，凭证草稿 " + voucherId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", per);
        result.put("total", total.setScale(2, RoundingMode.HALF_UP));
        result.put("count", rows.size());
        result.put("voucherId", voucherId);
        return result;
    }

    /** 期末向导第 ③ 步状态。 */
    public Map<String, Object> wizardStatus(String period) {
        String per = resolvePeriod(period);
        Map<String, Object> voucher = periodVoucher("ZJ", "ZJ" + per);
        if (voucher != null) {
            return Map.of("status", "done", "detail", "本期折旧已生成凭证（" + TmsUtil.str(voucher.get("voucherNo"))
                    + "），请审核过账", "voucherId", voucher.get("id"));
        }
        Map<String, Object> preview = depPreview(per, new LinkedHashMap<>());
        BigDecimal total = bd(preview.get("total"));
        if (total.abs().compareTo(EPS) < 0)
            return Map.of("status", "pass", "detail", "本期无需计提折旧（无使用中资产/均已提满/当月新增不提）");
        long n = ((List<?>) preview.get("rows")).size();
        return Map.of("status", "todo",
                "detail", "本期应提折旧 " + total.setScale(2, RoundingMode.HALF_UP).toPlainString()
                        + "（" + n + " 张卡片），尚未计提");
    }

    // ==================== M8：信息变更（原值禁改） ====================

    /** 卡片信息变更：部门/费用科目/折旧参数可调，原值/残值禁改；全程留痕 fin_asset_change。 */
    @Transactional
    public Map<String, Object> change(Map<String, Object> body) {
        String cardId = TmsUtil.str(body.get("cardId"));
        Map<String, Object> card = cardById(cardId);
        if (card == null) throw new IllegalArgumentException("卡片不存在：" + cardId);
        String status = TmsUtil.str(card.get("status"));
        if (!"使用中".equals(status) && !"已停用".equals(status))
            throw new IllegalArgumentException("卡片状态为「" + status + "」，不能变更");
        if (body.get("originalValue") != null || body.get("salvageValue") != null || body.get("salvageRate") != null)
            throw new IllegalArgumentException("原值/残值不允许变更（金额差错请用拆分/合并/清理处理）");

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (body.containsKey("departmentCode")) {
            before.put("departmentCode", card.get("departmentCode"));
            before.put("departmentName", card.get("departmentName"));
            String deptCode = TmsUtil.str(body.get("departmentCode"));
            String deptName = TmsUtil.str(body.get("departmentName"));
            if (deptName.isEmpty() && !deptCode.isEmpty()) deptName = lookupName("base_department", "department_code", "department_name", deptCode);
            sets.add("department_code = ?"); args.add(deptCode.isEmpty() ? null : deptCode);
            sets.add("department_name = ?"); args.add(deptName.isEmpty() ? null : deptName);
            after.put("departmentCode", deptCode);
            after.put("departmentName", deptName);
        }
        if (body.containsKey("expenseAccount")) {
            String exp = TmsUtil.str(body.get("expenseAccount"));
            if (leafAccount(exp) == null) throw new IllegalArgumentException("折旧费用科目不是末级启用科目：" + exp);
            if (!"借".equals(accountDirection(exp))) throw new IllegalArgumentException("折旧费用科目应为借方科目：" + exp);
            before.put("expenseAccount", card.get("expenseAccount"));
            after.put("expenseAccount", exp);
            sets.add("expense_account = ?"); args.add(exp);
        }
        if (body.containsKey("depreciationMethod")) {
            String method = TmsUtil.str(body.get("depreciationMethod"));
            if (!method.equals("直线") && !method.equals("双倍余额") && !method.equals("年数总和") && !method.equals("工作量"))
                throw new IllegalArgumentException("折旧方法应为：直线/双倍余额/年数总和/工作量");
            int lifeMonths;
            BigDecimal totalWorkload = bd(card.get("totalWorkload"));
            String workloadUnit = TmsUtil.str(card.get("workloadUnit"));
            if (method.equals("工作量")) {
                totalWorkload = bd(body.get("totalWorkload"));
                if (totalWorkload.signum() <= 0) throw new IllegalArgumentException("工作量法必须填写总工作量");
                if (body.containsKey("workloadUnit")) workloadUnit = TmsUtil.str(body.get("workloadUnit"));
                lifeMonths = TmsUtil.toInt(card.get("lifeMonths"));
            } else {
                if (body.get("lifeMonths") != null) lifeMonths = TmsUtil.toInt(body.get("lifeMonths"));
                else if (body.get("lifeYears") != null) lifeMonths = TmsUtil.toInt(body.get("lifeYears")) * 12;
                else lifeMonths = TmsUtil.toInt(card.get("lifeMonths"));
                if (lifeMonths < 12 || lifeMonths % 12 != 0) throw new IllegalArgumentException("折旧年限应为整年且至少 1 年");
            }
            before.put("depreciationMethod", card.get("depreciationMethod"));
            before.put("lifeMonths", card.get("lifeMonths"));
            before.put("totalWorkload", card.get("totalWorkload"));
            before.put("workloadUnit", card.get("workloadUnit"));
            after.put("depreciationMethod", method);
            after.put("lifeMonths", lifeMonths);
            after.put("totalWorkload", totalWorkload);
            after.put("workloadUnit", workloadUnit);
            sets.add("depreciation_method = ?"); args.add(method);
            sets.add("life_months = ?"); args.add(lifeMonths);
            sets.add("total_workload = ?"); args.add(totalWorkload);
            sets.add("workload_unit = ?"); args.add(workloadUnit.isEmpty() ? null : workloadUnit);
        }
        if (sets.isEmpty()) throw new IllegalArgumentException("没有可变更的字段（部门/费用科目/折旧方法）");

        args.add(cardId);
        jdbc.update("UPDATE fin_asset_card SET " + String.join(", ", sets) + " WHERE card_id = ?", args.toArray());

        String changeNo = nextSerialNo("fin_asset_change", "change_no", "BG", 6);
        jdbc.update("INSERT INTO fin_asset_change(change_id, change_no, card_id, card_no, change_type, before_json, after_json, reason, maker_name, create_time) "
                        + "VALUES (?,?,?,?, '变更', ?,?,?,?,CURRENT_TIMESTAMP)",
                TmsUtil.uuid("AH"), changeNo, cardId, TmsUtil.str(card.get("cardNo")),
                json(before), json(after), TmsUtil.str(body.get("reason")), TmsUtil.currentUser());
        TmsUtil.log(jdbc, "finance.gl.asset", "CHANGE", TmsUtil.str(card.get("cardNo")),
                "资产变更 " + changeNo + "：" + after.keySet());
        return Map.of("changeNo", changeNo);
    }

    // ==================== M8：拆分（比例和=1、末张吃尾差、不生凭证） ====================

    @Transactional
    public Map<String, Object> split(Map<String, Object> body) {
        String cardId = TmsUtil.str(body.get("cardId"));
        Map<String, Object> card = requireActiveCard(cardId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = body.get("parts") instanceof List<?> l
                ? l.stream().map(x -> (Map<String, Object>) x).toList() : List.of();
        if (parts.size() < 2) throw new IllegalArgumentException("拆分至少需要 2 个部分");
        BigDecimal ratioSum = BigDecimal.ZERO;
        for (Map<String, Object> p : parts) {
            if (TmsUtil.str(p.get("assetName")).isEmpty()) throw new IllegalArgumentException("每个拆分部分都要填资产名称");
            BigDecimal r = bd(p.get("ratio"));
            if (r.signum() <= 0) throw new IllegalArgumentException("拆分比例必须大于 0");
            ratioSum = ratioSum.add(r);
        }
        if (ratioSum.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.0001")) > 0)
            throw new IllegalArgumentException("拆分比例之和必须等于 1，当前 " + ratioSum);

        BigDecimal orig = bd(card.get("originalValue"));
        BigDecimal accum = bd(card.get("accumDepreciation"));
        BigDecimal salvage = bd(card.get("salvageValue"));
        BigDecimal totalWl = bd(card.get("totalWorkload"));
        BigDecimal usedWl = bd(card.get("usedWorkload"));

        List<Map<String, Object>> newCards = new ArrayList<>();
        BigDecimal[] allocated = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (int i = 0; i < parts.size(); i++) {
            boolean last = i == parts.size() - 1;
            BigDecimal r = bd(parts.get(i).get("ratio"));
            BigDecimal o = last ? orig.subtract(allocated[0]) : orig.multiply(r).setScale(2, RoundingMode.HALF_UP);
            BigDecimal a = last ? accum.subtract(allocated[1]) : accum.multiply(r).setScale(2, RoundingMode.HALF_UP);
            BigDecimal s = last ? salvage.subtract(allocated[2]) : salvage.multiply(r).setScale(2, RoundingMode.HALF_UP);
            BigDecimal t = last ? totalWl.subtract(allocated[3]) : totalWl.multiply(r).setScale(2, RoundingMode.HALF_UP);
            BigDecimal u = last ? usedWl.subtract(allocated[4]) : usedWl.multiply(r).setScale(2, RoundingMode.HALF_UP);
            allocated[0] = allocated[0].add(o); allocated[1] = allocated[1].add(a);
            allocated[2] = allocated[2].add(s); allocated[3] = allocated[3].add(t);
            allocated[4] = allocated[4].add(u);
            String newNo = nextCardNo();
            insertSuccessorCard(card, newNo, TmsUtil.str(parts.get(i).get("assetName")),
                    o, s, a, t, u, "由 " + TmsUtil.str(card.get("cardNo")) + " 拆分");
            Map<String, Object> nc = new LinkedHashMap<>();
            nc.put("cardNo", newNo); nc.put("assetName", parts.get(i).get("assetName"));
            nc.put("ratio", r); nc.put("originalValue", o); nc.put("accumDepreciation", a);
            newCards.add(nc);
        }
        jdbc.update("UPDATE fin_asset_card SET status = '已拆分' WHERE card_id = ?", cardId);
        insertChangeLog(TmsUtil.str(card.get("cardNo")), cardId, "拆分",
                Map.of("sourceCardNo", card.get("cardNo"), "newCards", newCards),
                "拆分 " + parts.size() + " 张");
        TmsUtil.log(jdbc, "finance.gl.asset", "SPLIT", TmsUtil.str(card.get("cardNo")),
                "拆分为 " + parts.size() + " 张：" + newCards.stream().map(c -> TmsUtil.str(c.get("cardNo"))).toList());
        return Map.of("newCards", newCards);
    }

    // ==================== M8：合并（同类限制、不生凭证） ====================

    @Transactional
    public Map<String, Object> merge(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> cardIds = body.get("cardIds") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        if (cardIds.size() < 2) throw new IllegalArgumentException("合并至少选择 2 张卡片");
        String assetName = TmsUtil.str(body.get("assetName"));
        if (assetName.isEmpty()) throw new IllegalArgumentException("合并后资产名称必填");

        List<Map<String, Object>> cards = new ArrayList<>();
        for (String id : cardIds) {
            Map<String, Object> c = requireActiveCard(id);
            cards.add(c);
        }
        for (Map<String, Object> c : cards) {
            if (!TmsUtil.str(c.get("categoryCode")).equals(TmsUtil.str(cards.get(0).get("categoryCode"))))
                throw new IllegalArgumentException("合并卡片必须为同一资产类别");
            if (!TmsUtil.str(c.get("depreciationMethod")).equals(TmsUtil.str(cards.get(0).get("depreciationMethod"))))
                throw new IllegalArgumentException("合并卡片必须使用同一折旧方法");
            if (!TmsUtil.str(c.get("expenseAccount")).equals(TmsUtil.str(cards.get(0).get("expenseAccount"))))
                throw new IllegalArgumentException("合并卡片折旧费用科目不一致");
            if (!TmsUtil.str(c.get("departmentCode")).equals(TmsUtil.str(cards.get(0).get("departmentCode"))))
                throw new IllegalArgumentException("合并卡片必须为同一使用部门");
        }

        BigDecimal orig = BigDecimal.ZERO, accum = BigDecimal.ZERO, salvage = BigDecimal.ZERO,
                totalWl = BigDecimal.ZERO, usedWl = BigDecimal.ZERO;
        int maxLife = 0;
        LocalDate earliest = null;
        for (Map<String, Object> c : cards) {
            orig = orig.add(bd(c.get("originalValue")));
            accum = accum.add(bd(c.get("accumDepreciation")));
            salvage = salvage.add(bd(c.get("salvageValue")));
            totalWl = totalWl.add(bd(c.get("totalWorkload")));
            usedWl = usedWl.add(bd(c.get("usedWorkload")));
            maxLife = Math.max(maxLife, TmsUtil.toInt(c.get("lifeMonths")));
            LocalDate d = TmsUtil.toLocalDate(c.get("acquiredDate"));
            if (earliest == null || (d != null && d.isBefore(earliest))) earliest = d;
        }
        BigDecimal salvageRate = orig.signum() > 0
                ? salvage.divide(orig, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        String newNo = nextCardNo();
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("card_id", TmsUtil.uuid("AC"));
        merged.put("card_no", newNo);
        merged.put("category_code", cards.get(0).get("categoryCode"));
        merged.put("category_name", cards.get(0).get("categoryName"));
        merged.put("asset_name", assetName);
        merged.put("department_code", cards.get(0).get("departmentCode"));
        merged.put("department_name", cards.get(0).get("departmentName"));
        merged.put("acquired_date", java.sql.Date.valueOf(earliest == null ? LocalDate.now() : earliest));
        merged.put("original_value", orig);
        merged.put("salvage_rate", salvageRate);
        merged.put("salvage_value", salvage);
        merged.put("depreciation_method", cards.get(0).get("depreciationMethod"));
        merged.put("life_months", maxLife);
        merged.put("total_workload", totalWl);
        merged.put("used_workload", usedWl);
        merged.put("workload_unit", cards.get(0).get("workloadUnit"));
        merged.put("expense_account", cards.get(0).get("expenseAccount"));
        merged.put("accum_depreciation", accum);
        merged.put("net_value", orig.subtract(accum));
        merged.put("last_dep_period", cards.stream().map(c -> TmsUtil.str(c.get("lastDepPeriod")))
                .filter(s -> !s.isEmpty()).sorted().reduce((a, b) -> b).orElse(null));
        merged.put("voucher_id", null);
        merged.put("status", "使用中");
        merged.put("remark", "由 " + cards.stream().map(c -> TmsUtil.str(c.get("cardNo"))).toList() + " 合并");
        merged.put("maker_name", TmsUtil.currentUser());
        insertCardRow(merged);

        for (Map<String, Object> c : cards) {
            jdbc.update("UPDATE fin_asset_card SET status = '已合并' WHERE card_id = ?", c.get("cardId"));
            insertChangeLog(TmsUtil.str(c.get("cardNo")), TmsUtil.str(c.get("cardId")), "合并",
                    Map.of("sourceCardNo", c.get("cardNo"), "newCardNo", newNo), "合并至 " + newNo);
        }
        TmsUtil.log(jdbc, "finance.gl.asset", "MERGE", newNo,
                cards.size() + " 张卡片合并为 " + newNo + "，原值 " + orig + "，累计折旧 " + accum);
        return Map.of("cardNo", newNo, "originalValue", orig, "accumDepreciation", accum);
    }

    // ==================== M8：清理（1606 过渡，损益 5301/5711） ====================

    /** 清理预览：净值、收支、损益方向、拟生成凭证分录、未提足折旧提醒。 */
    public Map<String, Object> disposalPreview(Map<String, Object> body) {
        Map<String, Object> card = requireDisposableCard(TmsUtil.str(body.get("cardId")));
        BigDecimal income = bd(body.get("incomeAmount"));
        BigDecimal expense = bd(body.get("expenseAmount"));
        String cashAccount = TmsUtil.str(body.get("cashAccount"));
        if (income.add(expense).signum() > 0) {
            if (cashAccount.isEmpty()) throw new IllegalArgumentException("有清理收入/费用时必须选择现金类对方科目");
            if (leafAccount(cashAccount) == null) throw new IllegalArgumentException("对方科目不是末级启用科目：" + cashAccount);
            if (!isCashAccount(cashAccount)) throw new IllegalArgumentException("清理收支对方科目应为现金类科目（is_cash）：" + cashAccount);
        }
        if (income.signum() < 0 || expense.signum() < 0) throw new IllegalArgumentException("清理收入/费用不能为负");

        BigDecimal orig = bd(card.get("originalValue"));
        BigDecimal accum = bd(card.get("accumDepreciation"));
        BigDecimal net = orig.subtract(accum);
        BigDecimal gainLoss = income.subtract(expense).subtract(net);   // 贷余为收益
        String resultType = gainLoss.abs().compareTo(EPS) < 0 ? "持平" : (gainLoss.signum() > 0 ? "收益" : "损失");

        List<Map<String, Object>> entries = disposalEntries(card, orig, accum, net, income, expense, cashAccount, gainLoss);

        String openPeriod = currentOpenPeriod();
        boolean depWarn = bd(card.get("accumDepreciation")).add(EPS).compareTo(orig.subtract(bd(card.get("salvageValue")))) < 0
                && !openPeriod.equals(TmsUtil.str(card.get("lastDepPeriod")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cardId", card.get("cardId"));
        result.put("cardNo", card.get("cardNo"));
        result.put("assetName", card.get("assetName"));
        result.put("period", openPeriod);
        result.put("originalValue", orig);
        result.put("accumDepreciation", accum);
        result.put("netValue", net);
        result.put("incomeAmount", income);
        result.put("expenseAmount", expense);
        result.put("gainLoss", gainLoss.setScale(2, RoundingMode.HALF_UP).abs());
        result.put("resultType", resultType);
        result.put("depWarn", depWarn);
        result.put("entries", entries.stream().map(e -> Map.of(
                "direction", e.get("direction"), "accountCode", e.get("accountCode"),
                "summary", e.get("summary"), "amount", bd(e.get("amount")).setScale(2, RoundingMode.HALF_UP))).toList());
        return result;
    }

    /** 执行清理：生成 QL 转字凭证草稿 + fin_asset_disposal 留痕 + 卡片置「已清理」。 */
    @Transactional
    public Map<String, Object> disposalExecute(Map<String, Object> body) {
        Map<String, Object> preview = disposalPreview(body);
        String cardId = TmsUtil.str(preview.get("cardId"));
        String period = TmsUtil.str(preview.get("period"));
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(1) FROM fin_asset_disposal WHERE card_id = ?", Integer.class, cardId);
        if (exists != null && exists > 0) throw new IllegalArgumentException("该卡片已清理，不能重复清理");

        Map<String, Object> card = cardById(cardId);
        BigDecimal income = bd(preview.get("incomeAmount"));
        BigDecimal expense = bd(preview.get("expenseAmount"));
        BigDecimal gainLoss = bd(preview.get("gainLoss"));
        if ("损失".equals(preview.get("resultType"))) gainLoss = gainLoss.negate();
        List<Map<String, Object>> entries = disposalEntries(card,
                bd(preview.get("originalValue")), bd(preview.get("accumDepreciation")),
                bd(preview.get("netValue")), income, expense,
                TmsUtil.str(body.get("cashAccount")), gainLoss);

        String disposalNo = nextSerialNo("fin_asset_disposal", "disposal_no", "QL", 6);
        String voucherId = createVoucher(period, "转", "资产清理", "QL", disposalNo,
                "清理固定资产 " + preview.get("cardNo") + " " + preview.get("assetName"),
                periodEnd(period), entries);

        jdbc.update("INSERT INTO fin_asset_disposal(disposal_id, disposal_no, card_id, card_no, asset_name, period, "
                        + "original_value, accum_depreciation, net_value, income_amount, expense_amount, gain_loss, "
                        + "result_type, cash_account, voucher_id, status, remark, maker_name, make_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'已清理',?,?,CURRENT_TIMESTAMP)",
                TmsUtil.uuid("AD"), disposalNo, cardId, preview.get("cardNo"), preview.get("assetName"), period,
                preview.get("originalValue"), preview.get("accumDepreciation"), preview.get("netValue"),
                income, expense, gainLoss.setScale(2, RoundingMode.HALF_UP), preview.get("resultType"),
                TmsUtil.str(body.get("cashAccount")).isEmpty() ? null : TmsUtil.str(body.get("cashAccount")),
                voucherId, TmsUtil.str(body.get("remark")), TmsUtil.currentUser());
        jdbc.update("UPDATE fin_asset_card SET status = '已清理' WHERE card_id = ?", cardId);
        TmsUtil.log(jdbc, "finance.gl.asset", "DISPOSAL", disposalNo,
                "清理 " + preview.get("cardNo") + " 净值 " + preview.get("netValue")
                        + "，" + preview.get("resultType") + " " + preview.get("gainLoss") + "，凭证草稿 " + voucherId);
        Map<String, Object> result = new LinkedHashMap<>(preview);
        result.put("disposalNo", disposalNo);
        result.put("voucherId", voucherId);
        return result;
    }

    private List<Map<String, Object>> disposalEntries(Map<String, Object> card, BigDecimal orig, BigDecimal accum,
                                                      BigDecimal net, BigDecimal income, BigDecimal expense,
                                                      String cashAccount, BigDecimal gainLossSigned) {
        String dept = TmsUtil.str(card.get("departmentCode"));
        Map<String, Object> aux = new LinkedHashMap<>();
        aux.put("auxDepartment", dept.isEmpty() ? null : dept);
        String name = TmsUtil.str(card.get("assetName"));
        List<Map<String, Object>> entries = new ArrayList<>();
        // 转入清理：借 1602 累计折旧 / 借 1606 净值 / 贷 1601 原值
        if (accum.signum() > 0) entries.add(entry("借", "1602", "清理固定资产转销折旧 " + name, accum, aux));
        entries.add(entry("借", "1606", "清理固定资产转入净值 " + name, net, null));
        entries.add(entry("贷", "1601", "清理固定资产转出原值 " + name, orig, aux));
        if (income.signum() > 0) {
            Map<String, Object> cashIn = entry("借", cashAccount, "清理固定资产收入 " + name, income, null);
            cashIn.put("cashFlowItem", "CF10");
            entries.add(cashIn);
            entries.add(entry("贷", "1606", "清理固定资产收入 " + name, income, null));
        }
        if (expense.signum() > 0) {
            entries.add(entry("借", "1606", "清理固定资产费用 " + name, expense, null));
            entries.add(entry("贷", cashAccount, "清理固定资产费用 " + name, expense, null));
        }
        if (gainLossSigned.abs().compareTo(EPS) >= 0) {
            if (gainLossSigned.signum() > 0) {
                // 收益：借 1606 / 贷 5301 营业外收入
                entries.add(entry("借", "1606", "结转固定资产清理净收益 " + name, gainLossSigned, null));
                entries.add(entry("贷", "5301", "结转固定资产清理净收益 " + name, gainLossSigned, null));
            } else {
                // 损失：借 5711 营业外支出 / 贷 1606
                BigDecimal loss = gainLossSigned.abs();
                entries.add(entry("借", "5711", "结转固定资产清理净损失 " + name, loss, null));
                entries.add(entry("贷", "1606", "结转固定资产清理净损失 " + name, loss, null));
            }
        }
        return entries;
    }

    // ==================== M8：盘点（盘亏只留痕） ====================

    @Transactional
    public Map<String, Object> checkSave(Map<String, Object> body) {
        String period = resolvePeriod(TmsUtil.str(body.get("period")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = body.get("rows") instanceof List<?> l
                ? l.stream().map(x -> (Map<String, Object>) x).toList() : List.of();
        if (rows.isEmpty()) throw new IllegalArgumentException("盘点明细为空");
        List<Map<String, Object>> enriched = new ArrayList<>();
        int loss = 0, profit = 0;
        for (Map<String, Object> r : rows) {
            Map<String, Object> card = cardById(TmsUtil.str(r.get("cardId")));
            if (card == null) throw new IllegalArgumentException("卡片不存在：" + r.get("cardId"));
            String result = TmsUtil.str(r.get("checkResult"));
            if (!result.equals("相符") && !result.equals("盘亏") && !result.equals("盘盈"))
                throw new IllegalArgumentException("盘点结果应为 相符/盘亏/盘盈");
            if ("盘亏".equals(result)) loss++;
            if ("盘盈".equals(result)) profit++;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("cardId", card.get("cardId"));
            e.put("cardNo", card.get("cardNo"));
            e.put("assetName", card.get("assetName"));
            e.put("departmentName", card.get("departmentName"));
            e.put("bookStatus", card.get("status"));
            e.put("checkResult", result);
            e.put("remark", TmsUtil.str(r.get("remark")));
            enriched.add(e);
        }
        Integer seq = jdbc.queryForObject("SELECT COUNT(1) FROM fin_asset_check WHERE period = ?",
                Integer.class, period);
        String checkNo = String.format("PD%s-%03d", period, (seq == null ? 0 : seq) + 1);
        jdbc.update("INSERT INTO fin_asset_check(check_id, check_no, period, department_code, department_name, "
                        + "total_count, loss_count, profit_count, detail_json, status, remark, maker_name, make_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,'已完成',?,?,CURRENT_TIMESTAMP)",
                TmsUtil.uuid("AK"), checkNo, period,
                TmsUtil.str(body.get("departmentCode")).isEmpty() ? null : TmsUtil.str(body.get("departmentCode")),
                TmsUtil.str(body.get("departmentName")).isEmpty() ? null : TmsUtil.str(body.get("departmentName")),
                enriched.size(), loss, profit, json(enriched),
                TmsUtil.str(body.get("remark")), TmsUtil.currentUser());
        TmsUtil.log(jdbc, "finance.gl.asset", "CHECK", checkNo,
                "盘点 " + enriched.size() + " 项，盘亏 " + loss + "，盘盈 " + profit + "（盘亏盘盈只留痕）");
        return Map.of("checkNo", checkNo, "totalCount", enriched.size(), "lossCount", loss, "profitCount", profit);
    }

    public List<Map<String, Object>> checkList() {
        return TmsUtil.queryCamel(jdbc,
                "SELECT check_id, check_no, period, department_name, total_count, loss_count, profit_count, "
                        + "status, remark, maker_name, make_time FROM fin_asset_check ORDER BY check_no DESC");
    }

    public Map<String, Object> checkDetail(String checkId) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_asset_check WHERE check_id = ?", checkId);
        if (rows.isEmpty()) throw new IllegalArgumentException("盘点单不存在：" + checkId);
        Map<String, Object> head = rows.get(0);
        Object detailJson = head.remove("detailJson");
        List<?> details = new ArrayList<>();
        if (detailJson != null && !TmsUtil.str(detailJson).isEmpty()) {
            try { details = objectMapper.readValue(String.valueOf(detailJson), List.class); }
            catch (Exception ignored) { }
        }
        head.put("details", details);
        return head;
    }

    // ==================== M8：资产台账滚动表 ====================

    /**
     * 台账滚动：期末 = 期初 + 本期新增 − 本期减少（清理）；累计折旧：期末 = 期初 + 本期计提 − 清理转出。
     * 同时列示 GL 1601/1602/折旧费用的 QC/QM/发生额供对照（账外卡片不进 GL，差异属预期）。
     */
    public Map<String, Object> ledger(String period) {
        String per = resolvePeriod(period);
        List<Map<String, Object>> active = new ArrayList<>();
        for (Map<String, Object> c : cards(null))
            if ("使用中".equals(TmsUtil.str(c.get("status"))) || "已停用".equals(TmsUtil.str(c.get("status"))))
                active.add(c);

        BigDecimal endOrig = BigDecimal.ZERO, endAccum = BigDecimal.ZERO, newOrig = BigDecimal.ZERO;
        for (Map<String, Object> c : active) {
            endOrig = endOrig.add(bd(c.get("originalValue")));
            endAccum = endAccum.add(bd(c.get("accumDepreciation")));
            if (acquiredMonth(c).equals(per)) newOrig = newOrig.add(bd(c.get("originalValue")));
        }
        BigDecimal dispOrig = sum0("SELECT COALESCE(SUM(original_value),0) FROM fin_asset_disposal WHERE period = ?", per);
        BigDecimal dispAccum = sum0("SELECT COALESCE(SUM(accum_depreciation),0) FROM fin_asset_disposal WHERE period = ?", per);
        BigDecimal depPeriod = sum0("SELECT COALESCE(SUM(amount),0) FROM fin_asset_depreciation WHERE period = ?", per);

        BigDecimal beginOrig = endOrig.subtract(newOrig).add(dispOrig);
        BigDecimal beginAccum = endAccum.subtract(depPeriod).add(dispAccum);
        BigDecimal origCheck = beginOrig.add(newOrig).subtract(dispOrig).subtract(endOrig).abs();
        BigDecimal accumCheck = beginAccum.add(depPeriod).subtract(dispAccum).subtract(endAccum).abs();

        calc.invalidate();
        Map<String, Object> gl = new LinkedHashMap<>();
        gl.put("a1601Begin", calc.qc("1601", per));
        gl.put("a1601Debit", calc.fsd("1601", per, per));
        gl.put("a1601Credit", calc.fsc("1601", per, per));
        gl.put("a1601End", calc.qm("1601", per));
        gl.put("a1602Begin", calc.qc("1602", per));
        gl.put("a1602Credit", calc.fsc("1602", per, per));
        gl.put("a1602Debit", calc.fsd("1602", per, per));
        gl.put("a1602End", calc.qm("1602", per));
        gl.put("depExpense", calc.fsd("560103", per, per).add(calc.fsd("560202", per, per)));

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(ledgerRow("固定资产原值", beginOrig, newOrig, dispOrig.negate(), endOrig,
                gl.get("a1601Begin"), gl.get("a1601Debit"), gl.get("a1601Credit"), gl.get("a1601End")));
        rows.add(ledgerRow("累计折旧", beginAccum, depPeriod, dispAccum.negate(), endAccum,
                gl.get("a1602Begin"), gl.get("a1602Credit"), gl.get("a1602Debit"), gl.get("a1602End")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", per);
        result.put("rows", rows);
        result.put("depExpenseGl", gl.get("depExpense"));
        result.put("depExpenseLedger", depPeriod);
        result.put("origBalanced", origCheck.compareTo(EPS) < 0);
        result.put("accumBalanced", accumCheck.compareTo(EPS) < 0);
        result.put("activeCount", active.size());
        result.put("disposedCount", jdbc.queryForObject("SELECT COUNT(1) FROM fin_asset_disposal WHERE period = ?",
                Integer.class, per));
        return result;
    }

    private Map<String, Object> ledgerRow(String item, BigDecimal begin, BigDecimal add, BigDecimal reduce,
                                          BigDecimal end, Object glBegin, Object glAdd, Object glReduce, Object glEnd) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("item", item);
        r.put("beginAmount", money(begin));
        r.put("addAmount", money(add));
        r.put("reduceAmount", money(reduce));
        r.put("endAmount", money(end));
        r.put("glBeginAmount", money(bd(glBegin)));
        r.put("glAddAmount", money(bd(glAdd)));
        r.put("glReduceAmount", money(bd(glReduce)));
        r.put("glEndAmount", money(bd(glEnd)));
        return r;
    }

    // ==================== M8 辅助 ====================

    private Map<String, Object> cardById(String cardId) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_asset_card WHERE card_id = ?", cardId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> requireActiveCard(String cardId) {
        Map<String, Object> card = cardById(cardId);
        if (card == null) throw new IllegalArgumentException("卡片不存在：" + cardId);
        if (!"使用中".equals(TmsUtil.str(card.get("status"))))
            throw new IllegalArgumentException("卡片 " + card.get("cardNo") + " 状态为「" + card.get("status") + "」，仅使用中卡片可操作");
        return card;
    }

    private Map<String, Object> requireDisposableCard(String cardId) {
        Map<String, Object> card = cardById(cardId);
        if (card == null) throw new IllegalArgumentException("卡片不存在：" + cardId);
        String st = TmsUtil.str(card.get("status"));
        if (!"使用中".equals(st) && !"已停用".equals(st))
            throw new IllegalArgumentException("卡片 " + card.get("cardNo") + " 状态为「" + st + "」，不能清理");
        return card;
    }

    private boolean isCashAccount(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT is_cash FROM fin_account WHERE account_code = ?", code);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).get("isCash"));
    }

    private String lookupName(String table, String codeCol, String nameCol, String code) {
        if (code.isEmpty()) return "";
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT " + nameCol + " AS name FROM " + table + " WHERE " + codeCol + " = ? LIMIT 1", code);
        return rows.isEmpty() ? "" : TmsUtil.str(rows.get(0).get("name"));
    }

    private String currentOpenPeriod() {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT period FROM fin_accounting_period WHERE status = ? ORDER BY period LIMIT 1",
                GlConst.P_IN_PROGRESS);
        if (rows.isEmpty()) throw new IllegalArgumentException("没有进行中的会计期间");
        return TmsUtil.str(rows.get(0).get("period"));
    }

    private BigDecimal sum0(String sql, Object... args) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
        return v == null ? BigDecimal.ZERO : v;
    }

    private String nextCardNo() {
        Integer cnt = jdbc.queryForObject("SELECT COUNT(1) FROM fin_asset_card", Integer.class);
        return String.format("ZC%06d", (cnt == null ? 0 : cnt) + 1);
    }

    /** 按全表计数生成 QL000001 式流水号。 */
    private String nextSerialNo(String table, String column, String prefix, int width) {
        Integer cnt = jdbc.queryForObject("SELECT COUNT(1) FROM " + table, Integer.class);
        return prefix + String.format("%0" + width + "d", (cnt == null ? 0 : cnt) + 1);
    }

    private String json(Object o) {
        try { return objectMapper.writeValueAsString(o); }
        catch (Exception e) { return "{}"; }
    }

    private void insertChangeLog(String cardNo, String cardId, String type, Object after, String reason) {
        jdbc.update("INSERT INTO fin_asset_change(change_id, change_no, card_id, card_no, change_type, before_json, after_json, reason, maker_name, create_time) "
                        + "VALUES (?,?,?,?,?,NULL,?,?,?,CURRENT_TIMESTAMP)",
                TmsUtil.uuid("AH"), nextSerialNo("fin_asset_change", "change_no", "BG", 6),
                cardId, cardNo, type, json(after), reason, TmsUtil.currentUser());
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /** 拆分后继卡：继承类别/方法/年限/购入日期/费用科目/最后折旧期，净值=原值−拆分累计。 */
    private void insertSuccessorCard(Map<String, Object> src, String cardNo, String assetName,
                                     BigDecimal original, BigDecimal salvage, BigDecimal accum,
                                     BigDecimal totalWorkload, BigDecimal usedWorkload, String remark) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("card_id", TmsUtil.uuid("AC"));
        m.put("card_no", cardNo);
        m.put("category_code", src.get("categoryCode"));
        m.put("category_name", src.get("categoryName"));
        m.put("asset_name", assetName);
        m.put("department_code", src.get("departmentCode"));
        m.put("department_name", src.get("departmentName"));
        m.put("acquired_date", src.get("acquiredDate"));
        m.put("original_value", original);
        m.put("salvage_rate", src.get("salvageRate"));
        m.put("salvage_value", salvage);
        m.put("depreciation_method", src.get("depreciationMethod"));
        m.put("life_months", src.get("lifeMonths"));
        m.put("total_workload", totalWorkload);
        m.put("used_workload", usedWorkload);
        m.put("workload_unit", src.get("workloadUnit"));
        m.put("expense_account", src.get("expenseAccount"));
        m.put("accum_depreciation", accum);
        m.put("net_value", original.subtract(accum));
        m.put("last_dep_period", src.get("lastDepPeriod"));
        m.put("voucher_id", null);
        m.put("status", "使用中");
        m.put("remark", remark);
        m.put("maker_name", TmsUtil.currentUser());
        insertCardRow(m);
    }

    /** 全字段插卡（Map 键与表列同名）。24 个入参列 + make_time 取 CURRENT_TIMESTAMP。 */
    private void insertCardRow(Map<String, Object> m) {
        String placeholders = "?,".repeat(23) + "?";   // 24 个入参列
        jdbc.update("INSERT INTO fin_asset_card(card_id, card_no, category_code, category_name, asset_name, "
                        + "department_code, department_name, acquired_date, original_value, salvage_rate, salvage_value, "
                        + "depreciation_method, life_months, total_workload, used_workload, workload_unit, "
                        + "expense_account, accum_depreciation, net_value, last_dep_period, voucher_id, status, "
                        + "remark, maker_name, make_time) "
                        + "VALUES (" + placeholders + ",CURRENT_TIMESTAMP)",
                m.get("card_id"), m.get("card_no"), m.get("category_code"), m.get("category_name"), m.get("asset_name"),
                m.get("department_code"), m.get("department_name"), m.get("acquired_date"),
                m.get("original_value"), m.get("salvage_rate"), m.get("salvage_value"),
                m.get("depreciation_method"), m.get("life_months"), m.get("total_workload"),
                m.get("used_workload"),
                m.get("workload_unit"), m.get("expense_account"), m.get("accum_depreciation"), m.get("net_value"),
                m.get("last_dep_period"), m.get("voucher_id"), m.get("status"),
                m.get("remark"), m.get("maker_name"));
    }

    // ==================== 单卡折旧计算 ====================

    /** 计算单卡本期折旧（workload 法需传入工作量）。返回台账行 + amount + tag。 */
    private Map<String, Object> depOne(Map<String, Object> card, String period,
                                       Map<String, BigDecimal> workloads, boolean executing) {
        Map<String, Object> row = new LinkedHashMap<>(card);
        BigDecimal amount = BigDecimal.ZERO;
        String tag;
        String cardNo = TmsUtil.str(card.get("cardNo"));
        String method = TmsUtil.str(card.get("depreciationMethod"));
        BigDecimal original = bd(card.get("originalValue"));
        BigDecimal salvage = bd(card.get("salvageValue"));
        BigDecimal base = original.subtract(salvage);
        BigDecimal already = alreadyDep(TmsUtil.str(card.get("cardId")));

        int elapsed = monthsElapsed(acquiredMonth(card), period);
        if (elapsed <= 0) {
            tag = "当月新增不提";
        } else if (already.subtract(base).abs().compareTo(EPS) < 0 || already.compareTo(base) > 0) {
            tag = "已提满";
        } else if (method.equals("工作量")) {
            BigDecimal units = workloads.getOrDefault(cardNo, BigDecimal.ZERO);
            if (units.signum() <= 0) {
                tag = "待填工作量";
            } else {
                BigDecimal totalWorkload = bd(card.get("totalWorkload"));
                BigDecimal rate = base.divide(totalWorkload, 6, RoundingMode.HALF_UP);
                amount = rate.multiply(units).setScale(2, RoundingMode.HALF_UP);
                amount = cap(amount, base.subtract(already));
                tag = "正常";
                row.put("workload", units);
            }
        } else {
            int life = TmsUtil.toInt(card.get("lifeMonths"));
            BigDecimal[] cum = schedule(card, method, original, salvage, life);
            int n = Math.min(elapsed, life);
            BigDecimal expected = cum[n];
            amount = cap(expected.subtract(already), base.subtract(already));
            tag = (n >= life && expected.subtract(base).abs().compareTo(EPS) < 0) ? "提足到期" : "正常";
        }
        row.put("amount", amount.setScale(2, RoundingMode.HALF_UP));
        row.put("tag", tag);
        row.put("alreadyDepreciation", already);
        return row;
    }

    /**
     * 时间法折旧累计表：cum[i] = 第 i 个折旧月份末的累计应折额（i=0..life），cum[life]=应折总额（末月补残值）。
     */
    private BigDecimal[] schedule(Map<String, Object> card, String method,
                                  BigDecimal original, BigDecimal salvage, int lifeMonths) {
        BigDecimal base = original.subtract(salvage);
        BigDecimal[] cum = new BigDecimal[lifeMonths + 1];
        cum[0] = BigDecimal.ZERO;
        int years = lifeMonths / 12;

        if (method.equals("直线")) {
            BigDecimal monthly = base.divide(new BigDecimal(lifeMonths), 2, RoundingMode.HALF_UP);
            for (int i = 1; i <= lifeMonths; i++)
                cum[i] = i == lifeMonths ? base : monthly.multiply(new BigDecimal(i));
            return cum;
        }

        if (method.equals("双倍余额")) {
            int ddbYears = Math.max(years - 2, 0);
            BigDecimal rate = RATE_DDB.divide(new BigDecimal(years), 6, RoundingMode.HALF_UP);
            // 前 ddbYears 年：双倍余额（年折旧按年初净值×率，月折=年折/12）
            BigDecimal annualTotal = BigDecimal.ZERO;
            for (int i = 1; i <= lifeMonths; i++) {
                int k = (i - 1) / 12 + 1;
                if (k <= ddbYears) {
                    BigDecimal annualPrev = BigDecimal.ZERO;
                    for (int j = 1; j < k; j++)
                        annualPrev = annualPrev.add(ddbAnnual(original, rate, j - 1));
                    BigDecimal annualK = ddbAnnual(original, rate, k - 1);
                    BigDecimal monthInYear = new BigDecimal(i - (k - 1) * 12);
                    cum[i] = annualPrev.add(annualK.divide(new BigDecimal(12), 2, RoundingMode.HALF_UP).multiply(monthInYear));
                } else {
                    // 末两年改直线：剩余应折额（净值-残值）/24，最后一月补尾差
                    if (annualTotal.signum() == 0) {
                        for (int j = 1; j <= ddbYears; j++) annualTotal = annualTotal.add(ddbAnnual(original, rate, j - 1));
                    }
                    BigDecimal remain = original.subtract(annualTotal).subtract(salvage);
                    BigDecimal monthlyLast = remain.divide(new BigDecimal(24), 2, RoundingMode.HALF_UP);
                    int monthInStraight = i - ddbYears * 12;
                    cum[i] = monthInStraight == 24
                            ? annualTotal.add(remain)
                            : annualTotal.add(monthlyLast.multiply(new BigDecimal(monthInStraight)));
                }
                if (cum[i].compareTo(base) > 0) cum[i] = base;
            }
            return cum;
        }

        if (method.equals("年数总和")) {
            int sy = years * (years + 1) / 2;
            for (int i = 1; i <= lifeMonths; i++) {
                int k = (i - 1) / 12 + 1;
                BigDecimal annualPrev = BigDecimal.ZERO;
                for (int j = 1; j < k; j++)
                    annualPrev = annualPrev.add(sydAnnual(base, sy, years, j));
                BigDecimal annualK = sydAnnual(base, sy, years, k);
                BigDecimal monthInYear = new BigDecimal(i - (k - 1) * 12);
                cum[i] = i == lifeMonths ? base
                        : annualPrev.add(annualK.divide(new BigDecimal(12), 2, RoundingMode.HALF_UP).multiply(monthInYear));
                if (cum[i].compareTo(base) > 0) cum[i] = base;
            }
            return cum;
        }
        return cum;
    }

    /** 双倍余额第 k 年（k 从 0 起）年折旧额。 */
    private BigDecimal ddbAnnual(BigDecimal original, BigDecimal rate, int k) {
        return original.multiply(BigDecimal.ONE.subtract(rate).pow(k))
                .multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** 年数总和第 year 年（year 从 1 起）年折旧额。 */
    private BigDecimal sydAnnual(BigDecimal base, int sy, int years, int year) {
        return base.multiply(new BigDecimal(years - year + 1))
                .divide(new BigDecimal(sy), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal cap(BigDecimal v, BigDecimal max) {
        if (v.signum() < 0) return BigDecimal.ZERO;
        return v.compareTo(max) > 0 ? max : v;
    }

    private BigDecimal alreadyDep(String cardId) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM fin_asset_depreciation WHERE card_id = ?",
                BigDecimal.class, cardId);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 已过折旧月数：次月起提（购入当月不提）。period/acquired 均为 yyyyMM。 */
    private static int monthsElapsed(String acquiredMonth, String period) {
        int ay = Integer.parseInt(acquiredMonth.substring(0, 4));
        int am = Integer.parseInt(acquiredMonth.substring(4, 6));
        int py = Integer.parseInt(period.substring(0, 4));
        int pm = Integer.parseInt(period.substring(4, 6));
        return (py - ay) * 12 + (pm - am);
    }

    private String acquiredMonth(Map<String, Object> card) {
        Object d = card.get("acquiredDate");
        if (d == null) return "";
        String s = String.valueOf(d);
        return s.replace("-", "").substring(0, 6);
    }

    // ==================== 凭证 / 科目 / 期间辅助 ====================

    private Map<String, Object> category(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT category_code, category_name, default_method, default_life_months, salvage_rate, expense_account " +
                        "FROM fin_asset_category WHERE category_code = ?", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> leafAccount(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT account_code FROM fin_account WHERE account_code = ? AND is_leaf = TRUE AND status = ?",
                code, GlConst.ACCOUNT_ENABLED);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String accountDirection(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT balance_direction FROM fin_account WHERE account_code = ?", code);
        return rows.isEmpty() ? "" : TmsUtil.str(rows.get(0).get("balanceDirection"));
    }

    private String accountAuxDimensions(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT aux_dimensions FROM fin_account WHERE account_code = ?", code);
        if (rows.isEmpty()) return null;
        String s = TmsUtil.str(rows.get(0).get("auxDimensions"));
        return s.isEmpty() ? null : s;
    }

    private Map<String, Object> periodVoucher(String billType, String billNo) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT id, voucher_no, status, summary FROM fin_voucher " +
                        "WHERE source_bill_type = ? AND source_bill_no = ? AND status <> '已作废' LIMIT 1",
                billType, billNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 生成草稿凭证，返回凭证 id。entries: direction/accountCode/summary/amount[/aux(Map)]。 */
    private String createVoucher(String period, String word, String source, String billType,
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
                            "VALUES (?,?,?,?,?,?,?,NULL,NULL,?,?,?,?,?,?,?,?,?,?)",
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
                    aux == null ? null : aux.get("auxText"),
                    e.get("cashFlowItem"));
        }
        return id;
    }

    private static Map<String, Object> entry(String direction, String accountCode, String summary,
                                             BigDecimal amount, Map<String, Object> aux) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("direction", direction);
        e.put("accountCode", accountCode);
        e.put("summary", summary);
        e.put("amount", amount);
        e.put("aux", aux);
        return e;
    }

    private void requirePeriodOpen(String period) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT status FROM fin_accounting_period WHERE period = ?", period);
        if (rows.isEmpty()) throw new IllegalArgumentException("会计期间 " + period + " 不存在");
        if (!GlConst.P_IN_PROGRESS.equals(TmsUtil.str(rows.get(0).get("status"))))
            throw new IllegalArgumentException("会计期间 " + period + " 不是进行中，不能计提折旧");
    }

    private String resolvePeriod(String period) {
        String per = TmsUtil.str(period);
        if (!per.isEmpty()) return per;
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT period FROM fin_accounting_period WHERE status = ? ORDER BY period LIMIT 1",
                GlConst.P_IN_PROGRESS);
        if (rows.isEmpty()) throw new IllegalArgumentException("没有进行中的会计期间，请检查总账初始化");
        return TmsUtil.str(rows.get(0).get("period"));
    }

    private LocalDate periodEnd(String period) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT end_date FROM fin_accounting_period WHERE period = ?", period);
        if (!rows.isEmpty()) {
            Object end = rows.get(0).get("endDate");
            if (end instanceof java.sql.Date d) return d.toLocalDate();
            if (end instanceof LocalDate d) return d;
        }
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        return LocalDate.of(y, m, 1).plusMonths(1).minusDays(1);
    }

    private static BigDecimal bd(Object o) {
        return o == null ? BigDecimal.ZERO : TmsUtil.toBd(o);
    }
}
