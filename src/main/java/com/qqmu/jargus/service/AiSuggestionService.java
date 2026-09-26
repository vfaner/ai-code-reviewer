package com.qqmu.jargus.service;

import com.qqmu.jargus.checker.IssueLevel;
import com.qqmu.jargus.dto.FileContext;
import com.qqmu.jargus.entity.ScanIssue;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.llm.AiChatClient;
import com.qqmu.jargus.llm.AiChatRequest;
import com.qqmu.jargus.llm.AiChatResponse;
import com.qqmu.jargus.llm.AiClientFactory;
import com.qqmu.jargus.mapper.ScanIssueMapper;
import com.qqmu.jargus.mapper.ScanTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * AI 修复建议增强服务：
 * - 单条增强：在代码上下文弹窗按需调用 AI，结合真实源码给出"问题分析/修复方案/修复代码"
 * - AI 深度评审：批量为任务下所有未忽略、尚未增强的问题生成增强建议（后台异步 + 进度查询），
 *   单条 LLM 调用提交到 aiReviewExecutor 专用线程池并发执行（默认 3 并发可配置），
 *   协调线程独立运行，不占用扫描池槽位
 * 结果落库 scan_issue.ai_suggestion，报告与列表复用，无需重复消耗 Token。
 */
@Slf4j
@Service
public class AiSuggestionService {

    /** 单次深度评审最多增强条数，防止异常大任务把 Token 额度跑空 */
    private static final int DEEP_REVIEW_LIMIT = 50;
    /** 取问题行前后各多少行做上下文（缩减输入 Token，可小幅加快模型响应） */
    private static final int CONTEXT_LINES = 20;

    private final AiClientFactory aiClientFactory;
    private final ScanIssueMapper scanIssueMapper;
    private final ScanTaskMapper scanTaskMapper;
    private final ScanIssueService scanIssueService;
    private final ReportService reportService;
    /** AI 调用专用线程池（与扫描池隔离，LLM 调用 IO 密集可安全并发） */
    private final Executor aiReviewExecutor;

    public AiSuggestionService(AiClientFactory aiClientFactory,
                               ScanIssueMapper scanIssueMapper,
                               ScanTaskMapper scanTaskMapper,
                               ScanIssueService scanIssueService,
                               ReportService reportService,
                               @Qualifier("aiReviewExecutor") Executor aiReviewExecutor) {
        this.aiClientFactory = aiClientFactory;
        this.scanIssueMapper = scanIssueMapper;
        this.scanTaskMapper = scanTaskMapper;
        this.scanIssueService = scanIssueService;
        this.reportService = reportService;
        this.aiReviewExecutor = aiReviewExecutor;
    }

    /** 深度评审进度：taskId → 进度（仅本节点内存态，重启后视为空闲） */
    private final Map<Long, Progress> jobs = new ConcurrentHashMap<>();

    public boolean isAvailable() {
        return aiClientFactory.isAiConfigured();
    }

    /**
     * 单条问题的 AI 增强建议（同步调用，结果落库）
     */
    public String enhanceIssue(Long issueId) {
        return doEnhance(issueId, true);
    }

    /**
     * 单条增强核心流程。purgeCache=false 时由调用方统一失效报告缓存
     * （深度评审场景逐条清理会重复做 N 次磁盘缓存删除，收尾清一次即可）
     */
    private String doEnhance(Long issueId, boolean purgeCache) {
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
        if (purgeCache) {
            reportService.purgeReportCache(issue.getTaskId());
        }
        log.info("AI 增强建议已生成: issueId={}, tokens={}", issueId, response.getTotalTokens());
        return text;
    }

