package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 安全漏洞检测器（本地 SAST）
 *
 * 基于 JavaParser AST 的启发式安全规则，覆盖 OWASP 常见风险：
 * 硬编码密钥、SQL 注入、命令注入、不安全反序列化、弱加密、不安全随机数、
 * 路径穿越、XXE、SSRF。
 *
 * 规则以保守匹配为原则（要求变量拼接 / 特定 API 组合），误报可通过忽略规则抑制。
 */
@Component
public class SecurityChecker extends AbstractLocalChecker {

    /** 敏感命名模式 */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|apikey|api_key|accesskey|access_key|" +
            "token|credential|privatekey|private_key)");
    /** 明显不是真实密钥的占位值（含 __xxx__ 双下划线哨兵：连通性探针等场景的假凭据约定值） */
    private static final Pattern SECRET_PLACEHOLDER = Pattern.compile(
            "(?i)^\\s*$|\\$\\{|^__\\w+__$|^(null|none|empty|undefined|example|sample|placeholder|" +
            "your[-_]?|changeme|todo|test|xxx+|\\*+|<[^>]*>|\\{\\{).*");
    /** 公开端点 URL（如 OAuth token 地址）不是秘密值 */
    private static final Pattern PUBLIC_URL = Pattern.compile("(?i)^https?://");
    /** 但 URL 里内嵌 user:pass@ 凭据仍是泄密，照常上报 */
    private static final Pattern URL_WITH_CREDENTIALS = Pattern.compile("(?i)^https?://[^/]*@");
    /** 标识名自称 URL/端点语义——URL 豁免仅对它生效（webhookSecret 等名字装 URL 值的照报，Slack webhook URL 本身就是密钥） */
    private static final Pattern URL_NAME = Pattern.compile("(?i)(url|uri|endpoint)");
    /** MD5/SHA-1 用作内容指纹/去重的语境（类名或方法名指明用途），不做安全承诺 */
    private static final Pattern FINGERPRINT_CONTEXT = Pattern.compile(
            "(?i)(fingerprint|checksum|dedup|duplicate|cpd|simhash)");
    /** SQL 关键字 */
    private static final Pattern SQL_KEYWORD = Pattern.compile(
            "(?i)\\b(select|insert\\s+into|update|delete\\s+from|from|where|union|drop\\s+table|" +
            "order\\s+by|group\\s+by)\\b");
    /** 安全语境随机数 */
    private static final Pattern SECURITY_CONTEXT = Pattern.compile(
            "(?i)(token|secret|password|session|salt|nonce|otp|captcha|verifycode|smscode)");
    /** 路径穿越污点参数名 */
    private static final Pattern PATH_NAME = Pattern.compile(
            "(?i)(name|path|file|dir|filename|folder|user|input|param|location|uri)");

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.SECURITY;
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        checkHardcodedSecrets(context, cu, issues);
        checkSqlInjection(context, cu, issues);
        checkCommandInjection(context, cu, issues);
        checkInsecureDeserialization(context, cu, issues);
        checkWeakCrypto(context, cu, issues);
        checkInsecureRandom(context, cu, issues);
        checkPathTraversal(context, cu, issues);
        checkXxe(context, cu, issues);
        checkSsrf(context, cu, issues);
    }

    // ---------------------------------------------------------------- 规则 1：硬编码密钥

    private void checkHardcodedSecrets(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        // 1a. 字段/局部变量声明：String password = "xxx"
        cu.findAll(VariableDeclarator.class).forEach(vd -> {
            if (!isStringType(vd.getTypeAsString())) return;
            if (!SECRET_NAME.matcher(vd.getNameAsString()).find()) return;
            vd.getInitializer().filter(StringLiteralExpr.class::isInstance)
                    .map(StringLiteralExpr.class::cast)
                    .ifPresent(lit -> reportSecret(context, lit, vd.getNameAsString(),
                            "变量 '" + vd.getNameAsString() + "'", issues));
        });
        // 1b. 赋值：this.password = "xxx"
        cu.findAll(AssignExpr.class).forEach(assign -> {
            String target = assign.getTarget().toString();
            int dot = target.lastIndexOf('.');
            String simpleName = dot >= 0 ? target.substring(dot + 1) : target;
            if (!SECRET_NAME.matcher(simpleName).find()) return;
            if (assign.getValue() instanceof StringLiteralExpr lit) {
                reportSecret(context, lit, simpleName, "变量 '" + simpleName + "'", issues);
            }
        });
        // 1c. Map.put("password", "xxx") / setProperty / addHeader 等
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            String name = call.getNameAsString();
            if (!name.equals("put") && !name.equals("setProperty") && !name.equals("set")
                    && !name.equals("addHeader") && !name.equals("header") && !name.equals("addParameter")) {
                return;
            }
            if (call.getArguments().size() < 2) return;
            if (!(call.getArgument(0) instanceof StringLiteralExpr keyLit)) return;
            if (!SECRET_NAME.matcher(keyLit.getValue()).find()) return;
            if (call.getArgument(1) instanceof StringLiteralExpr valLit) {
                reportSecret(context, valLit, keyLit.getValue(), "键 '" + keyLit.getValue() + "'", issues);
            }
        });
    }

    private void reportSecret(CheckContext context, StringLiteralExpr lit, String identName, String where,
                              List<CheckIssue> issues) {
        String value = lit.getValue();
        if (value == null || value.length() < 6 || SECRET_PLACEHOLDER.matcher(value).find()) {
            return;
        }
        // R44 收紧：标识名自称 URL/端点、且值是不含内嵌凭据的公开 URL（如 OAuth token 地址）才豁免；
        // 名字不带 url/uri/endpoint 的（如 webhookSecret）照报——此类 URL 本身可能就是密钥
        if (PUBLIC_URL.matcher(value).find() && !URL_WITH_CREDENTIALS.matcher(value).find()
                && URL_NAME.matcher(identName).find()) {
            return;
        }
        // R44 收紧：值与标识名共享实义词 → Cookie 名/头名等公开标识常量（真密钥不会复读自己的变量名）
        if (echoesIdentifier(identName, value)) {
            return;
        }
        int line = lineOf(lit);
        CheckIssue issue = createIssue(
                IssueLevel.BLOCKER,
                "SEC_HARDCODED_SECRET",
                "硬编码敏感信息",
                where + " 被赋予硬编码字符串常量（长度 " + value.length() + "），疑似密码/密钥/令牌明文写入源码",
                context.getCurrentFilePath(), line, line);
        issue.setSuggestion("将敏感信息移出代码，改用环境变量、配置中心或加密存储（本系统对数据库密码/API Key 均做 AES 加密）");
        issues.add(issue);
    }

    /**
     * 值与标识名共享 ≥4 字符的实义词即视为「名称复读」：
     * 真实密钥不会长得像自己的变量名，这类字面量几乎都是 Cookie 名/请求头名等公开标识常量
     * （如 TOKEN_COOKIE = "jargus_token"）。
     * 值只有单个实义词时不豁免——password="password" 这种整词弱口令必须照报。
     */
    private boolean echoesIdentifier(String identName, String value) {
        Set<String> nameTokens = significantTokens(identName);
        if (nameTokens.isEmpty()) {
            return false;
        }
        Set<String> valueTokens = significantTokens(value);
        if (valueTokens.size() < 2) {
            return false;
        }
        for (String token : valueTokens) {
            if (nameTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 按驼峰与非字母数字边界拆词，取小写后长度 ≥4 的实义词 */
    private Set<String> significantTokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) {
            return tokens;
        }
        for (String part : text.split("[^A-Za-z0-9]+")) {
            for (String word : part.split("(?<=[a-z0-9])(?=[A-Z])")) {
                if (word.length() >= 4) {
                    tokens.add(word.toLowerCase(Locale.ROOT));
                }
            }
        }
        return tokens;
    }

    // ---------------------------------------------------------------- 规则 2：SQL 注入

    private void checkSqlInjection(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            String name = call.getNameAsString();
            boolean sqlApi = name.equals("execute") || name.equals("executeQuery") || name.equals("executeUpdate")
                    || name.equals("executeLargeUpdate") || name.equals("query") || name.equals("queryForObject")
                    || name.equals("queryForList") || name.equals("queryForMap") || name.equals("update")
                    || name.equals("batchUpdate") || name.equals("createQuery") || name.equals("createNativeQuery");
            if (!sqlApi) return;
            for (Expression arg : call.getArguments()) {
                if (isSqlConcat(arg)) {
                    int line = lineOf(call);
                    CheckIssue issue = createIssue(
                            IssueLevel.BLOCKER,
                            "SEC_SQL_INJECTION",
                            "SQL 注入风险",
                            "'" + name + "' 的 SQL 语句由字符串拼接变量构成，攻击者可注入恶意 SQL",
                            context.getCurrentFilePath(), line, line);
                    issue.setSuggestion("改用 PreparedStatement 参数化查询（? 占位符）或 MyBatis #{} 参数绑定，禁止拼接用户输入");
                    issues.add(issue);
                    return;
                }
            }
        });
    }

    /** 是否为「含 SQL 关键字的字符串 + 变量」拼接 */
    private boolean isSqlConcat(Expression expr) {
        if (!(expr instanceof BinaryExpr binary) || binary.getOperator() != BinaryExpr.Operator.PLUS) {
            return false;
        }
        boolean hasLiteralSql = false;
        boolean hasVariable = false;
        for (Expression child : binary.findAll(Expression.class)) {
            if (child instanceof StringLiteralExpr lit) {
                if (SQL_KEYWORD.matcher(lit.getValue()).find()) {
                    hasLiteralSql = true;
                }
            } else if (child instanceof NameExpr || child instanceof MethodCallExpr
                    || child instanceof FieldAccessExpr || child instanceof EnclosedExpr) {
                hasVariable = true;
            }
        }
        return hasLiteralSql && hasVariable;
    }

    // ---------------------------------------------------------------- 规则 3：命令注入

    private void checkCommandInjection(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (!call.getNameAsString().equals("exec")) return;
            // 仅匹配 Runtime.getRuntime().exec(...) 或 runtime 变量上的 exec
            String scope = call.getScope().map(Expression::toString).orElse("");
            if (!scope.contains("getRuntime") && !scope.toLowerCase().contains("runtime")) return;
            if (hasTaintedArg(call)) {
                reportCommandInjection(context, call, "Runtime.exec", issues);
            }
        });
        cu.findAll(ObjectCreationExpr.class).forEach(newExpr -> {
            if (!newExpr.getType().getNameAsString().equals("ProcessBuilder")) return;
            if (hasTaintedArg(newExpr.getArguments())) {
                reportCommandInjection(context, newExpr, "ProcessBuilder", issues);
            }
        });
    }

    private void reportCommandInjection(CheckContext context, Node node, String api,
                                        List<CheckIssue> issues) {
        int line = lineOf(node);
        CheckIssue issue = createIssue(
                IssueLevel.BLOCKER,
                "SEC_COMMAND_INJECTION",
                "命令注入风险",
                api + " 的命令参数包含变量拼接，攻击者可能注入任意系统命令",
                context.getCurrentFilePath(), line, line);
        issue.setSuggestion("避免拼接用户输入构造命令；使用白名单校验参数，或以 ProcessBuilder 数组形式传参并过滤元字符");
        issues.add(issue);
    }

    private boolean hasTaintedArg(MethodCallExpr call) {
        return hasTaintedArg(call.getArguments());
    }

    private boolean hasTaintedArg(List<Expression> args) {
        for (Expression arg : args) {
            if (arg instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
                boolean hasLiteral = binary.findAll(StringLiteralExpr.class).size() > 0;
                boolean hasVar = binary.findAll(NameExpr.class).size() > 0;
                if (hasLiteral && hasVar) return true;
            } else if (arg instanceof NameExpr) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 规则 4：不安全反序列化

    private void checkInsecureDeserialization(CheckContext context, CompilationUnit cu,
                                              List<CheckIssue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            String name = call.getNameAsString();
            if (!name.equals("readObject") && !name.equals("readUnshared")) return;
            String scope = call.getScope().map(Expression::toString).orElse("");
            boolean fromOis = scope.toLowerCase().contains("objectinput")
                    || scope.toLowerCase().matches(".*\\bois\\b.*");
            // 无法确认来源时按方法名报告（readObject 极少有其他语义）
            int line = lineOf(call);
            CheckIssue issue = createIssue(
                    IssueLevel.BLOCKER,
                    "SEC_INSECURE_DESERIALIZATION",
                    "不安全反序列化",
                    (fromOis ? "ObjectInputStream." : "") + name +
                            "() 反序列化不可信数据可导致远程代码执行（参考 commons-collections 利用链）",
                    context.getCurrentFilePath(), line, line);
            issue.setSuggestion("避免对不可信数据使用 Java 原生反序列化；改用 JSON 等数据格式，或使用 ObjectInputFilter 白名单校验类型");
            issues.add(issue);
        });
    }

    // ---------------------------------------------------------------- 规则 5：弱加密

    private void checkWeakCrypto(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (!call.getNameAsString().equals("getInstance")) return;
            String scope = call.getScope().map(Expression::toString).orElse("");
            if (call.getArguments().isEmpty()
                    || !(call.getArgument(0) instanceof StringLiteralExpr lit)) {
                return;
            }
            String algo = lit.getValue().toUpperCase();
            String problem = null;
            if (scope.contains("MessageDigest")
                    && (algo.equals("MD5") || algo.equals("SHA-1") || algo.equals("SHA1"))) {
                // R44 收紧：内容指纹/去重用途的 MD5 不做安全承诺（如重复代码检测的滑动窗口指纹），不报弱加密
                if (isFingerprintContext(call)) {
                    return;
                }
                problem = "哈希算法 " + lit.getValue() + " 已被证明可碰撞，不能用于安全场景";
            } else if (scope.contains("Cipher")
                    && (algo.startsWith("DES/") || algo.equals("DES") || algo.startsWith("DESEDE")
                        || algo.contains("/ECB/"))) {
                problem = "加密算法/模式 " + lit.getValue() + " 强度不足（DES/3DES 可暴力破解，ECB 模式泄露明文模式）";
            } else if (scope.contains("KeyPairGenerator") && algo.equals("RSA") ) {
                return; // RSA 本身不报
            }
            if (problem == null) return;
            int line = lineOf(call);
            CheckIssue issue = createIssue(
                    IssueLevel.CRITICAL,
                    "SEC_WEAK_CRYPTO",
                    "弱加密算法",
                    problem,
                    context.getCurrentFilePath(), line, line);
            issue.setSuggestion("哈希使用 SHA-256 及以上（口令存储用 BCrypt/PBKDF2）；对称加密使用 AES/GCM/NoPadding，密钥长度 ≥ 128 位");
            issues.add(issue);
        });
    }

    /** 所在类名或方法名指明指纹/校验和/去重用途 → 非密码学语境，弱哈希不构成安全问题 */
    private boolean isFingerprintContext(MethodCallExpr call) {
        String methodName = call.findAncestor(MethodDeclaration.class)
                .map(MethodDeclaration::getNameAsString).orElse("");
        String className = call.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::getNameAsString).orElse("");
        return FINGERPRINT_CONTEXT.matcher(methodName).find()
                || FINGERPRINT_CONTEXT.matcher(className).find();
    }

    // ---------------------------------------------------------------- 规则 6：不安全随机数

    private void checkInsecureRandom(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        cu.findAll(ObjectCreationExpr.class).forEach(newExpr -> {
            String type = newExpr.getType().getNameAsString();
            if (!type.equals("Random") && !type.equals("ThreadLocalRandom")) return;
            // 仅在安全语境（变量名/方法名涉及 token/密码/会话等）报告，避免误报普通业务随机
            String varName = newExpr.findAncestor(VariableDeclarator.class)
                    .map(VariableDeclarator::getNameAsString).orElse("");
            String methodName = newExpr.findAncestor(MethodDeclaration.class)
                    .map(MethodDeclaration::getNameAsString).orElse("");
            if (!SECURITY_CONTEXT.matcher(varName).find()
                    && !SECURITY_CONTEXT.matcher(methodName).find()) {
                return;
            }
            int line = lineOf(newExpr);
            CheckIssue issue = createIssue(
                    IssueLevel.CRITICAL,
                    "SEC_INSECURE_RANDOM",
                    "安全场景使用非加密随机数",
                    "'" + (varName.isEmpty() ? methodName : varName) + "' 相关逻辑使用 " + type +
                            "（线性同余伪随机，可被预测）生成安全敏感值",
                    context.getCurrentFilePath(), line, line);
            issue.setSuggestion("令牌、盐值、验证码等安全敏感随机数必须使用 java.security.SecureRandom");
            issues.add(issue);
        });
    }

    // ---------------------------------------------------------------- 规则 7：路径穿越

    private void checkPathTraversal(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        cu.findAll(ObjectCreationExpr.class).forEach(newExpr -> {
            String type = newExpr.getType().getNameAsString();
            if (!type.equals("File") && !type.equals("FileInputStream") && !type.equals("FileOutputStream")
                    && !type.equals("RandomAccessFile")) {
                return;
            }
            if (isPathTainted(newExpr.getArguments())) {
                reportPathTraversal(context, newExpr, "new " + type + "(...)", issues);
            }
        });
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            String scope = call.getScope().map(Expression::toString).orElse("");
            boolean pathFactory = (scope.equals("Paths") && call.getNameAsString().equals("get"))
                    || (scope.equals("Path") && call.getNameAsString().equals("of"));
            if (!pathFactory) return;
            if (isPathTainted(call.getArguments())) {
                reportPathTraversal(context, call, scope + "." + call.getNameAsString() + "(...)", issues);
            }
        });
    }

    private boolean isPathTainted(List<Expression> args) {
        for (Expression arg : args) {
            // 直接污点源：request.getParameter(...) 等
            for (MethodCallExpr call : arg.findAll(MethodCallExpr.class)) {
                String n = call.getNameAsString();
                if (n.equals("getParameter") || n.equals("getHeader") || n.equals("getQueryString")
                        || n.equals("getRequestURI") || n.equals("getPathInfo")) {
                    return true;
                }
            }
            // 拼接语境：字面量 + 可疑命名变量，且所在方法存在请求取值调用
            if (arg instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
                boolean suspiciousName = binary.findAll(NameExpr.class).stream()
                        .anyMatch(ne -> PATH_NAME.matcher(ne.getNameAsString()).find());
                boolean hasTaintSource = arg.findAncestor(MethodDeclaration.class)
                        .map(md -> md.findAll(MethodCallExpr.class).stream()
                                .anyMatch(c -> c.getNameAsString().equals("getParameter")
                                        || c.getNameAsString().equals("getHeader")
                                        || c.getNameAsString().equals("getRequestURI")))
                        .orElse(false);
                if (suspiciousName && hasTaintSource) {
                    return true;
                }
            }
        }
        return false;
    }

    private void reportPathTraversal(CheckContext context, Node node, String api,
                                     List<CheckIssue> issues) {
        int line = lineOf(node);
        CheckIssue issue = createIssue(
                IssueLevel.CRITICAL,
                "SEC_PATH_TRAVERSAL",
                "路径穿越风险",
                api + " 的文件路径包含请求来源的变量，攻击者可用 ../ 读取或覆盖任意文件",
                context.getCurrentFilePath(), line, line);
        issue.setSuggestion("对用户输入的文件名做白名单校验；使用 Paths.get(base, name).normalize() 后校验结果仍位于基准目录内");
        issues.add(issue);
    }

    // ---------------------------------------------------------------- 规则 8：XXE

    private void checkXxe(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (!call.getNameAsString().equals("newInstance")) return;
            String scope = call.getScope().map(Expression::toString).orElse("");
            if (!scope.equals("DocumentBuilderFactory") && !scope.equals("SAXParserFactory")
                    && !scope.equals("TransformerFactory") && !scope.equals("XMLInputFactory")
                    && !scope.equals("SchemaFactory") && !scope.equals("SAXReader")
                    && !scope.equals("SAXBuilder")) {
                return;
            }
            // 在同一方法（或初始化块）内查找是否已做安全加固
            Optional<Node> scopeNode = call.findAncestor(MethodDeclaration.class)
                    .map(Node.class::cast)
                    .or(() -> call.findAncestor(BlockStmt.class).map(Node.class::cast));
            Node searchRoot = scopeNode.orElse(call);
            boolean hardened = searchRoot.findAll(MethodCallExpr.class).stream().anyMatch(c -> {
                String n = c.getNameAsString();
                if (!n.equals("setFeature") && !n.equals("setProperty") && !n.equals("setAttribute")) {
                    return false;
                }
                String args = c.getArguments().toString();
                return args.contains("disallow-doctype-decl")
                        || args.contains("external-general-entities")
                        || args.contains("external-parameter-entities")
                        || args.contains("load-external-dtd")
                        || args.contains("ACCESS_EXTERNAL_DTD")
                        || args.contains("ACCESS_EXTERNAL_SCHEMA");
            });
            if (hardened) return;
            int line = lineOf(call);
            CheckIssue issue = createIssue(
                    IssueLevel.CRITICAL,
                    "SEC_XXE",
                    "XML 外部实体注入（XXE）风险",
                    scope + ".newInstance() 创建的解析器未禁用外部实体/DTD，解析不可信 XML 时可导致文件读取或 SSRF",
                    context.getCurrentFilePath(), line, line);
            issue.setSuggestion("创建后立即加固：factory.setFeature(\"http://apache.org/xml/features/disallow-doctype-decl\", true)，并禁用 external-general/parameter-entities");
            issues.add(issue);
        });
    }

    // ---------------------------------------------------------------- 规则 9：SSRF

    private void checkSsrf(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        cu.findAll(ObjectCreationExpr.class).forEach(newExpr -> {
            if (!newExpr.getType().getNameAsString().equals("URL")) return;
            if (newExpr.getArguments().size() != 1) return;
            if (newExpr.getArgument(0) instanceof StringLiteralExpr) return; // 常量 URL 不报
            // 所在方法内存在 openConnection 调用才视为发起请求
            boolean opens = newExpr.findAncestor(MethodDeclaration.class)
                    .map(md -> md.findAll(MethodCallExpr.class).stream()
                            .anyMatch(c -> c.getNameAsString().equals("openConnection")
                                    || c.getNameAsString().equals("openStream")))
                    .orElse(false);
            if (!opens) return;
            int line = lineOf(newExpr);
            CheckIssue issue = createIssue(
                    IssueLevel.CRITICAL,
                    "SEC_SSRF",
                    "服务端请求伪造（SSRF）风险",
                    "new URL(变量) 后发起连接，若 URL 来自用户输入，攻击者可探测内网或访问元数据服务",
                    context.getCurrentFilePath(), line, line);
            issue.setSuggestion("校验目标地址：禁止内网网段（10./172.16-31./192.168./127./169.254.）与重定向跟随，或使用域名白名单");
            issues.add(issue);
        });
    }

    // ---------------------------------------------------------------- 工具方法

    private boolean isStringType(String typeName) {
        return typeName.equals("String") || typeName.equals("CharSequence")
                || typeName.equals("java.lang.String");
    }

    private int lineOf(Node node) {
        return node.getBegin().map(p -> p.line).orElse(1);
    }
}
