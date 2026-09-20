package com.aicodereview.checker.local;

import com.aicodereview.checker.*;
import com.github.javaparser.ast.CompilationUnit;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地检查器基类
 * 提供通用的检查逻辑和工具方法
 */
@Slf4j
public abstract class AbstractLocalChecker implements CodeChecker {

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public List<CheckIssue> check(CheckContext context) {
        if (!accept(context)) {
            return Collections.emptyList();
        }

        try {
            List<CheckIssue> issues = new ArrayList<>();
            CompilationUnit cu = context.getCompilationUnit();

            if (cu == null) {
                // 没有 AST 的情况（如非 Java 文件），子类可覆盖处理
                doCheckWithoutAst(context, issues);
            } else {
                doCheck(context, cu, issues);
            }

            return issues;
        } catch (Exception e) {
            log.warn("检查器 {} 执行出错: {}", getName(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 执行检查（有 AST）
     * 子类应覆盖此方法
     *
     * @param context 检查上下文
     * @param cu      编译单元
     * @param issues  问题列表（向此列表添加问题）
     */
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        // 默认什么都不做，子类覆盖
    }

    /**
     * 执行检查（无 AST）
     * 子类可覆盖此方法处理非 Java 文件或语法错误的文件
     */
    protected void doCheckWithoutAst(CheckContext context, List<CheckIssue> issues) {
        // 默认什么都不做
    }

    /**
     * 创建问题的便捷方法
     */
    protected CheckIssue createIssue(
            IssueLevel level,
            String ruleCode,
            String title,
            String description,
            String filePath,
            int lineStart,
            int lineEnd
    ) {
        return CheckIssue.builder()
                .level(level)
                .checkerType(getCheckerType())
                .checkerName(getName())
                .ruleCode(ruleCode)
                .title(title)
                .description(description)
                .filePath(filePath)
                .lineStart(lineStart)
                .lineEnd(lineEnd)
                .severity(5 - level.ordinal())
                .build();
    }
}
