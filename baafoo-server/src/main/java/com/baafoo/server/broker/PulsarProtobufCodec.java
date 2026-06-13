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
 * <h3>PulsarBaseCommand field numbers (from PulsarApi.proto)</h3>
 * <pre>
 *   Type type                          = 1;
 *   Message message                    = 2;
 *   Ping ping                          = 3;
 *   Pong pong                          = 4;
 *   Connect connect                    = 5;
 *   Connected connected                = 6;
 *   Subscribe subscribe                = 7;
 *   Unsubscribe unsubscribe            = 8;
 *   Seek seek                          = 9;
 *   Producer producer                  = 10;
 *   Ack ack                            = 11;
 *   Close close                        = 12;
 *   Flow flow                          = 13;
 *   Send send                          = 14;
 *   SendError sendError                = 15;
 *   SendReceipt sendReceipt            = 16;
 *   LookupTopic lookupTopic            = 17;
 *   PartitionMetadataRequest partitionMetadataRequest = 18;
 *   PartitionMetadataResponse partitionMetadataResponse = 19;
 *   LookupTopicResponse lookupTopicResponse = 20;
 *   ConsumerStats consumerStats        = 21;
 *   GetTopicsOfNamespace getTopicsOfNamespace = 22;
 *   GetSchema getSchema                = 23;
 *   GetSchemaResponse getSchemaResponse = 24;
 *   AuthChallenge authChallenge        = 25;
 * </pre>
 *
 * <h3>Pulsar command Type enum values</h3>
 * <pre>
 *   MESSAGE = 0;  PING = 1;  PONG = 2;
 *   CONNECT = 3;  CONNECTED = 4;
 *   SUBSCRIBE = 5;  UNSUBSCRIBE = 6;  SEEK = 7;
 *   PRODUCER = 8;  ACK = 9;  CLOSE = 10;  FLOW = 11;
 *   SEND = 12;  SEND_ERROR = 13;  SEND_RECEIPT = 14;
 *   LOOKUP = 15;  PARTITIONED_METADATA = 16;
 *   PARTITIONED_METADATA_RESPONSE = 17;
 *   LOOKUP_RESPONSE = 18;
 *   CONSUMER_STATS = 19;
 *   GET_TOPICS_OF_NAMESPACE = 20;
 *   GET_SCHEMA = 21;  GET_SCHEMA_RESPONSE = 22;
 *   AUTH_CHALLENGE = 23;
 * </pre>
 */
final class PulsarProtobufCodec {

    // ---- PulsarBaseCommand Type enum ----
    static final int TYPE_MESSAGE = 0;
    static final int TYPE_PING = 1;
    static final int TYPE_PONG = 2;
    static final int TYPE_CONNECT = 3;
    static final int TYPE_CONNECTED = 4;
    static final int TYPE_SUBSCRIBE = 5;
    static final int TYPE_UNSUBSCRIBE = 6;
    static final int TYPE_SEEK = 7;
    static final int TYPE_PRODUCER = 8;
    static final int TYPE_ACK = 9;
    static final int TYPE_CLOSE = 10;
    static final int TYPE_FLOW = 11;
    static final int TYPE_SEND = 12;
    static final int TYPE_SEND_ERROR = 13;
    static final int TYPE_SEND_RECEIPT = 14;
    static final int TYPE_LOOKUP = 15;
    static final int TYPE_PARTITIONED_METADATA = 16;
    static final int TYPE_PARTITIONED_METADATA_RESPONSE = 17;
    static final int TYPE_LOOKUP_RESPONSE = 18;
    static final int TYPE_CONSUMER_STATS = 19;
    static final int TYPE_GET_TOPICS_OF_NAMESPACE = 20;
    static final int TYPE_GET_SCHEMA = 21;
    static final int TYPE_GET_SCHEMA_RESPONSE = 22;
    static final int TYPE_AUTH_CHALLENGE = 23;
    static final int TYPE_PRODUCER_SUCCESS = 24;
    static final int TYPE_SUCCESS = 25;

