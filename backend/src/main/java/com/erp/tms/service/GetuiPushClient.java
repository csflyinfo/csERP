package com.erp.tms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 个推（GeTui）服务端 REST API V2 客户端。
 *
 * <p>只实现本项目用得到的两个能力：鉴权取 token、按 CID 单推透传消息。
 * 未引入官方 SDK——个推 Java SDK 会带入一整套 HTTP/JSON 依赖，
 * 而本仓库 Java 21 自带 {@link HttpClient}，两个接口手写反而更可控。
 *
 * <p><b>为什么发透传而不是通知消息</b>：APP 侧（PushService）接的是
 * onReceivePayload / onTransmitUserMessageReceive，通知栏由 APP 自己渲染，
 * 这样紧急消息才能用 Importance.max 弹横幅、普通消息安静入栏。
 * 代价是透传只在 APP 进程存活时可达，离线补偿依赖 APP 前台轮询站内消息。
 *
 * <p>本类不抛业务异常：所有失败都转成返回 false + 日志，
 * 由调用方决定如何标记推送状态，绝不能影响派单等主流程。
 */
@Component
public class GetuiPushClient {

    private static final Logger log = LoggerFactory.getLogger(GetuiPushClient.class);

    private static final String BASE_URL = "https://restapi.getui.com/v2/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 个推约定：业务接口返回此码代表 token 失效，需重新鉴权后重试。 */
    private static final int CODE_TOKEN_INVALID = 10001;

    /** 透传内容上限 3072 字，超限个推直接拒收，故发送前截断。 */
    private static final int TRANSMISSION_MAX = 3000;

    /** 提前 5 分钟视为过期，避免卡在有效期边界上推送失败。 */
    private static final long EXPIRE_SAFE_GAP_MS = 5 * 60 * 1000L;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final Object tokenLock = new Object();
    private String cachedAppId = "";
    private String cachedToken = "";
    private long tokenExpireAt = 0L;

    /** 个推应用凭据，来自 sys_param_runtime 参数表。 */
    public record Credential(String appId, String appKey, String masterSecret) {
        public boolean complete() {
            return appId != null && !appId.isBlank()
                    && appKey != null && !appKey.isBlank()
                    && masterSecret != null && !masterSecret.isBlank();
        }
    }

    /**
     * 向单个 CID 推送透传消息。
     *
     * @return 个推受理成功返回 true；凭据缺失、网络异常、业务码非 0 均返回 false
     */
    public boolean pushTransmission(Credential cred, String cid, String transmission) {
        if (!cred.complete() || cid == null || cid.isBlank()) return false;

        String body = buildSingleCidBody(cid, transmission);

        JsonNode resp = callPush(cred, body, false);
        // token 失效走一次强制刷新重试：个推 token 有效期 1 天，
        // 服务重启或跨天时缓存必然失效，被动刷新比定时任务更省心
        if (resp != null && resp.path("code").asInt(-1) == CODE_TOKEN_INVALID) {
            log.info("个推 token 失效，刷新后重试 cid={}", mask(cid));
            resp = callPush(cred, body, true);
        }

        if (resp == null) return false;
        int code = resp.path("code").asInt(-1);
        if (code != 0) {
            log.warn("个推推送失败 cid={} code={} msg={}", mask(cid), code, resp.path("msg").asText(""));
            return false;
        }
        return true;
    }

    /**
     * 批量推送，逐个 CID 调用单推。
     *
     * <p>没用 /push/single/batch/cid：一个司机的在用设备通常只有 1~2 台，
     * 批量接口的分片（≤200）与部分失败判定会显著增加出错面，收益不成比例。
     *
     * @return 成功条数
     */
    public int pushTransmissionBatch(Credential cred, List<String> cids, String transmission) {
        if (cids == null || cids.isEmpty()) return 0;
        int ok = 0;
        for (String cid : cids) {
            if (pushTransmission(cred, cid, transmission)) ok++;
        }
        return ok;
    }

