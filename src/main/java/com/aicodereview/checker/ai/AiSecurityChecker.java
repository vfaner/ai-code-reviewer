package com.aicodereview.checker.ai;

import com.aicodereview.checker.*;
import com.aicodereview.service.AiReviewService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 安全评审检查器
 *
 * 使用 AI 深度分析代码中的安全漏洞，
 * 包括：注入攻击、敏感信息泄露、权限问题、加密问题等。
 */
@Component
public class AiSecurityChecker extends AbstractAiChecker {

    public AiSecurityChecker(AiReviewService aiReviewService) {
        super(aiReviewService);
    }

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.AI_SECURITY;
    }

    @Override
    public List<CheckIssue> check(CheckContext context) {
        return safeCheck(context, aiReviewService::securityReview);
    }
}
