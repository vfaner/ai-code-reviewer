package com.aicodereview.checker;

import com.github.javaparser.ast.CompilationUnit;
import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检查上下文
 * 包含当前检查所需的所有信息
 */
@Data
@Builder
public class CheckContext {

    /** 任务ID */
    private Long taskId;

    /** 源码根目录 */
    private Path sourceRoot;

    /** 当前文件路径（相对路径） */
    private String currentFilePath;

    /** 当前文件的绝对路径 */
    private Path currentFileAbsolutePath;

    /** 当前文件的编译单元（JavaParser 解析结果） */
    private CompilationUnit compilationUnit;

    /** 源码内容 */
    private String sourceCode;

    /** 源码行列表（按行分割，从第0行开始） */
    private List<String> sourceLines;

    /** JDK 版本 */
    private String jdkVersion;

    /** Spring Boot 版本 */
    private String springBootVersion;

    /** 是否跳过单元测试 */
    private boolean skipUnitTest;

    /** 是否包含测试代码 */
    private boolean includeTestCode;

    /** 是否启用 AI 评审 */
    private boolean enableAiReview;

    /** 全局上下文数据（检查器之间共享） */
    private Map<String, Object> globalData;

    /** 当前文件发现的问题列表 */
    @Builder.Default
    private List<CheckIssue> issues = new ArrayList<>();

    /**
     * 添加一个问题
     */
    public void addIssue(CheckIssue issue) {
        if (issues == null) {
            issues = new ArrayList<>();
        }
        issues.add(issue);
    }

    /**
     * 获取指定行的代码
     */
    public String getLine(int lineNumber) {
        if (sourceLines == null || lineNumber < 1 || lineNumber > sourceLines.size()) {
            return "";
        }
        return sourceLines.get(lineNumber - 1);
    }

    /**
     * 获取指定范围的代码片段
     */
    public String getCodeSnippet(int startLine, int endLine) {
        if (sourceLines == null || startLine < 1 || endLine > sourceLines.size() || startLine > endLine) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = startLine - 1; i < endLine && i < sourceLines.size(); i++) {
            sb.append(sourceLines.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 是否是测试文件
     */
    public boolean isTestFile() {
        if (currentFilePath == null) return false;
        return currentFilePath.contains("/test/") || currentFilePath.contains("\\test\\")
                || currentFilePath.endsWith("Test.java") || currentFilePath.endsWith("Tests.java");
    }
}
