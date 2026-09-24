package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.ScanIssue;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.mapper.ScanIssueMapper;
import com.qqmu.jargus.mapper.ScanTaskMapper;
import com.qqmu.jargus.util.IssuePoints;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 历史扫描问题一次性升级（幂等，可反复执行）：
 * 1. 清理把 import/package 雷同误判成 DUP_CODE_BLOCK 的旧记录（需结合磁盘快照二次确认）；
 * 2. 把同任务、同文件、同规则码的逐行记录合并成一条（linePoints 承载全部问题位置）；
 * 3. 重算受影响任务的问题计数并清理过期报告缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueMergeService {

    private static final String DUP_RULE = "DUP_CODE_BLOCK";
    /** 描述中 "位置一：path:10-25" 形式的两处位置 */
    private static final Pattern LOCATION = Pattern.compile("位置[一二]：(.+):(\\d+)-(\\d+)");
    /** DUP 描述中的搭档位置（位置二），合并时提取 */
    private static final Pattern PARTNER = Pattern.compile("位置二：(.+):(\\d+)-(\\d+)");
    /** 合并后追加的其余搭档位置行；前缀刻意避开 LOCATION 正则，防止被误解析 */
    private static final Pattern OTHER_LOCATION = Pattern.compile("其他位置：(.+):(\\d+)-(\\d+)");
    private static final String OTHER_LOCATION_PREFIX = "\n  其他位置：";

    /** 从 DUP 描述中提取搭档位置 "file:s-e"，无则返回 null（供扫描落库合并复用） */
    public static String extractPartnerLocation(String description) {
        if (description == null) {
            return null;
        }
        Matcher m = PARTNER.matcher(description);
        return m.find() ? m.group(1) + ":" + m.group(2) + "-" + m.group(3) : null;
    }

    private final ScanIssueMapper scanIssueMapper;
    private final ScanTaskMapper scanTaskMapper;

    @Value("${app.work-dir:./work}")
    private String workDir;

    public void backfill() {
        List<ScanIssue> all = scanIssueMapper.selectList(
                new QueryWrapper<ScanIssue>().orderByAsc("task_id", "file_path", "line_start", "id"));
        if (all.isEmpty()) {
            return;
        }
        Set<Long> affectedTasks = new HashSet<>();
        int purged = purgeImportDuplicates(all, affectedTasks);

        all = scanIssueMapper.selectList(
                new QueryWrapper<ScanIssue>().orderByAsc("task_id", "file_path", "line_start", "id"));
        int merged = mergeSameFileIssues(all, affectedTasks);

        for (Long taskId : affectedTasks) {
            recomputeTaskCounts(taskId);
            deleteReportCache(taskId);
        }
        if (purged > 0 || merged > 0) {
            log.info("历史问题合并升级: 清理 import 误报 {} 条，合并 {} 条，涉及任务 {}",
                    purged, merged, affectedTasks);
        }
    }

    /**
     * 删除"整块代码都是 package/import 行"的重复代码误报，
     * 必须两个位置都能在磁盘快照上确认才删除，文件缺失时保守保留。
     */
    private int purgeImportDuplicates(List<ScanIssue> all, Set<Long> affectedTasks) {
        Map<Long, ScanTask> taskCache = new HashMap<>();
        int deleted = 0;
        for (ScanIssue issue : all) {
            if (!DUP_RULE.equals(issue.getRuleCode()) || issue.getDescription() == null) {
                continue;
            }
            ScanTask task = taskCache.computeIfAbsent(issue.getTaskId(), scanTaskMapper::selectById);
            if (task == null || task.getSnapshotPath() == null) {
                continue;
            }
            int matches = 0;
            boolean importOnly = true;
            // 位置一/位置二 + 合并追加的"其他位置"全部纳入判定：所有位置都是 import 区才删
            for (Pattern p : new Pattern[]{LOCATION, OTHER_LOCATION}) {
                Matcher m = p.matcher(issue.getDescription());
                while (m.find()) {
                    matches++;
                    if (!isImportOnlyBlock(task.getSnapshotPath(),
                            m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))) {
                        importOnly = false;
                    }
                }
            }
            if (importOnly && matches >= 2) {
                scanIssueMapper.deleteById(issue.getId());
                affectedTasks.add(issue.getTaskId());
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("清理 import/package 误报的重复代码记录: {} 条", deleted);
        }
        return deleted;
    }

    /**
     * 判断快照文件中 [start,end] 行是否全部为 package/import/注释/空行，
     * 且至少存在一条 package/import 语句。
     */
    private boolean isImportOnlyBlock(String snapshotPath, String relPath, int start, int end) {
        try {
            Path root = Paths.get(snapshotPath).normalize();
            Path file = root.resolve(relPath).normalize();
            if (!file.startsWith(root) || !Files.exists(file)) {
                return false;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean foundImport = false;
            for (int i = Math.max(1, start); i <= Math.min(end, lines.size()); i++) {
                String t = lines.get(i - 1).trim();
                if (t.isEmpty() || t.startsWith("//") || t.startsWith("/*")
                        || t.startsWith("*") || t.startsWith("/*")) {
                    continue;
                }
                if (t.startsWith("package ") || t.startsWith("import ")) {
                    foundImport = true;
                    continue;
                }
                return false;
            }
            return foundImport;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 同任务+同文件+同规则码的记录合并为一条。
     * DUP_CODE_BLOCK 同样参与合并：代表条描述保留自己的"位置一/位置二"，
     * 其余被并条目的重复对象提取为"其他位置"行追加进描述，避免同文件刷出大量重复行。
     */
    private int mergeSameFileIssues(List<ScanIssue> all, Set<Long> affectedTasks) {
        Map<String, List<ScanIssue>> groups = groupByTaskFileRule(all);

        int removed = 0;
        for (List<ScanIssue> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            List<ScanIssue> active = group.stream().filter(i -> !Boolean.TRUE.equals(i.getIsIgnored())).toList();
            List<ScanIssue> contributors = active.isEmpty() ? group : active;

            ScanIssue target = pickRepresentative(contributors);
            List<int[]> mergedPoints = IssuePoints.merge(collectPoints(contributors));
            if (mergedPoints.isEmpty()) {
                continue;
            }
            applyMergedGeometry(target, contributors, mergedPoints);
            carryOverAiSuggestion(target, contributors);
            appendDupPartners(target, contributors);
            appendCountSuffix(target, contributors.size(), mergedPoints);
            scanIssueMapper.updateById(target);

            // 非全忽略时被忽略的逐点记录直接丢弃（与新扫描"行级忽略点不并入"一致），
            // 其余点全部并入 target
            List<Long> deleteIds = collectDeleteIds(group, target);
            if (!deleteIds.isEmpty()) {
                scanIssueMapper.deleteBatchIds(deleteIds);
                removed += deleteIds.size();
            }
            affectedTasks.add(target.getTaskId());
        }
        return removed;
    }

    /** 同任务+同文件+同规则码分组（保留首次出现顺序） */
    private Map<String, List<ScanIssue>> groupByTaskFileRule(List<ScanIssue> all) {
        Map<String, List<ScanIssue>> groups = new LinkedHashMap<>();
        for (ScanIssue issue : all) {
            String key = issue.getTaskId() + "|" + issue.getFilePath() + "|" + issue.getRuleCode();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(issue);
        }
        return groups;
    }

    /** 代表条：组内 id 最小者（列号等元数据沿用它） */
    private ScanIssue pickRepresentative(@NonNull List<ScanIssue> contributors) {
        ScanIssue target = contributors.get(0);
        for (ScanIssue i : contributors) {
            if (i.getId() < target.getId()) {
                target = i;
            }
        }
        return target;
    }

    /** 收集各条目的行区间（优先 linePoints，回退 lineStart/lineEnd） */
    private List<int[]> collectPoints(List<ScanIssue> contributors) {
        List<int[]> points = new ArrayList<>();
        for (ScanIssue i : contributors) {
            List<int[]> existing = IssuePoints.toRanges(i.getLinePoints());
            if (!existing.isEmpty()) {
                points.addAll(existing);
            } else if (i.getLineStart() != null) {
                points.add(new int[]{i.getLineStart(),
                        i.getLineEnd() != null ? i.getLineEnd() : i.getLineStart()});
            }
        }
        return points;
    }

    /** 合并后的几何与聚合属性写回代表条：行区间、出现次数、最高 severity、AI 标记 */
    private void applyMergedGeometry(@NonNull ScanIssue target, @NonNull List<ScanIssue> contributors,
                                     @NonNull List<int[]> mergedPoints) {
        int severity = target.getSeverity() != null ? target.getSeverity() : 1;
        boolean anyAi = Boolean.TRUE.equals(target.getIsAiGenerated());
        for (ScanIssue i : contributors) {
            if (i.getSeverity() != null) {
                severity = Math.max(severity, i.getSeverity());
            }
            anyAi |= Boolean.TRUE.equals(i.getIsAiGenerated());
        }
        target.setLineStart(mergedPoints.get(0)[0]);
        target.setLineEnd(mergedPoints.get(mergedPoints.size() - 1)[1]);
        target.setLinePoints(mergedPoints.size() > 1 ? IssuePoints.toLists(mergedPoints) : null);
        target.setOccurrenceCount(contributors.size());
        target.setSeverity(severity);
        target.setIsAiGenerated(anyAi);
    }

    /** AI 增强结果若主记录缺失则从被合并记录里保留一条，避免用户已消耗的 token 白费 */
    private void carryOverAiSuggestion(@NonNull ScanIssue target, @NonNull List<ScanIssue> contributors) {
        if (target.getAiSuggestion() != null) {
            return;
        }
        for (ScanIssue i : contributors) {
            if (!i.getId().equals(target.getId()) && i.getAiSuggestion() != null) {
                target.setAiSuggestion(i.getAiSuggestion());
                target.setAiSuggestionAt(i.getAiSuggestionAt());
                return;
            }
        }
    }

    /** DUP 合并时保留各条目的重复对象：非代表条的"位置二"追加为"其他位置"行 */
    private void appendDupPartners(@NonNull ScanIssue target, @NonNull List<ScanIssue> contributors) {
        if (!DUP_RULE.equals(target.getRuleCode()) || contributors.size() < 2
                || target.getDescription() == null
                || target.getDescription().contains("其他位置：")) {
            return;
        }
        Set<String> partners = new LinkedHashSet<>();
        for (ScanIssue i : contributors) {
            if (i.getId().equals(target.getId())) {
                continue;
            }
            String partner = extractPartnerLocation(i.getDescription());
            if (partner != null) {
                partners.add(partner);
            }
        }
        StringBuilder desc = new StringBuilder(target.getDescription());
        for (String partner : partners) {
            desc.append(OTHER_LOCATION_PREFIX).append(partner);
        }
        target.setDescription(desc.toString());
    }

    /** 合并条描述追加"本文件同类问题共 N 处"行（仅多条且尚未追加过时） */
    private void appendCountSuffix(@NonNull ScanIssue target, int count, @NonNull List<int[]> mergedPoints) {
        if (count < 2 || target.getDescription() == null
                || target.getDescription().contains("本文件同类问题共")) {
            return;
        }
        target.setDescription(target.getDescription()
                + "\n本文件同类问题共 " + count + " 处，涉及行号："
                + IssuePoints.format(mergedPoints));
    }

    /** 组内除代表条外的 id 列表（待删除的被并记录） */
    private List<Long> collectDeleteIds(List<ScanIssue> group, @NonNull ScanIssue target) {
        List<Long> deleteIds = new ArrayList<>();
        for (ScanIssue i : group) {
            if (!i.getId().equals(target.getId())) {
                deleteIds.add(i.getId());
            }
        }
        return deleteIds;
    }

    /** 按五级模型重算任务问题计数（忽略项不计），一次 GROUP BY 完成，供扫描落库与启动迁移共用 */
    public void recomputeTaskCounts(Long taskId) {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        Map<String, Long> counts = countByLevel(taskId);
        long blockers = counts.getOrDefault("BLOCKER", 0L);
        long criticals = counts.getOrDefault("CRITICAL", 0L);
        long majors = counts.getOrDefault("MAJOR", 0L);
        long minors = counts.getOrDefault("MINOR", 0L);
        long infos = counts.getOrDefault("INFO", 0L);
        task.setBlockerCount((int) blockers);
        task.setCriticalCount((int) criticals);
        task.setMajorCount((int) majors);
        task.setMinorCount((int) minors);
        task.setInfoCount((int) infos);
        task.setTotalIssues((int) (blockers + criticals + majors + minors + infos));
        scanTaskMapper.updateById(task);
    }

    /** 任务各级别（五级码）未忽略问题数 */
    public Map<String, Long> countByLevel(Long taskId) {
        List<Map<String, Object>> rows = scanIssueMapper.selectMaps(
                new QueryWrapper<ScanIssue>()
                        .select("issue_level AS level", "COUNT(*) AS cnt")
                        .eq("task_id", taskId)
                        .eq("is_ignored", false)
                        .groupBy("issue_level"));
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            // 列标签大小写因方言而异（H2 大写、MySQL 小写），统一按小写键读取
            String level = null;
            Number cnt = null;
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() == null) continue;
                switch (e.getKey().toLowerCase()) {
                    case "level" -> level = e.getValue() != null ? String.valueOf(e.getValue()) : null;
                    case "cnt" -> cnt = e.getValue() instanceof Number n ? n : null;
                }
            }
            if (level != null && cnt != null) {
                counts.put(level, cnt.longValue());
            }
        }
        return counts;
    }

    private void deleteReportCache(Long taskId) {
        for (String fmt : new String[]{"pdf", "html"}) {
            try {
                Path p = Paths.get(workDir, "reports", "scan-report-" + taskId + "." + fmt);
                Files.deleteIfExists(p);
            } catch (Exception e) {
                log.debug("清理报告缓存失败 taskId={}, fmt={}: {}", taskId, fmt, e.getMessage());
            }
        }
    }
}
