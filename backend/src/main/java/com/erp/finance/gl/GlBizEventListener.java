package com.erp.finance.gl;

import com.erp.tms.TmsUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会计事件落池监听器。
 * hook 铁律：① 业务事务提交后触发（AFTER_COMMIT），绝不在业务事务里写 fin_gl_event；
 * ② 总账未启用/未初始化/自动事件开关关闭 → 静默跳过；
 * ③ 落池 SQL 极简，catch 一切异常仅记日志（事件池机制兜底）；
 * ④ 幂等四元组（单据类型+单号+事件码+反向标志）重复不重落。
 *
 * 反审核联动（反向事件落池时）：
 *  - 正向事件还没生成凭证     → 正反事件都置「已冲回」，不生成任何凭证；
 *  - 正向凭证尚是「草稿/作废」→ 自动删除草稿凭证，正反事件都置「已冲回」；
 *  - 正向凭证已「已审核/已过账」→ 反向事件保留「待生成」，会计批量生成红字凭证，
 *    红字凭证生成后正向事件置「已冲销」；
 *  - 找不到正向事件（审核时总账未启用等）→ 反向事件置「已忽略」。
 * 重新审核：四元组命中已终结的事件行（已冲回/已冲销，反向行为已冲回/已生成）时
 * 复用该行重置为「待生成」（唯一索引不允许重复插行），未生成凭证的挂起红字事件作废。
 */
@Component
public class GlBizEventListener {

