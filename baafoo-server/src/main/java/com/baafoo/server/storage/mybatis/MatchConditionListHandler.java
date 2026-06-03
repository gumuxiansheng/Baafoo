package com.baafoo.server.storage.mybatis;

import com.baafoo.core.model.MatchCondition;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;

import java.util.List;

@MappedJdbcTypes(JdbcType.VARCHAR)
public class MatchConditionListHandler extends JsonTypeHandler {

    private static final TypeReference<List<MatchCondition>> TYPE_REF =
            new TypeReference<List<MatchCondition>>() {};

    public MatchConditionListHandler() {
        super(TYPE_REF);
    }
}
