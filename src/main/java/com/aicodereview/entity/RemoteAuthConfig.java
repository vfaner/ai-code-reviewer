package com.aicodereview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 远端登录配置（OA 对接）
 */
@Data
@TableName("remote_auth_config")
public class RemoteAuthConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置名称 */
    private String configName;

    /** 认证类型: OAUTH2 / LDAP / HTTP */
    private String authType;

    /** 登录接口 URL */
    private String loginUrl;

    /** 用户信息接口 URL */
    private String userInfoUrl;

    /** Token URL (OAuth2) */
    private String tokenUrl;

    /** 客户端 ID */
    private String clientId;

    /** 客户端密钥（加密存储） */
    private String clientSecret;

    /** 用户名字段映射 */
    private String usernameField;

    /** 昵称字段映射 */
    private String nicknameField;

    /** 角色字段映射 */
    private String roleField;

    /** 角色映射 JSON（远端角色 -> 本地角色） */
    private String roleMapping;

    /** 是否启用 */
    private Boolean isEnabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
