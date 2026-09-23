package com.qqmu.jargus.service;

import com.qqmu.jargus.env.DependencyInfo;
import com.qqmu.jargus.env.ProjectInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 项目环境检测服务
 *
 * 检测项目的：
 * - JDK 版本
 * - Spring Boot 版本
 * - 构建工具（Maven/Gradle）
 * - 主要依赖
 * - 代码统计（文件数、行数、类数、方法数）
 * - 检测到的框架/技术栈
 */
@Slf4j
@Service
public class ProjectEnvService {

    private final JavaParser javaParser;

    public ProjectEnvService() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 分析项目环境
     *
     * @param sourceRoot 源码根目录
     * @return 项目环境信息
     */
    public ProjectInfo analyzeProject(Path sourceRoot) {
        ProjectInfo.ProjectInfoBuilder builder = ProjectInfo.builder();

        // 检测构建工具
        String buildTool = detectBuildTool(sourceRoot);
        builder.buildTool(buildTool);

        // 解析依赖
        List<DependencyInfo> dependencies = parseDependencies(sourceRoot, buildTool);
        builder.dependencies(dependencies);

        // 检测框架
        List<String> frameworks = detectFrameworks(dependencies);
        builder.detectedFrameworks(frameworks);

        // 检测 Spring Boot 版本
        String springBootVersion = findSpringBootVersion(dependencies);
        builder.springBootVersion(springBootVersion);
        builder.springVersion(findSpringVersion(dependencies));

        // 检测 JDK 版本
        String jdkVersion = detectJdkVersion(sourceRoot, buildTool);
        builder.jdkVersion(jdkVersion);
        builder.jdkVendor(System.getProperty("java.vendor"));

        // 统计代码
        List<Path> javaFiles = findJavaFiles(sourceRoot);
        builder.javaFileCount(javaFiles.size());

        // 统计行数和类/方法数
        int[] stats = countCodeStats(javaFiles);
        builder.totalLines(stats[0]);
        builder.codeLines(stats[1]);
        builder.classCount(stats[2]);
        builder.methodCount(stats[3]);

        // 检测包名
        builder.rootPackage(detectRootPackage(javaFiles));

        // 源码目录
        builder.sourceDirectories(findSourceDirs(sourceRoot));
        builder.testDirectories(findTestDirs(sourceRoot));

        // 字节码版本（如果有 class 文件）
        builder.bytecodeVersion(detectBytecodeVersion(sourceRoot));

        return builder.build();
    }

    /**
     * 检测构建工具
     */
    private String detectBuildTool(Path sourceRoot) {
        if (Files.exists(sourceRoot.resolve("pom.xml"))) {
            return "MAVEN";
        }
        if (Files.exists(sourceRoot.resolve("build.gradle"))
                || Files.exists(sourceRoot.resolve("build.gradle.kts"))) {
            return "GRADLE";
        }
        // 向上查找
        Path parent = sourceRoot.getParent();
        if (parent != null) {
            if (Files.exists(parent.resolve("pom.xml"))) {
                return "MAVEN";
            }
            if (Files.exists(parent.resolve("build.gradle"))) {
                return "GRADLE";
            }
        }
        // ZIP 解压常见形态：源码根下还有一层单模块目录（如 src/warehouse-system/pom.xml）
        if (findPomXml(sourceRoot) != null) {
            return "MAVEN";
        }
        if (findGradleBuild(sourceRoot) != null) {
            return "GRADLE";
        }
        return "NONE";
    }

    /**
     * 解析依赖
     */
    private List<DependencyInfo> parseDependencies(Path sourceRoot, String buildTool) {
        List<DependencyInfo> deps = new ArrayList<>();

        try {
            if ("MAVEN".equals(buildTool)) {
                Path pomPath = findPomXml(sourceRoot);
                if (pomPath != null && Files.exists(pomPath)) {
                    deps = parseMavenDependencies(pomPath);
                }
            } else if ("GRADLE".equals(buildTool)) {
                Path gradlePath = findGradleBuild(sourceRoot);
                if (gradlePath != null && Files.exists(gradlePath)) {
                    deps = parseGradleDependencies(gradlePath);
                }
            }
        } catch (Exception e) {
            log.warn("解析依赖失败: {}", e.getMessage());
        }

        return deps;
    }

