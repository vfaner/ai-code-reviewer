package com.aicodereview.service;

import com.aicodereview.checker.CheckIssue;
import com.aicodereview.entity.CiScanRecord;
import com.aicodereview.entity.ScanIssue;
import com.aicodereview.entity.ScanTask;
import com.aicodereview.mapper.CiScanRecordMapper;
import com.aicodereview.mapper.ScanIssueMapper;
import com.aicodereview.mapper.ScanTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aicodereview.env.ProjectInfo;
import com.aicodereview.test.UnitTestRunner;
import com.aicodereview.util.IssuePoints;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 扫描任务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanTaskService {

    private final ScanTaskMapper scanTaskMapper;
    private final ScanIssueMapper scanIssueMapper;
    private final CiScanRecordMapper ciScanRecordMapper;
    private final CodeParseService codeParseService;
    private final ProjectEnvService projectEnvService;
    private final UnitTestRunner unitTestRunner;
    private final IgnoreRuleService ignoreRuleService;
    private final CiTriggerService ciTriggerService;
    private final CiCallbackService ciCallbackService;

    @Value("${app.work-dir:./work}")
    private String workDir;

    /**
     * 创建扫描任务（从粘贴的代码）
     */
    public ScanTask createFromPaste(String code, String taskName, String projectName,
                                    boolean includeTestCode, boolean enableAiReview) {
        // 保存到文件
        Path taskDir = createTaskDirectory();
        Path javaFile = taskDir.resolve("PastedCode.java");
        try {
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("保存代码失败: " + e.getMessage(), e);
        }

        ScanTask task = new ScanTask();
        task.setTaskName(taskName != null ? taskName : "粘贴代码扫描");
        task.setProjectName(projectName);
        task.setSourceType("PASTE");
        task.setStatus("PENDING");
        task.setIncludeTestCode(includeTestCode);
        task.setEnableAiReview(enableAiReview);
        task.setSkipUnitTest(true);
        task.setSnapshotPath(taskDir.toAbsolutePath().toString());
        task.setTotalFiles(0);
        task.setTotalLines(0);
        task.setBlockerCount(0);
        task.setCriticalCount(0);
        task.setMajorCount(0);
        task.setMinorCount(0);
        task.setInfoCount(0);
        task.setTotalIssues(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        scanTaskMapper.insert(task);
        return task;
    }

    /**
     * 创建扫描任务（从 ZIP 文件）
     */
    public ScanTask createFromZip(byte[] zipData, String taskName, String projectName,
                                  boolean includeTestCode, boolean enableAiReview,
                                  boolean skipUnitTest) {
        Path taskDir = createTaskDirectory();
        Path sourceDir = taskDir.resolve("src");

        try {
            Files.createDirectories(sourceDir);
            log.info("开始解压ZIP文件, 大小: {} 字节", zipData.length);
            int fileCount = unzip(zipData, sourceDir);
            log.info("解压完成, 共 {} 个文件", fileCount);
        } catch (IOException e) {
            log.error("解压文件失败", e);
            throw new RuntimeException("解压文件失败: " + e.getMessage(), e);
        }

        ScanTask task = new ScanTask();
        task.setTaskName(taskName != null ? taskName : "ZIP 代码扫描");
        task.setProjectName(projectName);
        task.setSourceType("ZIP");
        task.setStatus("PENDING");
        task.setIncludeTestCode(includeTestCode);
        task.setEnableAiReview(enableAiReview);
        task.setSkipUnitTest(skipUnitTest);
        task.setSnapshotPath(sourceDir.toAbsolutePath().toString());
        task.setTotalFiles(0);
        task.setTotalLines(0);
        task.setBlockerCount(0);
        task.setCriticalCount(0);
        task.setMajorCount(0);
        task.setMinorCount(0);
        task.setInfoCount(0);
        task.setTotalIssues(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        scanTaskMapper.insert(task);
        return task;
    }

    /**
     * 重新上传代码并扫描：替换既有任务的代码快照后重跑扫描。
     * 任务名称 / 项目名称 / 扫描选项保持不变，旧问题与旧报告缓存一并清掉。
     */
    public ScanTask rescanFromZip(Long taskId, byte[] zipData) {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }
        if ("PENDING".equals(task.getStatus()) || "RUNNING".equals(task.getStatus())) {
            throw new RuntimeException("任务正在排队或扫描中，请等待完成后再重新上传");
        }

        // 先解压到新目录，成功后再删旧快照：解压失败时不丢原有代码
        Path taskDir = createTaskDirectory();
        Path sourceDir = taskDir.resolve("src");
        try {
            Files.createDirectories(sourceDir);
            int fileCount = unzip(zipData, sourceDir);
            log.info("重新上传解压完成: taskId={}, 共 {} 个文件", taskId, fileCount);
        } catch (IOException e) {
            deleteRecursively(taskDir);
            throw new RuntimeException("解压文件失败: " + e.getMessage(), e);
        }

        deleteOldSnapshot(task.getSnapshotPath());

        // 旧问题与报告磁盘缓存必须清掉，否则与新扫描结果混在一起 / 下载到过期报告
        scanIssueMapper.delete(new QueryWrapper<ScanIssue>().eq("task_id", taskId));
        deleteReportCache(taskId);

        // updateById 会跳过 null 字段，重置类字段（error_message/started_at/completed_at）用 UpdateWrapper 显式置空
        scanTaskMapper.update(null, new UpdateWrapper<ScanTask>()
                .eq("id", taskId)
                .set("snapshot_path", sourceDir.toAbsolutePath().toString())
                .set("source_type", "ZIP")
                .set("status", "PENDING")
                .set("error_message", null)
                .set("started_at", null)
                .set("completed_at", null)
                .set("blocker_count", 0)
                .set("critical_count", 0)
                .set("major_count", 0)
                .set("minor_count", 0)
                .set("info_count", 0)
                .set("total_issues", 0)
                .set("total_files", 0)
                .set("total_lines", 0)
                .set("updated_at", LocalDateTime.now()));

        task.setSnapshotPath(sourceDir.toAbsolutePath().toString());
        task.setSourceType("ZIP");
        task.setStatus("PENDING");
        return task;
    }

    /**
     * 删除扫描任务：问题、报告磁盘缓存、本地留存的代码快照一并清除；
     * 关联的 CI 扫描记录解除任务引用（保留触发历史，记录页"查看结果"按钮按 task_id 空自动隐藏）。
     * 只动本任务的快照目录，其他任务不受影响。
     */
    public void deleteTask(Long taskId) {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }
        if ("PENDING".equals(task.getStatus()) || "RUNNING".equals(task.getStatus())) {
            throw new RuntimeException("任务正在排队或扫描中，请等待完成后再删除");
        }
        scanIssueMapper.delete(new QueryWrapper<ScanIssue>().eq("task_id", taskId));
        deleteReportCache(taskId);
        deleteOldSnapshot(task.getSnapshotPath());
        ciScanRecordMapper.update(null, new UpdateWrapper<CiScanRecord>()
                .set("task_id", null)
                .eq("task_id", taskId));
        scanTaskMapper.deleteById(taskId);
        log.info("扫描任务已删除: taskId={}", taskId);
    }

    /**
     * 删除旧代码快照。ZIP 任务的 snapshotPath 指向任务目录下的 src 子目录，需连任务目录一起删；
     * 仅允许删 work-dir/snapshots 下的目录，防误删外部路径。
     */
    private void deleteOldSnapshot(String snapshotPath) {
        if (snapshotPath == null || snapshotPath.isEmpty()) {
            return;
        }
        Path old = Paths.get(snapshotPath).toAbsolutePath().normalize();
        if (old.getFileName() != null && "src".equals(old.getFileName().toString())) {
            old = old.getParent();
        }
        Path snapshotsRoot = Paths.get(workDir, "snapshots").toAbsolutePath().normalize();
        if (old == null || !old.startsWith(snapshotsRoot)) {
            log.warn("旧快照路径不在 snapshots 目录下，跳过删除: {}", snapshotPath);
            return;
        }
        deleteRecursively(old);
    }

    /** 删除该任务的报告磁盘缓存（重新扫描后旧报告不得再被命中） */
    private void deleteReportCache(Long taskId) {
        for (String ext : new String[]{"pdf", "html"}) {
            try {
                Files.deleteIfExists(Paths.get(workDir, "reports", "scan-report-" + taskId + "." + ext));
            } catch (IOException e) {
                log.warn("删除报告缓存失败: taskId={}, ext={}, err={}", taskId, ext, e.getMessage());
            }
        }
    }

    /** 递归删除目录（快照替换用，失败仅告警不阻断主流程） */
    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删不掉不影响整体
                }
            });
        } catch (IOException e) {
            log.warn("删除目录失败: {}, err={}", dir, e.getMessage());
        }
    }

    /**
     * 异步执行扫描任务（受 scanTaskExecutor 有界线程池约束，队列满时抛 RejectedExecutionException）
     */
    @Async("scanTaskExecutor")
    public void executeScanAsync(Long taskId) {
        try {
            executeScan(taskId);
        } catch (Exception e) {
            log.error("扫描任务执行失败: taskId={}", taskId, e);
            updateTaskStatus(taskId, "FAILED", e.getMessage());
        }
        // 回写关联的 CI 扫描记录状态（executeScan 内部已吞异常并置 FAILED）
        ScanTask latest = scanTaskMapper.selectById(taskId);
        String status = latest != null && latest.getStatus() != null ? latest.getStatus() : "FAILED";
        try {
            ciTriggerService.syncRecordByTaskId(taskId, status);
        } catch (Exception e) {
            log.warn("回写 CI 记录状态失败: taskId={}, err={}", taskId, e.getMessage());
        }
        // CI 回调：commit status + MR/PR 自动回评（非 CI 任务无记录，直接返回；内部吞异常）
        ciCallbackService.onScanCompleted(taskId, status);
    }

    /**
     * 同步执行扫描任务
     */
    public void executeScan(Long taskId) {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }

        // 更新状态为运行中
        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        scanTaskMapper.updateById(task);

        try {
            Path sourcePath = Paths.get(task.getSnapshotPath());

            // 1. 环境检测
            log.info("开始环境检测...");
            ProjectInfo projectInfo = projectEnvService.analyzeProject(sourcePath);
            task.setJdkVersion(projectInfo.getJdkVersion());
            task.setSpringBootVersion(projectInfo.getSpringBootVersion());
            task.setTotalFiles(projectInfo.getJavaFileCount());
            task.setTotalLines(projectInfo.getTotalLines());

            // 2. 执行单元测试（可选）
            if (!Boolean.TRUE.equals(task.getSkipUnitTest())
                    && projectInfo.getBuildTool() != null
                    && !"NONE".equals(projectInfo.getBuildTool())) {
                log.info("开始执行单元测试...");
                UnitTestRunner.TestResult testResult = unitTestRunner.runTests(sourcePath, 300);
                log.info("单元测试完成: {} 个测试, {} 失败",
                        testResult.getTestsRun(), testResult.getTestsFailed());
                // 测试结果可以存入数据库，这里简化处理
            } else {
                log.info("跳过单元测试");
            }

            // 3. 执行代码检查
            log.info("开始代码静态检查...");
            List<CheckIssue> issues = codeParseService.scanAndCheck(
                    sourcePath,
                    Boolean.TRUE.equals(task.getIncludeTestCode()),
                    Boolean.TRUE.equals(task.getEnableAiReview()),
                    taskId,
                    task.getJdkVersion(),
                    task.getSpringBootVersion()
            );

            // 保存问题到数据库（同文件同规则的多个命中点合并为一条，自动应用忽略规则）
            String sourceRootStr = sourcePath.toAbsolutePath().toString();
            List<ScanIssue> savedIssues = saveIssues(taskId, issues, sourceRootStr);

            // 统计未忽略的问题（按合并后的记录数计）
            int blockerCount = 0, criticalCount = 0, majorCount = 0, minorCount = 0, infoCount = 0;
            for (ScanIssue saved : savedIssues) {
                if (Boolean.TRUE.equals(saved.getIsIgnored())) {
                    continue;
                }
                switch (saved.getIssueLevel()) {
                    case "BLOCKER" -> blockerCount++;
                    case "CRITICAL" -> criticalCount++;
                    case "MAJOR" -> majorCount++;
                    case "MINOR" -> minorCount++;
                    default -> infoCount++;
                }
            }

            // 更新任务状态
            task.setStatus("SUCCESS");
            task.setCompletedAt(LocalDateTime.now());
            task.setBlockerCount(blockerCount);
            task.setCriticalCount(criticalCount);
            task.setMajorCount(majorCount);
            task.setMinorCount(minorCount);
            task.setInfoCount(infoCount);
            task.setTotalIssues(blockerCount + criticalCount + majorCount + minorCount + infoCount);
            task.setTotalFiles(countJavaFiles(sourcePath));
            task.setTotalLines(countLines(sourcePath));
            task.setDurationSeconds(Duration.between(
                    task.getStartedAt(), LocalDateTime.now()
            ).getSeconds());

            scanTaskMapper.updateById(task);

            log.info("扫描任务完成: taskId={}, 原始命中={}, 合并后问题={}",
                    taskId, issues.size(), savedIssues.size());

        } catch (Exception e) {
            log.error("扫描任务失败: taskId={}", taskId, e);
            updateTaskStatus(taskId, "FAILED", e.getMessage());
        }
    }

    /**
     * 保存问题列表。
     * 同一文件 + 同一规则码的多个命中点（如多个魔法数字）合并为一条记录，
     * 全部问题位置存进 linePoints，避免逐行/逐字面量刷出大量重复记录；
     * DUP_CODE_BLOCK 同样参与合并，各条的重复对象（位置二）以"其他位置"行保留在描述里。
     *
     * @return 实际落库（合并后）的问题记录
     */
    private List<ScanIssue> saveIssues(Long taskId, List<CheckIssue> issues, String sourceRoot) {
        List<ScanIssue> saved = new ArrayList<>();
        Map<String, List<CheckIssue>> groups = new LinkedHashMap<>();
        for (CheckIssue issue : issues) {
            groups.computeIfAbsent(issue.getFilePath() + "|" + issue.getRuleCode(),
                    k -> new ArrayList<>()).add(issue);
        }
        for (List<CheckIssue> group : groups.values()) {
            List<CheckIssue> active = new ArrayList<>();
            List<CheckIssue> ignoredOnes = new ArrayList<>();
            for (CheckIssue issue : group) {
                if (ignoreRuleService.shouldIgnore(issue, sourceRoot)) {
                    ignoredOnes.add(issue);
                } else {
                    active.add(issue);
                }
            }
            // 行级忽略规则可能只命中其中几个点：非忽略点优先单独成条；全部被忽略时才落一条忽略记录
            if (!active.isEmpty()) {
                saved.add(persistMergedIssue(taskId, active, false));
            } else {
                saved.add(persistMergedIssue(taskId, ignoredOnes, true));
            }
        }
        return saved;
    }

    /**
     * 把同文件同规则的一组原始命中合并成一条 scan_issue 记录并插入。
     */
    private ScanIssue persistMergedIssue(Long taskId, List<CheckIssue> hits, boolean ignored) {
        // 以行号最早的命中为代表，列号等元数据沿用它
        CheckIssue rep = hits.get(0);
        for (CheckIssue h : hits) {
            if (h.getLineStart() < rep.getLineStart()) {
                rep = h;
            }
        }
        List<int[]> rawPoints = new ArrayList<>();
        boolean anyAi = false;
        int maxSeverity = 1;
        String snippet = null;
        String suggestion = null;
        for (CheckIssue h : hits) {
            // 行号 1-based：个别检查器文件级问题可能漏设为 0，归到第 1 行避免合并后区间为空
            int s = Math.max(1, h.getLineStart());
            int e = Math.max(s, h.getLineEnd());
            rawPoints.add(new int[]{s, e});
            anyAi |= h.isAiGenerated();
            maxSeverity = Math.max(maxSeverity, h.getSeverity());
            if (snippet == null && h.getCodeSnippet() != null && !h.getCodeSnippet().isBlank()) {
                snippet = h.getCodeSnippet();
            }
            if (suggestion == null && h.getSuggestion() != null && !h.getSuggestion().isBlank()) {
                suggestion = h.getSuggestion();
            }
        }
        List<int[]> points = IssuePoints.merge(rawPoints);

        ScanIssue scanIssue = new ScanIssue();
        scanIssue.setTaskId(taskId);
        scanIssue.setFilePath(rep.getFilePath());
        scanIssue.setFileName(extractFileName(rep.getFilePath()));
        scanIssue.setLineStart(points.get(0)[0]);
        scanIssue.setLineEnd(points.get(points.size() - 1)[1]);
        scanIssue.setColumnStart(rep.getColumnStart());
        scanIssue.setColumnEnd(rep.getColumnEnd());
        scanIssue.setIssueLevel(rep.getLevel().getCode());
        scanIssue.setCheckerType(rep.getCheckerType().getCode());
        scanIssue.setCheckerName(rep.getCheckerName());
        scanIssue.setRuleCode(rep.getRuleCode());
        scanIssue.setTitle(rep.getTitle());
        if (hits.size() == 1) {
            scanIssue.setDescription(rep.getDescription());
        } else {
            StringBuilder desc = new StringBuilder(rep.getDescription());
            // DUP 合并时保留各条的重复对象：非代表条的"位置二"追加为"其他位置"行
            if ("DUP_CODE_BLOCK".equals(rep.getRuleCode())) {
                Set<String> partners = new LinkedHashSet<>();
                for (CheckIssue h : hits) {
                    if (h == rep) {
                        continue;
                    }
                    String partner = IssueMergeService.extractPartnerLocation(h.getDescription());
                    if (partner != null) {
                        partners.add(partner);
                    }
                }
                for (String partner : partners) {
                    desc.append("\n  其他位置：").append(partner);
                }
            }
            desc.append("\n本文件同类问题共 ").append(hits.size())
                    .append(" 处，涉及行号：").append(IssuePoints.format(points));
            scanIssue.setDescription(desc.toString());
        }
        scanIssue.setCodeSnippet(snippet);
        scanIssue.setSuggestion(suggestion != null ? suggestion
                : SuggestionCatalog.get(rep.getRuleCode()));
        scanIssue.setSeverity(maxSeverity);
        scanIssue.setIsAiGenerated(anyAi);
        scanIssue.setOccurrenceCount(hits.size());
        // 多点才写 linePoints（单点直接用 line_start/line_end，保持旧数据形态一致）
        if (hits.size() > 1) {
            scanIssue.setLinePoints(IssuePoints.toLists(points));
        }
        scanIssue.setIsIgnored(ignored);
        if (ignored) {
            scanIssue.setIgnoreType("RULE");
            scanIssue.setIgnoreReason("匹配忽略规则");
        }
        scanIssue.setCreatedAt(LocalDateTime.now());
        scanIssueMapper.insert(scanIssue);
        return scanIssue;
    }

    /**
     * 获取任务详情
     */
    public ScanTask getById(Long id) {
        return scanTaskMapper.selectById(id);
    }

    /**
     * 分页获取任务列表
     */
    public IPage<ScanTask> listTasks(int pageNum, int pageSize, String keyword) {
        Page<ScanTask> page = new Page<>(pageNum, pageSize);
        QueryWrapper<ScanTask> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like("task_name", keyword).or().like("project_name", keyword);
        }
        wrapper.orderByDesc("created_at");
        return scanTaskMapper.selectPage(page, wrapper);
    }

    /**
     * 更新任务状态
     */
    public void updateTaskStatus(Long taskId, String status, String errorMessage) {
        ScanTask task = new ScanTask();
        task.setId(taskId);
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        task.setCompletedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskMapper.updateById(task);
    }

    /**
     * 创建任务目录
     */
    private Path createTaskDirectory() {
        // 毫秒时间戳 + 随机后缀：多项目并发上传时同一毫秒也不会撞目录
        String dirName = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path workPath = Paths.get(workDir, "snapshots", dirName);
        try {
            Files.createDirectories(workPath);
            return workPath;
        } catch (IOException e) {
            throw new RuntimeException("创建工作目录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判断是否为 macOS 压缩包元数据（__MACOSX 目录、._ 开头的 AppleDouble 文件、.DS_Store），
     * 这些条目不应出现在源码快照里
     */
    static boolean isMacJunkEntry(String entryName) {
        if (entryName == null) return true;
        for (String part : entryName.replace('\\', '/').split("/")) {
            if ("__MACOSX".equals(part) || ".DS_Store".equals(part) || part.startsWith("._")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解压 ZIP 文件
     * @return 解压的文件数（不含目录）
     */
    private int unzip(byte[] zipData, Path destDir) throws IOException {
        int count = 0;
        Path normDestDir = destDir.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // macOS 图形界面压缩会带 __MACOSX/ 目录和 AppleDouble（._开头）二进制元数据，
                // 它们不是源码且会以 .java 结尾，后续按 UTF-8 读取/解析会直接搞挂扫描
                if (isMacJunkEntry(entry.getName())) {
                    zis.closeEntry();
                    continue;
                }
                Path entryPath = destDir.resolve(entry.getName()).toAbsolutePath().normalize();
                // 防止路径穿越
                if (!entryPath.startsWith(normDestDir)) {
                    log.warn("跳过可疑路径（路径穿越防护）: {}", entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
                zis.closeEntry();
            }
        }
        return count;
    }

    private int countJavaFiles(Path dir) {
        return (int) codeParseService.findJavaFiles(dir, true).size();
    }

    private int countLines(Path dir) {
        List<Path> files = codeParseService.findJavaFiles(dir, true);
        int total = 0;
        for (Path file : files) {
            // Files.lines 的解码异常在终结操作时以 UncheckedIOException 抛出
            try (java.util.stream.Stream<String> lines = Files.lines(file)) {
                total += (int) lines.count();
            } catch (IOException | java.io.UncheckedIOException ignored) {
                // 非 UTF-8/二进制文件跳过，不能让一个坏文件搞挂整个任务
            }
        }
        return total;
    }

    private String extractFileName(String filePath) {
        if (filePath == null) return "";
        int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash >= 0) {
            return filePath.substring(lastSlash + 1);
        }
        return filePath;
    }
}
