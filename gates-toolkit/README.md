# gates-toolkit

把 sql-guard、java-guard、wan 三个静态检查工具打包成可粘贴的部署工具集，一行命令集成到任意项目。

## 工具集内容

```
gates-toolkit/
├── bin/                                       # 工具二进制（不提交 git，由 fetch-binaries 下载）
│   ├── sql-guard/
│   │   ├── sqlguard.exe / sqlguard-linux-*    # 按平台下载
│   │   └── rules/                             # 22 条 Rhai 规则（随仓库提交）
│   ├── java-guard/
│   │   ├── java-guard.exe / java-guard-linux-* # 按平台下载
│   │   ├── java-parser.jar                    # 按平台下载
│   │   └── rules/                             # 12 条 YAML/Rhai 规则（随仓库提交）
│   └── wan/
│       └── wan.exe / wan-linux-*              # 按平台下载
│
├── templates/                                 # 配置模板
│   ├── sql-guard/                             # sqlguard.toml / sqlguard.rules.toml
│   ├── java-guard/                            # java-guard.yml / gate-config.yml
│   ├── wan/workflows/                         # pre-commit.yml (Windows / Linux)
│   └── hooks/                                 # pre-commit 模板
│
├── scripts/                                   # 安装与下载入口
│   ├── setup-gates.ps1                        # PowerShell 安装器
│   ├── setup-gates.cmd                        # Windows cmd 入口（PS5 兼容，自动调 ps1）
│   ├── setup-gates.sh                         # Bash 安装器
│   ├── fetch-binaries.ps1                     # PowerShell 二进制下载器
│   ├── fetch-binaries.cmd                     # Windows cmd 入口（PS5 兼容，自动调 ps1）
│   └── fetch-binaries.sh                      # Bash 二进制下载器
│
├── versions.toml                              # 版本配置（人工填写下载 URL）
├── .gitignore                                 # 忽略 bin/ 下的二进制
└── README.md
```

> **二进制链接方式**：
> - `sqlguard-linux-*`：**musl 静态链接**（`statically linked`），任意 Linux 发行版（含 Alpine）可直接运行；
> - `wan-linux-*` / `java-guard-linux-*`：自 v0.1.1 / v0.1.0 起已改为 **musl 静态链接**（上游 `.cargo/config.toml` + `.cnb.yml` 切换 `-musl` target），任意 Linux 发行版可直接运行；
> - 若本地用 `aarch64-unknown-linux-musl` 交叉编译出静态版，可覆盖 `bin/` 下的同名文件（`fetch-binaries` 仅在版本不一致或文件缺失时重新下载，不会无故覆盖）。

## 快速开始

### 成员一键安装（零参数，推荐）

把 `gates-toolkit/` 目录随项目仓库提交（二进制已被 `.gitignore` 排除，无需提交），
成员 `git pull` 后在**项目根目录**打开终端，执行一条命令：

```bat
rem Windows（cmd 窗口或资源管理器地址栏输入 cmd）
gates-toolkit\scripts\setup-gates.cmd
```

```bash
# Linux（macOS 暂无预编译产物，脚本会提示）
bash gates-toolkit/scripts/setup-gates.sh
```

也可以直接用 pwsh：`pwsh gates-toolkit/scripts/setup-gates.ps1`（零参数同样有效）。

**什么都不用配** —— 脚本自动完成：
1. 目标项目 = 当前目录（自动向上找 git 仓库根，无需 `-Target`）
2. 自动检测项目类型（spring-boot / multi-module）与 SQL/Java 模块
   （多个 SQL 模块时交互式选择；检测不到时会询问后手动输入）
3. `bin/` 二进制缺失**或本地版本落后于 `versions.toml`** 时自动下载（首次需联网；版本一致后自动跳过）
4. 渲染配置 → 生成 `gates-tools/` 并自动加入目标项目 `.gitignore` → 安装 `.git/hooks/` 三个 hook（pre-commit / prepare-commit-msg / commit-msg）+ 写入 `commit.template` git 配置（IDE 提交框预填模板）
5. 写入 toolkit 指纹到 `gates-tools/.meta`（供 pre-commit hook 做 staleness 检测）
6. 验证安装并打印各工具版本

之后 `git commit` 自动跑门禁。想手动触发：
- Windows：`gates-tools\gatecheck.cmd`（双击即可）
- Linux：`bash gates-tools/gatecheck.sh`
- 跳过本次门禁：`git commit --no-verify`