    /**
     * 解析项目依赖（供依赖漏洞扫描等外部调用）
     */
    public List<DependencyInfo> parseDependenciesForScan(Path sourceRoot) {
        return parseDependencies(sourceRoot, detectBuildTool(sourceRoot));
    }

    /**
     * 解析 Maven pom.xml 中的依赖
     */
    private List<DependencyInfo> parseMavenDependencies(Path pomPath) throws IOException {
        List<DependencyInfo> deps = new ArrayList<>();
        String content = Files.readString(pomPath, StandardCharsets.UTF_8);

        // 简单的正则解析 dependency 块
        Pattern depPattern = Pattern.compile(
                "<dependency>\\s*<groupId>(.*?)</groupId>\\s*<artifactId>(.*?)</artifactId>" +
                        "(?:\\s*<version>(.*?)</version>)?" +
                        "(?:\\s*<scope>(.*?)</scope>)?",
                Pattern.DOTALL
        );

        Matcher matcher = depPattern.matcher(content);
        while (matcher.find()) {
            deps.add(DependencyInfo.builder()
                    .groupId(matcher.group(1).trim())
                    .artifactId(matcher.group(2).trim())
                    .version(matcher.group(3) != null ? matcher.group(3).trim() : null)
                    .scope(matcher.group(4) != null ? matcher.group(4).trim() : "compile")
                    .type("jar")
                    .build());
        }

        // 提取 parent 中的 Spring Boot 版本
        Pattern parentPattern = Pattern.compile(
                "<parent>.*?<groupId>(.*?)</groupId>.*?<artifactId>(.*?)</artifactId>.*?<version>(.*?)</version>.*?</parent>",
                Pattern.DOTALL
        );
        matcher = parentPattern.matcher(content);
        if (matcher.find()) {
            deps.add(0, DependencyInfo.builder()
                    .groupId(matcher.group(1).trim())
                    .artifactId(matcher.group(2).trim())
                    .version(matcher.group(3).trim())
                    .scope("parent")
                    .build());
        }

        return deps;
    }

    /**
     * 解析 Gradle 依赖（简单解析）
     */
    private List<DependencyInfo> parseGradleDependencies(Path buildPath) throws IOException {
        List<DependencyInfo> deps = new ArrayList<>();
        List<String> lines = Files.readAllLines(buildPath, StandardCharsets.UTF_8);

        Pattern depPattern = Pattern.compile(
                "(?:implementation|api|compile|compileOnly|runtimeOnly|testImplementation)\\s+[\"\']([^:]+):([^:]+):?([^\"\']*)[\"\']"
        );

        for (String line : lines) {
            Matcher matcher = depPattern.matcher(line.trim());
            if (matcher.find()) {
                String scope = "compile";
                if (line.contains("test")) scope = "test";
                else if (line.contains("runtime")) scope = "runtime";
                else if (line.contains("compileOnly")) scope = "provided";

                deps.add(DependencyInfo.builder()
                        .groupId(matcher.group(1))
                        .artifactId(matcher.group(2))
                        .version(matcher.group(3) != null && !matcher.group(3).isEmpty() ? matcher.group(3) : null)
                        .scope(scope)
                        .build());
            }
        }

        return deps;
    }

    /**
     * 检测使用的框架
     */
    private List<String> detectFrameworks(List<DependencyInfo> dependencies) {
        List<String> frameworks = new ArrayList<>();

        Set<String> depNames = dependencies.stream()
                .map(DependencyInfo::getArtifactId)
                .collect(Collectors.toSet());

        // Spring Boot
        if (depNames.contains("spring-boot-starter")
                || depNames.contains("spring-boot")
                || depNames.contains("spring-boot-starter-parent")) {
            frameworks.add("Spring Boot");
        }
        // Spring MVC
        if (depNames.contains("spring-web") || depNames.contains("spring-boot-starter-web")) {
            frameworks.add("Spring Web");
        }
        // MyBatis
        if (depNames.contains("mybatis") || depNames.contains("mybatis-spring-boot-starter")) {
            frameworks.add("MyBatis");
        }
        // JPA/Hibernate
        if (depNames.contains("spring-data-jpa") || depNames.contains("hibernate-core")
                || depNames.contains("spring-boot-starter-data-jpa")) {
            frameworks.add("JPA / Hibernate");
        }
        // Redis
        if (depNames.contains("spring-boot-starter-data-redis") || depNames.contains("jedis")) {
            frameworks.add("Redis");
        }
        // Kafka
        if (depNames.contains("spring-kafka") || depNames.contains("kafka-clients")) {
            frameworks.add("Kafka");
        }
        // 单元测试
        if (depNames.contains("junit") || depNames.contains("junit-jupiter")
                || depNames.contains("spring-boot-starter-test")) {
            frameworks.add("JUnit");
        }
        // Mockito
        if (depNames.contains("mockito-core") || depNames.contains("mockito")) {
            frameworks.add("Mockito");
        }

        return frameworks;
    }

