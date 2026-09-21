package com.aicodereview.service.version;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 知道当前运行版本，以及它与 GitHub 最新发布的关系。
 *
 * <p>远端检查绝不阻塞页面渲染：后台线程执行（启动后预热一次、过期再刷），
 * 结果缓存（成功 10 分钟、失败 1 分钟），过期条目边返回边后台刷新。
 * 离线内网主机上系统信息页即时渲染出「检查中/失败」徽标，
 * 当前版本与 jar 内置发布说明不受网络影响照常显示。
 */
@Service
@Slf4j
public class VersionService {

    private static final Duration POSITIVE_TTL = Duration.ofMinutes(10);
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(1);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    private final String currentVersion;
    private final ReleaseFeed feed;
    private final boolean enabled;
    /** 网络调用离开请求线程；每次刷新一条新守护线程。 */
    private final Executor refreshExecutor;
    /** 保证同一时刻只有一个在途检查：页面连刷不会往 GitHub 堆请求。 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().escapeHtml(true).build();

    private volatile Cached cached;
    /** 当前版本发布说明的渲染结果缓存（含 null 情形）。 */
    private volatile String notesCache;
    private volatile boolean notesLoaded;

    @org.springframework.beans.factory.annotation.Autowired
    public VersionService(ReleaseFeed feed,
                          @Value("${app.version:}") String currentVersion,
                          @Value("${app.update-check.enabled:true}") boolean enabled) {
        this(feed, StringUtils.hasText(currentVersion) ? currentVersion.trim() : null,
                enabled, daemonExecutor());
    }

    /** 测试缝：注入假 feed 与同步执行器（如 Runnable::run），包内可见。 */
    VersionService(ReleaseFeed feed, String currentVersion, boolean enabled,
                   Executor refreshExecutor) {
        this.feed = feed;
        this.currentVersion = currentVersion;
        this.enabled = enabled;
        this.refreshExecutor = refreshExecutor;
    }

    /** 每次检查一条短命守护线程，空闲时不在退出阶段拴住线程池。 */
    private static Executor daemonExecutor() {
        return task -> {
            Thread thread = new Thread(task, "aicr-version-check");
            thread.setDaemon(true);
            thread.start();
        };
    }

    /** 启动完成后尽快预热缓存，离开启动主路径；失败保持 UNKNOWN。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (enabled) {
            requestRefresh();
        }
    }

    public VersionInfo snapshot() {
        if (!enabled) {
            return VersionInfo.builder()
                    .currentVersion(currentVersion)
                    .state(VersionInfo.State.DISABLED)
                    .currentNotesHtml(currentNotes())
                    .build();
        }

        Cached c = cached;
        if (c == null || !c.validUntil().isAfter(Instant.now())) {
            // 启动预热还在途（c == null → 渲染 CHECKING），或条目过期：
            // 先返回旧值，后台检查替换
            requestRefresh();
            c = cached;
        }

        VersionInfo.VersionInfoBuilder builder = VersionInfo.builder()
                .currentVersion(currentVersion)
                .currentNotesHtml(currentNotes());
        if (c == null) {
            return builder.state(VersionInfo.State.CHECKING).build();
        }
        return builder
                .state(c.state())
                .latestTag(c.latestTag())
                .latestVersion(c.latestVersion())
                .releaseUrl(c.releaseUrl())
                .latestPublishedDate(c.latestPublishedDate())
                .build();
    }

    /** 除已在刷新外提交一次刷新；绝不向请求线程抛异常。 */
    private void requestRefresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        refreshExecutor.execute(() -> {
            try {
                refresh(Instant.now());
            } catch (Throwable t) {
                log.debug("版本检查任务失败: {}", t.toString());
            } finally {
                refreshing.set(false);
            }
        });
    }

    private synchronized Cached refresh(Instant now) {
        if (cached != null && cached.validUntil().isAfter(now)) {
            return cached; // 等待锁期间别的线程已刷完
        }
        try {
            ReleaseFeed.Release latest = feed.latest();
            String latestVersion = stripV(latest.tag());
            VersionInfo.State state;
            if (currentVersion == null) {
                state = VersionInfo.State.UNKNOWN;
            } else {
                state = compareVersions(latestVersion, currentVersion) > 0
                        ? VersionInfo.State.UPDATE_AVAILABLE
                        : VersionInfo.State.UP_TO_DATE;
            }
            cached = new Cached(state, latest.tag(), latestVersion, latest.htmlUrl(),
                    latest.publishedAt() == null ? null : DATE.format(latest.publishedAt()),
                    now.plus(POSITIVE_TTL));
        } catch (Exception e) {
            log.debug("版本检查失败: {}", e.toString());
            cached = new Cached(VersionInfo.State.UNKNOWN, null, null, null, null,
                    now.plus(NEGATIVE_TTL));
        }
        return cached;
    }

    /**
     * 当前运行版本的发布说明（安全 HTML）。优先构建期打包进 jar 的 markdown
     *（离线主机可用），回退到 GitHub 对应 tag 的 release 正文。
     */
    private String currentNotes() {
        if (!StringUtils.hasText(currentVersion)) {
            return null;
        }
        if (notesLoaded) {
            return notesCache;
        }
        synchronized (this) {
            if (notesLoaded) {
                return notesCache;
            }
            String markdown = null;
            String bundled = "release-notes/RELEASE_NOTES_v" + currentVersion + ".md";
            try {
                ClassPathResource resource = new ClassPathResource(bundled);
                if (resource.exists()) {
                    markdown = StreamUtils.copyToString(resource.getInputStream(),
                            StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.debug("内置发布说明不可读: {}", bundled, e);
            }
            if (!StringUtils.hasText(markdown)) {
                try {
                    markdown = feed.byTag("v" + currentVersion).body();
                } catch (Exception e) {
                    log.debug("GitHub 无 v{} 的发布说明: {}", currentVersion, e.toString());
                }
            }
            notesCache = StringUtils.hasText(markdown)
                    ? htmlRenderer.render(markdownParser.parse(markdown))
                    : null;
            notesLoaded = true;
            return notesCache;
        }
    }

    /** 去掉一个前导 v：{@code v1.1.1} → {@code 1.1.1}。 */
    static String stripV(String tag) {
        if (tag == null) {
            return "";
        }
        String t = tag.trim();
        return t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t;
    }

    /**
     * 点分版本号逐段比较（{@code 1.2.10} &gt; {@code 1.2.9}）；
     * 缺段与非数字段按 0 计。
     */
    static int compareVersions(String a, String b) {
        String[] sa = stripV(a).split("[^0-9A-Za-z]+");
        String[] sb = stripV(b).split("[^0-9A-Za-z]+");
        int n = Math.max(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            int na = numericSegment(i < sa.length ? sa[i] : null);
            int nb = numericSegment(i < sb.length ? sb[i] : null);
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return 0;
    }

    private static int numericSegment(String part) {
        if (part == null || part.isBlank()) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : part.toCharArray()) {
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else {
                break;
            }
        }
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
    }

    private record Cached(VersionInfo.State state, String latestTag, String latestVersion,
                          String releaseUrl, String latestPublishedDate, Instant validUntil) {
    }
}