> **工具集更新提示（staleness 检测）**：管理员更新 `gates-toolkit`（规则/模板/版本配置）并提交后，
> 成员 `git pull` 下来，下次 `git commit` 时 hook 会检测到指纹不一致并提示重跑 setup
> （只提示不阻断）。重跑一次 setup 即完成配置+规则+二进制整体升级（二进制版本落后时自动下载）。

> 只需维护者（管理员）在首次使用前填写 `versions.toml` 的下载 URL，成员无需感知。

### 首次配置下载地址（维护者）

编辑 `versions.toml`，填写各工具各平台的下载 URL：

```toml
[wan]
version = "0.1.0"

  [wan.windows_amd64]
  url = "https://your-release-host/wan-v0.1.0-windows-amd64.exe"
  filename = "wan.exe"

  [wan.linux_arm64]
  url = "https://your-release-host/wan-v0.1.0-linux-arm64"
  filename = "wan-linux-arm64"
```

URL 可以是任意可公开访问的直链（CNB Release 附件、GitHub Release Asset、对象存储等）。
`filename` 字段仅作展示参考，脚本按平台硬编码文件名下载，请勿修改。

### 下载二进制（可选，脚本会自动执行）

```powershell
# Windows
pwsh scripts/fetch-binaries.ps1

# Linux
bash scripts/fetch-binaries.sh

# 所有平台
pwsh scripts/fetch-binaries.ps1 -Platform all
```

### 参数化安装（可选）

需要跨目录安装或手动指定模块时：

#### Windows

```powershell
# Spring Boot 单模块项目
pwsh scripts/setup-gates.ps1 -Target C:/my/project -ProjectType spring-boot

# 多模块 Maven（自动检测 SQL 模块和 Java 模块）
pwsh scripts/setup-gates.ps1 -Target C:/my/project -ProjectType multi-module

# 手动指定
pwsh scripts/setup-gates.ps1 -Target C:/my/project `
  -ProjectType multi-module `
  -SqlModule baafoo-server `
  -JavaModules baafoo-core,baafoo-server,baafoo-agent
```

> **没有 pwsh（PowerShell 7）？** 直接用系统自带的 Windows PowerShell 5.1（cmd 或双击）：

```bat
rem 脚本与 .ps1 同目录，参数原样透传（内部自动调 powershell.exe 5.1）
scripts\setup-gates.cmd -Target C:\my\project -ProjectType spring-boot
scripts\fetch-binaries.cmd -Platform all
```

`.ps1` 脚本已做 PS5 兼容：显式 UTF-8 读写、无 BOM 写入（PS5 的 `Set-Content -Encoding UTF8` 会写 BOM 导致 wan 解析 YAML 失败）。

#### Linux

```bash
# Spring Boot 单模块
bash scripts/setup-gates.sh /path/to/project spring-boot

# 多模块 Maven（第二个参数后依次为 SQL 模块、Java 模块）
bash scripts/setup-gates.sh /path/to/project multi-module baafoo-server baafoo-core baafoo-agent

