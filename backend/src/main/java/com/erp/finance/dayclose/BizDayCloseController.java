package com.erp.finance.dayclose;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.security.RequirePerm;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务日结管理接口（PRD-33 §7.2）。
 *
 * <p>分页统一 POST + {@link PageRequest}（平台约定）；功能点：
 * <ul>
 *   <li>{@code finance.day_close.view} 向导/记录/定版台账/RJ 单据查看；</li>
 *   <li>{@code finance.day_close.audit} 执行日结（手工）；</li>
 *   <li>{@code finance.day_close.unaudit} 反日结/批量反日结（v1.3：纯 RBAC 控制）。</li>
 * </ul>
 * Controller 不持事务，事务全部在 {@link BizDayCloseService}。
 */
@RestController
@RequestMapping("/finance/day-close")
public class BizDayCloseController {

    private final BizDayCloseService dayCloseService;

    public BizDayCloseController(BizDayCloseService dayCloseService) {
        this.dayCloseService = dayCloseService;
    }

    // -- 向导 ----------------------------------------------------------------

    /** 7 步向导预览。body: {date:"yyyy-MM-dd"}。 */
    @RequirePerm(value = "finance.day_close.view", name = "查看")
    @PostMapping("/wizard")
    public ApiResponse<Map<String, Object>> wizard(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(dayCloseService.wizard(parseDate(body.get("date"))));
    }

    /** 工作台红色提醒（过 P0188 时刻昨日未结 / 最近自动任务失败）。 */
    @RequirePerm(value = "finance.day_close.view", name = "查看")
    @PostMapping("/reminder")
    public ApiResponse<Map<String, Object>> reminder() {
        return ApiResponse.ok(dayCloseService.overdueReminder());
    }

    // -- 日结 / 反日结 -------------------------------------------------------

