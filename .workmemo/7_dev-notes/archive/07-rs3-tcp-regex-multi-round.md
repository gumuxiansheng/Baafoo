# R-S3 TCP Regex Matching & Multi-Round Interaction

## Summary

Implemented three TCP stub enhancements:
- **R-S3 AC-02**: Regex pattern matching on hex string of request bytes
- **R-S3 AC-03**: Multi-round TCP interaction with per-connection state machine
- **R-S3 AC-05**: Offset-based byte matching (match specific byte ranges)

## Files Changed

### New Files
- `baafoo-core/src/main/java/com/baafoo/core/model/TcpRound.java` — Model for a single round in multi-round interaction
- `baafoo-core/src/test/java/com/baafoo/core/model/TcpRoundTest.java` — Unit tests for TcpRound

### Modified Files
- `baafoo-core/src/main/java/com/baafoo/core/model/Rule.java` — Added TCP-specific fields: `tcpRounds`, `tcpLoop`, `tcpPattern`, `tcpPrefixHex`, `tcpOffsetStart`, `tcpOffsetEnd`, `tcpOffsetHex`
- `baafoo-core/src/test/java/com/baafoo/core/model/RuleTest.java` — Added test for new TCP fields
- `baafoo-server/src/main/java/com/baafoo/server/handler/TcpStubHandler.java` — Major rewrite with regex/offset/multi-round support
- `baafoo-server/src/test/java/com/baafoo/server/handler/TcpStubHandlerTest.java` — Expanded from 3 to 21 tests

### Pre-existing Bug Fixes (required for build)
- `baafoo-server/src/main/java/com/baafoo/server/broker/KafkaProtocolDecoder.java` — Fixed typo `DESCCRIBE_CONFIGS` → `DESCRIBE_CONFIGS`
- `baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java` — Added missing `pulsarPort` variable declaration
- `baafoo-server/src/main/java/com/baafoo/server/broker/JmsMockBroker.java` — Updated Artemis API calls for 2.19.1 compatibility (`addAcceptorConfiguration`, `addAddressesSetting`, `RoutingType` import path)
- `pom.xml` — Updated `artemis.version` from 2.15.0 to 2.19.1 to resolve dependency version mismatch

## Design Decisions

### Model Design
- **TcpRound** is a standalone model (not nested in Rule) for clean separation. Each round has its own `pattern`, `prefixHex`, `offsetStart/End/Hex`, `conditions`, and `response`.
- **Rule-level TCP fields** (`tcpPattern`, `tcpPrefixHex`, `tcpOffsetStart/End/Hex`) support single-round TCP rules without needing the `tcpRounds` array.
- **tcpLoop** flag on Rule controls whether multi-round interactions loop back to round 0 after exhausting all rounds (default: false = close connection).

### Matching Logic
- All specified matchers within a round/rule are AND-ed (pattern AND prefixHex AND offset must all match).
- If no TCP-specific matchers are defined on a TcpRound, it matches anything (wildcard).
- Regex uses `Pattern.find()` (not `matches()`) so partial matches work — use `^...$` anchors for full-match behavior.
- Regex is applied to the **hex string** of the request bytes, not the raw string payload.
- Offset matching compares hex at `bytes[offsetStart..offsetEnd]` against `offsetHex`.

### Multi-Round State Machine
- Per-connection state tracked via Netty `AttributeKey` on the channel: `tcpRuleId` and `tcpRoundIndex`.
- On first request: match against rule's first round, bind connection to rule.
- On subsequent requests: look up bound rule, match next round's pattern, advance round index.
- After all rounds exhausted: close connection (or loop back if `tcpLoop=true`).
- If a round doesn't match: close the connection.
- Connection cleanup happens in `channelInactive()`.

### Matching Priority
1. TCP-specific matching (tcpRounds → tcpPattern/tcpPrefixHex/tcpOffset) checked first
2. Falls back to generic `MatchEngine` matching for rules without TCP-specific fields

## Test Results
- baafoo-core: 136 tests, 0 failures
- TcpStubHandlerTest: 21 tests, 0 failures
- Test coverage includes: regex match/no-match, offset match/no-match, prefix hex, multi-round 2-round flow, single-round close, multi-round loop, round mismatch close, combined pattern+offset AND logic, multi-round with offset, multi-round with prefixHex, fallback to MatchEngine

## Known Pre-existing Issues (Unrelated)
- JmsMockBrokerTest fails due to missing `commons-logging` dependency at test runtime
- PulsarMockBrokerTest fails due to missing inner class (`PulsarMockBrokerTest$1`)
- These are pre-existing issues not caused by this change
