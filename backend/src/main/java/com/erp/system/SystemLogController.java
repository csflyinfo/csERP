package com.erp.system;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.security.RequirePerm;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端「操作日志 / 登录日志」查询与管理（PRD-31）。路径在 /system/** 下，仅 ADMIN 可访问。
 *
 * <p>与旧实现的区别：日志量可能很大，列表用 <b>SQL 级 WHERE 过滤 + COUNT/LIMIT 分页</b>，
 * 不再走 PageResult.of 的内存 substring；改前改后 CLOB 只在详情接口返回，列表不带。
 * 普通业务用户在单据内看操作记录走 {@link OperationLogController}（/operation-log/**）。
 */
@RestController
@RequestMapping("/system")
public class SystemLogController {

    private static final int EXPORT_LIMIT = 5000;

    private final JdbcTemplate jdbc;
    private final OperationLogService opLog;
    private final LogCleanupTask cleanupTask;
    private final ObjectMapper objectMapper;

    public SystemLogController(JdbcTemplate jdbc, OperationLogService opLog,
                               LogCleanupTask cleanupTask, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.opLog = opLog;
        this.cleanupTask = cleanupTask;
        this.objectMapper = objectMapper;
    }

    // ==================== 操作日志 ====================

    @PostMapping("/operation-log/page")
    @RequirePerm("system.log.view")
    public ApiResponse<PageResult<Map<String, Object>>> operationLogPage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendOpFilters(where, args, f);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log_runtime" + where, Long.class, args.toArray());

        int pageNo = request.safePageNo();
        int pageSize = request.safePageSize();
        String sql = "SELECT log_id, operate_at, operator_name, operator_account, module_code, module_name, " +
                "action, action_name, biz_type, biz_id, biz_no, result, request_ip, request_method, " +
                "cost_time_ms, sensitive, fail_reason, operation_content " +
                "FROM sys_operation_log_runtime" + where +
                " ORDER BY operate_at DESC, log_id DESC LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (pageNo - 1) * pageSize);
        List<Map<String, Object>> rows = jdbc.query(sql, this::mapRow, pageArgs.toArray());

