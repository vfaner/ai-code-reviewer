package com.aicodereview.controller;

import com.aicodereview.dto.Result;
import com.aicodereview.entity.CiScanRecord;
import com.aicodereview.entity.CiTriggerConfig;
import com.aicodereview.service.CiTriggerService;
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

import java.util.Map;

/**
 * CI 触发配置控制器
 */
@RestController
@RequestMapping("/api/ci/triggers")
@RequiredArgsConstructor
public class CiTriggerController {

    private final CiTriggerService ciTriggerService;

    @GetMapping
    public Result<IPage<CiTriggerConfig>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword
    ) {
        IPage<CiTriggerConfig> result = ciTriggerService.list(page, size, keyword);
        // 列表不回显密钥/凭据
        result.getRecords().forEach(t -> {
            t.setSecretToken(null);
            t.setRepoToken(null);
        });
        return Result.success(result);
    }

    @GetMapping("/{id}")
    public Result<CiTriggerConfig> getById(@PathVariable Long id) {
        CiTriggerConfig config = ciTriggerService.getById(id);
        if (config == null) return Result.error("配置不存在");
        // 密钥仅管理员可取（GET 默认对 VIEWER 开放）
        if (!com.aicodereview.security.UserContext.isAdmin()) {
            config.setSecretToken(null);
        }
        return Result.success(config);
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody CiTriggerConfig config) {
        if (config.getConfigName() == null || config.getConfigName().isEmpty()) {
            return Result.error("配置名称不能为空");
        }
        if (config.getPlatform() == null || config.getPlatform().isEmpty()) {
            config.setPlatform("GENERIC");
        }
        CiTriggerConfig saved = ciTriggerService.create(config);
        // 明文密钥仅创建成功时返回这一次，库存为密文
        return Result.success(Map.of(
                "id", saved.getId(),
                "configName", saved.getConfigName(),
                "platform", saved.getPlatform(),
                "webhookUrl", saved.getWebhookUrl(),
                "secretToken", saved.getSecretToken()
        ));
    }

    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody CiTriggerConfig config) {
        return Result.success(ciTriggerService.update(id, config));
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(ciTriggerService.delete(id));
    }

    @PutMapping("/{id}/toggle")
    public Result<Boolean> toggle(@PathVariable Long id) {
        return Result.success(ciTriggerService.toggle(id));
    }

    @PostMapping("/{id}/reset-secret")
    public Result<String> resetSecret(@PathVariable Long id) {
        String newSecret = ciTriggerService.resetSecret(id);
        if (newSecret == null) return Result.error("配置不存在");
        return Result.success(newSecret);
    }

    // ========== 扫描记录 ==========

    @GetMapping("/records")
    public Result<IPage<CiScanRecord>> listRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long triggerConfigId
    ) {
        return Result.success(ciTriggerService.listRecords(page, size, triggerConfigId));
    }
}
