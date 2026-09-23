package com.qqmu.jargus.entity;

import com.qqmu.jargus.util.IssuePoints;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 扫描问题详情实体
 */
@Data
@TableName(value = "scan_issue", autoResultMap = true)
public class ScanIssue {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 扫描任务ID */
    private Long taskId;

    /** 文件路径 */
    private String filePath;

    /** 文件名 */
    private String fileName;

    /** 起始行号 */
    private Integer lineStart;

    /** 结束行号 */
    private Integer lineEnd;

    /** 起始列 */
    private Integer columnStart;

    /** 结束列 */
    private Integer columnEnd;

    /** 同文件同规则合并后的全部问题位置（[[起始行,结束行]...] 闭区间列表；单点问题为 null） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<List<Integer>> linePoints;

    /** 原始命中点数（合并前逐行/逐字面量的命中数，单点为 1） */
    private Integer occurrenceCount;

    /** 问题级别: BUG/WARNING/INFO */
    private String issueLevel;

    /** 检查器类型 */
    private String checkerType;

    /** 检查器名称 */
    private String checkerName;

    /** 规则编码 */
    private String ruleCode;

    /** 问题标题 */
    private String title;

    /** 问题描述 */
    private String description;

    /** 代码片段 */
    private String codeSnippet;

    /** 修复建议 */
    private String suggestion;

    /** 严重程度 1-5 */
    private Integer severity;

    /** 是否 AI 生成 */
    private Boolean isAiGenerated;

    /** 是否已忽略 */
    private Boolean isIgnored;

    /** 忽略类型 */
    private String ignoreType;

    /** 忽略原因 */
    private String ignoreReason;

    /** AI 解释 */
    private String aiExplanation;

    /** AI 增强修复建议（深度评审按需生成，区别于扫描时 AI 直接产出的问题） */
    private String aiSuggestion;

    /** AI 增强建议生成时间 */
    private LocalDateTime aiSuggestionAt;

    private LocalDateTime createdAt;

    /**
     * 紧凑行号标签（JSON 输出为 lineLabel，Thymeleaf 中 issue.lineLabel 可用）：
     * 多点合并问题给 "53-56、59-60"，单点问题给单个行号。
     */
    public String getLineLabel() {
        return IssuePoints.label(IssuePoints.toRanges(linePoints), lineStart, lineEnd);
    }
}
