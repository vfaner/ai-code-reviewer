package com.aicodereview.controller;

import com.aicodereview.dto.Result;
import com.aicodereview.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 报告控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * 生成报告
     *
     * @param taskId 任务ID
     * @param format 格式: pdf / html
     */
    @PostMapping("/generate")
    public Result<String> generate(
            @RequestParam Long taskId,
            @RequestParam(defaultValue = "pdf") String format
    ) {
        try {
            String path;
            if ("html".equalsIgnoreCase(format)) {
                path = reportService.generateHtmlReport(taskId);
            } else {
                path = reportService.generatePdfReport(taskId);
            }
            return Result.success(path);
        } catch (Exception e) {
            log.error("生成报告失败: taskId={}, format={}", taskId, format, e);
            return Result.error("生成报告失败: " + e.getMessage());
        }
    }

    /**
     * 下载报告
     *
     * @param taskId 任务ID
     * @param format 格式: pdf / html
     */
    @GetMapping("/{taskId}/download")
    public ResponseEntity<Resource> download(
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "pdf") String format
    ) {
        try {
            Path reportFile = reportService.getReportFile(taskId, format);
            if (!Files.exists(reportFile)) {
                // 报告不存在，先生成
                if ("html".equalsIgnoreCase(format)) {
                    reportService.generateHtmlReport(taskId);
                } else {
                    reportService.generatePdfReport(taskId);
                }
            }

            if (!Files.exists(reportFile)) {
                return ResponseEntity.notFound().build();
            }

            String fileName = "code-review-report-" + taskId + "." + format.toLowerCase();
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20");

            Resource resource = new FileSystemResource(reportFile.toFile());
            String contentType = "pdf".equalsIgnoreCase(format)
                    ? MediaType.APPLICATION_PDF_VALUE
                    : MediaType.TEXT_HTML_VALUE;

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encodedFileName)
                    .body(resource);

        } catch (Exception e) {
            log.error("下载报告失败: taskId={}, format={}", taskId, format, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 在线预览 HTML 报告
     */
    @GetMapping("/{taskId}/preview")
    public ResponseEntity<String> preview(@PathVariable Long taskId) {
        try {
            Path reportFile = reportService.getReportFile(taskId, "html");
            if (!Files.exists(reportFile)) {
                reportService.generateHtmlReport(taskId);
            }
            if (!Files.exists(reportFile)) {
                return ResponseEntity.notFound().build();
            }
            String html = Files.readString(reportFile, StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(html);
        } catch (Exception e) {
            log.error("预览报告失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body("报告生成失败: " + e.getMessage());
        }
    }
}
