package com.erp.report.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 报表统一日期口径（K2）。
 *
 * <ul>
 *   <li>默认期间：截止日期=昨天；起始日期=截止日期「上月同日的前一天」
 *       （如今天 2026-09-11 → 默认 2026-08-09 ~ 2026-09-10，用户确认口径，报表24同款）。</li>
 *   <li>入参兼容两种形态：{@code dateRange:{startDate,endDate}} 或 filters 里的
 *       {@code startDate/endDate}（前端 dateRange 控件 keyFrom/keyTo）。</li>
 *   <li>跨度护栏：明细类 ≤ {@value #MAX_SPAN_DETAIL_DAYS} 天，汇总类 ≤ {@value #MAX_SPAN_SUMMARY_DAYS} 天，
 *       超出抛 IllegalArgumentException（中文提示走异步导出/缩小范围）。</li>
 * </ul>
 */
public record ReportDateRange(LocalDate startDate, LocalDate endDate) {

    /** 明细类单次最大跨度 366 天（含端点）。 */
    public static final int MAX_SPAN_DETAIL_DAYS = 366;
    /** 汇总类单次最大跨度 2 年。 */
    public static final int MAX_SPAN_SUMMARY_DAYS = 731;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static ReportDateRange defaultPeriod() {
        LocalDate end = LocalDate.now().minusDays(1);
        return new ReportDateRange(end.minusMonths(1).minusDays(1), end);
    }

    /**
     * 月结类报表默认期间（K2 例外）：自然月本月，1 号至昨天。
     * 用于 #8 进销存汇总 / #9 库存台账（月底盘库、账簿打印习惯）。
     */
    public static ReportDateRange naturalMonthPeriod() {
        LocalDate today = LocalDate.now();
        LocalDate end = today.minusDays(1);
        LocalDate start = today.withDayOfMonth(1);
        if (start.isAfter(end)) {
            // 本月 1 号当天（无已结账日期）：退到上月整月
            start = today.minusMonths(1).withDayOfMonth(1);
            end = today.withDayOfMonth(1).minusDays(1);
        }
        return new ReportDateRange(start, end);
    }

    /** 从报表请求体解析（{@code {dateRange:{startDate,endDate}, filters:{...}}}）。 */
    public static ReportDateRange from(Map<String, Object> body) {
        return from(body, false);
    }

    /**
     * @param naturalMonthDefault 无任何日期入参时是否默认自然月本月（月结类报表），
     *                            false 则按 K2 默认「上月同日的前一天 ~ 昨天」
     */
    @SuppressWarnings("unchecked")
    public static ReportDateRange from(Map<String, Object> body, boolean naturalMonthDefault) {
        LocalDate start = null;
        LocalDate end = null;
        Object dr = body == null ? null : body.get("dateRange");
        if (dr instanceof Map<?, ?> m) {
            start = parseDate(m.get("startDate"));
            if (start == null) start = parseDate(m.get("start"));
            end = parseDate(m.get("endDate"));
            if (end == null) end = parseDate(m.get("end"));
        }
        Map<String, Object> filters = body == null ? Map.of()
                : (body.get("filters") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of());
        if (start == null) start = parseDate(filters.get("startDate"));
        if (end == null) end = parseDate(filters.get("endDate"));
        if (start == null && end == null) {
            return naturalMonthDefault ? naturalMonthPeriod() : defaultPeriod();
        }
        if (start == null) start = end.minusMonths(1).minusDays(1);
        if (end == null) end = LocalDate.now().minusDays(1);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于截止日期");
        }
        return new ReportDateRange(start, end);
    }

    /** 明细类跨度校验。 */
    public ReportDateRange checkDetailSpan() {
        checkSpan(MAX_SPAN_DETAIL_DAYS, "明细报表一次最多查询 1 年");
        return this;
    }

    /** 汇总类跨度校验。 */
    public ReportDateRange checkSummarySpan() {
        checkSpan(MAX_SPAN_SUMMARY_DAYS, "汇总报表一次最多查询 2 年");
        return this;
    }

    private void checkSpan(int maxDays, String hint) {
        long span = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (span > maxDays) {
            throw new IllegalArgumentException(hint + "，请缩小日期范围（需要全量数据请使用异步导出）");
        }
    }

    public static LocalDate parseDate(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        // 兼容 datetime 字符串：截前 10 位
        return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s, FMT);
    }

    public String text() {
        return startDate + "~" + endDate;
    }
}
