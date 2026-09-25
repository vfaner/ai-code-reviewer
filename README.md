# 百目 JArgus · Java 代码评审平台

[中文](README.md) | [English](README_EN.md)

> 百目所视，无所遁形。开箱即用的 Java 代码质量评审平台：本地静态分析引擎 + 可选 AI 语义评审 + SonarQube 式五级评分与质量门禁 + CI/CD 全链路联动 + 扫描报告邮件推送。
> 单个 JAR / 单个 Docker 镜像交付，内嵌数据库零外部依赖，完全离线可用，界面、规则、报告原生中文。

---

## 📖 开发背景

中小团队做 Java 代码质量管控，常见方案各有明显痛点：

- **SonarQube 等平台太重**：需要独立服务器、数据库与多个后台组件，通常要专人运维；社区版功能受限，分支分析、PDF 报告、部分安全规则需商业授权；中文支持差。
- **PMD / SpotBugs / Checkstyle 只是"问题清单"**：命令行或 IDE 插件形态，没有评分体系、质量门禁、可视化报告与团队治理机制，非技术角色看不懂。
- **AI 编码助手各自为战**：每个工具单独配 API，无法统一管理多家大模型，也不与静态分析结果打通。
- **国内环境有额外诉求**：内网物理隔离、信创数据库（达梦 / 人大金仓 / openGauss）、国产大模型、中文汇报材料。

为此打造了百目 JArgus——**一个 JAR / 一个容器**即可跑通「扫描 → 评分 → 门禁 → 报告 → CI 阻断 → 邮件推送」完整闭环：内嵌 H2 数据库开箱即用；19 个检查器覆盖安全、缺陷、风格、架构、并发、依赖六大质量域；联网时可选接入多家大模型做 AI 深度评审；评分、门禁、技术债、报告全流程中文化。

## ✨ 项目介绍

**多种接入方式** — 上传 ZIP 压缩包、直接粘贴代码；CI 场景自动 Git 克隆（GitHub / GitLab / Gitee / 自建平台，支持私有仓库凭据）；自动检测 JDK 版本、构建工具、框架与依赖树；代码快照留存，问题可回溯到带行号高亮的源码上下文。

**本地静态分析引擎** — 完全离线，19 个内置检查器覆盖六大质量域：

| 质量域 | 覆盖内容 |
|--------|----------|
| 缺陷 | 编译诊断、空指针风险、资源泄露、异常处理（空 catch 等） |
| 安全（SAST） | SQL 注入、命令注入、不安全反序列化、硬编码密钥、弱加密、弱随机数、XXE、SSRF、路径穿越 |
| 架构约束 | 控制器跨层直连 DAO、下层反向依赖上层、实体泄漏到接口层 |
| 并发 | 单例共享可变状态、静态 SimpleDateFormat、双重检查锁缺 volatile |
| 风格 / 冗余 | 命名规范、魔法数字、通配符导入、超长行、TODO 注释、未使用方法、重复代码块（CPD 式 Token 指纹，自动排除 getter/setter 等样板，误报少） |
| 依赖漏洞 | 解析 pom.xml / build.gradle，与内置 CVE 漏洞库比对（可选 OSV 在线增强） |
| 质量 / 性能 / 框架 | 圈复杂度、方法/文件长度、性能问题、Spring 最佳实践 |

**AI 深度评审（可选，不配 AI 也能完整使用）** — OpenAI 兼容 / Anthropic 双协议，内置 12 个厂商模板（阿里百炼、火山方舟、DeepSeek、Kimi、智谱、百度千帆、Gemini、Claude、Ollama / vLLM / LocalAI 本地私有部署等），API Key AES 加密存储；单条问题「AI 增强建议」（问题分析 / 修复方案 / 修复代码三段式），也可一键批量深度评审，进度实时可视。

**五级评分与质量门禁（对标 SonarQube）** — 阻断 BLOCKER（-25）/ 严重 CRITICAL（-15）/ 主要 MAJOR（-5）/ 次要 MINOR（-1）/ 提示 INFO（0 分仅展示）；评分 = 100 − Σ(数量 × 扣分)，评级 优秀 / 良好 / 一般 / 较差；门禁 = 阻断清零（可配上限）+ 评分达线；**每级扣分、通过线、评级分界均可页面自定义，保存即时生效，无需重启重扫**；附技术债估算（按规则目录折算修复分钟数）。

