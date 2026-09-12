package com.erp.report.meta;

import java.util.List;

/**
 * 自定义分页 SQL 策略：当标准「全量物化 + 外部排序 + LIMIT」在规模数据下成为瓶颈时
 * （如百万行采购订单行按订单日期倒序翻页），可由定义提供「单据头窗口分页」SQL——
 * 先在万级单据头表上用窗口函数圈定与页码区间相交的少量单据，再 JOIN 行表取数。
 *
 * <p>引擎对页面查询与异步导出均按全局行偏移调用本接口（偏移从 0 起），
 * 取数后统一执行行级填充/脱敏；计数与取数同过滤条件、同数据范围，总数必然一致。
 */
public interface CustomPageSql {

    /** 取一页：全局行序号（1 起）位于 (offset, offset+limit] 的行，结果行按全局顺序排列。 */
    String listSql(long offset, int limit);

    /** 与 {@link #listSql} 对应的参数（窗口边界 + 业务过滤参数，顺序须与占位符一致）。 */
    List<Object> listArgs(long offset, int limit);

    /** 总行数（过滤条件与 listSql 完全一致）。 */
    String countSql();

    /** 计数 SQL 参数。 */
    List<Object> countArgs();
}
