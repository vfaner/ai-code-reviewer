package com.qqmu.jargus.config;

import com.qqmu.jargus.datasource.SqlDialectAdapter;
import com.qqmu.jargus.service.AuthService;
import com.qqmu.jargus.service.IgnoreRuleService;
import com.qqmu.jargus.service.IssueMergeService;
import com.qqmu.jargus.service.ReportService;
import com.qqmu.jargus.service.SchemaInitService;
import com.qqmu.jargus.service.SeverityMigrationService;
import com.qqmu.jargus.service.SuggestionCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 应用启动监听器
 * 负责在启动时初始化默认数据库的表结构和默认用户
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppStartupListener {

    private final DataSource dataSource;
    private final SchemaInitService schemaInitService;
    private final AuthService authService;
    private final IgnoreRuleService ignoreRuleService;
    private final IssueMergeService issueMergeService;
    private final SeverityMigrationService severityMigrationService;
    private final ReportService reportService;
    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("应用启动完成，开始检查数据库初始化状态...");

        try {
            // 检查默认数据源中是否已有表结构
            boolean schemaExists = schemaInitService.checkSchemaExists(dataSource);

            if (!schemaExists) {
                log.info("检测到数据库为空，开始初始化表结构...");
                boolean success = schemaInitService.initializeSchema(dataSource, SqlDialectAdapter.H2);
                if (success) {
                    log.info("数据库表结构初始化成功");
                } else {
                    log.error("数据库表结构初始化失败");
                }
            } else {
                String version = schemaInitService.getSchemaVersion(dataSource);
                log.info("数据库表结构已存在，版本: {}", version);
            }

            // 表结构就绪后，刷新依赖数据库的缓存（@PostConstruct 阶段表可能还不存在）
            ignoreRuleService.refreshCache();

            // 检查并创建 sys_user 表（如果不存在）
            ensureUserTable();
            // AI 厂商：max_tokens 新列 + 内置模板协议升级为 OpenAI 兼容 / Anthropic 两种
            migrateProviderSettings();
            // 既有库补种新增检查器配置（architecture / dependency_vuln）
            seedNewCheckers();
            // scan_issue 增补 AI 增强建议列（旧库升级）
            ensureScanIssueColumns();
            // scan_task 增补五级计数列（旧库升级，必须在任何计数回写之前完成）
            ensureScanTaskGradeColumns();
            // 门禁自定义配置表（旧库升级；无行时用 application.yml 默认值）
            ensureGateSettingTable();
            // 邮件管理：发件配置 / 通知收件人两表（旧库升级）
            ensureMailTables();
            // 邮件管理：scan_task / ci_trigger_config 通知字段（旧库升级）
            ensureMailNotifyColumns();
            // 历史扫描问题的修复建议回填（早期检查器未写 suggestion）
            backfillSuggestions();
            // 历史问题合并升级：同文件同规则多点合并为一条，清理 import 误报的重复代码
            issueMergeService.backfill();
            // 三级 BUG/WARNING/INFO → 五级 BLOCKER/CRITICAL/MAJOR/MINOR/INFO 迁移
            severityMigrationService.backfill();
            // 报告磁盘缓存对账：旧版本缓存、AI 深度评审中途预览产生的半成品缓存一律清理
            reportService.purgeStaleReportCaches();
            // 初始化默认用户
            authService.initDefaultUsers();

        } catch (Exception e) {
            log.error("初始化失败: {}", e.getMessage(), e);
        }

        log.info("========================================");
        log.info("  百目 JArgus 启动成功！");
        log.info("  访问地址: http://localhost:8080");
        log.info("========================================");
    }

    /**
     * AI 厂商配置升级：
     * 1. ai_provider_config 增加 max_tokens 列（null=不限制，对应表单里的最大 Token 限制开关）
     * 2. 内置 LLM 模板统一收敛到 OPENAI_COMPATIBLE / ANTHROPIC 两种协议
     */
    private void migrateProviderSettings() {
        try {
            jdbcTemplate.execute("ALTER TABLE ai_provider_config ADD IF NOT EXISTS max_tokens INT");
        } catch (Exception e) {
            log.warn("添加 max_tokens 列失败（可能已存在）: {}", e.getMessage());
        }
        try {
            // CI 触发器：代码平台地址（企业自建 GitHub/GitLab/Gitee）
            jdbcTemplate.execute("ALTER TABLE ci_trigger_config ADD IF NOT EXISTS platform_url VARCHAR(256)");
        } catch (Exception e) {
            log.warn("添加 platform_url 列失败（可能已存在）: {}", e.getMessage());
        }
        try {
            // CI 触发器：私有仓库克隆凭据
            jdbcTemplate.execute("ALTER TABLE ci_trigger_config ADD IF NOT EXISTS repo_username VARCHAR(128)");
            jdbcTemplate.execute("ALTER TABLE ci_trigger_config ADD IF NOT EXISTS repo_token VARCHAR(512)");
        } catch (Exception e) {
            log.warn("添加 repo_username/repo_token 列失败（可能已存在）: {}", e.getMessage());
        }
        try {
            // 协议、地址、鉴权随模板名固定升级；幂等，可反复执行
            jdbcTemplate.update("UPDATE llm_template SET protocol_type='OPENAI_COMPATIBLE', base_url=?, " +
                    "auth_type='BEARER', auth_header_name='Authorization', default_model='ernie-4.0-turbo-8k' " +
                    "WHERE template_name='百度千帆'", "https://qianfan.baidubce.com/v2");
            jdbcTemplate.update("UPDATE llm_template SET protocol_type='OPENAI_COMPATIBLE', base_url=?, " +
                    "auth_type='BEARER', auth_header_name='Authorization', default_model='gemini-2.0-flash' " +
                    "WHERE template_name='Gemini'", "https://generativelanguage.googleapis.com/v1beta/openai");
            jdbcTemplate.update("UPDATE llm_template SET protocol_type='ANTHROPIC', base_url=?, " +
                    "auth_type='API_KEY_HEADER', auth_header_name='x-api-key', default_model='claude-3-5-sonnet-latest' " +
                    "WHERE template_name='Claude'", "https://api.anthropic.com/v1");
            jdbcTemplate.update("UPDATE llm_template SET protocol_type='OPENAI_COMPATIBLE', " +
                    "auth_type='BEARER', auth_header_name='Authorization' WHERE template_name IN ('Ollama','vLLM','LocalAI')");
        } catch (Exception e) {
            log.warn("内置 LLM 模板升级失败: {}", e.getMessage());
        }
    }

    /**
     * 为既有数据库补种新增的内置检查器配置（幂等，H2/MySQL 方言中立）：
     * INSERT ... SELECT ... WHERE NOT EXISTS，仅当 checker_code 不存在时插入
     */
    private void seedNewCheckers() {
        Object[][] seeds = {
                {"architecture", "架构约束检查", "架构",
                        "检查分层架构约束：控制器不得跨层访问DAO、下层不得反向依赖上层、实体不得泄漏到接口层",
                        true, true, 18},
                {"dependency_vuln", "依赖漏洞扫描", "依赖",
                        "解析pom.xml/build.gradle依赖，与内置漏洞库匹配已知CVE（可选OSV在线增强）",
                        true, true, 19}
        };
        String sql = "INSERT INTO checker_config " +
                "(checker_code, checker_name, checker_category, description, is_enabled, is_local, sort_order) " +
                "SELECT ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS " +
                "(SELECT 1 FROM checker_config WHERE checker_code = ?)";
        for (Object[] s : seeds) {
            try {
                int rows = jdbcTemplate.update(sql,
                        s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[0]);
                if (rows > 0) {
                    log.info("补种检查器配置: {}", s[0]);
                }
            } catch (Exception e) {
                log.warn("补种检查器配置失败 {}: {}", s[0], e.getMessage());
            }
        }
    }

    /**
     * 旧库升级：scan_issue 增加 AI 增强建议列（H2/MySQL 方言中立，先查 INFORMATION_SCHEMA）
     */
    private void ensureScanIssueColumns() {
        ensureColumn("SCAN_ISSUE", "AI_SUGGESTION", "CLOB");
        ensureColumn("SCAN_ISSUE", "AI_SUGGESTION_AT", "TIMESTAMP");
        ensureColumn("SCAN_ISSUE", "LINE_POINTS", "CLOB");
        ensureColumn("SCAN_ISSUE", "OCCURRENCE_COUNT", "INT DEFAULT 1");
    }

    /**
     * 旧库升级：scan_task 增加五级严重度计数列（info_count 已存在，语义变为新 INFO）。
     * 旧 bug_count/warning_count 列留库不管，代码不再读写。
     */
    private void ensureScanTaskGradeColumns() {
        ensureColumn("SCAN_TASK", "BLOCKER_COUNT", "INT DEFAULT 0");
        ensureColumn("SCAN_TASK", "CRITICAL_COUNT", "INT DEFAULT 0");
        ensureColumn("SCAN_TASK", "MAJOR_COUNT", "INT DEFAULT 0");
        ensureColumn("SCAN_TASK", "MINOR_COUNT", "INT DEFAULT 0");
    }

    /**
     * 旧库升级：门禁自定义配置表（单行 id=1；无行 = 用 application.yml 的 app.gate.* 默认值）。
     * 列类型方言中立，H2/MySQL 通用。
     */
    private void ensureGateSettingTable() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS gate_setting (
                    id BIGINT PRIMARY KEY,
                    blocker_weight INT NOT NULL,
                    critical_weight INT NOT NULL,
                    major_weight INT NOT NULL,
                    minor_weight INT NOT NULL,
                    info_weight INT NOT NULL,
                    pass_score INT NOT NULL,
                    blocker_limit INT NOT NULL,
                    excellent_score INT NOT NULL,
                    good_score INT NOT NULL,
                    fair_score INT NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
        } catch (Exception e) {
            log.warn("检查/创建 gate_setting 表失败: {}", e.getMessage());
        }
    }

    /**
     * 旧库升级：邮件管理两表（发件配置、通知收件人）。
     * 列类型方言中立，H2/MySQL 通用；同一时间仅一个发件配置启用的约束由服务层保证。
     */
    private void ensureMailTables() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS mail_sender (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(128) NOT NULL,
                    host VARCHAR(256) NOT NULL,
                    port INT NOT NULL DEFAULT 465,
                    username VARCHAR(256),
                    password VARCHAR(512),
                    from_address VARCHAR(256) NOT NULL,
                    from_alias VARCHAR(128),
                    use_starttls BOOLEAN DEFAULT FALSE,
                    use_ssl BOOLEAN DEFAULT TRUE,
                    is_enabled BOOLEAN DEFAULT FALSE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
        } catch (Exception e) {
            log.warn("检查/创建 mail_sender 表失败: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS mail_recipient (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(128) NOT NULL,
                    email VARCHAR(256) NOT NULL UNIQUE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
        } catch (Exception e) {
            log.warn("检查/创建 mail_recipient 表失败: {}", e.getMessage());
        }
    }

    /** 旧库升级：扫描任务与 CI 触发配置的通知发信字段 */
    private void ensureMailNotifyColumns() {
        ensureColumn("SCAN_TASK", "NOTIFY_ENABLED", "BOOLEAN DEFAULT FALSE");
        ensureColumn("SCAN_TASK", "NOTIFY_RECIPIENT_IDS", "VARCHAR(512)");
        ensureColumn("SCAN_TASK", "MAIL_STATUS", "VARCHAR(16)");
        ensureColumn("CI_TRIGGER_CONFIG", "NOTIFY_ENABLED", "BOOLEAN DEFAULT FALSE");
        ensureColumn("CI_TRIGGER_CONFIG", "NOTIFY_RECIPIENT_IDS", "VARCHAR(512)");
    }

    /** INFORMATION_SCHEMA 判列存在，缺失则 ALTER ADD（type 给 H2 语法，MySQL 下 CLOB/TIMESTAMP 均兼容） */
    private void ensureColumn(String table, String column, String type) {
        try {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                    Integer.class, table, column);
            if (cnt == null || cnt == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
                log.info("新增列: {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("检查/新增列失败 {}.{}: {}", table, column, e.getMessage());
        }
    }

    /**
     * 历史问题数据回填修复建议（幂等，H2/MySQL 方言中立）：
     * 早期检查器不写 suggestion，老任务的问题在详情/上下文/报告里看不到修复建议，
     * 启动时按规则码把仍为空的行补上目录默认值，只动空值不覆盖检查器自带建议。
     */
    private void backfillSuggestions() {
        String sql = "UPDATE scan_issue SET suggestion = ? " +
                "WHERE rule_code = ? AND (suggestion IS NULL OR TRIM(suggestion) = '')";
        int total = 0;
        for (Map.Entry<String, String> e : SuggestionCatalog.all().entrySet()) {
            try {
                total += jdbcTemplate.update(sql, e.getValue(), e.getKey());
            } catch (Exception ex) {
                log.warn("回填修复建议失败 {}: {}", e.getKey(), ex.getMessage());
            }
        }
        if (total > 0) {
            log.info("历史问题修复建议回填: {} 行", total);
        }
    }

    /**
     * 确保 sys_user 表存在（旧版本数据库升级用）
     */
    private void ensureUserTable() {
        try {
            // 检查表是否存在
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SYS_USER'",
                    Integer.class
            );
            if (count != null && count == 0) {
                log.info("sys_user 表不存在，开始创建...");
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS sys_user (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(64) NOT NULL UNIQUE,
                        password_hash VARCHAR(256) NOT NULL,
                        nickname VARCHAR(128),
                        role VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
                        source VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
                        is_enabled BOOLEAN DEFAULT TRUE,
                        is_password_default BOOLEAN DEFAULT TRUE,
                        last_login_at TIMESTAMP,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);

                // remote_auth_config 表
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS remote_auth_config (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        config_name VARCHAR(128) NOT NULL,
                        auth_type VARCHAR(32) NOT NULL DEFAULT 'OAUTH2',
                        login_url VARCHAR(512),
                        user_info_url VARCHAR(512),
                        token_url VARCHAR(512),
                        client_id VARCHAR(256),
                        client_secret VARCHAR(512),
                        username_field VARCHAR(64),
                        nickname_field VARCHAR(128),
                        role_field VARCHAR(64),
                        role_mapping TEXT,
                        is_enabled BOOLEAN DEFAULT FALSE,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);

                // sys_oper_log 表
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS sys_oper_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(64),
                        operation VARCHAR(128),
                        method VARCHAR(16),
                        params TEXT,
                        ip VARCHAR(64),
                        status VARCHAR(16),
                        error_msg VARCHAR(512),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);

                log.info("用户相关表创建完成");
            }
        } catch (Exception e) {
            log.warn("检查 sys_user 表失败: {}", e.getMessage());
        }
    }
}