# 自动检测
bash scripts/setup-gates.sh /path/to/project auto
```

> macOS 暂无预编译二进制（未发布 darwin 产物），`fetch-binaries` / `setup-gates` 会直接报错，请在 Linux/Windows 或 CI 中使用。

安装脚本会自动检测 `bin/` 中是否已有二进制，不存在时自动调 `fetch-binaries` 下载
（不会在目标项目是工具集自身时报错退出，请先 `cd` 到目标项目）。

## 安装流程

1. **检测项目类型** —— 检查 `backend/src/main/java` 或根 `pom.xml`
2. **检测模块** —— 自动找 SQL/Mapper 所在模块和 Java 模块（多个 SQL 模块时交互选择）
3. **检查/下载二进制** —— 若 `bin/` 中缺二进制，自动从 `versions.toml` 配置的 URL 下载
4. **复制工具** —— 二进制 + 规则文件到目标项目 `gates-tools/`
5. **渲染配置** —— 把模板里的 `{{BACKEND_DIR}}` / `{{SQL_MODULE}}` / `{{MODULES_LIST}}` 替换成实际值
6. **生成 hook** —— 渲染 `pre-commit` 脚本，加入 `.git/hooks/`；同时安装 `prepare-commit-msg` / `commit-msg`，并写入 `git config commit.template`（IDE 提交框预填模板）
7. **生成快捷脚本** —— `gates-tools/gatecheck.cmd` / `gatecheck.sh`，一键手动触发门禁
8. **生成 README** —— 自动生成 `gates-tools/README.md`
9. **写入指纹** —— 计算 toolkit 内容指纹（versions.toml + templates + rules）写入 `gates-tools/.meta`
10. **验证** —— 运行每个工具的 `--version` 和 wan workflow 校验

## 项目类型

### spring-boot

适合 Spring Boot 单模块项目：

```
backend/
├── src/main/java/        # java-guard 扫描
├── src/main/resources/   # sql-guard 扫描（SQL + Mapper XML）
```

可参数 `-BackendDir` 指定 backend 目录名（默认 `backend`）。

### multi-module

适合多模块 Maven 项目：

```
pom.xml
├── baafoo-server/
│   ├── src/main/resources/mapper/   # sql-guard 扫描
│   └── src/main/java/                # java-guard 扫描
├── baafoo-core/src/main/java/        # java-guard 扫描
├── baafoo-agent/src/main/java/       # java-guard 扫描
└── ...
```

可参数 `-SqlModule` 指定 SQL 所在模块，`-JavaModules` 数组指定 Java 模块。

### auto

先看 `backend/src/main/java`，再回退到根 `pom.xml`。

## 集成后产物

```
目标项目/
└── gates-tools/
    ├── sql-guard/
    │   ├── bin/sqlguard(.exe)
    │   ├── config/rules/ddl/    # 7 条 DDL 规则
    │   ├── config/rules/dml/    # 15 条 DML 规则
    │   ├── config/rules/lib/helpers.rhai
    │   ├── sqlguard.toml
    │   └── sqlguard.rules.toml
    ├── java-guard/
    │   ├── bin/java-guard(.exe)
    │   ├── java-parser/java-parser.jar
    │   ├── rules/                # 12 条规则（含 J013）
    │   ├── java-guard.yml
    │   └── gate-config.yml
    ├── wan/
    │   ├── bin/wan(.exe)
    │   └── workflows/
    │       ├── pre-commit-win.yml
    │       └── pre-commit-unix.yml
    ├── hooks/pre-commit
    ├── hooks/prepare-commit-msg      # 提交信息模板预填
    ├── hooks/commit-msg              # 提交信息校验（警告模式）
    ├── commit-message/
    │   ├── commit.template           # 提交信息模板正文
    │   └── commit-msg.config         # 校验规则（type/长度/必填段落）
    ├── gatecheck.cmd / gatecheck.sh   # 手动一键触发门禁（双击/一条命令）
    ├── .meta                          # toolkit 指纹（pre-commit hook staleness 检测用）
    └── README.md
