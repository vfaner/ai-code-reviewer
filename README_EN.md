# AI Code Reviewer · AI Code Review Platform

[中文](README.md) | [English](README_EN.md)

> An out-of-the-box Java code quality review platform: a local static analysis engine + optional AI semantic review + SonarQube-style five-grade scoring and quality gates.
> Shipped as a single JAR / single Docker image with an embedded database — zero external dependencies, fully offline capable, with first-class Chinese UI, rules and reports.

---

## 📖 Background

Common code-quality solutions are painful for small and medium-sized teams:

- **SonarQube-style platforms are heavy**: they need a dedicated server, database and multiple backend components; the community edition has limited rules and features, advanced capabilities (branch analysis, PDF reports, some security rules) require commercial licensing, and Chinese support is poor.
- **PMD / SpotBugs / Checkstyle only produce "issue lists"**: CLI or IDE plugins without scoring, quality gates, visual reports, or team-level ignore/governance — unreadable for non-technical stakeholders.
- **AI coding assistants work in silos**: each tool needs its own API setup, multi-vendor LLMs cannot be managed centrally, and none of them integrate with static analysis results.
- **Chinese enterprise environments have extra requirements**: air-gapped intranets, domestic databases (DM / Kingbase / openGauss), domestic LLMs, and Chinese reporting.

This project is built as an **all-in-one review platform**: one JAR or one container runs everything, with an embedded H2 database out of the box; 19 checkers cover six quality domains — security, bugs, style, architecture, concurrency and dependencies; multiple LLM vendors can optionally be plugged in for AI deep review; scoring, gates, technical debt and reports are fully localized in Chinese.

## ✨ Overview

### Core Features

**1. Multiple code ingestion methods**
- Upload a ZIP archive or paste code snippets directly; in CI scenarios it clones Git repositories automatically (GitHub / GitLab / Gitee / self-hosted platforms, private credentials supported)
- Automatic environment detection: JDK version, build tool, framework, dependency tree, root package layout; optional unit test execution
- Code snapshots are retained so every issue can be traced back to its source context (line numbers + highlighted problem lines)

**2. Local static analysis engine (fully offline, 19 built-in checkers)**

| Quality domain | Coverage |
|----------------|----------|
| Bugs | Compilation diagnostics, null-pointer risks, resource leaks, exception handling (empty catch, etc.) |
| Security (SAST) | SQL injection, command injection, insecure deserialization, hardcoded secrets, weak crypto, weak randomness, XXE, SSRF, path traversal |
| Architecture | Controller-to-DAO layer skipping, reverse layer dependencies, entity leakage into API layers |
| Concurrency | Shared mutable state in singletons, static SimpleDateFormat, double-checked locking without volatile |
| Style / redundancy | Naming conventions, magic numbers, wildcard imports, long lines, TODO comments, unused methods, duplicate code blocks (CPD-style token fingerprinting that automatically excludes getters/setters and constructors — far fewer false positives) |
| Dependency vulnerabilities | Parses pom.xml / build.gradle and matches against a built-in CVE advisory store (optional OSV online enrichment) |
| Quality / performance / framework | Cyclomatic complexity, method/file length, performance issues, Spring best practices |

**3. AI deep review (optional — everything works without it)**
- Multi-vendor LLM support: OpenAI-compatible and Anthropic protocols, with 12 built-in vendor templates (Alibaba Bailian, Volcano Ark, DeepSeek, Kimi, Zhipu, Baidu Qianfan, Gemini, Claude, and local/private deployments via Ollama / vLLM / LocalAI). API keys are AES-encrypted at rest.
- Per-issue "AI enhanced suggestion": analysis / fix plan / fix code, strictly scoped to the flagged lines
- One-click "AI deep review" batch-enhances all remaining issues with live progress

**4. Five-grade scoring & quality gate (SonarQube-style)**
- Five severities: BLOCKER (−25) / CRITICAL (−15) / MAJOR (−5) / MINOR (−1) / INFO (0, informational)
- Score = 100 − Σ(count × weight); grades Excellent / Good / Fair / Poor
- Gate = blocker veto (limit configurable) + minimum score; **every weight, the pass threshold and the grade bands are customizable in the UI and take effect immediately — no restart, no rescan**
- Technical debt estimation based on a per-rule remediation-time catalog

**5. Issue governance**
- Same-file same-rule issues are automatically merged into one row (all locations listed), no screen flooding
- Line-level ignore + rule-level ignore (by rule code / file path / glob / line number), with reasons recorded
- Checkers and rule thresholds can be toggled and tuned in the UI

