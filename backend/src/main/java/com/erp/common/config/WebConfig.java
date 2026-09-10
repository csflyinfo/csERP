package com.erp.common.config;

import com.erp.common.security.PdaAppGuardInterceptor;
import com.erp.common.security.RequirePermInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RequirePermInterceptor requirePermInterceptor;
    private final PdaAppGuardInterceptor pdaAppGuardInterceptor;

    @Value("${storage.type:local}")
    private String storageType;

    @Value("${storage.local.base-dir:./data/uploads}")
    private String localBaseDir;

    @Value("${storage.local.url-prefix:/uploads}")
    private String urlPrefix;

    public WebConfig(RequirePermInterceptor requirePermInterceptor,
                     PdaAppGuardInterceptor pdaAppGuardInterceptor) {
        this.requirePermInterceptor = requirePermInterceptor;
        this.pdaAppGuardInterceptor = pdaAppGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // PRD-28 卡片9：/wms/app/** 端类型隔离（登录端点除外），非 WMS_PDA 令牌一律 401
        registry.addInterceptor(pdaAppGuardInterceptor)
                .addPathPatterns("/wms/app/**")
                .excludePathPatterns(PdaAppGuardInterceptor.LOGIN_PATH);
        // PRD-28：Controller 方法/类上的 @RequirePerm 功能权限校验
        registry.addInterceptor(requirePermInterceptor).addPathPatterns("/**");
    }

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
