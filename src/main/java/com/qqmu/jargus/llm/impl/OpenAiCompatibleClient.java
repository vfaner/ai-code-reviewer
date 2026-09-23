package com.qqmu.jargus.llm.impl;

import com.qqmu.jargus.entity.AiProviderConfig;
import com.qqmu.jargus.llm.AiChatRequest;
import com.qqmu.jargus.llm.AiChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议客户端
 *
 * 支持所有 OpenAI 兼容的 API：
 * - OpenAI
 * - 阿里百炼（通义千问）
 * - 火山方舟（豆包）
 * - DeepSeek
 * - Kimi（月之暗面）
 * - 智谱清言
 * - 其他 OpenAI 兼容服务
 */
@Slf4j
public class OpenAiCompatibleClient extends AbstractAiClient {

    public OpenAiCompatibleClient(AiProviderConfig config) {
        super(config);
    }

    @Override
    public String getProtocolType() {
        return "OPENAI_COMPATIBLE";
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            String apiKey = getDecryptedApiKey();
            String model = request.getModel() != null ? request.getModel() : getDefaultModel();

            // 构建请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);

            // 消息列表
            List<Map<String, String>> messages = new ArrayList<>();
            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
                messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
            }
            messages.add(Map.of("role", "user", "content", request.getUserPrompt()));
            body.put("messages", messages);

            // 可选参数
            if (request.getTemperature() != null) {
                body.put("temperature", request.getTemperature());
            }
            Integer effectiveMaxTokens = effectiveMaxTokens(request);
            if (effectiveMaxTokens != null) {
                body.put("max_tokens", effectiveMaxTokens);
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            // 发送请求
            String responseBody = httpPost("/chat/completions", jsonBody, Map.of(
                    HttpHeaders.AUTHORIZATION, "Bearer " + apiKey
            ));

            if (responseBody == null) {
                return AiChatResponse.failure("请求失败");
            }

            // 解析响应
            Map<String, Object> response = parseJson(responseBody);
            if (response == null) {
                return AiChatResponse.failure("响应解析失败");
            }

            // 检查错误
            if (response.containsKey("error")) {
                Object error = response.get("error");
                String errorMsg = error instanceof Map
                        ? String.valueOf(((Map<?, ?>) error).get("message"))
                        : String.valueOf(error);
                return AiChatResponse.failure(errorMsg);
            }

            // 提取内容
            String content = extractContent(response);
            int promptTokens = extractTokenCount(response, "prompt_tokens");
            int completionTokens = extractTokenCount(response, "completion_tokens");
            int totalTokens = extractTokenCount(response, "total_tokens");

            long duration = System.currentTimeMillis() - startTime;

            return AiChatResponse.builder()
                    .success(content != null)
                    .content(content != null ? content : "")
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .durationMs(duration)
                    .rawResponse(responseBody)
                    .build();

        } catch (Exception e) {
            log.error("OpenAI 兼容 API 调用失败: {}", e.getMessage(), e);
            return AiChatResponse.failure(e.getMessage());
        }
    }

    /**
     * 从响应中提取消息内容
     */
    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        try {
            Object choices = response.get("choices");
            if (choices instanceof List && !((List<?>) choices).isEmpty()) {
                Object firstChoice = ((List<?>) choices).get(0);
                if (firstChoice instanceof Map) {
                    Object message = ((Map<String, Object>) firstChoice).get("message");
                    if (message instanceof Map) {
                        return String.valueOf(((Map<String, Object>) message).get("content"));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("提取响应内容失败: {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private int extractTokenCount(Map<String, Object> response, String key) {
        try {
            Object usage = response.get("usage");
            if (usage instanceof Map) {
                Object value = ((Map<String, Object>) usage).get(key);
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }
}
