package com.aicodereview.service;

import com.aicodereview.checker.CheckContext;
import com.aicodereview.checker.CheckIssue;
import com.aicodereview.checker.CheckerType;
import com.aicodereview.checker.IssueLevel;
import com.aicodereview.llm.AiChatClient;
import com.aicodereview.llm.AiChatRequest;
import com.aicodereview.llm.AiChatResponse;
import com.aicodereview.llm.AiClientFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 评审服务
 *
 * 负责调用 AI 大模型进行代码评审
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewService {

    private final AiClientFactory aiClientFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 语义评审（通用代码质量分析）
     */
    public List<CheckIssue> semanticReview(CheckContext context) {
        return review(context, "semantic");
    }

    /**
     * 安全评审
     */
    public List<CheckIssue> securityReview(CheckContext context) {
        return review(context, "security");
    }

    /**
     * 设计评审
     */
    public List<CheckIssue> designReview(CheckContext context) {
        return review(context, "design");
    }

    /**
     * 执行 AI 评审
     */
    private List<CheckIssue> review(CheckContext context, String reviewType) {
        AiChatClient client = aiClientFactory.getActiveClient();
        if (client == null) {
            log.debug("AI 未配置，跳过 AI 评审");
            return Collections.emptyList();
        }

        try {
            String systemPrompt = buildSystemPrompt(reviewType);
            String userPrompt = buildUserPrompt(context);

            java.util.Map<String, String> vars = new java.util.HashMap<>();
            vars.put("code", context.getSourceCode());
            vars.put("file_name", context.getCurrentFilePath());
            vars.put("jdk_version", context.getJdkVersion() != null ? context.getJdkVersion() : "");
            vars.put("spring_boot_version", context.getSpringBootVersion() != null ? context.getSpringBootVersion() : "");

            AiChatRequest request = AiChatRequest.builder()
                    .systemPrompt(systemPrompt)
                    .userPrompt(userPrompt)
                    .temperature(0.3)
                    // maxTokens 不在此硬编码：由厂商配置中的「最大 Token 限制」决定（默认不限制）
                    .variables(vars)
                    .build();

            AiChatResponse response = client.chat(request);

            if (!response.isSuccess()) {
                log.warn("AI 评审失败: {}", response.getErrorMessage());
                return Collections.emptyList();
            }

            return parseIssues(response.getContent(), reviewType, context);

        } catch (Exception e) {
            log.error("AI 评审异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(String reviewType) {
        String basePrompt = "你是一位资深的 Java 代码审查专家。请仔细审查以下代码，找出其中的问题。" +
                "请以 JSON 数组格式返回结果，每个问题包含以下字段：" +
                "title（问题标题）、description（问题描述）、level（严重程度，五选一：BLOCKER 阻断/CRITICAL 严重/MAJOR 主要/MINOR 次要/INFO 提示）、" +
                "line（行号）、suggestion（修复建议）。" +
                "只返回 JSON 数组，不要返回其他文字。";

        return switch (reviewType) {
            case "security" -> basePrompt + "\n重点关注：安全漏洞、注入风险、敏感信息泄露、权限问题、加密问题等。";
            case "design" -> basePrompt + "\n重点关注：代码设计、架构模式、SOLID 原则、可扩展性、可维护性、耦合度等。";
            default -> basePrompt + "\n关注：代码质量、可读性、最佳实践、潜在 bug、性能问题等。";
        };
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(CheckContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件名: ").append(context.getCurrentFilePath()).append("\n");
        if (context.getJdkVersion() != null) {
            sb.append("JDK 版本: ").append(context.getJdkVersion()).append("\n");
        }
        sb.append("\n代码内容:\n```java\n");
        // 只发送前 300 行，避免 token 超限
        List<String> lines = context.getSourceLines();
        int maxLines = Math.min(lines.size(), 300);
        for (int i = 0; i < maxLines; i++) {
            sb.append(String.format("%4d | ", i + 1)).append(lines.get(i)).append("\n");
        }
        if (lines.size() > maxLines) {
            sb.append("... (共 ").append(lines.size()).append(" 行，已截断)\n");
        }
        sb.append("```\n");
        return sb.toString();
    }

    /**
     * 解析 AI 返回的问题
     */
    private List<CheckIssue> parseIssues(String responseContent, String reviewType, CheckContext context) {
        List<CheckIssue> issues = new ArrayList<>();
        if (responseContent == null || responseContent.isEmpty()) {
            return issues;
        }

        try {
            // 尝试提取 JSON 数组
            String jsonStr = extractJsonArray(responseContent);
            if (jsonStr == null) {
                // 如果解析不出 JSON，把整个响应作为一个 INFO 级别的建议
                issues.add(CheckIssue.builder()
                        .level(IssueLevel.INFO)
                        .checkerType(getCheckerType(reviewType))
                        .checkerName(getCheckerName(reviewType))
                        .ruleCode("AI_REVIEW_SUGGESTION")
                        .title("AI 评审建议")
                        .description(responseContent)
                        .filePath(context.getCurrentFilePath())
                        .lineStart(1)
                        .aiGenerated(true)
                        .severity(SeverityCatalog.rank(IssueLevel.INFO))
                        .build());
                return issues;
            }

            List<Map<String, Object>> issueList = objectMapper.readValue(
                    jsonStr, new TypeReference<List<Map<String, Object>>>() {}
            );

            for (Map<String, Object> item : issueList) {
                try {
                    String levelStr = String.valueOf(item.getOrDefault("level", "INFO")).toUpperCase();
                    IssueLevel level = IssueLevel.fromCode(levelStr);

                    String title = String.valueOf(item.getOrDefault("title", "AI 发现的问题"));
                    String description = String.valueOf(item.getOrDefault("description", ""));
                    String suggestion = item.get("suggestion") != null
                            ? String.valueOf(item.get("suggestion")) : null;

                    int line = 1;
                    if (item.get("line") != null) {
                        try {
                            line = Integer.parseInt(String.valueOf(item.get("line")));
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    CheckIssue issue = CheckIssue.builder()
                            .level(level)
                            .checkerType(getCheckerType(reviewType))
                            .checkerName(getCheckerName(reviewType))
                            .ruleCode("AI_" + reviewType.toUpperCase())
                            .title(title)
                            .description(description)
                            .suggestion(suggestion)
                            .filePath(context.getCurrentFilePath())
                            .fileName(extractFileName(context.getCurrentFilePath()))
                            .lineStart(line)
                            .lineEnd(line)
                            .aiGenerated(true)
                            .severity(SeverityCatalog.rank(level))
                            .build();

                    issues.add(issue);
                } catch (Exception e) {
                    log.debug("解析 AI 问题条目失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("解析 AI 响应失败: {}", e.getMessage());
        }

        return issues;
    }

    /**
     * 从 AI 响应中提取 JSON 数组
     */
    private String extractJsonArray(String content) {
        if (content == null) return null;

        // 尝试直接解析
        content = content.trim();

        // 找第一个 [ 和最后一个 ]
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');

        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }

        return null;
    }

    private CheckerType getCheckerType(String reviewType) {
        return switch (reviewType) {
            case "security" -> CheckerType.AI_SECURITY;
            case "design" -> CheckerType.AI_DESIGN;
            default -> CheckerType.AI_SEMANTIC;
        };
    }

    private String getCheckerName(String reviewType) {
        return switch (reviewType) {
            case "security" -> "AI安全评审";
            case "design" -> "AI设计评审";
            default -> "AI语义评审";
        };
    }

    private String extractFileName(String path) {
        if (path == null) return "";
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
