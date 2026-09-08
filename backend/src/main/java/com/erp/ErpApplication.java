package com.erp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 禁用 Spring Boot 生成的默认 in-memory user（避免打印一个每次启动都变的临时密码）；
// 认证由 JwtAuthFilter + AuthController 处理。
// @EnableScheduling：开启定时任务（操作日志/登录日志保留期自动清理，PRD-31）。
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableScheduling
@MapperScan("com.erp.**.mapper")
public class ErpApplication {
    public static void main(String[] args) {
        SpringApplication.run(ErpApplication.class, args);
    }
}
