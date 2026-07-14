package com.baafoo.server.storage;

import com.baafoo.core.model.Environment;

import java.util.List;

/**
 * Environment 聚合根的存储接口。
 *
 * <p>包含 Environment CRUD 以及 Rule-Environment 关联操作
 * （{@link #associateRulesToEnvironment} / {@link #dissociateRulesFromEnvironment}），
 * 因为关联操作的主体是 Environment 侧。</p>
 */
public interface EnvironmentService {

    List<Environment> listEnvironments();

    Environment getEnvironment(String id);

    Environment getEnvironmentByName(String name);

    Environment createEnvironment(Environment env);

    Environment updateEnvironment(String id, Environment update);

    boolean deleteEnvironment(String id);

    void associateRulesToEnvironment(String envName, List<String> ruleIds);

    void dissociateRulesFromEnvironment(String envName, List<String> ruleIds);
}
