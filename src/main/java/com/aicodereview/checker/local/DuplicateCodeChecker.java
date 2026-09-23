package com.aicodereview.checker.local;

import com.aicodereview.checker.CheckContext;
import com.aicodereview.checker.CheckIssue;
import com.aicodereview.checker.CheckerType;
import com.aicodereview.checker.IssueLevel;
import com.aicodereview.checker.PostScanChecker;
import com.aicodereview.service.CheckerParamsService;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 重复代码检测器（CPD 思路）
 *
 * doCheck 阶段：对每个文件的 Token 流做归一化（字面量→LIT、标识符保留原文、跳过注释），
 * 以滑动窗口（默认 60 个有效 Token）计算 MD5 指纹，收集到 globalData。
 * package/import 行、构造方法、简单 getter/setter 属样板，不参与指纹（误报高发区）。
 * postScanCheck 阶段：找出在 ≥2 处出现的指纹，合并连续窗口链后报告重复代码块。
 *
 * 参数（checker_config.params）：{"minTokens":60}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicateCodeChecker extends AbstractLocalChecker implements PostScanChecker {

    /** 默认最小重复 Token 数 */
    private static final int DEFAULT_MIN_TOKENS = 60;
    /** 单文件大小上限（超过跳过长度指纹收集） */
    private static final long MAX_FILE_BYTES = 200 * 1024L;
    /** 全局窗口指纹总量上限（防止超大仓库内存膨胀） */
    private static final int MAX_TOTAL_WINDOWS = 500_000;
    /** 单次扫描最多报告的重复块数量 */
    private static final int MAX_REPORTS = 50;
    /** 原始重复窗口对数量上限（防爆炸） */
    private static final int MAX_RAW_PAIRS = 100_000;

    private static final String GD_FINGERPRINTS = "dup_fingerprints";
    private static final String GD_WINDOW_COUNT = "dup_window_count";

    private final CheckerParamsService checkerParamsService;

    /** 一处窗口指纹的出现位置 */
    private record Occurrence(String file, int tokenIdx, int startLine, int endLine) {}

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.DUPLICATE_CODE;
    }

    @Override
    public int getPriority() {
        return 65;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        try {
            if (cu.getTokenRange().isEmpty() || context.getCurrentFileAbsolutePath() == null) {
                return;
            }
            // 超大文件跳过，控制耗时与内存
            if (Files.size(context.getCurrentFileAbsolutePath()) > MAX_FILE_BYTES) {
                log.debug("文件 {} 超过 {}KB，跳过重复代码指纹收集",
                        context.getCurrentFilePath(), MAX_FILE_BYTES / 1024);
                return;
            }
            Map<String, Object> globalData = context.getGlobalData();
            if (globalData == null) {
                return;
            }
            int windowCount = globalData.get(GD_WINDOW_COUNT) instanceof Integer c ? c : 0;
            if (windowCount >= MAX_TOTAL_WINDOWS) {
                return;
            }

            int minTokens = checkerParamsService.getInt(
                    CheckerType.DUPLICATE_CODE.getCode(), "minTokens", DEFAULT_MIN_TOKENS);

            // package 声明与 import 列表不参与重复判定：
            // 企业规范通常禁止通配符导入，各文件 import 写法天然雷同，属于误报高发区
            Set<Integer> excludedLines = new HashSet<>();
            cu.getPackageDeclaration().ifPresent(pd ->
                    pd.getTokenRange().ifPresent(r -> addCoveredLines(r, excludedLines)));
            cu.getImports().forEach(im ->
                    im.getTokenRange().ifPresent(r -> addCoveredLines(r, excludedLines)));
            // getter/setter 与构造方法属语言样板，逐字雷同是必然结果而非复制粘贴，同样排除
            excludeBoilerplate(cu, excludedLines);

            // 1. 收集有效 Token 并归一化
            List<String> normalized = new ArrayList<>();
            List<Integer> lines = new ArrayList<>();
            for (JavaToken token : cu.getTokenRange().get()) {
                if (token.getCategory() == JavaToken.Category.COMMENT) {
                    continue;
                }
                int tokenLine = token.getRange().map(r -> r.begin.line).orElse(-1);
                if (tokenLine > 0 && excludedLines.contains(tokenLine)) {
                    continue;
                }
                String text = token.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                // 仅归一化字面量（复制粘贴后只改常量/字符串的仍能查出）；
                // 标识符保留原文参与指纹（PMD CPD 默认行为）：若把标识符抹成占位符，
                // 样板代码的"关键字+标点骨架"（如 @Entity getter/setter vs @RestController CRUD）
                // 会哈希相同，产生大量语义无关的误报。代价：改名克隆不再检出。
                String norm;
                if (token.getCategory() == JavaToken.Category.LITERAL) {
                    norm = "LIT";
                } else {
                    norm = text;
                }
                normalized.add(norm);
                lines.add(tokenLine);
            }

            int n = normalized.size();
            if (n < minTokens) {
                return;
            }

            // 2. 滑动窗口指纹
            Map<String, List<Occurrence>> fingerprints =
                    (Map<String, List<Occurrence>>) globalData.computeIfAbsent(GD_FINGERPRINTS,
                            k -> new HashMap<>());
            MessageDigest md = MessageDigest.getInstance("MD5");
            String filePath = context.getCurrentFilePath();
            StringBuilder window = new StringBuilder();
            for (int i = 0; i + minTokens <= n; i++) {
                window.setLength(0);
                for (int j = i; j < i + minTokens; j++) {
                    window.append(normalized.get(j)).append(' ');
                }
                String hash = toHex(md.digest(window.toString().getBytes(StandardCharsets.UTF_8)));
                fingerprints.computeIfAbsent(hash, k -> new ArrayList<>())
                        .add(new Occurrence(filePath, i, lines.get(i), lines.get(i + minTokens - 1)));
                windowCount++;
                if (windowCount >= MAX_TOTAL_WINDOWS) {
                    log.warn("重复代码指纹窗口数达到上限 {}，本文件后续窗口不再收集", MAX_TOTAL_WINDOWS);
                    break;
                }
            }
            globalData.put(GD_WINDOW_COUNT, windowCount);
        } catch (Exception e) {
            log.debug("文件 {} 重复代码指纹收集失败: {}", context.getCurrentFilePath(), e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<CheckIssue> postScanCheck(CheckContext templateContext) {
        List<CheckIssue> issues = new ArrayList<>();
        Map<String, Object> globalData = templateContext.getGlobalData();
        if (globalData == null) {
            return issues;
        }
        Object raw = globalData.remove(GD_FINGERPRINTS);
        globalData.remove(GD_WINDOW_COUNT);
        if (!(raw instanceof Map)) {
            return issues;
        }
        Map<String, List<Occurrence>> fingerprints = (Map<String, List<Occurrence>>) raw;

        int minTokens = checkerParamsService.getInt(
                CheckerType.DUPLICATE_CODE.getCode(), "minTokens", DEFAULT_MIN_TOKENS);

        // 1. 收集所有重复窗口对（同一 hash 出现 ≥2 处）
        List<CandidatePair> rawPairs = new ArrayList<>();
        for (List<Occurrence> occ : fingerprints.values()) {
            if (occ.size() < 2) {
                continue;
            }
            occ.sort(Comparator.comparing((Occurrence o) -> o.file()).thenComparingInt(Occurrence::tokenIdx));
            for (int i = 0; i < occ.size() && rawPairs.size() < MAX_RAW_PAIRS; i++) {
                for (int j = i + 1; j < occ.size() && rawPairs.size() < MAX_RAW_PAIRS; j++) {
                    Occurrence a = occ.get(i);
                    Occurrence b = occ.get(j);
                    // 同一文件内窗口重叠 → 是同一处代码的滑动窗口，跳过
                    if (a.file().equals(b.file()) && Math.abs(a.tokenIdx() - b.tokenIdx()) < minTokens) {
                        continue;
                    }
                    // 统一顺序，保证 (fileA,idxA,fileB,idxB) 稳定
                    Occurrence first = a.file().compareTo(b.file()) <= 0 ? a : b;
                    Occurrence second = first == a ? b : a;
                    rawPairs.add(new CandidatePair(first, second));
                }
            }
        }

        // 2. 按位置排序后做连续窗口链去重：若 (aIdx-1, bIdx-1) 也是重复窗口对，
        //    说明当前窗口只是上一个窗口的滑动延续，不单独报告
        rawPairs.sort(Comparator.comparing((CandidatePair p) -> p.a().file())
                .thenComparingInt(p -> p.a().tokenIdx())
                .thenComparing(p -> p.b().file())
                .thenComparingInt(p -> p.b().tokenIdx()));
        Set<String> continuationKeys = new HashSet<>();
        List<CandidatePair> candidates = new ArrayList<>();
        for (CandidatePair pair : rawPairs) {
            Occurrence first = pair.a();
            Occurrence second = pair.b();
            String sig = first.file() + "#" + first.tokenIdx() + "|"
                    + second.file() + "#" + second.tokenIdx();
            boolean isContinuation = continuationKeys.contains(sig);
            // 无论是否报告，都登记下一个滑动位置，保证长链只报第一个窗口
            continuationKeys.add(first.file() + "#" + (first.tokenIdx() + 1) + "|"
                    + second.file() + "#" + (second.tokenIdx() + 1));
            if (!isContinuation) {
                candidates.add(pair);
                if (candidates.size() >= MAX_REPORTS) {
                    break;
                }
            }
        }

        for (CandidatePair pair : candidates) {
            Occurrence a = pair.a();
            Occurrence b = pair.b();
            int lines = Math.max(1, a.endLine() - a.startLine() + 1);
            String description = "检测到约 " + lines + " 行（≥" + minTokens
                    + " 个有效 Token）的重复代码块：\n"
                    + "  位置一：" + a.file() + ":" + a.startLine() + "-" + a.endLine() + "\n"
                    + "  位置二：" + b.file() + ":" + b.startLine() + "-" + b.endLine()
                    + "\n重复代码会增加维护成本，建议提取公共方法或抽象基类。";
            CheckIssue issue = createIssue(
                    IssueLevel.MAJOR,
                    "DUP_CODE_BLOCK",
                    "重复代码块",
                    description,
                    a.file(),
                    a.startLine(),
                    a.endLine()
            );
            issue.setCodeSnippet(readSnippet(templateContext, a.file(), a.startLine(), a.endLine()));
            issue.setSuggestion("将重复逻辑提取为公共方法、工具类或抽象基类，两处改为复用同一实现");
            issues.add(issue);
        }
        if (!issues.isEmpty()) {
            log.info("重复代码检测发现 {} 处重复块", issues.size());
        }
        return issues;
    }

    private record CandidatePair(Occurrence a, Occurrence b) {}

    /**
     * 样板代码不参与重复判定：全部构造方法 + 简单 getter/setter。
     * 这类代码逐字相同是 JavaBean/依赖注入的必然形态（实体与 VO 的访问器块完全一致很常见），
     * 报成"重复代码"只会产生噪音；真复制粘贴的业务方法不受影响。
     */
    private void excludeBoilerplate(CompilationUnit cu, Set<Integer> target) {
        cu.findAll(ConstructorDeclaration.class).forEach(cd ->
                cd.getTokenRange().ifPresent(r -> addCoveredLines(r, target)));
        cu.findAll(MethodDeclaration.class).forEach(md -> {
            if (isTrivialAccessor(md)) {
                md.getTokenRange().ifPresent(r -> addCoveredLines(r, target));
            }
        });
    }

    /**
     * 简单访问器判定：get/is 前缀无参单 return；set 前缀单参单赋值（或赋值 + return this 的流式写法）。
     * 抽象/接口方法（无方法体）与含业务逻辑的同名方法不算样板，仍参与重复判定。
     */
    private boolean isTrivialAccessor(MethodDeclaration md) {
        String name = md.getNameAsString();
        boolean getterShape = (name.startsWith("get") && name.length() > 3)
                || (name.startsWith("is") && name.length() > 2);
        boolean setterShape = name.startsWith("set") && name.length() > 3;
        if (!getterShape && !setterShape || md.getBody().isEmpty()) {
            return false;
        }
        List<Statement> stmts = md.getBody().get().getStatements();
        if (getterShape) {
            return md.getParameters().isEmpty() && stmts.size() == 1
                    && stmts.get(0) instanceof ReturnStmt;
        }
        if (md.getParameters().size() != 1) {
            return false;
        }
        if (stmts.size() == 1) {
            return stmts.get(0) instanceof ExpressionStmt
                    && ((ExpressionStmt) stmts.get(0)).getExpression() instanceof AssignExpr;
        }
        // 流式 setter：this.x = x; return this;
        if (stmts.size() == 2) {
            boolean assign = stmts.get(0) instanceof ExpressionStmt
                    && ((ExpressionStmt) stmts.get(0)).getExpression() instanceof AssignExpr;
            boolean retThis = stmts.get(1) instanceof ReturnStmt rs
                    && "this".equals(rs.getExpression().toString());
            return assign && retThis;
        }
        return false;
    }

    /** 把一个 AST 节点 TokenRange 覆盖到的行号全部记入排除集合（import 偶有跨行写法） */
    private void addCoveredLines(TokenRange range, Set<Integer> target) {
        Optional<Range> beginRange = range.getBegin().getRange();
        Optional<Range> endRange = range.getEnd().getRange();
        if (beginRange.isEmpty() || endRange.isEmpty()) {
            return;
        }
        for (int ln = beginRange.get().begin.line; ln <= endRange.get().end.line; ln++) {
            target.add(ln);
        }
    }

    /**
     * 读取重复块源码片段（best-effort，失败返回 null）
     */
    private String readSnippet(CheckContext templateContext, String file, int startLine, int endLine) {
        try {
            if (templateContext.getSourceRoot() == null) {
                return null;
            }
            List<String> all = Files.readAllLines(
                    templateContext.getSourceRoot().resolve(file), StandardCharsets.UTF_8);
            int from = Math.max(0, startLine - 1);
            int to = Math.min(all.size(), Math.min(endLine, startLine + 14));
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                sb.append(all.get(i)).append('\n');
            }
            if (endLine > to) {
                sb.append("...");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
