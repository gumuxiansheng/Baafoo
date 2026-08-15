#!/bin/bash
# gates-toolkit 一键安装脚本 (Linux/macOS)
#
# 零参数模式（推荐，在项目根目录运行）:
#   bash scripts/setup-gates.sh
#   自动使用当前 git 仓库根为目标项目，自动检测项目类型与模块。
#
# 参数化模式（可选）:
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

# 从当前目录向上找 git 仓库根（零参数模式用）
find_git_root() {
  local d="$1"
  while [ -n "$d" ] && [ "$d" != "/" ]; do
    if [ -d "$d/.git" ]; then echo "$d"; return 0; fi
    d="$(dirname "$d")"
  done
  return 1
}

print_banner
echo "工具集: $TOOLKIT_ROOT"

# 校验
case "$TARGET" in
  help|-h|--help)
    echo "Usage: $0 [target-project] [project-type] [extra-args...]"
    echo ""
    echo "零参数（推荐）: 在项目根目录直接运行，自动检测一切"
    echo "  $0"
    echo ""
    echo "参数化:"
    echo "  $0 /path/to/project spring-boot"
    echo "  $0 /path/to/project multi-module baafoo-server mod1 mod2 mod3"
    echo "  $0 /path/to/project auto"
    exit 0
    ;;
esac

# 目标项目：未指定时自动使用当前 git 仓库根（零参数模式）
if [ -z "$TARGET" ]; then
  TARGET="$(find_git_root "$PWD")"
  if [ -z "$TARGET" ]; then TARGET="$PWD"; fi
  echo "未指定目标项目，自动使用: $TARGET"
fi

echo "目标项目: $TARGET"
echo ""

if [ ! -d "$TARGET" ]; then
  echo "Error: target project not found: $TARGET" >&2
  exit 1
fi

TARGET="$(cd "$TARGET" && pwd)"
if [ "$TARGET" = "$TOOLKIT_ROOT" ]; then
  echo "Error: 目标项目不能是工具集自身（$TOOLKIT_ROOT）。请先 cd 到目标项目目录再运行。" >&2
  exit 1
