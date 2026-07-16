# R-S5: Pulsar Mock Broker

## Summary

Implemented a Pulsar Mock Broker that listens on port 9003 and handles a subset of the Apache Pulsar binary protocol. The Agent already intercepts `PulsarClient.builder().serviceUrl()` and replaces it with `pulsar://SERVER_HOST:9003`, so all Pulsar traffic from instrumented applications is routed to this broker.

## Acceptance Criteria Status

| AC | Description | Status |
|----|-------------|--------|
| AC-01 | Lookup phase — `lookupTopic` returns Mock Broker's own address | Done |
| AC-02 | Producer `send()` returns normal MessageId | Done |
| AC-03 | Consumer receives preset message sequences by subscription | Done |
| AC-07 | `getTopicsOfNamespace` returns topics from rules | Done |

## Architecture

### New Files (all in `com.baafoo.server.broker`)

| File | Purpose |
|------|---------|
| `PulsarMockBroker.java` | Netty server entry point on port 9003 |
| `PulsarFrameDecoder.java` | Netty ByteToMessageDecoder — reads Pulsar binary frames |
| `PulsarMockBrokerHandler.java` | Netty SimpleChannelInboundHandler — processes Pulsar commands |
| `PulsarProtobufCodec.java` | Minimal protobuf encode/decode for Pulsar commands (no Pulsar dependency) |
| `PulsarCommand.java` | Parsed command data class |
| `PulsarFrame.java` | Decoded frame (command + payload) |
| `PulsarMessageStore.java` | In-memory message storage by topic/subscription |

### Modified Files

| File | Change |
|------|--------|
| `BaafooServer.java` | Replaced generic TcpStubHandler for Pulsar with PulsarMockBroker; added pulsarBroker field and lifecycle management |

## Pulsar Binary Protocol Implementation

### Frame Format
```
[4 bytes totalSize] [4 bytes commandSize] [commandSize bytes protobuf] [payload bytes]
```

### Handled Commands

| Client → Broker | Broker → Client | Notes |
|-----------------|-----------------|-------|
| CONNECT (type=3) | CONNECTED (type=4) | Handshake |
| LOOKUP (type=15) | LOOKUP_RESPONSE (type=18) | Returns `pulsar://localhost:9003` |
| PARTITIONED_METADATA (type=16) | PARTITIONED_METADATA_RESPONSE (type=17) | Returns partitions=0 (non-partitioned) |
| PRODUCER (type=8) | PRODUCER_SUCCESS (type=24) | Assigns producer name |
| SEND (type=12) | SEND_RECEIPT (type=14) | Stores message, returns MessageId |
| SUBSCRIBE (type=5) | SUCCESS (type=25) | Registers subscription, delivers messages |
| FLOW (type=11) | — | Triggers message delivery |
| GET_TOPICS_OF_NAMESPACE (type=20) | Response (type=20) | Returns topics from rules |
| PING (type=1) | PONG (type=2) | Keep-alive |

### Key Design Decisions

1. **No Pulsar protobuf dependency**: All protobuf encoding/decoding is done by hand using varint/length-delimited wire format helpers. This avoids adding a large dependency.

2. **PulsarBaseCommand type values**: Based on Pulsar 2.10.x protocol:
   - PRODUCER_SUCCESS = 24, SUCCESS = 25
   - ProducerSuccess sub-message at field 29
   - Success sub-message at field 28

3. **Message delivery model**: Messages produced before subscription are delivered when the consumer subscribes (backfill). Messages produced after subscription are delivered immediately via the FLOW mechanism.

4. **Topic discovery**: `getTopicsOfNamespace` extracts topics from Pulsar rules in the storage service. If no rules are configured, a default topic is returned.

## Testing

11 tests in `PulsarMockBrokerTest`:

- `testConnectHandshake` — CONNECT → CONNECTED
- `testLookupReturnsSelf` — LOOKUP → LOOKUP_RESPONSE with self address
- `testPartitionedMetadataReturnsNonPartitioned` — PARTITIONED_METADATA → response
- `testPingPong` — PING → PONG
- `testMessageStoreStoreAndRetrieve` — message storage with ledger/entry IDs
- `testMessageStoreSubscriptionDelivery` — subscription receives existing messages
- `testMessageStoreGetTopicsOfNamespace` — namespace topic filtering
- `testProtobufVarintEncoding` — varint encoding correctness
- `testProtobufVarintRoundTrip` — varint encode/decode round-trip
- `testProtobufVarint64RoundTrip` — varint64 encode/decode round-trip
- `testProtobufCommandDecoding` — full command decode with sub-message parsing

All 86 server module tests pass (including Kafka and Pulsar broker tests).

## Known Limitations (v1)

- Non-partitioned topics only (PARTITIONED_METADATA always returns partitions=0)
- Single producer per topic (no multiplexing)
- Shared subscription only (Exclusive/Failover not specifically handled)
- No TLS support
- No schema validation
- No batch message support
- Consumer ID assignment is per-connection (not globally unique)

## Pre-existing Issues

- `JmsMockBroker.java` has compilation errors (Artemis API mismatch) — not related to this change
- `TcpStubHandlerTest.java` has compilation errors (missing `TcpRound` class) — not related to this change
