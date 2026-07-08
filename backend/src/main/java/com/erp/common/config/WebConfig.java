package com.erp.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${storage.type:local}")
    private String storageType;

    @Value("${storage.local.base-dir:./data/uploads}")
    private String localBaseDir;

    @Value("${storage.local.url-prefix:/uploads}")
    private String urlPrefix;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 本地存储模式下，将 /uploads/** 映射到文件系统目录
        // （url-prefix 含 /api 前缀供前端访问，resource handler 去掉 /api 因 context-path 已处理）
        if ("local".equals(storageType)) {
            String absPath = Paths.get(localBaseDir).toAbsolutePath().toString().replace("\\", "/");
            registry.addResourceHandler("/uploads/**")
                    .addResourceLocations("file:" + absPath + "/");
        }
    }
}