    /**
     * 手工执行日结。body：
     * {date, openingConfirmed, acknowledgeHanging, acknowledgeAnomaly,
     *  cashCounts:{账户名:金额}, fundRemark}
     */
    @RequirePerm(value = "finance.day_close.audit", name = "日结")
    @PostMapping("/close")
    public ApiResponse<Map<String, Object>> close(@RequestBody Map<String, Object> body) {
        LocalDate date = parseDate(body.get("date"));
        Map<String, BigDecimal> cashCounts = new LinkedHashMap<>();
        Object raw = body.get("cashCounts");
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (v != null && !String.valueOf(v).isBlank()) {
                    cashCounts.put(String.valueOf(k), new BigDecimal(String.valueOf(v).trim()));
                }
            });
        }
        BizDayCloseService.CloseRequest req = new BizDayCloseService.CloseRequest(
                date,
                boolVal(body.get("openingConfirmed")),
                boolVal(body.get("acknowledgeHanging")),
                boolVal(body.get("acknowledgeAnomaly")),
                cashCounts,
                body.get("fundRemark") == null ? null : String.valueOf(body.get("fundRemark")).trim());
        return ApiResponse.ok(dayCloseService.close(req));
    }

    /** 单日反日结（仅当前封单日）。body: {date, reason} */
    @RequirePerm(value = "finance.day_close.unaudit", name = "反日结")
    @PostMapping("/reopen")
    public ApiResponse<Map<String, Object>> reopen(@RequestBody Map<String, Object> body) {
        LocalDate date = parseDate(body.get("date"));
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason")).trim();
        dayCloseService.reopen(date, reason);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("date", date.toString());
        r.put("reopened", true);
        return ApiResponse.ok(r);
    }

    /** 批量反日结到指定日期。body: {toDate, reason} */
    @RequirePerm(value = "finance.day_close.unaudit", name = "批量反日结")
    @PostMapping("/reopen-batch")
    public ApiResponse<Map<String, Object>> reopenBatch(@RequestBody Map<String, Object> body) {
        LocalDate toDate = parseDate(body.get("toDate"));
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason")).trim();
        return ApiResponse.ok(dayCloseService.reopenBatch(toDate, reason));
    }

    // -- 查询分页 -------------------------------------------------------------

    /** 日结记录分页。filters: from/to（yyyy-MM-dd）。 */
    @RequirePerm(value = "finance.day_close.view", name = "查看")
    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> f = filters(request);
        List<Map<String, Object>> rows = dayCloseService.listCloseRows(dateFilter(f, "from"), dateFilter(f, "to"));
        return ApiResponse.ok(PageResult.of(rows, pagingOnly(request)));
    }

    /** 日结操作日志分页。filters: from/to/action。 */
    @RequirePerm(value = "finance.day_close.view", name = "查看")
    @PostMapping("/log-page")
    public ApiResponse<PageResult<Map<String, Object>>> logPage(@RequestBody PageRequest request) {
        Map<String, Object> f = filters(request);
        List<Map<String, Object>> rows = dayCloseService.listLogs(dateFilter(f, "from"),
                dateFilter(f, "to"), f.get("action") == null ? null : String.valueOf(f.get("action")));
        return ApiResponse.ok(PageResult.of(rows, pagingOnly(request)));
    }

    /** 应收定版台账分页。filters: from/to/keyword。 */
    @RequirePerm(value = "finance.day_close.view", name = "查看")
    @PostMapping("/ar-daily/page")
    public ApiResponse<PageResult<Map<String, Object>>> arDailyPage(@RequestBody PageRequest request) {
        Map<String, Object> f = filters(request);
        List<Map<String, Object>> rows = dayCloseService.listArDaily(dateFilter(f, "from"),
                dateFilter(f, "to"), request.keyword());
        return ApiResponse.ok(PageResult.of(rows, pagingOnly(request)));
    }

    /** 应付定版台账分页。 */
    @RequirePerm(value = "finance.day_close.view", name = "查看")
    @PostMapping("/ap-daily/page")
    public ApiResponse<PageResult<Map<String, Object>>> apDailyPage(@RequestBody PageRequest request) {
        Map<String, Object> f = filters(request);
        List<Map<String, Object>> rows = dayCloseService.listApDaily(dateFilter(f, "from"),
                dateFilter(f, "to"), request.keyword());
        return ApiResponse.ok(PageResult.of(rows, pagingOnly(request)));
    }

    /** 资金定版台账分页。 */
    @RequirePerm(value = "finance.day_close.view", name = "查看")
    @PostMapping("/fund-daily/page")
    public ApiResponse<PageResult<Map<String, Object>>> fundDailyPage(@RequestBody PageRequest request) {
        Map<String, Object> f = filters(request);
        List<Map<String, Object>> rows = dayCloseService.listFundDaily(dateFilter(f, "from"),
                dateFilter(f, "to"), request.keyword());
        return ApiResponse.ok(PageResult.of(rows, pagingOnly(request)));
    }

    /** RJ 单据详情（打印）。body: {date} */
    @RequirePerm(value = "finance.day_close.view", name = "查看")
    @PostMapping("/ticket")
    public ApiResponse<Map<String, Object>> ticket(@RequestBody Map<String, Object> body) {
        Map<String, Object> detail = dayCloseService.detail(parseDate(body.get("date")));
        if (detail == null) {
            throw new IllegalArgumentException("该日期无日结记录");
        }
        return ApiResponse.ok(detail);
    }

    // -- 工具 ----------------------------------------------------------------

    private static LocalDate parseDate(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("日期不能为空（yyyy-MM-dd）");
        }
        try {
            return LocalDate.parse(String.valueOf(v).trim().substring(0, 10));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式不正确，应为 yyyy-MM-dd：" + v);
        }
    }

    private static boolean boolVal(Object v) {
        return Boolean.TRUE.equals(v) || "Y".equalsIgnoreCase(String.valueOf(v))
                || "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static Map<String, Object> filters(PageRequest request) {
        return request.filters() == null ? Map.of() : request.filters();
    }

    private static LocalDate dateFilter(Map<String, Object> f, String key) {
        Object v = f.get(key);
        return v == null || String.valueOf(v).isBlank() ? null
                : LocalDate.parse(String.valueOf(v).trim().substring(0, 10));
    }

    /**
     * 日期/动作等过滤已在 SQL 完成，{@link PageResult#of} 会把 filters 当全文模糊再滤一遍
     * （结束日期等值会误杀行），故内存分页只保留分页/排序参数。
     */
    private static PageRequest pagingOnly(PageRequest request) {
        return new PageRequest(request.pageNo(), request.pageSize(),
                request.sortField(), request.sortOrder(), null);
    }
}
