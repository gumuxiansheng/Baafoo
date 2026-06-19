package com.baafoo.server.storage.mapper;

import com.baafoo.core.model.MqRelationship;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MqRelationshipMapper {

    List<MqRelationship> listMqRelationships();

    List<MqRelationship> listMqRelationshipsByFrom(
            @Param("fromProtocol") String fromProtocol,
            @Param("fromTopic") String fromTopic);

    MqRelationship getMqRelationship(@Param("id") String id);

    void createMqRelationship(MqRelationship relationship);

    void updateMqRelationship(MqRelationship relationship);

    int deleteMqRelationship(@Param("id") String id);
}
