package com.qqmu.jargus.checker.ai;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.service.AiReviewService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 设计评审检查器
 *
 * 使用 AI 分析代码设计质量，
 * 包括：SOLID 原则、架构模式、可扩展性、耦合度等。
 */
@Component
public class AiDesignChecker extends AbstractAiChecker {

    public AiDesignChecker(AiReviewService aiReviewService) {
        super(aiReviewService);
    }

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.AI_DESIGN;
    }

    @Override
    public List<CheckIssue> check(CheckContext context) {
        return safeCheck(context, aiReviewService::designReview);
    }
}
