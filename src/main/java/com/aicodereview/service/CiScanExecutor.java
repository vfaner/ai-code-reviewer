package com.aicodereview.service;

import com.aicodereview.entity.CiTriggerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CI 异步扫描执行器
 *
 * 独立成 Bean 是为了让 @Async 通过 Spring 代理生效（同类内部自调用不会走代理），
 * 并统一受 scanTaskExecutor 有界线程池约束，多项目并发扫描时排队执行、互不影响。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CiScanExecutor {

    private final CiTriggerService ciTriggerService;
    private final ScanTaskService scanTaskService;
    private final CiCallbackService ciCallbackService;

    /**
     * 克隆仓库并触发扫描（在 scanTaskExecutor 线程池中执行）
     */
    @Async("scanTaskExecutor")
    public void runGitScan(Long recordId, CiTriggerConfig config, String repoUrl,
                           String branch, String commitId) {
        Path sourceDir = null;
        try {
            ciTriggerService.updateRecordStatus(recordId, "RUNNING", null);

            // 克隆代码
            sourceDir = cloneRepo(config, repoUrl, branch, commitId, recordId);
            if (sourceDir == null) {
                ciTriggerService.updateRecordStatus(recordId, "FAILED", null);
                // 尚未生成扫描任务，按记录级失败回调（commit status = error）
                ciCallbackService.onRecordFailed(recordId);
                return;
            }

            byte[] zipData = zipDirectory(sourceDir);
            var task = scanTaskService.createFromZip(
                    zipData,
                    "CI-" + recordId,
                    null,
                    Boolean.TRUE.equals(config.getIncludeTestCode()),
                    Boolean.TRUE.equals(config.getEnableAiReview()),
                    Boolean.TRUE.equals(config.getSkipUnitTest())
            );

            ciTriggerService.attachTask(recordId, task.getId());
            ciTriggerService.updateRecordStatus(recordId, null, "/scan/result/" + task.getId());
            scanTaskService.executeScanAsync(task.getId());

        } catch (Exception e) {
            log.error("CI 扫描触发失败: recordId={}", recordId, e);
            ciTriggerService.updateRecordStatus(recordId, "FAILED", null);
            ciCallbackService.onRecordFailed(recordId);
        } finally {
            // 扫描基于 createFromZip 落盘的快照进行，克隆目录用完即删，避免磁盘泄漏
            if (sourceDir != null) {
                deleteRecursively(sourceDir);
            }
        }
    }

    /**
     * 递归删除目录（CI 克隆临时目录清理）
     */
    private void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    log.warn("删除 CI 临时文件失败: {}", p);
                }
            });
        } catch (Exception e) {
            log.warn("清理 CI 克隆目录失败: {}", dir, e);
        }
    }

    /**
     * 克隆 Git 仓库到按记录隔离的临时目录 ./work/ci/{recordId}
     */
    private Path cloneRepo(CiTriggerConfig config, String repoUrl, String branch,
                           String commitId, Long recordId) {
        try {
            Path workDir = Paths.get("./work/ci", String.valueOf(recordId));
            Files.createDirectories(workDir);

            var cloneCmd = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(workDir.toFile())
                    .setBranch(branch != null ? branch : "main")
                    .setDepth(1);

            // 私有库凭据（AES 加密存储，使用时解密）
            String token = CiTriggerService.decryptStored(config.getRepoToken());
            if (token != null && !token.isEmpty()) {
                String username = config.getRepoUsername() != null && !config.getRepoUsername().isBlank()
                        ? config.getRepoUsername().trim() : "oauth2";
                cloneCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, token));
            }

            try (Git git = cloneCmd.call()) {
                // 如果指定了 commit，checkout 到该 commit
                if (commitId != null && !commitId.isEmpty()) {
                    git.checkout().setName(commitId).call();
                }
            }

            return workDir;
        } catch (GitAPIException | java.io.IOException e) {
            log.error("克隆仓库失败: url={}, err={}", repoUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 将目录打包为 ZIP 字节数组
     */
    private byte[] zipDirectory(Path sourceDir) throws Exception {
        Path zipPath = Files.createTempFile("ci-scan-", ".zip");
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath))) {
            Files.walk(sourceDir)
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        try {
                            String entryName = sourceDir.relativize(path).toString()
                                    .replace(File.separatorChar, '/');
                            var entry = new java.util.zip.ZipEntry(entryName);
                            zos.putNextEntry(entry);
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (Exception e) {
                            log.warn("ZIP 添加文件失败: {}", path);
                        }
                    });
        }
        byte[] data = Files.readAllBytes(zipPath);
        Files.deleteIfExists(zipPath);
        return data;
    }
}
