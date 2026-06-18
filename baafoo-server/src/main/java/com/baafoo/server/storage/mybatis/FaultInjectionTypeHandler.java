package com.baafoo.server.storage.mybatis;

import com.baafoo.core.model.FaultInjection;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;

/**
 * MyBatis TypeHandler for serializing/deserializing {@link FaultInjection}
 * as a JSON TEXT column.
 *
 * <p>Uses a {@link TypeReference} so Jackson can properly reconstruct the
 * generic {@code List<Fault>} inside {@link FaultInjection}.</p>
 */
@MappedJdbcTypes(JdbcType.VARCHAR)
public class FaultInjectionTypeHandler extends JsonTypeHandler {

    private static final TypeReference<FaultInjection> TYPE_REF =
            new TypeReference<FaultInjection>() {};

    public FaultInjectionTypeHandler() {
        super(TYPE_REF);
    }
}
