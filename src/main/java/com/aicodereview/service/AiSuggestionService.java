package com.aicodereview.service;

import com.aicodereview.dto.FileContext;
import com.aicodereview.entity.ScanIssue;
import com.aicodereview.entity.ScanTask;
import com.aicodereview.llm.AiChatClient;
import com.aicodereview.llm.AiChatRequest;
import com.aicodereview.llm.AiChatResponse;
import com.aicodereview.llm.AiClientFactory;
import com.aicodereview.mapper.ScanIssueMapper;
import com.aicodereview.mapper.ScanTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 修复建议增强服务：
 * - 单条增强：在代码上下文弹窗按需调用 AI，结合真实源码给出"问题分析/修复方案/修复代码"
 * - AI 深度评审：批量为任务下所有未忽略、尚未增强的问题逐条生成增强建议（后台异步 + 进度查询）
 * 结果落库 scan_issue.ai_suggestion，报告与列表复用，无需重复消耗 Token。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSuggestionService {

    /** 单次深度评审最多增强条数，防止异常大任务把 Token 额度跑空 */
    private static final int DEEP_REVIEW_LIMIT = 50;
    /** 取问题行前后各多少行做上下文 */
    private static final int CONTEXT_LINES = 30;

    private final AiClientFactory aiClientFactory;
    private final ScanIssueMapper scanIssueMapper;
    private final ScanTaskMapper scanTaskMapper;
    private final ScanIssueService scanIssueService;
    private final ReportService reportService;
    /** 自注入代理：@Async 方法必须经 Spring 代理调用，类内 this 直调不会异步 */
    private final ObjectProvider<AiSuggestionService> selfProvider;

    /** 深度评审进度：taskId → 进度（仅本节点内存态，重启后视为空闲） */
    private final Map<Long, Progress> jobs = new ConcurrentHashMap<>();

    public boolean isAvailable() {
        return aiClientFactory.isAiConfigured();
    }

    /**
     * 单条问题的 AI 增强建议（同步调用，结果落库）
     */
    public String enhanceIssue(Long issueId) {
        ScanIssue issue = scanIssueMapper.selectById(issueId);
        if (issue == null) {
            throw new RuntimeException("问题不存在");
        }
        AiChatClient client = aiClientFactory.getActiveClient();
        if (client == null) {
            throw new RuntimeException("未配置可用的 AI 厂商，请先在「系统管理 - AI 厂商」中配置并启用");
        }

        FileContext ctx = scanIssueService.getIssueFileContext(issueId, CONTEXT_LINES);
        String codeText = buildCodeText(ctx);

        AiChatRequest request = AiChatRequest.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .userPrompt(buildUserPrompt(issue, ctx, codeText))
                .temperature(0.2)
                .build();

        AiChatResponse response = client.chat(request);
        if (!response.isSuccess()) {
            throw new RuntimeException("AI 调用失败：" + response.getErrorMessage());
        }
        String text = normalizeContent(response.getContent());
        if (text == null || text.isBlank()) {
            throw new RuntimeException("AI 返回内容为空，请稍后重试");
        }

        ScanIssue update = new ScanIssue();
        update.setId(issueId);
        update.setAiSuggestion(text);
        update.setAiSuggestionAt(LocalDateTime.now());
        scanIssueMapper.updateById(update);
        // AI 建议落库必须让报告磁盘缓存失效：preview/download 只要缓存文件存在就直接复用，
        // 否则深度评审进行中打开过预览的任务会把"半成品"报告一直缓存下去
        reportService.purgeReportCache(issue.getTaskId());
        log.info("AI 增强建议已生成: issueId={}, tokens={}", issueId, response.getTotalTokens());
        return text;
    }

    /**
     * 启动任务级 AI 深度评审（后台异步，逐条增强）
     */
    public Progress startDeepReview(Long taskId) {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }
        AiChatClient client = aiClientFactory.getActiveClient();
        if (client == null) {
            throw new RuntimeException("未配置可用的 AI 厂商，请先在「系统管理 - AI 厂商」中配置并启用");
        }
        Progress running = jobs.get(taskId);
        if (running != null && running.isRunning()) {
            throw new RuntimeException("该任务正在进行 AI 深度评审，请等待完成");
        }

        List<ScanIssue> pending = scanIssueMapper.selectList(new QueryWrapper<ScanIssue>()
                .eq("task_id", taskId)
                .eq("is_ignored", false)
                .isNull("ai_suggestion")
                .orderByAsc("issue_level", "id"));

        Progress progress = new Progress();
        progress.setRunning(true);
        progress.setTotal(Math.min(pending.size(), DEEP_REVIEW_LIMIT));
        progress.setDone(0);
        progress.setFailed(0);
        progress.setSkipped(Math.max(0, pending.size() - DEEP_REVIEW_LIMIT));
        progress.setStartedAt(LocalDateTime.now());
        if (pending.isEmpty()) {
            progress.setRunning(false);
            progress.setFinishedAt(LocalDateTime.now());
            progress.setMessage("所有问题均已生成 AI 增强建议");
        }
        jobs.put(taskId, progress);

        if (!pending.isEmpty()) {
            selfProvider.getObject().runDeepReview(taskId, pending.subList(0, progress.getTotal()));
        }
        return progress;
    }

    public Progress getProgress(Long taskId) {
        Progress p = jobs.get(taskId);
        if (p != null) return p;
        // 无内存记录（如重启后）：查库给出已增强条数
        Progress empty = new Progress();
        empty.setRunning(false);
        Long enhanced = scanIssueMapper.selectCount(new QueryWrapper<ScanIssue>()
                .eq("task_id", taskId).isNotNull("ai_suggestion"));
        empty.setDone(enhanced == null ? 0 : enhanced.intValue());
        return empty;
    }

    @Async("scanTaskExecutor")
    public void runDeepReview(Long taskId, List<ScanIssue> issues) {
        Progress progress = jobs.get(taskId);
        try {
            for (ScanIssue issue : issues) {
                if (!progress.isRunning()) break; // 预留中止能力
                try {
                    enhanceIssue(issue.getId());
                    progress.setDone(progress.getDone() + 1);
                } catch (Exception e) {
                    log.warn("AI 深度评审单条失败: issueId={}, err={}", issue.getId(), e.getMessage());
                    progress.setFailed(progress.getFailed() + 1);
                }
            }
            progress.setRunning(false);
            progress.setFinishedAt(LocalDateTime.now());
            progress.setMessage(progress.getFailed() > 0
                    ? "深度评审完成，" + progress.getFailed() + " 条失败（可重新发起补齐）"
                    : "AI 深度评审完成");
        } catch (Exception e) {
            log.error("AI 深度评审异常: taskId={}", taskId, e);
            progress.setRunning(false);
            progress.setFinishedAt(LocalDateTime.now());
            progress.setMessage("深度评审异常中断：" + e.getMessage());
        }
    }

    private String buildCodeText(FileContext ctx) {
        if (ctx == null || ctx.getLines() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (FileContext.LineInfo l : ctx.getLines()) {
            sb.append(String.format("%5d  %s%s%n",
                    l.getLineNumber(), l.isIssueLine() ? ">> " : "   ", l.getContent() == null ? "" : l.getContent()));
        }
        return sb.toString();
    }

    private String buildUserPrompt(ScanIssue issue, FileContext ctx, String codeText) {
        StringBuilder sb = new StringBuilder();
        sb.append("【问题元数据】\n");
        sb.append("文件: ").append(issue.getFilePath()).append("\n");
        sb.append("行号: ").append(issue.getLineLabel());
        if (issue.getOccurrenceCount() != null && issue.getOccurrenceCount() > 1) {
            sb.append("（同类问题共 ").append(issue.getOccurrenceCount()).append(" 处，均已用 >> 标记）");
        }
        sb.append("\n");
        sb.append("规则码: ").append(issue.getRuleCode() == null ? "—" : issue.getRuleCode()).append("\n");
        sb.append("问题标题: ").append(issue.getTitle() == null ? "—" : issue.getTitle()).append("\n");
        if (issue.getDescription() != null && !issue.getDescription().isBlank()) {
            sb.append("问题描述: ").append(issue.getDescription()).append("\n");
        }
        String base = issue.getSuggestion();
        if (base != null && !base.isBlank()) {
            sb.append("静态检查器的基础建议: ").append(base).append("\n");
        }
        if (ctx != null && ctx.getTotalLines() > 0) {
            sb.append("文件总行数: ").append(ctx.getTotalLines()).append("\n");
        }
        sb.append("\n【源码上下文】（行号前 >> 标记的是问题行，其余仅用于理解，不允许建议改动）\n").append(codeText);
        sb.append("\n请仅针对 >> 标记的问题行，按系统消息约定的三部分输出。");
        return sb.toString();
    }

    /** 去掉模型可能整体包裹的 ```markdown / ``` 围栏，保留正文中的 ```java 代码块 */
    private String normalizeContent(String content) {
        if (content == null) return null;
        String t = content.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) {
                String fence = t.substring(3, firstNl).trim().toLowerCase();
                // 仅当整段被 markdown/text 围栏包裹时剥离，java 等语言围栏可能正是正文开头则不处理
                if (fence.isEmpty() || fence.equals("markdown") || fence.equals("md") || fence.equals("text")) {
                    t = t.substring(firstNl + 1);
                    if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
                    t = t.trim();
                }
            }
        }
        return t;
    }

    private static final String SYSTEM_PROMPT = """
            你是一位资深 Java 工程师兼代码审查专家。用户会给出一条静态检查发现的问题及其真实源码上下文（>> 标记问题行）。
            请只针对【被 >> 标记的问题行】判断问题是否成立，并给出只修改该处的、可直接落地的修复建议。严格按以下三部分输出，使用简体中文：

            【问题分析】用 1-3 句话说明问题行的风险本质、触发条件与可能后果；若上下文表明该问题是误报，直接说明误报理由，不再输出后两部分。
            【修复方案】分条给出针对问题行的具体修改步骤，说明关键 API、判空/资源管理/并发等要点，避免空泛套话。
            【修复代码】只给出问题行所在位置修改后的关键 Java 代码，使用 ```java 代码块；无关代码一律用 ... 省略，不要大段重复原文件。

            严格范围：本次建议的唯一目标是修复被 >> 标记的问题行。除同一方法内完成该修复所必需的配套代码（如新增的 import 或局部变量）外，
            禁止建议修改、重构或"顺手修复"任何未被标记的其他方法、其他代码行——即使它们存在相似写法或同类隐患，那些代码本次扫描并未报告问题，不在建议范围内；
            也不要建议修改与问题行无关的业务逻辑。禁止输出 JSON、禁止复述用户给出的元数据、禁止泛泛而谈（如"加强健壮性"）。""" ;

    /** 深度评审进度（内存态，序列化为 JSON 给前端轮询） */
    @Data
    public static class Progress {
        private boolean running;
        private int total;
        private int done;
        private int failed;
        private int skipped;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private String message;
    }
}
