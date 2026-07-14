package com.baafoo.server.storage;

import com.baafoo.core.model.MqRelationship;

import java.util.List;

/**
 * MqRelationship 聚合根的存储接口。
 *
 * <p>描述 MQ 协议间（Kafka/Pulsar/JMS）的消息流转关系。</p>
 */
public interface MqRelationshipService {

    List<MqRelationship> listMqRelationships();

    List<MqRelationship> listMqRelationshipsByFrom(String fromProtocol, String fromTopic);

    MqRelationship getMqRelationship(String id);

    MqRelationship createMqRelationship(MqRelationship relationship);

    MqRelationship updateMqRelationship(String id, MqRelationship update);

    boolean deleteMqRelationship(String id);
}
