package com.aicodereview.checker.local;

import com.aicodereview.checker.*;
import com.aicodereview.env.DependencyInfo;
import com.aicodereview.service.AdvisoryStore;
import com.aicodereview.service.ProjectEnvService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * 依赖漏洞扫描器（扫描级检查，PostScanChecker）
 *
 * 解析 pom.xml / build.gradle 中的依赖坐标，与内置离线漏洞库
 * （security/advisories.json，精编高频 CVE）匹配；
 * CRITICAL/HIGH 报 BUG，MEDIUM/LOW 报 WARNING。
 *
 * 在线增强（默认关闭）：app.dependency-scan.online=true 时，
 * 对离线库未命中的依赖补查 OSV（https://api.osv.dev/v1/query）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DependencyVulnChecker extends AbstractLocalChecker implements PostScanChecker {

    /** 在线补查的依赖数量上限 */
    private static final int MAX_ONLINE_QUERIES = 50;
    private static final String OSV_QUERY_URL = "https://api.osv.dev/v1/query";

    private final ProjectEnvService projectEnvService;
    private final AdvisoryStore advisoryStore;
    private final ObjectMapper objectMapper;

    @Value("${app.dependency-scan.online:false}")
    private boolean onlineEnabled;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.DEPENDENCY_VULN;
    }

    @Override
    public int getPriority() {
        return 15;
    }

    @Override
    protected void doCheck(CheckContext context, com.github.javaparser.ast.CompilationUnit cu,
                           List<CheckIssue> issues) {
        // 依赖扫描与单个 Java 文件无关，统一在 postScanCheck 执行
    }

    @Override
    public List<CheckIssue> postScanCheck(CheckContext templateContext) {
        List<CheckIssue> issues = new ArrayList<>();
        Path sourceRoot = templateContext.getSourceRoot();
        if (sourceRoot == null) {
            return issues;
        }
        try {
            List<DependencyInfo> deps = projectEnvService.parseDependenciesForScan(sourceRoot);
            if (deps.isEmpty()) {
                return issues;
            }

            // 定位构建文件（问题挂载位置）
            Path buildFile = projectEnvService.findPomXml(sourceRoot);
            if (buildFile == null) {
                buildFile = projectEnvService.findGradleBuild(sourceRoot);
            }
            String relativePath = relativize(sourceRoot, buildFile);
            List<String> buildLines = readLines(buildFile);

            int onlineQueries = 0;
            for (DependencyInfo dep : deps) {
                String version = dep.getVersion();
                // 属性占位符（${xxx}）与缺省版本无法判定，跳过
                if (version == null || version.isBlank() || version.contains("${")) {
                    continue;
                }
                int line = findArtifactLine(buildLines, dep.getArtifactId());

                List<AdvisoryStore.Advisory> hits =
                        advisoryStore.match(dep.getGroupId(), dep.getArtifactId(), version);
                for (AdvisoryStore.Advisory a : hits) {
                    issues.add(buildIssue(templateContext, dep, version, a.getCve(), a.getSeverity(),
                            a.getTitle(), a.getFixedVersion(), a.getUrl(), relativePath, line));
                }
                // 在线增强：离线库未命中时补查 OSV
                if (onlineEnabled && hits.isEmpty() && onlineQueries < MAX_ONLINE_QUERIES) {
                    onlineQueries++;
                    issues.addAll(queryOsv(templateContext, dep, version, relativePath, line));
                }
            }
            if (!issues.isEmpty()) {
                log.info("依赖漏洞扫描发现 {} 个已知漏洞（漏洞库 {} 条，在线增强 {}）",
                        issues.size(), advisoryStore.size(), onlineEnabled ? "开" : "关");
            }
        } catch (Exception e) {
            log.warn("依赖漏洞扫描失败: {}", e.getMessage());
        }
        return issues;
    }

    /**
     * OSV 在线补查（失败仅记日志，不影响扫描）
     */
    private List<CheckIssue> queryOsv(CheckContext templateContext, DependencyInfo dep,
                                      String version, String relativePath, int line) {
        List<CheckIssue> issues = new ArrayList<>();
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "package", Map.of(
                            "name", dep.getGroupId() + ":" + dep.getArtifactId(),
                            "ecosystem", "Maven"),
                    "version", version));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OSV_QUERY_URL))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.debug("OSV 查询 {}:{}:{} 返回 {}", dep.getGroupId(), dep.getArtifactId(),
                        version, response.statusCode());
                return issues;
            }
            JsonNode vulns = objectMapper.readTree(response.body()).path("vulns");
            int count = 0;
            for (JsonNode v : vulns) {
                if (count++ >= 3) {
                    break;
                }
                String id = v.path("id").asText("");
                if (id.isEmpty()) {
                    continue;
                }
                String summary = v.path("summary").asText("OSV 收录的已知漏洞");
                String severity = v.path("database_specific").path("severity").asText("MEDIUM");
                String cve = id.startsWith("CVE-") ? id
                        : v.path("aliases").toString().replaceAll("[\\[\\]\"]", "");
                String fixed = null;
                JsonNode ranges = v.path("affected").path(0).path("ranges").path(0).path("events");
                for (JsonNode ev : ranges) {
                    if (ev.has("fixed")) {
                        fixed = ev.get("fixed").asText();
                        break;
                    }
                }
                issues.add(buildIssue(templateContext, dep, version, cve.isEmpty() ? id : cve,
                        severity, summary, fixed, "https://osv.dev/vulnerability/" + id,
                        relativePath, line));
            }
        } catch (Exception e) {
            log.debug("OSV 在线查询失败 {}:{}:{} - {}", dep.getGroupId(), dep.getArtifactId(),
                    version, e.getMessage());
        }
        return issues;
    }

    private CheckIssue buildIssue(CheckContext templateContext, DependencyInfo dep, String version,
                                  String cve, String severity, String title, String fixedVersion,
                                  String url, String relativePath, int line) {
        // 按 CVE severity 对齐五级模型：CRITICAL→阻断、HIGH→严重、MEDIUM→主要、其余→次要
        IssueLevel level;
        if ("CRITICAL".equalsIgnoreCase(severity)) {
            level = IssueLevel.BLOCKER;
        } else if ("HIGH".equalsIgnoreCase(severity)) {
            level = IssueLevel.CRITICAL;
        } else if ("MEDIUM".equalsIgnoreCase(severity) || "MODERATE".equalsIgnoreCase(severity)) {
            level = IssueLevel.MAJOR;
        } else {
            level = IssueLevel.MINOR;
        }
        String ruleCode = "DEP_VULN_" + (cve != null && !cve.isBlank()
                ? cve.replaceAll("[^A-Za-z0-9]", "_") : "UNKNOWN");
        String coordinate = dep.getGroupId() + ":" + dep.getArtifactId() + ":" + version;
        String description = "依赖 " + coordinate + " 存在已知安全漏洞（"
                + (cve != null && !cve.isBlank() ? cve : "-") + "，严重性 "
                + (severity != null ? severity : "UNKNOWN") + "）：" + title;
        CheckIssue issue = createIssue(level, ruleCode, "依赖存在已知漏洞",
                description, relativePath, line, line);
        StringBuilder suggestion = new StringBuilder();
        if (fixedVersion != null && !fixedVersion.isBlank()) {
            suggestion.append("升级 ").append(dep.getArtifactId()).append(" 至 ")
                    .append(fixedVersion).append(" 及以上版本");
        } else {
            suggestion.append("关注官方安全公告并尽快升级 ").append(dep.getArtifactId());
        }
        if (url != null && !url.isBlank()) {
            suggestion.append("；详情：").append(url);
        }
        issue.setSuggestion(suggestion.toString());
        issue.setCodeSnippet(coordinate);
        return issue;
    }

    private String relativize(Path sourceRoot, Path buildFile) {
        if (buildFile == null) {
            return "pom.xml";
        }
        try {
            Path rel = sourceRoot.relativize(buildFile);
            String s = rel.toString().replace(java.io.File.separatorChar, '/');
            return s.isEmpty() ? buildFile.getFileName().toString() : s;
        } catch (Exception e) {
            return buildFile.getFileName().toString();
        }
    }

    private List<String> readLines(Path buildFile) {
        if (buildFile == null) {
            return Collections.emptyList();
        }
        try {
            return Files.readAllLines(buildFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 在构建文件中定位 artifactId 所在行（best-effort）
     */
    private int findArtifactLine(List<String> lines, String artifactId) {
        if (artifactId == null) {
            return 1;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(">" + artifactId + "<")
                    || lines.get(i).contains("'" + artifactId + "'")
                    || lines.get(i).contains("\"" + artifactId + "\"")
                    || lines.get(i).contains(":" + artifactId + ":")) {
                return i + 1;
            }
        }
        return 1;
    }
}
