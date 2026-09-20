package com.aicodereview.controller;

import com.aicodereview.dto.Result;
import com.aicodereview.entity.IgnoreRule;
import com.aicodereview.service.IgnoreRuleService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 忽略规则控制器
 */
@RestController
@RequestMapping("/api/ignore-rules")
@RequiredArgsConstructor
public class IgnoreRuleController {

    private final IgnoreRuleService ignoreRuleService;

    /**
     * 分页查询忽略规则
     */
    @GetMapping
    public Result<IPage<IgnoreRule>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword
    ) {
        return Result.success(ignoreRuleService.list(page, size, keyword));
    }

    /**
     * 获取规则详情
     */
    @GetMapping("/{id}")
    public Result<IgnoreRule> getById(@PathVariable Long id) {
        IgnoreRule rule = ignoreRuleService.getById(id);
        if (rule == null) return Result.error("规则不存在");
        return Result.success(rule);
    }

    /**
     * 新增忽略规则
     */
    @PostMapping
    public Result<IgnoreRule> create(@RequestBody IgnoreRule rule) {
        if (rule.getRuleType() == null || rule.getRuleType().isEmpty()) {
            return Result.error("规则类型不能为空");
        }
        return Result.success(ignoreRuleService.create(rule));
    }

    /**
     * 更新忽略规则
     */
    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody IgnoreRule rule) {
        rule.setId(id);
        return Result.success(ignoreRuleService.update(rule));
    }

    /**
     * 删除忽略规则
     */
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(ignoreRuleService.delete(id));
    }

    /**
     * 启用/禁用规则
     */
    @PutMapping("/{id}/toggle")
    public Result<Boolean> toggle(@PathVariable Long id) {
        return Result.success(ignoreRuleService.toggle(id));
    }
}
