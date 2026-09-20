package com.aicodereview.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 质量门禁结果（五级严重度模型）
 */
@Data
@Builder
public class QualityGateResult {

    /** 质量评分 0-100 */
    private int score;

    /** 质量等级: EXCELLENT / GOOD / FAIR / POOR */
    private String level;

    /** 是否通过质量门禁 */
    private boolean passed;

    /** 阻断数量 */
    private int blockerCount;

    /** 严重数量 */
    private int criticalCount;

    /** 主要数量 */
    private int majorCount;

    /** 次要数量 */
    private int minorCount;

    /** 提示数量 */
    private int infoCount;

    /** 各级扣分明细 */
    private int blockerDeduct;
    private int criticalDeduct;
    private int majorDeduct;
    private int minorDeduct;

    /** 总问题数（未忽略） */
    private int totalIssues;

    /** 已忽略问题数 */
    private int ignoredCount;

    /** 预估技术债（分钟）：所有未忽略问题的标准修复时间之和 */
    @Builder.Default
    private long debtMinutes = 0;

    /** 预估技术债（可读文本，如 "3小时25分"） */
    @Builder.Default
    private String debtText = "";

    /** 门禁详情说明 */
    private String detail;
}