```

`.git/hooks/pre-commit` 自动安装，git commit 时自动跑门禁（门禁失败时错误摘要会输出到 stderr 首行，VSCode / IntelliJ 的失败弹窗直接显示该错误）；本地 git 配置 `commit.template` 指向 `gates-tools/commit-message/commit.template`，供 IDE 提交框预填模板；`gates-tools/gatecheck.*` 用于手动触发。

## 二进制管理策略

- `bin/` 目录下的二进制文件（`.exe`、`.jar`、无后缀的 Linux 二进制）**不提交到 git**
- 规则文件（`.rhai`、`.yml`）**随仓库提交**
- 下载逻辑由 `versions.toml` 驱动：
  - 本地不存在 → 下载
  - 本地版本 < 配置版本 → 更新
  - 本地版本 = 配置版本 → 跳过
  - `--force` → 强制重新下载
- `setup-gates` 同样按上述规则触发下载（缺失**或版本落后**时才下载，其余情况跳过、不联网）；
  二进制存在但 `.version` 缺失时（如手工交叉编译覆盖）视为不落后，不会误覆盖手工放置的二进制

### 升级工具版本

**管理员**：

1. 在 `versions.toml` 中更新 `version` 字段和各平台 `url`
2. 运行 `fetch-binaries.ps1 -Force`（或 `fetch-binaries.sh --force`）刷新本地 `bin/`
3. 提交 `versions.toml`（及规则/模板改动）

**成员**（配置+规则+二进制一条命令整体升级）：

1. `git pull` 拉到新版 `gates-toolkit`
2. 下次 `git commit` 时 pre-commit hook 会提示"toolkit 已更新，请重跑 setup"（指纹比对 `gates-tools/.meta`）
3. 重跑 `gates-toolkit\scripts\setup-gates.cmd`（Linux 为 `setup-gates.sh`）——
   二进制版本落后会自动下载，无需手动 `fetch-binaries`

## 门禁规则

### SqlGuard（默认 8 条 P0 + 13 条 P1 可选）

P0（必跑，CI 阻断）：
- DDL001 no_drop_table
- DDL002 primary_key_required
- DML001 no_select_all
- DML002 no_delete_update_without_where
- DML003 insert_columns_required
- DML004 subquery_alias_required
- DML006 no_join_without_condition
- DML108 no_constant_where

P1（默认关闭，按需启用）：DML005/007/008/011/012/013/014/015/016、DDL003/004/005/006

### JavaGuard（14 条规则）

默认启用（12 条）：
- J001 no_system_out
- J003 no_wildcard_import
- J004 class_naming
- J005 method_naming
- J006 long_method (max 50 行)
- J007 constant_naming
- J010/J011/J012 fastjson 检测
- J013 Spring Controller 禁止 Map 传参

默认关闭（2 条，按需启用，解除 `java-guard.yml` 中 `rules.disable` 注释即可）：
- J014 禁止引入非 jackson 的 JSON 框架（Import 检查）
- J015 禁止使用非 jackson 的 JSON 框架（使用点检查，与 J014 互补）

> 注：J014/J015 为 major 级且门禁阈值 `max_major: 0`，直接启用会让存在存量违规的项目 CI 全红；建议先用 `--baseline` 抑制已知存量后再启用。

门禁阈值：
- critical: 0
- major: 0
- minor: 20
- info: 100

## 提交信息模板

setup 同时安装 `prepare-commit-msg` / `commit-msg` 两个 hook，并配置 `git config commit.template` 指向模板文件：

- **模板预填**：
  - **IDE**：VSCode 源代码管理的提交输入框、IntelliJ 提交对话框均读取 `commit.template` 配置自动预填（VSCode 会自动忽略 `#` 注释行）；setup 已写入本地 git 配置，无需手工设置。
  - **CLI**：`git commit`（打开编辑器）时由 git 原生预填，`prepare-commit-msg` hook 兜底；已通过 `-m` / `-F` / IDE 输入的内容不会被覆盖。
- **兜底校验**：`commit-msg` 校验 type 白名单、subject 非空与长度、必填段落（改动说明/测试情况/影响范围），缺失时打印警告。
- **警告模式**（默认）：不阻断提交，仅提示。需升级为硬拦截时，把 `gates-tools/hooks/commit-msg` 末尾的 `exit 0` 改为 `exit 1` 即可。
- **豁免**：Merge / Revert 自动消息、`wip:` 开头的草稿、以及 `git commit --no-verify` 均跳过校验。
- **IDE 兼容**：git hook 在 git 层执行，`commit-msg` 校验对所有 IDE（IntelliJ / VSCode / Eclipse / VS / Sourcetree / CLI）生效。

自定义模板与规则：编辑 `gates-tools/commit-message/commit.template`（模板正文）和 `gates-tools/commit-message/commit-msg.config`（type 白名单、subject 长度上限、必填段落）。

## 修改配置

集成后想调整：
- 改阈值：编辑 `gates-tools/java-guard/gate-config.yml`
- 改 SQL 规则：编辑 `gates-tools/sql-guard/sqlguard.rules.toml`（取消注释启用 P1 规则）
- 改提交信息模板/校验：编辑 `gates-tools/commit-message/commit.template` 与 `commit-msg.config`
- 改扫描路径：编辑 `gates-tools/sql-guard/sqlguard.toml` 或 `gates-tools/java-guard/java-guard.yml`
- 改 hook：编辑 `gates-tools/hooks/pre-commit` 然后 `cp gates-tools/hooks/pre-commit .git/hooks/pre-commit`（commit-msg / prepare-commit-msg 同理）

## CI 集成建议

### 集成方式（推荐）

把 `gates-toolkit/` 目录随项目提交（二进制由 `.gitignore` 排除），CI 中通过
`scripts/ci-setup.sh` 一键准备（下载二进制 → 缺失兜底源码构建 → setup-gates 安装），
再用 wan 执行 CI 专用 workflow `gates-tools/wan/workflows/ci-unix.yml`。

