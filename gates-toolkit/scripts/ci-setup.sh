#!/bin/bash
# gates-toolkit CI 一键准备脚本 (Linux x86_64/arm64)
#
# 流程：
#   1. fetch-binaries 下载预编译二进制（容忍部分失败，内置 IPv4 兜底重试）
#   2. 校验/兜底：二进制缺失或不可运行（如 GLIBC 版本不兼容）时从源码构建；
#      java-parser.jar 缺失时用 maven 构建（java-guard 需要）
#   3. 确保 java 可用（java-guard 的 java-parser.jar 需要 JRE）
#   4. setup-gates 安装到目标项目
#
# 用法（参数原样透传给 setup-gates.sh）：
#   bash scripts/ci-setup.sh <target> multi-module <sql-module> <java-modules...>
#   bash scripts/ci-setup.sh <target> spring-boot
#
# 环境变量：
#   SQL_GUARD_REPO   sql-guard 源码仓库（兜底构建用）
#   WAN_REPO         wan 源码仓库（兜底构建用）
#   JAVA_GUARD_REPO  java-guard 源码仓库（兜底构建用）
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
WAN_REPO="${WAN_REPO:-https://cnb.cool/mikezhu/wan.git}"
JAVA_GUARD_REPO="${JAVA_GUARD_REPO:-https://cnb.cool/mikezhu/java-guard.git}"

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

# 从 versions.toml 读取工具配置版本
read_config_version() {
  awk -v sec="$1" '
    $0 ~ "^\\[" sec "\\]" { f=1; next }
    f && $0 ~ "^\\[" { f=0 }
    f && /^version/ { gsub(/.*= *"/, ""); gsub(/".*/, ""); print; exit }
  ' "$TOOLKIT_ROOT/versions.toml"
}

# 版本比较（语义化，支持 1.96.0 与 1.96）
compare_version() {
  printf '%s\n%s\n' "$1" "$2" | sort -V | head -1
}

# 源码兜底构建工具二进制
# 参数：名称 目标路径 仓库 包名 配置版本 最低Rust版本(可选)
# 触发条件：文件缺失，或文件存在但 --version 运行失败（GLIBC 过旧等）
build_tool_binary() {
  local name="$1" dest="$2" repo="$3" pkg="$4" version="$5" min_rust="${6:-}"
  local reason=""

  if [ -f "$dest" ] && "$dest" --version >/dev/null 2>&1; then
    return 0
  elif [ -f "$dest" ]; then
    reason="预编译二进制不可运行（GLIBC 版本不兼容等）"
  else
    reason="预编译二进制缺失"
  fi

  echo "==> $name $reason，从源码构建（$repo）"
  command -v cargo >/dev/null 2>&1 || { echo "Error: 源码构建需要 cargo（Rust 工具链）" >&2; return 1; }
  command -v git   >/dev/null 2>&1 || { echo "Error: 源码构建需要 git" >&2; return 1; }

  if [ -n "$min_rust" ] && command -v rustc >/dev/null 2>&1; then
    local got min_is_first
    got="$(rustc --version | awk '{print $2}')"
    min_is_first="$(compare_version "$min_rust" "$got")"
    if [ "$min_is_first" != "$min_rust" ]; then
      echo "Error: $name 源码构建需要 Rust >= $min_rust（当前 $got）" >&2
      echo "       请升级 CI 镜像的 Rust 工具链，或等待上游发布预编译 musl 版" >&2
      return 1
    fi
  fi

  local work
  work="$(mktemp -d)"
  local src_dir="$work/$name"

  if [ -n "$version" ]; then
    git clone --depth 1 --branch "v$version" "$repo" "$src_dir" 2>/dev/null \
      || git clone --depth 1 "$repo" "$src_dir"
  else
    git clone --depth 1 "$repo" "$src_dir"
  fi

  (
    cd "$src_dir" && cargo build --release --bin "$pkg" --locked
  ) || { echo "Error: $name 源码构建失败" >&2; rm -rf "$work"; return 1; }

  mkdir -p "$(dirname "$dest")"
  cp "$src_dir/target/release/$pkg" "$dest"
  chmod +x "$dest"
  [ -n "$version" ] && echo "$version" > "$(dirname "$dest")/.version"
  rm -rf "$work"
  echo "  ✓ $name 源码构建完成: $("$dest" --version 2>&1 | head -1)"
}

