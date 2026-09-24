package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.qqmu.jargus.checker.PostScanChecker;
import com.qqmu.jargus.service.CheckerParamsService;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
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
 * doCheck 阶段：对每个文件的 Token 流取原文（跳过注释；字面量与标识符均不归一化，
 * verbatim 口径——传值不同的同名调用不算重复），以滑动窗口（默认 100 个有效 Token，
 * 对齐 PMD CPD 的 Java 默认）计算 MD5 指纹，收集到 globalData。
 * package/import 行、构造方法、简单 getter/setter、均匀数据链（连续同接收者同方法名调用序列表）、
 * 均匀参数表（Map.ofEntries 等单表达式目录字面量）、条件分派调用（判断分支内且同文件
 * 存在异参调用）属样板/数据声明/条件巧合，不参与指纹（误报高发区）。
 * postScanCheck 阶段：找出在 ≥2 处出现的指纹，合并连续窗口链后报告重复代码块；
 * 仅当匹配窗口含语句级控制流（if/for/while/do/switch/try）才报告——纯直线路径的
 * 初始化/setter/赋值序列逐字相同也只是样板，不是被复制的逻辑（R48 用户裁定）。
 *
 * 参数（checker_config.params）：{"minTokens":100}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicateCodeChecker extends AbstractLocalChecker implements PostScanChecker {

    /** 默认最小重复 Token 数（对齐 PMD CPD 对 Java 的默认阈值；Java 语法噪杂，60 约等于 4 行，误报高发） */
    private static final int DEFAULT_MIN_TOKENS = 100;
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

    /** 一处窗口指纹的出现位置；hasControl = 窗口行区间内含语句级控制流 */
    private record Occurrence(String file, int tokenIdx, int startLine, int endLine, boolean hasControl) {}

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
            if (globalData == null || currentWindowCount(globalData) >= MAX_TOTAL_WINDOWS) {
                return;
            }

            int minTokens = checkerParamsService.getInt(
                    CheckerType.DUPLICATE_CODE.getCode(), "minTokens", DEFAULT_MIN_TOKENS);

            Set<Integer> excludedLines = collectExcludedLines(cu);
            TokenBag bag = collectTokenBag(cu, excludedLines);
            if (bag.texts().size() < minTokens) {
                return;
            }

            // 语句级控制流行区间：仅含控制流的匹配窗口才报告（见 postScanCheck 判定，R48）
            List<int[]> controlRanges = controlFlowRanges(cu);
            int windowCount = collectWindowFingerprints(
                    context, globalData, bag, controlRanges, minTokens);
            globalData.put(GD_WINDOW_COUNT, windowCount);
        } catch (Exception e) {
            log.debug("文件 {} 重复代码指纹收集失败: {}", context.getCurrentFilePath(), e.getMessage());
        }
    }

    /** 全局已累计的指纹窗口数（跨文件预算） */
    private int currentWindowCount(@NonNull Map<String, Object> globalData) {
        return globalData.get(GD_WINDOW_COUNT) instanceof Integer c ? c : 0;
    }

    /**
     * 汇总不参与重复判定的行：package/import、语言样板、均匀数据链/参数表、条件分派调用。
     * package 声明与 import 列表不参与重复判定：
     * 企业规范通常禁止通配符导入，各文件 import 写法天然雷同，属于误报高发区
     */
    private Set<Integer> collectExcludedLines(CompilationUnit cu) {
        Set<Integer> excludedLines = new HashSet<>();
        cu.getPackageDeclaration().ifPresent(pd ->
                pd.getTokenRange().ifPresent(r -> addCoveredLines(r, excludedLines)));
        cu.getImports().forEach(im ->
                im.getTokenRange().ifPresent(r -> addCoveredLines(r, excludedLines)));
        // getter/setter 与构造方法属语言样板，逐字雷同是必然结果而非复制粘贴，同样排除
        excludeBoilerplate(cu, excludedLines);
        // 均匀数据链（连续 ≥3 条同接收者同方法名调用，如 map.put(...) 序列表）是数据声明而非逻辑，
        // 其自相似滑动窗口会在每个偏移量上自匹配，产生成串误报（R48）
        excludeUniformChains(cu, excludedLines);
        // 均匀参数表（Map.ofEntries(Map.entry(...), ...) 等单表达式数据表）同属数据声明（R48）
        excludeUniformArgTables(cu, excludedLines);
        // 判断分支内、且同文件其他条件下存在异参调用的方法调用属条件分派：
        // 相同 (方法, 实参) 在不同分支命中只是条件覆盖的巧合，不算克隆（R48 用户裁定）
        excludeConditionalDispatch(cu, excludedLines);
        return excludedLines;
    }

    /** 参与指纹的逐字 Token 文本与其行号 */
    private record TokenBag(List<String> texts, List<Integer> lines) {}

    /** 收集参与指纹的逐字 Token（注释、排除行、空白 Token 跳过） */
    private TokenBag collectTokenBag(CompilationUnit cu, Set<Integer> excludedLines) {
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
            // 字面量与标识符均保留原文参与指纹（PMD CPD 默认 verbatim 口径）：
            // 集合添加/方法调用"方法名同而传值不同"（如逐 severity 建表的 addCell 链）
            // 在字面量归一化下会哈希相同、成串互配误报，业务裁定传值不同即不算重复（R48）；
            // 标识符同样保留，避免样板"关键字+标点骨架"互配。代价：改常量/改名的克隆不检出。
            normalized.add(text);
            lines.add(tokenLine);
        }
        return new TokenBag(normalized, lines);
    }

    /** 滑动窗口指纹收集，返回累计窗口数（达到跨文件上限即停） */
    @SuppressWarnings("unchecked")
    private int collectWindowFingerprints(CheckContext context, Map<String, Object> globalData,
                                          TokenBag bag, List<int[]> controlRanges, int minTokens)
            throws Exception {
        Map<String, List<Occurrence>> fingerprints =
                (Map<String, List<Occurrence>>) globalData.computeIfAbsent(GD_FINGERPRINTS,
                        k -> new HashMap<>());
        MessageDigest md = MessageDigest.getInstance("MD5");
        String filePath = context.getCurrentFilePath();
        List<String> normalized = bag.texts();
        List<Integer> lines = bag.lines();
        int n = normalized.size();
        StringBuilder window = new StringBuilder();
        int windowCount = currentWindowCount(globalData);
        for (int i = 0; i + minTokens <= n; i++) {
            window.setLength(0);
            for (int j = i; j < i + minTokens; j++) {
                window.append(normalized.get(j)).append(' ');
            }
            String hash = toHex(md.digest(window.toString().getBytes(StandardCharsets.UTF_8)));
            int winStart = lines.get(i);
            int winEnd = lines.get(i + minTokens - 1);
            fingerprints.computeIfAbsent(hash, k -> new ArrayList<>())
                    .add(new Occurrence(filePath, i, winStart, winEnd,
                            overlapsControl(controlRanges, winStart, winEnd)));
            windowCount++;
            if (windowCount >= MAX_TOTAL_WINDOWS) {
                log.warn("重复代码指纹窗口数达到上限 {}，本文件后续窗口不再收集", MAX_TOTAL_WINDOWS);
                break;
            }
        }
        return windowCount;
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

        List<CandidatePair> candidates =
                dedupeContinuations(collectRawPairs(fingerprints, minTokens));

        for (CandidatePair pair : candidates) {
            issues.add(buildDupIssue(pair, templateContext, minTokens));
        }
        if (!issues.isEmpty()) {
            log.info("重复代码检测发现 {} 处重复块", issues.size());
        }
        return issues;
    }

    /** 1. 收集所有重复窗口对（同一 hash 出现 ≥2 处） */
    private List<CandidatePair> collectRawPairs(@NonNull Map<String, List<Occurrence>> fingerprints,
                                                int minTokens) {
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
                    // 纯直线路径（无 if/for/while/do/switch/try）的匹配窗口属初始化/赋值样板：
                    // setter 链、几何量计算序列等逐字相同也只是样板而非被复制的逻辑，不报（R48 用户裁定）
                    if (!a.hasControl() || !b.hasControl()) {
                        continue;
                    }
                    // 统一顺序，保证 (fileA,idxA,fileB,idxB) 稳定
                    Occurrence first = a.file().compareTo(b.file()) <= 0 ? a : b;
                    Occurrence second = first == a ? b : a;
                    rawPairs.add(new CandidatePair(first, second));
                }
            }
        }
        return rawPairs;
    }

    /**
     * 2. 按位置排序后做连续窗口链去重：若 (aIdx-1, bIdx-1) 也是重复窗口对，
     *    说明当前窗口只是上一个窗口的滑动延续，不单独报告
     */
    private List<CandidatePair> dedupeContinuations(List<CandidatePair> rawPairs) {
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
        return candidates;
    }

    /** 3. 候选对转问题条目（描述含双位置与行数估计） */
    private CheckIssue buildDupIssue(CandidatePair pair, CheckContext templateContext, int minTokens) {
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
        return issue;
    }

    private record CandidatePair(Occurrence a, Occurrence b) {}

    /**
     * 均匀数据链排除：同一语句块内连续 ≥3 条"同接收者 + 同方法名"的调用表达式语句
     * （如 SUGGESTIONS.put(...)、list.add(...) 的序列表）视为数据声明而非逻辑。
     * 这类结构的 Token 骨架完全自相似，滑动窗口指纹会在每个偏移量上自匹配，
     * 报出一串"自己和自己重复"的块，属误报高发区（R48）。
     */
    private void excludeUniformChains(CompilationUnit cu, Set<Integer> target) {
        cu.findAll(BlockStmt.class).forEach(block -> {
            List<Statement> stmts = block.getStatements();
            int runStart = 0;
            String runKey = null;
            for (int i = 0; i <= stmts.size(); i++) {
                String key = null;
                if (i < stmts.size() && stmts.get(i) instanceof ExpressionStmt es
                        && es.getExpression() instanceof MethodCallExpr mc
                        && mc.getScope().isPresent()) {
                    key = mc.getScope().get().toString() + "." + mc.getNameAsString();
                }
                if (key != null && key.equals(runKey)) {
                    continue;
                }
                if (runKey != null && i - runStart >= 3) {
                    for (Statement st : stmts.subList(runStart, i)) {
                        st.getTokenRange().ifPresent(r -> addCoveredLines(r, target));
                    }
                }
                runStart = i;
                runKey = key;
            }
        });
    }

    /**
     * 均匀参数表排除：一次调用的全部实参（≥3 个）均为同接收者同方法名的子调用
     * （如 Map.ofEntries(Map.entry("A",1), Map.entry("B",2), ...)、List.of 风格的目录字面量），
     * 整个调用视为数据表声明并排除。这类结构不在 BlockStmt 语句序列中，
     * excludeUniformChains 覆盖不到，同样会在表内每个偏移量上自匹配（R48：TechnicalDebtCatalog）。
     */
    private void excludeUniformArgTables(CompilationUnit cu, Set<Integer> target) {
        cu.findAll(MethodCallExpr.class).forEach(mc -> {
            List<Expression> args = mc.getArguments();
            if (args.size() < 3) {
                return;
            }
            String uniformKey = null;
            for (Expression arg : args) {
                if (!(arg instanceof MethodCallExpr amc) || amc.getScope().isEmpty()) {
                    return;
                }
                String key = amc.getScope().get().toString() + "." + amc.getNameAsString();
                if (uniformKey == null) {
                    uniformKey = key;
                } else if (!uniformKey.equals(key)) {
                    return;
                }
            }
            mc.getTokenRange().ifPresent(r -> addCoveredLines(r, target));
        });
    }

    /**
     * 条件分派排除：方法调用位于判断分支（if/else 体、switch case、三目）内，
     * 且同一方法在本文件存在 ≥2 种不同实参组合的调用时，该调用的 Token 不参与指纹。
     * 此类"同方法同值"在不同分支的重复出现是条件覆盖的巧合（数据分派），
     * 而非复制粘贴；真克隆若整块搬抄，其周围结构仍会匹配（R48 用户裁定）。
     */
    private void excludeConditionalDispatch(CompilationUnit cu, Set<Integer> target) {
        List<MethodCallExpr> calls = cu.findAll(MethodCallExpr.class);
        Map<String, Set<String>> argSignatures = new HashMap<>();
        for (MethodCallExpr mc : calls) {
            argSignatures.computeIfAbsent(callKeyOf(mc), k -> new HashSet<>())
                    .add(mc.getArguments().toString());
        }
        for (MethodCallExpr mc : calls) {
            Set<String> sigs = argSignatures.get(callKeyOf(mc));
            if (sigs != null && sigs.size() >= 2 && isUnderConditionalBranch(mc)) {
                mc.getTokenRange().ifPresent(r -> addCoveredLines(r, target));
            }
        }
    }

    /**
     * 语句级控制流节点（if/for/foreach/while/do/switch/try）的行区间集合。
     * 三目、&&、|| 等表达式级分支不算：出现在初始化器里时属数据形态而非逻辑骨架。
     */
    private List<int[]> controlFlowRanges(CompilationUnit cu) {
        List<int[]> ranges = new ArrayList<>();
        cu.findAll(Statement.class).forEach(st -> {
            if (st instanceof IfStmt || st instanceof ForStmt || st instanceof ForEachStmt
                    || st instanceof WhileStmt || st instanceof DoStmt
                    || st instanceof SwitchStmt || st instanceof TryStmt) {
                st.getBegin().ifPresent(b ->
                        st.getEnd().ifPresent(e -> ranges.add(new int[]{b.line, e.line})));
            }
        });
        return ranges;
    }

    private boolean overlapsControl(List<int[]> ranges, int start, int end) {
        for (int[] r : ranges) {
            if (r[0] <= end && r[1] >= start) {
                return true;
            }
        }
        return false;
    }

    private String callKeyOf(MethodCallExpr mc) {
        return mc.getScope().map(Object::toString).orElse("") + "." + mc.getNameAsString();
    }

    /** 是否位于判断分支体内（if 的条件表达式本身不算分支体） */
    private boolean isUnderConditionalBranch(MethodCallExpr mc) {
        Optional<IfStmt> ifAncestor = mc.findAncestor(IfStmt.class);
        if (ifAncestor.isPresent()
                && !ifAncestor.get().getCondition().containsWithinRange(mc)) {
            return true;
        }
        return mc.findAncestor(SwitchEntry.class).isPresent()
                || mc.findAncestor(ConditionalExpr.class).isPresent();
    }

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
        boolean getterShape = isGetterShape(name);
        boolean setterShape = isSetterShape(name);
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
        return isSingleAssignSetter(stmts) || isFluentSetter(stmts);
    }

    /** getter 形态：get/is 前缀且前缀后还有名字 */
    private boolean isGetterShape(String name) {
        return (name.startsWith("get") && name.length() > 3)
                || (name.startsWith("is") && name.length() > 2);
    }

    /** setter 形态：set 前缀且前缀后还有名字 */
    private boolean isSetterShape(String name) {
        return name.startsWith("set") && name.length() > 3;
    }

    /** 单语句赋值 setter：方法体仅一条赋值表达式语句 */
    private boolean isSingleAssignSetter(@NonNull List<Statement> stmts) {
        return stmts.size() == 1 && isAssignStmt(stmts.get(0));
    }

    /** 流式 setter：this.x = x; return this; 两条语句 */
    private boolean isFluentSetter(@NonNull List<Statement> stmts) {
        return stmts.size() == 2 && isAssignStmt(stmts.get(0))
                && stmts.get(1) instanceof ReturnStmt rs
                && "this".equals(rs.getExpression().toString());
    }

    /** 单条赋值表达式语句 */
    private boolean isAssignStmt(Statement stmt) {
        return stmt instanceof ExpressionStmt es && es.getExpression() instanceof AssignExpr;
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
