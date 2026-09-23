package com.qqmu.jargus.service.version;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 读 Gitee REST API（v5）发布信息的实现，与 {@link GithubReleaseFeed} 同纪律：
 * 3 秒超时、不跟随重定向、地址配错只降级不挡启动。
 *
 * <p>用于系统信息页展示「Gitee 最新版本」；Gitee 不可达不影响 GitHub 主比对结果。
 */
@Component
@Slf4j
public class GiteeReleaseFeed implements ReleaseFeed {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** https://gitee.com/owner/repo(.git)(/) 或 http 变体。 */
    private static final Pattern HTTPS_URL =
            Pattern.compile("^https?://gitee\\.com/([^/]+)/([^/#?]+?)(?:\\.git)?/?$");

    /** git@gitee.com:owner/repo(.git) */
    private static final Pattern SSH_URL =
            Pattern.compile("^git@gitee\\.com:([^/]+)/([^/#?:]+?)(?:\\.git)?/?$");

    private final String apiBase;
    private final String repoUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public GiteeReleaseFeed(@Value("${app.gitee-url:}") String giteeUrl) {
        String parsedApi = null;
        String parsedRepo = null;
        try {
            String[] both = toApiBase(giteeUrl);
            parsedApi = both[0];
            parsedRepo = both[1];
        } catch (IllegalArgumentException e) {
            // 镜像地址未配置/配错只影响这一栏展示
            log.warn("Gitee 版本展示已禁用：{}", e.getMessage());
        }
        this.apiBase = parsedApi;
        this.repoUrl = parsedRepo;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 浏览器/SSH 仓库地址 → [REST API 根地址, 浏览器仓库地址]。
     *
     * @throws IllegalArgumentException 非 gitee.com 主机或地址无法解析
     */
    static String[] toApiBase(String giteeUrl) {
        if (giteeUrl == null || giteeUrl.isBlank()) {
            throw new IllegalArgumentException("app.gitee-url 未配置");
        }
        String url = giteeUrl.trim();
        Matcher m = HTTPS_URL.matcher(url);
        if (!m.matches()) {
            m = SSH_URL.matcher(url);
        }
        if (!m.matches()) {
            throw new IllegalArgumentException("不支持的 Gitee 仓库地址: " + url);
        }
        String repo = "https://gitee.com/" + m.group(1) + "/" + m.group(2);
        return new String[]{"https://gitee.com/api/v5/repos/" + m.group(1) + "/" + m.group(2), repo};
    }

    @Override
    public Release latest() throws Exception {
        return fetch(apiBase + "/releases/latest");
    }

    @Override
    public Release byTag(String tag) throws Exception {
        return fetch(apiBase + "/releases/tags/"
                + URLEncoder.encode(tag, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private Release fetch(String url) throws Exception {
        if (apiBase == null) {
            throw new IllegalStateException("app.gitee-url 推导不出 Gitee API 根地址");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "jargus-update-check")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Gitee API 返回 " + response.statusCode());
        }
        JsonNode json = mapper.readTree(response.body());
        String tag = json.path("tag_name").asText(null);
        if (tag == null) {
            throw new IllegalStateException("Gitee API 响应缺少 tag_name");
        }
        // v5 发布对象不保证带 html_url，缺省时按仓库地址拼发布页
        String htmlUrl = json.path("html_url").asText(null);
        if (htmlUrl == null || htmlUrl.isBlank()) {
            htmlUrl = repoUrl + "/releases/" + URLEncoder.encode(tag, StandardCharsets.UTF_8);
        }
        String published = json.path("created_at").asText(null);
        Instant publishedAt = null;
        if (published != null && !published.isBlank()) {
            try {
                publishedAt = Instant.parse(published);
            } catch (Exception ignored) {
                // 时间格式意外时只丢日期，不丢版本比对
            }
        }
        return new Release(tag, htmlUrl, publishedAt, json.path("body").asText(""));
    }
}
