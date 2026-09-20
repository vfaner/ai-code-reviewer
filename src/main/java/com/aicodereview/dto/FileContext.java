package com.aicodereview.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文件上下文（带行号和问题标记）
 */
@Data
@Builder
public class FileContext {

    /** 文件路径 */
    private String filePath;

    /** 文件总行数 */
    private int totalLines;

    /** 问题标题（上下文弹窗展示用） */
    private String title;

    /** 问题等级 */
    private String issueLevel;

    /** 规则码 */
    private String ruleCode;

    /** 修复建议（与报告一致，在代码上下文旁直接给出） */
    private String suggestion;

    /** 问题起始行（多点问题为最早一处） */
    private int issueLineStart;

    /** 问题结束行（多点问题为最晚一处） */
    private int issueLineEnd;

    /** 紧凑行号标签，如 "53-56、59-60" */
    private String lineLabel;

    /** 命中点数（同文件同规则合并了几处，单点为 1） */
    private int occurrenceCount;

    /** 窗口上方被省略的行数（0 = 从文件第 1 行开始） */
    private int hiddenBefore;

    /** 窗口下方被省略的行数（0 = 已到文件末尾） */
    private int hiddenAfter;

    /** 行信息列表（仅问题行上下各 contextLines 行，lineNumber 为文件真实行号） */
    private List<LineInfo> lines;

    /**
     * 单行信息
     */
    @Data
    @Builder
    public static class LineInfo {
        /** 行号（从1开始） */
        private int lineNumber;
        /** 行内容 */
        private String content;
        /** 是否是问题行 */
        private boolean issueLine;
        /** 问题标记: start / end / single / null */
        private String issueMarker;
        /** 本行之前被省略的行数（多点问题的两个窗口之间 >0，前端渲染中间省略占位） */
        private int gapBefore;
    }
}
