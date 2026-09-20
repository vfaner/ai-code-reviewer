package com.aicodereview.checker;

/**
 * 问题严重度分级（对标 SonarQube 五级模型，直接驱动质量评分与门禁）
 */
public enum IssueLevel {
    /** Blocker - 可能严重危害应用安全或功能，需立即修复 */
    BLOCKER("BLOCKER", "阻断", 25),
    /** Critical - 对应用有严重影响，需尽快修复 */
    CRITICAL("CRITICAL", "严重", 15),
    /** Major - 对应用有重大影响 */
    MAJOR("MAJOR", "主要", 5),
    /** Minor - 影响较小，但建议修复 */
    MINOR("MINOR", "次要", 1),
    /** Info - 纯信息提示，不影响评分与门禁 */
    INFO("INFO", "提示", 0);

    private final String code;
    private final String label;
    /** 默认扣分权重（实际权重以 app.gate.* 配置为准） */
    private final int defaultWeight;

    IssueLevel(String code, String label, int defaultWeight) {
        this.code = code;
        this.label = label;
        this.defaultWeight = defaultWeight;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
    public int getDefaultWeight() { return defaultWeight; }

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
