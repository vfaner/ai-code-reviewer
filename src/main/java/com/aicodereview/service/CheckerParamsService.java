package com.aicodereview.service;

import com.aicodereview.entity.CheckerConfig;
import com.aicodereview.mapper.CheckerConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检查器参数服务
 *
 * 读取 checker_config.params（JSON）为各检查器提供阈值配置，
 * 未配置或解析失败时由调用方回退默认常量。解析结果按检查器编码缓存于进程内。
 *
 * 示例：UPDATE checker_config SET params='{"threshold":20}' WHERE checker_code='complexity';
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckerParamsService {

    private final CheckerConfigMapper checkerConfigMapper;
    private final ObjectMapper objectMapper;

    private final Map<String, JsonNode> paramsCache = new ConcurrentHashMap<>();

    /**
     * 获取整型参数，未配置时返回默认值
     */
    public int getInt(String checkerCode, String key, int defaultValue) {
        JsonNode v = getParams(checkerCode).get(key);
        return v != null && v.canConvertToInt() ? v.asInt(defaultValue) : defaultValue;
    }

    /**
     * 获取检查器参数 JSON（无配置时为空节点，永不为 null）
     */
    public JsonNode getParams(String checkerCode) {
        return paramsCache.computeIfAbsent(checkerCode, code -> {
            try {
                CheckerConfig cfg = checkerConfigMapper.selectOne(
                        new QueryWrapper<CheckerConfig>()
                                .eq("checker_code", code)
                                .last("LIMIT 1"));
                if (cfg != null && cfg.getParams() != null && !cfg.getParams().isBlank()) {
                    return objectMapper.readTree(cfg.getParams());
                }
            } catch (Exception e) {
                log.debug("解析检查器 {} 的 params 失败: {}", code, e.getMessage());
            }
            return NullNode.getInstance();
        });
    }

    /**
     * 清除全部参数缓存（配置新增/修改/删除后由 CheckerConfigService 调用，使新 params 立即生效）
     */
    public void evictAll() {
        paramsCache.clear();
    }
}
