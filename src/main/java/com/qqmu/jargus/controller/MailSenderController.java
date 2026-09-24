package com.qqmu.jargus.controller;

import com.qqmu.jargus.dto.Result;
import com.qqmu.jargus.entity.MailSender;
import com.qqmu.jargus.service.MailSenderConfigService;
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
 * 发件配置控制器（管理员）
 */
@RestController
@RequestMapping("/api/mail/senders")
@RequiredArgsConstructor
public class MailSenderController {

    private final MailSenderConfigService mailSenderConfigService;

    /**
     * 分页查询发件配置（密码掩码）
     */
    @GetMapping
    public Result<IPage<MailSender>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword
    ) {
        return Result.success(mailSenderConfigService.list(page, size, keyword));
    }

    /**
     * 发件配置详情（密码掩码）
     */
    @GetMapping("/{id}")
    public Result<MailSender> getById(@PathVariable Long id) {
        MailSender sender = mailSenderConfigService.getById(id);
        if (sender == null) return Result.error("发件配置不存在");
        return Result.success(sender);
    }

    /**
     * 新增发件配置
     */
    @PostMapping
    public Result<MailSender> create(@RequestBody MailSender sender) {
        return Result.success(mailSenderConfigService.create(sender));
    }

    /**
     * 更新发件配置（密码留空或含 **** = 不修改）
     */
    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody MailSender sender) {
        return Result.success(mailSenderConfigService.update(id, sender));
    }

    /**
     * 删除发件配置
     */
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(mailSenderConfigService.delete(id));
    }

    /**
     * 启用/停用（启用时自动停用其他）
     */
    @PutMapping("/{id}/toggle")
    public Result<Boolean> toggle(@PathVariable Long id) {
        return Result.success(mailSenderConfigService.toggle(id));
    }

    /**
     * 发送测试邮件
     */
    @PostMapping("/{id}/test")
    public Result<Boolean> test(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object to = body.get("to");
        if (to == null || to.toString().isBlank()) {
            return Result.error("请输入测试收件邮箱");
        }
        try {
            mailSenderConfigService.sendTest(id, to.toString().trim());
            return Result.success(true);
        } catch (Exception e) {
            return Result.error("测试邮件发送失败: " + e.getMessage());
        }
    }
}
