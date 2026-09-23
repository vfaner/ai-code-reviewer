package com.qqmu.jargus.llm;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * AI 聊天请求
 */
@Data
@Builder
public class AiChatRequest {

    /** 系统提示词 */
    private String systemPrompt;

    /** 用户提示词 */
    private String userPrompt;

    /** 模型名称（可选，为空使用配置的默认模型） */
    private String model;

    /** 温度（0-2） */
    private Double temperature;

    /** 最大 token 数 */
    private Integer maxTokens;

    /** 额外的上下文变量（用于模板替换） */
    private Map<String, String> variables;

    /**
     * 获取变量值
     */
    public String getVariable(String key, String defaultValue) {
        if (variables == null) return defaultValue;
        return variables.getOrDefault(key, defaultValue);
    }
}
