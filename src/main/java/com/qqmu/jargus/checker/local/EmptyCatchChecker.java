package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 空 catch 块检查器
 *
 * 检测「空且无任何说明」的 catch 块——异常被静默吞掉，问题难以排查。
 * 两种业界通行的显式意图信号不报（R42 收紧）：
 * 1. 异常变量命名为 ignored / ignore / expected（IntelliJ 同款约定，命名即声明故意忽略）；
 * 2. catch 体内有注释说明忽略原因（与本项目修复建议「确需忽略时注释说明原因」一致）。
 * 其余空 catch（含只有空语句的）一律上报。
 */
@Component
public class EmptyCatchChecker extends AbstractLocalChecker {

    /** 显式声明故意忽略的异常变量命名约定 */
    private static final Set<String> EXPLICIT_IGNORED_NAMES = Set.of("ignored", "ignore", "expected");

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
            // 命名约定即显式意图：ignored / expected 等不报
            if (EXPLICIT_IGNORED_NAMES.contains(
                    catchClause.getParameter().getNameAsString().toLowerCase(Locale.ROOT))) {
                return;
            }
            // 检查 catch 块是否为空且无说明
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
     * 判断 catch 块是否为「空且无说明」：无实际语句（仅空语句）且体内没有任何注释。
     * 有注释说明忽略原因的空 catch 视为已文档化的故意忽略，不报。
     */
    private boolean isEmptyBody(BlockStmt body) {
        for (Statement stmt : body.getStatements()) {
            // 空语句不算有效代码，其余实际语句一律视为已处理
            if (!(stmt instanceof EmptyStmt)) {
                return false;
            }
        }
        return body.getAllContainedComments().isEmpty();
    }
}