    // ---- PulsarBaseCommand field numbers ----
    static final int FIELD_TYPE = 1;
    static final int FIELD_CONNECT = 5;
    static final int FIELD_CONNECTED = 6;
    static final int FIELD_SUBSCRIBE = 7;
    static final int FIELD_PRODUCER = 10;
    static final int FIELD_FLOW = 13;
    static final int FIELD_SEND = 14;
    static final int FIELD_SEND_RECEIPT = 16;
    static final int FIELD_LOOKUP_TOPIC = 17;
    static final int FIELD_PARTITION_METADATA_REQUEST = 18;
    static final int FIELD_LOOKUP_TOPIC_RESPONSE = 20;
    static final int FIELD_GET_TOPICS_OF_NAMESPACE = 22;

    // ---- Sub-message field numbers ----
    // Connect: auth_method_name=1, auth_data=2, protocol_version=3, client_version=4
    // Connected: server_version=1, protocol_version=2
    // LookupTopic: topic=1, authoritative=2, requestId=3
    // LookupTopicResponse: brokerServiceUrl=1, response=2, requestId=3
    //   LookupType enum: Redirect=0, Connect=1, Failed=2
    // PartitionMetadataRequest: topic=1, requestId=2
    // PartitionMetadataResponse: partitions=1, requestId=2
    // Producer: topic=1, producer_name=2, requestId=3
    // Send: producer_name=1, sequence_id=2, num_messages=3
    // SendReceipt: producer_name=1, sequence_id=2, message_id=3, highest_sequence_id=4
    //   MessageIdData: ledgerId=1, entryId=2, partition=3, batch_index=4
    // Subscribe: topic=1, subscription=2, subType=3, consumer_name=4, requestId=5
    //   SubType enum: Exclusive=0, Shared=1, Failover=2
    // Success: requestId=1, schema=2
    // GetTopicsOfNamespace: namespace=1, requestId=2
    // GetTopicsOfNamespaceResponse: topics=1 (repeated), requestId=2
    // Message: ... (complex, handled separately)

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
                case FIELD_LOOKUP_TOPIC:
                    cmd.lookupTopicData = readLengthDelimited(data, pos);
                    parseLookupTopic(cmd);
                    break;
                case FIELD_PARTITION_METADATA_REQUEST:
                    cmd.partitionMetadataRequestData = readLengthDelimited(data, pos);
                    parsePartitionMetadataRequest(cmd);
                    break;
                case FIELD_GET_TOPICS_OF_NAMESPACE:
                    cmd.getTopicsOfNamespaceData = readLengthDelimited(data, pos);
                    parseGetTopicsOfNamespace(cmd);
                    break;
                case FIELD_FLOW:
                    cmd.flowData = readLengthDelimited(data, pos);
                    parseFlow(cmd);
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
                case 1: // auth_method_name
                    cmd.authMethodName = readString(data, pos);
                    break;
                case 2: // auth_data
                    cmd.authData = readBytes(data, pos);
                    break;
                case 3: // protocol_version
                    cmd.protocolVersion = readVarint(data, pos);
                    break;
                case 4: // client_version
                    cmd.clientVersion = readString(data, pos);
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
                case 4: // consumer_name
                    cmd.consumerName = readString(data, pos);
                    break;
                case 5: // requestId
                    cmd.requestId = readVarint(data, pos);
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
                case 2: // producer_name
                    cmd.producerName = readString(data, pos);
                    break;
                case 3: // requestId
                    cmd.requestId = readVarint(data, pos);
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
                case 1: // producer_name
                    cmd.producerName = readString(data, pos);
                    break;
                case 2: // sequence_id
                    cmd.sequenceId = readVarint64(data, pos);
                    break;
                case 3: // num_messages (batch)
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
                case 2: // authoritative
                    cmd.authoritative = readVarint(data, pos) != 0;
                    break;
                case 3: // requestId
                    cmd.requestId = readVarint(data, pos);
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
                case 2: // requestId
                    cmd.requestId = readVarint(data, pos);
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
                case 1: // namespace
                    cmd.namespaceName = readString(data, pos);
                    break;
                case 2: // requestId
                    cmd.requestId = readVarint(data, pos);
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
    static ByteBuf encodeLookupTopicResponse(String brokerServiceUrl, int requestId) {
        byte[] responseMsg = encodeLookupTopicResponseMessage(brokerServiceUrl, requestId);
        return encodeBaseCommand(TYPE_LOOKUP_RESPONSE, FIELD_LOOKUP_TOPIC_RESPONSE, responseMsg);
    }

