#!/bin/bash
# gates-toolkit 二进制下载/更新脚本 (Linux/macOS)
#
# 读取 versions.toml 配置，下载各平台二进制到 bin/。
# - 本地不存在 → 下载
# - 本地版本低于配置版本 → 更新
# - 本地版本等于配置版本 → 跳过
#
# 用法:
#   bash scripts/fetch-binaries.sh                  # 下载当前平台
#   bash scripts/fetch-binaries.sh all              # 下载所有平台
#   bash scripts/fetch-binaries.sh --force          # 强制重新下载
#   bash scripts/fetch-binaries.sh all --force      # 强制下载所有平台

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLKIT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG_FILE="$TOOLKIT_ROOT/versions.toml"

PLATFORM="auto"
FORCE=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        all|windows-amd64|linux-arm64|linux-amd64) PLATFORM="$1"; shift ;;
        --force|-f) FORCE=true; shift ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# 检测当前平台
if [ "$PLATFORM" = "auto" ]; then
    OS="$(uname -s)"
    ARCH="$(uname -m)"
    if [ "$OS" = "Linux" ] && [ "$ARCH" = "aarch64" ]; then
        PLATFORM="linux-arm64"
    elif [ "$OS" = "Linux" ] && [ "$ARCH" = "x86_64" ]; then
        PLATFORM="linux-amd64"
    elif [ "$OS" = "Darwin" ]; then
        PLATFORM="linux-arm64"
        echo "warn: macOS 暂无预编译二进制，使用 linux-arm64 作为 fallback"
    else
        PLATFORM="linux-arm64"
        echo "warn: 未知平台 $OS/$ARCH，默认使用 linux-arm64"
    fi
fi

echo "========================================="
echo "  gates-toolkit 二进制下载"
echo "========================================="
echo "配置: $CONFIG_FILE"
echo "平台: $PLATFORM"
echo ""

if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: 配置文件不存在: $CONFIG_FILE" >&2
    exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "Error: 需要 curl" >&2
    exit 1
fi

# 简易 TOML 解析
parse_toml_value() {
    local section="$1"
    local key="$2"
    # 匹配 [section] 下的 key = "value"
    # 注意：sec 传入裸段名（不要加方括号），由下面的正则自行拼接 \[ \]，
    # 否则 "[sql-guard]" 会被 awk 当作字符类（invalid range endpoint）。
    awk -v sec="$section" -v k="$key" '
        $0 ~ "^[[:space:]]*\\[" sec "\\][[:space:]]*$" { in_sec=1; next }
        $0 ~ "^[[:space:]]*\\[" { in_sec=0 }
        in_sec && $0 ~ "^[[:space:]]*" k "[[:space:]]*=" {
            gsub(/.*=[[:space:]]*"/, ""); gsub(/".*/, ""); print; exit
        }
    ' "$CONFIG_FILE"
}

# 版本比较: echo -1/0/1
compare_version() {
    local a="$1" b="$2"
    if [ -z "$a" ]; then echo -1; return; fi
    if [ -z "$b" ]; then echo 1; return; fi

    IFS='.' read -ra A <<< "$a"
    IFS='.' read -ra B <<< "$b"
    local max_len=${#A[@]}
    [ ${#B[@]} -gt $max_len ] && max_len=${#B[@]}

    for ((i=0; i<max_len; i++)); do
        local av=${A[i]:-0}
        local bv=${B[i]:-0}
        if [ "$av" -lt "$bv" ]; then echo -1; return; fi
        if [ "$av" -gt "$bv" ]; then echo 1; return; fi
    done
    echo 0
}

read_local_version() {
    local bin_dir="$1"
    local vf="$bin_dir/.version"
    [ -f "$vf" ] && cat "$vf" | tr -d '[:space:]'
}

write_local_version() {
    local bin_dir="$1" version="$2"
    echo "$version" > "$bin_dir/.version"
}

# 工具定义
TOOLS=(
    "wan:wan:bin/wan"
    "sql-guard:sql-guard:bin/sql-guard"
    "java-guard:java-guard:bin/java-guard"
)

# 平台映射: tool_name -> "sub_section|filename" 对
get_platform_info() {
    local tool="$1" pf="$2"
    case "$tool" in
        wan)
            case "$pf" in
                windows-amd64) echo "wan.windows_amd64|wan.exe" ;;
                linux-arm64)   echo "wan.linux_arm64|wan-linux-arm64" ;;
                linux-amd64)   echo "wan.linux_amd64|wan-linux-amd64" ;;
            esac
            ;;
        sql-guard)
            case "$pf" in
                windows-amd64) echo "sql-guard.windows_amd64|sqlguard.exe" ;;
                linux-arm64)   echo "sql-guard.linux_arm64|sqlguard-linux-arm64" ;;
                linux-amd64)   echo "sql-guard.linux_amd64|sqlguard-linux-amd64" ;;
            esac
            ;;
        java-guard)
            case "$pf" in
                windows-amd64) echo "java-guard.windows_amd64|java-guard.exe" ;;
                linux-arm64)   echo "java-guard.linux_arm64|java-guard-linux-arm64" ;;
                linux-amd64)   echo "java-guard.linux_amd64|java-guard-linux-amd64" ;;
            esac
            ;;
    esac
}

