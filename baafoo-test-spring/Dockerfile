# =============================================================================
# Baafoo Test Spring Dockerfile — 带Agent的Spring Boot测试应用
# 前提：先在本地执行 mvnw clean package -DskipTests 构建好 JAR
# =============================================================================

FROM eclipse-temurin:8-jre-alpine

LABEL maintainer="Baafoo"

RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apk del tzdata

RUN addgroup -S baafoo && adduser -S baafoo -G baafoo

WORKDIR /app

# 复制本地构建好的 JAR 文件
COPY baafoo-test-spring/target/baafoo-test-spring-*.jar ./baafoo-test-spring.jar
COPY baafoo-agent/target/baafoo-agent-*.jar ./baafoo-agent.jar

# 复制Agent配置（由docker-compose volume覆盖）
COPY baafoo-agent/src/main/resources/baafoo-agent.yml ./baafoo-agent.yml

RUN mkdir -p /home/baafoo/.baafoo/data && \
    chown -R baafoo:baafoo /app /home/baafoo/.baafoo

USER baafoo

EXPOSE 9090

ENV JAVA_OPTS="" \
    BAAFOO_SERVER_HOST="server" \
    BAAFOO_ENV="staging-a" \
    SERVER_PORT=9090

# 以Agent模式启动Spring Boot应用
ENTRYPOINT ["sh", "-c", \
    "java ${JAVA_OPTS} \
     -javaagent:/app/baafoo-agent.jar=config=/app/baafoo-agent.yml \
     -jar /app/baafoo-test-spring.jar \
     --server.port=${SERVER_PORT}"]
