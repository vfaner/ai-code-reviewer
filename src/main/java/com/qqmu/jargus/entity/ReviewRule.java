package com.qqmu.jargus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评审规则配置实体
 */
@Data
@TableName("review_rule")
public class ReviewRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则编码（唯一） */
    private String ruleCode;

    /** 规则名称 */
    private String ruleName;

    /** 规则分类 */
    private String ruleCategory;

    /** 描述 */
    private String description;

    /** 默认级别: BUG/WARNING/INFO */
    private String defaultLevel;

    /** 是否启用 */
    private Boolean isEnabled;

    /** 是否内置 */
    private Boolean isBuiltin;

    /** 参数配置 JSON */
    private String params;

    /** 排序 */
    private Integer sortOrder;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
