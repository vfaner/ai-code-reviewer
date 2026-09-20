package com.aicodereview.env;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 项目环境信息
 */
@Data
@Builder
public class ProjectInfo {

    /** JDK 版本 */
    private String jdkVersion;

    /** JDK 厂商 */
    private String jdkVendor;

    /** Spring Boot 版本 */
    private String springBootVersion;

    /** Spring 版本 */
    private String springVersion;

    /** 项目构建工具: MAVEN / GRADLE / NONE */
    private String buildTool;

    /** 构建工具版本 */
    private String buildToolVersion;

    /** 包名（根包） */
    private String rootPackage;

    /** Java 文件总数 */
    private int javaFileCount;

    /** 总代码行数 */
    private int totalLines;

    /** 代码行数（不含空行和注释） */
    private int codeLines;

    /** 类总数 */
    private int classCount;

    /** 方法总数 */
    private int methodCount;

    /** 主要依赖列表（关键框架） */
    private List<DependencyInfo> dependencies;

    /** 检测到的框架/技术栈 */
    private List<String> detectedFrameworks;

    /** 字节码版本（编译后的 class 文件版本） */
    private String bytecodeVersion;

    /** 源码目录列表 */
    private List<String> sourceDirectories;

    /** 测试目录列表 */
    private List<String> testDirectories;

    /** 额外信息 */
    private Map<String, String> extraInfo;
}
