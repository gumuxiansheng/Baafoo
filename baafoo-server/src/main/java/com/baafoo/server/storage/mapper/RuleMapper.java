package com.baafoo.server.storage.mapper;

import com.baafoo.core.model.Rule;
import com.baafoo.core.model.RuleSet;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RuleMapper {

    List<Rule> listRules();

    long countRules(@Param("protocol") String protocol, @Param("keyword") String keyword);

    List<Rule> listRulesPaged(@Param("protocol") String protocol,
                              @Param("keyword") String keyword,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    Rule getRule(@Param("id") String id);

    int createRule(Rule rule);

    int updateRule(Rule rule);

    int deleteRule(@Param("id") String id);

    // --- Rule History ---

    int insertRuleHistory(@Param("ruleId") String ruleId,
                          @Param("ruleSnapshot") String ruleSnapshot,
                          @Param("createdAt") long createdAt);

    int deleteOldRuleHistory(@Param("ruleId") String ruleId, @Param("keepCount") int keepCount);

    String getLatestRuleSnapshot(@Param("ruleId") String ruleId);

    Long getLatestRuleHistoryId(@Param("ruleId") String ruleId);

    int deleteRuleHistoryById(@Param("ruleId") String ruleId, @Param("historyId") long historyId);

    int deleteRuleHistoryByRuleId(@Param("ruleId") String ruleId);

    // --- Rule Set ---

    List<RuleSet> listRuleSets();

    RuleSet createRuleSet(RuleSet ruleSet);

    int deleteRuleSet(@Param("id") String id);
}
