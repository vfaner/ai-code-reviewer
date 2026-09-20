package com.aicodereview.service;

import com.aicodereview.util.MavenVersionComparator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

/**
 * 内置离线漏洞库
 *
 * 启动时加载 classpath:security/advisories.json（精编高频 CVE），
 * 按 groupId:artifactId 建索引，用 MavenVersionComparator 判定
 * 「当前版本低于修复版本（且不低于引入版本）」即命中。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisoryStore {

    private final ObjectMapper objectMapper;

    private final List<Advisory> advisories = new ArrayList<>();
    private final Map<String, List<Advisory>> index = new HashMap<>();

    /**
     * 漏洞通告条目
     */
    @Data
    public static class Advisory {
        private String id;
        private String cve;
        private String groupId;
        private String artifactId;
        /** 引入版本（可空，空表示所有更早版本均受影响） */
        private String introduced;
        /** 修复版本（低于该版本即受影响） */
        private String fixedVersion;
        /** CRITICAL / HIGH / MEDIUM / LOW */
        private String severity;
        private String title;
        private String url;
    }

    @PostConstruct
    public void load() {
        try (InputStream in = new ClassPathResource("security/advisories.json").getInputStream()) {
            List<Advisory> loaded = objectMapper.readValue(in, new TypeReference<List<Advisory>>() {});
            for (Advisory a : loaded) {
                if (a.getGroupId() == null || a.getArtifactId() == null || a.getFixedVersion() == null) {
                    log.warn("漏洞库条目缺少必要字段，跳过: {}", a.getId());
                    continue;
                }
                advisories.add(a);
                index.computeIfAbsent(key(a.getGroupId(), a.getArtifactId()), k -> new ArrayList<>()).add(a);
            }
            log.info("内置依赖漏洞库加载完成，共 {} 条通告", advisories.size());
        } catch (Exception e) {
            log.error("加载内置依赖漏洞库失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 匹配指定依赖坐标命中的漏洞通告
     */
    public List<Advisory> match(String groupId, String artifactId, String version) {
        if (groupId == null || artifactId == null || version == null || version.isBlank()) {
            return Collections.emptyList();
        }
        List<Advisory> candidates = index.get(key(groupId, artifactId));
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<Advisory> hits = new ArrayList<>();
        for (Advisory a : candidates) {
            try {
                boolean belowFixed = MavenVersionComparator.compare(version, a.getFixedVersion()) < 0;
                boolean aboveIntroduced = a.getIntroduced() == null || a.getIntroduced().isBlank()
                        || MavenVersionComparator.compare(version, a.getIntroduced()) >= 0;
                if (belowFixed && aboveIntroduced) {
                    hits.add(a);
                }
            } catch (Exception e) {
                log.debug("版本比较失败 {}:{}:{} vs {}: {}", groupId, artifactId, version,
                        a.getFixedVersion(), e.getMessage());
            }
        }
        return hits;
    }

    public int size() {
        return advisories.size();
    }

    private static String key(String groupId, String artifactId) {
        return groupId.trim().toLowerCase() + ":" + artifactId.trim().toLowerCase();
    }
}
