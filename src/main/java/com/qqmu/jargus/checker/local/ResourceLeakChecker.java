package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资源泄露检测检查器
 *
 * 检测可能未正确关闭的资源：
 * - InputStream / OutputStream
 * - Reader / Writer
 * - Connection / Statement / ResultSet
 * - Socket
 * - Scanner
 *
 * 优先推荐使用 try-with-resources。
 *
 * 类型判定：类名后缀启发筛出候选后，能经 import/全限定名解析的类型仅当落在已知资源包
 * （java.io/java.sql/java.net/java.nio/java.util.zip/javax.sql 及 java.util.Scanner）内
 * 才算资源——同名不同源的类型（如 JavaParser AST 的 Statement、java.util.stream 的 Stream）
 * 不实现 Closeable，纯后缀误判属误报高发区（R48）；解析不到的（通配符导入等）保守视为资源。
 */
@Component
public class ResourceLeakChecker extends AbstractLocalChecker {

    /** 资源类型（类名后缀，仅在无法通过 import 解析时作为启发式兜底） */
    private static final Set<String> RESOURCE_TYPE_SUFFIXES = Set.of(
            "InputStream", "OutputStream",
            "Reader", "Writer",
            "Connection", "Statement", "ResultSet",
            "Socket", "ServerSocket",
            "Scanner",
            "Channel", "Stream", "ZipFile"
    );

    /** 真正实现 Closeable/AutoCloseable 的资源包前缀 */
    private static final List<String> RESOURCE_PACKAGE_PREFIXES = List.of(
            "java.io.", "java.sql.", "java.net.", "java.nio.", "java.util.zip.", "javax.sql.");

    /** 资源包前缀覆盖不到的散点资源类型 */
    private static final Set<String> RESOURCE_FQCN_EXACT = Set.of("java.util.Scanner");

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.RESOURCE_LEAK;
    }

    @Override
    public int getPriority() {
        return 25;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        Map<String, String> importedTypes = importedTypes(cu);
        // 检查变量声明的资源类型
        cu.findAll(VariableDeclarator.class).forEach(var -> {
            if (isResourceType(var.getTypeAsString(), importedTypes)) {
                int line = var.getBegin().map(p -> p.line).orElse(1);

                // 检查是否在 try-with-resources 中
                boolean isTryWithResource = isInTryWithResources(var);

                // 检查是否在 finally 中被关闭
                boolean isClosedInFinally = isClosedInFinally(var);

                if (!isTryWithResource && !isClosedInFinally) {
                    issues.add(createIssue(
                            IssueLevel.CRITICAL,
                            "RESOURCE_LEAK",
                            "可能存在资源泄露",
                            "资源 '" + var.getNameAsString() + "' (" + var.getTypeAsString()
                                    + ") 未使用 try-with-resources，也未在 finally 中关闭，可能导致资源泄露",
                            context.getCurrentFilePath(),
                            line,
                            line
                    ));
                }
            }
        });
    }

    /** import 的简单名 → FQCN 映射（跳过通配符与静态导入） */
    private Map<String, String> importedTypes(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        cu.getImports().forEach(im -> {
            if (im.isAsterisk() || im.isStatic()) {
                return;
            }
            String fqcn = im.getNameAsString();
            int dot = fqcn.lastIndexOf('.');
            if (dot > 0) {
                map.put(fqcn.substring(dot + 1), fqcn);
            }
        });
        return map;
    }

    /**
     * 判断类型是否是资源类型：先按类名后缀启发筛出候选（召回口径与历史一致），
     * 再经 import/全限定名解析剔除非资源包的同名类型（JavaParser AST 的 Statement、
     * java.util.stream 的 Stream 等，R48）；解析不到的（通配符导入等）保守视为资源。
     */
    private boolean isResourceType(String typeName, Map<String, String> importedTypes) {
        if (typeName == null || typeName.isEmpty()) return false;
        // 去掉泛型部分
        String simpleType = typeName.replaceAll("<.*>", "");
        String simpleName = simpleType.contains(".")
                ? simpleType.substring(simpleType.lastIndexOf('.') + 1)
                : simpleType;
        boolean suffixMatch = false;
        for (String suffix : RESOURCE_TYPE_SUFFIXES) {
            if (simpleName.endsWith(suffix)) {
                suffixMatch = true;
                break;
            }
        }
        if (!suffixMatch) {
            return false;
        }
        String fqcn = null;
        if (simpleType.contains(".")) {
            // 直接书写的全限定名
            fqcn = simpleType;
        } else if (importedTypes != null) {
            fqcn = importedTypes.get(simpleType);
        }
        if (fqcn == null) {
            // 无法解析（通配符导入/同包类）：维持原后缀启发的保守召回
            return true;
        }
        return isResourceFqcn(fqcn);
    }

    private boolean isResourceFqcn(String fqcn) {
        if (RESOURCE_FQCN_EXACT.contains(fqcn)) {
            return true;
        }
        for (String prefix : RESOURCE_PACKAGE_PREFIXES) {
            if (fqcn.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断变量是否在 try-with-resources 中声明
     */
    private boolean isInTryWithResources(VariableDeclarator var) {
        return var.findAncestor(TryStmt.class).map(tryStmt -> {
            // 检查是否是 try-with-resources
            return tryStmt.getResources().size() > 0;
        }).orElse(false);
    }

    /**
     * 判断变量是否在 finally 中被关闭（简单检查）
     */
    private boolean isClosedInFinally(VariableDeclarator var) {
        String varName = var.getNameAsString();

        // 找到包含此变量的方法
        var methodOpt = var.findAncestor(MethodDeclaration.class);
        if (methodOpt.isEmpty()) {
            return false;
        }

        var method = methodOpt.get();
        if (method.getBody().isEmpty()) return false;

        // 简单检查：方法内是否有 try-finally，且 finally 中有 .close() 调用包含该变量名
        final boolean[] hasFinallyClose = {false};
        method.walk(TryStmt.class, tryStmt -> {
            if (tryStmt.getFinallyBlock().isPresent()) {
                BlockStmt finallyBlock = tryStmt.getFinallyBlock().get();
                finallyBlock.walk(MethodCallExpr.class, call -> {
                    if ("close".equals(call.getNameAsString())) {
                        if (call.getScope().isPresent()
                                && call.getScope().get() instanceof NameExpr nameExpr
                                && nameExpr.getNameAsString().equals(varName)) {
                            hasFinallyClose[0] = true;
                        }
                    }
                });
            }
        });

        return hasFinallyClose[0];
    }
}
