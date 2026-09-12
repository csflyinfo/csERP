package com.erp.report.export;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.handler.CellWriteHandler;
import com.alibaba.excel.write.handler.context.CellWriteHandlerContext;
import com.erp.common.security.CurrentUser;
import com.erp.report.common.ReportQueryRequest;
import com.erp.report.meta.ReportColumnDef;
import com.erp.report.meta.ReportDefinition;
import com.erp.report.meta.ReportQueryEngine;
import com.erp.report.meta.ReportRegistry;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 报表异步导出（方案 §4.7）：同进程独立 worker（并发 2、排队 50）起步，未来可整体搬独立服务。
 *
 * <ul>
 *   <li>任务状态机 CREATED → RUNNING → FINISHED / FAILED，落 sys_export_task_runtime；</li>
 *   <li>取数/脱敏/口径全部走 {@link ReportQueryEngine#streamForExport}，与页面同 Plan；</li>
 *   <li>汇总类报表（#3/#4）导出时在 Java 侧按叶子分组行插组小计（加粗），保留层级；</li>
 *   <li>文件保留 7 天，到期由定时任务连文件带记录清理；服务重启把残留 RUNNING 置 FAILED。</li>
 * </ul>
 */
@Service
public class ReportExportService {

    private static final Logger log = LoggerFactory.getLogger(ReportExportService.class);
    private static final int QUEUE_CAPACITY = 50;
    private static final int RETENTION_DAYS = 7;

    private final JdbcTemplate jdbc;
    private final ReportRegistry registry;
    private final ReportQueryEngine engine;
    private final File exportDir;

    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "rpt-export-worker");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    public ReportExportService(JdbcTemplate jdbc, ReportRegistry registry, ReportQueryEngine engine,
                               @Value("${report.export.dir:data/export}") String exportDir) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.engine = engine;
        this.exportDir = new File(exportDir);
        if (!this.exportDir.exists() && !this.exportDir.mkdirs()) {
            log.warn("导出目录创建失败：{}", this.exportDir.getAbsolutePath());
        }
    }

    /** 入队。返回 taskNo；排队满时给用户中文提示。 */
    public String enqueue(String reportCode, Map<String, Object> body, String filterText) {
        ReportDefinition def = registry.require(reportCode);
        String taskNo = "EXP" + System.currentTimeMillis()
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String taskId = "EX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String fileName = def.name() + "_" + java.time.LocalDate.now() + "_" + taskNo.substring(3, 9) + ".xlsx";
        CurrentUser.Principal principal = CurrentUser.get();
        jdbc.update("""
                INSERT INTO sys_export_task_runtime(task_id, task_no, report_name, module_code,
                    filter_text, file_name, status, created_at, created_by)
                VALUES (?, ?, ?, 'report', ?, ?, 'CREATED', CURRENT_TIMESTAMP, ?)
                """,
                taskId, taskNo, def.name(),
                truncate(filterText, 1000), fileName,
                principal == null ? null : principal.userId());
        try {
            pool.execute(() -> run(taskNo, reportCode, body, def, principal));
        } catch (RejectedExecutionException e) {
            jdbc.update("UPDATE sys_export_task_runtime SET status='FAILED', finished_at=CURRENT_TIMESTAMP, "
                    + "error_msg='导出排队已满' WHERE task_no=?", taskNo);
            throw new IllegalArgumentException("导出任务排队已满（最多 50 个），请稍后再试");
        }
        return taskNo;
    }

    /** 后台执行：必须自己持有用户上下文（数据范围/字段脱敏都依赖 CurrentUser）。 */
    private void run(String taskNo, String reportCode, Map<String, Object> body,
                     ReportDefinition def, CurrentUser.Principal principal) {
        CurrentUser.set(principal);
        try {
            jdbc.update("UPDATE sys_export_task_runtime SET status='RUNNING', started_at=CURRENT_TIMESTAMP "
                    + "WHERE task_no=?", taskNo);
            File file = new File(exportDir, taskNo + ".xlsx");

            ReportQueryRequest req = ReportQueryRequest.from(body);
            List<ReportColumnDef> columns = def.columns().stream()
                    .filter(ReportColumnDef::defaultVisible).toList();

            List<List<Object>> allRows = new ArrayList<>();
            Set<Integer> boldRows = new HashSet<>();
            allRows.add(List.of(def.name() + "（导出时间："
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "）"));
            boldRows.add(0);
            String filterText = buildFilterText(body);
            allRows.add(List.of(filterText));
            boldRows.add(1);
            allRows.add(columns.stream().map(c -> (Object) c.title()).toList());
            boldRows.add(2);

            int[] total = {0};
            if (def.summaryReport()) {
                List<Map<String, Object>> leaves = new ArrayList<>();
                engine.streamForExport(def, body, batch -> {
                    leaves.addAll(batch);
                    total[0] += batch.size();
                });
                appendGroupedRows(def, req, columns, leaves, allRows, boldRows);
            } else {
                engine.streamForExport(def, body, batch -> {
                    for (Map<String, Object> r : batch) {
                        allRows.add(toRow(columns, r));
                    }
                    total[0] += batch.size();
                });
            }

            // 合计行（与页面同口径）
            Map<String, Object> summary = engine.summaryForExport(def, body);
            List<Object> totalRow = new ArrayList<>();
            boolean[] filled = {false};
            for (ReportColumnDef c : columns) {
                if (c.measure() && summary.get(c.field()) != null) {
                    totalRow.add(summary.get(c.field()));
                    filled[0] = true;
                } else if (c == columns.get(0)) {
                    totalRow.add("合计");
                } else {
                    totalRow.add(null);
                }
            }
            if (filled[0]) {
                allRows.add(totalRow);
                boldRows.add(allRows.size() - 1);
            }

            EasyExcel.write(file)
                    .registerWriteHandler(new BoldRowHandler(boldRows))
                    .sheet(def.name())
                    .doWrite(allRows);

            jdbc.update("""
                    UPDATE sys_export_task_runtime
                    SET status='FINISHED', finished_at=CURRENT_TIMESTAMP, file_path=?,
                        total_rows=?, expire_at=?
                    WHERE task_no=?
                    """,
                    file.getAbsolutePath(), total[0],
                    java.sql.Timestamp.valueOf(LocalDateTime.now().plusDays(RETENTION_DAYS)), taskNo);
            log.info("报表导出完成 {} {}，{} 行，{}", taskNo, reportCode, total[0], file.getName());
        } catch (Exception e) {
            log.warn("报表导出失败 {} {}：{}", taskNo, reportCode, e.getMessage());
            jdbc.update("""
                    UPDATE sys_export_task_runtime
                    SET status='FAILED', finished_at=CURRENT_TIMESTAMP, error_msg=?
                    WHERE task_no=?
                    """, truncate(e.getMessage() == null ? "导出失败" : e.getMessage(), 900), taskNo);
        } finally {
            CurrentUser.clear();
        }
    }

    /** 汇总类：按分组字段插组小计行（最深层不再小计，叶子行本身即该层合计）。 */
    private void appendGroupedRows(ReportDefinition def, ReportQueryRequest req,
                                   List<ReportColumnDef> columns, List<Map<String, Object>> leaves,
                                   List<List<Object>> allRows, Set<Integer> boldRows) {
        List<String> groups = def.groupFields(req);
        if (groups.isEmpty() || groups.size() == 1) {
            for (Map<String, Object> r : leaves) allRows.add(toRow(columns, r));
            return;
        }
        appendLevel(columns, groups, 0, leaves, allRows, boldRows);
    }

    private void appendLevel(List<ReportColumnDef> columns, List<String> groups, int level,
                             List<Map<String, Object>> rows,
                             List<List<Object>> allRows, Set<Integer> boldRows) {
        String field = groups.get(level);
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object v = r.get(field);
            buckets.computeIfAbsent(v == null ? "" : String.valueOf(v), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Map<String, Object>>> e : buckets.entrySet()) {
            if (level < groups.size() - 1) {
                appendLevel(columns, groups, level + 1, e.getValue(), allRows, boldRows);
                if (groups.size() > 1) {
                    allRows.add(subtotalRow(columns, groups, level, e.getKey(), e.getValue()));
                    boldRows.add(allRows.size() - 1);
                }
            } else {
                for (Map<String, Object> r : e.getValue()) allRows.add(toRow(columns, r));
            }
        }
    }

    private List<Object> subtotalRow(List<ReportColumnDef> columns, List<String> groups, int level,
                                     String key, List<Map<String, Object>> rows) {
        String labelField = groups.get(level);
        List<Object> out = new ArrayList<>();
        for (ReportColumnDef c : columns) {
            if (c.field().equals(labelField)) {
                out.add((key.isEmpty() ? "（空白）" : key) + " 小计");
            } else if (c.measure()) {
                BigDecimal sum = BigDecimal.ZERO;
                boolean any = false;
                for (Map<String, Object> r : rows) {
                    Object v = r.get(c.field());
                    if (v instanceof Number n) {
                        sum = sum.add(new BigDecimal(String.valueOf(n)));
                        any = true;
                    }
                }
                out.add(any ? sum : null);
            } else {
                out.add(null);
            }
        }
        return out;
    }

    private static List<Object> toRow(List<ReportColumnDef> columns, Map<String, Object> r) {
        List<Object> row = new ArrayList<>(columns.size());
        for (ReportColumnDef c : columns) {
            row.add(normalizeCell(r.get(c.field())));
        }
        return row;
    }

    /**
     * EasyExcel 无 java.sql.Date/Temporal 转换器（直接写会 Converter 报错整任务失败）；
     * 统一转字符串（BigDecimal 原生支持，金额保留精度原样写）。
     */
    private static Object normalizeCell(Object v) {
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        if (v instanceof java.util.Date d) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
        }
        if (v instanceof java.time.LocalDate d) {
            return d.toString();
        }
        if (v instanceof java.time.LocalDateTime t) {
            return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return v;
    }

    private static String buildFilterText(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("查询条件：");
        Object dr = body.get("dateRange");
        if (dr instanceof Map<?, ?> m) {
            sb.append("日期 ").append(m.get("startDate")).append(" ~ ").append(m.get("endDate"));
        }
        Object f = body.get("filters");
        if (f instanceof Map<?, ?> m && !m.isEmpty()) {
            sb.append("；").append(m);
        }
        Object g = body.get("groupBy");
        if (g instanceof List<?> l && !l.isEmpty()) {
            sb.append("；分组 ").append(String.join("/", l.stream().map(String::valueOf).toList()));
        }
        return truncate(sb.toString(), 500);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 启动恢复：重启前残留的执行中任务不可能再完成，置失败让用户重新导出。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        int n = jdbc.update("""
                UPDATE sys_export_task_runtime
                SET status='FAILED', finished_at=CURRENT_TIMESTAMP, error_msg='服务重启，任务中断，请重新导出'
                WHERE status IN ('CREATED','RUNNING')
                """);
        if (n > 0) log.info("重启恢复：{} 个残留导出任务置为失败", n);
    }

    /** 7 天保留期清理（定时任务每日调用）：先删文件再删记录。 */
    public int purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        List<Map<String, Object>> expired = jdbc.queryForList("""
                SELECT task_no, file_path FROM sys_export_task_runtime
                WHERE (expire_at IS NOT NULL AND expire_at < ?)
                   OR (expire_at IS NULL AND status IN ('FINISHED','FAILED') AND created_at < ?)
                """, cutoff, cutoff);
        for (Map<String, Object> t : expired) {
            String path = String.valueOf(t.get("FILE_PATH"));
            if (path != null && !"null".equals(path)) {
                try {
                    Files.deleteIfExists(new File(path).toPath());
                } catch (Exception e) {
                    log.debug("过期导出文件删除失败 {}：{}", path, e.getMessage());
                }
            }
        }
        return jdbc.update("""
                DELETE FROM sys_export_task_runtime
                WHERE (expire_at IS NOT NULL AND expire_at < ?)
                   OR (expire_at IS NULL AND status IN ('FINISHED','FAILED') AND created_at < ?)
                """, cutoff, cutoff);
    }

    /** 取已完成任务文件（下载鉴权由 Controller 负责）。 */
    public File finishedFile(String taskNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT file_path, status FROM sys_export_task_runtime WHERE task_no=?
                """, taskNo);
        if (rows.isEmpty()) throw new IllegalArgumentException("导出任务不存在");
        Map<String, Object> t = rows.get(0);
        String status = String.valueOf(t.get("STATUS"));
        if (!"FINISHED".equals(status)) {
            throw new IllegalArgumentException("导出任务" + ("FAILED".equals(status) ? "失败，请重新导出" : "尚未完成"));
        }
        String path = String.valueOf(t.get("FILE_PATH"));
        File file = new File(path);
        if (!file.exists()) throw new IllegalArgumentException("导出文件已过期清理，请重新导出");
        return file;
    }

    /** 导出中心分页（真实状态：处理中/已完成/失败 + 行数/错误）。 */
    public List<Map<String, Object>> listTasks(String taskNoLike, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT task_no AS task_no, report_name AS report_name, module_code AS module_code,
                       status AS raw_status,
                       CASE status WHEN 'FINISHED' THEN '已完成'
                                   WHEN 'FAILED' THEN '失败'
                                   ELSE '处理中' END AS status_text,
                       file_name AS file_name, filter_text AS remark, total_rows AS total_rows,
                       error_msg AS error_msg, created_at AS created_at, finished_at AS finished_at,
                       created_by AS created_by
                FROM sys_export_task_runtime
                WHERE module_code='report'
                  AND (file_path IS NOT NULL OR error_msg IS NOT NULL
                       OR status IN ('CREATED','RUNNING'))
                """);
        List<Object> args = new ArrayList<>();
        if (taskNoLike != null) {
            sql.append(" AND (task_no LIKE ? OR report_name LIKE ?) ");
            args.add("%" + taskNoLike + "%");
            args.add("%" + taskNoLike + "%");
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        return com.erp.report.common.ReportCamel.camelize(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    /** 指定行加粗（标题/条件/表头/小计/合计）。 */
    private static class BoldRowHandler implements CellWriteHandler {
        private final Set<Integer> boldRows;
        private CellStyle style;

        BoldRowHandler(Set<Integer> boldRows) {
            this.boldRows = boldRows;
        }

        @Override
        public void afterCellDispose(CellWriteHandlerContext context) {
            Integer rowIndex = context.getRowIndex();
            if (rowIndex == null || !boldRows.contains(rowIndex)) return;
            if (style == null) {
                var wb = context.getWriteWorkbookHolder().getWorkbook();
                style = wb.createCellStyle();
                Font font = wb.createFont();
                font.setBold(true);
                style.setFont(font);
            }
            context.getCell().setCellStyle(style);
        }
    }
}
