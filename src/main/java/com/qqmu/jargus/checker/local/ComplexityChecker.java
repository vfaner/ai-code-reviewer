package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.qqmu.jargus.service.CheckerParamsService;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.expr.ConditionalExpr;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 圈复杂度检查器
 *
 * 计算方法的圈复杂度（Cyclomatic Complexity），
 * 超过阈值时发出警告。
 *
 * 数据形态折抵（R48 用户裁定）：均匀守卫表（连续 ≥3 条同形"无 else 单调用 if"行，
 * 如逐依赖探测框架的规则表）与均匀谓词链（≥3 个同形调用经 &&/|| 相连的白名单条件）
 * 的规模由数据条目数决定而非逻辑难度，各整段计 1 个决策点；嵌套/交错的真实分支不受影响。
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

        // 均匀守卫表（连续 ≥3 条同形 if 行）整表计 1 个决策点，行内运算符不再计（R48）
        Set<IfStmt> tableIfs = new HashSet<>();
        complexity.addAndGet(countUniformGuardTables(body, tableIfs));

        // 均匀谓词链（≥3 个同形调用经 &&/|| 相连）整链计 1 个决策点（R48）
        Set<BinaryExpr> chainTops = new HashSet<>();
        collectUniformPredicateChains(body, chainTops);

        // 统计 if 语句（每个 if 算 1，守卫表行跳过）
        body.walk(IfStmt.class, ifStmt -> {
            if (!tableIfs.contains(ifStmt)) {
                complexity.incrementAndGet();
            }
        });

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

        // 统计三元表达式（守卫表行内跳过）
        body.walk(ConditionalExpr.class, condExpr -> {
            if (!inAnyOf(condExpr, tableIfs)) {
                complexity.incrementAndGet();
            }
        });

        // 统计 && 和 || 运算符（每个算 1；守卫表行内跳过；均匀链只在链顶算 1）
        body.walk(BinaryExpr.class, binaryExpr -> {
            var op = binaryExpr.getOperator();
            if (op != BinaryExpr.Operator.AND && op != BinaryExpr.Operator.OR) {
                return;
            }
            if (inAnyOf(binaryExpr, tableIfs) || hasAncestorIn(binaryExpr, chainTops)) {
                return;
            }
            complexity.incrementAndGet();
        });

        return complexity.get();
    }

    /**
     * 均匀守卫表识别：同一语句块内连续 ≥3 条"无 else、then 为单条调用语句、
     * 且接收者.方法名相同"的 if（如逐依赖探测框架的 if (depNames.contains(..)) frameworks.add(..) 行）。
     * 这类结构是规则表/数据分派：行数由数据条目数决定而非逻辑难度，无嵌套无交错，
     * 整表计 1 个决策点；返回表数量并把表行登记进 tableIfs（R48 用户裁定）。
     */
    private int countUniformGuardTables(BlockStmt body, Set<IfStmt> tableIfs) {
        int runs = 0;
        Set<BlockStmt> blocks = new LinkedHashSet<>();
        blocks.add(body);
        blocks.addAll(body.findAll(BlockStmt.class));
        for (BlockStmt block : blocks) {
            List<Statement> stmts = block.getStatements();
            int runStart = 0;
            String runKey = null;
            for (int i = 0; i <= stmts.size(); i++) {
                String key = i < stmts.size() ? guardRowKey(stmts.get(i)) : null;
                if (key != null && key.equals(runKey)) {
                    continue;
                }
                if (runKey != null && i - runStart >= 3) {
                    runs++;
                    for (int j = runStart; j < i; j++) {
                        tableIfs.add((IfStmt) stmts.get(j));
                    }
                }
                runStart = i;
                runKey = key;
            }
        }
        return runs;
    }

    /** 均匀守卫行判定：无 else、then 为单条调用语句；键 = 调用接收者.方法名 */
    private String guardRowKey(Statement stmt) {
        if (!(stmt instanceof IfStmt ifStmt) || ifStmt.getElseStmt().isPresent()) {
            return null;
        }
        Statement then = ifStmt.getThenStmt();
        Statement inner = (then instanceof BlockStmt bs && bs.getStatements().size() == 1)
                ? bs.getStatements().get(0) : then;
        if (inner instanceof ExpressionStmt es && es.getExpression() instanceof MethodCallExpr mc) {
            return mc.getScope().map(Object::toString).orElse("") + "." + mc.getNameAsString();
        }
        return null;
    }

    /**
     * 均匀谓词链识别：≥3 个同接收者同方法名的调用经 &&/|| 连成的条件
     * （如 path.startsWith("/a") || path.startsWith("/b") || ... 的白名单）。
     * 条件内容是数据清单而非逻辑难度，整链计 1 个决策点；只登记链顶运算符（R48）。
     */
    private void collectUniformPredicateChains(BlockStmt body, Set<BinaryExpr> chainTops) {
        body.walk(BinaryExpr.class, expr -> {
            var op = expr.getOperator();
            if (op != BinaryExpr.Operator.AND && op != BinaryExpr.Operator.OR) {
                return;
            }
            // 链顶：父节点不再是同族逻辑运算符
            if (expr.getParentNode().filter(p -> p instanceof BinaryExpr pe
                    && (pe.getOperator() == BinaryExpr.Operator.AND
                        || pe.getOperator() == BinaryExpr.Operator.OR)).isPresent()) {
                return;
            }
            if (uniformPredicateKey(expr) != null && expr.findAll(MethodCallExpr.class).size() >= 3) {
                chainTops.add(expr);
            }
        });
    }

    /** 均匀谓词链的公共键：所有叶子调用同接收者同方法名；不均匀返回 null */
    private String uniformPredicateKey(Expression expr) {
        if (expr instanceof EnclosedExpr pe) {
            return uniformPredicateKey(pe.getInner());
        }
        if (expr instanceof BinaryExpr be
                && (be.getOperator() == BinaryExpr.Operator.AND
                    || be.getOperator() == BinaryExpr.Operator.OR)) {
            String left = uniformPredicateKey(be.getLeft());
            String right = uniformPredicateKey(be.getRight());
            return left != null && left.equals(right) ? left : null;
        }
        if (expr instanceof MethodCallExpr mc) {
            return mc.getScope().map(Object::toString).orElse("") + "." + mc.getNameAsString();
        }
        return null;
    }

    /** 节点是否位于给定 if 集合任一成员内部 */
    private boolean inAnyOf(Node node, Set<IfStmt> scopes) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof IfStmt ifStmt && scopes.contains(ifStmt)) {
                return true;
            }
            parent = parent.get().getParentNode();
        }
        return false;
    }

    /** 节点是否存在给定运算符集合任一成员作为祖先（链内运算符交给链顶计数） */
    private boolean hasAncestorIn(BinaryExpr expr, Set<BinaryExpr> tops) {
        Optional<Node> parent = expr.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof BinaryExpr be && tops.contains(be)) {
                return true;
            }
            parent = parent.get().getParentNode();
        }
        return false;
    }
}
