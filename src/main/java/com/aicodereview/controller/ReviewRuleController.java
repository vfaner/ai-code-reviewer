package com.aicodereview.controller;

import com.aicodereview.dto.Result;
import com.aicodereview.entity.ReviewRule;
import com.aicodereview.service.ReviewRuleService;
import com.baomidou.mybatisplus.core.metadata.IPage;
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
 * 评审规则控制器
 */
@RestController
@RequestMapping("/api/review-rules")
@RequiredArgsConstructor
public class ReviewRuleController {

    private final ReviewRuleService reviewRuleService;

    /**
     * 分页查询规则列表
     */
    @GetMapping
    public Result<IPage<ReviewRule>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword
    ) {
        return Result.success(reviewRuleService.list(page, size, category, keyword));
    }

    /**
     * 获取所有规则（不分页）
     */
    @GetMapping("/all")
    public Result<List<ReviewRule>> listAll() {
        return Result.success(reviewRuleService.listAll());
    }

    /**
     * 获取规则详情
     */
    @GetMapping("/{id}")
    public Result<ReviewRule> getById(@PathVariable Long id) {
        ReviewRule rule = reviewRuleService.getById(id);
        if (rule == null) return Result.error("规则不存在");
        return Result.success(rule);
    }

    /**
     * 根据编码获取规则
     */
    @GetMapping("/code/{ruleCode}")
    public Result<ReviewRule> getByCode(@PathVariable String ruleCode) {
        ReviewRule rule = reviewRuleService.getByCode(ruleCode);
        if (rule == null) return Result.error("规则不存在");
        return Result.success(rule);
    }

    /**
     * 新增规则
     */
    @PostMapping
    public Result<ReviewRule> create(@RequestBody ReviewRule rule) {
        if (rule.getRuleCode() == null || rule.getRuleCode().isEmpty()) {
            return Result.error("规则编码不能为空");
        }
        if (rule.getRuleName() == null || rule.getRuleName().isEmpty()) {
            return Result.error("规则名称不能为空");
        }
        return Result.success(reviewRuleService.create(rule));
    }

    /**
     * 更新规则
     */
    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody ReviewRule rule) {
        rule.setId(id);
        return Result.success(reviewRuleService.update(rule));
    }

    /**
     * 删除规则
     */
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(reviewRuleService.delete(id));
    }

    /**
     * 启用/禁用规则
     */
    @PutMapping("/{id}/toggle")
    public Result<Boolean> toggle(@PathVariable Long id) {
        return Result.success(reviewRuleService.toggle(id));
    }
}
