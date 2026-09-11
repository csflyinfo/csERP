package com.erp.auth.sms;

import com.erp.common.util.RequestContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 短信验证码发码/核验（PRD-28 卡片10，方案 §8.3）。
 *
 * <p>风控口径（落库 sys_sms_code，V102 建表、V108 加锁列）：
 * <ul>
 *   <li>验证码 5 分钟有效，过期需重新获取；</li>
 *   <li>同一手机号 60 秒内只能发一次，每天（biz_type 维度）最多 10 条；</li>
 *   <li>同一验证码连续校验失败 5 次，锁定该手机号 15 分钟（发码/核验均拒绝）；</li>
 *   <li>非 prod 环境保留万能码 888888（不依赖网关即可登录），prod 必须真实验证码；</li>
 *   <li>prod 通道配置由 {@link SmsConfig} 在启动时强校验，缺失则拒启。</li>
 * </ul>
 */
@Service
public class SmsCodeService {

    public static final String BIZ_LOGIN = "LOGIN";
    public static final String DEV_FALLBACK_CODE = "888888";

    private static final Duration EXPIRE = Duration.ofMinutes(5);
    private static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final int DAILY_LIMIT = 10;
    private static final int MAX_FAIL = 5;

    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final JdbcTemplate jdbc;
    private final SmsSender smsSender;
    private final boolean prodProfile;
    private final SecureRandom random = new SecureRandom();

    public SmsCodeService(JdbcTemplate jdbc, SmsSender smsSender, Environment environment) {
        this.jdbc = jdbc;
        this.smsSender = smsSender;
        this.prodProfile = environment.acceptsProfiles(Profiles.of("prod"));
    }

    /**
     * 发送登录验证码。
     *
     * @throws IllegalArgumentException 手机号非法/限频/超限/锁定（全局转 400 中文提示）
     */
    public void sendLoginCode(String mobile, HttpServletRequest request) {
        String phone = normalizeMobile(mobile);
        ensureNotLocked(phone);

        Map<String, Object> latest = latestRow(phone, BIZ_LOGIN);
        if (latest != null) {
            Timestamp createdAt = (Timestamp) latest.get("created_at");
            if (createdAt != null && createdAt.toInstant().plus(RESEND_INTERVAL).isAfter(Instant.now())) {
                long waitSec = RESEND_INTERVAL.getSeconds()
                        - Duration.between(createdAt.toInstant(), Instant.now()).getSeconds();
                throw new IllegalArgumentException("验证码发送过于频繁，请 " + Math.max(1, waitSec) + " 秒后再试");
            }
            Integer todayCount = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM sys_sms_code WHERE mobile = ? AND biz_type = ? AND created_at >= CURRENT_DATE",
                    Integer.class, phone, BIZ_LOGIN);
            if (todayCount != null && todayCount >= DAILY_LIMIT) {
                throw new IllegalArgumentException("今日验证码发送次数已达上限（" + DAILY_LIMIT + " 次），请明日再试");
            }
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        // 个别随机数前导零会被 %06d 保留，但不允许 000000
        if ("000000".equals(code)) code = "100000";
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO sys_sms_code(id, mobile, code, biz_type, used, expire_at, created_at,
                                         fail_count, lock_until, send_ip)
                VALUES (?, ?, ?, ?, FALSE, ?, ?, 0, NULL, ?)
                """,
                "SMS" + UUID.randomUUID().toString().replace("-", "").substring(0, 13).toUpperCase(),
                phone, code, BIZ_LOGIN, Timestamp.from(Instant.now().plus(EXPIRE)), now,
                request == null ? null : RequestContext.clientIp(request));
        // 落库成功后再走通道：通道失败司机端得到错误提示，不会误以为已下发
        smsSender.sendVerifyCode(phone, code);
    }

    /**
     * 核验验证码。成功标记 used 并清除本码失败计数；失败累加，达 5 次锁手机号 15 分钟。
     *
     * @throws IllegalArgumentException 手机号非法/锁定/无待核验码/过期/错误
     */
    public void verifyLoginCode(String mobile, String input) {
        String phone = normalizeMobile(mobile);
        String inputCode = input == null ? "" : input.trim();
        if (inputCode.isEmpty()) throw new IllegalArgumentException("请输入验证码");

        // dev/test 万能码回落（prod 永不生效）；万能码同样不接受锁定手机号，保留风控语义
        if (!prodProfile && DEV_FALLBACK_CODE.equals(inputCode)) {
            ensureNotLocked(phone);
            return;
        }

        ensureNotLocked(phone);
        Map<String, Object> latest = latestRow(phone, BIZ_LOGIN);
        if (latest == null) {
            throw new IllegalArgumentException("请先获取验证码");
        }
        Boolean used = (Boolean) latest.get("used");
        Timestamp expireAt = (Timestamp) latest.get("expire_at");
        if (Boolean.TRUE.equals(used)) {
            throw new IllegalArgumentException("验证码已使用，请重新获取");
        }
        if (expireAt == null || expireAt.toInstant().isBefore(Instant.now())) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }

        if (!String.valueOf(latest.get("code")).equals(inputCode)) {
            int fails = latest.get("fail_count") == null ? 0 : ((Number) latest.get("fail_count")).intValue();
            int next = fails + 1;
            if (next >= MAX_FAIL) {
                // lock_until 必须写 now + 锁定时长；若误写成 now()，ensureNotLocked 的
                // isAfter(now) 立刻为 false，锁等于没上（验收 DRIVER-003 专项）
                jdbc.update("UPDATE sys_sms_code SET fail_count = ?, lock_until = ? WHERE id = ?",
                        next, Timestamp.from(Instant.now().plus(LOCK_DURATION)), latest.get("id"));
                throw new IllegalArgumentException(
                        "验证码连续错误次数过多，手机号已锁定 " + LOCK_DURATION.toMinutes() + " 分钟");
            }
            jdbc.update("UPDATE sys_sms_code SET fail_count = ? WHERE id = ?", next, latest.get("id"));
            int left = MAX_FAIL - next;
            throw new IllegalArgumentException("验证码错误，还可尝试 " + left + " 次");
        }

        jdbc.update("UPDATE sys_sms_code SET used = TRUE, fail_count = 0, lock_until = NULL WHERE id = ?",
                latest.get("id"));
    }

    private String normalizeMobile(String mobile) {
        String phone = mobile == null ? "" : mobile.trim();
        if (!MOBILE_PATTERN.matcher(phone).matches()) {
            throw new IllegalArgumentException("请输入正确的手机号");
        }
        return phone;
    }

    private void ensureNotLocked(String phone) {
        Map<String, Object> latest = latestRow(phone, BIZ_LOGIN);
        if (latest == null) return;
        Timestamp lockUntil = latest.get("lock_until") == null
                ? null : (Timestamp) latest.get("lock_until");
        if (lockUntil != null && lockUntil.toInstant().isAfter(Instant.now())) {
            long remainMin = (long) Math.ceil(
                    (lockUntil.toInstant().toEpochMilli() - Instant.now().toEpochMilli()) / 60000.0);
            throw new IllegalArgumentException("手机号已锁定，请 " + Math.max(1, remainMin) + " 分钟后再试");
        }
    }

    private Map<String, Object> latestRow(String phone, String bizType) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, code, used, expire_at, created_at, fail_count, lock_until
                FROM sys_sms_code
                WHERE mobile = ? AND biz_type = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """, phone, bizType);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
