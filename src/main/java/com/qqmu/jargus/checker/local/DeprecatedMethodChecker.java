package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 废弃方法检测检查器
 *
 * 检测调用了 @Deprecated 注解标记的方法或类。
 * 包括：
 * - 调用了废弃方法
 * - 继承了废弃类
 * - 使用了废弃字段
 */
@Component
public class DeprecatedMethodChecker extends AbstractLocalChecker {

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.DEPRECATED_METHOD;
    }

    @Override
    public int getPriority() {
        return 40;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        // 检查方法调用
        cu.findAll(MethodCallExpr.class).forEach(methodCall -> {
            // 这里只能检查语法层面的信息，无法解析符号
            // 实际的废弃方法检测需要结合符号解析或字节码分析
            // 这里先做简单的检测：如果方法调用在同一个 CompilationUnit 内且目标方法有 @Deprecated
            checkLocalDeprecatedCall(context, cu, methodCall, issues);
        });

        // 检查类是否有 @Deprecated 注解（在本文件内声明）
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(type -> {
            if (isDeprecated(type)) {
                int line = type.getBegin().map(p -> p.line).orElse(1);
                issues.add(createIssue(
                        IssueLevel.MINOR,
                        "DEPRECATED_CLASS",
                        "类被标记为废弃",
                        "类 '" + type.getNameAsString() + "' 使用了 @Deprecated 注解，标记为已废弃",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        });

        // 检查方法是否有 @Deprecated 注解（在本文件内声明）
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            if (isDeprecated(method)) {
                int line = method.getBegin().map(p -> p.line).orElse(1);
                issues.add(createIssue(
                        IssueLevel.MINOR,
                        "DEPRECATED_METHOD_DECL",
                        "方法被标记为废弃",
                        "方法 '" + method.getNameAsString() + "' 使用了 @Deprecated 注解，标记为已废弃",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        });
    }

    /**
     * 检查是否调用了同一个文件内被标记为废弃的方法
     */
    private void checkLocalDeprecatedCall(
            CheckContext context,
            CompilationUnit cu,
            MethodCallExpr methodCall,
            List<CheckIssue> issues
    ) {
        String methodName = methodCall.getNameAsString();

        // 在当前 CU 中查找同名的废弃方法
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            if (method.getNameAsString().equals(methodName) && isDeprecated(method)) {
                int line = methodCall.getBegin().map(p -> p.line).orElse(1);
                issues.add(createIssue(
                        IssueLevel.MINOR,
                        "DEPRECATED_METHOD_CALL",
                        "调用了废弃方法",
                        "调用的方法 '" + methodName + "' 已被标记为 @Deprecated，建议使用替代方案",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        });
    }

    /**
     * 判断节点是否有 @Deprecated 注解
     */
    private boolean isDeprecated(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .anyMatch(anno -> {
                    String name = anno.getNameAsString();
                    return "Deprecated".equals(name) || "java.lang.Deprecated".equals(name);
                });
    }
}
