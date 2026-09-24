package com.qqmu.jargus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 扫描任务实体
 */
@Data
@TableName("scan_task")
public class ScanTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务名称 */
    private String taskName;

    /** 项目名称 */
    private String projectName;

    /** 来源类型: ZIP/PASTE/GIT/CI */
    private String sourceType;

    /** 状态: PENDING/RUNNING/SUCCESS/FAILED/CANCELLED */
    private String status;

    /** 总文件数 */
    private Integer totalFiles;

    /** 总代码行数 */
    private Integer totalLines;

    /** 阻断（BLOCKER）数量 */
    private Integer blockerCount;

    /** 严重（CRITICAL）数量 */
    private Integer criticalCount;

    /** 主要（MAJOR）数量 */
    private Integer majorCount;

    /** 次要（MINOR）数量 */
    private Integer minorCount;

    /** 提示（INFO）数量 */
    private Integer infoCount;

    /** 问题总数 */
    private Integer totalIssues;

    /** JDK 版本 */
    private String jdkVersion;

    /** Spring Boot 版本 */
    private String springBootVersion;

    /** 跳过单元测试 */
    private Boolean skipUnitTest;

    /** 包含测试代码 */
    private Boolean includeTestCode;

    /** 启用 AI 评审 */
    private Boolean enableAiReview;

    /** 扫描完成后是否发送通知邮件 */
    private Boolean notifyEnabled;

    /** 通知收件人 id 逗号串（创建时快照） */
    private String notifyRecipientIds;

    /** 通知邮件状态: SENT/FAILED，null=未发信 */
    private String mailStatus;

    /** AI 发现问题数 */
    private Integer aiIssueCount;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 耗时(秒) */
    private Long durationSeconds;

    /** 错误信息 */
    private String errorMessage;

    /** 源码快照路径 */
    private String snapshotPath;

    /** 报告路径 */
    private String reportPath;

    /** 创建人 */
    private String createdBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
