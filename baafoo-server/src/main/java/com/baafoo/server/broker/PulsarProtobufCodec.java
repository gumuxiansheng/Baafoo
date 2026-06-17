package com.baafoo.server.broker;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Protobuf encode/decode for Pulsar binary protocol commands.
 *
 * <p>Implements just enough protobuf wire-format handling to parse and
 * generate the key Pulsar command types. Does NOT depend on the actual
 * Pulsar protobuf library — all encoding/decoding is done by hand.</p>
 *
 * <h3>Protobuf wire format refresher</h3>
 * <ul>
 *   <li>Tag = (field_number &lt;&lt; 3) | wire_type</li>
 *   <li>Wire type 0 = varint (int32, int64, bool, enum)</li>
 *   <li>Wire type 2 = length-delimited (string, bytes, embedded message)</li>
 * </ul>
 *
 * <h3>PulsarBaseCommand field numbers (Pulsar 2.10.x lightproto)</h3>
 * <pre>
 *   Type type                              = 1;
 *   Connect connect                        = 2;
 *   Connected connected                    = 3;
 *   Subscribe subscribe                    = 4;
 *   Producer producer                      = 5;
 *   Send send                              = 6;
 *   SendReceipt sendReceipt                = 7;
 *   SendError sendError                    = 8;
 *   Message message                        = 9;
 *   Ack ack                                = 10;
 *   Flow flow                              = 11;
 *   Unsubscribe unsubscribe                = 12;
 *   Success success                        = 13;
 *   Error error                            = 14;
 *   CloseProducer closeProducer            = 15;
 *   CloseConsumer closeConsumer            = 16;
 *   ProducerSuccess producerSuccess        = 17;
 *   Ping ping                              = 18;
 *   Pong pong                              = 19;
 *   RedeliverUnacknowledgedMessages        = 20;
 *   PartitionMetadata partitionMetadata    = 21;
 *   PartitionMetadataResponse              = 22;
 *   LookupTopic lookupTopic                = 23;
 *   LookupTopicResponse lookupTopicResponse= 24;
 *   ConsumerStats consumerStats            = 25;
 *   ConsumerStatsResponse                  = 26;
 *   ReachedEndOfTopic                      = 27;
 *   Seek seek                              = 28;
 *   GetLastMessageId                       = 29;
 *   GetLastMessageIdResponse               = 30;
 *   ActiveConsumerChange                   = 31;
 *   GetTopicsOfNamespace                   = 32;
 *   GetTopicsOfNamespaceResponse           = 33;
 *   GetSchema                              = 34;
 *   GetSchemaResponse                      = 35;
 *   AuthChallenge                          = 36;
 *   AuthResponse                           = 37;
 *   AckResponse                            = 38;
 * </pre>
 *
 * <h3>Pulsar command Type enum values (Pulsar 2.10.x lightproto)</h3>
 * <pre>
 *   CONNECT = 2;  CONNECTED = 3;
 *   SUBSCRIBE = 4;  PRODUCER = 5;
 *   SEND = 6;  SEND_RECEIPT = 7;  SEND_ERROR = 8;
 *   MESSAGE = 9;  ACK = 10;  FLOW = 11;
 *   UNSUBSCRIBE = 12;  SUCCESS = 13;  ERROR = 14;
 *   CLOSE_PRODUCER = 15;  CLOSE_CONSUMER = 16;
 *   PRODUCER_SUCCESS = 17;
 *   PING = 18;  PONG = 19;
 *   REDELIVER_UNACKNOWLEDGED_MESSAGES = 20;
 *   PARTITIONED_METADATA = 21;
 *   PARTITIONED_METADATA_RESPONSE = 22;
 *   LOOKUP = 23;  LOOKUP_RESPONSE = 24;
 *   CONSUMER_STATS = 25;  CONSUMER_STATS_RESPONSE = 26;
 *   REACHED_END_OF_TOPIC = 27;
 *   SEEK = 28;
 *   GET_TOPICS_OF_NAMESPACE = 32;
 *   GET_TOPICS_OF_NAMESPACE_RESPONSE = 33;
 *   AUTH_CHALLENGE = 36;  AUTH_RESPONSE = 37;
 * </pre>
 */
final class PulsarProtobufCodec {

