package com.qqmu.jargus.service;

import com.qqmu.jargus.dto.FileContext;
import com.qqmu.jargus.entity.ScanIssue;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.mapper.ScanIssueMapper;
import com.qqmu.jargus.mapper.ScanTaskMapper;
import com.qqmu.jargus.util.IssuePoints;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描问题服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanIssueService {

    private final ScanIssueMapper scanIssueMapper;
    private final ScanTaskMapper scanTaskMapper;

    /**
     * 分页查询任务的问题列表
     */
    public IPage<ScanIssue> listIssues(
            Long taskId,
            String level,
            String checkerType,
            String keyword,
            boolean includeIgnored,
            int pageNum,
            int pageSize
    ) {
        Page<ScanIssue> page = new Page<>(pageNum, pageSize);
        QueryWrapper<ScanIssue> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);

        if (!includeIgnored) {
            wrapper.eq("is_ignored", false);
        }
        if (level != null && !level.isEmpty() && !"ALL".equalsIgnoreCase(level)) {
            wrapper.eq("issue_level", level.toUpperCase());
        }
        if (checkerType != null && !checkerType.isEmpty() && !"ALL".equalsIgnoreCase(checkerType)) {
            wrapper.eq("checker_type", checkerType);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like("title", keyword)
                    .or().like("file_path", keyword)
                    .or().like("description", keyword));
        }

        wrapper.orderByAsc("file_path", "line_start");
        return scanIssueMapper.selectPage(page, wrapper);
    }

    /**
     * 获取问题详情
     */
    public ScanIssue getById(Long id) {
        return scanIssueMapper.selectById(id);
    }

    /**
     * 获取任务的问题统计
     */
    public Map<String, Object> getStats(Long taskId) {
        QueryWrapper<ScanIssue> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);

        long blockerCount = scanIssueMapper.selectCount(
                new QueryWrapper<ScanIssue>().eq("task_id", taskId).eq("issue_level", "BLOCKER")
        );
        long criticalCount = scanIssueMapper.selectCount(
                new QueryWrapper<ScanIssue>().eq("task_id", taskId).eq("issue_level", "CRITICAL")
        );
        long majorCount = scanIssueMapper.selectCount(
                new QueryWrapper<ScanIssue>().eq("task_id", taskId).eq("issue_level", "MAJOR")
        );
        long minorCount = scanIssueMapper.selectCount(
                new QueryWrapper<ScanIssue>().eq("task_id", taskId).eq("issue_level", "MINOR")
        );
        long infoCount = scanIssueMapper.selectCount(
                new QueryWrapper<ScanIssue>().eq("task_id", taskId).eq("issue_level", "INFO")
        );
        long total = blockerCount + criticalCount + majorCount + minorCount + infoCount;

        Map<String, Object> stats = new HashMap<>();
        stats.put("blockerCount", blockerCount);
        stats.put("criticalCount", criticalCount);
        stats.put("majorCount", majorCount);
        stats.put("minorCount", minorCount);
        stats.put("infoCount", infoCount);
        stats.put("total", total);

        // 按检查器聚合（selectMaps 保留 COUNT 别名，实体映射会丢弃聚合列）
        try {
            List<Map<String, Object>> rows = scanIssueMapper.selectMaps(
                    new QueryWrapper<ScanIssue>()
                            .select("checker_type", "MAX(checker_name) AS checker_name",
                                    "issue_level", "COUNT(*) AS cnt")
                            .eq("task_id", taskId)
                            .groupBy("checker_type", "issue_level")
            );
            List<Map<String, Object>> byChecker = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                // 列标签大小写因方言而异（H2 大写、MySQL 小写），统一按小写键读取
                Map<String, Object> ci = new HashMap<>();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    if (e.getKey() != null) {
                        ci.put(e.getKey().toLowerCase(), e.getValue());
                    }
                }
                Map<String, Object> m = new HashMap<>();
                m.put("checkerType", ci.get("checker_type"));
                m.put("checkerName", ci.get("checker_name"));
                m.put("level", ci.get("issue_level"));
                Object cnt = ci.get("cnt");
                m.put("count", cnt instanceof Number n ? n.longValue() : 0L);
                byChecker.add(m);
            }
            stats.put("byChecker", byChecker);
        } catch (Exception e) {
            log.warn("按检查器聚合统计失败: taskId={}, err={}", taskId, e.getMessage());
        }
        return stats;
    }

    /**
     * 获取问题行的上下文窗口（问题行上下各 contextLines 行，带真实行号和问题标记）；
     * 不返回整个文件，窗口外的行数通过 hiddenBefore/hiddenAfter 告知前端展示省略占位。
     */
    public FileContext getIssueFileContext(Long issueId, int contextLines) {
        ScanIssue issue = scanIssueMapper.selectById(issueId);
        if (issue == null) return null;
        int ctx = Math.max(0, Math.min(contextLines, 500));

        ScanTask task = scanTaskMapper.selectById(issue.getTaskId());
        if (task == null) return buildFromSnippet(issue);

        String snapshotPath = task.getSnapshotPath();
        String filePath = issue.getFilePath();
        int lineStart = issue.getLineStart() != null ? issue.getLineStart() : 1;
        int lineEnd = issue.getLineEnd() != null ? issue.getLineEnd() : lineStart;

        try {
            // 先尝试直接从 snapshotPath + filePath 读取
            Path file = Paths.get(snapshotPath, filePath);
            if (!Files.exists(file)) {
                // 尝试 filePath 为绝对路径
                file = Paths.get(filePath);
            }
            if (!Files.exists(file)) {
                // 尝试相对 snapshotPath
                Path relPath = Paths.get(filePath);
                if (!relPath.isAbsolute()) {
                    file = Paths.get(snapshotPath).resolve(filePath).normalize();
                }
            }

            if (!Files.exists(file)) {
                return buildFromSnippet(issue);
            }

            List<String> allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int total = allLines.size();

            // 同文件同规则可能合并了多个问题点：取每个点上下各 ctx 行的窗口，
            // 窗口重叠或相邻时并成一段，段间不相邻的部分用省略占位（gapBefore > 0）
            List<int[]> rawPoints = IssuePoints.toRanges(issue.getLinePoints());
            List<int[]> points = new ArrayList<>();
            if (!rawPoints.isEmpty()) {
                for (int[] p : IssuePoints.merge(rawPoints)) {
                    points.add(new int[]{Math.max(1, p[0]), Math.min(total, p[1])});
                }
            } else {
                points.add(new int[]{Math.max(1, Math.min(lineStart, total)),
                        Math.max(1, Math.min(lineEnd, total))});
            }
            List<int[]> windows = new ArrayList<>();
            for (int[] p : points) {
                int from = Math.max(1, p[0] - ctx);
                int to = Math.min(total, p[1] + ctx);
                if (!windows.isEmpty() && from <= windows.get(windows.size() - 1)[1] + 1) {
                    windows.get(windows.size() - 1)[1] = Math.max(windows.get(windows.size() - 1)[1], to);
                } else {
                    windows.add(new int[]{from, to});
                }
            }

            int firstFrom = windows.get(0)[0];
            int lastTo = windows.get(windows.size() - 1)[1];
            List<FileContext.LineInfo> lineInfos = new ArrayList<>();
            int prevTo = 0;
            for (int[] seg : windows) {
                int gapBefore = prevTo > 0 ? seg[0] - prevTo - 1 : 0;
                for (int lineNum = seg[0]; lineNum <= seg[1]; lineNum++) {
                    boolean isIssue = false;
                    String marker = null;
                    for (int[] p : points) {
                        if (lineNum >= p[0] && lineNum <= p[1]) {
                            isIssue = true;
                            if (p[0] == p[1] && lineNum == p[0]) {
                                marker = "single";
                            } else if (lineNum == p[0]) {
                                marker = "start";
                            } else if (lineNum == p[1]) {
                                marker = "end";
                            }
                            break;
                        }
                    }
                    FileContext.LineInfo.LineInfoBuilder b = FileContext.LineInfo.builder()
                            .lineNumber(lineNum)
                            .content(allLines.get(lineNum - 1))
                            .issueLine(isIssue)
                            .issueMarker(marker);
                    if (lineNum == seg[0] && gapBefore > 0) {
                        b.gapBefore(gapBefore);
                    }
                    lineInfos.add(b.build());
                }
                prevTo = seg[1];
            }

            int occurrenceCount = issue.getOccurrenceCount() != null
                    ? Math.max(1, issue.getOccurrenceCount()) : 1;
            return FileContext.builder()
                    .filePath(filePath)
                    .title(issue.getTitle())
                    .issueLevel(issue.getIssueLevel())
                    .ruleCode(issue.getRuleCode())
                    .suggestion(issue.getSuggestion())
                    .totalLines(total)
                    .issueLineStart(points.get(0)[0])
                    .issueLineEnd(points.get(points.size() - 1)[1])
                    .lineLabel(IssuePoints.format(points))
                    .occurrenceCount(occurrenceCount)
                    .hiddenBefore(firstFrom - 1)
                    .hiddenAfter(total - lastTo)
                    .lines(lineInfos)
                    .build();

        } catch (Exception e) {
            log.warn("获取文件上下文失败: {}", e.getMessage());
            return buildFromSnippet(issue);
        }
    }

    /**
     * 从代码片段构建上下文（兜底）
     */
    private FileContext buildFromSnippet(ScanIssue issue) {
        List<FileContext.LineInfo> lines = new ArrayList<>();
        String snippet = issue.getCodeSnippet();
        if (snippet != null && !snippet.isEmpty()) {
            String[] parts = snippet.split("\n");
            int lineStart = issue.getLineStart() != null ? issue.getLineStart() : 1;
            for (int i = 0; i < parts.length; i++) {
                int lineNum = lineStart + i;
                lines.add(FileContext.LineInfo.builder()
                        .lineNumber(lineNum)
                        .content(parts[i])
                        .issueLine(true)
                        .issueMarker(i == 0 && parts.length == 1 ? "single" : (i == 0 ? "start" : (i == parts.length - 1 ? "end" : null)))
                        .build());
            }
        }
        int ls = issue.getLineStart() != null ? issue.getLineStart() : 1;
        int le = issue.getLineEnd() != null ? issue.getLineEnd() : ls;
        return FileContext.builder()
                .filePath(issue.getFilePath())
                .title(issue.getTitle())
                .issueLevel(issue.getIssueLevel())
                .ruleCode(issue.getRuleCode())
                .suggestion(issue.getSuggestion())
                .totalLines(lines.size())
                .issueLineStart(ls)
                .issueLineEnd(le)
                .lineLabel(issue.getLineLabel())
                .occurrenceCount(issue.getOccurrenceCount() != null ? issue.getOccurrenceCount() : 1)
                .lines(lines)
                .build();
    }

    /**
     * 忽略问题
     *
     * @param issueId    问题ID
     * @param ignoreType SINGLE / RULE
     * @param reason     忽略原因
     */
    public boolean ignoreIssue(Long issueId, String ignoreType, String reason) {
        ScanIssue issue = scanIssueMapper.selectById(issueId);
        if (issue == null) return false;

        issue.setIsIgnored(true);
        issue.setIgnoreType(ignoreType != null ? ignoreType : "SINGLE");
        issue.setIgnoreReason(reason);
        return scanIssueMapper.updateById(issue) > 0;
    }
}
