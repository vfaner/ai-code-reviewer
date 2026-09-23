package com.qqmu.jargus.llm;

import com.qqmu.jargus.entity.AiProviderConfig;
import com.qqmu.jargus.llm.impl.AnthropicClient;
import com.qqmu.jargus.llm.impl.CustomHttpClient;
import com.qqmu.jargus.llm.impl.GeminiClient;
import com.qqmu.jargus.llm.impl.OpenAiCompatibleClient;
import com.qqmu.jargus.llm.impl.QianfanClient;
import com.qqmu.jargus.service.ProviderConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 客户端工厂
 *
 * 根据厂商配置创建对应的客户端实例
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiClientFactory {

    private final ProviderConfigService providerConfigService;

    /**
     * 获取当前激活的 AI 客户端
     *
     * @return AI 客户端，如果没有配置则返回 null
     */
    public AiChatClient getActiveClient() {
        AiProviderConfig activeConfig = providerConfigService.getActive();
        if (activeConfig == null) {
            log.debug("没有激活的 AI 厂商配置");
            return null;
        }
        return createClient(activeConfig);
    }

    /**
     * 根据配置创建客户端
     */
    public AiChatClient createClient(AiProviderConfig config) {
        if (config == null) return null;

        String protocol = config.getProtocolType();
        if (protocol == null) {
            protocol = "OPENAI_COMPATIBLE";
        }

        try {
            return switch (protocol.toUpperCase()) {
                case "OPENAI_COMPATIBLE", "OPENAI" -> new OpenAiCompatibleClient(config);
                case "ANTHROPIC", "CLAUDE" -> new AnthropicClient(config);
                case "QIANFAN" -> new QianfanClient(config);
                case "GEMINI" -> new GeminiClient(config);
                case "CUSTOM_HTTP", "CUSTOM" -> new CustomHttpClient(config);
                default -> {
                    log.warn("未知的协议类型: {}，默认使用 OpenAI 兼容", protocol);
                    yield new OpenAiCompatibleClient(config);
                }
            };
        } catch (Exception e) {
            log.error("创建 AI 客户端失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查是否已配置 AI
     */
    public boolean isAiConfigured() {
        return providerConfigService.getActive() != null;
    }
}