    // ---- PulsarBaseCommand Type enum (Pulsar 2.10.x lightproto) ----
    static final int TYPE_CONNECT = 2;
    static final int TYPE_CONNECTED = 3;
    static final int TYPE_SUBSCRIBE = 4;
    static final int TYPE_PRODUCER = 5;
    static final int TYPE_SEND = 6;
    static final int TYPE_SEND_RECEIPT = 7;
    static final int TYPE_SEND_ERROR = 8;
    static final int TYPE_MESSAGE = 9;
    static final int TYPE_ACK = 10;
    static final int TYPE_FLOW = 11;
    static final int TYPE_UNSUBSCRIBE = 12;
    static final int TYPE_SUCCESS = 13;
    static final int TYPE_ERROR = 14;
    static final int TYPE_CLOSE_PRODUCER = 15;
    static final int TYPE_CLOSE_CONSUMER = 16;
    static final int TYPE_PRODUCER_SUCCESS = 17;
    static final int TYPE_PING = 18;
    static final int TYPE_PONG = 19;
    static final int TYPE_REDELIVER_UNACKNOWLEDGED_MESSAGES = 20;
    static final int TYPE_PARTITIONED_METADATA = 21;
    static final int TYPE_PARTITIONED_METADATA_RESPONSE = 22;
    static final int TYPE_LOOKUP = 23;
    static final int TYPE_LOOKUP_RESPONSE = 24;
    static final int TYPE_CONSUMER_STATS = 25;
    static final int TYPE_GET_TOPICS_OF_NAMESPACE = 32;
    static final int TYPE_GET_TOPICS_OF_NAMESPACE_RESPONSE = 33;
    static final int TYPE_AUTH_CHALLENGE = 36;
    static final int TYPE_AUTH_RESPONSE = 37;
    // Legacy aliases for handler compatibility
    static final int TYPE_CLOSE = TYPE_CLOSE_PRODUCER;

    // ---- PulsarBaseCommand field numbers (Pulsar 2.10.x lightproto) ----
    static final int FIELD_TYPE = 1;
    static final int FIELD_CONNECT = 2;
    static final int FIELD_CONNECTED = 3;
    static final int FIELD_SUBSCRIBE = 4;
    static final int FIELD_PRODUCER = 5;
    static final int FIELD_SEND = 6;
    static final int FIELD_SEND_RECEIPT = 7;
    static final int FIELD_SEND_ERROR = 8;
    static final int FIELD_MESSAGE = 9;
    static final int FIELD_ACK = 10;
    static final int FIELD_FLOW = 11;
    static final int FIELD_UNSUBSCRIBE = 12;
    static final int FIELD_SUCCESS = 13;
    static final int FIELD_ERROR = 14;
    static final int FIELD_CLOSE_PRODUCER = 15;
    static final int FIELD_CLOSE_CONSUMER = 16;
    static final int FIELD_PRODUCER_SUCCESS = 17;
    static final int FIELD_PING = 18;
    static final int FIELD_PONG = 19;
    static final int FIELD_PARTITION_METADATA = 21;
    static final int FIELD_PARTITION_METADATA_RESPONSE = 22;
    static final int FIELD_LOOKUP_TOPIC = 23;
    static final int FIELD_LOOKUP_TOPIC_RESPONSE = 24;
    static final int FIELD_GET_TOPICS_OF_NAMESPACE = 32;
    static final int FIELD_GET_TOPICS_OF_NAMESPACE_RESPONSE = 33;
    static final int FIELD_AUTH_CHALLENGE = 36;

    // ---- Sub-message field numbers (Pulsar 2.10.x lightproto, verified from bytecode) ----
    // Connect: client_version=1, auth_method=2, auth_data=3, protocol_version=4, auth_method_name=5
    // Connected: server_version=1, protocol_version=2, max_message_size=3
    // LookupTopic: topic=1, requestId=2, authoritative=3
    // LookupTopicResponse: brokerServiceUrl=1, brokerServiceUrlTls=2, response=3, requestId=4, authoritative=5
    //   LookupType enum: Redirect=0, Connect=1, Failed=2
    // PartitionMetadataRequest: topic=1, requestId=2
    // PartitionMetadataResponse: partitions=1, requestId=2, response=3, error=4, message=5
    // Producer: topic=1, producerId=2, requestId=3, producerName=4
    // ProducerSuccess: requestId=1, producerName=2, lastSequenceId=3, schemaVersion=4, topicEpoch=5, producerReady=6
    // Send: producerId=1, sequenceId=2, numMessages=3
    // SendReceipt: producerId=1, sequenceId=2, messageId=3, highestSequenceId=4
    //   MessageIdData: ledgerId=1, entryId=2, partition=3, batch_index=4
    // Subscribe: topic=1, subscription=2, subType=3, consumerId=4, requestId=5, consumerName=6
    //   SubType enum: Exclusive=0, Shared=1, Failover=2
    // Success: requestId=1, schema=2
    // GetTopicsOfNamespace: requestId=1, namespace=2, mode=3
    // GetTopicsOfNamespaceResponse: requestId=1, topics=2
    // Ping: (empty message, field 18 in BaseCommand)
    // Pong: (empty message, field 19 in BaseCommand)

