package com.erp.finance.gl;

import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会计事件池：事件分页/手工补录、批量生成草稿凭证（逐事件成败回写）、
 * 忽略/取消忽略/重置（草稿凭证联动删除）。
 */
@Service
public class GlEventService {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbc;
    private final SysParamService sysParam;
    private final GlTemplateRenderService renderService;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GlEventService(JdbcTemplate jdbc, SysParamService sysParam,
                          GlTemplateRenderService renderService, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.sysParam = sysParam;
        this.renderService = renderService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ==================== 列表 / 统计 ====================

    public PageResult<Map<String, Object>> page(PageRequest request, Map<String, Object> body) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, event_code, source_bill_type, source_bill_no, biz_date, period, amount, " +
                        "status, err_msg, warn_msg, voucher_id, voucher_no, reverse_flag, reverse_of_event_id, " +
                        "process_name, process_time, create_time FROM fin_gl_event WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String eventCode = TmsUtil.str(body.get("eventCode"));
        if (!eventCode.isEmpty()) { sql.append(" AND event_code = ?"); args.add(eventCode); }
        String status = TmsUtil.str(body.get("status"));
        if (!status.isEmpty()) { sql.append(" AND status = ?"); args.add(status); }
        String period = TmsUtil.str(body.get("period"));
        if (!period.isEmpty()) { sql.append(" AND period = ?"); args.add(period); }
        String dateFrom = TmsUtil.str(body.get("dateFrom"));
        if (!dateFrom.isEmpty()) { sql.append(" AND biz_date >= ?"); args.add(java.sql.Date.valueOf(LocalDate.parse(dateFrom))); }
        String dateTo = TmsUtil.str(body.get("dateTo"));
        if (!dateTo.isEmpty()) { sql.append(" AND biz_date <= ?"); args.add(java.sql.Date.valueOf(LocalDate.parse(dateTo))); }
        String keyword = TmsUtil.str(body.get("keyword"));
        if (!keyword.isEmpty()) {
            sql.append(" AND (source_bill_no LIKE ? OR err_msg LIKE ?)");
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        if ("1".equals(TmsUtil.str(body.get("reverseFlag")))) sql.append(" AND reverse_flag = '1'");
        sql.append(" ORDER BY create_time DESC, id DESC");
        List<Map<String, Object>> list = TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
        return PageResult.of(list, request);
    }

    /** 待处理角标数：待生成 + 生成失败。 */
    public Map<String, Object> pendingCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fin_gl_event WHERE status IN ('待生成','生成失败')",
                Integer.class);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", n == null ? 0 : n);
        return r;
    }

    public Map<String, Object> payload(String id) {
        Map<String, Object> ev = head("SELECT id, event_code, source_bill_type, source_bill_no, biz_date, " +
                "amount, status, payload_json FROM fin_gl_event WHERE id = ?", id);
        if (ev == null) throw new IllegalArgumentException("事件不存在");
        Object parsed = null;
        String json = TmsUtil.str(ev.get("payloadJson"));
        if (!json.isEmpty()) {
            try { parsed = objectMapper.readValue(json, Object.class); }
            catch (Exception e) { parsed = json; }
        }
        ev.put("payload", parsed);
        ev.remove("payloadJson");
        return ev;
    }

    // ==================== 手工补录事件（工作台/冒烟用） ====================

    public Map<String, Object> manualEmit(Map<String, Object> body) throws Exception {
        ensureInitialized();
        String eventCode = TmsUtil.str(body.get("eventCode"));
        String billType = TmsUtil.str(body.get("billType"));
        String billNo = TmsUtil.str(body.get("billNo"));
        if (eventCode.isEmpty() || billNo.isEmpty())
            throw new IllegalArgumentException("eventCode 与 billNo 不能为空");
        LocalDate bizDate = TmsUtil.toLocalDate(body.get("bizDate"));
        if (bizDate == null) bizDate = LocalDate.now();
        boolean reverse = Boolean.TRUE.equals(body.get("reverse"));
        String reverseFlag = reverse ? "1" : "0";

        Map<String, Object> exist = head("SELECT id FROM fin_gl_event WHERE source_bill_type = ? " +
                "AND source_bill_no = ? AND event_code = ? AND reverse_flag = ?",
                billType, billNo, eventCode, reverseFlag);
        if (exist != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", exist.get("id")); r.put("duplicated", true);
            return r;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map
                ? (Map<String, Object>) body.get("payload") : new LinkedHashMap<>();
        payload.putIfAbsent("taxpayer", sysParam.get(GlConst.PARAM_TAXPAYER_TYPE, "GENERAL"));
        payload.putIfAbsent("bill_no", billNo);
        payload.putIfAbsent("bill_type", billType);

        BigDecimal amount = body.get("amount") != null ? TmsUtil.toBd(body.get("amount")) : BigDecimal.ZERO;
        String id = TmsUtil.uuid("evt");
        jdbc.update("INSERT INTO fin_gl_event (id, event_code, source_bill_type, source_bill_no, biz_date, period, " +
                        "amount, payload_json, status, reverse_flag) VALUES (?,?,?,?,?,?,?,?, '待生成', ?)",
                id, eventCode, billType, billNo, java.sql.Date.valueOf(bizDate),
                bizDate.format(PERIOD_FMT), amount, objectMapper.writeValueAsString(payload), reverseFlag);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id); r.put("duplicated", false);
        return r;
    }

    // ==================== 批量生成凭证 ====================

    public Map<String, Object> generate(Map<String, Object> body) {
        ensureInitialized();
        List<String> ids = new ArrayList<>();
        Object idsObj = body.get("ids");
        if (idsObj instanceof List<?> l) {
            for (Object o : l) if (o != null && !TmsUtil.str(o).isEmpty()) ids.add(TmsUtil.str(o));
        }
        if (ids.isEmpty() && Boolean.TRUE.equals(body.get("allPending"))) {
            for (Map<String, Object> row : TmsUtil.queryCamel(jdbc,
                    "SELECT id FROM fin_gl_event WHERE status IN ('待生成','生成失败') ORDER BY create_time")) {
                ids.add(TmsUtil.str(row.get("id")));
            }
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0, fail = 0, ignored = 0;
        for (String id : ids) {
            Map<String, Object> r = processOne(id);
            results.add(r);
            String st = TmsUtil.str(r.get("status"));
            if ("已生成".equals(st)) success++;
            else if ("已忽略".equals(st)) ignored++;
            else fail++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", success); out.put("fail", fail); out.put("ignored", ignored);
        out.put("results", results);
        return out;
    }

    /** 处理单个事件：渲染+落草稿凭证在同一事务；失败回滚并回写 err_msg。 */
    private Map<String, Object> processOne(String eventId) {
        Map<String, Object> ev = head("SELECT id, event_code, source_bill_type, source_bill_no, biz_date, period, " +
                "status, reverse_flag, reverse_of_event_id, payload_json FROM fin_gl_event WHERE id = ?", eventId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", eventId);
        r.put("billNo", ev == null ? "" : TmsUtil.str(ev.get("sourceBillNo")));
        if (ev == null) { r.put("status", "生成失败"); r.put("errMsg", "事件不存在"); return r; }
        String status = TmsUtil.str(ev.get("status"));
        if ("已生成".equals(status) || "已冲回".equals(status)
                || "已冲销".equals(status) || "已忽略".equals(status)) {
            r.put("status", status); return r;
        }

        Map<String, Object> payload;
        try {
            String json = TmsUtil.str(ev.get("payloadJson"));
            payload = json.isEmpty() ? new LinkedHashMap<>() : objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            markFail(eventId, "事件 payload 不是合法 JSON：" + e.getMessage());
            r.put("status", "生成失败"); r.put("errMsg", "payload 解析失败"); return r;
        }
        boolean reverse = "1".equals(TmsUtil.str(ev.get("reverseFlag")));
        String eventCode = TmsUtil.str(ev.get("eventCode"));
        LocalDate bizDate0 = TmsUtil.toLocalDate(ev.get("bizDate"));
        final LocalDate bizDate = bizDate0 != null ? bizDate0 : LocalDate.now();
        final String period = bizDate.format(PERIOD_FMT);

        try {
            String voucherId = txTemplate.execute(status1 -> {
                ensurePeriodOpen(period);
                GlTemplateRenderService.RenderResult rr = renderService.render(eventCode, payload, reverse);

                String vid = TmsUtil.uuid("VP");
                String user = TmsUtil.currentUser();
                jdbc.update("INSERT INTO fin_voucher(id, voucher_word, voucher_date, period, attachments, summary, " +
                                "source, source_bill_type, source_bill_no, event_id, status, is_red, " +
                                "maker_name, make_time) VALUES (?,?,?,?,?,?, '自动', ?,?,?, ?, ?, ?, CURRENT_TIMESTAMP)",
                        vid, rr.word, java.sql.Date.valueOf(bizDate), period, 0, rr.summary,
                        TmsUtil.str(ev.get("sourceBillType")), TmsUtil.str(ev.get("sourceBillNo")), eventId,
                        GlConst.V_DRAFT, rr.red, user);

                int lineNo = 1;
                for (GlTemplateRenderService.RenderedEntry e : rr.entries) {
                    jdbc.update("INSERT INTO fin_voucher_entry(id, voucher_id, line_no, summary, account_code, " +
                                    "debit_amount, credit_amount, qty, price, aux_customer, aux_supplier, " +
                                    "aux_department, aux_employee, aux_goods, aux_project, aux_area, aux_key, " +
                                    "aux_text, cash_flow_item) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            TmsUtil.uuid("VE"), vid, lineNo++,
                            e.summary, e.accountCode, e.debit, e.credit, e.qty, e.price,
                            nullIfEmpty(e.aux.get("customer")), nullIfEmpty(e.aux.get("supplier")),
                            nullIfEmpty(e.aux.get("department")), nullIfEmpty(e.aux.get("employee")),
                            nullIfEmpty(e.aux.get("goods")), nullIfEmpty(e.aux.get("project")),
                            nullIfEmpty(e.aux.get("area")),
                            e.auxKey, e.auxText, e.cashFlowItem);
                }

                String warn = rr.warnings.isEmpty() ? null : String.join("；", rr.warnings);
                jdbc.update("UPDATE fin_gl_event SET status = '已生成', voucher_id = ?, warn_msg = ?, " +
                                "err_msg = NULL, process_name = ?, process_time = CURRENT_TIMESTAMP WHERE id = ?",
                        vid, warn, user, eventId);
                // 红字凭证生成成功 → 正向事件置「已冲销」（其已过账凭证保留，红蓝成对留痕）
                if (reverse) {
                    String fwdId = TmsUtil.str(ev.get("reverseOfEventId"));
                    if (fwdId.isEmpty()) {
                        Map<String, Object> fwd = head("SELECT id FROM fin_gl_event WHERE source_bill_type = ? " +
                                        "AND source_bill_no = ? AND event_code = ? AND reverse_flag = '0'",
                                TmsUtil.str(ev.get("sourceBillType")), TmsUtil.str(ev.get("sourceBillNo")), eventCode);
                        if (fwd != null) fwdId = TmsUtil.str(fwd.get("id"));
                    }
                    if (!fwdId.isEmpty()) {
                        int n = jdbc.update("UPDATE fin_gl_event SET status = '已冲销' WHERE id = ? AND status = '已生成'",
                                fwdId);
                        if (n > 0) TmsUtil.log(jdbc, "finance.gl.event", "WRITE_OFF", fwdId,
                                "红字凭证 " + vid + " 生成，正向事件置已冲销");
                    }
                }
                TmsUtil.log(jdbc, "finance.gl.event", "GENERATE", eventId,
                        "事件 " + eventCode + " " + TmsUtil.str(ev.get("sourceBillNo")) + " 生成草稿凭证 " + vid);
                return vid;
            });
            r.put("status", "已生成"); r.put("voucherId", voucherId);
        } catch (GlTemplateRenderService.IgnoreSignal ig) {
            jdbc.update("UPDATE fin_gl_event SET status = '已忽略', warn_msg = ?, process_name = ?, " +
                    "process_time = CURRENT_TIMESTAMP WHERE id = ?",
                    ig.getMessage(), TmsUtil.currentUser(), eventId);
            r.put("status", "已忽略"); r.put("errMsg", ig.getMessage());
        } catch (Exception e) {
            String msg = rootMessage(e);
            markFail(eventId, msg);
            r.put("status", "生成失败"); r.put("errMsg", msg);
        }
        return r;
    }

    private void markFail(String eventId, String msg) {
        String m = msg.length() > 480 ? msg.substring(0, 480) : msg;
        jdbc.update("UPDATE fin_gl_event SET status = '生成失败', err_msg = ?, process_name = ?, " +
                "process_time = CURRENT_TIMESTAMP WHERE id = ?", m, TmsUtil.currentUser(), eventId);
    }

    // ==================== 忽略 / 取消忽略 / 重置 ====================

    public void ignore(String id) {
        Map<String, Object> ev = head("SELECT status FROM fin_gl_event WHERE id = ?", id);
        if (ev == null) throw new IllegalArgumentException("事件不存在");
        String st = TmsUtil.str(ev.get("status"));
        if (!"待生成".equals(st) && !"生成失败".equals(st))
            throw new IllegalArgumentException("只有「待生成/生成失败」的事件可以忽略，当前状态：" + st);
        jdbc.update("UPDATE fin_gl_event SET status = '已忽略', process_name = ?, process_time = CURRENT_TIMESTAMP WHERE id = ?",
                TmsUtil.currentUser(), id);
    }

    public void unignore(String id) {
        Map<String, Object> ev = head("SELECT status FROM fin_gl_event WHERE id = ?", id);
        if (ev == null) throw new IllegalArgumentException("事件不存在");
        if (!"已忽略".equals(TmsUtil.str(ev.get("status"))))
            throw new IllegalArgumentException("只有「已忽略」的事件可以取消忽略");
        jdbc.update("UPDATE fin_gl_event SET status = '待生成' WHERE id = ?", id);
    }

    /** 重置：已生成事件回到待生成；联动删除尚是草稿的凭证，凭证已审核/过账则拒绝。 */
    public void reset(String id) {
        Map<String, Object> ev = head("SELECT status, voucher_id FROM fin_gl_event WHERE id = ?", id);
        if (ev == null) throw new IllegalArgumentException("事件不存在");
        String st = TmsUtil.str(ev.get("status"));
        if (!"已生成".equals(st) && !"生成失败".equals(st))
            throw new IllegalArgumentException("只有「已生成/生成失败」的事件可以重置，当前状态：" + st);
        String voucherId = TmsUtil.str(ev.get("voucherId"));
        if (!voucherId.isEmpty()) {
            Map<String, Object> v = head("SELECT status FROM fin_voucher WHERE id = ?", voucherId);
            if (v != null) {
                String vs = TmsUtil.str(v.get("status"));
                if (!GlConst.V_DRAFT.equals(vs) && !GlConst.V_VOID.equals(vs))
                    throw new IllegalArgumentException("生成的凭证已是「" + vs + "」状态，不能重置事件；如需更正请红冲该凭证");
                jdbc.update("DELETE FROM fin_voucher_entry WHERE voucher_id = ?", voucherId);
                jdbc.update("DELETE FROM fin_voucher WHERE id = ?", voucherId);
            }
        }
        jdbc.update("UPDATE fin_gl_event SET status = '待生成', voucher_id = NULL, voucher_no = NULL, " +
                "err_msg = NULL WHERE id = ?", id);
    }

    // ==================== 基础校验 ====================

    private void ensureInitialized() {
        if (!sysParam.getBool(GlConst.PARAM_INITIALIZED, false))
            throw new IllegalArgumentException("总账尚未启用，请先在「总账初始化」中启用总账");
    }

    private void ensurePeriodOpen(String period) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT status FROM fin_accounting_period WHERE period = ?", period);
        if (rows.isEmpty()) throw new IllegalArgumentException("会计期间 " + period + " 不存在，请先生成期间");
        String st = TmsUtil.str(rows.get(0).get("status"));
        if (GlConst.P_CLOSED.equals(st) || GlConst.P_FROZEN.equals(st))
            throw new IllegalArgumentException("会计期间 " + period + " 已" + st + "，不能再生成凭证");
        if (!GlConst.P_IN_PROGRESS.equals(st))
            throw new IllegalArgumentException("会计期间 " + period + " 未开账，请在期末处理中检查期间状态");
    }

    private Map<String, Object> head(String sql, Object... args) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String nullIfEmpty(String s) { return (s == null || s.isEmpty()) ? null : s; }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return msg == null || msg.isEmpty() ? cur.getClass().getSimpleName() : msg;
    }
}
