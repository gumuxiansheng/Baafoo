# Mock Rule Design Best Practices

## Rule Structure

A Mock rule defines how Baafoo responds to incoming requests. Key fields:

- **id**: Unique identifier (kebab-case recommended)
- **name**: Human-readable name
- **protocol**: `http`, `tcp`, `kafka`, `pulsar`, `jms`, `grpc`
- **conditions**: List of match conditions (method, path, header, query, body, etc.)
- **responses**: List of response entries (status, headers, body, delay)
- **priority**: Lower number = higher priority (default 100)
- **environments**: Which environments this rule is active in

## HTTP Rules

### Basic GET mock
```json
{
  "id": "user-get",
  "name": "Get User",
  "protocol": "http",
  "conditions": [
    {"type": "method", "value": "GET"},
    {"type": "path", "operator": "equals", "value": "/api/users/123"}
  ],
  "responses": [
    {"statusCode": 200, "body": "{\"id\":123,\"name\":\"Alice\"}"}
  ]
}
```

### Path with regex
```json
{"type": "path", "operator": "regex", "value": "/api/users/\\d+"}
```

### Conditional response
```json
"responses": [
  {"condition": {"type": "header", "key": "Accept", "value": "application/xml"},
   "statusCode": 200, "body": "<user><id>123</id></user>"},
  {"statusCode": 200, "body": "{\"id\":123}"}
]
```

## TCP Rules

TCP rules support advanced matching:
- **tcpRounds**: Multi-round response sequences
- **tcpLoop**: Loop the rounds
- **tcpPattern**: Pattern to match in received data
- **tcpPrefixHex**: Hex prefix matching
- **tcpOffsetStart/End**: Offset-based matching
- **tcpOffsetHex**: Hex value at offset

## Priority Strategy

- 1-10: Override rules (highest priority)
- 10-50: Specific match rules
- 50-100: General rules
- 100+: Fallback rules

## Environment Association

Rules are associated to environments. When an environment is in STUB mode,
only associated rules are active. In RECORD mode, rules are inactive and
real responses are recorded.

## Common Patterns

1. **CRUD mock**: Create 4 rules (GET/POST/PUT/DELETE) for a resource
2. **Error simulation**: Add low-priority error response rules
3. **Timeout simulation**: Use delayMs in response
4. **Dynamic response**: Use fakerSeed for randomized data
