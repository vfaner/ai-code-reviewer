package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.CiScanRecord;
import com.qqmu.jargus.entity.CiTriggerConfig;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.mapper.CiTriggerConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * CI Webhook 服务
 *
 * 支持：
 * 1. 通用 Webhook（通过 POST 传入 gitUrl + branch 或直接上传 ZIP）
 * 2. GitHub / GitLab / Gitee 格式的 push/MR 事件解析
 *
 * 鉴权见 {@link CiWebhookAuthService}，异步克隆/扫描见 {@link CiScanExecutor}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CiWebhookService {

    private final CiTriggerConfigMapper triggerConfigMapper;
    private final CiTriggerService ciTriggerService;
    private final ScanTaskService scanTaskService;
    private final CiScanExecutor ciScanExecutor;

    /**
     * 处理 Git push 事件 Webhook（调用方应已通过 {@link CiWebhookAuthService} 鉴权）
     *
     * @param triggerId 触发配置 ID
     * @param payload   payload Map（从 JSON 解析）
     * @param platform  平台: GITHUB / GITLAB / GITEE / GENERIC
     * @return CI 扫描记录；分支不匹配时返回 null
     */
    public CiScanRecord handleWebhook(Long triggerId, Map<String, Object> payload, String platform) {
        // 平台缺省兜底：避免下游 parseEvent/record.setPlatform 对 null 平台的隐式依赖
        if (platform == null || platform.isBlank()) {
            platform = "GENERIC";
        }
        CiTriggerConfig config = triggerConfigMapper.selectById(triggerId);
        if (config == null) {
            throw new RuntimeException("触发配置不存在");
        }
        if (!Boolean.TRUE.equals(config.getIsEnabled())) {
            throw new RuntimeException("触发配置未启用");
        }

        // 解析事件
        WebhookEvent event = parseEvent(payload, platform);
        if (event == null) {
            throw new RuntimeException("无法解析 webhook 事件");
        }

        // 仓库范围：配置后仅接受指定仓库的事件，避免同一 Webhook 地址被其他仓库误触发
        if (!matchesRepoScope(event.repoUrl, config.getRepoScope())) {
            log.info("仓库 {} 不在触发配置 {} 的仓库范围 {} 内, 跳过扫描",
                    event.repoUrl, triggerId, config.getRepoScope());
            return null;
        }

        // 分支过滤
        if (!matchesBranch(event.branch, config.getBranchFilter())) {
            log.info("分支 {} 不匹配过滤规则 {}, 跳过扫描", event.branch, config.getBranchFilter());
            return null;
        }

        // 创建 CI 记录
        CiScanRecord record = new CiScanRecord();
        record.setTriggerConfigId(triggerId);
        record.setPlatform(platform);
        record.setProjectUrl(event.repoUrl);
        record.setCommitId(event.commitId);
        record.setBranch(event.branch);
        record.setMrPrId(event.mrPrId);
        record.setMrPrTitle(event.mrPrTitle);
        record.setAuthor(event.author);
        record.setStatus("PENDING");

        ciTriggerService.createRecord(record);

        // 异步执行扫描（独立线程池，按记录隔离工作目录）
        try {
            ciScanExecutor.runGitScan(record.getId(), config, event.repoUrl, event.branch, event.commitId);
        } catch (RejectedExecutionException e) {
            log.warn("CI 扫描排队已满，拒绝执行: recordId={}", record.getId());
            ciTriggerService.updateRecordStatus(record.getId(), "FAILED", null);
            throw new RuntimeException("当前扫描任务过多（已达并发上限），请稍后重试");
        }

        return record;
    }

    /**
     * 通用 Webhook：直接上传 ZIP（调用方应已完成鉴权）
     */
    public CiScanRecord handleZipUpload(Long triggerId, byte[] zipData, String branch, String commitId) {
        CiTriggerConfig config = triggerConfigMapper.selectById(triggerId);
        if (config == null) {
            throw new RuntimeException("触发配置不存在");
        }
        if (!Boolean.TRUE.equals(config.getIsEnabled())) {
            throw new RuntimeException("触发配置未启用");
        }

        CiScanRecord record = new CiScanRecord();
        record.setTriggerConfigId(triggerId);
        record.setPlatform("GENERIC");
        record.setBranch(branch);
        record.setCommitId(commitId);
        record.setStatus("PENDING");
        ciTriggerService.createRecord(record);

        try {
            ScanTask task;
            try {
                task = scanTaskService.createFromZip(
                        zipData,
                        "CI-" + record.getId(),
                        null,
                        Boolean.TRUE.equals(config.getIncludeTestCode()),
                        Boolean.TRUE.equals(config.getEnableAiReview()),
                        Boolean.TRUE.equals(config.getSkipUnitTest()),
                        Boolean.TRUE.equals(config.getNotifyEnabled()),
                        config.getNotifyRecipientIds()
                );
            } catch (Exception e) {
                log.error("CI ZIP 解压/建任务失败: recordId={}", record.getId(), e);
                ciTriggerService.updateRecordStatus(record.getId(), "FAILED", null);
                throw new RuntimeException("ZIP 处理失败: " + e.getMessage(), e);
            }

            record.setTaskId(task.getId());
            record.setStatus("RUNNING");
            ciTriggerService.attachTask(record.getId(), task.getId());
            ciTriggerService.updateRecordStatus(record.getId(), "RUNNING", null);

            // 异步执行（受扫描线程池并发上限保护，队列满时拒绝）
            try {
                scanTaskService.executeScanAsync(task.getId());
            } catch (RejectedExecutionException e) {
                scanTaskService.updateTaskStatus(task.getId(), "FAILED", "扫描排队已满，请稍后重试");
                ciTriggerService.updateRecordStatus(record.getId(), "FAILED", null);
                throw new RuntimeException("当前扫描任务过多（已达并发上限），请稍后重试");
            }

            // 更新结果 URL
            ciTriggerService.updateRecordStatus(record.getId(), null,
                    "/scan/result/" + task.getId());

        } catch (RuntimeException e) {
            throw e;
        }

        return record;
    }

    // ==================== 事件解析 ====================

    private WebhookEvent parseEvent(Map<String, Object> payload, String platform) {
        if (payload == null) return null;

        WebhookEvent event = new WebhookEvent();
        try {
            switch (platform.toUpperCase()) {
                case "GITHUB":
                    parseGitHub(payload, event);
                    break;
                case "GITLAB":
                    parseGitLab(payload, event);
                    break;
                case "GITEE":
                    parseGitee(payload, event);
                    break;
                case "GENERIC":
                default:
                    parseGeneric(payload, event);
                    break;
            }
        } catch (Exception e) {
            log.warn("解析 webhook 事件失败: platform={}, err={}", platform, e.getMessage());
            return null;
        }

        return event.repoUrl != null ? event : null;
    }

    @SuppressWarnings("unchecked")
    private void parseGitHub(Map<String, Object> payload, WebhookEvent event) {
        Map<String, Object> repo = (Map<String, Object>) payload.get("repository");
        if (repo != null) {
            event.repoUrl = (String) repo.get("clone_url");
        }
        String ref = (String) payload.get("ref");
        if (ref != null && ref.startsWith("refs/heads/")) {
            event.branch = ref.substring("refs/heads/".length());
        }
        Map<String, Object> headCommit = (Map<String, Object>) payload.get("head_commit");
        if (headCommit != null) {
            event.commitId = (String) headCommit.get("id");
            Map<String, Object> author = (Map<String, Object>) headCommit.get("author");
            if (author != null) {
                event.author = (String) author.get("username");
                if (event.author == null) event.author = (String) author.get("name");
            }
        }
        // Pull Request 事件
        Map<String, Object> pr = (Map<String, Object>) payload.get("pull_request");
        if (pr != null) {
            event.mrPrId = String.valueOf(pr.get("number"));
            event.mrPrTitle = (String) pr.get("title");
            Map<String, Object> head = (Map<String, Object>) pr.get("head");
            if (head != null) {
                event.branch = (String) head.get("ref");
                event.commitId = (String) head.get("sha");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void parseGitLab(Map<String, Object> payload, WebhookEvent event) {
        Map<String, Object> proj = (Map<String, Object>) payload.get("project");
        if (proj != null) {
            event.repoUrl = (String) proj.get("git_http_url");
        }
        event.branch = (String) payload.get("ref");
        if (event.branch != null && event.branch.startsWith("refs/heads/")) {
            event.branch = event.branch.substring("refs/heads/".length());
        }
        Object commitId = payload.get("checkout_sha");
        if (commitId == null) commitId = payload.get("after");
        if (commitId != null) event.commitId = commitId.toString();

        Map<String, Object> user = (Map<String, Object>) payload.get("user");
        if (user != null) event.author = (String) user.get("username");

        // MR 事件
        Map<String, Object> attrs = (Map<String, Object>) payload.get("object_attributes");
        if (attrs != null && attrs.get("iid") != null) {
            event.mrPrId = String.valueOf(attrs.get("iid"));
            event.mrPrTitle = (String) attrs.get("title");
            event.branch = (String) attrs.get("source_branch");
        }
    }

    @SuppressWarnings("unchecked")
    private void parseGitee(Map<String, Object> payload, WebhookEvent event) {
        Map<String, Object> proj = (Map<String, Object>) payload.get("project");
        if (proj != null) {
            event.repoUrl = (String) proj.get("git_http_url");
        }
        String ref = (String) payload.get("ref");
        if (ref != null && ref.startsWith("refs/heads/")) {
            event.branch = ref.substring("refs/heads/".length());
        }
        String after = (String) payload.get("after");
        if (after != null) event.commitId = after;

        Map<String, Object> pr = (Map<String, Object>) payload.get("pull_request");
        if (pr != null) {
            event.mrPrId = String.valueOf(pr.get("number"));
            event.mrPrTitle = (String) pr.get("title");
        }
    }

    @SuppressWarnings("unchecked")
    private void parseGeneric(Map<String, Object> payload, WebhookEvent event) {
        event.repoUrl = (String) payload.get("repoUrl");
        event.branch = (String) payload.getOrDefault("branch", "main");
        event.commitId = (String) payload.get("commitId");
        event.author = (String) payload.get("author");
        event.mrPrId = (String) payload.get("mrId");
        event.mrPrTitle = (String) payload.get("mrTitle");
    }

    // ==================== 工具方法 ====================

    private boolean matchesBranch(String branch, String filter) {
        if (branch == null) return true;
        if (filter == null || filter.isEmpty()) return true;
        String[] patterns = filter.split(",");
        for (String pattern : patterns) {
            String p = pattern.trim();
            if (p.isEmpty()) continue;
            if (globMatch(p, branch)) return true;
        }
        return false;
    }

    /** 仓库范围匹配：从克隆地址中取出路径部分与配置的 owner/repo 比较（忽略大小写，兼容子组后缀与 .git 后缀） */
    private boolean matchesRepoScope(String repoUrl, String scope) {
        if (scope == null || scope.trim().isEmpty()) return true;
        if (repoUrl == null || repoUrl.trim().isEmpty()) return false;
        String path = repoUrl.trim()
                .replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "")
                .replaceFirst("^git@[^:/]+:", "")
                .replaceFirst("^[^/]+/", "")
                .replaceAll("/+$", "")
                .replaceAll("(?i)\\.git$", "");
        String s = scope.trim()
                .replaceAll("/+$", "")
                .replaceAll("(?i)\\.git$", "");
        return path.equalsIgnoreCase(s)
                || path.toLowerCase().endsWith("/" + s.toLowerCase());
    }

    private boolean globMatch(String pattern, String text) {
        // 简化 glob: ** 匹配任意路径, * 匹配单段
        String regex = pattern
                .replace(".", "\\.")
                .replace("**/", "(.*/)?")
                .replace("*", "[^/]*")
                .replace("?", "[^/]");
        return text.matches(regex);
    }

    // ==================== 内部类 ====================

    private static class WebhookEvent {
        String repoUrl;
        String branch;
        String commitId;
        String author;
        String mrPrId;
        String mrPrTitle;
    }
}
