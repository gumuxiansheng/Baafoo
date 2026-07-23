#!/usr/bin/env bash
# Baafoo Web 控制台 UI 自动化测试运行脚本（Bash）
#
# 完整流程：
#   1. 检测 http://localhost:8084 是否可达；不可达则用 docker compose 启动 staging 集群
#      （server + postgres + app-env-a + app-env-b + staging-init）。
#   2. 等待 server 健康（GET /__baafoo__/api/status 返回 200）。
#   3. 从容器内 .admin-credentials 抽取 admin 一次性密码，写入
#      testing/7_Others/tmp/.admin-password；也支持通过环境变量
#      BAAFOO_ADMIN_PASSWORD 直接提供（优先级最高）。
#   4. 确保 web/node_modules 已安装。
#   5. 在 web/ 目录运行 npx playwright test（globalSetup 会自动登录 admin
#      并生成 admin-storage.json）。
#   6. 把 HTML 报告复制到 testing/6_UITest/playwright-report/。
set -euo pipefail

# ── 路径常量 ────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WEB_DIR="$PROJECT_ROOT/web"
TMP_DIR="$PROJECT_ROOT/testing/7_Others/tmp"
PASSWORD_FILE="$TMP_DIR/.admin-password"
REPORT_SRC_DIR="$TMP_DIR/playwright-report"
REPORT_DEST_DIR="$SCRIPT_DIR/playwright-report"

BASE_URL="${BAAFOO_BASE_URL:-http://localhost:8084}"
STATUS_URL="$BASE_URL/__baafoo__/api/status"

NO_AUTO_START=0
UPDATE_SNAPSHOTS=0
GREP_FILTER=""

# ── 参数解析 ────────────────────────────────────────────────────────────
usage() {
    cat <<EOF
用法: $0 [选项]
  --no-auto-start       跳过 docker compose 自动启动，仅检测 server 是否可达
  --update-snapshots    透传给 playwright test --update-snapshots
  --grep PATTERN        只跑标题匹配 PATTERN 的用例
  --base-url URL        覆盖默认 base URL（默认 http://localhost:8084）
  -h, --help            显示本帮助
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-auto-start)    NO_AUTO_START=1; shift ;;
        --update-snapshots) UPDATE_SNAPSHOTS=1; shift ;;
        --grep)             GREP_FILTER="$2"; shift 2 ;;
        --base-url)         BASE_URL="$2"; STATUS_URL="$BASE_URL/__baafoo__/api/status"; shift 2 ;;
        -h|--help)          usage ;;
        *) echo "未知参数: $1"; usage ;;
    esac
done

# ── 辅助函数 ────────────────────────────────────────────────────────────
step()  { printf '\n\033[36m==> %s\033[0m\n' "$1"; }
ok()    { printf '    \033[32m✔ %s\033[0m\n' "$1"; }
warn()  { printf '    \033[33m! %s\033[0m\n' "$1"; }
err()   { printf '    \033[31mx %s\033[0m\n' "$1"; }

server_ready() {
    curl -sf -o /dev/null --max-time 3 "$STATUS_URL" 2>/dev/null
}

# ── 1. 检测 / 启动 server ───────────────────────────────────────────────
step "检测 Baaoo Server ($BASE_URL)"
if server_ready; then
    ok "Server 已在运行"
elif [[ "$NO_AUTO_START" -eq 1 ]]; then
    err "Server 不可达且 --no-auto-start 已指定，退出。"
    exit 1
else
    warn "Server 不可达，启动 staging 集群（docker compose）..."
    cd "$PROJECT_ROOT"
    docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d \
        server postgres app-env-a app-env-b staging-init

    step "等待 server 健康（最多 120s）"
    deadline=$(( $(date +%s) + 120 ))
    ready=0
    while [[ $(date +%s) -lt $deadline ]]; do
        if server_ready; then ready=1; break; fi
        sleep 3
        printf '.'
    done
    echo
    if [[ $ready -eq 0 ]]; then
        err "server 在 120s 内未就绪。请手动检查：docker compose -f docker-compose.yml -f docker-compose.staging.yml logs server"
        exit 1
    fi
    # app-env-a/b 需要额外几秒注册到 server
    warn "等待 agent 注册（8s）..."
    sleep 8
    ok "staging 集群就绪"
fi

# ── 2. 准备 admin 密码 ─────────────────────────────────────────────────
step "准备 admin 密码"
if [[ -n "${BAAFOO_ADMIN_PASSWORD:-}" ]]; then
    ok "使用环境变量 BAAFOO_ADMIN_PASSWORD"
