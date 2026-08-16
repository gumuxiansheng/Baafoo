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

# 从 versions.toml 读取工具配置版本（与 ci-setup.sh 相同算法）
read_config_version() {
  awk -v sec="$1" '
    $0 ~ "^\\[" sec "\\]" { f=1; next }
    f && $0 ~ "^\\[" { f=0 }
    f && /^version/ { gsub(/.*= *"/, ""); gsub(/".*/, ""); print; exit }
  ' "$TOOLKIT_ROOT/versions.toml" 2>/dev/null
}

# 版本比较：version_lt a b → 返回 0 当且仅当 a < b
version_lt() {
  local a="$1" b="$2" first
  [ -n "$a" ] && [ -n "$b" ] || return 1
  first="$(printf '%s\n%s\n' "$a" "$b" | sort -V | head -1)"
  [ "$first" = "$a" ] && [ "$a" != "$b" ]
}

# 计算 toolkit 内容指纹（staleness 检测用，pre-commit hook 用相同算法重算比对）
# 覆盖所有会流入 gates-tools 的输入：versions.toml + templates/** + bin/**/rules/**
# 字节流 = versions.toml 字节 + 按相对路径序排列的(":" + 相对路径 + ":" + 文件字节)
# 注意：与 templates/hooks/pre-commit.template 中的指纹算法必须保持一致
gates_toolkit_fingerprint() {
  (
    cd "$1" 2>/dev/null || exit 0
    if command -v sha256sum >/dev/null 2>&1; then H="sha256sum"; else H="shasum -a 256"; fi
    {
      cat versions.toml 2>/dev/null
      { find templates -type f 2>/dev/null; find bin -type f -path '*/rules/*' 2>/dev/null; } \
        | LC_ALL=C sort | while IFS= read -r f; do
            printf ':%s:' "$f"
            cat "$f" 2>/dev/null
          done
    } | $H | awk '{print $1}'
  )
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

# 参数恢复：重跑 setup（手动升级或 toolkit-update 每日自动更新）时，
# 复用首次 setup 固化到 gates-tools/.meta 的参数，避免非交互下自动检测漂移。
# 显式传参（命令行参数）优先于 .meta。
META_FILE="$TARGET/gates-tools/.meta"
META_PROJECT_TYPE=""
META_SQL_MODULES=""
META_JAVA_MODULES=""
META_BACKEND_DIR=""
if [ -f "$META_FILE" ]; then
  META_PROJECT_TYPE="$(sed -n 's/^project_type=//p' "$META_FILE" | head -1 | tr -d '[:space:]')"
  # 仅恢复多模块键 sql_modules；旧版单数键 sql_module 不恢复——升级到多模块支持后
  # 重跑 setup 会重新全量检测（全部含 mapper 的模块纳入），检测结果固化为新键。
  META_SQL_MODULES="$(sed -n 's/^sql_modules=//p' "$META_FILE" | head -1 | tr -d '[:space:]')"
  META_JAVA_MODULES="$(sed -n 's/^java_modules=//p' "$META_FILE" | head -1)"
  META_BACKEND_DIR="$(sed -n 's/^backend_dir=//p' "$META_FILE" | head -1 | tr -d '[:space:]')"
fi
if [ "$PROJECT_TYPE" = "auto" ] && [ -n "$META_PROJECT_TYPE" ]; then
  PROJECT_TYPE="$META_PROJECT_TYPE"
  echo "[meta] 复用首次 setup 参数: project_type=$PROJECT_TYPE"
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
# SQL 模块（支持多个：含 src/main/resources/mapper 的模块全部纳入；
# 位置参数/-.meta 传入时为逗号分隔字符串，统一解析为 SQL_MODULES 数组）
SQL_MODULES_STR=""
SQL_MODULES=()
JAVA_MODULES=()

# backend_dir 恢复（spring-boot 布局，无命令行参数时）
if [ ${#EXTRA_ARGS[@]} -eq 0 ] && [ "$PROJECT_TYPE" != "multi-module" ] && [ -n "$META_BACKEND_DIR" ]; then
  BACKEND_DIR="$META_BACKEND_DIR"
fi

if [ "$PROJECT_TYPE" = "multi-module" ]; then
  # 是否交互式终端（CI / 重定向输入时不弹提示）
  if [ -t 0 ]; then INTERACTIVE=true; else INTERACTIVE=false; fi
  if [ ${#EXTRA_ARGS[@]} -gt 0 ]; then
    # 位置参数：第一个为 SQL 模块（逗号分隔可传多个），其余为 Java 模块
    SQL_MODULES_STR="${EXTRA_ARGS[0]}"
    JAVA_MODULES=("${EXTRA_ARGS[@]:1}")
  else
    # .meta 参数恢复（重跑场景）：显式参数 > .meta > 自动检测
    if [ -n "$META_SQL_MODULES" ]; then
      SQL_MODULES_STR="$META_SQL_MODULES"
      echo "[meta] 复用首次 setup 参数: sql_modules=$SQL_MODULES_STR"
    fi
    if [ -n "$META_JAVA_MODULES" ]; then
      # 固化时逗号分隔，恢复转空格分词
      # shellcheck disable=SC2206
      JAVA_MODULES=($(printf '%s' "$META_JAVA_MODULES" | tr ',' ' '))
      echo "[meta] 复用首次 setup 参数: java_modules=${JAVA_MODULES[*]}"
    fi

    # 自动猜测 SQL 模块（.meta 已恢复时跳过）：全部纳入
    # find 返回 .../module-name/src/main/resources/mapper，取往上第三层即为模块名
    # （mapper→resources→main→module-name）
    if [ -z "$SQL_MODULES_STR" ]; then
      SQL_CANDIDATES=()
      while IFS= read -r p; do
        # 从 mapper 路径上溯 3 层得到模块目录
        mod_dir="$(dirname "$(dirname "$(dirname "$p")")")"
        # 确保模块目录在 TARGET 下（避免误选 TARGET 自身）
        if [ "$mod_dir" != "$TARGET" ] && [ "$(dirname "$mod_dir")" = "$TARGET" ]; then
          SQL_CANDIDATES+=("$(basename "$mod_dir")")
        fi
      done < <(find "$TARGET" -maxdepth 7 -type d -path '*/src/main/resources/mapper' 2>/dev/null)
      # shellcheck disable=SC2206
      SQL_CANDIDATES=($(printf '%s\n' "${SQL_CANDIDATES[@]}" | sort -u))

      if [ ${#SQL_CANDIDATES[@]} -gt 0 ]; then
        SQL_MODULES=("${SQL_CANDIDATES[@]}")
        echo "[auto] SQL 模块: ${SQL_MODULES[*]}"
      elif [ "$INTERACTIVE" = "true" ]; then
        printf "未自动检测到 SQL 模块，请输入 SQL/Mapper 所在模块名（多个用逗号分隔）: "
        read -r SQL_MODULES_STR
      fi
    fi

    # 自动猜测 Java 模块（.meta 已恢复时跳过）
    if [ ${#JAVA_MODULES[@]} -eq 0 ]; then
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
  fi

  # 逗号分隔字符串归一化为数组（显式传参/.meta/交互输入路径）
  if [ ${#SQL_MODULES[@]} -eq 0 ] && [ -n "$SQL_MODULES_STR" ]; then
    # shellcheck disable=SC2206
    SQL_MODULES=($(printf '%s' "$SQL_MODULES_STR" | tr ',' ' '))
  fi

  if [ ${#SQL_MODULES[@]} -eq 0 ]; then
    echo "Error: 未找到 SQL/Mapper 所在模块（多个模块用逗号分隔传入）" >&2
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
  # 检查是否需要下载：二进制缺失，或本地 .version 落后于 versions.toml 配置版本
  need_fetch=false
  fetch_reason=""
  for b in \
    "$TOOLKIT_ROOT/bin/wan/$WAN_BIN" \
    "$TOOLKIT_ROOT/bin/sql-guard/$SQL_BIN" \
    "$TOOLKIT_ROOT/bin/java-guard/$JG_BIN" \
    "$TOOLKIT_ROOT/bin/java-guard/java-parser/java-parser.jar"; do
    if [ ! -f "$b" ]; then
      need_fetch=true
      fetch_reason="二进制缺失"
      break
    fi
  done

  # 版本落后检查：覆盖"管理员升级 toolkit（versions.toml 变更）后，成员本地二进制落后"的场景，
  # 重跑一次 setup 即完成配置+规则+二进制整体升级。
  # 仅在 .version 存在时比较；二进制在而 .version 缺失（如手工交叉编译覆盖）视为未落后，
  # 避免误覆盖手工放置的二进制。
  if [ "$need_fetch" = "false" ]; then
    for tool in wan sql-guard java-guard; do
      cfg_ver="$(read_config_version "$tool" || true)"
      ver_file="$TOOLKIT_ROOT/bin/$tool/.version"
      [ -n "$cfg_ver" ] && [ -f "$ver_file" ] || continue
      local_ver="$(sed '1s/^\xEF\xBB\xBF//' "$ver_file" | tr -d '[:space:]')"
      if [ -n "$local_ver" ] && version_lt "$local_ver" "$cfg_ver"; then
        need_fetch=true
        fetch_reason="$tool 本地 v$local_ver < 配置 v$cfg_ver"
        break
      fi
    done
  fi

  if [ "$need_fetch" = "true" ]; then
    echo "  二进制不完整或版本落后（$fetch_reason），执行下载..."
    set +e
    bash "$FETCH_SCRIPT" "$FETCH_PLATFORM"
    fetch_rc=$?
    set -e
    if [ "$fetch_rc" -ne 0 ]; then
      echo "Error: 二进制下载失败，请检查 versions.toml 中的 URL 配置" >&2
      exit 1
    fi
  else
    echo "  二进制已存在且版本匹配配置，跳过下载"
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
  # 多 SQL 模块：渲染为 "mod1/src/main/resources/mapper", "mod2/..." 列表
  MAPPER_PATHS=$(printf '"%s/src/main/resources/mapper",' "${SQL_MODULES[@]}" | sed 's/,$//')
  sed "s|{{MAPPER_PATHS}}|$MAPPER_PATHS|g" \
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

  sed -e "s|{{MODULES_ARRAY_PS}}|$MODULES_ARRAY_PS|g" \
    "$TOOLKIT_ROOT/templates/wan/workflows/pre-commit--multi-module.yml" \
    > "$TOOLS_DIR/wan/workflows/pre-commit-win.yml"
  sed -e "s|{{MODULES_LIST}}|$MODULES_LIST|g" \
    "$TOOLKIT_ROOT/templates/wan/workflows/pre-commit--multi-module-unix.yml" \
    > "$TOOLS_DIR/wan/workflows/pre-commit-unix.yml"
  # CI 专用 workflow（BASE_REF 控制增量/全量，见模板头部注释）
  sed -e "s|{{MODULES_LIST}}|$MODULES_LIST|g" \
    "$TOOLKIT_ROOT/templates/wan/workflows/ci--multi-module-unix.yml" \
    > "$TOOLS_DIR/wan/workflows/ci-unix.yml"
fi

# 渲染独立 workflow（sql-guard-only / java-guard-only）
echo "==> 生成独立检查 workflow (sql-guard / java-guard)"
for tool in sql-guard java-guard; do
  if [ "$PROJECT_TYPE" = "spring-boot" ]; then
    sed "s|{{BACKEND_DIR}}|$BACKEND_DIR|g" \
      "$TOOLKIT_ROOT/templates/wan/workflows/${tool}--spring-boot.yml" \
      > "$TOOLS_DIR/wan/workflows/${tool}-win.yml"
    sed "s|{{BACKEND_DIR}}|$BACKEND_DIR|g" \
      "$TOOLKIT_ROOT/templates/wan/workflows/${tool}--spring-boot-unix.yml" \
      > "$TOOLS_DIR/wan/workflows/${tool}-unix.yml"
  else
    if [ "$tool" = "java-guard" ]; then
      sed -e "s|{{MODULES_ARRAY_PS}}|$MODULES_ARRAY_PS|g" \
        "$TOOLKIT_ROOT/templates/wan/workflows/${tool}--multi-module.yml" \
        > "$TOOLS_DIR/wan/workflows/${tool}-win.yml"
      sed -e "s|{{MODULES_LIST}}|$MODULES_LIST|g" \
        "$TOOLKIT_ROOT/templates/wan/workflows/${tool}--multi-module-unix.yml" \
        > "$TOOLS_DIR/wan/workflows/${tool}-unix.yml"
    else
      cp "$TOOLKIT_ROOT/templates/wan/workflows/${tool}--multi-module.yml" \
        "$TOOLS_DIR/wan/workflows/${tool}-win.yml"
      cp "$TOOLKIT_ROOT/templates/wan/workflows/${tool}--multi-module-unix.yml" \
        "$TOOLS_DIR/wan/workflows/${tool}-unix.yml"
    fi
  fi
done

# 渲染 toolkit-update workflow（每日自动更新，注册调度见安装完成后的提示）
# 无占位符：setup 脚本路径按约定固定为 gates-toolkit/scripts/，参数从 .meta 恢复
echo "==> 生成 toolkit-update workflow (每日自动更新)"
cp "$TOOLKIT_ROOT/templates/wan/workflows/toolkit-update--win.yml" "$TOOLS_DIR/wan/workflows/toolkit-update-win.yml"
cp "$TOOLKIT_ROOT/templates/wan/workflows/toolkit-update--unix.yml" "$TOOLS_DIR/wan/workflows/toolkit-update-unix.yml"

# 渲染 hook
echo "==> 生成 pre-commit hook"
HOOK_TPL="$(cat "$TOOLKIT_ROOT/templates/hooks/pre-commit.template")"
if [ "$PROJECT_TYPE" = "spring-boot" ]; then
  JAVA_BLOCK='echo ""
echo "=== JavaGuard Check ==="
JAVAGUARD="$TOOLS_DIR/java-guard/bin/java-guard"
JAVA_CONFIG="$TOOLS_DIR/java-guard/java-guard.yml"
GATE_CONFIG="$TOOLS_DIR/java-guard/gate-config.yml"
if [ -f "$JAVAGUARD.exe" ]; then JAVAGUARD="$JAVAGUARD.exe"; fi
JAVA_TARGET="$PROJECT_ROOT/'"$BACKEND_DIR"'/src/main/java"
[ ! -d "$JAVA_TARGET" ] && { echo "skip: source dir not found"; exit 0; }
"$JAVAGUARD" scan "$JAVA_TARGET" --rules-dir "$TOOLS_DIR/java-guard/rules" --config "$JAVA_CONFIG" --gate --gate-config "$GATE_CONFIG" --diff HEAD -f console || {
  echo "✗ JavaGuard 门禁未通过，提交已被阻止。修复后重试；确认跳过: git commit --no-verify" >&2
  exit 1
}
echo "✓ JavaGuard passed"'
  HOOK_TPL="${HOOK_TPL//\{\{FALLBACK_JAVA_BLOCK\}\}/$JAVA_BLOCK}"
else
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
  \"\$JAVAGUARD\" scan \"\$SRC\" --rules-dir \"\$TOOLS_DIR/java-guard/rules\" --config \"\$JAVA_CONFIG\" --gate --gate-config \"\$GATE_CONFIG\" --diff HEAD -f console || {
    echo \"✗ JavaGuard 门禁未通过（\$MOD），提交已被阻止。修复后重试；确认跳过: git commit --no-verify\" >&2
    exit 1
  }
done
echo \"✓ JavaGuard passed\""
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
rem Usage: gates-tools\gatecheck.cmd [sql-guard^|java-guard^|toolkit-update]
rem   no args       = run full pre-commit gate (sql-guard + java-guard)
rem   sql-guard     = run SqlGuard only
rem   java-guard    = run JavaGuard only
rem   toolkit-update = refresh gates-tools from current toolkit source
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
set "TARGET=%1"
if "%TARGET%"=="" set "TARGET=pre-commit"
rem Validate target
if "%TARGET%"=="sql-guard" goto :ok
if "%TARGET%"=="java-guard" goto :ok
if "%TARGET%"=="pre-commit" goto :ok
if "%TARGET%"=="toolkit-update" goto :ok
echo [ERROR] Unknown target: %TARGET%
echo Usage: gates-tools\gatecheck.cmd [sql-guard^|java-guard^|toolkit-update]
exit /b 2
:ok
rem Prefer Windows workflow when pwsh exists, else fall back to bash workflow
rem (same logic as the pre-commit hook; toolkit-update also has win/unix variants)
set "WF=gates-tools\wan\workflows\%TARGET%-unix.yml"
where pwsh >nul 2>nul
if not errorlevel 1 set "WF=gates-tools\wan\workflows\%TARGET%-win.yml"
if not exist "%WF%" (
  echo [ERROR] workflow not found: %WF%
  exit /b 1
)
"%WAN%" run -C . "%WF%"
exit /b %ERRORLEVEL%
EOF
cat > "$TOOLS_DIR/gatecheck.sh" <<'EOF'
#!/bin/sh
# 手动运行代码门禁（由 gates-toolkit 自动生成）
# 用法: bash gates-tools/gatecheck.sh [sql-guard|java-guard|toolkit-update]
#   无参数         = 运行完整 pre-commit 门禁 (sql-guard + java-guard)
#   sql-guard      = 仅运行 SqlGuard
#   java-guard     = 仅运行 JavaGuard
#   toolkit-update = 刷新 gates-tools（从当前 toolkit 源重跑 setup）
cd "$(dirname "$0")/.." || exit 1
WAN="gates-tools/wan/bin/wan"
if [ ! -x "$WAN" ] && [ -x "$WAN.exe" ]; then WAN="$WAN.exe"; fi
if [ ! -x "$WAN" ]; then
  echo "[ERROR] wan binary not found: $WAN" >&2
  exit 1
fi
TARGET="${1:-pre-commit}"
case "$TARGET" in
  sql-guard|java-guard|pre-commit|toolkit-update) ;;
  *)
    echo "[ERROR] Unknown target: $TARGET" >&2
    echo "Usage: bash gates-tools/gatecheck.sh [sql-guard|java-guard|toolkit-update]" >&2
    exit 2
    ;;
esac
WF="gates-tools/wan/workflows/${TARGET}-unix.yml"
if [ -f "gates-tools/wan/workflows/${TARGET}-win.yml" ] && command -v pwsh >/dev/null 2>&1; then
  WF="gates-tools/wan/workflows/${TARGET}-win.yml"
fi
if [ ! -f "$WF" ]; then
  echo "[ERROR] workflow not found: $WF" >&2
  exit 1
fi
"$WAN" run -C . "$WF"
EOF
chmod +x "$TOOLS_DIR/gatecheck.sh" 2>/dev/null || true

# 写入 toolkit 指纹（staleness 检测：pre-commit hook 重算比对，不一致时提示重跑 setup）
# 同时固化 setup 参数（project_type 等）：重跑 setup（手动或 toolkit-update 定时）时
# 从 .meta 恢复，避免非交互下自动检测漂移。
echo "==> 写入 toolkit 指纹"
TOOLKIT_FINGERPRINT="$(gates_toolkit_fingerprint "$TOOLKIT_ROOT")"
if [ -n "$TOOLKIT_FINGERPRINT" ]; then
  {
    echo "# 由 gates-toolkit setup-gates 生成；pre-commit hook 用于 staleness 检测"
    echo "toolkit_fingerprint=$TOOLKIT_FINGERPRINT"
    echo "setup_at=$(date '+%Y-%m-%d %H:%M:%S')"
    echo "project_type=$PROJECT_TYPE"
    if [ "$PROJECT_TYPE" = "multi-module" ]; then
      echo "sql_modules=$(printf '%s,' "${SQL_MODULES[@]}" | sed 's/,$//')"
      echo "java_modules=$(printf '%s,' "${JAVA_MODULES[@]}" | sed 's/,$//')"
    else
      echo "backend_dir=$BACKEND_DIR"
    fi
  } > "$TOOLS_DIR/.meta"
else
  echo "Warning: toolkit 指纹计算失败，跳过 .meta 写入" >&2
fi

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
    echo "- SQL/Mapper: $(printf '`%s`, ' "${SQL_MODULES[@]}" | sed 's/, $//') (mapper 路径已全部写入 sqlguard.toml)"
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
  echo "单独运行某个检查工具:"
  echo "```"
  echo "# gatecheck 脚本带参数"
  echo "gates-tools\gatecheck.cmd sql-guard    # Windows"
  echo "bash gates-tools/gatecheck.sh java-guard # Linux"
  echo ""
  echo "# 或直接用 wan（需完整路径，wan 短名查找 .wan/workflows/）"
  echo "\"gates-tools/wan/bin/wan\" run \"gates-tools/wan/workflows/sql-guard-unix.yml\" -C ."
  echo "\"gates-tools/wan/bin/wan\" run \"gates-tools/wan/workflows/java-guard-unix.yml\" -C ."
  echo "```"
  echo ""
  echo "等价于以下完整命令（也可单独执行）:"
  echo '```bash'
  echo "# SqlGuard（pre-commit 自动增量检查未提交改动；手动全量在项目根运行）"
  echo "gates-tools/sql-guard/bin/sqlguard check -c gates-tools/sql-guard/sqlguard.toml -f plain ."
  echo ""
  echo "# JavaGuard"
  echo "export JAVAGUARD_PARSER_JAR=gates-tools/java-guard/java-parser/java-parser.jar"
  echo "gates-tools/java-guard/bin/java-guard scan $BACKEND_DIR/src/main/java --rules-dir gates-tools/java-guard/rules --config gates-tools/java-guard/java-guard.yml --gate --gate-config gates-tools/java-guard/gate-config.yml --diff HEAD -f console"
  echo ""
  echo "# wan 编排"
  echo "gates-tools/wan/bin/wan run pre-commit-unix -C ."
  echo '```'
  echo ""
  echo "### 每日自动更新"
  echo ""
  echo "setup 已自动注册 wan 定时调度（每日 09:00 刷新 gates-tools）并安装系统服务，无需手动操作。"
  echo '```'
  echo "# 查看执行历史 / 手动触发一次"
  echo "gates-tools/wan/bin/wan schedule history toolkit-update -C ."
  echo "bash gates-tools/gatecheck.sh toolkit-update"
  echo ""
  echo "# 如未注册（GATES_NO_SCHEDULE=1 / CI 环境跳过），手动开启:"
  echo "gates-tools/wan/bin/wan schedule add toolkit-update \"0 9 * * *\" gates-tools/wan/workflows/toolkit-update-unix.yml -C ."
  echo "gates-tools/wan/bin/wan schedule service install -C ."
  echo '```'
} > "$TOOLS_DIR/README.md"

# CI 集成：生成/更新 CI 编排文件（统一使用 gates-tools/ 路径，避免成员手写 tools/ 导致 CI 找不到产物）
#  - 不存在            → 生成
#  - 已含门禁门（wan run / ci-unix.yml）→ 自动修正裸 tools/ 路径为 gates-tools/（备份 .bak）
#  - 存在但无门禁门    → allow_append=1 时追加，否则仅提示（不破坏现有 workflow 结构）
update_ci_file() {
  local path="$1" content="$2" allow_append="${3:-0}"
  if [ ! -f "$path" ]; then
    printf '%s\n' "$content" > "$path"
    echo "    -> 已生成 $path"
    return
  fi
  if grep -qE 'wan run|ci-unix\.yml' "$path"; then
    if grep -qE '(^|[^a-z-])tools/' "$path"; then
      cp "$path" "$path.bak"
      sed -E 's/(^|[^a-z-])tools\//\1gates-tools\//g' "$path" > "$path.tmp" && mv "$path.tmp" "$path"
      echo "    -> 已修正 $path 中 tools/ 路径为 gates-tools/ (原文件备份 .bak)"
    else
      echo "    -> $path 已含门禁门且路径正确，无需修改"
    fi
  elif [ "$allow_append" = "1" ]; then
    cp "$path" "$path.bak"
    { cat "$path"; printf '\n'; printf '%s\n' "$content"; } > "$path.tmp" && mv "$path.tmp" "$path"
    echo "    -> 已追加门禁门到 $path (原文件备份 .bak)"
  else
    echo "    -> $path 已存在但无门禁门，请参照 README 在现有 workflow 中补充 gates 步骤 (未修改)"
  fi
}

echo "==> 生成/更新 CI 编排 (code-gate)"
CNB_ARGS=""
PROJECT_ARG="$PROJECT_TYPE"
if [ "$PROJECT_TYPE" = "multi-module" ]; then
  SQL_ARG="$(printf '%s,' "${SQL_MODULES[@]}" | sed 's/,$//')"
  JAVA_ARG="${JAVA_MODULES[*]}"
  CNB_ARGS="\"$SQL_ARG\" $JAVA_ARG"
fi

# CNB pipeline (.cnb.yml)——顶层为 stages 数组，无门禁门时可安全追加
CNB_TPL="$TOOLKIT_ROOT/templates/cnb/cnb.yml.template"
CNB_CONTENT="$(sed -e "s|{{PROJECT_TYPE}}|$PROJECT_ARG|g" -e "s|{{CISETUP_ARGS}}|$CNB_ARGS|g" "$CNB_TPL")"
update_ci_file "$TARGET/.cnb.yml" "$CNB_CONTENT" 1

# GitHub Actions workflow (.github/workflows/ci.yml)——完整文件结构，存在但无门禁门时仅提示
GHA_TPL="$TOOLKIT_ROOT/templates/github/ci.yml.template"
GHA_CONTENT="$(sed -e "s|{{PROJECT_TYPE}}|$PROJECT_ARG|g" -e "s|{{CISETUP_ARGS}}|$CNB_ARGS|g" "$GHA_TPL")"
mkdir -p "$TARGET/.github/workflows"
update_ci_file "$TARGET/.github/workflows/ci.yml" "$GHA_CONTENT" 0

# gates-tools 为生成产物：自动加入目标项目 .gitignore，避免误提交
# .wan/ 为 wan 调度状态（含机器相关绝对路径），同样不入库
echo "==> 更新目标项目 .gitignore（忽略生成的 gates-tools/ 与 .wan/）"
GITIGNORE_FILE="$TARGET/.gitignore"
GITIGNORE_NEW=0
if [ -f "$GITIGNORE_FILE" ]; then
  grep -qE '^gates-tools/?$' "$GITIGNORE_FILE" \
    || { printf '\n# gates-toolkit 门禁产物（由 setup-gates 生成，不入库）\ngates-tools/\n' >> "$GITIGNORE_FILE"; GITIGNORE_NEW=1; }
  grep -qE '^\.wan/?$' "$GITIGNORE_FILE" \
    || { printf '.wan/\n' >> "$GITIGNORE_FILE"; GITIGNORE_NEW=1; }
else
  printf '# gates-toolkit 门禁产物（由 setup-gates 生成，不入库）\ngates-tools/\n.wan/\n' > "$GITIGNORE_FILE"
  GITIGNORE_NEW=1
fi
[ "$GITIGNORE_NEW" = "1" ] && echo "    -> $GITIGNORE_FILE" || echo "    已存在，跳过"

# hook 是否由本工具生成（依据模板头部 marker 判断，避免误备份/覆盖第三方 hook）
hook_is_toolkit() {
  grep -q 'Generated by gates-toolkit' "$1" 2>/dev/null
}

# 备份已存在的非本工具 hook（保留 .bak，冲突时追加时间戳）
backup_existing_hook() {
  local dst="$1"
  local bak="$dst.bak"
  if [ -f "$bak" ]; then bak="$dst.bak.$(date +%Y%m%d%H%M%S)"; fi
  cp "$dst" "$bak"
  echo "    检测到已有 hook（非 gates-toolkit 生成），已备份: $bak"
}

# 安装单个 hook：非本工具生成且已存在时，先备份再覆盖
install_hook_file() {
  local name="$1"
  local src="$TOOLS_DIR/hooks/$name"
  local dst="$GIT_DIR/hooks/$name"
  if [ -f "$dst" ] && ! hook_is_toolkit "$dst"; then
    if [ -t 0 ]; then
      printf "    检测到已有 %s hook（非 gates-toolkit 生成），将备份后覆盖。继续? (y/N) " "$name"
      read -r ans
      [ "$ans" != "y" ] && { echo "Aborted." >&2; exit 1; }
    fi
    backup_existing_hook "$dst"
  fi
  cp "$src" "$dst"
  chmod +x "$dst"
  echo "    -> $dst"
}

# 安装 hook
if [ -d "$GIT_DIR" ]; then
  # core.hooksPath 检测：已配置指向其它目录时，git 不会执行 .git/hooks/ 下的 hook，安装将不生效
  HOOKS_PATH="$(git -C "$TARGET" config --get core.hooksPath 2>/dev/null || true)"
  if [ -n "$HOOKS_PATH" ]; then
    echo "Warning: 检测到 core.hooksPath=$HOOKS_PATH ，git 将只执行该目录下的 hook，.git/hooks/ 安装将不生效。" >&2
    if [ -t 0 ]; then
      printf "继续安装到 .git/hooks/ ? (y/N) "
      read -r ans
      [ "$ans" != "y" ] && { echo "Aborted." >&2; exit 1; }
    fi
  fi

  echo "==> 安装 git pre-commit hook"
  install_hook_file pre-commit

  echo "==> 安装 git commit 信息 hooks（prepare-commit-msg / commit-msg）"
  install_hook_file prepare-commit-msg
  install_hook_file commit-msg

  # 配置 commit.template：VSCode 提交输入框 / IntelliJ 提交对话框据此预填模板
  # （IDE 不执行 prepare-commit-msg，只有 git 原生 commit.template 才能在 IDE 输入框预填；
  #   用绝对路径——各成员仓库克隆位置不同，相对路径会因 git 执行目录而失效）
  TEMPLATE_FILE="$TOOLS_DIR/commit-message/commit.template"
  if [ -f "$TEMPLATE_FILE" ]; then
    git -C "$TARGET" config --local commit.template "$TEMPLATE_FILE"
    echo "    -> git config commit.template = $TEMPLATE_FILE"
  fi
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
[ -f "$TOOLS_DIR/wan/workflows/toolkit-update-unix.yml" ] && [ -f "$WAN_BIN_FILE" ] && "$WAN_BIN_FILE" validate "$TOOLS_DIR/wan/workflows/toolkit-update-unix.yml" 2>&1 || true

# 注册每日自动更新调度（toolkit-update）：setup 直接完成注册，成员无需手动执行
#  - CI 环境自动跳过；GATES_NO_SCHEDULE=1 可显式关闭
#  - schedule add 幂等：schedule list 已含 toolkit-update 时跳过（重复 add 会报错）
#  - service install 幂等：安装系统服务（开机自启），失败仅告警不阻断安装
#  - 注意：schedule add 的 workflow 相对路径按进程 CWD 解析（非 -C 目录），须切到目标项目根执行
SCHEDULE_STATE="registered"
if [ "${GATES_NO_SCHEDULE:-}" = "1" ] || [ "${CI:-}" = "true" ] || [ "${GITHUB_ACTIONS:-}" = "true" ]; then
  SCHEDULE_STATE="skipped"
  echo "==> 跳过每日自动更新调度注册 (GATES_NO_SCHEDULE / CI 环境)"
elif [ -f "$TOOLS_DIR/wan/workflows/toolkit-update-unix.yml" ] && [ -f "$WAN_BIN_FILE" ]; then
  echo "==> 注册每日自动更新调度 (toolkit-update, 每日 09:00)"
  # 子 shell 切到目标项目根执行（schedule add 的 workflow 相对路径按进程 CWD 解析）；
  # 注册失败通过非零退出码传出（set -e 下需整体置于 if 条件中）
  if (
    cd "$TARGET" || exit 1
    if "$WAN_BIN_FILE" schedule list -C . 2>/dev/null | grep -qE '^[[:space:]]*toolkit-update([[:space:]]|$)'; then
      echo "    调度 toolkit-update 已注册，跳过"
    else
      "$WAN_BIN_FILE" schedule add toolkit-update "0 9 * * *" gates-tools/wan/workflows/toolkit-update-unix.yml -C . \
        || { echo "    warn: schedule add 失败（不影响门禁安装），可稍后手动重试" >&2; exit 2; }
    fi
    svc_out="$("$WAN_BIN_FILE" schedule service install -C . 2>&1)" \
      && echo "    ✓ $svc_out" \
      || {
        echo "    service install: $svc_out" >&2
        echo "    warn: 调度服务安装失败（可能需要权限），可稍后手动: $WAN_BIN_FILE schedule service install -C ." >&2
      }
  ); then
    :
  else
    SCHEDULE_STATE="failed"
  fi
else
  SCHEDULE_STATE="missing"
  echo "==> 未找到 toolkit-update workflow 或 wan 二进制，跳过调度注册"
fi

echo ""
printf '\033[0;32m=========================================\n  安装完成\n=========================================\033[0m\n'
echo ""
echo "gates-tools/ 为 setup 生成的产物（含二进制），已加入目标项目 .gitignore，无需（也不应）提交到 git。"
echo "门禁工具与规则建议整包引入 gates-toolkit（git submodule 或随仓库提交），升级时重跑本脚本即可。"
echo ""
if [ "$SCHEDULE_STATE" = "registered" ]; then
  echo "每日自动更新: 已注册 toolkit-update 调度（每日 09:00）并安装系统服务。"
  echo "  查看运行记录: gates-tools/wan/bin/wan schedule history toolkit-update -C ."
  echo "  手动触发一次: bash gates-tools/gatecheck.sh toolkit-update"
else
  echo "每日自动更新: 本次未注册。如需开启（在项目根运行，时间可自定义）:"
  if [ "$OS_TYPE" = "Linux" ]; then
    echo "  gates-tools/wan/bin/wan schedule add toolkit-update \"0 9 * * *\" gates-tools/wan/workflows/toolkit-update-unix.yml -C ."
    echo "  gates-tools/wan/bin/wan schedule service install -C .   # 安装为系统服务（开机自启，可能需管理员权限）"
    echo "  手动触发一次: bash gates-tools/gatecheck.sh toolkit-update"
  else
    echo "  gates-tools\\wan\\bin\\wan.exe schedule add toolkit-update \"0 9 * * *\" gates-tools/wan/workflows/toolkit-update-win.yml -C ."
    echo "  gates-tools\\wan\\bin\\wan.exe schedule service install -C .   # 安装为系统服务（开机自启，可能需管理员权限）"
    echo "  手动触发一次: gates-tools\\gatecheck.cmd toolkit-update"
  fi
fi
