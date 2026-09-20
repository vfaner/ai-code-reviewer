package com.aicodereview.checker.local;

import com.aicodereview.checker.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.*;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 魔法数字检查器
 *
 * 检测代码中使用的魔法数字，建议定义为常量。
 * 排除常见的 0, 1, -1, 2, 100 等常用数字。
 */
@Component
public class MagicNumberChecker extends AbstractLocalChecker {

    /** 允许的魔法数字集合（常见值不报告） */
    private static final Set<Double> ALLOWED_NUMBERS = new HashSet<>();

    static {
        // 常用小整数
        ALLOWED_NUMBERS.add(0.0);
        ALLOWED_NUMBERS.add(1.0);
        ALLOWED_NUMBERS.add(-1.0);
        ALLOWED_NUMBERS.add(2.0);
        ALLOWED_NUMBERS.add(3.0);
        ALLOWED_NUMBERS.add(10.0);
        ALLOWED_NUMBERS.add(100.0);
        ALLOWED_NUMBERS.add(1000.0);
        ALLOWED_NUMBERS.add(-1.0);
    }

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.CODE_STYLE;
    }

    @Override
    public int getPriority() {
        return 60;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        cu.findAll(LiteralExpr.class).forEach(literal -> {
            if (literal instanceof IntegerLiteralExpr) {
                checkIntegerLiteral(context, (IntegerLiteralExpr) literal, issues);
            } else if (literal instanceof LongLiteralExpr) {
                checkLongLiteral(context, (LongLiteralExpr) literal, issues);
            } else if (literal instanceof DoubleLiteralExpr) {
                checkDoubleLiteral(context, (DoubleLiteralExpr) literal, issues);
            }
        });
    }

    private void checkIntegerLiteral(CheckContext context, IntegerLiteralExpr expr, List<CheckIssue> issues) {
        try {
            int value = expr.asInt();
            if (isMagicNumber(value) && !isInConstantContext(expr) && !isInArrayInitializer(expr)) {
                int line = expr.getBegin().map(p -> p.line).orElse(1);
                issues.add(createIssue(
                        IssueLevel.MINOR,
                        "MAGIC_NUMBER",
                        "存在魔法数字",
                        "数字 '" + value + "' 建议定义为具名常量，提高代码可读性",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        } catch (Exception ignored) {
        }
    }

    private void checkLongLiteral(CheckContext context, LongLiteralExpr expr, List<CheckIssue> issues) {
        try {
            long value = expr.asLong();
            if (isMagicNumber(value) && !isInConstantContext(expr)) {
                int line = expr.getBegin().map(p -> p.line).orElse(1);
                issues.add(createIssue(
                        IssueLevel.MINOR,
                        "MAGIC_NUMBER",
                        "存在魔法数字",
                        "数字 '" + value + "' 建议定义为具名常量，提高代码可读性",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        } catch (Exception ignored) {
        }
    }

    private void checkDoubleLiteral(CheckContext context, DoubleLiteralExpr expr, List<CheckIssue> issues) {
        try {
            double value = expr.asDouble();
            if (isMagicNumber(value) && !isInConstantContext(expr)) {
                int line = expr.getBegin().map(p -> p.line).orElse(1);
                issues.add(createIssue(
                        IssueLevel.MINOR,
                        "MAGIC_NUMBER",
                        "存在魔法数字",
                        "数字 '" + value + "' 建议定义为具名常量，提高代码可读性",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        } catch (Exception ignored) {
        }
    }


    /**
     * 判断是否为魔法数字
     */
    private boolean isMagicNumber(long value) {
        return !ALLOWED_NUMBERS.contains((double) value);
    }

    private boolean isMagicNumber(double value) {
        return !ALLOWED_NUMBERS.contains(value);
    }

    /**
     * 判断是否在常量上下文中（static final 字段初始化）
     */
    private boolean isInConstantContext(Expression expr) {
        // 向上查找父节点，如果是 static final 字段的初始化值，则不算魔法数字
        var parent = expr.getParentNode();
        while (parent.isPresent()) {
            var node = parent.get();
            if (node instanceof com.github.javaparser.ast.body.FieldDeclaration field) {
                return field.isStatic() && field.isFinal();
            }
            // 如果遇到赋值表达式，不往上找了
            if (node instanceof AssignExpr) {
                return false;
            }
            parent = node.getParentNode();
        }
        return false;
    }

    /**
     * 判断是否在数组初始化器中
     */
    private boolean isInArrayInitializer(Expression expr) {
        return expr.findAncestor(ArrayInitializerExpr.class).isPresent();
    }
}
