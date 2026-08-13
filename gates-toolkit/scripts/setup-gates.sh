#!/bin/bash
# gates-toolkit 一键安装脚本 (Linux/macOS)
#
# 用法:
#   bash scripts/setup-gates.sh /path/to/project spring-boot
#   bash scripts/setup-gates.sh /path/to/project multi-module "sql-module" "mod1" "mod2" ...
#
# 第二个参数: 项目类型 (spring-boot | multi-module | auto)
# 第三参数起: multi-module 模式下依次指定 [sql-module] [java-modules...]

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLKIT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

TARGET="${1:-}"
PROJECT_TYPE="${2:-auto}"
shift 2 || true
EXTRA_ARGS=("$@")

print_banner() { printf '\033[0;36m=========================================\n  gates-toolkit 一键安装\n=========================================\033[0m\n'; }

print_banner
echo "工具集: $TOOLKIT_ROOT"
echo "目标项目: $TARGET"
echo ""

# 校验
if [ -z "$TARGET" ]; then
  echo "Usage: $0 <target-project> [project-type] [extra-args...]"
  echo ""
  echo "Examples:"
  echo "  $0 /path/to/project spring-boot"
  echo "  $0 /path/to/project multi-module baafoo-server mod1 mod2 mod3"
  echo "  $0 /path/to/project auto"
  exit 1
fi

if [ ! -d "$TARGET" ]; then
  echo "Error: target project not found: $TARGET" >&2
  exit 1
fi

TARGET="$(cd "$TARGET" && pwd)"
GIT_DIR="$TARGET/.git"
if [ ! -d "$GIT_DIR" ]; then
  echo "Warning: target is not a git repo: $TARGET"
fi

# 自动检测
if [ "$PROJECT_TYPE" = "auto" ]; then
  if [ -d "$TARGET/backend/src/main/java" ]; then
    PROJECT_TYPE="spring-boot"
    echo "[auto] 检测到 Spring Boot 单模块布局"
  elif [ -f "$TARGET/pom.xml" ]; then
    PROJECT_TYPE="multi-module"
    echo "[auto] 检测到多模块 Maven"
  else
    echo "Error: 无法自动检测项目类型" >&2
    exit 1
  fi
fi

BACKEND_DIR="backend"
SQL_MODULE=""
JAVA_MODULES=()

