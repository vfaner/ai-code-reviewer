package com.aicodereview.callgraph;

import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * ASM 字节码调用图构建器
 *
 * 通过分析 .class 文件构建调用图，比 AST 分析更准确：
 * - 能正确识别所有调用（包括 lambda、反射外的动态调用）
 * - 能获取精确的方法描述符
 * - 可以分析第三方库的类（如果有 class 文件）
 */
@Slf4j
public class AsmCallGraphBuilder {

    private final CallGraph callGraph;

    public AsmCallGraphBuilder(CallGraph callGraph) {
        this.callGraph = callGraph;
    }

    /**
     * 分析目录下所有 .class 文件
     */
    public void analyzeDirectory(Path classRoot) throws IOException {
        if (!Files.exists(classRoot) || !Files.isDirectory(classRoot)) {
            log.warn("类目录不存在: {}", classRoot);
            return;
        }

        try (Stream<Path> stream = Files.walk(classRoot)) {
            List<Path> classFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .toList();

            log.info("找到 {} 个 class 文件", classFiles.size());

            for (Path classFile : classFiles) {
                try {
                    analyzeClassFile(classFile, classRoot);
                } catch (Exception e) {
                    log.debug("分析 class 文件失败: {} - {}", classFile, e.getMessage());
                }
            }
        }
    }

    /**
     * 分析单个 .class 文件
     */
    public void analyzeClassFile(Path classFile, Path classRoot) throws IOException {
        try (InputStream is = new FileInputStream(classFile.toFile())) {
            ClassReader cr = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, ClassReader.SKIP_FRAMES);

            String className = classNode.name.replace('/', '.');
            // 源文件相对路径：内部类的 classNode.name 含 $，需用包路径 + SourceFile 属性
            String sourcePath = resolveSourcePath(classNode);

            // 收集方法定义
            for (MethodNode method : classNode.methods) {
                boolean isPublic = (method.access & Opcodes.ACC_PUBLIC) != 0;
                boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
                boolean isAbstract = (method.access & Opcodes.ACC_ABSTRACT) != 0;
                boolean isNative = (method.access & Opcodes.ACC_NATIVE) != 0;
                boolean isConstructor = "<init>".equals(method.name) || "<clinit>".equals(method.name);

                // 从字节码指令流中的 LineNumberNode 取方法真实起止行（抽象/本地方法无行号，保持 0）
                int lineStart = 0;
                int lineEnd = 0;
                if (method.instructions != null) {
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn instanceof LineNumberNode lnn && lnn.line > 0) {
                            lineStart = lineStart == 0 ? lnn.line : Math.min(lineStart, lnn.line);
                            lineEnd = Math.max(lineEnd, lnn.line);
                        }
                    }
                }

                MethodInfo methodInfo = MethodInfo.builder()
                        .className(className)
                        .methodName(method.name)
                        .descriptor(method.desc)
                        .filePath(sourcePath)
                        .lineStart(lineStart)
                        .lineEnd(lineEnd)
                        .isPublic(isPublic)
                        .isStatic(isStatic)
                        .isConstructor(isConstructor)
                        .isAbstract(isAbstract)
                        .isNative(isNative)
                        .build();

                callGraph.addMethod(methodInfo);

                // 分析方法内的调用
                analyzeMethodCalls(methodInfo, method);
            }
        }
    }

    /**
     * 由字节码元数据推导源文件相对路径（包路径 + SourceFile 属性），
     * SourceFile 缺失时回退到 类名.java（内部类会退化为 外部$内部.java）
     */
    private String resolveSourcePath(ClassNode classNode) {
        String packagePath = "";
        int slash = classNode.name.lastIndexOf('/');
        if (slash >= 0) {
            packagePath = classNode.name.substring(0, slash + 1);
        }
        if (classNode.sourceFile != null && !classNode.sourceFile.isEmpty()) {
            return packagePath + classNode.sourceFile;
        }
        int dollar = classNode.name.lastIndexOf('$');
        String simple = dollar >= 0 ? classNode.name.substring(dollar + 1)
                : classNode.name.substring(slash + 1);
        return packagePath + simple + ".java";
    }

    /**
     * 分析方法内的调用指令
     */
    private void analyzeMethodCalls(MethodInfo caller, MethodNode methodNode) {
        if (methodNode.instructions == null) return;

        methodNode.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode methodInsn) {
                CallEdge.CallType callType = switch (methodInsn.getOpcode()) {
                    case Opcodes.INVOKESTATIC -> CallEdge.CallType.STATIC;
                    case Opcodes.INVOKESPECIAL -> CallEdge.CallType.SPECIAL;
                    case Opcodes.INVOKEVIRTUAL -> CallEdge.CallType.VIRTUAL;
                    case Opcodes.INVOKEINTERFACE -> CallEdge.CallType.INTERFACE;
                    default -> CallEdge.CallType.VIRTUAL;
                };

                String calleeClassName = methodInsn.owner.replace('/', '.');

                CallEdge edge = CallEdge.builder()
                        .caller(caller)
                        .calleeClassName(calleeClassName)
                        .calleeMethodName(methodInsn.name)
                        .calleeDescriptor(methodInsn.desc)
                        .callType(callType)
                        .lineNumber(0) // ASM 也可以获取行号，需要访问 LineNumberNode
                        .build();

                callGraph.addCall(edge);
            }
        });
    }
}
