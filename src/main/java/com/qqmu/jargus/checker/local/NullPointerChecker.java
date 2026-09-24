package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.qqmu.jargus.checker.PostScanChecker;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 空指针检测检查器
 *
 * 基于 AST 进行简单的空指针风险分析：
 * - 方法参数未判空就直接调用方法（NULL_CHECK）
 * - 可能返回 null 的方法调用后直接使用（NULL_CHAIN_CALL）
 *
 * NULL_CHECK 误报治理（R41 建立，R47 两轮扩展）：
 * 1. 框架契约豁免——以下情形的参数实例由框架/容器保证非空，不再登记候选：
 *    a. @ExceptionHandler / @Around / @EventListener 等框架注入注解方法；
 *    b. @GetMapping / @PostMapping 等处理器方法（Spring 绑定参数默认必填，
 *       缺失直接 400，不会以 null 进入方法体；required=false 与 Optional 除外）；
 *    c. 契约类型参数（HttpServletRequest / Model / CheckContext 等框架类型，
 *       以及 JavaParser AST 节点类型——遍历产物恒非空，按 import 识别）；
 *    d. @NonNull / @NotNull 注解修饰的参数（开发者显式契约）；
 *    e. @Override 且父类型为框架契约类型（Filter / Comparator / WebMvcConfigurer 等）
 *       ——接口契约规定 null 入参属调用方违约，不在被调方报险。
 * 2. 调用方链路裁决：doCheck 阶段只登记候选，postScanCheck 阶段通读全项目源码收集调用点，
 *    所有调用点实参均可证非空时抑制。可证非空包括：字面量 / new / 数组创建 / lambda /
 *    方法引用 / 字符串拼接 / 枚举与静态常量（含裸 ALL_CAPS 常量名）/ 恒非空方法返回值
 *    （静态工厂、builder.build、split、now、字符串与 Path 派生方法、MyBatis selectList 等）/
 *    Optional.orElse(可证非空回退) / 有 isEmpty 守卫的 get(整型字面量) /
 *    项目内已证「全返回非空」方法的返回值 / 三目双分支非空与判空分支透传
 *    （x != null ? x : fallback）/ split 数组元素 / foreach、forEach 与流式 lambda
 *    迭代变量（集合元素视为非空）/ 调用前已判空退出或处于 if (v != null) 分支内 /
 *    声明处无初值但在 if/else 各分支均赋可证非空值的局部变量 /
 *    契约或豁免方法的参数 / 调用方方法体开头已自行判空的参数 /
 *    沿调用链向上传递证明（限深 3 层，覆盖 Controller→Service→私有助手→子助手 的实体透传；
 *    自递归与互递归调用点采用协同归纳处理——环上唯一调用点意味着方法实际不可达，
 *    外部调用点仍被独立评估）。
 *    方法引用作迭代实参（filter(this::isCheckerEnabled)）登记为「按定义可证」调用点。
 *    「全返回非空」方法登记采用不动点迭代（方法间返回值证明可能层层依赖）。
 *    调用点按「类名归属」过滤：可解析出接收者类型的调用点只归属同名类的方法，
 *    裸调用（this/继承/静态导入）与无法解析的接收者退回全局池（保守），
 *    避免跨类同名同参数个数方法的调用点互相污染裁决；私有方法进一步收紧——
 *    只有词法位于其所在类（含嵌套类）内部的裸调用点才参与裁决，
 *    消除跨类同名私有方法（如三个类各自的 extractFileName）的调用点池污染。
 *    找不到调用点（反射、SPI、死代码、示例代码）时保持上报，宁可多报不可漏报。
 * 3. 判空守卫识别的健全性（R47）：组合条件中，退出守卫（v == null 则 return/throw）
 *    仅接受 OR 组合、进入守卫（v != null 分支内）仅接受 AND 组合；
 *    三目条件（v == null ? ... : 使用 v）与短路二元守卫
 *    （v != null && v.f() / v == null || v.f()）同样计入已判空变量；
 *    Objects.requireNonNull 接受带错误消息的两参形态；
 *    有界索引循环允许 i - 1 等含循环变量的下标表达式，并兼容 length 边界。
 * 4. 框架回调契约扩展（R47）：@Override 契约识别覆盖 record 实现
 *    （OpenPDF 事件回调多用 record implements PdfPCellEvent/PdfPTableEvent）；
 *    豁免方法的数组参数元素（canvases[0]）按框架契约视为非空。
 *
 * NULL_CHAIN_CALL 误报治理（R47）：
 * - findFirst / findAny 是 Optional 生产者，恒非空，移出可疑名单；
 * - Optional 链上的 .get() 空值抛 NoSuchElementException 而非 NPE，不属本规则
 *   （Optional 类型变量、findFirst 等生产者直挂、isEmpty/isPresent 前置守卫均豁免）；
 * - 静态工厂类（Paths / List / Optional / Files 等）返回值恒非空；
 * - 类型契约：CatchClause.getParameter()、WebClient.get() 等恒非空
 *   （字段类型经跨文件索引解析，覆盖父类声明字段）；
 *   getParameter() 仅对 Servlet 请求类型视为可空；
 * - 有界索引循环（i &lt; coll.size()）内的 coll.get(i)、keySet 遍历内的 map.get(k)、
 *   调用前已有 has / containsKey 同键守卫的 get，均不报。
 *
 * 调用图收集使用与主管线一致的 JAVA_17 语言级别解析器（R47 修复：此前默认级别
 * 解析 record / 模式匹配 / switch 表达式文件会静默失败，导致调用点缺失而误报），
 * 构造方法体内的调用同样计入调用点。
 */
@Component
public class NullPointerChecker extends AbstractLocalChecker implements PostScanChecker {

    /** 已知可能返回 null 的方法名模式（findFirst/findAny 为 Optional 生产者，恒非空，R47 移除） */
    private static final Set<String> POSSIBLE_NULL_METHODS = Set.of(
            "get", "getFirst", "getLast", "peek", "poll",
            "find",
            "getProperty", "getAttribute", "getParameter"
    );

    /** 框架契约：标注这些注解的方法，其参数由框架注入且保证非空 */
    private static final Set<String> FRAMEWORK_INJECTED = Set.of(
            "ExceptionHandler", "Around", "Before", "After", "AfterReturning", "AfterThrowing",
            "EventListener", "TransactionalEventListener", "MessageMapping"
    );

