package com.baafoo.server.api.dto;

import com.baafoo.core.model.Rule;
import java.util.List;

public class AgentPollResponseDto {
    public List<Rule> rules;
    public String mode;
    public long version;

    public AgentPollResponseDto rules(List<Rule> v) { this.rules = v; return this; }
    public AgentPollResponseDto mode(String v) { this.mode = v; return this; }
    public AgentPollResponseDto version(long v) { this.version = v; return this; }
}
