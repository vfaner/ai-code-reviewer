package com.aicodereview.controller;

import com.aicodereview.dto.Result;
import com.aicodereview.entity.CheckerConfig;
import com.aicodereview.service.CheckerConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检查器配置控制器
 */
@RestController
@RequestMapping("/api/checkers")
@RequiredArgsConstructor
public class CheckerConfigController {

    private final CheckerConfigService checkerConfigService;

    /**
     * 获取所有检查器配置
     */
    @GetMapping
    public Result<List<CheckerConfig>> list(
            @RequestParam(required = false) String category
    ) {
        return Result.success(checkerConfigService.listByCategory(category));
    }

    /**
     * 获取检查器详情
     */
    @GetMapping("/{id}")
    public Result<CheckerConfig> getById(@PathVariable Long id) {
        CheckerConfig config = checkerConfigService.getById(id);
        if (config == null) return Result.error("检查器不存在");
        return Result.success(config);
    }

    /**
     * 新增检查器配置
     */
    @PostMapping
    public Result<CheckerConfig> create(@RequestBody CheckerConfig config) {
        return Result.success(checkerConfigService.create(config));
    }

    /**
     * 更新检查器配置
     */
    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody CheckerConfig config) {
        config.setId(id);
        return Result.success(checkerConfigService.update(config));
    }

    /**
     * 删除检查器配置
     */
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(checkerConfigService.delete(id));
    }

    /**
     * 启用/禁用检查器
     */
    @PutMapping("/{id}/toggle")
    public Result<Boolean> toggle(@PathVariable Long id) {
        return Result.success(checkerConfigService.toggle(id));
    }
}
