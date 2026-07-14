# =============================================================================
# Baafoo Server Dockerfile
# 前提：先在本地执行 mvnw clean package -DskipTests 构建好 JAR
# 前端构建已由 Maven exec plugin 自动完成（npm install && npm run build）
# =============================================================================

FROM eclipse-temurin:8-jre-alpine

LABEL maintainer="Baafoo"

# 时区设置
RUN apk add --no-cache tzdata wget && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apk del tzdata

# 创建非 root 用户
RUN addgroup -S baafoo && adduser -S baafoo -G baafoo

WORKDIR /app

# 复制本地构建好的 JAR 文件
COPY baafoo-server/target/baafoo-server-*.jar ./baafoo-server.jar
COPY baafoo-agent/target/baafoo-agent-*.jar ./baafoo-agent.jar

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
