package com.erp.finance.gl;

import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动转账模板维护（总账 M9）：fin_auto_transfer / fin_auto_transfer_entry 的 CRUD。
 * 期末执行侧仍在 GlPeriodService（transferPreview/transferExecute 只取 enabled=TRUE 的模板）。
 * 规则：系统预置模板（is_system=TRUE，ZZ01/ZZ02）可改可停用、不可删除；
 * 金额公式保存时用当前进行中期间试算（GlReportCalc 白名单函数），试算不过即拒绝，
 * 避免期末执行时才发现公式写错；条件公式用 GlExprEngine 试算（变量 taxpayer/surtax_rate）。
 */
@Service
public class GlTransferService {

    private final JdbcTemplate jdbc;
    private final SysParamService sysParam;
    private final GlReportCalc calc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GlTransferService(JdbcTemplate jdbc, SysParamService sysParam, GlReportCalc calc) {
        this.jdbc = jdbc;
        this.sysParam = sysParam;
        this.calc = calc;
    }

    private void ensureInitialized() {
        if (!sysParam.getBool(GlConst.PARAM_INITIALIZED, false))
            throw new IllegalArgumentException("总账尚未启用，请先在「总账初始化」中启用总账");
    }

    /** 模板列表（含分录），按编号排序。 */
    public List<Map<String, Object>> list() {
        ensureInitialized();
        List<Map<String, Object>> headers = TmsUtil.queryCamel(jdbc,
                "SELECT id, transfer_no, transfer_name, condition_expr, enabled, is_system, remark, create_time " +
                        "FROM fin_auto_transfer ORDER BY transfer_no");
        for (Map<String, Object> h : headers) {
            h.put("entries", TmsUtil.queryCamel(jdbc,
                    "SELECT id, line_no, direction, summary, account_code, amount_expr, aux_config " +
                            "FROM fin_auto_transfer_entry WHERE transfer_id = ? ORDER BY line_no",
                    TmsUtil.str(h.get("id"))));
        }
        return headers;
    }

    /**
     * 新建/更新模板。body: {id?, transferNo?, transferName, conditionExpr?, enabled?, remark?,
     * entries: [{direction, summary, accountCode, amountExpr, auxConfig?}]}
     */
    @Transactional
    public Map<String, Object> save(Map<String, Object> body) {
        ensureInitialized();
        calc.invalidate();
        String id = TmsUtil.str(body.get("id"));
        String no = TmsUtil.str(body.get("transferNo")).trim().toUpperCase();
        String name = TmsUtil.str(body.get("transferName")).trim();
        String condition = TmsUtil.str(body.get("conditionExpr")).trim();
        String remark = TmsUtil.str(body.get("remark")).trim();
        boolean enabled = body.get("enabled") == null || Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        if (name.isEmpty()) throw new IllegalArgumentException("模板名称不能为空");
        if (!no.isEmpty() && !no.matches("ZZ[0-9A-Z]{1,8}"))
            throw new IllegalArgumentException("模板编号应为 ZZ 开头加数字/字母（如 ZZ03），留空则自动编号");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = body.get("entries") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        if (entries.size() < 2) throw new IllegalArgumentException("转账模板至少需要 2 条分录（有借有贷）");

        boolean isSystem = false;
        if (id.isEmpty()) {
            if (no.isEmpty()) no = nextTransferNo();
            else if (transferNoExists(no, null)) throw new IllegalArgumentException("模板编号已存在：" + no);
            id = TmsUtil.uuid("FT");
            jdbc.update("INSERT INTO fin_auto_transfer(id, transfer_no, transfer_name, condition_expr, " +
                            "enabled, is_system, remark, create_time) VALUES (?,?,?,?,?,FALSE,?,CURRENT_TIMESTAMP)",
                    id, no, name, nullIfEmpty(condition), enabled, remark);
        } else {
            List<Map<String, Object>> exist = TmsUtil.queryCamel(jdbc,
                    "SELECT id, transfer_no, is_system FROM fin_auto_transfer WHERE id = ?", id);
            if (exist.isEmpty()) throw new IllegalArgumentException("转账模板不存在或已被删除");
            isSystem = Boolean.TRUE.equals(exist.get(0).get("isSystem"));
            String oldNo = TmsUtil.str(exist.get(0).get("transferNo"));
            if (!no.isEmpty() && !no.equals(oldNo) && transferNoExists(no, id))
                throw new IllegalArgumentException("模板编号已存在：" + no);
            if (no.isEmpty()) no = oldNo;
            jdbc.update("UPDATE fin_auto_transfer SET transfer_no = ?, transfer_name = ?, condition_expr = ?, " +
                            "enabled = ?, remark = ? WHERE id = ?",
                    no, name, nullIfEmpty(condition), enabled, remark, id);
            jdbc.update("DELETE FROM fin_auto_transfer_entry WHERE transfer_id = ?", id);
        }

        // 分录结构校验 + 公式试算（用当前进行中期间；试算只验公式可解析，金额为 0 不阻断）
        String period = currentPeriod();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("taxpayer", sysParam.get(GlConst.PARAM_TAXPAYER_TYPE, "GENERAL"));
        try {
            vars.put("surtax_rate", new BigDecimal(sysParam.get(GlConst.PARAM_SURTAX_RATE, "0.12")));
        } catch (Exception e) {
            vars.put("surtax_rate", new BigDecimal("0.12"));
        }
        if (!condition.isEmpty()) {
            try {
                calc.evalCondition(condition, period, vars);
            } catch (Exception e) {
                throw new IllegalArgumentException("执行条件公式有误：" + condition + "（" + e.getMessage() + "）");
            }
        }
        int lineNo = 0;
        boolean hasDebit = false, hasCredit = false;
        for (Map<String, Object> e : entries) {
            lineNo++;
            String direction = TmsUtil.str(e.get("direction"));
            if (!"借".equals(direction) && !"贷".equals(direction))
                throw new IllegalArgumentException("第 " + lineNo + " 条分录方向必须为「借」或「贷」");
            if ("借".equals(direction)) hasDebit = true; else hasCredit = true;
            String accountCode = TmsUtil.str(e.get("accountCode")).trim();
            if (leafAccount(accountCode) == null)
                throw new IllegalArgumentException("第 " + lineNo + " 条分录科目 " + accountCode + " 不是末级启用科目");
            String expr = TmsUtil.str(e.get("amountExpr")).trim();
            if (expr.isEmpty())
                throw new IllegalArgumentException("第 " + lineNo + " 条分录金额公式不能为空");
            try {
                calc.evalAmount(expr, period);
            } catch (Exception ex) {
                throw new IllegalArgumentException("第 " + lineNo + " 条分录金额公式无法取数：" + expr
                        + "（" + ex.getMessage() + "）");
            }
            String auxConfig = "";
            Object aux = e.get("auxConfig");
            if (aux instanceof Map<?, ?> m) {
                try { auxConfig = objectMapper.writeValueAsString(m); }
                catch (Exception ex) { throw new IllegalArgumentException("第 " + lineNo + " 条分录辅助配置不是合法 JSON"); }
            } else if (aux != null) auxConfig = String.valueOf(aux);
            jdbc.update("INSERT INTO fin_auto_transfer_entry(id, transfer_id, line_no, direction, summary, " +
                            "account_code, amount_expr, aux_config, create_time) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                    TmsUtil.uuid("FE"), id, lineNo, direction,
                    TmsUtil.str(e.get("summary")).trim(), accountCode, expr, nullIfEmpty(auxConfig));
        }
        if (!hasDebit || !hasCredit) throw new IllegalArgumentException("转账模板必须同时有借方和贷方分录");

        TmsUtil.log(jdbc, "finance.gl.transfer", "SAVE", no,
                (isSystem ? "修改系统预置" : "保存") + "转账模板 " + no + " " + name
                        + "（" + entries.size() + " 条分录，" + (enabled ? "启用" : "停用") + "）");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("transferNo", no);
        return r;
    }