# java-parser.jar 兜底（Java 平台无关，不受 GLIBC 影响；缺失时用 maven 从源码构建）
build_java_parser_jar() {
  local dest="$1" version="$2"
  if [ -f "$dest" ]; then
    return 0
  fi
  echo "==> java-parser.jar 缺失，从源码构建（需要 maven + JDK）"
  command -v mvn >/dev/null 2>&1 || { echo "Error: java-parser.jar 缺失且无 maven 可构建" >&2; return 1; }

  local work
  work="$(mktemp -d)"
  if [ -n "$version" ]; then
    git clone --depth 1 --branch "v$version" "$JAVA_GUARD_REPO" "$work/java-guard" 2>/dev/null \
      || git clone --depth 1 "$JAVA_GUARD_REPO" "$work/java-guard"
  else
    git clone --depth 1 "$JAVA_GUARD_REPO" "$work/java-guard"
  fi

  ( cd "$work/java-guard/java-parser" && mvn -B clean package -DskipTests ) \
    || { echo "Error: java-parser.jar 源码构建失败" >&2; rm -rf "$work"; return 1; }

  mkdir -p "$(dirname "$dest")"
  cp "$work/java-guard/java-parser/target/java-parser.jar" "$dest"
  rm -rf "$work"
  echo "  ✓ java-parser.jar 源码构建完成"
}

# 1) 下载预编译二进制（容忍失败：某些平台产物可能未发布，由后续步骤兜底或报错）
echo "==> 下载预编译二进制 ($FETCH_PLATFORM)"
bash "$SCRIPT_DIR/fetch-binaries.sh" "$FETCH_PLATFORM" || echo "warn: fetch-binaries 部分失败，进入校验/兜底流程"

# 2) 校验/兜底：缺失或不可运行 → 源码构建
#    sql-guard : 静态 musl 产物，无 GLIBC 限制
#    java-guard: 当前 release 为 glibc 动态版，rust >= 1.96 的镜像才能兜底构建
#    wan       : rust-version = 1.96，低版本 Rust 镜像无法构建，需升级 CI 镜像或等 musl release
#    各工具独立尝试，互不阻塞；全部尝试完后统一校验结果
WAN_BIN_PATH="$TOOLKIT_ROOT/bin/wan/$WAN_BIN"
JG_BIN_PATH="$TOOLKIT_ROOT/bin/java-guard/$JG_BIN"
SQL_BIN_PATH="$TOOLKIT_ROOT/bin/sql-guard/$SQL_BIN"

rc_wan=0; rc_jg=0; rc_sql=0; rc_jar=0
build_tool_binary "wan" "$WAN_BIN_PATH" "$WAN_REPO" "wan" "$(read_config_version wan)" "1.96" \
  || rc_wan=$?
build_tool_binary "java-guard" "$JG_BIN_PATH" "$JAVA_GUARD_REPO" "java-guard" "$(read_config_version java-guard)" \
  || rc_jg=$?
build_tool_binary "sql-guard" "$SQL_BIN_PATH" "$SQL_GUARD_REPO" "sqlguard" "$(read_config_version sql-guard)" \
  || rc_sql=$?
build_java_parser_jar "$TOOLKIT_ROOT/bin/java-guard/java-parser/java-parser.jar" "$(read_config_version java-guard)" \
  || rc_jar=$?

if [ "$rc_wan" -ne 0 ] || [ "$rc_jg" -ne 0 ] || [ "$rc_sql" -ne 0 ] || [ "$rc_jar" -ne 0 ]; then
  echo "Error: 源码兜底构建未全部成功（详见上方各工具输出）" >&2
  exit 1
fi

# 2b) 最终硬校验：不可运行的产物到此为止必须全部就绪
for b in "$WAN_BIN_PATH" "$JG_BIN_PATH" "$SQL_BIN_PATH" \
         "$TOOLKIT_ROOT/bin/java-guard/java-parser/java-parser.jar"; do
  if [ ! -f "$b" ]; then
    echo "Error: 必需二进制缺失且无法兜底: $b" >&2
    echo "       请检查 versions.toml 中的 URL 是否有效（上方 fetch-binaries 输出含失败原因）" >&2
    echo "       网络/DNS 瞬态问题可重跑一次（脚本已内置 IPv4 兜底重试）" >&2
    exit 1
  fi
done

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