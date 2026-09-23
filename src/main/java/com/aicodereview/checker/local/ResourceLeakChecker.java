package com.aicodereview.checker.local;

import com.aicodereview.checker.CheckContext;
import com.aicodereview.checker.CheckIssue;
import com.aicodereview.checker.CheckerType;
import com.aicodereview.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;

import java.util.List;
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
 */
@Component
public class ResourceLeakChecker extends AbstractLocalChecker {

    /** 资源类型（类名后缀） */
    private static final Set<String> RESOURCE_TYPE_SUFFIXES = Set.of(
            "InputStream", "OutputStream",
            "Reader", "Writer",
            "Connection", "Statement", "ResultSet",
            "Socket", "ServerSocket",
            "Scanner",
            "Channel", "Stream", "ZipFile"
    );

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
        // 检查变量声明的资源类型
        cu.findAll(VariableDeclarator.class).forEach(var -> {
            if (isResourceType(var.getTypeAsString())) {
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

    /**
     * 判断类型是否是资源类型
     */
    private boolean isResourceType(String typeName) {
        if (typeName == null || typeName.isEmpty()) return false;
        // 去掉泛型部分
        String simpleType = typeName.replaceAll("<.*>", "");
        for (String suffix : RESOURCE_TYPE_SUFFIXES) {
            if (simpleType.endsWith(suffix) || simpleType.equals(suffix)) {
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
        var methodOpt = var.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class);
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
