package com.aicodereview.checker.local;

import com.aicodereview.checker.CheckContext;
import com.aicodereview.checker.CheckIssue;
import com.aicodereview.checker.CheckerType;
import com.aicodereview.checker.IssueLevel;
import com.aicodereview.checker.PostScanChecker;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 空指针检测检查器
 *
 * 基于 AST 进行简单的空指针风险分析：
 * - 方法参数未判空就直接调用方法（NULL_CHECK）
 * - 可能返回 null 的方法调用后直接使用（NULL_CHAIN_CALL）
 *
 * NULL_CHECK 误报治理（R41）：
 * 1. 框架契约参数白名单：@ExceptionHandler / @Around / @EventListener 等方法的参数由框架注入，
 *    实例必然非空（如异常处理器收到的就是正在处理的异常对象），直接跳过；
 * 2. 调用方链路裁决：doCheck 阶段只登记候选，postScanCheck 阶段通读全项目源码收集调用点，
 *    仅当「目标方法在项目内唯一（同名同参数个数无重载/接口多实现歧义）」且「所有调用点实参
 *    均可证非空（字面量 / new / 恒非空方法返回值 / 调用前已判空退出）」时才抑制；
 *    找不到调用点（反射、SPI、死代码）或任一实参存疑时保持上报，宁可多报不可漏报。
 */
@Component
public class NullPointerChecker extends AbstractLocalChecker implements PostScanChecker {

    /** 已知可能返回 null 的方法名模式 */
    private static final Set<String> POSSIBLE_NULL_METHODS = Set.of(
            "get", "getFirst", "getLast", "peek", "poll",
            "find", "findFirst", "findAny",
            "getProperty", "getAttribute", "getParameter"
    );

    /** 框架契约：标注这些注解的方法，其参数由框架注入且保证非空 */
    private static final Set<String> FRAMEWORK_INJECTED = Set.of(
            "ExceptionHandler", "Around", "Before", "After", "AfterReturning", "AfterThrowing",
            "EventListener", "TransactionalEventListener", "MessageMapping"
    );

    /** 返回值恒非空的方法名（用于调用点实参裁决） */
    private static final Set<String> NON_NULL_RETURNING = Set.of(
            "of", "copyOf", "asList", "requireNonNull", "values", "valueOf", "format", "toString"
    );

    private static final String GD_CANDIDATES = "null_check_candidates";

    /** 一处 NULL_CHECK 候选，等待 postScan 阶段链路裁决 */
    private record Candidate(String filePath, int line, String methodName,
                             int paramIndex, int arity, String paramName, String calledName) {}

    /** 一个调用点：调用方方法 + 调用表达式 + 调用行号（实参按候选参数位现取） */
    private record CallSite(MethodDeclaration caller, MethodCallExpr call, int line) {}

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

            // 框架契约参数（异常实例 / 连接点 / 事件对象等）由框架注入，恒非空
            boolean frameworkInjected = method.getAnnotations().stream()
                    .anyMatch(a -> FRAMEWORK_INJECTED.contains(a.getNameAsString()));

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
                    int paramIndex = -1;
                    for (int i = 0; i < method.getParameters().size(); i++) {
                        if (method.getParameters().get(i).getNameAsString().equals(varName)) {
                            paramIndex = i;
                            break;
                        }
                    }

                    if (paramIndex >= 0 && !nullCheckedVars.contains(varName) && !frameworkInjected) {
                        int line = methodCall.getBegin().map(p -> p.line).orElse(1);
                        if (context.getGlobalData() != null) {
                            // 登记候选，postScan 阶段通调用链路裁决是否真风险
                            @SuppressWarnings("unchecked")
                            List<Candidate> candidates = (List<Candidate>) context.getGlobalData()
                                    .computeIfAbsent(GD_CANDIDATES, k -> new ArrayList<Candidate>());
                            candidates.add(new Candidate(
                                    context.getCurrentFilePath(), line,
                                    method.getNameAsString(), paramIndex,
                                    method.getParameters().size(), varName,
                                    methodCall.getNameAsString()));
                        } else {
                            issues.add(nullCheckIssue(context.getCurrentFilePath(), line,
                                    varName, methodCall.getNameAsString()));
                        }
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
     * 扫描收尾：通读全项目源码收集调用点，对候选逐一裁决。
     * 抑制条件（同时满足）：目标方法项目内唯一；存在调用点；所有调用点该参数位实参均可证非空。
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<CheckIssue> postScanCheck(CheckContext templateContext) {
        List<CheckIssue> issues = new ArrayList<>();
        Map<String, Object> globalData = templateContext.getGlobalData();
        if (globalData == null) {
            return issues;
        }
        Object raw = globalData.remove(GD_CANDIDATES);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return issues;
        }
        List<Candidate> candidates = (List<Candidate>) list;

        Path sourceRoot = templateContext.getSourceRoot();
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            // 无源码可读，无法裁决，保守全报
            candidates.forEach(c -> issues.add(
                    nullCheckIssue(c.filePath(), c.line(), c.paramName(), c.calledName())));
            return issues;
        }

        Map<String, Integer> definedCount = new HashMap<>();
        Map<String, List<CallSite>> callSites = new HashMap<>();
        collectCallGraph(sourceRoot, definedCount, callSites);

