package com.erp.finance.gl;

import com.erp.tms.TmsUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭证模板渲染：事件 payload + 模板分录行 → 凭证明细（不落库）。
 * 支持：金额/条件白名单表达式（GlExprEngine）、GOODS_LINE/EXPENSE_LINE 明细展开、
 * 科目占位符 @AR/@AP/@FUND/@EXPENSE/@BIZ、辅助核算映射（含 "=CODE|名称" 固定值）、
 * 零金额行跳过、同科目同辅助合并、借贷平衡校验、反向事件金额取负（红字）。
 */
@Service
public class GlTemplateRenderService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** @BIZ 映射科目为空 → 该业务类型配置为"不生凭证"，事件应置「已忽略」而非失败。 */
    public static class IgnoreSignal extends RuntimeException {
        public IgnoreSignal(String msg) { super(msg); }
    }

    public static class RenderedEntry {
        public String direction;
        public String summary;
        public String accountCode;
        public String accountName;
        public BigDecimal debit = BigDecimal.ZERO;
        public BigDecimal credit = BigDecimal.ZERO;
        public BigDecimal qty;
        public BigDecimal price;
        public String cashFlowItem;
        public Map<String, String> aux = new LinkedHashMap<>(); // dim -> code
        public String auxKey;
        public String auxText;
    }

    public static class RenderResult {
        public String word;
        public String summary;
        public List<RenderedEntry> entries = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public boolean red;
    }

    public GlTemplateRenderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 渲染事件为凭证明细。
     * @param eventCode 事件码
     * @param payload   事件 payload（已反序列化）
     * @param reverse   是否反向事件（红字，金额取负）
     */
    @SuppressWarnings("unchecked")
    public RenderResult render(String eventCode, Map<String, Object> payload, boolean reverse) {
        Map<String, Object> tpl = head("SELECT id, template_code, event_code, template_name, voucher_word, " +
                "summary_pattern FROM fin_voucher_template WHERE event_code = ? AND enabled = TRUE", eventCode);
        if (tpl == null)
            throw new IllegalArgumentException("事件码「" + eventCode + "」没有启用的凭证模板，请在「凭证模板」中配置或启用模板");

        String templateId = TmsUtil.str(tpl.get("id"));
        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                "SELECT line_no, direction, summary_pattern, account_expr, amount_expr, qty_expr, " +
                        "aux_expr, cash_flow_item, condition_expr, expand_by, lines_key " +
                        "FROM fin_voucher_template_line WHERE template_id = ? AND enabled = TRUE ORDER BY line_no",
                templateId);
        if (lines.isEmpty())
            throw new IllegalArgumentException("模板「" + TmsUtil.str(tpl.get("templateName")) + "」没有启用的分录行");

        RenderResult result = new RenderResult();
        result.word = TmsUtil.str(tpl.get("voucherWord"));
        if (result.word.isEmpty()) result.word = "记";
        result.red = reverse;
        result.summary = fillPattern(TmsUtil.str(tpl.get("summaryPattern")), payload);

        Map<String, Object> topVars = scalarVars(payload);
        int seq = 0;

        for (Map<String, Object> line : lines) {
            String condition = TmsUtil.str(line.get("conditionExpr"));
            // 业务模板条件用宽松模式：payload 稀疏（客户/员工/供应商三选一），缺失字段按空串判断
            if (!condition.isEmpty() && !GlExprEngine.evalCondition(condition, topVars, true)) continue;

            String expandBy = TmsUtil.str(line.get("expandBy"));
            if (expandBy.isEmpty()) expandBy = "NONE";
            String linesKey = TmsUtil.str(line.get("linesKey"));
            if (linesKey.isEmpty()) linesKey = "lines";

            List<Map<String, Object>> candidates = new ArrayList<>();
            if ("NONE".equals(expandBy)) {
                candidates.add(topVars);
            } else {
                Object arr = payload.get(linesKey);
                if (!(arr instanceof List)) continue; // 无明细行 → 该行整体跳过（盘亏/盘盈可能为空）
                for (Object o : (List<Object>) arr) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> merged = new LinkedHashMap<>(topVars);
                    merged.putAll(scalarVars((Map<String, Object>) o));
                    candidates.add(merged);
                }
            }

            for (Map<String, Object> vars : candidates) {
                BigDecimal amount = GlExprEngine.evalAmount(TmsUtil.str(line.get("amountExpr")), vars);
                if (amount.abs().compareTo(new BigDecimal("0.005")) < 0) continue; // 零金额行跳过
                BigDecimal qty = null;
                String qtyExpr = TmsUtil.str(line.get("qtyExpr"));
                if (!qtyExpr.isEmpty()) {
                    qty = GlExprEngine.evalAmount(qtyExpr, vars);
                    if (qty.compareTo(BigDecimal.ZERO) == 0) qty = null;
                }

                String accountExpr = TmsUtil.str(line.get("accountExpr"));
                String[] resolved = resolveAccount(accountExpr, eventCode, payload, vars,
                        TmsUtil.str(line.get("direction")), result.warnings);
                String accountCode = resolved[0];
                String accountName = resolved[1];
                Map<String, Object> acc = accountLeaf(accountCode);
                if (acc == null)
                    throw new IllegalArgumentException("科目「" + accountCode + "」不存在或非末级科目，请检查凭证模板配置");
                if (!GlConst.ACCOUNT_ENABLED.equals(TmsUtil.str(acc.get("status"))))
                    throw new IllegalArgumentException("科目「" + accountCode + " " + accountName + "」已停用，请在科目档案中启用或修改凭证模板科目后重新生成");
                accountName = TmsUtil.str(acc.get("accountName"));

                RenderedEntry e = new RenderedEntry();
                e.direction = TmsUtil.str(line.get("direction"));
                e.summary = fillPattern(TmsUtil.str(line.get("summaryPattern")), vars);
                if (e.summary.isEmpty()) e.summary = result.summary;
                e.accountCode = accountCode;
                e.accountName = accountName;
                if ("借".equals(e.direction)) e.debit = amount; else e.credit = amount;
                e.qty = qty;
                if (qty != null && qty.compareTo(BigDecimal.ZERO) != 0)
                    e.price = amount.divide(qty, 6, RoundingMode.HALF_UP).abs();
                e.cashFlowItem = emptyToNull(TmsUtil.str(line.get("cashFlowItem")));

                applyAux(e, TmsUtil.str(line.get("auxExpr")), vars, TmsUtil.str(acc.get("auxDimensions")));

                result.entries.add(e);
            }
        }

        if (result.entries.isEmpty())
            throw new IllegalArgumentException("模板渲染后没有任何有效分录（金额全为 0 或条件均不满足），请检查事件数据与模板条件");

        mergeEntries(result.entries);

        BigDecimal sumD = BigDecimal.ZERO, sumC = BigDecimal.ZERO;
        for (RenderedEntry e : result.entries) { sumD = sumD.add(e.debit); sumC = sumC.add(e.credit); }
        if (sumD.subtract(sumC).abs().compareTo(new BigDecimal("0.005")) >= 0)
            throw new IllegalArgumentException("模板生成凭证借贷不平衡（借方 " + sumD + " / 贷方 " + sumC
                    + "），请检查模板「" + TmsUtil.str(tpl.get("templateName")) + "」的金额表达式");

        if (reverse) {
            for (RenderedEntry e : result.entries) {
                e.debit = e.debit.negate();
                e.credit = e.credit.negate();
                if (e.qty != null) e.qty = e.qty.negate();
            }
            result.summary = "红冲 " + result.summary;
        }

        // 重排行号
        int n = 1;
        for (RenderedEntry e : result.entries) {
            // 行号在落库时给；这里不设字段
            n++;
        }
        return result;
    }

    // ---------------- 科目占位符解析 ----------------

    /** 返回 [code, name]；@BIZ 空映射抛 IgnoreSignal。 */
    private String[] resolveAccount(String expr, String eventCode, Map<String, Object> payload,
                                    Map<String, Object> vars, String direction, List<String> warnings) {
        if (expr == null || expr.trim().isEmpty())
            throw new IllegalArgumentException("模板分录未配置科目");
        expr = expr.trim();
        if (expr.startsWith("@")) {
            switch (expr) {
                case "@AR": return new String[]{"1122", "应收账款"};
                case "@AP": return new String[]{"2202", "应付账款"};
                case "@FUND": {
                    String code = TmsUtil.str(vars.get("fund_subject_code"));
                    if (code.isEmpty())
                        throw new IllegalArgumentException("资金账户「" + TmsUtil.str(vars.get("fund_account_name"))
                                + "」未映射总账科目，请在「基础档案→资金账户」中配置对应科目后重新生成");
                    return new String[]{code, ""};
                }
                case "@EXPENSE": {
                    String code = TmsUtil.str(vars.get("subject_code"));
                    if (!code.isEmpty()) return new String[]{code, ""};
                    // M4 起费用类型/商品分类档案带 gl 科目列；列不存在时安全跳过
                    String expenseType = TmsUtil.str(vars.get("expense_type_code"));
                    if (!expenseType.isEmpty()) {
                        try {
                            Map<String, Object> r = head(
                                    "SELECT gl_expense_account_code code FROM base_expense_type WHERE expense_type_code = ?",
                                    expenseType);
                            if (r != null && !TmsUtil.str(r.get("code")).isEmpty())
                                return new String[]{TmsUtil.str(r.get("code")), ""};
                        } catch (Exception ignore) { /* 列尚未添加（M4），走默认科目 */ }
                    }
                    String defaultCode = "借".equals(direction) ? "560299" : "5051";
                    warnings.add("费用/收入类型「" + TmsUtil.str(vars.get("expense_type_name"))
                            + "」未配置总账科目，暂挂默认科目 " + defaultCode + "，请在档案中补配对应科目");
                    return new String[]{defaultCode, ""};
                }
                case "@INCOME": return resolveCategorySubject(vars, true, warnings);
                case "@COST":   return resolveCategorySubject(vars, false, warnings);
                case "@BIZ": return resolveBizSubject(eventCode, vars);
                default:
                    throw new IllegalArgumentException("模板配置了未知科目占位符：" + expr);
            }
        }
        return new String[]{expr, ""};
    }

    /** 按事件码+业务类型码查 fin_gl_biz_subject_map。 */
    private String[] resolveBizSubject(String eventCode, Map<String, Object> vars) {
        String bizCode = TmsUtil.str(vars.get("biz_type_code"));
        String bizName = TmsUtil.str(vars.get("biz_type_name"));
        if (bizCode.isEmpty())
            throw new IllegalArgumentException("事件缺少业务类型（biz_type_code），无法解析 @BIZ 对方科目");
        Map<String, Object> row = head(
                "SELECT counter_subject_code, biz_type_name FROM fin_gl_biz_subject_map " +
                        "WHERE event_code = ? AND biz_type_code = ?", eventCode, bizCode);
        if (row == null)
            throw new IllegalArgumentException("业务类型「" + bizName + "」（编码 " + bizCode
                    + "）未配置总账科目，请在「总账→业务类型科目映射」中配置后重新生成");
        String code = TmsUtil.str(row.get("counterSubjectCode"));
        if (code.isEmpty())
            throw new IgnoreSignal("业务类型「" + bizName + "」配置为不生成凭证（对方科目为空），事件自动忽略");
        return new String[]{code, ""};
    }

    /**
     * @INCOME/@COST：按商品分类档案映射收入/成本科目。
     * 商品行 vars.goods_code → base_goods.category_name → base_category.gl_income/gl_cost_account_code；
     * 未配映射回落默认科目（500101 主营业务收入 / 5401 主营业务成本）并警告。
     */
    private String[] resolveCategorySubject(Map<String, Object> vars, boolean income, List<String> warnings) {
        String defaultCode = income ? "500101" : "5401";
        String goodsCode = TmsUtil.str(vars.get("goods_code"));
        String goodsName = TmsUtil.str(vars.get("goods_name"));
        if (!goodsCode.isEmpty()) {
            try {
                Map<String, Object> g = head("SELECT category_name FROM base_goods WHERE goods_code = ?", goodsCode);
                String categoryName = g == null ? "" : TmsUtil.str(g.get("categoryName"));
                if (!categoryName.isEmpty()) {
                    String col = income ? "gl_income_account_code" : "gl_cost_account_code";
                    Map<String, Object> c = head(
                            "SELECT " + col + " code FROM base_category WHERE category_name = ?", categoryName);
                    String mapped = c == null ? "" : TmsUtil.str(c.get("code"));
                    if (!mapped.isEmpty()) return new String[]{mapped, ""};
                }
            } catch (Exception ignore) { /* 映射列缺失等异常不阻断，走默认科目 */ }
        }
        warnings.add((income ? "收入" : "成本") + "科目：商品「"
                + (goodsName.isEmpty() ? goodsCode : goodsName) + "」分类未配置总账科目，暂用默认科目 " + defaultCode
                + "，请在「基础档案→商品分类」中补配");
        return new String[]{defaultCode, ""};
    }

    // ---------------- 辅助核算 ----------------

    private void applyAux(RenderedEntry e, String auxExpr, Map<String, Object> vars, String accAuxDims) {
        if (auxExpr == null || auxExpr.trim().isEmpty()) return;
        Map<String, Object> mapping;
        try {
            mapping = objectMapper.readValue(auxExpr, Map.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("模板辅助核算配置不是合法 JSON：" + auxExpr);
        }
        List<String> accDims = accAuxDims.isEmpty() ? new ArrayList<>()
                : Arrays.asList(accAuxDims.split(","));
        List<String> keyParts = new ArrayList<>();
        List<String> textParts = new ArrayList<>();
        for (String dim : GlConst.AUX_DIMS) {
            Object spec = mapping.get(dim);
            if (spec == null) continue;
            String code, name;
            String specStr = String.valueOf(spec).trim();
            if (specStr.startsWith("=")) {
                // 固定值语法：=CODE|名称 或 =CODE 名称
                String fixed = specStr.substring(1);
                int sep = fixed.indexOf('|');
                if (sep < 0) sep = fixed.indexOf(' ');
                code = sep >= 0 ? fixed.substring(0, sep).trim() : fixed.trim();
                name = sep >= 0 ? fixed.substring(sep + 1).trim() : "";
            } else {
                code = TmsUtil.str(vars.get(specStr));
                if (code.isEmpty()) continue; // payload 无该维度值 → 不挂
                name = TmsUtil.str(vars.get(specStr.replace("_code", "_name")));
                if (name.isEmpty()) name = lookupName(dim, code);
            }
            if (code.isEmpty() || !accDims.contains(dim)) continue; // 科目未启用该维度 → 不挂
            e.aux.put(dim, code);
            keyParts.add(dim + "=" + code);
            String label = GlConst.AUX_LABELS.get(dim);
            textParts.add(label + "：" + (name.isEmpty() ? code : name));
        }
        e.auxKey = keyParts.isEmpty() ? null : String.join("|", keyParts);
        e.auxText = textParts.isEmpty() ? null : String.join("；", textParts);
    }

    private String lookupName(String dim, String code) {
        String sql;
        switch (dim) {
            case "customer":   sql = "SELECT customer_name name FROM base_customer WHERE customer_code = ?"; break;
            case "supplier":   sql = "SELECT supplier_name name FROM base_supplier WHERE supplier_code = ?"; break;
            case "department": sql = "SELECT department_name name FROM base_department WHERE department_code = ?"; break;
            case "employee":   sql = "SELECT employee_name name FROM base_employee WHERE employee_code = ?"; break;
            case "goods":      sql = "SELECT goods_name name FROM base_goods WHERE goods_code = ?"; break;
            case "project":    sql = "SELECT project_name name FROM fin_aux_project WHERE project_code = ?"; break;
            default: return "";
        }
        try {
            Map<String, Object> r = head(sql, code);
            return r == null ? "" : TmsUtil.str(r.get("name"));
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 合并 / 工具 ----------------

    /** 同科目+同方向+同辅助+同现金流量项目的分录合并（GOODS_LINE/EXPENSE_LINE 展开后收口）。 */
    private void mergeEntries(List<RenderedEntry> entries) {
        Map<String, RenderedEntry> merged = new LinkedHashMap<>();
        List<RenderedEntry> order = new ArrayList<>();
        for (RenderedEntry e : entries) {
            String key = e.accountCode + "|" + e.direction + "|" + (e.auxKey == null ? "" : e.auxKey)
                    + "|" + (e.cashFlowItem == null ? "" : e.cashFlowItem);
            RenderedEntry exist = merged.get(key);
            if (exist == null) {
                merged.put(key, e);
                order.add(e);
            } else {
                exist.debit = exist.debit.add(e.debit);
                exist.credit = exist.credit.add(e.credit);
                if (e.qty != null) exist.qty = (exist.qty == null ? BigDecimal.ZERO : exist.qty).add(e.qty);
            }
        }
        entries.clear();
        entries.addAll(order);
    }

    private Map<String, Object> accountLeaf(String code) {
        return head("SELECT account_code, account_name, aux_dimensions, is_qty, is_cash, status " +
                "FROM fin_account WHERE account_code = ? AND is_leaf = TRUE", code);
    }

    private Map<String, Object> head(String sql, Object... args) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 只保留标量字段（数字/字符串/布尔），List/Map 不进表达式变量。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> scalarVars(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> en : src.entrySet()) {
            Object v = en.getValue();
            if (v == null || v instanceof Number || v instanceof String || v instanceof Boolean)
                out.put(en.getKey(), v);
        }
        return out;
    }

    /** {key} 变量替换，缺失变量替换为空。 */
    private String fillPattern(String pattern, Map<String, Object> vars) {
        if (pattern == null || pattern.isEmpty()) return "";
        StringBuffer sb = new StringBuffer();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([a-zA-Z0-9_]+)\\}").matcher(pattern);
        while (m.find()) {
            Object v = vars.get(m.group(1));
            m.appendReplacement(sb, v == null ? "" : java.util.regex.Matcher.quoteReplacement(String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
}
