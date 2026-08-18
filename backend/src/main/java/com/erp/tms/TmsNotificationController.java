package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.tms.service.TmsNotifyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TMS 消息通知（P4-1）——消费端接口。
 *
 * 生产端在 {@link TmsNotifyService}，本类只负责读与置已读。
 *
 * APP 接口：
 *   POST /tms/app/notification/list           消息列表（支持未读过滤 + 类型过滤）
 *   POST /tms/app/notification/unread-count   未读数 + 轮询间隔（APP 角标与定时器都靠它）
 *   POST /tms/app/notification/read           单条或批量置已读
 *   POST /tms/app/notification/read-all       全部置已读
 *   POST /tms/app/notification/register-token 注册/更新设备推送令牌
 *
 * ERP 接口：
 *   POST /tms/notification/page               调度员收到的消息（异常告警等）
 *   POST /tms/notification/read               置已读
 *   POST /tms/notification/unread-count       未读数
 *
 * 全部用 POST：APP 侧 ApiService 只暴露 post，一旦写成 @GetMapping 前端会拿到 405。
 * 既有 /system/notification/unread-count 就是踩了这个坑（GET 接口被 post 调用，
 * 异常被 catch 静默吞掉，角标恒为 0），此处不重复该错误。
 *
 * 收件人一律取自 JWT（司机取 currentDriverId，ERP 用户取 currentUser），
 * 绝不接受前端传 receiverId——否则任何人都能读别人的消息。
 */
@RestController
public class TmsNotificationController {

    private final JdbcTemplate jdbcTemplate;

    public TmsNotificationController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String LIST_COLUMNS = """
            notify_id, notify_no, receiver_type, receiver_id, receiver_name,
            notify_type, level, title, content,
            link_type, link_id, biz_no,
            is_read, read_at, push_status, push_channel, pushed_at,
            sender, create_time, remark
            """;

    // ========================================================================
    // APP 端
    // ========================================================================

    /**
     * 司机消息列表。
     * 入参：onlyUnread?（默认 false）、notifyType?、limit?（默认 50）
     *
     * 不分页而是限条数：司机端消息是「看最近的」，无人会翻到第 5 页；
     * 上分页组件反而要多传 page/pageSize 且下拉加载逻辑复杂，收益为零。
     */
    @PostMapping("/tms/app/notification/list")
    public ApiResponse<Map<String, Object>> appList(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String driverId = TmsUtil.currentDriverId();

        StringBuilder sql = new StringBuilder("SELECT " + LIST_COLUMNS
                + " FROM tms_notification WHERE receiver_type = 'DRIVER' AND receiver_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(driverId);

        if (Boolean.parseBoolean(TmsUtil.str(b.get("onlyUnread")))) {
            sql.append(" AND is_read = FALSE");
        }
        String type = TmsUtil.str(b.get("notifyType"));
        if (!type.isEmpty()) { sql.append(" AND notify_type = ?"); args.add(type); }

        // 紧急消息优先置顶：司机打开消息中心时，车辆故障回执不该被淹没在普通派单通知里
        sql.append("""
                 ORDER BY is_read ASC,
                          CASE level WHEN 'URGENT' THEN 0 WHEN 'IMPORTANT' THEN 1 ELSE 2 END ASC,
                          create_time DESC
                """);

        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray());
        int limit = TmsUtil.toInt(b.getOrDefault("limit", 50));
        if (limit > 0 && rows.size() > limit) rows = new ArrayList<>(rows.subList(0, limit));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("count", rows.size());
        result.put("unreadCount", countUnread(TmsNotifyService.RECEIVER_DRIVER, driverId));
        return ApiResponse.ok(result);
    }

    /**
     * 未读数 + 轮询间隔。
     *
     * 把 pollSeconds 一并返回而不是让 APP 单独调参数接口：APP 每次拉未读数都会调本接口，
     * 顺带下发间隔可让运维改参数后客户端自动生效，无需发版也无需额外请求。
     */
    @PostMapping("/tms/app/notification/unread-count")
    public ApiResponse<Map<String, Object>> appUnreadCount() {
        String driverId = TmsUtil.currentDriverId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unreadCount", countUnread(TmsNotifyService.RECEIVER_DRIVER, driverId));
        result.put("urgentCount", countUrgentUnread(driverId));
        result.put("pollSeconds", pollSeconds());
        return ApiResponse.ok(result);
    }