    /**
     * Encode a PARTITIONED_METADATA_RESPONSE (non-partitioned: partitions=0).
     */
    static ByteBuf encodePartitionMetadataResponse(int partitions, int requestId) {
        byte[] responseMsg = encodePartitionMetadataResponseMessage(partitions, requestId);
        return encodeBaseCommand(TYPE_PARTITIONED_METADATA_RESPONSE, 19, responseMsg);
    }

    /**
     * Encode a PRODUCER_SUCCESS response.
     * Type.PRODUCER_SUCCESS = 24, ProducerSuccess sub-message at field 29.
     */
    static ByteBuf encodeProducerSuccess(String producerName, int requestId) {
        byte[] successMsg = encodeProducerSuccessMessage(producerName, requestId);
        return encodeBaseCommand(TYPE_PRODUCER_SUCCESS, 29, successMsg);
    }

    /**
     * Encode a SEND_RECEIPT response.
     */
    static ByteBuf encodeSendReceipt(String producerName, long sequenceId, long ledgerId, long entryId) {
        byte[] receiptMsg = encodeSendReceiptMessage(producerName, sequenceId, ledgerId, entryId);
        return encodeBaseCommand(TYPE_SEND_RECEIPT, FIELD_SEND_RECEIPT, receiptMsg);
    }

    /**
     * Encode a SUCCESS response (for subscribe ack).
     * Type.SUCCESS = 25, Success sub-message at field 28 in PulsarBaseCommand.
     */
    static ByteBuf encodeSuccess(int requestId) {
        byte[] successMsg = encodeSuccessMessage(requestId);
        return encodeBaseCommand(TYPE_SUCCESS, 28, successMsg);
    }

    /**
     * Encode a SUBSCRIBE success response.
     * In Pulsar protocol (2.8+), the broker responds to SUBSCRIBE with a
     * SUCCESS command (type=25) containing the Success sub-message (field=28).
     */
    static ByteBuf encodeSubscribeSuccess(int requestId) {
        byte[] successMsg = encodeSuccessMessage(requestId);
        return encodeBaseCommand(TYPE_SUCCESS, 28, successMsg);
    }

    /**
     * Encode a GET_TOPICS_OF_NAMESPACE_RESPONSE.
     */
    static ByteBuf encodeGetTopicsOfNamespaceResponse(List<String> topics, int requestId) {
        byte[] responseMsg = encodeGetTopicsOfNamespaceResponseMessage(topics, requestId);
        return encodeBaseCommand(TYPE_GET_TOPICS_OF_NAMESPACE, 27, responseMsg);
    }

    /**
     * Encode a MESSAGE command (broker → consumer).
     * The payload (message body) is sent as the frame payload, not inside the command.
     */
    static ByteBuf encodeMessage(long ledgerId, long entryId, int partition,
                                  String topic, int consumerId,
                                  byte[] payload) {
        byte[] messageCmd = encodeMessageCommand(ledgerId, entryId, partition, topic, consumerId);
        return encodeBaseCommandWithPayload(TYPE_MESSAGE, 2, messageCmd, payload);
    }

