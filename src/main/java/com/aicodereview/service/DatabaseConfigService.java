package com.aicodereview.service;

import com.aicodereview.datasource.CustomDriverLoader;
import com.aicodereview.datasource.DataSourceContextHolder;
import com.aicodereview.datasource.DataSourceFactory;
import com.aicodereview.datasource.SqlDialectAdapter;
import com.aicodereview.entity.DatabaseConfig;
import com.aicodereview.mapper.DatabaseConfigMapper;
import com.aicodereview.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.util.*;

/**
 * 数据库配置服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseConfigService {

    private final DatabaseConfigMapper databaseConfigMapper;
    private final DataSourceFactory dataSourceFactory;

    /**
     * 获取所有数据库配置
     */
    public List<DatabaseConfig> listAll() {
        List<DatabaseConfig> list = databaseConfigMapper.selectList(
                new QueryWrapper<DatabaseConfig>().orderByAsc("sort_order", "id")
        );
        // 不返回密码明文
        list.forEach(cfg -> cfg.setPassword(null));
        return list;
    }

    /**
     * 根据ID获取配置
     */
    public DatabaseConfig getById(Long id) {
        DatabaseConfig cfg = databaseConfigMapper.selectById(id);
        if (cfg != null) {
            cfg.setPassword(null);
        }
        return cfg;
    }

    /**
     * 获取激活的数据库配置
     */
    public DatabaseConfig getActive() {
        return databaseConfigMapper.selectOne(
                new QueryWrapper<DatabaseConfig>().eq("is_active", true)
        );
    }

    /**
     * 新增数据库配置
     */
    public DatabaseConfig create(DatabaseConfig config) {
        // 加密密码
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            config.setPassword(CryptoUtil.encrypt(config.getPassword()));
        }

        // 设置默认值
        if (config.getMaxPoolSize() == null) config.setMaxPoolSize(10);
        if (config.getMinIdle() == null) config.setMinIdle(5);
        if (config.getConnectionTimeout() == null) config.setConnectionTimeout(30000);
        if (config.getIsCustom() == null) config.setIsCustom(false);
        if (config.getIsActive() == null) config.setIsActive(false);
        if (config.getIsInitialized() == null) config.setIsInitialized(false);
        if (config.getSortOrder() == null) config.setSortOrder(0);

        // 根据 dbType 推断方言
        if (config.getDialect() == null || config.getDialect().isEmpty()) {
            config.setDialect(SqlDialectAdapter.fromDbType(config.getDbType()).getDialect());
        }

        // 根据 dbType 填充驱动类
        if (config.getDriverClass() == null || config.getDriverClass().isEmpty()) {
            config.setDriverClass(getDefaultDriverClass(config.getDbType()));
        }

        databaseConfigMapper.insert(config);
        log.info("新增数据库配置: id={}, name={}, type={}", config.getId(), config.getName(), config.getDbType());
        config.setPassword(null);
        return config;
    }

    /**
     * 更新数据库配置
     */
    public DatabaseConfig update(Long id, DatabaseConfig config) {
        DatabaseConfig existing = databaseConfigMapper.selectById(id);
        if (existing == null) {
            throw new RuntimeException("数据库配置不存在");
        }

        config.setId(id);

        // 如果传了密码，加密；否则保留原有密码
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            config.setPassword(CryptoUtil.encrypt(config.getPassword()));
        } else {
            config.setPassword(existing.getPassword());
        }

        databaseConfigMapper.updateById(config);
        log.info("更新数据库配置: id={}", id);

        DatabaseConfig updated = databaseConfigMapper.selectById(id);
        updated.setPassword(null);
        return updated;
    }

    /**
     * 删除数据库配置
     */
    public boolean delete(Long id) {
        DatabaseConfig config = databaseConfigMapper.selectById(id);
        if (config == null) {
            return false;
        }
        if (Boolean.TRUE.equals(config.getIsActive())) {
            throw new RuntimeException("不能删除当前激活的数据库配置");
        }
        databaseConfigMapper.deleteById(id);
        log.info("删除数据库配置: id={}", id);
        return true;
    }

    /**
     * 测试连接
     */
    public Map<String, Object> testConnection(Long id) {
        DatabaseConfig config = databaseConfigMapper.selectById(id);
        return testConnection(config);
    }

    /**
     * 测试连接（直接传配置）
     */
    public Map<String, Object> testConnection(DatabaseConfig config) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "");
        result.put("duration", 0);

        try {
            long start = System.currentTimeMillis();

            // 编辑态测试：前端不回显密码，密码留空时沿用库中已保存的（密文，下方统一解密）
            if (config.getId() != null
                    && (config.getPassword() == null || config.getPassword().isEmpty())) {
                DatabaseConfig saved = databaseConfigMapper.selectById(config.getId());
                if (saved != null) {
                    config.setPassword(saved.getPassword());
                    if ((config.getUsername() == null || config.getUsername().isBlank())
                            && saved.getUsername() != null) {
                        config.setUsername(saved.getUsername());
                    }
                }
            }

            // 如果是自定义数据库且驱动 JAR 存在，先加载驱动
            if (Boolean.TRUE.equals(config.getIsCustom()) && config.getDriverJarPath() != null) {
                File jarFile = new File(config.getDriverJarPath());
                if (jarFile.exists()) {
                    CustomDriverLoader.loadDriver(config.getDriverJarPath(), config.getDriverClass());
                }
            }

            String error = dataSourceFactory.testConnection(
                    config.getDriverClass(),
                    config.getJdbcUrl(),
                    config.getUsername(),
                    config.getPassword() != null ? CryptoUtil.decrypt(config.getPassword()) : ""
            );

            long duration = System.currentTimeMillis() - start;
            boolean success = (error == null);
            result.put("success", success);
            result.put("duration", duration);
            result.put("message", success ? "连接成功" : "连接失败: " + error);

            if (success) {
                // 推断数据库类型
                String inferredType = SqlDialectAdapter.inferDbTypeFromUrl(config.getJdbcUrl());
                result.put("inferredType", inferredType);
            }
        } catch (Exception e) {
            result.put("message", "连接失败: " + e.getMessage());
            log.error("测试连接失败: {}", e.getMessage(), e);
        }

        return result;
    }

    /**
     * 获取数据库类型列表
     */
    public List<Map<String, String>> getDatabaseTypes() {
        List<Map<String, String>> types = new ArrayList<>();

        // 内置类型：驱动 JAR 全部随应用打包（pom 依赖），前端只需 host/port/库名/参数
        types.add(createDbType("MySQL", "MYSQL", "com.mysql.cj.jdbc.Driver", false,
                "3306", "jdbc:mysql://", "?"));
        types.add(createDbType("PostgreSQL", "POSTGRESQL", "org.postgresql.Driver", false,
                "5432", "jdbc:postgresql://", "?"));
        types.add(createDbType("openGauss", "OPENGAUSS", "org.opengauss.Driver", false,
                "5432", "jdbc:opengauss://", "?"));
        types.add(createDbType("Oracle", "ORACLE", "oracle.jdbc.OracleDriver", false,
                "1521", "jdbc:oracle:thin:@//", "?"));
        types.add(createDbType("达梦 DM", "DM", "dm.jdbc.driver.DmDriver", false,
                "5236", "jdbc:dm://", "?"));
        types.add(createDbType("人大金仓 KingBase", "KINGBASE", "com.kingbase8.Driver", false,
                "54321", "jdbc:kingbase8://", "?"));
        types.add(createDbType("H2 (TCP)", "H2", "org.h2.Driver", false,
                "9092", "jdbc:h2:tcp://", ";"));
        // 仅保留一个自定义入口：驱动不在包内时自行提供 JAR、driver class 和完整 URL
        types.add(createDbType("其他自定义", "CUSTOM", "", true,
                "", "", ""));

        return types;
    }

    private Map<String, String> createDbType(String name, String type, String driver, boolean isCustom,
                                             String defaultPort, String urlPrefix, String paramSep) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("type", type);
        map.put("driverClass", driver);
        map.put("isCustom", String.valueOf(isCustom));
        map.put("defaultPort", defaultPort);
        map.put("urlPrefix", urlPrefix);
        map.put("paramSep", paramSep);
        return map;
    }

    private String getDefaultDriverClass(String dbType) {
        if (dbType == null) return "";
        return switch (dbType.toUpperCase()) {
            case "H2" -> "org.h2.Driver";
            case "MYSQL" -> "com.mysql.cj.jdbc.Driver";
            case "ORACLE" -> "oracle.jdbc.OracleDriver";
            case "POSTGRESQL" -> "org.postgresql.Driver";
            case "OPENGAUSS" -> "org.opengauss.Driver";
            case "DM" -> "dm.jdbc.driver.DmDriver";
            case "KINGBASE" -> "com.kingbase8.Driver";
            default -> "";
        };
    }
}