    private static final Logger log = LoggerFactory.getLogger(GlBizEventListener.class);
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbc;
    private final com.erp.system.SysParamService sysParam;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GlBizEventListener(JdbcTemplate jdbc, com.erp.system.SysParamService sysParam,
                              PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.sysParam = sysParam;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGlBizEvent(GlBizEvent ev) {
        try {
            handle(ev);
        } catch (Throwable t) {
            // 落池失败不能影响业务（业务事务已提交），仅日志告警
            log.error("会计事件落池失败 event={} bill={}{} : {}",
                    ev.getEventCode(), ev.getBillType(), ev.getBillNo(), t.toString());
        }
    }

    private void handle(GlBizEvent ev) throws Exception {
        if (!sysParam.getBool(GlConst.PARAM_INITIALIZED, false)) return;       // 总账未建账
        if (!sysParam.getBool(GlConst.PARAM_AUTO_EVENT, true)) return;          // 自动事件开关

        String reverseFlag = ev.isReverse() ? "1" : "0";
        LocalDate bizDate = ev.getBizDate() != null ? ev.getBizDate() : LocalDate.now();
        Map<String, Object> payload = ev.getPayload() != null ? ev.getPayload() : new HashMap<>();
        // 注入纳税人类型（模板税行 condition 用），业务 payload 已带则不覆盖
        payload.putIfAbsent("taxpayer", sysParam.get(GlConst.PARAM_TAXPAYER_TYPE, "GENERAL"));
        payload.putIfAbsent("bill_no", ev.getBillNo());
        payload.putIfAbsent("bill_type", ev.getBillType());
        String json = objectMapper.writeValueAsString(payload);

        // 幂等：同单同事件同方向只落一条
        Map<String, Object> dup = head(
                "SELECT id, status, amount FROM fin_gl_event WHERE source_bill_type = ? AND source_bill_no = ? " +
                        "AND event_code = ? AND reverse_flag = ?",
                ev.getBillType(), ev.getBillNo(), ev.getEventCode(), reverseFlag);

        String eventId;
        if (dup != null) {
            String dupStatus = TmsUtil.str(dup.get("status"));
            boolean reactivatable = ev.isReverse()
                    ? GlConst.E_REVERSED.equals(dupStatus) || GlConst.E_DONE.equals(dupStatus)
                    : GlConst.E_REVERSED.equals(dupStatus) || GlConst.E_WRITTEN_OFF.equals(dupStatus);
            if (!reactivatable) {
                // 正向事件重复到达（重新审核）：上一反审核周期挂起、尚未生成红字凭证的反向事件作废
                if (!ev.isReverse()) killPendingReverse(ev);
                return;
            }
            // 复用已终结的事件行：重置为待生成（凭证链接清空，历史凭证保留，仅事件侧解绑）
            eventId = TmsUtil.str(dup.get("id"));
            String reverseOfId = resolveReverseOfId(ev);
            jdbc.update("UPDATE fin_gl_event SET status = '待生成', payload_json = ?, biz_date = ?, period = ?, " +
                            "amount = ?, err_msg = NULL, warn_msg = NULL, voucher_id = NULL, voucher_no = NULL, " +
                            "reverse_of_event_id = ?, process_name = NULL, process_time = NULL WHERE id = ?",
                    json, java.sql.Date.valueOf(bizDate), bizDate.format(PERIOD_FMT),
                    ev.getAmount() != null ? ev.getAmount() : BigDecimal.ZERO,
                    reverseOfId == null ? null : reverseOfId, eventId);
            log.info("会计事件复用重开：{} {}{} 反向={}", ev.getEventCode(), ev.getBillType(), ev.getBillNo(), ev.isReverse());
        } else {
            eventId = TmsUtil.uuid("evt");
            String reverseOfId = resolveReverseOfId(ev);
            jdbc.update("INSERT INTO fin_gl_event (id, event_code, source_bill_type, source_bill_no, " +
                            "biz_date, period, amount, payload_json, status, reverse_of_event_id, reverse_flag) " +
                            "VALUES (?,?,?,?,?,?,?,?, '待生成', ?, ?)",
                    eventId, ev.getEventCode(), ev.getBillType(), ev.getBillNo(),
                    java.sql.Date.valueOf(bizDate), bizDate.format(PERIOD_FMT),
                    ev.getAmount() != null ? ev.getAmount() : BigDecimal.ZERO,
                    json, reverseOfId, reverseFlag);
            log.info("会计事件落池：{} {}{} 金额={} 反向={}",
                    ev.getEventCode(), ev.getBillType(), ev.getBillNo(), ev.getAmount(), ev.isReverse());
        }

        if (ev.isReverse()) linkReverse(eventId, ev);
    }

    /** 反向事件未显式关联时，按四元组找回正向事件 id 关联。 */
    private String resolveReverseOfId(GlBizEvent ev) {
        if (ev.getReverseOfEventId() != null && !ev.getReverseOfEventId().isEmpty())
            return ev.getReverseOfEventId();
        Map<String, Object> fwd = head(
                "SELECT id FROM fin_gl_event WHERE source_bill_type = ? AND source_bill_no = ? " +
                        "AND event_code = ? AND reverse_flag = '0'",
                ev.getBillType(), ev.getBillNo(), ev.getEventCode());
        return fwd == null ? null : TmsUtil.str(fwd.get("id"));
    }

    /** 重新审核时，把挂起（待生成/生成失败）的红字事件作废，避免事后误生成红字凭证。 */
    private void killPendingReverse(GlBizEvent ev) {
        int n = jdbc.update("UPDATE fin_gl_event SET status = '已冲回', " +
                        "warn_msg = CONCAT(COALESCE(warn_msg,''), '单据重新审核，未生成的红字事件自动作废') " +
                        "WHERE source_bill_type = ? AND source_bill_no = ? AND event_code = ? " +
                        "AND reverse_flag = '1' AND status IN ('待生成','生成失败')",
                ev.getBillType(), ev.getBillNo(), ev.getEventCode());
        if (n > 0) log.info("重新审核清理挂起红字事件：{} {}{} 共 {} 条", ev.getEventCode(), ev.getBillType(), ev.getBillNo(), n);
    }

    /**
     * 反审核联动：根据正向事件/凭证状态决定「直接冲回」还是「留待红字凭证」。
     * 删凭证 + 双事件状态翻转在同一事务内完成。
     */
    private void linkReverse(String reverseEventId, GlBizEvent ev) {
        txTemplate.executeWithoutResult(tx -> {
            Map<String, Object> rev = head(
                    "SELECT id, status FROM fin_gl_event WHERE id = ?", reverseEventId);
            if (rev == null || !"待生成".equals(TmsUtil.str(rev.get("status")))) return;

            String fwdId = resolveReverseOfId(ev);
            Map<String, Object> fwd = fwdId == null ? null : head(
                    "SELECT id, status, voucher_id FROM fin_gl_event WHERE id = ?", fwdId);
            if (fwd == null) {
                // 审核时总账未启用等原因导致没有正向事件，红字事件无对象可冲，忽略之
                jdbc.update("UPDATE fin_gl_event SET status = '已忽略', warn_msg = ? WHERE id = ?",
                        "无对应正向事件（审核时总账可能未启用），无需红字凭证", reverseEventId);
                return;
            }
            jdbc.update("UPDATE fin_gl_event SET reverse_of_event_id = ? WHERE id = ? AND reverse_of_event_id IS NULL",
                    TmsUtil.str(fwd.get("id")), reverseEventId);

            String fwdStatus = TmsUtil.str(fwd.get("status"));
            String voucherId = TmsUtil.str(fwd.get("voucherId"));
            String voucherStatus = "";
            if (!voucherId.isEmpty()) {
                Map<String, Object> v = head("SELECT status FROM fin_voucher WHERE id = ?", voucherId);
                if (v == null) voucherId = "";  // 凭证已不存在，按无凭证处理
                else voucherStatus = TmsUtil.str(v.get("status"));
            }

            if (!voucherId.isEmpty()
                    && !GlConst.V_DRAFT.equals(voucherStatus) && !GlConst.V_VOID.equals(voucherStatus)) {
                // 正向凭证已审核/已过账：不能删，留待会计生成红字凭证冲销
                jdbc.update("UPDATE fin_gl_event SET warn_msg = ? WHERE id = ?",
                        "正向凭证已「" + voucherStatus + "」，请批量生成红字冲销凭证", reverseEventId);
                log.info("反向事件留待红字冲销：{} {}{} 正向凭证 {}({})",
                        ev.getEventCode(), ev.getBillType(), ev.getBillNo(), voucherId, voucherStatus);
                return;
            }

            // 草稿/作废凭证直接删除；无凭证则两事件直接冲回
            if (!voucherId.isEmpty()) {
                jdbc.update("DELETE FROM fin_voucher_entry WHERE voucher_id = ?", voucherId);
                jdbc.update("DELETE FROM fin_voucher WHERE id = ?", voucherId);
                log.info("反审核联动删除草稿凭证：{} （{} {}）", voucherId, ev.getEventCode(), ev.getBillNo());
            }
            jdbc.update("UPDATE fin_gl_event SET status = '已冲回', voucher_id = NULL, voucher_no = NULL, " +
                    "err_msg = NULL WHERE id = ?", TmsUtil.str(fwd.get("id")));
            jdbc.update("UPDATE fin_gl_event SET status = '已冲回', err_msg = NULL WHERE id = ?", reverseEventId);
            TmsUtil.log(jdbc, "finance.gl.event", "REVERSE_LINK", reverseEventId,
                    "反审核联动：事件 " + ev.getEventCode() + " " + ev.getBillNo()
                            + (voucherId.isEmpty() ? " 未生成凭证，双向置已冲回" : " 草稿凭证已删除，双向置已冲回"));
            log.info("反审核联动双向冲回：{} {}{}", ev.getEventCode(), ev.getBillType(), ev.getBillNo());
        });
    }

    private Map<String, Object> head(String sql, Object... args) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
