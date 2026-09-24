package com.qqmu.jargus.llm.impl;

import com.qqmu.jargus.entity.AiProviderConfig;
import com.qqmu.jargus.llm.AiChatRequest;
import com.qqmu.jargus.llm.AiChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini 客户端
 *
 * Gemini API 格式：
 * - URL: /models/{model}:generateContent?key={api_key}
 * - 请求体: {contents:[{parts:[{text:"..."}]}]}
 * - 响应: {candidates:[{content:{parts:[{text:"..."}]}}]}
 */
@Slf4j
public class GeminiClient extends AbstractAiClient {

    public GeminiClient(AiProviderConfig config) {
        super(config);
    }

    @Override
    public String getProtocolType() {
        return "GEMINI";
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            String apiKey = getDecryptedApiKey();
            String model = request.getModel() != null ? request.getModel() : getDefaultModel();

            // 构建内容
            List<Map<String, Object>> parts = new ArrayList<>();

            // system prompt（Gemini 通过 system_instruction 传递，简化处理合并到 user prompt 中）
            StringBuilder contentBuilder = new StringBuilder();
            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
                contentBuilder.append("系统指令：\n").append(request.getSystemPrompt()).append("\n\n");
            }
            contentBuilder.append(request.getUserPrompt());

            parts.add(Map.of("text", contentBuilder.toString()));

            Map<String, Object> contentObj = Map.of("parts", parts, "role", "user");
            List<Map<String, Object>> contents = List.of(contentObj);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", contents);

            if (request.getTemperature() != null || request.getMaxTokens() != null) {
                Map<String, Object> generationConfig = new LinkedHashMap<>();
                if (request.getTemperature() != null) {
                    generationConfig.put("temperature", request.getTemperature());
                }
                if (request.getMaxTokens() != null) {
                    generationConfig.put("maxOutputTokens", request.getMaxTokens());
                }
                body.put("generationConfig", generationConfig);
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            // 构建请求路径
            String path = "/models/" + model + ":generateContent?key=" + apiKey;

            String responseBody = httpPost(path, jsonBody, Map.of());

            if (responseBody == null) {
                return AiChatResponse.failure("请求失败");
            }

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
            String resultText = extractText(response);

            return AiChatResponse.builder()
                    .success(resultText != null && !resultText.isEmpty())
                    .content(resultText != null ? resultText : "")
                    .durationMs(System.currentTimeMillis() - startTime)
                    .rawResponse(responseBody)
                    .build();

        } catch (Exception e) {
            log.error("Gemini API 调用失败: {}", e.getMessage(), e);
            return AiChatResponse.failure(e.getMessage());
        }
    }

    /**
     * 从响应中提取文本
     */
    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        try {
            Object candidates = response.get("candidates");
            if (candidates instanceof List && !((List<?>) candidates).isEmpty()) {
                Object first = ((List<?>) candidates).get(0);
                if (first instanceof Map) {
                    Object contentObj = ((Map<String, Object>) first).get("content");
                    if (contentObj instanceof Map) {
                        Object partsObj = ((Map<String, Object>) contentObj).get("parts");
                        if (partsObj instanceof List && !((List<?>) partsObj).isEmpty()) {
                            Object firstPart = ((List<?>) partsObj).get(0);
                            if (firstPart instanceof Map) {
                                return String.valueOf(((Map<String, Object>) firstPart).get("text"));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("提取 Gemini 响应文本失败: {}", e.getMessage());
        }
        return null;
    }
}
