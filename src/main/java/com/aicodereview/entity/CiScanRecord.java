package com.aicodereview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CI 扫描记录
 */
@Data
@TableName("ci_scan_record")
public class CiScanRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 触发配置 ID */
    private Long triggerConfigId;

    /** 扫描任务 ID */
    private Long taskId;

    /** 平台: GITHUB / GITLAB / GITEE / GENERIC */
    private String platform;

    /** 项目地址 */
    private String projectUrl;

    /** commit hash */
    private String commitId;

    /** 分支名 */
    private String branch;

    /** MR/PR ID */
    private String mrPrId;

    /** MR/PR 标题 */
    private String mrPrTitle;

    /** 提交作者 */
    private String author;

    /** 状态: PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    /** 结果详情页 URL */
    private String resultUrl;

    private LocalDateTime createdAt;
}
