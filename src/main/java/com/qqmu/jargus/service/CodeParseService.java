package com.qqmu.jargus.service;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerRegistry;
import com.qqmu.jargus.checker.CodeChecker;
import com.qqmu.jargus.checker.PostScanChecker;
import com.qqmu.jargus.llm.AiClientFactory;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 代码解析服务
 *
 * 负责：
 * - 扫描源码目录，找出所有 Java 文件
 * - 使用 JavaParser 解析 AST
 * - 调用检查器执行检查
 * - 汇总检查结果
 */
@Slf4j
@Service
public class CodeParseService {

    private final CheckerRegistry checkerRegistry;
    private final JavaParser javaParser;
    private final AiClientFactory aiClientFactory;
    /** AI 评审专用线程池（LLM 调用 IO 密集，逐文件并发，与扫描池隔离） */
    private final Executor aiReviewExecutor;

    public CodeParseService(CheckerRegistry checkerRegistry,
                            AiClientFactory aiClientFactory,
                            @Qualifier("aiReviewExecutor") Executor aiReviewExecutor) {
        this.checkerRegistry = checkerRegistry;
        this.aiClientFactory = aiClientFactory;
        this.aiReviewExecutor = aiReviewExecutor;

        // 配置 JavaParser
        CombinedTypeSolver combinedSolver = new CombinedTypeSolver();
        combinedSolver.add(new ReflectionTypeSolver());

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedSolver);

        ParserConfiguration configuration = new ParserConfiguration();
        configuration.setSymbolResolver(symbolSolver);
        configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        configuration.setStoreTokens(true);
        configuration.setLexicalPreservationEnabled(true);