**不要把 setup 产物 `gates-tools/` 提交到 git** —— 它是模板渲染产物（含二进制），提交后会与
`templates/` 形成双写漂移；setup 已自动把 `gates-tools/` 写入目标项目的 `.gitignore`，无需也不应提交。
工具与规则建议整包引入 `gates-toolkit/`（git submodule 或随仓库提交），升级时重跑 setup-gates 即可。

### CI 门禁行为

`ci-unix.yml` 通过环境变量 `BASE_REF` 指定增量基线（ratchet 策略：只门禁新增/修改
的代码，存量债务可后续清理；存量全量扫描通常超过 gate 阈值会让 CI 一开始全红）：

| 场景 | BASE_REF | 行为 |
|------|----------|------|
| push | `event.before` 或默认 `HEAD~1` | 增量扫描本次推送的改动 |
| pull request | `origin/<base-branch>` | 增量扫描 PR 全部改动 |

如需全量扫描，把 `BASE_REF` 设为仓库根提交（`git rev-list --max-parents=0 HEAD`）即可。

> 注意：不要在 CI 里直接用 `pre-commit-unix.yml` —— 它的 `--base HEAD` 是为本地
> 未提交改动设计的，CI 检出是干净的，diff 为空会导致门禁空转。

### GitHub Actions

```yaml
- uses: actions/checkout@v4
  with:
    fetch-depth: 0            # PR 增量 diff 需要完整历史

- uses: actions/cache@v4      # 二进制缓存，versions.toml 变更自动失效
  with:
    path: gates-toolkit/bin
    key: gates-bin-${{ runner.os }}-${{ runner.arch }}-${{ hashFiles('gates-toolkit/versions.toml') }}

- uses: actions/setup-java@v4 # java-guard 的 java-parser.jar 需要 JRE 8+
  with:
    distribution: temurin
    java-version: '8'

- name: Prepare gates
  run: bash gates-toolkit/scripts/ci-setup.sh . multi-module baafoo-server baafoo-core baafoo-agent

- name: Resolve PR base ref
  if: github.event_name == 'pull_request'
  run: echo "BASE_REF=origin/${{ github.base_ref }}" >> "$GITHUB_ENV"

- name: Run gates
  run: gates-tools/wan/bin/wan run gates-tools/wan/workflows/ci-unix.yml -C . --quiet
```

### CNB

```yaml
- name: code-gate
  docker:
    image: rust:1.77-bookworm   # sql-guard linux-amd64 未发布前需 cargo 兜底构建
  volumes:
    - /root/.cargo:copy-on-write
  stages:
    - name: setup-gates
      script: bash gates-toolkit/scripts/ci-setup.sh . multi-module baafoo-server baafoo-core baafoo-agent
    - name: run-gates
      stage: check
      script: gates-tools/wan/bin/wan run gates-tools/wan/workflows/ci-unix.yml -C . --quiet
```

PR 场景在 `run-gates` 前加 `export BASE_REF="origin/${{ cnb.pull_request.base_ref }}"`
（并先 `git fetch origin <base_ref>`）。

### 已知平台缺口

- `sql-guard` v0.2.0 Release 未上传 `linux-amd64` 产物，`ci-setup.sh` 会自动从
  源码兜底构建（需要 cargo）。Release 补齐附件后在 `versions.toml` 填入 URL 即
  可切回下载模式。
- **macOS**：暂无 darwin 预编译产物，`fetch-binaries` / `setup-gates` 会直接报错（不要用 linux-* 二进制替代，无法运行）。
- 完整可运行示例见 Baafoo 项目：`.github/workflows/ci.yml`（gates job）与
  `.cnb.yml`（code-gate / pr-code-gate pipeline）。

## 已知限制

- `bin/` 二进制不随仓库提交，首次使用需配置 `versions.toml` 并运行 `fetch-binaries`
- 当前支持两类项目结构（spring-boot / multi-module），未覆盖的场景需手动调整模板
- java-guard 需 JDK 8+，CI 环境需 `apt install default-jdk-headless`

## 版本

- wan 0.1.2（本地 musl 静态构建，上游源码 20b4480；CNB release 最新 v0.1.1）
- sql-guard 0.2.1
- java-guard 0.1.1（本地 musl 静态构建，上游源码 d644eb3；CNB release 最新 v0.1.0）

## 许可

工具集自身 MIT 许可。各工具遵循各自项目许可。
