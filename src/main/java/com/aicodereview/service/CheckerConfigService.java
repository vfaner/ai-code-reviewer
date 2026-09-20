package com.aicodereview.service;

import com.aicodereview.entity.CheckerConfig;
import com.aicodereview.mapper.CheckerConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 检查器配置服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckerConfigService {

    private final CheckerConfigMapper checkerConfigMapper;

    public List<CheckerConfig> listAll() {
        return checkerConfigMapper.selectList(
                new QueryWrapper<CheckerConfig>().orderByAsc("sort_order")
        );
    }

    public List<CheckerConfig> listByCategory(String category) {
        QueryWrapper<CheckerConfig> wrapper = new QueryWrapper<>();
        if (category != null && !category.isEmpty()) {
            wrapper.eq("checker_category", category);
        }
        wrapper.orderByAsc("sort_order");
        return checkerConfigMapper.selectList(wrapper);
    }

    public CheckerConfig getById(Long id) {
        return checkerConfigMapper.selectById(id);
    }

    public CheckerConfig create(CheckerConfig config) {
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        if (config.getIsEnabled() == null) config.setIsEnabled(true);
        if (config.getIsLocal() == null) config.setIsLocal(true);
        checkerConfigMapper.insert(config);
        return config;
    }

    public boolean update(CheckerConfig config) {
        config.setUpdatedAt(LocalDateTime.now());
        return checkerConfigMapper.updateById(config) > 0;
    }

    public boolean delete(Long id) {
        return checkerConfigMapper.deleteById(id) > 0;
    }

    /**
     * 切换启用状态
     *
     * @return 切换后的新状态（true=启用）；配置不存在时返回 false
     */
    public boolean toggle(Long id) {
        CheckerConfig config = checkerConfigMapper.selectById(id);
        if (config == null) return false;
        boolean newState = !Boolean.TRUE.equals(config.getIsEnabled());
        config.setIsEnabled(newState);
        config.setUpdatedAt(LocalDateTime.now());
        checkerConfigMapper.updateById(config);
        return newState;
    }
}
