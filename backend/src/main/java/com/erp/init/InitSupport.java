package com.erp.init;

import com.alibaba.excel.EasyExcel;
import com.erp.common.security.CurrentUser;
import com.erp.finance.dayclose.BizDayCloseGuard;
import com.erp.system.OperationLogService;
import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 期初初始化三模块（PRD-34）公共支撑：
 * 建账守卫、过批号、sys_param_runtime 建账标志、biz_init_post 读写、
 * 导入批次号、导入任务留痕与失败 xlsx 落盘（范式同 GoodsImportController）。
 */
@Service
public class InitSupport {

    /** 总账启用标志（与 GlConst.PARAM_INITIALIZED 同值，避免跨包依赖）。 */
    public static final String GL_PARAM_INITIALIZED = "fin.gl.initialized";

    private static final File FAILURE_DIR = new File("data/import-failures");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcTemplate jdbc;
    private final SysParamService sysParam;
    private final BizDayCloseGuard dayClose;
    private final OperationLogService opLog;

    public InitSupport(JdbcTemplate jdbc, SysParamService sysParam,
                       BizDayCloseGuard dayClose, OperationLogService opLog) {
        this.jdbc = jdbc;
        this.sysParam = sysParam;
        this.dayClose = dayClose;
        this.opLog = opLog;
    }

    // ==================== 守卫 ====================

    /** 总账是否已启用（启用后业务期初锁定）。 */
    public boolean isGlInitialized() {
        return sysParam.getBool(GL_PARAM_INITIALIZED, false);
    }

    /** 本模块是否已建账。 */
    public boolean isPosted(String postedParamKey) {
        return sysParam.getBool(postedParamKey, false);
    }

    /** 已有业务日结记录（封单日非空）。 */
    public boolean hasDayClose() {
        return dayClose.lastClosedDate() != null;
    }

    /**
     * 建账前可编辑守卫：未建账 + 无任何业务日结 + 总账未启用。
     *
     * @param moduleLabel 模块中文名（报错文案用）
     */
    public void assertEditable(String postedParamKey, String moduleLabel) {
        if (isPosted(postedParamKey)) {
            throw new IllegalArgumentException(moduleLabel + "已期初建账，数据已锁定；如需调整请先反建账");
        }
        LocalDate lastClosed = dayClose.lastClosedDate();
        if (lastClosed != null) {
            throw new IllegalArgumentException("已存在业务日结记录（封单日 " + lastClosed + "），"
                    + moduleLabel + "期初数据已锁定，不能再修改或导入");
        }
        if (isGlInitialized()) {
            throw new IllegalArgumentException("总账已启用，业务期初已锁定，不能再修改"
                    + moduleLabel + "期初；如需调整请通过总账凭证处理");
        }
    }

    /**
     * 反建账公共守卫：已建账 + 无日结 + 总账未启用。
     * 各模块的业务级附加条件（未被核销/无后续出入库）由模块 Service 自行校验。
     */
    public void assertReversable(String postedParamKey, String moduleLabel) {
        if (!isPosted(postedParamKey)) {
            throw new IllegalArgumentException(moduleLabel + "尚未期初建账，无需反建账");
        }
        LocalDate lastClosed = dayClose.lastClosedDate();
        if (lastClosed != null) {
            throw new IllegalArgumentException("已存在业务日结记录（封单日 " + lastClosed + "），"
                    + "期初建账不能反建账；请先反日结");
        }
        if (isGlInitialized()) {
            throw new IllegalArgumentException("总账已启用，期初建账不能反建账；如需调整请通过总账凭证处理");
        }
    }

    // ==================== 过批号 / 过账主表 ====================

