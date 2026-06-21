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

## MQ Relationships

MQ relationships define message flow between protocols:
- fromProtocol/fromTopic → toProtocol/toTopic
- Supports Kafka, Pulsar, JMS
- keyTemplate/valueTemplate for message transformation
- delayMs for delayed forwarding