        return ApiResponse.ok(new PageResult<>(rows, pageNo, pageSize, total == null ? 0 : total, Map.of()));
    }

    /** 详情：含改前改后 JSON（解析成对象返回）。/system 仅 ADMIN，敏感值不脱敏。 */
    @GetMapping("/operation-log/detail/{logId}")
    @RequirePerm("system.log.view")
    public ApiResponse<Map<String, Object>> operationLogDetail(@PathVariable String logId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM sys_operation_log_runtime WHERE log_id = ?", this::mapRowFull, logId);
        if (rows.isEmpty()) {
            return ApiResponse.ok(Map.of());
        }
        return ApiResponse.ok(rows.get(0));
    }

    /** 按筛选条件导出（最多 {@value #EXPORT_LIMIT} 行），并写一条 EXPORT 日志。 */
    @PostMapping("/operation-log/export")
    @RequirePerm("system.log.export")
    public ApiResponse<List<Map<String, Object>>> operationLogExport(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendOpFilters(where, args, f);
        String sql = "SELECT log_id, operate_at, operator_name, operator_account, module_name, action_name, " +
                "biz_type, biz_no, result, request_ip, cost_time_ms, sensitive, operation_content, fail_reason " +
                "FROM sys_operation_log_runtime" + where +
                " ORDER BY operate_at DESC LIMIT " + EXPORT_LIMIT;
        List<Map<String, Object>> rows = jdbc.query(sql, this::mapRow, args.toArray());
        opLog.log(OperationModule.OP_LOG, OperationAction.EXPORT, null, "导出操作日志 " + rows.size() + " 条");
        return ApiResponse.ok(rows);
    }

    /** 手动触发保留期清理。 */
    @PostMapping("/operation-log/manual-cleanup")
    @RequirePerm(value = "system.log.biz_manual_cleanup", name = "手动清理日志", type = "ACTION")
    public ApiResponse<Map<String, Object>> manualCleanup() {
        int[] r = cleanupTask.cleanupNow();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("opDeleted", r[0]);
        data.put("loginDeleted", r[1]);
        return ApiResponse.ok(data);
    }

    // ==================== 登录日志 ====================

    @PostMapping("/login-log/page")
    @RequirePerm("system.login_log.view")
    public ApiResponse<PageResult<Map<String, Object>>> loginLogPage(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendLoginFilters(where, args, f);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_login_log" + where, Long.class, args.toArray());

        int pageNo = request.safePageNo();
        int pageSize = request.safePageSize();
        String sql = "SELECT login_log_id, user_id, account, user_name, login_at, login_result, " +
                "fail_reason, ip, user_agent, app_type, logout_at " +
                "FROM sys_login_log" + where +
                " ORDER BY login_at DESC, login_log_id DESC LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (pageNo - 1) * pageSize);
        List<Map<String, Object>> rows = jdbc.query(sql, this::mapRow, pageArgs.toArray());

        return ApiResponse.ok(new PageResult<>(rows, pageNo, pageSize, total == null ? 0 : total, Map.of()));
    }

    @PostMapping("/login-log/export")
    @RequirePerm("system.login_log.export")
    public ApiResponse<List<Map<String, Object>>> loginLogExport(@RequestBody PageRequest request) {
        Map<String, Object> f = request.filters() == null ? Map.of() : request.filters();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendLoginFilters(where, args, f);
        String sql = "SELECT account, user_name, login_at, login_result, fail_reason, ip, app_type, logout_at " +
                "FROM sys_login_log" + where + " ORDER BY login_at DESC LIMIT " + EXPORT_LIMIT;
        List<Map<String, Object>> rows = jdbc.query(sql, this::mapRow, args.toArray());
        opLog.log(OperationModule.OP_LOG, OperationAction.EXPORT, null, "导出登录日志 " + rows.size() + " 条");
        return ApiResponse.ok(rows);
    }

    // ==================== WHERE 拼装 ====================

    private void appendOpFilters(StringBuilder where, List<Object> args, Map<String, Object> f) {
        like(where, args, "module_code", f.get("moduleCode"));
        eq(where, args, "action", f.get("action"));
        eq(where, args, "biz_type", f.get("bizType"));
        eq(where, args, "result", f.get("result"));
        eq(where, args, "sensitive", f.get("sensitive"));
        like(where, args, "operator_account", f.get("operatorAccount"));
        like(where, args, "operator_name", f.get("operatorName"));
        like(where, args, "biz_no", f.get("bizNo"));
        dateRange(where, args, "operate_at", f.get("dateFrom"), f.get("dateTo"));
        String kw = str(f.get("keyword"));
        if (kw != null) {
            where.append(" AND (COALESCE(operation_content,'') || ' ' || COALESCE(detail,'') || ' ' || ")
                 .append("COALESCE(biz_no,'') || ' ' || COALESCE(module_code,'') || ' ' || ")
                 .append("COALESCE(action,'') || ' ' || COALESCE(operator_name,'') || ' ' || COALESCE(operator_account,'') LIKE ?)");
            args.add("%" + kw + "%");
        }
    }

    private void appendLoginFilters(StringBuilder where, List<Object> args, Map<String, Object> f) {
        like(where, args, "account", f.get("account"));
        like(where, args, "user_name", f.get("userName"));
        eq(where, args, "login_result", f.get("loginResult"));
        eq(where, args, "app_type", f.get("appType"));
        dateRange(where, args, "login_at", f.get("dateFrom"), f.get("dateTo"));
        String kw = str(f.get("keyword"));
        if (kw != null) {
            where.append(" AND (COALESCE(account,'') || ' ' || COALESCE(user_name,'') || ' ' || COALESCE(ip,'') LIKE ?)");
            args.add("%" + kw + "%");
        }
    }

    private void eq(StringBuilder where, List<Object> args, String col, Object val) {
        String v = str(val);
        if (v != null) {
            where.append(" AND ").append(col).append(" = ?");
            args.add(v);
        }
    }

    private void like(StringBuilder where, List<Object> args, String col, Object val) {
        String v = str(val);
        if (v != null) {
            where.append(" AND ").append(col).append(" LIKE ?");
            args.add("%" + v + "%");
        }
    }

    private void dateRange(StringBuilder where, List<Object> args, String col, Object from, Object to) {
        String dFrom = str(from);
        if (dFrom != null) {
            where.append(" AND ").append(col).append(" >= ?");
            args.add(dFrom.length() == 10 ? dFrom + " 00:00:00" : dFrom);
        }
        String dTo = str(to);
        if (dTo != null) {
            where.append(" AND ").append(col).append(" <= ?");
            args.add(dTo.length() == 10 ? dTo + " 23:59:59" : dTo);
        }
    }

    private String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return (s.isEmpty() || "null".equalsIgnoreCase(s) || "全部".equals(s)) ? null : s;
    }

    // ==================== 行映射（下划线列名 → 驼峰 key） ====================

    /** 列表行映射：按 ResultSet 元数据逐列转驼峰。 */
    private Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        return toCamel(rs, false);
    }

    /** 详情行映射：含 CLOB，并把 before/after JSON 解析为对象。 */
    private Map<String, Object> mapRowFull(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = toCamel(rs, true);
        row.put("beforeValue", parseJson((String) row.get("beforeValue")));
        row.put("afterValue", parseJson((String) row.get("afterValue")));
        return row;
    }

    private Map<String, Object> toCamel(ResultSet rs, boolean includeClob) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        for (int i = 1; i <= cols; i++) {
            String col = meta.getColumnLabel(i).toLowerCase();
            boolean isClobCol = "before_value".equals(col) || "after_value".equals(col);
            if (!includeClob && isClobCol) {
                continue;
            }
            // CLOB 列必须用 getString：getObject 返回 org.h2.jdbc.JdbcClob，强转 String 会 ClassCastException（500）
            Object val = isClobCol ? rs.getString(i) : rs.getObject(i);
            out.put(snakeToCamel(col), val);
        }
        return out;
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String snakeToCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }
}
