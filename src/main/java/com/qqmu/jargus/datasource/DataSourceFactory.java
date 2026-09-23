package com.qqmu.jargus.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源工厂
 * 根据配置动态创建 HikariCP 数据源
 */
@Slf4j
@Component
public class DataSourceFactory {

    /**
     * 已创建的数据源缓存
     */
    private final Map<String, DataSource> dataSourceCache = new ConcurrentHashMap<>();

    /**
     * 创建数据源
     *
     * @param driverClass 驱动类名
     * @param jdbcUrl     JDBC URL
     * @param username    用户名
     * @param password    密码
     * @param poolName    连接池名称
     * @param maxPoolSize 最大连接数
     * @param minIdle     最小空闲连接
     * @return DataSource
     */
    public DataSource createDataSource(
            String driverClass,
            String jdbcUrl,
            String username,
            String password,
            String poolName,
            int maxPoolSize,
            int minIdle,
            long connectionTimeout
    ) {
        String cacheKey = driverClass + "|" + jdbcUrl + "|" + username;
        if (dataSourceCache.containsKey(cacheKey)) {
            return dataSourceCache.get(cacheKey);
        }

        HikariConfig config = new HikariConfig();
        config.setDriverClassName(driverClass);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName(poolName != null ? poolName : "HikariPool-" + System.currentTimeMillis());
        config.setMaximumPoolSize(maxPoolSize > 0 ? maxPoolSize : 10);
        config.setMinimumIdle(minIdle > 0 ? minIdle : 5);
        config.setConnectionTimeout(connectionTimeout > 0 ? connectionTimeout : 30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        HikariDataSource dataSource = new HikariDataSource(config);
        dataSourceCache.put(cacheKey, dataSource);

        log.info("创建数据源成功: driver={}, url={}, pool={}", driverClass, jdbcUrl, config.getPoolName());
        return dataSource;
    }

    /**
     * 测试连接
     *
     * @param driverClass 驱动类名
     * @param jdbcUrl     JDBC URL
     * @param username    用户名
     * @param password    密码
     * @return null 表示连接成功；否则返回失败原因
     */
    public String testConnection(String driverClass, String jdbcUrl, String username, String password) {
        HikariDataSource dataSource = null;
        try {
            HikariConfig config = new HikariConfig();
            config.setDriverClassName(driverClass);
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);
            config.setPoolName("test-pool-" + System.currentTimeMillis());
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(10000);

            dataSource = new HikariDataSource(config);
            dataSource.getConnection().close();
            return null;
        } catch (Exception e) {
            log.error("测试连接失败: {}", e.getMessage(), e);
            // 优先取第一层厂商驱动异常（如 ORA-xxxxx、DMException、KSQLException），
            // 而不是连接池包装信息或最底层的 ConnectException
            Throwable c = e.getCause();
            String msg = (c != null && c.getMessage() != null && !c.getMessage().isBlank())
                    ? c.getMessage() : e.getMessage();
            return msg != null ? msg : "未知错误";
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }
}
