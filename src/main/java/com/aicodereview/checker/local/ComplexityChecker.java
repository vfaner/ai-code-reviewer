package com.aicodereview.checker.local;

import com.aicodereview.checker.*;
import com.aicodereview.service.CheckerParamsService;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.expr.ConditionalExpr;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 圈复杂度检查器
 *
 * 计算方法的圈复杂度（Cyclomatic Complexity），
 * 超过阈值时发出警告。
 *
 * 默认阈值：15，可通过 checker_config.params 配置 {"threshold":N} 覆盖
 */
@Component
@RequiredArgsConstructor
public class ComplexityChecker extends AbstractLocalChecker {

    /** 默认复杂度阈值 */
    private static final int DEFAULT_THRESHOLD = 15;

    private final CheckerParamsService checkerParamsService;

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.COMPLEXITY;
    }

    @Override
    public int getPriority() {
        return 35;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        int threshold = checkerParamsService.getInt(
                CheckerType.COMPLEXITY.getCode(), "threshold", DEFAULT_THRESHOLD);
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            // 跳过抽象方法和空方法
            if (method.getBody().isEmpty()) return;

            int complexity = calculateCyclomaticComplexity(method);

            if (complexity > threshold) {
                int line = method.getBegin().map(p -> p.line).orElse(1);
                issues.add(createIssue(
                        IssueLevel.MAJOR,
                        "MAX_COMPLEXITY",
                        "方法圈复杂度超标",
                        "方法 '" + method.getNameAsString() + "' 的圈复杂度为 " + complexity +
                                "，超过阈值 " + threshold +
                                "，建议拆分为多个方法以提高可读性和可维护性",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        });
    }

    /**
     * 计算方法的圈复杂度
     *
     * 圈复杂度 = 1 + 决策点数量
     *
     * 决策点包括：
     * - if / else if
     * - for / while / do-while
     * - switch case
     * - catch
     * - 三元表达式 (?:)
     * - && / || 逻辑运算符
     */
    private int calculateCyclomaticComplexity(MethodDeclaration method) {
        if (method.getBody().isEmpty()) {
            return 1;
        }

        AtomicInteger complexity = new AtomicInteger(1); // 基础复杂度 1

        BlockStmt body = method.getBody().get();

        // 统计 if 语句（每个 if 算 1）
        body.walk(IfStmt.class, ifStmt -> complexity.incrementAndGet());

        // 统计 for 循环
        body.walk(ForStmt.class, forStmt -> complexity.incrementAndGet());

        // 统计 for-each 循环
        body.walk(ForEachStmt.class, forEachStmt -> complexity.incrementAndGet());

        // 统计 while 循环
        body.walk(WhileStmt.class, whileStmt -> complexity.incrementAndGet());

        // 统计 do-while 循环
        body.walk(DoStmt.class, doStmt -> complexity.incrementAndGet());

        // 统计 switch（每个 case 算 1）
        body.walk(SwitchStmt.class, switchStmt -> {
            // switch 语句本身算 1
            complexity.incrementAndGet();
        });

        // 统计 catch 子句
        body.walk(CatchClause.class, catchClause -> complexity.incrementAndGet());

        // 统计三元表达式
        body.walk(ConditionalExpr.class, condExpr -> complexity.incrementAndGet());

        // 统计 && 和 || 运算符（每个算 1）
        body.walk(com.github.javaparser.ast.expr.BinaryExpr.class, binaryExpr -> {
            var op = binaryExpr.getOperator();
            if (op == com.github.javaparser.ast.expr.BinaryExpr.Operator.AND
                    || op == com.github.javaparser.ast.expr.BinaryExpr.Operator.OR) {
                complexity.incrementAndGet();
            }
        });

        return complexity.get();
    }
}
