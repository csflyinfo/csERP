package com.erp.report.common;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 报表中心数据源隔离（方案 §4.6）。
 *
 * <p>报表走独立 Hikari 连接池（最大 10、readOnly），与业务连接池物理隔离——
 * 报表再慢也不挤占开单/审核的连接。当前指向同一数据库，未来切只读从库只改
 * {@code report.datasource.*} 配置（逻辑路由键 report 的落地方式：直接独立池，
 * 比 AbstractRoutingDataSource 风险小，隔离更彻底；切从库仅需改 yml）。
 *
 * <p>语句级 30s 超时双保险：连接初始化 SET 会话超时（MySQL MAX_EXECUTION_TIME /
 * H2 QUERY_TIMEOUT）+ JdbcTemplate queryTimeout。
 */
@Configuration
public class ReportDataSourceConfig {

    /** 主库数据源：接管原自动配置（spring.datasource.*，含 hikari 调优），@Primary 保证 Flyway/MyBatis/业务不变。 */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    /** 主库 JdbcTemplate：显式声明后自动配置让位，全项目既有注入点继续按类型拿到主库模板。 */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 报表只读池：默认复用主库地址，可用 report.datasource.url/username/password 切只读从库。 */
    @Bean(name = "reportDataSource", destroyMethod = "close")
    public DataSource reportDataSource(DataSourceProperties properties,
                                       org.springframework.core.env.Environment env) {
        String url = env.getProperty("report.datasource.url", env.getProperty("spring.datasource.url"));
        String username = env.getProperty("report.datasource.username", env.getProperty("spring.datasource.username"));
        String password = env.getProperty("report.datasource.password", env.getProperty("spring.datasource.password", ""));
        String driver = env.getProperty("report.datasource.driver-class-name",
                env.getProperty("spring.datasource.driver-class-name"));

        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("erp-report-pool");
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        if (driver != null && !driver.isBlank()) ds.setDriverClassName(driver);
        ds.setMaximumPoolSize(env.getProperty("report.datasource.max-pool-size", Integer.class, 10));
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(10_000);       // 池满 10s 快速失败，不拖挂请求线程
        ds.setIdleTimeout(600_000);
        ds.setMaxLifetime(1_800_000);
        ds.setReadOnly(true);
        ds.setConnectionInitSql(connectionInitSql(url));
        return ds;
    }

    @Bean(name = "reportJdbcTemplate")
    public JdbcTemplate reportJdbcTemplate(@Qualifier("reportDataSource") DataSource reportDataSource) {
        JdbcTemplate tpl = new JdbcTemplate(reportDataSource);
        tpl.setQueryTimeout(30);               // 30s 语句超时（会话级超时之外的 JDBC 保险）
        return tpl;
    }

    /** MySQL：MAX_EXECUTION_TIME 毫秒，仅作用于只读 SELECT；H2：QUERY_TIMEOUT 秒。 */
    static String connectionInitSql(String url) {
        if (url != null && url.startsWith("jdbc:mysql")) {
            return "SET SESSION MAX_EXECUTION_TIME=30000";
        }
        return "SET QUERY_TIMEOUT 30";
    }
}
