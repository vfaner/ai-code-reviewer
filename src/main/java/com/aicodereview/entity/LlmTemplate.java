package com.aicodereview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 配置模板实体
 */
@Data
@TableName("llm_template")
public class LlmTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板名称 */
    private String templateName;

    /** 厂商名称 */
    private String providerName;

    /** 协议类型 */
    private String protocolType;

    /** 默认 Base URL */
    private String baseUrl;

    /** 认证方式 */
    private String authType;

    /** 认证头名称 */
    private String authHeaderName;

    /** 请求体模板 */
    private String requestBodyTemplate;

    /** 响应内容路径 */
    private String responseContentPath;

    /** 错误路径 */
    private String responseErrorPath;

    /** 默认模型 */
    private String defaultModel;

    /** 说明 */
    private String description;

    /** 是否内置 */
    private Boolean isBuiltin;

    private LocalDateTime createdAt;
}
