# =============================================================================
# Baafoo Dockerfile — 多阶段构建
# 阶段1: 构建前端 (Node.js + pnpm)
# 阶段2: 构建后端 JAR (Maven + JDK 8)
# 阶段3: 运行时 (JRE 8)
# =============================================================================

# ---------- 阶段1: 构建前端 ----------
FROM node:18-alpine AS frontend-build

RUN npm install -g pnpm

WORKDIR /app/web
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY web/ ./
RUN pnpm run build

# ---------- 阶段2: 构建后端 ----------
# 使用 server 模块作为主构建入口，shade plugin 会打包所有依赖
FROM maven:3.8-openjdk-8-slim AS backend-build

WORKDIR /build

# 先复制所有 pom.xml，利用 Docker 缓存加速依赖下载
COPY pom.xml ./pom.xml
COPY baafoo-core/pom.xml ./baafoo-core/pom.xml
COPY baafoo-plugin-api/pom.xml ./baafoo-plugin-api/pom.xml
COPY baafoo-agent/pom.xml ./baafoo-agent/pom.xml
COPY baafoo-server/pom.xml ./baafoo-server/pom.xml
COPY baafoo-cli/pom.xml ./baafoo-cli/pom.xml

# 下载依赖
RUN mvn dependency:go-offline -B

# 复制源码
COPY baafoo-core/src ./baafoo-core/src
COPY baafoo-plugin-api/src ./baafoo-plugin-api/src
COPY baafoo-agent/src ./baafoo-agent/src
COPY baafoo-server/src ./baafoo-server/src
COPY baafoo-cli/src ./baafoo-cli/src

# 将前端构建产物放入 server 的 webconsole 资源目录
COPY --from=frontend-build /app/web/dist ./baafoo-server/src/main/resources/webconsole

# 构建 server 的 fat JAR（跳过测试）
RUN mvn clean package -pl baafoo-server -am -DskipTests -Djacoco.skip=true -B

# ---------- 阶段3: 运行时 ----------
FROM openjdk:8-jre-alpine

LABEL maintainer="Baafoo"

# 时区设置
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apk del tzdata

# 创建非 root 用户
RUN addgroup -S baafoo && adduser -S baafoo -G baafoo

WORKDIR /app

# 复制构建产物
COPY --from=backend-build /build/baafoo-server/target/baafoo-server-*.jar ./baafoo-server.jar
COPY --from=backend-build /build/baafoo-agent/target/baafoo-agent-*.jar ./baafoo-agent.jar

# 创建数据目录
RUN mkdir -p /home/baafoo/.baafoo/data && \
    chown -R baafoo:baafoo /app /home/baafoo/.baafoo

USER baafoo

# Server HTTP 端口
EXPOSE 8084

ENV JAVA_OPTS="" \
    BAAFOO_HTTP_PORT="8084" \
    BAAFOO_DB_TYPE="h2"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/baafoo-server.jar"]
