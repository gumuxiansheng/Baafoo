# Baafoo testing directory reorganization

## New structure

```
testing/
├── 1_UnitTest/
│   ├── run-mutation.ps1
│   └── toolchains.xml
├── 2_IntegrationTest/
│   ├── test-integration.ps1
│   ├── run-fullchain-tests.sh
│   └── rules/
│       ├── all-protocols-rules.json
│       ├── openapi-sample.json
│       ├── register-all.sh
│       ├── register-rules.ps1
│       ├── http-get.json
│       ├── http-post.json
│       ├── ... (34 JSON rule files)
│       ├── grpc-*.json
│       └── ...
├── 3_SystemTest/
│   ├── test-fullchain.ps1
│   ├── TEST-MANUAL.md
│   └── TEST-REPORT.md
├── 4_E2ETest/
│   └── enterprise/
│       ├── common/
│       ├── kafka/
│       ├── petclinic/
│       ├── spring-cloud-alibaba/
│       ├── enterprise-env.ps1
│       └── run-all-smoke-tests.ps1
├── 5_PerformanceTest/
│   └── (empty)
├── 6_UITest/
│   └── playwright-report/
│       └── index.html
└── 7_Others/
    ├── COVERAGE-REVIEW-2026-07-07.md
    ├── PROJECT-TEST-PLAN.md
    └── tmp/
        ├── test-all-protocols.py
        ├── integration-test-staging.py
        ├── verify-recordings.py
        ├── create-servicename-rule.ps1
        └── ...
```

## Scripts moved

- `testing/run-mutation.ps1` → `testing/1_UnitTest/run-mutation.ps1`
- `testing/toolchains.xml` → `testing/1_UnitTest/toolchains.xml`
- `testing/test-integration.ps1` → `testing/2_IntegrationTest/test-integration.ps1`
- `testing/run-fullchain-tests.sh` → `testing/2_IntegrationTest/run-fullchain-tests.sh`
- `testing/test-fullchain.ps1` → `testing/3_SystemTest/test-fullchain.ps1`
- `testing/TEST-MANUAL.md` → `testing/3_SystemTest/TEST-MANUAL.md`
- `testing/TEST-REPORT.md` → `testing/3_SystemTest/TEST-REPORT.md`
- `testing/test-rules/rules/` → `testing/2_IntegrationTest/rules/`
- `testing/test-rules/all-protocols-rules.json`, `openapi-sample.json`, `register-all.sh`, `register-rules.ps1` → `testing/2_IntegrationTest/rules/`
- `testing/enterprise/` → `testing/4_E2ETest/enterprise/`
- `testing/tmp/` → `testing/7_Others/tmp/`
- `testing/PROJECT-TEST-PLAN.md` → `testing/7_Others/PROJECT-TEST-PLAN.md`
- `testing/COVERAGE-REVIEW-2026-07-07.md` → `testing/7_Others/COVERAGE-REVIEW-2026-07-07.md`

## Path references updated

- `test-fullchain.ps1`: `$PROJECT_ROOT` now computed two levels up from `testing/3_SystemTest`; rule directory path set to `testing\2_IntegrationTest\rules`.
- `register-rules.ps1`: reads `all-protocols-rules.json` directly.
- `register-all.sh`: updated to point to `rules/*.json`.
- `run-mutation.ps1`: `$projectRoot` computed one level up from `testing/1_UnitTest`.

## Bash equivalents (P0) still needed

- `testing/2_IntegrationTest/test-fullchain.sh` (equivalent of `test-fullchain.ps1`, runs under Git Bash/WSL/Cygwin)
- `testing/1_UnitTest/run-mutation.sh` (equivalent of `run-mutation.ps1`)

下面对旧路径的引用仅用于记录迁移映射，不代表项目当前仍存在这些路径。)

## Notes

- `testing/tmp/` is kept as a historical archive and is excluded from Git by `.gitignore` (see the line `# testing/tmp/`).
- `5_PerformanceTest/` is currently empty.
- Docker Compose paths inside `test-integration.ps1` still reference `docker-compose.test.yml` / `.env.test`; verify those files exist at project root or adjust if they were moved.
- Some historical test reports in `7_Others/tmp/` contain stale links; they are archived and not executed.