fi
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
  # 是否交互式终端（CI / 重定向输入时不弹提示）
  if [ -t 0 ]; then INTERACTIVE=true; else INTERACTIVE=false; fi
  if [ ${#EXTRA_ARGS[@]} -gt 0 ]; then
    SQL_MODULE="${EXTRA_ARGS[0]}"
    JAVA_MODULES=("${EXTRA_ARGS[@]:1}")
  else
    # 自动猜测 SQL 模块：找 */src/main/resources/mapper 目录，从路径中提取模块名
    # find 返回 .../module-name/src/main/resources/mapper，取往上第三层即为模块名
    # （mapper→resources→main→module-name）
    SQL_CANDIDATES=()
    while IFS= read -r p; do
      # 从 mapper 路径上溯 3 层得到模块目录
      mod_dir="$(dirname "$(dirname "$(dirname "$p")")")"
      # 确保模块目录在 TARGET 下（避免误选 TARGET 自身）
      if [ "$mod_dir" != "$TARGET" ] && [ "$(dirname "$mod_dir")" = "$TARGET" ]; then
        SQL_CANDIDATES+=("$(basename "$mod_dir")")
      fi
    done < <(find "$TARGET" -maxdepth 7 -type d -path '*/src/main/resources/mapper' 2>/dev/null)
    SQL_CANDIDATES=($(printf '%s\n' "${SQL_CANDIDATES[@]}" | sort -u))

    if [ ${#SQL_CANDIDATES[@]} -gt 0 ]; then
      if [ ${#SQL_CANDIDATES[@]} -gt 1 ] && [ "$INTERACTIVE" = "true" ]; then
        echo "[auto] 发现多个可能的 SQL 模块:"
        for i in "${!SQL_CANDIDATES[@]}"; do echo "  [$((i + 1))] ${SQL_CANDIDATES[$i]}"; done
        printf "选择序号（默认 1）: "
        read -r sel
        idx=0
        case "$sel" in
          ''|*[!0-9]*) idx=0 ;;
          *) if [ "$sel" -ge 1 ] && [ "$sel" -le "${#SQL_CANDIDATES[@]}" ]; then idx=$((sel - 1)); else idx=0; fi ;;
        esac
        SQL_MODULE="${SQL_CANDIDATES[$idx]}"
      else
        SQL_MODULE="${SQL_CANDIDATES[0]}"
      fi
      echo "[auto] SQL 模块: $SQL_MODULE"
    elif [ "$INTERACTIVE" = "true" ]; then
      printf "未自动检测到 SQL 模块，请输入 SQL/Mapper 所在模块名: "
      read -r SQL_MODULE
    fi

    # 自动猜测 Java 模块
    # find 返回 .../module-name/src/main/java，取往上第二层即为模块名
    # （java→main→module-name）
    while IFS= read -r p; do
      mod_dir="$(dirname "$(dirname "$p")")"
      if [ "$mod_dir" != "$TARGET" ] && [ "$(dirname "$mod_dir")" = "$TARGET" ]; then
        JAVA_MODULES+=("$(basename "$mod_dir")")
      fi
    done < <(find "$TARGET" -maxdepth 7 -type d -path '*/src/main/java' 2>/dev/null | sort -u)
    echo "[auto] Java 模块: ${JAVA_MODULES[*]}"
  fi

  if [ -z "$SQL_MODULE" ]; then
    echo "Error: 未找到 SQL/Mapper 所在模块" >&2
    exit 1
  fi
  if [ ${#JAVA_MODULES[@]} -eq 0 ]; then
    if [ "$INTERACTIVE" = "true" ]; then
      printf "未检测到 Java 模块，请输入模块名（多个用空格分隔）: "
      read -r raw
      JAVA_MODULES=($raw)
      echo "[auto] Java 模块: ${JAVA_MODULES[*]}"
    fi
    if [ ${#JAVA_MODULES[@]} -eq 0 ]; then
      echo "Error: 未找到 Java 模块" >&2
      exit 1
    fi
  fi
fi

# 创建 gates-tools 目录
TOOLS_DIR="$TARGET/gates-tools"
if [ -d "$TOOLS_DIR" ]; then
  echo "Warning: $TOOLS_DIR 已存在，将覆盖"
  rm -rf "$TOOLS_DIR"
fi
mkdir -p "$TOOLS_DIR"/{sql-guard/bin,sql-guard/config/rules/{ddl,dml,lib},java-guard/bin,java-guard/java-parser,java-guard/rules/rhai,wan/bin,wan/workflows,hooks,commit-message}

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
elif [ "$OS_TYPE" = "Darwin" ]; then
  echo "Error: macOS 暂无预编译二进制（未发布 darwin 产物），请在 Linux/Windows 或 CI 中使用" >&2
  exit 1
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
    set +e
    bash "$FETCH_SCRIPT" "$FETCH_PLATFORM"
    fetch_rc=$?
    set -e
    if [ "$fetch_rc" -ne 0 ]; then
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
MISSING_BINS=""
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
    echo "Warning: 二进制不存在，跳过: $src" >&2
    MISSING_BINS="$MISSING_BINS $tool_dir"
  fi
done
# java-parser.jar
if [ -f "$TOOLKIT_ROOT/bin/java-guard/java-parser/java-parser.jar" ]; then
  cp "$TOOLKIT_ROOT/bin/java-guard/java-parser/java-parser.jar" "$TOOLS_DIR/java-guard/java-parser/"
else
  echo "Warning: java-parser.jar 不存在，跳过" >&2
  MISSING_BINS="$MISSING_BINS java-parser"
fi
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
"$SQLGUARD" check-diff --base HEAD -c "$SQL_CONFIG" -f plain "$SQL_TARGET" || exit 1
echo "✓ SqlGuard passed"'
  JAVA_BLOCK='echo ""
echo "=== JavaGuard Check ==="
JAVAGUARD="$TOOLS_DIR/java-guard/bin/java-guard"
JAVA_CONFIG="$TOOLS_DIR/java-guard/java-guard.yml"
GATE_CONFIG="$TOOLS_DIR/java-guard/gate-config.yml"
if [ -f "$JAVAGUARD.exe" ]; then JAVAGUARD="$JAVAGUARD.exe"; fi
JAVA_TARGET="$PROJECT_ROOT/'"$BACKEND_DIR"'/src/main/java"
[ ! -d "$JAVA_TARGET" ] && { echo "skip: source dir not found"; exit 0; }
"$JAVAGUARD" scan "$JAVA_TARGET" --rules-dir "$TOOLS_DIR/java-guard/rules" --config "$JAVA_CONFIG" --gate --gate-config "$GATE_CONFIG" --diff HEAD -f console || exit 1
echo "✓ JavaGuard passed"'
  HOOK_TPL="${HOOK_TPL//\{\{FALLBACK_SQL_BLOCK\}\}/$SQL_BLOCK}"
  HOOK_TPL="${HOOK_TPL//\{\{FALLBACK_JAVA_BLOCK\}\}/$JAVA_BLOCK}"
else
  SQL_BLOCK='echo ""
echo "=== SqlGuard Check ==="
SQLGUARD="$TOOLS_DIR/sql-guard/bin/sqlguard"
SQL_CONFIG="$TOOLS_DIR/sql-guard/sqlguard.toml"
SQL_TARGET="$PROJECT_ROOT/'"$SQL_MODULE"'/src/main/resources"
if [ -f "$SQLGUARD.exe" ]; then SQLGUARD="$SQLGUARD.exe"; fi
"$SQLGUARD" check-diff --base HEAD -c "$SQL_CONFIG" -f plain "$SQL_TARGET" || exit 1
echo "✓ SqlGuard passed"'
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
  \"\$JAVAGUARD\" scan \"\$SRC\" --rules-dir \"\$TOOLS_DIR/java-guard/rules\" --config \"\$JAVA_CONFIG\" --gate --gate-config \"\$GATE_CONFIG\" --diff HEAD -f console || exit 1
done
echo \"✓ JavaGuard passed\""
  HOOK_TPL="${HOOK_TPL//\{\{FALLBACK_SQL_BLOCK\}\}/$SQL_BLOCK}"
  HOOK_TPL="${HOOK_TPL//\{\{FALLBACK_JAVA_BLOCK\}\}/$JAVA_BLOCK}"
fi
printf '%s' "$HOOK_TPL" > "$TOOLS_DIR/hooks/pre-commit"

# 生成 commit 信息模板与校验规则（独立文件，后续可单独编辑）
echo "==> 生成 commit 信息模板与校验规则"
cp "$TOOLKIT_ROOT/templates/commit-message/commit.template" "$TOOLS_DIR/commit-message/commit.template"
cp "$TOOLKIT_ROOT/templates/commit-message/commit-msg.config" "$TOOLS_DIR/commit-message/commit-msg.config"

# 生成 prepare-commit-msg / commit-msg hook（无占位符，直接按模板复制）
echo "==> 生成 prepare-commit-msg / commit-msg hook"
cp "$TOOLKIT_ROOT/templates/hooks/prepare-commit-msg.template" "$TOOLS_DIR/hooks/prepare-commit-msg"
cp "$TOOLKIT_ROOT/templates/hooks/commit-msg.template" "$TOOLS_DIR/hooks/commit-msg"

# 生成手动运行脚本（一键触发门禁，无需记长命令）
echo "==> 生成 gatecheck 快捷脚本"
cat > "$TOOLS_DIR/gatecheck.cmd" <<'EOF'
@echo off
rem gatecheck.cmd - run code gates manually (generated by gates-toolkit)
rem Usage: gates-tools\gatecheck.cmd
rem NOTE: ASCII only on purpose (cmd parses batch files using OEM codepage,
rem       UTF-8 comments before chcp can corrupt line endings)
chcp 65001 >nul
setlocal
cd /d "%~dp0.."
set "WAN=gates-tools\wan\bin\wan.exe"
if not exist "%WAN%" (
  echo [ERROR] wan binary not found: %WAN%
  exit /b 1
)
rem Prefer Windows workflow when pwsh exists, else fall back to bash workflow
rem (same logic as the pre-commit hook)
set "WF=gates-tools\wan\workflows\pre-commit-unix.yml"
where pwsh >nul 2>nul
if not errorlevel 1 set "WF=gates-tools\wan\workflows\pre-commit-win.yml"
"%WAN%" run -C . "%WF%"
exit /b %ERRORLEVEL%
EOF
cat > "$TOOLS_DIR/gatecheck.sh" <<'EOF'
#!/bin/sh
# 手动运行代码门禁（由 gates-toolkit 自动生成）
# 用法: bash gates-tools/gatecheck.sh
cd "$(dirname "$0")/.." || exit 1
WAN="gates-tools/wan/bin/wan"
if [ ! -x "$WAN" ] && [ -x "$WAN.exe" ]; then WAN="$WAN.exe"; fi
if [ ! -x "$WAN" ]; then
  echo "[ERROR] wan binary not found: $WAN" >&2
  exit 1
fi
WF="gates-tools/wan/workflows/pre-commit-unix.yml"
if [ -f "gates-tools/wan/workflows/pre-commit-win.yml" ] && command -v pwsh >/dev/null 2>&1; then
  WF="gates-tools/wan/workflows/pre-commit-win.yml"
fi
"$WAN" run -C . "$WF"
EOF
chmod +x "$TOOLS_DIR/gatecheck.sh" 2>/dev/null || true

# 生成 README
echo "==> 生成 gates-tools/README.md"
{
  echo "# gates-tools/ — 代码门禁工具集"
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
  echo "一键触发全部门禁:"
  echo "- Windows: \`gates-tools\\gatecheck.cmd\`（双击即可）"
  echo "- Linux/macOS: \`bash gates-tools/gatecheck.sh\`"
  echo ""
  echo "等价于以下完整命令（也可单独执行）:"
  echo '```bash'
  echo "# SqlGuard"
  echo "gates-tools/sql-guard/bin/sqlguard check-diff --base HEAD -c gates-tools/sql-guard/sqlguard.toml -f plain $BACKEND_DIR/src/main/resources"
  echo ""
  echo "# JavaGuard"
  echo "export JAVAGUARD_PARSER_JAR=gates-tools/java-guard/java-parser/java-parser.jar"
  echo "gates-tools/java-guard/bin/java-guard scan $BACKEND_DIR/src/main/java --rules-dir gates-tools/java-guard/rules --config gates-tools/java-guard/java-guard.yml --gate --gate-config gates-tools/java-guard/gate-config.yml --diff HEAD -f console"
  echo ""
  echo "# wan 编排"
  echo "gates-tools/wan/bin/wan run pre-commit-unix -C ."
  echo '```'
} > "$TOOLS_DIR/README.md"

# gates-tools 为生成产物：自动加入目标项目 .gitignore，避免误提交
echo "==> 更新目标项目 .gitignore（忽略生成的 gates-tools/）"
GITIGNORE_FILE="$TARGET/.gitignore"
if [ -f "$GITIGNORE_FILE" ]; then
  if grep -qE '^gates-tools/?$' "$GITIGNORE_FILE"; then
    echo "    已存在，跳过"
  else
    printf '\n# gates-toolkit 门禁产物（由 setup-gates 生成，不入库）\ngates-tools/\n' >> "$GITIGNORE_FILE"
    echo "    -> $GITIGNORE_FILE"
  fi
else
  printf '# gates-toolkit 门禁产物（由 setup-gates 生成，不入库）\ngates-tools/\n' > "$GITIGNORE_FILE"
  echo "    -> $GITIGNORE_FILE"
fi

# 安装 hook
if [ -d "$GIT_DIR" ]; then
  echo "==> 安装 git pre-commit hook"
  cp "$TOOLS_DIR/hooks/pre-commit" "$GIT_DIR/hooks/pre-commit"
  chmod +x "$GIT_DIR/hooks/pre-commit"
  echo "    -> $GIT_DIR/hooks/pre-commit"

  echo "==> 安装 git commit 信息 hooks（prepare-commit-msg / commit-msg）"
  for h in prepare-commit-msg commit-msg; do
    cp "$TOOLS_DIR/hooks/$h" "$GIT_DIR/hooks/$h"
    chmod +x "$GIT_DIR/hooks/$h"
    echo "    -> $GIT_DIR/hooks/$h"
  done
fi

# 验证
if [ -n "$MISSING_BINS" ]; then
  echo ""
  echo "Warning: 以下工具二进制缺失，门禁不完整: $MISSING_BINS" >&2
  echo "         Linux amd64 场景请使用 scripts/ci-setup.sh（会自动从源码构建 sql-guard）" >&2
fi
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
[ -f "$WAN_BIN_FILE" ] && "$WAN_BIN_FILE" validate "$TOOLS_DIR/wan/workflows/pre-commit-unix.yml" 2>&1 || true
[ -f "$TOOLS_DIR/wan/workflows/ci-unix.yml" ] && [ -f "$WAN_BIN_FILE" ] && "$WAN_BIN_FILE" validate "$TOOLS_DIR/wan/workflows/ci-unix.yml" 2>&1 || true

echo ""
printf '\033[0;32m=========================================\n  安装完成\n=========================================\033[0m\n'
echo ""
echo "gates-tools/ 为 setup 生成的产物（含二进制），已加入目标项目 .gitignore，无需（也不应）提交到 git。"
echo "门禁工具与规则建议整包引入 gates-toolkit（git submodule 或随仓库提交），升级时重跑本脚本即可。"
