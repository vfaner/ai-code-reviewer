package com.aicodereview.controller;

import com.aicodereview.dto.Result;
import com.aicodereview.entity.LlmTemplate;
import com.aicodereview.service.LlmTemplateService;
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
 * LLM 配置模板控制器
 */
@RestController
@RequestMapping("/api/llm-templates")
@RequiredArgsConstructor
public class LlmTemplateController {

    private final LlmTemplateService llmTemplateService;

    @GetMapping
    public Result<List<LlmTemplate>> list(
            @RequestParam(required = false, defaultValue = "false") boolean builtinOnly
    ) {
        if (builtinOnly) {
            return Result.success(llmTemplateService.listBuiltin());
        }
        return Result.success(llmTemplateService.listAll());
    }

    @GetMapping("/{id}")
    public Result<LlmTemplate> getById(@PathVariable Long id) {
        return Result.success(llmTemplateService.getById(id));
    }

    @PostMapping
    public Result<LlmTemplate> create(@RequestBody LlmTemplate template) {
        return Result.success(llmTemplateService.create(template));
    }

    @PutMapping("/{id}")
    public Result<LlmTemplate> update(@PathVariable Long id, @RequestBody LlmTemplate template) {
        return Result.success(llmTemplateService.update(id, template));
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(llmTemplateService.delete(id));
    }
}
