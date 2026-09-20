package com.aicodereview.checker;

/**
 * 检查器类型枚举
 */
public enum CheckerType {
    /** 编译诊断 */
    COMPILATION("compilation", "编译诊断", "基础", true),
    /** 空指针检测 */
    NULL_POINTER("nullpointer", "空指针检测", "缺陷", true),
    /** 未使用方法检测 */
    UNUSED_METHOD("unused_method", "未使用方法检测", "冗余", true),
    /** 废弃方法检测 */
    DEPRECATED_METHOD("deprecated_method", "废弃方法检测", "兼容性", true),
    /** 圈复杂度检测 */
    COMPLEXITY("complexity", "圈复杂度检测", "质量", true),
    /** 命名规范检查 */
    NAMING("naming", "命名规范检查", "风格", true),
    /** 代码风格检查 */
    CODE_STYLE("code_style", "代码风格检查", "风格", true),
    /** 重复代码检测 */
    DUPLICATE_CODE("duplicate_code", "重复代码检测", "冗余", true),
    /** 异常处理检查 */
    EXCEPTION_HANDLING("exception_handling", "异常处理检查", "缺陷", true),
    /** 资源泄露检测 */
    RESOURCE_LEAK("resource_leak", "资源泄露检测", "缺陷", true),
    /** 安全漏洞检测 */
    SECURITY("security", "安全漏洞检测", "安全", true),
    /** 并发问题检测 */
    CONCURRENCY("concurrency", "并发问题检测", "并发", true),
    /** 性能问题检测 */
    PERFORMANCE("performance", "性能问题检测", "性能", true),
    /** Spring 最佳实践 */
    SPRING_BEST_PRACTICE("spring_best_practice", "Spring最佳实践", "框架", true),
    /** 架构约束检查 */
    ARCHITECTURE("architecture", "架构约束检查", "架构", true),
    /** 依赖漏洞扫描 */
    DEPENDENCY_VULN("dependency_vuln", "依赖漏洞扫描", "依赖", true),
    /** AI 语义评审 */
    AI_SEMANTIC("ai_semantic", "AI语义评审", "AI", false),
    /** AI 安全评审 */
    AI_SECURITY("ai_security", "AI安全评审", "AI", false),
    /** AI 设计评审 */
    AI_DESIGN("ai_design", "AI设计评审", "AI", false);

    private final String code;
    private final String name;
    private final String category;
    private final boolean local;

    CheckerType(String code, String name, String category, boolean local) {
        this.code = code;
        this.name = name;
        this.category = category;
        this.local = local;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public boolean isLocal() { return local; }

    public static CheckerType fromCode(String code) {
        for (CheckerType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
