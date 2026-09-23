package com.qqmu.jargus.service;

import com.qqmu.jargus.dto.QualityGateResult;
import com.qqmu.jargus.entity.ScanIssue;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.mapper.ScanIssueMapper;
import com.qqmu.jargus.mapper.ScanTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPTableEvent;
import com.lowagie.text.pdf.PdfShading;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告导出服务
 *
 * 支持 PDF 和 HTML 两种格式的代码评审报告。
 * 报告内容：概览、质量评分、问题分类统计、问题详情列表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ScanTaskMapper scanTaskMapper;
    private final ScanIssueMapper scanIssueMapper;
    private final QualityGateService qualityGateService;
    private final TemplateEngine templateEngine;

    @Value("${app.work-dir:./work}")
    private String workDir;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 报告内容版本标记：写进 HTML 报告头部，启动对账时凡是缺该标记的旧缓存一律清理。
     * 报告内容语义变化时递增版本号即可让全部存量缓存失效。
     */
    private static final String REPORT_CACHE_MARKER = "<!-- jargus-report-cache-v1 -->";

    /**
     * 生成 PDF 报告
     *
     * @param taskId 任务ID
     * @return 报告文件路径
     */
    public String generatePdfReport(Long taskId) throws Exception {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }

        QualityGateResult quality = qualityGateService.evaluateTask(taskId);
        List<ScanIssue> issues = getActiveIssues(taskId);

        // 确保报告目录存在
        Path reportDir = Paths.get(workDir, "reports");
        Files.createDirectories(reportDir);
        Path pdfPath = reportDir.resolve("scan-report-" + taskId + ".pdf");

        // 视觉对齐 HTML 报告：灰色页边 + 白色圆角卡片容器（PageBgEvent 绘制），
        // 内容列比容器每侧再内缩 28pt（近似 HTML .report-body 内边距），横幅铺满容器
        Document document = new Document(PageSize.A4, 44, 44, 16, 24);

        try (FileOutputStream fos = new FileOutputStream(pdfPath.toFile())) {
            PdfWriter writer = PdfWriter.getInstance(document, fos);
            writer.setPageEvent(new PageBgEvent());
            document.open();

            // 中文字体
            BaseFont bfChinese = getChineseFont();
            /* 配色与 templates/report.html 的 CSS 保持一致 */
            Font sectionFont = new Font(bfChinese, 13, Font.BOLD, new Color(30, 58, 138));
            Font labelFont = new Font(bfChinese, 8.5f, Font.NORMAL, new Color(107, 114, 128));
            // 9pt：四等分列宽约 127pt，10pt 时"2026-09-20 15:21:57"/"启用（16 条已 AI 增强）"会折行，HTML 里均为单行
            Font valueFont = new Font(bfChinese, 9, Font.BOLD, new Color(31, 41, 55));
            Font normalFont = new Font(bfChinese, 10, Font.NORMAL, new Color(55, 65, 81));
            Font smallFont = new Font(bfChinese, 9, Font.NORMAL, new Color(107, 114, 128));

            // ====== 头部横幅（对齐 HTML 报告 .report-header 渐变） ======
            PdfPTable header = new PdfPTable(1);
            // 铺满白色卡片容器（容器比内容列每侧宽 28pt），顶部两角随容器圆角
            header.setTotalWidth(PageSize.A4.getWidth() - 32);
            PdfPCell headerCell = new PdfPCell();
            headerCell.setCellEvent(new GradientEvent(new Color(30, 58, 138), new Color(59, 130, 246), 12, 12, 0, 0));
            headerCell.setBorder(Rectangle.NO_BORDER);
            headerCell.setPadding(28);
            Paragraph h1 = new Paragraph("百目 · Java 代码评审报告", new Font(bfChinese, 20, Font.BOLD, Color.WHITE));
            h1.setSpacingAfter(4);
            headerCell.addElement(h1);
            headerCell.addElement(new Paragraph("JArgus Code Review Report",
                    new Font(bfChinese, 10, Font.NORMAL, new Color(219, 234, 254))));
            header.addCell(headerCell);
            // 全幅出血：OpenPDF 的 PdfPTable 无负缩进 API，用绝对坐标写入使横幅贴齐容器左右边缘，
            // 再以固定行距占位段在文档流中让出「横幅高度 + 20pt 间距」
            float bannerEndY = header.writeSelectedRows(0, -1, 16, document.top(), writer.getDirectContent());
            Paragraph bannerSpacer = new Paragraph(" ");
            bannerSpacer.setLeading(document.top() - bannerEndY + 20);
            document.add(bannerSpacer);

            // ====== 一、评审概览（对齐 HTML .info-grid 四列卡片） ======
            addSectionTitle(document, "一、评审概览", sectionFont);
            String[][] info = {
                    {"任务名称", task.getTaskName()},
                    {"项目名称", task.getProjectName() != null ? task.getProjectName() : "-"},
                    {"来源类型", task.getSourceType()},
                    {"评审状态", task.getStatus()},
                    {"文件数量", String.valueOf(task.getTotalFiles() != null ? task.getTotalFiles() : 0)},
                    {"代码行数", String.valueOf(task.getTotalLines() != null ? task.getTotalLines() : 0)},
                    {"开始时间", task.getStartedAt() != null ? task.getStartedAt().format(DATE_FMT) : "-"},
                    {"完成时间", task.getCompletedAt() != null ? task.getCompletedAt().format(DATE_FMT) : "-"},
                    {"JDK 版本", task.getJdkVersion() != null ? task.getJdkVersion() : "-"},
                    {"Spring Boot", task.getSpringBootVersion() != null ? task.getSpringBootVersion() : "-"},
                    {"AI 评审", aiReviewStatus(task, issues)},
                    {"耗时", (task.getDurationSeconds() != null ? task.getDurationSeconds() : 0) + " 秒"},
            };
            PdfPTable infoTable = new PdfPTable(4);
            infoTable.setWidthPercentage(100);
            // 圆角外框 + 白底由表事件绘制（对齐 HTML .info-grid overflow:hidden 圆角）
            infoTable.setTableEvent(new RoundedFrameEvent(Color.WHITE, new Color(229, 231, 235), 8));
            for (int i = 0; i < info.length; i++) {
                PdfPCell c = new PdfPCell();
                // 内部分隔线用浅色 #f3f4f6（对齐 HTML .info-item），外边线交给圆角外框
                int mask = 0;
                if (i % 4 != 3) mask |= Rectangle.RIGHT;
                if (i < info.length - 4) mask |= Rectangle.BOTTOM;
                c.setBorder(mask);
                c.setBorderColor(new Color(243, 244, 246));
                c.setPaddingLeft(10);
                c.setPaddingRight(10);
                c.setPaddingTop(8);
                c.setPaddingBottom(8);
                Paragraph lp = new Paragraph(info[i][0], labelFont);
                lp.setSpacingAfter(3);
                c.addElement(lp);
                c.addElement(new Paragraph(info[i][1] != null ? info[i][1] : "-", valueFont));
                infoTable.addCell(c);
            }
            infoTable.setSpacingAfter(4);
            document.add(infoTable);

            // ====== 二、质量评分（对齐 HTML .quality-box：评分圆环 + 门禁 + 四项统计） ======
            addSectionTitle(document, "二、质量评分", sectionFont);
            PdfPTable qBox = new PdfPTable(1);
            qBox.setWidthPercentage(100);
            PdfPCell qCell = new PdfPCell();
            qCell.setCellEvent(new QualityPanelEvent(new Color(239, 246, 255), new Color(219, 234, 254),
                    scoreColor(quality.getLevel()), String.valueOf(quality.getScore()),
                    getLevelText(quality.getLevel()), bfChinese));
            qCell.setBorder(Rectangle.NO_BORDER);
            qCell.setPadding(14);

            PdfPTable qInner = new PdfPTable(2);
            qInner.setWidthPercentage(100);
            qInner.setWidths(new float[]{26, 74});

            // 左列仅作占位：圆盘由 qCell 的 QualityPanelEvent 统一绘制
            // （嵌套表格的单元格事件画布不生效）
            PdfPCell circleCell = new PdfPCell();
            circleCell.setBorder(Rectangle.NO_BORDER);
            circleCell.setFixedHeight(96);
            qInner.addCell(circleCell);

            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(Rectangle.NO_BORDER);
            rightCell.setPaddingLeft(10);
            Paragraph gate = new Paragraph(quality.isPassed() ? "✓ 质量门禁通过" : "✗ 质量门禁未通过",
                    new Font(bfChinese, 12, Font.BOLD,
                            quality.isPassed() ? new Color(5, 150, 105) : new Color(220, 38, 38)));
            gate.setSpacingAfter(8);
            rightCell.addElement(gate);

            PdfPTable stats = new PdfPTable(7);
            stats.setWidthPercentage(100);
            // 技术债列加宽（"2小时30分"等文案单行放下），其余六列均分
            stats.setWidths(new float[]{1, 1, 1, 1, 1, 1, 1.75f});
            stats.addCell(statCell(String.valueOf(quality.getBlockerCount()), "阻断", new Color(185, 28, 28), bfChinese));
            stats.addCell(statCell(String.valueOf(quality.getCriticalCount()), "严重", new Color(239, 68, 68), bfChinese));
            stats.addCell(statCell(String.valueOf(quality.getMajorCount()), "主要", new Color(245, 158, 11), bfChinese));
            stats.addCell(statCell(String.valueOf(quality.getMinorCount()), "次要", new Color(14, 165, 233), bfChinese));
            stats.addCell(statCell(String.valueOf(quality.getInfoCount()), "提示", new Color(107, 114, 128), bfChinese));
            stats.addCell(statCell(String.valueOf(quality.getTotalIssues()), "总计", new Color(55, 65, 81), bfChinese));
            String debtText = quality.getDebtText() != null && !quality.getDebtText().isEmpty()
                    ? quality.getDebtText() : "0分";
            stats.addCell(statCell(debtText, "技术债", new Color(124, 58, 237), bfChinese));
            stats.setSpacingAfter(8);
            rightCell.addElement(stats);

            String detailText = "说明：" + (quality.getDetail() != null ? quality.getDetail() : "-");
            if (quality.getIgnoredCount() > 0) {
                detailText += "；已忽略 " + quality.getIgnoredCount() + " 条";
            }
            rightCell.addElement(new Paragraph(detailText, smallFont));
            qInner.addCell(rightCell);

            qCell.addElement(qInner);
            qBox.addCell(qCell);
            qBox.setSpacingAfter(4);
            document.add(qBox);

            // ====== 三、问题分类统计（对齐 HTML .category-table 深蓝表头） ======
            addSectionTitle(document, "三、问题分类统计", sectionFont);
            Map<String, Map<String, Integer>> byChecker = countByChecker(issues);
            PdfPTable catTable = new PdfPTable(6);
            catTable.setWidthPercentage(100);
            catTable.setWidths(new float[]{10, 2, 2, 2, 2, 2});
            // 圆角外框（对齐 HTML .category-table 圆角），深色表头两端的圆角在单元格级实现
            catTable.setTableEvent(new RoundedFrameEvent(Color.WHITE, new Color(229, 231, 235), 8));

            Font thFont = new Font(bfChinese, 10, Font.BOLD, Color.WHITE);
            String[] heads = {"检查器", "阻断", "严重", "主要", "次要", "提示"};
            for (int i = 0; i < heads.length; i++) {
                PdfPCell hc = new PdfPCell(new Phrase(heads[i], thFont));
                Color thBg = new Color(30, 58, 138);
                if (i == 0) {
                    hc.setCellEvent(new RoundedCellEvent(thBg, null, 0, 8, 0, 0, 0));
                } else if (i == heads.length - 1) {
                    hc.setCellEvent(new RoundedCellEvent(thBg, null, 0, 0, 8, 0, 0));
                } else {
                    hc.setBackgroundColor(thBg);
                }
                hc.setBorder(Rectangle.NO_BORDER);
                hc.setPadding(8);
                if (i > 0) {
                    hc.setHorizontalAlignment(Element.ALIGN_CENTER);
                }
                catTable.addCell(hc);
            }

            // 计数列按等级着色（对齐 HTML .category-table 各列内联 color）
            Font[] countFonts = {
                    new Font(bfChinese, 10, Font.NORMAL, new Color(185, 28, 28)),    // BLOCKER
                    new Font(bfChinese, 10, Font.NORMAL, new Color(239, 68, 68)),    // CRITICAL
                    new Font(bfChinese, 10, Font.NORMAL, new Color(217, 119, 6)),    // MAJOR
                    new Font(bfChinese, 10, Font.NORMAL, new Color(2, 132, 199)),    // MINOR
                    new Font(bfChinese, 10, Font.NORMAL, new Color(107, 114, 128))   // INFO
            };
            // 无数据时仅保留深蓝表头（HTML 的 tbody 为空即此效果）
            int row = 0;
            int total = byChecker.size();
            for (Map.Entry<String, Map<String, Integer>> entry : byChecker.entrySet()) {
                boolean last = ++row == total;
                catTable.addCell(catBodyCell(entry.getKey(), normalFont, last, Element.ALIGN_LEFT));
                catTable.addCell(catBodyCell(String.valueOf(entry.getValue().getOrDefault("BLOCKER", 0)), countFonts[0], last, Element.ALIGN_CENTER));
                catTable.addCell(catBodyCell(String.valueOf(entry.getValue().getOrDefault("CRITICAL", 0)), countFonts[1], last, Element.ALIGN_CENTER));
                catTable.addCell(catBodyCell(String.valueOf(entry.getValue().getOrDefault("MAJOR", 0)), countFonts[2], last, Element.ALIGN_CENTER));
                catTable.addCell(catBodyCell(String.valueOf(entry.getValue().getOrDefault("MINOR", 0)), countFonts[3], last, Element.ALIGN_CENTER));
                catTable.addCell(catBodyCell(String.valueOf(entry.getValue().getOrDefault("INFO", 0)), countFonts[4], last, Element.ALIGN_CENTER));
            }
            catTable.setSpacingAfter(4);
            document.add(catTable);

            // ====== 四、问题详情（对齐 HTML .issue-item 卡片） ======
            addSectionTitle(document, "四、问题详情", sectionFont);
            if (issues.isEmpty()) {
                // 对齐 HTML .empty-state：居中彩带图标 + 灰字
                PdfPTable empty = new PdfPTable(1);
                empty.setWidthPercentage(100);
                PdfPCell ec = new PdfPCell();
                ec.setBorder(Rectangle.NO_BORDER);
                ec.setPadding(14);
                ec.setCellEvent(new EmptyStateEvent());
                // 空字符串 chunk 不占行高，用空格占出图标绘制区
                Paragraph spacer = new Paragraph(new Phrase(" ", new Font(bfChinese, 9)));
                spacer.setLeading(34f);
                spacer.setSpacingAfter(6);
                ec.addElement(spacer);
                Paragraph ep = new Paragraph("未发现代码问题",
                        new Font(bfChinese, 10.5f, Font.NORMAL, new Color(107, 114, 128)));
                ep.setAlignment(Element.ALIGN_CENTER);
                ec.addElement(ep);
                empty.addCell(ec);
                empty.setSpacingAfter(4);
                document.add(empty);
            } else {
                int idx = 1;
                for (ScanIssue issue : issues) {
                    document.add(buildIssueCard(issue, idx++, bfChinese, writer));
                }
            }

            // ====== 页脚（对齐 HTML .report-footer：顶部分隔线 + 灰色居中文字 + 时间戳） ======
            PdfPTable footerTable = new PdfPTable(1);
            footerTable.setWidthPercentage(100);
            PdfPCell fc = new PdfPCell();
            fc.setBorder(Rectangle.TOP);
            fc.setBorderColor(new Color(229, 231, 235));
            fc.setPadding(10);
            LocalDateTime footerTime = task.getCompletedAt() != null ? task.getCompletedAt() : task.getCreatedAt();
            Paragraph footer = new Paragraph("报告由 百目 JArgus 自动生成  |  "
                    + (footerTime != null ? footerTime.format(DATE_FMT) : "-"),
                    new Font(bfChinese, 9, Font.NORMAL, new Color(156, 163, 175)));
            footer.setAlignment(Element.ALIGN_CENTER);
            fc.addElement(footer);
            footerTable.addCell(fc);
            document.add(footerTable);

            document.close();
        }

        // 保存到 task
        task.setReportPath(pdfPath.toAbsolutePath().toString());
        scanTaskMapper.updateById(task);

        log.info("PDF 报告生成成功: taskId={}, path={}", taskId, pdfPath);
        return pdfPath.toAbsolutePath().toString();
    }

    /**
     * 生成 HTML 报告
     */
    public String generateHtmlReport(Long taskId) throws Exception {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }

        QualityGateResult quality = qualityGateService.evaluateTask(taskId);
        List<ScanIssue> issues = getActiveIssues(taskId);
        Map<String, Map<String, Integer>> byChecker = countByChecker(issues);

        Context context = new Context();
        context.setVariable("task", task);
        context.setVariable("quality", quality);
        context.setVariable("issues", issues);
        context.setVariable("byChecker", byChecker);
        context.setVariable("dateFmt", DATE_FMT);
        context.setVariable("levelText", getLevelText(quality.getLevel()));
        context.setVariable("levelLabelMap", getLevelLabelMap());
        context.setVariable("levelColorMap", getLevelColorMap());
        context.setVariable("aiReviewStatus", aiReviewStatus(task, issues));

        String html = templateEngine.process("report", context);

        // 保存文件
        Path reportDir = Paths.get(workDir, "reports");
        Files.createDirectories(reportDir);
        Path htmlPath = reportDir.resolve("scan-report-" + taskId + ".html");
        Files.writeString(htmlPath, html, StandardCharsets.UTF_8);

        log.info("HTML 报告生成成功: taskId={}, path={}", taskId, htmlPath);
        return htmlPath.toAbsolutePath().toString();
    }

    /**
     * 获取报告文件
     */
    public Path getReportFile(Long taskId, String format) {
        Path reportDir = Paths.get(workDir, "reports");
        String fileName = "scan-report-" + taskId + "." + format.toLowerCase();
        return reportDir.resolve(fileName);
    }

    /**
     * 清除指定任务的报告磁盘缓存（HTML + PDF）。
     * 预览 / 下载只要缓存文件存在就直接复用，因此 AI 建议落库后必须调用本方法，
     * 否则深度评审中途打开过预览的任务会把"半成品"报告一直缓存下去。
     */
    public void purgeReportCache(Long taskId) {
        if (taskId == null) {
            return;
        }
        for (String format : new String[]{"html", "pdf"}) {
            try {
                Files.deleteIfExists(getReportFile(taskId, format));
            } catch (Exception e) {
                log.warn("清理报告缓存失败: taskId={}, format={}, err={}", taskId, format, e.getMessage());
            }
        }
    }

    /**
     * 启动时对账报告磁盘缓存，删除后下次查看自动重新生成。两类过期缓存必须清理：
     * 1. 缺少当前版本标记的旧缓存（概览 AI 状态等展示语义已变化）；
     * 2. 文件生成时间早于该任务最后一条 AI 建议落库时间的缓存
     *    （AI 深度评审进行中打开预览的典型场景，内容不完整）。
     * HTML / PDF 成对清理：同一任务的两种格式总是同版本生成，一个过期另一个必然过期。
     *
     * @return 删除的文件数
     */
    public int purgeStaleReportCaches() {
        int purged = 0;
        try {
            File[] files = Paths.get(workDir, "reports").toFile().listFiles();
            if (files == null || files.length == 0) {
                return 0;
            }
            Map<Long, LocalDateTime> lastAiByTask = lastAiSuggestionTimes();
            for (File file : files) {
                String name = file.getName();
                if (!name.startsWith("scan-report-")) {
                    continue;
                }
                String rest = name.substring("scan-report-".length());
                int dot = rest.lastIndexOf('.');
                if (dot <= 0) {
                    continue;
                }
                Long taskId;
                try {
                    taskId = Long.parseLong(rest.substring(0, dot));
                } catch (NumberFormatException e) {
                    continue;
                }
                boolean stale = false;
                if (name.endsWith(".html")) {
                    try {
                        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                        stale = !content.substring(0, Math.min(content.length(), 4096)).contains(REPORT_CACHE_MARKER);
                    } catch (Exception e) {
                        stale = true;
                    }
                }
                if (!stale) {
                    LocalDateTime lastAi = lastAiByTask.get(taskId);
                    if (lastAi != null) {
                        LocalDateTime mtime = LocalDateTime.ofInstant(
                                Files.getLastModifiedTime(file.toPath()).toInstant(), ZoneId.systemDefault());
                        stale = mtime.isBefore(lastAi);
                    }
                }
                if (stale && file.delete()) {
                    purged++;
                    File sibling = new File(file.getParentFile(), "scan-report-" + taskId
                            + (name.endsWith(".html") ? ".pdf" : ".html"));
                    if (sibling.exists() && sibling.delete()) {
                        purged++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("报告缓存对账失败: {}", e.getMessage());
        }
        if (purged > 0) {
            log.info("已清理过期报告缓存 {} 个文件", purged);
        }
        return purged;
    }

    /**
     * 每个任务最后一条 AI 建议的落库时间（无 AI 建议的任务不在结果中）
     */
    private Map<Long, LocalDateTime> lastAiSuggestionTimes() {
        Map<Long, LocalDateTime> result = new HashMap<>();
        try {
            List<Map<String, Object>> rows = scanIssueMapper.selectMaps(
                    new QueryWrapper<ScanIssue>()
                            .select("task_id", "MAX(ai_suggestion_at) AS last_ai")
                            .isNotNull("ai_suggestion")
                            .groupBy("task_id"));
            for (Map<String, Object> row : rows) {
                Long taskId = null;
                LocalDateTime lastAi = null;
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    String key = entry.getKey().toLowerCase();
                    if ("task_id".equals(key) && entry.getValue() instanceof Number n) {
                        taskId = n.longValue();
                    } else if ("last_ai".equals(key)) {
                        lastAi = toLocalDateTime(entry.getValue());
                    }
                }
                if (taskId != null && lastAi != null) {
                    result.put(taskId, lastAi);
                }
            }
        } catch (Exception e) {
            log.warn("查询 AI 建议时间失败: {}", e.getMessage());
        }
        return result;
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        return null;
    }

    /**
     * 概览「AI 评审」展示：扫描开关打开、或存在任意 AI 增强建议，都算已启用。
     * 手动补跑深度评审的任务扫描开关可能仍是关闭状态，只看开关会误报"未启用"，
     * 因此有增强建议时按实际条数展示。
     */
    private String aiReviewStatus(ScanTask task, List<ScanIssue> issues) {
        long enhanced = 0;
        for (ScanIssue issue : issues) {
            if (issue.getAiSuggestion() != null && !issue.getAiSuggestion().isBlank()) {
                enhanced++;
            }
        }
        if (enhanced > 0) {
            return "启用（" + enhanced + " 条已 AI 增强）";
        }
        return Boolean.TRUE.equals(task.getEnableAiReview()) ? "启用" : "未启用";
    }

    // ==================== 辅助方法 ====================

    private List<ScanIssue> getActiveIssues(Long taskId) {
        return scanIssueMapper.selectList(
                new QueryWrapper<ScanIssue>()
                        .eq("task_id", taskId)
                        .eq("is_ignored", false)
                        .orderByAsc("issue_level", "file_path", "line_start")
        );
    }

    private BaseFont getChineseFont() {
        try {
            // 注意：OpenPDF 只能嵌入 TrueType 字体，Noto Sans CJK 的 .ttc 是 CFF/OpenType 轮廓会加载失败，
            // 因此 Linux 容器优先使用文泉驿（TrueType）。
            String[] fontNames = {
                    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",  // Linux 容器（TrueType）
                    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
                    "/System/Library/Fonts/Hiragino Sans GB.ttc",  // macOS 自带，OpenPDF 可嵌入
                    "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
                    "/Library/Fonts/Arial Unicode.ttf",
                    "/System/Library/Fonts/PingFang.ttc",  // 部分 macOS 版本
                    "/System/Library/Fonts/STHeiti Light.ttc",  // cmap 读不出时会被下方校验跳过
                    "C:/Windows/Fonts/msyh.ttc",  // Windows
                    "C:/Windows/Fonts/simhei.ttf",
                    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
            };
            for (String fontPath : fontNames) {
                if (!new File(fontPath).exists()) {
                    continue;
                }
                // .ttc 字体集合：先直接加载，失败时按 ",索引" 指定集合内第一个字体
                BaseFont font = tryCreateFont(fontPath);
                if (font == null && fontPath.toLowerCase().endsWith(".ttc")) {
                    font = tryCreateFont(fontPath + ",0");
                }
                // 加载成功不等于可用：STHeiti 等字体 cmap 读不出时 charExists 为 false，
                // 嵌进 PDF 后所有中文都是空 glyph（整页无字），必须跳过换下一个候选
                if (font != null && !font.charExists('中')) {
                    log.warn("字体 {} 加载成功但不含中文字形，跳过", fontPath);
                    font = null;
                }
                if (font != null) {
                    log.info("PDF 使用中文字体: {}", fontPath);
                    return font;
                }
            }
            // 兜底：使用内置字体（可能不支持中文）
            log.warn("未找到中文字体，PDF 中的中文可能显示异常");
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new RuntimeException("创建字体失败: " + e.getMessage(), e);
        }
    }

    private BaseFont tryCreateFont(String fontRef) {
        try {
            return BaseFont.createFont(fontRef, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        } catch (Exception e) {
            log.debug("加载字体 {} 失败: {}", fontRef, e.getMessage());
            return null;
        }
    }

    /** 章节标题：蓝字 + 灰色下划线（对齐 HTML .section-title） */
    private void addSectionTitle(Document document, String text, Font font) throws DocumentException {
        Paragraph p = new Paragraph(text, font);
        p.setSpacingBefore(4);
        p.setSpacingAfter(6);
        document.add(p);
        PdfPTable line = new PdfPTable(1);
        line.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setFixedHeight(2f);  // HTML .section-title border-bottom 2px
        c.setBackgroundColor(new Color(229, 231, 235));
        line.addCell(c);
        line.setSpacingAfter(8);
        document.add(line);
    }

    /** 质量统计小卡：彩色数字 + 灰色标签（对齐 HTML .stat-item） */
    private PdfPCell statCell(String num, String label, Color numColor, BaseFont bf) {
        PdfPCell c = new PdfPCell();
        // 对齐 HTML .stat-item 的 rgba(255,255,255,0.7) 圆角小格（叠在质量面板渐变上约为此色）
        c.setCellEvent(new RoundedCellEvent(new Color(244, 249, 255), null, 0, 6, 6, 6, 6));
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(8);
        Paragraph np = new Paragraph(num, new Font(bf, 12, Font.BOLD, numColor));
        np.setAlignment(Element.ALIGN_CENTER);
        np.setSpacingAfter(2);
        c.addElement(np);
        Paragraph lp = new Paragraph(label, new Font(bf, 8f, Font.NORMAL, new Color(107, 114, 128)));
        lp.setAlignment(Element.ALIGN_CENTER);
        c.addElement(lp);
        return c;
    }

    /** 分类统计正文单元格：仅底部分隔线（对齐 HTML .category-table td） */
    private PdfPCell catBodyCell(String text, Font font, boolean lastRow, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(8);
        c.setHorizontalAlignment(align);
        if (lastRow) {
            c.setBorder(Rectangle.NO_BORDER);
        } else {
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(new Color(243, 244, 246));
        }
        return c;
    }

    /** 问题卡片：浅灰头部（编号+等级徽章+标题）+ 正文（文件/描述/代码/建议/标签） */
    private PdfPTable buildIssueCard(ScanIssue issue, int idx, BaseFont bf, PdfWriter writer) {
        // 三列卡片：表头行用顶层单元（序号/胶囊徽章/标题），body 行 colspan=3。
        // OpenPDF 1.3.30 中复合单元一旦自带 cell event，其内部嵌套表的底色渲染会丢失，
        // 故表头不做嵌套，与分类统计表深色表头同一套路。
        PdfPTable card = new PdfPTable(3);
        card.setWidthPercentage(100);
        // 列宽收紧对齐 HTML .issue-header 的 gap:12px（旧 8/14/78 胶囊列过宽，居中后序号-胶囊-标题间距过大）
        card.setWidths(new float[]{7, 10.5f, 82.5f});
        Color border = new Color(229, 231, 235);
        // 圆角外框（对齐 HTML .issue-item 圆角）
        card.setTableEvent(new RoundedFrameEvent(Color.WHITE, border, 8));

        Color headBg = new Color(249, 250, 251);
        PdfPCell ic = new PdfPCell(new Phrase("#" + idx,
                new Font(bf, 9, Font.NORMAL, new Color(107, 114, 128))));
        // 灰底表头左端圆角；左内边距 16 对齐 HTML .issue-header padding:12px 16px
        ic.setCellEvent(new RoundedCellEvent(headBg, null, 0, 8, 0, 0, 0));
        ic.setBorder(Rectangle.NO_BORDER);
        ic.setVerticalAlignment(Element.ALIGN_MIDDLE);
        ic.setPadding(8);
        ic.setPaddingLeft(16);
        ic.setPaddingRight(0);
        card.addCell(ic);
        String levelLabel = getLevelLabel(issue.getIssueLevel());
        PdfPCell pill = new PdfPCell(new Phrase(levelLabel,
                new Font(bf, 8.5f, Font.BOLD, Color.WHITE)));
        // 胶囊宽度随文字（HTML .issue-level 是行内胶囊），不铺满 14% 列宽
        pill.setBackgroundColor(headBg);
        pill.setCellEvent(new PillEvent(getLevelColor(issue.getIssueLevel()),
                bf.getWidthPoint(levelLabel, 8.5f)));
        pill.setBorder(Rectangle.NO_BORDER);
        pill.setHorizontalAlignment(Element.ALIGN_CENTER);
        pill.setVerticalAlignment(Element.ALIGN_MIDDLE);
        pill.setPadding(3);
        pill.setPaddingLeft(0);
        pill.setPaddingRight(0);
        card.addCell(pill);
        PdfPCell tc = new PdfPCell(new Phrase(issue.getTitle() != null ? issue.getTitle() : "-",
                new Font(bf, 10.5f, Font.BOLD, new Color(31, 41, 55))));
        tc.setCellEvent(new RoundedCellEvent(headBg, null, 0, 0, 8, 0, 0));
        tc.setBorder(Rectangle.NO_BORDER);
        tc.setVerticalAlignment(Element.ALIGN_MIDDLE);
        tc.setPaddingLeft(0);
        tc.setPaddingRight(16);
        tc.setPaddingTop(8);
        tc.setPaddingBottom(8);
        card.addCell(tc);

        // body 按内容块拆成独立行：页面铺满时卡片可按行跨页（续页不重复表头，
        // 避免「下一页开头再写一遍上一页的标题」）；各行左右内边距 16 对齐 HTML .issue-body
        PdfPCell metaCell = bodyCell(16, 0);
        Paragraph meta = new Paragraph("文件：" + (issue.getFilePath() != null ? issue.getFilePath() : "-")
                + "  |  第 " + issue.getLineLabel() + " 行"
                + (issue.getOccurrenceCount() != null && issue.getOccurrenceCount() > 1
                        ? "（共 " + issue.getOccurrenceCount() + " 处）" : ""),
                new Font(bf, 8.5f, Font.NORMAL, new Color(107, 114, 128)));
        meta.setSpacingAfter(6);
        metaCell.addElement(meta);
        card.addCell(metaCell);
        if (notEmpty(issue.getDescription())) {
            PdfPCell descCell = bodyCell(0, 0);
            Paragraph d = new Paragraph(issue.getDescription(),
                    new Font(bf, 9.5f, Font.NORMAL, new Color(55, 65, 81)));
            d.setSpacingAfter(6);
            descCell.addElement(d);
            card.addCell(descCell);
        }
        if (notEmpty(issue.getCodeSnippet())) {
            PdfPCell codeCell = bodyCell(0, 0);
            PdfPTable codeBox = new PdfPTable(1);
            codeBox.setWidthPercentage(100);
            PdfPCell cc = new PdfPCell(new Phrase(issue.getCodeSnippet(),
                    new Font(bf, 8.5f, Font.NORMAL, new Color(229, 231, 235))));
            // 深色圆角代码块（对齐 HTML .issue-code border-radius:6px）
            cc.setCellEvent(new RoundedCellEvent(new Color(31, 41, 55), null, 0, 6, 6, 6, 6));
            cc.setBorder(Rectangle.NO_BORDER);
            cc.setPadding(8);
            codeBox.addCell(cc);
            codeBox.setSpacingAfter(6);
            codeCell.addElement(codeBox);
            card.addCell(codeCell);
        }
        if (notEmpty(issue.getSuggestion())) {
            PdfPCell sugCell = bodyCell(0, 0);
            PdfPTable sugBox = new PdfPTable(1);
            sugBox.setWidthPercentage(100);
            PdfPCell sc = new PdfPCell();
            // 浅绿圆角底 + 绿色左条（对齐 HTML .issue-suggestion border-radius:0 6px 6px 0）
            sc.setCellEvent(new RoundedCellEvent(new Color(236, 253, 245), null, 0, 0, 6, 6, 0));
            sc.setBorder(Rectangle.LEFT);
            sc.setBorderWidth(3);
            sc.setBorderColor(new Color(16, 185, 129));
            sc.setPaddingLeft(12);
            sc.setPaddingRight(12);
            sc.setPaddingTop(10);
            sc.setPaddingBottom(10);
            Phrase sp = new Phrase();
            sp.add(new Chunk("修复建议：", new Font(bf, 9, Font.BOLD, new Color(6, 95, 70))));
            sp.add(new Chunk(issue.getSuggestion(), new Font(bf, 9, Font.NORMAL, new Color(6, 95, 70))));
            sc.setPhrase(sp);
            sugBox.addCell(sc);
            sugBox.setSpacingAfter(6);
            sugCell.addElement(sugBox);
            card.addCell(sugCell);
        }
        if (notEmpty(issue.getAiSuggestion())) {
            addAiSuggestionRows(card, issue.getAiSuggestion(), bf);
        }
        // 标签胶囊行（对齐 HTML .issue-tag：灰底 r4 小标签；AI 生成用紫色变体）
        List<String[]> chips = new ArrayList<>();
        chips.add(new String[]{"检查器：" + (issue.getCheckerName() != null ? issue.getCheckerName() : "-"), "tag"});
        chips.add(new String[]{"规则：" + (issue.getRuleCode() != null ? issue.getRuleCode() : "-"), "tag"});
        if (Boolean.TRUE.equals(issue.getIsAiGenerated())) {
            chips.add(new String[]{"AI 生成", "ai"});
        }
        Font tagFont = new Font(bf, 8, Font.NORMAL, new Color(75, 85, 99));
        Font aiTagFont = new Font(bf, 8, Font.BOLD, new Color(109, 40, 217));
        int tagCols = chips.size() * 2 - 1;
        PdfPTable tagRow = new PdfPTable(tagCols);
        float[] tagWidths = new float[tagCols];
        float tagTotal = 0;
        for (int i = 0; i < chips.size(); i++) {
            tagWidths[i * 2] = bf.getWidthPoint(chips.get(i)[0], 8) + 16;
            tagTotal += tagWidths[i * 2];
            if (i < chips.size() - 1) {
                tagWidths[i * 2 + 1] = 8;
                tagTotal += 8;
            }
        }
        tagRow.setTotalWidth(tagTotal);
        tagRow.setLockedWidth(true);  // 复合单元内的嵌套表不锁宽会被 ColumnText 拉满整行
        tagRow.setHorizontalAlignment(Element.ALIGN_LEFT);  // HTML .issue-tags 为 flex 左起
        tagRow.setWidths(tagWidths);
        for (int i = 0; i < chips.size(); i++) {
            boolean ai = "ai".equals(chips.get(i)[1]);
            PdfPCell chip = new PdfPCell(new Phrase(chips.get(i)[0], ai ? aiTagFont : tagFont));
            chip.setCellEvent(new RoundedCellEvent(
                    ai ? new Color(237, 233, 254) : new Color(229, 231, 235), null, 0, 4, 4, 4, 4));
            chip.setBorder(Rectangle.NO_BORDER);
            chip.setHorizontalAlignment(Element.ALIGN_CENTER);
            chip.setPaddingTop(2);
            chip.setPaddingBottom(2);
            tagRow.addCell(chip);
            if (i < chips.size() - 1) {
                PdfPCell sp = new PdfPCell();
                sp.setBorder(Rectangle.NO_BORDER);
                tagRow.addCell(sp);
            }
        }
        PdfPCell tagsCell = bodyCell(0, 14);
        tagsCell.setHorizontalAlignment(Element.ALIGN_LEFT);  // HTML .issue-tags 为 flex 左起，不居中
        tagsCell.addElement(tagRow);
        card.addCell(tagsCell);
        card.setSpacingAfter(10);
        return card;
    }

    /** 问题卡片 body 行单元：无边框（外框由 RoundedFrameEvent 统一绘制），左右内边距 16 对齐 HTML .issue-body */
    private PdfPCell bodyCell(float padTop, float padBottom) {
        PdfPCell c = new PdfPCell();
        c.setColspan(3);  // 卡片三列布局下 body 行通栏
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingLeft(16);
        c.setPaddingRight(16);
        c.setPaddingTop(padTop);
        c.setPaddingBottom(padBottom);
        return c;
    }

    /**
     * AI 增强建议块：紫色左边框 + 浅紫底；Markdown 近似渲染（【…】加粗、``` 代码块深色底）。
     * 每个内容块作为问题卡片的独立一行（无上下边框、紫底连续，观感与整块一致），
     * 长建议因此可以逐行跨页，不在页底留整块空白。
     */
    private void addAiSuggestionRows(PdfPTable card, String md, BaseFont bf) {
        List<Object> elements = new ArrayList<>();  // Paragraph 文本行 / String 代码块
        Paragraph title = new Paragraph("✨ AI 增强建议",
                new Font(bf, 9, Font.BOLD, new Color(109, 40, 217)));
        title.setSpacingAfter(4);
        elements.add(title);

        Font textFont = new Font(bf, 8.5f, Font.NORMAL, new Color(76, 29, 149));
        Font headFont = new Font(bf, 9, Font.BOLD, new Color(76, 29, 149));
        StringBuilder codeBuf = new StringBuilder();
        boolean inCode = false;
        for (String raw : md.replace("\r\n", "\n").split("\n", -1)) {
            if (raw.trim().startsWith("```")) {
                if (inCode) {
                    elements.add(codeBuf.toString().replaceAll("\\n$", ""));
                    codeBuf.setLength(0);
                    inCode = false;
                } else {
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                codeBuf.append(raw).append('\n');
                continue;
            }
            String t = raw.replace("**", "").replaceAll("^#+\\s*", "").trim();
            if (t.isEmpty()) continue;
            Paragraph p = new Paragraph(t, t.startsWith("【") ? headFont : textFont);
            p.setSpacingAfter(2);
            elements.add(p);
        }
        if (codeBuf.length() > 0) elements.add(codeBuf.toString().replaceAll("\\n$", "")); // 围栏未闭合兜底

        Font codeFont = new Font(bf, 8f, Font.NORMAL, new Color(229, 231, 235));
        for (int i = 0; i < elements.size(); i++) {
            boolean first = i == 0;
            boolean last = i == elements.size() - 1;
            PdfPCell ac = new PdfPCell();
            ac.setColspan(3);  // 卡片三列布局下 AI 行通栏
            ac.setBorder(Rectangle.NO_BORDER);
            if (elements.get(i) instanceof String code) {
                // 代码块独立成行：深色圆角代码框由 AiCodeRowEvent 与紫底同层绘制，
                // 避开「带 event 的复合单元内嵌套表底色丢失」的 OpenPDF 问题
                ac.setCellEvent(new AiCodeRowEvent(first, last));
                ac.setPhrase(new Phrase(code, codeFont));
                // 代码文字落在深色框内：框左缘 33（紫底 16 + 紫条 3 + 内边距 14）+ ai-code 内边距 12
                ac.setPaddingLeft(45);
                ac.setPaddingRight(43);
                ac.setPaddingTop(16);
                ac.setPaddingBottom(16);
            } else {
                // AiRowEvent 绘制连续紫底 + 紫色左条 + 细紫描边，首末行右侧圆角（对齐 HTML .issue-ai）
                ac.setCellEvent(new AiRowEvent(first, last));
                // 紫底左右内缩 16 后，文字 = 紫底 16 + 紫条 3 + .issue-ai 内边距 14
                ac.setPaddingLeft(33);
                ac.setPaddingRight(31);
                ac.setPaddingTop(first ? 10 : 0);
                ac.setPaddingBottom(last ? 10 : 0);
                ac.addElement((Element) elements.get(i));
            }
            card.addCell(ac);
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /** 评分圆盘配色（对齐 HTML .score-excellent/good/fair/poor） */
    private Color scoreColor(String level) {
        if (level == null) return new Color(107, 114, 128);
        return switch (level) {
            case "EXCELLENT" -> new Color(16, 185, 129);
            case "GOOD" -> new Color(59, 130, 246);
            case "FAIR" -> new Color(245, 158, 11);
            case "POOR" -> new Color(239, 68, 68);
            default -> new Color(107, 114, 128);
        };
    }

    /** 圆角矩形路径（四角半径独立，半径 0 为直角），HTML 各圆角盒子的 PDF 等价物 */
    private static void roundedPath(PdfContentByte cb, float x, float y, float w, float h,
                                    float tl, float tr, float br, float bl) {
        // 半径钳制到 min(w,h)/2：胶囊徽章等矮盒子里半径过大路径会自交、填充消失
        float m = Math.min(w, h) / 2;
        tl = Math.min(tl, m);
        tr = Math.min(tr, m);
        br = Math.min(br, m);
        bl = Math.min(bl, m);
        float k = 0.5523f;
        cb.moveTo(x + tl, y + h);
        cb.lineTo(x + w - tr, y + h);
        if (tr > 0) cb.curveTo(x + w - tr + tr * k, y + h, x + w, y + h - tr + tr * k, x + w, y + h - tr);
        cb.lineTo(x + w, y + br);
        if (br > 0) cb.curveTo(x + w, y + br - br * k, x + w - br + br * k, y, x + w - br, y);
        cb.lineTo(x + bl, y);
        if (bl > 0) cb.curveTo(x + bl - bl * k, y, x, y + bl - bl * k, x, y + bl);
        cb.lineTo(x, y + h - tl);
        if (tl > 0) cb.curveTo(x, y + h - tl + tl * k, x + tl - tl * k, y + h, x + tl, y + h);
        cb.closePath();
    }

    /** 页面底：灰色页边 + 白色圆角卡片容器（对齐 HTML body #f3f4f6 + .report-container 圆角白卡） */
    private static class PageBgEvent extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContentUnder();
            Rectangle page = document.getPageSize();
            cb.setColorFill(new Color(243, 244, 246));
            cb.rectangle(page.getLeft(), page.getBottom(), page.getWidth(), page.getHeight());
            cb.fill();
            roundedPath(cb, 16, 16, page.getWidth() - 32, page.getHeight() - 32, 12, 12, 12, 12);
            cb.setColorFill(Color.WHITE);
            cb.fill();
        }
    }

    /** 圆角底/描边单元事件：chip、代码块、统计格等 HTML 圆角小盒子 */
    private record RoundedCellEvent(Color fill, Color stroke, float lineWidth,
                                    float tl, float tr, float br, float bl) implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            roundedPath(cb, position.getLeft(), position.getBottom(),
                    position.getWidth(), position.getHeight(), tl, tr, br, bl);
            if (fill != null) {
                cb.setColorFill(fill);
            }
            if (stroke != null) {
                cb.setColorStroke(stroke);
                cb.setLineWidth(lineWidth);
                cb.fillStroke();
            } else if (fill != null) {
                cb.fill();
            }
        }
    }

    /**
     * 等级徽章胶囊：宽度 = 文字宽 + 16pt、垂直居中（对齐 HTML .issue-level 行内胶囊），
     * 不铺满整个徽章列（铺满会成"胖块"，与 HTML 观感不符）。
     */
    private record PillEvent(Color fill, float textWidth) implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            float h = Math.min(position.getHeight() - 4, 15);
            float w = Math.min(textWidth + 16, position.getWidth());
            float x = position.getLeft() + (position.getWidth() - w) / 2;
            float y = position.getBottom() + (position.getHeight() - h) / 2;
            roundedPath(cb, x, y, w, h, h / 2, h / 2, h / 2, h / 2);
            cb.setColorFill(fill);
            cb.fill();
        }
    }

    /**
     * 表格圆角外框：白底画在 BASECANVAS（endWritingRows 时垫在单元格各层之下），
     * 圆角细边画在 LINECANVAS（叠在单元格底色之上），表格跨页时每个页面分片各触发一次。
     * 注意 OpenPDF 的 tableLayout 在行绘制完成后才触发，无法用裁剪裁掉深色表头直角，
     * 表头圆角改由 RoundedCellEvent 在单元格级实现。对齐 HTML .info-grid / .category-table / .issue-item。
     */
    private record RoundedFrameEvent(Color fill, Color stroke, float radius)
            implements PdfPTableEvent {
        @Override
        public void tableLayout(PdfPTable table, float[][] widths, float[] heights,
                                int headerRows, int rowNumber, PdfContentByte[] canvases) {
            float x = widths[0][0];
            float w = widths[0][widths[0].length - 1] - x;
            float y = heights[heights.length - 1];
            float h = heights[0] - y;
            if (fill != null) {
                PdfContentByte base = canvases[PdfPTable.BASECANVAS];
                roundedPath(base, x, y, w, h, radius, radius, radius, radius);
                base.setColorFill(fill);
                base.fill();
            }
            if (stroke != null) {
                PdfContentByte line = canvases[PdfPTable.LINECANVAS];
                roundedPath(line, x + 0.5f, y + 0.5f, w - 1, h - 1, radius, radius, radius, radius);
                line.setColorStroke(stroke);
                line.setLineWidth(1);
                line.stroke();
            }
        }
    }

    /**
     * AI 建议行：浅紫底连续、左侧紫粗边、右侧细紫边 + 右两角圆（首行上圆、末行下圆），
     * 多行拼起来与 HTML .issue-ai 整块圆角盒观感一致且可跨页。
     */
    private record AiRowEvent(boolean first, boolean last) implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            // 紫底左右内缩 16：与 body 文本/绿色建议盒同宽对齐（HTML .issue-ai 位于 .issue-body 内）
            float x = position.getLeft() + 16;
            float y = position.getBottom();
            float w = position.getWidth() - 32;
            float h = position.getHeight();
            float tr = first ? 6 : 0;
            float br = last ? 6 : 0;
            roundedPath(cb, x, y, w, h, 0, tr, br, 0);
            cb.setColorFill(new Color(245, 243, 255));
            cb.fill();
            cb.setColorFill(new Color(124, 58, 237));
            cb.rectangle(x, y, 3, h);
            cb.fill();
            cb.setColorStroke(new Color(221, 214, 254));
            cb.setLineWidth(1);
            cb.moveTo(x + w, y + br);
            cb.lineTo(x + w, y + h - tr);
            if (first) {
                cb.moveTo(x, y + h - 0.5f);
                cb.lineTo(x + w, y + h - 0.5f);
            }
            if (last) {
                cb.moveTo(x, y + 0.5f);
                cb.lineTo(x + w, y + 0.5f);
            }
            cb.stroke();
        }
    }

    /**
     * AI 建议代码块行：紫底 + 紫色左条与相邻 AI 行连续，内嵌深色圆角代码框同层绘制
     *（对齐 HTML .issue-ai 内 .ai-code 的深色底与 6px 上下外边距），代码文字由单元 padding 落在框内。
     */
    private record AiCodeRowEvent(boolean first, boolean last) implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            // 紫底左右内缩 16，与 AiRowEvent 同宽连续
            float x = position.getLeft() + 16;
            float y = position.getBottom();
            float w = position.getWidth() - 32;
            float h = position.getHeight();
            float tr = first ? 6 : 0;
            float br = last ? 6 : 0;
            roundedPath(cb, x, y, w, h, 0, tr, br, 0);
            cb.setColorFill(new Color(245, 243, 255));
            cb.fill();
            cb.setColorFill(new Color(124, 58, 237));
            cb.rectangle(x, y, 3, h);
            cb.fill();
            // 深色代码框占满紫盒内容区（紫条 3 + 内边距 14），上下留 6px 外边距（对齐 HTML .ai-code margin:6px 0）
            roundedPath(cb, x + 17, y + 6, w - 32, h - 12, 6, 6, 6, 6);
            cb.setColorFill(new Color(31, 36, 48));
            cb.fill();
            cb.setColorStroke(new Color(221, 214, 254));
            cb.setLineWidth(1);
            cb.moveTo(x + w, y + br);
            cb.lineTo(x + w, y + h - tr);
            if (first) {
                cb.moveTo(x, y + h - 0.5f);
                cb.lineTo(x + w, y + h - 0.5f);
            }
            if (last) {
                cb.moveTo(x, y + 0.5f);
                cb.lineTo(x + w, y + 0.5f);
            }
            cb.stroke();
        }
    }

    /** 线性渐变背景事件：近似 HTML 的 linear-gradient；四角半径独立（横幅仅上两角圆） */
    private record GradientEvent(Color from, Color to, float tl, float tr, float br, float bl)
            implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            // axial 渐变默认向两端 extend、垂直方向无限延伸，不裁剪会糊满整页
            cb.saveState();
            roundedPath(cb, position.getLeft(), position.getBottom(),
                    position.getWidth(), position.getHeight(), tl, tr, br, bl);
            cb.clip();
            cb.newPath();
            PdfShading shading = PdfShading.simpleAxial(cb.getPdfWriter(),
                    position.getLeft(), position.getBottom(), position.getRight(), position.getTop(),
                    from, to);
            cb.paintShading(shading);
            cb.restoreState();
        }
    }

    /** 质量面板事件：渐变底 + 左侧评分实心圆盘（近似 HTML .quality-box + .score-circle）。
     *  圆盘必须画在外层单元格事件里——嵌套表格的单元格事件画布不会输出。 */
    private record QualityPanelEvent(Color from, Color to, Color disc,
                                     String score, String label, BaseFont bf) implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            cb.saveState();
            roundedPath(cb, position.getLeft(), position.getBottom(),
                    position.getWidth(), position.getHeight(), 8, 8, 8, 8);
            cb.clip();
            cb.newPath();
            cb.paintShading(PdfShading.simpleAxial(cb.getPdfWriter(),
                    position.getLeft(), position.getBottom(), position.getRight(), position.getTop(),
                    from, to));
            // 圆盘居中于左侧 26% 占位列（与 qInner 列宽一致）
            float cx = position.getLeft() + position.getWidth() * 0.13f;
            float cy = position.getBottom() + position.getHeight() / 2;
            cb.setColorFill(disc);
            cb.circle(cx, cy, 45);
            cb.fill();
            cb.setColorFill(Color.WHITE);
            cb.beginText();
            cb.setFontAndSize(bf, 26);
            cb.setTextMatrix(cx - bf.getWidthPoint(score, 26) / 2, cy + 2);
            cb.showText(score);
            cb.endText();
            cb.beginText();
            cb.setFontAndSize(bf, 9);
            cb.setTextMatrix(cx - bf.getWidthPoint(label, 9) / 2, cy - 16);
            cb.showText(label);
            cb.endText();
            cb.restoreState();
        }
    }

    /** 空状态图标：🎉 emoji 在 PDF 字体中无字形，用矢量绘彩带礼花近似（对齐 HTML .empty-state .icon） */
    private record EmptyStateEvent() implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            float cx = (position.getLeft() + position.getRight()) / 2;
            float x0 = cx - 18;
            float y0 = position.getTop() - cell.getPaddingTop() - 34;
            // 金色礼花筒
            cb.setColorFill(new Color(245, 158, 11));
            cb.moveTo(x0 + 3, y0 + 2);
            cb.lineTo(x0 + 16, y0 + 10);
            cb.lineTo(x0 + 8, y0 + 17);
            cb.closePathFillStroke();
            // 喷出的彩带
            cb.setLineWidth(1.2f);
            cb.setColorStroke(new Color(236, 72, 153));
            cb.moveTo(x0 + 15, y0 + 13);
            cb.lineTo(x0 + 22, y0 + 24);
            cb.stroke();
            cb.setColorStroke(new Color(59, 130, 246));
            cb.moveTo(x0 + 17, y0 + 11);
            cb.lineTo(x0 + 28, y0 + 17);
            cb.stroke();
            // 彩点
            dot(cb, x0 + 21, y0 + 29, 2, new Color(239, 68, 68));
            dot(cb, x0 + 27, y0 + 24, 2, new Color(16, 185, 129));
            dot(cb, x0 + 32, y0 + 14, 2, new Color(139, 92, 246));
            dot(cb, x0 + 25, y0 + 7, 1.6f, new Color(236, 72, 153));
            dot(cb, x0 + 33, y0 + 22, 1.6f, new Color(245, 158, 11));
        }

        private void dot(PdfContentByte cb, float x, float y, float r, Color c) {
            cb.setColorFill(c);
            cb.circle(x, y, r);
            cb.fill();
        }
    }

    private Map<String, Map<String, Integer>> countByChecker(List<ScanIssue> issues) {
        Map<String, Map<String, Integer>> map = new LinkedHashMap<>();
        for (ScanIssue issue : issues) {
            String checker = issue.getCheckerName() != null ? issue.getCheckerName() : issue.getCheckerType();
            String level = issue.getIssueLevel();
            map.computeIfAbsent(checker, k -> new HashMap<>())
                    .merge(level, 1, Integer::sum);
        }
        return map;
    }

    private String getLevelText(String level) {
        if (level == null) return "-";
        return switch (level) {
            case "EXCELLENT" -> "优秀";
            case "GOOD" -> "良好";
            case "FAIR" -> "一般";
            case "POOR" -> "较差";
            default -> level;
        };
    }

    private String getLevelLabel(String level) {
        if (level == null) return "提示";
        return switch (level.toUpperCase()) {
            case "BLOCKER" -> "阻断";
            case "CRITICAL" -> "严重";
            case "MAJOR" -> "主要";
            case "MINOR" -> "次要";
            case "INFO" -> "提示";
            default -> level;
        };
    }

    /** 等级配色与 HTML .lv-* / .badge-blocker 等五级样式一致 */
    private Color getLevelColor(String level) {
        if (level == null) return Color.GRAY;
        return switch (level.toUpperCase()) {
            case "BLOCKER" -> new Color(185, 28, 28);
            case "CRITICAL" -> new Color(239, 68, 68);
            case "MAJOR" -> new Color(245, 158, 11);
            case "MINOR" -> new Color(14, 165, 233);
            case "INFO" -> new Color(107, 114, 128);
            default -> Color.GRAY;
        };
    }

    private Map<String, String> getLevelLabelMap() {
        Map<String, String> map = new HashMap<>();
        map.put("BLOCKER", "阻断");
        map.put("CRITICAL", "严重");
        map.put("MAJOR", "主要");
        map.put("MINOR", "次要");
        map.put("INFO", "提示");
        return map;
    }

    private Map<String, String> getLevelColorMap() {
        Map<String, String> map = new HashMap<>();
        map.put("BLOCKER", "#b91c1c");
        map.put("CRITICAL", "#ef4444");
        map.put("MAJOR", "#f59e0b");
        map.put("MINOR", "#0ea5e9");
        map.put("INFO", "#6b7280");
        return map;
    }
}