else
    mkdir -p "$TMP_DIR"
    extracted=""

    # 优先从 docker 容器抽取
    for c in baafoo-server baafoo-staging-server; do
        # 用 find 定位 .admin-credentials —— 路径随 dataDir 配置变化
        # （staging 配置 dataDir: "~/.baafoo/data"，Java 不展开 ~，实际落在 /app/~/.baafoo/data/）
        cred_path="$(docker exec "$c" sh -c 'find / -name .admin-credentials -type f 2>/dev/null | head -1' 2>/dev/null || true)"
        if [[ -n "$cred_path" ]]; then
            raw="$(docker exec "$c" sh -c "cat \"$cred_path\" 2>/dev/null" 2>/dev/null || true)"
            if [[ -n "$raw" ]]; then
                # 文件格式: "  Pass:  XXX"
                pass_line="$(echo "$raw" | grep 'Pass:' | sed 's/Pass://g' | head -n1 | awk '{$1=$1};1')"
                if [[ -n "$pass_line" ]]; then extracted="$pass_line"; break; fi
            fi
        fi
    done

    # 回退：本地运行的 server。Java 不展开 ~，所以候选路径包括字面 ~ 目录。
    if [[ -z "$extracted" ]]; then
        local_candidates=(
            "$HOME/.baafoo/data/.admin-credentials"
            "$PWD/~/.baafoo/data/.admin-credentials"
            "$PROJECT_ROOT/~/.baafoo/data/.admin-credentials"
        )
        for lc in "${local_candidates[@]}"; do
            if [[ -f "$lc" ]]; then
                pass_line="$(grep 'Pass:' "$lc" | sed 's/Pass://g' | head -n1 | awk '{$1=$1};1')"
                if [[ -n "$pass_line" ]]; then extracted="$pass_line"; break; fi
            fi
        done
    fi

    if [[ -n "$extracted" ]]; then
        printf '%s' "$extracted" > "$PASSWORD_FILE"
        export BAAFOO_ADMIN_PASSWORD="$extracted"
        ok "密码已抽取并写入 $PASSWORD_FILE"
    else
        err "无法自动获取 admin 密码。请任选其一："
        echo "    1) export BAAFOO_ADMIN_PASSWORD='<admin密码>'"
        echo "    2) 手工把密码写入 $PASSWORD_FILE"
        echo "    3) 若 server 是本地 java -jar 启动，查看 \$HOME/.baafoo/data/.admin-credentials"
        exit 1
    fi
fi

# ── 3. 安装 web 依赖 ───────────────────────────────────────────────────
step "检查 web 依赖"
if [[ ! -d "$WEB_DIR/node_modules" ]]; then
    warn "node_modules 缺失，运行 npm install..."
    (cd "$WEB_DIR" && npm install)
    ok "依赖安装完成"
else
    ok "node_modules 已存在"
fi

# 确保 playwright CLI 可用
if [[ ! -x "$WEB_DIR/node_modules/.bin/playwright" ]]; then
    warn "playwright CLI 缺失，重新安装依赖..."
    (cd "$WEB_DIR" && npm install)
fi

# ── 4. 运行 Playwright ─────────────────────────────────────────────────
step "运行 Playwright UI 测试"
pw_args=(test)
if [[ "$UPDATE_SNAPSHOTS" -eq 1 ]]; then pw_args+=(--update-snapshots); fi
if [[ -n "$GREP_FILTER" ]]; then pw_args+=(--grep "$GREP_FILTER"); fi

echo "    > npx playwright ${pw_args[*]}"
cd "$WEB_DIR"
test_exit=0
npx playwright "${pw_args[@]}" || test_exit=$?

# ── 5. 复制报告 ────────────────────────────────────────────────────────
step "汇总报告"
if [[ -d "$REPORT_SRC_DIR" ]]; then
    rm -rf "$REPORT_DEST_DIR"
    cp -r "$REPORT_SRC_DIR" "$REPORT_DEST_DIR"
    ok "HTML 报告: $REPORT_DEST_DIR/index.html"
else
    warn "未找到报告源目录 $REPORT_SRC_DIR"
fi

# ── 6. 结果 ────────────────────────────────────────────────────────────
echo
if [[ $test_exit -eq 0 ]]; then
    printf '\033[32m==> UI 测试全部通过 ✔\033[0m\n'
else
    printf '\033[31m==> UI 测试存在失败（exit %d）\033[0m\n' "$test_exit"
fi
exit $test_exit
