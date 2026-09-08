package com.erp.finance.gl;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 业务钩子发射器：业务服务在审核/反审核方法内调用 emit/emitReverse。
 * 只发 Spring 事件，不直接写库；落池由 GlBizEventListener 在事务提交后完成。
 * 总账未启用/未初始化时监听器静默跳过，业务侧无需判断。
 */
@Component
public class GlHookEmitter {

    private final ApplicationEventPublisher publisher;

    public GlHookEmitter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** 正向事件（单据审核）。 */
    public void emit(String eventCode, String billType, String billNo,
                     LocalDate bizDate, BigDecimal amount, Map<String, Object> payload) {
        publisher.publishEvent(new GlBizEvent(this, eventCode, billType, billNo,
                bizDate, amount, payload, false, null));
    }

    /** 反向事件（单据反审核，金额取负、红字）。reverseOfEventId 为原事件 id（可空）。 */
    public void emitReverse(String eventCode, String billType, String billNo,
                            LocalDate bizDate, BigDecimal amount, Map<String, Object> payload,
                            String reverseOfEventId) {
        publisher.publishEvent(new GlBizEvent(this, eventCode, billType, billNo,
                bizDate, amount, payload, true, reverseOfEventId));
    }
}