**6. Reports & CI/CD**
- One-click HTML / PDF export with embedded CJK fonts: score, gate verdict, category statistics, every issue with suggestions
- Webhook-triggered scans (GitHub / GitLab / Gitee / generic); on completion the platform writes back commit statuses and MR/PR comments (score + gate + top issues) so pipelines can block on the gate verdict
- CI access token management and scan record traceability

**7. Authentication & permissions**
- JWT local accounts + remote OAuth2 SSO (enterprise OA), with admin / read-only roles
- Database passwords, API keys and repository tokens are all AES-encrypted at rest

**8. UI & i18n**
- Thymeleaf server-side rendering — no Vue / npm / Node build chain, zero CDN, fully local assets (intranet friendly)
- Chinese / English switching, dark / light themes, responsive layout (desktop / tablet / mobile)

**9. Multi-database support**

| Database | Notes |
|----------|-------|
| H2 (embedded) | Default, zero installation |
| MySQL / PostgreSQL / Oracle | Drivers bundled |
| DM / Kingbase / openGauss | Chinese domestic (Xinchuang) databases, drivers bundled |
| Custom JDBC | Upload any driver JAR from the UI |

Databases are switched visually in the UI with automatic schema creation/migration and connectivity testing.

### Tech Stack

| Layer | Technologies |
|-------|--------------|
| Backend | Spring Boot 3.2.5 · Java 17 (Web / AOP / Validation / Cache / Actuator) |
| Persistence | MyBatis-Plus 3.5.5 · H2 2.2 (embedded default) · MySQL / PostgreSQL / Oracle / DM / Kingbase / openGauss drivers · dynamic multi-datasource |
| Static analysis | JavaParser 3.25 (AST + symbol solving) · ASM 9.6 (bytecode) · custom CPD-style duplicate-code fingerprinting |
| AI integration | Spring WebFlux HTTP client · OpenAI-compatible / Anthropic dual-protocol adapter |
| Reports | OpenPDF 1.3 (vector CJK PDF) · Thymeleaf HTML reports |
| Version control | JGit 6.8 (repository cloning) |
| Security | JWT · spring-security-crypto (BCrypt) · AES config encryption · OAuth2 remote auth |
| Frontend | Thymeleaf SSR · vanilla JavaScript · CSS-variable design tokens · inline SVG sprite · zero CDN |
| Deployment | Single JAR · multi-stage Docker build (CJK fonts baked in, non-root, HEALTHCHECK) · docker compose |

## 🖼️ Screenshots

All screenshots are taken from real running pages; image assets live in the [`images/`](images) directory.

**Dashboard** — task stats, quality score and technical debt overview

![Dashboard](images/ai_code_reviewer_kanban.png)

**New Scan** — launch ZIP / Git / Webhook scan tasks

![New Scan](images/ai_code_reviewer_daimasaomiao.png)

**Scan History** — task list and status transitions

![Scan History](images/ai_code_reviewer_saomiaolishi.png)

**Scan Result** — five-level issue stats and category summary

![Scan Result](images/ai_code_reviewer_result.png)

**Result Details** — code context, fix suggestions and AI-enhanced advice

![Result Details](images/ai_code_reviewer_result_detail.png)

**AI Provider Settings** — built-in provider templates, dual-protocol access

![AI Provider Settings](images/ai_code_reviewer_ai.png)

**Checker Settings** — enable/disable checkers and tune parameters

![Checker Settings](images/ai_code_reviewer_jianchapeizhi.png)

**Review Rules** — default severity per rule

![Review Rules](images/ai_code_reviewer_pingshenguize.png)

**Quality Gate** — thresholds and gate verdicts

![Quality Gate](images/ai_code_reviewer_zhiliangmenjin.png)

**Ignore Rules** — path- and rule-level ignores

![Ignore Rules](images/ai_code_reviewer_hulue.png)

**Database Settings** — embedded metadata and dynamic external datasources

![Database Settings](images/ai_code_reviewer_db.png)

**CI/CD Integration** — pipeline hookup

![CI/CD Integration](images/ai_code_reviewer_cicd.png)

**Remote Auth** — enterprise OA / SSO hookup

![Remote Auth](images/ai_code_reviewer_oa.png)

**English UI** — zh / en switch

![English UI](images/ai_code_reviewer_english.png)

**Dark Mode** — dark theme toggle

![Dark Mode](images/ai_code_reviewer_anye.png)

