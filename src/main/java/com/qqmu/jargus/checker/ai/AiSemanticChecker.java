package com.qqmu.jargus.checker.ai;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.service.AiReviewService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 语义评审检查器
 *
 * 使用 AI 进行语义级别的代码质量评审，
 * 发现本地静态分析难以检测的问题。
 */
@Component
public class AiSemanticChecker extends AbstractAiChecker {

    public AiSemanticChecker(AiReviewService aiReviewService) {
        super(aiReviewService);
    }

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.AI_SEMANTIC;
    }

    @Override
    public List<CheckIssue> check(CheckContext context) {
        return safeCheck(context, aiReviewService::semanticReview);
    }
}