    /** Spring MVC 处理器注解：参数由框架绑定，默认必填（缺失即 400），不会为 null */
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping"
    );

    /** 参数非空注解：开发者显式声明契约，直接信任 */
    private static final Set<String> NON_NULL_PARAM_ANNOTATIONS = Set.of(
            "NonNull", "NotNull", "Nonnull"
    );

    /** 契约参数类型：框架容器语境下传入实例保证非空 */
    private static final Set<String> CONTRACT_PARAM_TYPES = Set.of(
            "CheckContext", "TemplateContext",
            "HttpServletRequest", "HttpServletResponse", "ServletRequest", "ServletResponse",
            "HttpSession", "ServletContext", "FilterChain", "Model", "ModelMap",
            "BindingResult", "Principal", "Locale", "InterceptorRegistry",
            "ResourceHandlerRegistry", "DataSource", "JoinPoint"
    );

    /** JavaParser AST 类型 import 前缀：遍历产物恒非空，此类参数按 import 识别后豁免 */
    private static final String PARSER_PACKAGE_PREFIX = "com.github.javaparser.";

    /** 框架父类型：@Override 实现方法的参数由框架调用契约保证非空 */
    private static final Set<String> FRAMEWORK_SUPERTYPES = Set.of(
            "Filter", "OncePerRequestFilter", "GenericFilterBean", "HandlerInterceptor",
            "WebMvcConfigurer", "LocaleResolver", "CookieLocaleResolver", "AbstractLocaleResolver",
            "Comparator", "Runnable", "Callable",
            "PdfPageEventHelper", "PdfPCellEvent", "PdfPTableEvent", "ApplicationListener",
            "ApplicationRunner", "CommandLineRunner", "InitializingBean", "DisposableBean",
            "BeanPostProcessor", "BeanFactoryPostProcessor", "FactoryBean",
            "Converter", "GenericConverter", "ErrorAttributes",
            "ResponseBodyAdvice", "RequestBodyAdvice", "WebServerFactoryCustomizer",
            "ApplicationContextInitializer", "EnvironmentPostProcessor"
    );

    /**
     * 返回值恒非空的方法名（用于调用点实参裁决）。
     * R47 扩充：字符串派生、Path/Files 派生、JavaParser 访问器、OpenPDF 画布生产者
     * 均失败走异常而非 null。
     * 注意不含裸 "replace"——Map.replace 返回旧值可空。
     */
    private static final Set<String> NON_NULL_RETURNING = Set.of(
            "of", "copyOf", "asList", "requireNonNull", "values", "valueOf", "format", "toString",
            "now", "build", "split", "orElseThrow", "selectList", "selectMaps",
            "toUpperCase", "toLowerCase", "trim", "strip", "substring", "concat", "intern",
            "replaceAll", "replaceFirst", "getBytes",
            "resolve", "normalize", "toAbsolutePath", "relativize",
            "createDirectories", "createDirectory", "createTempDirectory", "createTempFile",
            "createFile",
            "getParameters", "getArguments", "getAnnotations", "getVariables", "getImports",
            "getNameAsString", "getTypeAsString", "asString", "getIdentifier",
            // OpenPDF：PdfWriter 画布惰性创建后缓存返回，契约恒非空
            "getDirectContent", "getDirectContentUnder"
    );

    /** 静态工厂类：其静态方法返回值恒非空（失败走异常而非 null），链式调用无空指针 */
    private static final Set<String> NON_NULL_FACTORY_TYPES = Set.of(
            "Paths", "Path", "Files", "List", "Map", "Set", "Optional",
            "Collections", "Arrays", "Instant", "LocalDate", "LocalDateTime",
            "LocalTime", "UUID", "BigInteger", "BigDecimal"
    );

    /** Optional 生产者方法：其结果 .get() 空值抛 NoSuchElementException 而非 NPE */
    private static final Set<String> OPTIONAL_PRODUCERS = Set.of(
            "findFirst", "findAny", "of", "ofNullable", "empty"
    );

    /** 特定类型上恒非空的方法：类型简单名 -> 方法名集合 */
    private static final Map<String, Set<String>> TYPE_SAFE_CHAINS = Map.of(
            "CatchClause", Set.of("getParameter"),
            "WebClient", Set.of("get", "post", "put", "delete", "patch", "head", "options")
    );

    /** getParameter() 仅在 Servlet 请求类型上才具备可空语义 */
    private static final Set<String> SERVLET_REQUEST_TYPES = Set.of(
            "HttpServletRequest", "ServletRequest"
    );

    /** 单参 lambda 且宿主调用属于这些 API 时，lambda 参数是迭代元素，视为非空 */
    private static final Set<String> ITERATION_APIS = Set.of(
            "forEach", "walk", "removeIf", "ifPresent",
            "anyMatch", "allMatch", "noneMatch", "filter", "map", "flatMap", "peek"
    );

    /** 裸常量名形态（static final 常量与枚举常量的命名惯例），视为恒非空 */
    private static final String CONSTANT_NAME_PATTERN = "[A-Z][A-Z0-9_]*";

    private static final String GD_CANDIDATES = "null_check_candidates";
    private static final String GD_FIELD_TYPES = "null_check_field_types";

    /** 字段类型索引中同名不同类型的歧义标记（不可解析，保守处理） */
    private static final String AMBIGUOUS = "";

    /** 调用链传递证明深度上限（Controller→Service→助手→子助手；环上重入由进行中集合短路） */
    private static final int MAX_PROOF_DEPTH = 6;

    /** 「全返回非空」方法登记的不动点迭代轮数上限（返回值证明可能层层依赖） */
    private static final int MAX_RETURN_ROUNDS = 5;

    /** 一处 NULL_CHECK 候选，等待 postScan 阶段链路裁决 */
    private record Candidate(String filePath, int line, String methodName, String className,
                             int paramIndex, int arity, String paramName, String calledName,
                             boolean privateMethod) {}

    /**
     * 一个调用点：调用方（方法或构造器）+ 实参列表 + 行号 + 接收者类型（null=裸调用/未解析）。
     * args 为 null 标记「方法引用作迭代实参」的调用点——实参由宿主 API 提供迭代元素，按定义可证非空。
     */
    private record CallSite(CallableDeclaration<?> caller, List<Expression> args, int line,
                            String scopeType) {}

    /** 调用链证明上下文：调用点索引 + 项目内已证恒非空返回的方法 + 递归防环进行中集合 */
    private record Proof(Map<String, List<CallSite>> sites, Set<String> nonNullReturns,
                         Set<String> inProgress) {}

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
        registerFieldTypes(context, cu);
        Set<String> parserTypes = importedParserTypes(cu);
        Map<String, String> fieldTypes = fieldTypeIndex(context);

        cu.findAll(MethodDeclaration.class).forEach(method -> {
            if (method.getBody().isEmpty()) return;

            List<Parameter> params = method.getParameters();
            boolean frameworkInjected = hasAnyAnnotation(method, FRAMEWORK_INJECTED);
            boolean mappingHandler = hasAnyAnnotation(method, MAPPING_ANNOTATIONS);
            boolean frameworkOverride = isOverrideOfFrameworkType(method);
            String className = enclosingClassName(method, cu);

            // 收集已进行 null 检查的变量（if 条件与三目条件，R47）
            Set<String> nullCheckedVars = new HashSet<>();
            method.walk(IfStmt.class, ifStmt ->
                    collectNullCheckedVars(ifStmt.getCondition(), nullCheckedVars));
            method.walk(ConditionalExpr.class, ternary ->
                    collectNullCheckedVars(ternary.getCondition(), nullCheckedVars));

            method.walk(MethodCallExpr.class, methodCall -> {
                checkParamDereference(context, issues, method, className, methodCall, params,
                        nullCheckedVars, frameworkInjected, mappingHandler,
                        frameworkOverride, parserTypes);
                checkChainCall(context, issues, method, methodCall, fieldTypes);
            });
        });
    }

    /** 把本文件所有字段（名→类型简单名）写入跨文件共享索引；同名不同类型标记为歧义 */
    private void registerFieldTypes(CheckContext context, CompilationUnit cu) {
        Map<String, String> fieldTypes = fieldTypeIndex(context);
        if (fieldTypes == null) {
            return;
        }
        cu.findAll(FieldDeclaration.class).forEach(field ->
                field.getVariables().forEach(v -> {
                    String type = simpleNameOfType(v.getType().asString());
                    fieldTypes.merge(v.getNameAsString(), type,
                            (oldType, newType) -> oldType.equals(newType) ? oldType : AMBIGUOUS);
                }));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fieldTypeIndex(CheckContext context) {
        if (context.getGlobalData() == null) {
            return null;
        }
        return (Map<String, String>) context.getGlobalData()
                .computeIfAbsent(GD_FIELD_TYPES, k -> new ConcurrentHashMap<String, String>());
    }

    /** 本编译单元 import 的 JavaParser AST 类型简单名集合（遍历产物恒非空，作契约类型豁免） */
    private Set<String> importedParserTypes(Node node) {
        return node.findCompilationUnit()
                .map(cu -> {
                    Set<String> types = new HashSet<>();
                    cu.getImports().forEach(imp -> {
                        String name = imp.getNameAsString();
                        if (name.startsWith(PARSER_PACKAGE_PREFIX)) {
                            types.add(simpleNameOfType(name));
                        }
                    });
                    return types;
                })
                .orElse(Set.of());
    }

    private String enclosingClassName(MethodDeclaration method, CompilationUnit cu) {
        return method.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(cls -> cls.getNameAsString())
                .orElseGet(() -> cu.getPrimaryTypeName().orElse(""));
    }

    /**
     * 参数解引用检查：scope 是本方法参数且未判空、未获契约豁免时登记候选（或直报）
     */
    private void checkParamDereference(CheckContext context, List<CheckIssue> issues,
                                       MethodDeclaration method, String className,
                                       MethodCallExpr methodCall, List<Parameter> params,
                                       Set<String> nullCheckedVars, boolean frameworkInjected,
                                       boolean mappingHandler, boolean frameworkOverride,
                                       Set<String> parserTypes) {
        if (methodCall.getScope().isEmpty()
                || !(methodCall.getScope().get() instanceof NameExpr nameExpr)) {
            return;
        }
        String varName = nameExpr.getNameAsString();
        if (nullCheckedVars.contains(varName) || isShortCircuitGuarded(nameExpr)) {
            return;
        }
        int paramIndex = indexOfParam(params, varName);
        if (paramIndex < 0
                || isParamExempt(params.get(paramIndex), frameworkInjected,
                        mappingHandler, frameworkOverride, parserTypes)) {
            return;
        }
        int line = methodCall.getBegin().map(p -> p.line).orElse(1);
        if (context.getGlobalData() != null) {
            // 登记候选，postScan 阶段通调用链路裁决是否真风险
            @SuppressWarnings("unchecked")
            List<Candidate> candidates = (List<Candidate>) context.getGlobalData()
                    .computeIfAbsent(GD_CANDIDATES, k -> new ArrayList<Candidate>());
            candidates.add(new Candidate(
                    context.getCurrentFilePath(), line,
                    method.getNameAsString(), className, paramIndex,
                    params.size(), varName,
                    methodCall.getNameAsString(), method.isPrivate()));
        } else {
            issues.add(nullCheckIssue(context.getCurrentFilePath(), line,
                    varName, methodCall.getNameAsString()));
        }
    }

    /**
     * 参数契约豁免：框架注入 / 框架父类型 @Override / 非空注解 / 契约类型 /
     * JavaParser AST 类型 / 处理器方法必填参数（required=false 与 Optional 除外）
     */
    private boolean isParamExempt(Parameter param, boolean frameworkInjected,
                                  boolean mappingHandler, boolean frameworkOverride,
                                  Set<String> parserTypes) {
        if (frameworkInjected || frameworkOverride) {
            return true;
        }
        if (param.getAnnotations().stream()
                .anyMatch(a -> NON_NULL_PARAM_ANNOTATIONS.contains(a.getNameAsString()))) {
            return true;
        }
        String typeName = simpleNameOfType(param.getType().asString());
        if (CONTRACT_PARAM_TYPES.contains(typeName) || parserTypes.contains(typeName)) {
            return true;
        }
        if (mappingHandler) {
            boolean nullableBinding = typeName.startsWith("Optional")
                    || param.getAnnotations().stream().anyMatch(a ->
                            a.toString().replace(" ", "").contains("required=false"));
            return !nullableBinding;
        }
        return false;
    }

    /**
     * 短路二元守卫（R47）：解引用位于 && 右操作数且左操作数为 v != null，
     * 或位于 || 右操作数且左操作数为 v == null——求值到达右操作数时 v 必非空。
     * 覆盖 return v != null && !v.isEmpty() 这类同表达式守卫。
     */
    private boolean isShortCircuitGuarded(NameExpr nameExpr) {
        String name = nameExpr.getNameAsString();
        Node child = nameExpr;
        Optional<Node> current = nameExpr.getParentNode();
        while (current.isPresent()) {
            Node node = current.get();
            if (node instanceof BinaryExpr binary && child == binary.getRight()) {
                if (binary.getOperator() == BinaryExpr.Operator.AND
                        && conditionIsNullEquals(binary.getLeft(), name,
                                BinaryExpr.Operator.NOT_EQUALS)) {
                    return true;
                }
                if (binary.getOperator() == BinaryExpr.Operator.OR
                        && conditionIsNullEquals(binary.getLeft(), name,
                                BinaryExpr.Operator.EQUALS)) {
                    return true;
                }
            }
            if (!(node instanceof Expression)) {
                break;
            }
            child = node;
            current = node.getParentNode();
        }
        return false;
    }

    /**
     * 链式调用检查：可能返回 null 的方法后直接调用方法，且不满足任何安全模式时上报
     */
    private void checkChainCall(CheckContext context, List<CheckIssue> issues,
                                MethodDeclaration method, MethodCallExpr methodCall,
                                Map<String, String> fieldTypes) {
        if (methodCall.getScope().isEmpty()
                || !(methodCall.getScope().get() instanceof MethodCallExpr innerCall)) {
            return;
        }
        String innerMethodName = innerCall.getNameAsString();
        if (!POSSIBLE_NULL_METHODS.contains(innerMethodName)
                || isChainSafe(innerCall, innerMethodName, method, fieldTypes)) {
            return;
        }
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

    /**
     * 链式调用安全裁决：
     * 静态工厂 / Optional 生产者链 / 类型契约 / 有界索引循环 /
     * keySet 遍历 / has、containsKey、isPresent、isEmpty 前置守卫
     */
    private boolean isChainSafe(MethodCallExpr innerCall, String innerName,
                                MethodDeclaration method, Map<String, String> fieldTypes) {
        Expression scope = innerCall.getScope().orElse(null);
        if (scope == null) {
            return false;
        }
        if (scope instanceof NameExpr scopeName
                && NON_NULL_FACTORY_TYPES.contains(scopeName.getNameAsString())) {
            return true;
        }
        if ("get".equals(innerName) && scope instanceof MethodCallExpr producer
                && OPTIONAL_PRODUCERS.contains(producer.getNameAsString())) {
            return true;
        }
        if (isTypeSafeChain(innerName, scope, method, fieldTypes)) {
            return true;
        }
        if (!"get".equals(innerName)) {
            return false;
        }
        String scopeText = scope.toString();
        // Optional 守卫与实参无关（.get() 无参），has/containsKey 守卫需同键
        if (conditionGuardBefore(method, lineOf(innerCall),
                condition -> isOptionalGuardText(condition, scopeText))) {
            return true;
        }
        if (innerCall.getArguments().size() == 1) {
            Expression arg = innerCall.getArgument(0);
            String argText = arg.toString();
            return isBoundedLoopGet(method, scopeText, arg)
                    || isKeySetIteratedGet(method, scopeText, arg)
                    || conditionGuardBefore(method, lineOf(innerCall), condition ->
                            condition.contains(scopeText + ".has(" + argText + ")")
                                    || condition.contains(scopeText + ".containsKey(" + argText + ")"));
        }
        return false;
    }

    /** 条件文本是否含 scope 的 isEmpty()/isPresent() 守卫（condition 为宿主回调传入的条件文本，契约非空） */
    private boolean isOptionalGuardText(@NonNull String condition, String scopeText) {
        return condition.contains(scopeText + ".isEmpty()")
                || condition.contains(scopeText + ".isPresent()");
    }

    /** 类型契约裁决：Optional 变量、CatchClause/WebClient 等特定类型、非 Servlet 的 getParameter */
    private boolean isTypeSafeChain(String innerName, Expression scope,
                                    MethodDeclaration method, Map<String, String> fieldTypes) {
        String scopeType = simpleTypeOf(scope, method, fieldTypes);
        if (scopeType == null) {
            return false;
        }
        if (scopeType.startsWith("Optional")) {
            return true;
        }
        if (TYPE_SAFE_CHAINS.getOrDefault(scopeType, Set.of()).contains(innerName)) {
            return true;
        }
        return "getParameter".equals(innerName) && !SERVLET_REQUEST_TYPES.contains(scopeType);
    }

    /**
     * for (int i = 0; i &lt; coll.size(); i++) 内的 coll.get(下标表达式)：
     * 下标含循环变量即视为有界（允许 i、i - 1 等形态），兼容数组 length 边界（R47）
     */
    private boolean isBoundedLoopGet(CallableDeclaration<?> method, String scopeText,
                                     Expression indexArg) {
        Set<String> indexNames = new HashSet<>();
        indexArg.findAll(NameExpr.class).forEach(n -> indexNames.add(n.getNameAsString()));
        if (indexNames.isEmpty()) {
            return false;
        }
        return method.findAll(ForStmt.class).stream().anyMatch(forStmt -> {
            String compare = forStmt.getCompare().map(Object::toString).orElse("");
            if (!compare.contains(scopeText + ".size()")
                    && !compare.contains(scopeText + ".length")) {
                return false;
            }
            return forStmt.getInitialization().stream()
                    .flatMap(init -> init.findAll(VariableDeclarator.class).stream())
                    .anyMatch(v -> indexNames.contains(v.getNameAsString()));
        });
    }

    /** for (K k : map.keySet()) 内的 map.get(k)：键来自键集，取值必然存在 */
    private boolean isKeySetIteratedGet(MethodDeclaration method, String scopeText, Expression keyArg) {
        if (!(keyArg instanceof NameExpr key)) {
            return false;
        }
        return method.findAll(ForEachStmt.class).stream().anyMatch(forEach ->
                forEach.getIterable().toString().contains(scopeText + ".keySet()")
                        && forEach.getVariable().getVariables().stream()
                                .anyMatch(v -> v.getNameAsString().equals(key.getNameAsString())));
    }

    private boolean conditionGuardBefore(CallableDeclaration<?> method, int callLine,
                                         Predicate<String> conditionMatcher) {
        return method.findAll(IfStmt.class).stream().anyMatch(ifStmt ->
                lineOf(ifStmt) < callLine
                        && conditionMatcher.test(ifStmt.getCondition().toString()));
    }

    /**
     * 扫描收尾：通读全项目源码收集调用点，对候选逐一裁决。
     * 抑制条件（同时满足）：按类名归属后存在调用点；所有归属调用点该参数位实参均可证非空。
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

        Map<String, String> fieldTypes = (Map<String, String>) globalData.get(GD_FIELD_TYPES);
        Map<String, List<CallSite>> callSites = new HashMap<>();
        Set<String> nonNullReturns = new HashSet<>();
        collectCallGraph(sourceRoot, callSites, nonNullReturns, fieldTypes);
        Proof proof = new Proof(callSites, nonNullReturns, new HashSet<>());

        for (Candidate c : candidates) {
            String key = c.methodName() + "|" + c.arity();
            List<CallSite> attributed = attributedSites(callSites.get(key), c.className(),
                    c.privateMethod());
            boolean suppress = !attributed.isEmpty()
                    && attributed.stream().allMatch(s ->
                            isSiteArgProvable(s, c.paramIndex(), proof, MAX_PROOF_DEPTH));
            if (!suppress) {
                issues.add(nullCheckIssue(c.filePath(), c.line(), c.paramName(), c.calledName()));
            }
        }
        globalData.remove(GD_FIELD_TYPES);
        return issues;
    }

    /**
     * 调用点归属过滤：接收者类型可解析且与候选类不同名的调用点归属其他同名方法，予以剔除；
     * 裸调用（this/继承）与无法解析的接收者保守地参与所有同名方法的裁决。
     * 私有方法例外（R47）：private 方法只能被其所在类（含嵌套类）词法范围内的调用点到达，
     * 来自其他类的裸调用点必属同名私有方法，剔除以消除跨类调用点池污染。
     */
    private List<CallSite> attributedSites(List<CallSite> sites, String className,
                                           boolean privateMethod) {
        if (sites == null) {
            return List.of();
        }
        return sites.stream()
                .filter(s -> {
                    if (s.scopeType() != null) {
                        return s.scopeType().equals(className);
                    }
                    return !privateMethod || callerInsideClass(s.caller(), className);
                })
                .toList();
    }

    /** 调用方是否词法位于目标类内部（含其嵌套类/记录/枚举）——私有方法的可达范围 */
    private boolean callerInsideClass(CallableDeclaration<?> caller, String className) {
        Node node = caller;
        while (node != null) {
            if ((node instanceof ClassOrInterfaceDeclaration cid
                        && cid.getNameAsString().equals(className))
                    || (node instanceof RecordDeclaration rec
                        && rec.getNameAsString().equals(className))
                    || (node instanceof EnumDeclaration en
                        && en.getNameAsString().equals(className))) {
                return true;
            }
            node = node.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean isSiteArgProvable(CallSite site, int paramIndex, Proof proof, int depth) {
        if (site.args() == null) {
            // 方法引用作迭代实参（filter(this::isCheckerEnabled)）：实参为宿主 API
            // 提供的迭代元素，按定义可证非空
            return true;
        }
        Expression arg = site.args().size() > paramIndex ? site.args().get(paramIndex) : null;
        return arg != null
                && isProvablyNonNull(arg, site.caller(), site.line(), proof, depth);
    }

    /**
     * 遍历源码收集各方法（同名同参数个数）的所有调用点实参与接收者类型，
     * 并以不动点迭代登记「全返回非空」的项目内方法（R47：方法间返回值证明可能
     * 层层依赖，如 parseDependencies → parseMavenDependencies，单趟会漏登记）。
     * 解析器与主管线一致（JAVA_17），构造方法体内的调用同样计入。
     */
    private void collectCallGraph(Path sourceRoot, Map<String, List<CallSite>> callSites,
                                  Set<String> nonNullReturns, Map<String, String> fieldTypes) {
        JavaParser parser = new JavaParser(
                new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        List<MethodDeclaration> methods = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            List<Path> javaFiles = walk
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .toList();
            for (Path file : javaFiles) {
                CompilationUnit cu;
                try {
                    ParseResult<CompilationUnit> result = parser.parse(file);
                    if (!result.isSuccessful() || result.getResult().isEmpty()) {
                        continue;
                    }
                    cu = result.getResult().get();
                } catch (Exception e) {
                    continue;
                }
                List<MethodDeclaration> declared = cu.findAll(MethodDeclaration.class);
                methods.addAll(declared);
                List<CallableDeclaration<?>> callables = new ArrayList<>(declared);
                callables.addAll(cu.findAll(ConstructorDeclaration.class));
                for (CallableDeclaration<?> caller : callables) {
                    collectSitesOf(caller, callSites, fieldTypes);
                }
            }
        } catch (Exception e) {
            // 遍历失败时保持空图，候选全报（保守）
        }
        boolean changed = true;
        int rounds = 0;
        while (changed && rounds++ < MAX_RETURN_ROUNDS) {
            changed = false;
            Proof returnsProof = new Proof(Map.of(), nonNullReturns, new HashSet<>());
            for (MethodDeclaration method : methods) {
                String key = method.getNameAsString() + "|" + method.getParameters().size();
                if (!nonNullReturns.contains(key) && allReturnsProvable(method, returnsProof)) {
                    nonNullReturns.add(key);
                    changed = true;
                }
            }
        }
    }

    /** 收集单个方法/构造器内的调用点与迭代方法引用点 */
    private void collectSitesOf(CallableDeclaration<?> caller, Map<String, List<CallSite>> callSites,
                                Map<String, String> fieldTypes) {
        caller.walk(MethodCallExpr.class, call -> {
            String key = call.getNameAsString() + "|" + call.getArguments().size();
            callSites.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new CallSite(caller, call.getArguments(), lineOf(call),
                            resolveScopeType(call.getScope().orElse(null), caller, fieldTypes)));
        });
        caller.walk(MethodReferenceExpr.class, ref -> {
            if (ref.getParentNode().orElse(null) instanceof MethodCallExpr host
                    && ITERATION_APIS.contains(host.getNameAsString())
                    && host.getArguments().stream().anyMatch(a -> a == ref)) {
                String key = ref.getIdentifier() + "|1";
                callSites.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new CallSite(caller, null, lineOf(ref), referenceScopeType(ref)));
            }
        });
    }

    /**
     * 方法是否「全返回非空」：有方法体、至少一条 return（lambda 内的 return 不计）、
     * 每条返回表达式均可证非空。用于裁决 createFromZip 等项目内工厂方法的返回值。
     */
    private boolean allReturnsProvable(MethodDeclaration method, Proof proof) {
        if (method.getBody().isEmpty()) {
            return false;
        }
        List<ReturnStmt> returns = method.findAll(ReturnStmt.class).stream()
                .filter(r -> r.findAncestor(CallableDeclaration.class).orElse(null) == method)
                .toList();
        if (returns.isEmpty()) {
            return false;
        }
        return returns.stream().allMatch(r -> r.getExpression()
                .map(expr -> isProvablyNonNull(expr, method, lineOf(r), proof, 0))
                .orElse(false));
    }

    /** 方法引用接收者类型：Type::method 取类型简单名；expr::method 无法静态解析返回 null */
    private String referenceScopeType(MethodReferenceExpr ref) {
        return ref.getScope() instanceof TypeExpr typeExpr
                ? simpleNameOfType(typeExpr.getType().asString())
                : null;
    }

    /**
     * 解析调用接收者类型简单名：参数 → 局部变量（var 除外）→ 所在类字段 →
     * 跨文件字段索引 → 大写名视为类名（静态调用）；解析失败返回 null（退回全局池）
     */
    private String resolveScopeType(Expression scope, CallableDeclaration<?> caller,
                                    Map<String, String> fieldTypes) {
        if (scope == null || scope instanceof ThisExpr) {
            return null;
        }
        if (!(scope instanceof NameExpr nameExpr)) {
            return null;
        }
        String name = nameExpr.getNameAsString();
        for (Parameter param : caller.getParameters()) {
            if (param.getNameAsString().equals(name)) {
                return simpleNameOfType(param.getType().asString());
            }
        }
        Optional<String> localType = caller.findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getNameAsString().equals(name))
                .map(v -> v.getType().asString())
                .filter(t -> !"var".equals(t))
                .map(this::simpleNameOfType)
                .findFirst();
        if (localType.isPresent()) {
            return localType.get();
        }
        String fieldType = declaredFieldType(caller, name);
        if (fieldType == null && fieldTypes != null) {
            String indexed = fieldTypes.get(name);
            fieldType = indexed == null || indexed.isEmpty() ? null : indexed;
        }
        if (fieldType != null) {
            return fieldType;
        }
        if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        return null;
    }

    /**
     * 实参是否可证非空（分派器）：自证形态直接判定，复合形态递归裁决。
     * R47 拆分自单体方法以控制圈复杂度。
     */
    private boolean isProvablyNonNull(Expression arg, CallableDeclaration<?> caller, int callLine,
                                      Proof proof, int depth) {
        if (arg instanceof NullLiteralExpr) {
            return false;
        }
        if (isSelfEvidentlyNonNull(arg)) {
            return true;
        }
        if (arg instanceof CastExpr cast) {
            return isProvablyNonNull(cast.getExpression(), caller, callLine, proof, depth);
        }
        if (arg instanceof EnclosedExpr enclosed) {
            return isProvablyNonNull(enclosed.getInner(), caller, callLine, proof, depth);
        }
        if (arg instanceof ConditionalExpr ternary) {
            return isProvablyTernary(ternary, caller, callLine, proof, depth);
        }
        if (arg instanceof MethodCallExpr call) {
            return isNonNullCall(call, caller, callLine, proof, depth);
        }
        if (arg instanceof ArrayAccessExpr access) {
            return isSplitElement(access, caller) || isExemptParamArrayElement(access, caller);
        }
        if (arg instanceof NameExpr nameExpr) {
            return isNonNullVariable(nameExpr, caller, callLine, proof, depth);
        }
        return false;
    }

    /**
     * 自证非空形态：字面量（非 null）/ new / 数组创建 / lambda / 方法引用 / this /
     * 字符串与算术拼接 / 大写 scope 字段访问（枚举与静态常量）/ 裸 ALL_CAPS 常量名（R47）
     */
    private boolean isSelfEvidentlyNonNull(Expression arg) {
        if (arg instanceof LiteralExpr || arg instanceof ObjectCreationExpr
                || arg instanceof ArrayCreationExpr || arg instanceof LambdaExpr
                || arg instanceof MethodReferenceExpr || arg instanceof ThisExpr) {
            return true;
        }
        if (arg instanceof BinaryExpr binary) {
            // 字符串拼接恒非空；数值加法为原始类型，若装箱拆箱为 null 会在求值处先抛
            return binary.getOperator() == BinaryExpr.Operator.PLUS;
        }
        if (arg instanceof FieldAccessExpr field) {
            // 大写 scope 视为类名访问（枚举常量/静态常量），恒非空
            String scopeText = field.getScope().toString();
            return !scopeText.isEmpty() && Character.isUpperCase(scopeText.charAt(0));
        }
        return arg instanceof NameExpr nameExpr
                && nameExpr.getNameAsString().matches(CONSTANT_NAME_PATTERN);
    }

    /**
     * 三目实参：双分支均可证非空；或「判空分支透传」形态（R47）——
     * x != null ? x : fallback 与 x == null ? fallback : x，仅需回退分支可证非空
     */
    private boolean isProvablyTernary(ConditionalExpr ternary, CallableDeclaration<?> caller,
                                      int callLine, Proof proof, int depth) {
        Expression thenExpr = ternary.getThenExpr();
        Expression elseExpr = ternary.getElseExpr();
        if (isProvablyNonNull(thenExpr, caller, callLine, proof, depth)
                && isProvablyNonNull(elseExpr, caller, callLine, proof, depth)) {
            return true;
        }
        if (!(ternary.getCondition() instanceof BinaryExpr binary)
                || !(binary.getRight() instanceof NullLiteralExpr)) {
            return false;
        }
        String leftText = binary.getLeft().toString();
        if (binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS
                && thenExpr.toString().equals(leftText)) {
            return isProvablyNonNull(elseExpr, caller, callLine, proof, depth);
        }
        if (binary.getOperator() == BinaryExpr.Operator.EQUALS
                && elseExpr.toString().equals(leftText)) {
            return isProvablyNonNull(thenExpr, caller, callLine, proof, depth);
        }
        return false;
    }

    /**
     * 方法调用返回值恒非空：已知非空方法名 / 静态工厂类 / orElse(可证非空回退)（R47）/
     * get(整型字面量) 且调用前有 isEmpty 守卫（R47）/ 项目内已证全返回非空的方法（R47）
     */
    private boolean isNonNullCall(MethodCallExpr call, CallableDeclaration<?> caller, int callLine,
                                  Proof proof, int depth) {
        String name = call.getNameAsString();
        if (NON_NULL_RETURNING.contains(name)) {
            return true;
        }
        if (call.getScope().orElse(null) instanceof NameExpr factory
                && NON_NULL_FACTORY_TYPES.contains(factory.getNameAsString())) {
            return true;
        }
        if ("orElse".equals(name) && call.getArguments().size() == 1
                && isProvablyNonNull(call.getArgument(0), caller, callLine, proof, depth)) {
            return true;
        }
        if ("get".equals(name) && call.getArguments().size() == 1
                && call.getArgument(0) instanceof IntegerLiteralExpr) {
            String scopeText = call.getScope().map(Object::toString).orElse("");
            return !scopeText.isEmpty()
                    && conditionGuardBefore(caller, callLine,
                            condition -> isOptionalGuardText(condition, scopeText)
                                    || condition.contains(scopeText + ".isEmpty()"));
        }
        return proof.nonNullReturns().contains(name + "|" + call.getArguments().size());
    }

    /** 数组元素访问仅当数组来自 split(...)（其元素恒非空）时可证 */
    private boolean isSplitElement(ArrayAccessExpr access, CallableDeclaration<?> caller) {
        Expression array = access.getName();
        if (array instanceof MethodCallExpr call) {
            return "split".equals(call.getNameAsString());
        }
        if (array instanceof NameExpr name) {
            return caller.findAll(VariableDeclarator.class).stream()
                    .filter(v -> v.getNameAsString().equals(name.getNameAsString()))
                    .map(VariableDeclarator::getInitializer)
                    .flatMap(Optional::stream)
                    .anyMatch(init -> init instanceof MethodCallExpr call
                            && "split".equals(call.getNameAsString()));
        }
        return false;
    }

    /**
     * 数组元素实参：数组本体是框架契约豁免方法的参数时按契约视为元素非空（R47），
     * 如 OpenPDF cellLayout 回调的 canvases[0]（画布数组由框架填充，恒有效）
     */
    private boolean isExemptParamArrayElement(ArrayAccessExpr access,
                                              CallableDeclaration<?> caller) {
        if (!(access.getName() instanceof NameExpr arrName)) {
            return false;
        }
        int idx = indexOfParam(caller.getParameters(), arrName.getNameAsString());
        if (idx < 0) {
            return false;
        }
        return isParamExempt(caller.getParameters().get(idx),
                hasAnyAnnotation(caller, FRAMEWORK_INJECTED),
                hasAnyAnnotation(caller, MAPPING_ANNOTATIONS),
                isOverrideOfFrameworkType(caller), importedParserTypes(caller));
    }

    /**
     * 变量是否可证非空：
     * foreach / 迭代 lambda 变量（集合元素视为非空）、三目判空分支内的变量（R47）、
     * 调用行前所有赋值来源均可证非空的局部变量（含无初值分支赋值形态，R47）、
     * 契约或豁免方法的参数、调用方已自行判空的参数（R47）、
     * 沿调用链向上传递证明的参数、调用前已判空的变量
     */
    private boolean isNonNullVariable(NameExpr nameExpr, CallableDeclaration<?> caller,
                                      int callLine, Proof proof, int depth) {
        String name = nameExpr.getNameAsString();
        if (isForEachVar(caller, name) || isIterationLambdaParam(nameExpr)
                || isTernaryBranchGuarded(nameExpr)) {
            return true;
        }
        if (isProvablyAssignedLocal(caller, name, callLine, proof, depth)) {
            return true;
        }
        int paramIndex = indexOfParam(caller.getParameters(), name);
        if (paramIndex >= 0) {
            Parameter param = caller.getParameters().get(paramIndex);
            if (isParamExempt(param, hasAnyAnnotation(caller, FRAMEWORK_INJECTED),
                    hasAnyAnnotation(caller, MAPPING_ANNOTATIONS),
                    isOverrideOfFrameworkType(caller), importedParserTypes(caller))) {
                return true;
            }
            // 调用方在方法体开头已对参数判空退出（if (p == null) throw/return）
            if (guardedBefore(caller, name, callLine)) {
                return true;
            }
            return depth > 0 && callerParamProvable(caller, paramIndex, proof, depth - 1);
        }
        return guardedBefore(caller, name, callLine);
    }

    /**
     * 变量位于三目「已判空」分支内（R47）：
     * x == null ? fallback : 使用 x（else 分支）或 x != null ? 使用 x : fallback（then 分支）
     */
    private boolean isTernaryBranchGuarded(NameExpr nameExpr) {
        String name = nameExpr.getNameAsString();
        Node child = nameExpr;
        Optional<Node> current = nameExpr.getParentNode();
        while (current.isPresent()) {
            if (current.get() instanceof ConditionalExpr ternary) {
                Expression condition = ternary.getCondition();
                if (child == ternary.getElseExpr()
                        && conditionIsNullEquals(condition, name, BinaryExpr.Operator.EQUALS)) {
                    return true;
                }
                if (child == ternary.getThenExpr()
                        && conditionIsNullEquals(condition, name, BinaryExpr.Operator.NOT_EQUALS)) {
                    return true;
                }
            }
            child = current.get();
            current = current.get().getParentNode();
        }
        return false;
    }

    /**
     * 局部变量：调用行前的全部赋值来源（声明初值 + 改赋值）均可证非空。
     * 覆盖「声明无初值、在 if/else 各分支赋值」的惯用形态（R47）。
     * 递归防环：进行中集合命中时悲观返回 false。
     */
    private boolean isProvablyAssignedLocal(CallableDeclaration<?> caller, String name,
                                            int callLine, Proof proof, int depth) {
        String cycleKey = "var|" + System.identityHashCode(caller) + "|" + name;
        if (!proof.inProgress().add(cycleKey)) {
            return false;
        }
        try {
            return assignedLocalProvable(caller, name, callLine, proof, depth);
        } finally {
            proof.inProgress().remove(cycleKey);
        }
    }

    private boolean assignedLocalProvable(CallableDeclaration<?> caller, String name, int callLine,
                                          Proof proof, int depth) {
        Optional<VariableDeclarator> local = caller.findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getNameAsString().equals(name))
                .findFirst();
        if (local.isEmpty()) {
            return false;
        }
        List<Expression> values = new ArrayList<>();
        local.get().getInitializer().ifPresent(values::add);
        caller.findAll(AssignExpr.class).stream()
                .filter(a -> a.getTarget() instanceof NameExpr target
                        && target.getNameAsString().equals(name)
                        && lineOf(a) < callLine)
                .forEach(a -> values.add(a.getValue()));
        return !values.isEmpty()
                && values.stream().allMatch(v -> isProvablyNonNull(v, caller, callLine, proof, depth));
    }

    /**
     * 传递证明：调用方该方法参数位在其全部归属调用点上均可证非空（限深递归防环）。
     * 环上重入乐观视为可证（R47 协同归纳）：若某调用点仅因递归环而不可判，
     * 意味着该方法实际只被自身调用（不可达），不构成真实风险来源；
     * 环外调用点仍会被独立评估，不会因此放行。
     */
    private boolean callerParamProvable(CallableDeclaration<?> caller, int paramIndex,
                                        Proof proof, int depth) {
        String key = caller.getNameAsString() + "|" + caller.getParameters().size();
        String className = caller.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(cls -> cls.getNameAsString()).orElse(null);
        boolean privateMethod = caller instanceof MethodDeclaration md && md.isPrivate();
        List<CallSite> sites = attributedSites(proof.sites().get(key), className, privateMethod);
        if (sites.isEmpty()) {
            return false;
        }
        String cycleKey = "param|" + key + "|" + paramIndex + "|" + className;
        if (!proof.inProgress().add(cycleKey)) {
            return true;
        }
        try {
            return sites.stream().allMatch(s -> isSiteArgProvable(s, paramIndex, proof, depth));
        } finally {
            proof.inProgress().remove(cycleKey);
        }
    }

    private boolean isForEachVar(CallableDeclaration<?> caller, String name) {
        return caller.findAll(ForEachStmt.class).stream()
                .anyMatch(forEach -> forEach.getVariable().getVariables().stream()
                        .anyMatch(v -> v.getNameAsString().equals(name)));
    }

    /**
     * 单参 lambda 且宿主调用是 forEach/walk/流式中间与终端操作时，
     * lambda 参数为迭代元素，视为非空。双参 lambda（Map.forEach 的 value 可空）不适用。
     */
    private boolean isIterationLambdaParam(NameExpr nameExpr) {
        String name = nameExpr.getNameAsString();
        Optional<Node> current = nameExpr.getParentNode();
        while (current.isPresent()) {
            if (current.get() instanceof LambdaExpr lambda
                    && lambda.getParameters().stream()
                            .anyMatch(p -> p.getNameAsString().equals(name))) {
                return lambda.getParameters().size() == 1
                        && lambda.getParentNode().orElse(null) instanceof MethodCallExpr host
                        && ITERATION_APIS.contains(host.getNameAsString());
            }
            current = current.get().getParentNode();
        }
        return false;
    }

    /**
     * 调用方方法内、调用行之前是否存在对变量的有效判空：
     * if (v == null) { return/throw/continue/break/改赋值 }、if (v != null) { ...调用... }
     * 或 Objects.requireNonNull(v)
     */
    private boolean guardedBefore(CallableDeclaration<?> caller, String varName, int callLine) {
        if (bodyOf(caller).isEmpty()) {
            return false;
        }
        return hasNullExitGuard(caller, varName, callLine)
                || hasNonNullEntryGuard(caller, varName, callLine)
                || hasRequireNonNull(caller, varName, callLine);
    }

    private Optional<BlockStmt> bodyOf(CallableDeclaration<?> caller) {
        if (caller instanceof MethodDeclaration method) {
            return method.getBody();
        }
        if (caller instanceof ConstructorDeclaration constructor) {
            return Optional.of(constructor.getBody());
        }
        return Optional.empty();
    }

    /** if (v == null) 后 then 分支内退出（return/throw/continue/break）或改赋值 */
    private boolean hasNullExitGuard(CallableDeclaration<?> caller, String varName, int callLine) {
        boolean[] guarded = {false};
        caller.findAll(IfStmt.class).forEach(ifStmt -> {
            if (lineOf(ifStmt) >= callLine
                    || !conditionIsNullEquals(ifStmt.getCondition(), varName,
                            BinaryExpr.Operator.EQUALS)) {
                return;
            }
            ifStmt.getThenStmt().walk(node -> {
                if (node instanceof ReturnStmt || node instanceof ThrowStmt
                        || node instanceof ContinueStmt || node instanceof BreakStmt) {
                    guarded[0] = true;
                }
                if (node instanceof AssignExpr assign
                        && assign.getTarget() instanceof NameExpr target
                        && target.getNameAsString().equals(varName)) {
                    guarded[0] = true;
                }
            });
        });
        return guarded[0];
    }

    /** if (v != null) { ... }：调用行落在非空分支内 */
    private boolean hasNonNullEntryGuard(CallableDeclaration<?> caller, String varName,
                                         int callLine) {
        return caller.findAll(IfStmt.class).stream().anyMatch(ifStmt ->
                conditionIsNullEquals(ifStmt.getCondition(), varName,
                        BinaryExpr.Operator.NOT_EQUALS)
                        && lineOf(ifStmt) < callLine
                        && callLine <= endLineOf(ifStmt.getThenStmt()));
    }

    /** 调用行之前存在 Objects.requireNonNull(v)（含带错误消息的两参形态，R47） */
    private boolean hasRequireNonNull(CallableDeclaration<?> caller, String varName, int callLine) {
        return caller.findAll(MethodCallExpr.class).stream().anyMatch(m ->
                "requireNonNull".equals(m.getNameAsString())
                        && !m.getArguments().isEmpty()
                        && m.getArguments().get(0) instanceof NameExpr n
                        && n.getNameAsString().equals(varName)
                        && lineOf(m) < callLine);
    }

    /**
     * 条件是否为 v == null / null == v（EQUALS）或 v != null / null != v（NOT_EQUALS）形式。
     * 组合条件的健全性规则（R47 修复）：退出守卫（== null 则 return/throw）只在 OR
     * 组合下传递——任一析取项命中即退出；进入守卫（!= null 分支内）只在 AND 组合下
     * 传递——全部合取项成立才保证非空。旧版对 EQUALS 误接受 AND 组合
     * （if (a == null && b == null) return 并不能单独保护 a）。
     */
    private boolean conditionIsNullEquals(Expression condition, String varName,
                                          BinaryExpr.Operator operator) {
        if (condition instanceof UnaryExpr unary
                && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            // !(v == null) 等价 v != null；!(v != null) 等价 v == null
            BinaryExpr.Operator flipped = operator == BinaryExpr.Operator.EQUALS
                    ? BinaryExpr.Operator.NOT_EQUALS : BinaryExpr.Operator.EQUALS;
            return conditionIsNullEquals(unary.getExpression(), varName, flipped);
        }
        if (condition instanceof BinaryExpr binary) {
            if (binary.getOperator() == operator) {
                return (isName(binary.getLeft(), varName) && binary.getRight() instanceof NullLiteralExpr)
                        || (isName(binary.getRight(), varName) && binary.getLeft() instanceof NullLiteralExpr);
            }
            BinaryExpr.Operator branchOp = operator == BinaryExpr.Operator.EQUALS
                    ? BinaryExpr.Operator.OR : BinaryExpr.Operator.AND;
            if (binary.getOperator() == branchOp) {
                return conditionIsNullEquals(binary.getLeft(), varName, operator)
                        || conditionIsNullEquals(binary.getRight(), varName, operator);
            }
        }
        return false;
    }

    private boolean isName(Expression expr, String varName) {
        return expr instanceof NameExpr n && n.getNameAsString().equals(varName);
    }

    private boolean hasAnyAnnotation(CallableDeclaration<?> method, Set<String> annotationNames) {
        return method.getAnnotations().stream()
                .anyMatch(a -> annotationNames.contains(a.getNameAsString()));
    }

    /**
     * @Override 且所在类的父类/接口命中框架契约类型清单。
     * 所在类含 record（R47：OpenPDF 事件回调多用 record 实现 PdfPCellEvent，
     * record 声明不是 ClassOrInterfaceDeclaration，需单独取其实现接口）。
     */
    private boolean isOverrideOfFrameworkType(CallableDeclaration<?> method) {
        if (method.getAnnotations().stream()
                .noneMatch(a -> "Override".equals(a.getNameAsString()))) {
            return false;
        }
        Set<String> supertypes = new HashSet<>();
        method.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(cls -> {
            cls.getExtendedTypes().forEach(t -> supertypes.add(t.getNameAsString()));
            cls.getImplementedTypes().forEach(t -> supertypes.add(t.getNameAsString()));
        });
        method.findAncestor(RecordDeclaration.class).ifPresent(rec ->
                rec.getImplementedTypes().forEach(t -> supertypes.add(t.getNameAsString())));
        return supertypes.stream().anyMatch(FRAMEWORK_SUPERTYPES::contains);
    }

    private int indexOfParam(List<Parameter> params, String name) {
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).getNameAsString().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 推断表达式变量的类型简单名：
     * 方法参数 → lambda 参数（findAll(X.class).forEach 惯用法）→ 局部/foreach 变量 →
     * 所在类字段 → 跨文件字段索引
     */
    private String simpleTypeOf(Expression scope, MethodDeclaration method,
                                Map<String, String> fieldTypes) {
        if (!(scope instanceof NameExpr nameExpr)) {
            return null;
        }
        String name = nameExpr.getNameAsString();
        String type = method.getParameters().stream()
                .filter(p -> p.getNameAsString().equals(name))
                .map(p -> p.getType().asString())
                .findFirst()
                .orElse(null);
        if (type == null) {
            type = lambdaParamType(nameExpr);
        }
        if (type == null) {
            type = method.findAll(VariableDeclarator.class).stream()
                    .filter(v -> v.getNameAsString().equals(name))
                    .map(v -> v.getType().asString())
                    .filter(t -> !"var".equals(t))
                    .findFirst()
                    .orElse(null);
        }
        if (type == null) {
            type = declaredFieldType(method, name);
        }
        if (type == null && fieldTypes != null) {
            String indexed = fieldTypes.get(name);
            type = indexed == null || indexed.isEmpty() ? null : indexed;
        }
        return type == null ? null : simpleNameOfType(type);
    }

    /** 所在类（含嵌套类）声明的字段类型 */
    private String declaredFieldType(Node context, String name) {
        return context.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(cls -> cls.getFields().stream()
                        .flatMap(f -> f.getVariables().stream())
                        .filter(v -> v.getNameAsString().equals(name))
                        .map(v -> v.getType().asString())
                        .filter(t -> !"var".equals(t))
                        .findFirst()
                        .orElse(null))
                .orElse(null);
    }

    /** 识别 JavaParser 惯用法 findAll(X.class).forEach(x -> ...) / walk(X.class, x -> ...) 的 lambda 参数类型 */
    private String lambdaParamType(NameExpr nameExpr) {
        String name = nameExpr.getNameAsString();
        Optional<Node> current = nameExpr.getParentNode();
        while (current.isPresent()) {
            if (current.get() instanceof LambdaExpr lambda
                    && lambda.getParameters().stream()
                            .anyMatch(p -> p.getNameAsString().equals(name))) {
                return classLiteralOfEnclosingCall(lambda);
            }
            current = current.get().getParentNode();
        }
        return null;
    }

    private String classLiteralOfEnclosingCall(LambdaExpr lambda) {
        if (!(lambda.getParentNode().orElse(null) instanceof MethodCallExpr host)) {
            return null;
        }
        Optional<ClassExpr> classExpr = host.getArguments().stream()
                .filter(ClassExpr.class::isInstance)
                .map(ClassExpr.class::cast)
                .findFirst();
        if (classExpr.isEmpty() && host.getScope().orElse(null) instanceof MethodCallExpr scopeCall) {
            classExpr = scopeCall.getArguments().stream()
                    .filter(ClassExpr.class::isInstance)
                    .map(ClassExpr.class::cast)
                    .findFirst();
        }
        return classExpr.map(ce -> ce.getType().asString()).orElse(null);
    }

    /** 去除泛型、数组与包名前缀，取类型简单名 */
    private String simpleNameOfType(String type) {
        String t = type;
        int generic = t.indexOf('<');
        if (generic >= 0) {
            t = t.substring(0, generic);
        }
        t = t.replace("[]", "");
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private int lineOf(Node node) {
        return node.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);
    }

    private int endLineOf(Node node) {
        return node.getEnd().map(p -> p.line).orElse(Integer.MAX_VALUE);
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
