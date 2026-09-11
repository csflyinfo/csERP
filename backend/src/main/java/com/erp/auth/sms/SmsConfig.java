package com.erp.auth.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 短信通道装配（PRD-28 卡片10）。
 *
 * <ul>
 *   <li>非 prod（dev/测试/未指定）：{@link LoggingSmsSender}，验证码只进日志，
 *       登录校验另保留 888888 万能回落；</li>
 *   <li>prod：必须配置 {@code sms.webhook-url}（与可选 token/模板号），
 *       否则 Bean 创建失败、应用拒启——禁止生产环境静默退回日志通道。</li>
 * </ul>
 */
@Configuration
public class SmsConfig {

    @Bean
    @Profile("!prod")
    public SmsSender loggingSmsSender() {
        return new LoggingSmsSender();
    }

    /**
     * 生产通道：启动即校验短信网关配置，缺失抛 IllegalStateException 使上下文初始化失败。
     */
    @Bean
    @Profile("prod")
    public SmsSender webhookSmsSender(
            @Value("${sms.webhook-url:}") String webhookUrl,
            @Value("${sms.webhook-token:}") String webhookToken,
            @Value("${sms.login-template-code:}") String templateCode) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException(
                    "生产环境必须配置短信网关 sms.webhook-url（司机短信登录依赖），启动中止");
        }
        if (!(webhookUrl.startsWith("http://") || webhookUrl.startsWith("https://"))) {
            throw new IllegalStateException("sms.webhook-url 必须是 http(s) 地址，启动中止");
        }
        return new WebhookSmsSender(webhookUrl.trim(), webhookToken == null ? "" : webhookToken.trim(),
                templateCode == null ? "" : templateCode.trim());
    }
}
