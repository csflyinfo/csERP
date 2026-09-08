package com.erp.finance.gl;

import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会计凭证服务：手工凭证 CRUD、审核/反审核、过账、作废、红冲。
 * 状态机：草稿 → 已审核 → 已过账；草稿/已审核可作废；已过账只能红冲（红字凭证直接过账）。
 * 凭证号在审核时按「字-期间(yyyyMM)-4 位」生成，作废/红冲留号不补。
 */
@Service
public class GlVoucherService {

    private final JdbcTemplate jdbc;
    private final SysParamService sysParam;

    public GlVoucherService(JdbcTemplate jdbc, SysParamService sysParam) {
        this.jdbc = jdbc;
        this.sysParam = sysParam;
    }

    private void ensureInitialized() {
        if (!sysParam.getBool(GlConst.PARAM_INITIALIZED, false))
            throw new IllegalArgumentException("总账尚未启用，请先在「总账初始化」中启用总账");
    }

    /** 期间必须是「进行中」才能录单/审核/过账。 */
    private void ensurePeriodOpen(String period) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT status FROM fin_accounting_period WHERE period = ?", period);
        if (rows.isEmpty()) throw new IllegalArgumentException("会计期间 " + period + " 不存在，请先生成期间");
        String st = TmsUtil.str(rows.get(0).get("status"));
        if (GlConst.P_CLOSED.equals(st) || GlConst.P_FROZEN.equals(st))
            throw new IllegalArgumentException("会计期间 " + period + " 已" + st + "，不能再做凭证操作");
        if (!GlConst.P_IN_PROGRESS.equals(st))
            throw new IllegalArgumentException("会计期间 " + period + " 未开账，请在期末处理中检查期间状态");
    }

    // ==================== 列表 / 详情 ====================

    public PageResult<Map<String, Object>> page(PageRequest request, Map<String, Object> body) {
        // 注意：TmsUtil.camelizeKey 会先整体小写，SQL 别名禁用驼峰，裸列名由 queryCamel 统一转驼峰
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM fin_voucher WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String period = TmsUtil.str(body.get("period"));
        if (!period.isEmpty()) { sql.append(" AND period = ?"); args.add(period); }
        String word = TmsUtil.str(body.get("voucherWord"));
        if (!word.isEmpty()) { sql.append(" AND voucher_word = ?"); args.add(word); }
        String status = TmsUtil.str(body.get("status"));
        if (!status.isEmpty()) { sql.append(" AND status = ?"); args.add(status); }
        String source = TmsUtil.str(body.get("source"));
        if (!source.isEmpty()) { sql.append(" AND source = ?"); args.add(source); }
        String dateFrom = TmsUtil.str(body.get("dateFrom"));
        if (!dateFrom.isEmpty()) { sql.append(" AND voucher_date >= ?"); args.add(java.sql.Date.valueOf(dateFrom.length() >= 10 ? dateFrom.substring(0, 10) : dateFrom + "-01")); }
        String dateTo = TmsUtil.str(body.get("dateTo"));
        if (!dateTo.isEmpty()) { sql.append(" AND voucher_date <= ?"); args.add(java.sql.Date.valueOf(dateTo.length() >= 10 ? dateTo.substring(0, 10) : dateTo + "-28")); }
        String keyword = TmsUtil.str(body.get("keyword"));
        if (!keyword.isEmpty()) {
            sql.append(" AND (voucher_no LIKE ? OR summary LIKE ? OR source_bill_no LIKE ?)");
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY voucher_date DESC, voucher_no DESC, create_time DESC");
        List<Map<String, Object>> list = TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
        // 合计借贷/分录数（一次 GROUP BY，避免 N+1）
        if (!list.isEmpty()) {
            String placeholders = String.join(",", list.stream().map(x -> "?").toList());
            List<Map<String, Object>> sums = TmsUtil.queryCamel(jdbc,
                    "SELECT voucher_id, SUM(debit_amount) debit_total, SUM(credit_amount) credit_total, " +
                    "COUNT(*) entry_count FROM fin_voucher_entry WHERE voucher_id IN (" + placeholders + ") " +
                    "GROUP BY voucher_id",
                    list.stream().map(v -> v.get("id")).toArray());
            Map<String, Map<String, Object>> sumMap = new HashMap<>();
            for (Map<String, Object> s : sums) sumMap.put(TmsUtil.str(s.get("voucherId")), s);
            for (Map<String, Object> v : list) {
                Map<String, Object> s = sumMap.get(TmsUtil.str(v.get("id")));
                v.put("debitTotal", s == null ? BigDecimal.ZERO : nz(s.get("debitTotal")));
                v.put("creditTotal", s == null ? BigDecimal.ZERO : nz(s.get("creditTotal")));
                v.put("entryCount", s == null ? 0 : ((Number) s.get("entryCount")).intValue());
            }
        }
        return PageResult.of(list, request);
    }

    public Map<String, Object> detail(String id) {
        List<Map<String, Object>> heads = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_voucher WHERE id = ?", id);
        if (heads.isEmpty()) throw new IllegalArgumentException("凭证不存在");
        Map<String, Object> head = heads.get(0);
        List<Map<String, Object>> entries = TmsUtil.queryCamel(jdbc,
                "SELECT e.*, a.account_name, a.aux_dimensions, a.is_qty, a.is_cash " +
                "FROM fin_voucher_entry e LEFT JOIN fin_account a ON a.account_code = e.account_code " +
                "WHERE e.voucher_id = ? ORDER BY e.line_no", id);
        head.put("entries", entries);
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (Map<String, Object> e : entries) {
            debit = debit.add(TmsUtil.toBd(e.get("debitAmount")));
            credit = credit.add(TmsUtil.toBd(e.get("creditAmount")));
        }
        head.put("debitTotal", debit);
        head.put("creditTotal", credit);
        return head;
    }

    // ==================== 保存草稿 ====================

    @Transactional
    public Map<String, Object> saveDraft(Map<String, Object> req) {
        ensureInitialized();
        String id = TmsUtil.str(req.get("id"));
        String word = TmsUtil.str(req.get("voucherWord"));
        if (word.isEmpty()) word = "记";
        if (!List.of("记", "收", "付", "转").contains(word)) throw new IllegalArgumentException("凭证字只能是 记/收/付/转");
        LocalDate date = TmsUtil.toLocalDate(req.get("voucherDate"));
        if (date == null) throw new IllegalArgumentException("凭证日期不能为空");
        String period = date.format(DateTimeFormatter.ofPattern("yyyyMM"));
        ensurePeriodOpen(period);

        Object entriesObj = req.get("entries");
        if (!(entriesObj instanceof List<?> rawList) || rawList.size() < 2)
            throw new IllegalArgumentException("凭证至少需要 2 条分录");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entryReqs = (List<Map<String, Object>>) rawList;

        BigDecimal totalDebit = BigDecimal.ZERO, totalCredit = BigDecimal.ZERO;
        List<Map<String, Object>> validated = new ArrayList<>();
        for (int i = 0; i < entryReqs.size(); i++) {
            Map<String, Object> line = entryReqs.get(i);
            String code = TmsUtil.str(line.get("accountCode"));
            Map<String, Object> acc = accountLeaf(code);
            if (acc == null) throw new IllegalArgumentException("第 " + (i + 1) + " 行科目不存在或不是末级启用科目：" + code);
            BigDecimal debit = TmsUtil.toBd(line.get("debitAmount"));
            BigDecimal credit = TmsUtil.toBd(line.get("creditAmount"));
            if (debit.signum() < 0 || credit.signum() < 0)
                throw new IllegalArgumentException("第 " + (i + 1) + " 行金额不能为负（红冲请用红冲功能）");
            if (debit.signum() == 0 && credit.signum() == 0)
                throw new IllegalArgumentException("第 " + (i + 1) + " 行借贷金额不能同时为 0");
            if (debit.signum() > 0 && credit.signum() > 0)
                throw new IllegalArgumentException("第 " + (i + 1) + " 行不能同时有借方和贷方金额");
            // 辅助核算必填校验
            String auxDim = TmsUtil.str(acc.get("auxDimensions"));
            Map<String, String> aux = extractAndValidateAux(line, auxDim, i + 1);
            BigDecimal qty = TmsUtil.toBd(line.get("qty"));
            BigDecimal price = TmsUtil.toBd(line.get("price"));
            if (Boolean.TRUE.equals(acc.get("isQty")) && qty.signum() == 0)
                throw new IllegalArgumentException("第 " + (i + 1) + " 行科目「" + TmsUtil.str(acc.get("accountName"))
                        + "」为数量核算科目，必须录入数量");
            String summary = TmsUtil.str(line.get("summary"));
            if (summary.isEmpty()) summary = TmsUtil.str(req.get("summary"));
            String cfItem = TmsUtil.str(line.get("cashFlowItem"));
            if (cfItem.isEmpty() && Boolean.TRUE.equals(acc.get("isCash"))) {
                // 现金类科目允许先保存后补（M6 现金流量表补录入口），不阻断
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("acc", acc); v.put("code", code); v.put("debit", debit); v.put("credit", credit);
            v.put("qty", qty); v.put("price", price); v.put("summary", summary); v.put("cf", cfItem);
            v.put("aux", aux);
            validated.add(v);
            totalDebit = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
        }
        if (totalDebit.subtract(totalCredit).abs().compareTo(new BigDecimal("0.005")) >= 0)
            throw new IllegalArgumentException("借贷不平衡：借方合计 " + totalDebit + "，贷方合计 " + totalCredit);

        String user = TmsUtil.currentUser();
        String headSummary = TmsUtil.str(req.get("summary"));
        if (headSummary.isEmpty() && !validated.isEmpty()) headSummary = TmsUtil.str(validated.get(0).get("summary"));
        int attachments = TmsUtil.toInt(req.get("attachments"));

        if (id.isEmpty()) {
            id = TmsUtil.uuid("VP");
            jdbc.update("INSERT INTO fin_voucher(id, voucher_word, voucher_date, period, attachments, summary, " +
                    "source, status, is_red, maker_name, make_time) VALUES (?,?,?,?,?,?, '手工', ?, FALSE, ?, CURRENT_TIMESTAMP)",
                    id, word, java.sql.Date.valueOf(date), period, attachments, headSummary,
                    GlConst.V_DRAFT, user);
        } else {
            Map<String, Object> exist = voucherHead(id);
            if (exist == null) throw new IllegalArgumentException("凭证不存在");
            if (!GlConst.V_DRAFT.equals(TmsUtil.str(exist.get("status"))))
                throw new IllegalArgumentException("只有草稿状态的凭证才能修改");
            ensurePeriodOpen(TmsUtil.str(exist.get("period")));
            jdbc.update("UPDATE fin_voucher SET voucher_word=?, voucher_date=?, period=?, attachments=?, summary=?, " +
                    "update_time=CURRENT_TIMESTAMP WHERE id=?",
                    word, java.sql.Date.valueOf(date), period, attachments, headSummary, id);
            jdbc.update("DELETE FROM fin_voucher_entry WHERE voucher_id = ?", id);
        }

        int lineNo = 1;
        for (Map<String, Object> v : validated) {
            @SuppressWarnings("unchecked")
            Map<String, String> aux = (Map<String, String>) v.get("aux");
            Map<String, Object> acc = (Map<String, Object>) v.get("acc");
            String auxKey = buildAuxKey(aux);
            String auxText = buildAuxText(aux, TmsUtil.str(acc.get("auxDimensions")));
            jdbc.update("INSERT INTO fin_voucher_entry(id, voucher_id, line_no, summary, account_code, " +
                    "debit_amount, credit_amount, qty, price, aux_customer, aux_supplier, aux_department, " +
                    "aux_employee, aux_goods, aux_project, aux_area, aux_key, aux_text, cash_flow_item) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    TmsUtil.uuid("VE"), id, lineNo++, v.get("summary"), v.get("code"),
                    v.get("debit"), v.get("credit"), v.get("qty"), v.get("price"),
                    nullIfEmpty(aux.get(GlConst.DIM_CUSTOMER)), nullIfEmpty(aux.get(GlConst.DIM_SUPPLIER)),
                    nullIfEmpty(aux.get(GlConst.DIM_DEPARTMENT)), nullIfEmpty(aux.get(GlConst.DIM_EMPLOYEE)),
                    nullIfEmpty(aux.get(GlConst.DIM_GOODS)), nullIfEmpty(aux.get(GlConst.DIM_PROJECT)),
                    nullIfEmpty(aux.get(GlConst.DIM_AREA)),
                    auxKey.isEmpty() ? null : auxKey, auxText.isEmpty() ? null : auxText,
                    TmsUtil.str(v.get("cf")).isEmpty() ? null : TmsUtil.str(v.get("cf")));
        }
        TmsUtil.log(jdbc, "finance.gl.voucher", "SAVE", id, "保存凭证草稿 " + word + " " + period + " 借贷 " + totalDebit);
        return detail(id);
    }

    // ==================== 审核 / 反审核 / 过账 / 作废 / 红冲 ====================

    @Transactional
    public void audit(String id) {
        Map<String, Object> v = voucherHead(id);
        if (v == null) throw new IllegalArgumentException("凭证不存在");
        if (!GlConst.V_DRAFT.equals(TmsUtil.str(v.get("status"))))
            throw new IllegalArgumentException("只有草稿凭证才能审核");
        ensurePeriodOpen(TmsUtil.str(v.get("period")));
        if (sysParam.getBool(GlConst.PARAM_MAKER_CHECKER, false)
                && TmsUtil.currentUser().equals(TmsUtil.str(v.get("makerName"))))
            throw new IllegalArgumentException("已开启制审分离：制单人不能审核自己的凭证");
        String voucherNo = nextVoucherNo(TmsUtil.str(v.get("voucherWord")), TmsUtil.str(v.get("period")));
        jdbc.update("UPDATE fin_voucher SET status=?, voucher_no=?, auditor_name=?, audit_time=CURRENT_TIMESTAMP, " +
                "update_time=CURRENT_TIMESTAMP WHERE id=?",
                GlConst.V_AUDITED, voucherNo, TmsUtil.currentUser(), id);
        TmsUtil.log(jdbc, "finance.gl.voucher", "AUDIT", voucherNo, "审核凭证 " + voucherNo);
    }

    @Transactional
    public void unaudit(String id) {
        Map<String, Object> v = voucherHead(id);
        if (v == null) throw new IllegalArgumentException("凭证不存在");
        if (!GlConst.V_AUDITED.equals(TmsUtil.str(v.get("status"))))
            throw new IllegalArgumentException("只有已审核凭证才能反审核");
        jdbc.update("UPDATE fin_voucher SET status=?, auditor_name=NULL, audit_time=NULL, " +
                "update_time=CURRENT_TIMESTAMP WHERE id=?", GlConst.V_DRAFT, id);
        TmsUtil.log(jdbc, "finance.gl.voucher", "UNAUDIT", TmsUtil.str(v.get("voucherNo")), "反审核凭证 " + TmsUtil.str(v.get("voucherNo")));
    }

    @Transactional
    public void post(String id) {
        Map<String, Object> v = voucherHead(id);
        if (v == null) throw new IllegalArgumentException("凭证不存在");
        if (!GlConst.V_AUDITED.equals(TmsUtil.str(v.get("status"))))
            throw new IllegalArgumentException("只有已审核凭证才能过账");
        ensurePeriodOpen(TmsUtil.str(v.get("period")));
        jdbc.update("UPDATE fin_voucher SET status=?, poster_name=?, post_time=CURRENT_TIMESTAMP, " +
                "update_time=CURRENT_TIMESTAMP WHERE id=?",
                GlConst.V_POSTED, TmsUtil.currentUser(), id);
        TmsUtil.log(jdbc, "finance.gl.voucher", "POST", TmsUtil.str(v.get("voucherNo")), "过账凭证 " + TmsUtil.str(v.get("voucherNo")));
    }

    @Transactional
    public void voidVoucher(String id, String reason) {
        Map<String, Object> v = voucherHead(id);
        if (v == null) throw new IllegalArgumentException("凭证不存在");
        String st = TmsUtil.str(v.get("status"));
        if (GlConst.V_POSTED.equals(st)) throw new IllegalArgumentException("已过账凭证不能作废，请使用红冲");
        if (GlConst.V_VOID.equals(st) || GlConst.V_REVERSED.equals(st))
            throw new IllegalArgumentException("凭证已" + st + "，不能重复作废");
        if (GlConst.V_DRAFT.equals(st)) ensurePeriodOpen(TmsUtil.str(v.get("period")));
        jdbc.update("UPDATE fin_voucher SET status=?, void_name=?, void_time=CURRENT_TIMESTAMP, " +
                "update_time=CURRENT_TIMESTAMP WHERE id=?",
                GlConst.V_VOID, TmsUtil.currentUser(), id);
        TmsUtil.log(jdbc, "finance.gl.voucher", "VOID", TmsUtil.str(v.get("voucherNo")),
                "作废凭证 " + TmsUtil.str(v.get("voucherNo")) + (reason.isEmpty() ? "" : " 原因：" + reason));
    }

    /** 红冲：对已过账凭证生成金额取负的红字凭证并直接过账，原凭证置「已冲销」。 */
    @Transactional
    public String redReverse(String id) {
        Map<String, Object> v = voucherHead(id);
        if (v == null) throw new IllegalArgumentException("凭证不存在");
        if (!GlConst.V_POSTED.equals(TmsUtil.str(v.get("status"))))
            throw new IllegalArgumentException("只有已过账凭证才能红冲");
        ensurePeriodOpen(TmsUtil.str(v.get("period")));
        Map<String, Object> orig = detail(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) orig.get("entries");

        String redId = TmsUtil.uuid("VP");
        String user = TmsUtil.currentUser();
        String period = TmsUtil.str(v.get("period"));
        String word = TmsUtil.str(v.get("voucherWord"));
        String redNo = nextVoucherNo(word, period);
        String origNo = TmsUtil.str(v.get("voucherNo"));
        jdbc.update("INSERT INTO fin_voucher(id, voucher_no, voucher_word, voucher_date, period, attachments, summary, " +
                "source, status, is_red, red_source_id, maker_name, make_time, auditor_name, audit_time, " +
                "poster_name, post_time) VALUES (?,?,?,?,?,?,?, '手工', ?, TRUE, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)",
                redId, redNo, word, java.sql.Date.valueOf(LocalDate.now()), period, 0,
                "红冲 " + origNo, GlConst.V_POSTED, id, user, user, user);

        int lineNo = 1;
        for (Map<String, Object> e : entries) {
            BigDecimal debit = TmsUtil.toBd(e.get("debitAmount")).negate();
            BigDecimal credit = TmsUtil.toBd(e.get("creditAmount")).negate();
            BigDecimal qty = TmsUtil.toBd(e.get("qty"));
            jdbc.update("INSERT INTO fin_voucher_entry(id, voucher_id, line_no, summary, account_code, " +
                    "debit_amount, credit_amount, qty, price, aux_customer, aux_supplier, aux_department, " +
                    "aux_employee, aux_goods, aux_project, aux_area, aux_key, aux_text, cash_flow_item) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    TmsUtil.uuid("VE"), redId, lineNo++,
                    "红冲：" + TmsUtil.str(e.get("summary")), TmsUtil.str(e.get("accountCode")),
                    debit, credit, qty.signum() == 0 ? null : qty.negate(), TmsUtil.toBd(e.get("price")),
                    e.get("auxCustomer"), e.get("auxSupplier"), e.get("auxDepartment"),
                    e.get("auxEmployee"), e.get("auxGoods"), e.get("auxProject"), e.get("auxArea"),
                    e.get("auxKey"), e.get("auxText"), e.get("cashFlowItem"));
        }
        jdbc.update("UPDATE fin_voucher SET status=?, update_time=CURRENT_TIMESTAMP WHERE id=?",
                GlConst.V_REVERSED, id);
        TmsUtil.log(jdbc, "finance.gl.voucher", "RED_REVERSE", redNo,
                "红冲凭证 " + origNo + "，生成红字凭证 " + redNo);
        return redNo;
    }

    // ==================== 内部方法 ====================

    /** 凭证号：字-期间-4 位，同期同字 MAX+1（作废/红冲占号不补）。 */
    private String nextVoucherNo(String word, String period) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT voucher_no FROM fin_voucher WHERE voucher_word = ? AND period = ? " +
                "AND voucher_no IS NOT NULL ORDER BY voucher_no DESC", word, period);
        int max = 0;
        for (Map<String, Object> r : rows) {
            String no = TmsUtil.str(r.get("voucherNo"));
            int dash = no.lastIndexOf('-');
            if (dash < 0) continue;
            try { max = Math.max(max, Integer.parseInt(no.substring(dash + 1))); } catch (Exception ignored) {}
        }
        return word + "-" + period + "-" + String.format("%04d", max + 1);
    }

    private Map<String, Object> voucherHead(String id) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT id, voucher_no, voucher_word, voucher_date, period, " +
                "summary, source, status, maker_name FROM fin_voucher WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> accountLeaf(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT account_code, account_name, aux_dimensions, is_qty, is_cash, status " +
                "FROM fin_account WHERE account_code = ? AND is_leaf = TRUE", code);
        if (rows.isEmpty()) return null;
        Map<String, Object> acc = rows.get(0);
        if (!GlConst.ACCOUNT_ENABLED.equals(TmsUtil.str(acc.get("status"))))
            throw new IllegalArgumentException("科目 " + code + " 已停用，不能录凭证");
        return acc;
    }

    /** 提取并校验分录辅助核算值：科目配置的维度必填，未配置的维度不允许传值。 */
    private Map<String, String> extractAndValidateAux(Map<String, Object> line, String auxDim, int lineNo) {
        List<String> dims = auxDim.isEmpty() ? List.of() : List.of(auxDim.split(","));
        Map<String, String> aux = new LinkedHashMap<>();
        for (String dim : GlConst.AUX_DIMS) {
            String val = TmsUtil.str(line.get("aux" + capitalize(dim)));
            aux.put(dim, val);
            if (!val.isEmpty() && !dims.contains(dim))
                throw new IllegalArgumentException("第 " + lineNo + " 行科目未配置「" + GlConst.AUX_LABELS.get(dim) + "」辅助核算");
            if (val.isEmpty() && dims.contains(dim))
                throw new IllegalArgumentException("第 " + lineNo + " 行必须填写辅助核算：" + GlConst.AUX_LABELS.get(dim));
        }
        return aux;
    }

    private String buildAuxKey(Map<String, String> aux) {
        StringBuilder sb = new StringBuilder();
        for (String dim : GlConst.AUX_DIMS) {
            String val = aux.get(dim);
            if (val != null && !val.isEmpty()) {
                if (sb.length() > 0) sb.append('|');
                sb.append(dim).append('=').append(val);
            }
        }
        return sb.toString();
    }

    /** 辅助项展示文本：按「维度中文名：名称」拼接，编码尽量反查名称。 */
    private String buildAuxText(Map<String, String> aux, String auxDim) {
        if (auxDim.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (String dim : auxDim.split(",")) {
            String val = aux.get(dim);
            if (val == null || val.isEmpty()) continue;
            String name = resolveName(dim, val);
            parts.add(GlConst.AUX_LABELS.get(dim) + "：" + name);
        }
        return String.join("；", parts);
    }

    private String resolveName(String dim, String codeOrName) {
        try {
            switch (dim) {
                case GlConst.DIM_CUSTOMER -> {
                    List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                            "SELECT customer_name name FROM base_customer WHERE customer_code = ? LIMIT 1", codeOrName);
                    if (!r.isEmpty()) return TmsUtil.str(r.get(0).get("name"));
                }
                case GlConst.DIM_SUPPLIER -> {
                    List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                            "SELECT supplier_name name FROM base_supplier WHERE supplier_code = ? LIMIT 1", codeOrName);
                    if (!r.isEmpty()) return TmsUtil.str(r.get(0).get("name"));
                }
                case GlConst.DIM_DEPARTMENT -> {
                    List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                            "SELECT department_name name FROM base_department WHERE department_code = ? LIMIT 1", codeOrName);
                    if (!r.isEmpty()) return TmsUtil.str(r.get(0).get("name"));
                }
                case GlConst.DIM_EMPLOYEE -> {
                    List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                            "SELECT employee_name name FROM base_employee WHERE employee_code = ? LIMIT 1", codeOrName);
                    if (!r.isEmpty()) return TmsUtil.str(r.get(0).get("name"));
                }
                case GlConst.DIM_GOODS -> {
                    List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                            "SELECT goods_name name FROM base_goods WHERE goods_code = ? LIMIT 1", codeOrName);
                    if (!r.isEmpty()) return TmsUtil.str(r.get(0).get("name"));
                }
                case GlConst.DIM_PROJECT -> {
                    List<Map<String, Object>> r = TmsUtil.queryCamel(jdbc,
                            "SELECT project_name name FROM fin_aux_project WHERE project_code = ? LIMIT 1", codeOrName);
                    if (!r.isEmpty()) return TmsUtil.str(r.get(0).get("name"));
                }
                default -> { return codeOrName; }
            }
        } catch (Exception ignored) {}
        return codeOrName;
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static BigDecimal nz(Object o) {
        return o == null ? BigDecimal.ZERO : TmsUtil.toBd(o);
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    // ==================== 打印数据（M9） ====================

    /**
     * 凭证打印数据：凭证头 + 分录 + 合计 + 金额大写 + 单位名称。
     * 草稿未审核无凭证号时按「（未审核）」展示，由打印窗口自行排版。
     */
    public Map<String, Object> printData(String id) {
        Map<String, Object> d = detail(id);
        d.put("companyName", sysParam.get("sys.company.name", ""));
        BigDecimal total = nz(d.get("debitTotal"));
        d.put("amountCn", amountInChinese(total));
        // 分录辅助核算文本补全：aux_text 缺失时按各辅助编码现查名称拼展示串
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) d.get("entries");
        for (Map<String, Object> e : entries) {
            if (TmsUtil.str(e.get("auxText")).isEmpty()) e.put("auxText", buildEntryAuxText(e));
        }
        return d;
    }

    /** 按分录 7 个辅助编码拼「维度：名称」串（供打印/显示用）。 */
    private String buildEntryAuxText(Map<String, Object> e) {
        StringBuilder sb = new StringBuilder();
        for (String dim : GlConst.AUX_DIMS) {
            String val = TmsUtil.str(e.get("aux" + capitalize(dim)));
            if (val.isEmpty()) continue;
            if (sb.length() > 0) sb.append("；");
            sb.append(GlConst.AUX_LABELS.get(dim)).append("：").append(resolveName(dim, val));
        }
        return sb.toString();
    }

    private static final String[] CN_NUM = {"零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"};
    private static final String[] CN_UNIT = {"", "拾", "佰", "仟"};
    private static final String[] CN_GROUP = {"", "万", "亿", "万亿"};

    /** 人民币金额大写（元角分，最高到万亿）。 */
    public static String amountInChinese(BigDecimal amount) {
        if (amount == null) amount = BigDecimal.ZERO;
        amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        if (amount.signum() == 0) return "零元整";
        String sign = amount.signum() < 0 ? "负" : "";
        amount = amount.abs();
        long yuan = amount.longValue();
        int frac = amount.multiply(new BigDecimal("100")).setScale(0, java.math.RoundingMode.DOWN).intValue() % 100;
        int jiao = frac / 10;
        int fen = frac % 10;

        StringBuilder sb = new StringBuilder();
        if (yuan > 0) {
            String s = String.valueOf(yuan);
            List<String> groups = new ArrayList<>();
            for (int end = s.length(); end > 0; end -= 4)
                groups.add(s.substring(Math.max(0, end - 4), end));   // 低位在前
            boolean pendingZero = false;
            for (int g = groups.size() - 1; g >= 0; g--) {
                String part = groups.get(g);
                if (Integer.parseInt(part) == 0) { pendingZero = true; continue; }
                for (int i = 0; i < part.length(); i++) {
                    int digit = part.charAt(i) - '0';
                    if (digit == 0) { pendingZero = true; continue; }
                    if (pendingZero && sb.length() > 0) sb.append("零");
                    pendingZero = false;
                    sb.append(CN_NUM[digit]).append(CN_UNIT[part.length() - 1 - i]);
                }
                sb.append(CN_GROUP[g]);
            }
            sb.append("元");
        }
        if (jiao == 0 && fen == 0) {
            sb.append("整");
        } else {
            if (jiao > 0) sb.append(CN_NUM[jiao]).append("角");
            else if (yuan > 0) sb.append("零");
            if (fen > 0) sb.append(CN_NUM[fen]).append("分");
        }
        return sign + sb;
    }
}
