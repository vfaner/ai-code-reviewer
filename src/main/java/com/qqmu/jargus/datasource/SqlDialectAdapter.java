package com.qqmu.jargus.datasource;

import lombok.extern.slf4j.Slf4j;

/**
 * SQL 方言适配器
 * 枚举值与 database_config.db_type 一一对应，每个值归属一个方言家族（{@link Family}）。
 * 分页方言、DDL 脚本、行限制语法等运行时行为按家族选择：
 * MariaDB/TiDB/OceanBase/GBase 8a 走 MySQL 协议；openGauss/KingBase/HighGo/Vastbase 为 PG 系；
 * DM/YashanDB 为 Oracle 系；GBase 8s/Oscar/自定义暂无内置 DDL（仅连接支持）。
 */
@Slf4j
public enum SqlDialectAdapter {

    // ---- MySQL 家族 ----
    MYSQL(Family.MYSQL),
    MARIADB(Family.MYSQL),
    TIDB(Family.MYSQL),
    OCEANBASE(Family.MYSQL),
    GBASE8A(Family.MYSQL),
    // ---- PostgreSQL 家族 ----
    POSTGRESQL(Family.POSTGRESQL),
    OPENGAUSS(Family.POSTGRESQL),
    KINGBASE(Family.POSTGRESQL),   // 人大金仓（PG 兼容，保留旧枚举名兼容存量 dialect 字符串）
    HIGHGO(Family.POSTGRESQL),     // 瀚高
    VASTBASE(Family.POSTGRESQL),   // 海量
    // ---- Oracle 家族 ----
    ORACLE(Family.ORACLE),
    DM(Family.ORACLE),             // 达梦（Oracle 兼容，保留旧枚举名）
    YASHANDB(Family.ORACLE),       // 崖山
    // ---- 其他家族 ----
    SQLSERVER(Family.SQLSERVER),
    DB2(Family.DB2),
    H2(Family.H2),
    // ---- 无内置 DDL（连接测试可用，自动建表需用户手工执行 DDL） ----
    GBASE8S(Family.CUSTOM),        // 南大通用 GBase 8s（Informix 系）
    OSCAR(Family.CUSTOM),          // 神通
    CUSTOM(Family.CUSTOM);

    /**
     * 方言家族：决定分页方言、DDL 脚本与行限制语法
     */
    public enum Family {
        MYSQL, POSTGRESQL, ORACLE, SQLSERVER, DB2, H2, CUSTOM
    }

    private final Family family;

    SqlDialectAdapter(Family family) {
        this.family = family;
    }

    public Family getFamily() {
        return family;
    }

    /**
     * 持久化到 database_config.dialect 的方言标识（与枚举名一致）
     */
    public String getDialect() {
        return name();
    }

    /**
     * 获取对应的 DDL 脚本路径；null 表示无内置脚本（需要用户手工执行 DDL）
     */
    public String getDdlScriptPath() {
        return switch (family) {
            case MYSQL -> "db/schema-mysql.sql";
            case POSTGRESQL -> "db/schema-postgresql.sql";
            case ORACLE -> "db/schema-oracle.sql";
            case SQLSERVER -> "db/schema-sqlserver.sql";
            case DB2 -> "db/schema-db2.sql";
            case H2 -> "db/schema-h2.sql";
            case CUSTOM -> null;
        };
    }

    /**
     * 是否有内置 DDL 脚本（无脚本的类型不支持自动建表）
     */
    public boolean hasBuiltinDdl() {
        return getDdlScriptPath() != null;
    }

    /**
     * 根据数据库类型（database_config.db_type）推断方言。
     * null 视为内置 H2；未知类型归入 CUSTOM，不再静默按 H2 处理。
     */
    public static SqlDialectAdapter fromDbType(String dbType) {
        if (dbType == null) {
            return H2;
        }
        return switch (dbType.trim().toUpperCase()) {
            case "MYSQL" -> MYSQL;
            case "MARIADB" -> MARIADB;
            case "TIDB" -> TIDB;
            case "OCEANBASE" -> OCEANBASE;
            case "GBASE8A" -> GBASE8A;
            case "POSTGRESQL", "PG" -> POSTGRESQL;
            case "OPENGAUSS" -> OPENGAUSS;
            case "KINGBASE", "KINGBASE8" -> KINGBASE;
            case "HIGHGO" -> HIGHGO;
            case "VASTBASE" -> VASTBASE;
            case "ORACLE" -> ORACLE;
            case "DM" -> DM;
            case "YASHANDB" -> YASHANDB;
            case "SQLSERVER", "SQL SERVER", "MSSQL" -> SQLSERVER;
            case "DB2" -> DB2;
            case "H2" -> H2;
            case "GBASE8S" -> GBASE8S;
            case "OSCAR" -> OSCAR;
            case "CUSTOM" -> CUSTOM;
            default -> CUSTOM;
        };
    }

    /**
     * 安全解析存量 dialect 字符串：先按枚举名解析（兼容旧值 MYSQL/DM/KINGBASE 等），
     * 失败时回退按 dbType 推断，仍未知则归入 CUSTOM。
     */
    public static SqlDialectAdapter resolve(String dialect, String dbType) {
        if (dialect != null && !dialect.isBlank()) {
            try {
                return valueOf(dialect.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("未知方言标识: {}，回退按数据库类型 {} 推断", dialect, dbType);
            }
        }
        return fromDbType(dbType);
    }

    /**
     * 从 JDBC URL 推断数据库类型。
     * TiDB / GBase 8a 走 MySQL 协议（jdbc:mysql:），统一归为 MYSQL。
     */
    public static String inferDbTypeFromUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "UNKNOWN";
        }
        String lower = jdbcUrl.toLowerCase();
        if (lower.startsWith("jdbc:h2:")) return "H2";
        if (lower.startsWith("jdbc:mysql:")) return "MYSQL";
        if (lower.startsWith("jdbc:mariadb:")) return "MARIADB";
        if (lower.startsWith("jdbc:oracle:")) return "ORACLE";
        if (lower.startsWith("jdbc:postgresql:")) return "POSTGRESQL";
        if (lower.startsWith("jdbc:opengauss:")) return "OPENGAUSS";
        if (lower.startsWith("jdbc:kingbase8:")) return "KINGBASE";
        if (lower.startsWith("jdbc:highgo:")) return "HIGHGO";
        if (lower.startsWith("jdbc:vastbase:")) return "VASTBASE";
        if (lower.startsWith("jdbc:dm:")) return "DM";
        if (lower.startsWith("jdbc:yasdb:")) return "YASHANDB";
        if (lower.startsWith("jdbc:sqlserver:")) return "SQLSERVER";
        if (lower.startsWith("jdbc:db2:")) return "DB2";
        if (lower.startsWith("jdbc:oceanbase:")) return "OCEANBASE";
        if (lower.startsWith("jdbc:gbasedbt-sqli:") || lower.startsWith("jdbc:gbasedbt:")) return "GBASE8S";
        if (lower.startsWith("jdbc:oscar:")) return "OSCAR";
        return "CUSTOM";
    }
}
