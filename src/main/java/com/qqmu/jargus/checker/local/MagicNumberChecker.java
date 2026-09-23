package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 魔法数字检查器
 *
 * 检测代码中使用的魔法数字，建议定义为常量。
 * 排除常见的 0, 1, -1, 2, 100 等常用数字。
 * 参与算术运算的字面量（单位换算 ×60/×1000、位运算移位掩码、取模分桶等）
 * 具有明确计算语义，不视为魔法数字（R41 收紧）。
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
            if (isMagicNumber(value) && !isInConstantContext(expr) && !isInArrayInitializer(expr)
                    && !isInArithmeticExpression(expr)) {
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
            if (isMagicNumber(value) && !isInConstantContext(expr) && !isInArithmeticExpression(expr)) {
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
            if (isMagicNumber(value) && !isInConstantContext(expr) && !isInArithmeticExpression(expr)) {
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
            if (node instanceof FieldDeclaration field) {
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

    /**
     * 判断是否参与算术运算（加减乘除模、移位）：此类字面量带计算语义，如
     * seconds * 60、bytes / 1024、index % 3、mask << 4，不作为魔法数字报告。
     * 一元正负号（如 x * -60 中的 60）穿透一层后再判断。
     */
    private boolean isInArithmeticExpression(Expression expr) {
        var parent = expr.getParentNode();
        if (parent.isPresent() && parent.get() instanceof UnaryExpr unary
                && (unary.getOperator() == UnaryExpr.Operator.MINUS
                    || unary.getOperator() == UnaryExpr.Operator.PLUS)) {
            parent = unary.getParentNode();
        }
        return parent.isPresent() && parent.get() instanceof BinaryExpr binary
                && isArithmeticOperator(binary.getOperator());
    }

    private boolean isArithmeticOperator(BinaryExpr.Operator op) {
        return op == BinaryExpr.Operator.PLUS
                || op == BinaryExpr.Operator.MINUS
                || op == BinaryExpr.Operator.MULTIPLY
                || op == BinaryExpr.Operator.DIVIDE
                || op == BinaryExpr.Operator.REMAINDER
                || op == BinaryExpr.Operator.LEFT_SHIFT
                || op == BinaryExpr.Operator.SIGNED_RIGHT_SHIFT
                || op == BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT;
    }
}
