package com.qqmu.jargus.llm.impl;

import com.qqmu.jargus.entity.AiProviderConfig;
import com.qqmu.jargus.llm.AiChatRequest;
import com.qqmu.jargus.llm.AiChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic (Claude) 协议客户端
 *
 * 固定鉴权：同时发送 x-api-key（官方 Anthropic）与 Authorization: Bearer
 * （火山方舟 Coding Plan 等网关要求），anthropic-version 固定 2023-06-01。
 * 请求体为 /messages 格式：
 * {"model":"...","max_tokens":N,"system":"...","messages":[{"role":"user","content":"..."}]}
 * 路径：基础地址以 /v1 结尾补 /messages，以 /messages 结尾直接用，其余补 /v1/messages
 * （官方 https://api.anthropic.com、方舟 https://ark.cn-beijing.volces.com/api/coding 均适配）。
 * 响应内容路径：content[0].text，错误路径：error.message
 */
@Slf4j
public class AnthropicClient extends AbstractAiClient {

    /** Anthropic 协议强制要求 max_tokens；未配置上限时使用该默认值 */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    public AnthropicClient(AiProviderConfig config) {
        super(config);
    }

    @Override
    public String getProtocolType() {
        return "ANTHROPIC";
    }

    /**
     * 依据用户填写的基础地址决定 messages 接口路径：
     * 以 /messages 结尾 → 已是完整地址；以 /v1 结尾 → 补 /messages；其他 → 补 /v1/messages
     */
    private String resolveMessagesPath() {
        String base = config.getBaseUrl() == null ? "" : config.getBaseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/messages")) return "";
        if (base.endsWith("/v1")) return "/messages";
        return "/v1/messages";
    }

    @Override
    @SuppressWarnings("unchecked")
    public AiChatResponse chat(AiChatRequest request) {
        long startTime = System.currentTimeMillis();
        try {
            String model = request.getModel() != null ? request.getModel() : getDefaultModel();

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);

            Integer maxTokens = effectiveMaxTokens(request);
            body.put("max_tokens", maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS);

            if (request.getTemperature() != null) {
                body.put("temperature", request.getTemperature());
            }
            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
                body.put("system", request.getSystemPrompt());
            }
            body.put("messages", List.of(Map.of("role", "user", "content", request.getUserPrompt())));

            Map<String, String> headers = new HashMap<>();
            String apiKey = getDecryptedApiKey();
            if (apiKey != null && !apiKey.isEmpty()) {
                // 两种鉴权头都带：官方走 x-api-key，方舟等网关走 Authorization: Bearer
                headers.put("x-api-key", apiKey);
                headers.put("Authorization", "Bearer " + apiKey);
            }
            headers.put("anthropic-version", "2023-06-01");

            String responseBody = httpPost(resolveMessagesPath(), objectMapper.writeValueAsString(body), headers);
            if (responseBody == null) {
                return AiChatResponse.failure("请求失败，无响应内容");
            }

            Map<String, Object> response = parseJson(responseBody);
            if (response == null) {
                // 非 JSON（网关登录页/反爬页等）：带回响应片段便于定位地址是否填错
                return AiChatResponse.failure("响应不是 JSON 格式："
                        + snippet(responseBody) + "（请检查接口地址是否为 Anthropic 兼容入口）");
            }

            Object err = response.get("error");
            if (err instanceof Map<?, ?> errMap) {
                Object msg = errMap.get("message");
                return AiChatResponse.failure(msg != null ? String.valueOf(msg) : "Anthropic 接口返回错误");
            }

            // content 是块数组：思考型模型会先返回 {"type":"thinking",...} 块，
            // 不能只取 content[0].text，需要跳过 thinking 块拼接所有 text 块
            String text = extractText(response.get("content"));
            if (!text.isEmpty()) {
                return AiChatResponse.builder()
                        .success(true).content(text)
                        .durationMs(System.currentTimeMillis() - startTime)
                        .rawResponse(responseBody).build();
            }
            if ("message".equals(response.get("type")) || response.get("content") instanceof List<?>) {
                return AiChatResponse.failure("模型返回了空文本（可能是思考型模型，最大 Token 数设置过小）");
            }

            return AiChatResponse.failure("无法识别的 Anthropic 响应：" + snippet(responseBody));
        } catch (Exception e) {
            log.error("Anthropic API 调用失败: {}", e.getMessage(), e);
            return AiChatResponse.failure(e.getMessage());
        }
    }

    /** 拼接所有 type=text 内容块，跳过 thinking 块（思考型模型/ark auto） */
    private static String extractText(Object content) {
        if (!(content instanceof List<?> list)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object block : list) {
            if (block instanceof Map<?, ?> m
                    && "text".equals(String.valueOf(m.get("type")))
                    && m.get("text") != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(String.valueOf(m.get("text")));
            }
        }
        return sb.toString().strip();
    }

    /** 错误提示里只保留响应前 300 个字符，去掉换行避免撑爆弹窗 */
    private static String snippet(String body) {
        if (body == null) return "<空响应>";
        String s = body.strip();
        if (s.length() > 300) s = s.substring(0, 300) + "...";
        return s.replaceAll("\\s*[\\r\\n]+\\s*", " ");
    }
}
