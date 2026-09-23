package com.qqmu.jargus.service;

import com.qqmu.jargus.dto.QualityGateResult;
import com.qqmu.jargus.entity.ScanIssue;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.mapper.ScanIssueMapper;
import com.qqmu.jargus.mapper.ScanTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量门禁服务（五级严重度模型，对标 SonarQube）
 *
 * 评分规则（默认值如下，两级可配）：
 * - 基础分 100
 * - 每个 BLOCKER 扣 25 分（app.gate.blocker-weight）
 * - 每个 CRITICAL 扣 15 分（app.gate.critical-weight）
 * - 每个 MAJOR 扣 5 分（app.gate.major-weight）
 * - 每个 MINOR 扣 1 分（app.gate.minor-weight）
 * - INFO 不扣分（app.gate.info-weight=0，仅提示）
 * - 最低 0 分
 *
 * 质量等级：EXCELLENT ≥90 / GOOD ≥75 / FAIR ≥60 / POOR
 * 门禁通过条件（默认）：阻断数 ≤ blocker-limit(0) 且评分 ≥ pass-score(80)
 *
 * 配置优先级：gate_setting 表（管理员在质量门禁页自定义，运行时即时生效）
 * &gt; application.yml 的 app.gate.* &gt; 代码默认值。评分/评级/门禁结论都是
 * 渲染时实时计算，不落库，因此改完分值历史任务无需重扫即按新规则展示。
 *
 * 技术债：所有未忽略问题的标准修复时间之和（见 TechnicalDebtCatalog）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityGateService {

    /** 可自定义的配置键（与 getGateSettings 返回键一致） */
    private static final String[] SETTING_KEYS = {
            "blockerWeight", "criticalWeight", "majorWeight", "minorWeight", "infoWeight",
            "passScore", "blockerLimit", "excellent", "good", "fair"
    };

    private final ScanTaskMapper scanTaskMapper;
    private final ScanIssueMapper scanIssueMapper;
    private final JdbcTemplate jdbcTemplate;

    // 扣分权重（yml 默认值；gate_setting 表有自定义行时以数据库为准）
    @Value("${app.gate.blocker-weight:25}")
    private int blockerWeight;
    @Value("${app.gate.critical-weight:15}")
    private int criticalWeight;
    @Value("${app.gate.major-weight:5}")
    private int majorWeight;
    @Value("${app.gate.minor-weight:1}")
    private int minorWeight;
    @Value("${app.gate.info-weight:0}")
    private int infoWeight;

    // 门禁阈值（可配置）
    @Value("${app.gate.pass-score:80}")
    private int passScoreThreshold;
    @Value("${app.gate.blocker-limit:0}")
    private int blockerLimitForPass;

    // 等级分界（可配置）
    @Value("${app.gate.excellent:90}")
    private int excellentThreshold;
    @Value("${app.gate.good:75}")
    private int goodThreshold;
    @Value("${app.gate.fair:60}")
    private int fairThreshold;

    /**
     * 评估扫描任务的质量门禁
     */
    public QualityGateResult evaluateTask(Long taskId) {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            return QualityGateResult.builder()
                    .score(0)
                    .level("POOR")
                    .passed(false)
                    .detail("任务不存在")
                    .build();
        }

        // 生效配置：数据库自定义值优先，缺省回落 application.yml
        Map<String, Integer> s = effectiveSettings();
        int wBlocker = s.get("blockerWeight");
        int wCritical = s.get("criticalWeight");
        int wMajor = s.get("majorWeight");
        int wMinor = s.get("minorWeight");
        int wInfo = s.get("infoWeight");
        int passScore = s.get("passScore");
        int blockerLimit = s.get("blockerLimit");

        // 一次 GROUP BY 取五级未忽略问题数
        Map<String, Integer> counts = countActiveByLevel(taskId);
        int blockerCount = counts.getOrDefault("BLOCKER", 0);
        int criticalCount = counts.getOrDefault("CRITICAL", 0);
        int majorCount = counts.getOrDefault("MAJOR", 0);
        int minorCount = counts.getOrDefault("MINOR", 0);
        int infoCount = counts.getOrDefault("INFO", 0);
        int totalIssues = blockerCount + criticalCount + majorCount + minorCount + infoCount;
        int ignoredCount = countIgnored(taskId);

        // 计算评分
        int blockerDeduct = blockerCount * wBlocker;
        int criticalDeduct = criticalCount * wCritical;
        int majorDeduct = majorCount * wMajor;
        int minorDeduct = minorCount * wMinor;
        int score = 100 - blockerDeduct - criticalDeduct - majorDeduct - minorDeduct
                - infoCount * wInfo;
        score = Math.max(0, Math.min(100, score));

        // 质量等级
        String level;
        if (score >= s.get("excellent")) level = "EXCELLENT";
        else if (score >= s.get("good")) level = "GOOD";
        else if (score >= s.get("fair")) level = "FAIR";
        else level = "POOR";

        // 门禁判定：阻断数一票否决，且评分达标
        boolean passed = blockerCount <= blockerLimit && score >= passScore;

        // 技术债估算
        long debtMinutes = calculateDebtMinutes(taskId);
        String debtText = TechnicalDebtCatalog.format(debtMinutes);

        // 详情
        StringBuilder detail = new StringBuilder();
        if (!passed) {
            if (blockerCount > blockerLimit) {
                detail.append("存在 ").append(blockerCount).append(" 个阻断(BLOCKER)问题，");
            }
            if (score < passScore) {
                detail.append("质量评分 ").append(score).append(" 低于阈值 ").append(passScore);
            }
        } else {
            detail.append("质量门禁通过：无阻断问题，评分 ").append(score);
        }
        if (debtMinutes > 0) {
            detail.append("；预估技术债 ").append(debtText);
        }

        return QualityGateResult.builder()
                .score(score)
                .level(level)
                .passed(passed)
                .blockerCount(blockerCount)
                .criticalCount(criticalCount)
                .majorCount(majorCount)
                .minorCount(minorCount)
                .infoCount(infoCount)
                .blockerDeduct(blockerDeduct)
                .criticalDeduct(criticalDeduct)
                .majorDeduct(majorDeduct)
                .minorDeduct(minorDeduct)
                .totalIssues(totalIssues)
                .ignoredCount(ignoredCount)
                .debtMinutes(debtMinutes)
                .debtText(debtText)
                .detail(detail.toString())
                .build();
    }

    /**
     * 门禁配置（供页面渲染真实阈值，避免文案与代码不一致）
     */
    public Map<String, Object> getGateSettings() {
        return new LinkedHashMap<>(effectiveSettings());
    }

    /** 生效配置缓存：读库成功后填充，保存/重置后置 null 失效 */
    private volatile Map<String, Integer> cachedSettings;

    /**
     * 生效配置 = gate_setting 表自定义行（id=1）覆盖 application.yml 默认值。
     * 表不存在或查询失败（如首次启动建表前）时回落默认值，不缓存失败结果。
     */
    private Map<String, Integer> effectiveSettings() {
        Map<String, Integer> cached = cachedSettings;
        if (cached != null) {
            return cached;
        }
        Map<String, Integer> merged = defaults();
        try {
            List<Map<String, Object>> rows =
                    jdbcTemplate.queryForList("SELECT * FROM gate_setting WHERE id = 1");
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                overlay(row, "blocker_weight", merged, "blockerWeight");
                overlay(row, "critical_weight", merged, "criticalWeight");
                overlay(row, "major_weight", merged, "majorWeight");
                overlay(row, "minor_weight", merged, "minorWeight");
                overlay(row, "info_weight", merged, "infoWeight");
                overlay(row, "pass_score", merged, "passScore");
                overlay(row, "blocker_limit", merged, "blockerLimit");
                overlay(row, "excellent_score", merged, "excellent");
                overlay(row, "good_score", merged, "good");
                overlay(row, "fair_score", merged, "fair");
            }
        } catch (Exception e) {
            log.warn("读取门禁自定义配置失败，使用配置文件默认值: {}", e.getMessage());
            return merged;
        }
        cachedSettings = merged;
        return merged;
    }

    /** 行内列值非空才覆盖（列标签大小写因方言而异，统一小写匹配） */
    private void overlay(Map<String, Object> row, String column,
                         Map<String, Integer> target, String key) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().equals(column)
                    && e.getValue() instanceof Number n) {
                target.put(key, n.intValue());
            }
        }
    }

    /** application.yml（含代码兜底默认值）构成的出厂配置 */
    private Map<String, Integer> defaults() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("blockerWeight", blockerWeight);
        m.put("criticalWeight", criticalWeight);
        m.put("majorWeight", majorWeight);
        m.put("minorWeight", minorWeight);
        m.put("infoWeight", infoWeight);
        m.put("passScore", passScoreThreshold);
        m.put("blockerLimit", blockerLimitForPass);
        m.put("excellent", excellentThreshold);
        m.put("good", goodThreshold);
        m.put("fair", fairThreshold);
        return m;
    }

    /**
     * 保存管理员自定义的门禁配置（覆盖式 upsert 到 gate_setting 单行）。
     * 未提交的键沿用当前生效值；非法值抛 IllegalArgumentException（中文提示直接回给前端）。
     */
    public void saveSettings(Map<String, Object> input) {
        Map<String, Integer> merged = new LinkedHashMap<>(effectiveSettings());
        for (String key : SETTING_KEYS) {
            Object raw = input.get(key);
            if (raw == null) {
                continue;
            }
            int value;
            try {
                value = raw instanceof Number n ? n.intValue()
                        : Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("参数 " + key + " 必须是整数");
            }
            merged.put(key, value);
        }
        validate(merged);

        Object[] args = {
                merged.get("blockerWeight"), merged.get("criticalWeight"),
                merged.get("majorWeight"), merged.get("minorWeight"), merged.get("infoWeight"),
                merged.get("passScore"), merged.get("blockerLimit"),
                merged.get("excellent"), merged.get("good"), merged.get("fair")
        };
        int updated = jdbcTemplate.update(
                "UPDATE gate_setting SET blocker_weight=?, critical_weight=?, major_weight=?, " +
                        "minor_weight=?, info_weight=?, pass_score=?, blocker_limit=?, " +
                        "excellent_score=?, good_score=?, fair_score=?, updated_at=CURRENT_TIMESTAMP " +
                        "WHERE id=1", args);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO gate_setting (id, blocker_weight, critical_weight, major_weight, " +
                            "minor_weight, info_weight, pass_score, blocker_limit, " +
                            "excellent_score, good_score, fair_score, updated_at) " +
                            "VALUES (1, ?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)", args);
        }
        cachedSettings = null;
        log.info("门禁配置已更新: {}", merged);
    }

    /** 恢复出厂：删除自定义行，回到 application.yml 默认值 */
    public void resetSettings() {
        try {
            jdbcTemplate.update("DELETE FROM gate_setting WHERE id = 1");
        } catch (Exception e) {
            log.warn("重置门禁配置失败: {}", e.getMessage());
        }
        cachedSettings = null;
        log.info("门禁配置已恢复默认值");
    }

    /** 合法性校验：权重/分数 0-100，阻断上限 0-1000，等级分界递减 */
    private void validate(Map<String, Integer> s) {
        for (String key : new String[]{"blockerWeight", "criticalWeight", "majorWeight",
                "minorWeight", "infoWeight", "passScore"}) {
            int v = s.get(key);
            if (v < 0 || v > 100) {
                throw new IllegalArgumentException("扣分权重与通过分必须在 0-100 之间：" + key + "=" + v);
            }
        }
        if (s.get("blockerLimit") < 0 || s.get("blockerLimit") > 1000) {
            throw new IllegalArgumentException("阻断数上限必须在 0-1000 之间");
        }
        int excellent = s.get("excellent");
        int good = s.get("good");
        int fair = s.get("fair");
        if (excellent < 0 || excellent > 100 || good < 0 || good > 100 || fair < 0 || fair > 100) {
            throw new IllegalArgumentException("评级分界必须在 0-100 之间");
        }
        if (!(excellent >= good && good >= fair)) {
            throw new IllegalArgumentException("评级分界必须满足：优秀 ≥ 良好 ≥ 一般");
        }
    }

    /**
     * 汇总任务的技术债（分钟）：所有未忽略问题的标准修复时间之和
     */
    private long calculateDebtMinutes(Long taskId) {
        try {
            List<ScanIssue> issues = scanIssueMapper.selectList(
                    new QueryWrapper<ScanIssue>()
                            .select("rule_code")
                            .eq("task_id", taskId)
                            .eq("is_ignored", false)
            );
            long total = 0;
            for (ScanIssue issue : issues) {
                total += TechnicalDebtCatalog.minutesFor(issue.getRuleCode());
            }
            return total;
        } catch (Exception e) {
            log.warn("计算任务 {} 技术债失败: {}", taskId, e.getMessage());
            return 0;
        }
    }

    /** 未忽略问题按五级分组计数（一次 GROUP BY） */
    private Map<String, Integer> countActiveByLevel(Long taskId) {
        List<Map<String, Object>> rows = scanIssueMapper.selectMaps(
                new QueryWrapper<ScanIssue>()
                        .select("issue_level AS level", "COUNT(*) AS cnt")
                        .eq("task_id", taskId)
                        .eq("is_ignored", false)
                        .groupBy("issue_level"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            // 列标签大小写因方言而异（H2 大写、MySQL 小写），统一按小写键读取
            String level = null;
            Integer cnt = null;
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() == null) continue;
                switch (e.getKey().toLowerCase()) {
                    case "level" -> level = e.getValue() != null ? String.valueOf(e.getValue()) : null;
                    case "cnt" -> cnt = e.getValue() instanceof Number n ? n.intValue() : null;
                }
            }
            if (level != null && cnt != null) {
                counts.put(level, cnt);
            }
        }
        return counts;
    }

    private int countIgnored(Long taskId) {
        Long count = scanIssueMapper.selectCount(
                new QueryWrapper<ScanIssue>()
                        .eq("task_id", taskId)
                        .eq("is_ignored", true)
        );
        return count != null ? count.intValue() : 0;
    }
}