    /** 启用/停用。body: {id, enabled} */
    @Transactional
    public Map<String, Object> toggle(Map<String, Object> body) {
        ensureInitialized();
        String id = TmsUtil.str(body.get("id"));
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT transfer_no, transfer_name FROM fin_auto_transfer WHERE id = ?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("转账模板不存在");
        jdbc.update("UPDATE fin_auto_transfer SET enabled = ? WHERE id = ?", enabled, id);
        TmsUtil.log(jdbc, "finance.gl.transfer", "TOGGLE", TmsUtil.str(rows.get(0).get("transferNo")),
                (enabled ? "启用" : "停用") + "转账模板 " + TmsUtil.str(rows.get(0).get("transferName")));
        return Map.of("id", id, "enabled", enabled);
    }

    /** 删除（系统预置模板拒绝）。body: {id} */
    @Transactional
    public void delete(Map<String, Object> body) {
        ensureInitialized();
        String id = TmsUtil.str(body.get("id"));
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT transfer_no, transfer_name, is_system FROM fin_auto_transfer WHERE id = ?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("转账模板不存在");
        if (Boolean.TRUE.equals(rows.get(0).get("isSystem")))
            throw new IllegalArgumentException("系统预置模板（" + TmsUtil.str(rows.get(0).get("transferNo"))
                    + "）不允许删除，如不需要可停用");
        jdbc.update("DELETE FROM fin_auto_transfer_entry WHERE transfer_id = ?", id);
        jdbc.update("DELETE FROM fin_auto_transfer WHERE id = ?", id);
        TmsUtil.log(jdbc, "finance.gl.transfer", "DELETE", TmsUtil.str(rows.get(0).get("transferNo")),
                "删除转账模板 " + TmsUtil.str(rows.get(0).get("transferName")));
    }

    // ==================== 内部工具 ====================

    private boolean transferNoExists(String no, String excludeId) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT id FROM fin_auto_transfer WHERE transfer_no = ?", no);
        if (rows.isEmpty()) return false;
        return excludeId == null || !TmsUtil.str(rows.get(0).get("id")).equals(excludeId);
    }

    /** 下一个模板编号：ZZ + 两位顺序号（ZZ01、ZZ02……）。 */
    private String nextTransferNo() {
        int max = 0;
        for (Map<String, Object> r : TmsUtil.queryCamel(jdbc,
                "SELECT transfer_no no FROM fin_auto_transfer WHERE transfer_no LIKE 'ZZ%'")) {
            String digits = TmsUtil.str(r.get("no")).substring(2);
            try { max = Math.max(max, Integer.parseInt(digits)); } catch (NumberFormatException ignored) {}
        }
        return String.format("ZZ%02d", max + 1);
    }

    private Map<String, Object> leafAccount(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT account_code FROM fin_account WHERE account_code = ? AND is_leaf = TRUE AND status = ?",
                code, GlConst.ACCOUNT_ENABLED);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String currentPeriod() {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT period FROM fin_accounting_period WHERE status = ? ORDER BY period LIMIT 1",
                GlConst.P_IN_PROGRESS);
        if (rows.isEmpty()) throw new IllegalArgumentException("没有进行中的会计期间，请检查总账初始化");
        return TmsUtil.str(rows.get(0).get("period"));
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