    /**
     * 启动任务级 AI 深度评审（后台异步，单条 LLM 调用并发执行）
     *
     * @param levels 逗号分隔的严重度过滤（如 "BLOCKER,CRITICAL"），空/null = 不限级别
     */
    public Progress startDeepReview(Long taskId, String levels) {
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

        Set<String> levelSet = parseLevels(levels);
        QueryWrapper<ScanIssue> qw = new QueryWrapper<ScanIssue>()
                .eq("task_id", taskId)
                .eq("is_ignored", false)
                .isNull("ai_suggestion");
        if (!levelSet.isEmpty()) {
            qw.in("issue_level", levelSet);
        }
        // issue_level 是字符串列，字母序 ≠ 严重度序（INFO 会排在 MAJOR 前面），
        // 改用 severity 秩排序（BLOCKER=5 … INFO=1），保证高严重度优先增强
        qw.orderByDesc("severity").orderByAsc("id");
        List<ScanIssue> pending = scanIssueMapper.selectList(qw);

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
            progress.setMessage(levelSet.isEmpty()
                    ? "所有问题均已生成 AI 增强建议"
                    : "所选严重度下没有待增强的问题");
        }
        jobs.put(taskId, progress);

        if (!pending.isEmpty()) {
            // 协调线程独立运行（不占扫描池槽位），单条增强提交 aiReviewExecutor 并发执行
            List<ScanIssue> batch = new ArrayList<>(pending.subList(0, progress.getTotal()));
            Thread coordinator = new Thread(() -> runDeepReview(taskId, batch), "ai-deep-review-" + taskId);
            coordinator.setDaemon(true);
            coordinator.start();
        }
        return progress;
    }

    /** 解析逗号分隔的严重度过滤，仅接受合法的五级枚举名；返回空集 = 不过滤 */
    private Set<String> parseLevels(String levels) {
        Set<String> set = new LinkedHashSet<>();
        if (levels == null || levels.isBlank()) {
            return set;
        }
        for (String part : levels.split(",")) {
            String t = part.trim().toUpperCase();
            if (t.isEmpty()) continue;
            for (IssueLevel lv : IssueLevel.values()) {
                if (lv.name().equals(t)) {
                    set.add(t);
                    break;
                }
            }
        }
        return set;
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

    /**
     * 深度评审协调器：把每条问题作为独立任务提交 aiReviewExecutor 并发执行，
     * 等待全部完成后统一收尾。计数在 synchronized 块内做，前端进度语义与串行版一致。
     */
    private void runDeepReview(Long taskId, List<ScanIssue> issues) {
        Progress progress = jobs.get(taskId);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ScanIssue issue : issues) {
                if (!progress.isRunning()) break; // 预留中止能力
                futures.add(CompletableFuture.runAsync(() -> {
                    synchronized (progress) {
                        progress.setInflight(progress.getInflight() + 1);
                    }
                    try {
                        doEnhance(issue.getId(), false);
                        synchronized (progress) {
                            progress.setDone(progress.getDone() + 1);
                        }
                    } catch (Exception e) {
                        log.warn("AI 深度评审单条失败: issueId={}, err={}", issue.getId(), e.getMessage());
                        synchronized (progress) {
                            progress.setFailed(progress.getFailed() + 1);
                            // 首条失败原因留给前端终态展示（厂商超时/欠费等不必翻日志）
                            if (progress.getLastError() == null) {
                                String m = e.getMessage();
                                progress.setLastError(m != null && m.length() > 160 ? m.substring(0, 160) : m);
                            }
                        }
                    } finally {
                        synchronized (progress) {
                            progress.setInflight(progress.getInflight() - 1);
                        }
                    }
                }, aiReviewExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            // 全部落库后一次性失效报告缓存（原来每条清一次，纯浪费）
            try {
                reportService.purgeReportCache(taskId);
            } catch (Exception e) {
                log.warn("深度评审后失效报告缓存失败: taskId={}, err={}", taskId, e.getMessage());
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
            也不要建议修改与问题行无关的业务逻辑。禁止输出 JSON、禁止复述用户给出的元数据、禁止泛泛而谈（如"加强健壮性"）。
            输出务求精炼：问题分析不超过 2 句话，修复方案不超过 4 条，修复代码只含最小必要片段。""" ;

    /** 深度评审进度（内存态，序列化为 JSON 给前端轮询） */
    @Data
    public static class Progress {
        private boolean running;
        private int total;
        private int done;
        private int failed;
        private int skipped;
        /** 当前在飞的 LLM 调用数（前端轮询展示的实时并发；排队中 = total-done-failed-inflight） */
        private int inflight;
        /** 本轮首条失败原因（终态展示，避免用户翻日志） */
        private String lastError;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private String message;
    }
}
