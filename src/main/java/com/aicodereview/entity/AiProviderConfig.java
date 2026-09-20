package com.aicodereview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 厂商配置实体
 */
@Data
@TableName("ai_provider_config")
public class AiProviderConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 厂商名称 */
    private String providerName;

    /** 显示名称 */
    private String displayName;

    /** 协议类型: OPENAI_COMPATIBLE/QIANFAN/GEMINI/CUSTOM_HTTP */
    private String protocolType;

    /** Base URL */
    private String baseUrl;

    /** API Key（加密） */
    private String apiKey;

    /** Secret Key（千帆，加密） */
    private String secretKey;

    /** 默认模型 */
    private String defaultModel;

    /** 可用模型 JSON */
    private String availableModels;

    /** 认证方式: BEARER/API_KEY_HEADER/BASIC/NONE/CUSTOM */
    private String authType;

    /** 认证头名称 */
    private String authHeaderName;

    /** 请求方法: POST/GET */
    private String requestMethod;

    /** 自定义请求头 JSON */
    private String requestHeaders;

    /** 请求体模板 */
    private String requestBodyTemplate;

    /** 响应内容路径 */
    private String responseContentPath;

    /** 错误信息路径 */
    private String responseErrorPath;

    /** 超时时间(秒) */
    private Integer timeoutSeconds;

    /** 最大 Token 数限制（null 表示不限制） */
    private Integer maxTokens;

    /** 是否自定义 */
    private Boolean isCustom;

    /** 是否当前启用（全局唯一） */
    private Boolean isActive;

    /** 是否启用 */
    private Boolean isEnabled;

    /** 排序 */
    private Integer sortOrder;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
