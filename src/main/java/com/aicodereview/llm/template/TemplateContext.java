package com.aicodereview.llm.template;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 模板上下文
 * 包含渲染模板时可用的所有变量
 */
@Data
@Builder
public class TemplateContext {

    /** 模型名称 */
    private String model;

    /** 系统提示词 */
    private String systemPrompt;

    /** 用户提示词 */
    private String userPrompt;

    /** 代码内容 */
    private String code;

    /** 文件名 */
    private String fileName;

    /** JDK 版本 */
    private String jdkVersion;

    /** Spring Boot 版本 */
    private String springBootVersion;

    /** 其他自定义变量 */
    private Map<String, String> extraVariables;

    /**
     * 获取变量值
     */
    public String getVariable(String name) {
        return switch (name) {
            case "model" -> model;
            case "system_prompt" -> systemPrompt;
            case "user_prompt" -> userPrompt;
            case "code" -> code;
            case "file_name" -> fileName;
            case "jdk_version" -> jdkVersion;
            case "spring_boot_version" -> springBootVersion;
            default -> extraVariables != null ? extraVariables.get(name) : null;
        };
    }

    /**
     * 从 Map 创建上下文
     */
    public static TemplateContext of(Map<String, String> variables) {
        TemplateContextBuilder builder = TemplateContext.builder();
        if (variables != null) {
            builder.model(variables.get("model"));
            builder.systemPrompt(variables.get("system_prompt"));
            builder.userPrompt(variables.get("user_prompt"));
            builder.code(variables.get("code"));
            builder.fileName(variables.get("file_name"));
            builder.jdkVersion(variables.get("jdk_version"));
            builder.springBootVersion(variables.get("spring_boot_version"));
            builder.extraVariables(variables);
        }
        return builder.build();
    }
}
