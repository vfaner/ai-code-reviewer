package com.qqmu.jargus.controller;

import com.qqmu.jargus.dto.QualityGateResult;
import com.qqmu.jargus.dto.Result;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.env.ProjectInfo;
import com.qqmu.jargus.service.AiSuggestionService;
import com.qqmu.jargus.service.ProjectEnvService;
import com.qqmu.jargus.service.QualityGateService;
import com.qqmu.jargus.service.ScanTaskService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * 扫描任务控制器
 */
@RestController
@RequestMapping("/api/scans")
@RequiredArgsConstructor
public class ScanTaskController {

    private final ScanTaskService scanTaskService;
    private final ProjectEnvService projectEnvService;
    private final QualityGateService qualityGateService;
    private final AiSuggestionService aiSuggestionService;

    /**
     * 分页获取扫描任务列表
     */
    @GetMapping
    public Result<IPage<ScanTask>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword
    ) {
        return Result.success(scanTaskService.listTasks(page, size, keyword));
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/{id}")
    public Result<ScanTask> getById(@PathVariable Long id) {
        return Result.success(scanTaskService.getById(id));
    }

    /**
     * 从粘贴的代码创建扫描任务
     */
    @PostMapping("/paste")
    public Result<ScanTask> createFromPaste(@RequestBody Map<String, Object> body) {
        String code = (String) body.getOrDefault("code", "");
        String taskName = (String) body.getOrDefault("taskName", "");
        String projectName = (String) body.getOrDefault("projectName", "");
        boolean includeTest = Boolean.TRUE.equals(body.getOrDefault("includeTestCode", false));
        boolean enableAi = Boolean.TRUE.equals(body.getOrDefault("enableAiReview", true));
        boolean notifyEnabled = Boolean.TRUE.equals(body.getOrDefault("notifyEnabled", false));
        String notifyRecipientIds = body.get("notifyRecipientIds") != null
                ? body.get("notifyRecipientIds").toString() : null;

        ScanTask task = scanTaskService.createFromPaste(code, taskName, projectName, includeTest, enableAi,
                notifyEnabled, notifyRecipientIds);
        if (!submitScan(task)) return Result.error("当前扫描任务过多（已达并发上限），请稍后重试");
        return Result.success(task);
    }

    /**
     * 从 ZIP 文件创建扫描任务
     */
    @PostMapping("/upload")
    public Result<ScanTask> createFromZip(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String taskName,
            @RequestParam(required = false) String projectName,
            @RequestParam(defaultValue = "false") boolean includeTestCode,
            @RequestParam(defaultValue = "true") boolean enableAiReview,
            @RequestParam(defaultValue = "true") boolean skipUnitTest,
            @RequestParam(defaultValue = "false") boolean notifyEnabled,
            @RequestParam(required = false) String notifyRecipientIds
    ) throws IOException {
        byte[] zipData = file.getBytes();
        ScanTask task = scanTaskService.createFromZip(
                zipData,
                taskName != null ? taskName : file.getOriginalFilename(),
                projectName,
                includeTestCode,
                enableAiReview,
                skipUnitTest,
                notifyEnabled,
                notifyRecipientIds
        );
        if (!submitScan(task)) return Result.error("当前扫描任务过多（已达并发上限），请稍后重试");
        return Result.success(task);
    }

    /**
     * 删除扫描任务：连同问题、报告缓存与本地留存的代码快照一起清除
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        scanTaskService.deleteTask(id);
        return Result.success(null);
    }

    /**
     * 重新上传代码并扫描既有任务：替换代码快照后重跑，任务名/项目名不变
     */
    @PostMapping("/{id}/rescan")
    public Result<ScanTask> rescan(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        ScanTask task = scanTaskService.rescanFromZip(id, file.getBytes());
        if (!submitScan(task)) return Result.error("当前扫描任务过多（已达并发上限），请稍后重试");
        return Result.success(task);
    }

    /**
     * AI 深度评审：为任务下所有问题批量生成 AI 增强修复建议（后台异步）
     * 可选 body {"levels":"BLOCKER,CRITICAL"} 按严重度过滤增强范围
     */
    @PostMapping("/{id}/ai-deep-review")
    public Result<AiSuggestionService.Progress> startDeepReview(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Object lv = body == null ? null : body.get("levels");
        return Result.success(aiSuggestionService.startDeepReview(id, lv == null ? null : String.valueOf(lv)));
    }

    /**
     * AI 深度评审进度（VIEWER 可查询）
     */
    @GetMapping("/{id}/ai-deep-review")
    public Result<AiSuggestionService.Progress> getDeepReviewStatus(@PathVariable Long id) {
        return Result.success(aiSuggestionService.getProgress(id));
    }

    /**
     * 提交异步扫描；线程池/队列已满时把任务置为失败
     */
    private boolean submitScan(ScanTask task) {
        try {
            scanTaskService.executeScanAsync(task.getId());
            return true;
        } catch (RejectedExecutionException e) {
            scanTaskService.updateTaskStatus(task.getId(), "FAILED",
                    "扫描排队已满（达到并发上限），请稍后重试");
            return false;
        }
    }

    /**
     * 获取任务的环境信息
     */
    @GetMapping("/{id}/env")
    public Result<ProjectInfo> getEnvInfo(@PathVariable Long id) {
        ScanTask task = scanTaskService.getById(id);
        if (task == null) {
            return Result.error("任务不存在");
        }
        ProjectInfo info = projectEnvService.analyzeProject(Paths.get(task.getSnapshotPath()));
        return Result.success(info);
    }

    /**
     * 获取任务的质量评分和质量门禁结果
     */
    @GetMapping("/{id}/quality")
    public Result<QualityGateResult> getQuality(@PathVariable Long id) {
        return Result.success(qualityGateService.evaluateTask(id));
    }

    /**
     * 获取任务状态
     */
    @GetMapping("/{id}/status")
    public Result<Map<String, Object>> getStatus(@PathVariable Long id) {
        ScanTask task = scanTaskService.getById(id);
        if (task == null) {
            return Result.error("任务不存在");
        }
        Map<String, Object> status = new HashMap<>();
        status.put("id", task.getId());
        status.put("status", task.getStatus());
        status.put("totalIssues", task.getTotalIssues() != null ? task.getTotalIssues() : 0);
        status.put("blockerCount", task.getBlockerCount() != null ? task.getBlockerCount() : 0);
        status.put("criticalCount", task.getCriticalCount() != null ? task.getCriticalCount() : 0);
        status.put("majorCount", task.getMajorCount() != null ? task.getMajorCount() : 0);
        status.put("minorCount", task.getMinorCount() != null ? task.getMinorCount() : 0);
        status.put("infoCount", task.getInfoCount() != null ? task.getInfoCount() : 0);
        status.put("totalFiles", task.getTotalFiles() != null ? task.getTotalFiles() : 0);
        status.put("totalLines", task.getTotalLines() != null ? task.getTotalLines() : 0);
        status.put("startedAt", task.getStartedAt());
        status.put("completedAt", task.getCompletedAt());
        status.put("durationSeconds", task.getDurationSeconds());
        status.put("errorMessage", task.getErrorMessage());
        return Result.success(status);
    }
}
