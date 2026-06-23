# Environment Modes

## Overview

Baafoo environments control how the agent handles requests. Each environment
has a mode that determines whether to return stubs, pass through to real
services, or record traffic.

## Mode Behavior Reference

### STUB (`stub`)

- **Behavior**: Agent returns pre-configured stub responses for matching rules
- **Rule match**: Returns mock response from the matched rule
- **No rule match**: Request passes through to the real downstream service (no recording)
- **Use case**: Development and testing with mocked dependencies
- **Default mode**

### PASSTHROUGH (`passthrough`)

- **Behavior**: Agent forwards all requests to real downstream services
- **Rule match**: Request passes through (stub rules are ignored)
- **No rule match**: Request passes through
- **Recording**: None
- **Use case**: When you need real service behavior (production-like testing)

### RECORD (`record`)

- **Behavior**: Agent forwards requests to real services and records responses for matching rules
- **Rule match**: Request passes through AND is recorded for later stub creation
- **No rule match**: Request passes through (**not recorded**)
- **Stub responses**: None returned
- **Use case**: Capturing real traffic to create stub rules

### RECORD_AND_STUB (`record-and-stub`)

- **Behavior**: Agent returns stub responses AND records real responses simultaneously for matching rules
- **Rule match**: Returns mock response AND records the request
- **No rule match**: Request passes through (**not recorded**)
- **Use case**: Comparing stub vs real behavior, gradual migration from passthrough to stub

### RECORD_ALL (`record-all`)

- **Behavior**: Agent records ALL traffic regardless of rule match
- **Rule match**: Request passes through AND is recorded
- **No rule match**: Request passes through AND is recorded (**unique behavior**)
- **Use case**: Initial traffic capture, comprehensive recording

## Behavior Comparison Table

| Mode | Rule Match | No Rule Match | Recording |
|------|-----------|---------------|-----------|
| `stub` | Return mock | Passthrough | None |
| `passthrough` | Passthrough | Passthrough | None |
| `record` | Passthrough + Record | Passthrough | Only matched |
| `record-and-stub` | Return mock + Record | Passthrough | Only matched |
| `record-all` | Passthrough + Record | **Passthrough + Record** | **All requests** |

**Key Insight**: Only `record-all` mode records requests that don't match any rules. All other modes only record when there's a matching rule.

## Mode Transitions

```
PASSTHROUGH → RECORD_ALL → RECORD → RECORD_AND_STUB → STUB
     ↑                                                      |
     └──────────────────────────────────────────────────────┘
```

Typical workflow:
1. Start with PASSTHROUGH to verify connectivity
2. Switch to RECORD_ALL to capture all traffic
3. Create rules from recorded traffic
4. Switch to RECORD to validate rules against real responses
5. Use RECORD_AND_STUB to compare stub vs real behavior
6. Switch to STUB for isolated development

## Environment Variables

Environments can have variables (key-value pairs) that can be referenced
in rules. Use `metadata` for additional configuration.

## Agent Assignment

Each environment has a list of `agentIds`. Only agents assigned to an
environment will use that environment's mode and rules.
