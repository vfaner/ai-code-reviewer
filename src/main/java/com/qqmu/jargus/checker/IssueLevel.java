package com.qqmu.jargus.checker;

/**
 * 问题严重度分级（对标 SonarQube 五级模型，直接驱动质量评分与门禁）。
 * 扣分权重不在枚举内维护，统一以 app.gate.* 配置为准（QualityGateService 读取）。
 */
public enum IssueLevel {
    /** Blocker - 可能严重危害应用安全或功能，需立即修复 */
    BLOCKER("BLOCKER"),
    /** Critical - 对应用有严重影响，需尽快修复 */
    CRITICAL("CRITICAL"),
    /** Major - 对应用有重大影响 */
    MAJOR("MAJOR"),
    /** Minor - 影响较小，但建议修复 */
    MINOR("MINOR"),
    /** Info - 纯信息提示，不影响评分与门禁 */
    INFO("INFO");

    private final String code;

    IssueLevel(String code) {
        this.code = code;
    }

    public String getCode() { return code; }

    /**
     * 兼容历史三级码：BUG→CRITICAL、WARNING→MAJOR、INFO(旧)→MINOR。
     * 注意旧 INFO 与新 INFO 同码不同义，历史数据迁移以规则码映射为准，此处仅为防御性兜底。
     */
    public static IssueLevel fromCode(String code) {
        if (code == null) return INFO;
        String upper = code.trim().toUpperCase();
        return switch (upper) {
            case "BLOCKER" -> BLOCKER;
            case "CRITICAL", "BUG" -> CRITICAL;
            case "MAJOR", "WARNING" -> MAJOR;
            case "MINOR" -> MINOR;
            case "INFO" -> INFO;
            default -> INFO;
        };
    }
}
