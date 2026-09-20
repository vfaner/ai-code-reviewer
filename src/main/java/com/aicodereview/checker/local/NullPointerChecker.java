package com.aicodereview.checker.local;

import com.aicodereview.checker.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.IfStmt;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 空指针检测检查器
 *
 * 基于 AST 进行简单的空指针风险分析：
 * - 方法参数未判空就直接调用方法
 * - 可能返回 null 的方法调用后直接使用
 * - get() 后直接链式调用
 *
 * 注意：这是基于 AST 的静态分析，存在一定误报。
 */
@Component
public class NullPointerChecker extends AbstractLocalChecker {

    /** 已知可能返回 null 的方法名模式 */
    private static final Set<String> POSSIBLE_NULL_METHODS = Set.of(
            "get", "getFirst", "getLast", "peek", "poll",
            "find", "findFirst", "findAny",
            "getProperty", "getAttribute", "getParameter"
    );

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.NULL_POINTER;
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            if (method.getBody().isEmpty()) return;

            // 收集已进行 null 检查的变量
            Set<String> nullCheckedVars = new HashSet<>();

            // 分析方法中的 null 检查
            method.walk(IfStmt.class, ifStmt -> {
                Expression condition = ifStmt.getCondition();
                collectNullCheckedVars(condition, nullCheckedVars);
            });

            // 检查方法调用中的空指针风险
            method.walk(MethodCallExpr.class, methodCall -> {
                // 检查 scope 是否可能为 null
                if (methodCall.getScope().isPresent()
                        && methodCall.getScope().get() instanceof NameExpr nameExpr) {

                    String varName = nameExpr.getNameAsString();

                    // 检查是否是方法参数且未判空
                    boolean isParameter = method.getParameters().stream()
                            .anyMatch(p -> p.getNameAsString().equals(varName));

                    if (isParameter && !nullCheckedVars.contains(varName)) {
                        int line = methodCall.getBegin().map(p -> p.line).orElse(1);
                        issues.add(createIssue(
                                IssueLevel.CRITICAL,
                                "NULL_CHECK",
                                "可能存在空指针风险",
                                "方法参数 '" + varName + "' 在调用 '" + methodCall.getNameAsString()
                                        + "' 前未进行 null 检查，可能导致 NullPointerException",
                                context.getCurrentFilePath(),
                                line,
                                line
                        ));
                    }
                }

                // 检查链式调用：可能返回 null 的方法后面直接调用方法
                if (methodCall.getScope().isPresent()
                        && methodCall.getScope().get() instanceof MethodCallExpr innerCall) {

                    String innerMethodName = innerCall.getNameAsString();
                    if (POSSIBLE_NULL_METHODS.contains(innerMethodName)) {
                        int line = methodCall.getBegin().map(p -> p.line).orElse(1);
                        issues.add(createIssue(
                                IssueLevel.MAJOR,
                                "NULL_CHAIN_CALL",
                                "链式调用可能导致空指针",
                                "'" + innerMethodName + "()' 可能返回 null，直接调用 '"
                                        + methodCall.getNameAsString() + "' 存在空指针风险，建议先判空",
                                context.getCurrentFilePath(),
                                line,
                                line
                        ));
                    }
                }
            });
        });
    }

    /**
     * 从条件表达式中收集被判空的变量
     */
    private void collectNullCheckedVars(Expression condition, Set<String> nullCheckedVars) {
        if (condition instanceof BinaryExpr binaryExpr) {
            // 检查 var == null 或 var != null
            if (binaryExpr.getOperator() == BinaryExpr.Operator.EQUALS
                    || binaryExpr.getOperator() == BinaryExpr.Operator.NOT_EQUALS) {

                Expression left = binaryExpr.getLeft();
                Expression right = binaryExpr.getRight();

                if (isNullLiteral(right) && left instanceof NameExpr nameExpr) {
                    nullCheckedVars.add(nameExpr.getNameAsString());
                } else if (isNullLiteral(left) && right instanceof NameExpr nameExpr) {
                    nullCheckedVars.add(nameExpr.getNameAsString());
                }
            }

            // 递归处理 && 和 || 的两侧
            if (binaryExpr.getOperator() == BinaryExpr.Operator.AND
                    || binaryExpr.getOperator() == BinaryExpr.Operator.OR) {
                collectNullCheckedVars(binaryExpr.getLeft(), nullCheckedVars);
                collectNullCheckedVars(binaryExpr.getRight(), nullCheckedVars);
            }
        }

        // 处理 ! 非运算
        if (condition instanceof UnaryExpr unaryExpr
                && unaryExpr.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            collectNullCheckedVars(unaryExpr.getExpression(), nullCheckedVars);
        }
    }

    private boolean isNullLiteral(Expression expr) {
        return expr instanceof NullLiteralExpr;
    }
}
