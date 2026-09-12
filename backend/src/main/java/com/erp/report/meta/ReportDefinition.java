package com.erp.report.meta;

import com.erp.report.common.ReportQueryRequest;

import java.util.List;

/**
 * 报表语义元数据：一张报表一个 Spring 组件，统一定义「查什么、怎么查、谁能看、列是什么」。
 * 分页 / 总计 / 异步导出 / 钻取落点全部消费同一份定义，新增报表只需实现本接口并注册。
 */
public interface ReportDefinition {

    /** 报表编码（URL 段 / 权限后缀 / 缓存与审计前缀），如 purchase_order_detail。 */
    String code();

    /** 报表中文名（页面标题、导出文件名、任务名）。 */
    String name();

    /** 查看权限点（@RequirePerm 同码 + .view）。 */
    String viewPerm();

    /** true=走 DWS/快照预聚合（缓存 5min）；false=业务表明细实时（缓存 60s）。 */
    boolean dws();

    /** true=汇总类（日期跨度护栏 2 年）；false=明细类（1 年）。 */
    boolean summaryReport();

    /**
     * 月结类报表（#8 进销存汇总 / #9 库存台账）无日期入参时默认自然月本月；
     * 其余报表默认 K2「上月同日的前一天 ~ 昨天」。
     */
    default boolean naturalMonthDefault() { return false; }

    /** 列元数据（前端列、导出表头、敏感控制）。 */
    List<ReportColumnDef> columns();

    /** 按入参产出查询计划（含数据范围拼装）；deny-all 时 plan.denyAll=true。 */
    Plan build(ReportQueryRequest req);

    /** 本次查询的叶子分组字段（驼峰，有序），仅汇总类报表导出层级/小计时使用；明细类返回空。 */
    default List<String> groupFields(ReportQueryRequest req) {
        return List.of();
    }
}
