package com.qqmu.jargus.service;

import com.qqmu.jargus.datasource.CustomDriverLoader;
import com.qqmu.jargus.datasource.DataSourceFactory;
import com.qqmu.jargus.datasource.SqlDialectAdapter;
import com.qqmu.jargus.entity.DatabaseConfig;
import com.qqmu.jargus.mapper.DatabaseConfigMapper;
import com.qqmu.jargus.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        if (config == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "数据源不存在: id=" + id);
            return result;
        }
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

            // 只要配置了驱动 JAR 且文件存在就先加载（自定义库、或驱动未内置的类型如神通 Oscar）
            if (config.getDriverJarPath() != null && !config.getDriverJarPath().isBlank()) {
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
     * 获取数据库类型列表。
     * 内置类型：驱动 JAR 随应用打包（pom 依赖），前端只需 host/port/库名/参数；
     * 神通 Oscar 中央仓库无驱动，需上传 JAR（jarRequired=true）；
     * TiDB / GBase 8a 走 MySQL 协议，复用 mysql-connector-j；
     * URL 形态特殊的类型（Oracle/SQL Server/DB2/GBase 8s）用 urlTemplate 描述
     * （{host}/{port}/{database}/{params} 占位），前端按模板拼接与反解。
     */
    public List<Map<String, String>> getDatabaseTypes() {
        List<Map<String, String>> types = new ArrayList<>();

        // ---- MySQL 家族 ----
        types.add(createDbType("MySQL", "MYSQL", "com.mysql.cj.jdbc.Driver", false,
                "3306", "jdbc:mysql://", "?"));
        types.add(createDbType("MariaDB", "MARIADB", "org.mariadb.jdbc.Driver", false,
                "3306", "jdbc:mariadb://", "?"));
        types.add(createDbType("TiDB", "TIDB", "com.mysql.cj.jdbc.Driver", false,
                "4000", "jdbc:mysql://", "?"));
        types.add(createDbType("OceanBase", "OCEANBASE", "com.oceanbase.jdbc.Driver", false,
                "2881", "jdbc:oceanbase://", "?"));
        types.add(createDbType("GBase 8a", "GBASE8A", "com.mysql.cj.jdbc.Driver", false,
                "5258", "jdbc:mysql://", "?"));
        // ---- PostgreSQL 家族 ----
        types.add(createDbType("PostgreSQL", "POSTGRESQL", "org.postgresql.Driver", false,
                "5432", "jdbc:postgresql://", "?"));
        types.add(createDbType("openGauss", "OPENGAUSS", "org.opengauss.Driver", false,
                "5432", "jdbc:opengauss://", "?"));
        types.add(createDbType("人大金仓 KingBase", "KINGBASE", "com.kingbase8.Driver", false,
                "54321", "jdbc:kingbase8://", "?"));
        types.add(createDbType("瀚高 HighGo", "HIGHGO", "com.highgo.jdbc.Driver", false,
                "5866", "jdbc:highgo://", "?"));
        types.add(createDbType("海量 Vastbase", "VASTBASE", "cn.com.vastbase.Driver", false,
                "5432", "jdbc:vastbase://", "?"));
        // ---- Oracle 家族 ----
        types.add(createDbType("Oracle", "ORACLE", "oracle.jdbc.OracleDriver", false,
                "1521", "jdbc:oracle:thin:@//", "?")
                .withTemplate("jdbc:oracle:thin:@//{host}:{port}/{database}"));
        types.add(createDbType("达梦 DM", "DM", "dm.jdbc.driver.DmDriver", false,
                "5236", "jdbc:dm://", "?"));
        types.add(createDbType("崖山 YashanDB", "YASHANDB", "com.yashandb.jdbc.Driver", false,
                "1688", "jdbc:yasdb://", "?"));
        // ---- 其他内置 ----
        types.add(createDbType("SQL Server", "SQLSERVER", "com.microsoft.sqlserver.jdbc.SQLServerDriver", false,
                "1433", "jdbc:sqlserver://", ";")
                .withTemplate("jdbc:sqlserver://{host}:{port};databaseName={database};{params}")
                .withParamsHint("encrypt=false;trustServerCertificate=true"));
        types.add(createDbType("DB2", "DB2", "com.ibm.db2.jcc.DB2Driver", false,
                "50000", "jdbc:db2://", ":")
                .withTemplate("jdbc:db2://{host}:{port}/{database}:{params}"));
        types.add(createDbType("GBase 8s", "GBASE8S", "com.gbasedbt.jdbc.Driver", false,
                "9088", "jdbc:gbasedbt-sqli://", ":")
                .withTemplate("jdbc:gbasedbt-sqli://{host}:{port}/{database}:{params}")
                .withParamsHint("GBASEDBTSERVER=gbase01"));
        types.add(createDbType("H2 (TCP)", "H2", "org.h2.Driver", false,
                "9092", "jdbc:h2:tcp://", ";"));
        // ---- 驱动未内置：需上传 JAR ----
        types.add(createDbType("神通 Oscar", "OSCAR", "com.oscar.Driver", false,
                "2003", "jdbc:oscar://", "?").withJarRequired());
        // 仅保留一个自定义入口：驱动不在包内时自行提供 JAR、driver class 和完整 URL
        types.add(createDbType("其他自定义", "CUSTOM", "", true,
                "", "", ""));

        return types;
    }

    private DbTypeBuilder createDbType(String name, String type, String driver, boolean isCustom,
                                       String defaultPort, String urlPrefix, String paramSep) {
        return new DbTypeBuilder(name, type, driver, isCustom, defaultPort, urlPrefix, paramSep);
    }

    /**
     * 数据库类型描述构造器（最终产出 LinkedHashMap 供前端 JSON 使用）
     */
    private static class DbTypeBuilder extends LinkedHashMap<String, String> {
        DbTypeBuilder(String name, String type, String driver, boolean isCustom,
                      String defaultPort, String urlPrefix, String paramSep) {
            put("name", name);
            put("type", type);
            put("driverClass", driver);
            put("isCustom", String.valueOf(isCustom));
            put("defaultPort", defaultPort);
            put("urlPrefix", urlPrefix);
            put("paramSep", paramSep);
        }

        /** URL 模板（{host}/{port}/{database}/{params} 占位） */
        DbTypeBuilder withTemplate(String template) {
            put("urlTemplate", template);
            return this;
        }

        /** 该类型必须提供驱动 JAR（驱动未随应用打包） */
        DbTypeBuilder withJarRequired() {
            put("jarRequired", "true");
            return this;
        }

        /** 连接参数示例（前端作为参数输入框 placeholder 展示） */
        DbTypeBuilder withParamsHint(String hint) {
            put("paramsHint", hint);
            return this;
        }
    }

    private String getDefaultDriverClass(String dbType) {
        if (dbType == null) return "";
        return switch (dbType.toUpperCase()) {
            case "H2" -> "org.h2.Driver";
            case "MYSQL", "TIDB", "GBASE8A" -> "com.mysql.cj.jdbc.Driver";
            case "MARIADB" -> "org.mariadb.jdbc.Driver";
            case "OCEANBASE" -> "com.oceanbase.jdbc.Driver";
            case "ORACLE" -> "oracle.jdbc.OracleDriver";
            case "POSTGRESQL" -> "org.postgresql.Driver";
            case "OPENGAUSS" -> "org.opengauss.Driver";
            case "DM" -> "dm.jdbc.driver.DmDriver";
            case "KINGBASE" -> "com.kingbase8.Driver";
            case "HIGHGO" -> "com.highgo.jdbc.Driver";
            case "VASTBASE" -> "cn.com.vastbase.Driver";
            case "YASHANDB" -> "com.yashandb.jdbc.Driver";
            case "SQLSERVER" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "DB2" -> "com.ibm.db2.jcc.DB2Driver";
            case "GBASE8S" -> "com.gbasedbt.jdbc.Driver";
            case "OSCAR" -> "com.oscar.Driver";
            default -> "";
        };
    }
}
