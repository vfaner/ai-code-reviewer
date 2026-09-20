package com.aicodereview.llm.template;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简单的模板引擎
 *
 * 支持 `${variable}` 格式的占位符替换。
 * 转义：`\${` 表示字面量 `${`
 *
 * 预定义变量：
 * - ${model}
 * - ${system_prompt}
 * - ${user_prompt}
 * - ${code}
 * - ${file_name}
 * - ${jdk_version}
 * - ${spring_boot_version}
 */
@Slf4j
public class TemplateEngine {

    /** 占位符正则：匹配 ${...}，但不匹配 \${...} */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("(?<!\\\\)\\$\\{([^}]+)\\}");

    /**
     * 渲染模板
     *
     * @param template  模板字符串
     * @param context   变量上下文
     * @return 渲染后的字符串
     */
    public static String render(String template, TemplateContext context) {
        if (template == null) {
            return "";
        }
        if (context == null) {
            return template;
        }

        StringBuffer result = new StringBuffer();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);

        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            String replacement = context.getVariable(varName);

            if (replacement == null) {
                // 变量不存在，保留原样（可选：替换为空字符串）
                replacement = matcher.group();
                log.debug("模板变量未定义: {}", varName);
            } else {
                // 转义特殊字符（对于 JSON 模板需要小心处理）
                replacement = Matcher.quoteReplacement(replacement);
            }

            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);

        // 处理转义的 \${ -> ${
        String rendered = result.toString().replace("\\${", "${");

        return rendered;
    }

    /**
     * 渲染模板（简单变量映射）
     */
    public static String render(String template, Map<String, String> variables) {
        return render(template, TemplateContext.of(variables));
    }

    /**
     * 从 JSON 路径中提取值
     * 支持格式：choices[0].message.content, data.result.text
     *
     * @param jsonObject 已解析的 JSON 对象（Map 或 List）
     * @param path       JSON 路径
     * @return 找到的值，未找到返回 null
     */
    @SuppressWarnings("unchecked")
    public static String extractFromJson(Object jsonObject, String path) {
        if (jsonObject == null || path == null || path.isEmpty()) {
            return null;
        }

        String[] parts = path.split("\\.");
        Object current = jsonObject;

        for (String part : parts) {
            if (current == null) return null;

            // 处理数组索引，如 choices[0]
            if (part.contains("[") && part.contains("]")) {
                int bracketStart = part.indexOf('[');
                int bracketEnd = part.indexOf(']');
                String arrayName = part.substring(0, bracketStart);
                int index = Integer.parseInt(part.substring(bracketStart + 1, bracketEnd));

                // 先获取数组
                if (!arrayName.isEmpty() && current instanceof Map) {
                    current = ((Map<String, Object>) current).get(arrayName);
                }

                // 再取索引
                if (current instanceof java.util.List) {
                    java.util.List<Object> list = (java.util.List<Object>) current;
                    if (index >= 0 && index < list.size()) {
                        current = list.get(index);
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            } else {
                // 普通属性访问
                if (current instanceof Map) {
                    current = ((Map<String, Object>) current).get(part);
                } else {
                    return null;
                }
            }
        }

        return current != null ? current.toString() : null;
    }
}
