package com.aicodereview.service;

import com.aicodereview.checker.IssueLevel;

import java.util.Map;

/**
 * 规则码 → 五级严重度目录（对标 SonarQube 定级惯例）。
 * 检查器产出问题时直接传入级别；本目录用于：
 * 1. 历史三级数据启动迁移；2. AI 等动态规则的兜底定级；3. 持久化时的防御性归一。
 * DEP_VULN_* 按 CVE severity 由 DependencyVulnChecker 动态定级，静态表仅给前缀兜底。
 */
public final class SeverityCatalog {

    private SeverityCatalog() {}

    private static final Map<String, IssueLevel> GRADES = Map.ofEntries(
            // ── 阻断 BLOCKER：注入 / RCE / 硬编码密钥 / 不安全反序列化 ──
            Map.entry("SEC_SQL_INJECTION", IssueLevel.BLOCKER),
            Map.entry("SEC_COMMAND_INJECTION", IssueLevel.BLOCKER),
            Map.entry("SEC_INSECURE_DESERIALIZATION", IssueLevel.BLOCKER),
            Map.entry("SEC_HARDCODED_SECRET", IssueLevel.BLOCKER),
            // ── 严重 CRITICAL：空指针 / 资源泄漏 / 高危安全项 ──
            Map.entry("NULL_CHECK", IssueLevel.CRITICAL),
            Map.entry("RESOURCE_LEAK", IssueLevel.CRITICAL),
            Map.entry("SEC_WEAK_CRYPTO", IssueLevel.CRITICAL),
            Map.entry("SEC_XXE", IssueLevel.CRITICAL),
            Map.entry("SEC_PATH_TRAVERSAL", IssueLevel.CRITICAL),
            Map.entry("SEC_SSRF", IssueLevel.CRITICAL),
            Map.entry("SEC_INSECURE_RANDOM", IssueLevel.CRITICAL),
            // ── 主要 MAJOR：吞异常 / 链式空指针 / 复杂度 / 死代码 / 重复 / 架构并发 ──
            Map.entry("EMPTY_CATCH", IssueLevel.MAJOR),
            Map.entry("NULL_CHAIN_CALL", IssueLevel.MAJOR),
            Map.entry("MAX_COMPLEXITY", IssueLevel.MAJOR),
            Map.entry("UNUSED_METHOD", IssueLevel.MAJOR),
            Map.entry("DUP_CODE_BLOCK", IssueLevel.MAJOR),
            Map.entry("ARCH_LAYER_SKIP", IssueLevel.MAJOR),
            Map.entry("ARCH_LAYER_REVERSE", IssueLevel.MAJOR),
            Map.entry("ARCH_ENTITY_LEAK", IssueLevel.MAJOR),
            Map.entry("CONC_SHARED_MUTABLE_STATE", IssueLevel.MAJOR),
            Map.entry("CONC_STATIC_SDF", IssueLevel.MAJOR),
            Map.entry("CONC_DCL_WITHOUT_VOLATILE", IssueLevel.MAJOR),
            Map.entry("SYSTEM_OUT", IssueLevel.MAJOR),
            Map.entry("NAMING_CLASS", IssueLevel.MAJOR),
            // ── 次要 MINOR：风格 / 魔法数 / 废弃 API / 普通命名 ──
            Map.entry("MAGIC_NUMBER", IssueLevel.MINOR),
            Map.entry("STYLE_LONG_LINE", IssueLevel.MINOR),
            Map.entry("DEPRECATED_CLASS", IssueLevel.MINOR),
            Map.entry("DEPRECATED_METHOD_DECL", IssueLevel.MINOR),
            Map.entry("DEPRECATED_METHOD_CALL", IssueLevel.MINOR),
            Map.entry("NAMING_METHOD", IssueLevel.MINOR),
            Map.entry("NAMING_FIELD", IssueLevel.MINOR),
            // ── 提示 INFO：纯信息，不扣分 ──
            // 通配符导入属纯风格提示（R29 由 MINOR 降级；精确条目必须保留，否则落回 STYLE_→MINOR 前缀兜底）
            Map.entry("STYLE_WILDCARD_IMPORT", IssueLevel.INFO),
            Map.entry("STYLE_TODO_COMMENT", IssueLevel.INFO),
            Map.entry("NAMING_CONSTANT", IssueLevel.INFO),
            Map.entry("NAMING_VARIABLE", IssueLevel.INFO),
            Map.entry("AI_REVIEW_SUGGESTION", IssueLevel.INFO)
    );

    /** 前缀兜底：键长降序匹配 */
    private static final Map<String, IssueLevel> PREFIX_GRADES = Map.of(
            "DEP_VULN_", IssueLevel.CRITICAL,
            "SEC_", IssueLevel.CRITICAL,
            "ARCH_", IssueLevel.MAJOR,
            "CONC_", IssueLevel.MAJOR,
            "STYLE_", IssueLevel.MINOR,
            "NAMING_", IssueLevel.MINOR,
            "AI_", IssueLevel.INFO
    );

    /**
     * 精确表查询：ruleCode 无精确条目时返回 null（不走前缀兜底）。
     * 供启动迁移做"目录重对齐"——DEP_VULN_* 等动态定级规则没有精确条目，天然不会被误对齐。
     */
    public static IssueLevel exactGrade(String ruleCode) {
        if (ruleCode == null || ruleCode.isBlank()) {
            return null;
        }
        return GRADES.get(ruleCode);
    }

    public static IssueLevel gradeFor(String ruleCode, IssueLevel fallback) {
        if (ruleCode == null || ruleCode.isBlank()) {
            return fallback != null ? fallback : IssueLevel.INFO;
        }
        IssueLevel exact = exactGrade(ruleCode);
        if (exact != null) {
            return exact;
        }
        return PREFIX_GRADES.entrySet().stream()
                .filter(e -> ruleCode.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(fallback != null ? fallback : IssueLevel.INFO);
    }

    /** 严重度秩：BLOCKER=5 … INFO=1（写入 scan_issue.severity） */
    public static int rank(IssueLevel level) {
        return level == null ? 1 : switch (level) {
            case BLOCKER -> 5;
            case CRITICAL -> 4;
            case MAJOR -> 3;
            case MINOR -> 2;
            case INFO -> 1;
        };
    }
}
