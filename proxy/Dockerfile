# Baafoo Proxy Dockerfile
# 多阶段构建：编译 Go binary，然后打包到最小镜像

FROM golang:1.21-alpine AS builder

WORKDIR /app

# 复制 Go SDK
COPY sdks/go/baafoo /app/sdks/go/baafoo

# 复制 Proxy 源码
COPY proxy /app/proxy

WORKDIR /app/proxy

# 下载依赖
RUN go mod download

# 编译
RUN CGO_ENABLED=0 go build -o /baafoo-proxy .

# --- 运行阶段 ---
FROM alpine:3.19

RUN apk add --no-cache ca-certificates

COPY --from=builder /baafoo-proxy /usr/local/bin/baafoo-proxy

ENTRYPOINT ["baafoo-proxy"]
CMD ["--config", "/etc/baafoo/proxy.yml"]
