package com.qqmu.jargus.service;

import com.qqmu.jargus.checker.IssueLevel;
import com.qqmu.jargus.entity.ReviewRule;
import com.qqmu.jargus.entity.ScanIssue;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.mapper.ReviewRuleMapper;
import com.qqmu.jargus.mapper.ScanIssueMapper;
import com.qqmu.jargus.mapper.ScanTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 三级（BUG/WARNING/INFO）→ 五级（BLOCKER/CRITICAL/MAJOR/MINOR/INFO）历史数据迁移。
 * 启动时执行，幂等可反复运行：
 * 1. scan_issue 旧三级行按规则码（SeverityCatalog）重新定级，并写五级秩到 severity；
 *    已是五级码的行只校正 severity 秩；
 * 2. 目录重对齐：五级行的规则码若在 SeverityCatalog 精确表且级别与目录不一致
 *    （如 R29 通配符导入 MINOR→INFO），按目录改写（跳过 AI 行；DEP_VULN_* 动态级别无精确条目，不受影响）；
 * 3. review_rule.default_level 旧值映射；
 * 4. 重算全部 scan_task 五级计数（委托 IssueMergeService，避免双份实现）；
 * 5. 删除旧评分时代的报告缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeverityMigrationService {

    private final ScanIssueMapper scanIssueMapper;
    private final ScanTaskMapper scanTaskMapper;
    private final ReviewRuleMapper reviewRuleMapper;
    private final IssueMergeService issueMergeService;

    @Value("${app.work-dir:./work}")
    private String workDir;

    /** 旧三级码 → 兜底五级（INFO 同码不同义，故显式映射而不是走 IssueLevel.fromCode） */
    private IssueLevel legacyGrade(String oldLevel, String ruleCode) {
        IssueLevel fallback = switch (oldLevel) {
            case "BUG" -> IssueLevel.CRITICAL;
            case "WARNING" -> IssueLevel.MAJOR;
            case "INFO" -> IssueLevel.MINOR;
            default -> IssueLevel.INFO;
        };
        // 依赖漏洞行级别随 CVE severity 动态变化，旧库无法区分 BUG 内部 CRITICAL/HIGH，
        // 统一取保守兜底（BUG→严重、WARNING→主要），不用前缀表覆盖
        if (ruleCode != null && ruleCode.startsWith("DEP_VULN_")) {
            return fallback;
        }
        return SeverityCatalog.gradeFor(ruleCode, fallback);
    }

    public void backfill() {
        List<ScanIssue> all = scanIssueMapper.selectList(
                new QueryWrapper<ScanIssue>().select(
                        "id", "task_id", "issue_level", "severity", "rule_code", "is_ai_generated"));
        int issueMigrated = 0;
        int realigned = 0;
        int rankFixed = 0;
        for (ScanIssue issue : all) {
            String oldLevel = issue.getIssueLevel();
            String ruleCode = issue.getRuleCode();
            boolean legacy = "BUG".equals(oldLevel) || "WARNING".equals(oldLevel) || "INFO".equals(oldLevel);
            IssueLevel target;
            boolean levelChanged;
            if (legacy) {
                target = legacyGrade(oldLevel, ruleCode);
                levelChanged = !target.getCode().equals(oldLevel);
            } else {
                // 已迁移 / 新扫描行：默认级别保持，仅保证 severity 秩一致；
                // 若规则码在目录精确表且级别与目录漂移（目录后续调过级），按目录重对齐。
                // AI 行跳过（级别由模型输出）；只认精确表，前缀兜底会错杀 DEP_VULN_* 动态级别
                target = IssueLevel.fromCode(oldLevel);
                levelChanged = false;
                if (!Boolean.TRUE.equals(issue.getIsAiGenerated())) {
                    IssueLevel catalog = SeverityCatalog.exactGrade(ruleCode);
                    if (catalog != null && catalog != target) {
                        target = catalog;
                        levelChanged = true;
                    }
                }
            }
            int rank = SeverityCatalog.rank(target);
            boolean rankChanged = issue.getSeverity() == null || issue.getSeverity() != rank;
            if (levelChanged || rankChanged) {
                scanIssueMapper.update(null, new UpdateWrapper<ScanIssue>()
                        .eq("id", issue.getId())
                        .set("issue_level", target.getCode())
                        .set("severity", rank));
                if (levelChanged) {
                    if (legacy) {
                        issueMigrated++;
                    } else {
                        realigned++;
                    }
                } else {
                    rankFixed++;
                }
            }
        }

        // review_rule.default_level 旧值迁移
        int rulesMigrated = migrateReviewRules();

        // 重算全部任务五级计数（旧库计数列语义已变，不能只动受影响任务）
        List<ScanTask> tasks = scanTaskMapper.selectList(new QueryWrapper<ScanTask>().select("id"));
        for (ScanTask task : tasks) {
            issueMergeService.recomputeTaskCounts(task.getId());
        }

        // 评分口径全变，报告缓存强制重建
        int cacheDeleted = deleteAllReportCaches();

        if (issueMigrated > 0 || realigned > 0 || rulesMigrated > 0 || rankFixed > 0) {
            log.info("五级严重度迁移: 问题改级 {} 条，目录重对齐 {} 条，秩校正 {} 条，规则 {} 条，任务 {} 个，清理报告缓存 {} 份",
                    issueMigrated, realigned, rankFixed, rulesMigrated, tasks.size(), cacheDeleted);
        }
    }

    private int migrateReviewRules() {
        try {
            List<ReviewRule> rules = reviewRuleMapper.selectList(null);
            int n = 0;
            for (ReviewRule rule : rules) {
                String old = rule.getDefaultLevel();
                if (old == null || old.isBlank()) continue;
                String upper = old.trim().toUpperCase();
                if (!"BUG".equals(upper) && !"WARNING".equals(upper) && !"INFO".equals(upper)) {
                    continue;
                }
                IssueLevel target = SeverityCatalog.gradeFor(rule.getRuleCode(), legacyGrade(upper, rule.getRuleCode()));
                reviewRuleMapper.update(null, new UpdateWrapper<ReviewRule>()
                        .eq("id", rule.getId())
                        .set("default_level", target.getCode()));
                n++;
            }
            // R29 通配符导入出厂默认 MINOR→INFO：只改仍是旧默认值的行，用户自定义过的级别不动
            // （内置种子数据没有该规则行，此 UPDATE 通常是 no-op，防用户手工添加过同名规则）
            n += reviewRuleMapper.update(null, new UpdateWrapper<ReviewRule>()
                    .eq("rule_code", "STYLE_WILDCARD_IMPORT")
                    .eq("default_level", "MINOR")
                    .set("default_level", IssueLevel.INFO.getCode()));
            return n;
        } catch (Exception e) {
            log.warn("review_rule 默认级别迁移失败: {}", e.getMessage());
            return 0;
        }
    }

    private int deleteAllReportCaches() {
        int n = 0;
        try {
            Path dir = Paths.get(workDir, "reports");
            if (!Files.isDirectory(dir)) return 0;
            try (var stream = Files.list(dir)) {
                List<Path> files = stream
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith("scan-report-")
                                    && (name.endsWith(".pdf") || name.endsWith(".html"));
                        })
                        .toList();
                for (Path p : files) {
                    if (Files.deleteIfExists(p)) n++;
                }
            }
        } catch (Exception e) {
            log.warn("清理报告缓存失败: {}", e.getMessage());
        }
        return n;
    }
}
