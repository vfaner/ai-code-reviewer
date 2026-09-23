package com.qqmu.jargus.service;

import java.util.Map;

/**
 * 技术债目录
 *
 * 为每个规则编码定义「标准修复时间」（分钟），参照 SonarQube SQALE 思路：
 * 扫描任务的技术债 = 所有未忽略问题的标准修复时间之和。
 *
 * 匹配顺序：精确编码 → 前缀（DEP_VULN_/SEC_/ARCH_/CONC_/STYLE_/NAMING_/AI_）→ 默认 10 分钟。
 */
public final class TechnicalDebtCatalog {

    /** 未命中任何规则时的默认修复时间（分钟） */
    private static final int DEFAULT_MINUTES = 10;

    private static final Map<String, Integer> MINUTES = Map.ofEntries(
            // ---- 既有本地规则 ----
            Map.entry("NULL_CHECK", 15),
            Map.entry("NULL_CHAIN_CALL", 20),
            Map.entry("RESOURCE_LEAK", 20),
            Map.entry("EMPTY_CATCH", 10),
            Map.entry("MAX_COMPLEXITY", 30),
            Map.entry("DEPRECATED_CLASS", 15),
            Map.entry("DEPRECATED_METHOD_DECL", 15),
            Map.entry("DEPRECATED_METHOD_CALL", 15),
            Map.entry("UNUSED_METHOD", 20),
            Map.entry("MAGIC_NUMBER", 5),
            Map.entry("SYSTEM_OUT", 10),
            // ---- 安全（SAST）----
            Map.entry("SEC_HARDCODED_SECRET", 30),
            Map.entry("SEC_SQL_INJECTION", 60),
            Map.entry("SEC_COMMAND_INJECTION", 60),
            Map.entry("SEC_INSECURE_DESERIALIZATION", 45),
            Map.entry("SEC_WEAK_CRYPTO", 30),
            Map.entry("SEC_INSECURE_RANDOM", 20),
            Map.entry("SEC_PATH_TRAVERSAL", 30),
            Map.entry("SEC_XXE", 20),
            Map.entry("SEC_SSRF", 30),
            // ---- 架构 ----
            Map.entry("ARCH_LAYER_SKIP", 20),
            Map.entry("ARCH_LAYER_REVERSE", 20),
            Map.entry("ARCH_ENTITY_LEAK", 20),
            // ---- 并发 ----
            Map.entry("CONC_SHARED_MUTABLE_STATE", 20),
            Map.entry("CONC_STATIC_SDF", 15),
            Map.entry("CONC_DCL_WITHOUT_VOLATILE", 20),
            // ---- 重复代码 ----
            Map.entry("DUP_CODE_BLOCK", 20),
            // ---- 风格补充 ----
            Map.entry("STYLE_WILDCARD_IMPORT", 5),
            Map.entry("STYLE_LONG_LINE", 5),
            Map.entry("STYLE_TODO_COMMENT", 5),
            // ---- AI 评审 ----
            Map.entry("AI_REVIEW_SUGGESTION", 15)
    );

    /** 前缀 → 默认修复时间（精确编码未命中时使用） */
    private static final Map<String, Integer> PREFIX_MINUTES = Map.of(
            "DEP_VULN_", 60,
            "SEC_", 30,
            "ARCH_", 20,
            "CONC_", 20,
            "STYLE_", 5,
            "NAMING_", 5,
            "AI_", 15
    );

    private TechnicalDebtCatalog() {
    }

    /**
     * 指定规则编码的标准修复时间（分钟）
     */
    public static int minutesFor(String ruleCode) {
        if (ruleCode == null || ruleCode.isBlank()) {
            return DEFAULT_MINUTES;
        }
        Integer exact = MINUTES.get(ruleCode);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Integer> e : PREFIX_MINUTES.entrySet()) {
            if (ruleCode.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return DEFAULT_MINUTES;
    }

    /**
     * 分钟数格式化为可读文本：45 → "45分"；205 → "3小时25分"；2900 → "2天0小时"
     */
    public static String format(long minutes) {
        if (minutes <= 0) {
            return "0分";
        }
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天");
            if (hours > 0) {
                sb.append(hours).append("小时");
            }
        } else if (hours > 0) {
            sb.append(hours).append("小时");
            if (mins > 0) {
                sb.append(mins).append("分");
            }
        } else {
            sb.append(mins).append("分");
        }
        return sb.toString();
    }
}