    // ---- LookupType enum ----
    static final int LOOKUP_TYPE_REDIRECT = 0;
    static final int LOOKUP_TYPE_CONNECT = 1;
    static final int LOOKUP_TYPE_FAILED = 2;

    private PulsarProtobufCodec() {}

    // ======================== Decoding ========================

    /**
     * Parse a PulsarBaseCommand from raw bytes and return a PulsarCommand.
     */
    static PulsarCommand decodeCommand(byte[] data) {
        PulsarCommand cmd = new PulsarCommand();
        int[] pos = {0};
        while (pos[0] < data.length) {
            int tag = readVarint(data, pos);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case FIELD_TYPE:
                    cmd.type = readVarint(data, pos);
                    break;
                case FIELD_CONNECT:
                    cmd.connectData = readLengthDelimited(data, pos);
                    parseConnect(cmd);
                    break;
                case FIELD_SUBSCRIBE:
                    cmd.subscribeData = readLengthDelimited(data, pos);
                    parseSubscribe(cmd);
                    break;
                case FIELD_PRODUCER:
                    cmd.producerData = readLengthDelimited(data, pos);
                    parseProducer(cmd);
                    break;
                case FIELD_SEND:
                    cmd.sendData = readLengthDelimited(data, pos);
                    parseSend(cmd);
                    break;
                case FIELD_ACK:
                    cmd.ackData = readLengthDelimited(data, pos);
                    break;
                case FIELD_FLOW:
                    cmd.flowData = readLengthDelimited(data, pos);
                    parseFlow(cmd);
                    break;
                case FIELD_LOOKUP_TOPIC:
                    cmd.lookupTopicData = readLengthDelimited(data, pos);
                    parseLookupTopic(cmd);
                    break;
                case FIELD_PARTITION_METADATA:
                    cmd.partitionMetadataRequestData = readLengthDelimited(data, pos);
                    parsePartitionMetadataRequest(cmd);
                    break;
                case FIELD_GET_TOPICS_OF_NAMESPACE:
                    cmd.getTopicsOfNamespaceData = readLengthDelimited(data, pos);
                    parseGetTopicsOfNamespace(cmd);
                    break;
                case FIELD_CLOSE_PRODUCER:
                case FIELD_CLOSE_CONSUMER:
                    readLengthDelimited(data, pos); // consume the sub-message
                    break;
                case FIELD_PING:
                case FIELD_PONG:
                    // Ping/Pong have no sub-message data
                    break;
                default:
                    skipField(data, pos, wireType);
                    break;
            }
        }
        return cmd;
    }

    private static void parseConnect(PulsarCommand cmd) {
        if (cmd.connectData == null) return;
        int[] pos = {0};
        byte[] data = cmd.connectData;
        while (pos[0] < data.length) {
            int tag = readVarint(data, pos);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1: // client_version (Pulsar 2.10.x lightproto)
                    cmd.clientVersion = readString(data, pos);
                    break;
                case 2: // auth_method (enum)
                    readVarint(data, pos);
                    break;
                case 3: // auth_data (bytes)
                    cmd.authData = readBytes(data, pos);
                    break;
                case 4: // protocol_version
                    cmd.protocolVersion = readVarint(data, pos);
                    break;
                case 5: // auth_method_name
                    cmd.authMethodName = readString(data, pos);
                    break;
                default:
                    skipField(data, pos, wireType);
                    break;
            }
        }
    }

    private static void parseSubscribe(PulsarCommand cmd) {
        if (cmd.subscribeData == null) return;
        int[] pos = {0};
        byte[] data = cmd.subscribeData;
        while (pos[0] < data.length) {
            int tag = readVarint(data, pos);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1: // topic
                    cmd.topic = readString(data, pos);
                    break;
                case 2: // subscription
                    cmd.subscription = readString(data, pos);
                    break;
                case 3: // subType
                    cmd.subType = readVarint(data, pos);
                    break;
                case 4: // consumerId (int64) — skip
                    readVarint64(data, pos);
                    break;
                case 5: // requestId (int64)
                    cmd.requestId = readVarint64(data, pos);
                    break;
                case 6: // consumerName
                    cmd.consumerName = readString(data, pos);
                    break;
                default:
                    skipField(data, pos, wireType);
                    break;
            }
        }
    }

    private static void parseProducer(PulsarCommand cmd) {
        if (cmd.producerData == null) return;
        int[] pos = {0};
        byte[] data = cmd.producerData;
        while (pos[0] < data.length) {
            int tag = readVarint(data, pos);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1: // topic
                    cmd.topic = readString(data, pos);
                    break;
                case 2: // producerId (int64)
                    cmd.producerId = readVarint64(data, pos);
                    break;
                case 3: // requestId (int64)
                    cmd.requestId = readVarint64(data, pos);
                    break;
                case 4: // producerName
                    cmd.producerName = readString(data, pos);
                    break;
                default:
                    skipField(data, pos, wireType);
                    break;
            }
        }
    }

    private static void parseSend(PulsarCommand cmd) {
        if (cmd.sendData == null) return;
        int[] pos = {0};
        byte[] data = cmd.sendData;
        while (pos[0] < data.length) {
            int tag = readVarint(data, pos);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1: // producerId (int64)
                    cmd.producerId = readVarint64(data, pos);
                    break;
                case 2: // sequenceId
                    cmd.sequenceId = readVarint64(data, pos);
                    break;
                case 3: // numMessages (batch)
                    cmd.numMessages = readVarint(data, pos);
                    break;
                default:
                    skipField(data, pos, wireType);
                    break;
            }
        }
    }

    private static void parseLookupTopic(PulsarCommand cmd) {
        if (cmd.lookupTopicData == null) return;
        int[] pos = {0};
        byte[] data = cmd.lookupTopicData;
        while (pos[0] < data.length) {
            int tag = readVarint(data, pos);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1: // topic
                    cmd.topic = readString(data, pos);
                    break;
                case 2: // requestId (int64)
                    cmd.requestId = readVarint64(data, pos);
                    break;
                case 3: // authoritative
                    cmd.authoritative = readVarint(data, pos) != 0;
                    break;
                default:
                    skipField(data, pos, wireType);
                    break;
            }
        }
    }

    private static void parsePartitionMetadataRequest(PulsarCommand cmd) {
        if (cmd.partitionMetadataRequestData == null) return;
        int[] pos = {0};
        byte[] data = cmd.partitionMetadataRequestData;
        while (pos[0] < data.length) {
            int tag = readVarint(data, pos);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1: // topic
                    cmd.topic = readString(data, pos);
                    break;
                case 2: // requestId (int64)
                    cmd.requestId = readVarint64(data, pos);
                    break;
                default:
                    skipField(data, pos, wireType);
                    break;
            }
        }
    }

    private static void parseGetTopicsOfNamespace(PulsarCommand cmd) {
        if (cmd.getTopicsOfNamespaceData == null) return;
        int[] pos = {0};
        byte[] data = cmd.getTopicsOfNamespaceData;
        while (pos[0] < data.length) {
            int tag = readVarint(data, pos);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1: // requestId (int64)
                    cmd.requestId = readVarint64(data, pos);
                    break;
                case 2: // namespace
                    cmd.namespaceName = readString(data, pos);
                    break;
                default:
                    skipField(data, pos, wireType);
                    break;
            }
        }
    }

    private static void parseFlow(PulsarCommand cmd) {
        if (cmd.flowData == null) return;
        int[] pos = {0};
        byte[] data = cmd.flowData;
        while (pos[0] < data.length) {
            int tag = readVarint(data, pos);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1: // messagePermits
                    cmd.messagePermits = readVarint(data, pos);
                    break;
                default:
                    skipField(data, pos, wireType);
                    break;
            }
        }
    }

    // ======================== Encoding ========================

    /**
     * Encode a CONNECTED response.
     */
    static ByteBuf encodeConnected(String serverVersion, int protocolVersion) {
        byte[] connectedMsg = encodeConnectedMessage(serverVersion, protocolVersion);
        return encodeBaseCommand(TYPE_CONNECTED, FIELD_CONNECTED, connectedMsg);
    }

    /**
     * Encode a LOOKUP_TOPIC_RESPONSE (redirect to self).
     */
    static ByteBuf encodeLookupTopicResponse(String brokerServiceUrl, long requestId) {
        byte[] responseMsg = encodeLookupTopicResponseMessage(brokerServiceUrl, requestId);
        return encodeBaseCommand(TYPE_LOOKUP_RESPONSE, FIELD_LOOKUP_TOPIC_RESPONSE, responseMsg);
    }

    /**
     * Encode a PARTITIONED_METADATA_RESPONSE (non-partitioned: partitions=0).
     */
    static ByteBuf encodePartitionMetadataResponse(int partitions, long requestId) {
        byte[] responseMsg = encodePartitionMetadataResponseMessage(partitions, requestId);
        return encodeBaseCommand(TYPE_PARTITIONED_METADATA_RESPONSE, FIELD_PARTITION_METADATA_RESPONSE, responseMsg);
    }

    /**
     * Encode a PRODUCER_SUCCESS response.
     * Type.PRODUCER_SUCCESS = 17, ProducerSuccess sub-message at field 17.
     */
    static ByteBuf encodeProducerSuccess(String producerName, long requestId) {
        byte[] successMsg = encodeProducerSuccessMessage(producerName, requestId);
        return encodeBaseCommand(TYPE_PRODUCER_SUCCESS, FIELD_PRODUCER_SUCCESS, successMsg);
    }

    /**
     * Encode a SEND_RECEIPT response.
     */
    static ByteBuf encodeSendReceipt(long producerId, long sequenceId, long ledgerId, long entryId) {
        byte[] receiptMsg = encodeSendReceiptMessage(producerId, sequenceId, ledgerId, entryId);
        return encodeBaseCommand(TYPE_SEND_RECEIPT, FIELD_SEND_RECEIPT, receiptMsg);
    }

    /**
     * Encode a SUCCESS response (for subscribe ack).
     * Type.SUCCESS = 13, Success sub-message at field 13.
     */
    static ByteBuf encodeSuccess(long requestId) {
        byte[] successMsg = encodeSuccessMessage(requestId);
        return encodeBaseCommand(TYPE_SUCCESS, FIELD_SUCCESS, successMsg);
    }

    /**
     * Encode a SUBSCRIBE success response.
     * In Pulsar protocol, the broker responds to SUBSCRIBE with a
     * SUCCESS command (type=13) containing the Success sub-message (field=13).
     */
    static ByteBuf encodeSubscribeSuccess(long requestId) {
        byte[] successMsg = encodeSuccessMessage(requestId);
        return encodeBaseCommand(TYPE_SUCCESS, FIELD_SUCCESS, successMsg);
    }

    /**
     * Encode a GET_TOPICS_OF_NAMESPACE_RESPONSE.
     */
    static ByteBuf encodeGetTopicsOfNamespaceResponse(List<String> topics, long requestId) {
        byte[] responseMsg = encodeGetTopicsOfNamespaceResponseMessage(topics, requestId);
        return encodeBaseCommand(TYPE_GET_TOPICS_OF_NAMESPACE_RESPONSE, FIELD_GET_TOPICS_OF_NAMESPACE_RESPONSE, responseMsg);
    }

    /**
     * Encode a MESSAGE command (broker → consumer).
     * The payload (message body) is sent as the frame payload, not inside the command.
     */
    static ByteBuf encodeMessage(long ledgerId, long entryId, int partition,
                                  String topic, int consumerId,
                                  byte[] payload) {
        byte[] messageCmd = encodeMessageCommand(ledgerId, entryId, partition, topic, consumerId);
        return encodeBaseCommandWithPayload(TYPE_MESSAGE, FIELD_MESSAGE, messageCmd, payload);
    }

    /**
     * Encode a PONG response.
     * PONG must include an empty CommandPong sub-message at field 19,
     * matching the Pulsar protocol (PING includes empty CommandPing at field 18).
     */
    static ByteBuf encodePong() {
        return encodeBaseCommand(TYPE_PONG, FIELD_PONG, new byte[0]);
    }

    // ======================== Low-level encoding helpers ========================

    private static byte[] encodeConnectedMessage(String serverVersion, int protocolVersion) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // server_version (field 1, string)
        writeStringField(out, 1, serverVersion);
        // protocol_version (field 2, varint)
        writeVarintField(out, 2, protocolVersion);
        return out.toByteArray();
    }

    private static byte[] encodeLookupTopicResponseMessage(String brokerServiceUrl, long requestId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // brokerServiceUrl (field 1, string)
        writeStringField(out, 1, brokerServiceUrl);
        // response (field 3, enum) = Connect (1)
        writeVarintField(out, 3, LOOKUP_TYPE_CONNECT);
        // requestId (field 4, varint)
        writeVarintField64(out, 4, requestId);
        return out.toByteArray();
    }

    private static byte[] encodePartitionMetadataResponseMessage(int partitions, long requestId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // partitions (field 1, varint) — 0 means non-partitioned
        writeVarintField(out, 1, partitions);
        // requestId (field 2, varint)
        writeVarintField64(out, 2, requestId);
        // response (field 3, enum) = Success (0)
        writeVarintField(out, 3, 0);
        return out.toByteArray();
    }

    private static byte[] encodeProducerSuccessMessage(String producerName, long requestId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // requestId (field 1, varint int64)
        writeVarintField64(out, 1, requestId);
        // producer_name (field 2, string)
        writeStringField(out, 2, producerName);
        // schema_version (field 4, bytes) — must be present (even if empty) to avoid
        // IllegalStateException in Pulsar 2.10.x lightproto ClientCnx.handleProducerSuccess
        writeBytesField(out, 4, new byte[0]);
        return out.toByteArray();
    }

    private static byte[] encodeSendReceiptMessage(long producerId, long sequenceId,
                                                    long ledgerId, long entryId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // producerId (field 1, varint int64)
        writeVarintField64(out, 1, producerId);
        // sequenceId (field 2, varint)
        writeVarintField64(out, 2, sequenceId);
        // messageId (field 3, embedded MessageIdData)
        byte[] messageIdData = encodeMessageIdData(ledgerId, entryId);
        writeBytesField(out, 3, messageIdData);
        // highestSequenceId (field 4, varint)
        writeVarintField64(out, 4, sequenceId);
        return out.toByteArray();
    }

    private static byte[] encodeMessageIdData(long ledgerId, long entryId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // ledgerId (field 1, varint)
        writeVarintField64(out, 1, ledgerId);
        // entryId (field 2, varint)
        writeVarintField64(out, 2, entryId);
        return out.toByteArray();
    }

    private static byte[] encodeSuccessMessage(long requestId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // requestId (field 1, varint)
        writeVarintField64(out, 1, requestId);
        return out.toByteArray();
    }

    private static byte[] encodeGetTopicsOfNamespaceResponseMessage(List<String> topics, long requestId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // requestId (field 1, varint)
        writeVarintField64(out, 1, requestId);
        // topics (field 2, repeated string)
        for (String topic : topics) {
            writeStringField(out, 2, topic);
        }
        return out.toByteArray();
    }

    private static byte[] encodeMessageCommand(long ledgerId, long entryId, int partition,
                                                String topic, int consumerId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // CommandMessage fields (Pulsar 2.10.x lightproto):
        //   required uint64 consumer_id     = 1;
        //   required MessageIdData message_id = 2;
        //   optional int32 redelivery_count = 3;
        //   repeated int64 ack_set          = 4;
        //   optional uint64 consumer_epoch  = 5;

        // consumer_id (field 1, varint int64)
        writeVarintField64(out, 1, consumerId);
        // message_id (field 2, embedded MessageIdData)
        byte[] messageIdData = encodeMessageIdData(ledgerId, entryId);
        writeBytesField(out, 2, messageIdData);
        return out.toByteArray();
    }

    // ======================== Frame encoding ========================

    /**
     * Encode a full Pulsar frame with command only (no payload).
     * Frame: [4 bytes totalSize] [4 bytes commandSize] [command bytes]
     */
    private static ByteBuf encodeBaseCommand(int type, int subFieldNumber, byte[] subMessage) {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        // type (field 1, varint)
        writeVarintField(cmdOut, FIELD_TYPE, type);
        // sub-message (field subFieldNumber, embedded)
        writeBytesField(cmdOut, subFieldNumber, subMessage);

        byte[] commandBytes = cmdOut.toByteArray();
        int commandSize = commandBytes.length;
        int totalSize = 4 + commandSize; // commandSize field (4 bytes) + command bytes

        ByteBuf frame = Unpooled.buffer(4 + totalSize);
        frame.writeInt(totalSize);
        frame.writeInt(commandSize);
        frame.writeBytes(commandBytes);
        return frame;
    }

    /**
     * Encode a full Pulsar frame with command + payload.
     * Frame: [4 bytes totalSize] [4 bytes commandSize] [command bytes] [payload bytes]
     */
    private static ByteBuf encodeBaseCommandWithPayload(int type, int subFieldNumber,
                                                         byte[] subMessage, byte[] payload) {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        // type (field 1, varint)
        writeVarintField(cmdOut, FIELD_TYPE, type);
        // sub-message (field subFieldNumber, embedded)
        writeBytesField(cmdOut, subFieldNumber, subMessage);

        byte[] commandBytes = cmdOut.toByteArray();
        int commandSize = commandBytes.length;
        int totalSize = 4 + commandSize + payload.length;

        ByteBuf frame = Unpooled.buffer(4 + totalSize);
        frame.writeInt(totalSize);
        frame.writeInt(commandSize);
        frame.writeBytes(commandBytes);
        frame.writeBytes(payload);
        return frame;
    }

    /**
     * Encode a base command with no sub-message (e.g., PONG).
     */
    private static ByteBuf encodeBaseCommandSimple(int type) {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        writeVarintField(cmdOut, FIELD_TYPE, type);

        byte[] commandBytes = cmdOut.toByteArray();
        int commandSize = commandBytes.length;
        int totalSize = 4 + commandSize;

        ByteBuf frame = Unpooled.buffer(4 + totalSize);
        frame.writeInt(totalSize);
        frame.writeInt(commandSize);
        frame.writeBytes(commandBytes);
        return frame;
    }

    // ======================== Protobuf primitive helpers ========================

    static int readVarint(byte[] data, int[] pos) {
        int result = 0;
        int shift = 0;
        while (pos[0] < data.length) {
            int b = data[pos[0]++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    static long readVarint64(byte[] data, int[] pos) {
        long result = 0;
        int shift = 0;
        while (pos[0] < data.length) {
            int b = data[pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private static String readString(byte[] data, int[] pos) {
        int len = readVarint(data, pos);
        String s = new String(data, pos[0], len, StandardCharsets.UTF_8);
        pos[0] += len;
        return s;
    }

    private static byte[] readBytes(byte[] data, int[] pos) {
        int len = readVarint(data, pos);
        byte[] bytes = new byte[len];
        System.arraycopy(data, pos[0], bytes, 0, len);
        pos[0] += len;
        return bytes;
    }

    private static byte[] readLengthDelimited(byte[] data, int[] pos) {
        return readBytes(data, pos);
    }

    private static void skipField(byte[] data, int[] pos, int wireType) {
        switch (wireType) {
            case 0: // varint
                while (pos[0] < data.length && (data[pos[0]++] & 0x80) != 0) {}
                break;
            case 1: // 64-bit
                pos[0] += 8;
                break;
            case 2: // length-delimited
                int len = readVarint(data, pos);
                pos[0] += len;
                break;
            case 5: // 32-bit
                pos[0] += 4;
                break;
            default:
                throw new IllegalArgumentException("Unknown wire type: " + wireType);
        }
    }

    static void writeVarint(ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    static void writeVarint64(ByteArrayOutputStream out, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            }
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    private static void writeVarintField(ByteArrayOutputStream out, int fieldNumber, int value) {
        writeVarint(out, (fieldNumber << 3) | 0); // wire type 0 = varint
        writeVarint(out, value);
    }

    static void writeVarintField64(ByteArrayOutputStream out, int fieldNumber, long value) {
        writeVarint(out, (fieldNumber << 3) | 0); // wire type 0 = varint
        writeVarint64(out, value);
    }

    private static void writeStringField(ByteArrayOutputStream out, int fieldNumber, String value) {
        writeVarint(out, (fieldNumber << 3) | 2); // wire type 2 = length-delimited
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    static void writeBytesField(ByteArrayOutputStream out, int fieldNumber, byte[] value) {
        writeVarint(out, (fieldNumber << 3) | 2); // wire type 2 = length-delimited
        writeVarint(out, value.length);
        out.write(value, 0, value.length);
    }
}
