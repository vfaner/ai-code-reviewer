package com.aicodereview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名 */
    private String username;

    /** 密码哈希 */
    private String passwordHash;

    /** 昵称 */
    private String nickname;

    /** 角色: ADMIN / VIEWER */
    private String role;

    /** 来源: LOCAL / REMOTE */
    private String source;

    /** 是否启用 */
    private Boolean isEnabled;

    /** 是否为默认密码 */
    private Boolean isPasswordDefault;

    /** 最后登录时间 */
    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
