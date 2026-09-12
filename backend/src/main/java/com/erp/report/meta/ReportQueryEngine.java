package com.erp.report.meta;

import com.erp.common.api.PageResult;
import com.erp.common.security.FieldMasker;
import com.erp.report.common.ReportCamel;
import com.erp.report.common.ReportGuard;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.common.SqlPager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 报表查询引擎：消费 {@link ReportDefinition} 元数据，对外提供三种消费方式——
 * 分页（页面）、总计（合计行）、全量流式（异步导出）；三者共用同一个 {@link Plan}，
 * 同口径、同数据范围、同脱敏，页面看到的数与导出的数必然一致。
 *
 * <p>查询走报表只读连接池 {@code reportJdbcTemplate}，并发/超时/缓存/审计由 {@link ReportGuard} 兜底。
 */
@Component
public class ReportQueryEngine {

    /** 异步导出单表上限（超过走数据范围收窄；与深分页护栏一致量级）。 */
    public static final int EXPORT_CAP = 100_000;
    private static final int EXPORT_BATCH = 2_000;

    private final JdbcTemplate reportJdbc;
    private final ReportGuard guard;
    private final FieldMasker fieldMasker;

    public ReportQueryEngine(@Qualifier("reportJdbcTemplate") JdbcTemplate reportJdbc,
                             ReportGuard guard, FieldMasker fieldMasker) {
        this.reportJdbc = reportJdbc;
        this.guard = guard;
        this.fieldMasker = fieldMasker;
    }

    /** 汇总类报表一次返回的叶子分组行数硬上限（超出引导加层级/缩范围/走导出）。 */
    public static final int SUMMARY_ROW_CAP = 100_000;

    /** 页面分页查询（含总计）。 */
    public PageResult<Map<String, Object>> page(ReportDefinition def, Map<String, Object> body) {
        ReportQueryRequest req = ReportQueryRequest.from(body, def.naturalMonthDefault());
        validateSpan(def, req);
        if (def.summaryReport()) {
            return summaryPage(def, body, req);
        }
        return guard.run("rpt." + def.code(), body, def.dws(), () -> {
            Plan plan = def.build(req);
            if (plan.denyAll) {
                return new PageResult<>(List.of(), req.pageNo(), req.pageSize(), 0, zeroSummary(def));
            }
            List<Map<String, Object>> rawRows;
            long total;
            int pageNo;
            int pageSize;
            if (plan.customPageSql != null) {
                // 头窗口分页：计数/取页均由定义按偏移产出，避免百万行外部排序
                CustomPageSql cp = plan.customPageSql;
                Long t = reportJdbc.queryForObject(cp.countSql(), Long.class, cp.countArgs().toArray());
                total = t == null ? 0 : t;
                long offset = (long) (req.pageNo() - 1) * req.pageSize();
                rawRows = reportJdbc.queryForList(cp.listSql(offset, req.pageSize()),
                        cp.listArgs(offset, req.pageSize()).toArray());
                pageNo = req.pageNo();
                pageSize = req.pageSize();
            } else {
                SqlPager.Query q = toPagerQuery(plan);
                SqlPager.PageData pd = SqlPager.page(reportJdbc, req, q, plan.sortWhitelist, plan.defaultOrder);
                rawRows = pd.rows();
                total = pd.total();
                pageNo = pd.pageNo();
                pageSize = pd.pageSize();
            }
            if (plan.rowEnricher != null) plan.rowEnricher.enrich(rawRows);
            List<Map<String, Object>> rows = ReportCamel.camelize(rawRows);
            // 明细页不再内嵌合计：合计由前端调 /summary 并行懒加载，全区间重合计不拖累翻页
            fieldMasker.mask(rows, plan.maskOverrides);
            return new PageResult<>(rows, pageNo, pageSize, total, zeroSummary(def));
        }, pr -> (int) Math.min(pr.total(), Integer.MAX_VALUE));
    }

    /**
     * 汇总类报表（#3/#4 等）：服务端不分页，一次返回全部叶子分组行（硬上限 10 万），
     * 组小计由前端按分组维度对叶子行汇总，避免深分页下小计口径错误。
     */
    private PageResult<Map<String, Object>> summaryPage(ReportDefinition def, Map<String, Object> body,
                                                        ReportQueryRequest req) {
        return guard.run("rpt." + def.code(), body, true, () -> {
            Plan plan = def.build(req);
            if (plan.denyAll) {
                return new PageResult<>(List.of(), 1, SUMMARY_ROW_CAP, 0, zeroSummary(def));
            }
            String order = req.sortField() != null && plan.sortWhitelist.containsKey(req.sortField())
                    ? " ORDER BY " + plan.sortWhitelist.get(req.sortField()) + (req.isAsc() ? " ASC" : " DESC")
                    : " " + plan.defaultOrder;
            String sql = plan.detailSelect + " " + plan.fromWhere + plan.groupBy + order + " LIMIT ?";
            List<Map<String, Object>> raw = reportJdbc.queryForList(sql,
                    appendArg(plan.args, SUMMARY_ROW_CAP + 1));
            if (raw.size() > SUMMARY_ROW_CAP) {
                throw new IllegalArgumentException("汇总结果超过 10 万行，请增加分组层级或缩小日期范围");
            }
            List<Map<String, Object>> rows = ReportCamel.camelize(raw);
            Map<String, Object> summary = fetchSummary(plan);
            fieldMasker.mask(rows, plan.maskOverrides);
            fieldMasker.mask(summary, plan.maskOverrides);
            return new PageResult<>(rows, 1, SUMMARY_ROW_CAP, rows.size(), summary);
        }, pr -> (int) Math.min(pr.total(), Integer.MAX_VALUE));
    }

