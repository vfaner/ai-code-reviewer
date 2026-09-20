# ============================================================
# AI Code Reviewer · 全量镜像构建
# 前端为 Thymeleaf 服务端渲染（无 Node 构建步骤），静态资源随 jar 打包。
# 网络受限环境可通过构建参数使用镜像站，例如：
#   docker build \
#     --build-arg MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 \
#     --build-arg MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public \
#     --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre-jammy \
#     --build-arg APT_MIRROR=mirrors.aliyun.com \
#     -t ai-code-reviewer:1.0.0 .
# ============================================================
# 基础镜像（全局 ARG，供各 FROM 使用；默认官方源，可用镜像站覆盖）
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-17
ARG JRE_IMAGE=eclipse-temurin:17-jre-jammy

# ============================================================
# Stage 1: 构建（Maven 打包，Thymeleaf 模板与静态资源在 src/main/resources 内）
# ============================================================
FROM ${MAVEN_IMAGE} AS builder

ARG MAVEN_MIRROR_URL=""
WORKDIR /app

# 可选：配置 Maven 镜像站
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 \
      && printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror><id>build-mirror</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; \
    fi

# 先复制 pom 预热依赖缓存
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q dependency:go-offline

# 复制源码并打包
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q clean package -DskipTests

# ============================================================
# Stage 2: 运行时（含中文字体，保证 PDF 报告中文正常）
# ============================================================
FROM ${JRE_IMAGE} AS runtime

# fontconfig + 思源黑体（Noto CJK）保证 PDF 中文渲染；curl 用于健康检查
# 网络受限环境可传 --build-arg APT_MIRROR=mirrors.aliyun.com
ARG APT_MIRROR=""
RUN if [ -n "$APT_MIRROR" ]; then \
        sed -i "s|http://archive.ubuntu.com|http://${APT_MIRROR}|g; s|http://security.ubuntu.com|http://${APT_MIRROR}|g; s|http://ports.ubuntu.com|http://${APT_MIRROR}|g" \
            /etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true; \
    fi \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        fontconfig \
        fonts-noto-cjk \
        fonts-wqy-zenhei \
        curl \
        tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

# 非 root 用户运行
RUN useradd -r -u 1001 -m -d /app appuser \
    && mkdir -p /app/data /app/work /app/logs /app/lib/custom \
    && chown -R appuser:appuser /app

WORKDIR /app
COPY --from=builder /app/target/ai-code-reviewer.jar /app/app.jar
RUN chown appuser:appuser /app/app.jar

USER appuser

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Shanghai" \
    TZ=Asia/Shanghai

EXPOSE 8080

VOLUME ["/app/data", "/app/work", "/app/logs", "/app/lib"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
