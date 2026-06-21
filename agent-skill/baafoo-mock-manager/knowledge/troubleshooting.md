# Troubleshooting Guide

## Rule Not Matching

1. **Check environment mode**: If STUB mode, ensure rule is associated to environment
2. **Check protocol**: Rule protocol must match request protocol
3. **Check priority**: Lower priority rules may be shadowed by higher priority ones
4. **Check conditions**: Verify operator types (equals vs contains vs regex)
5. **Use recordings**: List recordings to see what the agent actually received

## Agent Not Responding

1. **Check agent status**: Use `list_agents` to verify agent is online
2. **Check environment assignment**: Agent must be assigned to the environment
3. **Check agent environment**: Agent's environment must match the request
4. **Check heartbeat**: lastHeartbeat should be within 60 seconds

## Authentication Failed

1. **JWT token**: Check if token is expired
2. **API Key**: Verify X-Api-Key header is set correctly
3. **Role permissions**: Check if role has permission for the action
4. **Auth disabled**: If auth is disabled, any request is accepted

## Recording Issues

1. **Environment mode**: Must be RECORD, RECORD_AND_STUB, or RECORD_ALL
2. **Agent recording**: Agent must be in recording mode
3. **Storage**: Check if storage backend (file/database) is writable
4. **Retention**: Old recordings may have been cleaned up

## TCP Mock Issues

1. **Port binding**: Ensure TCP port is available
2. **Hex format**: TCP pattern matching uses hex, not ASCII
3. **Rounds**: Check tcpRounds configuration for multi-round scenarios
4. **Loop**: Verify tcpLoop setting for continuous scenarios

## Performance Issues

1. **Rule count**: Too many rules can slow matching. Use priority effectively
2. **Recording volume**: Enable recording only when needed
3. **Agent resources**: Check agent CPU/memory usage
4. **Network**: Verify agent can reach downstream services in PASSTHROUGH mode

## Common Error Codes

| Code | Meaning | Action |
|------|---------|--------|
| 401 | Authentication failed | Check JWT/API Key |
| 403 | Permission denied | Check role permissions |
| 404 | Resource not found | Verify ID |
| 405 | Method not allowed | Use POST for MCP |
| 500 | Internal error | Check server logs |
