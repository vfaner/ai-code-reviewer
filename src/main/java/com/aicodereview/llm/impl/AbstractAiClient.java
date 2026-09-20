package com.aicodereview.llm.impl;

import com.aicodereview.entity.AiProviderConfig;
import com.aicodereview.llm.AiChatClient;
import com.aicodereview.llm.AiChatRequest;
import com.aicodereview.llm.AiChatResponse;
import com.aicodereview.util.CryptoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * AI 客户端抽象基类
 *
 * 提供通用的 HTTP 调用、鉴权、重试等功能
 */
@Slf4j
public abstract class AbstractAiClient implements AiChatClient {

    protected final AiProviderConfig config;
    protected final WebClient webClient;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    /** 拼错误提示用的完整请求地址（baseUrl 去掉末尾斜杠 + path） */
    private final String baseUrlTrimmed;

    protected AbstractAiClient(AiProviderConfig config) {
        this.config = config;
        this.webClient = buildWebClient(config);
        String b = config.getBaseUrl() == null ? "" : config.getBaseUrl().trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.baseUrlTrimmed = b;
    }

    /**
     * 构建 WebClient
     */
    protected WebClient buildWebClient(AiProviderConfig config) {
        int timeoutSeconds = config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : 60;

        return WebClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024)) // 10MB
                .build();
    }

    /**
     * 获取 API Key（解密后）
     */
    protected String getDecryptedApiKey() {
        return config.getApiKey() != null && config.getApiKey().startsWith("{")
                ? CryptoUtil.decrypt(config.getApiKey())
                : config.getApiKey();
    }

    /**
     * 获取 Secret Key（解密后）
     */
    protected String getDecryptedSecretKey() {
        return config.getSecretKey() != null
                ? CryptoUtil.decrypt(config.getSecretKey())
                : null;
    }

    /**
     * 获取默认模型
     */
    protected String getDefaultModel() {
        return config.getDefaultModel();
    }

    /**
     * 生效的最大 Token 数：厂商配置了上限则取 min(请求值, 上限)，未配置则沿用请求值（可为 null）
     */
    protected Integer effectiveMaxTokens(AiChatRequest request) {
        Integer requested = request != null ? request.getMaxTokens() : null;
        Integer cap = config.getMaxTokens();
        if (cap == null || cap <= 0) {
            return requested;
        }
        return (requested == null || requested > cap) ? cap : requested;
    }

    /**
     * 执行 HTTP POST 请求。
     * 失败时抛出带 HTTP 状态码/响应体/请求地址的异常，由各客户端 chat() 的 catch
     * 转成 AiChatResponse.failure(e.getMessage())，测试连接时能看到真实原因（如 401）。
     */
    protected String httpPost(String path, String body, Map<String, String> extraHeaders) {
        String fullUrl = baseUrlTrimmed + (path == null ? "" : path);
        try {
            var requestSpec = webClient.post()
                    .uri(path == null ? "" : path);

            // 添加额外请求头
            if (extraHeaders != null) {
                extraHeaders.forEach(requestSpec::header);
            }

            return requestSpec
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(config.getTimeoutSeconds() != null
                            ? config.getTimeoutSeconds() : 60));
        } catch (WebClientResponseException wce) {
            String respBody = wce.getResponseBodyAsString(StandardCharsets.UTF_8);
            if (respBody != null) {
                respBody = respBody.strip();
                if (respBody.length() > 400) respBody = respBody.substring(0, 400) + "...";
            }
            String msg = "HTTP " + wce.getStatusCode().value()
                    + (wce.getStatusText() != null && !wce.getStatusText().isBlank()
                        ? " " + wce.getStatusText() : "");
            if (respBody != null && !respBody.isBlank()) msg += "：" + respBody;
            msg += "（POST " + fullUrl + "）";
            log.error("HTTP POST 请求失败: {} - {}", path, msg);
            throw new RuntimeException(msg, wce);
        } catch (Exception e) {
            String msg = "请求失败: " + e.getMessage() + "（POST " + fullUrl + "）";
            log.error("HTTP POST 请求失败: {} - {}", path, msg);
            throw new RuntimeException(msg, e);
        }
    }

    /**
     * 解析 JSON
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean testConnection() {
        return testConnectionDetailed().isSuccess();
    }

    @Override
    public AiChatResponse testConnectionDetailed() {
        try {
            // 探针给足 token：思考型模型（如 ark-code auto）会先输出 thinking 块，
            // token 太小会被思考耗尽导致 text 块为空、误判失败
            AiChatRequest request = AiChatRequest.builder()
                    .systemPrompt("你是一个助手，请简短回答")
                    .userPrompt("请只回复两个字：你好")
                    .maxTokens(256)
                    .build();
            AiChatResponse response = chat(request);
            if (response == null) {
                return AiChatResponse.failure("无响应");
            }
            if (response.isSuccess() && response.getContent() != null
                    && !response.getContent().isEmpty()) {
                return response;
            }
            return AiChatResponse.failure(
                    response.getErrorMessage() != null ? response.getErrorMessage() : "连接失败");
        } catch (Exception e) {
            log.debug("连接测试失败: {}", e.getMessage());
            return AiChatResponse.failure(e.getMessage());
        }
    }

    @Override
    public void close() {
        // WebClient 不需要显式关闭
    }
}
