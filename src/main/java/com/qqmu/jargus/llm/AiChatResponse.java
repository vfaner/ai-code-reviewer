package com.qqmu.jargus.llm;

import lombok.Builder;
import lombok.Data;

/**
 * AI 聊天响应
 */
@Data
@Builder
public class AiChatResponse {

    /** 是否成功 */
    private boolean success;

    /** 响应内容 */
    private String content;

    /** 输入 token 数 */
    private int promptTokens;

    /** 输出 token 数 */
    private int completionTokens;

    /** 总 token 数 */
    private int totalTokens;

    /** 耗时（毫秒） */
    private long durationMs;

    /** 错误信息（失败时） */
    private String errorMessage;

    /** 原始响应（调试用） */
    private String rawResponse;

    public static AiChatResponse success(String content) {
        return AiChatResponse.builder()
                .success(true)
                .content(content)
                .build();
    }

    public static AiChatResponse failure(String errorMessage) {
        return AiChatResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
