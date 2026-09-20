package com.aicodereview.checker.local;

import com.aicodereview.checker.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 空 catch 块检查器
 *
 * 检测 catch 块为空或只有注释的情况。
 * 空的 catch 块会吞掉异常，导致问题难以排查。
 */
@Component
public class EmptyCatchChecker extends AbstractLocalChecker {

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.EXCEPTION_HANDLING;
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        cu.findAll(CatchClause.class).forEach(catchClause -> {
            // 检查 catch 块是否为空
            if (isEmptyBody(catchClause.getBody())) {
                int line = catchClause.getBegin().map(p -> p.line).orElse(1);
                String exceptionType = catchClause.getParameter().getType().asString();

                issues.add(createIssue(
                        IssueLevel.MAJOR,
                        "EMPTY_CATCH",
                        "空的 catch 块",
                        "捕获 " + exceptionType + " 的 catch 块为空，异常被静默吞掉，建议至少记录日志或重新抛出异常",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        });
    }

    /**
     * 判断 catch 块是否为空（或只有注释）
     */
    private boolean isEmptyBody(com.github.javaparser.ast.stmt.BlockStmt body) {
        if (body.getStatements().isEmpty()) {
            return true;
        }

        // 检查所有语句是否都只是注释（空语句等）
        for (Statement stmt : body.getStatements()) {
            // 如果只有空语句、注释，不算有效代码
            if (stmt instanceof com.github.javaparser.ast.stmt.EmptyStmt) {
                continue;
            }
            // 有实际语句
            return false;
        }

        return true;
    }
}
