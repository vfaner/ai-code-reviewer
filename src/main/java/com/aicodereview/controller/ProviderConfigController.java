package com.aicodereview.controller;

import com.aicodereview.dto.Result;
import com.aicodereview.entity.AiProviderConfig;
import com.aicodereview.llm.AiChatClient;
import com.aicodereview.llm.AiChatResponse;
import com.aicodereview.llm.AiClientFactory;
import com.aicodereview.service.ProviderConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 厂商配置控制器
 */
@RestController
@RequestMapping("/api/providers")
@RequiredArgsConstructor
public class ProviderConfigController {

    private final ProviderConfigService providerConfigService;
    private final AiClientFactory aiClientFactory;

    @GetMapping
    public Result<List<AiProviderConfig>> list() {
        return Result.success(providerConfigService.listAll());
    }

    @GetMapping("/{id}")
    public Result<AiProviderConfig> getById(@PathVariable Long id) {
        return Result.success(providerConfigService.getById(id));
    }

    @GetMapping("/active")
    public Result<AiProviderConfig> getActive() {
        return Result.success(providerConfigService.getActive());
    }

    @PostMapping
    public Result<AiProviderConfig> create(@RequestBody AiProviderConfig config) {
        return Result.success(providerConfigService.create(config));
    }

    @PutMapping("/{id}")
    public Result<AiProviderConfig> update(@PathVariable Long id, @RequestBody AiProviderConfig config) {
        return Result.success(providerConfigService.update(id, config));
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(providerConfigService.delete(id));
    }

    /** 用已保存的配置（解密后）测试 */
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> testConnection(@PathVariable Long id) {
        AiProviderConfig config = providerConfigService.getConfigForTest(id);
        if (config == null) {
            return Result.success(Map.of("success", false, "message", "配置不存在"));
        }
        return runTest(config);
    }

    /** 用弹窗里尚未保存的表单配置测试；编辑态 Key 留空时沿用库中已保存的 */
    @PostMapping("/test")
    public Result<Map<String, Object>> testConnectionWithConfig(@RequestBody AiProviderConfig config) {
        return runTest(providerConfigService.prepareForFormTest(config));
    }

    private Result<Map<String, Object>> runTest(AiProviderConfig config) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        AiChatClient client = aiClientFactory.createClient(config);
        if (client == null) {
            result.put("success", false);
            result.put("message", "创建客户端失败");
            return Result.success(result);
        }
        AiChatResponse response = client.testConnectionDetailed();
        long duration = System.currentTimeMillis() - startTime;
        client.close();
        boolean success = response.isSuccess() && response.getContent() != null
                && !response.getContent().isEmpty();
        result.put("success", success);
        result.put("duration", duration);
        if (success) {
            result.put("message", "连接成功，模型回复：" + response.getContent().strip());
        } else {
            result.put("message", response.getErrorMessage() != null
                    ? response.getErrorMessage() : "连接失败");
        }
        return Result.success(result);
    }

    @PostMapping("/{id}/activate")
    public Result<Boolean> activate(@PathVariable Long id) {
        return Result.success(providerConfigService.activate(id));
    }
}
