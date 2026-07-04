# Baafoo Mock Manager

> MCP-compatible skill for managing Baafoo Mock Platform — rules, environments, scenes, recordings, agents, and MQ relationships.

## When to Use

- Create/update/delete Mock rules for HTTP/TCP/gRPC/Kafka/Pulsar/JMS protocols
- Manage environments (STUB / PASSTHROUGH / RECORD / RECORD_AND_STUB / RECORD_ALL)
- Query or manage scene sets
- List/delete recordings
- View agent status
- Manage MQ relationship mappings
- Export rules as OpenAPI 3.0
- Check system status

## MCP Server

**Endpoint:** `POST http://<host>:8084/__baafoo__/api/mcp`

**Authentication:** Bearer JWT or `X-Api-Key` header (same as Baafoo Management API)

**Protocol:** JSON-RPC 2.0

### Quick Start

```bash
# Initialize
curl -X POST http://localhost:8084/__baafoo__/api/mcp \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: <your-key>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'

# List tools
curl -X POST http://localhost:8084/__baafoo__/api/mcp \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: <your-key>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# Call a tool
curl -X POST http://localhost:8084/__baafoo__/api/mcp \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: <your-key>" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_rules","arguments":{"page":1,"size":10}}}'
```

## Tool Categories

### Rules (6 tools)
| Tool | Safety | Description |
|------|--------|-------------|
| `list_rules` | READ_ONLY | List rules with filters (protocol/keyword/environment/host) |
| `get_rule` | READ_ONLY | Get rule detail by ID |
| `create_rule` | CONTROLLED_WRITE | Create new Mock rule |
| `update_rule` | CONTROLLED_WRITE | Update existing rule |
| `delete_rule` | AUDIT_REQUIRED | Delete rule |
| `undo_rule` | CONTROLLED_WRITE | Undo last rule change |

### Environments (6 tools)
| Tool | Safety | Description |
|------|--------|-------------|
| `list_environments` | READ_ONLY | List all environments |
| `get_environment` | READ_ONLY | Get environment by ID |
| `create_environment` | CONTROLLED_WRITE | Create environment (admin only) |
| `update_environment` | CONTROLLED_WRITE | Update environment (admin only) |
| `delete_environment` | AUDIT_REQUIRED | Delete environment (admin only) |
| `associate_rules` | CONTROLLED_WRITE | Associate/dissociate rules to environment |

### Scenes (5 tools)
| Tool | Safety | Description |
|------|--------|-------------|
| `list_scenes` | READ_ONLY | List all scene sets |
| `get_scene` | READ_ONLY | Get scene set by ID |
| `create_scene` | CONTROLLED_WRITE | Create scene set |
| `update_scene` | CONTROLLED_WRITE | Update scene set |
| `delete_scene` | AUDIT_REQUIRED | Delete scene set |

### Recordings (3 tools)
| Tool | Safety | Description |
|------|--------|-------------|
| `list_recordings` | READ_ONLY | List recordings with filters |
| `get_recording_stats` | READ_ONLY | Get recording statistics |
| `delete_recording` | AUDIT_REQUIRED | Delete recording |

### MQ Relationships (3 tools)
| Tool | Safety | Description |
|------|--------|-------------|
| `list_mq_relationships` | READ_ONLY | List MQ relationship mappings |
| `create_mq_relationship` | CONTROLLED_WRITE | Create MQ relationship |
| `delete_mq_relationship` | AUDIT_REQUIRED | Delete MQ relationship |

### Agents (2 tools)
| Tool | Safety | Description |
|------|--------|-------------|
| `list_agents` | READ_ONLY | List all registered agents |
| `get_agent` | READ_ONLY | Get agent details |

### System (2 tools)
| Tool | Safety | Description |
|------|--------|-------------|
| `get_system_status` | READ_ONLY | Get system status overview |
| `export_openapi` | READ_ONLY | Export rules as OpenAPI 3.0 |

## Workflows

### Create a complete Mock setup

1. Create environment (STUB mode)
2. Create rules with conditions and responses
3. Associate rules to environment
4. Verify with list_rules

### Debug a Mock issue

1. `get_rule` to check rule configuration
2. `list_recordings` to see actual requests
3. Check if environment is in correct mode
4. Update rule if needed

### Export documentation

1. `export_openapi` to generate OpenAPI spec
2. Save to file for sharing

## Safety Levels

- **READ_ONLY**: No side effects, safe to call anytime
- **CONTROLLED_WRITE**: Modifies data, requires developer/admin role
- **AUDIT_REQUIRED**: Destructive operation, requires developer/admin role

## Notes

- All write operations require authentication (JWT or API Key)
- Role hierarchy: admin > developer > tester > guest
- Environment mode controls agent behavior (STUB/PASSTHROUGH/RECORD/etc.)
- Rules support HTTP, TCP, gRPC, Kafka, Pulsar, JMS protocols
- TCP rules support advanced features: rounds, loop, pattern matching, offset matching
