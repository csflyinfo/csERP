package com.erp.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置（P5-3 性能优化）。
 * <p>
 * 缓存层次：
 * - dashboard：调度看板聚合数据，60s TTL
 * - dict：字典数据（线路/片区），24h TTL
 * 默认缓存：60s TTL，最大 1000 条
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("dashboard", "dict", "default");
        // 默认缓存：60s TTL
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(1000));
        // dict 缓存单独配置更长 TTL（24h）
        manager.registerCustomCache("dict", Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(500)
                .build());
        // dashboard 缓存 60s
        manager.registerCustomCache("dashboard", Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(100)
                .build());
        return manager;
    }
}
