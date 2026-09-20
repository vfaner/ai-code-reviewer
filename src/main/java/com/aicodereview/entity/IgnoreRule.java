package com.aicodereview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 忽略规则
 */
@Data
@TableName("ignore_rule")
public class IgnoreRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则类型: RULE_CODE / FILE_PATH / FILE_PATTERN / LINE_NUMBER */
    private String ruleType;

    /** 规则编码（当 ruleType=RULE_CODE 时有效） */
    private String ruleCode;

    /** 文件匹配模式（glob 语法，当 ruleType=FILE_PATTERN 时有效） */
    private String filePattern;

    /** 文件路径（当 ruleType=FILE_PATH 时有效） */
    private String filePath;

    /** 行号（当 ruleType=LINE_NUMBER 时有效，需配合 filePath） */
    private Integer lineNumber;

    /** 忽略原因 */
    private String reason;

    /** 是否启用 */
    private Boolean isEnabled;

    /** 创建人 */
    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
