package com.erp.report.meta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张报表针对一次查询生成的「查询计划」——分页、总计、异步导出共用这同一份计划，
 * 口径（WHERE/参数/分组/排序/脱敏映射）在元数据层只产一次，杜绝同一份数据被重复统计。
 *
 * <ul>
 *   <li>{@link #detailSelect}：SELECT 片段（不含 FROM），蛇形别名；</li>
 *   <li>{@link #fromWhere}：FROM...WHERE 1=1 + 数据范围 + 筛选条件（向 {@link #args} 顺次绑参）；</li>
 *   <li>{@link #groupBy}：可选 GROUP BY 片段；</li>
 *   <li>总计：分组报表给 {@link #summaryAliases}（对分组结果包一层 SUM），
 *       明细报表给 {@link #grandSummarySelect}（直接对原始行聚合）。</li>
 * </ul>
 */
public class Plan {

    /** 数据范围 deny-all 时置 true，引擎直接返回空结果不打 SQL。 */
    public boolean denyAll;
    public String detailSelect;
    public final StringBuilder fromWhere = new StringBuilder();
    public final List<Object> args = new ArrayList<>();
    public String groupBy = "";
    public Map<String, String> sortWhitelist = new LinkedHashMap<>();
    public String defaultOrder = "ORDER BY 1 DESC";
    /** 明细类总计 SELECT（不含 FROM），与 fromWhere 拼接。 */
    public String grandSummarySelect;
    /** 分组类总计：对分组输出别名再 SUM。 */
    public List<String> summaryAliases = new ArrayList<>();
    /** 输出 key → 敏感字段权限码（透传 FieldMasker）。 */
    public Map<String, String> maskOverrides = new LinkedHashMap<>();

    /**
     * 自定义窗口分页策略：非空时页面/导出不走标准 LIMIT 包装，
     * 由定义按偏移产出「头窗口 + 页内行」SQL（百万行级明细表用）。
     */
    public CustomPageSql customPageSql;

    /** 页/导出批取数后、脱敏前的行级批量补算（如按页补算订单行已入库量）。 */
    public RowEnricher rowEnricher;

    /** 自定义合计完整 SQL 及参数：合计口径与分页主查询不同构时使用（为空走标准合计推导）。 */
    public String grandSql;
    public final List<Object> grandArgs = new ArrayList<>();

    public Plan denyAll() {
        this.denyAll = true;
        return this;
    }
}