    /**
     * Encode a PONG response.
     */
    static ByteBuf encodePong() {
        return encodeBaseCommandSimple(TYPE_PONG);
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

    private static byte[] encodeLookupTopicResponseMessage(String brokerServiceUrl, int requestId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // brokerServiceUrl (field 1, string)
        writeStringField(out, 1, brokerServiceUrl);
        // response (field 2, enum) = Connect (1)
        writeVarintField(out, 2, LOOKUP_TYPE_CONNECT);
        // requestId (field 3, varint)
        writeVarintField(out, 3, requestId);
        return out.toByteArray();
    }

    private static byte[] encodePartitionMetadataResponseMessage(int partitions, int requestId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // partitions (field 1, varint) — 0 means non-partitioned
        writeVarintField(out, 1, partitions);
        // requestId (field 2, varint)
        writeVarintField(out, 2, requestId);
        return out.toByteArray();
    }

    private static byte[] encodeProducerSuccessMessage(String producerName, int requestId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // requestId (field 1, varint)
        writeVarintField(out, 1, requestId);
        // producer_name (field 2, string)
        writeStringField(out, 2, producerName);
        return out.toByteArray();
    }

    private static byte[] encodeSendReceiptMessage(String producerName, long sequenceId,
                                                    long ledgerId, long entryId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // producer_name (field 1, string)
        writeStringField(out, 1, producerName);
        // sequence_id (field 2, varint)
        writeVarintField64(out, 2, sequenceId);
        // message_id (field 3, embedded MessageIdData)
        byte[] messageIdData = encodeMessageIdData(ledgerId, entryId);
        writeBytesField(out, 3, messageIdData);
        // highest_sequence_id (field 4, varint)
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

    private static byte[] encodeSuccessMessage(int requestId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // requestId (field 1, varint)
        writeVarintField(out, 1, requestId);
        return out.toByteArray();
    }

    private static byte[] encodeGetTopicsOfNamespaceResponseMessage(List<String> topics, int requestId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // topics (field 1, repeated string)
        for (String topic : topics) {
            writeStringField(out, 1, topic);
        }
        // requestId (field 2, varint)
        writeVarintField(out, 2, requestId);
        return out.toByteArray();
    }

    private static byte[] encodeMessageCommand(long ledgerId, long entryId, int partition,
                                                String topic, int consumerId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Message command fields:
        // consumer_id (field 6, varint)
        writeVarintField(out, 6, consumerId);
        // message_id (field 20, embedded MessageIdData) — actually this might be field 7
        // Let me check the Message proto definition...
        //
        // Message proto:
        //   optional MessageIdData message_id = 1;
        //   optional int32 redelivery_count = 2;
        //   repeated int64 ack_set = 3;
        //   optional MessageIdData encryption_keys = 4;
        //   optional CompressionType compression = 5;
        //   optional int32 uncompressed_size = 6;
        //   optional int32 consumer_id = 7;
        //   ...
        // Wait, I think the field numbers are different. Let me use the correct ones.
        //
        // Actually, looking at PulsarApi.proto more carefully:
        // message Message {
        //   optional MessageIdData message_id = 1;
        //   optional int32 redelivery_count = 2;
        //   repeated int64 ack_set = 3;
        //   optional string encryption_keys = 4;
        //   optional CompressionType compression = 5;
        //   optional int32 uncompressed_size = 6;
        //   optional int32 consumer_id = 7;
        //   ...
        // }

        // message_id (field 1, embedded MessageIdData)
        byte[] messageIdData = encodeMessageIdData(ledgerId, entryId);
        writeBytesField(out, 1, messageIdData);
        // compression (field 5, enum) = NONE (0)
        writeVarintField(out, 5, 0);
        // consumer_id (field 7, varint)
        writeVarintField(out, 7, consumerId);
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

    private static void writeVarintField64(ByteArrayOutputStream out, int fieldNumber, long value) {
        writeVarint(out, (fieldNumber << 3) | 0); // wire type 0 = varint
        writeVarint64(out, value);
    }

    private static void writeStringField(ByteArrayOutputStream out, int fieldNumber, String value) {
        writeVarint(out, (fieldNumber << 3) | 2); // wire type 2 = length-delimited
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeBytesField(ByteArrayOutputStream out, int fieldNumber, byte[] value) {
        writeVarint(out, (fieldNumber << 3) | 2); // wire type 2 = length-delimited
        writeVarint(out, value.length);
        out.write(value, 0, value.length);
    }
}
