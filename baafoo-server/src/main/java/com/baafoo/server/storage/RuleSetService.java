package com.baafoo.server.storage;

import com.baafoo.core.model.RuleSet;

import java.util.List;

/**
 * RuleSet 聚合根的存储接口。
 *
 * <p>RuleSet 是规则的集合，语义上与 {@link RuleService} 相近但独立持久化，
 * 因此拆为单独接口。</p>
 */
public interface RuleSetService {

    List<RuleSet> listRuleSets();

    RuleSet createRuleSet(RuleSet ruleSet);

    boolean deleteRuleSet(String id);
}
