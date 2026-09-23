package com.qqmu.jargus.datasource;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 自定义 JDBC 驱动加载器
 * 支持运行时从外部 JAR 加载数据库驱动（通过 URLClassLoader）
 */
@Slf4j
public class CustomDriverLoader {

    /**
     * 已加载的驱动类加载器缓存
     */
    private static final Map<String, URLClassLoader> LOADED_CLASSLOADERS = new ConcurrentHashMap<>();

    /**
     * 已注册的驱动 Shim 缓存
     */
    private static final Map<String, Driver> REGISTERED_DRIVERS = new ConcurrentHashMap<>();

    /**
     * 从指定 JAR 路径加载驱动
     *
     * @param jarPath     JAR 文件路径
     * @param driverClass 驱动类全名
     * @return 是否加载成功
     */
    public static synchronized boolean loadDriver(String jarPath, String driverClass) throws Exception {
        // 如果已经注册过，直接返回
        if (REGISTERED_DRIVERS.containsKey(driverClass)) {
            return true;
        }

        File jarFile = new File(jarPath);
        if (!jarFile.exists()) {
            throw new IllegalArgumentException("驱动 JAR 文件不存在: " + jarPath);
        }

        // 创建 URLClassLoader
        URL jarUrl = jarFile.toURI().toURL();
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarUrl},
                Thread.currentThread().getContextClassLoader()
        );

        // 加载驱动类
        Class<?> clazz = classLoader.loadClass(driverClass);
        Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();

        // 使用 DriverShim 包装后注册，绕过 DriverManager 的类加载器检查
        DriverShim driverShim = new DriverShim(driver);
        DriverManager.registerDriver(driverShim);

        LOADED_CLASSLOADERS.put(driverClass, classLoader);
        REGISTERED_DRIVERS.put(driverClass, driver);

        log.info("成功加载自定义驱动: {} from {}", driverClass, jarPath);
        return true;
    }

    /**
     * 卸载驱动
     */
    public static synchronized void unloadDriver(String driverClass) {
        try {
            Driver driver = REGISTERED_DRIVERS.get(driverClass);
            if (driver != null) {
                DriverManager.deregisterDriver(new DriverShim(driver));
                REGISTERED_DRIVERS.remove(driverClass);
            }
            URLClassLoader classLoader = LOADED_CLASSLOADERS.remove(driverClass);
            if (classLoader != null) {
                classLoader.close();
            }
            log.info("已卸载驱动: {}", driverClass);
        } catch (Exception e) {
            log.warn("卸载驱动失败: {}", e.getMessage());
        }
    }

    /**
     * 驱动 Shim 类
     * 用于绕过 DriverManager 对驱动类加载器的检查
     */
    static class DriverShim implements Driver {

        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
