package com.qqmu.jargus.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 修复建议目录：按规则码给出默认修复建议。
 * 与 {@link TechnicalDebtCatalog} 同一模式——检查器自身已设置更具体建议时不覆盖；
 * 早期检查器（空指针/命名/废弃方法等）历史上从不写 suggestion，统一在此兜底，
 * 让扫描结果详情、代码上下文弹窗与 PDF/HTML 报告都能展示修复建议。
 */
@Component
public class SuggestionCatalog {

    private static final Map<String, String> SUGGESTIONS = new LinkedHashMap<>();

    static {
        // ── 空指针 ──
        SUGGESTIONS.put("NULL_CHECK",
                "先做非空判断再调用（if (x != null)），或使用 Objects.requireNonNull / Optional.ofNullable 显式表达可空语义");
        SUGGESTIONS.put("NULL_CHAIN_CALL",
                "链式调用中的某一环可能为 null：逐级拆链并判空，或使用 Optional.map().orElseThrow() 风格的安全链");

        // ── 资源 ──
        SUGGESTIONS.put("RESOURCE_LEAK",
                "使用 try-with-resources 自动关闭资源，或在 finally 块中确保 close() 被调用，异常路径也不泄漏");

        // ── 异常处理 ──
        SUGGESTIONS.put("EMPTY_CATCH",
                "至少记录异常日志（log.warn/error 并带上异常对象）；确需忽略时注释说明原因，不要静默吞掉");

        // ── 复杂度 ──
        SUGGESTIONS.put("MAX_COMPLEXITY",
                "拆分方法：抽取分支为独立的小方法，用卫语句（early return）减少嵌套，或用多态/策略表替代多层 if-else");

        // ── 魔法数字 ──
        SUGGESTIONS.put("MAGIC_NUMBER",
                "将字面量提取为具名常量（private static final）或枚举，语义自解释且便于统一修改；0/1 等约定值除外");

        // ── 废弃 API ──
        SUGGESTIONS.put("DEPRECATED_METHOD_CALL",
                "查看 @Deprecated 说明（含 @link 指向的替代 API），迁移到推荐的新方法并回归测试");
        SUGGESTIONS.put("DEPRECATED_METHOD_DECL",
                "在 @Deprecated 注解中补充 since 与 forRemoval，并在 javadoc {@link} 指明替代方法");
        SUGGESTIONS.put("DEPRECATED_CLASS",
                "查看 @Deprecated 说明（含 @link 指向的替代 API），迁移到推荐的新类并回归测试");

        // ── 命名规范 ──
        SUGGESTIONS.put("NAMING_CLASS",
                "类名使用 UpperCamelCase（大驼峰），名词或名词短语，避免下划线与拼音缩写");
        SUGGESTIONS.put("NAMING_METHOD",
                "方法名使用 lowerCamelCase（小驼峰），动词开头（get/find/handle...），名称应能说明副作用与返回值");
        SUGGESTIONS.put("NAMING_VARIABLE",
                "变量名使用 lowerCamelCase（小驼峰），见名知义，避免 a/b/tmp 这类无意义命名");
        SUGGESTIONS.put("NAMING_FIELD",
                "字段名使用 lowerCamelCase（小驼峰），布尔字段以 is/has/can 等开头，常量走全大写蛇形命名");
        SUGGESTIONS.put("NAMING_CONSTANT",
                "static final 常量使用 UPPER_SNAKE_CASE（全大写+下划线），并确保它确实不可变");

        // ── 输出 ──
        SUGGESTIONS.put("SYSTEM_OUT",
                "生产代码改用 SLF4J 日志门面（log.info/error），由日志级别与配置统一控制输出，禁止直接 System.out/err");

        // ── 未使用方法 ──
        SUGGESTIONS.put("UNUSED_METHOD",
                "确认是否为死代码：无用则删除（需要时可从版本历史找回）；若是公共 API/反射入口，加注释或降低可见性");

        // ── 重复代码 ──
        SUGGESTIONS.put("DUP_CODE_BLOCK",
                "抽取公共方法/工具类复用两处逻辑，差异部分用参数或回调表达；改一处即两处生效，避免后续修漏");

        // ── 代码风格 ──
        SUGGESTIONS.put("STYLE_WILDCARD_IMPORT",
                "改为逐条显式 import，避免通配符导入带来的类名歧义（import x.y.* → import x.y.ClassName）");
        SUGGESTIONS.put("STYLE_LONG_LINE",
                "按表达式/参数自然断行，控制在项目约定行长（默认 120）以内；长链式调用每个 . 单独一行");
        SUGGESTIONS.put("STYLE_TODO_COMMENT",
                "TODO 应带责任人与处理计划（// TODO(name): ...）；已完成的及时清理，避免长期遗留技术债");
    }

    /**
     * 按规则码取默认修复建议，无配置返回 null
     */
    public static String get(String ruleCode) {
        return ruleCode == null ? null : SUGGESTIONS.get(ruleCode);
    }

    /**
     * 规则码 → 默认建议全量映射（启动回填历史问题数据用）
     */
    public static Map<String, String> all() {
        return SUGGESTIONS;
    }
}
