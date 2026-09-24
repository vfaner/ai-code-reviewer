package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.qqmu.jargus.service.CheckerParamsService;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 风格补充检查器（与 MagicNumberChecker / SystemOutChecker 同属 CODE_STYLE，
 * 注册中心按列表共存）
 *
 * 规则：
 * - STYLE_WILDCARD_IMPORT：通配符 import（import x.y.*）
 * - STYLE_LONG_LINE：超长行（默认 >120 字符，params 可配 maxLineLength）
 * - STYLE_TODO_COMMENT：遗留 TODO/FIXME 注释
 *
 * 每类问题单文件最多报告 10 条，避免大文件刷屏并过度扣分。
 */
@Component
@RequiredArgsConstructor
public class StyleExtraChecker extends AbstractLocalChecker {

    /** 默认行宽上限 */
    private static final int DEFAULT_MAX_LINE_LENGTH = 120;
    /** 单文件单规则报告上限 */
    private static final int MAX_PER_RULE_PER_FILE = 10;

    private static final Pattern TODO_PATTERN = Pattern.compile("//.*\\b(TODO|FIXME)\\b|/\\*+\\s*(TODO|FIXME)\\b");

    private final CheckerParamsService checkerParamsService;

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.CODE_STYLE;
    }

    @Override
    public int getPriority() {
        return 62;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        checkWildcardImports(context, cu, issues);
        checkLongLines(context, issues);
        checkTodoComments(context, issues);
    }

    private void checkWildcardImports(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        int count = 0;
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk()) {
                continue;
            }
            // java.lang.* 之类不会出现；静态通配 import 一并提示
            if (count++ >= MAX_PER_RULE_PER_FILE) {
                break;
            }
            int line = imp.getBegin().map(p -> p.line).orElse(1);
            // 通配符导入是纯风格提示，不影响正确性，按提示级（0 扣分）报告
            CheckIssue issue = createIssue(
                    IssueLevel.INFO,
                    "STYLE_WILDCARD_IMPORT",
                    "通配符导入",
                    "使用通配符导入 '" + imp + "'，会降低可读性并可能引入类名冲突",
                    context.getCurrentFilePath(), line, line);
            issue.setSuggestion("显式导入所用到的每个类（IDE 设置：类导入阈值调大 / 禁用 '*' 导入）");
            issues.add(issue);
        }
    }

    private void checkLongLines(CheckContext context, List<CheckIssue> issues) {
        int maxLength = checkerParamsService.getInt(
                CheckerType.CODE_STYLE.getCode(), "maxLineLength", DEFAULT_MAX_LINE_LENGTH);
        List<String> lines = context.getSourceLines();
        if (lines == null) {
            return;
        }
        int count = 0;
        for (int i = 0; i < lines.size() && count < MAX_PER_RULE_PER_FILE; i++) {
            int len = lines.get(i).length();
            if (len <= maxLength) {
                continue;
            }
            // 超长由单个字符串字面量（中文提示语/正则/URL 等）造成时豁免：
            // 字面量不可折行，拆开反而损害可读性，超长责任不在代码结构（R48）
            if (isLiteralDrivenOverlength(lines.get(i), maxLength)) {
                continue;
            }
            count++;
            int line = i + 1;
            CheckIssue issue = createIssue(
                    IssueLevel.MINOR,
                    "STYLE_LONG_LINE",
                    "超长代码行",
                    "第 " + line + " 行长度 " + len + " 字符，超过上限 " + maxLength,
                    context.getCurrentFilePath(), line, line);
            issue.setSuggestion("拆分长表达式/长参数列表为多行，或提取局部变量；统一团队行宽约定");
            issues.add(issue);
        }
    }

    /**
     * 该行超长是否由单个字符串/字符字面量造成：把最长字面量替换为 "" 后若回到限宽内即豁免。
     * 扫描时尊重反斜杠转义，避免把 \" 误当结尾引号。
     */
    private boolean isLiteralDrivenOverlength(String line, int maxLength) {
        if (line == null) {
            return false;
        }
        int longest = 0;
        int i = 0;
        while (i < line.length()) {
            char quote = line.charAt(i);
            if (quote != '"' && quote != '\'') {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < line.length()) {
                char c = line.charAt(j);
                if (c == '\\') {
                    j += 2;
                    continue;
                }
                if (c == quote) {
                    j++;
                    break;
                }
                j++;
            }
            longest = Math.max(longest, j - i);
            i = j;
        }
        return longest > 0 && (line.length() - longest + 2) <= maxLength;
    }

    private void checkTodoComments(CheckContext context, List<CheckIssue> issues) {
        List<String> lines = context.getSourceLines();
        if (lines == null) {
            return;
        }
        int count = 0;
        for (int i = 0; i < lines.size() && count < MAX_PER_RULE_PER_FILE; i++) {
            if (!TODO_PATTERN.matcher(lines.get(i)).find()) {
                continue;
            }
            count++;
            int line = i + 1;
            CheckIssue issue = createIssue(
                    IssueLevel.INFO,
                    "STYLE_TODO_COMMENT",
                    "遗留 TODO/FIXME 注释",
                    "第 " + line + " 行存在未完成事项标记，长期遗留会掩盖待办风险",
                    context.getCurrentFilePath(), line, line);
            issue.setSuggestion("将 TODO/FIXME 转为任务跟踪系统中的工单，注明负责人与期限后删除注释，或尽快完成");
            issues.add(issue);
        }
    }
}
