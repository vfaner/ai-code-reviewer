package com.aicodereview.controller;

import com.aicodereview.dto.Result;
import com.aicodereview.entity.CiToken;
import com.aicodereview.service.CiTokenService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CI 令牌控制器
 */
@RestController
@RequestMapping("/api/ci/tokens")
@RequiredArgsConstructor
public class CiTokenController {

    private final CiTokenService ciTokenService;

    @GetMapping
    public Result<IPage<CiToken>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword
    ) {
        return Result.success(ciTokenService.list(page, size, keyword));
    }

    @GetMapping("/{id}")
    public Result<CiToken> getById(@PathVariable Long id) {
        CiToken token = ciTokenService.getById(id);
        if (token == null) return Result.error("令牌不存在");
        return Result.success(token);
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody CiToken token) {
        if (token.getTokenName() == null || token.getTokenName().isEmpty()) {
            return Result.error("令牌名称不能为空");
        }
        String plainToken = ciTokenService.create(token);
        return Result.success(Map.of(
                "id", token.getId(),
                "tokenName", token.getTokenName(),
                "token", plainToken
        ));
    }

    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody CiToken token) {
        return Result.success(ciTokenService.update(id, token));
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(ciTokenService.delete(id));
    }

    @PutMapping("/{id}/toggle")
    public Result<Boolean> toggle(@PathVariable Long id) {
        return Result.success(ciTokenService.toggle(id));
    }
}
