package com.aicodereview.llm;

/**
 * AI 聊天客户端接口
 *
 * 所有 LLM 厂商的客户端都实现此接口
 */
public interface AiChatClient {

    /**
     * 发送聊天请求
     *
     * @param request 聊天请求
     * @return 聊天响应
     */
    AiChatResponse chat(AiChatRequest request);

    /**
     * 测试连接（发送简单请求验证配置）
     *
     * @return true 表示连接成功
     */
    boolean testConnection();

    /**
     * 测试连接并返回详细结果（失败时带真实错误原因，如 HTTP 401 及响应体）
     */
    default AiChatResponse testConnectionDetailed() {
        boolean ok = testConnection();
        return ok ? AiChatResponse.success("OK") : AiChatResponse.failure("连接失败");
    }

    /**
     * 获取协议类型
     */
    String getProtocolType();

    /**
     * 关闭资源
     */
    default void close() {
    }
}
