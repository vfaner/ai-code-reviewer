package com.aicodereview.service.version;

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
 * 用 JDK 自带 HTTP 客户端读 GitHub REST API 的发布信息的实现，
 * 超时/不跟随重定向的纪律与 AiChatClient 一致。
 *
 * <p>API 根地址由仓库地址（app.github-url）推导，改仓库不需要第二个配置；
 * 非 github.com 主机直接拒绝，不猜其他厂商的 API 形态。
 */
@Component
@Slf4j
public class GithubReleaseFeed implements ReleaseFeed {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** https://github.com/owner/repo(.git)(/) 或 http 变体。 */
    private static final Pattern HTTPS_URL =
            Pattern.compile("^https?://github\\.com/([^/]+)/([^/#?]+?)(?:\\.git)?/?$");

    /** git@github.com:owner/repo(.git) */
    private static final Pattern SSH_URL =
            Pattern.compile("^git@github\\.com:([^/]+)/([^/#?:]+?)(?:\\.git)?/?$");

    private final String apiBase;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public GithubReleaseFeed(@Value("${app.github-url:}") String githubUrl) {
        String parsed;
        try {
            parsed = toApiBase(githubUrl);
        } catch (IllegalArgumentException e) {
            // 仓库地址配置错不能挡住启动；之后每次检查只报 UNKNOWN
            log.warn("版本比对已禁用：{}", e.getMessage());
            parsed = null;
        }
        this.apiBase = parsed;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 浏览器/SSH 仓库地址 → REST API 根地址。
     *
     * @throws IllegalArgumentException 非 github.com 主机或地址无法解析
     */
    static String toApiBase(String githubUrl) {
        if (githubUrl == null || githubUrl.isBlank()) {
            throw new IllegalArgumentException("app.github-url 未配置");
        }
        String url = githubUrl.trim();
        Matcher m = HTTPS_URL.matcher(url);
        if (!m.matches()) {
            m = SSH_URL.matcher(url);
        }
        if (!m.matches()) {
            throw new IllegalArgumentException("不支持的版本比对仓库地址: " + url);
        }
        return "https://api.github.com/repos/" + m.group(1) + "/" + m.group(2);
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
            throw new IllegalStateException("app.github-url 推导不出 GitHub API 根地址");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ai-code-reviewer-update-check")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub API 返回 " + response.statusCode());
        }
        JsonNode json = mapper.readTree(response.body());
        String tag = json.path("tag_name").asText(null);
        String htmlUrl = json.path("html_url").asText(null);
        String published = json.path("published_at").asText(null);
        String body = json.path("body").asText("");
        if (tag == null || htmlUrl == null) {
            throw new IllegalStateException("GitHub API 响应缺少 tag_name/html_url");
        }
        Instant publishedAt = null;
        if (published != null && !published.isBlank()) {
            publishedAt = Instant.parse(published);
        }
        return new Release(tag, htmlUrl, publishedAt, body);
    }
}
