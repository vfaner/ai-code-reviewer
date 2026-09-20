package com.aicodereview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检查器配置实体
 */
@Data
@TableName("checker_config")
public class CheckerConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 检查器编码 */
    private String checkerCode;

    /** 检查器名称 */
    private String checkerName;

    /** 检查器分类 */
    private String checkerCategory;

    /** 描述 */
    private String description;

    /** 是否启用 */
    private Boolean isEnabled;

    /** 是否本地检查 */
    private Boolean isLocal;

    /** 参数配置 JSON */
    private String params;

    /** 排序 */
    private Integer sortOrder;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