## ⚡ Instant Deployment (no source code needed)

No clone, no Maven — download the **runnable Jar** attached to a Release (the very same artifact is published on GitHub and Gitee):

- GitHub Releases: <https://github.com/vfaner/ai-code-reviewer/releases>
- Gitee Releases: <https://gitee.com/super_rgh/ai-code-reviewer/releases>

All you need is **JDK / JRE 17+**:

```bash
java -jar ai-code-reviewer-1.0.0.jar
```

- First run auto-initializes the embedded H2 database (`data/`), scan snapshots & reports (`work/`) and logs (`logs/`) in the working directory — no external database required
- Open <http://localhost:8080>, default account `admin / 123456` (change the password after first login)
- Custom port: `java -jar ai-code-reviewer-1.0.0.jar --server.port=9090`
- Override the built-in secrets in production: `--app.jwt-secret=<new-jwt-secret> --app.crypto-key=<new-aes-key>`

For source builds and Docker, see [Deployment](#-deployment) below.

## ⚔️ Comparison

| Dimension | **AI Code Reviewer** | SonarQube (Community) | PMD / SpotBugs / Checkstyle | CodeQL |
|-----------|----------------------|-----------------------|------------------------------|--------|
| Deployment | ⭐ Single JAR / container, embedded DB, up in 1 minute | Server + DB + compute engine, usually needs dedicated ops | Lightweight, but CLI / IDE only — no server or UI | Requires compiling the codebase + dedicated CLI; server only on GitHub |
| Chinese support | ✅ Native (UI / rules / suggestions / reports) | ❌ English-first | ❌ | ❌ |
| AI semantic review | ✅ Multi-vendor LLMs (incl. domestic & local Ollama), per-issue fix suggestions | ❌ (commercial cloud only) | ❌ | ❌ |
| Scoring & gate | ✅ Five-grade scoring; weights / thresholds / bands customizable in UI, instant effect | ✅ Fixed rules, not customizable | ❌ Issue lists only | ❌ |
| Duplicate code | ✅ CPD-style token fingerprints, boilerplate excluded | ✅ (partially limited) | Needs separate CPD setup | ❌ |
| Dependency CVEs | ✅ Built-in advisory store + optional OSV | ❌ (commercial editions) | ❌ | ❌ (needs Dependabot) |
| Architecture rules | ✅ Built-in | Partial (plugins / paid) | ❌ | Custom queries, steep learning curve |
| CI/CD | ✅ Webhook trigger + status write-back + MR/PR comments (GitHub / GitLab / Gitee / self-hosted) | ✅ Extra plugins & config | DIY scripts | ✅ GitHub ecosystem only |
| Visual reports | ✅ One-click HTML / PDF (CJK-ready) | PDF needs plugins / paid | ❌ | ❌ |
| Offline / air-gapped | ✅ Full functionality offline (AI optional) | ✅ | ✅ | ✅ |
| Domestic databases | ✅ DM / Kingbase / openGauss drivers bundled | ❌ | — | — |
| Licensing cost | ✅ MIT, completely free | Community free, advanced paid | Free | Paid for private GitHub repos |
| Language coverage | Java (deep focus) | Multi-language | Mostly Java | Multi-language |

**Honest positioning**: for polyglot monorepos that need a massive rule ecosystem and long-term trend governance, SonarQube / CodeQL are more mature. This project's differentiated value is **zero-friction deployment, native Chinese, AI enhancement, and a one-stop Java workflow**: no database to install, no ops to hire, no license to buy — one command gets you the full loop of scan → score → gate → report → CI blocking. It is especially suited to small/medium Java teams, air-gapped intranets, Xinchuang (domestic-tech) projects, and teaching demos.

## 🚀 Deployment

### Requirements

| Method | Requires |
|--------|----------|
| JAR | JDK 17+ (Maven 3.9+ to build) |
| Docker | Docker 20.10+ / Docker Compose v2 |

### Option 1: JAR

```bash
# Build
mvn package -DskipTests

# Run (data/ database and work/ snapshots are created in the working directory)
java -jar target/ai-code-reviewer.jar
```

Open http://localhost:8080. Default accounts (created on first start — **change the passwords immediately**):

| Account | Password | Role |
|---------|----------|------|
| admin | 123456 | Administrator (full access) |
| view | 123456 | Read-only |

Development mode: `mvn spring-boot:run` (template caching disabled — just refresh).

### Option 2: Docker (recommended)

**docker compose:**

```bash
# Optional: copy .env.example to .env and change the JWT & AES secrets
docker compose up -d --build

docker compose ps          # status
docker compose logs -f     # logs
```

**Plain docker:**

```bash
# Build the image (multi-stage: Maven build → JRE runtime)
./scripts/docker-build.sh 1.0.0
# In mainland-China networks, build via registry mirrors:
./scripts/docker-build-cn.sh 1.0.0

# Run with persistent volumes
docker run -d --name ai-code-reviewer \
  -p 8080:8080 \
  -v aicr-data:/app/data \
  -v aicr-work:/app/work \
  -v aicr-logs:/app/logs \
  -v aicr-lib:/app/lib \
  -e APP_JWT_SECRET="your-own-random-secret-at-least-32-chars" \
  -e APP_CRYPTO_KEY="your-16-char-key" \
  --restart unless-stopped \
  ai-code-reviewer:1.0.0
```

Health check: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`

**Image highlights**: Noto CJK / WenQuanYi fonts baked in (PDF reports render Chinese correctly), non-root user, built-in HEALTHCHECK, graceful shutdown.

**Key environment variables:**

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `prod` | Production profile |
| `APP_AUTH_ENABLED` | `true` | Enable login authentication |
| `APP_JWT_SECRET` | built-in placeholder | JWT signing secret — **must change in production** |
| `APP_JWT_EXPIRE_HOURS` | `24` | Token lifetime (hours) |
| `APP_CRYPTO_KEY` | built-in placeholder | AES key for sensitive config (16 chars) — **must change in production** |
| `APP_WORK_DIR` | `/app/work` | Snapshot / report working directory |
| `APP_DRIVER_DIR` | `/app/lib/custom` | Custom JDBC driver directory |
| `DATASOURCE_URL` | container H2 file DB | Override to use an external MySQL / PostgreSQL etc. |
| `TZ` | `Asia/Shanghai` | Timezone |

Persistent paths: `/app/data` (database), `/app/work` (snapshots / reports), `/app/logs` (logs), `/app/lib` (driver JARs).

### Quick Start

1. Log in, open **New Scan**, upload a project ZIP or paste code;
2. When the scan finishes you land on the result page: score ring, five-grade distribution, gate verdict, and every issue (expandable with code context);
3. The **Quality Gate** page shows scores and gate results for all tasks; admins can click **Customize** to tune per-grade weights and thresholds;
4. Export **HTML / PDF** reports;
5. For CI, create a trigger and token on the **CI/CD** page — a single `curl` webhook from your pipeline triggers the scan and writes back statuses and comments.

## 📁 Project Layout

```
ai-code-reviewer/
├── src/main/java/com/aicodereview/
│   ├── checker/         # Checker framework + 19 built-in checkers (AST / regex / scan-level)
│   ├── config/          # Startup initialization, bean config
│   ├── controller/      # Page controllers + REST API
│   ├── datasource/      # Dynamic multi-datasource & dialect adapter
│   ├── dto/ entity/ mapper/   # Data model (MyBatis-Plus)
│   ├── llm/             # Multi-vendor LLM protocol adapters
│   ├── security/        # JWT, role aspect, user context
│   ├── service/         # Scanning, scoring/gate, AI review, reports, CI callbacks…
│   └── util/            # Utilities
├── src/main/resources/
│   ├── db/              # DDL for H2 / MySQL dialects
│   ├── i18n/            # Chinese & English message bundles
│   ├── security/        # Built-in CVE advisory store
│   ├── templates/       # Thymeleaf pages (16)
│   ├── static/          # CSS / JS / local icons (zero CDN)
│   └── application.yml
├── samples/             # Sample bad code (paste it to try the product)
├── scripts/             # Docker build / run scripts (incl. China-mirror variant)
├── Dockerfile           # Multi-stage build
└── docker-compose.yml
```

## 🤝 Summary & Feedback

This project started from one goal: **give small teams a complete code-quality loop at the lowest possible cost** — single-container delivery, zero external dependencies, offline-capable, Chinese-native, optional AI enhancement, fully customizable scoring gates, and end-to-end CI integration. It keeps evolving: rule sets, the advisory store and report formats will continue to grow.

Feedback, suggestions and issue reports are very welcome:

- Open an Issue / Pull Request
- **QQ: 817094 / 2912167928**
- **WeChat: hua47609**

Every piece of feedback makes it better.

## 📄 License

Released under the [MIT License](LICENSE) — free to use, modify and distribute commercially.
