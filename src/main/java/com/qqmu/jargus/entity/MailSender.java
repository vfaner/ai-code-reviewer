package com.qqmu.jargus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发件配置（SMTP 账号；同一时间仅允许一条启用，由服务层保证）
 */
@Data
@TableName("mail_sender")
public class MailSender {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置名称（如「公司邮箱」） */
    private String name;

    /** SMTP 主机 */
    private String host;

    /** SMTP 端口 */
    private Integer port;

    /** 登录用户名（空=无鉴权 SMTP） */
    private String username;

    /** 密码/授权码（AES 加密存储，编辑留空或掩码表示不修改） */
    private String password;

    /** 发件地址 */
    private String fromAddress;

    /** 发件人别名（收件箱显示名） */
    private String fromAlias;

    /** 使用 STARTTLS */
    private Boolean useStarttls;

    /** 使用 SSL（隐式 TLS 端口） */
    private Boolean useSsl;

    /** 是否启用（服务层保证全局仅一个） */
    private Boolean isEnabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