    private String buildSingleCidBody(String cid, String transmission) {
        String payload = transmission == null ? "" : transmission;
        if (payload.length() > TRANSMISSION_MAX) {
            payload = payload.substring(0, TRANSMISSION_MAX);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        // request_id 要求 10-32 位且不可重复，重复会被个推当成同一条消息丢弃
        body.put("request_id", UUID.randomUUID().toString().replace("-", ""));
        body.put("audience", Map.of("cid", List.of(cid)));
        // 未开通厂商通道（VIP 功能），不填 settings.strategy，默认走个推通道
        body.put("push_message", Map.of("transmission", payload));

        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("构造个推请求体失败 err={}", e.getMessage());
            return "";
        }
    }

    private JsonNode callPush(Credential cred, String body, boolean forceRefreshToken) {
        if (body.isEmpty()) return null;
        String token = obtainToken(cred, forceRefreshToken);
        if (token.isEmpty()) return null;

        return postJson(BASE_URL + cred.appId() + "/push/single/cid", body, token);
    }

    /** 取 token，优先用缓存。appId 变更或强制刷新时重新鉴权。 */
    private String obtainToken(Credential cred, boolean forceRefresh) {
        synchronized (tokenLock) {
            boolean usable = !forceRefresh
                    && !cachedToken.isEmpty()
                    && cred.appId().equals(cachedAppId)
                    && System.currentTimeMillis() + EXPIRE_SAFE_GAP_MS < tokenExpireAt;
            if (usable) return cachedToken;

            // 鉴权接口限流每分钟 100 次、每天 10 万次，必须靠缓存兜住，不能每条消息都取
            String token = requestToken(cred);
            if (token.isEmpty()) {
                cachedToken = "";
                tokenExpireAt = 0L;
                return "";
            }
            cachedAppId = cred.appId();
            cachedToken = token;
            return token;
        }
    }

    private String requestToken(Credential cred) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String sign = sha256(cred.appKey() + timestamp + cred.masterSecret());
        if (sign.isEmpty()) return "";

        String body;
        try {
            body = MAPPER.writeValueAsString(Map.of(
                    "sign", sign,
                    "timestamp", timestamp,
                    "appkey", cred.appKey()));
        } catch (Exception e) {
            log.warn("构造个推鉴权请求体失败 err={}", e.getMessage());
            return "";
        }

        JsonNode resp = postJson(BASE_URL + cred.appId() + "/auth", body, null);
        if (resp == null) return "";
        if (resp.path("code").asInt(-1) != 0) {
            log.warn("个推鉴权失败 code={} msg={}", resp.path("code").asInt(-1), resp.path("msg").asText(""));
            return "";
        }

        String token = resp.path("data").path("token").asText("");
        // expire_time 是毫秒时间戳字符串；解析不出来时按 12 小时兜底，
        // 宁可多鉴权一次也不能拿着过期 token 一直失败
        long expire = parseLong(resp.path("data").path("expire_time").asText(""));
        tokenExpireAt = expire > 0 ? expire : System.currentTimeMillis() + 12 * 3600_000L;
        return token;
    }

    private JsonNode postJson(String url, String body, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (token != null && !token.isEmpty()) {
                builder.header("token", token);
            }

            HttpResponse<String> resp = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("个推接口 HTTP 异常 url={} status={}", url, resp.statusCode());
                return null;
            }
            return MAPPER.readTree(resp.body());
        } catch (InterruptedException e) {
            // 恢复中断标记，否则会吞掉线程池的关闭信号
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("个推接口调用失败 url={} err={}", url, e.getMessage());
            return null;
        }
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("计算个推签名失败 err={}", e.getMessage());
            return "";
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 日志里不打完整 CID：属设备标识，避免日志外泄后被用来定向推送。 */
    private static String mask(String cid) {
        if (cid == null || cid.length() <= 8) return "***";
        return cid.substring(0, 4) + "***" + cid.substring(cid.length() - 4);
    }
}
