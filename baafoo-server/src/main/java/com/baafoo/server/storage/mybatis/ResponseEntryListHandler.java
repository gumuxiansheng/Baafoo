package com.baafoo.server.storage.mybatis;

import com.baafoo.core.model.ResponseEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;

import java.util.List;

@MappedJdbcTypes(JdbcType.VARCHAR)
public class ResponseEntryListHandler extends JsonTypeHandler {

    private static final TypeReference<List<ResponseEntry>> TYPE_REF =
            new TypeReference<List<ResponseEntry>>() {};

    public ResponseEntryListHandler() {
        super(TYPE_REF);
    }
}
