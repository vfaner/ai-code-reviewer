package com.qqmu.jargus.checker;

import java.util.List;

/**
 * 扫描级检查钩子
 *
 * 所有文件逐一检查完毕后，扫描引擎对启用的检查器中实现本接口者统一调用一次。
 * 适用场景：
 * - 跨文件汇总类检查（如重复代码检测：doCheck 阶段向 globalData 收集指纹，postScanCheck 阶段汇总报告）
 * - 与单个 Java 文件无关的检查（如依赖漏洞扫描：解析 pom.xml / build.gradle）
 *
 * 注意：即使项目中没有任何 Java 文件，postScanCheck 也会被调用（templateContext 的
 * compilationUnit / currentFilePath 为空，sourceRoot / globalData 可用）。
 */
public interface PostScanChecker {

    /**
     * 扫描收尾检查
     *
     * @param templateContext 模板上下文（携带 taskId / sourceRoot / globalData 等扫描级信息）
     * @return 发现的问题列表
     */
    List<CheckIssue> postScanCheck(CheckContext templateContext);
}
