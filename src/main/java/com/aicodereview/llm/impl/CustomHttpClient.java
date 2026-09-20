package com.aicodereview.llm.impl;

import com.aicodereview.entity.AiProviderConfig;
import com.aicodereview.llm.AiChatRequest;
import com.aicodereview.llm.AiChatResponse;
import com.aicodereview.llm.template.TemplateContext;
import com.aicodereview.llm.template.TemplateEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.*;

/**
 * 自定义 HTTP 客户端
 *
 * 支持用户自定义：
 * - 请求方法（GET/POST）
 * - 请求头（认证方式等）
 * - 请求体模板（${} 占位符）
 * - 响应内容路径（JSON Path）
 * - 错误信息路径
 *
 * 可接入任意 HTTP 协议的 LLM 服务：
 * - Claude (Anthropic)
 * - Ollama (本地部署)
 * - vLLM (私有部署)
 * - 企业内部 AI 网关
 * - 其他非 OpenAI 兼容的服务
 */
@Slf4j
public class CustomHttpClient extends AbstractAiClient {

    public CustomHttpClient(AiProviderConfig config) {
        super(config);
    }

    @Override
    public String getProtocolType() {
        return "CUSTOM_HTTP";
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            String model = request.getModel() != null ? request.getModel() : getDefaultModel();

            // 构建模板上下文
            TemplateContext context = TemplateContext.builder()
                    .model(model)
                    .systemPrompt(request.getSystemPrompt())
                    .userPrompt(request.getUserPrompt())
                    .code(request.getVariable("code", ""))
                    .fileName(request.getVariable("file_name", ""))
                    .jdkVersion(request.getVariable("jdk_version", ""))
                    .springBootVersion(request.getVariable("spring_boot_version", ""))
                    .extraVariables(request.getVariables())
                    .build();

            // 渲染请求体模板
            String requestBody = renderRequestBody(context);

            // 构建请求头
            Map<String, String> headers = buildHeaders();

            // 构建请求路径
            String path = ""; // baseUrl 已在 WebClient 中设置，这里通常是根路径

            // 发送请求
            String method = config.getRequestMethod() != null ? config.getRequestMethod() : "POST";
            String responseBody;

            if ("GET".equalsIgnoreCase(method)) {
                responseBody = httpGet(path, headers);
            } else {
                responseBody = httpPost(path, requestBody, headers);
            }

            if (responseBody == null) {
                return AiChatResponse.failure("请求失败，无响应内容");
            }

            // 解析响应
            Map<String, Object> response = parseJson(responseBody);
            if (response == null) {
                // 非 JSON 响应，直接返回内容
                return AiChatResponse.builder()
                        .success(true)
                        .content(responseBody)
                        .durationMs(System.currentTimeMillis() - startTime)
                        .rawResponse(responseBody)
                        .build();
            }

            // 检查错误
            String errorMessage = extractError(response);
            if (errorMessage != null) {
                return AiChatResponse.failure(errorMessage);
            }

            // 提取内容
            String content = extractContent(response);
            if (content == null) {
                content = responseBody;
            }

            return AiChatResponse.builder()
                    .success(true)
                    .content(content)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .rawResponse(responseBody)
                    .build();

        } catch (Exception e) {
            log.error("自定义 HTTP API 调用失败: {}", e.getMessage(), e);
            return AiChatResponse.failure(e.getMessage());
        }
    }

    /**
     * 渲染请求体
     */
    private String renderRequestBody(TemplateContext context) {
        String template = config.getRequestBodyTemplate();
        if (template == null || template.isEmpty()) {
            // 默认的 OpenAI 兼容格式
            return "{\"model\":\"" + context.getModel() + "\"," +
                    "\"messages\":[{\"role\":\"user\",\"content\":\"" +
                    escapeJson(context.getUserPrompt()) + "\"}]}";
        }
        return TemplateEngine.render(template, context);
    }

    /**
     * 构建请求头
     */
    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        String authType = config.getAuthType() != null ? config.getAuthType() : "BEARER";
        String apiKey = getDecryptedApiKey();
        String headerName = config.getAuthHeaderName() != null
                ? config.getAuthHeaderName()
                : "Authorization";

        switch (authType.toUpperCase()) {
            case "BEARER":
                if (apiKey != null) {
                    headers.put(headerName, "Bearer " + apiKey);
                }
                break;
            case "API_KEY_HEADER":
                if (apiKey != null) {
                    headers.put(headerName, apiKey);
                }
                break;
            case "BASIC":
                if (apiKey != null) {
                    // apiKey 格式: username:password
                    String encoded = Base64.getEncoder().encodeToString(apiKey.getBytes());
                    headers.put(headerName, "Basic " + encoded);
                }
                break;
            case "NONE":
                // 不需要认证
                break;
            case "CUSTOM":
            default:
                // 从 request_headers JSON 中解析
                if (config.getRequestHeaders() != null) {
                    try {
                        Map<String, String> customHeaders = objectMapper.readValue(
                                config.getRequestHeaders(),
                                new TypeReference<Map<String, String>>() {}
                        );
                        headers.putAll(customHeaders);
                    } catch (Exception e) {
                        log.debug("解析自定义请求头失败: {}", e.getMessage());
                    }
                }
                break;
        }

        return headers;
    }

    /**
     * 从响应中提取内容
     */
    private String extractContent(Map<String, Object> response) {
        String contentPath = config.getResponseContentPath();
        if (contentPath == null || contentPath.isEmpty()) {
            // 默认尝试 OpenAI 兼容格式
            try {
                Object choices = response.get("choices");
                if (choices instanceof List && !((List<?>) choices).isEmpty()) {
                    Map<?, ?> first = (Map<?, ?>) ((List<?>) choices).get(0);
                    Map<?, ?> msg = (Map<?, ?>) first.get("message");
                    if (msg != null) return String.valueOf(msg.get("content"));
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        return TemplateEngine.extractFromJson(response, contentPath);
    }

    /**
     * 从响应中提取错误信息
     */
    private String extractError(Map<String, Object> response) {
        String errorPath = config.getResponseErrorPath();
        if (errorPath == null || errorPath.isEmpty()) {
            // 检查常见的 error 字段
            if (response.containsKey("error")) {
                Object error = response.get("error");
                if (error instanceof Map) {
                    return String.valueOf(((Map<?, ?>) error).get("message"));
                }
                return String.valueOf(error);
            }
            return null;
        }

        return TemplateEngine.extractFromJson(response, errorPath);
    }

    /**
     * HTTP GET 请求
     */
    private String httpGet(String path, Map<String, String> headers) {
        try {
            var spec = webClient.get().uri(path);
            headers.forEach(spec::header);
            int timeout = config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : 60;
            return spec.retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(timeout));
        } catch (Exception e) {
            log.error("HTTP GET 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 简单的 JSON 字符串转义
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
