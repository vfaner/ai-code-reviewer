package com.aicodereview.controller;

import com.aicodereview.dto.FileContext;
import com.aicodereview.dto.Result;
import com.aicodereview.entity.ScanIssue;
import com.aicodereview.service.AiSuggestionService;
import com.aicodereview.service.ScanIssueService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 扫描问题控制器
 */
@RestController
@RequestMapping("/api/issues")
@RequiredArgsConstructor
public class ScanIssueController {

    private final ScanIssueService scanIssueService;
    private final AiSuggestionService aiSuggestionService;

    /**
     * 分页查询问题列表
     */
    @GetMapping
    public Result<IPage<ScanIssue>> list(
            @RequestParam Long taskId,
            @RequestParam(required = false, defaultValue = "ALL") String level,
            @RequestParam(required = false, defaultValue = "ALL") String checkerType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean includeIgnored,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return Result.success(scanIssueService.listIssues(
                taskId, level, checkerType, keyword, includeIgnored, page, size));
    }

    /**
     * 获取问题详情
     */
    @GetMapping("/{id}")
    public Result<ScanIssue> getById(@PathVariable Long id) {
        return Result.success(scanIssueService.getById(id));
    }

    /**
     * 获取任务的问题统计
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats(@RequestParam Long taskId) {
        return Result.success(scanIssueService.getStats(taskId));
    }

    /**
     * 获取问题所在文件的完整代码上下文（带行号和问题标记）
     */
    @GetMapping("/{id}/context")
    public Result<FileContext> getContext(
            @PathVariable Long id,
            @RequestParam(defaultValue = "10") int contextLines
    ) {
        FileContext context = scanIssueService.getIssueFileContext(id, contextLines);
        if (context == null) {
            return Result.error("问题不存在");
        }
        return Result.success(context);
    }

    /**
     * 忽略问题
     */
    @PostMapping("/{id}/ignore")
    public Result<Boolean> ignore(
            @PathVariable Long id,
            @RequestBody Map<String, String> body
    ) {
        String ignoreType = body.getOrDefault("ignoreType", "SINGLE");
        String reason = body.getOrDefault("reason", "");
        return Result.success(scanIssueService.ignoreIssue(id, ignoreType, reason));
    }

    /**
     * AI 是否可用（前端决定是否展示"AI 增强建议/深度评审"入口，VIEWER 可查）
     */
    @GetMapping("/ai/available")
    public Result<Map<String, Object>> getAiAvailability() {
        return Result.success(Map.of("available", aiSuggestionService.isAvailable()));
    }

    /**
     * 单条问题的 AI 增强修复建议（结合源码上下文，管理员操作，消耗 Token）
     */
    @PostMapping("/{id}/ai-suggestion")
    public Result<String> enhanceSuggestion(@PathVariable Long id) {
        return Result.success(aiSuggestionService.enhanceIssue(id));
    }
}
