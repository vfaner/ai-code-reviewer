package com.qqmu.jargus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CI 访问令牌
 */
@Data
@TableName("ci_token")
public class CiToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 令牌名称 */
    private String tokenName;

    /** 令牌值（加密存储） */
    private String tokenValue;

    /** 描述 */
    private String description;

    /** 是否启用 */
    private Boolean isEnabled;

    /** 过期时间（null=永不过期） */
    private LocalDateTime expiresAt;

    /** 最后使用时间 */
    private LocalDateTime lastUsedAt;

    /** 创建人 */
    private String createdBy;

    private LocalDateTime createdAt;
}
