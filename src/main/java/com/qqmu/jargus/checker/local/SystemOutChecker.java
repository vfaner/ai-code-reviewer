package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * System.out / System.err 检查器
 *
 * 检测生产代码中使用 System.out.println 等输出语句，
 * 建议使用日志框架（SLF4J、Log4j 等）。
 */
@Component
public class SystemOutChecker extends AbstractLocalChecker {

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.CODE_STYLE;
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(methodCall -> {
            // 检查 System.out.println / print 等
            if (isSystemOutCall(methodCall) || isSystemErrCall(methodCall)) {
                int line = methodCall.getBegin().map(p -> p.line).orElse(1);
                String methodName = methodCall.getNameAsString();

                issues.add(createIssue(
                        IssueLevel.MAJOR,
                        "SYSTEM_OUT",
                        "使用 System.out/err 输出",
                        "生产代码建议使用日志框架（如 SLF4J）替代 System." + getStreamName(methodCall) + "." + methodName + "()，便于日志级别控制和持久化",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        });
    }

    /**
     * 判断是否是 System.out 的方法调用
     */
    private boolean isSystemOutCall(MethodCallExpr methodCall) {
        return methodCall.getScope().isPresent()
                && methodCall.getScope().get() instanceof FieldAccessExpr fieldAccess
                && isSystemOut(fieldAccess);
    }

    /**
     * 判断是否是 System.err 的方法调用
     */
    private boolean isSystemErrCall(MethodCallExpr methodCall) {
        return methodCall.getScope().isPresent()
                && methodCall.getScope().get() instanceof FieldAccessExpr fieldAccess
                && isSystemErr(fieldAccess);
    }

    private boolean isSystemOut(FieldAccessExpr fieldAccess) {
        return "out".equals(fieldAccess.getNameAsString())
                && fieldAccess.getScope() instanceof NameExpr nameExpr
                && "System".equals(nameExpr.getNameAsString());
    }

    private boolean isSystemErr(FieldAccessExpr fieldAccess) {
        return "err".equals(fieldAccess.getNameAsString())
                && fieldAccess.getScope() instanceof NameExpr nameExpr
                && "System".equals(nameExpr.getNameAsString());
    }

    private String getStreamName(MethodCallExpr methodCall) {
        if (methodCall.getScope().isPresent()
                && methodCall.getScope().get() instanceof FieldAccessExpr fieldAccess) {
            return fieldAccess.getNameAsString();
        }
        return "out";
    }
}