        for (Candidate c : candidates) {
            String key = c.methodName() + "|" + c.arity();
            List<CallSite> sites = callSites.get(key);
            boolean suppress = definedCount.getOrDefault(key, 0) == 1
                    && sites != null && !sites.isEmpty()
                    && sites.stream().allMatch(s -> {
                        List<Expression> args = s.call().getArguments();
                        Expression arg = args.size() > c.paramIndex() ? args.get(c.paramIndex()) : null;
                        return arg != null && isProvablyNonNull(arg, s.caller(), s.line());
                    });
            if (!suppress) {
                issues.add(nullCheckIssue(c.filePath(), c.line(), c.paramName(), c.calledName()));
            }
        }
        return issues;
    }

    /**
     * 遍历源码收集：项目内方法定义计数（同名同参数个数）、各方法的所有调用点实参
     */
    private void collectCallGraph(Path sourceRoot, Map<String, Integer> definedCount,
                                  Map<String, List<CallSite>> callSites) {
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            List<Path> javaFiles = walk
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .toList();
            for (Path file : javaFiles) {
                CompilationUnit cu;
                try {
                    cu = StaticJavaParser.parse(file);
                } catch (Exception e) {
                    continue;
                }
                cu.findAll(MethodDeclaration.class).forEach(m ->
                        definedCount.merge(m.getNameAsString() + "|" + m.getParameters().size(), 1, Integer::sum));
                cu.findAll(MethodDeclaration.class).forEach(caller ->
                        caller.walk(MethodCallExpr.class, call -> {
                            String key = call.getNameAsString() + "|" + call.getArguments().size();
                            callSites.computeIfAbsent(key, k -> new ArrayList<>())
                                    .add(new CallSite(caller, call, call.getBegin().map(p -> p.line).orElse(1)));
                        }));
            }
        } catch (Exception e) {
            // 遍历失败时保持空图，候选全报（保守）
        }
    }

    /**
     * 实参是否可证非空：字面量（非 null）/ new / 恒非空方法返回值 / 调用前已判空退出或改赋值的变量
     */
    private boolean isProvablyNonNull(Expression arg, MethodDeclaration caller, int callLine) {
        if (arg instanceof NullLiteralExpr) {
            return false;
        }
        if (arg instanceof LiteralExpr || arg instanceof ObjectCreationExpr) {
            return true;
        }
        if (arg instanceof MethodCallExpr m) {
            return NON_NULL_RETURNING.contains(m.getNameAsString());
        }
        if (arg instanceof NameExpr nameExpr) {
            return guardedBefore(caller, nameExpr.getNameAsString(), callLine);
        }
        return false;
    }

    /**
     * 调用方方法内、调用行之前是否存在对变量的有效判空：
     * if (v == null) { return/throw/continue/break/改赋值 } 或 Objects.requireNonNull(v)
     */
    private boolean guardedBefore(MethodDeclaration caller, String varName, int callLine) {
        if (caller.getBody().isEmpty()) {
            return false;
        }
        boolean[] guarded = {false};
        caller.getBody().get().walk(IfStmt.class, ifStmt -> {
            int ifLine = ifStmt.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);
            if (ifLine >= callLine || !conditionIsNullEquals(ifStmt.getCondition(), varName)) {
                return;
            }
            ifStmt.getThenStmt().walk(node -> {
                if (node instanceof ReturnStmt || node instanceof ThrowStmt) {
                    guarded[0] = true;
                }
                if (node instanceof AssignExpr assign
                        && assign.getTarget() instanceof NameExpr target
                        && target.getNameAsString().equals(varName)) {
                    guarded[0] = true;
                }
                if (node instanceof ContinueStmt
                        || node instanceof BreakStmt) {
                    guarded[0] = true;
                }
            });
        });
        caller.getBody().get().walk(MethodCallExpr.class, m -> {
            if ("requireNonNull".equals(m.getNameAsString())
                    && m.getArguments().size() == 1
                    && m.getArguments().get(0) instanceof NameExpr n
                    && n.getNameAsString().equals(varName)
                    && m.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE) < callLine) {
                guarded[0] = true;
            }
        });
        return guarded[0];
    }

    /** 条件是否为 v == null / null == v 形式（含 && / || 递归与 ! 取反内的子条件不视为判空退出） */
    private boolean conditionIsNullEquals(Expression condition, String varName) {
        if (condition instanceof BinaryExpr binary) {
            if (binary.getOperator() == BinaryExpr.Operator.EQUALS) {
                return (isName(binary.getLeft(), varName) && binary.getRight() instanceof NullLiteralExpr)
                        || (isName(binary.getRight(), varName) && binary.getLeft() instanceof NullLiteralExpr);
            }
            if (binary.getOperator() == BinaryExpr.Operator.AND) {
                return conditionIsNullEquals(binary.getLeft(), varName)
                        || conditionIsNullEquals(binary.getRight(), varName);
            }
        }
        return false;
    }

    private boolean isName(Expression expr, String varName) {
        return expr instanceof NameExpr n && n.getNameAsString().equals(varName);
    }

    private CheckIssue nullCheckIssue(String filePath, int line, String paramName, String calledName) {
        return createIssue(
                IssueLevel.CRITICAL,
                "NULL_CHECK",
                "可能存在空指针风险",
                "方法参数 '" + paramName + "' 在调用 '" + calledName
                        + "' 前未进行 null 检查，可能导致 NullPointerException",
                filePath,
                line,
                line
        );
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
