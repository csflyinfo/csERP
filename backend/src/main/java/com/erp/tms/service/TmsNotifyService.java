package com.erp.tms.service;

import com.erp.tms.TmsUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TMS 消息通知发送服务（P4-1）。
 *
 * <p>这是全项目第一个消息「生产端」。此前 sys_notification 表虽然存在，
 * 但全仓零处 INSERT，消息中心永远是空的；本服务补上统一发送入口。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>发送绝不抛异常</b>：消息是业务的旁路，不能因为发消息失败而回滚派单、
 *       让调度员点不了「确认派单」。所有方法内部兜底捕获，失败只记日志。
 *       这也是为什么本类不加 {@code @Transactional}——若与调用方共享事务，
 *       消息写入失败仍会连带业务回滚，与「旁路」定位矛盾。</li>
 *   <li><b>真推送已接入个推</b>：{@link #pushToDevice} 通过 {@link GetuiPushClient}
 *       下发透传消息。参数 TMS_PUSH_ENABLED 为 false、或个推凭据未配齐时标记 SKIPPED，
 *       由 APP 轮询拉取；推送失败标记 FAILED，两者可区分排查。</li>
 *   <li>本类不做权限校验：调用方都是已鉴权的 Controller。</li>
 * </ul>
 *
 * <p>为什么是 Service 而不是像其他 TMS 代码一样平铺在 Controller：
 * 消息发送要被 6 个以上不同 Controller 复用，若继续平铺会产生大量重复 SQL，
 * 且未来接真推送时需要改动每一处。这是项目里少数值得抽层的横切逻辑。
 *
 * <p>收件人标识约定（与既有代码保持一致，勿自行发明）：
 * <ul>
 *   <li>司机：receiver_id = base_employee.employee_id，即 TmsUtil.currentDriverId() 的值</li>
 *   <li>ERP 用户：receiver_id = sys_user_runtime.username</li>
 * </ul>
 */
@Service
public class TmsNotifyService {

    private static final Logger log = LoggerFactory.getLogger(TmsNotifyService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 推送渠道：与 APP 上报的 channel、参数 TMS_PUSH_CHANNEL 三处必须一致。 */
    public static final String CHANNEL_GETUI = "GETUI";

    private final JdbcTemplate jdbcTemplate;
    private final GetuiPushClient getuiPushClient;

    public TmsNotifyService(JdbcTemplate jdbcTemplate, GetuiPushClient getuiPushClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.getuiPushClient = getuiPushClient;
    }

    // ========================================================================
    // 消息类型常量（与 V65 迁移脚本注释一一对应）
    // ========================================================================
    public static final String TYPE_NEW_TASK          = "NEW_TASK";
    public static final String TYPE_EXCEPTION_REPLY   = "EXCEPTION_REPLY";
    public static final String TYPE_SETTLE_RESULT     = "SETTLE_RESULT";
    public static final String TYPE_REJECT_RESULT     = "REJECT_RESULT";
    public static final String TYPE_RESCHEDULE_RESULT = "RESCHEDULE_RESULT";
    public static final String TYPE_EXCEPTION_ALERT   = "EXCEPTION_ALERT";
    public static final String TYPE_SYSTEM            = "SYSTEM";

    public static final String LEVEL_NORMAL    = "NORMAL";
    public static final String LEVEL_IMPORTANT = "IMPORTANT";
    public static final String LEVEL_URGENT    = "URGENT";

    public static final String RECEIVER_DRIVER = "DRIVER";
    public static final String RECEIVER_USER   = "USER";

    // ========================================================================
    // 对外发送入口
    // ========================================================================

    /**
     * 给司机发消息。
     *
     * @param driverId  司机 ID（base_employee.employee_id），为空则直接跳过（派单时可能还未指定司机）
     * @param type      消息类型，见本类 TYPE_* 常量
     * @param level     紧急程度，见 LEVEL_* 常量
     * @param title     标题
     * @param content   正文
     * @param linkType  APP 跳转类型（DISPATCH/EXCEPTION/...），可为 null
     * @param linkId    跳转业务 ID，可为 null
     * @param bizNo     业务单号，用于展示
     * @return 消息 ID；发送失败返回 null（调用方无需处理）
     */
    public String notifyDriver(String driverId, String type, String level,
                               String title, String content,
                               String linkType, String linkId, String bizNo) {
        return send(RECEIVER_DRIVER, driverId, null, type, level, title, content, linkType, linkId, bizNo);
    }

    /** 给司机发普通级别消息的简化重载。 */
    public String notifyDriver(String driverId, String type, String title, String content,
                               String linkType, String linkId, String bizNo) {
        return notifyDriver(driverId, type, LEVEL_NORMAL, title, content, linkType, linkId, bizNo);
    }

    /**
     * 给 ERP 用户发消息（如司机上报异常后反向提醒调度员）。
     *
     * @param username sys_user_runtime.username，为空则跳过
     */
    public String notifyUser(String username, String type, String level,
                             String title, String content,
                             String linkType, String linkId, String bizNo) {
        return send(RECEIVER_USER, username, null, type, level, title, content, linkType, linkId, bizNo);
    }

    /**
     * 给一组 ERP 用户群发（如通知全部调度员）。逐条落库，单条失败不影响其余。
     *
     * @return 成功发送的条数
     */
    public int notifyUsers(List<String> usernames, String type, String level,
                           String title, String content,
                           String linkType, String linkId, String bizNo) {
        if (usernames == null || usernames.isEmpty()) return 0;
        int ok = 0;
        for (String u : usernames) {
            if (notifyUser(u, type, level, title, content, linkType, linkId, bizNo) != null) ok++;
        }
        return ok;
    }

    /**
     * 查询拥有指定角色的用户名列表，用于「通知全部调度员」这类场景。
     *
     * <p>注意本项目的用户与角色<b>没有关联表</b>：sys_user_runtime.role_name 直接冗余存
     * sys_role_runtime.role_name（如 '管理员组'），因此按 role_code 找人要先转成 role_name 再匹配。
     * 这不是好设计，但改造用户体系不属于本次范围，此处顺从既有结构。
     *
     * <p>查不到时返回空列表而非抛错——没有配调度员角色不该阻断司机上报异常。
     */
    public List<String> findUsernamesByRole(String roleCode) {
        try {
            return jdbcTemplate.queryForList("""
                    SELECT u.username
                      FROM sys_user_runtime u
                      JOIN sys_role_runtime r ON r.role_name = u.role_name
                     WHERE r.role_code = ?
                       AND u.status = 'NORMAL'
                       AND r.status = 'NORMAL'
                    """, String.class, roleCode);
        } catch (Exception e) {
            log.warn("查询角色用户失败 roleCode={} err={}", roleCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询应当接收司机异常提醒的用户名。
     *
     * <p>先找调度角色（DISPATCH），找不到再退到管理员（ADMIN）——异常提醒宁可多发给管理员，
     * 也不能因为没配调度角色而无人知晓司机在路上出了问题。
     */
    public List<String> findDispatcherUsernames() {
        List<String> users = findUsernamesByRole("DISPATCH");
        if (users.isEmpty()) users = findUsernamesByRole("ADMIN");
        return users;
    }

    // ========================================================================
    // 内部实现
    // ========================================================================

    /**
     * 落库并尝试推送。整个流程对调用方无副作用（不抛异常、不影响其事务结果）。
     */
    private String send(String receiverType, String receiverId, String receiverName,
                        String type, String level, String title, String content,
                        String linkType, String linkId, String bizNo) {
        // 收件人为空是正常业务情形（例如调度单尚未指派司机），静默跳过而非报错
        if (TmsUtil.str(receiverId).isEmpty()) return null;

        try {
            String notifyId = TmsUtil.uuid("NT");
            String notifyNo = nextNotifyNo();
            String name = TmsUtil.str(receiverName).isEmpty()
                    ? resolveReceiverName(receiverType, receiverId)
                    : receiverName;

            jdbcTemplate.update("""
                    INSERT INTO tms_notification
                      (notify_id, notify_no, receiver_type, receiver_id, receiver_name,
                       notify_type, level, title, content,
                       link_type, link_id, biz_no,
                       is_read, push_status, sender, create_time)
                    VALUES (?,?,?,?,?, ?,?,?,?, ?,?,?, FALSE, 'PENDING', ?, CURRENT_TIMESTAMP)
                    """,
                    notifyId, notifyNo, receiverType, receiverId, name,
                    type, TmsUtil.str(level).isEmpty() ? LEVEL_NORMAL : level, title, content,
                    linkType, linkId, bizNo,
                    TmsUtil.currentUser());

            // 站内消息已落库，APP 轮询即可拿到；真推送是额外增强，失败不回滚消息
            tryPush(notifyId, receiverType, receiverId, type, level, title, content, linkType, linkId);
            return notifyId;
        } catch (Exception e) {
            // 关键：吞掉异常。派单不能因为发消息失败而失败
            log.warn("发送消息失败 receiver={}:{} type={} err={}", receiverType, receiverId, type, e.getMessage());
            return null;
        }
    }

    /**
     * 尝试走第三方推送。未启用或无设备令牌时标记 SKIPPED，
     * 这样运维能一眼区分「没配推送」和「推送真失败」。
     */
    private void tryPush(String notifyId, String receiverType, String receiverId,
                         String type, String level, String title, String content,
                         String linkType, String linkId) {
        try {
            PushConfig cfg = loadPushConfig();

            // ERP 用户走 Web 端角标，不需要移动推送
            if (!RECEIVER_DRIVER.equals(receiverType) || !cfg.enabled()) {
                markPush(notifyId, "SKIPPED", "INAPP", null);
                return;
            }

            // 凭据没配齐就不要发起调用：否则每条消息都会留一条 FAILED，
            // 把「没配推送」和「推送真失败」混在一起，运维无法排查
            if (!cfg.credential().complete()) {
                markPush(notifyId, "SKIPPED", "INAPP", "推送凭据未配置");
                return;
            }

            List<String> tokens = jdbcTemplate.queryForList("""
                    SELECT device_token FROM tms_push_token
                     WHERE driver_id = ? AND enabled = TRUE AND channel = ?
                    """, String.class, receiverId, cfg.channel());

            if (tokens.isEmpty()) {
                markPush(notifyId, "SKIPPED", "INAPP", "无可用设备令牌");
                return;
            }

            int ok = pushToDevice(cfg, tokens,
                    notifyId, type, level, title, content, linkType, linkId);

            if (ok <= 0) {
                markPush(notifyId, "FAILED", cfg.channel(), "全部设备推送失败");
            } else if (ok < tokens.size()) {
                // 一台成功即算送达：司机通常只用一台在手机上，
                // 另一台可能是已卸载的旧设备，不该因此判定推送失败
                markPush(notifyId, "SENT", cfg.channel(),
                        "部分设备失败 " + ok + "/" + tokens.size());
            } else {
                markPush(notifyId, "SENT", cfg.channel(), null);
            }
        } catch (Exception e) {
            markPush(notifyId, "FAILED", null, TmsUtil.str(e.getMessage()));
        }
    }

    /**
     * 第三方推送发送点。
     *
     * <p>发的是<b>透传消息</b>（push_message.transmission）而非个推通知消息：
     * APP 侧 PushService 接的是 onReceivePayload / onTransmitUserMessageReceive，
     * 通知栏由 APP 用 flutter_local_notifications 自行渲染，
     * 才能做到紧急消息弹横幅、普通消息安静入栏，并在未登录时丢弃他人消息。
     *
     * <p>载荷 JSON 必须与 APP 侧 PushPayload.fromJson 的字段名严格一致，
     * 字段名改了 APP 会静默退化成「把原文当正文」，不会报错，所以两边不能各自改。
     *
     * @return 推送成功的设备数
     */
    private int pushToDevice(PushConfig cfg, List<String> deviceTokens,
                             String notifyId, String type, String level,
                             String title, String content,
                             String linkType, String linkId) {
        if (!CHANNEL_GETUI.equalsIgnoreCase(cfg.channel())) {
            // 参数被改成 JPUSH/FCM 等未实现渠道时，明确留痕而不是假装成功
            log.warn("推送渠道 {} 尚未实现，跳过。notifyId={}", cfg.channel(), notifyId);
            return 0;
        }

        String payload = buildPushPayload(notifyId, type, level, title, content, linkType, linkId);
        if (payload.isEmpty()) return 0;

        return getuiPushClient.pushTransmissionBatch(cfg.credential(), deviceTokens, payload);
    }

    /** 构造透传 JSON。字段名与 APP 侧 PushPayload 一一对应。 */
    private String buildPushPayload(String notifyId, String type, String level,
                                    String title, String content,
                                    String linkType, String linkId) {
        // 透传上限 3072 字，正文可能很长（如异常回执备注），
        // 这里先截断：APP 打开消息中心时会走接口取完整内容，不影响信息完整性
        String shortContent = TmsUtil.str(content);
        if (shortContent.length() > 300) {
            shortContent = shortContent.substring(0, 300) + "...";
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notifyId", TmsUtil.str(notifyId));
        m.put("notifyType", TmsUtil.str(type).isEmpty() ? TYPE_SYSTEM : type);
        m.put("level", TmsUtil.str(level).isEmpty() ? LEVEL_NORMAL : level);
        m.put("title", TmsUtil.str(title));
        m.put("content", shortContent);
        m.put("linkType", TmsUtil.str(linkType));
        m.put("linkId", TmsUtil.str(linkId));

        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            log.warn("构造推送载荷失败 notifyId={} err={}", notifyId, e.getMessage());
            return "";
        }
    }

    /** 更新推送状态。此处失败仅记日志——消息本体已落库，状态字段不准不影响司机收信。 */
    private void markPush(String notifyId, String status, String channel, String error) {
        try {
            jdbcTemplate.update("""
                    UPDATE tms_notification
                       SET push_status = ?, push_channel = ?, push_error = ?, pushed_at = CURRENT_TIMESTAMP
                     WHERE notify_id = ?
                    """, status, channel, error, notifyId);
        } catch (Exception e) {
            log.warn("更新推送状态失败 notifyId={} err={}", notifyId, e.getMessage());
        }
    }

    /** 推送配置。 */
    private record PushConfig(boolean enabled, String channel, GetuiPushClient.Credential credential) {}

    private PushConfig loadPushConfig() {
        Map<String, String> kv = new HashMap<>();
        try {
            // 走 queryCamel 统一列名大小写：H2 返回大写、MySQL 返回原样，直接 get 会漏读
            TmsUtil.queryCamel(jdbcTemplate, """
                    SELECT param_key, COALESCE(param_value, default_value) AS param_value
                      FROM sys_param_runtime
                     WHERE param_key IN ('TMS_PUSH_ENABLED','TMS_PUSH_CHANNEL',
                                         'TMS_PUSH_GETUI_APP_ID','TMS_PUSH_GETUI_APP_KEY',
                                         'TMS_PUSH_GETUI_MASTER_SECRET')
                    """).forEach(r -> kv.put(TmsUtil.str(r.get("paramKey")), TmsUtil.str(r.get("paramValue"))));
        } catch (Exception ignore) {
            // 参数表缺失时用内置默认值，不阻断发消息
        }
        boolean enabled = "true".equalsIgnoreCase(kv.getOrDefault("TMS_PUSH_ENABLED", "false"));
        // 默认 GETUI：APP 侧上报的 channel 固定为 GETUI，而 tryPush 按 channel 精确匹配查令牌，
        // 默认值若仍是 JPUSH 会查不到任何令牌，推送链路静默走不通
        String channel = kv.getOrDefault("TMS_PUSH_CHANNEL", CHANNEL_GETUI);
        GetuiPushClient.Credential cred = new GetuiPushClient.Credential(
                kv.getOrDefault("TMS_PUSH_GETUI_APP_ID", ""),
                kv.getOrDefault("TMS_PUSH_GETUI_APP_KEY", ""),
                kv.getOrDefault("TMS_PUSH_GETUI_MASTER_SECRET", ""));
        return new PushConfig(enabled,
                TmsUtil.str(channel).isEmpty() ? CHANNEL_GETUI : channel, cred);
    }

    /** 生成消息编号 XXTZ + yyyyMMdd + 4 位流水。 */
    private String nextNotifyNo() {
        try {
            String prefix = "XXTZ" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tms_notification WHERE notify_no LIKE ?", Integer.class, prefix + "%");
            return prefix + String.format("%04d", (cnt == null ? 0 : cnt) + 1);
        } catch (Exception e) {
            return "XXTZ" + System.currentTimeMillis();
        }
    }

    /**
     * 补齐收件人姓名，便于消息列表展示与后台排查。查不到返回空字符串。
     *
     * <p>司机取 base_employee.employee_name（司机就是标记了 is_deliveryman 的员工，
     * 没有独立的 tms_driver 表）；ERP 用户取 sys_user_runtime.display_name。
     */
    private String resolveReceiverName(String receiverType, String receiverId) {
        try {
            String sql = RECEIVER_DRIVER.equals(receiverType)
                    ? "SELECT employee_name FROM base_employee WHERE employee_id = ?"
                    : "SELECT display_name FROM sys_user_runtime WHERE username = ?";
            List<String> n = jdbcTemplate.queryForList(sql, String.class, receiverId);
            return n.isEmpty() ? "" : TmsUtil.str(n.get(0));
        } catch (Exception e) {
            return "";
        }
    }
}
