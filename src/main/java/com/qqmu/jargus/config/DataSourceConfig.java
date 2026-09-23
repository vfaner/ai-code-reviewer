package com.qqmu.jargus.config;

import com.qqmu.jargus.datasource.DataSourceFactory;
import com.qqmu.jargus.datasource.DynamicDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 动态数据源配置
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataSourceConfig {

    @Value("${datasource.default.driver-class-name}")
    private String defaultDriver;

    @Value("${datasource.default.url}")
    private String defaultUrl;

    @Value("${datasource.default.username}")
    private String defaultUsername;

    @Value("${datasource.default.password:}")
    private String defaultPassword;

    @Value("${datasource.default.hikari.maximum-pool-size:10}")
    private int maxPoolSize;

    @Value("${datasource.default.hikari.minimum-idle:5}")
    private int minIdle;

    @Value("${datasource.default.hikari.connection-timeout:30000}")
    private long connectionTimeout;

    private final DataSourceFactory dataSourceFactory;

    @Bean
    @Primary
    public DynamicDataSource dynamicDataSource() {
        // 创建默认数据源（H2）
        DataSource defaultDataSource = dataSourceFactory.createDataSource(
                defaultDriver,
                defaultUrl,
                defaultUsername,
                defaultPassword,
                "default-pool",
                maxPoolSize,
                minIdle,
                connectionTimeout
        );

        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("default", defaultDataSource);

        log.info("动态数据源初始化完成，默认数据源: {}", defaultDriver);
        return new DynamicDataSource(defaultDataSource, targetDataSources);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DynamicDataSource dynamicDataSource) {
        return new DataSourceTransactionManager(dynamicDataSource);
    }
}