DOWNLOADED=0
SKIPPED=0
FAILED=0

for tool_entry in "${TOOLS[@]}"; do
    IFS=':' read -r tool_name section bin_dir_rel <<< "$tool_entry"
    bin_dir="$TOOLKIT_ROOT/$bin_dir_rel"
    config_version=$(parse_toml_value "$section" "version")

    if [ -z "$config_version" ]; then
        echo "[$tool_name] 配置中未找到 version，跳过"
        continue
    fi

    local_version=$(read_local_version "$bin_dir")

    need_download=false
    if [ "$FORCE" = "true" ]; then
        need_download=true
        reason="强制下载"
    elif [ -z "$local_version" ]; then
        need_download=true
        reason="本地不存在"
    else
        cmp=$(compare_version "$local_version" "$config_version")
        if [ "$cmp" -lt 0 ]; then
            need_download=true
            reason="本地 v$local_version < 配置 v$config_version"
        else
            reason="本地 v$local_version = 配置 v$config_version"
        fi
    fi

    echo "[$tool_name] v$config_version — $reason"

    if [ "$need_download" = "false" ]; then
        # 检查文件是否完整
        all_present=true
        if [ "$PLATFORM" = "all" ]; then
            for pf in windows-amd64 linux-arm64; do
                info=$(get_platform_info "$tool_name" "$pf")
                filename="${info#*|}"
                [ -f "$bin_dir/$filename" ] || all_present=false
            done
        else
            info=$(get_platform_info "$tool_name" "$PLATFORM")
            filename="${info#*|}"
            [ -f "$bin_dir/$filename" ] || all_present=false
        fi

        # java-parser.jar
        if [ "$tool_name" = "java-guard" ]; then
            [ -f "$bin_dir/java-parser/java-parser.jar" ] || all_present=false
        fi

        if [ "$all_present" = "true" ]; then
            echo "  → 跳过（版本一致且文件完整）"
            SKIPPED=$((SKIPPED + 1))
            continue
        else
            need_download=true
            echo "  → 版本一致但部分文件缺失，补充下载"
        fi
    fi

    [ "$need_download" = "false" ] && continue

    mkdir -p "$bin_dir"

    # 下载二进制
    if [ "$PLATFORM" = "all" ]; then
        platforms=("windows-amd64" "linux-arm64")
    else
        platforms=("$PLATFORM")
    fi

    for pf in "${platforms[@]}"; do
        info=$(get_platform_info "$tool_name" "$pf")
        sub_section="${info%%|*}"
        filename="${info#*|}"

        url=$(parse_toml_value "$sub_section" "url")
        if [ -z "$url" ]; then
            echo "  $pf : URL 未配置，跳过"
            FAILED=$((FAILED + 1))
            continue
        fi

        dest="$bin_dir/$filename"
        echo "  $pf : $url"
        if curl -fsSL "$url" -o "$dest"; then
            chmod +x "$dest" 2>/dev/null || true
            size=$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest" 2>/dev/null || echo 0)
            size_mb=$(echo "scale=1; $size / 1048576" | bc 2>/dev/null || echo "?")
            echo "  ✓ $filename ($size_mb MB)"
            DOWNLOADED=$((DOWNLOADED + 1))
        else
            echo "  ✗ 下载失败"
            FAILED=$((FAILED + 1))
        fi
    done

    # java-parser.jar
    if [ "$tool_name" = "java-guard" ]; then
        mkdir -p "$bin_dir/java-parser"
        url=$(parse_toml_value "java-guard.java_parser" "url")
        if [ -n "$url" ]; then
            dest="$bin_dir/java-parser/java-parser.jar"
            echo "  java-parser : $url"
            if curl -fsSL "$url" -o "$dest"; then
                size=$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest" 2>/dev/null || echo 0)
                size_mb=$(echo "scale=1; $size / 1048576" | bc 2>/dev/null || echo "?")
                echo "  ✓ java-parser.jar ($size_mb MB)"
                DOWNLOADED=$((DOWNLOADED + 1))
            else
                echo "  ✗ 下载失败"
                FAILED=$((FAILED + 1))
            fi
        else
            echo "  java-parser : URL 未配置，跳过"
            FAILED=$((FAILED + 1))
        fi
    fi

    write_local_version "$bin_dir" "$config_version"
done

echo ""
if [ "$FAILED" -gt 0 ]; then
    echo "========================================="
    echo "  下载完成: $DOWNLOADED 个文件, $SKIPPED 个跳过, $FAILED 个失败"
    echo "========================================="
    exit 1
else
    echo "========================================="
    echo "  下载完成: $DOWNLOADED 个文件, $SKIPPED 个跳过, 0 个失败"
    echo "========================================="
    exit 0
fi
