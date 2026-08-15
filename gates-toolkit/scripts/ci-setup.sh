#!/bin/bash
# gates-toolkit CI 一键准备脚本 (Linux x86_64/arm64)
#
# 流程：
#   1. fetch-binaries 下载预编译二进制（容忍部分失败）
#   2. 校验本平台二进制；sql-guard 缺失时从源码兜底构建
#      （v0.2.0 Release 未提供 linux-amd64 产物，见 versions.toml 注释）
#   3. 确保 java 可用（java-guard 的 java-parser.jar 需要 JRE）
#   4. setup-gates 安装到目标项目
#
# 用法（参数原样透传给 setup-gates.sh）：
#   bash scripts/ci-setup.sh <target> multi-module <sql-module> <java-modules...>
#   bash scripts/ci-setup.sh <target> spring-boot
#
# 环境变量：
#   SQL_GUARD_REPO  sql-guard 源码仓库（兜底构建用）
#   CI_SETUP_SKIP_JAVA=1  跳过 java 检查/安装

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLKIT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

TARGET="${1:-}"
if [ -z "$TARGET" ]; then
  echo "Usage: $0 <target-project> <project-type> [extra-args...]" >&2
  exit 1
fi

SQL_GUARD_REPO="${SQL_GUARD_REPO:-https://cnb.cool/mikezhu/sql-guard.git}"

echo "========================================="
echo "  gates-toolkit CI 准备"
echo "========================================="

# 平台二进制名（与 setup-gates.sh 保持一致）
OS_TYPE="$(uname -s)"
ARCH_TYPE="$(uname -m)"
if [ "$OS_TYPE" = "Linux" ] && { [ "$ARCH_TYPE" = "aarch64" ] || [ "$ARCH_TYPE" = "arm64" ]; }; then
  FETCH_PLATFORM="linux-arm64"
  WAN_BIN="wan-linux-arm64"; SQL_BIN="sqlguard-linux-arm64"; JG_BIN="java-guard-linux-arm64"
elif [ "$OS_TYPE" = "Linux" ]; then
  FETCH_PLATFORM="linux-amd64"
  WAN_BIN="wan-linux-amd64"; SQL_BIN="sqlguard-linux-amd64"; JG_BIN="java-guard-linux-amd64"
else
  FETCH_PLATFORM="windows-amd64"
  WAN_BIN="wan.exe"; SQL_BIN="sqlguard.exe"; JG_BIN="java-guard.exe"
fi

# 1) 下载预编译二进制（容忍失败：某些平台产物可能未发布，由后续步骤兜底或报错）
echo "==> 下载预编译二进制 ($FETCH_PLATFORM)"
bash "$SCRIPT_DIR/fetch-binaries.sh" "$FETCH_PLATFORM" || echo "warn: fetch-binaries 部分失败，进入校验/兜底流程"

# 2) 校验 wan / java-guard（这两个必须能从 Release 下载，缺失即为配置错误）
for b in "$TOOLKIT_ROOT/bin/wan/$WAN_BIN" \
         "$TOOLKIT_ROOT/bin/java-guard/$JG_BIN" \
         "$TOOLKIT_ROOT/bin/java-guard/java-parser/java-parser.jar"; do
  if [ ! -f "$b" ]; then
    echo "Error: 必需二进制缺失且无法兜底: $b" >&2
    echo "       请检查 versions.toml 中的 URL 是否有效" >&2
    exit 1
  fi
done

# 2b) sql-guard 缺失 → 源码兜底构建
if [ ! -f "$TOOLKIT_ROOT/bin/sql-guard/$SQL_BIN" ]; then
  echo "==> sql-guard 预编译二进制缺失，从源码构建（$SQL_GUARD_REPO）"
  command -v cargo >/dev/null 2>&1 || { echo "Error: 源码构建需要 cargo（Rust 工具链）" >&2; exit 1; }
  command -v git   >/dev/null 2>&1 || { echo "Error: 源码构建需要 git" >&2; exit 1; }

  SQLGUARD_VERSION=$(awk '/^\[sql-guard\]/{f=1;next} /^\[/{f=0} f && /^version/{gsub(/.*= *"/,""); gsub(/".*/,""); print; exit}' "$TOOLKIT_ROOT/versions.toml")
  SRC_DIR="$(mktemp -d)/sql-guard"
  # 确保脚本退出时清理临时构建目录
  trap 'rm -rf "$(dirname "$SRC_DIR")"' EXIT
  if [ -n "$SQLGUARD_VERSION" ]; then
    git clone --depth 1 --branch "v$SQLGUARD_VERSION" "$SQL_GUARD_REPO" "$SRC_DIR" 2>/dev/null \
      || git clone --depth 1 "$SQL_GUARD_REPO" "$SRC_DIR"
  else
    git clone --depth 1 "$SQL_GUARD_REPO" "$SRC_DIR"
  fi
  (cd "$SRC_DIR" && cargo build --release --bin sqlguard --locked)
  cp "$SRC_DIR/target/release/sqlguard" "$TOOLKIT_ROOT/bin/sql-guard/$SQL_BIN"
  chmod +x "$TOOLKIT_ROOT/bin/sql-guard/$SQL_BIN"
  [ -n "$SQLGUARD_VERSION" ] && echo "$SQLGUARD_VERSION" > "$TOOLKIT_ROOT/bin/sql-guard/.version"
  echo "  ✓ sql-guard 源码构建完成: $("$TOOLKIT_ROOT/bin/sql-guard/$SQL_BIN" --version 2>&1 | head -1)"
fi

# 3) java（java-guard 运行 java-parser.jar 需要）
if [ "${CI_SETUP_SKIP_JAVA:-}" != "1" ] && ! command -v java >/dev/null 2>&1; then
  echo "==> 未检测到 java，尝试安装 default-jdk-headless"
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq && apt-get install -y -qq default-jdk-headless >/dev/null 2>&1 || true
  fi
  command -v java >/dev/null 2>&1 || { echo "Error: java 不可用，且无法自动安装" >&2; exit 1; }
fi

# 4) setup-gates 安装到目标项目（参数透传）
echo "==> 安装门禁到项目"
bash "$SCRIPT_DIR/setup-gates.sh" "$@"

echo ""
echo "========================================="
echo "  CI 门禁准备完成，可执行："
echo "    tools/wan/bin/wan run tools/wan/workflows/ci-unix.yml -C . --quiet"
echo "  （PR 增量：先 export BASE_REF=origin/<base-branch>）"
echo "========================================="
