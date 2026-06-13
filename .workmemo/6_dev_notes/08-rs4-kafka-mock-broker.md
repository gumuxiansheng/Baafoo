# R-S4: Kafka Mock Broker — Dev Notes

## Summary

Implemented a Kafka Mock Broker that listens on port 9002 and handles a subset of the Kafka binary protocol, enabling Producer `send()` and Consumer `poll()` to work without a real Kafka cluster.

## Files Created

| File | Description |
|------|-------------|
| `baafoo-server/src/main/java/com/baafoo/server/broker/KafkaMessageStore.java` | In-memory message storage by topic-partition with offset tracking and preset message support |
| `baafoo-server/src/main/java/com/baafoo/server/broker/KafkaProtocolDecoder.java` | Netty ChannelInboundHandler that parses Kafka binary protocol requests and builds responses |
| `baafoo-server/src/main/java/com/baafoo/server/broker/KafkaMockBroker.java` | Netty server bootstrap on port 9002 with LengthFieldBasedFrameDecoder + KafkaProtocolDecoder pipeline |
| `baafoo-server/src/test/java/com/baafoo/server/broker/KafkaMockBrokerTest.java` | 11 tests covering ApiVersions, Metadata, Produce+Fetch, FindCoordinator, Heartbeat, OffsetCommit, and KafkaMessageStore unit tests |

## Files Modified

| File | Change |
|------|--------|
| `baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java` | Added `KafkaMockBroker` field, import, and startup/shutdown integration in `startProtocolServers()` and `stop()` |

## Architecture

```
Kafka Client → Agent (rewrites bootstrap.servers) → :9002
  └─ Netty ServerBootstrap
       └─ Pipeline: LengthFieldBasedFrameDecoder(4-byte prefix) → KafkaProtocolDecoder
            ├─ KafkaMessageStore (in-memory, ConcurrentHashMap<TopicPartition, PartitionLog>)
            └─ StorageService (for future rule-based preset messages)
```

## Kafka Protocol Implementation

### Supported APIs

| API Key | Name | Behavior |
|---------|------|----------|
| 0 | Produce | Stores record batch in KafkaMessageStore, returns offset + timestamp |
| 1 | Fetch | Reads from KafkaMessageStore by topic+partition+offset, returns RecordBatch data |
| 3 | Metadata | Returns 1 broker (self) + 1 partition per topic with leader=0 |
| 8 | OffsetCommit | Returns empty response (no-op) |
| 9 | OffsetFetch | Returns empty offsets |
| 10 | FindCoordinator | Returns self as coordinator (host+port) |
| 11 | JoinGroup | Returns empty members, generation=0 |
| 12 | Heartbeat | Returns error_code=0 |
| 13 | LeaveGroup | Returns error_code=0 |
| 14 | SyncGroup | Returns empty assignment |
| 15 | DescribeGroups | Returns empty groups array |
| 16 | ListGroups | Returns empty groups array |
| 18 | ApiVersions | Returns supported version ranges for all handled APIs |
| 32 | DescribeConfigs | Returns empty configs array |
| other | — | Returns empty/default response (no exception) |

### Key Design Decisions

1. **LengthFieldBasedFrameDecoder**: Kafka protocol uses 4-byte big-endian size prefix. The `LengthFieldBasedFrameDecoder` strips this prefix before the handler sees the data, and `frameResponse()` adds it back on the response.

2. **Produce handling**: The entire record batch is stored as-is in the message store. On Fetch, if the stored data looks like a RecordBatch (magic=2 at byte 16), it's returned directly with the baseOffset patched. For preset messages (from rules), a minimal RecordBatch wrapper is constructed.

3. **Response framing**: All responses are built in a ByteBuf starting with `correlationId`, then wrapped by `frameResponse()` which prepends the 4-byte size.

4. **Produce response order**: `throttle_time_ms` comes BEFORE the topics array (per Kafka protocol spec), not after.

5. **Varint encoding**: Used zigzag + unsigned varint for record encoding (matching Kafka's CompactArray/Varint format).

## Test Results

All 11 Kafka tests pass:
- `testApiVersionsRequest` — verifies correlation ID and error code
- `testMetadataRequest` — verifies broker info, topic, and partition metadata
- `testProduceAndFetch` — end-to-end produce then fetch with offset verification
- `testFindCoordinatorReturnsBroker` — verifies coordinator points to self
- `testHeartbeatReturnsSuccess` — verifies heartbeat returns error_code=0
- `testOffsetCommitReturnsEmpty` — verifies no exception on OffsetCommit
- `testMessageStoreAppendAndFetch` — unit test for message store
- `testMessageStoreFetchFromOffset` — verifies offset-based fetch
- `testMessageStoreEmptyTopic` — verifies empty result for unknown topic
- `testMessageStorePresetMessages` — verifies preset message injection
- `testMessageStoreClear` — verifies store clearing

## Pre-existing Issues Fixed (Not R-S4 Scope)

- Fixed `JmsMockBroker.java`: Changed `Configuration` interface to `ConfigurationImpl` concrete class for `addAcceptorConfig()` / `addAddressSetting()` method resolution
- Fixed `JmsMockBrokerTest.java`: Disambiguated `IllegalStateException` reference (javax.jms vs java.lang)

## Known Limitations

1. **No CRC validation**: RecordBatch CRC is set to 0 in preset messages. Real Kafka clients may warn but typically don't reject.
2. **Single broker**: Only 1 broker (node_id=0) is advertised. No replica support.
3. **No consumer groups**: JoinGroup/SyncGroup return empty responses. Consumer group rebalancing is not supported.
4. **No SASL/SSL**: Plain text only.
5. **API version range**: Supports API versions 0-12 for core APIs. Newer Kafka clients (3.x+) may negotiate higher versions that could have additional fields not handled.