**问题治理** — 同文件同规则问题自动聚合为一条，不刷屏；行级忽略 + 规则级忽略（按规则码 / 文件路径 / glob / 行号），忽略原因留痕；检查器启停与规则阈值页面可配。

**报告与邮件推送** — HTML / PDF 中文报告一键导出（内嵌中文字体，含评分、门禁结论、分类统计与逐条建议）；多 SMTP 发件配置（SSL / STARTTLS、一键测试发信、授权码 AES 加密）与收件人管理；扫描或 CI 触发器勾选「邮件通知」后，扫描完成自动推送 **HTML 摘要正文 + PDF 报告附件**，失败也发通知，扫描历史显示送达状态。

**CI/CD 集成** — Webhook 触发扫描（GitHub / GitLab / Gitee / 通用，按平台约定自动校验 HMAC 签名），完成后自动回写 commit status 与 MR/PR 评论（评分 + 门禁 + Top 问题），流水线按门禁结论阻断合并；访问令牌管理、触发记录追溯。详见 [CI/CD 集成指南](#-cicd-集成指南)。

**认证与安全** — JWT 本地账号 + 远端 OAuth2 单点登录（对接企业 OA），管理员 / 只读双角色；数据库密码、API Key、仓库令牌、SMTP 授权码等敏感配置全部 AES 加密落库；SQL 全参数化，ZIP 解压含 Zip Slip 防护。

**界面与国际化** — Thymeleaf 服务端渲染，无 Vue / npm / Node 构建链，零 CDN 全本地化资源（内网可用）；中英双语切换、深色 / 浅色主题、响应式布局（PC / 平板 / 手机）。

**多数据库支持** — H2（内嵌默认，零安装）/ MySQL / PostgreSQL / Oracle 驱动内置；信创场景达梦 DM / 人大金仓 / openGauss 驱动随包内置；任意数据库可页面上传 JDBC 驱动接入；可视化切换、自动建表迁移、连通性测试。

## 🧰 技术栈

| 层次 | 技术选型 |
|------|----------|
| 后端框架 | Spring Boot 3.2.5 · Java 17（Web / AOP / Validation / Cache / Actuator） |
| 持久层 | MyBatis-Plus 3.5.5 · H2 2.2（内嵌默认）· MySQL / PostgreSQL / Oracle / 达梦 / 金仓 / openGauss 驱动 · 动态多数据源 |
| 静态分析 | JavaParser 3.25（AST + 符号求解）· ASM 9.6（字节码）· 自研 CPD 式重复代码指纹 |
| AI 接入 | Spring WebFlux HTTP 客户端 · OpenAI 兼容 / Anthropic 双协议适配层 |
| 报告与邮件 | OpenPDF 1.3（矢量中文 PDF）· Thymeleaf HTML 报告 · Spring Mail（SMTP / SSL / STARTTLS） |
| 版本控制 | JGit 6.8（仓库克隆） |
| 安全 | JWT · spring-security-crypto（BCrypt）· AES 配置加密 · OAuth2 远端认证 |
| 前端 | Thymeleaf SSR · 原生 JavaScript · CSS 变量设计令牌 · 内联 SVG 图标精灵 · 零 CDN |
| 部署 | 单 JAR · Docker 多阶段构建（内置中文字体、非 root 运行、HEALTHCHECK）· docker compose |

## 🖼️ 效果预览

截图均取自真实运行页面，图片资产位于仓库 [`images/`](images) 目录。

### 总览与界面

**仪表盘** — 任务统计、质量评分与技术债总览

![仪表盘](images/jargus_kanban.png)

**暗色主题** — 深色 / 浅色一键切换

![暗色主题](images/jargus_kanban_anye.png)

**英文界面** — 中 / 英双语

![英文界面](images/jargus_kanban_en.png)

### 扫描与治理

**新建扫描** — ZIP 上传 / 代码粘贴，扫描选项与邮件通知

![新建扫描](images/jargus_saomiao.png)

**扫描历史** — 任务列表、状态流转与邮件送达状态

![扫描历史](images/jargus_lishi.png)

**检查器配置** — 检查器启停与参数调整

![检查器配置](images/jargus_jianchaqi.png)

**评审规则** — 规则默认等级与维护

![评审规则](images/jargus_guize.png)

**忽略规则** — 路径与规则级忽略配置

![忽略规则](images/jargus_hulve.png)

**质量门禁** — 阈值配置与门禁判定

![质量门禁](images/jargus_menjin.png)

### AI 与邮件

**AI 厂商配置** — 内置厂商模板与双协议接入

![AI 厂商配置](images/jargus_ai.png)

**发件配置** — 多 SMTP 发件邮箱，同一时间启用一个，支持测试发信

![发件配置](images/jargus_fajianpeizhi.png)

**邮件收件人** — 通知收件人管理

![邮件收件人](images/jargus_shoujianren.png)

### CI/CD 与系统管理

**CI/CD 触发器** — 触发器与访问令牌管理

![CI/CD 触发器](images/jargus_cicd.png)

**新建触发器** — 平台、分支过滤、扫描行为与发信开关

![新建触发器](images/jargus_cicd_add.png)

**触发扫描记录** — 每次触发的状态流转追溯

![触发扫描记录](images/jargus_cicd_jilu.png)

**数据库配置** — 内嵌元数据与动态外接数据源

![数据库配置](images/jargus_db.png)

**远端认证** — 对接企业 OA / 统一登录

![远端认证](images/jargus_oa.png)

**新增远端认证** — OAuth2 认证源配置

![新增远端认证](images/jargus_oa_add.png)

**系统信息** — 版本、仓库地址与联系方式

![系统信息](images/jargus_xitongxinxi.png)

## ⚔️ 优势对比

与市面常用工具横向对比：

| 维度 | **百目 JArgus** | SonarQube（社区版） | PMD / SpotBugs / Checkstyle | CodeQL |
|------|----------------------|---------------------|------------------------------|--------|
| 部署复杂度 | ⭐ 单 JAR / 单容器，内嵌数据库，1 分钟起服务 | 服务器 + 数据库 + 计算引擎，通常需要专人运维 | 轻量，但只有 CLI / IDE 插件，无服务端与页面 | 需编译 codebase + 专用 CLI，服务端仅 GitHub |
| 中文支持 | ✅ 界面 / 规则 / 建议 / 报告原生中文 | ❌ 以英文为主 | ❌ | ❌ |
| AI 语义评审 | ✅ 多厂商大模型（含国产与本地 Ollama），问题级修复建议 | ❌（仅商业云服务提供） | ❌ | ❌ |
| 评分与质量门禁 | ✅ 五级评分，扣分值 / 阈值 / 评级分界页面自定义即时生效 | ✅ 规则固定，不可自定义分值 | ❌ 只有问题清单 | ❌ |
| 重复代码检测 | ✅ CPD 式 Token 指纹，自动排除样板代码误报 | ✅（部分能力受限） | 需另配 CPD | ❌ |
| 依赖漏洞（CVE） | ✅ 内置漏洞库 + 可选 OSV 在线增强 | ❌（依赖商业版） | ❌ | ❌（需另配 Dependabot） |
| 架构分层检查 | ✅ 内置 | 部分（需插件 / 付费） | ❌ | 可自写查询，学习成本高 |
| CI/CD 集成 | ✅ Webhook 触发 + 状态回写 + MR/PR 评论（GitHub / GitLab / Gitee / 自建） | ✅ 需额外插件与配置 | 需自行编写脚本 | ✅ 仅限 GitHub 生态 |
| 报告邮件推送 | ✅ 扫描完成自动发 HTML 摘要 + PDF 报告邮件 | 需商业版或额外配置 | ❌ | ❌ |
| 可视化报告 | ✅ HTML / PDF 中文报告一键导出 | PDF 需插件 / 付费 | ❌ | ❌ |
| 离线 / 内网运行 | ✅ 全功能离线（AI 为可选增强） | ✅ | ✅ | ✅ |
| 信创数据库 | ✅ 达梦 / 人大金仓 / openGauss 驱动内置 | ❌ | — | — |
| 授权费用 | ✅ MIT 完全免费 | 社区版免费，高级功能付费 | 免费 | GitHub 私有仓库需付费 |
| 语言覆盖 | Java（深度聚焦） | 多语言 | Java 为主 | 多语言 |

**客观定位**：如果你需要多语言混合仓库的海量规则生态与长期趋势治理，SonarQube / CodeQL 更成熟。本项目的差异化价值在于——**零门槛部署、中文原生、AI 增强、Java 场景一站式**：不用装数据库、不用配运维、不用买授权，一条命令获得完整质量闭环，特别适合中小 Java 团队、内网隔离环境、信创项目与教学演示。

## 🚀 部署教程

### 环境要求

| 部署方式 | 要求 |
|----------|------|
| Release JAR | JDK / JRE 17+ |
| 源码构建 | JDK 17+ · Maven 3.9+ |
| Docker | Docker 20.10+ / Docker Compose v2 |

### 方式一：Release JAR（无需源码，最快）

从 Release 直接下载**可运行 Jar**（GitHub 与 Gitee 为同一个包）：

- GitHub Releases：<https://github.com/vfaner/jargus/releases>
- Gitee Releases：<https://gitee.com/super_rgh/jargus/releases>

```bash
java -jar jargus-2.0.0.jar
```

- 首次启动自动在当前目录初始化内嵌 H2 数据库（`data/`）、扫描快照与报告（`work/`）、日志（`logs/`），无需外接数据库；
- 访问 <http://localhost:8080>，默认账号 `admin / 123456`（登录后请尽快修改密码）；
- 换端口：`java -jar jargus-2.0.0.jar --server.port=9090`；
- 生产环境建议覆盖内置密钥：`--app.jwt-secret=<新JWT密钥> --app.crypto-key=<新AES密钥>`。

### 方式二：源码构建

```bash
git clone https://gitee.com/super_rgh/jargus.git   # 或 github.com/vfaner/jargus
cd jargus
mvn package -DskipTests

# 启动（工作目录下自动生成 data/ 数据库、work/ 快照与报告）
java -jar target/jargus.jar
```

开发模式：`mvn spring-boot:run`（模板已关闭缓存，改完刷新即可）。

默认账号（首次启动自动创建，**请立即修改密码**）：

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | 123456 | 管理员（全部功能） |
| view | 123456 | 只读用户 |

### 方式三：Docker（推荐生产环境）

**docker compose 一键起：**

```bash
# 可选：复制 .env.example 为 .env，修改 JWT 与 AES 密钥
docker compose up -d --build

docker compose ps          # 查看状态
docker compose logs -f     # 跟踪日志
```

**docker 命令方式：**

```bash
# 构建镜像（多阶段：Maven 打包 → JRE 运行时）
./scripts/docker-build.sh 2.0.0
# 国内网络环境可用镜像站加速构建：
./scripts/docker-build-cn.sh 2.0.0

# 运行（数据卷持久化）
docker run -d --name jargus \
  -p 8080:8080 \
  -v jargus-data:/app/data \
  -v jargus-work:/app/work \
  -v jargus-logs:/app/logs \
  -v jargus-lib:/app/lib \
  -e APP_JWT_SECRET="your-own-random-secret-at-least-32-chars" \
  -e APP_CRYPTO_KEY="your-16-char-key" \
  --restart unless-stopped \
  jargus:2.0.0
```

健康检查：`curl http://localhost:8080/actuator/health` → `{"status":"UP"}`

**镜像特性**：内置 Noto CJK / 文泉驿中文字体（PDF 报告中文正常）、非 root 用户运行、自带 HEALTHCHECK、优雅停机。

**主要环境变量：**

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `prod` | 生产配置 |
| `APP_AUTH_ENABLED` | `true` | 是否开启登录鉴权 |
| `APP_JWT_SECRET` | 内置占位值 | JWT 签名密钥，**生产必须修改** |
| `APP_JWT_EXPIRE_HOURS` | `24` | Token 有效期（小时） |
| `APP_CRYPTO_KEY` | 内置占位值 | 敏感配置 AES 密钥（16 字符），**生产必须修改** |
| `APP_WORK_DIR` | `/app/work` | 代码快照 / 报告工作目录 |
| `APP_DRIVER_DIR` | `/app/lib/custom` | 自定义 JDBC 驱动目录 |
| `DATASOURCE_URL` | 容器内 H2 文件库 | 可覆盖为外部 MySQL / PostgreSQL 等 |
| `TZ` | `Asia/Shanghai` | 时区 |

数据持久化目录：`/app/data`（数据库）、`/app/work`（快照 / 报告）、`/app/logs`（日志）、`/app/lib`（驱动 JAR）。

### 快速上手

1. 登录后进入「新建扫描」，上传项目 ZIP 或直接粘贴代码（可用 `samples/SampleBadCode.java` 快速体验）；
2. 扫描完成自动跳转结果页：评分环、五级问题分布、门禁结论、逐条问题（可展开代码上下文），一键导出 HTML / PDF 报告；
3. 「质量门禁」页查看评分趋势与门禁结果，管理员可「自定义分值」调整每级扣分与阈值；
4. 可选增强：「AI 配置」接入大模型开启深度评审；「邮件管理」配置发件邮箱与收件人，扫描时勾选「邮件通知」自动推送报告；
5. CI 联动：在「CI/CD 集成」页创建触发器与令牌，流水线推送 Webhook 即可自动扫描、回写状态与评论，见下文详细指南。

## 🔌 CI/CD 集成指南

无需改源码、无需装插件：在平台侧建一个触发器，push / MR 事件即自动触发扫描；扫描完成按质量门禁结论回写 commit status 与 MR/PR 评论，流水线据此阻断合并。支持 GitHub Actions、GitLab CI、Gitee Go 与任意通用 CI。

### 第一步：新建触发器，拿到 Webhook 地址与密钥

「CI/CD 集成」页 → 「触发器」页签 → 「新建触发器」：

| 字段 | 填写说明 |
|------|----------|
| 名称 | 便于识别即可，如「核心服务-主分支扫描」 |
| 平台 | GitHub Actions / GitLab CI / Gitee Go / 通用，决定事件解析格式与回写 API |
| 平台地址 | 自动填官方云地址；企业自建版改成内网地址（如 `https://gitlab.company.com`） |
| 分支过滤 | glob 模式逗号分隔，如 `develop,release/**`；留空 = 所有分支；不匹配的事件直接跳过不扫描 |
| 仓库范围（可选） | `owner/repo`（GitLab 子组 `group/project` 亦可）；填写后仅接受该仓库的事件，防止同一 Webhook 地址被其他仓库误触发；留空 = 不限制 |
| 仓库账号 / 仓库令牌 | 仅**私有仓库** HTTPS 克隆需要（GitHub 账号可填 `x-access-token`、GitLab 可填 `oauth2`，令牌填 Personal Access Token）；**公开仓库留空**；令牌 AES 加密落库，编辑时留空表示不修改 |
| 跳过单元测试 / 分析测试代码 / 启用 AI 评审 / 扫描完成回评 MR/PR | 扫描行为开关：AI 评审消耗模型额度、回评会向平台写评论，按需开启 |
| 开启发信 / 通知收件人 | 勾选后每次触发扫描完成自动向所选收件人发送报告邮件（HTML 摘要 + PDF 附件）；需先在「邮件管理」启用一个发件邮箱并维护收件人 |

创建成功的弹窗会给出该触发器的 **Webhook 地址**与 **Webhook 密钥**：**密钥仅完整展示这一次**（库中加密存储），请立即复制保存；错过可在触发器行「重置密钥」重新生成（重置后需同步更新平台侧配置）。

### 第二步：在代码平台配置推送（二选一）

**方式 A：平台侧配置 Webhook（推荐，push / MR 自动触发）**

| 平台 | 配置路径 |
|------|----------|
| GitHub | 仓库 `Settings → Webhooks → Add webhook`：Payload URL = Webhook 地址，Content type = `application/json`，Secret = Webhook 密钥，勾选 `Pushes` 与 `Pull requests` |
| GitLab | 项目 `Settings → Webhooks`：URL = Webhook 地址，Secret token = Webhook 密钥，勾选 Push / Merge request events |
| Gitee | 仓库 `管理 → WebHooks → 添加 webhook`：URL = Webhook 地址，密码 = Webhook 密钥 |

GitHub 投递带 `X-Hub-Signature-256` HMAC-SHA256 签名，GitLab 带 `X-Gitlab-Token`，Gitee 带 `X-Gitee-Token`；本平台按平台约定自动校验，签名不匹配的请求直接拒绝（401）。

**方式 B：CI 脚本主动调用（任意流水线适用，含自托管 runner）**

推送 JSON 事件（以 GitHub 签名为例）：

```bash
BODY='{"ref":"refs/heads/master","head_commit":{"id":"<commit-sha>"},"repository":{"clone_url":"https://github.com/owner/repo.git"}}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "<Webhook密钥>" -hex | awk '{print $2}')
curl -X POST "<Webhook地址>?platform=GITHUB" \
  -H "Content-Type: application/json" \
  -H "X-GitHub-Event: push" \
  -H "X-Hub-Signature-256: sha256=$SIG" \
  --data-binary "$BODY"
```

或跳过 Git 克隆、直接上传源码 ZIP（流水线已有产物、或 runner 访问不到 Git 仓库时适用）：

```bash
curl -X POST "<Webhook地址>/upload" \
  -H "Authorization: Bearer <Webhook密钥或访问令牌>" \
  -F "file=@source.zip" -F "branch=master" -F "commitId=<commit-sha>"
```

`Authorization: Bearer` 既可填 Webhook 密钥，也可填「访问令牌」页签创建的系统访问令牌（`X-Ci-Token` 头同样支持）；访问令牌可设过期时间、可单独吊销，适合分发给多条流水线。

### 第三步：查看扫描记录与结果回写

1. 事件到达后按「签名 → 仓库范围 → 分支过滤」顺序校验，通过才创建扫描记录；随后**异步克隆仓库**（私有库自动使用所配凭据，按记录隔离工作目录），打包 ZIP 走与页面扫描完全相同的流程；
2. 「扫描记录」页签查看每次触发的状态流转（PENDING → RUNNING → SUCCESS / FAILED），可按触发器筛选，点击跳转扫描结果页；
3. 扫描完成自动向对应 commit 回写 **commit status**：状态取质量门禁结论（success / failure，描述含评分与五级问题计数），扫描失败回写 error；平台侧配置分支保护「状态检查必须通过」后，门禁不达标的提交将无法合并；
4. 勾选「扫描完成回评 MR/PR」且事件携带 MR/PR 号时，额外在该 MR/PR 下评论：评分、门禁结论、五级计数、技术债与 Top 问题清单，附完整报告链接（链接域名取自 `app.webhook-base-url`）；
5. 勾选「开启发信」的触发器，扫描结束后同步向所选收件人推送报告邮件，送达状态在「扫描历史」页可见。

> 注意：方式 A 要求 Webhook 地址能被代码平台直接访问（内网部署需公网映射或内网穿透）；方式 B 的 ZIP 上传只要求 runner 能访问本服务，全内网环境亦可使用。

## 📁 项目结构

```
jargus/
├── src/main/java/com/qqmu/jargus/
│   ├── checker/         # 检查器框架与内置检查器（AST / 正则 / 扫描级）
│   ├── config/          # 启动初始化、Bean 配置
│   ├── controller/      # 页面控制器 + REST API
│   ├── datasource/      # 动态多数据源与方言适配
│   ├── dto/ entity/ mapper/   # 数据模型（MyBatis-Plus）
│   ├── llm/             # 多厂商 LLM 协议适配层
│   ├── security/        # JWT、角色切面、用户上下文
│   ├── service/         # 扫描、评分门禁、AI 评审、报告、邮件通知、CI 回调等
│   └── util/            # 工具类
├── src/main/resources/
│   ├── db/              # H2 / MySQL 双方言 DDL
│   ├── i18n/            # 中英文消息包
│   ├── security/        # 内置 CVE 漏洞库
│   ├── templates/       # Thymeleaf 页面（19 个）
│   ├── static/          # CSS / JS / 本地图标（零 CDN）
│   └── application.yml
├── samples/             # 示例坏代码（可直接粘贴体验）
├── scripts/             # Docker 构建 / 运行脚本（含国内镜像站版）
├── Dockerfile           # 多阶段构建
└── docker-compose.yml
```

## 🤝 总结与反馈

这个项目从"让中小团队用最低成本获得完整代码质量闭环"出发，做到了：**一个容器交付、零外部依赖、离线可用、中文原生、AI 可选增强、评分门禁可自定义、CI 全链路联动、报告邮件直达**。它还在持续演进，规则库、漏洞库、报告形态都会不断扩充。

欢迎使用、欢迎 Star、欢迎提出建议与问题反馈：

- 提交 Issue / Pull Request
- **QQ：817094 / 2912167928**
- **微信：hua47609**

你的每一条反馈都会让它变得更好。

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源，可自由使用、修改与商用。
