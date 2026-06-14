package com.baafoo.server.broker;

/**
 * Parsed Pulsar command with all relevant fields extracted.
 *
 * <p>Only the fields needed for the mock broker are included.
 * Fields are populated by {@link PulsarProtobufCodec#decodeCommand(byte[])}
 * based on the command type.</p>
 */
class PulsarCommand {

    // ---- Command type (from PulsarBaseCommand.type field) ----
    int type;

    // ---- Raw sub-message data (kept for reference) ----
    byte[] connectData;
    byte[] subscribeData;
    byte[] producerData;
    byte[] sendData;
    byte[] lookupTopicData;
    byte[] partitionMetadataRequestData;
    byte[] getTopicsOfNamespaceData;
    byte[] flowData;
    byte[] ackData;

    // ---- Connect fields ----
    String authMethodName;
    byte[] authData;
    int protocolVersion;
    String clientVersion;

    // ---- Subscribe fields ----
    String topic;
    String subscription;
    int subType;
    String consumerName;
    long requestId;

    // ---- Producer fields ----
    long producerId;
    String producerName;
    // topic is shared with Subscribe

    // ---- Send fields ----
    long sequenceId;
    int numMessages;

    // ---- LookupTopic fields ----
    boolean authoritative;

    // ---- PartitionMetadataRequest fields ----
    // topic and requestId are shared

    // ---- GetTopicsOfNamespace fields ----
    String namespaceName;

    // ---- Flow fields ----
    int messagePermits;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PulsarCommand{type=");
        sb.append(type);
        if (topic != null) sb.append(", topic='").append(topic).append("'");
        if (producerName != null) sb.append(", producerName='").append(producerName).append("'");
        if (subscription != null) sb.append(", subscription='").append(subscription).append("'");
        if (consumerName != null) sb.append(", consumerName='").append(consumerName).append("'");
        sb.append(", requestId=").append(requestId);
        sb.append(", sequenceId=").append(sequenceId);
        if (namespaceName != null) sb.append(", namespace='").append(namespaceName).append("'");
        sb.append("}");
        return sb.toString();
    }
}
