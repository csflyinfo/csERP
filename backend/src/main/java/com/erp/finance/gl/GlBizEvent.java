package com.erp.finance.gl;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 业务会计事件：业务单据审核/反审核时由 GlHookEmitter 发布，
 * 监听器在事务提交后（AFTER_COMMIT）落 fin_gl_event 事件池。
 * 绝不在业务事务里写事件表。
 */
public class GlBizEvent extends ApplicationEvent {

    private final String eventCode;          // PUR_IN/SALE_OUT/RECEIPT/...
    private final String billType;           // 来源单据类型
    private final String billNo;             // 来源单据号
    private final LocalDate bizDate;         // 业务日期
    private final BigDecimal amount;         // 事件金额（含税总额/成本额，列表展示用）
    private final Map<String, Object> payload; // 单据全量快照
    private final boolean reverse;           // 反向事件（反审核，红字）
    private final String reverseOfEventId;   // 反向事件关联的原事件 id

    public GlBizEvent(Object source, String eventCode, String billType, String billNo,
                      LocalDate bizDate, BigDecimal amount, Map<String, Object> payload,
                      boolean reverse, String reverseOfEventId) {
        super(source);
        this.eventCode = eventCode;
        this.billType = billType;
        this.billNo = billNo;
        this.bizDate = bizDate;
        this.amount = amount;
        this.payload = payload;
        this.reverse = reverse;
        this.reverseOfEventId = reverseOfEventId;
    }

    public String getEventCode() { return eventCode; }
    public String getBillType() { return billType; }
    public String getBillNo() { return billNo; }
    public LocalDate getBizDate() { return bizDate; }
    public BigDecimal getAmount() { return amount; }
    public Map<String, Object> getPayload() { return payload; }
    public boolean isReverse() { return reverse; }
    public String getReverseOfEventId() { return reverseOfEventId; }
}
