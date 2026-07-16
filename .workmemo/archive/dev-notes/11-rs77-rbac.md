# R-S7.7 — RBAC Permission Control Enhancement

## What was done

Enhanced the existing AuthService with a centralized auth filter in the HTTP pipeline.

### 1. Local bypass mode (AC-10)
Already existed in `AuthService`:
- `auth.local-bypass` config (default `true`) in `ServerConfig.AuthConfig`
- `isLocalAddress()` checks `127.0.0.1`, `::1`, `0:0:0:0:0:0:0:1`, `localhost`
- When enabled, requests from local addresses automatically get `admin` role

### 2. API Key authentication (AC-09)
Already existed in `AuthService`:
- `X-Api-Key` header support via `apiKeyRoleMap` config
- `validateApiKey()` checks both config-defined keys and user-bound API keys
- Config: `auth.apiKeys` map of `{key: role}` pairs

### 3. Permission checking (AC-07/AC-08)
Already existed as `hasPermission(Role, Resource, Action)`. Added:
- `checkPermission(String role, String httpMethod, String path)` — simplified method that maps HTTP method to action (GET→read, POST→create, PUT→update, DELETE→delete) and infers resource from URL path
- `inferResourceFromPath(String path)` — maps URL paths to resource types

Permission matrix:
| Role       | Rules        | Scenes       | Environments | Recordings   | Users |
|------------|-------------|-------------|-------------|-------------|-------|
| admin      | CRUD         | CRUD         | CRUD         | CRUD         | CRUD  |
| developer  | CRUD         | CRUD         | read-only    | CRUD         | —     |
| tester     | read-only    | CRUD         | read-only    | CRUD         | —     |
| guest      | read-only    | read-only    | read-only    | read-only    | —     |

### 4. User management API (AC-03~AC-06)
Already existed in `UserApiHandler`:
- `GET /api/users` — admin only
- `POST /api/users` — admin only
- `PUT /api/users/{username}/role` — admin only
- `DELETE /api/users/{username}` — admin only
- Plus: `POST /api/users/import` (CSV import), `POST/DELETE /api/users/{username}/api-key`

### 5. Auth endpoints (AC-01/AC-02)
Already existed in `AuthApiHandler`:
- `GET /api/auth/me` — returns current auth status, role, permissions
- `POST /api/auth/login` — login with username/password, returns JWT token

### 6. Auth filter in HTTP pipeline (NEW)
Created `AuthFilter` class added to the Netty pipeline before `ManagementApiHandler`:
- Authenticates all `/api/*` requests
- Skips auth endpoints (`/api/auth/*`) and OPTIONS requests
- Uses `AuthService.checkPermission()` for centralized permission enforcement
- Returns 401 for auth failures, 403 for permission denied
- Passes auth info via `X-Baafoo-Auth-Role` and `X-Baafoo-Auth-User` headers

## Files modified
- `baafoo-server/src/main/java/com/baafoo/server/auth/AuthService.java` — added `checkPermission()` and `inferResourceFromPath()`
- `baafoo-server/src/main/java/com/baafoo/server/auth/AuthFilter.java` — new auth filter
- `baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java` — added AuthFilter to pipeline