if [ "$PROJECT_TYPE" = "multi-module" ]; then
  if [ ${#EXTRA_ARGS[@]} -gt 0 ]; then
    SQL_MODULE="${EXTRA_ARGS[0]}"
    JAVA_MODULES=("${EXTRA_ARGS[@]:1}")
  else
    # 自动猜测 SQL 模块
    SQL_MODULE="$(find "$TARGET" -maxdepth 2 -type d -name 'src' -path '*/main/resources/*' -printf '%h\n' 2>/dev/null \
      | xargs -I{} dirname {} 2>/dev/null \
      | xargs -I{} dirname {} 2>/dev/null \
      | while read m; do
          if [ -d "$m/src/main/resources/mapper" ]; then echo "$(basename "$m")"; break; fi
        done | head -1)"
    [ -n "$SQL_MODULE" ] && echo "[auto] SQL 模块: $SQL_MODULE"

    # 自动猜测 Java 模块
    while IFS= read -r d; do
      [ -d "$d/src/main/java" ] && JAVA_MODULES+=("$(basename "$d")")
    done < <(find "$TARGET" -maxdepth 2 -type d -name 'src' -path '*/main/java' -printf '%h\n' 2>/dev/null \
      | xargs -I{} dirname {} 2>/dev/null \
      | sort -u)
    echo "[auto] Java 模块: ${JAVA_MODULES[*]}"
  fi

  if [ -z "$SQL_MODULE" ]; then
    echo "Error: 未找到 SQL/Mapper 所在模块" >&2
    exit 1
  fi
  if [ ${#JAVA_MODULES[@]} -eq 0 ]; then
    echo "Error: 未找到 Java 模块" >&2
    exit 1
  fi
fi

# 创建 tools 目录
TOOLS_DIR="$TARGET/tools"
if [ -d "$TOOLS_DIR" ]; then
  echo "Warning: $TOOLS_DIR 已存在，将覆盖"
  rm -rf "$TOOLS_DIR"
fi
mkdir -p "$TOOLS_DIR"/{sql-guard/bin,sql-guard/config/rules/{ddl,dml,lib},java-guard/bin,java-guard/java-parser,java-guard/rules/rhai,wan/bin,wan/workflows,hooks}

# 确保工具二进制存在（必要时从配置 URL 下载）
echo "==> 检查工具二进制"
FETCH_SCRIPT="$TOOLKIT_ROOT/scripts/fetch-binaries.sh"

# 检测当前平台二进制名
OS_TYPE="$(uname -s)"
if [ "$OS_TYPE" = "Linux" ]; then
  ARCH_TYPE="$(uname -m)"
  if [ "$ARCH_TYPE" = "aarch64" ] || [ "$ARCH_TYPE" = "arm64" ]; then
    FETCH_PLATFORM="linux-arm64"
    WAN_BIN="wan-linux-arm64"
    SQL_BIN="sqlguard-linux-arm64"
    JG_BIN="java-guard-linux-arm64"
  else
    FETCH_PLATFORM="linux-amd64"
    WAN_BIN="wan-linux-amd64"
    SQL_BIN="sqlguard-linux-amd64"
    JG_BIN="java-guard-linux-amd64"
  fi
else
  # Windows (Git Bash / MSYS)
  FETCH_PLATFORM="windows-amd64"
  WAN_BIN="wan.exe"
  SQL_BIN="sqlguard.exe"
  JG_BIN="java-guard.exe"
fi

if [ -f "$FETCH_SCRIPT" ]; then
  need_fetch=false
  for b in \
    "$TOOLKIT_ROOT/bin/wan/$WAN_BIN" \
    "$TOOLKIT_ROOT/bin/sql-guard/$SQL_BIN" \
    "$TOOLKIT_ROOT/bin/java-guard/$JG_BIN" \
    "$TOOLKIT_ROOT/bin/java-guard/java-parser/java-parser.jar"; do
    [ -f "$b" ] || need_fetch=true
  done
  if [ "$need_fetch" = "true" ]; then
    echo "  二进制不完整，执行下载..."
    bash "$FETCH_SCRIPT" "$FETCH_PLATFORM"
    if [ $? -ne 0 ]; then
      echo "Error: 二进制下载失败，请检查 versions.toml 中的 URL 配置" >&2
      exit 1
    fi
  else
    echo "  二进制已存在，跳过下载"
  fi
fi

# 复制工具二进制到目标项目（统一输出为 wan / sqlguard / java-guard 无后缀名，Linux 可直接执行）
echo "==> 复制工具二进制"
# 格式: 工具目录名:源二进制名:输出目录:输出文件名
# 注意 sql-guard 的输出名必须是 sqlguard（wan workflow / hook / 验证步骤均使用该名）
BIN_MAP="wan:$WAN_BIN:wan/bin:wan sql-guard:$SQL_BIN:sql-guard/bin:sqlguard java-guard:$JG_BIN:java-guard/bin:java-guard"
for entry in $BIN_MAP; do
  tool_dir="$(echo "$entry" | cut -d: -f1)"
  bin_name="$(echo "$entry" | cut -d: -f2)"
  out_dir="$(echo "$entry" | cut -d: -f3)"
  out_name="$(echo "$entry" | cut -d: -f4)"
  src="$TOOLKIT_ROOT/bin/$tool_dir/$bin_name"
  dst="$TOOLS_DIR/$out_dir/$out_name"
  if [ -f "$src" ]; then
    cp "$src" "$dst"
    chmod +x "$dst" 2>/dev/null || true
  else
    echo "Error: 二进制不存在: $src" >&2
    exit 1
  fi
done
# java-parser.jar
cp "$TOOLKIT_ROOT/bin/java-guard/java-parser/java-parser.jar" "$TOOLS_DIR/java-guard/java-parser/"
# 规则文件
cp "$TOOLKIT_ROOT/bin/sql-guard/rules/ddl/"*.rhai "$TOOLS_DIR/sql-guard/config/rules/ddl/"
cp "$TOOLKIT_ROOT/bin/sql-guard/rules/dml/"*.rhai "$TOOLS_DIR/sql-guard/config/rules/dml/"
cp "$TOOLKIT_ROOT/bin/sql-guard/rules/lib/"*.rhai "$TOOLS_DIR/sql-guard/config/rules/lib/"
cp "$TOOLKIT_ROOT/bin/java-guard/rules/"*.yml "$TOOLS_DIR/java-guard/rules/"
cp "$TOOLKIT_ROOT/bin/java-guard/rules/rhai/"*.rhai "$TOOLS_DIR/java-guard/rules/rhai/"

# 渲染 sql-guard 配置
echo "==> 生成 sql-guard 配置"
if [ "$PROJECT_TYPE" = "spring-boot" ]; then
  cp "$TOOLKIT_ROOT/templates/sql-guard/sql-guard-spring-boot.toml" "$TOOLS_DIR/sql-guard/sqlguard.toml"
else
  sed "s|{{SQL_MODULE}}|$SQL_MODULE|g" \
    "$TOOLKIT_ROOT/templates/sql-guard/sql-guard-multi-module.toml" \
    > "$TOOLS_DIR/sql-guard/sqlguard.toml"
fi
cp "$TOOLKIT_ROOT/templates/sql-guard/sqlguard.rules.toml" "$TOOLS_DIR/sql-guard/"

# 渲染 java-guard 配置
echo "==> 生成 java-guard 配置"
if [ "$PROJECT_TYPE" = "spring-boot" ]; then
  cp "$TOOLKIT_ROOT/templates/java-guard/java-guard--spring-boot.yml" "$TOOLS_DIR/java-guard/java-guard.yml"
else
  MODULES_LIST="${JAVA_MODULES[*]}"
  sed "s|{{MODULES_LIST}}|$MODULES_LIST|g" \
    "$TOOLKIT_ROOT/templates/java-guard/java-guard--multi-module.yml" \
    > "$TOOLS_DIR/java-guard/java-guard.yml"
fi
cp "$TOOLKIT_ROOT/templates/java-guard/gate-config.yml" "$TOOLS_DIR/java-guard/"

# 渲染 wan workflow
echo "==> 生成 wan workflow"
if [ "$PROJECT_TYPE" = "spring-boot" ]; then
  sed "s|{{BACKEND_DIR}}|$BACKEND_DIR|g" \
    "$TOOLKIT_ROOT/templates/wan/workflows/pre-commit--spring-boot.yml" \
    > "$TOOLS_DIR/wan/workflows/pre-commit-win.yml"
  sed "s|{{BACKEND_DIR}}|$BACKEND_DIR|g" \
    "$TOOLKIT_ROOT/templates/wan/workflows/pre-commit--spring-boot-unix.yml" \
    > "$TOOLS_DIR/wan/workflows/pre-commit-unix.yml"
else
  MODULES_LIST="${JAVA_MODULES[*]}"
  MODULES_ARRAY_PS=$(printf '"%s",' "${JAVA_MODULES[@]}" | sed 's/,$//')

  sed -e "s|{{SQL_MODULE}}|$SQL_MODULE|g" -e "s|{{MODULES_ARRAY_PS}}|$MODULES_ARRAY_PS|g" \
    "$TOOLKIT_ROOT/templates/wan/workflows/pre-commit--multi-module.yml" \
    > "$TOOLS_DIR/wan/workflows/pre-commit-win.yml"
  sed -e "s|{{SQL_MODULE}}|$SQL_MODULE|g" -e "s|{{MODULES_LIST}}|$MODULES_LIST|g" \
    "$TOOLKIT_ROOT/templates/wan/workflows/pre-commit--multi-module-unix.yml" \
    > "$TOOLS_DIR/wan/workflows/pre-commit-unix.yml"
  # CI 专用 workflow（BASE_REF 控制增量/全量，见模板头部注释）
  sed -e "s|{{SQL_MODULE}}|$SQL_MODULE|g" -e "s|{{MODULES_LIST}}|$MODULES_LIST|g" \
    "$TOOLKIT_ROOT/templates/wan/workflows/ci--multi-module-unix.yml" \
    > "$TOOLS_DIR/wan/workflows/ci-unix.yml"
fi

# 渲染 hook
echo "==> 生成 pre-commit hook"
HOOK_TPL="$(cat "$TOOLKIT_ROOT/templates/hooks/pre-commit.template")"
if [ "$PROJECT_TYPE" = "spring-boot" ]; then
  SQL_BLOCK='echo ""
echo "=== SqlGuard Check ==="
SQLGUARD="$TOOLS_DIR/sql-guard/bin/sqlguard"
SQL_CONFIG="$TOOLS_DIR/sql-guard/sqlguard.toml"
SQL_TARGET="$PROJECT_ROOT/'"$BACKEND_DIR"'/src/main/resources"
if [ -f "$SQLGUARD.exe" ]; then SQLGUARD="$SQLGUARD.exe"; fi
"$SQLGUARD" check-diff --base HEAD -c "$SQL_CONFIG" -f plain "$SQL_TARGET"
echo "? SqlGuard passed"'
  JAVA_BLOCK='echo ""
echo "=== JavaGuard Check ==="
JAVAGUARD="$TOOLS_DIR/java-guard/bin/java-guard"
JAVA_CONFIG="$TOOLS_DIR/java-guard/java-guard.yml"
GATE_CONFIG="$TOOLS_DIR/java-guard/gate-config.yml"
if [ -f "$JAVAGUARD.exe" ]; then JAVAGUARD="$JAVAGUARD.exe"; fi
JAVA_TARGET="$PROJECT_ROOT/'"$BACKEND_DIR"'/src/main/java"
[ ! -d "$JAVA_TARGET" ] && { echo "skip: source dir not found"; exit 0; }
"$JAVAGUARD" scan "$JAVA_TARGET" --config "$JAVA_CONFIG" --gate --gate-config "$GATE_CONFIG" --diff HEAD -f console
echo "? JavaGuard passed"'
  HOOK_TPL="${HOOK_TPL//\{\{FALLBACK_SQL_BLOCK\}\}/$SQL_BLOCK}"
  HOOK_TPL="${HOOK_TPL//\{\{FALLBACK_JAVA_BLOCK\}\}/$JAVA_BLOCK}"
else
  SQL_BLOCK='echo ""
echo "=== SqlGuard Check ==="
SQLGUARD="$TOOLS_DIR/sql-guard/bin/sqlguard"
SQL_CONFIG="$TOOLS_DIR/sql-guard/sqlguard.toml"
SQL_TARGET="$PROJECT_ROOT/'"$SQL_MODULE"'/src/main/resources"
if [ -f "$SQLGUARD.exe" ]; then SQLGUARD="$SQLGUARD.exe"; fi
"$SQLGUARD" check-diff --base HEAD -c "$SQL_CONFIG" -f plain "$SQL_TARGET"
echo "? SqlGuard passed"'
  MODULES_LIST="${JAVA_MODULES[*]}"
  JAVA_BLOCK="echo \"\"
echo \"=== JavaGuard Check ===\"
JAVAGUARD=\"\$TOOLS_DIR/java-guard/bin/java-guard\"
JAVA_CONFIG=\"\$TOOLS_DIR/java-guard/java-guard.yml\"
GATE_CONFIG=\"\$TOOLS_DIR/java-guard/gate-config.yml\"
if [ -f \"\$JAVAGUARD.exe\" ]; then JAVAGUARD=\"\$JAVAGUARD.exe\"; fi
MODULES=\"$MODULES_LIST\"
for MOD in \$MODULES; do
  SRC=\"\$PROJECT_ROOT/\$MOD/src/main/java\"
  [ ! -d \"\$SRC\" ] && continue
  echo \"  scanning \$MOD ...\"
  \"\$JAVAGUARD\" scan \"\$SRC\" --config \"\$JAVA_CONFIG\" --gate --gate-config \"\$GATE_CONFIG\" --diff HEAD -f console
done
echo \"? JavaGuard passed\""
  HOOK_TPL="${HOOK_TPL//\{\{FALLBACK_SQL_BLOCK\}\}/$SQL_BLOCK}"
  HOOK_TPL="${HOOK_TPL//\{\{FALLBACK_JAVA_BLOCK\}\}/$JAVA_BLOCK}"
fi
printf '%s' "$HOOK_TPL" > "$TOOLS_DIR/hooks/pre-commit"

# 生成 README
echo "==> 生成 tools/README.md"
{
  echo "# tools/ — 代码门禁工具集"
  echo ""
  echo "由 gates-toolkit 自动生成于 $(date +%Y-%m-%d\ %H:%M:%S)"
  echo ""
  echo "## 项目类型"
  echo "$PROJECT_TYPE"
  echo ""
  echo "## 扫描路径"
  if [ "$PROJECT_TYPE" = "spring-boot" ]; then
    echo "- SQL/Mapper: \`$BACKEND_DIR/src/main/resources\`"
    echo "- Java: \`$BACKEND_DIR/src/main/java\`"
  else
    echo "- SQL/Mapper: \`$SQL_MODULE/src/main/resources\`"
    echo "- Java 模块: $(printf '`%s/src/main/java`, ' "${JAVA_MODULES[@]}" | sed 's/, $//')"
  fi
  echo ""
  echo "## 使用"
  echo ""
  echo '### 本地门禁'
  echo '`git commit` 时自动检查。如需跳过: `git commit --no-verify`'
  echo ""
  echo "### 手动运行"
  echo '```bash'
  echo "# SqlGuard"
  echo "tools/sql-guard/bin/sqlguard check -c tools/sql-guard/sqlguard.toml -f plain $BACKEND_DIR/src/main/resources"
  echo ""
  echo "# JavaGuard"
  echo "export JAVAGUARD_PARSER_JAR=tools/java-guard/java-parser/java-parser.jar"
  echo "tools/java-guard/bin/java-guard scan $BACKEND_DIR/src/main/java --config tools/java-guard/java-guard.yml --gate --gate-config tools/java-guard/gate-config.yml --diff HEAD -f console"
  echo ""
  echo "# wan 编排"
  echo "tools/wan/bin/wan run pre-commit-unix -C ."
  echo '```'
} > "$TOOLS_DIR/README.md"

# 安装 hook
if [ -d "$GIT_DIR" ]; then
  echo "==> 安装 git pre-commit hook"
  cp "$TOOLS_DIR/hooks/pre-commit" "$GIT_DIR/hooks/pre-commit"
  chmod +x "$GIT_DIR/hooks/pre-commit"
  echo "    -> $GIT_DIR/hooks/pre-commit"
fi

# 验证
echo ""
echo "==> 验证安装"
WAN_BIN_FILE="$TOOLS_DIR/wan/bin/wan"
SQL_BIN_FILE="$TOOLS_DIR/sql-guard/bin/sqlguard"
JG_BIN_FILE="$TOOLS_DIR/java-guard/bin/java-guard"
for tool in "$WAN_BIN_FILE" "$SQL_BIN_FILE" "$JG_BIN_FILE"; do
  if [ -f "$tool" ]; then
    ver=$("$tool" --version 2>&1 | head -1)
    echo "  ✓ $(basename "$tool") : $ver"
  else
    echo "  ✗ $tool : 未找到"
  fi
done
"$WAN_BIN_FILE" validate "$TOOLS_DIR/wan/workflows/pre-commit-unix.yml" 2>&1 || true
[ -f "$TOOLS_DIR/wan/workflows/ci-unix.yml" ] && "$WAN_BIN_FILE" validate "$TOOLS_DIR/wan/workflows/ci-unix.yml" 2>&1 || true

echo ""
printf '\033[0;32m=========================================\n  安装完成\n=========================================\033[0m\n'
echo ""
echo "建议把 tools/ 加入 git: cd $TARGET && git add tools/"
