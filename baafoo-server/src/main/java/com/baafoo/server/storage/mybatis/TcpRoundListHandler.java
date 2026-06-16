package com.baafoo.server.storage.mybatis;

import com.baafoo.core.model.TcpRound;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;

import java.util.List;

@MappedJdbcTypes(JdbcType.VARCHAR)
public class TcpRoundListHandler extends JsonTypeHandler {

    private static final TypeReference<List<TcpRound>> TYPE_REF =
            new TypeReference<List<TcpRound>>() {};

    public TcpRoundListHandler() {
        super(TYPE_REF);
    }
}
