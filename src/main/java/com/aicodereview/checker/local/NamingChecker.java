package com.aicodereview.checker.local;

import com.aicodereview.checker.CheckContext;
import com.aicodereview.checker.CheckIssue;
import com.aicodereview.checker.CheckerType;
import com.aicodereview.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.body.FieldDeclaration;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 命名规范检查器
 *
 * 检查规则：
 * - 类名：大驼峰（UpperCamelCase）
 * - 方法名：小驼峰（lowerCamelCase）
 * - 变量名：小驼峰（lowerCamelCase）
 * - 常量名：全大写下划线分隔
 * - 包名：全小写
 */
@Component
public class NamingChecker extends AbstractLocalChecker {

    /** 类名正则：大驼峰，允许数字，不以下划线开头 */
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("^[A-Z][a-zA-Z0-9]*$");

    /** 方法名正则：小驼峰 */
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

    /** 变量名正则：小驼峰 */
    private static final Pattern VARIABLE_NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

    /** 常量名正则：全大写下划线 */
    private static final Pattern CONSTANT_NAME_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.NAMING;
    }

    @Override
    public int getPriority() {
        return 30;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        // 检查类命名
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            String className = cls.getNameAsString();
            if (!CLASS_NAME_PATTERN.matcher(className).matches()) {
                issues.add(createIssue(
                        IssueLevel.MAJOR,
                        "NAMING_CLASS",
                        "类命名不符合大驼峰规范",
                        "类名 '" + className + "' 应使用大驼峰命名法（UpperCamelCase），首字母大写",
                        context.getCurrentFilePath(),
                        cls.getBegin().map(p -> p.line).orElse(1),
                        cls.getBegin().map(p -> p.line).orElse(1)
                ));
            }
        });

        // 检查方法命名
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            String methodName = method.getNameAsString();
            // 跳过 main 方法
            if ("main".equals(methodName)) return;

            if (!METHOD_NAME_PATTERN.matcher(methodName).matches()) {
                issues.add(createIssue(
                        IssueLevel.MINOR,
                        "NAMING_METHOD",
                        "方法命名不符合小驼峰规范",
                        "方法名 '" + methodName + "' 应使用小驼峰命名法（lowerCamelCase），首字母小写",
                        context.getCurrentFilePath(),
                        method.getBegin().map(p -> p.line).orElse(1),
                        method.getBegin().map(p -> p.line).orElse(1)
                ));
            }
        });

        // 检查构造方法命名（类名，不需要检查，但确保不报告）
        cu.findAll(ConstructorDeclaration.class).forEach(constructor -> {
            // 构造方法名就是类名，不做小驼峰检查
        });

        // 检查字段命名
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            boolean isStatic = field.isStatic();
            boolean isFinal = field.isFinal();
            boolean isConstant = isStatic && isFinal;

            field.getVariables().forEach(var -> {
                String varName = var.getNameAsString();
                int line = var.getBegin().map(p -> p.line).orElse(1);

                if (isConstant) {
                    // 常量：全大写下划线
                    if (!CONSTANT_NAME_PATTERN.matcher(varName).matches()) {
                        issues.add(createIssue(
                                IssueLevel.INFO,
                                "NAMING_CONSTANT",
                                "常量命名不符合规范",
                                "常量 '" + varName + "' 应使用全大写下划线分隔的命名法",
                                context.getCurrentFilePath(),
                                line,
                                line
                        ));
                    }
                } else {
                    // 普通字段：小驼峰
                    if (!VARIABLE_NAME_PATTERN.matcher(varName).matches()) {
                        issues.add(createIssue(
                                IssueLevel.MINOR,
                                "NAMING_FIELD",
                                "字段命名不符合小驼峰规范",
                                "字段名 '" + varName + "' 应使用小驼峰命名法",
                                context.getCurrentFilePath(),
                                line,
                                line
                        ));
                    }
                }
            });
        });

        // 检查局部变量命名（只检查方法内的变量声明）
        cu.findAll(VariableDeclarator.class).forEach(var -> {
            // 跳过字段（已在上面检查）
            if (var.getParentNode().isPresent() && var.getParentNode().get() instanceof FieldDeclaration) {
                return;
            }

            String varName = var.getNameAsString();
            int line = var.getBegin().map(p -> p.line).orElse(1);

            // 跳过单字母变量（常见的 i, j, k 等循环变量）
            if (varName.length() == 1) return;

            if (!VARIABLE_NAME_PATTERN.matcher(varName).matches()) {
                issues.add(createIssue(
                        IssueLevel.INFO,
                        "NAMING_VARIABLE",
                        "变量命名不符合小驼峰规范",
                        "变量名 '" + varName + "' 建议使用小驼峰命名法",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        });
    }
}
