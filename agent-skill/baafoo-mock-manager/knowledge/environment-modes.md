# Environment Modes

## Overview

Baafoo environments control how the agent handles requests. Each environment
has a mode that determines whether to return stubs, pass through to real
services, or record traffic.

## Modes

### STUB (`stub`)
- Agent returns pre-configured stub responses
- Only rules associated to this environment are active
- Default mode for development and testing

### PASSTHROUGH (`passthrough`)
- Agent forwards all requests to real downstream services
- No stubbing, no recording
- Use when you need real service behavior

### RECORD (`record`)
- Agent forwards requests to real services and records responses
- Recorded responses can be used to create stub rules later
- No stub responses returned

### RECORD_AND_STUB (`record-and-stub`)
- Agent returns stub responses AND records real responses simultaneously
- Useful for comparing stub vs real behavior
- Best for gradual migration from passthrough to stub

### RECORD_ALL (`record-all`)
- Agent records ALL traffic regardless of rule match
- Most comprehensive recording mode
- Use for initial traffic capture

## Mode Transitions

```
PASSTHROUGH → RECORD → RECORD_AND_STUB → STUB
     ↑                                    |
     └────────────────────────────────────┘
```

Typical workflow:
1. Start with PASSTHROUGH to verify connectivity
2. Switch to RECORD_ALL to capture traffic
3. Create rules from recordings
4. Switch to STUB for development
5. Use RECORD_AND_STUB to validate stubs against real

## Environment Variables

Environments can have variables (key-value pairs) that can be referenced
in rules. Use `metadata` for additional configuration.

## Agent Assignment

Each environment has a list of `agentIds`. Only agents assigned to an
environment will use that environment's mode and rules.
