# 百目 JArgus v2.0.0

品牌焕新：「AI Code Reviewer · AI 代码评审平台」正式更名为「**百目 JArgus · Java 代码评审平台**」。

百目，典出《礼记·大学》"十目所视，十手所指"——百目所视，代码无所遁形；JArgus = **Jar + Argus**，既是 Java 之 jar 的双关，也是希腊神话中永不闭目的百眼巨人。

## 本次更新

### 全新品牌

- 产品名、界面标题、登录页、报告页头页脚、CI 回写文案全面切换为「百目 JArgus」
- 全新眼睛母题图标（favicon 与页头 logo），延续靛蓝→粉渐变视觉
- 代码包根迁移：`com.aicodereview` → `com.qqmu.jargus`；可执行包更名 `jargus.jar`
- Docker 镜像更名 `jargus`，数据卷前缀 `jargus-*`
- 仓库地址：github.com/vfaner/jargus 与 gitee.com/super_rgh/jargus

### 检测精度（自 v1.0.0 以来）

- NULL_CHECK：框架注入参数白名单 + 全项目调用点非空论证后抑制，反射/SPI/死代码保守保留
- MAGIC_NUMBER：豁免参与算术/位运算/取模分桶的字面量（单位换算、掩码等计算语义）
- EMPTY_CATCH：仅报「空且无任何说明」的 catch，豁免 ignored/expected 命名约定与体内注释两种显式意图
- SEC_HARDCODED_SECRET：豁免四类误报——`__xxx__` 连通性哨兵假凭据、标识名自称 url/uri/endpoint 的公开端点 URL、值与变量名互证回显的公开标识常量、指纹/去重语境的 MD5/SHA-1；webhookSecret 型 URL 密钥、`password="password"` 整词弱口令、`user:pass@` 内嵌凭据仍照报
- 内置默认 AES 密钥启动时输出 WARN，提示生产环境覆盖

### 升级须知（自 v1.x）

- Cookie 与本地存储键全面更名（`jargus_token` 等），升级后需**重新登录一次**
- 内嵌 H2 数据文件由 `data/aicodereview` 更名为 `data/jargus`：覆盖升级时将旧文件重命名（或复制）为 `data/jargus.mv.db` 即可完整保留任务与配置；「数据库配置」页中仍指向旧路径的连接行请同步更新
- CI 触发器若配置了仓库范围（repo_scope）且仓库同步改名，请更新为新仓库名，否则 webhook 将因范围不匹配被跳过
- 静态加密密钥（`app.crypto-key`）机制不变，已加密的 API Key / 数据库密码 / CI Token 不受影响

功能主体与 v1.0.0 一致：19 个内置检查器、五级评分与质量门禁、可选 AI 增强评审、双语 HTML/PDF 报告、动态多数据源、CI/Webhook 集成。
