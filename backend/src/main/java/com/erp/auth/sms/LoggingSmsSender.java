package com.erp.auth.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开发/测试环境短信通道：只打日志，不实际发送（PRD-28 卡片10）。
 *
 * <p>dev profile 下登录另有 888888 万能回落，{@link SmsCodeService} 随机生成的
 * 真实验证码也会写进日志，便于在不依赖短信网关时走完整流程。
 */
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void sendVerifyCode(String mobile, String code) {
        log.info("【短信通道-dev】向 {} 发送登录验证码：{}（生产环境将由短信网关下发）", mobile, code);
    }
}
