package com.baafoo.server.storage;

import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.model.Rule;

import java.util.List;

/**
 * Rule 聚合根的存储接口。
 *
 * <p>从 {@link StorageService} 拆分而来，使调用方可以只依赖 Rule 维度
 * 而非整个 {@link StorageService} 组合接口。</p>
 */
public interface RuleService {

    List<Rule> listRules();

    PaginatedResult<Rule> listRulesPaged(String protocol, String keyword, String environment, String host,
                                         String sortBy, String sortOrder, int page, int size);

    Rule getRule(String id);

    Rule createRule(Rule rule);

    Rule updateRule(String id, Rule update);

    boolean deleteRule(String id);

    boolean undoRule(String id);
}