    /**
     * 置已读。入参：notifyId 或 notifyIds[]
     *
     * WHERE 带上 receiver_id：只传 notifyId 就能改的话，构造一次请求即可把别人的消息标掉。
     */
    @PostMapping("/tms/app/notification/read")
    public ApiResponse<Map<String, Object>> appRead(@RequestBody Map<String, Object> body) {
        List<String> ids = extractIds(body);
        if (ids.isEmpty()) return ApiResponse.fail("400", "缺少消息 ID");
        int n = markRead(TmsNotifyService.RECEIVER_DRIVER, TmsUtil.currentDriverId(), ids);
        return ApiResponse.ok(Map.of("updated", n, "unreadCount",
                countUnread(TmsNotifyService.RECEIVER_DRIVER, TmsUtil.currentDriverId())));
    }

    /** 全部置已读。 */
    @PostMapping("/tms/app/notification/read-all")
    public ApiResponse<Map<String, Object>> appReadAll() {
        int n = markRead(TmsNotifyService.RECEIVER_DRIVER, TmsUtil.currentDriverId(), null);
        return ApiResponse.ok(Map.of("updated", n, "unreadCount", 0));
    }

    /**
     * 注册/更新设备推送令牌。
     * 入参：deviceToken（必填）、platform?、channel?、deviceModel?、appVersion?、enabled?
     *
     * 现在真推送还没接（TMS_PUSH_ENABLED=false），但令牌要提前收集：
     * 否则接入极光当天所有存量司机都得重新登录一次才能收到推送。
     *
     * 同一 device_token 重复注册走更新：APP 每次启动都会上报令牌，
     * 若无脑 INSERT，一个司机跑半年会攒出几百条重复记录，推送时重复发送。
     */
    @PostMapping("/tms/app/notification/register-token")
    public ApiResponse<Map<String, Object>> registerToken(@RequestBody Map<String, Object> body) {
        String driverId = TmsUtil.currentDriverId();
        String deviceToken = TmsUtil.str(body.get("deviceToken"));
        if (deviceToken.isEmpty()) return ApiResponse.fail("400", "缺少设备令牌");

        String channel = TmsUtil.str(body.getOrDefault("channel", "JPUSH"));
        if (channel.isEmpty()) channel = "JPUSH";
        boolean enabled = body.get("enabled") == null || Boolean.parseBoolean(TmsUtil.str(body.get("enabled")));

        List<String> exists = jdbcTemplate.queryForList(
                "SELECT token_id FROM tms_push_token WHERE device_token = ?", String.class, deviceToken);

        String tokenId;
        if (exists.isEmpty()) {
            tokenId = TmsUtil.uuid("PT");
            jdbcTemplate.update("""
                    INSERT INTO tms_push_token
                      (token_id, driver_id, device_token, platform, channel,
                       device_model, app_version, enabled, last_active, create_time)
                    VALUES (?,?,?,?,?, ?,?,?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    tokenId, driverId, deviceToken,
                    TmsUtil.str(body.get("platform")), channel,
                    TmsUtil.str(body.get("deviceModel")), TmsUtil.str(body.get("appVersion")), enabled);
        } else {
            tokenId = exists.get(0);
            // driver_id 一起更新：同一台设备换司机登录（共用备用机）必须改绑，
            // 否则新司机的消息会推给前一个司机
            jdbcTemplate.update("""
                    UPDATE tms_push_token
                       SET driver_id = ?, platform = ?, channel = ?, device_model = ?,
                           app_version = ?, enabled = ?, last_active = CURRENT_TIMESTAMP
                     WHERE token_id = ?
                    """,
                    driverId, TmsUtil.str(body.get("platform")), channel,
                    TmsUtil.str(body.get("deviceModel")), TmsUtil.str(body.get("appVersion")),
                    enabled, tokenId);
        }
        return ApiResponse.ok(Map.of("tokenId", tokenId, "pushEnabled", pushEnabled()));
    }

    // ========================================================================
    // ERP 端
    // ========================================================================

    /**
     * 当前 ERP 用户收到的消息（主要是司机异常上报告警）。
     * 支持 filters：notifyType、level、isRead、keyword
     */
    @PostMapping("/tms/notification/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        StringBuilder sql = new StringBuilder("SELECT " + LIST_COLUMNS
                + " FROM tms_notification WHERE receiver_type = 'USER' AND receiver_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(TmsUtil.currentUser());

        String type = TmsUtil.str(filters.get("notifyType"));
        if (!type.isEmpty()) { sql.append(" AND notify_type = ?"); args.add(type); }
        String level = TmsUtil.str(filters.get("level"));
        if (!level.isEmpty()) { sql.append(" AND level = ?"); args.add(level); }
        String isRead = TmsUtil.str(filters.get("isRead"));
        if (!isRead.isEmpty()) {
            sql.append(Boolean.parseBoolean(isRead) ? " AND is_read = TRUE" : " AND is_read = FALSE");
        }
        String keyword = TmsUtil.str(filters.get("keyword"));
        if (!keyword.isEmpty()) {
            sql.append(" AND (title LIKE ? OR content LIKE ? OR biz_no LIKE ?)");
            String kw = "%" + keyword + "%";
            args.add(kw); args.add(kw); args.add(kw);
        }
        sql.append(" ORDER BY is_read ASC, create_time DESC");

        return ApiResponse.ok(PageResult.of(
                TmsUtil.queryCamel(jdbcTemplate, sql.toString(), args.toArray()), request));
    }

    /** ERP 端置已读。入参：notifyId 或 notifyIds[]；不传则全部已读。 */
    @PostMapping("/tms/notification/read")
    public ApiResponse<Map<String, Object>> read(@RequestBody(required = false) Map<String, Object> body) {
        List<String> ids = extractIds(body);
        String user = TmsUtil.currentUser();
        int n = markRead(TmsNotifyService.RECEIVER_USER, user, ids.isEmpty() ? null : ids);
        return ApiResponse.ok(Map.of("updated", n, "unreadCount",
                countUnread(TmsNotifyService.RECEIVER_USER, user)));
    }

    /** ERP 端未读数。 */
    @PostMapping("/tms/notification/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        String user = TmsUtil.currentUser();
        return ApiResponse.ok(Map.of("unreadCount", countUnread(TmsNotifyService.RECEIVER_USER, user)));
    }

    // ========================================================================
    // 内部工具
    // ========================================================================

    /** 兼容单条 notifyId 与批量 notifyIds 两种入参形态。 */
    @SuppressWarnings("unchecked")
    private List<String> extractIds(Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        if (body == null) return ids;
        String single = TmsUtil.str(body.get("notifyId"));
        if (!single.isEmpty()) ids.add(single);
        Object arr = body.get("notifyIds");
        if (arr instanceof List<?> list) {
            for (Object o : (List<Object>) list) {
                String s = TmsUtil.str(o);
                if (!s.isEmpty() && !ids.contains(s)) ids.add(s);
            }
        }
        return ids;
    }

    /** ids 为 null 表示该收件人的全部未读。已读的不重复覆盖 read_at，保留首次阅读时间。 */
    private int markRead(String receiverType, String receiverId, List<String> ids) {
        if (TmsUtil.str(receiverId).isEmpty()) return 0;
        StringBuilder sql = new StringBuilder("""
                UPDATE tms_notification
                   SET is_read = TRUE, read_at = CURRENT_TIMESTAMP
                 WHERE receiver_type = ? AND receiver_id = ? AND is_read = FALSE
                """);
        List<Object> args = new ArrayList<>();
        args.add(receiverType);
        args.add(receiverId);
        if (ids != null && !ids.isEmpty()) {
            sql.append(" AND notify_id IN (")
               .append(String.join(",", ids.stream().map(x -> "?").toList()))
               .append(")");
            args.addAll(ids);
        }
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    private int countUnread(String receiverType, String receiverId) {
        if (TmsUtil.str(receiverId).isEmpty()) return 0;
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tms_notification
                 WHERE receiver_type = ? AND receiver_id = ? AND is_read = FALSE
                """, Integer.class, receiverType, receiverId);
        return n == null ? 0 : n;
    }

    private int countUrgentUnread(String driverId) {
        if (TmsUtil.str(driverId).isEmpty()) return 0;
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tms_notification
                 WHERE receiver_type = 'DRIVER' AND receiver_id = ?
                   AND is_read = FALSE AND level = 'URGENT'
                """, Integer.class, driverId);
        return n == null ? 0 : n;
    }

    /** 轮询间隔，参数缺失或非法时回退 60 秒并夹到 [30, 600]。 */
    private int pollSeconds() {
        int def = 60;
        try {
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT COALESCE(param_value, default_value) AS param_value
                      FROM sys_param_runtime WHERE param_key = 'TMS_NOTIFY_POLL_SECONDS'
                    """);
            if (rows.isEmpty()) return def;
            int v = TmsUtil.toInt(rows.get(0).get("paramValue"));
            if (v <= 0) return def;
            // 下限 30 秒：参数被误填成 1 会让每台司机手机每秒发一次请求，直接打满后端
            return Math.min(Math.max(v, 30), 600);
        } catch (Exception e) {
            return def;
        }
    }

    private boolean pushEnabled() {
        try {
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT COALESCE(param_value, default_value) AS param_value
                      FROM sys_param_runtime WHERE param_key = 'TMS_PUSH_ENABLED'
                    """);
            return !rows.isEmpty() && "true".equalsIgnoreCase(TmsUtil.str(rows.get(0).get("paramValue")));
        } catch (Exception e) {
            return false;
        }
    }
}
