package com.erp.auth.sms;

/**
 * 短信发送通道抽象（PRD-28 卡片10）。
 *
 * <p>dev/默认：{@link LoggingSmsSender} 只记日志不实际发送，登录侧另保留 888888 回落；
 * prod：{@link SmsConfig} 装配 webhook 通道（通用 JSON 网关，JDK HttpClient 实现，零新依赖），
 * 配置缺失时启动直接失败（拒启），不允许生产环境静默退化为日志通道。
 */
public interface SmsSender {

    /**
     * 发送验证码短信。
     *
     * @param mobile 手机号
     * @param code   验证码
     */
    void sendVerifyCode(String mobile, String code);
}
