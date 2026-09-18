package com.erp.system;

import com.alibaba.excel.EasyExcel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;

/**
 * 导入任务留痕与失败文件落盘（商品/客户等导入共用）：
 * 失败行生成真实 xlsx 到 data/import-failures/{taskNo}.xlsx（由【导入列表】行内下载），
 * 任务写入 sys_import_task_runtime。失败文件落盘异常不阻断导入结果。
 */
@Component
public class ImportTaskRecorder {

    public static final File FAILURE_DIR = new File("data/import-failures");

    private final JdbcTemplate jdbcTemplate;

    public ImportTaskRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param moduleCode 任务模块编码（sys_import_task_runtime.module_code，如 goods/customer）
     * @param fields     字段定义 {中文表头, 驼峰键}，决定失败文件列序
     * @param rawRows    原始上传行（按 Excel 行号 rowNo-2 回取填报原值）
     * @return 任务号 taskNo
     */
    public String record(String moduleCode, String taskName, String fileName, int success,
                         List<Map<String, Object>> failures, String[][] fields,
                         List<Map<String, Object>> rawRows) {
        String taskNo = "IMP" + System.currentTimeMillis()
                + String.format("%03d", new Random().nextInt(1000));
        String failureFile = null;
        if (!failures.isEmpty()) {
            try {
                if (!FAILURE_DIR.exists() && !FAILURE_DIR.mkdirs()) {
                    throw new IllegalStateException("无法创建失败文件目录：" + FAILURE_DIR.getAbsolutePath());
                }
                File out = new File(FAILURE_DIR, taskNo + ".xlsx");
                List<List<String>> head = new ArrayList<>();
                head.add(Collections.singletonList("Excel行号"));
                for (String[] f : fields) head.add(Collections.singletonList(f[0]));
                head.add(Collections.singletonList("失败原因"));
                List<List<Object>> data = new ArrayList<>();
                for (Map<String, Object> fail : failures) {
                    int rowNo = ((Number) fail.get("rowNo")).intValue();
                    Map<String, Object> raw = rawRows.get(rowNo - 2);
                    List<Object> line = new ArrayList<>();
                    line.add(rowNo);
                    for (String[] f : fields) line.add(raw == null ? "" : str(raw.get(f[1])));
                    line.add(fail.get("reason"));
                    data.add(line);
                }
                EasyExcel.write(out).sheet("失败明细").head(head).doWrite(data);
                failureFile = "import-failures/" + taskNo + ".xlsx";
            } catch (Exception e) {
                // 失败文件落盘失败不阻断导入结果，任务仍写库（仅无文件可下）
                failureFile = null;
            }
        }
        jdbcTemplate.update(
                "INSERT INTO sys_import_task_runtime "
                        + "(task_id, task_no, module_code, task_name, file_name, success_rows, failed_rows, status, result_text, failure_file, created_at, finished_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'FINISHED', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "IMP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                taskNo, moduleCode, taskName, fileName, success, failures.size(),
                failures.isEmpty() ? "导入完成，成功 " + success + " 条"
                        : "成功 " + success + " 条，失败 " + failures.size() + " 条（可下载失败文件）",
                failureFile);
        return taskNo;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
