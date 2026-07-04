# Protocol-Specific Mocking

## HTTP

### Match Conditions
| Type | Operators | Description |
|------|-----------|-------------|
| method | (none) | HTTP method (GET/POST/PUT/DELETE/PATCH) |
| path | equals, contains, startsWith, regex | URL path |
| header | (key required) | Header value match |
| query | (key required) | Query parameter match |
| body | contains, jsonPath, regex | Request body match |
| bodyJsonPath | (jsonPath) | JSONPath expression match |

### Response Fields
- statusCode: HTTP status code
- headers: Response headers map
- body: Response body (string)
- delayMs: Response delay in milliseconds
- charset: Character encoding

## TCP

### Match Fields
- tcpPattern: Pattern to match in received data
- tcpPrefixHex: Hex prefix to match
- tcpOffsetStart/tcpOffsetEnd: Offset range for matching
- tcpOffsetHex: Hex value at specified offset

### Response Fields
- tcpRounds: List of TcpRound objects (multi-round response)
  - Each round: data (hex), delayMs
- tcpLoop: Whether to loop the rounds

## Kafka

### Match Fields
- topic: Kafka topic name
- header: Message header
- body: Message body

### Response Fields
- body: Response message body
- headers: Response headers

## Pulsar

Same structure as Kafka, with `topic` being the Pulsar topic.

## JMS

### Match Fields
- destination: JMS destination name
- destinationType: QUEUE or TOPIC
- header: Message header
- body: Message body

## gRPC

### Match Fields
| Type | Operators | Description |
|------|-----------|-------------|
| grpc.service | equals | gRPC 服务名（如 `helloworld.Greeter`） |
| grpc.method | equals | gRPC 方法名（如 `SayHello`） |
| path | equals | 完整路径（`/package.Service/Method`） |
| header | (key required) | gRPC metadata 匹配 |
| body | contains, jsonPath, regex | 请求体（JSON 格式的 protobuf 消息） |

### Response Fields
- statusCode: HTTP/2 状态码（200=正常）
- grpcStatus: gRPC 状态码（0=OK, 1=CANCELLED, 2=UNKNOWN, ...）
- grpcStatusMessage: gRPC 状态消息
- body: 响应体（JSON 格式的 protobuf 消息）
- headers: gRPC 响应 metadata
- delayMs: 响应延迟

### Streaming 支持
规则通过 `grpcStreaming` 字段配置流类型：
- `unary`（默认）：一元调用
- `client-streaming`：客户端流
- `server-streaming`：服务端流
- `bidi-streaming`：双向流

### gRPC 状态码
| 码值 | 名称 |
|------|------|
| 0 | OK |
| 1 | CANCELLED |
| 2 | UNKNOWN |
| 3 | INVALID_ARGUMENT |
| 4 | DEADLINE_EXCEEDED |
| 5 | NOT_FOUND |
| 6 | ALREADY_EXISTS |
| 7 | PERMISSION_DENIED |
| 8 | RESOURCE_EXHAUSTED |
| 13 | INTERNAL |
| 14 | UNAVAILABLE |
| 16 | UNAUTHENTICATED |

## MQ Relationships

MQ relationships define message flow between protocols:
- fromProtocol/fromTopic → toProtocol/toTopic
- Supports Kafka, Pulsar, JMS
- keyTemplate/valueTemplate for message transformation
- delayMs for delayed forwarding
