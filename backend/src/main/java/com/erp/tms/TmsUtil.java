package com.erp.tms;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * TMS 模块共享工具方法，对齐现有 Controller 范式（JdbcTemplate + Map + 驼峰转换）。
 * 各 Controller 通过此类避免重复私有方法。
 */
public final class TmsUtil {

    private TmsUtil() {}

    /** 查询并转驼峰命名返回 List<Map>。 */
    public static List<Map<String, Object>> queryCamel(JdbcTemplate jdbc, String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) out.add(camelize(r));
        return out;
    }

    /** 把下划线列名转驼峰（与 TransferController 一致）。 */
    public static Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (row == null) return out;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(camelizeKey(e.getKey()), e.getValue());
        }
        return out;
    }

    public static String camelizeKey(String k) {
        String lower = k.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : lower.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    public static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    public static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(str(o)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    public static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(str(o)); } catch (Exception e) { return 0; }
    }

    public static LocalDate date(Object o) {
        String s = str(o);
        if (s.isEmpty()) return LocalDate.now();
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return LocalDate.now(); }
    }

    public static LocalDateTime now() { return LocalDateTime.now(); }

    /**
     * 客户结算方式短文案（司机 APP 用）。
     *
     * 口径与 base_customer.settlement_type 一致（PREPAY 预付 / COD 货到付款 / TERM 账期）。
     * 与 ERP 端 BaseController.settlementSummary 的区别：司机只需要知道「这单该不该当场收钱」，
     * 账期的天数/截账日等细节对司机无意义，故账期统一压成「账期」两字，避免任务卡被长文案挤爆。
     * 未维护结算方式时返回空串，由前端隐藏该标签而不是显示「未知」。
     */
    public static String settlementText(Object settlementType) {
        String t = str(settlementType);
        return switch (t) {
            case "PREPAY" -> "预付";
            case "COD" -> "货到付款";
            case "TERM" -> "账期";
            default -> t;
        };
    }

    /** 是否需要司机当场收款：只有货到付款要收，预付已付、账期挂账。 */
    public static boolean needCollect(Object settlementType) {
        return "COD".equals(str(settlementType));
    }

    public static String uuid(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    /** 当前登录用户名（ERP 端）。 */
    public static String currentUser() {
        try {
            String name = SecurityContextHolder.getContext().getAuthentication().getName();
            if (name != null && !name.isBlank()) return name;
        } catch (Exception ignored) {}
        return "管理员";
    }

    /** 当前司机 ID（APP 端，登录时 subject=driverId）。 */
    public static String currentDriverId() {
        try {
            String name = SecurityContextHolder.getContext().getAuthentication().getName();
            if (name != null && !name.isBlank()) return name;
        } catch (Exception ignored) {}
        return "";
    }

    /** 写操作日志（复用 sys_operation_log_runtime）。 */
    public static void log(JdbcTemplate jdbc, String moduleCode, String action, String bizNo, String detail) {
        try {
            jdbc.update("""
                    INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                    VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, ?, 'SUCCESS', ?)
                    """, uuid("LOG"), currentUser(), moduleCode, action, bizNo, detail);
        } catch (Exception ignored) {}
    }

    public static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 从图片 URL 中提取 objectKey（/uploads/ 之后的部分，或 MinIO URL 最后一段路径）。 */
    public static String extractObjectKey(String url) {
        if (url == null || url.isEmpty()) return "";
        int idx = url.indexOf("/uploads/");
        if (idx >= 0) return url.substring(idx + 9);
        int lastSlash = url.lastIndexOf('/');
        return lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
    }
}
