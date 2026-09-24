package com.qqmu.jargus.service;

import com.qqmu.jargus.dto.QualityGateResult;
import com.qqmu.jargus.entity.CiScanRecord;
import com.qqmu.jargus.entity.CiTriggerConfig;
import com.qqmu.jargus.entity.ScanIssue;
import com.qqmu.jargus.mapper.CiScanRecordMapper;
import com.qqmu.jargus.mapper.CiTriggerConfigMapper;
import com.qqmu.jargus.mapper.ScanIssueMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * CI 回调服务：扫描完成后回写代码平台
 *
 * 1. **Commit Status**（合并阻断手段）：按质量门禁结果提交 success/failure/error 状态，
 *    在平台侧配置分支保护「状态检查必须通过」后，不达标的提交将无法合并。
 * 2. **MR/PR 自动回评**（触发配置勾选 autoComment 且事件携带 MR/PR 号时）：
 *    回评评分、五级问题计数、技术债与 Top 问题清单。
 *
 * 平台 API：
 * - GitHub：api.github.com（SaaS）或 {platformUrl}/api/v3（Enterprise）
 * - GitLab：{platformUrl}/api/v4
 * - Gitee：{platformUrl}/api/v5（token 放请求体 access_token）
 *
 * 回调使用触发配置中的「仓库令牌」（AES 解密）；未配置令牌则跳过。
 * 全程只记日志、不抛异常，绝不影响扫描主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CiCallbackService {

    private final CiScanRecordMapper scanRecordMapper;
    private final CiTriggerConfigMapper triggerConfigMapper;
    private final QualityGateService qualityGateService;
    private final ScanIssueMapper scanIssueMapper;
    private final ObjectMapper objectMapper;

    @Value("${app.webhook-base-url:http://localhost:8080}")
    private String webhookBaseUrl;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String CONTEXT_NAME = "jargus";

    /**
     * 扫描任务结束后回调（SUCCESS / FAILED），按 taskId 找 CI 记录，普通任务无记录直接返回
     */
    public void onScanCompleted(Long taskId, String status) {
        try {
            if (taskId == null) {
                return;
            }
            CiScanRecord record = scanRecordMapper.selectOne(
                    new QueryWrapper<CiScanRecord>().eq("task_id", taskId)
                            .orderByDesc("id").last("LIMIT 1"));
            if (record == null) {
                return;
            }
            doCallback(record, status);
        } catch (Exception e) {
            log.warn("CI 回调失败: taskId={}, err={}", taskId, e.getMessage());
        }
    }

    /**
     * 记录级失败回调（克隆失败等尚未生成扫描任务的场景）
     */
    public void onRecordFailed(Long recordId) {
        try {
            if (recordId == null) {
                return;
            }
            CiScanRecord record = scanRecordMapper.selectById(recordId);
            if (record != null) {
                doCallback(record, "FAILED");
            }
        } catch (Exception e) {
            log.warn("CI 失败回调异常: recordId={}, err={}", recordId, e.getMessage());
        }
    }

    private void doCallback(CiScanRecord record, String status) {
        CiTriggerConfig config = record.getTriggerConfigId() != null
                ? triggerConfigMapper.selectById(record.getTriggerConfigId()) : null;
        if (config == null) {
            return;
        }
        String platform = config.getPlatform() != null ? config.getPlatform().toUpperCase() : "GENERIC";
        if (!platform.equals("GITHUB") && !platform.equals("GITLAB") && !platform.equals("GITEE")) {
            log.debug("平台 {} 无状态回写 API，跳过 CI 回调: recordId={}", platform, record.getId());
            return;
        }
        String token = CiTriggerService.decryptStored(config.getRepoToken());
        if (token == null || token.isBlank()) {
            log.info("触发配置 {} 未设置仓库令牌，跳过 commit status / MR 回评（平台侧无法鉴权）",
                    config.getConfigName());
            return;
        }
        String ownerRepo = extractOwnerRepo(record.getProjectUrl());
        if (ownerRepo == null) {
            log.warn("无法从项目地址解析 owner/repo，跳过 CI 回调: {}", record.getProjectUrl());
            return;
        }

        QualityGateResult gate = evaluateGateSafely(record, status);

        String commitId = sanitizeCommitId(record.getCommitId());
        if (commitId != null) {
            postCommitStatus(platform, config, token, ownerRepo, commitId, status, gate, record);
        } else {
            log.debug("CI 记录 {} 无有效 commitId，跳过 commit status", record.getId());
        }

        String mrPrId = sanitizeMrPrId(record.getMrPrId());
        if (Boolean.TRUE.equals(config.getAutoComment()) && mrPrId != null) {
            postMrComment(platform, config, token, ownerRepo, mrPrId, status, gate, record);
        }
    }

    /** 扫描成功且带任务时计算质量门禁；计算失败不阻断回调，按无门禁处理 */
    private QualityGateResult evaluateGateSafely(CiScanRecord record, String status) {
        if (!"SUCCESS".equals(status) || record.getTaskId() == null) {
            return null;
        }
        try {
            return qualityGateService.evaluateTask(record.getTaskId());
        } catch (Exception e) {
            log.warn("CI 回调计算质量门禁失败: taskId={}, err={}", record.getTaskId(), e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- commit status

    private void postCommitStatus(String platform, CiTriggerConfig config, String token,
                                  String ownerRepo, String commitId, String status,
                                  QualityGateResult gate, CiScanRecord record) {
        String description;
        String githubStyleState;
        if (!"SUCCESS".equals(status)) {
            githubStyleState = "error";
            description = "百目评审：扫描执行失败";
        } else if (gate == null) {
            githubStyleState = "error";
            description = "百目评审：无法计算质量门禁";
        } else if (gate.isPassed()) {
            githubStyleState = "success";
            description = String.format("百目评审通过：评分 %d · 阻断 %d · 严重 %d · 主要 %d · 次要 %d · 提示 %d",
                    gate.getScore(), gate.getBlockerCount(), gate.getCriticalCount(),
                    gate.getMajorCount(), gate.getMinorCount(), gate.getInfoCount());
        } else {
            githubStyleState = "failure";
            description = String.format("百目评审不通过：评分 %d · 阻断 %d · 严重 %d · 主要 %d · 次要 %d · 提示 %d",
                    gate.getScore(), gate.getBlockerCount(), gate.getCriticalCount(),
                    gate.getMajorCount(), gate.getMinorCount(), gate.getInfoCount());
        }
        description = truncate(description, 140);
        String targetUrl = buildTargetUrl(record);

        try {
            switch (platform) {
                case "GITHUB" -> {
                    String url = apiBase(platform, config.getPlatformUrl())
                            + "/repos/" + encodePath(ownerRepo) + "/statuses/" + commitId;
                    postJson(url, Map.of(
                            "state", githubStyleState,
                            "context", CONTEXT_NAME,
                            "description", description,
                            "target_url", targetUrl
                    ), githubHeaders(token), "GitHub commit status");
                }
                case "GITLAB" -> {
                    // GitLab 状态枚举：pending/running/success/failed/canceled
                    String state = switch (githubStyleState) {
                        case "success" -> "success";
                        default -> "failed";
                    };
                    String url = apiBase(platform, config.getPlatformUrl())
                            + "/projects/" + encodeProjectPath(ownerRepo) + "/statuses/" + commitId;
                    postJson(url, Map.of(
                            "state", state,
                            "name", CONTEXT_NAME,
                            "description", description,
                            "target_url", targetUrl
                    ), gitlabHeaders(token), "GitLab commit status");
                }
                case "GITEE" -> {
                    String url = apiBase(platform, config.getPlatformUrl())
                            + "/repos/" + encodePath(ownerRepo) + "/statuses/" + commitId;
                    postJson(url, Map.of(
                            "access_token", token,
                            "state", githubStyleState,
                            "context", CONTEXT_NAME,
                            "description", description,
                            "target_url", targetUrl
                    ), plainJsonHeaders(), "Gitee commit status");
                }
                default -> { }
            }
        } catch (Exception e) {
            log.warn("提交 commit status 失败: platform={}, err={}", platform, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- MR/PR 回评

    private void postMrComment(String platform, CiTriggerConfig config, String token,
                               String ownerRepo, String mrPrId, String status,
                               QualityGateResult gate, CiScanRecord record) {
        try {
            String body = buildCommentMarkdown(status, gate, record);
            switch (platform) {
                case "GITHUB" -> {
                    String url = apiBase(platform, config.getPlatformUrl())
                            + "/repos/" + encodePath(ownerRepo) + "/issues/" + mrPrId + "/comments";
                    postJson(url, Map.of("body", body), githubHeaders(token), "GitHub PR 回评");
                }
                case "GITLAB" -> {
                    String url = apiBase(platform, config.getPlatformUrl())
                            + "/projects/" + encodeProjectPath(ownerRepo)
                            + "/merge_requests/" + mrPrId + "/notes";
                    postJson(url, Map.of("body", body), gitlabHeaders(token), "GitLab MR 回评");
                }
                case "GITEE" -> {
                    String url = apiBase(platform, config.getPlatformUrl())
                            + "/repos/" + encodePath(ownerRepo) + "/pulls/" + mrPrId + "/comments";
                    postJson(url, Map.of("access_token", token, "body", body),
                            plainJsonHeaders(), "Gitee PR 回评");
                }
                default -> { }
            }
        } catch (Exception e) {
            log.warn("MR/PR 回评失败: platform={}, err={}", platform, e.getMessage());
        }
    }

    private String buildCommentMarkdown(String status, QualityGateResult gate, CiScanRecord record) {
        StringBuilder md = new StringBuilder();
        md.append("## 百目代码评审结果 ");
        if (!"SUCCESS".equals(status)) {
            md.append("❌\n\n扫描执行失败，请查看流水线日志。\n");
            return md.toString();
        }
        if (gate == null) {
            md.append("⚠️\n\n扫描完成，但质量门禁计算失败。\n");
            return md.toString();
        }
        md.append(gate.isPassed() ? "✅\n" : "❌\n").append('\n');
        md.append("| 指标 | 结果 |\n|---|---|\n");
        md.append("| 质量评分 | **").append(gate.getScore()).append("**（")
                .append(levelText(gate.getLevel())).append("） |\n");
        md.append("| 质量门禁 | ").append(gate.isPassed() ? "✅ 通过" : "❌ 不通过").append(" |\n");
        md.append("| 阻断 / 严重 / 主要 / 次要 / 提示 | ")
                .append(gate.getBlockerCount()).append(" / ")
                .append(gate.getCriticalCount()).append(" / ")
                .append(gate.getMajorCount()).append(" / ")
                .append(gate.getMinorCount()).append(" / ")
                .append(gate.getInfoCount()).append(" |\n");
        if (gate.getDebtMinutes() > 0) {
            md.append("| 预估技术债 | ").append(gate.getDebtText()).append(" |\n");
        }

        List<ScanIssue> top = topIssues(record.getTaskId());
        if (!top.isEmpty()) {
            md.append("\n**主要问题（Top ").append(top.size()).append("）：**\n\n");
            md.append("| 等级 | 规则 | 位置 | 标题 |\n|---|---|---|---|\n");
            for (ScanIssue issue : top) {
                md.append("| ").append(levelBadge(issue.getIssueLevel()))
                        .append(" | ").append(escapeMd(issue.getRuleCode()))
                        .append(" | ").append(escapeMd(shortPath(issue.getFilePath())))
                        .append(':').append(escapeMd(issue.getLineLabel() != null ? issue.getLineLabel() : "0"))
                        .append(" | ").append(escapeMd(truncate(issue.getTitle(), 60)))
                        .append(" |\n");
            }
        }
        md.append("\n[查看完整报告](").append(buildTargetUrl(record)).append(")\n");
        md.append("\n<sub>由 百目 JArgus 自动生成</sub>");
        return md.toString();
    }

    private List<ScanIssue> topIssues(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        try {
            List<ScanIssue> issues = scanIssueMapper.selectList(
                    new QueryWrapper<ScanIssue>()
                            .eq("task_id", taskId)
                            .eq("is_ignored", false)
                            .last("LIMIT 300"));
            issues.sort(Comparator.comparingInt((ScanIssue i) -> levelOrder(i.getIssueLevel()))
                    .thenComparing(i -> i.getFilePath() == null ? "" : i.getFilePath()));
            return issues.size() > 5 ? issues.subList(0, 5) : issues;
        } catch (Exception e) {
            log.debug("读取 Top 问题失败: {}", e.getMessage());
            return List.of();
        }
    }

    private int levelOrder(String level) {
        return switch (level == null ? "" : level) {
            case "BLOCKER" -> 0;
            case "CRITICAL" -> 1;
            case "MAJOR" -> 2;
            case "MINOR" -> 3;
            default -> 4;
        };
    }

    private String levelBadge(String level) {
        return switch (level == null ? "" : level) {
            case "BLOCKER" -> "🟣 阻断";
            case "CRITICAL" -> "🔴 严重";
            case "MAJOR" -> "🟠 主要";
            case "MINOR" -> "🔵 次要";
            default -> "⚪ 提示";
        };
    }

    private String levelText(String level) {
        return switch (level == null ? "" : level) {
            case "EXCELLENT" -> "优秀";
            case "GOOD" -> "良好";
            case "FAIR" -> "一般";
            default -> "较差";
        };
    }

    // ---------------------------------------------------------------- HTTP 基础

    private void postJson(String url, Map<String, ?> body, Map<String, String> headers, String what) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            headers.forEach(builder::header);
            HttpResponse<String> response = HTTP_CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("{} 成功: {} -> {}", what, url, response.statusCode());
            } else {
                log.warn("{} 失败: {} -> {} {}", what, url, response.statusCode(),
                        truncate(response.body(), 200));
            }
        } catch (Exception e) {
            log.warn("{} 请求异常: {} - {}", what, url, e.getMessage());
        }
    }

    private Map<String, String> githubHeaders(String token) {
        return Map.of(
                "Authorization", "Bearer " + token,
                "Accept", "application/vnd.github+json",
                "Content-Type", "application/json",
                "User-Agent", CONTEXT_NAME);
    }

    private Map<String, String> gitlabHeaders(String token) {
        return Map.of(
                "PRIVATE-TOKEN", token,
                "Content-Type", "application/json",
                "User-Agent", CONTEXT_NAME);
    }

    private Map<String, String> plainJsonHeaders() {
        return Map.of("Content-Type", "application/json", "User-Agent", CONTEXT_NAME);
    }

    /**
     * 平台 API 基地址：
     * GitHub SaaS → https://api.github.com；GitHub Enterprise → {platformUrl}/api/v3；
     * GitLab → {platformUrl}/api/v4；Gitee → {platformUrl}/api/v5
     */
    private String apiBase(String platform, String platformUrl) {
        String base = platformUrl != null ? platformUrl.trim() : "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            base = CiTriggerService.defaultPlatformUrl(platform);
        }
        String host = "";
        try {
            String parsedHost = URI.create(base).getHost();
            // 无主机段的地址（如 "github"）getHost() 返回 null 且不抛异常，归一为空串避免 null 流入下方比较
            host = parsedHost != null ? parsedHost : "";
        } catch (IllegalArgumentException e) {
            // 平台地址配置非法：降级按自建平台拼接 API 地址，记日志避免静默吞掉难以排查
            log.warn("平台地址不是合法 URI，API 基地址回退按自建平台拼接: {}", base);
        }
        return switch (platform) {
            case "GITHUB" -> "github.com".equalsIgnoreCase(host)
                    ? "https://api.github.com" : base + "/api/v3";
            case "GITLAB" -> base + "/api/v4";
            case "GITEE" -> base + "/api/v5";
            default -> base;
        };
    }

    /**
     * 从仓库地址提取 owner/repo：去协议、去 git@host:、去主机段、去 .git 后缀
     */
    static String extractOwnerRepo(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return null;
        }
        String path = repoUrl.trim()
                .replaceAll("^[a-zA-Z][a-zA-Z0-9+.-]*://", "")
                .replaceAll("^git@[^:/]+:", "")
                .replaceAll("^[^/]+/", "")
                .replaceAll("/+$", "")
                .replaceAll("(?i)\\.git$", "");
        return path.isEmpty() || !path.contains("/") ? null : path;
    }

    private String encodePath(String ownerRepo) {
        String[] parts = ownerRepo.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** GitLab 项目路径需整体 URL 编码（/ → %2F） */
    private String encodeProjectPath(String ownerRepo) {
        return URLEncoder.encode(ownerRepo, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String buildTargetUrl(CiScanRecord record) {
        String resultUrl = record.getResultUrl();
        if (resultUrl == null || resultUrl.isBlank()) {
            resultUrl = record.getTaskId() != null ? "/scan/result/" + record.getTaskId() : "/ci";
        }
        return webhookBaseUrl + resultUrl;
    }

    private String sanitizeCommitId(String commitId) {
        if (commitId == null) {
            return null;
        }
        String c = commitId.trim();
        return c.matches("[0-9a-fA-F]{4,64}") ? c : null;
    }

    private String sanitizeMrPrId(String mrPrId) {
        if (mrPrId == null) {
            return null;
        }
        String m = mrPrId.trim();
        return m.matches("\\d{1,10}") ? m : null;
    }

    private String shortPath(String filePath) {
        if (filePath == null) {
            return "-";
        }
        int idx = filePath.lastIndexOf('/');
        return idx >= 0 && filePath.length() > 40 ? "..." + filePath.substring(Math.max(0, filePath.length() - 37)) : filePath;
    }

    private String escapeMd(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