    private static Object[] appendArg(List<Object> args, Object extra) {
        List<Object> all = new java.util.ArrayList<>(args);
        all.add(extra);
        return all.toArray();
    }

    /** 只要总计（合计行独立接口）。 */
    public Map<String, Object> summary(ReportDefinition def, Map<String, Object> body) {
        ReportQueryRequest req = ReportQueryRequest.from(body, def.naturalMonthDefault());
        validateSpan(def, req);
        return guard.run("rpt." + def.code() + ".summary", body, def.dws(), () -> {
            Plan plan = def.build(req);
            if (plan.denyAll) return zeroSummary(def);
            Map<String, Object> summary = fetchSummary(plan);
            fieldMasker.mask(summary, plan.maskOverrides);
            return summary;
        });
    }

    /**
     * 异步导出全量流式读取：按主键集分页批取（OFFSET 由引擎内部递增，非用户深翻页），
     * 脱敏走导出口径（maskExport，受全局「导出敏感数据」开关控制）。
     *
     * @return 实际导出的行数
     */
    public int streamForExport(ReportDefinition def, Map<String, Object> body,
                               Consumer<List<Map<String, Object>>> batchConsumer) {
        ReportQueryRequest req = ReportQueryRequest.from(body, def.naturalMonthDefault());
        validateSpan(def, req);
        // 导出在后台线程执行，不走交互式信号量/短缓存，但仍受 30s 单语句超时与只读池隔离保护
        Plan plan = def.build(req);
        if (plan.denyAll) return 0;
        int total = 0;
        while (total < EXPORT_CAP) {
            List<Map<String, Object>> rawRows;
            if (plan.customPageSql != null) {
                CustomPageSql cp = plan.customPageSql;
                rawRows = reportJdbc.queryForList(cp.listSql(total, EXPORT_BATCH),
                        cp.listArgs(total, EXPORT_BATCH).toArray());
            } else {
                String sql = plan.detailSelect + " " + plan.fromWhere + plan.groupBy
                        + " " + plan.defaultOrder + " LIMIT ? OFFSET ?";
                List<Object> args = new java.util.ArrayList<>(plan.args);
                args.add(EXPORT_BATCH);
                args.add(total);
                rawRows = reportJdbc.queryForList(sql, args.toArray());
            }
            if (rawRows.isEmpty()) break;
            if (plan.rowEnricher != null) plan.rowEnricher.enrich(rawRows);
            List<Map<String, Object>> rows = ReportCamel.camelize(rawRows);
            fieldMasker.maskExport(rows, plan.maskOverrides);
            batchConsumer.accept(rows);
            total += rows.size();
            if (rows.size() < EXPORT_BATCH) break;
        }
        return total;
    }

    /** 导出用总计（与页面同口径，供 Excel 合计行）。 */
    public Map<String, Object> summaryForExport(ReportDefinition def, Map<String, Object> body) {
        ReportQueryRequest req = ReportQueryRequest.from(body, def.naturalMonthDefault());
        validateSpan(def, req);
        Plan plan = def.build(req);
        if (plan.denyAll) return zeroSummary(def);
        Map<String, Object> summary = fetchSummary(plan);
        fieldMasker.maskExport(summary, plan.maskOverrides);
        return summary;
    }

    // ============================ 内部 ============================

    private void validateSpan(ReportDefinition def, ReportQueryRequest req) {
        if (def.summaryReport()) {
            req.range().checkSummarySpan();
        } else {
            req.range().checkDetailSpan();
        }
    }

    private static SqlPager.Query toPagerQuery(Plan plan) {
        SqlPager.Query q = new SqlPager.Query();
        q.sql().append(plan.detailSelect).append(' ').append(plan.fromWhere);
        q.args().addAll(plan.args);
        q.groupBy(plan.groupBy);
        return q;
    }

    private Map<String, Object> fetchSummary(Plan plan) {
        String sql;
        if (plan.grandSql != null) {
            Map<String, Object> grow = reportJdbc.queryForMap(plan.grandSql, plan.grandArgs.toArray());
            return ReportCamel.camelize(grow);
        }
        // summaryAliases 对叶子行直接 SUM；无动态 GROUP BY 的键集透视报表（如 #8 进销存）同样适用
        if (!plan.summaryAliases.isEmpty()) {
            StringBuilder sb = new StringBuilder("SELECT ");
            for (int i = 0; i < plan.summaryAliases.size(); i++) {
                String a = plan.summaryAliases.get(i);
                if (i > 0) sb.append(", ");
                sb.append("COALESCE(SUM(_t.").append(a).append("),0) AS ").append(a);
            }
            sb.append(" FROM (").append(plan.detailSelect).append(' ').append(plan.fromWhere)
              .append(plan.groupBy).append(") _t");
            sql = sb.toString();
        } else if (plan.grandSummarySelect != null) {
            sql = plan.grandSummarySelect + " " + plan.fromWhere;
        } else {
            return Map.of();
        }
        Map<String, Object> row = reportJdbc.queryForMap(sql, plan.args.toArray());
        return ReportCamel.camelize(row);
    }

    private Map<String, Object> zeroSummary(ReportDefinition def) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (ReportColumnDef c : def.columns()) {
            if (c.measure()) m.put(c.field(), 0);
        }
        return m;
    }
}
