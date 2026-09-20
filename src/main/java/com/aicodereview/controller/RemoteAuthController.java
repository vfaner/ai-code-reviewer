package com.aicodereview.controller;

import com.aicodereview.dto.Result;
import com.aicodereview.entity.RemoteAuthConfig;
import com.aicodereview.security.RequiresRole;
import com.aicodereview.service.RemoteAuthConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 远端登录配置控制器（管理员功能）
 */
@RestController
@RequestMapping("/api/remote-auth")
@RequiredArgsConstructor
@RequiresRole("ADMIN")
public class RemoteAuthController {

    private final RemoteAuthConfigService remoteAuthConfigService;

    @GetMapping
    public Result<List<RemoteAuthConfig>> list() {
        return Result.success(remoteAuthConfigService.listAll());
    }

    @GetMapping("/{id}")
    public Result<RemoteAuthConfig> getById(@PathVariable Long id) {
        RemoteAuthConfig config = remoteAuthConfigService.getById(id);
        if (config == null) return Result.error("配置不存在");
        return Result.success(config);
    }

    @PostMapping
    public Result<RemoteAuthConfig> create(@RequestBody RemoteAuthConfig config) {
        String check = validate(config);
        if (check != null) return Result.error(check);
        if (config.getAuthType() == null) {
            config.setAuthType("OAUTH2");
        }
        return Result.success(remoteAuthConfigService.create(config));
    }

    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody RemoteAuthConfig config) {
        String check = validate(config);
        if (check != null) return Result.error(check);
        return Result.success(remoteAuthConfigService.update(id, config));
    }

    /** 只有名称和登录接口地址是必填项 */
    private String validate(RemoteAuthConfig config) {
        if (config.getConfigName() == null || config.getConfigName().isBlank()) {
            return "配置名称不能为空";
        }
        if (config.getLoginUrl() == null || config.getLoginUrl().isBlank()) {
            return "登录接口 URL 不能为空";
        }
        return null;
    }

    @PostMapping("/test")
    public Result<java.util.Map<String, Object>> test(@RequestBody RemoteAuthConfig config) {
        return Result.success(remoteAuthConfigService.testConnection(config));
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(remoteAuthConfigService.delete(id));
    }

    @PutMapping("/{id}/toggle")
    public Result<Boolean> toggle(@PathVariable Long id) {
        return Result.success(remoteAuthConfigService.toggle(id));
    }
}
