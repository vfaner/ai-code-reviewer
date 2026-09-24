package com.qqmu.jargus.checker;

import lombok.Builder;
import lombok.Data;
import org.springframework.lang.NonNull;

/**
 * 检查结果 - 单个问题
 */
@Data
@Builder
public class CheckIssue {

    /** 问题级别 */
    private IssueLevel level;

    /** 检查器类型 */
    private CheckerType checkerType;

    /** 检查器名称 */
    private String checkerName;

    /** 规则编码 */
    private String ruleCode;

    /** 问题标题 */
    private String title;

    /** 问题描述 */
    private String description;

    /** 文件路径（相对路径） */
    private String filePath;

    /** 文件名 */
    private String fileName;

    /** 起始行号（从1开始） */
    private int lineStart;

    /** 结束行号 */
    private int lineEnd;

    /** 起始列 */
    private int columnStart;

    /** 结束列 */
    private int columnEnd;

    /** 代码片段 */
    private String codeSnippet;

    /** 修复建议 */
    private String suggestion;

    /** 严重度秩 1-5（INFO=1 … BLOCKER=5，对应 IssueLevel 枚举序） */
    private int severity;

    /** 是否 AI 生成 */
    private boolean aiGenerated;

    /**
     * 创建一个简单的问题
     */
    public static CheckIssue simple(
            @NonNull CheckerType checkerType,
            @NonNull IssueLevel level,
            String ruleCode,
            String title,
            String filePath,
            int lineStart
    ) {
        return CheckIssue.builder()
                .checkerType(checkerType)
                .checkerName(checkerType.getName())
                .level(level)
                .ruleCode(ruleCode)
                .title(title)
                .filePath(filePath)
                .lineStart(lineStart)
                .lineEnd(lineStart)
                .severity(5 - level.ordinal())
                .build();
    }
}
