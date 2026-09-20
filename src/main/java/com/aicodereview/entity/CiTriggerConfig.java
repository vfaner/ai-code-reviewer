package com.aicodereview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CI 触发配置
 */
@Data
@TableName("ci_trigger_config")
public class CiTriggerConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置名称 */
    private String configName;

    /** 平台: GITHUB / GITLAB / GITEE / GENERIC */
    private String platform;

    /** 代码平台地址（官方云默认地址或企业自建地址，如 https://gitlab.company.com） */
    private String platformUrl;

    /** Webhook URL（只读，由系统生成） */
    private String webhookUrl;

    /** 密钥令牌（用于签名验证，AES 加密存储） */
    private String secretToken;

    /** 私有仓库克隆用户名（可为空） */
    private String repoUsername;

    /** 私有仓库访问令牌/密码（AES 加密存储，编辑留空表示不修改） */
    private String repoToken;

    /** 仓库范围（owner/repo 或 group/project，空=不限制；用于校验 Webhook 事件所属仓库） */
    private String repoScope;

    /** 分支过滤（glob 模式，逗号分隔，空=所有分支） */
    private String branchFilter;

    /** 是否跳过单元测试 */
    private Boolean skipUnitTest;

    /** 是否包含测试代码 */
    private Boolean includeTestCode;

    /** 是否启用 AI 评审 */
    private Boolean enableAiReview;

    /** 是否自动评论 MR/PR */
    private Boolean autoComment;

    /** 是否启用 */
    private Boolean isEnabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
