package com.qqmu.jargus.llm.impl;

import com.qqmu.jargus.entity.AiProviderConfig;
import com.qqmu.jargus.llm.AiChatRequest;
import com.qqmu.jargus.llm.AiChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百度千帆大模型客户端
 *
 * 千帆 API 格式与 OpenAI 略有不同：
 * - 需要使用 API Key + Secret Key 获取 access_token
 * - 请求体格式不同
 * - 响应体格式不同
 */
@Slf4j
public class QianfanClient extends AbstractAiClient {

    /** access_token 缓存 */
    private String accessToken;
    /** token 过期时间 */
    private long tokenExpireTime;

    public QianfanClient(AiProviderConfig config) {
        super(config);
    }

    @Override
    public String getProtocolType() {
        return "QIANFAN";
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            // 获取 access_token
            String token = getAccessToken();
            if (token == null) {
                return AiChatResponse.failure("获取 access_token 失败");
            }

            String model = request.getModel() != null ? request.getModel() : getDefaultModel();

            // 构建请求体（千帆格式）
            List<Map<String, String>> messages = new ArrayList<>();
            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
                // 千帆的 system 通过 messages 第一条或 system 参数传递
                messages.add(Map.of("role", "user", "content", request.getSystemPrompt()
                        + "\n\n用户问题：" + request.getUserPrompt()));
            } else {
                messages.add(Map.of("role", "user", "content", request.getUserPrompt()));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("messages", messages);
            body.put("stream", false);

            if (request.getTemperature() != null) {
                body.put("temperature", request.getTemperature());
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            // 构建 URL：千帆的 URL 格式是 /chat/模型名
            String path = "/chat/" + model + "?access_token=" + token;

            String responseBody = httpPost(path, jsonBody, Map.of(
                    HttpHeaders.CONTENT_TYPE, "application/json"
            ));

            if (responseBody == null) {
                return AiChatResponse.failure("请求失败");
            }

            Map<String, Object> response = parseJson(responseBody);
            if (response == null) {
                return AiChatResponse.failure("响应解析失败");
            }

            // 检查错误
            if (response.containsKey("error_code") && !"0".equals(String.valueOf(response.get("error_code")))) {
                String errorMsg = String.valueOf(response.getOrDefault("error_msg", "未知错误"));
                return AiChatResponse.failure(errorMsg);
            }

            // 提取内容
            String content = String.valueOf(response.getOrDefault("result", ""));

            return AiChatResponse.builder()
                    .success(true)
                    .content(content)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .rawResponse(responseBody)
                    .build();

        } catch (Exception e) {
            log.error("千帆 API 调用失败: {}", e.getMessage(), e);
            return AiChatResponse.failure(e.getMessage());
        }
    }

    /**
     * 获取 access_token
     */
    private synchronized String getAccessToken() {
        // 如果 token 还没过期，直接返回
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return accessToken;
        }

        try {
            String apiKey = getDecryptedApiKey();
            String secretKey = getDecryptedSecretKey();

            if (apiKey == null || secretKey == null) {
                return null;
            }

            String tokenUrl = "https://aip.baidubce.com/oauth/2.0/token";
            String body = "grant_type=client_credentials&client_id=" + apiKey
                    + "&client_secret=" + secretKey;

            String response = webClient.post()
                    .uri(tokenUrl)
                    .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            if (response == null) return null;

            Map<String, Object> resp = parseJson(response);
            if (resp == null) return null;

            accessToken = String.valueOf(resp.get("access_token"));
            // 提前 1 小时过期
            int expiresIn = resp.containsKey("expires_in")
                    ? ((Number) resp.get("expires_in")).intValue()
                    : 2592000; // 30天
            tokenExpireTime = System.currentTimeMillis() + (expiresIn - 3600) * 1000L;

            return accessToken;
        } catch (Exception e) {
            log.error("获取千帆 access_token 失败: {}", e.getMessage());
            return null;
        }
    }
}