    /** 生成过批号：QC + yyyyMMdd + 4 位流水（biz_init_post 当日 MAX+1）。 */
    public String nextPostNo() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "QC" + date;
        String max = jdbc.queryForObject(
                "SELECT MAX(post_no) FROM biz_init_post WHERE post_no LIKE ?",
                String.class, prefix + "%");
        int next = 1;
        if (max != null && max.length() >= 4) {
            try {
                next = Integer.parseInt(max.substring(max.length() - 4)) + 1;
            } catch (NumberFormatException ignored) {
            }
        }
        return String.format("%s%04d", prefix, next);
    }

    /** 写过账主表，返回 post_id。 */
    public String insertPost(String postNo, String initType, int lineCount,
                             BigDecimal totalQty, BigDecimal totalAmount, String remark) {
        String postId = TmsUtil.uuid("QP");
        CurrentUser.Principal u = CurrentUser.get();
        jdbc.update("INSERT INTO biz_init_post(post_id, post_no, init_type, line_count, total_qty, total_amount, " +
                        "status, remark, posted_by, posted_by_name) VALUES (?,?,?,?,?,?,'POSTED',?,?,?)",
                postId, postNo, initType, lineCount,
                totalQty == null ? BigDecimal.ZERO : totalQty,
                totalAmount == null ? BigDecimal.ZERO : totalAmount,
                remark,
                u == null ? null : u.userId(),
                currentUserName());
        return postId;
    }

    /** 过账主表置 REVERSED 并留痕反建账人/原因。 */
    public void markPostReversed(String postNo, String reason) {
        CurrentUser.Principal u = CurrentUser.get();
        jdbc.update("UPDATE biz_init_post SET status='REVERSED', reversed_by=?, reversed_by_name=?, " +
                        "reversed_at=CURRENT_TIMESTAMP, reverse_reason=? WHERE post_no=?",
                u == null ? null : u.userId(), currentUserName(), reason, postNo);
    }

    /** 查指定类型当前生效的过账（POSTED）主表行，无则 null。 */
    public Map<String, Object> activePost(String initType) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT post_id, post_no, init_type, line_count, total_qty, total_amount, status, remark, " +
                        "posted_by, posted_by_name, posted_at, reverse_reason " +
                        "FROM biz_init_post WHERE init_type=? AND status='POSTED' " +
                        "ORDER BY posted_at DESC LIMIT 1", initType);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 建账标志参数 ====================

    /** 置建账标志（懒创建 sys_param_runtime）。 */
    public void setPostedFlag(String flagKey, String postNoKey, String postNo, String name) {
        setParam(flagKey, "Y", name + "已建账标志");
        if (postNoKey != null) {
            setParam(postNoKey, postNo, name + "最近过批号");
        }
    }

    /** 清建账标志（反建账，值置 N 保留参数行）。 */
    public void clearPostedFlag(String flagKey, String postNoKey) {
        setParam(flagKey, "N", null);
        if (postNoKey != null) {
            setParam(postNoKey, "", null);
        }
    }

    private void setParam(String key, String value, String paramName) {
        jdbc.update("INSERT INTO sys_param_runtime(param_id, param_key, param_name, param_value, default_value, param_group) " +
                        "SELECT ?,?,?,?,?, '期初初始化' WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key = ?)",
                TmsUtil.uuid("PM"), key, paramName == null ? key : paramName, value, value, key);
        jdbc.update("UPDATE sys_param_runtime SET param_value=? WHERE param_key=?", value, key);
        sysParam.evict();
    }

    // ==================== 导入批次号 / 操作人 ====================

    /** 每次导入一个批次号（可按批清空），如 AR20260915143001082。 */
    public String nextBatchNo(String typePrefix) {
        return typePrefix + LocalDateTime.now().format(TS_FMT)
                + String.format("%03d", new Random().nextInt(1000));
    }

    /** 生成导入任务号：IMP + 毫秒时间戳 + 3 位随机数（与 sys_import_task_runtime / 失败文件同名）。 */
    public String nextTaskNo() {
        return "IMP" + System.currentTimeMillis()
                + String.format("%03d", new Random().nextInt(1000));
    }

    public String currentUserId() {
        CurrentUser.Principal u = CurrentUser.get();
        return u == null ? null : u.userId();
    }

    public String currentUserName() {
        CurrentUser.Principal u = CurrentUser.get();
        if (u != null) {
            if (u.displayName() != null && !u.displayName().isBlank()) return u.displayName();
            if (u.username() != null && !u.username().isBlank()) return u.username();
        }
        return TmsUtil.currentUser();
    }

    // ==================== 导入任务留痕 + 失败文件 ====================

    /**
     * 写 sys_import_task_runtime；失败行生成 xlsx 落盘 data/import-failures（由「导入列表」下载）。
     * 范式与 GoodsImportController.recordTask 一致。
     *
     * @param fields {中文表头, 驼峰键}，顺序即失败文件列序
     * @return taskNo
     */
    public String recordImportTask(String moduleCode, String taskName, String fileName, int success,
                                   List<Map<String, Object>> failures, String[][] fields,
                                   List<Map<String, Object>> rawRows) {
        return recordImportTask(nextTaskNo(), moduleCode, taskName, fileName, success, failures, fields, rawRows);
    }

    /**
     * 同 {@link #recordImportTask(String, String, String, int, List, String[][], List)}，
     * 但使用调用方预生成的任务号——保证暂存行 task_no、sys_import_task_runtime、失败文件名三者一致。
     */
    public String recordImportTask(String taskNo, String moduleCode, String taskName, String fileName, int success,
                                   List<Map<String, Object>> failures, String[][] fields,
                                   List<Map<String, Object>> rawRows) {
        String failureFile = null;
        if (!failures.isEmpty()) {
            try {
                if (!FAILURE_DIR.exists() && !FAILURE_DIR.mkdirs()) {
                    throw new IllegalStateException("无法创建失败文件目录：" + FAILURE_DIR.getAbsolutePath());
                }
                File out = new File(FAILURE_DIR, taskNo + ".xlsx");
                List<List<String>> head = new ArrayList<>();
                head.add(Collections.singletonList("Excel行号"));
                for (String[] f : fields) {
                    head.add(Collections.singletonList(f[0]));
                }
                head.add(Collections.singletonList("失败原因"));
                List<List<Object>> data = new ArrayList<>();
                for (Map<String, Object> fail : failures) {
                    int rowNo = ((Number) fail.get("rowNo")).intValue();
                    Map<String, Object> raw = rawRows.get(rowNo - 2);
                    List<Object> line = new ArrayList<>();
                    line.add(rowNo);
                    for (String[] f : fields) {
                        line.add(raw == null ? "" : TmsUtil.str(raw.get(f[1])));
                    }
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
        jdbc.update(
                "INSERT INTO sys_import_task_runtime "
                        + "(task_id, task_no, module_code, task_name, file_name, success_rows, failed_rows, " +
                        "status, result_text, failure_file, created_at, finished_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'FINISHED', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "IMP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                taskNo, moduleCode, taskName, fileName, success, failures.size(),
                failures.isEmpty() ? "导入完成，成功 " + success + " 条"
                        : "成功 " + success + " 条，失败 " + failures.size() + " 条（可下载失败文件）",
                failureFile);
        return taskNo;
    }

    /** 组装一行失败原因（行号取 Excel 物理行号 = 序号 + 2）。 */
    public Map<String, Object> failure(int rowNo, String reason) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("rowNo", rowNo);
        f.put("reason", reason);
        return f;
    }

    public OperationLogService opLog() {
        return opLog;
    }
}
