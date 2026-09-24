package com.qqmu.jargus.service;

import com.qqmu.jargus.dto.QualityGateResult;
import com.qqmu.jargus.entity.MailRecipient;
import com.qqmu.jargus.entity.MailSender;
import com.qqmu.jargus.entity.ScanIssue;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.mapper.ScanTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.internet.MimeMessage;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 扫描完成邮件通知服务
 *
 * 钩子位于 ScanTaskService.executeScanAsync 的 ciCallbackService.onScanCompleted 之后，
 * 内部吞掉全部异常——邮件发送绝不影响扫描结果与 CI 回写。
 *
 * 邮件形态：HTML 摘要正文（mail-notify 模板，内联样式）+ 完整 PDF 报告附件（附件尽力，
 * 生成失败则无附件照发）。发送结果写 scan_task.mail_status（SENT/FAILED）。
 * 依赖 ScanTaskMapper 而非 ScanTaskService，避免 Bean 循环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailNotifyService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScanTaskMapper scanTaskMapper;
    private final MailSenderConfigService mailSenderConfigService;
    private final MailRecipientService mailRecipientService;
    private final ReportService reportService;
    private final QualityGateService qualityGateService;
    private final ScanIssueService scanIssueService;
    private final TemplateEngine templateEngine;
    private final MessageSource messageSource;

    /**
     * 扫描完成回调：按任务快照的发信配置发送通知邮件。
     * 任何异常均被吞掉并尽力落 mail_status=FAILED。
     */
    public void onScanCompleted(Long taskId, String status) {
        try {
            ScanTask task = scanTaskMapper.selectById(taskId);
            if (task == null) return;
            if (!Boolean.TRUE.equals(task.getNotifyEnabled())) return;
            if (task.getNotifyRecipientIds() == null || task.getNotifyRecipientIds().isBlank()) {
                log.warn("任务开启了邮件通知但未选择收件人，跳过发信: taskId={}", taskId);
                return;
            }
            MailSender sender = mailSenderConfigService.getEnabledSender();
            if (sender == null) {
                log.warn("无启用的发件配置，跳过通知邮件: taskId={}", taskId);
                return;
            }
            List<MailRecipient> recipients = mailRecipientService.resolveByIds(task.getNotifyRecipientIds());
            if (recipients.isEmpty()) {
                log.warn("通知收件人均已删除，跳过发信: taskId={}, ids={}", taskId, task.getNotifyRecipientIds());
                return;
            }

            boolean failed = !"SUCCESS".equals(status);
            QualityGateResult quality = null;
            List<ScanIssue> topIssues = Collections.emptyList();
            try {
                quality = qualityGateService.evaluateTask(taskId);
            } catch (Exception e) {
                log.warn("通知邮件质量门禁评估失败（继续发送）: taskId={}", taskId, e);
            }
            try {
                topIssues = scanIssueService
                        .listIssues(taskId, null, null, null, false, 1, 10)
                        .getRecords();
            } catch (Exception e) {
                log.warn("通知邮件问题列表加载失败（继续发送）: taskId={}", taskId, e);
            }

            Context ctx = new Context();
            ctx.setLocale(Locale.SIMPLIFIED_CHINESE);
            ctx.setVariable("task", task);
            ctx.setVariable("quality", quality);
            ctx.setVariable("topIssues", topIssues);
            ctx.setVariable("failed", failed);
            ctx.setVariable("dateFmt", DATE_FMT);
            // attached 变量在 PDF 附件生成后回填，再渲染正文

            String subjectKey = failed ? "mail.subject.failed" : "mail.subject.success";
            String subject = messageSource.getMessage(subjectKey,
                    new Object[]{task.getTaskName()}, Locale.SIMPLIFIED_CHINESE);

            JavaMailSenderImpl impl = mailSenderConfigService.buildMailSender(sender);
            MimeMessage message = impl.createMimeMessage();

            // 先尝试生成 PDF 附件，确定 attached 后再渲染正文
            byte[] pdfBytes = null;
            try {
                String pdfPath = reportService.generatePdfReport(taskId);
                pdfBytes = Files.readAllBytes(Paths.get(pdfPath));
            } catch (Exception e) {
                log.warn("通知邮件 PDF 附件生成失败（无附件照发）: taskId={}", taskId, e);
            }
            ctx.setVariable("attached", pdfBytes != null);
            String html = templateEngine.process("mail-notify", ctx);

            MimeMessageHelper helper = new MimeMessageHelper(message, pdfBytes != null, "UTF-8");
            String alias = sender.getFromAlias() != null && !sender.getFromAlias().isEmpty()
                    ? sender.getFromAlias() : sender.getName();
            helper.setFrom(sender.getFromAddress(), alias);
            helper.setTo(recipients.stream().map(MailRecipient::getEmail).toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(html, true);
            if (pdfBytes != null) {
                helper.addAttachment("scan-report-" + taskId + ".pdf",
                        new ByteArrayResource(pdfBytes), "application/pdf");
            }

            impl.send(message);
            log.info("通知邮件发送成功: taskId={}, 收件人={} 人, 附件={}",
                    taskId, recipients.size(), pdfBytes != null);
            updateMailStatus(taskId, "SENT");
        } catch (Exception e) {
            log.error("通知邮件发送失败: taskId={}", taskId, e);
            updateMailStatus(taskId, "FAILED");
        }
    }

    /** 稀疏更新 mail_status（只写 id/mailStatus/updatedAt，不动其他字段） */
    private void updateMailStatus(Long taskId, String mailStatus) {
        try {
            ScanTask patch = new ScanTask();
            patch.setId(taskId);
            patch.setMailStatus(mailStatus);
            patch.setUpdatedAt(LocalDateTime.now());
            scanTaskMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("mail_status 更新失败: taskId={}, status={}", taskId, mailStatus, e);
        }
    }
}
