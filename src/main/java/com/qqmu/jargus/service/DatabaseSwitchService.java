package com.qqmu.jargus.service;

import com.qqmu.jargus.datasource.CustomDriverLoader;
import com.qqmu.jargus.datasource.DataSourceContextHolder;
import com.qqmu.jargus.datasource.DataSourceFactory;
import com.qqmu.jargus.datasource.DynamicDataSource;
import com.qqmu.jargus.datasource.SqlDialectAdapter;
import com.qqmu.jargus.entity.DatabaseConfig;
import com.qqmu.jargus.mapper.DatabaseConfigMapper;
import com.qqmu.jargus.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据库切换服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseSwitchService {

    private final DatabaseConfigMapper databaseConfigMapper;
    private final DynamicDataSource dynamicDataSource;
    private final DataSourceFactory dataSourceFactory;
    private final SchemaInitService schemaInitService;

    /**
     * 检查目标数据库状态（在切换前调用）
     */
    public Map<String, Object> checkTargetDatabase(Long targetId) {
        Map<String, Object> result = new HashMap<>();
        result.put("canSwitch", false);
        result.put("status", "UNKNOWN");
        result.put("message", "");
        result.put("schemaVersion", null);

        DatabaseConfig target = databaseConfigMapper.selectById(targetId);
        if (target == null) {
            result.put("message", "目标数据库配置不存在");
            return result;
        }

        try {
            // 只要配置了驱动 JAR 且文件存在就先加载（自定义库、或驱动未内置的类型如神通 Oscar）
            loadDriverJarIfNeeded(target);

            // 创建临时数据源测试
            DataSource tempDs = dataSourceFactory.createDataSource(
                    target.getDriverClass(),
                    target.getJdbcUrl(),
                    target.getUsername(),
                    CryptoUtil.decrypt(target.getPassword()),
                    "temp-check-" + targetId,
                    1,
                    0,
                    10000
            );

            // 检查表结构
            boolean schemaExists = schemaInitService.checkSchemaExists(tempDs);

            if (!schemaExists) {
                SqlDialectAdapter dialect = SqlDialectAdapter.resolve(target.getDialect(), target.getDbType());
                result.put("status", "NO_SCHEMA");
                result.put("needsInit", true);
                result.put("dialect", dialect.getDialect());
                if (dialect.hasBuiltinDdl()) {
                    result.put("canSwitch", true);
                    result.put("message", "目标数据库中未检测到系统表，切换时将自动初始化表结构");
                } else {
                    result.put("canSwitch", false);
                    result.put("message", "目标数据库中未检测到系统表，且该类型暂不支持自动建表，请手工执行 DDL 后重试");
                }
            } else {
                String version = schemaInitService.getSchemaVersion(tempDs);
                result.put("schemaVersion", version);

                if ("1.0.0".equals(version)) {
                    result.put("status", "SCHEMA_OK");
                    result.put("canSwitch", true);
                    result.put("needsInit", false);
                    result.put("message", "检测到已有表结构（版本 " + version + "），可直接切换");
                } else {
                    result.put("status", "SCHEMA_VERSION_MISMATCH");
                    result.put("canSwitch", false);
                    result.put("needsMigration", true);
                    result.put("message", "表结构版本不一致（当前版本: " + version + "，系统版本: 1.0.0），需要迁移");
                }
            }
        } catch (Exception e) {
            result.put("status", "CONNECTION_FAILED");
            result.put("message", "连接失败: " + e.getMessage());
            log.error("检查目标数据库失败: {}", e.getMessage(), e);
        }

        return result;
    }

    /**
     * 切换到目标数据库
     */
    @Transactional
    public Map<String, Object> switchDatabase(Long targetId, boolean initIfNeeded) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "");

        DatabaseConfig target = databaseConfigMapper.selectById(targetId);
        if (target == null) {
            result.put("message", "目标数据库配置不存在");
            return result;
        }

        try {
            // 只要配置了驱动 JAR 且文件存在就先加载（自定义库、或驱动未内置的类型如神通 Oscar）
            loadDriverJarIfNeeded(target);

            // 创建新数据源
            DataSource newDataSource = dataSourceFactory.createDataSource(
                    target.getDriverClass(),
                    target.getJdbcUrl(),
                    target.getUsername(),
                    CryptoUtil.decrypt(target.getPassword()),
                    "db-pool-" + targetId,
                    target.getMaxPoolSize() != null ? target.getMaxPoolSize() : 10,
                    target.getMinIdle() != null ? target.getMinIdle() : 5,
                    target.getConnectionTimeout() != null ? target.getConnectionTimeout() : 30000
            );

            // 检查表结构
            boolean schemaExists = schemaInitService.checkSchemaExists(newDataSource);
            if (!schemaExists) {
                if (!initIfNeeded) {
                    result.put("message", "目标数据库未初始化，请确认后重试");
                    return result;
                }
                // 初始化表结构（dialect 存量值安全解析，未知回退 dbType 推断）
                SqlDialectAdapter dialect = SqlDialectAdapter.resolve(target.getDialect(), target.getDbType());
                if (!dialect.hasBuiltinDdl()) {
                    result.put("message", "该数据库类型暂不支持自动建表，请手工执行 DDL 后重试");
                    return result;
                }
                boolean initSuccess = schemaInitService.initializeSchema(newDataSource, dialect);
                if (!initSuccess) {
                    result.put("message", "表结构初始化失败");
                    return result;
                }
                target.setIsInitialized(true);
                target.setSchemaVersion("1.0.0");
            }

            // 添加到动态数据源
            String dataSourceKey = "db_" + targetId;
            dynamicDataSource.addDataSource(dataSourceKey, newDataSource);

            // 切换到新数据源（用于更新配置表）
            DataSourceContextHolder.setDataSourceKey(dataSourceKey);

            try {
                // 禁用所有其他数据库的 is_active
                databaseConfigMapper.update(null,
                        new UpdateWrapper<DatabaseConfig>().set("is_active", false)
                );

                // 激活目标数据库
                target.setIsActive(true);
                databaseConfigMapper.updateById(target);
            } finally {
                DataSourceContextHolder.clearDataSourceKey();
            }

            result.put("success", true);
            result.put("message", "切换成功");
            result.put("activeDbName", target.getName());
            log.info("数据库切换成功: id={}, name={}", targetId, target.getName());
        } catch (Exception e) {
            result.put("message", "切换失败: " + e.getMessage());
            log.error("数据库切换失败: {}", e.getMessage(), e);
        }

        return result;
    }

    /**
     * 配置了驱动 JAR 路径且文件存在时加载驱动（自定义库、或驱动未内置的类型如神通 Oscar）
     */
    private void loadDriverJarIfNeeded(DatabaseConfig target) throws Exception {
        if (target.getDriverJarPath() != null && !target.getDriverJarPath().isBlank()) {
            File jarFile = new File(target.getDriverJarPath());
            if (jarFile.exists()) {
                CustomDriverLoader.loadDriver(target.getDriverJarPath(), target.getDriverClass());
            }
        }
    }
}
