package com.aicodereview.checker.ai;

import com.aicodereview.checker.CheckContext;
import com.aicodereview.checker.CheckIssue;
import com.aicodereview.checker.CodeChecker;
import com.aicodereview.service.AiReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * AI 检查器基类
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractAiChecker implements CodeChecker {

    protected final AiReviewService aiReviewService;

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean accept(CheckContext context) {
        if (!CodeChecker.super.accept(context)) {
            return false;
        }
        // AI 检查器需要启用 AI 评审
        if (!context.isEnableAiReview()) {
            return false;
        }
        // 非 Java 文件跳过
        if (context.getCurrentFilePath() == null
                || !context.getCurrentFilePath().endsWith(".java")) {
            return false;
        }
        // 文件太大的话跳过（AI token 限制）
        if (context.getSourceLines() != null && context.getSourceLines().size() > 1000) {
            log.debug("文件过大，跳过 AI 评审: {}", context.getCurrentFilePath());
            return false;
        }
        return true;
    }

    @Override
    public int getPriority() {
        return 200;
    }

    protected List<CheckIssue> safeCheck(CheckContext context, java.util.function.Function<CheckContext, List<CheckIssue>> reviewer) {
        try {
            return reviewer.apply(context);
        } catch (Exception e) {
            log.warn("AI 检查器 {} 执行出错: {}", getName(), e.getMessage());
            return Collections.emptyList();
        }
    }
}
