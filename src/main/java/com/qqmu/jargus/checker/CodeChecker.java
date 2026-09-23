package com.qqmu.jargus.checker;

import java.util.List;

/**
 * 代码检查器接口
 * 所有本地和 AI 检查器都实现此接口
 */
public interface CodeChecker {

    /**
     * 获取检查器类型
     */
    CheckerType getCheckerType();

    /**
     * 获取检查器名称
     */
    default String getName() {
        return getCheckerType().getName();
    }

    /**
     * 是否启用
     */
    boolean isEnabled();

    /**
     * 是否是本地检查器（非 AI）
     */
    boolean isLocal();

    /**
     * 对单个文件执行检查
     *
     * @param context 检查上下文
     * @return 发现的问题列表
     */
    List<CheckIssue> check(CheckContext context);

    /**
     * 检查前置条件（是否需要跳过）
     *
     * @param context 检查上下文
     * @return true 表示可以执行检查
     */
    default boolean accept(CheckContext context) {
        if (!isEnabled()) {
            return false;
        }
        // 测试文件根据配置决定是否检查
        if (context.isTestFile() && !context.isIncludeTestCode()) {
            return false;
        }
        return true;
    }

    /**
     * 检查执行的优先级（数值小的先执行）
     */
    default int getPriority() {
        return 100;
    }
}
