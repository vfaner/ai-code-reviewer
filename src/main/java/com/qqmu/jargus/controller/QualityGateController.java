package com.qqmu.jargus.controller;

import com.qqmu.jargus.dto.Result;
import com.qqmu.jargus.security.RequiresRole;
import com.qqmu.jargus.service.QualityGateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 质量门禁配置接口
 *
 * GET 读取生效配置（登录即可，页面渲染需要）；
 * PUT/DELETE 仅管理员：保存自定义分值/阈值、恢复出厂默认。
 * 配置存 gate_setting 单行表，保存后即时生效（评分为渲染时实时计算，历史任务无需重扫）。
 */
@RestController
@RequestMapping("/api/quality-gate")
@RequiredArgsConstructor
public class QualityGateController {

    private final QualityGateService qualityGateService;

    @GetMapping("/settings")
    @RequiresRole("VIEWER")
    public Result<Map<String, Object>> getSettings() {
        return Result.success(qualityGateService.getGateSettings());
    }

    @PutMapping("/settings")
    @RequiresRole("ADMIN")
    public Result<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> body) {
        try {
            qualityGateService.saveSettings(body);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
        return Result.success(qualityGateService.getGateSettings());
    }

    @DeleteMapping("/settings")
    @RequiresRole("ADMIN")
    public Result<Map<String, Object>> resetSettings() {
        qualityGateService.resetSettings();
        return Result.success(qualityGateService.getGateSettings());
    }
}
