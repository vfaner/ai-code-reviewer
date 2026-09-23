package com.qqmu.jargus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据库配置实体
 */
@Data
@TableName("database_config")
public class DatabaseConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置名称 */
    private String name;

    /** 数据库类型: H2/MYSQL/ORACLE/POSTGRESQL/DM/KINGBASE/CUSTOM */
    private String dbType;

    /** 驱动类名 */
    private String driverClass;

    /** JDBC URL */
    private String jdbcUrl;

    /** 用户名 */
    private String username;

    /** 密码（AES加密） */
    private String password;

    /** 最大连接数 */
    private Integer maxPoolSize;

    /** 最小空闲连接 */
    private Integer minIdle;

    /** 连接超时(ms) */
    private Integer connectionTimeout;

    /** SQL方言: ORACLE/MYSQL/POSTGRESQL/CUSTOM/H2 */
    private String dialect;

    /** 驱动JAR路径（自定义数据库） */
    private String driverJarPath;

    /** 额外连接属性 JSON */
    private String connectionProperties;

    /** 是否自定义 */
    private Boolean isCustom;

    /** 是否当前启用 */
    private Boolean isActive;

    /** 表结构版本 */
    private String schemaVersion;

    /** 是否已初始化 */
    private Boolean isInitialized;

    /** 排序 */
    private Integer sortOrder;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