        this.javaParser = new JavaParser(configuration);
    }

    /**
     * 扫描并检查指定目录下的所有 Java 文件
     *
     * @param sourceRoot     源码根目录
     * @param includeTest    是否包含测试代码
     * @param taskId         任务ID
     * @param jdkVersion     JDK 版本
     * @param springBootVersion Spring Boot 版本
     * @return 所有检查发现的问题
     */
    public List<CheckIssue> scanAndCheck(
            Path sourceRoot,
            boolean includeTest,
            boolean enableAiReview,
            Long taskId,
            String jdkVersion,
            String springBootVersion
    ) {
        List<CheckIssue> allIssues = Collections.synchronizedList(new ArrayList<>());

        List<Path> javaFiles = findJavaFiles(sourceRoot, includeTest);
        log.info("找到 {} 个 Java 文件", javaFiles.size());

        // 获取启用的本地检查器
        List<CodeChecker> localCheckers = checkerRegistry.getEnabledLocalCheckers();
        log.info("启用的本地检查器: {} 个", localCheckers.size());

        // 获取启用的 AI 检查器（如果配置了 AI）
        List<CodeChecker> aiCheckers = Collections.emptyList();
        if (enableAiReview && aiClientFactory.isAiConfigured()) {
            aiCheckers = checkerRegistry.getEnabledAiCheckers();
            log.info("启用的 AI 检查器: {} 个", aiCheckers.size());
        }

        // 合并所有检查器（PostScanChecker 扫描级后处理仍用合并全表；AI 检查器不实现该接口，行为不变）
        List<CodeChecker> checkers = new ArrayList<>();
        checkers.addAll(localCheckers);
        checkers.addAll(aiCheckers);

        // 全局上下文数据
        Map<String, Object> globalData = new HashMap<>();

        // 统计文件数和行数
        int[] stats = {0, 0};

        for (Path javaFile : javaFiles) {
            try {
                stats[0]++;
                // 逐文件循环只跑本地检查器：checkFile 的上下文 enableAiReview=false，
                // AI 检查器在此会被 accept() 拒绝，统一放到下面的 AI 阶段执行
                List<CheckIssue> fileIssues = checkFile(
                        sourceRoot,
                        javaFile,
                        localCheckers,
                        taskId,
                        jdkVersion,
                        springBootVersion,
                        includeTest,
                        globalData
                );
                allIssues.addAll(fileIssues);
                stats[1] += countLines(javaFile);
            } catch (Exception e) {
                log.warn("检查文件 {} 出错: {}", javaFile, e.getMessage());
            }
        }

        // AI 评审阶段：此前 AI 检查器混在逐文件循环里，但循环上下文 enableAiReview=false，
        // AbstractAiChecker.accept() 一律拒绝，扫描期 AI 评审实际从未执行（静默失效）。
        // 现拆为独立阶段：每个文件一个任务提交 aiReviewExecutor 并发执行，LLM 调用 IO 密集可安全并发。
        if (!aiCheckers.isEmpty() && !javaFiles.isEmpty()) {
            runAiReviewPhase(aiCheckers, javaFiles, sourceRoot, taskId,
                    jdkVersion, springBootVersion, includeTest, globalData, allIssues);
        }

        // 扫描级检查（跨文件汇总 / 依赖漏洞扫描）：文件循环结束后统一调用一次。
        // 即使没有任何 Java 文件也执行（依赖扫描只需 pom.xml）。
        CheckContext postCtx = CheckContext.builder()
                .taskId(taskId)
                .sourceRoot(sourceRoot)
                .jdkVersion(jdkVersion)
                .springBootVersion(springBootVersion)
                .includeTestCode(includeTest)
                .globalData(globalData)
                .build();
        for (CodeChecker checker : checkers) {
            if (checker instanceof PostScanChecker postScanChecker) {
                try {
                    allIssues.addAll(postScanChecker.postScanCheck(postCtx));
                } catch (Exception e) {
                    log.warn("检查器 {} 扫描级后处理出错: {}", checker.getName(), e.getMessage());
                }
            }
        }

        log.info("扫描完成: {} 个文件, {} 行代码, {} 个问题",
                stats[0], stats[1], allIssues.size());

        return allIssues;
    }

    /**
     * AI 评审阶段：每个文件作为独立任务提交 aiReviewExecutor 并发执行，等待全部完成。
     * 结果汇入 allIssues（本身是线程安全的 synchronizedList）。
     */
    private void runAiReviewPhase(List<CodeChecker> aiCheckers,
                                  List<Path> javaFiles,
                                  Path sourceRoot,
                                  Long taskId,
                                  String jdkVersion,
                                  String springBootVersion,
                                  boolean includeTest,
                                  Map<String, Object> globalData,
                                  List<CheckIssue> allIssues) {
        log.info("开始 AI 评审阶段: {} 个文件, {} 个 AI 检查器", javaFiles.size(), aiCheckers.size());
        long start = System.currentTimeMillis();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Path javaFile : javaFiles) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    allIssues.addAll(checkFileAi(sourceRoot, javaFile, aiCheckers, taskId,
                            jdkVersion, springBootVersion, includeTest, globalData));
                } catch (Exception e) {
                    log.warn("AI 评审文件 {} 出错: {}", javaFile, e.getMessage());
                }
            }, aiReviewExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("AI 评审阶段完成: 耗时 {} ms", System.currentTimeMillis() - start);
    }

    /**
     * 对单个文件执行 AI 检查器：与 checkFile 同构，但上下文 enableAiReview=true 放行 AI 检查器
     */
    private List<CheckIssue> checkFileAi(Path sourceRoot,
                                         Path javaFile,
                                         List<CodeChecker> aiCheckers,
                                         Long taskId,
                                         String jdkVersion,
                                         String springBootVersion,
                                         boolean includeTest,
                                         Map<String, Object> globalData) {
        String relativePath = sourceRoot.relativize(javaFile).toString()
                .replace(File.separatorChar, '/');

        String sourceCode;
        List<String> sourceLines;
        try {
            sourceCode = Files.readString(javaFile, StandardCharsets.UTF_8);
            sourceLines = Files.readAllLines(javaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取文件失败: {}", javaFile);
            return Collections.emptyList();
        }

        // 尝试解析 AST（解析必须串行，原因同 checkFile）
        CompilationUnit cu = null;
        try {
            ParseResult<CompilationUnit> result;
            synchronized (javaParser) {
                result = javaParser.parse(sourceCode);
            }
            if (result.isSuccessful()) {
                cu = result.getResult().orElse(null);
            }
        } catch (Exception e) {
            log.debug("AI 阶段解析文件异常: {} - {}", relativePath, e.getMessage());
        }

        CheckContext context = CheckContext.builder()
                .taskId(taskId)
                .sourceRoot(sourceRoot)
                .currentFilePath(relativePath)
                .currentFileAbsolutePath(javaFile)
                .compilationUnit(cu)
                .sourceCode(sourceCode)
                .sourceLines(sourceLines)
                .jdkVersion(jdkVersion)
                .springBootVersion(springBootVersion)
                .includeTestCode(includeTest)
                .enableAiReview(true) // AI 阶段专属执行，放行 AI 检查器
                .globalData(globalData)
                .build();

        List<CheckIssue> fileIssues = new ArrayList<>();
        for (CodeChecker checker : aiCheckers) {
            try {
                if (checker.accept(context)) {
                    fileIssues.addAll(checker.check(context));
                }
            } catch (Exception e) {
                log.warn("AI 检查器 {} 处理文件 {} 时出错: {}",
                        checker.getName(), relativePath, e.getMessage());
            }
        }
        return fileIssues;
    }

    /**
     * 检查单个文件
     */
    public List<CheckIssue> checkFile(
            Path sourceRoot,
            Path javaFile,
            List<CodeChecker> checkers,
            Long taskId,
            String jdkVersion,
            String springBootVersion,
            boolean includeTest,
            Map<String, Object> globalData
    ) {
        String relativePath = sourceRoot.relativize(javaFile).toString()
                .replace(File.separatorChar, '/');

        String sourceCode = "";
        List<String> sourceLines = Collections.emptyList();

        try {
            sourceCode = Files.readString(javaFile, StandardCharsets.UTF_8);
            sourceLines = Files.readAllLines(javaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取文件失败: {}", javaFile);
            return Collections.emptyList();
        }

        // 尝试解析 AST
        CompilationUnit cu = null;
        try {
            // JavaParser 单实例在开启 lexicalPreservation 时并发解析会竞态
            // （词法保存器共享状态，偶发 IndexOutOfBounds，导致整个文件 AST 丢失），解析必须串行
            ParseResult<CompilationUnit> result;
            synchronized (javaParser) {
                result = javaParser.parse(sourceCode);
            }
            if (result.isSuccessful()) {
                cu = result.getResult().orElse(null);
            } else {
                log.warn("解析文件失败: {} | {}", relativePath, result.getProblems());
            }
        } catch (Exception e) {
            log.debug("解析文件异常: {} - {}", relativePath, e.getMessage());
        }

        // 构建检查上下文
        CheckContext context = CheckContext.builder()
                .taskId(taskId)
                .sourceRoot(sourceRoot)
                .currentFilePath(relativePath)
                .currentFileAbsolutePath(javaFile)
                .compilationUnit(cu)
                .sourceCode(sourceCode)
                .sourceLines(sourceLines)
                .jdkVersion(jdkVersion)
                .springBootVersion(springBootVersion)
                .includeTestCode(includeTest)
                .enableAiReview(false) // AI 检查器由独立的 AI 评审阶段放行（见 runAiReviewPhase）
                .globalData(globalData)
                .build();

        List<CheckIssue> fileIssues = new ArrayList<>();

        for (CodeChecker checker : checkers) {
            try {
                if (checker.accept(context)) {
                    List<CheckIssue> issues = checker.check(context);
                    fileIssues.addAll(issues);
                }
            } catch (Exception e) {
                log.warn("检查器 {} 处理文件 {} 时出错: {}",
                        checker.getName(), relativePath, e.getMessage());
            }
        }

        return fileIssues;
    }

    /**
     * 查找目录下所有 Java 文件
     */
    public List<Path> findJavaFiles(Path sourceRoot, boolean includeTest) {
        if (!Files.exists(sourceRoot) || !Files.isDirectory(sourceRoot)) {
            return Collections.emptyList();
        }

        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(CodeParseService::isNotMacJunk)
                    .filter(p -> includeTest || !isTestFile(sourceRoot, p))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("扫描文件出错: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 排除 macOS 压缩包元数据：__MACOSX 目录下的文件与 ._ 开头的 AppleDouble 文件。
     * 它们是二进制文件（恰好可能以 .java 结尾），按 UTF-8 读取会抛 MalformedInputException，
     * 交给 JavaParser 还可能触发内部 AssertionError。
     */
    static boolean isNotMacJunk(Path p) {
        for (Path seg : p) {
            String n = seg.toString();
            if ("__MACOSX".equals(n) || n.startsWith("._")) return false;
        }
        return true;
    }

    /**
     * 判断是否是测试文件
     */
    private boolean isTestFile(Path sourceRoot, Path file) {
        String relativePath = sourceRoot.relativize(file).toString()
                .replace(File.separatorChar, '/');
        return relativePath.contains("/test/")
                || relativePath.startsWith("test/")
                || relativePath.endsWith("Test.java")
                || relativePath.endsWith("Tests.java");
    }

    /**
     * 统计文件行数
     */
    private int countLines(Path file) {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return (int) lines.count();
        } catch (IOException | UncheckedIOException e) {
            // 二进制/非 UTF-8 文件读不了，按 0 行处理
            return 0;
        }
    }
}
