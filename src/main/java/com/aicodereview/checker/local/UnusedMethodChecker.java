package com.aicodereview.checker.local;

import com.aicodereview.checker.*;
import com.aicodereview.service.CallGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 未使用方法检测检查器
 *
 * 这是一个全局检查器（非单文件检查），
 * 需要对整个项目构建调用图后才能分析。
 *
 * 分析方法：
 * 1. 优先使用 ASM 字节码分析（需要编译）
 * 2. 回退到 JavaParser AST 分析
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnusedMethodChecker extends AbstractLocalChecker {

    private final CallGraphService callGraphService;

    /**
     * 全局分析结果缓存（在整个扫描过程中只计算一次）
     */
    private static final String GLOBAL_DATA_KEY = "unused_method_issues";

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.UNUSED_METHOD;
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    protected void doCheck(CheckContext context, com.github.javaparser.ast.CompilationUnit cu, List<CheckIssue> issues) {
        // 这是全局检查器，在 check() 方法中处理
    }

    @Override
    public List<CheckIssue> check(CheckContext context) {
        if (!accept(context)) {
            return Collections.emptyList();
        }

        Map<String, Object> globalData = context.getGlobalData();
        if (globalData == null) {
            return Collections.emptyList();
        }

        // 检查是否已经分析过
        @SuppressWarnings("unchecked")
        List<CheckIssue> allIssues = (List<CheckIssue>) globalData.get(GLOBAL_DATA_KEY);

        if (allIssues == null) {
            // 第一次调用，执行全局分析
            synchronized (this) {
                @SuppressWarnings("unchecked")
                List<CheckIssue> cached = (List<CheckIssue>) globalData.get(GLOBAL_DATA_KEY);
                if (cached == null) {
                    log.debug("开始全局未使用方法分析...");
                    allIssues = analyzeAll(context);
                    globalData.put(GLOBAL_DATA_KEY, allIssues);
                } else {
                    allIssues = cached;
                }
            }
        }

        // 过滤出当前文件的问题
        String currentFile = context.getCurrentFilePath();
        return allIssues.stream()
                .filter(issue -> currentFile.equals(issue.getFilePath())
                        || issue.getFilePath().endsWith(currentFile))
                .toList();
    }

    /**
     * 执行全局分析
     */
    private List<CheckIssue> analyzeAll(CheckContext context) {
        try {
            return callGraphService.analyzeUnusedMethods(
                    Paths.get(context.getSourceRoot().toUri()),
                    context.isIncludeTestCode(),
                    context.getTaskId()
            );
        } catch (Exception e) {
            log.warn("未使用方法分析失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
