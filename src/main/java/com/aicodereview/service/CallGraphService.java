package com.aicodereview.service;

import com.aicodereview.callgraph.AsmCallGraphBuilder;
import com.aicodereview.callgraph.AstCallGraphBuilder;
import com.aicodereview.callgraph.CallGraph;
import com.aicodereview.callgraph.MethodInfo;
import com.aicodereview.checker.CheckIssue;
import com.aicodereview.checker.CheckerType;
import com.aicodereview.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 调用图分析服务
 *
 * 负责构建调用图，分析未使用的方法。
 * 优先使用 ASM 字节码分析（准确），
 * 如果没有编译产物则回退到 AST 分析。
 */
@Slf4j
@Service
public class CallGraphService {

    /**
     * 分析源码目录，找出未被调用的方法
     *
     * @param sourceRoot      源码根目录
     * @param includeTest     是否包含测试代码
     * @param taskId          任务ID
     * @return 未被使用的方法列表
     */
    public List<CheckIssue> analyzeUnusedMethods(Path sourceRoot, boolean includeTest, Long taskId) {
        CallGraph callGraph = new CallGraph();

        // 尝试编译源码获取 class 文件
        Path classDir = tryCompile(sourceRoot);
        boolean hasClasses = classDir != null;

        if (hasClasses) {
            // 使用 ASM 分析（更准确）
            log.info("使用 ASM 字节码分析调用图");
            AsmCallGraphBuilder asmBuilder = new AsmCallGraphBuilder(callGraph);
            try {
                asmBuilder.analyzeDirectory(classDir);
            } catch (Exception e) {
                log.warn("ASM 分析失败，回退到 AST 分析: {}", e.getMessage());
                buildAstGraph(sourceRoot, includeTest, callGraph);
            }
        } else {
            // 使用 AST 分析
            log.info("使用 AST 分析调用图");
            buildAstGraph(sourceRoot, includeTest, callGraph);
        }

        callGraph.printStats();

        // 获取未使用方法
        List<MethodInfo> unusedMethods = callGraph.getUnusedMethods();
        log.info("发现 {} 个未被调用的方法", unusedMethods.size());

        // 转换为检查结果
        List<CheckIssue> issues = new ArrayList<>();
        for (MethodInfo method : unusedMethods) {
            // 只报告项目内部的类（跳过 java.lang 等）
            if (isExternalClass(method.getClassName())) {
                continue;
            }

            String file = method.getFilePath() != null ? method.getFilePath()
                    : method.getClassName().replace('.', '/') + ".java";

            CheckIssue issue = CheckIssue.builder()
                    .level(IssueLevel.MAJOR)
                    .checkerType(CheckerType.UNUSED_METHOD)
                    .checkerName("未使用方法检测")
                    .ruleCode("UNUSED_METHOD")
                    .title("方法 '" + method.getMethodName() + "' 可能未被使用")
                    .description("方法 '" + method.getMethodName() + "' 在代码中未发现调用点，可能是死代码。" +
                            "如果该方法是公共 API 或通过反射/依赖注入调用，可忽略此警告。")
                    .filePath(file)
                    .fileName(extractFileName(file))
                    .lineStart(method.getLineStart())
                    .lineEnd(method.getLineEnd())
                    .build();

            issues.add(issue);
        }

        return issues;
    }

    /**
     * 使用 AST 构建调用图
     */
    private void buildAstGraph(Path sourceRoot, boolean includeTest, CallGraph callGraph) {
        AstCallGraphBuilder astBuilder = new AstCallGraphBuilder(callGraph);

        List<Path> javaFiles = findJavaFiles(sourceRoot, includeTest);
        JavaParser parser = createParser();

        for (Path javaFile : javaFiles) {
            try {
                String source = Files.readString(javaFile, StandardCharsets.UTF_8);
                ParseResult<CompilationUnit> result = parser.parse(source);
                if (result.isSuccessful()) {
                    CompilationUnit cu = result.getResult().orElse(null);
                    if (cu != null) {
                        String relativePath = sourceRoot.relativize(javaFile).toString()
                                .replace(File.separatorChar, '/');
                        astBuilder.analyzeCompilationUnit(cu, relativePath, sourceRoot);
                    }
                }
            } catch (Exception e) {
                log.debug("解析文件失败: {} - {}", javaFile, e.getMessage());
            }
        }
    }

    /**
     * 尝试编译源码获取 class 文件
     */
    private Path tryCompile(Path sourceRoot) {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                log.debug("无法获取系统 Java 编译器");
                return null;
            }

            // 创建输出目录
            Path outputDir = Files.createTempDirectory("aicr-classes-");

            // 收集所有 Java 文件
            List<Path> javaFiles = findJavaFiles(sourceRoot, true);
            if (javaFiles.isEmpty()) {
                return null;
            }

            // 准备编译参数
            List<String> options = new ArrayList<>();
            options.add("-d");
            options.add(outputDir.toString());
            options.add("-source");
            options.add("17");
            options.add("-target");
            options.add("17");
            options.add("-proc:none"); // 不处理注解
            options.add("-Xlint:none");

            // 添加文件名
            List<String> files = javaFiles.stream()
                    .map(Path::toString)
                    .toList();

            // 执行编译
            ByteArrayOutputStream errStream = new ByteArrayOutputStream();
            int result = compiler.run(null, null, errStream,
                    Stream.concat(options.stream(), files.stream()).toArray(String[]::new));

            if (result == 0) {
                log.info("源码编译成功，输出目录: {}", outputDir);
                return outputDir;
            } else {
                log.debug("源码编译失败 ({}), 将使用 AST 分析", result);
                // 即使编译失败也可能有部分 class 文件
                if (hasClassFiles(outputDir)) {
                    return outputDir;
                }
                deleteDirectory(outputDir);
                return null;
            }
        } catch (Exception e) {
            log.debug("编译失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasClassFiles(Path dir) {
        try {
            return Files.exists(dir) && Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walk(dir)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * 查找 Java 文件
     */
    private List<Path> findJavaFiles(Path root, boolean includeTest) {
        if (!Files.exists(root)) return Collections.emptyList();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(CodeParseService::isNotMacJunk)
                    .filter(p -> includeTest || !isTestFile(root, p))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private boolean isTestFile(Path root, Path file) {
        String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
        return relative.contains("/test/")
                || relative.startsWith("test/")
                || relative.endsWith("Test.java")
                || relative.endsWith("Tests.java");
    }

    /**
     * 判断是否是外部类（JDK 或第三方库）
     */
    private boolean isExternalClass(String className) {
        if (className == null) return true;
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.")
                || className.startsWith("org.springframework.")
                || className.startsWith("org.junit.")
                || className.startsWith("<"); // 作用域解析失败的
    }

    private String extractFileName(String path) {
        int idx = path.lastIndexOf('/');
        if (idx >= 0) {
            return path.substring(idx + 1);
        }
        return path;
    }

    private JavaParser createParser() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        config.setStoreTokens(true);
        return new JavaParser(config);
    }
}
