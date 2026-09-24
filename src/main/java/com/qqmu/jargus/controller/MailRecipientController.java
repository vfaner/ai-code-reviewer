package com.qqmu.jargus.controller;

import com.qqmu.jargus.dto.Result;
import com.qqmu.jargus.entity.MailRecipient;
import com.qqmu.jargus.service.MailRecipientService;
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
 * 通知收件人控制器（查看/下拉选项对 VIEWER 开放，写操作 ADMIN）
 */
@RestController
@RequestMapping("/api/mail/recipients")
@RequiredArgsConstructor
public class MailRecipientController {

    private final MailRecipientService mailRecipientService;

    /**
     * 分页查询收件人
     */
    @GetMapping
    public Result<IPage<MailRecipient>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword
    ) {
        return Result.success(mailRecipientService.list(page, size, keyword));
    }

    /**
     * 全量收件人（新增扫描 / CI 触发器的下拉多选数据源；list 前缀 VIEWER 放行）
     */
    @GetMapping("/options")
    public Result<List<MailRecipient>> listOptions() {
        return Result.success(mailRecipientService.listAll());
    }

    /**
     * 收件人详情
     */
    @GetMapping("/{id}")
    public Result<MailRecipient> getById(@PathVariable Long id) {
        MailRecipient recipient = mailRecipientService.getById(id);
        if (recipient == null) return Result.error("收件人不存在");
        return Result.success(recipient);
    }

    /**
     * 新增收件人
     */
    @PostMapping
    public Result<MailRecipient> create(@RequestBody MailRecipient recipient) {
        return Result.success(mailRecipientService.create(recipient));
    }

    /**
     * 更新收件人
     */
    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody MailRecipient recipient) {
        return Result.success(mailRecipientService.update(id, recipient));
    }

    /**
     * 删除收件人
     */
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(mailRecipientService.delete(id));
    }
}
