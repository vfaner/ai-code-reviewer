package com.aicodereview.checker;

import com.aicodereview.entity.CheckerConfig;
import com.aicodereview.mapper.CheckerConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 检查器注册中心
 * 管理所有检查器的注册、启用/禁用
 *
 * 注意：多个检查器可以共享同一个类型编码（如 CODE_STYLE 下有魔法数字、
 * System.out、风格补充三个检查器），因此按 code 索引为列表而非单值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckerRegistry {

    private final List<CodeChecker> checkers;
    private final CheckerConfigMapper checkerConfigMapper;

    /**
     * 检查器缓存（按 code 索引，同一 code 允许多个检查器，按优先级排序）
     */
    private final Map<String, List<CodeChecker>> checkerMap = new ConcurrentHashMap<>();

    /**
     * 全部检查器的扁平列表（按优先级排序）
     */
    private final List<CodeChecker> allCheckers = new CopyOnWriteArrayList<>();

    /**
     * 配置缓存
     */
    private final Map<String, CheckerConfig> configMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (checkers == null || checkers.isEmpty()) {
            log.warn("未找到任何检查器实现");
            return;
        }

        // 注册所有检查器（同 code 共存）
        for (CodeChecker checker : checkers) {
            checkerMap.computeIfAbsent(checker.getCheckerType().getCode(),
                    k -> new CopyOnWriteArrayList<>()).add(checker);
        }
        checkerMap.values().forEach(list ->
                list.sort(Comparator.comparingInt(CodeChecker::getPriority)));

        allCheckers.addAll(checkers);
        allCheckers.sort(Comparator.comparingInt(CodeChecker::getPriority));

        log.info("检查器注册完成，共 {} 个检查器（{} 个类型编码）",
                allCheckers.size(), checkerMap.size());
    }

    /**
     * 从数据库加载配置（刷新配置缓存）
     */
    public void refreshConfigs() {
        try {
            List<CheckerConfig> configs = checkerConfigMapper.selectList(
                    new QueryWrapper<CheckerConfig>().orderByAsc("sort_order", "id")
            );
            configMap.clear();
            for (CheckerConfig config : configs) {
                configMap.put(config.getCheckerCode(), config);
            }
            log.debug("检查器配置刷新完成，共 {} 条", configMap.size());
        } catch (Exception e) {
            log.warn("加载检查器配置失败: {}", e.getMessage());
        }
    }

    /**
     * 获取所有启用的本地检查器
     */
    public List<CodeChecker> getEnabledLocalCheckers() {
        refreshConfigs();
        return allCheckers.stream()
                .filter(CodeChecker::isLocal)
                .filter(this::isCheckerEnabled)
                .sorted(Comparator.comparingInt(CodeChecker::getPriority))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有启用的 AI 检查器
     */
    public List<CodeChecker> getEnabledAiCheckers() {
        refreshConfigs();
        return allCheckers.stream()
                .filter(c -> !c.isLocal())
                .filter(this::isCheckerEnabled)
                .sorted(Comparator.comparingInt(CodeChecker::getPriority))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有启用的检查器
     */
    public List<CodeChecker> getEnabledCheckers() {
        List<CodeChecker> all = new ArrayList<>();
        all.addAll(getEnabledLocalCheckers());
        all.addAll(getEnabledAiCheckers());
        return all;
    }

    /**
     * 根据 code 获取检查器（同 code 多个时返回优先级最高的一个）
     */
    public CodeChecker getChecker(String code) {
        List<CodeChecker> list = checkerMap.get(code);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /**
     * 检查指定检查器是否启用
     */
    public boolean isCheckerEnabled(CodeChecker checker) {
        // 如果有数据库配置，以数据库配置为准
        CheckerConfig config = configMap.get(checker.getCheckerType().getCode());
        if (config != null && config.getIsEnabled() != null) {
            return config.getIsEnabled();
        }
        // 否则使用检查器自身的默认值
        return checker.isEnabled();
    }

    /**
     * 获取所有检查器数量
     */
    public int getTotalCount() {
        return allCheckers.size();
    }
}
