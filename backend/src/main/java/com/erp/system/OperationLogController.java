package com.erp.system;

import com.erp.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * 业务侧操作日志接口（PRD-31）：放在 /operation-log/**（登录即可访问），<b>不</b>在 /system/** 下，
 * 这样普通业务员也能在自己的业务单据里看操作记录时间线；管理员的集中查询页在 {@link SystemLogController}。
 *
 * <ul>
 *   <li>{@code /bill-timeline}：按单据聚合并按时间正序返回该单据全部操作（含改前改后 diff）。
 *       非管理员看不到成本/采购价等敏感字段的改前改后值（脱敏 ***）。</li>
 *   <li>{@code /print}、{@code /export}：前端打印/导出是纯客户端行为，后端无感知，
 *       由前端在动作发生时 fire-and-forget 打点，失败静默不阻塞用户操作。</li>
 * </ul>
 */
@RestController
@RequestMapping("/operation-log")
public class OperationLogController {

    private final JdbcTemplate jdbc;
    private final OperationLogService opLog;
    private final ObjectMapper objectMapper;

    public OperationLogController(JdbcTemplate jdbc, OperationLogService opLog, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.opLog = opLog;
        this.objectMapper = objectMapper;
    }

    /** 单据操作记录时间线：入参 {bizType, bizNo, bizId?}，按时间正序。 */
    @PostMapping("/bill-timeline")
    public ApiResponse<List<Map<String, Object>>> billTimeline(@RequestBody Map<String, Object> body) {
        String bizType = str(body.get("bizType"));
        String bizNo = str(body.get("bizNo"));
        if (bizType == null || bizNo == null) {
            return ApiResponse.ok(List.of());
        }
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT log_id, operate_at, operator_name, operator_account, module_name, action_name, " +
                "action, biz_no, result, request_ip, cost_time_ms, sensitive, fail_reason, " +
                "operation_content, before_value, after_value, detail " +
                "FROM sys_operation_log_runtime WHERE biz_type = ? AND biz_no = ? " +
                "ORDER BY operate_at ASC, log_id ASC",
                this::mapTimelineRow, bizType, bizNo);

        boolean admin = isAdmin();
        for (Map<String, Object> row : rows) {
            row.put("beforeValue", maskJson(row.get("beforeValue"), admin));
            row.put("afterValue", maskJson(row.get("afterValue"), admin));
        }
        return ApiResponse.ok(rows);
    }

    /** 打印打点：入参 {module, bizType?, bizId?, bizNo?}。 */
    @PostMapping("/print")
    public ApiResponse<Boolean> printPing(@RequestBody Map<String, Object> body) {
        opLog.log(strOr(body.get("module"), OperationModule.SYSTEM), OperationAction.PRINT,
                str(body.get("bizType")), str(body.get("bizId")), str(body.get("bizNo")),
                "打印单据 " + strOr(body.get("bizNo"), ""));
        return ApiResponse.ok(true);
    }

    /** 导出打点：入参 {module, bizType?, bizId?, bizNo?, filterText?}。 */
    @PostMapping("/export")
    public ApiResponse<Boolean> exportPing(@RequestBody Map<String, Object> body) {
        String filter = str(body.get("filterText"));
        String detail = "导出数据" + (filter == null ? "" : "（筛选：" + filter + "）");
        opLog.log(strOr(body.get("module"), OperationModule.SYSTEM), OperationAction.EXPORT,
                str(body.get("bizType")), str(body.get("bizId")), str(body.get("bizNo")), detail);
        return ApiResponse.ok(true);
    }

    // ==================== 脱敏 ====================

    /**
     * 非管理员对改前改后里的敏感字段（成本/采购价等）脱敏。
     * afterValue 结构：{main:[{field,old,new}], lines:[{changes:[{field,old,new}]}]}；
     * beforeValue 结构：{字段:值}。管理员原样返回。
     */
    private Object maskJson(Object parsed, boolean admin) {
        if (admin || !(parsed instanceof Map<?, ?>)) {
            return parsed;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) parsed;
            maskChangeList(root.get("main"));
            Object lines = root.get("lines");
            if (lines instanceof List<?> lineList) {
                for (Object line : lineList) {
                    if (line instanceof Map<?, ?> lm) {
                        maskChangeList(((Map<?, ?>) lm).get("changes"));
                    }
                }
            }
            // beforeValue 裸快照：敏感 key 直接掩码
            for (Map.Entry<String, Object> e : root.entrySet()) {
                if (KeyFields.isSensitiveField(e.getKey())) {
                    e.setValue("***");
                }
            }
            return root;
        } catch (Exception e) {
            return parsed;
        }
    }

    @SuppressWarnings("unchecked")
    private void maskChangeList(Object changes) {
        if (!(changes instanceof List<?> list)) return;
        for (Object c : list) {
            if (c instanceof Map<?, ?> cm) {
                Map<String, Object> change = (Map<String, Object>) cm;
                if (KeyFields.isSensitiveField(str(change.get("field")))) {
                    change.put("old", "***");
                    change.put("new", "***");
                }
            }
        }
    }

    private boolean isAdmin() {
        // PRD-28：优先读线程级当前用户；兼容老令牌再回落 SecurityContext 角色判定
        try {
            if (com.erp.common.security.CurrentUser.isSuperAdmin()) return true;
        } catch (Exception ignored) {}
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) return false;
            for (GrantedAuthority a : auth.getAuthorities()) {
                if ("ROLE_SYS_ADMIN".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ==================== 行映射 ====================

    private Map<String, Object> mapTimelineRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        for (int i = 1; i <= cols; i++) {
            String col = meta.getColumnLabel(i).toLowerCase();
            Object val = rs.getObject(i);
            if ("before_value".equals(col) || "after_value".equals(col)) {
                val = parseJson(rs.getString(i));
            }
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

    private String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return (s.isEmpty() || "null".equalsIgnoreCase(s)) ? null : s;
    }

    private String strOr(Object v, String fallback) {
        String s = str(v);
        return s == null ? fallback : s;
    }
}
