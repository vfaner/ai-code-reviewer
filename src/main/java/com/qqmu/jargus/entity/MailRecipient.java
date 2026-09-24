package com.qqmu.jargus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 扫描通知邮件收件人
 */
@Data
@TableName("mail_recipient")
public class MailRecipient {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 姓名 */
    private String name;

    /** 邮箱（唯一） */
    private String email;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
