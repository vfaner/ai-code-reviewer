package com.qqmu.jargus.datasource;

import lombok.extern.slf4j.Slf4j;

/**
 * SQL 方言适配器
 * 根据数据库类型选择合适的 DDL 和 SQL 语句
 */
@Slf4j
public enum SqlDialectAdapter {

    MYSQL("MYSQL"),
    ORACLE("ORACLE"),
    POSTGRESQL("POSTGRESQL"),
    H2("H2"),
    DM("DM"),        // 达梦（ORACLE 兼容）
    KINGBASE("KINGBASE"), // 人大金仓（PG 兼容）
    CUSTOM("CUSTOM");

    private final String dialect;

    SqlDialectAdapter(String dialect) {
        this.dialect = dialect;
    }

    public String getDialect() {
        return dialect;
    }

    /**
     * 获取对应的 DDL 脚本路径
     */
    public String getDdlScriptPath() {
        return switch (this) {
            case MYSQL -> "db/schema-mysql.sql";
            case ORACLE, DM -> "db/schema-oracle.sql";
            case POSTGRESQL, KINGBASE -> "db/schema-postgresql.sql";
            case H2 -> "db/schema-h2.sql";
            case CUSTOM -> null; // 自定义需要用户提供
        };
    }

    /**
     * 根据数据库类型推断方言
     */
    public static SqlDialectAdapter fromDbType(String dbType) {
        if (dbType == null) {
            return H2;
        }
        return switch (dbType.toUpperCase()) {
            case "MYSQL" -> MYSQL;
            case "ORACLE" -> ORACLE;
            case "POSTGRESQL", "PG", "OPENGAUSS" -> POSTGRESQL;
            case "H2" -> H2;
            case "DM" -> DM;
            case "KINGBASE", "KINGBASE8" -> KINGBASE;
            case "CUSTOM" -> CUSTOM;
            default -> H2;
        };
    }

    /**
     * 从 JDBC URL 推断数据库类型
     */
    public static String inferDbTypeFromUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "UNKNOWN";
        }
        String lower = jdbcUrl.toLowerCase();
        if (lower.startsWith("jdbc:h2:")) return "H2";
        if (lower.startsWith("jdbc:mysql:")) return "MYSQL";
        if (lower.startsWith("jdbc:oracle:")) return "ORACLE";
        if (lower.startsWith("jdbc:postgresql:")) return "POSTGRESQL";
        if (lower.startsWith("jdbc:opengauss:")) return "OPENGAUSS";
        if (lower.startsWith("jdbc:dm:")) return "DM";
        if (lower.startsWith("jdbc:kingbase8:")) return "KINGBASE";
        return "CUSTOM";
    }
}
