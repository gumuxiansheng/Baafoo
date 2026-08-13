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
│   ├── setup-gates.sh                         # Bash 安装器
│   ├── fetch-binaries.ps1                     # PowerShell 二进制下载器
│   └── fetch-binaries.sh                      # Bash 二进制下载器
│
├── versions.toml                              # 版本配置（人工填写下载 URL）
├── .gitignore                                 # 忽略 bin/ 下的二进制
└── README.md
```

## 快速开始

### 1. 配置下载地址

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

### 2. 下载二进制

```powershell
# Windows
pwsh scripts/fetch-binaries.ps1

# Linux
bash scripts/fetch-binaries.sh

# 所有平台
pwsh scripts/fetch-binaries.ps1 -Platform all
```

### 3. 集成到项目

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

#### Linux / macOS

```bash
# Spring Boot 单模块
bash scripts/setup-gates.sh /path/to/project spring-boot

# 多模块 Maven
bash scripts/setup-gates.sh /path/to/project multi-module baafoo-server baafoo-core baafoo-server baafoo-agent

# 自动检测
bash scripts/setup-gates.sh /path/to/project auto
```

安装脚本会自动检测 `bin/` 中是否已有二进制，不存在时自动调 `fetch-binaries` 下载。

## 安装流程

1. **检测项目类型** —— 检查 `backend/src/main/java` 或根 `pom.xml`
2. **检测模块** —— 自动找 SQL/Mapper 所在模块和 Java 模块
3. **检查/下载二进制** —— 若 `bin/` 中缺二进制，自动从 `versions.toml` 配置的 URL 下载
4. **复制工具** —— 二进制 + 规则文件到目标项目 `tools/`
5. **渲染配置** —— 把模板里的 `{{BACKEND_DIR}}` / `{{SQL_MODULE}}` / `{{MODULES_LIST}}` 替换成实际值
6. **生成 hook** —— 渲染 `pre-commit` 脚本，加入 `.git/hooks/`
7. **生成 README** —— 自动生成 `tools/README.md`
8. **验证** —— 运行每个工具的 `--version` 和 wan workflow 校验

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
└── tools/
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
    └── README.md
```

`.git/hooks/pre-commit` 自动安装，git commit 时自动跑门禁。

## 二进制管理策略

- `bin/` 目录下的二进制文件（`.exe`、`.jar`、无后缀的 Linux 二进制）**不提交到 git**
- 规则文件（`.rhai`、`.yml`）**随仓库提交**
- 下载逻辑由 `versions.toml` 驱动：
  - 本地不存在 → 下载
  - 本地版本 < 配置版本 → 更新
  - 本地版本 = 配置版本 → 跳过
  - `--force` → 强制重新下载

### 升级工具版本

1. 在 `versions.toml` 中更新 `version` 字段和各平台 `url`
2. 运行 `fetch-binaries.ps1 -Force`（或 `fetch-binaries.sh --force`）
3. 对已集成的项目重新跑 `setup-gates` 覆盖安装

## 门禁规则

### SqlGuard（默认 7 条 P0 + 5 条 P1 可选）

P0（必跑，CI 阻断）：
- DDL001 no_drop_table
- DDL002 primary_key_required
- DML001 no_select_all
- DML002 no_delete_update_without_where
- DML003 insert_columns_required
- DML004 subquery_alias_required
- DML006 no_join_without_condition

P1（默认关闭，按需启用）：DML005/007/008/011/012/013/014/015/016、DDL003/004/005/006

### JavaGuard（12 条规则）

- J001 no_system_out
- J003 no_wildcard_import
- J004 class_naming
- J005 method_naming
- J006 long_method (max 50 行)
- J007 constant_naming
- J010/J011/J012 fastjson 检测
- J013 Spring Controller 禁止 Map 传参

门禁阈值：
- critical: 0
- major: 0
- minor: 20
- info: 100

## 修改配置

集成后想调整：
- 改阈值：编辑 `tools/java-guard/gate-config.yml`
- 改 SQL 规则：编辑 `tools/sql-guard/sqlguard.rules.toml`（取消注释启用 P1 规则）
- 改扫描路径：编辑 `tools/sql-guard/sqlguard.toml` 或 `tools/java-guard/java-guard.yml`
- 改 hook：编辑 `tools/hooks/pre-commit` 然后 `cp tools/hooks/pre-commit .git/hooks/pre-commit`

## CI 集成建议

### 集成方式（推荐）

把 `gates-toolkit/` 目录随项目提交（二进制由 `.gitignore` 排除），CI 中通过
`scripts/ci-setup.sh` 一键准备（下载二进制 → 缺失兜底源码构建 → setup-gates 安装），
再用 wan 执行 CI 专用 workflow `tools/wan/workflows/ci-unix.yml`。

**不要把 setup 产物 `tools/` 提交到 git** —— 它是模板渲染产物，提交后会与
`templates/` 形成双写漂移；目标项目的 `.gitignore` 应忽略 `tools/`。

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
  run: bash gates-toolkit/scripts/ci-setup.sh . multi-module baafoo-server baafoo-core baafoo-server baafoo-agent

- name: Resolve PR base ref
  if: github.event_name == 'pull_request'
  run: echo "BASE_REF=origin/${{ github.base_ref }}" >> "$GITHUB_ENV"

- name: Run gates
  run: tools/wan/bin/wan run tools/wan/workflows/ci-unix.yml -C . --quiet
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
      script: bash gates-toolkit/scripts/ci-setup.sh . multi-module baafoo-server baafoo-core baafoo-server baafoo-agent
    - name: run-gates
      stage: check
      script: tools/wan/bin/wan run tools/wan/workflows/ci-unix.yml -C . --quiet
```

PR 场景在 `run-gates` 前加 `export BASE_REF="origin/${{ cnb.pull_request.base_ref }}"`
（并先 `git fetch origin <base_ref>`）。

### 已知平台缺口

- `sql-guard` v0.2.0 Release 未上传 `linux-amd64` 产物，`ci-setup.sh` 会自动从
  源码兜底构建（需要 cargo）。Release 补齐附件后在 `versions.toml` 填入 URL 即
  可切回下载模式。
- 完整可运行示例见 Baafoo 项目：`.github/workflows/ci.yml`（gates job）与
  `.cnb.yml`（code-gate / pr-code-gate pipeline）。

## 已知限制

- `bin/` 二进制不随仓库提交，首次使用需配置 `versions.toml` 并运行 `fetch-binaries`
- 当前支持两类项目结构（spring-boot / multi-module），未覆盖的场景需手动调整模板
- java-guard 需 JDK 8+，CI 环境需 `apt install default-jdk-headless`

## 版本

- wan 0.1.0
- sql-guard 0.2.0
- java-guard 0.1.0

## 许可

工具集自身 MIT 许可。各工具遵循各自项目许可。
