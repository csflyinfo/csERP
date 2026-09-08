package com.erp.finance.gl;

import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭证模板维护：列表（含分录行）、保存（整表覆盖行）、启停、删除（预置禁删）、试渲染预览。
 */
@Service
public class GlTemplateService {

    private final JdbcTemplate jdbc;
    private final GlTemplateRenderService renderService;

    public GlTemplateService(JdbcTemplate jdbc, GlTemplateRenderService renderService) {
        this.jdbc = jdbc;
        this.renderService = renderService;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> tpls = TmsUtil.queryCamel(jdbc,
                "SELECT id, template_code, event_code, template_name, voucher_word, summary_pattern, " +
                        "enabled, is_system, remark FROM fin_voucher_template ORDER BY template_code");
        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                "SELECT id, template_id, line_no, direction, summary_pattern, account_expr, amount_expr, " +
                        "qty_expr, aux_expr, cash_flow_item, condition_expr, expand_by, lines_key, enabled " +
                        "FROM fin_voucher_template_line ORDER BY template_id, line_no");
        Map<String, List<Map<String, Object>>> byTpl = new LinkedHashMap<>();
        for (Map<String, Object> l : lines)
            byTpl.computeIfAbsent(TmsUtil.str(l.get("templateId")), k -> new ArrayList<>()).add(l);
        for (Map<String, Object> t : tpls)
            t.put("lines", byTpl.getOrDefault(TmsUtil.str(t.get("id")), new ArrayList<>()));
        return tpls;
    }

    @Transactional
    public Map<String, Object> save(Map<String, Object> body) {
        String id = TmsUtil.str(body.get("id"));
        String eventCode = TmsUtil.str(body.get("eventCode"));
        String templateName = TmsUtil.str(body.get("templateName"));
        if (eventCode.isEmpty() || templateName.isEmpty())
            throw new IllegalArgumentException("事件码与模板名称不能为空");
        String word = TmsUtil.str(body.get("voucherWord"));
        if (word.isEmpty()) word = "记";
        if (!List.of("记", "收", "付", "转").contains(word))
            throw new IllegalArgumentException("凭证字只能是 记/收/付/转");
        String summaryPattern = TmsUtil.str(body.get("summaryPattern"));
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));

        if (id.isEmpty()) {
            // 同事件码只允许一张启用模板；新增模板若与预置同事件码，则必须停用
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fin_voucher_template WHERE event_code = ?", Integer.class, eventCode);
            if (cnt != null && cnt > 0)
                throw new IllegalArgumentException("事件码「" + eventCode + "」已存在模板，请直接修改预置模板（预置模板不可删）");
            id = TmsUtil.uuid("tpl");
            jdbc.update("INSERT INTO fin_voucher_template (id, template_code, event_code, template_name, voucher_word, " +
                            "summary_pattern, enabled, is_system) VALUES (?,?,?,?,?,?,?, FALSE)",
                    id, eventCode, eventCode, templateName, word, summaryPattern, enabled);
        } else {
            Map<String, Object> exist = head("SELECT id FROM fin_voucher_template WHERE id = ?", id);
            if (exist == null) throw new IllegalArgumentException("模板不存在");
            jdbc.update("UPDATE fin_voucher_template SET template_name = ?, voucher_word = ?, summary_pattern = ?, " +
                    "enabled = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                    templateName, word, summaryPattern, enabled, id);
            jdbc.update("DELETE FROM fin_voucher_template_line WHERE template_id = ?", id);
        }

        Object linesObj = body.get("lines");
        int lineNo = 1;
        if (linesObj instanceof List<?> rawList) {
            for (Object o : rawList) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> l = (Map<String, Object>) o;
                String direction = TmsUtil.str(l.get("direction"));
                String accountExpr = TmsUtil.str(l.get("accountExpr"));
                if (!"借".equals(direction) && !"贷".equals(direction)) continue;
                if (accountExpr.isEmpty()) continue;
                String expandBy = TmsUtil.str(l.get("expandBy"));
                if (expandBy.isEmpty()) expandBy = "NONE";
                String linesKey = TmsUtil.str(l.get("linesKey"));
                if (linesKey.isEmpty()) linesKey = "lines";
                jdbc.update("INSERT INTO fin_voucher_template_line (id, template_id, line_no, direction, " +
                                "summary_pattern, account_expr, amount_expr, qty_expr, aux_expr, cash_flow_item, " +
                                "condition_expr, expand_by, lines_key, enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        TmsUtil.uuid("tln"), id, lineNo++,
                        direction,
                        TmsUtil.str(l.get("summaryPattern")), accountExpr,
                        TmsUtil.str(l.get("amountExpr")), nullIfEmpty(TmsUtil.str(l.get("qtyExpr"))),
                        nullIfEmpty(TmsUtil.str(l.get("auxExpr"))), nullIfEmpty(TmsUtil.str(l.get("cashFlowItem"))),
                        nullIfEmpty(TmsUtil.str(l.get("conditionExpr"))), expandBy, linesKey,
                        !Boolean.FALSE.equals(l.get("enabled")));
            }
        }
        if (lineNo == 1) throw new IllegalArgumentException("模板至少需要 1 条有效分录行");
        TmsUtil.log(jdbc, "finance.gl.template", "SAVE", id, "保存凭证模板 " + eventCode);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        return r;
    }

    public void toggle(String id, boolean enabled) {
        Map<String, Object> t = head("SELECT id FROM fin_voucher_template WHERE id = ?", id);
        if (t == null) throw new IllegalArgumentException("模板不存在");
        jdbc.update("UPDATE fin_voucher_template SET enabled = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                enabled, id);
    }

    public void delete(String id) {
        Map<String, Object> t = head("SELECT is_system FROM fin_voucher_template WHERE id = ?", id);
        if (t == null) throw new IllegalArgumentException("模板不存在");
        if (Boolean.TRUE.equals(t.get("isSystem")))
            throw new IllegalArgumentException("预置模板不可删除，可停用后新建自定义模板");
        jdbc.update("DELETE FROM fin_voucher_template_line WHERE template_id = ?", id);
        jdbc.update("DELETE FROM fin_voucher_template WHERE id = ?", id);
    }

    /** 试渲染：不落库，返回将生成的分录。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> preview(Map<String, Object> body) {
        String eventCode = TmsUtil.str(body.get("eventCode"));
        if (eventCode.isEmpty()) throw new IllegalArgumentException("eventCode 不能为空");
        Map<String, Object> payload;
        Object p = body.get("payload");
        if (p instanceof Map) {
            payload = (Map<String, Object>) p;
        } else {
            String json = TmsUtil.str(p);
            if (json.isEmpty()) payload = new LinkedHashMap<>();
            else {
                try {
                    payload = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
                } catch (Exception e) {
                    throw new IllegalArgumentException("payload 不是合法 JSON：" + e.getMessage());
                }
            }
        }
        boolean reverse = Boolean.TRUE.equals(body.get("reverse"));
        GlTemplateRenderService.RenderResult rr = renderService.render(eventCode, payload, reverse);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("word", rr.word);
        out.put("summary", rr.summary);
        out.put("warnings", rr.warnings);
        List<Map<String, Object>> entries = new ArrayList<>();
        BigDecimalWrap sumD = new BigDecimalWrap(), sumC = new BigDecimalWrap();
        int n = 1;
        for (GlTemplateRenderService.RenderedEntry e : rr.entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", n++);
            row.put("direction", e.direction);
            row.put("summary", e.summary);
            row.put("accountCode", e.accountCode);
            row.put("accountName", e.accountName);
            row.put("debit", e.debit);
            row.put("credit", e.credit);
            row.put("qty", e.qty);
            row.put("auxText", e.auxText);
            row.put("cashFlowItem", e.cashFlowItem);
            entries.add(row);
            sumD.v = sumD.v.add(e.debit);
            sumC.v = sumC.v.add(e.credit);
        }
        out.put("entries", entries);
        out.put("debitTotal", sumD.v);
        out.put("creditTotal", sumC.v);
        return out;
    }

    private static class BigDecimalWrap { java.math.BigDecimal v = java.math.BigDecimal.ZERO; }

    private Map<String, Object> head(String sql, Object... args) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String nullIfEmpty(String s) { return (s == null || s.isEmpty()) ? null : s; }
}
