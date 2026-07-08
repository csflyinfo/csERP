package com.erp.common.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.TimeZone;

/**
 * 全局 Jackson 配置：日期时间统一按规范格式返回给前端
 * <p>
 * 规范文档：{@code docs/PRD-版本化产品需求/00-总览与规范/规范-日期时间字段格式.md}
 * <ul>
 *   <li>时刻（LocalDateTime / Timestamp / Date）→ {@code yyyy-MM-dd HH:mm:ss}</li>
 *   <li>日期（LocalDate）→ {@code yyyy-MM-dd}</li>
 * </ul>
 * 对 JdbcTemplate 返回的 {@code Map<String, Object>} 里的 LocalDateTime / Timestamp 同样生效。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            JavaTimeModule javaTime = new JavaTimeModule();
            javaTime.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FMT));
            javaTime.addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FMT));
            javaTime.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FMT));
            javaTime.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FMT));

            // java.sql.Timestamp（JdbcTemplate MySQL 返回类型）
            SimpleModule sqlModule = new SimpleModule();
            sqlModule.addSerializer(java.sql.Timestamp.class, new com.fasterxml.jackson.databind.JsonSerializer<java.sql.Timestamp>() {
                @Override public void serialize(java.sql.Timestamp value, com.fasterxml.jackson.core.JsonGenerator gen, com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                    gen.writeString(value.toLocalDateTime().format(DATE_TIME_FMT));
                }
            });
            sqlModule.addSerializer(java.sql.Date.class, new com.fasterxml.jackson.databind.JsonSerializer<java.sql.Date>() {
                @Override public void serialize(java.sql.Date value, com.fasterxml.jackson.core.JsonGenerator gen, com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                    gen.writeString(value.toLocalDate().format(DATE_FMT));
                }
            });

            builder.modules(javaTime, sqlModule);
            builder.simpleDateFormat("yyyy-MM-dd HH:mm:ss"); // 兼容 java.util.Date
            builder.timeZone(Objects.requireNonNull(TimeZone.getTimeZone("Asia/Shanghai")));
        };
    }
}
