package com.aicodereview.service;

import com.aicodereview.entity.LlmTemplate;
import com.aicodereview.mapper.LlmTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LLM 配置模板服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmTemplateService {

    private final LlmTemplateMapper llmTemplateMapper;

    /**
     * 获取所有模板
     */
    public List<LlmTemplate> listAll() {
        return llmTemplateMapper.selectList(
                new QueryWrapper<LlmTemplate>().orderByAsc("is_builtin", "id")
        );
    }

    /**
     * 获取内置模板
     */
    public List<LlmTemplate> listBuiltin() {
        return llmTemplateMapper.selectList(
                new QueryWrapper<LlmTemplate>().eq("is_builtin", true).orderByAsc("id")
        );
    }

    /**
     * 根据ID获取模板
     */
    public LlmTemplate getById(Long id) {
        return llmTemplateMapper.selectById(id);
    }

    /**
     * 新增自定义模板
     */
    public LlmTemplate create(LlmTemplate template) {
        template.setIsBuiltin(false);
        llmTemplateMapper.insert(template);
        log.info("新增 LLM 模板: id={}, name={}", template.getId(), template.getTemplateName());
        return template;
    }

    /**
     * 更新模板
     */
    public LlmTemplate update(Long id, LlmTemplate template) {
        LlmTemplate existing = llmTemplateMapper.selectById(id);
        if (existing == null) {
            throw new RuntimeException("模板不存在");
        }
        if (Boolean.TRUE.equals(existing.getIsBuiltin())) {
            throw new RuntimeException("内置模板不可修改");
        }
        template.setId(id);
        llmTemplateMapper.updateById(template);
        return llmTemplateMapper.selectById(id);
    }

    /**
     * 删除模板
     */
    public boolean delete(Long id) {
        LlmTemplate existing = llmTemplateMapper.selectById(id);
        if (existing == null) {
            return false;
        }
        if (Boolean.TRUE.equals(existing.getIsBuiltin())) {
            throw new RuntimeException("内置模板不可删除");
        }
        llmTemplateMapper.deleteById(id);
        return true;
    }
}