    /**
     * 查找 Spring Boot 版本
     */
    private String findSpringBootVersion(List<DependencyInfo> dependencies) {
        for (DependencyInfo dep : dependencies) {
            if ("org.springframework.boot".equals(dep.getGroupId())
                    && dep.getVersion() != null) {
                return dep.getVersion();
            }
        }
        return null;
    }

    /**
     * 查找 Spring 版本
     */
    private String findSpringVersion(List<DependencyInfo> dependencies) {
        for (DependencyInfo dep : dependencies) {
            if (dep.getGroupId() != null
                    && dep.getGroupId().startsWith("org.springframework")
                    && !dep.getGroupId().contains("boot")
                    && dep.getVersion() != null) {
                return dep.getVersion();
            }
        }
        return null;
    }

    /**
     * 检测 JDK 版本
     */
    private String detectJdkVersion(Path sourceRoot, String buildTool) {
        // 先尝试从构建文件中获取
        try {
            if ("MAVEN".equals(buildTool)) {
                Path pom = findPomXml(sourceRoot);
                if (pom != null) {
                    String content = Files.readString(pom, StandardCharsets.UTF_8);
                    // 找 maven.compiler.source / java.version 等
                    Pattern[] patterns = {
                            Pattern.compile("<java\\.version>(.*?)</java\\.version>"),
                            Pattern.compile("<maven\\.compiler\\.source>(.*?)</maven\\.compiler\\.source>"),
                            Pattern.compile("<source>(.*?)</source>")
                    };
                    for (Pattern p : patterns) {
                        Matcher m = p.matcher(content);
                        if (m.find()) {
                            String ver = m.group(1).trim();
                            if (ver.matches("\\d+")) {
                                return ver;
                            }
                            return ver;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("检测 JDK 版本失败: {}", e.getMessage());
        }

        // 默认使用当前 JDK 版本
        String version = System.getProperty("java.specification.version");
        return version != null ? version : "17";
    }

    /**
     * 统计代码信息
     */
    private int[] countCodeStats(List<Path> javaFiles) {
        int totalLines = 0;
        int codeLines = 0;
        int classCount = 0;
        int methodCount = 0;

        for (Path file : javaFiles) {
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                totalLines += lines.size();

                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("*")) {
                        codeLines++;
                    }
                }

                // 用 JavaParser 统计类和方法
                try {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    // JavaParser 单实例并发解析会竞态（与 CodeParseService 同款问题），串行化
                    ParseResult<CompilationUnit> result;
                    synchronized (javaParser) {
                        result = javaParser.parse(source);
                    }
                    if (result.isSuccessful()) {
                        CompilationUnit cu = result.getResult().orElse(null);
                        if (cu != null) {
                            classCount += cu.findAll(ClassOrInterfaceDeclaration.class).size();
                            methodCount += cu.findAll(MethodDeclaration.class).size();
                        }
                    }
                } catch (Exception | AssertionError ignored) {
                    // 解析失败不计入；二进制/截断源码会让 JavaParser 词法器抛
                    // AssertionError（token kind = MAX_VALUE），它不是普通解析异常
                }

            } catch (IOException e) {
                log.debug("统计文件失败: {}", file);
            }
        }

        return new int[]{totalLines, codeLines, classCount, methodCount};
    }

    /**
     * 检测根包名：取所有 Java 文件 package 声明的最长公共前缀
     * （不能只取遍历到的第一个文件——它可能位于 vo/util 等子包）
     */
    private String detectRootPackage(List<Path> javaFiles) {
        String prefix = null;
        for (Path file : javaFiles) {
            String pkg = readPackageDecl(file);
            if (pkg == null) continue;
            if (prefix == null) {
                prefix = pkg;
            } else if (!pkg.equals(prefix)) {
                prefix = commonPackagePrefix(prefix, pkg);
            }
            if (prefix != null && prefix.isEmpty()) break;
        }
        return (prefix == null || prefix.isEmpty()) ? null : prefix;
    }

    /** 读取单个 Java 文件首条 package 声明，无包名/读取失败返回 null */
    private String readPackageDecl(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("package ")) {
                    int semi = t.indexOf(';');
                    return t.substring(8, semi > 0 ? semi : t.length()).trim();
                }
                if (t.startsWith("public class") || t.startsWith("class ")
                        || t.startsWith("public interface") || t.startsWith("public enum")) {
                    break;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** 两个包名按 '.' 分段的最长公共前缀 */
    private String commonPackagePrefix(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.min(pa.length, pb.length);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (!pa[i].equals(pb[i])) break;
            if (out.length() > 0) out.append('.');
            out.append(pa[i]);
        }
        return out.toString();
    }

    /**
     * 查找 pom.xml（根目录 → 上一级 → 向下两层遍历）
     */
    public Path findPomXml(Path sourceRoot) {
        Path pom = sourceRoot.resolve("pom.xml");
        if (Files.exists(pom)) return pom;

        // 向上找一级
        Path parent = sourceRoot.getParent();
        if (parent != null && Files.exists(parent.resolve("pom.xml"))) {
            return parent.resolve("pom.xml");
        }

        // 在源码目录中找
        try (Stream<Path> stream = Files.walk(sourceRoot, 2)) {
            return stream.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public Path findGradleBuild(Path sourceRoot) {
        Path build = sourceRoot.resolve("build.gradle");
        if (Files.exists(build)) return build;
        build = sourceRoot.resolve("build.gradle.kts");
        if (Files.exists(build)) return build;

        Path parent = sourceRoot.getParent();
        if (parent != null) {
            if (Files.exists(parent.resolve("build.gradle"))) return parent.resolve("build.gradle");
            if (Files.exists(parent.resolve("build.gradle.kts"))) return parent.resolve("build.gradle.kts");
        }
        return null;
    }

    /**
     * 查找所有 Java 文件
     */
    private List<Path> findJavaFiles(Path sourceRoot) {
        if (!Files.exists(sourceRoot)) return Collections.emptyList();
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(CodeParseService::isNotMacJunk)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /**
     * 查找源码目录
     */
    private List<String> findSourceDirs(Path sourceRoot) {
        List<String> dirs = new ArrayList<>();
        try {
            if (Files.exists(sourceRoot.resolve("src/main/java"))) {
                dirs.add("src/main/java");
            }
            if (Files.exists(sourceRoot.resolve("src"))) {
                // 检查直接是源码的情况
                try (Stream<Path> stream = Files.list(sourceRoot.resolve("src"))) {
                    long javaFiles = stream.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".java")).count();
                    if (javaFiles > 0) {
                        dirs.add("src");
                    }
                }
            }
            // 直接在根目录
            try (Stream<Path> stream = Files.list(sourceRoot)) {
                long javaFiles = stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java")).count();
                if (javaFiles > 0 && dirs.isEmpty()) {
                    dirs.add(".");
                }
            }
        } catch (IOException ignored) {
        }
        return dirs;
    }

    private List<String> findTestDirs(Path sourceRoot) {
        List<String> dirs = new ArrayList<>();
        if (Files.exists(sourceRoot.resolve("src/test/java"))) {
            dirs.add("src/test/java");
        }
        return dirs;
    }

    /**
     * 检测字节码版本
     */
    private String detectBytecodeVersion(Path sourceRoot) {
        // 找 class 文件读取版本
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            Path classFile = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .findFirst().orElse(null);

            if (classFile != null) {
                byte[] bytes = Files.readAllBytes(classFile);
                if (bytes.length >= 8) {
                    // 第 5-6 字节是 minor version，第 7-8 字节是 major version
                    int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
                    // 45 = Java 1.1, 46 = 1.2, ..., 52 = 8, 55 = 11, 61 = 17
                    int version = major - 44;
                    if (version > 0) {
                        return String.valueOf(version);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
