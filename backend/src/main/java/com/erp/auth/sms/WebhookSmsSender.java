package com.erp.auth.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 生产环境通用 webhook 短信通道（PRD-28 卡片10）。
 *
 * <p>不引入阿里云/腾讯云 SDK（项目约束：零新增外部依赖），以 JDK 内置 HttpClient 调用
 * 企业短信网关的通用 JSON Webhook：
 * <pre>
 * POST {sms.webhook-url}
 * Header: Authorization: Bearer {sms.webhook-token}（配置了才发）
 * Body: {"mobile":"...","code":"123456","bizType":"LOGIN","templateCode":"..."}
 * </pre>
 * 由集成侧/网关适配器负责把该报文换成运营商模板下发；非 2xx 响应抛异常使发送失败，
 * 服务端不会返回"已发送"，司机端可安全重试（限频逻辑仍生效）。
 */
public class WebhookSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSmsSender.class);

    private final String webhookUrl;
    private final String webhookToken;
    private final String templateCode;
    private final HttpClient httpClient;

    public WebhookSmsSender(String webhookUrl, String webhookToken, String templateCode) {
        this.webhookUrl = webhookUrl;
        this.webhookToken = webhookToken;
        this.templateCode = templateCode;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override
    public void sendVerifyCode(String mobile, String code) {
        try {
            // 手工拼 JSON：入参均为服务端生成/已校验内容（手机号、6 位数字），无注入面
            String body = "{\"mobile\":\"" + mobile + "\",\"code\":\"" + code
                    + "\",\"bizType\":\"LOGIN\",\"templateCode\":\"" + templateCode + "\"}";
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (webhookToken != null && !webhookToken.isBlank()) {
                builder.header("Authorization", "Bearer " + webhookToken);
            }
            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("短信网关返回 " + resp.statusCode());
            }
            log.info("登录验证码已经 webhook 下发：mobile={}", mobile);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("短信发送失败，请稍后重试", e);
        }
    }
}
