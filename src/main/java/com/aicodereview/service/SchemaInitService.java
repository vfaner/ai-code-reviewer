package com.aicodereview.service;

import com.aicodereview.datasource.SqlDialectAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库表结构初始化服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaInitService {

    /**
     * 检查目标数据库中是否存在系统表
     */
    public boolean checkSchemaExists(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // 检查 schema_version 表是否存在
            ResultSet rs = conn.getMetaData().getTables(null, null, "SCHEMA_VERSION", new String[]{"TABLE"});
            boolean exists = rs.next();
            rs.close();
            return exists;
        } catch (SQLException e) {
            log.warn("检查表结构失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取目标数据库的 schema 版本
     */
    public String getSchemaVersion(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version ORDER BY id DESC LIMIT 1")) {
            if (rs.next()) {
                return rs.getString("version");
            }
        } catch (SQLException e) {
            log.warn("获取 schema 版本失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 在目标数据源初始化表结构
     */
    public boolean initializeSchema(DataSource dataSource, SqlDialectAdapter dialect) {
        String scriptPath = dialect.getDdlScriptPath();
        if (scriptPath == null) {
            log.error("方言 {} 没有内置 DDL 脚本，需要用户自定义", dialect);
            return false;
        }

        try {
            ClassPathResource resource = new ClassPathResource(scriptPath);
            if (!resource.exists()) {
                log.error("DDL 脚本不存在: {}", scriptPath);
                return false;
            }

            try (Connection conn = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(conn, resource);
                log.info("表结构初始化成功: {}", scriptPath);
                return true;
            }
        } catch (Exception e) {
            log.error("表结构初始化失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
